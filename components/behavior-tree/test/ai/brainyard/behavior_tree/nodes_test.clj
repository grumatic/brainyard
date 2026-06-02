;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.behavior-tree.nodes-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.behavior-tree.interface :as bt]
            [ai.brainyard.behavior-tree.interface.protocol :as p]))

;; ============================================================================
;; Sequence tests
;; ============================================================================

(deftest sequence-all-success-test
  (testing "Sequence returns success when all children succeed"
    (let [result (bt/run (bt/build [:sequence
                                    [:action (fn [_] bt/success)]
                                    [:action (fn [_] bt/success)]]
                                   {}))]
      (is (= bt/success result)))))

(deftest sequence-first-failure-test
  (testing "Sequence returns failure on first child failure"
    (let [side-effect (atom false)
          result (bt/run (bt/build [:sequence
                                    [:action (fn [_] bt/failure)]
                                    [:action (fn [_]
                                               (reset! side-effect true)
                                               bt/success)]]
                                   {}))]
      (is (= bt/failure result))
      (is (false? @side-effect) "Second action should not execute"))))

(deftest sequence-running-test
  (testing "Sequence returns running when child returns running"
    (let [result (bt/run (bt/build [:sequence
                                    [:action (fn [_] bt/running)]
                                    [:action (fn [_] bt/success)]]
                                   {}))]
      (is (= bt/running result)))))

;; ============================================================================
;; Fallback tests
;; ============================================================================

(deftest fallback-first-success-test
  (testing "Fallback returns success on first child success"
    (let [result (bt/run (bt/build [:fallback
                                    [:action (fn [_] bt/success)]
                                    [:action (fn [_] bt/failure)]]
                                   {}))]
      (is (= bt/success result)))))

(deftest fallback-all-failure-test
  (testing "Fallback returns failure when all children fail"
    (let [result (bt/run (bt/build [:fallback
                                    [:action (fn [_] bt/failure)]
                                    [:action (fn [_] bt/failure)]]
                                   {}))]
      (is (= bt/failure result)))))

(deftest fallback-second-success-test
  (testing "Fallback tries second child when first fails"
    (let [result (bt/run (bt/build [:fallback
                                    [:action (fn [_] bt/failure)]
                                    [:action (fn [_] bt/success)]]
                                   {}))]
      (is (= bt/success result)))))

;; ============================================================================
;; Parallel tests
;; ============================================================================

(deftest parallel-all-success-test
  (testing "Parallel succeeds when all children succeed (default threshold)"
    (let [result (bt/run (bt/build [:parallel
                                    [:action (fn [_] bt/success)]
                                    [:action (fn [_] bt/success)]]
                                   {}))]
      (is (= bt/success result)))))

(deftest parallel-threshold-test
  (testing "Parallel succeeds when success count >= threshold"
    (let [result (bt/run (bt/build [:parallel {:success-threshold 1}
                                    [:action (fn [_] bt/success)]
                                    [:action (fn [_] bt/failure)]]
                                   {}))]
      (is (= bt/success result)))))

(deftest parallel-failure-test
  (testing "Parallel fails when too many children fail"
    (let [result (bt/run (bt/build [:parallel {:success-threshold 2}
                                    [:action (fn [_] bt/success)]
                                    [:action (fn [_] bt/failure)]
                                    [:action (fn [_] bt/failure)]]
                                   {}))]
      (is (= bt/failure result)))))

;; ============================================================================
;; Condition tests
;; ============================================================================

(deftest condition-true-test
  (testing "Condition returns success when predicate is true"
    (let [result (bt/run (bt/build [:condition (fn [_] true)]
                                   {}))]
      (is (= bt/success result)))))

(deftest condition-false-test
  (testing "Condition returns failure when predicate is false"
    (let [result (bt/run (bt/build [:condition (fn [_] false)]
                                   {}))]
      (is (= bt/failure result)))))

(deftest condition-with-opts-test
  (testing "Condition receives opts in context"
    (let [result (bt/run (bt/build [:condition {:my-key "hello"}
                                    (fn [ctx] (= "hello" (get-in ctx [:opts :my-key])))]
                                   {}))]
      (is (= bt/success result)))))

;; ============================================================================
;; Action tests
;; ============================================================================

(deftest action-success-test
  (testing "Action returns its function's result"
    (let [result (bt/run (bt/build [:action (fn [_] bt/success)]
                                   {}))]
      (is (= bt/success result)))))

(deftest action-with-st-memory-test
  (testing "Action can read and write st-memory"
    (let [built (bt/build [:sequence
                           [:action (fn [ctx]
                                      (swap! (:st-memory ctx) assoc :result "done")
                                      bt/success)]
                           [:action (fn [ctx]
                                      (if (= "done" (:result @(:st-memory ctx)))
                                        bt/success
                                        bt/failure))]]
                          {})]
      (is (= bt/success (bt/run built)))
      (is (= "done" (:result @(:st-memory (:context built))))))))

(deftest action-with-opts-test
  (testing "Action receives opts in context"
    (let [result (bt/run (bt/build [:action {:id :test-action :value 42}
                                    (fn [ctx]
                                      (if (= 42 (get-in ctx [:opts :value]))
                                        bt/success
                                        bt/failure))]
                                   {}))]
      (is (= bt/success result)))))

;; ============================================================================
;; Nested tree tests
;; ============================================================================

(deftest nested-sequence-fallback-test
  (testing "Nested sequence inside fallback"
    (let [result (bt/run (bt/build [:fallback
                                    [:sequence
                                     [:action (fn [_] bt/success)]
                                     [:action (fn [_] bt/failure)]]
                                    [:action (fn [_] bt/success)]]
                                   {}))]
      (is (= bt/success result)))))

;; ============================================================================
;; Repeat tests
;; ============================================================================

(deftest repeat-basic-test
  (testing "Repeat executes child multiple times until condition passes"
    (let [counter (atom 0)
          built (bt/build [:repeat {:id :test-repeat :max-n 5
                                    :condition-fn (fn [ctx]
                                                    (>= (:counter @(:st-memory ctx)) 3))}
                           [:action (fn [ctx]
                                      (swap! (:st-memory ctx) update :counter (fnil inc 0))
                                      (swap! counter inc)
                                      bt/success)]]
                          {})]
      (is (= bt/success (bt/run built)))
      (is (>= @counter 3)))))

(deftest repeat-max-n-test
  (testing "Repeat stops after max-n iterations"
    (let [counter (atom 0)
          result (bt/run (bt/build [:repeat {:id :test-repeat :max-n 3
                                             :condition-fn (fn [_] false)}
                                    [:action (fn [_]
                                               (swap! counter inc)
                                               bt/success)]]
                                   {}))]
      (is (= bt/success result))
      (is (= 3 @counter)))))

(deftest repeat-child-failure-test
  (testing "Repeat returns failure when child fails"
    (let [result (bt/run (bt/build [:repeat {:id :test-repeat}
                                    [:action (fn [_] bt/failure)]]
                                   {}))]
      (is (= bt/failure result)))))

;; ============================================================================
;; Unknown node type test
;; ============================================================================

(deftest unknown-node-type-test
  (testing "Unknown node type throws exception"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Node type not implemented"
                          (p/tick {:type :unknown-type} {})))))
