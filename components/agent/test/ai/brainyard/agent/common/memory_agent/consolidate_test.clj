;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.memory-agent.consolidate-test
  "Phase 4 — consolidate + purge surface.

   Covers:
   - LlmReducer signature compiles with the expected I/O.
   - `memory$llm-consolidate` tool dispatches through chain-of-thought
     and surfaces facts/errors (stubbed LLM).
   - `memory$purge-plan` produces the orphan / stale candidate list
     against a populated in-memory DB.
   - Instruction includes the :consolidate and :purge per-op playbooks
     with the right anchors so the LLM can find them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.memory-agent]
            [ai.brainyard.agent.common.memory-agent.commands :as ma-cmds]
            [ai.brainyard.agent.common.memory-agent.signatures :as ma-sig]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.memory.core.manager :as manager]
            [ai.brainyard.memory.interface :as mem])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ============================================================================
;; Stub agent — mirrors essence-test
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

;; ============================================================================
;; Fixtures
;; ============================================================================

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
;; LlmReducer signature
;; ============================================================================

(deftest llm-reducer-signature-test
  (testing "LlmReducer is compiled with dspy metadata"
    (is (some? ma-sig/LlmReducer))
    (is (:dspy/signature (meta #'ma-sig/LlmReducer))))

  (testing "input keys cover episodes + window + existing-l3-hits + user"
    (let [iks (set (:input-keys ma-sig/LlmReducer))]
      (is (contains? iks :episodes))
      (is (contains? iks :window-desc))
      (is (contains? iks :existing-l3-hits))
      (is (contains? iks :user-id))))

  (testing "outputs declare :facts"
    (is (contains? (set (:output-keys ma-sig/LlmReducer)) :facts)))

  (testing "instructions surface the density rule and the empty-default"
    (let [instr (str (:instructions ma-sig/LlmReducer))]
      (is (str/includes? instr "HIGH INFORMATION DENSITY"))
      (is (or (str/includes? instr "AT MOST 5")
              (str/includes? instr "at most 5")))
      (is (str/includes? instr "Empty output is fine")))))

;; ============================================================================
;; memory$llm-consolidate
;; ============================================================================

(deftest llm-consolidate-registered-test
  (testing "memory$llm-consolidate is in the roster + the guard set"
    (is (contains? ma-cmds/all-tool-ids :memory$llm-consolidate))
    (is (contains? ma-cmds/write-guarded-tools "memory$llm-consolidate"))))

(deftest llm-consolidate-happy-path-test
  (testing "tool returns Malli-validated facts when chain-of-thought succeeds"
    (let [agent (make-stub :memory-agent/test *mm* "s1")
          stub-facts [{:content "deploy uses prod scripts"
                       :kind "fact"
                       :tags ["deploy"]
                       :confidence 0.85
                       :source-episode-ids ["e1" "e2"]
                       :supersedes-fact-ids []}]]
      (with-redefs [clj-llm/chain-of-thought
                    (fn [sig inputs & _]
                      (is (= ma-sig/LlmReducer sig))
                      (is (vector? (:episodes inputs)))
                      (is (= "session s1" (:window-desc inputs)))
                      {:outputs {:facts stub-facts}
                       :reasoning "two episodes agreed on the script"})]
        (proto/with-agent agent
          (let [r (ma-cmds/memory$llm-consolidate
                   :episodes [{:id "e1" :content "ran scripts/deploy.sh"}
                              {:id "e2" :content "ran scripts/deploy.sh again"}]
                   :window-desc "session s1"
                   :existing-l3-hits ""
                   :user-id (:user-id *mm*))]
            (is (= stub-facts (:facts r)))
            (is (string? (:reasoning r)))
            (is (nil? (:error r)))))))))

(deftest llm-consolidate-error-surface-test
  (testing "chain-of-thought exception surfaces as :error"
    (let [agent (make-stub :memory-agent/test *mm* "s1")]
      (with-redefs [clj-llm/chain-of-thought
                    (fn [& _] (throw (ex-info "lm timeout" {})))]
        (proto/with-agent agent
          (let [r (ma-cmds/memory$llm-consolidate :episodes [])]
            (is (string? (:error r)))
            (is (str/includes? (:error r) "lm timeout"))))))))

;; ============================================================================
;; memory$purge-plan
;; ============================================================================

(deftest purge-plan-registered-test
  (testing "memory$purge-plan is registered + gated"
    (is (contains? ma-cmds/all-tool-ids :memory$purge-plan))
    (is (contains? ma-cmds/write-guarded-tools "memory$purge-plan"))))

(deftest purge-plan-empty-db-test
  (testing "fresh DB → zero counts everywhere"
    (proto/with-agent (make-stub :memory-agent/test *mm* "s-live")
      (with-redefs [ma-cmds/live-sessions-on-disk (constantly #{})]
        (let [r (ma-cmds/memory$purge-plan)]
          (is (nil? (:error r)))
          (is (= [] (:l2-orphan-sessions r)))
          (is (zero? (get-in r [:counts :l2-orphan-sessions])))
          (is (zero? (get-in r [:counts :l2-orphan-episodes])))
          (is (zero? (get-in r [:counts :l3-stale])))
          (is (= 500 (:cap r)))
          (is (= 60  (:stale-days r))))))))

(deftest purge-plan-orphan-session-detection-test
  (testing "session-ids in DB but absent from registry/disk are marked orphan"
    (let [user-id (:user-id *mm*)]
      ;; Two L2 episodes — one in the live session, one in a stale session.
      (mem/write-entry *mm* :l2
                       {:kind :observation :content "live work"
                        :session-id "s-live" :user-id user-id})
      (mem/write-entry *mm* :l2
                       {:kind :observation :content "old work"
                        :session-id "s-old" :user-id user-id})
      (proto/with-agent (make-stub :memory-agent/test *mm* "s-live")
        ;; Stub agents aren't in the global !agent-registry, so we
        ;; stub the registry / disk lookups to simulate "s-live is
        ;; live, s-old is orphan".
        (with-redefs [ma-cmds/live-sessions-on-disk     (constantly #{})
                      ma-cmds/live-sessions-in-registry (constantly #{"s-live"})]
          (let [r       (ma-cmds/memory$purge-plan)
                orphans (set (:l2-orphan-sessions r))]
            (is (contains? orphans "s-old"))
            (is (not (contains? orphans "s-live"))
                "the live session must not appear as orphan")
            (is (= 1 (count (:l2-orphan-episodes r))))
            (is (= "old work" (-> r :l2-orphan-episodes first :content-snippet)))))))))

(deftest purge-plan-disk-sessions-elide-orphans-test
  (testing "sessions present on disk are NOT orphan even when no agent is registered"
    (let [user-id (:user-id *mm*)]
      (mem/write-entry *mm* :l2
                       {:kind :observation :content "x"
                        :session-id "s-on-disk" :user-id user-id})
      (proto/with-agent (make-stub :memory-agent/test *mm* "s-live")
        (with-redefs [ma-cmds/live-sessions-on-disk
                      (constantly #{"s-on-disk"})]
          (let [r (ma-cmds/memory$purge-plan)]
            (is (not (contains? (set (:l2-orphan-sessions r)) "s-on-disk"))
                "on-disk sessions are still live — operator may reopen them")))))))

(deftest purge-plan-cap-test
  (testing ":cap arg flows through and is echoed in the result"
    (proto/with-agent (make-stub :memory-agent/test *mm* "s-live")
      (with-redefs [ma-cmds/live-sessions-on-disk (constantly #{})]
        (let [r (ma-cmds/memory$purge-plan :cap 17 :stale-days 7)]
          (is (= 17 (:cap r)))
          (is (= 7  (:stale-days r))))))))

;; ============================================================================
;; Instruction anchors
;; ============================================================================

(deftest instruction-consolidate-purge-anchors-test
  (let [d  (get (tool/get-tool-defs :type :agent) :memory-agent)
        ix (get-in d [:meta :instruction])]
    (testing ":op :consolidate playbook present"
      (is (str/includes? ix "### :op :consolidate"))
      (is (str/includes? ix "memory$llm-consolidate"))
      (is (str/includes? ix "supersedes-fact-ids"))
      (is (str/includes? ix "memory$keep!")))

    (testing ":op :purge playbook present"
      (is (str/includes? ix "### :op :purge"))
      (is (str/includes? ix "memory$purge-plan"))
      (is (str/includes? ix ":dry-run?"))
      (is (str/includes? ix "pending/verify-queue.edn")))))

(deftest tool-context-mentions-phase4-tools-test
  (let [d  (get (tool/get-tool-defs :type :agent) :memory-agent)
        tc (get-in d [:meta :tool-context])]
    (is (str/includes? tc "memory$llm-consolidate"))
    (is (str/includes? tc "memory$purge-plan"))))

;; ============================================================================
;; Agent roster — Phase 4 tools land
;; ============================================================================

(deftest agent-roster-includes-phase4-tools-test
  (testing "memory-agent's declared roster includes the new tools"
    (let [d           (get (tool/get-tool-defs :type :agent) :memory-agent)
          agent-tools (get-in d [:meta :agent-tools])
          ids         (set (map (comp :id meta deref) (:tools agent-tools)))]
      (is (contains? ids :memory$llm-consolidate))
      (is (contains? ids :memory$purge-plan)))))

;; ============================================================================
;; Sub-LM default
;; ============================================================================

(deftest sub-lm-default-test
  (testing "memory-agent defagent meta carries the sub-LM default"
    (let [d (get (tool/get-tool-defs :type :agent) :memory-agent)]
      (is (= "claude-code:sonnet" (get-in d [:meta :sub-lm-config]))))))
