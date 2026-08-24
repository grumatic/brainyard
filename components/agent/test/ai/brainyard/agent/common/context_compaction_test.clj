;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.context-compaction-test
  "Guards the search/commit split in `compact-previous-turns` (design §7.2).

   The compactor picks one of four progressively tighter passes. It used to pick
   by *applying* each pass to the previous pass's output, so an answer could be
   truncated up to four times. `truncate-to-file` survives that only by reading
   its temp file back to recover the original — and the sandbox cache evicts
   oldest-first at 200 files. When the file is gone, re-truncation silently
   re-bases: the marker still promises the full content while the file holds the
   truncated text.

   Two things therefore need locking down:

   1. **No read-backs.** The search must price candidates without touching the
      filesystem, so no pass depends on a file surviving. This is the
      correctness property; the I/O saving is incidental.
   2. **Same output.** The split is only safe because depth is a pure function
      of recency + pass parameters, and truncating once from the original equals
      truncating repeatedly *when recovery works*. If that ever stops holding,
      the split silently changes what the model reads."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.context-compaction :as cc]))

(def ^:private recompress #'cc/recompress-turns)
(def ^:private est-tokens #'cc/estimate-turns-tokens)

(defn- turns
  "n turns with answers far over every pass limit, so each pass truncates."
  [n]
  (vec (for [i (range n)]
         {:question   (str "q" i)
          :answer     (str "A" i "-" (apply str (repeat 3000 "z")))
          :iterations [{:n 1}]
          :depth      :full})))

(defn- old-compact
  "The pre-§7.2 algorithm: apply each pass for real, cumulatively, measuring as
   it goes. Kept here as the differential oracle — the new implementation must
   agree with it on every output field."
  [ts target]
  (if (empty? ts)
    ts
    (let [passes [[3 10 2000] [1 5 1000] [0 0 400] [0 0 200]]]
      (loop [t ts [[fd sd al] & more] passes]
        (if (nil? fd)
          (subvec (vec t) (max 0 (- (count t) 10)))
          (let [c (recompress t fd sd al)]
            (if (<= (est-tokens c) target)
              c
              (if (seq more)
                (recur c more)
                (subvec (vec c) (max 0 (- (count c) 10)))))))))))

(defn- normalize
  "Strip the temp-file id so two runs are comparable — the id is random per
   file, and it is the only thing that legitimately differs."
  [ts]
  (mapv #(update % :answer (fn [a] (when a (str/replace a #"/[0-9a-f]{8}\.txt" "/ID.txt"))))
        ts))

(defn- cache-path?
  "True for a path under the truncation cache. Scoping the counter to these is
   the point: a bare `slurp` counter also catches cold config/dirs resolution on
   the first call in a process, which is unrelated to re-truncation and made an
   earlier hand measurement compare a cold run against a warm one."
  [x]
  (and (or (string? x) (instance? java.io.File x))
       (str/includes? (str x) "truncation")))

(defn- count-io
  "Run f, returning [reads writes] of TRUNCATION-CACHE filesystem calls."
  [f]
  (let [reads (atom 0) writes (atom 0)
        real-slurp slurp real-spit spit]
    (with-redefs [slurp (fn [x & a] (when (cache-path? x) (swap! reads inc))
                          (apply real-slurp x a))
                  spit  (fn [x & a] (when (cache-path? x) (swap! writes inc))
                          (apply real-spit x a))]
      (f))
    [@reads @writes]))

;; Targets chosen to select each pass in turn, plus one nothing can satisfy.
(def ^:private scenarios
  [["loose target"  12 100000]
   ["mid target"    12 6000]
   ["tight target"  12 1500]
   ["no pass fits"  20 50]])

(deftest search-performs-no-file-reads
  ;; THE correctness property. A read here is a re-truncation depending on a
  ;; temp file that the 200-file cache cap may already have evicted.
  (doseq [[label n target] scenarios]
    (testing label
      (let [ts (turns n)
            [reads _] (count-io #(cc/compact-previous-turns ts target))]
        (is (zero? reads)
            (str label ": compaction read " reads
                 " temp file(s); the search must not depend on any file surviving"))))))

(deftest output-matches-the-cumulative-algorithm
  (doseq [[label n target] scenarios]
    (testing label
      (let [ts  (turns n)
            old (old-compact ts target)
            new (cc/compact-previous-turns ts target)]
        (is (= (count old) (count new)) (str label ": turn count"))
        (is (= (mapv :depth old) (mapv :depth new)) (str label ": depth assignment"))
        (is (= (normalize old) (normalize new)) (str label ": answers"))))))

(deftest truncation-still-happens-and-stays-recoverable
  ;; The split must not accidentally stop truncating, nor drop the recovery
  ;; pointer — a dry-run string names a placeholder file and must never be what
  ;; the caller gets back.
  (let [out (cc/compact-previous-turns (turns 12) 1500)
        answers (keep :answer out)]
    (is (seq answers))
    (is (every? #(str/includes? % "TRUNCATED (original:") answers)
        "every over-limit answer carries a truncation notice")
    (is (not-any? #(str/includes? % "/00000000.txt") answers)
        "no dry-run placeholder path leaked into committed output")))

(deftest empty-and-small-inputs-are-untouched
  (is (= [] (cc/compact-previous-turns [] 1000)))
  (let [small [{:question "q" :answer "short" :iterations [] :depth :full}]]
    (is (= small (cc/compact-previous-turns small 100000))
        "a turn already under target passes through unchanged")))
