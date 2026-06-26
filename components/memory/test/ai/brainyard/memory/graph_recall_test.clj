;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.graph-recall-test
  "Phase 3 (CR-MEM-23) relational recall: read-graph + '## Related' briefing
  + as-of history."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.unified-store :as us]
            [ai.brainyard.memory.core.recall-v2 :as recall]
            [ai.brainyard.memory.interface.protocol :as proto]))

(def ^:dynamic *store* nil)

(defn with-store [f]
  (sqlite/reset-vec-extension!)
  (let [ds (sqlite/create-datasource ":memory:")]
    (sqlite/init-schema! ds)
    (binding [*store* (us/create-unified-store :user-id "u1" :ds ds)]
      (try (f) (finally (.close ds))))))

(use-fixtures :each with-store)

(defn- node! [t n & {:as o}] (proto/upsert-node *store* (merge {:node-type t :name n} o)))
(defn- edge! [s d rel & {:as o}]
  (proto/upsert-edge *store* (merge {:src-id (:id s) :dst-id (:id d) :relation rel} o)))

(defn- seed-graph! []
  (let [sci (node! :config-key "BY_SANDBOX_INTEROP" :summary "SCI interop knob")
        sbx (node! :component "clj-sandbox")
        ce  (node! :component "code-eval")]
    (edge! sci sbx :configures :fact "the knob configures clj-sandbox")
    (edge! sbx ce :part_of :fact "clj-sandbox is part of code-eval")
    {:sci sci :sbx sbx :ce ce}))

;; =====================================================
;; related / read-graph
;; =====================================================

(deftest related-returns-relationship-entries-test
  (seed-graph!)
  (testing "keyword seeds resolve and expand into relationship entries"
    (let [rels (proto/related *store* ["sandbox"] {})]
      (is (seq rels))
      (is (every? #(= :graph (:layer %)) rels))
      (is (some #(str/includes? (:content %) "—configures→") rels))
      (is (some #(= "BY_SANDBOX_INTEROP" (-> % :_graph :src)) rels)))))

(deftest multi-hop-neighborhood-test
  (seed-graph!)
  (testing "a single seed reaches a 2-hop edge"
    ;; "interop" matches only BY_SANDBOX_INTEROP; 2-hop expansion must still
    ;; surface the clj-sandbox —part_of→ code-eval edge.
    (let [rels (proto/related *store* ["interop"] {:max-hops 2})
          dsts (set (map #(-> % :_graph :dst) rels))]
      (is (contains? dsts "clj-sandbox"))
      (is (contains? dsts "code-eval") "2-hop neighbor surfaced"))))

;; =====================================================
;; Recall fusion + "## Related" briefing
;; =====================================================

(deftest related-fuses-into-recall-and-briefing-test
  (seed-graph!)
  (let [{:keys [layers combined briefing]}
        (recall/recall-layered :store *store* :query "sandbox configures component")]
    (testing ":graph is a populated candidate source"
      (is (seq (:graph layers))))
    (testing "relationship entries appear in the fused result"
      (is (some #(= :graph (:layer %)) combined)))
    (testing "briefing gains a '## Related' section with the triple"
      (is (str/includes? briefing "## Related"))
      (is (str/includes? briefing "—configures→")))))

(deftest empty-graph-is-non-regressing-test
  (testing "no graph ⇒ :graph empty, no '## Related' section"
    (let [{:keys [layers briefing]}
          (recall/recall-layered :store *store* :query "anything at all")]
      (is (empty? (:graph layers)))
      (is (not (str/includes? briefing "## Related"))))))

;; =====================================================
;; as-of history
;; =====================================================

(deftest as-of-history-recall-test
  (let [a (node! :concept "seatbelt-sandbox")
        b (node! :concept "write-containment")
        e (edge! a b :part_of :t-valid "2026-01-01 00:00:00" :fact "v1 policy")]
    (proto/invalidate-edge *store* (:id e) "2026-06-01 00:00:00")
    (testing "as-of inside the validity window recovers the superseded edge"
      (is (= 1 (count (proto/as-of *store* (:id a) "2026-03-01 00:00:00" {})))))
    (testing "as-of after invalidation does not"
      (is (empty? (proto/as-of *store* (:id a) "2026-09-01 00:00:00" {}))))))
