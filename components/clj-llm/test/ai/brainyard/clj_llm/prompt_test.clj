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

;; ============================================================================
;; Ordered user-message rendering + user-cache-prefix (prompt-cache Phase 2)
;; ============================================================================

(def ^:private ordered-sig
  (sig/compile-signature
   "OrderedSig"
   "Do the thing."
   {:question        [:string {:desc "q"}]
    :recalled-memory [:string {:desc "m"}]
    :iterations      [:string {:desc "volatile history"}]}
   {:answer [:string {:desc "a"}]}))

(deftest user-message-renders-in-declared-order-test
  (testing "input values render in :input-order, not map-iteration order"
    (let [{:keys [messages]}
          (prompt/build-messages-with-breakdown
           ordered-sig
           {:iterations "ITER" :question "Q" :recalled-memory "M"}
           {})
          user-content (:content (second messages))]
      (is (str/starts-with? user-content
                            "question: Q\nrecalled-memory: M\niterations: ITER")))))

(deftest user-cache-prefix-test
  (let [big-m (apply str (repeat 5000 "m"))]
    (testing "prefix covers the fields before the boundary and leads the message"
      (let [{:keys [messages user-cache-prefix]}
            (prompt/build-messages-with-breakdown
             ordered-sig
             {:question "Q" :recalled-memory big-m :iterations "ITER"}
             {:user-cache-boundary :iterations})
            user-content (:content (second messages))]
        (is (= (str "question: Q\nrecalled-memory: " big-m) user-cache-prefix))
        (is (str/starts-with? user-content user-cache-prefix)
            "prefix must be an exact leading substring so providers can split on it")))

    (testing "no prefix when the stable part is below the min cacheable size"
      (let [{:keys [user-cache-prefix]}
            (prompt/build-messages-with-breakdown
             ordered-sig
             {:question "Q" :recalled-memory "small" :iterations "ITER"}
             {:user-cache-boundary :iterations})]
        (is (nil? user-cache-prefix))))

    (testing "no prefix when the boundary field is first (nothing stable before it)"
      (let [sig (sig/compile-signature
                 "IterFirst" "t"
                 {:iterations [:string] :question [:string]}
                 {:answer [:string]})
            {:keys [user-cache-prefix]}
            (prompt/build-messages-with-breakdown
             sig
             {:iterations big-m :question "Q"}
             {:user-cache-boundary :iterations})]
        (is (nil? user-cache-prefix))))

    (testing "no prefix when no boundary requested"
      (let [{:keys [user-cache-prefix]}
            (prompt/build-messages-with-breakdown
             ordered-sig
             {:question "Q" :recalled-memory big-m :iterations "ITER"}
             {})]
        (is (nil? user-cache-prefix))))))
