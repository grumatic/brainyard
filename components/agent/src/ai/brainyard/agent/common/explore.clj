;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.explore
  "Explore-agent persistence — READ & DISCOVERY seams plus an auto-persist
   safety net. The write-side helper chain (explore$slug / explore$frontmatter /
   explore$write / explore$index-append) is RETIRED: per
   docs/design/explore-agent-lightweight-redesign.md the dossier is just a
   markdown file, so explore-agent authors it directly with `write-file` from a
   fixed template instead of constructing it through precisely-keyed helpers.

   What survives are the two deterministic readers — exactly where a machine
   beats the model:
     - `explore$find`           — corpus search; the mandatory iteration-0
                                  prior-art gate that turns the corpus into a
                                  reuse cache (don't re-explore what's on disk).
     - `explore$read-frontmatter` — cheap metadata read of one dossier; used to
                                  judge freshness, resolve `related:` lineage
                                  links, and route downstream agents.
     - `explore$reuse?`         — applies the freshness rule (static: cited
                                  files unchanged; volatile: age < window) so the
                                  reuse decision lives in one tested place.

   The `:agent.ask/finalize` auto-persist hook backstops a skipped dossier:
   when a smaller model emits a non-trivial answer without persisting, the hook
   fills the §5 template from regex-detected entities and `spit`s ONE file —
   the same one-file path the happy path uses, so the two can't diverge — then
   injects the absent `Saved exploration: <path>` handoff line into the answer.

   Frontmatter is hand-rolled (no clj-yaml dep): a flat key/value head, a nested
   `entities` sub-map, and the lineage/freshness scalars `related` / `freshness`.
   The lenient `parse-flat-yaml` reads it back and tolerates a keyword typo."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.text SimpleDateFormat)
           (java.time Instant)
           (java.util Date)))

;; ============================================================================
;; Constants & utilities
;; ============================================================================

(def ^:private results-subdir "results")
(def ^:private results-base ".brainyard/agents/explore-agent")
(def ^:private results-dir-rel (str results-base "/" results-subdir))
(def ^:private index-rel (str results-base "/INDEX.md"))

(def ^:private default-volatile-hours
  "Fallback reuse window for `volatile` dossiers when the
   :explore-reuse-volatile-hours config key resolves to nothing."
  24)

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
  (str (Instant/now)))

(defn- now-yyyy-mm-dd-hh-mm
  "Returns 'YYYY-MM-DD HH:MM' for INDEX.md line prefix (UTC).
   ISO format is 'YYYY-MM-DDTHH:MM…'; we replace the 'T' with a space to
   match the design doc spec."
  []
  (-> (subs (now-iso) 0 16)
      (str/replace "T" " ")))

(defn- slugify
  "Deterministic kebab-case slug: lower-case, drop stopwords + non-alnum,
   cap at `max-chars`. Used by the auto-persist hook to derive a filename
   when the LLM didn't author the dossier itself. (The LLM-facing
   explore$slug command is retired — the model derives its own slug inline.)"
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

;; ============================================================================
;; Frontmatter emission (private — backs the template-fill auto-persist hook;
;; no LLM-facing command, the happy path authors markdown directly)
;; ============================================================================

(defn- yaml-string
  "Double-quote + escape a string value (YAML 1.2 double-quoted scalar rules)."
  [s]
  (str "\"" (-> (str s)
                (str/replace "\\" "\\\\")
                (str/replace "\"" "\\\""))
       "\""))

