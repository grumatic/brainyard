;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.event-agent-test
  "Tests for event-agent (docs/design/event-agent-design.md §10).

   Two surfaces (the third, §10.3 real-LLM e2e, is manual / CI-opt-in and lives
   outside this deterministic suite):

   1. STRUCTURAL — registration, inherited CoAct bt-factory, the three
      command families (event$*/reaction$*/watch$*) bound (positive) and the
      design's exclusions (negative: NO schedule$* time-jobs — those are
      schedule-agent's; NO config-write — gates are config-agent's; NO
      clone-self), schema shape, and the instruction / tool-context anchors
      that carry the design's non-negotiable guidances (read sweep, gate
      outcome, blast radius, config-agent hand-off). Mirrors config_agent_test /
      schedule_agent_test.

   2. COMMAND PASS-THROUGH — the design §10.1 'command pass-through' check,
      driven hermetically (no LLM): the event$*/reaction$*/watch$* commands the
      agent binds behave as the instruction promises — an event declares +
      reads back, a reaction persists even with the gate off (with the inert
      note), and a watch lands in the scheduler store surfaced by watch$list but
      HIDDEN from schedule$list (the core event-agent / schedule-agent boundary,
      §5/§11).

   The event/reaction/watch engines themselves are covered by events_test.clj /
   reactor_test.clj / watch_test.clj; this suite does not re-test them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [ai.brainyard.agent.common.coact-agent]
            [ai.brainyard.agent.common.event-agent]
            [ai.brainyard.agent.common.events :as events]
            [ai.brainyard.agent.common.reactor :as reactor]
            [ai.brainyard.agent.common.schedule :as schedule]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.tool :as tool]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- agent-def []
  (get (tool/get-tool-defs :type :agent) :event-agent))

(defn- tool-ids []
  (set (map (comp :id meta deref)
            (get-in (agent-def) [:meta :agent-tools :tools]))))

;; ============================================================================
;; 1. STRUCTURAL
;; ============================================================================

