;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.help-tips-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.agent-tui.help-tips :as ht]))

(use-fixtures :each (fn [f] (ht/clear-agent-suggestion!) (f) (ht/clear-agent-suggestion!)))

(deftest static-tips-rotation
  (testing "with no suggestion, the placeholder is a static tip"
    (ht/clear-agent-suggestion!)
    (is (some #{(ht/current-placeholder)} ht/static-tips))
    (is (= :static (:source (ht/current-tip)))))
  (testing "rotate-static! advances and wraps through the curated set"
    (let [n (count ht/static-tips)
          seen (set (for [_ (range n)]
                      (let [p (ht/current-placeholder)] (ht/rotate-static!) p)))]
      ;; one full cycle visits every distinct static tip
      (is (= (set ht/static-tips) seen))
      ;; wrap-around: after n rotations we're back to the same tip
      (let [before (ht/current-placeholder)]
        (dotimes [_ n] (ht/rotate-static!))
        (is (= before (ht/current-placeholder)))))))

(deftest agent-suggestion-priority
  (testing "an agent suggestion overrides the static tip"
    (ht/set-agent-suggestion! "add error handling to the script")
    (is (= :agent-suggestion (:source (ht/current-tip))))
    (is (= "add error handling to the script" (ht/agent-suggestion)))
    (let [ph (ht/current-placeholder)]
      (is (clojure.string/includes? ph "add error handling to the script"))
      (is (clojure.string/includes? ph "→ to use"))))
  (testing "clearing falls back to a static tip"
    (ht/set-agent-suggestion! "x")
    (ht/clear-agent-suggestion!)
    (is (nil? (ht/agent-suggestion)))
    (is (= :static (:source (ht/current-tip))))))

(deftest set-suggestion-semantics
  (testing "blank / whitespace clears rather than setting"
    (ht/set-agent-suggestion! "   ")
    (is (nil? (ht/agent-suggestion)))
    (ht/set-agent-suggestion! nil)
    (is (nil? (ht/agent-suggestion))))
  (testing "leading/trailing whitespace is trimmed"
    (ht/set-agent-suggestion! "  do the thing  ")
    (is (= "do the thing" (ht/agent-suggestion)))))

(deftest suggestion-width-bounded
  (testing "a very long suggestion is truncated so the placeholder stays one line"
    (ht/set-agent-suggestion! (apply str (repeat 200 \x)))
    (let [ph (ht/current-placeholder)]
      (is (clojure.string/includes? ph "…"))
      ;; comfortably under a narrow terminal width
      (is (< (count ph) 90)))))
