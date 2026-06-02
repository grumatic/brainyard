;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.bench.scoring
  "Scoring functions for RLM benchmarks.
   Pure functions — no LLM or I/O dependency."
  (:require [clojure.string :as str]))

;; ============================================================================
;; Answer Normalization
;; ============================================================================

(defn normalize-answer
  "Strip common LLM answer prefixes and whitespace for fair comparison."
  [s]
  (when s
    (-> (str s)
        str/trim
        (str/replace #"(?i)^(the\s+(answer|value|result|number)\s+(is|=)\s*:?\s*)" "")
        (str/replace #"(?i)^(answer\s*:?\s*)" "")
        (str/replace #"[\"'`]" "")
        str/trim)))

;; ============================================================================
;; Scoring Functions
;; ============================================================================

(defn exact-match
  "Score 1.0 if answer matches gold (case-insensitive, normalized), 0.0 otherwise.
   Returns {:score double :correct? bool :gold str :answer str}"
  [gold answer]
  (let [g (str/lower-case (str/trim (str gold)))
        a (str/lower-case (normalize-answer (str answer)))
        correct? (or (= g a)
                     ;; Also check if the gold value appears as a substring
                     (str/includes? a g))]
    {:score (if correct? 1.0 0.0)
     :correct? correct?
     :gold gold
     :answer (str answer)}))

(defn numeric-exponential-decay
  "Score = 0.75^|gold - predicted|. Parses answer to extract first number.
   Returns {:score double :gold long :predicted long :delta long}"
  [gold answer]
  (let [gold-n (if (number? gold) (long gold) (parse-long (str gold)))
        ;; Extract first number from answer string
        answer-str (normalize-answer (str answer))
        predicted (some-> (re-find #"-?\d+" answer-str) parse-long)]
    (if (and gold-n predicted)
      (let [delta (Math/abs (- gold-n predicted))]
        {:score (Math/pow 0.75 delta)
         :gold gold-n
         :predicted predicted
         :delta delta})
      {:score 0.0
       :gold gold-n
       :predicted predicted
       :delta nil
       :parse-error (when-not predicted (str "Could not parse number from: " answer-str))})))

;; ============================================================================
;; Aggregation
;; ============================================================================

(defn aggregate-scores
  "Compute aggregate statistics from a seq of score maps.
   Returns {:mean :median :min :max :count :correct-count :accuracy}"
  [score-maps]
  (let [scores (mapv :score score-maps)
        n (count scores)]
    (if (zero? n)
      {:mean 0.0 :median 0.0 :min 0.0 :max 0.0
       :count 0 :correct-count 0 :accuracy 0.0}
      (let [sorted (sort scores)
            correct (count (filter :correct? score-maps))]
        {:mean (/ (reduce + 0.0 scores) n)
         :median (if (odd? n)
                   (nth sorted (quot n 2))
                   (/ (+ (nth sorted (dec (quot n 2)))
                         (nth sorted (quot n 2)))
                      2.0))
         :min (first sorted)
         :max (last sorted)
         :count n
         :correct-count correct
         :accuracy (/ (double correct) n)}))))
