;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.explore
  "Explore-agent quality-of-life helpers — the 6 small functions that compress
   the persistence + handoff flow described in docs/explore-agent-design.md §5,
   plus an `:agent.ask/post` hook that auto-persists when the LLM forgets to
   call the helpers itself (smaller models like haiku regularly skip the
   FINAL-STEP CHECKLIST in the agent instruction).

   Each helper is a `defcommand` so it surfaces in the unified tool registry
   and is auto-bound into the SCI sandbox (callable as `(explore$slug ...)` in
   a clojure fence). They are not new primitives — explore-agent works without
   them, falling back to the inline write-file skeleton in its tool-context —
   but they shrink the prompt because the LLM no longer has to inline equivalent
   helpers in every persisted run.

   Frontmatter shape is hand-rolled (no clj-yaml dep) because the keys are
   fully under our control: a flat key/value head plus a nested `entities`
   sub-map. Round-trip stability matters more than supporting arbitrary YAML."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.text SimpleDateFormat)
           (java.util Date)))

;; ============================================================================
;; Constants & utilities
;; ============================================================================

(def ^:private results-subdir "results")
(def ^:private results-base ".brainyard/agents/explore-agent")
(def ^:private results-dir-rel (str results-base "/" results-subdir))
(def ^:private index-rel (str results-base "/INDEX.md"))

(def ^:private slug-stopwords
  #{"a" "an" "the" "is" "are" "and" "or" "of" "in" "on" "at" "to" "for"
    "by" "with" "from" "as" "but" "if" "then" "than" "so"
    "what" "where" "when" "who" "whom" "why" "how" "which"
    "do" "does" "did" "can" "could" "would" "should" "shall" "will"
    "this" "that" "these" "those" "it" "its" "we" "they" "you" "i"
    "be" "been" "being" "was" "were" "have" "has" "had"})

(defn- now-ts
  "Returns yyyyMMdd-HHmmss for the current instant. Extracted as a private fn
   so tests can `with-redefs` it for deterministic filenames."
  []
  (.format (SimpleDateFormat. "yyyyMMdd-HHmmss") (Date.)))

(defn- now-iso
  "Returns ISO-8601 instant string for frontmatter `created` field."
  []
  (str (java.time.Instant/now)))

(defn- now-yyyy-mm-dd-hh-mm
  "Returns 'YYYY-MM-DD HH:MM' for INDEX.md line prefix (UTC).
   ISO format is 'YYYY-MM-DDTHH:MM…'; we replace the 'T' with a space to
   match the design doc spec."
  []
  (-> (subs (now-iso) 0 16)
      (str/replace "T" " ")))

;; ============================================================================
;; explore$slug — deterministic kebab-case slug
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
        joined (if kept (str/join "-" kept) "exploration")]
    (subs joined 0 (min max-chars (count joined)))))

