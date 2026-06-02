;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.task.commands-test
  "Tests for the sync waiter's `:on-timeout` kwarg. `:kill` (existing behavior)
   cancels the task on timeout and returns :status \"timeout\". `:detach`
   (the default) leaves the task running and returns :status \"pending\" so a
   later iteration can harvest the terminal result via task$detail."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.task.commands :as cmds]
            [ai.brainyard.agent.task.manager :as manager]
            [ai.brainyard.agent.task.protocol :as tp]))

(def ^:private await-task #'cmds/await-task)

(defn- reset-globals! []
  (when-let [mgr (manager/peek-default-manager)]
    (try (tp/shutdown mgr) (catch Exception _)))
  (manager/set-default-manager! nil))

(use-fixtures :each
  (fn [t] (reset-globals!) (t) (reset-globals!)))

(defn- start-bash [command timeout-ms]
  (let [m (manager/get-default-manager)
        task (tp/create-task m (str "test: " command) :bash
                             {:command command :timeout-ms timeout-ms})]
    (tp/start-task m (:id task))
    [m task]))

(defn- wait-for [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [v (pred)]
        (cond
          v v
          (> (System/currentTimeMillis) deadline) nil
          :else (do (Thread/sleep 25) (recur)))))))

;; ============================================================================
;; await-task :on-timeout
;; ============================================================================

(deftest on-timeout-kill-cancels-and-returns-timeout
  (testing ":on-timeout :kill → cancel on sync deadline, return :status \"timeout\""
    (let [[m task] (start-bash "sleep 5" 10000)
          r (await-task m (:id task) 300 :on-timeout :kill)]
      (is (= "timeout" (:status r)))
      (is (= 300 (:timeout-ms r)))
      (is (= (clojure.core/name (:id task)) (:task-id r)))
      ;; Task is terminal (cancelled or failed via interrupt).
      (let [final (tp/get-task m (:id task))]
        (is (#{:cancelled :failed} (:status final))
            (str "expected terminal status, got " (:status final)))))))

(deftest on-timeout-detach-leaves-task-running
  (testing ":on-timeout :detach → don't cancel, return :status \"pending\"; task keeps running"
    (let [[m task] (start-bash "sleep 1 ; echo done" 10000)
          r (await-task m (:id task) 200 :on-timeout :detach)]
      (is (= "pending" (:status r)))
      (is (= 200 (:timeout-ms r)))
      (is (= (clojure.core/name (:id task)) (:task-id r)))
      ;; Task is still :running in the manager (executor wasn't cancelled).
      (let [live (tp/get-task m (:id task))]
        (is (= :running (:status live))
            "task should still be :running after a :detach timeout"))
      ;; Executor still finishes on its own.
      (is (wait-for #(= :completed (:status (tp/get-task m (:id task)))) 5000)
          "task should eventually transition to :completed via the executor")
      (let [final (tp/get-task m (:id task))]
        (is (= :completed (:status final)))
        (is (= 0 (:exit-code (:result final))))))))

(deftest detach-is-the-default
  (testing "omitting :on-timeout defaults to :detach (unified code-eval contract)"
    (let [[m task] (start-bash "sleep 1 ; echo done" 10000)
          r (await-task m (:id task) 200)]
      (is (= "pending" (:status r))
          "default behavior should be :detach (return :pending, not cancel)")
      (is (= :running (:status (tp/get-task m (:id task)))))
      (is (wait-for #(= :completed (:status (tp/get-task m (:id task)))) 5000)))))

(deftest detach-snapshot-includes-output-so-far
  (testing ":detach result carries the output captured before the deadline"
    (let [[m task] (start-bash "echo first ; sleep 1 ; echo second" 10000)
          ;; Give the first echo time to land in output-lines before the deadline.
          _ (Thread/sleep 200)
          r (await-task m (:id task) 100 :on-timeout :detach)]
      (is (= "pending" (:status r)))
      (is (re-find #"first" (:output r))
          (str "expected 'first' in :detach output snapshot, got: "
               (pr-str (:output r))))
      ;; Let the task finish so we don't leak it across tests.
      (wait-for #(= :completed (:status (tp/get-task m (:id task)))) 5000))))

(deftest sync-completion-returns-terminal-snapshot
  (testing "task that finishes within the sync deadline returns its terminal snapshot — no timeout branch hit"
    (let [[m task] (start-bash "echo quick" 5000)
          r (await-task m (:id task) 5000 :on-timeout :detach)]
      (is (= "completed" (:status r)))
      (is (= 0 (:exit-code r)))
      (is (re-find #"quick" (:output r))))))

;; ============================================================================
;; task$run :sync / :on-timeout inputs
;; ============================================================================

(deftest task-run-sync-default-is-detach
  (testing "task$run :sync true with no :on-timeout flag defaults to detach per the unified contract"
    (let [m (manager/get-default-manager)
          r (cmds/task$run :job-type "bash"
                           :command "sleep 1 ; echo done"
                           :sync true
                           :timeout "200")]
      (is (= "pending" (:status r))
          "default task$run sync behavior is :detach")
      (is (some? (:task-id r)))
      (let [tid (keyword (:task-id r))]
        (is (= :running (:status (tp/get-task m tid)))
            "task should still be :running after task$run returns :pending")
        (is (wait-for #(= :completed (:status (tp/get-task m tid))) 5000))))))

(deftest task-run-sync-on-timeout-kill
  (testing "task$run :on-timeout \"kill\" → cancel on deadline, return :status \"timeout\""
    (let [m (manager/get-default-manager)
          r (cmds/task$run :job-type "bash"
                           :command "sleep 5"
                           :sync true
                           :timeout "200"
                           :on-timeout "kill")]
      (is (= "timeout" (:status r)))
      (is (= 200 (:timeout-ms r)))
      (let [final (tp/get-task m (keyword (:task-id r)))]
        (is (#{:cancelled :failed} (:status final)))))))

(deftest task-run-sync-completed-snapshot-still-works
  (testing "task$run :sync true with a fast command — terminal path unchanged"
    (let [r (cmds/task$run :job-type "bash"
                           :command "echo zippy"
                           :sync true
                           :timeout "5000")]
      (is (= "completed" (:status r)))
      (is (= 0 (:exit-code r)))
      (is (re-find #"zippy" (:output r))))))

(deftest task-run-async-mode-unchanged
  (testing "task$run :sync false (async) ignores :on-timeout, returns immediately"
    (let [r (cmds/task$run :job-type "bash"
                           :command "sleep 1"
                           ;; :sync omitted (defaults false → async)
                           :on-timeout "kill")]
      (is (some? (:result r)))
      (is (= "running" (get-in r [:result :status])))
      (is (some? (get-in r [:result :task-id]))))))
