;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.display-block.marker-test
  (:require [ai.brainyard.display-block.core.marker :as marker]
            [clojure.test :refer [deftest is testing]]))

(deftest build-and-parse-roundtrip
  (testing "collapsed line with hint"
    (let [line (marker/collapsed-line "abc123" "+500 lines"
                                      :hint "Enter: expand, Ctrl-O: edit")]
      (is (= "[*Block:abc123* collapsed: +500 lines | Enter: expand, Ctrl-O: edit]" line))
      (let [p (marker/parse line)]
        (is (= "abc123" (:id p)))
        (is (= :collapsed (:state p)))
        (is (= "+500 lines" (:summary p)))
        (is (= "Enter: expand, Ctrl-O: edit" (:hint p))))))

  (testing "expanded line without hint"
    (let [line (marker/expanded-line "ff00aa" "100 of 500 lines")]
      (is (= "[*Block:ff00aa* expanded: 100 of 500 lines]" line))
      (let [p (marker/parse line)]
        (is (= "ff00aa" (:id p)))
        (is (= :expanded (:state p)))
        (is (= "100 of 500 lines" (:summary p)))
        (is (nil? (:hint p)))))))

(deftest parse-rejects-non-markers
  (is (nil? (marker/parse "just text")))
  (is (nil? (marker/parse "")))
  (is (nil? (marker/parse nil)))
  (is (nil? (marker/parse "[*Block:abc* unknown: foo]"))))

(deftest parse-tolerates-surrounding-text
  (let [line "  ┃ stuff [*Block:zz1* collapsed: +5 lines] more stuff ┃"
        p (marker/parse line)]
    (is (= "zz1" (:id p)))
    (is (= :collapsed (:state p)))
    (is (= "+5 lines" (:summary p)))))

(deftest scan-lines-orders-by-index
  (let [lines ["plain"
               (marker/collapsed-line "a1" "+1 lines")
               "x"
               (marker/expanded-line "b2" "10 of 20 lines" :hint "Enter: collapse")
               "y"]
        hits (marker/scan-lines lines)]
    (is (= 2 (count hits)))
    (is (= [1 3] (mapv :line-idx hits)))
    (is (= ["a1" "b2"] (mapv :id hits)))
    (is (= [:collapsed :expanded] (mapv :state hits)))))

(deftest scan-lines-respects-bounds
  (let [lines [(marker/collapsed-line "a" "+1 lines")
               (marker/collapsed-line "b" "+2 lines")
               (marker/collapsed-line "c" "+3 lines")]
        hits (marker/scan-lines lines 1 3)]
    (is (= ["b" "c"] (mapv :id hits)))))
