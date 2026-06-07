;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.context-budget-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.agent.core.context-budget :as cb]))

;; ============================================================================
;; estimate-tokens / total-tokens / section-tokens
;; ============================================================================

(deftest estimate-tokens-test
  (testing "chars/4 heuristic"
    (is (= 0 (cb/estimate-tokens nil)))
    (is (= 0 (cb/estimate-tokens "")))
    (is (= 1 (cb/estimate-tokens "abcd")))
    (is (= 1 (cb/estimate-tokens "ab")))         ; ceil(2/4)=1
    (is (= 25 (cb/estimate-tokens (apply str (repeat 100 "x")))))))

(deftest section-and-total-tokens-test
  (let [secs {:a (apply str (repeat 100 "x"))    ; 25 tokens
              :b (apply str (repeat 200 "y"))    ; 50 tokens
              :c "hi"}                            ; 1 token
        order [:a :b :c]]
    (testing "section-tokens reports per-section"
      (is (= {:a 25 :b 50 :c 1} (cb/section-tokens secs order))))
    (testing "total-tokens sums section-tokens"
      (is (= 76 (cb/total-tokens secs order))))
    (testing "missing keys are omitted"
      (is (= {:a 25 :b 50} (cb/section-tokens secs [:a :b :missing])))
      (is (= 75 (cb/total-tokens secs [:a :b :missing]))))))

;; ============================================================================
;; compose
;; ============================================================================

(deftest compose-test
  (testing "joins sections in order with double newline"
    (is (= "A\n\nB\n\nC"
           (cb/compose {:a "A" :b "B" :c "C"} [:a :b :c]))))
  (testing "drops missing and blank sections"
    (is (= "A\n\nC"
           (cb/compose {:a "A" :b "" :c "C"} [:a :b :c])))
    (is (= "A\n\nC"
           (cb/compose {:a "A" :c "C"} [:a :b :c]))))
  (testing "respects order argument"
    (is (= "C\n\nA"
           (cb/compose {:a "A" :b "B" :c "C"} [:c :a])))))

;; ============================================================================
;; model->budget
;; ============================================================================

(deftest model->budget-test
  (testing "default — 128k context, 4k output, 10% safety"
    ;; usable = 128000 - 4096 = 123904; margin = ceil(123904 * 0.10) = 12391
    (is (= 111513 (cb/model->budget {}))))
  (testing "explicit overrides"
    (is (= 90 (cb/model->budget {:max-context-tokens 100
                                 :max-output-tokens 0
                                 :safety-ratio 0.10}))))
  (testing "negative inputs clamp to zero"
    (is (zero? (cb/model->budget {:max-context-tokens 100
                                  :max-output-tokens 1000
                                  :safety-ratio 0.0})))
    (is (zero? (cb/model->budget {:max-context-tokens 100
                                  :max-output-tokens 0
                                  :safety-ratio 1.0})))))

;; ============================================================================
;; enforce — happy path
;; ============================================================================

(deftest enforce-under-budget-no-op-test
  (testing "when total <= budget, returns sections unchanged with no compactions"
    (let [secs {:role "AGENT ROLE" :previous-turns "Q1: ...\nA1: ..."}
          order [:role :previous-turns]
          result (cb/enforce {:sections secs
                              :order order
                              :budget 100000
                              :strategies {}})]
      (is (= secs (:sections result)))
      (is (false? (:over-budget? result)))
      (is (empty? (:compactions result)))
      (is (= (cb/total-tokens secs order) (:total-tokens result))))))

;; ============================================================================
;; enforce — compacts lowest-priority first
;; ============================================================================

(deftest enforce-priority-order-test
  (testing "compactable sections are visited in ascending :priority"
    (let [;; priorities from default-section-policies:
          ;;   previous-turns 50, conversation-history 60, live-artifacts 70
          big (apply str (repeat 4000 "x"))     ; 1000 tokens each
          secs {:previous-turns big
                :conversation-history big
                :live-artifacts big}
          order [:previous-turns :conversation-history :live-artifacts]
          ;; record visitation order
          visited (atom [])
          dropper (fn [k]
                    (fn [s]
                      (swap! visited conj k)
                      (dissoc s k)))
          strategies {:bump-previous-turns  (dropper :previous-turns)
                      :shrink-conversation  (dropper :conversation-history)
                      :drop-live-artifacts  (dropper :live-artifacts)}
          ;; budget too tight to keep any of them
          result (cb/enforce {:sections secs
                              :order order
                              :budget 0
                              :strategies strategies})]
      (is (= [:previous-turns :conversation-history :live-artifacts] @visited))
      (is (zero? (:total-tokens result)))
      (is (false? (:over-budget? result)))
      (is (= 3 (count (:compactions result)))))))

