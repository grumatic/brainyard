;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.init
  "init-agent helpers: read, detect-sources, diff, snapshot, list, revert,
   apply, smoke-test + dossier writer.

   See docs/design/init-agent-design.md.

   This namespace owns the mechanical surface for BRAINYARD.md authoring at
   both project (`<project>/.brainyard/BRAINYARD.md`) and user
   (`~/.brainyard/BRAINYARD.md`) scopes. The init-agent's CoAct loop drives
   the LLM-shaped parts (source summarisation, section classification,
   curation prompts); this namespace just validates, snapshots, writes, and
   appends dossiers.

   `agent.core.config/load-brainyard-instructions` is intentionally unchanged
   — init-agent only changes what's *in* the BRAINYARD.md files, not who
   reads them."
  (:require [ai.brainyard.agent.core.config :as core-config]
            [ai.brainyard.agent.core.tool :as tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import (java.io File)
           (java.text SimpleDateFormat)
           (java.util Date)))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private size-hard-cap
  "Hard ceiling on BRAINYARD.md size. Writes that would exceed this refuse
   and propose `:op :curate`."
  8192)

(def ^:private size-soft-target-project 4096)
(def ^:private size-soft-target-user    2048)

(def ^:private snapshot-retention
  "Keep this many snapshots per scope. Older ones rotate out."
  20)

(def ^:private auto-append-threshold
  "In --auto / :auto? mode, :op :append writes <= this many added chars pass
   without :confirm? true. Larger writes refuse with
   :auto-confirmation-required."
  200)

(def ^:private known-sections
  "§4.3 — the recognised top-level sections. Case-insensitive on read. Init
   never auto-edits `Notes`; unknown sections are preserved as-is."
  ["Overview" "Build & Run" "Conventions" "Architecture"
   "House Rules" "Tooling" "Glossary" "Notes"])

(def ^:private brainyard-filename "BRAINYARD.md")
(def ^:private artifacts-subdir   "agents/init-agent")
(def ^:private snapshots-subdir   "snapshots")
(def ^:private dossiers-subdir    "dossiers")
(def ^:private index-rel          (str artifacts-subdir "/INDEX.md"))

(def ^:private sources-edn-resource "init-agent/sources.edn")

;; ============================================================================
;; Path resolution — scope-aware
;; ============================================================================

(defn- valid-scope? [s]
  (contains? #{:project :user} s))

(defn resolve-dirs
  "Resolve the dirs map, honoring an optional :project-dir override (for
   tests). When override is supplied, the .brainyard/ subdir is ensured."
  ([]              (resolve-dirs nil))
  ([project-dir]
   (if project-dir
     (let [d {:user-dir    (System/getProperty "user.home")
              :project-dir project-dir
              :working-dir project-dir}]
       (core-config/ensure-config-dirs! d))
     (core-config/init-dirs!))))

(defn- scope-dir
  "Resolve the .brainyard/ directory for a given scope. Returns nil when the
   scope has no usable dir (e.g. :project requested but no project-dir was
   resolved)."
  [scope dirs]
  (case scope
    :project (core-config/project-config-dir dirs)
    :user    (core-config/user-config-dir dirs)
    nil))

(defn auto-scope
  "Decide the default scope when the caller did not pin one. Project wins
   when a project .brainyard/ dir exists; otherwise user."
  [dirs]
  (if (core-config/project-config-dir dirs) :project :user))

(defn- ^File brainyard-file
  "Path to BRAINYARD.md at the given scope, or nil."
  [scope dirs]
  (when-let [d (scope-dir scope dirs)]
    (io/file d brainyard-filename)))

(defn- init-agent-base
  "Pick the .brainyard/ dir to anchor init-agent's snapshots/dossiers/INDEX
   for a given scope. Mirrors config-agent: snapshots of project BRAINYARD.md
   live under <project>/.brainyard/agents/init-agent/; snapshots of user BRAINYARD.md
   live under ~/.brainyard/agents/init-agent/. The two scopes have independent
   snapshot histories.

   Scope is `:project | :user`. Returns nil when the scope's .brainyard/
   dir can't be resolved (e.g. :project requested with project-dir nil,
   which is rare since resolve-project-dir falls back to working-dir)."
  [dirs scope]
  (case scope
    :project (core-config/project-config-dir dirs)
    :user    (core-config/user-config-dir dirs)
    nil))

(defn- init-agent-bases-for-listing
  "Return a vector of [scope base-dir] pairs to read when listing snapshots
   across scopes. Skips scopes whose base-dir didn't resolve.

   `scope-filter` is `:project | :user | :both | nil` — nil and :both
   include both."
  [dirs scope-filter]
  (let [scopes (case scope-filter
                 :project [:project]
                 :user    [:user]
                 [:project :user])]
    (->> scopes
         (keep (fn [s]
                 (when-let [b (init-agent-base dirs s)]
                   [s b])))
         vec)))

(defn- ^File snapshots-dir [base] (io/file base artifacts-subdir snapshots-subdir))
(defn- ^File dossiers-dir  [base] (io/file base artifacts-subdir dossiers-subdir))

;; ============================================================================
;; Slug / timestamp helpers (mirrors config.clj)
;; ============================================================================

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
  (-> (subs (now-iso) 0 16) (str/replace "T" " ")))

