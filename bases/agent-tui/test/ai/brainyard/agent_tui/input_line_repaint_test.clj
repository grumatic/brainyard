;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.input-line-repaint-test
  "Tests for what the input line repaints itself FROM.

   The readline editor owns its buffer as a local, so the SIGWINCH handler
   repaints the input line from `layout/!last-input` instead. That record was
   only ever written by `redraw-input-line!` — the call that FILLS the line —
   and never by the calls that BLANK it. So a submitted message outlived its
   own input box: the loop painted an empty idle prompt over it, the record
   still said `\"hello world\"`, and the next resize put it back into an input
   box that no longer held it (typing one character then made it vanish again,
   since a keystroke redraws from the real buffer)."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.terminal :as terminal]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(def ^:private submitted "hello world from the input line")

(defn- fake-fullscreen!
  "Put the layout into fullscreen with a writer we can read back."
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

(defn- reset-layout-fixture [t]
  (let [saved @layout/!layout
        saved-input @layout/!last-input]
    (try (t)
         (finally
           (reset! layout/!scrollback [])
           (reset! layout/!live-blocks {})
           (reset! layout/!scrollback-src [])
           (reset! layout/!last-input saved-input)
           (reset! layout/!layout saved)))))

(use-fixtures :each reset-layout-fixture)

(defn- repaint-as-sigwinch-would!
  "What `install-sigwinch-handler!` does to the input line on a resize."
  []
  (let [{:keys [buffer cursor-pos]} (layout/last-input)]
    (terminal/redraw-input-line! buffer cursor-pos)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest typing-is-what-a-resize-repaints
  (testing "a live buffer survives a resize — the record is the editor's proxy"
    (fake-fullscreen!)
    (terminal/redraw-input-line! submitted (count submitted))
    (is (= submitted (:buffer (layout/last-input))))
    (let [sw (fake-fullscreen!)]
      (reset! layout/!last-input {:buffer submitted :cursor-pos (count submitted)})
      (repaint-as-sigwinch-would!)
      (is (str/includes? (str sw) submitted)
          "mid-typing text must be repainted after a resize, not dropped"))))

(deftest blanking-the-line-clears-what-a-resize-repaints
  (testing "the idle prompt drops the submitted line from the repaint record"
    (fake-fullscreen!)
    (terminal/redraw-input-line! submitted (count submitted))
    (is (= submitted (:buffer (layout/last-input))))
    ;; What the input loop does at the top of the next iteration.
    (tui-session/redraw-idle-prompt!)
    (is (= "" (:buffer (layout/last-input))))
    (is (zero? (:cursor-pos (layout/last-input)))))

  (testing "so the resize repaint does NOT put the submitted line back"
    (fake-fullscreen!)
    (terminal/redraw-input-line! submitted (count submitted))
    (tui-session/redraw-idle-prompt!)
    (let [sw (fake-fullscreen!)]
      (repaint-as-sigwinch-would!)
      (is (not (str/includes? (str sw) submitted))
          "the submitted message must not reappear in the input box on resize")))

  (testing "draw-input-prompt! clears it outside fullscreen too"
    (reset! layout/!layout (assoc @layout/!layout :mode :inline))
    (layout/set-last-input! submitted (count submitted))
    (layout/draw-input-prompt! "> ")
    (is (= "" (:buffer (layout/last-input))))))

(deftest a-free-input-feedback-answer-survives-a-resize
  ;; The other half of the same rule: a typed answer is only repaintable if it
  ;; lives in the editor's buffer. Free-input `:select` answers used to be
  ;; collected byte-by-byte and echoed straight at the terminal, so no repaint
  ;; could reproduce them and a resize wiped what had been typed.
  (let [pr (promise)
        typed "my own answer"]
    (try
      (reset! tui-session/!pending-feedback
              {:promise pr :kind :select :mode :awaiting-text :free-idx 1
               :options [{:label "A"} {:label "Other" :free-input true}]})
      (fake-fullscreen!)
      (terminal/redraw-input-line! typed (count typed))
      (is (= typed (:buffer (layout/last-input)))
          "the answer is ordinary input, recorded like any other")
      (let [sw (fake-fullscreen!)]
        (reset! layout/!last-input {:buffer typed :cursor-pos (count typed)})
        (repaint-as-sigwinch-would!)
        (is (str/includes? (str sw) typed)
            "a half-typed feedback answer must survive a resize"))
      (finally (reset! tui-session/!pending-feedback nil)))))
