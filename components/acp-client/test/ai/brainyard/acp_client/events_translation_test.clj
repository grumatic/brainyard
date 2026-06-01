;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.acp-client.events-translation-test
  "Pure-data tests for the ACP session/update → hook event bridge.
   No I/O, no subprocess. Verifies the translation table from §4.2.1."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.acp-client.core.events :as events]
            [ai.brainyard.acp-client.core.callbacks :as callbacks]))

(deftest agent-message-chunk-test
  (testing "agent_message_chunk → :agent.dspy-action/chunk with the text"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :sessionUpdate "agent_message_chunk"
            :content {:type "text" :text "hello"}})]
      (is (= :agent.dspy-action/chunk event))
      (is (= "hello" (:chunk data)))
      (is (= "s1" (:session-id data))))))

(deftest agent-thought-chunk-test
  (testing "agent_thought_chunk marks meta with :kind :thought"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :sessionUpdate "agent_thought_chunk"
            :content {:type "text" :text "thinking..."}})]
      (is (= :agent.dspy-action/chunk event))
      (is (= "thinking..." (:chunk data)))
      (is (= :thought (-> data :meta :kind))))))

(deftest plan-test
  (testing "plan → :todo/updated with normalized entries"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :sessionUpdate "plan"
            :entries [{:content "step 1" :status "in_progress"}
                      {:content "step 2"}]})]
      (is (= :todo/updated event))
      (is (= 2 (count (:todo-list data))))
      (is (= "step 1" (-> data :todo-list first :content)))
      (is (= "in_progress" (-> data :todo-list first :status)))
      (is (= "pending" (-> data :todo-list second :status))
          "default status is pending"))))

(deftest tool-call-test
  (testing "tool_call → :agent.tool-use/pre (observer)"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :sessionUpdate "tool_call"
            :toolCall {:toolCallId "tc-1"
                       :title "Read file"
                       :kind "read"
                       :status "in_progress"
                       :rawInput {:path "/tmp/foo"}}})]
      (is (= :agent.tool-use/pre event))
      (is (= "tc-1" (:tool-call-id data)))
      (is (= "Read file" (:tool-name data)))
      (is (= {:path "/tmp/foo"} (:tool-args data)))
      (is (true? (:observer? data))))))

(deftest tool-call-update-completed-test
  (testing "tool_call_update with completed → :agent.tool-use/post"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :sessionUpdate "tool_call_update"
            :toolCall {:toolCallId "tc-1"
                       :status "completed"
                       :content [{:type "text" :text "ok"}]}})]
      (is (= :agent.tool-use/post event))
      (is (= "tc-1" (:tool-call-id data)))
      (is (= "completed" (-> data :result :status))))))

(deftest tool-call-update-failed-test
  (testing "tool_call_update with failed → :agent.tool-use/post with :error"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :sessionUpdate "tool_call_update"
            :toolCall {:toolCallId "tc-1"
                       :status "failed"
                       :content [{:type "text" :text "permission denied"}]}})]
      (is (= :agent.tool-use/post event))
      (is (= "failed" (-> data :result :status)))
      (is (some? (-> data :result :error))))))

(deftest tool-call-update-in-progress-test
  (testing "tool_call_update with in_progress → no event (observer-only)"
    (is (nil?
         (events/translate-update
          {:sessionId "s1"
           :sessionUpdate "tool_call_update"
           :toolCall {:toolCallId "tc-1"
                      :status "in_progress"}})))))

(deftest unknown-update-kind-test
  (testing "unknown sessionUpdate variant → nil"
    (is (nil?
         (events/translate-update
          {:sessionId "s1"
           :sessionUpdate "future_variant_we_dont_know"
           :something "else"})))))

(deftest stop-reason-translation-test
  (testing "end_turn → :agent.iteration/post with :goal-achieved true"
    (let [{:keys [event data]} (events/translate-stop-reason "end_turn" "s1")]
      (is (= :agent.iteration/post event))
      (is (true? (:goal-achieved data)))
      (is (= "s1" (:session-id data)))))

  (testing "cancelled → :agent.iteration/exhausted with :reason :cancelled"
    (let [{:keys [event data]} (events/translate-stop-reason "cancelled" "s1")]
      (is (= :agent.iteration/exhausted event))
      (is (= :cancelled (:reason data)))))

  (testing "max_tokens / max_turn_requests / refusal → :iteration/exhausted"
    (doseq [reason ["max_tokens" "max_turn_requests" "refusal"]]
      (let [{:keys [event data]} (events/translate-stop-reason reason "s1")]
        (is (= :agent.iteration/exhausted event))
        (is (= (keyword reason) (:reason data))))))

  (testing "unknown stop reason → nil"
    (is (nil? (events/translate-stop-reason "novel_reason" "s1")))))

(deftest pick-option-id-test
  (testing "fallback policy from §9.2 decision 4"
    (let [opts [{:optionId "allow_once"   :name "Allow once"}
                {:optionId "allow_always" :name "Allow always"}
                {:optionId "reject_once"  :name "Reject once"}
                {:optionId "reject_always" :name "Reject always"}]]
      (is (= "allow_once" (callbacks/pick-option-id :allow opts)))
      (is (= "reject_once" (callbacks/pick-option-id :block opts)))
      (is (= "allow_once" (callbacks/pick-option-id :replace opts)))))

  (testing "non-canonical option ids fall back to first allow_/reject_ prefix"
    (let [opts [{:optionId "allow_now"  :name "Allow now"}
                {:optionId "reject_perm" :name "Reject permanently"}]]
      (is (= "allow_now"  (callbacks/pick-option-id :allow opts)))
      (is (= "reject_perm" (callbacks/pick-option-id :block opts)))))

  (testing "no prefix matches → first option"
    (let [opts [{:optionId "yes" :name "Yes"}
                {:optionId "no"  :name "No"}]]
      (is (= "yes" (callbacks/pick-option-id :allow opts)))
      (is (= "yes" (callbacks/pick-option-id :block opts)))))

  (testing "empty options → nil"
    (is (nil? (callbacks/pick-option-id :allow [])))
    (is (nil? (callbacks/pick-option-id :block [])))))
