;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.viewport-hold-test
  "A non-zero `:viewport-offset` holds the viewport against live output.

   `:viewport-offset` counts ROWS BACK FROM THE TAIL, so anything that changes
   the row total moves what it points at. That produced two distinct failures,
   and the rule that fixes both is one sentence: a non-zero offset is the user
   having taken the viewport deliberately — scroll mode, search mode, a search
   whose bar was left but whose highlights remain — so live output holds the
   TEXT still and moves the NUMBER.

   - `write-output!` used to SNAP the offset to 0 on every emit, which yanked a
     scrolled-up reader back to live on the next streamed chunk.
   - Every other mutation site left the offset alone, which silently DRIFTED the
     view: a live block growing by a line per tick scrolled the reader down a
     line per tick.

   What replaces the news the viewport no longer carries is the separator, so
   the label is under test too."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(def ^:private region-rows
  "Scroll-region height these tests reason in. Every expected visible range is
   derived from it rather than written out, so the numbers stay honest."
  30)

(def ^:private !sink
  "Where the fake terminal's bytes land, so the separator label can be read."
  (atom nil))

(defn- fake-fullscreen!
  "Fullscreen layout over a writer that goes nowhere in particular."
  [n-rows]
  (let [sw (java.io.StringWriter.)]
    (reset! !sink sw)
    (reset! layout/!scrollback (mapv #(str "line " %) (range n-rows)))
    (reset! layout/!live-blocks {})
    (reset! layout/!scrollback-src [])
    (layout/invalidate-painted!)
    (reset! layout/!layout
            {:mode :fullscreen :rows 40 :cols 100
             :scroll-bottom region-rows :separator-row 36 :input-row 37
             :separator2-row 38 :tab-row 39 :status-row 40
             :viewport-offset 0 :input-height 1 :menu-height 0
             :task-activity-height 0 :agent-activity-height 0
             :writer (java.io.PrintWriter. sw)})))

(defn- sink-text
  "Everything written to the fake terminal since the last `clear-sink!`, with
   ANSI stripped so a label can be matched as text."
  []
  (fmt/strip-ansi (str @!sink)))

(defn- clear-sink! []
  (.getBuffer ^java.io.StringWriter @!sink)
  (reset! !sink (java.io.StringWriter.))
  (swap! layout/!layout assoc :writer (java.io.PrintWriter. ^java.io.StringWriter @!sink)))

(defn- visible
  "The scrollback rows currently on screen — `render-viewport!`'s own
   arithmetic, which is what 'the view did not move' has to be measured in."
  []
  (let [{:keys [scroll-bottom viewport-offset]} @layout/!layout
        total (count @layout/!scrollback)
        end   (- total (long (or viewport-offset 0)))
        start (max 0 (- end (long scroll-bottom)))]
    (subvec @layout/!scrollback (max 0 start) (max 0 end))))

(defn- park!
  "Scroll up `n` rows, the way a user does."
  [n]
  (layout/scroll-lines-up! n))

(defn- reset-layout-fixture [t]
  (let [saved @layout/!layout]
    (try (t)
         (finally
           (reset! layout/!layout saved)
           (reset! layout/!scrollback [])
           (reset! layout/!live-blocks {})
           (reset! layout/!scrollback-src [])
           (layout/invalidate-painted!)))))

(use-fixtures :each reset-layout-fixture)

;; ---------------------------------------------------------------------------
;; write-output! — the snap that made scrollback useless during a turn
;; ---------------------------------------------------------------------------

(deftest emitting-while-parked-does-not-move-the-view
  (fake-fullscreen! 500)
  (park! 50)
  (let [before (visible)
        off0   (:viewport-offset @layout/!layout)]
    (is (= 50 off0) "sanity: parked 50 rows back")
    (dotimes [i 12]
      (layout/write-output! (str "streamed chunk " i)))
    (is (= before (visible))
        "12 emits must leave the visible text exactly where it was")
    (is (= 62 (:viewport-offset @layout/!layout))
        "the offset absorbs the appended rows instead of snapping to 0")))

(deftest a-multi-line-emit-is-held-whole
  (fake-fullscreen! 500)
  (park! 40)
  (let [before (visible)]
    (layout/write-output! (str/join "\n" (map #(str "para " %) (range 7))))
    (is (= before (visible)))
    (is (= 47 (:viewport-offset @layout/!layout)))))

(deftest at-the-live-tail-output-still-follows
  ;; The half that must NOT change. Offset 0 is the reader following live
  ;; output, and holding their top row fixed there would walk them off the
  ;; bottom — the regression this whole change is one clamp away from.
  (fake-fullscreen! 500)
  (is (zero? (:viewport-offset @layout/!layout)))
  (layout/write-output! "fresh")
  (is (zero? (:viewport-offset @layout/!layout)) "still live")
  (is (= "fresh" (last (visible))) "and showing the newest row"))

(deftest returning-to-live-resumes-following
  (fake-fullscreen! 500)
  (park! 50)
  (layout/write-output! "while parked")
  (layout/scroll-to-bottom!)
  (is (zero? (:viewport-offset @layout/!layout)))
  (layout/write-output! "after returning")
  (is (= "after returning" (last (visible)))
      "back at the tail, emits follow again"))

;; ---------------------------------------------------------------------------
;; Live blocks — the silent drift, one line per tick
;; ---------------------------------------------------------------------------

(deftest a-growing-live-block-does-not-drift-the-view
  (fake-fullscreen! 500)
  (park! 60)
  (let [before (visible)]
    (layout/update-live-block! :iter ["iter 1"])
    (is (= before (visible)) "a block appearing must not move the view")
    ;; The tick that mattered: the block grows a line at a time, and each one
    ;; used to scroll the reader down by one.
    (doseq [n (range 2 15)]
      (layout/update-live-block! :iter (mapv #(str "iter " %) (range 1 (inc n)))))
    (is (= before (visible)) "13 growth ticks must not move it either")))

(deftest a-same-count-tick-moves-nothing
  (fake-fullscreen! 500)
  (park! 60)
  (layout/update-live-block! :spin ["⠋ thinking"])
  (let [before (visible)
        off    (:viewport-offset @layout/!layout)]
    (doseq [f ["⠙" "⠹" "⠸" "⠼"]]
      (layout/update-live-block! :spin [(str f " thinking")]))
    (is (= before (visible)))
    (is (= off (:viewport-offset @layout/!layout))
        "no row count changed, so the offset must not either")))

(deftest a-shrinking-block-does-not-drift-the-view
  (fake-fullscreen! 500)
  (park! 60)
  (layout/update-live-block! :task (mapv #(str "task row " %) (range 10)))
  (let [before (visible)]
    (layout/update-live-block! :task (mapv #(str "task row " %) (range 3)))
    (is (= before (visible)) "a block collapsing to 3 rows must not move it")))

(deftest disposing-a-block-does-not-drift-the-view
  (fake-fullscreen! 500)
  (park! 60)
  (layout/update-live-block! :task (mapv #(str "task row " %) (range 8)))
  (let [before (visible)]
    (layout/dispose-live-block! :task)
    (is (= before (visible)) "removing 8 rows below the view must not move it")))

(deftest interleaved-emits-and-block-ticks-stay-put
  ;; What a real turn looks like: a block ticking while answers stream past it.
  (fake-fullscreen! 500)
  (park! 45)
  (let [before (visible)]
    (dotimes [i 10]
      (layout/update-live-block! :iter (mapv #(str "iter " %) (range (inc i))))
      (layout/write-output! (str "output " i)))
    (is (= before (visible)))))

;; ---------------------------------------------------------------------------
;; The status line — where the news goes instead
;; ---------------------------------------------------------------------------

(deftest the-separator-reports-the-growing-total-and-what-is-new
  (fake-fullscreen! 500)
  (park! 50)
  (clear-sink!)
  (dotimes [i 10] (layout/write-output! (str "chunk " i)))
  (let [out (sink-text)]
    (is (str/includes? out "of 510")
        "the total must track output that arrived while parked")
    (is (str/includes? out "↓ 10 new")
        "and say how much of it is new since the reader stopped following")))

(deftest the-new-count-is-not-the-viewport-offset
  ;; They are different numbers and conflating them is the tempting shortcut:
  ;; the offset also counts rows the user scrolled PAST on purpose, which are
  ;; not news.
  (fake-fullscreen! 500)
  (park! 200)
  (layout/write-output! "one new row")
  (clear-sink!)
  (layout/draw-separator!)
  (let [out (sink-text)]
    (is (str/includes? out "↓ 1 new"))
    (is (not (str/includes? out "↓ 201 new")))))

(deftest a-parked-reader-with-nothing-new-is-told-nothing-is-new
  (fake-fullscreen! 500)
  (park! 50)
  (clear-sink!)
  (layout/draw-separator!)
  (let [out (sink-text)]
    (is (str/includes? out "of 500"))
    (is (not (str/includes? out "new"))
        "no output has arrived, so there is no news to report")))

(deftest returning-to-live-restarts-the-new-count
  (fake-fullscreen! 500)
  (park! 50)
  (layout/write-output! "a")
  (layout/write-output! "b")
  (layout/scroll-to-bottom!)
  (park! 20)
  (layout/write-output! "c")
  (clear-sink!)
  (layout/draw-separator!)
  (is (str/includes? (sink-text) "↓ 1 new")
      "scrolling back to live and away again starts the tally over"))

(deftest the-label-never-outgrows-the-terminal
  ;; It is no longer pure ASCII and it embeds counts of unbounded width; a label
  ;; wider than the pane wraps onto the input row.
  (doseq [cols [24 40 100]]
    (fake-fullscreen! 500)
    (swap! layout/!layout assoc :cols cols)
    (park! 50)
    (layout/write-output! "x")
    (clear-sink!)
    (layout/draw-separator!)
    (doseq [line (remove str/blank? (str/split-lines (sink-text)))]
      (is (<= (fmt/display-width line) cols)
          (str "separator overflowed a " cols "-column pane: " (pr-str line))))))

;; ---------------------------------------------------------------------------
;; Display-block expand / collapse — a splice the user asked for
;; ---------------------------------------------------------------------------

(defn- splice!
  "The expand/collapse primitive, at the layout level."
  [start delete-count new-rows]
  (layout/splice-rows! start delete-count new-rows))

(deftest expanding-a-block-holds-the-top-row
  ;; Expanding grows rows DOWNWARD from the marker. Without the hold the whole
  ;; screen slides up by however many lines the block turned out to be, which
  ;; scrolls away the context the reader expanded it in.
  (fake-fullscreen! 500)
  (park! 50)
  (let [top-idx (- 500 50 region-rows)
        above   (subvec @layout/!scrollback top-idx (+ top-idx 5))]
    ;; Splice 24 rows over one marker line, below the top of the view.
    (splice! (+ top-idx 10) 1 (mapv #(str "body " %) (range 24)))
    (is (= above (subvec (visible) 0 5))
        "the rows above the marker must not move")
    (is (= 73 (:viewport-offset @layout/!layout))
        "the offset absorbs the revealed rows")))

(deftest collapsing-a-block-holds-the-top-row
  (fake-fullscreen! 500)
  (park! 50)
  (let [top-idx (- 500 50 region-rows)]
    (splice! (+ top-idx 10) 1 (mapv #(str "body " %) (range 24)))
    (let [above (subvec (visible) 0 5)]
      (splice! (+ top-idx 10) 24 ["marker"])
      (is (= above (subvec (visible) 0 5))
          "folding back must not move the rows above it either"))))

(deftest revealed-rows-are-not-reported-as-new-output
  ;; The defect this test found on a live terminal: `↓ 24 new` for lines the
  ;; reader had just revealed themselves — and some of them on screen, not
  ;; below. An expand is not output arriving.
  (fake-fullscreen! 500)
  (park! 50)
  (splice! (- 500 50 (dec region-rows)) 1 (mapv #(str "body " %) (range 24)))
  (clear-sink!)
  (layout/draw-separator!)
  (is (not (str/includes? (sink-text) "new"))
      "expanding a block announces no news"))

(deftest expanding-does-not-inflate-a-tally-already-running
  ;; The other half: once real output HAS arrived, a later expand must not be
  ;; added to its count. The mark shifts with the splice instead.
  (fake-fullscreen! 500)
  (park! 50)
  (dotimes [i 6] (layout/write-output! (str "real output " i)))
  (splice! (- 500 50 (dec region-rows)) 1 (mapv #(str "body " %) (range 24)))
  (clear-sink!)
  (layout/draw-separator!)
  (let [out (sink-text)]
    (is (str/includes? out "↓ 6 new")
        "only the 6 emitted rows are news")
    (is (not (str/includes? out "↓ 30 new")))))

(deftest collapsing-does-not-deflate-a-tally-already-running
  (fake-fullscreen! 500)
  (park! 50)
  (dotimes [i 6] (layout/write-output! (str "real output " i)))
  (let [at (- 500 50 (dec region-rows))]
    (splice! at 1 (mapv #(str "body " %) (range 24)))
    (splice! at 24 ["marker"]))
  (clear-sink!)
  (layout/draw-separator!)
  (is (str/includes? (sink-text) "↓ 6 new")
      "an expand/collapse round trip leaves the tally exactly where it was"))

(deftest the-new-tally-never-exceeds-what-is-below-the-view
  ;; `↓` claims the rows are down there. Counting any that have already
  ;; scrolled into view is a promise the screen contradicts.
  (fake-fullscreen! 500)
  (park! 3)
  (dotimes [i 20] (layout/write-output! (str "chunk " i)))
  (clear-sink!)
  (layout/draw-separator!)
  (let [out   (sink-text)
        below (:viewport-offset @layout/!layout)
        n     (some-> (re-find #"↓ (\d+) new" out) second parse-long)]
    (is (some? n) "a tally is shown")
    (is (<= n below)
        (str "claimed " n " new below, but only " below " rows are below the view"))))

;; ---------------------------------------------------------------------------
;; Search mode — the same rule, plus an anchor that may reclaim the viewport
;; ---------------------------------------------------------------------------

(deftest a-kept-search-holds-the-view-like-any-other-scroll
  ;; `end-search-typing!` drops the search ANCHOR on purpose (the user has gone
  ;; back to the input line). What used to happen next is that the offset had
  ;; nothing holding it and the next emit snapped to live, so the match the user
  ;; had just found scrolled away while they typed about it. The offset itself
  ;; is now what holds.
  (fake-fullscreen! 500)
  (park! 50)
  (layout/set-search! "line 42")
  (layout/end-search-typing!)
  (let [before (visible)]
    (dotimes [i 5] (layout/write-output! (str "streamed " i)))
    (is (= before (visible))
        "highlights kept, bar left, still parked — output must not move it")))

(deftest a-search-anchor-does-not-hijack-a-viewport-at-the-live-tail
  ;; The rule read in the other direction, and it is not hypothetical: an
  ;; anchor left behind by a search the reader has since scrolled away from
  ;; used to drag the viewport backwards on the next live-block create. The
  ;; blocks then sat off screen and their ticks painted nothing, so a whole
  ;; streaming turn rendered as a frozen display.
  (fake-fullscreen! 500)
  (park! 200)
  (layout/set-search! "line 7")
  (is (some? (get-in @layout/!layout [:search :cur-idx])) "sanity: anchored")
  (layout/scroll-lines-down! 1000)
  (is (zero? (:viewport-offset @layout/!layout)) "back at the live tail")
  (layout/update-live-block! :think ["thinking"])
  (layout/write-output! "an answer")
  (is (zero? (:viewport-offset @layout/!layout))
      "an old anchor must not pull a following reader back to it")
  (is (some #(= "an answer" %) (visible))
      "and the live output must be on screen"))

(deftest an-anchored-search-keeps-its-hit-on-screen
  (fake-fullscreen! 500)
  (park! 50)
  (layout/set-search! "line 4")
  (let [anchored (get-in @layout/!layout [:search :cur-idx])]
    (is (some? anchored) "sanity: the search parked on a hit")
    (dotimes [i 20] (layout/write-output! (str "streamed " i)))
    (let [{:keys [scroll-bottom viewport-offset]} @layout/!layout
          total (count @layout/!scrollback)
          end   (- total (long viewport-offset))
          start (max 0 (- end (long scroll-bottom)))]
      (is (and (<= start (long anchored)) (< (long anchored) end))
          "the hit the search is anchored on must still be visible"))))
