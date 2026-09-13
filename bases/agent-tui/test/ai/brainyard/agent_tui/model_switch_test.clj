;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.model-switch-test
  "Regression tests for `/model` provider switching (commands/switch-model!).

   Bug it guards against: switching to a provider that wasn't in a hardcoded
   env-var map (e.g. :free-llm, :mistral, …) carried the PREVIOUS provider's
   api-key over, so an OpenAI key was sent to free-llm as a Bearer token → 401.
   The fix delegates key resolution to clj-llm/create-lm (provider catalog's
   :api-key-env), reusing the current key only when the provider is unchanged."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-llm.interface :as llm]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.commands :as commands]
            [ai.brainyard.agent-tui-persist.interface :as persist]))

(def ^:private switch-model! #'commands/switch-model!)

(defn- capture-switch
  "Drive switch-model! with `current-lm` as the active LM and `model-name` as
   the /model argument. Returns the option map handed to create-lm (what the
   new LM is built from). All real side effects are stubbed. `popular` stands
   in for the curated catalog; the provider guess mimics the real one's
   \"contains claude -> anthropic\" fallback."
  ([current-lm model-name] (capture-switch current-lm model-name []))
  ([current-lm model-name popular]
  (let [captured (atom nil)]
    (with-redefs [llm/get-default-lm          (fn [] current-lm)
                  llm/get-popular-models      (fn [] popular)
                  llm/get-provider-from-model (fn [m] (cond (= m "auto") :free-llm
                                                            (re-find #"claude|sonnet" m) :anthropic
                                                            :else :openai))
                  llm/create-lm               (fn [opts] (reset! captured opts) opts)
                  llm/configure-default-lm!   (fn [_] nil)
                  tui-session/emit!           (fn [_] nil)
                  tui-session/update-status-bar! (fn [] nil)
                  ;; switch-model! persists {:model :provider} for the ACTIVE
                  ;; agent so --resume restores the swap. Unstubbed, that wrote
                  ;; into the developer's real sessions dir — keyed by whatever
                  ;; stub session an earlier namespace had left in the global
                  ;; registry. The docstring above promises every real side
                  ;; effect is stubbed; this is one of them.
                  persist/save-meta!          (fn [& _] nil)]
      (switch-model! model-name))
    @captured)))

(deftest provider-switch-does-not-carry-previous-key
  (testing "openai → free-llm: the old OpenAI key is NOT passed to create-lm,
            so create-lm resolves FREELLM_API_KEY from the provider catalog"
    (let [opts (capture-switch {:provider :openai :model "gpt-4.1-mini"
                                :api-key "sk-openai-SHOULD-NOT-LEAK"}
                               "auto")]
      (is (= :free-llm (:provider opts)))
      (is (= "auto" (:model opts)))
      ;; The crux: no :api-key override leaks across the provider boundary.
      (is (not (contains? opts :api-key))
          "switch-model! must leave :api-key unset on a provider switch")
      (is (not= "sk-openai-SHOULD-NOT-LEAK" (:api-key opts))))))

(deftest same-provider-switch-reuses-current-key
  (testing "openai → openai (different model): the resolved key is reused so we
            don't needlessly re-read the env"
    (let [opts (capture-switch {:provider :openai :model "gpt-4.1-mini"
                                :api-key "sk-openai-keep-me"}
                               "gpt-4o")]
      (is (= :openai (:provider opts)))
      (is (= "gpt-4o" (:model opts)))
      (is (= "sk-openai-keep-me" (:api-key opts))))))

(deftest explicit-provider-prefix-wins-over-the-guess
  (testing "claude-code/sonnet stays on :claude-code — the guess from the whole
            string says :anthropic, and used to be passed as :provider"
    (let [opts (capture-switch {:provider :openai :model "gpt-4o"} "claude-code/sonnet")]
      (is (= :claude-code (:provider opts)))
      (is (= "sonnet" (:model opts)))))

  (testing "legacy provider:model form is honored the same way"
    (let [opts (capture-switch {:provider :openai :model "gpt-4o"} "claude-code:opus")]
      (is (= [:claude-code "opus"] [(:provider opts) (:model opts)]))))

  (testing "the prefix picks WHICH catalog entry supplies a pinned region"
    (let [popular [{:model "sonnet" :provider :anthropic}
                   {:model "amazon.nova-lite-v1:0" :provider :bedrock :region "us-east-1"}]
          opts    (capture-switch {:provider :openai :model "gpt-4o"}
                                  "bedrock/amazon.nova-lite-v1:0" popular)]
      (is (= :bedrock (:provider opts)))
      (is (= "amazon.nova-lite-v1:0" (:model opts)))
      (is (= "us-east-1" (:region opts)))))

  (testing "a bare id that contains ':' is not mistaken for a prefix"
    (let [opts (capture-switch {:provider :openai :model "gpt-4o"} "amazon.nova-lite-v1:0"
                               [{:model "amazon.nova-lite-v1:0" :provider :bedrock}])]
      (is (= [:bedrock "amazon.nova-lite-v1:0"] [(:provider opts) (:model opts)]))))

  (testing "same provider via prefix still reuses the current key"
    (let [opts (capture-switch {:provider :openai :model "gpt-4o" :api-key "sk-keep"} "openai/gpt-4.1")]
      (is (= [:openai "gpt-4.1" "sk-keep"] [(:provider opts) (:model opts) (:api-key opts)])))))
