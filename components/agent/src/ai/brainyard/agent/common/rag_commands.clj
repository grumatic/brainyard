;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.rag-commands
  "RAG (Retrieval-Augmented Generation) commands for agents.
   Ported from cloudcast.backend.agent.common.rag-agent.

   Provides vector search capabilities with pluggable backends.
   Currently supports memory-based vector store for development;
   can be extended with Milvus, Qdrant, Chroma, etc.

   Uses embeddings from clj-llm component for vectorization."
  (:require [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; =====================================================
;; In-Memory Vector Store
;; =====================================================

(defonce !vector-store
  (atom {:documents []  ;; [{:id :text :embedding :metadata}]
         :embed-fn nil}))

(defn configure-rag
  "Configure the RAG system with an embedding function.
   embed-fn: (fn [text] -> float-vector)"
  [embed-fn]
  (swap! !vector-store assoc :embed-fn embed-fn))

(defn- cosine-sim [a b]
  (let [dot (reduce + (map * a b))
        mag-a (Math/sqrt (reduce + (map #(* % %) a)))
        mag-b (Math/sqrt (reduce + (map #(* % %) b)))]
    (if (or (zero? mag-a) (zero? mag-b)) 0.0
        (/ dot (* mag-a mag-b)))))

;; =====================================================
;; Document Management
;; =====================================================

(defcommand rag-command$add-document
  "Add a document to the RAG knowledge base."
  (fn [& {:keys [text metadata id]}]
    (let [{:keys [embed-fn]} @!vector-store]
      (if-not embed-fn
        {:error "RAG not configured — call configure-rag with an embedding function"}
        (try
          (let [embedding (embed-fn text)
                doc {:id (or id (str (java.util.UUID/randomUUID)))
                     :text text
                     :embedding embedding
                     :metadata (or metadata {})
                     :timestamp (System/currentTimeMillis)}]
            (swap! !vector-store update :documents conj doc)
            {:success true :id (:id doc) :text-length (count text)})
          (catch Exception e
            {:error (str "Failed to add document: " (.getMessage e))})))))
  :input-schema  [:map
                  [:text     [:string {:desc "Document text to index"}]]
                  [:metadata [:any {:desc "Optional metadata map"}]]]
  :output-schema [:map
                  [:success [:boolean]]
                  [:id      [:string]]])

(defcommand rag-command$search
  "Search the RAG knowledge base by semantic similarity."
  (fn [& {:keys [query top-k threshold]}]
    (let [{:keys [embed-fn documents]} @!vector-store
          top-k (or top-k 5)
          threshold (or threshold 0.7)]
      (if-not embed-fn
        {:error "RAG not configured — call configure-rag with an embedding function"}
        (if (empty? documents)
          {:results [] :count 0 :message "Knowledge base is empty"}
          (try
            (let [query-emb (embed-fn query)
                  scored (->> documents
                              (map (fn [doc]
                                     (assoc doc :score (cosine-sim query-emb (:embedding doc)))))
                              (filter #(>= (:score %) threshold))
                              (sort-by :score >)
                              (take top-k)
                              (mapv (fn [doc]
                                      {:id (:id doc)
                                       :text (:text doc)
                                       :score (double (:score doc))
                                       :metadata (:metadata doc)})))]
              {:results scored
               :count (count scored)
               :query query})
            (catch Exception e
              {:error (str "Search failed: " (.getMessage e))}))))))
  :input-schema  [:map
                  [:query     [:string {:desc "Search query"}]]
                  [:top-k     [:int {:desc "Max results (default 5)"}]]
                  [:threshold [:double {:desc "Min similarity 0-1 (default 0.7)"}]]]
  :output-schema [:map
                  [:results [:vector [:map
                                      [:id :string]
                                      [:text :string]
                                      [:score :double]
                                      [:metadata {:optional true} [:maybe [:map-of :any :any]]]]]]
                  [:count [:int]]])

(defcommand rag-command$clear
  "Clear all documents from the RAG knowledge base."
  (fn [& _]
    (let [count-before (count (:documents @!vector-store))]
      (swap! !vector-store assoc :documents [])
      {:success true :cleared count-before}))
  :input-schema  [:map]
  :output-schema [:map
                  [:success [:boolean]]
                  [:cleared [:int]]])

(defcommand rag-command$stats
  "Get RAG knowledge base statistics."
  (fn [& _]
    (let [{:keys [documents embed-fn]} @!vector-store]
      {:configured (boolean embed-fn)
       :document-count (count documents)
       :total-chars (reduce + 0 (map #(count (:text %)) documents))}))
  :input-schema  [:map]
  :output-schema [:map
                  [:configured    [:boolean]]
                  [:document-count [:int]]])
