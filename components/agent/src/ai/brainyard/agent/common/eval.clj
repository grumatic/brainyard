;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.eval
  "Eval-agent helpers — verdict + dossier emission + auto-persist hook.

   A *verdict* is the human-readable judgement file (per criterion class
   + confidence + evidence excerpts). A *dossier* is the schema'd handoff
   record (machine consumable, links to the verdict, carries pre/score/
   post/handoff blocks). Both share a slug + timestamp.

     .brainyard/agents/eval-agent/
     ├── verdicts/<yyyyMMdd-HHmmss>-<slug>.md
     ├── dossiers/<yyyyMMdd-HHmmss>-<slug>.md
     └── INDEX.md

   Per docs/eval-agent-design.md §7.

   v1 narrowings (matching plan/todo/exec):
   - POST-FLIGHT PASS/HOLD only (no REVISE auto-round)
   - C7 double-score check INFORMATIONAL only
   - No `eval$preflight`/`eval$postflight`/`eval$score-criterion` mega-helpers
   - R7 reproducibility check is instruction-level only (no opt-in flag)
   - Sibling rewires deferred — plan/todo/exec already accept any
     `Saved dossier:` in :agent-context."
  (:require [ai.brainyard.agent.common.guard :as guard]
            [ai.brainyard.agent.core.config :as config]
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
(def ^:private dossiers-subdir "dossiers")
(def ^:private base-rel ".brainyard/agents/eval-agent")
(def ^:private verdicts-dir-rel (str base-rel "/" verdicts-subdir))
(def ^:private dossiers-dir-rel (str base-rel "/" dossiers-subdir))
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

;; ---------------------------------------------------------------------------
;; YAML emission — mirror of exec.clj's, with namespaced-keyword + numeric-
;; flow-vector support, plus nested-flow-map (for criteria/recommendations
;; vectors of maps).
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

(defn- yaml-value
  [v]
  (cond
    (sequential? v) (yaml-flow-vector v)
    (map? v)        (yaml-flow-map v)
    :else           (yaml-scalar v)))

(defn- yaml-block
  [block-key entries]
  (apply str
         (name block-key) ":\n"
         (mapv (fn [[k v]]
                 (str "  " (name k) ": " (yaml-value v) "\n"))
               entries)))

(defn- ordered-pairs
  [m preferred]
  (let [in-pref  (filter #(contains? m %) preferred)
        leftover (remove (set preferred) (keys m))]
    (mapv (fn [k] [k (get m k)]) (concat in-pref leftover))))

(def ^:private pre-key-order
  [:verdict :checks :degradation :is_re_run :gather_question :refuse_reason])

(def ^:private score-key-order
  [:verdict :confidence :criteria :gaps :recommendations :degradation])

(def ^:private post-key-order
  [:verdict :rubric :revision_applied :revision_summary :holds])

(def ^:private handoff-key-order
  [:next_agent :next_call])

(defn- build-eval-dossier-frontmatter*
  [{:keys [slug verdict_path
           exec_dossier todo_dossier plan_dossier
           plan_path todo_path exec_run_record
           created turn-id session-id
           pre score post handoff]}]
  (str "---\n"
       "slug: " (yaml-scalar slug) "\n"
       "agent: eval-agent\n"
       "created: " (or created (now-iso)) "\n"
       (when verdict_path    (str "verdict_path: "    (yaml-value verdict_path) "\n"))
       (when exec_dossier    (str "exec_dossier: "    (yaml-value exec_dossier) "\n"))
       (when todo_dossier    (str "todo_dossier: "    (yaml-value todo_dossier) "\n"))
       (when plan_dossier    (str "plan_dossier: "    (yaml-value plan_dossier) "\n"))
       (when plan_path       (str "plan_path: "       (yaml-value plan_path) "\n"))
       (when todo_path       (str "todo_path: "       (yaml-value todo_path) "\n"))
       (when exec_run_record (str "exec_run_record: " (yaml-value exec_run_record) "\n"))
       (when turn-id         (str "turn_id: "         (yaml-string turn-id) "\n"))
       (when session-id      (str "session_id: "      (yaml-string session-id) "\n"))
       "\n"
       (yaml-block :pre     (ordered-pairs (or pre {})     pre-key-order))
       "\n"
       (yaml-block :score   (ordered-pairs (or score {})   score-key-order))
       "\n"
       (yaml-block :post    (ordered-pairs (or post {})    post-key-order))
       "\n"
       (yaml-block :handoff (ordered-pairs (or handoff {}) handoff-key-order))
       "---\n"))

;; ---------------------------------------------------------------------------
;; YAML parsing — same shape as exec.clj's; flow-style maps preserved as
;; raw strings (downstream callers can split as needed).
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
          ;; Naive split-on-top-comma; doesn't recurse into nested
          ;; flow-maps. Acceptable for v1 — the common consumer pattern is
          ;; "is criterion C in recommendations.criteria?" which can be
          ;; answered by substring search on the raw value.
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
      ;; Heuristic: if a flow-vector contains nested {…}, keep it as raw
      ;; string for v1 (parser doesn't recurse into nested maps inside
      ;; vectors). This catches `criteria: [{class: ...}, {...}]`.
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
  #{"pre" "score" "post" "handoff"})

(defn- parse-eval-dossier-yaml
  [lines]
  (loop [ls    lines
         acc   {}
         block nil]
    (if (empty? ls)
      acc
      (let [ln    (first ls)
            rest* (rest ls)]
        (cond
          (and (vector? block) (= :flat-block (first block))
               (re-matches #"^\s{2,}\S.*" ln))
          (if-let [[_ k v] (re-matches #"^\s+([\w_-]+):\s*(.*)$" ln)]
            (recur rest*
                   (assoc-in acc [(second block) (keyword k)] (parse-scalar v))
                   block)
            (recur rest* acc nil))

          (re-matches #"^([\w_-]+):\s*$" ln)
          (let [[_ k] (re-matches #"^([\w_-]+):\s*$" ln)]
            (if (block-keys k)
              (recur rest* (assoc acc (keyword k) {}) [:flat-block (keyword k)])
              (recur rest* acc nil)))

          (re-matches #"^([\w_-]+):\s*(.*)$" ln)
          (let [[_ k v] (re-matches #"^([\w_-]+):\s*(.*)$" ln)]
            (recur rest* (assoc acc (keyword k) (parse-scalar v)) nil))

          :else
          (recur rest* acc block))))))

;; ---------------------------------------------------------------------------
;; eval$dossier-slug
;; ---------------------------------------------------------------------------

(defcommand eval$dossier-slug
  "Deterministic kebab-case slug from a question (eval-agent stopwords, 60-char cap)."
  (fn [& {:keys [question max-chars]
          :or   {max-chars 60}}]
    (cond
      (not (string? question))
      {:error ":question is required (string)"}

      (or (not (integer? max-chars)) (<= max-chars 0))
      {:error ":max-chars must be a positive integer"}

      :else
      {:slug (eval-slugify question max-chars)}))
  :input-schema  [:map
                  [:question [:string {:desc "User question to slugify"}]]
                  [:max-chars {:optional true} :int]]
  :output-schema [:map
                  [:slug {:optional true} [:string {:desc "Kebab-case slug, stopwords dropped"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; eval$verdict-write — human-readable verdict body
;; ---------------------------------------------------------------------------

(defn- existing-slug-count
  "Count files in `<base>/<dir-rel>` matching the slug suffix."
  [base-dir dir-rel slug]
  (let [^java.io.File dir (io/file base-dir dir-rel)]
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
  "Compute final slug for the given dir-rel. Counts ONLY collisions in
   that directory so a paired verdict + dossier write in the same turn
   both come out with the same slug (e.g. both `twice`, then both
   `twice-2` on the next turn). The dossier carries `verdict_path` as
   the cross-reference; pairing across dirs is by-convention, not by
   filename match across dirs."
  [base-dir dir-rel base-slug]
  (let [n (existing-slug-count base-dir dir-rel base-slug)]
    (if (zero? n)
      base-slug
      (str base-slug "-" (inc n)))))

(defcommand eval$verdict-write
  "Write the human-readable verdict body under .brainyard/agents/eval-agent/verdicts/."
  (fn [& {:keys [slug content base-dir]
          :or   {base-dir (dossier-default-base-dir)}}]
    (cond
      (not (string? slug))    {:error ":slug is required (string)"}
      (not (string? content)) {:error ":content is required (string)"}
      (guard/content-violation content) (guard/content-violation content)
      :else
      (let [final-slug (final-slug-with-suffix base-dir verdicts-dir-rel slug)
            ts         (now-ts)
            rel-path   (str verdicts-dir-rel "/" ts "-" final-slug ".md")
            file       (io/file base-dir rel-path)]
        (.mkdirs (.getParentFile file))
        (spit file content)
        (mulog/log ::eval.verdict-write
                   :slug final-slug :path rel-path :bytes (count content))
        {:path (.getAbsolutePath file) :slug final-slug :ts ts})))
  :input-schema  [:map
                  [:slug [:string {:desc "Verdict slug (typically the source plan/todo/exec slug)"}]]
                  [:content [:string {:desc "Full verdict body (frontmatter + per-criterion table + recommendations)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:path {:optional true} [:string {:desc "Absolute path of the written verdict body"}]]
                  [:slug {:optional true} [:string {:desc "Final slug actually used (may have -N suffix)"}]]
                  [:ts {:optional true} [:string {:desc "Timestamp portion of filename"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; eval$dossier-frontmatter
;; ---------------------------------------------------------------------------

(defcommand eval$dossier-frontmatter
  "Build a YAML frontmatter block for an eval-agent dossier (see docs/eval-agent-design.md §7.3)."
  (fn [& {:keys [slug verdict-path exec-dossier todo-dossier plan-dossier
                 plan-path todo-path exec-run-record
                 pre score post handoff
                 created turn-id session-id]}]
    (cond
      (not (string? slug)) {:error ":slug is required (string)"}
      :else
      (let [fm (build-eval-dossier-frontmatter*
                {:slug slug
                 :verdict_path    verdict-path
                 :exec_dossier    exec-dossier
                 :todo_dossier    todo-dossier
                 :plan_dossier    plan-dossier
                 :plan_path       plan-path
                 :todo_path       todo-path
                 :exec_run_record exec-run-record
                 :pre             pre
                 :score           score
                 :post            post
                 :handoff         handoff
                 :created         created
                 :turn-id         turn-id
                 :session-id      session-id})]
        (mulog/log ::eval.dossier-frontmatter
                   :slug slug
                   :pre-verdict (or (:verdict pre) :n-a)
                   :score-verdict (or (:verdict score) :n-a)
                   :post-verdict (or (:verdict post) :n-a)
                   :next-agent (:next_agent handoff)
                   :n-criteria (count (:criteria score)))
        {:frontmatter fm})))
  :input-schema  [:map
                  [:slug [:string {:desc "Verdict slug"}]]
                  [:verdict-path {:optional true} [:string {:desc "Repo-relative path to the human-readable verdict body"}]]
                  [:exec-dossier {:optional true} [:string {:desc "Path to the consumed exec-agent dossier"}]]
                  [:todo-dossier {:optional true} [:string {:desc "Path to the consumed todo-agent dossier"}]]
                  [:plan-dossier {:optional true} [:string {:desc "Path to the source plan-agent dossier"}]]
                  [:plan-path {:optional true} [:string {:desc "Repo-relative path to the plan body"}]]
                  [:todo-path {:optional true} [:string {:desc "Repo-relative path to the todo body"}]]
                  [:exec-run-record {:optional true} [:string {:desc "Repo-relative path to the exec run record"}]]
                  [:pre {:optional true} [:map {:desc "Pre-flight outcomes (verdict, checks, degradation, is_re_run, gather_question, refuse_reason)"}]]
                  [:score {:optional true} [:map {:desc "Score outcomes (verdict :achieved/:partially-achieved/:not-achieved, confidence, criteria, gaps, recommendations, degradation)"}]]
                  [:post {:optional true} [:map {:desc "Post-flight outcomes (verdict :pass/:hold, rubric, revision_applied, holds)"}]]
                  [:handoff {:optional true} [:map {:desc "Handoff suggestion (next_agent, next_call)"}]]
                  [:created {:optional true} [:string {:desc "ISO-8601 created timestamp (default: now)"}]]
                  [:turn-id {:optional true} [:string {:desc "Trajectory turn-id"}]]
                  [:session-id {:optional true} [:string {:desc "Trajectory session-id"}]]]
  :output-schema [:map
                  [:frontmatter {:optional true} [:string {:desc "Full YAML frontmatter block, trailing newline included"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; eval$dossier-write
;; ---------------------------------------------------------------------------

(defcommand eval$dossier-write
  "Write an eval-agent dossier under .brainyard/agents/eval-agent/dossiers/ (auto-suffixes on collision)."
  (fn [& {:keys [slug content base-dir]
          :or   {base-dir (dossier-default-base-dir)}}]
    (cond
      (not (string? slug))    {:error ":slug is required (string)"}
      (not (string? content)) {:error ":content is required (string)"}
      (not (re-find #"(?s)\A---\n.+?\n---\n" content))
      {:error (str ":content must begin with a YAML frontmatter block "
                   "(---\\n...\\n---\\n). Build it via eval$dossier-frontmatter "
                   "and prepend before writing: "
                   "(eval$dossier-write :slug ... :content (str fm body))")}
      (guard/content-violation content) (guard/content-violation content)
      :else
      (let [final-slug (final-slug-with-suffix base-dir dossiers-dir-rel slug)
            ts         (now-ts)
            rel-path   (str dossiers-dir-rel "/" ts "-" final-slug ".md")
            file       (io/file base-dir rel-path)]
        (.mkdirs (.getParentFile file))
        (spit file content)
        (mulog/log ::eval.dossier-write
                   :slug final-slug :path rel-path
                   :bytes (count content) :collision? (not= slug final-slug))
        {:path (.getAbsolutePath file) :slug final-slug :ts ts})))
  :input-schema  [:map
                  [:slug [:string {:desc "Verdict slug"}]]
                  [:content [:string {:desc "Full file content (frontmatter + body)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:path {:optional true} [:string {:desc "Absolute path of the written dossier"}]]
                  [:slug {:optional true} [:string {:desc "Final slug actually used (may have -N suffix)"}]]
                  [:ts {:optional true} [:string {:desc "Timestamp portion of filename"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; eval$dossier-index-append
;; ---------------------------------------------------------------------------

(defn- index-line
  [{:keys [path slug pre-verdict score-verdict confidence post-verdict next-agent]}]
  (let [filename (-> path (str/split #"/") last)
        pre-tok    (str "pre:" (name pre-verdict))
        score-tok  (str "verdict:" (if (nil? score-verdict)
                                     "n/a"
                                     (-> score-verdict name str/upper-case)))
        conf-tok   (when confidence (str "(conf:" (name confidence) ")"))
        post-tok   (str "post:" (if (nil? post-verdict) "n/a" (name post-verdict)))
        next-tok   (str "→ " (name (or next-agent :unspecified)))]
    (str "- " (now-yyyy-mm-dd-hh-mm)
         " [" slug "](" dossiers-subdir "/" filename ") — "
         pre-tok " · " score-tok (when conf-tok (str " " conf-tok))
         " · " post-tok " · " next-tok "\n")))

(defcommand eval$dossier-index-append
  "Prepend a one-line entry to .brainyard/agents/eval-agent/INDEX.md (newest-first)."
  (fn [& {:keys [path slug pre-verdict score-verdict confidence
                 post-verdict next-agent base-dir]
          :or   {base-dir (dossier-default-base-dir)}}]
    (cond
      (not (string? path)) {:error ":path is required (string)"}
      (not (string? slug)) {:error ":slug is required (string)"}
      (not (or (keyword? pre-verdict) (string? pre-verdict)))
      {:error ":pre-verdict is required (keyword or string)"}
      :else
      (let [pre-kw   (if (keyword? pre-verdict) pre-verdict (keyword pre-verdict))
            score-kw (cond
                       (keyword? score-verdict) score-verdict
                       (string? score-verdict)  (keyword score-verdict)
                       :else                    nil)
            conf-kw  (cond
                       (keyword? confidence) confidence
                       (string? confidence)  (keyword confidence)
                       :else                 nil)
            post-kw  (cond
                       (keyword? post-verdict) post-verdict
                       (string? post-verdict)  (keyword post-verdict)
                       :else                   nil)
            next-kw  (cond
                       (keyword? next-agent) next-agent
                       (string? next-agent)  (keyword next-agent)
                       :else                 :unspecified)
            line     (index-line {:path path :slug slug
                                  :pre-verdict pre-kw
                                  :score-verdict score-kw
                                  :confidence conf-kw
                                  :post-verdict post-kw
                                  :next-agent next-kw})
            file     (io/file base-dir index-rel)
            existing (if (.isFile file) (slurp file) "")]
        (.mkdirs (.getParentFile file))
        (spit file (str line existing))
        (mulog/log ::eval.dossier-index
                   :slug slug :path path
                   :pre-verdict pre-kw :score-verdict score-kw
                   :confidence conf-kw :post-verdict post-kw
                   :next-agent next-kw)
        {:appended true :line line})))
  :input-schema  [:map
                  [:path [:string {:desc "Repo-relative path of the dossier"}]]
                  [:slug [:string {:desc "Verdict slug"}]]
                  [:pre-verdict [:string {:desc "Pre-flight verdict: go | gather | refuse"}]]
                  [:score-verdict {:optional true} [:string {:desc "Score verdict: achieved | partially-achieved | not-achieved (omit when no SCORE ran)"}]]
                  [:confidence {:optional true} [:string {:desc "Aggregate confidence: high | medium | low"}]]
                  [:post-verdict {:optional true} [:string {:desc "Post-flight verdict: pass | hold (omit when no POST-FLIGHT ran)"}]]
                  [:next-agent {:optional true} [:string {:desc "Recommended next agent (e.g. plan-agent | todo-agent | exec-agent | user | none)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:appended {:optional true} [:boolean {:desc "true on success"}]]
                  [:line {:optional true} [:string {:desc "The exact line that was prepended"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; eval$read-dossier
;; ---------------------------------------------------------------------------

(defcommand eval$read-dossier
  "Read and parse the YAML frontmatter from an eval-agent dossier."
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
                  [:path [:string {:desc "Repo-relative or absolute path to an eval-agent dossier"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:slug {:optional true} [:string {:desc "Verdict slug"}]]
                  [:verdict_path {:optional true} [:string {:desc "Path to the human-readable verdict body"}]]
                  [:exec_dossier {:optional true} [:string {:desc "Path to the consumed exec-agent dossier"}]]
                  [:todo_dossier {:optional true} [:string {:desc "Path to the consumed todo-agent dossier"}]]
                  [:plan_dossier {:optional true} [:string {:desc "Path to the source plan-agent dossier"}]]
                  [:pre {:optional true} [:map {:desc "Pre-flight sub-block"}]]
                  [:score {:optional true} [:map {:desc "Score sub-block"}]]
                  [:post {:optional true} [:map {:desc "Post-flight sub-block"}]]
                  [:handoff {:optional true} [:map {:desc "Handoff sub-block"}]]
                  [:error {:optional true} [:string {:desc "Error if file missing or no frontmatter"}]]])

;; ---------------------------------------------------------------------------
;; eval$find — search prior verdicts (C7 double-score check + history)
;; ---------------------------------------------------------------------------

(defcommand eval$find
  "Search prior eval-agent dossiers by slug (optionally by exec run-record). Newest-first."
  (fn [& {:keys [slug run-record base-dir]
          :or   {base-dir (dossier-default-base-dir)}}]
    (cond
      (not (string? slug))
      {:error ":slug is required (string)"}

      :else
      (let [dir (io/file base-dir dossiers-dir-rel)]
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
                               (let [fm (parse-eval-dossier-yaml lines)
                                     match? (or (nil? run-rec-norm)
                                                (= run-rec-norm (:exec_run_record fm)))]
                                 (when match?
                                   {:path           (str dossiers-dir-rel "/" (.getName f))
                                    :slug           (:slug fm)
                                    :created        (:created fm)
                                    :score_verdict  (get-in fm [:score :verdict])
                                    :confidence     (get-in fm [:score :confidence])
                                    :exec_run_record (:exec_run_record fm)})))))
                     vec)]
            (mulog/log ::eval.find :slug slug :run-record run-record :n-matches (count matches))
            {:matches matches :n-matches (count matches)})))))
  :input-schema  [:map
                  [:slug [:string {:desc "Slug to search for"}]]
                  [:run-record {:optional true} [:string {:desc "Optional: only match dossiers whose :exec_run_record equals this path (used for C7 'same exec turn' detection)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:matches [:vector {:desc "Matching prior dossiers, newest-first"} :map]]
                  [:n-matches [:int {:desc "Number of matches"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ---------------------------------------------------------------------------
;; eval$next-handoff
;; ---------------------------------------------------------------------------

(defn- handoff-from-state
  [pre-verdict score-verdict slug verdict-path dossier-path]
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
       :next-call (str "Per-criterion recommendations are in score.recommendations. "
                       "Most common: (todo-agent {…}) for missing items, "
                       "or accept partial result via (doc$update "
                       "{:kind :todo :slug \"" (or slug "<slug>") "\" :status :completed}).")}

      :not-achieved
      {:next-agent "plan-agent"
       :next-call (str "Recommended primary path: re-spec via plan-agent. "
                       "(plan-agent {:question \"Revise approach: <gap>\""
                       ctx "}). "
                       "Per-criterion recommendations also in score.recommendations.")}

      :n-a
      (case pre-verdict
        :gather
        {:next-agent "user"
         :next-call (str "Provide the missing input named in dossier.gather_question; "
                         "typically run exec-agent first to produce evidence.")}

        :refuse
        {:next-agent "none"
         :next-call (str "See dossier.refuse_reason for the redirect (typically "
                         "exec-agent re-run when evidence was empty, or plan-agent "
                         "when acceptance was empty).")}

        {:next-agent "user"
         :next-call (str "Inspect the dossier and decide next step.")}))))

(defcommand eval$next-handoff
  "Compute the recommended next agent and direct-invocation form from :pre/:score verdicts."
  (fn [& {:keys [pre score slug verdict-path dossier-path]}]
    (let [pre-v   (when pre   (:verdict pre))
          score-v (when score (:verdict score))]
      (handoff-from-state pre-v score-v slug verdict-path dossier-path)))
  :input-schema  [:map
                  [:pre {:optional true} [:map {:desc "Pre-flight outcome map (only :verdict required)"}]]
                  [:score {:optional true} [:map {:desc "Score outcome map (only :verdict required); omit on GATHER/REFUSE"}]]
                  [:slug {:optional true} [:string {:desc "Verdict slug"}]]
                  [:verdict-path {:optional true} [:string {:desc "Repo-relative path to the verdict body"}]]
                  [:dossier-path {:optional true} [:string {:desc "Repo-relative path to the dossier"}]]]
  :output-schema [:map
                  [:next-agent [:string {:desc "Recommended next agent: plan-agent | todo-agent | exec-agent | user | none"}]]
                  [:next-call [:string {:desc "Exact direct-invocation form (<agent-name> {…}) or user-side instruction"}]]])

;; ---------------------------------------------------------------------------
;; Public roster
;; ---------------------------------------------------------------------------

(def eval-dossier-helpers
  "Vector of all eval$* helper vars (slug, verdict-write, dossier-
   frontmatter, dossier-write, dossier-index-append, read-dossier, find,
   next-handoff). eval-agent appends these to its :agent-tools roster."
  [#'eval$dossier-slug
   #'eval$verdict-write
   #'eval$dossier-frontmatter
   #'eval$dossier-write
   #'eval$dossier-index-append
   #'eval$read-dossier
   #'eval$find
   #'eval$next-handoff])

;; ============================================================================
;; Auto-persist hook
;;
;; Same proven pattern as plan/todo/exec-auto-persist. The hook reconstructs
;; a minimal dossier from answer text when the LLM forgets the helpers or
;; hallucinates the `Saved dossier:` path. Idempotency is on-disk-existence
;; check (catches hallucinations).
;;
;; Note: the hook only writes the DOSSIER, not the verdict body — the
;; verdict body is a richer artifact the LLM should produce deliberately
;; (the dossier carries enough info to reconstruct the verdict body if
;; needed).
;; ============================================================================

(def saved-verdict-prefix
  "Stable prefix the agent emits when a verdict body was written this turn."
  "Saved verdict: ")

(def saved-dossier-prefix
  "Stable prefix the agent emits when a dossier was persisted this turn."
  "Saved dossier: ")

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
        (-> (subs after 0 end)
            (str/replace #"^[`'\"\s]+" "")
            (str/replace #"[`'\"\.,\s]+$" "")
            str/trim)))))

(defn- absolute->repo-relative
  [base-dir ^String path]
  (cond
    (or (nil? path) (str/blank? path))
    nil

    (and base-dir (str/starts-with? path (str base-dir "/")))
    (subs path (inc (count base-dir)))

    :else path))

(defn- dossier-already-saved?
  [^String answer base-dir]
  (when-let [claimed (extract-line-after answer saved-dossier-prefix)]
    (let [file (if (.isAbsolute (io/file claimed))
                 (io/file claimed)
                 (io/file base-dir claimed))]
      (.isFile file))))

(defn- detect-pre-verdict [^String answer]
  (cond
    (re-find #"(?im)^\s*Refused:\s*\S" answer) :refuse
    (re-find #"(?im)^\s*Need:\s*\S"    answer) :gather
    :else                                       :go))

(defn- detect-score-verdict
  "Infer score verdict from the `Verdict:` answer line. Recognizes:
   ACHIEVED, PARTIALLY_ACHIEVED, NOT_ACHIEVED. Returns nil when no
   `Verdict:` line found (typical for GATHER/REFUSE)."
  [^String answer]
  (when-let [line (extract-line-after answer "Verdict: ")]
    (cond
      (str/starts-with? line "ACHIEVED") :achieved
      (str/starts-with? (str/upper-case line) "PARTIALLY_ACHIEVED") :partially-achieved
      (str/starts-with? line "PARTIALLY_ACHIEVED") :partially-achieved
      (str/starts-with? line "NOT_ACHIEVED") :not-achieved
      :else nil)))

(defn- detect-confidence
  "Pull confidence from `Verdict: <X> (confidence: <Y>)` answer line."
  [^String answer]
  (when-let [line (extract-line-after answer "Verdict: ")]
    (when-let [[_ conf] (re-find #"(?i)\bconfidence:\s*(high|medium|low)" line)]
      (keyword (str/lower-case conf)))))

(defn- detect-post-verdict [^String answer score-verdict]
  (cond
    (nil? score-verdict)                      nil
    (re-find #"(?im)^\s*Hold:\s*\S" answer)   :hold
    :else                                     :pass))

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

(defn materialize-auto-dossier!
  "Core of the eval auto-persist hook — agent-state-free. Given an answer
   string + question + base-dir, reconstruct a minimal dossier from the
   answer text and persist it. Returns `{:path :slug :pre-verdict
   :score-verdict :post-verdict}` or nil when skipped.

   The verdict body is NOT auto-persisted — the dossier carries enough
   info to reconstruct it later if needed."
  [{:keys [answer question base-dir]}]
  (cond
    (or (not (string? answer)) (str/blank? answer))
    nil

    (dossier-already-saved? answer base-dir)
    nil

    :else
    (let [pre-verdict   (detect-pre-verdict answer)
          score-verdict (detect-score-verdict answer)
          confidence    (detect-confidence answer)
          post-verdict  (detect-post-verdict answer score-verdict)
          verdict-raw   (extract-line-after answer saved-verdict-prefix)
          verdict-path  (when verdict-raw
                          (absolute->repo-relative base-dir verdict-raw))
          slug          (or (when verdict-raw
                              (-> verdict-raw (str/split #"/") last
                                  (str/replace #"\.md$" "")))
                            (:slug (eval$dossier-slug
                                    :question (or question "auto-persisted")))
                            "eval")
          summary       (one-line-summary answer 200)
          handoff       (let [pre-map  {:verdict pre-verdict}
                              score-map (when score-verdict
                                          (cond-> {:verdict score-verdict}
                                            confidence (assoc :confidence confidence)))
                              kebab    (#'eval$next-handoff
                                        :pre   pre-map
                                        :score score-map
                                        :slug  slug)]
                          {:next_agent (:next-agent kebab)
                           :next_call  (:next-call  kebab)})
          fm            (:frontmatter
                         (#'eval$dossier-frontmatter
                          :slug         slug
                          :verdict-path verdict-path
                          :pre          {:verdict pre-verdict}
                          :score        (when score-verdict
                                          (cond-> {:verdict score-verdict}
                                            confidence (assoc :confidence confidence)))
                          :post         (when post-verdict {:verdict post-verdict})
                          :handoff      handoff))
          body          (str "# Eval dossier (auto-persisted)\n\n"
                             "*Reconstructed from the agent's answer text — the LLM did "
                             "not call eval$dossier-write itself this turn.*\n\n"
                             "## Summary\n" summary "\n\n"
                             "## Original answer\n" answer "\n")
          write-result  (#'eval$dossier-write :slug slug
                                              :content (str fm body)
                                              :base-dir base-dir)]
      (if (:error write-result)
        (do (mulog/warn ::eval.auto-persist-write-failed
                        :slug slug :error (:error write-result))
            nil)
        (do
          (#'eval$dossier-index-append
           :path          (:path write-result)
           :slug          (:slug write-result)
           :pre-verdict   pre-verdict
           :score-verdict score-verdict
           :confidence    confidence
           :post-verdict  post-verdict
           :next-agent    (or (:next_agent handoff) :unspecified)
           :base-dir      base-dir)
          (mulog/log ::eval.auto-persist
                     :slug          (:slug write-result)
                     :path          (:path write-result)
                     :pre-verdict   pre-verdict
                     :score-verdict score-verdict
                     :confidence    confidence
                     :post-verdict  post-verdict
                     :answer-chars  (count answer)
                     :had-verdict-path? (boolean verdict-raw))
          {:path          (:path write-result)
           :slug          (:slug write-result)
           :pre-verdict   pre-verdict
           :score-verdict score-verdict
           :post-verdict  post-verdict})))))

(defn eval-auto-persist
  "Gated handler for `:agent.ask/finalize`. When the LLM skipped the
   FINAL-STEP checklist, materializes the dossier AND returns a `:replace`
   decision injecting the absent `Saved dossier: <path>` handoff line into the
   answer — so answer-grepping consumers (main-agent routing) see it even when
   the LLM forgot. Idempotent — no-op when the line is already present or
   nothing was persisted. Defensive — failures logged, never re-thrown."
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
            path      (:path persisted)]
        (when (and path (not (str/includes? answer "Saved dossier:")))
          {:result      :replace
           :reason      "injected absent Saved-dossier handoff line"
           :replacement (assoc result :answer (str answer "\n\nSaved dossier: " path))})))
    (catch Throwable t
      (mulog/error ::eval.auto-persist-failed
                   :exception t
                   :agent-id  (try (proto/agent-id agent)
                                   (catch Throwable _ "unknown")))
      nil)))

(defn install-auto-persist!
  "Register the auto-persist hook on the gated `:agent.ask/finalize` event.
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
                                   "or hand the change to update-agent / exec-agent.")}}))

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
