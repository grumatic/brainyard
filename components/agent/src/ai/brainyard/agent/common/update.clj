;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.update
  "Update-agent quality-of-life helpers — slug / frontmatter / write / index /
   read-record / find, plus the `update$apply` mega-helper that runs the full
   safe-edit pipeline (PROBE → APPLY → VERIFY → PERSIST → ROLLBACK-on-fail) as
   one call.

   Each helper is a `defcommand` so it surfaces in the unified tool registry
   and is auto-bound into the SCI sandbox (callable as `(update$slug ...)` in
   a clojure fence). Mirrors the explore-agent helper pattern in `explore.clj`.

   Frontmatter shape is hand-rolled (no clj-yaml dep). Top-level scalars +
   three nested blocks (`pre:`, `apply:`, `verify:`) — each emitted YAML
   block-style with one-space-indented keys, vectors emitted as flow-style
   `[a, b]` so the round-trip parser stays simple.

   See `docs/update-agent-design.md` §6 for the persistence contract and §11
   for the helper signatures."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import (java.text SimpleDateFormat)
           (java.util Date)))

;; ============================================================================
;; Constants & utilities
;; ============================================================================

(def ^:private edits-subdir "edits")
(def ^:private edits-base ".brainyard/agents/update-agent")
(def ^:private edits-dir-rel (str edits-base "/" edits-subdir))
(def ^:private backups-dir-rel (str edits-base "/backups"))
(def ^:private index-rel (str edits-base "/INDEX.md"))

(def ^:private slug-stopwords
  #{"a" "an" "the" "is" "are" "and" "or" "of" "in" "on" "at" "to" "for"
    "by" "with" "from" "as" "but" "if" "then" "than" "so"
    "what" "where" "when" "who" "whom" "why" "how" "which"
    "do" "does" "did" "can" "could" "would" "should" "shall" "will"
    "this" "that" "these" "those" "it" "its" "we" "they" "you" "i"
    "be" "been" "being" "was" "were" "have" "has" "had"})

(defn- now-ts []
  (.format (SimpleDateFormat. "yyyyMMdd-HHmmss") (Date.)))

(defn- now-iso []
  (str (java.time.Instant/now)))

(defn- now-yyyy-mm-dd-hh-mm []
  (-> (subs (now-iso) 0 16)
      (str/replace "T" " ")))

;; ============================================================================
;; update$slug
;; ============================================================================

