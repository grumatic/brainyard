
;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.tui.width-fastpath-test
  "Guards for the display-width fast paths (issue #15).

   Three optimisations share one hazard: each skips work for characters it
   believes cannot be wide or zero-width, and each is WRONG if a skipped
   character can still be promoted by a following U+FE0F. A keycap is
   U+0031 U+FE0F U+20E3 -- an ASCII base that renders two columns -- so every
   fast path has to keep the VS16 look-ahead that the speedup tempts you to
   drop."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent.tui.format :as fmt]))

(def ^:private wbcp @#'fmt/width-by-codepoint)
(def ^:private wbc  @#'fmt/width-by-cluster)
(def ^:private wide? @#'fmt/wide-codepoint?)

(defn- cps [& xs] (apply str (map #(String. (Character/toChars %)) xs)))

;; ============================================================================
;; The trap the ASCII fast path invites
;; ============================================================================

(deftest ascii-base-promoted-by-vs16-still-counts-two
  (testing "U+0031 U+FE0F U+20E3 -- the base is ASCII and must NOT take the
            1-column fast path"
    (is (= 2 (wbcp (cps 0x31 0xFE0F 0x20E3))) "keycap 1")
    (is (= 2 (wbcp (cps 0x23 0xFE0F 0x20E3))) "keycap #")
    (is (= 2 (wbc  (cps 0x31 0xFE0F 0x20E3))) "keycap 1, clustered")))

(deftest plain-ascii-is-one-column-each
  (is (= 5 (wbcp "hello")))
  (is (= 0 (wbcp "")))
  (is (= 1 (wbcp " ")))
  (testing "a digit NOT followed by VS16 stays narrow"
    (is (= 1 (wbcp "1")))
    (is (= 2 (wbcp "12")))))

;; ============================================================================
;; The sub-0x300 guard: nothing below U+0300 is wide or zero-width
;; ============================================================================

(deftest nothing-below-0x300-is-wide-or-zero-width
  (testing "the guard's premise, asserted rather than assumed"
    (is (every? (fn [cp] (= 1 (wbcp (String/valueOf (char cp)))))
                (range 0x20 0x300))
        "every printable codepoint below U+0300 measures exactly 1")))

(deftest combining-mark-at-0x300-is-still-zero-width
  (testing "immediately above the guard boundary the general path must run"
    (is (= 1 (wbcp (cps 0x65 0x301))) "e + combining acute = 1 column")
    (is (= 0 (wbcp (cps 0x301))) "a lone combining mark adds nothing")))

;; ============================================================================
;; The wide-codepoint guard: nothing below U+1100 is wide
;; ============================================================================

(deftest wide-guard-boundary
  (is (false? (boolean (wide? 0x10FF))) "just below the first wide range")
  (is (true?  (boolean (wide? 0x1100))) "first Hangul Jamo IS wide")
  (is (= 2 (wbcp (cps 0x1100))))
  (is (= 1 (wbcp (cps 0x10FF)))))

;; ============================================================================
;; The cluster path measured without a per-cluster substring
;; ============================================================================

(deftest clustered-sequences-are-two-columns
  (testing "a cluster is one cell slot regardless of how many codepoints it has"
    (is (= 2 (wbc (cps 0x1F468 0x200D 0x1F469 0x200D 0x1F467 0x200D 0x1F466)))
        "ZWJ family")
    (is (= 2 (wbc (cps 0x1F1F0 0x1F1F7))) "regional-indicator flag")
    (is (= 2 (wbc (cps 0x1F44D 0x1F3FD))) "thumbs up + skin tone")
    (is (= 2 (wbc (cps 0x26A0 0xFE0F))) "warning sign promoted by VS16")
    (is (= 1 (wbc (cps 0x26A0))) "and NOT promoted without it")))

(deftest vs16-does-not-leak-across-a-cluster-boundary
  (testing "a VS16 belonging to the NEXT cluster must not promote this one

           This is why the cluster scan is bounded by the cluster, not by the
           rest of the string."
    (let [s (str (cps 0x41) (cps 0x26A0 0xFE0F))]   ;; "A" then a VS16 emoji
      (is (= 3 (wbc s)) "A(1) + promoted warning(2)"))))

(deftest ansi-escapes-cost-nothing-on-either-path
  (let [esc (cps 0x1b)
        s   (str esc "[31mred" esc "[0m")]
    (is (= 3 (wbcp s)))
    (is (= 3 (wbc s)))))
