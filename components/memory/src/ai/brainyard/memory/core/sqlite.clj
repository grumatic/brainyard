;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.sqlite
  "SQLite infrastructure for agent memory system.

  Provides connection management, schema initialization, and lifecycle
  for the layered memory architecture using SQLite FTS5 for full-text search."
  (:require [next.jdbc :as jdbc]
            [clojure.java.io :as io]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; =====================================================
;; Database Path Management
;; =====================================================

(defn expand-path
  "Expand a leading `~` (alone or as `~/...`) to the user's home directory.
  Other paths are returned unchanged. JDBC/SQLite do not expand `~`, so
  callers passing user-supplied paths must run them through this first
  to avoid creating a literal `~` directory under the JVM's CWD."
  [path]
  (cond
    (not (string? path)) path
    (= path "~")         (System/getProperty "user.home")
    (str/starts-with? path "~/")
    (str (System/getProperty "user.home") (subs path 1))
    :else path))

(defn db-path
  "Generate database file path for a user.
  Each user gets their own SQLite database file for isolation.
  A leading `~` in `base-path` is expanded to the user's home directory."
  [base-path user-id]
  (str (expand-path base-path) "/" user-id ".db"))

(defn- ensure-parent-dir!
  "Ensure the parent directory exists for a database file."
  [path]
  (when-not (str/starts-with? path ":")
    (let [parent (.getParentFile (io/file path))]
      (when parent
        (.mkdirs parent)))))

;; =====================================================
;; Connection Management
;; =====================================================

(defn- apply-pragmas!
  "Apply performance pragmas to a connection."
  [conn]
  (doseq [pragma ["journal_mode = WAL"
                  "synchronous = NORMAL"
                  "cache_size = -64000"
                  "foreign_keys = ON"
                  "temp_store = MEMORY"]]
    (try
      (jdbc/execute! conn [(str "PRAGMA " pragma)])
      (catch Exception e
        (mulog/warn ::pragma-set-failed :pragma pragma :error (ex-message e))))))

(defn create-datasource
  "Create a SQLite datasource with optimized settings.

  Uses WAL mode for concurrent reads and applies performance pragmas.

  For in-memory databases (db-path contains \":memory:\"), returns a
  persistent java.sql.Connection. Each call creates an isolated in-memory
  database. The caller should close the connection when done.

  For file-based databases, returns a standard DataSource. A leading
  `~` is expanded to the user's home directory.

  Both Connection and DataSource work as next.jdbc connectables."
  [db-path]
  (let [db-path (expand-path db-path)]
    (if (str/includes? db-path ":memory:")
      ;; In-memory: return a single connection as the connectable.
      ;; Each connection to :memory: gets its own private database.
      ;; We hold this connection so the DB persists across operations.
      (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname ":memory:"})
            conn (jdbc/get-connection ds)]
        (apply-pragmas! conn)
        conn)
      ;; File-based: normal datasource
      (do
        (ensure-parent-dir! db-path)
        (let [ds (jdbc/get-datasource {:dbtype "sqlite"
                                       :dbname db-path})]
          (with-open [conn (jdbc/get-connection ds)]
            (apply-pragmas! conn))
          ds)))))

;; =====================================================
;; Schema Definitions
;; =====================================================

(def ^:private episodic-schema
  "Schema for episodic memory (conversations, actions, observations).

  Unified-memory columns:
    tags             — JSON array of tag strings (e.g. [\"tool:bash\" \"topic:deploy\"])
    sources          — JSON array of provenance maps ({:type :id})
    entry_id         — stable cross-layer id; nullable; unique per (user_id, entry_id)
    keep_flag        — pinned, retained beyond TTL sweep
    archived_flag    — excluded from default recall
    tombstoned_flag  — soft-deleted; excluded from default recall, retained for audit"
  ["CREATE TABLE IF NOT EXISTS episodes (
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     session_id TEXT NOT NULL,
     user_id TEXT NOT NULL,
     timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
     episode_type TEXT NOT NULL,
     role TEXT,
     content TEXT NOT NULL,
     metadata TEXT,
     tags TEXT,
     sources TEXT,
     entry_id TEXT,
     keep_flag INTEGER NOT NULL DEFAULT 0,
     archived_flag INTEGER NOT NULL DEFAULT 0,
     tombstoned_flag INTEGER NOT NULL DEFAULT 0
   )"

   "CREATE VIRTUAL TABLE IF NOT EXISTS episodes_fts USING fts5(
     content,
     episode_type,
     role,
     content='episodes',
     content_rowid='id',
     tokenize='porter unicode61'
   )"

   "CREATE TRIGGER IF NOT EXISTS episodes_ai AFTER INSERT ON episodes BEGIN
     INSERT INTO episodes_fts(rowid, content, episode_type, role)
     VALUES (new.id, new.content, new.episode_type, new.role);
   END"

   "CREATE TRIGGER IF NOT EXISTS episodes_ad AFTER DELETE ON episodes BEGIN
     INSERT INTO episodes_fts(episodes_fts, rowid, content, episode_type, role)
     VALUES ('delete', old.id, old.content, old.episode_type, old.role);
   END"

   "CREATE TRIGGER IF NOT EXISTS episodes_au AFTER UPDATE ON episodes BEGIN
     INSERT INTO episodes_fts(episodes_fts, rowid, content, episode_type, role)
     VALUES ('delete', old.id, old.content, old.episode_type, old.role);
     INSERT INTO episodes_fts(rowid, content, episode_type, role)
     VALUES (new.id, new.content, new.episode_type, new.role);
   END"

   "CREATE INDEX IF NOT EXISTS idx_episodes_session ON episodes(session_id, timestamp)"
   "CREATE INDEX IF NOT EXISTS idx_episodes_user ON episodes(user_id, timestamp)"
   "CREATE INDEX IF NOT EXISTS idx_episodes_type ON episodes(episode_type, timestamp)"
   "CREATE INDEX IF NOT EXISTS idx_episodes_keep ON episodes(session_id, keep_flag, archived_flag)"
   "CREATE UNIQUE INDEX IF NOT EXISTS idx_episodes_entry_id ON episodes(user_id, entry_id) WHERE entry_id IS NOT NULL"])

