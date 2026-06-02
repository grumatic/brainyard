;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.unified-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.unified-store :as us]
            [ai.brainyard.memory.core.l1-store :as l1]
            [ai.brainyard.memory.interface.protocol :as proto]))

(def ^:dynamic *store* nil)
(def ^:dynamic *ds* nil)

(defn with-test-store [f]
  (let [ds (sqlite/create-datasource ":memory:")]
    (sqlite/init-schema! ds)
    (let [store (us/create-unified-store :user-id "u1" :ds ds)]
      (try
        (binding [*store* store *ds* ds]
          (f))
        (finally (.close ds))))))

(use-fixtures :each with-test-store)

;; =====================================================
;; Per-layer round-trip
;; =====================================================

(deftest l1-user-context-roundtrip-test
  (testing "L1 :user-context entries round-trip through the unified store"
    (proto/write-entry *store* :l1
                       {:kind :user-context
                        :content "deploy=prod"
                        :session-id "s1"
                        :id (l1/l1-entry-id :user-context :scratch "deploy")
                        :data {:field :scratch :section "deploy"}})
    (let [[e] (proto/read-entries *store* :l1 {:kind :user-context} {})]
      (is (= :l1 (:layer e)))
      (is (= :user-context (:kind e)))
      (is (= "deploy=prod" (:content e))))))

(deftest l1-system-context-roundtrip-test
  (testing "L1 :system-context entries round-trip"
    (proto/write-entry *store* :l1
                       {:kind :system-context
                        :content "Use kebab-case."
                        :session-id "s1"
                        :id (l1/l1-entry-id :system-context :tool-context "naming")
                        :data {:field :tool-context :section "naming"}})
    (let [[e] (proto/read-entries *store* :l1 {:field :tool-context} {})]
      (is (= :l1 (:layer e)))
      (is (= :system-context (:kind e)))
      (is (= "Use kebab-case." (:content e))))))