(defn- slugify
  [request max-chars]
  (let [tokens (-> (str request)
                   str/lower-case
                   (str/replace #"[^a-z0-9]+" "-")
                   (str/split #"-"))
        kept   (->> tokens
                    (remove #(or (str/blank? %) (slug-stopwords %)))
                    seq)
        joined (if kept (str/join "-" kept) "edit")]
    (subs joined 0 (min max-chars (count joined)))))

(defcommand update$slug
  "Deterministic kebab-case slug from an edit request.
   Drops a small stopword set and caps at 60 chars (override with `:max-chars`).
   Same request always yields the same slug — re-runs cluster in directory
   listings without GUIDs.

   Returns `{:slug \"<kebab-case-slug>\"}`. Empty/blank input yields slug
   \"edit\" so downstream code never sees an empty filename."
  (fn [& {:keys [request max-chars]
          :or   {max-chars 60}}]
    (cond
      (not (string? request))
      {:error ":request is required (string)"}

      (or (not (integer? max-chars)) (<= max-chars 0))
      {:error ":max-chars must be a positive integer"}

      :else
      {:slug (slugify request max-chars)}))
  :input-schema  [:map
                  [:request   [:string {:desc "Edit request to slugify"}]]
                  [:max-chars {:optional true} [:int {:desc "Cap on slug length in chars (default 60)"}]]]
  :output-schema [:map
                  [:slug  [:string {:desc "Kebab-case slug, stopwords dropped"}]]
                  [:error [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; YAML emission helpers (shared by frontmatter)
;; ============================================================================

(defn- yaml-string
  [s]
  (str "\"" (-> (str s)
                (str/replace "\\" "\\\\")
                (str/replace "\"" "\\\""))
       "\""))

(def ^:private bareword-re #"^[A-Za-z0-9_./:-]+$")

(defn- yaml-scalar
  "Emit a Clojure value as a YAML scalar. Strings matching the bareword
   charset (alnum + `_./:-`) are unquoted; everything else is quoted.
   Booleans and numbers pass through verbatim (lower-case for booleans).
   Keywords emit their name unquoted."
  [v]
  (cond
    (nil? v)        "null"
    (boolean? v)    (if v "true" "false")
    (keyword? v)    (name v)
    (number? v)     (str v)
    (string? v)     (if (and (seq v) (re-matches bareword-re v))
                      v
                      (yaml-string v))
    :else           (yaml-string (str v))))

(defn- yaml-flow-vector
  [xs]
  (str "[" (str/join ", " (map yaml-scalar xs)) "]"))

(defn- yaml-value
  "Emit a value for either a scalar slot or a flow vector slot."
  [v]
  (cond
    (sequential? v) (yaml-flow-vector v)
    :else           (yaml-scalar v)))

(defn- yaml-block
  "Emit a top-level block: `<key>:\\n  <subkey>: <value>\\n…`. Sub-values are
   either scalars (numbers/strings/booleans/keywords) or vectors (flow-style).
   `entries` is a seq of [k v] pairs to preserve insertion order."
  [block-key entries]
  (apply str
         (name block-key) ":\n"
         (mapv (fn [[k v]]
                 (str "  " (name k) ": " (yaml-value v) "\n"))
               entries)))

(defn- format-summary
  "Folded scalar — collapse internal whitespace to keep the summary on one
   physical line under `>`."
  [s]
  (let [collapsed (-> (str s) (str/replace #"\s+" " ") str/trim)]
    (str ">\n  " collapsed)))

;; ============================================================================
;; update$frontmatter
;; ============================================================================

(defn- ordered-pairs
  "Stable ordering for sub-block emission: prefer the curated key list when
   provided, append any remaining keys in original-map order."
  [m preferred]
  (let [in-pref  (filter #(contains? m %) preferred)
        leftover (remove (set preferred) (keys m))]
    (mapv (fn [k] [k (get m k)]) (concat in-pref leftover))))

(def ^:private pre-key-order    [:head_rev :status :recent :match_count :region_lines :region_sha])
(def ^:private apply-key-order  [:pattern :replacement :regex :all :replaced
                                 :region_lines :region_sha :path :bytes])
(def ^:private verify-key-order [:diff_match :old_count_after :new_count_after
                                 :lint :tests])

(defn- build-frontmatter*
  [{:keys [request slug mode target pre apply* verify rollback ok summary
           created turn-id session-id]}]
  (str "---\n"
       "slug: " slug "\n"
       "request: " (yaml-string request) "\n"
       "created: " (or created (now-iso)) "\n"
       "agent: update-agent\n"
       "mode: " (yaml-scalar mode) "\n"
       "target: " (yaml-scalar target) "\n"
       (yaml-block :pre    (ordered-pairs (or pre {})    pre-key-order))
       (yaml-block :apply  (ordered-pairs (or apply* {}) apply-key-order))
       (yaml-block :verify (ordered-pairs (or verify {}) verify-key-order))
       "rollback: " (yaml-string rollback) "\n"
       "ok: " (yaml-scalar (boolean ok)) "\n"
       (when summary (str "summary: " (format-summary summary) "\n"))
       (when turn-id (str "turn_id: " (yaml-string turn-id) "\n"))
       (when session-id (str "session_id: " (yaml-string session-id) "\n"))
       "---\n"))

(defcommand update$frontmatter
  "Build a YAML frontmatter block for an update-agent edit record.
   Required: `:request`, `:slug`, `:mode`, `:target`, `:rollback`.
   Optional: `:pre`, `:apply` (map; key conflict with the function `apply` —
   pass as `:apply {…}` in the args map), `:verify`, `:ok`, `:summary`,
   `:created`, `:turn-id`, `:session-id`.

   Sub-blocks (`pre`, `apply`, `verify`) are arbitrary maps of scalar/vector
   values; emitted YAML block-style. Vectors render flow-style for parser
   simplicity.

   Returns `{:frontmatter \"---\\nslug: …\\n…\\n---\\n\"}`. Always ends with
   a trailing newline so the caller can concat the body directly."
  (fn [& {:keys [request slug mode target pre verify rollback ok summary
                 created turn-id session-id]
          :as   m}]
    (let [apply-map (get m :apply)]
      (cond
        (not (string? request))  {:error ":request is required (string)"}
        (not (string? slug))     {:error ":slug is required (string)"}
        (not (or (string? mode) (keyword? mode)))
        {:error ":mode is required (string or keyword)"}
        (not (string? target))   {:error ":target is required (string)"}
        (not (string? rollback)) {:error ":rollback is required (string)"}
        :else
        (let [fm (build-frontmatter*
                  {:request request :slug slug :mode mode :target target
                   :pre pre :apply* apply-map :verify verify
                   :rollback rollback :ok ok :summary summary
                   :created created :turn-id turn-id :session-id session-id})]
          (mulog/log ::update.frontmatter
                     :slug slug
                     :mode (str mode)
                     :target target
                     :ok? (boolean ok))
          {:frontmatter fm}))))
  :input-schema  [:map
                  [:request    [:string {:desc "Verbatim user / caller request"}]]
                  [:slug       [:string {:desc "Kebab-case slug (use update$slug to derive)"}]]
                  [:mode       [:enum   {:desc "Edit mode: pattern | syntax | new-file"}
                                "pattern" "syntax" "new-file"]]
                  [:target     [:string {:desc "Repo-relative path of the edited file"}]]
                  [:pre        {:optional true} [:map {:desc "Pre-flight outcomes (head_rev, status, recent, match_count, region_lines, region_sha)"}]]
                  [:apply      {:optional true} [:map {:desc "Apply outcomes (pattern, replacement, regex, all, replaced, region_lines, region_sha, path, bytes)"}]]
                  [:verify     {:optional true} [:map {:desc "Verify outcomes (diff_match, old_count_after, new_count_after, lint, tests)"}]]
                  [:rollback   [:string {:desc "Literal shell command to undo this edit (single line, copy-paste safe)"}]]
                  [:ok         {:optional true} [:boolean {:desc "Whether verification passed (default false)"}]]
                  [:summary    {:optional true} [:string {:desc "One-paragraph distilled description (will be folded onto one line)"}]]
                  [:created    {:optional true} [:string {:desc "ISO-8601 created timestamp (default: now)"}]]
                  [:turn-id    {:optional true} [:string {:desc "Trajectory turn-id for cross-reference"}]]
                  [:session-id {:optional true} [:string {:desc "Trajectory session-id for cross-reference"}]]]
  :output-schema [:map
                  [:frontmatter [:string {:desc "Full YAML frontmatter block, trailing newline included"}]]
                  [:error       [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; update$write
;; ============================================================================

(defn- existing-slugs-for
  [base-dir slug]
  (let [^java.io.File dir (io/file base-dir edits-dir-rel)]
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
  (let [n (existing-slugs-for base-dir base-slug)]
    (if (zero? n)
      base-slug
      (str base-slug "-" (inc n)))))

(defcommand update$write
  "Write an edit record under `.brainyard/agents/update-agent/edits/`.

   Computes filename `<yyyyMMdd-HHmmss>-<slug>.md` (timestamp captured at call
   time). If a file with the same slug already exists, suffixes the slug with
   `-2`, `-3`, … so re-runs land near each other in directory listings without
   overwriting prior records.

   Returns `{:path \"<absolute path>\" :slug \"<final slug>\" :ts \"<ts>\"}`."
  (fn [& {:keys [slug content base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? slug))    {:error ":slug is required (string)"}
      (not (string? content)) {:error ":content is required (string)"}
      :else
      (let [final-slug (final-slug-with-suffix base-dir slug)
            ts         (now-ts)
            rel-path   (str edits-dir-rel "/" ts "-" final-slug ".md")
            file       (io/file base-dir rel-path)]
        (.mkdirs (.getParentFile file))
        (spit file content)
        (mulog/log ::update.persist
                   :slug final-slug
                   :path rel-path
                   :bytes (count content)
                   :collision? (not= slug final-slug))
        {:path (.getAbsolutePath file) :slug final-slug :ts ts})))
  :input-schema  [:map
                  [:slug     [:string {:desc "Slug from update$slug (will get -N suffix on collision)"}]]
                  [:content  [:string {:desc "Full file content (frontmatter + body)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:path  [:string {:desc "Absolute path of the written file"}]]
                  [:slug  [:string {:desc "Final slug actually used (may have -N suffix)"}]]
                  [:ts    [:string {:desc "Timestamp portion of filename (yyyyMMdd-HHmmss)"}]]
                  [:error [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; update$index-append
;; ============================================================================

(defn- index-line
  [{:keys [path slug mode target ok? summary]}]
  (let [filename (-> path (str/split #"/") last)
        target-base (-> (str target) (str/split #"/") last)
        mark (if ok? "✅" "❌")
        summary-trim (-> (str summary) (str/replace #"\s+" " ") str/trim)
        summary-cap  (if (> (count summary-trim) 200)
                       (str (subs summary-trim 0 197) "…")
                       summary-trim)
        suffix (if (str/blank? summary-cap)
                 (str " · " mark)
                 (str " · " summary-cap " · " mark))]
    (str "- " (now-yyyy-mm-dd-hh-mm)
         " [" slug "](" edits-subdir "/" filename ") — "
         (name mode) " · `" target-base "`" suffix "\n")))

(defcommand update$index-append
  "Prepend a one-line entry to `.brainyard/agents/update-agent/INDEX.md` (newest-first).

   Format:
     `- YYYY-MM-DD HH:MM [<slug>](edits/<file>.md) — <mode> · \\`<target-basename>\\` · <summary> · ✅`

   Trailing ✅/❌ reflects the verification outcome (`:ok?`). Returns
   `{:appended true :line \"…\"}`."
  (fn [& {:keys [path slug mode target ok? summary base-dir]
          :or   {base-dir (config/project-dir)
                 ok? true
                 summary ""}}]
    (cond
      (not (string? path))   {:error ":path is required (string)"}
      (not (string? slug))   {:error ":slug is required (string)"}
      (not (or (string? mode) (keyword? mode)))
      {:error ":mode is required (string or keyword)"}
      (not (string? target)) {:error ":target is required (string)"}
      :else
      (let [line     (index-line {:path path :slug slug :mode mode
                                  :target target :ok? ok? :summary summary})
            file     (io/file base-dir index-rel)
            existing (if (.isFile file) (slurp file) "")]
        (.mkdirs (.getParentFile file))
        (spit file (str line existing))
        (mulog/log ::update.index
                   :slug slug
                   :path path
                   :mode (str mode)
                   :ok? (boolean ok?))
        {:appended true :line line})))
  :input-schema  [:map
                  [:path     [:string  {:desc "Repo-relative path of the edit record (from update$write)"}]]
                  [:slug     [:string  {:desc "Slug used in the record file"}]]
                  [:mode     [:enum    {:desc "Edit mode"} "pattern" "syntax" "new-file"]]
                  [:target   [:string  {:desc "Repo-relative path of the edited file"}]]
                  [:ok?      {:optional true} [:boolean {:desc "Whether verification passed (default true)"}]]
                  [:summary  {:optional true} [:string  {:desc "One-line summary (collapsed; truncated to 200 chars)"}]]
                  [:base-dir {:optional true} [:string  {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:appended [:boolean {:desc "true on success"}]]
                  [:line     [:string  {:desc "The exact line that was prepended"}]]
                  [:error    [:string  {:desc "Error if validation failed"}]]])

;; ============================================================================
;; YAML parsing — flat scalars + named nested blocks
;; ============================================================================

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

(defn- coerce-bare-value
  "Best-effort coercion of an unquoted YAML scalar: 'true'/'false' → bool,
   integer-only → Long, otherwise the trimmed string. Quoted strings are
   left untouched (caller already stripped the quotes if needed)."
  [s]
  (let [trimmed (str/trim s)]
    (cond
      (= trimmed "true")  true
      (= trimmed "false") false
      (= trimmed "null")  nil
      (re-matches #"-?\d+" trimmed)
      (try (Long/parseLong trimmed) (catch Exception _ trimmed))
      :else trimmed)))

(defn- parse-scalar-value
  "Parse the RHS of `key: value`. Quoted strings → unquoted; flow vectors →
   vectors; bare → coerced via coerce-bare-value."
  [v]
  (let [v (str/trim v)]
    (cond
      (re-matches #"^\[.*\]$" v)
      (parse-flow-vector v)

      (and (str/starts-with? v "\"") (str/ends-with? v "\""))
      (-> v
          (subs 1 (dec (count v)))
          (str/replace "\\\"" "\"")
          (str/replace "\\\\" "\\"))

      :else
      (coerce-bare-value v))))

(def ^:private block-keys
  "Headers that introduce a 2-space-indented block of sub-keys."
  #{"pre" "apply" "verify"})

(defn- parse-record-yaml
  "Lenient parser targeting the shape `update$frontmatter` emits. Recognizes:
   - flat `key: value` (string / quoted / flow-vector / bool / int)
   - folded scalar `key: >` followed by a single indented line
   - block headers in #{pre apply verify} followed by 2-space-indented `subkey: value`"
  [lines]
  (loop [ls    lines
         acc   {}
         block nil]   ; nil | [:flat-block :pre|:apply|:verify] | [:folded-key :k]
    (if (empty? ls)
      acc
      (let [ln    (first ls)
            rest* (rest ls)]
        (cond
          ;; Inside a flat block: 2-space indented `subkey: value` line
          (and (vector? block) (= :flat-block (first block))
               (re-matches #"^\s{2,}\S.*" ln))
          (if-let [[_ k v] (re-matches #"^\s+([\w_-]+):\s*(.*)$" ln)]
            (recur rest*
                   (assoc-in acc [(second block) (keyword k)] (parse-scalar-value v))
                   block)
            (recur rest* acc nil))

          ;; Folded scalar continuation (single line indented under `>`)
          (and (vector? block) (= :folded-key (first block)))
          (let [k (second block)]
            (recur rest* (assoc acc k (str/trim ln)) nil))

          ;; Block header: `pre:`, `apply:`, `verify:`
          (re-matches #"^([\w_-]+):\s*$" ln)
          (let [[_ k] (re-matches #"^([\w_-]+):\s*$" ln)]
            (if (block-keys k)
              (recur rest* (assoc acc (keyword k) {}) [:flat-block (keyword k)])
              (recur rest* acc nil)))

          ;; key: > (folded scalar)
          (re-matches #"^([\w_-]+):\s*>\s*$" ln)
          (let [[_ k] (re-matches #"^([\w_-]+):\s*>\s*$" ln)]
            (recur rest* acc [:folded-key (keyword k)]))

          ;; Standard flat key: value
          (re-matches #"^([\w_-]+):\s*(.*)$" ln)
          (let [[_ k v] (re-matches #"^([\w_-]+):\s*(.*)$" ln)]
            (recur rest* (assoc acc (keyword k) (parse-scalar-value v)) nil))

          :else
          (recur rest* acc block))))))

;; ============================================================================
;; update$read-record
;; ============================================================================

(defcommand update$read-record
  "Read just the leading `---`/`---` YAML block from an edit record and parse
   it into a Clojure map. Cheap (~700 bytes typical) — used by downstream
   agents to confirm an edit landed without paying for the full diff body.

   Returns the parsed map: `{:slug … :request … :mode … :target …
   :pre {…} :apply {…} :verify {…} :rollback … :ok bool}`. Returns
   `{:error \"…\"}` if the file doesn't exist or doesn't begin with `---`."
  (fn [& {:keys [path base-dir]
          :or   {base-dir (config/project-dir)}}]
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
            (parse-record-yaml lines)
            {:error (str "No frontmatter block at " path " (file did not start with ---)")})))))
  :input-schema  [:map
                  [:path     [:string {:desc "Repo-relative or absolute path to an edit record"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:slug     {:optional true} [:string {:desc "Slug from frontmatter"}]]
                  [:request  {:optional true} [:string {:desc "Verbatim request"}]]
                  [:mode     {:optional true} [:string {:desc "Edit mode"}]]
                  [:target   {:optional true} [:string {:desc "Edited file path"}]]
                  [:pre      {:optional true} [:map    {:desc "Pre-flight sub-block"}]]
                  [:apply    {:optional true} [:map    {:desc "Apply sub-block"}]]
                  [:verify   {:optional true} [:map    {:desc "Verify sub-block"}]]
                  [:rollback {:optional true} [:string {:desc "Rollback shell command"}]]
                  [:ok       {:optional true} [:boolean {:desc "Whether verification passed"}]]
                  [:error    {:optional true} [:string {:desc "Error if file missing or no frontmatter"}]]])

;; ============================================================================
;; update$find
;; ============================================================================

(defcommand update$find
  "Search prior update-agent edit records by substring (case-insensitive) over slug+target+request; returns newest-first matches."
  (fn [& {:keys [query base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? query))
      {:error ":query is required (string)"}

      :else
      (let [^java.io.File dir (io/file base-dir edits-dir-rel)
            q   (str/lower-case query)]
        (if-not (.isDirectory dir)
          {:matches [] :n-matches 0}
          (let [matches
                (->> (.listFiles dir)
                     (filter (fn [^java.io.File f]
                               (and (.isFile f) (str/ends-with? (.getName f) ".md"))))
                     (keep (fn [^java.io.File f]
                             (when-let [lines (read-frontmatter-lines f)]
                               (let [fm (parse-record-yaml lines)
                                     hay (str/lower-case
                                          (str (:slug fm) " "
                                               (:target fm) " "
                                               (:request fm)))]
                                 (when (str/includes? hay q)
                                   {:path    (str edits-dir-rel "/" (.getName f))
                                    :slug    (:slug fm)
                                    :target  (:target fm)
                                    :mode    (:mode fm)
                                    :ok?     (boolean (:ok fm))
                                    :created (:created fm)})))))
                     (sort-by :path)
                     reverse
                     vec)]
            (mulog/log ::update.find
                       :query query
                       :n-matches (count matches))
            {:matches matches :n-matches (count matches)})))))
  :input-schema  [:map
                  [:query    [:string {:desc "Substring to match against slug + target + request (case-insensitive)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:matches   [:vector {:desc "Matching records, newest-first"}
                               [:map {} :path :slug :target :mode :ok? :created]]]
                  [:n-matches [:int    {:desc "Number of matches"}]]
                  [:error     [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; update$apply — full pipeline (PROBE → APPLY → VERIFY → PERSIST → ROLLBACK)
;; ============================================================================

(defn- shell-out
  "Run a shell command in `base-dir` via clojure.java.shell/sh. Returns
   {:exit :out :err}. Errors are captured, never thrown — callers branch on
   :exit. Used for read-only git/probe commands; the bash deftool isn't
   suitable here because we want quiet, JVM-fast invocations without going
   through the task system."
  [base-dir cmd-vec]
  (try
    (apply shell/sh (concat cmd-vec [:dir base-dir]))
    (catch Throwable t
      {:exit -1 :out "" :err (str "shell-error: " (.getMessage t))})))

(defn- git-status-clean?
  [base-dir target]
  (let [{:keys [exit out]} (shell-out base-dir ["git" "status" "--porcelain" "--" target])]
    {:exit  exit
     :out   (str out)
     :clean? (and (zero? exit) (str/blank? (str out)))}))

(defn- git-rev-parse-head
  [base-dir]
  (-> (shell-out base-dir ["git" "rev-parse" "HEAD"]) :out str str/trim))

(defn- git-recent-commits
  [base-dir target n]
  (let [{:keys [exit out]} (shell-out base-dir ["git" "log" (str "--oneline") (str "-n" n) "--" target])]
    (if (zero? exit)
      (->> (str/split-lines (str out))
           (remove str/blank?)
           vec)
      [])))

(defn- normalize-diff
  "Strip file-header lines (`diff --git`, `index`, `---`, `+++`, mode lines)
   and per-line trailing whitespace before V1 byte compare. We compare HUNK
   content only — `git diff --no-index` (used by direct-update-file) and
   `git diff -- <target>` (used by V1) emit different file headers but
   identical hunk content for the same change. Mirrors the design doc's
   'modulo trailing whitespace' clause, extended to also tolerate header
   format differences."
  [^String s]
  (when (string? s)
    (->> (str/split-lines s)
         (remove (fn [ln]
                   (or (str/starts-with? ln "diff --git ")
                       (str/starts-with? ln "index ")
                       (str/starts-with? ln "--- ")
                       (str/starts-with? ln "+++ ")
                       (str/starts-with? ln "new file mode ")
                       (str/starts-with? ln "old mode ")
                       (str/starts-with? ln "new mode ")
                       (str/starts-with? ln "deleted file mode "))))
         (map #(str/replace % #"[ \t]+$" ""))
         (str/join "\n")
         str/trim)))

(defn- count-pattern-matches
  [content pattern regex?]
  (when (and (string? content) (string? pattern))
    (let [p (if regex?
              (re-pattern pattern)
              (re-pattern (java.util.regex.Pattern/quote pattern)))]
      (count (re-seq p content)))))

(defn- read-target-content
  [base-dir target]
  (let [f (if (.isAbsolute (io/file target))
            (io/file target)
            (io/file base-dir target))]
    (when (.isFile f)
      (try (slurp f) (catch Throwable _ nil)))))

(defn- file-exists?
  [base-dir target]
  (.isFile (if (.isAbsolute (io/file target))
             (io/file target)
             (io/file base-dir target))))

(defn- linter-for-ext
  "Return [tool-name (fn [path] cmd-vec)] for the given extension, or nil if
   unsupported. Caller checks `command-v?` to skip silently when the tool is
   not installed."
  [^String target]
  (let [ext (-> target (str/split #"\.") last str/lower-case)]
    (case ext
      ("clj" "cljs" "cljc") ["clj-kondo" (fn [p] ["clj-kondo" "--lint" p])]
      "py"                  ["python3"   (fn [p] ["python3" "-m" "py_compile" p])]
      "json"                ["jq"        (fn [p] ["jq" "empty" p])]
      ("yaml" "yml")        ["yamllint"  (fn [p] ["yamllint" p])]
      nil)))

(defn- command-v?
  [base-dir tool]
  (zero? (:exit (shell-out base-dir ["sh" "-c" (str "command -v " tool)]))))

(defn- finding-count
  "Count `:line:col:` findings in linter output. clj-kondo / yamllint emit one
   per finding; jq / py_compile emit few/none and rely on the exit code."
  [^String out]
  (count (re-seq #"(?m):\d+:\d+:" (str out))))

(defn- lint-file
  "Lint `path` using the linter for `target`'s extension, with cwd `base-dir`.
   Returns {:tool :exit :output :findings} or nil when the extension is
   unsupported or the tool is not installed."
  [base-dir target path]
  (when-let [[tool mk-cmd] (linter-for-ext target)]
    (when (command-v? base-dir tool)
      (let [{:keys [exit out err]} (shell-out base-dir (mk-cmd path))]
        {:tool tool :exit exit :output (str out err)
         :findings (finding-count (str out err))}))))

(defn- lint-regressed?
  "True when `after` is worse than `before` — a higher exit code OR more
   findings (only edit-INTRODUCED issues count). With no `before` (new-file,
   or baseline lint unavailable) any non-zero exit is a regression."
  [before after]
  (cond
    (nil? after)  false
    (nil? before) (not (zero? (:exit after)))
    :else         (or (> (:exit after) (:exit before))
                      (> (:findings after) (:findings before)))))

(defn- infer-component
  "Polylith component name from a repo-relative path: components/<name>/… →
   <name>. nil when the target is not under components/."
  [target]
  (second (re-find #"(?:^|/)components/([^/]+)/" (str target))))

(defn- run-component-tests!
  "Run `bb test:component <component>` with cwd `base-dir`. Returns
   {:component :exit :output}."
  [base-dir component]
  (let [{:keys [exit out err]} (shell-out base-dir ["bb" "test:component" component])]
    {:component component :exit exit :output (str out err)}))

(defn- take-backup!
  "Write `original` bytes to a per-transaction backup file under
   `.brainyard/agents/update-agent/backups/`. Used only when the file was dirty
   going in (`:dirty-ok? true`) — for clean edits the operator can use
   `git checkout -- <target>`. Returns the repo-relative backup path on
   success, nil on failure."
  [base-dir slug original]
  (try
    (let [ts        (now-ts)
          rel-path  (str backups-dir-rel "/" ts "-" slug ".bak")
          file      (io/file base-dir rel-path)]
      (.mkdirs (.getParentFile file))
      (spit file (or original ""))
      rel-path)
    (catch Throwable t
      (mulog/warn ::update.backup-failed :error (.getMessage t))
      nil)))

(defn- rollback-tracked
  "Restore `target` to its pre-APPLY bytes. `original` is the content captured
   at PROBE time (before update-file ran). We deliberately do NOT use `git
   checkout -- <target>` — that resets the working tree to HEAD and would
   discard any pre-existing uncommitted changes the file already had going
   into this transaction. Restoring `original` makes the rollback
   transaction-scoped: only THIS edit is undone."
  [base-dir target original]
  (let [f (if (.isAbsolute (io/file target))
            (io/file target)
            (io/file base-dir target))]
    (try
      (spit f (or original ""))
      {:ok? true
       :output (str "restored " (count (or original "")) " bytes to " target)}
      (catch Throwable t
        {:ok? false
         :output (str "restore failed: " (ex-message t))}))))

(defn- rollback-fresh
  [base-dir target]
  (let [f (if (.isAbsolute (io/file target))
            (io/file target)
            (io/file base-dir target))
        deleted? (try (.delete f) (catch Throwable _ false))]
    {:ok? (boolean deleted?) :output (str "rm: " target " " (if deleted? "ok" "failed"))}))

(defn- resolve-target-file
  "Resolve a target path under base-dir to an absolute java.io.File, refusing
   any traversal outside the project root. Returns {:file …} on success or
   {:error …} on refusal."
  [base-dir target]
  (let [raw (if (.isAbsolute (io/file target))
              (io/file target)
              (io/file base-dir target))]
    (try
      (let [canon-base   (.getCanonicalPath (io/file base-dir))
            canon-target (.getCanonicalPath raw)]
        (cond
          (str/includes? (str target) "..")
          {:error (str "Refusing target with parent-traversal: " target)}

          (or (str/includes? canon-target "/.git/")
              (str/ends-with? canon-target "/.git"))
          {:error (str "Refusing target inside .git/: " target)}

          (not (or (= canon-target canon-base)
                   (str/starts-with? canon-target (str canon-base "/"))))
          {:error (str "Refusing out-of-tree target: " target)}

          :else
          {:file raw :canonical canon-target}))
      (catch Throwable t
        {:error (str "Could not canonicalize target: " (.getMessage t))}))))

(defn- git-diff-no-index
  "Compute a unified diff between `old-content` and the current contents of
   `new-file` via `git diff --no-index`. Falls back to a minimal line-by-line
   diff if git is unavailable. Mirrors tools.clj/compute-diff."
  [old-content ^java.io.File new-file]
  (let [old-tmp (doto (java.io.File/createTempFile "update-old-" ".txt")
                  (.deleteOnExit))]
    (try
      (spit old-tmp old-content)
      (let [{:keys [exit out]} (shell/sh "git" "diff" "--no-index" "--no-color"
                                         "--" (.getPath old-tmp) (.getPath new-file))]
        (if (#{0 1} exit)
          {:diff (str out) :diff-source "git"}
          {:diff (str "--- " (.getPath new-file) " (old)\n+++ " (.getPath new-file) " (new)\n")
           :diff-source "fallback"}))
      (catch Throwable _
        {:diff "" :diff-source "fallback"})
      (finally
        (try (.delete old-tmp) (catch Throwable _))))))

(defn- direct-update-file
  "Direct file edit — pattern→replacement against `<base-dir>/<target>` (or an
   absolute target). Bypasses the update-file deftool's get-base-dir lookup so
   update$apply works without a bound agent (callable from tests / standalone
   helpers). Path is canonicalized + refused for `.git/` / out-of-tree.

   Returns {:path :replaced :diff :diff-source} on success, {:error …} on fail."
  [base-dir target pattern replacement regex? all?]
  (let [{:keys [^java.io.File file error]} (resolve-target-file base-dir target)]
    (if error
      {:error error}
      (let [original (try (slurp file) (catch Throwable t {:error (.getMessage t)}))]
        (if (map? original)
          original
          (let [p (if regex?
                    (re-pattern pattern)
                    (re-pattern (java.util.regex.Pattern/quote pattern)))
                r (if regex?
                    replacement
                    (java.util.regex.Matcher/quoteReplacement replacement))
                total (count (re-seq p original))]
            (cond
              (zero? total)
              {:error (format "Pattern not found in %s" target)}

              :else
              (let [replaced    (if all? total 1)
                    new-content (if all?
                                  (str/replace original p r)
                                  (str/replace-first original p r))]
                (if (= original new-content)
                  {:error "Replacement produced identical content"}
                  (do (spit file new-content)
                      (let [{:keys [diff diff-source]} (git-diff-no-index original file)]
                        {:path        (.getPath file)
                         :replaced    replaced
                         :diff        diff
                         :diff-source diff-source})))))))))))

(defn- direct-write-file
  "Direct file write — used for new-file mode. Refuses if the file already
   exists (callers should pre-flight)."
  [base-dir target content]
  (let [{:keys [^java.io.File file error]} (resolve-target-file base-dir target)]
    (cond
      error
      {:error error}

      (.isFile file)
      {:error (str "File already exists (new-file mode): " target)}

      :else
      (do (.mkdirs (.getParentFile file))
          (spit file content)
          {:path (.getPath file) :replaced (count content) :diff "" :diff-source "n/a"}))))

(defn- maybe-handle-stash
  "Run `git stash push -- target` when :dirty-ok? = :stash. Returns the stash
   indicator (truthy if stash was created, falsy otherwise)."
  [base-dir target dirty-ok?]
  (when (= :stash dirty-ok?)
    (let [{:keys [exit]} (shell-out base-dir ["git" "stash" "push" "--" target])]
      (zero? exit))))

(defn- maybe-pop-stash
  "Pop the per-target stash created by `maybe-handle-stash`. Returns
   `{:ok? bool :output str}` so the caller can surface the result, or nil
   when no stash was created. NOTE: `git stash pop` refuses to merge when
   the working tree already has local changes to the same file — which
   APPLY has just produced. The common :stash-mode flow therefore leaves
   the stash in `git stash list` even though the pipeline returns
   ok?=true; the rollback path is unaffected because rollback-tracked
   clears the edit before pop runs. Operators should prefer
   `:dirty-ok? true` for layered edits — same intent, no pop-conflict
   failure mode."
  [base-dir stashed?]
  (when stashed?
    (let [{:keys [exit out err]} (shell-out base-dir ["git" "stash" "pop"])]
      {:ok? (zero? exit) :output (str out err)})))

(defn- pipeline-error
  [stage msg]
  {:ok? false :stage stage :error msg})

(defn- persist-record!
  [{:keys [base-dir request slug mode target pre apply-map verify rollback ok summary]}]
  (let [final-slug (final-slug-with-suffix base-dir slug)
        ts         (now-ts)
        rel-path   (str edits-dir-rel "/" ts "-" final-slug ".md")
        file       (io/file base-dir rel-path)
        body-diff  (or (:diff apply-map) "")
        verify-lines
        (->> [(when (some? (:diff_match verify))
                (str "- diff matches: " (:diff_match verify)))
              (when (some? (:old_count_after verify))
                (str "- old pattern after: " (:old_count_after verify)))
              (when (some? (:new_count_after verify))
                (str "- new pattern after: " (:new_count_after verify)))
              (when (some? (:lint verify))
                (str "- lint: " (pr-str (:lint verify))))
              (when (some? (:tests verify))
                (str "- tests: " (pr-str (:tests verify))))]
             (remove nil?))
        body (str "# " request "\n\n"
                  "## What changed\n"
                  (or summary "(no summary)") "\n\n"
                  "## Diff\n"
                  "```diff\n" body-diff "\n```\n\n"
                  "## Verification\n"
                  (str/join "\n" verify-lines) "\n\n"
                  "## Rollback\n"
                  "```\n" rollback "\n```\n")
        fm (build-frontmatter*
            {:request request :slug final-slug :mode mode :target target
             :pre pre :apply* apply-map :verify verify
             :rollback rollback :ok ok :summary summary})]
    (.mkdirs (.getParentFile file))
    (spit file (str fm body))
    (mulog/log ::update.persist
               :slug final-slug :path rel-path
               :ok? (boolean ok) :mode (str mode))
    {:path (.getAbsolutePath file) :slug final-slug :ts ts}))

(defn- run-pipeline
  [{:keys [request target mode pattern replacement regex? all? content
           dirty-ok? lint-ok-to-fail? run-tests? base-dir]
    :as opts}]
  (let [request   (str request)
        slug      (slugify request 60)
        ;; ---------------- PROBE ----------------
        rev       (git-rev-parse-head base-dir)
        existed?  (file-exists? base-dir target)
        status    (git-status-clean? base-dir target)   ; pre-stash status (audit)
        recent    (git-recent-commits base-dir target 5)

        ;; Stash early when :dirty-ok? :stash so APPLY operates on the clean
        ;; baseline. `original` MUST be captured AFTER this — V1 (txn-diff)
        ;; and rollback both compare to what APPLY actually saw on disk.
        stashed?  (maybe-handle-stash base-dir target dirty-ok?)

        original  (when existed? (read-target-content base-dir target))
        n-matches (when (and (= :pattern mode) (some? original))
                    (count-pattern-matches original pattern regex?))
        expected  (cond
                    (not= :pattern mode) nil
                    all? n-matches
                    :else 1)
        pre-map   (cond-> {:head_rev rev
                           :status   (cond
                                       (and (not (:clean? status))
                                            (= :stash dirty-ok?))             "stashed"
                                       (and (not (:clean? status)) dirty-ok?) "dirty-ok"
                                       (:clean? status)                       "clean"
                                       :else                                  "dirty")
                           :recent   (vec recent)}
                    (= :pattern mode) (assoc :match_count (or n-matches 0)))]

    (cond
      ;; Out-of-tree refusal
      (or (str/blank? target)
          (str/includes? (str target) "..")
          (str/includes? (str target) ".git/"))
      (pipeline-error :probe (str "Refusing target outside project tree: " target))

      ;; P1 — dirty file refusal
      (and (not (:clean? status)) (not dirty-ok?))
      (pipeline-error :probe (str "Refusing to edit dirty file. git status:\n" (:out status)))

      ;; P3 — file must exist for pattern/syntax modes
      (and (not= :new-file mode) (not existed?))
      (pipeline-error :probe (str "Target does not exist: " target))

      ;; new-file mode requires the file NOT to exist
      (and (= :new-file mode) existed?)
      (pipeline-error :probe (str "new-file mode requires non-existent target; got existing: " target))

      ;; P4 — pattern-mode count mismatch
      (and (= :pattern mode) (not= n-matches expected))
      (pipeline-error :probe
                      (format "Pattern match count mismatch: found %d, expected %d (escalate to syntax mode or re-anchor pattern)."
                              (or n-matches 0) (or expected 0)))

      :else
      (let [;; Backup only when going in dirty via `:dirty-ok? true` —
            ;; clean edits use the familiar `git checkout -- <target>`
            ;; rollback. `:stash` mode is intentionally excluded: APPLY
            ;; operates on the post-stash CLEAN baseline, so a backup of
            ;; the post-stash content would mislead the operator (cp'ing
            ;; it back would not restore the stashed dirty state).
            ;; Bounded growth: one .bak per :dirty-ok? true edit.
            backup-rel (when (and (not= :new-file mode)
                                  (not (:clean? status))
                                  (true? dirty-ok?))
                         (take-backup! base-dir slug original))

            ;; V4 baseline — lint the REAL file BEFORE the edit (still holds
            ;; `original`), so the post-edit delta compares apples-to-apples
            ;; (same path / ns-root). nil for new-file mode.
            lint-before (when (not= :new-file mode)
                          (lint-file base-dir target target))

            ;; ---------------- APPLY ----------------
            apply-result
            (case mode
              :new-file (direct-write-file base-dir target (or content ""))
              (direct-update-file base-dir target pattern replacement regex? all?))
            apply-err  (or (:error apply-result) (:error-message apply-result))]
        (if apply-err
          (do (maybe-pop-stash base-dir stashed?)
              (pipeline-error :apply (str "Apply failed: " apply-err)))
          (let [diff       (or (:diff apply-result) "")
                replaced   (or (:replaced apply-result) 0)
                rollback-cmd
                (cond
                  (= :new-file mode) (str "rm -- " target)
                  ;; Dirty-ok edit with a backup → manual rollback must
                  ;; preserve the prior dirty state; git-checkout would
                  ;; clobber it.
                  backup-rel         (str "cp -- '" backup-rel "' '" target "'")
                  :else              (str "git checkout -- " target))

                ;; ---------------- VERIFY ----------------
                ;; V1 — transaction-scoped diff match. Compare the diff between
                ;; pre-APPLY `original` and post-APPLY disk bytes against
                ;; update-file's reported `diff`. Using `git diff -- <target>`
                ;; here would be working-tree-vs-HEAD scoped and would falsely
                ;; flag any pre-existing uncommitted changes in the file as a
                ;; mismatch (the bug that broke layered :dirty-ok? edits).
                target-file (if (.isAbsolute (io/file target))
                              (io/file target)
                              (io/file base-dir target))
                txn-diff    (when (not= :new-file mode)
                              (:diff (git-diff-no-index original target-file)))
                diff-match? (or (= :new-file mode)
                                (= (normalize-diff txn-diff) (normalize-diff diff)))
                new-content (read-target-content base-dir target)
                old-after   (when (and (= :pattern mode) new-content)
                              (count-pattern-matches new-content pattern regex?))
                new-after   (when (and (= :pattern mode) new-content)
                              (count-pattern-matches new-content replacement regex?))
                ;; V4 — lint as a before/after delta: only edit-INTRODUCED
                ;; findings count (lint-before captured pre-APPLY).
                lint-after  (lint-file base-dir target target)
                lint-fail?  (lint-regressed? lint-before lint-after)
                ;; V2/V3 expectations
                v2-ok?      (or (not= :pattern mode)
                                (if all?
                                  (zero? (or old-after 0))
                                  (= (max 0 (dec (or n-matches 0))) (or old-after 0))))
                v3-ok?      (or (not= :pattern mode) (>= (or new-after 0) 1))
                ;; V5 — tests. Only when :run-tests? true AND V1–V4 passed
                ;; (no point testing an edit we're about to roll back) AND a
                ;; component is inferable from the path.
                pre-test-ok? (and diff-match? v2-ok? v3-ok?
                                  (or (not lint-fail?) lint-ok-to-fail?))
                component    (when run-tests? (infer-component target))
                test-result  (when (and run-tests? pre-test-ok? component)
                               (run-component-tests! base-dir component))
                tests-fail?  (boolean (and test-result (not (zero? (:exit test-result)))))
                ok?          (and pre-test-ok? (not tests-fail?))

                apply-map   (cond-> {:path     (or (:path apply-result) target)
                                     :replaced replaced}
                              (= :pattern mode) (merge {:pattern pattern
                                                        :replacement replacement
                                                        :regex (boolean regex?)
                                                        :all (boolean all?)})
                              (= :new-file mode) (assoc :bytes (count (or content ""))))
                verify-map  (cond-> {:diff_match (boolean diff-match?)}
                              (= :pattern mode) (merge {:old_count_after (or old-after 0)
                                                        :new_count_after (or new-after 0)})
                              lint-after        (assoc :lint
                                                       (str (:tool lint-after) ":" (:exit lint-after)
                                                            " (findings "
                                                            (if lint-before (:findings lint-before) "n/a")
                                                            "→" (:findings lint-after) ")"))
                              (nil? lint-after) (assoc :lint "skipped")
                              true              (assoc :tests
                                                       (cond
                                                         (not run-tests?)   "skipped"
                                                         (not pre-test-ok?) "skipped (verify failed)"
                                                         (nil? component)   (str "skipped (no component: " target ")")
                                                         test-result        (str "bb test:component " component ":" (:exit test-result))
                                                         :else              "skipped")))

                ;; ---------------- ROLLBACK on fail ----------------
                ;; Tracked-file rollback restores `original` bytes (captured
                ;; pre-APPLY) so prior uncommitted state survives — see
                ;; rollback-tracked. New-file mode rolls back via `rm`.
                rb (when-not ok?
                     (if (= :new-file mode)
                       (rollback-fresh base-dir target)
                       (rollback-tracked base-dir target original)))

                ;; ---------------- PERSIST ----------------
                summary (cond
                          (and ok? (= :new-file mode))
                          (str (count (or content "")) " bytes written")

                          ok?
                          (str replaced " replaced")

                          (and rb (:ok? rb))
                          "rolled back (verify failed)"

                          :else
                          "rollback failed; workspace UNKNOWN — manual intervention required")
                rec (persist-record! {:base-dir base-dir
                                      :request request
                                      :slug slug
                                      :mode (name mode)
                                      :target target
                                      :pre pre-map
                                      :apply-map apply-map
                                      :verify verify-map
                                      :rollback rollback-cmd
                                      :ok ok?
                                      :summary summary})
                _ (try
                    (spit (io/file base-dir index-rel)
                          (str (index-line {:path (:path rec) :slug (:slug rec)
                                            :mode mode :target target
                                            :ok? ok? :summary summary})
                               (if (.isFile (io/file base-dir index-rel))
                                 (slurp (io/file base-dir index-rel))
                                 "")))
                    (catch Throwable t
                      (mulog/error ::update.index-failed :exception t)))]
            (let [pop-result (maybe-pop-stash base-dir stashed?)]
              (cond-> {:path     (:path rec)
                       :slug     (:slug rec)
                       :ok?      ok?
                       :mode     (name mode)
                       :target   target
                       :replaced replaced
                       :diff     diff
                       :verify   verify-map
                       :rollback (if ok? rollback-cmd nil)}
                (and (not ok?) rb)
                (assoc :rolled-back (boolean (:ok? rb))
                       :rollback-output (:output rb))
                ;; Surface stash pop failure so the operator knows the
                ;; dirty content is still in `git stash list` and needs
                ;; manual recovery.
                (and pop-result (not (:ok? pop-result)))
                (assoc :stash-pop-failed?  true
                       :stash-pop-output   (:output pop-result))))))))))

(defcommand update$apply
  "Run the full safe-edit pipeline: PROBE → APPLY → VERIFY → PERSIST → ROLLBACK-on-fail. Mode: :pattern (needs :pattern+:replacement), :syntax (whole-region replace), or :new-file (needs :content)."
  (fn [& {:keys [request target mode pattern replacement regex? all? content
                 dirty-ok? run-tests? lint-ok-to-fail? base-dir]
          :or   {base-dir (config/project-dir)
                 mode :pattern
                 all? false
                 regex? false
                 dirty-ok? false
                 lint-ok-to-fail? false
                 run-tests? false}}]
    (let [mode-kw (cond
                    (keyword? mode) mode
                    (string? mode)  (keyword mode)
                    :else           :pattern)
          opts {:request request :target target :mode mode-kw
                :pattern pattern :replacement replacement
                :regex? regex? :all? all? :content content
                :dirty-ok? dirty-ok? :run-tests? run-tests?
                :lint-ok-to-fail? lint-ok-to-fail?
                :base-dir base-dir}]
      (cond
        (not (string? request))
        {:error ":request is required (string)"}

        (not (string? target))
        {:error ":target is required (string)"}

        (not (#{:pattern :syntax :new-file} mode-kw))
        {:error (str "Unknown :mode " mode-kw " (expected :pattern, :syntax, or :new-file)")}

        (and (#{:pattern :syntax} mode-kw)
             (or (not (string? pattern)) (not (string? replacement))))
        {:error ":pattern and :replacement are required strings for pattern/syntax mode"}

        (and (= :new-file mode-kw) (not (string? content)))
        {:error ":content is required (string) for new-file mode"}

        :else
        (try
          (let [result (run-pipeline opts)]
            (mulog/log ::update.apply
                       :target target :mode (str mode-kw)
                       :ok? (boolean (:ok? result))
                       :stage (or (:stage result) :complete))
            result)
          (catch Throwable t
            (mulog/error ::update.apply-exception
                         :exception t :target target :mode (str mode-kw))
            {:ok? false :stage :exception
             :error (str "Pipeline exception: " (.getMessage t))})))))
  :input-schema  [:map
                  [:request          [:string  {:desc "Verbatim edit request (used for slug + record body)"}]]
                  [:target           [:string  {:desc "Repo-relative path to edit"}]]
                  [:mode             {:optional true} [:enum    {:desc "Edit mode"}
                                                       "pattern" "syntax" "new-file"]]
                  [:pattern          {:optional true} [:string  {:desc "Pattern to find (literal by default; regex when :regex? true). Required for pattern/syntax mode."}]]
                  [:replacement      {:optional true} [:string  {:desc "Replacement text. Required for pattern/syntax mode."}]]
                  [:regex?           {:optional true} [:boolean {:desc "Treat :pattern as a Java regex (default false)"}]]
                  [:all?             {:optional true} [:boolean {:desc "Replace every match (default false — first match only)"}]]
                  [:content          {:optional true} [:string  {:desc "Full file content for new-file mode"}]]
                  [:dirty-ok?        {:optional true} [:string  {:desc "false (default) | true | :stash. When false, refuses dirty target."}]]
                  [:run-tests?       {:optional true} [:boolean {:desc "Run `bb test:component <inferred>` after VERIFY when the target is under components/<name>/; rolls back on failure (default false)"}]]
                  [:lint-ok-to-fail? {:optional true} [:boolean {:desc "When true, lint failures warn instead of rolling back"}]]
                  [:base-dir         {:optional true} [:string  {:desc "Project root (default: resolved from session/git)"}]]]
  :output-schema [:map
                  [:path        [:string  {:desc "Repo-relative path of the persisted edit record"}]]
                  [:slug        [:string  {:desc "Slug used in the record filename"}]]
                  [:ok?         [:boolean {:desc "Whether the pipeline succeeded end-to-end (false on probe/apply/verify failure)"}]]
                  [:mode        [:string  {:desc "Mode that ran"}]]
                  [:target      [:string  {:desc "Edited path"}]]
                  [:replaced    {:optional true} [:int     {:desc "Number of replacements (pattern/syntax) or bytes (new-file)"}]]
                  [:diff        {:optional true} [:string  {:desc "Unified diff returned by update-file"}]]
                  [:verify      {:optional true} [:map     {:desc "Verify outcomes"}]]
                  [:rollback    {:optional true} [:string  {:desc "Shell command to undo this edit (only set when :ok? true)"}]]
                  [:rolled-back {:optional true} [:boolean {:desc "Whether rollback succeeded (only set when :ok? false)"}]]
                  [:stash-pop-failed? {:optional true} [:boolean {:desc ":dirty-ok? :stash only — true when `git stash pop` conflicted with the edit. The dirty content is preserved in `git stash list` for manual recovery. Prefer :dirty-ok? true to avoid this failure mode."}]]
                  [:stash-pop-output  {:optional true} [:string  {:desc ":dirty-ok? :stash only — stdout/stderr from the failed `git stash pop`."}]]
                  [:stage       {:optional true} [:string  {:desc "Stage that failed: :probe | :apply | :verify | :exception"}]]
                  [:error       {:optional true} [:string  {:desc "Error message"}]]])

;; ============================================================================
;; Public roster (for update-agent's :agent-tools)
;; ============================================================================

(def update-helpers
  "Vector of all update$* helper vars in registration order. update-agent
   appends these to its :agent-tools roster so the SCI sandbox auto-binds
   them (callable as `(update$slug ...)` in a clojure fence)."
  [#'update$slug
   #'update$frontmatter
   #'update$write
   #'update$index-append
   #'update$read-record
   #'update$find
   #'update$apply])
