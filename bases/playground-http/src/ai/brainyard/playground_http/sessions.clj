;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-http.sessions
  "Session store + lifecycle glue — the in-memory stand-in for session-broker +
   playground-store. Holds one record per workspace and drives the
   `workspace` runtime (Docker) to start/stop the container behind each.

   Two modes:
   - real  (default): each session is a `brainyard/workspace` container running
     `by --web-tmux`; `create!` blocks until ttyd is reachable, then the `/tty`
     route proxies to it.
   - fake  (`PG_FAKE=1`, or Docker unavailable): no container; the `/tty` route
     falls back to the echo stub. Lets the SPA/REST flow run with no Docker.

   Internal fields (`:host-port`, `:ttyd-pass`, `:container-id`) NEVER leave this
   ns — `public` strips them so credentials can't reach the client or logs."
  (:require [clojure.string :as str]
            [ai.brainyard.playground-http.workspace :as workspace]))

(defonce ^:private store (atom {}))   ; id -> full session record

(def ^:private ready-timeout-ms 30000)

(defn fake-mode?
  "True when containers are disabled — explicit PG_FAKE=1, or no Docker daemon."
  []
  (or (= "1" (System/getenv "PG_FAKE"))
      (not (workspace/docker-available?))))

(defn- gen-id []
  (subs (str/replace (str (random-uuid)) "-" "") 0 12))

(defn- public
  "Client-safe projection: identity + status only, no credentials/ports."
  [s]
  (when s (select-keys s [:id :status :created-at])))

;; --- queries ---------------------------------------------------------------

(defn list-all []
  (->> (vals @store) (sort-by :created-at) (map public) vec))

(defn get-one [id]
  (public (get @store id)))

(defn upstream
  "Proxy target for `id` — {:host-port :ttyd-user :ttyd-pass} — or nil when the
   session has no live container (fake mode / not ready)."
  [id]
  (when-let [s (get @store id)]
    (when (:host-port s)
      (select-keys s [:host-port :ttyd-user :ttyd-pass]))))

;; --- lifecycle -------------------------------------------------------------

(defn create!
  "Allocate a workspace. In real mode, start a container and block until its
   ttyd answers (or fail). Returns the public projection (with :status
   ready|failed)."
  []
  (let [id  (gen-id)
        now (System/currentTimeMillis)]
    (if (fake-mode?)
      (let [s {:id id :status "ready" :created-at now :fake true}]
        (swap! store assoc id s)
        (public s))
      (let [res (workspace/start! id)]
        (if (:error res)
          (let [s {:id id :status "failed" :created-at now :error (:error res)}]
            (swap! store assoc id s)
            (public s))
          (let [ready? (workspace/wait-ready! (:host-port res) ready-timeout-ms)
                s (merge {:id id :created-at now
                          :status (if ready? "ready" "failed")}
                         res)]
            (when-not ready? (workspace/stop! id))
            (swap! store assoc id s)
            (public s)))))))

(defn resume!
  "Phase-0 resume: restart a stopped container (real) or flip status (fake)."
  [id]
  (when-let [s (get @store id)]
    (if (:fake s)
      (do (swap! store assoc-in [id :status] "ready") (public (get @store id)))
      (let [res (workspace/start! id)]
        (if (:error res)
          (do (swap! store assoc-in [id :status] "failed") (public (get @store id)))
          (let [ready? (workspace/wait-ready! (:host-port res) ready-timeout-ms)]
            (swap! store update id merge res {:status (if ready? "ready" "failed")})
            (public (get @store id))))))))

(defn destroy! [id]
  (when-let [s (get @store id)]
    (when-not (:fake s) (workspace/stop! id)))
  (swap! store dissoc id)
  nil)
