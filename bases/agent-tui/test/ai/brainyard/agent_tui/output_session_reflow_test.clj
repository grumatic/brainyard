;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.output-session-reflow-test
  "Reflow for output written to a session that is NOT the active tab.

   An output-only tab (the shared sub-output tab a root's sub-agents render
   into) collects all of its content while it is in the background — the user is
   watching the chat tab the sub-agent was dispatched from. That put it outside
   both halves of the reflow machinery:

   - `emit-to-session!` dropped `:render` on the background path, so the rows
     were buffered with no way to re-render them. Worse, the source list then no
     longer covered the rows, and `ensure-src!` responds to that by rebuilding
     the WHOLE tab as non-reflowable — so one uncovered emit cost every OTHER
     emit its reflow too.
   - `handle-resize!` only ever reaches the ACTIVE tab. A resize while the tab
     was away never touched its buffered rows, so switching to it installed rows
     formatted for a width that had stopped being true.

   Either way the symptom is the same and permanent: a long line clipped at
   paint time, at every width, for the rest of the session."
  (:require [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.session :as session]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private long-text
  (str "Here is a deliberately long paragraph that will certainly need to be "
       "wrapped by the renderer because it runs well past any sane terminal "
       "width and keeps going for quite a while longer still."))

(defn- fake-fullscreen!
  "Fullscreen at `cols`, with a chat tab (0, active) and an output-only tab (1)."
  [cols]
  (reset! layout/!scrollback [])
  (reset! layout/!scrollback-src [])
  (reset! layout/!live-blocks {})
  (reset! layout/!layout
          {:mode :fullscreen :rows 40 :cols cols
           :scroll-bottom 35 :separator-row 36 :input-row 37
           :separator2-row 38 :tab-row 39 :status-row 40
           :viewport-offset 0 :input-height 1 :input-height-max 6
           :menu-height 0 :menu-height-hold 0
           :task-activity-height 0 :agent-activity-height 0
           :writer (java.io.PrintWriter. (java.io.StringWriter.))})
  (reset! sessions/!sessions
          {:active-idx 0
           :sessions {0 {:id 0 :label "main0"  :session-type :chat   :scrollback []}
                      1 {:id 1 :label "main0↓" :session-type :output
                         :sub-output-of 0 :scrollback []}}}))

