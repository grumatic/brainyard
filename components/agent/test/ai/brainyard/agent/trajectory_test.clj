;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.trajectory-test
  "Tests for per-session trajectory recording (append-only trajectory.edn)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.trajectory :as trajectory]
            [ai.brainyard.agent.core.config :as config]))

;; ============================================================================
;; Fixtures — redirect the sessions root to a throwaway temp dir
;; ============================================================================

(def ^:dynamic *tmp-root* nil)

(defn- delete-tree! [^java.io.File f]
  (when (.exists f)
    (doseq [^java.io.File c (reverse (file-seq f))] (.delete c))))

(defn with-tmp-root [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "by-traj-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (binding [config/*sessions-root-override* (.getAbsolutePath dir)
              *tmp-root* dir]
      (try (f)
           (finally (delete-tree! dir))))))

(use-fixtures :each with-tmp-root)

;; ============================================================================
;; project-iteration
;; ============================================================================

(deftest project-iteration-test
  (testing "code-channel iteration keeps code/result/output/error"
    (let [entry (trajectory/project-iteration
                 {:iteration 1 :thought "summing" :channel "code"
                  :code-results [{:lang "clojure" :code "(+ 1 2)"
                                  :result "3" :output "" :error ""}]})]
      (is (= 1 (:n entry)))
      (is (= "code" (:channel entry)))
      (is (= "summing" (:thought entry)))
      (is (= ["(+ 1 2)"] (:code entry)))
      (is (= ["3"] (:result entry)))
      ;; blank output/error are dropped
      (is (nil? (:output entry)))
      (is (nil? (:error entry)))))

  (testing "tool-channel iteration keeps full per-tool detail"
    (let [entry (trajectory/project-iteration
                 {:iteration 2 :thought "reading" :channel "tool"
                  :tool-results [{:tool-name "read-file"
                                  :tool-args {:path "deps.edn"}
                                  :tool-result "…contents…"}]})]
      (is (= 2 (:n entry)))
      (is (= "tool" (:channel entry)))
      (is (= [{:name "read-file" :args {:path "deps.edn"} :result "…contents…"}]
             (:tools entry)))))

  (testing "async-completion records are tagged"
    (let [entry (trajectory/project-iteration
                 {:iteration 3 :channel "code" :async-completion? true
                  :code-results [{:code "x" :result "y"}]})]
      (is (true? (:async? entry)))))

  (testing "in-flight-roster records are dropped"
    (is (nil? (trajectory/project-iteration
               {:iteration 4 :channel "none" :in-flight-roster? true}))))

  (testing "oversized fields are truncated with a marker"
    (let [big (apply str (repeat (+ trajectory/max-field-chars 100) "x"))
          entry (trajectory/project-iteration
                 {:iteration 5 :channel "code"
                  :code-results [{:code big}]})]
      (is (str/includes? (first (:code entry)) "[truncated"))
      (is (<= (count (first (:code entry)))
              (+ trajectory/max-field-chars 40))))))

;; ============================================================================
;; build-turn-trajectory
;; ============================================================================

(deftest build-turn-trajectory-test
  (testing "covers all iterations, the last of which carries the answer"
    (let [rec (trajectory/build-turn-trajectory
               {:session-id "sess-1" :agent-id "ag-1" :turn-id 2
                :question "what is 2+2?" :answer "4"
                :iterations [{:iteration 1 :channel "code"
                              :code-results [{:code "(+ 2 2)" :result "4"}]}
                             {:iteration 2 :channel "none"
                              :code-results [{:code "(FINAL 4)"}]}]
                :success true :terminated-by :answer :total-iterations 2
                :model "free-llm:auto"
                :usage-summary {:totals {:total-cost 0.0
                                         :input-tokens 100 :output-tokens 20}}
                :started-at (- (System/currentTimeMillis) 500)})]
      (is (= 3 (:v rec)))
      (is (= "sess-1" (:session rec)))
      (is (= "ag-1" (:agent rec)))
      (is (= "what is 2+2?" (:question rec)))
      (is (true? (:success rec)))
      (is (= :answer (:terminated-by rec)))
      (is (= "free-llm:auto" (:model rec)))
      (is (= 0.0 (:cost rec)))
      (is (= {:in 100 :out 20} (:usage rec)))
      (is (>= (:duration-ms rec) 0))

      (testing "v3 — no top-level answer, and no turn number"
        (is (nil? (:answer rec)))
        ;; `:turn-id` was passed and deliberately ignored: it restarted at 1 on
        ;; every resume, so a file could hold three records all claiming to be
        ;; turn 1. Position in the file is the ordering that is actually true.
        (is (nil? (:turn rec))))

      (testing "the answer is the terminal iteration"
        (let [last-it (last (:iterations rec))]
          (is (= "answer" (:channel last-it)))
          (is (= "4" (:answer last-it)))))

      (testing "and it counts as an iteration, because it was one"
        ;; Two acting iterations + the one where the model chose to answer.
        (is (= 3 (count (:iterations rec))))
        (is (= 3 (:total-iterations rec))))))

  (testing "a loop that recorded its own answer iteration is left alone"
    ;; What coact does: the terminal iteration arrives with the model's final
    ;; thought attached. Synthesizing a second one would double the answer.
    (let [rec (trajectory/build-turn-trajectory
               {:session-id "s" :question "q" :answer "the answer"
                :iterations [{:iteration 1 :channel "answer"
                              :thought "I have what I need."
                              :answer "the answer"}]})]
      (is (= 1 (count (:iterations rec))))
      (is (= "I have what I need." (:thought (first (:iterations rec)))))
      (is (= "the answer" (trajectory/record-answer rec)))))

  (testing "marks unsuccessful / exhausted turns"
    (let [rec (trajectory/build-turn-trajectory
               {:session-id "s" :question "q" :answer nil
                :iterations [] :success false :terminated-by :max-iterations})]
      (is (false? (:success rec)))
      (is (= :max-iterations (:terminated-by rec)))
      ;; No answer to synthesize an iteration from, so there is nothing to count.
      (is (= 0 (:total-iterations rec)))
      (is (empty? (:iterations rec)))))

  (testing "answer is truncated when oversized"
    (let [big (apply str (repeat (+ trajectory/max-answer-chars 50) "a"))
          rec (trajectory/build-turn-trajectory
               {:session-id "s" :question "q" :answer big :iterations []})]
      (is (str/includes? (trajectory/record-answer rec) "[truncated")))))

