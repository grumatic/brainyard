;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.analytics.core.cost
  "Cost calculator and optimization analysis for agent sessions.

   Wraps existing usage.clj data with:
   - Structured cost breakdown by model
   - Optimal cost estimation (model right-sizing)
   - Throughput statistics"
  (:require [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Model Tier Classification
;; ============================================================================

(def ^:private model-tiers
  "Model → tier classification for right-sizing analysis."
  {"claude-opus-4-6"           :opus
   "claude-opus-4-20250514"    :opus
   "claude-3-opus-20240229"    :opus
   "claude-sonnet-4-6"         :sonnet
   "claude-sonnet-4-20250514"  :sonnet
   "claude-3-5-sonnet-20241022" :sonnet
   "claude-haiku-4-5"          :haiku
   "claude-3-5-haiku-20241022" :haiku
   "gpt-4o"                    :opus
   "gpt-4o-mini"               :haiku
   "o1"                        :opus
   "o1-mini"                   :sonnet
   "o3"                        :opus
   "o3-mini"                   :sonnet})

(def ^:private tier-rates
  "Approximate cost per 1M tokens by tier (blended input+output)."
  {:opus   20.0
   :sonnet 5.0
   :haiku  0.80})

(defn- model-tier
  "Get tier for a model name. Defaults to :sonnet for unknown models."
  [model]
  (get model-tiers model :sonnet))

;; ============================================================================
;; Cost Breakdown
;; ============================================================================

(defn session-cost-breakdown
  "Structured cost breakdown from usage summary.

   Parameters:
     usage-summary - From usage tracker: {:totals {...} :by-model {...}}

   Returns:
     {:total-cost N
      :input-cost N
      :output-cost N
      :total-tokens N
      :input-tokens N
      :output-tokens N
      :call-count N
      :cache {:read-tokens N :write-tokens N}
      :by-model {model-name {:cost N :calls N :input-tokens N :output-tokens N :tier :keyword}}}"
  [usage-summary]
  (let [totals (or (:totals usage-summary) {})
        by-model (or (:by-model usage-summary) {})]
    {:total-cost (get totals :total-cost 0)
     :input-cost (get totals :input-cost 0)
     :output-cost (get totals :output-cost 0)
     :total-tokens (get totals :total-tokens 0)
     :input-tokens (get totals :input-tokens 0)
     :output-tokens (get totals :output-tokens 0)
     :call-count (get totals :call-count 0)
     :cache {:read-tokens (get totals :cache-read-tokens 0)
             :write-tokens (get totals :cache-write-tokens 0)}
     :by-model (reduce-kv
                (fn [m model model-data]
                  (assoc m model
                         {:cost (get model-data :total-cost 0)
                          :calls (get model-data :call-count 0)
                          :input-tokens (get model-data :input-tokens 0)
                          :output-tokens (get model-data :output-tokens 0)
                          :tier (model-tier model)}))
                {}
                by-model)}))

;; ============================================================================
;; Optimal Cost Estimation
;; ============================================================================

(defn estimate-optimal-cost
  "Estimate what the session would cost with optimal model selection.
   Rules:
   - Simple tasks (< 100 output tokens): use haiku-tier pricing
   - Medium tasks (100-500 output tokens): use sonnet-tier pricing
   - Complex tasks (> 500 output tokens, tool loops): keep current model pricing
   Subtracts identified waste cost.

   Parameters:
     usage-history - Call history [{:model :input-tokens :output-tokens :cost {...}}]
     waste-results - From detect-all-waste (optional)

   Returns:
     {:optimal-cost N :savings-from-model-selection N :savings-from-waste N :total-savings N}"
  [usage-history & [{:keys [total-waste-cost] :as waste-results}]]
  (let [optimal-per-call
        (map (fn [call]
               (let [output-tokens (get call :output-tokens 0)
                     input-tokens (get call :input-tokens 0)
                     total-tokens (+ input-tokens output-tokens)
                     current-cost (get-in call [:cost :total-cost] 0)
                     optimal-tier (cond
                                    (< output-tokens 100)  :haiku
                                    (< output-tokens 500)  :sonnet
                                    :else                  (model-tier (get call :model "unknown")))
                     optimal-cost (* (/ (double total-tokens) 1000000.0)
                                     (get tier-rates optimal-tier 5.0))]
                 {:current-cost current-cost
                  :optimal-cost optimal-cost
                  :savings (max 0 (- current-cost optimal-cost))}))
             usage-history)
        total-current (reduce + (map :current-cost optimal-per-call))
        total-optimal (reduce + (map :optimal-cost optimal-per-call))
        model-savings (max 0 (- total-current total-optimal))
        waste-savings (or total-waste-cost 0)]
    {:optimal-cost (- total-current model-savings waste-savings)
     :savings-from-model-selection model-savings
     :savings-from-waste waste-savings
     :total-savings (+ model-savings waste-savings)}))

;; ============================================================================
;; Throughput Statistics
;; ============================================================================

(defn throughput-stats
  "Calculate tokens/second throughput from usage history.

   Parameters:
     usage-history     - Call history [{:input-tokens :output-tokens :latency-ms ...}]
     session-duration-ms - Total wall-clock session time (optional)

   Returns:
     {:input-tokens-per-sec N :output-tokens-per-sec N
      :avg-latency-ms N :total-latency-ms N
      :calls N}"
  [usage-history & [session-duration-ms]]
  (if (empty? usage-history)
    {:input-tokens-per-sec 0
     :output-tokens-per-sec 0
     :avg-latency-ms 0
     :total-latency-ms 0
     :calls 0}
    (let [total-input (reduce + (map #(get % :input-tokens 0) usage-history))
          total-output (reduce + (map #(get % :output-tokens 0) usage-history))
          latencies (keep :latency-ms usage-history)
          total-latency (if (seq latencies) (reduce + latencies) 0)
          avg-latency (if (seq latencies)
                        (/ (double total-latency) (count latencies))
                        0)
          ;; Use total latency for per-sec calculation (not wall clock)
          duration-sec (/ (double (or session-duration-ms total-latency)) 1000.0)]
      {:input-tokens-per-sec (if (pos? duration-sec)
                               (Math/round (/ (double total-input) duration-sec))
                               0)
       :output-tokens-per-sec (if (pos? duration-sec)
                                (Math/round (/ (double total-output) duration-sec))
                                0)
       :avg-latency-ms (Math/round (double avg-latency))
       :total-latency-ms total-latency
       :calls (count usage-history)})))

;; ============================================================================
;; Combined Cost Analysis
;; ============================================================================

(defn calculate-session-cost
  "Calculate detailed cost breakdown with optimization analysis.

   Parameters:
     usage-summary - From usage tracker
     usage-history - Usage history records

   Options:
     :waste-results      - Waste detection results
     :session-duration-ms - Session wall-clock time

   Returns:
     {:actual {:total-cost N :by-model {...} ...}
      :optimal-estimate N
      :savings-potential N
      :throughput {:input-tokens-per-sec N ...}}"
  [usage-summary usage-history & {:keys [waste-results session-duration-ms]}]
  (let [actual (session-cost-breakdown usage-summary)
        optimal (estimate-optimal-cost usage-history [waste-results])
        throughput (throughput-stats usage-history session-duration-ms)]
    {:actual actual
     :optimal-estimate (:optimal-cost optimal)
     :savings-potential (:total-savings optimal)
     :savings-detail (dissoc optimal :optimal-cost)
     :throughput throughput}))
