;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.interface-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.agent.core.session :as session]
            [ai.brainyard.agent.core.runtime :as runtime]
            [ai.brainyard.agent.core.context :as context]
            [ai.brainyard.agent.core.tool :as defs]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.clj-llm.interface :as dspy]))

;; ============================================================================
;; Existing Tests
;; ============================================================================

(deftest interface-functions-exist-test
  (testing "Message functions are defined"
    (is (fn? agent/system-message))
    (is (fn? agent/user-message))
    (is (fn? agent/assistant-message))))

(deftest message-creation-test
  (testing "Creating messages"
    (is (= {:role "system" :content "You are helpful"}
           (agent/system-message "You are helpful")))
    (is (= {:role "user" :content "Hello"}
           (agent/user-message "Hello")))
    (is (= {:role "assistant" :content "Hi there"}
           (agent/assistant-message "Hi there")))))

;; ============================================================================
;; New Tests: Agent Record
;; ============================================================================

(deftest create-agent-test
  (testing "Creating a basic agent"
    (let [a (@#'agent-core/create-agent "user-1" "session-1" "agent-1"
                                        :meta {:name "Test Agent"
                                               :description "A test agent"})]
      (is (some? a))
      (is (= "agent-1" (agent/agent-id a)))
      (is (= "Test Agent" (agent/agent-name a)))
      (is (= "A test agent" (agent/agent-description a)))
      (is (= :created (:status (agent/get-state a))))))

  (testing "Agent lifecycle"
    (let [a (@#'agent-core/create-agent "user-1" "session-1" "agent-lifecycle"
                                        :config {:name "Lifecycle Agent"})]
      (is (not (agent/agent-running? a)))
      (agent/start-agent a)
      (is (agent/agent-running? a))
      (agent/stop-agent a)
      (is (not (agent/agent-running? a)))))

  (testing "Agent implements Closeable"
    (let [a (@#'agent-core/create-agent "user-1" "session-1" "agent-close"
                                        :config {:name "Close Agent"})]
      (agent/start-agent a)
      (.close a)
      (is (not (agent/agent-running? a))))))

;; ============================================================================
;; New Tests: Agent State
;; ============================================================================

(deftest agent-state-test
  (testing "State access via protocol"
    (let [a (@#'agent-core/create-agent "user-1" "session-1" "agent-state"
                                        :config {:name "State Agent" :custom-key "custom-val"})]
      (is (= {:name "State Agent" :custom-key "custom-val"}
             (agent/get-state-value a [:config])))
      (agent/set-state-value! a [:config :new-key] "new-val")
      (is (= "new-val" (agent/get-state-value a [:config :new-key]))))))

;; ============================================================================
;; New Tests: Session Management
;; ============================================================================

(deftest session-management-test
  (testing "Session store"
    (let [store (agent/create-session-store)
          !s (atom (agent/create-session "s1" "user-1"))]
      (session/set-session store "s1" !s)
      (is (= "s1" (:session-id @(session/get-session store "s1"))))
      (is (= 1 (count (session/list-sessions store "user-1"))))
      (session/delete-session store "s1")
      (is (nil? (session/get-session store "s1")))))

  (testing "Session message management"
    (let [s (agent/create-session "s1" "user-1")]
      (is (empty? (session/get-messages s)))
      (let [s2 (session/add-message s {:role "user" :content "Hello"})
            s3 (session/add-message s2 {:role "assistant" :content "Hi"})
            s4 (session/add-message s3 {:role "user" :content "How?"})]
        (is (= 3 (count (session/get-messages s4))))
        (is (= 2 (count (session/get-messages s4 2)))))))

  (testing "Session data updates"
    (let [s (agent/create-session "s1" "user-1")
          s2 (session/update-data s {:trace {:agent-id "test" :depth 0 :content "test trace"}})
          s3 (session/update-data s2 {:exception "some error"})]
      (is (= 1 (count (get-in s2 [:data :traces]))))
      (is (= 1 (count (get-in s3 [:data :exceptions]))))
      (let [s4 (session/clear-data s3)]
        (is (empty? (get-in s4 [:data :traces])))
        (is (empty? (get-in s4 [:data :exceptions])))))))

;; ============================================================================
;; New Tests: Runtime
;; ============================================================================

