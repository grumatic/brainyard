;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.behavior-tree.engine-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.behavior-tree.interface :as bt]))

;; ============================================================================
;; Build tests
;; ============================================================================

(deftest build-creates-tree-and-context-test
  (testing "Build returns a map with :tree and :context"
    (let [built (bt/build [:action (fn [_] bt/success)] {})]
      (is (map? built))
      (is (contains? built :tree))
      (is (contains? built :context))
      (is (instance? clojure.lang.Atom (:st-memory (:context built)))))))

(deftest build-initializes-st-memory-test
  (testing "Build wraps st-memory in an atom"
    (let [built (bt/build [:action (fn [_] bt/success)]
                          {:st-memory {:key "value"}})]
      (is (= {:key "value"} @(:st-memory (:context built)))))))

;; ============================================================================
;; Run tests
;; ============================================================================

(deftest run-simple-action-test
  (testing "Run executes the tree and returns result"
    (let [built (bt/build [:action (fn [_] bt/success)] {})]
      (is (= bt/success (bt/run built))))))

(deftest run-with-st-memory-test
  (testing "Run allows actions to modify st-memory"
    (let [built (bt/build [:action (fn [ctx]
                                     (swap! (:st-memory ctx) assoc :result 42)
                                     bt/success)]
                          {})]
      (bt/run built)
      (is (= 42 (:result @(:st-memory (:context built))))))))

;; ============================================================================
;; st-memory-has-value? tests
;; ============================================================================

(deftest st-memory-has-value-with-path-test
  (testing "st-memory-has-value? checks path against schema"
    (let [built (bt/build [:action (fn [_] bt/success)]
                          {:st-memory {:answer "hello"}})]
      (is (true? (bt/st-memory-has-value?
                  {:opts {:path [:answer] :schema :string}
                   :st-memory (:st-memory (:context built))}))))))

(deftest st-memory-has-value-without-path-test
  (testing "st-memory-has-value? checks whole memory against schema"
    (let [built (bt/build [:action (fn [_] bt/success)]
                          {:st-memory {:answer "hello"}})]
      (is (true? (bt/st-memory-has-value?
                  {:opts {:schema [:map [:answer :string]]}
                   :st-memory (:st-memory (:context built))}))))))

(deftest st-memory-has-value-negative-test
  (testing "st-memory-has-value? returns false when schema doesn't match"
    (let [built (bt/build [:action (fn [_] bt/success)]
                          {:st-memory {:answer 42}})]
      (is (false? (bt/st-memory-has-value?
                   {:opts {:path [:answer] :schema :string}
                    :st-memory (:st-memory (:context built))}))))))

;; ============================================================================
;; Integration: condition using st-memory-has-value?
;; ============================================================================

(deftest condition-with-st-memory-has-value-test
  (testing "Condition node using st-memory-has-value? as condition-fn"
    (let [built (bt/build [:sequence
                           [:action (fn [ctx]
                                      (swap! (:st-memory ctx) assoc :answer "hello")
                                      bt/success)]
                           [:condition {:path [:answer] :schema :string}
                            bt/st-memory-has-value?]]
                          {})]
      (is (= bt/success (bt/run built))))))
