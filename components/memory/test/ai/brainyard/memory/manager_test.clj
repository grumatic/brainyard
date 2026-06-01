;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.manager-test
  "Tests for MemoryManager — narrowed to the cross-layer surface that
  remains after the unified-store refactor: contextual-recall,
  get-stats, lifecycle. Per-layer reads/writes are exercised through
  the IMemoryStore protocol on (:store mm)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.memory.core.manager :as manager]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.memory.interface.protocol :as proto]))

(def ^:dynamic *mm* nil)

(defn- store [] (mem/store *mm*))

(defn with-test-mm [f]
  (let [mm (manager/create-memory-manager (str "user-" (random-uuid))
                                          :in-memory true)]
    (try
      (binding [*mm* mm] (f))
      (finally (.close (:ds mm))))))

(use-fixtures :each with-test-mm)

;; =====================================================
;; Cross-layer recall
;; =====================================================

(deftest contextual-recall-test
  (testing "cross-layer recall surfaces hits from L2 and L3"
    (proto/write-entry (store) :l2
                       {:kind :conversation :role "user"
                        :content "Analyze our AWS EC2 spending"
                        :session-id "s1"})
    (proto/write-entry (store) :l3
                       {:kind :summary
                        :content "AWS EC2 costs are $12,500 per month from EKS"
                        :user-id (:user-id *mm*)
                        :source "analysis"})

    (let [results (proto/contextual-recall *mm* "AWS EC2 costs"
                                           {:limit 10 :session-id "s1"})]
      (is (vector? results))
      (is (pos? (count results)))
      (is (every? :_layer results))
      (is (every? :_rrf_score results)))))

(deftest contextual-recall-honors-match-mode-test
  (proto/write-entry (store) :l3
                     {:kind :fact :content "deploy uses production scripts"
                      :user-id (:user-id *mm*)})
  (proto/write-entry (store) :l3
                     {:kind :fact :content "production database tuning"
                      :user-id (:user-id *mm*)})
  ;; :and requires both words → only first doc matches
  (let [or-hits  (proto/contextual-recall *mm* "deploy production"
                                          {:limit 5 :match :or})
        and-hits (proto/contextual-recall *mm* "deploy production"
                                          {:limit 5 :match :and})]
    (is (>= (count or-hits) (count and-hits)))
    (is (pos? (count and-hits)))))

;; =====================================================
;; Lifecycle / stats
;; =====================================================

(deftest get-stats-test
  (testing "get-stats returns expected keys (no longer reports working memory)"
    (let [stats (proto/get-stats *mm*)]
      (is (contains? stats :episodes))
      (is (contains? stats :semantic-facts))
      (is (contains? stats :schema-version))
      (is (not (contains? stats :working-memory-keys))
          "working-memory-keys is gone since !working was removed"))))

;; =====================================================
;; Per-layer access via IMemoryStore (replaces the old per-protocol
;; tests for working / episodic / semantic accessors)
;; =====================================================

(deftest l1-system-and-user-context-via-store-test
  (testing "L1 :system-context and :user-context entries accessible via IMemoryStore"
    (proto/write-entry (store) :l1
                       {:kind :user-context
                        :content "deploy = prod"
                        :session-id "s-u"
                        :user-id (:user-id *mm*)
                        :id (mem/l1-entry-id :user-context :scratch "deploy")
                        :data {:field :scratch :section "deploy"}})
    (proto/write-entry (store) :l1
                       {:kind :system-context
                        :content "Use kebab-case."
                        :session-id "s-u"
                        :user-id (:user-id *mm*)
                        :id (mem/l1-entry-id :system-context :tool-context "naming")
                        :data {:field :tool-context :section "naming"}})
    (is (= 1 (count (proto/read-entries (store) :l1 {:kind :user-context} {}))))
    (is (= 1 (count (proto/read-entries (store) :l1 {:kind :system-context} {}))))
    (is (= 2 (count (proto/read-entries (store) :l1 {:session-id "s-u"} {}))))))

(deftest l2-write-and-read-via-store-test
  (testing "L2 episodes via write-entry / read-entries"
    (proto/write-entry (store) :l2
                       {:kind :conversation :role "user"
                        :content "How do I deploy to AWS?"
                        :session-id "s2"
                        :user-id (:user-id *mm*)})
    (proto/write-entry (store) :l2
                       {:kind :conversation :role "assistant"
                        :content "I'll help you deploy to AWS ECS."
                        :session-id "s2"
                        :user-id (:user-id *mm*)})
    (let [hits (proto/read-entries (store) :l2 {:text "deploy AWS"} {:limit 5})]
      (is (pos? (count hits))))))

(deftest l3-write-and-read-via-store-test
  (testing "L3 facts via write-entry / read-entries"
    (proto/write-entry (store) :l3
                       {:kind :preference
                        :content "User prefers dark mode interface"
                        :user-id (:user-id *mm*)})
    (proto/write-entry (store) :l3
                       {:kind :fact
                        :content "EC2 costs are $12,500 per month"
                        :user-id (:user-id *mm*)})
    (is (pos? (count (proto/read-entries (store) :l3 {:text "EC2 costs"} {:limit 5}))))))

;; =====================================================
;; Capture-aware consolidate path is exercised in
;; consolidate_keep_test.clj — nothing to add here.
;; =====================================================
