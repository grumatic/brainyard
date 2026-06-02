;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.reference
  "Reference resolution for @-prefix paths, URLs, and glob patterns.
   Used as sandbox bindings in the RLM agent."
  (:require [ai.brainyard.agent.core.config :as config]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.net URL HttpURLConnection]))

(def ^:private max-content-chars (* 100 1024))

(defn- truncate-with-warning
  "Truncate content with a plain message indicating the limit.
   Instructs LLM to use :lines or :offset/:limit for full content."
  [content source]
  (if (<= (count content) max-content-chars)
    content
    (let [total-lines (count (str/split-lines content))
          head-len (long (* max-content-chars 0.8))
          tail-len (long (* max-content-chars 0.1))]
      (str (subs content 0 head-len)
           "\n\n--- TRUNCATED (showing " head-len "/" (count content) " chars, "
           total-lines " total lines) from " source " ---"
           "\n--- Use (read-file \"" source "\" :lines [1 100]) to read in line ranges ---\n\n"
           (subs content (- (count content) tail-len))))))

(defn- strip-at-prefix [s]
  (if (str/starts-with? s "@") (subs s 1) s))

(defn- ^java.io.File resolve-path
  "Resolve path relative to base-dir. Returns nil if outside base-dir (traversal blocked)."
  [base-dir path]
  (let [^java.io.File base (-> (io/file base-dir) .getCanonicalFile)
        ^java.io.File target (-> (io/file base-dir path) .getCanonicalFile)]
    (when (str/starts-with? (.getPath target) (.getPath base))
      target)))

(defn- resolve-allowed-path
  "Resolve an absolute path if it falls within any of the allowed directories.
   Returns the canonical File, or nil if not within any allowed dir."
  [allowed-dirs path]
  (let [target (-> (io/file path) .getCanonicalFile)]
    (when (some (fn [dir]
                  (str/starts-with? (.getPath target)
                                    (.getPath (-> (io/file dir) .getCanonicalFile))))
                allowed-dirs)
      target)))

