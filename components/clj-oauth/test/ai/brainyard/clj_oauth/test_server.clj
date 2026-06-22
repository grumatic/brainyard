;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.test-server
  "Brainyard test OAuth 2.0 Device Authorization Grant (RFC 8628) provider, plus
   a minimal OAuth-gated MCP endpoint — a real target to exercise the whole
   clj-oauth + MCP stack end to end (Anthropic's consumer OAuth is Claude-Code-
   only, so it can't serve as one).

   TEST/DEMO ONLY. Lives in test sources — never compiled into the uberjar or
   native binary. Built on the JDK `com.sun.net.httpserver.HttpServer`, so it
   needs no HTTP-server dependency.

   Endpoints (issuer = http://localhost:<port>):
     GET  /.well-known/openid-configuration  → discovery (advertises device flow)
     POST /device                            → { device_code, user_code, … }
     GET  /                  ?code=<user>    → approve a code (one-click / form)
     POST /approve           code=<user>     → approve a code (programmatic)
     POST /token                             → device_code & refresh_token grants
     POST /mcp                               → JSON-RPC MCP; 401 without a bearer

   Programmatic: `(start! 0)` → `{:base-url :approve! :stop! …}` (port 0 = OS
   picks one). Launcher: `bb oauth:test-server [port]` (default 7900)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress URLDecoder]))

;; ---------------------------------------------------------------------------
;; Tiny request/response helpers
;; ---------------------------------------------------------------------------

(defn- req-body [^HttpExchange ex] (slurp (.getRequestBody ex)))

