;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.runtime
  "Async agent runtime with cancellation and permission management.

   Provides:
   - Serialized async ask execution on a Clojure agent (`send-ask`)
   - Cooperative cancellation, checked by the BT at every node tick
   - Cooperative pause/resume parked on a Condition
   - Promise-based action permission system
   - Parent-agent relationship for sub-agents"
  (:require [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]
            [missionary.core :as m])
  (:import [java.util.concurrent.locks ReentrantLock Condition]))

;; ============================================================================
;; Runtime State Management
;; ============================================================================

(defn create-runtime-state
  "Create initial runtime state map."
  []
  {:cancelled? false
   :paused? false
   :pause-condition nil
   :active-http nil
   :action-permissions {}
   :action-promises {}
   :parent-agent nil})

;; ============================================================================
;; Clojure Agent-based Async Execution
;; ============================================================================

(defn create-clj-agent
  "Create a Clojure agent for serialized async ask execution.
   State: {:agent <brainyard-agent> :input nil :output nil}
   Uses :continue error mode so the agent remains usable after errors."
  [brainyard-agent]
  (clojure.core/agent {:agent brainyard-agent :input nil :output nil}
                      :error-mode :continue
                      :error-handler (fn [_ag ex]
                                       (mulog/error ::agent-async-execution-failed :exception ex))))

(defn send-ask
  "Dispatch an ask to the Clojure agent via send-off.
   send-off uses an unbounded thread pool — appropriate for blocking I/O (LLM calls).
   Back-to-back sends are queued and executed sequentially by the Clojure agent.
   Stores the execution thread in the brainyard agent's runtime state so cancel-run
   can interrupt it.
   `opts` (default {}) is forwarded to ask-fn as a third arg — carries e.g.
   `{:source :wakeup}` for auto-asks (see ai.brainyard.agent.core.agent/ask).
   Returns the Clojure agent ref."
  ([clj-agent ask-fn brainyard-agent input]
   (send-ask clj-agent ask-fn brainyard-agent input {}))
  ([clj-agent ask-fn brainyard-agent input opts]
   (send-off clj-agent
             (fn [_state]
               ;; Store executing thread so cancel-run can interrupt it
               (let [!state (:!state brainyard-agent)]
                 (swap! !state assoc-in [:runtime :thread] (Thread/currentThread)))
               (let [result (try
                              (ask-fn brainyard-agent input opts)
                              (catch InterruptedException _
                                (mulog/info ::agent-execution-interrupted)
                                {:error "interrupted"})
                              (catch Exception e
                                (mulog/error ::agent-execution-failed :exception e)
                                {:error (ex-message e)}))]
                 ;; Clear thread ref on completion
                 (let [!state (:!state brainyard-agent)]
                   (swap! !state update :runtime dissoc :thread))
                 {:agent brainyard-agent :input input :output result})))
   clj-agent))

(defn- ensure-pause-condition
  "Lazily allocate the (lock, condition) pair used to park a paused agent
   thread. Returns the pair map {:lock ReentrantLock :cond Condition}."
  [!state]
  (or (get-in @!state [:runtime :pause-condition])
      (let [lock (ReentrantLock.)
            cnd  (.newCondition lock)
            pair {:lock lock :cond cnd}]
        (swap! !state update :runtime
               (fn [r] (if (:pause-condition r) r (assoc r :pause-condition pair))))
        (get-in @!state [:runtime :pause-condition]))))

(defn- signal-pause-condition!
  "Wake all threads parked on the pause condition (no-op if none allocated)."
  [!state]
  (when-let [pair (get-in @!state [:runtime :pause-condition])]
    (let [^ReentrantLock lock (:lock pair)
          ^Condition cnd      (:cond pair)]
      (.lock lock)
      (try (.signalAll cnd) (finally (.unlock lock))))))

