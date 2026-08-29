;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp-client.events-translation-test
  "Pure-data tests for the ACP session/update → hook event bridge.
   No I/O, no subprocess. Verifies the translation table from §4.2.1."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.acp-client.core.events :as events]
            [ai.brainyard.acp-client.core.callbacks :as callbacks]))

(deftest agent-message-chunk-test
  (testing "agent_message_chunk → :agent.dspy-action/chunk with the text"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :sessionUpdate "agent_message_chunk"
            :content {:type "text" :text "hello"}})]
      (is (= :agent.dspy-action/chunk event))
      (is (= "hello" (:chunk data)))
      (is (= "s1" (:session-id data))))))

(deftest nested-update-shape-test
  (testing "spec-compliant nested `:update` shape (real ACP agents) is translated too"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :update {:sessionUpdate "agent_message_chunk"
                     :content {:type "text" :text "hello nested"}}})]
      (is (= :agent.dspy-action/chunk event))
      (is (= "hello nested" (:chunk data)))
      (is (= "s1" (:session-id data))
          "sessionId sibling of :update is preserved through normalization")))
  (testing "nested tool_call with inline fields under :update"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :update {:sessionUpdate "tool_call"
                     :toolCallId "tc1" :title "Read x" :kind "read" :status "in_progress"}})]
      (is (= :agent.tool-use/pre event))
      (is (= "tc1" (:call-id data)))
      (is (= "Read x" (:tool-name data))))))

(deftest tool-name-prefers-meta-over-title-test
  (testing "real claude-code shape: :tool-name is _meta.claudeCode.toolName, not the :title description"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :update {:_meta {:claudeCode {:toolName "Bash"}}
                     :sessionUpdate "tool_call"
                     :toolCallId "tc-9"
                     :title "`echo hi`"     ;; title is a description, not the tool name
                     :kind "execute"
                     :rawInput {:command "echo hi" :description "Echo hi"}}})]
      (is (= :agent.tool-use/pre event))
      (is (= "Bash" (:tool-name data)) "tool-name comes from _meta.claudeCode.toolName")
      (is (= {:command "echo hi" :description "Echo hi"} (:args data)))
      (is (= "tc-9" (:call-id data)))))
  (testing "no _meta → falls back to :title"
    (let [{:keys [data]}
          (events/translate-update
           {:update {:sessionUpdate "tool_call" :toolCallId "t" :title "Read x" :kind "read"}})]
      (is (= "Read x" (:tool-name data))))))

(deftest agent-thought-chunk-test
  (testing "agent_thought_chunk marks meta with :kind :thought"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :sessionUpdate "agent_thought_chunk"
            :content {:type "text" :text "thinking..."}})]
      (is (= :agent.dspy-action/chunk event))
      (is (= "thinking..." (:chunk data)))
      (is (= :thought (-> data :meta :kind))))))

(deftest plan-test
  (testing "plan → :todo/updated with entries normalized to the native todo shape"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :sessionUpdate "plan"
            :entries [{:content "step 1" :status "in_progress"}
                      {:content "step 2"}
                      {:content "step 3" :status "completed"}]})]
      (is (= :todo/updated event))
      (is (= 3 (count (:todo-list data))))
      ;; ACP `content` is mapped to `:description` so render/todo-block and
      ;; tui.format/format-todo-list (which read `:description`/`:done`) show text.
      (is (= "step 1" (-> data :todo-list first :description)))
      (is (= "in_progress" (-> data :todo-list first :status)))
      (is (false? (-> data :todo-list first :done)))
      (is (= "pending" (-> data :todo-list second :status))
          "default status is pending")
      (is (false? (-> data :todo-list second :done)))
      ;; `completed` status drives the `:done` flag the renderer ticks on.
      (is (true? (-> data :todo-list last :done)))
      (is (= "completed" (-> data :todo-list last :status))))))

(deftest tool-call-test
  (testing "tool_call → :agent.tool-use/pre (observer)"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :sessionUpdate "tool_call"
            :toolCall {:toolCallId "tc-1"
                       :title "Read file"
                       :kind "read"
                       :status "in_progress"
                       :rawInput {:path "/tmp/foo"}}})]
      (is (= :agent.tool-use/pre event))
      (is (= "tc-1" (:call-id data)))
      (is (= "Read file" (:tool-name data)))
      (is (= {:path "/tmp/foo"} (:args data)))
      (is (true? (:observer? data))))))

