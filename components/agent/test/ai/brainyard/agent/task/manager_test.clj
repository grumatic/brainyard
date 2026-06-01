;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.task.manager-test
  "Tests for Step A of the unified-code-eval refactor: the task lifecycle's
   `:detached` outcome, the shared detach watcher, cancel-while-detached, and
   poll-failure handling. Uses a stub IJobExecutor that returns the new
   detached shape — no real bash/sandbox needed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.task.manager :as manager]
            [ai.brainyard.agent.task.protocol :as tp]))

;; ============================================================================
;; Stub executor — drives the new :detached outcome on demand
;; ============================================================================

(defn- stub-executor
  "Return an IJobExecutor that returns whatever {:status :detached …} or
   terminal map the test puts in `:initial`. `:on-poll` and `:on-cancel`
   read from atoms so the test can drive completion timing precisely."
  [{:keys [job-type initial on-poll-atom on-cancel-counter]
    :or {job-type :test-detach
         on-poll-atom (atom tp/still-running)
         on-cancel-counter (atom 0)}}]
  (reify tp/IJobExecutor
    (execute-job [_ _task _on-output]
      (case (:status initial)
        :detached
        {:status    :detached
         :on-poll   (fn [] @on-poll-atom)
         :on-cancel (fn [] (swap! on-cancel-counter inc))}
        ;; terminal — passthrough
        initial))
    (cancel-job [_ _task] true)
    (job-type [_] job-type)))

(defn- make-manager-with [executor job-type]
  (manager/set-default-manager! nil)
  ;; create-task-manager constructs the standard executor map; we then
  ;; install our stub under the chosen job-type by reaching into the record.
  (let [base (manager/create-task-manager :pool-size 2)
        mgr  (manager/->TaskManager (assoc (:executors base) job-type executor))]
    (manager/set-default-manager! mgr)
    mgr))

(defn- wait-for
  "Poll `pred` every 25ms up to `timeout-ms`. Returns truthy/nil."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [v (pred)]
        (cond
          v v
          (> (System/currentTimeMillis) deadline) nil
          :else (do (Thread/sleep 25) (recur)))))))

(defn- reset-globals! []
  (when-let [mgr (manager/peek-default-manager)]
    (try (tp/shutdown mgr) (catch Exception _)))
  (manager/set-default-manager! nil))

