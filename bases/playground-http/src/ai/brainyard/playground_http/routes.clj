;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-http.routes
  "HTTP surface for the playground control plane (Phase-0 stub). Implements the
   exact contract the front-end (`playground-ui`) calls:

     GET    /api/me                       -> 200 user | 401
     GET    /api/sessions                 -> 200 [session ...]
     POST   /api/sessions                 -> 201 session
     GET    /api/sessions/:id             -> 200 session | 404
     POST   /api/sessions/:id/resume      -> 200 session | 404
     DELETE /api/sessions/:id             -> 204
     POST   /api/sessions/:id/tty-token   -> 200 {:token ...}
     GET    /api/sessions/:id/tty         -> WebSocket (ttyd protocol)
     GET    /auth/login                   -> 302 (set session cookie)
     GET    /auth/logout                  -> 302 (clear cookie)
     *                                    -> SPA static assets / index.html

   Auth here is a stub cookie. Real impl: playground-auth (OIDC) + JWT."
  (:require [clojure.java.io :as io]
            [jsonista.core :as j]
            [reitit.ring :as ring]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]]
            [ai.brainyard.playground-http.sessions :as sessions]
            [ai.brainyard.playground-http.proxy :as proxy]
            [ai.brainyard.playground-http.tty :as tty]))

(def ^:private cookie-name "pg_session")

(defn- json [status body]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (j/write-value-as-string body)})

(defn- authed? [req]
  (boolean (some-> req :cookies (get cookie-name) :value seq)))

(defn- current-user [_req]
  ;; Stub identity; real impl decodes the session/JWT -> user + quota.
  {:userId "demo-user"
   :email  "demo@grumatic.com"
   :quota  {:workspaces 5 :used (count (sessions/list-all))}})

;; --- handlers --------------------------------------------------------------

(defn- me [req]
  (if (authed? req)
    (json 200 (current-user req))
    (json 401 {:error "unauthenticated"})))

(defn- list-sessions   [_]   (json 200 (sessions/list-all)))
(defn- create-session  [_]   (json 201 (sessions/create!)))

(defn- get-session [req]
  (if-let [s (sessions/get-one (-> req :path-params :id))]
    (json 200 s)
    (json 404 {:error "not found"})))

(defn- resume-session [req]
  (if-let [s (sessions/resume! (-> req :path-params :id))]
    (json 200 s)
    (json 404 {:error "not found"})))

(defn- destroy-session [req]
  (sessions/destroy! (-> req :path-params :id))
  {:status 204})

(defn- tty-token [_]
  ;; Short-lived per-socket token. The stub doesn't verify it; playground-proxy
  ;; will. Returning it keeps the front-end handshake identical to production.
  (json 200 {:token (str (random-uuid))}))

(defn- tty-ws [req]
  ;; Proxy to the session's container ttyd when it has one; otherwise (fake
  ;; mode / no container) fall back to the echo stub so the SPA still works.
  (let [id (-> req :path-params :id)]
    (if-let [up (sessions/upstream id)]
      ((proxy/handler up) req)
      ((tty/handler id) req))))

(defn- auth-login [_]
  {:status  302
   :headers {"Location" "/"}
   :cookies {cookie-name {:value     (str (random-uuid))
                          :http-only true
                          :path      "/"
                          :same-site :lax}}})

(defn- auth-logout [_]
  {:status  302
   :headers {"Location" "/"}
   :cookies {cookie-name {:value "" :http-only true :path "/" :max-age 0}}})

;; --- middleware ------------------------------------------------------------

(defn- wrap-require-auth [handler]
  (fn [req]
    (if (authed? req)
      (handler req)
      (json 401 {:error "unauthenticated"}))))

;; --- router ----------------------------------------------------------------

(def router
  (ring/router
   [["/api"
     ["/me"                      {:get me}]
     ["/sessions"                {:get  (wrap-require-auth list-sessions)
                                  :post (wrap-require-auth create-session)}]
     ["/sessions/:id"            {:get    (wrap-require-auth get-session)
                                  :delete (wrap-require-auth destroy-session)}]
     ["/sessions/:id/resume"     {:post (wrap-require-auth resume-session)}]
     ["/sessions/:id/tty-token"  {:post (wrap-require-auth tty-token)}]
     ["/sessions/:id/tty"        {:get  (wrap-require-auth tty-ws)}]]
    ["/auth"
     ["/login"  {:get auth-login}]
     ["/logout" {:get auth-logout}]]]))

(defn- index-html [_]
  (if-let [r (io/resource "public/index.html")]
    {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body (slurp r)}
    {:status 404 :headers {"Content-Type" "text/plain"}
     :body "playground-ui not built. Run `npm run release` in frontend/playground-ui."}))

(def app
  "Top handler: API/auth routes, then static SPA assets, then index.html
   fallback so client-side routes (e.g. /workspace/:id) deep-link correctly."
  (ring/ring-handler
   router
   (ring/routes
    (ring/create-resource-handler {:path "/" :root "public"})
    index-html)
   {:middleware [wrap-cookies wrap-params]}))
