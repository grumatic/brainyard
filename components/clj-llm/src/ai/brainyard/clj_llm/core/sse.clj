;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-llm.core.sse
  "Server-Sent Events (SSE) parsing and stream processing for LLM APIs.
   Supports OpenAI-compatible and Anthropic streaming responses."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.io BufferedReader]))

;; ============================================================================
;; SSE Event Parsing
;; ============================================================================

(defn read-sse-events
  "Read SSE events from a BufferedReader. Returns a lazy seq of event maps.
   Each event map has :event (string, may be nil) and :data (string).
   Stops on EOF or when data is \"[DONE]\"."
  [^BufferedReader reader]
  (letfn [(read-events []
            (lazy-seq
             (loop [event-type nil
                    data-parts []]
               (when (Thread/interrupted)
                 (throw (java.io.InterruptedIOException. "SSE reader interrupted")))
               (let [line (.readLine reader)]
                 (cond
                    ;; EOF
                   (nil? line)
                   (when (seq data-parts)
                     (let [data (str/join "\n" data-parts)]
                       (when-not (= "[DONE]" data)
                         [{:event event-type :data data}])))

                    ;; Blank line = event boundary
                   (str/blank? line)
                   (if (seq data-parts)
                     (let [data (str/join "\n" data-parts)]
                       (if (= "[DONE]" data)
                         nil ;; end of stream
                         (cons {:event event-type :data data}
                               (read-events))))
                     (recur event-type data-parts))

                    ;; event: type
                   (str/starts-with? line "event:")
                   (recur (str/trim (subs line 6)) data-parts)

                    ;; data: payload
                   (str/starts-with? line "data:")
                   (let [payload (str/trim (subs line 5))]
                     (if (= "[DONE]" payload)
                        ;; Check if we have accumulated data to emit first
                       (if (seq data-parts)
                         (let [data (str/join "\n" data-parts)]
                           [{:event event-type :data data}])
                         nil)
                       (recur event-type (conj data-parts payload))))

                    ;; Comment or unknown prefix — skip
                   :else
                   (recur event-type data-parts))))))]
    (read-events)))

;; ============================================================================
;; OpenAI-Compatible Stream Processing
;; ============================================================================

(defn process-openai-stream
  "Process an OpenAI-compatible SSE stream. Calls on-chunk for each content delta.
   Returns a reconstructed response identical to non-streaming format:
     {:choices [{:message {:role \"assistant\" :content \"full text\"} :finish_reason \"stop\"}]
      :usage {...}}"
  [^BufferedReader reader on-chunk]
  (let [events (read-sse-events reader)]
    (loop [evts events
           content (StringBuilder.)
           role nil
           finish-reason nil
           usage nil]
      (if-let [evt (first evts)]
        (let [parsed (try
                       (json/read-str (:data evt) :key-fn keyword)
                       (catch Exception e
                         (mulog/debug ::sse-parse-error :message (.getMessage e))
                         nil))]
          (if parsed
            (let [delta (get-in parsed [:choices 0 :delta])
                  delta-content (:content delta)
                  delta-role (:role delta)
                  fr (get-in parsed [:choices 0 :finish_reason])
                  evt-usage (:usage parsed)]
              ;; Call on-chunk for content deltas
              (when (and delta-content on-chunk)
                (on-chunk {:type :content-delta :text delta-content}))
              (recur (rest evts)
                     (if delta-content (.append content delta-content) content)
                     (or delta-role role)
                     (or fr finish-reason)
                     (or evt-usage usage)))
            ;; Unparseable event, skip
            (recur (rest evts) content role finish-reason usage)))
        ;; Stream complete
        (let [full-text (str content)
              result {:choices [{:message {:role (or role "assistant")
                                           :content full-text}
                                 :finish_reason (or finish-reason "stop")}]}
              result (if usage (assoc result :usage usage) result)]
          (when on-chunk
            (on-chunk {:type :done :usage usage}))
          result)))))

;; ============================================================================
;; Anthropic Stream Processing
;; ============================================================================

(defn process-anthropic-stream
  "Process an Anthropic SSE stream. Calls on-chunk for each content delta.
   Returns a reconstructed response identical to non-streaming format:
     {:content [{:type \"text\" :text \"full text\"}]
      :usage {:input_tokens N :output_tokens N ...}
      :model \"...\" :stop_reason \"end_turn\"}"
  [^BufferedReader reader on-chunk]
  (let [events (read-sse-events reader)]
    (loop [evts events
           content (StringBuilder.)
           model nil
           stop-reason nil
           input-usage nil
           output-usage nil]
      (if-let [evt (first evts)]
        (let [event-type (:event evt)
              parsed (try
                       (json/read-str (:data evt) :key-fn keyword)
                       (catch Exception e
                         (mulog/debug ::anthropic-sse-parse-error :message (.getMessage e))
                         nil))]
          (if parsed
            (case event-type
              ;; message_start: contains model, usage (input_tokens)
              "message_start"
              (let [msg (:message parsed)]
                (recur (rest evts)
                       content
                       (:model msg)
                       stop-reason
                       (:usage msg)
                       output-usage))

              ;; content_block_delta: contains text deltas
              "content_block_delta"
              (let [delta-text (get-in parsed [:delta :text])]
                (when (and delta-text on-chunk)
                  (on-chunk {:type :content-delta :text delta-text}))
                (recur (rest evts)
                       (if delta-text (.append content delta-text) content)
                       model
                       stop-reason
                       input-usage
                       output-usage))

              ;; message_delta: contains stop_reason and output usage
              "message_delta"
              (recur (rest evts)
                     content
                     model
                     (get-in parsed [:delta :stop_reason])
                     input-usage
                     (:usage parsed))

              ;; message_stop: stream complete
              "message_stop"
              (let [merged-usage (merge input-usage output-usage)
                    result {:content [{:type "text" :text (str content)}]
                            :model model
                            :stop_reason (or stop-reason "end_turn")
                            :usage merged-usage}]
                (when on-chunk
                  (on-chunk {:type :done :usage merged-usage}))
                result)

              ;; content_block_start, content_block_stop, ping — skip
              (recur (rest evts)
                     content model stop-reason input-usage output-usage))
            ;; Unparseable event, skip
            (recur (rest evts) content model stop-reason input-usage output-usage)))
        ;; Stream ended without message_stop (unusual, but handle gracefully)
        (let [merged-usage (merge input-usage output-usage)
              result {:content [{:type "text" :text (str content)}]
                      :model model
                      :stop_reason (or stop-reason "end_turn")
                      :usage merged-usage}]
          (when on-chunk
            (on-chunk {:type :done :usage merged-usage}))
          result)))))
