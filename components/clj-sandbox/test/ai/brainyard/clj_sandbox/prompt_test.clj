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

(deftest extract-verbatim-blocks-test
  (testing "4-backtick verbatim fence is captured, not executed; body verbatim"
    (let [text (str "````markdown report.md\n"
                    "# Title\n"
                    "Inline ```clojure (inc 1)``` stays literal.\n"
                    "```bash\necho hi\n```\n"
                    "````")
          blocks (prompt/extract-all-code-blocks-multi text)]
      ;; The nested ``` fences inside the verbatim body must NOT become their
      ;; own executable blocks — exactly one block is returned.
      (is (= 1 (count blocks)))
      (let [b (first blocks)]
        (is (= "markdown" (:lang b)))
        (is (true? (:verbatim? b)))
        (is (= "report.md" (:filename b)))
        ;; Body preserved verbatim, fence lines excluded.
        (is (str/includes? (:code b) "(inc 1)"))
        (is (str/includes? (:code b) "echo hi"))
        (is (not (str/includes? (:code b) "report.md"))))))

  (testing "md/txt aliases normalize; missing filename is nil"
    (let [b (first (prompt/extract-all-code-blocks-multi "````md\nhi\n````"))]
      (is (= "markdown" (:lang b)))
      (is (nil? (:filename b))))
    (let [b (first (prompt/extract-all-code-blocks-multi "````txt\nplain\n````"))]
      (is (= "text" (:lang b)))
      (is (= "plain" (:code b)))))

  (testing "4-backtick CODE fence lets the body hold ``` fences (executed, not saved)"
    ;; Regression: code building a markdown string with embedded ```clojure
    ;; fences must NOT close at the first inner ```. A 4-backtick code fence is
    ;; closed only by a 4-backtick run, so the inner ``` passes through.
    (let [text (str "````clojure\n"
                    "(def body \"# Doc\n"
                    "```clojure\n"
                    "(detached-task-marker auto-bg-ms tid)\n"
                    "```\n"
                    "tail\")\n"
                    "````")
          blocks (prompt/extract-all-code-blocks-multi text)]
      (is (= 1 (count blocks)))
      (let [b (first blocks)]
        (is (= "clojure" (:lang b)))
        (is (nil? (:verbatim? b)))
        (is (nil? (:fence-error b)))
        ;; The whole body — including the inner fences — is one code block.
        (is (str/includes? (:code b) "detached-task-marker"))
        (is (str/includes? (:code b) "```clojure"))
        (is (str/includes? (:code b) "tail")))))

  (testing "3-backtick code fence still closes at the first inner ``` (unchanged)"
    (let [blocks (prompt/extract-all-code-blocks-multi "```clojure\n(+ 1 2)\n```")]
      (is (= 1 (count blocks)))
      (is (= "(+ 1 2)" (:code (first blocks))))))

  (testing "verbatim and code fences interleave in source order"
    (let [text (str "```clojure\n(def x 1)\n```\n"
                    "````html page.html\n<h1>hi</h1>\n````\n"
                    "```bash\nls\n```")
          blocks (prompt/extract-all-code-blocks-multi text)]
      (is (= ["clojure" "html" "bash"] (mapv :lang blocks)))
      (is (= [nil true nil] (mapv :verbatim? blocks)))))

  (testing "filename hint is sanitized to a safe basename"
    (let [b (first (prompt/extract-all-code-blocks-multi
                    "````markdown ../../etc/pa ss.md\nx\n````"))]
      (is (not (str/includes? (:filename b) "/")))
      (is (not (str/includes? (:filename b) " ")))))

  (testing "verbatim-lang? predicate"
    (is (true? (prompt/verbatim-lang? "markdown")))
    (is (true? (prompt/verbatim-lang? "text")))
    (is (true? (prompt/verbatim-lang? "html")))
    (is (false? (prompt/verbatim-lang? "clojure")))
    (is (false? (prompt/verbatim-lang? "bash")))))

