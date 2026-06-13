;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-server.store
  "Durable control-plane state — the `playground-store`. Persists the
   authoritative session records so the control plane is stateless and
   restart-safe (design §5.4). One `JdbcStore` (next.jdbc) serves two engines,
   plus an in-memory fallback, selected by `make-store`:

   - **SQLite** (default) — a file at `~/.brainyard/playground.db` (override
     with `PLAYGROUND_DB`). Zero-ops, durable across restarts, single-node.
   - **Postgres** when `PG_DATABASE_URL` is set — opt-in, for multi-node
     scale-out. The SQL is portable; only the datasource differs.
   - **mem** (atom) when `PG_FAKE=1` — the dependency-free dev/test path.

   Only DURABLE, non-secret fields live here: `:id :user-id :status :created-at
   :container-id :fake`. The ephemeral ttyd password + published host port are
   runtime-only and secret — they stay in memory in `sessions` and are
   re-derived from the running container on restart, never written to the DB."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as j]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [org.sqlite SQLiteConfig SQLiteConfig$JournalMode
            SQLiteConfig$SynchronousMode SQLiteDataSource]))

(defprotocol Store
  (save! [s rec]        "Upsert a session record (by :id).")
  (fetch [s id]         "Record for id, or nil.")
  (by-user [s uid]      "Records owned by uid, oldest first.")
  (remove! [s id]       "Delete the record for id.")
  (all [s]              "Every record (for startup reconcile).")
  (env-get [s uid]      "Per-user BYO env map {name->value}, or nil.")
  (env-set! [s uid env] "Replace the per-user BYO env map; returns it."))

;; --- in-memory -------------------------------------------------------------

