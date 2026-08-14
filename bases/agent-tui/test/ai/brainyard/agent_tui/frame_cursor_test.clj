;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.frame-cursor-test
  "Frame + cursor invariants for the fullscreen renderer.

   These assert the two properties that make the TUI flicker-free, both of
   which are structural rather than per-call-site:

   1. ONE user gesture is ONE flush. Every `raw-write-direct!` is something the
      terminal can present on its own, so a composite that flushed four times
      (`redraw-chrome!`) or twice (a page scroll: viewport, then separator) was
      showing a half-drawn screen. `with-frame` collapses them.

   2. A frame can only ever leave the cursor HIDDEN or PARKED AT THE INPUT LINE.
      Draw functions no longer emit show-cursor themselves; the frame epilogue
      is the single owner. Before that, `render-viewport!` ended on the last
      scrollback row, so a repaint parked a visible cursor mid-content.

   Plus the anti-blink rule: the hide/show pair is emitted only on a
   transition, so a whole turn of streaming (frames arriving ~18x/second)
   emits no cursor toggles at all."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.terminal :as terminal]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private ESC "\033")
(def ^:private SYNC-ON  (str ESC "[?2026h"))
(def ^:private SYNC-OFF (str ESC "[?2026l"))
(def ^:private HIDE     (str ESC "[?25l"))
(def ^:private SHOW     (str ESC "[?25h"))

;; Row/col the probe layout puts the input line at.
(def ^:private INPUT-ROW 37)
(def ^:private INPUT-COL 3)
(def ^:private PARK (str ESC "[" INPUT-ROW ";" INPUT-COL "H"))

(def ^:private !flushes (atom []))

(defn- probe-writer
  "A Writer that records each flush separately — one entry per thing the
   terminal is given the chance to present."
  []
  (let [sb (StringBuilder.)]
    (proxy [java.io.Writer] []
      (write [^String s] (.append sb s))
      (flush [] (let [s (.toString sb)]
                  (when (seq s) (swap! !flushes conj s))
                  (.setLength sb 0)))
      (close []))))