(deftest tool-call-update-completed-test
  (testing "tool_call_update with completed → :agent.tool-use/post"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :sessionUpdate "tool_call_update"
            :toolCall {:toolCallId "tc-1"
                       :status "completed"
                       :content [{:type "text" :text "ok"}]}})]
      (is (= :agent.tool-use/post event))
      (is (= "tc-1" (:call-id data)))
      (is (= "completed" (-> data :result :status)))
      ;; Bare ContentBlock (what the in-tree stub emits) still reads.
      (is (= "ok" (-> data :result :output))))))

(deftest tool-call-update-failed-test
  (testing "tool_call_update with failed → :agent.tool-use/post with :error"
    (let [{:keys [event data]}
          (events/translate-update
           {:sessionId "s1"
            :sessionUpdate "tool_call_update"
            :toolCall {:toolCallId "tc-1"
                       :status "failed"
                       :content [{:type "text" :text "permission denied"}]}})]
      (is (= :agent.tool-use/post event))
      (is (= "failed" (-> data :result :status)))
      (is (some? (-> data :result :error))))))

;; ---------------------------------------------------------------------------
;; ToolCallContent normalization.
;;
;; The raw `ToolCallContent[]` must never reach a renderer: `pr-str`'d it shows
;; the wire envelope (`[{:type "content", :content {:type "text", …}}]`) where
;; the tool's actual output belongs. These pin the flattening per variant.
;; ---------------------------------------------------------------------------

(defn- tool-result
  "Translate one completed/failed tool_call_update and return its `:result`."
  [status content]
  (-> (events/translate-update
       {:sessionId "s1"
        :sessionUpdate "tool_call_update"
        :toolCall (cond-> {:toolCallId "tc-1" :status status}
                    (some? content) (assoc :content content))})
      :data :result))

(deftest tool-content-spec-wrapper-test
  (testing "the spec's {:type \"content\" :content <ContentBlock>} wrapper is unwrapped"
    (let [result (tool-result "completed"
                              [{:type "content"
                                :content {:type "text" :text "total 48\ndrwxr-xr-x"}}])]
      (is (= "total 48\ndrwxr-xr-x" (:output result)))
      ;; The envelope is preserved for persistence, but out of the display key.
      (is (= [{:type "content" :content {:type "text" :text "total 48\ndrwxr-xr-x"}}]
             (:acp/content result)))
      (is (not (str/includes? (str (:output result)) ":type"))))))

(deftest tool-content-multiple-blocks-joined-test
  (testing "several content entries join as lines, in order"
    (is (= "first\nsecond"
           (:output (tool-result "completed"
                                 [{:type "content" :content {:type "text" :text "first"}}
                                  {:type "content" :content {:type "text" :text "second"}}]))))))

(deftest tool-content-diff-test
  (testing "a diff entry becomes structured :diffs, not prose"
    (let [result (tool-result "completed"
                              [{:type "diff" :path "src/x.clj"
                                :oldText "(def a 1)" :newText "(def a 2)"}])]
      (is (= [{:path "src/x.clj" :old "(def a 1)" :new "(def a 2)"}] (:diffs result)))
      ;; A diff carries no prose, so there is no :output to render as text.
      (is (nil? (:output result))))))

(deftest tool-content-mixed-diff-and-text-test
  (testing "prose and diffs from the same result are separated, both kept"
    (let [result (tool-result "completed"
                              [{:type "content" :content {:type "text" :text "edited 1 file"}}
                               {:type "diff" :path "a.clj" :oldText "x" :newText "y"}])]
      (is (= "edited 1 file" (:output result)))
      (is (= 1 (count (:diffs result)))))))

(deftest tool-content-terminal-test
  (testing "a terminal entry renders as a readable placeholder"
    (is (= "[terminal term-7]"
           (:output (tool-result "completed" [{:type "terminal" :terminalId "term-7"}]))))))

(deftest tool-content-unknown-variant-test
  (testing "an unrecognized entry is pr-str'd rather than dropped"
    (let [out (:output (tool-result "completed" [{:type "future_thing" :payload 42}]))]
      (is (some? out))
      (is (str/includes? out "future_thing")))))

