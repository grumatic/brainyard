;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.think-block-session-test
  "Regression: the think (spinner) live block is a global singleton pinned to
   the session of the agent whose turn is running. When the user switches to a
   different tab mid-turn, its disposal (turn end) and takeover (a different
   session's agent replacing the singleton) must reach the block's ORIGIN
   session — otherwise a stale spinner is left frozen in that session's saved
   scrollback. Mirrors the per-task / iteration block origin handling."
  (:require [ai.brainyard.agent-tui.session :as session]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.agent-tui.layout :as layout]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private bid :think-block)

(defn- reset-fixture [t]
  (let [saved-sessions @sessions/!sessions
        saved-sb       @layout/!scrollback
        saved-blocks   @layout/!live-blocks]
    (reset! layout/!scrollback [])
    (reset! layout/!live-blocks {})
    (try (t)
         (finally
           (reset! sessions/!sessions saved-sessions)
           (reset! layout/!scrollback saved-sb)
           (reset! layout/!live-blocks saved-blocks)))))

(use-fixtures :each reset-fixture)

(defn- two-sessions-with-think-in-origin! []
  ;; Origin session 0 (backgrounded) holds the think spinner; active is 1.
  (reset! sessions/!sessions
          {:active-idx 1
           :next-id    2
           :sessions   {0 {:id 0
                           :scrollback  ["think line 1" "think line 2"]
                           :live-blocks {bid {:start-idx 0 :line-count 2}}}
                        1 {:id 1 :scrollback [] :live-blocks {}}}}))

(deftest finalize-think-block-disposes-from-backgrounded-origin
  (testing "dispose routes to the origin session's saved scrollback, not the active tab"
    (two-sessions-with-think-in-origin!)
    (#'session/finalize-think-block-in-session! 0 true)
    (is (nil? (get-in @sessions/!sessions [:sessions 0 :live-blocks bid]))
        "think block removed from origin session's saved live-blocks")
    (is (empty? (:scrollback (sessions/get-session 0)))
        "spinner lines scrubbed from the origin session's scrollback")
    (is (empty? (:scrollback (sessions/get-session 1)))
        "the active session is untouched")))

(deftest finalize-think-block-freeze-keeps-lines-in-origin
  (testing "freeze drops the live entry but keeps the lines as origin history"
    (two-sessions-with-think-in-origin!)
    (#'session/finalize-think-block-in-session! 0 false)
    (is (nil? (get-in @sessions/!sessions [:sessions 0 :live-blocks bid]))
        "block entry removed from origin live-blocks (no longer live)")
    (is (= ["think line 1" "think line 2"] (:scrollback (sessions/get-session 0)))
        "frozen lines remain in the origin session's scrollback")))
