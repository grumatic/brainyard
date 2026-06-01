;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.memory-agent.verify-test
  "Phase 5 — verify-fact + correct surface.

   Covers:
   - FactVerification signature compiles with the expected I/O.
   - `memory$verify-fact` dispatches through chain-of-thought and
     handles all three verdicts cleanly (:still-true, :refine, :wrong).
   - Instruction includes both :verify-fact and :correct playbooks
     with the right anchors.
   - The signature is registered in the agent's roster + the
     write-guard set."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.memory-agent]
            [ai.brainyard.agent.common.memory-agent.commands :as ma-cmds]
            [ai.brainyard.agent.common.memory-agent.signatures :as ma-sig]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.memory.core.manager :as manager]))

;; ============================================================================
;; Stub agent
;; ============================================================================

(defrecord StubAgent [agent-id !state !session]
  proto/IAgent
  (agent-id [_] agent-id)
  (agent-name [_] (str agent-id))
  (agent-description [_] "stub")
  (user-id [_] (some-> !session deref :user-id))
  (session-id [_] (some-> !session deref :session-id))
  (defagent-type [_]
    (if-let [ns (and (keyword? agent-id) (namespace agent-id))]
      (keyword ns)
      agent-id))
  (process [_ _ _] nil)
  (get-tools [_] [])
  (get-state [_] @!state))

(defn- make-stub
  [agent-id mm session-id]
  (->StubAgent agent-id
               (atom {:memory-manager mm :config {:name (str agent-id)}})
               (atom {:user-id (:user-id mm) :session-id session-id})))

(def ^:dynamic *mm* nil)

(defn- with-test-mm [f]
  (let [mm (manager/create-memory-manager (str "user-" (random-uuid))
                                          :in-memory true)]
    (try
      (binding [*mm* mm] (f))
      (finally
        (when-let [ds (:ds mm)] (try (.close ds) (catch Exception _)))))))

(use-fixtures :each with-test-mm)

;; ============================================================================
;; FactVerification signature
;; ============================================================================

