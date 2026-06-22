;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.interface
  "Public API for the agent component.

   Provides a full-lifecycle AI agent framework including:
   - Agent creation, lifecycle, and execution
   - Behavior tree integration with memory recall/remember cycle
   - Declarative capability macros (defcommand/defskill/defagent)
   - Session management with thinking/progress tracking
   - Async execution with cancellation and permissions
   - LLM client (via clj-llm) with embeddings
   - Context building from memory + conversation"
  (:require [ai.brainyard.util.interface.macros :refer [export-symbols]]
            [ai.brainyard.agent.core.protocol :as protocol]
            [ai.brainyard.agent.core.session :as session]
            [ai.brainyard.agent.task.manager :as task-mgr]
            [ai.brainyard.agent.task.protocol :as task-proto]
            ;; Side-effecting load: registers the default loop-guard hook
            ;; on :agent.tool-use/pre. Apps may opt out via
            ;; (hooks/unregister-source! :default-loop-guard).
            [ai.brainyard.agent.common.loop-guard-hook]
            ;; Side-effecting load: registers the polymorphic doc$* commands
            ;; (doc$list / doc$read / doc$create / doc$update / doc$delete) —
            ;; the canonical CRUD surface for both todos and plans.
            [ai.brainyard.agent.common.doc]
            ;; Side-effecting load: registers the aws$* credential-management
            ;; commands (aws$list-profiles / aws$whoami / aws$get-profile /
            ;; aws$set-profile). The ns has no other consumers, so without
            ;; this require its defcommands never registered at runtime
            ;; despite the file existing — the dormant-ns bug flagged
            ;; during Phase 1.3.
            [ai.brainyard.agent.common.aws-commands]
            ;; Side-effecting loads: register every built-in defagent in the
            ;; unified tool registry. Anyone requiring this interface ns
            ;; automatically gets the full agent roster — no project-level
            ;; static chain or base-level try-require! fallback needed.
            ;; Single source of truth: add a new agent here when it ships.
            [ai.brainyard.agent.common.react-agent]
            [ai.brainyard.agent.common.coact-agent]
            [ai.brainyard.agent.common.skill-agent]
            [ai.brainyard.agent.common.rlm-agent]
            [ai.brainyard.agent.common.explore-agent]
            ;; Live-runtime debug specialist. Requires the clj-nrepl
            ;; server to be running on the host (BY_NREPL_ENABLED
            ;; on agent-tui / agent-web) and an active grant
            ;; (BY_NREPL_GRANT). Without those, the agent loads
            ;; fine but its first code-eval surfaces the gate error.
            [ai.brainyard.agent.common.debug-agent]
            [ai.brainyard.agent.common.update-agent]
            [ai.brainyard.agent.common.plan-agent]
            [ai.brainyard.agent.common.todo-agent]
            [ai.brainyard.agent.common.exec-agent]
            [ai.brainyard.agent.common.eval-agent]
            [ai.brainyard.agent.common.mcp-agent]
            [ai.brainyard.agent.common.tool-agent]
            [ai.brainyard.agent.common.hook-agent]
            [ai.brainyard.agent.common.meta-agent]
            [ai.brainyard.agent.common.research-agent]
            [ai.brainyard.agent.common.memory-agent]
            [ai.brainyard.agent.common.workflow-agent]
            [ai.brainyard.agent.common.config-agent]
            [ai.brainyard.agent.common.init-agent]
            ;; ACP-driven agent. Soft-coupled to ai.brainyard/acp-client via
            ;; requiring-resolve — consumers must add the dep at runtime if
            ;; they want this agent to be invokable. Loading the ns is safe
            ;; either way (the resolve is lazy at first invocation).
            [ai.brainyard.agent.common.acp-agent]
            ;; Front-door router. Loads main-agent AND its three lifecycle
            ;; hooks (:agent.session/created bootstrap, :agent.tool-use/post
            ;; capture-saved-artifacts, :agent.session/closed INDEX summary).
            ;; Apps may opt out via (hooks/unregister-source!
            ;; :ai.brainyard.agent.common.main-agent-hooks/main-agent).
            [ai.brainyard.agent.common.main-agent]
            [ai.brainyard.agent.common.main-agent-hooks]
            ;; Dynamic skill registry. `reload-skills!` is re-exported below
            ;; so entry points (e.g. the TUI's `start!`) can register skills at
            ;; runtime startup instead of via a namespace-load `defonce` — which
            ;; the native binary would otherwise bake at build time.
            [ai.brainyard.agent.common.skills]))

