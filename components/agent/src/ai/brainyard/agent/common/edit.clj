;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.edit
  "Edit-agent substrate — the safe-edit transaction `edit$apply` plus the two
   deterministic read seams (`edit$read-record`, `edit$find`).

   `edit$apply` runs the full pipeline as one SCI-callable command:
   PROBE → APPLY → VERIFY → PERSIST → ROLLBACK-on-fail, returning
   `{:ok? :mode :replaced :diff :verify :rollback}`. It is MECHANISM — diffing,
   match-counting, linting, byte-restore rollback, git plumbing — exactly the
   work a model does badly and a machine does correctly, so it stays a tool (see
   docs/design/edit-agent-design.md §1-§2).

   What RETIRED (renamed from update-agent; see §9.2): the record-authoring
   helper chain `edit$slug` / `edit$frontmatter` / `edit$write` /
   `edit$index-append`. The record is just markdown — `edit$apply`'s persist
   step writes it directly from the §5 template (flow-map `apply`/`verify`), and
   the agent only relays the path. The read seams stay because parsing is where
   a machine beats the model.

   Rollback is transaction-scoped by byte-overwrite of the pre-APPLY content
   (new-file: `rm`) — no git stash, no backup artifact (§8). Records live under
   `.brainyard/agents/edit-agent/edits/`; the legacy `update-agent` dir is
   read as a fallback for one release."
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
(def ^:private edits-base ".brainyard/agents/edit-agent")
(def ^:private legacy-edits-base
  "Pre-rename location (update-agent). Read as a fallback by edit$find for one
   release; new records always write to the edit-agent dir."
  ".brainyard/agents/update-agent")
(def ^:private edits-dir-rel (str edits-base "/" edits-subdir))
(def ^:private legacy-edits-dir-rel (str legacy-edits-base "/" edits-subdir))
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

