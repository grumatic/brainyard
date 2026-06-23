;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.timeutil-test
  (:require [ai.brainyard.agent.core.timeutil :as tu]
            [clojure.test :refer [deftest is testing]]))

(def instant-keys #{:iso :epoch-ms :epoch-sec :tz-iana :tz-offset-minutes :day-of-week})

(deftest now-map-canonical-shape
  (testing "now-map returns the canonical instant map in the requested zone"
    (let [m (tu/now-map "Asia/Seoul")]
      (is (every? m instant-keys))
      (is (re-find #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}" (:iso m)))
      (is (= "Asia/Seoul" (:tz-iana m)))
      (is (= 540 (:tz-offset-minutes m)))
      (is (integer? (:epoch-ms m)))
      (is (integer? (:epoch-sec m)))
      (is (string? (:day-of-week m)))))
  (testing "blank/nil zone falls back to system default without error"
    (is (every? (tu/now-map nil) instant-keys))
    (is (every? (tu/now-map "") instant-keys))))

(deftest add-map-from-fixed-base
  (testing "epoch-ms base + plain hour/day shift is exact and keeps the shape"
    ;; 2026-01-01T00:00:00Z = 1767225600000 ms
    (let [base 1767225600000
          m    (tu/add-map base "UTC" {:days 1 :hours 2})]
      (is (every? m instant-keys))
      (is (= "2026-01-02T02:00:00Z" (:iso m)))
      (is (= (+ base (* 26 60 60 1000)) (:epoch-ms m)))))
  (testing "ISO-string base, negative deltas, and calendar month math"
    (is (= "2026-03-31T12:00:00Z"
           (:iso (tu/add-map "2026-01-31T12:00:00Z" "UTC" {:months 2}))))
    (is (= "2025-12-31T12:00:00Z"
           (:iso (tu/add-map "2026-01-31T12:00:00Z" "UTC" {:months -1}))))))

(deftest add-map-dst-is-calendar-correct
  (testing "+1 day across a US spring-forward keeps the wall-clock hour (23h elapsed)"
    ;; 2026-03-08 02:00 EST→EDT spring forward in America/New_York.
    (let [before "2026-03-07T12:00:00-05:00"
          m      (tu/add-map before "America/New_York" {:days 1})]
      ;; Same wall-clock 12:00, but now EDT (-04:00) — not a naive +24h.
      (is (= "2026-03-08T12:00:00-04:00" (:iso m))))))

(deftest diff-map-shape-and-direction
  (testing "future direction with signed totals and humanized magnitude"
    (let [from "2026-01-01T00:00:00Z"
          to   "2026-01-03T04:15:00Z"
          d    (tu/diff-map from to "UTC")]
      (is (= "future" (:direction d)))
      (is (= "2d 4h 15m" (:humanized d)))
      (is (= 2 (:days d)))
      (is (= (-> 2 (* 24) (+ 4) (* 60) (+ 15)) (:minutes d)))
      (is (pos? (:ms d)))
      ;; :from / :to compose with the canonical instant shape.
      (is (every? (:from d) instant-keys))
      (is (every? (:to d) instant-keys))
      (is (= {:years 0 :months 0 :days 2 :hours 4 :minutes 15 :seconds 0}
             (:calendar d)))))
  (testing "past direction yields negative totals; magnitude stays positive"
    (let [d (tu/diff-map "2026-01-03T00:00:00Z" "2026-01-01T00:00:00Z" "UTC")]
      (is (= "past" (:direction d)))
      (is (neg? (:ms d)))
      (is (= "2d" (:humanized d)))))
  (testing "equal instants report :same and 0s"
    (let [d (tu/diff-map "2026-01-01T00:00:00Z" "2026-01-01T00:00:00Z" "UTC")]
      (is (= "same" (:direction d)))
      (is (= "0s" (:humanized d)))
      (is (zero? (:ms d))))))

(deftest bad-zone-throws
  (testing "an unknown zone throws (tools convert this to {:error ...})"
    (is (thrown? Exception (tu/now-map "Not/AZone")))))
