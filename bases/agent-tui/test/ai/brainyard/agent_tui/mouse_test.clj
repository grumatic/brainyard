;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.mouse-test
  "Tests for TUI mouse click support — the three layers a click passes through:

   1. `terminal/read-key!` decoding an SGR mouse report off the byte stream.
   2. `layout/row->scrollback-idx` turning a terminal row into the scrollback
      line the user actually sees there.
   3. `sessions/tab-at-column` turning a column on the tab row into a session.

   Layer 2 is the one worth being strict about: it is `render-viewport!` read
   backwards, and the two agreeing is a property, not a coincidence — so the
   test paints through a captured writer and asserts the mapping against what
   was really written, rather than against a second copy of the arithmetic."
  (:require [ai.brainyard.agent-tui.display-block-ui :as block-ui]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.links :as links]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.agent-tui.terminal :as terminal]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- key-of
  "Run `s` through `read-key!` as raw bytes and return the decoded key.
   The input-reader thread is not running under test, so `read-key!` reads the
   stream directly."
  [^String s]
  (terminal/read-key! (java.io.ByteArrayInputStream. (.getBytes s "UTF-8"))))

(defn- fake-fullscreen!
  "Fullscreen layout over a capturing writer. `scroll-bottom` rows of scroll
   region; the chrome rows below it match `recalc-layout-rows!`'s stacking."
  [scroll-bottom lines viewport-offset]
  (let [rows (+ scroll-bottom 5)
        sw   (java.io.StringWriter.)]
    (reset! layout/!scrollback (vec lines))
    (reset! layout/!live-blocks {})
    (reset! layout/!scrollback-src [])
    ;; Same reason `!scrollback-src` is reset above: replacing the rows behind
    ;; the renderer's back leaves its painted-row cache describing the PREVIOUS
    ;; test's screen, and the diff would then skip every row that happens to
    ;; match. Production callers that swap the rows wholesale (a session switch)
    ;; invalidate for exactly this reason.
    (layout/invalidate-painted!)
    (reset! layout/!layout
            {:mode :fullscreen :rows rows :cols 80
             :scroll-bottom scroll-bottom
             :separator-row (+ scroll-bottom 1)
             :input-row     (+ scroll-bottom 2)
             :separator2-row (+ scroll-bottom 3)
             :tab-row       (+ scroll-bottom 4)
             :status-row    rows
             :viewport-offset viewport-offset
             :input-height 1 :menu-height 0
             :task-activity-height 0 :agent-activity-height 0
             :writer (java.io.PrintWriter. sw)})
    sw))