(deftest runtime-test
  (testing "Runtime state management"
    (let [!state (atom {:runtime (runtime/create-runtime-state)})]
      (is (not (runtime/cancelled? !state)))
      (runtime/cancel-run !state)
      (is (runtime/cancelled? !state))
      (runtime/reset-runtime !state)
      (is (not (runtime/cancelled? !state)))))

  (testing "Action permissions"
    (let [!state (atom {:runtime (runtime/create-runtime-state)})]
      (is (nil? (runtime/get-action-permission !state "action-1")))
      (runtime/set-action-permission !state "action-1" :allowed)
      (is (= :allowed (runtime/get-action-permission !state "action-1")))))

  (testing "Action promises"
    (let [!state (atom {:runtime (runtime/create-runtime-state)})
          p (runtime/create-action-promise !state "action-1")]
      (is (instance? clojure.lang.IPending p))
      (is (not (realized? p)))
      (runtime/deliver-action-response !state "action-1" :yes)
      (is (= :yes @p))))

  (testing "Async execution"
    (let [!state (atom {:runtime (runtime/create-runtime-state)})
          result (atom nil)
          fut (runtime/run-async !state (fn [] (reset! result :done) :done))]
      (is (= :done @fut))
      (is (= :done @result)))))

;; ============================================================================
;; New Tests: Context Building
;; ============================================================================

(deftest context-building-test
  (testing "build-comprehensive-context"
    (let [ctx (agent/build-comprehensive-context
               :system-prompt "You are helpful."
               :conversation [{:role "user" :content "Hi"}
                              {:role "assistant" :content "Hello!"}])]
      (is (string? ctx))
      (is (.contains ctx "System Instructions"))
      (is (.contains ctx "Conversation History"))))

  (testing "process-system-command"
    (is (= {:command "cancel" :args nil}
           (agent/process-system-command "/cancel")))
    (is (= {:command "new" :args "topic here"}
           (agent/process-system-command "/new topic here")))
    (is (nil? (agent/process-system-command "not a command")))))

;; ============================================================================
;; New Tests: BT Integration Protocol
;; ============================================================================

(deftest bt-integration-protocol-test
  (testing "Agent implements BT integration protocol"
    (let [a (@#'agent-core/create-agent "user-1" "session-1" "bt-agent"
                                        :config {:name "BT Agent"})]
      ;; check-run-cancelled? should work
      (is (false? (agent/check-run-cancelled? a)))

      ;; action permissions
      (is (nil? (agent/get-action-permission a "some-action")))
      (agent/set-action-permission a "some-action" :allowed)
      (is (= :allowed (agent/get-action-permission a "some-action")))

      ;; action promises
      (let [p (agent/create-action-promise a "test-action")]
        (is (not (realized? p)))
        (agent/deliver-action a "test-action" :yes)
        (is (= :yes @p)))

      ;; update-session-data
      (agent/update-session-data a {:trace {:agent-id (:agent-id a) :depth 0 :content "test"}})
      (is (= 1 (count (get-in @(:!session a) [:data :traces])))))))

;; ============================================================================
;; New Tests: New Interface Functions Exist
;; ============================================================================

