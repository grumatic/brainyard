;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.memory-agent.essence-test
  "Phase 3 — essence capture surface.

   Covers:
   - EssenceExtraction signature compiles and carries the expected
     output schema.
   - `memory$essence-extract` tool dispatches through chain-of-thought
     and validates output (uses with-redefs to stub clj-llm).
   - `essence-capture-handler` fires (or elides) on `:agent.ask/post`
     based on the eligibility predicate (memory-agent self-skip,
     non-root skip, flag off skip)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.memory-agent.commands :as ma-cmds]
            [ai.brainyard.agent.common.memory-agent.hooks :as ma-hooks]
            [ai.brainyard.agent.common.memory-agent.signatures :as ma-sig]
            [ai.brainyard.agent.core.config :as core-config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.clj-llm.interface :as clj-llm]))

;; ============================================================================
;; Stub agent — mirrors the one in commands-test
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
  "Test stub mirroring setup-agent's routing: schema-key entries in
   `:config` land in st-memory-init :config; non-schema entries land in
   the agent record's :config slot."
  [agent-id & {:keys [user-id session-id config st-mem-config parent-agent total-turns
                      ;; Legacy alias retained for callers still using
                      ;; the pre-Phase-2 :runtime-config kwarg.
                      runtime-config]
               :or {user-id "user-x" session-id "s-x"
                    config {} st-mem-config {} runtime-config {}}}]
  (let [schema-keys core-config/config-keys
        cfg-schema-half    (select-keys config schema-keys)
        cfg-nonschema-half (apply dissoc config schema-keys)
        st-mem-config-full (merge cfg-schema-half st-mem-config runtime-config)]
    (->StubAgent agent-id
                 (atom {:config         cfg-nonschema-half
                        :st-memory-init (atom {:config st-mem-config-full})
                        :runtime        (when parent-agent
                                          {:parent-agent parent-agent})})
                 (atom {:user-id user-id :session-id session-id
                        :total-turns (or total-turns 0)}))))

;; ============================================================================
;; EssenceExtraction signature
;; ============================================================================

