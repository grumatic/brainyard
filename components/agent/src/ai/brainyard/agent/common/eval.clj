;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.eval
  "Eval-agent helpers — verdict read seams + auto-persist backstop.

   Lightweight redesign (docs/design/eval-agent-lightweight-redesign.md):
   SCORE is pure LLM judgment (reasoning + query$llm) and the verdict is
   authored as markdown directly — the persist-side helper chain
   (eval$dossier-slug / -frontmatter / -write / -index-append, eval$verdict-write,
   eval$next-handoff) is retired. The verdict and the machine-readable dossier
   are UNIFIED into ONE file (frontmatter = machine, body = human report):

     .brainyard/agents/eval-agent/
     ├── verdicts/<yyyyMMdd-HHmmss>-<slug>.md   (frontmatter + report)
     └── INDEX.md

   What stays is pure mechanism a machine does better than the model — the
   deterministic READ seams (the evidence inputs + prior-verdict search):
   - eval$read-verdict — frontmatter-only parse of a unified verdict file
     (also reads legacy pre/score/post/handoff dossiers — dual-read, §10).
   - eval$find        — prior verdicts by slug (+ exec run-record) for the C7
     double-score check.
   The four UPSTREAM readers (exec/plan/todo$read-dossier, edit$read-record) are
   bound in eval_agent.clj, not here.

   v1 narrowings (matching plan/todo/exec):
   - POST-FLIGHT PASS/HOLD only (no REVISE auto-round)
   - C7 double-score check INFORMATIONAL only
   - R7 reproducibility check is instruction-level only (no opt-in flag)"
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.text SimpleDateFormat]
           [java.time Instant]
           [java.util Date]))

(def ^:private verdicts-subdir "verdicts")
(def ^:private base-rel ".brainyard/agents/eval-agent")
(def ^:private verdicts-dir-rel (str base-rel "/" verdicts-subdir))
(def ^:private index-rel (str base-rel "/INDEX.md"))

(def ^:private slug-stopwords
  #{"a" "an" "the" "is" "are" "and" "or" "of" "in" "on" "at" "to" "for"
    "by" "with" "from" "as" "but" "if" "then" "than" "so"
    "what" "where" "when" "who" "whom" "why" "how" "which"
    "do" "does" "did" "can" "could" "would" "should" "shall" "will"
    "this" "that" "these" "those" "it" "its" "we" "they" "you" "i"
    "be" "been" "being" "was" "were" "have" "has" "had"
    "eval" "evaluate" "score" "verdict"})

