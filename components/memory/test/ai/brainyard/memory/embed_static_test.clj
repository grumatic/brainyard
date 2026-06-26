;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.embed-static-test
  "CR-MEM-21 self-contained Model2Vec embeddings. Skips when the bundled
  model is absent (CI without `bb model2vec:fetch`)."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.memory.core.embed-static :as es]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.unified-store :as us]
            [ai.brainyard.memory.interface.protocol :as proto]))

(defn- cosine [a b] (reduce + (map * a b)))
(defn- norm [v] (Math/sqrt (reduce + (map #(* % %) v))))

(deftest static-embeddings-test
  (if-not (es/available?)
    (println "  [embed-static-test] model absent — skipping (run `bb model2vec:fetch`)")
    (let [ef (es/static-embed-fn)
          e  (fn [s] (first (ef [s])))]
      (testing "model metadata"
        (is (= 256 (es/dimensions)) "potion-base-8M is 256-dim")
        (is (some? ef)))

      (testing "vectors are 256-dim and L2-normalized"
        (let [v (e "BY_SANDBOX_INTEROP configures clj-sandbox")]
          (is (= 256 (count v)))
          (is (< (Math/abs (- 1.0 (norm v))) 1e-4) "unit length")))

      (testing "deterministic — same text ⇒ identical vector"
        (is (= (e "GraalVM native-image") (e "GraalVM native-image"))))

      (testing "empty string ⇒ zero vector"
        (is (zero? (norm (e "")))))

      (testing "exactness anchor — matches the reference model2vec vector"
        ;; First components of the gold vector for this exact string
        ;; (generated with the python model2vec lib). Locks tokenizer +
        ;; pooling + normalization correctness.
        (let [v (e "the cat sat on the mat")
              gold [-0.07842 -0.0541 -0.07419 -0.10808 -0.02288 -0.01289]]
          (doseq [[i g] (map-indexed vector gold)]
            (is (< (Math/abs (- g (nth v i))) 1e-3)
                (str "component " i " ≈ reference")))))

      (testing "semantic signal — related ≫ unrelated"
        (let [related   (cosine (e "seatbelt sandbox write containment")
                                (e "OS write-containment policy for the sandbox"))
              unrelated (cosine (e "seatbelt sandbox write containment")
                                (e "how to bake a chocolate cake"))]
          (is (> related 0.5) "related technical phrases are close")
          (is (< unrelated 0.25) "unrelated phrases are far")
          (is (> related unrelated)))))))

(deftest static-embed-recall-integration-test
  (if-not (and (es/available?) (sqlite/resolve-vec-extension))
    (println "  [embed-static-test] model or sqlite-vec absent — skipping integration")
    (let [ds (sqlite/create-datasource ":memory:")]
      (try
        ;; graph_vec must be created at the embedder's native dim (256), not
        ;; the 768 default — this is the dim-coupling the manager threads.
        (sqlite/init-schema! ds :embed-dims (es/dimensions))
        (let [store (us/create-unified-store :user-id "u1" :ds ds
                                             :embed-fn (es/static-embed-fn))]
          (proto/write-entry store :l3 {:kind :fact :user-id "u1"
                                        :content "the seatbelt sandbox enforces OS write containment"})
          (proto/write-entry store :l3 {:kind :fact :user-id "u1"
                                        :content "the user prefers tabs over spaces in source files"})
          (testing "semantic vec-search retrieves the related fact first"
            (let [hits (proto/vec-search store "write-containment policy for the sandbox" {:limit 2})]
              (is (seq hits))
              (is (re-find #"write containment" (:content (first hits)))
                  "the containment fact ranks above the unrelated preference fact"))))
        (finally (.close ds))))))