(defn- slugify
  [s max-chars]
  (let [tokens (-> (str s)
                   str/lower-case
                   (str/replace #"[^a-z0-9]+" "-")
                   (str/split #"-"))
        kept   (->> tokens
                    (remove #(or (str/blank? %) (slug-stopwords %)))
                    seq)
        joined (if kept (str/join "-" kept) "change")]
    (subs joined 0 (min max-chars (count joined)))))

;; ============================================================================
;; Section parser (§4.3) — regex-based, permissive
;; ============================================================================

(defn- normalise-heading [^String s]
  (-> s str/trim str/lower-case (str/replace #"\s+" " ")))

(defn- canonical-heading
  "Match a free-form heading against `known-sections` (case-insensitive,
   whitespace-collapsed). Returns the canonical form, or the original
   string when unknown (unknown sections are preserved verbatim)."
  [^String s]
  (let [k (normalise-heading s)]
    (or (some (fn [h] (when (= k (normalise-heading h)) h)) known-sections)
        (str/trim s))))

(defn parse-sections
  "Split a BRAINYARD.md body into `[{:heading :body}]`. Lines before the
   first `## ` heading become `{:heading nil :body <preamble>}`. Order
   preserved. Unknown headings keep their literal text."
  [body]
  (if (str/blank? body)
    []
    (let [lines (str/split-lines body)
          ;; Walk lines, accumulating sections at every `## ` boundary.
          {:keys [sections current heading]}
          (reduce
           (fn [{:keys [sections current heading] :as st} line]
             (if-let [[_ h] (re-matches #"^##\s+(.+?)\s*$" line)]
               (let [flushed (cond-> sections
                               (or heading (seq current))
                               (conj {:heading heading
                                      :body    (str/join "\n" current)}))]
                 {:sections flushed
                  :current  []
                  :heading  (canonical-heading h)})
               (assoc st :current (conj current line))))
           {:sections [] :current [] :heading nil}
           lines)]
      (cond-> sections
        (or heading (seq current))
        (conj {:heading heading
               :body    (str/join "\n" current)})))))

(defn- section-titles-set [sections]
  (->> sections (keep :heading) (map normalise-heading) set))

(defn- notes-body
  "Trimmed body of the `## Notes` section in a parsed section-list; nil when
   absent. `## Notes` is the user's verbatim scratchpad — preserved unchanged
   across :curate / :reseed (R6). Trims so whitespace-only churn at the
   section boundary is not flagged as a modification."
  [sections]
  (some (fn [s]
          (when (and (:heading s)
                     (= "notes" (normalise-heading (:heading s))))
            (str/trim (:body s))))
        sections))

(defn- section-delta
  "Lightweight structural delta between two section-lists."
  [before after]
  (let [b-set (section-titles-set before)
        a-set (section-titles-set after)
        b-map (into {} (map (fn [s] [(some-> (:heading s) normalise-heading) s])) before)
        a-map (into {} (map (fn [s] [(some-> (:heading s) normalise-heading) s])) after)]
    {:added   (vec (sort (set/difference a-set b-set)))
     :removed (vec (sort (set/difference b-set a-set)))
     :changed (vec (sort (filter (fn [k]
                                   (and (contains? a-set k)
                                        (contains? b-set k)
                                        (not= (:body (get a-map k))
                                              (:body (get b-map k)))))
                                 a-set)))}))

;; ============================================================================
;; Sources priority list (§8.1)
;; ============================================================================

(defn- default-sources
  "Fallback when resources/init-agent/sources.edn is not on the classpath."
  []
  [{:source :claude-md
    :scope  :project
    :path   "CLAUDE.md"
    :priority :high}
   {:source :agents-md
    :scope  :project
    :path   "AGENTS.md"
    :priority :high}
   {:source :readme-md
    :scope  :project
    :path   "README.md"
    :priority :medium}
   {:source :claude-md
    :scope  :user
    :path   "~/.claude/CLAUDE.md"
    :priority :medium}
   {:source :agents-md
    :scope  :user
    :path   "~/.codex/AGENTS.md"
    :priority :medium}
   {:source :cursorrules
    :scope  :user
    :path   "~/.cursorrules"
    :priority :low}])

(defn- load-sources []
  (if-let [res (io/resource sources-edn-resource)]
    (try (edn/read-string (slurp res))
         (catch Throwable _ (default-sources)))
    (default-sources)))

(defn- expand-tilde [^String p]
  (if (str/starts-with? p "~/")
    (str (System/getProperty "user.home") (subs p 1))
    p))

(defn- resolve-source-path
  "Resolve a source path against a project-dir + scope."
  [{:keys [scope path]} dirs]
  (cond
    (str/starts-with? path "/") path
    (str/starts-with? path "~") (expand-tilde path)
    (= scope :user)             (str (:user-dir dirs) "/" path)
    :else                       (str (or (:project-dir dirs) ".") "/" path)))

;; ============================================================================
;; init$read — return current BRAINYARD.md contents + parsed sections
;; ============================================================================

(defn- read-file-info
  "Return `{:content :size :mtime}` (content trimmed) or nil if the file is
   missing/empty."
  [^File f]
  (when (and f (.isFile f))
    (let [raw (slurp f)]
      {:content (str/trim raw)
       :size    (.length f)
       :mtime   (.lastModified f)
       :path    (.getPath f)})))

(defn- one-scope-read [scope dirs]
  (let [f    (brainyard-file scope dirs)
        info (read-file-info f)]
    (merge {:scope    scope
            :path     (some-> f .getPath)
            :exists?  (boolean info)}
           (when info
             (assoc (select-keys info [:size :mtime])
                    :content  (:content info)
                    :sections (parse-sections (:content info)))))))

(defcommand init$read
  "Read BRAINYARD.md at one scope, the other, or both. Returns the raw
   markdown plus a section-parsed view (§4.3). Pure read; no side effects."
  (fn [& {:keys [scope project-dir] :or {scope :both}}]
    (let [scope* (if (keyword? scope) scope (keyword scope))
          dirs   (resolve-dirs project-dir)]
      (case scope*
        :project {:project (one-scope-read :project dirs)}
        :user    {:user    (one-scope-read :user    dirs)}
        :both    {:project (one-scope-read :project dirs)
                  :user    (one-scope-read :user    dirs)}
        {:error (str "Unknown scope: " scope*)})))
  :input-schema  [:map
                  [:scope       {:optional true} [:keyword {:desc "One of :project :user :both (default :both)"}]]
                  [:project-dir {:optional true} [:string  {:desc "Override project dir (tests)"}]]]
  :output-schema [:map
                  [:project {:optional true} [:map     {:desc "BRAINYARD.md state at project scope"}]]
                  [:user    {:optional true} [:map     {:desc "BRAINYARD.md state at user scope"}]]
                  [:error   {:optional true} :string]])

;; ============================================================================
;; init$detect-sources — stat-only probe of CLAUDE.md / AGENTS.md / README.md
;; ============================================================================

(defn- stat-source [src dirs]
  (let [abs (resolve-source-path src dirs)
        f   (io/file abs)]
    (cond-> (assoc src :path abs)
      (.isFile f) (assoc :exists? true
                         :size    (.length f)
                         :mtime   (.lastModified f))
      (not (.isFile f)) (assoc :exists? false))))

(defcommand init$detect-sources
  "Look for CLAUDE.md / AGENTS.md / README.md / ~/.cursorrules at the
   requested scope(s). Pure stat-only probe — does not read body contents.
   Returns {:found [...] :missing [...]} keyed by source descriptor."
  (fn [& {:keys [scope project-dir] :or {scope :both}}]
    (let [scope*  (if (keyword? scope) scope (keyword scope))
          dirs    (resolve-dirs project-dir)
          sources (load-sources)
          want    (case scope*
                    :both    sources
                    :project (filter #(= :project (:scope %)) sources)
                    :user    (filter #(= :user    (:scope %)) sources)
                    [])
          stated  (mapv #(stat-source % dirs) want)]
      {:scope   scope*
       :found   (vec (filter :exists? stated))
       :missing (vec (remove :exists? stated))}))
  :input-schema  [:map
                  [:scope       {:optional true} [:keyword {:desc "One of :project :user :both (default :both)"}]]
                  [:project-dir {:optional true} [:string  {:desc "Override project dir (tests)"}]]]
  :output-schema [:map
                  [:scope   :keyword]
                  [:found   [:vector {:desc "Sources that exist on disk"} :map]]
                  [:missing [:vector {:desc "Sources that were looked for but absent"} :map]]])

;; ============================================================================
;; init$diff — unified diff between on-disk file and proposed body
;; ============================================================================

(defn- write-temp! [^String prefix ^String content]
  (let [f (File/createTempFile prefix ".md")]
    (.deleteOnExit f)
    (spit f content)
    f))

(defn- git-text-diff
  "Try `git diff --no-index --no-color -- <before> <after>`. Returns the diff
   string on success, nil on failure / no diff."
  [before after]
  (try
    (let [^java.io.File pre  (write-temp! "brainyard-before" before)
          ^java.io.File post (write-temp! "brainyard-after"  after)
          pb   (doto (ProcessBuilder. ^"[Ljava.lang.String;"
                      (into-array String
                                  ["git" "diff" "--no-index" "--no-color"
                                   "--" (.getPath pre) (.getPath post)]))
                 (.redirectErrorStream true))
          proc (.start pb)
          out  (slurp (.getInputStream proc))]
      (.waitFor proc)
      (when-not (str/blank? out) out))
    (catch Throwable _ nil)))

(defn- fallback-diff [before after]
  (str "--- before\n" before "\n+++ after\n" after))

(defcommand init$diff
  "Compute a unified diff between the on-disk BRAINYARD.md at :scope and a
   proposed :body. Also returns a section-level structural delta. Pure read."
  (fn [& {:keys [scope proposed project-dir]
          :or   {scope :project}}]
    (cond
      (not (string? proposed))
      {:error ":proposed must be a string (the full proposed body)"}

      (not (valid-scope? (if (keyword? scope) scope (keyword scope))))
      {:error (str "Unknown scope: " scope)}

      :else
      (let [scope*   (if (keyword? scope) scope (keyword scope))
            dirs     (resolve-dirs project-dir)
            f        (brainyard-file scope* dirs)
            current  (or (some-> f read-file-info :content) "")
            before-s (parse-sections current)
            after-s  (parse-sections proposed)
            text     (or (git-text-diff current proposed)
                         (fallback-diff current proposed))]
        {:diff       text
         :structural (section-delta before-s after-s)
         :before     current
         :after      proposed
         :size       {:before (count current) :after (count proposed)}})))
  :input-schema  [:map
                  [:scope       {:optional true} [:keyword {:desc ":project or :user (default :project)"}]]
                  [:proposed    [:string  {:desc "The full proposed BRAINYARD.md body"}]]
                  [:project-dir {:optional true} [:string  {:desc "Override project dir (tests)"}]]]
  :output-schema [:map
                  [:diff       [:string {:desc "Unified diff text"}]]
                  [:structural [:map    {:desc "{:added :removed :changed} section keys"}]]
                  [:before     :string]
                  [:after      :string]
                  [:size       [:map    {:desc "{:before :after} byte counts"}]]
                  [:error      {:optional true} :string]])

;; ============================================================================
;; Snapshot internals + public commands
;; ============================================================================

(defn- snapshot-filename [scope reason]
  (str (now-ts) "-" (name scope) "-" (slugify (or reason "snapshot") 60) ".md"))

(defn- parse-snapshot-name
  "yyyyMMdd-HHmmss-<scope>-<slug>.md → {:ts :scope :reason}; nil if no match."
  [^String filename]
  (when-let [[_ ts scope reason]
             (re-matches #"^(\d{8}-\d{6})-(project|user)-(.+)\.md$" filename)]
    {:ts ts :scope (keyword scope) :reason reason}))

(defn- snapshot-records
  "List snapshot records (optionally filtered by scope), newest-first."
  ([base]       (snapshot-records base nil))
  ([base scope]
   (let [dir (snapshots-dir base)]
     (if-not (.isDirectory dir)
       []
       (->> (.listFiles dir)
            (filter (fn [^File f] (.isFile f)))
            (keep (fn [^File f]
                    (let [n (.getName f)]
                      (when-let [parsed (parse-snapshot-name n)]
                        (when (or (nil? scope) (= scope (:scope parsed)))
                          (merge parsed
                                 {:path       (.getPath f)
                                  :filename   n
                                  :size-bytes (.length f)
                                  :mtime      (.lastModified f)}))))))
            (sort-by :ts)
            reverse
            vec)))))

(defn- take-snapshot!
  "Copy the current BRAINYARD.md at `scope` into the snapshots dir.
   Returns {:ok? :path :scope :reason :ts :rotated [...]}. Per-scope rotation."
  [base scope reason dirs]
  (try
    (let [snap-dir (snapshots-dir base)
          _        (.mkdirs snap-dir)
          file     (brainyard-file scope dirs)]
      (cond
        (not file)
        {:ok? false :error (str "No .brainyard/ dir for scope " scope)}

        (not (.isFile file))
        ;; No source file yet — snapshot of an empty starting state. Write
        ;; an empty file so revert is still meaningful (restoring empty).
        (let [fname (snapshot-filename scope (or reason "pre-init"))
              out   (io/file snap-dir fname)]
          (spit out "")
          (mulog/log ::init.snapshot
                     :path (.getPath out) :scope scope
                     :reason reason :empty? true)
          {:ok? true :path (.getPath out) :scope scope
           :reason reason :ts (subs fname 0 15) :rotated []})

        :else
        (let [fname    (snapshot-filename scope reason)
              outfile  (io/file snap-dir fname)
              _        (io/copy file outfile)
              all      (snapshot-records base scope)
              keep     (set (map :filename (take snapshot-retention all)))
              rotated  (->> (.listFiles snap-dir)
                            (filter (fn [^File f]
                                      (let [n (.getName f)
                                            p (parse-snapshot-name n)]
                                        (and p
                                             (= scope (:scope p))
                                             (not (contains? keep n))))))
                            (mapv (fn [^File f]
                                    (let [n (.getName f)]
                                      (.delete f)
                                      n))))]
          (mulog/log ::init.snapshot
                     :path (.getPath outfile)
                     :scope scope :reason reason
                     :rotated (count rotated))
          {:ok?     true
           :path    (.getPath outfile)
           :scope   scope
           :reason  reason
           :ts      (subs fname 0 15)
           :rotated rotated})))
    (catch Throwable t
      {:ok? false :error (.getMessage t)})))

(defcommand init$snapshot
  "Snapshot the current BRAINYARD.md at :scope into init-agent/snapshots/.
   Useful before manual edits or risky exploratory writes."
  (fn [& {:keys [scope reason project-dir]
          :or   {scope :project}}]
    (let [scope* (if (keyword? scope) scope (keyword scope))]
      (cond
        (not (valid-scope? scope*))   {:error (str "Unknown scope: " scope)}
        (not (string? reason))         {:error ":reason is required (string)"}
        :else
        (let [dirs (resolve-dirs project-dir)
              base (init-agent-base dirs scope*)]
          (if-not base
            {:ok? false :error (str "No .brainyard/ dir for scope " scope*)}
            (take-snapshot! base scope* reason dirs))))))
  :input-schema  [:map
                  [:scope       {:optional true} [:keyword {:desc ":project or :user (default :project)"}]]
                  [:reason      [:string  {:desc "Short why (used in filename slug)"}]]
                  [:project-dir {:optional true} :string]]
  :output-schema [:map
                  [:ok?     :boolean]
                  [:path    {:optional true} :string]
                  [:scope   {:optional true} :keyword]
                  [:reason  {:optional true} :string]
                  [:ts      {:optional true} :string]
                  [:rotated {:optional true} [:vector :string]]
                  [:error   {:optional true} :string]])

(defcommand init$list-snapshots
  "List snapshots newest-first. Filter by :scope, or pass :scope :both /
   omit to see everything. Reads from the project init-agent/ AND user
   init-agent/ dirs and merges results — scope dirs hold independent
   histories per the recent per-scope split (mirrors config-agent)."
  (fn [& {:keys [scope limit project-dir]
          :or   {scope :both limit snapshot-retention}}]
    (let [scope*   (if (keyword? scope) scope (keyword scope))
          flt      (when (valid-scope? scope*) scope*)
          dirs     (resolve-dirs project-dir)
          pairs    (init-agent-bases-for-listing dirs flt)
          per-base (mapcat (fn [[_ b]] (snapshot-records b flt)) pairs)
          sorted   (->> per-base
                        (sort-by :ts)
                        reverse
                        vec)]
      {:snapshots (vec (take limit sorted))
       :scope     scope*
       :base-dirs (mapv second pairs)}))
  :input-schema  [:map
                  [:scope       {:optional true} [:keyword {:desc ":project :user :both (default :both)"}]]
                  [:limit       {:optional true} [:int     {:desc "Cap on results (default 20)"}]]
                  [:project-dir {:optional true} :string]]
  :output-schema [:map
                  [:snapshots [:vector {:desc "Newest-first, merged across scope dirs"} :map]]
                  [:scope     :keyword]
                  [:base-dirs [:vector {:desc "Scope dirs that contributed records"} :string]]])

;; ============================================================================
;; Smoke test — verify on-disk file parses and fits in budget
;; ============================================================================

(defn- smoke-test-scope
  [scope dirs]
  (let [f (brainyard-file scope dirs)
        info (read-file-info f)]
    (cond
      (not f)
      {:ok? false :reason "no-config-dir" :scope scope}

      (not info)
      {:ok? true :reason "absent" :scope scope :size 0}

      (> (:size info) size-hard-cap)
      {:ok? false :reason "over-hard-cap" :scope scope
       :size (:size info) :cap size-hard-cap}

      :else
      (let [sections (parse-sections (:content info))
            ;; Reload via core-config — round-trip check that the rest of
            ;; the system can still pick the file up.
            loaded   (core-config/load-brainyard-instructions dirs)
            loaded-v (case scope
                       :project (:project-instructions loaded)
                       :user    (:user-instructions    loaded))]
        {:ok?         (some? loaded-v)
         :scope       scope
         :size        (:size info)
         :soft-target (case scope
                        :project size-soft-target-project
                        :user    size-soft-target-user)
         :cap         size-hard-cap
         :section-count (count sections)
         :sections      (mapv (comp (fnil str "") :heading) sections)}))))

(defcommand init$smoke-test
  "Verify BRAINYARD.md at :scope: parses, fits the 8 KB cap, and is picked
   up by core-config/load-brainyard-instructions. Pure read."
  (fn [& {:keys [scope project-dir] :or {scope :both}}]
    (let [scope* (if (keyword? scope) scope (keyword scope))
          dirs   (resolve-dirs project-dir)]
      (case scope*
        :project {:project (smoke-test-scope :project dirs)}
        :user    {:user    (smoke-test-scope :user    dirs)}
        :both    {:project (smoke-test-scope :project dirs)
                  :user    (smoke-test-scope :user    dirs)}
        {:error (str "Unknown scope: " scope)})))
  :input-schema  [:map
                  [:scope       {:optional true} [:keyword {:desc ":project :user :both (default :both)"}]]
                  [:project-dir {:optional true} :string]]
  :output-schema [:map
                  [:project {:optional true} :map]
                  [:user    {:optional true} :map]
                  [:error   {:optional true} :string]])

;; ============================================================================
;; init$revert — restore a snapshot (snapshotting current first)
;; ============================================================================

(defn- find-snapshot
  "Resolve a snapshot record from :snapshot-path or (:scope + :steps).

   `base-fn` is `(fn [scope] -> base-dir-or-nil)`. For :snapshot-path the
   filename is enough to identify scope. For :steps we list records at the
   chosen scope's base dir."
  [base-fn {:keys [snapshot-path scope steps]}]
  (cond
    (string? snapshot-path)
    (let [f (io/file snapshot-path)]
      (if (.isFile f)
        (let [parsed (parse-snapshot-name (.getName f))]
          (merge parsed {:path (.getPath f) :filename (.getName f)}))
        {:error (str "Snapshot not found: " snapshot-path)}))

    (and (integer? steps) scope)
    (if-let [base (base-fn scope)]
      (let [recs (snapshot-records base scope)]
        (cond
          (or (< steps 1) (zero? (count recs)))
          {:error "No snapshots available at this scope."}

          (>= steps (inc (count recs)))
          {:error (str ":steps " steps " exceeds " (count recs)
                       " available snapshots at " scope ".")}

          :else (nth recs (dec steps))))
      {:error (str "No .brainyard/ dir for scope " scope)})

    :else
    {:error "Provide :snapshot-path, or (:scope + :steps)."}))

(defcommand init$revert
  "Restore a prior snapshot. Snapshots the current file at the same scope
   first (so revert is itself reversible), then copies the chosen snapshot
   over BRAINYARD.md.

   Snapshot lookup and pre-revert snapshot both honor the per-scope layout:
   project snapshots live under <project>/.brainyard/agents/init-agent/, user
   snapshots under ~/.brainyard/agents/init-agent/."
  (fn [& {:keys [snapshot-path scope steps project-dir]}]
    (let [dirs    (resolve-dirs project-dir)
          scope*  (when scope (if (keyword? scope) scope (keyword scope)))
          base-fn (fn [s] (init-agent-base dirs s))
          chosen  (find-snapshot base-fn {:snapshot-path snapshot-path
                                          :scope         scope*
                                          :steps         steps})]
      (if (:error chosen)
        chosen
        (let [src-scope (:scope chosen)
              src-path  (:path  chosen)
              src-slug  (:reason chosen)
              base-pre  (init-agent-base dirs src-scope)
              pre       (when base-pre
                          (take-snapshot! base-pre src-scope
                                          (str "revert-to-"
                                               (or src-slug "snapshot"))
                                          dirs))]
          (cond
            (not src-scope)
            {:ok? false :error "Could not parse scope from snapshot filename."}

            (not base-pre)
            {:ok? false :error (str "No .brainyard/ dir for scope " src-scope)}

            (not (:ok? pre))
            {:ok? false :error (str "Pre-revert snapshot failed: "
                                    (:error pre))}

            :else
            (try
              (let [dest (brainyard-file src-scope dirs)]
                (when-not dest
                  (throw (ex-info "No target file path for scope"
                                  {:scope src-scope})))
                (.mkdirs (.getParentFile dest))
                (io/copy (io/file src-path) dest)
                (mulog/log ::init.revert
                           :scope src-scope
                           :restored-from src-path
                           :pre-revert-snapshot (:path pre))
                {:ok?                 true
                 :scope               src-scope
                 :restored-from       src-path
                 :pre-revert-snapshot (:path pre)
                 :dest                (.getPath dest)})
              (catch Throwable t
                {:ok? false :error (.getMessage t)})))))))
  :input-schema  [:map
                  [:snapshot-path {:optional true} [:string  {:desc "Absolute path to a snapshot .md"}]]
                  [:scope         {:optional true} [:keyword {:desc "Scope when using :steps"}]]
                  [:steps         {:optional true} [:int     {:desc "1 = most-recent at scope, 2 = next-older, ..."}]]
                  [:project-dir   {:optional true} :string]]
  :output-schema [:map
                  [:ok?                 :boolean]
                  [:scope               {:optional true} :keyword]
                  [:restored-from       {:optional true} :string]
                  [:pre-revert-snapshot {:optional true} :string]
                  [:dest                {:optional true} :string]
                  [:error               {:optional true} :string]])

;; ============================================================================
;; Dossier helpers — frontmatter + write + INDEX
;; ============================================================================

(defn- yaml-string [s]
  (str "\"" (-> (str s)
                (str/replace "\\" "\\\\")
                (str/replace "\"" "\\\""))
       "\""))

(def ^:private bareword-re #"^[A-Za-z0-9_./:-]+$")

(defn- yaml-flow-vector [xs]
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

(defn- build-dossier-frontmatter*
  [{:keys [question slug session-id scope brainyard-path snapshots writes
           reverts started ended next-steps]}]
  (str "---\n"
       "agent: init-agent\n"
       "session-id: "    (yaml-string (or session-id "")) "\n"
       "question: "      (yaml-string (or question "")) "\n"
       "slug: "          slug "\n"
       "scope: "         (if scope (name scope) "project") "\n"
       "started: "       (or started (now-iso)) "\n"
       (when ended (str "ended: " ended "\n"))
       "brainyard-path: " (yaml-string (or brainyard-path "")) "\n"
       "snapshots: "     (yaml-flow-vector (or snapshots [])) "\n"
       "writes: "        (or writes 0) "\n"
       "reverts: "       (or reverts 0) "\n"
       "next-steps: "    (yaml-flow-vector (or next-steps [])) "\n"
       "---\n"))

(defn- dossier-filename [slug]
  (str (now-ts) "-" slug ".md"))

(defn- append-dossier!
  "Append a one-section markdown block to the conversation's dossier file.
   Creates the file with frontmatter on first call. Returns dossier path."
  [base slug entry]
  (let [dir   (dossiers-dir base)
        _     (.mkdirs dir)
        files (filter (fn [^File f]
                        (and (.isFile f)
                             (str/ends-with? (.getName f) (str "-" slug ".md"))))
                      (.listFiles dir))
        file  (or (first (sort-by #(.lastModified ^File %) > files))
                  (io/file dir (dossier-filename slug)))
        section (str "\n## " (now-yyyy-mm-dd-hh-mm) " — " (name (:op entry)) "\n"
                     "- op: "       (name (:op entry)) "\n"
                     "- scope: "    (name (:scope entry)) "\n"
                     "- reason: "   (or (:reason entry) "") "\n"
                     "- snapshot: " (:snapshot-path entry) "\n"
                     "- size: "     (:size-after entry) " B\n"
                     "- smoke: "    (pr-str (:smoke-test entry)) "\n"
                     (when (seq (:diff entry))
                       (str "\n```diff\n" (:diff entry) "```\n")))]
    (if (.exists ^File file)
      (spit file section :append true)
      (spit file (str (build-dossier-frontmatter*
                       {:question       (:question entry)
                        :slug           slug
                        :session-id     (:session-id entry)
                        :scope          (:scope entry)
                        :brainyard-path (:brainyard-path entry)
                        :snapshots      [(:snapshot-path entry)]
                        :writes         1
                        :reverts        0
                        :started        (now-iso)})
                      section)))
    (.getPath ^File file)))

(defn- index-append!
  "Prepend one line to .brainyard/agents/init-agent/INDEX.md."
  [base path slug summary]
  (let [file     (io/file base index-rel)
        existing (if (.isFile file) (slurp file) "")
        filename (-> path (str/split #"/") last)
        summary* (-> (str/replace summary #"\s+" " ") str/trim)
        line     (str "- " (now-yyyy-mm-dd-hh-mm)
                      " [" slug "](" dossiers-subdir "/" filename ") — "
                      "*" (if (> (count summary*) 200)
                            (str (subs summary* 0 197) "…")
                            summary*) "*\n")]
    (.mkdirs (.getParentFile file))
    (spit file (str line existing))
    (mulog/log ::init.dossier-index :slug slug :path path)
    line))

(defcommand init$frontmatter
  "Build a YAML frontmatter block for an init-agent dossier. Useful when
   composing a dossier by hand outside init$apply."
  (fn [& {:as args}]
    (if (string? (:slug args))
      {:frontmatter (build-dossier-frontmatter* args)}
      {:error ":slug is required (string)"}))
  :input-schema  [:map
                  [:slug           :string]
                  [:question       {:optional true} :string]
                  [:session-id     {:optional true} :string]
                  [:scope          {:optional true} :keyword]
                  [:brainyard-path {:optional true} :string]
                  [:snapshots      {:optional true} [:vector :string]]
                  [:writes         {:optional true} :int]
                  [:reverts        {:optional true} :int]
                  [:started        {:optional true} :string]
                  [:ended          {:optional true} :string]
                  [:next-steps     {:optional true} [:vector :string]]]
  :output-schema [:map
                  [:frontmatter :string]
                  [:error       {:optional true} :string]])

;; ============================================================================
;; init$apply — the transactional write
;;
;; Validates: scope, body type, size budget, secret scan, mtime conflict,
;; auto-confirmation rule (§7.4). Pipeline: snapshot → write → smoke test
;; → dossier → INDEX. Auto-reverts on smoke-test failure.
;; ============================================================================

(def ^:private secret-patterns
  "Cheap surface-level prefix patterns. We don't try to be exhaustive — the
   point is to refuse the obvious paste-in mistake."
  [#"sk-[A-Za-z0-9]{20,}"
   #"AKIA[0-9A-Z]{16}"
   #"ASIA[0-9A-Z]{16}"
   #"ghp_[A-Za-z0-9]{20,}"
   #"github_pat_[A-Za-z0-9_]{20,}"
   #"xox[bpoars]-[A-Za-z0-9-]{10,}"
   #"-----BEGIN [A-Z ]*PRIVATE KEY-----"
   #"AIza[0-9A-Za-z_-]{20,}"])

(defn- secret-scan
  "Return [match …] or empty vec."
  [^String body]
  (vec (mapcat (fn [pat]
                 (let [m (re-matcher pat body)]
                   (loop [hits []]
                     (if (.find m)
                       (recur (conj hits (.group m)))
                       hits))))
               secret-patterns)))

(def ^:private valid-ops #{:init :append :curate :reseed :replace-section})

(defn- char-delta
  "How many net chars an :op :append would add (`(- (count after) (count before))`)."
  [before after]
  (- (count after) (count before)))

(defn- append-only?
  "True when `after` preserves every line of `before` (compared as a line
   multiset) and only adds content — i.e. no existing line was removed or
   edited in place. Gates the :auto? self-confirming :op :append path so a
   same-size destructive edit (replace N chars with N different chars,
   net delta 0) cannot masquerade as a small append. Mid-section inserts
   still pass: existing lines remain, new ones are added. Blank `before`
   (first write) is vacuously append-only."
  [before after]
  (or (str/blank? before)
      (let [need (frequencies (str/split-lines before))
            have (frequencies (str/split-lines after))]
        (every? (fn [[line n]] (>= (long (get have line 0)) (long n))) need))))

(defcommand init$apply
  "Transactional write to BRAINYARD.md at :scope.

   Pipeline:
     1. validate :op, :scope, :body, size cap (8 KB), secret scan, and the
        R6 `## Notes` guard (:curate / :reseed may not alter Notes →
        :stage :notes-modified)
     2. confirm gate (§7.4: :auto? + :op :append that adds <= 200 chars AND
        removes no existing line passes; everything else needs :confirm? true)
     3. mtime check (if :expected-mtime supplied)
     4. snapshot current file (revert anchor)
     5. write file (direct spit; .brainyard/ dir is ensured)
     6. smoke test (parse + size + reload via load-brainyard-instructions)
     7. on smoke-test failure: auto-revert using the pre-write snapshot
     8. dossier append + INDEX line

   Returns {:ok? :snapshot-path :diff :smoke-test :dossier-path :size-after
            :mtime-after} on success;
           {:ok? false :stage <kw> :error :hint ...} on failure."
  (fn [& {:keys [scope op body reason confirm? auto? expected-mtime
                 question session-id project-dir]
          :or   {op :init scope :project}}]
    (let [scope* (if (keyword? scope) scope (keyword scope))
          op*    (if (keyword? op)    op    (keyword op))
          dirs   (resolve-dirs project-dir)
          base   (init-agent-base dirs scope*)
          file   (brainyard-file scope* dirs)]
      (cond
        ;; 1. Validate inputs
        (not (valid-scope? scope*))
        {:ok? false :stage :validate
         :error (str "Unknown scope: " scope)}

        (not base)
        {:ok? false :stage :validate
         :error (str "No .brainyard/ dir for scope " scope*)}

        (not (contains? valid-ops op*))
        {:ok? false :stage :validate
         :error (str ":op must be one of " (sort valid-ops))}

        (not (string? body))
        {:ok? false :stage :validate
         :error ":body must be a string (the full proposed markdown)"}

        (not (string? reason))
        {:ok? false :stage :validate
         :error ":reason is required (string)"}

        (not file)
        {:ok? false :stage :validate
         :error (str "No .brainyard/ dir for scope " scope*)}

        ;; 1b. Size budget
        (> (count body) size-hard-cap)
        {:ok? false :stage :budget-exceeded
         :size (count body) :cap size-hard-cap
         :hint "Propose :op :curate or split between :project and :user scopes."}

        ;; 1c. Secret-pattern scan
        :else
        (let [secrets (secret-scan body)]
          (if (seq secrets)
            {:ok? false :stage :secret-detected
             :matches (mapv #(str (subs % 0 (min 8 (count %))) "…") secrets)
             :hint "BRAINYARD.md is loaded into every agent turn. Put secrets in .env."}

            (let [current (or (some-> file read-file-info :content) "")
                  delta   (char-delta current body)
                  ;; 1d. R6 guard — `## Notes` is the user's verbatim
                  ;; scratchpad; :curate / :reseed must not alter it. (:append
                  ;; never targets Notes; :init / :replace-section have no
                  ;; prior Notes to protect. When current has no Notes the
                  ;; check is vacuous — adding a Notes section is allowed.)
                  notes-violation?
                  (and (contains? #{:curate :reseed} op*)
                       (let [cur (notes-body (parse-sections current))]
                         (and cur (not= cur (notes-body (parse-sections body))))))
                  ;; 2. Confirmation gate — :auto? self-confirms only a SMALL
                  ;; (net add <= threshold) AND purely-additive :op :append.
                  ;; The append-only? check closes the same-size destructive
                  ;; edit hole that a net-delta-only gate would let through.
                  auto-ok? (and auto?
                                (= op* :append)
                                (<= 0 delta auto-append-threshold)
                                (append-only? current body))
                  confirmed? (or confirm? auto-ok?)]
              (cond
                notes-violation?
                {:ok? false :stage :notes-modified
                 :hint (str "`## Notes` is the user's verbatim scratchpad; "
                            ":curate / :reseed must preserve it unchanged. "
                            "Re-propose with the original `## Notes` section intact.")}

                (not confirmed?)
                (let [d  (try (tool/invoke-tool :init$diff
                                                :scope scope*
                                                :proposed body
                                                :project-dir project-dir)
                              (catch Throwable t {:error (.getMessage t)}))]
                  {:ok? false :stage :unconfirmed
                   :hint (if auto?
                           "auto-confirmation-required: only an :op :append that adds <= 200 chars AND removes no existing line self-confirms; otherwise pass :confirm? true."
                           "Surface :diff to the user, then re-call with :confirm? true.")
                   :diff (:diff d)
                   :structural (:structural d)
                   :delta-chars delta})

                ;; 3. mtime conflict
                (and expected-mtime
                     (.isFile file)
                     (not= expected-mtime (.lastModified file)))
                {:ok? false :stage :mtime-conflict
                 :expected expected-mtime
                 :actual   (.lastModified file)
                 :hint "BRAINYARD.md changed since you read it. Re-read with init$read."}

                :else
                ;; 4. Pre-write snapshot
                (let [snap (take-snapshot! base scope* reason dirs)]
                  (if-not (:ok? snap)
                    {:ok? false :stage :snapshot :error (:error snap)}
                    (try
                      ;; 5. Compute diff BEFORE write (post-write read of
                      ;; "current" would be the new content).
                      (let [pre-diff (tool/invoke-tool :init$diff
                                                       :scope scope*
                                                       :proposed body
                                                       :project-dir project-dir)
                            ;; 5b. Write
                            _        (.mkdirs (.getParentFile file))
                            _        (spit file body)
                            mtime'   (.lastModified file)
                            ;; 6. Smoke test
                            smoke    (smoke-test-scope scope* dirs)]
                        (if-not (:ok? smoke)
                          ;; 7. Auto-revert on smoke failure
                          (do
                            (io/copy (io/file (:path snap)) file)
                            (mulog/log ::init.apply.auto-revert
                                       :scope scope* :reason reason
                                       :smoke smoke)
                            {:ok? false :stage :smoke-test-failed
                             :reverted? true
                             :smoke smoke
                             :snapshot-path (:path snap)
                             :error "Smoke test failed; auto-reverted to pre-write state."})
                          ;; 8. Dossier + INDEX
                          (let [slug    (slugify reason 60)
                                dossier (append-dossier!
                                         base slug
                                         {:op             op*
                                          :scope          scope*
                                          :reason         reason
                                          :snapshot-path  (:path snap)
                                          :size-after     (count body)
                                          :smoke-test     smoke
                                          :diff           (:diff pre-diff)
                                          :question       question
                                          :session-id     session-id
                                          :brainyard-path (.getPath file)})
                                _       (index-append!
                                         base dossier slug
                                         (or reason "init-agent write"))]
                            (mulog/log ::init.apply
                                       :scope scope* :op op*
                                       :reason reason
                                       :path (.getPath file)
                                       :snapshot (:path snap)
                                       :size (count body))
                            {:ok?           true
                             :scope         scope*
                             :op            op*
                             :path          (.getPath file)
                             :snapshot-path (:path snap)
                             :diff          (:diff pre-diff)
                             :structural    (:structural pre-diff)
                             :smoke-test    smoke
                             :dossier-path  dossier
                             :size-after    (count body)
                             :mtime-after   mtime'})))
                      (catch Throwable t
                        {:ok? false :stage :write :error (.getMessage t)})))))))))))
  :input-schema  [:map
                  [:scope          {:optional true} [:keyword {:desc ":project or :user (default :project)"}]]
                  [:op             {:optional true} [:keyword {:desc ":init :append :curate :reseed :replace-section (default :init)"}]]
                  [:body           [:string  {:desc "Full proposed BRAINYARD.md body"}]]
                  [:reason         [:string  {:desc "Short why (used for snapshot slug + dossier)"}]]
                  [:confirm?       {:optional true} [:boolean {:desc "Required true (or :auto? + small append) to actually write"}]]
                  [:auto?          {:optional true} [:boolean {:desc "--auto mode: small :op :append writes self-confirm"}]]
                  [:expected-mtime {:optional true} [:int     {:desc "Refuse if file mtime differs (concurrent-edit guard)"}]]
                  [:question       {:optional true} [:string  {:desc "User question (recorded in dossier)"}]]
                  [:session-id     {:optional true} [:string  {:desc "Agent session id (recorded in dossier)"}]]
                  [:project-dir    {:optional true} [:string  {:desc "Override project dir (tests)"}]]]
  :output-schema [:map
                  [:ok?           :boolean]
                  [:stage         {:optional true} :keyword]
                  [:scope         {:optional true} :keyword]
                  [:op            {:optional true} :keyword]
                  [:path          {:optional true} :string]
                  [:snapshot-path {:optional true} :string]
                  [:diff          {:optional true} :string]
                  [:structural    {:optional true} :map]
                  [:smoke-test    {:optional true} :map]
                  [:dossier-path  {:optional true} :string]
                  [:size-after    {:optional true} :int]
                  [:mtime-after   {:optional true} :int]
                  [:delta-chars   {:optional true} :int]
                  [:reverted?     {:optional true} :boolean]
                  [:matches       {:optional true} [:vector :string]]
                  [:expected      {:optional true} :int]
                  [:actual        {:optional true} :int]
                  [:hint          {:optional true} :string]
                  [:error         {:optional true} :string]])

;; ============================================================================
;; Public roster
;; ============================================================================

(def init-helpers
  "Vars for init-agent's :agent-tools concat."
  [#'init$read
   #'init$detect-sources
   #'init$diff
   #'init$snapshot
   #'init$list-snapshots
   #'init$revert
   #'init$smoke-test
   #'init$apply
   #'init$frontmatter])
