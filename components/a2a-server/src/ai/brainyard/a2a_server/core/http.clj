;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-server.core.http
  "The A2A HTTP listener, on `com.sun.net.httpserver`.

   ## Why the JDK server

   Zero new dependencies, already inside the native image via the JDK, and
   a minimal reflection surface — so `by a2a serve` ships in the binary
   instead of being exiled to a JVM-only base. It is enough for JSON-RPC
   POST plus SSE, which is the whole requirement. http-kit would add
   megabytes and native-image reflection risk for capabilities (WebSocket,
   async) A2A does not use.

   ## Security posture

   Inbound A2A is remote code execution against the local workspace, by
   design: a caller sends a prompt and a local agent runs it with tools and
   disk access. Containment is therefore explicit, not implied.

   - **A token is REQUIRED to start.** No token, no listener — `start!`
     refuses rather than binding unauthenticated. There is no 'dev mode'
     escape hatch, because that is the flag that ends up set in production.
   - **Loopback by default** (the caller supplies the host; the config
     default is `127.0.0.1`).
   - **The well-known card is the ONLY unauthenticated route.** The spec
     requires it to be public, and it deliberately carries no secrets.
   - Auth compares with a **constant-time** check, so the token cannot be
     recovered a byte at a time by timing.

   See docs/design/a2a-design.md §8."
  (:require [clojure.string :as str]
            [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-server.core.handlers :as handlers]
            [ai.brainyard.a2a-server.core.sse :as sse]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io OutputStream]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util.concurrent Executors]))

;; =============================================================================
;; Routes
;; =============================================================================

(def ^:const RPC_PATH "/a2a")
(def ^:const EXTENDED_CARD_PATH "/a2a/agent-card")

;; =============================================================================
;; Auth
;; =============================================================================

(defn- constant-time=
  "Compare two strings without leaking their common prefix length through
   timing. `MessageDigest/isEqual` is the JDK's constant-time comparator."
  [^String a ^String b]
  (and (some? a) (some? b)
       (MessageDigest/isEqual (.getBytes a StandardCharsets/UTF_8)
                              (.getBytes b StandardCharsets/UTF_8))))

(defn- bearer-token
  "The bearer token from an Authorization header, or nil."
  [^HttpExchange ex]
  (let [h (.getFirst (.getRequestHeaders ex) "Authorization")]
    (when (and h (str/starts-with? h "Bearer "))
      (subs h 7))))

(defn authorized?
  [^HttpExchange ex expected-token]
  (constant-time= (bearer-token ex) expected-token))

;; =============================================================================
;; Response helpers
;; =============================================================================

(defn- respond!
  [^HttpExchange ex status ^String body & {:keys [content-type]}]
  (let [bs (.getBytes (or body "") StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders ex) "Content-Type"
          (or content-type "application/json"))
    (.sendResponseHeaders ex (int status) (alength bs))
    (with-open [^OutputStream os (.getResponseBody ex)]
      (.write os bs))))

(defn- respond-json! [ex status m]
  (respond! ex status (a2a/encode m)))

(defn- unauthorized!
  "401 with the scheme, as the spec requires for an authentication failure."
  [^HttpExchange ex]
  (.set (.getResponseHeaders ex) "WWW-Authenticate" "Bearer")
  (respond! ex 401 "{\"error\":\"unauthorized\"}"))

(defn- read-body ^String [^HttpExchange ex]
  (slurp (.getRequestBody ex) :encoding "UTF-8"))

;; =============================================================================
;; SSE response
;; =============================================================================

(defn- start-sse!
  "Send SSE response headers and return a `write!` fn.

   Content-Length 0 selects chunked encoding, which is what lets us keep
   writing frames after the headers are out."
  [^HttpExchange ex]
  (doto (.getResponseHeaders ex)
    (.set "Content-Type" "text/event-stream")
    (.set "Cache-Control" "no-cache")
    (.set "Connection" "keep-alive"))
  (.sendResponseHeaders ex 200 0)
  (let [^OutputStream os (.getResponseBody ex)]
    (fn [^String s]
      (.write os (.getBytes s StandardCharsets/UTF_8))
      (.flush os))))

;; =============================================================================
;; Handlers
;; =============================================================================

(defn- card-handler
  "`GET /.well-known/agent-card.json` — public, unauthenticated, per spec."
  [{:keys [card-fn]}]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (if (= "GET" (.getRequestMethod ^HttpExchange ex))
          (respond-json! ex 200 (card-fn))
          (respond! ex 405 "{\"error\":\"method not allowed\"}"))
        (catch Throwable t
          (mulog/error ::card-handler-failed :exception t)
          (try (respond! ex 500 "{\"error\":\"internal\"}") (catch Throwable _ nil)))
        (finally (.close ^HttpExchange ex))))))

