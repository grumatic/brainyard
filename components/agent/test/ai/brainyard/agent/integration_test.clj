;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.integration-test
  "End-to-end integration tests for the react-agent and coact-agent.

   These tests issue real LLM calls and exercise the full agent loop:
   setup → ask → tool invocation → answer.

   They are gated by the BRAINYARD_RUN_INTEGRATION env var so they do
   not run during `clojure -M:test` by default. To run them:

     BRAINYARD_RUN_INTEGRATION=1 clojure -M:test \\
       -e \"(require 'clojure.test 'ai.brainyard.agent.integration-test)
            (clojure.test/run-tests 'ai.brainyard.agent.integration-test)
            (shutdown-agents)\"

   When the env var is unset (or no LM is configured) each test prints a
   skip notice and reports a single passing assertion."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.clj-llm.interface :as llm]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.core.agent :as agent]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.common.tools]
            [ai.brainyard.agent.common.commands]
            [ai.brainyard.agent.task.commands]
            [ai.brainyard.agent.common.react-agent]
            [ai.brainyard.agent.common.coact-agent]
            [ai.brainyard.agent.common.explore-agent]))

;; ============================================================================
;; Test Gating
;; ============================================================================

(defn- run-integration?
  "Skip integration tests unless BRAINYARD_RUN_INTEGRATION is truthy AND a
   default LM is configured. Returns the LM map when ready, nil otherwise."
  []
  (when (System/getenv "BRAINYARD_RUN_INTEGRATION")
    (try (llm/get-default-lm) (catch Throwable _ nil))))

(defn- skip-notice [test-name]
  (println (str "[SKIP] " test-name
                " — set BRAINYARD_RUN_INTEGRATION=1 and configure default LM "
                "(e.g. (llm/configure-default-lm! ...)) to run this test.")))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- build-agent
  "Create a fresh agent of the given id with shared user/session, no caching."
  [agent-id session-id & {:keys [max-iterations] :or {max-iterations 5}}]
  (reset! @#'ai.brainyard.agent.core.agent/!agent-registry {})
  (let [meta (-> (tool/get-tool-defs :id agent-id) :meta)]
    (apply agent/setup-agent
           (mapcat identity
                   (merge meta
                          {:agent-session  {:user-id "integration-test"
                                            :session-id session-id}
                           :id             agent-id
                           :max-iterations max-iterations
                           :question       "PLACEHOLDER"})))))

(defn- bt-state [ag]
  (some-> (proto/get-bt-st-memory ag) deref))

(defn- safe-close [ag]
  (try (.close ag) (catch Throwable _ nil)))

;; ============================================================================
;; Test 1 — react-agent end-to-end
;; ============================================================================

