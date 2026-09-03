;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.scroll-keys-test
  "Ctrl / Shift+Ctrl arrow shortcuts for scrolling.

   Ctrl+Up and Ctrl+Down are aliases of PgUp and PgDn; Shift+Ctrl+Up and
   Shift+Ctrl+Down jump to the head and back to live. Two halves are worth
   pinning separately:

   1. DECODING. These arrive as `ESC [ 1 ; <mod> <A|B>`, where `mod` is the
      xterm convention of 1 plus a bitmask of Shift=1, Alt=2, Ctrl=4 — so 5 is
      Ctrl and 6 is Shift+Ctrl. Getting the bitmask backwards yields a
      plausible-looking sequence that silently does the wrong thing, and an
      unhandled combination must DRAIN rather than leave digits to be typed
      into the input line.

   2. THE JUMP ITSELF. `scroll-to-top!` is the new one, and its contract
      differs from `scroll-to-bottom!` in a way that is easy to get wrong: it
      does NOT clear the search, because going to the head is still deliberate
      navigation rather than the user saying they are done."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.terminal :as terminal]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- key-of
  "Decode `s` through `read-key!` as raw bytes. Same helper as `mouse_test`:
   the input-reader thread is not running under test, so the stream is read
   directly."
  [^String s]
  (terminal/read-key! (java.io.ByteArrayInputStream. (.getBytes s "UTF-8"))))

(def ^:private ESC "\033")

(def ^:private region-rows 30)

(defn- fake-fullscreen! [n-rows]
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
           :writer (java.io.PrintWriter. (java.io.StringWriter.))}))

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
;; Decoding
;; ---------------------------------------------------------------------------

(deftest ctrl-arrows-decode
  (is (= :ctrl-arrow-up   (key-of (str ESC "[1;5A"))))
  (is (= :ctrl-arrow-down (key-of (str ESC "[1;5B")))))

(deftest shift-ctrl-arrows-decode
  ;; mod 6 = 1 + Shift(1) + Ctrl(4). Transposing this with 5 is the easy
  ;; mistake and would silently bind the jump keys to paging.
  (is (= :ctrl-shift-arrow-up   (key-of (str ESC "[1;6A"))))
  (is (= :ctrl-shift-arrow-down (key-of (str ESC "[1;6B")))))

(deftest existing-arrow-decoding-is-unchanged
  ;; The modified-arrow branch was shared with these; a regression here is the
  ;; likely cost of touching it.
  (is (= :shift-arrow-right (key-of (str ESC "[1;2C"))))
  (is (= :shift-arrow-left  (key-of (str ESC "[1;2D"))))
  (is (= :scroll-up   (key-of (str ESC "[A"))))
  (is (= :scroll-down (key-of (str ESC "[B"))))
  (is (= :arrow-right (key-of (str ESC "[C"))))
  (is (= :arrow-left  (key-of (str ESC "[D"))))
  (is (= :page-up   (key-of (str ESC "[5~"))))
  (is (= :page-down (key-of (str ESC "[6~")))))

(deftest an-unhandled-modifier-combination-is-consumed-whole
  ;; Alt+Up is mod 3, Shift+Up is mod 2 on A — neither is bound. What must NOT
  ;; happen is the tail escaping into the input line as typed text.
  (doseq [s [(str ESC "[1;3A") (str ESC "[1;2A") (str ESC "[1;7B") (str ESC "[1;5Z")]]
    (is (= :unknown (key-of s)) (str "expected :unknown for " (pr-str s)))))

;; ---------------------------------------------------------------------------
;; scroll-to-top!
;; ---------------------------------------------------------------------------

(deftest scroll-to-top-lands-on-the-oldest-row
  (fake-fullscreen! 500)
  (layout/scroll-to-top!)
  (let [{:keys [viewport-offset scroll-bottom]} @layout/!layout
        total (count @layout/!scrollback)
        end   (- total (long viewport-offset))
        start (max 0 (- end (long scroll-bottom)))]
    (is (zero? start) "the first scrollback row is on screen")
    (is (= (- total region-rows) viewport-offset)
        "and the offset is the maximum, not something past it")))

(deftest scroll-to-top-cannot-overshoot-a-short-scrollback
  ;; Fewer rows than the region: there is nothing to scroll, and the clamp is
  ;; what keeps the offset from going negative-by-way-of-huge.
  (fake-fullscreen! 10)
  (layout/scroll-to-top!)
  (is (zero? (:viewport-offset @layout/!layout))))

(deftest scroll-to-top-keeps-the-search-but-to-bottom-drops-it
  ;; The asymmetry is deliberate. Returning to live is the user saying they are
  ;; done with wherever they were; going to the head is not.
  (fake-fullscreen! 500)
  (layout/set-search! "line 4")
  (layout/scroll-to-top!)
  (is (some? (:search @layout/!layout))
      "jumping to the head keeps the highlights")
  (layout/scroll-to-bottom!)
  (is (nil? (:search @layout/!layout))
      "returning to live still clears them"))

(deftest the-two-jumps-are-inverses
  (fake-fullscreen! 500)
  (layout/scroll-to-top!)
  (is (pos? (:viewport-offset @layout/!layout)))
  (layout/scroll-to-bottom!)
  (is (zero? (:viewport-offset @layout/!layout)))
  (is (= "line 499" (last @layout/!scrollback))
      "and the tail is what is on screen at offset 0"))

(deftest jumping-to-the-head-then-emitting-still-holds-the-view
  ;; The jump keys land in scroll mode, so the viewport-hold rule has to apply
  ;; to them exactly as it does to PgUp.
  (fake-fullscreen! 500)
  (layout/scroll-to-top!)
  (let [{:keys [scroll-bottom viewport-offset]} @layout/!layout
        total  (count @layout/!scrollback)
        end    (- total (long viewport-offset))
        before (subvec @layout/!scrollback (max 0 (- end (long scroll-bottom))) end)]
    (dotimes [i 5] (layout/write-output! (str "streamed " i)))
    (let [{:keys [scroll-bottom viewport-offset]} @layout/!layout
          total (count @layout/!scrollback)
          end   (- total (long viewport-offset))
          after (subvec @layout/!scrollback (max 0 (- end (long scroll-bottom))) end)]
      (is (= before after) "output must not move a viewport parked at the head"))))
