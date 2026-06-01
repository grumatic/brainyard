;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.task-test
  "Unit tests for task management system. No LLM needed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.agent.task.manager :as manager]
            [ai.brainyard.agent.task.persist :as persist]
            [ai.brainyard.agent.task.executor :as executor]
            [ai.brainyard.agent.task.commands :as cmds]
            [ai.brainyard.agent.task.format :as task-fmt]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.common.tools :as ctools]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defn reset-task-state [f]
  (reset! manager/!tasks {})
  (reset! manager/!task-counter 0)
  (f)
  ;; Cleanup
  (when-let [mgr (manager/get-default-manager)]
    (try (tp/shutdown mgr) (catch Exception _)))
  (manager/set-default-manager! nil)
  (reset! manager/!tasks {})
  (reset! manager/!task-counter 0))

(use-fixtures :each reset-task-state)

;; ============================================================================
;; Default-manager auto-initialization
;; ============================================================================

(deftest get-default-manager-auto-inits
  (testing "first get-default-manager creates a manager when none is set"
    (manager/set-default-manager! nil)
    (is (nil? (manager/peek-default-manager))
        "peek does NOT auto-create — sanity check")
    (let [tm (manager/get-default-manager)]
      (is (some? tm) "get auto-creates")
      (is (identical? tm (manager/get-default-manager))
          "second get returns the same instance")
      (is (identical? tm (manager/peek-default-manager))
          "peek now returns the auto-created instance"))))

(deftest set-default-manager-wins-over-auto-init
  (testing "explicit set-default-manager! is preserved by get"
    (let [custom (manager/create-task-manager :pool-size 2)]
      (manager/set-default-manager! custom)
      (is (identical? custom (manager/get-default-manager)))
      (is (identical? custom (manager/peek-default-manager))))))

(deftest set-default-manager-nil-allows-fresh-auto-init
  (testing "clearing with nil lets the next get auto-init a fresh manager"
    (let [first-tm (manager/get-default-manager)]
      (manager/set-default-manager! nil)
      (let [second-tm (manager/get-default-manager)]
        (is (some? second-tm))
        (is (not (identical? first-tm second-tm))
            "after nil-clear, a new instance is created")))))

