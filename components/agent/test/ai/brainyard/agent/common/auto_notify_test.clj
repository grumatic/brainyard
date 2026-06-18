;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.auto-notify-test
  "Unit tests for runtime-driven detached-task notification.

   Drives the resume decision directly via the `:task/completed` hook (no real
   task manager / watcher timing): `arm-auto-notify!` registers a one-shot
   completion watch, and firing `:task/completed` with a matching task simulates
   termination. The auto-ask routes through the real `agent/submit-turn`, so a
   capturing turn-submitter both (a) makes `interactive-host?` true and (b)
   records the injected resume turn."
  (:require [ai.brainyard.agent.common.auto-notify :as an]
            [ai.brainyard.agent.core.agent :as agent]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.task.manager :as manager]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(defn- clean-fixture [f]
  (an/reset-state!)
  (agent/register-turn-submitter! nil)
  (try (f)
       (finally
         (an/reset-state!)
         (agent/register-turn-submitter! nil)
         (manager/set-default-manager! nil)
         (reset! manager/!tasks {}))))

(use-fixtures :each clean-fixture)

(defn- mock-agent
  "A plain-map stand-in: has :!state (status + empty runtime so it reads as a
   root agent), a stable session/agent id, and a bt-st-memory atom for the
   deflect path."
  [& {:keys [status session] :or {status :idle session "s1"}}]
  {:!state       (atom {:status status :runtime {}})
   :agent-id     :test/root
   :session-id   session
   :bt-st-memory (atom {})})

(defn- capturing-submitter!
  "Register a turn-submitter that records calls into `captured`, returning it."
  []
  (let [captured (atom [])]
    (agent/register-turn-submitter!
     (fn [_agent input opts] (swap! captured conj {:input input :opts opts})))
    captured))

(defn- complete! [task-id status]
  (hooks/fire! :task/completed {:task {:id task-id :status status}}))

;; ---------------------------------------------------------------------------

(deftest headless-arm-is-noop
  (testing "with no turn-submitter registered (headless), arming does nothing"
    (let [ag (mock-agent)]
      (an/arm-auto-notify! ag :task-1)
      (is (not (an/armed? :task-1)))
      ;; A completion fires no resume because nothing was armed.
      (complete! :task-1 :completed)
      (is (not (an/armed? :task-1))))))

