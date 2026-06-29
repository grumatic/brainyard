;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.todo-agent-test
  "Tests for todo-agent (v2 — pre/post-flight gated authoring with dossier
   handoff): registration, inherited bt-factory (CoAct), curated agent-tools
   roster (positive + negative assertions), and instruction-content anchors
   that pin the three-phase pipeline contract.

   Helper unit tests for the surviving read seams (todo$read-dossier /
   todo$sync) + checklist edits, the :tags schema extension, the dual-read
   fallback, and the auto-persist hook live in todo_test.clj."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.todo-agent]
            [ai.brainyard.agent.core.tool :as tool]))

;; ============================================================================
;; Registration
;; ============================================================================

(deftest registration-test
  (testing "todo-agent is registered as an agent"
    (let [agent-defs (tool/get-tool-defs :type :agent)]
      (is (contains? agent-defs :todo-agent))
      (let [agent-def (get agent-defs :todo-agent)]
        (is (= :todo-agent (:id agent-def)))
        (is (some? (:fn agent-def)))
        (is (some? (:meta agent-def)))))))

;; ============================================================================
;; Inheritance via run-coact-derived
;; ============================================================================

(deftest inheritance-test
  (require 'ai.brainyard.agent.common.coact-agent)
  (let [todo-def  (get (tool/get-tool-defs :type :agent) :todo-agent)
        coact-def (get (tool/get-tool-defs :type :agent) :coact-agent)]

    (testing "todo-agent's :fn is registered (the wrap-fn invoking run-coact-derived)"
      (is (some? (:fn todo-def))))

    (testing "todo-agent pins :bt-factory explicitly"
      (let [bt-factory (get-in todo-def [:meta :bt-factory])]
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

(defn- todo-tool-ids []
  (let [agent-def   (get (tool/get-tool-defs :type :agent) :todo-agent)
        agent-tools (get-in agent-def [:meta :agent-tools])]
    (set (map (comp :id meta deref) (:tools agent-tools)))))

(deftest agent-tools-test
  (testing "todo-agent :agent-tools includes the doc$* CRUD surface"
    (let [ids (todo-tool-ids)]
      (is (contains? ids :doc$list))
      (is (contains? ids :doc$read))
      (is (contains? ids :doc$create))
      (is (contains? ids :doc$update))
      (is (contains? ids :doc$delete))))

  (testing "todo-agent binds the plan-agent dossier READ seam (only the reader survives)"
    (let [ids (todo-tool-ids)]
      (is (contains? ids :plan$read-dossier)
          "primary pre-flight tool: read plan-agent dossier")
      ;; The plan-agent write-side helper chain is retired (plan-agent now
      ;; authors dossiers via write-file); downstream agents only ever read.
      (is (not (contains? ids :plan$dossier-slug)))
      (is (not (contains? ids :plan$dossier-frontmatter)))
      (is (not (contains? ids :plan$dossier-write)))
      (is (not (contains? ids :plan$dossier-index-append)))
      (is (not (contains? ids :plan$next-handoff)))))

  (testing "todo-agent keeps the todo READ seams + gains file tools (write-side chain retired)"
    (let [ids (todo-tool-ids)]
      ;; Surviving read seams
      (is (contains? ids :todo$read-dossier))
      (is (contains? ids :todo$sync))
      ;; Checklist + dossier authored directly
      (is (contains? ids :write-file))
      (is (contains? ids :update-file))
      ;; Retired write-side dossier chain
      (is (not (contains? ids :todo$dossier-slug)))
      (is (not (contains? ids :todo$dossier-frontmatter)))
      (is (not (contains? ids :todo$dossier-write)))
      (is (not (contains? ids :todo$dossier-index-append)))
      (is (not (contains? ids :todo$next-handoff)))))

  (testing "todo-agent :agent-tools includes reads + probes + sub-LLM"
    (let [ids (todo-tool-ids)]
      (is (contains? ids :read-file))
      (is (contains? ids :grep))
      (is (contains? ids :bash))
      (is (contains? ids :search))

      (is (contains? ids :query$llm))

      (is (contains? ids :list-tools))
      (is (contains? ids :get-tool-info))
      (is (contains? ids :call-tool))

      (is (contains? ids :task$run))

      (is (contains? ids :agent-runtime$config))))

  (testing "todo-agent :agent-tools EXCLUDES recursion + out-of-scope surfaces"
    (let [ids (todo-tool-ids)]
      (is (not (contains? ids :query$clone))
          "query$clone must not be in todo-agent's roster (clone-self forbidden)")

      ;; fetch-url is the only file-tool stripped now — write-file/update-file
      ;; are bound for direct checklist + dossier authoring.
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
  (testing "instruction string contains the cardinal todo-agent v2 anchors"
    (let [agent-def   (get (tool/get-tool-defs :type :agent) :todo-agent)
          instruction (get-in agent-def [:meta :instruction])]
      (is (string? instruction))
      (is (not (str/blank? instruction)))

      ;; Three phases
      (is (str/includes? instruction "PRE-FLIGHT"))
      (is (str/includes? instruction "AUTHOR"))
      (is (str/includes? instruction "POST-FLIGHT"))
      (is (str/includes? instruction "PERSIST"))
      (is (str/includes? instruction "ANSWER"))

      ;; SPAWN vs ADVANCE distinction
      (is (str/includes? instruction "SPAWN"))
      (is (str/includes? instruction "ADVANCE"))

      ;; Pre-flight checklist
      (is (str/includes? instruction "C1"))
      (is (str/includes? instruction "C7"))
      (is (str/includes? instruction "PLAN DOSSIER"))
      (is (str/includes? instruction "PLAN POST-FLIGHT PASSED"))
      (is (str/includes? instruction "GO"))
      (is (str/includes? instruction "GATHER"))
      (is (str/includes? instruction "REFUSE"))

      ;; Post-flight rubric
      (is (str/includes? instruction "R1"))
      (is (str/includes? instruction "R7"))
      (is (str/includes? instruction "PASS"))
      (is (str/includes? instruction "HOLD"))
      (is (str/includes? instruction "ACCEPTANCE COVERED"))

      ;; :tags schema
      (is (str/includes? instruction ":tags"))
      (is (str/includes? instruction ":via"))
      (is (str/includes? instruction "covers:"))

      ;; Storage + handoff contract
      (is (str/includes? instruction ".brainyard/agents/todo-agent/todos/"))
      (is (str/includes? instruction ".brainyard/agents/todo-agent/dossiers/"))
      (is (str/includes? instruction "Saved todo:"))
      (is (str/includes? instruction "Saved dossier:"))

      ;; Hard rules
      (is (str/includes? instruction "HARD RULES"))
      (is (str/includes? instruction "clone-self"))
      (is (str/includes? instruction "Plans are read-only here")
          "todo-agent must not mutate plans")

      ;; v1 narrowing
      (is (or (str/includes? instruction "REVISE auto-round is deferred")
              (str/includes? instruction "v1.5"))
          "instruction notes that REVISE is deferred"))))

(deftest tool-context-content-test
  (testing "tool-context names the doc$* surface, plan helpers (read-only), todo helpers"
    (let [agent-def    (get (tool/get-tool-defs :type :agent) :todo-agent)
          tool-context (get-in agent-def [:meta :tool-context])]
      (is (string? tool-context))
      (is (not (str/blank? tool-context)))

      ;; Todo CRUD via doc$*
      (is (str/includes? tool-context "doc$list"))
      (is (str/includes? tool-context "doc$read"))
      (is (str/includes? tool-context "doc$create"))
      (is (str/includes? tool-context "doc$update"))

      ;; Plan helpers (read-only)
      (is (str/includes? tool-context "plan$read-dossier"))
      (is (str/includes? tool-context "READ-ONLY"))

      ;; Todo read seams documented; write-side chain retired (file-edit path)
      (is (str/includes? tool-context "todo$read-dossier"))
      (is (str/includes? tool-context "todo$sync"))
      (is (str/includes? tool-context "write-file"))
      (is (str/includes? tool-context "update-file"))
      (is (not (str/includes? tool-context "todo$dossier-write")))
      (is (not (str/includes? tool-context "todo$next-handoff")))

      ;; Inline checklist tag format documented
      (is (str/includes? tool-context "via:"))
      (is (str/includes? tool-context "covers:"))

      ;; Sub-LLM
      (is (str/includes? tool-context "query$llm"))

      ;; Cross-agent dispatch
      (is (str/includes? tool-context "plan-agent"))
      (is (str/includes? tool-context "exec-agent"))

      ;; Storage path
      (is (str/includes? tool-context ".brainyard/agents/todo-agent/")))))
