;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-client.core.transport
  "HTTP and SSE transport for A2A, over `clj-http-native`.

   Two entry points:

   - `rpc!`      — one blocking JSON-RPC call, returns a result or an error map.
   - `open-sse!` — a long-lived Server-Sent Events subscription, delivering
                   decoded JSON-RPC payloads to a callback.

   Everything returns brainyard-shaped `{:error …}` maps rather than
   throwing. A remote peer being down, slow, unauthorized or malformed is
   an ordinary outcome for this component, not an exceptional one, and the
   result has to be something an LLM can read.

   ## The request-timeout trap

   `clj-http-native` ALWAYS applies `:timeout-ms` (default 60s) to the whole
   request via `HttpRequest.timeout`. For a normal RPC that is what you
   want. For SSE it is a bug waiting to happen: the JDK client counts the
   timeout against the entire exchange, not against idle time, so a healthy
   long-lived stream is killed mid-flight the moment it outlives the
   window — and it surfaces as a generic I/O failure with nothing pointing
   at the timeout. `open-sse!` therefore applies `:stream-timeout-ms`
   (default 24h), deliberately decoupled from the RPC timeout.

   ## Backpressure

   `on-event` is invoked INLINE on the reader thread, not handed to a
   queue. A slow consumer therefore stalls `.readLine`, which stops draining
   the socket, which applies TCP backpressure to the server. That is real
   flow control and it costs nothing.

   The alternative — an internal queue — would need a bound and a policy
   for hitting it, and every available policy is worse: dropping loses
   protocol frames (an `artifact-update` is not resendable), and growing
   unbounded turns a chatty peer into an OOM. The cost of doing it this way
   is that `on-event` must not block indefinitely; that contract is on the
   caller, and it is stated on `open-sse!`."
  (:require [clojure.string :as str]
            [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-client.core.auth :as auth]
            [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.io BufferedReader]))

(def ^:const DEFAULT_TIMEOUT_MS 600000)

(def ^:const DEFAULT_STREAM_TIMEOUT_MS
  "Whole-exchange cap for an SSE subscription. Effectively 'no timeout' —
   see the request-timeout note in the ns docstring for why this cannot
   just reuse the RPC timeout."
  86400000)

;; =============================================================================
;; Headers
;; =============================================================================

(defn request-headers
  "Headers for an A2A request: content negotiation, the protocol version
   service parameter, and credentials.

   `:dialect` decides the `A2A-Version` value, and it is per-PEER rather
   than global. This line used to send a single hardcoded
   `a2a/PROTOCOL_VERSION` — which is how a v1.0 server came back
   `-32009 VERSION_NOT_SUPPORTED` no matter what we asked it."
  [auth-spec & {:keys [accept extensions dialect]}]
  (cond-> (merge {"Content-Type" "application/json"
                  "Accept"       (or accept "application/json")
                  a2a/VERSION_HEADER (a2a/dialect-version
                                      (or dialect a2a/DEFAULT_DIALECT))}
                 (auth/headers auth-spec))
    (seq extensions) (assoc a2a/EXTENSIONS_HEADER (str/join "," extensions))))

;; =============================================================================
;; Error shaping
;; =============================================================================

