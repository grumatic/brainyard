;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.bt-test
  "Tests for BT visualization utilities moved from behavior-tree nodes-ext."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.behavior-tree.interface :as bt]
            [ai.brainyard.agent.core.bt :as agent-bt]))

;; ============================================================================
;; BT Visualization (btree->jstree)
;; ============================================================================

(deftest btree-visualization-test
  (testing "btree->jstree produces correct tree structure"
    (let [built (bt/build
                 [:sequence {:id :root}
                  [:fallback {:id :try-strategies}
                   [:condition {:id :check-cache} (fn [_] true)]
                   [:action {:id :compute} (fn [_] bt/success)]]
                  [:repeat {:id :retry :max-n 3}
                   [:action {:id :save} (fn [_] bt/success)]]]
                 {})
          tree (:tree built)
          jstree (agent-bt/btree->jstree tree)]
      ;; Root is sequence
      (is (= ":root" (:id jstree)))
      (is (clojure.string/includes? (:text jstree) "sequence"))
      ;; Has 2 children: fallback and repeat
      (is (= 2 (count (:children jstree))))
      ;; First child is fallback with 2 children
      (let [fallback (first (:children jstree))]
        (is (= ":try-strategies" (:id fallback)))
        (is (= 2 (count (:children fallback)))))
      ;; Second child is repeat with 1 child
      (let [repeat-node (second (:children jstree))]
        (is (= ":retry" (:id repeat-node)))
        (is (= 1 (count (:children repeat-node))))))))

;; ============================================================================
;; get-btree-node finds nodes by ID
;; ============================================================================

(deftest get-btree-node-test
  (testing "get-btree-node finds deeply nested nodes"
    (let [built (bt/build
                 [:sequence {:id :root}
                  [:fallback {:id :branch-a}
                   [:action {:id :action-a1} (fn [_] bt/success)]
                   [:action {:id :action-a2} (fn [_] bt/success)]]
                  [:repeat {:id :loop}
                   [:sequence {:id :inner}
                    [:condition {:id :check} (fn [_] true)]
                    [:action {:id :deep-action} (fn [_] bt/success)]]]]
                 {})
          tree (:tree built)]
      ;; Find root
      (is (= :root (:id (agent-bt/get-btree-node tree :root))))
      ;; Find nested action
      (let [found (agent-bt/get-btree-node tree :action-a2)]
        (is (some? found))
        (is (= :action (:type found))))
      ;; Find deeply nested action inside repeat
      (let [found (agent-bt/get-btree-node tree :deep-action)]
        (is (some? found))
        (is (= :action (:type found))))
      ;; Find condition inside repeat > sequence
      (let [found (agent-bt/get-btree-node tree :check)]
        (is (some? found))
        (is (= :condition (:type found))))
      ;; Non-existent node returns nil
      (is (nil? (agent-bt/get-btree-node tree :nonexistent))))))

;; ============================================================================
;; get-btree-node-info extracts metadata
;; ============================================================================

(deftest get-btree-node-info-test
  (testing "get-btree-node-info extracts correct metadata"
    (let [built (bt/build
                 [:sequence {:id :pipeline}
                  [:action {:id :step-1 :some-opt "value"} (fn [_] bt/success)]
                  [:repeat {:id :loop :max-n 5}
                   [:condition {:id :done-check} (fn [_] true)]]]
                 {})
          tree (:tree built)]
      ;; Sequence info
      (let [info (agent-bt/get-btree-node-info tree)]
        (is (= :pipeline (:id info)))
        (is (= :sequence (:type info)))
        (is (= 2 (count (:children info)))))
      ;; Action info
      (let [action (agent-bt/get-btree-node tree :step-1)
            info (agent-bt/get-btree-node-info action)]
        (is (= :step-1 (:id info)))
        (is (= :action (:type info))))
      ;; Repeat info
      (let [repeat-node (agent-bt/get-btree-node tree :loop)
            info (agent-bt/get-btree-node-info repeat-node)]
        (is (= :loop (:id info)))
        (is (= :repeat (:type info)))
        (is (= :done-check (:child info)))))))
