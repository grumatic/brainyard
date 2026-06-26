;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.vec-test
  "Phase 1 (CR-MEM-21) sqlite-vec semantic-recall path. Uses a deterministic
  stub embed-fn (no live LLM). Skips when sqlite-vec is unavailable (e.g. CI
  without `bb sqlite-vec:fetch`), so the suite stays green either way."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.unified-store :as us]
            [ai.brainyard.memory.core.recall-v2 :as recall]
            [ai.brainyard.memory.interface.protocol :as proto]))

(defn- stub-embed
  "Deterministic embed-fn: identical text → identical vector (distance 0);
  different text → different non-zero slots. dims must match the table."
  [dims]
  (fn [texts]
    (mapv (fn [t]
            (let [v (double-array dims 0.0)]
              (doseq [i (range 4)]
                (aset v (mod (Math/abs (hash (str t "#" i))) dims) (double (inc i))))
              (vec v)))
          texts)))

(def ^:dynamic *store* nil)
(def ^:dynamic *vec?* false)

(defn with-vec-store [f]
  (sqlite/reset-vec-extension!)
  (let [ds (sqlite/create-datasource ":memory:")]
    (sqlite/init-schema! ds)
    (let [store (us/create-unified-store :user-id "u1" :ds ds
                                         :embed-fn (stub-embed (sqlite/graph-embed-dims)))]
      (try
        (binding [*store* store
                  *vec?*  (boolean (sqlite/resolve-vec-extension))]
          (f))
        (finally (.close ds))))))

(use-fixtures :each with-vec-store)

(defn- fact! [content]
  (proto/write-entry *store* :l3 {:kind :fact :content content :user-id "u1"}))

(deftest vec-availability-and-roundtrip-test
  (if-not *vec?*
    (println "  [vec-test] sqlite-vec unavailable — skipping (run `bb sqlite-vec:fetch`)")
    (do
      (testing "graph_vec table is created when sqlite-vec is present"
        (is (true? (sqlite/table-exists? (:ds *store*) "graph_vec"))))

      (testing "writing an L3 fact indexes its embedding; vec-search retrieves it"
        (fact! "BY_SANDBOX_INTEROP controls in-process SCI Java interop")
        (fact! "the L2 retention sweep tombstones episodes after 30 days")
        (let [hits (proto/vec-search *store* "BY_SANDBOX_INTEROP controls in-process SCI Java interop" {:limit 5})]
          (is (seq hits) "semantic hit returned")
          (is (= "BY_SANDBOX_INTEROP controls in-process SCI Java interop"
                 (:content (first hits))) "nearest is the matching fact")
          (is (= 0.0 (:_vec_distance (first hits))) "exact embedding → distance 0")
          (is (= :l3 (:layer (first hits))) "vec hits are hydrated L3 entries")))

      (testing "vec hits fuse into recall's combined RRF result"
        (fact! "GraalVM native-image bakes build-time state into defonce")
        (let [{:keys [layers combined]}
              (recall/recall-layered :store *store*
                                     :query "GraalVM native-image bakes build-time state into defonce")]
          (is (seq (:vec layers)) ":vec is a populated candidate source")
          (is (some #(= "GraalVM native-image bakes build-time state into defonce" (:content %))
                    combined) "the semantically-matched fact appears in combined"))))))

(deftest no-embed-fn-is-non-regressing-test
  (testing "a store without an embed-fn yields no vec hits (FTS-only fallback)"
    (let [ds (sqlite/create-datasource ":memory:")]
      (sqlite/init-schema! ds)
      (let [store (us/create-unified-store :user-id "u2" :ds ds)] ; no :embed-fn
        (try
          (proto/write-entry store :l3 {:kind :fact :content "no embedder here" :user-id "u2"})
          (is (= [] (proto/vec-search store "no embedder here" {:limit 5})))
          (let [{:keys [layers]} (recall/recall-layered :store store :query "no embedder here")]
            (is (empty? (:vec layers)) ":vec empty without an embedding provider"))
          (finally (.close ds)))))))
