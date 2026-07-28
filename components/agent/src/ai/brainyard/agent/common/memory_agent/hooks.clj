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
  (:require [ai.brainyard.agent.common.background :as bg]
            [ai.brainyard.agent.common.memory-agent.commands :as cmds]
            [clojure.string :as str]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.lang ProcessHandle]))

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

(defonce ^{:doc
           "Optional detached-consolidation launcher, installed by the app layer via
   `set-offload-fn!`. A fn of `{:user-id :session-id :reducer}` that spawns a
   DETACHED `by memory reduce` child and returns a truthy handle (the pid) on a
   successful spawn, or nil to fall back to running the reduce in-process.

   Used by BOTH consolidation paths: the session-end flush (so /quit isn't
   blocked) and, in graph mode, the cadence itself (so a multi-minute reduce
   outlives the session instead of being cancelled at shutdown).

   The seam is a function slot rather than a hardcoded subprocess call because
   components/agent can't resolve the `by` binary path or honor BY_JAR — that's
   a project concern. nil when unset (tests / non-TUI entrypoints), so behavior
   is byte-identical to the in-process reduce until the app installs it."}
  !offload-fn
  (atom nil))

(defn set-offload-fn!
  "Install (or clear, with nil) the detached consolidation launcher.
   See `!offload-fn`. Called once by the app layer at TUI startup."
  [f]
  (reset! !offload-fn f))

;; session-id → pid of a detached CADENCE consolidation believed to still be
;; running. The task-manager single-flight guard cannot see a child process, so
;; without this a later boundary would spawn a second child racing the first
;; over the same session's L2→L3 — the very race the in-process guard prevents.
(defonce ^:private !detached-cadence-pids (atom {}))

(defn- detached-cadence-running?
  "True when this session's last detached cadence child is still alive. Treats
   an unknown/errored handle as not-running: re-reducing is recoverable, a
   permanently wedged cadence is not."
  [sid]
  (boolean
   (when-let [pid (get @!detached-cadence-pids (str sid))]
     (try
       (let [oh (ProcessHandle/of (long pid))]
         (and (.isPresent oh) (.isAlive (.get oh))))
       (catch Throwable _ false)))))

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

;; Session-end flushes that were handed to a detached `by memory reduce` child,
;; as {:session-id :pid}. Populated only by the session-end flush (the cadence
;; runs in-process), and drained by the TUI after it closes its sessions so it
;; can report the spawned background process(es) instead of a blocking notice.
(defonce ^:private !detached-consolidations (atom []))

(defn drain-detached-consolidations!
  "Return-and-clear the session-end consolidations handed to a detached
   `by memory reduce` child since the last drain, as a vector of
   {:session-id :pid}. The TUI calls this after `close-session!` to surface the
   spawned background process. Atomic, so concurrent closes don't lose entries."
  []
  (first (reset-vals! !detached-consolidations [])))

(defn pending-consolidation?
  "True when at least one session has completed turns not yet flushed to L3 — so
   the session-end flush will do real (and, on the graph path, multi-second)
   consolidation work. Lets the TUI warn the user before it blocks on `/quit`.
   The turn counter is only incremented for consolidation-eligible turns, so a
   non-empty map already implies consolidation is enabled."
  []
  (boolean (seq @!turn-counters)))

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
    ;; No `:after-id` — `extract-l2-batch!` reads and advances the PERSISTED
    ;; watermark. This used to be an in-process atom, which meant a detached
    ;; `by memory reduce` child (which has always used the persisted mark) and
    ;; the live session tracked independent positions and re-extracted each
    ;; other's episodes. One mark, so any process picks up where the last left
    ;; off — the prerequisite for offloading this work to a child at all.
    (mem/extract-l2-batch!
     mm :session-id sid
     :max-input-chars (config/get-config agent :graph-extract-max-input-chars)
     :max-entities    (config/get-config agent :graph-max-entities-per-episode)
     :max-relations   (config/get-config agent :graph-max-relations-per-episode)
     :max-nodes       (config/get-config agent :graph-max-nodes)
     :max-edges       (config/get-config agent :graph-max-edges))))

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