(defn current-dirs
  "Resolve dirs from the current agent session, falling back to init-dirs!."
  []
  (or (when-let [a (some-> (requiring-resolve 'ai.brainyard.agent.core.protocol/*current-agent*)
                           deref)]
        (some-> (:!session a) deref
                ((or (requiring-resolve 'ai.brainyard.agent.core.session/get-session-config)
                     (constantly nil))
                 :dirs)))
      ((or (requiring-resolve 'ai.brainyard.agent.core.config/init-dirs!)
           (constantly {})))))

(defn dossier-default-base-dir
  "Project root for eval-agent artifacts. Public so tests can `with-redefs`
   it cleanly. Delegates to `agent.core.config/project-dir` — the single
   source of truth shared by every functional agent and the LLM tool
   channel."
  []
  (config/project-dir))

(defn- now-ts []
  (.format (SimpleDateFormat. "yyyyMMdd-HHmmss") (Date.)))

(defn- now-iso []
  (str (Instant/now)))

(defn- now-yyyy-mm-dd-hh-mm []
  (-> (subs (now-iso) 0 16)
      (str/replace "T" " ")))

(defn- eval-slugify
  [request max-chars]
  (let [tokens (-> (str request)
                   str/lower-case
                   (str/replace #"[^a-z0-9]+" "-")
                   (str/split #"-"))
        kept   (->> tokens
                    (remove #(or (str/blank? %) (slug-stopwords %)))
                    seq)
        joined (if kept (str/join "-" kept) "eval")]
    (subs joined 0 (min max-chars (count joined)))))

(defn- verdict->yaml
  "Render a score verdict keyword as the UPPER_CASE token used in the
   frontmatter + body. nil → \"null\"."
  [v]
  (case v
    :achieved           "ACHIEVED"
    :partially-achieved "PARTIALLY_ACHIEVED"
    :not-achieved       "NOT_ACHIEVED"
    nil                 "null"
    (-> (name v) str/upper-case)))

;; ---------------------------------------------------------------------------
;; YAML emission — minimal flow-style emitter (source map, criteria/
;; recommendations vectors). The happy-path LLM authors frontmatter directly;
;; this backs only the auto-persist backstop, which emits empty criteria/recs.
;; ---------------------------------------------------------------------------

(def ^:private bareword-re #"^[A-Za-z0-9_./:-]+$")

(defn- yaml-string
  [s]
  (str "\"" (-> (str s)
                (str/replace "\\" "\\\\")
                (str/replace "\"" "\\\""))
       "\""))

(defn- kw-full-name
  "Full keyword name, including namespace. `:n/a` → `\"n/a\"`."
  [k]
  (if-let [ns (namespace k)]
    (str ns "/" (name k))
    (name k)))

(defn- yaml-scalar
  [v]
  (cond
    (nil? v)        "null"
    (boolean? v)    (if v "true" "false")
    (keyword? v)    (kw-full-name v)
    (number? v)     (str v)
    (string? v)     (if (and (seq v) (re-matches bareword-re v))
                      v
                      (yaml-string v))
    :else           (yaml-string (str v))))

(defn- yaml-flow-key
  [k]
  (cond
    (keyword? k) (kw-full-name k)
    (string? k)  (if (and (seq k) (re-matches bareword-re k))
                   k
                   (yaml-string k))
    :else        (yaml-string (str k))))

(declare yaml-flow-vector yaml-flow-map)

(defn- yaml-flow-value
  [v]
  (cond
    (sequential? v) (yaml-flow-vector v)
    (map? v)        (yaml-flow-map v)
    :else           (yaml-scalar v)))

(defn- yaml-flow-vector
  "Flow-style vector. Elements may be scalars, flow-vectors, or flow-maps."
  [xs]
  (str "[" (str/join ", " (map yaml-flow-value xs)) "]"))

(defn- yaml-flow-map
  "Single-line flow-style flat map. Values may be scalars, flow-vectors,
   or one-level-nested flow-maps."
  [m]
  (str "{"
       (str/join ", "
                 (map (fn [[k v]]
                        (str (yaml-flow-key k) ": " (yaml-flow-value v)))
                      m))
       "}"))

(defn- build-eval-verdict-frontmatter*
  "Render the §5 unified-verdict frontmatter: top-level verdict/confidence/
   source + criteria/recommendations (flow-vectors of flow-maps). The
   happy-path LLM writes this from the template; the backstop calls it with
   empty criteria/recs."
  [{:keys [slug created verdict confidence
           exec_dossier todo_dossier plan_dossier exec_run_record
           degradation is_re_run criteria recommendations]}]
  (str "---\n"
       "slug: " (yaml-scalar slug) "\n"
       "agent: eval-agent\n"
       "created: " (or created (now-iso)) "\n"
       "verdict: " (verdict->yaml verdict) "\n"
       "confidence: " (if confidence (name confidence) "null") "\n"
       "source: " (yaml-flow-map (cond-> {}
                                   exec_dossier    (assoc :exec_dossier exec_dossier)
                                   todo_dossier    (assoc :todo_dossier todo_dossier)
                                   plan_dossier    (assoc :plan_dossier plan_dossier)
                                   exec_run_record (assoc :exec_run_record exec_run_record))) "\n"
       "degradation: " (yaml-flow-vector (or degradation [])) "\n"
       "is_re_run: " (if is_re_run "true" "false") "\n"
       "\n"
       "criteria: " (yaml-flow-vector (or criteria [])) "\n"
       "recommendations: " (yaml-flow-vector (or recommendations [])) "\n"
       "---\n"))

;; ---------------------------------------------------------------------------
;; YAML parsing — lenient; flow-maps preserved as raw strings (downstream can
;; substring-search). Handles BOTH the new unified shape (top-level verdict/
;; confidence/source + criteria/recommendations block-lists) AND legacy
;; pre/score/post/handoff nested blocks (dual-read, §10).
;; ---------------------------------------------------------------------------

(defn- read-frontmatter-lines
  [^java.io.File file]
  (with-open [r (io/reader file)]
    (let [reader (java.io.BufferedReader. r)
          first-line (.readLine reader)]
      (when (= "---" first-line)
        (loop [acc []]
          (let [ln (.readLine reader)]
            (cond
              (nil? ln)        nil
              (= "---" ln)     acc
              :else            (recur (conj acc ln)))))))))

(defn- parse-flow-vector
  [s]
  (let [trimmed (str/trim (str s))]
    (if (and (str/starts-with? trimmed "[") (str/ends-with? trimmed "]"))
      (let [inner (subs trimmed 1 (dec (count trimmed)))]
        (if (str/blank? inner)
          []
          ;; Naive split-on-top-comma; doesn't recurse into nested flow-maps.
          (->> (str/split inner #",")
               (map str/trim)
               (map (fn [tok]
                      (cond
                        (and (str/starts-with? tok "\"") (str/ends-with? tok "\""))
                        (-> tok
                            (subs 1 (dec (count tok)))
                            (str/replace "\\\"" "\"")
                            (str/replace "\\\\" "\\"))

                        :else
                        (let [t (str/trim tok)]
                          (cond
                            (= t "true")  true
                            (= t "false") false
                            (= t "null")  nil
                            (re-matches #"-?\d+" t)
                            (try (Long/parseLong t) (catch Exception _ t))
                            :else t)))))
               vec)))
      [])))

(defn- coerce-bare-value
  [s]
  (let [trimmed (str/trim s)]
    (cond
      (= trimmed "true")  true
      (= trimmed "false") false
      (= trimmed "null")  nil
      (re-matches #"-?\d+" trimmed)
      (try (Long/parseLong trimmed) (catch Exception _ trimmed))
      :else trimmed)))

(defn- parse-scalar
  [v]
  (let [v (str/trim v)]
    (cond
      ;; Flow-vector containing nested {…} → keep raw (parser doesn't recurse
      ;; into maps inside vectors). Catches `criteria: [{class: ...}, {...}]`.
      (and (re-matches #"^\[.*\]$" v)
           (str/includes? v "{"))
      v

      (re-matches #"^\[.*\]$" v) (parse-flow-vector v)
      (re-matches #"^\{.*\}$" v) v ; flow-map kept as raw string for v1
      (and (str/starts-with? v "\"") (str/ends-with? v "\""))
      (-> v
          (subs 1 (dec (count v)))
          (str/replace "\\\"" "\"")
          (str/replace "\\\\" "\\"))
      :else (coerce-bare-value v))))

(def ^:private block-keys
  "Legacy nested-block keys (dual-read of pre-redesign dossiers)."
  #{"pre" "score" "post" "handoff"})

(def ^:private list-keys
  "Top-level keys whose value is a block-list of flow-maps (or an inline
   flow-vector): the unified verdict's `criteria` / `recommendations`."
  #{"criteria" "recommendations"})

(defn- parse-eval-dossier-yaml
  [lines]
  (loop [ls    lines
         acc   {}
         mode  nil]
    (if (empty? ls)
      acc
      (let [ln    (first ls)
            rest* (rest ls)]
        (cond
          ;; Block-list item under criteria/recommendations: "  - <flow-map>"
          (and (vector? mode) (= :list-block (first mode))
               (re-matches #"^\s+-\s+\S.*$" ln))
          (let [[_ raw] (re-matches #"^\s+-\s+(.*)$" ln)]
            (recur rest* (update acc (second mode) (fnil conj []) (parse-scalar raw)) mode))

          ;; Legacy flat-block sub-key: "  k: v"
          (and (vector? mode) (= :flat-block (first mode))
               (re-matches #"^\s{2,}\S.*" ln))
          (if-let [[_ k v] (re-matches #"^\s+([\w_-]+):\s*(.*)$" ln)]
            (recur rest*
                   (assoc-in acc [(second mode) (keyword k)] (parse-scalar v))
                   mode)
            (recur rest* acc nil))

          ;; Bare key (no value) → block header
          (re-matches #"^([\w_-]+):\s*$" ln)
          (let [[_ k] (re-matches #"^([\w_-]+):\s*$" ln)]
            (cond
              (list-keys k)  (recur rest* (assoc acc (keyword k) []) [:list-block (keyword k)])
              (block-keys k) (recur rest* (assoc acc (keyword k) {}) [:flat-block (keyword k)])
              :else          (recur rest* acc nil)))

          ;; Flat key: value (parse-scalar handles inline []/[{…}]/{…})
          (re-matches #"^([\w_-]+):\s*(.*)$" ln)
          (let [[_ k v] (re-matches #"^([\w_-]+):\s*(.*)$" ln)]
            (recur rest* (assoc acc (keyword k) (parse-scalar v)) nil))

          :else
          (recur rest* acc mode))))))

;; ---------------------------------------------------------------------------
;; Slug collision suffix + INDEX append (append-only, newest at bottom).
;; ---------------------------------------------------------------------------

(defn- existing-slug-count
  [base-dir slug]
  (let [^java.io.File dir (io/file base-dir verdicts-dir-rel)]
    (if (.isDirectory dir)
      (let [slug-re (re-pattern (str "(?i)-" (java.util.regex.Pattern/quote slug)
                                     "(-\\d+)?\\.md$"))]
        (->> (.listFiles dir)
             (filter (fn [^java.io.File f] (.isFile f)))
             (map (fn [^java.io.File f] (.getName f)))
             (filter #(re-find slug-re %))
             count))
      0)))

(defn- final-slug-with-suffix
  [base-dir base-slug]
  (let [n (existing-slug-count base-dir base-slug)]
    (if (zero? n)
      base-slug
      (str base-slug "-" (inc n)))))

(defn- index-line
  [{:keys [path slug pre-verdict score-verdict confidence next-agent]}]
  (let [filename (-> path (str/split #"/") last)
        v-tok    (if score-verdict
                   (verdict->yaml score-verdict)
                   (-> (name (or pre-verdict :n-a)) str/upper-case))
        c-tok    (if confidence (name confidence) "n/a")
        next-tok (str "→ " (name (or next-agent :unspecified)))]
    (str "- " (now-yyyy-mm-dd-hh-mm)
         " [" slug "](" verdicts-subdir "/" filename ") — "
         v-tok " · " c-tok " · " next-tok "\n")))

(defn- append-index!
  "Append one INDEX.md line for a freshly written verdict (append-only; the
   auto-persist backstop uses this). Verdicts coerced to keywords."
  [base-dir {:keys [path slug pre-verdict score-verdict confidence next-agent]}]
  (let [pre-kw   (if (keyword? pre-verdict) pre-verdict (keyword (str pre-verdict)))
        score-kw (cond (keyword? score-verdict) score-verdict
                       (string? score-verdict)  (keyword score-verdict)
                       :else                     nil)
        conf-kw  (cond (keyword? confidence) confidence
                       (string? confidence)  (keyword confidence)
                       :else                 nil)
        next-kw  (cond (keyword? next-agent) next-agent
                       (string? next-agent)  (keyword next-agent)
                       :else                 :unspecified)
        line     (index-line {:path path :slug slug :pre-verdict pre-kw
                              :score-verdict score-kw :confidence conf-kw
                              :next-agent next-kw})
        file     (io/file base-dir index-rel)]
    (.mkdirs (.getParentFile file))
    (spit file line :append true)
    (mulog/log ::eval.verdict-index :slug slug :path path
               :score-verdict score-kw :confidence conf-kw :next-agent next-kw)
    line))

;; ---------------------------------------------------------------------------
;; eval$read-verdict — frontmatter-only parse of a unified verdict file
;; (also reads legacy pre/score/post/handoff dossiers — dual-read, §10).
;; ---------------------------------------------------------------------------

(defcommand eval$read-verdict
  "Read and parse the YAML frontmatter from an eval-agent verdict file (or a legacy dossier)."
  (fn [& {:keys [path base-dir]
          :or   {base-dir (dossier-default-base-dir)}}]
    (cond
      (not (string? path))
      {:error ":path is required (string)"}

      :else
      (let [file (if (.isAbsolute (io/file path))
                   (io/file path)
                   (io/file base-dir path))]
        (cond
          (not (.isFile file))
          {:error (str "File not found: " path)}

          :else
          (if-let [lines (read-frontmatter-lines file)]
            (parse-eval-dossier-yaml lines)
            {:error (str "No frontmatter block at " path " (file did not start with ---)")})))))
  :input-schema  [:map
                  [:path [:string {:desc "Repo-relative or absolute path to an eval-agent verdict file"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:slug {:optional true} [:string {:desc "Verdict slug"}]]
                  [:verdict {:optional true} [:string {:desc "ACHIEVED | PARTIALLY_ACHIEVED | NOT_ACHIEVED"}]]
                  [:confidence {:optional true} [:string {:desc "high | medium | low"}]]
                  [:source {:optional true} [:string {:desc "Flow-map of upstream paths (exec_dossier/todo_dossier/plan_dossier/exec_run_record), raw"}]]
                  [:criteria {:optional true} [:vector {:desc "Per-criterion flow-maps (raw strings)"} :any]]
                  [:recommendations {:optional true} [:vector {:desc "Per-criterion recommendation flow-maps (raw strings)"} :any]]
                  [:degradation {:optional true} [:vector {:desc "Soft-fail keywords"} :any]]
                  [:is_re_run {:optional true} [:boolean {:desc "True when a prior verdict existed for this exec run"}]]
                  ;; Legacy nested blocks (dual-read of pre-redesign dossiers)
                  [:pre {:optional true} [:map {:desc "Legacy pre-flight sub-block"}]]
                  [:score {:optional true} [:map {:desc "Legacy score sub-block"}]]
                  [:post {:optional true} [:map {:desc "Legacy post-flight sub-block"}]]
                  [:handoff {:optional true} [:map {:desc "Legacy handoff sub-block"}]]
                  [:error {:optional true} [:string {:desc "Error if file missing or no frontmatter"}]]])

;; ---------------------------------------------------------------------------
;; eval$find — search prior verdicts (C7 double-score check + history)
;; ---------------------------------------------------------------------------

(defcommand eval$find
  "Search prior eval-agent verdicts by slug (optionally by exec run-record). Newest-first."
  (fn [& {:keys [slug run-record base-dir]
          :or   {base-dir (dossier-default-base-dir)}}]
    (cond
      (not (string? slug))
      {:error ":slug is required (string)"}

      :else
      (let [dir (io/file base-dir verdicts-dir-rel)]
        (if-not (.isDirectory dir)
          {:matches [] :n-matches 0}
          (let [slug-re (re-pattern (str "(?i)-" (java.util.regex.Pattern/quote slug)
                                         "(-\\d+)?\\.md$"))
                run-rec-norm (when run-record (str/trim run-record))
                matches
                (->> (.listFiles dir)
                     (filter (fn [^java.io.File f]
                               (and (.isFile f)
                                    (re-find slug-re (.getName f)))))
                     (sort-by #(.getName ^java.io.File %))
                     reverse
                     (keep (fn [^java.io.File f]
                             (when-let [lines (read-frontmatter-lines f)]
                               (let [fm     (parse-eval-dossier-yaml lines)
                                     ;; source is a raw flow-map string; the
                                     ;; run-record path is a substring of it.
                                     src    (str (:source fm))
                                     match? (or (nil? run-rec-norm)
                                                (str/includes? src run-rec-norm))]
                                 (when match?
                                   {:path           (str verdicts-dir-rel "/" (.getName f))
                                    :slug           (:slug fm)
                                    :created        (:created fm)
                                    :verdict        (:verdict fm)
                                    :confidence     (:confidence fm)
                                    :source         (:source fm)})))))
                     vec)]
            (mulog/log ::eval.find :slug slug :run-record run-record :n-matches (count matches))
            {:matches matches :n-matches (count matches)})))))
  :input-schema  [:map
                  [:slug [:string {:desc "Slug to search for"}]]
                  [:run-record {:optional true} [:string {:desc "Optional: only match verdicts whose source contains this exec run-record/dossier path (C7 'same exec turn' detection)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:matches [:vector {:desc "Matching prior verdicts, newest-first"} :map]]
                  [:n-matches [:int {:desc "Number of matches"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; Handoff rule (pure fn) — the eval$next-handoff command is retired; the LLM
;; writes the `Next:` line from the verdict→next rule table in eval_agent.clj.
;; This fn backs the auto-persist backstop's INDEX next-agent + injected Next.
;; ---------------------------------------------------------------------------

(defn- handoff-from-state
  [pre-verdict score-verdict slug dossier-path]
  (let [ctx (cond-> "" dossier-path (str " :agent-context \"" dossier-path "\""))]
    (case (or score-verdict :n-a)
      :achieved
      {:next-agent "user"
       :next-call (str "After user confirms: (doc$update "
                       "{:kind :todo :slug \"" (or slug "<slug>")
                       "\" :status :completed}) and "
                       "(doc$update {:kind :plan :slug \""
                       (or slug "<slug>") "\" :status :completed}).")}

      :partially-achieved
      {:next-agent "user"
       :next-call (str "Per-criterion recommendations are in recommendations. "
                       "Most common: (todo-agent {…}) for missing items, "
                       "or accept partial result via (doc$update "
                       "{:kind :todo :slug \"" (or slug "<slug>") "\" :status :completed}).")}

      :not-achieved
      {:next-agent "plan-agent"
       :next-call (str "Recommended primary path: re-spec via plan-agent. "
                       "(plan-agent {:question \"Revise approach: <gap>\""
                       ctx "}). Per-criterion recommendations also in frontmatter.")}

      :n-a
      (case pre-verdict
        :gather
        {:next-agent "user"
         :next-call (str "Provide the missing input named in the verdict notes; "
                         "typically run exec-agent first to produce evidence.")}

        :refuse
        {:next-agent "none"
         :next-call (str "See the verdict notes for the redirect (typically "
                         "exec-agent re-run when evidence was empty, or plan-agent "
                         "when acceptance was empty).")}

        {:next-agent "user"
         :next-call (str "Inspect the verdict and decide next step.")}))))

;; ---------------------------------------------------------------------------
;; Public roster
;; ---------------------------------------------------------------------------

(def eval-dossier-helpers
  "The surviving eval READ seams — the unified-verdict frontmatter reader and
   the prior-verdict search (eval$find). eval-agent appends these so the SCI
   sandbox auto-binds them. The write-side helper chain is retired (verdicts
   authored via write-file)."
  [#'eval$read-verdict
   #'eval$find])

;; ============================================================================
;; Auto-persist backstop
;;
;; Same proven shape as exec-auto-persist: the LLM can forget to write or
;; hallucinate the `Saved verdict:` path. The hook fills the §5 template from
;; the answer text and `spit`s ONE unified file. Idempotency is an on-disk-
;; existence check (catches hallucinated paths).
;; ============================================================================

(def saved-verdict-prefix
  "Stable prefix the agent emits when a verdict file was written this turn.
   No trailing space: the LLM often emits the marker markdown-bolded
   (`**Saved verdict:** …`), so we match up to the colon and let
   `extract-line-after` strip any `*`/backtick decoration off the path."
  "Saved verdict:")

(def saved-dossier-prefix
  "Legacy marker (pre-unification). Still recognized so a turn that emits the
   old `Saved dossier:` line is treated as already-persisted."
  "Saved dossier:")

(defn- eval-agent? [agent]
  (try
    (= :eval-agent (proto/defagent-type agent))
    (catch Throwable _ false)))

(defn- extract-line-after
  [^String answer ^String prefix]
  (when (and (string? answer) (string? prefix))
    (when-let [start (str/index-of answer prefix)]
      (let [after (subs answer (+ start (count prefix)))
            end   (or (str/index-of after "\n") (count after))]
        ;; Tolerate markdown decoration: `**Saved verdict:** `path`` → strip
        ;; leading/trailing `*`, backticks, quotes, and the colon.
        (-> (subs after 0 end)
            (str/replace #"^[`'\"*:\s]+" "")
            (str/replace #"[`'\"*\.,\s]+$" "")
            str/trim)))))

(defn- absolute->repo-relative
  [base-dir ^String path]
  (cond
    (or (nil? path) (str/blank? path))
    nil

    (and base-dir (str/starts-with? path (str base-dir "/")))
    (subs path (inc (count base-dir)))

    :else path))

(defn- claimed-file?
  [base-dir claimed]
  (when (and (string? claimed) (seq claimed))
    (let [file (if (.isAbsolute (io/file claimed))
                 (io/file claimed)
                 (io/file base-dir claimed))]
      (.isFile file))))

(defn- verdict-already-saved?
  "True when the answer names a `Saved verdict:` (or legacy `Saved dossier:`)
   path that exists on disk."
  [^String answer base-dir]
  (boolean
   (or (claimed-file? base-dir (extract-line-after answer saved-verdict-prefix))
       (claimed-file? base-dir (extract-line-after answer saved-dossier-prefix)))))

(defn- detect-pre-verdict [^String answer]
  (cond
    (re-find #"(?im)^\s*Refused:\s*\S" answer) :refuse
    (re-find #"(?im)^\s*Need:\s*\S"    answer) :gather
    :else                                       :go))

(defn- detect-score-verdict
  "Infer score verdict from the `Verdict:` answer line. Recognizes
   ACHIEVED, PARTIALLY_ACHIEVED, NOT_ACHIEVED. nil when no `Verdict:` line
   (typical for GATHER/REFUSE)."
  [^String answer]
  (when-let [line (extract-line-after answer "Verdict:")]
    (let [up (str/upper-case line)]
      (cond
        (str/starts-with? up "PARTIALLY_ACHIEVED") :partially-achieved
        (str/starts-with? up "NOT_ACHIEVED")       :not-achieved
        (str/starts-with? up "ACHIEVED")           :achieved
        :else nil))))

(defn- detect-confidence
  "Pull confidence from `Verdict: <X> (confidence: <Y>)` answer line."
  [^String answer]
  (when-let [line (extract-line-after answer "Verdict:")]
    (when-let [[_ conf] (re-find #"(?i)\bconfidence:\s*(high|medium|low)" line)]
      (keyword (str/lower-case conf)))))

(defn- one-line-summary
  [^String answer max-chars]
  (let [paragraphs (->> (str/split (or answer "") #"\n\n")
                        (map str/trim)
                        (remove str/blank?))
        prose? (fn [^String p]
                 (and (not (str/starts-with? p "#"))
                      (not (str/starts-with? p "|"))
                      (not (str/starts-with? p "```"))
                      (not (str/starts-with? p "Saved "))
                      (not (str/starts-with? p "Need:"))
                      (not (str/starts-with? p "Refused:"))
                      (not (str/starts-with? p "Hold:"))
                      (not (str/starts-with? p "Verdict:"))
                      (not (str/starts-with? p "Recommended:"))
                      (not (str/starts-with? p "Suggested:"))
                      (not (str/starts-with? p "Next:"))))
        chosen (or (first (filter prose? paragraphs))
                   (first paragraphs)
                   "")
        flat   (-> chosen
                   (str/replace #"\s+" " ")
                   (str/replace #"^#+\s*" "")
                   str/trim)]
    (subs flat 0 (min max-chars (count flat)))))

(defn- render-eval-verdict-md
  "Fill the §5 unified-verdict template: frontmatter + body. Backs the
   auto-persist backstop; the happy-path LLM writes the equivalent markdown."
  [{:keys [slug verdict confidence summary answer title]}]
  (str (build-eval-verdict-frontmatter*
        {:slug slug :verdict verdict :confidence confidence})
       "\n# Verdict — " (or title slug) ": " (verdict->yaml verdict)
       " (confidence: " (if confidence (name confidence) "n/a") ")\n\n"
       "*Reconstructed from the agent's answer text — the LLM did not author "
       "the verdict itself this turn.*\n\n"
       "## Summary\n" (or summary "") "\n\n"
       "## Per-criterion\n(not machine-reconstructed — see Original answer)\n\n"
       "## Original answer\n" answer "\n"))

(defn materialize-auto-dossier!
  "Core of the eval auto-persist backstop — agent-state-free. Reconstruct a
   §5-template verdict from the answer text, `spit` it under verdicts/
   (timestamp-prefixed), append one INDEX line. Returns
   `{:path :rel-path :slug :pre-verdict :score-verdict}` or nil when skipped.
   Does the SAME thing the happy path does (write one unified file)."
  [{:keys [answer question base-dir]}]
  (cond
    (or (not (string? answer)) (str/blank? answer))
    nil

    (verdict-already-saved? answer base-dir)
    nil

    :else
    (let [pre-verdict   (detect-pre-verdict answer)
          score-verdict (detect-score-verdict answer)
          confidence    (detect-confidence answer)
          verdict-raw   (extract-line-after answer saved-verdict-prefix)
          slug          (or (when (seq verdict-raw)
                              (-> verdict-raw (str/split #"/") last
                                  (str/replace #"\.md$" "")))
                            (eval-slugify (or question "auto-persisted") 60)
                            "eval")
          summary       (one-line-summary answer 200)
          handoff-kebab (handoff-from-state pre-verdict score-verdict slug nil)
          ts            (now-ts)
          final-slug    (final-slug-with-suffix base-dir slug)
          rel-path      (str verdicts-dir-rel "/" ts "-" final-slug ".md")
          file          (io/file base-dir rel-path)
          content       (render-eval-verdict-md
                         {:slug       final-slug
                          :verdict    score-verdict
                          :confidence confidence
                          :title      (str final-slug " (auto-persisted)")
                          :summary    summary
                          :answer     answer})]
      (.mkdirs (.getParentFile file))
      (spit file content)
      (append-index! base-dir
                     {:path rel-path :slug final-slug
                      :pre-verdict pre-verdict :score-verdict score-verdict
                      :confidence confidence :next-agent (:next-agent handoff-kebab)})
      (mulog/log ::eval.auto-persist
                 :slug final-slug :path rel-path
                 :pre-verdict pre-verdict :score-verdict score-verdict
                 :confidence confidence :answer-chars (count answer))
      {:path          (.getAbsolutePath file)
       :rel-path      rel-path
       :slug          final-slug
       :pre-verdict   pre-verdict
       :score-verdict score-verdict})))

(defn eval-auto-persist
  "Gated handler for `:agent.ask/finalize`. Materializes the verdict file when
   the LLM skipped PERSIST AND returns a `:replace` decision injecting the
   absent `Saved verdict: <path>` line into the answer — so answer-grepping
   consumers see it even when the LLM forgot. Idempotent — no-op when a marker
   is present or nothing was persisted. Defensive — failures logged, never
   re-thrown."
  [{:keys [agent input result]}]
  (try
    (when (and (eval-agent? agent) (map? result))
      (let [question (or (when (string? input) input)
                         (some-> input :question str)
                         "(question not captured)")
            answer    (str (:answer result))
            persisted (materialize-auto-dossier!
                       {:answer   (:answer result)
                        :question question
                        :base-dir (dossier-default-base-dir)})
            path      (:rel-path persisted)]
        (when (and path
                   (not (str/includes? answer "Saved verdict:"))
                   (not (str/includes? answer "Saved dossier:")))
          {:result      :replace
           :reason      "injected absent Saved-verdict handoff line"
           :replacement (assoc result :answer (str answer "\n\nSaved verdict: " path))})))
    (catch Throwable t
      (mulog/error ::eval.auto-persist-failed
                   :exception t
                   :agent-id  (try (proto/agent-id agent)
                                   (catch Throwable _ "unknown")))
      nil)))

(defn install-auto-persist!
  "Register the auto-persist backstop on the gated `:agent.ask/finalize` event.
   Idempotent — `register-hook!` replaces by id. The :match predicate scopes
   to eval-agent only."
  []
  (hooks/register-hook!
   :agent.ask/finalize
   ::eval-auto-persist
   eval-auto-persist
   :source   :eval-agent
   :match    (fn [{:keys [agent]}] (eval-agent? agent))
   :priority 50))

;; Self-install at namespace load.
(install-auto-persist!)

;; ============================================================================
;; Read-only bash backstop
;;
;; eval-agent's contract is non-mutation, and its prompt promises read-only
;; bash — but the roster binds the unrestricted bash tool. This gated
;; :agent.tool-use/pre hook enforces the contract: an obviously-mutating bash
;; command is REFUSED (the tool body is skipped via a :replace verdict and an
;; error result is returned), so the LLM sees the refusal and adapts WITHOUT
;; the command running and WITHOUT terminating the turn. Best-effort denylist
;; (covers rm/mv/git-write/sed -i/file-redirect) — a code backstop behind the
;; prompt's read-only contract, not a full shell sandbox.
;; ============================================================================

(def ^:private mutating-commands
  #{"rm" "rmdir" "unlink" "mv" "cp" "mkdir" "touch" "tee" "truncate"
    "dd" "chmod" "chown" "ln" "shred"})

(def ^:private mutating-git-subcommands
  #{"commit" "reset" "push" "rebase" "checkout" "merge" "add" "rm"
    "stash" "clean" "tag" "branch" "apply" "restore" "switch" "mv"})

(defn- segment-mutating? [seg]
  (let [toks (->> (str/split (str/trim (str seg)) #"\s+") (remove str/blank?))
        cmd  (first toks)]
    (cond
      (nil? cmd)                              false
      (contains? mutating-commands cmd)       true
      (and (= "git" cmd)
           (contains? mutating-git-subcommands (second toks))) true
      ;; in-place edit flags (sed -i, perl -i, perl -pi …)
      (and (#{"sed" "perl"} cmd)
           (some #(str/starts-with? % "-i") toks)) true
      :else false)))

(defn- mutating-bash?
  "Best-effort: true when `command` writes — a file-redirect to a real path
   (not /dev/null or an fd) or a mutating command in any pipeline/sequence
   segment."
  [command]
  (when (string? command)
    (or (boolean (re-find #">>?\s*(?!/dev/null\b)(?!&)[^\s&|;<>]" command))
        (some segment-mutating? (str/split command #"\||&&|\|\||;|&")))))

(defn eval-bash-guard
  "Gated `:agent.tool-use/pre` handler. Refuses a mutating bash command from
   eval-agent (read-only contract) via a :replace verdict carrying an error
   result — the command never runs and the turn continues."
  [{:keys [agent tool-name args]}]
  (when (and (eval-agent? agent)
             (= "bash" (name (or tool-name "")))
             (mutating-bash? (:command args)))
    {:result      :replace
     :reason      "eval-agent is read-only; mutating bash refused"
     :replacement {:status    "refused"
                   :exit-code nil
                   :output    ""
                   :error     (str "eval-agent is read-only — refusing mutating bash: "
                                   (pr-str (:command args))
                                   ". Use read-only commands (test -f, wc -l, grep, git diff, …) "
                                   "or hand the change to edit-agent / exec-agent.")}}))

(defn install-bash-guard!
  "Register the read-only bash backstop on the gated `:agent.tool-use/pre`
   event, scoped to eval-agent. Idempotent — register-hook! replaces by id."
  []
  (hooks/register-hook!
   :agent.tool-use/pre
   ::eval-bash-guard
   eval-bash-guard
   :source   :eval-agent
   :match    (fn [{:keys [agent]}] (eval-agent? agent))
   :priority 50))

(install-bash-guard!)
