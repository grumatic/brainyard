;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.acp-provider-test
  "Integration tests for the :acp clj-llm provider.

   Routes a chat-completion call through `:provider :acp :backend :stub`,
   spawning the in-tree stub agent. Verifies the response shape matches
   the claude-code provider's contract so downstream callers (predict,
   chain-of-thought) work unchanged.

   Slow (~5–10s) due to subprocess JVM startup."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-llm.core.providers :as providers]
            [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.acp :as acp]))

;; =============================================================================
;; Provider registry wiring
;; =============================================================================

(deftest acp-provider-registered-test
  (testing ":acp is in the providers registry with no auth, :acp message-format"
    (let [cfg (get providers/providers :acp)]
      (is (some? cfg))
      (is (nil? (:base-url cfg)))
      (is (nil? (:api-key-env cfg)))
      (is (= :acp (:message-format cfg)))
      (is (= :stub (:default-backend cfg))))))

(deftest create-lm-acp-test
  (testing "create-lm with :provider :acp produces a usable lm-config"
    (let [lm (providers/create-lm {:model "stub-1" :provider :acp})]
      (is (= :acp (:provider lm)))
      (is (= :acp (:message-format lm)))
      (is (= "stub-1" (:model lm)))
      (is (nil? (:api-key lm))))))

;; =============================================================================
;; Direct provider call (bypasses dispatch)
;; =============================================================================

(deftest ^:integration direct-chat-completion-test
  (testing "acp/chat-completion against the :stub backend returns text"
    (let [lm (providers/create-lm {:model "stub-1" :provider :acp})
          lm (assoc lm :backend :stub :chunk-delay-ms 5)
          response (acp/chat-completion lm
                                        [{:role "user" :content "hello acp"}]
                                        {})]
      (is (vector? (:content response)))
      (is (= "text" (-> response :content first :type)))
      (let [text (-> response :content first :text)]
        (is (string? text))
        (is (re-find #"hello" text)
            "stub echoes the user's text"))
      (is (= "end_turn" (:stop-reason response)))
      (is (= "stub-1" (:model response))))))

(deftest ^:integration direct-streaming-test
  (testing "acp/chat-completion-stream calls on-chunk for each delta"
    (let [lm (providers/create-lm {:model "stub-1" :provider :acp})
          lm (assoc lm :backend :stub :chunk-delay-ms 5)
          !chunks (atom [])
          on-chunk (fn [c] (swap! !chunks conj c))
          response (acp/chat-completion-stream lm
                                               [{:role "user" :content "stream me"}]
                                               {}
                                               on-chunk)
          chunks @!chunks
          deltas (filter #(= :content-delta (:type %)) chunks)
          done   (filter #(= :done (:type %)) chunks)]
      (is (seq deltas) "saw at least one content-delta")
      (is (= 1 (count done)) "exactly one :done at end")
      (let [streamed (->> deltas (map :text) (apply str))]
        (is (re-find #"stream" streamed)
            "the user's prompt token appears in streamed deltas")
        (is (= streamed (-> response :content first :text))
            "final response text equals concatenation of deltas")))))

;; =============================================================================
;; Through the central llm/chat-completion dispatch
;; =============================================================================

(deftest ^:integration dispatch-non-stream-test
  (testing "llm/chat-completion routes :acp message-format to acp/chat-completion"
    (let [lm (-> (providers/create-lm {:model "stub-1" :provider :acp})
                 (assoc :backend :stub :chunk-delay-ms 5))
          response (llm/chat-completion lm
                                        [{:role "user" :content "via dispatch"}])
          text (llm/extract-content response lm)]
      (is (string? text))
      (is (re-find #"dispatch" text)))))

(deftest ^:integration dispatch-streaming-test
  (testing "llm/chat-completion with on-chunk streams via :acp arm"
    (let [lm (-> (providers/create-lm {:model "stub-1" :provider :acp})
                 (assoc :backend :stub :chunk-delay-ms 5))
          !chunks (atom [])
          response (llm/chat-completion lm
                                        [{:role "user" :content "streaming dispatch"}]
                                        :on-chunk #(swap! !chunks conj %))
          deltas (filter #(= :content-delta (:type %)) @!chunks)]
      (is (some? response))
      (is (seq deltas)))))

;; =============================================================================
;; System message handling
;; =============================================================================

(deftest ^:integration system-message-included-test
  (testing "system messages are flattened into the prompt the agent receives"
    (let [lm (-> (providers/create-lm {:model "stub-1" :provider :acp})
                 (assoc :backend :stub :chunk-delay-ms 5))
          response (acp/chat-completion lm
                                        [{:role "system" :content "be brief"}
                                         {:role "user"   :content "hello"}]
                                        {})
          text (-> response :content first :text)]
      ;; The stub echoes the prompt; the system message and user message
      ;; both end up tokenized in the response.
      (is (re-find #"brief" text))
      (is (re-find #"hello" text)))))
