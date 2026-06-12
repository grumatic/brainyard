;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-http.server
  "http-kit lifecycle for the playground control plane (Phase-0 stub).

     cd bases/playground-http && clojure -M:run [port]   ; default 8090

   The front-end dev server (shadow-cljs on :8080) proxies /api, /auth and the
   /tty WebSocket here, so the SPA runs end-to-end against this stub."
  (:require [org.httpkit.server :as hk]
            [ai.brainyard.playground-http.routes :as routes]
            [ai.brainyard.playground-http.sessions :as sessions])
  (:gen-class))

(defonce ^:private server (atom nil))

(defn stop! []
  (when-let [s @server]
    (hk/server-stop! s)
    (reset! server nil)))

(defn start!
  ([] (start! {}))
  ([{:keys [port] :or {port 8090}}]
   (stop!)
   ;; Build the store + reconcile runtime state against running containers.
   (sessions/init!)
   ;; #'routes/app (a var) so a REPL reload is picked up without a restart.
   (reset! server (hk/run-server #'routes/app {:port port :legacy-return-value? false}))
   (println (str "playground-http listening on http://localhost:" port
                 (when (sessions/fake-mode?) "  (fake mode — no Docker)")))
   @server))

(defn -main [& [port]]
  (start! (cond-> {} port (assoc :port (Integer/parseInt port))))
  @(promise))   ; block the main thread forever
