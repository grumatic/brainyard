;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.llm
  "HTTP client for LLM APIs (OpenAI-compatible and Anthropic)."
  (:require [ai.brainyard.clj-http-native.interface :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ai.brainyard.clj-llm.core.sse :as sse]
            [ai.brainyard.clj-llm.core.usage :as usage]
            [ai.brainyard.clj-llm.core.providers :as providers]
            [ai.brainyard.clj-llm.core.claude-code :as claude-code]
            [ai.brainyard.clj-llm.core.acp :as acp]
            [ai.brainyard.clj-llm.core.bedrock :as bedrock]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.io BufferedReader]))

;; clj-http-native uses java.net.http which handles connection pooling
;; internally (HTTP/2 multiplexing + per-host pool). The old clj-http
;; reusable-conn-manager defonce is gone; call sites that pass
;; `:connection-manager` get silently ignored by the wrapper.

(def ^:dynamic *active-stream-register*
  "Optional callback (fn [^java.io.Closeable stream-or-nil]) the agent layer
   binds to track an in-flight LLM stream for preemptive cancellation. The
   streaming functions invoke it with the response reader on open, and with
   nil in finally. When unbound, streaming proceeds without registration."
  nil)

(defn- with-active-stream*
  [^BufferedReader rdr f]
  (try
    (when *active-stream-register* (*active-stream-register* rdr))
    (f)
    (finally
      (when *active-stream-register* (*active-stream-register* nil)))))

(defn- proxy-opts
  "Return clj-http proxy options from https_proxy env var, or empty map."
  []
  (if-let [proxy-url (or (System/getenv "https_proxy")
                         (System/getenv "HTTPS_PROXY"))]
    (try
      (let [uri (java.net.URI. proxy-url)]
        {:proxy-host (.getHost uri)
         :proxy-port (.getPort uri)})
      (catch Exception _ {}))
    {}))

;; ============================================================================
;; Response Parsing
;; ============================================================================

