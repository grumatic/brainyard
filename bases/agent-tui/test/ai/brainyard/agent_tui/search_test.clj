;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.search-test
  "Scrollback search — the pure scan/highlight layer, and the viewport
   anchoring that makes a hit survive live output."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.search :as search]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(defn- color-on-fixture [f]
  (ansi/color!)
  (f))

(use-fixtures :each color-on-fixture)

;; ============================================================================
;; Matching
;; ============================================================================

(defn- spans-of [s query]
  (search/row-spans s (search/compile-query query)))

(deftest smart-case
  (testing "a lowercase query ignores case"
    (is (not (search/case-sensitive? "error")))
    (is (= [[0 5]] (spans-of "ERROR here" "error"))))
  (testing "an uppercase character makes it case-sensitive"
    (is (search/case-sensitive? "Error"))
    (is (empty? (spans-of "error here" "Error")))
    (is (= [[0 5]] (spans-of "Error here" "Error")))))

(deftest every-occurrence-on-a-row-is-a-hit
  ;; Reporting only the first would make "next" skip matches inside a long row
  ;; while the n/m counter claimed otherwise.
  (is (= [[0 2] [4 6] [8 10]] (spans-of "ab..ab..ab" "ab")))
  (is (= 3 (count (search/scan ["ab..ab..ab"] "ab")))))

(deftest overlapping-matches-do-not-double-count
  ;; `aa` in `aaaa` is two non-overlapping hits, not three.
  (is (= [[0 2] [2 4]] (spans-of "aaaa" "aa"))))

;; ============================================================================
;; Regex queries (`/` prefix)
;; ============================================================================

(deftest slash-selects-regex-and-double-slash-escapes-it
  (is (= :literal (:kind (search/compile-query "abc"))))
  (is (= :regex   (:kind (search/compile-query "/a.c"))))
  (is (= [[3 6]] (spans-of "foo123bar" "/[0-9]+")))
  (testing "// searches for a literal leading slash"
    (is (= :literal (:kind (search/compile-query "//tmp"))))
    (is (= "/tmp" (:needle (search/compile-query "//tmp"))))
    (is (= [[0 4]] (spans-of "/tmp/x" "//tmp")))))

(deftest an-unfinished-regex-is-a-value-not-a-throw
  ;; Every prefix of a real pattern is broken on the way in, so typing one must
  ;; not be an error path.
  (doseq [q ["/a(" "/[a" "/*"]]
    (is (= :invalid (:kind (search/compile-query q))) (str "for " q))
    (is (= [] (search/scan ["anything"] q)) "an invalid pattern finds nothing")))

(deftest a-zero-width-pattern-terminates-and-records-nothing
  ;; `x*` matches the empty string at every position; recording those would
  ;; fill the hit list with nothing-spans and never advance the cursor.
  (is (= [] (search/scan ["aaa"] "/x*")))
  (is (= [] (search/scan ["aaa"] "/^")))
  (is (= [[0 3]] (spans-of "aaa" "/a*")) "a non-empty match still counts"))

(deftest regex-honours-smart-case
  (is (= 1 (count (search/scan ["Error here"] "/error"))))
  (is (= 0 (count (search/scan ["error here"] "/Error")))))

(deftest scan-matches-across-a-style-boundary
  ;; The whole reason search runs on the STRIPPED row: `config` here is split
  ;; by an SGR sequence and is present only in the plain text.
  (let [row (str "the " ansi/bold "con" ansi/reset "fig key")]
    (is (empty? (spans-of row "config"))
        "sanity: the styled string does not contain it")
    (is (= 1 (count (search/scan [row] "config")))
        "but the stripped row does")))

(deftest scan-does-not-match-inside-escape-bodies
  ;; A styled-string search would find the `1` of `ESC[1m` and the `m` that
  ;; ends every SGR.
  (let [rows [(str ansi/bold "hello" ansi/reset)]]
    (is (empty? (search/scan rows "1m")))
    (is (empty? (search/scan rows "[0")))))

(deftest scan-reports-plain-indices
  (let [row  (str ansi/bold "abc" ansi/reset "MATCH")
        hits (search/scan [row] "match")]
    (is (= 1 (count hits)))
    (is (= {:idx 0 :start 3 :end 8} (first hits)))
    (is (= "MATCH" (subs (fmt/strip-ansi row) 3 8)))))

