;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-sandbox.bench.oolong
  "OOLONG benchmark — synthetic classification aggregation.
   O(N) complexity — must process nearly all items to answer.
   Based on oolongbench/oolong-synth, simplified for synthetic generation."
  (:require [ai.brainyard.clj-sandbox.bench.gen :as gen]
            [ai.brainyard.clj-sandbox.bench.scoring :as scoring]))

(def default-categories
  ["abbreviation" "entity" "description" "human" "location" "numeric"])

(def default-config
  {:context-sizes [32000 64000]
   :examples-per-size 10
   :categories default-categories
   :query-types [:most-frequent :count-category]
   :max-iterations 20
   :max-depth 1})

(defn- items-for-tokens
  "Estimate number of items that fit in a token budget.
   Each item is ~60 chars (~15 tokens)."
  [token-count]
  (quot token-count 15))

(defn generate-suite
  "Generate all OOLONG examples for given config."
  [config]
  (let [{:keys [context-sizes examples-per-size categories query-types]} config]
    (vec
     (for [ctx-tokens context-sizes
           i (range examples-per-size)
           :let [seed (hash [::oolong ctx-tokens i])
                 n-items (items-for-tokens ctx-tokens)
                 query-type (nth query-types (mod i (count query-types)))
                 example (gen/generate-oolong-example n-items categories query-type seed)]]
       (assoc example
              :id (str "oolong-" ctx-tokens "t-" (name query-type) "-" i)
              :context-tokens ctx-tokens
              :max-iterations (:max-iterations config)
              :max-depth (:max-depth config))))))

(defn score-one
  "Score one OOLONG result. Exact match for labels, exponential decay for counts."
  [example result]
  (case (:query-type example)
    :most-frequent (scoring/exact-match (:gold example) (:answer result))
    :count-category (scoring/numeric-exponential-decay (:gold example) (:answer result))))

(def benchmark-def
  {:name "OOLONG"
   :description "Synthetic classification aggregation — O(N) processing"
   :generate-fn generate-suite
   :score-fn score-one
   :default-config default-config})
