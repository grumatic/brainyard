;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.react-agent-test
  "Tests for the ReAct agent migration.
   Covers: schemas, BT structure, registration, bind-tools,
   tool-calls-action, and full BT execution with mocked DSPy."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.agent.common.react-agent :as react]
            [ai.brainyard.agent.common.schema :as acs]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.common.commands :as commands]
            [ai.brainyard.agent.common.trace :as trace]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.agent :as agent]
            [ai.brainyard.agent.core.session :as session]
            [ai.brainyard.agent.core.bt :as agent-bt]
            [ai.brainyard.agent.core.context :as context]
            [ai.brainyard.behavior-tree.interface :as bt]
            [ai.brainyard.behavior-tree.interface.protocol :as p]
            [ai.brainyard.clj-llm.interface :as dspy]
            [malli.core :as m]))

;; ============================================================================
;; Test 1: Schema Validation
;; ============================================================================

(deftest schema-validation-test
  (testing "Common agent schemas resolve and validate"
    (is (m/validate ::acs/question "What is the weather?"))
    (is (m/validate ::acs/answer "It is sunny."))
    (is (m/validate ::acs/conversation
                    [{:role "user" :content "Hi"}
                     {:role "assistant" :content "Hello!"}]))
    (is (m/validate ::acs/tool-calls
                    [{:tool-name "search" :tool-args [{:name "q" :value "test"}]}]))
    (is (m/validate ::acs/tool-results
                    [{:tool-name "search" :tool-args [] :tool-result "found it"}]))
    (is (m/validate ::acs/tools
                    [{:name "search"
                      :description "Search the web"
                      :parameters {:type "object"
                                   :properties {:q {:type "string"}}
                                   :required ["q"]}}])))

  (testing "React-specific schemas resolve and validate"
    (is (m/validate ::react/thought "I should search for information."))
    (is (m/validate ::react/thoughts ["thought 1" "thought 2"]))
    (is (m/validate ::react/observation "Found 3 results about Clojure."))
    (is (m/validate ::react/observations ["obs 1" "obs 2"]))
    (is (m/validate ::react/goal-achieved true))
    (is (m/validate ::react/next-user-prompt "Ask a follow-up question."))
    (is (m/validate ::react/request-for-information false))
    (is (m/validate ::react/iterations
                    [{:iteration 1
                      :thought "Let me search"
                      :actions [{:tool-name "search" :tool-args [] :tool-result "ok"}]
                      :observation "Found results"
                      :evaluation {:goal-achieved false}}]))))

;; ============================================================================
;; Test 2: DSPy Signature Structure
;; ============================================================================

