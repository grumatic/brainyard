;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.sse-test
  "Tests for SSE parsing and stream processing."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.clj-llm.core.sse :as sse])
  (:import [java.io BufferedReader StringReader]))

(defn- str->reader
  "Create a BufferedReader from a string for testing."
  ^BufferedReader [^String s]
  (BufferedReader. (StringReader. s)))

;; ============================================================================
;; read-sse-events
;; ============================================================================

(deftest read-sse-events-basic-test
  (testing "Parses simple SSE events separated by blank lines"
    (let [input "data: {\"a\":1}\n\ndata: {\"b\":2}\n\n"
          events (sse/read-sse-events (str->reader input))]
      (is (= [{:event nil :data "{\"a\":1}"}
              {:event nil :data "{\"b\":2}"}]
             (vec events)))))

  (testing "Parses events with event type"
    (let [input "event: message_start\ndata: {\"type\":\"start\"}\n\nevent: content_block_delta\ndata: {\"type\":\"delta\"}\n\n"
          events (sse/read-sse-events (str->reader input))]
      (is (= [{:event "message_start" :data "{\"type\":\"start\"}"}
              {:event "content_block_delta" :data "{\"type\":\"delta\"}"}]
             (vec events)))))

  (testing "Stops on [DONE] sentinel"
    (let [input "data: {\"a\":1}\n\ndata: [DONE]\n\n"
          events (sse/read-sse-events (str->reader input))]
      (is (= [{:event nil :data "{\"a\":1}"}]
             (vec events)))))

  (testing "Handles EOF without trailing blank line"
    (let [input "data: {\"a\":1}"
          events (sse/read-sse-events (str->reader input))]
      (is (= [{:event nil :data "{\"a\":1}"}]
             (vec events)))))

  (testing "Skips comment lines"
    (let [input ": this is a comment\ndata: {\"a\":1}\n\n"
          events (sse/read-sse-events (str->reader input))]
      (is (= [{:event nil :data "{\"a\":1}"}]
             (vec events)))))

  (testing "Empty stream returns empty seq"
    (let [events (sse/read-sse-events (str->reader ""))]
      (is (= [] (vec events)))))

  (testing "Skips leading blank lines"
    (let [input "\n\ndata: {\"a\":1}\n\n"
          events (sse/read-sse-events (str->reader input))]
      (is (= [{:event nil :data "{\"a\":1}"}]
             (vec events))))))

;; ============================================================================
;; process-openai-stream
;; ============================================================================

(deftest process-openai-stream-test
  (testing "Reconstructs full response from OpenAI-compatible SSE stream"
    (let [input (str "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"},\"index\":0}]}\n\n"
                     "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"index\":0}]}\n\n"
                     "data: {\"choices\":[{\"delta\":{\"content\":\" world\"},\"index\":0}]}\n\n"
                     "data: {\"choices\":[{\"delta\":{},\"index\":0,\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}\n\n"
                     "data: [DONE]\n\n")
          chunks (atom [])
          result (sse/process-openai-stream
                  (str->reader input)
                  (fn [chunk] (swap! chunks conj chunk)))]
      ;; Verify reconstructed response shape
      (is (= "assistant" (get-in result [:choices 0 :message :role])))
      (is (= "Hello world" (get-in result [:choices 0 :message :content])))
      (is (= "stop" (get-in result [:choices 0 :finish_reason])))
      (is (= {:prompt_tokens 10 :completion_tokens 2 :total_tokens 12}
             (:usage result)))
      ;; Verify on-chunk callbacks
      (is (= 3 (count @chunks)))  ; 2 content-deltas + 1 done
      (is (= {:type :content-delta :text "Hello"} (nth @chunks 0)))
      (is (= {:type :content-delta :text " world"} (nth @chunks 1)))
      (is (= :done (:type (nth @chunks 2))))))

  (testing "Handles stream without usage data"
    (let [input (str "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"},\"index\":0}]}\n\n"
                     "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"index\":0}]}\n\n"
                     "data: {\"choices\":[{\"delta\":{},\"index\":0,\"finish_reason\":\"stop\"}]}\n\n"
                     "data: [DONE]\n\n")
          result (sse/process-openai-stream (str->reader input) nil)]
      (is (= "Hi" (get-in result [:choices 0 :message :content])))
      (is (nil? (:usage result)))))

  (testing "Handles empty content stream"
    (let [input "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"},\"index\":0}]}\n\ndata: {\"choices\":[{\"delta\":{},\"index\":0,\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
          result (sse/process-openai-stream (str->reader input) nil)]
      (is (= "" (get-in result [:choices 0 :message :content])))))

  (testing "Handles malformed SSE data gracefully"
    (let [input (str "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"index\":0}]}\n\n"
                     "data: not-json\n\n"
                     "data: {\"choices\":[{\"delta\":{\"content\":\"!\"},\"index\":0}]}\n\n"
                     "data: [DONE]\n\n")
          result (sse/process-openai-stream (str->reader input) nil)]
      (is (= "ok!" (get-in result [:choices 0 :message :content]))))))

