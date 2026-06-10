;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.analytics.persistence-test
  "Persistence is upsert-by-session: analytics runs once per turn but every
   fact is keyed only by `analytics:session:<id>`, so re-running must replace
   the prior fact-set rather than append a duplicate."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.analytics.core.persistence :as persistence]
            [ai.brainyard.memory.interface :as mem]))

(defn- analytics-fixture
  "A minimal full analytics map for one session at a given PQS score."
  [session-id user-id score]
  {:session-id session-id
   :user-id    user-id
   :pqs   {:overall-score score
           :dimensions {:specificity 10 :task-atomicity 10
                        :context-completeness 10 :acceptance-criteria 10
                        :clarity 5}
           :adjustments {:total 0}
           :recommendations []}
   :cost  {:actual {:total-cost 0.0123 :total-tokens 1000 :call-count 3
                    :by-model {"anthropic.claude" {}}}
           :savings-potential 0.0
           :throughput nil}
   :waste {:patterns [{:pattern-id :redundant-context :severity :low
                       :detail "minor" :waste-tokens 10 :waste-cost 0.001}]
           :total-waste-tokens 10 :total-waste-cost 0.001 :waste-percentage 1.0}})

(defn- session-facts
  "All L3 analytics facts persisted for `session-id`, via the same query
   shape the persistence layer's own reader uses."
  [mm session-id]
  (let [prefix (str "analytics:session:" session-id)]
    (->> (mem/read-entries mm :l3 {:text (str "analytics session " session-id)} {:limit 50})
         (filter #(some-> (:source %) str (.startsWith prefix))))))

(deftest persist-analytics-upserts-by-session
  (testing "re-persisting a session replaces its fact-set, not appends"
    (let [user-id (str "u-an-" (random-uuid))
          sid     "sess-upsert-1"
          mm      (mem/create-memory-manager user-id :in-memory true)]

      ;; First turn: 3 facts (pqs, usage, waste)
      (is (= {:facts-stored 3}
             (persistence/persist-analytics! mm (analytics-fixture sid user-id 50))))
      (is (= 3 (count (session-facts mm sid)))
          "one PQS + one usage + one waste fact after the first persist")

      ;; Second turn for the SAME session: still 3 facts total, not 6.
      (is (= {:facts-stored 3}
             (persistence/persist-analytics! mm (analytics-fixture sid user-id 90))))
      (let [facts (session-facts mm sid)
            by-kind (group-by :kind facts)]
        (is (= 3 (count facts))
            "prior fact-set was replaced, not appended")
        (is (= 1 (count (:pqs-score by-kind))))
        (is (= 1 (count (:usage-metric by-kind))))
        (is (= 1 (count (:waste-detection by-kind)))))

      ;; The surviving PQS fact reflects the latest turn (score 90, not 50).
      (let [pqs (:pqs (persistence/query-session-analytics mm sid))]
        (is (= 90 (get-in pqs [:data :score])))))))

(deftest persist-analytics-keeps-sessions-isolated
  (testing "upserting one session does not disturb another session's facts"
    (let [user-id (str "u-an-" (random-uuid))
          mm      (mem/create-memory-manager user-id :in-memory true)]
      (persistence/persist-analytics! mm (analytics-fixture "sess-A" user-id 70))
      (persistence/persist-analytics! mm (analytics-fixture "sess-B" user-id 40))
      ;; Re-run A — B must be untouched.
      (persistence/persist-analytics! mm (analytics-fixture "sess-A" user-id 80))
      (is (= 3 (count (session-facts mm "sess-A"))))
      (is (= 3 (count (session-facts mm "sess-B"))))
      (is (= 80 (get-in (:pqs (persistence/query-session-analytics mm "sess-A")) [:data :score])))
      (is (= 40 (get-in (:pqs (persistence/query-session-analytics mm "sess-B")) [:data :score]))))))
