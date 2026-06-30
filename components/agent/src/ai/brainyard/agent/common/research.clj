;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.research
  "Research-agent helpers — the surviving READ/DERIVE seams + auto-finalize
   backstop (lightweight redesign, docs/design/research-agent-design.md).

   Orchestration is LLM judgment and persistence is markdown the model authors
   directly, so the structured-construction helpers (research$bootstrap with its
   acceptance vector-of-maps, research$update-status, research$write-verdict's
   frontmatter emission, research$append-log, research$index-append) are RETIRED.
   The agent now: `bash mkdir -p` + `write-file` the dossier files from templates;
   tracks acceptance as a markdown CHECKLIST (the shared todo substrate, §5) and
   flips a criterion's status index-free by its stable id via `update-file`;
   appends findings.log + INDEX.md with `write-file :append`.

   What stays is pure mechanism a machine does better than the model:
   - research$id            — deterministic kebab id (the resume key).
   - research$resume?        — parse dossier frontmatter + the acceptance
     checklist statuses (dual-reads the legacy frontmatter acceptance block).
   - research$verdict-outcome — read the acceptance checklist, derive the
     outcome, and ENFORCE the :achieved guard (the one load-bearing bit of the
     old write-verdict, now a read-side validator the model calls BEFORE
     write-file-ing verdict.md).

   The sibling read-helpers (plan$/todo$/exec$read-dossier, eval$read-verdict,
   edit$read-record) are bound in research_agent.clj, not here."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private results-base ".brainyard/agents/research-agent")
(def ^:private index-rel (str results-base "/INDEX.md"))

(def ^:private slug-stopwords
  #{"a" "an" "the" "is" "are" "and" "or" "of" "in" "on" "at" "to" "for"
    "by" "with" "from" "as" "but" "if" "then" "than" "so"
    "what" "where" "when" "who" "whom" "why" "how" "which"
    "do" "does" "did" "can" "could" "would" "should" "shall" "will"
    "this" "that" "these" "those" "it" "its" "we" "they" "you" "i"
    "be" "been" "being" "was" "were" "have" "has" "had"})

