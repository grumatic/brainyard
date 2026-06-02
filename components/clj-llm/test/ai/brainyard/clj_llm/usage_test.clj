;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.usage-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-llm.core.usage :as usage]))

;; ============================================================================
;; Mock Responses
;; ============================================================================

(def openai-chat-response
  {:id "chatcmpl-abc123"
   :model "gpt-4o"
   :choices [{:message {:content "{\"answer\": \"42\"}"}}]
   :usage {:prompt_tokens 100
           :completion_tokens 50
           :total_tokens 150
           :prompt_tokens_details {:cached_tokens 80}}})

(def anthropic-chat-response
  {:id "msg-abc123"
   :model "claude-sonnet-4-5"
   :type "message"
   :content [{:type "text" :text "{\"answer\": \"42\"}"}]
   :usage {:input_tokens 200
           :output_tokens 75
           :cache_read_input_tokens 120
           :cache_creation_input_tokens 30}})

(def openai-embedding-response
  {:data [{:embedding [0.1 0.2 0.3]}]
   :model "text-embedding-ada-002"
   :usage {:prompt_tokens 10
           :total_tokens 10}})

;; ============================================================================
;; Extraction Tests
;; ============================================================================

(deftest extract-openai-usage-test
  (testing "Extracts token counts from OpenAI response"
    (let [usage (usage/extract-usage openai-chat-response
                                     {:message-format :openai})]
      (is (= 100 (:input-tokens usage)))
      (is (= 50 (:output-tokens usage)))
      (is (= 150 (:total-tokens usage)))
      (is (= 80 (get-in usage [:cache :read-tokens])))
      (is (= 0 (get-in usage [:cache :write-tokens]))))))

(deftest extract-anthropic-usage-test
  (testing "Extracts token counts from Anthropic response (cache folded into :input-tokens)"
    (let [usage (usage/extract-usage anthropic-chat-response
                                     {:message-format :anthropic})]
      ;; :input-tokens is the TOTAL processed input = fresh + cache-read + cache-write.
      ;; Fixture: input_tokens 200 + cache_read 120 + cache_create 30 = 350.
      (is (= 350 (:input-tokens usage)))
      (is (= 75 (:output-tokens usage)))
      (is (= 425 (:total-tokens usage)))
      (is (= 120 (get-in usage [:cache :read-tokens])))
      (is (= 30 (get-in usage [:cache :write-tokens]))))))

(deftest extract-embedding-usage-test
  (testing "Extracts token counts from embedding response"
    (let [usage (usage/extract-usage openai-embedding-response
                                     {:message-format :openai
                                      :call-type :embedding})]
      (is (= 10 (:input-tokens usage)))
      (is (= 0 (:output-tokens usage)))
      (is (= 10 (:total-tokens usage))))))

(deftest extract-nil-usage-test
  (testing "Returns nil when no usage in response"
    (is (nil? (usage/extract-usage {} {:message-format :openai})))
    (is (nil? (usage/extract-usage {:choices []} {:message-format :anthropic})))))

;; ============================================================================
;; Cost Calculation Tests
;; ============================================================================

(deftest calculate-cost-known-model-test
  (testing "Calculates cost for known model"
    (let [usage {:input-tokens 1000000
                 :output-tokens 500000
                 :cache {:read-tokens 0 :write-tokens 0}}
          cost (usage/calculate-cost usage :openai "gpt-4o")]
      (is (> (:input-cost cost) 0))
      (is (> (:output-cost cost) 0))
      ;; gpt-4o: $2.50/1M input, $10/1M output
      (is (< (Math/abs (- 2.50 (:input-cost cost))) 0.001))
      (is (< (Math/abs (- 5.00 (:output-cost cost))) 0.001))
      (is (< (Math/abs (- 7.50 (:total-cost cost))) 0.001)))))

(deftest calculate-cost-with-cache-test
  (testing "Calculates cost including cache tokens"
    (let [usage {:input-tokens 100000
                 :output-tokens 50000
                 :cache {:read-tokens 80000 :write-tokens 10000}}
          cost (usage/calculate-cost usage :anthropic "claude-sonnet-4-5")]
      (is (> (:cache-read-cost cost) 0))
      (is (> (:cache-write-cost cost) 0))
      (is (= (:total-cost cost)
             (+ (:input-cost cost)
                (:output-cost cost)
                (:cache-read-cost cost)
                (:cache-write-cost cost)))))))

(deftest calculate-cost-unknown-model-test
  (testing "Returns zero cost for unknown model without throwing"
    (let [usage {:input-tokens 100 :output-tokens 50
                 :cache {:read-tokens 0 :write-tokens 0}}
          cost (usage/calculate-cost usage :openai "unknown-model-xyz")]
      (is (= 0.0 (:total-cost cost)))
      (is (= 0.0 (:input-cost cost)))
      (is (= 0.0 (:output-cost cost))))))

