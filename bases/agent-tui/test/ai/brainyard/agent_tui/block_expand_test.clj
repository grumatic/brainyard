;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.block-expand-test
  "Tests for what an expand/collapse does to scrollback.

   A display-block toggle is a THIRD kind of scrollback mutation — not an
   append (`write-output!`), not a replacement of a block's whole span
   (`update-live-block!`), but an edit made INSIDE rows another producer
   already owns. Everything derived from those rows has to move with them:
   the containing live block's `:line-count`, the `!scrollback-src` entry, and
   the blocks that start after the splice.

   The one that is easy to get wrong is the CONTAINING block. Shifting only
   the blocks that start after the splice leaves that block under-counting its
   own rows, so its next tick rewrites `line-count` of them and orphans the
   rest — a second copy of the body and a second marker for the same block,
   which is what an expand-then-tick used to look like."
  (:require [ai.brainyard.agent-tui.display-block-ui :as block-ui]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.display-block.interface :as block]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- reset-layout! []
  (reset! layout/!scrollback [])
  (reset! layout/!live-blocks {})
  (reset! layout/!scrollback-src [])
  (reset! layout/!layout {:mode :inline :rows 24 :cols 80}))

(use-fixtures :each
  (fn [t]
    (reset-layout!)
    (block/clear!)
    (t)
    (reset-layout!)
    (block/clear!)))

(defn- head+marker
  "Register a block over `total` numbered lines and return the rows a producer
   would put in scrollback: the first `shown` lines plus the collapsed marker."
  [id total shown]
  (let [content (clojure.string/join "\n" (map #(str "line-" %) (range 1 (inc total))))
        text    (block/text-block content {:id id
                                           :max-collapsed-lines shown
                                           :storage :memory})]
    (vec (clojure.string/split-lines text))))

(defn- src-total []
  (reduce + 0 (map :n @layout/!scrollback-src)))

(defn- marker-count []
  (count (filter #(re-find block/marker-re %) @layout/!scrollback)))

(defn- marker-idx []
  (first (keep-indexed (fn [i l] (when (re-find block/marker-re l) i))
                       @layout/!scrollback)))

;; ---------------------------------------------------------------------------
;; 1. Ordinary (non-block) rows
;; ---------------------------------------------------------------------------

(deftest expand-keeps-the-entry-list-accounting-for-every-row
  (let [rows (head+marker "aaa" 60 20)]
    (reset! layout/!scrollback (conj rows "AFTER"))
    (reset! layout/!scrollback-src
            (conj (mapv (fn [r] {:render (constantly [r]) :n 1 :block-id nil}) rows)
                  {:render (constantly ["AFTER"]) :n 1 :block-id nil}))
    (let [delta (block-ui/expand! "aaa" (marker-idx))]
      (is (pos? delta) "expanding a 60-line block over a 20-line head adds rows")
      (is (= (count @layout/!scrollback) (src-total))
          "the entry list still accounts for exactly the rows on screen — a
           drifted list makes the next ensure-src! discard every renderer")
      (is (= 1 (marker-count)) "one marker, now in the expanded state"))))

(deftest expand-then-collapse-round-trips
  (testing "collapse restores the pre-expand rows byte for byte, and leaves the
            entry list consistent"
    (let [rows (head+marker "bbb" 60 20)]
      (reset! layout/!scrollback (conj rows "AFTER"))
      (reset! layout/!scrollback-src
              (conj (mapv (fn [r] {:render (constantly [r]) :n 1 :block-id nil}) rows)
                    {:render (constantly ["AFTER"]) :n 1 :block-id nil}))
      (let [before @layout/!scrollback]
        (block-ui/expand! "bbb" (marker-idx))
        (block-ui/collapse! "bbb" (marker-idx))
        (is (= before @layout/!scrollback))
        (is (= (count @layout/!scrollback) (src-total)))))))

;; ---------------------------------------------------------------------------
;; 2. Rows owned by a live block — the regression that mattered
;; ---------------------------------------------------------------------------

(deftest expanding-inside-a-live-block-grows-that-block
  (let [rows (head+marker "ccc" 60 20)
        n    (count rows)]
    (reset! layout/!scrollback (conj rows "AFTER"))
    (reset! layout/!live-blocks {"blk" {:start-idx 0 :line-count n
                                        :sticky-bottom? false}})
    (reset! layout/!scrollback-src
            [{:render (constantly rows) :n n :block-id "blk" :sticky? false}
             {:render (constantly ["AFTER"]) :n 1 :block-id nil}])
    (let [delta (block-ui/expand! "ccc" (marker-idx))]
      (is (= (+ n delta) (:line-count (get @layout/!live-blocks "blk")))
          "the block whose span CONTAINS the splice grows by delta — it did not
           move, so shifting only the blocks after it leaves it short")
      (is (= (count @layout/!scrollback) (src-total))))))

(deftest a-tick-after-an-expand-orphans-nothing
  (testing "re-rendering the live block replaces ALL of its rows, including the
            ones the expand spliced in — no duplicate body, no second marker"
    (let [rows (head+marker "ddd" 60 20)
          n    (count rows)]
      (reset! layout/!scrollback (conj rows "AFTER"))
      (reset! layout/!live-blocks {"blk" {:start-idx 0 :line-count n
                                          :sticky-bottom? false}})
      (reset! layout/!scrollback-src
              [{:render (constantly rows) :n n :block-id "blk" :sticky? false}
               {:render (constantly ["AFTER"]) :n 1 :block-id nil}])
      (block-ui/expand! "ddd" (marker-idx))
      ;; The next tick: the renderer emits the collapsed form again, which is
      ;; correct — the expansion is a scrollback edit, not block state.
      (layout/update-live-block! "blk" rows)
      (is (= (inc n) (count @layout/!scrollback))
          "back to the block's rows plus the trailing line")
      (is (= 1 (marker-count)) "exactly one marker for the block")
      (is (= "AFTER" (peek @layout/!scrollback))
          "the row after the block is still last")
      (is (= (count @layout/!scrollback) (src-total))))))

(deftest blocks-after-the-splice-still-shift
  (testing "growing the containing block must not cost the old behaviour for
            blocks that start below the splice"
    (let [rows (head+marker "eee" 60 20)
          n    (count rows)]
      (reset! layout/!scrollback (into rows ["tail-a" "tail-b"]))
      (reset! layout/!live-blocks {"blk"  {:start-idx 0 :line-count n
                                           :sticky-bottom? false}
                                   "next" {:start-idx n :line-count 2
                                           :sticky-bottom? false}})
      (reset! layout/!scrollback-src
              [{:render (constantly rows) :n n :block-id "blk" :sticky? false}
               {:render (constantly ["tail-a" "tail-b"]) :n 2 :block-id "next"
                :sticky? false}])
      (let [delta (block-ui/expand! "eee" (marker-idx))]
        (is (= (+ n delta) (:start-idx (get @layout/!live-blocks "next"))))
        (is (= 2 (:line-count (get @layout/!live-blocks "next")))
            "a block below the splice moves; its size is unchanged")))))
