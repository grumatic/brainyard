;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.exec-agent-test
  "Tests for exec-agent (v2 — pre/post-flight gated execution with per-item
   routing & dossier evidence): registration, inherited bt-factory (CoAct),
   curated agent-tools roster (positive + negative assertions — write
   delegation hard rule enforced), and instruction-content anchors that pin
   the three-phase pipeline contract.

   Helper unit tests for the new exec$* commands and the auto-persist hook
   live in exec_test.clj."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.exec-agent]
            [ai.brainyard.agent.core.tool :as tool]))

;; ============================================================================
;; Registration
;; ============================================================================

(deftest registration-test
  (testing "exec-agent is registered as an agent"
    (let [agent-defs (tool/get-tool-defs :type :agent)]
      (is (contains? agent-defs :exec-agent))
      (let [agent-def (get agent-defs :exec-agent)]
        (is (= :exec-agent (:id agent-def)))
        (is (some? (:fn agent-def)))
        (is (some? (:meta agent-def)))))))

;; ============================================================================
;; Inheritance via run-coact-derived
;; ============================================================================

(deftest inheritance-test
  (require 'ai.brainyard.agent.common.coact-agent)
  (let [exec-def  (get (tool/get-tool-defs :type :agent) :exec-agent)
        coact-def (get (tool/get-tool-defs :type :agent) :coact-agent)]

    (testing "exec-agent's :fn is registered (the wrap-fn invoking run-coact-derived)"
      (is (some? (:fn exec-def))))

    (testing "exec-agent pins :bt-factory explicitly"
      (let [bt-factory (get-in exec-def [:meta :bt-factory])]
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
;; Agent tools — positive + negative
;; ============================================================================

(defn- exec-tool-ids []
  (let [agent-def   (get (tool/get-tool-defs :type :agent) :exec-agent)
        agent-tools (get-in agent-def [:meta :agent-tools])]
    (set (map (comp :id meta deref) (:tools agent-tools)))))

(deftest agent-tools-test
  (testing "exec-agent :agent-tools includes the doc$* surface (limited write)"
    (let [ids (exec-tool-ids)]
      (is (contains? ids :doc$list))
      (is (contains? ids :doc$read))
      (is (contains? ids :doc$update))))

  (testing "exec-agent :agent-tools includes plan + todo dossier helpers (read-only)"
    (let [ids (exec-tool-ids)]
      ;; Plan helper (for C3) — only the read seam survives the plan-agent
      ;; lightweight redesign (write-side chain retired).
      (is (contains? ids :plan$read-dossier))
      (is (not (contains? ids :plan$dossier-slug)))
      (is (not (contains? ids :plan$next-handoff)))
      ;; Todo helpers (for C1/C2)
      (is (contains? ids :todo$read-dossier))
      (is (contains? ids :todo$dossier-slug))
      (is (contains? ids :todo$next-handoff))))

  (testing "exec-agent :agent-tools includes the new exec dossier helpers"
    (let [ids (exec-tool-ids)]
      (is (contains? ids :exec$dossier-slug))
      (is (contains? ids :exec$dossier-frontmatter))
      (is (contains? ids :exec$dossier-write))
      (is (contains? ids :exec$dossier-index-append))
      (is (contains? ids :exec$read-dossier))
      (is (contains? ids :exec$find))
      (is (contains? ids :exec$next-handoff))))

  (testing "exec-agent :agent-tools includes reads + probes + MCP read-routing"
    (let [ids (exec-tool-ids)]
      ;; Reads + probes
      (is (contains? ids :read-file))
      (is (contains? ids :grep))
      (is (contains? ids :bash))
      (is (contains? ids :search))

      ;; MCP for :via :mcp routing (read-only proceeds; write-side surfaces)
      (is (contains? ids :mcp$server))
      (is (contains? ids :mcp$tools))
      (is (contains? ids :mcp$lifecycle))

      ;; Sub-LLM
      (is (contains? ids :query$llm))

      ;; Bookkeeping + cross-agent dispatch
      (is (contains? ids :list-tools))
      (is (contains? ids :get-tool-info))
      (is (contains? ids :call-tool))

      (is (contains? ids :task$run))

      (is (contains? ids :agent-runtime$config))))

  (testing "exec-agent :agent-tools EXCLUDES write-side tools (HARD RULE: delegate to update-agent)"
    (let [ids (exec-tool-ids)]
      (is (not (contains? ids :write-file))
          "write-file is excluded — exec-agent delegates writes to update-agent")
      (is (not (contains? ids :update-file))
          "update-file is excluded — exec-agent delegates writes to update-agent")))

  (testing "exec-agent :agent-tools EXCLUDES web/skills/clone-self"
    (let [ids (exec-tool-ids)]
      (is (not (contains? ids :query$clone))
          "query$clone must not be in exec-agent's roster (clone-self forbidden)")
      (is (not (contains? ids :fetch-url)))
      (is (not (contains? ids :web-search)))
      (is (not (contains? ids :skills$list)))
      (is (not (contains? ids :skills$find)))
      (is (not (contains? ids :skills$read))))))

;; ============================================================================
;; Instruction & tool-context content anchors
;; ============================================================================

(deftest instruction-content-test
  (testing "instruction string contains the cardinal exec-agent v2 anchors"
    (let [agent-def   (get (tool/get-tool-defs :type :agent) :exec-agent)
          instruction (get-in agent-def [:meta :instruction])]
      (is (string? instruction))
      (is (not (str/blank? instruction)))

      ;; Three phases
      (is (str/includes? instruction "PRE-FLIGHT"))
      (is (str/includes? instruction "EXECUTE"))
      (is (str/includes? instruction "POST-FLIGHT"))
      (is (str/includes? instruction "PERSIST"))
      (is (str/includes? instruction "ANSWER"))

      ;; Pre-flight checklist
      (is (str/includes? instruction "C1"))
      (is (str/includes? instruction "C8"))
      (is (str/includes? instruction "TODO DOSSIER"))
      (is (str/includes? instruction "PLAN POST-FLIGHT PASSED"))
      (is (str/includes? instruction "WORKING TREE"))
      (is (str/includes? instruction "GO"))
      (is (str/includes? instruction "GATHER"))
      (is (str/includes? instruction "REFUSE"))

      ;; Per-item routing
      (is (str/includes? instruction ":update-agent"))
      (is (str/includes? instruction ":bash"))
      (is (str/includes? instruction ":mcp"))
      (is (str/includes? instruction ":explore-agent"))
      (is (str/includes? instruction ":read-only"))
      (is (str/includes? instruction ":manual"))

      ;; Post-flight rubric
      (is (str/includes? instruction "R1"))
      (is (str/includes? instruction "R7"))
      (is (str/includes? instruction "EVIDENCE PRESENT"))
      (is (str/includes? instruction "DIFF MATCH"))
      (is (str/includes? instruction "PASS"))
      (is (str/includes? instruction "HOLD"))

      ;; Storage + handoff contract
      (is (str/includes? instruction ".brainyard/agents/exec-agent/dossiers/"))
      (is (str/includes? instruction "Saved dossier:"))
      (is (str/includes? instruction "Done:"))
      (is (str/includes? instruction "Manual:"))

      ;; Hard rules
      (is (str/includes? instruction "HARD RULES"))
      (is (str/includes? instruction "delegated to update-agent")
          "Hard Rule 1: writes go through update-agent, not direct write-file/update-file")
      (is (str/includes? instruction "clone-self"))

      ;; v1 narrowing
      (is (or (str/includes? instruction "REVISE auto-retry is deferred")
              (str/includes? instruction "v1.5"))
          "instruction notes that REVISE is deferred"))))

(deftest tool-context-content-test
  (testing "tool-context names the doc$* surface, dossier helpers, and cross-agent dispatch"
    (let [agent-def    (get (tool/get-tool-defs :type :agent) :exec-agent)
          tool-context (get-in agent-def [:meta :tool-context])]
      (is (string? tool-context))
      (is (not (str/blank? tool-context)))

      ;; Todo CRUD
      (is (str/includes? tool-context "doc$update"))
      (is (str/includes? tool-context "doc$read"))

      ;; Plan / todo helpers (read-only)
      (is (str/includes? tool-context "plan$read-dossier"))
      (is (str/includes? tool-context "todo$read-dossier"))
      (is (str/includes? tool-context "READ-ONLY"))

      ;; Exec dossier helpers (all seven)
      (is (str/includes? tool-context "exec$dossier-slug"))
      (is (str/includes? tool-context "exec$dossier-frontmatter"))
      (is (str/includes? tool-context "exec$dossier-write"))
      (is (str/includes? tool-context "exec$dossier-index-append"))
      (is (str/includes? tool-context "exec$read-dossier"))
      (is (str/includes? tool-context "exec$find"))
      (is (str/includes? tool-context "exec$next-handoff"))

      ;; Cross-agent dispatch (the key delegation rule)
      (is (str/includes? tool-context "update-agent"))
      (is (str/includes? tool-context "explore-agent"))
      (is (str/includes? tool-context "mcp$tools"))

      ;; Sub-LLM
      (is (str/includes? tool-context "query$llm"))

      ;; Storage path
      (is (str/includes? tool-context ".brainyard/agents/exec-agent/")))))