(deftest l2-roundtrip-test
  (testing "L2 entries persist with tags/sources/keep flags"
    (proto/write-entry *store* :l2
                       {:kind :conversation
                        :role "user"
                        :content "How do I deploy?"
                        :session-id "s1"
                        :tags #{"topic:deploy"}
                        :sources [{:type :user-message :id 1}]
                        :keep true})
    (let [[e] (proto/read-entries *store* :l2 {:session-id "s1"} {})]
      (is (= :l2 (:layer e)))
      (is (= :conversation (:kind e)))
      (is (= #{"topic:deploy"} (:tags e)))
      (is (true? (:keep e))))))

(deftest l3-roundtrip-test
  (testing "L3 entries persist with confidence and tags"
    (proto/write-entry *store* :l3
                       {:kind :preference
                        :content "User prefers Polylith"
                        :confidence 0.9
                        :tags #{"topic:arch"}})
    (let [[e] (proto/read-entries *store* :l3 {:text "polylith"} {})]
      (is (= :l3 (:layer e)))
      (is (= :preference (:kind e)))
      (is (< 0.85 (:confidence e) 0.95))
      (is (= #{"topic:arch"} (:tags e))))))

;; =====================================================
;; Cross-store promote
;; =====================================================

(deftest promote-l1-to-l3-test
  (testing "promote copies entry to target layer with provenance"
    (proto/write-entry *store* :l1
                       {:kind :user-context
                        :content "Important fact"
                        :session-id "s1"
                        :id (l1/l1-entry-id :user-context :scratch "imp")
                        :data {:field :scratch :section "imp"}})
    (let [[note]    (proto/read-entries *store* :l1 {:kind :user-context} {})
          promoted  (proto/promote *store* note :l1 :l3)
          [l3-entry] (proto/read-entries *store* :l3 {} {})]
      (is (= :l3 (:layer promoted)))
      (is (= "Important fact" (:content promoted)))
      (is (some #(= "promotion" (str (:type %))) (:sources l3-entry)))
      ;; Source L1 entry remains intact
      (is (= 1 (count (proto/read-entries *store* :l1 {} {})))))))

;; =====================================================
;; Forget tombstones SQL layers
;; =====================================================

(deftest forget-l2-tombstones-test
  (testing "forget on L2 sets tombstoned_flag and excludes from default reads"
    (proto/write-entry *store* :l2
                       {:kind :conversation :role "user" :content "x"
                        :session-id "s1" :id "stable-id-1"})
    (is (proto/forget *store* :l2 "stable-id-1"))
    (is (= 0 (count (proto/read-entries *store* :l2 {:session-id "s1"} {}))))
    (testing "include-tombstoned reveals tombstoned rows"
      (is (= 1 (count (proto/read-entries
                       *store* :l2 {:session-id "s1"}
                       {:include-tombstoned true})))))))

(deftest forget-l3-tombstones-test
  (testing "forget on L3 sets tombstoned_flag"
    (proto/write-entry *store* :l3
                       {:kind :fact :content "ABC"
                        :id "fact-1"})
    (is (proto/forget *store* :l3 "fact-1"))
    (is (= 0 (count (proto/read-entries *store* :l3 {:text "ABC"} {}))))))

;; =====================================================
;; Backward-compat: legacy episodic insert reaches read path
;; =====================================================

(deftest legacy-episode-visible-via-read-entries-test
  (testing "an episode written by legacy append-episode! is visible via L2 read"
    (require '[ai.brainyard.memory.core.episodic :as ep])
    (let [ep (ns-resolve 'ai.brainyard.memory.core.episodic 'append-episode!)]
      (ep *ds* {:session-id "s-legacy"
                :user-id "u1"
                :episode-type :conversation
                :role "user"
                :content "hello via legacy"}))
    (let [entries (proto/read-entries *store* :l2 {:session-id "s-legacy"} {})]
      (is (= 1 (count entries)))
      (is (= "hello via legacy" (-> entries first :content))))))

;; =====================================================
;; :match modes (:or default, :and, :phrase)
;; =====================================================

(deftest match-modes-l3-test
  (testing "L3 text search honors :match :or | :and | :phrase"
    (proto/write-entry *store* :l3
                       {:kind :fact :content "deploy uses production scripts"})
    (proto/write-entry *store* :l3
                       {:kind :fact :content "production database tuning notes"})
    (proto/write-entry *store* :l3
                       {:kind :fact :content "deploy.sh script for staging"})
    (proto/write-entry *store* :l3
                       {:kind :fact :content "the canonical deploy production environment doc"})

    ;; :or default — broad match
    (let [hits (proto/read-entries *store* :l3 {:text "deploy production"} {})]
      (is (= 4 (count hits))
          ":or matches docs with either word"))

    ;; explicit :or
    (let [hits (proto/read-entries *store* :l3
                                   {:text "deploy production" :match :or} {})]
      (is (= 4 (count hits))
          ":match :or behaves identically to default"))

    ;; :and — both words must appear
    (let [hits (proto/read-entries *store* :l3
                                   {:text "deploy production" :match :and} {})
          contents (set (map :content hits))]
      (is (= 2 (count hits)))
      (is (contains? contents "deploy uses production scripts"))
      (is (contains? contents "the canonical deploy production environment doc"))
      (is (not (contains? contents "deploy.sh script for staging"))))

    ;; :phrase — exact consecutive sequence
    (let [hits (proto/read-entries *store* :l3
                                   {:text "deploy production" :match :phrase} {})
          contents (set (map :content hits))]
      (is (= 1 (count hits)))
      (is (contains? contents "the canonical deploy production environment doc")))))

(deftest match-modes-l2-test
  (testing "L2 text search honors :match"
    (proto/write-entry *store* :l2
                       {:kind :conversation :role "user"
                        :content "how do I deploy production"
                        :session-id "s-match"})
    (proto/write-entry *store* :l2
                       {:kind :conversation :role "user"
                        :content "production tips"
                        :session-id "s-match"})
    ;; :or — both match
    (is (= 2 (count (proto/read-entries *store* :l2
                                        {:text "deploy production" :session-id "s-match"} {}))))
    ;; :and — only the doc with both words
    (is (= 1 (count (proto/read-entries *store* :l2
                                        {:text "deploy production" :session-id "s-match" :match :and} {}))))))
