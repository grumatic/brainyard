;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.schedule-agent-test
  "Tests for schedule-agent (docs/design/schedule-agent-design.md §12).

   Two surfaces (the third, §12.3 real-LLM e2e, is manual / CI-opt-in and lives
   outside this deterministic suite):

   1. STRUCTURAL — registration, inherited CoAct bt-factory, the schedule$*
      tool roster (positive) and the design's exclusions (negative: NO watch$*,
      NO config-write, NO clone-self), schema shape, and the instruction /
      tool-context anchors that carry the design's non-negotiable guidances
      (session-bound firing caveat, plain-words cron, config-agent / event-agent
      hand-offs). Mirrors config_agent_test.clj.

   2. COMMAND PASS-THROUGH — the design §12.1 'command pass-through' check,
      driven hermetically (no LLM): invoking the schedule$* commands the agent
      binds behaves as the instruction promises the user — a cron job lands on
      disk with a computed next-fire, schedule$list EXCLUDES watches (the core
      schedule-agent / event-agent boundary, §5/§11), run-now is a dry-run that
      does not advance next-fire, and remove drops the spec.

   The scheduler engine + command guards themselves are covered by
   schedule_test.clj; this suite does not re-test them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [ai.brainyard.agent.common.coact-agent]
            [ai.brainyard.agent.common.schedule-agent]
            [ai.brainyard.agent.common.schedule :as sched]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :as tool]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- agent-def []
  (get (tool/get-tool-defs :type :agent) :schedule-agent))

(defn- tool-ids []
  (set (map (comp :id meta deref)
            (get-in (agent-def) [:meta :agent-tools :tools]))))

;; ============================================================================
;; 1. STRUCTURAL
;; ============================================================================

