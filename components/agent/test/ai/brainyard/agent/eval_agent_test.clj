;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.eval-agent-test
  "Tests for eval-agent (v2 — pre/post-flight gated verdict production with
   dossier handoff): registration, inherited bt-factory (CoAct), curated
   agent-tools roster (positive + negative assertions — read-only enforced;
   `edit$read-record` cherry-picked from update-helpers, write-side update
   helpers excluded), and instruction-content anchors that pin the
   three-phase pipeline contract.

   Helper unit tests for the new eval$* commands and the auto-persist hook
   live in eval_test.clj."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.eval-agent]
            [ai.brainyard.agent.core.tool :as tool]))

;; ============================================================================
;; Registration
;; ============================================================================

(deftest registration-test
  (testing "eval-agent is registered as an agent"
    (let [agent-defs (tool/get-tool-defs :type :agent)]
      (is (contains? agent-defs :eval-agent))
      (let [agent-def (get agent-defs :eval-agent)]
        (is (= :eval-agent (:id agent-def)))
        (is (some? (:fn agent-def)))
        (is (some? (:meta agent-def)))))))

;; ============================================================================
;; Inheritance via run-coact-derived
;; ============================================================================

(deftest inheritance-test
  (require 'ai.brainyard.agent.common.coact-agent)
  (let [eval-def  (get (tool/get-tool-defs :type :agent) :eval-agent)
        coact-def (get (tool/get-tool-defs :type :agent) :coact-agent)]

    (testing "eval-agent's :fn is registered (the wrap-fn invoking run-coact-derived)"
      (is (some? (:fn eval-def))))

    (testing "eval-agent pins :bt-factory explicitly"
      (let [bt-factory (get-in eval-def [:meta :bt-factory])]
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

(defn- eval-tool-ids []
  (let [agent-def   (get (tool/get-tool-defs :type :agent) :eval-agent)
        agent-tools (get-in agent-def [:meta :agent-tools])]
    (set (map (comp :id meta deref) (:tools agent-tools)))))

(deftest agent-tools-test
  (testing "eval-agent :agent-tools includes plan + todo + exec dossier-helpers (READ-ONLY — first agent reading three upstream)"
    (let [ids (eval-tool-ids)]
      (is (contains? ids :plan$read-dossier))
      (is (contains? ids :todo$read-dossier))
      (is (contains? ids :exec$read-dossier))))

  (testing "eval-agent :agent-tools includes edit$read-record (cherry-picked drill-down)"
    (let [ids (eval-tool-ids)]
      (is (contains? ids :edit$read-record)
          "edit$read-record is the only update-helper bound — eval drills from criterion → item → evidence → diff")))

  (testing "eval-agent keeps the eval READ seams (write-side chain retired)"
    (let [ids (eval-tool-ids)]
      (is (contains? ids :eval$read-verdict))
      (is (contains? ids :eval$find))
      (is (not (contains? ids :eval$dossier-slug)))
      (is (not (contains? ids :eval$verdict-write)))
      (is (not (contains? ids :eval$dossier-frontmatter)))
      (is (not (contains? ids :eval$dossier-write)))
      (is (not (contains? ids :eval$dossier-index-append)))
      (is (not (contains? ids :eval$read-dossier)) "renamed → eval$read-verdict")
      (is (not (contains? ids :eval$next-handoff)))))

  (testing "eval-agent :agent-tools includes doc$read/list (fallback) + reads + sub-LLM"
    (let [ids (eval-tool-ids)]
      (is (contains? ids :doc$read))
      (is (contains? ids :doc$list))

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

  (testing "eval-agent binds write-file (its own verdict) but NOT update-file"
    (let [ids (eval-tool-ids)]
      (is (contains? ids :write-file)
          "write-file is bound — eval authors its own unified verdict under verdicts/")
      (is (not (contains? ids :update-file))
          "update-file stays OUT — eval writes whole files, never patches")))

  (testing "eval-agent :agent-tools EXCLUDES edit-agent's WRITE-SIDE helpers (only read-record cherry-picked)"
    (let [ids (eval-tool-ids)]
      (is (not (contains? ids :edit$apply))
          "edit$apply (write-side mega-helper) excluded")
      (is (not (contains? ids :edit$write))
          "edit$write (writes records) excluded")
      (is (not (contains? ids :edit$frontmatter)))
      (is (not (contains? ids :edit$slug)))
      (is (not (contains? ids :edit$find))
          "even edit$find is not bound — only read-record cherry-picked")
      (is (not (contains? ids :edit$index-append)))))

  (testing "eval-agent :agent-tools EXCLUDES web/skills/MCP/clone-self"
    (let [ids (eval-tool-ids)]
      (is (not (contains? ids :query$clone))
          "query$clone must not be in eval-agent's roster (clone-self forbidden)")
      (is (not (contains? ids :fetch-url)))
      (is (not (contains? ids :web-search)))
      (is (not (contains? ids :skills$list)))
      (is (not (contains? ids :skills$find)))
      (is (not (contains? ids :skills$read)))
      (is (not (contains? ids :mcp$server))
          "MCP not bound — eval reads dossiers, not MCP servers directly")
      (is (not (contains? ids :mcp$tools))))))

;; ============================================================================
;; Instruction & tool-context content anchors
;; ============================================================================

(deftest instruction-content-test
  (testing "instruction string contains the cardinal eval-agent v2 anchors"
    (let [agent-def   (get (tool/get-tool-defs :type :agent) :eval-agent)
          instruction (get-in agent-def [:meta :instruction])]
      (is (string? instruction))
      (is (not (str/blank? instruction)))

      ;; Three phases
      (is (str/includes? instruction "PRE-FLIGHT"))
      (is (str/includes? instruction "SCORE"))
      (is (str/includes? instruction "POST-FLIGHT"))
      (is (str/includes? instruction "PERSIST"))
      (is (str/includes? instruction "ANSWER"))

      ;; Pre-flight checklist
      (is (str/includes? instruction "C1"))
      (is (str/includes? instruction "C7"))
      (is (str/includes? instruction "EXEC DOSSIER"))
      (is (str/includes? instruction "EVIDENCE PRESENT"))
      (is (str/includes? instruction "PLAN ACCEPTANCE"))
      (is (str/includes? instruction "GO"))
      (is (str/includes? instruction "GATHER"))
      (is (str/includes? instruction "REFUSE"))

      ;; Per-criterion classification
      (is (str/includes? instruction "SATISFIED"))
      (is (str/includes? instruction "PARTIAL"))
      (is (str/includes? instruction "MISSING"))
      (is (str/includes? instruction "CONTRADICTED"))

      ;; Verdict aggregation
      (is (str/includes? instruction "ACHIEVED"))
      (is (str/includes? instruction "PARTIALLY_ACHIEVED"))
      (is (str/includes? instruction "NOT_ACHIEVED"))

      ;; Confidence
      (is (str/includes? instruction "confidence"))
      (is (str/includes? instruction ":high"))
      (is (str/includes? instruction ":medium"))
      (is (str/includes? instruction ":low"))

      ;; Post-flight rubric
      (is (str/includes? instruction "R1"))
      (is (str/includes? instruction "R7"))
      (is (str/includes? instruction "REPRODUCIBILITY"))
      (is (str/includes? instruction "PASS"))
      (is (str/includes? instruction "HOLD"))

      ;; Storage + handoff — ONE unified verdict file, authored via write-file
      (is (str/includes? instruction ".brainyard/agents/eval-agent/verdicts/"))
      (is (str/includes? instruction "VERDICT TEMPLATE"))
      (is (str/includes? instruction "write-file"))
      ;; No separate dossiers/ dir anymore (verdict+dossier unified, §5)
      (is (not (str/includes? instruction ".brainyard/agents/eval-agent/dossiers/")))
      (is (str/includes? instruction "Saved verdict:"))
      ;; `Saved dossier:` survives only as the EXEC INPUT marker (C1), not output
      (is (str/includes? instruction "Saved dossier:"))
      (is (str/includes? instruction "Verdict:"))

      ;; Hard rules
      (is (str/includes? instruction "HARD RULES"))
      (is (str/includes? instruction "READ-ONLY"))
      (is (str/includes? instruction "clone-self"))
      (is (str/includes? instruction "auto-dispatching")
          "Hard Rule 6: never auto-dispatch plan/todo/exec recommendations")

      ;; v1 narrowing
      (is (or (str/includes? instruction "REVISE auto-round deferred")
              (str/includes? instruction "v1.5"))
          "instruction notes that REVISE is deferred"))))

(deftest tool-context-content-test
  (testing "tool-context names the upstream-dossier surface, eval helpers, and read-only constraint"
    (let [agent-def    (get (tool/get-tool-defs :type :agent) :eval-agent)
          tool-context (get-in agent-def [:meta :tool-context])]
      (is (string? tool-context))
      (is (not (str/blank? tool-context)))

      ;; Upstream dossier readers (READ-ONLY)
      (is (str/includes? tool-context "exec$read-dossier"))
      (is (str/includes? tool-context "todo$read-dossier"))
      (is (str/includes? tool-context "plan$read-dossier"))
      (is (str/includes? tool-context "edit$read-record")
          "drill-down from evidence to diff via edit-agent record")
      (is (str/includes? tool-context "READ-ONLY"))

      ;; Eval READ seams survive; write-side chain is retired
      (is (str/includes? tool-context "eval$read-verdict"))
      (is (str/includes? tool-context "eval$find"))
      (is (not (str/includes? tool-context "eval$dossier-slug")))
      (is (not (str/includes? tool-context "eval$verdict-write")))
      (is (not (str/includes? tool-context "eval$dossier-frontmatter")))
      (is (not (str/includes? tool-context "eval$dossier-write")))
      (is (not (str/includes? tool-context "eval$dossier-index-append")))
      (is (not (str/includes? tool-context "eval$next-handoff")))

      ;; Verdict authored via write-file (one unified file)
      (is (str/includes? tool-context "write-file"))

      ;; Sub-LLM
      (is (str/includes? tool-context "query$llm"))

      ;; Cross-agent dispatch (with user opt-in caveat)
      (is (str/includes? tool-context "plan-agent"))
      (is (str/includes? tool-context "todo-agent"))
      (is (str/includes? tool-context "exec-agent"))
      (is (str/includes? tool-context "auto-apply")
          "v1 does NOT auto-apply recommendations")

      ;; Storage path
      (is (str/includes? tool-context ".brainyard/agents/eval-agent/")))))