(defn- ^java.io.File resolve-relative
  "Resolve a relative path against the first base in `bases` under which it
   exists (traversal outside each base is blocked). If it exists under none,
   returns the resolution against the FIRST base so the caller can render a
   stable 'not found' message. Returns nil only when every base rejects the
   path as traversal."
  [bases path]
  (let [cands (keep #(resolve-path % path) (distinct bases))]
    (or (some (fn [^java.io.File f] (when (.exists f) f)) cands)
        (first cands))))

(defn- coerce-int
  "Coerce n to a long. Accepts integers and numeric strings (the LLM sometimes
   sends `\"12\"` for an int arg). Returns nil when absent or not coercible."
  [n]
  (cond
    (integer? n) (long n)
    (and (string? n) (re-matches #"\s*-?\d+\s*" n)) (Long/parseLong (str/trim n))
    :else nil))

(defn- coerce-lines
  "Coerce a :lines arg into `[start end]` longs.
   Returns nil when absent, the `[start end]` vector when coercible, or
   `{:error msg}` when present-but-malformed. Accepts a 2-element int vector
   AND the stringified forms the LLM frequently emits for a vector arg —
   `\"[1, 12]\"`, `\"1 12\"`, `\"1,12\"` — instead of erroring on them."
  [lines]
  (cond
    (nil? lines) nil
    (sequential? lines)
    (let [[a b] (map coerce-int lines)]
      (if (and (= 2 (count lines)) a b)
        [a b]
        {:error (str "Invalid :lines — expected [start end] integers, got " (pr-str lines))}))
    (string? lines)
    (let [nums (mapv #(Long/parseLong %) (re-seq #"-?\d+" lines))]
      (if (= 2 (count nums))
        nums
        {:error (str "Invalid :lines — expected two integers like [1 12], got " (pr-str lines))}))
    :else
    {:error (str "Invalid :lines — expected [start end] integers, got " (pr-str lines))}))

(defn- read-file-partial
  "Read a slice of a file by char offset+limit or line range.
   Skips truncation — the caller explicitly asked for a partial read."
  [^java.io.File file offset limit lines]
  (let [full-content (slurp file)
        total-chars (count full-content)
        all-lines (str/split-lines full-content)
        total-lines (count all-lines)]
    (if lines
      ;; Line-based reading: lines is [start end] (1-based inclusive)
      (let [[start end] lines
            start-idx (max 0 (dec (long start)))
            end-idx (min total-lines (long end))
            selected (subvec (vec all-lines) start-idx end-idx)
            content (str/join "\n" selected)]
        {:content content
         :path (.getPath file)
         :size total-chars
         :total-lines total-lines
         :lines-range [(inc start-idx) end-idx]
         :has-more (< end-idx total-lines)})
      ;; Char offset+limit reading
      (let [off (long (or offset 0))
            lim (long (or limit 5000))
            end-pos (min total-chars (+ off lim))
            content (subs full-content off end-pos)]
        {:content content
         :path (.getPath file)
         :size total-chars
         :total-lines total-lines
         :offset off
         :limit lim
         :has-more (< end-pos total-chars)}))))

(defn read-file-content
  "Read a file, returning {:content str :path str :size int} or {:error str}.

   A relative path is resolved against base-dir first, then each entry of
   `fallback-dirs` in order — the first base under which the file exists wins
   (traversal outside each base is blocked). base-dir is the primary so tool
   reads stay in lockstep with the bash tool (both anchor at project-dir);
   the fallbacks let a path that lives in a sibling tree (the bb tui
   launcher's cwd) or under ~/.brainyard resolve instead of erroring. A
   relative path that resolves OUTSIDE base-dir is still gated through
   allowed-dirs / permission-fn, exactly like an absolute path — the fallback
   widens discovery, not the security envelope.

   Absolute paths within base-dir or allowed-dirs (default [\"/tmp\"]) are
   auto-allowed; other absolute paths call permission-fn if provided.

   `:lines` accepts `[start end]` or the stringified forms an LLM tends to
   emit (`\"[1, 12]\"`); a malformed `:lines` returns a precise {:error …}
   instead of the old blank failure."
  [base-dir path & {:keys [allowed-dirs permission-fn fallback-dirs offset limit lines]
                    :or {allowed-dirs (config/default-allowed-dirs)}}]
  (try
    (let [lines* (coerce-lines lines)]
      (if (:error lines*)
        lines*
        (let [offset*    (coerce-int offset)
              limit*     (coerce-int limit)
              clean-path (strip-at-prefix path)
              absolute?  (str/starts-with? clean-path "/")
              ;; Resolve a relative path to an absolute candidate (first
              ;; existing across base-dir + fallback-dirs); absolutes pass
              ;; through. From here the allow-gate is identical for both.
              abs-path   (if absolute?
                           clean-path
                           (some-> (resolve-relative (cons base-dir (or fallback-dirs []))
                                                     clean-path)
                                   .getPath))
              within-base? (when abs-path
                             (let [canon      (-> (io/file abs-path) .getCanonicalFile .getPath)
                                   base-canon (-> (io/file base-dir) .getCanonicalFile .getPath)]
                               (str/starts-with? canon base-canon)))
              file (cond
                     ;; Traversal-blocked on every base → no candidate.
                     (nil? abs-path) nil

                     ;; Within base-dir (incl. the working-dir subtree): auto-allow.
                     within-base?
                     (-> (io/file abs-path) .getCanonicalFile)

                     ;; Within an allowed dir (e.g. /tmp, ~/.brainyard): auto-allow.
                     (resolve-allowed-path allowed-dirs abs-path)
                     (resolve-allowed-path allowed-dirs abs-path)

                     ;; Outside both: ask permission.
                     permission-fn
                     (let [resp (permission-fn {:type :file-access :path abs-path :action :read})]
                       (when (:allowed resp)
                         (-> (io/file abs-path) .getCanonicalFile)))

                     :else nil)]
          (let [^java.io.File file file]
            (if (and file (.exists file) (.isFile file))
              (if (or offset* limit* lines*)
                ;; Partial read — no truncation
                (read-file-partial file offset* limit* lines*)
                ;; Full read — existing behavior with truncation
                (let [content (slurp file)]
                  {:content (truncate-with-warning content (.getPath file))
                   :path (.getPath file)
                   :size (count content)}))
              {:error (if file
                        (str "File not found: " clean-path)
                        (str "Access denied — outside allowed directories: " clean-path))})))))
    (catch Exception e
      {:error (str "Failed to read " path ": "
                   (or (.getMessage e) (.. e getClass getSimpleName)))})))

(defn read-url-content
  "Fetch a URL, returning {:content str :url str :size int} or {:error str}."
  [url]
  (try
    (let [clean-url (strip-at-prefix url)
          content (slurp clean-url)]
      {:content (truncate-with-warning content clean-url)
       :url clean-url
       :size (count content)})
    (catch Exception e
      {:error (str "Failed to fetch URL: " (.getMessage e))})))

(defn read-glob-content
  "Glob for files relative to base-dir or within allowed directories.
   Returns {:files [{:path :content :size}] :count int} or {:error str}.
   Limits to 20 files max."
  [base-dir pattern & {:keys [allowed-dirs permission-fn]
                       :or {allowed-dirs (config/default-allowed-dirs)}}]
  (try
    (let [clean-pattern (strip-at-prefix pattern)
          absolute? (str/starts-with? clean-pattern "/")
          ;; For absolute glob patterns, extract base directory from pattern
          glob-base (when absolute?
                      (let [;; Find the first glob metacharacter
                            meta-idx (reduce (fn [acc c]
                                               (if (or (= c \*) (= c \?) (= c \{) (= c \[))
                                                 (reduced acc)
                                                 (inc acc)))
                                             0 clean-pattern)
                            prefix (subs clean-pattern 0 meta-idx)
                            dir-end (str/last-index-of prefix "/")]
                        (when dir-end (subs prefix 0 dir-end))))
          ;; Determine if this absolute glob is allowed
          allowed-base (when (and absolute? glob-base)
                         (cond
                           (resolve-allowed-path allowed-dirs glob-base) glob-base
                           permission-fn
                           (let [resp (permission-fn {:type :file-access :path glob-base :action :read})]
                             (when (:allowed resp) glob-base))
                           :else nil))
          ;; Use absolute base or default base-dir
          effective-base (if (and absolute? allowed-base)
                           (io/file allowed-base)
                           (io/file base-dir))
          ;; Adjust pattern for absolute paths
          effective-pattern (if (and absolute? allowed-base)
                              (subs clean-pattern (inc (count allowed-base)))
                              clean-pattern)
          matcher (.getPathMatcher (java.nio.file.FileSystems/getDefault)
                                   (str "glob:" effective-pattern))
          base-path (.toPath effective-base)]
      (if (and absolute? (not allowed-base))
        {:error (str "Glob base directory not allowed: " (or glob-base clean-pattern))}
        (let [matches (->> (file-seq effective-base)
                           (filter #(.isFile ^java.io.File %))
                           (filter #(.matches matcher (.relativize base-path (.toPath ^java.io.File %))))
                           (take 20)
                           vec)]
          (if (empty? matches)
            {:error (str "No files matched pattern: " clean-pattern)
             :pattern clean-pattern}
            {:files (mapv (fn [^java.io.File f]
                            (let [rel (str (.relativize base-path (.toPath f)))
                                  display-path (if absolute?
                                                 (.getPath f)
                                                 rel)
                                  content (slurp f)]
                              {:path display-path
                               :content (truncate-with-warning content display-path)
                               :size (count content)}))
                          matches)
             :count (count matches)
             :pattern clean-pattern}))))
    (catch Exception e
      {:error (str "Glob failed: " (.getMessage e))})))

(defn list-files
  "List files matching a glob pattern, returning metadata only (no content).
   Options:
     :max-results - Maximum files to return (default 100)
   Returns: {:files [{:path str :size int}] :count int :pattern str}"
  [base-dir allowed-dirs pattern & {:keys [max-results] :or {max-results 100}}]
  (try
    (let [clean-pattern (strip-at-prefix pattern)
          absolute? (str/starts-with? clean-pattern "/")
          glob-base (when absolute?
                      (let [meta-idx (reduce (fn [acc c]
                                               (if (or (= c \*) (= c \?) (= c \{) (= c \[))
                                                 (reduced acc)
                                                 (inc acc)))
                                             0 clean-pattern)
                            prefix (subs clean-pattern 0 meta-idx)
                            dir-end (str/last-index-of prefix "/")]
                        (when dir-end (subs prefix 0 dir-end))))
          allowed-base (when (and absolute? glob-base)
                         (when (resolve-allowed-path allowed-dirs glob-base) glob-base))
          effective-base (if (and absolute? allowed-base)
                           (io/file allowed-base)
                           (io/file base-dir))
          effective-pattern (if (and absolute? allowed-base)
                              (subs clean-pattern (inc (count allowed-base)))
                              clean-pattern)
          matcher (.getPathMatcher (java.nio.file.FileSystems/getDefault)
                                   (str "glob:" effective-pattern))
          base-path (.toPath effective-base)]
      (if (and absolute? (not allowed-base))
        {:error (str "Glob base directory not allowed: " (or glob-base clean-pattern))}
        (let [matches (->> (file-seq effective-base)
                           (filter #(.isFile ^java.io.File %))
                           (filter #(.matches matcher (.relativize base-path (.toPath ^java.io.File %))))
                           (take max-results)
                           vec)]
          {:files (mapv (fn [^java.io.File f]
                          (let [rel (str (.relativize base-path (.toPath f)))
                                display-path (if absolute? (.getPath f) rel)]
                            {:path display-path
                             :size (.length f)}))
                        matches)
           :count (count matches)
           :pattern clean-pattern})))
    (catch Exception e
      {:error (str "list-files failed: " (.getMessage e))})))

(defn read-files-batch
  "Read multiple files in a single call.
   Each entry in paths is either a string or {:path str :lines [start end]}.
   Options:
     :max-chars-total - Total character budget across all files (default 200000)
   Returns: {:files [{:path str :content str :size int :error str}] :count int}"
  [base-dir allowed-dirs permission-fn paths & {:keys [max-chars-total]
                                                :or {max-chars-total 200000}}]
  (try
    (let [!budget (atom max-chars-total)
          results (mapv (fn [entry]
                          (let [[path lines] (if (map? entry)
                                               [(:path entry) (:lines entry)]
                                               [entry nil])]
                            (if (<= @!budget 0)
                              {:path path :error "Character budget exhausted"}
                              (let [result (if lines
                                             (read-file-content base-dir path
                                                                :allowed-dirs allowed-dirs
                                                                :permission-fn permission-fn
                                                                :lines lines)
                                             (read-file-content base-dir path
                                                                :allowed-dirs allowed-dirs
                                                                :permission-fn permission-fn))
                                    content-len (count (or (:content result) ""))]
                                (swap! !budget - content-len)
                                (merge {:path path} (select-keys result [:content :size :error]))))))
                        paths)]
      {:files results :count (count results)})
    (catch Exception e
      {:error (str "read-files-batch failed: " (.getMessage e))})))

;; ============================================================================
;; File Writing
;; ============================================================================

(defn- validate-write-path
  "Validate that a path is within an allowed write directory.
   Allowed: /tmp, /private/tmp, <base-dir>/.brainyard/
   Returns canonical File or {:error str}."
  [base-dir path]
  (let [;; Resolve path — relative paths resolve against base-dir
        raw-file (if (str/starts-with? (str path) "/")
                   (java.io.File. (str path))
                   (java.io.File. (str base-dir) (str path)))
        f (.getCanonicalFile raw-file)
        p (.getPath f)
        brainyard-dir (.getPath (.getCanonicalFile (java.io.File. (str base-dir) ".brainyard")))]
    (cond
      (or (str/starts-with? p "/tmp")
          (str/starts-with? p "/private/tmp"))
      f

      (str/starts-with? p (str brainyard-dir "/"))
      f

      (= p brainyard-dir)
      {:error "Cannot write to .brainyard/ directory itself — specify a file within it"}

      :else
      {:error (str "File write denied: only /tmp and .brainyard/ paths allowed, got: " p)})))

(defn write-project-file
  "Write a file to an allowed project directory.
   Allowed targets: /tmp/*, <base-dir>/.brainyard/*
   For other paths, permission-fn is called if provided.
   Content is written via spit. Parent directories are auto-created.
   Returns: {:path str :chars int} or {:error str}"
  [base-dir path content & {:keys [append? permission-fn]}]
  (try
    (let [result (validate-write-path base-dir path)]
      (if (instance? java.io.File result)
        ;; Path is in whitelist — proceed
        (let [^java.io.File f result]
          (.mkdirs (.getParentFile f))
          (clojure.core/spit f (str content) :append (boolean append?))
          {:path (.getPath f)
           :chars (count (str content))})
        ;; Path denied by whitelist — try permission-fn
        (let [clean-path (str path)
              abs-path (-> (if (str/starts-with? clean-path "/")
                             (java.io.File. clean-path)
                             (java.io.File. (str base-dir) clean-path))
                           .getCanonicalFile .getPath)]
          (if (and permission-fn
                   (:allowed (permission-fn {:type :file-access :path abs-path :action :write})))
            (let [f (.getCanonicalFile (if (str/starts-with? clean-path "/")
                                         (java.io.File. clean-path)
                                         (java.io.File. (str base-dir) clean-path)))]
              (.mkdirs (.getParentFile f))
              (clojure.core/spit f (str content) :append (boolean append?))
              {:path (.getPath f)
               :chars (count (str content))})
            result))))
    (catch Exception e
      {:error (str "write-project-file failed: " (.getMessage e))})))

;; ============================================================================
;; Grep (Content Search)
;; ============================================================================

(defn grep-files
  "Search file contents by regex pattern using grep -rn or rg.
   Options:
     :max-results  - Maximum matches (default 50)
     :include-exts - File extensions to include, e.g. [\".clj\" \".md\"]
     :recursive    - Recursive search (default true)
   Returns: {:matches [{:file str :line int :text str}] :count int} or {:error str}"
  [base-dir allowed-dirs pattern path & {:keys [max-results include-exts recursive]
                                         :or {max-results 50 recursive true}}]
  (try
    (let [;; Resolve search path
          abs? (str/starts-with? path "/")
          search-dir (if abs?
                       (when (resolve-allowed-path allowed-dirs path) path)
                       (.getPath (resolve-path base-dir path)))
          _ (when-not search-dir
              (throw (ex-info "Search path not allowed" {:path path})))
          ;; Try rg first, fall back to grep
          use-rg? (try (let [p (.start (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["which" "rg"])))]
                         (zero? (.waitFor p)))
                       (catch Exception _ false))
          ;; Build command
          cmd (if use-rg?
                (cond-> ["rg" "-n" "--no-heading" "-M" "500"]
                  (not recursive) (conj "--no-recursive")
                  include-exts (into (mapcat (fn [ext]
                                               ["-g" (str "*" ext)])
                                             include-exts))
                  true (conj pattern search-dir))
                (cond-> ["grep" "-rn"]
                  (not recursive) (-> (pop) (conj "-n"))
                  include-exts (conj (str "--include="
                                          (str/join " --include="
                                                    (map #(str "*" %) include-exts))))
                  true (conj pattern search-dir)))
          proc (.start (ProcessBuilder. ^java.util.List cmd))
          output (slurp (.getInputStream proc))
          _ (.waitFor proc)
          lines (remove str/blank? (str/split-lines output))
          matches (->> lines
                       (take max-results)
                       (mapv (fn [line]
                               (let [parts (str/split line #":" 3)]
                                 (if (>= (count parts) 3)
                                   {:file (first parts)
                                    :line (try (Integer/parseInt (second parts)) (catch Exception _ 0))
                                    :text (nth parts 2)}
                                   {:file "" :line 0 :text line})))))]
      {:matches matches
       :count (count matches)
       :total-matches (count lines)
       :pattern pattern
       :path search-dir})
    (catch Exception e
      {:error (str "grep failed: " (.getMessage e))})))

;; ============================================================================
;; URL Fetching
;; ============================================================================

(defn fetch-url
  "Fetch content from a URL using Java HTTP.
   Options:
     :timeout    - Connection timeout ms (default 10000)
     :max-chars  - Maximum response chars (default 100000)
     :headers    - Map of request headers
   Returns: {:url str :status int :body str :content-type str :size int :truncated bool} or {:error str}"
  [url & {:keys [timeout max-chars headers]
          :or {timeout 10000 max-chars 100000}}]
  (try
    (let [conn (doto ^HttpURLConnection (.openConnection (URL. url))
                 (.setRequestMethod "GET")
                 (.setConnectTimeout (int timeout))
                 (.setReadTimeout (int timeout))
                 (.setInstanceFollowRedirects true))
          _ (doseq [[k v] headers]
              (.setRequestProperty conn (name k) (str v)))
          status (.getResponseCode conn)
          content-type (.getContentType conn)
          stream (if (< status 400)
                   (.getInputStream conn)
                   (.getErrorStream conn))
          content (when stream
                    (with-open [rdr (BufferedReader. (InputStreamReader. stream "UTF-8"))]
                      (let [sb (StringBuilder.)
                            buf (char-array 8192)]
                        (loop [total 0]
                          (let [n (.read rdr buf)]
                            (if (or (neg? n) (>= total max-chars))
                              (.toString sb)
                              (do (.append sb buf 0 n)
                                  (recur (+ total n)))))))))
          size (count (or content ""))]
      {:url url
       :status status
       :body (or content "")
       :content-type (or content-type "unknown")
       :size size
       :truncated (>= size max-chars)})
    (catch Exception e
      {:error (str "fetch-url failed: " (.getMessage e))})))

;; ============================================================================
;; Web Search (Tavily)
;; ============================================================================

(def ^:private tavily-endpoint "https://api.tavily.com/search")

(defn- post-json
  "POST a JSON body and parse the JSON response. Returns parsed map or throws."
  [^String url payload timeout]
  (let [conn (doto ^HttpURLConnection (.openConnection (URL. url))
               (.setRequestMethod "POST")
               (.setConnectTimeout (int timeout))
               (.setReadTimeout (int timeout))
               (.setDoOutput true)
               (.setRequestProperty "Content-Type" "application/json")
               (.setRequestProperty "Accept" "application/json"))
        body (json/write-str payload)]
    (with-open [w (OutputStreamWriter. (.getOutputStream conn) "UTF-8")]
      (.write w body))
    (let [status (.getResponseCode conn)
          stream (if (< status 400) (.getInputStream conn) (.getErrorStream conn))
          text (when stream
                 (with-open [rdr (BufferedReader. (InputStreamReader. stream "UTF-8"))]
                   (slurp rdr)))]
      {:status status
       :body (try (when text (json/read-str text :key-fn keyword))
                  (catch Exception _ {:raw text}))})))

(defn tavily-search
  "POST to Tavily /search. Returns {:answer :results [{:title :url :content :score}]}
   or {:error msg}. API key from :api-key opt.

   Options:
     :api-key         - Tavily API key (required)
     :max-results     - Max results (default 5)
     :search-depth    - \"basic\" (default) or \"advanced\"
     :include-answer? - Include Tavily-generated answer (default true)
     :timeout         - Request timeout ms (default 15000)"
  [query & {:keys [api-key max-results search-depth include-answer? timeout]
            :or {max-results 5 search-depth "basic" include-answer? true timeout 15000}}]
  (cond
    (str/blank? (str api-key)) {:error "tavily-search: :api-key is required"}
    (str/blank? (str query))   {:error "tavily-search: :query is required"}
    :else
    (try
      (let [payload {:api_key api-key
                     :query query
                     :max_results max-results
                     :search_depth search-depth
                     :include_answer include-answer?}
            {:keys [status body]} (post-json tavily-endpoint payload timeout)]
        (if (>= status 400)
          {:error (str "tavily-search HTTP " status ": "
                       (or (:error body) (:message body) (pr-str body)))}
          {:answer  (:answer body)
           :query   (:query body query)
           :results (mapv (fn [r]
                            {:title   (:title r)
                             :url     (:url r)
                             :content (:content r)
                             :score   (:score r)})
                          (:results body))}))
      (catch Exception e
        {:error (str "tavily-search failed: " (.getMessage e))}))))
