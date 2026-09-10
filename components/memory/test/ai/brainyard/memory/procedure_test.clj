;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.procedure-test
  "Procedural-graph storage + DIRECTED traversal, and the non-contamination
   guarantee that justifies giving it its own tables.

   Design: docs/design/procedural-graph-implementation.md §7"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.unified-store :as us]
            [ai.brainyard.memory.core.graph :as graph]
            [ai.brainyard.memory.core.procedure :as p]
            [ai.brainyard.memory.interface.protocol :as proto]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *store* nil)

(defn with-test-db [f]
  (let [ds (sqlite/create-datasource ":memory:")]
    (sqlite/init-schema! ds)
    (let [store (us/create-unified-store :user-id "u1" :ds ds)]
      (try
        (binding [*ds* ds *store* store] (f))
        (finally (.close ds))))))

(use-fixtures :each with-test-db)

(defn- node! [nm & {:as opts}]
  (p/upsert-node! *ds* nil (merge {:name nm :kind :tool} opts)))

(defn- edge! [src dst relation & {:as opts}]
  (p/upsert-edge! *ds* nil (merge {:src-id (:id src) :dst-id (:id dst)
                                   :relation relation} opts)))

;; =====================================================
;; Schema
;; =====================================================

(deftest schema-version-test
  (testing "the procedural tables bump the memory schema to 2.4.0"
    (is (= "2.4.0" (sqlite/get-schema-version *ds*))))

  (testing "all four procedural tables exist"
    (let [names (->> (jdbc/execute! *ds* ["SELECT name FROM sqlite_master WHERE type='table'"])
                     (map #(or (:name %) (:sqlite_master/name %)))
                     set)]
      (is (every? names ["proc_nodes" "proc_edges" "proc_graphs" "proc_rejections"])))))

;; =====================================================
;; Nodes
;; =====================================================

(deftest node-upsert-and-match-test
  (testing "insert assigns an id and round-trips name/kind"
    (let [n (node! "edit$apply" :summary "apply a patch")]
      (is (some? (:id n)))
      (is (= "edit$apply" (:name n)))
      (is (= :tool (:kind n)))))

  (testing "upsert by (graph-id, name) merges rather than duplicating"
    (let [a (node! "edit$apply")
          b (node! "edit$apply" :summary "second look")]
      (is (= (:id a) (:id b)))
      (is (= 1 (p/count-nodes *ds* nil)))
      (is (= "second look" (:summary b)))))

  (testing "a nil summary does not erase an existing one"
    (node! "read-file" :summary "reads a file")
    (is (= "reads a file" (:summary (node! "read-file")))))

  (testing "Match is EXACT — a prefix or substring does not resolve"
    (node! "task$wait")
    (is (some? (p/find-node *ds* nil "task$wait")))
    (is (nil? (p/find-node *ds* nil "task")))
    (is (nil? (p/find-node *ds* nil "task$wait2")))
    (is (nil? (p/find-node *ds* nil "TASK$WAIT"))))

  (testing "Match resolves an exact alias, not a substring of one"
    (node! "code:bash" :aliases ["bash" "sh"])
    (is (= "code:bash" (:name (p/find-node *ds* nil "bash"))))
    (is (nil? (p/find-node *ds* nil "ba"))))

  (testing "a blank or nil probe never queries its way to a node"
    (is (nil? (p/find-node *ds* nil nil)))
    (is (nil? (p/find-node *ds* nil "")))
    (is (nil? (p/find-node *ds* nil "   ")))))

;; =====================================================
;; Edges
;; =====================================================

(deftest edge-upsert-idempotency-test
  (let [a (node! "edit$apply") b (node! "read-file")]
    (testing "a transition round-trips its three Phi fields"
      (let [e (edge! a b :verifies
                     :condition "the apply reported success"
                     :guidance  "re-read the region"
                     :pitfalls  "a successful apply is not a successful edit")]
        (is (= :verifies (:relation e)))
        (is (= "the apply reported success" (:condition e)))
        (is (= "re-read the region" (:guidance e)))
        (is (= "a successful apply is not a successful edit" (:pitfalls e)))))

    (testing "re-asserting updates in place — one LIVE row, not two"
      (edge! a b :verifies :support 9 :guidance "revised")
      (is (= 1 (p/count-edges *ds* nil)))
      (let [e (first (p/out-neighborhood *ds* nil (:id a) {}))]
        (is (= 9 (:support e)))
        (is (= "revised" (:guidance e)))))

    (testing "a different relation between the same pair is a different edge"
      (edge! a b :precedes)
      (is (= 2 (p/count-edges *ds* nil))))))

(deftest edge-validation-test
  (let [a (node! "a") b (node! "b")]
    (testing "an unknown relation is refused rather than silently stored"
      (is (thrown? clojure.lang.ExceptionInfo (edge! a b :mentions)))
      (is (thrown? clojure.lang.ExceptionInfo (edge! a b :depends_on))))

    (testing "the what-is vocabulary is disjoint from the what-to-do one"
      (is (empty? (clojure.set/intersection p/procedure-relations proto/relations))))

    (testing "a self-loop is not an admissible transition"
      (is (thrown? clojure.lang.ExceptionInfo (edge! a a :precedes))))

    (testing "missing endpoints are refused"
      (is (thrown? clojure.lang.ExceptionInfo
                   (p/upsert-edge! *ds* nil {:src-id (:id a) :relation :precedes}))))))

(deftest bitemporal-supersession-test
  (let [a (node! "a") b (node! "b")
        e (edge! a b :precedes :guidance "original")]

    (testing "invalidate takes the edge out of the live set without deleting it"
      (is (true? (p/invalidate-edge! *ds* nil (:id e))))
      (is (= 0 (p/count-edges *ds* nil)))
      (is (= 1 (:c (first (jdbc/execute! *ds* ["SELECT COUNT(*) AS c FROM proc_edges"]))))))

    (testing "invalidating twice reports no live row was affected"
      (is (false? (p/invalidate-edge! *ds* nil (:id e)))))

    (testing "re-asserting after supersession inserts a NEW live row"
      ;; The case a table-level UNIQUE(..., t_valid) would have rejected — the
      ;; bug graph_edges had to be rebuilt to fix. Do not relearn it.
      (let [e2 (edge! a b :precedes :guidance "revised")]
        (is (not= (:id e) (:id e2)))
        (is (= 1 (p/count-edges *ds* nil)))
        (is (= "revised" (:guidance e2)))
        (is (= 2 (:c (first (jdbc/execute! *ds* ["SELECT COUNT(*) AS c FROM proc_edges"])))))))

    (testing "a superseded edge is not walked"
      (is (= ["revised"] (mapv :guidance (p/out-neighborhood *ds* nil (:id a) {})))))))

;; =====================================================
;; Traversal — the error the design flags as most likely
;; =====================================================

(deftest out-neighborhood-is-directed-test
  (let [a (node! "a") b (node! "b") c (node! "c")]
    (edge! a b :precedes)
    (edge! b c :precedes)

    (testing "walking OUT from a reaches b at depth 0 and c at depth 1"
      (let [es (p/out-neighborhood *ds* nil (:id a) {})]
        (is (= [["a" "b" 0] ["b" "c" 1]]
               (mapv (juxt :src_name :dst_name :depth) es)))))

    (testing "walking OUT from b does NOT surface a"
      ;; The whole reason this is a new walk rather than a reuse of
      ;; `graph/expand-edges`, whose join is
      ;;   (e.src_id = w.node_id OR e.dst_id = w.node_id).
      ;; Copying that fails SILENTLY: guidance still renders, it just describes
      ;; what leads INTO the current step as though it were the next one.
      (let [es (p/out-neighborhood *ds* nil (:id b) {})]
        (is (= [["b" "c"]] (mapv (juxt :src_name :dst_name) es)))
        (is (not-any? #(= "a" (:dst_name %)) es))))

    (testing "a leaf has an empty out-neighborhood"
      (is (= [] (p/out-neighborhood *ds* nil (:id c) {}))))

    (testing "graph/expand-edges on the ENTITY graph is undirected — the contrast"
      (let [n1 (proto/upsert-node *store* {:node-type :concept :name "x"})
            n2 (proto/upsert-node *store* {:node-type :concept :name "y"})]
        (proto/upsert-edge *store* {:src-id (:id n1) :dst-id (:id n2) :relation :part_of})
        ;; Seeded at y, the undirected walk still returns the x->y edge.
        (is (seq (graph/expand-edges *ds* "u1" [(:id n2)] {})))))))

(deftest out-neighborhood-bounds-test
  (let [ns- (mapv #(node! (str "n" %)) (range 6))]
    (doseq [i (range 5)] (edge! (ns- i) (ns- (inc i)) :precedes))

    (testing "max-hops bounds the walk"
      (is (= 1 (count (p/out-neighborhood *ds* nil (:id (first ns-)) {:max-hops 1}))))
      (is (= 2 (count (p/out-neighborhood *ds* nil (:id (first ns-)) {:max-hops 2})))))

    (testing "max-hops is clamped to 3, so an unbounded request cannot walk the graph"
      (is (= 3 (count (p/out-neighborhood *ds* nil (:id (first ns-)) {:max-hops 99})))))

    (testing "limit caps the result"
      (is (= 1 (count (p/out-neighborhood *ds* nil (:id (first ns-))
                                          {:max-hops 3 :limit 1})))))

    (testing "depth is ascending"
      (let [ds- (mapv :depth (p/out-neighborhood *ds* nil (:id (first ns-)) {:max-hops 3}))]
        (is (= ds- (sort ds-)))))))

(deftest out-neighborhood-cycle-test
  (testing "a cycle terminates and each edge is reported at its SHORTEST depth"
    (let [a (node! "a") b (node! "b")]
      (edge! a b :precedes)
      (edge! b a :leads_to)
      (let [es (p/out-neighborhood *ds* nil (:id a) {:max-hops 3})]
        (is (= 2 (count es)))
        (is (= [0 1] (sort (map :depth es))))))))

(deftest out-neighborhood-nil-node-test
  (testing "no node id means no query and an empty result"
    (is (= [] (p/out-neighborhood *ds* nil nil {})))))

;; =====================================================
;; Non-contamination — why these are separate tables
;; =====================================================

(deftest procedural-graph-does-not-leak-into-entity-recall-test
  ;; The regression the comparison note needed and lacked. A `:procedure` node
  ;; co-tenanted in `graph_nodes` would be picked up by `search-nodes` (no
  ;; node_type predicate), by the `graph_vec` kNN (untyped ref_kind='node'),
  ;; and bridged by `expand-edges` (no relation filter, undirected).
  (let [n1 (proto/upsert-node *store* {:node-type :file :name "deps.edn"
                                       :summary "project dependency file"})
        n2 (proto/upsert-node *store* {:node-type :component :name "memory"
                                       :summary "the memory component"})
        _  (proto/upsert-edge *store* {:src-id (:id n1) :dst-id (:id n2)
                                       :relation :part_of :fact "deps names memory"})
        before-related (proto/related *store* ["deps" "memory" "file"] {})
        before-nodes   (graph/search-nodes *ds* "u1" ["file" "memory"] {})]

    ;; Populate a procedural graph whose text overlaps the query hard.
    (let [a (node! "edit$apply" :summary "edit a project dependency file")
          b (node! "read-file"  :summary "read a file from the memory component")]
      (edge! a b :verifies :guidance "re-read the file"))

    (testing "relational recall is byte-identical with a procedural graph present"
      (is (= before-related (proto/related *store* ["deps" "memory" "file"] {}))))

    (testing "lexical node seeding never sees a procedure node"
      (let [after (graph/search-nodes *ds* "u1" ["file" "memory"] {})]
        (is (= before-nodes after))
        (is (not-any? #(#{"edit$apply" "read-file"} (:name %)) after))))

    (testing "the entity graph's counts are unchanged"
      (is (= 2 (graph/count-nodes *ds* "u1")))
      (is (= 1 (graph/count-edges *ds* "u1"))))

    (testing "and the procedural graph is genuinely populated — not an empty pass"
      (is (= 2 (p/count-nodes *ds* nil)))
      (is (= 1 (p/count-edges *ds* nil))))))

(deftest entity-retention-sweep-cannot-evict-procedures-test
  ;; Phase 2 spends validation rollouts to earn each edge, so silent eviction by
  ;; memory retention pressure is the data loss this feature can least afford.
  (dotimes [i 5]
    (proto/upsert-node *store* {:node-type :concept :name (str "concept-" i)}))
  (let [a (node! "edit$apply") b (node! "read-file")]
    (edge! a b :verifies))

  (let [evicted (graph/prune-nodes-to-budget! *ds* "u1" {:max-nodes 1})]
    (testing "the sweep evicts entity nodes"
      (is (pos? evicted)))
    (testing "and leaves the procedural graph completely untouched"
      (is (= 2 (p/count-nodes *ds* nil)))
      (is (= 1 (p/count-edges *ds* nil))))))

;; =====================================================
;; Gate state + rejection memory
;; =====================================================

(deftest graph-meta-test
  (testing "an unseen graph reads as generation 0 with no cached score"
    (let [m (p/graph-meta *ds* nil)]
      (is (= 0 (:gen m)))
      (is (nil? (:val-score m)))))

  (testing "the retained score is cached, and a partial update preserves the rest"
    (p/set-graph-meta! *ds* nil {:gen 1 :val-score 0.45 :val-suite "smoke"})
    (is (= 0.45 (:val-score (p/graph-meta *ds* nil))))
    (p/set-graph-meta! *ds* nil {:gen 2})
    (let [m (p/graph-meta *ds* nil)]
      (is (= 2 (:gen m)))
      (is (= 0.45 (:val-score m)))
      (is (= "smoke" (:val-suite m))))))

(deftest rejection-memory-test
  (let [a (node! "a") b (node! "b")]
    (p/record-rejection! *ds* nil {:gen 3 :reason :regressed
                                   :val-score 0.2 :base-score 0.45
                                   :edits [{:op "add" :src "a" :dst "b"}]})
    (testing "a rejection round-trips as negative evidence"
      (let [[r] (p/rejections *ds* nil 10)]
        (is (= 3 (:gen r)))
        (is (= "regressed" (:reason r)))
        (is (= 0.45 (:base_score r)))
        (is (= [{"op" "add" "src" "a" "dst" "b"}] (:edits r)))))

    (testing "rejections are NOT reachable by a traversal"
      ;; They are proposals that were never part of any graph. An invalidated
      ;; edge would be one `t_invalid` predicate away from being walked.
      (edge! a b :precedes)
      (is (= 1 (count (p/out-neighborhood *ds* nil (:id a) {}))))
      (is (= 1 (p/count-edges *ds* nil))))))

;; =====================================================
;; Graph isolation
;; =====================================================

(deftest graph-id-isolation-test
  (let [a (p/upsert-node! *ds* "g1" {:name "shared" :kind :tool})
        b (p/upsert-node! *ds* "g1" {:name "other"  :kind :tool})
        c (p/upsert-node! *ds* "g2" {:name "shared" :kind :tool})]
    (p/upsert-edge! *ds* "g1" {:src-id (:id a) :dst-id (:id b) :relation :precedes})

    (testing "the same name in two graphs is two nodes"
      (is (not= (:id a) (:id c))))

    (testing "a walk never crosses graphs"
      (is (= 1 (count (p/out-neighborhood *ds* "g1" (:id a) {}))))
      (is (= 0 (count (p/out-neighborhood *ds* "g2" (:id c) {})))))

    (testing "counts are per graph"
      (is (= 2 (p/count-nodes *ds* "g1")))
      (is (= 1 (p/count-nodes *ds* "g2")))
      (is (= 0 (p/count-edges *ds* "g2"))))))

(deftest snapshot-test
  (let [a (node! "a") b (node! "b")]
    (edge! a b :precedes :support 4))
  (let [s (p/snapshot *ds* nil {})]
    (testing "a snapshot carries nodes, live edges with endpoint names, and counts"
      (is (= {:nodes 2 :edges 1} (:counts s)))
      (is (= #{"a" "b"} (set (map :name (:nodes s)))))
      (is (= ["a" "b"] ((juxt :src_name :dst_name) (first (:edges s))))))))
