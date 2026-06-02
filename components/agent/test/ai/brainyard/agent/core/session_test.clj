;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.session-test
  "Tests for session-level counters.

   :total-turns is the cumulative ask count across the root agent and
   every sub-agent in an agent-session. Per-agent :turn-id lives on
   each agent's :st-memory-init and is bumped by `ask` in agent.clj —
   that integration is exercised by the coact_agent and integration
   test suites."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent.core.session :as session]))

(deftest create-session-defaults-total-turns-to-zero
  (let [s (session/create-session "s-1" "u-1")]
    (is (= 0 (:total-turns s)))
    (is (not (contains? s :turn-id))
        "Session no longer carries :turn-id; per-agent counters live on st-memory-init.")))

(deftest inc-total-turns-is-monotonic
  (let [!s (atom (session/create-session "s-1" "u-1"))]
    (is (= 1 (session/inc-total-turns! !s)))
    (is (= 2 (session/inc-total-turns! !s)))
    (is (= 3 (session/inc-total-turns! !s)))
    (is (= 3 (:total-turns @!s)))))

(deftest inc-total-turns-handles-missing-key
  (testing "Robust against sessions persisted before this counter existed."
    (let [!s (atom {:session-id "legacy" :user-id "u"})]
      (is (= 1 (session/inc-total-turns! !s))
          "fnil-inc treats nil as 0 and bumps to 1"))))
