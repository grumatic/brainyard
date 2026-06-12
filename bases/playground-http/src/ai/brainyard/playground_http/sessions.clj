;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-http.sessions
  "Session broker — lifecycle policy over the durable `store` and the Docker
   `workspace` runtime. Each session is owned by a `user-id`; cross-tenant
   access is refused by checking ownership on every per-id operation (design
   §5.3), so guessing another user's id yields nothing.

   Split of state:
   - **durable** (in `store`, Postgres or in-memory): id, user, status,
     created-at, container-id — the authoritative record, restart-safe.
   - **runtime** (in `upstream-cache`, always in memory): the published host
     port + the ephemeral ttyd password. Secret + disposable, never persisted;
     rebuilt from the running container by `init!`'s reconcile after a restart.

   Modes: real (Docker) vs fake (`PG_FAKE=1` / no Docker) — fake sessions skip
   the container and the `/tty` route falls back to the echo stub."
  (:require [clojure.string :as str]
            [ai.brainyard.playground-http.secrets :as secrets]
            [ai.brainyard.playground-http.store :as store]
            [ai.brainyard.playground-http.workspace :as workspace]))

(defonce ^:private db (atom nil))                 ; the Store instance
(defonce ^:private upstream-cache (atom {}))      ; id -> {:host-port :ttyd-user :ttyd-pass}

(def ^:private ready-timeout-ms 30000)

(defn fake-mode?
  "True when containers are disabled — explicit PG_FAKE=1, or no Docker daemon."
  []
  (or (= "1" (System/getenv "PG_FAKE"))
      (not (workspace/docker-available?))))

(defn- gen-id []
  (subs (str/replace (str (random-uuid)) "-" "") 0 12))

(defn- public
  "Client-safe projection: identity + status only, no credentials/ports/owner."
  [rec]
  (when rec (select-keys rec [:id :status :created-at])))

(defn- owned
  "The record if owned by `user-id`, else nil (the tenant boundary)."
  [user-id id]
  (let [rec (store/fetch @db id)]
    (when (and rec (= user-id (:user-id rec))) rec)))

;; --- startup ---------------------------------------------------------------

(defn init!
  "Build the store and reconcile in-memory runtime state against reality:
   for each persisted non-fake session, rebuild the upstream cache if its
   container is still running, else mark it suspended (container gone)."
  []
  (reset! db (store/make-store))
  (reset! upstream-cache {})
  (when-not (fake-mode?)
    (doseq [rec (store/all @db) :when (not (:fake rec))]
      (let [id (:id rec)]
        (if-let [up (and (workspace/running? id) (workspace/rederive-upstream id))]
          (do (swap! upstream-cache assoc id up)
              (when (not= "ready" (:status rec))
                (store/save! @db (assoc rec :status "ready"))))
          (when (not= "suspended" (:status rec))
            (store/save! @db (assoc rec :status "suspended")))))))
  @db)

(defn- store! [] (or @db (init!)))

;; --- queries ---------------------------------------------------------------

(defn list-for [user-id]
  (->> (store/by-user (store!) user-id) (map public) vec))

(defn get-for [user-id id]
  (public (owned user-id id)))

(defn upstream
  "Proxy target for `id` if owned by `user-id` and live — else nil."
  [user-id id]
  (when (owned user-id id)
    (get @upstream-cache id)))

;; --- lifecycle -------------------------------------------------------------

(defn- provision!
  "Start a container for `rec`, block until ttyd answers, cache upstream. Returns
   the rec with final :status (ready|failed) and :container-id."
  [rec]
  (let [id  (:id rec)
        res (workspace/start! id (secrets/env-for-user (:user-id rec)))]
    (if (:error res)
      (assoc rec :status "failed")
      (let [ready? (workspace/wait-ready! (:host-port res) ready-timeout-ms)]
        (if ready?
          (do (swap! upstream-cache assoc id (select-keys res [:host-port :ttyd-user :ttyd-pass]))
              (assoc rec :status "ready" :container-id (:container-id res)))
          (do (workspace/stop! id)
              (assoc rec :status "failed")))))))

(defn create!
  "Allocate a workspace for `user-id`. Returns the public projection."
  [user-id]
  (let [base {:id (gen-id) :user-id user-id :created-at (System/currentTimeMillis)}
        rec  (if (fake-mode?)
               (assoc base :status "ready" :fake true)
               (provision! (assoc base :status "provisioning" :fake false)))]
    (store/save! (store!) rec)
    (public rec)))

(defn resume!
  "Restart a stopped workspace owned by `user-id` (fake: just flip status)."
  [user-id id]
  (when-let [rec (owned user-id id)]
    (let [rec' (if (:fake rec)
                 (assoc rec :status "ready")
                 (provision! rec))]
      (store/save! @db rec')
      (public rec'))))

(defn destroy!
  "Reap a workspace owned by `user-id` (idempotent; no-op if not owned)."
  [user-id id]
  (when-let [rec (owned user-id id)]
    (when-not (:fake rec) (workspace/stop! id))
    (swap! upstream-cache dissoc id)
    (store/remove! @db id))
  nil)
