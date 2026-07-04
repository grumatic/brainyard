;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.claude-code-test
  "The claude-code CLI exposes --json-schema's StructuredOutput as an OPTIONAL
   tool, so large/agentic prompts can slip into a plain-prose reply that fails
   JSON parsing. reinforce-structured-output hardens the system prompt against
   that whenever structured output is active."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.clj-llm.core.claude-code :as cc]))

(def ^:private reinforce  @#'cc/reinforce-structured-output)
(def ^:private directive  @#'cc/structured-output-directive)
(def ^:private build-args @#'cc/build-cli-args)

(deftest reinforce-appends-only-under-structured-output
  (testing "with :json-schema, the directive is appended to the system prompt"
    (let [sys "SYSTEM PROMPT BODY"
          out (reinforce sys {:json-schema {:type "object"}})]
      (is (str/starts-with? out sys) "original prompt is preserved as the prefix")
      (is (str/ends-with? out directive) "directive is appended verbatim")
      (is (str/includes? out "StructuredOutput") "names the tool the model must call")
      (is (str/includes? out "code-blocks") "steers large code into the code-blocks field")
      (is (str/includes? out "never write files") "forbids doing the task itself")))
  (testing "without :json-schema, the system prompt is returned unchanged"
    (let [sys "SYSTEM PROMPT BODY"]
      (is (= sys (reinforce sys {})))
      (is (= sys (reinforce sys {:json-schema nil}))))))

(deftest build-cli-args-still-emits-json-schema
  (testing "structured-output args shape is intact alongside the nudge"
    (let [args (build-args {:model "opus"} {:json-schema {:type "object"}}
                           {:system-prompt-flag "--system-prompt"
                            :system-prompt-value "SYS"})]
      (is (some #{"--json-schema"} args) "the synthetic StructuredOutput tool is still requested")
      (is (some #{"--max-turns"} args))
      (is (= "opus" (second (drop-while #(not= "--model" %) args)))))))
