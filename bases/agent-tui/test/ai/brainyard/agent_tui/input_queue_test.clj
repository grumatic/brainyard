;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.input-queue-test
  "The TUI input queue is a single global serial queue, but each turn must run
   against the tab (root agent) it was typed in — NOT whatever tab is active
   when the queue gets around to processing it. `enqueue-input!` tags each item
   with the active agent at enqueue time; the queue's process-fn prefers that
   over `get-active-agent`. This guards the tag (and that an explicit `:agent`,
   e.g. a task$wakeup resume, is preserved)."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent-tui.core :as core]
            [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.agent.interface :as agent]))

(defn- capture-enqueue
  "Run `(enqueue-input! input opts)` with the active agent stubbed to
   `active-ag`, capturing the opts handed to `agent/enqueue!`."
  [active-ag input opts]
  (let [captured (atom :uncalled)
        saved-q  @core/!input-queues]
    (try
      ;; Seed the per-root queue map so ensure-input-queue-for-root! returns a
      ;; stub instead of creating a real queue/worker.
      (reset! core/!input-queues {:root-a :stub-queue})
      (with-redefs [sessions/get-active-session (constantly {:session-type :chat})
                    tui-session/get-active-agent (constantly active-ag)
                    tui-session/root-agent-id (constantly :root-a)
                    agent/register-turn-submitter! (fn [_] nil)
                    agent/enqueue! (fn [_q _input o] (reset! captured o) {})]
        (core/enqueue-input! input opts))
      @captured
      (finally (reset! core/!input-queues saved-q)))))

(deftest enqueue-tags-input-with-active-agent
  (testing "untagged keyboard input is tagged with the agent active at enqueue time"
    (let [opts (capture-enqueue :agent-a "hello" nil)]
      (is (= :agent-a (:agent opts))
          "the item carries the enqueue-time active agent, so a later tab switch can't misroute it"))))

(deftest enqueue-preserves-explicit-target-agent
  (testing "an explicit :agent (e.g. task$wakeup resume) is NOT overridden by the active agent"
    (let [opts (capture-enqueue :agent-a "resume"
                                {:agent :agent-b :source :wakeup})]
      (is (= :agent-b (:agent opts)) "the wakeup's target agent wins over the active agent")
      (is (= :wakeup (:source opts)) "other opts pass through"))))
