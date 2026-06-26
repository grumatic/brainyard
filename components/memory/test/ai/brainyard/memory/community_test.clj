;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.community-test
  "Phase 4 (CR-MEM-24) community detection + summarization — the GraphRAG
  tier that replaces the heuristic L2→L3 reducer (closes CR-MEM-07)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.unified-store :as us]
            [ai.brainyard.memory.core.community :as community]
            [ai.brainyard.memory.interface.protocol :as proto]
            [next.jdbc :as jdbc]))

;; =====================================================
;; Pure label propagation
;; =====================================================

(deftest label-propagation-test
  (testing "connected nodes share a label; isolated nodes keep their own"
    (let [labels (community/label-propagation [1 2 3 4] [[1 2] [2 3]])]
      (is (= (labels 1) (labels 2) (labels 3)) "1-2-3 collapse to one community")
      (is (not= (labels 4) (labels 1)) "4 is its own community")))
  (testing "two disjoint components yield two labels"
    (let [labels (community/label-propagation [1 2 3 4] [[1 2] [3 4]])]
      (is (= (labels 1) (labels 2)))
      (is (= (labels 3) (labels 4)))
      (is (not= (labels 1) (labels 3))))))

;; =====================================================
;; Detection + summarization over a real store
;; =====================================================

(def ^:dynamic *store* nil)

(defn make-store [& {:keys [summarize-fn]}]
  (sqlite/reset-vec-extension!)
  (let [ds (sqlite/create-datasource ":memory:")]
    (sqlite/init-schema! ds)
    (us/create-unified-store :user-id "u1" :ds ds :summarize-fn summarize-fn)))

(defn with-store [f]
  (let [store (make-store)]
    (binding [*store* store]
      (try (f) (finally (.close (:ds store)))))))

(use-fixtures :each with-store)

(defn- node! [t n & {:as o}] (proto/upsert-node *store* (merge {:node-type t :name n} o)))
(defn- edge! [s d rel] (proto/upsert-edge *store* {:src-id (:id s) :dst-id (:id d) :relation rel}))

(defn- seed-two-clusters! []
  ;; Cluster 1: BY_SANDBOX_INTEROP — clj-sandbox — code-eval
  (let [a (node! :config-key "BY_SANDBOX_INTEROP" :summary "interop knob")
        b (node! :component "clj-sandbox")
        c (node! :component "code-eval")
        ;; Cluster 2: jake — tabs
        d (node! :person "jake")
        e (node! :concept "tabs")
        ;; isolated singleton
        _ (node! :file "orphan.clj")]
    (edge! a b :configures)
    (edge! b c :part_of)
    (edge! d e :prefers)
    {:a a :b b :c c :d d :e e}))

(deftest detect-assigns-community-ids-test
  (seed-two-clusters!)
  (let [{:keys [communities nodes]} (community/detect-communities! (:ds *store*) "u1")]
    (testing "two clusters + one singleton = 3 communities over 6 nodes"
      (is (= 6 nodes))
      (is (= 3 communities)))
    (testing "connected nodes share a community_id"
      (let [cid (fn [nm] (let [r (jdbc/execute-one! (:ds *store*)
                                                    ["SELECT community_id FROM graph_nodes WHERE user_id='u1' AND name=?" nm])]
                           (or (:community_id r) (:graph_nodes/community_id r))))]
        (is (every? some? [(cid "BY_SANDBOX_INTEROP") (cid "jake")]) "community_id is populated")
        (is (= (cid "BY_SANDBOX_INTEROP") (cid "clj-sandbox") (cid "code-eval")))
        (is (= (cid "jake") (cid "tabs")))
        (is (not= (cid "BY_SANDBOX_INTEROP") (cid "jake")))))))

(deftest consolidate-writes-summaries-test
  (seed-two-clusters!)
  (testing "templated fallback (no summarize-fn) produces community L3 facts"
    (let [r (proto/consolidate-layer *store* :l2 {:reducer :community})]
      (is (= :community (:reducer r)))
      (is (= 2 (:produced r)) "two multi-member communities summarized (singleton skipped)")
      ;; community rows persisted
      (is (= 2 (-> (jdbc/execute-one! (:ds *store*)
                                      ["SELECT COUNT(*) n FROM graph_communities WHERE user_id='u1'"]) :n)))
      ;; mirrored into recallable L3 :summary facts
      (let [facts (proto/read-entries *store* :l3 {:text "community"} {:limit 10})]
        (is (<= 2 (count facts)))
        (is (every? #(= :summary (:kind %)) facts))
        (is (some #(str/includes? (:content %) "Community [") facts))))))

(deftest consolidate-uses-summarize-fn-test
  (testing "an injected summarize-fn supplies the community summary text"
    (let [store (make-store :summarize-fn (fn [_desc] "LLM-SUMMARY-MARKER"))]
      (try
        (binding [*store* store]
          (seed-two-clusters!)
          (proto/consolidate-layer store :l2 {:reducer :community})
          (let [facts (proto/read-entries store :l3 {:text "community"} {:limit 10})]
            (is (some #(str/includes? (:content %) "LLM-SUMMARY-MARKER") facts))))
        (finally (.close (:ds store)))))))

(deftest consolidate-is-idempotent-test
  (seed-two-clusters!)
  (testing "re-running consolidation upserts (no duplicate communities/facts)"
    (proto/consolidate-layer *store* :l2 {:reducer :community})
    (proto/consolidate-layer *store* :l2 {:reducer :community})
    (is (= 2 (-> (jdbc/execute-one! (:ds *store*)
                                    ["SELECT COUNT(*) n FROM graph_communities WHERE user_id='u1'"]) :n))
        "communities upserted by (user,label), not duplicated")))

(deftest heuristic-reducer-still-default-test
  (testing "without :reducer :community, the heuristic path is unchanged"
    (let [r (proto/consolidate-layer *store* :l2 {})]
      (is (= :l2 (:from-layer r)))
      (is (not= :community (:reducer r))))))
