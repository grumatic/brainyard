;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.analytics.cost-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.analytics.core.cost :as cost]))

;; ============================================================================
;; Mock Data
;; ============================================================================

(def ^:private usage-summary
  {:totals {:input-tokens 5000 :output-tokens 2000 :total-tokens 7000
            :total-cost 0.25 :call-count 3
            :cache-read-tokens 500 :cache-write-tokens 200
            :input-cost 0.10 :output-cost 0.15}
   :by-model {"claude-sonnet-4-6" {:input-tokens 3000 :output-tokens 1500
                                   :total-tokens 4500 :total-cost 0.15 :call-count 2}
              "claude-opus-4-6" {:input-tokens 2000 :output-tokens 500
                                 :total-tokens 2500 :total-cost 0.10 :call-count 1}}})

(def ^:private usage-history
  [{:model "claude-sonnet-4-6" :input-tokens 1500 :output-tokens 800
    :cost {:total-cost 0.08 :input-cost 0.03 :output-cost 0.05} :latency-ms 2000}
   {:model "claude-sonnet-4-6" :input-tokens 1500 :output-tokens 700
    :cost {:total-cost 0.07 :input-cost 0.03 :output-cost 0.04} :latency-ms 1800}
   {:model "claude-opus-4-6" :input-tokens 2000 :output-tokens 50
    :cost {:total-cost 0.10 :input-cost 0.04 :output-cost 0.06} :latency-ms 3000}])

;; ============================================================================
;; Cost Breakdown
;; ============================================================================

(deftest test-session-cost-breakdown
  (testing "Returns structured breakdown"
    (let [result (cost/session-cost-breakdown usage-summary)]
      (is (= 0.25 (:total-cost result)))
      (is (= 7000 (:total-tokens result)))
      (is (= 3 (:call-count result)))
      (is (= 500 (get-in result [:cache :read-tokens])))
      (is (map? (:by-model result)))
      (is (contains? (:by-model result) "claude-sonnet-4-6"))
      (is (= :sonnet (get-in result [:by-model "claude-sonnet-4-6" :tier])))))

  (testing "Handles empty summary"
    (let [result (cost/session-cost-breakdown {:totals {} :by-model {}})]
      (is (= 0 (:total-cost result)))
      (is (empty? (:by-model result))))))

;; ============================================================================
;; Optimal Cost
;; ============================================================================

(deftest test-estimate-optimal-cost
  (testing "Identifies savings from model downgrade"
    (let [result (cost/estimate-optimal-cost usage-history)]
      (is (map? result))
      (is (contains? result :optimal-cost))
      (is (contains? result :savings-from-model-selection))
      ;; Opus call with 50 output tokens should be flagged for downgrade
      (is (>= (:savings-from-model-selection result) 0))))

  (testing "Handles empty history"
    (let [result (cost/estimate-optimal-cost [])]
      (is (zero? (:optimal-cost result)))
      (is (zero? (:total-savings result))))))

;; ============================================================================
;; Throughput Stats
;; ============================================================================

(deftest test-throughput-stats
  (testing "Calculates throughput from history"
    (let [result (cost/throughput-stats usage-history)]
      (is (pos? (:output-tokens-per-sec result)))
      (is (pos? (:avg-latency-ms result)))
      (is (= 3 (:calls result)))))

  (testing "Handles empty history"
    (let [result (cost/throughput-stats [])]
      (is (= 0 (:output-tokens-per-sec result)))
      (is (= 0 (:calls result)))))

  (testing "Uses session duration when provided"
    (let [result (cost/throughput-stats usage-history 10000)]
      (is (pos? (:output-tokens-per-sec result))))))

;; ============================================================================
;; Combined Cost Analysis
;; ============================================================================

(deftest test-calculate-session-cost
  (testing "Returns full analysis"
    (let [result (cost/calculate-session-cost usage-summary usage-history)]
      (is (contains? result :actual))
      (is (contains? result :optimal-estimate))
      (is (contains? result :savings-potential))
      (is (contains? result :throughput))
      (is (= 0.25 (get-in result [:actual :total-cost]))))))
