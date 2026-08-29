;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.effect.effect-test
  "JVM-side tests for the effect brick.

   These do NOT retire the Phase 0 risk — that is a native-image
   class-initialization risk and is invisible from the JVM. `by effect-smoke`
   on the built binary is the gate. These cover the semantics the migration
   depends on, so a regression shows up in `bb test` rather than in a TUI."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.effect.interface :as fx]
            [missionary.core :as m]))

;; Used by both the retry-conveyance and the binding-across-a-park tests.
(def ^:dynamic *tag* :root)

(deftest effects-are-values
  (testing "nothing runs until something runs it"
    (let [!n   (atom 0)
          task (m/sp (swap! !n inc) :done)]
      (is (zero? @!n) "constructing a Task must not execute it")
      (is (= {:ok :done} (fx/run!! task 2000)))
      (is (= 1 @!n))
      (fx/run!! task 2000)
      (is (= 2 @!n) "a Task is re-runnable — run it twice, it happens twice"))))

(deftest run-bang-bang-reports-rather-than-throws
  (testing "failure is data, matching brainyard's {:error …} convention"
    (let [r (fx/run!! (m/sp (throw (ex-info "boom" {:x 1}))) 2000)]
      (is (nil? (:ok r)))
      (is (= "boom" (ex-message (:err r))))))
  (testing "timeout cancels on the way out"
    (let [!cancelled (atom false)
          task (fn [s f]
                 (let [inner ((m/sleep 5000 :never) s f)]
                   (fn [] (reset! !cancelled true) (inner))))]
      (is (:timeout (fx/run!! task 100)))
      (is @!cancelled "a timed-out run!! must not leak the effect"))))

