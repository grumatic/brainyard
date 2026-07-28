;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.skill-distill-test
  "Tests for the skill-distillation self-improvement loop (R1):
   pre-filter, staging store, accept/reject review gate, the staging decision,
   and hook eligibility. No LLM calls — the scorer is stubbed."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [ai.brainyard.agent.common.skill-distill :as sd]
            [ai.brainyard.agent.common.background :as bg]
            [ai.brainyard.agent.common.skill-distill.proposals :as proposals]
            [ai.brainyard.agent.common.trajectory :as traj]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [clojure.java.io :as io]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def ^:dynamic *project-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "skill-distill-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (binding [*project-dir* (.getPath dir)]
      (try (f)
           (finally
             (doseq [^java.io.File x (reverse (file-seq dir))] (.delete x)))))))

(use-fixtures :each temp-dir-fixture)

(def sample-skill-md
  "---\nname: deploy-flow\ndescription: Deploy procedure\n---\n\n# Deploy flow\n\n1. Build\n2. Stage\n3. Release\n")

(defn- staged-names []
  (set (map :name (proposals/list-proposals *project-dir*))))

;; ============================================================================
;; Pre-filter
;; ============================================================================

(def multi-step-record
  {:success true
   :question "Set up the release"
   :answer "Done — released v1."
   :turn 3
   :iterations [{:n 1 :channel "tool" :tools [{:name "bash" :args {} :result "ok"}]}
                {:n 2 :channel "code" :code ["(build)"] :result ["built"]}
                {:n 3 :channel "none" :thought "summarize"}]})

(deftest worth-scoring-pre-filter
  (testing "a successful multi-step turn is worth scoring"
    (is (true? (sd/worth-scoring? multi-step-record))))

  (testing "trivial / ineligible turns are skipped without an LLM call"
    (are [record] (false? (sd/worth-scoring? record))
      nil
      {}
      ;; failed turn
      (assoc multi-step-record :success false)
      ;; blank answer
      (assoc multi-step-record :answer "  ")
      ;; only one action step (pure Q&A + a single tool)
      (assoc multi-step-record :iterations
             [{:n 1 :channel "tool" :tools [{:name "read"}]}
              {:n 2 :channel "none" :thought "answer"}])
      ;; reasoning-only, no actions
      (assoc multi-step-record :iterations
             [{:n 1 :channel "none" :thought "think"}
              {:n 2 :channel "none" :thought "answer"}]))))

;; A model capable enough to batch a whole procedure into ONE code block used to
;; be dropped by an iteration-level count — the most skill-worthy shape scoring
;; as a single step. Steps are counted INSIDE the block.

(def batched-record
  (assoc multi-step-record
         :iterations
         [{:n 1 :channel "code"
           :code ["root=$(git rev-parse --show-toplevel)\ncd \"$root\"\necho '=== (1) describe'\ngit describe --tags\necho '=== (2) tree'\ngit status --porcelain"]
           :output ["v0.4.0"]}
          {:n 2 :channel "none" :thought "answer"}]))

(deftest code-steps-counting
  (testing "one step per top-level statement"
    (is (= 3 (sd/code-steps "cd /tmp\nls -la\nwc -l *.clj"))))

  (testing "indented continuations and bodies are not separate steps"
    (is (= 1 (sd/code-steps "(let [x 1]\n  (inc x))")))
    (is (= 1 (sd/code-steps "for f in *.clj; do\n  wc -l \"$f\"\ndone"))))

  (testing "blank lines, comments and bare closers do not count"
    (is (= 2 (sd/code-steps "# audit\ncd /tmp\n\n;; note\nls\n)")))
    (is (= 0 (sd/code-steps "   ")))
    (is (= 0 (sd/code-steps nil))))

  (testing "a non-blank block is always at least one step"
    (is (= 1 (sd/code-steps "  (+ 1 2)")))))

(deftest iteration-and-turn-steps
  (testing "every tool call is a step"
    (is (= 2 (sd/iteration-steps {:channel "tool"
                                  :tools [{:name "read"} {:name "bash"}]}))))

  (testing "an action iteration never scores zero"
    (is (= 1 (sd/iteration-steps {:channel "tool" :tools [] :code []}))))

  (testing "pure reasoning scores zero"
    (is (= 0 (sd/iteration-steps {:channel "none" :thought "think"}))))

  (testing "turn steps sum across iterations"
    (is (= 2 (sd/turn-steps multi-step-record)))
    (is (= 6 (sd/turn-steps batched-record)))))

