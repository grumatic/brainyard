;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.autocomplete-test
  "Unit tests for the autocomplete primitives — prefix-first sort and the
   scroll-state indicator rendered on the menu's reserved last row."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent.interface.tui.format :as fmt]
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
    ;; /pause contains 'se' as a substring (pau-SE) but doesn't start
    ;; with it; the filter should still surface it.
    (let [matches (ac/filter-commands "/se")
          names   (set (map first matches))]
      (is (contains? names "/pause")))))

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
  (testing "for /se: /session is a prefix match (bucket 0) and /pause is
            substring-only (bucket 1, via 'se' in 'pau-SE'). /session must sort
            first. Uses command-registry-only entries so the test does not depend
            on !tool-defs being populated."
    (let [matches (ac/filter-commands "/se")
          names   (mapv first matches)
          idx     (fn [n] (.indexOf ^java.util.List names n))
          i-sess  (idx "/session")
          i-paus  (idx "/pause")]
      (is (>= i-sess 0)
          "/session must appear (prefix match)")
      (is (>= i-paus 0)
          "/pause must appear (substring match on name)")
      (is (< i-sess i-paus)
          "/session (prefix) must sort before /pause (substring only)"))))

;; ---------------------------------------------------------------------------
;; Menu-description truncation
;;
;; The menu's private truncator checked the fit with `display-width` but did the
;; cut with `subs`, so it split whatever the char index happened to land in —
;; measured on the old code, 40 ZWJ family emoji cut to 12 columns ended on a
;; LONE HIGH SURROGATE. Its fallback was worse: when no indicator could fit it
;; cut to max-w CHARS against a COLUMN budget, so 200 CJK glyphs at max-w 12
;; rendered 24 columns — twice the budget it existed to enforce, on the row it
;; was called to protect.
;; ---------------------------------------------------------------------------

(def ^:private truncate-to-width #'ac/truncate-to-width)

(defn- ends-mid-surrogate? [^String s]
  (and (pos? (count s))
       (Character/isHighSurrogate (.charAt s (dec (count s))))))

(deftest truncation-never-exceeds-the-budget
  (testing "across scripts and down to degenerate budgets"
    (doseq [[label s] [["ascii" (apply str (repeat 200 "a"))]
                       ["cjk"   (apply str (repeat 200 "漢"))]
                       ["emoji" (apply str (repeat 40 "👨‍👩‍👦"))]
                       ["mixed" (str "list the files " (apply str (repeat 40 "漢")) " now")]]
            w [80 40 20 12 6 3 1]]
      (let [r (truncate-to-width s w)]
        (is (<= (fmt/display-width r) w)
            (str label " overflows at max-w=" w ": " (fmt/display-width r)))))))

(deftest truncation-cuts-on-a-unit-boundary
  (testing "no lone surrogate is ever left at the cut"
    (let [s (apply str (repeat 40 "👨‍👩‍👦"))]
      (doseq [w [40 20 12 10 5 3 1]]
        (is (not (ends-mid-surrogate? (truncate-to-width s w)))
            (str "lone high surrogate at max-w=" w))))))

(deftest truncation-keeps-the-indicator-when-it-fits
  (testing "a long description reports how much was hidden"
    (let [r (truncate-to-width (apply str (repeat 200 "a")) 20)]
      (is (re-find #"\[\+\d+ chars\]" r))
      (is (<= (fmt/display-width r) 20))))
  (testing "and drops it rather than overflowing when it cannot fit"
    (let [r (truncate-to-width (apply str (repeat 200 "a")) 6)]
      (is (not (re-find #"\[\+" r)))
      (is (<= (fmt/display-width r) 6)))))

(deftest truncation-passes-through-what-already-fits
  (is (= "short" (truncate-to-width "short" 40)))
  (is (= "" (truncate-to-width "" 40)))
  (testing "a non-positive budget is not an excuse to emit something"
    (is (string? (truncate-to-width "anything" 0)))
    (is (string? (truncate-to-width "anything" -5)))))
