;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui.sticky-live-block-test
  "Unit tests for the sticky-bottom semantics in layout/update-live-block!.

   A live-block created with {:sticky-bottom? true} must always end up at
   the bottom of the live-block region — i.e. its :start-idx is the
   highest among all live blocks. New non-sticky blocks created while a
   sticky-bottom block exists must be inserted *before* it, shifting the
   sticky block forward."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(defn- reset-layout-fixture [t]
  (reset! layout/!scrollback [])
  (reset! layout/!live-blocks {})
  (try (t)
       (finally
         (reset! layout/!scrollback [])
         (reset! layout/!live-blocks {}))))

(use-fixtures :each reset-layout-fixture)

(defn- block-start [id]
  (:start-idx (get @layout/!live-blocks id)))

(defn- max-non-sticky-start []
  (->> @layout/!live-blocks
       (remove (fn [[_ b]] (:sticky-bottom? b)))
       (map (fn [[_ b]] (:start-idx b)))
       (apply max 0)))

(deftest sticky-block-stays-below-later-non-sticky-block
  (testing "Creating a non-sticky block after a sticky one inserts before it"
    (layout/update-live-block! :iter-1 ["iter1-line-1" "iter1-line-2"])
    (layout/update-live-block! :feedback ["? prompt"]
                               {:sticky-bottom? true})
    ;; Now a NEW iteration block appears while feedback prompt is up.
    (layout/update-live-block! :iter-2 ["iter2-line-1" "iter2-line-2"
                                        "iter2-line-3"])
    (let [iter-1 (block-start :iter-1)
          iter-2 (block-start :iter-2)
          fb     (block-start :feedback)]
      (is (= 0 iter-1) "iter-1 stays at its original position")
      (is (= 2 iter-2) "iter-2 was inserted immediately before the sticky block")
      (is (= 5 fb)
          "sticky feedback block shifted forward by iter-2's 3 lines"))
    (is (> (block-start :feedback) (max-non-sticky-start))
        "sticky-bottom invariant: its start-idx exceeds every non-sticky block's")))

(deftest scrollback-line-order-matches-block-order
  (testing "After inserting before a sticky, scrollback lines are in the right order"
    (layout/update-live-block! :iter-1 ["A"])
    (layout/update-live-block! :feedback ["Z-prompt"] {:sticky-bottom? true})
    (layout/update-live-block! :iter-2 ["B" "C"])
    (is (= ["A" "B" "C" "Z-prompt"] @layout/!scrollback)
        "iter-1, iter-2 lines come before the sticky prompt line")))

(deftest updating-existing-block-preserves-sticky-flag
  (testing "Re-rendering a sticky block keeps the :sticky-bottom? flag"
    (layout/update-live-block! :feedback ["q1"] {:sticky-bottom? true})
    ;; Update with new content but no opts — flag should persist.
    (layout/update-live-block! :feedback ["q1-updated"])
    (is (true? (:sticky-bottom? (get @layout/!live-blocks :feedback)))
        "sticky-bottom flag survives across updates")))

(deftest non-sticky-block-without-sticky-still-appends-to-tail
  (testing "Plain blocks (no sticky present) keep current append-to-tail behavior"
    (layout/update-live-block! :iter-1 ["A"])
    (layout/update-live-block! :iter-2 ["B"])
    (is (= 0 (block-start :iter-1)))
    (is (= 1 (block-start :iter-2)))
    (is (= ["A" "B"] @layout/!scrollback))))

(deftest dispose-sticky-block-leaves-others-intact
  (testing "Disposing the sticky block removes its lines and unblocks the tail"
    (layout/update-live-block! :iter-1 ["A" "B"])
    (layout/update-live-block! :feedback ["prompt"] {:sticky-bottom? true})
    (layout/dispose-live-block! :feedback)
    (is (nil? (get @layout/!live-blocks :feedback))
        "feedback block removed from registry")
    (is (= ["A" "B"] @layout/!scrollback)
        "prompt line scrubbed from scrollback")
    (is (= 0 (block-start :iter-1))
        "iter-1 untouched")))

(deftest splice-during-iteration-update-keeps-sticky-anchored
  (testing "Re-rendering iter block with more lines shifts sticky forward"
    (layout/update-live-block! :iter-1 ["A"])
    (layout/update-live-block! :feedback ["prompt"] {:sticky-bottom? true})
    ;; iter-1 grows from 1 line to 3 lines — sticky's start-idx should shift +2.
    (layout/update-live-block! :iter-1 ["A1" "A2" "A3"])
    (is (= 0 (block-start :iter-1)))
    (is (= 3 (block-start :feedback))
        "sticky shifted forward by 2 (the delta)")
    (is (= ["A1" "A2" "A3" "prompt"] @layout/!scrollback))))