(deftest registration-test
  (testing "event-agent is registered in the unified tool registry"
    (let [d (agent-def)]
      (is (some? d))
      (is (= :event-agent (:id d)))
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
  (testing "binds all three event-subsystem command families"
    (let [ids (tool-ids)]
      ;; events
      (is (contains? ids :event$define))
      (is (contains? ids :event$list))
      (is (contains? ids :event$remove))
      (is (contains? ids :event$emit))
      ;; reactions
      (is (contains? ids :reaction$add))
      (is (contains? ids :reaction$list))
      (is (contains? ids :reaction$remove))
      (is (contains? ids :reaction$enable))
      (is (contains? ids :reaction$disable))
      ;; watches
      (is (contains? ids :watch$add))
      (is (contains? ids :watch$list))
      (is (contains? ids :watch$remove))
      (is (contains? ids :watch$run-now))))

  (testing "inherits the read-side gate + synthesis + cross-agent dispatch"
    (let [ids (tool-ids)]
      ;; gates are READ here (agent-runtime$config) …
      (is (contains? ids :agent-runtime$config))
      ;; … cross-agent dispatch to config-agent / explore-agent / edit-agent …
      (is (contains? ids :call-tool))
      ;; … plus the flat sub-LLM and dossier write primitives.
      (is (contains? ids :query$llm))
      (is (contains? ids :write-file)))))

(deftest agent-tools-negative
  (let [ids (tool-ids)]
    (testing "time-triggered prompt jobs are schedule-agent's — NO schedule$* bound"
      (is (not (contains? ids :schedule$add)))
      (is (not (contains? ids :schedule$run-now)))
      (is (not (contains? ids :schedule$run-due))))

    (testing "the gate flags are config-agent's — NO config-write bound"
      (is (not (contains? ids :config$apply)))
      (is (not (contains? ids :config$revert))))

    (testing "no clone-self recursion"
      (is (not (contains? ids :query$clone))))))

(deftest instruction-anchors
  (testing "instruction carries the design's non-negotiable guidances"
    (let [instr (get-in (agent-def) [:meta :instruction])]
      (is (re-find #"FIVE CAPABILITY KINDS" instr))
      ;; the three conflated vocabularies distinguished
      (is (re-find #"reaction\$add" instr))
      (is (re-find #"watch\$add" instr))
      (is (re-find #"event\$emit" instr))
      ;; gate outcome stated plainly + blast radius named
      (is (re-find #"(?i)gate" instr))
      (is (re-find #"(?i)blast radius" instr))
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
      (is (re-find #"event\$define" tc))
      (is (re-find #"reaction\$add" tc))
      (is (re-find #"watch\$run-now" tc))
      (is (re-find #"agent-runtime\$config" tc))
      (is (re-find #"call-tool \"config-agent\"" tc))
      (is (re-find #"FORBIDDEN" tc)))))

;; ============================================================================
;; 2. COMMAND PASS-THROUGH (hermetic — no LLM)
;; ============================================================================

(def ^:dynamic *pdir* nil)

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "event-agent-test-" (System/currentTimeMillis) "-" (rand-int 100000)))]
    (.mkdirs dir)
    (binding [*pdir* (.getPath dir)]
      (try (f)
           (finally
             ;; event$define registers on the global bus — undo it so state
             ;; can't leak across tests / REPL reruns (mirrors events_test).
             (hooks/reset-hooks!)
             (hooks/unregister-event! :order/shipped)
             (doseq [^java.io.File x (reverse (file-seq dir))] (.delete x)))))))

(use-fixtures :each temp-dir-fixture)

(defmacro ^:private with-scratch
  "Run body with project-dir pinned at *pdir* and every gate reporting OFF (so
   nothing fires unattended and reaction$add exercises its inert-note path)."
  [& body]
  `(with-redefs [config/project-dir (fn ([] *pdir*) ([_#] *pdir*))
                 config/get-config   (fn ([_k#] false) ([_a# _k#] false))]
     ~@body))

(deftest event-define-list-round-trip
  (with-scratch
    (testing "event$define declares a named event, event$list reads it back"
      (let [res (events/event$define :name "order/shipped"
                                     :payload-schema [:map [:order-id :string]]
                                     :desc "an order shipped")]
        (is (= :order/shipped (:defined res)))
        (let [declared (:events (events/event$list))
              names    (set (map :name declared))]
          (is (contains? names :order/shipped)))
        (testing "event$remove drops the declaration"
          (is (= :order/shipped (:removed (events/event$remove :name "order/shipped"))))
          (is (empty? (:events (events/event$list)))))))))

(deftest reaction-add-persists-even-with-gate-off
  (with-scratch
    (testing "reaction$add stores the rule and flags it inert while the gate is off"
      (let [res (reactor/reaction$add
                 :on "order/shipped"
                 :do {:as :context :text "Order {{order-id}} shipped — dashboard may be stale."}
                 :title "on-ship-refresh")
            id  (:id res)]
        (is (string? id))
        (is (= :order/shipped (:on res)))
        (is (re-find #"(?i)reactions are off" (:note res))
            "gate-off write must carry the inert note (design principle 4)")
        (testing "reaction$list surfaces it"
          (let [ids (map :id (:reactions (reactor/reaction$list)))]
            (is (some #{id} ids))))
        (testing "reaction$remove deletes it"
          (is (= id (:removed (reactor/reaction$remove :id id))))
          (is (empty? (:reactions (reactor/reaction$list)))))))))

(deftest watch-surfaced-by-watch-list-hidden-from-schedule-list
  ;; The core event-agent / schedule-agent boundary (design §5, §11): a watch
  ;; lives in the scheduler store but is NOT a time-job, so watch$list surfaces
  ;; it while schedule$list must hide it.
  (with-scratch
    (let [res (schedule/watch$add :probe {:type :shell :cmd "echo 1"}
                                  :when {:op :increased}
                                  :emit "order/shipped" :every 60000
                                  :title "orders-watch")
          wid (:id res)
          watch-ids (set (map :id (:watches (schedule/watch$list))))
          job-ids   (set (map :id (:schedules (schedule/schedule$list))))]
      (is (string? wid))
      (is (contains? watch-ids wid) "watch$list surfaces the watch")
      (is (not (contains? job-ids wid)) "schedule$list must HIDE the watch")
      (is (empty? (set/intersection watch-ids job-ids))
          "no watch leaks into the time-job view"))))
