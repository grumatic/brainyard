;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.analytics.interface-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.analytics.interface :as analytics]))

;; ============================================================================
;; Mock Data
;; ============================================================================

(def ^:private test-messages
  [{:role "user" :content "Fix the thing"}
   {:role "assistant" :content "I've fixed the payment service by adding a null check to processPayment()."}
   {:role "user" :content "No, I meant fix the payment service's error handling specifically"}
   {:role "assistant" :content "Done. Added proper error handling with try-catch around processPayment()."}])

(def ^:private good-messages
  [{:role "user" :content "In /src/services/payment.ts, the processPayment() function throws TypeError when amount is null. Add a null check that returns {success: false, error: 'invalid_amount'} for null or negative values."}
   {:role "assistant" :content "Done. Added the null check with proper error return."}])

;; ============================================================================
;; analyze-session (without usage tracker)
;; ============================================================================

(deftest test-analyze-session-basic
  (testing "Full analysis without usage tracker or LLM"
    (let [result (analytics/analyze-session
                  {:session-id "test-1"
                   :user-id "user-1"
                   :messages test-messages
                   :usage-tracker nil}
                  :skip-llm-analysis true)]
      (is (= "test-1" (:session-id result)))
      (is (= "user-1" (:user-id result)))
      (is (some? (:timestamp result)))
      ;; PQS
      (is (contains? result :pqs))
      (is (<= 0 (get-in result [:pqs :overall-score]) 100))
      (is (map? (get-in result [:pqs :dimensions])))
      ;; Waste
      (is (contains? result :waste))
      (is (vector? (get-in result [:waste :patterns])))
      ;; Cost
      (is (contains? result :cost))
      (is (map? (get-in result [:cost :actual]))))))

(deftest test-analyze-session-quality-difference
  (testing "Good prompts score higher than bad prompts"
    (let [bad-result (analytics/analyze-session
                      {:session-id "bad" :user-id "u1" :messages test-messages}
                      :skip-llm-analysis true)
          good-result (analytics/analyze-session
                       {:session-id "good" :user-id "u1" :messages good-messages}
                       :skip-llm-analysis true)]
      (is (> (get-in good-result [:pqs :overall-score])
             (get-in bad-result [:pqs :overall-score]))
          "Good prompts should score higher than vague + corrected prompts"))))

;; ============================================================================
;; format-analytics
;; ============================================================================

(deftest test-format-analytics
  (testing "Produces formatted string"
    (let [result (analytics/analyze-session
                  {:session-id "fmt-test" :user-id "u1" :messages test-messages}
                  :skip-llm-analysis true)
          formatted (analytics/format-analytics result)]
      (is (string? formatted))
      (is (.contains formatted "Prompt Quality Score"))
      (is (.contains formatted "Waste Detection"))
      (is (.contains formatted "Cost Analysis")))))

;; ============================================================================
;; Individual Analyzers
;; ============================================================================

(deftest test-score-pqs-standalone
  (testing "score-pqs returns expected structure"
    (let [result (analytics/score-pqs test-messages)]
      (is (contains? result :overall-score))
      (is (contains? result :dimensions))
      (is (contains? result :adjustments))
      (is (contains? result :recommendations)))))

(deftest test-detect-waste-standalone
  (testing "detect-waste returns expected structure"
    (let [result (analytics/detect-waste test-messages {:totals {}} [])]
      (is (contains? result :patterns))
      (is (contains? result :total-waste-tokens))
      (is (contains? result :waste-percentage)))))

(deftest test-calculate-session-cost-standalone
  (testing "calculate-session-cost returns expected structure"
    (let [result (analytics/calculate-session-cost {:totals {}} [])]
      (is (contains? result :actual))
      (is (contains? result :optimal-estimate))
      (is (contains? result :throughput)))))