(defn- painted-rows
  "Parse what `render-viewport!` wrote into `sw` as {row -> text}. Matches the
   `cursor-to` + content pair the renderer emits per row; the content capture
   stops at the next escape, which is the row's own trailing `reset` +
   `erase-eol`. The erase moved AFTER the content (it used to be an
   `erase-line` before it) so a repaint overwrites in place instead of blanking
   the row first — see `ansi/erase-eol`."
  [^java.io.StringWriter sw]
  (into {}
        (map (fn [[_ row text]] [(parse-long row) text]))
        (re-seq #"\033\[(\d+);1H([^\033]*)" (str sw))))

(use-fixtures :each
  (fn [t]
    (sessions/reset-sessions!)
    (t)
    (sessions/reset-sessions!)
    (reset! layout/!scrollback [])
    (reset! layout/!live-blocks {})
    (reset! layout/!scrollback-src [])
    (reset! layout/!layout {:mode :inline :rows 24 :cols 80})))

;; ---------------------------------------------------------------------------
;; 1. SGR decoding
;; ---------------------------------------------------------------------------

(deftest sgr-press-decodes-to-a-mouse-map
  (testing "left press carries 1-based row/col and no modifiers"
    (is (= {:type :mouse :button :left :col 10 :row 5
            :shift? false :alt? false :ctrl? false}
           (key-of "\033[<0;10;5M"))))
  (testing "middle and right buttons are distinguished"
    (is (= :middle (:button (key-of "\033[<1;3;4M"))))
    (is (= :right  (:button (key-of "\033[<2;3;4M")))))
  (testing "modifier bits decode independently (shift 4, alt 8, ctrl 16)"
    (let [k (key-of "\033[<28;3;4M")]
      (is (= :left (:button k)))
      (is (true? (:shift? k)))
      (is (true? (:alt? k)))
      (is (true? (:ctrl? k))))))

(deftest sgr-handles-coordinates-past-the-x10-ceiling
  (testing "the reason ?1006h is mandatory: X10 packs a coord into 32+n and
            cannot address past column 223"
    (is (= {:type :mouse :button :left :col 400 :row 250
            :shift? false :alt? false :ctrl? false}
           (key-of "\033[<0;400;250M")))))

(deftest wheel-maps-back-to-the-scroll-keywords
  (testing "?1000h supersedes ?1007h, so the wheel arrives as buttons 64/65 —
            mapping them back is what keeps scrolling alive"
    (is (= :scroll-up   (key-of "\033[<64;1;1M")))
    (is (= :scroll-down (key-of "\033[<65;1;1M"))))
  (testing "horizontal wheel (66/67) is not a scroll action — swallowed"
    (is (= :unknown (key-of "\033[<66;1;1M")))
    (is (= :unknown (key-of "\033[<67;1;1M")))))

(deftest release-and-motion-are-swallowed
  (testing "release (`m` final byte) yields no action"
    (is (= :unknown (key-of "\033[<0;10;5m"))))
  (testing "motion (bit 32) yields no action even on the press final byte"
    (is (= :unknown (key-of "\033[<32;10;5M")))))

(deftest malformed-reports-degrade-to-unknown
  (testing "a non-mouse sequence opening ESC[< never reaches the printable branch"
    (is (= :unknown (key-of "\033[<0;10;5X")))
    (is (= :unknown (key-of "\033[<not-a-report")))
    (is (= :unknown (key-of "\033[<1;2M")))          ;; too few params
    (is (= :unknown (key-of "\033[<0;0;5M"))))       ;; 0 is not a valid 1-based coord
  (testing "an unterminated report is bounded by sgr-max-bytes, not the stream"
    (is (= :unknown (key-of (str "\033[<" (str/join ";" (repeat 20 "9"))))))))

(deftest ordinary-keys-still-decode
  (testing "the new CSI branch does not disturb the sequences beside it"
    (is (= :scroll-up   (key-of "\033[A")))
    (is (= :scroll-down (key-of "\033[B")))
    (is (= :arrow-left  (key-of "\033[D")))
    (is (= :page-up     (key-of "\033[5~")))
    (is (= :page-down   (key-of "\033[6~")))
    (is (= "a"          (key-of "a")))))

;; ---------------------------------------------------------------------------
;; 2. row -> scrollback index
;; ---------------------------------------------------------------------------

(deftest row-mapping-agrees-with-what-was-painted
  (testing "for every row in the scroll region, row->scrollback-idx names the
            line render-viewport! actually wrote there — at the tail, scrolled
            up, and while content is short enough to leave blank padding"
    (doseq [[n-lines offset label] [[100 0  "full viewport, live tail"]
                                    [100 17 "scrolled up 17 lines"]
                                    [100 90 "scrolled near the top"]
                                    [4   0  "content shorter than the region"]
                                    [0   0  "empty scrollback"]]]
      (testing label
        (let [scroll-bottom 10
              lines (mapv #(str "line-" %) (range n-lines))
              sw    (fake-fullscreen! scroll-bottom lines offset)]
          (layout/render-viewport!)
          (let [painted (painted-rows sw)]
            (is (= scroll-bottom (count painted))
                "every region row is addressed, blank or not")
            (doseq [row (range 1 (inc scroll-bottom))]
              (let [idx  (layout/row->scrollback-idx row)
                    text (get painted row)]
                (if (str/blank? text)
                  (is (nil? idx)
                      (str "row " row " painted nothing, so it maps to no line"))
                  (is (= text (get lines idx))
                      (str "row " row " maps to the line printed on it")))))))))))

(deftest rows-outside-the-scroll-region-map-to-nothing
  (let [scroll-bottom 10]
    (fake-fullscreen! scroll-bottom (mapv str (range 50)) 0)
    (testing "chrome rows are not scrollback"
      (is (nil? (layout/row->scrollback-idx (+ scroll-bottom 1))) "separator")
      (is (nil? (layout/row->scrollback-idx (+ scroll-bottom 4))) "tab row")
      (is (nil? (layout/row->scrollback-idx 0)))
      (is (nil? (layout/row->scrollback-idx 999))))))

(deftest row-mapping-is-inline-safe
  (testing "inline mode has no row model, so a click can never be located"
    (reset! layout/!layout {:mode :inline :rows 24 :cols 80 :scroll-bottom 10})
    (is (nil? (layout/row->scrollback-idx 3)))))

;; ---------------------------------------------------------------------------
;; 2b. the marker lookup a scroll-region click performs
;; ---------------------------------------------------------------------------

(deftest clicked-line-resolves-to-the-marker-on-it
  (testing "a click resolves row -> scrollback idx -> the block marker on that
            single line, and only that line"
    (let [marker "[*Block:abc123* collapsed: 42 lines]"
          lines  ["before" marker "after"]
          _      (fake-fullscreen! 10 lines 0)
          ;; 3 lines in a 10-row region → 7 blank rows, content on rows 8..10.
          idx    (layout/row->scrollback-idx 9)]
      (is (= 1 idx) "row 9 holds the marker line")
      (let [hits (block-ui/find-markers-in-range idx (inc idx))]
        (is (= 1 (count hits)))
        (is (= "abc123" (:id (first hits))))
        (is (= :collapsed (:kind (first hits))))
        (is (= idx (:line-idx (first hits)))))
      (testing "a neighbouring line has no marker, so the click does nothing"
        (let [plain-idx (layout/row->scrollback-idx 8)]
          (is (= 0 plain-idx))
          (is (empty? (block-ui/find-markers-in-range plain-idx (inc plain-idx)))))))))

;; ---------------------------------------------------------------------------
;; 2c. recovering the logical text behind a wrapped row
;; ---------------------------------------------------------------------------

(defn- wrap-at
  "Hard-wrap `s` into rows of `cols` characters — a stand-in for what the real
   renderers do to a token too long for the pane."
  [^String s cols]
  (mapv #(apply str %) (partition-all cols s)))

(deftest unwrapped-entry-text-recovers-what-the-wrap-broke
  (let [url  "https://example.com/very/long/path/that/does/not/fit/on/one/row"
        text (str "see " url " end")]
    (fake-fullscreen! 10 [] 0)
    (layout/write-output! (str/join "\n" (wrap-at text 20))
                          {:render (fn [cols] (wrap-at text cols))})
    (testing "the visible rows really did split the URL"
      (is (< 1 (count @layout/!scrollback)))
      (is (not-any? #(str/includes? % url) @layout/!scrollback)))
    (testing "re-rendering the owning entry wide puts it back together"
      (let [un (layout/unwrapped-entry-text 0)]
        (is (some? un))
        (is (str/includes? un url))))
    (testing "every row of the entry resolves to the same unwrapped text"
      (is (apply = (map layout/unwrapped-entry-text
                        (range (count @layout/!scrollback))))))))

(deftest wrapped-recovery-composes-with-detection
  (testing "the end-to-end shape: a click on a fragment yields the whole target"
    (let [url  "https://example.com/very/long/path/that/does/not/fit"
          text (str "see " url " end")]
      (fake-fullscreen! 10 [] 0)
      (layout/write-output! (str/join "\n" (wrap-at text 20))
                            {:render (fn [cols] (wrap-at text cols))})
      (let [row      (nth @layout/!scrollback 0)
            ;; column 8 is inside the URL fragment on the first row
            fragment (links/detect-in-row row 8)
            widened  (links/recover-target fragment (layout/unwrapped-entry-text 0))]
        (is (= :url (:kind fragment)))
        (is (not= url (:text fragment)) "the row alone holds only a fragment")
        (is (= url (:text widened)) "widening against the entry recovers it")))))

(deftest live-blocks-are-excluded-from-unwrapping
  (testing "block renderers take width from !layout, not the argument, so a
            wide re-render would be both useless and re-entrant"
    (fake-fullscreen! 10 [] 0)
    (layout/update-live-block! :blk ["block row one" "block row two"] {})
    (is (nil? (layout/unwrapped-entry-text 0)))))

(deftest unwrapping-degrades-rather-than-throws
  (fake-fullscreen! 10 ["plain row"] 0)
  (testing "an index past the end has no owning entry"
    (is (nil? (layout/unwrapped-entry-text 999))))
  (testing "a renderer that throws loses reflow, not the click"
    (fake-fullscreen! 10 [] 0)
    (layout/write-output! "boom" {:render (fn [_] (throw (ex-info "nope" {})))})
    (is (nil? (layout/unwrapped-entry-text 0))))
  (testing "a non-reflowable entry yields its own rows, so nothing is recovered
            and nothing breaks"
    (fake-fullscreen! 10 [] 0)
    (layout/write-output! "just text")
    (is (= "just text" (layout/unwrapped-entry-text 0)))))

;; ---------------------------------------------------------------------------
;; 3. tab strip spans
;; ---------------------------------------------------------------------------

(defn- mk-session! [id label]
  (sessions/create-session! {:id id :label label :skip-agent-creation true}))

(deftest tab-spans-line-up-with-the-rendered-strip
  (testing "each span covers exactly the columns its segment occupies"
    (mk-session! 0 "main0")
    (mk-session! 1 "main1")
    (let [text  (sessions/format-tab-strip)
          plain (str/replace text #"\033\[[0-9;]*m" "")
          spans (sessions/tab-spans)]
      ;; " main0*" = 7 cols, " main1" = 6 cols.
      (is (= [{:id 0 :col-start 1 :col-end 7}
              {:id 1 :col-start 8 :col-end 13}] spans))
      (is (= 13 (fmt/display-width plain)))
      (testing "and the last span ends exactly where the strip does"
        (is (= (fmt/display-width plain) (:col-end (last spans))))))))

(deftest tab-at-column-resolves-clicks
  (mk-session! 0 "main0")
  (mk-session! 1 "main1")
  (sessions/format-tab-strip)
  (testing "a column inside a tab resolves to it"
    (is (= 0 (sessions/tab-at-column 2)))
    (is (= 0 (sessions/tab-at-column 7)))
    (is (= 1 (sessions/tab-at-column 8)))
    (is (= 1 (sessions/tab-at-column 13))))
  (testing "the leading space belongs to the tab that follows it"
    (is (= 0 (sessions/tab-at-column 1)))
    (is (= 1 (sessions/tab-at-column 8))))
  (testing "past the end of the strip resolves to nothing"
    (is (nil? (sessions/tab-at-column 14)))
    (is (nil? (sessions/tab-at-column 80)))))

(deftest tab-spans-measure-display-width-not-character-count
  (testing "a CJK label is 2 columns per glyph — a span computed from `count`
            would leave every later tab hit-testing to its neighbour"
    (mk-session! 0 "日本")
    (mk-session! 1 "main1")
    (let [_     (sessions/format-tab-strip)
          spans (sessions/tab-spans)]
      ;; " 日本*" = 1 + 2 + 2 + 1 = 6 columns (4 characters).
      (is (= {:id 0 :col-start 1 :col-end 6} (first spans)))
      (is (= 7 (:col-start (second spans)))
          "the second tab starts after 6 COLUMNS, not after 4 characters"))))

(deftest truncated-tabs-are-not-clickable
  (testing "a tab dropped past the `…` occupies no column, so hit-testing must
            not resolve to it — otherwise a click switches to a tab that is
            not on screen"
    (swap! layout/!layout assoc :cols 20)
    (dotimes [i 6] (mk-session! i (str "session" i)))
    (let [text  (sessions/format-tab-strip)
          plain (str/replace text #"\033\[[0-9;]*m" "")
          spans (sessions/tab-spans)]
      (is (str/ends-with? plain "…") "the strip really did overflow")
      (is (< (count spans) 6) "not every tab was painted")
      (testing "every span still maps back to a painted tab"
        (doseq [{:keys [id col-start col-end]} spans]
          (is (= id (sessions/tab-at-column col-start)))
          (is (= id (sessions/tab-at-column col-end)))))
      (testing "the dropped tabs are unreachable at any column"
        (let [painted (set (map :id spans))]
          (doseq [col (range 1 21)]
            (when-let [hit (sessions/tab-at-column col)]
              (is (contains? painted hit)))))))))

(deftest no-sessions-clears-the-spans
  (testing "spans never outlive their paint"
    (mk-session! 0 "main0")
    (sessions/format-tab-strip)
    (is (seq (sessions/tab-spans)))
    (sessions/reset-sessions!)
    (is (= "" (sessions/format-tab-strip)))
    (is (empty? (sessions/tab-spans)))
    (is (nil? (sessions/tab-at-column 1)))))