(deftest blank-and-empty-queries-find-nothing
  (is (= [] (search/scan ["anything"] "")))
  (is (= [] (search/scan ["anything"] "   ")))
  (is (= [] (search/scan [] "x"))))

;; ============================================================================
;; Highlighting
;; ============================================================================

(defn- spans [& triples]
  (mapv (fn [[s e c]] {:start s :end e :current? c}) triples))

(deftest highlight-preserves-display-width
  ;; The contract `install-row-decorator!` states, and what makes it safe to run
  ;; on the paint path after the width clamp.
  (doseq [row ["plain text here"
               (str ansi/bold "styled" ansi/reset " text here")
               "unicode ⌕ ✓ text here"]]
    (let [out (search/highlight-row row (spans [0 5 true]))]
      (is (= (fmt/display-width row) (fmt/display-width out))
          (str "width changed for: " (pr-str row)))
      (is (= (fmt/strip-ansi row) (fmt/strip-ansi out))
          "visible characters changed"))))

(deftest highlight-reasserts-after-an-embedded-reset
  ;; A reset inside the match would otherwise silently drop the mark for the
  ;; rest of it. The row's own escapes are not ours to interpret.
  (let [row (str "aa" ansi/reset "bb")
        out (search/highlight-row row (spans [0 4 true]))
        on  (ansi/mark-on :search/current)]
    (is (str/includes? out on))
    ;; Once to open, once re-asserted after the embedded reset.
    (is (= 2 (count (re-seq (re-pattern (java.util.regex.Pattern/quote on)) out))))))

(deftest highlight-closes-the-mark
  (let [out (search/highlight-row "abcdef" (spans [1 3 true]))]
    (is (str/includes? out (ansi/mark-off :search/current))
        "an unclosed mark leaks onto every row below")))

(deftest current-and-other-hits-are-visually-distinct
  ;; Otherwise "which one am I on" is unanswerable on a row with two hits.
  (let [out (search/highlight-row "ab..ab" (spans [0 2 true] [4 6 false]))]
    (is (str/includes? out (ansi/mark-on :search/current)))
    (is (str/includes? out (ansi/mark-on :search/match)))
    (is (not= (ansi/mark-on :search/current) (ansi/mark-on :search/match)))))

(deftest adjacent-spans-do-not-merge
  ;; Close before open at a shared boundary.
  (let [out (search/highlight-row "abcd" (spans [0 2 false] [2 4 false]))
        off (ansi/mark-off :search/match)]
    (is (= 2 (count (re-seq (re-pattern (java.util.regex.Pattern/quote off)) out))))))

(deftest highlight-never-splits-an-escape
  (let [row (str "ab" ansi/bold "cd" ansi/reset "ef")
        out (search/highlight-row row (spans [0 6 true]))]
    ;; Every ESC in the output still begins a well-formed sequence: nothing was
    ;; inserted between the ESC and its terminator.
    (is (every? (fn [i] (> (long (fmt/ansi-seq-end out i)) (inc (long i))))
                (keep-indexed (fn [i c] (when (= 27 (int c)) i)) out)))))

(deftest highlight-is-a-no-op-without-spans
  (is (= "abc" (search/highlight-row "abc" [])))
  (is (= "" (search/highlight-row "" (spans [0 1 true])))))

;; ============================================================================
;; Hit selection
;; ============================================================================

(deftest fresh-query-lands-on-the-last-hit-at-or-before-the-view
  ;; Searching a scrollback is "find the thing that scrolled past", so it works
  ;; backwards from where the user is looking.
  (let [hits [{:idx 5} {:idx 20} {:idx 50}]]
    (is (= 1 (search/hit-at-or-before hits 30)))
    (is (= 2 (search/hit-at-or-before hits 99)) "at the live tail: most recent")
    (is (= 0 (search/hit-at-or-before hits 0)) "before every hit: the first")
    (is (= -1 (search/hit-at-or-before [] 10)))))

(deftest nearest-hit-re-seats-after-a-rescan
  (let [hits [{:idx 10} {:idx 40} {:idx 41}]]
    (is (= 0 (search/nearest-hit hits 12)))
    (is (= 2 (search/nearest-hit hits 45)))
    (is (= -1 (search/nearest-hit [] 5)))))

