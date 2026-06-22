;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.usage-test
  "Tests for the open usage-guide registry and its built-in content."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.agent.core.usage :as usage]
            ;; bare — populates the registry with the built-in guides
            [ai.brainyard.agent.common.usage-guides]))

(deftest builtin-guides-registered
  (testing "all built-in topics resolve to a non-blank guide"
    (let [topics (usage/list-usage-topics)]
      (is (seq topics))
      (doseq [t topics]
        (is (not (str/blank? (usage/get-usage-guide t)))
            (str "topic " t " has no guide body")))))
  (testing "the new generalized topics are present (:nrepl is colocated in
            debug-agent, so it's covered by debug-agent-test, not here)"
    (let [topics (set (usage/list-usage-topics))]
      (doseq [t [:tool :code :sandbox :agents]]
        (is (contains? topics t) (str t " should be registered")))))
  (testing "migrated topics survive the move from clj-sandbox"
    (let [topics (set (usage/list-usage-topics))]
      (doseq [t [:memory :todo :plans :skills :files :artifacts :llm-query
                 :mcp :feedback :truncation :final :rules :discovery
                 :tool-priority :agent-state]]
        (is (contains? topics t) (str t " should still be registered"))))))

(deftest get-usage-guide-coercion
  (testing "topic accepts keyword, string, leading-colon string, and symbol"
    (let [g (usage/get-usage-guide :memory)]
      (is (string? g))
      (is (= g (usage/get-usage-guide "memory")))
      (is (= g (usage/get-usage-guide ":memory")))
      (is (= g (usage/get-usage-guide 'memory)))))
  (testing "unknown / invalid topic returns nil"
    (is (nil? (usage/get-usage-guide :no-such-topic)))
    (is (nil? (usage/get-usage-guide 123)))))

(deftest catalog-and-listing
  (testing "catalog entries carry topic/title/category in listing order"
    (let [cat (usage/usage-catalog)]
      (is (= (mapv :topic cat) (usage/list-usage-topics)))
      (doseq [e cat]
        (is (keyword? (:topic e)))
        (is (string? (:title e)))
        (is (keyword? (:category e)))))))

(deftest consult-table-is-registry-driven
  (let [t (usage/consult-table)]
    (is (string? t))
    (is (str/includes? t "When to consult"))
    (testing "includes a new topic — proves it's generated, not hardcoded"
      (is (str/includes? t "`:sandbox`"))
      (is (str/includes? t "`:agents`")))))

(deftest register-usage-roundtrip-and-validation
  (testing "register + read back, idempotent overwrite"
    (let [topic (keyword (str "test-topic-" (System/nanoTime)))]
      (usage/register-usage! topic {:guide "first" :title "T" :category :test
                                    :consult "when testing"})
      (is (= "first" (usage/get-usage-guide topic)))
      (is (= "T" (:title (usage/usage-def topic))))
      (is (= "when testing" (:consult (usage/usage-def topic))))
      (usage/register-usage! topic {:guide "second"})
      (is (= "second" (usage/get-usage-guide topic))
          "re-registering the same topic overwrites")
      ;; cleanup
      (swap! usage/!usage-defs dissoc topic)))
  (testing "title defaults to a humanized topic"
    (let [topic (keyword (str "two-word-" (System/nanoTime)))]
      (usage/register-usage! topic {:guide "x"})
      (is (str/starts-with? (:title (usage/usage-def topic)) "Two Word"))
      (swap! usage/!usage-defs dissoc topic)))
  (testing "blank guide is rejected loudly"
    (is (thrown? clojure.lang.ExceptionInfo
                 (usage/register-usage! :bad {:guide "   "})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (usage/register-usage! :bad {:guide nil})))))
