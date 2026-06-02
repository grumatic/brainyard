;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.render-test
  "Pure-function tests for the render helpers.  These do not exercise FIFO
   I/O — they verify that the strings sent to the stream/status sinks
   contain the expected text after stripping ANSI escapes."
  (:require [ai.brainyard.agent-tui.render :as render]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- strip-ansi [^String s]
  (when s (str/replace s #"\x1b\[[0-9;]*[A-Za-z]" "")))

(deftest ask-pre-line-format
  (is (= "\n❯ hello world\n"
         (strip-ansi (render/ask-pre-line "hello world")))))

(deftest ask-post-line-includes-iters-and-separator
  (let [out (strip-ansi (render/ask-post-line {:answer "ok" :iteration-count 3}))]
    (is (str/includes? out "▾ answer") "header marker present")
    (is (str/includes? out "ok") "answer body present")
    (is (str/includes? out "(3 iters)"))
    (is (str/includes? out "─"))))

(deftest perf-block-empty-history
  (let [out (strip-ansi (render/perf-block []))]
    (is (str/includes? out "no calls recorded"))))

(deftest perf-block-renders-aggregates-and-per-call-rows
  (testing "aggregate header + per-call table"
    (let [history [{:duration-ms 250 :input-tokens 100 :output-tokens 50
                    :provider :openai :model "gpt-4o-mini"}
                   {:latency-ms  500 :input-tokens 200 :output-tokens 75
                    :provider :anthropic :model "claude-haiku"}]
          out (strip-ansi (render/perf-block history))]
      (is (str/includes? out "Performance"))
      (is (str/includes? out "calls         : 2"))
      (is (str/includes? out "total time    : 750"))
      (is (str/includes? out "avg per call  : 375"))
      (is (str/includes? out "openai:gpt-4o-mini") "first call's model surfaced")
      (is (str/includes? out "anthropic:claude-haiku") "second call's model surfaced")
      (is (str/includes? out "250ms"))
      (is (str/includes? out "500ms")))))

(deftest perf-block-columns-align
  (testing "header and data row columns end at the same character index"
    (let [history [{:duration-ms 250 :input-tokens 12345 :output-tokens 99
                    :provider :openai :model "gpt-4o-mini"}]
          out (strip-ansi (render/perf-block history))
          rows (clojure.string/split-lines out)
          header (first (filter #(re-find #"In Tok" %) rows))
          data   (first (filter #(re-find #"openai:" %) rows))]
      (is (some? header))
      (is (some? data))
      ;; The 'k' of "In Tok" must end at the same column as the last
      ;; digit of the input-tokens value (both right-aligned).
      (is (= (+ (.indexOf ^String header "In Tok") (count "In Tok"))
             (+ (.indexOf ^String data "12345") (count "12345")))
          "In Tok header and data value end at the same column (right-aligned)")
      ;; The right edge of "Model" header must align with the start of
      ;; the model value (both are left-aligned in the last column).
      (is (= (.indexOf ^String header "Model")
             (.indexOf ^String data "openai:"))
          "Model column starts at the same column in both rows"))))

(deftest perf-block-caps-table-to-last-20
  (testing "with > 20 calls, only the most recent 20 rows render"
    (let [history (vec (for [i (range 30)]
                         {:duration-ms (* (inc i) 10)
                          :input-tokens (* 10 (inc i))
                          :output-tokens 5
                          :provider :openai :model (str "m" i)}))
          out (strip-ansi (render/perf-block history))
          model-rows (count (re-seq #"openai:m\d+" out))]
      (is (= 20 model-rows) "exactly 20 model rows shown")
      (is (str/includes? out "calls         : 30") "aggregate still reflects all 30 calls"))))

(deftest ask-post-line-error-uses-distinct-header
  (testing "errors get their own ▾ error header (parallel to ▾ answer for successes)"
    (let [out (strip-ansi (render/ask-post-line {:error "rate limited"}))]
      (is (str/includes? out "▾ error") "distinct error marker")
      (is (not (str/includes? out "▾ answer"))
          "error path doesn't show the success header")
      (is (str/includes? out "✗"))
      (is (str/includes? out "rate limited")))))

(deftest tool-pre-line-includes-name-and-args
  (let [out (strip-ansi (render/tool-pre-line {:tool-name :bash.run
                                               :args {:command "ls -la"}}))]
    (is (str/includes? out "⮕"))
    (is (str/includes? out "bash.run"))
    (is (str/includes? out "command=ls -la"))))

(deftest tool-pre-line-omits-noisy-keys
  (let [out (strip-ansi
             (render/tool-pre-line
              {:tool-name :foo
               :args {:agent ::big-thing :session-store ::also-big :ok 1}}))]
    (is (str/includes? out "ok=1"))
    (is (not (str/includes? out "agent=")))
    (is (not (str/includes? out "session-store=")))))

(deftest tool-post-line-glyph-on-error
  (let [ok  (strip-ansi (render/tool-post-line {:tool-name :foo :result "fine"}))
        err (strip-ansi (render/tool-post-line {:tool-name :foo
                                                :result {:error "boom"}}))]
    (is (str/includes? ok "✓"))
    (is (str/includes? err "✗"))))

(deftest tool-post-line-surfaces-error-text
  (testing ":error message is shown after the ✗ glyph"
    (let [out (strip-ansi
               (render/tool-post-line
                {:tool-name :read.file
                 :result {:error "ENOENT: /missing/file.txt"}}))]
      (is (str/includes? out "ENOENT: /missing/file.txt")
          "users see WHY the tool failed, not just that it did"))))

(deftest tool-post-line-surfaces-hook-blocked-text
  (testing ":hook-blocked reason is shown after the ✗ glyph"
    (let [out (strip-ansi
               (render/tool-post-line
                {:tool-name :bash.run
                 :result {:hook-blocked "loop guard tripped"}}))]
      (is (str/includes? out "loop guard tripped")))))

(deftest tool-post-line-surfaces-reason-and-message-keys
  (testing ":reason and :message keys also surface"
    (is (str/includes? (strip-ansi (render/tool-post-line
                                    {:tool-name :foo
                                     :result {:reason "rate limited"}}))
                       "rate limited"))
    (is (str/includes? (strip-ansi (render/tool-post-line
                                    {:tool-name :foo
                                     :result {:message "permission denied"}}))
                       "permission denied"))))

(deftest tool-post-line-surfaces-error-message-key
  (testing ":error-message (returned by invoke-tool for unknown tools) surfaces with ✗"
    (let [out (strip-ansi (render/tool-post-line
                           {:tool-name :foo
                            :result {:error-message "foo tool is not registered!"}}))]
      (is (str/includes? out "✗"))
      (is (str/includes? out "foo tool is not registered!")))))

(deftest tool-post-line-truncates-long-error
  (let [long-err (apply str (repeat 500 "x"))
        out (strip-ansi
             (render/tool-post-line
              {:tool-name :foo :result {:error long-err}}))]
    (is (< (count out) 260) "long error truncated to ~200 chars")))

(deftest clamp-helper-passes-short-strings-through
  (is (= "ok"   (render/clamp "ok" 80)))
  (is (= ""     (render/clamp "" 80)))
  (is (= "abc"  (render/clamp "abc" 3))))

(deftest clamp-helper-replaces-tail-with-ellipsis
  (is (= "abcd…" (render/clamp "abcdefgh" 5)))
  (is (= "ab…"   (render/clamp "abcdefgh" 3))))

(deftest clamp-helper-degenerate-budget
  (is (= "…" (render/clamp "abcdef" 1)))
  (is (= "…" (render/clamp "abcdef" 0)))
  (is (= "abcdef" (render/clamp "abcdef" nil)) "nil max-len → no clamp"))

(deftest collapse-ws-helper
  (is (= "" (render/collapse-ws nil)))
  (is (= "" (render/collapse-ws "")))
  (is (= "a b c" (render/collapse-ws "a   b\nc")))
  (is (= "a b" (render/collapse-ws "  a\t b  "))))

(deftest tool-pre-line-clamps-args-to-cols
  (testing "with :cols, long arg summary is clamped to fit one line"
    (let [out (strip-ansi
               (render/tool-pre-line
                {:tool-name :bash.run
                 :args {:command (apply str (repeat 200 "x"))}
                 :cols 60}))]
      (is (str/includes? out "…"))
      ;; Full line should not exceed cols + a small ANSI margin.
      (is (< (count out) 80)
          (str "tool-pre-line with :cols 60 should fit on one row, got "
               (count out))))))

(deftest tool-post-line-clamps-error-to-cols
  (testing "with :cols, long error is clamped to fit one line"
    (let [out (strip-ansi
               (render/tool-post-line
                {:tool-name :read.file
                 :result {:error (apply str (repeat 500 "x"))}
                 :cols 80}))]
      (is (str/includes? out "…"))
      (is (< (count out) 100)
          (str "tool-post-line with :cols 80 should fit on one row, got "
               (count out))))))

(deftest render-markdown-headings
  (testing "heading markers are stripped, body is bolded"
    (let [out (strip-ansi (render/render-markdown "# Title\n## Sub\n### Sub2\nbody"))]
      (is (= "Title\nSub\nSub2\nbody" out)
          "raw `##` markers removed from rendered output")))
  (testing "ANSI bold present on heading body"
    (let [out (render/render-markdown "# Title")]
      (is (str/includes? out "\033[1m") "bold ANSI present on heading body")
      (is (not (str/includes? out "# Title")) "raw # marker not visible"))))

(deftest render-markdown-heading-preserves-indent
  (testing "leading whitespace before the heading marker is preserved"
    (let [out (strip-ansi (render/render-markdown "  ## Indented"))]
      (is (= "  Indented" out)
          "indent kept, marker stripped"))))

(deftest render-markdown-heading-applies-inline-md-to-body
  (testing "inline code in a heading body is also styled"
    (let [out (render/render-markdown "## Use `foo`")
          stripped (strip-ansi out)]
      (is (= "Use foo" stripped) "raw `##` and backticks both removed")
      (is (str/includes? out "\033[1m") "bold present")
      (is (str/includes? out "\033[36m") "cyan (inline code) present"))))

(deftest render-markdown-code-fence
  (let [src "before\n```\ncode line\n```\nafter"
        out (render/render-markdown src)]
    ;; Each line should be styled or untouched depending on fence state.
    (is (str/includes? out "code line"))
    (is (str/includes? out "before"))
    (is (str/includes? out "after"))
    ;; The two fence rows AND the code line carry the dim escape.
    (is (>= (count (re-seq #"\033\[2m" out)) 3))))

(deftest render-markdown-bullet-list
  (let [out (strip-ansi (render/render-markdown "- one\n- two\n  - nested"))]
    (is (str/includes? out "• one"))
    (is (str/includes? out "• two"))
    ;; nested list items keep their indent.
    (is (str/includes? out "  • nested"))))

(deftest render-markdown-numbered-list
  (let [out (strip-ansi (render/render-markdown "1. first\n2. second"))]
    (is (str/includes? out "1. first"))
    (is (str/includes? out "2. second"))))

(deftest render-markdown-inline-code
  (let [out (render/render-markdown "use `foo` to bar")]
    ;; The backticks are stripped and the inner is styled cyan.
    (is (not (str/includes? out "`foo`")))
    (is (str/includes? out "foo"))))

(deftest render-markdown-plain-text-passthrough
  (is (= "hello world" (strip-ansi (render/render-markdown "hello world")))))

(deftest ask-post-line-markdown-default-on
  (let [out (render/ask-post-line {:answer "# Done\n- step 1\n- step 2"
                                   :iteration-count 3})]
    ;; Bold escape implies markdown ran on the heading.
    (is (str/includes? out "\033[1m"))
    (is (str/includes? (strip-ansi out) "• step 1"))))

(deftest display-width-ignores-ansi
  (is (= 5 (render/display-width "hello")))
  (is (= 5 (render/display-width "\033[1mhello\033[0m"))
      "ANSI escapes don't count toward visible width")
  (is (= 0 (render/display-width nil)))
  (is (= 0 (render/display-width ""))))

(deftest ansi-aware-word-wrap-passes-short-lines
  (is (= ["hi"] (render/ansi-aware-word-wrap "hi" 80)))
  (is (= ["hello world"]
         (render/ansi-aware-word-wrap "hello world" 80))))

(deftest ansi-aware-word-wrap-breaks-at-spaces
  (let [out (render/ansi-aware-word-wrap "the quick brown fox jumps over the lazy dog" 20)]
    (is (every? #(<= (count %) 20) out)
        (str "every line ≤ 20 cols, got " out))
    (is (>= (count out) 2) "split into multiple lines")
    (is (= "the quick brown fox jumps over the lazy dog"
           (clojure.string/replace (clojure.string/join " " out) #"\s+" " "))
        "no characters lost across the split")))

(deftest ansi-aware-word-wrap-hard-cuts-long-tokens
  (let [out (render/ansi-aware-word-wrap "abcdefghijklmnopqrstuvwxyz" 10)]
    (is (every? #(<= (count %) 10) out))
    (is (>= (count out) 3))))

(deftest ansi-aware-word-wrap-preserves-ansi-state
  (testing "split mid-span closes + reopens active codes"
    (let [styled (str "\033[1mone two three four\033[0m")
          out (render/ansi-aware-word-wrap styled 10)]
      (is (>= (count out) 2))
      ;; First line ends with reset.
      (is (clojure.string/ends-with? (first out) "\033[0m")
          "first wrapped line closes with reset")
      ;; Second line starts with the active codes restored.
      (is (clojure.string/starts-with? (second out) "\033[1m")
          "second wrapped line reopens with the prior bold span"))))

(deftest render-markdown-bold-italic
  (let [bold-out (render/render-markdown "this is **important**")
        ital-out (render/render-markdown "this is *emphasized*")]
    (is (str/includes? bold-out "\033[1m") "bold ANSI present")
    (is (str/includes? ital-out "\033[3m") "italic ANSI present")
    ;; Markers themselves are gone.
    (is (not (str/includes? bold-out "**important**")))
    (is (not (str/includes? ital-out "*emphasized*")))))

(deftest render-markdown-italic-not-confused-by-bold
  (let [out (render/render-markdown "**bold** and *ital* together")]
    (is (str/includes? out "\033[1m"))
    (is (str/includes? out "\033[3m"))
    (is (not (str/includes? out "**bold**")) "bold marker removed")
    (is (not (str/includes? out "*ital*"))   "italic marker removed")))

(deftest task-log-block-wraps-long-lines-when-cols-given
  (let [long-line (apply str (repeat 200 "x"))
        out (strip-ansi
             (render/task-log-block :t1 [long-line] :cols 80))
        rows (str/split-lines out)]
    ;; Header + at least 2 wrapped content rows + trailing blank.
    (is (>= (count rows) 3) "long line was wrapped into multiple rows")
    (is (every? #(<= (count %) 84) rows)
        "no row exceeds cols + indent budget")))

(deftest ask-post-line-wraps-long-paragraph-at-cols
  (let [long-para (apply str (repeat 200 "a"))
        out (strip-ansi
             (render/ask-post-line {:answer long-para :cols 60}))
        ;; Drop the trailing horizontal-rule and meta line.
        body-rows (->> (str/split-lines out)
                       (remove #(re-find #"^─+$" %))
                       (remove clojure.string/blank?))]
    (is (>= (count body-rows) 2)
        "long paragraph wrapped to multiple rows")
    (is (every? #(<= (count %) 64) body-rows)
        "wrapped rows fit within :cols + small ANSI margin")))

(deftest ask-post-line-rule-stretches-to-cols
  (let [out (strip-ansi (render/ask-post-line {:answer "x" :cols 100}))
        rule (->> (str/split-lines out)
                  (some #(when (re-find #"^─+$" %) %)))]
    (is (some? rule))
    (is (= 100 (count rule)) "horizontal-rule width matches :cols")))

(deftest ask-post-line-default-rule-width-when-no-cols
  (let [out (strip-ansi (render/ask-post-line {:answer "x"}))
        rule (->> (str/split-lines out)
                  (some #(when (re-find #"^─+$" %) %)))]
    (is (= 60 (count rule)) "no :cols falls back to legacy 60-col rule")))

(deftest status-content-without-cols-keeps-everything
  (testing "no :cols → all segments rendered (legacy behavior)"
    (let [out (strip-ansi
               (render/status-line
                {:state :running :agent-id :coact-agent
                 :model "claude-code:sonnet"
                 :iter 5 :max-iter 100
                 :tools-used 3
                 :last-tool :bash.run
                 :todo-progress {:done 1 :total 4}
                 :queue-depth 2}))]
      (is (str/includes? out "running"))
      (is (str/includes? out "coact-agent"))
      (is (str/includes? out "claude-code:sonnet"))
      (is (str/includes? out "iter 5/100"))
      (is (str/includes? out "▸ bash.run"))
      (is (str/includes? out "tools 3"))
      (is (str/includes? out "todo 1/4"))
      (is (str/includes? out "q 2")))))

(deftest status-content-narrow-pane-drops-tail-segments
  (testing "narrow :cols drops right-most segments to fit"
    (let [snap {:state :running :agent-id :coact-agent
                :model "claude-code:sonnet"
                :iter 5 :max-iter 100
                :tools-used 3
                :last-tool :bash.run
                :todo-progress {:done 1 :total 4}
                :queue-depth 2
                :cols 30}
          out (strip-ansi (render/status-line snap))]
      ;; State + agent always survive.
      (is (str/includes? out "running"))
      (is (str/includes? out "coact-agent"))
      ;; Tail-most segment (queue) must be dropped on a 30-col pane.
      (is (not (str/includes? out " q 2"))
          (str "queue should be dropped at 30 cols, got: " out))
      ;; The fitted line should not exceed cols (counting visible chars).
      (let [trimmed-len (count out)]
        ;; status-line prepends "\rESC[2K " — strip those for size check.
        (is (<= trimmed-len 50)
            (str "fitted output stays bounded, got " trimmed-len))))))

(deftest status-content-extreme-narrow-keeps-state-and-agent
  (testing "<20 col pane keeps only state + agent"
    (let [snap {:state :idle :agent-id :coact :model "long-model-name"
                :iter 5 :tools-used 99
                :cols 20}
          out (strip-ansi (render/status-line snap))]
      (is (str/includes? out "idle"))
      (is (str/includes? out "coact"))
      (is (not (str/includes? out "long-model-name"))))))

(deftest ask-pre-line-2-arity-with-cols-wraps-long-input
  (let [long-in (apply str (repeat 200 "x"))
        out (strip-ansi (render/ask-pre-line long-in {:cols 60}))
        rows (->> (str/split-lines out) (remove clojure.string/blank?))]
    (is (>= (count rows) 2) "long input wrapped to multiple rows")
    (is (every? #(<= (count %) 64) rows)
        "every row fits within :cols + ANSI margin")))

(deftest ask-pre-line-only-first-row-has-prompt-glyph
  (testing "wrapped input echo: ❯ on first row, indent on continuation rows"
    (let [out (strip-ansi (render/ask-pre-line
                           "the quick brown fox jumps over the lazy dog repeatedly"
                           {:cols 30}))
          rows (->> (str/split-lines out) (remove clojure.string/blank?))]
      (is (>= (count rows) 2))
      (is (str/includes? (first rows) "❯") "first row carries the prompt glyph")
      (doseq [r (rest rows)]
        (is (not (str/includes? r "❯"))
            (str "continuation row should not carry ❯ glyph: " (pr-str r)))))))

(deftest ask-pre-line-1-arity-still-works
  (let [out (strip-ansi (render/ask-pre-line "hello"))]
    (is (str/includes? out "hello"))
    (is (str/includes? out "❯"))))

(deftest welcome-banner-cols-stretches-bars
  (let [out (strip-ansi (render/welcome-banner
                         {:agent-id :coact :session-id "s1" :cols 100}))
        bars (->> (str/split-lines out)
                  (filter #(re-find #"^─+$" %)))]
    (is (= 2 (count bars)))
    (is (every? #(= 100 (count %)) bars)
        "both top + bottom bars match :cols")))

(deftest welcome-banner-default-bar-width
  (let [out (strip-ansi (render/welcome-banner
                         {:agent-id :coact :session-id "s1"}))
        bars (->> (str/split-lines out)
                  (filter #(re-find #"^─+$" %)))]
    (is (every? #(= 60 (count %)) bars))))

(deftest skill-read-block-wraps-long-content-at-cols
  (let [long-line (apply str (repeat 200 "x"))
        out (strip-ansi
             (render/skill-read-block
              {:name "big" :type :brainyard
               :content long-line :size 200}
              {:cols 80}))
        body-rows (->> (str/split-lines out)
                       (filter #(re-find #"│" %)))]
    (is (>= (count body-rows) 2)
        "long content line was wrapped into multiple body rows")
    (is (every? #(<= (count %) 84) body-rows)
        "no body row exceeds cols + indent")))

(deftest task-log-block-skips-wrap-when-no-cols
  (let [long-line (apply str (repeat 200 "x"))
        out (strip-ansi
             (render/task-log-block :t1 [long-line]))
        rows (str/split-lines out)]
    ;; Without cols, the line is emitted as one prefixed row.
    (is (some #(> (count %) 100) rows)
        "without :cols, the long line stays on one row")))

(deftest ask-post-line-markdown-disabled
  (let [out (render/ask-post-line {:answer "# Done"
                                   :markdown? false
                                   :iteration-count 1})]
    ;; With markdown? false, no bold escape on the heading row.
    (is (not (re-find #"# Done.*\033\[1m" out))
        "raw mode keeps the heading unstyled")))

(deftest tool-pre-line-falls-back-when-no-cols
  (testing "without :cols, behaves as before (no clamping of args)"
    (let [args {:command "ls -la"}
          out (strip-ansi (render/tool-pre-line {:tool-name :bash.run :args args}))]
      (is (not (str/includes? out "…")) "short args don't get a clamp marker"))))

(deftest welcome-banner-renders-key-fields
  (let [out (strip-ansi (render/welcome-banner
                         {:agent-id :coact-agent
                          :session-id "sess-42"
                          :model "claude-code:sonnet"}))]
    (is (str/includes? out "Brainyard Agent TUI"))
    (is (str/includes? out "coact-agent"))
    (is (str/includes? out "sess-42"))
    (is (str/includes? out "claude-code:sonnet"))))

(deftest todo-block-empty-list-is-nil
  (is (nil? (render/todo-block {:todo-list []})))
  (is (nil? (render/todo-block {:todo-list nil}))))

(deftest todo-block-counts-and-rows
  (let [items [{:description "read source" :done true}
               {:description "draft answer" :done false}
               {:description "sanity-check" :done false}]
        out (strip-ansi (render/todo-block {:todo-list items
                                            :active-slug "feature-x"}))]
    (is (str/includes? out "todo"))
    (is (str/includes? out "1/3"))
    (is (str/includes? out "(feature-x)"))
    (is (str/includes? out "[x] read source"))
    (is (str/includes? out "[ ] draft answer"))))

(deftest task-created-line-shows-id-and-name
  (let [out (strip-ansi (render/task-created-line
                         {:id :task-7 :name "compile project"}))]
    (is (str/includes? out "task"))
    (is (str/includes? out ":task-7"))
    (is (str/includes? out "compile project"))))

(deftest task-completed-line-glyph-by-status
  (let [done   (strip-ansi (render/task-completed-line
                            {:id :task-1 :name "foo" :status :completed
                             :started-at 1000 :completed-at 3500}))
        failed (strip-ansi (render/task-completed-line
                            {:id :task-2 :name "bar" :status :failed}))]
    (is (str/includes? done "✓"))
    (is (str/includes? done "2.5s"))
    (is (str/includes? failed "✗"))))

(deftest status-line-includes-todo-progress-segment
  (let [out (strip-ansi (render/status-line
                         {:state :thinking
                          :agent-id :coact-agent
                          :model "claude-code:sonnet"
                          :iter 2 :max-iter 100
                          :tools-used 5
                          :todo-progress {:done 1 :total 3}
                          :queue-depth 0}))]
    (is (str/includes? out "thinking"))
    (is (str/includes? out "iter 2/100"))
    (is (str/includes? out "tools 5"))
    (is (str/includes? out "todo 1/3"))
    ;; queue-depth 0 should be omitted.
    (is (not (str/includes? out " q ")))))

(deftest status-line-omits-todo-when-empty
  (let [out (strip-ansi (render/status-line
                         {:state :idle
                          :agent-id :foo
                          :todo-progress nil}))]
    (is (not (str/includes? out "todo")))))

(deftest status-block-has-two-rows
  (testing "row 1 = state content; row 2 = mode (left) + version (right)"
    (let [snap {:state :thinking
                :agent-id :coact-agent
                :model "claude-code:sonnet"
                :iter 2 :max-iter 100
                :tools-used 5
                :queue-depth 0
                :mode :repl
                :version "0.1.0"}
          raw  (render/status-block snap)
          stripped (str/replace raw #"\x1b\[[0-9;]*[A-Za-z]" "")
          rows (str/split stripped #"\r\n")]
      (is (= 2 (count rows))
          (str "expected exactly 2 rows, got: " (vec rows)))
      ;; Row 1 carries the live state content.
      (let [row1 (first rows)]
        (is (str/includes? row1 "thinking"))
        (is (str/includes? row1 "coact-agent"))
        (is (str/includes? row1 "claude-code:sonnet"))
        (is (str/includes? row1 "iter 2/100")))
      ;; Row 2 carries the chrome: mode on left, version on right.
      (let [row2 (second rows)]
        (is (str/includes? row2 "mode repl"))
        (is (str/includes? row2 "v0.1.0"))
        ;; Mode should appear left of version.
        (is (< (str/index-of row2 "mode")
               (str/index-of row2 "v0.1.0")))))))

(deftest status-block-defaults-mode-and-version
  (testing "missing :mode/:version still produce a sensible chrome row"
    (let [raw (render/status-block {:state :idle :agent-id :foo})
          stripped (str/replace raw #"\x1b\[[0-9;]*[A-Za-z]" "")
          [_row1 row2] (str/split stripped #"\r\n")]
      (is (str/includes? row2 "mode repl"))
      (is (str/includes? row2 "v?")))))

(deftest empty-status-line-clears-both-rows
  (let [s (render/empty-status-line)]
    ;; Should contain a CR+LF separating the two row clears.
    (is (clojure.string/includes? s "\r\n"))))

(deftest help-block-lists-core-slashes
  (let [out (strip-ansi (render/help-block))]
    (is (str/includes? out "Commands"))
    (is (str/includes? out "/help"))
    (is (str/includes? out "/model"))
    (is (str/includes? out "/agent"))
    (is (str/includes? out "/session"))
    (is (str/includes? out "/quit"))
    (is (str/includes? out "Keys"))
    (is (str/includes? out "Ctrl-C"))))

(deftest model-block-renders-provider-and-model
  (let [out (strip-ansi (render/model-block
                         {:provider :claude-code :model "sonnet"}))]
    (is (str/includes? out "Current Model"))
    (is (str/includes? out "claude-code"))
    (is (str/includes? out "sonnet"))))

(deftest model-block-handles-missing-fields
  (let [out (strip-ansi (render/model-block {}))]
    (is (str/includes? out "?"))))

(deftest unknown-command-line-shows-cmd
  (let [out (strip-ansi (render/unknown-command-line "/nope"))]
    (is (str/includes? out "Unknown command: /nope"))
    (is (str/includes? out "/help"))))

(deftest clear-screen-bytes-emits-csi-2j
  (let [s (render/clear-screen-bytes)]
    ;; Expect ESC [ 2 J ESC [ H — clear + cursor home.
    (is (str/includes? s "[2J"))
    (is (str/includes? s "[H"))))

(deftest status-block-cmd-shows-snapshot-fields
  (let [out (strip-ansi
             (render/status-block-cmd
              {:state :thinking :agent-id :coact-agent
               :model "claude-code:sonnet"
               :iter 3 :max-iter 100 :tools-used 5
               :queue-depth 2 :mode :repl :version "0.1.0"}))]
    (is (str/includes? out "Agent Status"))
    (is (str/includes? out "thinking"))
    (is (str/includes? out "coact-agent"))
    (is (str/includes? out "claude-code:sonnet"))
    (is (str/includes? out "3 / 100"))
    (is (str/includes? out "tools     : 5"))
    (is (str/includes? out "queue     : 2"))
    (is (str/includes? out "mode      : repl"))
    (is (str/includes? out "version   : v0.1.0"))))

(deftest queue-block-empty-shows-marker
  (let [out (strip-ansi (render/queue-block []))]
    (is (str/includes? out "(queue is empty)"))))

(deftest queue-block-numbers-items
  (let [out (strip-ansi (render/queue-block
                         [{:input "first" :status :queued}
                          {:input "second" :status :queued}]))]
    (is (str/includes? out "1. first"))
    (is (str/includes? out "2. second"))))

(deftest usage-block-renders-tokens-and-cost
  (let [out (strip-ansi (render/usage-block
                         {:provider :claude-code :model "sonnet"
                          :input-tokens 1234 :output-tokens 567
                          :total-tokens 1801 :cost-usd 0.0145
                          :request-count 3}))]
    (is (str/includes? out "LLM Usage"))
    (is (str/includes? out "claude-code"))
    (is (str/includes? out "sonnet"))
    (is (str/includes? out "1234"))
    (is (str/includes? out "567"))
    (is (str/includes? out "$0.0145"))
    (is (str/includes? out "requests      : 3"))))

(deftest usage-block-with-history-shows-per-call-rows
  (testing "passing :history adds a per-call breakdown table after the aggregate"
    (let [history [{:input-tokens 100 :output-tokens 50
                    :cache {:read-tokens 0 :write-tokens 0}
                    :cost {:total-cost 0.001}
                    :provider :openai :model "gpt-4o-mini"}
                   {:input-tokens 200 :output-tokens 75
                    :cache {:read-tokens 50 :write-tokens 0}
                    :cost {:total-cost 0.002}
                    :provider :anthropic :model "claude-haiku"}]
          out (strip-ansi (render/usage-block
                           {:provider :openai :model "gpt-4o-mini"
                            :input-tokens 300 :output-tokens 125
                            :total-tokens 425 :cost-usd 0.003
                            :request-count 2 :history history}))]
      (is (str/includes? out "LLM Usage") "aggregate header still present")
      (is (str/includes? out "In Tok") "per-call header column shown")
      (is (str/includes? out "Cached") "Cached column shown")
      (is (str/includes? out "openai:gpt-4o-mini"))
      (is (str/includes? out "anthropic:claude-haiku"))
      (is (str/includes? out "$0.0010"))
      (is (str/includes? out "$0.0020"))
      (is (str/includes? out "100") "first call's input tokens")
      (is (str/includes? out "200") "second call's input tokens"))))

(deftest usage-block-without-history-omits-table
  (testing "no :history key ⇒ no per-call section appended"
    (let [out (strip-ansi (render/usage-block
                           {:provider :openai :model "gpt-4o-mini"
                            :input-tokens 10 :output-tokens 5
                            :total-tokens 15}))]
      (is (not (str/includes? out "In Tok"))
          "table header absent without history"))))

(deftest config-line-keyword-renders-by-name
  (is (str/includes?
       (strip-ansi (render/config-line :verbose :verbose))
       "= verbose"))
  (is (str/includes?
       (strip-ansi (render/config-line :verbose :debug))
       "= debug"))
  (is (str/includes?
       (strip-ansi (render/config-line :thinking true))
       "= true")))

(deftest iteration-header-format
  (is (= "[+] Iteration 3 / 100"
         (str/trim (strip-ansi (render/iteration-header 3 100)))))
  (is (= "[+] Iteration 5"
         (str/trim (strip-ansi (render/iteration-header 5 nil))))))

(deftest mcp-block-empty-marker
  (is (str/includes? (strip-ansi (render/mcp-block nil))
                     "(MCP not available)"))
  (is (str/includes? (strip-ansi (render/mcp-block {:total 0 :connected 0 :servers []}))
                     "(no MCP servers configured)")))

(deftest mcp-block-renders-summary
  (let [out (strip-ansi
             (render/mcp-block
              {:total 2 :connected 1
               :servers [{:name "fs" :enabled true :connected true
                          :transport :stdio :tool-count 7}
                         {:name "weather" :enabled false :connected false
                          :transport :http :tool-count nil}]}))]
    (is (str/includes? out "MCP Servers"))
    (is (str/includes? out "(2 configured, 1 connected)"))
    (is (str/includes? out "fs"))
    (is (str/includes? out "weather"))
    (is (str/includes? out "transport=stdio"))
    (is (str/includes? out "tools=7"))
    (is (str/includes? out "tools=—"))))

(deftest trace-block-empty-marker
  (is (str/includes? (strip-ansi (render/trace-block nil))
                     "(no traces yet"))
  (is (str/includes? (strip-ansi (render/trace-block []))
                     "(no traces yet")))

(deftest trace-block-renders-rows
  (let [traces [{:agent-id :coact-agent :depth 0 :content "plan started"}
                {:agent-id :coact-agent :depth 1 :content "exec node"}
                {:agent-id :sub :depth 2
                 :content (apply str (repeat 200 "x"))}]
        out (strip-ansi (render/trace-block traces :n 5))]
    (is (str/includes? out "BT Trace"))
    (is (str/includes? out "(last 3 of 3)"))
    (is (str/includes? out "coact-agent"))
    (is (str/includes? out "exec node"))
    ;; long content truncated with ellipsis
    (is (str/includes? out "…"))))

(deftest trace-block-respects-n-limit
  (let [traces (vec (for [i (range 10)] {:agent-id :a :depth 0 :content (str "n=" i)}))
        out   (strip-ansi (render/trace-block traces :n 3))]
    (is (str/includes? out "(last 3 of 10)"))
    (is (str/includes? out "n=7"))
    (is (str/includes? out "n=9"))
    (is (not (str/includes? out "n=0")))))

(deftest reasoning-block-empty-is-nil
  (is (nil? (render/reasoning-block nil)))
  (is (nil? (render/reasoning-block "")))
  (is (nil? (render/reasoning-block "   \n\t "))))

(deftest reasoning-block-renders-multiline
  (let [out (strip-ansi (render/reasoning-block "step one\nstep two\nstep three"))]
    (is (str/includes? out "[reasoning]"))
    (is (str/includes? out "step one"))
    (is (str/includes? out "step two"))
    (is (str/includes? out "step three"))))

(deftest observation-block-empty-is-nil
  (is (nil? (render/observation-block nil)))
  (is (nil? (render/observation-block "")))
  (is (nil? (render/observation-block "   \n\t "))))

(deftest observation-block-renders-text
  (let [out (strip-ansi (render/observation-block "tool returned 42 rows"))]
    (is (str/includes? out "[observe]"))
    (is (str/includes? out "tool returned 42 rows"))))

(deftest reasoning-block-wraps-when-cols-given
  (testing "long reasoning row word-wraps to fit pane width"
    (let [long-line (apply str (repeat 80 "x "))
          out (strip-ansi (render/reasoning-block long-line {:cols 40}))
          rows (->> (str/split-lines out)
                    (remove clojure.string/blank?)
                    (remove #(re-find #"^\[reasoning\]" %)))]
      (is (>= (count rows) 2) "long row wrapped to multiple sub-rows")
      (is (every? #(<= (count %) 44) rows)
          "every row fits within :cols + small ANSI margin"))))

(deftest observation-block-wraps-when-cols-given
  (testing "long observation row word-wraps to fit pane width"
    (let [long-line "the agent observed something quite long that goes well beyond the pane width and needs wrapping"
          out (strip-ansi (render/observation-block long-line {:cols 30}))
          rows (->> (str/split-lines out)
                    (remove clojure.string/blank?)
                    (remove #(re-find #"^\[observe\]" %)))]
      (is (>= (count rows) 2) "long observation wrapped")
      (is (every? #(<= (count %) 34) rows) "rows fit within :cols + margin"))))

(deftest goal-status-line-achieved
  (let [out (strip-ansi (render/goal-status-line
                         {:goal-achieved? true
                          :goal-reasoning "user request fully addressed"}))]
    (is (str/includes? out "✓ Goal achieved"))
    (is (str/includes? out "user request fully addressed"))))

(deftest goal-status-line-not-achieved
  (let [out (strip-ansi (render/goal-status-line
                         {:goal-achieved? false
                          :goal-reasoning "still need to verify"}))]
    (is (str/includes? out "✗ Goal not yet achieved"))
    (is (str/includes? out "still need to verify"))))

(deftest goal-status-line-omits-blank-reasoning
  (let [out (strip-ansi (render/goal-status-line {:goal-achieved? true}))]
    (is (str/includes? out "✓ Goal achieved"))
    (is (not (str/includes? out "()"))
        "no empty parens for missing reasoning")))

(deftest goal-status-line-has-leading-blank-line
  (testing "goal verdict prepends a newline so it separates visually from the previous emit"
    (let [out (render/goal-status-line {:goal-achieved? true})]
      (is (.startsWith ^String out "\n")
          "leading newline guarantees a blank row before the verdict"))))

(deftest agent-eval-line-renders-fields
  (let [out (strip-ansi (render/agent-eval-line
                         {:score 0.85 :verdict :pass
                          :reason "passes the rubric"}))]
    (is (str/includes? out "[eval]"))
    (is (str/includes? out "pass"))
    (is (str/includes? out "score=0.85"))
    (is (str/includes? out "passes the rubric"))))

(deftest agent-eval-line-fail-color
  (let [out (render/agent-eval-line {:verdict :fail :reason "x"})]
    ;; Verdict styled red on fail.
    (is (str/includes? out "\033[91m"))))

(deftest code-display-block-empty-is-nil
  (is (nil? (render/code-display-block nil)))
  (is (nil? (render/code-display-block []))))

(deftest code-display-block-single-block
  (let [out (strip-ansi (render/code-display-block
                         [{:code "(+ 1 2)"}]))]
    (is (str/includes? out "• Code:")
        "single block uses unsuffixed label")
    (is (str/includes? out "│ (+ 1 2)"))))

(deftest code-display-block-multi-block
  (let [out (strip-ansi (render/code-display-block
                         [{:code "(println :a)"}
                          {:code "(println :b)"}]))]
    (is (str/includes? out "• Code[1]:"))
    (is (str/includes? out "• Code[2]:"))))

(deftest eval-result-block-empty-is-nil
  (is (nil? (render/eval-result-block nil)))
  (is (nil? (render/eval-result-block []))))

(deftest eval-result-block-renders-result-output-error
  (let [out (strip-ansi
             (render/eval-result-block
              [{:result "42"
                :output "computed: 42\n"
                :error nil}]))]
    (is (str/includes? out "• Result:"))
    (is (str/includes? out "42"))
    (is (str/includes? out "• Output:"))
    (is (str/includes? out "computed: 42"))
    (is (not (str/includes? out "• Error:")))))

(deftest eval-result-block-suppresses-nil-result
  (testing "side-effecting blocks (nil return + no output/error) → fully empty render"
    (let [out (render/eval-result-block [{:result "nil"}])]
      (is (nil? out)
          "all-empty entry produces no block at all")))
  (testing "nil result still renders block when there's output"
    (let [out (strip-ansi (render/eval-result-block
                           [{:result "nil" :output "side effect ran"}]))]
      (is (not (str/includes? out "• Result")) "Result section dropped")
      (is (str/includes? out "• Output:") "Output still rendered")
      (is (str/includes? out "side effect ran")))))

(deftest eval-result-block-error-is-red
  (let [out (render/eval-result-block
             [{:error "java.lang.ArithmeticException: divide by zero"}])]
    (is (str/includes? out "\033[91m")
        "error body lines styled bright-red")
    (is (str/includes? out "• Error:"))))

(deftest eval-result-block-multi-block-suffix
  (let [out (strip-ansi
             (render/eval-result-block
              [{:result "1"} {:result "2"} {:error "boom"}]))]
    (is (str/includes? out "• Result[1]:"))
    (is (str/includes? out "• Result[2]:"))
    (is (str/includes? out "• Error[3]:"))))

(deftest agents-block-empty-marker
  (is (str/includes? (strip-ansi (render/agents-block nil)) "(no agents registered)"))
  (is (str/includes? (strip-ansi (render/agents-block [])) "(no agents registered)")))

(deftest agents-block-renders-rows
  (let [out (strip-ansi
             (render/agents-block
              [{:session-id "sess-1" :agent-id :coact-agent :name "Main"}
               {:session-id "sess-2" :agent-id :react-agent :name nil}]))]
    (is (str/includes? out "Active Agents"))
    (is (str/includes? out "(2)"))
    (is (str/includes? out "sess-1"))
    (is (str/includes? out "sess-2"))
    (is (str/includes? out "coact-agent"))
    (is (str/includes? out "react-agent"))
    (is (str/includes? out "Main"))))

(deftest sessions-block-empty-marker
  (is (str/includes? (strip-ansi (render/sessions-block []))
                     "(no persisted sessions)")))

(deftest sessions-block-renders-rows
  (let [out (strip-ansi
             (render/sessions-block
              [{:session-id "abc-123" :agent-id :coact-agent :locked? true
                :size-bytes 2048 :age-ms 90000}
               {:session-id "stale-99" :agent-id :react :locked? false
                :size-bytes 500 :age-ms 86400000}]))]
    (is (str/includes? out "Persisted Sessions"))
    (is (str/includes? out "(2)"))
    (is (str/includes? out "abc-123"))
    (is (str/includes? out "stale-99"))
    (is (str/includes? out "● live"))
    (is (str/includes? out "○ idle"))
    (is (str/includes? out "2.0K"))
    (is (str/includes? out "age=1m"))
    (is (str/includes? out "age=1d"))))

(deftest tool-activity-line-success-uses-check-glyph
  (let [out (strip-ansi
             (render/tool-activity-line
              {:tool-name :bash.run
               :tool-args {:command "ls -la"}
               :tool-result "total 12\nfoo bar"}))]
    (is (str/includes? out "✓"))
    (is (str/includes? out "bash.run"))
    (is (str/includes? out "command=ls -la"))
    (is (str/includes? out "→ total 12 foo bar"))))

(deftest tool-activity-line-error-uses-cross
  (let [out (strip-ansi
             (render/tool-activity-line
              {:tool-name :read.file
               :tool-args {:path "/missing"}
               :tool-result {:error "ENOENT"}}))]
    (is (str/includes? out "✗"))
    (is (str/includes? out "read.file"))
    (is (str/includes? out "ENOENT"))))

(deftest tool-activity-line-truncates-long-result
  (let [long-r (apply str (repeat 200 "x"))
        out (strip-ansi
             (render/tool-activity-line
              {:tool-name :foo :tool-args {} :tool-result long-r}))]
    (is (str/includes? out "…"))
    (is (< (count out) 130))))

(deftest status-content-thinking-shows-braille-spinner
  (testing "active states (:thinking, :running) show a braille frame; idle keeps `●`"
    (let [thinking (strip-ansi (render/status-line
                                {:state :thinking :agent-id :a :tick 0}))
          running  (strip-ansi (render/status-line
                                {:state :running :agent-id :a :tick 0}))
          idle     (strip-ansi (render/status-line
                                {:state :idle :agent-id :a :tick 0}))]
      (is (str/includes? thinking "⠋") "tick=0 ⇒ first braille frame")
      (is (str/includes? running "⠋"))
      (is (str/includes? idle "●") "idle keeps the static bullet")
      (is (not (str/includes? idle "⠋"))))))

(deftest status-content-spinner-cycles-with-tick
  (testing ":tick selects the braille frame; consecutive paints advance"
    (let [out0 (strip-ansi (render/status-line
                            {:state :thinking :agent-id :a :tick 0}))
          out3 (strip-ansi (render/status-line
                            {:state :thinking :agent-id :a :tick 3}))
          out10 (strip-ansi (render/status-line
                             {:state :thinking :agent-id :a :tick 10}))]
      (is (str/includes? out0 "⠋"))
      (is (str/includes? out3 "⠸") "frame 3 ⇒ ⠸")
      (is (str/includes? out10 "⠋") "frame 10 wraps back to ⠋ (mod 10)"))))

(deftest status-content-includes-current-tool-when-running
  (let [out (strip-ansi (render/status-line
                         {:state :running
                          :agent-id :coact-agent
                          :model "sonnet"
                          :iter 2 :max-iter 100
                          :tools-used 3
                          :last-tool :bash.run}))]
    (is (str/includes? out "▸ bash.run"))
    (is (str/includes? out "tools 3"))))

(deftest status-content-omits-current-tool-when-idle
  (let [out (strip-ansi (render/status-line
                         {:state :idle
                          :agent-id :coact-agent
                          :last-tool :bash.run}))]
    (is (not (str/includes? out "▸")))))

(deftest capture-result-line-success
  (let [out (strip-ansi (render/capture-result-line
                         {:path "/tmp/foo.txt" :bytes-written 1234}))]
    (is (str/includes? out "✓ captured"))
    (is (str/includes? out "1234"))
    (is (str/includes? out "/tmp/foo.txt"))))

(deftest capture-result-line-error
  (is (str/includes? (strip-ansi (render/capture-result-line
                                  {:error "no scrollback"}))
                     "✗ /capture — no scrollback")))

(deftest allow-path-block-list
  (let [out (strip-ansi (render/allow-path-block
                         {:dirs ["/tmp" "/Users/me/proj"]}))]
    (is (str/includes? out "Allowed paths"))
    (is (str/includes? out "(2)"))
    (is (str/includes? out "/tmp"))
    (is (str/includes? out "/Users/me/proj"))))

(deftest allow-path-block-added
  (let [out (strip-ansi (render/allow-path-block {:added "/tmp/data"}))]
    (is (str/includes? out "✓ allowed"))
    (is (str/includes? out "/tmp/data"))))

(deftest allow-path-block-error
  (is (str/includes? (strip-ansi (render/allow-path-block
                                  {:error "path does not exist: /nope"}))
                     "✗ /allow-path")))

(deftest task-detail-block-renders-fields
  (let [out (strip-ansi (render/task-detail-block
                         {:id :task-7 :name "compile" :status :running
                          :job-type :bash :job-config {:command "make"}
                          :created-at 1714000000000
                          :started-at 1714000001000}))]
    (is (str/includes? out "Task"))
    (is (str/includes? out ":task-7"))
    (is (str/includes? out "name"))
    (is (str/includes? out "compile"))
    (is (str/includes? out "running"))
    (is (str/includes? out "bash"))
    (is (str/includes? out "command"))))

(deftest task-detail-block-not-found
  (is (str/includes? (strip-ansi (render/task-detail-block nil))
                     "✗ task not found")))

(deftest task-log-block-empty
  (is (str/includes? (strip-ansi (render/task-log-block :t1 []))
                     "(no output for task :t1)")))

(deftest task-log-block-tails
  (let [out (strip-ansi (render/task-log-block :t1
                                               (vec (for [i (range 8)] (str "line " i)))
                                               :n 3))]
    (is (str/includes? out "Task log"))
    (is (str/includes? out ":t1"))
    (is (str/includes? out "(last 3 of 8)"))
    (is (str/includes? out "line 7"))
    (is (str/includes? out "line 5"))
    (is (not (str/includes? out "line 0")))))

(deftest usage-by-iteration-block-empty
  (is (str/includes? (strip-ansi (render/usage-by-iteration-block []))
                     "(no usage recorded yet)")))

(deftest usage-by-iteration-block-groups
  (let [history [{:iteration 1 :input-tokens 100 :output-tokens 50
                  :cost {:total-cost 0.01}}
                 {:iteration 1 :input-tokens 80  :output-tokens 60
                  :cost {:total-cost 0.02}}
                 {:iteration 2 :input-tokens 200 :output-tokens 70
                  :cache {:read-tokens 30}
                  :cost {:total-cost 0.03}}
                 {:input-tokens 5 :output-tokens 5}]
        out (strip-ansi (render/usage-by-iteration-block history))]
    (is (str/includes? out "Usage by Iteration"))
    (is (str/includes? out "iter"))
    (is (str/includes? out "calls"))
    ;; Iteration 1: 2 calls, 180 in, 110 out, 0 cached, $0.0300
    (is (str/includes? out "180"))
    (is (str/includes? out "110"))
    (is (str/includes? out "$0.0300"))
    ;; Iteration 2: 1 call, 200 in, 70 out, 30 cached, $0.0300
    (is (str/includes? out "200"))
    (is (str/includes? out "30"))
    ;; Unknown bucket renders as `?`
    (is (str/includes? out "?"))))

(deftest clear-tasks-line-zero
  (is (str/includes? (strip-ansi (render/clear-tasks-line 0))
                     "(no completed/failed tasks to clear)")))

(deftest clear-tasks-line-purged
  (let [out (strip-ansi (render/clear-tasks-line 5))]
    (is (str/includes? out "✓ purged"))
    (is (str/includes? out "5"))
    (is (str/includes? out "task(s) from the registry"))))

(deftest messages-block-empty-marker
  (is (str/includes? (strip-ansi (render/messages-block []))
                     "(no messages yet)")))

(deftest messages-block-renders-roles-and-content
  (let [out (strip-ansi
             (render/messages-block
              [{:role "user"      :content "What is 2+2?"}
               {:role "assistant" :content "It is 4."}
               {:role "tool"      :tool-name :calc :content "{:result 4}"}]))]
    (is (str/includes? out "Messages"))
    (is (str/includes? out "(3 shown)"))
    (is (str/includes? out "user"))
    (is (str/includes? out "assistant"))
    (is (str/includes? out "tool"))
    (is (str/includes? out "[:calc]"))
    (is (str/includes? out "What is 2+2?"))
    (is (str/includes? out "It is 4."))))

(deftest messages-block-truncates-long-content
  (let [long-msg (apply str (repeat 300 "x"))
        out (strip-ansi
             (render/messages-block [{:role "assistant" :content long-msg}]))]
    (is (str/includes? out "…"))))

(deftest memory-recall-block-empty
  (is (str/includes? (strip-ansi (render/memory-recall-block "deploy" []))
                     "(no memory hits for")))

(deftest session-destroy-line-success
  (let [out (strip-ansi (render/session-destroy-line
                         {:id "agt-d-9" :deleted? true}))]
    (is (str/includes? out "✓ destroyed"))
    (is (str/includes? out "agt-d-9"))))

(deftest session-destroy-line-not-found
  (is (str/includes? (strip-ansi (render/session-destroy-line
                                  {:id "missing-sid" :deleted? false}))
                     "session not found: missing-sid")))

(deftest session-destroy-line-error
  (is (str/includes? (strip-ansi (render/session-destroy-line
                                  {:error "usage: /session prune <session-id>"}))
                     "✗ /session destroy — usage:")))

(deftest memory-forget-line-success
  (let [out (strip-ansi (render/memory-forget-line
                         {:id "abc-123" :existed? true}))]
    (is (str/includes? out "✓ forgotten"))
    (is (str/includes? out "abc-123"))))

(deftest memory-forget-line-missing
  (let [out (strip-ansi (render/memory-forget-line
                         {:id "missing" :existed? false}))]
    (is (str/includes? out "✗ /memory forget"))
    (is (str/includes? out "no entry with id"))))

(deftest memory-forget-line-error
  (is (str/includes? (strip-ansi (render/memory-forget-line
                                  {:error "manager not initialized"}))
                     "✗ /memory forget — manager not initialized")))

(deftest memory-list-block-empty
  (is (str/includes? (strip-ansi (render/memory-list-block []))
                     "(no entries)")))

(deftest memory-list-block-empty-with-filter
  (is (str/includes? (strip-ansi (render/memory-list-block
                                  []
                                  :tag-filter #{"ops" "deploy"}))
                     "(no entries for #deploy #ops)")))

(deftest memory-list-block-renders-rows
  (let [out (strip-ansi
             (render/memory-list-block
              [{:id "f-1" :content "user prefers tabs"
                :tags #{"editor"} :layer :l3}
               {:id "f-2" :fact "deploys via blue-green"
                :tags #{"ops" "deploy"}}]))]
    (is (str/includes? out "Memory"))
    (is (str/includes? out "2 entries"))
    (is (str/includes? out "f-1"))
    (is (str/includes? out "[l3]"))
    (is (str/includes? out "#editor"))
    (is (str/includes? out "user prefers tabs"))
    (is (str/includes? out "f-2"))
    (is (str/includes? out "#deploy"))
    (is (str/includes? out "deploys via blue-green"))))

(deftest memory-list-block-header-shows-filter
  (let [out (strip-ansi
             (render/memory-list-block
              [{:id "f-1" :content "x" :tags #{"ops"} :layer :l3}]
              :tag-filter #{"ops"}))]
    (is (str/includes? out "1 entries · filter ops"))))

(deftest memory-recall-block-empty-with-tag-filter
  (let [out (strip-ansi (render/memory-recall-block "deploy" [] #{"ops" "prod"}))]
    (is (str/includes? out "(no memory hits"))
    (is (str/includes? out "#ops"))
    (is (str/includes? out "#prod"))))

(deftest memory-recall-block-renders-tag-chips
  (let [out (strip-ansi
             (render/memory-recall-block
              "deploy"
              [{:_layer :l3 :_rrf_score 0.7 :content "blue-green deploy plan"
                :tags #{"ops" "deploy"}}]))]
    (is (str/includes? out "#deploy"))
    (is (str/includes? out "#ops"))
    (is (str/includes? out "blue-green deploy plan"))))

(deftest memory-recall-block-header-shows-active-filter
  (let [out (strip-ansi
             (render/memory-recall-block
              "deploy"
              [{:_layer :l3 :_rrf_score 0.5 :content "x"}]
              #{"ops"}))]
    (is (str/includes? out "filter ops"))))

(deftest memory-recall-block-renders-rows
  (let [out (strip-ansi
             (render/memory-recall-block
              "deploy"
              [{:_layer :l1 :_rrf_score 0.872 :content "deployed v0.5 yesterday"}
               {:_layer :l2 :_rrf_score 0.412 :fact "user prefers blue/green"}
               {:_layer :l3 :_rrf_score 0.205 :summary "ops runbook v3"}]))]
    (is (str/includes? out "Memory recall"))
    (is (str/includes? out "\"deploy\""))
    (is (str/includes? out "3 hits"))
    (is (str/includes? out "[l1]"))
    (is (str/includes? out "[l2]"))
    (is (str/includes? out "[l3]"))
    (is (str/includes? out "0.872"))
    (is (str/includes? out "deployed v0.5 yesterday"))
    (is (str/includes? out "blue/green"))
    (is (str/includes? out "ops runbook v3"))))

(deftest context-block-renders-present-fields
  (let [out (strip-ansi (render/context-block
                         {:instruction   "You are a helpful assistant."
                          :tool-count    7
                          :message-count 12
                          :iteration     3
                          :max-iter      100
                          :todo-progress {:done 1 :total 4}
                          :last-reasoning "Step 1: parse input. Step 2: dispatch."}))]
    (is (str/includes? out "Context"))
    (is (str/includes? out "instruction"))
    (is (str/includes? out "helpful assistant"))
    (is (str/includes? out "tools"))
    (is (str/includes? out "7 bound"))
    (is (str/includes? out "messages"))
    (is (str/includes? out "12 in conversation"))
    (is (str/includes? out "3 / 100"))
    (is (str/includes? out "1/4"))
    (is (str/includes? out "last-reasoning"))
    (is (str/includes? out "parse input"))))

(deftest memory-remember-line-error
  (is (str/includes? (strip-ansi (render/memory-remember-line
                                  {:error "memory not available"}))
                     "✗ /memory remember"))
  (is (str/includes? (strip-ansi (render/memory-remember-line nil))
                     "write returned nil")))

(deftest memory-remember-line-ok
  (let [out (strip-ansi
             (render/memory-remember-line
              {:entry {:id "fact-42" :content "user prefers tabs"}
               :fact-type :preference}))]
    (is (str/includes? out "✓ remembered"))
    (is (str/includes? out "fact-42"))
    (is (str/includes? out "[preference]"))
    (is (str/includes? out "user prefers tabs"))))

(deftest memory-stats-block-empty
  (is (str/includes? (strip-ansi (render/memory-stats-block nil))
                     "(memory not initialized)"))
  (is (str/includes? (strip-ansi (render/memory-stats-block {}))
                     "(memory not initialized)")))

(deftest memory-stats-block-renders-fields
  (let [out (strip-ansi (render/memory-stats-block
                         {:episodes 17 :semantic-facts 5
                          :schema-version "v3"}))]
    (is (str/includes? out "Memory"))
    (is (str/includes? out "episodes"))
    (is (str/includes? out "17"))
    (is (str/includes? out "semantic-facts"))
    (is (str/includes? out "5"))
    (is (str/includes? out "v3"))))

(deftest lm-list-block-empty
  (is (str/includes? (strip-ansi (render/lm-list-block {}))
                     "(no providers registered)")))

(deftest lm-list-block-renders-providers
  (let [out (strip-ansi
             (render/lm-list-block
              {:current {:provider :anthropic :model "sonnet"}
               :providers
               [{:provider :anthropic :env-var "ANTHROPIC_API_KEY"
                 :env-set? true
                 :models ["claude-sonnet-4-6" "claude-haiku-4-5" "claude-opus-4-7"]}
                {:provider :openai    :env-var "OPENAI_API_KEY"
                 :env-set? false
                 :models ["gpt-5" "gpt-4.1" "o4-mini" "o3"]}
                {:provider :claude-code :env-var nil :env-set? false
                 :models []}]}))]
    (is (str/includes? out "LM Providers"))
    (is (str/includes? out "current: anthropic / sonnet"))
    (is (str/includes? out "anthropic"))
    (is (str/includes? out "ANTHROPIC_API_KEY"))
    (is (str/includes? out "claude-sonnet-4-6"))
    (is (str/includes? out "openai"))
    (is (str/includes? out "OPENAI_API_KEY"))
    (is (str/includes? out "+1 more"))
    (is (str/includes? out "claude-code"))))

(deftest lm-switch-line-success
  (let [out (strip-ansi
             (render/lm-switch-line
              {:old {:provider :claude-code :model "sonnet"}
               :new {:provider :anthropic    :model "claude-opus-4-7"}}))]
    (is (str/includes? out "✓ switched"))
    (is (str/includes? out "claude-code/sonnet"))
    (is (str/includes? out "→"))
    (is (str/includes? out "anthropic/claude-opus-4-7"))))

(deftest lm-switch-line-error
  (is (str/includes? (strip-ansi (render/lm-switch-line {:error "no API key"}))
                     "✗ /lm switch")))

(deftest lm-test-block-success
  (let [out (strip-ansi (render/lm-test-block
                         {:provider :openai :model "gpt-4o"
                          :latency-ms 423 :answer "OK"}))]
    (is (str/includes? out "LM probe"))
    (is (str/includes? out "openai/gpt-4o"))
    (is (str/includes? out "(423ms)"))
    (is (str/includes? out "→ OK"))))

(deftest lm-test-block-error
  (is (str/includes? (strip-ansi (render/lm-test-block {:error "401 Unauthorized"}))
                     "✗ /lm test"))
  (is (str/includes? (strip-ansi (render/lm-test-block {:error "401 Unauthorized"}))
                     "401 Unauthorized")))

(deftest skills-list-block-empty
  (is (str/includes? (strip-ansi (render/skills-list-block []))
                     "(no skills available)")))

(deftest skills-list-block-renders-types
  (let [out (strip-ansi
             (render/skills-list-block
              [{:name "deploy"  :type :brainyard :scope :project
                :description "Push to staging then prod with smoke check"}
               {:name "summarize" :type :claude :description "Pull-request summarizer"}
               {:name "react-agent" :type :agents}]))]
    (is (str/includes? out "Skills"))
    (is (str/includes? out "(3)"))
    (is (str/includes? out "[brainyard]"))
    (is (str/includes? out "[claude]"))
    (is (str/includes? out "[agents]"))
    (is (str/includes? out "deploy"))
    (is (str/includes? out "summarize"))
    (is (str/includes? out "react-agent"))
    (is (str/includes? out "(project)"))
    (is (str/includes? out "smoke check"))))

(deftest skill-read-block-error
  (is (str/includes? (strip-ansi (render/skill-read-block
                                  {:error "not found: foo"}))
                     "✗ /skills read"))
  (is (str/includes? (strip-ansi (render/skill-read-block
                                  {:error "not found: foo"}))
                     "not found: foo")))

(deftest skill-read-block-renders-header-and-body
  (let [out (strip-ansi
             (render/skill-read-block
              {:name "deploy" :type :brainyard :scope :project
               :path "/Users/me/.brainyard/skills/deploy"
               :size 200 :description "Push to staging then prod"
               :content "# Deploy\n\nStep 1.\nStep 2.\n"}))]
    (is (str/includes? out "Skill"))
    (is (str/includes? out "deploy"))
    (is (str/includes? out "[brainyard]"))
    (is (str/includes? out "(project)"))
    (is (str/includes? out "200B"))
    (is (str/includes? out "/Users/me/.brainyard/skills/deploy"))
    (is (str/includes? out "Push to staging then prod"))
    (is (str/includes? out "# Deploy"))
    (is (str/includes? out "Step 1."))))

(deftest skill-read-block-truncates-large-body
  (let [body (apply str (repeat 6000 "x"))
        out (strip-ansi
             (render/skill-read-block
              {:name "big" :type :claude :content body}
              {:max-bytes 100}))]
    (is (str/includes? out "more — use /capture for full body"))
    (is (< (count out) 1500))))

(deftest tools-list-block-empty
  (is (str/includes? (strip-ansi (render/tools-list-block {}))
                     "(no tools registered)"))
  (is (str/includes? (strip-ansi (render/tools-list-block {} {:type-filter :skill}))
                     "(no skill tools registered)")))

(deftest tools-list-block-renders-mixed-types
  (let [defs {:cmd-help    {:id :cmd-help    :type :command
                            :meta {:doc "Show help"}}
              :skill-deploy {:id :skill-deploy :type :skill
                             :meta {:description "Deploy to prod with smoke check"}}
              :react-agent  {:id :react-agent  :type :agent
                             :meta {:doc "ReAct loop"}}
              :tool-bash    {:id :tool-bash    :type :tool
                             :meta {:doc "Run shell"}}}
        out (strip-ansi (render/tools-list-block defs))]
    (is (str/includes? out "Tools"))
    (is (str/includes? out "(4)"))
    (is (str/includes? out "[command]"))
    (is (str/includes? out "[skill]"))
    (is (str/includes? out "[agent]"))
    (is (str/includes? out "[tool]"))
    (is (str/includes? out ":cmd-help"))
    (is (str/includes? out "Show help"))
    (is (str/includes? out "Deploy to prod with smoke check"))))

(deftest tools-list-block-type-filter
  (let [defs {:cmd-a  {:id :cmd-a :type :command :meta {:doc "A"}}
              :skill-b {:id :skill-b :type :skill :meta {:doc "B"}}}
        out (strip-ansi (render/tools-list-block defs {:type-filter :skill}))]
    (is (str/includes? out "Tools"))
    (is (str/includes? out "(1)"))
    (is (str/includes? out ":skill-b"))
    (is (not (str/includes? out ":cmd-a")))))

(deftest activity-collapse-line-renders-marker
  (let [out (strip-ansi (render/activity-collapse-line :tool))]
    (is (str/includes? out "▸ tool"))
    (is (str/includes? out "(see /activity)"))))

(deftest activity-collapse-line-defaults-to-question-mark
  (let [out (strip-ansi (render/activity-collapse-line nil))]
    (is (str/includes? out "▸ ?"))))

(deftest lm-models-block-empty-marker
  (is (str/includes? (strip-ansi (render/lm-models-block
                                  {:provider :foo :models []}))
                     "(no models known for provider foo)")))

(deftest lm-models-block-paginates
  (let [models (mapv #(str "model-" %) (range 47))
        page1 (subvec models 0 20)
        out (strip-ansi
             (render/lm-models-block
              {:provider :openai :page 1 :per-page 20 :total 47 :models page1}))]
    (is (str/includes? out "Models"))
    (is (str/includes? out "openai"))
    (is (str/includes? out "(1–20 of 47)"))
    (is (str/includes? out "  1. model-0"))
    (is (str/includes? out " 20. model-19"))
    (is (str/includes? out "/lm models openai 2  →  next page"))))

(deftest lm-models-block-final-page-omits-next
  (let [models (mapv #(str "m" %) (range 7))
        out (strip-ansi
             (render/lm-models-block
              {:provider :ollama :page 1 :per-page 20 :total 7 :models models}))]
    (is (str/includes? out "(1–7 of 7)"))
    (is (not (str/includes? out "next page")))))

(deftest memory-remember-line-with-tags
  (let [out (strip-ansi
             (render/memory-remember-line
              {:entry {:id "f-1" :content "user prefers tabs"
                       :tags #{"editor" "preference"}}
               :fact-type :preference}))]
    (is (str/includes? out "✓ remembered"))
    (is (str/includes? out "[preference]"))
    (is (str/includes? out "#editor"))
    (is (str/includes? out "#preference"))
    (is (str/includes? out "user prefers tabs"))))

(deftest activity-banner-renders-feeds
  (let [out (strip-ansi (render/activity-banner))]
    (is (str/includes? out "Agent Activity"))
    (is (str/includes? out "tools") "tool events advertised")
    (is (str/includes? out "todos") "todo events advertised")
    (is (str/includes? out "sub-agents") "sub-agent lifecycle events advertised")
    (is (str/includes? out "/activity hide") "dismiss hint included")))

(deftest with-activity-timestamp-prepends-hh-mm-ss
  (let [stamped (render/with-activity-timestamp "⮕ tool foo=1\n✓ tool\n")
        plain   (strip-ansi stamped)]
    (is (re-find #"\d\d:\d\d:\d\d ⮕ tool foo=1" plain)
        "tool-pre line carries a timestamp prefix")
    (is (re-find #"\d\d:\d\d:\d\d ✓ tool" plain)
        "tool-post line carries a timestamp prefix")
    (is (.endsWith ^String stamped "\n")
        "trailing newline preserved so block boundaries don't merge"))
  (testing "blank lines stay blank — no timestamp on visual separators"
    (let [out (strip-ansi (render/with-activity-timestamp "alpha\n\nbeta\n"))]
      (is (re-find #"\d\d:\d\d:\d\d alpha\n\n\d\d:\d\d:\d\d beta" out))))
  (testing "nil block round-trips to nil"
    (is (nil? (render/with-activity-timestamp nil)))))

(deftest context-block-omits-missing-fields
  (let [out (strip-ansi (render/context-block {:tool-count 3}))]
    (is (str/includes? out "Context"))
    (is (str/includes? out "tools"))
    (is (not (str/includes? out "instruction")))
    (is (not (str/includes? out "messages")))
    (is (not (str/includes? out "todo")))))