(deftest cancellation-is-structural
  (testing "the canceller reaches a nested park"
    (let [!out   (promise)
          cancel (fx/run (m/sp (m/? (m/sp (m/? (m/sleep 5000)))) :never)
                         #(deliver !out {:ok %})
                         #(deliver !out {:err %}))]
      (Thread/sleep (long 50))
      (cancel)
      (let [r (deref !out 2000 {:timeout true})]
        (is (fx/cancelled? (:err r))
            "cancellation must propagate through every nested m/?")))))

(deftest timeout-fallback
  (is (= {:ok :fell-back}
         (fx/run!! (fx/timeout (m/sp (m/? (m/sleep 5000)) :never) 50 :fell-back) 2000))))

(deftest retry-backoff-semantics
  (testing "retries until success, reporting each wait before it happens"
    (let [!n    (atom 0)
          !seen (atom [])
          task  (m/sp (let [n (swap! !n inc)]
                        (if (< n 3) (throw (ex-info "flaky" {})) n)))
          r     (fx/run!! (fx/retry-backoff
                           {:max-retries 5 :base-delay-ms 5
                            :on-retry #(swap! !seen conj (:attempt %))}
                           task)
                          5000)]
      (is (= {:ok 3} r))
      (is (= [1 2] @!seen))))

  (testing "gives up and rethrows the last error"
    (let [r (fx/run!! (fx/retry-backoff
                       {:max-retries 2 :base-delay-ms 5}
                       (m/sp (throw (ex-info "always" {}))))
                      5000)]
      (is (= "always" (ex-message (:err r))))))

  (testing ":retryable? false short-circuits — an exhausted quota is not retried"
    (let [!n (atom 0)
          r  (fx/run!! (fx/retry-backoff
                        {:max-retries 5 :base-delay-ms 5 :retryable? (constantly false)}
                        (m/sp (swap! !n inc) (throw (ex-info "permanent" {}))))
                       5000)]
      (is (= "permanent" (ex-message (:err r))))
      (is (= 1 @!n) "a non-retryable error must be attempted exactly once")))

  (testing ":on-retry sees the CALLER's dynamic frame on every attempt, not just
            the first — it fires from inside the coroutine after an m/sleep park,
            so without an explicit frame it silently reverts to root from attempt
            2 on. This is how clj-llm installs *on-retry*, so the symptom would
            be a TUI that reports the first retry and then goes quiet."
    (let [!seen (atom [])]
      (binding [*tag* :listener]
        (fx/run!! (fx/retry-backoff
                   {:max-retries 3 :base-delay-ms 5
                    :on-retry (fn [_] (swap! !seen conj *tag*))}
                   (m/sp (throw (ex-info "always" {}))))
                  5000))
      (is (= [:listener :listener :listener] @!seen))))

  (testing "an in-flight backoff is cancellable — the Thread/sleep it replaces is not"
    (let [!out   (promise)
          cancel (fx/run (fx/retry-backoff
                          {:max-retries 5 :base-delay-ms 10000}
                          (m/sp (throw (ex-info "flaky" {}))))
                         #(deliver !out {:ok %}) #(deliver !out {:err %}))]
      (Thread/sleep (long 100))
      (cancel)
      (is (fx/cancelled? (:err (deref !out 1000 {:timeout true})))))))

(deftest bounded-preserves-input-order
  (testing "completion order is reversed; result order is not"
    (let [tasks (for [i (range 6)]
                  (m/sp (m/? (m/sleep (* 15 (- 6 i)))) i))]
      (is (= {:ok [0 1 2 3 4 5]} (fx/run!! (fx/bounded 3 tasks) 5000)))))

  (testing "empty input"
    (is (= {:ok []} (fx/run!! (fx/bounded 3 []) 1000))))

  (testing "a failure cancels the siblings — the thing pmap cannot do"
    (let [!finished (atom 0)
          tasks [(m/sp (m/? (m/sleep 20)) (throw (ex-info "boom" {})))
                 (m/sp (m/? (m/sleep 2000)) (swap! !finished inc))
                 (m/sp (m/? (m/sleep 2000)) (swap! !finished inc))]
          r (fx/run!! (fx/bounded 3 tasks) 3000)]
      (is (= "boom" (ex-message (:err r))))
      (Thread/sleep (long 100))
      (is (zero? @!finished)
          "siblings must be cancelled, not left to run to completion"))))

(deftest flows-are-reusable-values
  (let [tick (fx/ticker 10)
        take4 #(fx/run!! (m/reduce conj [] (m/eduction (take 4) tick)) 3000)]
    (is (= {:ok [0 1 2 3]} (take4)))
    (is (= {:ok [0 1 2 3]} (take4)) "a Flow is a value, not a draining channel")))

(deftest sample-lines-emits-only-complete-lines
  (let [w (java.io.StringWriter.)]
    (.write w "alpha\nbeta\npart")
    (let [r (fx/run!! (m/reduce str "" (m/eduction (take 1) (fx/sample-lines w 10))) 2000)]
      (is (= {:ok "alpha\nbeta\n"} r)
          "a partial trailing line must not be emitted twice"))))

(deftest ticking-self-stops-and-is-cancellable
  (testing "runs while tick! is truthy, then completes"
    (let [!n (atom 0)]
      (is (= {:ok nil} (fx/run!! (fx/ticking 5 #(< (swap! !n inc) 4)) 3000)))
      (is (= 4 @!n) "one extra call — the falsey one that stopped it")))

  (testing "work happens BEFORE the first sleep, so a ticker paints immediately
            rather than after one interval of blank"
    (let [!n (atom 0)]
      (fx/run!! (fx/ticking 10000 (fn [] (swap! !n inc) false)) 2000)
      (is (= 1 @!n))))

  (testing "cancellable mid-sleep"
    (let [!n (atom 0)
          cancel (fx/run (fx/ticking 50 (fn [] (swap! !n inc) true))
                         (fn [_]) (fn [_]))]
      (Thread/sleep (long 120))
      (cancel)
      (let [after @!n]
        (Thread/sleep (long 150))
        (is (= after @!n) "a cancelled ticker must stop ticking"))))

  (testing "a throwing tick! fails the task rather than wedging the loop"
    (is (= "tick blew up"
           (ex-message (:err (fx/run!! (fx/ticking 5 #(throw (ex-info "tick blew up" {})))
                                       2000)))))))

(deftest poll-until-for-genuinely-remote-work
  (testing "asks immediately, then paces — matching a throttle whose
            last-polled-at starts at zero"
    (let [!n (atom 0)
          t0 (System/currentTimeMillis)
          r  (fx/run!! (fx/poll-until 50 :pending
                                      (fn [] (if (< (swap! !n inc) 4) :pending {:done @!n})))
                       5000)]
      (is (= {:ok {:done 4}} r))
      (is (= 4 @!n))
      ;; 4 attempts = 3 sleeps of 50ms. If it slept FIRST it would be 4.
      (is (>= (- (System/currentTimeMillis) t0) 140))))

  (testing "the wait is cancellable — the hand-rolled timestamp throttle it
            replaces had to run to the end of its interval first"
    (let [!n (atom 0)
          cancel (fx/run (fx/poll-until 100 :pending (fn [] (swap! !n inc) :pending))
                         (fn [_]) (fn [_]))]
      (Thread/sleep (long 250))
      (cancel)
      (let [after @!n]
        (Thread/sleep (long 300))
        (is (= after @!n) "a cancelled poll must stop asking"))))

  (testing "a throwing poll fails the task rather than looping forever"
    (is (= "peer exploded"
           (ex-message (:err (fx/run!! (fx/poll-until 10 :pending
                                                      #(throw (ex-info "peer exploded" {})))
                                       2000)))))))

(deftest ensure-vs-start
  (testing "ensure! leaves an incumbent alone — the ticker guard, without the atom"
    (let [!a (atom 0) !b (atom 0)]
      (is (true?  (fx/ensure! ::e (fx/ticking 10 (fn [] (swap! !a inc) true)))))
      (is (false? (fx/ensure! ::e (fx/ticking 10 (fn [] (swap! !b inc) true)))))
      (Thread/sleep (long 60))
      (is (pos? @!a))
      (is (zero? @!b) "the second task must never have started")
      (fx/stop! ::e)))

  (testing "start! REPLACES an incumbent"
    (let [!a (atom 0) !b (atom 0)]
      (fx/start! ::r (fx/ticking 10 (fn [] (swap! !a inc) true)))
      (Thread/sleep (long 50))
      (fx/start! ::r (fx/ticking 10 (fn [] (swap! !b inc) true)))
      (Thread/sleep (long 60))
      (let [a-then @!a]
        (Thread/sleep (long 60))
        (is (= a-then @!a) "the first task must have been cancelled")
        (is (pos? @!b)))
      (fx/stop! ::r)))

  (testing "a self-stopped process deregisters, so ensure! can start a fresh one"
    (let [!n (atom 0)]
      (is (true? (fx/ensure! ::f (fx/ticking 5 #(< (swap! !n inc) 3)))))
      (Thread/sleep (long 200))
      (is (not (fx/running? ::f)) "completion must deregister")
      (is (true? (fx/ensure! ::f (fx/ticking 5 (constantly false)))))
      (fx/stop! ::f))))

(deftest supervisor-lifecycle
  (testing "start is idempotent and stop actually stops"
    (let [!ticks (atom 0)
          task   (m/reduce (fn [_ _] (swap! !ticks inc)) nil (fx/ticker 10))]
      (fx/start! ::a task)
      (fx/start! ::a task)                ;; cancels the incumbent, no guard needed
      (is (contains? (fx/running) ::a))
      (Thread/sleep (long 80))
      (is (true? (fx/stop! ::a)))
      (is (not (contains? (fx/running) ::a)))
      (let [after @!ticks]
        (Thread/sleep (long 60))
        (is (= after @!ticks) "a stopped process must stop ticking"))))

  (testing "stop! on an unknown label is a no-op, not an error"
    (is (nil? (fx/stop! ::never-started))))

  (testing "stop-all! clears the registry"
    (fx/start! ::b (m/sp (m/? (m/sleep 10000))))
    (fx/start! ::c (m/sp (m/? (m/sleep 10000))))
    (is (<= 2 (fx/stop-all!)))
    (is (empty? (fx/running)))))

(deftest task-of-is-the-macro-free-entry
  (testing "a thunk becomes a Task"
    (is (= {:ok 4950} (fx/run!! (fx/task-of #(reduce + (range 100))) 2000))))

  (testing "thunks joined through task-of are GENUINELY parallel — this is what
            makes the sandbox usable without m/sp, so a wrapper that quietly
            serialized would defeat the whole Q1 answer"
    (let [slow #(do (Thread/sleep (long 300)) :done)
          t0   (System/currentTimeMillis)
          r    (fx/run!! (fx/all [(fx/task-of slow) (fx/task-of slow)]) 5000)
          el   (- (System/currentTimeMillis) t0)]
      (is (= {:ok [:done :done]} r))
      (is (< el 500) (str "took " el "ms; serial would be ~600"))))

  (testing "a throwing thunk fails the Task rather than escaping"
    (is (= "boom" (ex-message (:err (fx/run!! (fx/task-of #(throw (ex-info "boom" {}))) 2000)))))))

(deftest task-of-conveys-dynamic-bindings
  (testing "matches (future …), which is what call sites migrate from"
    (is (= {:ok :outer}
           (binding [*tag* :outer] (fx/run!! (fx/task-of (fn [] *tag*)) 2000)))))

  (testing "a bare m/via does NOT convey — this is the behaviour task-of exists
            to paper over, pinned so nobody 'simplifies' task-of back to it"
    (is (= {:ok :root}
           (binding [*tag* :outer] (fx/run!! (m/via m/blk *tag*) 2000)))))

  (testing "the frame is captured at CONSTRUCTION, like future"
    (let [t (binding [*tag* :at-build] (fx/task-of (fn [] *tag*)))]
      (is (= {:ok :at-build} (fx/run!! t 2000)))))

  (testing "conveyance survives a join"
    (is (= {:ok [:outer :outer]}
           (binding [*tag* :outer]
             (fx/run!! (fx/all [(fx/task-of (fn [] *tag*))
                                (fx/task-of (fn [] *tag*))])
                       3000))))))

(deftest a-dynamic-var-changes-value-across-a-park
  ;; NOT a wish — a characterization test for a missionary property that will
  ;; silently corrupt *current-task* attribution if anyone forgets it. If this
  ;; ever starts failing, missionary changed and prim/conveying-note is stale.
  (testing "read either side of one park: the value REVERTS to root"
    (is (= {:ok [:outer :root]}
           (binding [*tag* :outer]
             (fx/run!! (m/sp (let [before *tag*]
                               (m/? (m/sleep 10))
                               [before *tag*]))
                       2000)))))

  (testing "...but NOT when the awaited task completes synchronously — which is
            what makes this timing-dependent, and so dangerous"
    (is (= {:ok [:outer :outer]}
           (binding [*tag* :outer]
             (fx/run!! (m/sp (let [before *tag*]
                               (m/? (m/sp 1))
                               [before *tag*]))
                       2000)))))

  (testing "the rule that works: capture lexically before the first park"
    (is (= {:ok :outer}
           (binding [*tag* :outer]
             (fx/run!! (m/sp (let [tag *tag*]
                               (m/? (m/sleep 10))
                               tag))
                       2000)))))

  (testing "and for a callee that must see the var, bind inside the segment"
    (is (= {:ok :outer}
           (fx/run!! (m/sp (m/? (m/sleep 10))
                           (m/? (fx/task-of (fn [] (binding [*tag* :outer] *tag*)))))
                     2000)))))

(deftest from-future-adopts-a-running-future
  (testing "success"
    (let [fut (future (Thread/sleep (long 50)) :from-fut)]
      (is (= {:ok :from-fut} (fx/run!! (fx/from-future fut) 2000)))))

  (testing "cancelling the Task cancels the underlying future — cancelling only
            the getter would leave the real work running unwatched"
    (let [!ran (atom false)
          fut  (future (Thread/sleep (long 2000)) (reset! !ran true))
          !out (promise)
          cancel (fx/run (fx/from-future fut)
                         #(deliver !out {:ok %}) #(deliver !out {:err %}))]
      (Thread/sleep (long 50))
      (cancel)
      (Thread/sleep (long 100))
      (is (future-cancelled? fut))
      (is (false? @!ran)))))

(deftest smoke-report-passes-on-jvm
  (testing "the native gate's checks all pass under the JVM — a baseline, so a
            native FAILURE is unambiguously a class-init problem and not a bug
            in the check itself"
    (let [{:keys [pass? checks]} (fx/smoke-report)]
      (is pass? (pr-str (remove :pass? checks))))))
