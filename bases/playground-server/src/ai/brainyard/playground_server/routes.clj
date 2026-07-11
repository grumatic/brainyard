;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-server.routes
  "HTTP surface for the playground control plane (Phase-0 stub). Implements the
   exact contract the front-end (`playground-ui`) calls:

     GET    /api/me                       -> 200 user | 401
     GET    /api/sessions                 -> 200 [session ...]
     POST   /api/sessions                 -> 201 session
     GET    /api/sessions/:id             -> 200 session | 404
     POST   /api/sessions/:id/resume      -> 200 session | 404
     GET    /api/sessions/:id/ports       -> 200 {:ports [...]} | 404
     GET    /api/sessions/:id/brainyard   -> 200 {:sessions [...]} | 404
     GET    /api/sessions/:id/brainyard/:sid/config -> 200 {config} | 404
     GET    /api/sessions/:id/graph       -> 200 {:nodes :edges :counts} | 404
     GET    /api/sessions/:id/memory         -> 200 {:stats :vec-status} | 404
     GET    /api/sessions/:id/memory/list    -> 200 {:entries …} | 404   ?layer&session&kind&limit
     GET    /api/sessions/:id/memory/search  -> 200 {:entries …} | 404   ?q&session&limit
     GET    /api/sessions/:id/memory/explain -> 200 {:explain …} | 404   ?session&turn
     DELETE /api/sessions/:id             -> 204
     POST   /api/sessions/:id/tty-token   -> 200 {:token ...}
     GET    /api/sessions/:id/tty         -> WebSocket (ttyd protocol)
     GET    /auth/login                   -> 302 (set session cookie)
     GET    /auth/logout                  -> 302 (clear cookie)
     *                                    -> SPA static assets / index.html

   Auth here is a stub cookie. Real impl: playground-auth (OIDC) + JWT."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as j]
            [reitit.ring :as ring]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]]
            [ai.brainyard.playground-server.auth :as auth]
            [ai.brainyard.playground-server.sessions :as sessions]
            [ai.brainyard.playground-server.proxy :as proxy]
            [ai.brainyard.playground-server.tty :as tty]))

(defn- json [status body]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (j/write-value-as-string body)})

(defn- user-id [req] (:user-id (auth/identity-of req)))

(defn- json-body [req]
  (some-> (:body req) slurp not-empty (j/read-value (j/object-mapper))))

