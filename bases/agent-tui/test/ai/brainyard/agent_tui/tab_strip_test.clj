;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.tab-strip-test
  "Tests for the multi-session tab strip: monotonic root-tab labels
   (`mainN`) and the render shape produced by `format-tab-strip` —
   ` <label>` per tab (no id prefix), suffixed-without-space markers
   (`*` / `●` for active, `?` for unread), and `↓` glyph for
   `:session-type :output` tabs.

   Also covers `format-session-list` (`/session tabs`), whose first column is
   the tab id `/session switch <idx>` takes."
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

(deftest next-root-tab-label-skips-in-use
  (testing "skips mainN labels already held by a live session, so multiple alive tabs never collide"
    ;; Two live tabs labelled main0 and main2 (gap at main1) — e.g. a resumed
    ;; session carrying a persisted mainN while the counter restarted at 0.
    (sessions/create-session! {:id 0 :label "main0" :skip-agent-creation true})
    (sessions/create-session! {:id 1 :label "main2" :skip-agent-creation true})
    ;; counter starts at 0: main0 taken → main1; main2 taken → main3; then main4.
    (is (= "main1" (sessions/next-root-tab-label!)))
    (is (= "main3" (sessions/next-root-tab-label!)))
    (is (= "main4" (sessions/next-root-tab-label!)))))

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

(deftest format-session-list-shows-switch-index
  (testing "each row leads with the tab's own [id] — the /session switch arg"
    (sessions/create-session! {:id 0 :label "main0" :skip-agent-creation true})
    (sessions/create-session! {:id 1 :label "main1" :skip-agent-creation true})
    (let [lines (str/split-lines (plain (sessions/format-session-list)))]
      (is (some #(str/starts-with? % " [0] ") lines))
      (is (some #(str/starts-with? % " [1] ") lines))
      (is (some #(str/includes? % "/session switch <idx>") lines))))

  (testing "the index is the id, NOT the row position — a closed tab leaves a hole"
    (sessions/reset-sessions!)
    ;; Ids 2 and 11 only: tab 0/1 closed, and a two-digit id in play.
    (sessions/create-session! {:id 2  :label "main2"  :skip-agent-creation true})
    (sessions/create-session! {:id 11 :label "main11" :skip-agent-creation true})
    (let [lines (str/split-lines (plain (sessions/format-session-list)))
          rows  (filter #(str/includes? % "main") lines)]
      ;; Numbering rows 0,1 here would name two tabs that do not exist.
      (is (not-any? #(str/includes? % "[0]") rows))
      (is (not-any? #(str/includes? % "[1]") rows))
      ;; Right-aligned to the widest id so labels stay in one column.
      (is (some #(str/starts-with? % "  [2] ") rows))
      (is (some #(str/starts-with? % " [11] ") rows)))))
