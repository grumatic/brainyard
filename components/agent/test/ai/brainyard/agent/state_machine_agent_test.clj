;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.state-machine-agent-test
  "Tests for state-machine-agent (docs/design/state-machine-agent-design.md §10).

   Two surfaces (the third, §10.3 real-LLM e2e, is manual / CI-opt-in and lives
   outside this deterministic suite):

   1. STRUCTURAL — registration, inherited CoAct bt-factory, the fsm$* family
      bound (positive) and the design's exclusions (negative: NO flat
      event$*/reaction$*/watch$* — those are event-agent's; NO schedule$* time
      jobs; NO config-write — gates are config-agent's; NO clone-self), schema
      shape, and the instruction / tool-context anchors that carry the design's
      non-negotiable guidances (two lifecycles, graph validation, gate outcome,
      reset consequence, blast radius, config-agent hand-off). Mirrors
      config_agent_test / event_agent_test.

   2. COMMAND PASS-THROUGH — the design §10.1 check, driven hermetically (no
      LLM): fsm$define writes a normalized machine.edn (string keys coerced to
      keywords), persists it even with the gate off (with the inert note),
      fsm$list surfaces it, fsm$status reads the DISTINCT per-session runtime
      (definition vs runtime — the two lifecycles), fsm$define guards a bad id,
      and fsm$remove drops both definition and runtime.

   The FSM engine (guards, step!, tick!, normalization) is covered by
   fsm_test.clj; this suite does not re-test it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [ai.brainyard.agent.common.coact-agent]
            [ai.brainyard.agent.common.state-machine-agent]
            [ai.brainyard.agent.common.fsm :as fsm]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- agent-def []
  (get (tool/get-tool-defs :type :agent) :state-machine-agent))

(defn- tool-ids []
  (set (map (comp :id meta deref)
            (get-in (agent-def) [:meta :agent-tools :tools]))))

;; ============================================================================
;; 1. STRUCTURAL
;; ============================================================================

(deftest registration-test
  (testing "state-machine-agent is registered in the unified tool registry"
    (let [d (agent-def)]
      (is (some? d))
      (is (= :state-machine-agent (:id d)))
      (is (= :agent (:type d)))
      (is (some? (:fn d))))))

(deftest inheritance-test
  (testing ":bt-factory is pinned (so setup-agent-by-id resolves the CoAct BT)"
    (is (fn? (get-in (agent-def) [:meta :bt-factory])))))