;; Ceiling on ONE cadence job, mode-aware for the same reason `flush-timeout-ms`
;; below is: the two reducers differ by orders of magnitude. The heuristic path
;; is LLM-free and returns in milliseconds (measured: 6ms). The graph path does
;; batch extraction plus a per-community LLM summary — a live run over a
;; 2-turn window (88 episodes → 12 communities) took **261s**, i.e. 87% of the
;; background layer's generic 300s default. That default was written for a
;; sub-LM drafting a SKILL.md and is far too tight here: on a longer session
;; the job would be cancelled mid-reduce, plausibly after the extract-marker
;; advanced but before the communities were summarized.
;;
;; These bounds exist to reclaim a WEDGED job, not to bound normal work, so the
;; graph value is deliberately generous relative to the 261s observation.
(def ^:private ^:const cadence-timeout-ms 300000)         ; heuristic (LLM-free)
(def ^:private ^:const cadence-timeout-graph-ms 1800000)  ; graph (LLM extract + summaries)

(defn- cadence-job-timeout-ms
  "How long one cadence consolidation job may run before the task layer
   reclaims it, given the agent's reducer path."
  [agent]
  (if (config/get-config agent :enable-graph-memory)
    cadence-timeout-graph-ms
    cadence-timeout-ms))

(defn- submit-cadence-job!
  "Run this boundary's consolidation in-process, as a background `:fn` task.

   Bounded by the pool, single-flight per session, recorded in an `output.log`,
   and given a grace period at `/quit`. The job carries its own mode-aware
   `:timeout-ms` — the background layer's generic default is sized for a
   scoring call, not for a graph reduce."
  [agent sid tally]
  (bg/run-off-turn!
   :kind       :consolidate
   :key        sid
   :label      (str "memory-consolidate " sid " (turn " tally ")")
   :timeout-ms (cadence-job-timeout-ms agent)
   :thunk (fn []
            (try
              (let [r (run-consolidation! agent)]
                (mulog/info ::consolidation-ran :session-id sid :turn tally :report r)
                r)
              (catch Exception e
                (mulog/warn ::consolidation-failed :session-id sid :exception e)
                nil)))))

(defn- offload-cadence-job!
  "Hand this boundary's consolidation to a detached `by memory reduce` child.
   Returns the pid on a successful spawn, nil when the launcher declines or
   throws (caller falls back in-process)."
  [agent sid tally]
  (try
    (when-let [pid (@!offload-fn {:user-id    (some-> agent proto/user-id)
                                  :session-id sid
                                  :reducer    :community})]
      (swap! !detached-cadence-pids assoc (str sid) pid)
      (mulog/info ::consolidation-cadence-detached
                  :session-id sid :turn tally :pid pid)
      pid)
    (catch Exception e
      (mulog/warn ::cadence-detach-failed :session-id sid :exception e)
      nil)))

(defn consolidation-cadence-handler
  "`:agent.ask/post` handler. Increments the session turn counter and, on every
   Nth turn, consolidates off the turn thread. Returns nil and never throws —
   consolidation is a best-effort lift, not a critical path.

   Two ways off the turn thread, and which one runs matters:

   - GRAPH mode with a launcher installed → a DETACHED `by memory reduce`
     child. A graph reduce (batch extraction + per-community LLM summaries)
     measured 261s on a two-turn window; an in-process job that long is
     cancelled outright if the user quits, throwing away LLM spend already
     paid. A child outlives the session. This is the same seam the session-end
     flush uses, and it is safe to share only because the extraction watermark
     is PERSISTED — an in-process marker would have had parent and child
     re-extracting each other's episodes.
   - otherwise (heuristic reducer, or no launcher — tests, non-TUI) → an
     in-process background task via `submit-cadence-job!`. The heuristic path
     is LLM-free and returns in milliseconds, so there is nothing to outlive.

   Single-flight either way: the task-manager guard covers the in-process path,
   `detached-cadence-running?` covers the detached one. Two overlapping reduces
   over ONE session's L2→L3 would race the watermark and re-insert edges, so a
   boundary reached while one is still running is dropped — the next boundary
   reduces whatever accumulated.

   NOTE: the session-end flush still does NOT route through here. It blocks
   close (bounded, mode-aware) or offloads directly — load-bearing behaviour,
   not an oversight."
  [{:keys [agent]}]
  (when (consolidation-eligible? agent)
    (let [sid (str (some-> agent proto/session-id))
          n   (long (or (config/get-config agent :memory-consolidate-every-n-turns) 12))
          tally (get (swap! !turn-counters update sid (fnil inc 0)) sid)]
      (when (and (pos? n) (zero? (mod tally n)))
        (let [offload? (and (config/get-config agent :enable-graph-memory)
                            (some? @!offload-fn))]
          (cond
            (and offload? (detached-cadence-running? sid))
            (mulog/log ::cadence-detach-skipped-in-flight :session-id sid :turn tally)

            offload?
            (or (offload-cadence-job! agent sid tally)
                ;; launcher declined / failed — still consolidate, in-process
                (submit-cadence-job! agent sid tally))

            :else
            (submit-cadence-job! agent sid tally))))))
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
;; per-community LLM summaries) can take minutes on a long session — a short
;; bound would abandon it and lose the session's tail. See `flush-timeout-ms`.

