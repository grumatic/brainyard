;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-http.workspace
  "Workspace runtime — the *mechanism* for running one tenant's `by` inside an
   isolated boundary. Phase-0 driver: a Docker container per session running the
   workspace image (ttyd + tmux + `by run`) bound to container-internal 7681,
   published to an ephemeral host port the proxy dials.

   This is the seam the design calls `workspace-runtime`: `start!`/`stop!`/
   `status` behind which Docker → gVisor/Firecracker is a driver swap. Kept as a
   base namespace for Phase 0 (one runnable artifact); it graduates to
   `components/workspace-runtime` when the playground-server project is created.

   Policy (when to start/suspend/destroy) lives in the session store; this ns
   only knows how to run a container. No secrets pass through logs here."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as j])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.util.concurrent TimeUnit]))

(def ^:private image
  (or (System/getenv "PG_WORKSPACE_IMAGE") "brainyard/workspace:dev"))

(def ^:private ttyd-user "by")        ; ttyd basic-auth username (matches proxy)
(def ^:private container-port "7681") ; ttyd inside the container

;; --- shelling out to docker ------------------------------------------------

(defn- sh
  "Run a command, returning {:exit :out :err}. Never throws on non-zero."
  [& args]
  (let [pb   (doto (ProcessBuilder. ^java.util.List (vec args))
               (.redirectErrorStream false))
        proc (.start pb)
        out  (slurp (.getInputStream proc))
        err  (slurp (.getErrorStream proc))]
    (.waitFor proc 60 TimeUnit/SECONDS)
    {:exit (.exitValue proc) :out (str/trim out) :err (str/trim err)}))

(defn docker-available?
  "True when the docker CLI is reachable and the daemon answers."
  []
  (zero? (:exit (sh "docker" "version" "--format" "{{.Server.Version}}"))))

(defn- gen-pass []
  (str/replace (str (random-uuid)) "-" ""))

(defn- env-file-args
  "If PG_WORKSPACE_ENV_FILE points at a readable file, pass it to `by` inside
   the container so a provider is configured. This is the Phase-0 stand-in for
   playground-secrets (per-user creds injected at start, never baked/logged)."
  []
  (when-let [f (System/getenv "PG_WORKSPACE_ENV_FILE")]
    (when (.canRead (io/file f))
      ["--env-file" f])))

(defn- by-args
  "Extra args appended after the image → passed to the container's
   `by --web-tmux` ENTRYPOINT, which forwards `-p`/`-m`/`-a` to the child
   `by run`. Lets a deployment pick the workspace's provider/model/agent
   (e.g. PG_WORKSPACE_PROVIDER=openai) without rebuilding the image."
  []
  (concat
   (when-let [p (System/getenv "PG_WORKSPACE_PROVIDER")] ["-p" p])
   (when-let [m (System/getenv "PG_WORKSPACE_MODEL")]    ["-m" m])
   (when-let [a (System/getenv "PG_WORKSPACE_AGENT")]    ["-a" a])))

(defn- aws-mount-args
  "When PG_WORKSPACE_AWS_DIR points at a readable dir (e.g. ~/.aws), mount it
   read-only at the container user's $HOME/.aws so the Bedrock SDK finds the
   profile (`AWS_PROFILE`/`AWS_REGION` arrive via the env-file). Phase-0
   stand-in for playground-secrets resolving per-user cloud creds."
  []
  (when-let [d (System/getenv "PG_WORKSPACE_AWS_DIR")]
    (when (.isDirectory (io/file d))
      ["-v" (str d ":/home/by/.aws:ro")])))