(deftest new-interface-functions-exist-test
  (testing "Agent factory and lifecycle"
    (is (fn? @#'agent-core/create-agent))
    (is (fn? agent/ask))
    (is (fn? agent/ask-async))
    (is (fn? agent/deliver-action)))

  (testing "State access"
    (is (fn? agent/get-state-value))
    (is (fn? agent/set-state-value!))
    (is (fn? agent/get-bt))
    (is (fn? agent/get-bt-context)))

  (testing "BT integration"
    (is (fn? agent/get-bt-st-memory))
    (is (fn? agent/check-run-cancelled?))
    (is (fn? agent/create-action-promise))
    (is (fn? agent/get-action-permission))
    (is (fn? agent/set-action-permission))
    (is (fn? agent/build-bt))
    (is (fn? agent/run-bt))
    (is (fn? agent/skill-behavior-fn)))

  (testing "Memory integration"
    (is (fn? agent/get-memory-manager)))

  (testing "Session management"
    (is (fn? agent/create-session-store))
    (is (fn? agent/create-session)))

  (testing "Context building"
    (is (fn? agent/build-comprehensive-context))
    (is (fn? agent/process-system-command))
    (is (fn? agent/extract-parent-context)))

  (testing "Clone"
    (is (fn? agent/clone-agent))))

;; ============================================================================
;; New Tests: Clone Agent
;; ============================================================================

(deftest clone-agent-test
  (testing "Clone creates new agent with shared session"
    (let [a (@#'agent-core/create-agent "user-1" "s1" "react-agent"
                                        :meta {:name "Original" :description "Test"})
          _ (agent/start-agent a)
          clone (agent/clone-agent a)]
      ;; Different identity, keyword type
      (is (not= (agent/agent-id a) (agent/agent-id clone)))
      (is (keyword? (agent/agent-id clone)))

      ;; Namespace derived from source agent-id
      (is (= "react-agent" (namespace (agent/agent-id clone))))

      ;; Same user/session
      (is (= (agent/user-id a) (agent/user-id clone)))
      (is (= (agent/session-id a) (agent/session-id clone)))

      ;; Shared session atom
      (is (identical? (:!session a) (:!session clone)))

      ;; Config copied
      (is (= "Original" (agent/agent-name clone)))

      ;; Clone is running
      (is (agent/agent-running? clone))

      ;; Cleanup
      (agent/stop-agent clone)
      (agent/stop-agent a)))

  (testing "Clone agent-id namespace derivation from namespaced source"
    (let [a (@#'agent-core/create-agent "u" "s" :skill/planner :config {:name "B"})
          _ (agent/start-agent a)
          clone (agent/clone-agent a)]
      ;; :skill/planner → :skill.planner/<random>
      (is (= "skill.planner" (namespace (agent/agent-id clone))))
      ;; Cleanup
      (agent/stop-agent clone)
      (agent/stop-agent a))))

;; ============================================================================
;; New Tests: Clone Agent with Options
;; ============================================================================

(deftest clone-agent-with-opts-test
  (testing "Clone with st-memory-init-overrides merges into cloned state"
    (let [a (@#'agent-core/create-agent "u" "s" "test-agent"
                                        :config {:name "Leader"}
                                        :st-memory-init {:tools [] :config {:enable-context-budget true}})
          _ (agent/start-agent a)
          clone (agent/clone-agent a
                                   {:st-memory-init-overrides {:config {:enable-context-budget false}}})]
      ;; Override merged
      (is (false? (get-in @(:st-memory-init @(:!state clone))
                          [:config :enable-context-budget])))
      ;; Non-overridden keys preserved
      (is (= [] (get @(:st-memory-init @(:!state clone)) :tools)))
      (agent/stop-agent clone)
      (agent/stop-agent a)))

  (testing "Clone with parent-agent overrides parent"
    (let [leader (@#'agent-core/create-agent "u" "s" "leader" :config {:name "L"})
          _ (agent/start-agent leader)
          clone (agent/clone-agent leader {:parent-agent leader})]
      ;; Parent is the leader itself (not leader's parent which is nil)
      (is (identical? leader
                      (get-in @(:!state clone) [:runtime :parent-agent])))
      (agent/stop-agent clone)
      (agent/stop-agent leader)))

  (testing "Clone without opts retains current behavior"
    (let [a (@#'agent-core/create-agent "u" "s" "no-opts"
                                        :config {:name "NoOpts"}
                                        :st-memory-init {:tools [:a :b] :instruction "test"})
          _ (agent/start-agent a)
          clone (agent/clone-agent a)]
      ;; st-memory-init copied verbatim
      (is (= [:a :b] (get @(:st-memory-init @(:!state clone)) :tools)))
      (is (= "test" (get @(:st-memory-init @(:!state clone)) :instruction)))
      (agent/stop-agent clone)
      (agent/stop-agent a))))

;; ============================================================================
;; New Tests: ask-async (Clojure agent-based)
;; ============================================================================

(deftest ask-async-basic-test
  (testing "ask-async returns a Clojure agent ref"
    (let [a (@#'agent-core/create-agent "u1" "s1" "async-1" :config {:name "Async"})
          _ (agent/start-agent a)
          clj-ag (agent/ask-async a "Hello")]
      (is (instance? clojure.lang.Agent clj-ag))
      (await clj-ag)
      (.close a)))

  (testing "ask-async result has correct state shape"
    (let [a (@#'agent-core/create-agent "u1" "s1" "async-2" :config {:name "Async"})
          _ (agent/start-agent a)
          clj-ag (agent/ask-async a "Hello")]
      (await clj-ag)
      (let [state @clj-ag]
        (is (contains? state :agent))
        (is (contains? state :input))
        (is (contains? state :output))
        (is (= "Hello" (:input state)))
        (is (identical? a (:agent state))))
      (.close a)))

  (testing "ask-async output matches what ask returns (no BT fallback)"
    (let [a (@#'agent-core/create-agent "u1" "s1" "async-3" :config {:name "Async"})
          _ (agent/start-agent a)
          clj-ag (agent/ask-async a "What?")]
      (await clj-ag)
      (let [output (:output @clj-ag)]
        (is (= :no-bt (:result output)))
        (is (= "What?" (:input output))))
      (.close a)))

  (testing "Same clj-agent ref returned on consecutive calls"
    (let [a (@#'agent-core/create-agent "u1" "s1" "async-4" :config {:name "Async"})
          _ (agent/start-agent a)
          ref1 (agent/ask-async a "Q1")
          ref2 (agent/ask-async a "Q2")]
      (is (identical? ref1 ref2))
      (await ref1)
      (.close a)))

  (testing "Back-to-back calls execute sequentially, last output wins"
    (let [order (atom [])
          a (@#'agent-core/create-agent "u1" "s1" "async-5" :config {:name "Async"})
          _ (agent/start-agent a)]
      ;; Wrap ask to track execution order
      (with-redefs [agent-core/ask (fn [ag input]
                                     (swap! order conj input)
                                     (Thread/sleep 50)
                                     {:result input})]
        (let [clj-ag (agent/ask-async a "first")
              _ (agent/ask-async a "second")
              _ (agent/ask-async a "third")]
          (await clj-ag)
          ;; All three executed in order
          (is (= ["first" "second" "third"] @order))
          ;; Last output is from the third call
          (is (= "third" (:input @clj-ag)))
          (is (= {:result "third"} (:output @clj-ag)))))
      (.close a))))

(deftest ask-async-exception-test
  (testing "Exception in ask is captured in output as :error"
    (let [a (@#'agent-core/create-agent "u1" "s1" "async-err-1" :config {:name "Err"})
          _ (agent/start-agent a)]
      (with-redefs [agent-core/ask (fn [_ _] (throw (ex-info "boom" {})))]
        (let [clj-ag (agent/ask-async a "fail")]
          (await clj-ag)
          (is (= "boom" (:error (:output @clj-ag))))))
      (.close a)))

  (testing "Clojure agent remains usable after exception"
    (let [a (@#'agent-core/create-agent "u1" "s1" "async-err-2" :config {:name "Err"})
          _ (agent/start-agent a)
          call-count (atom 0)]
      ;; First call throws
      (with-redefs [agent-core/ask (fn [_ _]
                                     (swap! call-count inc)
                                     (throw (ex-info "transient failure" {})))]
        (let [clj-ag (agent/ask-async a "fail")]
          (await clj-ag)
          (is (= "transient failure" (:error (:output @clj-ag))))))

      ;; Second call succeeds — agent is still alive
      (with-redefs [agent-core/ask (fn [_ input] {:answer input})]
        (let [clj-ag (agent/ask-async a "recover")]
          (await clj-ag)
          (is (= {:answer "recover"} (:output @clj-ag)))
          (is (= "recover" (:input @clj-ag)))))
      (.close a)))

  (testing "Error mode :continue keeps agent functional across multiple failures"
    (let [a (@#'agent-core/create-agent "u1" "s1" "async-err-3" :config {:name "Err"})
          _ (agent/start-agent a)]
      (with-redefs [agent-core/ask (fn [_ input]
                                     (if (= input "bad")
                                       (throw (ex-info "bad input" {}))
                                       {:answer input}))]
        (let [clj-ag (agent/ask-async a "bad")]
          (await clj-ag)
          (is (= "bad input" (:error (:output @clj-ag)))))

        (let [clj-ag (agent/ask-async a "good")]
          (await clj-ag)
          (is (= {:answer "good"} (:output @clj-ag))))

        (let [clj-ag (agent/ask-async a "bad")]
          (await clj-ag)
          (is (= "bad input" (:error (:output @clj-ag)))))

        (let [clj-ag (agent/ask-async a "still works")]
          (await clj-ag)
          (is (= {:answer "still works"} (:output @clj-ag)))))
      (.close a))))

;; ============================================================================
;; New Tests: LM Usage Tracking
;; ============================================================================

(deftest session-usage-tracker-test
  (testing "Usage tracker is created on session during create-agent"
    (let [a (@#'agent-core/create-agent "user-1" "s-usage-1" "agent-usage-1"
                                        :config {:name "Usage Agent"})]
      (let [tracker (session/get-session-config @(:!session a) :usage-tracker)]
        (is (some? tracker) "Tracker should be created on session")
        (is (instance? clojure.lang.Atom tracker) "Tracker should be an atom")
        (let [summary (dspy/get-usage-summary tracker)]
          (is (= 0 (get-in summary [:totals :call-count])))
          (is (= 0 (get-in summary [:totals :total-tokens])))
          (is (= 0.0 (get-in summary [:totals :total-cost])))))
      (.close a))))

(deftest sub-agent-shares-tracker-test
  (testing "Sub-agent shares parent's usage tracker via inherited !session"
    (let [parent (@#'agent-core/create-agent "user-1" "s-usage-2" "parent-agent"
                                             :config {:name "Parent"})
          child  (@#'agent-core/create-agent "user-1" "s-usage-2" "child-agent"
                                             :config {:name "Child"}
                                             :parent-agent parent)]
      (let [parent-tracker (session/get-session-config @(:!session parent) :usage-tracker)
            child-tracker  (session/get-session-config @(:!session child) :usage-tracker)]
        (is (some? parent-tracker))
        (is (identical? parent-tracker child-tracker)
            "Sub-agent should share the same tracker instance as parent"))
      (.close child)
      (.close parent))))

(deftest ask-returns-usage-test
  (testing "ask result includes :usage key with correct shape"
    (let [a (@#'agent-core/create-agent "user-1" "s-usage-3" "agent-usage-3"
                                        :config {:name "Usage Ask Agent"})
          _ (agent/start-agent a)
          result (agent/ask a "Hello")]
      ;; No BT → process returns {:result :no-bt ...}
      ;; but usage should still be present since tracker exists
      (is (contains? result :usage) "Result should contain :usage key")
      (is (map? (:usage result)))
      (is (contains? (:usage result) :totals))
      (is (contains? (:usage result) :by-model))
      (is (= 0 (get-in result [:usage :totals :call-count]))
          "No LLM calls made, so call-count should be 0")
      (.close a)))

  (testing "Session config helpers are exported"
    (is (fn? agent/get-session-config))
    (is (fn? agent/set-session-config))))

;; ============================================================================
;; Regression: ask-post fires even on exception
;; ============================================================================

(deftest ask-post-fires-on-exception-regression-test
  (testing "agent.ask/post fires even when proto/process throws (e.g. user-cancel mid-LLM)"
    ;; Without this hook fire, the daemon's status snapshot stays stuck
    ;; at :running forever because daemon-ask-post-handler is the
    ;; mechanism that flips it back to :idle.  See agent.clj catch
    ;; clause + log line "agent-execution-failed (Cancelled)".
    ;;
    ;; We can't mock `proto/process` via `with-redefs` — protocol calls
    ;; on a defrecord dispatch through Java interop, bypassing the var.
    ;; Instead we register a system-command handler that throws; that
    ;; handler is called from inside `proto/process`, so the exception
    ;; surfaces in the same try/catch the BT's `Cancelled` would.
    (let [throwing-cmd (fn [_args _agent]
                         (throw (ex-info "Cancelled"
                                         {:node-type :action
                                          :node-id :test/sentinel})))
          a (@#'agent-core/create-agent "u" "s" "post-on-fail"
                                        :config {:name "Failing Agent"
                                                 :system-commands
                                                 {"boom" throwing-cmd}})
          _ (agent/start-agent a)
          posts (atom [])
          handler-id ::ask-post-fires-on-exception-test
          _ (hooks/register-hook!
             :agent.ask/post handler-id
             (fn [event] (swap! posts conj event)))]
      (try
        (let [result (agent/ask a "/boom")]
          (is (= "Cancelled" (:error result))
              "ask returns the error map"))
        (is (= 1 (count @posts))
            "agent.ask/post fired exactly once even though the system-command threw")
        (let [event (first @posts)]
          (is (= "/boom" (:input event)))
          (is (= "Cancelled" (-> event :result :error))
              "result map carries the error so daemon's ask-post-handler can render it"))
        (finally
          (hooks/unregister-hook! :agent.ask/post handler-id)
          (.close a))))))
