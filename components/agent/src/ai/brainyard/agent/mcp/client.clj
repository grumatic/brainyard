;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.mcp.client
  "Model Context Protocol (MCP) client implementation for Brainyard agents.

   MCP is an open protocol that standardizes how applications provide context to LLMs.
   This client enables Brainyard agents to connect with MCP servers via JSON-RPC transport.

   Protocol Reference: https://modelcontextprotocol.io
   Specification: https://spec.modelcontextprotocol.io"
  (:require [ai.brainyard.mulog.interface :as mulog]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.clj-oauth.interface :as oauth])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.util.concurrent.atomic AtomicLong]))

;; =============================================================================
;; Validation Helper
;; =============================================================================

(defn- ensure!
  "Validate val with pred, returning val on success or throwing ExceptionInfo on failure.
   Drop-in replacement for taoensso.truss/have."
  ([pred val]
   (ensure! pred val (str "Validation failed: (" pred " " (pr-str val) ")")))
  ([pred val msg]
   (when-not (pred val)
     (throw (ex-info msg {:pred pred :val val})))
   val))

;; =============================================================================
;; MCP Protocol Constants and Types
;; =============================================================================

(def ^:const MCP_VERSION "2024-11-05")
(def ^:const JSON_RPC_VERSION "2.0")

;; MCP Message Types
(def message-types
  {:request "request"
   :response "response"
   :notification "notification"})

;; MCP Standard Methods
(def mcp-methods
  {:initialize "initialize"
   :initialized "notifications/initialized"
   :ping "ping"
   :list-resources "resources/list"
   :read-resource "resources/read"
   :list-tools "tools/list"
   :call-tool "tools/call"
   :list-prompts "prompts/list"
   :get-prompt "prompts/get"
   :complete "completion/complete"
   :set-level "logging/setLevel"
   ;; Notification methods
   :resources-list-changed "notifications/resources/list_changed"
   :tools-list-changed "notifications/tools/list_changed"
   :prompts-list-changed "notifications/prompts/list_changed"
   :notification-message "notifications/message"})

;; Transport Types
(def transport-types
  {:stdio "stdio"
   :http-sse "http+sse"})

;; =============================================================================
;; MCP Message Construction
;; =============================================================================

(defonce ^AtomicLong request-id-counter (AtomicLong. 0))

(defn next-request-id
  "Generate next unique request ID"
  []
  (.incrementAndGet request-id-counter))

(defn make-request
  "Create MCP JSON-RPC request message"
  [method params]
  (ensure! map? params "params must be a map")
  {:jsonrpc JSON_RPC_VERSION
   :id (next-request-id)
   :method method
   :params params})

(defn make-response
  "Create MCP JSON-RPC response message"
  [id result]
  {:jsonrpc JSON_RPC_VERSION
   :id id
   :result result})

(defn make-error-response
  "Create MCP JSON-RPC error response"
  [id error-code error-message & [error-data]]
  {:jsonrpc JSON_RPC_VERSION
   :id id
   :error (cond-> {:code error-code :message error-message}
            error-data (assoc :data error-data))})

(defn make-notification
  "Create MCP JSON-RPC notification message"
  [method params]
  {:jsonrpc JSON_RPC_VERSION
   :method method
   :params params})

(defn process-notification!
  "Process MCP server notifications (messages without id field)"
  [client notification]
  (let [method (:method notification)
        params (:params notification)]
    (mulog/debug ::processing-notification :method method :params params)

    (case method
      ;; Handle resource list changes
      "notifications/resources/list_changed"
      (mulog/info ::resources-changed :params params)

      ;; Handle tool list changes
      "notifications/tools/list_changed"
      (mulog/info ::tools-changed :params params)

      ;; Handle prompt list changes
      "notifications/prompts/list_changed"
      (mulog/info ::prompts-changed :params params)

      ;; Handle logging messages
      "notifications/message"
      (let [{:keys [level logger message data]} params]
        (case level
          "error" (mulog/error ::server-message :logger logger :message message :data data)
          "warn" (mulog/warn ::server-message :logger logger :message message :data data)
          "info" (mulog/info ::server-message :logger logger :message message :data data)
          "debug" (mulog/debug ::server-message :logger logger :message message :data data)
          (mulog/info ::server-message :params params)))

      ;; Handle unknown notifications
      (mulog/debug ::unknown-notification :method method :params params))))

