;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.semantic-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.semantic :as semantic]))

(def ^:dynamic *ds* nil)

(defn with-test-db [f]
  (let [conn (sqlite/create-datasource ":memory:?cache=shared")]
    (sqlite/init-schema! conn)
    (try
      (binding [*ds* conn]
        (f))
      (finally
        (.close conn)))))

(use-fixtures :each with-test-db)

(deftest store-and-retrieve-fact-test
  (testing "store and retrieve fact"
    (let [fact (semantic/store-fact! *ds*
                                     {:user-id "u1"
                                      :fact-type :preference
                                      :content "User prefers EC2 over Lambda"
                                      :source "conversation:s1"
                                      :confidence 0.95})]
      (is (some? fact))
      (is (= "preference" (:fact_type fact)))

      (let [retrieved (semantic/get-fact-by-id *ds* (:id fact))]
        (is (some? retrieved))
        (is (= "User prefers EC2 over Lambda" (:content retrieved)))
        (is (< (Math/abs (- 0.95 (double (:confidence retrieved)))) 0.001))))))

(deftest fts-search-facts-test
  (testing "FTS5 search on facts"
    (semantic/store-fact! *ds*
                          {:user-id "u1" :fact-type :preference
                           :content "User prefers EC2 over Lambda for compute"
                           :source "conv:s1"})
    (semantic/store-fact! *ds*
                          {:user-id "u1" :fact-type :summary
                           :content "High AWS EC2 costs, $12,500/month from EKS cluster"
                           :source "session:s1"})
    (semantic/store-fact! *ds*
                          {:user-id "u1" :fact-type :fact
                           :content "Primary region is us-west-2"
                           :source "config"})

    (let [results (semantic/search-fts *ds* "EC2 costs" 5 :user-id "u1")]
      (is (pos? (count results)))
      (is (some #(clojure.string/includes? (:content %) "EC2") results)))))

(deftest fact-type-filter-test
  (testing "filter by fact type"
    (semantic/store-fact! *ds*
                          {:user-id "u1" :fact-type :preference
                           :content "Dark mode preferred"})
    (semantic/store-fact! *ds*
                          {:user-id "u1" :fact-type :summary
                           :content "Summary of dark mode discussion"})

    (let [prefs (semantic/search-fts *ds* "dark mode" 5
                                     :fact-types [:preference]
                                     :user-id "u1")]
      (is (every? #(= "preference" (:fact_type %)) prefs)))))

(deftest update-fact-test
  (testing "update fact content and confidence"
    (let [fact (semantic/store-fact! *ds*
                                     {:user-id "u1" :fact-type :fact
                                      :content "Original content"
                                      :confidence 0.8})
          updated (semantic/update-fact! *ds* (:id fact)
                                         {:content "Updated content"
                                          :confidence 0.95})]
      (is (= "Updated content" (:content updated)))
      (is (< (Math/abs (- 0.95 (double (:confidence updated)))) 0.001)))))

(deftest access-tracking-test
  (testing "record access increments count"
    (let [fact (semantic/store-fact! *ds*
                                     {:user-id "u1" :fact-type :fact
                                      :content "Test fact"})
          id (:id fact)]
      (semantic/record-access! *ds* id)
      (semantic/record-access! *ds* id)
      (let [updated (semantic/get-fact-by-id *ds* id)]
        (is (= 2 (:access_count updated)))
        (is (some? (:last_accessed updated)))))))

(deftest confidence-decay-test
  (testing "decay reduces confidence"
    (semantic/store-fact! *ds*
                          {:user-id "u1" :fact-type :fact
                           :content "Decayable fact"
                           :confidence 0.5})
    (let [{:keys [decayed]} (semantic/decay-facts! *ds* :decay-rate 0.1)]
      (is (pos? decayed))
      ;; Check confidence was reduced
      (let [facts (semantic/search-fts *ds* "Decayable" 1)]
        (is (< (:confidence (first facts)) 0.5))))))

(deftest consolidation-helpers-test
  (testing "store-summary!"
    (let [result (semantic/store-summary! *ds* "u1" "s1" "Session summary content")]
      (is (some? result))
      (is (= "summary" (:fact_type result)))))

  (testing "store-user-preference!"
    (let [result (semantic/store-user-preference! *ds* "u1" "Prefers dark mode" "observation")]
      (is (some? result))
      (is (= "preference" (:fact_type result)))))

  (testing "store-learned-fact!"
    (let [result (semantic/store-learned-fact! *ds* "u1" "EC2 costs $12k/month" "analysis")]
      (is (some? result))
      (is (= "fact" (:fact_type result))))))

;; The SemanticMemoryImpl protocol-implementation test was removed in
;; the unified-store refactor — the per-protocol defrecord is gone.
;; All protocol-level coverage now lives in unified_store_test.clj
;; against the IMemoryStore surface.

(deftest count-facts-test
  (testing "count facts by user"
    (semantic/store-fact! *ds*
                          {:user-id "u1" :fact-type :fact :content "Fact 1"})
    (semantic/store-fact! *ds*
                          {:user-id "u1" :fact-type :preference :content "Pref 1"})
    (semantic/store-fact! *ds*
                          {:user-id "u2" :fact-type :fact :content "Fact 2"})

    (is (= 2 (semantic/count-facts *ds* :user-id "u1")))
    (is (= 1 (semantic/count-facts *ds* :user-id "u2")))
    (is (= 3 (semantic/count-facts *ds*)))))
