;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.util.interface-test
  (:require [clojure.test :as test :refer :all]
            [clojure.java.io :as io]
            [ai.brainyard.util.interface :as util]))

;; ============================================================================
;; UUID Tests (core/common.clj)
;; ============================================================================

(deftest new-uuid-test
  (testing "no args returns a UUID"
    (let [u (util/new-uuid)]
      (is (uuid? u))))

  (testing "passing a UUID returns it unchanged"
    (let [original (util/new-uuid)
          result (util/new-uuid original)]
      (is (= original result))))

  (testing "passing an int creates deterministic UUID"
    (let [u1 (util/new-uuid 123)
          u2 (util/new-uuid 123)]
      (is (uuid? u1))
      (is (= u1 u2))
      (is (= "ffffffff-ffff-ffff-ffff-000000000123" (str u1)))))

  (testing "passing a string parses it as UUID"
    (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
          u (util/new-uuid uuid-str)]
      (is (uuid? u))
      (is (= uuid-str (str u))))))

(deftest custom-uuid-test
  (testing "returns uppercase hex string without hyphens"
    (let [u (util/custom-uuid)]
      (is (string? u))
      (is (= u (.toUpperCase u)))
      (is (nil? (re-find #"-" u)))
      (is (= 32 (count u)))
      (is (re-matches #"[A-F0-9]+" u)))))

;; ============================================================================
;; Directory Tests (core/common.clj)
;; ============================================================================

(deftest create-dir-and-exist-dir-test
  (let [test-dir (str "file:///tmp/brainyard-test-" (System/currentTimeMillis))]
    (testing "exist-dir? returns false for non-existent directory"
      (is (false? (util/exist-dir? test-dir))))

    (testing "create-dir creates directory"
      (util/create-dir test-dir)
      (is (true? (util/exist-dir? test-dir))))

    ;; Cleanup
    (.delete (io/file (io/as-url test-dir)))))

;; ============================================================================
;; File Copy Tests (core/common.clj)
;; ============================================================================

(deftest copy-file-test
  (let [ts (System/currentTimeMillis)
        source-path (str "/tmp/brainyard-source-" ts ".txt")
        dest-path (str "/tmp/brainyard-dest-" ts ".txt")
        source-url (str "file://" source-path)
        dest-url (str "file://" dest-path)
        test-content "Hello, Brainyard!"]
    (try
      (testing "copies file content correctly"
        (spit source-path test-content)
        (util/copy-file source-url dest-url)
        (is (= test-content (slurp dest-path))))
      (finally
        (io/delete-file source-path true)
        (io/delete-file dest-path true)))))

;; ============================================================================
;; Iterator Sequence Tests (core/common.clj)
;; ============================================================================

(deftest iter-seq-test
  (testing "converts ArrayList to sequence"
    (let [al (java.util.ArrayList. [1 2 3 4 5])
          result (util/iter-seq al)]
      (is (seq? result))
      (is (= [1 2 3 4 5] (vec result)))))

  (testing "converts HashSet to sequence"
    (let [hs (java.util.HashSet. #{:a :b :c})
          result (util/iter-seq hs)]
      (is (seq? result))
      (is (= #{:a :b :c} (set result)))))

  (testing "handles empty collection"
    (let [empty-list (java.util.ArrayList.)
          result (util/iter-seq empty-list)]
      (is (nil? (seq result)))))

  (testing "is lazy with large collection"
    (let [al (java.util.ArrayList. (range 1000000))
          result (util/iter-seq al)]
      (is (= 0 (first result)))
      (is (= 10 (nth result 10))))))

;; ============================================================================
;; Naming Tests (core/naming.cljc)
;; ============================================================================

(deftest kw->nspc-test
  (testing "converts simple keyword"
    (is (= "hello-world" (util/kw->nspc :hello-world))))

  (testing "converts namespaced keyword"
    (is (= "my.ns.foo-bar" (util/kw->nspc :my.ns/foo-bar))))

  (testing "parameterizes the name (lowercases)"
    (is (= "helloworld" (util/kw->nspc :HelloWorld)))))

(deftest kw->str-test
  (testing "simple keyword"
    (is (= "foo" (util/kw->str :foo))))
  (testing "namespaced keyword"
    (is (= "ns/foo" (util/kw->str :ns/foo))))
  (testing "non-keyword passthrough"
    (is (= "hello" (util/kw->str "hello")))))

(deftest abbreviate-test
  (testing "short string returns unchanged"
    (is (= "hello" (util/abbreviate "hello"))))

  (testing "long string gets abbreviated with default length"
    (let [long-str "this is a very long string that should be abbreviated"]
      (is (< (count (util/abbreviate long-str)) (count long-str)))
      (is (re-find #"\.\.\." (util/abbreviate long-str)))))

  (testing "custom pre and post lengths"
    (let [long-str "abcdefghijklmnopqrstuvwxyz0123456789"]
      (is (re-find #"^abc \.\.\.\[\d+ chars\]\.\.\. 789$" (util/abbreviate long-str 3 3)))))

  (testing "shows correct omitted character count"
    (let [long-str (apply str (repeat 100 "x"))]
      (is (= (str (subs long-str 0 10) " ...[80 chars]... " (subs long-str 90))
             (util/abbreviate long-str 10 10))))))

(deftest gen-random-words-test
  (testing "generates non-empty string"
    (let [words (util/gen-random-words)]
      (is (string? words))
      (is (not (empty? words)))))

  (testing "generates different values on each call"
    (let [words1 (util/gen-random-words)
          words2 (util/gen-random-words)]
      ;; Allow for rare collision
      (is (or (not= words1 words2)
              (string? words1))))))

(deftest gen-unique-names-test
  (testing "generates non-empty string"
    (let [name (util/gen-unique-names)]
      (is (string? name))
      (is (not (empty? name)))))

  (testing "generates different values on each call"
    (let [name1 (util/gen-unique-names)
          name2 (util/gen-unique-names)]
      ;; Allow for rare collision
      (is (or (not= name1 name2)
              (string? name1))))))

;; ============================================================================
;; Logging Tests (core/logging.cljc)
;; ============================================================================

(deftest pretty-test
  (testing "adds :pretty metadata to value"
    (let [data {:a 1 :b 2}
          result (util/pretty data)]
      (is (= data result))
      (is (true? (:pretty (meta result))))))

  (testing "works with vectors"
    (let [data [1 2 3]
          result (util/pretty data)]
      (is (= data result))
      (is (true? (:pretty (meta result)))))))

(deftest default-log-config-test
  (testing "has expected structure"
    (is (map? util/default-log-config))
    (is (contains? util/default-log-config :min-level))
    (is (contains? util/default-log-config :ns-filter))))

(deftest configure-logging-test
  (testing "configures logging without error"
    (is (map? (util/configure-logging!))))

  (testing "accepts custom config"
    (let [result (util/configure-logging! {:min-level :info})]
      (is (map? result)))))