(deftest ^:integration react-agent-end-to-end-test
  (testing "react-agent invokes list-tools then a domain command and answers"
    (if-let [lm (run-integration?)]
      (let [_ (println (str "[INFO] react-agent integration: provider="
                            (:provider lm) " model=" (:model lm)))
            ag (build-agent :react-agent "react-int-1" :max-iterations 5)]
        (try
          (let [result (agent/ask ag
                                  (str "Step 1: call list-tools with type=\"command\" and pattern=\"task\" "
                                       "to find task-related commands. "
                                       "Step 2: call task$list with no arguments. "
                                       "Step 3: in one short sentence, summarize what you found."))
                state (bt-state ag)
                tool-results (:tool-results state)
                tool-names   (set (map :tool-name tool-results))
                answer       (str (:answer result))]
            (is (= :success (:result result))
                "react-agent should report :success status")
            (is (true? (:goal-achieved state))
                "react-agent should mark goal-achieved=true")
            (is (pos? (:iteration-count state 0))
                "react-agent should run at least one iteration")
            (is (contains? tool-names "list-tools")
                (str "Expected list-tools in tool-results, got " tool-names))
            (is (contains? tool-names "task$list")
                (str "Expected task$list in tool-results, got " tool-names))
            (is (not (str/blank? answer))
                "react-agent should produce a non-blank answer")
            (is (re-find #"task\$\w+" answer)
                (str "Answer should mention task$* commands, got: "
                     (subs answer 0 (min 200 (count answer))))))
          (finally (safe-close ag))))
      (do (skip-notice "react-agent-end-to-end-test") (is true)))))

;; ============================================================================
;; Test 2 — coact-agent end-to-end
;; ============================================================================

(deftest ^:integration coact-agent-end-to-end-test
  (testing "coact-agent runs sandbox iterations and produces an answer"
    (if-let [lm (run-integration?)]
      (let [_ (println (str "[INFO] coact-agent integration: provider="
                            (:provider lm) " model=" (:model lm)))
            ag (build-agent :coact-agent "coact-int-1" :max-iterations 5)]
        (try
          (let [result (agent/ask ag
                                  (str "Use the sandbox to compute the value of "
                                       "(reduce + (range 1 11)) and return ONLY the number."))
                answer (str (:answer result))]
            (is (= :success (:result result))
                "coact-agent should report :success status")
            (is (not (str/blank? answer))
                "coact-agent should produce a non-blank answer")
            (is (re-find #"\b55\b" answer)
                (str "Expected '55' in answer for sum(1..10), got: "
                     (subs answer 0 (min 200 (count answer))))))
          (finally (safe-close ag))))
      (do (skip-notice "coact-agent-end-to-end-test") (is true)))))

;; ============================================================================
;; Test 3 — react-agent reaches list-tools via discovery prompt
;; ============================================================================

(deftest ^:integration react-agent-discovery-test
  (testing "react-agent uses list-tools when the bound toolbox is unfamiliar"
    (if-let [_lm (run-integration?)]
      (let [ag (build-agent :react-agent "react-int-2" :max-iterations 4)]
        (try
          (let [result (agent/ask ag
                                  "What commands are registered in the system that begin with task$? Use list-tools to find out.")
                state (bt-state ag)
                tool-results (:tool-results state)
                tool-names   (set (map :tool-name tool-results))
                answer       (str (:answer result))]
            (is (= :success (:result result)))
            (is (contains? tool-names "list-tools")
                (str "Expected list-tools call, got " tool-names))
            (is (re-find #"task\$" answer)
                (str "Answer should mention at least one task$* command, got: "
                     (subs answer 0 (min 200 (count answer))))))
          (finally (safe-close ag))))
      (do (skip-notice "react-agent-discovery-test") (is true)))))

;; ============================================================================
;; Test 4 — explore-agent end-to-end (filesystem surface)
;; ============================================================================

(deftest ^:integration explore-agent-end-to-end-test
  (testing "explore-agent answers a project-local question using filesystem tools"
    (if-let [lm (run-integration?)]
      (let [_ (println (str "[INFO] explore-agent integration: provider="
                            (:provider lm) " model=" (:model lm)))
            ag (build-agent :explore-agent "explore-int-1" :max-iterations 5)]
        (try
          (let [result (agent/ask ag
                                  (str "In this repository, where is the `bind-tools` function defined? "
                                       "Use grep (or `bash` with `find`) to locate it, then report the file:line."))
                state (bt-state ag)
                tool-results (:tool-results state)
                tool-names   (set (map :tool-name tool-results))
                answer       (str (:answer result))]
            (is (= :success (:result result))
                "explore-agent should report :success status")
            (is (or (contains? tool-names "grep")
                    (contains? tool-names "bash")
                    (contains? tool-names "search"))
                (str "Expected grep/bash/search usage, got " tool-names))
            (is (re-find #"tool\.clj" answer)
                (str "Answer should reference tool.clj, got: "
                     (subs answer 0 (min 200 (count answer))))))
          (finally (safe-close ag))))
      (do (skip-notice "explore-agent-end-to-end-test") (is true)))))
