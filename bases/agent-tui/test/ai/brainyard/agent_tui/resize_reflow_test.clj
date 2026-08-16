;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.resize-reflow-test
  "Tests for re-wrapping the scrollback when the terminal is resized.

   !scrollback holds RENDERED ROWS, hard-wrapped at the width they were
   formatted for. Before `reflow-scrollback!`, `handle-resize!` updated the
   geometry and then replayed those same rows into a terminal that was no
   longer that wide: narrower, each row overflowed and the next row's
   `erase-line` wiped the spill, so the tail of every line silently vanished;
   wider, text stayed broken at the old column.

   Two mechanisms are under test, and they are independent on purpose:

   - Entries carrying a `:render` fn are re-rendered at the new width, so the
     content actually reflows and the live-block row indices are recomputed.
   - Whatever is still too wide — an entry with no renderer — is clipped at
     paint time, so it can never wrap onto the row below or scroll the
     DECSTBM region."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.permissions :as perms]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(def ^:private long-text
  (str "Here is a deliberately long paragraph that will certainly need to be "
       "wrapped by the renderer because it runs well past any sane terminal "
       "width and keeps going for quite a while longer still."))

(defn- fake-fullscreen!
  "Put the layout into fullscreen at `cols` with a writer that goes nowhere."
  [cols]
  (reset! layout/!scrollback [])
  (reset! layout/!live-blocks {})
  (reset! layout/!scrollback-src [])
  (reset! layout/!layout
          {:mode :fullscreen :rows 40 :cols cols
           :scroll-bottom 35 :separator-row 36 :input-row 37
           :separator2-row 38 :tab-row 39 :status-row 40
           :viewport-offset 0 :input-height 1 :menu-height 0
           :task-activity-height 0 :agent-activity-height 0
           :writer (java.io.PrintWriter. (java.io.StringWriter.))}))

(defn- reset-layout-fixture [t]
  (let [saved @layout/!layout]
    (try (t)
         (finally
           (reset! layout/!scrollback [])
           (reset! layout/!live-blocks {})
           (reset! layout/!scrollback-src [])
           (reset! layout/!layout saved)))))

(use-fixtures :each reset-layout-fixture)

(defn- resize!
  "Drive `handle-resize!` at `cols` without shelling out to `stty`."
  [cols]
  (with-redefs [fmt/refresh-terminal-size! (fn [] {:rows 40 :cols cols})
                fmt/terminal-rows          (fn [] 40)
                fmt/terminal-columns       (fn [] cols)]
    (layout/handle-resize!)))

(defn- widths [] (map fmt/display-width @layout/!scrollback))

