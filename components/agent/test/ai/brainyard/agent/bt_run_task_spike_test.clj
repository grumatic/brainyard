;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.bt-run-task-spike-test
  "SPIKE §16 step 2: `bt/run` returns a Task, and `cancel-run` uses its
   canceller.

   This is the step the whole exercise was for. Everything before it changed no
   user-visible behaviour; this is where `cancel-run` stops needing the
   cooperative flag, because the run finally hands back something to cancel."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent.core.bt :as abt]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.runtime :as rt]
            [ai.brainyard.behavior-tree.interface :as bt]
            [ai.brainyard.effect.interface :as fx]))

(defrecord SpikeAgent [agent-id !state !traces !paused? !cancel-checks !st]
  proto/IAgentBTIntegration
  (update-session-data [_ data]
    (when-let [t (:trace data)] (swap! !traces conj (:content t))) nil)
  ;; Counted, not just answered: the point of the step is that nothing asks.
  (check-run-cancelled? [_] (swap! !cancel-checks inc) false)
  (check-run-paused? [_] @!paused?)
  (await-resume [_] :resumed)
  (apply-resume-note! [_] nil)
  (create-action-promise [_ _] (promise))
  (get-action-permission [_ _] nil)
  (set-action-permission [_ _ _] nil)
  (get-bt-st-memory [_] !st))

(defn- spike-agent
  "An agent whose :behavior-tree is `config`, ready for `run-bt-task`."
  [config]
  (let [a (->SpikeAgent :spike/agent (atom {}) (atom []) (atom false) (atom 0) (atom {}))]
    (reset! (:!state a) {:runtime (rt/create-runtime-state)
                         :behavior-tree (bt/build config {:agent a})})
    a))

(deftest run-bt-task-produces-a-result
  (testing "the happy path still answers, and stamps the question into st-memory"
    (let [a (spike-agent [:sequence {:id "S"}
                          [:action {:id "a"} (fn [_] :success)]
                          [:action {:id "b"} (fn [_] :success)]])
          r (fx/run!! (abt/run-bt-task a "what is the question?") 15000)]
      (is (= {:ok :success} r))
      (is (= "what is the question?"
             (:question @(get-in @(:!state a) [:behavior-tree :context :st-memory])))))))

(deftest cancel-run-stops-an-effect-run-without-the-flag
  (testing "THE STEP. cancel-run cancels the tree's Task, and the cooperative
            `:cancelled?` flag is never consulted — the mock counts every ask."
    (let [reached (atom [])
          a (spike-agent [:sequence {:id "S"}
                          [:action {:id "first"} (fn [_] (swap! reached conj :first) :success)]
                          ;; A plainly BLOCKING leaf — no Task, no m/sleep. It
                          ;; still unwinds, because every leaf runs through
                          ;; `m/via m/blk` and cancelling interrupts it.
                          [:action {:id "slow"}  (fn [_] (Thread/sleep 5000)
                                                   (swap! reached conj :slow-finished) :success)]
                          [:action {:id "after"} (fn [_] (swap! reached conj :after) :success)]])
          !state (:!state a)
          !out (promise)
          cancel (fx/run (abt/run-bt-task a "q")
                         #(deliver !out {:ok %}) #(deliver !out {:err %}))]
      (rt/set-bt-canceller! !state cancel)
      (Thread/sleep 400)
      (is (= [:first] @reached) "the slow leaf should be in flight")

      (rt/cancel-run !state)

      (is (not= :TIMEOUT (deref !out 3000 :TIMEOUT)) "cancel-run must settle the run")
      (Thread/sleep 5200)
      (is (= [:first] @reached)
          "the blocking leaf must have been interrupted, and the node after it never run")
      (is (zero? @(:!cancel-checks a))
          "nothing asked check-run-cancelled? — cancellation was structural"))))

(deftest a-blocking-leaf-needs-no-conversion
  (testing "leaf conversion is NOT required for cancellation

           Measured, and it is why §16 step 1 turned out to be optional: every
           leaf already runs on `m/blk` via `leaf-task`, so a cancel interrupts
           it whether or not it returns a Task. Converting leaves buys
           composition — a timeout or retry around the LLM call as a value —
           not cancellation."
    (let [finished (atom false)
          a (spike-agent [:sequence {:id "S"}
                          [:action {:id "sync-blocking"}
                           (fn [_] (Thread/sleep 4000) (reset! finished true) :success)]])
          !out (promise)
          cancel (fx/run (abt/run-bt-task a "q")
                         #(deliver !out [:ok %]) #(deliver !out [:err (.getSimpleName (class %))]))]
      (Thread/sleep 300)
      (cancel)
      (is (= [:err "InterruptedException"] (deref !out 3000 :TIMEOUT))
          "a cancelled all-sync tree settles as InterruptedException, not Cancelled")
      (Thread/sleep 4200)
      (is (false? @finished) "the blocking leaf was actually interrupted"))))

(deftest leaf-wrap-is-installed-by-run-bt-task
  (testing "`run-bt-task` wires :leaf-wrap itself, so *current-agent* survives a
            park without the caller having to know about Q4"
    (let [seen (atom :unset)
          a (spike-agent [:sequence {:id "S"}
                          [:action {:id "park"} (fn [_] (Thread/sleep 80) :success)]
                          [:action {:id "read"} (fn [_] (reset! seen proto/*current-agent*) :success)]])
          r (fx/run!! (abt/run-bt-task a "q") 15000)]
      (is (= {:ok :success} r))
      (is (identical? a @seen)
          "the reading leaf saw the agent, after a park, with no caller involvement"))))