(defn- setup!
  "Install a fullscreen layout over the probe writer. `active?` is
   `:input-active` — true when the user is sitting at the prompt, false during
   a turn."
  [active?]
  (reset! !flushes [])
  (layout/set-writer! (probe-writer))
  (swap! layout/!layout assoc
         :mode :fullscreen :rows 40 :cols 100
         :scroll-bottom 35 :separator-row 36 :input-row INPUT-ROW
         :separator2-row 38 :tab-row 39 :status-row 40
         :status-text "idle" :status-left "" :tab-strip-text " main0*"
         :viewport-offset 0 :input-height 1 :input-height-max 13
         :menu-height 0 :task-activity-height 0 :agent-activity-height 0
         :input-cursor-row INPUT-ROW :input-cursor-col INPUT-COL
         :input-active active?)
  (reset! layout/!scrollback (vec (map #(str "line " %) (range 60))))
  (reset! layout/!live-blocks {})
  ;; The real precondition: the cursor is visible at the prompt the instant a
  ;; turn starts. Starting from "hidden" would hide the transition we assert.
  (layout/note-cursor-shown!))

(defn- reset-state-fixture [t]
  (let [saved-layout @layout/!layout
        saved-sb     @layout/!scrollback
        saved-blocks @layout/!live-blocks]
    (try (t)
         (finally
           (reset! layout/!layout saved-layout)
           (reset! layout/!scrollback saved-sb)
           (reset! layout/!live-blocks saved-blocks)))))

(use-fixtures :each reset-state-fixture)

(defn- occurrences [needle hay]
  (count (re-seq (re-pattern (java.util.regex.Pattern/quote needle)) hay)))

(defn- joined [] (str/join @!flushes))

;; ---------------------------------------------------------------------------
;; One gesture, one flush
;; ---------------------------------------------------------------------------

(deftest every-repaint-is-a-single-synchronized-frame
  (testing "each render path emits exactly one flush wrapped in DEC 2026"
    (doseq [[label op]
            [["render-viewport!"   #(layout/render-viewport!)]
             ["draw-separator!"    #(layout/draw-separator!)]
             ["draw-tab-strip!"    #(layout/draw-tab-strip! " main0*")]
             ["draw-status-bar!"   #(layout/draw-status-bar! "" "running")]
             ["write-output!"      #(layout/write-output! "hello")]
             ["live-block create"  #(layout/update-live-block! :b ["x" "y"])]
             ["keystroke redraw"   #(terminal/redraw-input-line! "hi" 2)]]]
      (setup! true)
      (op)
      (let [all (joined)]
        (is (= 1 (count @!flushes)) (str label " should present once"))
        (is (= 1 (occurrences SYNC-ON all)) (str label " opens one sync block"))
        (is (= 1 (occurrences SYNC-OFF all)) (str label " closes it))"))
        (is (str/starts-with? all SYNC-ON) (str label " starts the frame first"))
        (is (str/ends-with? all SYNC-OFF) (str label " ends the frame last"))))))

(deftest composite-gestures-collapse-to-one-frame
  (testing "redraw-chrome! is one frame, not four"
    ;; Was: separator, activity areas, bottom separator, tab strip and status
    ;; bar each flushing on their own — four presentations, three show-cursors.
    (setup! true)
    (layout/redraw-chrome!)
    (is (= 1 (count @!flushes)))
    (is (= 1 (occurrences SHOW (joined))) "exactly one show-cursor, at the end"))

  (testing "a page scroll is one frame, not viewport-then-separator"
    (setup! true)
    (layout/scroll-page-up!)
    (is (= 1 (count @!flushes))))

  (testing "a wheel scroll is one frame"
    (setup! true)
    (layout/scroll-lines-up! 3)
    (is (= 1 (count @!flushes))))

  (testing "a resize rebuild is one frame, not seven"
    (setup! true)
    (layout/handle-resize!)
    (is (>= 1 (count @!flushes)))))

(deftest nested-draws-join-the-open-frame
  (testing "draws called inside with-frame contribute rather than flush"
    (setup! true)
    (layout/draw-frame!
     (fn []
       (layout/draw-separator!)
       (layout/draw-tab-strip! " main0*")
       (layout/draw-status-bar! "" "x")
       (layout/render-viewport!)))
    (is (= 1 (count @!flushes)) "four draws, one presentation")
    (is (= 1 (occurrences SYNC-ON (joined))))
    (is (= 1 (occurrences SHOW (joined))))))

;; ---------------------------------------------------------------------------
;; Cursor ownership
;; ---------------------------------------------------------------------------

(deftest a-frame-leaves-the-cursor-only-at-the-input-line
  (testing "render-viewport! ends parked at the input line, not on content"
    ;; The body ends on the last scrollback row; the epilogue must move it.
    (setup! true)
    (layout/render-viewport!)
    (let [all (joined)]
      (is (str/ends-with? all (str PARK SHOW SYNC-OFF))
          "park at input, then show, then close the frame")))

  (testing "the park uses the tracked multi-row input cursor position"
    (setup! true)
    (swap! layout/!layout assoc :input-cursor-row 34 :input-cursor-col 9)
    (layout/render-viewport!)
    (is (str/includes? (joined) (str ESC "[34;9H" SHOW)))))

(deftest no-draw-function-shows-the-cursor-on-its-own
  (testing "at most one show-cursor per frame, always the epilogue's"
    (doseq [op [#(layout/draw-status-bar! "" "s")
                #(layout/draw-tab-strip! " t")
                #(layout/draw-input-prompt! "> ")
                #(layout/restore-input-cursor!)
                #(layout/update-live-block! :b ["x"])]]
      (setup! true)
      (op)
      (is (>= 1 (occurrences SHOW (joined)))))))

(deftest mid-turn-frames-never-show-the-cursor
  (testing "with input inactive the cursor is hidden and stays hidden"
    (setup! false)
    (layout/render-viewport!)
    (let [all (joined)]
      (is (zero? (occurrences SHOW all)) "no show-cursor during a turn")
      (is (= 1 (occurrences HIDE all)) "one hide, on the transition from the prompt"))))

;; ---------------------------------------------------------------------------
;; The status row is one line and stays one line
;;
;; It is the BOTTOM row, so an overflow does not wrap harmlessly onto the next
;; line — the tail lands back on the row's own start and garbles it. Narrowing
;; the terminal is all it takes.
;; ---------------------------------------------------------------------------

(defn- status-row-width
  "Visible columns the last status-bar write puts on the row."
  []
  (let [s (joined)
        ;; body after the final erase-line, minus the frame epilogue
        body (last (str/split s (re-pattern (java.util.regex.Pattern/quote (str ESC "[2K")))))]
    (fmt/display-width (str/replace (or body "")
                                    (re-pattern (str (java.util.regex.Pattern/quote ESC) "\\[[?0-9;]*[A-Za-z]"))
                                    ""))))

(deftest status-bar-never-overflows-the-row
  (testing "left + right are truncated to fit, not just the gap clamped"
    (doseq [cols [120 80 60 40 24 16]]
      (setup! true)
      (swap! layout/!layout assoc :cols cols)
      (layout/draw-status-bar!
       " BRAINYARD coact-agent [claude-code/opus]"
       "idle │ 0 calls │ 0 tokens │ $0.0000")
      (is (<= (status-row-width) cols)
          (str "status row must fit in " cols " columns"))))

  (testing "the live state on the right survives; identity on the left gives way"
    (setup! true)
    (swap! layout/!layout assoc :cols 40)
    (layout/draw-status-bar! " BRAINYARD coact-agent [claude-code/opus]" "idle │ $0.0000")
    (let [all (joined)]
      (is (str/includes? all "idle │ $0.0000") "right side kept whole")
      (is (str/includes? all "…") "left side truncated with an ellipsis")))

  (testing "a right side wider than the terminal is itself truncated"
    (setup! true)
    (swap! layout/!layout assoc :cols 20)
    (layout/draw-status-bar! "" "idle │ 0 calls │ 0 tokens │ $0.0000")
    (is (<= (status-row-width) 20))))

;; ---------------------------------------------------------------------------
;; Geometry changes must not strand the tracked cursor
;;
;; `:input-cursor-row`/`:input-cursor-col` are ABSOLUTE screen coordinates. They
;; are correct only for the geometry that computed them, so anything that
;; changes the geometry has to drop them — otherwise the frame epilogue keeps
;; parking the cursor where the input line USED to be: mid-content when the
;; terminal grew, on the chrome when it shrank.
;; ---------------------------------------------------------------------------

(deftest resize-drops-the-stale-input-cursor
  (testing "handle-resize! clears coordinates computed for the old geometry"
    (setup! true)
    ;; A two-row input, cursor on the second row — the shape most obviously
    ;; wrong after a re-wrap.
    (swap! layout/!layout assoc :input-cursor-row 38 :input-cursor-col 61)
    (with-redefs [ai.brainyard.agent.interface.tui.format/refresh-terminal-size! (fn [] nil)
                  ai.brainyard.agent.interface.tui.format/terminal-rows (fn [] 24)
                  ai.brainyard.agent.interface.tui.format/terminal-columns (fn [] 60)]
      (layout/handle-resize!))
    (let [{:keys [input-cursor-row input-cursor-col input-row]} @layout/!layout]
      (is (nil? input-cursor-row) "a row from the old geometry is not carried over")
      (is (nil? input-cursor-col) "nor a column from the old width")
      (is (= 21 input-row) "chrome recomputed for 24 rows")
      (testing "so the next frame parks on the NEW input row"
        (reset! !flushes [])
        (layout/render-viewport!)
        (is (str/includes? (joined) (str ESC "[" input-row ";3H" SHOW))
            "epilogue falls back to (input-row, prompt col), not row 38")))))

(deftest entering-fullscreen-starts-with-no-tracked-cursor
  (testing "init-fullscreen! does not inherit a previous session's coordinates"
    (setup! true)
    (swap! layout/!layout assoc :input-cursor-row 38 :input-cursor-col 61)
    (with-redefs [ai.brainyard.agent.interface.tui.format/refresh-terminal-size! (fn [] nil)
                  ai.brainyard.agent.interface.tui.format/terminal-rows (fn [] 40)
                  ai.brainyard.agent.interface.tui.format/terminal-columns (fn [] 100)]
      (layout/init-fullscreen!))
    (is (nil? (:input-cursor-row @layout/!layout)))
    (is (nil? (:input-cursor-col @layout/!layout)))))

(deftest a-scroll-gesture-repaints-the-input-line-in-the-same-frame
  (testing "scroll + input redraw nested in one frame is one flush, one show"
    ;; What the :page-up / :scroll-up key handlers do. Unframed they were two
    ;; flushes — hide/show, hide/show — per keypress, which is what a held
    ;; Page Up reads as flicker.
    (setup! true)
    (layout/draw-frame!
     (fn []
       (layout/scroll-page-up!)
       (terminal/redraw-input-line! "hi" 2)))
    (is (= 1 (count @!flushes)) "one presentation for the whole gesture")
    (let [all (joined)]
      (is (= 1 (occurrences SHOW all)) "cursor shown once, by the epilogue")
      (is (= 1 (occurrences HIDE all)) "and hidden once, at the top")
      (is (str/ends-with? all (str SHOW SYNC-OFF)))))

  (testing "scroll-to-bottom! is framed like its siblings"
    (setup! true)
    (swap! layout/!layout assoc :viewport-offset 12)
    (layout/scroll-to-bottom!)
    (is (= 1 (count @!flushes)) "viewport + separator present together")))

;; ---------------------------------------------------------------------------
;; Anti-blink: toggle only on transition
;; ---------------------------------------------------------------------------

(deftest streaming-emits-no-cursor-churn
  (testing "a turn's worth of ticks emits ONE hide and no shows"
    ;; Toggling DECTCEM per frame is itself a blink source, and during a turn
    ;; frames arrive ~18x/second (150ms think ticker + 1s block tickers +
    ;; streamed chunks). Since :input-active is false throughout, the cursor
    ;; goes hidden once and is left alone.
    (setup! false)
    (layout/update-live-block! :think ["t0" "t1" "t2"])
    (layout/update-live-block! :acp (vec (map #(str "a" %) (range 8))))
    (reset! !flushes [])
    (dotimes [i 7] (layout/update-live-block! :think [(str "t" i) "b" "c"]))
    (dotimes [i 10] (layout/update-live-block! :acp (vec (map #(str "a" i "-" %) (range 8)))))
    (let [all (joined)]
      (is (= 17 (count @!flushes)) "one frame per tick — they are real animation frames")
      (is (= 17 (occurrences SYNC-ON all)) "every one of them synchronized")
      (is (zero? (occurrences SHOW all)))
      (is (zero? (occurrences HIDE all))
          "already hidden from the first block create — no further toggles")))

  (testing "an already-hidden cursor is not re-hidden"
    (setup! false)
    (layout/note-cursor-hidden!)
    (layout/render-viewport!)
    (is (zero? (occurrences HIDE (joined))))))

(deftest out-of-band-cursor-writes-are-tracked
  (testing "note-cursor-shown! makes the next frame emit the hide it owes"
    (setup! false)
    (layout/note-cursor-hidden!)
    (layout/note-cursor-shown!)
    (layout/render-viewport!)
    (is (= 1 (occurrences HIDE (joined))))))

;; ---------------------------------------------------------------------------
;; Inline mode is untouched
;; ---------------------------------------------------------------------------

(deftest inline-mode-emits-no-frame-machinery
  (testing "inline writes stay a plain stream — no sync, no cursor control"
    (reset! !flushes [])
    (layout/set-writer! (probe-writer))
    (swap! layout/!layout assoc :mode :inline)
    (layout/write-output! "plain line")
    (let [all (joined)]
      (is (= "plain line\n" all))
      (is (zero? (occurrences SYNC-ON all)))
      (is (zero? (occurrences HIDE all))))))