(defn- parse-pairs [s]
  (into {} (for [pair (str/split (or s "") #"&") :when (seq pair)]
             (let [[k v] (str/split pair #"=" 2)]
               [(URLDecoder/decode k "UTF-8") (URLDecoder/decode (or v "") "UTF-8")]))))

(defn- form-params [^HttpExchange ex] (parse-pairs (req-body ex)))
(defn- query-params [^HttpExchange ex] (parse-pairs (.getQuery (.getRequestURI ex))))

(defn- respond! [^HttpExchange ex status ctype ^String body]
  (let [bs (.getBytes body "UTF-8")]
    (.set (.getResponseHeaders ex) "Content-Type" ctype)
    (.sendResponseHeaders ex status (alength bs))
    (with-open [os (.getResponseBody ex)] (.write os bs))))

(defn- json! [ex status m] (respond! ex status "application/json" (json/write-str m)))
(defn- html! [ex status h] (respond! ex status "text/html; charset=utf-8" h))

;; ---------------------------------------------------------------------------
;; Random codes
;; ---------------------------------------------------------------------------

(defn- rand-hex [n] (apply str (repeatedly n #(rand-nth "0123456789abcdef"))))
(defn- gen-user-code []
  (str "BRNY-" (apply str (repeatedly 4 #(rand-nth (seq "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"))))))

(defn- approve! [state user-code]
  (swap! state update :devices
         (fn [devs] (reduce-kv (fn [m dc d]
                                 (assoc m dc (cond-> d (= (:user-code d) user-code) (assoc :approved? true))))
                               {} devs))))

;; ---------------------------------------------------------------------------
;; Endpoint handlers
;; ---------------------------------------------------------------------------

(defn- discovery [base]
  {:issuer                         base
   :device_authorization_endpoint  (str base "/device")
   :token_endpoint                 (str base "/token")
   :authorization_endpoint         (str base "/")
   :grant_types_supported          ["urn:ietf:params:oauth:grant-type:device_code" "refresh_token"]})

(defn- handle-device [state base ex]
  (let [form (form-params ex)
        dc   (rand-hex 32)
        uc   (gen-user-code)]
    (swap! state assoc-in [:devices dc]
           {:user-code uc :approved? false :client-id (form "client_id") :scope (form "scope")})
    (json! ex 200 {:device_code               dc
                   :user_code                 uc
                   :verification_uri          (str base "/")
                   :verification_uri_complete (str base "/?code=" uc)
                   :expires_in                300
                   :interval                  1})))

(defn- issue-tokens! [state]
  (let [at (rand-hex 40) rt (rand-hex 40)]
    (swap! state #(-> % (update :valid-tokens (fnil conj #{}) at)
                      (assoc-in [:refresh rt] true)))
    {:access_token at :refresh_token rt :token_type "Bearer" :expires_in 3600}))

(defn- handle-token [state ex]
  (let [form (form-params ex)]
    (case (form "grant_type")
      "urn:ietf:params:oauth:grant-type:device_code"
      (let [dev (get-in @state [:devices (form "device_code")])]
        (cond
          (nil? dev)             (json! ex 400 {:error "invalid_grant"})
          (not (:approved? dev)) (json! ex 400 {:error "authorization_pending"})
          :else                  (json! ex 200 (issue-tokens! state))))

      "refresh_token"
      (let [rt (form "refresh_token")]
        (if (get-in @state [:refresh rt])
          (do (swap! state update :refresh dissoc rt)   ; rotate
              (json! ex 200 (issue-tokens! state)))
          (json! ex 400 {:error "invalid_grant"})))

      (json! ex 400 {:error "unsupported_grant_type"}))))

(defn- handle-approve [state ex code]
  (if (seq code)
    (do (approve! state code)
        (html! ex 200 (str "<html><body style='font-family:sans-serif'>"
                           "<h2>&#10003; Approved <code>" code "</code></h2>"
                           "<p>Return to your terminal — authorization will complete shortly.</p>"
                           "</body></html>")))
    (html! ex 200 (str "<html><body style='font-family:sans-serif'>"
                       "<h2>Brainyard test OAuth</h2>"
                       "<form method='get' action='/'>"
                       "<label>Enter code: <input name='code' autofocus></label> "
                       "<button>Approve</button></form></body></html>"))))

(defn- bearer [^HttpExchange ex]
  (some->> (.getFirst (.getRequestHeaders ex) "Authorization")
           (re-matches #"Bearer (.+)") second))

(defn- handle-mcp [state ex]
  (let [token (bearer ex)]
    (if-not (contains? (:valid-tokens @state) token)
      (do (.set (.getResponseHeaders ex) "WWW-Authenticate" "Bearer realm=\"brainyard\"")
          (json! ex 401 {:error "unauthorized"}))
      (let [req    (try (json/read-str (req-body ex) :key-fn keyword) (catch Exception _ {}))
            id     (:id req)
            method (:method req)]
        (case method
          "initialize"
          (do (.set (.getResponseHeaders ex) "Mcp-Session-Id" "brainyard-test-session")
              (json! ex 200 {:jsonrpc "2.0" :id id
                             :result {:protocolVersion "2024-11-05"
                                      :capabilities {:tools {}}
                                      :serverInfo {:name "brainyard-test" :version "1.0.0"}}}))
          "notifications/initialized" (json! ex 200 {:jsonrpc "2.0"})
          "tools/list"
          (json! ex 200 {:jsonrpc "2.0" :id id
                         :result {:tools [{:name "echo"
                                           :description "Echo text back (brainyard test tool)"
                                           :inputSchema {:type "object"
                                                         :properties {:text {:type "string"}}
                                                         :required ["text"]}}]}})
          "tools/call"
          (json! ex 200 {:jsonrpc "2.0" :id id
                         :result {:content [{:type "text"
                                             :text (str "echo: " (get-in req [:params :arguments :text]))}]}})
          (json! ex 200 {:jsonrpc "2.0" :id id :result {}}))))))

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(defn- dispatch [state base-atom ^HttpExchange ex]
  (try
    (let [base (or @base-atom "")
          path (.getPath (.getRequestURI ex))]
      (case path
        "/.well-known/openid-configuration" (json! ex 200 (discovery base))
        "/device"  (handle-device state base ex)
        "/token"   (handle-token state ex)
        "/approve" (handle-approve state ex ((form-params ex) "code"))
        "/mcp"     (handle-mcp state ex)
        "/"        (handle-approve state ex ((query-params ex) "code"))
        (json! ex 404 {:error "not_found" :path path})))
    (catch Throwable t
      (try (json! ex 500 {:error "server_error" :message (.getMessage t)}) (catch Throwable _ nil)))
    (finally (.close ex))))

(defn start!
  "Start the provider. `port` 0 lets the OS pick. Returns
   `{:server :port :base-url :state :approve! :stop!}`."
  ([] (start! 0))
  ([port]
   (let [state  (atom {:devices {} :valid-tokens #{} :refresh {}})
         base   (atom nil)
         server (HttpServer/create (InetSocketAddress. (int port)) 0)]
     (.createContext server "/" (reify HttpHandler (handle [_ ex] (dispatch state base ex))))
     (.setExecutor server nil)
     (.start server)
     (let [actual (.getPort (.getAddress server))
           url    (str "http://localhost:" actual)]
       (reset! base url)
       {:server   server
        :port     actual
        :base-url url
        :state    state
        :approve! (fn [user-code] (approve! state user-code))
        :stop!    (fn [] (.stop server 0))}))))

(defn -main
  "Launcher for `bb oauth:test-server [port]` (default 7900). Blocks."
  [& args]
  (let [port (if (seq args) (Integer/parseInt (first args)) 7900)
        {:keys [base-url]} (start! port)]
    (println (str "\nBrainyard test OAuth + MCP provider — " base-url))
    (println "  discovery : " (str base-url "/.well-known/openid-configuration"))
    (println "  approve   : open the verification URL in a browser, or:")
    (println (str "              curl -s '" base-url "/?code=<USER_CODE>'"))
    (println "\nAdd to <project>/.brainyard/config.edn, then restart `by`:\n")
    (println (str "  {:mcp {:servers\n"
                  "         {\"brainyard\"\n"
                  "          {:transport :http\n"
                  "           :config {:url \"" base-url "/mcp\"\n"
                  "                    :auth {:type :oauth :issuer \"" base-url "\"\n"
                  "                           :client-id \"brainyard-test\" :flow :device}}\n"
                  "           :enabled true}}}}"))
    (println "\nIn the TUI:  /mcp brainyard auth   → a code box appears → approve it.")
    (println "Ctrl-C to stop.\n")
    @(promise)))
