;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.core.runtime
  "Async agent runtime with cancellation and permission management.

   Provides:
   - Future-based async execution with cancellation
   - Cooperative pause/resume parked on a Condition
   - Promise-based action permission system
   - Parent-agent relationship for sub-agents"
  (:require [ai.brainyard.mulog.interface :as mulog])
  (:import [java.util.concurrent.locks ReentrantLock Condition]))

;; ============================================================================
;; Runtime State Management
;; ============================================================================

(defn create-runtime-state
  "Create initial runtime state map."
  []
  {:future nil
   :cancelled? false
   :paused? false
   :pause-condition nil
   :active-http nil
   :action-permissions {}
   :action-promises {}
   :parent-agent nil})

;; ============================================================================
;; Async Execution
;; ============================================================================

(defn run-async
  "Execute a function asynchronously, storing the future in state.
   Returns the future."
  [!state f]
  (let [fut (future
              (try
                (f)
                (catch InterruptedException _
                  (mulog/info ::agent-execution-interrupted))
                (catch Exception e
                  (mulog/error ::agent-execution-failed :exception e)
                  {:error (ex-message e)})))]
    (swap! !state assoc-in [:runtime :future] fut)
    fut))

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

(defn cancel-run
  "Cancel the current async execution.
   Sets the :cancelled? flag (checked by BT nodes), aborts any active HTTP
   request (so a streaming LLM call unblocks promptly), wakes any thread
   parked on the pause condition, and interrupts the executing thread —
   either via future-cancel (run-async path) or direct Thread.interrupt
   (send-ask/clj-agent path)."
  [!state]
  (swap! !state assoc-in [:runtime :cancelled?] true)
  (signal-pause-condition! !state)
  (when-let [^java.io.Closeable http (get-in @!state [:runtime :active-http])]
    (try (.close http) (catch Throwable _)))
  (if-let [fut (get-in @!state [:runtime :future])]
    (future-cancel fut)
    (when-let [^Thread thread (get-in @!state [:runtime :thread])]
      (.interrupt thread)))
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
  (swap! !state (fn [s]
                  (-> s
                      (assoc-in [:runtime :paused?] true)
                      (assoc-in [:runtime :pre-pause-status] (:status s))
                      (assoc :status :paused))))
  (mulog/info ::agent-run-paused))

(defn resume-run
  "Clear the pause flag and signal any parked thread to wake up.
   Restores `:status` to whatever it was before `pause-run` flipped it
   (defaulting to `:running` when no prior value was saved)."
  [!state]
  (swap! !state (fn [s]
                  (let [prev (or (get-in s [:runtime :pre-pause-status]) :running)]
                    (-> s
                        (assoc-in [:runtime :paused?] false)
                        (update :runtime dissoc :pre-pause-status)
                        (assoc :status prev)))))
  (signal-pause-condition! !state)
  (mulog/info ::agent-run-resumed))

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
     :running   - was not paused; no wait
     :resumed   - was paused, now resumed
     :cancelled - was paused, now cancelled"
  [!state]
  (if-not (paused? !state)
    :running
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
         {:future nil
          :cancelled? false
          :paused? false
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
