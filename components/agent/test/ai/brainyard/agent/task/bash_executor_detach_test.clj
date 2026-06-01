;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.task.bash-executor-detach-test
  "Tests for Step E of the unified-code-eval refactor: BashJobExecutor
   returns the Step A :detached outcome when its :timeout-ms expires before
   the bash process exits. The proc + stdout reader stay alive; the
   manager's watcher promotes the task when proc finally exits naturally,
   and cancel-task drives :on-cancel which kills the proc tree.

   Sync paths (proc exits within :timeout-ms) are covered by the existing
   bash-executor-*-test and task-manager-*-test in task_test.clj — Step E
   preserves those."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.task.manager :as manager]
            [ai.brainyard.agent.task.protocol :as tp]))

(defn- reset-globals! []
  (when-let [mgr (manager/peek-default-manager)]
    (try (tp/shutdown mgr) (catch Exception _)))
  (manager/set-default-manager! nil))

(use-fixtures :each
  (fn [t] (reset-globals!) (t) (reset-globals!)))

(defn- wait-for [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [v (pred)]
        (cond
          v v
          (> (System/currentTimeMillis) deadline) nil
          :else (do (Thread/sleep 25) (recur)))))))

(defn- start-bash [command & {:keys [timeout-ms] :or {timeout-ms 200}}]
  (let [m (manager/get-default-manager)
        t (tp/create-task m (str "test: " command) :bash
                          {:command command :timeout-ms timeout-ms})]
    (tp/start-task m (:id t))
    [m t]))

;; ============================================================================
;; Detach + watcher-promotion path
;; ============================================================================

(deftest long-bash-detaches-then-promotes
  (testing "bash that outlives :timeout-ms returns :detached; watcher promotes when proc exits naturally"
    (let [;; 1s sleep, executor :timeout-ms 200 → detaches after ~200ms,
          ;; proc completes at ~1s, watcher (250ms tick) promotes shortly after.
          [m task] (start-bash "sleep 1 ; echo done" :timeout-ms 200)]
      (is (wait-for #(get-in (tp/get-task m (:id task)) [:metadata :detached?]) 2000)
          "task should flip to :detached? once pool thread returns the detached outcome")
      (let [mid (tp/get-task m (:id task))]
        (is (= :running (:status mid))
            "task stays :running during detach (executor pool thread is free)")
        (is (true? (get-in mid [:metadata :detached?]))))
      (is (wait-for #(= :completed (:status (tp/get-task m (:id task)))) 5000)
          "watcher should promote to :completed once proc exits")
      (let [final (tp/get-task m (:id task))]
        (is (= :completed (:status final)))
        (is (= 0 (get-in final [:result :exit-code])))
        (is (some #(re-find #"done" %) @(:output-lines final))
            "post-detach output ('done') should land in output-lines via the still-alive reader future")))))

(deftest nonzero-exit-after-detach-promotes-to-failed
  (testing "detached proc that eventually exits non-zero promotes to :failed"
    (let [[m task] (start-bash "sleep 1 ; exit 7" :timeout-ms 200)]
      (is (wait-for #(get-in (tp/get-task m (:id task)) [:metadata :detached?]) 2000))
      (is (wait-for #(= :failed (:status (tp/get-task m (:id task)))) 5000))
      (let [final (tp/get-task m (:id task))]
        (is (= 7 (get-in final [:result :exit-code])))))))

(deftest cancel-while-detached-kills-proc
  (testing "cancel-task on a detached bash drives :on-cancel — proc + reader die, task → :cancelled"
    (let [[m task] (start-bash "sleep 30" :timeout-ms 200)]
      (is (wait-for #(get-in (tp/get-task m (:id task)) [:metadata :detached?]) 2000))
      (tp/cancel-task m (:id task))
      (let [final (tp/get-task m (:id task))]
        (is (= :cancelled (:status final)))
        (is (= {:error "cancelled"} (:result final)))))))

(deftest detached-bash-keeps-streaming-output
  (testing "post-detach stdout is captured by the still-alive reader and lands in output-lines"
    (let [;; Emit 3 lines spaced 250ms apart, total ~750ms. Detach at 100ms.
          [m task] (start-bash "for i in 1 2 3; do echo line-$i; sleep 0.25; done"
                               :timeout-ms 100)]
      (is (wait-for #(get-in (tp/get-task m (:id task)) [:metadata :detached?]) 1000))
      ;; Mid-flight: at least line-1 should be in output-lines via streaming.
      (is (wait-for #(some (fn [s] (re-find #"line-1" s)) @(:output-lines (tp/get-task m (:id task))))
                    2000)
          "streaming output should arrive after detach via the still-alive reader")
      ;; Eventually completes with all three lines.
      (is (wait-for #(= :completed (:status (tp/get-task m (:id task)))) 5000))
      (let [final (tp/get-task m (:id task))
            lines @(:output-lines final)]
        (is (some #(re-find #"line-1" %) lines))
        (is (some #(re-find #"line-2" %) lines))
        (is (some #(re-find #"line-3" %) lines))))))
