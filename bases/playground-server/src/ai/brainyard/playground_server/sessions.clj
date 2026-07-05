;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-server.sessions
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
            [ai.brainyard.playground-server.secrets :as secrets]
            [ai.brainyard.playground-server.store :as store]
            [ai.brainyard.playground-server.workspace :as workspace]))

(defonce ^:private db (atom nil))                 ; the Store instance
(defonce ^:private upstream-cache (atom {}))      ; id -> {:host-port :ttyd-user :ttyd-pass}

;; Idle-reaper activity clock — defined in the reaper section below, but seeded
;; by init!/provision! above it.
(declare touch!)

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
              (touch! id)                       ; start the idle clock for reconciled sessions
              (when (not= "ready" (:status rec))
                (store/save! @db (assoc rec :status "ready"))))
          (when (not= "suspended" (:status rec))
            (store/save! @db (assoc rec :status "suspended"))))))
    ;; Orphan-volume sweep: reclaim per-session volumes with no store record
    ;; (e.g. a crash between stop! and store removal during destroy). Runs at
    ;; startup before serving, so there are no concurrent creates to race.
    (let [live (set (map :id (store/all @db)))]
      (doseq [id (workspace/data-volume-session-ids)
              :when (not (contains? live id))]
        (workspace/remove-data-volumes! id))))
  @db)

(defn- store! [] (or @db (init!)))

;; --- queries ---------------------------------------------------------------

(defn list-for [user-id]
  (->> (store/by-user (store!) user-id) (map public) vec))

(defn get-for [user-id id]
  (public (owned user-id id)))

;; --- BYO env (settings) — injected into the user's workspace at provision -----

(defn get-env
  "The user's BYO env vars (settings screen): {name->value}, or {}."
  [user-id]
  (or (store/env-get (store!) user-id) {}))

(defn set-env!
  "Replace the user's BYO env vars. Takes effect for newly provisioned/resumed
   workspaces (env is injected at container start)."
  [user-id env]
  (store/env-set! (store!) user-id env))

(defn upstream
  "Proxy target for `id` if owned by `user-id` and live — else nil."
  [user-id id]
  (when (owned user-id id)
    (get @upstream-cache id)))

(defn ports
  "Published dev-port mappings (container 3000-3010 -> host) for a session owned
   by `user-id`: a vector [{:container n :host n} ...]. [] when the workspace
   isn't running (fake/suspended), nil when not owned (the tenant boundary)."
  [user-id id]
  (when-let [rec (owned user-id id)]
    (if (and (not (:fake rec)) (workspace/running? id))
      (workspace/published-dev-ports id)
      [])))

(defn brainyard-sessions
  "Live brainyard sessions inside `id`'s workspace container (for the config
   view). nil when not owned (the tenant boundary); [] when the workspace isn't
   running (fake/suspended)."
  [user-id id]
  (when-let [rec (owned user-id id)]
    (if (and (not (:fake rec)) (workspace/running? id))
      (workspace/brainyard-sessions id)
      [])))

(defn brainyard-session-config
  "Effective config of brainyard session `sid` inside `id`'s container, read over
   its ask channel. `sid`'s project dir is resolved from the live enumeration, so
   the sid is validated against this container and the project dir is never
   client-supplied. nil when not owned; {:success false …} when the workspace
   isn't running or `sid` is unknown."
  [user-id id sid query]
  (when-let [rec (owned user-id id)]
    (if (or (:fake rec) (not (workspace/running? id)))
      {:success false :session-id sid :error "workspace not running"}
      (if-let [wd (some #(when (= sid (:session-id %)) (:project-dir %))
                        (workspace/brainyard-sessions id))]
        (workspace/brainyard-session-config id wd sid query)
        {:success false :session-id sid
         :error (str "no live brainyard session '" sid "' in this workspace")}))))