(def ^:private bareword-re #"^[A-Za-z0-9_./:-]+$")

(defn- yaml-flow-vector
  "Format a coll as a YAML flow-style vector `[a, b, c]`. Barewords
   (alnum + `_./:-`) stay unquoted; anything else is double-quoted."
  [xs]
  (str "[" (str/join ", " (map (fn [x]
                                 (cond
                                   (keyword? x) (name x)
                                   (string? x)  (if (re-matches bareword-re x)
                                                  x
                                                  (yaml-string x))
                                   :else        (str x)))
                               xs))
       "]"))

(defn- collapse-1-line
  "Collapse internal whitespace/newlines to a single line."
  [s]
  (-> (str s) (str/replace #"\s+" " ") str/trim))

(defn- render-dossier
  "Fill the §5 RESULT TEMPLATE: frontmatter (incl. related + freshness) plus a
   4-section body. Returns the full markdown string ready to `spit`. Used by
   the auto-persist hook; the happy-path LLM writes the equivalent itself."
  [{:keys [slug question created surfaces entities related freshness summary
           title what-found where builds-on caveats]}]
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
         "related: " (yaml-flow-vector (or related [])) "\n"
         "freshness: " (name (or freshness :static)) "\n"
         "summary: >\n  " (collapse-1-line summary) "\n"
         "---\n\n"
         "# " (or title slug) "\n\n"
         "## What was found\n" (or what-found "") "\n\n"
         "## Where\n" (or where "") "\n\n"
         "## Builds on\n" (or builds-on "None — first exploration of this area.") "\n\n"
         "## Caveats / freshness\n" (or caveats "") "\n")))

;; ============================================================================
;; Frontmatter parsing (READ seam — kept; sidesteps the write-side brittleness)
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

(defn- unquote-yaml-token
  "Strip surrounding double quotes (and unescape) from a YAML scalar token."
  [t]
  (let [t (str/trim t)]
    (if (and (>= (count t) 2) (str/starts-with? t "\"") (str/ends-with? t "\""))
      (-> t (subs 1 (dec (count t))) (str/replace "\\\"" "\"") (str/replace "\\\\" "\\"))
      t)))

(defn- parse-flat-yaml
  "Parse the frontmatter inner lines into a map. Recognizes flat scalars,
   quoted strings, folded scalars (`key: >` + indented line — covers `summary`),
   the nested `entities:` block, and list-valued keys (`surfaces` / `related` /
   `entities.*`) in EITHER flow style (`[a, b]`) OR YAML block-list style
   (`key:` then indented `- item` lines) — capable models emit block lists even
   when the template shows flow vectors. Lenient: an unknown line is skipped,
   never fatal."
  [lines]
  (loop [ls        lines
         acc       {}
         block     nil            ; nil | :entities | [:folded-key k]
         list-path nil]           ; key path armed for `- item` continuation
    (if (empty? ls)
      acc
      (let [ln    (first ls)
            rest* (rest ls)]
        (cond
          ;; Block-list item ("  - value") → append to the armed key path.
          (and list-path (re-matches #"^\s+-\s+\S.*$" ln))
          (let [[_ raw] (re-matches #"^\s+-\s+(.*)$" ln)]
            (recur rest* (update-in acc list-path (fnil conj []) (unquote-yaml-token raw))
                   block list-path))

          ;; Indented entities sub-key
          (and (= block :entities) (re-matches #"^\s{2,}[\w_-]+:.*$" ln))
          (let [[_ k v] (re-matches #"^\s+([\w_-]+):\s*(.*)$" ln)
                kp [:entities (keyword k)]
                tv (str/trim v)]
            (cond
              (re-matches #"^\[.*\]$" tv) (recur rest* (assoc-in acc kp (parse-flow-vector tv)) :entities nil)
              (str/blank? tv)            (recur rest* (assoc-in acc kp []) :entities kp)   ; arm block list
              :else                      (recur rest* (assoc-in acc kp (unquote-yaml-token tv)) :entities nil)))

          ;; Folded-scalar continuation (single line indented under `>`)
          (and (vector? block) (= :folded-key (first block)))
          (recur rest* (assoc acc (second block) (str/trim ln)) nil nil)

          ;; entities: header → start nested-map mode
          (re-matches #"^entities:\s*$" ln)
          (recur rest* (assoc acc :entities {}) :entities nil)

          ;; key: > (folded scalar — next non-blank line is the value)
          (re-matches #"^([\w_-]+):\s*>\s*$" ln)
          (let [[_ k] (re-matches #"^([\w_-]+):\s*>\s*$" ln)]
            (recur rest* acc [:folded-key (keyword k)] nil))

          ;; Standard flat key: value
          (re-matches #"^([\w_-]+):\s*(.*)$" ln)
          (let [[_ k v] (re-matches #"^([\w_-]+):\s*(.*)$" ln)
                kw (keyword k)
                tv (str/trim v)]
            (cond
              (re-matches #"^\[.*\]$" tv) (recur rest* (assoc acc kw (parse-flow-vector tv)) nil nil)
              (str/blank? tv)            (recur rest* (assoc acc kw []) nil [kw])   ; arm block list
              :else                      (recur rest* (assoc acc kw (unquote-yaml-token tv)) nil nil)))

          ;; Unrecognized line — skip, don't crash
          :else
          (recur rest* acc block list-path))))))

(defn- read-dossier-frontmatter
  "Internal: resolve `path` (absolute or base-dir-relative) and return the
   parsed frontmatter map, or {:error …}. Shared by explore$read-frontmatter
   and explore$reuse?."
  [path base-dir]
  (let [file (if (.isAbsolute (io/file path))
               (io/file path)
               (io/file base-dir path))]
    (cond
      (not (.isFile file))
      {:error (str "File not found: " path)}

      :else
      (if-let [lines (read-frontmatter-lines file)]
        (assoc (parse-flat-yaml lines) ::file file)
        {:error (str "No frontmatter block at " path " (file did not start with ---)")}))))

(defcommand explore$read-frontmatter
  "Read and parse just the leading YAML frontmatter from a result file (cheap; skips the body)."
  (fn [& {:keys [path base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? path))
      {:error ":path is required (string)"}

      :else
      (let [m (read-dossier-frontmatter path base-dir)]
        (if (:error m) m (dissoc m ::file)))))
  :input-schema  [:map
                  [:path [:string {:desc "Repo-relative or absolute path to a result file"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:slug {:optional true} [:string {:desc "Slug from frontmatter"}]]
                  [:question {:optional true} [:string {:desc "Question from frontmatter"}]]
                  [:surfaces {:optional true} [:vector {:desc "Surfaces touched"} :string]]
                  [:entities {:optional true} :map]
                  [:related {:optional true} [:vector {:desc "Prior dossier paths this one builds on (lineage)"} :string]]
                  [:freshness {:optional true} [:string {:desc "static (filesystem/code) | volatile (web/MCP/time-sensitive)"}]]
                  [:summary {:optional true} [:string {:desc "One-line summary"}]]
                  [:created {:optional true} [:string {:desc "ISO-8601 timestamp"}]]
                  [:error {:optional true} [:string {:desc "Error if file missing or no frontmatter"}]]])

;; ============================================================================
;; explore$find — keyword search across the results corpus (the reuse gate)
;; ============================================================================

(defn- parse-index-line
  "Best-effort parse of one INDEX.md line into a match map, or nil. Line
   format (see the auto-persist INDEX writer):
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
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:matches [:vector {:desc "Matching result files, newest-first"} :map]]
                  [:n-matches [:int {:desc "Number of matches"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; explore$reuse? — freshness rule in one tested place (§7.4)
;; ============================================================================

(defn- parse-instant
  "Parse an ISO-8601 string to a Java Instant, or nil on failure."
  [s]
  (when (string? s)
    (try (Instant/parse s) (catch Exception _ nil))))

(defn- changed-files
  "Return cited repo-relative `files` whose on-disk mtime is newer than
   `created-ms`, or that no longer exist. Either condition invalidates a
   `static` dossier (the code it described has moved on)."
  [base-dir files created-ms]
  (->> files
       (filter (fn [rel]
                 (let [f (if (.isAbsolute (io/file rel))
                           (io/file rel)
                           (io/file base-dir rel))]
                   (or (not (.isFile f))
                       (and created-ms (> (.lastModified f) created-ms))))))
       vec))

(defcommand explore$reuse?
  "Judge whether a prior dossier is fresh enough to reuse, applying the
   freshness rule: a `static` (filesystem/code) dossier is reusable while its
   cited files are unchanged; a `volatile` (web/MCP) dossier is reusable only
   while younger than the reuse window (:explore-reuse-volatile-hours, default
   24h). Read-only — call it at iteration 0 on an explore$find hit before
   re-probing."
  (fn [& {:keys [path base-dir volatile-hours]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? path))
      {:error ":path is required (string)"}

      :else
      (let [fm (read-dossier-frontmatter path base-dir)]
        (if (:error fm)
          fm
          (let [created     (:created fm)
                created-inst (parse-instant created)
                created-ms  (some-> created-inst .toEpochMilli)
                now-ms      (.toEpochMilli (Instant/now))
                age-hours   (when created-ms
                              (double (/ (- now-ms created-ms) 3600000.0)))
                freshness   (keyword (or (:freshness fm) "static"))
                window      (or volatile-hours
                                (config/get-config :explore-reuse-volatile-hours)
                                default-volatile-hours)]
            (case freshness
              :volatile
              (let [reuse? (boolean (and age-hours (< age-hours window)))]
                {:reuse?    reuse?
                 :freshness "volatile"
                 :age-hours age-hours
                 :reason    (cond
                              (nil? age-hours)
                              "volatile dossier has no parseable created timestamp — re-probe to be safe"
                              reuse?
                              (format "volatile dossier is %.1fh old (< %dh window) — reuse" age-hours window)
                              :else
                              (format "volatile dossier is %.1fh old (>= %dh window) — stale, re-probe" age-hours window))})

              ;; :static (and anything unrecognized → treated as static)
              (let [files   (vec (get-in fm [:entities :files] []))
                    changed (changed-files base-dir files created-ms)
                    reuse?  (empty? changed)]
                {:reuse?    reuse?
                 :freshness "static"
                 :age-hours age-hours
                 :changed   changed
                 :reason    (if reuse?
                              (format "static dossier: all %d cited file(s) unchanged since capture — reuse"
                                      (count files))
                              (format "static dossier: %d cited file(s) changed/missing since capture — stale, re-probe the gap"
                                      (count changed)))})))))))
  :input-schema  [:map
                  [:path [:string {:desc "Repo-relative or absolute path to a prior dossier (from explore$find)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]
                  [:volatile-hours {:optional true} [:int {:desc "Override the volatile reuse window in hours (default: :explore-reuse-volatile-hours / 24)"}]]]
  :output-schema [:map
                  [:reuse? {:optional true} [:boolean {:desc "true ⇒ safe to reuse without re-probing"}]]
                  [:freshness {:optional true} [:string {:desc "static | volatile"}]]
                  [:age-hours {:optional true} [:double {:desc "Dossier age in hours (nil if created unparseable)"}]]
                  [:changed {:optional true} [:vector {:desc "Cited files changed/missing since capture (static only)"} :string]]
                  [:reason {:optional true} [:string {:desc "Human-readable reuse decision"}]]
                  [:error {:optional true} [:string {:desc "Error if file missing or no frontmatter"}]]])

;; ============================================================================
;; Public roster (for explore-agent's :agent-tools)
;; ============================================================================

(def explore-helpers
  "Vector of the surviving explore$* READER vars in registration order.
   explore-agent appends these to its :agent-tools roster so the SCI sandbox
   auto-binds them (callable as `(explore$find ...)` in a clojure fence). The
   write-side helpers are retired — authoring is a direct `write-file`."
  [#'explore$find
   #'explore$read-frontmatter
   #'explore$reuse?])

;; ============================================================================
;; Auto-persist hook (gated :agent.ask/finalize)
;;
;; The agent instruction tells the LLM to write the dossier + INDEX line before
;; answering. Sonnet+ follows it; haiku and smaller models often skip it. This
;; hook is the safety net: when explore-agent emits a non-trivial answer it
;; didn't persist, the hook fills the §5 template from regex-detected entities,
;; `spit`s ONE file (the same path the happy path writes), appends the INDEX
;; line, and — because :agent.ask/finalize is gated — injects the absent
;; `Saved exploration: <path>` handoff line into the answer.
;; ============================================================================

(def ^:private trivial-char-threshold
  "Non-trivial threshold. Inline answer >= this many chars triggers
   persistence. Tunable per turn via :explore-persist-threshold."
  1000)

(def saved-exploration-prefix
  "Stable prefix the agent emits when a dossier was persisted. Public so tests
   + downstream consumers can grep for it without re-defining the constant."
  "Saved exploration: ")

(defn- explore-agent? [agent]
  (try
    (= :explore-agent (proto/defagent-type agent))
    (catch Throwable _ false)))

(defn- already-saved? [^String answer]
  ;; Match the trimmed marker core so markdown emphasis around it
  ;; (`**Saved exploration:** …`) still counts as saved.
  (boolean (and (string? answer)
                (str/includes? answer (str/trimr saved-exploration-prefix)))))

(defn- detect-entities
  "Best-effort regex scan for entity markers in the answer text. Fallback only
   (used when the LLM didn't author the dossier); misses are acceptable.
   Matches the citation conventions named in the agent instruction:
   `path/to/file.ext` in backticks, file:<path>:<line>, http(s) URLs,
   mcp:<server>:<tool>, skill:<backend>:<skill-name>."
  [^String answer]
  (let [files
        (->> (concat
              (re-seq #"`(?:components|bases|projects|docs|src|test|resources)/[^`\s]+`" answer)
              (re-seq #"file:[^\s)\]\"`]+" answer))
             (map #(-> %
                       (str/replace #"`" "")
                       (str/replace #"^file:" "")
                       (str/replace #":\d+(?:-\d+)?$" "")))
             distinct vec)
        urls   (->> (re-seq #"https?://[^\s)\]\"`*]+" answer) distinct vec)
        mcp    (->> (re-seq #"mcp:[\w.-]+:[\w$.-]+" answer) distinct vec)
        skills (->> (re-seq #"skill:[\w.-]+:[\w.-]+" answer) distinct vec)]
    {:files files :urls urls :mcp_tools mcp :skills skills}))

(defn- detect-surfaces [{:keys [files urls mcp_tools skills]}]
  (cond-> []
    (seq files)     (conj "filesystem")
    (seq urls)      (conj "web")
    (seq mcp_tools) (conj "mcp")
    (seq skills)    (conj "skills")))

(defn- infer-freshness
  "volatile when the findings depend on web/MCP (time-sensitive); else static
   (filesystem/code, valid while cited files are unchanged)."
  [{:keys [urls mcp_tools]}]
  (if (or (seq urls) (seq mcp_tools)) :volatile :static))

(defn- one-line-summary
  "Distill a one-line summary from the answer body: drop any inlined
   frontmatter, take the first prose paragraph (not a heading/table/fence/rule),
   collapse whitespace, cap at `max-chars`."
  [^String answer max-chars]
  (let [stripped (-> answer
                     (str/replace #"^---\n[\s\S]*?\n---\n" "")
                     str/trim)
        paragraphs (->> (str/split stripped #"\n\n")
                        (map str/trim)
                        (remove str/blank?))
        prose? (fn [p]
                 (and (not (str/starts-with? p "#"))
                      (not (str/starts-with? p "|"))
                      (not (str/starts-with? p "```"))
                      (not (str/starts-with? p "Saved exploration:"))
                      (not (re-matches #"^[-*_]{3,}$" p))))
        chosen (or (first (filter prose? paragraphs))
                   (first paragraphs)
                   "")
        flat   (-> chosen
                   (str/replace #"\s+" " ")
                   (str/replace #"^#+\s*" "")
                   str/trim)]
    (subs flat 0 (min max-chars (count flat)))))

(defn- index-line
  "Build one newest-first INDEX.md line for a freshly written dossier."
  [{:keys [rel-path slug surfaces summary]}]
  (let [filename (-> rel-path (str/split #"/") last)
        surfaces-str (str/join ", " (map name surfaces))
        summary-trim (collapse-1-line summary)
        summary-cap  (if (> (count summary-trim) 200)
                       (str (subs summary-trim 0 197) "…")
                       summary-trim)]
    (str "- " (now-yyyy-mm-dd-hh-mm)
         " [" slug "](" results-subdir "/" filename ") — "
         surfaces-str " · *" summary-cap "*\n")))

(defn- persist-config
  "Per-turn override of the trivial threshold + enable flag via config."
  [agent]
  (try
    {:threshold (config/get-config agent :explore-persist-threshold)
     :enabled?  (boolean (config/get-config agent :explore-auto-persist))}
    (catch Throwable _
      {:threshold trivial-char-threshold :enabled? true})))

(defn materialize-auto-dossier!
  "Core of the auto-persist safety net (no agent-state required): given an
   answer + question + base-dir, reconstruct a minimal §5-template dossier from
   regex-detected entities, `spit` it under results/, and append the INDEX
   line. Returns {:path :rel-path :slug} on success, or nil when skipped
   (already saved, trivial, or disabled). Extracted as a public fn so tests can
   drive it directly. `:threshold`/`:enabled?` default to the module constants."
  [{:keys [answer question base-dir threshold enabled?]
    :or   {threshold trivial-char-threshold enabled? true}}]
  (when (and enabled?
             (string? answer)
             (not (already-saved? answer)))
    (let [entities (detect-entities answer)]
      (when (or (>= (count answer) threshold)
                (some seq (vals entities)))
        (let [q        (or (when (string? question) question) "(question not captured)")
              surfaces (let [s (detect-surfaces entities)]
                         (if (empty? s) ["filesystem"] s))
              slug     (slugify q 60)
              summary  (let [s (one-line-summary answer 200)]
                         (if (str/blank? s) "(auto-persisted; no summary extracted)" s))
              ts       (now-ts)
              rel-path (str results-dir-rel "/" ts "-" slug ".md")
              file     (io/file base-dir rel-path)
              content  (render-dossier
                        {:slug      slug
                         :question  q
                         :surfaces  surfaces
                         :entities  entities
                         :related   []
                         :freshness (infer-freshness entities)
                         :summary   summary
                         :title     slug
                         :what-found (str "*Reconstructed from the agent's answer text — the LLM did "
                                          "not author the dossier itself this turn.*\n\n" answer)
                         :where     (str/join "\n"
                                              (concat (map #(str "- file:" %) (:files entities))
                                                      (map #(str "- " %) (:urls entities))
                                                      (map #(str "- mcp:" %) (:mcp_tools entities))
                                                      (map #(str "- skill:" %) (:skills entities))))
                         :builds-on "None — first exploration of this area."
                         :caveats   (if (= :volatile (infer-freshness entities))
                                      (str "captured " (now-iso) "; volatile (web/MCP) — re-check if stale.")
                                      "static (filesystem) — valid while the cited files are unchanged.")})]
          (.mkdirs (.getParentFile file))
          (spit file content)
          (let [idx (io/file base-dir index-rel)]
            (.mkdirs (.getParentFile idx))
            (spit idx (str (index-line {:rel-path rel-path :slug slug
                                        :surfaces surfaces :summary summary})
                           (if (.isFile idx) (slurp idx) ""))))
          (mulog/log ::explore.auto-persist
                     :slug slug :path rel-path :answer-chars (count answer)
                     :surfaces surfaces
                     :files-count (count (:files entities))
                     :urls-count (count (:urls entities))
                     :mcp-count (count (:mcp_tools entities))
                     :skills-count (count (:skills entities)))
          {:path (.getAbsolutePath file) :rel-path rel-path :slug slug})))))

(defn explore-auto-persist
  "Gated handler for `:agent.ask/finalize`. Persists the answer when
   explore-agent skipped the dossier, then returns a `:replace` decision
   injecting the absent `Saved exploration: <path>` handoff line into the
   answer. Idempotent — no-op when the line is present or nothing was
   persisted. Defensive — any failure is logged but never re-thrown (the
   user-facing answer must not be affected by hook errors)."
  [{:keys [agent input result]}]
  (try
    (when (and (explore-agent? agent) (map? result))
      (let [answer (:answer result)]
        (when (string? answer)
          (let [question (or (when (string? input) input)
                             (some-> input :question str)
                             "(question not captured)")
                {:keys [threshold enabled?]} (persist-config agent)
                persisted (materialize-auto-dossier!
                           {:answer answer :question question
                            :base-dir (config/project-dir)
                            :threshold threshold :enabled? enabled?})
                rel-path  (:rel-path persisted)]
            (when (and rel-path (not (already-saved? answer)))
              {:result      :replace
               :reason      "injected absent Saved-exploration handoff line"
               :replacement (assoc result :answer
                                   (str answer "\n\n" saved-exploration-prefix rel-path))})))))
    (catch Throwable t
      (mulog/error ::explore.auto-persist-failed
                   :exception t
                   :agent-id (try (proto/agent-id agent)
                                  (catch Throwable _ "unknown")))
      nil)))

(defn install-auto-persist!
  "Register the auto-persist hook on the gated `:agent.ask/finalize` event.
   Idempotent — `register-hook!` replaces by id. The :match predicate scopes
   the hook to explore-agent instances only, so other agents are unaffected.

   Tag `:source :explore-agent` lets apps opt out via
   `(hooks/unregister-source! :explore-agent)`."
  []
  (hooks/register-hook!
   :agent.ask/finalize
   ::explore-auto-persist
   explore-auto-persist
   :source   :explore-agent
   :match    (fn [{:keys [agent]}] (explore-agent? agent))
   :priority 50))

;; Self-install at namespace load so anyone requiring explore-agent (which
;; transitively requires this ns) gets the safety net for free. Idempotent —
;; register-hook! replaces by id.
(install-auto-persist!)
