;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.tui.soft-wrap-test
  "The soft-newline contract for `format-answer-soft`.

   A hard newline inserted to make text fit is permanent and lossy: the
   terminal cannot tell it apart from one the author wrote, so a copy of the
   answer comes back with breaks in the middle of sentences. A soft wrap is
   recorded by the terminal as a wrap and rejoined on copy — verified against
   tmux, where `capture-pane -J` reconstitutes a soft-wrapped paragraph
   exactly while a pre-wrapped one stays broken forever.

   So the property under test is a negative one: this renderer must not invent
   line breaks. Every newline in its output has to be one the author typed."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.agent.tui.format :as fmt]))

(def ^:private long-para
  (str "This is a fairly long paragraph of prose that would certainly be "
       "wrapped by the renderer at any normal pane width, and that is exactly "
       "the text a user would want to select and paste somewhere else intact."))

(defn- content-lines [s]
  (remove str/blank? (str/split-lines (str s))))

(deftest soft-render-invents-no-line-breaks
  (testing "a paragraph stays ONE logical line, however long -- the terminal
            wraps it for display and rejoins it on copy"
    (let [out (fmt/format-answer-soft long-para)
          lines (content-lines out)]
      (is (= 1 (count lines))
          (str "paragraph was split into " (count lines) " lines"))
      (is (> (fmt/display-width (first lines)) 150)
          "the single line should be far wider than any real pane"))))

(deftest soft-render-is-width-independent
  (testing "unlike the wrapped renderers there is no width to pass, so output
            cannot go stale when the terminal is resized"
    (let [a (fmt/format-answer-soft long-para)]
      (is (= a (fmt/format-answer-soft long-para)) "deterministic")
      ;; The wrapped renderer, by contrast, bakes the width in:
      (is (not= (fmt/format-answer-plain long-para 40)
                (fmt/format-answer-plain long-para 100))
          "format-answer-plain DOES bake the pane width in (contrast case)"))))

(deftest soft-render-preserves-authored-newlines
  (testing "breaks the author wrote survive -- only INVENTED ones are gone"
    (let [md  (str "First paragraph here.\n\n"
                   "```clojure\n(defn f [x]\n  (inc x))\n```\n\n"
                   "Last paragraph.")
          out (fmt/format-answer-soft md)]
      (is (str/includes? out "(defn f [x]"))
      (is (str/includes? out "(inc x))")
          "a code block's own newlines are authored and must remain")
      (is (str/includes? out "First paragraph here."))
      (is (str/includes? out "Last paragraph.")))))

(deftest soft-render-carries-no-box-chrome
  (testing "a right border has to be padded to a width we chose, and choosing
            that width is exactly what soft wrapping hands to the terminal --
            so the box cannot come along"
    (let [out (fmt/format-answer-soft long-para)]
      (doseq [ch ["\u250C" "\u2510" "\u2514" "\u2518" "\u2502"]]
        (is (not (str/includes? out ch))
            (str "box-drawing char " (pr-str ch) " leaked into the soft render")))))

  (testing "the boxed renderer still HAS its chrome -- this is a contrast case,
            so the test fails if the two renderers are ever confused"
    (is (str/includes? (fmt/format-answer long-para 60) "\u250C"))))

(deftest soft-and-boxed-agree-on-the-words
  (testing "same content, different line breaking -- collapsing whitespace must
            make the two renderings identical"
    (let [norm (fn [s] (-> (str s)
                           (str/replace #"\u001b\[[0-9;]*m" "")
                           (str/replace #"[\u250C\u2510\u2514\u2518\u2502\u2500]" " ")
                           (str/replace #"\s+" " ")
                           str/trim))]
      (is (= (norm (fmt/format-answer-soft long-para))
             (norm (fmt/format-answer long-para 60)))
          "the soft renderer dropped or added words, not just line breaks"))))

(deftest soft-render-handles-empty-input
  (testing "nil / blank yields nil, matching the other answer renderers"
    (is (nil? (fmt/format-answer-soft nil)))
    (is (nil? (fmt/format-answer-soft "")))
    (is (nil? (fmt/format-answer-soft "   \n  ")))))