(defn brainyard-graph
  "Context-graph memory dump for `id`'s workspace container (whole-DB, user-scoped
   — not per brainyard-session). nil when not owned (the tenant boundary);
   `{:success false …}` when the workspace isn't running."
  [user-id id]
  (when-let [rec (owned user-id id)]
    (if (or (:fake rec) (not (workspace/running? id)))
      {:success false :enabled? false :nodes [] :edges [] :counts {:nodes 0 :edges 0}
       :error "workspace not running"}
      (workspace/brainyard-graph id))))

(defn mark-down!
  "The container for `id` is gone/unreachable: drop its stale upstream cache and
   mark it suspended so the dashboard shows Resume. No-op if not owned/fake."
  [user-id id]
  (when-let [rec (owned user-id id)]
    (when-not (:fake rec)
      (swap! upstream-cache dissoc id)
      (store/save! @db (assoc rec :status "suspended"))))
  nil)

;; --- idle reaper -----------------------------------------------------------
;; Suspend (NOT destroy) workspaces with no connected client for the idle
;; window. Non-destructive thanks to persistent volumes + --resume-latest: the
;; dashboard shows Resume and the container comes back where it left off.
;; Idle = "no client connected for PG_IDLE_TIMEOUT_MIN" (default 30; 0 = off).

(defonce ^:private activity (atom {}))   ; id -> {:clients n :last-activity ms}
(defonce ^:private reaper   (atom nil))  ; ScheduledExecutorService or nil

(defn touch!
  "Reset `id`'s idle clock (called when a session goes ready). Preserves the
   current connected-client count."
  [id]
  (swap! activity update id
         #(assoc % :last-activity (System/currentTimeMillis) :clients (or (:clients %) 0)))
  nil)

(defn client-connected!
  "A browser opened the terminal for `id` (from the proxy WS bridge)."
  [id]
  (swap! activity update id
         (fn [a] (-> a (update :clients (fnil inc 0))
                     (assoc :last-activity (System/currentTimeMillis)))))
  nil)

(defn client-disconnected!
  "A browser closed the terminal for `id`. Starts the idle clock once the last
   client is gone."
  [id]
  (swap! activity update id
         (fn [a] (-> a (update :clients (fn [c] (max 0 (dec (or c 0)))))
                     (assoc :last-activity (System/currentTimeMillis)))))
  nil)

(defn suspend!
  "System-level suspend (the reaper): stop the container (volumes survive), drop
   the upstream cache + activity, mark suspended. No user check — the reaper acts
   on ids directly. No-op for fake/non-ready sessions."
  [id]
  (when-let [rec (store/fetch @db id)]
    (when (and (not (:fake rec)) (= "ready" (:status rec)))
      (workspace/stop! id)
      (swap! upstream-cache dissoc id)
      (swap! activity dissoc id)
      (store/save! @db (assoc rec :status "suspended"))))
  nil)

(defn- idle-timeout-ms []
  (long (* 60000 (or (some-> (System/getenv "PG_IDLE_TIMEOUT_MIN")
                             str/trim not-empty parse-double)
                     30.0))))

(defn- reaper-tick-ms []
  (long (* 1000 (or (some-> (System/getenv "PG_REAPER_TICK_SEC")
                            str/trim not-empty parse-double)
                    60.0))))

(defn- reap-once!
  "One sweep: suspend every ready, client-less session idle past the window."
  []
  (let [timeout (idle-timeout-ms)]
    (when (pos? timeout)
      (let [now (System/currentTimeMillis)]
        (doseq [rec (store/all @db)
                :when (and (= "ready" (:status rec)) (not (:fake rec)))]
          (let [id (:id rec) a (get @activity id)]
            (cond
              (nil? a)                       (touch! id)   ; first sight → seed clock
              (pos? (or (:clients a) 0))     nil           ; someone's watching
              (> (- now (or (:last-activity a) now)) timeout) (suspend! id))))))))

(defn stop-reaper! []
  (when-let [e @reaper]
    (.shutdownNow ^java.util.concurrent.ExecutorService e)
    (reset! reaper nil)))

(defn start-reaper!
  "Start the background idle-reaper (daemon thread). No-op in fake mode or when
   PG_IDLE_TIMEOUT_MIN <= 0."
  []
  (stop-reaper!)
  (when (and (not (fake-mode?)) (pos? (idle-timeout-ms)))
    (let [exec (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
                (reify java.util.concurrent.ThreadFactory
                  (newThread [_ r] (doto (Thread. ^Runnable r "pg-idle-reaper")
                                     (.setDaemon true)))))
          tick (reaper-tick-ms)]
      (.scheduleAtFixedRate ^java.util.concurrent.ScheduledExecutorService exec
                            ^Runnable #(try (reap-once!) (catch Throwable _ nil))
                            tick tick java.util.concurrent.TimeUnit/MILLISECONDS)
      (reset! reaper exec))))

;; --- lifecycle -------------------------------------------------------------

(defn- provision!
  "Start a container for `rec`, block until ttyd answers, cache upstream. Returns
   the rec with final :status (ready|failed) and :container-id."
  [rec]
  (let [id  (:id rec)
        ;; Per-user env injected into the container: Vault secrets, overlaid with
        ;; the user's BYO settings (BYO wins — it's their explicit choice).
        env (not-empty (merge (secrets/env-for-user (:user-id rec))
                              (store/env-get @db (:user-id rec))))
        res (workspace/start! id env)]
    (if (:error res)
      (assoc rec :status "failed")
      (let [ready? (workspace/wait-ready! (:host-port res) ready-timeout-ms)]
        (if ready?
          (do (swap! upstream-cache assoc id (select-keys res [:host-port :ttyd-user :ttyd-pass]))
              (touch! id)                       ; start the idle clock
              (assoc rec :status "ready" :container-id (:container-id res)))
          (do (workspace/stop! id)
              (assoc rec :status "failed")))))))

