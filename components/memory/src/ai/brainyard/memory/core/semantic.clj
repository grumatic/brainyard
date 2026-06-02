;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.semantic
  "Semantic memory implementation using SQLite FTS5.

  Stores long-term knowledge as persistent facts:
  - Summaries (compressed knowledge from conversations)
  - Facts (explicit factual statements)
  - Preferences (user preferences and patterns)
  - Entities (named entities and properties)
  - Relationships (links between entities)"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.memory.core.fts :as fts]))

;; =====================================================
;; Fact Record Creation
;; =====================================================

(defn- normalize-fact
  "Strip table-namespace prefix from semantic fact keys.
  Converts :semantic_facts/content -> :content, :semantic_facts/id -> :id, etc.
  Leaves non-namespaced keys unchanged."
  [record]
  (when record
    (persistent!
     (reduce-kv (fn [m k v]
                  (let [k' (if (and (keyword? k) (= "semantic_facts" (namespace k)))
                             (keyword (name k))
                             k)]
                    (assoc! m k' v)))
                (transient {})
                record))))

(defn create-fact
  "Create a semantic fact record for storage."
  [& {:keys [user-id fact-type content source confidence metadata]
      :or {confidence 1.0}}]
  {:user_id user-id
   :fact_type (if (keyword? fact-type) (name fact-type) fact-type)
   :content content
   :source source
   :confidence (float confidence)
   :metadata (when metadata (json/write-str metadata))})

;; =====================================================
;; Core Operations
;; =====================================================

(defn store-fact!
  "Store a new semantic fact.
  Returns: The created fact with id"
  [ds fact]
  (let [record (if (contains? fact :user_id)
                 fact
                 (create-fact
                  :user-id (:user-id fact)
                  :fact-type (:fact-type fact)
                  :content (:content fact)
                  :source (:source fact)
                  :confidence (:confidence fact 1.0)
                  :metadata (:metadata fact)))]
    (try
      (let [result (sql/insert! ds :semantic_facts record)
            id (or (:semantic_facts/id result) (:id result) (get result (keyword "last_insert_rowid()")))]
        (assoc record :id id))
      (catch Exception e
        (mulog/error ::fact-store-failed :error (ex-message e))
        nil))))

(defn get-fact-by-id
  "Get a specific fact by ID."
  [ds id]
  (normalize-fact
   (jdbc/execute-one! ds
                      ["SELECT * FROM semantic_facts WHERE id = ?" id])))

(defn update-fact!
  "Update an existing fact.
  Updates map can include: :content :confidence :metadata :source"
  [ds fact-id updates]
  (try
    (let [update-map (cond-> {:updated_at (str (java.time.Instant/now))}
                       (:content updates) (assoc :content (:content updates))
                       (:confidence updates) (assoc :confidence (float (:confidence updates)))
                       (:source updates) (assoc :source (:source updates))
                       (:metadata updates) (assoc :metadata (json/write-str (:metadata updates))))]
      (sql/update! ds :semantic_facts update-map {:id fact-id})
      (get-fact-by-id ds fact-id))
    (catch Exception e
      (mulog/error ::fact-update-failed :fact-id fact-id :error (ex-message e))
      nil)))

(defn delete-fact!
  "Delete a fact by ID."
  [ds fact-id]
  (try
    (let [result (jdbc/execute-one! ds
                                    ["DELETE FROM semantic_facts WHERE id = ?" fact-id])]
      (get result :next.jdbc/update-count 0))
    (catch Exception e
      (mulog/error ::fact-delete-failed :fact-id fact-id :error (ex-message e))
      0)))

;; =====================================================
;; FTS5 Search Operations
;; =====================================================

(defn search-fts
  "Search semantic facts using FTS5 with BM25 ranking.

  Options:
    :fact-types     - Vector of fact types to filter
    :min-confidence - Minimum confidence threshold (default 0.0)
    :user-id        - Filter by user
    :match          - Multi-word query mode: :or (default), :and, :phrase
                      (see fts/normalize-fts-query for semantics)

  Returns: Vector of facts sorted by relevance"
  [ds query k & {:keys [fact-types min-confidence user-id match]
                 :or {min-confidence 0.0 match :or}}]
  (if-let [normalized-query (fts/normalize-fts-query query match)]
    (try
      (let [base-sql "SELECT f.*, bm25(semantic_fts) as rank
                      FROM semantic_facts f
                      JOIN semantic_fts fts ON f.id = fts.rowid
                      WHERE semantic_fts MATCH ?
                      AND f.confidence >= ?"
            conditions []
            params [normalized-query min-confidence]

            [conditions params]
            (if user-id
              [(conj conditions "f.user_id = ?")
               (conj params user-id)]
              [conditions params])

            [conditions params]
            (if (seq fact-types)
              [(conj conditions
                     (str "f.fact_type IN ("
                          (str/join "," (repeat (count fact-types) "?"))
                          ")"))
               (into params (map #(if (keyword? %) (name %) %) fact-types))]
              [conditions params])

            sql (str base-sql
                     (when (seq conditions)
                       (str " AND " (str/join " AND " conditions)))
                     " ORDER BY f.confidence DESC, rank LIMIT ?")]

        (mapv normalize-fact (jdbc/execute! ds (into [sql] (conj params k)))))
      (catch Exception e
        (mulog/error ::semantic-fts-search-failed :error (ex-message e))
        []))
    []))

;; =====================================================
;; Access Tracking
;; =====================================================

(defn record-access!
  "Record that a fact was accessed."
  [ds fact-id]
  (try
    (jdbc/execute! ds
                   ["UPDATE semantic_facts
        SET access_count = access_count + 1,
            last_accessed = CURRENT_TIMESTAMP
        WHERE id = ?"
                    fact-id])
    nil
    (catch Exception e
      (mulog/warn ::fact-access-record-failed :fact-id fact-id :error (ex-message e))
      nil)))

;; =====================================================
;; Confidence Decay
;; =====================================================

(defn decay-facts!
  "Apply confidence decay to all facts.

  Decay formula: new_confidence = confidence * (1 - decay_rate)

  Returns: {:decayed n :deleted n}"
  [ds & {:keys [decay-rate min-confidence]
         :or {decay-rate 0.01 min-confidence 0.1}}]
  (try
    (let [decay-result (jdbc/execute-one! ds
                                          ["UPDATE semantic_facts
                           SET confidence = confidence * (1 - ?),
                               updated_at = CURRENT_TIMESTAMP
                           WHERE confidence > ?"
                                           decay-rate min-confidence])
          decayed-count (get decay-result :next.jdbc/update-count 0)

          delete-result (jdbc/execute-one! ds
                                           ["DELETE FROM semantic_facts WHERE confidence < ?"
                                            min-confidence])
          deleted-count (get delete-result :next.jdbc/update-count 0)]

      {:decayed decayed-count :deleted deleted-count})
    (catch Exception e
      (mulog/error ::fact-decay-failed :error (ex-message e))
      {:decayed 0 :deleted 0})))

;; =====================================================
;; Fact Statistics
;; =====================================================

(defn count-facts
  "Count facts, optionally filtered by user or type."
  [ds & {:keys [user-id fact-type]}]
  (let [result (cond
                 (and user-id fact-type)
                 (jdbc/execute-one! ds
                                    ["SELECT COUNT(*) as cnt FROM semantic_facts
                     WHERE user_id = ? AND fact_type = ?"
                                     user-id (if (keyword? fact-type) (name fact-type) fact-type)])

                 user-id
                 (jdbc/execute-one! ds
                                    ["SELECT COUNT(*) as cnt FROM semantic_facts WHERE user_id = ?"
                                     user-id])

                 fact-type
                 (jdbc/execute-one! ds
                                    ["SELECT COUNT(*) as cnt FROM semantic_facts WHERE fact_type = ?"
                                     (if (keyword? fact-type) (name fact-type) fact-type)])

                 :else
                 (jdbc/execute-one! ds
                                    ["SELECT COUNT(*) as cnt FROM semantic_facts"]))]
    (:cnt result 0)))

;; =====================================================
;; Knowledge Consolidation Helpers
;; =====================================================

(defn store-summary!
  "Store a conversation summary as a semantic fact."
  [ds user-id session-id summary]
  (store-fact! ds
               (create-fact
                :user-id user-id
                :fact-type :summary
                :content summary
                :source (str "session:" session-id)
                :confidence 0.9
                :metadata {:session-id session-id
                           :created-from "episodic-consolidation"})))

(defn store-user-preference!
  "Store a user preference as a semantic fact."
  [ds user-id preference-content source]
  (store-fact! ds
               (create-fact
                :user-id user-id
                :fact-type :preference
                :content preference-content
                :source source
                :confidence 0.95)))

(defn store-learned-fact!
  "Store a learned fact from conversation."
  [ds user-id fact-content source]
  (store-fact! ds
               (create-fact
                :user-id user-id
                :fact-type :fact
                :content fact-content
                :source source
                :confidence 0.8)))

;; The SemanticMemoryImpl defrecord was removed in the unified-store
;; refactor — semantic data is now accessed via the IMemoryStore
;; protocol on UnifiedStore at layer :l3. The functions above remain
;; as the internal storage backend.
