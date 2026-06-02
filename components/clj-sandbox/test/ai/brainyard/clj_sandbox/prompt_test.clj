;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.prompt-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.clj-sandbox.core.prompt :as prompt]
            [clojure.string :as str]))

(deftest extract-code-blocks-test
  (testing "extracts single clojure block"
    (let [text "Here's some code:\n```clojure\n(+ 1 2)\n```\nDone."
          blocks (prompt/extract-code-blocks text)]
      (is (= 1 (count blocks)))
      (is (= "(+ 1 2)\n" (first blocks)))))

  (testing "returns first block when multiple blocks found (drops rest)"
    (let [text "First:\n```clojure\n(def x 1)\n```\nSecond:\n```clojure\n(def y 2)\n```"
          blocks (prompt/extract-code-blocks text)]
      ;; extract-code-blocks intentionally returns only the first block
      (is (= 1 (count blocks)))
      (is (= "(def x 1)\n" (first blocks)))
      ;; Metadata records dropped blocks
      (is (= 1 (:dropped-count (meta blocks))))
      (is (= 2 (:total-count (meta blocks))))))

  (testing "extracts clj blocks too"
    (let [text "```clj\n(+ 1 1)\n```"
          blocks (prompt/extract-code-blocks text)]
      (is (= 1 (count blocks)))))

  (testing "returns empty for no blocks"
    (is (= [] (prompt/extract-code-blocks "Just text, no code."))))

  (testing "returns empty for blank text"
    (is (= [] (prompt/extract-code-blocks "")))
    (is (= [] (prompt/extract-code-blocks nil))))

  (testing "ignores non-clojure blocks"
    (let [text "```python\nprint('hi')\n```\n```clojure\n(println \"hi\")\n```"
          blocks (prompt/extract-code-blocks text)]
      (is (= 1 (count blocks)))
      (is (str/includes? (first blocks) "println"))))

  (testing "handles multiline code blocks"
    (let [text "```clojure\n(defn foo [x]\n  (* x 2))\n\n(foo 21)\n```"
          blocks (prompt/extract-code-blocks text)]
      (is (= 1 (count blocks)))
      (is (str/includes? (first blocks) "defn foo")))))

(deftest build-system-prompt-test
  (testing "builds raw mode prompt with critical rules and context discovery"
    (let [content (prompt/build-system-prompt :mode :raw)]
      (is (string? content))
      (is (str/includes? content "writing and executing code"))
      (is (str/includes? content "Critical Rules"))
      (is (str/includes? content "Context & Functions"))
      (is (str/includes? content "Workflow"))))

  (testing "builds structured mode prompt with FINAL rules"
    (let [content (prompt/build-system-prompt :mode :structured)]
      (is (str/includes? content "FINAL"))
      (is (str/includes? content "REPL sandbox"))))

  (testing "includes optional sections"
    (let [content (prompt/build-system-prompt :mode :raw
                                              :instruction "Do X"
                                              :agent-context "You are Y"
                                              :tool-context "Use Z")]
      (is (str/includes? content "Do X"))
      (is (str/includes? content "You are Y"))
      (is (str/includes? content "Use Z"))))

  (testing "return-breakdown returns map with content and token-breakdown"
    (let [result (prompt/build-system-prompt :mode :raw :return-breakdown? true)]
      (is (map? result))
      (is (string? (:content result)))
      (is (map? (:token-breakdown result)))
      (is (contains? (:token-breakdown result) :role-and-execution))
      (is (contains? (:token-breakdown result) :critical-rules)))))

(deftest build-initial-user-message-test
  (testing "builds user message with query"
    (let [msg (prompt/build-initial-user-message "What is 2+2?")]
      (is (= "user" (:role msg)))
      (is (str/includes? (:content msg) "What is 2+2?"))
      (is (str/includes? (:content msg) "Write code")))))

(deftest build-feedback-message-test
  (testing "builds feedback from successful eval"
    (let [msg (prompt/build-feedback-message [{:result 42 :output "" :error nil}])]
      (is (= "user" (:role msg)))
      (is (str/includes? (:content msg) "42"))))

  (testing "builds feedback with stdout"
    (let [msg (prompt/build-feedback-message [{:result nil :output "hello\n" :error nil}])]
      (is (str/includes? (:content msg) "hello"))))

  (testing "builds feedback with error"
    (let [msg (prompt/build-feedback-message [{:result nil :output "" :error "Division by zero"}])]
      (is (str/includes? (:content msg) "Division by zero"))))

  (testing "builds feedback from multiple blocks"
    (let [msg (prompt/build-feedback-message [{:result 1 :output "" :error nil}
                                              {:result 2 :output "" :error nil}])]
      (is (str/includes? (:content msg) "Block 1"))
      (is (str/includes? (:content msg) "Block 2")))))
