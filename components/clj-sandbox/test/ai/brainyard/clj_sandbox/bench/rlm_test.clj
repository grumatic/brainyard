;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-sandbox.bench.rlm-test
  "Smoke tests for the RLM benchmark suites.
   Pure-data verification — generators must produce well-formed examples,
   scorers must give sensible scores on canned answers, benchmark-defs must
   carry the keys bench.core expects. NO LLM calls fire from these tests."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.set :as set]
            [clojure.string :as str]
            [ai.brainyard.clj-sandbox.bench.rlm :as rlm]
            [ai.brainyard.clj-sandbox.bench.core :as bench-core]))

;; ============================================================================
;; benchmark-def shape — what bench.core/run-benchmark requires
;; ============================================================================

(deftest benchmark-def-shape-test
  (testing "multi-file-summary-def has the keys bench.core expects"
    (let [d rlm/multi-file-summary-def]
      (is (string? (:name d)))
      (is (string? (:description d)))
      (is (fn? (:generate-fn d)))
      (is (fn? (:score-fn d)))
      (is (map? (:default-config d)))))

  (testing "pairwise-dup-def has the keys bench.core expects"
    (let [d rlm/pairwise-dup-def]
      (is (string? (:name d)))
      (is (string? (:description d)))
      (is (fn? (:generate-fn d)))
      (is (fn? (:score-fn d)))
      (is (map? (:default-config d)))))

  (testing "both rlm benchmarks are registered in bench-core/all-benchmarks"
    (let [names (set (map :name bench-core/all-benchmarks))]
      (is (contains? names "Multi-File-Summary"))
      (is (contains? names "Pairwise-Duplicate")))))

;; ============================================================================
;; Multi-file-summary generator
;; ============================================================================

