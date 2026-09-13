;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.sub-query-test
  "Where a sub-query's context lands on the wire. It rides the SYSTEM message
   and the user turn is the prompt alone — the context is the material every
   prompt asks about, and in a batch it is identical across every call."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [ai.brainyard.clj-llm.core.llm :as llm]))

(defn- captured-messages
  "Run `f` with `chat-completion` stubbed; return the message vectors it saw."
  [f]
  (let [seen (atom [])]
    (with-redefs [llm/chat-completion (fn [_lm messages & _] (swap! seen conj messages) {})
                  llm/extract-content (constantly "ok")]
      (f))
    @seen))

(deftest context-goes-in-the-system-message
  (testing "with context: system carries it, user is the bare prompt"
    (let [[[sys usr]] (captured-messages #((llm/create-llm-query-fn {} nil) "Q?" "THE DATA"))]
      (is (= "system" (:role sys)))
      (is (str/includes? (:content sys) "<context>\nTHE DATA\n</context>"))
      (is (= {:role "user" :content "Q?"} usr))))

  (testing "without context: no context block, prompt unchanged"
    (let [[[sys usr]] (captured-messages #((llm/create-llm-query-fn {} nil) "Q?"))]
      (is (not (str/includes? (:content sys) "<context>")))
      (is (= "Q?" (:content usr)))))

  (testing "batched: every call gets the same system message and its own prompt"
    (let [calls (captured-messages #((llm/create-llm-query-batched-fn {} nil) ["a" "b"] "SHARED"))]
      (is (= 2 (count calls)))
      (is (apply = (map first calls)))
      (is (= #{"a" "b"} (set (map (comp :content second) calls))))))

  (testing "context is truncated to the cap"
    (let [[[sys]] (captured-messages #((llm/create-llm-query-fn {} nil) "Q" (apply str (repeat 600000 "x"))))]
      (is (< (count (:content sys)) 510000)))))
