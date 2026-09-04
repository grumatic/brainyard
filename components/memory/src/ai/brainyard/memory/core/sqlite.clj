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
;; sqlite-vec extension (CR-MEM-21)
;; =====================================================
;;
;; The `graph_vec` vector index needs the `sqlite-vec` C extension (`vec0`)
;; loaded on every connection that touches it. The binary is fetched per
;; platform by `bb sqlite-vec:fetch` into resources/sqlite-vec/ (gitignored,
;; bundled into the native image). Resolution is best-effort and memoized:
;; when no binary is found, vector search is simply unavailable and recall
;; falls back to FTS — nothing throws.

(defn- vec-platform
  "host platform tag, e.g. \"macos-aarch64\", or nil if unsupported."
  []
  (let [os   (str/lower-case (System/getProperty "os.name"))
        arch (str/lower-case (System/getProperty "os.arch"))
        os'  (cond (str/includes? os "mac")   "macos"
                   (str/includes? os "linux") "linux")
        arc' (cond (#{"aarch64" "arm64"} arch)  "aarch64"
                   (#{"x86_64" "amd64"} arch)    "x86_64")]
    (when (and os' arc') (str os' "-" arc'))))

(defn- vec-file-ext []
  (if (str/includes? (str/lower-case (System/getProperty "os.name")) "mac") "dylib" "so"))

(defn- strip-lib-ext
  "Drop a trailing .dylib/.so/.dll — sqlite3_load_extension appends the
  platform default, so the path must be passed without an extension."
  [p]
  (str/replace p (re-pattern "\\.(dylib|so|dll)$") ""))

(defonce ^:private !vec-ext-path (atom ::unresolved))

(defn- extract-bundled-vec
  "Copy the bundled sqlite-vec resource (if present on the classpath) to a
  temp file and return its base path (no extension); else nil."
  []
  (when-let [plat (vec-platform)]
    (when-let [url (io/resource (str "sqlite-vec/vec0-" plat "." (vec-file-ext)))]
      (let [tmp (java.io.File/createTempFile "vec0-" (str "." (vec-file-ext)))]
        (.deleteOnExit tmp)
        (with-open [in (io/input-stream url)]
          (io/copy in tmp))
        (strip-lib-ext (.getAbsolutePath tmp))))))

(defn resolve-vec-extension
  "Resolve the sqlite-vec extension base path (no file extension), or nil
  when vector search is unavailable. Order: BY_SQLITE_VEC_PATH env >
  bundled classpath resource > nil. Memoized; call `reset-vec-extension!`
  in tests to re-resolve."
  []
  (let [v @!vec-ext-path]
    (if (not= v ::unresolved)
      v
      (let [resolved (or (when-let [e (System/getenv "BY_SQLITE_VEC_PATH")]
                           (when-not (str/blank? e) (strip-lib-ext e)))
                         (extract-bundled-vec))]
        (when resolved (mulog/info ::sqlite-vec-resolved :path resolved))
        (reset! !vec-ext-path resolved)
        resolved))))

(defn reset-vec-extension!
  "Clear the memoized sqlite-vec path (tests)."
  []
  (reset! !vec-ext-path ::unresolved))

(defn graph-embed-dims
  "Embedding dimensionality for the `graph_vec` table. Fixed at table
  creation, so the configured embedding model must match. Env
  BY_GRAPH_EMBED_DIMS overrides the 768 default."
  []
  (or (when-let [v (System/getenv "BY_GRAPH_EMBED_DIMS")]
        (try (Integer/parseInt (str/trim v)) (catch Exception _ nil)))
      768))

(defn- load-vec!
  "Load the sqlite-vec extension on a connection. Non-fatal: a failure
  leaves the connection usable for everything except `graph_vec`. Uses
  next.jdbc (not raw `.createStatement`) so it stays reflection-free under
  GraalVM native-image — the path is our own trusted resource."
  [conn vec-base]
  (try
    (jdbc/execute! conn [(str "SELECT load_extension('" vec-base "')")])
    (catch Exception e
      (mulog/warn ::sqlite-vec-load-failed :path vec-base :error (ex-message e)))))

;; =====================================================
;; Connection Management
;; =====================================================

(defn- apply-pragmas!
  "Apply performance pragmas to a connection."
  [conn]
  (doseq [pragma ["journal_mode = WAL"
                  "synchronous = NORMAL"
                  ;; WAL permits a separate OS process (the detached session-end
                  ;; consolidation child, `by memory reduce`) to write the same
                  ;; db file while this process still writes L2. Without a busy
                  ;; timeout the loser of a write race gets SQLITE_BUSY *immediately*
                  ;; instead of waiting; 30s covers the brief interactive write
                  ;; bursts. Single-process use is unaffected.
                  "busy_timeout = 30000"
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
  (let [db-path  (expand-path db-path)
        vec-base (resolve-vec-extension)]
    (if (str/includes? db-path ":memory:")
      ;; In-memory: return a single connection as the connectable.
      ;; Each connection to :memory: gets its own private database.
      ;; We hold this connection so the DB persists across operations.
      (let [conn (if vec-base
                   ;; enableLoadExtension is a connect-time property. Use a
                   ;; PRIVATE :memory: db (drop any `?cache=shared` in db-path)
                   ;; so each in-memory store stays isolated — matching the
                   ;; non-vec branch, which hardcodes `:dbname ":memory:"`.
                   (let [cfg (doto (org.sqlite.SQLiteConfig.) (.enableLoadExtension true))]
                     (java.sql.DriverManager/getConnection "jdbc:sqlite::memory:"
                                                           (.toProperties cfg)))
                   (jdbc/get-connection (jdbc/get-datasource {:dbtype "sqlite" :dbname ":memory:"})))]
        (apply-pragmas! conn)
        (when vec-base (load-vec! conn vec-base))
        conn)
      ;; File-based: a DataSource (new connection per op).
      (do
        (ensure-parent-dir! db-path)
        (if vec-base
          ;; Every connection must enable + load vec0, since the `graph_vec`
          ;; virtual table's module is connection-scoped. Pragmas are applied
          ;; per-connection here too (the non-vec path applies them once).
          (let [url (str "jdbc:sqlite:" db-path)
                cfg (doto (org.sqlite.SQLiteConfig.) (.enableLoadExtension true))
                ds  (reify javax.sql.DataSource
                      (getConnection [_]
                        (let [c (java.sql.DriverManager/getConnection url (.toProperties cfg))]
                          (apply-pragmas! c)
                          (load-vec! c vec-base)
                          c))
                      (getConnection [this _user _pass] (.getConnection this)))]
            ;; Eagerly open once so the DB file exists immediately (parity with
            ;; the non-vec branch, which callers rely on).
            (with-open [_ (.getConnection ^javax.sql.DataSource ds)])
            ds)
          (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path})]
            (with-open [conn (jdbc/get-connection ds)]
              (apply-pragmas! conn))
            ds))))))

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
    tombstoned_flag  — soft-deleted; excluded from default recall, retained for audit
    access_count     — times this episode was INJECTED into a prompt
    last_accessed    — when that last happened

  The two access columns mirror semantic_facts, which has carried them since
  the first schema. They count what the agent actually READ, not what recall
  merely returned — see `policy/record-access!` for why that distinction is the
  whole point of the signal."
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
     tombstoned_flag INTEGER NOT NULL DEFAULT 0,
     access_count INTEGER DEFAULT 0,
     last_accessed DATETIME
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

   ;; SCOPED TO THE INDEXED COLUMNS. An unqualified `AFTER UPDATE` fires on
   ;; every column — keep_flag, archived_flag, tombstoned_flag, access_count —
   ;; and each fire does a delete+insert against an external-content FTS index
   ;; that mirrors only these three. That is waste on every policy write, and
   ;; worse than waste on a database whose FTS index has drifted: the delete
   ;; half raises SQLITE_CORRUPT_VTAB, so an unrelated counter update fails.
   ;; `record-access!` runs on the prompt's critical path, so it must not
   ;; depend on the state of a virtual table it does not touch.
   "CREATE TRIGGER IF NOT EXISTS episodes_au
      AFTER UPDATE OF content, episode_type, role ON episodes BEGIN
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

   ;; Scoped for the same reason as episodes_au above.
   "CREATE TRIGGER IF NOT EXISTS semantic_au
      AFTER UPDATE OF content, fact_type ON semantic_facts BEGIN
     INSERT INTO semantic_fts(semantic_fts, rowid, content, fact_type)
     VALUES ('delete', old.id, old.content, old.fact_type);
     INSERT INTO semantic_fts(rowid, content, fact_type)
     VALUES (new.id, new.content, new.fact_type);
   END"

   "CREATE INDEX IF NOT EXISTS idx_semantic_user ON semantic_facts(user_id, fact_type)"
   "CREATE INDEX IF NOT EXISTS idx_semantic_confidence ON semantic_facts(confidence DESC)"
   "CREATE INDEX IF NOT EXISTS idx_semantic_keep ON semantic_facts(user_id, keep_flag, archived_flag)"
   "CREATE UNIQUE INDEX IF NOT EXISTS idx_semantic_entry_id ON semantic_facts(user_id, entry_id) WHERE entry_id IS NOT NULL"])