(defcommand explore$slug
  "Deterministic kebab-case slug from a question; drops stopwords, caps at 60 chars. Same question always yields the same slug."
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
                  [:question [:string {:desc "User question to slugify"}]]
                  [:max-chars {:optional true} :int]]
  :output-schema [:map
                  [:slug {:optional true} [:string {:desc "Kebab-case slug, stopwords dropped"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; explore$frontmatter — build YAML-flavored frontmatter block
;; ============================================================================

(defn- yaml-string
  "Quote a string value for YAML emission. Conservatively double-quote and
   escape backslashes + double-quotes — YAML 1.2 double-quoted scalar rules."
  [s]
  (str "\"" (-> (str s)
                (str/replace "\\" "\\\\")
                (str/replace "\"" "\\\""))
       "\""))

(def ^:private bareword-re #"^[A-Za-z0-9_./:-]+$")

(defn- yaml-flow-vector
  "Format a coll as a YAML flow-style vector: `[a, b, c]`.
   Strings matching the bareword charset (alnum + `_./:-`) are emitted
   unquoted — matches the design-doc shape and keeps surfaces / file paths /
   short tags readable. Anything else (whitespace, commas, special chars)
   is double-quoted for correctness."
  [xs]
  (str "[" (str/join ", " (map (fn [x]
                                 (cond
                                   (keyword? x) (name x)
                                   (string? x)
                                   (if (re-matches bareword-re x)
                                     x
                                     (yaml-string x))
                                   :else (str x)))
                               xs))
       "]"))

(defn- format-summary
  "Frontmatter summary uses YAML folded-block scalar (`>`) so multi-line
   summaries don't break parsing. We also collapse internal newlines because
   single-line keeps the file `head`-friendly."
  [s]
  (let [collapsed (-> (str s) (str/replace #"\s+" " ") str/trim)]
    (str ">\n  " collapsed)))

(defn- build-frontmatter*
  [{:keys [question slug surfaces entities summary
           created turn-id session-id]}]
  (let [{:keys [files urls mcp_tools skills]
         :or   {files [] urls [] mcp_tools [] skills []}} entities]
    (str "---\n"
         "slug: " slug "\n"
         "question: " (yaml-string question) "\n"
         "created: " (or created (now-iso)) "\n"
         "agent: explore-agent\n"
         "surfaces: " (yaml-flow-vector surfaces) "\n"
         "entities:\n"
         "  files: " (yaml-flow-vector files) "\n"
         "  urls: " (yaml-flow-vector urls) "\n"
         "  mcp_tools: " (yaml-flow-vector mcp_tools) "\n"
         "  skills: " (yaml-flow-vector skills) "\n"
         "summary: " (format-summary summary) "\n"
         (when turn-id (str "turn_id: " (yaml-string turn-id) "\n"))
         (when session-id (str "session_id: " (yaml-string session-id) "\n"))
         "---\n")))

(defcommand explore$frontmatter
  "Build a YAML frontmatter block for an explore-agent result file."
  (fn [& {:keys [question slug surfaces entities summary
                 created turn-id session-id]
          :as   m}]
    (cond
      (not (string? question)) {:error ":question is required (string)"}
      (not (string? slug))     {:error ":slug is required (string)"}
      (not (sequential? surfaces)) {:error ":surfaces must be a vector"}
      (not (string? summary))  {:error ":summary is required (string)"}
      :else
      (let [fm (build-frontmatter*
                {:question question
                 :slug slug
                 :surfaces surfaces
                 :entities (or entities {})
                 :summary summary
                 :created created
                 :turn-id turn-id
                 :session-id session-id})]
        (mulog/log ::explore.frontmatter
                   :slug slug
                   :surfaces (vec surfaces)
                   :n-entities (reduce + 0 (map (comp count val) (or entities {}))))
        {:frontmatter fm})))
  :input-schema  [:map
                  [:question [:string {:desc "Verbatim user question"}]]
                  [:slug [:string {:desc "Kebab-case slug (use explore$slug to derive)"}]]
                  [:surfaces [:vector {:desc "Surfaces touched: any of \"filesystem\" \"web\" \"mcp\" \"skills\""} :string]]
                  [:entities {:optional true} [:map {:desc "Entity citations: :files :urls :mcp_tools :skills (vectors of strings)"}]]
                  [:summary [:string {:desc "One-paragraph distilled answer (will be folded onto one line)"}]]
                  [:created {:optional true} [:string {:desc "ISO-8601 created timestamp (default: now)"}]]
                  [:turn-id {:optional true} [:string {:desc "Trajectory turn-id for cross-reference"}]]
                  [:session-id {:optional true} [:string {:desc "Trajectory session-id for cross-reference"}]]]
  :output-schema [:map
                  [:frontmatter {:optional true} [:string {:desc "Full YAML frontmatter block, trailing newline included"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; explore$write — write the result file with collision-aware suffix
;; ============================================================================

(defn- existing-slugs-for
  "Scan `<base-dir>/.brainyard/agents/explore-agent/results/` for files ending with
   `-<slug>.md` (any prefix). Returns the count of files matching the slug
   — used to compute the suffix.

   Robust to the directory not existing yet (returns 0). Pure side-effect-free
   filesystem scan."
  [base-dir slug]
  (let [^java.io.File dir (io/file base-dir results-dir-rel)]
    (if (.isDirectory dir)
      (let [;; Filename pattern: <ts>-<slug>.md   OR   <ts>-<slug>-<N>.md
            ;; We only want collisions on the EXACT slug, so a strict suffix
            ;; match works: filename ends with "-<slug>.md" or "-<slug>-N.md".
            slug-re (re-pattern (str "(?i)-" (java.util.regex.Pattern/quote slug)
                                     "(-\\d+)?\\.md$"))]
        (->> (.listFiles dir)
             (filter (fn [^java.io.File f] (.isFile f)))
             (map (fn [^java.io.File f] (.getName f)))
             (filter #(re-find slug-re %))
             count))
      0)))

(defn- final-slug-with-suffix
  [base-dir base-slug]
  (let [n (existing-slugs-for base-dir base-slug)]
    (if (zero? n)
      base-slug
      (str base-slug "-" (inc n)))))

(defcommand explore$write
  "Write a result file under .brainyard/agents/explore-agent/results/ as <ts>-<slug>.md; appends -N suffix on slug collision."
  (fn [& {:keys [slug content base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? slug))    {:error ":slug is required (string)"}
      (not (string? content)) {:error ":content is required (string)"}
      :else
      (let [final-slug (final-slug-with-suffix base-dir slug)
            ts         (now-ts)
            rel-path   (str results-dir-rel "/" ts "-" final-slug ".md")
            file       (io/file base-dir rel-path)]
        (.mkdirs (.getParentFile file))
        (spit file content)
        (mulog/log ::explore.persist
                   :slug final-slug
                   :path rel-path
                   :bytes (count content)
                   :collision? (not= slug final-slug))
        {:path (.getAbsolutePath file) :slug final-slug :ts ts})))
  :input-schema  [:map
                  [:slug [:string {:desc "Slug from explore$slug (will get -N suffix on collision)"}]]
                  [:content [:string {:desc "Full file content (frontmatter + body)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: System/user.dir)"}]]]
  :output-schema [:map
                  [:path {:optional true} [:string {:desc "Absolute path of the written file"}]]
                  [:slug {:optional true} [:string {:desc "Final slug actually used (may have -N suffix)"}]]
                  [:ts {:optional true} [:string {:desc "Timestamp portion of filename (yyyyMMdd-HHmmss)"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; explore$index-append — prepend a line to INDEX.md (newest-first)
;; ============================================================================

(defn- index-line
  [{:keys [path slug surfaces summary]}]
  (let [filename (-> path (str/split #"/") last)
        surfaces-str (str/join ", " (map name surfaces))
        ;; Single-line summary, conservative truncation for readability.
        summary-trim (-> (str summary) (str/replace #"\s+" " ") str/trim)
        summary-cap  (if (> (count summary-trim) 200)
                       (str (subs summary-trim 0 197) "…")
                       summary-trim)]
    (str "- " (now-yyyy-mm-dd-hh-mm)
         " [" slug "](" results-subdir "/" filename ") — "
         surfaces-str " · *" summary-cap "*\n")))

(defcommand explore$index-append
  "Prepend a one-line entry to .brainyard/agents/explore-agent/INDEX.md (newest-first ordering)."
  (fn [& {:keys [path slug surfaces summary base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? path))    {:error ":path is required (string)"}
      (not (string? slug))    {:error ":slug is required (string)"}
      (not (sequential? surfaces)) {:error ":surfaces must be a vector"}
      (not (string? summary)) {:error ":summary is required (string)"}
      :else
      (let [line     (index-line {:path path :slug slug :surfaces surfaces :summary summary})
            file     (io/file base-dir index-rel)
            existing (if (.isFile file) (slurp file) "")]
        (.mkdirs (.getParentFile file))
        (spit file (str line existing))
        (mulog/log ::explore.index
                   :slug slug
                   :path path
                   :existing-bytes (count existing))
        {:appended true :line line})))
  :input-schema  [:map
                  [:path [:string {:desc "Repo-relative path of the result file (from explore$write)"}]]
                  [:slug [:string {:desc "Slug used in the result file"}]]
                  [:surfaces [:vector {:desc "Surfaces touched"} :string]]
                  [:summary [:string {:desc "One-line summary (collapsed; truncated to 200 chars)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: System/user.dir)"}]]]
  :output-schema [:map
                  [:appended {:optional true} [:boolean {:desc "true on success"}]]
                  [:line {:optional true} [:string {:desc "The exact line that was prepended"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; explore$read-frontmatter — cheap read of just the leading --- block
;; ============================================================================

(defn- read-frontmatter-lines
  "Read lines from the file until the second `---` (frontmatter close).
   Returns the inner lines (between, exclusive). Returns nil if the file
   doesn't start with `---`."
  [^java.io.File file]
  (with-open [r (io/reader file)]
    (let [reader (java.io.BufferedReader. r)
          first-line (.readLine reader)]
      (when (= "---" first-line)
        (loop [acc []]
          (let [ln (.readLine reader)]
            (cond
              (nil? ln)        nil           ; EOF before close
              (= "---" ln)     acc           ; close marker
              :else            (recur (conj acc ln)))))))))

(defn- parse-flow-vector
  "Parse a YAML flow vector like `[\"a\", \"b\"]` or `[a, b]` into a vector
   of strings. Lenient — strips quotes/whitespace per element."
  [s]
  (let [trimmed (str/trim (str s))]
    (if (and (str/starts-with? trimmed "[") (str/ends-with? trimmed "]"))
      (let [inner (subs trimmed 1 (dec (count trimmed)))]
        (if (str/blank? inner)
          []
          (->> (str/split inner #",")
               (map str/trim)
               (map (fn [tok]
                      (if (and (str/starts-with? tok "\"") (str/ends-with? tok "\""))
                        (-> tok
                            (subs 1 (dec (count tok)))
                            (str/replace "\\\"" "\"")
                            (str/replace "\\\\" "\\"))
                        tok)))
               vec)))
      [])))

(defn- parse-flat-yaml
  "Parse the frontmatter inner lines into a map. Recognizes:
   - `key: value`                       → flat string entry
   - `key: \"quoted value\"`            → unquoted string
   - `key: [a, b, c]`                   → vector of strings
   - `key: >` followed by indented line → folded scalar (single line)
   - `entities:` (block) followed by `  subkey: ...` lines → nested map

   Lenient and targeted at the shape that explore$frontmatter emits; not a
   general YAML parser."
  [lines]
  (loop [ls    lines
         acc   {}
         block nil]            ; nil | :entities | :folded-key
    (if (empty? ls)
      acc
      (let [ln    (first ls)
            rest* (rest ls)]
        (cond
          ;; Indented entities sub-key
          (and (= block :entities) (re-matches #"^\s{2,}\S.*" ln))
          (if-let [[_ k v] (re-matches #"^\s+([\w_-]+):\s*(.*)$" ln)]
            (let [parsed (cond
                           (re-matches #"^\[.*\]$" (str/trim v))
                           (parse-flow-vector v)
                           (str/blank? v) []
                           :else (str/trim v))]
              (recur rest*
                     (assoc-in acc [:entities (keyword k)] parsed)
                     :entities))
            (recur rest* acc nil))

          ;; Folded-scalar continuation (single line indented under `>`)
          (and (vector? block) (= :folded-key (first block)))
          (let [k (second block)
                v (str/trim ln)]
            (recur rest* (assoc acc k v) nil))

          ;; entities: header → start nested-map mode
          (re-matches #"^entities:\s*$" ln)
          (recur rest* (assoc acc :entities {}) :entities)

          ;; key: > (folded scalar — next non-blank line is the value)
          (re-matches #"^([\w_-]+):\s*>\s*$" ln)
          (let [[_ k] (re-matches #"^([\w_-]+):\s*>\s*$" ln)]
            (recur rest* acc [:folded-key (keyword k)]))

          ;; Standard flat key: value
          (re-matches #"^([\w_-]+):\s*(.*)$" ln)
          (let [[_ k v] (re-matches #"^([\w_-]+):\s*(.*)$" ln)
                v (str/trim v)
                parsed (cond
                         (re-matches #"^\[.*\]$" v)
                         (parse-flow-vector v)

                         (and (str/starts-with? v "\"") (str/ends-with? v "\""))
                         (-> v
                             (subs 1 (dec (count v)))
                             (str/replace "\\\"" "\"")
                             (str/replace "\\\\" "\\"))

                         :else v)]
            (recur rest* (assoc acc (keyword k) parsed) nil))

          ;; Unrecognized line — skip, don't crash
          :else
          (recur rest* acc block))))))

(defcommand explore$read-frontmatter
  "Read and parse just the leading YAML frontmatter from a result file (cheap; skips the body)."
  (fn [& {:keys [path base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? path))
      {:error ":path is required (string)"}

      :else
      (let [;; Support absolute paths transparently — io/file blows up when
            ;; given (base-dir, /absolute) on JDK 11+.
            file (if (.isAbsolute (io/file path))
                   (io/file path)
                   (io/file base-dir path))]
        (cond
          (not (.isFile file))
          {:error (str "File not found: " path)}

          :else
          (if-let [lines (read-frontmatter-lines file)]
            (parse-flat-yaml lines)
            {:error (str "No frontmatter block at " path " (file did not start with ---)")})))))
  :input-schema  [:map
                  [:path [:string {:desc "Repo-relative path to a result file"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: System/user.dir)"}]]]
  :output-schema [:map
                  [:slug {:optional true} [:string {:desc "Slug from frontmatter"}]]
                  [:question {:optional true} [:string {:desc "Question from frontmatter"}]]
                  [:surfaces {:optional true} [:vector {:desc "Surfaces touched"} :string]]
                  [:entities {:optional true} :map]
                  [:summary {:optional true} [:string {:desc "One-line summary"}]]
                  [:created {:optional true} [:string {:desc "ISO-8601 timestamp"}]]
                  [:error {:optional true} [:string {:desc "Error if file missing or no frontmatter"}]]])

;; ============================================================================
;; explore$find — keyword search across the results corpus
;; ============================================================================

(defn- parse-index-line
  "Best-effort parse of one INDEX.md line into a match map, or nil. Line
   format (see `index-line`):
     - <YYYY-MM-DD HH:MM> [<slug>](results/<file>) — <surfaces> · *<summary>*
   Path comes from the markdown link (always well-formed); the rest is
   extracted leniently so a summary containing `·`/`*` doesn't break parsing."
  [^String line]
  (when-let [rel (second (re-find #"\]\((results/[^)]+)\)" line))]
    (let [slug (some-> (re-find #"\[([^\]]*)\]\(results/" line) second str/trim)
          date (some-> (re-find #"^-\s+(\d{4}-\d{2}-\d{2} \d{2}:\d{2})" line) second)
          tail (some-> (re-find #"—\s*(.*)$" line) second)
          dot  (when tail (str/index-of tail " · "))
          surfaces-str (when tail (if dot (subs tail 0 dot) tail))
          summary      (when (and tail dot) (subs tail (+ dot 3)))]
      {:path     (str results-base "/" rel)
       :slug     slug
       :summary  (-> (or summary "") (str/replace #"^\*" "") (str/replace #"\*$" "") str/trim)
       :surfaces (when surfaces-str
                   (->> (str/split surfaces-str #",") (map str/trim) (remove str/blank?) vec))
       :created  date})))

(defcommand explore$find
  "Search prior explore-agent results by substring (case-insensitive) over
   slug+summary+question; newest-first. INDEX-first: matches against the cheap
   single-file INDEX.md (covers slug+summary+surfaces). Falls back to a full
   per-file frontmatter scan when INDEX.md is absent or yields no match — the
   scan also catches matches on `question`, which INDEX.md does not carry."
  (fn [& {:keys [query base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? query))
      {:error ":query is required (string)"}

      :else
      (let [q          (str/lower-case query)
            index-file (io/file base-dir index-rel)
            index-matches
            (when (.isFile index-file)
              (->> (str/split-lines (slurp index-file))
                   (filter #(str/includes? (str/lower-case %) q))
                   (keep parse-index-line)
                   vec))]
        (if (seq index-matches)
          (do (mulog/log ::explore.find
                         :query query :n-matches (count index-matches) :source :index)
              {:matches index-matches :n-matches (count index-matches)})
          ;; Fallback — INDEX.md missing / empty / no hit. Full per-file scan
          ;; (also matches on `question`, absent from INDEX).
          (let [^java.io.File dir (io/file base-dir results-dir-rel)]
            (if-not (.isDirectory dir)
              {:matches [] :n-matches 0}
              (let [matches
                    (->> (.listFiles dir)
                         (filter (fn [^java.io.File f]
                                   (and (.isFile f) (str/ends-with? (.getName f) ".md"))))
                         (keep (fn [^java.io.File f]
                                 (when-let [lines (read-frontmatter-lines f)]
                                   (let [fm (parse-flat-yaml lines)
                                         hay (str/lower-case
                                              (str (:slug fm) " "
                                                   (:summary fm) " "
                                                   (:question fm)))]
                                     (when (str/includes? hay q)
                                       {:path     (str results-dir-rel "/" (.getName f))
                                        :slug     (:slug fm)
                                        :summary  (:summary fm)
                                        :surfaces (:surfaces fm)
                                        :created  (:created fm)})))))
                         ;; Newest-first by filename (timestamp prefix sorts naturally)
                         (sort-by :path)
                         reverse
                         vec)]
                (mulog/log ::explore.find
                           :query query :n-matches (count matches) :source :scan)
                {:matches matches :n-matches (count matches)})))))))
  :input-schema  [:map
                  [:query [:string {:desc "Substring to match against slug + summary + question (case-insensitive)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: System/user.dir)"}]]]
  :output-schema [:map
                  [:matches [:vector {:desc "Matching result files, newest-first"} :map]]
                  [:n-matches [:int {:desc "Number of matches"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; Public roster (for explore-agent's :agent-tools)
;; ============================================================================

(def explore-helpers
  "Vector of all explore$* helper vars in registration order. explore-agent
   appends these to its :agent-tools roster so the SCI sandbox auto-binds
   them (callable as `(explore$slug ...)` in a clojure fence)."
  [#'explore$slug
   #'explore$frontmatter
   #'explore$write
   #'explore$index-append
   #'explore$read-frontmatter
   #'explore$find])

;; ============================================================================
;; Auto-persist hook
;;
;; The agent instruction has a FINAL-STEP CHECKLIST telling the LLM to call
;; explore$write + explore$index-append before answering. Sonnet+ follows it
;; reliably; haiku and other smaller models often skip it. This `:agent.ask/post`
;; hook is the safety net: when explore-agent emits a non-trivial answer that
;; the LLM didn't persist itself, the hook persists it after-the-fact.
;;
;; Caveat: `:agent.ask/post` is observe-only (return values ignored, see
;; hooks.clj:64). The hook can write the artifact and append INDEX.md, but it
;; CANNOT inject a `Saved exploration: <path>` line into the answer the
;; caller already received. Downstream agents that need to discover
;; auto-persisted artifacts should use `(explore$find :query "<keyword>")`
;; rather than greppning the prior answer text.
;; ============================================================================

(def ^:private trivial-char-threshold
  "Non-trivial threshold (matches design doc §5.4). Inline answer >= this
   many chars triggers persistence. Tunable per turn via
   agent-runtime$config :key \"explore-persist-threshold\" :value \"N\"."
  1000)

(def ^:private saved-exploration-prefix
  "If the answer already contains this exact prefix, the LLM honored the
   FINAL-STEP CHECKLIST itself and the hook is a no-op."
  "Saved exploration: ")

(defn- explore-agent? [agent]
  (try
    (= :explore-agent (proto/defagent-type agent))
    (catch Throwable _ false)))

(defn- already-saved? [^String answer]
  (boolean (and (string? answer)
                (str/includes? answer saved-exploration-prefix))))

(defn- detect-entities
  "Best-effort regex scan for entity markers in the answer text. Used only as
   a fallback when the LLM didn't supply structured entities itself; misses
   are acceptable (the worst case is a slightly thinner frontmatter).

   Matches the citation conventions named in the agent instruction:
     - file:<repo-relative-path>:<line>  OR  bare repo-relative paths inside
       backticks (e.g. `components/.../foo.clj`)
     - http(s) URLs
     - mcp:<server>:<tool>
     - skill:<backend>:<skill-name>"
  [^String answer]
  (let [files
        (->> (concat
              ;; `path/to/file.ext` (with or without surrounding backticks)
              (re-seq #"`(?:components|bases|projects|docs|src|test|resources)/[^`\s]+`" answer)
              ;; file:<path>:<line>
              (re-seq #"file:[^\s)\]\"`]+" answer))
             (map #(-> %
                       (str/replace #"`" "")
                       (str/replace #"^file:" "")
                       (str/replace #":\d+(?:-\d+)?$" "")))
             distinct vec)

        urls (->> (re-seq #"https?://[^\s)\]\"`*]+" answer) distinct vec)
        mcp  (->> (re-seq #"mcp:[\w.-]+:[\w$.-]+" answer) distinct vec)
        skills (->> (re-seq #"skill:[\w.-]+:[\w.-]+" answer) distinct vec)]
    {:files files :urls urls :mcp_tools mcp :skills skills}))

(defn- detect-surfaces [{:keys [files urls mcp_tools skills]}]
  (cond-> []
    (seq files)     (conj "filesystem")
    (seq urls)      (conj "web")
    (seq mcp_tools) (conj "mcp")
    (seq skills)    (conj "skills")))

(defn- non-trivial-answer? [^String answer entities]
  (or (>= (count answer) trivial-char-threshold)
      (some seq (vals entities))))

(defn- one-line-summary
  "Distill a one-line summary from the answer body. Heuristic: drop any
   leading frontmatter the LLM may have inlined, then take the first
   paragraph that has actual prose (not a markdown heading or table row),
   collapse whitespace, cap at `max-chars`. Falls back to the first
   non-blank paragraph if no prose is found."
  [^String answer max-chars]
  (let [stripped (-> answer
                     (str/replace #"^---\n[\s\S]*?\n---\n" "")
                     str/trim)
        paragraphs (->> (str/split stripped #"\n\n")
                        (map str/trim)
                        (remove str/blank?))
        prose? (fn [p]
                 (and (not (str/starts-with? p "#"))      ; markdown heading
                      (not (str/starts-with? p "|"))      ; markdown table row
                      (not (str/starts-with? p "```"))    ; code-fence boundary
                      (not (re-matches #"^[-*_]{3,}$" p)))) ; horizontal rule
        chosen (or (first (filter prose? paragraphs))
                   (first paragraphs)
                   "")
        flat   (-> chosen
                   (str/replace #"\s+" " ")
                   (str/replace #"^#+\s*" "")
                   str/trim)]
    (subs flat 0 (min max-chars (count flat)))))

(defn- persist-config
  "Per-turn override of the trivial threshold via config."
  [agent]
  (try
    {:threshold (config/get-config agent :explore-persist-threshold)
     :enabled?  (boolean (config/get-config agent :explore-auto-persist))}
    (catch Throwable _
      {:threshold trivial-char-threshold :enabled? true})))

(defn explore-auto-persist
  "Persist the answer when explore-agent forgets to call explore$write itself.
   Idempotent — skips when the LLM-emitted answer already contains a
   `Saved exploration:` line (= LLM honored the FINAL-STEP CHECKLIST).

   Defensive: any failure inside the hook is logged but never re-thrown — the
   user-facing answer must not be affected by hook errors."
  [{:keys [agent input result]}]
  (try
    (when (and (explore-agent? agent) (map? result))
      (let [answer (:answer result)
            {:keys [threshold enabled?]} (persist-config agent)]
        (when (and enabled?
                   (string? answer)
                   (not (already-saved? answer)))
          (let [entities (detect-entities answer)]
            (when (or (>= (count answer) threshold)
                      (some seq (vals entities)))
              (let [question (or (when (string? input) input)
                                 (some-> input :question str)
                                 "(question not captured)")
                    surfaces (let [s (detect-surfaces entities)]
                               (if (empty? s) ["filesystem"] s))
                    slug     (:slug (explore$slug :question question))
                    summary  (one-line-summary answer 200)
                    fm       (:frontmatter
                              (explore$frontmatter
                               :question question
                               :slug     slug
                               :surfaces surfaces
                               :entities entities
                               :summary  (if (str/blank? summary)
                                           "(auto-persisted; no summary extracted)"
                                           summary)))
                    write-res (explore$write :slug slug
                                             :content (str fm "\n" answer))]
                (when-not (:error write-res)
                  (explore$index-append :path     (:path write-res)
                                        :slug     (:slug write-res)
                                        :surfaces surfaces
                                        :summary  summary)
                  (mulog/log ::explore.auto-persist
                             :slug          (:slug write-res)
                             :path          (:path write-res)
                             :answer-chars  (count answer)
                             :surfaces      surfaces
                             :files-count   (count (:files entities))
                             :urls-count    (count (:urls entities))
                             :mcp-count     (count (:mcp_tools entities))
                             :skills-count  (count (:skills entities))))))))))
    (catch Throwable t
      (mulog/error ::explore.auto-persist-failed
                   :exception t
                   :agent-id (try (proto/agent-id agent)
                                  (catch Throwable _ "unknown"))))))

(defn install-auto-persist!
  "Register the auto-persist hook globally. Idempotent — safe to call multiple
   times. The `:match` predicate scopes the hook to explore-agent instances
   only, so other agents (rlm, search, plan, etc.) are unaffected.

   Tag `:source :explore-agent` lets apps opt out via
   `(hooks/unregister-source! :explore-agent)`."
  []
  (hooks/register-hook!
   :agent.ask/post
   ::explore-auto-persist
   explore-auto-persist
   :source   :explore-agent
   :match    (fn [{:keys [agent]}] (explore-agent? agent))
   :priority 50))

;; Self-install at namespace load so anyone requiring explore-agent (which
;; transitively requires this ns) gets the safety net for free. Idempotent —
;; register-hook! replaces by id.
(install-auto-persist!)
