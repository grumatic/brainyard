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
