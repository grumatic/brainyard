;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.claude-code-test
  "The claude-code CLI exposes --json-schema's StructuredOutput as an OPTIONAL
   tool, so large/agentic prompts can slip into a plain-prose reply that fails
   JSON parsing. reinforce-structured-output hardens the system prompt against
   that whenever structured output is active."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [ai.brainyard.clj-llm.core.claude-code :as cc]))

(def ^:private reinforce  @#'cc/reinforce-structured-output)
(def ^:private directive  @#'cc/structured-output-directive)
(def ^:private build-args @#'cc/build-cli-args)
(def ^:private so-from-result @#'cc/structured-output-from-result)
(def ^:private normalize      @#'cc/normalize-response)
(def ^:private classify       @#'cc/classify-stream-outcome)

(deftest reinforce-appends-only-under-structured-output
  (testing "with :json-schema, the directive is appended to the system prompt"
    (let [sys "SYSTEM PROMPT BODY"
          out (reinforce sys {:json-schema {:type "object"}})]
      (is (str/starts-with? out sys) "original prompt is preserved as the prefix")
      (is (str/ends-with? out directive) "directive is appended verbatim")
      (is (str/includes? out "StructuredOutput") "names the tool the model must call")
      (is (str/includes? out "code-blocks") "steers large code into the code-blocks field")
      (is (str/includes? out "never write files") "forbids doing the task itself")))
  (testing "without :json-schema, the system prompt is returned unchanged"
    (let [sys "SYSTEM PROMPT BODY"]
      (is (= sys (reinforce sys {})))
      (is (= sys (reinforce sys {:json-schema nil}))))))

(deftest build-cli-args-still-emits-json-schema
  (testing "structured-output args shape is intact alongside the nudge"
    (let [args (build-args {:model "opus"} {:json-schema {:type "object"}}
                           {:system-prompt-flag "--system-prompt"
                            :system-prompt-value "SYS"})]
      (is (some #{"--json-schema"} args) "the synthetic StructuredOutput tool is still requested")
      (is (some #{"--max-turns"} args))
      (is (= "opus" (second (drop-while #(not= "--model" %) args)))))))

;; ---------------------------------------------------------------------------
;; structured_output extraction (Claude CLI ≥ 2.1 first-class result field)
;; ---------------------------------------------------------------------------

(defn- text-of
  "Parse the result-text normalize-response produced back into data for
   order-insensitive comparison."
  [resp]
  (json/read-str (get-in resp [:content 0 :text]) :key-fn keyword))

(deftest structured-output-from-result-test
  (testing "reads the first-class :structured_output object as a JSON string"
    (is (= {:done true :items ["a" "b"]}
           (json/read-str (so-from-result {:type "result"
                                           :structured_output {:done true :items ["a" "b"]}})
                          :key-fn keyword))))
  (testing "unwraps a {:value \"<json>\"} envelope to its inner JSON"
    (is (= {:x 1}
           (json/read-str (so-from-result {:structured_output {:value "{\"x\":1}"}})
                          :key-fn keyword))))
  (testing "absent field → nil"
    (is (nil? (so-from-result {:type "result" :result "plain text"}))))
  (testing "a stray scalar in the field is ignored (not a real payload)"
    (is (nil? (so-from-result {:structured_output "oops"})))))

(deftest normalize-response-prefers-structured-output-test
  (testing "the :structured_output field wins over a misleading :result string"
    ;; Mirrors a real CLI ≥ 2.1 result event: :result carries a max_turns/prose
    ;; string, but :structured_output holds the authoritative payload.
    (let [stdout (json/write-str
                  [{:type "assistant"
                    :message {:content [{:type "text" :text "Done."}]}}
                   {:type "result" :subtype "success" :is_error false
                    :terminal_reason "completed"
                    :result "Done — turns exhausted"
                    :structured_output {:tool-calls [] :answer "42" :goal-achieved true}
                    :usage {:input_tokens 10 :output_tokens 5}}])
          resp   (normalize {:exit 0 :stdout stdout} "claude-code:opus")]
      (is (= {:tool-calls [] :answer "42" :goal-achieved true} (text-of resp)))
      (is (= 10 (get-in resp [:usage :input_tokens]))))))

(deftest normalize-response-falls-back-to-tool-use-dig-test
  (testing "older CLI without :structured_output → dig the StructuredOutput tool_use block"
    (let [stdout (json/write-str
                  [{:type "assistant"
                    :message {:content [{:type "tool_use" :name "StructuredOutput"
                                         :input {:answer "hi" :goal-achieved false}}]}}
                   {:type "result" :result "max turns reached"}])
          resp   (normalize {:exit 0 :stdout stdout} "claude-code:opus")]
      (is (= {:answer "hi" :goal-achieved false} (text-of resp))))))

;; ---------------------------------------------------------------------------
;; classify-stream-outcome — exit code is a secondary signal; the result event
;; (:is_error) is authoritative. On CLI ≥ 2.1 success exits 0.
;; ---------------------------------------------------------------------------

(deftest classify-stream-outcome-test
  (testing "clean success: exit 0, no error, text present → not an error, not fatal"
    (is (= {:cli-error? false :recovered? true :fatal? false}
           (classify 0 {:is_error false} "{\"done\":true}"))))
  (testing "exit-0 blank with no error → soft empty-result (retry), NOT fatal"
    (is (= {:cli-error? false :recovered? false :fatal? false}
           (classify 0 {:is_error false} "   "))))
  (testing "older max_turns CLI: exit 1 but structured output recovered → error, recovered, not fatal"
    (is (= {:cli-error? true :recovered? true :fatal? false}
           (classify 1 {:is_error false} "{\"answer\":\"hi\"}"))))
  (testing "nonzero exit with nothing recovered → fatal (throw)"
    (is (= {:cli-error? true :recovered? true :fatal? false}   ;; sanity: recovered guards fatal
           (classify 1 {} "text")))
    (is (:fatal? (classify 1 {} ""))))
  (testing ":is_error true even at exit 0 is a real error"
    (is (= {:cli-error? true :recovered? true :fatal? false}
           (classify 0 {:is_error true} "prose flows downstream")))
    (is (:fatal? (classify 0 {:is_error true} ""))))
  (testing "nil result event (no result event seen) is treated by exit code alone"
    (is (false? (:cli-error? (classify 0 nil "x"))))
    (is (:fatal? (classify 1 nil "")))))
