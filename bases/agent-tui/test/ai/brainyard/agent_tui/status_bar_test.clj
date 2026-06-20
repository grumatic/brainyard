;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.status-bar-test
  "format-status rendering. The runtime-drift chip was removed along with
   the clj-nrepl drift machinery — the status bar no longer shows it."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [ai.brainyard.agent-tui.layout :as layout]))

(defn- visible
  "Strip ANSI escapes so assertions are tied to user-visible text, not
   color codes that drift across terminals."
  [s]
  (str/replace s #"\[[0-9;]*m" ""))

(deftest no-drift-chip-ever
  (is (not (re-find #"drifted" (visible (layout/format-status {:status :idle}))))
      "drift chip is gone — no 'drifted' text in the status bar")
  ;; even if a stale caller passes the old keys, nothing renders them
  (is (not (re-find #"drifted"
                    (visible (layout/format-status
                              {:status :idle :drifted? true :drift-count 3}))))))

(deftest segments-render-without-drift
  (let [out (visible (layout/format-status
                      {:status :running
                       :tasks-running 2 :queue-count 5
                       :calls 4 :tokens 1234 :cost 0.5}))]
    (is (re-find #"running" out))
    (is (re-find #"5 queued" out))
    (is (re-find #"2 tasks" out))
    (is (re-find #"4 calls" out))))
