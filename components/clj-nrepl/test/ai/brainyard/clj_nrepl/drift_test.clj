;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
(ns ai.brainyard.clj-nrepl.drift-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.clj-nrepl.core.drift :as drift]))

(use-fixtures :each (fn [t] (drift/clear!) (t) (drift/clear!)))

(deftest no-drift-by-default
  (is (not (drift/drifted?)))
  (is (zero? (drift/marker-count)))
  (is (= [] (drift/markers))))

(deftest mark-records-marker
  (drift/mark! "sess-1" "(def x 1)")
  (is (drift/drifted?))
  (is (= 1 (drift/marker-count)))
  (let [m (first (drift/markers))]
    (is (= "sess-1" (:session m)))
    (is (re-find #"def x 1" (:code-preview m)))
    (is (pos? (:timestamp m)))
    (is (= :mutating-eval (:reason m)))))

(deftest preview-truncates-long-code
  (drift/mark! "s" (apply str (repeat 200 "a")))
  (let [m (first (drift/markers))]
    (is (<= (count (:code-preview m)) 80))))

(deftest clear-resets-and-returns-prior
  (drift/mark! "s" "(def x 1)")
  (drift/mark! "s" "(def y 2)")
  (is (= 2 (drift/clear!)))
  (is (not (drift/drifted?))))

(deftest reason-keyword-recorded
  (drift/mark! "s" "(def x 1)" :promoted)
  (is (= :promoted (-> (drift/markers) first :reason))))