(defn- rows-wider-than [n] (count (filter #(> % n) (widths))))

(defn- src-row-total []
  (reduce + 0 (map :n @layout/!scrollback-src)))

;; ---------------------------------------------------------------------------
;; Reflow
;; ---------------------------------------------------------------------------

(deftest reflows-a-renderable-emit-on-shrink
  (testing "an emit that carries :render re-wraps to the narrower width"
    (fake-fullscreen! 100)
    (layout/write-output! (fmt/format-answer long-text)
                          {:render #(fmt/format-answer long-text %)})
    (is (pos? (rows-wider-than 60))
        "precondition: formatted at 100, the rows do not fit in 60")
    (resize! 60)
    (is (zero? (rows-wider-than 60))
        "after the resize every row fits the new width")
    (is (str/includes? (str/join " " @layout/!scrollback) "deliberately")
        "the text is re-wrapped, not truncated away")))

(deftest reflows-on-grow-too
  (testing "widening re-wraps rather than leaving text broken at the old column"
    (fake-fullscreen! 50)
    (layout/write-output! (fmt/format-answer long-text)
                          {:render #(fmt/format-answer long-text %)})
    (let [narrow-rows (count @layout/!scrollback)]
      (resize! 120)
      (is (< (count @layout/!scrollback) narrow-rows)
          "the same text occupies fewer rows once the terminal is wider")
      (is (zero? (rows-wider-than 120))))))

(deftest emit-without-render-is-left-alone
  (testing "a pre-formatted emit keeps its rows — reflow is opt-in"
    (fake-fullscreen! 100)
    (layout/write-output! (fmt/format-answer long-text))
    (let [before (count @layout/!scrollback)]
      (resize! 60)
      (is (= before (count @layout/!scrollback))
          "no renderer means nothing to re-render")
      (is (pos? (rows-wider-than 60))
          "the stored rows are still the old width — the paint-time clamp is
           what keeps that safe, covered separately below"))))

;; ---------------------------------------------------------------------------
;; Live blocks
;; ---------------------------------------------------------------------------

(deftest live-block-indices-follow-the-reflow
  (testing "block start-idx values still point at the block's own rows"
    (fake-fullscreen! 100)
    (layout/update-live-block! :blk-a ["block-a-1" "block-a-2"])
    (layout/write-output! (fmt/format-answer long-text)
                          {:render #(fmt/format-answer long-text %)})
    (layout/update-live-block! :blk-b ["block-b-1"])
    (let [b-before (:start-idx (get @layout/!live-blocks :blk-b))]
      (resize! 55)
      (let [sb @layout/!scrollback
            a  (get @layout/!live-blocks :blk-a)
            b  (get @layout/!live-blocks :blk-b)]
        (is (= ["block-a-1" "block-a-2"]
               (subvec sb (:start-idx a) (+ (:start-idx a) (:line-count a)))))
        (is (= ["block-b-1"]
               (subvec sb (:start-idx b) (+ (:start-idx b) (:line-count b)))))
        (is (not= b-before (:start-idx b))
            "the answer between the blocks grew a row, so blk-b moved")
        (is (zero? (rows-wider-than 55)))))))

(deftest dispose-and-freeze-keep-the-source-in-step
  (testing "removing and freezing blocks leaves a source that still describes
            every row"
    (fake-fullscreen! 100)
    (layout/update-live-block! :keep ["keep-1"])
    (layout/update-live-block! :drop ["drop-1" "drop-2"])
    (layout/write-output! (fmt/format-answer long-text)
                          {:render #(fmt/format-answer long-text %)})
    (layout/dispose-live-block! :drop)
    (layout/freeze-live-block! :keep)
    (is (nil? (some #{"drop-1"} @layout/!scrollback)) "disposed rows are gone")
    (is (= {} @layout/!live-blocks))
    (is (= (count @layout/!scrollback) (src-row-total))
        "the source accounts for exactly the rows on screen")
    (resize! 50)
    (is (some #{"keep-1"} @layout/!scrollback)
        "a frozen block's rows survive the reflow as ordinary scrollback")
    (is (zero? (rows-wider-than 50)))))

(deftest a-frozen-block-still-reflows
  (testing "a live block that carried :render keeps re-wrapping after it is
            frozen — which is when it matters most, because a finished
            iteration never ticks again and would otherwise keep whatever
            width it was last rendered at forever"
    (fake-fullscreen! 24)
    ;; A renderer that wraps to the CURRENT layout width, the way every block
    ;; renderer in `session` does.
    (let [render (fn [_cols]
                   (let [w (max 4 (- (long (:cols @layout/!layout)) 4))]
                     (mapv #(str "  " %)
                           (loop [s long-text, acc []]
                             (if (<= (count s) w)
                               (conj acc s)
                               (recur (subs s w) (conj acc (subs s 0 w))))))))]
      (layout/update-live-block! :iter (render nil) {:render render})
      (let [narrow (count @layout/!scrollback)]
        (is (pos? narrow))
        (layout/freeze-live-block! :iter)
        (is (= {} @layout/!live-blocks) "block is frozen, no longer live")
        (resize! 160)
        (is (< (count @layout/!scrollback) narrow)
            "the frozen block re-wrapped to the wider terminal")
        (is (zero? (rows-wider-than 160)))))))

;; ---------------------------------------------------------------------------
;; Prompt formatters — width-aware in their own right
;;
;; These had no width term at all, so no amount of `:render` would have made
;; them reflow: they overflowed a narrow pane at first paint, not just after a
;; resize. A permission prompt is the sharp case — a clipped one asks the user
;; to approve something they cannot fully read.
;; ---------------------------------------------------------------------------

;; `format-welcome-banner` and `format-next-prompt` got the same treatment and
;; are covered in `agent.tui.format-test` — they are component-internal and
;; their width comes from the impl's own cached terminal size, which a base
;; test cannot set through the interface.

(deftest permission-prompts-wrap-to-the-pane
  (let [question (str "Allow write to /Users/someone/Projects/deeply/nested/path/"
                      "inside/a/repository/module/file_with_a_long_name.clj?")
        options  [{:label "yes" :description "allow this once"}
                  {:label "always" :description (str "allow every write under this "
                                                     "directory for the session")}]
        choices  [{:key \y :label "yes"} {:key \n :label "no"} {:key \d :label "never"}]]
    (doseq [cols [40 72 200]]
      (testing (str "select prompt at " cols " columns")
        (is (every? #(<= (fmt/display-width %) cols)
                    (perms/format-feedback-lines question options cols))))
      (testing (str "confirm prompt at " cols " columns")
        (is (every? #(<= (fmt/display-width %) cols)
                    (perms/format-confirm-lines question choices cols))))
      (testing (str "text prompt at " cols " columns")
        (is (every? #(<= (fmt/display-width %) cols)
                    (perms/format-text-lines question cols)))))))

;; ---------------------------------------------------------------------------
;; Drift
;; ---------------------------------------------------------------------------

(deftest drift-degrades-to-no-reflow-never-to-corruption
  (testing "rows swapped in behind layout's back (a session switch, a test)
            are preserved as-is and the source re-establishes itself"
    (fake-fullscreen! 100)
    (layout/write-output! (fmt/format-answer long-text)
                          {:render #(fmt/format-answer long-text %)})
    (reset! layout/!scrollback ["a-row-layout-never-saw"])
    (reset! layout/!live-blocks {})
    (resize! 40)
    (is (= ["a-row-layout-never-saw"] @layout/!scrollback)
        "an inconsistent source must not rewrite the rows")
    (layout/write-output! "after-drift" {:render (constantly ["after-drift"])})
    (is (= (count @layout/!scrollback) (src-row-total))
        "the next write rebuilds a source that covers every row")))

;; ---------------------------------------------------------------------------
;; Scroll position
;; ---------------------------------------------------------------------------

(defn- top-visible-row
  "The scrollback row currently at the top of the viewport."
  []
  (let [{:keys [viewport-offset scroll-bottom]} @layout/!layout
        total (count @layout/!scrollback)]
    (get @layout/!scrollback
         (max 0 (- total (long viewport-offset) (long scroll-bottom))))))

(defn- top-entry-index
  "Which source entry the top visible row belongs to. The row's TEXT changes
   when it re-wraps (that is the point), so identity has to be expressed in
   the thing a reflow preserves: which emit the row came from."
  []
  (let [{:keys [viewport-offset scroll-bottom]} @layout/!layout
        entries @layout/!scrollback-src
        total   (count @layout/!scrollback)
        top     (max 0 (- total (long viewport-offset) (long scroll-bottom)))]
    (loop [i 0, acc 0]
      (cond
        (>= i (count entries)) nil
        (< top (+ acc (long (:n (nth entries i))))) i
        :else (recur (inc i) (+ acc (long (:n (nth entries i)))))))))

(defn- fill-scrollback!
  "n numbered one-row emits, each individually reflowable so the entry list is
   fully populated. Row text is stable across widths, so a row can be used to
   identify a position."
  [n]
  (doseq [i (range n)]
    (let [line (format "row-%03d" i)]
      (layout/write-output! line {:render (constantly [line])}))))

(deftest scrolled-back-position-survives-a-resize
  (testing "the text at the top of the viewport is still there afterwards"
    (fake-fullscreen! 100)
    (fill-scrollback! 200)
    ;; Park the viewport well back from the tail.
    (swap! layout/!layout assoc :viewport-offset 120)
    (let [before (top-visible-row)]
      (is (some? before))
      (resize! 60)
      (is (= before (top-visible-row))
          "the reader is looking at the same line, not the same row number"))))

(deftest following-live-output-stays-at-the-tail
  (testing "offset 0 is preserved — a resize must not scroll the user away
            from the live tail"
    (fake-fullscreen! 100)
    (fill-scrollback! 200)
    (is (zero? (:viewport-offset @layout/!layout)))
    (resize! 55)
    (is (zero? (:viewport-offset @layout/!layout)))
    (is (= (last @layout/!scrollback) (last @layout/!scrollback)))
    (resize! 140)
    (is (zero? (:viewport-offset @layout/!layout)))))

(deftest anchor-holds-through-a-genuine-rewrap
  (testing "position is kept even when the entries around it change height"
    (fake-fullscreen! 100)
    ;; Reflowable long answers, whose row counts DO change with width.
    (dotimes [i 12]
      (let [t (str "answer-" i " " long-text)]
        (layout/write-output! (fmt/format-answer t)
                              {:render #(fmt/format-answer t %)})))
    (swap! layout/!layout assoc :viewport-offset 30)
    (let [before      (top-entry-index)
          rows-before (count @layout/!scrollback)]
      (is (some? before))
      (resize! 55)
      (is (not= rows-before (count @layout/!scrollback))
          "precondition: the rewrap actually changed the row count")
      (is (= before (top-entry-index))
          "the same answer is on top, at a different row index — its rendered
           text necessarily differs, since re-wrapping it is the whole point"))))

(deftest offset-is-clamped-when-growing-leaves-nothing-to-scroll
  (testing "a wider terminal can fit everything; the offset must not survive
            as a value past the end"
    (fake-fullscreen! 40)
    (fill-scrollback! 40)
    (swap! layout/!layout assoc :viewport-offset 20)
    (resize! 200)
    (let [{:keys [viewport-offset scroll-bottom]} @layout/!layout
          total (count @layout/!scrollback)]
      (is (<= 0 viewport-offset (max 0 (- total scroll-bottom)))
          "offset stays inside the scrollable range"))))

;; ---------------------------------------------------------------------------
;; Paint-time clamp
;; ---------------------------------------------------------------------------

(defn- painted-rows
  "Run `render-viewport!` against a capturing writer and return the visible
   text of each row it addressed."
  []
  (let [sw (java.io.StringWriter.)]
    (swap! layout/!layout assoc :writer (java.io.PrintWriter. sw))
    (#'layout/render-viewport!)
    (->> (str/split (str sw) #"\[\d+;1H")
         (map #(str/replace % #"\[[0-9;]*[A-Za-z]" ""))
         (remove str/blank?))))

(deftest overlong-rows-are-clipped-at-paint-time
  (testing "a row wider than the terminal is never emitted at full width"
    (fake-fullscreen! 100)
    ;; No :render, so the resize cannot re-wrap these rows.
    (layout/write-output! (fmt/format-answer long-text))
    (resize! 60)
    (is (pos? (rows-wider-than 60))
        "precondition: the buffer still holds rows too wide for the terminal")
    (let [rows (painted-rows)]
      (is (seq rows) "the viewport painted something")
      (is (every? #(<= (fmt/display-width %) 60) rows)
          "no painted row can overflow — an overflow wraps onto the row below,
           which the next erase-line wipes, and on the bottom region row it
           scrolls the region out from under the absolute row accounting"))))
