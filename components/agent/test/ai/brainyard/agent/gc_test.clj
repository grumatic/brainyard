;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.gc-test
  "Tests for on-disk artifact GC. Each test builds an isolated tmp project,
   seeds known fixture files, and runs the relevant sweep with explicit
   retention bounds (no reliance on global config). Cleanup is shell-rm —
   File.deleteOnExit() doesn't walk dirs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [ai.brainyard.agent.gc :as gc]))

;; ============================================================================
;; Fixture: isolated tmp project per test
;; ============================================================================

(def ^:dynamic *project* nil)
(def ^:dynamic *dirs*    nil)

(defn- mk-project-fixture [f]
  (let [p (io/file (System/getProperty "java.io.tmpdir")
                   (str "gc-test-" (System/currentTimeMillis) "-"
                        (.nextInt (java.util.Random.) 1000000)))]
    (.mkdirs p)
    (binding [*project* p
              *dirs*    {:user-dir    (System/getProperty "user.home")
                         :project-dir (.getAbsolutePath p)
                         :working-dir (.getAbsolutePath p)}]
      (try (f)
           (finally (shell/sh "rm" "-rf" (.getAbsolutePath p)))))))

(use-fixtures :each mk-project-fixture)

(def ^:private day-ms  86400000)
(def ^:private hour-ms 3600000)

(defn- mk-task-dir!
  "Create <project>/.brainyard/tasks/task-<n>/ with meta.edn + output.log
   stamped to `age-days` old. Status :completed unless `:status` override."
  [n age-days & {:keys [status] :or {status :completed}}]
  (let [dir (io/file *project* ".brainyard" "tasks" (str "task-" n))
        ts  (- (System/currentTimeMillis) (* age-days day-ms))]
    (.mkdirs dir)
    (let [m (io/file dir "meta.edn")
          o (io/file dir "output.log")]
      (spit m (pr-str {:id (keyword (str "task-" n)) :status status}))
      (spit o "x\n")
      (.setLastModified m ts)
      (.setLastModified o ts))
    (.setLastModified dir ts)
    dir))

