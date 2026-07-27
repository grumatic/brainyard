;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.skill-distill-background-test
  "Tests for off-turn execution of the self-improvement loop (R1): the `:fn`
   job executor and the background submission helper — single-flight, the
   no-manager fallback, LLM-surface invisibility, and the exit drain.
   No LLM calls; thunks are plain functions."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.agent.common.skill-distill.background :as bg]
            [ai.brainyard.agent.task.executor :as executor]
            [ai.brainyard.agent.task.manager :as manager]
            [ai.brainyard.agent.task.protocol :as tp]))

;; ============================================================================
;; Fixtures / helpers
;; ============================================================================

(defn- reset-globals! []
  (when-let [mgr (manager/peek-default-manager)]
    (try (tp/shutdown mgr) (catch Exception _)))
  (manager/set-default-manager! nil))

(use-fixtures :each (fn [t] (reset-globals!) (t) (reset-globals!)))

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

(defn- start-manager! []
  (let [mgr (manager/create-task-manager :pool-size 2)]
    (manager/set-default-manager! mgr)
    mgr))

;; ============================================================================
;; FnJobExecutor
;; ============================================================================

(defn- run-fn-job
  "Drive FnJobExecutor directly with a synthetic task; returns [result output]."
  [job-config]
  (let [out (atom [])
        r   (tp/execute-job (executor/->FnJobExecutor)
                            {:id :task-1 :job-config job-config}
                            (fn [line] (swap! out conj line)))]
    [r @out]))