;; ============================================================================
;; Protocols (re-exported for implementors)
;; ============================================================================

(export-symbols ai.brainyard.agent.core.protocol
                IAgent IAgentLifecycle IAgentState IAgentBTIntegration
                IAgentMemoryAccess)

;; ============================================================================
;; Protocol Functions
;; ============================================================================

(export-symbols ai.brainyard.agent.core.protocol
                agent-id agent-name agent-description process get-tools get-state
                user-id session-id defagent-type
                start-agent stop-agent agent-running? clone-agent
                get-state-value set-state-value! get-bt get-bt-context
                get-bt-st-memory get-st-memory-init
                check-run-cancelled? create-action-promise
                get-action-permission set-action-permission update-session-data
                get-current-user-id get-current-session-id
                get-memory-manager)

;; ============================================================================
;; Message Types
;; ============================================================================

(export-symbols ai.brainyard.agent.core.session
                system-message user-message assistant-message
                clear-data)

;; ============================================================================
;; Agent Factory & Lifecycle
;; ============================================================================

(export-symbols ai.brainyard.agent.core.agent
                ask ask-async deliver-action
                register-turn-submitter! submit-turn
                register-agent unregister-agent get-agent list-agents
                list-agents-for-session reset-agent-registry!
                generate-instance-id
                create-agent
                setup-agent setup-agent-by-id run-agent)

;; ============================================================================
;; BT Integration
;; ============================================================================

(export-symbols ai.brainyard.agent.core.bt
                build-bt run-bt skill-behavior-fn skill-behavior-fn*
                ;; Extended BT nodes (moved from behavior-tree nodes-ext):
                request-user-action request-user-action--request-check-fn
                request-user-action--response-fn artifact-action
                user-approval-action user-interrupt-action
                get-st-memory-value
                get-btree-node-id get-btree-node-info btree->jstree get-btree-node)

;; ============================================================================
;; Session Management
;; ============================================================================

(export-symbols ai.brainyard.agent.core.session
                create-session-store create-session ISessionStore
                set-session
                generate-session-id
                get-session-config set-session-config get-messages
                append-agent-activity)

;; ============================================================================
;; Context Building
;; ============================================================================

(export-symbols ai.brainyard.agent.core.context
                build-comprehensive-context process-system-command extract-parent-context)

;; ============================================================================
;; Context Compaction
;; ============================================================================

(export-symbols ai.brainyard.agent.common.context-compaction
                estimate-context-tokens compact-context!)

;; ============================================================================
;; Defs (deftool / defcommand / defskill / defagent)
;; ============================================================================

