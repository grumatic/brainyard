;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

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

(defn openai-init
  "Fresh accumulator for the OpenAI fold."
  []
  {:content (StringBuilder.) :role nil :finish-reason nil :usage nil})

(defn openai-step
  "One SSE event folded into the accumulator; returns the step fn.

   Factored out of `process-openai-stream`'s inline loop so the SAME delta
   logic can be driven by either source of events — the blocking reader's seq
   or the pushed-body Flow (docs/design/functional-effect-system.md §18). A
   second copy of this logic would be a second place for the two paths to
   disagree, which is exactly what the differential guarantee exists to
   prevent.

   `on-chunk` is the side effect: called `{:type :content-delta :text …}` per
   delta, as the public contract in `interface.clj` promises. An unparseable
   event is skipped, not fatal — a provider may interleave keep-alives or
   comments, and one bad frame must not abort a stream mid-answer.

   The accumulator carries a mutable `StringBuilder`, so the fold is
   single-threaded by construction. It is reduced over a seq or a Flow, both of
   which are sequential; do not fan it out."
  [on-chunk]
  (fn [acc evt]
    (let [parsed (try
                   (json/read-str (:data evt) :key-fn keyword)
                   (catch Exception e
                     (mulog/debug ::sse-parse-error :message (.getMessage e))
                     nil))]
      (if parsed
        (let [delta         (get-in parsed [:choices 0 :delta])
              delta-content (:content delta)
              delta-role    (:role delta)
              fr            (get-in parsed [:choices 0 :finish_reason])
              evt-usage     (:usage parsed)]
          (when (and delta-content on-chunk)
            (on-chunk {:type :content-delta :text delta-content}))
          (cond-> acc
            delta-content (update :content #(.append ^StringBuilder % ^String delta-content))
            delta-role    (assoc :role delta-role)
            fr            (assoc :finish-reason fr)
            evt-usage     (assoc :usage evt-usage)))
        acc))))

(defn openai-result
  "Reconstruct the non-streaming response shape from a finished fold, and fire
   the terminal `{:type :done}`."
  [{:keys [content role finish-reason usage]} on-chunk]
  (let [result {:choices [{:message {:role (or role "assistant")
                                     :content (str content)}
                           :finish_reason (or finish-reason "stop")}]}
        result (if usage (assoc result :usage usage) result)]
    (when on-chunk
      (on-chunk {:type :done :usage usage}))
    result))

(defn process-openai-stream
  "Process an OpenAI-compatible SSE stream. Calls on-chunk for each content delta.
   Returns a reconstructed response identical to non-streaming format:
     {:choices [{:message {:role \"assistant\" :content \"full text\"} :finish_reason \"stop\"}]
      :usage {...}}"
  [^BufferedReader reader on-chunk]
  (-> (reduce (openai-step on-chunk) (openai-init) (read-sse-events reader))
      (openai-result on-chunk)))

;; ============================================================================
;; Anthropic Stream Processing
;; ============================================================================

(defn anthropic-init
  "Fresh accumulator for the Anthropic fold."
  []
  {:content (StringBuilder.) :model nil :stop-reason nil
   :input-usage nil :output-usage nil})

(defn anthropic-step
  "One Anthropic SSE event folded into the accumulator; returns the step fn.

   Same motive as `openai-step`: the delta logic must be drivable from either
   the blocking reader's seq or the pushed-body Flow, without a second copy to
   drift (docs/design/functional-effect-system.md §18).

   `message_stop` returns `reduced`. That is load-bearing rather than
   incidental: unlike the OpenAI stream, whose terminal coincides with the end
   of the events, Anthropic's terminal arrives MID-STREAM and the original
   `loop` returned from inside it — so anything after `message_stop` was never
   examined. A step that merely went quiet would keep consuming, which over a
   Flow means never completing. This is the same terminal-boundary seam that
   produced three separate bugs in §18; it is modelled explicitly here."
  [on-chunk]
  (fn [acc evt]
    (let [parsed (try
                   (json/read-str (:data evt) :key-fn keyword)
                   (catch Exception e
                     (mulog/debug ::anthropic-sse-parse-error :message (.getMessage e))
                     nil))]
      (if-not parsed
        acc
        (case (:event evt)
          "message_start"
          (let [msg (:message parsed)]
            (assoc acc :model (:model msg) :input-usage (:usage msg)))

          "content_block_delta"
          (let [delta-text (get-in parsed [:delta :text])]
            (when (and delta-text on-chunk)
              (on-chunk {:type :content-delta :text delta-text}))
            (cond-> acc
              delta-text (update :content #(.append ^StringBuilder % ^String delta-text))))

          "message_delta"
          (assoc acc :stop-reason (get-in parsed [:delta :stop_reason])
                     :output-usage (:usage parsed))

          ;; Terminal. See the docstring — this must STOP the reduction.
          "message_stop"
          (reduced acc)

          ;; content_block_start, content_block_stop, ping — skip
          acc)))))

(defn anthropic-result
  "Reconstruct the non-streaming response shape from a finished fold, and fire
   the terminal `{:type :done}`.

   Shared by both endings — `message_stop`, and a stream that simply runs out
   without one. The original built the identical map in two places; they cannot
   drift now."
  [{:keys [content model stop-reason input-usage output-usage]} on-chunk]
  (let [merged-usage (merge input-usage output-usage)
        result {:content [{:type "text" :text (str content)}]
                :model model
                :stop_reason (or stop-reason "end_turn")
                :usage merged-usage}]
    (when on-chunk
      (on-chunk {:type :done :usage merged-usage}))
    result))

(defn process-anthropic-stream
  "Process an Anthropic SSE stream. Calls on-chunk for each content delta.
   Returns a reconstructed response identical to non-streaming format:
     {:content [{:type \"text\" :text \"full text\"}]
      :usage {:input_tokens N :output_tokens N ...}
      :model \"...\" :stop_reason \"end_turn\"}"
  [^BufferedReader reader on-chunk]
  (-> (reduce (anthropic-step on-chunk) (anthropic-init) (read-sse-events reader))
      (anthropic-result on-chunk)))
