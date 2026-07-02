;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.episodic
  "Episodic memory implementation using SQLite FTS5.

  Stores time-ordered records of agent interactions:
  - Conversations (user/assistant dialogue turns)
  - Actions (tool invocations and parameters)
  - Observations (results from tools and environment)
  - Thoughts (agent's internal reasoning)"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.memory.core.fts :as fts]))

;; =====================================================
;; Episode Record Creation
;; =====================================================

(defn- normalize-episode
  "Strip table-namespace prefix from episode keys.
  Converts :episodes/content -> :content, :episodes/id -> :id, etc.
  Leaves non-namespaced keys unchanged."
  [record]
  (when record
    (persistent!
     (reduce-kv (fn [m k v]
                  (let [k' (if (and (keyword? k) (= "episodes" (namespace k)))
                             (keyword (name k))
                             k)]
                    (assoc! m k' v)))
                (transient {})
                record))))

(defn create-episode
  "Create an episode record for storage."
  [& {:keys [session-id user-id episode-type role content metadata]}]
  {:session_id session-id
   :user_id user-id
   :episode_type (if (keyword? episode-type) (name episode-type) episode-type)
   :role role
   :content content
   :metadata (when metadata (json/write-str metadata))})

;; =====================================================
;; Core Operations
;; =====================================================

(defn- upsert-episode-by-entry-id!
  "INSERT the episode, or UPDATE it in place on a `(user_id, entry_id)`
  conflict — so a re-captured episode sharing a content-addressable
  `entry_id` (e.g. a re-asked question) refreshes instead of duplicating or
  failing the unique index. Preserves keep/archived/tombstoned flags on
  update (a re-ask shouldn't un-pin or resurrect a forgotten episode)."
  [ds {:keys [session_id user_id episode_type role content tags sources
              entry_id keep_flag archived_flag tombstoned_flag metadata]
       :as record}]
  (let [r (jdbc/execute-one!
           ds ["INSERT INTO episodes
                  (session_id, user_id, episode_type, role, content, tags, sources,
                   entry_id, keep_flag, archived_flag, tombstoned_flag, metadata)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(user_id, entry_id) WHERE entry_id IS NOT NULL
                DO UPDATE SET session_id   = excluded.session_id,
                              episode_type = excluded.episode_type,
                              role         = excluded.role,
                              content      = excluded.content,
                              tags         = excluded.tags,
                              sources      = excluded.sources,
                              metadata     = excluded.metadata,
                              timestamp    = CURRENT_TIMESTAMP
                RETURNING id"
               session_id user_id episode_type role content tags sources
               entry_id (or keep_flag 0) (or archived_flag 0) (or tombstoned_flag 0) metadata])]
    (assoc record :id (or (:id r) (:episodes/id r)))))

(defn append-episode!
  "Append a new episode to the database. When the record carries an
  `entry_id`, the write is an upsert on `(user_id, entry_id)` (dedup of
  re-captured episodes); otherwise a plain insert.
  Returns: The created/updated episode with id and timestamp"
  [ds episode]
  (let [record (if (contains? episode :session_id)
                 episode
                 (create-episode
                  :session-id (:session-id episode)
                  :user-id (:user-id episode)
                  :episode-type (:episode-type episode)
                  :role (:role episode)
                  :content (:content episode)
                  :metadata (:metadata episode)))]
    (try
      (if (:entry_id record)
        (upsert-episode-by-entry-id! ds record)
        (let [result (sql/insert! ds :episodes record)
              id (or (:episodes/id result) (:id result) (get result (keyword "last_insert_rowid()")))]
          (assoc record :id id)))
      (catch Exception e
        (mulog/error ::episode-append-failed :error (ex-message e))
        nil))))

(defn get-episode-by-id
  "Get a specific episode by ID."
  [ds id]
  (normalize-episode
   (jdbc/execute-one! ds
                      ["SELECT * FROM episodes WHERE id = ?" id])))

;; =====================================================
;; FTS5 Search Operations
;; =====================================================

(defn- build-fts-query
  "Build a parameterized FTS5 search query.
  Returns: [sql-string & params]"
  [query & {:keys [session-id exclude-session-id limit episode-types time-after time-before]
            :or {limit 20}}]
  (let [base-sql "SELECT e.*, bm25(episodes_fts) as rank
                  FROM episodes e
                  JOIN episodes_fts f ON e.id = f.rowid
                  WHERE episodes_fts MATCH ?"
        conditions []
        params [query]

        [conditions params]
        (if session-id
          [(conj conditions "e.session_id = ?")
           (conj params session-id)]
          [conditions params])

        ;; Cross-session recall: EXCLUDE the current session so recall is
        ;; additive (prior-session knowledge) rather than redundant with the
        ;; agent's own previous-turns. NULL-session rows are kept.
        [conditions params]
        (if exclude-session-id
          [(conj conditions "(e.session_id IS NULL OR e.session_id != ?)")
           (conj params exclude-session-id)]
          [conditions params])

        [conditions params]
        (if (seq episode-types)
          [(conj conditions
                 (str "e.episode_type IN ("
                      (str/join "," (repeat (count episode-types) "?"))
                      ")"))
           (into params (map #(if (keyword? %) (name %) %) episode-types))]
          [conditions params])

        [conditions params]
        (if time-after
          [(conj conditions "e.timestamp > ?")
           (conj params time-after)]
          [conditions params])

        [conditions params]
        (if time-before
          [(conj conditions "e.timestamp < ?")
           (conj params time-before)]
          [conditions params])

        sql (str base-sql
                 (when (seq conditions)
                   (str " AND " (str/join " AND " conditions)))
                 " ORDER BY rank LIMIT ?")]

    (into [sql] (conj params limit))))

(defn search-fts
  "Search episodic memory using FTS5 with BM25 ranking.

  Options:
    :session-id    - Restrict to a session
    :exclude-session-id - Exclude a session (cross-session recall). NULL-session
                          rows are kept. Mutually distinct from :session-id.
    :limit         - Maximum results (default 20)
    :episode-types - Vector of types to filter
    :time-after    - Only episodes after this timestamp
    :time-before   - Only episodes before this timestamp
    :match         - Multi-word query mode: :or (default), :and, :phrase
                     (see fts/normalize-fts-query for semantics)

  Returns: Vector of episodes sorted by relevance"
  [ds query & {:keys [session-id exclude-session-id limit episode-types time-after time-before match]
               :or {limit 20 match :or}}]
  (if-let [normalized-query (fts/normalize-fts-query query match)]
    (try
      (let [sql-and-params (build-fts-query normalized-query
                                            :session-id session-id
                                            :exclude-session-id exclude-session-id
                                            :limit limit
                                            :episode-types episode-types
                                            :time-after time-after
                                            :time-before time-before)]
        (mapv normalize-episode (jdbc/execute! ds sql-and-params)))
      (catch Exception e
        (mulog/error ::episodic-fts-search-failed :error (ex-message e))
        []))
    []))

;; =====================================================
;; Temporal Queries
;; =====================================================

(defn get-recent-episodes
  "Get most recent episodes for a session.
  Returns episodes ordered by timestamp DESC (newest first)."
  [ds session-id limit]
  (mapv normalize-episode
        (jdbc/execute! ds
                       ["SELECT * FROM episodes
      WHERE session_id = ?
      ORDER BY timestamp DESC
      LIMIT ?"
                        session-id limit])))

(defn get-total-count
  "Get total count of episodes for a session."
  [ds session-id]
  (let [result (jdbc/execute-one! ds
                                  ["SELECT COUNT(*) as cnt FROM episodes WHERE session_id = ?"
                                   session-id])]
    (:cnt result 0)))

(defn get-conversation-history
  "Reconstruct conversation history for a session.
  Returns vector of maps in chronological order,
  only including conversation-type episodes."
  [ds session-id]
  (let [episodes (jdbc/execute! ds
                                ["SELECT role, content FROM episodes
                     WHERE session_id = ?
                     AND episode_type = 'conversation'
                     ORDER BY timestamp ASC"
                                 session-id])]
    (mapv #(normalize-episode (select-keys % [:episodes/role :episodes/content])) episodes)))

(defn get-all-session-episodes
  "Get all episodes for a session in chronological order."
  [ds session-id]
  (mapv normalize-episode
        (jdbc/execute! ds
                       ["SELECT * FROM episodes
      WHERE session_id = ?
      ORDER BY timestamp ASC"
                        session-id])))

;; =====================================================
;; Episode Statistics & Maintenance
;; =====================================================

(defn count-episodes
  "Count episodes, optionally filtered by session or user."
  [ds & {:keys [session-id user-id]}]
  (let [result (cond
                 session-id
                 (jdbc/execute-one! ds
                                    ["SELECT COUNT(*) as cnt FROM episodes WHERE session_id = ?"
                                     session-id])

                 user-id
                 (jdbc/execute-one! ds
                                    ["SELECT COUNT(*) as cnt FROM episodes WHERE user_id = ?"
                                     user-id])

                 :else
                 (jdbc/execute-one! ds
                                    ["SELECT COUNT(*) as cnt FROM episodes"]))]
    (:cnt result 0)))

(defn delete-old-episodes!
  "Delete episodes older than specified days.
  Returns: Number of deleted episodes"
  [ds days-to-keep]
  (try
    (let [result (jdbc/execute-one! ds
                                    ["DELETE FROM episodes
                     WHERE timestamp < datetime('now', '-' || ? || ' days')"
                                     days-to-keep])]
      (get result :next.jdbc/update-count 0))
    (catch Exception e
      (mulog/error ::old-episodes-delete-failed :error (ex-message e))
      0)))

(defn delete-session-episodes!
  "Delete all episodes for a specific session."
  [ds session-id]
  (try
    (let [result (jdbc/execute-one! ds
                                    ["DELETE FROM episodes WHERE session_id = ?"
                                     session-id])]
      (get result :next.jdbc/update-count 0))
    (catch Exception e
      (mulog/error ::session-episodes-delete-failed :error (ex-message e))
      0)))

;; The EpisodicMemoryImpl defrecord was removed in the unified-store
;; refactor — episodic data is now accessed via the IMemoryStore
;; protocol on UnifiedStore at layer :l2. The functions above remain
;; as the internal storage backend.
