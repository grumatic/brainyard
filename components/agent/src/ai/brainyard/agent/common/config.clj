;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.config
  "config-agent helpers: read, diff, snapshot, list, revert + dossier writer.
   See docs/design/config-agent-design.md.

   This namespace owns the read/diff/snapshot/revert surface and the dossier
   writer for config-agent. `config$apply` (the transactional write) lives
   here too — added in Step B. `agent.core.config` (read-edn-config /
   write-edn-config!) is intentionally unchanged."
  (:require [ai.brainyard.agent.core.config :as core-config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:import (java.io File)
           (java.text SimpleDateFormat)
           (java.util Date)))

;; ============================================================================
;; Persisted-schema (design §7) — allowlist + leaf validators
;; ============================================================================

(def writable-prefixes
  "Path prefixes config-agent may write through config$apply. Anything that
   doesn't have one of these as a prefix is rejected as :allowlist-violation.

   `[:agent :config]` is the per-key runtime override subtree — every leaf
   under it must be a `config-schema` key (validated via the schema-type
   leaf check in `validate-persisted`). The legacy flat prefix
   `[:agent :max-iterations]` was replaced by `[:agent :config]` once
   readers migrated to `config/get-config`; a one-shot migrator in
   `config$apply` relocates the legacy position for callers writing
   against the old shape.

   :llm.default-provider / :llm.default-model are deliberately absent — those
   belong to bootstrap. :bootstrap.* is absent — read-only.
   :environment.executables/:sandbox-type/:os are absent — env-detect owns them."
  #{[:llm :available-providers]
    [:permissions :mode]
    [:permissions :allowed-dirs]
    [:agent :default-agent]
    [:agent :config]
    [:environment :sandbox-mode]
    [:mcp :servers]
    [:updated-at]})