(defn set-bt-canceller!
  "SPIKE §16: register the canceller of an in-flight effect-engine BT run, so
   `cancel-run` can stop it structurally.

   This is the seam Phase 4 needed and never had. `send-ask` hands the loop to
   a thread and keeps only the thread; a Task hands back a canceller, and this
   is where it lands."
  [!state cancel]
  (swap! !state assoc-in [:runtime :bt-cancel] cancel))

(defn clear-bt-canceller!
  "Drop the registered BT canceller (call in a finally)."
  [!state]
  (swap! !state update :runtime dissoc :bt-cancel))

;; `cancelled?` / `paused?` are defined below (they belong with the other
;; predicates); the dfv pause needs them.
(declare cancelled? paused?)

;; ============================================================================
;; Pause, as a dataflow variable (§17)
;; ============================================================================
;;
;; The Condition below parks a THREAD. Under the synchronous engine that is the
;; `send-off` thread doing its job. Under the effect engine it parks an `m/blk`
;; POOL thread — shared with every task waiter in the process — for as long as
;; the user leaves the turn paused. Thread count is unchanged, so nothing
;; breaks; the kind of thread is worse.
;;
;; An `m/dfv` is precisely "a value that will arrive later": `resume-run`
;; delivers it, and `(m/? dfv)` parks the COROUTINE holding no thread at all.
;;
;; A dfv can only be delivered once, so a fresh one is allocated per pause and
;; dropped on resume/cancel. Delivery is guarded — `resume-run` and `cancel-run`
;; can race, and the second delivery must be a no-op rather than a throw.

(defn- fresh-pause-dfv!
  "Allocate the dfv for a new pause, replacing any stale one."
  [!state]
  (let [d (m/dfv)]
    (swap! !state assoc-in [:runtime :pause-dfv] d)
    d))

(defn- settle-pause-dfv!
  "Deliver `outcome` to the pending pause dfv, if any, and drop it. Safe to
   call twice — a dfv throws on a second delivery, and resume/cancel race."
  [!state outcome]
  (when-let [d (get-in @!state [:runtime :pause-dfv])]
    (swap! !state update :runtime dissoc :pause-dfv)
    (try (d outcome) (catch Throwable _))))

(defn await-resume-task
  "A Task completing with :cancelled | :running | :resumed — `wait-if-paused`'s
   three outcomes, without holding a thread while it waits.

   Same ordering as the blocking version: cancelled is checked FIRST, so a
   cancelled run never parks, and not-paused returns immediately."
  [!state]
  (m/sp
   (cond
     (cancelled? !state)    :cancelled
     (not (paused? !state)) :running
     :else (m/? (or (get-in @!state [:runtime :pause-dfv])
                    ;; Paused with no dfv: pause-run ran before this code did,
                    ;; or the flag was set directly. Allocate one and wait.
                    (fresh-pause-dfv! !state))))))