;; ============================================================================
;; record-answer — one accessor, two record shapes
;; ============================================================================

(deftest record-answer-reads-both-shapes-test
  (testing "v3 — the terminal iteration"
    (is (= "new" (trajectory/record-answer
                  {:v 3 :iterations [{:n 1 :channel "code" :code ["(+ 1 1)"]}
                                     {:n 2 :channel "answer" :answer "new"}]}))))

  (testing "v2 — the top-level field"
    ;; trajectory.edn is append-only, so a session that predates v3 keeps its
    ;; old lines above the new ones forever. A reader that only knew v3 would
    ;; report every one of those turns as unanswered.
    (is (= "old" (trajectory/record-answer
                  {:v 2 :turn 1 :answer "old" :iterations [{:n 1 :channel "code"}]}))))

  (testing "a blank terminal answer falls through rather than winning"
    (is (= "old" (trajectory/record-answer
                  {:answer "old" :iterations [{:n 1 :channel "answer" :answer ""}]}))))

  (testing "nil when the turn has no answer either way"
    (is (nil? (trajectory/record-answer {:iterations [{:n 1 :channel "tool"}]})))))

;; ============================================================================
;; append / read round-trip
;; ============================================================================

(deftest append-and-read-test
  (testing "appends one EDN line per turn and reads them back in order"
    (let [r1 (trajectory/build-turn-trajectory
              {:session-id "sx" :turn-id 1 :question "q1" :answer "a1" :iterations []})
          r2 (trajectory/build-turn-trajectory
              {:session-id "sx" :turn-id 2 :question "q2" :answer "a2" :iterations []})]
      (trajectory/append-trajectory! "sx" r1)
      (trajectory/append-trajectory! "sx" r2)
      (let [back (trajectory/read-trajectories "sx")]
        (is (= 2 (count back)))
        (is (= "q1" (:question (first back))))
        (is (= "q2" (:question (second back))))
        ;; Order is the file's, not a field's — the last line is the last turn.
        (is (= "q2" (:question (trajectory/latest-trajectory "sx")))))))

  (testing "file lands at sessions/<id>/trajectory.edn"
    (trajectory/append-trajectory!
     "where" (trajectory/build-turn-trajectory
              {:session-id "where" :question "q" :answer "a" :iterations []}))
    (let [^java.io.File f (trajectory/session-trajectory-file "where")]
      (is (.exists f))
      (is (= "trajectory.edn" (.getName f)))
      (is (= "where" (.getName (.getParentFile f))))))

  (testing "embedded newlines in content stay on a single physical line"
    (let [rec (trajectory/build-turn-trajectory
               {:session-id "nl" :question "multi\nline\nquestion"
                :answer "line1\nline2"
                :iterations [{:iteration 1 :channel "code"
                              :code-results [{:code "(let [x 1]\n  (+ x 2))"}]}]})]
      (trajectory/append-trajectory! "nl" rec)
      (let [^java.io.File f (trajectory/session-trajectory-file "nl")
            raw (slurp f)
            physical-lines (remove str/blank? (str/split-lines raw))]
        ;; one turn → exactly one physical line, despite embedded \n in strings
        (is (= 1 (count physical-lines)))
        ;; and it round-trips back to the original multi-line content
        (let [back (first (trajectory/read-trajectories "nl"))]
          (is (= "multi\nline\nquestion" (:question back)))
          (is (= "line1\nline2" (trajectory/record-answer back)))
          (is (= ["(let [x 1]\n  (+ x 2))"] (get-in back [:iterations 0 :code])))))))

  (testing "read-trajectories returns nil when no file exists"
    (is (nil? (trajectory/read-trajectories "never-written"))))

  (testing "a corrupt tail line does not poison reads"
    (trajectory/append-trajectory!
     "corrupt" (trajectory/build-turn-trajectory
                {:session-id "corrupt" :question "ok" :answer "a" :iterations []}))
    ;; simulate a half-written line appended after a crash
    (spit (trajectory/session-trajectory-file "corrupt") "{:v 2 :half-writ" :append true)
    (let [back (trajectory/read-trajectories "corrupt")]
      (is (= 1 (count back)))
      (is (= "ok" (:question (first back)))))))