(defn- provision-async!
  "Run `provision!` for `rec` on a background thread, persisting the final
   status (ready|failed). Lets the HTTP handler return immediately with a
   `provisioning` record the client polls — Docker startup can take tens of
   seconds and must not block an http-kit worker. Skips the final save if the
   session was destroyed mid-provision, so a destroy can't be resurrected."
  [rec]
  (future
    (let [result (try (provision! rec)
                      (catch Throwable _ (assoc rec :status "failed")))]
      (when (store/fetch @db (:id rec))            ; still exists (not destroyed)
        (store/save! @db result)))))

(defn create!
  "Allocate a workspace for `user-id`. Real mode returns a `provisioning`
   record immediately and starts the container in the background; the client
   polls GET /api/sessions/:id until it reaches `ready`/`failed`. Returns the
   public projection."
  [user-id]
  (let [base {:id (gen-id) :user-id user-id :created-at (System/currentTimeMillis)}]
    (if (fake-mode?)
      (let [rec (assoc base :status "ready" :fake true)]
        (store/save! (store!) rec)
        (public rec))
      (let [rec (assoc base :status "provisioning" :fake false)]
        (store/save! (store!) rec)                 ; persist provisioning first → pollable
        (provision-async! rec)
        (public rec)))))

(defn resume!
  "Restart a stopped workspace owned by `user-id` (fake: just flip status).
   Real mode flips to `provisioning` and reprovisions in the background, same
   poll-to-ready contract as `create!`."
  [user-id id]
  (when-let [rec (owned user-id id)]
    (if (:fake rec)
      (let [rec' (assoc rec :status "ready")]
        (store/save! @db rec')
        (public rec'))
      (let [rec' (assoc rec :status "provisioning")]
        (store/save! @db rec')
        (provision-async! rec)
        (public rec')))))

(defn destroy!
  "Reap a workspace owned by `user-id` (idempotent; no-op if not owned). Destroy
   is the only path that discards persistent state: stop the container, then
   delete its volumes (resume must NOT — that's why suspend keeps them)."
  [user-id id]
  (when-let [rec (owned user-id id)]
    (when-not (:fake rec)
      (workspace/stop! id)
      (workspace/remove-data-volumes! id))
    (swap! upstream-cache dissoc id)
    (store/remove! @db id))
  nil)