(defn cancel-run
  "Cancel the current async execution. Four mechanisms, each covering a
   different way a run can be stuck, none of them redundant:

     1. `:cancelled?` — cooperative. The BT checks it at every node tick
        (`bt/check-interrupt-cancel-pause!`) and throws. This is what actually stops
        the loop; the rest exist to make sure it gets *reached*.
     2. `.close` on `:active-http` — a thread blocked in a socket read is not
        interruptible on the JVM by any mechanism. Closing the stream under it
        is the only way, and without it a streaming LLM call would run to
        completion before the flag was next checked.
     3. `.interrupt` on `[:runtime :thread]` — unparks a `Thread/sleep` or a
        blocking queue take between checkpoints, AND — since streaming defaults
        to the pushed-body path — is what aborts an in-flight HTTP exchange.
        `run-stream-task!!` blocks in `fx/run!!`, which cancels its Task when
        the waiting thread is interrupted; cancelling the Flow cancels the
        subscription, which drops the connection. That is a SECOND reason for
        this mechanism, independent of the BT engine, and it means 3 cannot be
        retired on the grounds that an effect canceller supersedes it.
     4. `signal-pause-condition!` — wakes a thread parked in `wait-if-paused`,
        which is waiting on a `Condition` and would otherwise never re-check
        the flag.

   These do NOT reduce to an effect canceller *as the run is structured today*
   (docs/design/functional-effect-system.md §14). Missionary cancels *effects*,
   propagating through `m/?` parks; the run is a thread — `send-ask` hands the
   BT loop to a `send-off` pool thread and it runs synchronously to completion,
   so there is no park for cancellation to attach to.

   That is a property of the BT engine being synchronous, not a law. §15
   works out what converting it would take and concludes it is tractable —
   about 200 lines, mechanically — with the real cost living in the 39 action
   leaves rather than the engine. Until that happens, these four stay.

   Which of them the effect BT engine actually retires is narrower than that
   framing suggests, and worth being precise about now that it is the default:

     - 1 and 4 become redundant ON THE EFFECT PATH — `:bt-cancel` stops the run
       structurally, so nothing needs to reach a cooperative checkpoint. They
       stay because the synchronous engine remains supported.
     - 2 stays for as long as `BY_STREAM_FLOW=false` is honoured: a thread
       blocked in `BufferedReader.readLine` is unreachable by any other means,
       and that is precisely what the reader fallback selects.
     - 3 does NOT go away even if the synchronous engine does. See above: it is
       now the path by which a cancel reaches the socket.

   Formerly documented as cancelling 'either via future-cancel (run-async path)
   or direct Thread.interrupt'. The `run-async` path had no production callers
   at all — `[:runtime :future]` was never set outside it — so the
   `future-cancel` branch was dead and every real cancel took the interrupt."
  [!state]
  ;; A cancelled run is no longer paused. Clear the pause flags (and any pending
  ;; resume note) in the same swap that sets :cancelled? — otherwise a paused
  ;; turn that gets cancelled leaves `[:runtime :paused?]` stale, so the next
  ;; user line is misrouted into the resume-with-note path instead of starting a
  ;; fresh turn. The parked thread still wakes as :cancelled (wait-if-paused
  ;; checks cancelled? first), so this is race-free.
  (swap! !state (fn [s]
                  (-> s
                      (assoc-in [:runtime :cancelled?] true)
                      (assoc-in [:runtime :paused?] false)
                      (update :runtime dissoc :pre-pause-status :resume-note))))
  (signal-pause-condition! !state)
  (settle-pause-dfv! !state :cancelled)
  (when-let [^java.io.Closeable http (get-in @!state [:runtime :active-http])]
    (try (.close http) (catch Throwable _)))
  ;; SPIKE §16: when the run is an effect, cancelling it is one call and needs
  ;; none of the three mechanisms above. Both paths coexist while only one of
  ;; them is wired in production.
  (when-let [cancel (get-in @!state [:runtime :bt-cancel])]
    (try (cancel) (catch Throwable _)))
  (when-let [^Thread thread (get-in @!state [:runtime :thread])]
    (.interrupt thread))
  (mulog/info ::agent-run-cancelled))

(defn cancelled?
  "Check if the current run has been cancelled.
   Also checks parent agent's cancellation status for sub-agents."
  [!state]
  (boolean
   (or (get-in @!state [:runtime :cancelled?])
       (when-let [parent (get-in @!state [:runtime :parent-agent])]
         (cancelled? (:!state parent))))))

(defn pause-run
  "Request a cooperative pause. The next BT checkpoint (between iterations,
   before a :condition or :action ticks) will park the agent thread on the
   pause condition until resume-run or cancel-run is called.

   Pause is NOT preemptive — an in-flight LLM call still runs to completion;
   the pause lands at the next BT checkpoint.

   Also flips the agent's `:status` to `:paused` (saving the previous
   value under `[:runtime :pre-pause-status]`) so status bars / daemon
   snapshots / hooks can render the paused state without consulting the
   runtime flag separately."
  [!state]
  (ensure-pause-condition !state)
  ;; §17: allocate the dfv this pause will settle. Fresh per pause —
  ;; a dfv delivers once.
  (fresh-pause-dfv! !state)
  (swap! !state (fn [s]
                  (-> s
                      (assoc-in [:runtime :paused?] true)
                      (assoc-in [:runtime :pre-pause-status] (:status s))
                      (assoc :status :paused))))
  (mulog/info ::agent-run-paused))