(defn- reset-fixture [t]
  (let [layout' @layout/!layout, sb @layout/!scrollback
        src @layout/!scrollback-src, blocks @layout/!live-blocks
        sess @sessions/!sessions]
    (try (t)
         (finally
           (reset! layout/!layout layout')
           (reset! layout/!scrollback sb)
           (reset! layout/!scrollback-src src)
           (reset! layout/!live-blocks blocks)
           (reset! sessions/!sessions sess)))))

(use-fixtures :each reset-fixture)

(defn- resize! [cols]
  (with-redefs [fmt/refresh-terminal-size! (fn [] {:rows 40 :cols cols})
                fmt/terminal-rows          (fn [] 40)
                fmt/terminal-columns       (fn [] cols)]
    (layout/handle-resize!)))

(defn- switch-to! [idx]
  (#'sessions/save-current-session-state!)
  (swap! sessions/!sessions assoc :active-idx idx)
  (#'sessions/load-session-into-layout! (sessions/get-session idx)))

(defn- answer-emit [cols]
  [(fmt/format-answer long-text cols) {:render #(fmt/format-answer long-text %)}])

(defn- widths [] (mapv fmt/display-width @layout/!scrollback))

(defn- too-wide [n] (count (filter #(> % n) (widths))))

(defn- src-covers-rows?
  "The invariant `ensure-src!` checks: the source describes exactly the rows on
   screen. Break it and the whole tab silently stops reflowing."
  [session]
  (= (count (:scrollback session))
     (reduce + 0 (map :n (:scrollback-src session)))))

;; ---------------------------------------------------------------------------
;; The background emit keeps its renderer
;; ---------------------------------------------------------------------------

(deftest background-emit-records-how-to-re-render-itself
  (testing "a :render passed to a background session is stored, not dropped"
    (fake-fullscreen! 120)
    (let [[s opts] (answer-emit 120)]
      (sessions/emit-to-session! 1 s opts))
    (let [session (sessions/get-session 1)]
      (is (pos? (count (:scrollback session))) "the rows were buffered")
      (is (= 1 (count (:scrollback-src session)))
          "and one entry describes them")
      (is (every? (comp fn? :render) (:scrollback-src session))
          "carrying a real renderer, not a placeholder")
      (is (src-covers-rows? session)
          "source and rows stay in step — a drifted pair loses reflow wholesale"))))

(deftest an-output-tab-reflows-on-resize-after-a-switch
  (testing "output collected in the background re-wraps once the tab is shown"
    (fake-fullscreen! 120)
    (let [[s opts] (answer-emit 120)]
      (sessions/emit-to-session! 1 s opts))
    (switch-to! 1)
    (is (pos? (count @layout/!scrollback)) "the tab shows its rows")
    (resize! 60)
    (is (zero? (too-wide 60))
        "every row fits the narrower terminal — this is the bug: they used to
         stay at their emit-time width and be clipped forever")
    (is (str/includes? (str/join " " @layout/!scrollback) "deliberately")
        "re-wrapped, not truncated away")))

(deftest switching-to-a-tab-rewraps-it-to-the-current-width
  (testing "a resize that happened while the tab was away is applied on arrival"
    ;; handle-resize! only reflows the ACTIVE tab, so nothing else can fix this.
    (fake-fullscreen! 120)
    (let [[s opts] (answer-emit 120)]
      (sessions/emit-to-session! 1 s opts))
    (resize! 60)                                  ;; user is on the chat tab
    (is (pos? (count (filter #(> % 60)
                             (map fmt/display-width
                                  (:scrollback (sessions/get-session 1))))))
        "precondition: the buffered rows are still 120-column rows")
    (switch-to! 1)
    (is (zero? (too-wide 60))
        "arriving at the tab re-wraps what it loads")))

(deftest rows-with-no-source-are-sealed-rather-than-poisoning-the-tab
  (testing "uncovered rows stop reflowing; everything after them keeps it"
    ;; A replayed on-disk tail, or any path that predates the source list.
    (fake-fullscreen! 120)
    (swap! sessions/!sessions update-in [:sessions 1] assoc
           :scrollback ["a pre-existing row with no renderer"])
    (let [[s opts] (answer-emit 120)]
      (sessions/emit-to-session! 1 s opts))
    (let [session (sessions/get-session 1)]
      (is (src-covers-rows? session) "the seal restores the invariant")
      (is (= 2 (count (:scrollback-src session)))
          "one sealed entry for the orphan rows, one real entry for the emit"))
    (switch-to! 1)
    (resize! 60)
    (is (some #{"a pre-existing row with no renderer"} @layout/!scrollback)
        "the unreflowable row survives verbatim")
    (is (zero? (too-wide 60))
        "and the emit after it still re-wraps")))

;; ---------------------------------------------------------------------------
;; The sub-agent ask header — the long line that had nothing to reflow
;; ---------------------------------------------------------------------------

(deftest a-long-sub-agent-ask-wraps-and-reflows
  (testing "the ❯ prompt is wrapped at emit and re-wrapped on resize"
    ;; It used to be emitted raw: one row of arbitrary length, overflowing a
    ;; narrow pane at FIRST paint, with no renderer to fix it later.
    (fake-fullscreen! 120)
    (#'session/emit-sub-agent-ask-header! 1 :explore-agent long-text)
    (switch-to! 1)
    (is (zero? (too-wide 120)) "fits at the width it was emitted for")
    (resize! 60)
    (is (zero? (too-wide 60)) "and re-wraps to a narrower one")
    (resize! 100)
    (is (zero? (too-wide 100)) "and back out to a wider one")
    (is (str/includes? (str/join " " @layout/!scrollback) "explore-agent")
        "the separator label survives the reflow")))
