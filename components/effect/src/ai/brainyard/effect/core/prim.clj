;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.effect.core.prim
  "Running effect values, and adopting effects that already started.

   A missionary Task is `(fn [success failure] -> canceller)` — that is the
   whole runtime contract. Everything here is either a way to leave that world
   (`run!!`, for blocking callers at the edge) or a way to enter it from
   brainyard's existing `future`/`promise` surfaces (`from-future`,
   `from-promise`), which is what makes the migration incremental: an executor
   can hand back a Task without its internals changing yet."
  (:require [missionary.core :as m])
  (:import [java.util.concurrent Future]))

;; ============================================================================
;; Running
;; ============================================================================

(defn run
  "Run `task`, calling `success` or `failure` when it settles. Returns the
   canceller — a 0-arg fn. This is missionary's raw contract, re-exported so
   call sites don't have to look like they are invoking a map."
  [task success failure]
  (task success failure))

(defn run!!
  "Run `task` and BLOCK until it settles. Returns `{:ok v}` or `{:err e}`;
   with `timeout-ms`, `{:timeout true}` once elapsed (and the task is cancelled
   on the way out, so a timed-out `run!!` leaks nothing).

   Never throws. Brainyard's result convention is a map with `:error`, not an
   exception — an effect that failed is data, and the caller decides.

   This is the ONLY sanctioned place a thread blocks on an effect. It exists
   for the edges: `-main`, a test, a synchronous command handler. Inside the
   effect world use `m/?`; a `run!!` there would pin a thread and, worse, sever
   the cancellation chain — the outer canceller cannot reach past a blocked
   `deref`."
  ([task] (run!! task nil))
  ([task timeout-ms]
   (let [p      (promise)
         cancel (run task
                     (fn [v] (deliver p {:ok v}))
                     (fn [e] (deliver p {:err e})))
         r      (if timeout-ms
                  (deref p timeout-ms {:timeout true})
                  (deref p))]
     (when (:timeout r) (cancel))
     r)))

(defn run-detached
  "Fire `task` and return its canceller, logging failure rather than reporting
   it. For effects nobody awaits — a best-effort disk flush, a notification.

   Deliberately not `(run task nil nil)`: a Task whose failure callback drops
   the error is how an effect system grows silent holes."
  ([task] (run-detached task nil))
  ([task label]
   (run task
        (fn [_] nil)
        (fn [e]
          ((requiring-resolve 'ai.brainyard.mulog.interface/warn)
           ::detached-effect-failed :label label :exception e)))))

;; ============================================================================
;; Adopting effects that already started
;; ============================================================================

(defn from-future
  "Adopt an already-running `java.util.concurrent.Future` as a Task.

   The bridge that makes Phase 3 incremental: an executor keeps starting a
   `future` exactly as it does today, and callers get a composable Task over
   it — `timeout`, `join`, `race` all apply — without the executor's internals
   changing in the same commit.

   The blocking `.get` runs on `m/blk`, so it never occupies a compute thread.
   Cancelling the Task cancels BOTH: `.cancel` on the underlying future (which
   interrupts the work) and the `m/via` process (which unparks the getter).
   Cancelling only one of them is the bug this exists to prevent — cancelling
   the getter alone leaves the real work running with nobody watching."
  [^Future fut]
  (let [getter (m/via m/blk (.get fut))]
    (fn [s f]
      (let [cancel (getter s f)]
        (fn []
          (.cancel fut true)
          (cancel))))))

(defn from-promise
  "Adopt a Clojure promise as a Task. Same shape as `from-future`, minus
   cancellation of the producer — a promise has no canceller, so cancelling
   this abandons the wait and leaves whoever was going to `deliver` running.
   That asymmetry is inherent to promises and is the reason `m/dfv` should
   replace them where the producer IS ours to cancel."
  [p]
  (m/via m/blk (deref p)))

(defn success
  "A Task that immediately succeeds with `v`. (`m/sp` without a body needs a
   body; this reads better at call sites that branch into a constant.)"
  [v]
  (fn [s _] (s v) (fn [])))

(defn failure
  "A Task that immediately fails with `e`."
  [e]
  (fn [_ f] (f e) (fn [])))
