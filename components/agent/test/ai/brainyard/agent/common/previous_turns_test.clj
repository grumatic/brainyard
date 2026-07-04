;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.previous-turns-test
  "Batched depth-demotion in append-turn (prompt-cache Phase 3b).

   The rendered previous-turns chain rides an append-only cache zone
   (:history-context), so old entries must stay byte-stable between
   demotions — demote in batches, never slide the boundary by one."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent.common.previous-turns :as prev-turns]))

(defn- turn [i]
  {:question (str "Q" i)
   :answer (str "A" i)
   :iterations [{:iteration 1 :code [(str "code-" i)] :result [(str "r-" i)]}]})

(defn- chain-of
  "Build a chain by appending n turns with the given opts."
  [n & opts]
  (reduce (fn [acc i] (apply prev-turns/append-turn acc (turn i) opts))
          [] (range n)))

(deftest no-demotion-below-hysteresis-band-test
  (testing "up to full-depth + batch − 1 turns, everything stays :full"
    (doseq [n [1 10 19]]
      (is (every? #(= :full (:depth %)) (chain-of n))
          (str n " turns should all be :full (band is 10..19)")))))

(deftest batched-demotion-at-boundary-test
  (testing "crossing full-depth + batch demotes a whole batch at once"
    (let [c19 (chain-of 19)
          c20 (chain-of 20)]
      (is (every? #(= :full (:depth %)) c19))
      (is (= 10 (count (filter #(= :summary (:depth %)) c20))))
      (is (= 10 (count (filter #(= :full (:depth %)) c20))))
      ;; The demoted ten are the OLDEST ten.
      (is (every? #(= :summary (:depth %)) (subvec c20 0 10)))))

  (testing "minimal demotion also lands in batches (full 10 + summary 30 → at 50)"
    (let [c49 (chain-of 49)
          c50 (chain-of 50)]
      (is (zero? (count (filter #(= :minimal (:depth %)) c49))))
      (is (= 10 (count (filter #(= :minimal (:depth %)) c50)))))))

(deftest demoted-entries-byte-stable-between-demotions-test
  (testing "entries below the demotion boundary are IDENTICAL across appends
            within a batch window — the append-only property the
            :history-context cache zone depends on"
    (let [c20 (chain-of 20)
          c29 (chain-of 29)]
      ;; The 10 demoted-at-20 entries must be untouched by appends 21..29.
      (is (= (subvec c20 0 10) (subvec c29 0 10))))
    (let [c50 (chain-of 50)
          c59 (chain-of 59)]
      (is (= (subvec c50 0 40) (subvec c59 0 40))
          "everything below the :full band is stable across a batch window"))))

(deftest recompression-idempotent-test
  (testing "re-running compression over already-demoted entries is a no-op
            (truncate-to-file must not re-fire on already-truncated text)"
    (let [c (chain-of 25)
          ;; Appending one more turn re-runs the compression map over all
          ;; entries; the previously-demoted prefix must be byte-identical.
          c' (prev-turns/append-turn c (turn 25))]
      (is (= (subvec c 0 10) (subvec c' 0 10))))))

(deftest batch-1-reproduces-sliding-behavior-test
  (testing ":demote-batch 1 gives the original slide-by-one boundaries"
    (let [c15 (chain-of 15 :demote-batch 1)]
      (is (= 5 (count (filter #(= :summary (:depth %)) c15))))
      (is (= 10 (count (filter #(= :full (:depth %)) c15)))))
    (let [c45 (chain-of 45 :demote-batch 1)]
      (is (= 5 (count (filter #(= :minimal (:depth %)) c45)))))))

(deftest max-turns-trim-still-applies-test
  (testing "the chain is capped at :max-turns"
    (is (= 100 (count (chain-of 120))))
    (is (= 5 (count (chain-of 20 :max-turns 5))))))
