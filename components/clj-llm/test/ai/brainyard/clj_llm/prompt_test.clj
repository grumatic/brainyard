;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.prompt-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-llm.core.prompt :as prompt]
            [ai.brainyard.clj-llm.core.signature :as sig]
            [clojure.string :as str]))

(def test-signature
  (sig/compile-signature
   "QA"
   "Answer questions accurately."
   {:question [:string {:desc "The question to answer"}]}
   {:answer   [:string {:desc "The answer"}]}))

(deftest build-messages-predict-test
  (let [msgs (prompt/build-messages test-signature {:question "What?"} {})]
    (testing "returns system and user messages"
      (is (= 2 (count msgs)))
      (is (= "system" (:role (first msgs))))
      (is (= "user" (:role (second msgs)))))

    (testing "system message includes typed, numbered input fields"
      (let [content (-> msgs first :content)]
        (is (str/includes? content "Your input fields are:"))
        (is (str/includes? content "`question` (string)"))
        (is (str/includes? content "The question to answer"))))

    (testing "system message includes typed, numbered output fields"
      (let [content (-> msgs first :content)]
        (is (str/includes? content "Your output fields are:"))
        (is (str/includes? content "`answer` (string)"))
        (is (str/includes? content "The answer"))))

    (testing "system message includes JSON instruction"
      (is (str/includes? (-> msgs first :content) "JSON")))

    (testing "system message has instructions at end (DSPy convention)"
      (let [content (-> msgs first :content)]
        (is (str/includes? content "In adhering to this structure, your objective is:"))
        (is (str/includes? content "Answer questions accurately."))))))

(deftest build-messages-with-json-schema-test
  (let [schema {:type "object" :properties {"answer" {:type "string"}} :required ["answer"]}
        msgs (prompt/build-messages test-signature {:question "What?"} {:json-schema schema})]
    (testing "system message includes JSON schema definition"
      (let [content (-> msgs first :content)]
        (is (str/includes? content "IMPORTANT: You MUST respond with ONLY a valid JSON object"))
        (is (str/includes? content "\"answer\""))
        (is (str/includes? content "Use EXACTLY the field names"))))))

(deftest build-messages-user-message-test
  (let [msgs (prompt/build-messages test-signature {:question "What is 2+2?"} {})]
    (testing "user message includes input values"
      (is (str/includes? (-> msgs second :content) "question: What is 2+2?")))

    (testing "user message includes output field reminder"
      (is (str/includes? (-> msgs second :content) "`answer`")))))

(deftest build-messages-cot-test
  (let [msgs (prompt/build-messages test-signature {:question "What?"} {:chain-of-thought? true})]
    (testing "CoT system message includes reasoning as first output field"
      (let [content (-> msgs first :content)
            reasoning-pos (str/index-of content "`reasoning`")
            answer-pos (str/index-of content "`answer`")]
        (is (some? reasoning-pos))
        (is (< reasoning-pos answer-pos))))

    (testing "CoT system message has instructions at end"
      (is (str/includes? (-> msgs first :content) "In adhering to this structure, your objective is:")))

    (testing "CoT user message reminds about reasoning field first"
      (is (str/includes? (-> msgs second :content) "starting with the field `reasoning`")))))