(deftest essence-extraction-signature-test
  (testing "signature is compiled and carries the dspy metadata"
    (is (some? ma-sig/EssenceExtraction))
    (is (:dspy/signature (meta #'ma-sig/EssenceExtraction))))

  (testing "input keys match the documented shape"
    (let [iks (set (:input-keys ma-sig/EssenceExtraction))]
      (is (contains? iks :turn-summary))
      (is (contains? iks :turn-messages))
      (is (contains? iks :recent-episodes))
      (is (contains? iks :user-id))))

  (testing "outputs declare :essences"
    (let [oks (set (:output-keys ma-sig/EssenceExtraction))]
      (is (contains? oks :essences))))

  (testing "instructions surface the cap (\"three\") and the empty-default"
    (let [instr (str (:instructions ma-sig/EssenceExtraction))]
      (is (str/includes? instr "THREE"))
      (is (str/includes? instr "Empty output is the COMMON case"))
      (is (str/includes? instr "fact"))
      (is (str/includes? instr "observation"))
      (is (str/includes? instr "user-context")))))

;; ============================================================================
;; memory$essence-extract — tool dispatch
;; ============================================================================

(deftest essence-extract-registered-test
  (testing "memory$essence-extract is in the roster + the guard set"
    (is (contains? ma-cmds/all-tool-ids :memory$essence-extract))
    (is (contains? ma-cmds/write-guarded-tools "memory$essence-extract"))))

(deftest essence-extract-happy-path-test
  (testing "tool returns Malli-validated essences when chain-of-thought succeeds"
    (let [agent (make-stub :memory-agent/test :user-id "alice")
          stub-essences [{:kind "fact"
                          :content "User prefers polylith layout"
                          :tags ["arch"]
                          :confidence 0.9
                          :source-ids ["ep1"]
                          :rationale "user said so explicitly"}]]
      (with-redefs [clj-llm/chain-of-thought
                    (fn [sig inputs & _]
                      (is (= ma-sig/EssenceExtraction sig))
                      (is (= "summary text" (:turn-summary inputs)))
                      (is (= "alice" (:user-id inputs)))
                      {:outputs {:essences stub-essences}
                       :reasoning "spotted an explicit preference"})]
        (proto/with-agent agent
          (let [r (ma-cmds/memory$essence-extract
                   :turn-summary "summary text"
                   :turn-messages "a\nb"
                   :recent-episodes "ep1 fact x"
                   :user-id "alice")]
            (is (= stub-essences (:essences r)))
            (is (= "spotted an explicit preference" (:reasoning r)))
            (is (nil? (:error r)))))))))

(deftest essence-extract-empty-output-test
  (testing "empty essences vector flows through cleanly (most turns)"
    (let [agent (make-stub :memory-agent/test)]
      (with-redefs [clj-llm/chain-of-thought
                    (fn [_ _ & _] {:outputs {:essences []}
                                   :reasoning "nothing worth lifting"})]
        (proto/with-agent agent
          (let [r (ma-cmds/memory$essence-extract :turn-summary "")]
            (is (= [] (:essences r)))
            (is (nil? (:error r)))))))))

(deftest essence-extract-error-surface-test
  (testing "chain-of-thought exceptions surface as :error, not as throws"
    (let [agent (make-stub :memory-agent/test)]
      (with-redefs [clj-llm/chain-of-thought
                    (fn [& _] (throw (ex-info "llm down" {})))]
        (proto/with-agent agent
          (let [r (ma-cmds/memory$essence-extract :turn-summary "x")]
            (is (string? (:error r)))
            (is (str/includes? (:error r) "llm down"))))))))

;; ============================================================================
;; essence-capture-handler — :agent.ask/post
;; ============================================================================

(deftest essence-capture-eligible-cases-test
  (testing "memory-agent never triggers essence on its own turn"
    (let [ag (make-stub :memory-agent/abc
                        :config {:enable-memory-essence true})]
      ;; The handler returns nil and dispatches no future.
      (is (nil? (ma-hooks/essence-capture-handler {:agent ag})))))

  (testing "sub-agents (with :parent-agent) do not fire — the root does"
    (let [parent (make-stub :coact-agent/root)
          child  (make-stub :coact-agent/child
                            :config {:enable-memory-essence true}
                            :parent-agent parent)]
      (is (nil? (ma-hooks/essence-capture-handler {:agent child})))))

  (testing "flag off → no fire even on a root coact-agent"
    (let [ag (make-stub :coact-agent/root
                        :config {:enable-memory-essence false})]
      (is (nil? (ma-hooks/essence-capture-handler {:agent ag}))))))

(deftest essence-capture-fires-on-eligible-root-test
  (testing "eligible root coact-agent → handler dispatches the memory-agent tool inside a future"
    (let [called  (atom nil)
          root    (make-stub :coact-agent/root
                             :config {:enable-memory-essence true}
                             :user-id "alice" :session-id "s-42"
                             :total-turns 7)]
      ;; The handler dispatches through `tool/invoke-tool`. Stub matches
      ;; the (defn invoke-tool [id & {:as options}]) signature.
      (with-redefs [ai.brainyard.agent.core.tool/invoke-tool
                    (fn [id & {:as args}]
                      (reset! called {:id id :args args})
                      {:answer "ok"})]
        (ma-hooks/essence-capture-handler
         {:agent  root
          :input  "hello"
          :result {:answer "world"}})
        ;; Give the future a tick to run.
        (Thread/sleep 100))
      (is (some? @called) "invoke-tool was dispatched")
      (is (= :memory-agent (:id @called)))
      (is (= "essence"  (-> @called :args :op)))
      (is (= "s-42"     (-> @called :args :session-id)))
      (is (= 7          (-> @called :args :total-turns)))
      (is (string?      (-> @called :args :hint))))))

(deftest essence-capture-truncates-hint-test
  (testing "hint longer than 400 chars is truncated"
    (let [called (atom nil)
          root   (make-stub :coact-agent/root
                            :config {:enable-memory-essence true})
          huge   (apply str (repeat 1000 "x"))]
      (with-redefs [ai.brainyard.agent.core.tool/invoke-tool
                    (fn [_ & {:as args}] (reset! called args) {:answer "ok"})]
        (ma-hooks/essence-capture-handler
         {:agent  root
          :input  "q"
          :result {:answer huge}})
        (Thread/sleep 100))
      (is (= 400 (count (:hint @called)))))))

(deftest essence-capture-handler-registered-test
  (testing "essence-capture hook is registered on :agent.ask/post at namespace load"
    ;; Re-install in case other tests reset the registry.
    (ma-hooks/install-essence-capture!)
    (let [entries (hooks/list-hooks :agent.ask/post)
          ids     (set (map :id entries))]
      (is (contains? ids :ai.brainyard.agent.common.memory-agent.hooks/essence-capture)))))
