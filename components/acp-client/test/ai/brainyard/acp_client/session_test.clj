;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp-client.session-test
  "Pure-data tests for session helpers (no subprocess). Currently
   covers `resolve-model-id`, the fuzzy matcher used to turn a
   user-supplied model string into one of the agent's advertised
   modelIds before `set-model!`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.acp-client.interface :as acp-client]))

(def ^:private claude-code-models
  ;; Shape the claude-code adapter returns in session/new :models.
  [{:modelId "default" :name "Default (recommended)"
    :description "Opus 4.6 · Most capable for complex work"}
   {:modelId "sonnet" :name "Sonnet" :description "Sonnet 4.5 · Best for everyday tasks"}
   {:modelId "haiku"  :name "Haiku"  :description "Haiku 4.5 · Fastest for quick answers"}])

(deftest resolve-model-id-test
  (testing "exact modelId matches"
    (is (= "sonnet" (acp-client/resolve-model-id claude-code-models "sonnet")))
    (is (= "haiku"  (acp-client/resolve-model-id claude-code-models "haiku")))
    (is (= "default" (acp-client/resolve-model-id claude-code-models "default"))))
  (testing "case-insensitive + name/description substring"
    (is (= "sonnet" (acp-client/resolve-model-id claude-code-models "Sonnet")))
    (is (= "default" (acp-client/resolve-model-id claude-code-models "opus"))
        "\"opus\" matches the Opus description on the :default entry")
    (is (= "default" (acp-client/resolve-model-id claude-code-models "OPUS"))))
  (testing "no match / nil input → nil (caller warns + keeps default)"
    (is (nil? (acp-client/resolve-model-id claude-code-models "gpt-4")))
    (is (nil? (acp-client/resolve-model-id claude-code-models nil)))
    (is (nil? (acp-client/resolve-model-id [] "sonnet")))))

;; -----------------------------------------------------------------------------
;; Session config options — the CURRENT model-selection mechanism.
;;
;; claude-agent-acp 0.70.0 removed `session/set_model` (it answers -32601 for
;; every id) and moved model choice into `session/new`'s `configOptions`, set
;; via `session/set_config_option`. Fixtures below are verbatim 0.70.0 captures.
;; -----------------------------------------------------------------------------

(def ^:private config-options-070
  [{:id "mode" :name "Mode" :category "mode" :type "select"
    :currentValue "default"
    :options [{:value "default" :name "Manual"}
              {:value "plan" :name "Plan Mode"}]}
   {:id "model" :name "Model" :description "AI model to use"
    :category "model" :type "select" :currentValue "default"
    :options [{:value "default" :name "Default (recommended)"
               :description "Opus (1M context)"}
              {:value "opus[1m]" :name "Opus (1M context)"
               :description "Opus 5 with 1M context · Best for everyday, complex tasks"}
              {:value "claude-fable-5[1m]" :name "Fable"
               :description "Fable 5 · Most capable for your hardest and longest-running tasks"}
              {:value "sonnet" :name "Sonnet" :description "Sonnet 5 · Efficient for routine tasks"}
              {:value "haiku" :name "Haiku" :description "Haiku 4.5 · Fastest for quick answers"}]}
   {:id "agent" :name "Agent" :type "select" :currentValue "default"
    :options [{:value "default" :name "Default"}]}])

(def ^:private config-options-070-no-mode
  (vec (remove #(= "mode" (:category %)) config-options-070)))

(deftest model-config-option-test
  (testing "picks the model selector out of the option set"
    (let [opt (acp-client/model-config-option config-options-070)]
      (is (= "model" (:id opt)))
      (is (= "default" (:currentValue opt)))))
  (testing "matches on :category, not position — mode comes first in the vector"
    (is (= "model" (:id (acp-client/model-config-option
                         (reverse config-options-070))))))
  (testing "falls back to :id/:name when the agent sends no category"
    (is (= "model" (:id (acp-client/model-config-option
                         [{:id "model" :name "Model" :options []}])))))
  (testing "model_config is a DIFFERENT category (secondary knobs) — not the selector"
    (is (nil? (acp-client/model-config-option
               [{:id "ctx" :name "Context" :category "model_config" :options []}]))))
  (testing "no selector → nil (backend does not support model selection)"
    (is (nil? (acp-client/model-config-option [])))
    (is (nil? (acp-client/model-config-option nil)))))

(deftest mode-config-option-test
  (testing "picks the mode selector, which gates the permission bridge"
    (let [opt (acp-client/mode-config-option config-options-070)]
      (is (= "mode" (:id opt)))
      (is (= #{"default" "plan"} (set (map :value (:options opt)))))))
  (testing "category match is EXACT — model_config must not satisfy model"
    (is (nil? (acp-client/config-option
               [{:id "ctx" :category "model_config" :options []}] "model")))
    (is (= "ctx" (:id (acp-client/config-option
                       [{:id "ctx" :category "model_config" :options []}]
                       "model_config")))))
  (testing "absent selector → nil (agent offers no mode control)"
    (is (nil? (acp-client/mode-config-option [])))
    (is (nil? (acp-client/mode-config-option config-options-070-no-mode)))))

(deftest resolve-config-value-test
  (let [opt (acp-client/model-config-option config-options-070)]
    (testing "exact value"
      (is (= "sonnet" (acp-client/resolve-config-value opt "sonnet")))
      (is (= "haiku"  (acp-client/resolve-config-value opt "haiku"))))
    (testing "\"opus\" resolves to the explicit Opus entry, not the catch-all default"
      ;; Both match: "opus[1m]" by id-substring, "default" by its
      ;; description "Opus (1M context)". Tier order is what decides, and
      ;; picking `default` here would silently ignore a real opus entry.
      (is (= "opus[1m]" (acp-client/resolve-config-value opt "opus")))
      (is (= "opus[1m]" (acp-client/resolve-config-value opt "OPUS"))))
    (testing "name/description substring for an id that shares no text"
      (is (= "claude-fable-5[1m]" (acp-client/resolve-config-value opt "fable"))))
    (testing "no match / nil → nil"
      (is (nil? (acp-client/resolve-config-value opt "gpt-4")))
      (is (nil? (acp-client/resolve-config-value opt nil)))
      (is (nil? (acp-client/resolve-config-value {:options []} "sonnet"))))))
