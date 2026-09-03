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

   Binds the LOOPBACK address only (see `loopback`) — not reachable off-box.

   Endpoints (issuer = http://127.0.0.1:<port>):
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
   :authorization_endpoint         (str base "/authorize")
   :registration_endpoint          (str base "/register")
   :grant_types_supported          ["urn:ietf:params:oauth:grant-type:device_code"
                                    "authorization_code" "refresh_token"]})

(defn- handle-register [ex]
  ;; RFC 7591 — accept any public-client request, mint a client_id.
  (json! ex 201 {:client_id                  (str "dyn-" (rand-hex 12))
                 :token_endpoint_auth_method "none"
                 :grant_types                ["urn:ietf:params:oauth:grant-type:device_code" "refresh_token"]}))

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

(defn- handle-authorize
  "Authorization-code endpoint. Issues a code; for the out-of-band redirect
   (our headless paste flow) it shows the code; for a real redirect_uri it 302s
   back with ?code=&state=."
  [state ex]
  (let [q        (query-params ex)
        code     (rand-hex 24)
        redirect (q "redirect_uri")]
    (swap! state assoc-in [:auth-codes code] true)
    (if (and redirect (str/starts-with? redirect "http"))
      (do (.set (.getResponseHeaders ex) "Location"
                (str redirect "?code=" code "&state=" (q "state")))
          (respond! ex 302 "text/plain" ""))
      (html! ex 200 (str "<html><body style='font-family:sans-serif'>"
                         "<h2>Authorization code</h2>"
                         "<p>Copy this code back into your terminal:</p>"
                         "<pre style='font-size:1.4em'>" code "</pre></body></html>")))))

(defn- handle-token [state ex]
  (let [form (form-params ex)]
    (case (form "grant_type")
      "urn:ietf:params:oauth:grant-type:device_code"
      (let [dev (get-in @state [:devices (form "device_code")])]
        (cond
          (nil? dev)             (json! ex 400 {:error "invalid_grant"})
          (not (:approved? dev)) (json! ex 400 {:error "authorization_pending"})
          :else                  (json! ex 200 (issue-tokens! state))))

      "authorization_code"
      (if (get-in @state [:auth-codes (form "code")])
        (do (swap! state update :auth-codes dissoc (form "code"))
            (json! ex 200 (issue-tokens! state)))
        (json! ex 400 {:error "invalid_grant"}))

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
        "/device"    (handle-device state base ex)
        "/register"  (handle-register ex)
        "/authorize" (handle-authorize state ex)
        "/token"   (handle-token state ex)
        "/approve" (handle-approve state ex ((form-params ex) "code"))
        "/mcp"     (handle-mcp state ex)
        "/"        (handle-approve state ex ((query-params ex) "code"))
        (json! ex 404 {:error "not_found" :path path})))
    (catch Throwable t
      (try (json! ex 500 {:error "server_error" :message (.getMessage t)}) (catch Throwable _ nil)))
    (finally (.close ex))))

(defn- probe-serving
  "One discovery probe. Returns `:ok`, or a SHORT STRING naming why it failed —
   never throws.

   Naming the reason is the point. The previous version collapsed every failure
   into `false`, so a startup that gave up could only ever report \"never
   started serving\" after the whole budget had elapsed — with no way to tell a
   connection refused from a read timeout from an HTTP 500, which are three
   different bugs. That is a poor trade in a helper whose entire job is to
   diagnose a race.

   The connection is consumed and disconnected in a `finally`: an
   HttpURLConnection whose response body is never read holds its socket out of
   the keep-alive cache, and this runs in a retry loop."
  [^String url]
  (let [c ^java.net.HttpURLConnection
        (.openConnection (java.net.URL. (str url "/.well-known/openid-configuration")))]
    (try
      (doto c (.setConnectTimeout 500) (.setReadTimeout 500))
      (let [code (.getResponseCode c)]
        (if (= 200 code) :ok (str "HTTP " code)))
      (catch Exception e
        (str (.getSimpleName (class e))
             (when-let [m (ex-message e)] (str ": " m))))
      (finally
        (try (some-> (.getInputStream c) .close) (catch Exception _ nil))
        (try (.disconnect c) (catch Exception _ nil))))))

