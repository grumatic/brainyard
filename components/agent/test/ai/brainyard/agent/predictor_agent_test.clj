;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.predictor-agent-test
  "Tests for predictor-agent (docs/design/predictor-agent-design.md, Phase 2).

   Three surfaces, none of which reaches an LLM:

   1. STRUCTURAL — registration, the pinned CoAct bt-factory, schema shape, and
      the roster: BOTH halves of the arc bound (predictor$* authoring AND
      program$* measurement), and the exclusions that carry the design's trust
      boundary (no config write, no clone-self).

   2. INSTRUCTION ANCHORS — the non-negotiables the design argues for, asserted
      as text so a later edit cannot quietly drop one: validate-before-create,
      verify-after-create, accept-is-CLI-only, never-redefine-a-source-predictor,
      the prediction-log gate, never-a-bare-score, side-effects-are-tool-agent's,
      and the hard dossier contract.

   3. ROUTER REGISTRATION — the three places a new specialist has to appear in
      router_agent.clj (directory, decision table, summary list) or it
      exists and is never reached. That trio is easy to half-do; this is the
      check that it was not.

   The commands themselves are covered by user_predictors_test.clj (authoring)
   and programs_test.clj (measurement); this suite does not re-test them."
  (:require [ai.brainyard.agent.common.coact-agent]
            [ai.brainyard.agent.common.predictor-agent]
            [ai.brainyard.agent.common.router-agent]
            [ai.brainyard.agent.core.tool :as tool]
            [clojure.test :refer [deftest is testing]]))

(defn- agent-def []
  (get (tool/get-tool-defs :type :agent) :predictor-agent))

(defn- tool-ids []
  (set (map (comp :id meta deref)
            (get-in (agent-def) [:meta :agent-tools :tools]))))

(defn- router-text [k]
  (get-in (get (tool/get-tool-defs :type :agent) :router-agent) [:meta k]))

;; ============================================================================
;; 1. STRUCTURAL
;; ============================================================================

(deftest registration-test
  (testing "predictor-agent is registered in the unified tool registry"
    (let [d (agent-def)]
      (is (some? d))
      (is (= :predictor-agent (:id d)))
      (is (= :agent (:type d)))
      (is (some? (:fn d))))))

(deftest inheritance-test
  (testing ":bt-factory is pinned (so setup-agent-by-id resolves the CoAct BT)"
    (is (fn? (get-in (agent-def) [:meta :bt-factory])))))