;; ============================================================================
;; process-anthropic-stream
;; ============================================================================

(deftest process-anthropic-stream-test
  (testing "Reconstructs full response from Anthropic SSE stream"
    (let [input (str "event: message_start\n"
                     "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-sonnet-4-20250514\",\"usage\":{\"input_tokens\":25,\"output_tokens\":0}}}\n\n"
                     "event: content_block_start\n"
                     "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                     "event: content_block_delta\n"
                     "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n"
                     "event: content_block_delta\n"
                     "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" there\"}}\n\n"
                     "event: content_block_stop\n"
                     "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                     "event: message_delta\n"
                     "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":8}}\n\n"
                     "event: message_stop\n"
                     "data: {\"type\":\"message_stop\"}\n\n")
          chunks (atom [])
          result (sse/process-anthropic-stream
                  (str->reader input)
                  (fn [chunk] (swap! chunks conj chunk)))]
      ;; Verify reconstructed response shape
      (is (= [{:type "text" :text "Hello there"}] (:content result)))
      (is (= "claude-sonnet-4-20250514" (:model result)))
      (is (= "end_turn" (:stop_reason result)))
      (is (= 25 (get-in result [:usage :input_tokens])))
      (is (= 8 (get-in result [:usage :output_tokens])))
      ;; Verify on-chunk callbacks
      (is (= 3 (count @chunks)))  ; 2 content-deltas + 1 done
      (is (= {:type :content-delta :text "Hello"} (nth @chunks 0)))
      (is (= {:type :content-delta :text " there"} (nth @chunks 1)))
      (is (= :done (:type (nth @chunks 2))))))

  (testing "Handles stream ending without message_stop"
    (let [input (str "event: message_start\n"
                     "data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-sonnet-4-20250514\",\"usage\":{\"input_tokens\":10}}}\n\n"
                     "event: content_block_delta\n"
                     "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"partial\"}}\n\n")
          chunks (atom [])
          result (sse/process-anthropic-stream
                  (str->reader input)
                  (fn [chunk] (swap! chunks conj chunk)))]
      (is (= "partial" (get-in result [:content 0 :text])))
      (is (= :done (:type (last @chunks))))))

  (testing "Handles empty content stream"
    (let [input (str "event: message_start\n"
                     "data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-sonnet-4-20250514\",\"usage\":{\"input_tokens\":5}}}\n\n"
                     "event: message_delta\n"
                     "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":0}}\n\n"
                     "event: message_stop\n"
                     "data: {\"type\":\"message_stop\"}\n\n")
          result (sse/process-anthropic-stream (str->reader input) nil)]
      (is (= "" (get-in result [:content 0 :text])))
      (is (= "end_turn" (:stop_reason result))))))

;; ============================================================================
;; Edge Cases
;; ============================================================================