(deftest multi-file-summary-generator-test
  (testing "single example shape"
    (let [ex (rlm/generate-multi-file-summary-example 6 12345)]
      (is (string? (:context ex)))
      (is (string? (:query ex)))
      (is (= 6 (:n-docs ex)))
      (is (= 6 (count (:doc-ids ex))))
      (is (every? #(re-matches #"doc-\d{2}" %) (:doc-ids ex)))
      (is (vector? (get-in ex [:gold :per-doc])))
      (is (= 6 (count (get-in ex [:gold :per-doc]))))
      (is (vector? (get-in ex [:gold :cross-doc-anchors])))
      (is (= 2 (count (get-in ex [:gold :cross-doc-anchors]))))
      (is (pos? (:context-chars ex)))))

  (testing "deterministic — same seed produces same example"
    (let [a (rlm/generate-multi-file-summary-example 6 999)
          b (rlm/generate-multi-file-summary-example 6 999)]
      (is (= (:context a) (:context b)))
      (is (= (:gold a) (:gold b)))))

  (testing "different seeds produce different examples"
    (let [a (rlm/generate-multi-file-summary-example 6 1)
          b (rlm/generate-multi-file-summary-example 6 2)]
      (is (not= (:context a) (:context b)))))

  (testing "context is a doc bundle with `=== doc-NN.md ===` headers"
    (let [ex (rlm/generate-multi-file-summary-example 6 42)
          ctx (:context ex)]
      (is (= 6 (count (re-seq #"=== doc-\d{2}\.md ===" ctx))))))

  (testing "every per-doc anchor is actually present in its source content"
    ;; If anchors weren't in the doc text, no LLM could possibly score.
    (let [ex (rlm/generate-multi-file-summary-example 6 7)
          ctx (str/lower-case (:context ex))]
      (doseq [{:keys [anchors]} (get-in ex [:gold :per-doc])
              anchor anchors]
        (is (str/includes? ctx (str/lower-case anchor))
            (str "anchor " anchor " missing from generated context")))))

  (testing "suite generator produces correct cardinality"
    (let [suite (rlm/generate-multi-file-summary-suite
                 (assoc rlm/multi-file-summary-default-config
                        :examples-per-option 2))]
      ;; n-docs-options = [6 12], examples-per-option = 2 ⇒ 4 total
      (is (= 4 (count suite)))
      (is (every? :id suite))
      (is (every? :max-iterations suite)))))

;; ============================================================================
;; Pairwise-dup generator
;; ============================================================================

(deftest pairwise-dup-generator-test
  (testing "single example shape"
    (let [ex (rlm/generate-pairwise-dup-example 10 3 7)]
      (is (string? (:context ex)))
      (is (string? (:query ex)))
      (is (= 10 (:n-docs ex)))
      (is (= 10 (count (:doc-ids ex))))
      (is (vector? (get-in ex [:gold :clusters])))
      ;; n-clusters = 3 ⇒ 3 ground-truth clusters, each ≥ 2 members
      (is (= 3 (count (get-in ex [:gold :clusters]))))
      (is (every? #(>= (count %) 2) (get-in ex [:gold :clusters])))))

  (testing "deterministic"
    (let [a (rlm/generate-pairwise-dup-example 10 3 555)
          b (rlm/generate-pairwise-dup-example 10 3 555)]
      (is (= (:context a) (:context b)))
      (is (= (:gold a) (:gold b)))))

  (testing "every cluster member appears as a doc in the bundle"
    (let [ex (rlm/generate-pairwise-dup-example 10 3 11)
          all-doc-ids (set (:doc-ids ex))]
      (doseq [cluster (get-in ex [:gold :clusters])]
        (is (set/subset? cluster all-doc-ids)
            (str "cluster " cluster " has members not in doc-ids " all-doc-ids)))))

  (testing "throws when n-docs is too small for n-clusters of size ≥2"
    (is (thrown? clojure.lang.ExceptionInfo
                 (rlm/generate-pairwise-dup-example 3 3 1))))

  (testing "suite generator"
    (let [suite (rlm/generate-pairwise-dup-suite
                 (assoc rlm/pairwise-dup-default-config
                        :examples-per-option 2))]
      ;; n-docs-options = [10 18], examples-per-option = 2 ⇒ 4 total
      (is (= 4 (count suite)))
      (is (every? :id suite)))))

;; ============================================================================
;; Multi-file-summary scorer
;; ============================================================================

(deftest score-multi-file-summary-test
  (let [ex (rlm/generate-multi-file-summary-example 6 100)
        per-doc-anchors (->> (get-in ex [:gold :per-doc])
                             (mapcat :anchors))
        cross-anchors   (get-in ex [:gold :cross-doc-anchors])
        all-anchors     (concat per-doc-anchors cross-anchors)
        perfect-answer  (str/join " " all-anchors)
        empty-answer    ""
        partial-answer  (str/join " " (take (quot (count all-anchors) 2) all-anchors))]

    (testing "perfect answer — score 1.0, correct? true"
      (let [s (rlm/score-multi-file-summary ex {:answer perfect-answer})]
        (is (= 1.0 (:score s)))
        (is (true? (:correct? s)))
        (is (= 1.0 (:per-doc-recall s)))
        (is (= 1.0 (:cross-doc-recall s)))))

    (testing "empty answer — score 0.0, correct? false"
      (let [s (rlm/score-multi-file-summary ex {:answer empty-answer})]
        (is (= 0.0 (:score s)))
        (is (false? (:correct? s)))
        (is (= (count per-doc-anchors) (count (:missing-anchors s))))))

    (testing "partial answer scores between 0 and 1"
      (let [s (rlm/score-multi-file-summary ex {:answer partial-answer})]
        (is (< 0.0 (:score s)))
        (is (< (:score s) 1.0))))

    (testing "case-insensitive matching — uppercase anchors still hit"
      (let [s (rlm/score-multi-file-summary
               ex
               {:answer (str/upper-case perfect-answer)})]
        (is (= 1.0 (:score s)))))

    (testing "dashed and spaced anchor forms both match"
      ;; "lattice-based" → "lattice based" should still hit
      (let [variant (str/replace perfect-answer "-" " ")
            s (rlm/score-multi-file-summary ex {:answer variant})]
        (is (= 1.0 (:score s)))))))

;; ============================================================================
;; Pairwise-dup scorer
;; ============================================================================

(deftest score-pairwise-dup-test
  (let [ex (rlm/generate-pairwise-dup-example 10 3 200)
        gold-clusters (get-in ex [:gold :clusters])
        cluster-line (fn [c] (str/join ", " (sort c)))
        perfect-answer
        (str/join "\n" (map cluster-line gold-clusters))]

    (testing "perfect answer — gold-matched = gold-total, score = 1.0"
      (let [s (rlm/score-pairwise-dup ex {:answer perfect-answer})]
        (is (= 1.0 (:score s)))
        (is (true? (:correct? s)))
        (is (= (count gold-clusters) (:gold-matched s)))
        (is (zero? (:extra-clusters s)))))

    (testing "empty answer — recall 0"
      (let [s (rlm/score-pairwise-dup ex {:answer ""})]
        (is (= 0.0 (:score s)))
        (is (zero? (:gold-matched s)))))

    (testing "answer naming all docs in one giant cluster — superset matches"
      ;; A single line listing every doc-id is a superset of every gold
      ;; cluster, so recall is 1.0. There are no spurious clusters since
      ;; every gold cluster's members appear there.
      (let [all-line (str/join ", " (:doc-ids ex))
            s (rlm/score-pairwise-dup ex {:answer all-line})]
        (is (= (count gold-clusters) (:gold-matched s)))))

    (testing "spurious cluster (no overlap with gold) → small precision penalty"
      ;; Use fabricated doc-ids NOT in the bundle to guarantee zero overlap.
      ;; (The scorer extracts doc-NN tokens regardless of whether the id
      ;; really exists — what matters is that the proposed set has no
      ;; intersection with any gold cluster.)
      (let [spurious-line "doc-98, doc-99"
            answer (str perfect-answer "\n" spurious-line)
            s (rlm/score-pairwise-dup ex {:answer answer})]
        (is (= (count gold-clusters) (:gold-matched s)))
        (is (= 1 (:extra-clusters s)))
        (is (< (:score s) 1.0))
        (is (>= (:score s) 0.7))))

    (testing "partial answer — only some clusters matched"
      (let [first-cluster (first gold-clusters)
            answer (cluster-line first-cluster)
            s (rlm/score-pairwise-dup ex {:answer answer})]
        (is (= 1 (:gold-matched s)))
        (is (= (count gold-clusters) (:gold-total s)))))))

;; ============================================================================
;; Generators integrate cleanly with bench.core/run-benchmark wiring
;; (check the shape only — do NOT actually invoke an LLM)
;; ============================================================================

(deftest harness-integration-test
  (testing "multi-file-summary suite is consumable by bench.core's loop shape"
    (let [suite (rlm/generate-multi-file-summary-suite
                 {:n-docs-options [6]
                  :examples-per-option 1
                  :max-iterations 5
                  :max-depth 1})]
      (is (= 1 (count suite)))
      (let [ex (first suite)]
        ;; bench.core/run-one-example reads :id, :query, :context,
        ;; :max-iterations, :max-depth from the example map.
        (is (string? (:id ex)))
        (is (string? (:query ex)))
        (is (string? (:context ex)))
        (is (integer? (:max-iterations ex)))
        (is (integer? (:max-depth ex))))))

  (testing "pairwise-dup suite is consumable by bench.core's loop shape"
    (let [suite (rlm/generate-pairwise-dup-suite
                 {:n-docs-options [10]
                  :n-clusters 3
                  :examples-per-option 1
                  :max-iterations 5
                  :max-depth 1})]
      (is (= 1 (count suite)))
      (let [ex (first suite)]
        (is (string? (:id ex)))
        (is (string? (:query ex)))
        (is (string? (:context ex)))))))

;; ============================================================================
;; Agent-dispatch runner — soft-resolve smoke
;; (does NOT actually run the agent — just verifies the runner namespace is
;; reachable and the bundle-spill helper produces real files.)
;; ============================================================================

(deftest agent-dispatch-soft-resolve-test
  (testing "bundle-spill helper lays files out correctly"
    ;; Use the private function via #'-style access.
    (let [spill #'rlm/spit-bundle-to-dir!
          ex (rlm/generate-multi-file-summary-example 6 999)
          dir (spill (:context ex))
          files (->> (.listFiles (java.io.File. ^String dir))
                     (map #(.getName ^java.io.File %))
                     sort
                     vec)]
      (is (= 6 (count files)))
      (is (every? #(re-matches #"doc-\d{2}\.md" %) files))
      ;; Each spilled file is non-empty
      (is (every? pos? (map #(.length (java.io.File. (str dir "/" %))) files)))))

  (testing "run-one-example-via-agent is callable (defaults are well-formed)"
    ;; We don't invoke it (would dispatch a real LLM call) — just verify
    ;; the function var is reachable and accepts the documented shape.
    (is (fn? rlm/run-one-example-via-agent))))