(deftest worth-scoring-counts-steps-within-an-iteration
  (testing "a procedure batched into ONE code block still qualifies"
    (is (true? (sd/worth-scoring? batched-record))))

  (testing "a single-command block is still filtered out"
    (is (false? (sd/worth-scoring?
                 (assoc multi-step-record :iterations
                        [{:n 1 :channel "code" :code ["git status --porcelain"]}
                         {:n 2 :channel "none" :thought "answer"}]))))))

;; ============================================================================
;; Batching (:at-cadence mode)
;; ============================================================================

(def ^:private window-records
  [(assoc multi-step-record :turn 1 :question "step one")
   (assoc multi-step-record :turn 2 :question "step two")
   (assoc multi-step-record :turn 3 :question "step three")])

(deftest batch-window-render
  (testing "each turn is headed by its number and question"
    (let [txt (sd/batch->text window-records)]
      (is (re-find #"=== turn 1 — step one ===" txt))
      (is (re-find #"=== turn 3 — step three ===" txt))
      (is (re-find #"iteration 1 \[tool\]" txt))))

  (testing "the window stays bounded however many turns accumulate"
    (let [fat  (assoc multi-step-record
                      :turn 1
                      :iterations [{:n 1 :channel "code"
                                    :code [(apply str (repeat 50000 "x"))]}])
          txt  (sd/batch->text (repeat 20 fat))]
      (is (<= (count txt) (+ sd/max-batch-chars
                             ;; per-turn floor dominates once the window is wide
                             (* 20 (+ sd/min-batch-turn-chars 200)))))
      (is (re-find #"\[turn truncated\]" txt))))

  (testing "a single-turn window still renders"
    (is (re-find #"=== turn 1 —" (sd/batch->text [(first window-records)])))))

(deftest batch-staging-provenance
  (testing "a staged batch proposal records every turn that fed it"
    (with-redefs [sd/score-batch (fn [_ _] {:reusable true :score 0.9
                                            :proposed-name "windowed-flow"
                                            :rationale "spans turns 1-3"
                                            :skill-md sample-skill-md})
                  sd/window-records (fn [_ _] window-records)]
      (is (= :staged (sd/run-batch! nil "sess-1" *project-dir* 0.7 [1 2 3])))
      (let [p (proposals/read-proposal *project-dir* "windowed-flow")]
        (is (= [1 2 3] (:turns (:meta p))) "provenance names the whole window")
        (is (= 3 (:turn (:meta p))) "and the window's last turn")
        (is (re-find #"batch of 3 turns" (:source-question (:meta p))))
        (is (= :distillation (:kind (:meta p)))))))

  (testing "an empty window is skipped without scoring"
    (let [scored? (atom false)]
      (with-redefs [sd/score-batch (fn [_ _] (reset! scored? true) nil)
                    sd/window-records (fn [_ _] [])]
        (is (= :no-window (sd/run-batch! nil "sess-1" *project-dir* 0.7 [9])))
        (is (false? @scored?) "no sub-LM call for a window that read back empty")))))

(deftest trajectory-text-render
  (testing "renders iteration markers and stays bounded"
    (let [txt (sd/trajectory->text multi-step-record)]
      (is (re-find #"iteration 1 \[tool\]" txt))
      (is (re-find #"tool bash" txt))
      (is (re-find #"code:" txt))
      (is (<= (count txt) (+ sd/max-trajectory-chars 64))))))

;; ============================================================================
;; Proposal store
;; ============================================================================

(deftest valid-name-guard
  (are [name ok?] (= ok? (proposals/valid-name? name))
    "deploy-flow" true
    "a" true
    "a1-b2" true
    "Deploy" false        ;; uppercase
    "1deploy" false       ;; leading digit
    "../escape" false     ;; traversal
    "has space" false
    "" false
    nil false))

(deftest write-read-list-delete-round-trip
  (testing "write rejects bad input"
    (is (:error (proposals/write-proposal! *project-dir* {:name "Bad" :skill-md sample-skill-md})))
    (is (:error (proposals/write-proposal! *project-dir* {:name "ok-name" :skill-md "  "}))))

  (testing "write → read → list → delete"
    (let [res (proposals/write-proposal!
               *project-dir*
               {:name "deploy-flow" :skill-md sample-skill-md
                :score 0.82 :rationale "reusable release recipe"
                :session "sess-1" :turn 3 :source-question "Set up the release"})]
      (is (= "deploy-flow" (:name res)))
      (is (nil? (:error res)))
      ;; read back
      (let [p (proposals/read-proposal *project-dir* "deploy-flow")]
        (is (= sample-skill-md (:skill-md p)))
        (is (= 0.82 (-> p :meta :score)))
        (is (= :distillation (-> p :meta :kind)))
        (is (number? (-> p :meta :created-ts))))
      ;; list
      (is (= #{"deploy-flow"} (staged-names)))
      ;; delete
      (is (true? (proposals/delete-proposal! *project-dir* "deploy-flow")))
      (is (empty? (staged-names)))
      (is (nil? (proposals/read-proposal *project-dir* "deploy-flow")))))

  (testing "list tolerates an empty/absent root"
    (is (= [] (proposals/list-proposals (str *project-dir* "/nope"))))))

;; ============================================================================
;; Accept / reject — the review gate
;; ============================================================================

(deftest accept-promotes-and-clears
  (proposals/write-proposal! *project-dir* {:name "deploy-flow" :skill-md sample-skill-md})
  (testing "accept calls skills$write :create with the drafted content, then clears staging"
    (let [calls (atom [])]
      (with-redefs [tool/invoke-tool (fn [id & {:as args}]
                                       (swap! calls conj (assoc args :id id))
                                       {:name (:skill-name args) :path "/skills/deploy-flow"})]
        (let [res (proposals/accept-proposal! *project-dir* "deploy-flow")]
          (is (true? (:accepted res)))
          (is (= 1 (count @calls)))
          (let [c (first @calls)]
            (is (= :skills$write (:id c)))
            (is (= "create" (:op c)))
            (is (= "deploy-flow" (:skill-name c)))
            (is (= sample-skill-md (:content c))))
          ;; staging dir cleared after promotion
          (is (empty? (staged-names))))))))

(deftest accept-keeps-proposal-on-create-failure
  (proposals/write-proposal! *project-dir* {:name "deploy-flow" :skill-md sample-skill-md})
  (with-redefs [tool/invoke-tool (fn [_ & _] {:error "disk full"})]
    (let [res (proposals/accept-proposal! *project-dir* "deploy-flow")]
      (is (= "disk full" (:error res)))
      ;; NOT cleared — user can retry
      (is (= #{"deploy-flow"} (staged-names))))))

(deftest accept-missing-proposal-errors
  (is (:error (proposals/accept-proposal! *project-dir* "ghost"))))

(deftest reject-discards
  (proposals/write-proposal! *project-dir* {:name "deploy-flow" :skill-md sample-skill-md})
  (is (= #{"deploy-flow"} (staged-names)))
  (is (true? (proposals/delete-proposal! *project-dir* "deploy-flow")))
  (is (empty? (staged-names))))

;; ============================================================================
;; Staging decision (pure)
;; ============================================================================

(deftest stage-proposal-decision-matrix
  (let [good {:reusable true :score 0.9 :proposed-name "deploy-flow"
              :rationale "r" :skill-md sample-skill-md}]
    (are [scored expected] (= expected (sd/stage-proposal! *project-dir* multi-step-record scored 0.7 "sess"))
      nil                                        :no-score
      (assoc good :reusable false)               :not-reusable
      (assoc good :score 0.5)                    :below-threshold
      (assoc good :proposed-name "Bad Name")     :invalid-name
      (assoc good :skill-md "")                  :empty-skill-md)
    ;; clear staging between sub-cases handled by fixture; now the success case
    (is (empty? (staged-names)))
    (is (= :staged (sd/stage-proposal! *project-dir* multi-step-record good 0.7 "sess")))
    (is (= #{"deploy-flow"} (staged-names)))
    (let [p (proposals/read-proposal *project-dir* "deploy-flow")]
      (is (= "sess" (-> p :meta :session)))
      (is (= 3 (-> p :meta :turn)))
      (is (= 0.9 (-> p :meta :score))))))

;; ============================================================================
;; Eligibility
;; ============================================================================

(defn- stub-agent [{:keys [parent]}]
  {:!state (atom {:runtime {:parent-agent parent}})})

(deftest distill-eligible-gating
  (testing "root agent + config on → eligible"
    (with-redefs [config/get-config (fn [_ k] (when (= k :enable-skill-distillation) true))]
      (is (true? (sd/distill-eligible? (stub-agent {:parent nil}))))))

  (testing "config off → not eligible"
    (with-redefs [config/get-config (fn [_ _] false)]
      (is (false? (sd/distill-eligible? (stub-agent {:parent nil}))))))

  (testing "sub-agent (has parent) → not eligible even with config on"
    (with-redefs [config/get-config (fn [_ _] true)]
      (is (false? (sd/distill-eligible? (stub-agent {:parent {:agent-id :root/x}}))))))

  (testing "nil agent → not eligible"
    (is (not (sd/distill-eligible? nil)))))

;; ============================================================================
;; Cadence — when a batch actually fires
;; ============================================================================

(defn- cadence-config
  "config/get-config stub for :at-cadence mode with a window of `n` turns."
  [n]
  (fn [_ k]
    (case k
      :enable-skill-distillation   true
      :skill-distill-mode          :at-cadence
      :skill-distill-every-n-turns n
      :skill-distill-threshold     0.7
      nil)))

;; The accumulator atoms are process-wide `defonce`s keyed by session, so every
;; run needs its OWN session id — otherwise one case's leftover candidates and
;; turn tally bleed into the next and shift where the window boundary lands.
(def ^:private !sid-seq (atom 0))

(defn- run-turns!
  "Fire `distill-handler` `n` times against a stub agent in a fresh session.
   `worth?` decides whether each turn passes the pre-filter. Returns the labels
   of any submitted batches — no LLM call, no task manager involved."
  [n every worth?]
  (let [submitted (atom [])
        turn      (atom 0)
        sid       (str "sess-cadence-" (swap! !sid-seq inc))]
    (with-redefs [config/get-config       (cadence-config every)
                  proto/session-id        (fn [_] sid)
                  config/project-dir      (fn [_] *project-dir*)
                  traj/latest-trajectory  (fn [_] (assoc multi-step-record :turn (swap! turn inc)))
                  sd/worth-scoring?       (fn [_] worth?)
                  bg/run-off-turn!        (fn [& {:keys [label]}]
                                            (swap! submitted conj label)
                                            :submitted)]
      (dotimes [_ n] (sd/distill-handler {:agent (stub-agent {:parent nil})}))
      @submitted)))

(deftest cadence-batches-instead-of-scoring-every-turn
  (testing "no batch before the window closes"
    (is (empty? (run-turns! 3 4 true))))

  (testing "one batch per window, not one call per turn"
    (let [subs (run-turns! 8 4 true)]
      (is (= 2 (count subs)) "8 qualifying turns → 2 sub-LM calls, not 8")
      (is (every? #(re-find #"cadence, 4 turns" %) subs))))

  (testing "a window with no qualifying turns costs nothing"
    (is (empty? (run-turns! 8 4 false))
        "the counter still advances, but an empty window is never submitted")))

(deftest session-end-flushes-the-tail
  (let [submitted (atom [])
        turn      (atom 0)
        ag        (stub-agent {:parent nil})]
    (with-redefs [config/get-config      (cadence-config 12)
                  proto/session-id       (fn [_] "sess-tail")
                  config/project-dir     (fn [_] *project-dir*)
                  traj/latest-trajectory (fn [_] (assoc multi-step-record :turn (swap! turn inc)))
                  sd/worth-scoring?      (fn [_] true)
                  bg/run-off-turn!       (fn [& {:keys [label]}]
                                           (swap! submitted conj label)
                                           :submitted)]
      (dotimes [_ 3] (sd/distill-handler {:agent ag}))
      (is (empty? @submitted) "3 turns is short of the 12-turn window")
      (is (= [1 2 3] (sd/candidate-turns "sess-tail")))

      (sd/session-end-flush-handler {:agent ag})
      (is (= 1 (count @submitted)) "a short session still distills on close")
      (is (re-find #"session-end, 3 turns" (first @submitted)))

      (testing "state is cleared so nothing leaks across a resume"
        (is (empty? (sd/candidate-turns "sess-tail"))))

      (testing "a second close is a no-op"
        (sd/session-end-flush-handler {:agent ag})
        (is (= 1 (count @submitted)))))))

(deftest per-turn-mode-still-scores-each-turn
  (let [submitted (atom [])
        turn      (atom 0)]
    (with-redefs [config/get-config      (fn [_ k]
                                           (case k
                                             :enable-skill-distillation true
                                             :skill-distill-mode        :per-turn
                                             :skill-distill-threshold   0.7
                                             nil))
                  proto/session-id       (fn [_] "sess-per-turn")
                  config/project-dir     (fn [_] *project-dir*)
                  traj/latest-trajectory (fn [_] (assoc multi-step-record :turn (swap! turn inc)))
                  sd/worth-scoring?      (fn [_] true)
                  bg/run-off-turn!       (fn [& {:keys [label]}]
                                           (swap! submitted conj label)
                                           :submitted)]
      (dotimes [_ 3] (sd/distill-handler {:agent (stub-agent {:parent nil})}))
      (is (= 3 (count @submitted)) "opt-in mode keeps the old one-call-per-turn shape")
      (is (every? #(not (re-find #"batch" %)) @submitted))
      (is (empty? (sd/candidate-turns "sess-per-turn")) "and accumulates nothing"))))