(defn- await-serving!
  "Block until the server actually ANSWERS its discovery endpoint, or give up.

   `.start` returns before the dispatch thread is necessarily serving, so a
   caller that proceeds immediately can issue discovery into a server that is
   not answering yet. On an idle machine that window is invisible; under a full
   `bb test` it is not, and the failure reads as
   `OAuth discovery failed: no well-known document at issuer` — which looks
   like a bug in discovery rather than a test that started too early.

   The budget is a CEILING, not a wait: this returns the moment the server
   answers, so a healthy start pays a millisecond or two.

   Returns nil when serving, or a DIAGNOSIS map when it gave up: which reasons
   were seen and how often, how many probes were made, how long it waited, and
   the JVM's live thread count. The thread count is there because this ns runs
   257th of 257 namespaces under `bb test`, sharing one JVM with everything
   before it — so \"the machine was exhausted\" is a real candidate and was
   previously indistinguishable from \"the server is broken\"."
  [^String url]
  (let [t0       (System/currentTimeMillis)
        deadline (+ t0 15000)]
    (loop [attempts 1
           reasons  {}]
      (let [r (probe-serving url)]
        (cond
          (= :ok r) nil

          (> (System/currentTimeMillis) deadline)
          {:attempts     attempts
           :waited-ms    (- (System/currentTimeMillis) t0)
           :reasons      (update reasons r (fnil inc 0))
           :live-threads (Thread/activeCount)}

          :else (do (Thread/sleep 20)
                    (recur (inc attempts) (update reasons r (fnil inc 0)))))))))

(def ^:const loopback
  "The address this provider binds, and the host every URL it hands out uses.

   Binding the LOOPBACK ADDRESS EXPLICITLY rather than the wildcard is what
   makes a port collision impossible, and this ns was the only server in the
   workspace getting it wrong — every other test server already binds
   \"127.0.0.1\".

   A wildcard bind does NOT conflict with a loopback bind on the same port: the
   OS considers them different sockets and lets both exist. So when the OS
   handed `InetSocketAddress(0)` a port that some earlier test's leaked
   loopback-bound server still held, `create` succeeded silently — and every
   client reaching us through `localhost` (which resolves 127.0.0.1 FIRST) was
   routed to THAT server instead. Measured: the startup probe got 618
   consecutive HTTP 401s from an unrelated server and timed out after 15s,
   reported as \"test OAuth server never started serving\" — a provider that
   was in fact serving perfectly, on a socket nothing was talking to.

   With a loopback bind the OS will never hand out a port already bound on that
   address, and a genuine collision (a fixed `port` argument) throws
   BindException immediately instead of misrouting every request.

   Using the literal IP for the URL, not the name \"localhost\", also keeps
   resolution and dual-stack ordering out of the picture entirely."
  "127.0.0.1")

(defn start!
  "Start the provider. `port` 0 lets the OS pick. Returns
   `{:server :port :base-url :state :approve! :stop!}`.

   Binds the loopback address only — see `loopback`.

   Does not return until the server is actually answering — see
   `await-serving!`."
  ([] (start! 0))
  ([port]
   (let [state  (atom {:devices {} :valid-tokens #{} :refresh {}})
         base   (atom nil)
         server (HttpServer/create (InetSocketAddress. ^String loopback (int port)) 0)
         ;; A real (small, daemon) pool rather than `nil`. The default executor
         ;; handles requests SERIALLY on the dispatch thread, so under load a
         ;; slow handler delays every other request behind it — long enough for
         ;; a client's discovery timeout to expire. The device flow polls the
         ;; token endpoint while other requests are in flight, so serial
         ;; handling is not merely slower here, it is a source of timeouts.
         pool   (java.util.concurrent.Executors/newFixedThreadPool
                 4
                 (reify java.util.concurrent.ThreadFactory
                   (newThread [_ r]
                     (doto (Thread. r "oauth-test-server")
                       (.setDaemon true)))))]
     (.createContext server "/" (reify HttpHandler (handle [_ ex] (dispatch state base ex))))
     (.setExecutor server pool)
     (.start server)
     (let [actual (.getPort (.getAddress server))
           url    (str "http://" loopback ":" actual)]
       (reset! base url)
       (when-let [why (await-serving! url)]
         (throw (ex-info (str "test OAuth server never started serving — "
                              (pr-str (:reasons why)))
                         (assoc why :url url :port actual))))
       {:server   server
        :port     actual
        :base-url url
        :state    state
        :approve! (fn [user-code] (approve! state user-code))
        :stop!    (fn [] (.stop server 0) (.shutdownNow pool))}))))

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
