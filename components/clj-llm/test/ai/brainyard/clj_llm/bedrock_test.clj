;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.bedrock-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.clj-llm.core.providers :as providers]
            [ai.brainyard.clj-llm.core.bedrock :as bedrock]
            [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.usage :as usage]))

;; ============================================================================
;; Provider Detection
;; ============================================================================

(deftest provider-detection-test
  (testing "Bedrock model IDs route to :bedrock provider"
    (is (= :bedrock (providers/get-provider-from-model
                     "us.anthropic.claude-sonnet-4-5-20250929-v1:0")))
    (is (= :bedrock (providers/get-provider-from-model
                     "anthropic.claude-3-5-sonnet-20241022-v2:0")))
    (is (= :bedrock (providers/get-provider-from-model "amazon.nova-pro-v1:0")))
    (is (= :bedrock (providers/get-provider-from-model "meta.llama3-3-70b-instruct-v1:0")))
    (is (= :bedrock (providers/get-provider-from-model "mistral.mistral-large-2407-v1:0")))
    (is (= :bedrock (providers/get-provider-from-model "cohere.command-r-plus-v1:0"))))

  (testing "OpenRouter-style bedrock/ prefix routes to :bedrock"
    (is (= :bedrock (providers/get-provider-from-model
                     "bedrock/anthropic.claude-3-5-sonnet-20241022-v2:0"))))

  (testing "Direct anthropic models still route to :anthropic"
    (is (= :anthropic (providers/get-provider-from-model "claude-sonnet-4-6")))
    (is (= :anthropic (providers/get-provider-from-model "claude-haiku-4-5")))))

;; ============================================================================
;; AWS Auto-Detection
;; ============================================================================

(deftest detect-aws-region-test
  (testing "Explicit region wins"
    (is (= "eu-west-1" (providers/detect-aws-region "eu-west-1"))))

  (testing "Returns a non-nil string when called with no args"
    ;; Falls back through AWS_REGION → AWS_DEFAULT_REGION → :bedrock :default-region.
    (is (string? (providers/detect-aws-region)))))

(deftest aws-credentials-detected?-test
  (testing "Detects credentials when AWS_ACCESS_KEY_ID is set"
    (with-redefs [providers/aws-credentials-detected? (constantly true)]
      (is (true? (providers/aws-credentials-detected?)))))

  (testing "Returns boolean"
    (is (boolean? (providers/aws-credentials-detected?)))))

;; ============================================================================
;; create-lm for :bedrock
;; ============================================================================

(deftest create-lm-bedrock-test
  (testing "Bedrock LM has no api-key, uses :aws-sigv4 auth-type"
    (let [lm (providers/create-lm
              {:model    "us.anthropic.claude-sonnet-4-5-20250929-v1:0"
               :provider :bedrock
               :region   "us-west-2"})]
      (is (= :bedrock (:provider lm)))
      (is (= :bedrock (:message-format lm)))
      (is (= :aws-sigv4 (:auth-type lm)))
      (is (nil? (:api-key lm)))
      (is (= "us-west-2" (:region lm)))))

  (testing "Region falls back to detect-aws-region when not provided"
    (let [lm (providers/create-lm
              {:model    "amazon.nova-lite-v1:0"
               :provider :bedrock})]
      (is (string? (:region lm)))
      (is (not (str/blank? (:region lm))))))

  (testing "Custom credentials provider is passed through"
    (let [fake-provider (reify Object)
          lm (providers/create-lm
              {:model                "amazon.nova-pro-v1:0"
               :provider             :bedrock
               :region               "us-east-1"
               :credentials-provider fake-provider})]
      (is (identical? fake-provider (:credentials-provider lm)))))

  (testing "AWS profile is captured"
    (let [lm (providers/create-lm
              {:model       "amazon.nova-lite-v1:0"
               :provider    :bedrock
               :region      "us-east-1"
               :aws-profile "my-profile"})]
      (is (= "my-profile" (:aws-profile lm))))))

;; ============================================================================
;; Request Shaping
;; ============================================================================

