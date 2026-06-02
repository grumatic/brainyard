;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.signature-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-llm.core.signature :as sig]))

(deftest compile-signature-test
  (testing "compiles a basic signature"
    (let [result (sig/compile-signature
                  "QA"
                  "Answer questions."
                  {:question [:string {:desc "The question"}]}
                  {:answer   [:string {:desc "The answer"}]})]
      (is (= "QA" (:name result)))
      (is (= "Answer questions." (:instructions result)))
      (is (= #{:question} (:input-keys result)))
      (is (= #{:answer} (:output-keys result)))
      (is (map? (:output-json-schema result)))
      (is (= "object" (get-in result [:output-json-schema :type])))))

  (testing "compiles a multi-field signature"
    (let [result (sig/compile-signature
                  "Summarize"
                  "Summarize the document."
                  {:document [:string {:desc "The document"}]
                   :max-length [:int {:desc "Max summary length"}]}
                  {:summary [:string {:desc "The summary"}]
                   :key-points [:vector :string]})]
      (is (= #{:document :max-length} (:input-keys result)))
      (is (= #{:summary :key-points} (:output-keys result))))))

(deftest defsignature-test
  (testing "defsignature creates a var with signature map"
    (sig/defsignature TestSig
      "Test signature."
      {:inputs  {:question [:string {:desc "Q"}]}
       :outputs {:answer   [:string {:desc "A"}]}})
    (is (map? TestSig))
    (is (= "TestSig" (:name TestSig)))
    (is (= #{:question} (:input-keys TestSig)))
    (is (= #{:answer} (:output-keys TestSig)))
    (is (= "Test signature." (:instructions TestSig)))))

(deftest extract-signature-metadata-test
  (testing "extracts input and output keys"
    (let [sig-map (sig/compile-signature
                   "QA" "test"
                   {:question :string}
                   {:answer :string})
          meta (sig/extract-signature-metadata sig-map)]
      (is (= [:question] (:input-keys meta)))
      (is (= [:answer] (:output-keys meta))))))