(deftest tool-content-empty-completed-test
  (testing "a completed call with no content yields no :output (no empty Result box)"
    (let [result (tool-result "completed" nil)]
      (is (= "completed" (:status result)))
      (is (nil? (:output result))))))

(deftest tool-content-failed-without-detail-test
  (testing "a failed call ALWAYS carries an :error string"
    ;; An absent :error reads as success downstream — the TUI's error? test is
    ;; `(some? (:error result))` — and would render a green `done` marker for a
    ;; call that failed.
    (is (string? (:error (tool-result "failed" nil))))))

(deftest tool-content-failed-error-is-prose-test
  (testing "the failure :error is prose, not the raw wire vector"
    (let [result (tool-result "failed"
                              [{:type "content"
                                :content {:type "text" :text "permission denied"}}])]
      (is (= "permission denied" (:error result)))
      (is (not (str/includes? (:error result) ":type"))))))

(deftest tool-content-strips-a-wrapping-code-fence-test
  (testing "a fence enclosing the WHOLE text is unwrapped (claude-code fences its errors)"
    ;; Observed live: the leading ``` also ate 4 of the head line's 40-char
    ;; error preview before the message started.
    (is (= "Reading file failed: file not found"
           (:error (tool-result "failed"
                                [{:type "content"
                                  :content {:type "text"
                                            :text "```\nReading file failed: file not found\n```"}}])))))
  (testing "a language tag on the opening fence is dropped with it"
    (is (= "(def a 1)"
           (:output (tool-result "completed"
                                 [{:type "content"
                                   :content {:type "text" :text "```clojure\n(def a 1)\n```"}}])))))
  (testing "prose containing a fenced block keeps its fences — only a lone wrapper is stripped"
    (let [text "Here is the fix:\n```\n(def a 1)\n```\nApply it."
          out  (:output (tool-result "completed"
                                     [{:type "content" :content {:type "text" :text text}}]))]
      (is (= text out))))
  (testing "text with two separate fenced blocks is left alone"
    (let [text "```\nfirst\n```\nand\n```\nsecond\n```"
          out  (:output (tool-result "completed"
                                     [{:type "content" :content {:type "text" :text text}}]))]
      (is (= text out)))))

(deftest tool-call-update-raw-output-test
  (testing "the spec's rawOutput is carried through when present"
    (let [result (-> (events/translate-update
                      {:sessionId "s1"
                       :sessionUpdate "tool_call_update"
                       :toolCall {:toolCallId "tc-1" :status "completed"
                                  :rawOutput {:exitCode 0}}})
                     :data :result)]
      (is (= {:exitCode 0} (:raw-output result))))))

(deftest tool-call-update-in-progress-test
  (testing "tool_call_update with in_progress → no event (observer-only)"
    (is (nil?
         (events/translate-update
          {:sessionId "s1"
           :sessionUpdate "tool_call_update"
           :toolCall {:toolCallId "tc-1"
                      :status "in_progress"}})))))

;; -----------------------------------------------------------------------------
;; claude-agent-acp 0.70.0 two-phase tool input
;;
;; Payloads below are VERBATIM captures from adapter 0.70.0 (2026-08-29) for a
;; single Bash call. 0.16.2 emitted `tool_call` twice — placeholder, then real
;; input — so `/pre` fired twice and the TUI's `upsert-tool-call` merged the
;; args. 0.70.0 emits one placeholder `tool_call` and moves the real input into
;; status-less `tool_call_update`s, which used to translate to nil: the args
;; were dropped and the call rendered as `Bash({})`.
;; -----------------------------------------------------------------------------

(deftest tool-call-070-placeholder-carries-no-args-test
  (testing "0.70.0's initial tool_call is an empty-input placeholder"
    (let [evt (events/translate-update
               {:sessionId "s1"
                :sessionUpdate "tool_call"
                :_meta {:claudeCode {:toolName "Bash"}}
                :toolCallId "toolu_01PkCz"
                :rawInput {}
                :status "pending"
                :title "Terminal"
                :kind "execute"
                :content []})]
      (is (= :agent.tool-use/pre (:event evt)))
      ;; The real tool name still comes from _meta, not the "Terminal" title.
      (is (= "Bash" (-> evt :data :tool-name)))
      (is (= {} (-> evt :data :args))
          "no args yet — they arrive in a later tool_call_update"))))

(deftest tool-call-update-070-delivers-args-test
  (testing "a status-less tool_call_update carrying rawInput re-fires /pre"
    (let [evt (events/translate-update
               {:sessionId "s1"
                :sessionUpdate "tool_call_update"
                :_meta {:claudeCode {:toolName "Bash"}}
                :toolCallId "toolu_01PkCz"
                :rawInput {:command "echo probe-args"}
                :title "echo probe-args"
                :kind "execute"
                :content []})]
      (is (= :agent.tool-use/pre (:event evt))
          "same event as the placeholder, so upsert-tool-call merges by call-id")
      (is (= "toolu_01PkCz" (-> evt :data :call-id))
          "the call-id must match the placeholder or the merge appends instead")
      (is (= {:command "echo probe-args"} (-> evt :data :args)))
      (is (= "Bash" (-> evt :data :tool-name)))
      (is (true? (-> evt :data :observer?))))))

(deftest tool-call-update-070-progress-tick-is-silent-test
  (testing "a status-less update with no rawInput stays observer-only"
    ;; Verbatim 0.70.0 capture: the toolResponse tick. Firing /pre here would
    ;; blank the merged args, since it carries none.
    (is (nil?
         (events/translate-update
          {:sessionId "s1"
           :sessionUpdate "tool_call_update"
           :_meta {:claudeCode {:toolResponse {:stdout "probe-args"}
                                :toolName "Bash"}}
           :toolCallId "toolu_01PkCz"
           :content []})))
    (is (nil?
         (events/translate-update
          {:sessionId "s1"
           :sessionUpdate "tool_call_update"
           :toolCallId "toolu_01PkCz"
           :rawInput {}})))))

(deftest unknown-update-kind-test
  (testing "unknown sessionUpdate variant → nil"
    (is (nil?
         (events/translate-update
          {:sessionId "s1"
           :sessionUpdate "future_variant_we_dont_know"
           :something "else"})))))

(deftest stop-reason-translation-test
  (testing "end_turn → :agent.iteration/post with :goal-achieved true"
    (let [{:keys [event data]} (events/translate-stop-reason "end_turn" "s1")]
      (is (= :agent.iteration/post event))
      (is (true? (:goal-achieved data)))
      (is (= "s1" (:session-id data)))))

  (testing "cancelled → :agent.iteration/exhausted with :reason :cancelled"
    (let [{:keys [event data]} (events/translate-stop-reason "cancelled" "s1")]
      (is (= :agent.iteration/exhausted event))
      (is (= :cancelled (:reason data)))))

  (testing "max_tokens / max_turn_requests / refusal → :iteration/exhausted"
    (doseq [reason ["max_tokens" "max_turn_requests" "refusal"]]
      (let [{:keys [event data]} (events/translate-stop-reason reason "s1")]
        (is (= :agent.iteration/exhausted event))
        (is (= (keyword reason) (:reason data))))))

  (testing "unknown stop reason → nil"
    (is (nil? (events/translate-stop-reason "novel_reason" "s1")))))

(deftest pick-option-id-test
  (testing "fallback policy from §9.2 decision 4"
    (let [opts [{:optionId "allow_once"   :name "Allow once"}
                {:optionId "allow_always" :name "Allow always"}
                {:optionId "reject_once"  :name "Reject once"}
                {:optionId "reject_always" :name "Reject always"}]]
      (is (= "allow_once" (callbacks/pick-option-id :allow opts)))
      (is (= "reject_once" (callbacks/pick-option-id :block opts)))
      (is (= "allow_once" (callbacks/pick-option-id :replace opts)))))

  (testing "non-canonical option ids fall back to first allow_/reject_ prefix"
    (let [opts [{:optionId "allow_now"  :name "Allow now"}
                {:optionId "reject_perm" :name "Reject permanently"}]]
      (is (= "allow_now"  (callbacks/pick-option-id :allow opts)))
      (is (= "reject_perm" (callbacks/pick-option-id :block opts)))))

  (testing "no prefix matches → first option"
    (let [opts [{:optionId "yes" :name "Yes"}
                {:optionId "no"  :name "No"}]]
      (is (= "yes" (callbacks/pick-option-id :allow opts)))
      (is (= "yes" (callbacks/pick-option-id :block opts)))))

  (testing "empty options → nil"
    (is (nil? (callbacks/pick-option-id :allow [])))
    (is (nil? (callbacks/pick-option-id :block [])))))
