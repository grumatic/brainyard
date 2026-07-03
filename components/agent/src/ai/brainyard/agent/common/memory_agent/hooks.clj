;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.memory-agent.hooks
  "Hook handlers for the memory-agent.

   - write-guard (Phase 1) — `:agent.tool-use/pre` decision that
     blocks non-memory-agent callers from invoking the gated `memory$*`
     mutation primitives. Other agents reach the same effects via
     `(call-tool \"memory-agent\" {...})`.
   - consolidation-cadence — `:agent.ask/post` observer that counts
     turns per session (deterministic, no LLM) and every
     `:memory-consolidate-every-n-turns` fire-and-forget runs the
     memory pipeline's batch L2→L3 reducer (community when
     `:enable-graph-memory`, else the LLM-free heuristic). Gated on
     `:enable-memory-consolidation`; opt-in per agent-type (root
     coact-agent / research-agent), specialists stay off. This
     REPLACES the retired per-turn essence-capture loop, which spun a
     full memory-agent BT loop on every turn.
   - session-end-flush — `:agent.instance/closed` observer that runs a
     final consolidation when a root agent closes, so a session ending
     between cadence boundaries still reduces its tail of episodes into
     L3. Same gate as the cadence; bounded by a timeout so it can't
     wedge shutdown."
  (:require [ai.brainyard.agent.common.memory-agent.commands :as cmds]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.mulog.interface :as mulog]))

(def ^:const memory-agent-type
  "The defagent-type keyword used by `memory-agent`. Comparing
   `(proto/defagent-type agent)` against this lets the guard tell
   whether the caller is *us*."
  :memory-agent)

(defn- memory-agent-instance?
  "True when the running agent is an instance of `memory-agent`.
   Agent-ids follow the `:<defagent-type>/<suffix>` convention; we
   accept any namespaced keyword whose namespace is \"memory-agent\".
   Bare (non-namespaced) agent-ids in tests are accepted only when the
   id equals `:memory-agent` itself."
  [agent]
  (when agent
    (let [aid (some-> agent proto/agent-id)]
      (or (= aid memory-agent-type)
          (and (keyword? aid)
               (= "memory-agent" (namespace aid)))))))

(defn write-guard-decision
  "Decide whether to block a `memory$*` mutation call.

   Returns nil (= allow) when:
     - the tool isn't in `write-guarded-tools`
     - no agent is currently bound (REPL / direct invoke-tool)
     - the calling agent IS memory-agent

   Otherwise returns a `{:result :block ...}` decision map redirecting
   the caller to `call-tool \"memory-agent\"`."
  [{:keys [agent tool-name]}]
  (when (and (contains? cmds/write-guarded-tools (str tool-name))
             agent
             (not (memory-agent-instance? agent)))
    {:result :block
     :reason (format "%s is gated to memory-agent. Reach it via (memory-agent {:op ...})."
                     tool-name)
     :answer (format "(memory-agent write guard) Tool '%s' is only callable from inside memory-agent. Use (memory-agent {:op ...}) instead."
                     tool-name)}))

(defn install-write-guard!
  "Register the write-guard hook globally. Idempotent —
   `register-hook!` replaces by id. Tag `:source :memory-agent` lets
   apps tear down all memory-agent hooks via
   `(hooks/unregister-source! :memory-agent)`."
  []
  (hooks/register-hook!
   :agent.tool-use/pre
   ::write-guard
   write-guard-decision
   :source   :memory-agent
   :priority 200))

;; ============================================================================
;; Batch consolidation cadence — :agent.ask/post
;; ============================================================================
;; Replaces the retired per-turn essence-capture loop. Rather than spinning a
;; full memory-agent BT loop (6-8 LLM iterations) on EVERY turn — even a bare
;; "hello" — we run the memory pipeline's L2→L3 reducer in batch, every Nth
;; turn, off the same :agent.ask/post seam. The turn count is deterministic
;; (a plain counter, no LLM); the reducer itself is LLM-free (heuristic) or
;; one summary call per cluster (community / CR-MEM-24). Fire-and-forget in a
;; future so the user's next turn never waits and a reducer fault never tanks
;; the parent ask.

(defn- root-agent?
  "True when `agent` has no parent — only root agents drive consolidation;
   sub-agents share the session and would double-count the same turn."
  [agent]
  (try
    (nil? (get-in @(:!state agent) [:runtime :parent-agent]))
    (catch Exception _ false)))