(deftest calculate-cost-bedrock-test
  (testing "Bedrock cross-region inference profile resolves to base pricing"
    ;; Sonnet 4.5 on Bedrock matches direct Anthropic: $3/$15, $0.30/$3.75 cache.
    ;; Uniform convention: :input-tokens INCLUDES cache-read and cache-write.
    ;; To exercise 1M fresh + 1M cache-read + 1M cache-write, set :input-tokens 3M.
    (let [usage {:input-tokens 3000000
                 :output-tokens 1000000
                 :cache {:read-tokens 1000000 :write-tokens 1000000}}]
      (doseq [model ["us.anthropic.claude-sonnet-4-5-20250929-v1:0"
                     "global.anthropic.claude-sonnet-4-5"
                     "anthropic.claude-sonnet-4-5-20250929-v1:0"]]
        (let [cost (usage/calculate-cost usage :bedrock model)]
          (is (< (Math/abs (- 3.00  (:input-cost cost)))       0.001) model)
          (is (< (Math/abs (- 15.00 (:output-cost cost)))      0.001) model)
          (is (< (Math/abs (- 0.30  (:cache-read-cost cost)))  0.001) model)
          (is (< (Math/abs (- 3.75  (:cache-write-cost cost))) 0.001) model)
          (is (< (Math/abs (- 22.05 (:total-cost cost)))       0.001) model)))))

  (testing "Amazon Nova Lite on Bedrock resolves through -v1:0 stripping"
    (let [usage {:input-tokens 1000000 :output-tokens 1000000
                 :cache {:read-tokens 0 :write-tokens 0}}
          cost  (usage/calculate-cost usage :bedrock "amazon.nova-lite-v1:0")]
      (is (< (Math/abs (- 0.06 (:input-cost cost)))  0.001))
      (is (< (Math/abs (- 0.24 (:output-cost cost))) 0.001)))))

;; ============================================================================
;; Build Usage Map Tests
;; ============================================================================

(deftest build-usage-map-test
  (testing "Builds complete usage map"
    (let [lm-config {:model "gpt-4o" :provider :openai :message-format :openai}
          usage-map (usage/build-usage-map openai-chat-response lm-config
                                           {:latency-ms 342})]
      (is (= "gpt-4o" (:model usage-map)))
      (is (= :openai (:provider usage-map)))
      (is (= 100 (:input-tokens usage-map)))
      (is (= 50 (:output-tokens usage-map)))
      (is (= 342 (:latency-ms usage-map)))
      (is (= :chat-completion (:call-type usage-map)))
      (is (instance? java.util.Date (:timestamp usage-map)))
      (is (map? (:cost usage-map)))
      (is (> (get-in usage-map [:cost :total-cost]) 0)))))

(deftest build-usage-map-no-usage-test
  (testing "Returns nil when response has no usage"
    (is (nil? (usage/build-usage-map {} {:model "gpt-4o" :provider :openai :message-format :openai}
                                     {})))))

;; ============================================================================
;; Pricing Override Tests
;; ============================================================================

(deftest set-pricing-test
  (testing "Can override pricing for a model"
    (usage/set-pricing! {[:openai "custom-model"] {:input 1.0 :output 2.0 :cache-read 0.5 :cache-write 1.0}})
    (let [cost (usage/calculate-cost {:input-tokens 1000000 :output-tokens 1000000
                                      :cache {:read-tokens 0 :write-tokens 0}}
                                     :openai "custom-model")]
      (is (< (Math/abs (- 1.0 (:input-cost cost))) 0.001))
      (is (< (Math/abs (- 2.0 (:output-cost cost))) 0.001)))
    (usage/reset-pricing!)))

;; ============================================================================
;; Tracker Tests
;; ============================================================================

(deftest tracker-lifecycle-test
  (testing "Create, record, summary, reset lifecycle"
    (let [tracker (usage/create-usage-tracker)
          u1 {:model "gpt-4o" :provider :openai
              :input-tokens 100 :output-tokens 50 :total-tokens 150
              :cache {:read-tokens 0 :write-tokens 0}
              :cost {:input-cost 0.00025 :output-cost 0.0005
                     :cache-read-cost 0.0 :cache-write-cost 0.0
                     :total-cost 0.00075}
              :latency-ms 200 :call-type :chat-completion
              :timestamp (java.util.Date.)}
          u2 {:model "gpt-4o-mini" :provider :openai
              :input-tokens 200 :output-tokens 100 :total-tokens 300
              :cache {:read-tokens 0 :write-tokens 0}
              :cost {:input-cost 0.00003 :output-cost 0.00006
                     :cache-read-cost 0.0 :cache-write-cost 0.0
                     :total-cost 0.00009}
              :latency-ms 150 :call-type :chat-completion
              :timestamp (java.util.Date.)}]
      ;; Record usage
      (is (= u1 (usage/record-usage! tracker u1)))
      (usage/record-usage! tracker u2)
      ;; Check summary
      (let [summary (usage/get-usage-summary tracker)]
        (is (= 300 (get-in summary [:totals :input-tokens])))
        (is (= 150 (get-in summary [:totals :output-tokens])))
        (is (= 450 (get-in summary [:totals :total-tokens])))
        (is (= 2 (get-in summary [:totals :call-count])))
        (is (contains? (:by-model summary) "gpt-4o"))
        (is (contains? (:by-model summary) "gpt-4o-mini")))
      ;; Check history
      (let [history (usage/get-usage-history tracker)]
        (is (= 2 (count history)))
        ;; Most recent first
        (is (= "gpt-4o-mini" (:model (first history)))))
      ;; Filter by model
      (let [filtered (usage/get-usage-history tracker :model "gpt-4o")]
        (is (= 1 (count filtered)))
        (is (= "gpt-4o" (:model (first filtered)))))
      ;; Limit
      (let [limited (usage/get-usage-history tracker :limit 1)]
        (is (= 1 (count limited))))
      ;; Reset
      (usage/reset-tracker! tracker)
      (let [summary (usage/get-usage-summary tracker)]
        (is (= 0 (get-in summary [:totals :call-count])))
        (is (= 0 (get-in summary [:totals :input-tokens])))
        (is (empty? (:by-model summary)))))))

