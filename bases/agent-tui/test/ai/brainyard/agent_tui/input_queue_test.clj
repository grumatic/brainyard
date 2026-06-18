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

(defn- run-enqueue
  "Run `(enqueue-input! input opts)` under stubs; return the opts handed to
   `agent/enqueue!`, or `:uncalled` when no enqueue happened (rejected/skipped).
   Override map keys: :active-ag, :input, :opts, :session-type (default :chat),
   :root-aid (default :root-a; the stubbed result of root-agent-id)."
  [{:keys [active-ag input opts session-type root-aid]
    :or {session-type :chat root-aid :root-a}}]
  (let [captured (atom :uncalled)
        saved-q  @core/!input-queues]
    (try
      ;; Seed the per-root queue map so ensure-input-queue-for-root! returns a
      ;; stub instead of creating a real queue/worker.
      (reset! core/!input-queues {root-aid :stub-queue})
      (with-redefs [sessions/get-active-session (constantly {:session-type session-type})
                    tui-session/get-active-agent (constantly active-ag)
                    tui-session/root-agent-id (constantly root-aid)
                    agent/register-turn-submitter! (fn [_] nil)
                    agent/enqueue! (fn [_q _input o] (reset! captured o) {})]
        (core/enqueue-input! input opts))
      @captured
      (finally (reset! core/!input-queues saved-q)))))

(deftest enqueue-tags-input-with-active-agent
  (testing "untagged keyboard input is tagged with the agent active at enqueue time"
    (let [opts (run-enqueue {:active-ag :agent-a :input "hello"})]
      (is (= :agent-a (:agent opts))
          "the item carries the enqueue-time active agent, so a later tab switch can't misroute it"))))

(deftest enqueue-preserves-explicit-target-agent
  (testing "an explicit :agent (e.g. task$wakeup resume) is NOT overridden by the active agent"
    (let [opts (run-enqueue {:active-ag :agent-a :input "resume"
                             :opts {:agent :agent-b :source :wakeup}})]
      (is (= :agent-b (:agent opts)) "the wakeup's target agent wins over the active agent")
      (is (= :wakeup (:source opts)) "other opts pass through"))))

(deftest normal-input-rejected-on-output-session
  (testing "keyboard input into an output-only tab is NOT enqueued (read-only)"
    (let [r (run-enqueue {:active-ag :agent-a :input "hi" :session-type :output})]
      (is (= :uncalled r) "no enqueue — the output tab has no root agent / queue"))))

(deftest targeted-resume-not-blocked-by-active-output-session
  (testing "a task$wakeup resume proceeds even when the active tab is output-only"
    ;; The resume targets a background agent (root :root-b), not the active tab.
    (let [opts (run-enqueue {:active-ag :agent-a :input "resume"
                             :opts {:agent :agent-b :source :wakeup}
                             :session-type :output :root-aid :root-b})]
      (is (not= :uncalled opts) "the targeted resume was enqueued despite the output tab")
      (is (= :agent-b (:agent opts))))))

(deftest enqueue-skipped-when-no-root-agent
  (testing "a resolvable agent with no root (nil root-aid) is skipped, not nil-keyed"
    (let [r (run-enqueue {:active-ag :agent-a :input "hi" :root-aid nil})]
      (is (= :uncalled r) "no enqueue — nothing to run against, no bogus nil queue"))))