(defn- slugify
  "Deterministic kebab-case slug from request text; drops stopwords, caps at
   `max-chars`. Used internally by the persist step (the LLM-facing edit$slug
   command is retired)."
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

;; ============================================================================
;; YAML emission (private — backs the §5 record template; no LLM-facing helper)
;; ============================================================================

(defn- yaml-string
  [s]
  (str "\"" (-> (str s)
                (str/replace "\\" "\\\\")
                (str/replace "\"" "\\\""))
       "\""))

(def ^:private bareword-re #"^[A-Za-z0-9_./:-]+$")

(defn- yaml-scalar
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

(defn- yaml-flow-map
  "One-line YAML flow map `{k: v, …}` for the record's `apply` / `verify`
   blocks. Values are scalars (kept comma-free, so the lenient reader can
   split on `,`)."
  [m]
  (str "{" (str/join ", " (map (fn [[k v]] (str (name k) ": " (yaml-scalar v))) m)) "}"))

;; ============================================================================
;; YAML parsing — flat scalars + flow vectors + flow maps
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
  [s]
  (let [trimmed (str/trim s)]
    (cond
      (= trimmed "true")  true
      (= trimmed "false") false
      (= trimmed "null")  nil
      (re-matches #"-?\d+" trimmed)
      (try (Long/parseLong trimmed) (catch Exception _ trimmed))
      :else trimmed)))

(declare parse-scalar-value)

(defn- parse-flow-map
  "Parse a one-line YAML flow map `{k: v, …}` into a Clojure map so downstream
   agents get per-key access to `verify`/`apply` (e.g. `(:diff_match verify)`).
   Lenient: splits on `,` then on the first `:` per pair."
  [s]
  (let [t (str/trim (str s))]
    (if (and (str/starts-with? t "{") (str/ends-with? t "}"))
      (let [inner (str/trim (subs t 1 (dec (count t))))]
        (if (str/blank? inner)
          {}
          (into {}
                (keep (fn [pair]
                        (when-let [[_ k v] (re-matches #"\s*([\w_-]+)\s*:\s*(.*?)\s*" pair)]
                          [(keyword k) (parse-scalar-value v)]))
                      (str/split inner #",")))))
      {})))

(defn- parse-scalar-value
  "Parse the RHS of `key: value`: flow map → map; flow vector → vector;
   quoted string → unquoted; bare → coerced."
  [v]
  (let [v (str/trim v)]
    (cond
      (re-matches #"^\{.*\}$" v) (parse-flow-map v)
      (re-matches #"^\[.*\]$" v) (parse-flow-vector v)
      (and (str/starts-with? v "\"") (str/ends-with? v "\""))
      (-> v
          (subs 1 (dec (count v)))
          (str/replace "\\\"" "\"")
          (str/replace "\\\\" "\\"))
      :else (coerce-bare-value v))))

(defn- parse-record-yaml
  "Lenient parser for the §5 edit-record frontmatter: flat `key: value` lines
   where values may be scalars, quoted strings, flow vectors, or flow maps
   (`apply`/`verify`)."
  [lines]
  (loop [ls lines, acc {}]
    (if (empty? ls)
      acc
      (let [ln (first ls)]
        (if-let [[_ k v] (re-matches #"^([\w_-]+):\s*(.*)$" ln)]
          (recur (rest ls) (assoc acc (keyword k) (parse-scalar-value v)))
          (recur (rest ls) acc))))))

;; ============================================================================
;; edit$read-record
;; ============================================================================

(defcommand edit$read-record
  "Read + parse just the leading `---`/`---` YAML block of an edit record into a
   map. Cheap (~700 bytes) — downstream agents confirm an edit landed (e.g.
   `(:ok rec)`, `(:diff_match (:verify rec))`) without paying for the diff body.
   Returns `{:slug … :request … :mode … :target … :apply {…} :verify {…}
   :rollback … :ok bool}` or `{:error …}`."
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
                  [:apply    {:optional true} [:map    {:desc "Apply sub-block (flow map: replaced, bytes)"}]]
                  [:verify   {:optional true} [:map    {:desc "Verify sub-block (flow map: diff_match, old/new_count_after, lint, tests)"}]]
                  [:rollback {:optional true} [:string {:desc "Rollback shell command"}]]
                  [:ok       {:optional true} [:boolean {:desc "Whether verification passed"}]]
                  [:error    {:optional true} [:string {:desc "Error if file missing or no frontmatter"}]]])

;; ============================================================================
;; edit$find
;; ============================================================================

(defn- scan-records
  "Scan one edits dir for records whose slug+target+request matches `q`
   (lower-cased). Returns a vector of match maps (unsorted)."
  [^java.io.File dir rel-prefix q]
  (if-not (.isDirectory dir)
    []
    (->> (.listFiles dir)
         (filter (fn [^java.io.File f]
                   (and (.isFile f) (str/ends-with? (.getName f) ".md"))))
         (keep (fn [^java.io.File f]
                 (when-let [lines (read-frontmatter-lines f)]
                   (let [fm  (parse-record-yaml lines)
                         hay (str/lower-case
                              (str (:slug fm) " " (:target fm) " " (:request fm)))]
                     (when (str/includes? hay q)
                       {:path    (str rel-prefix "/" (.getName f))
                        :slug    (:slug fm)
                        :target  (:target fm)
                        :mode    (:mode fm)
                        :ok?     (boolean (:ok fm))
                        :created (:created fm)})))))
         vec)))

(defcommand edit$find
  "Search prior edit records by substring (case-insensitive) over slug+target+request; newest-first. Reads the edit-agent dir plus the legacy update-agent dir (one-release fallback)."
  (fn [& {:keys [query base-dir]
          :or   {base-dir (config/project-dir)}}]
    (cond
      (not (string? query))
      {:error ":query is required (string)"}

      :else
      (let [q       (str/lower-case query)
            matches (->> (concat
                          (scan-records (io/file base-dir edits-dir-rel) edits-dir-rel q)
                          (scan-records (io/file base-dir legacy-edits-dir-rel) legacy-edits-dir-rel q))
                         ;; Sort by FILENAME (the <ts>-<slug>.md timestamp prefix),
                         ;; not full path — so newest-first holds across both the
                         ;; edit-agent and legacy update-agent dirs.
                         (sort-by #(last (str/split (:path %) #"/")))
                         reverse
                         vec)]
        (mulog/log ::edit.find :query query :n-matches (count matches))
        {:matches matches :n-matches (count matches)})))
  :input-schema  [:map
                  [:query    [:string {:desc "Substring to match against slug + target + request (case-insensitive)"}]]
                  [:base-dir {:optional true} [:string {:desc "Working directory (default: project root)"}]]]
  :output-schema [:map
                  [:matches   [:vector {:desc "Matching records, newest-first"}
                               [:map {} :path :slug :target :mode :ok? :created]]]
                  [:n-matches [:int    {:desc "Number of matches"}]]
                  [:error     [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; edit$apply — full pipeline (PROBE → APPLY → VERIFY → PERSIST → ROLLBACK)
;; ============================================================================

(defn- shell-out
  "Run a shell command in `base-dir`. Returns {:exit :out :err}; errors captured."
  [base-dir cmd-vec]
  (try
    (apply shell/sh (concat cmd-vec [:dir base-dir]))
    (catch Throwable t
      {:exit -1 :out "" :err (str "shell-error: " (.getMessage t))})))

(defn- git-status-clean?
  [base-dir target]
  (let [{:keys [exit out]} (shell-out base-dir ["git" "status" "--porcelain" "--" target])]
    {:exit exit :out (str out)
     :clean? (and (zero? exit) (str/blank? (str out)))}))

(defn- normalize-diff
  "Strip file-header lines and per-line trailing whitespace before the V1 byte
   compare (compare HUNK content only)."
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
  [^String out]
  (count (re-seq #"(?m):\d+:\d+:" (str out))))

(defn- lint-file
  [base-dir target path]
  (when-let [[tool mk-cmd] (linter-for-ext target)]
    (when (command-v? base-dir tool)
      (let [{:keys [exit out err]} (shell-out base-dir (mk-cmd path))]
        {:tool tool :exit exit :output (str out err)
         :findings (finding-count (str out err))}))))

(defn- lint-regressed?
  "True when `after` is worse than `before` — only edit-INTRODUCED issues count."
  [before after]
  (cond
    (nil? after)  false
    (nil? before) (not (zero? (:exit after)))
    :else         (or (> (:exit after) (:exit before))
                      (> (:findings after) (:findings before)))))

(defn- infer-component
  [target]
  (second (re-find #"(?:^|/)components/([^/]+)/" (str target))))

(defn- run-component-tests!
  [base-dir component]
  (let [{:keys [exit out err]} (shell-out base-dir ["bb" "test:component" component])]
    {:component component :exit exit :output (str out err)}))

(defn- rollback-tracked
  "Restore `target` to its pre-APPLY bytes (`original`, captured at PROBE).
   Byte-overwrite is transaction-scoped: only THIS edit is undone, so prior
   uncommitted changes survive (§8). No git, no backup artifact."
  [base-dir target original]
  (let [f (if (.isAbsolute (io/file target))
            (io/file target)
            (io/file base-dir target))]
    (try
      (spit f (or original ""))
      {:ok? true :output (str "restored " (count (or original "")) " bytes to " target)}
      (catch Throwable t
        {:ok? false :output (str "restore failed: " (ex-message t))}))))

(defn- rollback-fresh
  [base-dir target]
  (let [f (if (.isAbsolute (io/file target))
            (io/file target)
            (io/file base-dir target))
        deleted? (try (.delete f) (catch Throwable _ false))]
    {:ok? (boolean deleted?) :output (str "rm: " target " " (if deleted? "ok" "failed"))}))

(defn- resolve-target-file
  "Resolve target under base-dir to an absolute File, refusing traversal /
   .git / out-of-tree. Returns {:file …} or {:error …}."
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
  "Unified diff between `old-content` and the current contents of `new-file`."
  [old-content ^java.io.File new-file]
  (let [old-tmp (doto (java.io.File/createTempFile "edit-old-" ".txt")
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
  "Pattern→replacement against `<base-dir>/<target>`. Canonicalized + refused
   for `.git/` / out-of-tree. Returns {:path :replaced :diff :diff-source} or
   {:error …}."
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
  "New-file write. Refuses if the file already exists."
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

(defn- pipeline-error
  [stage msg]
  {:ok? false :stage stage :error msg})

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
    (if (zero? n) base-slug (str base-slug "-" (inc n)))))

(defn- record-index-line
  [{:keys [path slug mode target ok? summary]}]
  (let [filename    (-> path (str/split #"/") last)
        target-base (-> (str target) (str/split #"/") last)
        mark        (if ok? "✅" "❌")
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

(defn- render-record
  "Fill the §5 edit-record template (frontmatter + ## Diff). Returns full
   markdown ready to spit. `apply`/`verify` are emitted as one-line flow maps;
   the LLM-facing frontmatter helpers are retired."
  [{:keys [request slug mode target apply-map verify rollback ok summary diff]}]
  (str "---\n"
       "slug: " slug "\n"
       "agent: edit-agent\n"
       "created: " (now-iso) "\n"
       "request: " (yaml-string request) "\n"
       "target: " (yaml-scalar target) "\n"
       "mode: " (yaml-scalar mode) "\n"
       "ok: " (yaml-scalar (boolean ok)) "\n"
       "\n"
       "apply: " (yaml-flow-map (or apply-map {})) "\n"
       "verify: " (yaml-flow-map (or verify {})) "\n"
       "rollback: " (yaml-string rollback) "\n"
       "---\n\n"
       "# Edit — " (if (str/blank? (str summary)) request summary) "\n\n"
       "## Diff\n```diff\n" (or diff "") "\n```\n"))

(defn- persist-record!
  [{:keys [base-dir request slug mode target apply-map verify rollback ok summary diff]}]
  (let [final-slug (final-slug-with-suffix base-dir slug)
        ts         (now-ts)
        rel-path   (str edits-dir-rel "/" ts "-" final-slug ".md")
        file       (io/file base-dir rel-path)
        content    (render-record {:request request :slug final-slug :mode mode
                                   :target target :apply-map apply-map :verify verify
                                   :rollback rollback :ok ok :summary summary :diff diff})]
    (.mkdirs (.getParentFile file))
    (spit file content)
    (mulog/log ::edit.persist :slug final-slug :path rel-path :ok? (boolean ok) :mode (str mode))
    {:path (.getAbsolutePath file) :slug final-slug :ts ts}))

(defn- run-pipeline
  [{:keys [request target mode pattern replacement regex? all? content
           dirty-ok? lint-ok-to-fail? run-tests? base-dir]}]
  (let [request   (str request)
        slug      (slugify request 60)
        ;; ---------------- PROBE ----------------
        existed?  (file-exists? base-dir target)
        status    (git-status-clean? base-dir target)
        original  (when existed? (read-target-content base-dir target))
        n-matches (when (and (= :pattern mode) (some? original))
                    (count-pattern-matches original pattern regex?))
        expected  (cond
                    (not= :pattern mode) nil
                    all? n-matches
                    :else 1)]
    (cond
      ;; Out-of-tree refusal
      (or (str/blank? target)
          (str/includes? (str target) "..")
          (str/includes? (str target) ".git/"))
      (pipeline-error :probe (str "Refusing target outside project tree: " target))

      ;; P1 — dirty file refusal (byte-overwrite rollback keeps prior dirty
      ;; hunks, so :dirty-ok? is safe for legitimate layered edits)
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
      (let [;; V4 baseline — lint the REAL file BEFORE the edit.
            lint-before (when (not= :new-file mode)
                          (lint-file base-dir target target))

            ;; ---------------- APPLY ----------------
            apply-result
            (case mode
              :new-file (direct-write-file base-dir target (or content ""))
              (direct-update-file base-dir target pattern replacement regex? all?))
            apply-err (or (:error apply-result) (:error-message apply-result))]
        (if apply-err
          (pipeline-error :apply (str "Apply failed: " apply-err))
          (let [diff     (or (:diff apply-result) "")
                replaced (or (:replaced apply-result) 0)
                rollback-cmd (if (= :new-file mode)
                               (str "rm -- " target)
                               (str "git checkout -- " target))

                ;; ---------------- VERIFY ----------------
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
                lint-after  (lint-file base-dir target target)
                lint-fail?  (lint-regressed? lint-before lint-after)
                v2-ok?      (or (not= :pattern mode)
                                (if all?
                                  (zero? (or old-after 0))
                                  (= (max 0 (dec (or n-matches 0))) (or old-after 0))))
                v3-ok?      (or (not= :pattern mode) (>= (or new-after 0) 1))
                pre-test-ok? (and diff-match? v2-ok? v3-ok?
                                  (or (not lint-fail?) lint-ok-to-fail?))
                component    (when run-tests? (infer-component target))
                test-result  (when (and run-tests? pre-test-ok? component)
                               (run-component-tests! base-dir component))
                tests-fail?  (boolean (and test-result (not (zero? (:exit test-result)))))
                ok?          (and pre-test-ok? (not tests-fail?))

                apply-map   (cond-> {:replaced replaced}
                              (= :new-file mode) (assoc :bytes (count (or content ""))))
                verify-map  (cond-> {:diff_match (boolean diff-match?)}
                              (= :pattern mode) (merge {:old_count_after (or old-after 0)
                                                        :new_count_after (or new-after 0)})
                              lint-after        (assoc :lint
                                                       (str (:tool lint-after) ":" (:exit lint-after)
                                                            " (findings "
                                                            (if lint-before (:findings lint-before) "n/a")
                                                            "->" (:findings lint-after) ")"))
                              (nil? lint-after) (assoc :lint "skipped")
                              true              (assoc :tests
                                                       (cond
                                                         (not run-tests?)   "skipped"
                                                         (not pre-test-ok?) "skipped (verify failed)"
                                                         (nil? component)   (str "skipped (no component: " target ")")
                                                         test-result        (str "bb test:component " component ":" (:exit test-result))
                                                         :else              "skipped")))

                ;; ---------------- ROLLBACK on fail ----------------
                ;; Byte-overwrite of pre-APPLY `original` (tracked) / rm (new):
                ;; transaction-scoped, no stash, no backup artifact.
                rb (when-not ok?
                     (if (= :new-file mode)
                       (rollback-fresh base-dir target)
                       (rollback-tracked base-dir target original)))

                summary (cond
                          (and ok? (= :new-file mode)) (str (count (or content "")) " bytes written")
                          ok?                          (str replaced " replaced")
                          (and rb (:ok? rb))           "rolled back (verify failed)"
                          :else                        "rollback failed; workspace UNKNOWN — manual intervention required")

                rec (persist-record! {:base-dir base-dir :request request :slug slug
                                      :mode (name mode) :target target
                                      :apply-map apply-map :verify verify-map
                                      :rollback rollback-cmd :ok ok? :summary summary
                                      :diff diff})
                _ (try
                    (spit (io/file base-dir index-rel)
                          (str (record-index-line {:path (:path rec) :slug (:slug rec)
                                                   :mode mode :target target
                                                   :ok? ok? :summary summary})
                               (if (.isFile (io/file base-dir index-rel))
                                 (slurp (io/file base-dir index-rel))
                                 "")))
                    (catch Throwable t
                      (mulog/error ::edit.index-failed :exception t)))]
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
                     :rollback-output (:output rb)))))))))

(defcommand edit$apply
  "Run the full safe-edit pipeline: PROBE → APPLY → VERIFY → PERSIST → ROLLBACK-on-fail. Mode: :pattern (needs :pattern+:replacement), :syntax (whole-region replace), or :new-file (needs :content). Rollback is byte-overwrite (transaction-scoped; prior dirty hunks survive)."
  (fn [& {:keys [request target mode pattern replacement regex? all? content
                 dirty-ok? run-tests? lint-ok-to-fail? base-dir]
          :or   {base-dir (config/project-dir)
                 mode :pattern
                 all? false
                 regex? false
                 dirty-ok? false
                 lint-ok-to-fail? false
                 run-tests? false}}]
    (let [mode-kw   (cond
                      (keyword? mode) mode
                      (string? mode)  (keyword mode)
                      :else           :pattern)
          ;; dirty-ok? accepts boolean or the strings "true"/"false".
          dirty-ok? (cond
                      (boolean? dirty-ok?) dirty-ok?
                      (string? dirty-ok?)  (= "true" (str/lower-case dirty-ok?))
                      :else                false)
          opts {:request request :target target :mode mode-kw
                :pattern pattern :replacement replacement
                :regex? regex? :all? all? :content content
                :dirty-ok? dirty-ok? :run-tests? run-tests?
                :lint-ok-to-fail? lint-ok-to-fail? :base-dir base-dir}]
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
            (mulog/log ::edit.apply
                       :target target :mode (str mode-kw)
                       :ok? (boolean (:ok? result))
                       :stage (or (:stage result) :complete))
            result)
          (catch Throwable t
            (mulog/error ::edit.apply-exception
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
                  [:dirty-ok?        {:optional true} [:string  {:desc "false (default) | true. When false, refuses a dirty target. Rollback is byte-overwrite, so :dirty-ok? true safely allows layered edits."}]]
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
                  [:diff        {:optional true} [:string  {:desc "Unified diff"}]]
                  [:verify      {:optional true} [:map     {:desc "Verify outcomes"}]]
                  [:rollback    {:optional true} [:string  {:desc "Shell command to undo this edit (only set when :ok? true)"}]]
                  [:rolled-back {:optional true} [:boolean {:desc "Whether rollback succeeded (only set when :ok? false)"}]]
                  [:stage       {:optional true} [:string  {:desc "Stage that failed: :probe | :apply | :verify | :exception"}]]
                  [:error       {:optional true} [:string  {:desc "Error message"}]]])

;; ============================================================================
;; Public roster (for edit-agent's :agent-tools)
;; ============================================================================

(def edit-helpers
  "The surviving edit$* commands — the transaction (mechanism) + the two read
   seams. edit-agent appends these to its :agent-tools so the SCI sandbox
   auto-binds them (callable as `(edit$apply ...)`). The record-authoring helper
   chain is retired (the persist step writes the §5 template directly)."
  [#'edit$apply
   #'edit$read-record
   #'edit$find])
