;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.analytics-commands-test
  "Tests for the on-demand session$analytics command: whole-session analysis
   from trajectory.edn, empty-trajectory error, and projection round-trip."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [ai.brainyard.agent.common.analytics-commands :as ac]
            [ai.brainyard.agent.common.trajectory :as trajectory]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.analytics.core.trajectory :as atraj]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn- delete-tree! [^java.io.File f]
  (when (.exists f)
    (doseq [^java.io.File c (reverse (file-seq f))] (.delete c))))

(defn with-tmp-root [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "by-analytics-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (binding [trajectory/*sessions-root* (.getAbsolutePath dir)]
      (try (f) (finally (delete-tree! dir))))))

(use-fixtures :each with-tmp-root)

(defn- mock-agent [session-id]
  (reify
    proto/IAgent
    (agent-id [_] :mock)
    (agent-name [_] "mock")
    (agent-description [_] "mock")
    (user-id [_] "tester")
    (session-id [_] session-id)
    (defagent-type [_] :mock)
    (process [_ _ _] nil)
    (get-tools [_] nil)
    (get-state [_] {})
    proto/IAgentMemoryAccess
    (get-memory-manager [_] nil)))

(defn- write-turn! [sid turn q ok? iters]
  (trajectory/append-trajectory!
   sid {:v 2 :turn turn :session sid :agent "mock"
        :question q :answer (if ok? "done" "")
        :success ok? :terminated-by (if ok? :answer :max-iterations)
        :total-iterations (if ok? 1 20)
        :model "claude-sonnet-4-6" :cost 0.002
        :usage {:in 1000 :out 200 :cache-read 700 :cache-write 0}
        :duration-ms 3000 :iterations iters}))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest empty-trajectory-test
  (testing "no trajectory yet → :error, zero turns"
    (binding [proto/*current-agent* (mock-agent "empty-sess")]
      (let [r (ac/session$analytics)]
        (is (string? (:error r)))
        (is (= 0 (:turns r)))))))

(deftest whole-session-analysis-test
  (let [sid "full-sess"]
    (write-turn! sid 1 "Fix parse-line in foo.clj" true
                 [{:n 1 :channel "tool" :tools [{:name "edit-file" :args {:p "x"} :result "ok"}]}])
    (write-turn! sid 2 "make it work somehow" false
                 [{:n 1 :channel "code" :error ["boom"]}])
    (write-turn! sid 3 "Add a test for parse-line" true
                 [{:n 1 :channel "code" :code ["(deftest …)"] :result ["ok"]}])
    (binding [proto/*current-agent* (mock-agent sid)]
      (testing "all three turns analyzed, full metric set returned"
        (let [r (ac/session$analytics)]
          (is (= "full-sess" (:session-id r)))
          (is (= 3 (:turns r)))
          (is (map? (:health-score r)))
          (doseq [k [:pqs :cost :iteration :tools :latency :cache :outcome]]
            (is (contains? r k) (str "missing " k)))
          (is (string? (:summary r)))
          (is (re-find #"Session Health:" (:summary r)))))
      (testing "format=full renders the per-turn drill-down"
        (let [r (ac/session$analytics :format "full")]
          (is (re-find #"Per-turn PQS" (:summary r)))))
      (testing "outcome reflects the one failed turn (2/3 success)"
        (let [r (ac/session$analytics)]
          (is (= 1 (count (get-in r [:outcome :failed-turns])))))))))

(deftest projection-round-trip-test
  (testing "build-turn-trajectory usage → records->usage-summary inverts cleanly"
    (let [rec (trajectory/build-turn-trajectory
               {:session-id "rt" :agent-id "mock" :turn-id 1
                :question "q" :answer "a" :success true :terminated-by :answer
                :total-iterations 1 :model "m" :iterations []
                :usage-summary {:totals {:input-tokens 1234 :output-tokens 567
                                         :cache-read-tokens 89 :cache-write-tokens 10
                                         :total-cost 0.042}}})
          summary (atraj/records->usage-summary [rec])
          totals  (:totals summary)]
      (is (= 1234 (:input-tokens totals)))
      (is (= 567 (:output-tokens totals)))
      (is (= 89 (:cache-read-tokens totals)))
      (is (= 10 (:cache-write-tokens totals))))))