(deftest stream-edge-cases-test
  (testing "OpenAI stream with nil on-chunk still returns response"
    (let [input (str "data: {\"choices\":[{\"delta\":{\"content\":\"test\"},\"index\":0}]}\n\n"
                     "data: [DONE]\n\n")
          result (sse/process-openai-stream (str->reader input) nil)]
      (is (= "test" (get-in result [:choices 0 :message :content])))))

  (testing "Anthropic stream with nil on-chunk still returns response"
    (let [input (str "event: message_start\n"
                     "data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-sonnet-4-20250514\",\"usage\":{\"input_tokens\":5}}}\n\n"
                     "event: content_block_delta\n"
                     "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"test\"}}\n\n"
                     "event: message_stop\n"
                     "data: {\"type\":\"message_stop\"}\n\n")
          result (sse/process-anthropic-stream (str->reader input) nil)]
      (is (= "test" (get-in result [:content 0 :text])))))

  (testing "Chunk accumulation tracks all deltas"
    (let [input (str "data: {\"choices\":[{\"delta\":{\"content\":\"a\"},\"index\":0}]}\n\n"
                     "data: {\"choices\":[{\"delta\":{\"content\":\"b\"},\"index\":0}]}\n\n"
                     "data: {\"choices\":[{\"delta\":{\"content\":\"c\"},\"index\":0}]}\n\n"
                     "data: [DONE]\n\n")
          chunks (atom [])
          result (sse/process-openai-stream
                  (str->reader input)
                  (fn [chunk] (swap! chunks conj chunk)))]
      (is (= "abc" (get-in result [:choices 0 :message :content])))
      (is (= ["a" "b" "c"]
             (mapv :text (filter #(= :content-delta (:type %)) @chunks)))))))

;; ============================================================================
;; Accumulator coverage — added after a negative control found this UNTESTED
;; ============================================================================

(deftest openai-fold-accumulates-role-finish-reason-and-usage
  (testing "the non-content fields the fold carries across events

           Six existing call sites exercise process-openai-stream, and NONE of
           them would fail if role, finish_reason or usage stopped
           accumulating: they either assert only on content, or use streams
           whose values happen to match the defaults (role \"assistant\",
           finish_reason \"stop\"). A negative control that broke finish-reason
           accumulation passed the whole suite. These assert the fold actually
           carries them."
    (let [input (str "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}\n\n"
                     "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}],"
                     "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}\n\n"
                     "data: [DONE]\n\n")
          result (sse/process-openai-stream (str->reader input) nil)]
      (is (= "length" (get-in result [:choices 0 :finish_reason]))
          "finish_reason must come from the stream, not the \"stop\" default")
      (is (= {:prompt_tokens 7 :completion_tokens 3} (:usage result))
          "usage must be carried out of the fold")
      (is (= "hi" (get-in result [:choices 0 :message :content]))))))

(deftest openai-fold-carries-a-non-default-role
  (testing "role is taken from the delta rather than defaulted"
    (let [input (str "data: {\"choices\":[{\"delta\":{\"role\":\"developer\",\"content\":\"x\"}}]}\n\n"
                     "data: [DONE]\n\n")
          result (sse/process-openai-stream (str->reader input) nil)]
      (is (= "developer" (get-in result [:choices 0 :message :role]))))))

(deftest openai-done-chunk-carries-usage
  (testing "the terminal {:type :done} reports the usage the fold collected"
    (let [input (str "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}],"
                     "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}\n\n"
                     "data: [DONE]\n\n")
          !chunks (atom [])]
      (sse/process-openai-stream (str->reader input) #(swap! !chunks conj %))
      (is (= [{:type :content-delta :text "a"}
              {:type :done :usage {:prompt_tokens 1 :completion_tokens 1}}]
             @!chunks)))))

(deftest anthropic-fold-carries-a-non-default-stop-reason
  (testing "stop_reason must come from message_delta, not the end_turn default

           A negative control that dropped stop-reason accumulation passed the
           whole suite — every existing Anthropic stream here either omits it
           or uses \"end_turn\", which is also the default."
    (let [input (str "event: message_start\ndata: {\"message\":{\"model\":\"m\"}}\n\n"
                     "event: content_block_delta\ndata: {\"delta\":{\"text\":\"x\"}}\n\n"
                     "event: message_delta\ndata: {\"delta\":{\"stop_reason\":\"max_tokens\"}}\n\n"
                     "event: message_stop\ndata: {}\n\n")
          result (sse/process-anthropic-stream (str->reader input) nil)]
      (is (= "max_tokens" (:stop_reason result))))))

(deftest anthropic-message-stop-terminates-the-stream
  (testing "events after message_stop are NOT processed

           The Anthropic terminal arrives mid-stream, unlike OpenAI's. The
           original loop returned from inside message_stop; the fold models
           that with `reduced`. Nothing tested it — a control that made
           message_stop non-terminating passed the suite — and this is the
           exact seam that produced three separate bugs in the pushed-body
           work: a terminal that does not actually terminate."
    (let [input (str "event: message_start\ndata: {\"message\":{\"model\":\"m\"}}\n\n"
                     "event: content_block_delta\ndata: {\"delta\":{\"text\":\"kept\"}}\n\n"
                     "event: message_stop\ndata: {}\n\n"
                     "event: content_block_delta\ndata: {\"delta\":{\"text\":\"IGNORED\"}}\n\n")
          !chunks (atom [])
          result (sse/process-anthropic-stream (str->reader input) #(swap! !chunks conj %))]
      (is (= "kept" (get-in result [:content 0 :text]))
          "text after message_stop must not reach the response")
      (is (= [{:type :content-delta :text "kept"}
              {:type :done :usage nil}]
             @!chunks)
          "nor reach on-chunk"))))
