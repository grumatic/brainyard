;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.grapheme-stepping-test
  "The string-walking loops in this base must step by `fmt/next-unit`, not by
   `Character/charCount`.

   These loops decide wrap points, truncation points and cursor positions. When
   they step by codepoint while `display-width` measures by grapheme cluster,
   a cut lands inside a ZWJ sequence: the two halves render as separate glyphs,
   so the line the cut was narrowing gets WIDER. This suite is the guard that
   they keep tracking the regime.

   Non-ASCII is written as \\uXXXX escapes so a mangled transfer cannot quietly
   change what is asserted."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.tui.format :as fmt]
            [ai.brainyard.agent.tui.terminal-caps :as caps]
            [ai.brainyard.agent-tui.session]
            [ai.brainyard.agent-tui.terminal]))

(use-fixtures :each (fn [t] (caps/reset-negotiation!) (t) (caps/reset-negotiation!)))

(def FAMILY "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66")
(def WARN   "\u26A0\uFE0F")
(def ZWJ    "\u200D")

;; The loops under test are private -- reached by var so the test pins the real
;; implementation rather than a re-implementation of it.
(def wrap-snippet #'ai.brainyard.agent-tui.session/wrap-snippet-to-width)
(def truncate-w   #'ai.brainyard.agent-tui.session/truncate-to-display-width)
(def wrap-line    #'ai.brainyard.agent-tui.terminal/wrap-line-to-width)

(defn- starts-with-bare-zwj? [rows]
  (boolean (some #(str/starts-with? (str %) ZWJ) rows)))

(deftest wrapping-never-starts-a-row-with-a-bare-zwj
  (testing "a row beginning with ZWJ means the previous cut landed inside a
            joined sequence -- true in BOTH regimes: without clustering the ZWJ
            still belongs to the unit before it"
    (doseq [regime [false true]]
      (caps/set-grapheme-clustering! regime :test)
      (let [line (str "ab " FAMILY " cd")]
        (doseq [w (range 2 16)]
          (is (not (starts-with-bare-zwj? (wrap-snippet line w)))
              (str "wrap-snippet split a cluster at width " w ", clustering=" regime))
          (is (not (starts-with-bare-zwj? (map :text (wrap-line line 0 w))))
              (str "wrap-line split a cluster at width " w ", clustering=" regime)))))))

(deftest wrapped-rows-fit-the-width-they-were-given
  (testing "each row must measure within the requested width by the SAME
            display-width the wrapper consulted -- the invariant that breaks
            when stepping and measuring use different units"
    (doseq [regime [false true]]
      (caps/set-grapheme-clustering! regime :test)
      (let [line (str "ab " FAMILY " cd " WARN)]
        (doseq [w (range 4 16)]
          (doseq [row (wrap-snippet line w)]
            ;; A single unit wider than the budget is allowed to overflow --
            ;; nothing can render a 2-column glyph in 1 column.
            (is (or (<= (fmt/display-width row) w)
                    (= 1 (count (loop [i 0 n 0]
                                  (if (>= i (count row)) (repeat n :u)
                                      (recur (second (fmt/next-unit row i)) (inc n)))))))
                (str "row " (pr-str row) " exceeds width " w " (clustering=" regime ")"))))))))

(deftest clustering-makes-a-joined-emoji-wrap-as-one
  (testing "the payoff: with clustering the family emoji occupies 2 columns, so
            a 8-column budget holds the whole line; without it, 8 columns of
            emoji alone force a split"
    (let [line (str "ab " FAMILY " cd")]
      (caps/set-grapheme-clustering! true :test)
      (is (= 8 (fmt/display-width line)))
      (is (= 1 (count (wrap-snippet line 8))) "fits on one row when clustered")

      (caps/set-grapheme-clustering! false :test)
      (is (= 14 (fmt/display-width line)))
      (is (< 1 (count (wrap-snippet line 8))) "must split when counted per codepoint"))))

(deftest truncation-cuts-on-a-unit-boundary
  (testing "the ellipsis must not land inside a joined sequence"
    (doseq [regime [false true]]
      (caps/set-grapheme-clustering! regime :test)
      (let [line (str "ab " FAMILY " cd")]
        (doseq [w (range 2 16)]
          (let [out (truncate-w line w)]
            (is (not (str/includes? out (str ZWJ "\u2026")))
                (str "truncated between a ZWJ and the ellipsis at width " w))))))))

(deftest vs16-emoji-is-never-cut-from-its-selector
  (testing "U+26A0 + U+FE0F renders as one 2-column glyph. Stepping used to
            measure the base alone (1 column) and could cut between them,
            leaving a bare variation selector -- this is the regression that
            the next-unit invariant surfaced."
    (doseq [regime [false true]]
      (caps/set-grapheme-clustering! regime :test)
      (is (= 2 (fmt/display-width WARN)))
      (is (= WARN (truncate-w WARN 2)) "fits in 2 columns, must survive whole")
      (doseq [row (wrap-snippet (str "x " WARN " y") 3)]
        (is (not (str/starts-with? row "\uFE0F"))
            "row begins with a bare variation selector")))))
