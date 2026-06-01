;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.recall-v2-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.unified-store :as us]
            [ai.brainyard.memory.core.recall-v2 :as r2]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.memory.interface.protocol :as proto]))

(def ^:dynamic *store* nil)

(defn with-seeded-store [f]
  (let [ds (sqlite/create-datasource ":memory:")]
    (sqlite/init-schema! ds)
    (let [store (us/create-unified-store :user-id "u1" :ds ds)]
      ;; Seed across the three live layers (L1 holds both kinds).
      (proto/write-entry store :l1
                         {:kind :user-context :content "deploy target = prod"
                          :session-id "s1"
                          :id (mem/l1-entry-id :user-context :scratch "deploy")
                          :data {:field :scratch :section "deploy"}})
      (proto/write-entry store :l1
                         {:kind :user-context :content "totally unrelated reminder"
                          :session-id "s1"
                          :id (mem/l1-entry-id :user-context :scratch "other")
                          :data {:field :scratch :section "other"}})
      (proto/write-entry store :l1
                         {:kind :system-context :content "Use kebab-case for keywords."
                          :session-id "s1"
                          :id (mem/l1-entry-id :system-context :tool-context "naming")
                          :data {:field :tool-context :section "naming"}})
      (proto/write-entry store :l2
                         {:kind :conversation :role "user"
                          :content "How do I deploy to prod?"
                          :session-id "s1"})
      (proto/write-entry store :l2
                         {:kind :conversation :role "assistant"
                          :content "Run scripts/deploy.sh."
                          :session-id "s1"})
      (proto/write-entry store :l3
                         {:kind :preference
                          :content "User prefers Polylith layout"})
      (proto/write-entry store :l3
                         {:kind :fact
                          :content "Deploy uses Datomic Pro 1.0.7"})
      (try
        (binding [*store* store]
          (f))
        (finally (.close ds))))))

(use-fixtures :each with-seeded-store)

;; =====================================================
;; Layered recall
;; =====================================================

(deftest recall-layered-returns-each-layer-test
  (let [r (r2/recall-layered :store *store* :query "deploy" :session-id "s1")]
    (is (every? (set (keys (:layers r))) [:l1 :l2 :l3])
        "All three layers present in :layers map (no :system after refactor)")
    (is (pos? (count (get-in r [:layers :l1]))))
    (is (pos? (count (get-in r [:layers :l2]))))
    (is (pos? (count (get-in r [:layers :l3]))))))

(deftest recall-layered-keyword-matching-test
  (testing "L1 entries matching the query come back; non-matches still surfaced as fallback"
    (let [r (r2/recall-layered :store *store* :query "deploy" :session-id "s1")
          l1-contents (mapv :content (get-in r [:layers :l1]))]
      ;; Both L1 entries surface (matched + fallback). The matched one
      ;; should appear in the result set.
      (is (some #(str/includes? (str/lower-case %) "deploy") l1-contents)))))

(deftest recall-layered-ignores-irrelevant-l3-test
  (testing "L3 facts unrelated to the query are excluded by FTS"
    (let [r (r2/recall-layered :store *store* :query "Datomic" :session-id "s1")
          l3-contents (mapv :content (get-in r [:layers :l3]))]
      (is (some #(str/includes? % "Datomic") l3-contents))
      (is (not (some #(str/includes? % "Polylith") l3-contents))))))

;; =====================================================
;; Combined / RRF
;; =====================================================

(deftest combined-respects-total-limit-test
  (let [r (r2/recall-layered :store *store* :query "deploy" :session-id "s1"
                             :total-limit 3)]
    (is (<= (count (:combined r)) 3))))

(deftest combined-entries-have-rrf-score-test
  (let [r (r2/recall-layered :store *store* :query "deploy" :session-id "s1")]
    (is (every? :_rrf_score (:combined r)))
    (is (every? :_layer (:combined r)))))

(deftest combined-sorted-by-rrf-descending-test
  (let [r (r2/recall-layered :store *store* :query "deploy" :session-id "s1")
        scores (mapv :_rrf_score (:combined r))]
    (is (= scores (sort > scores))
        "Combined results sorted by descending RRF score")))

;; =====================================================
;; Briefing render
;; =====================================================

(deftest briefing-includes-all-non-empty-layers-test
  (let [r (r2/recall-layered :store *store* :query "deploy" :session-id "s1")
        b (:briefing r)]
    (is (string? b))
    (is (str/includes? b "## System Context"))
    (is (str/includes? b "## User Context"))
    (is (str/includes? b "## Recent Events"))
    (is (str/includes? b "## What We Know"))
    ;; Entry-ids appear in lines so the agent can request expansion
    (is (str/includes? b "(#"))))

(deftest briefing-respects-budget-test
  (let [r (r2/recall-layered :store *store* :query "deploy" :session-id "s1"
                             :budget 50)]
    (is (<= (count (:briefing r)) 51)))) ; +1 for trailing ellipsis

;; =====================================================
;; Layer subsetting
;; =====================================================

(deftest layers-subset-test
  (testing "asking only for :l1 skips the SQL layers entirely"
    (let [r (r2/recall-layered :store *store* :query "deploy"
                               :session-id "s1"
                               :layers [:l1])]
      (is (contains? (:layers r) :l1))
      (is (not (contains? (:layers r) :l2)))
      (is (not (contains? (:layers r) :l3))))))

;; =====================================================
;; recall-flat backward-compat shape
;; =====================================================

(deftest recall-flat-shape-test
  (testing "recall-flat preserves legacy {:facts :episodes :combined :keywords :query} keys"
    (let [r (r2/recall-flat :store *store* :query "deploy" :session-id "s1")]
      (is (every? (set (keys r)) [:facts :episodes :combined :keywords :query]))
      (is (vector? (:facts r)))
      (is (vector? (:episodes r)))
      ;; And exposes :layers for new callers
      (is (contains? r :layers)))))

(deftest recall-layered-match-and-test
  (testing ":match :and propagates to L2 and L3 reads"
    (proto/write-entry *store* :l3
                       {:kind :fact :content "deploy uses Datomic Pro 1.0.7"})
    (proto/write-entry *store* :l3
                       {:kind :fact :content "deploy alone in unrelated context"})
    (proto/write-entry *store* :l3
                       {:kind :fact :content "datomic by itself"})
    (let [or-hits  (-> (r2/recall-layered :store *store*
                                          :query "deploy datomic"
                                          :match :or)
                       :layers :l3 count)
          and-hits (-> (r2/recall-layered :store *store*
                                          :query "deploy datomic"
                                          :match :and)
                       :layers :l3 count)]
      (is (>= or-hits and-hits))
      (is (pos? and-hits)
          "AND mode still finds the doc that contains both terms"))))
