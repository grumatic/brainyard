;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.iteration-block-session-test
  "Regression test: an iteration block's status transitions must reach its
   ORIGIN session even when the user switched the active session to a different
   tab (e.g. a sub-agent's output session) mid-iteration.

   Bug (pre-fix): `update-iteration-block!` was gated on `(= origin-idx
   (active-idx))` and rendered only to the foreground surface. A tool entry
   flipping `:called → :done` while the origin session was backgrounded updated
   `!iteration-blocks` but the render was dropped. `iteration-post-handler`'s
   `already-buffered?` guard then trusted the stale pre-switch snapshot and
   froze the block with a `called` tool line that never settled to `done`.
   The fix routes the background render through
   `sessions/update-live-block-in-session!`."
  (:require [ai.brainyard.agent-tui.session :as session]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.agent-tui.layout :as layout]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private aid :main)
(def ^:private rid "_")
(def ^:private iter 1)
(def ^:private block-id (keyword "iter-block" "main:_:1"))

(defn- reset-state-fixture [t]
  (let [saved-sessions @sessions/!sessions
        saved-iters    @session/!iteration-blocks
        saved-sb       @layout/!scrollback
        saved-blocks   @layout/!live-blocks]
    (reset! layout/!scrollback [])
    (reset! layout/!live-blocks {})
    (try (t)
         (finally
           (reset! sessions/!sessions saved-sessions)
           (reset! session/!iteration-blocks saved-iters)
           (reset! layout/!scrollback saved-sb)
           (reset! layout/!live-blocks saved-blocks)))))

(use-fixtures :each reset-state-fixture)

(deftest tool-status-done-reaches-backgrounded-origin-session
  (testing "a :called→:done tool transition lands in the backgrounded origin session"
    ;; Iteration originated in session 0 (now backgrounded); active is session 1.
    ;; Session 0 already holds the `called` snapshot rendered while it was active.
    (reset! sessions/!sessions
            {:active-idx 1
             :next-id    2
             :sessions   {0 {:id          0
                             :scrollback  ["  -> skill$x(): called"]
                             :live-blocks {block-id {:start-idx 0 :line-count 1}}}
                          1 {:id 1 :scrollback [] :live-blocks {}}}})
    ;; State already advanced to :done (as tool-use-post-handler's swap! does,
    ;; regardless of which session is active).
    (reset! session/!iteration-blocks
            {[aid rid iter]
             {:agent-id       aid
              :repeat-id      rid
              :iteration      iter
              :max-iterations 100
              :stage          :tools
              :tool-batch     [{:call-id      "c1"
                                :name         "skill$x"
                                :args         {}
                                :status       :done
                                :start-ms     1000
                                :end-ms       2000
                                :result-chars 50}]
              :session-idx    0
              :start-ms       1000}})
    (#'session/update-iteration-block! aid rid iter)
    (let [sb (:scrollback (sessions/get-session 0))]
      (is (some #(str/includes? % "done") sb)
          "origin session's saved scrollback shows the tool settled to 'done'")
      (is (empty? (:scrollback (sessions/get-session 1)))
          "the active (foreground) session is untouched"))))
