;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui.autocomplete-test
  "Unit tests for the autocomplete primitives — prefix-first sort and the
   scroll-state indicator rendered on the menu's reserved last row."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent-tui.autocomplete :as ac]))

(defn- strip-ansi
  "Drop CSI escape sequences so tests can assert the visible text."
  [s]
  (str/replace s #"\[[0-9;?]*[A-Za-z]" ""))

;; ============================================================================
;; prefix-first-sort-key
;; ============================================================================

(deftest prefix-first-sort-key-buckets-name-prefix-matches-first
  (testing "name prefix → bucket 0, non-prefix → bucket 1"
    (is (= 0 (first (ac/prefix-first-sort-key "/help" "he"))))
    (is (= 1 (first (ac/prefix-first-sort-key "/clear" "he"))))
    (is (= 0 (first (ac/prefix-first-sort-key ":bash" "ba"))))
    (is (= 1 (first (ac/prefix-first-sort-key ":read" "ba")))))
  (testing "leading / or : is stripped before comparison so the query body
            (which has already had its prefix-char removed) aligns"
    (is (= 0 (first (ac/prefix-first-sort-key "/help" "help"))))
    (is (= 0 (first (ac/prefix-first-sort-key ":bash" "bash")))))
  (testing "case-insensitive on both sides"
    (is (= 0 (first (ac/prefix-first-sort-key "/Help" "he"))))
    (is (= 0 (first (ac/prefix-first-sort-key "/HELP" "he")))))
  (testing "blank query body → everything is a prefix match"
    (is (= 0 (first (ac/prefix-first-sort-key "/help" ""))))
    (is (= 0 (first (ac/prefix-first-sort-key "/clear" "")))))
  (testing "alphabetical tiebreak"
    (let [k1 (ac/prefix-first-sort-key "/clear" "c")
          k2 (ac/prefix-first-sort-key "/compact" "c")]
      (is (= 0 (first k1)))
      (is (= 0 (first k2)))
      ;; lexicographic compare on the second element: "/clear" < "/compact"
      (is (neg? (compare k1 k2))))))

(deftest prefix-first-sort-key-orders-a-mixed-set
  (testing "sort-by gives prefix matches first, alphabetical within each bucket"
    (let [cmds   ["/zap" "/clear" "/coact-help" "/compact" "/agent"]
          ;; Query "co" — name-prefix matches: "/coact-help" "/compact".
          ;; Non-prefix matches: alphabetical by cmd name.
          sorted (sort-by #(ac/prefix-first-sort-key % "co") cmds)]
      (is (= ["/coact-help" "/compact" "/agent" "/clear" "/zap"] sorted)))))

;; ============================================================================
;; format-scroll-indicator
;; ============================================================================

(deftest format-scroll-indicator-blank-when-nothing-hidden
  (is (= "" (ac/format-scroll-indicator 0 0 80)))
  (is (= "" (ac/format-scroll-indicator 0 0 1))))

(deftest format-scroll-indicator-shows-only-the-active-direction
  (testing "items hidden above only"
    (let [out (strip-ansi (ac/format-scroll-indicator 3 0 80))]
      (is (str/ends-with? out "↑ 3 more"))
      (is (not (str/includes? out "↓")))))
  (testing "items hidden below only"
    (let [out (strip-ansi (ac/format-scroll-indicator 0 12 80))]
      (is (str/ends-with? out "↓ 12 more"))
      (is (not (str/includes? out "↑"))))))

(deftest format-scroll-indicator-shows-both-when-both-hidden
  (let [out (strip-ansi (ac/format-scroll-indicator 3 12 80))]
    (is (str/ends-with? out "↑ 3 · ↓ 12 more"))))

(deftest format-scroll-indicator-right-aligns-to-width
  (testing "padding fills the row to `width` so the indicator hugs the right edge"
    (let [out  (strip-ansi (ac/format-scroll-indicator 0 12 40))
          text "↓ 12 more"]
      (is (= 40 (count out)))
      (is (str/ends-with? out text))
      ;; Everything before the indicator is whitespace padding.
      (is (every? #(= \space %)
                  (subs out 0 (- (count out) (count text))))))))

(deftest format-scroll-indicator-handles-tiny-width
  (testing "when `width` is shorter than the indicator text, no padding is added
            (negative pad is clamped to zero) and the indicator still renders"
    (let [out (strip-ansi (ac/format-scroll-indicator 0 12 3))]
      (is (str/ends-with? out "↓ 12 more")))))

;; ============================================================================
;; filter-commands — description text is intentionally NOT searched
;; ============================================================================

(deftest filter-commands-matches-on-cmd-name-substring
  (testing "typing a substring of the command name matches"
    (let [matches (ac/filter-commands "/he")
          names   (set (map first matches))]
      (is (contains? names "/help"))))
  (testing "typing a non-prefix substring still matches (bucket 1)"
    ;; /verbose contains 'se' as a substring (verbo-SE) but doesn't start
    ;; with it; the filter should still surface it.
    (let [matches (ac/filter-commands "/se")
          names   (set (map first matches))]
      (is (contains? names "/verbose")))))

(deftest filter-commands-does-not-match-description-text
  (testing "regression: /clear stayed in the menu for /he because its description
            contained 'history'. filter-commands now matches name only — see the
            docstring."
    (let [matches (ac/filter-commands "/he")
          names   (set (map first matches))]
      (is (not (contains? names "/clear"))
          "/clear must NOT match /he — 'he' is only in its description")
      (is (not (contains? names "/quit"))
          "/quit must NOT match /he — neither name nor description contains 'he'"))))

(deftest filter-commands-prefix-matches-sort-before-substring-matches
  (testing "for /se: /session is a prefix match (bucket 0) and /verbose is
            substring-only (bucket 1, via 'se' in 'verbo-SE'). /session must sort
            first. Uses command-registry-only entries so the test does not depend
            on !tool-defs being populated."
    (let [matches (ac/filter-commands "/se")
          names   (mapv first matches)
          idx     (fn [n] (.indexOf ^java.util.List names n))
          i-sess  (idx "/session")
          i-verb  (idx "/verbose")]
      (is (>= i-sess 0)
          "/session must appear (prefix match)")
      (is (>= i-verb 0)
          "/verbose must appear (substring match on name)")
      (is (< i-sess i-verb)
          "/session (prefix) must sort before /verbose (substring only)"))))
