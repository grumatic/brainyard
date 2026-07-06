;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.subagents-block-test
  "Unit tests for the subagents-block render helpers: the activity summary that
   now rides the (frozen) summary line, and the compact quiet-mode milestone
   lines that stand in for the suppressed animated block."
  (:require [ai.brainyard.agent-tui.session :as session]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- strip-ansi [s]
  (str/replace (or s "") #"\x1b\[[0-9;]*m" ""))

(def ^:private sub-activity-summary   #'session/sub-activity-summary)
(def ^:private quiet-milestone        #'session/quiet-sub-milestone-line)
(def ^:private render-sub-agent-lines #'session/render-sub-agent-lines)

(deftest sub-activity-summary-test
  (testing "counts render as a ` · N tools · M code-blocks` segment, singular-aware"
    (is (= " · 5 tools · 2 code-blocks" (sub-activity-summary 5 2)))
    (is (= " · 1 tool · 1 code-block"   (sub-activity-summary 1 1)))
    (is (= " · 3 tools"                 (sub-activity-summary 3 0)))
    (is (= " · 4 code-blocks"           (sub-activity-summary 0 4))))
  (testing "no activity ⇒ nil (no empty segment)"
    (is (nil? (sub-activity-summary 0 0)))
    (is (nil? (sub-activity-summary nil nil)))))

(deftest render-sub-agent-lines-done-carries-activity-test
  (testing "a finished sub-agent's frozen record keeps WHAT it did on one summary line"
    (let [lines (render-sub-agent-lines
                 {:agent-id :research-agent/abc :defagent-id :research-agent
                  :status :done :start-time 0 :end-time 12000
                  :tools-used 5 :code-blocks-used 2 :depth 0
                  :iter-rollup {:total-tokens 3200}}
                 "●")
          text  (strip-ansi (first lines))]
      (is (= 1 (count lines)) "done collapses to a single summary line")
      (is (str/includes? text "done"))
      (is (str/includes? text "· 5 tools · 2 code-blocks")
          "activity totals survive to :done on the summary line"))))

(deftest render-sub-agent-lines-running-has-no-footer-test
  (testing "a running sub-agent shows detail lines + activity on the summary, no totals footer"
    (let [lines (render-sub-agent-lines
                 {:agent-id :research-agent/abc :defagent-id :research-agent
                  :status :running :start-time (System/currentTimeMillis)
                  :tools-used 5 :code-blocks-used 2 :depth 0
                  :recent-tools ["bash" "grep"] :recent-code [{:lang "clj" :lines 12}]}
                 "●")
          joined (strip-ansi (str/join "\n" lines))]
      (is (str/includes? joined "· 5 tools · 2 code-blocks") "activity on summary line")
      (is (str/includes? joined "tools: bash · grep") "recent-tools detail line kept")
      (is (not (str/includes? joined "tools used"))
          "the redundant `(+N tools used)` footer is gone"))))

(deftest quiet-sub-milestone-line-test
  (testing "started line — marker + [type], no activity/elapsed yet"
    (let [s (strip-ansi (quiet-milestone {:agent-id :research-agent/abc
                                          :defagent-id :research-agent
                                          :depth 0 :kind :started}))]
      (is (str/includes? s "[research-agent]"))
      (is (str/includes? s "started"))
      (is (not (str/includes? s "·")) "no activity segment on start")))
  (testing "done line carries the activity summary and elapsed"
    (let [s (strip-ansi (quiet-milestone {:agent-id :research-agent/abc
                                          :defagent-id :research-agent :depth 0
                                          :kind :done :tools-used 5 :code-blocks-used 2
                                          :elapsed-ms 12000}))]
      (is (str/includes? s "done"))
      (is (str/includes? s "· 5 tools · 2 code-blocks"))
      (is (str/includes? s "12s"))))
  (testing "error line for a nested sub-agent is depth-indented with ↳"
    (let [s (strip-ansi (quiet-milestone {:agent-id :edit-agent/xy :defagent-id :edit-agent
                                          :depth 1 :kind :error :tools-used 1
                                          :code-blocks-used 0 :elapsed-ms 800}))]
      (is (str/starts-with? s "  ↳ ") "depth 1 → two-space indent + ↳")
      (is (str/includes? s "[edit-agent]"))
      (is (str/includes? s "error"))
      (is (str/includes? s "· 1 tool")))))
