;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.sqlite-test
  "Tests for path normalization in sqlite.clj — specifically the
  expand-path helper that resolves a leading `~` (which JDBC does NOT
  expand) to the user's home directory before SQLite sees it."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [ai.brainyard.memory.core.sqlite :as sqlite])
  (:import [java.util UUID]))

(def ^:private home (System/getProperty "user.home"))

;; ============================================================================
;; expand-path
;; ============================================================================

(deftest expand-path-test
  (testing "lone ~ expands to user.home"
    (is (= home (sqlite/expand-path "~"))))

  (testing "~/x expands to user.home/x"
    (is (= (str home "/.brainyard/memory.db")
           (sqlite/expand-path "~/.brainyard/memory.db")))
    (is (= (str home "/data")
           (sqlite/expand-path "~/data"))))

  (testing "non-tilde paths are returned unchanged"
    (is (= "/abs/path" (sqlite/expand-path "/abs/path")))
    (is (= "relative/path" (sqlite/expand-path "relative/path")))
    (is (= ":memory:" (sqlite/expand-path ":memory:")))
    (is (= ":memory:?cache=shared" (sqlite/expand-path ":memory:?cache=shared"))))

  (testing "tilde NOT at the start is returned unchanged"
    (is (= "/some/~/path" (sqlite/expand-path "/some/~/path")))
    (is (= "name~suffix" (sqlite/expand-path "name~suffix"))))

  (testing "non-strings pass through"
    (is (nil? (sqlite/expand-path nil)))))

;; ============================================================================
;; db-path
;; ============================================================================

(deftest db-path-expands-tilde-test
  (testing "db-path expands ~ in base-path"
    (is (= (str home "/data/memory/u1.db")
           (sqlite/db-path "~/data/memory" "u1"))))

  (testing "db-path leaves absolute base-path alone"
    (is (= "/var/data/u1.db"
           (sqlite/db-path "/var/data" "u1")))))

;; ============================================================================
;; create-datasource — integration: confirms a file is created at the
;; ~-expanded location, not at literal "./~/...".
;; ============================================================================

(deftest create-datasource-honors-tilde-test
  (testing "create-datasource with ~/<path> writes under HOME, not under CWD"
    (let [rel-name (str ".brainyard-test-" (UUID/randomUUID) ".db")
          tilde-path (str "~/" rel-name)
          expected   (io/file home rel-name)
          stray      (io/file "~" rel-name)]
      (try
        (let [ds (sqlite/create-datasource tilde-path)]
          (is (.exists expected)
              (str "DB file should exist at " (.getAbsolutePath expected)))
          (is (not (.exists stray))
              "No literal `~` directory should be created under CWD")
          (when (instance? java.sql.Connection ds)
            (.close ^java.sql.Connection ds)))
        (finally
          (.delete expected)
          ;; If a literal ~ folder was created, fail loudly but still
          ;; clean up so the test is self-contained.
          (when (.exists stray)
            (.delete stray))
          (let [tilde-dir (io/file "~")]
            (when (and (.exists tilde-dir) (.isDirectory tilde-dir)
                       (zero? (count (.listFiles tilde-dir))))
              (.delete tilde-dir))))))))
