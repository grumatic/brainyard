;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.editor-handover-test
  "Tests for the terminal-handover gate — who may paint while $EDITOR owns the
   screen.

   Ctrl-O (and a click on a file location) suspends the TUI, leaves the alt
   screen and hands the terminal to $EDITOR. Stopping the input reader was the
   only thing that changed, and the writers that broke this were never
   keystroke-driven: the 15s idle-tip ticker, a turn finishing in the
   background, a status refresh. Each of them repainted the input line at a row
   number that was true for OUR layout, drawing the prompt across the middle of
   the editor's output.

   So the gate is latched for the whole handover and enforced at
   `raw-write-direct!`, the single point where bytes reach the terminal."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.terminal :as terminal]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- fake-fullscreen!
  "Fullscreen layout with a writer we can read back."
  []
  (let [sw (java.io.StringWriter.)]
    (reset! layout/!scrollback [])
    (reset! layout/!live-blocks {})
    (reset! layout/!scrollback-src [])
    (reset! layout/!layout
            {:mode :fullscreen :rows 40 :cols 100
             :scroll-bottom 35 :separator-row 36 :input-row 37
             :separator2-row 38 :tab-row 39 :status-row 40
             :viewport-offset 0 :input-height 1 :input-height-max 6
             :menu-height 0 :task-activity-height 0 :agent-activity-height 0
             :input-active true
             :writer (java.io.PrintWriter. sw)})
    sw))

(defn- reset-fixture [t]
  (let [saved @layout/!layout
        saved-input @layout/!last-input]
    (try (t)
         (finally
           ;; A test that failed mid-handover must not leave the gate latched
           ;; for every namespace after it.
           (layout/set-external-owner! false)
           (reset! layout/!scrollback [])
           (reset! layout/!live-blocks {})
           (reset! layout/!scrollback-src [])
           (reset! layout/!last-input saved-input)
           (reset! layout/!layout saved)))))

(use-fixtures :each reset-fixture)

(defn- on-another-thread
  "Run `f` on a thread that is NOT the one holding the handover — which is what
   every background writer is."
  [f]
  (let [t (Thread. ^Runnable f)]
    (.start t)
    (.join t 5000)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest background-writers-cannot-paint-while-the-editor-owns-the-screen
  (let [sw (fake-fullscreen!)]
    (layout/set-external-owner! true)
    (on-another-thread
     (fn []
       ;; The three shapes that were landing on top of $EDITOR: the input line
       ;; (idle-tip ticker / suggestion hook), an emit from a turn still
       ;; running, and a chrome refresh.
       (terminal/redraw-input-line! "typed in the background" 5)
       (layout/write-output! "an answer chunk arriving mid-edit")
       (layout/draw-status-bar! "agent" "running")))
    (is (= "" (str sw))
        "not one byte may reach a terminal another program owns")

    (testing "and they paint again once the terminal comes back"
      (layout/set-external-owner! false)
      (on-another-thread (fn [] (layout/draw-status-bar! "agent" "idle")))
      (is (str/includes? (str sw) "idle")))))

(deftest the-thread-performing-the-handover-still-paints
  ;; It has to: the alt-screen leave on the way out and the full repaint on the
  ;; way back are both writes, and gating them would leave $EDITOR running
  ;; inside our alt-screen and the screen never rebuilt.
  (let [sw (fake-fullscreen!)]
    (layout/set-external-owner! true)
    (layout/draw-status-bar! "agent" "handing over")
    (is (str/includes? (str sw) "handing over"))))

(deftest a-deferred-emit-is-dropped-from-the-screen-but-not-from-the-scrollback
  ;; This is what makes dropping bytes safe rather than lossy: only the
  ;; terminal write is gated, so the repaint on return replays the content.
  (let [sw (fake-fullscreen!)
        text "an answer that arrived while the editor was open"]
    (layout/set-external-owner! true)
    (on-another-thread (fn [] (layout/write-output! text)))
    (is (= "" (str sw)) "nothing on screen")
    (is (some #(str/includes? % text) @layout/!scrollback)
        "but the scrollback has it, which is what the repaint reads")

    (layout/set-external-owner! false)
    (let [sw2 (java.io.StringWriter.)]
      (swap! layout/!layout assoc :writer (java.io.PrintWriter. sw2))
      (layout/render-viewport!)
      (is (str/includes? (str sw2) text)
          "the repaint on return recovers what the gate dropped"))))

(deftest releasing-the-gate-forgets-the-painted-rows
  ;; `note-painted!` records what a paint wrote, and a paint the gate dropped
  ;; was recorded all the same. Without the invalidation on release, that row
  ;; would be skipped as already-correct and stay stale until its text changed.
  (let [_ (fake-fullscreen!)
        text "a row the cache believes is on screen"]
    (layout/write-output! text)
    (layout/render-viewport!)
    (let [sw2 (java.io.StringWriter.)]
      (swap! layout/!layout assoc :writer (java.io.PrintWriter. sw2))
      (layout/render-viewport!)
      (is (not (str/includes? (str sw2) text))
          "baseline: an unchanged row is skipped by the painted-row cache"))

    (layout/set-external-owner! true)
    (layout/set-external-owner! false)
    (let [sw3 (java.io.StringWriter.)]
      (swap! layout/!layout assoc :writer (java.io.PrintWriter. sw3))
      (layout/render-viewport!)
      (is (str/includes? (str sw3) text)
          "after a handover the cache must not be trusted"))))