(def ^:private terminal-statuses
  "Statuses a VERDICT may carry. `:in-progress` is mid-flight and must never
   be written as a verdict / INDEX entry."
  #{:achieved :partial :abandoned})

(def ^:private valid-criterion-statuses
  #{:open :satisfied :partial :descoped :contradicted})

;; ============================================================================
;; Time formatters (extracted as private fns so tests can with-redefs)
;; ============================================================================

(defn- now-iso [] (str (java.time.Instant/now)))

(defn- now-yyyy-mm-dd-hh-mm []
  (-> (subs (now-iso) 0 16)
      (str/replace "T" " ")))

;; ============================================================================
;; Slugify — mirrors explore$slug (kept local to avoid coupling)
;; ============================================================================

(defn- slugify
  [question max-chars]
  (let [tokens (-> (str question)
                   str/lower-case
                   (str/replace #"[^a-z0-9]+" "-")
                   (str/split #"-"))
        kept   (->> tokens
                    (remove #(or (str/blank? %) (slug-stopwords %)))
                    seq)
        joined (if kept (str/join "-" kept) "research")]
    (subs joined 0 (min max-chars (count joined)))))

;; ============================================================================
;; research$id — deterministic kebab-case research id
;; ============================================================================

(defcommand research$id
  "Deterministic kebab-case research id from a question (stable across re-runs)."
  (fn [& {:keys [question max-chars]
          :or   {max-chars 60}}]
    (cond
      (not (string? question))
      {:error ":question is required (string)"}

      (or (not (integer? max-chars)) (<= max-chars 0))
      {:error ":max-chars must be a positive integer"}

      :else
      {:slug (slugify question max-chars)}))
  :input-schema  [:map
                  [:question  [:string {:desc "Research question to slugify"}]]
                  [:max-chars {:optional true} [:int {:desc "Cap on slug length (default 60)"}]]]
  :output-schema [:map
                  [:slug  [:string {:desc "Kebab-case research id"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; Frontmatter + acceptance-checklist parsing (read seams)
;; ============================================================================

(defn- read-frontmatter-lines
  "Read lines from the file until the second `---` (frontmatter close).
   Returns the inner lines, or nil if the file doesn't start with `---`."
  [^java.io.File file]
  (with-open [r (io/reader file)]
    (let [reader (java.io.BufferedReader. r)
          first-line (.readLine reader)]
      (when (= "---" first-line)
        (loop [acc []]
          (let [ln (.readLine reader)]
            (cond
              (nil? ln)    nil
              (= "---" ln) acc
              :else        (recur (conj acc ln)))))))))

(defn- extract-flat
  "Targeted regex for `key: <value>` lines (single line, no nested blocks)."
  [lines key]
  (some (fn [ln]
          (when-let [[_ v] (re-matches (re-pattern (str "^" key ":\\s*(.*)$")) ln)]
            (let [v (str/trim v)]
              (cond
                (and (str/starts-with? v "\"") (str/ends-with? v "\""))
                (subs v 1 (dec (count v)))
                (= "null" v) nil
                :else v))))
        lines))

(defn- parse-acceptance-checklist
  "Parse the §5 acceptance checklist (acceptance.md) → `{<id-kw> <status-kw>}`.
   Each criterion line is `- [<box>] aN (<status>) — <text>`; the `(status)`
   token is authoritative. Lenient — non-matching lines are skipped; an unknown
   status word degrades to :open."
  [^String content]
  (reduce
   (fn [acc ln]
     (if-let [[_ id status] (re-find #"(?m)^\s*-\s*\[.\]\s+(\S+)\s+\((\w+)\)" ln)]
       (let [st (keyword status)]
         (assoc acc (keyword id) (if (valid-criterion-statuses st) st :open)))
       acc))
   {}
   (str/split-lines (str content))))

(defn- extract-acceptance-state-legacy
  "Dual-read: parse a legacy `acceptance:` frontmatter block (the pre-redesign
   `- id:/text:/status:` shape) from dossier.md lines → `{<id-kw> <status-kw>}`."
  [lines]
  (loop [ls lines, in? false, pending {}, acc {}]
    (if (empty? ls)
      (if-let [pid (:id pending)]
        (assoc acc (keyword pid) (or (some-> (:status pending) keyword) :open))
        acc)
      (let [ln (first ls), r (rest ls)]
        (cond
          (re-matches #"^acceptance:\s*$" ln)
          (recur r true {} acc)

          (and in? (re-matches #"^[a-z_]+:.*$" ln))
          (if-let [pid (:id pending)]
            (assoc acc (keyword pid) (or (some-> (:status pending) keyword) :open))
            acc)

          (and in? (re-matches #"^\s*- id:\s*(\S+)\s*$" ln))
          (let [[_ id-str] (re-matches #"^\s*- id:\s*(\S+)\s*$" ln)
                acc' (if-let [pid (:id pending)]
                       (assoc acc (keyword pid) (or (some-> (:status pending) keyword) :open))
                       acc)]
            (recur r true {:id id-str} acc'))

          (and in? (re-matches #"^\s+status:\s*(\S+)\s*$" ln))
          (let [[_ s] (re-matches #"^\s+status:\s*(\S+)\s*$" ln)]
            (recur r true (assoc pending :status s) acc))

          :else
          (recur r in? pending acc))))))

(defn- read-acceptance-state
  "Resolve the acceptance state for a research id → `{<id-kw> <status-kw>}`,
   or nil when neither the checklist nor a legacy frontmatter block exists.
   Prefers the §5 checklist (acceptance.md); falls back to the legacy
   dossier.md frontmatter acceptance block."
  [base-dir id]
  (let [acc-file (io/file base-dir results-base id "acceptance.md")
        checklist (when (.isFile acc-file)
                    (parse-acceptance-checklist (slurp acc-file)))]
    (if (seq checklist)
      checklist
      (let [dossier (io/file base-dir results-base id "dossier.md")]
        (when (.isFile dossier)
          (when-let [lines (read-frontmatter-lines dossier)]
            (let [legacy (extract-acceptance-state-legacy lines)]
              (when (seq legacy) legacy))))))))

;; ============================================================================
;; research$resume? — cheap probe (does the dossier already exist + its state?)
;; ============================================================================

(defcommand research$resume?
  "Cheap probe: does a research dossier exist for this id, and what is its state?"
  (fn [& {:keys [id base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? id))
      {:error ":id is required (string)"}

      :else
      (let [dossier (io/file base-dir results-base id "dossier.md")]
        (if-not (.isFile dossier)
          {:exists? false}
          (let [lines (read-frontmatter-lines dossier)]
            (if (nil? lines)
              {:exists? false :error "dossier.md present but lacks frontmatter"}
              (let [status (some-> (extract-flat lines "status") keyword)
                    last-i (some-> (extract-flat lines "last_iteration")
                                   (str)
                                   (Integer/parseInt))
                    accept (or (read-acceptance-state base-dir id) {})]
                {:exists?          true
                 :status           (or status :in-progress)
                 :last-iteration   (or last-i 1)
                 :acceptance-state accept})))))))
  :input-schema  [:map
                  [:id       [:string {:desc "Research id (from research$id)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:exists?          [:boolean {:desc "true if the dossier directory exists with a dossier.md"}]]
                  [:status           {:optional true} [:keyword {:desc "Overall research status from frontmatter"}]]
                  [:last-iteration   {:optional true} [:int     {:desc "last_iteration from frontmatter"}]]
                  [:acceptance-state {:optional true} [:map     {:desc "{<criterion-id-kw> <status-kw>} from the acceptance checklist"}]]
                  [:error            {:optional true} [:string  {:desc "Error if validation failed"}]]])

;; ============================================================================
;; research$verdict-outcome — read acceptance, derive outcome, :achieved guard
;; ============================================================================

(defn- derive-outcome
  "Derive a verdict outcome from the acceptance-state map.
     - any :open               → :in-progress (not finalizable)
     - all :satisfied/:descoped → :achieved
     - all :contradicted        → :abandoned
     - any other mix            → :partial"
  [state]
  (let [vs (set (vals state))]
    (cond
      (empty? vs)                          :in-progress
      (contains? vs :open)                 :in-progress
      (every? #{:satisfied :descoped} vs)  :achieved
      (every? #{:contradicted} vs)         :abandoned
      :else                                :partial)))

(defcommand research$verdict-outcome
  "Read the acceptance checklist, derive the verdict outcome, and enforce the :achieved guard. READ-ONLY — call before write-file-ing verdict.md."
  (fn [& {:keys [id base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? id))
      {:error ":id is required (string)"}

      :else
      (let [state (read-acceptance-state base-dir id)]
        (if (nil? state)
          {:error (str "No acceptance checklist for id " id
                       " (acceptance.md missing and no legacy frontmatter). "
                       "Bootstrap the dossier first.")}
          (let [outcome  (derive-outcome state)
                ok?      (and (seq state)
                              (every? #{:satisfied :descoped} (vals state)))
                blockers (vec (for [[cid s] state
                                    :when (not (#{:satisfied :descoped} s))]
                                (str (name cid) ":" (name s))))]
            (mulog/log ::research.verdict-outcome
                       :id id :outcome outcome :achieved-ok? ok?
                       :n-criteria (count state))
            {:outcome            outcome
             :achieved-ok?       ok?
             :blockers           blockers
             :acceptance-outcome (into {} (map (fn [[k v]] [k (name v)]) state))})))))
  :input-schema  [:map
                  [:id       [:string {:desc "Research id"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:outcome            {:optional true} [:keyword {:desc "Derived outcome: :achieved | :partial | :abandoned | :in-progress"}]]
                  [:achieved-ok?       {:optional true} [:boolean {:desc "true iff every criterion is :satisfied or :descoped (the :achieved guard)"}]]
                  [:blockers           {:optional true} [:vector {:desc "Criteria blocking :achieved, as \"aN:status\" strings"} :string]]
                  [:acceptance-outcome {:optional true} [:map {:desc "{<criterion-id-kw> <status-name-string>} for the verdict frontmatter"}]]
                  [:error              {:optional true} [:string {:desc "Error if validation failed or no acceptance checklist"}]]])

;; ============================================================================
;; Public roster (for research-agent's :agent-tools)
;; ============================================================================

(def research-helpers
  "The surviving research READ/DERIVE seams: the deterministic id (resume key),
   the resume probe (frontmatter + acceptance-checklist parse), and the verdict
   outcome derivation + :achieved guard. research-agent appends these so the SCI
   sandbox auto-binds them. The structured-construction helpers (bootstrap,
   update-status, write-verdict, append-log, index-append) are retired — the
   model authors the dossier markdown directly and flips acceptance via the
   shared todo substrate's index-free checklist edit."
  [#'research$id
   #'research$resume?
   #'research$verdict-outcome])

;; ============================================================================
;; INDEX append (append-only; backs the auto-finalize backstop)
;; ============================================================================

(defn- append-index!
  "Append one INDEX.md line for a finalized research thread (append-only)."
  [base-dir {:keys [id status one-line]}]
  (let [trimmed (-> (str one-line) (str/replace #"\s+" " ") str/trim)
        capped  (if (> (count trimmed) 200) (str (subs trimmed 0 197) "…") trimmed)
        line    (str "- " (now-yyyy-mm-dd-hh-mm)
                     " [" id "](" id "/) — "
                     (str/upper-case (name status)) " · " capped "\n")
        file    (io/file base-dir index-rel)]
    (.mkdirs (.getParentFile file))
    (spit file line :append true)
    (mulog/log ::research.index :id id :status status)
    line))

;; ============================================================================
;; Auto-finalize backstop
;;
;; The instruction tells the LLM to research$verdict-outcome → write-file
;; verdict.md → append INDEX → end :answer with `Saved research dossier:`.
;; Sonnet+ follows it; smaller models skip the FINALIZE step. This gated
;; `:agent.ask/finalize` hook is the safety net: it derives the outcome, writes
;; verdict.md from a template, appends INDEX, and injects the absent
;; `Saved research dossier:` line.
;;
;; STRICT trigger: only fires when the dossier exists AND no acceptance
;; criterion is :open (else the LLM is mid-flight / in CLARIFY mode) AND
;; verdict.md doesn't already exist. Never retroactively bootstraps.
;; ============================================================================

(def ^:private saved-research-prefix
  "If :answer already contains this prefix, the LLM finalized itself; no-op."
  "Saved research dossier:")

(defn- research-agent? [agent]
  (try
    (= :research-agent (proto/defagent-type agent))
    (catch Throwable _ false)))

(defn- already-finalized? [^String answer]
  (boolean (and (string? answer)
                (str/includes? answer saved-research-prefix))))

(defn- render-verdict-md
  "Compose verdict.md (frontmatter + ## Verdict body) for the backstop."
  [{:keys [id status iterations acceptance-outcome narrative]}]
  (str "---\n"
       "research_id: " id "\n"
       "status: " (name status) "\n"
       "terminated: " (now-iso) "\n"
       (when iterations (str "iterations: " iterations "\n"))
       (when (seq acceptance-outcome)
         (str "acceptance_outcome:\n"
              (str/join "" (for [[k v] acceptance-outcome]
                             (str "  " (name k) ": " (name v) "\n")))))
       "---\n\n"
       "## Verdict\n"
       (str/trim (str narrative))
       "\n"))

(defn- one-line-summary
  "Distill a one-line summary from the answer (drop frontmatter, the trailing
   handoff line, headings/tables/fences; collapse whitespace; cap)."
  [^String answer max-chars]
  (let [stripped (-> answer
                     (str/replace #"^---\n[\s\S]*?\n---\n" "")
                     (str/replace #"(?m)^Saved research dossier:.*$" "")
                     str/trim)
        paragraphs (->> (str/split stripped #"\n\n")
                        (map str/trim)
                        (remove str/blank?))
        prose? (fn [p]
                 (and (not (str/starts-with? p "#"))
                      (not (str/starts-with? p "|"))
                      (not (str/starts-with? p "```"))
                      (not (re-matches #"^[-*_]{3,}$" p))))
        chosen (or (first (filter prose? paragraphs))
                   (first paragraphs)
                   "")
        flat   (-> chosen
                   (str/replace #"\s+" " ")
                   (str/replace #"^#+\s*" "")
                   str/trim)]
    (subs flat 0 (min max-chars (count flat)))))

(defn- finalize-config
  "Per-turn override of the auto-finalize behavior via config."
  [agent]
  (try
    {:enabled? (boolean (config/get-config agent :research-auto-finalize))}
    (catch Throwable _
      {:enabled? true})))

(defn research-auto-finalize
  "Auto-write verdict.md + append INDEX.md when research-agent emits a non-blank
   answer without finalizing itself, then inject the absent
   `Saved research dossier:` line. Strict trigger: only fires when the dossier
   exists AND no acceptance criterion is :open AND verdict.md doesn't already
   exist. Defensive — failures logged, never re-thrown."
  [{:keys [agent input result]}]
  (try
    (when (and (research-agent? agent) (map? result))
      (let [answer (:answer result)
            {:keys [enabled?]} (finalize-config agent)]
        (when (and enabled?
                   (string? answer)
                   (not (str/blank? answer))
                   (not (already-finalized? answer)))
          (let [question (or (when (string? input) input)
                             (some-> input :question str)
                             "")
                rid      (when-not (str/blank? question) (slugify question 60))]
            (when rid
              (let [base-dir (config/project-dir)
                    dir      (io/file base-dir results-base rid)]
                (cond
                  (not (.isDirectory dir))
                  (mulog/log ::research.no-dossier-skip :id rid :answer-chars (count answer))

                  (.isFile (io/file dir "verdict.md"))
                  (mulog/log ::research.auto-finalize-skip :id rid :reason :verdict-exists)

                  :else
                  (let [state   (or (read-acceptance-state base-dir rid) {})
                        outcome (derive-outcome state)]
                    (cond
                      (or (empty? state) (= :in-progress outcome))
                      (mulog/log ::research.auto-finalize-skip
                                 :id rid
                                 :reason (cond
                                           (empty? state) :no-acceptance
                                           :else          :open-criteria-remain)
                                 :acceptance-state state)

                      :else
                      (let [dossier   (io/file dir "dossier.md")
                            last-iter (when (.isFile dossier)
                                        (some-> (read-frontmatter-lines dossier)
                                                (extract-flat "last_iteration")
                                                (str) Integer/parseInt))
                            summary   (one-line-summary answer 200)
                            acc-out   (into {} (map (fn [[k v]] [k (name v)]) state))
                            verdict   (render-verdict-md
                                       {:id rid :status outcome
                                        :iterations last-iter
                                        :acceptance-outcome acc-out
                                        :narrative answer})
                            vfile     (io/file dir "verdict.md")]
                        (.mkdirs dir)
                        (spit vfile verdict)
                        (append-index! base-dir
                                       {:id rid :status outcome
                                        :one-line (if (str/blank? summary)
                                                    "(auto-finalized; no summary extracted)"
                                                    summary)})
                        (mulog/log ::research.auto-finalize
                                   :id rid :status outcome
                                   :answer-chars (count answer) :n-criteria (count state))
                        (when-not (str/includes? answer "Saved research dossier:")
                          {:result      :replace
                           :reason      "injected absent Saved-research-dossier handoff line"
                           :replacement (assoc result :answer
                                               (str answer "\n\nSaved research dossier: "
                                                    results-base "/" rid "/"))})))))))))))
    (catch Throwable t
      (mulog/error ::research.auto-finalize-failed
                   :exception t
                   :agent-id (try (proto/agent-id agent)
                                  (catch Throwable _ "unknown"))))))

(defn install-auto-finalize!
  "Register the auto-finalize backstop on the gated `:agent.ask/finalize` event,
   scoped to research-agent. Idempotent — register-hook! replaces by id.
   Per-turn opt-out via `agent-runtime$config :key \"research-auto-finalize\"
   :value \"false\"`."
  []
  (hooks/register-hook!
   :agent.ask/finalize
   ::research-auto-finalize
   research-auto-finalize
   :source   :research-agent
   :match    (fn [{:keys [agent]}] (research-agent? agent))
   :priority 50))

;; Self-install at namespace load. Idempotent — register-hook! replaces by id.
(install-auto-finalize!)