(defn- count-task-dirs []
  (let [base (io/file *project* ".brainyard" "tasks")]
    (if (.exists base)
      (->> (.listFiles base) (filter #(.isDirectory ^java.io.File %)) count)
      0)))

;; ============================================================================
;; sweep-tasks!
;; ============================================================================

(deftest sweep-tasks-age-expires-regardless-of-count
  (testing "expired tasks are deleted even when well within the count cap
            (intersection: days is a hard expiry, not just a floor)"
    (dotimes [i 5] (mk-task-dir! i 30))  ; all 30 days old (terminal)
    ;; count 10 (> 5, so every task is within the count slot) but days 7 →
    ;; all are expired, so all are deleted. Under the old union policy the
    ;; count slot would have protected all 5.
    (let [r (gc/sweep-tasks! *dirs* :retention-count 10 :retention-days 7)]
      (is (= 5 (:scanned r)))
      (is (= 0 (:kept r))    "expiry deletes all aged tasks despite the count cap")
      (is (= 5 (:deleted r)))
      (is (= 0 (count-task-dirs))))))

(deftest sweep-tasks-count-caps-even-when-fresh
  (testing "count caps the number kept even when every task is within the age
            window (intersection: count is a hard cap, not just a floor)"
    (dotimes [i 5] (mk-task-dir! i 1))   ; all 1 day old (fresh, terminal)
    ;; All 5 are fresh, but count 2 caps survivors to the newest 2. Under the
    ;; old union policy freshness would have protected all 5.
    (let [r (gc/sweep-tasks! *dirs* :retention-count 2 :retention-days 7)]
      (is (= 5 (:scanned r)))
      (is (= 2 (:kept r))    "only the newest 2 survive despite all being fresh")
      (is (= 3 (:deleted r)))
      (is (= 2 (count-task-dirs))))))

(deftest sweep-tasks-drops-when-both-bounds-fail
  (testing "old AND beyond count → deleted"
    (mk-task-dir! 1 30)  ; old + beyond count
    (mk-task-dir! 2 30)  ; old + beyond count
    (mk-task-dir! 3 1)   ; fresh — protected by age
    (mk-task-dir! 4 1)   ; fresh — protected by age
    (let [r (gc/sweep-tasks! *dirs* :retention-count 2 :retention-days 7)]
      (is (= 4 (:scanned r)))
      ;; newest-by-mtime are 3,4 (1d old) → in keep-2 slot AND fresh
      ;; → 1,2 fail both → deleted.
      (is (= 2 (:kept r)))
      (is (= 2 (:deleted r))))))

(deftest sweep-tasks-skips-non-terminal
  (testing "live (running/pending) tasks are never deleted even when stale"
    (mk-task-dir! 1 60 :status :running)   ; 60 days old + running
    (mk-task-dir! 2 60 :status :pending)   ; 60 days old + pending
    (mk-task-dir! 3 60 :status :completed) ; 60 days old + terminal
    (let [r (gc/sweep-tasks! *dirs* :retention-count 0 :retention-days 0)]
      (is (= 3 (:scanned r)))
      (is (= 2 (:kept r))    "running + pending must survive")
      (is (= 1 (:deleted r)) "only the completed one is eligible"))))

(deftest sweep-tasks-dry-run-deletes-nothing
  (testing "dry-run reports counts but leaves disk untouched"
    (mk-task-dir! 1 30)
    (mk-task-dir! 2 30)
    (let [r (gc/sweep-tasks! *dirs* :retention-count 0 :retention-days 0 :dry-run? true)]
      (is (true? (:dry-run? r)))
      (is (= 2 (:deleted r)))
      (is (= 0 (:bytes-freed r))    "dry-run never reports freed bytes")
      (is (= 2 (count-task-dirs))))))

;; ============================================================================
;; sweep-coact-scratch!
;; ============================================================================

(defn- mk-scratch-file! [filename age-hours]
  (let [d (io/file *project* ".brainyard" "temp" "coact-agent" "scratch")
        _ (.mkdirs d)
        f (io/file d filename)
        ts (- (System/currentTimeMillis) (* age-hours hour-ms))]
    (spit f "scratch")
    (.setLastModified f ts)
    f))

(deftest sweep-coact-scratch-drops-old-files
  (testing "files older than max-age-hours are deleted; fresh ones kept"
    (mk-scratch-file! "old-1.sh" 48)
    (mk-scratch-file! "old-2.clj" 48)
    (mk-scratch-file! "fresh.py" 1)
    (let [r (gc/sweep-coact-scratch! *dirs* :max-age-hours 24)]
      (is (= 3 (:scanned r)))
      (is (= 2 (:deleted r)))
      (is (= 1 (:kept r))))))

(deftest sweep-coact-scratch-missing-dir-no-op
  (testing "no scratch dir → zero result, no exception"
    (let [r (gc/sweep-coact-scratch! *dirs*)]
      (is (= 0 (:scanned r)))
      (is (= 0 (:deleted r))))))

;; ============================================================================
;; sweep-sandbox-cache!
;; ============================================================================

(defn- mk-cache-file! [subpath filename ts size-bytes]
  (let [d (io/file *project* ".brainyard" "temp" "clj-sandbox" subpath)
        _ (.mkdirs d)
        f (io/file d filename)]
    (spit f (apply str (repeat size-bytes "x")))
    (.setLastModified f ts)
    f))

(deftest sweep-sandbox-cache-count-cap
  (testing "drops oldest until under file-count cap"
    (let [now (System/currentTimeMillis)]
      (dotimes [i 10]
        ;; mtime ascends with i → file-0 is oldest
        (mk-cache-file! "truncation/repl-output" (str "id" i ".txt") (+ now i) 100)))
    (let [r (gc/sweep-sandbox-cache! *dirs*
                                     :max-files 4
                                     :max-bytes 1000000
                                     :max-age-days 365)]
      (is (= 10 (:scanned r)))
      (is (= 4  (:kept r)))
      (is (= 6  (:deleted r))))))

(deftest sweep-sandbox-cache-byte-cap
  (testing "drops oldest until under byte cap"
    (let [now (System/currentTimeMillis)]
      ;; 10 files × 100 bytes = 1000 total
      (dotimes [i 10]
        (mk-cache-file! "file-backed/eval-code" (str "f" i ".txt") (+ now i) 100)))
    (let [r (gc/sweep-sandbox-cache! *dirs*
                                     :max-files 9999
                                     :max-bytes 300   ; keep ≤300B
                                     :max-age-days 365)]
      (is (= 10 (:scanned r)))
      ;; survives: 3 files × 100B (the last is at <= cap; oldest 7 dropped)
      (is (= 3 (:kept r)))
      (is (= 7 (:deleted r))))))

(deftest sweep-sandbox-cache-age-cap
  (testing "files older than max-age-days are dropped first"
    (let [now (System/currentTimeMillis)]
      (mk-cache-file! "truncation/r" "old.txt"   (- now (* 30 day-ms)) 100)
      (mk-cache-file! "truncation/r" "fresh.txt" (- now (* 1  day-ms)) 100))
    (let [r (gc/sweep-sandbox-cache! *dirs*
                                     :max-files 9999
                                     :max-bytes 1000000
                                     :max-age-days 7)]
      (is (= 2 (:scanned r)))
      (is (= 1 (:deleted r)))
      (is (= 1 (:kept r))))))

;; ============================================================================
;; run-all! aggregation
;; ============================================================================

(deftest run-all-returns-per-class-results
  (testing "run-all! returns one result map per class"
    (let [results (gc/run-all! *dirs* :dry-run? true)]
      (is (= 3 (count results)))
      (is (= #{:tasks :coact-scratch :sandbox-cache}
             (set (map :class results)))))))
