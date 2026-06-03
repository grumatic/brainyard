;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.tab-strip-test
  "Tests for the multi-session tab strip: monotonic root-tab labels
   (`mainN`) and the render shape produced by `format-tab-strip` —
   ` <label>` per tab (no id prefix), suffixed-without-space markers
   (`*` / `●` for active, `?` for unread), and `↓` glyph for
   `:session-type :output` tabs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent-tui.sessions :as sessions]))

(defn- plain
  "Strip ANSI escape sequences for easier assertions on render shape."
  [^String s]
  (str/replace s #"\033\[[0-9;]*m" ""))

(use-fixtures :each
  (fn [t]
    (sessions/reset-sessions!)
    (t)
    (sessions/reset-sessions!)))

(deftest next-root-tab-label-sequence
  (testing "produces main0, main1, main2 in order"
    (is (= "main0" (sessions/next-root-tab-label!)))
    (is (= "main1" (sessions/next-root-tab-label!)))
    (is (= "main2" (sessions/next-root-tab-label!))))
  (testing "reset-sessions! resets the counter back to 0"
    (sessions/reset-sessions!)
    (is (= "main0" (sessions/next-root-tab-label!)))))

(deftest format-tab-strip-render-shape
  (testing "single root tab: `main0*` (active, idle, no leading space on marker)"
    (sessions/create-session! {:id 0
                               :label (sessions/next-root-tab-label!)
                               :skip-agent-creation true})
    (is (= " main0*" (plain (sessions/format-tab-strip)))))
  (testing "second root: `main1` (background, idle — no marker)"
    (sessions/create-session! {:id 1
                               :label (sessions/next-root-tab-label!)
                               :skip-agent-creation true})
    (let [out (plain (sessions/format-tab-strip))]
      (is (str/includes? out " main0*"))
      (is (str/includes? out " main1"))
      (is (not (str/includes? out " main1*")))
      (is (not (str/includes? out " main1?")))))
  (testing "sub-output inherits root's label and appends bare `↓`"
    (sessions/create-session! {:id 2
                               :label "main0"           ; inherited from root 0
                               :session-type :output
                               :sub-output-of 0
                               :skip-agent-creation true})
    (sessions/create-session! {:id 3
                               :label "main1"           ; inherited from root 1
                               :session-type :output
                               :sub-output-of 1
                               :skip-agent-creation true})
    (let [out (plain (sessions/format-tab-strip))]
      (is (str/includes? out " main0↓"))
      (is (str/includes? out " main1↓"))
      ;; no tab-id suffix on the ↓ glyph itself
      (is (not (str/includes? out "↓0")))
      (is (not (str/includes? out "↓1")))))
  (testing "background unread renders `?` suffixed without space"
    ;; Flip the unread flag on tab 1.
    (sessions/update-session! 1 assoc :has-unread? true)
    (let [out (plain (sessions/format-tab-strip))]
      (is (str/includes? out " main1?")))))
