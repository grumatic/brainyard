;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.task.clj-sandbox-executor-test
  "Tests for Step D of the unified-code-eval refactor: the new
   :clj-sandbox-eval executor + task lifecycle integration.

   Verifies the executor projects sandbox results into the task-manager's
   terminal-result shape, that long-running evals trigger the manager's
   :detached branch (Step A) and are promoted by the watcher when the
   underlying sandbox future eventually completes, and that cancel-task on
   a detached sandbox-eval drives the executor's :on-cancel."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.task.manager :as manager]
            [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.clj-sandbox.interface :as sandbox]))

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

(defn- start-eval [code & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (let [m  (manager/get-default-manager)
        sb (sandbox/create-sandbox)
        t  (tp/create-task m (str "eval: " (subs code 0 (min 40 (count code))))
                           :clj-sandbox-eval
                           {:sandbox sb :code code :timeout-ms timeout-ms})]
    (tp/start-task m (:id t))
    [m sb t]))

;; ============================================================================
;; Sync completion paths
;; ============================================================================

(deftest sync-completion-simple-expr
  (testing "small expression — eval completes in-pool, task → :completed with :result"
    (let [[m _sb task] (start-eval "(+ 1 2 3)")]
      (is (wait-for #(= :completed (:status (tp/get-task m (:id task)))) 2000))
      (let [final (tp/get-task m (:id task))]
        (is (= :completed (:status final)))
        (is (= 6 (get-in final [:result :result])))
        (is (= "" (get-in final [:result :output])))))))

(deftest sync-completion-captures-stdout
  (testing "captured *out* surfaces in :output and is fanned out as on-output lines"
    (let [[m _sb task] (start-eval "(println \"hello\") (println \"world\") :ok")]
      (is (wait-for #(= :completed (:status (tp/get-task m (:id task)))) 2000))
      (let [final (tp/get-task m (:id task))]
        (is (= :ok (get-in final [:result :result])))
        (is (re-find #"(?s)hello.*world" (get-in final [:result :output])))
        (is (= ["hello" "world"] @(:output-lines final))
            "on-output should receive the captured lines once the eval returns")))))

(deftest sync-error-projects-to-failed
  (testing "user exception → executor returns {:error ...}, task → :failed"
    (let [[m _sb task] (start-eval "(throw (ex-info \"boom\" {}))")]
      (is (wait-for #(= :failed (:status (tp/get-task m (:id task)))) 2000))
      (let [final (tp/get-task m (:id task))]
        (is (= :failed (:status final)))
        (is (re-find #"boom" (get-in final [:result :error])))))))

(deftest defs-update-sandbox-history
  (testing "sandbox history sees the completed eval (parity with eval-code's sync path)"
    (let [[m sb task] (start-eval "(def x 99) x")]
      (is (wait-for #(= :completed (:status (tp/get-task m (:id task)))) 2000))
      (let [hist @(:history sb)]
        (is (= 1 (count hist)))
        (is (= 99 (:result (first hist))))))))

;; ============================================================================
;; Detached path (Step A integration)
;; ============================================================================

(deftest long-eval-detaches-then-promotes
  (testing "eval that outlives :timeout-ms returns :detached; watcher promotes when the daemon future completes"
    (let [[m _sb task] (start-eval "(Thread/sleep 600) :slow-done"
                                   :timeout-ms 200)]
      ;; Wait until the pool thread returns :detached and the manager registers it.
      (is (wait-for #(manager/detached? (:id task)) 2000)
          "task should be detach-registered once execute-job's pool thread returns")
      (let [mid (tp/get-task m (:id task))]
        (is (= :running (:status mid)))
        (is (manager/detached? (:id task))))
      ;; The watcher polls every 250ms; the underlying eval takes ~600ms.
      (is (wait-for #(= :completed (:status (tp/get-task m (:id task)))) 5000))
      (let [final (tp/get-task m (:id task))]
        (is (= :completed (:status final)))
        (is (= :slow-done (get-in final [:result :result])))))))

(deftest cancel-while-detached-drives-on-cancel
  (testing "cancel-task on a detached sandbox-eval cancels the daemon future; task → :cancelled"
    (let [[m _sb task] (start-eval "(Thread/sleep 10000)" :timeout-ms 200)]
      (is (wait-for #(manager/detached? (:id task)) 2000))
      (tp/cancel-task m (:id task))
      (let [final (tp/get-task m (:id task))]
        (is (= :cancelled (:status final)))
        (is (= {:error "cancelled"} (:result final)))))))

;; ============================================================================
;; FINAL termination — surfaced as :failed with explanatory error (Step F may
;; rewrap once coact owns the projection).
;; ============================================================================

(deftest final-call-projects-to-failed-with-value
  (testing "(FINAL …) inside a sandbox-eval task: :failed status, :error message, and :final-value rides along on :result"
    (let [[m _sb task] (start-eval "(FINAL :nope)")]
      (is (wait-for #(= :failed (:status (tp/get-task m (:id task)))) 2000))
      (let [final (tp/get-task m (:id task))]
        (is (= :failed (:status final)))
        (is (str/includes? (get-in final [:result :error]) "FINAL termination"))
        ;; FINAL stringifies non-string values via (with-out-str (print answer)),
        ;; so :final-value is ":nope" (the string form), not :nope (the keyword).
        (is (= ":nope" (get-in final [:result :final-value])))))))
