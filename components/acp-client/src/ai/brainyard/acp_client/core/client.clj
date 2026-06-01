;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.acp-client.core.client
  "AcpClient — concurrent JSON-RPC dispatcher over an ACP transport.

   Responsibilities:

     1. **Pending requests.** `request!` allocates a fresh id, sends
        the message, and returns a promise that resolves when the
        response arrives.

     2. **Dispatcher pump.** A daemon thread reads parsed messages
        from the transport and routes:
        - responses     → resolve the matching pending promise
        - notifications → call `on-event` with the message (the caller
                          typically passes events to events.clj for
                          translation, then fires hooks)
        - reverse calls → look up a handler in `:callbacks`, run it,
                          send back a response

     3. **Initialize handshake.** `initialize!` runs the client side
        of the lifecycle — sends `initialize`, awaits the result.

   Concurrency: the pump thread does no work besides routing, so a
   slow notification handler will not stall responses for in-flight
   prompts. If the caller's `on-event` blocks, prompt resolution still
   happens — but downstream events queue. For Phase 3 that is the
   simpler-and-safer default; an optional `:async-events?` flag is
   left as a follow-up."
  (:require [ai.brainyard.acp.interface :as acp]
            [ai.brainyard.acp-client.core.callbacks :as callbacks]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.io Closeable]
           [java.util.concurrent TimeUnit TimeoutException]))

;; =============================================================================
;; AcpClient record
;; =============================================================================

(defrecord AcpClient [transport         ;; ITransport
                      id-source         ;; () -> long
                      !pending          ;; atom<id -> promise>
                      !running          ;; atom<bool>
                      !pump-thread      ;; atom<Thread?>
                      on-event          ;; fn(notification-msg) — called on notifications
                      callbacks         ;; map<method-name, fn>
                      !server-info]     ;; atom<map?> — set after initialize
  Closeable
  (close [this]
    ;; Implemented below as `close!`; Closeable is for `with-open` interop.
    (when (and @!running)
      (reset! !running false)
      (when-let [^Thread t @!pump-thread]
        (.interrupt t))
      (acp/close! transport)
      ;; Reject any in-flight requests so callers don't hang.
      (let [pending @!pending]
        (reset! !pending {})
        (doseq [[id p] pending]
          (deliver p {::error :transport-closed :id id}))))
    nil))

(defmethod print-method AcpClient [c ^java.io.Writer w]
  (.write w (str "#AcpClient{:running " (boolean @(:!running c))
                 ", :pending " (count @(:!pending c))
                 ", :server-info " (pr-str @(:!server-info c)) "}")))

;; =============================================================================
;; Internal — dispatch arms
;; =============================================================================

(defn- handle-response! [client msg]
  (let [{:keys [id]} msg
        pending (:!pending client)
        p (get @pending id)]
    (if p
      (do
        (swap! pending dissoc id)
        (deliver p msg))
      (mulog/warn ::orphan-response :id id))))

(defn- handle-notification! [client msg]
  (try
    ((:on-event client) msg)
    (catch Throwable t
      (mulog/warn ::on-event-error
                  :method (:method msg)
                  :error  (ex-message t)))))

(defn- handle-reverse-call! [client msg]
  (let [{:keys [id method params]} msg
        handler (get (:callbacks client) method)]
    (try
      (if handler
        (let [result (handler params)]
          (acp/write-message! (:transport client)
                              (acp/response id result)))
        (acp/write-message! (:transport client)
                            (acp/error-response id
                                                (:method-not-found acp/error-codes)
                                                (str "method not found: " method))))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)
              code (if (= :acp/method-error (:type data))
                     (:invalid-params acp/error-codes)
                     (:internal-error acp/error-codes))]
          (acp/write-message! (:transport client)
                              (acp/error-response id code (ex-message e)))))
      (catch Throwable t
        (mulog/warn ::reverse-call-error :method method :error (ex-message t))
        (acp/write-message! (:transport client)
                            (acp/error-response id
                                                (:internal-error acp/error-codes)
                                                (or (ex-message t) "internal error")))))))

(defn- pump-loop! [client]
  (let [transport (:transport client)
        running   (:!running client)]
    (try
      (loop []
        (when @running
          (let [msg (try
                      (acp/read-message! transport 200)
                      (catch InterruptedException _ nil))]
            (cond
              (nil? msg)
              (if (acp/open? transport)
                (recur)               ;; idle tick — keep polling
                (do
                  (mulog/info ::pump-eof)
                  (reset! running false)))

              :else
              (do
                (case (acp/classify msg)
                  :response     (handle-response! client msg)
                  :notification (handle-notification! client msg)
                  :request      (handle-reverse-call! client msg)
                  :invalid      (mulog/warn ::invalid-message :msg msg))
                (recur))))))
      (catch Throwable t
        (mulog/error ::pump-fatal :error (ex-message t))
        (reset! running false))
      (finally
        ;; Wake up any pending awaiters so they don't block forever.
        (let [pending (deref (:!pending client))]
          (reset! (:!pending client) {})
          (doseq [[_id p] pending]
            (deliver p {::error :pump-stopped})))))))

