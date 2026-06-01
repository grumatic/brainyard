;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.config-agent-test
  "Structural tests for config-agent (Step D).
   Verifies registration, inherited bt-factory (CoAct), tool roster (positive
   + negative), and instruction-content anchors."
  (:require [ai.brainyard.agent.common.coact-agent]
            [ai.brainyard.agent.common.config-agent]
            [ai.brainyard.agent.core.tool :as tool]
            [clojure.test :refer [deftest is testing]]))

(defn- agent-def []
  (get (tool/get-tool-defs :type :agent) :config-agent))

(defn- tool-ids []
  (set (map (comp :id meta deref)
            (get-in (agent-def) [:meta :agent-tools :tools]))))

(deftest registration-test
  (testing "config-agent is registered in the unified tool registry"
    (let [d (agent-def)]
      (is (some? d))
      (is (= :config-agent (:id d)))
      (is (= :agent (:type d)))
      (is (some? (:fn d))))))

(deftest inheritance-test
  (testing ":bt-factory is pinned (so setup-agent-by-id resolves the CoAct BT)"
    (is (fn? (get-in (agent-def) [:meta :bt-factory])))))

(deftest agent-tools-positive
  (testing "config-agent's :agent-tools includes the new config$* surface"
    (let [ids (tool-ids)]
      (is (contains? ids :config$read))
      (is (contains? ids :config$diff))
      (is (contains? ids :config$apply))
      (is (contains? ids :config$snapshot))
      (is (contains? ids :config$list-snapshots))
      (is (contains? ids :config$revert))
      (is (contains? ids :env-detect$rescan))
      (is (contains? ids :bootstrap$re-run-rung))))

  (testing "inherits the existing runtime + MCP + query$llm tools"
    (let [ids (tool-ids)]
      (is (contains? ids :agent-runtime$config))
      (is (contains? ids :mcp$server))
      (is (contains? ids :mcp$tools))
      (is (contains? ids :query$llm)))))

(deftest agent-tools-negative
  (testing "config-agent does NOT include query$clone (clone-self recursion)"
    (is (not (contains? (tool-ids) :query$clone)))))

(deftest instruction-anchors
  (testing "instruction names the right tools + redirects"
    (let [instr (get-in (agent-def) [:meta :instruction])]
      (is (re-find #"bootstrap\$re-run-rung"  instr))
      (is (re-find #"ALLOWLIST"               instr))
      (is (re-find #"READ.*PROPOSE.*DIFF.*CONFIRM.*APPLY" instr))
      (is (re-find #"FIVE CAPABILITY KINDS"   instr))
      (is (re-find #"FINAL-STEP CHECKLIST"    instr)))))

(deftest tool-context-anchors
  (testing "tool-context names the polymorphic commands the agent uses"
    (let [tc (get-in (agent-def) [:meta :tool-context])]
      (is (re-find #"config\$apply"            tc))
      (is (re-find #"bootstrap\$re-run-rung"   tc))
      (is (re-find #"agent-runtime\$config"    tc))
      (is (re-find #"mcp\$server"              tc))
      (is (re-find #"call-tool.*mcp-agent"     tc))
      (is (re-find #"FORBIDDEN"                tc)))))
