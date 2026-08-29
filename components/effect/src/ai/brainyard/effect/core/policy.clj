;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.effect.core.policy
  "Policy combinators: Task -> Task.

   Everything here is a WRAPPER, which is the point. Today brainyard's timeout
   policy is an enum (`await-task`'s `:on-timeout :kill | :detach | :snapshot`)
   because a timeout could not be expressed as something you put around an
   effect. Once effects are values, `timeout` is a function and the three modes
   are three call sites."
  (:require [missionary.core :as m])
  (:import [missionary Cancelled]))

;; ============================================================================
;; Cancellation
;; ============================================================================

(defn cancelled?
  "True when `e` is missionary's cancellation signal.

   `missionary.Cancelled` is a Java class, not an `m/`-namespaced var, so this
   exists to keep the interop out of call sites — and to give one place to look
   when a `Cancelled` escapes somewhere it should have been absorbed. An
   unabsorbed `Cancelled` from a switched-away branch fails the WHOLE flow;
   inside an `m/ap` the idiom is `(catch missionary.Cancelled _ (m/amb))`,
   which drops the branch instead."
  [e]
  (instance? Cancelled e))

;; ============================================================================
;; Timeout
;; ============================================================================

(defn timeout
  "Fail `task` with `Cancelled` after `ms`, cancelling it.

   With `fallback`, succeed with that value instead of failing — the shape
   `await-task :snapshot` wants (observe, don't disturb) versus `:kill`
   (cancel and report)."
  ([task ms] (m/timeout task ms))
  ([task ms fallback] (m/timeout task ms fallback)))

;; ============================================================================
;; Retry
;; ============================================================================

(defn- attempt
  "Run `task`, reifying its outcome as `{:v …}` or `{:e …}`.

   Required, not stylistic: you cannot `catch` across an `m/?` park inside a
   `loop`/`recur`. Reifying the outcome into a value is how a retry loop gets
   to look at a failure without the try spanning the park."
  [task]
  (m/sp (try {:v (m/? task)}
             (catch Cancelled c (throw c))
             (catch Throwable e {:e e}))))

(defn- backoff-ms
  "Exponential backoff with up to 50% jitter, floored by any `retry-after` the
   error carries. Jitter avoids a thundering herd of agents all waking at the
   same instant after a shared 429."
  [attempt-n base-ms retry-after-ms]
  (let [base   (* base-ms (long (Math/pow 2 attempt-n)))
        jitter (long (* base (rand 0.5)))]
    (max (+ base jitter) (long (or retry-after-ms 0)))))

(defn retry-backoff
  "Retry `task` with exponential backoff. Returns a Task.

   opts:
     :max-retries    — default 3
     :base-delay-ms  — default 1000
     :retryable?     — (fn [e] bool), default `(constantly true)`
     :retry-after-ms — (fn [e] ms|nil), honoured as a floor on the delay
     :on-retry       — (fn [{:attempt :max :delay-ms :error}]) fired BEFORE the
                       sleep, so a UI can announce a wait the user has not yet
                       sat through. Throwing from it cannot fail the retry.
     :max-retries-fn — (fn [e] n) overriding :max-retries per error, for the
                       `clj-llm` rule that a throttling 429 earns extra
                       attempts but an exhausted quota earns none.

   Note the sleep is `m/sleep`, so it is CANCELLABLE. The `Thread/sleep` in
   today's `retry-with-backoff` is not: cancelling an agent mid-backoff leaves
   a thread parked for up to 32 seconds before it notices."
  [{:keys [max-retries base-delay-ms retryable? retry-after-ms on-retry max-retries-fn]
    :or   {max-retries 3 base-delay-ms 1000 retryable? (constantly true)}}
   task]
  ;; The caller's dynamic frame, captured at CONSTRUCTION so `on-retry` can be
  ;; invoked under it. Every retry past the first fires from inside the
  ;; coroutine AFTER an `m/sleep` park, i.e. on the scheduler thread with a
  ;; root frame (design §8 Q4) — so without this the callback silently stops
  ;; seeing its own binding from attempt 2 onward. That is exactly how
  ;; `clj-llm` installs `*on-retry*` (`with-retry-listener*`), so the failure
  ;; would have been: the TUI shows the first retry and then goes quiet, with
  ;; the retries still happening. Measured before the fix:
  ;;   [:listener-installed nil nil]
  ;; The TASK is deliberately not run under this frame — it belongs to the
  ;; caller, who chooses conveyance by building it with `prim/task-of`. The
  ;; callback is ours to invoke, so we owe it the frame.
  (let [frame (get-thread-bindings)]
    (m/sp
     (loop [n 0]
       (let [{:keys [v e]} (m/? (attempt task))]
         (if (nil? e)
           v
           (let [max-n (if max-retries-fn (max-retries-fn e) max-retries)]
             (if-not (and (retryable? e) (< n max-n))
               (throw e)
               (let [delay (backoff-ms n base-delay-ms
                                       (when retry-after-ms (retry-after-ms e)))]
                 (when on-retry
                   (try (with-bindings frame
                          (on-retry {:attempt (inc n) :max max-n
                                     :delay-ms delay :error e}))
                        (catch Throwable _ nil)))
                 (m/? (m/sleep delay))
                 (recur (inc n)))))))))))

;; ============================================================================
;; Fan-out
;; ============================================================================

(defn bounded
  "Run `tasks` in parallel, at most `n` at a time, preserving INPUT ORDER in
   the result vector. Returns a Task of the result vector.

   Replaces `pmap` at `coact_agent/coact-tool-dispatch-action`. Three things
   `pmap` cannot do and this does:

     - the bound is `n`, not `availableProcessors + 2`, and not chunked in 32s;
     - a failure cancels the siblings instead of letting them all finish first
       (which is why today's hook-blocked check only fires after every other
       tool has already run);
     - the whole fan-out has one canceller.

   Order is restored explicitly because completion order is not input order —
   results are collected against their index and re-sequenced, so a caller
   zipping results back onto tool-calls cannot silently mispair them."
  [n tasks]
  (let [tasks (vec tasks)]
    (if (empty? tasks)
      (m/sp [])
      (m/sp
       (let [by-idx (m/? (m/reduce conj {}
                                   (m/ap (let [[i t] (m/?> n (m/seed (map-indexed vector tasks)))]
                                           [i (m/? t)]))))]
         (mapv by-idx (range (count tasks))))))))

(defn all
  "Run every task in parallel, unbounded, returning a vector of results.
   A failure cancels the siblings. `m/join` directly — named for readability
   at call sites that would otherwise read `(apply m/join vector ts)`."
  [tasks]
  (apply m/join vector tasks))

(defn race
  "First task to settle wins; the rest are cancelled."
  [& tasks]
  (apply m/race tasks))

(defn delayed
  "Run `task` after `ms`. The wait is cancellable, so cancelling before the
   delay elapses means `task` never runs at all.

   Exists so a caller that needs sleep-then-work does not have to reach for
   `missionary.core` — one site needing a leading delay is not a reason to
   give `ticking` a mode, nor to spread the coroutine macros into the TUI."
  [ms task]
  (m/sp (m/? (m/sleep ms))
        (m/? task)))
