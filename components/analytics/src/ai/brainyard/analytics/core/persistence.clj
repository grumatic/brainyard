;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.analytics.core.persistence
  "Persist and query analytics data via the memory component.

   Stores analytics as semantic facts with special fact-types:
   - :pqs-score     — PQS score for a session
   - :usage-metric  — Token/cost metrics for a session
   - :waste-detection — Waste patterns found in a session

   All dependencies on memory component are soft (via requiring-resolve)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Soft Dependency Resolution
;; ============================================================================

(defn- resolve-mem-fn
  "Resolve a memory interface function, returning nil if unavailable."
  [sym]
  (try
    (requiring-resolve sym)
    (catch Exception _ nil)))

;; ============================================================================
;; Storage Functions
;; ============================================================================

(defn store-pqs-score!
  "Store PQS score as a semantic fact in the memory component.

   Parameters:
     memory-manager - MemoryManager instance
     session-id     - Session identifier
     pqs-result     - PQS result map from scoring

   Returns: stored fact or nil"
  [memory-manager session-id pqs-result]
  (when-let [write-fn (resolve-mem-fn 'ai.brainyard.memory.interface/write-entry)]
    (try
      (let [score (:overall-score pqs-result)
            dims (:dimensions pqs-result)
            content (format "Session %s: PQS %d/100. Specificity %d/25, Atomicity %d/25, Context %d/20, Criteria %d/20, Clarity %d/10."
                            session-id score
                            (:specificity dims 0)
                            (:task-atomicity dims 0)
                            (:context-completeness dims 0)
                            (:acceptance-criteria dims 0)
                            (:clarity dims 0))]
        (write-fn memory-manager :l3
                  {:kind       :pqs-score
                   :content    content
                   :user-id    (:user-id memory-manager)
                   :source     (str "analytics:session:" session-id)
                   :confidence 0.9
                   :data       {:session-id session-id
                                :score score
                                :dimensions dims
                                :adjustments (:adjustments pqs-result)
                                :recommendations (:recommendations pqs-result)
                                :timestamp (str (java.time.Instant/now))}}))
      (catch Exception e
        (mulog/warn ::store-pqs-score-failed :message "Failed to store PQS score" :exception e)
        nil))))

(defn store-usage-metrics!
  "Store usage metrics as a semantic fact.

   Parameters:
     memory-manager - MemoryManager instance
     session-id     - Session identifier
     cost-result    - Cost analysis result map

   Returns: stored fact or nil"
  [memory-manager session-id cost-result]
  (when-let [write-fn (resolve-mem-fn 'ai.brainyard.memory.interface/write-entry)]
    (try
      (let [actual (:actual cost-result)
            content (format "Session %s: Cost $%.4f (%d tokens, %d calls). Models: %s. Potential savings: $%.4f."
                            session-id
                            (get actual :total-cost 0)
                            (get actual :total-tokens 0)
                            (get actual :call-count 0)
                            (str/join ", " (keys (get actual :by-model {})))
                            (get cost-result :savings-potential 0))]
        (write-fn memory-manager :l3
                  {:kind       :usage-metric
                   :content    content
                   :user-id    (:user-id memory-manager)
                   :source     (str "analytics:session:" session-id)
                   :confidence 0.95
                   :data       {:session-id session-id
                                :total-cost (get actual :total-cost 0)
                                :total-tokens (get actual :total-tokens 0)
                                :call-count (get actual :call-count 0)
                                :by-model (get actual :by-model {})
                                :savings-potential (get cost-result :savings-potential 0)
                                :throughput (:throughput cost-result)
                                :timestamp (str (java.time.Instant/now))}}))
      (catch Exception e
        (mulog/warn ::store-usage-metrics-failed :message "Failed to store usage metrics" :exception e)
        nil))))

(defn store-waste-detection!
  "Store waste detection results as a semantic fact.

   Parameters:
     memory-manager - MemoryManager instance
     session-id     - Session identifier
     waste-result   - Waste detection result map

   Returns: stored fact or nil"
  [memory-manager session-id waste-result]
  (when-let [write-fn (resolve-mem-fn 'ai.brainyard.memory.interface/write-entry)]
    (when (seq (:patterns waste-result))
      (try
        (let [patterns (:patterns waste-result)
              pattern-names (str/join ", " (map #(name (:pattern-id %)) patterns))
              content (format "Session %s: %d waste pattern(s) detected: %s. Total waste: %d tokens ($%.4f, %.1f%%)."
                              session-id
                              (count patterns)
                              pattern-names
                              (:total-waste-tokens waste-result)
                              (:total-waste-cost waste-result)
                              (:waste-percentage waste-result))]
          (write-fn memory-manager :l3
                    {:kind       :waste-detection
                     :content    content
                     :user-id    (:user-id memory-manager)
                     :source     (str "analytics:session:" session-id)
                     :confidence 0.85
                     :data       {:session-id session-id
                                  :patterns (mapv #(select-keys % [:pattern-id :severity :detail
                                                                   :waste-tokens :waste-cost])
                                                  patterns)
                                  :total-waste-tokens (:total-waste-tokens waste-result)
                                  :total-waste-cost (:total-waste-cost waste-result)
                                  :waste-percentage (:waste-percentage waste-result)
                                  :timestamp (str (java.time.Instant/now))}}))
        (catch Exception e
          (mulog/warn ::store-waste-detection-failed :message "Failed to store waste detection" :exception e)
          nil)))))

;; ============================================================================
;; Query Functions
;; ============================================================================

(defn query-session-analytics
  "Query stored analytics for a specific session.

   Parameters:
     memory-manager - MemoryManager instance
     session-id     - Session identifier

   Returns: map with :pqs, :usage, :waste keys, or nil"
  [memory-manager session-id]
  (when-let [read-fn (resolve-mem-fn 'ai.brainyard.memory.interface/read-entries)]
    (try
      (let [query         (str "analytics session " session-id)
            results       (read-fn memory-manager :l3 {:text query} {:limit 10})
            source-prefix (str "analytics:session:" session-id)
            session-facts (->> results
                               (filter #(let [src (:source %)]
                                          (and src (.startsWith (str src) source-prefix)))))]
        (when (seq session-facts)
          (let [by-kind (group-by :kind session-facts)]
            {:pqs   (first (get by-kind :pqs-score))
             :usage (first (get by-kind :usage-metric))
             :waste (first (get by-kind :waste-detection))})))
      (catch Exception e
        (mulog/warn ::query-session-analytics-failed :message "Failed to query session analytics" :exception e)
        nil))))

(defn query-analytics-trends
  "Query cross-session analytics trends for a user.

   Parameters:
     memory-manager - MemoryManager instance
     user-id        - User identifier

   Options:
     :limit     - Max results (default 20)
     :fact-type - Filter: :pqs-score, :usage-metric, or :waste-detection

   Returns: vector of analytics facts, most recent first"
  [memory-manager user-id & {:keys [limit fact-type]
                             :or {limit 20}}]
  (when-let [read-fn (resolve-mem-fn 'ai.brainyard.memory.interface/read-entries)]
    (try
      (let [query   (str "analytics session "
                         (when fact-type (str (name fact-type) " ")))
            results (read-fn memory-manager :l3
                             (cond-> {:text query}
                               fact-type (assoc :kind fact-type))
                             {:limit limit})]
        (->> results
             (filter #(let [src (:source %)]
                        (and src (.startsWith (str src) "analytics:session:"))))
             (sort-by #(or (get-in % [:data :timestamp])
                           (:created-at %))
                      #(compare %2 %1))
             (take limit)
             vec))
      (catch Exception e
        (mulog/warn ::query-analytics-trends-failed :message "Failed to query analytics trends" :exception e)
        []))))

;; ============================================================================
;; Batch Persistence
;; ============================================================================

(defn persist-analytics!
  "Persist all analytics results for a session.

   Parameters:
     memory-manager - MemoryManager instance
     analytics      - Full analytics result map

   Returns: {:facts-stored N}"
  [memory-manager analytics]
  (let [session-id (:session-id analytics)
        stored (atom 0)]
    (when (:pqs analytics)
      (when (store-pqs-score! memory-manager session-id (:pqs analytics))
        (swap! stored inc)))
    (when (:cost analytics)
      (when (store-usage-metrics! memory-manager session-id (:cost analytics))
        (swap! stored inc)))
    (when (:waste analytics)
      (when (store-waste-detection! memory-manager session-id (:waste analytics))
        (swap! stored inc)))
    {:facts-stored @stored}))
