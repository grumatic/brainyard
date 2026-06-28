;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.memory-agent.essence-test
  "Phase 3 — essence capture surface.

   Covers:
   - EssenceExtraction signature compiles and carries the expected
     output schema.
   - `memory$essence-extract` tool dispatches through chain-of-thought
     and validates output (uses with-redefs to stub clj-llm).
   - `consolidation-cadence-handler` counts turns and fires (or elides)
     the batch reducer on `:agent.ask/post` based on the eligibility
     predicate (memory-agent self-skip, non-root skip, flag off skip)
     and the every-Nth-turn cadence."
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
;; consolidation-cadence-handler — :agent.ask/post
;; ============================================================================

(defn- reset-counters! []
  (reset! @#'ma-hooks/!turn-counters {}))

(deftest consolidation-eligible-cases-test
  (testing "memory-agent never consolidates on its own turn"
    (let [ag (make-stub :memory-agent/abc
                        :config {:enable-memory-consolidation true})]
      (is (false? (boolean (ma-hooks/consolidation-eligible? ag))))))

  (testing "sub-agents (with :parent-agent) are not eligible — the root is"
    (let [parent (make-stub :coact-agent/root)
          child  (make-stub :coact-agent/child
                            :config {:enable-memory-consolidation true}
                            :parent-agent parent)]
      (is (false? (boolean (ma-hooks/consolidation-eligible? child))))))

  (testing "flag off → not eligible even on a root coact-agent"
    (let [ag (make-stub :coact-agent/root
                        :config {:enable-memory-consolidation false})]
      (is (false? (boolean (ma-hooks/consolidation-eligible? ag))))))

  (testing "flag on + root coact-agent → eligible"
    (let [ag (make-stub :coact-agent/root
                        :config {:enable-memory-consolidation true})]
      (is (true? (boolean (ma-hooks/consolidation-eligible? ag)))))))

(deftest consolidation-cadence-fires-every-n-test
  (testing "handler fires the reducer only on every Nth eligible turn"
    (reset-counters!)
    (let [fires (atom 0)
          root  (make-stub :coact-agent/root
                           :session-id "s-cadence"
                           :config {:enable-memory-consolidation true
                                    :memory-consolidate-every-n-turns 3})]
      (with-redefs [ma-hooks/run-consolidation! (fn [_] (swap! fires inc) {:produced 0})]
        ;; 6 completed turns at N=3 → fire on turn 3 and turn 6.
        (dotimes [_ 6]
          (ma-hooks/consolidation-cadence-handler {:agent root}))
        (Thread/sleep 150))
      (is (= 2 @fires) "reducer ran exactly twice across 6 turns at N=3"))))

(deftest consolidation-cadence-elides-when-off-test
  (testing "flag off → counter untouched, reducer never runs"
    (reset-counters!)
    (let [fires (atom 0)
          root  (make-stub :coact-agent/root
                           :session-id "s-off"
                           :config {:enable-memory-consolidation false
                                    :memory-consolidate-every-n-turns 1})]
      (with-redefs [ma-hooks/run-consolidation! (fn [_] (swap! fires inc) nil)]
        (dotimes [_ 5]
          (ma-hooks/consolidation-cadence-handler {:agent root}))
        (Thread/sleep 100))
      (is (= 0 @fires))
      (is (nil? (get @@#'ma-hooks/!turn-counters "s-off"))))))

(deftest consolidation-cadence-handler-registered-test
  (testing "consolidation-cadence hook is registered on :agent.ask/post at namespace load"
    ;; Re-install in case other tests reset the registry.
    (ma-hooks/install-consolidation-cadence!)
    (let [entries (hooks/list-hooks :agent.ask/post)
          ids     (set (map :id entries))]
      (is (contains? ids :ai.brainyard.agent.common.memory-agent.hooks/consolidation-cadence)))))

;; ============================================================================
;; session-end-flush-handler — :agent.instance/closed
;; ============================================================================

(deftest session-end-flush-fires-on-eligible-root-test
  (testing "root close with counted turns → final consolidation runs, counter cleared"
    (reset-counters!)
    (let [fires (atom 0)
          root  (make-stub :coact-agent/root
                           :session-id "s-end"
                           :config {:enable-memory-consolidation true})]
      ;; Simulate two cadence ticks having counted turns for this session.
      (swap! @#'ma-hooks/!turn-counters assoc "s-end" 5)
      (with-redefs [ma-hooks/run-consolidation! (fn [_] (swap! fires inc) {:produced 1})]
        (ma-hooks/session-end-flush-handler {:agent root})
        (Thread/sleep 100))
      (is (= 1 @fires) "final consolidation ran once on root close")
      (is (nil? (get @@#'ma-hooks/!turn-counters "s-end")) "session counter cleared"))))

(deftest session-end-flush-skips-when-no-turns-test
  (testing "root close with zero counted turns → no consolidation, counter still cleared"
    (reset-counters!)
    (let [fires (atom 0)
          root  (make-stub :coact-agent/root
                           :session-id "s-empty"
                           :config {:enable-memory-consolidation true})]
      (with-redefs [ma-hooks/run-consolidation! (fn [_] (swap! fires inc) nil)]
        (ma-hooks/session-end-flush-handler {:agent root})
        (Thread/sleep 50))
      (is (= 0 @fires)))))

(deftest session-end-flush-skips-when-off-test
  (testing "flag off → no flush even on a root close"
    (reset-counters!)
    (let [fires (atom 0)
          root  (make-stub :coact-agent/root
                           :session-id "s-off2"
                           :config {:enable-memory-consolidation false})]
      (swap! @#'ma-hooks/!turn-counters assoc "s-off2" 9)
      (with-redefs [ma-hooks/run-consolidation! (fn [_] (swap! fires inc) nil)]
        (ma-hooks/session-end-flush-handler {:agent root})
        (Thread/sleep 50))
      (is (= 0 @fires)))))

(deftest session-end-flush-skips-sub-agent-test
  (testing "sub-agent (with :parent-agent) close → no flush; the root handles it"
    (reset-counters!)
    (let [fires  (atom 0)
          parent (make-stub :coact-agent/root)
          child  (make-stub :coact-agent/child
                            :session-id "s-sub"
                            :config {:enable-memory-consolidation true}
                            :parent-agent parent)]
      (swap! @#'ma-hooks/!turn-counters assoc "s-sub" 4)
      (with-redefs [ma-hooks/run-consolidation! (fn [_] (swap! fires inc) nil)]
        (ma-hooks/session-end-flush-handler {:agent child})
        (Thread/sleep 50))
      (is (= 0 @fires)))))

(deftest session-end-flush-handler-registered-test
  (testing "session-end-flush hook is registered on :agent.instance/closed at namespace load"
    (ma-hooks/install-session-end-flush!)
    (let [entries (hooks/list-hooks :agent.instance/closed)
          ids     (set (map :id entries))]
      (is (contains? ids :ai.brainyard.agent.common.memory-agent.hooks/session-end-flush)))))
