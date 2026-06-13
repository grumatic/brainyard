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
            [ai.brainyard.agent-tui.commands :as commands]))

(def ^:private switch-model! #'commands/switch-model!)

(defn- capture-switch
  "Drive switch-model! with `current-lm` as the active LM and `model-name` as
   the /model argument. Returns the option map handed to create-lm (what the
   new LM is built from). All real side effects are stubbed."
  [current-lm model-name]
  (let [captured (atom nil)]
    (with-redefs [llm/get-default-lm          (fn [] current-lm)
                  llm/get-popular-models      (fn [] [])
                  llm/get-provider-from-model (fn [m] (if (= m "auto") :free-llm :openai))
                  llm/create-lm               (fn [opts] (reset! captured opts) opts)
                  llm/configure-default-lm!   (fn [_] nil)
                  tui-session/emit!           (fn [_] nil)
                  tui-session/update-status-bar! (fn [] nil)]
      (switch-model! model-name))
    @captured))

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
