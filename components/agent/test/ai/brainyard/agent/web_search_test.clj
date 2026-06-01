;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.web-search-test
  "Tests for web-search deftool and the underlying tavily-search fn."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.agent.common.tools :as common-tools]
            [ai.brainyard.agent.common.reference :as ref]))

;; ============================================================================
;; web-search deftool
;; ============================================================================

(deftest web-search-deftool-test
  (testing "web-search returns error when no API key configured"
    (with-redefs-fn {#'common-tools/get-tavily-api-key (fn [] nil)}
      (fn []
        (let [result (common-tools/web-search :query "test")]
          (is (contains? result :error))
          (is (.contains (str (:error result)) "TAVILY_API_KEY"))))))

  (testing "web-search passes args to ref/tavily-search"
    (let [captured (atom nil)]
      (with-redefs-fn
        {#'ref/tavily-search
         (fn [query & opts]
           (reset! captured (into {:query query} (apply hash-map opts)))
           {:answer "mocked" :results [{:title "t" :url "u" :content "c" :score 0.9}]})
         #'common-tools/get-tavily-api-key
         (fn [] "fake-key")}
        (fn []
          (let [result (common-tools/web-search :query "clojure latest"
                                                :max-results 3
                                                :search-depth "advanced"
                                                :include-answer false)]
            (is (= "mocked" (:answer result)))
            (is (= 1 (count (:results result))))
            (let [c @captured]
              (is (= "clojure latest" (:query c)))
              (is (= "fake-key" (:api-key c)))
              (is (= 3 (:max-results c)))
              (is (= "advanced" (:search-depth c)))
              (is (= false (:include-answer? c))))))))))

;; ============================================================================
;; tavily-search fn
;; ============================================================================

(deftest tavily-search-test
  (testing "missing api-key returns error"
    (let [r (ref/tavily-search "hello")]
      (is (contains? r :error))
      (is (.contains (str (:error r)) "api-key"))))

  (testing "blank query returns error"
    (let [r (ref/tavily-search "" :api-key "k")]
      (is (contains? r :error))
      (is (.contains (str (:error r)) "query"))))

  (testing "normalizes successful response"
    (with-redefs-fn
      {#'ai.brainyard.agent.common.reference/post-json
       (fn [_url _payload _timeout]
         {:status 200
          :body {:query "hello"
                 :answer "Hi!"
                 :results [{:title "T1" :url "https://a" :content "c1" :score 0.9 :extra "drop"}
                           {:title "T2" :url "https://b" :content "c2" :score 0.8}]}})}
      (fn []
        (let [r (ref/tavily-search "hello" :api-key "k")]
          (is (= "Hi!" (:answer r)))
          (is (= 2 (count (:results r))))
          (let [first-r (first (:results r))]
            (is (= "T1" (:title first-r)))
            (is (= "https://a" (:url first-r)))
            (is (= "c1" (:content first-r)))
            (is (= 0.9 (:score first-r)))
            (is (not (contains? first-r :extra))))))))

  (testing "HTTP error returns :error"
    (with-redefs-fn
      {#'ai.brainyard.agent.common.reference/post-json
       (fn [_url _payload _timeout]
         {:status 401 :body {:error "Unauthorized"}})}
      (fn []
        (let [r (ref/tavily-search "hello" :api-key "k")]
          (is (contains? r :error))
          (is (.contains (str (:error r)) "401")))))))
