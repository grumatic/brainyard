;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.graph-test
  "Phase 0 (CR-MEM-20) context-graph storage + traversal."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.unified-store :as us]
            [ai.brainyard.memory.core.graph :as graph]
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

;; =====================================================
;; Total-size budget: edge eviction (prune-edges-to-budget!)
;; =====================================================

(deftest prune-edges-to-budget-test
  (let [ds     (:ds *store*)
        uid    (:user-id *store*)
        hub    (node! :component "hub")
        ;; 12 edges hub --relates_to--> leaf-i, confidence i/12 so leaf-0 is the
        ;; weakest and leaf-11 the strongest (ascending → evicted low-first).
        leaves (mapv #(node! :entity (str "leaf-" %)) (range 12))]
    (doseq [[i leaf] (map-indexed vector leaves)]
      (edge! hub leaf :relates_to :confidence (double (/ i 12))))
    (testing "count-edges sees every valid edge"
      (is (= 12 (graph/count-edges ds uid))))
    (testing "disabled budget (nil / 0 / non-positive) is a no-op"
      (is (= 0 (graph/prune-edges-to-budget! ds uid {:max-edges nil})))
      (is (= 0 (graph/prune-edges-to-budget! ds uid {:max-edges 0})))
      (is (= 12 (graph/count-edges ds uid))))
    (testing "under budget is a no-op"
      (is (= 0 (graph/prune-edges-to-budget! ds uid {:max-edges 20})))
      (is (= 12 (graph/count-edges ds uid))))
    (testing "over budget evicts to the 90% low-water mark, weakest-confidence first"
      (let [evicted   (graph/prune-edges-to-budget! ds uid {:max-edges 10})
            survivors (set (map :dst_id (graph/all-edges ds uid)))]
        ;; target = floor(10 * 0.9) = 9; evict 12 - 9 = 3
        (is (= 3 evicted))
        (is (= 9 (graph/count-edges ds uid)))
        (is (not (contains? survivors (:id (nth leaves 0)))) "weakest edge evicted")
        (is (not (contains? survivors (:id (nth leaves 2)))) "3rd-weakest evicted")
        (is (contains? survivors (:id (nth leaves 3))) "4th-weakest survives")
        (is (contains? survivors (:id (nth leaves 11))) "strongest survives")))))

(deftest prune-orphan-nodes-test
  (let [ds  (:ds *store*)
        uid (:user-id *store*)
        a   (node! :component "connected-a")
        b   (node! :component "connected-b")
        lone (node! :entity "lonely")                 ;; never wired into an edge
        gone (node! :person "was-connected")]         ;; edge will be invalidated
    (edge! a b :relates_to)
    (let [e (edge! gone a :mentions)]
      (testing "before prune all four nodes present"
        (is (= 4 (graph/count-nodes ds uid))))
      (testing "orphan prune removes only the node with no edge row"
        (let [pruned (graph/prune-orphan-nodes! ds uid)
              names  (set (map :name (graph/all-nodes ds uid)))]
          (is (= 1 pruned) "only 'lonely' has no edge row")
          (is (= 3 (graph/count-nodes ds uid)))
          (is (contains? names "connected-a"))
          (is (contains? names "connected-b"))
          (is (contains? names "was-connected") "has an edge row")
          (is (not (contains? names "lonely")) "never-connected node deleted")))
      (testing "a node whose only edge is INVALIDATED is retained (supersession invariant)"
        (proto/invalidate-edge *store* (:id e) nil)
        (let [pruned (graph/prune-orphan-nodes! ds uid)
              names  (set (map :name (graph/all-nodes ds uid)))]
          (is (= 0 pruned) "invalidated edge row still protects the node")
          (is (contains? names "was-connected") "superseded target kept for as-of history")
          (is (= #{"connected-a" "connected-b" "was-connected"} names)))))))

(deftest prune-nodes-budget-deterministic-tiebreak-test
  (let [ds    (:ds *store*)
        uid   (:user-id *store*)
        ;; 10 nodes identical on every ranking key: same type, no summary, no
        ;; edges (degree 0). Then force an identical updated_at so ONLY the id
        ;; tiebreak can order them — this guards the `n.id ASC` tiebreak (without
        ;; it, SQLite's order for the fully-tied group is unspecified).
        nodes (mapv #(node! :entity (str "n" %)) (range 10))
        ids   (sort (map :id nodes))]
    (jdbc/execute! ds ["UPDATE graph_nodes SET updated_at = '2026-01-01 00:00:00'
                        WHERE user_id = ?" uid])
    (testing "fully-tied nodes evict lowest-id first (deterministic)"
      ;; target = floor(6 * 0.9) = 5; evict 10 - 5 = 5 → the 5 lowest ids go.
      (let [evicted   (graph/prune-nodes-to-budget! ds uid {:max-nodes 6})
            survivors (set (map :id (graph/all-nodes ds uid)))]
        (is (= 5 evicted))
        (is (= 5 (graph/count-nodes ds uid)))
        (is (= (set (take 5 ids)) (set (remove survivors ids))) "5 lowest ids evicted")
        (is (= (set (drop 5 ids)) survivors) "5 highest ids survive")))))

(deftest prune-nodes-weighted-degree-test
  (testing "a node held up by weak `mentions` edges evicts before one with fewer STRONG edges"
    (let [ds   (:ds *store*)
          uid  (:user-id *store*)
          hubs (mapv #(node! :concept (str "hub" %)) (range 4)) ;; endpoints, high score → survive
          f    (node! :entity "filler")        ;; 1 mention  → wdeg 0.25
          w    (node! :entity "mention-heavy") ;; 4 mentions → wdeg 1.0  (raw degree 4)
          s    (node! :entity "strong-few")]   ;; 2 depends  → wdeg 2.0  (raw degree 2)
      (edge! f (nth hubs 0) :mentions)
      (doseq [h hubs] (edge! w h :mentions))
      (edge! s (nth hubs 0) :depends_on)
      (edge! s (nth hubs 1) :depends_on)
      ;; total 7 nodes; max-nodes 6 → target floor(5.4)=5, evict 2 lowest-score.
      (let [evicted   (graph/prune-nodes-to-budget! ds uid {:max-nodes 6})
            survivors (set (map :name (graph/all-nodes ds uid)))]
        (is (= 2 evicted))
        (is (not (contains? survivors "mention-heavy"))
            "high raw-degree but weak (mentions) node is evicted…")
        (is (contains? survivors "strong-few")
            "…while the lower raw-degree but strongly-connected node survives")))))

(deftest prune-nodes-type-bonus-test
  (testing "at equal weighted degree, a lower-value type (file) evicts before a knowledge type (concept)"
    (let [ds  (:ds *store*)
          uid (:user-id *store*)
          h   (node! :component "hub")
          f   (node! :entity "filler")          ;; 1 mention → 0.25
          fl  (node! :file "some-file")         ;; 1 depends → wdeg 1.0 + file 0.0    = 1.0
          c   (node! :concept "some-concept")]  ;; 1 depends → wdeg 1.0 + concept 3.0 = 4.0
      (edge! f h :mentions)
      (edge! fl h :depends_on)
      (edge! c h :depends_on)
      ;; total 4 nodes; max-nodes 3 → target floor(2.7)=2, evict 2 lowest-score.
      (let [evicted   (graph/prune-nodes-to-budget! ds uid {:max-nodes 3})
            survivors (set (map :name (graph/all-nodes ds uid)))]
        (is (= 2 evicted))
        (is (not (contains? survivors "some-file"))
            "file evicted first despite identical weighted degree…")
        (is (contains? survivors "some-concept")
            "…knowledge type kept by the type bonus")))))
