;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.status-bar-test
  "format-status chip rendering — drift chip in particular (Phase 2a')."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.agent-tui.layout :as layout]))

(defn- visible
  "Strip ANSI escapes so assertions are tied to user-visible text, not
   color codes that drift across terminals."
  [s]
  (str/replace s #"\[[0-9;]*m" ""))

(deftest no-drift-chip-when-not-drifted
  (let [out (visible (layout/format-status {:status :idle}))]
    (is (not (re-find #"drifted" out))
        "no drift chip when :drifted? is falsy / absent")))

(deftest drift-chip-appears-when-drifted
  (let [out (visible (layout/format-status
                      {:status :idle :drifted? true :drift-count 3}))]
    (is (re-find #"drifted \(3\)" out)
        "chip shows drift count parenthetical when count is positive")
    (is (re-find #"^idle" out)
        "status keeps :idle prefix")
    (is (re-find #"drifted .* 0 calls" out)
        "drift chip sits between status segments and calls counter")))

(deftest drift-chip-without-count
  (let [out (visible (layout/format-status
                      {:status :idle :drifted? true}))]
    (is (re-find #"drifted" out))
    (is (not (re-find #"drifted \(" out))
        "no parenthetical when count missing / zero")))

(deftest drift-chip-coexists-with-tasks-and-queue
  (let [out (visible (layout/format-status
                      {:status :running
                       :tasks-running 2 :queue-count 5
                       :drifted? true :drift-count 1
                       :calls 4 :tokens 1234 :cost 0.5}))]
    (is (re-find #"running" out))
    (is (re-find #"5 queued" out))
    (is (re-find #"2 tasks" out))
    (is (re-find #"drifted \(1\)" out))
    (is (re-find #"4 calls" out))))