(defn- extended-card-handler
  [{:keys [auth-token] :as service}]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (if-not (authorized? ex auth-token)
          (unauthorized! ex)
          (respond-json! ex 200 (:result (handlers/handle-extended-card service 1 {}))))
        (catch Throwable t
          (mulog/error ::extended-card-failed :exception t)
          (try (respond! ex 500 "{\"error\":\"internal\"}") (catch Throwable _ nil)))
        (finally (.close ^HttpExchange ex))))))

(defn- rpc-handler
  "`POST /a2a` — the JSON-RPC surface, authenticated."
  [{:keys [auth-token] :as service}]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (cond
          (not= "POST" (.getRequestMethod ^HttpExchange ex))
          (respond! ex 405 "{\"error\":\"method not allowed\"}")

          (not (authorized? ex auth-token))
          (unauthorized! ex)

          :else
          (let [body (read-body ex)
                msg  (try (a2a/decode body) (catch Throwable _ ::bad-json))]
            (cond
              (= ::bad-json msg)
              (respond-json! ex 200 (a2a/error->jsonrpc nil :parse-error))

              (not (a2a/request? msg))
              (respond-json! ex 200 (a2a/error->jsonrpc (:id msg) :invalid-request))

              :else
              ;; Resolve the wire dialect BEFORE routing: it decides both
              ;; the method vocabulary and how the reply is encoded. The
              ;; A2A-Version header is the spec's answer, but the method
              ;; name is unambiguous on its own — see
              ;; `handlers/resolve-dialect`.
              (let [version (.getFirst (.getRequestHeaders ^HttpExchange ex)
                                       a2a/VERSION_HEADER)]
                (if-let [[dialect method-kw] (handlers/resolve-dialect version (:method msg))]
                  (let [svc (assoc service :dialect dialect)]
                    (if (contains? #{:message-stream :tasks-resubscribe} method-kw)
                      (let [write! (start-sse! ex)
                            params (a2a/decode-send-params dialect (or (:params msg) {}))]
                        (if (= :tasks-resubscribe method-kw)
                          (sse/resubscribe! svc (:id msg) params write!)
                          (sse/stream-turn! svc (:id msg) params write!)))
                      (respond-json! ex 200 (handlers/dispatch service msg dialect))))
                  (respond-json! ex 200 (a2a/error->jsonrpc
                                         (:id msg) :method-not-found
                                         {:detail (str (:method msg))})))))))
        (catch Throwable t
          (mulog/error ::rpc-handler-failed :exception t)
          ;; Never leak a stack trace to a remote caller.
          (try (respond! ex 500 "{\"error\":\"internal\"}") (catch Throwable _ nil)))
        (finally (.close ^HttpExchange ex))))))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn validate-service
  "Vector of problems with a service map (empty == usable)."
  [{:keys [card-fn ask-fn auth-token]}]
  (cond-> []
    (not (fn? card-fn))            (conj ":card-fn is required and must be a function")
    (not (fn? ask-fn))             (conj ":ask-fn is required and must be a function")
    (str/blank? (str auth-token))  (conj (str ":auth-token is required — the server refuses to bind "
                                              "unauthenticated. Set :a2a-serve-token "
                                              "(env BY_A2A_SERVE_TOKEN)."))))

(defn start!
  "Start the A2A server. Returns `{:server … :host … :port … :url …}` or
   `{:error …}`.

   `:port 0` binds an ephemeral port; the assigned one comes back in
   `:port`, which is what the tests use.

   The thread pool is small and its threads are DAEMONS: a serving process
   must still be able to exit."
  [service {:keys [host port threads] :or {host "127.0.0.1" port 41241 threads 4}}]
  (let [problems (validate-service service)]
    (if (seq problems)
      {:error (str "cannot start A2A server: " (str/join "; " problems))}
      (try
        (let [server (HttpServer/create (InetSocketAddress. ^String host (int port)) 0)
              bound  (.getPort (.getAddress server))]
          (.createContext server a2a/AGENT_CARD_PATH (card-handler service))
          (.createContext server EXTENDED_CARD_PATH (extended-card-handler service))
          (.createContext server RPC_PATH (rpc-handler service))
          (.setExecutor server (Executors/newFixedThreadPool
                                (int threads)
                                (reify java.util.concurrent.ThreadFactory
                                  (newThread [_ r]
                                    (doto (Thread. ^Runnable r "a2a-server")
                                      (.setDaemon true))))))
          (.start server)
          (mulog/info ::server-started :host host :port bound
                      :node-id (a2a/node-id))
          {:server server
           :host   host
           :port   bound
           :url    (str "http://" host ":" bound)
           :card-url (str "http://" host ":" bound a2a/AGENT_CARD_PATH)})
        (catch Throwable t
          {:error (str "failed to bind " host ":" port " — " (ex-message t))})))))

(defn stop!
  "Stop a running server. Idempotent."
  [{:keys [server]}]
  (when server
    (.stop ^HttpServer server 0)
    (mulog/info ::server-stopped)
    true))