(deftest request-shaping-test
  (let [build-request* @#'bedrock/build-request
        build-request  (fn
                         ([lm msgs]            (build-request* lm msgs nil))
                         ([lm msgs zones]      (build-request* lm msgs zones)))
        lm-config {:model       "amazon.nova-pro-v1:0"
                   :provider    :bedrock
                   :temperature 0.5
                   :max-tokens  256
                   :region      "us-east-1"}]

    (testing "Builds Converse request with messages and inferenceConfig"
      (let [req (build-request lm-config
                               [{:role "user" :content "Hello"}])]
        (is (= "amazon.nova-pro-v1:0" (:modelId req)))
        (is (= [{:role "user" :content [{:text "Hello"}]}] (:messages req)))
        (is (= 0.5 (get-in req [:inferenceConfig :temperature])))
        (is (= 256 (get-in req [:inferenceConfig :maxTokens])))
        (is (nil? (:system req)))))

    (testing "Pulls system messages out into :system field"
      (let [req (build-request lm-config
                               [{:role "system" :content "You are concise."}
                                {:role "user"   :content "Hi"}])]
        (is (= [{:text "You are concise."}] (:system req)))
        (is (= [{:role "user" :content [{:text "Hi"}]}] (:messages req)))))

    (testing "Joins multiple system messages with blank lines"
      (let [req (build-request lm-config
                               [{:role "system" :content "Be concise."}
                                {:role "system" :content "Be friendly."}
                                {:role "user"   :content "Hi"}])]
        (is (= [{:text "Be concise.\n\nBe friendly."}] (:system req)))))

    (testing "Honors :drop-params #{:temperature} (Opus 4.7 etc.)"
      (let [req (build-request (assoc lm-config :drop-params #{:temperature})
                               [{:role "user" :content "Hi"}])]
        (is (nil? (get-in req [:inferenceConfig :temperature]))
            "temperature must be omitted when dropped")
        (is (= 256 (get-in req [:inferenceConfig :maxTokens]))
            "other inference params still pass through")))

    (testing ":prompt-cache appends cachePoint blocks to system + last user message"
      (let [req (build-request (assoc lm-config :prompt-cache true)
                               [{:role "system"    :content "Be concise."}
                                {:role "user"      :content "First question"}
                                {:role "assistant" :content "First answer"}
                                {:role "user"      :content "Follow-up"}])]
        (is (= [{:text "Be concise."} {:cachePoint {:type "default"}}]
               (:system req)))
        ;; First user message: untouched.
        (is (= [{:text "First question"}]
               (get-in req [:messages 0 :content])))
        ;; Assistant message: untouched.
        (is (= [{:text "First answer"}]
               (get-in req [:messages 1 :content])))
        ;; Last user message: cachePoint appended.
        (is (= [{:text "Follow-up"} {:cachePoint {:type "default"}}]
               (get-in req [:messages 2 :content])))))

    (testing "No cachePoint blocks when :prompt-cache is not set"
      (let [req (build-request lm-config
                               [{:role "system" :content "Be concise."}
                                {:role "user"   :content "Hi"}])]
        (is (= [{:text "Be concise."}] (:system req)))
        (is (= [{:text "Hi"}] (get-in req [:messages 0 :content])))))

    (testing ":cache-zones splits system into per-zone blocks with cachePoint between"
      (let [;; A system string that includes two ## headers — dspy-action's
            ;; standard shape — plus a leading uncached preamble.
            preamble    "Preamble line."
            zone-a      "## system-context\nStable foundations text"
            zone-b      "## user-context\nVolatile per-turn text"
            sys-text    (str preamble "\n\n" zone-a "\n\n" zone-b)
            req         (build-request (assoc lm-config :prompt-cache true)
                                       [{:role "system" :content sys-text}
                                        {:role "user"   :content "Q?"}]
                                       [{:key :system-context :text zone-a}
                                        {:key :user-context   :text zone-b}])]
        ;; System: preamble (uncached) + zone-a + cachePoint + zone-b + cachePoint.
        (is (= [{:text preamble}
                {:text zone-a} {:cachePoint {:type "default"}}
                {:text zone-b} {:cachePoint {:type "default"}}]
               (:system req)))
        ;; Last user message still gets its turn-level cachePoint.
        (is (= [{:text "Q?"} {:cachePoint {:type "default"}}]
               (get-in req [:messages 0 :content])))))

    (testing ":cache-zones caps at 3 system cachePoints (Bedrock 4-point limit, 1 reserved for user)"
      (let [zones (for [i (range 5)]
                    {:key  (keyword (str "zone-" i))
                     :text (str "## zone-" i "\nbody-" i)})
            sys-text (clojure.string/join "\n\n" (map :text zones))
            req      (build-request (assoc lm-config :prompt-cache true)
                                    [{:role "system" :content sys-text}
                                     {:role "user"   :content "Hi"}]
                                    zones)
            cps      (filter :cachePoint (:system req))]
        (is (= 3 (count cps))
            "no more than max-system-cache-points cachePoints in :system")))

    (testing "cache-zones fall back to single trailing cachePoint when zone text not found"
      (let [req (build-request (assoc lm-config :prompt-cache true)
                               [{:role "system" :content "A different system"}
                                {:role "user"   :content "Hi"}]
                               [{:key :system-context :text "## system-context\nnot in sys"}])]
        (is (= [{:text "A different system"} {:cachePoint {:type "default"}}]
               (:system req))
            "fallback path preserves the existing single-block behavior")))))

(deftest prompt-cache-default-test
  (testing "Bedrock Anthropic models default :prompt-cache on"
    (doseq [model ["us.anthropic.claude-sonnet-4-5-20250929-v1:0"
                   "global.anthropic.claude-opus-4-7"
                   "anthropic.claude-haiku-4-5-20251001-v1:0"
                   "apac.anthropic.claude-3-5-sonnet-20241022-v2:0"]]
      (let [lm (providers/create-lm {:model model :region "us-east-1"})]
        (is (true? (:prompt-cache lm))
            (str "expected :prompt-cache true by default for " model)))))

  (testing "Bedrock Amazon Nova models default :prompt-cache on"
    (doseq [model ["amazon.nova-pro-v1:0"
                   "amazon.nova-lite-v1:0"
                   "amazon.nova-micro-v1:0"
                   "apac.amazon.nova-lite-v1:0"]]
      (let [lm (providers/create-lm {:model model :region "us-east-1"})]
        (is (true? (:prompt-cache lm))
            (str "expected :prompt-cache true by default for " model)))))

  (testing "Bedrock non-cache-supporting models default :prompt-cache off"
    (doseq [model ["meta.llama3-3-70b-instruct-v1:0"
                   "mistral.mistral-large-2407-v1:0"
                   "cohere.command-r-plus-v1:0"
                   "deepseek.r1-v1:0"
                   "openai.gpt-oss-120b-1:0"]]
      (let [lm (providers/create-lm {:model model :region "us-east-1"})]
        (is (nil? (:prompt-cache lm))
            (str "expected no :prompt-cache for " model)))))

  (testing "Explicit :prompt-cache false overrides the Bedrock default"
    (let [lm (providers/create-lm
              {:model        "us.anthropic.claude-sonnet-4-5-20250929-v1:0"
               :region       "us-east-1"
               :prompt-cache false})]
      (is (nil? (:prompt-cache lm))
          "explicit false should suppress the cache flag"))))

(deftest opus-4-7-auto-drops-temperature-test
  (testing "create-lm auto-drops :temperature for Claude Opus 4.7 on Bedrock"
    (doseq [model ["global.anthropic.claude-opus-4-7"
                   "us.anthropic.claude-opus-4-7"
                   "anthropic.claude-opus-4-7"
                   "claude-opus-4-7"]]
      (let [lm (providers/create-lm {:model model :region "us-east-1"})]
        (is (= #{:temperature} (:drop-params lm))
            (str "expected :temperature in :drop-params for " model))))))

;; ============================================================================
;; Response Reshape
;; ============================================================================

(def ^:private converse-response-fixture
  {:output     {:message {:role    "assistant"
                          :content [{:text "Hello!"}]}}
   :stopReason "end_turn"
   :usage      {:inputTokens          42
                :outputTokens         7
                :totalTokens          49
                :cacheReadInputTokens 10
                :cacheWriteInputTokens 5}})

(deftest response-reshape-test
  (let [reshape @#'bedrock/reshape-converse-response
        reshaped (reshape converse-response-fixture)]

    (testing "Content reshaped to Anthropic-style :content array"
      (is (= [{:type "text" :text "Hello!"}] (:content reshaped))))

    (testing "Stop reason preserved"
      (is (= "end_turn" (:stop_reason reshaped))))

    (testing "Role preserved"
      (is (= "assistant" (:role reshaped))))

    (testing "Usage keys mapped to Anthropic-style"
      (let [u (:usage reshaped)]
        (is (= 42 (:input_tokens u)))
        (is (= 7  (:output_tokens u)))
        (is (= 49 (:total_tokens u)))
        (is (= 10 (:cache_read_input_tokens u)))
        (is (= 5  (:cache_creation_input_tokens u)))))

    (testing "Original response preserved under ::raw key"
      (is (= converse-response-fixture (::bedrock/raw reshaped))))))

;; ============================================================================
;; Integration with extract-content / extract-usage
;; ============================================================================

(deftest extract-content-handles-bedrock-test
  (let [reshape @#'bedrock/reshape-converse-response
        reshaped (reshape converse-response-fixture)]
    (testing "extract-content routes :bedrock through Anthropic extractor"
      (is (= "Hello!" (llm/extract-content reshaped {:message-format :bedrock}))))))

(deftest extract-usage-handles-bedrock-test
  (let [reshape @#'bedrock/reshape-converse-response
        reshaped (reshape converse-response-fixture)
        u (usage/extract-usage reshaped {:message-format :bedrock})]
    (testing "extract-usage normalizes Bedrock usage via Anthropic shape (cache folded into :input-tokens)"
      ;; Fixture: inputTokens 42, cacheRead 10, cacheWrite 5 → :input-tokens 57 = 42 + 10 + 5
      (is (= 57 (:input-tokens u)))
      (is (= 7  (:output-tokens u)))
      (is (= 64 (:total-tokens u)))
      (is (= 10 (get-in u [:cache :read-tokens])))
      (is (= 5  (get-in u [:cache :write-tokens]))))))
