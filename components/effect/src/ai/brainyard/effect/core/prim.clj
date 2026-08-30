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
   with `timeout-ms`, `{:timeout true}` once elapsed; `{:interrupted true}` if
   the waiting thread is interrupted. The task is cancelled on the way out of
   BOTH non-settling exits, so neither a timeout nor an interrupt leaks a
   running effect.

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
         r      (try
                  (if timeout-ms
                    (deref p timeout-ms {:timeout true})
                    (deref p))
                  (catch InterruptedException _
                    ;; INTERRUPT LEAKS UNLESS IT CANCELS. `deref` throws when
                    ;; the waiting thread is interrupted, and before this the
                    ;; exception propagated straight out — past `cancel`,
                    ;; leaving the effect running with nobody holding its
                    ;; canceller. Measured against a streaming HTTP body: the
                    ;; caller died on the interrupt and the server kept sending
                    ;; for as long as it was watched. Same class of leak the
                    ;; `:timeout` branch already guarded, on the path far more
                    ;; likely to be taken — cancelling a turn interrupts the
                    ;; thread, it does not wait for a timeout.
                    ;;
                    ;; Restore the flag rather than swallow it: `deref` clears
                    ;; the interrupt status, and the caller's own loop still
                    ;; needs to see that it was told to stop.
                    (.interrupt (Thread/currentThread))
                    {:interrupted true}))]
     (when (or (:timeout r) (:interrupted r)) (cancel))
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

(defn task-of
  "Lift a plain thunk into a Task, evaluated on `m/blk`, CONVEYING the caller's
   dynamic bindings — the drop-in shape of `(future …)`.

   Small, and the reason the SCI sandbox question (design §8 Q1) has a happy
   answer. `m/sp` and `m/ap` cannot work under SCI — they expand into
   cloroutine's CPS transform, which analyzes against the JVM compiler's
   `&env`, and SCI does not have one. But the sandbox never needed them:
   `m/sp` exists so you can write sequential code *containing parks*, and
   sandboxed code does not park — it hands back composable work. A thunk does
   that, and every other combinator (`join`, `timeout`, `race`, `bounded`,
   `reduce`, `seed`) is already a function that crosses the boundary untouched.

   Verified inside a real sandbox at `:restricted` interop: two 300 ms thunks
   joined complete in 316 ms against 607 ms sequential, so this is genuine
   parallelism and not a wrapper that serializes.

   `m/blk`, not `m/cpu`: a thunk from a caller is presumed blocking. Running it
   on the compute pool would let one `Thread/sleep` starve a pool sized to the
   core count.

   WHY IT CONVEYS (design §8 Q4). `m/via` is `(via-call exec #(do body))` →
   `Thunk/run`, which invokes the thunk on a pool thread with no
   `binding-conveyor-fn` anywhere — so a bare `m/via` sees ROOT bindings.
   `future` and `pmap` both convey, and this brick's whole purpose is to be
   what those call sites migrate to. A `task-of` that silently dropped
   `proto/*current-task*` would break subagent progress attribution with no
   error — `append-task-output!` simply no-ops on an unknown task. Conveying
   is the least-surprising default and the one that makes the port safe.

   The frame is captured HERE, at construction, exactly as `future` captures at
   creation. Re-running a stored Task therefore replays the frame it was built
   with.

   THIS DOES NOT AND CANNOT FIX READS ACROSS A PARK — see `conveying-note`."
  [f]
  (let [frame (get-thread-bindings)]
    (m/via m/blk (with-bindings frame (f)))))

(def conveying-note
  "Why there is no general `(conveying bindings task)` wrapper, and what to do
   instead. Kept as a var so it has somewhere to be cited from.

   A wrapper only ever sees a Task's `(success, failure)` callbacks and its
   canceller. It cannot reach inside an arbitrary Task's BODY to install a
   binding frame there — wrapping the callbacks installs bindings for the
   continuation, which is not what anyone means by 'convey'. Only a
   constructor that owns its body (`task-of`) can honestly convey, so only
   `task-of` does.

   THE HAZARD, measured. Inside `m/sp`, a dynamic var can change value
   MID-BODY, at the first park:

     (binding [*tag* :outer]
       (run!! (m/sp (let [before *tag*]
                      (m/? (m/sleep 10))
                      [before *tag*]))))
     ;; => [:outer :root]   thread names: [\"main\" \"missionary scheduler\"]

   A park releases the thread; the coroutine resumes on whichever thread
   completed the awaited task, carrying that thread's (root) frame. Worse, it
   is TIMING-DEPENDENT: parking on an already-completed task resumes
   synchronously and yields [:outer :outer]. So the same code is
   binding-stable or not depending on whether the inner task happened to be
   done — passing under a fast test double and failing against a real LLM call.

   THE RULE: never read a dynamic var after a park. Capture it lexically before
   the first `m/?` and use the local:

     (m/sp (let [tag *tag*]          ;; captured BEFORE any park
             (m/? (m/sleep 10))
             tag))                   ;; => :outer

   Where a callee must see the var (a tool that reads `*current-task*`), bind
   it inside the segment that calls it, which is also what the code does today:

     (m/? (m/via m/blk (binding [*current-task* tid] (call-tool …))))")

(defn success
  "A Task that immediately succeeds with `v`. (`m/sp` without a body needs a
   body; this reads better at call sites that branch into a constant.)"
  [v]
  (fn [s _] (s v) (fn [])))

(defn failure
  "A Task that immediately fails with `e`."
  [e]
  (fn [_ f] (f e) (fn [])))
