;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-sandbox.bench.sniah
  "S-NIAH (Single Needle-In-A-Haystack) benchmark.
   O(1) complexity — find a single needle embedded in large filler text.
   Based on NVIDIA RULER benchmark, adapted for RLM evaluation."
  (:require [ai.brainyard.clj-sandbox.bench.gen :as gen]
            [ai.brainyard.clj-sandbox.bench.scoring :as scoring]))

(def default-config
  {:context-sizes [8000 32000 64000 128000] ;; in tokens
   :examples-per-size 10
   :max-iterations 20
   :max-depth 1})

(defn generate-suite
  "Generate all S-NIAH examples for given config.
   Returns [{:id str :context str :query str :gold str :context-tokens int ...} ...]"
  [config]
  (let [{:keys [context-sizes examples-per-size]} config]
    (vec
     (for [ctx-tokens context-sizes
           i (range examples-per-size)
           :let [seed (hash [::sniah ctx-tokens i])
                 ctx-chars (gen/chars-for-tokens ctx-tokens)
                 example (gen/generate-sniah-example ctx-chars seed)]]
       (assoc example
              :id (str "sniah-" ctx-tokens "t-" i)
              :context-tokens ctx-tokens
              :max-iterations (:max-iterations config)
              :max-depth (:max-depth config))))))

(defn score-one
  "Score a single S-NIAH result."
  [example result]
  (scoring/exact-match (:gold example) (:answer result)))

(def benchmark-def
  {:name "S-NIAH"
   :description "Single Needle-In-A-Haystack — O(1) retrieval from large context"
   :generate-fn generate-suite
   :score-fn score-one
   :default-config default-config})
