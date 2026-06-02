;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.cache
  "Semantic cache for LLM responses.
   Ported from cloudcast.backend.agent.cache.core — reimplemented in pure Clojure
   using embedding similarity instead of Python LangCache.

   Caches prompt/response pairs and retrieves them by semantic similarity.
   Reduces LLM costs for repeated or near-identical queries.

   Usage:
     (def cache (create-cache {:max-entries 1000}))
     (cache-put cache query response {:model \"haiku\"})
     (cache-get cache query)  ;; => {:response \"...\" :similarity 0.99}
     (with-cache cache query opts (fn [] (llm-call ...)))"
  (:require [ai.brainyard.mulog.interface :as mulog]))

;; =====================================================
;; Similarity Thresholds (from cloudcast)
;; =====================================================

(defn- default-threshold
  "Dynamic threshold based on query length.
   Shorter queries need higher similarity to match."
  [query]
  (let [word-count (count (re-seq #"\S+" (or query "")))]
    (cond
      (<= word-count 3) 0.98   ;; Very short — must be near-exact
      (<= word-count 6) 0.96   ;; Short — high similarity
      :else             0.94))) ;; Longer — moderate similarity OK

;; =====================================================
;; Cosine Similarity
;; =====================================================

(defn- dot-product [a b]
  (reduce + (map * a b)))

(defn- magnitude [v]
  (Math/sqrt (reduce + (map #(* % %) v))))

(defn cosine-similarity
  "Compute cosine similarity between two vectors."
  [a b]
  (let [mag-a (magnitude a)
        mag-b (magnitude b)]
    (if (or (zero? mag-a) (zero? mag-b))
      0.0
      (/ (dot-product a b) (* mag-a mag-b)))))

;; =====================================================
;; Cache Store
;; =====================================================

(defrecord CacheEntry [query response embedding attributes timestamp])

(defn create-cache
  "Create a semantic cache store.
   Options:
   - :max-entries  - Maximum cache entries (default 1000)
   - :embed-fn     - Function (fn [text] -> vector) for computing embeddings
                     If nil, cache is disabled (passthrough mode)"
  [& {:keys [max-entries embed-fn]
      :or {max-entries 1000}}]
  (atom {:entries []
         :max-entries max-entries
         :embed-fn embed-fn
         :hits 0
         :misses 0}))

(defn cache-put
  "Store a query/response pair in the cache."
  [cache query response & {:keys [model provider] :as attrs}]
  (let [{:keys [embed-fn max-entries]} @cache]
    (when embed-fn
      (try
        (let [embedding (embed-fn query)
              entry (->CacheEntry query response embedding
                                  (or attrs {})
                                  (System/currentTimeMillis))]
          (swap! cache update :entries
                 (fn [entries]
                   (let [updated (conj entries entry)]
                     (if (> (count updated) max-entries)
                       (subvec updated (- (count updated) max-entries))
                       updated)))))
        (catch Exception e
          (mulog/warn ::cache-put-failed :message (.getMessage e)))))))

(defn cache-get
  "Look up a query in the cache by semantic similarity.
   Returns {:response str :similarity float :cached-query str} or nil."
  [cache query & {:keys [threshold]}]
  (let [{:keys [embed-fn entries]} @cache]
    (when (and embed-fn (seq entries))
      (try
        (let [query-emb (embed-fn query)
              threshold (or threshold (default-threshold query))
              best (reduce
                    (fn [best entry]
                      (let [sim (cosine-similarity query-emb (:embedding entry))]
                        (if (and (> sim threshold)
                                 (or (nil? best) (> sim (:similarity best))))
                          {:response (:response entry)
                           :similarity sim
                           :cached-query (:query entry)
                           :attributes (:attributes entry)}
                          best)))
                    nil
                    entries)]
          (if best
            (do (swap! cache update :hits inc)
                (mulog/debug ::cache-hit :cached-query (:cached-query best) :similarity (:similarity best))
                best)
            (do (swap! cache update :misses inc)
                nil)))
        (catch Exception e
          (mulog/warn ::cache-get-failed :message (.getMessage e))
          nil)))))

(defn with-cache
  "Execute body-fn with caching. If a cache hit is found, return it.
   Otherwise execute body-fn and cache the result.

   Usage:
     (with-cache cache query {:model \"haiku\"}
       (fn [] (llm-completion ...)))"
  [cache query attrs body-fn]
  (or (when-let [hit (cache-get cache query)]
        (:response hit))
      (let [result (body-fn)]
        (when result
          (cache-put cache query result attrs))
        result)))

(defn cache-stats
  "Return cache statistics."
  [cache]
  (let [{:keys [entries hits misses]} @cache]
    {:entries (count entries)
     :hits hits
     :misses misses
     :hit-rate (if (pos? (+ hits misses))
                 (double (/ hits (+ hits misses)))
                 0.0)}))

(defn cache-clear
  "Clear all cache entries."
  [cache]
  (swap! cache assoc :entries [] :hits 0 :misses 0))
