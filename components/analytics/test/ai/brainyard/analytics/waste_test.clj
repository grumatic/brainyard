;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.analytics.waste-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.analytics.core.waste :as waste]))

;; ============================================================================
;; Mock Data
;; ============================================================================

(def ^:private usage-summary-bloated
  {:totals {:input-tokens 20000 :output-tokens 2000 :total-tokens 22000
            :total-cost 0.50 :call-count 5}})

(def ^:private usage-summary-normal
  {:totals {:input-tokens 4000 :output-tokens 2000 :total-tokens 6000
            :total-cost 0.15 :call-count 3}})

(def ^:private usage-history-overkill
  [{:model "claude-opus-4-6" :input-tokens 500 :output-tokens 50
    :cost {:total-cost 0.08} :latency-ms 1200}
   {:model "claude-opus-4-6" :input-tokens 400 :output-tokens 30
    :cost {:total-cost 0.06} :latency-ms 900}
   {:model "claude-sonnet-4-6" :input-tokens 2000 :output-tokens 800
    :cost {:total-cost 0.05} :latency-ms 2000}])

(def ^:private usage-history-normal
  [{:model "claude-sonnet-4-6" :input-tokens 2000 :output-tokens 800
    :cost {:total-cost 0.05} :latency-ms 2000}
   {:model "claude-sonnet-4-6" :input-tokens 1500 :output-tokens 600
    :cost {:total-cost 0.04} :latency-ms 1800}])

(def ^:private usage-history-big-system-prompt
  [{:model "claude-sonnet-4-6" :input-tokens 8000 :output-tokens 500
    :cost {:total-cost 0.10} :latency-ms 3000}
   {:model "claude-sonnet-4-6" :input-tokens 3000 :output-tokens 600
    :cost {:total-cost 0.04} :latency-ms 1800}
   {:model "claude-sonnet-4-6" :input-tokens 3200 :output-tokens 700
    :cost {:total-cost 0.05} :latency-ms 2000}])

(def ^:private messages-redundant
  [{:role "user" :content "How do I deploy the application to production?"}
   {:role "assistant" :content "You can deploy using..."}
   {:role "user" :content "How can I deploy my application to production environment?"}
   {:role "assistant" :content "As I mentioned..."}])

(def ^:private messages-unique
  [{:role "user" :content "How do I deploy the application?"}
   {:role "assistant" :content "You can deploy using..."}
   {:role "user" :content "What are the database migration steps?"}
   {:role "assistant" :content "Run the migration..."}])

;; ============================================================================
;; Context Bloat
;; ============================================================================

(deftest test-detect-context-bloat
  (testing "Flags high input/output ratio"
    (let [result (waste/detect-context-bloat usage-summary-bloated)]
      (is (some? result) "Should detect context bloat")
      (is (= :context-bloat (:pattern-id result)))
      (is (pos? (:waste-tokens result)))
      (is (pos? (:waste-cost result)))))

  (testing "No flag for normal ratio"
    (let [result (waste/detect-context-bloat usage-summary-normal)]
      (is (nil? result) "Should not flag normal ratio")))

  (testing "No flag for zero output"
    (let [result (waste/detect-context-bloat {:totals {:input-tokens 100 :output-tokens 0}})]
      (is (nil? result) "Zero output should not flag"))))

;; ============================================================================
;; Model Overkill
;; ============================================================================

(deftest test-detect-model-overkill
  (testing "Flags expensive model for small output"
    (let [result (waste/detect-model-overkill usage-history-overkill)]
      (is (some? result) "Should detect model overkill")
      (is (= :model-overkill (:pattern-id result)))
      (is (pos? (:waste-cost result)))))

  (testing "No flag for appropriate model usage"
    (let [result (waste/detect-model-overkill usage-history-normal)]
      (is (nil? result) "Should not flag sonnet for normal tasks"))))

;; ============================================================================
;; Oversized System Prompt
;; ============================================================================

(deftest test-detect-oversized-system-prompt
  (testing "Flags large first-call input"
    (let [result (waste/detect-oversized-system-prompt usage-history-big-system-prompt)]
      (is (some? result) "Should detect oversized system prompt")
      (is (= :oversized-system-prompt (:pattern-id result)))))

  (testing "No flag when calls are similar size"
    (let [result (waste/detect-oversized-system-prompt usage-history-normal)]
      (is (nil? result) "Similar-sized calls should not flag")))

  (testing "No flag with single call"
    (let [result (waste/detect-oversized-system-prompt [(first usage-history-normal)])]
      (is (nil? result) "Need at least 2 calls"))))

;; ============================================================================
;; Redundant Requests
;; ============================================================================

(deftest test-detect-redundant-requests
  (testing "Flags similar messages"
    (let [result (waste/detect-redundant-requests messages-redundant)]
      (is (some? result) "Should detect redundant requests")
      (is (= :redundant-requests (:pattern-id result)))
      (is (pos? (:waste-tokens result)))))

  (testing "No flag for unique messages"
    (let [result (waste/detect-redundant-requests messages-unique)]
      (is (nil? result) "Unique messages should not flag"))))

;; ============================================================================
;; Orchestrator
;; ============================================================================

(deftest test-detect-all-waste
  (testing "Returns expected structure"
    (let [result (waste/detect-all-waste messages-unique usage-summary-normal usage-history-normal)]
      (is (contains? result :patterns))
      (is (contains? result :total-waste-tokens))
      (is (contains? result :total-waste-cost))
      (is (contains? result :waste-percentage))
      (is (vector? (:patterns result)))))

  (testing "Detects multiple patterns"
    (let [result (waste/detect-all-waste messages-redundant usage-summary-bloated usage-history-overkill)]
      (is (>= (count (:patterns result)) 2) "Should detect at least context bloat + model overkill")
      (is (pos? (:total-waste-cost result)))))

  (testing "Empty session produces no waste"
    (let [result (waste/detect-all-waste [] {:totals {}} [])]
      (is (empty? (:patterns result))))))
