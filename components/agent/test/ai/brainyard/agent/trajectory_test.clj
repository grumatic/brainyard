;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.trajectory-test
  "Tests for the trajectory export module."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.trajectory :as trajectory]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn cleanup-store [f]
  (trajectory/clear-trajectories!)
  (f)
  (trajectory/clear-trajectories!))

(use-fixtures :each cleanup-store)

;; ============================================================================
;; extract-reasoning tests
;; ============================================================================

(deftest extract-reasoning-test
  (testing "extracts text excluding code blocks"
    (let [response "I'll analyze the data first.\n\n```clojure\n(+ 1 2)\n```\n\nThis gives us the answer."
          result (#'trajectory/extract-reasoning response)]
      (is (string? result))
      (is (str/includes? result "analyze the data"))
      (is (str/includes? result "gives us the answer"))
      (is (not (str/includes? result "(+ 1 2)")))))

  (testing "returns nil for nil input"
    (is (nil? (#'trajectory/extract-reasoning nil))))

  (testing "returns nil for code-only response"
    (is (nil? (#'trajectory/extract-reasoning "```clojure\n(+ 1 2)\n```")))))

;; ============================================================================
;; build-trajectory-entry tests
;; ============================================================================

(deftest build-trajectory-entry-test
  (testing "builds entry from iteration data"
    (let [entry (trajectory/build-trajectory-entry
                 {:iteration 1
                  :code-results [{:code "(+ 1 2)" :output "=> 3"}]})]
      (is (= 1 (:iteration entry)))
      (is (= ["(+ 1 2)"] (:code entry)))
      (is (= ["=> 3"] (:output entry)))))

  (testing "handles error in iteration"
    (let [entry (trajectory/build-trajectory-entry
                 {:iteration 2
                  :code-results [{:code "(/ 1 0)" :output ""}]
                  :error "Divide by zero"})]
      (is (= 2 (:iteration entry)))
      (is (some #(str/starts-with? % "ERROR:") (:output entry)))))

  (testing "handles nil/blank outputs"
    (let [entry (trajectory/build-trajectory-entry
                 {:iteration 1
                  :code ["(def x 1)"]
                  :output ["" nil ""]})]
      (is (= 1 (:iteration entry)))
      ;; blank outputs should be filtered
      (is (empty? (or (:output entry) [])))))

  (testing "handles nil code"
    (let [entry (trajectory/build-trajectory-entry
                 {:iteration 1
                  :code [nil]
                  :output ["=> 42"]})]
      (is (= 1 (:iteration entry)))
      ;; nil code items should be filtered
      (is (empty? (or (:code entry) []))))))

;; ============================================================================
;; build-trajectory tests
;; ============================================================================

(deftest build-trajectory-test
  (testing "builds complete trajectory from RLM result"
    (let [result {:answer "The answer is 42"
                  :iterations [{:iteration 1
                                :code ["(FINAL \"The answer is 42\")"]
                                :output [nil]}]
                  :terminated-by :final
                  :total-iterations 1}
          traj (trajectory/build-trajectory "What is the answer?" result
                                            :model "claude-sonnet-4-20250514"
                                            :agent-id "test-agent"
                                            :session-id "test-session")]
      (is (= "What is the answer?" (:query traj)))
      (is (= "The answer is 42" (:answer traj)))
      (is (true? (:success traj)))
      (is (= :final (:terminated-by traj)))
      (is (= 1 (:total-iterations traj)))
      (is (= "claude-sonnet-4-20250514" (:model traj)))
      (is (= "test-agent" (get-in traj [:metadata :agent-id])))
      (is (= "test-session" (get-in traj [:metadata :session-id])))
      (is (= 1 (count (:trajectory traj))))))

  (testing "marks failed trajectories"
    (let [result {:answer nil
                  :iterations [{:iteration 1 :code ["(+ 1 2)"] :output ["3"]}
                               {:iteration 2 :code ["(+ 3 4)"] :output ["7"]}]
                  :terminated-by :max-iterations
                  :total-iterations 2}
          traj (trajectory/build-trajectory "test" result)]
      (is (false? (:success traj)))
      (is (= :max-iterations (:terminated-by traj)))
      (is (= 2 (count (:trajectory traj))))))

  (testing "includes timing when started-at provided"
    (let [started (- (System/currentTimeMillis) 1000)
          result {:answer "done" :iterations [] :terminated-by :final :total-iterations 0}
          traj (trajectory/build-trajectory "q" result :started-at started)]
      (is (some? (:timing traj)))
      (is (>= (get-in traj [:timing :duration-ms]) 0))
      (is (= started (get-in traj [:timing :started-at])))))

  (testing "includes cost from usage summary"
    (let [result {:answer "done" :iterations [] :terminated-by :final :total-iterations 0}
          traj (trajectory/build-trajectory "q" result
                                            :usage-summary {:totals {:total-cost 0.0042}})]
      (is (= 0.0042 (:cost traj))))))

;; ============================================================================
;; store/retrieve tests
;; ============================================================================

(deftest store-and-retrieve-test
  (testing "stores and retrieves trajectories"
    (let [traj (trajectory/build-trajectory "test query"
                                            {:answer "test answer"
                                             :iterations [{:iteration 1 :code ["(+ 1 2)"] :output ["3"]}]
                                             :terminated-by :final
                                             :total-iterations 1}
                                            :session-id "sess-1")
          stored (trajectory/store-trajectory! traj)]
      (is (some? (:id stored)))
      (is (some? (:exported-at stored)))

      ;; Retrieve all
      (let [all (trajectory/get-trajectories)]
        (is (= 1 (count all)))
        (is (= (:id stored) (:id (first all)))))

      ;; Retrieve by ID
      (let [found (trajectory/get-trajectory (:id stored))]
        (is (some? found))
        (is (= "test query" (:query found))))))

  (testing "filters by session-id"
    (trajectory/clear-trajectories!)
    (let [traj1 (trajectory/build-trajectory "q1" {:answer "a1" :iterations [] :terminated-by :final :total-iterations 0}
                                             :session-id "sess-A")
          traj2 (trajectory/build-trajectory "q2" {:answer "a2" :iterations [] :terminated-by :final :total-iterations 0}
                                             :session-id "sess-B")]
      (trajectory/store-trajectory! traj1)
      (trajectory/store-trajectory! traj2)

      (is (= 2 (count (trajectory/get-trajectories))))
      (is (= 1 (count (trajectory/get-trajectories :session-id "sess-A"))))
      (is (= 1 (count (trajectory/get-trajectories :session-id "sess-B"))))))

  (testing "filters by success"
    (trajectory/clear-trajectories!)
    (let [success-traj (trajectory/build-trajectory "q1" {:answer "a1" :iterations [] :terminated-by :final :total-iterations 0})
          fail-traj (trajectory/build-trajectory "q2" {:answer nil :iterations [] :terminated-by :max-iterations :total-iterations 0})]
      (trajectory/store-trajectory! success-traj)
      (trajectory/store-trajectory! fail-traj)

      (is (= 1 (count (trajectory/get-trajectories :successful true))))
      (is (= 1 (count (trajectory/get-trajectories :successful false))))))

  (testing "bounded ring buffer (max 50)"
    (trajectory/clear-trajectories!)
    (dotimes [i 55]
      (trajectory/store-trajectory!
       (trajectory/build-trajectory (str "q" i) {:answer (str "a" i) :iterations [] :terminated-by :final :total-iterations 0})))

    (is (= 50 (count (trajectory/get-trajectories))))))

;; ============================================================================
;; trajectory-summary tests
;; ============================================================================

(deftest trajectory-summary-test
  (testing "creates lightweight summary"
    (let [traj {:id "test-id"
                :query "What is the answer to life, the universe, and everything?"
                :success true
                :terminated-by :final
                :total-iterations 3
                :cost 0.005
                :model "claude-sonnet"
                :timing {:started-at 1000 :ended-at 4000 :duration-ms 3000}
                :metadata {:agent-id "ag-1" :session-id "sess-1"}
                :trajectory [{:iteration 1 :code ["(+ 1 2)"] :output ["3"]}
                             {:iteration 2 :code nil :output nil}
                             {:iteration 3 :code ["(FINAL 42)"] :output ["ERROR: oops"]}]}
          summary (trajectory/trajectory-summary traj)]
      (is (= "test-id" (:id summary)))
      (is (true? (:success summary)))
      (is (= 3 (:total-iterations summary)))
      (is (= 0.005 (:cost summary)))
      (is (= 3000 (:duration-ms summary)))
      (is (= 3 (count (:iterations summary))))
      ;; First iteration has code
      (is (true? (get-in summary [:iterations 0 :has-code])))
      ;; Second iteration has no code
      (is (false? (get-in summary [:iterations 1 :has-code])))
      ;; Third iteration has error
      (is (true? (get-in summary [:iterations 2 :has-error]))))))

;; ============================================================================
;; format-trajectory-for-export tests
;; ============================================================================

(deftest format-for-export-test
  (testing "strips internal metadata for training export"
    (let [traj {:id "internal-id"
                :exported-at 12345
                :query "test"
                :context-summary "Context keys: conversation"
                :trajectory [{:iteration 1 :code ["x"] :output ["y"]}]
                :answer "42"
                :success true
                :cost 0.01
                :model "claude-sonnet"
                :total-iterations 1
                :timing {:started-at 1 :ended-at 2 :duration-ms 1}
                :metadata {:agent-id "ag" :session-id "ss"}}
          exported (trajectory/format-trajectory-for-export traj)]
      ;; Includes training-relevant fields
      (is (= "test" (:query exported)))
      (is (= "42" (:answer exported)))
      (is (true? (:success exported)))
      (is (= 0.01 (:cost exported)))
      (is (= "claude-sonnet" (:model exported)))
      (is (some? (:trajectory exported)))
      ;; Excludes internal fields
      (is (nil? (:id exported)))
      (is (nil? (:exported-at exported)))
      (is (nil? (:timing exported)))
      (is (nil? (:metadata exported)))
      (is (nil? (:terminated-by exported))))))
