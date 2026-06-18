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

;; Think blocks are keyed per root agent: id = (think-block-id root-aid).
(def ^:private root-aid :coact-agent/root-x)
(def ^:private bid (keyword "think-block" "root-x"))

(def ^:private !think-blocks @#'session/!think-blocks)

(defn- reset-fixture [t]
  (let [saved-sessions @sessions/!sessions
        saved-think    @!think-blocks
        saved-sb       @layout/!scrollback
        saved-blocks   @layout/!live-blocks]
    (reset! layout/!scrollback [])
    (reset! layout/!live-blocks {})
    (reset! !think-blocks {})
    (try (t)
         (finally
           (reset! sessions/!sessions saved-sessions)
           (reset! !think-blocks saved-think)
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
    (#'session/finalize-think-block-in-session! root-aid 0 true)
    (is (nil? (get-in @sessions/!sessions [:sessions 0 :live-blocks bid]))
        "think block removed from origin session's saved live-blocks")
    (is (empty? (:scrollback (sessions/get-session 0)))
        "spinner lines scrubbed from the origin session's scrollback")
    (is (empty? (:scrollback (sessions/get-session 1)))
        "the active session is untouched")))

(deftest finalize-think-block-freeze-keeps-lines-in-origin
  (testing "freeze drops the live entry but keeps the lines as origin history"
    (two-sessions-with-think-in-origin!)
    (#'session/finalize-think-block-in-session! root-aid 0 false)
    (is (nil? (get-in @sessions/!sessions [:sessions 0 :live-blocks bid]))
        "block entry removed from origin live-blocks (no longer live)")
    (is (= ["think line 1" "think line 2"] (:scrollback (sessions/get-session 0)))
        "frozen lines remain in the origin session's scrollback")))

(deftest think-block-id-is-per-root
  (testing "each root agent gets a distinct think-block id so concurrent tabs don't collide"
    (is (= :think-block/maroon-lion-800
           (#'session/think-block-id :coact-agent/maroon-lion-800)))
    (is (not= (#'session/think-block-id :coact-agent/maroon-lion-800)
              (#'session/think-block-id :coact-agent/scarlet-toad-3072))
        "two live root agents map to different live-block ids")))

(deftest reattach-recreates-think-block-at-sticky-bottom
  (testing "a stale think block (not at the bottom) is moved to the sticky bottom on switch-back"
    (let [tbid (#'session/think-block-id root-aid)]
      ;; Active session is 0 so update-think-block! renders to the layout.
      (reset! sessions/!sessions {:active-idx 0 :next-id 1 :sessions {0 {:id 0}}})
      ;; Layout: an iteration block, then a STALE think block in the middle,
      ;; then a task block — i.e. the spinner is NOT at the bottom (as happens
      ;; when task/iter blocks land below it while the tab was backgrounded).
      (reset! layout/!scrollback ["iter" "stale-think" "task"])
      (reset! layout/!live-blocks
              {:iter {:start-idx 0 :line-count 1}
               tbid  {:start-idx 1 :line-count 1}
               :task {:start-idx 2 :line-count 1}})
      ;; A live think entry for this root in session 0 (agent still running).
      (reset! !think-blocks
              {root-aid {:session-idx 0 :shuffled-words ["Thinking"]
                         :spinner-idx (volatile! 0)
                         :start-time (System/currentTimeMillis) :activity nil}})
      (#'session/reattach-think-block-for-session! 0)
      (let [tb     (get @layout/!live-blocks tbid)
            others (->> (dissoc @layout/!live-blocks tbid) vals (map :start-idx))]
        (is (some? tb) "think block present after reattach")
        (is (every? #(< % (:start-idx tb)) others)
            "think block sits below every other live block (sticky bottom)")))))

(deftest detach-reattach-preserves-elapsed-start-time
  (testing "detach/reattach keep the entry's :start-time so the elapsed timer is continuous"
    (let [t0 (- (System/currentTimeMillis) 5000)] ;; started 5s ago
      (reset! sessions/!sessions {:active-idx 0 :next-id 1 :sessions {0 {:id 0}}})
      (reset! !think-blocks
              {root-aid {:session-idx 0 :shuffled-words ["Thinking"]
                         :spinner-idx (volatile! 0) :start-time t0 :activity nil}})
      (#'session/detach-think-block-for-session! 0)
      (#'session/reattach-think-block-for-session! 0)
      (is (= t0 (:start-time (get @!think-blocks root-aid)))
          ":start-time is preserved (detach/reattach only touch the layout block, not the entry)")
      ;; Elapsed is wall-clock from the preserved start-time → the rendered
      ;; spinner reflects the ~5s that have passed, not a reset to 0.
      (let [{:keys [shuffled-words spinner-idx start-time activity]}
            (get @!think-blocks root-aid)
            line (first (#'session/render-think-block-lines
                         shuffled-words @spinner-idx "⠋" start-time
                         (#'session/fresh-activity-text activity)))]
        (is (re-find #"5\.\ds" line)
            "elapsed shows ~5s (wall-clock from the preserved start-time), not 0")))))
