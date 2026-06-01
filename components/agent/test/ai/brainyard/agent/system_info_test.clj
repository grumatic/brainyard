;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.system-info-test
  (:require [ai.brainyard.agent.core.system-info :as sys-info]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.time Instant ZoneId)))

(deftest build-system-info-section-renders-all-subsections
  (testing "System info has Host / Workspace / LLM / Session subsections"
    (let [s (sys-info/build-system-info-section)]
      (is (str/starts-with? s "## System Information"))
      (is (str/includes? s "### Host"))
      (is (str/includes? s "### Workspace"))
      (is (str/includes? s "### LLM"))
      (is (str/includes? s "### Session"))
      (is (str/includes? s "- OS: "))
      (is (str/includes? s "- Working directory: "))
      (is (str/includes? s "- Timezone: ")))))

(deftest build-system-info-section-stable-across-calls
  (testing "Two consecutive calls with no args produce byte-identical output
            (cache-stability property)"
    (let [a (sys-info/build-system-info-section)
          b (sys-info/build-system-info-section)]
      (is (= a b)
          "system-info must be stable across calls — it sits above the
           cross-turn cache breakpoint"))))

(deftest build-system-info-without-agent-has-fallbacks
  (testing "Missing agent — LLM / Session rows fall back to <unset>/<unknown>"
    (let [s (sys-info/build-system-info-section)]
      (is (str/includes? s "Provider: <unset>"))
      (is (str/includes? s "Agent: <unknown>"))
      (is (str/includes? s "Session: <unknown>"))
      (is (str/includes? s "(root, depth 0)")))))

(deftest build-system-info-with-depth
  (testing "Non-zero depth surfaces in the Agent row"
    (let [s (sys-info/build-system-info-section :depth 2)]
      (is (str/includes? s "(depth 2)"))
      (is (not (str/includes? s "(root, depth 0)"))))))

(deftest build-system-info-parent-row-test
  (testing "Parent row appears only when depth > 0 AND parent-agent-id is set (M9)"
    (let [s-root (sys-info/build-system-info-section
                  :depth 0 :parent-agent-id :plan-agent/p1)
          s-sub  (sys-info/build-system-info-section
                  :depth 1 :parent-agent-id :plan-agent/p1)
          s-no-id (sys-info/build-system-info-section :depth 2)]
      ;; Root agent (depth 0) — Parent row suppressed even if id passed.
      (is (not (str/includes? s-root "- Parent: ")))
      ;; Sub-agent with id — Parent row rendered.
      (is (str/includes? s-sub "- Parent: :plan-agent/p1"))
      ;; Sub-agent without id — Parent row suppressed.
      (is (not (str/includes? s-no-id "- Parent: "))))))

(deftest build-turn-info-section-with-explicit-clock
  (testing "Explicit :now and :tz produce the expected ISO timestamp + DOW"
    (let [;; 2026-05-16T05:32:17Z is a Saturday
          now (Instant/parse "2026-05-16T05:32:17Z")
          tz  (ZoneId/of "Asia/Seoul")
          s   (sys-info/build-turn-info-section
               :turn-id 3 :total-turns 7 :now now :tz tz)]
      (is (str/starts-with? s "## Turn"))
      (is (str/includes? s "2026-05-16T14:32:17+09:00"))
      (is (str/includes? s "(Saturday)"))
      (is (str/includes? s "- Turn: 3 (session total: 7)")))))

(deftest build-turn-info-section-turn-id-increments
  (testing "Different turn-ids produce different output"
    (let [now (Instant/parse "2026-05-16T05:32:17Z")
          tz  (ZoneId/of "UTC")
          s1  (sys-info/build-turn-info-section :turn-id 1 :total-turns 1 :now now :tz tz)
          s2  (sys-info/build-turn-info-section :turn-id 2 :total-turns 2 :now now :tz tz)]
      (is (str/includes? s1 "Turn: 1 (session total: 1)"))
      (is (str/includes? s2 "Turn: 2 (session total: 2)"))
      (is (not= s1 s2)))))

(deftest build-turn-info-section-defaults
  (testing "Defaults: turn=1, total=turn-id, current wall-clock"
    (let [s (sys-info/build-turn-info-section)]
      (is (str/starts-with? s "## Turn"))
      (is (str/includes? s "Turn: 1 (session total: 1)"))
      ;; ISO timestamp should be present and parseable.
      (is (re-find #"- Now: \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[\.\d]*[+-]\d{2}:\d{2}" s)))))

(deftest now-snapshot-shape
  (testing "now-snapshot returns the three documented keys"
    (let [m (sys-info/now-snapshot)]
      (is (string? (:wall-time-iso m)))
      (is (string? (:tz-iana m)))
      (is (integer? (:tz-offset-minutes m)))
      ;; ISO timestamp matches the expected shape.
      (is (re-find #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}" (:wall-time-iso m))))))
