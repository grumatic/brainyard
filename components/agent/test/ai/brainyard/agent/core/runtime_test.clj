;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.runtime-test
  "Tests for the cooperative pause/resume primitive in the agent runtime:
   flag transitions, status save/restore, the park/wake handshake, and
   cancel-while-paused (cancel must win over a parked waiter)."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent.core.runtime :as runtime]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- fresh-state
  "An atom shaped like an agent state map: a :status plus a :runtime submap."
  ([] (fresh-state :running))
  ([status]
   (atom {:status status :runtime (runtime/create-runtime-state)})))

(defn- await-thread-state
  "Poll until `thread` reaches one of `states` (a set of Thread$State), or
   `timeout-ms` elapses. Returns true if reached. Used to confirm a waiter
   has actually parked on the condition before we resume/cancel it, so the
   test exercises the real wake path rather than a no-op fast return."
  [^Thread thread states timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (contains? states (.getState thread)) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(defn- park!
  "Spawn a thread that calls wait-if-paused and delivers its outcome to a
   promise. Returns [thread outcome-promise]."
  [!state]
  (let [outcome (promise)
        t (Thread. ^Runnable (fn [] (deliver outcome (runtime/wait-if-paused !state))))]
    (.start t)
    [t outcome]))

;; ============================================================================
;; Flag transitions + status save/restore
;; ============================================================================

(deftest pause-resume-flag-transitions-test
  (testing "paused? is false on a fresh state"
    (let [!s (fresh-state)]
      (is (false? (runtime/paused? !s)))))

  (testing "pause-run sets the flag and flips :status to :paused"
    (let [!s (fresh-state :running)]
      (runtime/pause-run !s)
      (is (true? (runtime/paused? !s)))
      (is (= :paused (:status @!s)))
      (is (= :running (get-in @!s [:runtime :pre-pause-status]))
          "previous status is saved for restore")))

  (testing "resume-run clears the flag and restores the pre-pause :status"
    (let [!s (fresh-state :running)]
      (runtime/pause-run !s)
      (runtime/resume-run !s)
      (is (false? (runtime/paused? !s)))
      (is (= :running (:status @!s)))
      (is (nil? (get-in @!s [:runtime :pre-pause-status]))
          "the saved status is cleaned up on resume")))

  (testing "a non-:running status is preserved across a pause/resume cycle"
    (let [!s (fresh-state :thinking)]
      (runtime/pause-run !s)
      (is (= :paused (:status @!s)))
      (runtime/resume-run !s)
      (is (= :thinking (:status @!s)))))

  (testing "resume-run with no prior pause defaults :status to :running"
    (let [!s (fresh-state :idle)]
      (runtime/resume-run !s)
      (is (= :running (:status @!s))))))

;; ============================================================================
;; wait-if-paused fast path
;; ============================================================================

(deftest wait-if-paused-not-paused-returns-running-test
  (testing "wait-if-paused returns :running immediately when not paused"
    (let [!s (fresh-state)]
      (is (= :running (runtime/wait-if-paused !s))))))

;; ============================================================================
;; Park / wake handshake
;; ============================================================================

(deftest wait-if-paused-parks-then-resumes-test
  (testing "a parked waiter wakes with :resumed when resume-run is called"
    (let [!s (fresh-state :running)]
      (runtime/pause-run !s)
      (let [[t outcome] (park! !s)]
        (is (await-thread-state t #{Thread$State/WAITING Thread$State/TIMED_WAITING} 2000)
            "waiter should park on the condition")
        ;; Still parked — no outcome yet.
        (is (= :pending (deref outcome 50 :pending)))
        (runtime/resume-run !s)
        (is (= :resumed (deref outcome 2000 :timed-out)))
        (.join t 1000)
        (is (not (.isAlive t)))))))

(deftest wait-if-paused-cancel-wins-over-pause-test
  (testing "cancel-run unblocks a parked waiter with :cancelled (Ctrl-C escapes a pause)"
    (let [!s (fresh-state :running)]
      (runtime/pause-run !s)
      (let [[t outcome] (park! !s)]
        (is (await-thread-state t #{Thread$State/WAITING Thread$State/TIMED_WAITING} 2000)
            "waiter should park on the condition")
        (runtime/cancel-run !s)
        (is (= :cancelled (deref outcome 2000 :timed-out)))
        (is (true? (runtime/cancelled? !s)))
        (.join t 1000)
        (is (not (.isAlive t)))))))

(deftest wait-if-paused-already-cancelled-returns-cancelled-test
  (testing "a waiter that is both paused and already cancelled returns :cancelled without hanging"
    (let [!s (fresh-state :running)]
      (runtime/pause-run !s)
      (runtime/cancel-run !s)
      (is (= :cancelled (runtime/wait-if-paused !s))))))

;; ============================================================================
;; Pause condition reuse
;; ============================================================================

(deftest pause-condition-reused-across-cycles-test
  (testing "the (lock, condition) pair is allocated once and reused"
    (let [!s (fresh-state :running)]
      (runtime/pause-run !s)
      (let [pair-1 (get-in @!s [:runtime :pause-condition])]
        (is (some? pair-1))
        (runtime/resume-run !s)
        (runtime/pause-run !s)
        (let [pair-2 (get-in @!s [:runtime :pause-condition])]
          (is (identical? pair-1 pair-2)
              "same pair object survives a pause/resume cycle")))))

  (testing "reset-runtime preserves the pause-condition object"
    (let [!s (fresh-state :running)]
      (runtime/pause-run !s)
      (let [pair (get-in @!s [:runtime :pause-condition])]
        (runtime/reset-runtime !s)
        (is (false? (get-in @!s [:runtime :paused?])))
        (is (identical? pair (get-in @!s [:runtime :pause-condition])))))))

;; ============================================================================
;; Parent-agent propagation
;; ============================================================================

(deftest paused-walks-parent-agents-test
  (testing "a child reports paused? when its parent is paused"
    (let [!parent (fresh-state :running)
          !child  (fresh-state :running)]
      ;; Wire the child's runtime to point at a parent-agent-shaped map.
      (swap! !child assoc-in [:runtime :parent-agent] {:!state !parent})
      (is (false? (runtime/paused? !child)))
      (runtime/pause-run !parent)
      (is (true? (runtime/paused? !child))
          "pausing the parent pauses the child")))

  (testing "cancelled? also walks to the parent"
    (let [!parent (fresh-state :running)
          !child  (fresh-state :running)]
      (swap! !child assoc-in [:runtime :parent-agent] {:!state !parent})
      (is (false? (runtime/cancelled? !child)))
      (runtime/cancel-run !parent)
      (is (true? (runtime/cancelled? !child))))))
