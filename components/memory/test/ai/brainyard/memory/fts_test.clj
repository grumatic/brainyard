;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.fts-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.memory.core.fts :as fts]))

(deftest normalize-fts-query-test
  (testing "basic normalization"
    (is (= "hello OR world" (fts/normalize-fts-query "hello world")))
    (is (= "finops" (fts/normalize-fts-query "finops"))))

  (testing "special characters stripped"
    (is (= "AWS OR EC2 OR costs" (fts/normalize-fts-query "AWS #EC2 costs")))
    (is (= "user OR email OR com" (fts/normalize-fts-query "user@email.com")))
    (is (= "AWS OR EC2 OR r5 OR xlarge" (fts/normalize-fts-query "AWS EC2 (r5.xlarge)"))))

  (testing "FTS5 operators neutralized"
    (is (= "not OR important OR and OR urgent"
           (fts/normalize-fts-query "NOT important AND urgent"))))

  (testing "empty/invalid queries return nil"
    (is (nil? (fts/normalize-fts-query "   ")))
    (is (nil? (fts/normalize-fts-query "###")))
    (is (nil? (fts/normalize-fts-query nil))))

  (testing ":match modes"
    ;; :or is the default and joins with OR
    (is (= "deploy OR production" (fts/normalize-fts-query "deploy production")))
    (is (= "deploy OR production" (fts/normalize-fts-query "deploy production" :or)))
    ;; :and joins with whitespace (FTS5 implicit AND)
    (is (= "deploy production" (fts/normalize-fts-query "deploy production" :and)))
    ;; :phrase wraps in double quotes for consecutive matching
    (is (= "\"deploy production\"" (fts/normalize-fts-query "deploy production" :phrase)))
    ;; Single-word queries return verbatim regardless of mode
    (is (= "deploy" (fts/normalize-fts-query "deploy" :or)))
    (is (= "deploy" (fts/normalize-fts-query "deploy" :and)))
    (is (= "deploy" (fts/normalize-fts-query "deploy" :phrase)))))

(deftest extract-keywords-test
  (testing "extracts distinctive terms"
    (let [keywords (fts/extract-keywords "AWS EC2 costs are high, need to optimize EC2 spending")]
      (is (vector? keywords))
      (is (pos? (count keywords)))
      ;; "ec2" should appear since it's repeated
      (is (some #{"ec2"} keywords))))

  (testing "filters stop words"
    (let [keywords (fts/extract-keywords "the quick brown fox jumps over the lazy dog")]
      (is (not (some #{"the" "over"} keywords)))
      (is (some #{"quick" "brown" "jumps" "lazy"} keywords))))

  (testing "respects min-length"
    (let [keywords (fts/extract-keywords "a bb ccc dddd" :min-length 4)]
      (is (= ["dddd"] keywords))))

  (testing "respects max-keywords"
    (let [keywords (fts/extract-keywords "alpha beta gamma delta epsilon zeta"
                                         :max-keywords 3)]
      (is (<= (count keywords) 3))))

  (testing "empty text returns empty vector"
    (is (= [] (fts/extract-keywords "")))
    (is (= [] (fts/extract-keywords nil)))))
