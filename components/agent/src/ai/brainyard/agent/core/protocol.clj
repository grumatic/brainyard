;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.protocol
  "Agent protocol definitions and core abstractions.

   Provides:
   - Agent identity and lifecycle protocols
   - BT integration protocol (methods expected by agent.core.bt extended nodes)
   - State management protocol
   - Memory access protocol
   - Message helpers")

;; ============================================================================
;; Current Agent Dynamic Var
;; ============================================================================

(def ^:dynamic *current-agent*
  "Dynamic binding for the currently executing agent.
   Set during agent execution to enable tool functions,
   commands, and skills to access the agent context."
  nil)

(def ^:dynamic *current-task*
  "Dynamic binding holding an atom that wraps the current task-id keyword.
   nil when not inside a task context. When bound, deref the atom to get the
   task-id (e.g. :task-5). Fast-eval paths start with a sentinel
   (:inline-code-eval, :inline-tool-eval) and get reset! to the real task-id
   on adoption. Child threads spawned via future/pmap inherit the binding
   and share the same atom reference."
  nil)

(defn in-task-context?
  "True when code is running inside a task thunk (any binding, including inline)."
  []
  (some? *current-task*))

(defn current-task-id
  "Return the current task-id keyword, or nil if not in a task context."
  []
  (some-> *current-task* deref))

(defn update-task-id!
  "Update the task-id in the given task-ref atom. Called from adoption sites —
   the atom is shared across threads, so the future thread and its children
   see the update immediately."
  [!task-ref new-id]
  (reset! !task-ref new-id))

;; ============================================================================
;; Subagent Call Tracking
;; ============================================================================

(def ^:dynamic *call-depth*
  "Current agent-to-agent call depth. 0 = top-level agent.
   Incremented each time an agent invokes another agent via call-tools.
   Used to enforce max-agent-call-depth limit."
  0)

(def ^:dynamic *call-chain*
  "Vector of agent-id keywords representing the current call stack.
   e.g. [:react-agent :coact-agent]. Used for circular call detection —
   if target agent-id already appears in chain, the call is rejected."
  [])

(def ^:dynamic *subagent-capture*
  "When bound to an atom by the fast-eval/detach tool path, the sub-agent
   dispatch (do-call-tool--agent) CAS-writes the dispatched sub-agent's
   instance-id into it — first-writer-wins, so nested dispatches don't clobber
   the top one. Lets the adopted task's on-cancel resolve that sub-agent and
   `runtime/cancel-run` it, cascading cancellation down the chain via the
   upward parent-chain `cancelled?` walk. nil outside that path."
  nil)

(declare user-id session-id)

(defn get-current-user-id
  "Get user-id from *current-agent*. Returns nil if no agent bound."
  []
  (when *current-agent* (user-id *current-agent*)))

(defn get-current-session-id
  "Get session-id from *current-agent*. Returns nil if no agent bound."
  []
  (when *current-agent* (session-id *current-agent*)))

(defmacro with-agent
  "Bind *current-agent* for the duration of body. Convenience for tests."
  [agent & body]
  `(binding [*current-agent* ~agent] ~@body))

;; ============================================================================
;; Agent Protocol
;; ============================================================================

(defprotocol IAgent
  "Protocol for AI agents."
  (agent-id [this] "Get the agent's unique instance id (namespaced keyword :<defagent-type>/<suffix>)")
  (agent-name [this] "Get the agent's name")
  (agent-description [this] "Get the agent's description")
  (user-id [this] "Get the user-id (read from the agent-session).")
  (session-id [this] "Get the agent-session id (read from the agent-session).")
  (defagent-type [this]
    "Return the defagent-type keyword derived from agent-id.
     For :<type>/<suffix> instance ids, returns :<type>.
     For bare (non-namespaced) ids, returns the id itself.")
  (process [this input context] "Process input and return response")
  (get-tools [this] "Get available tools for this agent")
  (get-state [this] "Get agent's current state"))

(defprotocol IAgentLifecycle
  "Protocol for agent lifecycle management."
  (start-agent [this] "Start the agent")
  (stop-agent [this] "Stop the agent")
  (agent-running? [this] "Check if agent is running")
  (clone-agent [this] [this opts]
    "Clone the agent with a new identity, shared session, and snapshotted state.
     opts map (optional):
       :st-memory-init-overrides - map merged into cloned st-memory-init
       :bt-config - BT config vector to build fresh BT (instead of reusing source's tree)
       :parent-agent - set as parent (default: source's parent)"))

;; ============================================================================
;; Agent State Protocol
;; ============================================================================

(defprotocol IAgentState
  "Protocol for deep state access on agent instances."
  (get-state-value [this path] "Get a value from agent state by key path")
  (set-state-value! [this path value] "Set a value in agent state by key path")
  (get-bt [this] "Get the current behavior tree")
  (get-bt-context [this] "Get the BT execution context")
  (get-st-memory-init [this] "Get the initial short-term memory atom."))

;; ============================================================================
;; BT Integration Protocol
;; ============================================================================
;; These methods are called by agent.core.bt extended nodes via Java interop
;; e.g. (.update-session-data agent data)

(defprotocol IAgentBTIntegration
  "Protocol providing methods expected by BT extended nodes in agent.core.bt.
   Method names match what the tracing-aware node overrides call via Java interop."
  (update-session-data [this data]
    "Update session progress/data state. Called by BT tracing nodes.")
  (check-run-cancelled? [this]
    "Check if the current run has been cancelled.")
  (check-run-paused? [this]
    "Check if a pause has been requested for this run (walks parent agents).")
  (await-resume [this]
    "If paused, park the calling thread until resumed or cancelled.
     Returns :running | :resumed | :cancelled.")
  (apply-resume-note! [this]
    "Consume any pending resume note (set by `resume-run` with a note) and fold
     it into the running loop's active task so the next iteration is steered by
     it. No-op when none pending. Called at every BT checkpoint so it fires
     whether or not the loop actually parked on the pause.")
  (create-action-promise [this action-id]
    "Create a promise for a user action request. Returns the promise.")
  (get-action-permission [this action-id]
    "Get a stored action permission value.")
  (set-action-permission [this action-id value]
    "Store an action permission for future use.")
  (get-bt-st-memory [this]
    "Get the BT short-term memory atom."))

;; ============================================================================
;; Memory Access Protocol
;; ============================================================================

(defprotocol IAgentMemoryAccess
  "Protocol for agent memory integration."
  (get-memory-manager [this] "Get the memory manager instance"))