;; =============================================================================
;; MCP Client Protocol Implementation
;; =============================================================================

(defprotocol MCPClient
  "Protocol for MCP client implementations"
  (connect! [client config] "Establish connection to MCP server")
  (disconnect! [client] "Close connection to MCP server")
  (send-request! [client method params] "Send request and await response")
  (send-notification! [client method params] "Send notification (no response expected)")
  (get-capabilities [client] "Get client capabilities")
  (get-server-info [client] "Get server information after initialization")
  (list-resources [client] "List available resources from server")
  (read-resource [client uri] "Read specific resource by URI")
  (list-tools [client] "List available tools from server")
  (call-tool [client name arguments] "Call specific tool with arguments")
  (list-prompts [client] "List available prompts from server")
  (get-prompt [client name arguments] "Get specific prompt with arguments"))

;; =============================================================================
;; STDIO Transport Implementation
;; =============================================================================

(defn- drain-stderr-lines!
  "Read every line from `rdr`, calling `(sink line)` per line, returning when the
   reader reaches EOF or is closed. Pure I/O loop, separated from the threading
   so it is unit-testable with a StringReader + collecting sink."
  [^java.io.BufferedReader rdr sink]
  (try
    (loop []
      (when-let [line (.readLine rdr)]
        (sink line)
        (recur)))
    (catch java.io.IOException _ nil)
    (catch Exception _ nil)))