(deftest schema-shape-test
  (testing "input takes :question; output surfaces :answer"
    (let [d (agent-def)
          in  (get-in d [:meta :input-schema])
          out (get-in d [:meta :output-schema])]
      (is (some #(= :question (first %)) (filter vector? in)))
      (is (some #(= :answer (first %)) (filter vector? out))))))

(deftest agent-tools-positive
  (testing "binds the full fsm$* family (definition + runtime lifecycles)"
    (let [ids (tool-ids)]
      (is (contains? ids :fsm$define))
      (is (contains? ids :fsm$list))
      (is (contains? ids :fsm$status))
      (is (contains? ids :fsm$send))
      (is (contains? ids :fsm$reset))
      (is (contains? ids :fsm$remove))))

  (testing "inherits the read-side gate + synthesis + cross-agent dispatch"
    (let [ids (tool-ids)]
      (is (contains? ids :agent-runtime$config))
      (is (contains? ids :call-tool))
      (is (contains? ids :query$llm))
      (is (contains? ids :write-file)))))

(deftest agent-tools-negative
  (let [ids (tool-ids)]
    (testing "flat trigger→action families are event-agent's — NOT bound here"
      (is (not (contains? ids :event$define)))
      (is (not (contains? ids :reaction$add)))
      (is (not (contains? ids :watch$add))))

    (testing "time-triggered prompt jobs are schedule-agent's — NO schedule$* bound"
      (is (not (contains? ids :schedule$add))))

    (testing "the gate flags are config-agent's — NO config-write bound"
      (is (not (contains? ids :config$apply)))
      (is (not (contains? ids :config$revert))))

    (testing "no clone-self recursion"
      (is (not (contains? ids :query$clone))))))

(deftest instruction-anchors
  (testing "instruction carries the design's non-negotiable guidances"
    (let [instr (get-in (agent-def) [:meta :instruction])]
      (is (re-find #"SIX CAPABILITY KINDS" instr))
      ;; the two lifecycles kept distinct
      (is (re-find #"(?i)two lifecycles" instr))
      (is (re-find #"fsm\$define" instr))
      (is (re-find #"fsm\$send" instr))
      ;; graph validation (reachability / dangling target)
      (is (re-find #"(?i)reachab" instr))
      (is (re-find #"(?i)dangling :target" instr))
      ;; gate outcome + reset consequence + blast radius
      (is (re-find #":fsm-allow-code" instr))
      (is (re-find #"(?i)reset" instr))
      (is (re-find #"(?i)forces a turn" instr))
      ;; hand-off to config-agent for the gates
      (is (re-find #"config-agent" instr))
      ;; per-conversation dossier + hard rules
      (is (re-find #"(?i)dossier" instr))
      (is (re-find #"HARD RULES" instr))
      ;; Dossier is a HARD final-step contract, not advisory.
      (is (re-find #"FINAL-STEP CHECKLIST" instr))
      (is (re-find #"DOSSIER WRITTEN" instr))
      (is (re-find #"(?i)incomplete turn" instr)))))

(deftest tool-context-anchors
  (testing "tool-context names the commands + the cross-agent dispatch shape"
    (let [tc (get-in (agent-def) [:meta :tool-context])]
      (is (re-find #"fsm\$define" tc))
      (is (re-find #"fsm\$send" tc))
      (is (re-find #"fsm\$status" tc))
      (is (re-find #"agent-runtime\$config" tc))
      (is (re-find #"call-tool \"config-agent\"" tc))
      (is (re-find #"FORBIDDEN" tc)))))

;; ============================================================================
;; 2. COMMAND PASS-THROUGH (hermetic — no LLM)
;; ============================================================================

(def ^:dynamic *pdir* nil)
(def ^:private test-sid "sma-test-session")

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "sma-test-" (System/currentTimeMillis) "-" (rand-int 100000)))]
    (.mkdirs dir)
    (binding [*pdir* (.getPath dir)]
      (try (f)
           (finally
             (fsm/reset-state!)
             (hooks/reset-hooks!)
             (doseq [^java.io.File x (reverse (file-seq dir))] (.delete x)))))))

(use-fixtures :each temp-dir-fixture)

(defmacro ^:private with-scratch
  "Run body with project-dir pinned at *pdir*, a fixed current session-id, and
   every gate reporting OFF (so fsm$define exercises its inert-note path)."
  [& body]
  `(with-redefs [config/project-dir            (fn ([] *pdir*) ([_#] *pdir*))
                 config/get-config             (fn ([_k#] false) ([_a# _k#] false))
                 proto/get-current-session-id  (fn [] test-sid)]
     ~@body))

(deftest fsm-define-list-status-remove-round-trip
  (with-scratch
    (testing "fsm$define writes a normalized machine (string keys → keywords) and flags it inert"
      (let [res (fsm/fsm$define
                 :id "deploy-gate"
                 :initial "idle"
                 :states {"idle"    {:on {"ci/passed" [{:target "awaiting-approval"}]}}
                          "awaiting-approval" {:on {"user/approved" [{:target "done"}]}}
                          "done"    {:type :final}})]
        (is (= "deploy-gate" (:defined res)))
        (is (= :idle (:initial res)) ":initial coerced to a keyword")
        (is (re-find #"(?i)state machines are off" (:note res))
            "gate-off write carries the inert note (design principle 4)")
        ;; normalize-machine coerced string state/event keys to keywords on disk.
        (let [m (fsm/read-machine *pdir* "deploy-gate")]
          (is (= :idle (:initial m)))
          (is (contains? (:states m) :idle))
          (is (contains? (get-in m [:states :idle :on]) :ci/passed)
              "event key coerced to a namespaced keyword"))))

    (testing "fsm$list surfaces the machine with its state names"
      (let [machines (:machines (fsm/fsm$list))
            gate (first (filter #(= "deploy-gate" (:id %)) machines))]
        (is (some? gate))
        (is (= :idle (:initial gate)))
        (is (= #{:idle :awaiting-approval :done} (set (:states gate))))))

    (testing "fsm$status reads the DISTINCT per-session runtime (definition vs runtime)"
      ;; runtime starts at :initial for a fresh session.
      (let [st (fsm/fsm$status :id "deploy-gate")]
        (is (= "deploy-gate" (:id st)))
        (is (= :idle (:state st)) "fresh session runtime is the initial state")
        (is (vector? (:history st)))))

    (testing "fsm$remove drops the definition (and runtime)"
      (is (= "deploy-gate" (:removed (fsm/fsm$remove :id "deploy-gate"))))
      (is (nil? (fsm/read-machine *pdir* "deploy-gate")))
      (is (empty? (:machines (fsm/fsm$list)))))))

(deftest fsm-define-guards-bad-input
  (with-scratch
    (testing "invalid :id is rejected (lowercase-kebab constraint, edge case 9)"
      (is (re-find #"invalid :id"
                   (:error (fsm/fsm$define :id "Deploy Gate" :initial "idle"
                                           :states {"idle" {}})))))
    (testing "missing :initial is rejected"
      (is (re-find #":initial"
                   (:error (fsm/fsm$define :id "m" :initial nil
                                           :states {"idle" {}})))))
    (testing "non-map :states is rejected"
      (is (re-find #":states"
                   (:error (fsm/fsm$define :id "m" :initial "idle" :states "nope")))))))
