;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.task.manager-test
  "Tests for the task lifecycle's `:detached` outcome: promotion on completion,
   cancel-while-detached, incremental output draining, and the guarantee that a
   broken executor cannot park a task at `:running` forever. Uses a stub
   IJobExecutor returning the detached shape — no real bash/sandbox needed.

   These used to test a shared 300ms WATCHER that asked every detached task
   whether it had finished yet. The watcher is gone (design Phase 3): an
   executor's `:task` reports its own completion, so promotion is immediate
   rather than up to 300ms late. Every invariant the watcher suite asserted is
   still asserted here — promotion on success, on failure, independence across
   concurrent tasks, cancel driving `:on-cancel` exactly once, and above all
   that a task can never be stranded — they are simply expressed against the
   effect contract instead of a poll fn.

   The stub settles on a promise rather than an atom the watcher would sample,
   which is what lets a test control completion timing precisely without a
   polling loop in the way."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.task.manager :as manager]
            [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.effect.interface :as fx]))

;; ============================================================================
;; Stub executor — drives the :detached outcome on demand
;; ============================================================================

(defn- stub-executor
  "An IJobExecutor returning whatever `:initial` says.

   For the detached shape, `:settle` is a promise the test delivers to decide
   when — and with what — the task completes. `fx/from-promise` turns it into a
   Task, so this exercises the real registration path rather than a mock of it.
   `:on-drain` is passed through when supplied so the drain scheduling can be
   tested too."
  [{:keys [job-type initial settle on-cancel-counter on-drain]
    :or {job-type :test-detach
         settle (promise)
         on-cancel-counter (atom 0)}}]
  (reify tp/IJobExecutor
    (execute-job [_ _task _on-output]
      (case (:status initial)
        :detached
        (cond-> {:status    :detached
                 :task      (fx/from-promise settle)
                 :on-cancel (fn [] (swap! on-cancel-counter inc))}
          on-drain (assoc :on-drain on-drain))
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
;; Promotion
;; ============================================================================

(deftest detached-outcome-promotes-when-the-effect-completes
  (testing "executor :detached → task stays :running and detach-registered, then promotes the moment its Task settles"
    (let [settle (promise)
          stub (stub-executor {:initial {:status :detached} :settle settle})
          mgr  (make-manager-with stub :test-detach)
          task (tp/create-task mgr "detach-promote" :test-detach {})
          _    (tp/start-task mgr (:id task))]

      (testing "after start, task is :running and detach-handler is registered"
        (is (wait-for #(manager/detached? (:id task)) 1000)
            "task should be detach-registered once the pool thread returns the detached outcome")
        (let [t (tp/get-task mgr (:id task))]
          (is (= :running (:status t)))
          (is (manager/detached? (:id task)))
          (is (nil? (:completed-at t)))))

      (testing "a settled Task promotes to :completed"
        (deliver settle {:result :ok})
        (is (wait-for #(= :completed (:status (tp/get-task mgr (:id task)))) 2000))
        (let [t (tp/get-task mgr (:id task))]
          (is (= :completed (:status t)))
          (is (= {:result :ok} (:result t)))
          (is (some? (:completed-at t)))))

      (testing "detach-handler is removed after promotion"
        (is (not (contains? @@#'manager/!detached-handlers (:id task))))))))

(deftest detached-promotion-is-prompt
  (testing "promotion no longer waits on a poll interval

           The watcher sampled every 300ms, so a task that finished right after
           a tick waited most of that before anyone noticed. The Task calls
           back directly, so this asserts a bound the old design could not
           meet: well inside one former poll interval."
    (let [settle (promise)
          stub (stub-executor {:initial {:status :detached} :settle settle})
          mgr  (make-manager-with stub :test-detach)
          task (tp/create-task mgr "detach-prompt" :test-detach {})
          _    (tp/start-task mgr (:id task))]
      (is (wait-for #(manager/detached? (:id task)) 1000))
      (let [t0 (System/currentTimeMillis)]
        (deliver settle {:result :ok})
        (is (wait-for #(= :completed (:status (tp/get-task mgr (:id task)))) 2000))
        (let [elapsed (- (System/currentTimeMillis) t0)]
          ;; 150ms is deliberately loose — `wait-for` itself samples at 25ms and
          ;; CI is not a quiet machine. The point is that it is not ~300ms+.
          (is (< elapsed 150)
              (str "expected prompt promotion, took " elapsed "ms")))))))

(deftest detached-failure-result-promotes-to-failed
  (testing "a Task settling with {:error ...} promotes the task to :failed"
    (let [settle (promise)
          stub (stub-executor {:initial {:status :detached} :settle settle})
          mgr  (make-manager-with stub :test-detach)
          task (tp/create-task mgr "detach-fail" :test-detach {})
          _    (tp/start-task mgr (:id task))]
      (is (wait-for #(manager/detached? (:id task)) 1000))
      (deliver settle {:error "boom"})
      (is (wait-for #(= :failed (:status (tp/get-task mgr (:id task)))) 2000))
      (is (= {:error "boom"} (:result (tp/get-task mgr (:id task))))))))

;; ============================================================================
;; A task can never be stranded
;; ============================================================================
;;
;; The watcher suite asserted this by making `:on-poll` throw. The mechanism
;; changed but the invariant is the same and matters more than any other in
;; this file: a detached task that nobody finalizes sits at `:running`
;; forever, and every caller waiting on it — `await-task`, the harvest, the
;; TUI block — waits with it.

(deftest detached-effect-throwing-finalizes-as-failed
  (testing "a Task that throws is terminal, not a task parked forever"
    (let [stub (reify tp/IJobExecutor
                 (execute-job [_ _task _on-output]
                   {:status    :detached
                    :task      (fx/task-of (fn [] (throw (ex-info "effect broke" {}))))
                    :on-cancel (fn [] nil)})
                 (cancel-job [_ _task] true)
                 (job-type [_] :test-detach))
          mgr  (make-manager-with stub :test-detach)
          task (tp/create-task mgr "detach-effect-broke" :test-detach {})
          _    (tp/start-task mgr (:id task))]
      (is (wait-for #(= :failed (:status (tp/get-task mgr (:id task)))) 2000))
      (is (= "effect broke" (:error (:result (tp/get-task mgr (:id task)))))))))

(deftest detached-without-a-task-fails-loudly
  (testing "an executor returning :detached with no :task is a programming error

           Under the watcher this shape simply registered an :on-poll of nil
           and the task sat at :running until something cancelled it. Now it is
           failed immediately with a message naming the cause, because a
           silent strand is the worst outcome available here."
    (let [stub (reify tp/IJobExecutor
                 (execute-job [_ _task _on-output]
                   {:status :detached :on-cancel (fn [] nil)})
                 (cancel-job [_ _task] true)
                 (job-type [_] :test-detach))
          mgr  (make-manager-with stub :test-detach)
          task (tp/create-task mgr "detach-no-task" :test-detach {})
          _    (tp/start-task mgr (:id task))]
      (is (wait-for #(= :failed (:status (tp/get-task mgr (:id task)))) 2000)
          "must not sit at :running")
      (is (re-find #"no :task" (:error (:result (tp/get-task mgr (:id task)))))))))

;; ============================================================================
;; Cancellation
;; ============================================================================

(deftest cancel-while-detached-drives-on-cancel
  (testing "cancel-task on a detached task invokes :on-cancel and finalizes :cancelled"
    (let [cancel-counter (atom 0)
          ;; Never delivered — the task only ends by being cancelled.
          settle (promise)
          stub (stub-executor {:initial {:status :detached}
                               :settle settle
                               :on-cancel-counter cancel-counter})
          mgr  (make-manager-with stub :test-detach)
          task (tp/create-task mgr "detach-cancel" :test-detach {})
          _    (tp/start-task mgr (:id task))]
      (is (wait-for #(manager/detached? (:id task)) 1000))
      (tp/cancel-task mgr (:id task))
      (is (= 1 @cancel-counter) ":on-cancel should be called exactly once")
      (let [t (tp/get-task mgr (:id task))]
        (is (= :cancelled (:status t)))
        (is (= {:error "cancelled"} (:result t))))
      (is (not (contains? @@#'manager/!detached-handlers (:id task))))
      (testing "the cancelled effect does not later re-finalize it as :failed"
        ;; Cancelling a Task interrupts its thread, so the failure callback
        ;; fires with an InterruptedException. finalize-task! is idempotent and
        ;; the handler classifies interrupts as cancellation, so the terminal
        ;; status must stay :cancelled rather than flipping to :failed.
        (Thread/sleep 300)
        (is (= :cancelled (:status (tp/get-task mgr (:id task)))))))))

;; ============================================================================
;; Incremental output draining
;; ============================================================================

(deftest on-drain-is-sampled-then-flushed
  (testing "the manager samples :on-drain while the task runs, and flushes once at the end

           This replaces what an executor's `:on-poll` used to do as a side
           effect of being asked whether it had finished. Sampling was always
           the right model for a growing buffer; only the completion check
           moved out of it."
    (let [settle (promise)
          drains (atom [])
          stub (stub-executor {:initial {:status :detached}
                               :settle settle
                               :on-drain (fn [flush?] (swap! drains conj flush?))})
          mgr  (make-manager-with stub :test-detach)
          task (tp/create-task mgr "detach-drain" :test-detach {})
          _    (tp/start-task mgr (:id task))]
      (is (wait-for #(manager/detached? (:id task)) 1000))
      (is (wait-for #(seq (filter false? @drains)) 2000)
          "should be sampled (flush? false) while running")
      (deliver settle {:result :ok})
      (is (wait-for #(= :completed (:status (tp/get-task mgr (:id task)))) 2000))
      (is (= [true] (filterv true? @drains))
          "exactly one final flush, at the end")
      (testing "and sampling stops once the task is terminal"
        (let [n (count @drains)]
          (Thread/sleep 500)
          (is (= n (count @drains))
              "the drain ticker must not outlive the task it drains for"))))))

;; ============================================================================
;; Hooks and the sync path
;; ============================================================================

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
        (let [settle (promise)
              stub (stub-executor {:initial {:status :detached} :settle settle})
              mgr  (make-manager-with stub :test-detach)
              task (tp/create-task mgr "detach" :test-detach {})
              _    (tp/start-task mgr (:id task))]
          (is (wait-for #(manager/detached? (:id task)) 1000))
          (deliver settle {:result :ok})
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

;; ============================================================================
;; Independence
;; ============================================================================

(deftest concurrent-detached-tasks-settle-independently
  (testing "N concurrent detached tasks promote independently, in whatever order they settle

           Under the watcher this proved one shared thread tracked each task
           separately. Now each task owns its own effect, so what it proves is
           that the registry keyed them correctly and no completion callback
           finalizes the wrong task."
    (let [settles (vec (repeatedly 3 promise))
          stubs   (mapv (fn [s] (stub-executor {:initial {:status :detached}
                                                :settle s})) settles)
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
        (is (wait-for #(manager/detached? (:id t)) 1000)))
      ;; Settle out of order, so an implementation that assumed registration
      ;; order would be caught.
      (deliver (settles 2) {:result :third})
      (is (wait-for #(= :completed (:status (tp/get-task mgr (:id (tasks 2))))) 2000))
      (deliver (settles 0) {:result :first})
      (deliver (settles 1) {:error  "second-failed"})
      (is (wait-for #(= :completed (:status (tp/get-task mgr (:id (tasks 0))))) 2000))
      (is (wait-for #(= :failed    (:status (tp/get-task mgr (:id (tasks 1))))) 2000))
      (is (= {:result :first}          (:result (tp/get-task mgr (:id (tasks 0))))))
      (is (= {:error  "second-failed"} (:result (tp/get-task mgr (:id (tasks 1))))))
      (is (= {:result :third}          (:result (tp/get-task mgr (:id (tasks 2))))))
      (is (empty? @@#'manager/!detached-handlers)))))
