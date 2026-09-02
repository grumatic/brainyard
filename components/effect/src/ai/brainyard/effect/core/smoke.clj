;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.effect.core.smoke
  "The Phase 0 gate: does missionary actually work in the NATIVE binary?

   This is not a unit test. The whole risk this phase exists to retire is a
   native-image class-initialization risk, and it is invisible from the JVM:

     - `missionary.impl.Sleep` holds a `static final Sleep$Scheduler S`, and
       `Sleep$Scheduler` extends `Thread` and calls `.start()` in its
       CONSTRUCTOR. Initialized at build time, that snapshots a running thread
       into the image heap.
     - `missionary.impl.Thunk` builds `blk` (cached pool) and `cpu`
       (`newFixedThreadPool(Runtime.getRuntime().availableProcessors())`) in
       its static initializer. Initialized at build time, the BUILD machine's
       core count is baked into every shipped binary.

   Under `--strict-image-heap` the first is expected to fail the build loudly.
   The second would not — it would ship a working binary with a silently
   mis-sized pool. So the gate has to actually run the effects, in the real
   artifact, and check that timers fire and pools are parallel.

   Reached via the hidden `by effect-smoke` argument. Exits non-zero on any
   failure so CI can gate on it."
  (:require [ai.brainyard.effect.core.flows :as flows]
            [ai.brainyard.effect.core.policy :as policy]
            [ai.brainyard.effect.core.prim :as prim]
            [ai.brainyard.effect.core.supervisor :as sup]
            [missionary.core :as m]))

(defn- ms-of
  "Run `thunk`, returning [elapsed-ms result]."
  [thunk]
  (let [t0 (System/currentTimeMillis)
        r  (thunk)]
    [(- (System/currentTimeMillis) t0) r]))

(defn- check
  [label pass? detail]
  {:label label :pass? (boolean pass?) :detail detail})

;; ============================================================================
;; The checks
;; ============================================================================

