;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.task.persist-test
  "Tests for task output/lifecycle disk persistence."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.task.persist :as persist]
            [ai.brainyard.agent.task.protocol :as tp])
  (:import [java.io File]))

(defn- make-tmp-dirs ^File []
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "brainyard-persist-test-" (System/currentTimeMillis) "-" (rand-int 1000000)))]
    (.mkdirs d)
    d))

(defn- dirs-for [^File project-root]
  {:user-dir    (.getAbsolutePath project-root)
   :project-dir (.getAbsolutePath project-root)
   :working-dir (.getAbsolutePath project-root)})

(defn- make-task [id task-name]
  (tp/->Task id task-name :bash {:command "echo hi"} :pending
             (System/currentTimeMillis) nil nil nil
             (atom []) 10000 nil nil {}))

(deftest open-append-close-roundtrip
  (testing "open-appender! creates the directory and meta.edn; append-line! writes; close-appender! flushes terminal meta"
    (let [root (make-tmp-dirs)
          dirs (dirs-for root)
          task (make-task :task-rt-1 "roundtrip")]
      (try
        (persist/open-appender! dirs task)
        (let [out (persist/output-path dirs (:id task))
              meta-file (persist/meta-path dirs (:id task))]
          (is (.exists (io/file out)) "output.log created")
          (is (.exists (io/file meta-file)) "meta.edn created at open")
          (let [opened-meta (edn/read-string (slurp meta-file))]
            (is (= :pending (:status opened-meta)) "initial meta carries :pending status"))
          (persist/append-line! (:id task) "first")
          (persist/append-line! (:id task) "second")
          (persist/append-line! (:id task) "third")
          (let [terminal (assoc task :status :completed
                                :completed-at (System/currentTimeMillis)
                                :result {:exit-code 0})]
            (persist/close-appender! (:id task) terminal))
          (is (= "first\nsecond\nthird\n" (slurp out)))
          (let [final-meta (edn/read-string (slurp meta-file))]
            (is (= :completed (:status final-meta)))
            (is (= 0 (:exit-code final-meta)))
            (is (= {:exit-code 0} (:result final-meta)))))
        (finally
          (persist/close-appender! (:id task) task))))))

(deftest read-tail-returns-last-n-lines
  (testing "read-tail returns the last N lines in chronological order"
    (let [root (make-tmp-dirs)
          dirs (dirs-for root)
          task (make-task :task-tail-1 "tail")]
      (try
        (persist/open-appender! dirs task)
        (dotimes [i 12]
          (persist/append-line! (:id task) (str "line-" i)))
        ;; Force a flush by closing & reopening would also work, but the
        ;; appender flushes per line, so the file is up to date.
        (let [tail (persist/read-tail dirs (:id task) 3)]
          (is (= ["line-9" "line-10" "line-11"] tail)))
        (let [tail-1 (persist/read-tail dirs (:id task) 1)]
          (is (= ["line-11"] tail-1)))
        (finally
          (persist/close-appender! (:id task) task))))))

(deftest read-tail-empty-file
  (testing "read-tail returns [] for a missing file"
    (let [root (make-tmp-dirs)
          dirs (dirs-for root)]
      (is (= [] (persist/read-tail dirs :task-missing 5))))))

(deftest concurrent-append-line-is-thread-safe
  (testing "concurrent append-line! calls produce no interleaved or lost lines"
    (let [root (make-tmp-dirs)
          dirs (dirs-for root)
          task (make-task :task-conc-1 "concurrent")
          thread-count 8
          per-thread 50]
      (try
        (persist/open-appender! dirs task)
        (let [futures (doall
                       (for [t (range thread-count)]
                         (future
                           (dotimes [i per-thread]
                             (persist/append-line! (:id task) (str "t" t "-" i))))))]
          (run! deref futures))
        (persist/close-appender! (:id task) (assoc task :status :completed))
        (let [out (persist/output-path dirs (:id task))
              content (slurp out)
              lines (vec (filter seq (str/split-lines content)))]
          (is (= (* thread-count per-thread) (count lines))
              "all lines written, none lost")
          ;; Each line should be exactly one of the expected forms — no
          ;; interleaving within a line.
          (let [expected (set (for [t (range thread-count) i (range per-thread)]
                                (str "t" t "-" i)))]
            (is (= expected (set lines)))))
        (finally
          (persist/close-appender! (:id task) task))))))

(deftest open-appender-truncates-prior-content
  (testing "open-appender! truncates an existing output.log so re-runs start clean"
    (let [root (make-tmp-dirs)
          dirs (dirs-for root)
          task (make-task :task-trunc-1 "truncate")]
      (try
        (persist/open-appender! dirs task)
        (persist/append-line! (:id task) "old-1")
        (persist/close-appender! (:id task) (assoc task :status :completed))
        ;; Second open should truncate.
        (persist/open-appender! dirs (assoc task :status :pending))
        (persist/append-line! (:id task) "new-1")
        (persist/close-appender! (:id task) (assoc task :status :completed))
        (is (= "new-1\n" (slurp (persist/output-path dirs (:id task)))))
        (finally
          (persist/close-appender! (:id task) task))))))

(deftest read-meta-returns-nil-when-missing
  (testing "read-meta returns nil for a task with no meta.edn"
    (let [root (make-tmp-dirs)
          dirs (dirs-for root)]
      (is (nil? (persist/read-meta dirs :task-no-meta))))))

(deftest line-count-test
  (testing "line-count returns the number of lines on disk; 0 for missing/empty files"
    (let [root (make-tmp-dirs)
          dirs (dirs-for root)
          task (make-task :task-lc-1 "line-count")]
      (try
        ;; Missing file.
        (is (= 0 (persist/line-count dirs :task-no-output)))
        ;; Empty file (open + close, no appends).
        (persist/open-appender! dirs task)
        (persist/close-appender! (:id task) (assoc task :status :completed))
        (is (= 0 (persist/line-count dirs (:id task))))
        ;; Re-open + append N; verify count matches.
        (persist/open-appender! dirs (assoc task :status :pending))
        (dotimes [i 17]
          (persist/append-line! (:id task) (str "line-" i)))
        (persist/close-appender! (:id task) (assoc task :status :completed))
        (is (= 17 (persist/line-count dirs (:id task))))
        (finally
          (persist/close-appender! (:id task) task))))))

(deftest task-dir-exists?-probes-without-creating
  (testing "task-dir-exists? reports on-disk claims and never materializes one"
    (let [root (make-tmp-dirs)
          dirs (dirs-for root)]
      ;; No tasks dir at all yet — probe must not create it.
      (is (false? (persist/task-dir-exists? dirs :task-mkq8f3x2a1b))
          "unresolvable / missing tasks dir → false")
      (is (not (.exists (io/file root ".brainyard" "tasks")))
          "probing must not materialize .brainyard/tasks/")
      ;; Claim one, then probe both it and a sibling.
      (.mkdirs (persist/task-dir dirs :task-mkq8f3x2a1b))
      (is (true? (persist/task-dir-exists? dirs :task-mkq8f3x2a1b))
          "existing dir → true")
      (is (false? (persist/task-dir-exists? dirs :task-mkq8f3x2zzz))
          "sibling id → false")
      (is (not (.exists (io/file root ".brainyard" "tasks" "task-mkq8f3x2zzz")))
          "a negative probe leaves no directory behind"))))