(deftest build-system-prompt-test
  (testing "builds the prompt with critical rules and workflow"
    (let [content (prompt/build-system-prompt)]
      (is (string? content))
      (is (str/includes? content "writing and executing code"))
      (is (str/includes? content "Critical Rules"))
      (is (str/includes? content "Workflow"))))

  (testing "describes no context section when it carries neither directory nor briefing"
    (let [content (prompt/build-system-prompt)]
      (is (not (str/includes? content "Context & Functions"))
          "a bare prompt must not point at sections it does not carry")
      (is (not (str/includes? content "Context Briefing")))
      (is (not (str/includes? content "usage$guide"))
          "usage$guide is an agent-registered binding, absent from a standalone sandbox")))

  (testing "describes the function directory only when one is supplied"
    (let [content (prompt/build-system-prompt :function-directory "(foo [x])")]
      (is (str/includes? content "Context & Functions"))
      (is (str/includes? content "Function Directory"))
      (is (str/includes? content "(foo [x])"))
      (is (not (str/includes? content "Context Briefing")))
      (is (str/includes? content "usage$guide"))))

  (testing "describes the briefing only when the caller says it supplies one"
    (let [content (prompt/build-system-prompt :briefing? true)]
      (is (str/includes? content "Context Briefing"))
      (is (not (str/includes? content "Function Directory")))))

  (testing "a blank function directory counts as absent"
    (let [content (prompt/build-system-prompt :function-directory "   ")]
      (is (not (str/includes? content "Function Directory")))
      (is (not (str/includes? content "Context & Functions")))))

  (testing "states the caller's iteration budget"
    (is (str/includes? (prompt/build-system-prompt :max-iterations 5)
                       "Budget: 5 iterations"))
    (is (str/includes? (prompt/build-system-prompt :max-iterations 1)
                       "Budget: 1 iteration total")
        "singular when the budget is one")
    (is (str/includes? (prompt/build-system-prompt) "Budget: 20 iterations")
        "default budget is stated too"))

  (testing "includes optional sections"
    (let [content (prompt/build-system-prompt :instruction "Do X"
                                              :agent-context "You are Y"
                                              :tool-context "Use Z")]
      (is (str/includes? content "Do X"))
      (is (str/includes? content "You are Y"))
      (is (str/includes? content "Use Z"))))

  (testing "critical rules ask for the only fence this loop executes"
    (let [content (prompt/build-system-prompt)]
      (is (str/includes? content "```clojure fenced block"))
      (is (str/includes? content "is ignored, not run"))))

  (testing "states what actually ends the run, and that prose does not"
    (let [content (prompt/build-system-prompt)]
      ;; Measured on claude-code/opus: following the old "Rich markdown text
      ;; ONLY, no code blocks" cost a whole round trip — the prose answer was
      ;; bounced by handle-no-code-feedback and re-sent inside (FINAL …).
      (is (not (str/includes? content "Rich markdown text ONLY")))
      (is (str/includes? content "prose does NOT end it"))
      (is (str/includes? content "(FINAL \"your answer\")"))
      (is (str/includes? content "```markdown block"))
      (is (str/includes? content "defer the FINAL by one iteration")
          "split-code-at-final defers a FINAL that shares its block")))

  (testing "names a caller-supplied tool only when the sandbox has it"
    (let [bare   (prompt/build-system-prompt)
          tooled (prompt/build-system-prompt
                  :available '#{bash write-file list-plans query$llm})]
      (doseq [t ["write-file" "list-plans" "query$llm" "/tmp/script.sh"]]
        (is (not (str/includes? bare t))
            (str t " is not bound in a bare sandbox"))
        (is (str/includes? tooled t)
            (str t " should be offered when bound)")))
      (is (str/includes? bare "`bash` is not bound in this sandbox")
          "the shell clause says so outright rather than going silent")
      (is (str/includes? tooled "(bash :command") )))

  (testing "return-breakdown returns map with content and token-breakdown"
    (let [result (prompt/build-system-prompt :return-breakdown? true)]
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
      (is (str/includes? (:content msg) "Write Clojure code"))))

  (testing "asks for the only fence this loop executes"
    (let [content (:content (prompt/build-initial-user-message "q"))]
      (is (str/includes? content "```clojure"))
      (is (not (str/includes? content "```python"))
          "extract-code-blocks matches clojure/clj only — a python fence is silently ignored")
      (is (not (str/includes? content "```bash"))))))

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

;; Usage-guide content + registry moved to the agent component — coverage lives
;; in ai.brainyard.agent.core.usage-test and agent.common.commands-test.

(deftest context-access-prompt-content-test
  (testing "documents only what a standalone sandbox actually has"
    (is (str/includes? prompt/context-access-prompt "context-index"))
    (is (str/includes? prompt/context-access-prompt ":user-vars"))
    ;; All agent constructs: the agent injects :agent-state into the context it
    ;; passes and registers trajectory$search as a tool. A standalone loop has
    ;; neither, and neither has previous turns to recall.
    (is (not (str/includes? prompt/context-access-prompt ":agent-state")))
    (is (not (str/includes? prompt/context-access-prompt "trajectory$search")))
    (is (not (str/includes? prompt/context-access-prompt "Previous Turns")))
    (is (not (str/includes? prompt/context-access-prompt "Recalled Memory")))))