(use-fixtures :each
  (fn [t] (reset-globals!) (t) (reset-globals!)))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest detached-outcome-promotes-via-watcher
  (testing "executor :detached → task stays :running with :detached? flag, then watcher promotes on terminal poll"
    (let [poll-atom (atom tp/still-running)
          stub (stub-executor {:initial {:status :detached}
                               :on-poll-atom poll-atom})
          mgr  (make-manager-with stub :test-detach)
          task (tp/create-task mgr "detach-promote" :test-detach {})
          _    (tp/start-task mgr (:id task))]

      (testing "after start, task is :running and detach-handler is registered"
        ;; Wait for the pool thread to call execute-job and return :detached.
        (is (wait-for #(get-in (tp/get-task mgr (:id task)) [:metadata :detached?])
                      1000)
            "task should be flagged :detached? once pool thread returns the detached outcome")
        (let [t (tp/get-task mgr (:id task))]
          (is (= :running (:status t)))
          (is (true? (get-in t [:metadata :detached?])))
          (is (nil? (:completed-at t)))))

      (testing "watcher promotes when :on-poll returns a terminal map"
        (reset! poll-atom {:result :ok})
        (is (wait-for #(= :completed (:status (tp/get-task mgr (:id task))))
                      2000)
            "watcher should promote to :completed once poll returns terminal")
        (let [t (tp/get-task mgr (:id task))]
          (is (= :completed (:status t)))
          (is (= {:result :ok} (:result t)))
          (is (some? (:completed-at t)))))

      (testing "detach-handler is removed after promotion"
        (is (not (contains? @@#'manager/!detached-handlers (:id task))))))))

(deftest detached-failure-result-promotes-to-failed
  (testing ":on-poll returning {:error ...} promotes task to :failed"
    (let [poll-atom (atom tp/still-running)
          stub (stub-executor {:initial {:status :detached}
                               :on-poll-atom poll-atom})
          mgr  (make-manager-with stub :test-detach)
          task (tp/create-task mgr "detach-fail" :test-detach {})
          _    (tp/start-task mgr (:id task))]
      (is (wait-for #(get-in (tp/get-task mgr (:id task)) [:metadata :detached?]) 1000))
      (reset! poll-atom {:error "boom"})
      (is (wait-for #(= :failed (:status (tp/get-task mgr (:id task)))) 2000))
      (is (= {:error "boom"} (:result (tp/get-task mgr (:id task))))))))

(deftest detached-poll-throw-finalizes-as-failed
  (testing "poll closure throwing is treated as terminal failure (can't park forever)"
    (let [stub (reify tp/IJobExecutor
                 (execute-job [_ _task _on-output]
                   {:status    :detached
                    :on-poll   (fn [] (throw (ex-info "poll broke" {})))
                    :on-cancel (fn [] nil)})
                 (cancel-job [_ _task] true)
                 (job-type [_] :test-detach))
          mgr  (make-manager-with stub :test-detach)
          task (tp/create-task mgr "detach-poll-broke" :test-detach {})
          _    (tp/start-task mgr (:id task))]
      (is (wait-for #(= :failed (:status (tp/get-task mgr (:id task)))) 2000))
      (let [t (tp/get-task mgr (:id task))]
        (is (= :failed (:status t)))
        (is (re-find #"detach poll failed" (:error (:result t))))))))

(deftest cancel-while-detached-drives-on-cancel
  (testing "cancel-task on a detached task invokes :on-cancel and finalizes :cancelled"
    (let [cancel-counter (atom 0)
          poll-atom      (atom tp/still-running)  ;; never resolves on its own
          stub (stub-executor {:initial {:status :detached}
                               :on-poll-atom poll-atom
                               :on-cancel-counter cancel-counter})
          mgr  (make-manager-with stub :test-detach)
          task (tp/create-task mgr "detach-cancel" :test-detach {})
          _    (tp/start-task mgr (:id task))]
      (is (wait-for #(get-in (tp/get-task mgr (:id task)) [:metadata :detached?]) 1000))
      (tp/cancel-task mgr (:id task))
      (is (= 1 @cancel-counter) ":on-cancel should be called exactly once")
      (let [t (tp/get-task mgr (:id task))]
        (is (= :cancelled (:status t)))
        (is (= {:error "cancelled"} (:result t))))
      (is (not (contains? @@#'manager/!detached-handlers (:id task)))))))

(deftest task-completed-hook-fires-once-per-task
  (testing ":task/completed fires exactly once whether the path is sync-terminal or detach-then-promote"
    (let [fired (atom [])
          _ (hooks/register-hook!
             :task/completed ::manager-test-hook
             (fn [{:keys [task]}] (swap! fired conj (:id task)))
             :source ::manager-test)]
      (try
        ;; Sync terminal path
        (let [stub (stub-executor {:initial {:result :sync-ok}})
              mgr  (make-manager-with stub :test-sync)
              task (tp/create-task mgr "sync" :test-sync {})
              _    (tp/start-task mgr (:id task))]
          (is (wait-for #(= :completed (:status (tp/get-task mgr (:id task)))) 1000)))
        ;; Detach path
        (let [poll-atom (atom tp/still-running)
              stub (stub-executor {:initial {:status :detached}
                                   :on-poll-atom poll-atom})
              mgr  (make-manager-with stub :test-detach)
              task (tp/create-task mgr "detach" :test-detach {})
              _    (tp/start-task mgr (:id task))]
          (is (wait-for #(get-in (tp/get-task mgr (:id task)) [:metadata :detached?]) 1000))
          (reset! poll-atom {:result :ok})
          (is (wait-for #(= :completed (:status (tp/get-task mgr (:id task)))) 2000)))
        ;; Each task fired :task/completed exactly once.
        (let [counts (frequencies @fired)]
          (is (every? #(= 1 %) (vals counts))
              (str "Each task-id should appear once in fired list, got: " @fired)))
        (finally
          (hooks/unregister-source! ::manager-test))))))

(deftest sync-terminal-paths-unchanged
  (testing "non-detached results still flow through finalize-task! → :completed / :failed"
    ;; :result → :completed
    (let [stub (stub-executor {:initial {:result 42}})
          mgr  (make-manager-with stub :test-sync)
          task (tp/create-task mgr "sync-ok" :test-sync {})
          _    (tp/start-task mgr (:id task))]
      (is (wait-for #(= :completed (:status (tp/get-task mgr (:id task)))) 1000))
      (is (= {:result 42} (:result (tp/get-task mgr (:id task))))))
    ;; :error → :failed
    (let [stub (stub-executor {:initial {:error "nope"}})
          mgr  (make-manager-with stub :test-fail)
          task (tp/create-task mgr "sync-fail" :test-fail {})
          _    (tp/start-task mgr (:id task))]
      (is (wait-for #(= :failed (:status (tp/get-task mgr (:id task)))) 1000))
      (is (= {:error "nope"} (:result (tp/get-task mgr (:id task))))))))

(deftest watcher-handles-multiple-concurrent-detached-tasks
  (testing "shared watcher promotes N concurrent detached tasks independently"
    (let [polls   (vec (repeatedly 3 #(atom tp/still-running)))
          stubs   (mapv (fn [p] (stub-executor {:initial {:status :detached}
                                                :on-poll-atom p})) polls)
          ;; Three distinct job-types so we can install all three executors.
          jts     [:test-detach-1 :test-detach-2 :test-detach-3]
          base    (manager/create-task-manager :pool-size 4)
          mgr     (manager/->TaskManager (merge (:executors base)
                                                (zipmap jts stubs)))
          _       (manager/set-default-manager! mgr)
          tasks   (mapv (fn [jt] (let [t (tp/create-task mgr (str "multi-" (name jt)) jt {})]
                                   (tp/start-task mgr (:id t))
                                   t))
                        jts)]
      (doseq [t tasks]
        (is (wait-for #(get-in (tp/get-task mgr (:id t)) [:metadata :detached?]) 1000)))
      ;; Resolve them out of order to make sure the watcher tracks each separately.
      (reset! (polls 2) {:result :third})
      (is (wait-for #(= :completed (:status (tp/get-task mgr (:id (tasks 2))))) 2000))
      (reset! (polls 0) {:result :first})
      (reset! (polls 1) {:error  "second-failed"})
      (is (wait-for #(= :completed (:status (tp/get-task mgr (:id (tasks 0))))) 2000))
      (is (wait-for #(= :failed    (:status (tp/get-task mgr (:id (tasks 1))))) 2000))
      (is (= {:result :first}        (:result (tp/get-task mgr (:id (tasks 0))))))
      (is (= {:error  "second-failed"} (:result (tp/get-task mgr (:id (tasks 1))))))
      (is (= {:result :third}        (:result (tp/get-task mgr (:id (tasks 2))))))
      (is (empty? @@#'manager/!detached-handlers)))))