(defn- start-pump! [client]
  (let [t (Thread. ^Runnable (fn [] (pump-loop! client)))]
    (.setDaemon t true)
    (.setName t "acp-client-pump")
    (.start t)
    (reset! (:!pump-thread client) t)
    client))

;; =============================================================================
;; Public — request / notify
;; =============================================================================

(defn request!
  "Send a JSON-RPC request and return a promise that resolves with the
   parsed response map (success or error). Caller derefs / awaits.

   The `opts` arity is accepted for API symmetry but currently ignores
   `:timeout-ms` — timeouts are handled in `await-result` instead so
   that the returned reference is always a plain promise (uniform
   deref semantics)."
  ([client method params] (request! client method params {}))
  ([client method params _opts]
   (when-not @(:!running client)
     (throw (ex-info "client is closed" {:method method})))
   (let [id ((:id-source client))
         p  (promise)]
     (swap! (:!pending client) assoc id p)
     (try
       (acp/write-message! (:transport client) (acp/request id method params))
       (catch Throwable t
         (swap! (:!pending client) dissoc id)
         (throw t)))
     p)))

(defn notify!
  "Send a JSON-RPC notification (fire-and-forget — no response expected)."
  [client method params]
  (when-not @(:!running client)
    (throw (ex-info "client is closed" {:method method})))
  (acp/write-message! (:transport client) (acp/notification method params))
  nil)

(defn await-result
  "Deref a request! result, returning the JSON-RPC `:result` value on
   success or throwing ExceptionInfo on error response or transport
   failure."
  ([result-ref]            (await-result result-ref nil))
  ([result-ref timeout-ms]
   (let [msg (if timeout-ms
               (let [v (deref result-ref timeout-ms ::timeout)]
                 (if (= ::timeout v)
                   (throw (ex-info "ACP await timeout" {:timeout-ms timeout-ms}))
                   v))
               (deref result-ref))]
     (cond
       (and (map? msg) (::error msg))
       (throw (ex-info "ACP transport error before response"
                       {:type :acp/transport-error :error (::error msg)}))

       (acp/error? msg)
       (throw (ex-info (or (-> msg :error :message) "ACP error response")
                       {:type :acp/error-response
                        :error (:error msg)
                        :id (:id msg)}))

       :else
       (:result msg)))))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn create
  "Build (but do not yet open) an AcpClient over the given transport.

   Required:
     :transport   — an `ai.brainyard.acp/ITransport` instance

   Optional:
     :on-event    — fn(notification-msg) called for every server
                    notification (typically `session/update`).
                    Default: drop and log at debug.
     :callbacks   — map<method-name, fn(params) -> result-map> used to
                    service reverse calls (`fs/*`,
                    `session/request_permission`). Merged on top of
                    `callbacks/default-callbacks`."
  [{:keys [transport on-event callbacks] :as _opts}]
  (when-not transport
    (throw (ex-info "transport is required" {})))
  (map->AcpClient
   {:transport     transport
    :id-source     (acp/make-id-source)
    :!pending      (atom {})
    :!running      (atom false)
    :!pump-thread  (atom nil)
    :on-event      (or on-event
                       (fn [m] (mulog/debug ::dropped-notification :method (:method m))))
    :callbacks     (merge callbacks/default-callbacks (or callbacks {}))
    :!server-info  (atom nil)}))

(defn open!
  "Open the underlying transport and start the dispatcher pump.
   Returns the client. Idempotent on already-open clients."
  [client]
  (when-not @(:!running client)
    (acp/open! (:transport client))
    (reset! (:!running client) true)
    (start-pump! client))
  client)

(defn close!
  "Stop the dispatcher pump and close the transport. Idempotent."
  [client]
  (.close ^Closeable client))

(defn open?
  "True while the client is alive."
  [client]
  (boolean @(:!running client)))

(defn server-info
  "Return the agent's `initialize` result (capabilities, auth methods,
   protocol version) — nil before initialize completes."
  [client]
  @(:!server-info client))

;; =============================================================================
;; Initialize handshake
;; =============================================================================

(defn initialize!
  "Run the client side of the ACP `initialize` handshake.

   Sends `initialize` with our protocol version and capabilities;
   awaits and stores the agent's result. Subsequent `server-info`
   calls return what came back.

   Returns the agent's initialize result map.

   `opts`:
     :protocol-version    — override the version we declare (default: acp/PROTOCOL_VERSION)
     :client-info         — {:name str :version str?} (default: brainyard)
     :client-capabilities — capabilities map (default: declares fs read/write)
     :timeout-ms          — handshake timeout (default 30000)"
  ([client] (initialize! client {}))
  ([client {:keys [protocol-version client-info client-capabilities timeout-ms]
            :or   {protocol-version    acp/PROTOCOL_VERSION
                   client-info         {:name "brainyard" :version "1.0.0"}
                   client-capabilities {:fs {:readTextFile  true
                                             :writeTextFile true}}
                   timeout-ms          30000}}]
   (let [result-ref (request! client "initialize"
                              {:protocolVersion     protocol-version
                               :clientCapabilities  client-capabilities
                               :clientInfo          client-info}
                              {:timeout-ms timeout-ms})
         result     (await-result result-ref timeout-ms)]
     (reset! (:!server-info client) result)
     result)))
