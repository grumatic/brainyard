;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp-stub-agent.core
  "In-tree ACP agent for protocol validation.

   Speaks ACP over stdin/stdout (line-delimited JSON-RPC 2.0) and
   exposes one mode:

     --echo   Deterministic echo agent. The prompt's text is tokenized
              on whitespace; each token is streamed back as an
              `agent_message_chunk` `session/update` notification.
              No LLM call, no external dependencies. Suitable for CI.

   The stub validates the brainyard ACP client end-to-end without
   pulling in coact-agent, an LLM, or a Node/TS adapter. A future
   `--coact` mode (Phase 5) will wrap coact-agent and translate its
   hooks into `session/update` payloads.

   Per <docs/acp-design.md §4.5>."
  (:require [ai.brainyard.acp.interface :as acp]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader])
  (:gen-class))

;; =============================================================================
;; State
;; =============================================================================

(def ^:private !sessions
  "session-id -> {:cancelled? atom<bool>, :prompt-future future?}"
  (atom {}))

(def ^:private !out-lock (Object.))

(def ^:private !shutdown? (atom false))

;; =============================================================================
;; I/O — stdout writes are serialized; stderr is used only for diagnostics
;; (so it doesn't pollute the JSON-RPC channel).
;; =============================================================================

(defn- log!
  "Diagnostic log to stderr. The JSON-RPC channel on stdout must not be touched."
  [& args]
  (binding [*out* *err*]
    (apply println args)
    (.flush *err*)))

(defn- write-message!
  "Encode a JSON-RPC message and write a single line to stdout, flushed.
   Synchronized so concurrent producers (response thread + prompt
   future) don't interleave bytes."
  [msg]
  (let [line (acp/encode msg)]
    (locking !out-lock
      (.write ^java.io.Writer *out* (str line "\n"))
      (.flush ^java.io.Writer *out*))))

;; =============================================================================
;; Echo streamer
;; =============================================================================

(defn- extract-prompt-text
  "Concatenate text from prompt content blocks (ignoring non-text)."
  [prompt-blocks]
  (->> prompt-blocks
       (keep (fn [block]
               (when (= "text" (:type block))
                 (:text block))))
       (str/join " ")))

(defn- emit-chunk!
  "Send one `agent_message_chunk` session/update notification."
  [session-id text]
  (write-message!
   (acp/notification "session/update"
                     {:sessionId     session-id
                      :sessionUpdate "agent_message_chunk"
                      :content       {:type "text" :text text}})))

(defn- echo-stream!
  "Stream the prompt back token-by-token. Returns the stop reason as a
   string per ACP semantics: \"end_turn\" if completed, \"cancelled\"
   if the session's :cancelled? flag flipped mid-stream.

   `:chunk-delay-ms` controls inter-chunk pacing (default 20ms). Tests
   that exercise cancellation pass a higher value so they can
   reliably interrupt mid-stream."
  [session-id !cancelled? prompt-text {:keys [chunk-delay-ms] :or {chunk-delay-ms 20}}]
  (let [tokens (->> (str/split prompt-text #"\s+")
                    (remove str/blank?))]
    (emit-chunk! session-id "echo:")
    (loop [[tok & more] tokens]
      (cond
        @!cancelled?
        "cancelled"

        (nil? tok)
        "end_turn"

        :else
        (do
          (emit-chunk! session-id (str " " tok))
          (when (pos? chunk-delay-ms)
            (Thread/sleep (long chunk-delay-ms)))
          (recur more))))))

;; =============================================================================
;; Method handlers
;; =============================================================================

(defn- handle-initialize [_params]
  {:protocolVersion   acp/PROTOCOL_VERSION
   :agentCapabilities {:promptCapabilities {:audio false :image false :embeddedContext false}}
   :authMethods       []})

(defn- handle-session-new [_params]
  (let [session-id (str "stub-" (random-uuid))]
    (swap! !sessions assoc session-id {:cancelled?    (atom false)
                                       :prompt-future nil})
    {:sessionId session-id}))

(defn- handle-session-prompt
  "Streams chunks asynchronously; returns the response *future* via
   side-effect rather than the dispatcher's return path so that the
   dispatcher loop can continue reading messages (e.g. session/cancel)
   while the stream is in flight."
  [request-id {:keys [sessionId prompt] :as _params} mode-opts]
  (let [{:keys [cancelled?]} (get @!sessions sessionId)]
    (when-not cancelled?
      (write-message! (acp/error-response request-id
                                          (:invalid-params acp/error-codes)
                                          "unknown sessionId"
                                          {:sessionId sessionId}))
      (throw (ex-info "no such session" {:sessionId sessionId})))
    (reset! cancelled? false)
    (let [text   (extract-prompt-text prompt)
          fut    (future
                   (try
                     (let [stop (echo-stream! sessionId cancelled? text mode-opts)]
                       (write-message! (acp/response request-id {:stopReason stop})))
                     (catch Throwable t
                       (log! "[stub] prompt error:" (ex-message t))
                       (write-message!
                        (acp/error-response request-id
                                            (:internal-error acp/error-codes)
                                            (or (ex-message t) "internal error"))))))]
      (swap! !sessions assoc-in [sessionId :prompt-future] fut)
      ::async)))

(defn- handle-session-cancel [{:keys [sessionId]}]
  (when-let [{:keys [cancelled?]} (get @!sessions sessionId)]
    (reset! cancelled? true))
  ;; ACP cancel is a request → empty success response.
  {})

;; =============================================================================
;; Dispatcher
;; =============================================================================

(defn- dispatch-request!
  "Invoke the right handler. For methods that respond inline the
   handler returns the result map and the dispatcher writes the
   response. For methods that respond asynchronously (session/prompt)
   the handler writes its own response and returns ::async."
  [request mode-opts]
  (let [{:keys [id method params]} request]
    (try
      (case method
        "initialize"
        (write-message! (acp/response id (handle-initialize params)))

        "session/new"
        (write-message! (acp/response id (handle-session-new params)))

        "session/prompt"
        (handle-session-prompt id params mode-opts)

        "session/cancel"
        (write-message! (acp/response id (handle-session-cancel params)))

        ;; Unknown method
        (write-message! (acp/error-response id
                                            (:method-not-found acp/error-codes)
                                            (str "method not found: " method))))
      (catch Throwable t
        (log! "[stub] handler error for" method ":" (ex-message t))
        (write-message! (acp/error-response id
                                            (:internal-error acp/error-codes)
                                            (or (ex-message t) "internal error")))))))

(defn- run-dispatcher!
  "Read JSON-RPC lines from `reader` until EOF, dispatching each.
   Returns when the stream closes or `!shutdown?` flips true."
  [^BufferedReader reader mode-opts]
  (loop []
    (when-not @!shutdown?
      (when-let [line (.readLine reader)]
        (when-not (str/blank? line)
          (try
            (let [msg (acp/decode line)]
              (case (acp/classify msg)
                :request      (dispatch-request! msg mode-opts)
                :notification (log! "[stub] notification (ignored):" (:method msg))
                :response     (log! "[stub] unexpected response (ignored):" (:id msg))
                :invalid      (log! "[stub] invalid message (ignored):" (pr-str msg))))
            (catch Throwable t
              (log! "[stub] decode/dispatch error:" (ex-message t)))))
        (recur)))))

;; =============================================================================
;; Entry point
;; =============================================================================

(defn -main
  "Run the stub agent. Single mode: --echo (default).

   Flags:
     --echo                    Echo mode (the only mode; default).
     --chunk-delay-ms=N        Inter-token delay during streaming
                               (default 20). Useful for cancel tests.

   Reads JSON-RPC from stdin, writes responses + session/update
   notifications to stdout. Stderr carries diagnostic logs only."
  [& args]
  (let [known-flag? (fn [a] (or (= "--echo" a)
                                (str/starts-with? a "--chunk-delay-ms=")))
        unknown (remove known-flag? args)]
    (when (seq unknown)
      (binding [*out* *err*]
        (println "unknown args:" (pr-str unknown))
        (println "supported: --echo, --chunk-delay-ms=N"))
      (System/exit 2))
    (let [chunk-delay-ms (or (some-> (some #(when (str/starts-with? % "--chunk-delay-ms=") %) args)
                                     (subs (count "--chunk-delay-ms="))
                                     parse-long)
                             20)]
      (log! "[stub] starting --echo mode (chunk-delay-ms=" chunk-delay-ms ")")
      (let [reader (BufferedReader. (InputStreamReader. System/in))]
        (run-dispatcher! reader {:chunk-delay-ms chunk-delay-ms})
        (log! "[stub] stdin closed; shutting down")
        (System/exit 0)))))
