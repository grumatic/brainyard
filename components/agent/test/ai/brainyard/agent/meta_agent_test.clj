;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.meta-agent-test
  "Structural tests for meta-agent.
   Verifies registration, inherited bt-factory (CoAct), tool roster (positive
   + negative), and instruction-content anchors. Mirrors tool-agent-test."
  (:require [ai.brainyard.agent.common.coact-agent]
            [ai.brainyard.agent.common.meta-agent]
            [ai.brainyard.agent.core.tool :as tool]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- agent-def []
  (get (tool/get-tool-defs :type :agent) :meta-agent))

(defn- tool-ids []
  (set (map (comp :id meta deref)
            (get-in (agent-def) [:meta :agent-tools :tools]))))

(deftest registration-test
  (testing "meta-agent is registered in the unified tool registry"
    (let [d (agent-def)]
      (is (some? d))
      (is (= :meta-agent (:id d)))
      (is (= :agent (:type d)))
      (is (some? (:fn d))))))

(deftest inheritance-test
  (testing ":bt-factory is pinned (so setup-agent-by-id resolves the CoAct BT)"
    (is (fn? (get-in (agent-def) [:meta :bt-factory])))))

(deftest agent-tools-positive
  (testing "meta-agent's roster is exactly the meta-agent$* lifecycle surface"
    (let [ids (tool-ids)]
      (is (contains? ids :meta-agent$create))
      (is (contains? ids :meta-agent$validate))
      (is (contains? ids :meta-agent$list))
      (is (contains? ids :meta-agent$read))
      (is (contains? ids :meta-agent$delete)))))

(deftest agent-tools-negative
  (testing "meta-agent does NOT carry sibling-specialist write surfaces"
    (let [ids (tool-ids)]
      (is (not (contains? ids :tool-agent$create)))
      (is (not (contains? ids :hook-agent$create)))
      (is (not (contains? ids :skills$write)))
      (is (not (contains? ids :config$apply))))))

(deftest instruction-anchors
  (testing "instruction encodes the instruction-first + validate-before-create discipline"
    (let [instr @#'ai.brainyard.agent.common.meta-agent/instruction]
      (is (str/includes? instr "meta-agent$validate"))
      (is (str/includes? instr "meta-agent$create"))
      (is (str/includes? instr "user$agent$<name>"))
      (is (str/includes? instr ".brainyard/agents/user$agent"))
      ;; the central design rule: never bind tools
      (is (str/includes? instr "NEVER bind tools"))
      ;; validate-before-create + verify-after-create are HARD RULES (codified)
      (is (str/includes? instr "HARD RULE"))
      (is (str/includes? instr "VALIDATE BEFORE CREATE"))
      (is (str/includes? instr "VERIFY AFTER CREATE"))
      ;; optional file-first authoring note
      (is (str/includes? instr "FILE-FIRST")))))
