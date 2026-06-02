;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.bench.retrieval
  "Simple key-value retrieval baseline benchmark.
   O(1) complexity — tests that RLM handles trivial tasks efficiently.
   Paper 2 finding: RLM can degrade performance on simple retrieval."
  (:require [ai.brainyard.clj-sandbox.bench.gen :as gen]
            [ai.brainyard.clj-sandbox.bench.scoring :as scoring]))

(def default-config
  {:n-pairs-options [5 10 20 50]
   :examples-per-option 5
   :max-iterations 10 ;; should finish fast for O(1) tasks
   :max-depth 1})

(defn generate-suite
  "Generate all simple retrieval examples for given config."
  [config]
  (let [{:keys [n-pairs-options examples-per-option]} config]
    (vec
     (for [n-pairs n-pairs-options
           i (range examples-per-option)
           :let [seed (hash [::retrieval n-pairs i])
                 example (gen/generate-retrieval-example n-pairs seed)]]
       (assoc example
              :id (str "retrieval-" n-pairs "p-" i)
              :max-iterations (:max-iterations config)
              :max-depth (:max-depth config))))))

(defn score-one
  "Score a single retrieval result."
  [example result]
  (scoring/exact-match (:gold example) (:answer result)))

(def benchmark-def
  {:name "Simple-Retrieval"
   :description "Simple key-value retrieval baseline — O(1) complexity"
   :generate-fn generate-suite
   :score-fn score-one
   :default-config default-config})