;; session-id → completed-turn tally. Per-session so the cadence boundary
;; is deterministic and independent of which agent instance handled a turn.
;; A `defonce` empty map is native-image-safe (only mutated at runtime).
(defonce ^:private !turn-counters (atom {}))

;; session-id → max L2 episode id already batch-extracted into the graph
;; (`:at-consolidation` mode). Lets each consolidation extract only the episodes
;; captured since the last one, so edges aren't re-inserted.
(defonce ^:private !extract-marker (atom {}))

(defn- agent-memory-manager
  "The per-agent memory manager (same slot `memory$*` commands read via
   `current-mm`). nil when none is bound (tests / REPL without memory)."
  [agent]
  (try (some-> agent :!state deref :memory-manager) (catch Exception _ nil)))

(defn consolidation-eligible?
  "True when the just-finished agent should drive batch consolidation:
     1. Not memory-agent itself (would consolidate its own bookkeeping).
     2. Is a root agent (sub-agents share a session — root handles it).
     3. `:enable-memory-consolidation` is true, OR `:enable-graph-memory` is on.

   The graph coupling: when graph memory is enabled the async extractor is
   already populating the entity graph, and `run-consolidation!` routes to the
   community reducer (which harvests those communities into L3). Turning graph
   memory on without consolidation would build a graph nobody harvests, so we
   imply consolidation from it — derived at this read site rather than baking a
   second default that would drift."
  [agent]
  (when agent
    (try
      (let [aid (some-> agent proto/agent-id)
            ag-type (and (keyword? aid) (namespace aid))]
        (cond
          (= "memory-agent" ag-type) false
          (not (root-agent? agent))  false
          :else
          (boolean (or (config/get-config agent :enable-memory-consolidation)
                       (config/get-config agent :enable-graph-memory)))))
      (catch Exception _ false))))

(defn- batch-extract-if-deferred!
  "In `:at-consolidation` mode, batch-extract the L2 episodes captured since the
   last consolidation into the graph (one LLM call) before it is summarized. The
   per-session marker advances by max episode id so nothing is re-extracted.
   No-op in `:per-episode` mode (the async extractor already populated the graph)."
  [agent mm sid]
  (when (= :at-consolidation (config/get-config agent :graph-extract-mode))
    ;; Capture is async and the cadence can fire on the same ask/post whose L2
    ;; write is still in flight — flush pending writes so the triggering turn's
    ;; episode is visible before we read L2 (else it slips to the next window,
    ;; or is lost entirely at session-end).
    (mem/capture-quiesce! mm 5000)
    (let [skey  (str sid)
          after (get @!extract-marker skey 0)
          r (mem/extract-l2-batch!
             mm :session-id sid :after-id after
             :max-input-chars (config/get-config agent :graph-extract-max-input-chars)
             :max-entities    (config/get-config agent :graph-max-entities-per-episode)
             :max-relations   (config/get-config agent :graph-max-relations-per-episode)
             :max-nodes       (config/get-config agent :graph-max-nodes))]
      (swap! !extract-marker assoc skey (:max-id r))
      r)))

(defn- run-consolidation!
  "Run one batch L2→L3 reduction over the agent's current session. Community
   consolidation when `:enable-graph-memory` is on (the GraphRAG reducer that
   supersedes the heuristic), else the LLM-free heuristic reducer. In graph mode
   with `:at-consolidation` extraction, the new episodes are batch-extracted into
   the graph first. No-op when no memory manager is bound."
  [agent]
  (when-let [mm (agent-memory-manager agent)]
    (let [sid (some-> agent proto/session-id)]
      (if (config/get-config agent :enable-graph-memory)
        (do (batch-extract-if-deferred! agent mm sid)
            (mem/consolidate-graph! mm :session-id sid))
        (mem/consolidate-l2!    mm :session-id sid)))))

(defn consolidation-cadence-handler
  "`:agent.ask/post` handler. Increments the session turn counter and, on
   every Nth turn, fire-and-forget runs `run-consolidation!`. Returns nil and
   never throws — consolidation is a best-effort lift, not a critical path."
  [{:keys [agent]}]
  (when (consolidation-eligible? agent)
    (let [sid (str (some-> agent proto/session-id))
          n   (long (or (config/get-config agent :memory-consolidate-every-n-turns) 12))
          tally (get (swap! !turn-counters update sid (fnil inc 0)) sid)]
      (when (and (pos? n) (zero? (mod tally n)))
        (future
          (try
            (let [r (run-consolidation! agent)]
              (mulog/info ::consolidation-ran :session-id sid :turn tally :report r))
            (catch Exception e
              (mulog/warn ::consolidation-failed :session-id sid :exception e)
              nil))))))
  nil)

