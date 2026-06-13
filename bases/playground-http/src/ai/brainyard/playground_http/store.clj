;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-http.store
  "Durable control-plane state — the `playground-store`. Persists the
   authoritative session records so the control plane is stateless and
   restart-safe (design §5.4). Two backends behind one `Store` protocol:

   - **jdbc** (Postgres) when `PG_DATABASE_URL` is set — the real path.
   - **mem** (atom) otherwise — keeps the dev/`PG_FAKE` flow dependency-free.

   Only DURABLE, non-secret fields live here: `:id :user-id :status :created-at
   :container-id :fake`. The ephemeral ttyd password + published host port are
   runtime-only and secret — they stay in memory in `sessions` and are
   re-derived from the running container on restart, never written to the DB."
  (:require [clojure.string :as str]
            [jsonista.core :as j]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]))

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

;; --- postgres --------------------------------------------------------------

(def ^:private ddl
  "CREATE TABLE IF NOT EXISTS workspaces (
     id           text PRIMARY KEY,
     user_id      text NOT NULL,
     status       text NOT NULL,
     created_at   bigint NOT NULL,
     container_id text,
     fake         boolean NOT NULL DEFAULT false)")

(def ^:private ddl-idx
  "CREATE INDEX IF NOT EXISTS workspaces_user_id_idx ON workspaces (user_id)")

;; BYO env (settings screen): per-user env vars injected into their workspace.
;; Stored as JSON text. NOTE: plaintext at rest — acceptable for a dev preview
;; (the user's own keys); a hardening pass would encrypt or push to Vault.
(def ^:private ddl-env
  "CREATE TABLE IF NOT EXISTS user_env (user_id text PRIMARY KEY, env text NOT NULL)")

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- row->rec [row]
  (when row (update row :fake boolean)))

(defrecord JdbcStore [ds]
  Store
  (save! [_ {:keys [id user-id status created-at container-id fake]}]
    (jdbc/execute-one!
     ds
     ["INSERT INTO workspaces (id, user_id, status, created_at, container_id, fake)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT (id) DO UPDATE
       SET status = EXCLUDED.status, container_id = EXCLUDED.container_id"
      id user-id status created-at container-id (boolean fake)])
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
  "Build a Postgres-backed store from a JDBC URL and create the schema."
  [jdbc-url]
  (let [ds (jdbc/get-datasource {:jdbcUrl jdbc-url})]
    (jdbc/execute! ds [ddl])
    (jdbc/execute! ds [ddl-idx])
    (jdbc/execute! ds [ddl-env])
    (->JdbcStore ds)))

;; --- selection -------------------------------------------------------------

(defn make-store
  "Postgres store when PG_DATABASE_URL is set (e.g.
   `jdbc:postgresql://localhost:5432/playground?user=pg&password=pg`),
   otherwise an in-memory store."
  []
  (if-let [url (not-empty (System/getenv "PG_DATABASE_URL"))]
    (let [url (if (str/starts-with? url "jdbc:") url (str "jdbc:" url))]
      (jdbc-store url))
    (mem-store)))