(deftest tracker-history-cap-test
  (testing "History is capped at specified limit"
    (let [tracker (usage/create-usage-tracker :history-cap 5)]
      ;; Record 8 entries
      (doseq [i (range 8)]
        (usage/record-usage! tracker
                             {:model (str "model-" i) :provider :openai
                              :input-tokens 10 :output-tokens 5 :total-tokens 15
                              :cache {:read-tokens 0 :write-tokens 0}
                              :cost {:total-cost 0.001}
                              :timestamp (java.util.Date.)}))
      ;; History should be capped at 5
      (let [history (usage/get-usage-history tracker)]
        (is (= 5 (count history)))
        ;; Most recent should be model-7
        (is (= "model-7" (:model (first history))))
        ;; Oldest in history should be model-3
        (is (= "model-3" (:model (last history)))))
      ;; But totals should count all 8
      (is (= 8 (get-in (usage/get-usage-summary tracker) [:totals :call-count]))))))

(deftest tracker-nil-safety-test
  (testing "Tracker functions handle nil gracefully"
    ;; record-usage! always returns usage-map (pass-through for threading)
    (is (= {:model "x"} (usage/record-usage! nil {:model "x"})))
    ;; nil usage-map returns nil
    (is (nil? (usage/record-usage! (usage/create-usage-tracker) nil)))
    (is (nil? (usage/get-usage-summary nil)))
    (is (nil? (usage/get-usage-history nil)))
    (is (nil? (usage/reset-tracker! nil)))))

(defn- entry
  [{:keys [t input-tokens iteration]}]
  (cond-> {:model "opus" :provider :anthropic
           :input-tokens input-tokens :output-tokens 10
           :total-tokens (+ input-tokens 10)
           :cache {:read-tokens 0 :write-tokens 0}
           :cost {:total-cost 0.001}
           :timestamp (java.util.Date. (long t))}
    iteration (assoc :iteration iteration)))

(deftest last-input-tokens-with-delta-test
  (testing "Returns nil when no BT-iteration calls recorded"
    (let [tracker (usage/create-usage-tracker)]
      (is (nil? (usage/last-input-tokens-with-delta [tracker])))
      ;; Sub-LLM-only entries (no :iteration) → still nil.
      (usage/record-usage! tracker (entry {:t 1 :input-tokens 4633}))
      (usage/record-usage! tracker (entry {:t 2 :input-tokens 4796}))
      (is (nil? (usage/last-input-tokens-with-delta [tracker])))))
  (testing "Delta is nil when only one BT-iteration call recorded"
    (let [tracker (usage/create-usage-tracker)]
      (usage/record-usage! tracker (entry {:t 1 :input-tokens 30000 :iteration 1}))
      (is (= {:last-input-tokens 30000 :input-tokens-delta nil}
             (usage/last-input-tokens-with-delta [tracker])))))
  (testing "Sub-LLM calls between BT iterations don't pollute the delta"
    (let [tracker (usage/create-usage-tracker)]
      (usage/record-usage! tracker (entry {:t 1 :input-tokens 30000 :iteration 1}))
      (usage/record-usage! tracker (entry {:t 2 :input-tokens 4633}))
      (usage/record-usage! tracker (entry {:t 3 :input-tokens 4796}))
      (usage/record-usage! tracker (entry {:t 4 :input-tokens 33000 :iteration 2}))
      ;; Naïve "last two by timestamp" would yield delta=33000-4796=28204.
      ;; Filtered: 33000 - 30000 = 3000.
      (is (= {:last-input-tokens 33000 :input-tokens-delta 3000}
             (usage/last-input-tokens-with-delta [tracker])))))
  (testing "Merges BT-iteration entries across multiple trackers"
    (let [t1 (usage/create-usage-tracker)
          t2 (usage/create-usage-tracker)]
      (usage/record-usage! t1 (entry {:t 1 :input-tokens 30000 :iteration 1}))
      (usage/record-usage! t2 (entry {:t 2 :input-tokens 4796})) ;; sub-LLM noise
      (usage/record-usage! t2 (entry {:t 3 :input-tokens 32500 :iteration 2}))
      (is (= {:last-input-tokens 32500 :input-tokens-delta 2500}
             (usage/last-input-tokens-with-delta [t1 t2]))))))