(def ^:private graph-schema
  "Schema for the context-graph overlay (CR-MEM-20).

  Typed entities (`graph_nodes`) and bi-temporal, typed relationships
  (`graph_edges`) connecting concepts mentioned across L2/L3 rows. Not a
  storage layer — two extra retrieval signals fused into RRF (recall_v2).

  Bi-temporal columns (Graphiti model):
    t_valid    — when the fact became true (event time)
    t_invalid  — when superseded/false (NULL = still valid)
    ingested_at — when observed
  Supersession sets `t_invalid` on the old edge; it never deletes.

  `source_entry_ids` reuses the cross-layer `entry_id`, so the graph and
  the FTS store reference the same rows — provenance stays bidirectional
  with no data duplication. The `graph_vec` vector index (sqlite-vec) is
  deferred to CR-MEM-21 and added separately."
  ["CREATE TABLE IF NOT EXISTS graph_nodes (
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     user_id TEXT NOT NULL,
     node_type TEXT NOT NULL,
     name TEXT NOT NULL,
     summary TEXT,
     aliases TEXT,
     metadata TEXT,
     community_id INTEGER,
     created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
     updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
     UNIQUE(user_id, node_type, name)
   )"

   ;; Communities (CR-MEM-24): label-propagation clusters of the entity
   ;; graph, each with an LLM-maintained rolling summary — the GraphRAG tier
   ;; that supersedes the heuristic L2→L3 time-bucket reducer (closes
   ;; CR-MEM-07). `entry_id` links to the L3 fact a community summary is
   ;; mirrored into, so recall surfaces it like any other fact.
   "CREATE TABLE IF NOT EXISTS graph_communities (
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     user_id TEXT NOT NULL,
     label TEXT,
     summary TEXT,
     node_count INTEGER DEFAULT 0,
     entry_id TEXT,
     created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
     updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
     UNIQUE(user_id, label)
   )"

   "CREATE TABLE IF NOT EXISTS graph_edges (
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     user_id TEXT NOT NULL,
     src_id INTEGER NOT NULL REFERENCES graph_nodes(id),
     dst_id INTEGER NOT NULL REFERENCES graph_nodes(id),
     relation TEXT NOT NULL,
     fact TEXT,
     confidence REAL DEFAULT 0.85,
     t_valid DATETIME DEFAULT CURRENT_TIMESTAMP,
     t_invalid DATETIME,
     ingested_at DATETIME DEFAULT CURRENT_TIMESTAMP,
     source_entry_ids TEXT
     /* No UNIQUE(..., t_valid) here — uniqueness is `idx_edges_live_unique`,
        the partial index on live rows (see below). The old table-level key was
        both too weak and too strong: t_valid defaults to now(), so it failed to
        stop duplicate live edges, while still rejecting a legitimate
        re-assertion made in the same second as that triple's own superseded
        row — which broke bi-temporal supersession. Existing databases are
        rebuilt by `rebuild-graph-edges!`. */
   )"

   "CREATE INDEX IF NOT EXISTS idx_graph_nodes_user ON graph_nodes(user_id, node_type, name)"
   "CREATE INDEX IF NOT EXISTS idx_graph_nodes_comm ON graph_nodes(user_id, community_id)"])