;; ============================================================================
;; enforce — strategy makes no progress → drop section
;; ============================================================================

(deftest enforce-no-progress-drops-section-test
  (testing "if a strategy returns the section unchanged, enforce drops it"
    (let [big (apply str (repeat 8000 "y"))    ; 2000 tokens
          secs {:previous-turns big}
          order [:previous-turns]
          strategies {:bump-previous-turns identity}  ; no-op strategy
          result (cb/enforce {:sections secs
                              :order order
                              :budget 100
                              :strategies strategies})]
      (is (not (contains? (:sections result) :previous-turns)))
      (is (zero? (:total-tokens result)))
      (is (= [:dropped] (mapv :strategy (:compactions result)))))))

;; ============================================================================
;; enforce — :keep-floor? section is retained, not dropped, when stuck
;; ============================================================================

(deftest enforce-keep-floor-retains-section-test
  (testing ":keep-floor? section keeps its floor (not dropped) when its strategy can't reduce"
    ;; Uses default-section-policies: :live-artifacts has :keep-floor? true,
    ;; :previous-turns does not. A no-op live-artifacts strategy models 'only
    ;; pinned/system artifacts remain' — those bytes must survive.
    (let [big (apply str (repeat 8000 "y"))    ; 2000 tokens each
          secs {:previous-turns big :live-artifacts big}
          order [:previous-turns :live-artifacts]
          strategies {:bump-previous-turns (fn [s] (dissoc s :previous-turns))
                      :drop-live-artifacts identity}   ; no progress
          result (cb/enforce {:sections secs
                              :order order
                              :budget 100             ; far too tight
                              :strategies strategies})]
      ;; live-artifacts floor is KEPT verbatim despite remaining over budget
      (is (contains? (:sections result) :live-artifacts))
      (is (= big (:live-artifacts (:sections result))))
      (is (true? (:over-budget? result)))
      ;; audit trail marks it :kept-floor, not :dropped
      (is (some #(and (= :live-artifacts (:section %)) (= :kept-floor (:strategy %)))
                (:compactions result)))
      ;; the non-floor section was still drained
      (is (not (contains? (:sections result) :previous-turns))))))

;; ============================================================================
;; enforce — over-budget when no strategies available
;; ============================================================================

(deftest enforce-over-budget-test
  (testing "when system-only sections (no compact) exceed budget, reports over-budget"
    (let [big (apply str (repeat 8000 "z"))     ; 2000 tokens
          secs {:role big}                       ; no :compact strategy in policy
          order [:role]
          result (cb/enforce {:sections secs
                              :order order
                              :budget 100
                              :strategies {}})]
      (is (true? (:over-budget? result)))
      (is (= secs (:sections result)))
      (is (empty? (:compactions result))))))

;; ============================================================================
;; enforce — gradual shrink stops once under budget
;; ============================================================================

(deftest enforce-stops-when-under-budget-test
  (testing "stops invoking strategies once total fits under budget"
    (let [;; Two sections, each 1000 tokens; budget tight enough to need
          ;; only ONE compaction.
          big (apply str (repeat 4000 "x"))
          secs {:previous-turns big :conversation-history big}
          order [:previous-turns :conversation-history]
          ;; previous-turns priority 50, conversation-history 60 — pt visited first
          strategies {:bump-previous-turns
                      (fn [s] (dissoc s :previous-turns))
                      :shrink-conversation
                      (fn [s] (dissoc s :conversation-history))}
          result (cb/enforce {:sections secs
                              :order order
                              :budget 1500
                              :strategies strategies})]
      ;; Only previous-turns should have been touched.
      (is (= [:bump-previous-turns] (mapv :strategy (:compactions result))))
      (is (false? (:over-budget? result)))
      (is (contains? (:sections result) :conversation-history))
      (is (not (contains? (:sections result) :previous-turns))))))