(defn- valid-mode?    [v] (contains? #{:auto-approve :ask-each-time :deny-by-default} v))
(defn- valid-sandbox? [v] (contains? #{:permissive :standard :restricted} v))

(def leaf-validators
  "Per-leaf predicates for the small set of structurally-typed paths.
   `[:agent :config :*]` leaves are type-checked dynamically against
   `core-config/config-schema` inside `validate-persisted` — no per-key
   entry needed here.
   Paths under `[:mcp :servers ...]` are passed through unchecked
   (mcp-agent owns the per-server shape)."
  {[:permissions :mode]               valid-mode?
   [:permissions :allowed-dirs]       (every-pred sequential?
                                                  #(every? string? %))
   [:agent :default-agent]            keyword?
   [:environment :sandbox-mode]        valid-sandbox?
   [:llm :available-providers]         (every-pred sequential?
                                                   #(every? keyword? %))
   [:updated-at]                       string?})

(defn- path-allowed?
  "True if `path` is a writable leaf OR sits under a writable map prefix."
  [path]
  (boolean
   (or (contains? writable-prefixes path)
       (some (fn [pref]
               (and (<= (count pref) (count path))
                    (= pref (subvec (vec path) 0 (count pref)))))
             writable-prefixes))))

(defn- collect-leaves
  "Walk `m` and return a list of `[path leaf-value]` for every leaf."
  [m]
  (letfn [(walk [prefix x]
            (cond
              ;; Treat :mcp.servers's body as opaque — its sub-shape is
              ;; per-server-mcp-agent territory.
              (= prefix [:mcp :servers])
              [[prefix x]]
              (map? x)
              (mapcat (fn [[k v]] (walk (conj prefix k) v)) x)
              :else
              [[prefix x]]))]
    (walk [] m)))

(defn- schema-config-leaf?
  "True when `path` is of shape `[:agent :config <schema-key>]`."
  [path]
  (and (= 3 (count path))
       (= [:agent :config] (subvec (vec path) 0 2))))

(defn validate-persisted
  "Check `proposed` against the allowlist + leaf validators.
   For leaves under `[:agent :config :*]`, validate against
   `core-config/config-schema`'s declared type — the key must be in
   `config-keys` and the value must match the type.
   Returns {:ok? bool :errors [{:type :path :value :reason}]}."
  [proposed]
  (let [leaves (collect-leaves proposed)
        errors (reduce
                (fn [errs [path v]]
                  (cond
                    (not (path-allowed? path))
                    (conj errs {:type   :allowlist-violation
                                :path   path
                                :reason (str path " is not in writable-prefixes. "
                                             "If this is an LLM provider/model change, "
                                             "call bootstrap$re-run-rung instead.")})

                    (schema-config-leaf? path)
                    (let [k (last path)]
                      (cond
                        (not (contains? core-config/config-keys k))
                        (conj errs {:type   :schema-violation
                                    :path   path
                                    :value  v
                                    :reason (str k " is not a known config-schema key. "
                                                 "See agent.core.config/config-schema.")})

                        (not (core-config/valid-config-value? k v))
                        (conj errs {:type   :schema-violation
                                    :path   path
                                    :value  v
                                    :reason (str path " value " (pr-str v)
                                                 " does not match schema type "
                                                 (:type (get core-config/config-schema k)) ".")})

                        :else
                        errs))

                    (when-let [pred (get leaf-validators path)]
                      (not (pred v)))
                    (conj errs {:type   :schema-violation
                                :path   path
                                :value  v
                                :reason (str path " did not pass the leaf validator.")})

                    :else
                    errs))
                []
                leaves)]
    {:ok? (empty? errors) :errors errors}))

(def ^:private secret-patterns
  "Cheap surface-level patterns — refuse the obvious paste-in mistake (not
   exhaustive). config.edn is read into the running process and :project
   scope is committed with the repo, so inline secret VALUES must not land
   here; reference them via env vars instead."
  [#"sk-[A-Za-z0-9]{20,}"
   #"AKIA[0-9A-Z]{16}"
   #"ASIA[0-9A-Z]{16}"
   #"ghp_[A-Za-z0-9]{20,}"
   #"github_pat_[A-Za-z0-9_]{20,}"
   #"xox[bpoars]-[A-Za-z0-9-]{10,}"
   #"-----BEGIN [A-Z ]*PRIVATE KEY-----"
   #"AIza[0-9A-Za-z_-]{20,}"])

(defn- secret-scan
  "Return [match …] of secret-shaped substrings in `s` (empty when clean)."
  [^String s]
  (vec (mapcat (fn [pat]
                 (let [m (re-matcher pat s)]
                   (loop [hits []]
                     (if (.find m) (recur (conj hits (.group m))) hits))))
               secret-patterns)))

(def ^:private sensitive-prefixes
  "Security-relevant write targets that must never self-confirm under :auto?
   — a human approval is required even in headless mode, because loosening
   any of these widens the agent's blast radius."
  #{[:permissions :mode]
    [:permissions :allowed-dirs]
    [:environment :sandbox-mode]})

(defn- sensitive-write?
  "True when `proposed` writes a leaf under any `sensitive-prefixes` path."
  [proposed]
  (boolean (some (fn [[path _]]
                   (some (fn [pref]
                           (and (<= (count pref) (count path))
                                (= pref (subvec (vec path) 0 (count pref)))))
                         sensitive-prefixes))
                 (collect-leaves proposed))))

;; ============================================================================
;; Base directory resolution
;; ============================================================================

(defn resolve-dirs
  "Resolve the dirs map, honoring an optional :project-dir override (for tests).
   When override is supplied, returns a synthetic dirs map rooted at it; the
   `.brainyard/` subdir is ensured."
  ([]              (resolve-dirs nil))
  ([project-dir]
   (if project-dir
     (let [d {:user-dir    (System/getProperty "user.home")
              :project-dir project-dir
              :working-dir project-dir}]
       (core-config/ensure-config-dirs! d))
     (core-config/init-dirs!))))

(defn config-agent-base-dir
  "Resolve the .brainyard/agents/config-agent/ owner directory.

   Arity-1 (string) — explicit path passthrough (advanced/test override).
   Arity-1 (keyword) — scope `:project|:user|:auto`; nil if not resolvable.
   Arity-2 — explicit string takes precedence over scope (back-compat).
   Arity-0 — same as `:auto`.

   `:auto` mirrors `write-edn-config!`: project-config-dir if available,
   else user-config-dir."
  ([] (config-agent-base-dir nil :auto))
  ([explicit-or-scope]
   (cond
     (string? explicit-or-scope) explicit-or-scope
     (keyword? explicit-or-scope) (config-agent-base-dir nil explicit-or-scope)
     :else (config-agent-base-dir nil :auto)))
  ([explicit scope]
   (cond
     (string? explicit) explicit
     :else
     (let [dirs (resolve-dirs)]
       (case (or scope :auto)
         :project (core-config/project-config-dir dirs)
         :user    (core-config/user-config-dir dirs)
         :auto    (or (core-config/project-config-dir dirs)
                      (core-config/user-config-dir dirs))
         nil)))))

(def ^:private artifacts-subdir "agents/config-agent")
(def ^:private snapshots-subdir "snapshots")
(def ^:private dossiers-subdir "dossiers")
(def ^:private index-rel (str artifacts-subdir "/INDEX.md"))

(defn- ^java.io.File snapshots-dir [base] (io/file base artifacts-subdir snapshots-subdir))
(defn- ^java.io.File dossiers-dir  [base] (io/file base artifacts-subdir dossiers-subdir))

;; ============================================================================
;; Slug / timestamps (extracted shape; mirrors explore.clj)
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

(defcommand config$slug
  "Kebab-case slug from a reason/question; drops stopwords, caps at 60 chars."
  (fn [& {:keys [reason max-chars] :or {max-chars 60}}]
    (cond
      (not (string? reason))
      {:error ":reason is required (string)"}

      (or (not (integer? max-chars)) (<= max-chars 0))
      {:error ":max-chars must be a positive integer"}

      :else
      {:slug (slugify reason max-chars)}))
  :input-schema  [:map
                  [:reason [:string {:desc "Short why-string (used for the slug)"}]]
                  [:max-chars {:optional true} :int]]
  :output-schema [:map
                  [:slug [:string {:desc "Kebab-case slug"}]]
                  [:error {:optional true} [:string {:desc "Error if validation failed"}]]])

;; ============================================================================
;; config$read — section read + runtime overlay
;; ============================================================================

(defn- section-of [config section]
  (case section
    :all         config
    :llm         {:llm (:llm config)}
    :permissions {:permissions (:permissions config)}
    :agent       {:agent (:agent config)}
    :mcp         {:mcp (:mcp config)}
    :environment {:environment (:environment config)}
    :bootstrap   {:bootstrap (:bootstrap config)}
    {:error (str "Unknown section: " section)}))

(defn- config-file-info
  "Resolve the actual config.edn path + mtime for a given scope, or nil.

   `scope` is `:project | :user | :auto` (default `:auto`):
     :auto    — project file if present, else user file
     :project — project file only
     :user    — user file only
   Returns nil when no file exists for the requested scope."
  ([dirs] (config-file-info dirs :auto))
  ([dirs scope]
   (let [^java.io.File pcfg (some-> (core-config/project-config-dir dirs)
                                    (io/file "config.edn"))
         ^java.io.File ucfg (some-> (core-config/user-config-dir dirs)
                                    (io/file "config.edn"))
         pcfg-exists? (and pcfg (.isFile pcfg))
         ucfg-exists? (and ucfg (.isFile ucfg))
         ^java.io.File f (case (or scope :auto)
                           :project (when pcfg-exists? pcfg)
                           :user    (when ucfg-exists? ucfg)
                           :auto    (cond pcfg-exists? pcfg ucfg-exists? ucfg :else nil)
                           nil)]
     (when f
       {:path (.getPath f) :mtime (.lastModified f) :size (.length f)}))))

(defn- scope-summary
  "Build a {:scope :requested :config-dir :scope-resolvable?} block used in
   command responses so the caller (LLM) can see where reads/writes are
   actually landing. Returns {:scope-resolvable? false :error ...} on
   `:scope :project` requested outside a repo, etc."
  [dirs scope-arg]
  (let [scope-arg (or scope-arg :auto)]
    (if-let [r (core-config/resolve-scope dirs scope-arg)]
      (assoc r :scope-resolvable? true)
      {:scope-resolvable? false
       :requested         scope-arg
       :error             (case scope-arg
                            :project "No project dir resolvable (not in a git repo and BRAINYARD_PROJECT_DIR not set)."
                            :user    "No user home dir resolvable."
                            (str "Unknown scope: " scope-arg))})))

(defcommand config$read
  "Read the persisted config (whole or section) plus the runtime overlay.

   `:scope :project|:user|:auto` (default `:auto`) selects which file to
   read. `:auto` prefers project if its config.edn exists, else user.

   Returns {:persisted <slice> :runtime <map> :mtime <long> :path <str>
            :scope :project|:user :requested-scope <kw>}."
  (fn [& {:keys [section scope project-dir] :or {section :all scope :auto}}]
    (let [section* (if (keyword? section) section (keyword section))
          dirs     (resolve-dirs project-dir)
          scope*   (scope-summary dirs scope)]
      (if-not (:scope-resolvable? scope*)
        {:error (:error scope*) :requested-scope (:requested scope*)}
        (let [resolved-scope (:scope scope*)
              config         (core-config/read-edn-config dirs resolved-scope)
              info           (config-file-info dirs resolved-scope)
              slice          (section-of config section*)]
          (if (:error slice)
            slice
            (let [runtime (if-let [ag proto/*current-agent*]
                            (core-config/get-config-snapshot ag)
                            (core-config/get-config-snapshot))]
              (cond-> {:persisted       slice
                       :runtime         runtime
                       :section         section*
                       :scope           resolved-scope
                       :requested-scope (:requested scope*)
                       :config-dir      (:config-dir scope*)}
                info (assoc :path  (:path info)
                            :mtime (:mtime info)))))))))
  :input-schema  [:map
                  [:section {:optional true} [:keyword {:desc "One of :all :llm :permissions :agent :mcp :environment :bootstrap (default :all)"}]]
                  [:scope {:optional true} [:keyword {:desc ":project | :user | :auto (default :auto = project-if-exists-else-user)"}]]
                  [:project-dir {:optional true} [:string {:desc "Override project dir (tests)"}]]]
  :output-schema [:map
                  [:persisted [:map {:desc "Requested slice of config.edn"}]]
                  [:runtime [:map {:desc "Live merged-config snapshot for the current agent"}]]
                  [:section [:keyword {:desc "Echo of the requested section"}]]
                  [:scope {:optional true} [:keyword {:desc "Resolved scope (:project or :user)"}]]
                  [:requested-scope {:optional true} [:keyword {:desc "Echo of the requested scope"}]]
                  [:config-dir {:optional true} [:string {:desc ".brainyard/ dir actually read from"}]]
                  [:path {:optional true} [:string {:desc "On-disk path of config.edn (when present)"}]]
                  [:mtime {:optional true} [:int {:desc "Last-modified epoch ms (when present)"}]]
                  [:error {:optional true} [:string {:desc "Error if invalid section or unresolvable scope"}]]])

;; ============================================================================
;; config$diff — pretty-printed unified diff + structural delta
;; ============================================================================

(defn- pp-str [m]
  (with-out-str (pp/pprint m)))

(defn- write-temp! [^String prefix ^String content]
  (let [f (File/createTempFile prefix ".edn")]
    (.deleteOnExit f)
    (spit f content)
    f))

(defn- git-text-diff
  "Try `git diff --no-index --no-color -- <before> <after>`. Returns the diff
   string on success, nil if git is unavailable or returns no output."
  [before after]
  (try
    (let [^java.io.File pre  (write-temp! "config-before" before)
          ^java.io.File post (write-temp! "config-after"  after)
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

(defn- fallback-diff
  "When `git` is absent, emit a simple before/after block."
  [before after]
  (str "--- before\n" before
       "+++ after\n" after))

(defn- structural-delta
  "Use clojure.data/diff to compute structural adds/removes/changes."
  [before after]
  (let [[only-before only-after _both] (data/diff before after)]
    {:adds    (or only-after  {})
     :removes (or only-before {})
     :changes (when (and (map? only-before) (map? only-after))
                (let [shared-paths (set (keys only-before))]
                  (->> shared-paths
                       (map (fn [k] [k {:before (get only-before k)
                                        :after  (get only-after k)}]))
                       (into {}))))}))

(defcommand config$diff
  "Compute a unified diff and structural delta between current config.edn
   (at the requested scope) and a proposed (deep-merged) map.
   Pure read; no side effects."
  (fn [& {:keys [proposed scope project-dir] :or {scope :auto}}]
    (cond
      (not (map? proposed))
      {:error ":proposed must be a map"}

      :else
      (let [dirs   (resolve-dirs project-dir)
            scope* (scope-summary dirs scope)]
        (if-not (:scope-resolvable? scope*)
          {:error (:error scope*) :requested-scope (:requested scope*)}
          (let [resolved-scope (:scope scope*)
                current        (core-config/read-edn-config dirs resolved-scope)
                merged         (merge-with (fn [a b] (if (and (map? a) (map? b)) (merge a b) b))
                                           current proposed)
                before         (pp-str current)
                after          (pp-str merged)
                text           (or (git-text-diff before after)
                                   (fallback-diff before after))]
            {:diff            text
             :structural      (structural-delta current merged)
             :before          before
             :after           after
             :scope           resolved-scope
             :requested-scope (:requested scope*)
             :config-dir      (:config-dir scope*)})))))
  :input-schema  [:map
                  [:proposed [:map {:desc "Partial map deep-merged over current config"}]]
                  [:scope {:optional true} [:keyword {:desc ":project | :user | :auto (default :auto)"}]]
                  [:project-dir {:optional true} [:string {:desc "Override project dir (tests)"}]]]
  :output-schema [:map
                  [:diff [:string {:desc "Unified-diff text"}]]
                  [:structural [:map {:desc "{:adds :removes :changes} from clojure.data/diff"}]]
                  [:before [:string {:desc "Pretty-printed current config"}]]
                  [:after [:string {:desc "Pretty-printed merged config"}]]
                  [:scope {:optional true} [:keyword {:desc "Resolved scope"}]]
                  [:requested-scope {:optional true} [:keyword {:desc "Echo of the requested scope"}]]
                  [:config-dir {:optional true} [:string {:desc ".brainyard/ dir compared against"}]]
                  [:error {:optional true} [:string {:desc "Error if input invalid or unresolvable scope"}]]])

;; ============================================================================
;; Snapshot internals + public commands
;; ============================================================================

(def ^:private snapshot-retention 20)

(defn- snapshot-filename [reason]
  (str (now-ts) "-" (slugify (or reason "snapshot") 60) ".edn"))

(defn- parse-snapshot-name
  "yyyyMMdd-HHmmss-<slug>.edn → {:ts str :reason str}; nil if not a match."
  [^String filename]
  (when-let [[_ ts reason] (re-matches #"^(\d{8}-\d{6})-(.+)\.edn$" filename)]
    {:ts ts :reason reason}))

(defn- take-snapshot!
  "Pure-ish helper used by both config$snapshot and (Step B) config$apply.
   Snapshots the config.edn at the requested scope into `<base>/snapshots/`.

   When there's no file to snapshot at the requested scope, returns
   `{:ok? true :skipped? true :reason \"no config.edn at scope\"}` so that
   `config$apply` can still create a fresh file. The standalone
   `config$snapshot` command treats that as an error (no file = nothing to
   preserve), which is enforced at the command layer.

   Otherwise: {:ok? :path :reason :ts :rotated [str]}."
  [base reason project-dir scope]
  (try
    (let [snap-dir (snapshots-dir base)
          _       (.mkdirs snap-dir)
          dirs    (resolve-dirs project-dir)
          info    (config-file-info dirs (or scope :auto))]
      (if-not info
        {:ok? true :skipped? true
         :reason (str "No config.edn at scope " (or scope :auto)
                      " — nothing to snapshot.")}
        (let [fname               (snapshot-filename reason)
              ^java.io.File outfile (io/file snap-dir fname)
              ;; full-file copy keeps "drop-in replacement" invariant simple.
              _                   (io/copy (io/file (:path info)) outfile)
              ;; rotation: keep most-recent `snapshot-retention`, drop oldest.
              all                 (->> (.listFiles snap-dir)
                                       (filter (fn [^java.io.File f]
                                                 (and (.isFile f)
                                                      (str/ends-with? (.getName f) ".edn"))))
                                       (sort-by (fn [^java.io.File f] (.getName f))))
              keep                (set (take-last snapshot-retention
                                                  (map (fn [^java.io.File f] (.getName f)) all)))
              rotated             (->> all
                                       (remove (fn [^java.io.File f] (contains? keep (.getName f))))
                                       (mapv (fn [^java.io.File f]
                                               (let [n (.getName f)]
                                                 (.delete f)
                                                 n))))]
          (mulog/log ::config.snapshot
                     :path (.getPath outfile)
                     :reason reason
                     :rotated (count rotated))
          {:ok?     true
           :path    (.getPath outfile)
           :reason  reason
           :ts      (subs fname 0 15)
           :rotated rotated})))
    (catch Throwable t
      {:ok? false :error (.getMessage t)})))

(defcommand config$snapshot
  "Copy the current config.edn (at the requested scope) into
   <scope>/.brainyard/agents/config-agent/snapshots/. Rotates: keep last 20.

   Snapshot history is per-scope: project edits snapshot under the project's
   .brainyard/, user edits under ~/.brainyard/."
  (fn [& {:keys [reason scope base-dir project-dir] :or {scope :auto}}]
    (cond
      (not (string? reason))
      {:error ":reason is required (string)"}

      :else
      (let [dirs   (resolve-dirs project-dir)
            scope* (scope-summary dirs scope)]
        (if-not (:scope-resolvable? scope*)
          {:ok? false :error (:error scope*) :requested-scope (:requested scope*)}
          (let [resolved-scope (:scope scope*)
                base (or base-dir
                         (if project-dir
                           (config-agent-base-dir (str project-dir "/.brainyard"))
                           (config-agent-base-dir nil resolved-scope)))
                result (take-snapshot! base reason project-dir resolved-scope)]
            (cond
              ;; `take-snapshot!` skips when there's no file; surface that as
              ;; an error at the standalone-command boundary (the user asked
              ;; explicitly for a snapshot).
              (:skipped? result)
              {:ok? false :error (:reason result)
               :scope resolved-scope :requested-scope (:requested scope*)}

              (:ok? result)
              (assoc result :scope           resolved-scope
                     :requested-scope (:requested scope*))

              :else
              (assoc result :scope           resolved-scope
                     :requested-scope (:requested scope*))))))))
  :input-schema  [:map
                  [:reason [:string {:desc "Short why (used in filename slug)"}]]
                  [:scope {:optional true} [:keyword {:desc ":project | :user | :auto (default :auto)"}]]
                  [:base-dir {:optional true} [:string {:desc "Override base dir directly (advanced)"}]]
                  [:project-dir {:optional true} [:string {:desc "Override project dir; sets base-dir to <project>/.brainyard"}]]]
  :output-schema [:map
                  [:ok? :boolean]
                  [:path {:optional true} :string]
                  [:reason {:optional true} :string]
                  [:ts {:optional true} :string]
                  [:scope {:optional true} :keyword]
                  [:requested-scope {:optional true} :keyword]
                  [:rotated {:optional true} [:vector {:desc "Snapshot filenames pruned by rotation"} :string]]
                  [:error {:optional true} :string]])

(defn- snapshot-records
  "List snapshot records, newest-first."
  [base]
  (let [dir (snapshots-dir base)]
    (if-not (.isDirectory dir)
      []
      (->> (.listFiles dir)
           (filter (fn [^java.io.File f] (.isFile f)))
           (keep (fn [^java.io.File f]
                   (let [n (.getName f)]
                     (when-let [parsed (parse-snapshot-name n)]
                       (merge parsed
                              {:path        (.getPath f)
                               :filename    n
                               :size-bytes  (.length f)
                               :mtime       (.lastModified f)})))))
           (sort-by :ts)
           reverse
           vec))))

(defcommand config$list-snapshots
  "List the most-recent snapshots (newest-first) at the requested scope."
  (fn [& {:keys [limit scope base-dir project-dir]
          :or {limit snapshot-retention scope :auto}}]
    (let [dirs   (resolve-dirs project-dir)
          scope* (scope-summary dirs scope)]
      (if-not (:scope-resolvable? scope*)
        {:snapshots [] :error (:error scope*) :requested-scope (:requested scope*)}
        (let [resolved-scope (:scope scope*)
              base (or base-dir
                       (if project-dir
                         (config-agent-base-dir (str project-dir "/.brainyard"))
                         (config-agent-base-dir nil resolved-scope)))]
          {:snapshots       (vec (take limit (snapshot-records base)))
           :base-dir        base
           :scope           resolved-scope
           :requested-scope (:requested scope*)}))))
  :input-schema  [:map
                  [:limit {:optional true} [:int {:desc "Cap on results (default 20)"}]]
                  [:scope {:optional true} [:keyword {:desc ":project | :user | :auto (default :auto)"}]]
                  [:base-dir {:optional true} [:string {:desc "Override base dir directly"}]]
                  [:project-dir {:optional true} [:string {:desc "Override project dir; sets base-dir to <project>/.brainyard"}]]]
  :output-schema [:map
                  [:snapshots [:vector {:desc "Records newest-first"} :map]]
                  [:base-dir {:optional true} :string]
                  [:scope {:optional true} :keyword]
                  [:requested-scope {:optional true} :keyword]
                  [:error {:optional true} :string]])

;; ============================================================================
;; config$revert — restore a snapshot (snapshotting current first)
;; ============================================================================

(defn- find-snapshot
  "Resolve a snapshot record from :snapshot-path or :steps."
  [base {:keys [snapshot-path steps]}]
  (cond
    (string? snapshot-path)
    (let [f (io/file snapshot-path)]
      (cond
        (.isFile f) {:path (.getPath f) :filename (.getName f)}
        :else        {:error (str "Snapshot not found: " snapshot-path)}))

    (integer? steps)
    (let [recs (snapshot-records base)]
      (cond
        (or (< steps 1) (zero? (count recs)))
        {:error "No snapshots available."}

        (>= steps (inc (count recs)))
        {:error (str ":steps " steps " exceeds " (count recs) " available snapshots.")}

        :else
        (let [chosen (nth recs (dec steps))]
          (select-keys chosen [:path :filename]))))

    :else
    {:error "Provide :snapshot-path or :steps."}))

(defcommand config$revert
  "Restore a prior snapshot at the requested scope. Snapshots the CURRENT
   file first (so revert is itself reversible), then copies the chosen
   snapshot over config.edn.

   Scope is per-file: a `:project` revert restores the project's config.edn
   from a project-scope snapshot; `:user` operates entirely on user-scope."
  (fn [& {:keys [snapshot-path steps scope base-dir project-dir]
          :or {scope :auto}}]
    (let [dirs   (resolve-dirs project-dir)
          scope* (scope-summary dirs scope)]
      (if-not (:scope-resolvable? scope*)
        {:ok? false :error (:error scope*) :requested-scope (:requested scope*)}
        (let [resolved-scope (:scope scope*)
              base   (or base-dir
                         (if project-dir
                           (config-agent-base-dir (str project-dir "/.brainyard"))
                           (config-agent-base-dir nil resolved-scope)))
              chosen (find-snapshot base {:snapshot-path snapshot-path :steps steps})]
          (if (:error chosen)
            (assoc chosen :scope resolved-scope :requested-scope (:requested scope*))
            (let [{src-path :path src-name :filename} chosen
                  src-slug (some-> (parse-snapshot-name src-name) :reason)
                  pre      (take-snapshot! base
                                           (str "revert-to-" (or src-slug "snapshot"))
                                           project-dir
                                           resolved-scope)]
              (if-not (:ok? pre)
                {:ok? false :error (str "Pre-revert snapshot failed: " (:error pre))
                 :scope resolved-scope :requested-scope (:requested scope*)}
                (try
                  (let [info (config-file-info dirs resolved-scope)
                        dest (when info (io/file (:path info)))]
                    (when-not dest
                      (throw (ex-info (str "No config.edn at scope " resolved-scope
                                           " to revert into.")
                                      {:type :no-target})))
                    (io/copy (io/file src-path) dest)
                    (mulog/log ::config.revert
                               :restored-from src-path
                               :pre-revert-snapshot (:path pre)
                               :scope resolved-scope)
                    {:ok?                  true
                     :restored-from        src-path
                     :pre-revert-snapshot  (:path pre)
                     :dest                 (.getPath dest)
                     :scope                resolved-scope
                     :requested-scope      (:requested scope*)})
                  (catch Throwable t
                    {:ok? false :error (.getMessage t)
                     :scope resolved-scope :requested-scope (:requested scope*)})))))))))
  :input-schema  [:map
                  [:snapshot-path {:optional true} [:string {:desc "Absolute path to a snapshot .edn"}]]
                  [:steps {:optional true} [:int {:desc "1 = most-recent snapshot, 2 = next-older, ..."}]]
                  [:scope {:optional true} [:keyword {:desc ":project | :user | :auto (default :auto)"}]]
                  [:base-dir {:optional true} [:string {:desc "Override base dir directly"}]]
                  [:project-dir {:optional true} [:string {:desc "Override project dir; sets base-dir to <project>/.brainyard"}]]]
  :output-schema [:map
                  [:ok? :boolean]
                  [:restored-from {:optional true} :string]
                  [:pre-revert-snapshot {:optional true} :string]
                  [:dest {:optional true} :string]
                  [:scope {:optional true} :keyword]
                  [:requested-scope {:optional true} :keyword]
                  [:error {:optional true} :string]])

;; ============================================================================
;; Dossier helpers (mirrors explore.clj / update.clj patterns)
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
  [{:keys [question slug session-id config-path snapshots writes reverts
           started ended next-steps]}]
  (str "---\n"
       "agent: config-agent\n"
       "session-id: " (yaml-string (or session-id "")) "\n"
       "question: " (yaml-string (or question "")) "\n"
       "slug: " slug "\n"
       "started: " (or started (now-iso)) "\n"
       (when ended (str "ended: " ended "\n"))
       "config-path: " (yaml-string (or config-path "")) "\n"
       "snapshots: " (yaml-flow-vector (or snapshots [])) "\n"
       "writes: " (or writes 0) "\n"
       "reverts: " (or reverts 0) "\n"
       "next-steps: " (yaml-flow-vector (or next-steps [])) "\n"
       "---\n"))

(defcommand config$frontmatter
  "Build the YAML frontmatter for a config-agent dossier."
  (fn [& {:keys [question slug session-id config-path snapshots writes reverts
                 started ended next-steps]}]
    (cond
      (not (string? slug))
      {:error ":slug is required (string)"}

      :else
      {:frontmatter (build-dossier-frontmatter*
                     {:question question :slug slug :session-id session-id
                      :config-path config-path :snapshots snapshots
                      :writes writes :reverts reverts
                      :started started :ended ended
                      :next-steps next-steps})}))
  :input-schema  [:map
                  [:question {:optional true} :string]
                  [:slug [:string {:desc "Kebab-case slug for the conversation"}]]
                  [:session-id {:optional true} :string]
                  [:config-path {:optional true} :string]
                  [:snapshots {:optional true} [:vector {:desc "Snapshot paths"} :string]]
                  [:writes {:optional true} :int]
                  [:reverts {:optional true} :int]
                  [:started {:optional true} :string]
                  [:ended {:optional true} :string]
                  [:next-steps {:optional true} [:vector {:desc "Next steps"} :string]]]
  :output-schema [:map
                  [:frontmatter :string]
                  [:error {:optional true} :string]])

(defn- dossier-filename [slug]
  (str (now-ts) "-" slug ".md"))

(defcommand config$write
  "Write (or overwrite) a dossier file under .brainyard/agents/config-agent/dossiers/.
   Returns {:ok? :path :slug}."
  (fn [& {:keys [slug content base-dir]}]
    (cond
      (not (string? slug))    {:error ":slug is required (string)"}
      (not (string? content)) {:error ":content is required (string)"}

      :else
      (let [base                (config-agent-base-dir base-dir)
            dir                 (dossiers-dir base)
            _                   (.mkdirs dir)
            ^java.io.File file  (io/file dir (dossier-filename slug))]
        (spit file content)
        (mulog/log ::config.dossier-write
                   :path (.getPath file)
                   :slug slug
                   :bytes (count content))
        {:ok? true :path (.getPath file) :slug slug})))
  :input-schema  [:map
                  [:slug :string]
                  [:content :string]
                  [:base-dir {:optional true} :string]]
  :output-schema [:map
                  [:ok? :boolean]
                  [:path {:optional true} :string]
                  [:slug {:optional true} :string]
                  [:error {:optional true} :string]])

(defcommand config$index-append
  "Prepend a one-line entry to .brainyard/agents/config-agent/INDEX.md (newest-first)."
  (fn [& {:keys [path slug summary base-dir]}]
    (cond
      (not (string? path))    {:error ":path is required (string)"}
      (not (string? slug))    {:error ":slug is required (string)"}
      (not (string? summary)) {:error ":summary is required (string)"}

      :else
      (let [base     (config-agent-base-dir base-dir)
            file     (io/file base index-rel)
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
        (mulog/log ::config.dossier-index
                   :slug slug :path path)
        {:appended true :line line})))
  :input-schema  [:map
                  [:path :string]
                  [:slug :string]
                  [:summary :string]
                  [:base-dir {:optional true} :string]]
  :output-schema [:map
                  [:appended :boolean]
                  [:line {:optional true} :string]
                  [:error {:optional true} :string]])

;; ============================================================================
;; config$apply — the transactional write
;; ============================================================================

(defn- deep-merge
  "Recursive map merge. Non-map values from `b` win; vectors are replaced
   wholesale (not concatenated) so :permissions.allowed-dirs has predictable
   semantics."
  [a b]
  (cond
    (and (map? a) (map? b))
    (merge-with deep-merge a b)
    :else b))

(defn- ensure-vec [x]
  (cond
    (nil? x)        []
    (vector? x)     x
    (sequential? x) (vec x)
    :else           [x]))

(defn- selective-smoke-test
  "Verify the just-written config.edn round-trips: re-read it at `scope` and
   confirm it parses as EDN and equals the intended `expected` content (what
   we meant to write). A mismatch or parse error means the on-disk file is
   corrupt — config$apply auto-reverts from the pre-write snapshot.

   Also surfaces which sections changed (:touched), from `structural`.
   Per-section semantic probes (MCP connectivity, .env reachability) remain
   the agent layer's job via follow-up call-tool turns; this layer guarantees
   the file is readable and matches intent. Returns
   {:ok? bool :touched [str] (:reason <str> on failure)}."
  [{:keys [structural] :as _diff} dirs scope expected]
  (let [touched (vec (distinct (concat (keys (:adds structural))
                                       (keys (:changes structural)))))
        base    {:touched (mapv name touched)}]
    (try
      (let [reread (core-config/read-edn-config dirs scope)]
        (cond
          (not (map? reread))
          (assoc base :ok? false :reason "re-read of config.edn did not parse to a map")

          (not= reread expected)
          (assoc base :ok? false
                 :reason "written config.edn did not round-trip (re-read != intended)")

          :else
          (assoc base :ok? true)))
      (catch Throwable t
        (assoc base :ok? false
               :reason (str "re-read of config.edn failed: " (.getMessage t)))))))

(defn- append-dossier!
  "Append a one-section markdown block to the conversation's dossier file.
   Creates the file with frontmatter on first call. Returns dossier path."
  [project-dir scope slug entry]
  (let [base               (or (when project-dir (config-agent-base-dir (str project-dir "/.brainyard")))
                               (config-agent-base-dir nil (or scope :auto)))
        dir                (dossiers-dir base)
        _                  (.mkdirs dir)
        ;; Per-conversation file: today's slug. New conversation = new file.
        files              (filter (fn [^java.io.File f]
                                     (and (.isFile f)
                                          (str/ends-with? (.getName f) (str "-" slug ".md"))))
                                   (.listFiles dir))
        ^java.io.File file (or (first (sort-by (fn [^java.io.File f] (.lastModified f)) > files))
                               (io/file dir (dossier-filename slug)))
        section (str "\n## " (now-yyyy-mm-dd-hh-mm) " — apply\n"
                     "- reason: " (:reason entry) "\n"
                     "- snapshot: " (:snapshot-path entry) "\n"
                     "- writes: " (count (:writes entry)) "\n"
                     "- smoke-test: " (pr-str (:smoke-test entry)) "\n"
                     (when (seq (:diff entry))
                       (str "\n```diff\n" (:diff entry) "```\n")))]
    (if (.exists file)
      (spit file section :append true)
      (spit file (str (build-dossier-frontmatter*
                       {:question    (:question entry)
                        :slug        slug
                        :session-id  (:session-id entry)
                        :config-path (:config-path entry)
                        :snapshots   [(:snapshot-path entry)]
                        :writes      1
                        :reverts     0
                        :started     (now-iso)})
                      section)))
    (.getPath file)))

(defcommand config$apply
  "Transactional write at the requested scope: allowlist → schema → secret
   scan → confirm gate → mtime check → snapshot → write → smoke test
   (re-read + round-trip) → dossier append. A failed smoke test auto-reverts
   to the pre-write state and returns :stage :smoke-test-failed :reverted? true.
   Inline secret-shaped values are refused (:stage :secret-detected). A write
   touching a security-sensitive key (permissions / sandbox-mode /
   allowed-dirs) never self-confirms under :auto? — it always needs explicit
   :confirm? true.

   `:scope :project|:user|:auto` (default `:auto`) chooses which config.edn
   to write. `:auto` resolves to project when a project config.edn exists
   on disk, else user — see resolve-scope. The resolved scope is surfaced
   in the response so the LLM can be honest about where the write landed.

   Returns {:ok? :snapshot-path :path :scope :requested-scope :diff
            :smoke-test :dossier-path :mtime-after}.

   Refuses without :confirm? true unless :auto? true. The LLM should call
   this twice for an interactive flow: once with :confirm? false to see the
   diff, then once with :confirm? true after the user agrees on BOTH the
   diff AND the target scope/path."
  (fn [& {:keys [proposed reason confirm? auto? expected-mtime
                 question session-id scope project-dir]
          :or {scope :auto}}]
    (cond
      (not (map? proposed))
      {:error ":proposed must be a map"}

      (not (string? reason))
      {:error ":reason is required (string)"}

      :else
      (let [dirs    (resolve-dirs project-dir)
            scope*  (scope-summary dirs scope)]
        (if-not (:scope-resolvable? scope*)
          {:ok? false :stage :scope :error (:error scope*)
           :requested-scope (:requested scope*)}
          (let [resolved-scope (:scope scope*)
                raw-current    (core-config/read-edn-config dirs resolved-scope)
                ;; 0. Migrate any legacy `[:agent :max-iterations]` key into
                ;; `[:agent :config :*]` before merging. Log once on shape
                ;; relocation so operators can spot the auto-migration.
                migrated       (core-config/migrate-legacy-edn-shape raw-current)
                _              (when (:changed? migrated)
                                 (mulog/warn ::config-edn-shape-migrated
                                             :scope resolved-scope))
                current        (:config migrated)
                info    (config-file-info dirs resolved-scope)
                ;; 1. Allowlist + schema validation
                v       (validate-persisted proposed)
                ;; 1b. Secret scan + sensitive-key detection
                secrets    (secret-scan (pr-str proposed))
                sensitive? (sensitive-write? proposed)]
            (cond
              (not (:ok? v))
              {:ok? false
               :stage :validate
               :error (str "Validation failed (" (count (:errors v)) " issue(s)).")
               :errors (:errors v)
               :scope  resolved-scope
               :requested-scope (:requested scope*)
               :hint   (when (some #(= :allowlist-violation (:type %)) (:errors v))
                         "Use bootstrap$re-run-rung for LLM provider/model changes.")}

              ;; 1c. Refuse inline secrets — config.edn is read into the
              ;; process and :project scope is committed with the repo.
              (seq secrets)
              {:ok? false
               :stage :secret-detected
               :matches (mapv #(str (subs % 0 (min 8 (count %))) "…") secrets)
               :scope resolved-scope
               :requested-scope (:requested scope*)
               :hint "Secret-shaped value in config.edn. Reference it via an env var (e.g. in :mcp server env), not an inline literal. Put the secret in .env."}

              ;; 2. Confirm gate — a sensitive write (permissions / sandbox /
              ;; allowed-dirs) never self-confirms under :auto?; it always
              ;; needs explicit :confirm? true.
              (and (not confirm?) (or (not auto?) sensitive?))
              (let [diff (tool/invoke-tool :config$diff
                                           :proposed proposed
                                           :scope resolved-scope
                                           :project-dir project-dir)
                    target-path (or (:path info)
                                    (str (:config-dir scope*) "/config.edn"))]
                {:ok?             false
                 :stage           :unconfirmed
                 :sensitive?      sensitive?
                 :hint            (str "Surface :diff AND :path to the user, then re-call with "
                                       ":confirm? true. Resolved scope: " resolved-scope
                                       (when (= :auto (:requested scope*))
                                         " (auto-resolved). Confirm the user wants this scope.")
                                       (when (and auto? sensitive?)
                                         " NOTE: this touches a security-sensitive key (permissions / sandbox-mode / allowed-dirs); --auto cannot self-confirm it — explicit :confirm? true is required.")
                                       " Target: " target-path)
                 :diff            (:diff diff)
                 :structural      (:structural diff)
                 :scope           resolved-scope
                 :requested-scope (:requested scope*)
                 :path            target-path})

              ;; 3. mtime check (only if caller pinned an :expected-mtime)
              (and expected-mtime info (not= expected-mtime (:mtime info)))
              {:ok? false
               :stage :mtime-conflict
               :expected expected-mtime
               :actual (:mtime info)
               :scope resolved-scope
               :requested-scope (:requested scope*)
               :hint "config.edn changed since you read it. Re-read with config$read."}

              :else
              (let [base-dir-for-snap (if project-dir
                                        (config-agent-base-dir (str project-dir "/.brainyard"))
                                        (config-agent-base-dir nil resolved-scope))
                    ;; 4. Snapshot first (per-scope snapshot history).
                    ;; `take-snapshot!` returns {:ok? true :skipped? true}
                    ;; when there's no file yet at this scope — fine for a
                    ;; first-time write. A real failure (I/O, etc.) returns
                    ;; {:ok? false :error ...} and aborts the apply.
                    snap (take-snapshot! base-dir-for-snap reason
                                         project-dir resolved-scope)]
                (if-not (:ok? snap)
                  {:ok? false :stage :snapshot :error (:error snap)
                   :scope resolved-scope :requested-scope (:requested scope*)}
                  (try
                    (let [merged    (deep-merge current proposed)
                          stamped   (assoc merged :updated-at (now-iso))
                          pre-diff  (tool/invoke-tool :config$diff
                                                      :proposed proposed
                                                      :scope resolved-scope
                                                      :project-dir project-dir)
                          ;; 5. Write
                          path      (core-config/write-edn-config!
                                     dirs stamped resolved-scope)
                          ;; 5b. Invalidate the cached `!global-config` so the
                          ;; next setup-agent / get-config read picks up the
                          ;; freshly written file.
                          _         (core-config/invalidate-global-config!)
                          ;; 6. Smoke test — re-read the file + round-trip check.
                          smoke     (selective-smoke-test pre-diff dirs
                                                          resolved-scope stamped)]
                      (if-not (:ok? smoke)
                        ;; 7. Auto-revert on smoke failure: restore the
                        ;; pre-write state (delete on a first-time write, else
                        ;; copy the snapshot back), drop the bad cache.
                        (do
                          (if (:skipped? snap)
                            (.delete (io/file path))
                            (io/copy (io/file (:path snap)) (io/file path)))
                          (core-config/invalidate-global-config!)
                          (mulog/log ::config.apply.auto-revert
                                     :scope resolved-scope :reason reason
                                     :smoke smoke)
                          {:ok?             false
                           :stage           :smoke-test-failed
                           :reverted?       true
                           :smoke           smoke
                           :snapshot-path   (:path snap)
                           :scope           resolved-scope
                           :requested-scope (:requested scope*)
                           :error           "Smoke test failed; auto-reverted to pre-write state."})
                        ;; 8. Dossier + INDEX
                        (let [slug      (slugify reason 60)
                              dossier   (append-dossier!
                                         project-dir resolved-scope slug
                                         {:reason        reason
                                          :snapshot-path (:path snap)
                                          :smoke-test    smoke
                                          :diff          (:diff pre-diff)
                                          :question      question
                                          :session-id    session-id
                                          :config-path   path
                                          :writes        [path]})
                              _         (tool/invoke-tool
                                         :config$index-append
                                         :path dossier
                                         :slug slug
                                         :summary reason
                                         :base-dir base-dir-for-snap)
                              mtime'    (.lastModified (io/file path))]
                          (mulog/log ::config.apply
                                     :reason reason
                                     :path path
                                     :scope resolved-scope
                                     :snapshot (or (:path snap) :skipped)
                                     :smoke-ok? (:ok? smoke))
                          (cond-> {:ok?             true
                                   :snapshot-path   (:path snap)
                                   :path            path
                                   :diff            (:diff pre-diff)
                                   :structural      (:structural pre-diff)
                                   :smoke-test      smoke
                                   :dossier-path    dossier
                                   :mtime-after     mtime'
                                   :scope           resolved-scope
                                   :requested-scope (:requested scope*)}
                            (:skipped? snap) (assoc :snapshot-skipped true)))))
                    (catch Throwable t
                      {:ok? false :stage :write :error (.getMessage t)
                       :scope resolved-scope
                       :requested-scope (:requested scope*)}))))))))))
  :input-schema  [:map
                  [:proposed [:map {:desc "Partial config map deep-merged over current config.edn"}]]
                  [:reason [:string {:desc "Short why (used for snapshot slug + dossier)"}]]
                  [:confirm? {:optional true} [:boolean {:desc "Required true (unless :auto?) to actually write"}]]
                  [:auto? {:optional true} [:boolean {:desc "Bypass :confirm? gate (--auto mode)"}]]
                  [:expected-mtime {:optional true} [:int {:desc "If supplied, refuse the write when file mtime differs"}]]
                  [:question {:optional true} [:string {:desc "User question (recorded in dossier frontmatter)"}]]
                  [:session-id {:optional true} [:string {:desc "Agent session id (recorded in dossier)"}]]
                  [:scope {:optional true} [:keyword {:desc ":project | :user | :auto (default :auto)"}]]
                  [:project-dir {:optional true} [:string {:desc "Override project dir (tests)"}]]]
  :output-schema [:map
                  [:ok? :boolean]
                  [:stage {:optional true} :keyword]
                  [:snapshot-path {:optional true} :string]
                  [:snapshot-skipped {:optional true} :boolean]
                  [:path {:optional true} :string]
                  [:diff {:optional true} :string]
                  [:structural {:optional true} :map]
                  [:smoke-test {:optional true} :map]
                  [:dossier-path {:optional true} :string]
                  [:mtime-after {:optional true} :int]
                  [:scope {:optional true} :keyword]
                  [:requested-scope {:optional true} :keyword]
                  [:errors {:optional true} [:vector {:desc "Validation errors"} :map]]
                  [:hint {:optional true} :string]
                  [:error {:optional true} :string]
                  [:expected {:optional true} :int]
                  [:actual {:optional true} :int]])

;; ============================================================================
;; env-detect$rescan + bootstrap$re-run-rung (soft deps via requiring-resolve)
;;
;; Both target namespaces live outside the `agent` component (env-detect is a
;; sibling component; bootstrap-driver is the agent-tui base). Components must
;; not hard-depend on bases per Polylith, so we resolve at call time. When the
;; project that wires the agent (e.g. agent-tui-app) includes both on the
;; classpath, the commands work. In standalone agent tests they return a
;; structured error.
;; ============================================================================

(defn- resolve-or-nil [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

(defcommand env-detect$rescan
  "Re-run env-detect/detect-all. Pure read. Surfaces the new providers,
   pulled-models, network egress, and OS info to the caller.

   When the env-detect component is not on the classpath, returns an error."
  (fn [& _]
    (if-let [f (resolve-or-nil 'ai.brainyard.env-detect.interface/detect-all)]
      (let [detection (f)]
        (mulog/log ::config.rescan
                   :providers-available
                   (mapv :provider (filter :available? (:providers detection))))
        {:detection detection})
      {:error "env-detect is not on the classpath."}))
  :input-schema  [:map]
  :output-schema [:map
                  [:detection {:optional true} :map]
                  [:error {:optional true} :string]])

(defcommand bootstrap$re-run-rung
  "Programmatic rerun of one bootstrap ladder rung. The ONLY path through
   which :llm.default-provider / :default-model may change while config-agent
   is running (config$apply rejects those keys).

   Persists the new :llm + :bootstrap blocks to config.edn on success.
   Takes a pre-write snapshot for revert safety (mirrors config$apply).

   Rungs (a/b/c/d/f) execute without prompts. Rung (e) (install/pull) requires
   :auto? true — otherwise returns {:requires-interactivity? true} and the
   agent is expected to advise the user to drop --auto and re-run.

   The new LLM defaults take effect on the NEXT bb tui session — the running
   agent (which made this call) keeps using its already-configured LM."
  (fn [& {:keys [rung provider model auto? project-dir]}]
    (cond
      (not (keyword? rung))
      {:error ":rung is required (keyword: :a :b :c :d :e :f)"}

      (= rung :g)
      {:error "Rung :g is the stop sentinel, not a rerun target."}

      :else
      (if-let [run! (resolve-or-nil
                     'ai.brainyard.agent-tui.bootstrap-driver/re-run-rung!)]
        ;; Pre-write snapshot for revert safety. Only worth taking when a
        ;; config.edn already exists; fresh-install case has nothing to
        ;; preserve. Scope is :auto — bootstrap-driver writes to whichever
        ;; scope the wizard targeted (project if in a repo, else user).
        (let [snap (when (config-file-info (resolve-dirs project-dir) :auto)
                     (take-snapshot!
                      (config-agent-base-dir
                       (when project-dir (str project-dir "/.brainyard")))
                      (str "pre-rerun-rung-" (name rung))
                      project-dir
                      :auto))
              r (run! {:rung rung :provider provider :model model :auto? auto?}
                      {:callbacks {}})
              r (cond-> r
                  (and (:ok? r) snap (:ok? snap))
                  (assoc :pre-rerun-snapshot (:path snap)))]
          (mulog/log ::config.re-run-rung
                     :rung rung :ok? (:ok? r)
                     :requires-interactivity? (:requires-interactivity? r)
                     :config-path (:config-path r))
          r)
        {:error "bootstrap-driver not on classpath (agent-tui base not loaded)."})))
  :input-schema  [:map
                  [:rung [:keyword {:desc ":a :b :c :d :e :f"}]]
                  [:provider {:optional true} [:keyword {:desc "Force a specific provider (rung b)"}]]
                  [:model {:optional true} [:string {:desc "Force a specific model"}]]
                  [:auto? {:optional true} [:boolean {:desc "Allow rung (e) install/pull without prompts"}]]
                  [:project-dir {:optional true} [:string {:desc "Override project dir (tests)"}]]]
  :output-schema [:map
                  [:ok? :boolean]
                  [:requires-interactivity? {:optional true} :boolean]
                  [:chosen {:optional true} :map]
                  [:detection {:optional true} :map]
                  [:result {:optional true} :map]
                  [:config-path {:optional true} :string]
                  [:pre-rerun-snapshot {:optional true} :string]
                  [:delta-config {:optional true} :map]
                  [:reason {:optional true} :string]
                  [:error {:optional true} :string]])

;; ============================================================================
;; Public roster
;; ============================================================================

(def config-helpers
  "Vars for config-agent's :agent-tools concat."
  [#'config$slug
   #'config$read
   #'config$diff
   #'config$snapshot
   #'config$list-snapshots
   #'config$revert
   #'config$apply
   #'config$frontmatter
   #'config$write
   #'config$index-append
   #'env-detect$rescan
   #'bootstrap$re-run-rung])
