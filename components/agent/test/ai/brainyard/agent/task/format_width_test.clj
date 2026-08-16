;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.task.format-width-test
  "The task-activity formatters take `cols` — a COLUMN budget — and used to
   enforce it with `count`, which is UTF-16 code units. Measured against the
   previous implementation with CJK content:

     cols=80  activity line rendered 126 columns
     cols=40  activity line rendered  71 columns
     cols=20  activity line rendered  31 columns

   Every one over budget, and these render task NAMES and task OUTPUT — a name
   the user chose and arbitrary subprocess bytes, which is exactly where
   non-ASCII shows up. On the sticky task-activity area an over-wide row wraps
   into the chrome below it."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent.task.format :as tf]
            [ai.brainyard.agent.tui.format :as fmt]))

(def ^:private samples
  {"ascii" (apply str (repeat 200 "a"))
   "cjk"   (apply str (repeat 60 "漢"))
   "emoji" (apply str (repeat 20 "👨‍👩‍👦"))
   "mixed" (str "running " (apply str (repeat 30 "漢")) " step")})

(defn- ends-mid-surrogate? [^String s]
  (and (pos? (count s))
       (Character/isHighSurrogate (.charAt s (dec (count s))))))

(deftest activity-line-respects-the-column-budget
  (doseq [[label s] samples
          ;; Down to widths where the PREFIX alone exceeds the budget — the
          ;; degenerate end is where the old `(max 1 …)` floor put the line one
          ;; column over, and one column is all it takes to wrap.
          cols [120 80 40 20 10 6 3 1]]
    (let [r (tf/format-task-activity-line "•" :task-1 s cols)]
      (testing (str label " at " cols " columns")
        (is (<= (fmt/display-width r) cols)
            (str "rendered " (fmt/display-width r) " columns"))
        (is (not (ends-mid-surrogate? r)))))))

(deftest output-line-respects-the-column-budget
  (doseq [[label s] samples
          cols [120 80 40 20 10 6 3 1]]
    (let [r (tf/format-task-output-line s cols)]
      (testing (str label " at " cols " columns")
        (is (<= (fmt/display-width r) cols)
            (str "rendered " (fmt/display-width r) " columns"))
        (is (not (ends-mid-surrogate? r)))))))

(deftest short-content-passes-through-untouched
  (testing "the budget is a ceiling, not a target"
    (is (= (fmt/display-width "  │ ok")
           (fmt/display-width (tf/format-task-output-line "ok" 80)))
        "content that already fits is neither padded nor ellipsised")))