;; Note: macros must be used via require, not re-exported as vars.
;; Use: (require '[ai.brainyard.agent.core.tool :as t])
;;      (t/deftool ...)       ;; unified macro (:type defaults to :command)
;;      (t/defcommand ...)    ;; wrapper: :type :command
;;      (t/defskill ...)      ;; wrapper: :type :skill
;;      (t/defagent ...)      ;; wrapper: :type :agent

(export-symbols ai.brainyard.agent.core.tool
                get-tool-defs invoke-tool call-tool
                def->tool bind-tools reset-tool-registry!
                inputs->malli-map-schema
                malli-map-entries malli-map-entry-key
                malli-map-entry-props malli-map-entry-schema
                !tool-defs schema->type coerce-value)

;; ============================================================================
;; Hooks Registry
;; ============================================================================

(export-symbols ai.brainyard.agent.core.hooks
                register-hook! unregister-hook! unregister-source! reset-hooks!
                list-hooks fire!
                event-catalog known-event?
                match-all match-any match-agent-id match-defagent-type
                match-root-agent)

;; ============================================================================
;; Current Agent Dynamic Var
;; ============================================================================

;; Dynamic var — must be manual (export-symbols captures root value, losing var identity)
(def ^:dynamic *current-agent*
  "Dynamic binding for the currently executing agent.
   Set during agent execution."
  protocol/*current-agent*)

;; Macro — must be manual (export-symbols doesn't handle macros)
(defmacro with-agent
  "Bind *current-agent* for the duration of body. Convenience for tests."
  [agent & body]
  `(binding [protocol/*current-agent* ~agent] ~@body))

;; Macro — binds the SOURCE override var (a by-value re-export would lose
;; binding identity), so this is the public seam for redirecting every session
;; writer (persist resolver, trajectory, memory-agent) at once.
(defmacro with-sessions-root
  "Pin the project-scoped sessions root to `dir` (a path string) for `body`.
   Used by tests/REPL to redirect session persistence into a temp dir."
  [dir & body]
  `(binding [ai.brainyard.agent.core.config/*sessions-root-override* (str ~dir)] ~@body))

;; ============================================================================
;; Queue Management
;; ============================================================================

(export-symbols ai.brainyard.agent.core.queue
                create-queue get-queue-info enqueue! cancel-item!
                cancel-all-queued! stop-queue!)

;; ============================================================================
;; Runtime Config
;; ============================================================================

(export-symbols ai.brainyard.agent.core.config
                config-schema config-keys default-config
                coerce-config-value valid-config-value?
                migrate-legacy-edn-shape
                !global-config load-global-config! invalidate-global-config!
                get-config get-config-snapshot set-config!
                resolve-sub-lm resolve-sandbox-interop)

;; ============================================================================
;; Async Runtime
;; ============================================================================

(export-symbols ai.brainyard.agent.core.runtime
                cancel-run cancelled?
                pause-run resume-run paused?
                get-parent-agent)

;; ============================================================================
;; Directory & File Management
;; ============================================================================

(export-symbols ai.brainyard.agent.core.config
                find-git-root resolve-working-dir set-working-dir-override!
                resolve-project-dir resolve-dirs
                user-config-dir project-config-dir default-allowed-dirs
                ensure-config-dirs! init-dirs! list-config-files read-config-file
                load-brainyard-instructions read-edn-config write-edn-config!
                subdir-scope-policy subdir-allowed-scopes subdir-scope-allowed?
                brainyard-subdir brainyard-subdir! sessions-root
                working-dir allowed-dirs set-allowed-dirs! resolve-agent-dirs)

;; ============================================================================
;; Live Artifacts
;; ============================================================================

(export-symbols ai.brainyard.agent.common.artifacts
                add-artifact!)

;; ============================================================================
;; Log
;; ============================================================================

(export-symbols ai.brainyard.agent.common.log
                set-app-log-path! query-events list-turns log-commands)

;; ============================================================================
;; Sandbox Metadata
;; ============================================================================

(export-symbols ai.brainyard.agent.common.sandbox-meta
                sandbox-functions sandbox-menu-items sandbox-fn-by-name
                format-sandbox-help)

;; ============================================================================
;; React Agent
;; ============================================================================

(export-symbols ai.brainyard.agent.common.react-agent
                react-behavior-tree
                thinking-loop-subtree)

;; ============================================================================
;; Dynamic Skills
;; ============================================================================

;; `reload-skills!` discovers every available skill (brainyard / claude /
;; agents / project) and (re)registers each as `:skill$<name>` in the unified
;; tool registry. Entry points call this once at runtime startup; there is no
;; namespace-load bootstrap (see the note at the foot of
;; ai.brainyard.agent.common.skills).
(export-symbols ai.brainyard.agent.common.skills
                reload-skills!)

;; ============================================================================
;; MCP (Model Context Protocol) Integration
;; ============================================================================

(export-symbols ai.brainyard.agent.mcp.integration
                mcp-initialized? initialize-mcp-system! shutdown-mcp-system!
                connect-mcp-server! disconnect-mcp-server!
                list-server-tools list-all-server-tools call-server-tool
                list-configured-servers get-server-info get-mcp-server-config
                init-mcp-from-config! start-mcp-server! stop-mcp-server!
                reconnect-mcp-server! reauth-mcp-server! mcp-oauth-server?
                mcp-oauth-status mcp-oauth-logout! apply-oauth-config!
                builtin-default-servers persist-server-enabled!)

(export-symbols ai.brainyard.agent.mcp.client
                list-active-clients set-oauth-prompt-renderer! set-oauth-read-code!)

;; ============================================================================
;; ============================================================================
;; Trajectory Recording — per-session append-only trajectory.edn
;; ============================================================================

(export-symbols ai.brainyard.agent.common.trajectory
                project-iteration build-turn-trajectory
                append-trajectory! read-trajectories latest-trajectory
                session-trajectory-file)

;; ============================================================================
;; Task Management — direct exports for low-level access
;; ============================================================================

(export-symbols ai.brainyard.agent.task.manager
                create-task-manager set-default-manager! get-default-manager
                peek-default-manager
                set-sync-mode! get-sync-tasks set-display-mode!)

;; Atom holding all tasks — used for add-watch in TUI
(export-symbols ai.brainyard.agent.task.manager
                !tasks)

(export-symbols ai.brainyard.agent.task.protocol
                ITaskManager IJobExecutor)

;; Protocol functions (work on ITaskManager instances)
(export-symbols ai.brainyard.agent.task.protocol
                create-task start-task cancel-task remove-task
                list-tasks get-task shutdown)

(export-symbols ai.brainyard.agent.task.format
                format-task-list format-task-detail format-task-output
                format-task-notification format-task-status-bar
                format-task-activity-line format-task-output-line)

;; ============================================================================
;; Task Management — high-level convenience wrappers
;; ============================================================================

(defn task-create
  "Create and start a background task. Returns Task record."
  [task-name job-type job-config & [opts]]
  (if-let [mgr (task-mgr/get-default-manager)]
    (let [task (if opts
                 (task-proto/create-task mgr task-name job-type job-config opts)
                 (task-proto/create-task mgr task-name job-type job-config))]
      (task-proto/start-task mgr (:id task))
      task)
    (throw (ex-info "Task manager not initialized" {}))))

(defn task-list
  "List tasks, optionally filtered by {:status :kw :job-type :kw}."
  [& [filters]]
  (if-let [mgr (task-mgr/get-default-manager)]
    (if filters
      (task-proto/list-tasks mgr filters)
      (task-proto/list-tasks mgr))
    (throw (ex-info "Task manager not initialized" {}))))

(defn task-cancel
  "Cancel a running task by ID."
  [task-id]
  (if-let [mgr (task-mgr/get-default-manager)]
    (task-proto/cancel-task mgr task-id)
    (throw (ex-info "Task manager not initialized" {}))))

(defn task-get
  "Get a task by ID."
  [task-id]
  (if-let [mgr (task-mgr/get-default-manager)]
    (task-proto/get-task mgr task-id)
    (throw (ex-info "Task manager not initialized" {}))))

(defn task-shutdown
  "Shutdown the task manager, cancelling all running tasks (which drives each
   detached task's :on-cancel — destroying its subprocess tree). No-op when no
   manager was ever created. Uses `peek-default-manager` so a shutdown path
   never spins up a fresh manager just to tear it down; safe to call twice (the
   second call sees nil)."
  []
  (when-let [mgr (task-mgr/peek-default-manager)]
    (task-proto/shutdown mgr)
    (task-mgr/set-default-manager! nil)))

