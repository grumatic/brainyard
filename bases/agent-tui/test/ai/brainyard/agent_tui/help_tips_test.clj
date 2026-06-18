;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.help-tips-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.agent-tui.help-tips :as ht]))

;; Suggestions are keyed per tab (session-idx); use a fixed key in these tests.
(def ^:private k 0)

(use-fixtures :each (fn [f] (ht/clear-agent-suggestion! k) (f) (ht/clear-agent-suggestion! k)))

(deftest static-tips-rotation
  (testing "with no suggestion, the placeholder is a static tip"
    (ht/clear-agent-suggestion! k)
    (is (some #{(ht/current-placeholder k)} ht/static-tips))
    (is (= :static (:source (ht/current-tip k)))))
  (testing "rotate-static! advances and wraps through the curated set"
    (let [n (count ht/static-tips)
          seen (set (for [_ (range n)]
                      (let [p (ht/current-placeholder k)] (ht/rotate-static!) p)))]
      ;; one full cycle visits every distinct static tip
      (is (= (set ht/static-tips) seen))
      ;; wrap-around: after n rotations we're back to the same tip
      (let [before (ht/current-placeholder k)]
        (dotimes [_ n] (ht/rotate-static!))
        (is (= before (ht/current-placeholder k)))))))

(deftest agent-suggestion-priority
  (testing "an agent suggestion overrides the static tip"
    (ht/set-agent-suggestion! k "add error handling to the script")
    (is (= :agent-suggestion (:source (ht/current-tip k))))
    (is (= "add error handling to the script" (ht/agent-suggestion k)))
    (let [ph (ht/current-placeholder k)]
      (is (clojure.string/includes? ph "add error handling to the script"))
      (is (clojure.string/includes? ph "→ to use"))))
  (testing "clearing falls back to a static tip"
    (ht/set-agent-suggestion! k "x")
    (ht/clear-agent-suggestion! k)
    (is (nil? (ht/agent-suggestion k)))
    (is (= :static (:source (ht/current-tip k))))))

(deftest set-suggestion-semantics
  (testing "blank / whitespace clears rather than setting"
    (ht/set-agent-suggestion! k "   ")
    (is (nil? (ht/agent-suggestion k)))
    (ht/set-agent-suggestion! k nil)
    (is (nil? (ht/agent-suggestion k))))
  (testing "leading/trailing whitespace is trimmed"
    (ht/set-agent-suggestion! k "  do the thing  ")
    (is (= "do the thing" (ht/agent-suggestion k)))))

(deftest suggestion-width-bounded
  (testing "a very long suggestion is truncated so the placeholder stays one line"
    (ht/set-agent-suggestion! k (apply str (repeat 200 \x)))
    (let [ph (ht/current-placeholder k)]
      (is (clojure.string/includes? ph "…"))
      ;; comfortably under a narrow terminal width
      (is (< (count ph) 90)))))

(deftest suggestions-are-per-tab
  (testing "a suggestion on one tab doesn't clobber or leak into another tab"
    (ht/set-agent-suggestion! 1 "tab-1 follow-up")
    (ht/set-agent-suggestion! 2 "tab-2 follow-up")
    (is (= "tab-1 follow-up" (ht/agent-suggestion 1)))
    (is (= "tab-2 follow-up" (ht/agent-suggestion 2)))
    (is (nil? (ht/agent-suggestion 3)) "an unset tab has no suggestion")
    (ht/clear-agent-suggestion! 1)
    (is (nil? (ht/agent-suggestion 1)) "clearing tab 1 leaves tab 2 intact")
    (is (= "tab-2 follow-up" (ht/agent-suggestion 2)))
    (ht/clear-agent-suggestion! 2)))