(deftest dspy-signature-test
  ;; After M2, ReAct's surviving signature drops redundant inputs
  ;; (:conversation / :agent-context / :tool-context / :tools /
  ;; :instruction) — those fields move into the system message via
  ;; :stable-keys #{:system-context :user-context}. Only the per-turn
  ;; deltas remain as signature inputs.
  ;;
  ;; Multi-mode signatures (ThinkAndSelectTools / ObserveAndEvaluate /
  ;; FinalizeAnswer) were removed; the single-mode signature
  ;; ThinkActAndEvaluate is the sole remaining variant.
  (testing "ThinkActAndEvaluate signature has correct keys"
    (is (some? react/ThinkActAndEvaluate))
    (let [{:keys [input-keys output-keys]} react/ThinkActAndEvaluate]
      (is (contains? input-keys :question))
      (is (contains? input-keys :recalled-memory))
      (is (contains? input-keys :thoughts))
      (is (contains? input-keys :observations))
      (is (contains? input-keys :tool-results))
      (is (contains? input-keys :iterations))
      ;; Moved into the system message via stable-keys
      (is (not (contains? input-keys :tools)))
      (is (not (contains? input-keys :conversation)))
      (is (not (contains? input-keys :agent-context)))
      (is (not (contains? input-keys :tool-context)))
      (is (not (contains? input-keys :instruction)))
      (is (contains? output-keys :tool-calls))
      (is (contains? output-keys :observation))
      (is (contains? output-keys :goal-achieved))
      ;; :goal-reasoning was dropped; :next-user-prompt added (mirrors CoAct's
      ;; answer-channel self-assessment — surfaced once per turn).
      (is (not (contains? output-keys :goal-reasoning)))
      (is (contains? output-keys :next-user-prompt))
      (is (contains? output-keys :request-for-information))
      (is (contains? output-keys :answer))))

  (testing "multi-mode signatures are removed"
    (is (nil? (resolve 'ai.brainyard.agent.common.react-agent/ThinkAndSelectTools)))
    (is (nil? (resolve 'ai.brainyard.agent.common.react-agent/ObserveAndEvaluate)))
    (is (nil? (resolve 'ai.brainyard.agent.common.react-agent/FinalizeAnswer)))))

;; ============================================================================
;; Test 3: BT Structure
;; ============================================================================

(deftest bt-structure-test
  ;; After multi-mode removal, the BT id keyword prefix is just "react"
  ;; (no longer split between "react-single" / "react-multi").
  (testing "thinking-loop-subtree produces valid BT config"
    (let [bt-config (react/thinking-loop-subtree 5)]
      (is (= :sequence (first bt-config)))
      (is (= :react.sequence/thinking-loop
             (get-in bt-config [1 :id])))
      ;; [:sequence opts fallback] — repeat is inside the fallback guard
      (is (= 3 (count bt-config)))
      (let [fallback-node (nth bt-config 2)
            repeat-node (nth fallback-node 2)]
        (is (= :fallback (first fallback-node)))
        (is (= :repeat (first repeat-node)))
        (is (= 5 (get-in repeat-node [1 :max-n]))))))

  (testing "react-behavior-tree produces valid BT config"
    (let [bt-config (react/react-behavior-tree 10)]
      (is (= :sequence (first bt-config)))
      (is (= :react.sequence/main
             (get-in bt-config [1 :id])))
      ;; condition(question), action(prepare-conversation),
      ;; action(prepare-recalled-memory), action(init), fallback(thinking-loop),
      ;; action(ensure-answer), condition(answer), action(maintain-conversation)
      (is (= 10 (count bt-config))))) ;; [:sequence opts cond act act act fallback act cond act]

  (testing "BT can be built with behavior-tree engine"
    (let [bt-config (react/react-behavior-tree 3)
          context {:st-memory {:question "test question"
                               :tools []
                               :tools-fn-map {}}}
          built (bt/build bt-config context)]
      (is (some? (:tree built)))
      (is (some? (:context built)))
      (is (instance? clojure.lang.Atom (get-in built [:context :st-memory])))
      (is (= "test question" (:question @(get-in built [:context :st-memory])))))))

;; ============================================================================
;; Test 3b: Hardening against oversized tool results / degenerate iterations
;; ============================================================================

(deftest normalize-evaluation-coerces-booleans
  (let [normalize @#'react/normalize-evaluation-action]
    (testing "real boolean true is preserved (loop may exit)"
      (let [st (atom {:goal-achieved true :request-for-information false
                      :answer "done" :tool-calls [] :observation "obs"})]
        (normalize {:st-memory st})
        (is (true? (:goal-achieved @st)))
        (is (= 0 (:consecutive-empty @st)))
        (is (false? (:react-degenerate-stop @st)))))
    (testing "empty-string goal-achieved (degenerate) is coerced to false and counted"
      (let [st (atom {:goal-achieved "" :request-for-information ""
                      :answer "" :tool-calls [] :observation ""
                      :consecutive-empty 0})]
        (normalize {:st-memory st})
        (is (false? (:goal-achieved @st)) "\"\" must not stay truthy")
        (is (false? (:request-for-information @st)))
        (is (= 1 (:consecutive-empty @st)))
        (is (false? (:react-degenerate-stop @st)) "one empty iter is below threshold")))
    (testing "consecutive degenerate iterations trip the stop flag"
      (let [st (atom {:goal-achieved "" :request-for-information ""
                      :answer "" :tool-calls [] :observation ""
                      :consecutive-empty 1})]
        (normalize {:st-memory st})
        (is (= 2 (:consecutive-empty @st)))
        (is (true? (:react-degenerate-stop @st)) "second empty iter trips the stop")))
    (testing "a productive iteration resets the empty counter"
      (let [st (atom {:goal-achieved false :request-for-information false
                      :answer "" :tool-calls [{:tool-name "x"}] :observation ""
                      :consecutive-empty 1})]
        (normalize {:st-memory st})
        (is (= 0 (:consecutive-empty @st)))
        (is (false? (:react-degenerate-stop @st)))))))

(deftest loop-condition-strict-on-degenerate-goal
  (testing "loop-exit condition ignores a degenerate \"\" goal-achieved but honors the stop flag"
    (let [sub (react/thinking-loop-subtree 5)
          fallback (nth sub 2)
          repeat-node (nth fallback 2)
          cond-fn (get-in repeat-node [1 :condition-fn])]
      (is (not (cond-fn {:st-memory (atom {:goal-achieved "" :request-for-information ""})}))
          "\"\" goal-achieved must NOT exit the loop")
      (is (cond-fn {:st-memory (atom {:goal-achieved true})}))
      (is (cond-fn {:st-memory (atom {:react-degenerate-stop true})})
          "degenerate-stop flag exits the loop"))))

(deftest truncate-result-spills-oversized-to-file
  (let [truncate @#'react/truncate-result
        big (apply str (repeat 200000 "x"))
        {:keys [result-str]} (truncate big)]
    (is (< (count result-str) (count big)) "oversized result is truncated")
    (is (re-find #"TRUNCATED" result-str) "carries a recovery marker")
    (is (re-find #"saved to:" result-str) "points at the spill file")))

;; ============================================================================
;; Test 4: Skill and Agent Registration
;; ============================================================================

(deftest registration-test
  (testing "react-skill$thinking-loop is registered"
    (let [skill-defs (tool/get-tool-defs :type :skill)]
      (is (contains? skill-defs :react-skill$thinking-loop))
      (let [skill-def (get skill-defs :react-skill$thinking-loop)]
        (is (= :react-skill$thinking-loop (:id skill-def)))
        (is (some? (:fn skill-def)))
        (is (some? (:meta skill-def))))))

  (testing "react-agent is registered"
    (let [agent-defs (tool/get-tool-defs :type :agent)]
      (is (contains? agent-defs :react-agent))
      (let [agent-def (get agent-defs :react-agent)]
        (is (= :react-agent (:id agent-def)))
        (is (some? (:fn agent-def)))
        (is (some? (:meta agent-def)))))))

;; ============================================================================
;; Test 5: bind-tools
;; ============================================================================

(defn ^{:desc "add two numbers"}
  test-add
  [^{:type "number" :desc "first number"} a
   ^{:type "number" :desc "second number"} b]
  (+ (parse-long a) (parse-long b)))

(deftest bind-tools-test
  (testing "bind-tools with function vars"
    (let [[tools tools-fn-map] (tool/bind-tools :functions [#'test-add])]
      ;; Should have only test-add (no more pseudo list-tools)
      (is (= 1 (count tools)))
      (is (some #(= "test-add" (:name %)) tools))
      ;; Verify tool structure
      (let [add-tool (first (filter #(= "test-add" (:name %)) tools))]
        (is (= "add two numbers" (:description add-tool)))
        (is (= "object" (get-in add-tool [:parameters :type]))))
      ;; fn-map should have entry
      (is (contains? tools-fn-map "test-add"))))

  (testing "bind-tools rejects nested vectors"
    (is (thrown? clojure.lang.ExceptionInfo
                 (tool/bind-tools :functions [[:math [#'test-add]]]))))

  (testing "bind-tools with common commands"
    (let [[tools _tools-fn-map] (tool/bind-tools
                                 :tools commands/all-common-commands)]
      ;; Runtime commands
      (is (some #(= "agent-runtime$config" (:name %)) tools)))))

;; ============================================================================
;; Test 6: tool-calls-action
;; ============================================================================

(deftest tool-calls-action-test
  (testing "tool-calls-action executes tools and stores results"
    (let [call-log (atom [])
          mock-fn-map {"calculator" (fn [args]
                                      (swap! call-log conj {:tool "calculator" :args args})
                                      {:result 42})
                       "greeter"   (fn [args]
                                     (swap! call-log conj {:tool "greeter" :args args})
                                     (str "Hello, " (get args "name") "!"))}
          st-mem (atom {:tool-calls [{:tool-name "calculator"
                                      :tool-args [{:name "expression" :value "2+2"}]}
                                     {:tool-name "greeter"
                                      :tool-args [{:name "name" :value "World"}]}]
                        :tools [{:name "calculator"} {:name "greeter"}]
                        :tools-fn-map mock-fn-map})
          context {:st-memory st-mem}
          result (react/tool-calls-action context)]
      ;; Should succeed
      (is (= :success result))
      ;; Both tools called. tool-calls-action dispatches via pmap, so the
      ;; side-effect order of call-log is racy — assert the SET of calls, not
      ;; their order. Ordered coverage is checked on :tool-results below, which
      ;; preserves pmap's ORDERED return value (the deterministic contract).
      (is (= 2 (count @call-log)))
      (is (= #{"calculator" "greeter"} (set (map :tool @call-log))))
      ;; Results stored in st-memory (tool-name stored as keyword — call-tool coerces via keyword)
      (let [results (:tool-results @st-mem)]
        (is (= 2 (count results)))
        (is (= "calculator" (:tool-name (first results))))
        (is (= "greeter" (:tool-name (second results)))))))

  (testing "tool-calls-action handles unbound tools gracefully"
    (let [st-mem (atom {:tool-calls [{:tool-name "nonexistent"
                                      :tool-args []}]
                        :tools []
                        :tools-fn-map {}})
          context {:st-memory st-mem}
          result (react/tool-calls-action context)]
      ;; Should still succeed (unbound is not a call failure)
      (is (= :success result))
      ;; Error message stored (results are pr-str'd by truncate-result, so always strings)
      (let [results (:tool-results @st-mem)]
        (is (= 1 (count results)))
        (is (string? (:tool-result (first results))))
        (is (.contains (str (:tool-result (first results))) "not bound as a tool"))))))

;; ============================================================================
;; Test 7: Trace helpers
;; ============================================================================

(deftest trace-helpers-test
  (testing "add-trace-event doesn't throw"
    (let [mock-agent {:agent-id "test" :user-id "u1" :session-id "s1"}]
      ;; Should not throw — may return nil or mulog result
      (try
        (trace/add-trace-event mock-agent {:test true})
        (is true)
        (catch Exception e
          (is false (str "Should not throw: " (ex-message e)))))))

  (testing "default-maintain-conversation is a function"
    (is (fn? trace/default-maintain-conversation)))

  (testing "maintain-conversation reads from st-memory"
    (let [st-mem (atom {:question "What is Clojure?"
                        :answer "A functional programming language."})
          context {:st-memory st-mem}
          result (trace/default-maintain-conversation context)]
      ;; Should return success
      (is (= :success result)))))

;; ============================================================================
;; Test 8: Common commands registry
;; ============================================================================

(deftest common-commands-registry-test
  (testing "all-common-commands exposes registry/runtime commands"
    (let [ids (set (map (comp :id meta deref) commands/all-common-commands))]
      (is (contains? ids :agent-registry$instances))
      (is (contains? ids :agent-runtime$config))
      ;; agent-knowledge$* commands were removed in the L1 simplification.
      (is (not (contains? ids :agent-knowledge$update)))
      ;; agent-session$clear was removed; ensure it stays gone.
      (is (not (contains? ids :agent-session$clear))))))

;; ============================================================================
;; Test 10: Full BT execution with mocked DSPy
;; ============================================================================

(def ^:private mock-iter-counter (atom 0))

(defn mock-dspy-handler
  "Mock DSPy action for single-mode ReAct.

   Single-mode forces a hard choice per iteration: EITHER tool-calls
   OR answer (the BT's call-tools step short-circuits when goal-achieved
   is true). To keep parity with the legacy multi-mode tests (which
   exercised both the tool path AND the answer path), this mock counts
   iterations: iter 1 fires the test tool with goal-achieved=false,
   iter 2 returns the final answer with goal-achieved=true and
   tool-calls=[]. The BT therefore exits after exactly two iterations.

   `mock-iter-counter` is rebound to 0 by tests via `with-redefs` or by
   resetting before each test run."
  [{{:keys [signature]} :opts
    :keys [st-memory]
    :as _context}]
  (let [sig (if (var? signature) @signature signature)
        sig-name (:name sig)
        iter (swap! mock-iter-counter inc)]
    (case sig-name
      "ThinkActAndEvaluate"
      (do
        (if (= iter 1)
          (swap! st-memory assoc
                 :last-reasoning "I should add some numbers to test the tool."
                 :tool-calls [{:tool-name "test-add"
                               :tool-args [{:name "a" :value "1"}
                                           {:name "b" :value "2"}]}]
                 :observation ""
                 :goal-achieved false
                 :goal-reasoning "Need to call the tool first."
                 :request-for-information false
                 :answer "")
          (swap! st-memory assoc
                 :last-reasoning "Tool result received; ready to answer."
                 :tool-calls []
                 :observation "I have tested the add tool successfully."
                 :goal-achieved true
                 :goal-reasoning "I now have enough information to answer."
                 :request-for-information false
                 :answer "Based on my analysis, the tools work correctly."))
        bt/success)

      ;; Default
      (do
        (swap! st-memory assoc :last-reasoning "default mock")
        bt/success))))

(defn- with-mock-counter-reset
  "Wrap a test fn so `mock-iter-counter` starts at 0 every time."
  [f]
  (reset! mock-iter-counter 0)
  (f))

(use-fixtures :each with-mock-counter-reset)

(deftest full-bt-execution-with-mock-dspy-test
  (testing "Full react BT runs to completion with mocked DSPy"
    (with-redefs [bt/dspy mock-dspy-handler]
      (let [bt-config (react/react-behavior-tree 5)
            [tools tools-fn-map] (tool/bind-tools :functions [#'test-add])
            context {:st-memory {:question "What tools are available?"
                                 :tools tools
                                 :tools-fn-map tools-fn-map
                                 :instruction "Answer the question"}
                     :agent nil}
            built (bt/build bt-config context)
            result (bt/run built)]
        ;; BT should succeed
        (is (= :success result))
        ;; st-memory should have answer
        (let [st @(get-in built [:context :st-memory])]
          (is (some? (:answer st)))
          (is (.contains (:answer st) "tools"))
          ;; Should have iteration traces — single-mode mock runs 2
          ;; iterations: iter 1 tool call, iter 2 answer.
          (is (seq (:iterations st)))
          (is (= 2 (count (:iterations st))))
          ;; Thoughts and observations recorded
          (is (seq (:thoughts st)))
          (is (seq (:observations st)))
          ;; Goal achieved
          (is (true? (:goal-achieved st))))))))

(deftest full-bt-execution-multi-iteration-test
  (testing "React BT runs multiple iterations before completing"
    (let [iteration-counter (atom 0)]
      (with-redefs [bt/dspy
                    (fn [{{:keys [signature]} :opts
                          :keys [st-memory] :as _context}]
                      (let [sig (if (var? signature) @signature signature)
                            sig-name (:name sig)]
                        (case sig-name
                          "ThinkActAndEvaluate"
                          (do
                            (swap! iteration-counter inc)
                            (if (= 1 @iteration-counter)
                              ;; First iteration: need to gather info
                              (do
                                (swap! st-memory assoc
                                       :last-reasoning "I need to search first."
                                       :tool-calls [{:tool-name "test-add"
                                                     :tool-args [{:name "a" :value "1"}
                                                                 {:name "b" :value "2"}]}]
                                       :observation "" ; no prior tool-results
                                       :goal-achieved false
                                       :goal-reasoning "Need another round."
                                       :request-for-information false
                                       :answer "")
                                bt/success)
                              ;; Second iteration: done
                              (do
                                (swap! st-memory assoc
                                       :last-reasoning "Now I have the information."
                                       :tool-calls []
                                       :observation "All information gathered."
                                       :goal-achieved true
                                       :goal-reasoning "Complete."
                                       :request-for-information false
                                       :answer "Final answer after 2 iterations.")
                                bt/success)))
                          bt/success)))]
        (let [bt-config (react/react-behavior-tree 5)
              [tools tools-fn-map] (tool/bind-tools :functions [#'test-add])
              context {:st-memory {:question "Complex question"
                                   :tools tools
                                   :tools-fn-map tools-fn-map}
                       :agent nil}
              built (bt/build bt-config context)
              result (bt/run built)]
          (is (= :success result))
          ;; Should have 2 iterations
          (is (= 2 @iteration-counter))
          (let [st @(get-in built [:context :st-memory])]
            (is (= 2 (count (:iterations st))))
            (is (= "Final answer after 2 iterations." (:answer st)))))))))

;; ============================================================================
;; Test 11: get-or-create-agent
;; ============================================================================

(deftest get-or-create-agent-test
  (testing "Creates new agent when none exists"
    (let [ag (@#'agent/get-or-create-agent "u-test" "s-test" "react-test-1"
                                           {:config {:name "Test React"
                                                     :description "Test"}})]
      (is (some? ag))
      (is (= "react-test-1" (proto/agent-id ag)))
      (is (proto/agent-running? ag))
      ;; Cleanup
      (proto/stop-agent ag)))

  (testing "Returns existing agent on second call"
    (let [ag1 (@#'agent/get-or-create-agent "u-test2" "s-test2" "react-test-2"
                                            {:config {:name "Test" :description "Test"}})
          ag2 (@#'agent/get-or-create-agent "u-test2" "s-test2" "react-test-2"
                                            {:config {:name "Different" :description "Different"}})]
      ;; Should be the same instance
      (is (identical? ag1 ag2))
      ;; Cleanup
      (proto/stop-agent ag1))))

;; ============================================================================
;; Test 12: *current-agent* dynamic binding
;; ============================================================================

(deftest current-agent-binding-test
  (testing "*current-agent* is nil by default"
    (is (nil? proto/*current-agent*))
    (is (nil? (proto/get-current-user-id)))
    (is (nil? (proto/get-current-session-id))))

  (testing "*current-agent* can be bound"
    (let [a (@#'agent/create-agent "user-bind" "session-bind" "bind-agent"
                                   :config {:name "Bind Test"})]
      (binding [proto/*current-agent* a]
        (is (= a proto/*current-agent*))
        (is (= "user-bind" (proto/get-current-user-id)))
        (is (= "session-bind" (proto/get-current-session-id)))))))

;; ============================================================================
;; Test 13: Tool argument type coercion
;; ============================================================================

(deftest coerce-value-test
  (testing "coerce-value: integer"
    (is (= 42 (tool/coerce-value "42" "integer")))
    (is (= -7 (tool/coerce-value "-7" "integer")))
    (is (= 0 (tool/coerce-value "0" "integer"))))

  (testing "coerce-value: number (float)"
    (is (= 3.14 (tool/coerce-value "3.14" "number")))
    (is (= -0.5 (tool/coerce-value "-0.5" "number")))
    (is (= 100.0 (tool/coerce-value "100.0" "number"))))

  (testing "coerce-value: boolean"
    (is (= true (tool/coerce-value "true" "boolean")))
    (is (= false (tool/coerce-value "false" "boolean"))))

  (testing "coerce-value: string passthrough"
    (is (= "hello" (tool/coerce-value "hello" "string")))
    (is (= "hello" (tool/coerce-value "hello" nil))))

  (testing "coerce-value: non-string passthrough"
    (is (= 42 (tool/coerce-value 42 "integer")))
    (is (= true (tool/coerce-value true "boolean"))))

  (testing "coerce-value: array"
    (is (= [1 2 3] (tool/coerce-value "[1, 2, 3]" "array")))
    (is (= ["a" "b"] (tool/coerce-value "[\"a\", \"b\"]" "array"))))

  (testing "coerce-value: object (keys are keywordized for ergonomic :keys destructuring)"
    (is (= {:key "val"} (tool/coerce-value "{\"key\": \"val\"}" "object"))))

  (testing "coerce-value: fallback on parse failure"
    (is (= "not-a-number" (tool/coerce-value "not-a-number" "integer")))
    (is (= "not-a-number" (tool/coerce-value "not-a-number" "number")))
    (is (= "maybe" (tool/coerce-value "maybe" "boolean")))
    (is (= "not-json" (tool/coerce-value "not-json" "array")))
    (is (= "not-json" (tool/coerce-value "not-json" "object"))))

  (testing "coerce-value: unknown type passthrough"
    (is (= "foo" (tool/coerce-value "foo" "custom-type")))))

(deftest coerce-tool-args-test
  (testing "coerces args based on properties map"
    (let [args {"count" "5" "ratio" "0.75" "verbose" "true" "name" "Alice"}
          properties {:count {:type "integer"}
                      :ratio {:type "number"}
                      :verbose {:type "boolean"}
                      :name {:type "string"}}
          result (#'tool/coerce-tool-args args properties)]
      (is (= 5 (get result "count")))
      (is (= 0.75 (get result "ratio")))
      (is (= true (get result "verbose")))
      (is (= "Alice" (get result "name")))))

  (testing "returns args unchanged when properties is empty"
    (let [args {"x" "42"}
          result (#'tool/coerce-tool-args args {})]
      (is (= "42" (get result "x")))))

  (testing "handles args with no matching property (no type info)"
    (let [args {"unknown" "123"}
          properties {:known {:type "integer"}}
          result (#'tool/coerce-tool-args args properties)]
      (is (= "123" (get result "unknown"))))))

(deftest schema->type-test
  (testing "bare keyword schemas → JSON Schema primitives"
    (let [r (tool/schema->type [:map [:s :string] [:i :int] [:b :boolean] [:d :double]])]
      (is (= {:type "string"}  (:s r)))
      (is (= {:type "integer"} (:i r)))
      (is (= {:type "boolean"} (:b r)))
      (is (= {:type "number"}  (:d r)))))

  (testing "value-schema props carry :desc"
    (let [r (tool/schema->type [:map [:q [:string {:desc "A question"}]]])]
      (is (= {:type "string" :desc "A question"} (:q r)))))

  (testing "value-schema props carry :default"
    (let [r (tool/schema->type [:map [:n [:int {:desc "count" :default 10}]]])]
      (is (= {:type "integer" :desc "count" :default 10} (:n r)))))

  (testing "entry-level :optional propagates flag"
    (let [r (tool/schema->type [:map [:s {:optional true} [:string {:desc "x"}]]])]
      (is (= {:type "string" :desc "x" :optional true} (:s r)))))

  (testing "all metadata props combined (entry :optional + value :desc/:default)"
    (let [r (tool/schema->type [:map [:t {:optional true} [:int {:desc "timeout" :default 30000}]]])]
      (is (= {:type "integer" :desc "timeout" :default 30000 :optional true} (:t r)))))

  (testing "vector schemas produce array with items"
    (let [r (tool/schema->type [:map
                                [:tags [:vector :string]]
                                [:nums [:vector :int]]])]
      (is (= {:type "array" :items {:type "string"}}  (:tags r)))
      (is (= {:type "array" :items {:type "integer"}} (:nums r)))))

  (testing "vector with entry :optional + value-schema :desc"
    (let [r (tool/schema->type [:map [:tags {:optional true} [:vector {:desc "tag list"} :string]]])]
      (is (= {:type "array" :items {:type "string"} :desc "tag list" :optional true}
             (:tags r)))))

  (testing "enum without metadata"
    (let [r (tool/schema->type [:map [:src [:enum "mcp" "registered"]]])]
      (is (= {:type "string" :enum ["mcp" "registered"]} (:src r)))))

  (testing "enum with entry :optional + value-schema :desc"
    (let [r (tool/schema->type [:map [:src {:optional true} [:enum {:desc "source"}
                                                             "mcp" "registered"]]])]
      (is (= {:type "string" :enum ["mcp" "registered"] :desc "source" :optional true}
             (:src r)))))

  (testing "map schema produces object with properties"
    (let [r (tool/schema->type [:map [:cfg [:map [:host :string] [:port :int]]]])]
      (is (= "object" (get-in r [:cfg :type])))
      (is (= "string"  (get-in r [:cfg :properties :host :type])))
      (is (= "integer" (get-in r [:cfg :properties :port :type])))))

  (testing "empty [:map] schema → empty result"
    (is (= {} (tool/schema->type [:map]))))

  (testing "preserves keyword keys"
    (let [r (tool/schema->type [:map [:question :string] [:answer :string]])]
      (is (= #{:question :answer} (set (keys r)))))))

(deftest tool-calls-action-coercion-test
  (testing "tool-calls-action coerces string args to declared types"
    (let [received-args (atom nil)
          mock-fn-map {"typed-tool" (fn [args]
                                      (reset! received-args args)
                                      {:result "ok"})}
          st-mem (atom {:tool-calls [{:tool-name "typed-tool"
                                      :tool-args [{:name "count" :value "5"}
                                                  {:name "ratio" :value "3.14"}
                                                  {:name "verbose" :value "true"}
                                                  {:name "label" :value "test"}]}]
                        :tools [{:name "typed-tool"
                                 :description "A tool with typed params"
                                 :parameters {:type "object"
                                              :properties {:count {:type "integer" :description "count"}
                                                           :ratio {:type "number" :description "ratio"}
                                                           :verbose {:type "boolean" :description "flag"}
                                                           :label {:type "string" :description "label"}}
                                              :required ["count"]}}]
                        :tools-fn-map mock-fn-map})
          context {:st-memory st-mem}
          result (react/tool-calls-action context)]
      (is (= :success result))
      ;; Verify the tool received coerced types
      (is (= 5 (get @received-args "count")))
      (is (= 3.14 (get @received-args "ratio")))
      (is (= true (get @received-args "verbose")))
      (is (= "test" (get @received-args "label")))))

  (testing "tool-calls-action falls back gracefully on unparseable values"
    (let [received-args (atom nil)
          mock-fn-map {"typed-tool" (fn [args]
                                      (reset! received-args args)
                                      {:result "ok"})}
          st-mem (atom {:tool-calls [{:tool-name "typed-tool"
                                      :tool-args [{:name "count" :value "not-a-number"}]}]
                        :tools [{:name "typed-tool"
                                 :description "Tool"
                                 :parameters {:type "object"
                                              :properties {:count {:type "integer"}}
                                              :required ["count"]}}]
                        :tools-fn-map mock-fn-map})
          context {:st-memory st-mem}
          result (react/tool-calls-action context)]
      (is (= :success result))
      ;; Should fall back to original string
      (is (= "not-a-number" (get @received-args "count"))))))

;; ============================================================================
;; Test 14: Incremental conversation tracking across BT lifecycle
;; ============================================================================

(deftest incremental-conversation-tracking-test
  (testing "Full react flow tracks user, tool(content), and assistant messages in !session :messages"
    ;; After the unified-store refactor the conversation lives at
    ;; !session :messages — `ask` writes user/assistant, the react
    ;; agent writes tool messages. The legacy working-memory
    ;; :conversation buffer was removed.
    (with-redefs [bt/dspy mock-dspy-handler]
      (let [[tools tools-fn-map] (tool/bind-tools :functions [#'test-add])
            ag (@#'agent/create-agent "u-conv" "s-conv" "conv-agent"
                                      :config {:name "Conv Test Agent"}
                                      :bt-config (react/react-behavior-tree 5)
                                      :st-memory-init {:tools tools
                                                       :tools-fn-map tools-fn-map
                                                       :instruction "Answer the question"}
                                      :memory-opts {:in-memory true})
            _ (proto/start-agent ag)
            _ (agent/ask ag "What tools are available?")
            conv (->> @(:!session ag) :messages
                      (filter #(some? (:role %)))
                      vec)]

        ;; Conversation should have at least 3 entries:
        ;; user → tool(content) → assistant(answer)
        (is (>= (count conv) 3)
            (str "Expected >= 3 conversation entries, got " (count conv)))

        ;; First entry: user message
        (let [first-msg (first conv)]
          (is (= "user" (:role first-msg)))
          (is (= "What tools are available?" (:content first-msg))))

        ;; Middle entries: tool messages with :content (stringified tool-results)
        (let [tool-msgs (filter #(= "tool" (:role %)) conv)]
          (is (pos? (count tool-msgs))
              "Should have at least one tool message")
          (is (string? (:content (first tool-msgs)))
              "Tool message should have :content as a string"))

        ;; Last entry: assistant answer
        (let [last-msg (last conv)]
          (is (= "assistant" (:role last-msg)))
          (is (some? (:content last-msg)))
          (is (.contains (:content last-msg) "tools")))

        ;; Cleanup
        (proto/stop-agent ag))))

  (testing "Multi-iteration react flow tracks tool messages for each iteration"
    (let [iteration-counter (atom 0)]
      (with-redefs [bt/dspy
                    (fn [{{:keys [signature]} :opts
                          :keys [st-memory] :as _context}]
                      (let [sig (if (var? signature) @signature signature)
                            sig-name (:name sig)]
                        (case sig-name
                          "ThinkActAndEvaluate"
                          (do
                            (swap! iteration-counter inc)
                            (if (< @iteration-counter 3)
                              ;; Iters 1 and 2 each fire one tool call.
                              (swap! st-memory assoc
                                     :last-reasoning (str "Iteration " @iteration-counter " reasoning.")
                                     :tool-calls [{:tool-name "test-add"
                                                   :tool-args [{:name "a" :value "1"}
                                                               {:name "b" :value "2"}]}]
                                     :observation "Need more info."
                                     :goal-achieved false
                                     :goal-reasoning "Not done yet."
                                     :request-for-information false
                                     :answer "")
                              ;; Iter 3: answer only, no tools (single-mode
                              ;; tools-or-answer contract).
                              (swap! st-memory assoc
                                     :last-reasoning (str "Iteration " @iteration-counter " reasoning.")
                                     :tool-calls []
                                     :observation "All done."
                                     :goal-achieved true
                                     :goal-reasoning "Complete."
                                     :request-for-information false
                                     :answer "Answer after multiple iterations."))
                            bt/success)
                          bt/success)))]
        (let [[tools tools-fn-map] (tool/bind-tools :functions [#'test-add])
              ag (@#'agent/create-agent "u-conv2" "s-conv2" "conv-agent-2"
                                        :config {:name "Conv Multi Agent"}
                                        :bt-config (react/react-behavior-tree 5)
                                        :st-memory-init {:tools tools
                                                         :tools-fn-map tools-fn-map
                                                         :instruction "Answer the question"}
                                        :memory-opts {:in-memory true})
              _ (proto/start-agent ag)
              _ (agent/ask ag "Complex question")
              conv (->> @(:!session ag) :messages
                        (filter #(some? (:role %)))
                        vec)]

          ;; Should have run 3 iterations (2 tool iters + 1 answer iter)
          (is (= 3 @iteration-counter))

          ;; Conversation: 1 user + 2 tool(content) + 1 assistant answer = 4
          (is (>= (count conv) 4)
              (str "Expected >= 4 entries for 2 tool iterations, got " (count conv)))

          ;; Count tool messages (each with :content, no assistant tool-calls messages)
          (let [tool-msgs (filter #(= "tool" (:role %)) conv)]
            (is (= 2 (count tool-msgs))
                "Should have 2 tool messages (one per tool iteration)")
            (doseq [msg tool-msgs]
              (is (string? (:content msg))
                  "Each tool message should have :content as a string")))

          ;; First message is user, last is assistant answer
          (is (= "user" (:role (first conv))))
          (is (= "Complex question" (:content (first conv))))
          (is (= "assistant" (:role (last conv))))
          (is (= "Answer after multiple iterations." (:content (last conv))))

          ;; Cleanup
          (proto/stop-agent ag))))))

;; ============================================================================
;; Test 15: lm-config resolution at agent config, session, and dspy action levels
;; ============================================================================

(def ^:private resolve-lm-config
  "Reference to the private resolve-lm-config fn in dspy-action."
  @(requiring-resolve 'ai.brainyard.behavior-tree.core.dspy-action/resolve-lm-config))

(defn- mock-dspy-capturing-lm-config
  "Mock bt/dspy that captures resolved lm-config and returns canned outputs.
   Single-mode: one ThinkActAndEvaluate call per BT iteration sets all
   output fields. With goal-achieved=true on the first call, the BT
   exits after a single dspy invocation."
  [captured-configs]
  (fn [{{:keys [signature]} :opts
        :keys [st-memory]
        :as context}]
    (swap! captured-configs conj (resolve-lm-config context))
    (let [sig (if (var? signature) @signature signature)
          sig-name (:name sig)]
      (case sig-name
        "ThinkActAndEvaluate"
        (do
          (swap! st-memory assoc
                 :last-reasoning "Mock reasoning"
                 :tool-calls []
                 :observation "Done"
                 :goal-achieved true
                 :goal-reasoning "Complete"
                 :request-for-information false
                 :answer "Mock answer")
          bt/success)

        ;; Default
        bt/success))))

(deftest lm-config-from-agent-config-test
  (testing "lm-config resolved from agent :config :lm-config"
    (let [captured (atom [])
          lm-cfg {:provider :openai :model "gpt-4o"}]
      (with-redefs [bt/dspy (mock-dspy-capturing-lm-config captured)]
        (let [[tools tools-fn-map] (tool/bind-tools :functions [#'test-add])
              ag (@#'agent/create-agent "u-lm1" "s-lm1" "lm-agent-cfg"
                                        :config {:name "Agent Config LM"}
                                        :bt-config (react/react-behavior-tree 5)
                                        :st-memory-init {:tools tools :tools-fn-map tools-fn-map
                                                         :instruction "test"
                                                         :config {:lm-config lm-cfg}}
                                        :memory-opts {:in-memory true})]
          (proto/start-agent ag)
          (let [result (agent-bt/run-bt ag "test question")]
            (is (= :success result))
            ;; Single-mode: one ThinkActAndEvaluate call per BT run
            (is (= 1 (count @captured)))
            ;; resolve-lm-config enriches with :base-url, :auth-header, etc.;
            ;; compare the identity keys the test cares about.
            (is (every? #(= (select-keys lm-cfg [:provider :model])
                            (select-keys % [:provider :model])) @captured)
                (str "Expected agent config lm-config, got: " @captured)))
          (proto/stop-agent ag))))))

(deftest lm-config-from-session-config-test
  (testing "lm-config resolved from session :config :lm-config when agent has none"
    (let [captured (atom [])
          session-lm {:provider :anthropic :model "claude-sonnet"}]
      (with-redefs [bt/dspy (mock-dspy-capturing-lm-config captured)]
        (let [[tools tools-fn-map] (tool/bind-tools :functions [#'test-add])
              ag (@#'agent/create-agent "u-lm2" "s-lm2" "lm-agent-session"
                                        :config {:name "Session Config LM"
                                                 :session-config {:lm-config session-lm}}
                                        :bt-config (react/react-behavior-tree 5)
                                        :st-memory-init {:tools tools :tools-fn-map tools-fn-map
                                                         :instruction "test"}
                                        :memory-opts {:in-memory true})]
          (proto/start-agent ag)
          (let [result (agent-bt/run-bt ag "test question")]
            (is (= :success result))
            (is (= 1 (count @captured)))
            (is (every? #(= (select-keys session-lm [:provider :model])
                            (select-keys % [:provider :model])) @captured)
                (str "Expected session lm-config, got: " @captured)))
          (proto/stop-agent ag))))))

(deftest lm-config-agent-overrides-session-test
  (testing "agent config lm-config takes precedence over session config"
    (let [captured (atom [])
          agent-lm {:provider :openai :model "gpt-4o"}
          session-lm {:provider :anthropic :model "claude-sonnet"}]
      (with-redefs [bt/dspy (mock-dspy-capturing-lm-config captured)]
        (let [[tools tools-fn-map] (tool/bind-tools :functions [#'test-add])
              ag (@#'agent/create-agent "u-lm3" "s-lm3" "lm-agent-priority"
                                        :config {:name "Both LM"
                                                 :session-config {:lm-config session-lm}}
                                        :bt-config (react/react-behavior-tree 5)
                                        :st-memory-init {:tools tools :tools-fn-map tools-fn-map
                                                         :instruction "test"
                                                         :config {:lm-config agent-lm}}
                                        :memory-opts {:in-memory true})]
          (proto/start-agent ag)
          (let [result (agent-bt/run-bt ag "test question")]
            (is (= :success result))
            (is (= 1 (count @captured)))
            ;; Agent config wins over session config
            (is (every? #(= (select-keys agent-lm [:provider :model])
                            (select-keys % [:provider :model])) @captured)
                (str "Expected agent lm-config to win, got: " @captured)))
          (proto/stop-agent ag))))))

(deftest lm-config-dspy-action-opts-overrides-all-test
  (testing "BT action opts :lm-config overrides agent and session config"
    (let [captured (atom [])
          agent-lm {:provider :openai :model "gpt-4o"}
          opts-lm {:provider :ollama :model "llama3"}]
      (with-redefs [bt/dspy (mock-dspy-capturing-lm-config captured)]
        ;; bt-config must be constructed INSIDE with-redefs so bt/dspy resolves to mock
        (let [bt-config [:sequence {:id :test/main}
                         [:condition {:id :test/check-question
                                      :path [:question] :schema :string}
                          bt/st-memory-has-value?]
                         [:action {:id :test/dspy-with-lm-config
                                   :signature #'react/ThinkActAndEvaluate
                                   :operation :chain-of-thought
                                   :lm-config opts-lm}
                          bt/dspy]
                         [:condition {:id :test/check-answer
                                      :path [:answer] :schema :string}
                          bt/st-memory-has-value?]]
              [tools tools-fn-map] (tool/bind-tools :functions [#'test-add])
              ag (@#'agent/create-agent "u-lm4" "s-lm4" "lm-agent-opts"
                                        :config {:name "Opts LM" :lm-config agent-lm}
                                        :bt-config bt-config
                                        :st-memory-init {:tools tools :tools-fn-map tools-fn-map
                                                         :instruction "test"
                                                         :iterations [{:iteration 1 :thought "t" :actions []
                                                                       :observation "o"
                                                                       :evaluation {:goal-achieved true
                                                                                    :goal-reasoning "done"}}]}
                                        :memory-opts {:in-memory true})]
          (proto/start-agent ag)
          (let [result (agent-bt/run-bt ag "test question")]
            (is (= :success result))
            ;; Only 1 dspy call (single-mode ThinkActAndEvaluate)
            (is (= 1 (count @captured)))
            ;; Opts-level lm-config wins over agent config
            (is (every? #(= (select-keys opts-lm [:provider :model])
                            (select-keys % [:provider :model])) @captured)
                (str "Expected opts lm-config to override, got: " @captured)))
          (proto/stop-agent ag))))))

(deftest lm-config-shared-via-session-store-test
  (testing "Two agents sharing a session store use the same session lm-config"
    (let [captured (atom [])
          session-lm {:provider :anthropic :model "claude-haiku"}
          store (session/create-session-store)]
      (with-redefs [bt/dspy (mock-dspy-capturing-lm-config captured)]
        (let [[tools tools-fn-map] (tool/bind-tools :functions [#'test-add])
              ;; Agent 1 creates session with lm-config
              ag1 (@#'agent/create-agent "u-shared" "s-shared" "ag-shared-1"
                                         :config {:name "Agent 1"
                                                  :session-config {:lm-config session-lm}}
                                         :bt-config (react/react-behavior-tree 5)
                                         :st-memory-init {:tools tools :tools-fn-map tools-fn-map
                                                          :instruction "test"}
                                         :memory-opts {:in-memory true}
                                         :session-store store)
              ;; Agent 2 joins the same session (no lm-config of its own)
              ag2 (@#'agent/create-agent "u-shared" "s-shared" "ag-shared-2"
                                         :config {:name "Agent 2"}
                                         :bt-config (react/react-behavior-tree 5)
                                         :st-memory-init {:tools tools :tools-fn-map tools-fn-map
                                                          :instruction "test"}
                                         :memory-opts {:in-memory true}
                                         :session-store store)]
          ;; Verify they share the same session atom
          (is (identical? (:!session ag1) (:!session ag2)))

          ;; Run agent 2 — should pick up session lm-config set by agent 1
          (proto/start-agent ag2)
          (let [result (agent-bt/run-bt ag2 "test from agent 2")]
            (is (= :success result))
            (is (= 1 (count @captured)))
            (is (every? #(= (select-keys session-lm [:provider :model])
                            (select-keys % [:provider :model])) @captured)
                (str "Agent 2 should use shared session lm-config, got: " @captured)))
          (proto/stop-agent ag2))))))

;; ============================================================================
;; Test 16: Agent-context in st-memory
;; ============================================================================

(deftest agent-context-in-st-memory-test
  (testing "agent-context in st-memory-init flows into BT st-memory"
    (with-redefs [bt/dspy mock-dspy-handler]
      (let [[tools tools-fn-map] (tool/bind-tools :functions [#'test-add])
            ag (@#'agent/create-agent "u-ctx" "s-ctx" "ctx-agent"
                                      :config {:name "Context Agent"}
                                      :bt-config (react/react-behavior-tree 5)
                                      :st-memory-init {:tools tools
                                                       :tools-fn-map tools-fn-map
                                                       :instruction "test"
                                                       :agent-context "You are a helpful assistant."}
                                      :memory-opts {:in-memory true})]
        (proto/start-agent ag)
        ;; Verify st-memory-init has agent-context
        (let [st-mem-init (proto/get-st-memory-init ag)]
          (is (= "You are a helpful assistant." (:agent-context @st-mem-init))))
        ;; Run BT and check st-memory has agent-context
        (let [result (agent-bt/run-bt ag "Hello")
              st-mem @(proto/get-bt-st-memory ag)]
          (is (= :success result))
          (is (= "You are a helpful assistant." (:agent-context st-mem))))
        (proto/stop-agent ag))))

  (testing "agent-context defaults to nil when not provided"
    (with-redefs [bt/dspy mock-dspy-handler]
      (let [[tools tools-fn-map] (tool/bind-tools :functions [#'test-add])
            ag (@#'agent/create-agent "u-ctx2" "s-ctx2" "ctx-agent-2"
                                      :config {:name "No Context Agent"}
                                      :bt-config (react/react-behavior-tree 5)
                                      :st-memory-init {:tools tools
                                                       :tools-fn-map tools-fn-map
                                                       :instruction "test"}
                                      :memory-opts {:in-memory true})]
        (proto/start-agent ag)
        (let [st-mem-init (proto/get-st-memory-init ag)]
          (is (nil? (:agent-context @st-mem-init))))
        (proto/stop-agent ag)))))

;; ============================================================================
;; Test 17: Knowledge section commands (update/remove/list)
;; ============================================================================

;; The agent-knowledge$* commands and the supporting context helpers
;; (set/remove/get-knowledge-section!) were removed in the L1
;; simplification refactor. System context is now operator-only —
;; operators write directly via `mem/write-entry` at L1 with
;; `:kind :system-context`. The assemble-field flow is exercised in
;; agent.core.context-test against a real store.

;; ============================================================================
;; Test 19: Runtime config commands
;; ============================================================================

(deftest config-commands-test
  ;; Isolate from the real `.brainyard/config.edn` so the test sees the
  ;; schema defaults (load) and doesn't mutate the file (set).
  (with-redefs [ai.brainyard.agent.core.config/load-global-config!
                (fn [& _]
                  (reset! ai.brainyard.agent.core.config/!global-config
                          ai.brainyard.agent.core.config/default-config))
                ai.brainyard.agent.core.config/write-edn-config!
                (fn [& _] "/tmp/by-test-stub.edn")]
    (reset! ai.brainyard.agent.core.config/!global-config nil)
    (testing "agent-runtime$config (read mode) returns defaults when no agent-specific config set"
      (let [ag (@#'agent/create-agent "u-cfg" "s-cfg" "cfg-agent"
                                      :config {:name "Config Agent"}
                                      :st-memory-init {}
                                      :memory-opts {:in-memory true})]
        (proto/start-agent ag)
        (binding [proto/*current-agent* ag]
          (let [result (tool/invoke-tool :agent-runtime$config)]
            (is (map? (:config result)))
            (is (= false (get-in result [:config :show-llm-streaming])))
            (is (= 128000 (get-in result [:config :max-context-tokens])))))
        (proto/stop-agent ag)))

    (testing "agent-runtime$config (set mode) changes values and returns updated config"
      (reset! ai.brainyard.agent.core.config/!global-config nil)
      (let [ag (@#'agent/create-agent "u-cfg2" "s-cfg2" "cfg-agent-2"
                                      :config {:name "Config Agent 2"}
                                      :st-memory-init {}
                                      :memory-opts {:in-memory true})]
        (proto/start-agent ag)
        (binding [proto/*current-agent* ag]
          (let [result (tool/invoke-tool :agent-runtime$config
                                         :key "show-llm-streaming" :value "true")]
            (is (some? (:result result)))
            (is (= true (get-in result [:config :show-llm-streaming]))))
          (let [result (tool/invoke-tool :agent-runtime$config
                                         :key "max-context-tokens" :value "8192")]
            (is (some? (:result result)))
            (is (= 8192 (get-in result [:config :max-context-tokens]))))
          (let [result (tool/invoke-tool :agent-runtime$config)]
            (is (= true (get-in result [:config :show-llm-streaming])))
            (is (= 8192 (get-in result [:config :max-context-tokens])))))
        (proto/stop-agent ag)))

    (testing "agent-runtime$config rejects invalid key"
      (let [ag (@#'agent/create-agent "u-cfg3" "s-cfg3" "cfg-agent-3"
                                      :config {:name "Config Agent 3"}
                                      :st-memory-init {}
                                      :memory-opts {:in-memory true})]
        (proto/start-agent ag)
        (binding [proto/*current-agent* ag]
          (let [result (tool/invoke-tool :agent-runtime$config
                                         :key "nonexistent-key" :value "true")]
            (is (some? (:error-message result)))
            (is (.contains (:error-message result) "Invalid config key"))))
        (proto/stop-agent ag)))

    (testing "agent-runtime$config rejects partial args (key without value, or vice versa)"
      (let [ag (@#'agent/create-agent "u-cfg4" "s-cfg4" "cfg-agent-4"
                                      :config {:name "Config Agent 4"}
                                      :st-memory-init {}
                                      :memory-opts {:in-memory true})]
        (proto/start-agent ag)
        (binding [proto/*current-agent* ag]
          (let [result (tool/invoke-tool :agent-runtime$config :key "max-iterations")]
            (is (some? (:error-message result)))
            (is (.contains (:error-message result) "Both 'key' and 'value'")))
          (let [result (tool/invoke-tool :agent-runtime$config :value "10")]
            (is (some? (:error-message result)))
            (is (.contains (:error-message result) "Both 'key' and 'value'"))))
        (proto/stop-agent ag)))

    (testing "runtime commands return error when no agent bound"
      (is (= {:error-message "current agent is not running"}
             (tool/invoke-tool :agent-runtime$config)))
      (is (= {:error-message "current agent is not running"}
             (tool/invoke-tool :agent-runtime$config
                               :key "show-llm-streaming" :value "false"))))))

;; ============================================================================
;; M3 — Deterministic budget strategies + rebudget action
;; ============================================================================

(deftest format-blocks-shape-test
  (testing "format-thoughts-block emits numbered lines"
    (let [out (@#'react/format-thoughts-block
               ["first thought" "second thought" "third"])]
      (is (clojure.string/includes? out "(1) first thought"))
      (is (clojure.string/includes? out "(2) second thought"))
      (is (clojure.string/includes? out "(3) third"))))

  (testing "format-observations-block emits numbered lines"
    (let [out (@#'react/format-observations-block
               ["saw A" "saw B"])]
      (is (clojure.string/includes? out "(1) saw A"))
      (is (clojure.string/includes? out "(2) saw B"))))

  (testing "format-iterations-block emits ReAct iteration shape"
    (let [iters [{:iteration 1
                  :thought "thought-1"
                  :actions [{:tool-name "echo" :tool-args [] :tool-result "ok"}]
                  :observation "echoed"
                  :evaluation {:goal-achieved false}}
                 {:iteration 2
                  :thought "thought-2"
                  :actions []
                  :observation "no tools"
                  :evaluation {:goal-achieved true}}]
          out (@#'react/format-iterations-block iters)]
      (is (clojure.string/includes? out "(1) thought-1"))
      (is (clojure.string/includes? out "echo"))
      (is (clojure.string/includes? out "echoed"))
      (is (clojure.string/includes? out "(2) thought-2"))))

  (testing "empty inputs return nil (caller treats as missing)"
    (is (nil? (@#'react/format-thoughts-block [])))
    (is (nil? (@#'react/format-observations-block nil)))
    (is (nil? (@#'react/format-iterations-block [])))))

(deftest keep-last-n-thoughts-strategy-test
  (testing "drops oldest one per call until floor (default 3)"
    (let [st-memory (atom {:thoughts ["t1" "t2" "t3" "t4" "t5"]})
          strategies (@#'react/react-strategies st-memory)
          keep (:keep-last-n-thoughts strategies)
          secs {:thoughts "current"}
          r1 (keep secs)
          n1 (count (:thoughts @st-memory))
          _ (is (= 4 n1))
          _ (is (clojure.string/includes? (:thoughts r1) "t2"))
          r2 (keep r1)
          n2 (count (:thoughts @st-memory))
          _ (is (= 3 n2))
          r3 (keep r2)]
      ;; At floor (3) — third call returns secs unchanged.
      (is (= 3 (count (:thoughts @st-memory))))
      (is (= r3 r2)))))

(deftest keep-last-n-observations-strategy-test
  (testing "drops oldest until floor"
    (let [st-memory (atom {:observations ["o1" "o2" "o3" "o4"]})
          strategies (@#'react/react-strategies st-memory)
          keep (:keep-last-n-observations strategies)
          secs {:observations "x"}
          r1 (keep secs)]
      (is (= 3 (count (:observations @st-memory))))
      (is (= ["o2" "o3" "o4"] (:observations @st-memory)))
      ;; At floor — second call is unchanged
      (is (= (keep r1) r1)))))

(deftest react-collapse-iterations-strategy-test
  (testing "keeps last N verbatim and prepends a summary iteration"
    (let [iters (mapv (fn [n]
                        {:iteration n
                         :thought (str "t" n)
                         :actions []
                         :observation (str "obs" n)
                         :evaluation {:goal-achieved false}})
                      (range 1 11))
          st-memory (atom {:iterations iters})
          strategies (@#'react/react-strategies st-memory)
          collapse (:collapse-iterations strategies)
          secs {:iterations "x"}
          _ (collapse secs)
          new-iters (:iterations @st-memory)]
      ;; Default floor 3 + 1 summary = 4
      (is (= 4 (count new-iters)))
      (is (= 0 (:iteration (first new-iters))))
      (is (clojure.string/includes? (:thought (first new-iters)) "t1"))
      (is (clojure.string/includes? (:thought (first new-iters)) "t7"))
      (is (= [8 9 10] (mapv :iteration (rest new-iters)))))))

(deftest react-rebudget-action-skips-iteration-zero-test
  (testing "rebudget no-ops at iteration 0 (init has already enforced)"
    (let [fired (atom 0)
          st-memory (atom {:iteration-count 0
                           :iterations []
                           :thoughts []
                           :observations []
                           :cached-sections {:role "r"}
                           :sys-order [:role]
                           :usr-order []
                           :merged-order [:role]
                           :budget-tokens 100000})
          before (:system-context @st-memory)
          result (with-redefs [ai.brainyard.agent.core.hooks/fire!
                               (fn [& _] (swap! fired inc))]
                   (react/react-rebudget-action {:st-memory st-memory :agent nil}))]
      (is (= bt/success result))
      (is (= before (:system-context @st-memory)))
      (is (zero? @fired)))))

(deftest react-rebudget-action-respects-cadence-test
  (testing ":rebudget-every-n-iter = 3 fires on iters 3 and 6 only"
    (let [fired (atom [])
          fake-agent {:!state (atom {:st-memory-init
                                     (atom {:config {:enable-context-budget true
                                                     :rebudget-every-n-iter 3}})})}
          base {:iterations []
                :thoughts []
                :observations []
                :cached-sections {:role "r"}
                :sys-order [:role]
                :usr-order []
                :merged-order [:role]
                :budget-tokens 100000}]
      (with-redefs [ai.brainyard.agent.core.hooks/fire!
                    (fn [event-key _] (swap! fired conj event-key))]
        (doseq [n [1 2 3 4 5 6]]
          (let [st-memory (atom (assoc base :iteration-count n))]
            (react/react-rebudget-action {:st-memory st-memory :agent fake-agent}))))
      (is (= 2 (count @fired)))
      (is (every? #(= % :agent.context/budgeted) @fired)))))
