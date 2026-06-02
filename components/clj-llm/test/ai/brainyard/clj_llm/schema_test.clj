;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-llm.core.schema :as schema]))

(deftest malli->json-schema-test
  (testing "converts simple string schema"
    (let [js (schema/malli->json-schema :string)]
      (is (= "string" (:type js)))))

  (testing "converts integer schema"
    (let [js (schema/malli->json-schema :int)]
      (is (= "integer" (:type js)))))

  (testing "converts map schema with additionalProperties false"
    (let [js (schema/malli->json-schema [:map [:name :string] [:age :int]])]
      (is (= "object" (:type js)))
      (is (false? (:additionalProperties js)))
      (is (contains? (:properties js) :name))
      (is (contains? (:properties js) :age))))

  (testing "converts vector schema"
    (let [js (schema/malli->json-schema [:vector :string])]
      (is (= "array" (:type js)))
      (is (= "string" (get-in js [:items :type])))))

  (testing "nested maps get additionalProperties false recursively"
    (let [js (schema/malli->json-schema
              [:map
               [:user [:map [:name :string]]]])]
      (is (false? (:additionalProperties js)))
      (is (false? (get-in js [:properties :user :additionalProperties])))))

  (testing ":maybe is rendered with :anyOf, not :oneOf (OpenAI strict mode rejects oneOf)"
    (let [js (schema/malli->json-schema [:maybe :int])]
      (is (nil? (:oneOf js)) "no :oneOf key")
      (is (sequential? (:anyOf js)) ":anyOf with the variants")
      (is (= #{"integer" "null"} (set (map :type (:anyOf js)))))))

  (testing ":maybe with constraints preserves bounds inside :anyOf"
    (let [js (schema/malli->json-schema [:maybe [:int {:min 1000 :max 600000}]])]
      (is (nil? (:oneOf js)))
      (let [int-variant (first (filter #(= "integer" (:type %)) (:anyOf js)))]
        (is (= 1000 (:minimum int-variant)))
        (is (= 600000 (:maximum int-variant))))))

  (testing "nested :maybe inside a map also gets the rename"
    (let [js (schema/malli->json-schema
              [:map [:opt-num [:maybe :int]]])]
      (is (nil? (get-in js [:properties :opt-num :oneOf])))
      (is (some? (get-in js [:properties :opt-num :anyOf]))))))

(deftest parse-malli-field-test
  (testing "plain schema"
    (let [result (schema/parse-malli-field :string)]
      (is (= :string (:schema result)))
      (is (nil? (:desc result)))
      (is (nil? (:default result)))))

  (testing "native schema with desc — schema kept whole"
    (let [result (schema/parse-malli-field [:string {:desc "A question"}])]
      (is (= [:string {:desc "A question"}] (:schema result)))
      (is (= "A question" (:desc result)))))

  (testing "native schema with default — schema kept whole"
    (let [result (schema/parse-malli-field [:string {:default "hello"}])]
      (is (= [:string {:default "hello"}] (:schema result)))
      (is (= "hello" (:default result)))))

  (testing "native enum with metadata + values"
    (let [result (schema/parse-malli-field [:enum {:desc "x" :optional true} "a" "b"])]
      (is (= [:enum {:desc "x" :optional true} "a" "b"] (:schema result)))
      (is (= "x" (:desc result)))
      (is (true? (:optional result)))))

  (testing "vector schema without metadata props is bare"
    (let [result (schema/parse-malli-field [:vector :string])]
      (is (= [:vector :string] (:schema result)))
      (is (nil? (:desc result))))))

(deftest fields->json-schema-test
  (testing "converts fields map to JSON Schema object"
    (let [fields {:question [:string {:desc "The question"}]
                  :answer   [:string {:desc "The answer"}]}
          js (schema/fields->json-schema fields)]
      (is (= "object" (:type js)))
      (is (false? (:additionalProperties js)))
      (is (= #{"question" "answer"} (set (:required js))))
      (is (= "The question" (get-in js [:properties "question" :description])))
      (is (= "The answer" (get-in js [:properties "answer" :description]))))))

(deftest fields->malli-schema-test
  (testing "converts fields map to Malli :map schema"
    (let [fields {:question [:string {:desc "The question"}]
                  :answer   :string}
          ms (schema/fields->malli-schema fields)]
      (is (vector? ms))
      (is (= :map (first ms))))))

(deftest validate-output-test
  (testing "valid output"
    (let [ms [:map [:answer :string]]
          result (schema/validate-output ms {:answer "Paris"})]
      (is (true? (:valid? result)))
      (is (= {:answer "Paris"} (:data result)))))

  (testing "invalid output"
    (let [ms [:map [:answer :string]]
          result (schema/validate-output ms {:answer 42})]
      (is (false? (:valid? result)))
      (is (some? (:errors result))))))
