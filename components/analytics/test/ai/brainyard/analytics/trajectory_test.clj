;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.analytics.trajectory-test
  "Pure analyzer tests over hand-built `:v 2` trajectory record vectors.
   No I/O — native-image-safe (mirrors agent trajectory_test.clj)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.analytics.core.trajectory :as traj]
            [ai.brainyard.analytics.interface :as a]))

;; ============================================================================
;; Sample sessions
;; ============================================================================

(def clean-session
  [{:v 2 :turn 1
    :question "Fix the off-by-one in components/foo/bar.clj parse-line"
    :answer "Done." :success true :terminated-by :answer :total-iterations 1
    :model "claude-sonnet-4-6" :cost 0.002
    :usage {:in 1000 :out 200 :cache-read 800 :cache-write 100} :duration-ms 4000
    :iterations [{:n 1 :channel "tool"
                  :tools [{:name "edit-file" :args {:path "bar.clj"} :result "ok"}]}]}
   {:v 2 :turn 2
    :question "Add a unit test for parse-line covering empty input"
    :answer "Added test." :success true :terminated-by :answer :total-iterations 2
    :model "claude-sonnet-4-6" :cost 0.003
    :usage {:in 1200 :out 300 :cache-read 1000 :cache-write 0} :duration-ms 6000
    :iterations [{:n 1 :channel "code" :code ["(deftest …)"] :result ["ok"]}]}])

(def thrashing-session
  [{:v 2 :turn 1 :question "make it work somehow" :answer "" :success false
    :terminated-by :max-iterations :total-iterations 30 :model "claude-opus-4-6" :cost 0.05
    :usage {:in 8000 :out 50 :cache-read 100 :cache-write 0} :duration-ms 60000
    :iterations [{:n 1 :channel "tool" :tools [{:name "read-file" :args {:p "x"} :result "err"}]}
                 {:n 2 :channel "code" :error ["boom"]}]}
   {:v 2 :turn 2 :question "make it work somehow please" :answer "" :success false
    :terminated-by :max-iterations :total-iterations 28 :model "claude-opus-4-6" :cost 0.04
    :usage {:in 9000 :out 40 :cache-read 50 :cache-write 0} :duration-ms 58000
    :iterations [{:n 1 :channel "code" :error ["boom again"]}]}])

(def tool-error-session
  [{:v 2 :turn 1 :question "read three files" :answer "ok" :success true
    :terminated-by :answer :total-iterations 4 :model "claude-sonnet-4-6" :cost 0.01
    :usage {:in 2000 :out 400 :cache-read 500 :cache-write 0} :duration-ms 8000
    :iterations [{:n 1 :channel "tool" :tools [{:name "read-file" :args {:p "a"} :result "x"}]}
                 {:n 2 :channel "code" :error ["NPE"]}
                 {:n 3 :channel "code" :error ["NPE again"]}
                 ;; redundant: same name+args repeated
                 {:n 4 :channel "tool" :tools [{:name "read-file" :args {:p "a"} :result "x"}
                                               {:name "read-file" :args {:p "a"} :result "x"}]}]}])

(def cache-degrading-session
  [{:v 2 :turn 1 :question "q1" :answer "a1" :success true :terminated-by :answer
    :total-iterations 1 :model "m" :cost 0.001
    :usage {:in 100 :out 50 :cache-read 900 :cache-write 0} :duration-ms 1000 :iterations []}
   {:v 2 :turn 2 :question "q2" :answer "a2" :success true :terminated-by :answer
    :total-iterations 1 :model "m" :cost 0.001
    :usage {:in 100 :out 50 :cache-read 800 :cache-write 0} :duration-ms 1000 :iterations []}
   {:v 2 :turn 3 :question "q3" :answer "a3" :success true :terminated-by :answer
    :total-iterations 1 :model "m" :cost 0.001
    :usage {:in 900 :out 50 :cache-read 50 :cache-write 0} :duration-ms 1000 :iterations []}])

;; ============================================================================
;; Projection (inverse of usage->compact)
;; ============================================================================