(defn- find-last-json-object
  "Scan `text` left-to-right tracking brace depth and JSON-string state;
   return the last top-level `{...}` substring, or nil when none found.
   Strings are recognized with `\\`-escapes so braces inside string values
   don't throw off the depth counter. Used as a fallback when the model
   wraps the required JSON envelope with preamble prose or markdown fences."
  [^String text]
  (let [n (.length text)]
    (loop [i 0, start nil, depth 0, in-str? false, escape? false, last-obj nil]
      (if (>= i n)
        last-obj
        (let [c (.charAt text i)]
          (cond
            escape?  (recur (inc i) start depth in-str? false last-obj)
            in-str?  (cond
                       (= \\ c) (recur (inc i) start depth true true last-obj)
                       (= \" c) (recur (inc i) start depth false false last-obj)
                       :else    (recur (inc i) start depth true false last-obj))
            (= \" c) (recur (inc i) start depth true false last-obj)
            (= \{ c) (recur (inc i) (or start i) (inc depth) false false last-obj)
            (= \} c) (let [d (dec depth)]
                       (if (and (zero? d) start)
                         (recur (inc i) nil 0 false false (subs text start (inc i)))
                         (recur (inc i) start d false false last-obj)))
            :else    (recur (inc i) start depth false false last-obj)))))))

(defn parse-json-response
  "Parse a JSON response string, handling markdown fences.
   Returns parsed Clojure data.
   Fallback path: when the raw text is preamble + fenced code + JSON (some
   models emit the required envelope after a free-form explanation), scan
   for the LAST top-level balanced `{...}` object and parse that.
   On final parse failure, throws ex-info with :raw-text attached."
  [text]
  (let [cleaned (-> text
                    str/trim
                    ;; Remove markdown json fences
                    (str/replace #"^```(?:json)?\s*\n?" "")
                    (str/replace #"\n?```\s*$" "")
                    str/trim)]
    (try
      (json/read-str cleaned :key-fn keyword)
      (catch Exception e
        (if-let [extracted (find-last-json-object cleaned)]
          (try
            (json/read-str extracted :key-fn keyword)
            (catch Exception _
              (let [preview (subs cleaned 0 (min (count cleaned) 200))]
                (throw (ex-info (str "JSON parse failed: " (.getMessage e)
                                     "\nLLM response: " preview)
                                {:raw-text cleaned}
                                e)))))
          ;; No balanced {...} object anywhere — the model replied with plain
          ;; prose instead of the required JSON envelope. Flag it distinctly
          ;; (:no-json-envelope?) so callers can surface the prose as a thought
          ;; rather than as a parse error, and keep the message free of a prose
          ;; dump (the full text rides :raw-text).
          (throw (ex-info "LLM response was plain text with no JSON object."
                          {:raw-text cleaned :no-json-envelope? true}
                          e)))))))

(defn- extract-openai-content
  "Extract text content from an OpenAI-compatible response."
  [response]
  (-> response :choices first :message :content))

(defn- extract-anthropic-content
  "Extract text content from an Anthropic Messages API response."
  [response]
  (->> (:content response)
       (filter #(= "text" (:type %)))
       first
       :text))

(defn extract-content
  "Extract text content from an LLM response based on message format."
  [response lm-config]
  (case (:message-format lm-config)
    :claude-code (extract-anthropic-content response)
    :acp         (extract-anthropic-content response)
    :anthropic   (extract-anthropic-content response)
    :bedrock     (extract-anthropic-content response)
    (extract-openai-content response)))

;; ============================================================================
;; Retry Logic
;; ============================================================================

(defn- retryable-status?
  "Check if an HTTP status code is retryable."
  [status]
  (when status
    (or (= 429 status)
        (>= status 500))))

(defn- parse-retry-after
  "Parse retry-after header (seconds) from an ExceptionInfo's ex-data.
   Returns delay in milliseconds, or nil."
  [e]
  (when (instance? clojure.lang.ExceptionInfo e)
    (some-> (ex-data e)
            (get-in [:headers "retry-after"])
            parse-long
            (* 1000))))

(defn- retry-with-backoff
  "Execute f with exponential backoff retry on retryable errors.
   Respects retry-after header from 429 responses.
   For 429 rate limits, allows extra retries beyond max-retries.
   Returns the result of f or throws the last exception."
  [f {:keys [max-retries base-delay-ms]
      :or   {max-retries 3 base-delay-ms 1000}}]
  (loop [attempt 0]
    (let [result (try
                   {:ok (f)}
                   (catch clojure.lang.ExceptionInfo e
                     (let [status (-> (ex-data e) :status)
                           ;; 429 gets extra retries to ride out rate-limit windows
                           effective-max (if (= 429 status)
                                           (+ max-retries 3)
                                           max-retries)]
                       (if (and (retryable-status? status)
                                (< attempt effective-max))
                         {:retry true :exception e}
                         {:error e})))
                   (catch Exception e
                     (if (< attempt max-retries)
                       {:retry true :exception e}
                       {:error e})))]
      (cond
        (:ok result)    (:ok result)
        (:error result) (throw (:error result))
        (:retry result) (do
                          (let [base (* base-delay-ms (long (Math/pow 2 attempt)))
                                ;; Add up to 50% random jitter to avoid thundering herd
                                jitter (long (* base (rand 0.5)))
                                backoff-ms (+ base jitter)
                                ;; Respect retry-after header when present (429 responses)
                                retry-after-ms (or (parse-retry-after (:exception result)) 0)
                                delay-ms (max backoff-ms retry-after-ms)]
                            (mulog/info ::retry-llm-call :delay-ms delay-ms :attempt (inc attempt))
                            ;; (long ...) required for native-image —
                            ;; Thread/sleep dispatches via reflection
                            ;; without it. See mode.clj note.
                            (Thread/sleep (long delay-ms)))
                          (recur (inc attempt)))))))

;; ============================================================================
;; Error classification (for the agent repair path)
;; ============================================================================
;;
;; `retry-with-backoff` above already retries transient errors at the *call*
;; layer (429/5xx + generic network throws). What still surfaces to a caller is
;; one of: a malformed-output parse error, a non-retryable client/config error,
;; or a transient failure that exhausted call-layer retries (or came from a
;; provider path without them). `classify-error` lets the agent tell these apart
;; so it can re-prompt vs retry vs abort — and show an accurate message instead
;; of a blanket "malformed output".

(def ^:private fatal-error-re
  ;; Non-retryable, non-HTTP-status phrases: auth / quota / billing / config.
  #"(?i)authenticat|unauthorized|forbidden|invalid[ _-]?api[ _-]?key|api[ _-]?key (?:is )?(?:invalid|missing)|invalid[ _-]?credential|permission denied|\bquota\b|\bbilling\b|payment required|is ?n['’]?t supported|not supported|undefined scheme|model not found|does not exist|invalid[ _-]?request")

(def ^:private transient-error-re
  ;; Retryable network/server phrases that carry no HTTP :status.
  #"(?i)timed? ?out|timeout|connection reset|connection refused|connection closed|reset by peer|broken pipe|unexpected end of stream|stream closed|goaway|unknown ?host|no route to host|temporarily unavailable|service unavailable|overloaded|unable to process|bad gateway|gateway timeout|internal server error|please try again")

(defn- error-text [e]
  (str (or (ex-message e) (str e))
       (when-let [c (some-> (ex-cause e) ex-message)] (str " | " c))))

(defn- first-line [s]
  (let [l (-> (str s) (str/split #"\n") first str/trim)]
    (subs l 0 (min (count l) 160))))

(defn classify-error
  "Classify an exception thrown by an LLM call into a remedy class:
     :malformed — the model produced unparseable/invalid output; the fix is to
                  re-prompt (re-running the same call won't help).
     :transient — a network/server failure that may succeed on retry: HTTP 5xx,
                  connection/timeout errors, provider overloaded / unable-to-process.
     :fatal     — non-retryable: auth / quota / billing, malformed request, a 4xx
                  (incl. 429 rate-limit), or a model/endpoint misconfiguration.
   Returns `{:class <kw> :reason <short human string>}`. HTTP errors carry
   `:status` in ex-data; parse errors carry `:raw-text` (see parse-json-response)."
  [e]
  (let [data   (when (instance? clojure.lang.ExceptionInfo e) (ex-data e))
        status (:status data)
        text   (error-text e)]
    (cond
      ;; Parse failure → the model's output, not the transport.
      (or (contains? data :raw-text)
          (re-find #"(?i)JSON parse failed|JSON error" text))
      {:class :malformed :reason "model returned unparseable output"}

      ;; HTTP status present: 5xx transient; 429 + other 4xx fatal.
      (some? status)
      (cond
        (>= status 500) {:class :transient :reason (str "provider error (HTTP " status ")")}
        (= status 429)  {:class :fatal     :reason "rate limited (HTTP 429)"}
        (>= status 400) {:class :fatal     :reason (str "request rejected (HTTP " status ")")}
        :else           {:class :transient :reason (str "HTTP " status)})

      (re-find fatal-error-re text)     {:class :fatal     :reason (first-line text)}
      (re-find transient-error-re text) {:class :transient :reason (first-line text)}
      ;; Unknown — treat as malformed (bounded re-prompt then abort), preserving
      ;; the prior default rather than retrying a possibly-deterministic failure.
      :else                              {:class :malformed :reason (first-line text)})))

;; ============================================================================
;; OpenAI-Compatible API
;; ============================================================================

(defn- build-openai-headers
  "Build HTTP headers for OpenAI-compatible APIs."
  [lm-config]
  (cond-> {"Content-Type" "application/json"}
    (:api-key lm-config)
    (assoc "Authorization" (str (:auth-header lm-config) " " (:api-key lm-config)))))

(defn- inject-json-schema-into-messages
  "When the provider doesn't support json_schema response_format,
   append the JSON schema instruction to the first system message."
  [messages json-schema]
  (let [schema-instruction (str "\n\nIMPORTANT: You MUST respond with ONLY a valid JSON object "
                                "matching this schema:\n"
                                (json/write-str json-schema)
                                "\nDo not include any text before or after the JSON."
                                "\nUse EXACTLY the field names specified in the schema.")]
    (if-let [idx (some (fn [[i m]] (when (= "system" (:role m)) i))
                       (map-indexed vector messages))]
      (update-in (vec messages) [idx :content] str schema-instruction)
      (into [{:role "system" :content schema-instruction}] messages))))

(defn- build-openai-body
  "Build the request body for OpenAI-compatible chat completion.
   JSON schema is already in the system prompt (injected by prompt.clj).
   For providers that support response_format, we also pass it as API-level enforcement."
  [lm-config messages {:keys [json-schema stream?]}]
  (let [use-json-schema? (and json-schema (:supports-json-schema? lm-config))]
    (let [drop? (or (:drop-params lm-config) #{})]
      (cond-> {:model    (:model lm-config)
               :messages messages}
        (not (drop? :temperature))
        (assoc :temperature (:temperature lm-config))

        (:max-tokens lm-config)
        (assoc :max_tokens (:max-tokens lm-config))

        use-json-schema?
        (assoc :response_format
               {:type        "json_schema"
                :json_schema {:name   "response"
                              :strict true
                              :schema json-schema}})

        stream?
        (->
         (assoc :stream true)
         (assoc :stream_options {:include_usage true}))))))

(defn openai-chat-completion
  "Call an OpenAI-compatible chat completion API."
  [lm-config messages opts]
  (let [url      (str (:base-url lm-config) "/chat/completions")
        headers  (build-openai-headers lm-config)
        body     (build-openai-body lm-config messages opts)
        start-ns (System/nanoTime)]
    (mulog/log ::openai-api-call
               :provider (:provider lm-config) :model (:model lm-config) :url url
               :request-body body)
    (try
      (let [response (http/post url
                                (merge {:headers            headers
                                        :body               (json/write-str body)
                                        :as                 :string
                                        :throw-exceptions   true}
                                       (proxy-opts)))
            parsed   (json/read-str (:body response) :key-fn keyword)
            usage    (:usage parsed)
            duration-ms (quot (- (System/nanoTime) start-ns) 1000000)]
        (mulog/log ::openai-api-call-result
                   :provider (:provider lm-config) :model (:model lm-config) :url url
                   :duration-ms duration-ms
                   :http-status (:status response)
                   :response-id (:id parsed)
                   :finish-reason (get-in parsed [:choices 0 :finish_reason])
                   :prompt-tokens (:prompt_tokens usage)
                   :completion-tokens (:completion_tokens usage)
                   :total-tokens (:total_tokens usage)
                   :response-body parsed)
        parsed)
      (catch Exception e
        (mulog/log ::openai-api-call-result
                   :provider (:provider lm-config) :model (:model lm-config) :url url
                   :duration-ms (quot (- (System/nanoTime) start-ns) 1000000)
                   :outcome :error
                   :exception e)
        (throw e)))))

;; ============================================================================
;; Anthropic Messages API
;; ============================================================================

(defn- convert-messages-for-anthropic
  "Convert OpenAI-style messages to Anthropic format.
   Extracts system message separately."
  [messages]
  (let [system-msgs (filter #(= "system" (:role %)) messages)
        other-msgs  (remove #(= "system" (:role %)) messages)
        system-text (when (seq system-msgs)
                      (str/join "\n\n" (map :content system-msgs)))]
    {:system   system-text
     :messages (vec other-msgs)}))

(defn- build-anthropic-system-blocks
  "Split `system-text` at cache-zone boundaries and return a vector of
   Anthropic content blocks. Each zone (a `{:key :text}` map from the
   dspy-action) becomes its own `{:type \"text\" :text ... :cache_control
   {:type \"ephemeral\"}}` block. The DSPy preamble (everything before
   the first zone) becomes a leading uncached block.

   Returns nil if any zone's text can't be located inside the system
   message — defensive fallback so callers can drop back to the plain
   string form."
  [system-text cache-zones]
  (loop [remaining system-text
         zones (vec cache-zones)
         blocks []]
    (if (empty? zones)
      (cond-> blocks
        (not (str/blank? remaining))
        (conj {:type "text" :text remaining}))
      (let [zone-text (:text (first zones))
            idx (str/index-of remaining zone-text)]
        (if idx
          (let [preamble (subs remaining 0 idx)
                rest-after (subs remaining (+ idx (count zone-text)))
                ;; Trim trailing inter-block separator from the preamble.
                preamble-clean (str/replace preamble #"\n\n\z" "")
                blocks (cond-> blocks
                         (not (str/blank? preamble-clean))
                         (conj {:type "text" :text preamble-clean}))
                blocks (conj blocks {:type "text"
                                     :text zone-text
                                     :cache_control {:type "ephemeral"}})]
            (recur (str/replace rest-after #"\A\n\n" "")
                   (vec (rest zones))
                   blocks))
          ;; zone text not found — bail out so the caller can fall back
          ;; to the unstructured string form.
          nil)))))

(defn- build-anthropic-body
  "Build the request body for Anthropic Messages API.
   JSON schema is already in the system prompt (injected by prompt.clj).
   When :prompt-cache is enabled in lm-config AND :cache-zones are
   provided (M7), the system field is emitted as a structured content
   array with `cache_control: {type: \"ephemeral\"}` on each zone block.
   Otherwise the system field is a single string (legacy behaviour)."
  [lm-config messages {:keys [json-schema stream? cache-zones]}]
  (let [{:keys [system messages]} (convert-messages-for-anthropic messages)
        prompt-cache? (:prompt-cache lm-config)
        system-blocks (when (and prompt-cache? system (seq cache-zones))
                        (or (build-anthropic-system-blocks system cache-zones)
                            ;; Zone text not found in the system message —
                            ;; cache_control markers silently dropped would
                            ;; hide a caching regression; make it loud.
                            (do (mulog/warn ::cache-zone-fallback
                                            :provider :anthropic
                                            :model (:model lm-config)
                                            :zone-keys (mapv :key cache-zones)
                                            :message (str "cache zone text not found in system"
                                                          " message — falling back to plain string"
                                                          " system (no cache breakpoints)"))
                                nil)))
        system-field  (or system-blocks system)]
    ;; Use array-map to preserve key order: model, system, messages, ...
    ;; This matches Anthropic's docs and makes request body readable in logs.
    (cond-> (if system-field
              (array-map :model      (:model lm-config)
                         :system     system-field
                         :messages   messages
                         :max_tokens (or (:max-tokens lm-config) 4096))
              (array-map :model      (:model lm-config)
                         :messages   messages
                         :max_tokens (or (:max-tokens lm-config) 4096)))
      (:temperature lm-config) (assoc :temperature (:temperature lm-config))

      stream?
      (assoc :stream true))))

(defn- build-anthropic-headers
  "Build HTTP headers for Anthropic API calls.
   Supports both API key auth (x-api-key) and OAuth bearer token (Authorization)."
  [lm-config]
  (let [base-headers {"Content-Type"      "application/json"
                      "anthropic-version"  "2023-06-01"}]
    (if (= :oauth (:auth-type lm-config))
      ;; OAuth: resolve bearer token dynamically
      (let [oauth-ns (try (require 'ai.brainyard.clj-llm.core.oauth) true
                          (catch Exception _ false))
            get-token (when oauth-ns
                        (resolve 'ai.brainyard.clj-llm.core.oauth/get-valid-access-token))
            token (when get-token ((deref get-token)))]
        (when-not token
          (throw (ex-info "OAuth authentication required. Run (oauth/authenticate!) first." {})))
        (assoc base-headers "Authorization" (str "Bearer " token)))
      ;; Standard API key auth
      (assoc base-headers "x-api-key" (:api-key lm-config)))))

(defn anthropic-chat-completion
  "Call the Anthropic Messages API."
  [lm-config messages opts]
  (let [url      (str (:base-url lm-config) "/messages")
        headers  (build-anthropic-headers lm-config)
        body     (build-anthropic-body lm-config messages opts)
        start-ns (System/nanoTime)]
    (mulog/log ::anthropic-api-call
               :provider :anthropic :model (:model lm-config) :url url
               :request-body body)
    (try
      (let [response (http/post url
                                (merge {:headers            headers
                                        :body               (json/write-str body)
                                        :as                 :string
                                        :throw-exceptions   true}
                                       (proxy-opts)))
            parsed   (json/read-str (:body response) :key-fn keyword)
            usage    (:usage parsed)
            duration-ms (quot (- (System/nanoTime) start-ns) 1000000)]
        (mulog/log ::anthropic-api-call-result
                   :provider :anthropic :model (:model lm-config) :url url
                   :duration-ms duration-ms
                   :http-status (:status response)
                   :response-id (:id parsed)
                   :response-type (:type parsed)
                   :stop-reason (:stop_reason parsed)
                   :input-tokens (:input_tokens usage)
                   :output-tokens (:output_tokens usage)
                   :cache-creation-tokens (:cache_creation_input_tokens usage)
                   :cache-read-tokens (:cache_read_input_tokens usage)
                   :response-body parsed)
        parsed)
      (catch Exception e
        (mulog/log ::anthropic-api-call-result
                   :provider :anthropic :model (:model lm-config) :url url
                   :duration-ms (quot (- (System/nanoTime) start-ns) 1000000)
                   :outcome :error
                   :exception e)
        (throw e)))))

;; ============================================================================
;; Streaming API
;; ============================================================================

(defn- openai-chat-completion-stream
  "Call an OpenAI-compatible chat completion API with streaming.
   Calls on-chunk for each content delta when on-chunk is non-nil; a nil
   on-chunk is accepted (chunks are consumed and discarded).
   Returns a reconstructed response identical to the non-streaming format."
  [lm-config messages opts on-chunk]
  (let [url      (str (:base-url lm-config) "/chat/completions")
        headers  (build-openai-headers lm-config)
        body     (build-openai-body lm-config messages (assoc opts :stream? true))
        start-ns (System/nanoTime)]
    (mulog/log ::openai-api-call
               :provider (:provider lm-config) :model (:model lm-config) :url url
               :stream true
               :request-body body)
    (let [response (http/post url
                              (merge {:headers            headers
                                      :body               (json/write-str body)
                                      :as                 :reader
                                      :throw-exceptions   true}
                                     (proxy-opts)))
          result (with-open [^BufferedReader rdr (:body response)]
                   (with-active-stream* rdr
                     (fn [] (sse/process-openai-stream rdr on-chunk))))]
      (mulog/log ::openai-api-call-result
                 :provider (:provider lm-config) :model (:model lm-config) :url url
                 :stream true
                 :duration-ms (quot (- (System/nanoTime) start-ns) 1000000)
                 :response-body result)
      result)))

(defn- anthropic-chat-completion-stream
  "Call the Anthropic Messages API with streaming.
   Calls on-chunk for each content delta when on-chunk is non-nil; a nil
   on-chunk is accepted (chunks are consumed and discarded).
   Returns a reconstructed response identical to the non-streaming format."
  [lm-config messages opts on-chunk]
  (let [url      (str (:base-url lm-config) "/messages")
        headers  (build-anthropic-headers lm-config)
        body     (build-anthropic-body lm-config messages (assoc opts :stream? true))
        start-ns (System/nanoTime)]
    (mulog/log ::anthropic-api-call
               :provider :anthropic :model (:model lm-config) :url url
               :stream true
               :request-body body)
    (let [response (http/post url
                              (merge {:headers            headers
                                      :body               (json/write-str body)
                                      :as                 :reader
                                      :throw-exceptions   true}
                                     (proxy-opts)))
          result (with-open [^BufferedReader rdr (:body response)]
                   (with-active-stream* rdr
                     (fn [] (sse/process-anthropic-stream rdr on-chunk))))]
      (mulog/log ::anthropic-api-call-result
                 :provider :anthropic :model (:model lm-config) :url url
                 :stream true
                 :duration-ms (quot (- (System/nanoTime) start-ns) 1000000)
                 :response-body result)
      result)))

;; ============================================================================
;; Unified API
;; ============================================================================

(defn chat-completion
  "Main entry point for LLM chat completion.
   Dispatches to the appropriate provider API based on :message-format.

   lm-config  - LM configuration from create-lm
   messages   - Vector of {:role \"system\"/\"user\"/\"assistant\" :content \"...\"}
   opts       - Optional keyword args:
                :json-schema    - JSON Schema for structured output
                :max-retries    - Max retry attempts (default 3)
                :usage-tracker  - Atom from create-usage-tracker (optional)
                :stream?        - Authoritative switch for SSE streaming. When
                                  explicitly passed, its value wins regardless of
                                  :on-chunk (so :stream? false forces non-streaming
                                  even if an :on-chunk is provided). When omitted,
                                  defaults to (some? on-chunk) — passing only
                                  :on-chunk still enables streaming, as before.
                :on-chunk       - Optional callback fn. Called with:
                                  {:type :content-delta :text \"...\"}  per delta
                                  {:type :done :usage {...}}           on completion
                                  Only invoked when streaming is enabled. Missing
                                  :on-chunk under :stream? true is fine — the
                                  streaming variants discard chunks silently.
                :input-token-breakdown - Per-category token breakdown map from
                                         usage/build-token-breakdown. Attached to
                                         usage-map for prompt cost attribution."
  [lm-config messages & {:keys [json-schema max-retries usage-tracker
                                stream? on-chunk
                                input-token-breakdown] :as opts}]
  (when-not lm-config
    (throw (ex-info "No LM configuration provided. Call create-lm first." {})))
  (when-not (or (:api-key lm-config) (= :oauth (:auth-type lm-config))
                (= :aws-sigv4 (:auth-type lm-config)))
    (when-not (#{:ollama :claude-code :acp :apple-fm :bedrock :free-llm} (:provider lm-config))
      (mulog/warn ::no-api-key :provider (:provider lm-config) :message "No API key found for provider")))
  (let [start-ms (System/currentTimeMillis)
        ;; Structured-output fallback for providers WITHOUT native json_schema
        ;; (:supports-json-schema? false — Bedrock/Anthropic/Ollama): append the
        ;; schema as a system-prompt instruction so the model still returns JSON.
        ;; The DSPy path (predict/CoT) already injects the schema via prompt.clj
        ;; and passes :schema-in-prompt? true, so we don't double-inject there.
        ;; Native-schema providers keep API-level enforcement (build-openai-body).
        messages (if (and json-schema
                          (not (:supports-json-schema? lm-config))
                          (not (:schema-in-prompt? opts)))
                   (inject-json-schema-into-messages messages json-schema)
                   messages)
        ;; Bedrock ConverseStream is not yet supported by cognitect aws-api
        ;; (no event-stream codec). Force non-streaming for any Bedrock LM,
        ;; even if a caller passed :stream? true or an :on-chunk callback.
        bedrock? (= :bedrock (:provider lm-config))
        ;; :stream? is authoritative when explicitly passed; otherwise fall back
        ;; to (some? on-chunk) so callers that pass only :on-chunk still stream.
        stream?  (cond
                   bedrock?                 false
                   (contains? opts :stream?) (boolean stream?)
                   :else                     (some? on-chunk))
        call-fn  (fn []
                   (if stream?
                     (case (:message-format lm-config)
                       :claude-code (claude-code/chat-completion-stream lm-config messages opts on-chunk)
                       :acp         (acp/chat-completion-stream lm-config messages opts on-chunk)
                       :anthropic   (anthropic-chat-completion-stream lm-config messages opts on-chunk)
                       :bedrock     (bedrock/chat-completion-stream lm-config messages opts on-chunk)
                       (openai-chat-completion-stream lm-config messages opts on-chunk))
                     (case (:message-format lm-config)
                       :claude-code (claude-code/chat-completion lm-config messages opts)
                       :acp         (acp/chat-completion lm-config messages opts)
                       :anthropic   (anthropic-chat-completion lm-config messages opts)
                       :bedrock     (bedrock/chat-completion lm-config messages opts)
                       (openai-chat-completion lm-config messages opts))))
        response (retry-with-backoff call-fn {:max-retries (or max-retries 3)})
        elapsed  (- (System/currentTimeMillis) start-ms)
        ;; Resolve tracker: explicit > global
        tracker  (or usage-tracker (providers/get-global-tracker))
        usage-map (cond-> (usage/build-usage-map response lm-config {:latency-ms elapsed})
                    input-token-breakdown (assoc :input-token-breakdown input-token-breakdown))]
    (when usage-map
      (mulog/log ::chat-completion
                 :provider (:provider lm-config)
                 :model (:model lm-config)
                 :message-count (count messages)
                 :temperature (:temperature lm-config)
                 :has-json-schema (boolean json-schema)
                 :total-tokens (:total-tokens usage-map)
                 :input-tokens (:input-tokens usage-map)
                 :output-tokens (:output-tokens usage-map)
                 :cost (get-in usage-map [:cost :total-cost] 0.0)
                 :latency-ms elapsed
                 :input-token-breakdown input-token-breakdown)
      (when tracker
        (usage/record-usage! tracker usage-map)))
    (cond-> response
      usage-map (assoc ::usage usage-map))))

;; ============================================================================
;; Sub-LLM Query Factories
;; ============================================================================
;;
;; Thin wrappers around chat-completion/extract-content used by callers that
;; need a single-shot (prompt [+ shared sub-context]) → answer-string contract:
;; the recursive sandbox loop, and the agent layer's `query$llm` command (which
;; accepts either a single `:prompt` or a vector `:prompts`). Pure chat-completion
;; calls — no tools, no nested iteration.

(def ^:private max-sub-context-chars
  "Maximum characters for sub-context passed to a sub-LLM query."
  500000)

(defn create-llm-query-fn
  "Create a single-shot sub-LLM query function.

   Returns a fn with two arities:
     (f prompt)             — query with prompt only
     (f prompt sub-context) — query with prompt + sub-context (truncated to ~500K chars)

   Parameters:
     lm-config      - LM configuration for sub-calls
     usage-tracker  - Shared usage tracker atom (may be nil)"
  [lm-config usage-tracker]
  (fn llm-query
    ([prompt] (llm-query prompt nil))
    ([prompt sub-context]
     (mulog/debug ::llm-query-sub-call
                  :prompt-len (count prompt)
                  :sub-context-len (when sub-context (count (str sub-context))))
     (let [content (if sub-context
                     (let [ctx-str (str sub-context)
                           truncated (subs ctx-str 0 (min max-sub-context-chars (count ctx-str)))]
                       (str "Context:\n" truncated "\n\nQuery: " prompt))
                     prompt)
           messages [{:role "system"
                      :content "Answer the query based on the provided context. Be concise and accurate."}
                     {:role "user"
                      :content content}]
           response (chat-completion lm-config messages
                                     :usage-tracker usage-tracker)]
       (extract-content response lm-config)))))

(defn create-llm-query-batched-fn
  "Create a concurrent sub-LLM query function.

   Sends multiple prompts concurrently via futures. Returns a vector of responses,
   one per prompt, in order. Reuses create-llm-query-fn internally — non-recursive.

   Parameters:
     lm-config      - LM configuration for sub-calls
     usage-tracker  - Shared usage tracker atom (may be nil)"
  [lm-config usage-tracker]
  (let [single-fn (create-llm-query-fn lm-config usage-tracker)]
    (fn llm-query-batched
      ([prompts] (llm-query-batched prompts nil))
      ([prompts sub-context]
       (when-not (sequential? prompts)
         (throw (ex-info "llm-query-batched requires a vector/list of prompts"
                         {:got (type prompts)})))
       (when (> (count prompts) 20)
         (throw (ex-info "llm-query-batched: max 20 prompts per call"
                         {:count (count prompts)})))
       (mulog/debug ::llm-query-batched :prompt-count (count prompts))
       ;; Per-future deadline so one stuck sub-LLM future can't block the whole
       ;; batch (the reduce/MAP step) indefinitely. Generous default (3 min) so
       ;; a slow-but-valid LLM call isn't cut; override via lm-config :timeout-ms.
       (let [timeout-ms (or (:timeout-ms lm-config) 180000)
             ;; Absolute deadline: futures run concurrently, so each deref
             ;; waits only until the shared deadline — total wall-clock is
             ;; bounded by timeout-ms even if every call hangs (not N×timeout).
             deadline (+ (System/currentTimeMillis) (long timeout-ms))
             futures (mapv (fn [prompt]
                             (future
                               (try
                                 (if sub-context
                                   (single-fn prompt sub-context)
                                   (single-fn prompt))
                                 (catch Exception e
                                   (str "Error: " (.getMessage e))))))
                           prompts)]
         (mapv (fn [f]
                 (let [remaining (max 0 (- deadline (System/currentTimeMillis)))
                       r (deref f remaining ::timeout)]
                   (if (= r ::timeout)
                     (do (future-cancel f)
                         (str "Error: sub-LLM call timed out after " timeout-ms "ms"))
                     r)))
               futures))))))

(defn split-lm-str
  "Split an LM identifier string into `[provider model]`, preferring the
   `provider/model` form and falling back to legacy `provider:model`. Canonical
   implementation lives in `providers/split-lm-str` (the provider registry's
   home); delegated here so `core.llm` and the interface share one splitter."
  [lm-str]
  (providers/split-lm-str lm-str))

(defn parse-lm-str
  "Parse an LM identifier string into an LM instance via `providers/create-lm`.

   The string is interpreted as `provider/model` (preferred) or, when it has
   no `/`, the legacy `provider:model`. Returns nil if the string is blank or
   `create-lm` throws — callers should fall back to a default LM in that case."
  [lm-str]
  (when (and lm-str (not (str/blank? lm-str)))
    (let [[provider model] (split-lm-str lm-str)]
      (try
        (providers/create-lm {:provider (keyword provider) :model model})
        (catch Exception _ nil)))))

;; ============================================================================
;; Embeddings
;; ============================================================================

(defn create-embedding
  "Create an embedding for a single text.
   lm-config - LM configuration from create-lm (needs :api-key, :base-url)
   text      - String to embed
   Options:
     :model         - Embedding model (default: text-embedding-ada-002)
     :return-usage? - When true, returns {:embedding [...] :usage {...}} instead of just the vector
     :usage-tracker - Atom from create-usage-tracker (optional)"
  [lm-config text & {:keys [model return-usage? usage-tracker]
                     :or   {model "text-embedding-ada-002"}}]
  (let [url     (str (:base-url lm-config) "/embeddings")
        headers (build-openai-headers lm-config)
        body    {:model model :input text}
        start-ms (System/currentTimeMillis)
        call-fn (fn []
                  (mulog/trace ::embedding-api-call
                               [:provider (:provider lm-config) :model model :url url]
                               (let [response (http/post url
                                                         (merge {:headers            headers
                                                                 :body               (json/write-str body)
                                                                 :as                 :string
                                                                 :throw-exceptions   true}
                                                                (proxy-opts)))]
                                 (json/read-str (:body response) :key-fn keyword))))
        response  (retry-with-backoff call-fn {:max-retries 3})
        elapsed   (- (System/currentTimeMillis) start-ms)
        embedding (get-in response [:data 0 :embedding])
        usage-map (usage/build-usage-map response lm-config
                                         {:latency-ms elapsed :call-type :embedding})]
    (mulog/log ::create-embedding
               :provider (:provider lm-config)
               :model model
               :dimensions (count embedding)
               :latency-ms elapsed)
    (when (and usage-map usage-tracker)
      (usage/record-usage! usage-tracker usage-map))
    (if return-usage?
      {:embedding embedding :usage usage-map}
      embedding)))

(defn create-embeddings
  "Create embeddings for multiple texts.
   lm-config - LM configuration from create-lm (needs :api-key, :base-url)
   texts     - Vector of strings to embed
   Options:
     :model         - Embedding model (default: text-embedding-ada-002)
     :return-usage? - When true, returns {:embeddings [[...]] :usage {...}} instead of just vectors
     :usage-tracker - Atom from create-usage-tracker (optional)"
  [lm-config texts & {:keys [model return-usage? usage-tracker]
                      :or   {model "text-embedding-ada-002"}}]
  (let [url     (str (:base-url lm-config) "/embeddings")
        headers (build-openai-headers lm-config)
        body    {:model model :input texts}
        start-ms (System/currentTimeMillis)
        call-fn (fn []
                  (mulog/trace ::embeddings-api-call
                               [:provider (:provider lm-config) :model model :url url
                                :text-count (count texts)]
                               (let [response (http/post url
                                                         (merge {:headers            headers
                                                                 :body               (json/write-str body)
                                                                 :as                 :string
                                                                 :throw-exceptions   true}
                                                                (proxy-opts)))]
                                 (json/read-str (:body response) :key-fn keyword))))
        response   (retry-with-backoff call-fn {:max-retries 3})
        elapsed    (- (System/currentTimeMillis) start-ms)
        embeddings (mapv :embedding (:data response))
        usage-map  (usage/build-usage-map response lm-config
                                          {:latency-ms elapsed :call-type :embedding})]
    (mulog/log ::create-embeddings
               :provider (:provider lm-config)
               :model model
               :text-count (count texts)
               :dimensions (count (first embeddings))
               :latency-ms elapsed)
    (when (and usage-map usage-tracker)
      (usage/record-usage! usage-tracker usage-map))
    (if return-usage?
      {:embeddings embeddings :usage usage-map}
      embeddings)))
