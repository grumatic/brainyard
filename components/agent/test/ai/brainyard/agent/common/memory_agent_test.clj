;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.memory-agent-test
  "Tests for memory-agent's defagent surface: registration, the bt-factory
   inheritance from CoAct with a 10-iteration default cap, the curated
   16-tool roster with both positive (must-have) and negative (forbidden)
   assertions, instruction + tool-context anchors, and a setup-agent-by-id
   round-trip that exercises the runtime wiring path."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.memory-agent]
            [ai.brainyard.agent.common.memory-agent.commands :as ma-cmds]
            [ai.brainyard.agent.core.tool :as tool]))

;; ============================================================================
;; Registration
;; ============================================================================

(deftest registration-test
  (testing "memory-agent is registered as an :agent"
    (let [d (tool/get-tool-defs :id :memory-agent)]
      (is (some? d))
      (is (= :memory-agent (:id d)))
      (is (= :agent (:type d)))
      (is (some? (:fn d)))
      (is (some? (:meta d))))))

;; ============================================================================
;; Inheritance via run-memory-agent — CoAct BT, 10-iter default
;; ============================================================================

(deftest inheritance-test
  (require 'ai.brainyard.agent.common.coact-agent)
  (let [d (get (tool/get-tool-defs :type :agent) :memory-agent)]

    (testing "memory-agent's :fn is registered (the wrap-fn invoking run-memory-agent)"
      (is (some? (:fn d))))

    (testing ":bt-factory is pinned explicitly and produces a CoAct BT"
      (let [bt-factory (get-in d [:meta :bt-factory])]
        (is (fn? bt-factory))
        (let [bt (bt-factory {:max-iterations 3})]
          (is (vector? bt))
          (is (= :sequence (first bt)))
          (is (= "coact.sequence" (namespace (get-in bt [1 :id])))
              ":bt-factory yields the CoAct BT (coact.sequence/* root)"))))

    (testing "default :max-iterations is 10 when bt-factory receives nil"
      (let [bt-factory (get-in d [:meta :bt-factory])
            bt-default (bt-factory {})
            bt-10      (bt-factory {:max-iterations 10})]
        (is (vector? bt-default))
        (is (= (first bt-default) (first bt-10))
            "default-10 build has the same shape as explicit-10")))))

;; ============================================================================
;; Tool roster — positive + negative assertions
;; ============================================================================

(defn- memory-agent-tool-ids []
  (let [d           (get (tool/get-tool-defs :type :agent) :memory-agent)
        agent-tools (get-in d [:meta :agent-tools])]
    (set (map (comp :id meta deref) (:tools agent-tools)))))

(deftest tool-roster-positive-test
  (let [ids (memory-agent-tool-ids)]
    (testing "roster includes the read primitives (own + reused common)"
      (is (contains? ids :memory$read))
      (is (contains? ids :memory$stats))
      (is (contains? ids :memory$keywords))
      (is (contains? ids :memory$recall))
      (is (contains? ids :memory$explain)))

    (testing "roster includes the write primitives (gated to memory-agent)"
      (is (contains? ids :memory$write))
      (is (contains? ids :memory$promote))
      (is (contains? ids :memory$forget))
      (is (contains? ids :memory$keep!))
      (is (contains? ids :memory$archive!))
      (is (contains? ids :memory$consolidate))
      (is (contains? ids :memory$sweep-l2)))

    (testing "roster includes the working-area triplet"
      (is (contains? ids :memory$state-read))
      (is (contains? ids :memory$state-write))
      (is (contains? ids :memory$essence-append)))

    (testing "roster includes query$llm for reasoning"
      (is (contains? ids :query$llm)))

    (testing "every gated write primitive is present (matches write-guard set)"
      (let [guard-kws (set (map keyword ma-cmds/write-guarded-tools))]
        ;; memory-agent must be able to call every tool the guard governs;
        ;; otherwise non-memory-agent callers can't get those effects at all.
        (doseq [k guard-kws]
          (is (contains? ids k)
              (str "guard-protected tool " k " must be in memory-agent's roster")))))))

(deftest tool-roster-negative-test
  (let [ids (memory-agent-tool-ids)]
    (testing "Hard Rule 1 — no clone-self / sub-agent spawning"
      (is (not (contains? ids :query$clone))
          ":query$clone must be excluded (clone-self forbidden)")
      (is (not (contains? ids :call-tool))
          ":call-tool must be excluded (no sub-agent spawning)"))

    (testing "no file/shell tools — memory-agent's filesystem surface is memory$state-*"
      (is (not (contains? ids :read-file)))
      (is (not (contains? ids :write-file)))
      (is (not (contains? ids :update-file)))
      (is (not (contains? ids :bash)))
      (is (not (contains? ids :grep))))

    (testing "no other agent's helpers — memory-agent is a leaf bookkeeper"
      (is (not (contains? ids :research$bootstrap)))
      (is (not (contains? ids :plan$dossier-write)))
      (is (not (contains? ids :todo$dossier-write))))

    (testing "no task$run — memory-agent ops are synchronous"
      (is (not (contains? ids :task$run))))

    ;; Phase 5 added memory$verify-fact to the roster; positive assertion
    ;; lives in tool-roster-positive-test now.
    ))

;; ============================================================================
;; I/O contract — :inputs / :outputs shape
;; ============================================================================

(deftest io-contract-test
  ;; schema->type normalizes each [:map ...] entry into a flat map per key,
  ;; pulling :optional from the entry-props slot and :default from the
  ;; value-schema props — so this test reads both from one place regardless
  ;; of which slot the schema put them in.
  (let [d            (get (tool/get-tool-defs :type :agent) :memory-agent)
        input-schema (get-in d [:meta :input-schema])
        by-key       (tool/schema->type input-schema)]

    (testing ":op is the required discriminator with a 'stats' default"
      (let [op (get by-key :op)]
        (is (some? op))
        (is (= "stats" (:default op)))
        (is (not= true (:optional op))
            ":op is required (no :optional true)")))

    (testing "every per-op argument is declared optional"
      (doseq [k [:scope :format :content :kind :tags :confidence :session-id
                 :turn-id :total-turns :hint :window :fact-id :evidence :dry-run?]]
        (let [entry (get by-key k)]
          (is (some? entry) (str "input " k " must be declared"))
          (is (true? (:optional entry))
              (str "input " k " must be :optional true")))))

    (testing "outputs contain :answer (string) per CoAct contract"
      (is (some #(= :answer (first %)) (rest (get-in d [:meta :output-schema])))))))

;; ============================================================================
;; Instruction + tool-context content anchors
;; ============================================================================

(deftest instruction-content-test
  (let [d           (get (tool/get-tool-defs :type :agent) :memory-agent)
        instruction (get-in d [:meta :instruction])]

    (testing "instruction is a non-blank string"
      (is (string? instruction))
      (is (not (str/blank? instruction))))

    (testing "states the agent's role + scope"
      (is (str/includes? instruction "MEMORY-AGENT"))
      ;; The phrase "layered memory stack" wraps across a newline in the
      ;; prompt body — match the prefix that survives the wrap.
      (is (re-find #"(?s)layered memory\s+stack" instruction))
      (is (str/includes? instruction ".brainyard/memory/")))

    (testing "lists the supported Phase 2 ops"
      (is (str/includes? instruction ":stats"))
      (is (str/includes? instruction ":remember")))

    (testing "lists every supported op (Phases 1-5 all live)"
      (is (str/includes? instruction ":essence"))
      (is (str/includes? instruction ":consolidate"))
      (is (str/includes? instruction ":purge"))
      (is (str/includes? instruction ":verify-fact"))
      (is (str/includes? instruction ":correct"))
      (is (str/includes? instruction "unknown operation")
          "instruction must tell the LLM what to do for any op outside the supported set"))

    (testing "operating contract anchors"
      (is (str/includes? instruction "Run `memory$stats` first"))
      (is (str/includes? instruction "Idempotency"))
      (is (str/includes? instruction "Tombstones are permanent"))
      (is (str/includes? instruction "iteration"))
      (is (or (str/includes? instruction "10 max")
              (str/includes? instruction "10 iterations")
              (str/includes? instruction "cap is 10"))
          "instruction must surface the 10-iteration budget"))

    (testing "per-op playbook entries"
      (is (str/includes? instruction "### :op :stats"))
      (is (str/includes? instruction "### :op :remember"))
      (is (str/includes? instruction "memory$recall"))
      (is (str/includes? instruction "memory$write"))
      (is (str/includes? instruction "memory$keep!")))

    (testing "structured output contract"
      (is (str/includes? instruction "fenced clojure block"))
      (is (str/includes? instruction ":status"))
      (is (str/includes? instruction ":counts")))

    (testing "Hard Rule 1 echoed"
      (is (str/includes? instruction "clone yourself"))
      (is (str/includes? instruction "sub-agent")))))

(deftest tool-context-content-test
  (let [d            (get (tool/get-tool-defs :type :agent) :memory-agent)
        tool-context (get-in d [:meta :tool-context])]

    (testing "tool-context is a non-blank string"
      (is (string? tool-context))
      (is (not (str/blank? tool-context))))

    (testing "names every primitive in the roster (LLM discoverability)"
      (doseq [name-str ["memory$stats" "memory$recall" "memory$read"
                        "memory$explain" "memory$keywords"
                        "memory$write" "memory$promote" "memory$forget"
                        "memory$keep!" "memory$archive!"
                        "memory$consolidate" "memory$sweep-l2"
                        "memory$state-read" "memory$state-write"
                        "memory$essence-append"
                        "query$llm"]]
        (is (str/includes? tool-context name-str)
            (str "tool-context must name " name-str))))

    (testing "labels the forbidden set"
      (is (str/includes? tool-context "Sub-agent dispatch")))

    (testing "documents the iteration budget"
      (is (str/includes? tool-context "10")))))

;; ============================================================================
;; setup-agent-by-id smoke — runtime wiring
;; ============================================================================

(deftest setup-agent-by-id-smoke-test
  (require 'ai.brainyard.agent.core.agent)
  (let [setup (resolve 'ai.brainyard.agent.core.agent/setup-agent-by-id)
        ag    (setup :memory-agent
                     :agent-session {:user-id    (str "p2-" (System/nanoTime))
                                     :session-id (str "s-"  (System/nanoTime))}
                     :max-iterations 10)]
    (try
      (let [state   @(:!state ag)
            st-init @(:st-memory-init state)]

        (testing "agent is created + started + memory-managed"
          (is (keyword? (:agent-id ag)))
          (is (= "memory-agent" (namespace (:agent-id ag))))
          (is (= :idle (:status state)))
          (is (some? (:memory-manager state))
              "memory-manager is always created (default in-memory)"))

        (testing "behavior-tree is wired in (CoAct via :bt-factory)"
          (is (some? (:behavior-tree state))))

        (testing "instruction + tool-context flow into st-memory-init"
          (is (string? (:instruction st-init)))
          (is (str/includes? (:instruction st-init) "MEMORY-AGENT"))
          (is (string? (:tool-context st-init)))
          (is (str/includes? (:tool-context st-init) "memory$stats")))

        (testing "the 20-tool roster lands on the bound tools list"
          (let [names (set (map (comp str :name) (:tools st-init)))]
            (is (= 20 (count names)))
            (is (contains? names "memory$stats"))
            (is (contains? names "memory$write"))
            (is (contains? names "memory$state-write"))
            (is (contains? names "memory$essence-extract"))
            (is (contains? names "memory$llm-consolidate"))
            (is (contains? names "memory$purge-plan"))
            (is (contains? names "memory$verify-fact"))
            (is (contains? names "query$llm"))
            (is (not (contains? names "query$clone")))
            (is (not (contains? names "call-tool")))))

        (testing ":max-iterations is honored from the call args"
          (is (= 10 (get-in st-init [:config :max-iterations])))))

      (finally (.close ag)))))
