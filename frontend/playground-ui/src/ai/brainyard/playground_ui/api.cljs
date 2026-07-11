;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
(ns ai.brainyard.playground-ui.api
  "Thin wrappers over the playground-server control-plane REST API. Every call
   sends the httpOnly session cookie (`:credentials \"include\"`) and returns a
   JS Promise resolving to keywordized Clojure data (or rejecting with
   {:status n})."
  (:require [clojure.string :as str]))

(defn- ->clj [x]
  (js->clj x :keywordize-keys true))

(defn request
  "method: \"GET\"|\"POST\"|\"DELETE\"; body: clj map or nil. → Promise<clj|nil>."
  [method url body]
  (let [opts (cond-> {:method      method
                      :credentials "include"
                      :headers     {"Accept" "application/json"}}
               body (-> (assoc-in [:headers "Content-Type"] "application/json")
                        (assoc :body (js/JSON.stringify (clj->js body)))))]
    (-> (js/fetch url (clj->js opts))
        (.then (fn [res]
                 (cond
                   (= 204 (.-status res)) (js/Promise.resolve nil)
                   (.-ok res)             (.then (.json res) ->clj)
                   :else (js/Promise.reject #js {:status (.-status res)})))))))

(defn get-json [url]        (request "GET" url nil))

;; --- Endpoints (mirror §5.3 of the design doc) -----------------------------
(defn me              []   (get-json "/api/me"))
(defn list-sessions   []   (get-json "/api/sessions"))
(defn get-session     [id] (get-json (str "/api/sessions/" id)))
(defn create-session! []   (request "POST"   "/api/sessions" {}))
(defn resume!         [id] (request "POST"   (str "/api/sessions/" id "/resume") {}))
(defn destroy!        [id] (request "DELETE" (str "/api/sessions/" id) nil))
(defn ports           [id] (get-json (str "/api/sessions/" id "/ports")))

;; Per-workspace brainyard-session config view.
;; brainyard-sessions -> {:sessions [{:session-id :project-dir :model …} …]}
(defn brainyard-sessions [id] (get-json (str "/api/sessions/" id "/brainyard")))
(defn session-config
  "Effective config of brainyard session `sid` in workspace `id`. Optional
   `query` narrows to matching config keys."
  ([id sid] (session-config id sid nil))
  ([id sid query]
   (get-json (str "/api/sessions/" id "/brainyard/" sid "/config"
                  (when (seq query) (str "?query=" (js/encodeURIComponent query)))))))

;; Context-graph memory dump for a workspace (nodes + edges + counts).
;; -> {:success :enabled? :nodes [...] :edges [...] :counts {:nodes :edges}}
(defn graph [id] (get-json (str "/api/sessions/" id "/graph")))

;; --- user-scoped memory DB reads (L1/L2/L3) --------------------------------
(defn- query-string
  "Encode a {k v} map as `?k=v&…`, dropping blank/nil values. → \"\" when empty."
  [params]
  (let [pairs (for [[k v] params
                    :when (and (some? v) (not= "" (str v)))]
                (str (name k) "=" (js/encodeURIComponent (str v))))]
    (if (seq pairs) (str "?" (str/join "&" pairs)) "")))

;; -> {:success :stats {:episodes :semantic-facts …} :vec-status {…} :graph-enabled?}
(defn memory-status [id] (get-json (str "/api/sessions/" id "/memory")))

;; opts {:layer :session :kind :limit} -> {:success :layer :count :entries [...]}
(defn memory-list [id opts]
  (get-json (str "/api/sessions/" id "/memory/list" (query-string opts))))

;; -> {:success :query :count :entries [{:_layer :content …} …]}
(defn memory-search [id q opts]
  (get-json (str "/api/sessions/" id "/memory/search"
                 (query-string (assoc opts :q q)))))

;; opts {:session :turn} -> {:success :explain {…}}
(defn memory-explain [id opts]
  (get-json (str "/api/sessions/" id "/memory/explain" (query-string opts))))

;; BYO env (settings). env is a {name -> value} map.
(defn get-env         []    (get-json "/api/me/env"))
(defn put-env         [env] (request "PUT" "/api/me/env" {:env env}))

;; The terminal is ttyd's own client embedded via an iframe at
;; /api/sessions/:id/term/ — it handles its own WS/token handshake, so the SPA
;; no longer mints a tty token.
