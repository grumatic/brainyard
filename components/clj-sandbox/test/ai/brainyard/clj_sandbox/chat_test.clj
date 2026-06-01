;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-sandbox.chat-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.clj-sandbox.core.chat :as chat]
            [ai.brainyard.clj-llm.interface :as clj-llm]))

;; ============================================================================
;; Mock helpers
;; ============================================================================

(defn- mock-response
  "Create a mock LLM response map with the given text content."
  [text]
  {:choices [{:message {:content text}}]})

(defn- make-sequential-responder
  "Create a function that returns different responses on successive calls.
   responses is a vector of text strings."
  [responses]
  (let [call-count (atom 0)]
    (fn [& _args]
      (let [idx (min @call-count (dec (count responses)))
            resp (mock-response (nth responses idx))]
        (swap! call-count inc)
        resp))))

(defn- run-completion
  "Run chat/completion with mock LLM and a raw `context` binding in the sandbox.
   chat/completion's :context arg is now a map-or-nil (path-based context
   accessors require map shape — non-map values throw). The tests use
   `(count context)` etc. on the raw value, so we pass nil for :context and
   bind the raw value into the sandbox as `context` via :bindings."
  [query ctx responses & {:keys [max-iterations] :or {max-iterations 5}}]
  (let [mock-chat (make-sequential-responder responses)]
    (with-redefs [clj-llm/chat-completion mock-chat
                  clj-llm/extract-content (fn [r _] (-> r :choices first :message :content))]
      (chat/completion query nil
                       :max-iterations max-iterations
                       :bindings {'context ctx}))))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest completion-immediate-final-test
  (testing "LLM calls FINAL on first iteration"
    (let [result (run-completion
                  "How long is the context?" "Hello World"
                  ["Let me check the context.\n```clojure\n(FINAL (str \"The answer is \" (count context)))\n```"])]
      (is (= "The answer is 11" (:answer result)))
      (is (= :final (:terminated-by result)))
      (is (= 1 (:total-iterations result))))))

(deftest completion-multi-iteration-test
  (testing "LLM explores then calls FINAL"
    (let [result (run-completion
                  "How long?" "test data"
                  ["Let me check what we have.\n```clojure\n(def n (count context))\n```"
                   "Now I know.\n```clojure\n(FINAL (str \"Length: \" n))\n```"]
                  :max-iterations 10)]
      (is (= "Length: 9" (:answer result)))
      (is (= :final (:terminated-by result)))
      (is (= 2 (:total-iterations result))))))

(deftest completion-max-iterations-test
  (testing "stops at max iterations"
    (let [call-count (atom 0)
          mock-chat (fn [& _args]
                      (swap! call-count inc)
                      (mock-response "```clojure\n(+ 1 1)\n```"))]
      (with-redefs [clj-llm/chat-completion mock-chat
                    clj-llm/extract-content (fn [r _] (-> r :choices first :message :content))]
        ;; :context must be a map (or nil) under the new path-based accessors.
        (let [result (chat/completion "test" nil :max-iterations 3)]
          (is (= :max-iterations (:terminated-by result)))
          (is (= 3 (:total-iterations result)))
          (is (= 3 @call-count)))))))

(deftest completion-no-code-blocks-test
  (testing "handles response with no code blocks"
    (let [result (run-completion
                  "What?" "ctx"
                  ["I think the answer is 42."
                   "```clojure\n(FINAL \"42\")\n```"])]
      (is (= "42" (:answer result)))
      (is (= 2 (:total-iterations result))))))

(deftest completion-on-iteration-callback-test
  (testing "on-iteration is called each iteration"
    (let [iterations-seen (atom [])
          mock-chat (make-sequential-responder
                     ["```clojure\n(+ 1 1)\n```"
                      "```clojure\n(FINAL \"done\")\n```"])]
      (with-redefs [clj-llm/chat-completion mock-chat
                    clj-llm/extract-content (fn [r _] (-> r :choices first :message :content))]
        ;; :context must be a map (or nil); pass nil — the code blocks don't
        ;; reference `context`, they just (+ 1 1) and (FINAL "done").
        (chat/completion "test" nil
                         :max-iterations 5
                         :on-iteration (fn [info] (swap! iterations-seen conj info)))
        (is (= 2 (count @iterations-seen)))
        (is (= 1 (:iteration (first @iterations-seen))))
        (is (= 2 (:iteration (second @iterations-seen))))))))

(deftest completion-error-handling-test
  (testing "code errors are captured and loop continues"
    (let [result (run-completion
                  "test" "data"
                  ["```clojure\n(/ 1 0)\n```"
                   "```clojure\n(FINAL \"recovered\")\n```"])]
      (is (= "recovered" (:answer result)))
      (is (= 2 (:total-iterations result))))))

(deftest completion-context-types-test
  (testing "works with string context"
    (let [result (run-completion "count" "abc"
                                 ["```clojure\n(FINAL (str (count context)))\n```"])]
      (is (= "3" (:answer result)))))

  (testing "works with map context"
    (let [result (run-completion "count keys" {:a 1 :b 2}
                                 ["```clojure\n(FINAL (str (count (keys context))))\n```"])]
      (is (= "2" (:answer result)))))

  (testing "works with vector context"
    (let [result (run-completion "sum" [1 2 3 4 5]
                                 ["```clojure\n(FINAL (str (reduce + context)))\n```"])]
      (is (= "15" (:answer result))))))

(deftest completion-final-stripped-test
  (testing "FINAL is deferred when preceded by other expressions"
    (let [messages-seen (atom [])
          mock-chat (make-sequential-responder
                     [;; Iteration 1: code before FINAL — FINAL should be stripped
                      "```clojure\n(def n (count context))\n(println \"counted:\" n)\n(FINAL (str \"Length: \" n))\n```"
                      ;; Iteration 2: LLM sees results and calls FINAL alone
                      "```clojure\n(FINAL (str \"Length: \" n))\n```"])]
      (with-redefs [clj-llm/chat-completion
                    (fn [_ msgs & {:as opts}]
                      (swap! messages-seen conj msgs)
                      (mock-chat nil msgs opts))
                    clj-llm/extract-content (fn [r _] (-> r :choices first :message :content))]
        (let [result (chat/completion "How long?" nil :max-iterations 5
                                      :bindings {'context "test data"})]
          ;; Should take 2 iterations (stripped + re-issued FINAL)
          (is (= 2 (:total-iterations result)))
          (is (= "Length: 9" (:answer result)))
          (is (= :final (:terminated-by result)))
          ;; Check that feedback included the deferral note
          (let [second-call-msgs (second @messages-seen)
                last-user-msg (->> second-call-msgs
                                   (filter #(= "user" (:role %)))
                                   last
                                   :content)]
            (is (str/includes? last-user-msg "FINAL call was deferred")))))))

  (testing "FINAL-only code (no pre-code) executes immediately"
    (let [result (run-completion "count" "abc"
                                 ["```clojure\n(FINAL (str \"answer: \" (count context)))\n```"])]
      (is (= 1 (:total-iterations result)))
      (is (= "answer: 3" (:answer result)))
      (is (= :final (:terminated-by result))))))