(deftest projection-test
  (testing "records->usage-summary inverts the usage->compact field mapping"
    (let [s (traj/records->usage-summary
             [{:usage {:in 100 :out 50 :cache-read 30 :cache-write 10} :cost 0.5 :model "m"}])
          totals (:totals s)]
      (is (= 100 (:input-tokens totals)))
      (is (= 50 (:output-tokens totals)))
      (is (= 150 (:total-tokens totals)))
      (is (= 30 (:cache-read-tokens totals)))
      (is (= 10 (:cache-write-tokens totals)))
      (is (= 1 (:call-count totals)))
      (is (== 0.5 (:total-cost totals)))))

  (testing "records->messages projects question→user, answer→assistant"
    (let [msgs (traj/records->messages clean-session)]
      (is (= 4 (count msgs)))
      (is (= "user" (:role (first msgs))))
      (is (= "assistant" (:role (second msgs)))))))

;; ============================================================================
;; Whole-suite directional assertions
;; ============================================================================

(deftest clean-vs-thrashing-test
  (let [c (a/analyze-trajectory clean-session)
        t (a/analyze-trajectory thrashing-session)]
    (testing "clean session scores higher overall"
      (is (> (get-in c [:health-score :score])
             (get-in t [:health-score :score]))))
    (testing "clean outcome is perfect, thrashing is broken"
      (is (= 100 (get-in c [:outcome :score])))
      (is (= 0 (get-in t [:outcome :score]))))
    (testing "thrashing converges poorly (max-iterations heavy)"
      (is (= 2 (get-in t [:iteration :terminated-by :max-iterations])))
      (is (< (get-in t [:iteration :score])
             (get-in c [:iteration :score]))))
    (testing "every metric block is present"
      (doseq [k [:pqs :cost :iteration :tools :latency :cache :outcome :waste :health-score]]
        (is (contains? c k) (str "missing " k))))
    (testing "health-score carries a letter grade"
      (is (string? (get-in c [:health-score :grade]))))))

(deftest tool-reliability-test
  (let [r (a/analyze-trajectory tool-error-session)
        tur (:tools r)]
    (testing "tool error rate is positive and redundant calls detected"
      (is (pos? (:error-rate tur)))
      (is (pos? (:redundant-calls tur))))
    (testing "TUR score penalized below perfect"
      (is (< (:score tur) 100)))
    (testing "tool call counts aggregate"
      (is (= 3 (:total-tool-calls tur)))
      (is (= 1 (:unique-tools tur))))))

(deftest cache-degradation-test
  (let [r (a/analyze-trajectory cache-degrading-session)]
    (testing "degradation flagged when late hit-rate collapses"
      (is (true? (get-in r [:cache :degrading?]))))))

(deftest shs-renormalization-test
  (testing "absent TUR/LT (no tool work, no latency) are nil and SHS renormalizes"
    (let [no-work [{:v 2 :turn 1 :question "hi" :answer "ok" :success true
                    :terminated-by :answer :total-iterations 1 :model "m" :cost 0.0
                    :usage {:in 10 :out 5} :iterations []}]
          r (a/analyze-trajectory no-work)]
      (is (nil? (get-in r [:tools :score])))
      (is (nil? (get-in r [:latency :score])))
      ;; SHS still produced (renormalized over present components), not zeroed
      (is (pos? (get-in r [:health-score :score]))))))

(deftest empty-session-test
  (testing "empty record vector analyzes to zero turns without throwing"
    (let [r (a/analyze-trajectory [])]
      (is (= 0 (:turns r)))
      (is (map? (:health-score r))))))

(deftest format-levels-test
  (let [r (a/analyze-trajectory clean-session)]
    (testing "summary renders the health header + component lines"
      (let [s (a/format-session-analytics r :summary)]
        (is (re-find #"Session Health:" s))
        (is (re-find #"Prompt Quality" s))))
    (testing "full adds per-turn PQS + slowest turns"
      (let [s (a/format-session-analytics r :full)]
        (is (re-find #"Per-turn PQS" s))
        (is (re-find #"Slowest turns" s))))
    (testing "raw returns the data map as a string"
      (is (re-find #":health-score" (a/format-session-analytics r :raw))))))
