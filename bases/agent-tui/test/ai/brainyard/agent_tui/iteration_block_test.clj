;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.iteration-block-test
  "Pure-function + handler-flow tests for iteration live blocks.

   Covers:
   - The formatters (args, elapsed, result-chars).
   - The renderer `render-iteration-block-lines` for tools-only,
     code-only, error, and done states.
   - The 11 hook handlers wired through session.clj — by stubbing
     `agent/get-bt-st-memory` and `find-session-for-agent` we exercise the
     full state-machine in `!iteration-blocks` without spinning up a real
     agent or terminal.
   - The iteration-sink protocol surface — verified by installing a
     recording sink and asserting the ops emitted across a full
     iteration lifecycle."
  (:require [ai.brainyard.agent-tui.session :as s]
            [ai.brainyard.agent-tui.iteration-sink :as iter-sink]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent.interface :as agent]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(defn- strip-ansi [^String x]
  (when x (str/replace x #"\x1b\[[0-9;]*[A-Za-z]" "")))

(defn- joined [lines]
  (str/join "\n" (map strip-ansi lines)))

;; ----------------------------------------------------------------------------
;; Test fixtures: clean shared state
;; ----------------------------------------------------------------------------

(defn- reset-state-fixture [t]
  (reset! @#'s/!iteration-blocks {})
  (reset! @#'s/!subagents-blocks {})
  (try (t)
       (finally
         (reset! @#'s/!iteration-blocks {})
         (reset! @#'s/!subagents-blocks {}))))

(use-fixtures :each reset-state-fixture)

;; ----------------------------------------------------------------------------
;; Formatters (private — accessed via #')
;; ----------------------------------------------------------------------------

(deftest format-iter-args-truncates-past-120
  (let [tiny-args   {:k "v"}
        mid-args    {:msg (apply str (repeat 100 "x"))} ; ~109 chars pr-str → within cap
        long-args   {:msg (apply str (repeat 130 "x"))} ; ~139 chars pr-str → truncated
        out-tiny    (#'s/format-iter-args tiny-args)
        out-mid     (#'s/format-iter-args mid-args)
        out-long    (#'s/format-iter-args long-args)]
    (is (= "{:k \"v\"}" out-tiny) "small args render as-is")
    (is (not (str/ends-with? out-mid "...")) "args within 120 chars render in full")
    (is (<= (count out-long) 120) "long args truncated to 120 chars")
    (is (str/ends-with? out-long "...") "truncation marker appended")))

(deftest format-iter-elapsed-bands
  (is (= "0ms" (#'s/format-iter-elapsed 0)))
  (is (= "850ms" (#'s/format-iter-elapsed 850)))
  (is (= "1.2s" (#'s/format-iter-elapsed 1234)))
  (is (= "59.5s" (#'s/format-iter-elapsed 59500)))
  (is (= "1m 30s" (#'s/format-iter-elapsed 90000)))
  (is (= "" (#'s/format-iter-elapsed nil))))

(deftest format-iter-result-chars-bands
  (is (= "" (#'s/format-iter-result-chars nil)))
  (is (= "0 chars" (#'s/format-iter-result-chars 0)))
  (is (= "999 chars" (#'s/format-iter-result-chars 999)))
  (is (= "1.0k chars" (#'s/format-iter-result-chars 1000)))
  (is (= "12.3k chars" (#'s/format-iter-result-chars 12345))))

;; ----------------------------------------------------------------------------
;; Renderer
;; ----------------------------------------------------------------------------

(deftest render-tools-section-with-mixed-statuses
  (let [state {:iteration 2
               :max-iterations 10
               :stage :tools
               :tool-batch [{:call-id 1 :name "search" :args {:q "x"}
                             :status :done :start-ms 0 :end-ms 1200
                             :result-chars 350}
                            {:call-id 2 :name "fetch" :args {:url "u"}
                             :status :error :start-ms 0 :end-ms 500
                             :error-msg "Connection refused"}
                            {:call-id 3 :name "slow" :args {} :status :called
                             :start-ms 0}]}
        raw  (#'s/render-iteration-block-lines state "⠋")
        ;; Tool name should retain its bold-cyan ANSI before stripping.
        raw-joined (str/join "\n" raw)
        text (joined raw)]
    (is (re-find #"\x1b\[\d+(?:;\d+)*m\s*search" raw-joined)
        "tool name 'search' is wrapped in an ANSI sequence (styled bold cyan)")
    (is (str/includes? text "[+] Iteration 2 / 10") "header has iteration label")
    (is (not (str/includes? text "[Tools]"))
        "no [Tools] header — tool lines appear directly")
    (is (str/includes? text "→ search({:q \"x\"}): called → done")
        "bullet prefix is '→' followed by tool name")
    (is (str/includes? text "1.2s"))
    (is (str/includes? text "350 chars"))
    (is (str/includes? text "→ fetch({:url \"u\"}): called → error"))
    (is (str/includes? text "Connection refused"))
    (is (str/includes? text "→ slow({}): called")
        "in-flight tool shows just 'called'")))

(deftest render-tools-section-boxes-code-like-args
  (testing "a multi-line script arg moves into a boxed Call section,
            the head line drops the inline args, and the script lines survive
            under a `command:` label"
    (let [state {:iteration 1 :max-iterations 5 :stage :tools
                 :tool-batch [{:call-id 9 :name "bash"
                               :args {:command "echo hi\nfor i in 1 2 3; do\n  echo $i\ndone"}
                               :status :done :start-ms 0 :end-ms 800 :result-chars 12}]}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      ;; Head line: name + status, no inline args spilled onto it.
      (is (str/includes? text "→ bash: called → done"))
      (is (not (str/includes? text "→ bash(")) "inline args are not on the head line")
      ;; Boxed Call section: arg shown as `name:` then the indented script.
      (is (str/includes? text "• Call:"))
      (is (str/includes? text "┌─"))
      (is (str/includes? text "command:") "arg name labels the value uniformly")
      (is (str/includes? text "echo hi"))
      (is (str/includes? text "for i in 1 2 3; do"))
      (is (str/includes? text "└─"))))
  (testing "a long single-line arg (>120 chars) also boxes rather than truncating"
    (let [long-q (apply str (repeat 130 "x"))
          state {:iteration 1 :stage :tools
                 :tool-batch [{:call-id 10 :name "search" :args {:query long-q}
                               :status :called :start-ms 0}]}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (str/includes? text "• Call:"))
      (is (str/includes? text "query:") "arg name labels the value")
      ;; The box word-wraps long lines, so the 130 x's may span rows — assert
      ;; all 130 survive (vs. the 120-char pr-str cap) and nothing is elided.
      (is (= 130 (count (filter #{\x} text))) "full query preserved across wrapped rows")
      (is (not (str/includes? text "...")) "no pr-str truncation ellipsis")))
  (testing "a multi-arg call boxes with each arg on its own `name: value` line"
    (let [state {:iteration 1 :stage :tools
                 :tool-batch [{:call-id 12 :name "write"
                               :args {:path "a.clj"
                                      :content (apply str (repeat 130 "y"))}
                               :status :called :start-ms 0}]}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (str/includes? text "• Call:"))
      (is (str/includes? text "path: a.clj") "scalar arg inline after colon")
      (is (str/includes? text "content:") "long arg labeled by name too")))
  (testing "short args still render as a compact inline one-liner (no box)"
    (let [state {:iteration 1 :stage :tools
                 :tool-batch [{:call-id 11 :name "read" :args {:path "a.clj"}
                               :status :done :start-ms 0 :end-ms 50}]}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (str/includes? text "→ read({:path \"a.clj\"}): called → done"))
      (is (not (str/includes? text "• Call:"))))))

(deftest render-tools-section-boxes-result
  (testing "an entry with :result-body renders a boxed Result section
            (code-eval Result box style) below the head line"
    (let [state {:iteration 1 :max-iterations 5 :stage :tools
                 :tool-batch [{:call-id 21 :name "search" :args {:q "x"}
                               :status :done :start-ms 0 :end-ms 900
                               :result-chars 34
                               :result-body "hit-1: foo.clj:12\nhit-2: bar.clj:88"}]}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (str/includes? text "→ search({:q \"x\"}): called → done"))
      (is (str/includes? text "• Result:") "boxed Result section header present")
      (is (str/includes? text "┌─"))
      (is (str/includes? text "hit-1: foo.clj:12") "result body content shown")
      (is (str/includes? text "hit-2: bar.clj:88"))
      (is (str/includes? text "└─"))))
  (testing "a tool-reported error shows in the same Result box (no Error box) —
            the result map carries the error description"
    (let [state {:iteration 1 :stage :tools
                 :tool-batch [{:call-id 22 :name "read-file" :args {:path "x"}
                               :status :done :start-ms 0 :end-ms 8
                               :result-chars 40
                               :result-body "{:error \"File not found: /x\"}"}]}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (str/includes? text "• Result:") "one Result box surfaces everything")
      (is (not (str/includes? text "• Error:")) "no separate Error box")
      (is (str/includes? text "File not found: /x")
          "the error description survives in the Result box")))
  (testing "an entry with no :result-body renders no Result box"
    (let [state {:iteration 1 :stage :tools
                 :tool-batch [{:call-id 23 :name "noop" :args {} :status :done
                               :start-ms 0 :end-ms 10 :result-chars 3}]}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (str/includes? text "→ noop({}): called → done"))
      (is (not (str/includes? text "• Result:")) "no empty Result box"))))

(deftest render-splices-eval-section-lines
  ;; The renderer no longer formats Code / Result / Error sections
  ;; inline from `:code` and `:code-output`. Instead, the code-eval
  ;; hook handlers pre-render those sections via `format-eval-sections`
  ;; and stash them under `:eval-section-lines`; the renderer just
  ;; splices that vector after the header / Think / tool lines. This
  ;; test feeds hand-crafted `:eval-section-lines` so it isolates the
  ;; renderer's splicing behaviour from `format-eval-sections`.
  (testing "code + result sections appear in output"
    (let [state {:iteration 1 :max-iterations 5 :stage :code
                 :eval-section-lines ["  • Code:"
                                      "    ┌─"
                                      "    │ (+ 1 2)"
                                      "    └─"
                                      "  • Result:"
                                      "    ┌─"
                                      "    │ 12"
                                      "    └─"]}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (str/includes? text "[+] Iteration 1 / 5") "header still present")
      (is (str/includes? text "• Code:"))
      (is (str/includes? text "(+ 1 2)"))
      (is (str/includes? text "• Result:"))
      (is (str/includes? text "12"))))

  (testing "error section appears in output"
    (let [state {:iteration 1 :max-iterations 5 :stage :code
                 :eval-section-lines ["  • Error:"
                                      "    ┌─"
                                      "    │ kaboom"
                                      "    └─"]}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (str/includes? text "• Error:"))
      (is (str/includes? text "kaboom"))))

  (testing "no eval-section-lines → no eval body in output (only header)"
    (let [state {:iteration 1 :max-iterations 5 :stage :pre}
          text  (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (str/includes? text "[+] Iteration 1 / 5"))
      (is (not (str/includes? text "• Code:")))
      (is (not (str/includes? text "• Result:")))
      (is (not (str/includes? text "• Error:"))))))

(deftest render-done-uses-checkmark
  (let [state {:iteration 1 :max-iterations 5 :stage :done :result :success}
        text  (joined (#'s/render-iteration-block-lines state "⠋"))]
    (is (str/includes? text "✓") "success → check")
    (is (str/includes? text "[✓] Iteration 1 / 5"))))

(deftest think-section-wraps-multi-line
  (testing "long Think text wraps onto multiple lines (cap = think-max-lines)"
    (let [;; ~30 short tokens — guaranteed to exceed one terminal-width line
          long-text (clojure.string/join " "
                                         (repeat 80 "thinking-word"))
          state {:iteration 1 :max-iterations 5 :stage :reasoning
                 :streaming long-text}
          lines (#'s/render-iteration-block-lines state "⠋")
          ;; Header line + Think header line + extra wrapped lines
          ;; (no tool/code/result here)
          think-lines (filter #(re-find #"Think:|^\s{2,}" (joined [%])) lines)]
      (is (>= (count think-lines) 2)
          "long text produces a multi-line Think block")
      (is (<= (count think-lines) 11)
          "Think section is capped at think-max-lines (10)")
      (is (re-find #"Think: " (joined lines))
          "first line is the Think: header")))

  (testing "very long text shows the [-N lines] elision indicator"
    (let [;; Build text long enough to force overflow past 10 lines.
          huge (clojure.string/join " " (repeat 800 "thinking-word"))
          state {:iteration 1 :max-iterations 5 :stage :reasoning
                 :streaming huge}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (re-find #"\[-\d+ lines\]" text)
          "elision marker appears when overflow occurs"))))

(deftest think-section-skipped-when-blank
  (testing "no Think text → no Think section in iteration block"
    (let [state {:iteration 1 :max-iterations 5 :stage :reasoning
                 :streaming ""
                 :reasoning ""}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (not (str/includes? text "Think:"))))))

(deftest notice-section-renders
  (testing "a short notice (self-improvement nudge) appears in full"
    (let [state {:iteration 1 :max-iterations 5 :stage :done :result :success
                 :notices (str "💡 Self-improvement: 1 skill proposal awaiting review — "
                               "`deploy-flow`. Review with skill-proposal$accept.")}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (str/includes? text "Self-improvement"))
      (is (str/includes? text "deploy-flow"))
      (is (str/includes? text "skill-proposal$accept"))))

  (testing "no notice → no notice section"
    (let [state {:iteration 1 :max-iterations 5 :stage :done :result :success}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (not (str/includes? text "Self-improvement")))))

  (testing "blank notice is skipped"
    (let [state {:iteration 1 :max-iterations 5 :stage :done :result :success
                 :notices "   "}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (= 1 (count (#'s/render-iteration-block-lines state "⠋"))))
      (is (str/includes? text "Iteration 1"))))

  (testing "a very long notice (e.g. a full usage guide) truncates with [-N lines]"
    (let [huge (clojure.string/join " " (repeat 400 "guide-word"))
          state {:iteration 1 :max-iterations 5 :stage :done :result :success
                 :notices huge}
          text (joined (#'s/render-iteration-block-lines state "⠋"))]
      (is (re-find #"\[-\d+ lines\]" text)))))

(deftest think-live-block-is-one-line-braille
  (testing "render-think-block-lines returns a single line: [<braille>] <body>"
    (let [lines (#'s/render-think-block-lines ["Pondering"] 0 "⠋" (System/currentTimeMillis) nil nil)
          text  (joined lines)]
      (is (= 1 (count lines)) "single line — no leading/trailing blank padding")
      (is (str/starts-with? text "[⠋]") "starts with the bracketed braille frame")
      (is (str/includes? text "Pondering...") "rotating word + ellipsis is present")))

  (testing "frame argument is rendered as-is (any of the braille frames)"
    (let [lines (#'s/render-think-block-lines ["Cogitating"] 0 "⠙" (System/currentTimeMillis) nil nil)
          text  (joined lines)]
      (is (str/includes? text "[⠙]") "the requested braille frame is shown")
      (is (str/includes? text "Cogitating...") "word is rendered with ellipsis")))

  (testing "word rotates as spinner-idx advances (one word per 10 ticks)"
    (let [words ["alpha" "beta" "gamma"]
          now   (System/currentTimeMillis)
          at-0  (joined (#'s/render-think-block-lines words 0 "⠋" now nil nil))
          at-10 (joined (#'s/render-think-block-lines words 10 "⠋" now nil nil))
          at-30 (joined (#'s/render-think-block-lines words 30 "⠋" now nil nil))]
      (is (str/includes? at-0 "alpha..."))
      (is (str/includes? at-10 "beta..."))
      ;; 30 / 10 = 3 → wraps back to index 0 (alpha)
      (is (str/includes? at-30 "alpha..."))))

  (testing "activity text (passed in) is appended in the suffix alongside the word"
    (let [now   (System/currentTimeMillis)
          lines (#'s/render-think-block-lines ["Pondering"] 0 "⠋" now "→ bash" nil)
          text  (joined lines)]
      (is (str/includes? text "→ bash") "activity text is shown in the suffix")
      (is (str/includes? text "Pondering...")
          "rotating word remains visible while activity is shown")))

  (testing "nil activity → just the rotating word, no activity suffix"
    (let [now   (System/currentTimeMillis)
          lines (#'s/render-think-block-lines ["Pondering"] 0 "⠋" now nil nil)
          text  (joined lines)]
      (is (str/includes? text "Pondering...") "word is shown")
      (is (not (str/includes? text "→")) "no activity arrow when activity is nil")))

  (testing "paused-at → static ⏸ frame, frozen elapsed, no live activity"
    (let [start (System/currentTimeMillis)
          ;; paused 5s after the block started
          lines (#'s/render-think-block-lines ["Pondering"] 0 "⠋" start "→ bash" (+ start 5000))
          text  (joined lines)]
      (is (str/includes? text "⏸") "paused glyph replaces the braille frame")
      (is (not (str/includes? text "⠋")) "the braille frame is not shown while paused")
      (is (str/includes? text "paused") "the suffix is marked paused")
      (is (not (str/includes? text "→ bash")) "live activity is dropped while paused")
      (is (str/includes? text "5.0s") "elapsed is frozen at the paused-at instant"))))

(deftest fresh-activity-text-ttl
  (testing "fresh-activity-text returns the text within TTL, nil once stale / absent"
    (let [now (System/currentTimeMillis)]
      (is (= "→ new" (#'s/fresh-activity-text {:text "→ new" :ts now}))
          "fresh activity (just stamped) is returned")
      (is (nil? (#'s/fresh-activity-text {:text "→ old" :ts (- now (long 10000))}))
          "activity older than the TTL is dropped")
      (is (nil? (#'s/fresh-activity-text nil)) "nil activity → nil"))))

;; ----------------------------------------------------------------------------
;; Handlers — full lifecycle
;; ----------------------------------------------------------------------------

(defn- stub-agent
  "Minimal 'agent' for handler tests. Carries an :agent-id, a stub
   st-memory atom (returned by `agent/get-bt-st-memory` via with-redefs),
   and an `:!state` atom holding `{:runtime {…}}` so handlers that walk
   the `[:runtime :parent-agent]` chain (e.g. `root-of-agent`) work.
   Pass `:parent` to mark this agent as a sub-agent of another."
  ([] (stub-agent {}))
  ([{:keys [agent-id parent]}]
   (let [st (atom {})]
     {:agent-id (or agent-id :test-agent)
      :stub-st  st
      :!state   (atom {:runtime (cond-> {} parent (assoc :parent-agent parent))})})))

(defn- with-stub-agent
  "Run thunk with `agent/get-bt-st-memory` and `find-session-for-agent`
   stubbed, plus `update-iteration-block!` no-op'd (we don't touch the
   layout/scrollback in these tests — we assert on !iteration-blocks)."
  [a thunk]
  (with-redefs [agent/get-bt-st-memory (fn [x]
                                         (or (:stub-st x) (:stub-st a)))
                s/find-session-for-agent (constantly {:id 0})
                ;; Skip live-block render — the layout machinery isn't
                ;; initialized in this test environment.
                layout/update-live-block! (fn [_ _] nil)
                layout/freeze-live-block! (fn [_] nil)]
    (thunk)))

(deftest iteration-pre-then-post-creates-and-clears
  (let [a (stub-agent)]
    (with-stub-agent a
      (fn []
        (s/iteration-pre-handler {:agent a :iteration 1 :max-iterations 5
                                  :repeat-id "main"})
        (let [snap @@#'s/!iteration-blocks]
          (is (= 1 (count snap)))
          (let [[_ blk] (first snap)]
            (is (= 1 (:iteration blk)))
            (is (= 5 (:max-iterations blk)))
            (is (= :pre (:stage blk)))))
        ;; After /post, the entry is removed (frozen + dissoc'd).
        (s/iteration-post-handler {:agent a :iteration 1 :repeat-id "main"
                                   :result :success})
        (is (zero? (count @@#'s/!iteration-blocks)))))))

(deftest dspy-chunk-accumulates-streaming
  (let [a (stub-agent)]
    (with-stub-agent a
      (fn []
        (s/iteration-pre-handler {:agent a :iteration 1 :max-iterations 5
                                  :repeat-id "main"})
        (s/dspy-pre-handler {:agent a})
        (s/dspy-chunk-handler {:agent a :accumulated "Hello"})
        (s/dspy-chunk-handler {:agent a :accumulated "Hello, world"})
        (let [blk (-> @@#'s/!iteration-blocks vals first)]
          (is (= :think (:stage blk)))
          (is (= "Hello, world" (:streaming blk))))
        (s/dspy-post-handler {:agent a
                              :reasoning "I will think hard."
                              :usage {:input-tokens 100 :output-tokens 50}})
        (let [blk (-> @@#'s/!iteration-blocks vals first)]
          (is (nil? (:streaming blk)) "/post clears streaming")
          (is (= "I will think hard." (:reasoning blk)))
          (is (= 150 (-> blk :usage :total))))))))

(deftest tool-use-pairs-pre-post-by-call-id
  (let [a (stub-agent)
        cid1 (random-uuid)
        cid2 (random-uuid)]
    (with-stub-agent a
      (fn []
        (s/iteration-pre-handler {:agent a :iteration 1 :max-iterations 5
                                  :repeat-id "main"})
        (s/tool-calls-pre-handler {:agent a})
        (s/tool-use-pre-handler {:agent a :tool-name "search"
                                 :args {:q "a"} :call-id cid1})
        (s/tool-use-pre-handler {:agent a :tool-name "fetch"
                                 :args {:url "u"} :call-id cid2})
        (let [tb (-> @@#'s/!iteration-blocks vals first :tool-batch)]
          (is (= 2 (count tb)))
          (is (every? #(= :called (:status %)) tb)))
        ;; Post for cid2 first (out-of-order is fine — paired by call-id)
        (s/tool-use-post-handler {:agent a :tool-name "fetch"
                                  :call-id cid2 :result {:status 200}})
        (s/tool-use-post-handler {:agent a :tool-name "search"
                                  :call-id cid1
                                  :result {:error-message "boom"}})
        (let [tb (-> @@#'s/!iteration-blocks vals first :tool-batch)
              by-name (into {} (map (juxt :name identity) tb))]
          (is (= :error (-> by-name (get "search") :status))
              "search post resolved as error via :error-message")
          (is (= "boom" (-> by-name (get "search") :error-msg)))
          (is (= :done (-> by-name (get "fetch") :status))
              "fetch post resolved as done")
          ;; The full result (normal output AND any error description) is
          ;; stringified into :result-body for a single Result box.
          (is (= "{:status 200}" (-> by-name (get "fetch") :result-body))
              "success result stringified into :result-body for the Result box")
          (is (= "{:error-message \"boom\"}" (-> by-name (get "search") :result-body))
              "error result also stringified into :result-body (no separate Error box)")
          (is (every? :end-ms tb)))))))

(deftest tool-use-post-result-body-stringification
  (testing "string / :answer-map / error-map / nil / empty-map results reduce
            to the right :result-body (nil ⇒ no Result box)"
    (is (= "hello world" (#'s/tool-result->body "hello world")))
    (is (= "the answer"  (#'s/tool-result->body {:answer "the answer"})))
    (is (= "{:error \"nope\"}" (#'s/tool-result->body {:error "nope"}))
        "an error map is boxed as-is — the Result box shows the error description")
    (is (nil? (#'s/tool-result->body nil)))
    (is (nil? (#'s/tool-result->body true)))
    (is (nil? (#'s/tool-result->body {})))
    (is (nil? (#'s/tool-result->body "   ")) "blank string ⇒ no box"))
  (testing "an oversized body is capped so a multi-MB blob can't blow up state"
    (let [big (apply str (repeat 50000 "x"))
          out (#'s/tool-result->body big)]
      (is (< (count out) 50000))
      (is (str/includes? out "[truncated]")))))

(deftest code-eval-pre-then-post-records-output
  (let [a (stub-agent)]
    (with-stub-agent a
      (fn []
        (s/iteration-pre-handler {:agent a :iteration 1 :max-iterations 5
                                  :repeat-id "main"})
        (s/code-eval-pre-handler {:agent a :code "(+ 1 2)"})
        (let [blk (-> @@#'s/!iteration-blocks vals first)]
          (is (= :code (:stage blk)))
          (is (= "(+ 1 2)" (:code blk)))
          (is (seq (:eval-section-lines blk))
              "code-eval/pre pre-renders the Code section into :eval-section-lines")
          (let [text (str/join "\n" (map strip-ansi (:eval-section-lines blk)))]
            (is (str/includes? text "Code")
                "rendered eval-section-lines contain a Code section header")
            (is (str/includes? text "(+ 1 2)")
                "rendered eval-section-lines contain the code body")))
        (s/code-eval-post-handler {:agent a :code "(+ 1 2)"
                                   :result "3" :output "" :error ""
                                   :duration-ms 42})
        (let [blk (-> @@#'s/!iteration-blocks vals first)
              co  (:code-output blk)]
          (is (= "3" (:result co)))
          (is (= 42 (:duration-ms co)))
          (let [text (str/join "\n" (map strip-ansi (:eval-section-lines blk)))]
            (is (str/includes? text "Code")
                "post-render keeps the Code section")
            (is (str/includes? text "Result")
                "post-render adds the Result section")
            (is (str/includes? text "3")
                "post-render shows the result body")))))))

(deftest sub-agent-rollup-accumulates-across-iterations
  ;; Sub-agent (parent set in :!state) fires iteration-post; the handler
  ;; resolves the root via `root-of-agent` and bumps the sub-agent's
  ;; entry's :iter-rollup under the root's consolidated subagents block.
  (let [parent (stub-agent {:agent-id :test-parent})
        sub    (stub-agent {:agent-id :test-sub :parent parent})
        sub-aid    (:agent-id sub)
        root-aid   (:agent-id parent)]
    (try
      (with-stub-agent sub
        (fn []
          ;; Pre-populate !subagents-blocks as if agent-created had run.
          (swap! @#'s/!subagents-blocks assoc root-aid
                 {:block-id    :subagents/test
                  :session-idx 0
                  :sub-agents  (sorted-map
                                sub-aid
                                {:agent-id        sub-aid
                                 :defagent-id     :test
                                 :status          :running
                                 :start-time      (System/currentTimeMillis)
                                 :end-time        nil
                                 :st-mem-atom     (:stub-st sub)
                                 :iter-rollup     nil
                                 :parent-agent-id (:agent-id parent)
                                 :depth           0})})
          ;; Iter 1: success, 100 tokens.
          (s/iteration-pre-handler {:agent sub :iteration 1 :max-iterations 5
                                    :repeat-id "main"})
          (s/dspy-post-handler {:agent sub :reasoning "r1"
                                :usage {:input-tokens 60 :output-tokens 40}})
          (s/iteration-post-handler {:agent sub :iteration 1 :repeat-id "main"
                                     :result :success})
          ;; Iter 2: failure, 50 tokens.
          (s/iteration-pre-handler {:agent sub :iteration 2 :max-iterations 5
                                    :repeat-id "main"})
          (s/dspy-post-handler {:agent sub :reasoning "r2"
                                :usage {:input-tokens 30 :output-tokens 20}})
          (s/iteration-post-handler {:agent sub :iteration 2 :repeat-id "main"
                                     :result :failure})
          (let [rollup (-> @@#'s/!subagents-blocks
                           (get-in [root-aid :sub-agents sub-aid :iter-rollup]))]
            (is (= 2 (:total-iterations rollup)))
            (is (= 150 (:total-tokens rollup)))
            (is (= 2 (:last-iteration rollup)))
            (is (= :failure (:last-result rollup))))))
      (finally (swap! @#'s/!subagents-blocks dissoc root-aid)))))

(deftest subagents-block-renders-one-line-per-sub-agent
  ;; The consolidated renderer produces one summary line per sub-agent
  ;; (depth-indented under each parent), with NO `── subagents … ──`
  ;; separator header. No Thinking snippet — intentionally dropped.
  (let [now (System/currentTimeMillis)
        block-state
        {:block-id    :subagents/test
         :session-idx 0
         :sub-agents
         (sorted-map
          :research-agent/abc
          {:agent-id        :research-agent/abc
           :defagent-id     :research-agent
           :status          :running
           :start-time      (- now 2000)
           :end-time        nil
           :st-mem-atom     (atom {:iteration-count 2
                                   :config {:max-iterations 100}})
           :iter-rollup     {:total-iterations 1 :total-tokens 1234
                             :last-iteration 1 :last-result :success}
           :parent-agent-id :coact/root
           :depth           0}
          :search/def
          {:agent-id        :search/def
           :defagent-id     :search-agent
           :status          :done
           :start-time      (- now 1500)
           :end-time        (- now 200)
           :st-mem-atom     (atom {:iteration-count 3
                                   :config {:max-iterations 3}})
           :iter-rollup     {:total-iterations 3 :total-tokens 567
                             :last-iteration 3 :last-result :success}
           :parent-agent-id :research-agent/abc
           :depth           1})}
        lines (#'s/render-subagents-block-lines block-state "●")
        text  (joined lines)]
    (is (not (str/includes? text "subagents ("))
        "no `── subagents (N running, M done) ──` separator header")
    (is (str/includes? text "research-agent/abc")
        "first sub-agent (depth 0) appears")
    (is (str/includes? text "[research-agent]")
        "type tag uses defagent-id namespace")
    (is (str/includes? text "Iter 2/100")
        "live iteration count from st-memory")
    (is (str/includes? text "(1234 tok)")
        "total-tokens from rollup")
    (is (str/includes? text "search/def")
        "child sub-agent (depth 1) appears")
    (is (str/includes? text "↳")
        "child is rendered with the ↳ depth marker")
    (is (not (str/includes? text "Thinking"))
        "Thinking snippet is intentionally dropped from this block")))

(deftest subagents-block-line-marker-changes-with-status
  (let [base {:agent-id    :sub/test-id
              :defagent-id :coact-agent
              :start-time  (System/currentTimeMillis)
              :st-mem-atom (atom {:iteration-count 3
                                  :config {:max-iterations 5}})
              :iter-rollup {:total-iterations 3 :total-tokens 99
                            :last-iteration 3 :last-result :success}
              :parent-agent-id :root
              :depth       0}
        running-text (joined (#'s/render-sub-agent-lines
                              (assoc base :status :running) "●"))
        done-text    (joined (#'s/render-sub-agent-lines
                              (assoc base :status :done :end-time
                                     (+ (:start-time base) 5000)) "●"))]
    (is (str/includes? running-text "running"))
    (is (str/includes? done-text "done"))
    (is (str/includes? done-text "✓") "done status uses ✓ marker")))

(deftest subagents-block-shows-activity-for-running-only
  (let [base {:agent-id    :sub/act
              :defagent-id :coact-agent
              :start-time  (System/currentTimeMillis)
              :st-mem-atom (atom {:iteration-count 2 :config {:max-iterations 5}})
              :iter-rollup {:total-iterations 2 :total-tokens 600}
              :parent-agent-id :root
              :depth       0
              :tools-used       5
              :recent-tools     ["plan$read" "grep"]
              :code-blocks-used 2
              :recent-code      [{:lang "clj" :lines 12} {:lang "bash" :lines 3}]}
        running (#'s/render-sub-agent-lines (assoc base :status :running) "●")
        done    (#'s/render-sub-agent-lines (assoc base :status :done) "●")
        rtext   (joined running)]
    (testing "running sub-agent shows tools, code-blocks, and a totals footer"
      (is (str/includes? rtext "tools: plan$read · grep"))
      (is (str/includes? rtext "code-blocks: clj 12 lines · bash 3 lines"))
      (is (str/includes? rtext "(+5 tools used, +2 code-blocks used)"))
      (is (>= (count running) 4) "summary + 2 activity lines + footer"))
    (testing "finished sub-agent collapses to a single summary line"
      (is (= 1 (count done)))
      (is (not (str/includes? (joined done) "tools:"))))))

(deftest subagent-hooks-accumulate-tools-and-code-blocks
  ;; Drive the REAL tool-calls/post + code-eval/post handlers for a sub-agent
  ;; and assert they accumulate into !subagents-blocks — the hook wiring that
  ;; the render-only test above does not exercise.
  (let [root (stub-agent {:agent-id :root/r})
        sub  (stub-agent {:agent-id :explore-agent/s :parent root})
        seed {:agent-id :explore-agent/s :defagent-id :explore-agent
              :status :running :start-time (System/currentTimeMillis)
              :tools-used 0 :recent-tools [] :code-blocks-used 0 :recent-code []
              :parent-agent-id :root/r :depth 0}]
    (swap! @#'s/!subagents-blocks assoc :root/r
           {:block-id :subagents/t :session-idx 0
            :sub-agents (sorted-map :explore-agent/s seed)})
    (with-stub-agent sub
      (fn []
        ;; two tool-channel calls
        (#'s/tool-calls-post-handler
         {:agent sub :calls [{:tool-name "grep"} {:tool-name "read-file"}]})
        ;; a clojure block (2 lines) then a bash block (1 line)
        (#'s/code-eval-post-handler
         {:agent sub :lang "clojure" :code "(+ 1 2)\n(println :x)"
          :result "3" :output "" :error "" :duration-ms 5})
        (#'s/code-eval-post-handler
         {:agent sub :lang "bash" :code "echo hi"
          :result "0" :output "hi" :error "" :duration-ms 9})))
    (let [final (get-in @@#'s/!subagents-blocks
                        [:root/r :sub-agents :explore-agent/s])]
      (is (= 2 (:tools-used final)) "tool count accrues from the batch")
      (is (= ["grep" "read-file"] (:recent-tools final)))
      (is (= 2 (:code-blocks-used final)) "one per code-eval/post block")
      (is (= [{:lang "clojure" :lines 2} {:lang "bash" :lines 1}]
             (:recent-code final))
          "lang from the event, line count from the code")
      (let [text (joined (#'s/render-sub-agent-lines final "●"))]
        (is (str/includes? text "tools: grep · read-file"))
        (is (str/includes? text "code-blocks: clojure 2 lines · bash 1 lines"))
        (is (str/includes? text "(+2 tools used, +2 code-blocks used)"))))))

;; ----------------------------------------------------------------------------
;; Iteration-sink protocol — recording sink replays handler call shape
;; ----------------------------------------------------------------------------

(defn- recording-sink
  "Build an IterationSink that appends `[op id & rest]` tuples to `!ops`.
   Lets a test assert the SHAPE of the sink contract emitted by the
   handlers without depending on any specific surface (layout, tmux,
   etc.)."
  [!ops]
  (reify iter-sink/IterationSink
    (-write-widget!  [_ id lines] (swap! !ops conj [:write id (count lines)]))
    (-freeze-widget! [_ id]       (swap! !ops conj [:freeze id]))
    (-clear-widget!  [_ id]       (swap! !ops conj [:clear id]))))

(deftest sink-receives-write-and-freeze-on-iter-lifecycle
  (let [a    (stub-agent)
        !ops (atom [])
        sink (recording-sink !ops)
        prev (iter-sink/current-sink)]
    (try
      (iter-sink/set-iteration-sink! sink)
      (with-redefs [agent/get-bt-st-memory (fn [x]
                                             (or (:stub-st x) (:stub-st a)))
                    s/find-session-for-agent (constantly {:id 0})]
        (s/iteration-pre-handler {:agent a :iteration 1 :max-iterations 5
                                  :repeat-id "main"})
        (s/dspy-post-handler {:agent a :reasoning "r"
                              :usage {:input-tokens 10 :output-tokens 5}})
        (s/iteration-post-handler {:agent a :iteration 1 :repeat-id "main"
                                   :result :success}))
      (let [ops @!ops
            kinds (map first ops)
            ids   (set (map second ops))]
        (is (some #{:write} kinds) ":write fired during the iteration")
        (is (some #{:freeze} kinds) ":freeze fired on iter/post")
        (is (= 1 (count ids))
            (str "all sink ops target one widget id; got: " (pr-str ops)))
        ;; The freeze op must follow the last write — verifies the
        ;; iteration-post-handler renders final state BEFORE freezing.
        (let [last-write-idx (->> (map-indexed vector ops)
                                  (filter #(= :write (first (second %))))
                                  last
                                  first)
              freeze-idx     (->> (map-indexed vector ops)
                                  (filter #(= :freeze (first (second %))))
                                  first
                                  first)]
          (is (and (some? last-write-idx) (some? freeze-idx)))
          (is (< last-write-idx freeze-idx)
              "final write happens before the freeze")))
      (finally (iter-sink/set-iteration-sink! prev)))))

(deftest tool-use-suppressed-when-stage-is-code
  (let [a (stub-agent)]
    (with-stub-agent a
      (fn []
        (s/iteration-pre-handler {:agent a :iteration 1 :max-iterations 5
                                  :repeat-id "main"})
        (s/code-eval-pre-handler {:agent a :code "(call-tool ...)"})
        (s/tool-use-pre-handler {:agent a :tool-name "search"
                                 :args {} :call-id (random-uuid)})
        (let [blk (-> @@#'s/!iteration-blocks vals first)]
          (is (empty? (:tool-batch blk))
              "tool-use during :code stage must NOT populate [Tools]"))))))

;; ----------------------------------------------------------------------------
;; Abnormal turn end — leftover [+] blocks must freeze on ask/post
;; ----------------------------------------------------------------------------

(deftest freeze-pending-finalizes-stuck-block
  (testing "freeze-pending-iterations! finalizes any !iteration-blocks entry
            whose matching iteration/post never fired (cancel, loop-guard,
            exception, max-iter exhaust) — entry is removed from the atom"
    (let [a (stub-agent)]
      (with-stub-agent a
        (fn []
          (s/iteration-pre-handler {:agent a :iteration 7 :max-iterations 100
                                    :repeat-id "main"})
          (is (= 1 (count @@#'s/!iteration-blocks))
              "iteration-pre creates the in-progress block")
          (let [[_ blk] (first @@#'s/!iteration-blocks)]
            (is (= :pre (:stage blk)) "stage is still :pre — no /post yet"))
          ;; Simulate the abnormal turn end: ask/post fires without an
          ;; intervening iteration/post for iter 7.
          (#'s/freeze-pending-iterations! a)
          (is (zero? (count @@#'s/!iteration-blocks))
              "the stuck entry must be removed so the next turn starts clean"))))))

(deftest freeze-pending-emits-freeze-via-sink
  (testing "freeze-pending-iterations! routes through iter-sink/freeze-widget!
            so the live region commits to scrollback (mirrors the normal
            iteration-post-handler path)"
    (let [a    (stub-agent)
          !ops (atom [])
          sink (recording-sink !ops)
          prev (iter-sink/current-sink)]
      (try
        (iter-sink/set-iteration-sink! sink)
        (with-redefs [agent/get-bt-st-memory (fn [x]
                                               (or (:stub-st x) (:stub-st a)))
                      s/find-session-for-agent (constantly {:id 0})]
          (s/iteration-pre-handler {:agent a :iteration 3 :max-iterations 100
                                    :repeat-id "main"})
          (#'s/freeze-pending-iterations! a))
        (let [ops   @!ops
              kinds (map first ops)]
          (is (some #{:freeze} kinds)
              ":freeze op must fire on abnormal-end finalization")
          (is (some #{:write} kinds)
              "final :write must render the [●] state before freezing"))
        (finally (iter-sink/set-iteration-sink! prev))))))

(deftest freeze-pending-scoped-to-agent
  (testing "freeze-pending-iterations! only freezes entries for the named
            agent — sibling agents' pending blocks must survive (matters
            when a sub-agent ask/post fires while the root is still mid-turn)"
    (let [a1 (stub-agent {:agent-id :agent-1})
          a2 (stub-agent {:agent-id :agent-2})]
      (with-redefs [agent/get-bt-st-memory (fn [x]
                                             (or (:stub-st x) (:stub-st a1)))
                    s/find-session-for-agent (constantly {:id 0})
                    layout/update-live-block! (fn [_ _] nil)
                    layout/freeze-live-block! (fn [_] nil)]
        (s/iteration-pre-handler {:agent a1 :iteration 1 :max-iterations 100
                                  :repeat-id "main"})
        (s/iteration-pre-handler {:agent a2 :iteration 1 :max-iterations 100
                                  :repeat-id "main"})
        (is (= 2 (count @@#'s/!iteration-blocks))
            "both agents' pending iterations are recorded")
        (#'s/freeze-pending-iterations! a1)
        (let [keys-remaining (set (map first (keys @@#'s/!iteration-blocks)))]
          (is (= #{:agent-2} keys-remaining)
              "only the other agent's pending block survives"))))))

(deftest freeze-pending-stamps-done-stage-not-success
  (testing "frozen-on-abnormal-end blocks render the neutral [●] marker
            (stage :done, result nil) — neither [+] (still running) nor [✓]
            (succeeded). Mirrors what the user sees when a turn is cancelled
            mid-iteration."
    (let [a (stub-agent)]
      (with-stub-agent a
        (fn []
          (s/iteration-pre-handler {:agent a :iteration 1 :max-iterations 5
                                    :repeat-id "main"})
          ;; Capture the entry as it would be re-rendered, before dissoc.
          (let [seen (atom nil)]
            (with-redefs [s/update-iteration-block!
                          (fn [aid rid it]
                            (reset! seen (get @@#'s/!iteration-blocks [aid rid it])))]
              (#'s/freeze-pending-iterations! a))
            (is (some? @seen) "the re-render must observe the final state")
            (is (= :done (:stage @seen))
                "stage flipped to :done so the marker resolves to [●]")
            (is (nil? (:result @seen))
                "result stays nil — we don't claim success or failure")))))))