(defn- start-stderr-drain!
  "Continuously drain a stdio MCP child's stderr to mulog on a daemon thread.
   Without this the child's stderr pipe (~64KB) can fill and wedge the process —
   e.g. mcp-remote during a slow OAuth flow — and its human-facing output (most
   importantly the authorization URL) would be invisible. URL-bearing lines are
   logged at info as ::mcp-stderr-url so the auth link is queryable via log$events.
   The thread exits when stderr is closed (disconnect! closes it). Returns the Thread."
  [server-name ^java.io.BufferedReader stderr]
  (doto (Thread.
         ^Runnable
         (fn []
           (drain-stderr-lines!
            stderr
            (fn [line]
              (if (re-find #"https?://\S+" line)
                (mulog/info  ::mcp-stderr-url :server server-name :line line)
                (mulog/debug ::mcp-stderr     :server server-name :line line)))))
         (str "mcp-stderr-drain-" (or server-name "mcp")))
    (.setDaemon true)
    (.start)))

(defrecord StdioMCPClient [process stdin stdout stderr server-info capabilities pending-requests]
  MCPClient

  (connect! [client config]
    (let [{:keys [command args working-dir env]} config
          ;; Bound the init handshake so a wedged or never-authorized stdio
          ;; server can't leak a blocked reader thread. Connects now run in the
          ;; background (see integration/load-mcp-servers!), so this can be
          ;; generous — OAuth bridges (mcp-remote) need time for the browser
          ;; flow. Overridable per server via :connect-timeout-ms.
          connect-timeout-ms (or (:connect-timeout-ms config) 120000)
          process-builder (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String (cons command args)))
          _ (when working-dir (.directory process-builder (io/file working-dir)))
          _ (when env
              (let [env-map (.environment process-builder)]
                (doseq [[k v] env]
                  (.put env-map (str k) (str v)))))
          proc (.start process-builder)
          stdin (OutputStreamWriter. (.getOutputStream proc))
          stdout (BufferedReader. (InputStreamReader. (.getInputStream proc)))
          stderr (BufferedReader. (InputStreamReader. (.getErrorStream proc)))]

      (mulog/info ::server-process-started :command command :args args)

      ;; Drain the child's stderr on a daemon thread so its pipe can't fill and
      ;; wedge the process (e.g. mcp-remote mid-OAuth), and so its auth URL /
      ;; progress is captured. Terminates when disconnect! closes stderr.
      (start-stderr-drain! (:name client) stderr)

      ;; Initialize connection
      (let [init-request (make-request (:initialize mcp-methods)
                                       {:protocolVersion MCP_VERSION
                                        :capabilities {:roots {:listChanged false}}
                                        :sampling {}
                                        :clientInfo {:name "Brainyard Agent"
                                                     :version "1.0.0"}})
            init-request-id (:id init-request)
            init-json (json/write-str init-request)]

        (.write stdin init-json)
        (.write stdin "\n")
        (.flush stdin)

        ;; Read the init response on a worker future, bounded by
        ;; connect-timeout-ms — `.readLine` can't be unparked by
        ;; future-cancel, but the timeout frees the caller and we destroy the
        ;; process so the orphaned read drains on EOF. Mirrors send-request!.
        (let [handshake
              (future
                (loop []
                  ;; Read initialization response
                  (let [response-line (.readLine stdout)]
                    (when-not response-line
                      (throw (ex-info "MCP server process closed during initialization"
                                      {:server-name (:name client)})))
                    (let [response (json/read-str response-line :key-fn keyword)]

                      (cond
                        ;; This is a notification (no id field) - skip it and continue
                        (nil? (:id response))
                        (do
                          (mulog/debug ::skipping-notification :method (:method response))
                          (process-notification! nil response)
                          (recur))

                        ;; This is a response but not for our request - skip it
                        (not= (:id response) init-request-id)
                        (do
                          (mulog/warn ::unexpected-response
                                      :expected-id init-request-id :received-id (:id response))
                          (recur))

                        ;; Error response from server
                        (:error response)
                        (throw (ex-info "MCP initialization failed" {:error (:error response)}))

                        :else
                        (do
                          (mulog/info ::server-initialized :result (:result response))
                          ;; Send initialized notification
                          (let [init-notif (make-notification (:initialized mcp-methods) {})]
                            (.write stdin (json/write-str init-notif))
                            (.write stdin "\n")
                            (.flush stdin))
                          ;; Update client state
                          (-> client
                              (assoc :process proc)
                              (assoc :stdin stdin)
                              (assoc :stdout stdout)
                              (assoc :stderr stderr)
                              (assoc :server-info (:result response))
                              (assoc :pending-requests (atom {})))))))))
              ;; Unwrap the future's ExecutionException so callers see the
              ;; original ex-info; destroy the process on any failure so a
              ;; half-started server doesn't linger.
              result (try (deref handshake connect-timeout-ms ::timeout)
                          (catch java.util.concurrent.ExecutionException e
                            (try (.destroy proc) (catch Exception _))
                            (throw (or (ex-cause e) e))))]
          (if (= result ::timeout)
            (do (future-cancel handshake)
                (try (.destroy proc) (catch Exception _))
                (throw (ex-info "MCP initialization timed out"
                                {:server-name (:name client)
                                 :command command
                                 :timeout-ms connect-timeout-ms})))
            result)))))

  (disconnect! [client]
    (when-let [^Process proc (:process client)]
      (mulog/info ::disconnecting-stdio-client)
      (try
        (.close ^java.io.Writer (:stdin client))
        (.close ^java.io.BufferedReader (:stdout client))
        (.close ^java.io.BufferedReader (:stderr client))
        (.destroy proc)
        (catch Exception e
          (mulog/warn ::disconnect-error :exception e)))
      (-> client
          (assoc :process nil)
          (assoc :stdin nil)
          (assoc :stdout nil)
          (assoc :stderr nil)
          (assoc :server-info nil)
          (assoc :pending-requests nil)
          (assoc :capabilities nil))))

  (send-request! [client method params]
    (ensure! string? method "method must be a string")
    (ensure! map? params "params must be a map")
    (let [request (make-request method params)
          request-json (json/write-str request)
          request-id (:id request)
          ;; Bound the wait so a wedged stdio server can't hang the caller
          ;; indefinitely — the HTTP transport already sets socket/connection
          ;; timeouts; stdio had none. Reads an optional :timeout off the
          ;; client (records return nil for absent keys), default 30s to match
          ;; the HTTP path.
          timeout-ms (or (:timeout client) 30000)]

      (mulog/debug ::sending-request :method method :id request-id)

      (let [^java.io.Writer stdin (:stdin client)]
        (.write stdin request-json)
        (.write stdin "\n")
        (.flush stdin))

      ;; Read responses (skipping notifications / non-matching ids) until our
      ;; response arrives, on a worker future so the blocking read is bounded
      ;; by the timeout deref. future-cancel can't unpark a blocked readLine,
      ;; but the CALLER is freed — the orphaned read drains on the next
      ;; line/EOF, so the agent never hangs on a silent server.
      (let [reader (future
                     (loop []
                       (let [response-line (.readLine ^java.io.BufferedReader (:stdout client))]
                         (when-not response-line
                           (throw (ex-info "MCP server process closed unexpectedly"
                                           {:method method :server-name (:name client)})))
                         (let [response (json/read-str response-line :key-fn keyword)]
                           (cond
                             ;; Notification (no id) — handle and keep reading.
                             (nil? (:id response))
                             (do (mulog/debug ::skipping-notification :method (:method response))
                                 (process-notification! client response)
                                 (recur))

                             ;; Response for a different request — skip.
                             (not= (:id response) request-id)
                             (do (mulog/warn ::unexpected-response
                                             :expected-id request-id :received-id (:id response))
                                 (recur))

                             (:error response)
                             (throw (ex-info "MCP request failed"
                                             {:method method :error (:error response)}))

                             :else
                             (:result response))))))
            ;; Unwrap the future's ExecutionException so callers see the
            ;; original ex-info (process-closed / request-failed), not a wrapper.
            result (try (deref reader timeout-ms ::timeout)
                        (catch java.util.concurrent.ExecutionException e
                          (throw (or (ex-cause e) e))))]
        (if (= result ::timeout)
          (do (future-cancel reader)
              (throw (ex-info "MCP stdio request timed out"
                              {:method method :server-name (:name client)
                               :timeout-ms timeout-ms})))
          result))))

  (send-notification! [client method params]
    (ensure! string? method "method must be a string")
    (ensure! map? params "params must be a map")
    (let [notification (make-notification method params)
          notif-json (json/write-str notification)]

      (mulog/debug ::sending-notification :method method)

      (let [^java.io.Writer stdin (:stdin client)]
        (.write stdin notif-json)
        (.write stdin "\n")
        (.flush stdin))))

  (get-capabilities [client]
    (:capabilities client))

  (get-server-info [client]
    (:server-info client))

  (list-resources [client]
    (send-request! client (:list-resources mcp-methods) {}))

  (read-resource [client uri]
    (ensure! string? uri "uri must be a string")
    (send-request! client (:read-resource mcp-methods) {:uri uri}))

  (list-tools [client]
    (send-request! client (:list-tools mcp-methods) {}))

  (call-tool [client name arguments]
    (ensure! string? name "name must be a string")
    (ensure! map? arguments "arguments must be a map")
    (send-request! client (:call-tool mcp-methods)
                   {:name name :arguments arguments}))

  (list-prompts [client]
    (send-request! client (:list-prompts mcp-methods) {}))

  (get-prompt [client name arguments]
    (ensure! string? name "name must be a string")
    (ensure! map? arguments "arguments must be a map")
    (send-request! client (:get-prompt mcp-methods)
                   {:name name :arguments arguments})))