(defn- http-error
  "Turn a non-2xx HTTP response into a brainyard error map. 401/403 get a
   credentials hint when we can produce one, because an LLM staring at a
   bare `HTTP 401` has no idea whether to retry or to ask for a token."
  [{:keys [status body]} {:keys [card auth url]}]
  (let [hint (when (#{401 403} status)
               (auth/missing-credentials-hint card auth))
        snippet (some-> body str str/trim (as-> s (subs s 0 (min 300 (count s)))))]
    {:error (cond-> (str "A2A HTTP " status " from " url)
              hint    (str " — " hint)
              (and (not hint) (not (str/blank? snippet))) (str " — " snippet))
     :status status}))

(defn- transport-error [^Throwable t url]
  {:error (str "A2A transport failure calling " url ": " (ex-message t))
   :cause (.getSimpleName (class t))})

;; =============================================================================
;; JSON-RPC over HTTP
;; =============================================================================

(defn rpc!
  "Issue one JSON-RPC call against a peer.

   `peer` is `{:endpoint url :auth spec :timeout-ms n :card card :next-id fn}`.
   `method` is a method KEYWORD (`:message-send`), resolved via
   `a2a/method-name` so a typo fails here rather than as `MethodNotFound`
   from every server in the ecosystem.

   Returns `{:result …}` on success, or `{:error …}` — never throws."
  [{:keys [endpoint auth timeout-ms card next-id dialect] :as _peer} method params]
  (let [dl    (or dialect a2a/DEFAULT_DIALECT)
        url   (auth/apply-to-url endpoint auth)
        mname (a2a/dialect-method-name dl method)
        id    (if next-id (next-id) 1)
        body  (a2a/encode (a2a/request id mname params))]
    (try
      (let [resp (http/post url {:headers          (request-headers auth :dialect dl)
                                 :body             body
                                 :as               :string
                                 :throw-exceptions false
                                 :timeout-ms       (or timeout-ms DEFAULT_TIMEOUT_MS)})]
        (if (>= (:status resp) 400)
          (http-error resp {:card card :auth auth :url url})
          (let [decoded (a2a/decode (:body resp))]
            (cond
              (:error decoded) (a2a/error->result (:error decoded))
              ;; The result is handed back in CANONICAL form, so every caller
              ;; above this line stays dialect-unaware. Decoding is per
              ;; METHOD — a GetTask result is a bare Task, not the
              ;; SendMessage one-of.
              (contains? decoded :result)
              {:result (a2a/decode-result dl method (:result decoded))}

              :else {:error (str "Malformed JSON-RPC response from " url
                                 " (no :result and no :error)")}))))
      (catch Exception e
        (mulog/warn ::rpc-failed :method mname :url url :error (ex-message e))
        (transport-error e url)))))

;; =============================================================================
;; SSE
;; =============================================================================

(defn- strip-field
  "Split an SSE line into [field value], stripping ONE optional leading
   space from the value as the SSE grammar requires. A line with no colon
   is a field with an empty value."
  [^String line]
  (let [i (.indexOf line ":")]
    (if (neg? i)
      [line ""]
      (let [field (subs line 0 i)
            v     (subs line (inc i))]
        [field (if (str/starts-with? v " ") (subs v 1) v)]))))

(defn parse-sse-line
  "Fold one SSE line into the in-progress frame `acc`.

   Returns `[acc' dispatch?]` where `dispatch?` is true when the line
   terminated a frame. Comment lines (leading `:`) are ignored — servers
   use them as keep-alives, and treating one as data would inject garbage
   into the stream.

   Exposed (not private) because SSE framing is exactly the kind of thing
   that deserves direct unit tests rather than only being exercised
   through a socket."
  [acc ^String line]
  (cond
    (nil? line) [acc false]

    (str/blank? line)
    (if (seq (:data acc)) [acc true] [acc false])

    (str/starts-with? line ":")
    [acc false]

    :else
    (let [[field v] (strip-field line)]
      [(case field
         "data"  (update acc :data (fnil conj []) v)
         "event" (assoc acc :event v)
         "id"    (assoc acc :id v)
         "retry" (assoc acc :retry v)
         acc)
       false])))

(defn frame-payload
  "The `data` payload of a completed SSE frame: its data lines joined by
   newline, per the SSE grammar."
  [frame]
  (str/join "\n" (:data frame)))

(defn- decode-frame
  "Decode one SSE frame's payload into the A2A object it carries.

   A2A streams JSON-RPC responses, so each frame's data is a full envelope
   and the payload we want is its `:result`. A frame carrying an `:error`
   envelope is surfaced as an error rather than silently skipped.

   The result is normalized to the CANONICAL frame shape, so
   `events/translate` never learns that a second dialect exists."
  [dialect frame]
  (let [payload (frame-payload frame)]
    (when-not (str/blank? payload)
      (try
        (let [decoded (a2a/decode payload)]
          (cond
            (:error decoded) (a2a/error->result (:error decoded))
            (contains? decoded :result)
            {:result (a2a/decode-stream-frame dialect (:result decoded))}
            ;; Some servers stream the bare object without an envelope.
            :else {:result (a2a/decode-stream-frame dialect decoded)}))
        (catch Exception e
          {:error (str "Malformed SSE frame: " (ex-message e))})))))

(defn open-sse!
  "Open an SSE subscription and stream decoded payloads to `on-event`.

   `handlers`:
     :on-event — (fn [payload]) per frame, where payload is `{:result …}`
                 or `{:error …}`. Called INLINE on the reader thread; see
                 the backpressure note in the ns docstring. It must not
                 block indefinitely.
     :on-error — (fn [err-map]) on transport/HTTP failure. Optional.
     :on-close — (fn []) once, when the stream ends for any reason,
                 including `stop!`. Optional.

   Returns `{:stop! (fn []) :running? (fn [])}` immediately; the read loop
   runs on a daemon thread.

   The thread is a raw daemon `Thread`, not a `future`: `future` runs on
   the agent pool, whose non-daemon threads keep the JVM alive at exit
   unless `shutdown-agents` is called. A subscription that outlives its
   caller must never be the reason `by` will not quit."
  [{:keys [endpoint auth stream-timeout-ms card next-id dialect] :as _peer}
   method params
   {:keys [on-event on-error on-close]}]
  (let [dl      (or dialect a2a/DEFAULT_DIALECT)
        url     (auth/apply-to-url endpoint auth)
        mname   (a2a/dialect-method-name dl method)
        id      (if next-id (next-id) 1)
        body    (a2a/encode (a2a/request id mname params))
        !running (atom true)
        !reader  (atom nil)
        fire-err (fn [e] (when on-error (try (on-error e) (catch Exception _ nil))))
        finish   (fn [] (reset! !running false)
                   (when-let [^BufferedReader r @!reader]
                     (try (.close r) (catch Exception _ nil)))
                   (when on-close (try (on-close) (catch Exception _ nil))))
        work    (fn []
                  (try
                    (let [resp (http/post url
                                          {:headers (request-headers
                                                     auth :accept "text/event-stream"
                                                     :dialect dl)
                                           :body    body
                                           :as      :reader
                                           :throw-exceptions false
                                           :timeout-ms (or stream-timeout-ms
                                                           DEFAULT_STREAM_TIMEOUT_MS)})
                          ^BufferedReader rdr (:body resp)]
                      (reset! !reader rdr)
                      (if (>= (:status resp) 400)
                        (fire-err (http-error {:status (:status resp)
                                               :body   (try (slurp rdr) (catch Exception _ ""))}
                                              {:card card :auth auth :url url}))
                        (loop [acc {}]
                          (if-not @!running
                            nil
                            (let [line (.readLine rdr)]
                              (if (nil? line)
                                nil ;; EOF — server closed the stream
                                (let [[acc' dispatch?] (parse-sse-line acc line)]
                                  (if dispatch?
                                    (do (when-let [payload (decode-frame dl acc')]
                                          (when on-event (on-event payload)))
                                        (recur {}))
                                    (recur acc')))))))))
                    (catch Exception e
                      ;; A close from `stop!` surfaces here as an I/O error on
                      ;; the blocked read. That is an orderly shutdown, not a
                      ;; failure, so it must not reach on-error.
                      (when @!running
                        (mulog/warn ::sse-failed :method mname :url url
                                    :error (ex-message e))
                        (fire-err (transport-error e url))))
                    (finally (finish))))
        thread  (doto (Thread. ^Runnable work (str "a2a-sse-" id))
                  (.setDaemon true)
                  (.start))]
    {:stop!    (fn []
                 (reset! !running false)
                 (when-let [^BufferedReader r @!reader]
                   (try (.close r) (catch Exception _ nil)))
                 (.interrupt thread)
                 true)
     :running? (fn [] @!running)}))

;; =============================================================================
;; Plain GET (Agent Card discovery)
;; =============================================================================

(defn get-json!
  "GET `url` and decode a JSON body. Returns `{:result …}` or `{:error …}`.
   Used for the unauthenticated well-known Agent Card."
  [url {:keys [auth timeout-ms]}]
  (let [u (auth/apply-to-url url auth)]
    (try
      (let [resp (http/get u {:headers          (request-headers auth)
                              :as               :string
                              :throw-exceptions false
                              :timeout-ms       (or timeout-ms DEFAULT_TIMEOUT_MS)})]
        (if (>= (:status resp) 400)
          (http-error resp {:auth auth :url u})
          (try
            {:result (a2a/decode (:body resp))}
            (catch Exception e
              {:error (str "Malformed JSON from " u ": " (ex-message e))}))))
      (catch Exception e
        (transport-error e u)))))