(def ^:private graph-edge-indexes
  "Indexes owned by `graph_edges`, kept separate from `graph-schema` because
   `rebuild-graph-edges!` DROPs the table — which drops its indexes with it —
   and so must recreate them afterwards. Referencing one vector from both places
   keeps the rebuild from silently leaving the neighbor lookups unindexed."
  ["CREATE INDEX IF NOT EXISTS idx_edges_src ON graph_edges(user_id, src_id) WHERE t_invalid IS NULL"
   "CREATE INDEX IF NOT EXISTS idx_edges_dst ON graph_edges(user_id, dst_id) WHERE t_invalid IS NULL"])

(defn- vec-schema
  "DDL for the `graph_vec` vector index (CR-MEM-21), built when sqlite-vec
  is available. `+ref_kind`/`+ref_id` are auxiliary (retrievable) columns
  carrying the back-reference to a node summary or L3 fact row. Dimensions
  are fixed at creation (see `graph-embed-dims`)."
  [dims]
  [(str "CREATE VIRTUAL TABLE IF NOT EXISTS graph_vec USING vec0(
           embedding float[" dims "],
           +ref_kind text,
           +ref_id integer
         )")])

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
      (let [msg (ex-message e)]
        (when-not (or (str/includes? msg "already exists")
                      (str/includes? msg "duplicate column"))
          (mulog/error ::ddl-execution-failed :statement stmt :error msg)))
      false)))

