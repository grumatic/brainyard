;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.task-block-session-test
  "Regression tests: a per-task live block must reach its final settled state
   in its ORIGIN session even when the user has switched the active session to
   a different tab (e.g. a shared sub-output session) while the task runs.

   Bug (pre-fix): `update-task-block!` was gated on `(= origin-idx
   (active-idx))` and `finalize-task-block!` ignored the origin session,
   writing only to the foreground layout. So a task that finished while its
   origin session was backgrounded left a stale `running` block frozen in that
   session's saved scrollback — visible on switch-back. The per-task block now
   routes through the same session-aware helpers the subagents block uses."
  (:require [ai.brainyard.agent-tui.session :as session]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.agent-tui.layout :as layout]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private bid (keyword "task-block" "t1"))

(def ^:private !task-owner-session @#'session/!task-owner-session)

(defn- reset-state-fixture [t]
  (let [saved-sessions @sessions/!sessions
        saved-tasks    @session/!task-blocks
        saved-owner    @!task-owner-session
        saved-sb       @layout/!scrollback
        saved-blocks   @layout/!live-blocks]
    (reset! layout/!scrollback [])
    (reset! layout/!live-blocks {})
    (reset! !task-owner-session {})
    (try (t)
         (finally
           (#'session/stop-task-block-ticker!)
           (reset! sessions/!sessions saved-sessions)
           (reset! session/!task-blocks saved-tasks)
           (reset! !task-owner-session saved-owner)
           (reset! layout/!scrollback saved-sb)
           (reset! layout/!live-blocks saved-blocks)))))

(use-fixtures :each reset-state-fixture)

(defn- two-sessions!
  "Origin chat session at idx 0 (backgrounded) already holds the task's
   `running` live block in its saved scrollback; active session is idx 1."
  []
  (reset! sessions/!sessions
          {:active-idx 1
           :next-id    2
           :sessions   {0 {:id          0
                           :scrollback  ["t1 - running"]
                           :live-blocks {bid {:start-idx 0 :line-count 1}}}
                        1 {:id 1 :scrollback [] :live-blocks {}}}}))

(defn- origin-scrollback [] (:scrollback (sessions/get-session 0)))

(deftest task-block-update-reaches-backgrounded-origin-session
  (testing "completed status is patched into the backgrounded origin session"
    (two-sessions!)
    (reset! session/!task-blocks
            {:t1 {:status       :completed
                  :session-idx  0
                  :start-time   (System/currentTimeMillis)
                  :job-type     :tool
                  :output-lines []
                  :output-count 0}})
    (#'session/update-task-block! :t1)
    (let [sb (origin-scrollback)]
      (is (some #(str/includes? % "done") sb)
          "origin session's saved scrollback shows final 'done' status")
      (is (not-any? #(str/includes? % "running") sb)
          "the stale 'running' line was replaced, not left behind"))
    (is (empty? (:scrollback (sessions/get-session 1)))
        "the active (foreground) session is untouched")))

(deftest task-block-finalize-targets-origin-session
  (testing "finalize removes the block from the backgrounded origin session"
    (two-sessions!)
    (#'session/finalize-task-block! bid 0)
    (is (nil? (get-in @sessions/!sessions [:sessions 0 :live-blocks bid]))
        "finalize cleared the task block from the origin session's saved state")))

(deftest task-block-without-origin-idx-falls-back-to-layout
  (testing "a task block with no :session-idx renders to the foreground layout"
    (two-sessions!)
    (reset! session/!task-blocks
            {:t1 {:status       :running
                  :session-idx  nil
                  :start-time   (System/currentTimeMillis)
                  :job-type     :tool
                  :output-lines []
                  :output-count 0}})
    (#'session/update-task-block! :t1)
    (is (contains? @layout/!live-blocks bid)
        "nil origin-idx routes to layout/update-live-block! (foreground)")))

;; ---------------------------------------------------------------------------
;; Origin = the OWNING AGENT's session, not whatever session is active when the
;; task block is created. (Bug: create-task-block! captured (sessions/active-idx),
;; so a task spawned by a background/sub- agent — or while the user switched tabs
;; mid-turn — anchored to the viewer's session and settled in the wrong place.)
;; ---------------------------------------------------------------------------

(deftest session-idx-for-agent-session-id-matches-by-agent-session-id
  (testing "resolves the TUI session whose :agent-session-id matches"
    (reset! sessions/!sessions
            {:active-idx 1
             :next-id    2
             :sessions   {0 {:id 0 :agent-session-id "agt-main"}
                          1 {:id 1 :agent-session-id "agt-other"}}})
    (is (= 0 (#'session/session-idx-for-agent-session-id "agt-main")))
    (is (= 1 (#'session/session-idx-for-agent-session-id "agt-other")))
    (is (nil? (#'session/session-idx-for-agent-session-id "agt-unknown")))
    (is (nil? (#'session/session-idx-for-agent-session-id nil)))))

(deftest create-task-block-anchors-to-owner-session-not-active
  (testing "the block's :session-idx is the captured owner, not the active session"
    ;; Owning agent's session is idx 0 (backgrounded); the user is viewing idx 1.
    (reset! sessions/!sessions
            {:active-idx 1
             :next-id    2
             :sessions   {0 {:id 0 :agent-session-id "agt-main"
                             :scrollback [] :live-blocks {}}
                          1 {:id 1 :agent-session-id "agt-other"
                             :scrollback [] :live-blocks {}}}})
    ;; task-created-handler recorded the owner at :task/created time.
    (reset! !task-owner-session {:t1 0})
    (#'session/create-task-block! :t1 {:name "skill$x" :job-type :tool})
    (is (= 0 (:session-idx (get @session/!task-blocks :t1)))
        "block anchored to the owning agent's session (0), not the active one (1)")))

(deftest create-task-block-falls-back-to-active-when-owner-unresolved
  (testing "no owner entry → block anchors to the active session"
    (reset! sessions/!sessions
            {:active-idx 1
             :next-id    2
             :sessions   {0 {:id 0 :scrollback [] :live-blocks {}}
                          1 {:id 1 :scrollback [] :live-blocks {}}}})
    (reset! !task-owner-session {})
    (#'session/create-task-block! :t1 {:name "skill$x" :job-type :tool})
    (is (= 1 (:session-idx (get @session/!task-blocks :t1)))
        "unresolved owner falls back to the active session (1)")))

(deftest create-task-block-prefers-metadata-owner-session-id
  (testing ":owner-session-id in task metadata wins over the bridge and the active session"
    ;; This is the reliable path for fast-eval/detach tool tasks (e.g. a slow
    ;; skill$… call): the owning agent's session-id is stamped into metadata at
    ;; creation because *current-agent* is NOT bound on the adopting BT thread.
    (reset! sessions/!sessions
            {:active-idx 1
             :next-id    2
             :sessions   {0 {:id 0 :agent-session-id "agt-main"
                             :scrollback [] :live-blocks {}}
                          1 {:id 1 :agent-session-id "agt-other"
                             :scrollback [] :live-blocks {}}}})
    ;; Bridge deliberately points at the (wrong) active session — metadata must win.
    (reset! !task-owner-session {:t1 1})
    (#'session/create-task-block!
     :t1 {:name "skill$heka-finops" :job-type :tool
          :metadata {:owner-session-id "agt-main"}})
    (is (= 0 (:session-idx (get @session/!task-blocks :t1)))
        "metadata owner (agt-main → session 0) wins over bridge (1) and active (1)")))

;; ---------------------------------------------------------------------------
;; A detached subagent dispatch (an :type :agent tool that timed out into a
;; task) must NOT get its own per-task live block — the consolidated subagents
;; block already surfaces it. `adopt-tool-into-task` stamps such tasks with
;; `:coact/subagent-id`; `subagent-task?` keys the suppression off that marker.
;; ---------------------------------------------------------------------------

(deftest subagent-task?-detects-coact-subagent-id
  (testing "a task carrying :coact/subagent-id is a subagent dispatch"
    (is (#'session/subagent-task?
         {:job-type :tool :metadata {:coact/subagent-id "research-agent/abc"}})))
  (testing "ordinary tool / bash tasks are not"
    (is (not (#'session/subagent-task?
              {:job-type :tool :metadata {:display-mode :foreground}})))
    (is (not (#'session/subagent-task? {:job-type :bash :metadata {}})))
    (is (not (#'session/subagent-task? {})))))

(defn- one-session! []
  (reset! sessions/!sessions
          {:active-idx 0 :next-id 1
           :sessions   {0 {:id 0 :scrollback [] :live-blocks {}}}}))

(deftest handle-tasks-change-suppresses-subagent-task-block
  (testing "a :pending→:running subagent task (carries :coact/subagent-id) creates NO block"
    (one-session!)
    (reset! session/!task-blocks {})
    (#'session/handle-tasks-change
     nil nil
     {:sub {:status :pending}}
     {:sub {:status :running :name "tool: research-agent" :job-type :tool
            :started-at (System/currentTimeMillis)
            :metadata {:display-mode :foreground
                       :coact/subagent-id "research-agent/abc"}}})
    (is (not (contains? @session/!task-blocks :sub))
        "subagent dispatch is covered by the subagents block — no redundant task block"))
  (testing "a normal :tool task still gets its per-task block"
    (one-session!)
    (reset! session/!task-blocks {})
    (#'session/handle-tasks-change
     nil nil
     {:t2 {:status :pending}}
     {:t2 {:status :running :name "bash: ls" :job-type :bash
           :started-at (System/currentTimeMillis)
           :metadata {:display-mode :foreground}}})
    (is (contains? @session/!task-blocks :t2)
        "a non-subagent task is unaffected — block created as before")))