(deftest fact-verification-signature-test
  (testing "FactVerification is compiled with dspy metadata"
    (is (some? ma-sig/FactVerification))
    (is (:dspy/signature (meta #'ma-sig/FactVerification))))

  (testing "input keys cover fact + fresh-recall + evidence"
    (let [iks (set (:input-keys ma-sig/FactVerification))]
      (is (contains? iks :fact))
      (is (contains? iks :fresh-recall))
      (is (contains? iks :evidence))))

  (testing "outputs declare verdict + refined-content + new-confidence + rationale"
    (let [oks (set (:output-keys ma-sig/FactVerification))]
      (is (contains? oks :verdict))
      (is (contains? oks :refined-content))
      (is (contains? oks :new-confidence))
      (is (contains? oks :rationale))))

  (testing "instructions surface the conservatism rules + verdict semantics"
    (let [instr (str (:instructions ma-sig/FactVerification))]
      (is (str/includes? instr "still-true"))
      (is (str/includes? instr "refine"))
      (is (str/includes? instr "wrong"))
      (is (str/includes? instr "Absence of evidence is NOT refutation"))
      (is (str/includes? instr "user")))))

;; ============================================================================
;; memory$verify-fact tool
;; ============================================================================

(deftest verify-fact-registered-test
  (testing "memory$verify-fact is in the roster + the guard set"
    (is (contains? ma-cmds/all-tool-ids :memory$verify-fact))
    (is (contains? ma-cmds/write-guarded-tools "memory$verify-fact"))))

(deftest verify-fact-still-true-test
  (testing ":still-true verdict flows through cleanly"
    (let [agent (make-stub :memory-agent/test *mm* "s1")]
      (with-redefs [clj-llm/chain-of-thought
                    (fn [sig inputs & _]
                      (is (= ma-sig/FactVerification sig))
                      (is (= "user prefers polylith"
                             (-> inputs :fact :content)))
                      {:outputs {:verdict         "still-true"
                                 :refined-content ""
                                 :new-confidence  0.95
                                 :rationale       "user said so explicitly"}
                       :reasoning "fresh recall confirms"})]
        (proto/with-agent agent
          (let [r (ma-cmds/memory$verify-fact
                   :fact {:id "f1"
                          :content "user prefers polylith"
                          :confidence 0.85
                          :tags ["arch"]}
                   :fresh-recall "user said: I prefer polylith\nuser said: polylith is the right layout"
                   :evidence "")]
            (is (= :still-true (:verdict r)))
            (is (= "" (:refined-content r)))
            (is (= 0.95 (:new-confidence r)))
            (is (string? (:rationale r)))
            (is (nil? (:error r)))))))))

(deftest verify-fact-refine-test
  (testing ":refine verdict surfaces the refined content"
    (let [agent (make-stub :memory-agent/test *mm* "s1")]
      (with-redefs [clj-llm/chain-of-thought
                    (fn [_ _ & _]
                      {:outputs {:verdict         "refine"
                                 :refined-content "user prefers polylith for backend services"
                                 :new-confidence  0.9
                                 :rationale       "scope narrowed: backend only, not frontend"}})]
        (proto/with-agent agent
          (let [r (ma-cmds/memory$verify-fact
                   :fact {:id "f1" :content "user prefers polylith"
                          :confidence 0.8 :tags []}
                   :fresh-recall ""
                   :evidence "frontend uses something else")]
            (is (= :refine (:verdict r)))
            (is (str/includes? (:refined-content r) "backend"))
            (is (= 0.9 (:new-confidence r)))))))))

(deftest verify-fact-wrong-test
  (testing ":wrong verdict surfaces the counter-fact in refined-content"
    (let [agent (make-stub :memory-agent/test *mm* "s1")]
      (with-redefs [clj-llm/chain-of-thought
                    (fn [_ _ & _]
                      {:outputs {:verdict         "wrong"
                                 :refined-content "user prefers monorepo with deps.edn"
                                 :new-confidence  0.95
                                 :rationale       "user explicitly corrected the stored claim"}})]
        (proto/with-agent agent
          (let [r (ma-cmds/memory$verify-fact
                   :fact {:id "f1" :content "user prefers polylith"
                          :confidence 0.8 :tags []}
                   :fresh-recall ""
                   :evidence "I never said polylith; I prefer a single-deps.edn monorepo")]
            (is (= :wrong (:verdict r)))
            (is (str/includes? (:refined-content r) "monorepo"))
            (is (>= (:new-confidence r) 0.9))))))))

(deftest verify-fact-error-surface-test
  (testing "chain-of-thought exception surfaces as :error"
    (let [agent (make-stub :memory-agent/test *mm* "s1")]
      (with-redefs [clj-llm/chain-of-thought
                    (fn [& _] (throw (ex-info "lm 5xx" {})))]
        (proto/with-agent agent
          (let [r (ma-cmds/memory$verify-fact
                   :fact {:id "f1" :content "x" :confidence 0.5 :tags []})]
            (is (string? (:error r)))
            (is (str/includes? (:error r) "lm 5xx"))))))))

(deftest verify-fact-fills-defaults-test
  (testing "missing fact keys are filled with safe defaults so the schema is satisfied"
    (let [agent (make-stub :memory-agent/test *mm* "s1")]
      (with-redefs [clj-llm/chain-of-thought
                    (fn [_ inputs & _]
                      (let [f (:fact inputs)]
                        (is (string? (:id f)))
                        (is (string? (:content f)))
                        (is (number? (:confidence f)))
                        (is (vector? (:tags f))))
                      {:outputs {:verdict "still-true"
                                 :refined-content ""
                                 :new-confidence 0.5
                                 :rationale "no signal"}})]
        (proto/with-agent agent
          (let [r (ma-cmds/memory$verify-fact :fact {} :fresh-recall "" :evidence "")]
            (is (= :still-true (:verdict r)))))))))

;; ============================================================================
;; Instruction anchors
;; ============================================================================

(deftest instruction-verify-correct-anchors-test
  (let [d  (get (tool/get-tool-defs :type :agent) :memory-agent)
        ix (get-in d [:meta :instruction])]
    (testing ":op :verify-fact playbook present"
      (is (str/includes? ix "### :op :verify-fact"))
      (is (str/includes? ix "memory$verify-fact"))
      (is (str/includes? ix ":still-true"))
      (is (str/includes? ix ":refine"))
      (is (str/includes? ix ":wrong")))

    (testing ":op :correct playbook present"
      (is (str/includes? ix "### :op :correct"))
      (is (str/includes? ix ":user-correction"))
      (is (str/includes? ix ":fact-id"))
      (is (str/includes? ix ":evidence")))))

(deftest tool-context-mentions-verify-fact-test
  (let [d  (get (tool/get-tool-defs :type :agent) :memory-agent)
        tc (get-in d [:meta :tool-context])]
    (is (str/includes? tc "memory$verify-fact"))))

;; ============================================================================
;; Agent roster — Phase 5 tool lands
;; ============================================================================

(deftest agent-roster-includes-verify-fact-test
  (testing "memory-agent's declared roster includes memory$verify-fact"
    (let [d           (get (tool/get-tool-defs :type :agent) :memory-agent)
          agent-tools (get-in d [:meta :agent-tools])
          ids         (set (map (comp :id meta deref) (:tools agent-tools)))]
      (is (contains? ids :memory$verify-fact)))))