(def ^:private live-edge-unique-index
  "At most ONE live edge per (user, src, dst, relation).

   The table's `UNIQUE(user_id, src_id, dst_id, relation, t_valid)` cannot
   enforce this: `t_valid` defaults to CURRENT_TIMESTAMP, so re-asserting the
   same relation a second later inserted a duplicate LIVE row (the Phase-0
   caveat noted in `graph/upsert-edge!`). Re-extraction is expected here — a
   per-session watermark run and a later per-user sweep deliberately overlap
   rather than risk skipping episodes — so the write path has to be idempotent.

   Partial on `t_invalid IS NULL` so bi-temporal history still works:
   invalidate-then-reassert keeps the superseded rows and adds one new live row.
   `graph/upsert-edge!` targets this index in its ON CONFLICT clause; the two
   must stay in sync or inserts throw instead of upserting."
  "CREATE UNIQUE INDEX IF NOT EXISTS idx_edges_live_unique
     ON graph_edges(user_id, src_id, dst_id, relation) WHERE t_invalid IS NULL")

(defn- rebuild-graph-edges!
  "Drop the obsolete table-level `UNIQUE(user_id, src_id, dst_id, relation,
   t_valid)` from an existing `graph_edges`. SQLite cannot ALTER a constraint
   away, so this is the standard create-copy-drop-rename rebuild.

   Why it must go rather than merely be superseded: it does NOT exclude
   invalidated rows, so re-asserting a relation in the same second that its own
   previous version was superseded raises SQLITE_CONSTRAINT_UNIQUE. `ON CONFLICT`
   cannot cover it — sqlite permits a single conflict target per INSERT, and
   that target is the live-rows partial index. Leaving the old key in place
   breaks bi-temporal supersession outright.

   Guarded: a no-op unless the old constraint is actually present, so it runs at
   most once per database. Nothing references `graph_edges` (no inbound FKs,
   triggers or views), so the rename is safe. Indexes are recreated by the
   IF NOT EXISTS DDL / `live-edge-unique-index` that follow."
  [ds]
  (try
    (let [ddl (some-> (jdbc/execute-one!
                       ds ["SELECT sql FROM sqlite_master WHERE type='table' AND name='graph_edges'"])
                      first val str)]
      (when (str/includes? (str/replace ddl #"\s+" " ") "UNIQUE(user_id, src_id, dst_id, relation, t_valid)")
        (jdbc/with-transaction [tx ds]
          (jdbc/execute! tx ["CREATE TABLE graph_edges_rebuild (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                user_id TEXT NOT NULL,
                                src_id INTEGER NOT NULL REFERENCES graph_nodes(id),
                                dst_id INTEGER NOT NULL REFERENCES graph_nodes(id),
                                relation TEXT NOT NULL,
                                fact TEXT,
                                confidence REAL DEFAULT 0.85,
                                t_valid DATETIME DEFAULT CURRENT_TIMESTAMP,
                                t_invalid DATETIME,
                                ingested_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                                source_entry_ids TEXT)"])
          ;; Explicit column list — never SELECT *, so a future column added to
          ;; the live table can't silently shift into the wrong slot here.
          (jdbc/execute! tx ["INSERT INTO graph_edges_rebuild
                                (id, user_id, src_id, dst_id, relation, fact, confidence,
                                 t_valid, t_invalid, ingested_at, source_entry_ids)
                              SELECT id, user_id, src_id, dst_id, relation, fact, confidence,
                                     t_valid, t_invalid, ingested_at, source_entry_ids
                                FROM graph_edges"])
          (jdbc/execute! tx ["DROP TABLE graph_edges"])
          (jdbc/execute! tx ["ALTER TABLE graph_edges_rebuild RENAME TO graph_edges"]))
        (mulog/info ::rebuilt-graph-edges :dropped "UNIQUE(user_id,src_id,dst_id,relation,t_valid)")))
    (catch Exception e
      (mulog/warn ::rebuild-graph-edges-failed :error (ex-message e)))))

(defn- dedupe-live-edges!
  "Collapse pre-existing duplicate LIVE edges so `live-edge-unique-index` can be
   created. Must run BEFORE it — the index cannot be built over a table that
   already violates it, and `execute-ddl!` only logs that failure.

   Keeps one row per (user, src, dst, relation): a populated `fact` wins over a
   blank one (observed duplicates included degraded blank-fact copies), then the
   earliest `t_valid` (the original assertion), then the lowest id for
   determinism. Provenance is adopted from a discarded row when the keeper has
   none — verbatim, never concatenated, since `source_entry_ids` is JSON.

   Only touches live rows; invalidated history is never deleted. Idempotent: a
   no-op once no duplicates remain."
  [ds]
  (try
    (let [;; `val (first …)` rather than a keyword lookup: an aggregate over a
          ;; subquery has no table to namespace the key with, so the builder's
          ;; key name is not worth depending on.
          dups (some-> (jdbc/execute-one!
                        ds ["SELECT COUNT(*) AS n FROM (
                              SELECT 1 FROM graph_edges WHERE t_invalid IS NULL
                              GROUP BY user_id, src_id, dst_id, relation
                              HAVING COUNT(*) > 1)"])
                       first val)]
      (when (pos? (long (or dups 0)))
        ;; Rank once; both statements below reuse the same keeper choice.
        (let [ranked "SELECT id, user_id, src_id, dst_id, relation, source_entry_ids,
                             ROW_NUMBER() OVER (
                               PARTITION BY user_id, src_id, dst_id, relation
                               ORDER BY (CASE WHEN COALESCE(TRIM(fact),'') = '' THEN 1 ELSE 0 END),
                                        t_valid, id) AS rn
                      FROM graph_edges WHERE t_invalid IS NULL"]
          ;; 1. Don't lose provenance the keeper lacks.
          (jdbc/execute!
           ds [(str "UPDATE graph_edges SET source_entry_ids = (
                       SELECT d.source_entry_ids FROM (" ranked ") d
                        WHERE d.user_id = graph_edges.user_id
                          AND d.src_id = graph_edges.src_id
                          AND d.dst_id = graph_edges.dst_id
                          AND d.relation = graph_edges.relation
                          AND d.rn > 1
                          AND COALESCE(TRIM(d.source_entry_ids),'') <> ''
                        LIMIT 1)
                     WHERE t_invalid IS NULL
                       AND COALESCE(TRIM(source_entry_ids),'') = ''
                       AND id IN (SELECT id FROM (" ranked ") WHERE rn = 1)
                       AND EXISTS (SELECT 1 FROM (" ranked ") d2
                                    WHERE d2.user_id = graph_edges.user_id
                                      AND d2.src_id = graph_edges.src_id
                                      AND d2.dst_id = graph_edges.dst_id
                                      AND d2.relation = graph_edges.relation
                                      AND d2.rn > 1
                                      AND COALESCE(TRIM(d2.source_entry_ids),'') <> '')")])
          ;; 2. Drop the redundant live copies.
          (let [r (jdbc/execute-one!
                   ds [(str "DELETE FROM graph_edges
                              WHERE t_invalid IS NULL
                                AND id IN (SELECT id FROM (" ranked ") WHERE rn > 1)")])]
            (mulog/info ::deduped-live-edges :duplicate-groups dups :rows-deleted (or (:next.jdbc/update-count r) 0))))))
    (catch Exception e
      ;; Never block schema init on this; the index creation below then logs
      ;; its own failure and the DB keeps working with the old constraint.
      (mulog/warn ::dedupe-live-edges-failed :error (ex-message e)))))

(defn- column-exists?
  "True when `table` has a column named `col`."
  [ds table col]
  (try
    (some #(= col (or (:name %) (get % (keyword "table_info" "name"))
                      (get % :PRAGMA_TABLE_INFO/name)))
          (jdbc/execute! ds [(str "PRAGMA table_info(" table ")")]))
    (catch Exception _ false)))

(defn init-schema!
  "Initialize all memory tables and FTS5 virtual tables.
  Safe to call multiple times — uses IF NOT EXISTS.

  Options:
    :embed-dims — dimensionality of the `graph_vec` vector index (CR-MEM-21).
                  Defaults to `graph-embed-dims` (BY_GRAPH_EMBED_DIMS or 768).
                  Pass the configured embedder's native dim (e.g. 256 for the
                  bundled Model2Vec model) so the table matches the vectors."
  [ds & {:keys [embed-dims]}]
  (mulog/info ::schema-initializing)
  ;; Replace the two unscoped FTS update triggers BEFORE the schema doseq —
  ;; `CREATE TRIGGER IF NOT EXISTS` will not replace an existing definition, so
  ;; without this drop an existing database keeps firing a full FTS re-index on
  ;; every flag and counter write (see the trigger bodies for why that is worse
  ;; than slow). Dropping is safe at any point: the trigger is recreated a few
  ;; lines below, in the same open, before anything can write.
  (doseq [t ["episodes_au" "semantic_au"]]
    (execute-ddl! ds (str "DROP TRIGGER IF EXISTS " t)))

  (let [all-schemas (concat metadata-schema
                            episodic-schema
                            semantic-schema
                            audit-schema
                            graph-schema
                            graph-edge-indexes
                            ;; The vector index only exists when sqlite-vec
                            ;; loaded; otherwise recall falls back to FTS.
                            (when (resolve-vec-extension)
                              (vec-schema (or embed-dims (graph-embed-dims)))))]
    (doseq [stmt all-schemas]
      (execute-ddl! ds stmt))

    ;; Migration for pre-existing graph_nodes (schema 2.1.0) that predate the
    ;; community_id column — CREATE TABLE IF NOT EXISTS won't add it, so ALTER
    ;; in only when missing (fresh dbs already have it from the CREATE above,
    ;; so column-exists? is true and this is skipped).
    (when-not (column-exists? ds "graph_nodes" "community_id")
      (execute-ddl! ds "ALTER TABLE graph_nodes ADD COLUMN community_id INTEGER"))

    ;; Usage feedback on recall (A1 of docs/design/evoharness-rl-comparison.md).
    ;; semantic_facts has carried these two since the first schema; episodes
    ;; never did, so an L2 hit had nowhere to record that it was read. Same
    ;; ALTER-when-missing shape as community_id above: a fresh database gets
    ;; them from the CREATE and skips this.
    ;;
    ;; DEFAULT 0 rather than NULL so an existing row reads as "never injected"
    ;; instead of "unknown", which is what it actually is — nothing was
    ;; counting before this.
    (when-not (column-exists? ds "episodes" "access_count")
      (execute-ddl! ds "ALTER TABLE episodes ADD COLUMN access_count INTEGER DEFAULT 0"))
    (when-not (column-exists? ds "episodes" "last_accessed")
      (execute-ddl! ds "ALTER TABLE episodes ADD COLUMN last_accessed DATETIME"))

    ;; 2.3.0: exactly one LIVE edge per (user, src, dst, relation).
    ;; ORDER MATTERS, all three steps:
    ;;   1. drop the obsolete UNIQUE(..., t_valid) — it rejects legitimate
    ;;      re-assertion after supersession, and ON CONFLICT cannot cover both
    ;;      it and the partial index;
    ;;   2. collapse duplicate live rows — the unique index cannot be built over
    ;;      a table that already violates it, and execute-ddl! only LOGS that;
    ;;   3. create the index.
    ;; Deliberately after the `all-schemas` doseq, which only runs IF NOT EXISTS
    ;; DDL and would otherwise attempt the index before the data is clean.
    (rebuild-graph-edges! ds)
    ;; The rebuild DROPped the table, taking idx_edges_src/idx_edges_dst with
    ;; it — the doseq above already ran, so recreate them or neighbor lookups
    ;; silently lose their index.
    (doseq [stmt graph-edge-indexes]
      (execute-ddl! ds stmt))
    (dedupe-live-edges! ds)
    (execute-ddl! ds live-edge-unique-index)

    ;; Store schema version. 2.0.0 introduced unified-memory columns
    ;; (tags, sources, entry_id, keep_flag, archived_flag, tombstoned_flag)
    ;; on episodes and semantic_facts. 2.1.0 added the context-graph overlay
    ;; (graph_nodes, graph_edges — CR-MEM-20). 2.2.0 adds communities
    ;; (graph_communities + graph_nodes.community_id — CR-MEM-24). 2.3.0 adds
    ;; `idx_edges_live_unique` so edge re-assertion is idempotent, and is the
    ;; first version carrying a real DATA migration (`dedupe-live-edges!`)
    ;; rather than DDL alone — it collapses duplicate LIVE edges minted before
    ;; the index existed. Everything else is IF NOT EXISTS (plus the guarded
    ;; ALTER above), so existing databases still migrate transparently on open.
    (try
      (jdbc/execute! ds
                     ["INSERT OR REPLACE INTO memory_metadata (key, value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)"
                      "schema_version" "2.3.0"])
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

(defn get-metadata
  "Read a `memory_metadata` value by key, or nil."
  [ds k]
  (try
    (let [r (jdbc/execute-one! ds ["SELECT value FROM memory_metadata WHERE key = ?" k])]
      (or (:value r) (:memory_metadata/value r)))
    (catch Exception _ nil)))

(defn set-metadata!
  "Upsert a `memory_metadata` key/value."
  [ds k v]
  (try
    (jdbc/execute! ds ["INSERT OR REPLACE INTO memory_metadata (key, value, updated_at)
                        VALUES (?, ?, CURRENT_TIMESTAMP)" k v])
    (catch Exception e
      (mulog/warn ::metadata-set-failed :key k :error (ex-message e)))))

(defn recreate-graph-vec!
  "Drop and recreate `graph_vec` at `dims` (CR-MEM-21 rebuild). No-op when
  sqlite-vec is unavailable. Used by the embed-model-change rebuild."
  [ds dims]
  (when (resolve-vec-extension)
    (execute-ddl! ds "DROP TABLE IF EXISTS graph_vec")
    (doseq [stmt (vec-schema dims)] (execute-ddl! ds stmt))))

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