(defn install-consolidation-cadence!
  "Register the consolidation-cadence hook globally. Idempotent.

   Priority -200 runs this AFTER the capture dispatcher (-100), so the current
   turn's episode is offered to the capture channel BEFORE the cadence spawns its
   consolidation future — a prerequisite for the future's `capture-quiesce!`
   barrier to actually wait for that episode (higher priority fires first)."
  []
  (hooks/register-hook!
   :agent.ask/post
   ::consolidation-cadence
   consolidation-cadence-handler
   :source   :memory-agent
   :priority -200))

;; ============================================================================
;; Session-end consolidation flush — :agent.instance/closed
;; ============================================================================
;; The real end-of-session signal. When a ROOT agent instance closes (/quit,
;; EOF, /agent close, programmatic .close), run a FINAL consolidation over its
;; session so a session that ended between cadence boundaries still gets the
;; tail of its episodes reduced into L3 — the every-Nth-turn cadence alone
;; would drop the last <N turns. `:agent.instance/closed` fires synchronously
;; in `agent.core/close` BEFORE the manager's capture is torn down, so the
;; memory manager is still live here. We block close on the reduce (we want it
;; to finish before exit) but bound it with a timeout so a slow community/LLM
;; reduce can't wedge shutdown. The bound is mode-aware: the heuristic reducer is
;; LLM-free and returns in milliseconds, but the graph path (batch extraction +
;; per-community LLM summaries) legitimately takes tens of seconds — a 10s bound
;; would abandon it and lose the session's tail. See `flush-timeout-ms`.

(def ^:private ^:const session-end-flush-timeout-ms 10000)       ; heuristic (LLM-free)
(def ^:private ^:const session-end-flush-timeout-graph-ms 60000) ; graph path (LLM extract + summaries)

(defn- flush-timeout-ms
  "How long the session-end flush may block on the final consolidation, given the
   agent's reducer path. Graph mode does real LLM work; the heuristic does not."
  [agent]
  (if (config/get-config agent :enable-graph-memory)
    session-end-flush-timeout-graph-ms
    session-end-flush-timeout-ms))

(defn session-end-flush-handler
  "`:agent.instance/closed` handler. For an eligible root agent whose session
   saw at least one counted turn, run a final consolidation (bounded by
   `flush-timeout-ms`) and drop the session's turn counter. Returns nil and never
   throws."
  [{:keys [agent]}]
  (when (consolidation-eligible? agent)
    (let [sid   (str (some-> agent proto/session-id))
          tally (get @!turn-counters sid 0)
          to    (flush-timeout-ms agent)]
      ;; Clear the per-session tally + extraction marker regardless so they
      ;; never leak across a resume; only do real work when the session actually
      ;; had turns. (The flush's own run-consolidation! extracts the tail first.)
      (swap! !turn-counters dissoc sid)
      (when (pos? (long tally))
        (try
          (let [r (deref (future (run-consolidation! agent)) to ::timeout)]
            (if (= r ::timeout)
              (mulog/warn ::session-end-flush-timeout
                          :session-id sid :after-ms to)
              (mulog/info ::session-end-flush-ran
                          :session-id sid :turns tally :report r)))
          (catch Exception e
            (mulog/warn ::session-end-flush-failed :session-id sid :exception e)
            nil)))
      ;; Clear the extraction marker AFTER the flush consolidation (which read it
      ;; to extract the tail) so a resumed session with the same id restarts from
      ;; a clean marker rather than re-extracting from 0.
      (swap! !extract-marker dissoc sid)))
  nil)

(defn install-session-end-flush!
  "Register the session-end-flush hook globally. Idempotent."
  []
  (hooks/register-hook!
   :agent.instance/closed
   ::session-end-flush
   session-end-flush-handler
   :source   :memory-agent
   :priority 50))

;; Self-install at namespace load. Each hook registers by stable id and
;; replaces itself on subsequent loads, so requiring this ns multiple
;; times is safe.
(install-write-guard!)
(install-consolidation-cadence!)
(install-session-end-flush!)
