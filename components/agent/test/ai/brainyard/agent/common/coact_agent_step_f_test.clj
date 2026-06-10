;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.coact-agent-step-f-test
  "Tests for Step F: CoAct's run-clj-sandbox-block / run-script-block delegate to
   the task manager (Steps A–E) and harvest-pending-tasks! walks task state
   instead of the sandbox's pending-evals registry.

   Covers: sync clojure success/error, sync bash, soft-timeout-pending for
   both langs (task stays :running, eval-entry has :status :pending), and
   harvest synthesizes an async-completion iteration record once the task
   actually completes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.coact-agent]
            [ai.brainyard.agent.task.manager :as task-mgr]
            [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.clj-sandbox.interface :as sandbox]))

;; Private fn handles via #'
(def ^:private run-clj-sandbox-block #'ai.brainyard.agent.common.coact-agent/run-clj-sandbox-block)
(def ^:private run-script-block  #'ai.brainyard.agent.common.coact-agent/run-script-block)
(def ^:private harvest-pending-tasks! #'ai.brainyard.agent.common.coact-agent/harvest-pending-tasks!)

(defn- reset-globals! []
  (when-let [mgr (task-mgr/peek-default-manager)]
    (try (tp/shutdown mgr) (catch Exception _)))
  (task-mgr/set-default-manager! nil))

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

;; ============================================================================
;; Sync paths
;; ============================================================================

(deftest sync-clojure-eval-projects-to-eval-entry
  (testing "clojure block — eval-entry has :result (pr-str), :output, no :error"
    (let [sb (sandbox/create-sandbox)
          r  (run-clj-sandbox-block sb "(println :hi) (* 6 7)" 5000)]
      (is (= "clojure" (:lang r)))
      (is (= "42" (:result r)))
      (is (re-find #":hi" (:output r)))
      (is (= "" (:error r)))
      (is (nil? (:status r)) "sync success has no :status keyword"))))

(deftest sync-clojure-error-surfaces-error
  (testing "clojure block — exception → :error populated, :result nil"
    (let [sb (sandbox/create-sandbox)
          r  (run-clj-sandbox-block sb "(throw (ex-info \"nope\" {}))" 5000)]
      (is (= "clojure" (:lang r)))
      (is (nil? (:result r)))
      (is (re-find #"nope" (:error r))))))

(deftest sync-final-call-projects-coact-nudge
  (testing "(FINAL …) in CoAct returns the explanatory nudge error, not the raw FINAL message"
    (let [sb (sandbox/create-sandbox)
          r  (run-clj-sandbox-block sb "(FINAL :done)" 5000)]
      (is (= "clojure" (:lang r)))
      (is (re-find #"FINAL is not supported in CoAct" (:error r))))))

(deftest sync-bash-eval-projects-to-eval-entry
  (testing "bash block — eval-entry has :result (exit-code), output, no :error"
    (let [r (run-script-block "bash" "echo bash-output ; echo more" 5000)]
      (is (= "bash" (:lang r)))
      (is (= "0" (:result r)))
      (is (re-find #"bash-output" (:output r)))
      (is (re-find #"more" (:output r)))
      (is (= "" (:error r))))))

(deftest sync-bash-nonzero-exit-projects-error
  (testing "bash nonzero exit → :result is exit-code string AND :error is populated"
    (let [r (run-script-block "bash" "echo oops ; exit 3" 5000)]
      (is (= "bash" (:lang r)))
      (is (re-find #"Exit code: 3" (:error r))))))

;; ============================================================================
;; Output truncation marker (eval-entry projection adds "[output truncated …]"
;; prefix when the on-disk log has more lines than survived the tail cache.)
;; ============================================================================

(deftest bash-eval-entry-marks-truncation-when-disk-exceeds-cache
  (testing "bash emitting more lines than the cache cap → eval-entry :output starts with the truncation marker pointing at the log file"
    ;; Drive the executor straight via the task manager so we can pin a small
    ;; cache cap. 50 cached vs 200 emitted exercises the truncation path
    ;; deterministically without hammering the default 500.
    (let [m (task-mgr/get-default-manager)
          task (tp/create-task m "trunc-bash" :bash
                               {:command "for i in $(seq 1 200); do echo line-$i; done"
                                :timeout-ms 10000}
                               {:metadata {:coact/lang "bash"}
                                :max-output-lines 50})
          _ (tp/start-task m (:id task))
          ;; Wait for completion (~50ms for 200 echos).
          _ (is (wait-for #(= :completed (:status (tp/get-task m (:id task)))) 5000))
          final (tp/get-task m (:id task))
          project @#'ai.brainyard.agent.common.coact-agent/project-terminal-task->eval-entry
          entry (project final)]
      (is (re-find #"\[output truncated: showing last 50 of 200 lines"
                   (:output entry))
          "marker prefix shows cached vs total counts")
      (is (re-find #"full log: /" (:output entry))
          "marker includes absolute path to the log file")
      (is (re-find #"line-200" (:output entry))
          "the actual tail still appears after the marker"))))

(deftest bash-eval-entry-no-marker-when-fits-in-cache
  (testing "bash emitting fewer lines than the cache cap → no truncation marker"
    (let [m (task-mgr/get-default-manager)
          task (tp/create-task m "fit-bash" :bash
                               {:command "for i in $(seq 1 5); do echo small-$i; done"
                                :timeout-ms 10000}
                               {:metadata {:coact/lang "bash"}
                                :max-output-lines 50})
          _ (tp/start-task m (:id task))
          _ (is (wait-for #(= :completed (:status (tp/get-task m (:id task)))) 5000))
          final (tp/get-task m (:id task))
          project @#'ai.brainyard.agent.common.coact-agent/project-terminal-task->eval-entry
          entry (project final)]
      (is (not (re-find #"\[output truncated" (:output entry)))
          "no marker when on-disk count ≤ cache count")
      (is (re-find #"small-5" (:output entry))))))

(deftest clojure-eval-entry-never-marked-truncated
  (testing "clojure entries source :output from the StringWriter directly — tail cache truncation does not apply"
    (let [sb (sandbox/create-sandbox)
          ;; Even with a tiny cap, clojure should never get the marker — the
          ;; executor's StringWriter holds the full output regardless of the
          ;; cache's eviction state.
          r (run-clj-sandbox-block sb "(dotimes [i 60] (println (str \"clj-line-\" i))) :done"
                                   5000)]
      (is (= "clojure" (:lang r)))
      (is (= ":done" (:result r)))
      (is (not (re-find #"\[output truncated" (:output r))))
      (is (re-find #"clj-line-59" (:output r))))))

;; ============================================================================
;; Soft-timeout pending path (the unified-contract win)
;; ============================================================================

(deftest soft-timeout-clojure-returns-pending
  (testing "long clojure eval with short waiter deadline → :status :pending, task still :running"
    (let [sb (sandbox/create-sandbox)
          ;; Note: positional arg `from-iteration` and `hard-timeout?` are
          ;; kwargs on the runner.
          r  (run-clj-sandbox-block sb "(Thread/sleep 800) :slow-clj" 200
                                    :from-iteration 5)]
      (is (= :pending (:status r)))
      (is (some? (:task-id r)) ":task-id should be set on pending entries")
      (is (re-find #"detached to background" (:output r)))
      ;; Verify the underlying task is still :running.
      (let [mgr  (task-mgr/get-default-manager)
            tid  (keyword (:task-id r))
            task (tp/get-task mgr tid)]
        (is (= :running (:status task)))
        (is (= 5 (get-in task [:metadata :coact/pending-from-iter])))
        (is (= "clojure" (get-in task [:metadata :coact/lang]))))
      ;; Let it complete so harvest test below isn't polluted.
      (Thread/sleep 1000))))

(deftest soft-timeout-bash-returns-pending
  (testing "long bash with short waiter deadline → :status :pending, task still :running, tmp-file rides on metadata"
    (let [r (run-script-block "bash" "sleep 1 ; echo done" 200
                              :from-iteration 7)]
      (is (= :pending (:status r)))
      (is (some? (:task-id r)))
      (let [mgr  (task-mgr/get-default-manager)
            tid  (keyword (:task-id r))
            task (tp/get-task mgr tid)]
        (is (= :running (:status task)))
        (is (= 7 (get-in task [:metadata :coact/pending-from-iter])))
        (is (= "bash" (get-in task [:metadata :coact/lang])))
        (is (some? (get-in task [:metadata :coact/tmp-file]))
            "tmp-file path should ride on metadata so harvest can clean up"))
      (Thread/sleep 1500))))

;; ============================================================================
;; Harvest path
;; ============================================================================

(deftest harvest-promotes-completed-pending-task
  (testing "harvest-pending-tasks! synthesizes an :async-completion? record from completed pending tasks and marks them :harvested? to prevent re-harvest (without removing them from the registry — /tasks and the activity panel still show the entry)"
    (let [sb (sandbox/create-sandbox)
          ;; Trigger soft-timeout pending for a quick clojure eval.
          pending (run-clj-sandbox-block sb "(Thread/sleep 300) :recovered" 50
                                         :from-iteration 11)
          _ (is (= :pending (:status pending)))
          mgr (task-mgr/get-default-manager)
          tid (keyword (:task-id pending))
          ;; Wait for the underlying task to terminate.
          _ (is (wait-for #(= :completed (:status (tp/get-task mgr tid))) 2000))
          st-memory (atom {:iteration-count 12 :iterations []})]
      (harvest-pending-tasks! st-memory)
      (let [iters (:iterations @st-memory)]
        (is (= 1 (count iters)))
        (let [rec (first iters)]
          (is (true? (:async-completion? rec)))
          (is (= 12 (:iteration rec)))
          (is (= "code" (:channel rec)))
          (is (re-find #"iter 11" (:thought rec)))
          (let [entry (first (:code-results rec))]
            (is (= "clojure" (:lang entry)))
            (is (= :resolved (:status entry)))
            (is (= ":recovered" (:result entry)))
            (is (= 11 (:from-iteration entry))))))
      ;; Task stays in the registry, marked :harvested? true. The marker
      ;; is what prevents double-harvest on subsequent calls; keeping the
      ;; task around lets `/tasks` and the activity panel still show it.
      (let [t (tp/get-task mgr tid)]
        (is (some? t)
            "harvested task should remain in the registry")
        (is (true? (get-in t [:metadata :harvested?]))
            "harvested task should be marked :harvested? true"))
      ;; Second harvest is a no-op — the :harvested? marker filters it out.
      (let [before-count (count (:iterations @st-memory))]
        (harvest-pending-tasks! st-memory)
        (is (= before-count (count (:iterations @st-memory)))
            "re-harvesting a marked task must NOT produce a duplicate record")))))

(deftest harvest-skips-still-running-tasks
  (testing "harvest leaves :running tasks in the registry; only terminal tasks are projected"
    (let [sb (sandbox/create-sandbox)
          pending (run-clj-sandbox-block sb "(Thread/sleep 2000) :slow" 50
                                         :from-iteration 3)
          _ (is (= :pending (:status pending)))
          mgr (task-mgr/get-default-manager)
          tid (keyword (:task-id pending))
          st-memory (atom {:iteration-count 4 :iterations []})]
      ;; Harvest immediately — task is still running, so nothing to project.
      (harvest-pending-tasks! st-memory)
      (is (= [] (:iterations @st-memory)))
      (is (= :running (:status (tp/get-task mgr tid)))
          "still-running task should remain in the registry")
      ;; Let it finish so the fixture's reset doesn't trip on a runaway task.
      (Thread/sleep 2200))))

(deftest harvest-no-op-when-no-coact-tagged-tasks
  (testing "harvest-pending-tasks! does nothing when no coact-tagged tasks exist"
    (let [st-memory (atom {:iteration-count 1 :iterations []})]
      (harvest-pending-tasks! st-memory)
      (is (= [] (:iterations @st-memory))))))

(deftest harvest-batches-multi-lang-completions
  (testing "harvest folds multiple completed pending tasks (clojure + bash) into one record"
    (let [sb (sandbox/create-sandbox)
          p1 (run-clj-sandbox-block sb "(Thread/sleep 300) :one" 50
                                    :from-iteration 9)
          p2 (run-script-block "bash" "sleep 0.3 ; echo two" 50
                               :from-iteration 9)]
      (is (= :pending (:status p1)))
      (is (= :pending (:status p2)))
      (let [mgr (task-mgr/get-default-manager)
            t1  (keyword (:task-id p1))
            t2  (keyword (:task-id p2))]
        (is (wait-for #(and (= :completed (:status (tp/get-task mgr t1)))
                            (= :completed (:status (tp/get-task mgr t2))))
                      3000))
        (let [st-memory (atom {:iteration-count 10 :iterations []})]
          (harvest-pending-tasks! st-memory)
          (let [iters (:iterations @st-memory)]
            (is (= 1 (count iters)) "all completions batched into a single record")
            (let [langs (set (map :lang (:code-results (first iters))))]
              (is (= #{"clojure" "bash"} langs)))))))))
