;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.schema-instruction-test
  "The structured-output instruction frames the schema as a DESCRIPTION of the
   reply and shows the reply's shape as a placeholder skeleton — small models
   given only the schema sent the schema back."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.schema :as schema]))

(defn- skeleton-of
  "The JSON skeleton line embedded in the instruction for `js`, parsed back."
  [js]
  (let [text (schema/json-schema-instruction js)
        line (second (re-find #"numbers and booleans unquoted\):\n(.*)\n" text))]
    (json/read-str line)))

(deftest instruction-says-schema-is-not-the-reply
  (let [text (schema/json-schema-instruction {:type "object" :properties {:a {:type "string"}}})]
    (is (str/includes? text "never the schema"))
    (is (str/includes? text "JSON Schema:\n{\"type\":\"object\""))
    (is (str/includes? text "Output only the JSON object"))))

(deftest instruction-lets-unused-fields-stay-empty
  ;; CoAct replies carry tool-calls / code-blocks / answer and must leave the
  ;; unused ones empty. "Replace every placeholder with a real value" drew the
  ;; empty string written as TEXT, which the router took for a real answer.
  (let [text (schema/json-schema-instruction {:type "object" :properties {:a {:type "string"}}})]
    (is (str/includes? text "A field you have nothing for stays empty"))
    (is (not (str/includes? text "every <placeholder> replaced")))
    (testing "schema keywords are not banned — a field's value may itself be a schema"
      (is (not (str/includes? text "never copy schema keywords"))))))

(deftest skeleton-uses-placeholders-never-sample-values
  (is (= {"sentiment" "<one of: positive | negative | neutral>"
          "score"     "<integer 1-5>"
          "ok"        "<true or false>"
          "ratio"     "<number>"}
         (skeleton-of {:type "object"
                       :properties {:sentiment {:type "string" :enum ["positive" "negative" "neutral"]}
                                    :score     {:type "integer" :minimum 1 :maximum 5}
                                    :ok        {:type "boolean"}
                                    :ratio     {:type "number"}}}))
      "an example VALUE would be an answer the model could copy"))

(deftest skeleton-keeps-structure
  (testing "nested arrays of objects, string-keyed schema"
    (is (= {"product" "<string>" "issues" [{"kind" "<string>" "severity" "<one of: low | high>"}]}
           (skeleton-of {"type" "object"
                         "properties" {"product" {"type" "string"}
                                       "issues" {"type" "array"
                                                 "items" {"type" "object"
                                                          "properties" {"kind" {"type" "string"}
                                                                        "severity" {"enum" ["low" "high"]}}}}}}))))
  (testing "local $ref (malli->json-schema emits these), nullable type arrays, anyOf"
    (is (= {"p" {"x" "<integer >= 0>"} "n" "<string>" "u" "<number>"}
           (skeleton-of {:type "object"
                         :properties {:p {:$ref "#/definitions/pt"}
                                      :n {:type ["null" "string"]}
                                      :u {:anyOf [{:type "number"} {:type "null"}]}}
                         :definitions {:pt {:type "object" :properties {:x {:type "integer" :minimum 0}}}}}))))
  (testing "a self-referencing definition terminates"
    (is (map? (skeleton-of {:type "object"
                            :properties {:node {:$ref "#/definitions/n"}}
                            :definitions {:n {:type "object" :properties {:child {:$ref "#/definitions/n"}}}}})))))

(deftest fallback-injection-uses-the-shared-instruction
  (let [inject #'llm/inject-json-schema-into-messages
        js     {:type "object" :properties {:a {:type "string"}}}]
    (testing "appended to an existing system message"
      (let [[sys usr] (inject [{:role "system" :content "Base."} {:role "user" :content "Q"}] js)]
        (is (= (str "Base.\n\n" (schema/json-schema-instruction js)) (:content sys)))
        (is (= "Q" (:content usr)))))
    (testing "a system message is created when there is none"
      (let [[sys] (inject [{:role "user" :content "Q"}] js)]
        (is (= "system" (:role sys)))
        (is (= (schema/json-schema-instruction js) (:content sys)))))))