(deftest idle-completion-auto-resumes
  (testing "a backgrounded task completing while idle injects ONE :auto-resume turn"
    (let [captured (capturing-submitter!)
          resumed  (atom [])
          _        (hooks/register-hook! :agent.task/auto-resumed ::t
                                         (fn [ev] (swap! resumed conj ev))
                                         :source ::auto-notify-test)
          ag       (mock-agent :status :idle)]
      (an/arm-auto-notify! ag :task-7)
      (is (an/armed? :task-7))
      (complete! :task-7 :completed)
      (is (= 1 (count @captured)))
      (is (= :auto-resume (get-in (first @captured) [:opts :source])))
      (is (re-find #"task-7" (:input (first @captured))))
      (is (not (an/armed? :task-7)) "watch disarms after firing")
      (is (= 1 (count @resumed)) ":agent.task/auto-resumed observer fired once")
      (hooks/unregister-source! ::auto-notify-test))))

(deftest failure-status-noted-in-resume
  (testing "a non-success terminal status is surfaced in the resume text"
    (let [captured (capturing-submitter!)
          base     (manager/create-task-manager :pool-size 1)
          mgr      (manager/->TaskManager (:executors base))]
      (manager/set-default-manager! mgr)
      (reset! manager/!tasks {:task-2 {:id :task-2 :status :failed}})
      (let [ag (mock-agent :status :idle)]
        (an/arm-auto-notify! ag :task-2)
        (complete! :task-2 :failed)
        (is (= 1 (count @captured)))
        (is (re-find #"(?i)not.*success|fail" (:input (first @captured))))))))

(deftest running-completion-defers-until-turn-settle
  (testing "while a turn is running, completion does NOT resume; the turn-settle flush does"
    (let [captured (capturing-submitter!)
          ag       (mock-agent :status :running)]
      (an/arm-auto-notify! ag :task-9)
      (complete! :task-9 :completed)
      (is (zero? (count @captured)) "no resume while the agent is running")
      ;; Turn ends → :agent.ask/post flush calls maybe-resume!.
      (swap! (:!state ag) assoc :status :idle)
      (an/maybe-resume! ag)
      (is (= 1 (count @captured)) "flush after the turn settles resumes"))))

(deftest mark-surfaced-suppresses-redundant-resume
  (testing "a completion the live loop already surfaced (harvest) is not re-announced"
    (let [captured (capturing-submitter!)
          ag       (mock-agent :status :running)]
      (an/arm-auto-notify! ag :task-3)
      (complete! :task-3 :completed)            ;; lands in inbox (agent running)
      (an/mark-surfaced! ag [:task-3])          ;; live loop folded it in
      (swap! (:!state ag) assoc :status :idle)
      (an/maybe-resume! ag)
      (is (zero? (count @captured)) "already surfaced → no auto-ask"))))

(deftest budget-bounds-auto-resumes
  (testing "auto-resumes are bounded by the per-session budget"
    (let [captured (capturing-submitter!)
          ag       (mock-agent :status :idle)]
      ;; default-max-resumes is 20; drive past it.
      (dotimes [i 25]
        (let [tid (keyword (str "task-b" i))]
          (an/arm-auto-notify! ag tid)
          (complete! tid :completed)))
      (is (= 20 (count @captured)) "stops arming/resuming once budget is spent"))))

(deftest deflect-then-autopark
  (testing "polling a still-running armed task: poll 1 allowed, poll 2 parks the turn"
    (capturing-submitter!)                       ;; interactive host
    (let [base (manager/create-task-manager :pool-size 1)
          mgr  (manager/->TaskManager (:executors base))]
      (manager/set-default-manager! mgr)
      (reset! manager/!tasks {:task-1 {:id :task-1 :status :running}})
      (let [parked (atom [])
            _      (hooks/register-hook! :agent.iteration/auto-parked ::p
                                         (fn [ev] (swap! parked conj ev))
                                         :source ::auto-notify-test)
            ag     (mock-agent :status :running)
            event  {:agent ag :tool-name "task$detail" :args {:task-id "task-1"}}
            decide #(#'an/deflect-decision event)]
        (an/arm-auto-notify! ag :task-1)
        ;; :auto-park-after-polls defaults to 2.
        (is (= :allow (:result (decide))) "first poll passes through")
        (let [d (decide)]
          (is (= :block (:result d)) "second poll force-parks the turn")
          (is (string? (:answer d)) "parked answer terminates the loop"))
        (is (= 1 (count @parked)) ":agent.iteration/auto-parked fired once")
        (hooks/unregister-source! ::auto-notify-test)))))

(deftest deflect-allows-poll-of-unarmed-task
  (testing "polls of a task the runtime did not arm are never deflected"
    (capturing-submitter!)
    (let [ag    (mock-agent :status :running)
          event {:agent ag :tool-name "task$detail" :args {:task-id "task-x"}}]
      (is (= :allow (:result (#'an/deflect-decision event)))))))

(deftest deflect-allows-poll-of-terminal-armed-task
  (testing "once an armed task is terminal, a real poll is allowed (and the arm dropped)"
    (capturing-submitter!)
    (let [base (manager/create-task-manager :pool-size 1)
          mgr  (manager/->TaskManager (:executors base))]
      (manager/set-default-manager! mgr)
      (reset! manager/!tasks {:task-1 {:id :task-1 :status :completed}})
      (let [ag    (mock-agent :status :running)
            event {:agent ag :tool-name "task$detail" :args {:task-id "task-1"}}]
        ;; Seed the armed set directly (arm would also register a watch).
        (an/arm-auto-notify! ag :task-1)
        (is (= :allow (:result (#'an/deflect-decision event))))
        (is (not (an/armed? :task-1)) "terminal poll drops the arm")))))