(def ^:private semantic-schema
  "Schema for semantic memory (facts, summaries, knowledge).

  Unified-memory columns mirror episodes (see episodic-schema docstring)."
  ["CREATE TABLE IF NOT EXISTS semantic_facts (
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     user_id TEXT NOT NULL,
     fact_type TEXT NOT NULL,
     content TEXT NOT NULL,
     source TEXT,
     confidence REAL DEFAULT 1.0,
     created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
     updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
     access_count INTEGER DEFAULT 0,
     last_accessed DATETIME,
     metadata TEXT,
     tags TEXT,
     sources TEXT,
     entry_id TEXT,
     keep_flag INTEGER NOT NULL DEFAULT 0,
     archived_flag INTEGER NOT NULL DEFAULT 0,
     tombstoned_flag INTEGER NOT NULL DEFAULT 0
   )"

   "CREATE VIRTUAL TABLE IF NOT EXISTS semantic_fts USING fts5(
     content,
     fact_type,
     content='semantic_facts',
     content_rowid='id',
     tokenize='porter unicode61'
   )"

   "CREATE TRIGGER IF NOT EXISTS semantic_ai AFTER INSERT ON semantic_facts BEGIN
     INSERT INTO semantic_fts(rowid, content, fact_type)
     VALUES (new.id, new.content, new.fact_type);
   END"

   "CREATE TRIGGER IF NOT EXISTS semantic_ad AFTER DELETE ON semantic_facts BEGIN
     INSERT INTO semantic_fts(semantic_fts, rowid, content, fact_type)
     VALUES ('delete', old.id, old.content, old.fact_type);
   END"

   "CREATE TRIGGER IF NOT EXISTS semantic_au AFTER UPDATE ON semantic_facts BEGIN
     INSERT INTO semantic_fts(semantic_fts, rowid, content, fact_type)
     VALUES ('delete', old.id, old.content, old.fact_type);
     INSERT INTO semantic_fts(rowid, content, fact_type)
     VALUES (new.id, new.content, new.fact_type);
   END"

   "CREATE INDEX IF NOT EXISTS idx_semantic_user ON semantic_facts(user_id, fact_type)"
   "CREATE INDEX IF NOT EXISTS idx_semantic_confidence ON semantic_facts(confidence DESC)"
   "CREATE INDEX IF NOT EXISTS idx_semantic_keep ON semantic_facts(user_id, keep_flag, archived_flag)"
   "CREATE UNIQUE INDEX IF NOT EXISTS idx_semantic_entry_id ON semantic_facts(user_id, entry_id) WHERE entry_id IS NOT NULL"])

(def ^:private metadata-schema
  "Schema for memory system metadata"
  ["CREATE TABLE IF NOT EXISTS memory_metadata (
     key TEXT PRIMARY KEY,
     value TEXT NOT NULL,
     updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
   )"])

(def ^:private audit-schema
  "Schema for the memory_audit table.

  One row per entry that lands in a prompt. Lets
  `(memory/explain session-id agent-id turn-id)` reconstruct exactly
  what an agent 'knew' on a given turn. Retention of audit rows is
  independent of L2/L3 sweep — audit is the historical record of what
  actually informed the model.

  Columns:
    agent_id    — qualifies turn_id (per-agent monotonic counter)
    turn_id     — per-agent turn (1-based, increments on each ask to
                  this agent)
    total_turns — session-cumulative ask counter (across root + sub-
                  agents); strictly monotonic within a session, useful
                  for cross-agent ordering."
  ["CREATE TABLE IF NOT EXISTS memory_audit (
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     user_id TEXT NOT NULL,
     session_id TEXT NOT NULL,
     agent_id TEXT,
     turn_id INTEGER NOT NULL,
     total_turns INTEGER,
     entry_id TEXT NOT NULL,
     layer TEXT NOT NULL,
     byte_cost INTEGER,
     created_at DATETIME DEFAULT CURRENT_TIMESTAMP
   )"
   "CREATE INDEX IF NOT EXISTS idx_audit_session_agent_turn
      ON memory_audit(session_id, agent_id, turn_id)"
   "CREATE INDEX IF NOT EXISTS idx_audit_session_total
      ON memory_audit(session_id, total_turns)"
   "CREATE INDEX IF NOT EXISTS idx_audit_user ON memory_audit(user_id, created_at)"])

