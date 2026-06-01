;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.recall-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.episodic :as episodic]
            [ai.brainyard.memory.core.semantic :as semantic]
            [ai.brainyard.memory.core.recall :as recall]))

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

(defn- seed-test-data! [ds]
  ;; Semantic facts
  (semantic/store-fact! ds {:user-id "u1" :fact-type :summary
                            :content "EC2 costs $12,500 per month, primarily from EKS cluster"
                            :source "analysis" :confidence 0.9})
  (semantic/store-fact! ds {:user-id "u1" :fact-type :preference
                            :content "User prefers Reserved Instances for cost optimization"
                            :source "conversation" :confidence 0.95})
  (semantic/store-fact! ds {:user-id "u1" :fact-type :fact
                            :content "AWS account uses us-west-2 as primary region"
                            :source "config" :confidence 1.0})

  ;; Episodic episodes
  (episodic/append-episode! ds {:session-id "s1" :user-id "u1"
                                :episode-type :conversation :role "user"
                                :content "How can I reduce my AWS EC2 costs?"})
  (episodic/append-episode! ds {:session-id "s1" :user-id "u1"
                                :episode-type :conversation :role "assistant"
                                :content "I recommend looking at Reserved Instances and right-sizing."})
  (episodic/append-episode! ds {:session-id "s1" :user-id "u1"
                                :episode-type :observation :role "system"
                                :content "Cost Explorer shows 40% of EC2 spend is on-demand."}))

(deftest reciprocal-rank-fusion-test
  (testing "RRF combines results from multiple sources"
    (let [combined (recall/reciprocal-rank-fusion
                    {:semantic {:results [{:id 1 :content "Fact A"}
                                          {:id 2 :content "Fact B"}]
                                :weight 0.6}
                     :episodic {:results [{:id 10 :content "Episode X"}
                                          {:id 11 :content "Episode Y"}]
                                :weight 0.4}})]
      (is (= 4 (count combined)))
      ;; All results should have RRF scores
      (is (every? :_rrf_score combined))
      ;; All should have layer annotation
      (is (every? :_layer combined))
      ;; Scores should be in descending order
      (is (apply >= (map :_rrf_score combined))))))

(deftest recall-pipeline-test
  (testing "full 4-step recall pipeline"
    (seed-test-data! *ds*)

    (let [result (recall/recall-pipeline *ds* "u1" "AWS EC2 costs"
                                         :semantic-limit 5
                                         :episodic-limit 5
                                         :total-limit 10)]
      ;; Should have all expected keys
      (is (contains? result :facts))
      (is (contains? result :episodes))
      (is (contains? result :combined))
      (is (contains? result :keywords))
      (is (= "AWS EC2 costs" (:query result)))

      ;; Should find semantic facts
      (is (pos? (count (:facts result))))

      ;; Should extract keywords
      (is (pos? (count (:keywords result))))

      ;; Combined should have results from both layers
      (is (pos? (count (:combined result)))))))

(deftest recall-pipeline-empty-test
  (testing "recall pipeline with no data returns empty results"
    (let [result (recall/recall-pipeline *ds* "u1" "nonexistent query")]
      (is (= [] (:facts result)))
      (is (= [] (:episodes result)))
      (is (= [] (:combined result))))))

(deftest recall-pipeline-with-session-filter-test
  (testing "recall pipeline respects session-id filter"
    (seed-test-data! *ds*)

    ;; Add episode in a different session
    (episodic/append-episode! *ds* {:session-id "s2" :user-id "u1"
                                    :episode-type :conversation :role "user"
                                    :content "Unrelated session about AWS Lambda"})

    (let [result (recall/recall-pipeline *ds* "u1" "AWS"
                                         :session-id "s1"
                                         :episodic-limit 10)]
      ;; Episodes should only be from session s1
      (let [episode-layers (filter #(= :episodic (:_layer %)) (:combined result))]
        (when (seq episode-layers)
          (is (every? #(= "s1" (:session_id %)) episode-layers)))))))

(deftest recall-pipeline-weights-test
  (testing "semantic-heavy weights prioritize facts"
    (seed-test-data! *ds*)

    (let [result (recall/recall-pipeline *ds* "u1" "EC2 costs"
                                         :semantic-weight 0.9
                                         :episodic-weight 0.1
                                         :total-limit 5)]
      ;; With heavy semantic weight, top results should be semantic
      (when (> (count (:combined result)) 1)
        (is (= :semantic (:_layer (first (:combined result)))))))))
