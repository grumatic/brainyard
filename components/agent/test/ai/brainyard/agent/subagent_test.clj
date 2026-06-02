;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.subagent-test
  "Tests for runtime subagent management: depth limits, circular detection, visibility."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.agent :as agent]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn clean-registries [f]
  (let [saved-tools @tool/!tool-defs]
    (try
      (f)
      (finally
        (reset! tool/!tool-defs saved-tools)
        (agent/reset-agent-registry!)))))

(use-fixtures :each clean-registries)

;; ============================================================================
;; Dynamic Var Defaults
;; ============================================================================

(deftest call-depth-defaults-test
  (testing "*call-depth* defaults to 0"
    (is (= 0 proto/*call-depth*)))
  (testing "*call-chain* defaults to empty vector"
    (is (= [] proto/*call-chain*))))

(deftest call-depth-binding-test
  (testing "binding increments depth"
    (binding [proto/*call-depth* (inc proto/*call-depth*)]
      (is (= 1 proto/*call-depth*))
      (binding [proto/*call-depth* (inc proto/*call-depth*)]
        (is (= 2 proto/*call-depth*))))
    ;; unwound
    (is (= 0 proto/*call-depth*))))

(deftest call-chain-binding-test
  (testing "binding accumulates chain"
    (binding [proto/*call-chain* (conj proto/*call-chain* :agent-a)]
      (is (= [:agent-a] proto/*call-chain*))
      (binding [proto/*call-chain* (conj proto/*call-chain* :agent-b)]
        (is (= [:agent-a :agent-b] proto/*call-chain*))))
    ;; unwound
    (is (= [] proto/*call-chain*))))

;; ============================================================================
;; Config Helpers
;; ============================================================================

(deftest config-defaults-test
  (testing "max-agent-call-depth defaults to 3"
    (is (= 3 (config/get-config nil :max-agent-call-depth))))
  (testing "enable-subagent-calls defaults to true"
    (is (true? (config/get-config nil :enable-subagent-calls)))))

(deftest config-override-test
  (testing "per-agent override of max-agent-call-depth"
    (let [agent {:!state (atom {:st-memory-init
                                (atom {:config {:max-agent-call-depth 5}})})}]
      (is (= 5 (config/get-config agent :max-agent-call-depth)))))
  (testing "per-agent override of enable-subagent-calls"
    (let [agent {:!state (atom {:st-memory-init
                                (atom {:config {:enable-subagent-calls false}})})}]
      (is (false? (config/get-config agent :enable-subagent-calls))))))

;; ============================================================================
;; Visibility
;; ============================================================================

(deftest visibility-hidden-test
  (testing "hidden agents are invisible"
    (let [tool-def {:meta {:tool-use-control {:visibility :hidden}}}]
      (is (false? (tool/tool-visible? tool-def :some-agent))))))

(deftest visibility-default-test
  (testing "default (empty) tool-use-control is visible"
    (let [tool-def {:meta {:tool-use-control {}}}]
      (is (true? (tool/tool-visible? tool-def :some-agent))))))

(deftest visibility-allow-glob-test
  (testing "allow pattern matches"
    (let [tool-def {:meta {:tool-use-control {:allow ["react-*"]}}}]
      (is (true? (tool/tool-visible? tool-def :react-agent)))
      (is (false? (tool/tool-visible? tool-def :coact-agent))))))

(deftest visibility-deny-glob-test
  (testing "deny pattern excludes"
    (let [tool-def {:meta {:tool-use-control {:deny ["coact-*"]}}}]
      (is (false? (tool/tool-visible? tool-def :coact-agent)))
      (is (true? (tool/tool-visible? tool-def :react-agent))))))

;; ============================================================================
;; Depth Limit Enforcement
;; ============================================================================

(defn- register-echo-agent!
  "Register a simple echo agent for testing."
  [id]
  (swap! tool/!tool-defs assoc id
         {:id id :type :agent
          :fn (reify clojure.lang.IDeref
                (deref [_]
                  (fn [& {:keys [question]}]
                    {:answer (str "echo: " question)})))
          :meta {:id id :type :agent :description (str "Echo agent " (name id))
                 :tool-use-control {}
                 :input-schema [:map [:question [:string {:desc "Question"}]]]}}))

(defn- make-mock-agent
  "Create a minimal mock agent for binding *current-agent*.

   `st-memory-init-map` may use the new `:config` key (preferred) or the
   legacy `:runtime-config` key — this helper normalizes both into a
   single `:config` map so the agent's overrides reach
   `config/get-config` correctly under the Phase 3 shape."
  [agent-id st-memory-init-map]
  (let [normalized (cond-> st-memory-init-map
                     (and (map? st-memory-init-map)
                          (:runtime-config st-memory-init-map))
                     (-> (assoc :config (merge (:config st-memory-init-map)
                                               (:runtime-config st-memory-init-map)))
                         (dissoc :runtime-config)))
        st-mem-atom (atom normalized)
        ;; Wrap the atom in a map exposing :!state with :st-memory-init —
        ;; matches the shape config/get-config walks via
        ;; (some-> agent :!state deref :st-memory-init deref :config).
        state-atom  (atom {:st-memory-init st-mem-atom})]
    (reify
      proto/IAgent
      (agent-id [_] agent-id)
      (agent-name [_] (name agent-id))
      (agent-description [_] "mock")
      (user-id [_] "mock-user")
      (session-id [_] "mock-session")
      (defagent-type [_] agent-id)
      (process [_ _ _] nil)
      (get-tools [_] nil)
      (get-state [_] {})
      proto/IAgentState
      (get-st-memory-init [_] st-mem-atom)
      proto/IAgentBTIntegration
      (get-bt-st-memory [_] nil)
      clojure.lang.ILookup
      (valAt [_ k] (when (= k :!state) state-atom))
      (valAt [_ k not-found] (if (= k :!state) state-atom not-found)))))

(deftest depth-limit-blocks-deep-calls-test
  (testing "agent call at max depth is rejected"
    (register-echo-agent! :echo-agent)
    (let [mock (make-mock-agent :parent-agent {:runtime-config {:max-agent-call-depth 1}})]
      ;; At depth 1 (= max), calling should be blocked. Pass the mock as
      ;; the explicit caller so do-call-tool--agent can read its
      ;; runtime-config — call-tool no longer auto-picks-up
      ;; proto/*current-agent* when the :agent kwarg is missing.
      (binding [proto/*call-depth* 1
                proto/*call-chain* [:root-agent]]
        (let [result (tool/call-tool "echo-agent" {:question "hello"} :agent mock)]
          (is (some? (:error-message result)))
          (is (re-find #"depth limit" (:error-message result))))))))

(deftest depth-limit-allows-within-budget-test
  (testing "agent call within depth budget succeeds"
    (register-echo-agent! :echo-agent)
    (let [mock (make-mock-agent :caller-agent {:runtime-config {:max-agent-call-depth 3}})]
      ;; At depth 0 (< max 3), calling should succeed
      (binding [proto/*current-agent* mock
                proto/*call-depth* 0
                proto/*call-chain* []]
        (let [result (tool/call-tool "echo-agent" {:question "hello"})]
          ;; The tool/invoke-tool path may not have a running agent instance,
          ;; but it should at least pass the guards (no depth/circular error)
          (is (nil? (when (map? result)
                      (re-find #"depth limit" (str (:error-message result)))))))))))

;; ============================================================================
;; Circular Call Detection
;; ============================================================================

(deftest circular-call-detected-test
  (testing "calling an agent already in the chain is rejected"
    (register-echo-agent! :agent-a)
    (let [mock (make-mock-agent :agent-b {:runtime-config {:max-agent-call-depth 5}})]
      ;; Chain: [:agent-a :agent-b], trying to call :agent-a again
      (binding [proto/*current-agent* mock
                proto/*call-depth* 2
                proto/*call-chain* [:agent-a :agent-b]]
        (let [result (tool/call-tool "agent-a" {:question "hello"})]
          (is (some? (:error-message result)))
          (is (re-find #"[Cc]ircular" (:error-message result))))))))

(deftest no-false-circular-on-independent-chains-test
  (testing "calling an agent NOT in the chain succeeds (no false positive)"
    (register-echo-agent! :agent-d)
    (let [mock (make-mock-agent :agent-b {:runtime-config {:max-agent-call-depth 5}})]
      ;; Chain: [:agent-a :agent-b], calling :agent-d (not in chain) — should pass
      (binding [proto/*current-agent* mock
                proto/*call-depth* 2
                proto/*call-chain* [:agent-a :agent-b]]
        (let [result (tool/call-tool "agent-d" {:question "hello"})]
          (is (nil? (when (map? result)
                      (re-find #"[Cc]ircular" (str (:error-message result)))))))))))

;; ============================================================================
;; Kill Switch
;; ============================================================================

(deftest kill-switch-blocks-all-agent-calls-test
  (testing "enable-subagent-calls=false blocks agent calls"
    (register-echo-agent! :echo-agent)
    (let [mock (make-mock-agent :caller {:runtime-config {:enable-subagent-calls false}})]
      (binding [proto/*call-depth* 0
                proto/*call-chain* []]
        (let [result (tool/call-tool "echo-agent" {:question "hello"} :agent mock)]
          (is (some? (:error-message result)))
          (is (re-find #"disabled" (:error-message result))))))))

;; ============================================================================
;; Commands/Skills bypass agent guards
;; ============================================================================

(deftest command-calls-bypass-agent-guards-test
  (testing "calling a command does not trigger depth/circular checks"
    (tool/defcommand test-echo-cmd
      "Echo command for testing"
      (fn [& {:keys [msg]}] {:result msg})
      :input-schema [:map [:msg [:string {:desc "message"}]]])
    (let [mock (make-mock-agent :caller {:runtime-config {:enable-subagent-calls false
                                                          :max-agent-call-depth 0}})]
      ;; Even with subagent calls disabled and depth=0, commands should work
      (binding [proto/*current-agent* mock
                proto/*call-depth* 99
                proto/*call-chain* [:a :b :c]]
        (let [result (tool/call-tool "test-echo-cmd" {:msg "hello"})]
          (is (= "hello" (:result result))))))))