(def ^:private ^:const session-end-flush-timeout-ms 10000)        ; heuristic (LLM-free)
(def ^:private ^:const session-end-flush-timeout-graph-ms 300000) ; graph path (LLM extract + summaries)

(defn- flush-timeout-ms
  "How long the session-end flush may block on the final consolidation, given the
   agent's reducer path. Graph mode does real LLM work; the heuristic does not."
  [agent]
  (if (config/get-config agent :enable-graph-memory)
    session-end-flush-timeout-graph-ms
    session-end-flush-timeout-ms))

(defn- run-flush-blocking!
  "Run the final consolidation in-process, bounded by `flush-timeout-ms`. Used
   for the LLM-free heuristic path, and as the fallback when no detached-offload
   launcher is installed (or it declines to spawn). Never throws."
  [agent sid tally]
  (let [to (flush-timeout-ms agent)
        r  (deref (future (run-consolidation! agent)) to ::timeout)]
    (if (= r ::timeout)
      (mulog/warn ::session-end-flush-timeout :session-id sid :after-ms to)
      (mulog/info ::session-end-flush-ran :session-id sid :turns tally :report r))))

(defn session-end-flush-handler
  "`:agent.instance/closed` handler. For an eligible root agent whose session
   saw at least one counted turn, finalize the session's tail into L3 and drop
   the session's turn counter. Returns nil and never throws.

   Graph mode does minutes of LLM work (batch extraction + per-community
   summaries). When a detached launcher is installed (`!offload-fn`), hand that
   work to a `by memory reduce` child scoped to this session and return
   immediately, so /quit isn't blocked for up to `session-end-flush-timeout-graph-ms`.
   The heuristic path (LLM-free, ms) — and the fallback when no launcher is
   installed or it declines — runs the bounded in-process flush inline."
  [{:keys [agent]}]
  (when (consolidation-eligible? agent)
    (let [sid   (str (some-> agent proto/session-id))
          tally (get @!turn-counters sid 0)]
      ;; Clear the per-session tally and any detached-cadence pid regardless, so
      ;; neither leaks across a resume; only do real work when the session
      ;; actually had turns. (The extraction watermark is deliberately NOT
      ;; cleared — it lives in the db precisely so a resume, or a child, picks
      ;; up where this process left off.)
      (swap! !turn-counters dissoc sid)
      (swap! !detached-cadence-pids dissoc sid)
      (when (pos? (long tally))
        (if (str/blank? sid)
          ;; A blank session-id would make BOTH paths unscoped: the detached child
          ;; drops the empty `-s` and the inline `run-consolidation!` passes
          ;; `:session-id nil` — either way `extract-l2-graph!` + the community
          ;; reducer would run over the user's ENTIRE L2 history (a whole-history
          ;; backfill), never the intent at session end. A real root session always
          ;; has an id, so this only fires defensively; when it does, skip rather
          ;; than trigger an accidental expensive full reduce.
          (mulog/warn ::session-end-flush-skipped-no-session :turns tally)
          (try
            (let [graph?  (config/get-config agent :enable-graph-memory)
                  offload (and graph? @!offload-fn)
                  pid     (when offload
                            (offload {:user-id    (some-> agent proto/user-id)
                                      :session-id sid
                                      :reducer    :community}))]
              (if pid
                ;; Detached: the child extracts this session's L2 tail into the
                ;; graph and runs the community reducer AFTER we exit. Scoped to
                ;; `-s sid`, so it re-reads only this session's episodes from
                ;; the db (not the whole user history), resuming from the
                ;; PERSISTED extraction watermark this process has been
                ;; advancing all along. Record the spawn so the TUI can report
                ;; the PID after close.
                (do
                  (swap! !detached-consolidations conj {:session-id sid :pid pid})
                  (mulog/info ::session-end-flush-detached
                              :session-id sid :turns tally :pid pid))
                ;; Heuristic path, or no launcher / it declined → bounded inline.
                (run-flush-blocking! agent sid tally)))
            (catch Exception e
              (mulog/warn ::session-end-flush-failed :session-id sid :exception e)
              nil))))))
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