(deftest task-manager-pool-uses-daemon-threads
  (testing "executor pool's worker threads are daemons so JVM exits cleanly"
    ;; Force a fresh manager to make sure the auto-init path produces
    ;; daemon threads (regression: a non-daemon factory would block JVM
    ;; exit and silently make `stop!` cleanup load-bearing again).
    (manager/set-default-manager! nil)
    (let [tm (manager/get-default-manager)
          ;; Submit a tiny job whose only purpose is to make the
          ;; thread pool actually create a worker we can inspect.
          latch (java.util.concurrent.CountDownLatch. 1)
          captured (atom nil)
          job (proxy [java.util.concurrent.Callable] []
                (call []
                  (reset! captured (Thread/currentThread))
                  (.countDown latch)
                  :done))
          es @(deref #'manager/!executor-service)]
      (is (some? es) "executor was lazily created")
      (.submit ^java.util.concurrent.ExecutorService es ^java.util.concurrent.Callable job)
      (is (.await latch 2 java.util.concurrent.TimeUnit/SECONDS)
          "worker thread ran our probe job within 2s")
      (is (.isDaemon ^Thread @captured)
          "executor worker thread is a daemon — JVM will exit cleanly without explicit shutdown")
      ;; Best-effort cleanup so the next test starts with a clean atom.
      (tp/shutdown tm)
      (manager/set-default-manager! nil))))

;; ============================================================================
;; BashJobExecutor Tests
;; ============================================================================

(defn- run-detached-to-terminal
  "Drive a detached executor result to its terminal map. BashJobExecutor
   follows the pure-async contract — execute-job returns :detached
   immediately and the manager/watcher polls :on-poll until it yields a
   terminal {:exit-code …}/{:error …} map. This helper mimics that poll
   loop so the unit tests can exercise the executor without the manager."
  [exec task on-output]
  (let [r (tp/execute-job exec task on-output)]
    (if (= :detached (:status r))
      (loop [n 0]
        (let [pr ((:on-poll r))]
          (if (and (= pr tp/still-running) (< n 200))
            (do (Thread/sleep 20) (recur (inc n)))
            pr)))
      r)))

(deftest bash-executor-echo-test
  (testing "BashJobExecutor runs echo command and captures output"
    (let [exec (executor/->BashJobExecutor)
          output (atom [])
          task (tp/->Task :test-1 "echo test" :bash
                          {:command "echo hello"} :running
                          (System/currentTimeMillis)
                          (System/currentTimeMillis) nil nil
                          (atom []) 10000 nil nil {})
          result (run-detached-to-terminal exec task (fn [line] (swap! output conj line)))]
      (is (= 0 (:exit-code result)))
      (is (= ["hello"] @output)))))

(deftest bash-executor-nonzero-exit-test
  (testing "BashJobExecutor handles nonzero exit code"
    (let [exec (executor/->BashJobExecutor)
          task (tp/->Task :test-2 "false cmd" :bash
                          {:command "false"} :running
                          (System/currentTimeMillis)
                          (System/currentTimeMillis) nil nil
                          (atom []) 10000 nil nil {})
          result (run-detached-to-terminal exec task (fn [_]))]
      (is (some? (:error result)))
      (is (= 1 (:exit-code result))))))

(deftest bash-executor-multiline-test
  (testing "BashJobExecutor captures multi-line output"
    (let [exec (executor/->BashJobExecutor)
          output (atom [])
          task (tp/->Task :test-3 "multi" :bash
                          {:command "echo line1 && echo line2 && echo line3"} :running
                          (System/currentTimeMillis)
                          (System/currentTimeMillis) nil nil
                          (atom []) 10000 nil nil {})
          result (run-detached-to-terminal exec task (fn [line] (swap! output conj line)))]
      (is (= 0 (:exit-code result)))
      (is (= ["line1" "line2" "line3"] @output)))))

;; ============================================================================
;; TaskManager Lifecycle Tests
;; ============================================================================

(deftest task-manager-create-and-start-test
  (testing "Create task manager, create task, start task, verify completion"
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      (let [task (tp/create-task tm "echo test" :bash {:command "echo hi"})
            tid  (:id task)]
        (is (= :pending (:status task)))
        ;; The task-id counter is seeded from on-disk artifacts via
        ;; persist/max-existing-task-id, so we can't assume :task-1.
        (is (keyword? tid))
        (tp/start-task tm tid)
        ;; Wait for completion
        (Thread/sleep 500)
        (let [updated (tp/get-task tm tid)]
          (is (= :completed (:status updated)))
          (is (= 0 (get-in updated [:result :exit-code])))
          (is (= ["hi"] @(:output-lines task))))))))

(deftest task-manager-persists-output-and-meta-to-disk
  (testing "start-task wires the disk appender — output.log and meta.edn exist after completion"
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      (let [task (tp/create-task tm "persist" :bash
                                 {:command "echo one && echo two && echo three"})]
        (tp/start-task tm (:id task))
        (Thread/sleep 700)
        (let [updated  (tp/get-task tm (:id task))
              out-file (persist/output-path nil (:id task))
              meta-file (persist/meta-path nil (:id task))]
          (is (= :completed (:status updated)))
          (is (some? out-file) "output-path resolvable")
          (is (.exists (io/file out-file)) "output.log exists on disk")
          (let [content (slurp out-file)]
            (is (re-find #"one" content))
            (is (re-find #"two" content))
            (is (re-find #"three" content)))
          (is (.exists (io/file meta-file)) "meta.edn exists")
          (let [meta (edn/read-string (slurp meta-file))]
            (is (= :completed (:status meta)))
            (is (= 0 (:exit-code meta))))
          ;; Backward-compat: ring buffer atom remains the source of truth
          (is (= ["one" "two" "three"] @(:output-lines task))))))))

(deftest task-detail-command-surfaces-output-file
  (testing "task$detail output includes :output-file pointing at an existing file"
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      (let [task (tp/create-task tm "detail-file" :bash {:command "echo detail"})]
        (tp/start-task tm (:id task))
        (Thread/sleep 500)
        (let [resp (cmds/task$detail :task-id (name (:id task)))]
          (is (some? (:output-file resp)) ":output-file populated")
          (is (.exists (io/file (:output-file resp))) "file exists on disk")
          (is (some? (:meta-file resp)) ":meta-file populated"))))))

(deftest task-detail-with-last-n-returns-lines
  (testing "task$detail :last-n N returns :lines (replaces the old task$output command)"
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      (let [task (tp/create-task tm "lines-via-detail" :bash {:command "echo a && echo b"})]
        (tp/start-task tm (:id task))
        (Thread/sleep 500)
        ;; With :last-n, :lines is populated.
        (let [resp (cmds/task$detail :task-id (name (:id task)) :last-n "10")]
          (is (= ["a" "b"] (:lines resp)))
          (is (some? (:output-file resp)))
          (is (.exists (io/file (:output-file resp)))))
        ;; Without :last-n, :lines is omitted (metadata-only).
        (let [resp (cmds/task$detail :task-id (name (:id task)))]
          (is (not (contains? resp :lines)) ":lines omitted when :last-n absent")
          (is (= 2 (:cached-lines resp)))
          (is (= 2 (:total-lines resp))))))))

(deftest task-manager-list-tasks-test
  (testing "List tasks with and without filters"
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      (tp/create-task tm "t1" :bash {:command "echo a"})
      (tp/create-task tm "t2" :bash {:command "echo b"})
      (is (= 2 (count (tp/list-tasks tm))))
      (is (= 2 (count (tp/list-tasks tm {:status :pending}))))
      (is (= 0 (count (tp/list-tasks tm {:status :running})))))))

(deftest task-manager-cancel-test
  (testing "Cancel a running task"
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      (let [task (tp/create-task tm "slow" :bash {:command "sleep 30"})]
        (tp/start-task tm (:id task))
        (Thread/sleep 200) ;; let it start
        (let [cancelled (tp/cancel-task tm (:id task))]
          (is (some? cancelled))
          (is (= :cancelled (:status cancelled))))))))

(deftest task-manager-remove-test
  (testing "Remove a task from registry"
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      (let [task (tp/create-task tm "removable" :bash {:command "echo bye"})]
        (is (some? (tp/get-task tm (:id task))))
        (tp/remove-task tm (:id task))
        (is (nil? (tp/get-task tm (:id task))))))))

(deftest task-manager-retry-test
  (testing "Retry a failed task creates a new task with same config"
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      (let [task (tp/create-task tm "will-fail" :bash {:command "false"})]
        (tp/start-task tm (:id task))
        (Thread/sleep 500)
        (is (= :failed (:status (tp/get-task tm (:id task)))))
        (let [retried (tp/retry-task tm (:id task))]
          (is (some? retried))
          (is (not= (:id task) (:id retried)))
          (is (= (:id task) (get-in retried [:metadata :retried-from]))))))))

;; ============================================================================
;; Ring Buffer Tests
;; ============================================================================

(deftest ring-buffer-test
  (testing "Output ring buffer truncates at max-output-lines"
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      ;; Generate 15 lines but cap at 5
      (let [task (tp/create-task tm "ring-buf" :bash
                                 {:command "for i in $(seq 1 15); do echo line$i; done"}
                                 {:max-output-lines 5})]
        (tp/start-task tm (:id task))
        (Thread/sleep 1000)
        (let [lines @(:output-lines task)]
          (is (<= (count lines) 5))
          ;; Should have the last 5 lines
          (is (= "line15" (last lines))))))))

