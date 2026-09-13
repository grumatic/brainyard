;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.json-schema-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.clj-llm.core.json-schema :as js]))

(def ^:private person
  {"type"                 "object"
   "properties"           {"name" {"type" "string" "minLength" 1}
                           "age"  {"type" "integer" "minimum" 0}
                           "tags" {"type" "array" "items" {"type" "string"} "uniqueItems" true}
                           "role" {"enum" ["admin" "user"]}}
   "required"             ["name" "age"]
   "additionalProperties" false})

(defn- paths [r] (set (map :path (:errors r))))

(deftest valid-values-pass
  (is (:valid? (js/validate person {"name" "Ann" "age" 3 "tags" ["a" "b"] "role" "user"})))
  (testing "keyword keys on either side are the same schema and the same data"
    (is (:valid? (js/validate person {:name "Ann" :age 3})))
    (is (:valid? (js/validate {:type "object" :properties {:n {:type "number"}} :required [:n]}
                              {"n" 1.5}))))
  (testing "an integral double is an integer; an integer is a number"
    (is (:valid? (js/validate {"type" "integer"} 3.0)))
    (is (:valid? (js/validate {"type" "number"} 3)))))

(deftest violations-are-reported-with-paths
  (let [r (js/validate person {"name" "" "age" -1 "tags" ["a" "a" 7] "extra" true})]
    (is (false? (:valid? r)))
    (is (= #{["name"] ["age"] ["tags"] ["tags" 2] ["extra"]} (paths r)))))

(deftest type-and-required
  (is (= #{["age"]} (paths (js/validate person {"name" "x"}))))
  (is (= "expected object, got string"
         (-> (js/validate person "nope") :errors first :message)))
  (testing "a type array admits either"
    (is (:valid? (js/validate {"type" ["string" "null"]} nil)))
    (is (not (:valid? (js/validate {"type" ["string" "null"]} 1))))))

(deftest combinators-const-pattern-and-refs
  (is (:valid? (js/validate {"anyOf" [{"type" "string"} {"type" "integer"}]} 1)))
  (is (not (:valid? (js/validate {"anyOf" [{"type" "string"} {"type" "integer"}]} true))))
  (is (not (:valid? (js/validate {"oneOf" [{"type" "number"} {"type" "integer"}]} 1)))
      "an integer matches both, so oneOf fails")
  (is (not (:valid? (js/validate {"allOf" [{"minimum" 1} {"maximum" 2}]} 3))))
  (is (not (:valid? (js/validate {"not" {"type" "null"}} nil))))
  (is (not (:valid? (js/validate {"const" "x"} "y"))))
  (is (:valid? (js/validate {"type" "string" "pattern" "^[a-z]+$"} "abc")))
  (is (not (:valid? (js/validate {"type" "string" "pattern" "^[a-z]+$"} "ab1"))))
  (testing "local $ref through $defs and definitions"
    (let [s {"type" "object"
             "properties" {"p" {"$ref" "#/$defs/pt"} "q" {"$ref" "#/definitions/pt"}}
             "$defs" {"pt" {"type" "integer"}}
             "definitions" {"pt" {"type" "integer"}}}]
      (is (:valid? (js/validate s {"p" 1 "q" 2})))
      (is (= #{["p"]} (paths (js/validate s {"p" "x" "q" 2})))))))

(deftest annotations-are-ignored
  (is (:valid? (js/validate {"type" "string" "format" "email" "description" "d"} "not-an-email"))))