(defrecord MemStore [a env-a]
  Store
  (save!    [_ rec] (swap! a assoc (:id rec) rec) rec)
  (fetch    [_ id]  (get @a id))
  (by-user  [_ uid] (->> (vals @a) (filter #(= uid (:user-id %))) (sort-by :created-at) vec))
  (remove!  [_ id]  (swap! a dissoc id) nil)
  (all      [_]     (vec (vals @a)))
  (env-get  [_ uid] (get @env-a uid))
  (env-set! [_ uid env] (swap! env-a assoc uid env) env))

(defn mem-store [] (->MemStore (atom {}) (atom {})))

;; --- jdbc (sqlite | postgres) ----------------------------------------------
;; DDL is kept dialect-neutral so the same statements run on both engines:
;; `bigint`/`integer` are accepted by SQLite's flexible typing, and `fake` is an
;; INTEGER 0/1 (not a `boolean` literal, which SQLite only learned recently) —
;; coerced back to a Clojure boolean by `row->rec`.

(def ^:private ddl
  "CREATE TABLE IF NOT EXISTS workspaces (
     id           text PRIMARY KEY,
     user_id      text NOT NULL,
     status       text NOT NULL,
     created_at   bigint NOT NULL,
     container_id text,
     fake         integer NOT NULL DEFAULT 0)")

(def ^:private ddl-idx
  "CREATE INDEX IF NOT EXISTS workspaces_user_id_idx ON workspaces (user_id)")

;; BYO env (settings screen): per-user env vars injected into their workspace.
;; Stored as JSON text. NOTE: plaintext at rest — acceptable for a dev preview
;; (the user's own keys); a hardening pass would encrypt or push to Vault.
(def ^:private ddl-env
  "CREATE TABLE IF NOT EXISTS user_env (user_id text PRIMARY KEY, env text NOT NULL)")

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- ->bool
  "Coerce a DB `fake` value to a Clojure boolean. SQLite returns 0/1 (and
   `(boolean 0)` is truthy in Clojure!), Postgres returns a real Boolean."
  [v]
  (cond (boolean? v) v
        (number? v)  (not (zero? v))
        (string? v)  (contains? #{"1" "t" "true"} (str/lower-case v))
        :else        (boolean v)))

(defn- row->rec [row]
  (when row (update row :fake ->bool)))

(defrecord JdbcStore [ds]
  Store
  (save! [_ {:keys [id user-id status created-at container-id fake]}]
    (jdbc/execute-one!
     ds
     ["INSERT INTO workspaces (id, user_id, status, created_at, container_id, fake)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT (id) DO UPDATE
       SET status = EXCLUDED.status, container_id = EXCLUDED.container_id"
      id user-id status created-at container-id (if fake 1 0)])
    {:id id :user-id user-id :status status :created-at created-at
     :container-id container-id :fake (boolean fake)})
  (fetch [_ id]
    (row->rec (jdbc/execute-one! ds ["SELECT * FROM workspaces WHERE id = ?" id] opts)))
  (by-user [_ uid]
    (mapv row->rec (jdbc/execute! ds ["SELECT * FROM workspaces WHERE user_id = ? ORDER BY created_at" uid] opts)))
  (remove! [_ id]
    (jdbc/execute-one! ds ["DELETE FROM workspaces WHERE id = ?" id]) nil)
  (all [_]
    (mapv row->rec (jdbc/execute! ds ["SELECT * FROM workspaces ORDER BY created_at"] opts)))
  (env-get [_ uid]
    (some-> (jdbc/execute-one! ds ["SELECT env FROM user_env WHERE user_id = ?" uid] opts)
            :env (j/read-value (j/object-mapper))))
  (env-set! [_ uid env]
    (jdbc/execute-one! ds
                       ["INSERT INTO user_env (user_id, env) VALUES (?, ?)
        ON CONFLICT (user_id) DO UPDATE SET env = EXCLUDED.env"
                        uid (j/write-value-as-string env)])
    env))

(defn jdbc-store
  "Wrap a ready datasource and create the schema (idempotent)."
  [ds]
  (jdbc/execute! ds [ddl])
  (jdbc/execute! ds [ddl-idx])
  (jdbc/execute! ds [ddl-env])
  (->JdbcStore ds))

;; --- datasources -----------------------------------------------------------

(defn- pg-datasource [jdbc-url]
  (jdbc/get-datasource {:jdbcUrl jdbc-url}))

(defn- sqlite-datasource
  "A SQLiteDataSource at `path` with WAL + a busy timeout so the http-kit worker
   threads don't trip over 'database is locked' under light concurrency. Pragmas
   are set on the config so EVERY connection the datasource hands out inherits
   them (a URL alone would not). Creates the parent dir if missing."
  [path]
  (let [f (io/file path)]
    (some-> (.getParentFile (.getAbsoluteFile f)) (.mkdirs))
    (let [cfg (doto (SQLiteConfig.)
                (.setJournalMode SQLiteConfig$JournalMode/WAL)
                (.setSynchronous SQLiteConfig$SynchronousMode/NORMAL)
                (.setBusyTimeout 5000))]
      (doto (SQLiteDataSource. cfg)
        (.setUrl (str "jdbc:sqlite:" (.getAbsolutePath f)))))))

(defn- default-sqlite-path []
  (.getAbsolutePath (io/file (System/getProperty "user.home") ".brainyard" "playground.db")))

;; --- selection -------------------------------------------------------------

(defn make-store
  "Pick a backend by environment:
   - `PG_FAKE=1`        -> in-memory (dependency-free dev/test).
   - `PG_DATABASE_URL`  -> Postgres (multi-node opt-in).
   - otherwise (default)-> SQLite file at `PLAYGROUND_DB` or
                           `~/.brainyard/playground.db` — durable, zero-ops."
  []
  (cond
    (= "1" (System/getenv "PG_FAKE"))
    (mem-store)

    (not-empty (System/getenv "PG_DATABASE_URL"))
    (let [url (System/getenv "PG_DATABASE_URL")]
      (jdbc-store (pg-datasource (if (str/starts-with? url "jdbc:") url (str "jdbc:" url)))))

    :else
    (jdbc-store (sqlite-datasource (or (not-empty (System/getenv "PLAYGROUND_DB"))
                                       (default-sqlite-path))))))