(deftest fn-executor-outcomes
  (testing "a returning thunk yields :result and logs to the task output"
    (let [[r out] (run-fn-job {:f (fn [] :staged) :label "distill"})]
      (is (= {:result :staged} r))
      (is (some #(re-find #"Running: distill" %) out))
      (is (some #(re-find #"Result: :staged" %) out))))

  (testing "a throwing thunk is contained as :error, never propagated"
    (let [[r out] (run-fn-job {:f (fn [] (throw (ex-info "boom" {})))})]
      (is (= "boom" (:error r)))
      (is (some #(re-find #"Failed after" %) out))))

  (testing "a wedged thunk is bounded by :timeout-ms"
    (let [[r _] (run-fn-job {:f (fn [] (Thread/sleep 5000) :never)
                             :timeout-ms 100})]
      (is (true? (:timed-out r)))
      (is (= 100 (:timeout-ms r)))))

  (testing "a non-fn job-config is rejected rather than thrown"
    (let [[r _] (run-fn-job {:f "not-a-fn"})]
      (is (re-find #"must be a function" (:error r)))))

  (testing "job-type is :fn and the manager registers it"
    (is (= :fn (tp/job-type (executor/->FnJobExecutor))))
    (is (some? (get (:executors (manager/create-task-manager :pool-size 1)) :fn)))))

;; ============================================================================
;; Submission
;; ============================================================================

(deftest run-off-turn-submits-a-background-task
  (let [mgr  (start-manager!)
        !ran (atom nil)
        r    (bg/run-off-turn! :kind :distill :key "sess-1" :label "d1"
                               :thunk (fn [] (reset! !ran :yes) :staged))]
    (is (= :submitted r))
    (is (wait-for #(= :yes @!ran) 3000) "the thunk ran on the task pool")
    (let [t (wait-for #(let [t (first (filter (comp #{"d1"} :name) (tp/list-tasks mgr)))]
                         (when (= :completed (:status t)) t))
                      3000)]
      (is (some? t) "the task reached a terminal state")
      (is (= {:result :staged} (:result t)))

      (testing "invisible to the model: no :coact/pending-from-iter tag"
        (is (nil? (get-in t [:metadata :coact/pending-from-iter]))))

      (testing "no TUI per-task block"
        (is (= :background (get-in t [:metadata :display-mode]))))

      (testing "tagged for single-flight and drain"
        (is (= :distill (get-in t [:metadata bg/kind-key])))
        (is (= "sess-1"  (get-in t [:metadata bg/flight-key])))))))

(deftest single-flight-per-kind-and-key
  (start-manager!)
  (let [!count (atom 0)
        gate   (promise)
        submit #(bg/run-off-turn! :kind :distill :key %
                                  :thunk (fn [] (swap! !count inc) (deref gate 3000 nil) :done))]
    (is (= :submitted (submit "sess-1")))
    (is (wait-for #(= 1 @!count) 3000) "first job started")

    (testing "a second job for the same kind+key is dropped, not queued"
      (is (= :duplicate (submit "sess-1")))
      (is (= 1 @!count)))

    (testing "a different key is independent"
      (is (= :submitted (submit "sess-2")))
      (is (wait-for #(= 2 @!count) 3000)))

    (testing "a different kind with the same key is independent"
      (is (= :submitted (bg/run-off-turn! :kind :refine :key "sess-1"
                                          :thunk (fn [] (swap! !count inc) :done))))
      (is (wait-for #(= 3 @!count) 3000)))

    (deliver gate :go)

    (testing "once the first job is terminal, the same key is submittable again"
      (is (wait-for #(empty? (bg/in-flight-tasks :distill "sess-1")) 3000))
      (is (= :submitted (submit "sess-1"))))))

(deftest auto-initializes-the-manager-when-absent
  (manager/set-default-manager! nil)
  (let [!ran (atom nil)
        r    (bg/run-off-turn! :kind :distill :key "sess-1"
                               :thunk (fn [] (reset! !ran :yes)))]
    (is (= :submitted r)
        "a session that has not run a task yet must still get a real task, not a future")
    (is (some? (manager/peek-default-manager)))
    (is (wait-for #(= :yes @!ran) 3000))))

(deftest reads-never-spin-up-a-manager
  (manager/set-default-manager! nil)
  (is (= [] (bg/in-flight-tasks)))
  (is (= 0 (bg/await-quiet! 100)))
  (is (nil? (manager/peek-default-manager))
      "the exit drain must not resurrect a manager just to find it empty"))

(deftest falls-back-to-a-future-when-submission-fails
  (let [!ran (atom nil)]
    (with-redefs [manager/get-default-manager (fn [] (throw (ex-info "no pool" {})))]
      (let [r (bg/run-off-turn! :kind :distill :key "sess-1"
                                :thunk (fn [] (reset! !ran :yes)))]
        (is (= :error r))
        (is (wait-for #(= :yes @!ran) 3000)
            "a task-layer fault must not silently drop the job")))

    (testing "a thunk that throws in the fallback never reaches the caller"
      (with-redefs [manager/get-default-manager (fn [] (throw (ex-info "no pool" {})))]
        (is (= :error (bg/run-off-turn! :kind :refine :key "s"
                                        :thunk (fn [] (throw (ex-info "boom" {}))))))))))

;; ============================================================================
;; Exit drain
;; ============================================================================

(deftest await-quiet-drains-then-gives-up
  (testing "returns 0 immediately when nothing is in flight"
    (start-manager!)
    (is (= 0 (bg/await-quiet! 1000))))

  (testing "waits for an in-flight job to finish"
    (start-manager!)
    (bg/run-off-turn! :kind :distill :key "sess-1"
                      :thunk (fn [] (Thread/sleep 300) :done))
    (is (wait-for #(seq (bg/in-flight-tasks)) 2000))
    (is (= 0 (bg/await-quiet! 5000)) "drained within the grace period"))

  (testing "gives up on a straggler rather than blocking /quit"
    (start-manager!)
    (bg/run-off-turn! :kind :distill :key "sess-2"
                      :thunk (fn [] (Thread/sleep 5000) :done))
    (is (wait-for #(seq (bg/in-flight-tasks)) 2000))
    (let [t0 (System/currentTimeMillis)
          n  (bg/await-quiet! 300)]
      (is (= 1 n) "still running when the wait ended")
      (is (< (- (System/currentTimeMillis) t0) 3000) "returned near the deadline"))))