(deftest registration-test
  (testing "schedule-agent is registered in the unified tool registry"
    (let [d (agent-def)]
      (is (some? d))
      (is (= :schedule-agent (:id d)))
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
  (testing "binds the full schedule$* job surface"
    (let [ids (tool-ids)]
      (is (contains? ids :schedule$add))
      (is (contains? ids :schedule$list))
      (is (contains? ids :schedule$remove))
      (is (contains? ids :schedule$enable))
      (is (contains? ids :schedule$disable))
      (is (contains? ids :schedule$run-now))
      (is (contains? ids :schedule$run-due))))

  (testing "inherits the read-side gate + synthesis + cross-agent dispatch"
    (let [ids (tool-ids)]
      ;; The :enable-scheduler gate is READ here (agent-runtime$config) …
      (is (contains? ids :agent-runtime$config))
      ;; … cross-agent dispatch to config-agent / event-agent rides call-tool …
      (is (contains? ids :call-tool))
      ;; … plus the flat sub-LLM and discovery primitives.
      (is (contains? ids :query$llm))
      (is (contains? ids :list-tools))
      (is (contains? ids :write-file)))))

(deftest agent-tools-negative
  (let [ids (tool-ids)]
    (testing "watches are event-agent's — NO watch$* is bound (design §5/§11)"
      (is (not (contains? ids :watch$add)))
      (is (not (contains? ids :watch$list)))
      (is (not (contains? ids :watch$remove)))
      (is (not (contains? ids :watch$run-now))))

    (testing "the :enable-scheduler gate is config-agent's — NO config-write bound"
      (is (not (contains? ids :config$apply)))
      (is (not (contains? ids :config$revert))))

    (testing "no clone-self recursion"
      (is (not (contains? ids :query$clone))))))

(deftest instruction-anchors
  (testing "instruction carries the design's non-negotiable guidances"
    (let [instr (get-in (agent-def) [:meta :instruction])]
      (is (re-find #"SIX CAPABILITY KINDS" instr))
      ;; The firing model is a first-class caveat, stated on every write.
      (is (re-find #"(?i)session is open" instr))
      (is (re-find #"(?i)plain words" instr))
      ;; run-now is the dry-run to prove a job.
      (is (re-find #"schedule\$run-now" instr))
      ;; Hand-offs: gate → config-agent, condition-trigger → event-agent.
      (is (re-find #"config-agent" instr))
      (is (re-find #"event-agent" instr))
      ;; Per-conversation dossier + hard rules.
      (is (re-find #"(?i)dossier" instr))
      (is (re-find #"HARD RULES" instr))
      ;; Dossier is a HARD final-step contract, not advisory.
      (is (re-find #"FINAL-STEP CHECKLIST" instr))
      (is (re-find #"DOSSIER WRITTEN" instr))
      (is (re-find #"(?i)incomplete turn" instr)))))

(deftest tool-context-anchors
  (testing "tool-context names the commands + the cross-agent dispatch shape"
    (let [tc (get-in (agent-def) [:meta :tool-context])]
      (is (re-find #"schedule\$add" tc))
      (is (re-find #"schedule\$run-now" tc))
      (is (re-find #"schedule\$run-due" tc))
      (is (re-find #"agent-runtime\$config" tc))
      (is (re-find #"call-tool \"config-agent\"" tc))
      (is (re-find #"call-tool \"event-agent\"" tc))
      (is (re-find #"FORBIDDEN" tc)))))

;; ============================================================================
;; 2. COMMAND PASS-THROUGH (hermetic — no LLM)
;; ============================================================================

(def ^:dynamic *pdir* nil)

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "sched-agent-test-" (System/currentTimeMillis) "-" (rand-int 100000)))]
    (.mkdirs dir)
    (binding [*pdir* (.getPath dir)]
      (try (f)
           (finally (doseq [^java.io.File x (reverse (file-seq dir))] (.delete x)))))))

(use-fixtures :each temp-dir-fixture)

(defmacro ^:private with-scratch-scheduler
  "Run body with project-dir pinned at *pdir*, the scheduler ticker gated OFF
   (so nothing fires unattended during the test), and the job executor stubbed
   (so run-now never reaches a real LLM)."
  [& body]
  `(with-redefs [config/project-dir (fn ([] *pdir*) ([_#] *pdir*))
                 config/get-config   (fn ([_k#] false) ([_a# _k#] false))]
     (binding [sched/*execute-job* (fn [_spec#] {:answer "stub-output"})]
       ~@body)))

(deftest command-pass-through-add-list-remove
  (with-scratch-scheduler
    (testing "schedule$add lands a cron job on disk with a computed next-fire"
      (let [res (sched/schedule$add :prompt "Summarize yesterday's commits."
                                    :cron "0 9 * * 1-5" :title "weekday-standup")
            id  (:id res)]
        (is (string? id))
        (is (number? (:next-fire res)) "cron add computes a next-fire timestamp")
        (is (true? (:enabled res)))
        ;; spec.edn is persisted under the project-scoped store.
        (let [spec (sched/read-spec *pdir* id)]
          (is (= "0 9 * * 1-5" (:cron spec)))
          (is (= "coact-agent" (:agent spec)) "defaults to coact-agent")
          (is (= "file" (:sink spec)) "defaults to the file sink"))

        (testing "schedule$list surfaces the job"
          (let [ids (map :id (:schedules (sched/schedule$list)))]
            (is (some #{id} ids))))

        (testing "schedule$remove drops the spec"
          (is (= id (:removed (sched/schedule$remove :id id))))
          (is (nil? (sched/read-spec *pdir* id)))
          (is (empty? (:schedules (sched/schedule$list)))))))))

(deftest schedule-list-excludes-watches
  ;; The core schedule-agent / event-agent boundary (design §5, §10, §11):
  ;; a watch shares the store + ticker but is NOT a schedule job, so the
  ;; command the agent calls must never surface it.
  (with-scratch-scheduler
    (sched/schedule$add :prompt "run a report" :cron "0 * * * *" :title "hourly")
    (sched/watch$add :probe {:type :shell :cmd "echo 1"}
                     :emit "orders/grew" :every 60000 :title "orders-watch")
    (let [job-ids   (set (map :id (:schedules (sched/schedule$list))))
          watch-ids (set (map :id (:watches (sched/watch$list))))]
      (is (= 1 (count job-ids)) "schedule$list shows only the time-job")
      (is (= 1 (count watch-ids)) "the watch exists — in watch$list, not schedule$list")
      (is (empty? (set/intersection job-ids watch-ids))
          "no watch leaks into the schedule-agent's schedule$list view"))))

(deftest run-now-is-a-dry-run
  ;; The instruction promises schedule$run-now proves a job WITHOUT disturbing
  ;; its schedule (does not advance :next-fire). Verified through the bound command.
  (with-scratch-scheduler
    (let [{:keys [id next-fire]} (sched/schedule$add :prompt "prove me"
                                                     :cron "0 9 * * 1-5"
                                                     :title "dry-run-job")
          res  (sched/schedule$run-now :id id)
          spec (sched/read-spec *pdir* id)]
      (is (= :ok (:status res)) "stubbed executor reports success")
      (is (string? (:output res)) "run-log path returned")
      (is (= next-fire (:next-fire spec))
          "run-now must NOT advance :next-fire — it is the dry-run")
      (is (some? (:last-run spec)) "but the run IS recorded (last-run stamped)"))))
