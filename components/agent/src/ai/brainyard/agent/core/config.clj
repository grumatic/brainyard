;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.config
  "Configuration + directory resolution for agents.

   Three concerns live here:
   1. `config-schema` — single registry of all configurable items, each
      declaring its type and default value.
   2. Unified config API — `get-config` / `set-config!` resolve every read
      and write through one precedence chain (lowest → highest):
        schema default (`:default` or lazy `:default-fn`)
          < !global-config (.brainyard/config.edn)
          < session-config (`(:config @!session)`)
          < per-agent override (`(:config @st-memory-init)`)
      Per-agent overrides are seeded from defagent `:config-extra` +
      caller options; schema-shape keys (in `config-keys`) flow into this
      slot automatically at `setup-agent` time. `set-config!` always
      persists to `.brainyard/config.edn`; with an agent arg it also
      writes the per-agent override.
   3. Directory resolution — user/project/working dirs, `.brainyard/`
      config directories, and helpers to read .brainyard/ files
      (BRAINYARD.md, config.edn, SKILL.md, etc.)."
  (:require [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file FileSystems Path]))

;; ============================================================================
;; Config Schema
;; ============================================================================

(declare resolve-dirs user-config-dir default-allowed-dirs)

(def config-schema
  "Config key → {:type <coerce-value type-str> :default <value>}

   Each entry declares EITHER `:default` (a static value) OR `:default-fn`
   (a 0-arity callable invoked lazily by `get-config` as the final fallback).
   Use `:default-fn` for runtime-resolved values that depend on env vars,
   system properties, or other components — `(clj-llm/get-default-lm)`,
   `(System/getenv ...)`, etc."
  {:max-output-tokens       {:type "integer" :default 0}
   :show-llm-streaming      {:type "boolean" :default false}
   ;; Token-budget enforcement on assembled prompt context (coact)
   :enable-context-budget      {:type "boolean" :default true}
   :context-budget-safety-ratio {:type "number" :default 0.10}
   ;; CoAct system-context: include the full sandbox function directory
   ;; (categories + signatures for ALL bound callables). When false, the
   ;; system prompt shows only a compact category index instead.
   :include-function-directory {:type "boolean" :default false}
   ;; RLM context management
   :max-context-tokens         {:type "integer" :default 128000}
   ;; Inline cap (chars) for a single LLM-facing result/output before it is
   ;; truncated to a recoverable temp file (head 70% + tail 20%). ~8k tokens.
   ;; Unified knob for coact/react iteration-field truncation.
   :max-output-chars           {:type "integer" :default 32000}
   ;; TUI display-block collapsing (display-only; never reaches the LLM).
   ;; Collapsed view shows :max-collapsed-lines; expanding (Enter) splices in
   ;; up to :max-expanded-lines more (Ctrl-O opens the full content).
   :max-collapsed-lines        {:type "integer" :default 20}
   :max-expanded-lines         {:type "integer" :default 200}
   :enable-sandbox-persistence {:type "boolean" :default true}
   :enable-budget-monitoring   {:type "boolean" :default false}
   ;; Cross-turn context compaction. Gated (along with the per-iteration /
   ;; turn-init budget reducer) by the single `:enable-context-budget` knob;
   ;; the after-turn auto-compaction safety valve fires only when the
   ;; estimated context overflows `:max-context-tokens`, shrinking carryover
   ;; to `:compaction-target-ratio` × max-context-tokens.
   :compaction-target-ratio    {:type "number"  :default 0.2}
   ;; RLM iteration / evaluation / refinement
   :max-iterations             {:type "integer" :default 100}
   :max-refinements            {:type "integer" :default 0}
   :eval-lm                    {:type "string"  :default "claude-code:opus"}
   ;; Sub-LLM query configuration (llm-query, rlm-query in sandbox)
   :sub-lm-config              {:type "string"  :default nil}
   :llm-query-max-depth        {:type "integer" :default 1}
   ;; Auto-background deadline (ms) for LLM-emitted code blocks (coact).
   ;; Each code block runs as a synchronous foreground task; if it has not
   ;; finished after this many ms, await-task detaches into background
   ;; display mode and returns a :status :pending snapshot. The work keeps
   ;; running; a later iteration harvests the resolved result. The LLM can
   ;; poll via `task$detail` or stop via `task$cancel` at any time.
   :auto-background-timeout-ms {:type "integer" :default 120000}
   ;; Fast-eval threshold (ms). Clojure code blocks are evaluated inline
   ;; first; only promoted to a tracked task if they exceed this timeout.
   ;; Set to 0 to disable (always create a task). Does not apply to bash.
   :fast-eval-timeout-ms       {:type "integer" :default 10000}
   ;; Whether the BT iteration loop blocks waiting for in-flight tasks.
   ;; When false (default), pending tasks flow to the next iteration and
   ;; the LLM polls via task$wait. When true, coact-await-pending-action
   ;; blocks up to :hold-max-wait-ms before falling through.
   :enable-iteration-hold       {:type "boolean" :default false}
   ;; Max time (ms) the iteration hold waits for pending tasks before
   ;; falling through. Only applies when :enable-iteration-hold is true.
   :hold-max-wait-ms            {:type "integer" :default 300000}
   ;; Default timeout (ms) for tasks launched via task$run when the caller
   ;; doesn't pass an explicit :timeout. Applies to both :bash and :tool
   ;; jobs, and to the foreground waiter's own deadline.
   :task-timeout-ms            {:type "integer" :default 120000}
   ;; Max inline retries when a ThinkActCode call returns an empty result
   ;; (blank reasoning + no channel) — the signature of a transient CLI
   ;; hiccup (rate limit / nonzero exit / empty stream). The repair path
   ;; re-runs the LLM call up to this many times before falling back to the
   ;; no-action nudge. Set to 0 to disable retries.
   :empty-result-max-retries   {:type "integer" :default 2}
   ;; Base delay (ms) for exponential backoff between empty-result retries.
   ;; Delay for attempt N is base * 2^(N-1): attempt 1 waits base, attempt 2
   ;; waits 2*base, etc. Gives a rate-limited backend time to recover.
   :empty-result-retry-base-ms {:type "integer" :default 1000}
   ;; On-disk artifact GC (ai.brainyard.agent.gc). Sweeps run async on
   ;; :agent.session/created (1h in-process throttle) and via task$sweep.
   ;; :tasks  — keep newest N task-N dirs OR ones younger than D days; union.
   ;; :coact-scratch — drop coact-agent/scratch/ entries older than H hours.
   ;; :sandbox-cache — cap clj-sandbox/{truncation,file-backed}/ by count,
   ;;                  bytes, and age (oldest dropped first).
   :task-retention-count        {:type "integer" :default 100}
   :task-retention-days         {:type "integer" :default 7}
   :coact-scratch-max-age-hours {:type "integer" :default 24}
   :sandbox-cache-max-files     {:type "integer" :default 200}
   :sandbox-cache-max-bytes     {:type "integer" :default 52428800}
   :sandbox-cache-max-age-days  {:type "integer" :default 7}
   :enable-finalize-answer     {:type "boolean" :default false}
   ;; Post-session analytics
   :enable-analytics           {:type "boolean" :default false}
   ;; Memory capture pipeline — when true, root agents auto-start the
   ;; capture pipeline on creation and auto-stop it when the last agent
   ;; sharing the memory manager closes. Subscribes to :agent.ask/pre,
   ;; :agent.ask/post, :agent.tool-use/post, :agent.code-eval/post, :agent/exception hooks
   ;; and feeds events through the S1 parser into L2.
   :enable-memory-capture      {:type "boolean" :default false}
   ;; Memory-agent end-of-turn essence capture — when true, registers
   ;; a :agent.ask/post hook that fire-and-forget calls memory-agent
   ;; with :op :essence after each turn finishes. The LLM extracts
   ;; 0..3 essences worth carrying beyond the turn; high-confidence
   ;; facts auto-promote to L3. Off by default; opt-in per agent type
   ;; via :config-extra on the defagent (root coact-agent /
   ;; research-agent enable it; specialists stay off).
   :enable-memory-essence      {:type "boolean" :default false}
   ;; Subagent call management
   :max-agent-call-depth       {:type "integer" :default 3}
   :enable-subagent-calls      {:type "boolean" :default true}
   ;; Global kill-switch for LLM-authored user hooks (.brainyard/hooks/*.edn,
   ;; registered via hook-agent). When false, persisted user hooks stay on disk
   ;; but do not fire. Read by ai.brainyard.agent.common.user-hooks.
   :enable-user-hooks          {:type "boolean" :default true}
   ;; TUI thinking live block: when true, removes the block from scrollback when
   ;; thinking stops; when false, freezes it so it remains as scrollback history.
   :dispose-think-block        {:type "boolean" :default true}
   ;; TUI iteration live block: when true, removes the block from scrollback when
   ;; the iteration ends; when false, freezes it so it remains as scrollback history.
   :dispose-iteration-block    {:type "boolean" :default false}
   ;; TUI task live block: when true, removes the block from scrollback when
   ;; the task ends; when false, freezes it so it remains as scrollback history.
   :dispose-task-block         {:type "boolean" :default true}
   ;; TUI sub-agent live block: when true, removes the block from scrollback when
   ;; the sub-agent call ends; when false, freezes it so it remains as scrollback history.
   :dispose-agent-block        {:type "boolean" :default true}
   ;; TUI resume: how many trailing bytes of the persisted `:stream` scrollback
   ;; to replay into the pane on `--resume` (NOT characters — raw ANSI bytes,
   ;; decoded UTF-8). Bounded by the on-disk cap (5 MiB/stream × rotations).
   ;; Default 10 MiB (10 * 1024 * 1024).
   :resume-scrollback-bytes    {:type "integer" :default 10485760}
   ;; Mid-turn re-recall (M8a). When true, the :agent.tool-use/post hook
   ;; in context-actions extracts novel entity terms from each tool
   ;; result, fires a refined recall query, and merges new hits into
   ;; :recalled-memory. Off by default — flip per turn via
   ;; agent-runtime$config :key "enable-mid-turn-recall" :value "true".
   :enable-mid-turn-recall     {:type "boolean" :default false}
   ;; Cross-turn tool-result cache (M8b). Default 0 disables cache
   ;; lookup; set to a positive number of seconds to enable. Look-ups
   ;; only fire for tools tagged as read-style via the
   ;; :tool-cache-readers config key (vector of tool-id strings).
   :tool-cache-ttl             {:type "integer" :default 0}
   :tool-cache-readers         {:type "vector"  :default []}
   ;; Explore-agent auto-persist (per-turn tunable)
   :explore-persist-threshold  {:type "integer" :default 1000}
   :explore-auto-persist       {:type "boolean" :default true}
   ;; Specialist-agent auto-finalize gates
   :workflow-auto-finalize     {:type "boolean" :default true}
   :research-auto-finalize     {:type "boolean" :default true}
   ;; BT loop / context cadence
   :rebudget-every-n-iter      {:type "integer" :default 10}
   :conversation-limit         {:type "integer" :default 20}
   :recall-limit               {:type "integer" :default 10}
   ;; ACP agent backend
   :acp-backend                {:type "keyword" :default :stub}
   :acp-backend-opts           {:type "object"  :default {}}
   :acp-timeout-ms             {:type "integer" :default 600000}
   :acp-permission-timeout-ms  {:type "integer" :default 120000}
   ;; Parent-handoff trail depth
   :parent-trail-k             {:type "integer" :default 3}
   ;; ReAct loop mode ("single" | "multi")
   :react-loop-mode            {:type "string"  :default "single"}
   ;; ReAct section-budget compaction floors
   :react-keep-thoughts-n      {:type "integer" :default 3}
   :react-keep-observations-n  {:type "integer" :default 3}
   :react-keep-iterations-n    {:type "integer" :default 3}
   ;; Agent's effective working directory. Resolved lazily from
   ;; `user.dir` when no override is set; callers in the agent pipeline
   ;; should read via `(config/working-dir agent)`.
   :working-dir                {:type "string"
                                :default-fn #(System/getProperty "user.dir")}
   ;; Agent's main LM config map ({:provider :model :max-tokens ...}).
   ;; Default falls through to `(clj-llm/get-default-lm)` which honors
   ;; the LLM_PROVIDER env var.
   :lm-config                  {:type "object"
                                :default-fn #(clj-llm/get-default-lm)}
   ;; Agent's resolved dirs map ({:user-dir :project-dir :working-dir}).
   ;; Typically seeded into session-config at startup; falls back to
   ;; `resolve-dirs` when nothing in the chain provides it.
   :dirs                       {:type "object"
                                :default-fn #(resolve-dirs)}
   ;; Tavily web-search API key. Lazy default reads `TAVILY_API_KEY` env.
   :tavily-api-key             {:type "string"
                                :default-fn #(System/getenv "TAVILY_API_KEY")}
   ;; Allow-list of directories for filesystem-touching tools (bash, read,
   ;; write, grep, task$run). Lazy default falls through to
   ;; `default-allowed-dirs` (/tmp + project-dir + user-config-dir).
   :allowed-dirs               {:type "array"
                                :default-fn #(default-allowed-dirs)}
   ;; Permission-prompt policy for sensitive tool ops. One of
   ;; :auto-approve | :ask-each-time | :deny-by-default. Surfaced as
   ;; `[:permissions :mode]` in the persisted EDN; bridged into
   ;; `!global-config` by `load-global-config!`.
   :permission-mode            {:type "keyword" :default :ask-each-time}
   ;; clj-nrepl-eval bootstrap (docs/design/clj-nrepl-eval.md §5).
   ;; The in-process nREPL server backing `code$eval :backend :nrepl` is
   ;; off by default. Operators enable it durably by setting
   ;; `[:agent :config :nrepl-enabled?] true` in .brainyard/config.edn,
   ;; or transiently via BRAINYARD_NREPL_ENABLED=true (the env-fallback
   ;; layer below). Same precedence applies to :nrepl-port (0 = ephemeral)
   ;; and :nrepl-grant (e.g. "read-only:15m" or "mutate:5m").
   :nrepl-enabled?             {:type "boolean"
                                :default-fn #(= "true" (System/getenv "BRAINYARD_NREPL_ENABLED"))}
   :nrepl-port                 {:type "integer"
                                :default-fn #(or (some-> (System/getenv "BRAINYARD_NREPL_PORT")
                                                         parse-long)
                                                 0)}
   :nrepl-grant                {:type "string"
                                :default-fn #(System/getenv "BRAINYARD_NREPL_GRANT")}
   ;; Clojure code-execution backend for ```clojure blocks in CoAct agents.
   ;; :sandbox — SCI sandbox (default, safe for arbitrary agents).
   ;; :nrepl   — live brainyard JVM via clj-nrepl (debug-agent; needs server).
   ;; Per-agent override layer (lifecycle hook → `:!state :st-memory-init
   ;; :config`); not persisted to .brainyard/config.edn.
   :clj-backend                {:type "keyword" :default :sandbox}})

(def config-keys (set (keys config-schema)))

;; ============================================================================
;; Internal Constants (not user-configurable, but centralized to avoid magic numbers)
;; ============================================================================

(defn default-allowed-dirs
  "Default allow-list for filesystem operations.
   Returns a vector containing /tmp plus the resolved :project-dir and
   user-config-dir (~/.brainyard), with nils dropped and duplicates removed.

   Arity-0 resolves dirs at call time via `resolve-dirs`.
   Arity-1 accepts a dirs map (e.g. from a live session) so the caller
   avoids re-resolving."
  ([] (default-allowed-dirs (resolve-dirs)))
  ([dirs]
   (->> ["/tmp" (:project-dir dirs) (user-config-dir dirs)]
        (remove nil?)
        distinct
        vec)))

(def default-config
  "Map of schema defaults — one entry per `config-schema` key that declares
   a static `:default`. Entries that declare `:default-fn` instead are
   omitted here; `get-config` invokes the fn lazily as the final fallback."
  (reduce-kv (fn [m k entry]
               (if (contains? entry :default)
                 (assoc m k (:default entry))
                 m))
             {}
             config-schema))

;; ============================================================================
;; Coercion
;; ============================================================================

(defn coerce-config-value
  "Coerce a value string using the config key's declared type via tool/coerce-value."
  [k value-str]
  (if-let [{:keys [type]} (get config-schema k)]
    (tool/coerce-value value-str type)
    value-str))

(defn valid-config-value?
  "True if `v` matches the declared type for `config-schema` key `k`.
   Unknown keys → false. Used by `common/config` to validate leaves under
   `[:agent :config :*]` in `config$apply` writes."
  [k v]
  (if-let [{:keys [type]} (get config-schema k)]
    (case type
      "string"  (or (string? v) (keyword? v))   ;; legacy tolerance; prefer "keyword" for keyword-valued knobs
      "keyword" (keyword? v)
      "integer" (integer? v)
      "number"  (number? v)
      "boolean" (boolean? v)
      ("array" "vector") (sequential? v)
      "object"  (map? v)
      true)  ;; unknown declared type → accept
    false))

(defn migrate-legacy-edn-shape
  "Relocate the legacy persisted positions for runtime knobs into the
   unified `[:agent :config :*]` subtree:
     [:agent :max-iterations] → [:agent :config :max-iterations]
   Idempotent: a map with the new shape only is returned unchanged.
   Returns {:config <migrated-map> :changed? bool}."
  [cfg]
  (let [agent-map  (get cfg :agent {})
        legacy-mi  (when (contains? agent-map :max-iterations)
                     (:max-iterations agent-map))
        changed?   (some? legacy-mi)]
    (if-not changed?
      {:config cfg :changed? false}
      (let [under-cfg (get agent-map :config {})
            under-cfg (cond-> under-cfg
                        legacy-mi (assoc :max-iterations legacy-mi))
            agent-map (-> agent-map
                          (dissoc :max-iterations)
                          (assoc :config under-cfg))]
        {:config (assoc cfg :agent agent-map) :changed? true}))))

;; ============================================================================
;; Directory Resolution
;; ============================================================================

(defn find-git-root
  "Walk upward from start-dir looking for .git directory.
   Returns canonical path string, or nil."
  [start-dir]
  (loop [dir (io/file start-dir)]
    (when dir
      (if (.isDirectory (io/file dir ".git"))
        (.getCanonicalPath dir)
        (recur (.getParentFile dir))))))

(defn resolve-working-dir
  "Resolve working directory — always the JVM's `user.dir` system property
   (i.e. the actual process cwd). Distinct from `project-dir` by design:
   the three scopes (user / project / working) carry separate meanings
   and should each report truth, not paper over each other. For 'where
   should `.brainyard/` artifacts live?' see `project-dir` instead."
  []
  (System/getProperty "user.dir"))

(defn resolve-project-dir
  "Resolve project directory:
   1. BRAINYARD_PROJECT_DIR env (explicit override)
   2. Git root found walking up from working-dir
   3. working-dir itself (current working directory fallback)

   The working-dir fallback means project-dir is ALWAYS resolvable as long
   as we have a working-dir — there is no nil case in normal operation.
   This keeps `*-agent/` artifact dirs sane even outside a git repo:
   they land under `<cwd>/.brainyard/<agent>/` instead of silently leaking
   into the user's home dir."
  [working-dir]
  (or (System/getenv "BRAINYARD_PROJECT_DIR")
      (find-git-root working-dir)
      working-dir))

(defn resolve-dirs
  "Compute full directory info map."
  []
  (let [user-dir    (System/getProperty "user.home")
        working-dir (resolve-working-dir)
        project-dir (resolve-project-dir working-dir)]
    {:user-dir    user-dir
     :project-dir project-dir
     :working-dir working-dir}))

(defn user-config-dir
  "Derive the user-level .brainyard/ config dir from a dirs map."
  [dirs]
  (when-let [ud (:user-dir dirs)]
    (str ud "/.brainyard")))

(defn project-config-dir
  "Derive the project-level .brainyard/ config dir from a dirs map, or nil."
  [dirs]
  (when-let [pd (:project-dir dirs)]
    (str pd "/.brainyard")))

(defn ensure-config-dirs!
  "Create .brainyard/ directories if they don't exist. Returns dirs unchanged."
  [dirs]
  (when-let [ucd (user-config-dir dirs)]
    (.mkdirs (io/file ucd)))
  (when-let [pcd (project-config-dir dirs)]
    (.mkdirs (io/file pcd)))
  dirs)

(defn init-dirs!
  "Resolve directories and ensure .brainyard/ dirs exist."
  []
  (ensure-config-dirs! (resolve-dirs)))

;; ============================================================================
;; Config File Helpers (.brainyard/ reads & writes)
;; ============================================================================

(defn- glob-match?
  "Check if filename matches a glob pattern (e.g., \"*.md\", \"*/SKILL.md\")."
  [^String pattern ^String relative-path]
  (let [matcher (.getPathMatcher (FileSystems/getDefault) (str "glob:" pattern))]
    (.matches matcher (Path/of relative-path (into-array String [])))))

(defn- list-files-recursive
  "List files in a directory, returning relative paths."
  [^String dir-path]
  (let [dir (File. dir-path)]
    (when (.isDirectory dir)
      (->> (file-seq dir)
           (filter #(.isFile ^File %))
           (mapv (fn [^File f]
                   (subs (.getPath f) (inc (count dir-path)))))))))

(defn list-config-files
  "List files in .brainyard/ directories.
   Returns {:user-files [...] :project-files [...]} with relative paths.
   Optional pattern filter (e.g., \"*.md\", \"*/SKILL.md\")."
  [dirs & {:keys [pattern] :or {pattern "**"}}]
  (let [filter-fn (if (= pattern "**")
                    (constantly true)
                    #(glob-match? pattern %))
        user-files (when-let [ucd (user-config-dir dirs)]
                     (->> (list-files-recursive ucd)
                          (filterv filter-fn)))
        project-files (when-let [pcd (project-config-dir dirs)]
                        (->> (list-files-recursive pcd)
                             (filterv filter-fn)))]
    {:user-files    (or user-files [])
     :project-files (or project-files [])}))

(defn read-config-file
  "Read a file from .brainyard/ directories.
   Searches project config dir first, then user config dir.
   Returns {:content str :source :project|:user :path str} or {:error str}."
  [dirs relative-path]
  (let [try-read (fn [config-dir source]
                   (when config-dir
                     (let [f (File. ^String config-dir ^String relative-path)]
                       (when (.isFile f)
                         {:content (slurp f)
                          :source  source
                          :path    (.getPath f)}))))]
    (or (try-read (project-config-dir dirs) :project)
        (try-read (user-config-dir dirs) :user)
        {:error (str "File not found: " relative-path
                     " (searched .brainyard/ in project and user directories)")})))

(defn load-brainyard-instructions
  "Load BRAINYARD.md from both user and project config dirs.
   Returns {:user-instructions str-or-nil :project-instructions str-or-nil}."
  [dirs]
  (let [read-md (fn [config-dir]
                  (when config-dir
                    (let [f (File. ^String config-dir "BRAINYARD.md")]
                      (when (.isFile f)
                        (str/trim (slurp f))))))]
    {:user-instructions    (read-md (user-config-dir dirs))
     :project-instructions (read-md (project-config-dir dirs))}))

;; ============================================================================
;; EDN Config Read/Write (.brainyard/config.edn)
;; ============================================================================

(defn resolve-scope-dir
  "Resolve a `:scope` keyword to a concrete .brainyard/ directory path.

   Scope values:
     :project — return project-config-dir; nil only when :project-dir was
                explicitly set to nil in the dirs map (rare; `resolve-dirs`
                falls back to working-dir so the normal case is non-nil)
     :user    — return user-config-dir; nil only when :user-dir is nil
     :auto    — return project-config-dir if config.edn exists at that scope,
                else fall back to user-config-dir

   Callers that need to distinguish 'real project (git root)' from
   'cwd-fallback project' should inspect `resolve-project-dir`'s sources
   directly; this helper treats both identically."
  [dirs scope]
  (case scope
    :project (project-config-dir dirs)
    :user    (user-config-dir dirs)
    :auto    (let [pcd (project-config-dir dirs)]
               (if (and pcd (.isFile (File. ^String pcd "config.edn")))
                 pcd
                 (user-config-dir dirs)))
    nil))

(defn resolve-scope
  "Resolve `:scope :project|:user|:auto` against a dirs map, returning a map
   describing the concrete scope chosen, or nil when the request can't be
   honored.

   Returns:
     {:scope :project|:user :config-dir <str> :requested <scope-arg>}
     or nil when:
       - :scope :project is requested but project-dir is nil in the dirs
         map (rare — `resolve-dirs` falls back to working-dir, so the only
         way to hit nil is to construct a dirs map explicitly with :project-dir
         nil, e.g. in tests)
       - :scope is an unknown keyword

   :auto resolves to :project iff that scope's config.edn already exists on
   disk; otherwise it resolves to :user. The `:requested` key preserves the
   original argument so callers can distinguish 'auto resolved to project'
   from 'caller asked for project explicitly'."
  [dirs scope]
  (let [scope (or scope :auto)]
    (case scope
      :project (when-let [pcd (project-config-dir dirs)]
                 {:scope :project :config-dir pcd :requested :project})
      :user    (when-let [ucd (user-config-dir dirs)]
                 {:scope :user :config-dir ucd :requested :user})
      :auto    (let [pcd (project-config-dir dirs)
                     project-file? (and pcd (.isFile (File. ^String pcd "config.edn")))
                     ucd (user-config-dir dirs)]
                 (cond
                   project-file? {:scope :project :config-dir pcd :requested :auto}
                   ucd           {:scope :user    :config-dir ucd :requested :auto}
                   :else         nil))
      nil)))

;; ============================================================================
;; .brainyard/ subdir scope policy
;; ============================================================================

(def subdir-scope-policy
  "Which scopes each `.brainyard/<subdir>` entry is allowed at.

   Membership:
     :user-only — lives ONLY under ~/.brainyard/ (per-account state that
                  must not leak across projects).
     :project-only — lives ONLY under <project>/.brainyard/ (per-repo
                     artifacts that should travel with the codebase).
     :both — may exist at either scope. The two scopes are independent
             files/dirs; the runtime does not auto-merge them.

   The map is explicit for named entries; anything ending in `-agent` not
   listed here is treated as :project-only by `subdir-allowed-scopes`."
  {;; user-only: per-account state
   "memory"        :user-only
   "sessions"      :user-only
   "logs"          :user-only

   ;; both scopes: dual-scope files/dirs the runtime reads from either layer
   "config.edn"    :both
   "BRAINYARD.md"  :both
   "skills"        :both

   ;; dual-scope agents: artifacts mirror the scope of the file they edit
   ;; (config.edn for config-agent, BRAINYARD.md for init-agent). Agent
   ;; artifact dirs live under .brainyard/agents/<name>/.
   "agents/config-agent"  :both
   "agents/init-agent"    :both

   ;; project-only: per-repo artifacts that aren't agent-named
   "charts"             :project-only
   "temp/clj-sandbox"   :project-only
   "tasks"              :project-only})

(defn subdir-allowed-scopes
  "Return the set of scopes (`:project`, `:user`) at which the given
   `.brainyard/<name>` entry is allowed. Defaults to :project-only for
   `*-agent` names not explicitly listed in `subdir-scope-policy`, and to
   `#{:user :project}` for unknown names (permissive — caller decides)."
  [^String name]
  (case (get subdir-scope-policy name)
    :user-only    #{:user}
    :project-only #{:project}
    :both         #{:user :project}
    nil           (if (.endsWith name "-agent")
                    #{:project}
                    #{:user :project})))

(defn subdir-scope-allowed?
  "Predicate: is `scope` permitted for the `.brainyard/<name>` entry?"
  [^String name scope]
  (contains? (subdir-allowed-scopes name) scope))

(defn brainyard-subdir
  "Resolve `.brainyard/<name>` at the requested scope, respecting
   `subdir-scope-policy`. Returns the absolute path string, or nil when:
     - scope is :project but project-config-dir is nil in dirs
     - scope is :user but user-config-dir is nil in dirs
     - scope is not in (subdir-allowed-scopes name) — i.e. caller asked
       for a forbidden scope (e.g. memory at :project)

   Does NOT create the directory. Callers .mkdirs as needed."
  [dirs ^String name scope]
  (when (subdir-scope-allowed? name scope)
    (when-let [base (case scope
                      :project (project-config-dir dirs)
                      :user    (user-config-dir dirs)
                      nil)]
      (str base "/" name))))

(defn brainyard-subdir!
  "Resolve `.brainyard/<name>` at the requested scope (per `brainyard-subdir`)
   and ENSURE the directory exists. Returns the absolute path string, or
   nil when the scope is forbidden or the base dir can't be resolved.

   Convenience for callers that always want the dir to exist before
   writing into it (logs, scratch dirs, caches). Inert when the dir
   already exists."
  [dirs ^String name scope]
  (when-let [p (brainyard-subdir dirs name scope)]
    (.mkdirs (io/file p))
    p))

(defn read-edn-config
  "Read and parse config.edn from project or user config dir.

   Optional `:scope` is `:project | :user | :auto` (default `:auto`):
     :auto    — project if its config.edn exists on disk, else user
     :project — project only (returns {} if not present)
     :user    — user only
   When `:scope :project` is requested but no project-dir is resolvable,
   returns {} (callers wanting an error should use `resolve-scope` first).

   The arity-1 form preserves the legacy 'project-first, user-fallback'
   behavior used by non-config-agent callers (TUI startup, etc.) and
   matches `write-edn-config!`'s search order."
  ([dirs] (read-edn-config dirs :auto))
  ([dirs scope]
   (if-let [dir (resolve-scope-dir dirs scope)]
     (let [f (File. ^String dir "config.edn")]
       (if (.isFile f)
         (edn/read-string (slurp f))
         {}))
     {})))

(defn write-edn-config!
  "Write config map as pretty-printed EDN to .brainyard/config.edn.

   Optional `:scope` is `:project | :user | :auto` (default `:auto`):
     :auto    — project-config-dir if available, else user-config-dir
                (preserves the legacy wizard/bootstrap-driver behavior)
     :project — project-config-dir only; throws if not available
     :user    — user-config-dir; throws if home dir not available

   Throws if the requested scope's directory can't be resolved. The
   arity-2 form is the legacy 'project-or-user-fallback' behavior used by
   `bb tui config` outside a repo."
  ([dirs config-map] (write-edn-config! dirs config-map :auto))
  ([dirs config-map scope]
   (let [config-dir (case scope
                      :project (project-config-dir dirs)
                      :user    (user-config-dir dirs)
                      :auto    (or (project-config-dir dirs)
                                   (user-config-dir dirs))
                      nil)]
     (when-not config-dir
       (throw (ex-info (str "Cannot write config.edn at scope " scope
                            " — no matching .brainyard/ dir resolved")
                       {:dirs dirs :scope scope})))
     (.mkdirs (io/file config-dir))
     (let [path    (str config-dir "/config.edn")
           content (with-out-str (pprint/pprint config-map))]
       (spit path content)
       path))))

;; ============================================================================
;; Unified Config API — `get-config` / `set-config!`
;; ============================================================================
;;
;; Precedence chain (lowest → highest):
;;   1. Schema `:default` (in `default-config`) or `:default-fn` (invoked
;;      lazily by `get-config` when no override exists)
;;   2. `.brainyard/config.edn`    — `[:agent :config]` subtree merged over (1)
;;                                   to form `!global-config` (cached atom)
;;   3. Session-config             — `(:config @(:!session agent))`, seeded at
;;                                   session creation from `(:session-config ...)`
;;   4. Per-agent override         — `(:config @(:st-memory-init @!state agent))`
;;                                   seeded at `setup-agent`, mutable via
;;                                   `set-config!` with agent arg
;;
;; Reads use `get-config`; writes use `set-config!`. Both accept an optional
;; agent. The 1-arity read returns the global view; the 2-arity overlays the
;; per-agent value when present (boolean false is honored — `contains?` check).
;; The 2-arity write updates global + persists to `config.edn`; the 3-arity
;; additionally writes the per-agent override so the running agent sees it.

(def !global-config
  "Cached merge of `default-config` and the persisted `[:agent :config]`
   subtree of `.brainyard/config.edn`. Lazy-loaded on first `get-config`
   read; invalidated by `invalidate-global-config!` and refreshed by
   `load-global-config!`."
  (atom nil))

(defn- bridge-permissions-section
  "Bridge the user-facing nested `[:permissions ...]` subtree of the
   persisted EDN onto the flat schema-key positions (`:allowed-dirs`,
   `:permission-mode`). One-way: writes to those schema keys are NOT
   mirrored back here. Returns a map ready to merge into the persisted
   slice."
  [edn]
  (let [perm (get edn :permissions)]
    (cond-> {}
      (contains? perm :allowed-dirs) (assoc :allowed-dirs (:allowed-dirs perm))
      (contains? perm :mode)         (assoc :permission-mode (:mode perm)))))

(defn load-global-config!
  "Read `.brainyard/config.edn` (`:auto` scope), pick its `[:agent :config]`
   subtree intersected with `config-keys`, merge over `default-config`, reset
   `!global-config` to the result. Returns the loaded map. Safe to call
   repeatedly (idempotent re-read).

   Applies `migrate-legacy-edn-shape` in-memory so callers using legacy
   positions (`[:agent :max-iterations]` etc.) still take effect at startup
   without requiring a `config$apply` write first.

   Also bridges the user-facing `[:permissions :allowed-dirs]` and
   `[:permissions :mode]` positions onto the flat `:allowed-dirs` /
   `:permission-mode` schema keys — wizard-edited values take effect at
   runtime via `get-config` without changing the persisted EDN shape."
  ([] (load-global-config! (resolve-dirs)))
  ([dirs]
   (let [raw       (read-edn-config dirs :auto)
         migrated  (:config (migrate-legacy-edn-shape raw))
         persisted (-> (get-in migrated [:agent :config] {})
                       (select-keys config-keys))
         bridged   (bridge-permissions-section migrated)
         merged    (merge default-config persisted bridged)]
     (reset! !global-config merged)
     merged)))

(defn invalidate-global-config!
  "Clear the cached `!global-config`. Next read via `get-config` (or an
   explicit `load-global-config!`) will re-read `.brainyard/config.edn`.
   Called by `config$apply` after persisted writes."
  []
  (reset! !global-config nil)
  nil)

(defn- ensure-global-loaded! []
  (when (nil? @!global-config)
    (load-global-config!)))

(defn- agent-config
  "Return the per-agent override map (`(:config @st-memory-init)`) or nil
   when `agent` is nil / not started / has no overrides."
  [agent]
  (some-> agent :!state deref :st-memory-init deref :config))

(defn- session-config
  "Return the session-level config map (`(:config @!session)`) or nil
   when `agent` is nil / has no session / no session-config."
  [agent]
  (some-> agent :!session deref :config))

(defn- resolve-agent
  "Given an Agent record, an st-memory map, or nil, return the Agent record
   most appropriate for config lookup:
     - Agent record (has :!state)               → itself
     - st-memory map containing :agent           → that :agent
     - anything else / nil                       → proto/*current-agent*"
  [x]
  (cond
    (and x (some-> x :!state)) x
    (map? x)                    (or (:agent x) proto/*current-agent*)
    :else                       proto/*current-agent*))

(defn- schema-default-fn-value
  "Invoke the `:default-fn` for schema key `k`, or return nil when the
   entry has no `:default-fn`. Used by `get-config` as the final fallback
   for runtime-resolved keys (`:lm-config`, `:working-dir`, etc.)."
  [k]
  (when-let [f (get-in config-schema [k :default-fn])]
    (f)))

(defn get-config
  "Resolve a single config item. Precedence (lowest → highest):
     schema default (`:default` or `:default-fn`)
       → `!global-config` (.brainyard/config.edn)
       → session-config (`(:config @!session)`)
       → per-agent override (`(:config @st-memory-init)`)
   Boolean `false` overrides are honored — uses `contains?` not `or`.

   The 2-arity accepts an Agent record, an st-memory map (with optional
   `:agent` key), or nil; it falls back to `proto/*current-agent*` when
   no agent reference is in the argument. The 1-arity skips the per-agent
   and session layers."
  ([k]
   (ensure-global-loaded!)
   (if (contains? @!global-config k)
     (get @!global-config k)
     (schema-default-fn-value k)))
  ([agent-or-st k]
   (let [agent (resolve-agent agent-or-st)
         ac    (agent-config agent)
         sc    (session-config agent)]
     (cond
       (and ac (contains? ac k)) (get ac k)
       (and sc (contains? sc k)) (get sc k)
       :else                     (get-config k)))))

(defn get-config-snapshot
  "Return the merged config view (per-agent over session over global) as a
   plain map. Use this when a caller needs the whole map at once (e.g.
   sandbox bindings) — single-key lookups should use `get-config`.
   Accepts the same agent-or-st forms as `get-config`.

   Schema keys with only `:default-fn` (no static `:default`) are NOT in
   the snapshot — they exist solely as a per-key lazy fallback in
   `get-config`. Snapshot callers needing those should use `get-config`."
  ([] (ensure-global-loaded!) @!global-config)
  ([agent-or-st]
   (let [agent (resolve-agent agent-or-st)]
     (merge (get-config-snapshot)
            (or (session-config agent) {})
            (or (agent-config agent) {})))))

(defn- write-persisted-key!
  "Write a single `[:agent :config k]` leaf to `.brainyard/config.edn` at
   `:auto` scope, preserving the rest of the file. Returns the file path."
  [k v]
  (let [dirs     (resolve-dirs)
        existing (read-edn-config dirs :auto)
        updated  (assoc-in existing [:agent :config k] v)]
    (write-edn-config! dirs updated :auto)))

(defn set-config!
  "Persist a single config write. Asserts `k` is in `config-keys`.

   - 2-arity `(set-config! k v)`: updates `!global-config` and writes the
     value to `.brainyard/config.edn` at `[:agent :config k]`.
   - 3-arity `(set-config! agent k v)`: does (2-arity) plus writes the
     per-agent override at `(:config @st-memory-init)` so the running
     agent observes the new value immediately.

   Returns `v`."
  ([k v]
   (assert (contains? config-keys k) (str "Unknown config key: " k))
   (ensure-global-loaded!)
   (swap! !global-config assoc k v)
   (write-persisted-key! k v)
   v)
  ([agent-or-st k v]
   (set-config! k v)
   (when-let [smi (some-> (resolve-agent agent-or-st) :!state deref :st-memory-init)]
     (swap! smi assoc-in [:config k] v))
   v))

;; ============================================================================
;; Agent Directory Helpers
;; ============================================================================
;;
;; Sugar over `get-config` for the working-dir / allowed-dirs / permission-fn
;; tuple used by file-touching tools (`bash`, `read-file`, `write-file`,
;; `grep`, `task$run`, etc.). Centralizing the resolution here keeps the
;; canonicalization + permission-prompt + default-fallback logic in one
;; place — call sites no longer reach into `:!state` / `:!session` directly.
;;
;; Both `:working-dir` and `:allowed-dirs` are schema keys with lazy
;; defaults — see `config-schema` for their `:default-fn` entries. The
;; helpers below are thin wrappers over `get-config` kept for call-site
;; readability.

(defn working-dir
  "Resolve the agent's effective working directory.
   Arity-0 uses `proto/*current-agent*`."
  ([] (get-config :working-dir))
  ([agent] (get-config agent :working-dir)))

(defn project-dir
  "Resolve the agent's effective project root (git-root, or `:project-dir`
   from the session's resolved `:dirs` map). Falls back to `working-dir`
   when no git root is detected (e.g. outside a repo, or in tests with no
   `:dirs` configured).

   Prefer this over `working-dir` for filesystem-touching tools (read,
   write, grep, bash). `working-dir` returns the JVM cwd, which for
   `bb tui` is the launcher's `cd projects/agent-tui-app/` subdir — not
   the canonical brainyard root where `.brainyard/` artifacts live. The
   functional agents (plan/todo/explore/research/update/workflow/main)
   anchor at `project-dir`, so tool-channel reads/writes must agree."
  ([]      (or (:project-dir (get-config :dirs))       (working-dir)))
  ([agent] (or (:project-dir (get-config agent :dirs)) (working-dir agent))))

(defn allowed-dirs
  "Resolve the agent's allowed-dirs allow-list via the unified config chain
   (per-agent override → session → global → schema default-fn).
   Arity-0 uses `proto/*current-agent*`."
  ([] (get-config :allowed-dirs))
  ([agent] (get-config agent :allowed-dirs)))

(defn set-allowed-dirs!
  "Update the agent's allowed-dirs allow-list on the per-agent override
   layer (`(:config @st-memory-init)`). Does not persist to
   `.brainyard/config.edn`. Arity-1 uses `proto/*current-agent*`.
   Returns `dirs`."
  ([dirs] (set-allowed-dirs! proto/*current-agent* dirs))
  ([agent dirs]
   (when-let [smi (some-> agent :!state deref :st-memory-init)]
     (swap! smi assoc-in [:config :allowed-dirs] dirs))
   dirs))

(defn- session-permission-fn
  "Look up `(:permission-fn (:config @!session))` on the agent. Returns nil
   when agent / session / fn is absent."
  [agent]
  (when agent
    (some-> (:!session agent) deref (get-in [:config :permission-fn]))))

(defn resolve-agent-dirs
  "Return the agent's `{:base-dir :canonical-allowed :permission-fn}` triple
   for path-validating tools (bash, task$run). `:canonical-allowed` is the
   union of `:base-dir` and `(allowed-dirs agent)`, each `.getCanonicalPath`'d.
   Arity-0 uses `proto/*current-agent*`.

   `:base-dir` is `project-dir` (git-root), not `working-dir` (JVM cwd) —
   `bb tui` cd's into `projects/agent-tui-app/` before launching, so a
   working-dir-anchored bash would `cd` into the subdir, see only that
   tree (e.g. `ls .brainyard/` would miss main-agent's routing.log + the
   plan/todo/research artifacts), and reject absolute paths under the
   canonical repo root. Anchoring at project-dir makes bash agree with
   `read-file`/`write-file`/`grep` and with the functional agents'
   `.brainyard/` writes."
  ([] (resolve-agent-dirs proto/*current-agent*))
  ([agent]
   (let [base-dir          (project-dir agent)
         canonical-allowed (into [(-> (File. ^String base-dir) .getCanonicalFile .getPath)]
                                 (keep #(try (.getPath (.getCanonicalFile (File. ^String %)))
                                             (catch Exception _ nil)))
                                 (allowed-dirs agent))
         permission-fn     (session-permission-fn agent)]
     {:base-dir          base-dir
      :canonical-allowed canonical-allowed
      :permission-fn     permission-fn})))

;; ============================================================================
;; LM Config Helpers
;; ============================================================================

(defn resolve-sub-lm
  "Resolve the agent's sub-LM config. Pattern:
     :sub-lm-config (string) → parsed via `clj-llm/parse-lm-str` if non-blank
     otherwise → main `:lm-config`
   Unparseable sub-strings fall back to the main config rather than yielding
   nil. `parse-lm-str` returns nil for anything that isn't a strict
   `provider:model` pair (a bare model like \"opus\" → nil), and a nil
   sub-LM would crash `query$llm` with \"No LM configuration provided\" — so
   we `or` it against the main LM. Arity-0 uses `proto/*current-agent*`."
  ([] (resolve-sub-lm proto/*current-agent*))
  ([agent]
   (let [main-lm (get-config agent :lm-config)
         sub-str (get-config agent :sub-lm-config)]
     (if (and sub-str (not (str/blank? sub-str)))
       (or (clj-llm/parse-lm-str sub-str) main-lm)
       main-lm))))