;; ============================================================================
;; Viewport anchoring
;; ============================================================================

(defn- with-scrollback
  "Install `n` rows and a fixed geometry, without entering fullscreen."
  [n]
  (reset! layout/!scrollback (mapv #(str "line " %) (range n)))
  (swap! layout/!layout assoc :scroll-bottom 30 :cols 80 :viewport-offset 0))

(defn- visible-range
  "[start end) of scrollback indices the current offset puts on screen —
   `render-viewport!`'s own arithmetic."
  []
  (let [{:keys [scroll-bottom viewport-offset]} @layout/!layout
        total (count @layout/!scrollback)
        end   (- total (long (or viewport-offset 0)))]
    [(max 0 (- end (long scroll-bottom))) end]))

(deftest seating-puts-the-hit-on-screen
  (doseq [idx [0 1 15 200 498 499]]
    (with-scrollback 500)
    (#'layout/seat-index! idx)
    (let [[start end] (visible-range)]
      (is (and (<= start idx) (< idx end))
          (str "index " idx " not visible in [" start " " end ")")))))

(deftest seating-clamps-rather-than-scrolling-past-either-end
  (with-scrollback 500)
  (#'layout/seat-index! 499)
  (is (zero? (:viewport-offset @layout/!layout))
      "a hit at the tail cannot scroll below live")
  (#'layout/seat-index! 0)
  (is (= 470 (:viewport-offset @layout/!layout))
      "a hit at the head clamps to the maximum offset"))

(deftest seating-is-a-no-op-when-everything-fits
  (with-scrollback 10)
  (#'layout/seat-index! 3)
  (is (zero? (:viewport-offset @layout/!layout))
      "nothing to scroll when the scrollback is shorter than the region"))

(deftest the-anchor-survives-appended-output
  ;; The point of anchoring on an index. `viewport-offset` counts back from the
  ;; tail, so without re-deriving it the same offset lands N rows earlier after
  ;; every emit — which is what made a search useless during a streaming turn.
  (with-scrollback 500)
  (#'layout/seat-index! 100)
  (let [[start0 end0] (visible-range)]
    ;; 40 rows of new output arrive at the tail.
    (swap! layout/!scrollback into (mapv #(str "new " %) (range 40)))
    (let [[stale-start stale-end] (visible-range)]
      (is (not (and (<= stale-start 100) (< 100 stale-end)))
          "sanity: the un-re-derived offset has walked the hit off screen"))
    (#'layout/seat-index! 100)
    (let [[start1 end1] (visible-range)]
      (is (and (<= start1 100) (< 100 end1)) "hit is still on screen")
      (is (= [start0 end0] [start1 end1])
          "and on exactly the same rows as before the output arrived"))))

;; ============================================================================
;; Keeping the hit list true as the scrollback changes
;; ============================================================================

(defn- install-search!
  "Put a search over the current scrollback directly, bypassing the fullscreen
   guard on `set-search!`."
  [query]
  (let [hits (search/scan @layout/!scrollback query)]
    (swap! layout/!layout assoc :search
           {:query query :hits hits :cur 0
            :cur-idx (:idx (first hits)) :typing? true})
    hits))

(defn- hit-rows []
  (mapv #(get @layout/!scrollback (:idx %))
        (:hits (:search @layout/!layout))))

(deftest shift-moves-hits-after-a-splice-and-drops-those-inside-it
  (reset! layout/!scrollback (into ["target a"] (mapv #(str "row " %) (range 10))))
  (swap! layout/!layout assoc :scroll-bottom 30)
  (reset! layout/!scrollback (assoc @layout/!scrollback 5 "target b"))
  (install-search! "target")
  (is (= [0 5] (mapv :idx (:hits (:search @layout/!layout)))))
  ;; Replace 1 row at index 2 with 4 rows: everything after 2 moves by +3.
  (layout/shift-search! 2 1 3)
  (is (= [0 8] (mapv :idx (:hits (:search @layout/!layout))))
      "the hit before the splice is untouched, the one after moves by delta")
  (testing "a hit inside the replaced range is dropped, not moved"
    (layout/shift-search! 8 1 0)
    (is (= [0] (mapv :idx (:hits (:search @layout/!layout)))))))

(deftest shift-follows-the-current-hit-rather-than-its-ordinal
  (reset! layout/!scrollback (vec (concat ["x target" "a" "b"] ["y target"] ["c"])))
  (swap! layout/!layout assoc :scroll-bottom 30)
  (install-search! "target")
  ;; Park on the SECOND hit, then drop the first by replacing it.
  (swap! layout/!layout update :search assoc :cur 1 :cur-idx 3)
  (layout/shift-search! 0 1 0)
  (let [s (:search @layout/!layout)]
    (is (= 1 (count (:hits s))) "the first hit was inside the replaced range")
    (is (= 0 (:cur s)) "the current hit kept its identity at its new ordinal")
    (is (= 3 (:cur-idx s)) "and still points at the same row")))

(deftest shift-does-not-re-anchor-a-search-that-had-let-go
  ;; `end-search-typing!` drops `:cur-idx` on purpose; restoring it here would
  ;; silently re-enable the auto-snap suppression after the user left the bar.
  (reset! layout/!scrollback ["target" "a" "b"])
  (swap! layout/!layout assoc :scroll-bottom 30)
  (install-search! "target")
  (swap! layout/!layout update :search assoc :cur-idx nil)
  (layout/shift-search! 1 1 2)
  (is (nil? (:cur-idx (:search @layout/!layout)))))

(deftest rescan-by-ordinal-survives-a-rewrap
  ;; A resize changes where lines break, never what text exists — so the Nth
  ;; match is still the Nth, even though every index moved.
  (reset! layout/!scrollback ["hit one" "pad" "hit two" "pad" "hit three"])
  (swap! layout/!layout assoc :scroll-bottom 30)
  (install-search! "hit")
  (swap! layout/!layout update :search assoc :cur 2 :cur-idx 4)
  ;; Same three matches, re-wrapped so each is preceded by an extra row.
  (reset! layout/!scrollback
          ["a" "hit one" "pad" "b" "hit two" "pad" "c" "hit three"])
  (layout/rescan-search! :ordinal)
  (let [s (:search @layout/!layout)]
    (is (= 3 (count (:hits s))))
    (is (= 2 (:cur s)) "still the third match")
    (is (= 7 (:cur-idx s)) "re-seated onto its new row")
    (is (= "hit three" (get @layout/!scrollback (:cur-idx s))))))

(deftest rescan-by-ordinal-clamps-when-matches-disappear
  (reset! layout/!scrollback ["hit a" "hit b" "hit c"])
  (swap! layout/!layout assoc :scroll-bottom 30)
  (install-search! "hit")
  (swap! layout/!layout update :search assoc :cur 2 :cur-idx 2)
  (reset! layout/!scrollback ["hit a"])
  (layout/rescan-search! :ordinal)
  (is (= 0 (:cur (:search @layout/!layout)))))

(deftest rescan-picks-up-text-a-splice-revealed
  ;; The expand/collapse case: rows the user just uncollapsed are exactly the
  ;; text they could not search a moment ago.
  (reset! layout/!scrollback ["target one" "[collapsed]" "tail"])
  (swap! layout/!layout assoc :scroll-bottom 30)
  (install-search! "target")
  (is (= 1 (count (:hits (:search @layout/!layout)))))
  ;; Expand: the marker row becomes three, one of which matches.
  (reset! layout/!scrollback
          ["target one" "expanded a" "target two" "expanded c" "tail"])
  (layout/resync-search-after-splice! 1 1 2)
  (is (= 2 (count (:hits (:search @layout/!layout))))
      "the revealed match joined the hit list")
  (is (= ["target one" "target two"] (hit-rows))))

(deftest an-invalid-pattern-is-recorded-on-rescan
  (reset! layout/!scrollback ["anything"])
  (swap! layout/!layout assoc :scroll-bottom 30)
  (swap! layout/!layout assoc :search
         {:query "/a(" :hits [] :cur -1 :cur-idx nil :typing? true})
  (layout/rescan-search! :ordinal)
  (is (true? (:invalid? (:search @layout/!layout)))
      "so the bar can say 'bad pattern' rather than 'no matches'"))
