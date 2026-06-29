;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.tool-agent-test
  "Structural tests for tool-agent.
   Verifies registration, inherited bt-factory (CoAct), tool roster (positive
   + negative), and instruction-content anchors. Mirrors config-agent-test."
  (:require [ai.brainyard.agent.common.coact-agent]
            [ai.brainyard.agent.common.tool-agent]
            [ai.brainyard.agent.core.tool :as tool]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- agent-def []
  (get (tool/get-tool-defs :type :agent) :tool-agent))

(defn- tool-ids []
  (set (map (comp :id meta deref)
            (get-in (agent-def) [:meta :agent-tools :tools]))))

(deftest registration-test
  (testing "tool-agent is registered in the unified tool registry"
    (let [d (agent-def)]
      (is (some? d))
      (is (= :tool-agent (:id d)))
      (is (= :agent (:type d)))
      (is (some? (:fn d))))))

(deftest inheritance-test
  (testing ":bt-factory is pinned (so setup-agent-by-id resolves the CoAct BT)"
    (is (fn? (get-in (agent-def) [:meta :bt-factory])))))

(deftest agent-tools-positive
  (testing "tool-agent's roster is exactly the tool-agent$* lifecycle surface"
    (let [ids (tool-ids)]
      (is (contains? ids :tool-agent$create))
      (is (contains? ids :tool-agent$validate))
      (is (contains? ids :tool-agent$list))
      (is (contains? ids :tool-agent$read))
      (is (contains? ids :tool-agent$delete)))))

(deftest agent-tools-negative
  (testing "tool-agent does NOT carry sibling-specialist write surfaces"
    (let [ids (tool-ids)]
      (is (not (contains? ids :skills$write)))
      (is (not (contains? ids :plan$update-body)))
      (is (not (contains? ids :config$apply))))))

(deftest instruction-anchors
  (testing "instruction encodes the validate-before-create + verify discipline"
    (let [instr @#'ai.brainyard.agent.common.tool-agent/instruction]
      (is (str/includes? instr "tool-agent$validate"))
      (is (str/includes? instr "tool-agent$create"))
      (is (str/includes? instr "user$tool$<name>"))
      (is (str/includes? instr ".brainyard/tools"))
      ;; validate-before-create + verify-after-create are HARD RULES (codified)
      (is (str/includes? instr "HARD RULE"))
      (is (str/includes? instr "VALIDATE BEFORE CREATE"))
      (is (str/includes? instr "VERIFY AFTER CREATE"))
      ;; optional file-first authoring note
      (is (str/includes? instr "FILE-FIRST")))))