(defn resume-run
  "Clear the pause flag and signal any parked thread to wake up.
   Restores `:status` to whatever it was before `pause-run` flipped it
   (defaulting to `:running` when no prior value was saved).

   With a non-blank `note`, stash it under `[:runtime :resume-note]` before
   waking. The agent consumes it at its next BT checkpoint (`apply-resume-note!`
   → `take-resume-note!`) and folds it into the running loop's active task — so
   the iteration loop resumes *carrying* the user's mid-run request, and the LLM
   is told it was resumed with that request."
  ([!state] (resume-run !state nil))
  ([!state note]
   (when (and (string? note) (not (str/blank? note)))
     (swap! !state assoc-in [:runtime :resume-note] note))
   (swap! !state (fn [s]
                   (let [prev (or (get-in s [:runtime :pre-pause-status]) :running)]
                     (-> s
                         (assoc-in [:runtime :paused?] false)
                         (update :runtime dissoc :pre-pause-status)
                         (assoc :status prev)))))
   (signal-pause-condition! !state)
   (settle-pause-dfv! !state :resumed)
   (mulog/info ::agent-run-resumed :with-note? (boolean (not (str/blank? (str note)))))))

(defn take-resume-note!
  "Return and clear the pending mid-run resume note set by `(resume-run !state
   note)`, or nil. Consumed once at the agent's next BT checkpoint."
  [!state]
  (let [n (get-in @!state [:runtime :resume-note])]
    (when n (swap! !state update :runtime dissoc :resume-note))
    n))

(defn paused?
  "Check if a pause has been requested (also walks parent agents)."
  [!state]
  (boolean
   (or (get-in @!state [:runtime :paused?])
       (when-let [parent (get-in @!state [:runtime :parent-agent])]
         (paused? (:!state parent))))))

(defn wait-if-paused
  "If paused, park the calling thread on the pause condition until
   resume-run or cancel-run wakes it. Returns:
     :cancelled - the run is cancelled (checked first, so a cancelled run never
                  parks — and since `cancel-run` now clears `paused?`, this is
                  the fast path a cancelled-while-paused run takes)
     :running   - was not paused; no wait
     :resumed   - was paused, now resumed"
  [!state]
  (cond
    (cancelled? !state) :cancelled
    (not (paused? !state)) :running
    :else
    (let [pair (ensure-pause-condition !state)
          ^ReentrantLock lock (:lock pair)
          ^Condition cnd      (:cond pair)]
      (.lock lock)
      (try
        (loop []
          (cond
            (cancelled? !state)    :cancelled
            (not (paused? !state)) :resumed
            :else                  (do (.await cnd) (recur))))
        (finally (.unlock lock))))))

(defn set-active-http!
  "Register an in-flight HTTP request/stream so cancel-run can abort it.
   `req` should be Closeable (typically the response body InputStream or
   an HttpUriRequest)."
  [!state req]
  (swap! !state assoc-in [:runtime :active-http] req))

(defn clear-active-http!
  "Clear the registered active HTTP request (call in finally)."
  [!state]
  (swap! !state assoc-in [:runtime :active-http] nil))

(defn reset-runtime
  "Reset runtime state for a new run.
   Preserves the :pause-condition object — it is reusable across runs."
  [!state]
  (swap! !state update :runtime merge
         {:cancelled? false
          :paused? false
          :resume-note nil
          :active-http nil
          :action-promises {}}))

;; ============================================================================
;; Action Permissions (promise-based approval flow)
;; ============================================================================

(defn create-action-promise
  "Create a promise for an action permission request.
   Returns the promise (caller will deref to wait for user response)."
  [!state action-id]
  (let [p (promise)]
    (swap! !state assoc-in [:runtime :action-promises action-id] p)
    p))

(defn deliver-action-response
  "Deliver a response to a pending action promise."
  [!state action-id value]
  (when-let [p (get-in @!state [:runtime :action-promises action-id])]
    (deliver p value)))

(defn get-action-permission
  "Get a stored action permission."
  [!state action-id]
  (get-in @!state [:runtime :action-permissions action-id]))

(defn set-action-permission
  "Store an action permission for future use."
  [!state action-id value]
  (swap! !state assoc-in [:runtime :action-permissions action-id] value))

;; ============================================================================
;; Parent-Agent Hierarchy
;; ============================================================================

(defn set-parent-agent
  "Set the parent agent for sub-agent hierarchy."
  [!state parent-agent]
  (swap! !state assoc-in [:runtime :parent-agent] parent-agent))

(defn get-parent-agent
  "Get the parent agent, if any."
  [!state]
  (get-in @!state [:runtime :parent-agent]))

;; ---------------------------------------------------------------------------
;; Agent hierarchy — TWO ORTHOGONAL AXES
;;
;; These live here, on the leaf ns that already owns the `!state` accessors,
;; because the low-level callers that need them (core.hooks, core.feature)
;; cannot require `core.agent` — `core.agent` requires THEM. `core.agent` keeps
;; the record-level predicates; these ask the same questions of raw `!state`.
;;
;;   Axis 1 — HIERARCHY (ownership).  Exactly ONE root per session; everything
;;            else is a subagent (has a parent / `:owner`). Answers "is this THE
;;            session's agent?" — who drives per-session SINGLETON behaviour
;;            (consolidation cadence, distillation, nudges, reactions, FSM,
;;            task-wakeup, auto-notify), who holds management authority, who
;;            owns the persisted resume identity.
;;
;;   Axis 2 — SESSION SHARING.  A property OF a subagent: are its turns the
;;            user's turns in this session? An acp-agent shares (it is the user
;;            talking to a second model); a dispatched worker does not (its ask
;;            is operational detail nested in the root's turn). Answers "is this
;;            the user talking?" — L2 capture, answer rendering, input
;;            suggestions.
;;
;; `:share-parent-session` is an axis-2 MODIFIER, never an axis-1 promotion.
;; A session-sharing subagent is still a subagent: it must not drive per-session
;; singletons, or it and the root would both advance the same session's cadence
;; and double-count it. (This is exactly the trap the "sibling"/"peer" language
;; elsewhere in the codebase sets — there is no third kind of agent.)
;; ---------------------------------------------------------------------------

(defn root-state?
  "Axis 1. True when this instance is the session's ROOT — no parent.

   The strict test, and the right one for every per-session singleton. Do NOT
   widen it to admit session-sharing subagents: sharing is about whose turn it
   is (axis 2), not about who owns the session.

   Defensive: an unreadable/absent state reads as root, so an unknown shape
   never silently withholds a capability."
  [!state]
  (try (nil? (get-parent-agent !state))
       (catch Throwable _ true)))

(defn dispatched-subagent-state?
  "Axis 2 (complement). True when this instance is a subagent DISPATCHED to do a
   job — i.e. NOT the user talking. False for the root and for a
   session-sharing subagent.

   `(not (dispatched-subagent-state? …))` is the 'is this the user talking?'
   test: use it for L2 capture, user-facing answer rendering and input
   suggestions. It is NOT a root test — see `root-state?`.

   Defensive: an unreadable/absent state reads as NOT dispatched, so an unknown
   shape is never silently treated as operational detail and dropped."
  [!state]
  (try
    (boolean (and (some? (get-parent-agent !state))
                  (not (get-in @!state [:lifecycle :share-parent-session?]))))
    (catch Throwable _ false)))