(deftest schema-shape-test
  (testing "input takes :question; output surfaces :answer"
    (let [d   (agent-def)
          in  (get-in d [:meta :input-schema])
          out (get-in d [:meta :output-schema])]
      (is (some #(= :question (first %)) (filter vector? in)))
      (is (some #(= :answer (first %)) (filter vector? out))))))

(deftest agent-tools-positive
  (let [ids (tool-ids)]
    (testing "the DEFINITION half — the whole predictor$* authoring surface"
      (is (contains? ids :predictor$validate))
      (is (contains? ids :predictor$create))
      (is (contains? ids :predictor$list))
      (is (contains? ids :predictor$read))
      (is (contains? ids :predictor$delete)))

    (testing "the MEASUREMENT half — bound EXPLICITLY, not inherited"
      ;; program$* also rides the common roster, but a derived agent's
      ;; :agent-tools IS its roster — inheriting it is not something this
      ;; agent may assume. The arc breaks in the middle if this regresses.
      (is (contains? ids :program$list))
      (is (contains? ids :program$build-dataset))
      (is (contains? ids :program$eval))
      (is (contains? ids :program$compile))
      (is (contains? ids :program$proposals)))

    (testing "the read-side gate, cross-agent dispatch, synthesis, dossier I/O"
      (is (contains? ids :agent-runtime$config))   ; reads :enable-prediction-log
      (is (contains? ids :call-tool))              ; tool-/meta-/config-agent
      (is (contains? ids :query$llm))
      (is (contains? ids :list-tools))
      (is (contains? ids :write-file))
      (is (contains? ids :update-file)))))

(deftest agent-tools-negative
  (let [ids (tool-ids)]
    (testing "accept/reject are not reachable — they are not tools at all"
      ;; The trust boundary is enforced by there being no command, not by the
      ;; roster omitting one. Asserted so a later `defcommand program$accept`
      ;; fails here rather than silently handing the agent its own params.
      (is (not (contains? ids :program$accept)))
      (is (not (contains? ids :program$reject)))
      (is (not (contains? (set (keys (tool/get-tool-defs))) :program$accept)))
      (is (not (contains? (set (keys (tool/get-tool-defs))) :program$reject))))

    (testing "config gates are config-agent's — no config write bound"
      (is (not (contains? ids :config$apply)))
      (is (not (contains? ids :config$revert))))

    (testing "no clone-self recursion"
      (is (not (contains? ids :query$clone))))))

;; ============================================================================
;; 2. INSTRUCTION / TOOL-CONTEXT ANCHORS
;; ============================================================================

(deftest instruction-anchors
  (let [instr (get-in (agent-def) [:meta :instruction])]
    (testing "the six capability kinds and the authoring flow"
      (is (re-find #"SIX CAPABILITY KINDS" instr))
      (is (re-find #"predictor\$validate" instr))
      (is (re-find #"predictor\$create" instr))
      (is (re-find #"user\$predictor\$<name>" instr)))

    (testing "validate before create, verify after create"
      (is (re-find #"(?i)DRY-RUN — HARD RULE" instr))
      (is (re-find #"(?i)VERIFY — HARD RULE" instr))
      (is (re-find #"(?i)never report success before" instr)))

    (testing "accept is the user's, via the CLI"
      (is (re-find #"by programs accept" instr))
      (is (re-find #"(?i)you cannot, by design" instr)))

    (testing "a source predictor is changed with params, never redefined"
      (is (re-find #"(?i)params, not redefinition" instr))
      (is (re-find #"(?i):builtin true" instr)))

    (testing "the prediction-log gate is read before a dataset is promised"
      (is (re-find #":enable-prediction-log" instr))
      (is (re-find #"program\$build-dataset" instr)))

    (testing "no bare score — the measured-noise guidance"
      (is (re-find #"(?i)NEVER REPORT A BARE SCORE" instr))
      (is (re-find #":repeats" instr))
      (is (re-find #"(?i)inside the noise" instr)))

    (testing "a predictor has no side effects — those are tool-agent's"
      (is (re-find #"(?i)A PREDICTOR IS DATA, NOT CODE" instr))
      (is (re-find #"tool-agent" instr))
      (is (re-find #"meta-agent" instr))
      (is (re-find #"config-agent" instr)))

    (testing "the dossier is a hard final-step contract, not advisory"
      (is (re-find #"(?i)dossier" instr))
      (is (re-find #"HARD RULES" instr))
      (is (re-find #"FINAL-STEP CHECKLIST" instr))
      (is (re-find #"DOSSIER WRITTEN" instr))
      (is (re-find #"(?i)incomplete turn" instr)))))

(deftest tool-context-anchors
  (let [tc (get-in (agent-def) [:meta :tool-context])]
    (testing "both command families are named with their shapes"
      (is (re-find #"predictor\$validate" tc))
      (is (re-find #"predictor\$create" tc))
      (is (re-find #"program\$eval" tc))
      (is (re-find #"program\$compile" tc)))

    (testing "the EDN-string arg convention is taught (the tool-calls channel)"
      (is (re-find #"(?i)EDN string" tc)))

    (testing "accept is spelled out as a CLI line, and marked forbidden here"
      (is (re-find #"ACCEPT IS NOT A TOOL" tc))
      (is (re-find #"by programs accept" tc))
      (is (re-find #"FORBIDDEN" tc)))

    (testing "cross-agent dispatch shape"
      (is (re-find #"call-tool \"tool-agent\"" tc))
      (is (re-find #"call-tool \"meta-agent\"" tc))
      (is (re-find #"call-tool \"config-agent\"" tc))
      (is (re-find #"agent-runtime\$config" tc)))))

;; ============================================================================
;; 3. ROUTER REGISTRATION — all three places, or it is unreachable
;; ============================================================================

(deftest router-registration
  (let [instr (router-text :instruction)
        tc    (str (router-text :tool-context))
        both  (str instr "\n" tc)]
    (testing "1. the AGENT DIRECTORY names it and says what the tell is"
      (is (re-find #"(?m)^- predictor-agent" both))
      (is (re-find #"predictor\$\*" both))
      (is (re-find #"program\$\*" both)))

    (testing "2. the DECISION TABLE carries a PREDICTOR-LIFECYCLE row"
      (is (re-find #"PREDICTOR-LIFECYCLE\s+→ predictor-agent" both)))

    (testing "3. the SUMMARY list at the end mentions it"
      (is (re-find #"(?m)^- predictor-agent\s+→ predictor lifecycle" both)))

    (testing "the boundary against its nearest neighbours is stated"
      ;; Without this the router sends 'make me a tool that classifies X' here
      ;; and 'make me something that classifies X' to tool-agent, at random.
      (is (re-find #"(?i)not a tool with a side effect" both)))))