(defn- sanitize-env
  "Keep only valid env-var entries: name matches [A-Za-z_][A-Za-z0-9_]*, value
   coerced to string. Defends against junk/injection in the settings payload."
  [m]
  (when (map? m)
    (into {} (for [[k v] m
                   :let  [k (str k)]
                   :when (re-matches #"[A-Za-z_][A-Za-z0-9_]*" k)]
               [k (str v)]))))

(defn- current-user [req]
  (let [{:keys [user-id email]} (auth/identity-of req)]
    {:userId user-id
     :email  email
     :quota  {:workspaces 5 :used (count (sessions/list-for user-id))}}))

;; --- handlers --------------------------------------------------------------

(defn- me [req]
  (if (auth/identity-of req)
    (json 200 (current-user req))
    (json 401 {:error "unauthenticated"})))

;; Suggested env-var names for the settings dropdown (free-text still allowed).
(def ^:private builtin-env-names
  ["OPENAI_API_KEY" "ANTHROPIC_API_KEY" "GOOGLE_API_KEY" "GEMINI_API_KEY"
   "GROQ_API_KEY" "MISTRAL_API_KEY" "DEEPSEEK_API_KEY" "OPENROUTER_API_KEY"
   "TOGETHER_API_KEY" "FIREWORKS_API_KEY" "AZURE_OPENAI_API_KEY"
   "FREELLM_API_KEY" "FREELLM_BASE_URL"
   ;; AWS (Bedrock + aws CLI): a profile/region, or explicit static credentials.
   "AWS_PROFILE" "AWS_REGION" "AWS_DEFAULT_REGION"
   "AWS_ACCESS_KEY_ID" "AWS_SECRET_ACCESS_KEY" "AWS_SESSION_TOKEN"
   ;; GitHub CLI (gh) auth — gh reads either; GH_TOKEN wins.
   "GH_TOKEN" "GITHUB_TOKEN"])

(defn- env-name-suggestions
  "Var names from `.env.example` next to PG_WORKSPACE_ENV_FILE (the maintained
   template), falling back to the built-in list. Includes commented-out optional
   vars (e.g. the AWS static keys and gh tokens kept commented so a copied `.env`
   doesn't export them empty) — `#?` allows a leading comment marker. App-config
   vars (BY_*) are filtered out; only provider/credential names are suggested."
  []
  (or (when-let [ef (System/getenv "PG_WORKSPACE_ENV_FILE")]
        (let [ex (io/file (.getParentFile (.getCanonicalFile (io/file ef))) ".env.example")]
          (when (.canRead ex)
            (not-empty
             (->> (str/split-lines (slurp ex))
                  (keep #(second (re-find #"^#?\s*([A-Z][A-Z0-9_]*)=" %)))
                  (remove #(str/starts-with? % "BY_"))
                  distinct vec)))))
      builtin-env-names))

;; BYO env (settings). GET returns the owner's own values (editable form);
;; behind auth + same-origin. A hardening pass would mask/write-only these.
;; `:suggested` feeds the name dropdown (free-text still allowed).
(defn- get-env [req] (json 200 {:env       (sessions/get-env (user-id req))
                                :suggested (env-name-suggestions)}))

(defn- put-env [req]
  (let [env (sanitize-env (get (json-body req) "env"))]
    (sessions/set-env! (user-id req) (or env {}))
    (json 200 {:env (sessions/get-env (user-id req))})))

(defn- list-sessions   [req] (json 200 (sessions/list-for (user-id req))))
(defn- create-session  [req] (json 201 (sessions/create! (user-id req))))

(defn- get-session [req]
  (if-let [s (sessions/get-for (user-id req) (-> req :path-params :id))]
    (json 200 s)
    (json 404 {:error "not found"})))

(defn- session-ports
  "Dev-port mappings (3000-3010 -> host) for the owned session, for the
   workspace header's port dropdown."
  [req]
  (let [uid (user-id req) id (-> req :path-params :id)]
    (if (sessions/get-for uid id)
      (json 200 {:ports (or (sessions/ports uid id) [])})
      (json 404 {:error "not found"}))))

(defn- brainyard-sessions
  "Live brainyard sessions inside the owned workspace container, for the config
   view's session picker."
  [req]
  (let [uid (user-id req) id (-> req :path-params :id)]
    (if-let [rows (sessions/brainyard-sessions uid id)]
      (json 200 {:sessions rows})
      (json 404 {:error "not found"}))))

(defn- brainyard-session-config
  "Effective configuration of one brainyard session inside the owned workspace,
   read over its ask channel (read-only). `?query=` narrows to matching keys."
  [req]
  (let [uid   (user-id req)
        id    (-> req :path-params :id)
        sid   (-> req :path-params :sid)
        query (get-in req [:query-params "query"])]
    (if-let [cfg (sessions/brainyard-session-config uid id sid query)]
      (json 200 cfg)
      (json 404 {:error "not found"}))))

(defn- brainyard-graph
  "Context-graph memory (nodes + edges + counts) of the owned workspace, read
   over `by memory graph --json`. Whole-DB / user-scoped (not per session)."
  [req]
  (let [uid (user-id req) id (-> req :path-params :id)]
    (if-let [g (sessions/brainyard-graph uid id)]
      (json 200 g)
      (json 404 {:error "not found"}))))

;; --- user-scoped memory DB reads (Phase 3) ---------------------------------
;; Each shells a `by memory <verb> … --json` read into the owned workspace.
;; `nil` (not owned) → 404; a running-but-empty/off store returns 200 with the
;; CLI's own JSON payload (the SPA reads :success / :error from it).

(defn- qp [req k] (some-> (get-in req [:query-params k]) str/trim not-empty))

(defn- qp-int [req k]
  (when-let [s (qp req k)] (parse-long s)))

(defn- memory-status [req]
  (let [uid (user-id req) id (-> req :path-params :id)]
    (if-let [r (sessions/memory-status uid id)]
      (json 200 r)
      (json 404 {:error "not found"}))))

(defn- memory-list [req]
  (let [uid (user-id req) id (-> req :path-params :id)]
    (if-let [r (sessions/memory-list uid id {:layer   (qp req "layer")
                                             :session (qp req "session")
                                             :kind    (qp req "kind")
                                             :limit   (qp-int req "limit")})]
      (json 200 r)
      (json 404 {:error "not found"}))))

(defn- memory-search [req]
  (let [uid (user-id req) id (-> req :path-params :id)]
    (if-let [r (sessions/memory-search uid id (or (qp req "q") "")
                                       {:session (qp req "session")
                                        :limit   (qp-int req "limit")})]
      (json 200 r)
      (json 404 {:error "not found"}))))

(defn- memory-explain [req]
  (let [uid (user-id req) id (-> req :path-params :id)]
    (if-let [r (sessions/memory-explain uid id {:session (qp req "session")
                                                :turn    (qp-int req "turn")})]
      (json 200 r)
      (json 404 {:error "not found"}))))

(defn- resume-session [req]
  (if-let [s (sessions/resume! (user-id req) (-> req :path-params :id))]
    (json 200 s)
    (json 404 {:error "not found"})))

(defn- destroy-session [req]
  (sessions/destroy! (user-id req) (-> req :path-params :id))
  {:status 204})

;; --- terminal: serve the container's OWN ttyd client, same-origin -----------
;; The workspace embeds `/api/sessions/:id/term/` in an iframe. ttyd's client
;; (a self-contained page) then derives its endpoints from the URL it loaded at:
;;   tokenUrl = <path>/token   wsUrl = <path>/ws
;; so we proxy those three to the container ttyd, injecting basic auth. Using
;; ttyd's real client means the TUI renders exactly as ttyd intends.

(defn- term-down-page
  "Shown in the iframe when the workspace has no running container."
  [id]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (str "<!doctype html><meta charset=utf-8>"
                 "<body style='margin:0;font:14px ui-sans-serif,system-ui;"
                 "background:#0b0e14;color:#c9d1d9;padding:2rem'>"
                 "<h3 style='margin:0 0 .5rem'>Workspace not running</h3>"
                 "<p style='color:#8b949e'>This workspace (<code>" id "</code>) has no "
                 "running container. Go back to <b>← Workspaces</b> and Resume it, "
                 "or create a new one.</p></body>")})

(defn- term-resource
  "Proxy a ttyd HTTP resource (the page or /token) for the owned session. If the
   container is gone/unreachable, mark the session down and show a friendly page
   instead of a 500."
  [req ttyd-path]
  (let [uid (user-id req) id (-> req :path-params :id)]
    (if-let [up (sessions/upstream uid id)]
      (try (proxy/http-proxy up ttyd-path)
           (catch Exception _
             (sessions/mark-down! uid id)
             (term-down-page id)))
      (term-down-page id))))

(defn- term-page  [req] (term-resource req "/"))
(defn- term-token [req] (term-resource req "/token"))

(defn- term-ws [req]
  (let [id (-> req :path-params :id)]
    (if-let [up (sessions/upstream (user-id req) id)]
      ;; Track connect/disconnect so the idle reaper knows when nobody's watching.
      ((proxy/handler up {:on-open  #(sessions/client-connected! id)
                          :on-close #(sessions/client-disconnected! id)}) req)
      ((tty/handler id) req))))   ; fake mode (no container) -> echo stub

;; --- middleware ------------------------------------------------------------

(defn- wrap-require-auth [handler]
  (fn [req]
    (if (auth/identity-of req)
      (handler req)
      (json 401 {:error "unauthenticated"}))))

;; --- router ----------------------------------------------------------------

(def router
  (ring/router
   [["/api"
     ["/me"                      {:get me}]
     ["/me/env"                  {:get (wrap-require-auth get-env)
                                  :put (wrap-require-auth put-env)}]
     ["/sessions"                {:get  (wrap-require-auth list-sessions)
                                  :post (wrap-require-auth create-session)}]
     ["/sessions/:id"            {:get    (wrap-require-auth get-session)
                                  :delete (wrap-require-auth destroy-session)}]
     ["/sessions/:id/resume"     {:post (wrap-require-auth resume-session)}]
     ["/sessions/:id/ports"      {:get (wrap-require-auth session-ports)}]
     ["/sessions/:id/brainyard"  {:get (wrap-require-auth brainyard-sessions)}]
     ["/sessions/:id/graph"      {:get (wrap-require-auth brainyard-graph)}]
     ["/sessions/:id/memory"         {:get (wrap-require-auth memory-status)}]
     ["/sessions/:id/memory/list"    {:get (wrap-require-auth memory-list)}]
     ["/sessions/:id/memory/search"  {:get (wrap-require-auth memory-search)}]
     ["/sessions/:id/memory/explain" {:get (wrap-require-auth memory-explain)}]
     ["/sessions/:id/brainyard/:sid/config"
      {:get (wrap-require-auth brainyard-session-config)}]
     ;; ttyd's own client, proxied same-origin (workspace iframe)
     ["/sessions/:id/term"       {:get (wrap-require-auth term-page)}]
     ["/sessions/:id/term/"      {:get (wrap-require-auth term-page)}]
     ["/sessions/:id/term/token" {:get (wrap-require-auth term-token)}]
     ["/sessions/:id/term/ws"    {:get (wrap-require-auth term-ws)}]]
    ["/auth"
     ["/login"    {:get auth/login}]
     ["/callback" {:get auth/callback}]
     ["/logout"   {:get auth/logout}]]]))

(defn- index-html [_]
  (if-let [r (io/resource "public/index.html")]
    {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body (slurp r)}
    {:status 404 :headers {"Content-Type" "text/plain"}
     :body "playground-ui not built. Run `npm run release` in frontend/playground-ui."}))

(defn- wrap-no-cache
  "Always revalidate. The SPA bundle has a fixed name (`main.js`), so without
   this browsers heuristically serve a stale bundle after a rebuild/redeploy."
  [handler]
  (fn [req]
    (some-> (handler req)
            (update :headers assoc "Cache-Control" "no-cache"))))

(def app
  "Top handler: API/auth routes, then static SPA assets, then index.html
   fallback so client-side routes (e.g. /workspace/:id) deep-link correctly."
  (ring/ring-handler
   router
   (ring/routes
    (ring/create-resource-handler {:path "/" :root "public"})
    index-html)
   {:middleware [wrap-cookies wrap-params wrap-no-cache]}))
