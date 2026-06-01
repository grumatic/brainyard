;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.plan-agent-test
  "Tests for plan-agent (v2 — pre/post-flight gated authoring with dossier
   handoff): registration, inherited bt-factory (CoAct), curated agent-tools
   roster (positive + negative assertions), and instruction-content anchors
   that pin the three-phase pipeline contract.

   Helper unit tests for the new plan$dossier-* / plan$read-dossier /
   plan$next-handoff commands live in plan_test.clj."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.plan-agent]
            [ai.brainyard.agent.core.tool :as tool]))

;; ============================================================================
;; Registration
;; ============================================================================

(deftest registration-test
  (testing "plan-agent is registered as an agent"
    (let [agent-defs (tool/get-tool-defs :type :agent)]
      (is (contains? agent-defs :plan-agent))
      (let [agent-def (get agent-defs :plan-agent)]
        (is (= :plan-agent (:id agent-def)))
        (is (some? (:fn agent-def)))
        (is (some? (:meta agent-def)))))))

;; ============================================================================
;; Inheritance via run-coact-derived
;;
;; plan-agent pins :bt-factory explicitly (mirroring update-agent / explore-
;; agent / rlm-agent) so direct entry points (e.g. setup-agent-by-id used by
;; `bb tui ask`) that resolve agent metadata without going through
;; run-coact-derived still pick up the correct CoAct BT.
;; ============================================================================