;; =====================================================
;; Schema Initialization
;; =====================================================

(defn- execute-ddl!
  "Execute a DDL statement, logging any errors."
  [ds stmt]
  (try
    (jdbc/execute! ds [stmt])
    true
    (catch Exception e
      (when-not (str/includes? (ex-message e) "already exists")
        (mulog/error ::ddl-execution-failed :statement stmt :error (ex-message e)))
      false)))

(defn init-schema!
  "Initialize all memory tables and FTS5 virtual tables.
  Safe to call multiple times — uses IF NOT EXISTS."
  [ds]
  (mulog/info ::schema-initializing)
  (let [all-schemas (concat metadata-schema
                            episodic-schema
                            semantic-schema
                            audit-schema)]
    (doseq [stmt all-schemas]
      (execute-ddl! ds stmt))

    ;; Store schema version. 2.0.0 introduces unified-memory columns
    ;; (tags, sources, entry_id, keep_flag, archived_flag, tombstoned_flag)
    ;; on episodes and semantic_facts. Unpublished prior to this — no migration.
    (try
      (jdbc/execute! ds
                     ["INSERT OR REPLACE INTO memory_metadata (key, value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)"
                      "schema_version" "2.0.0"])
      (catch Exception e
        (mulog/warn ::schema-version-store-failed :error (ex-message e)))))

  (mulog/info ::schema-initialized))

(defn get-schema-version
  "Get the current schema version."
  [ds]
  (try
    (-> (jdbc/execute-one! ds
                           ["SELECT value FROM memory_metadata WHERE key = ?" "schema_version"])
        :memory_metadata/value)
    (catch Exception _
      nil)))

;; =====================================================
;; Utility Functions
;; =====================================================

(defn table-exists?
  "Check if a table exists in the database."
  [ds table-name]
  (let [result (jdbc/execute-one! ds
                                  ["SELECT name FROM sqlite_master WHERE type='table' AND name=?"
                                   table-name])]
    (some? result)))

(defn count-rows
  "Count rows in a table, optionally with a WHERE clause."
  [ds table-name & {:keys [where-clause params]
                    :or {where-clause nil params []}}]
  (let [sql (str "SELECT COUNT(*) as cnt FROM " table-name
                 (when where-clause (str " WHERE " where-clause)))
        result (jdbc/execute-one! ds (into [sql] params))]
    (:cnt result 0)))

(defn vacuum!
  "Optimize the database by running VACUUM."
  [ds]
  (jdbc/execute! ds ["VACUUM"]))

(defn optimize-fts!
  "Optimize FTS5 indexes for better search performance."
  [ds]
  (doseq [fts-table ["episodes_fts" "semantic_fts"]]
    (try
      (jdbc/execute! ds [(str "INSERT INTO " fts-table "(" fts-table ") VALUES ('optimize')")])
      (catch Exception e
        (mulog/warn ::fts-optimize-failed :fts-table fts-table :error (ex-message e))))))

(defn get-db-stats
  "Get statistics about the memory database."
  [ds]
  {:episodes (count-rows ds "episodes")
   :semantic-facts (count-rows ds "semantic_facts")
   :schema-version (get-schema-version ds)})

;; =====================================================
;; Memory Manager Cache (Per-User)
;; =====================================================

(defonce ^:private !datasources (atom {}))

(defn get-or-create-datasource
  "Get or create a datasource for a user, with caching."
  [base-path user-id]
  (let [cache-key (str base-path "/" user-id)]
    (if-let [ds (get @!datasources cache-key)]
      ds
      (let [path (db-path base-path user-id)
            ds (create-datasource path)]
        (init-schema! ds)
        (swap! !datasources assoc cache-key ds)
        ds))))

(defn close-datasource!
  "Close and remove a cached datasource."
  [base-path user-id]
  (let [cache-key (str base-path "/" user-id)]
    (swap! !datasources dissoc cache-key)))

(defn close-all-datasources!
  "Close all cached datasources."
  []
  (reset! !datasources {}))
