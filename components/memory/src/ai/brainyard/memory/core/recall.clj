;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.core.recall
  "Multi-step recall pipeline for cross-layer memory search.

  Implements a 4-step recall process:
  1. Semantic FTS search on semantic_facts (BM25)
  2. Extract keywords from semantic results
  3. Episodic FTS search using extracted keywords
  4. Combine via Reciprocal Rank Fusion (RRF)

  This is the core new piece that does not exist in cloudcast
  as a standalone module."
  (:require [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.memory.core.fts :as fts]
            [ai.brainyard.memory.core.episodic :as episodic]
            [ai.brainyard.memory.core.semantic :as semantic]))

;; =====================================================
;; Reciprocal Rank Fusion
;; =====================================================

(defn- extract-id
  "Extract ID from a result record."
  [record]
  (:id record))

(defn- extract-content
  "Extract content from a result record."
  [record]
  (:content record))

(defn reciprocal-rank-fusion
  "Combine rankings from multiple result lists using RRF.

  RRF formula: score_i = sum(weight / (k + rank))

  Parameters:
    results-maps - Map of {:layer-name {:results [...] :weight w}}
    k - Smoothing constant (default 60)

  Returns: Combined results sorted by RRF score"
  [results-maps & {:keys [k] :or {k 60}}]
  (let [;; Build layer -> {id -> rank} map
        layer-ranks (into {}
                          (for [[layer {:keys [results]}] results-maps]
                            [layer (into {}
                                         (map-indexed
                                          (fn [idx r]
                                            [(str layer "-" (extract-id r)) (inc idx)])
                                          results))]))

        ;; Build composite-id -> original record map
        all-results (for [[layer {:keys [results]}] results-maps
                          r results]
                      (assoc r
                             :_id (str layer "-" (extract-id r))
                             :_layer layer))
        id->record (into {} (map (juxt :_id identity) all-results))

        ;; Calculate RRF score for each record
        rrf-scores (for [[id record] id->record]
                     (let [layer (:_layer record)
                           weight (get-in results-maps [layer :weight] 1.0)
                           rank (get-in layer-ranks [layer id])
                           score (if rank
                                   (* weight (/ 1.0 (+ k rank)))
                                   0.0)]
                       {:id id :rrf-score score :record record}))]

    ;; Sort by RRF score and return records
    (vec
     (->> rrf-scores
          (sort-by :rrf-score >)
          (map (fn [{:keys [record rrf-score]}]
                 (assoc record :_rrf_score rrf-score)))))))

;; =====================================================
;; Multi-Step Recall Pipeline
;; =====================================================

(defn recall-pipeline
  "Execute the multi-step recall pipeline.

  Steps:
   1. Semantic FTS search on semantic_facts (BM25)
   2. Extract keywords from semantic results
   3. Episodic FTS search using extracted keywords
   4. Combine via RRF

  Parameters:
    ds - Database datasource
    user-id - User identifier
    query - Search query string

  Options:
    :semantic-limit - Max semantic results (default 10)
    :episodic-limit - Max episodic results (default 10)
    :total-limit - Max combined results (default 20)
    :semantic-weight - RRF weight for semantic (default 0.6)
    :episodic-weight - RRF weight for episodic (default 0.4)
    :session-id - Optional session filter for episodic search

  Returns:
    {:facts [...] :episodes [...] :combined [...] :keywords [...] :query q}"
  [ds user-id query & {:keys [semantic-limit episodic-limit total-limit
                              semantic-weight episodic-weight session-id]
                       :or {semantic-limit 10 episodic-limit 10 total-limit 20
                            semantic-weight 0.6 episodic-weight 0.4}}]
  (try
    ;; Step 1: Semantic FTS search
    (let [facts (semantic/search-fts ds query semantic-limit
                                     :user-id user-id)

          ;; Step 2: Extract keywords from semantic results + original query
          result-text (->> facts
                           (map extract-content)
                           (cons query)
                           (filter some?)
                           (clojure.string/join " "))
          keywords (fts/extract-keywords result-text
                                         :max-keywords 15)

          ;; Step 3: Episodic FTS search using keywords
          keyword-query (when (seq keywords)
                          (clojure.string/join " " keywords))
          episodes (if keyword-query
                     (episodic/search-fts ds keyword-query
                                          :limit episodic-limit
                                          :session-id session-id)
                     ;; Fallback: search with original query
                     (episodic/search-fts ds query
                                          :limit episodic-limit
                                          :session-id session-id))

          ;; Step 4: Combine via RRF
          combined (when (or (seq facts) (seq episodes))
                     (vec (take total-limit
                                (reciprocal-rank-fusion
                                 {:semantic {:results facts :weight semantic-weight}
                                  :episodic {:results episodes :weight episodic-weight}}))))]

      {:facts (vec facts)
       :episodes (vec episodes)
       :combined (or combined [])
       :keywords keywords
       :query query})

    (catch Exception e
      (mulog/error ::recall-pipeline-failed :error (ex-message e))
      {:facts []
       :episodes []
       :combined []
       :keywords []
       :query query
       :error (ex-message e)})))