(deftest inheritance-test
  (require 'ai.brainyard.agent.common.coact-agent)
  (let [plan-def  (get (tool/get-tool-defs :type :agent) :plan-agent)
        coact-def (get (tool/get-tool-defs :type :agent) :coact-agent)]

    (testing "plan-agent's :fn is registered (the wrap-fn invoking run-coact-derived)"
      (is (some? (:fn plan-def))))

    (testing "plan-agent pins :bt-factory explicitly"
      (let [bt-factory (get-in plan-def [:meta :bt-factory])]
        (is (fn? bt-factory))
        (let [bt (bt-factory {:max-iterations 3})]
          (is (vector? bt))
          (is (= :sequence (first bt)))
          (is (= "coact.sequence" (namespace (get-in bt [1 :id])))))))

    (testing "coact-agent (the parent) has the same bt-factory shape"
      (let [bt-factory (get-in coact-def [:meta :bt-factory])
            bt (bt-factory {:max-iterations 3})]
        (is (= :sequence (first bt)))
        (is (= "coact.sequence" (namespace (get-in bt [1 :id]))))))))

;; ============================================================================
;; Agent tools — positive + negative assertions
;; ============================================================================

(defn- plan-tool-ids []
  (let [agent-def   (get (tool/get-tool-defs :type :agent) :plan-agent)
        agent-tools (get-in agent-def [:meta :agent-tools])]
    (set (map (comp :id meta deref) (:tools agent-tools)))))

(deftest agent-tools-test
  (testing "plan-agent :agent-tools includes the doc$* CRUD surface"
    (let [ids (plan-tool-ids)]
      (is (contains? ids :doc$list))
      (is (contains? ids :doc$read))
      (is (contains? ids :doc$create))
      (is (contains? ids :doc$update))
      (is (contains? ids :doc$delete))))

  (testing "plan-agent :agent-tools includes the new dossier helpers"
    (let [ids (plan-tool-ids)]
      (is (contains? ids :plan$dossier-slug))
      (is (contains? ids :plan$dossier-frontmatter))
      (is (contains? ids :plan$dossier-write))
      (is (contains? ids :plan$dossier-index-append))
      (is (contains? ids :plan$read-dossier))
      (is (contains? ids :plan$next-handoff))))

  (testing "plan-agent :agent-tools includes reads + probes + sub-LLM"
    (let [ids (plan-tool-ids)]
      ;; Reads + probes — for C4/R3 (test -f), R7 (grep), context inspection
      (is (contains? ids :read-file))
      (is (contains? ids :grep))
      (is (contains? ids :bash))
      (is (contains? ids :search))

      ;; Sub-LLM — heavy use in POST-FLIGHT R1/R2/R6
      (is (contains? ids :query$llm))

      ;; Bookkeeping + cross-agent dispatch via call-tool
      (is (contains? ids :list-tools))
      (is (contains? ids :get-tool-info))
      (is (contains? ids :call-tool))

      ;; Background fan-out
      (is (contains? ids :task$run))

      ;; Runtime config
      (is (contains? ids :agent-runtime$config))))

  (testing "plan-agent :agent-tools EXCLUDES write-side + cross-agent recursion"
    (let [ids (plan-tool-ids)]
      ;; Hard Rule — no clone-self
      (is (not (contains? ids :query$clone))
          "query$clone must not be in plan-agent's roster (clone-self forbidden)")

      ;; Plan-agent writes ONLY through doc$create + plan$dossier-write —
      ;; never directly via the file-system write tools.
      (is (not (contains? ids :write-file))
          "write-file is excluded — plan-agent writes only through doc$create + plan$dossier-write")
      (is (not (contains? ids :update-file))
          "update-file is excluded — plan-agent does not edit arbitrary files")

      ;; Web / fetch / MCP / skills surfaces all live in explore-agent.
      (is (not (contains? ids :fetch-url)))
      (is (not (contains? ids :web-search)))
      (is (not (contains? ids :mcp$server)))
      (is (not (contains? ids :mcp$tools)))
      (is (not (contains? ids :mcp$lifecycle)))
      (is (not (contains? ids :skills$list)))
      (is (not (contains? ids :skills$find)))
      (is (not (contains? ids :skills$read))))))

;; ============================================================================
;; Instruction & tool-context content anchors
;; ============================================================================

(deftest instruction-content-test
  (testing "instruction string contains the cardinal plan-agent v2 anchors"
    (let [agent-def   (get (tool/get-tool-defs :type :agent) :plan-agent)
          instruction (get-in agent-def [:meta :instruction])]
      (is (string? instruction))
      (is (not (str/blank? instruction)))

      ;; Three phases
      (is (str/includes? instruction "PRE-FLIGHT"))
      (is (str/includes? instruction "AUTHOR"))
      (is (str/includes? instruction "POST-FLIGHT"))
      (is (str/includes? instruction "PERSIST"))
      (is (str/includes? instruction "ANSWER"))

      ;; Pre-flight checklist
      (is (str/includes? instruction "C1"))
      (is (str/includes? instruction "C7"))
      (is (str/includes? instruction "GO"))
      (is (str/includes? instruction "GATHER"))
      (is (str/includes? instruction "REFUSE"))

      ;; Post-flight rubric
      (is (str/includes? instruction "R1"))
      (is (str/includes? instruction "R7"))
      (is (str/includes? instruction "PASS"))
      (is (str/includes? instruction "HOLD"))

      ;; Storage + handoff contract
      (is (str/includes? instruction ".brainyard/agents/plan-agent/plans/"))
      (is (str/includes? instruction ".brainyard/agents/plan-agent/dossiers/"))
      (is (str/includes? instruction "Saved plan:"))
      (is (str/includes? instruction "Saved dossier:"))

      ;; Hard rules
      (is (str/includes? instruction "HARD RULES"))
      (is (str/includes? instruction "clone-self"))
      (is (str/includes? instruction "assumptions"))

      ;; v1 narrowing — REVISE deferred, only PASS/HOLD shipped
      (is (or (str/includes? instruction "REVISE auto-round deferred")
              (str/includes? instruction "v1.5"))
          "instruction notes that REVISE is out of scope for v1"))))

(deftest tool-context-content-test
  (testing "tool-context names the modern doc$* surface and the new helpers"
    (let [agent-def    (get (tool/get-tool-defs :type :agent) :plan-agent)
          tool-context (get-in agent-def [:meta :tool-context])]
      (is (string? tool-context))
      (is (not (str/blank? tool-context)))

      ;; Plan CRUD via doc$*
      (is (str/includes? tool-context "doc$list"))
      (is (str/includes? tool-context "doc$read"))
      (is (str/includes? tool-context "doc$create"))
      (is (str/includes? tool-context "doc$update"))
      (is (str/includes? tool-context "doc$delete"))

      ;; New dossier helpers (all six)
      (is (str/includes? tool-context "plan$dossier-slug"))
      (is (str/includes? tool-context "plan$dossier-frontmatter"))
      (is (str/includes? tool-context "plan$dossier-write"))
      (is (str/includes? tool-context "plan$dossier-index-append"))
      (is (str/includes? tool-context "plan$read-dossier"))
      (is (str/includes? tool-context "plan$next-handoff"))

      ;; Probes + sub-LLM
      (is (str/includes? tool-context "bash \"test -f"))
      (is (str/includes? tool-context "query$llm"))

      ;; Cross-agent dispatch (no recursion)
      (is (str/includes? tool-context "explore-agent"))
      (is (str/includes? tool-context "todo-agent"))

      ;; Storage path
      (is (str/includes? tool-context ".brainyard/agents/plan-agent/")))))