(defn- published-host-port
  "Resolve the ephemeral host port Docker mapped to the container's 7681."
  [container-id]
  (let [{:keys [exit out]} (sh "docker" "port" container-id (str container-port "/tcp"))]
    (when (zero? exit)
      ;; e.g. "127.0.0.1:54231" (last line if both v4/v6 printed)
      (some-> (last (str/split-lines out))
              (str/split #":") last str/trim Integer/parseInt))))

;; --- lifecycle -------------------------------------------------------------

(defn- write-secret-env-file
  "Write env vars to an owner-only (0600) temp file as K=V lines, returning its
   path. Secrets go through `--env-file`, never the docker command line (which
   `ps` would expose). Created with restrictive perms atomically; the caller
   deletes it right after `docker run` consumes it."
  ^String [env-map]
  (let [perms (PosixFilePermissions/asFileAttribute
               (PosixFilePermissions/fromString "rw-------"))
        path  (Files/createTempFile "pg-secrets-" ".env"
                                    (into-array FileAttribute [perms]))]
    (spit (.toFile path)
          (->> env-map (map (fn [[k v]] (str k "=" v))) (str/join "\n")))
    (str path)))

(defn start!
  "Start a workspace container for `session-id`. `user-env` is an optional map of
   per-user secret env vars (from playground-secrets) injected via a private
   env-file — overriding the shared one. Returns {:container-id :host-port
   :ttyd-user :ttyd-pass} or {:error msg}. Binds ttyd to a random host port on
   127.0.0.1 (host-reachable, not public)."
  ([session-id] (start! session-id nil))
  ([session-id user-env]
   (let [pass (gen-pass)
         name (str "pg-" session-id)
         secret-file (when (seq user-env) (write-secret-env-file user-env))
         args (concat ["docker" "run" "-d" "--rm"
                       "--name" name
                       ;; ephemeral host port on loopback -> container 7681
                       "-p" (str "127.0.0.1:0:" container-port)
                       ;; per-session ttyd basic-auth password
                       "-e" (str "BY_WEB_PASS=" pass)
                       ;; stable identity for `by` memory partitioning
                       "-e" (str "BY_USER_ID=" session-id)]
                      (env-file-args)                                  ; shared base
                      (when secret-file ["--env-file" secret-file])    ; per-user wins
                      (aws-mount-args)
                      [image]
                      (by-args))
         {:keys [exit out err]} (apply sh args)]
     ;; The daemon has read the file by the time `docker run` returns; remove it.
     (when secret-file (io/delete-file secret-file true))
     (if-not (zero? exit)
       {:error (str "docker run failed: " (not-empty err) (not-empty out))}
       (let [cid  (first (str/split-lines out))
             port (published-host-port cid)]
         (if port
           {:container-id cid :host-port port :ttyd-user ttyd-user :ttyd-pass pass}
           (do (sh "docker" "rm" "-f" cid)
               {:error "could not resolve published host port"})))))))

(defn ttyd-ready?
  "True once the container's ttyd answers on the published port. ttyd requires
   basic-auth, so an unauthenticated GET returning 401 means it's up."
  [host-port]
  (try
    (let [url  (java.net.URI. (str "http://127.0.0.1:" host-port "/"))
          conn ^java.net.HttpURLConnection (.openConnection (.toURL url))]
      (.setConnectTimeout conn 500)
      (.setReadTimeout conn 500)
      (.setRequestMethod conn "GET")
      (let [code (.getResponseCode conn)]
        (.disconnect conn)
        (contains? #{200 401} code)))
    (catch Exception _ false)))

(defn wait-ready!
  "Poll until ttyd answers or `timeout-ms` elapses. Returns true/false."
  [host-port timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (ttyd-ready? host-port)                 true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 250) (recur))))))

(defn stop!
  "Stop + remove the container for `session-id` (idempotent)."
  [session-id]
  (sh "docker" "rm" "-f" (str "pg-" session-id))
  nil)

(defn status
  "Docker state string for the session's container, or nil if absent."
  [session-id]
  (let [{:keys [exit out]} (sh "docker" "inspect" "-f" "{{.State.Status}}"
                               (str "pg-" session-id))]
    (when (zero? exit) (not-empty out))))

(defn running?
  "True when the session's container is up."
  [session-id]
  (= "running" (status session-id)))

(defn rederive-upstream
  "Re-read {:host-port :ttyd-user :ttyd-pass} from a running container, or nil.
   Used to rebuild the in-memory upstream cache after a control-plane restart —
   the password lives only in the container's env, never in the store."
  [session-id]
  (let [cid (str "pg-" session-id)]
    (when-let [port (published-host-port cid)]
      (let [{:keys [exit out]} (sh "docker" "exec" cid "printenv" "BY_WEB_PASS")]
        (when (and (zero? exit) (not (str/blank? out)))
          {:host-port port :ttyd-user ttyd-user :ttyd-pass (str/trim out)})))))
