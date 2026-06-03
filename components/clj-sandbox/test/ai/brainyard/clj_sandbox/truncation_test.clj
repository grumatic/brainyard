;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.truncation-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.clj-sandbox.core.truncation :as trunc]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(deftest truncate-to-file-passthrough-test
  (testing "text under limit is returned unchanged"
    (is (= "short" (trunc/truncate-to-file "short" 100 "test")))
    (is (= nil (trunc/truncate-to-file nil 100 "test")))
    (is (= "" (trunc/truncate-to-file "" 100 "test")))))

(deftest truncate-to-file-saves-original-test
  (testing "text over limit is truncated with recovery path"
    (let [big (apply str (repeat 1000 "x"))
          result (trunc/truncate-to-file big 200 "test-class" :label "test data")]
      ;; Result should contain the head portion
      (is (str/starts-with? result (subs big 0 10)))
      ;; Result should contain truncation notice
      (is (str/includes? result "TRUNCATED"))
      (is (str/includes? result "test data TRUNCATED"))
      (is (str/includes? result (str (count big))))
      ;; Result should contain a recovery path. The temp dir is project-scoped
      ;; (<project>/.brainyard/temp/clj-sandbox/truncation/) when the agent
      ;; component resolves, else the /tmp fallback — either way the content
      ;; `class` is a path segment.
      (is (str/includes? result "read-file"))
      (is (str/includes? result "/test-class/"))
      (is (str/includes? result "test-class"))
      ;; Extract the path and verify the file exists with original content
      (let [path-match (re-find #"Full content saved to: ([^\s]+)" result)
            path (second path-match)]
        (is (some? path) "Recovery path should be present")
        (when path
          (is (.exists (io/file path)) "Temp file should exist")
          (is (= big (slurp path)) "Temp file should contain original untruncated text"))))))

(deftest truncate-to-file-head-tail-test
  (testing "result contains both head and tail of original"
    (let [text (str (apply str (repeat 100 "H"))   ;; 100 H's
                    (apply str (repeat 100 "M"))   ;; 100 M's
                    (apply str (repeat 100 "T")))  ;; 100 T's = 300 total
          result (trunc/truncate-to-file text 100 "test")]
      ;; Head (70% of 100 = 70 chars)
      (is (str/starts-with? result "HHHHH"))
      ;; Tail (20% of 100 = 20 chars from end = last 20 T's)
      (is (str/ends-with? result "TTTTTTTTTTTTTTTTTTTT")))))

(deftest truncate-to-file-working-dir-binding-test
  (testing "*working-dir* namespaces the /tmp fallback path when no project dir resolves"
    ;; The project-scoped path is preferred; *working-dir* only drives the
    ;; legacy /tmp fallback, so force that branch by making the project-dir
    ;; resolver return nil.
    (with-redefs-fn {#'trunc/resolve-project-clj-sandbox-dir (constantly nil)}
      (fn []
        (binding [trunc/*working-dir* "/test/project"]
          (let [big (apply str (repeat 500 "x"))
                result (trunc/truncate-to-file big 100 "bound-test")]
            (is (str/includes? result "/tmp/test_project/"))))))))