(defmethod print-method StdioMCPClient [client ^java.io.Writer w]
  (.write w (str "#StdioMCPClient{:server-info " (pr-str (:server-info client))
                 ", :connected " (boolean (:process client)) "}")))

;; =============================================================================
;; HTTP Streamable Transport — SSE Parsing Helpers
;; =============================================================================

(defn- parse-sse-events
  "Parse SSE text into a sequence of event maps {:event :data}.
   Each event is separated by a blank line. Lines starting with ':' are comments."
  [body-text]
  (let [blocks (str/split body-text #"\n\n+")]
    (->> blocks
         (map (fn [block]
                (let [lines (str/split-lines block)]
                  (reduce (fn [acc line]
                            (cond
                              (str/blank? line) acc
                              (str/starts-with? line ":") acc ; SSE comment
                              (str/starts-with? line "event:")
                              (assoc acc :event (str/trim (subs line 6)))
                              (str/starts-with? line "data:")
                              (update acc :data str (when (:data acc) "\n")
                                      (str/trim (subs line 5)))
                              :else acc))
                          {}
                          lines))))
         (filter #(some? (:data %))))))

(defn- extract-jsonrpc-response
  "Extract the JSON-RPC response matching request-id from SSE events.
   Processes any notifications encountered along the way."
  [client events request-id]
  (loop [[event & rest-events] events]
    (when event
      (let [parsed (json/read-str (:data event) :key-fn keyword)]
        (cond
          ;; Notification (no id) — process and continue
          (nil? (:id parsed))
          (do
            (process-notification! client parsed)
            (recur rest-events))

          ;; Matching response
          (= (:id parsed) request-id)
          (if (:error parsed)
            (throw (ex-info "MCP request failed"
                            {:error (:error parsed) :request-id request-id}))
            (:result parsed))

          ;; Non-matching response — skip
          :else
          (do
            (mulog/warn ::ignoring-mismatched-response
                        :expected-id request-id :received-id (:id parsed))
            (recur rest-events)))))))

(defn- read-sse-stream
  "Read an SSE stream from an InputStream, returning parsed SSE events."
  [input-stream]
  (with-open [^java.io.BufferedReader rdr (io/reader input-stream)]
    (let [sb (StringBuilder.)]
      (loop []
        (let [line (.readLine rdr)]
          (when (some? line)
            (.append sb line)
            (.append sb "\n")
            (recur))))
      (parse-sse-events (str sb)))))

(defn- content-type-is?
  "Check if Content-Type header starts with the given type."
  [headers type-prefix]
  (some-> (or (get headers "content-type")
              (get headers "Content-Type"))
          str/lower-case
          (str/starts-with? type-prefix)))

(defn- read-response
  "Read HTTP response, handling both JSON and SSE content types.
   Returns the JSON-RPC result for the given request-id."
  [client http-response request-id]
  (let [headers (:headers http-response)
        status  (:status http-response)]
    (when-not (<= 200 status 299)
      (throw (ex-info "HTTP request failed"
                      {:status status :headers headers})))
    (cond
      ;; SSE stream response
      (content-type-is? headers "text/event-stream")
      (let [events (read-sse-stream (:body http-response))]
        (extract-jsonrpc-response client events request-id))

      ;; JSON response (single message)
      :else
      (let [body (if (string? (:body http-response))
                   (json/read-str (:body http-response) :key-fn keyword)
                   ;; InputStream body (from :as :stream)
                   (with-open [rdr (io/reader (:body http-response))]
                     (json/read rdr :key-fn keyword)))]
        (cond
          (:error body)
          (throw (ex-info "MCP request failed"
                          {:error (:error body) :request-id request-id}))

          ;; Notification — process and return nil
          (nil? (:id body))
          (do (process-notification! client body) nil)

          :else
          (:result body))))))

(defn- build-request-headers
  "Build standard MCP HTTP request headers."
  [auth-headers session-id]
  (cond-> (merge {"Content-Type"  "application/json"
                  "Accept"        "application/json, text/event-stream"}
                 auth-headers)
    session-id (assoc "Mcp-Session-Id" session-id)))

;; =============================================================================
;; OAuth (RFC 8628 device flow / auth-code) — components/clj-oauth
;;
;; A server config may declare `:auth {:type :oauth :issuer … :client-id …
;; :scopes [...] :flow :auto :account-id "<server>"}`. We resolve a bearer
;; lazily per request via clj-oauth (auto-refreshing within 60s of expiry) and
;; force one refresh + retry on a server-side 401. The bearer is never logged —
;; only the verification URI / user code (which are meant to be shown) are.
;; See docs/design/oauth.md §7.1.
;; =============================================================================

(defn- oauth-server?
  "True when an auth config map declares the OAuth type."
  [auth]
  (and (map? auth) (= :oauth (:type auth))))

(defonce ^:private !oauth-prompt-renderer
  ;; A front-end (the TUI) registers a renderer here at startup; when set, the
  ;; device/auth verification prompt and poll-status updates route to it (rich
  ;; code box / QR) instead of the stderr/log fallback below. The renderer is
  ;; called with a map: {:account-id :event :verification_uri
  ;; :verification_uri_complete :user_code :expires_in :authorize_uri :scopes},
  ;; where :event is :prompt | :pending | :slow-down | :authorized. Never a token.
  (atom nil))

(defn set-oauth-prompt-renderer!
  "Register (or clear with nil) the front-end renderer for OAuth device/auth
   prompts. See `!oauth-prompt-renderer`."
  [f]
  (reset! !oauth-prompt-renderer f))

(defn- oauth-prompt-stderr-fallback
  "Headless fallback when no renderer is registered: stderr + mulog (never tokens)."
  [{:keys [account-id verification_uri user_code authorize_uri]}]
  (binding [*out* *err*]
    (cond
      (and verification_uri user_code)
      (println (format "[MCP %s] To authorize, open %s and enter code: %s"
                       account-id verification_uri user_code))
      authorize_uri
      (println (format "[MCP %s] To authorize, open %s and paste the code back."
                       account-id authorize_uri)))
    (flush)))

(defn- dispatch-oauth-prompt!
  "Route an OAuth prompt/status event to the registered renderer, falling back
   to stderr for the initial :prompt when none is registered. Renderer failures
   never break the login."
  [{:keys [event] :as ev}]
  (if-let [r @!oauth-prompt-renderer]
    (try (r ev) (catch Throwable _ (when (= :prompt event) (oauth-prompt-stderr-fallback ev))))
    (when (= :prompt event) (oauth-prompt-stderr-fallback ev))))

(defn- oauth-on-prompt
  "Initial verification prompt (device code or paste URL). Logs the safe fields
   (never tokens) and dispatches a :prompt event to the renderer/fallback."
  [account-id]
  (fn [{:keys [verification_uri verification_uri_complete user_code expires_in
               authorize_uri scopes]}]
    (mulog/info ::mcp-oauth-prompt
                :account account-id
                :verification_uri verification_uri
                :verification_uri_complete verification_uri_complete
                :user_code user_code
                :authorize_uri authorize_uri
                :scopes scopes
                :expires_in expires_in)
    (dispatch-oauth-prompt! {:account-id account-id :event :prompt
                             :verification_uri verification_uri
                             :verification_uri_complete verification_uri_complete
                             :user_code user_code :expires_in expires_in
                             :authorize_uri authorize_uri :scopes scopes})))

(defn- oauth-on-status
  "Poll-status updates (:pending/:slow-down/:authorized) → renderer, best-effort."
  [account-id]
  (fn [event]
    (dispatch-oauth-prompt! {:account-id account-id :event event})))

(defn- ensure-oauth-login!
  "Ensure a token bundle exists for an OAuth server's account, running the
   device/auth-code flow once if not. Blocks for the human round-trip — safe
   because connects run on background futures (connect-server-async!)."
  [auth]
  (let [account-id (:account-id auth)]
    (when-not (oauth/authenticated? account-id)
      (mulog/info ::mcp-oauth-login :account account-id :flow (:flow auth :auto))
      (oauth/login! (assoc auth
                           :on-user-prompt (oauth-on-prompt account-id)
                           :on-status      (oauth-on-status account-id))))))

(defn- effective-auth-headers
  "Per-request auth headers: the static config `:headers` plus, for an OAuth
   server, a fresh (auto-refreshed) bearer keyed by account-id."
  [static-headers auth]
  (cond-> static-headers
    (oauth-server? auth) (merge (oauth/bearer-headers (:account-id auth)))))

(defn- request-auth-headers
  "Resolve the effective auth headers for a live client record."
  [client]
  (effective-auth-headers (:auth-headers client) (:oauth-auth client)))

(defn- unauthorized?
  "True for a 401 HTTP response map."
  [resp]
  (= 401 (:status resp)))

;; =============================================================================
;; HTTP Streamable Transport Implementation
;; =============================================================================

(defrecord HttpMCPClient [base-url auth-headers session-id server-info capabilities options]
  MCPClient

  (connect! [client config]
    (let [{:keys [url headers auth]} config
          ;; OAuth servers: log in once (device/auth-code) before initialize, and
          ;; carry the auth config on the record so every later request derives a
          ;; fresh bearer. account-id is injected by integration/connect-mcp-server!.
          oauth?  (oauth-server? auth)
          client  (cond-> client oauth? (assoc :oauth-auth auth))
          _       (when oauth? (ensure-oauth-login! auth))
          timeout (or (:timeout (:options client)) (:timeout config) 30000)
          init-request (make-request (:initialize mcp-methods)
                                     {:protocolVersion MCP_VERSION
                                      :capabilities {:roots {:listChanged false}
                                                     :sampling {}}
                                      :clientInfo {:name "Brainyard Agent"
                                                   :version "1.0.0"}})
          req-id  (:id init-request)
          post-init (fn []
                      (http/post url
                                 {:headers (build-request-headers (request-auth-headers client) nil)
                                  :body (json/write-str init-request)
                                  :as :stream
                                  :socket-timeout timeout
                                  :connection-timeout timeout
                                  :throw-exceptions false}))
          resp0   (post-init)
          ;; On a 401 from an OAuth server, force one refresh and retry the init.
          resp    (if (and oauth? (unauthorized? resp0))
                    (do (mulog/info ::mcp-oauth-401-refresh :account (:account-id auth) :phase :initialize)
                        (oauth/refresh! (:account-id auth))
                        (post-init))
                    resp0)]

      (when-not (<= 200 (:status resp) 299)
        (throw (ex-info "HTTP MCP initialization failed"
                        {:status (:status resp)
                         :url url})))

      (let [sess-id (or (get-in resp [:headers "mcp-session-id"])
                        (get-in resp [:headers "Mcp-Session-Id"]))
            result  (read-response client resp req-id)]

        (mulog/info ::http-server-initialized :server-info result :session-id sess-id)

        ;; Send notifications/initialized
        (let [notif-headers (build-request-headers (request-auth-headers client) sess-id)]
          (http/post url
                     {:headers notif-headers
                      :body (json/write-str (make-notification (:initialized mcp-methods) {}))
                      :throw-exceptions false}))

        (cond-> client
          true    (assoc :base-url url)
          true    (assoc :auth-headers headers)
          true    (assoc :session-id sess-id)
          true    (assoc :server-info result)
          oauth?  (assoc :oauth-auth auth)))))

  (disconnect! [client]
    (mulog/info ::disconnecting-http-client :session-id (:session-id client))
    ;; Send DELETE for graceful session teardown when session-id is present
    (when (and (:base-url client) (:session-id client))
      (try
        (http/delete (:base-url client)
                     {:headers (build-request-headers (request-auth-headers client) (:session-id client))
                      :throw-exceptions false})
        (catch Exception e
          (mulog/debug ::delete-session-error :error (ex-message e)))))
    (-> client
        (assoc :base-url nil)
        (assoc :auth-headers nil)
        (assoc :session-id nil)
        (assoc :server-info nil)))

  (send-request! [client method params]
    (ensure! string? method "method must be a string")
    (ensure! map? params "params must be a map")
    (let [request (make-request method params)
          req-id  (:id request)
          timeout (or (:timeout (:options client)) 30000)
          oauth?  (oauth-server? (:oauth-auth client))
          do-post (fn []
                    (http/post (:base-url client)
                               {:headers (build-request-headers (request-auth-headers client)
                                                                (:session-id client))
                                :body (json/write-str request)
                                :as :stream
                                :socket-timeout timeout
                                :connection-timeout timeout
                                :throw-exceptions false}))
          resp    (do-post)]
      ;; A mid-session 401 on an OAuth server means the bearer was revoked or
      ;; expired server-side: force one refresh and retry before surfacing.
      (if (and oauth? (unauthorized? resp))
        (do (mulog/info ::mcp-oauth-401-refresh
                        :account (:account-id (:oauth-auth client)) :method method)
            (oauth/refresh! (:account-id (:oauth-auth client)))
            (read-response client (do-post) req-id))
        (read-response client resp req-id))))

  (send-notification! [client method params]
    (ensure! string? method "method must be a string")
    (ensure! map? params "params must be a map")
    (let [notification (make-notification method params)]
      (try
        (http/post (:base-url client)
                   {:headers (build-request-headers (request-auth-headers client)
                                                    (:session-id client))
                    :body (json/write-str notification)
                    :throw-exceptions false})
        (catch Exception e
          (mulog/debug ::send-notification-error :method method :error (ex-message e))))))

  (get-capabilities [client] (:capabilities client))
  (get-server-info [client] (:server-info client))
  (list-resources [client] (send-request! client (:list-resources mcp-methods) {}))
  (read-resource [client uri]
    (ensure! string? uri "uri must be a string")
    (send-request! client (:read-resource mcp-methods) {:uri uri}))
  (list-tools [client] (send-request! client (:list-tools mcp-methods) {}))
  (call-tool [client name arguments]
    (ensure! string? name "name must be a string")
    (ensure! map? arguments "arguments must be a map")
    (send-request! client (:call-tool mcp-methods) {:name name :arguments arguments}))
  (list-prompts [client] (send-request! client (:list-prompts mcp-methods) {}))
  (get-prompt [client name arguments]
    (ensure! string? name "name must be a string")
    (ensure! map? arguments "arguments must be a map")
    (send-request! client (:get-prompt mcp-methods) {:name name :arguments arguments})))

(defmethod print-method HttpMCPClient [client ^java.io.Writer w]
  (.write w (str "#HttpMCPClient{:base-url " (pr-str (:base-url client))
                 ", :session-id " (pr-str (:session-id client)) "}")))

;; =============================================================================
;; Client Factory and Utilities
;; =============================================================================

(defn create-stdio-client
  "Create a new STDIO MCP client. Carries an optional per-request `:timeout`
   (ms) from config onto the record so a slow stdio server (e.g. Gmail/Calendar
   via mcp-remote) can raise the 30s `send-request!` default — mirrors
   `create-http-client`. `connect!` threads the client through `assoc`, so the
   value survives onto the connected, registered record that `send-request!`
   reads via `(:timeout client)`. Absent → nil → the 30s default applies."
  [config]
  (cond-> (->StdioMCPClient nil nil nil nil nil {:roots {:listChanged false} :sampling {}} nil)
    (:timeout config) (assoc :timeout (:timeout config))))

(defn create-http-client
  "Create a new HTTP MCP client"
  [config]
  (->HttpMCPClient nil nil nil nil
                   {:roots {:listChanged false} :sampling {}}
                   (select-keys config [:timeout])))

(defn create-client
  "Create MCP client based on transport type"
  [transport-type config]
  (case transport-type
    :stdio (create-stdio-client config)
    :http (create-http-client config)
    :http-sse (create-http-client config) ; For now, same as HTTP
    (throw (ex-info "Unsupported transport type" {:transport transport-type}))))

;; =============================================================================
;; High-Level Client Manager
;; =============================================================================

(defonce ^:private active-clients (atom {}))

(defn register-client!
  "Register an active MCP client by name"
  [client-name client]
  (swap! active-clients assoc client-name client)
  (mulog/info ::client-registered :name client-name))

(defn unregister-client!
  "Unregister and disconnect an MCP client"
  [client-name]
  (when-let [client (get @active-clients client-name)]
    (disconnect! client)
    (swap! active-clients dissoc client-name)
    (mulog/info ::client-unregistered :name client-name)))

(defn get-client
  "Get active MCP client by name"
  [client-name]
  (get @active-clients client-name))

(defn list-active-clients
  "List all active MCP clients"
  []
  (keys @active-clients))

;; =============================================================================
;; Configuration Helpers
;; =============================================================================

(defn validate-config
  "Validate MCP client configuration"
  [transport-type config]
  (case transport-type
    :stdio
    (do
      (ensure! string? (:command config) "STDIO config requires :command")
      (ensure! sequential? (:args config) "STDIO config requires :args sequence"))

    (:http :http-sse)
    (do
      (ensure! string? (:url config) "HTTP config requires :url")
      (when (:headers config)
        (ensure! map? (:headers config) "HTTP config :headers must be a map when provided")))

    (throw (ex-info "Unknown transport type" {:transport transport-type})))

  config)

(defn load-mcp-servers-config
  "Load MCP servers configuration from file or environment"
  [config-source]
  (cond
    (string? config-source)
    (-> config-source slurp (json/read-str :key-fn keyword))

    (map? config-source)
    config-source

    :else
    (throw (ex-info "Invalid config source" {:source config-source}))))

(comment
  ;; Example usage

  ;; Create and connect STDIO client
  (def stdio-client (create-client :stdio {}))
  (def connected-stdio
    (connect! stdio-client {:command "python"
                            :args ["-m" "mcp_server_filesystem"]
                            :working-dir "/tmp"}))
  (register-client! "filesystem" connected-stdio)

  ;; Create and connect HTTP client (Streamable HTTP transport)
  ;; URL should be the full MCP endpoint — no suffix is appended
  (def http-client (create-client :http {:timeout 30000}))
  (def connected-http
    (connect! http-client {:url "https://server.smithery.ai/@anthropics/brave-search/mcp"
                           :headers {"Authorization" "Bearer <SMITHERY_API_KEY>"}}))
  (register-client! "brave-search" connected-http)
  (get-server-info connected-http)
  (list-tools connected-http)

  ;; Cleanup
  (unregister-client! "filesystem")
  (unregister-client! "brave-search"))