(deftest default-max-output-lines-is-500-test
  (testing "tasks created without an explicit :max-output-lines opt default to 500 (tail-cache cap)"
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      (let [task (tp/create-task tm "default-cap" :bash {:command "echo hi"})]
        (is (= 500 (:max-output-lines task)))))))

;; ============================================================================
;; Format Tests
;; ============================================================================

(deftest format-task-list-empty-test
  (testing "format-task-list with no tasks"
    (let [result (task-fmt/format-task-list [])]
      (is (string? result))
      (is (re-find #"(?i)no tasks" result)))))

(deftest format-task-list-with-tasks-test
  (testing "format-task-list with tasks"
    (let [task (tp/->Task :task-1 "echo test" :bash {} :completed
                          1000 1000 1100 {:exit-code 0}
                          (atom ["hello"]) 10000 nil nil {})
          result (task-fmt/format-task-list [task])]
      (is (string? result))
      (is (re-find #"task-1" result))
      (is (re-find #"echo test" result)))))

(deftest format-task-detail-test
  (testing "format-task-detail shows all fields"
    (let [task (tp/->Task :task-1 "echo test" :bash {:command "echo hi"} :completed
                          1000 1000 1100 {:exit-code 0}
                          (atom ["hello"]) 10000 nil nil {})
          result (task-fmt/format-task-detail task)]
      (is (string? result))
      (is (re-find #"task-1" result))
      (is (re-find #"echo test" result))
      (is (re-find #"completed" result)))))

(deftest format-task-output-test
  (testing "format-task-output shows output lines"
    (let [task (tp/->Task :task-1 "test" :bash {} :completed
                          1000 1000 1100 nil
                          (atom ["line1" "line2" "line3"]) 10000 nil nil {})
          result (task-fmt/format-task-output task nil)]
      (is (string? result))
      (is (re-find #"3 lines" result))
      (is (re-find #"line1" result))
      (is (re-find #"line3" result)))))

(deftest format-task-output-last-n-test
  (testing "format-task-output respects last-n"
    (let [task (tp/->Task :task-1 "test" :bash {} :completed
                          1000 1000 1100 nil
                          (atom ["line1" "line2" "line3"]) 10000 nil nil {})
          result (task-fmt/format-task-output task 2)]
      (is (string? result))
      (is (re-find #"last 2 of 3" result))
      (is (not (re-find #"line1" result)))
      (is (re-find #"line2" result))
      (is (re-find #"line3" result)))))

(deftest format-task-notification-test
  (testing "format-task-notification for various transitions"
    (let [task-completed (tp/->Task :task-1 "test" :bash {} :completed
                                    1000 1000 1100 {:exit-code 0}
                                    (atom []) 10000 nil nil {})
          task-failed (tp/->Task :task-2 "test" :bash {} :failed
                                 1000 1000 1100 {:error "exit 1"}
                                 (atom []) 10000 nil nil {})
          task-cancelled (tp/->Task :task-3 "test" :bash {} :cancelled
                                    1000 1000 1100 {:error "cancelled"}
                                    (atom []) 10000 nil nil {})]
      (is (re-find #"\u2713" (task-fmt/format-task-notification task-completed :running)))
      (is (re-find #"\u2717" (task-fmt/format-task-notification task-failed :running)))
      (is (re-find #"cancelled" (task-fmt/format-task-notification task-cancelled :running)))
      (is (nil? (task-fmt/format-task-notification task-completed :completed))))))

;; ============================================================================
;; ToolJobExecutor Tests
;; ============================================================================

(defn- register-test-tool!
  "Register a temporary test tool. Returns the tool-id keyword."
  [tool-id tool-fn]
  (swap! tool/!tool-defs assoc tool-id
         {:id tool-id :type :command :fn (reify clojure.lang.IDeref (deref [_] tool-fn)) :meta {}})
  tool-id)

(defn- unregister-test-tool! [tool-id]
  (swap! tool/!tool-defs dissoc tool-id))

(deftest tool-executor-not-found-test
  (testing "ToolJobExecutor returns error when tool-id doesn't exist"
    (let [exec (executor/->ToolJobExecutor)
          output (atom [])
          task (tp/->Task :test-t1 "bad tool" :tool
                          {:tool-id :nonexistent-tool-xyz :tool-args {}} :running
                          (System/currentTimeMillis)
                          (System/currentTimeMillis) nil nil
                          (atom []) 10000 nil nil {})
          result (tp/execute-job exec task (fn [line] (swap! output conj line)))]
      (is (some? (:error result)))
      (is (re-find #"not found" (:error result)))
      (is (some #(re-find #"not found" %) @output)))))

(deftest tool-executor-success-test
  (testing "ToolJobExecutor invokes a registered tool and captures result"
    (let [tid (register-test-tool! :test-echo-tool
                                   (fn [& {:as args}] {:echoed (:msg args)}))]
      (try
        (let [exec (executor/->ToolJobExecutor)
              output (atom [])
              task (tp/->Task :test-t2 "echo tool" :tool
                              {:tool-id :test-echo-tool :tool-args {:msg "hello"}} :running
                              (System/currentTimeMillis)
                              (System/currentTimeMillis) nil nil
                              (atom []) 10000 nil nil {})
              result (tp/execute-job exec task (fn [line] (swap! output conj line)))]
          (is (= {:echoed "hello"} (:result result)))
          (is (some #(re-find #"Completed in" %) @output)))
        (finally (unregister-test-tool! tid))))))

(deftest tool-executor-exception-test
  (testing "ToolJobExecutor catches exceptions and returns error"
    (let [tid (register-test-tool! :test-throw-tool
                                   (fn [& _] (throw (ex-info "boom" {}))))]
      (try
        (let [exec (executor/->ToolJobExecutor)
              output (atom [])
              task (tp/->Task :test-t3 "throw tool" :tool
                              {:tool-id :test-throw-tool :tool-args {}} :running
                              (System/currentTimeMillis)
                              (System/currentTimeMillis) nil nil
                              (atom []) 10000 nil nil {})
              result (tp/execute-job exec task (fn [line] (swap! output conj line)))]
          (is (some? (:error result)))
          (is (some #(re-find #"Failed after" %) @output)))
        (finally (unregister-test-tool! tid))))))

;; ============================================================================
;; TaskManager Tool Task Tests
;; ============================================================================

(deftest task-manager-tool-task-test
  (testing "TaskManager runs a tool task end-to-end"
    (let [tid (register-test-tool! :test-adder
                                   (fn [& {:keys [a b]}] {:sum (+ (or a 0) (or b 0))}))]
      (try
        (let [tm (manager/create-task-manager)]
          (manager/set-default-manager! tm)
          (let [task (tp/create-task tm "add test" :tool
                                     {:tool-id :test-adder :tool-args {:a 3 :b 4}})]
            (tp/start-task tm (:id task))
            (Thread/sleep 500)
            (let [updated (tp/get-task tm (:id task))]
              (is (= :completed (:status updated)))
              (is (= {:sum 7} (:result (:result updated)))))))
        (finally (unregister-test-tool! tid))))))

;; ============================================================================
;; task$run :job-type :tool Command Tests
;; ============================================================================

(deftest task-run-tool-command-test
  (testing "task$run :job-type :tool creates and starts a tool task"
    (let [tid (register-test-tool! :test-greeter
                                   (fn [& {:keys [who]}] {:greeting (str "Hello " who)}))]
      (try
        (let [tm (manager/create-task-manager)]
          (manager/set-default-manager! tm)
          (let [result (cmds/task$run :job-type "tool" :tool-id "test-greeter"
                                      :tool-args "{\"who\":\"world\"}"
                                      :name "greet task")]
            (is (some? (:result result)))
            (is (= "running" (get-in result [:result :status])))
            (is (= "greet task" (get-in result [:result :name])))
            ;; Wait and verify completion
            (Thread/sleep 500)
            (let [task-id (keyword (get-in result [:result :task-id]))
                  updated (tp/get-task tm task-id)]
              (is (= :completed (:status updated)))
              (is (= {:greeting "Hello world"} (:result (:result updated)))))))
        (finally (unregister-test-tool! tid))))))

(deftest task-run-tool-missing-id-test
  (testing "task$run :job-type :tool returns error when tool-id is blank"
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      (is (some? (:error (cmds/task$run :job-type "tool" :tool-id "")))))))

(deftest task-run-tool-invalid-json-test
  (testing "task$run :job-type :tool returns error for invalid JSON in tool-args"
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      (let [result (cmds/task$run :job-type "tool" :tool-id "some-tool" :tool-args "{bad json}")]
        (is (some? (:error result)))
        (is (re-find #"Invalid JSON" (:error result)))))))

;; ============================================================================
;; Timeout enforcement (Tool executor + task$run :tool + run-bash-inline)
;; ============================================================================

(deftest tool-executor-timeout-test
  (testing "ToolJobExecutor cancels and surfaces :timed-out when :timeout-ms exceeded"
    (let [tid (register-test-tool! :test-slow-tool
                                   (fn [& _] (Thread/sleep 5000) {:ok true}))]
      (try
        (let [exec (executor/->ToolJobExecutor)
              output (atom [])
              task (tp/->Task :test-tt1 "slow tool" :tool
                              {:tool-id :test-slow-tool
                               :tool-args {}
                               :timeout-ms 200} :running
                              (System/currentTimeMillis)
                              (System/currentTimeMillis) nil nil
                              (atom []) 10000 nil nil {})
              start (System/currentTimeMillis)
              result (tp/execute-job exec task (fn [line] (swap! output conj line)))
              elapsed (- (System/currentTimeMillis) start)]
          (is (true? (:timed-out result)) ":timed-out true on result")
          (is (= 200 (:timeout-ms result)) ":timeout-ms surfaced")
          (is (re-find #"Tool timed out" (:error result)) ":error mentions timeout")
          (is (< elapsed 1500) (str "returned within 1.5s (actual " elapsed "ms)"))
          (is (some #(re-find #"Timed out" %) @output) "on-output reports timeout"))
        (finally (unregister-test-tool! tid))))))

(deftest tool-executor-agent-deref-uses-configured-timeout-test
  (testing "Agent-deref path honors :timeout-ms (regression: was hardcoded 300s)"
    (let [tid (register-test-tool! :test-slow-agent
                                   (fn [& _]
                                     (let [a (agent nil)]
                                       (send-off a (fn [_] (Thread/sleep 5000) :done))
                                       a)))]
      (try
        (let [exec (executor/->ToolJobExecutor)
              task (tp/->Task :test-tt2 "slow agent tool" :tool
                              {:tool-id :test-slow-agent
                               :tool-args {}
                               :timeout-ms 200} :running
                              (System/currentTimeMillis)
                              (System/currentTimeMillis) nil nil
                              (atom []) 10000 nil nil {})
              start (System/currentTimeMillis)
              result (tp/execute-job exec task (fn [_]))
              elapsed (- (System/currentTimeMillis) start)]
          (is (true? (:timed-out result))
              "agent-deref path bounded by configured :timeout-ms, not legacy 300s")
          (is (< elapsed 1500) (str "returned within 1.5s (actual " elapsed "ms)")))
        (finally (unregister-test-tool! tid))))))

(deftest task-run-tool-with-timeout-test
  (testing "task$run :job-type :tool plumbs :timeout into job-config and times out"
    (let [tid (register-test-tool! :test-slow-runner
                                   (fn [& _] (Thread/sleep 5000) {:ok true}))]
      (try
        (let [tm (manager/create-task-manager)]
          (manager/set-default-manager! tm)
          (let [result (cmds/task$run :job-type "tool"
                                      :tool-id "test-slow-runner"
                                      :timeout "200"
                                      :name "slow run")]
            (is (some? (:result result)) "task started")
            (Thread/sleep 1000)
            (let [task-id (keyword (get-in result [:result :task-id]))
                  updated (tp/get-task tm task-id)]
              (is (= :failed (:status updated))
                  "task transitions to :failed when ToolJobExecutor returns :error")
              (is (true? (get-in updated [:result :timed-out])) ":timed-out surfaced on task result")
              (is (= 200 (get-in updated [:result :timeout-ms])) ":timeout-ms surfaced"))))
        (finally (unregister-test-tool! tid))))))

(deftest run-bash-inline-completed-test
  (testing "run-bash-inline returns :status \"completed\" for fast bash commands"
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      (let [result (@#'ctools/run-bash-inline "echo hello" 5000)]
        (is (= "completed" (:status result)))
        (is (= 0 (:exit-code result)))
        (is (re-find #"hello" (:output result)))
        (is (nil? (:timeout-ms result)) ":timeout-ms only set on timeout path")))))

(deftest run-bash-inline-timeout-test
  (testing "run-bash-inline cancels + waits for proc death, returns :status \"timeout\""
    (let [tm (manager/create-task-manager)]
      (manager/set-default-manager! tm)
      (let [start (System/currentTimeMillis)
            result (@#'ctools/run-bash-inline "sleep 5" 500)
            elapsed (- (System/currentTimeMillis) start)]
        (is (= "timeout" (:status result)) ":status \"timeout\" rather than stale \"running\"")
        (is (= 500 (:timeout-ms result)) ":timeout-ms surfaced")
        ;; Generous bound: 500ms timeout + 500ms poll tick + 2s grace + invoke-tool overhead.
        (is (< elapsed 5000)
            (str "returned without waiting full 5s sleep (actual " elapsed "ms)"))))))
