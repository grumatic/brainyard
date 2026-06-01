;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-llm.core.bedrock
  "AWS Bedrock provider — uses the Bedrock Converse API for unified
   chat completion across Anthropic / Amazon Nova / Meta Llama / Mistral /
   Cohere foundation models.

   Auth: AWS SigV4 via cognitect aws-api. Credentials resolve from
   :credentials-provider on lm-config, then :aws-profile, then the default
   AWS chain (env → system properties → profile → container → IMDS).

   Response shape: rewritten to the Anthropic message shape so the existing
   extract-anthropic-content / extract-anthropic-usage paths work unchanged."
  (:require [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Lazy AWS SDK Loading
;; ============================================================================
;; Load cognitect aws-api / aws-client only when Bedrock is actually used,
;; so projects that don't include the Bedrock dep aren't forced to.
;;
;; Use resolve+require-via-try (not requiring-resolve) so that native-image
;; binaries — where the ns is preloaded by a project-level static require but
;; the source .clj files aren't on the runtime classpath — don't fail when
;; requiring-resolve calls (require ...) and the loader looks for files.

(defn- safe-require-resolve
  "Like requiring-resolve, but tolerates missing source files when the ns
   is already loaded (the native-image case)."
  [sym]
  (let [ns-sym (symbol (namespace sym))]
    (or (resolve sym)
        (do (try (require ns-sym) (catch Throwable _ nil))
            (resolve sym)))))

(defn- aws-invoke-fn []
  (safe-require-resolve 'cognitect.aws.client.api/invoke))

(defn- aws-client-fn []
  (safe-require-resolve 'cognitect.aws.client.api/client))

(defn- aws-client-profile-creds [profile-name]
  ((safe-require-resolve 'ai.brainyard.aws-client.interface/profile-credentials-provider)
   profile-name))

;; ============================================================================
;; Client Cache
;; ============================================================================

(defonce ^:private !client-cache
  (atom {}))

(defn- resolve-credentials
  "Pick a cognitect credentials provider based on lm-config.
   Returns nil when neither an explicit provider nor profile is given —
   cognitect aws-api will then build its own default chain."
  [{:keys [credentials-provider aws-profile]}]
  (cond
    credentials-provider credentials-provider
    aws-profile          (aws-client-profile-creds aws-profile)
    :else                nil))

(defn- cache-key
  [lm-config]
  ;; If a custom provider is supplied, key by identity so each one gets its own
  ;; client. Otherwise key by [region profile-or-default] for sharing.
  [(:region lm-config)
   (or (:credentials-provider lm-config)
       (:aws-profile lm-config)
       :default)])

(defn- get-client
  "Get or create a cached cognitect aws-api Bedrock runtime client."
  [lm-config]
  (let [k (cache-key lm-config)]
    (or (get @!client-cache k)
        (let [creds   (resolve-credentials lm-config)
              base    {:api :bedrock-runtime :region (:region lm-config)}
              cfg     (cond-> base
                        creds (assoc :credentials-provider creds))
              client  ((aws-client-fn) cfg)]
          (swap! !client-cache assoc k client)
          client))))

;; ============================================================================
;; Request Shaping
;; ============================================================================

(defn- convert-messages
  "Convert OpenAI-style {:role :content} messages into Bedrock Converse format.
   System messages are pulled out separately; user/assistant messages become
   {:role role :content [{:text content}]}."
  [messages]
  (let [system-msgs (filter #(= "system" (:role %)) messages)
        other-msgs  (remove #(= "system" (:role %)) messages)
        system-text (when (seq system-msgs)
                      (str/join "\n\n" (map :content system-msgs)))]
    {:system   system-text
     :messages (mapv (fn [{:keys [role content]}]
                       {:role    role
                        :content [{:text content}]})
                     other-msgs)}))

(def ^:private cache-point-block
  {:cachePoint {:type "default"}})

;; Bedrock Converse allows up to 4 cachePoints per request. Reserve one for
;; the last user message, leaving 3 available for the system zones.
(def ^:private max-system-cache-points 3)

(defn- append-cache-point-to-last-user
  "Append a Bedrock cachePoint block to the content of the last user message,
   so the prefix through that turn is cached on the next request."
  [messages]
  (let [last-user (->> messages
                       (keep-indexed (fn [i m] (when (= "user" (:role m)) i)))
                       last)]
    (if last-user
      (update-in (vec messages) [last-user :content] conj cache-point-block)
      messages)))

(defn- system-blocks-from-zones
  "Split `system-text` at cache-zone boundaries and return Converse content
   blocks: each zone becomes a `{:text zone-text}` block followed by a
   `cachePoint`. Anything before the first zone is emitted as a leading
   uncached block.

   Mirrors `build-anthropic-system-blocks` from core.llm so the dspy-action's
   zone metadata gives Bedrock the same per-zone cache breakpoints Anthropic
   already gets via `cache_control: ephemeral`.

   Returns nil if any zone's text can't be located inside system-text — the
   caller falls back to a single trailing cachePoint block."
  [system-text cache-zones]
  (loop [remaining system-text
         zones (vec (take max-system-cache-points cache-zones))
         blocks []]
    (if (empty? zones)
      (cond-> blocks
        (not (str/blank? remaining))
        (conj {:text remaining}))
      (let [zone-text (:text (first zones))
            idx (str/index-of remaining zone-text)]
        (if idx
          (let [preamble       (subs remaining 0 idx)
                rest-after     (subs remaining (+ idx (count zone-text)))
                preamble-clean (str/replace preamble #"\n\n\z" "")
                blocks (cond-> blocks
                         (not (str/blank? preamble-clean))
                         (conj {:text preamble-clean}))
                blocks (-> blocks
                           (conj {:text zone-text})
                           (conj cache-point-block))]
            (recur (str/replace rest-after #"\A\n\n" "")
                   (vec (rest zones))
                   blocks))
          ;; Zone text not found inside remaining — bail and let the caller
          ;; fall back to a single trailing-cachePoint system block.
          nil)))))

(defn- build-system-blocks
  "Choose between zone-based multi-block system content (one cachePoint per
   stable-key zone) and a single text block + trailing cachePoint."
  [system cache? cache-zones]
  (when system
    (or (when (and cache? (seq cache-zones))
          (system-blocks-from-zones system cache-zones))
        (cond-> [{:text system}]
          cache? (conj cache-point-block)))))

(defn- build-request
  "Build a Converse-API request body from lm-config + messages.
   Honors `:drop-params` (e.g. Opus 4.7 rejects :temperature).

   When `:prompt-cache` is truthy:
   - if the caller passes `:cache-zones`, the system field is split into
     `{:text zone}` blocks separated by cachePoint markers (per-zone
     breakpoints — same shape Anthropic uses with cache_control ephemeral);
   - otherwise a single trailing cachePoint is appended to the system block.
   A cachePoint is also appended to the last user message so the
   conversation prefix through that turn is cached on the next request."
  [lm-config messages cache-zones]
  (let [{:keys [system messages]} (convert-messages messages)
        cache?    (boolean (:prompt-cache lm-config))
        drop?     (or (:drop-params lm-config) #{})
        inference (cond-> {}
                    (and (:temperature lm-config) (not (drop? :temperature)))
                    (assoc :temperature (double (:temperature lm-config)))
                    (:max-tokens lm-config)
                    (assoc :maxTokens (:max-tokens lm-config)))
        system-blocks (build-system-blocks system cache? cache-zones)
        messages' (if cache? (append-cache-point-to-last-user messages) messages)]
    (cond-> {:modelId  (:model lm-config)
             :messages messages'}
      (seq inference) (assoc :inferenceConfig inference)
      system-blocks   (assoc :system system-blocks))))

;; ============================================================================
;; Response Reshaping
;; ============================================================================

(defn- usage->anthropic-shape
  "Convert Bedrock Converse usage keys to the Anthropic-style keys used by
   extract-anthropic-usage in usage.clj."
  [usage]
  (when usage
    (cond-> {}
      (:inputTokens usage)            (assoc :input_tokens (:inputTokens usage))
      (:outputTokens usage)           (assoc :output_tokens (:outputTokens usage))
      (:totalTokens usage)            (assoc :total_tokens (:totalTokens usage))
      (:cacheReadInputTokens usage)   (assoc :cache_read_input_tokens (:cacheReadInputTokens usage))
      (:cacheWriteInputTokens usage)  (assoc :cache_creation_input_tokens (:cacheWriteInputTokens usage)))))

(defn- text-blocks
  "Extract the {:text ...} blocks from a Converse output message and shape
   them as Anthropic-style {:type \"text\" :text \"...\"}."
  [content]
  (->> content
       (filter :text)
       (mapv (fn [b] {:type "text" :text (:text b)}))))

(defn- reshape-converse-response
  "Reshape a Converse response into Anthropic Messages-API shape so that
   extract-anthropic-content / extract-anthropic-usage just work.

   Bedrock returns:
     {:output {:message {:role \"assistant\" :content [{:text \"...\"}]}}
      :stopReason ...
      :usage {:inputTokens N :outputTokens N :totalTokens N ...}}

   We project to:
     {:content [{:type \"text\" :text \"...\"}]
      :role \"assistant\"
      :stop_reason ...
      :usage {:input_tokens ... :output_tokens ... ...}
      ::raw <original response>}"
  [resp]
  (let [content (get-in resp [:output :message :content])]
    (cond-> {:content     (text-blocks content)
             :role        (or (get-in resp [:output :message :role]) "assistant")
             :stop_reason (:stopReason resp)}
      (:usage resp) (assoc :usage (usage->anthropic-shape (:usage resp)))
      true          (assoc ::raw resp))))

;; ============================================================================
;; Error / Anomaly Detection
;; ============================================================================

(defn- anomaly?
  [resp]
  (some? (:cognitect.anomalies/category resp)))

(defn- throw-on-anomaly
  [resp lm-config]
  (when (anomaly? resp)
    (throw (ex-info (str "Bedrock invoke failed: "
                         (or (:cognitect.anomalies/message resp)
                             (:message resp)
                             (:Message resp)
                             (:cognitect.aws.error/code resp)
                             "anomaly"))
                    {:provider :bedrock
                     :model    (:model lm-config)
                     :region   (:region lm-config)
                     :anomaly  resp}))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn chat-completion
  "Call Bedrock Converse for a single non-streaming completion.
   Honors `:cache-zones` in opts (per-stable-key zone metadata from the
   dspy-action) so each zone gets its own cachePoint breakpoint."
  [lm-config messages opts]
  (let [client   (get-client lm-config)
        request  (build-request lm-config messages (:cache-zones opts))
        start-ns (System/nanoTime)]
    (mulog/log ::bedrock-api-call
               :provider :bedrock :model (:model lm-config) :region (:region lm-config)
               :request-body request)
    (let [resp ((aws-invoke-fn) client {:op :Converse :request request})
          duration-ms (quot (- (System/nanoTime) start-ns) 1000000)]
      (mulog/log ::bedrock-api-call-result
                 :provider :bedrock :model (:model lm-config) :region (:region lm-config)
                 :duration-ms duration-ms
                 :stop-reason (:stopReason resp)
                 :input-tokens (get-in resp [:usage :inputTokens])
                 :output-tokens (get-in resp [:usage :outputTokens])
                 :response-body resp)
      (throw-on-anomaly resp lm-config)
      (reshape-converse-response resp))))

(defn- consume-stream-events
  "Walk a ConverseStream event sequence (or core.async channel) and accumulate
   text deltas + final usage. Calls on-chunk for each delta and on completion."
  [events on-chunk]
  (let [text-buf  (StringBuilder.)
        !usage    (volatile! nil)
        !stop     (volatile! nil)
        consume   (fn [evt]
                    (cond
                      ;; Content block delta with text
                      (and (:contentBlockDelta evt)
                           (get-in evt [:contentBlockDelta :delta :text]))
                      (let [t (get-in evt [:contentBlockDelta :delta :text])]
                        (.append text-buf ^String t)
                        (when on-chunk
                          (on-chunk {:type :content-delta :text t})))

                      ;; Stop reason on messageStop
                      (:messageStop evt)
                      (vreset! !stop (get-in evt [:messageStop :stopReason]))

                      ;; Final usage on metadata event
                      (:metadata evt)
                      (vreset! !usage (get-in evt [:metadata :usage]))

                      :else nil))
        ;; Bedrock returns the stream events in :stream as a core.async channel
        ;; in some library versions; in others it's a seq. Handle both.
        chan-> (try (safe-require-resolve 'clojure.core.async/<!!) (catch Throwable _ nil))]
    (cond
      (sequential? events)
      (doseq [evt events] (consume evt))

      ;; core.async ReadPort
      chan->
      (loop []
        (when-let [evt (chan-> events)]
          (consume evt)
          (recur)))

      :else
      (doseq [evt events] (consume evt)))
    {:text  (.toString text-buf)
     :usage @!usage
     :stop  @!stop}))

(defn chat-completion-stream
  "Call Bedrock ConverseStream and stream content deltas to on-chunk.
   Returns a reconstructed response in the Anthropic shape.

   NOTE: cognitect aws-api (as of 871.x) does NOT decode the
   `application/vnd.amazon.eventstream` framing returned by ConverseStream;
   it tries to JSON-parse the binary body and fails with a confusing
   `No matching clause: 0` from clojure.data.json. Until aws-api ships
   event-stream support (or this code switches to AWS SDK v2), streaming
   is unsupported and we fail fast with a clear error."
  [lm-config messages opts on-chunk]
  (let [client   (get-client lm-config)
        request  (build-request lm-config messages (:cache-zones opts))
        start-ns (System/nanoTime)]
    (mulog/log ::bedrock-api-call
               :provider :bedrock :model (:model lm-config) :region (:region lm-config)
               :stream true
               :request-body request)
    (let [resp   ((aws-invoke-fn) client {:op :ConverseStream :request request})
          _      (when (and (anomaly? resp)
                            (let [t (:cognitect.aws.client.impl/throwable resp)]
                              (and t (re-find #"No matching clause" (str (.getMessage ^Throwable t))))))
                   (throw (ex-info "Bedrock ConverseStream is not supported by the cognitect aws-api event-stream codec. Use chat-completion (non-streaming) for now."
                                   {:provider :bedrock :model (:model lm-config) :region (:region lm-config)
                                    :anomaly resp})))
          _      (throw-on-anomaly resp lm-config)
          stream (or (:stream resp) (:body resp))
          {:keys [text usage stop]} (consume-stream-events stream on-chunk)
          duration-ms (quot (- (System/nanoTime) start-ns) 1000000)
          reconstructed {:content     [{:type "text" :text text}]
                         :role        "assistant"
                         :stop_reason stop
                         :usage       (usage->anthropic-shape usage)}]
      (mulog/log ::bedrock-api-call-result
                 :provider :bedrock :model (:model lm-config) :region (:region lm-config)
                 :stream true
                 :duration-ms duration-ms
                 :stop-reason stop
                 :input-tokens (:inputTokens usage)
                 :output-tokens (:outputTokens usage))
      (when on-chunk
        (on-chunk {:type :done :usage (:usage reconstructed)}))
      reconstructed)))
