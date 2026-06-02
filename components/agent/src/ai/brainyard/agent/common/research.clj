;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.research
  "Research-agent quality-of-life helpers — mechanical defcommands that compress
   the dossier-bootstrap / append-log / update-status / write-verdict /
   index-append flow described in docs/research-agent-design.md §8.

   Each helper is a `defcommand` so it surfaces in the unified tool registry
   and is auto-bound into the SCI sandbox (callable as `(research$id ...)` in
   a clojure fence). Research-agent works without them — the agent instruction
   has an inline `mkdir + write-file` skeleton — but binding them shrinks the
   prompt because the dossier mechanics no longer have to be inlined every
   iteration.

   Frontmatter shape is hand-rolled (no clj-yaml dep). The dossier carries
   list-of-maps (acceptance) and list-of-strings (direction) on top of the
   flat scalars + nested artifacts map; the emitter below targets exactly that
   shape, not arbitrary YAML. Round-trip stability matters more than YAML
   spec compliance.

   `research$summarize-log` (LLM-backed roll-up of findings.log into a dossier
   ## Findings rewrite) is intentionally NOT in this file. The agent calls
   `query$llm` directly with the log content as `:sub-context` — keeping the
   helper layer mechanical and side-effect-only."
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

(def ^:private valid-statuses
  #{:in-progress :achieved :partial :abandoned})

(def ^:private terminal-statuses
  "Statuses a VERDICT may carry. `:in-progress` is mid-flight and must never
   be written as a verdict / INDEX entry — that's a non-terminal state."
  #{:achieved :partial :abandoned})

(def ^:private valid-criterion-statuses
  #{:open :satisfied :partial :descoped :contradicted})

;; ============================================================================
;; Time formatters (extracted as private fns so tests can with-redefs)
;; ============================================================================

(defn- now-iso
  []
  (str (java.time.Instant/now)))

(defn- now-yyyy-mm-dd-hh-mm
  []
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
;; YAML emission helpers (hand-rolled — targeted at the dossier shape)
;; ============================================================================

(defn- yaml-string
  [s]
  (str "\""
       (-> (str s)
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\""))
       "\""))

(def ^:private bareword-re #"^[A-Za-z0-9_./:-]+$")

(defn- yaml-flow-vector
  [xs]
  (str "["
       (str/join ", " (map (fn [x]
                             (cond
                               (keyword? x) (name x)
                               (string? x) (if (re-matches bareword-re x)
                                             x
                                             (yaml-string x))
                               :else (str x)))
                           xs))
       "]"))

(defn- yaml-folded-scalar
  "Emit `>` folded scalar, collapsing internal whitespace to one line indented
   under the key. Keeps the file head-friendly."
  [s]
  (let [collapsed (-> (str s) (str/replace #"\s+" " ") str/trim)]
    (str ">\n  " collapsed)))

(defn- yaml-acceptance-block
  "Emit `acceptance:\\n  - id: a1\\n    text: \\\"...\\\"\\n    status: open\\n` …
   for the dossier frontmatter. Each item is a map `{:id :text :status}`."
  [items]
  (str/join ""
            (for [{:keys [id text status]} items]
              (str "  - id: " (name id) "\n"
                   "    text: " (yaml-string text) "\n"
                   "    status: " (name (or status :open)) "\n"))))

(defn- yaml-direction-block
  "Emit `direction:\\n  - \\\"<bullet>\\\"\\n  - \\\"<bullet>\\\"` etc."
  [bullets]
  (str/join "" (for [b bullets]
                 (str "  - " (yaml-string b) "\n"))))

;; ============================================================================
;; Dossier composition
;; ============================================================================

(defn- build-dossier-md
  "Compose the dossier.md from a fully-populated map. Frontmatter shape
   matches docs/research-agent-design.md §4.2."
  [{:keys [id created last-iteration status purpose acceptance direction artifacts]
    :or   {last-iteration 1
           status         :in-progress
           artifacts      {}}}]
  (let [{:keys [exploration plan_slug todo_slug evals]
         :or   {exploration []
                evals       []}} artifacts]
    (str "---\n"
         "research_id: " id "\n"
         "created: " (or created (now-iso)) "\n"
         "last_iteration: " last-iteration "\n"
         "status: " (name status) "\n"
         "purpose: " (yaml-folded-scalar purpose) "\n"
         "acceptance:\n"
         (yaml-acceptance-block acceptance)
         "direction:\n"
         (yaml-direction-block direction)
         "artifacts:\n"
         "  exploration: " (yaml-flow-vector exploration) "\n"
         "  plan_slug: " (if plan_slug (yaml-string plan_slug) "null") "\n"
         "  todo_slug: " (if todo_slug (yaml-string todo_slug) "null") "\n"
         "  evals: " (yaml-flow-vector evals) "\n"
         "calls_log: findings.log\n"
         "---\n\n"
         "## Purpose\n" purpose "\n\n"
         "## Direction\n"
         (str/join "" (for [d direction] (str "- " d "\n")))
         "\n## Acceptance criteria\n"
         (str/join "" (for [{:keys [id text status]} acceptance]
                        (str "- **" (name id) "** [" (name (or status :open)) "]: " text "\n")))
         "\n## Findings (rolling summary)\n"
         "_(populated as specialists report — see findings.log)_\n")))

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
;; research$resume? — cheap probe (does the dossier already exist?)
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
  "Targeted regex for `key: <value>` lines (single line, no nested blocks).
   Returns the value as a string with quotes and trailing whitespace stripped."
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

(defn- extract-acceptance-state
  "Parse the `acceptance:` block out of frontmatter lines. Returns a map of
   `{<id-keyword> <status-keyword>}`. Tolerant — any line shape that doesn't
   match the canonical 3-line block is skipped silently."
  [lines]
  (loop [ls    lines
         in?   false
         pending {}
         acc   {}]
    (if (empty? ls)
      acc
      (let [ln (first ls)
            r  (rest ls)]
        (cond
          (re-matches #"^acceptance:\s*$" ln)
          (recur r true {} acc)

          ;; Once we leave the acceptance block (a non-indented key reappears),
          ;; flush any in-flight item and stop scanning.
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

          (and in? (re-matches #"^\s+text:.*$" ln))
          (recur r true pending acc)

          :else
          (if (and in? (empty? r))
            (if-let [pid (:id pending)]
              (assoc acc (keyword pid) (or (some-> (:status pending) keyword) :open))
              acc)
            (recur r in? pending acc)))))))

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
                    accept (extract-acceptance-state lines)]
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
                  [:acceptance-state {:optional true} [:map     {:desc "{<criterion-id-kw> <status-kw>}"}]]
                  [:error            {:optional true} [:string  {:desc "Error if validation failed"}]]])

;; ============================================================================
;; research$bootstrap — create dossier directory + 5 files (idempotent)
;; ============================================================================

(defcommand research$bootstrap
  "Create the research dossier directory and 5 baseline files. Idempotent — won't overwrite."
  (fn [& {:keys [id purpose acceptance direction base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? id))           {:error ":id is required (string)"}
      (not (string? purpose))      {:error ":purpose is required (string)"}
      (not (sequential? acceptance)) {:error ":acceptance must be a vector of {:id :text :status} maps"}
      (not (sequential? direction))  {:error ":direction must be a vector of strings"}
      :else
      (let [dir          (io/file base-dir results-base id)
            dir-rel      (str results-base "/" id)
            dossier-rel  (str dir-rel "/dossier.md")]
        (if (.isDirectory dir)
          (do
            (mulog/log ::research.bootstrap-skip
                       :id id :reason :already-exists)
            {:exists? true :dir dir-rel :dossier-path dossier-rel})
          (let [_ (.mkdirs (io/file dir "artifacts"))
                purpose-md (str purpose "\n")
                acceptance-md (str "# Acceptance criteria\n\n"
                                   (str/join ""
                                             (for [{:keys [id text status]} acceptance]
                                               (str "- **" (name id) "** ["
                                                    (name (or status :open)) "]: " text "\n"))))
                direction-md (str "# Direction\n\n"
                                  (str/join "" (for [d direction] (str "- " d "\n"))))
                acceptance-norm (mapv (fn [{:keys [id text status]}]
                                        {:id (if (keyword? id) (name id) (str id))
                                         :text text
                                         :status (or status :open)})
                                      acceptance)
                dossier-md (build-dossier-md
                            {:id             id
                             :created        (now-iso)
                             :last-iteration 1
                             :status         :in-progress
                             :purpose        purpose
                             :acceptance     acceptance-norm
                             :direction      direction
                             :artifacts      {}})]
            (spit (io/file dir "purpose.md") purpose-md)
            (spit (io/file dir "acceptance.md") acceptance-md)
            (spit (io/file dir "direction.md") direction-md)
            (spit (io/file dir "dossier.md") dossier-md)
            (spit (io/file dir "findings.log") "")
            (mulog/log ::research.bootstrap
                       :id id
                       :acceptance-count (count acceptance)
                       :direction-count (count direction))
            {:dir dir-rel :dossier-path dossier-rel})))))
  :input-schema  [:map
                  [:id         [:string {:desc "Research id"}]]
                  [:purpose    [:string {:desc "Verbatim purpose / user question"}]]
                  [:acceptance [:vector {:desc "Vector of {:id :text :status} criterion maps"} :any]]
                  [:direction  [:vector {:desc "Vector of stratagem bullets (3-6 strings)"} :string]]
                  [:base-dir   {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:dir          {:optional true} [:string  {:desc "Created (or existing) dossier directory"}]]
                  [:dossier-path {:optional true} [:string  {:desc "Path to dossier.md"}]]
                  [:exists?      {:optional true} [:boolean {:desc "true if directory already existed (idempotent skip)"}]]
                  [:error        {:optional true} [:string  {:desc "Error if validation failed"}]]])

;; ============================================================================
;; research$append-log — NDJSON append to findings.log
;; ============================================================================

(defcommand research$append-log
  "Append one NDJSON line to <dossier>/findings.log; one line per specialist invocation. :pointers map flattened into the JSON."
  (fn [& {:keys [id iter agent summary pointers base-dir]
          :or   {base-dir (config/project-dir)
                 pointers {}}}]
    (cond
      (not (string? id))      {:error ":id is required (string)"}
      (not (integer? iter))   {:error ":iter is required (integer)"}
      (not (string? agent))   {:error ":agent is required (string)"}
      (not (string? summary)) {:error ":summary is required (string)"}
      :else
      (let [log-file (io/file base-dir results-base id "findings.log")]
        (if-not (.isFile log-file)
          {:error (str "findings.log not found at "
                       (str results-base "/" id "/findings.log")
                       " — run research$bootstrap first")}
          (let [entry (merge {:iter iter :agent agent :summary summary}
                             pointers)
                ;; Hand-rolled JSON to avoid a hard dep on a json lib in this
                ;; ns. Keys/values are simple scalars or strings — escape the
                ;; minimum (\\, \", control chars).
                escape (fn [s]
                         (-> (str s)
                             (str/replace "\\" "\\\\")
                             (str/replace "\"" "\\\"")
                             (str/replace "\n" "\\n")
                             (str/replace "\r" "\\r")
                             (str/replace "\t" "\\t")))
                kv     (fn [[k v]]
                         (str "\"" (escape (if (keyword? k) (name k) (str k))) "\":"
                              (cond
                                (number? v)  (str v)
                                (boolean? v) (if v "true" "false")
                                (nil? v)     "null"
                                :else        (str "\"" (escape v) "\""))))
                line   (str "{" (str/join "," (map kv entry)) "}\n")]
            (spit log-file line :append true)
            (mulog/log ::research.handoff
                       :id id :iter iter :agent agent
                       :pointer-keys (sort (keys pointers)))
            {:appended true})))))
  :input-schema  [:map
                  [:id       [:string {:desc "Research id"}]]
                  [:iter     [:int    {:desc "Iteration number"}]]
                  [:agent    [:string {:desc "Specialist name (or 'system' for non-specialist events)"}]]
                  [:summary  [:string {:desc "One-line summary of the call's outcome"}]]
                  [:pointers {:optional true} [:map    {:desc "Optional map flattened into the JSON line (plan_slug, todo_slug, …)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:appended {:optional true} [:boolean {:desc "true on success"}]]
                  [:error    {:optional true} [:string  {:desc "Error if validation failed or dossier missing"}]]])

;; ============================================================================
;; research$update-status — flip one acceptance criterion's status
;; ============================================================================

(defcommand research$update-status
  "Targeted edit of a single criterion's status in dossier.md frontmatter. Valid statuses: open|satisfied|partial|descoped|contradicted."
  (fn [& {:keys [id criterion-id status base-dir]
          :or   {base-dir (config/project-dir)}}]
    (let [criterion-name (cond
                           (keyword? criterion-id) (name criterion-id)
                           (string? criterion-id)  criterion-id
                           :else                   nil)
          status-name    (cond
                           (keyword? status) (name status)
                           (string? status)  status
                           :else             nil)]
      (cond
        (not (string? id))
        {:error ":id is required (string)"}

        (nil? criterion-name)
        {:error ":criterion-id is required (string or keyword)"}

        (or (nil? status-name) (not (valid-criterion-statuses (keyword status-name))))
        {:error (str ":status must be one of " (sort (map name valid-criterion-statuses)))}

        :else
        (let [dossier (io/file base-dir results-base id "dossier.md")]
          (if-not (.isFile dossier)
            {:error (str "dossier.md not found for id " id)}
            (let [content (slurp dossier)
                  ;; Match the canonical 3-line acceptance block. The (?m)
                  ;; multiline flag makes ^ match each line; the lookahead
                  ;; on `text:` confines the replacement to the matching
                  ;; criterion only.
                  pat (re-pattern
                       (str "(?m)^(\\s*-\\s*id:\\s*"
                            (java.util.regex.Pattern/quote criterion-name)
                            "\\s*\\n\\s+text:[^\\n]*\\n\\s+status:\\s*)(\\S+)"))
                  m   (re-find pat content)]
              (if-not m
                {:error (str "criterion " criterion-name " not found in dossier.md")}
                (let [old-status (last m)
                      new-content (str/replace content pat (str "$1" status-name))]
                  (spit dossier new-content)
                  (mulog/log ::research.acceptance-flip
                             :id id
                             :criterion-id criterion-name
                             :from (keyword old-status)
                             :to   (keyword status-name))
                  {:updated true
                   :from    (keyword old-status)
                   :to      (keyword status-name)}))))))))
  :input-schema  [:map
                  [:id           [:string {:desc "Research id"}]]
                  [:criterion-id [:string {:desc "Criterion id (e.g. \"a1\")"}]]
                  [:status       [:string {:desc "New status: open|satisfied|partial|descoped|contradicted"}]]
                  [:base-dir     {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:updated {:optional true} [:boolean {:desc "true on success"}]]
                  [:from    {:optional true} [:keyword {:desc "Previous status"}]]
                  [:to      {:optional true} [:keyword {:desc "New status"}]]
                  [:error   {:optional true} [:string  {:desc "Error if validation failed or criterion missing"}]]])

;; ============================================================================
;; research$write-verdict — terminal verdict.md (source of truth)
;; ============================================================================

(defn- read-acceptance-from-dossier
  "Best-effort extraction of acceptance state from the dossier for the
   verdict.md `acceptance_outcome:` map. Returns a vector of `[id-str status-kw]`
   pairs preserving insertion order, or nil if dossier is missing."
  [^java.io.File dossier-file]
  (when (.isFile dossier-file)
    (when-let [lines (read-frontmatter-lines dossier-file)]
      (let [state (extract-acceptance-state lines)]
        ;; The state map preserves insertion order in PersistentArrayMap for
        ;; small maps. For strict order-preservation we'd re-scan, but the
        ;; current parser already inserts in the order it sees ids, which
        ;; matches the file order.
        (vec state)))))

(defn- strip-leading-verdict-heading
  "If the narrative starts with a `## Verdict` (or `# Verdict`) heading,
   strip it. The verdict.md template always emits its own `## Verdict\\n`
   section header before the narrative; without this strip, an LLM that
   includes its own heading produces a duplicate. Idempotent: a narrative
   already without the heading is unchanged."
  [narrative]
  (str/replace (str narrative) #"\A#+\s*Verdict\s*\n+" ""))

(defn- block-mismatched-achieved
  "Validation guard: when the LLM claims `:achieved` but the dossier still
   has criteria in non-terminal states (`:open`/`:partial`/`:contradicted`),
   refuse the write and surface the mismatched ids. Forces the LLM to call
   `research$update-status` on each criterion before finalizing.

   Returns nil when the claim is consistent (or status is not :achieved),
   or `{:error \"...\"}` when there's a mismatch.

   `:abandoned` and `:partial` are not validated this strictly — abandonment
   can happen with any acceptance state, and `:partial` is the catch-all the
   auto-finalize hook also uses for mixed states."
  [status-kw acceptance]
  (when (and (= :achieved status-kw) (seq acceptance))
    (let [bad (->> acceptance
                   (remove (fn [[_ v]] (#{:satisfied :descoped} v)))
                   (mapv (fn [[id v]] (str (name id) ":" (name v)))))]
      (when (seq bad)
        {:error (str ":achieved requires every criterion to be :satisfied or "
                     ":descoped; the following are not: "
                     (str/join ", " bad)
                     ". Call research$update-status to flip each criterion's "
                     "status before writing the verdict, or use :partial / "
                     ":abandoned instead.")}))))

(defcommand research$write-verdict
  "Compose verdict.md from current dossier state at termination (status: achieved|partial|abandoned). :achieved requires every criterion :satisfied or :descoped — flip via research$update-status first."
  (fn [& {:keys [id status narrative base-dir]
          :or   {base-dir (config/project-dir)}}]
    (let [status-kw (cond
                      (keyword? status) status
                      (string? status)  (keyword status)
                      :else             nil)]
      (cond
        (not (string? id))             {:error ":id is required (string)"}
        (not (terminal-statuses status-kw)) {:error (str ":status must be one of "
                                                         (sort (map name terminal-statuses))
                                                         " (a verdict is terminal; :in-progress is mid-flight)")}
        (not (string? narrative))      {:error ":narrative is required (string)"}
        :else
        (let [dir         (io/file base-dir results-base id)
              _           (.mkdirs dir)
              dossier     (io/file dir "dossier.md")
              dossier-frontmatter (when (.isFile dossier)
                                    (read-frontmatter-lines dossier))
              last-iter   (when dossier-frontmatter
                            (some-> (extract-flat dossier-frontmatter "last_iteration")
                                    (str)
                                    Integer/parseInt))
              acceptance  (read-acceptance-from-dossier dossier)]
          (or (block-mismatched-achieved status-kw acceptance)
              (let [acceptance-block (when (seq acceptance)
                                       (str "acceptance_outcome:\n"
                                            (str/join ""
                                                      (for [[k v] acceptance]
                                                        (str "  " (name k) ": " (name v) "\n")))))
                    verdict-file (io/file dir "verdict.md")
                    cleaned-narrative (strip-leading-verdict-heading narrative)
                    content (str "---\n"
                                 "research_id: " id "\n"
                                 "status: " (name status-kw) "\n"
                                 "terminated: " (now-iso) "\n"
                                 (when last-iter (str "iterations: " last-iter "\n"))
                                 acceptance-block
                                 "---\n\n"
                                 "## Verdict\n"
                                 (str/trim cleaned-narrative)
                                 "\n")]
                (spit verdict-file content)
                (mulog/log ::research.terminate
                           :id id :status status-kw
                           :iterations last-iter
                           :n-criteria (count acceptance))
                {:path (.getAbsolutePath verdict-file)}))))))
  :input-schema  [:map
                  [:id        [:string {:desc "Research id"}]]
                  [:status    [:string {:desc "Terminal status: achieved|partial|abandoned"}]]
                  [:narrative [:string {:desc "Markdown body for the ## Verdict section"}]]
                  [:base-dir  {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:path  {:optional true} [:string {:desc "Path to verdict.md"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; research$index-append — prepend one line to INDEX.md (newest first)
;; ============================================================================

(defcommand research$index-append
  "Prepend a one-line entry to .brainyard/agents/research-agent/INDEX.md (newest-first) with status + one-line distillation."
  (fn [& {:keys [id status one-line base-dir]
          :or   {base-dir (config/project-dir)}}]
    (let [status-name (cond
                        (keyword? status) (name status)
                        (string? status)  status
                        :else             nil)]
      (cond
        (not (string? id))           {:error ":id is required (string)"}
        (or (nil? status-name)
            (not (terminal-statuses (keyword status-name))))
        {:error (str ":status must be one of " (sort (map name terminal-statuses))
                     " (INDEX entries are terminal)")}
        (not (string? one-line))     {:error ":one-line is required (string)"}
        :else
        (let [trimmed (-> one-line (str/replace #"\s+" " ") str/trim)
              capped  (if (> (count trimmed) 200)
                        (str (subs trimmed 0 197) "…")
                        trimmed)
              line    (str "- " (now-yyyy-mm-dd-hh-mm)
                           " [" id "](" id "/) — "
                           status-name " · " capped "\n")
              file    (io/file base-dir index-rel)
              existing (if (.isFile file) (slurp file) "")]
          (.mkdirs (.getParentFile file))
          (spit file (str line existing))
          (mulog/log ::research.index
                     :id id :status (keyword status-name))
          {:appended true :line line}))))
  :input-schema  [:map
                  [:id       [:string {:desc "Research id"}]]
                  [:status   [:string {:desc "Terminal status: achieved|partial|abandoned"}]]
                  [:one-line [:string {:desc "One-line distillation (truncated to 200 chars)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:appended {:optional true} [:boolean {:desc "true on success"}]]
                  [:line     {:optional true} [:string  {:desc "The exact line that was prepended"}]]
                  [:error    {:optional true} [:string  {:desc "Error if validation failed"}]]])

;; ============================================================================
;; Public roster (for research-agent's :agent-tools)
;; ============================================================================

(def research-helpers
  "Vector of all research$* helper vars. research-agent appends these to its
   :agent-tools roster so the SCI sandbox auto-binds them (callable as
   `(research$id ...)` in a clojure fence).

   `research$summarize-log` is intentionally absent — the agent calls
   `query$llm` directly with findings.log content as `:sub-context`."
  [#'research$id
   #'research$resume?
   #'research$bootstrap
   #'research$append-log
   #'research$update-status
   #'research$write-verdict
   #'research$index-append])

;; ============================================================================
;; Auto-finalize hook
;;
;; The agent instruction tells the LLM to call research$write-verdict +
;; research$index-append before populating :answer at termination. Sonnet+
;; follows it reliably; smaller models often skip the FINALIZE step (H).
;; This `:agent.ask/post` hook is the safety net.
;;
;; STRICTER trigger condition than explore-agent's auto-persist: we only
;; auto-finalize when the dossier exists AND every acceptance criterion has
;; moved off :open. If any are :open, the LLM is mid-flight (or in CLARIFY
;; mode G — emitting :answer for clarification while still expecting a
;; follow-up turn). Auto-writing verdict.md in those cases would be wrong.
;;
;; If the dossier doesn't exist (LLM never bootstrapped — run wasn't research-
;; shaped), we DO NOT retroactively create one. Log and bail.
;;
;; Caveat: `:agent.ask/post` is observe-only (return values ignored — see
;; hooks.clj). The hook can write verdict.md and append INDEX.md, but it
;; CANNOT inject a `Saved research dossier: <path>` line into the answer the
;; caller already received. Downstream callers that need to discover
;; auto-finalized dossiers should look for `verdict.md` directly or grep
;; `.brainyard/agents/research-agent/INDEX.md`.
;; ============================================================================

(def ^:private saved-research-prefix
  "If :answer already contains this exact prefix, the LLM honored the
   FINALIZE step itself and the hook is a no-op."
  "Saved research dossier:")

(defn- research-agent? [agent]
  (try
    (= :research-agent (proto/defagent-type agent))
    (catch Throwable _ false)))

(defn- already-finalized? [^String answer]
  (boolean (and (string? answer)
                (str/includes? answer saved-research-prefix))))

(defn- terminal-status
  "Derive a terminal status from the dossier's acceptance-state map. Returns
   a status keyword if the dossier is ready to finalize, or `nil` if not.

   Rules (intentionally conservative):
     - empty acceptance-state → nil (degenerate research run; do not fabricate
       a verdict).
     - any :open value           → nil (LLM is mid-flight or in CLARIFY mode).
     - all :satisfied / :descoped → :achieved
     - all :contradicted          → :abandoned
     - any other mix             → :partial"
  [acceptance-state]
  (let [vs (set (vals acceptance-state))]
    (cond
      (empty? vs)                            nil
      (contains? vs :open)                   nil
      (every? #{:satisfied :descoped} vs)    :achieved
      (every? #{:contradicted} vs)           :abandoned
      :else                                  :partial)))

(defn- one-line-summary
  "Distill a one-line summary from the answer. Heuristic: drop frontmatter,
   strip the trailing `Saved research dossier:` line if present, take the
   first prose paragraph (not heading / table / code-fence), collapse
   whitespace, cap at `max-chars`."
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
  "Auto-write verdict.md + append INDEX.md when research-agent emits a
   non-blank answer without finalizing itself. Strict trigger: only fires
   when the dossier exists AND no acceptance criterion is :open AND
   verdict.md doesn't already exist. Idempotent — defensive against repeat
   invocations.

   Any failure inside the hook is logged but never re-thrown — the
   user-facing answer must not be affected by hook errors."
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
                rid      (when-not (str/blank? question)
                           (slugify question 60))]
            (when rid
              (let [base-dir (config/project-dir)
                    dir      (io/file base-dir results-base rid)]
                (cond
                  (not (.isDirectory dir))
                  ;; LLM never bootstrapped — the run wasn't research-shaped.
                  ;; DO NOT retroactively create a dossier; just log and bail.
                  (mulog/log ::research.no-dossier-skip
                             :id rid
                             :answer-chars (count answer))

                  (.isFile (io/file dir "verdict.md"))
                  ;; Already finalized (LLM did it, or a prior hook run).
                  ;; Still log so we can see the no-op in trajectories.
                  (mulog/log ::research.auto-finalize-skip
                             :id rid
                             :reason :verdict-exists)

                  :else
                  (let [resume-state (research$resume? :id rid :base-dir base-dir)
                        accept       (:acceptance-state resume-state)
                        status       (terminal-status accept)]
                    (cond
                      (nil? status)
                      (mulog/log ::research.auto-finalize-skip
                                 :id rid
                                 :reason (cond
                                           (empty? (or accept {})) :no-acceptance
                                           (contains? (set (vals accept)) :open) :open-criteria-remain
                                           :else :status-undetermined)
                                 :acceptance-state accept)

                      :else
                      (let [summary (one-line-summary answer 200)
                            v (research$write-verdict
                               :id rid
                               :status status
                               :narrative answer
                               :base-dir base-dir)]
                        (when-not (:error v)
                          (research$index-append
                           :id rid
                           :status status
                           :one-line (if (str/blank? summary)
                                       "(auto-finalized; no summary extracted)"
                                       summary)
                           :base-dir base-dir)
                          (mulog/log ::research.auto-finalize
                                     :id rid
                                     :status status
                                     :answer-chars (count answer)
                                     :n-criteria (count accept))
                          ;; Inject the absent handoff line — propagates up
                          ;; through the enclosing when/cond/let as the hook's
                          ;; return, a :replace decision the :agent.ask/finalize
                          ;; runner applies to the answer.
                          (when-not (str/includes? answer "Saved research dossier:")
                            {:result      :replace
                             :reason      "injected absent Saved-research-dossier handoff line"
                             :replacement (assoc result :answer
                                                 (str answer "\n\nSaved research dossier: " (:path v)))}))))))))))))
    (catch Throwable t
      (mulog/error ::research.auto-finalize-failed
                   :exception t
                   :agent-id (try (proto/agent-id agent)
                                  (catch Throwable _ "unknown"))))))

(defn install-auto-finalize!
  "Register the auto-finalize hook globally. Idempotent — safe to call
   multiple times. The `:match` predicate scopes the hook to research-agent
   instances only, so other agents are unaffected.

   Tag `:source :research-agent` lets apps opt out via
   `(hooks/unregister-source! :research-agent)`. Per-turn opt-out is via
   `agent-runtime$config :key \"research-auto-finalize\" :value \"false\"`."
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
