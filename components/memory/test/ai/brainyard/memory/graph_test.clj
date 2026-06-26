;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.graph-test
  "Phase 0 (CR-MEM-20) context-graph storage + traversal."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.unified-store :as us]
            [ai.brainyard.memory.interface.protocol :as proto]))

(def ^:dynamic *store* nil)

(defn with-test-store [f]
  (let [ds (sqlite/create-datasource ":memory:")]
    (sqlite/init-schema! ds)
    (let [store (us/create-unified-store :user-id "u1" :ds ds)]
      (try
        (binding [*store* store] (f))
        (finally (.close ds))))))

(use-fixtures :each with-test-store)

(defn- node! [node-type name & {:as opts}]
  (proto/upsert-node *store* (merge {:node-type node-type :name name} opts)))

(defn- edge! [src dst relation & {:as opts}]
  (proto/upsert-edge *store* (merge {:src-id (:id src) :dst-id (:id dst)
                                     :relation relation} opts)))

;; =====================================================
;; Nodes & entity resolution
;; =====================================================

(deftest node-upsert-and-resolve-test
  (testing "insert assigns an id and round-trips type/name"
    (let [n (node! :config-key "BY_SANDBOX_INTEROP" :summary "sci interop knob")]
      (is (some? (:id n)))
      (is (= :config-key (:node-type n)))
      (is (= "BY_SANDBOX_INTEROP" (:name n)))))

  (testing "upsert by (type, name) merges rather than duplicating"
    (let [a (node! :config-key "BY_SANDBOX_INTEROP" :aliases ["SANDBOX_INTEROP"])
          b (node! :config-key "BY_SANDBOX_INTEROP" :summary "updated" :aliases ["sci"])]
      (is (= (:id a) (:id b)) "same node id on conflict")
      (is (= "updated" (:summary b)))
      (is (= #{"SANDBOX_INTEROP" "sci"} (set (:aliases b))) "aliases unioned")))

  (testing "find-node resolves by exact name and by alias"
    (node! :config-key "BY_SANDBOX_INTEROP" :aliases ["SANDBOX_INTEROP"])
    (is (= "BY_SANDBOX_INTEROP"
           (:name (proto/find-node *store* :config-key "BY_SANDBOX_INTEROP"))))
    (is (= "BY_SANDBOX_INTEROP"
           (:name (proto/find-node *store* :config-key "SANDBOX_INTEROP")))
        "alias surface form resolves to the canonical node")
    (is (= "BY_SANDBOX_INTEROP"
           (:name (proto/find-node *store* nil "SANDBOX_INTEROP")))
        "nil node-type searches across all types")
    (is (nil? (proto/find-node *store* :config-key "nonexistent")))))

;; =====================================================
;; Edges & bi-temporal supersession
;; =====================================================

(deftest edge-and-supersession-test
  (let [sci (node! :config-key "BY_SANDBOX_INTEROP")
        sbx (node! :component "clj-sandbox")]
    (testing "edge insert carries relation, fact, provenance"
      (let [e (edge! sci sbx :configures
                     :fact "BY_SANDBOX_INTEROP configures clj-sandbox"
                     :source-entry-ids ["ep-1" "ep-2"])]
        (is (some? (:id e)))
        (is (= :configures (:relation e)))
        (is (= ["ep-1" "ep-2"] (:source-entry-ids e)))
        (is (nil? (:t_invalid e)) "fresh edge is valid")))

    (testing "neighbors returns the connected node over a valid edge"
      (let [ns (proto/neighbors *store* (:id sci) {})]
        (is (= 1 (count ns)))
        (is (= "clj-sandbox" (-> ns first :node :name)))
        (is (= :configures (-> ns first :edge :relation)))))

    (testing "invalidate-edge hides the edge from default reads but keeps history"
      (let [e (-> (proto/neighbors *store* (:id sci) {}) first :edge)]
        (is (true? (proto/invalidate-edge *store* (:id e) nil)))
        (is (empty? (proto/neighbors *store* (:id sci) {}))
            "invalidated edge no longer a valid neighbor")
        (is (false? (proto/invalidate-edge *store* (:id e) nil))
            "already-invalidated edge is a no-op")))))

;; =====================================================
;; Multi-hop expansion
;; =====================================================

(deftest expand-multihop-test
  (let [sci   (node! :config-key "BY_SANDBOX_INTEROP")
        sbx   (node! :component "clj-sandbox")
        coact (node! :component "code-eval")]
    ;; BY_SANDBOX_INTEROP --configures--> clj-sandbox --part_of--> code-eval
    (edge! sci sbx :configures)
    (edge! sbx coact :part_of)

    (testing "1 hop reaches only the direct neighbor"
      (let [r (proto/expand *store* [(:id sci)] {:max-hops 1})]
        (is (= #{"clj-sandbox"} (set (map #(-> % :node :name) r))))))

    (testing "2 hops reaches the transitive node at depth 2"
      (let [r (proto/expand *store* [(:id sci)] {:max-hops 2})
            by-name (into {} (map (juxt #(-> % :node :name) :depth) r))]
        (is (= #{"clj-sandbox" "code-eval"} (set (keys by-name))))
        (is (= 1 (get by-name "clj-sandbox")))
        (is (= 2 (get by-name "code-eval")) "min depth collapses paths")))

    (testing "seeds are excluded and empty seeds yield nothing"
      (is (empty? (filter #(= "BY_SANDBOX_INTEROP" (-> % :node :name))
                          (proto/expand *store* [(:id sci)] {:max-hops 3}))))
      (is (empty? (proto/expand *store* [] {:max-hops 2}))))))

;; =====================================================
;; As-of historical queries
;; =====================================================

(deftest as-of-history-test
  (let [a (node! :concept "seatbelt-sandbox")
        b (node! :concept "write-containment")
        e (edge! a b :part_of :t-valid "2026-01-01 00:00:00")]
    (proto/invalidate-edge *store* (:id e) "2026-06-01 00:00:00")
    (testing "edge is invisible to default neighbors after invalidation"
      (is (empty? (proto/neighbors *store* (:id a) {}))))
    (testing "as-of within the validity window still sees the edge"
      (is (= 1 (count (proto/as-of *store* (:id a) "2026-03-01 00:00:00" {})))))
    (testing "as-of after t_invalid does not"
      (is (empty? (proto/as-of *store* (:id a) "2026-09-01 00:00:00" {}))))
    (testing "as-of before t_valid does not"
      (is (empty? (proto/as-of *store* (:id a) "2025-12-01 00:00:00" {}))))))

;; =====================================================
;; Non-regression: graph empty by default
;; =====================================================

(deftest empty-graph-is-inert-test
  (testing "an untouched graph yields empty traversals (recall falls back to FTS)"
    (let [n (node! :entity "lonely")]
      (is (empty? (proto/neighbors *store* (:id n) {})))
      (is (empty? (proto/expand *store* [(:id n)] {:max-hops 3}))))))
