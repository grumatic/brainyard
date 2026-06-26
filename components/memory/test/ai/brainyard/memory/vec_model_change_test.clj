;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.vec-model-change-test
  "CR-MEM-21 embed-model-change detection + safe-disable + rebuild. A model
  swap must never silently serve mixed-space rankings."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.graph :as graph]
            [ai.brainyard.memory.core.unified-store :as us]
            [ai.brainyard.memory.interface.protocol :as proto]))

;; deterministic 768-dim stub (matches the default graph_vec dim); identical
;; per text. The fingerprint keys on model-id, not vector content, so one stub
;; serves both "models".
(defn- stub-embed [texts]
  (mapv (fn [t]
          (let [v (double-array 768 0.0)]
            (doseq [i (range 4)]
              (aset v (mod (Math/abs (hash (str t "#" i))) 768) (double (inc i))))
            (vec v)))
        texts))

;; =====================================================
;; Pure fingerprint reconcile
;; =====================================================

(deftest reconcile-fingerprint-test
  (let [ds (sqlite/create-datasource ":memory:")]
    (try
      (sqlite/init-schema! ds)
      (testing "fresh index stamps and is not stale"
        (is (false? (:stale? (graph/reconcile-vec-model! ds "model-A" 768)))))
      (testing "same model is consistent"
        (is (false? (:stale? (graph/reconcile-vec-model! ds "model-A" 768)))))
      (testing "different model with no rows just re-stamps (not stale)"
        (is (false? (:stale? (graph/reconcile-vec-model! ds "model-B" 768))))
        (is (= "model-B|768" (sqlite/get-metadata ds "graph_vec_model"))))
      (testing "no embedder configured ⇒ never stale"
        (is (false? (:stale? (graph/reconcile-vec-model! ds nil 768)))))
      (finally (.close ds)))))

;; =====================================================
;; End-to-end safe-disable + rebuild (needs sqlite-vec)
;; =====================================================

(deftest model-change-pauses-then-rebuild-test
  (sqlite/reset-vec-extension!)
  (if-not (sqlite/resolve-vec-extension)
    (println "  [vec-model-change] sqlite-vec absent — skipping integration")
    (let [ds (sqlite/create-datasource ":memory:")]
      (try
        (sqlite/init-schema! ds)
        ;; 1. Build the index with model-A.
        (let [store-a (us/create-unified-store :user-id "u1" :ds ds
                                               :embed-fn stub-embed :embed-model-id "model-A" :embed-dims 768)]
          (proto/write-entry store-a :l3 {:kind :fact :user-id "u1" :content "fact one about sandboxes"})
          (proto/write-entry store-a :l3 {:kind :fact :user-id "u1" :content "fact two about graals"})
          (is (false? (:stale? (us/vec-status store-a))))
          (is (seq (proto/vec-search store-a "sandboxes" {:limit 2})) "vec works with model-A"))

        ;; 2. Reopen the SAME db with model-B → stale.
        (let [store-b (us/create-unified-store :user-id "u1" :ds ds
                                               :embed-fn stub-embed :embed-model-id "model-B" :embed-dims 768)]
          (testing "model change is detected and recall is paused"
            (let [st (us/vec-status store-b)]
              (is (true? (:stale? st)))
              (is (= "model-A|768" (:was st)))
              (is (= "model-B|768" (:now st)))
              (is (= 2 (:count st))))
            (is (= [] (proto/vec-search store-b "sandboxes" {:limit 2}))
                "vec-search returns nothing while stale (FTS still serves elsewhere)"))

          (testing "a new write does NOT pollute the old-model index"
            (proto/write-entry store-b :l3 {:kind :fact :user-id "u1" :content "fact three while stale"})
            (is (= 2 (graph/vec-row-count ds)) "no new vector added while stale"))

          (testing "reembed rebuilds, clears the flag, resumes recall"
            (let [r (us/reembed! store-b)]
              (is (= 3 (:facts r)) "all three L3 facts re-embedded under model-B"))
            (is (false? (:stale? (us/vec-status store-b))))
            (is (= "model-B|768" (sqlite/get-metadata ds "graph_vec_model")))
            (is (seq (proto/vec-search store-b "sandboxes" {:limit 3})) "vec recall resumes")))
        (finally (.close ds))))))