(defn- check-sleep
  "The scheduler thread is alive AT RUNTIME. A build-time-initialized
   `Sleep$Scheduler` would be a dead thread in the heap and this would hang
   (hence the outer timeout) or return instantly."
  []
  (let [[elapsed r] (ms-of #(prim/run!! (m/sp (m/? (m/sleep 200)) :slept) 5000))]
    (check "m/sleep fires"
           (and (= :slept (:ok r)) (>= elapsed 180))
           (str (pr-str r) " in " elapsed "ms (expect ~200)"))))

(defn- check-blk-parallel
  "`m/join` over two 300ms BLOCKING sleeps on `m/blk` must take ~300ms, not
   ~600ms. Proves the blk pool exists, is not empty, and hands out more than
   one thread. This is also the tutorial's case 4 — missionary runs user code
   on the calling thread, so without `m/via m/blk` this test would serialize."
  []
  (let [blocking (fn [] (Thread/sleep (long 300)) :done)
        task     (m/join vector (m/via m/blk (blocking)) (m/via m/blk (blocking)))
        [elapsed r] (ms-of #(prim/run!! task 5000))]
    (check "m/blk pool is parallel"
           (and (= [:done :done] (:ok r)) (< elapsed 500))
           (str (pr-str r) " in " elapsed "ms (expect ~300, serial would be ~600)"))))

(defn- check-cpu-pool
  "The cpu pool is functional. Its SIZE is baked from
   `Runtime.availableProcessors()` at class-init time — the runtime value is
   printed alongside so a build-machine/run-machine mismatch is at least
   visible in the output."
  []
  (let [task (m/join vector (m/via m/cpu (reduce + (range 100000)))
                     (m/via m/cpu (reduce + (range 100000))))
        r    (prim/run!! task 5000)]
    (check "m/cpu pool works"
           (= 2 (count (:ok r)))
           (str "availableProcessors=" (.availableProcessors (Runtime/getRuntime))
                " result " (pr-str (:ok r))))))

(defn- check-cancel
  "Running a task returns its canceller, and cancelling a parked sleep
   delivers `missionary.Cancelled` — the structural-cancellation property the
   whole migration rests on."
  []
  (let [!out   (promise)
        cancel (prim/run (m/sp (m/? (m/sleep 5000)) :never)
                         #(deliver !out {:ok %})
                         #(deliver !out {:err %}))
        _      (Thread/sleep (long 100))
        _      (cancel)
        r      (deref !out 2000 {:timeout true})]
    (check "cancellation propagates"
           (policy/cancelled? (:err r))
           (str (pr-str (if (:err r) (str (class (:err r))) r))))))

(defn- check-timeout
  []
  (let [r (prim/run!! (policy/timeout (m/sp (m/? (m/sleep 5000)) :never) 200 :fell-back)
                      3000)]
    (check "timeout + fallback" (= :fell-back (:ok r)) (pr-str r))))

(defn- check-retry
  "Backoff sequencing, and that the retry sleep is missionary's (cancellable)
   rather than `Thread/sleep`."
  []
  (let [!n  (atom 0)
        !seen (atom [])
        task (m/sp (let [n (swap! !n inc)]
                     (if (< n 3) (throw (ex-info "boom" {:n n})) {:attempt n})))
        [elapsed r] (ms-of
                     #(prim/run!! (policy/retry-backoff
                                   {:max-retries 5 :base-delay-ms 20
                                    :on-retry (fn [i] (swap! !seen conj (:attempt i)))}
                                   task)
                                  5000))]
    (check "retry-backoff"
           (and (= {:attempt 3} (:ok r)) (= [1 2] @!seen))
           (str (pr-str r) " retries=" (pr-str @!seen) " in " elapsed "ms"))))

(defn- check-bounded-order
  "Parallel fan-out preserves INPUT order even though completion order is
   reversed — the property `pmap`'s replacement must not lose."
  []
  (let [tasks (for [i (range 6)]
                (m/sp (m/? (m/sleep (* 20 (- 6 (long i))))) i))
        [elapsed r] (ms-of #(prim/run!! (policy/bounded 3 tasks) 5000))]
    (check "bounded fan-out keeps order"
           (= [0 1 2 3 4 5] (:ok r))
           (str (pr-str r) " in " elapsed "ms"))))

(defn- check-watch-until
  "An atom watch settles the waiter, in the real artifact.

   This is on the path of EVERY task await — `await-task` replaced its
   `Thread/sleep 100` poll with `watch-until`, so if `m/watch` misbehaves in
   the image then every code block and tool call promoted to a task stops
   waiting correctly. It is a distinct native surface from the checks above:
   `m/watch` + `m/reduce` over a CONTINUOUS flow, terminated by `reduced`.

   Both directions matter. Settling on a write is the feature; NOT settling
   while the predicate is false is the contract that makes it safe to race,
   and a `watch-until` that completed spuriously would turn every await into
   an instant false 'done'."
  []
  (let [!a (atom 0)
        _  (future (Thread/sleep (long 150)) (reset! !a 42))
        gt    (fn [n] (fn [v] (> (long v) (long n))))
        [elapsed r] (ms-of (fn [] (prim/run!! (flows/watch-until !a (gt 2)) 5000)))
        never (prim/run!! (flows/watch-until (atom 0) (gt 99)) 300)]
    (check "watch-until settles on a write"
           (and (= 42 (:ok r)) (>= elapsed 100) (:timeout never))
           (str (pr-str r) " in " elapsed "ms (expect ~150); "
                "never-satisfied=" (pr-str never) " (expect timeout)"))))

(defn- check-flow
  "A Flow is a re-runnable VALUE: the ticker below is consumed twice from the
   same value, and the consumer's `take` terminates it — no self-stop check,
   no thread handle, no nil-out."
  []
  (let [tick (flows/ticker 30)
        run  #(prim/run!! (m/reduce conj [] (m/eduction (take 4) tick)) 5000)
        a    (run)
        b    (run)]
    (check "flow is a reusable value"
           (= [0 1 2 3] (:ok a) (:ok b))
           (str (pr-str (:ok a)) " / " (pr-str (:ok b))))))

(defn- check-supervisor
  []
  (let [!ticks (atom 0)]
    (sup/start! ::smoke (m/reduce (fn [_ _] (swap! !ticks inc)) nil (flows/ticker 20)))
    (Thread/sleep (long 150))
    (let [live?    (contains? (sup/running) ::smoke)
          stopped? (sup/stop! ::smoke)
          after    @!ticks
          _        (Thread/sleep (long 100))
          quiet?   (= after @!ticks)]
      (check "supervisor start/stop"
             (and live? stopped? quiet? (pos? after))
             (str "ticks=" after " live?=" live? " stopped?=" stopped?
                  " quiet-after-stop?=" quiet?)))))

;; ============================================================================
;; Entry point
;; ============================================================================

(defn run-smoke!
  "Run every gate check. Returns {:pass? bool :checks [...]}."
  []
  (let [checks (mapv (fn [f]
                       (try (f)
                            (catch Throwable t
                              (check (str f) false (str "THREW " (class t) ": " (ex-message t))))))
                     [check-sleep
                      check-blk-parallel
                      check-cpu-pool
                      check-cancel
                      check-timeout
                      check-retry
                      check-bounded-order
                      check-watch-until
                      check-flow
                      check-supervisor])]
    {:pass? (every? :pass? checks) :checks checks}))

(defn -print-and-exit!
  "Run the gate, print a report, exit 0/1. Called from the app's `-main` for
   the hidden `effect-smoke` argument."
  []
  (let [{:keys [pass? checks]} (run-smoke!)]
    (println "missionary native gate")
    (println "----------------------")
    (doseq [{:keys [label pass? detail]} checks]
      (println (format "  %s  %-30s %s" (if pass? "PASS" "FAIL") label detail)))
    (println)
    (println (if pass? "ALL PASS" "FAILED"))
    (System/exit (if pass? 0 1))))
