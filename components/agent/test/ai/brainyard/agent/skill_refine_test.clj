;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.skill-refine-test
  "Tests for skill refinement (R1 Phase 2): divergence detection, the staging
   decision, and kind-aware accept (refinement → skills$write :update). No LLM
   calls — the scorer is stubbed."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [ai.brainyard.agent.common.background :as bg]
            [ai.brainyard.agent.common.skill-refine :as refine]
            [ai.brainyard.agent.common.skill-distill.proposals :as proposals]
            [ai.brainyard.agent.common.trajectory :as traj]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:dynamic *project-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "skill-refine-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (binding [*project-dir* (.getPath dir)]
      (try (f)
           (finally
             (doseq [^java.io.File x (reverse (file-seq dir))] (.delete x)))))))

(use-fixtures :each temp-dir-fixture)

(def revised-md "---\nname: deploy-flow\ndescription: d\n---\n# Deploy\n1. build\n2. release\n")

;; ============================================================================
;; Divergence pre-filter
;; ============================================================================

(deftest skill-invocation-detection
  (are [tool ok?] (= ok? (refine/skill-invocation? tool))
    :skill$deploy-flow true
    "skill$deploy-flow" true
    :read-file false
    :skills$read false        ;; the management command, not a skill invocation
    nil false))

(deftest skill-name-resolution
  (is (= "deploy-flow" (refine/skill-name-of :skill$deploy-flow {:skill "deploy-flow"})))
  (testing "falls back to stripping the prefix when result carries no :skill"
    (is (= "deploy-flow" (refine/skill-name-of :skill$deploy-flow {:error-message "boom"})))))

(deftest result-error-detection
  (are [result err?] (= err? (refine/result-error? result))
    {:error-message "boom"} true
    {:error "boom"}         true
    {:answer "ok"}          false
    {}                      false
    nil                     false))

(deftest divergence-pre-filter
  (testing "failed skill invocation → divergence"
    (is (true? (refine/divergence? :skill$deploy-flow {:error-message "boom" :skill "deploy-flow"}))))
  (testing "non-divergent cases"
    (are [tool result] (not (refine/divergence? tool result))
      :skill$deploy-flow {:answer "ok" :skill "deploy-flow"}   ;; succeeded
      :read-file         {:error-message "boom"}               ;; not a skill
      :skills$read       {:error "x"}                          ;; management cmd
      :skill$x           nil)))                                ;; no result

;; ============================================================================
;; Staging decision
;; ============================================================================

(defn- staged []
  (set (map :name (proposals/list-proposals *project-dir*))))

(deftest stage-refinement-matrix
  (let [good {:should-revise true :revised-md revised-md :rationale "added release step"}]
    (are [scored expected]
         (= expected (refine/stage-refinement! *project-dir* "deploy-flow" scored "boom" "sess"))
      nil                              :no-score
      (assoc good :should-revise false) :no-revision
      (assoc good :revised-md "")      :empty-revised-md)
    (is (empty? (staged)))
    (testing "document at fault → stages a :refinement proposal with evidence"
      (is (= :staged (refine/stage-refinement! *project-dir* "deploy-flow" good "boom: missing step" "sess")))
      (is (= #{"deploy-flow"} (staged)))
      (let [p (proposals/read-proposal *project-dir* "deploy-flow")]
        (is (= :refinement (-> p :meta :kind)))
        (is (= "boom: missing step" (-> p :meta :evidence)))
        (is (= revised-md (:skill-md p)))))))

;; ============================================================================
;; Kind-aware accept (the Phase 2 promotion path)
;; ============================================================================

(deftest accept-refinement-calls-update
  (proposals/write-proposal! *project-dir*
                             {:name "deploy-flow" :skill-md revised-md :kind :refinement
                              :evidence "boom"})
  (let [calls (atom [])]
    (with-redefs [tool/invoke-tool (fn [id & {:as a}]
                                     (swap! calls conj (assoc a :id id))
                                     {:name (:skill-name a) :path "/skills/deploy-flow"})]
      (let [res (proposals/accept-proposal! *project-dir* "deploy-flow")]
        (is (true? (:accepted res)))
        (is (= "update" (:op res)))
        (let [c (first @calls)]
          (is (= :skills$write (:id c)))
          (is (= "update" (:op c)))
          (is (= "deploy-flow" (:skill-name c)))
          (is (= revised-md (:content c)))
          (testing "update does NOT force a scope (auto-detect)"
            (is (nil? (:scope c)))))
        (is (empty? (staged)))))))

;; ============================================================================
;; Turn-level trigger — the LOAD path (default dispatch)
;;
;; A loaded skill hands over its SKILL.md and succeeds, so it can never trip the
;; failed-invocation trigger. Its divergence signal is a failed TURN that loaded
;; it.
;; ============================================================================

(deftest skill-loaded-detects-the-load-path-only
  (testing "a load result is recognised"
    (is (true? (refine/skill-loaded? :skill$deploy {:loaded true :skill "deploy"}))))
  (testing "a delegated answer is not a load"
    (is (false? (refine/skill-loaded? :skill$deploy {:answer "done" :skill "deploy"}))))
  (testing "an errored load is not a load"
    (is (false? (refine/skill-loaded? :skill$deploy {:error-message "not found"}))))
  (testing "non-skill tools never count"
    (are [t] (false? (refine/skill-loaded? t {:loaded true}))
      :write-file :bash :skills$read :skills$find)))

(deftest turn-failed-requires-a-record-reporting-failure
  (testing "an unsuccessful turn is a divergence candidate"
    (is (true? (refine/turn-failed? {:turn 3 :success false}))))
  (testing "a successful turn is not"
    (is (false? (refine/turn-failed? {:turn 3 :success true}))))
  (testing "a MISSING record is not a failure — absence of evidence is not evidence"
    (is (false? (refine/turn-failed? nil)))
    (is (false? (refine/turn-failed? "not-a-record")))))

(deftest turn-evidence-names-the-outcome-and-bounds-itself
  (let [record {:turn 7 :success false :terminated-by :max-iterations
                :question "deploy the service"
                :answer "gave up"
                :iterations [{:n 1 :channel "tool" :tools [{:name "bash"}]}]}
        ev     (refine/turn-evidence record)]
    (testing "the judge is told how the turn ended"
      (is (str/includes? ev "did NOT succeed"))
      (is (str/includes? ev "max-iterations"))
      (is (str/includes? ev "deploy the service")))
    (testing "a huge trace is truncated, not passed whole"
      (let [big (assoc record :iterations
                       (vec (repeat 4000 {:n 1 :channel "tool"
                                          :tools [{:name "bash" :args {:x (apply str (repeat 50 "y"))}}]})))
            ev2 (refine/turn-evidence big)]
        (is (<= (count ev2) (+ refine/max-turn-evidence-chars 64)))
        (is (str/includes? ev2 "evidence truncated"))))))

(defn- stub-agent [{:keys [parent]}]
  {:!state (atom {:runtime {:parent-agent parent}})})

(defn- refinement-on
  "config stub: the refinement gate resolves true, everything else nil."
  [_ k]
  (when (= k :enable-skill-refinement) true))

(deftest track-load-handler-records-only-loads
  (with-redefs [config/get-config refinement-on
                proto/session-id  (fn [_] "sess-track")]
    (let [ag (stub-agent {:parent nil})]
      (try
        (refine/track-load-handler {:agent ag :tool-name :skill$deploy
                                    :result {:loaded true :skill "deploy"}})
        (is (= #{"deploy"} (refine/loaded-skills "sess-track")))
        (testing "a delegated answer is not a load and is not recorded"
          (refine/track-load-handler {:agent ag :tool-name :skill$other
                                      :result {:answer "done" :skill "other"}})
          (is (= #{"deploy"} (refine/loaded-skills "sess-track"))))
        (finally
          (refine/session-end-clear-handler {:agent ag}))))))

(deftest failed-turn-queues-one-judge-per-loaded-skill
  (let [submitted (atom [])]
    (with-redefs [config/get-config  refinement-on
                  config/project-dir (fn [_] "/tmp/proj")
                  proto/session-id   (fn [_] "sess-fail")
                  traj/latest-trajectory (fn [_] {:turn 4 :success false
                                                  :question "q" :answer "a"
                                                  :iterations []})
                  bg/run-off-turn!   (fn [& {:keys [kind key]}]
                                       (swap! submitted conj [kind key])
                                       :submitted)]
      (let [ag (stub-agent {:parent nil})]
        (doseq [s ["deploy" "lint"]]
          (refine/track-load-handler {:agent ag
                                      :tool-name (keyword (str "skill$" s))
                                      :result {:loaded true :skill s}}))
        (refine/turn-refine-handler {:agent ag})
        (testing "each skill the failed turn loaded is judged"
          (is (= #{[:refine "deploy"] [:refine "lint"]} (set @submitted))))
        (testing "the set is cleared so it cannot spill into the next turn"
          (is (empty? (refine/loaded-skills "sess-fail"))))))))

(deftest successful-turn-judges-nothing-but-still-clears
  (let [submitted (atom [])]
    (with-redefs [config/get-config  refinement-on
                  config/project-dir (fn [_] "/tmp/proj")
                  proto/session-id   (fn [_] "sess-ok")
                  traj/latest-trajectory (fn [_] {:turn 2 :success true})
                  bg/run-off-turn!   (fn [& _] (swap! submitted conj :called) :submitted)]
      (let [ag (stub-agent {:parent nil})]
        (refine/track-load-handler {:agent ag :tool-name :skill$deploy
                                    :result {:loaded true :skill "deploy"}})
        (refine/turn-refine-handler {:agent ag})
        (is (empty? @submitted) "a skill that worked must not be second-guessed")
        (is (empty? (refine/loaded-skills "sess-ok")))))))

(deftest turn-trigger-is-root-only
  ;; Sub-agents share the session id. If a sub-agent's ask consumed the set, the
  ;; root turn would be judged against an empty one and the signal would vanish.
  (with-redefs [config/get-config  refinement-on
                config/project-dir (fn [_] "/tmp/proj")
                proto/session-id   (fn [_] "sess-root")
                traj/latest-trajectory (fn [_] {:turn 1 :success false
                                                :question "q" :iterations []})
                bg/run-off-turn!   (fn [& _] :submitted)]
    (let [root (stub-agent {:parent nil})
          sub  (stub-agent {:parent {:agent-id :root/x}})]
      (try
        (refine/track-load-handler {:agent root :tool-name :skill$deploy
                                    :result {:loaded true :skill "deploy"}})
        (refine/turn-refine-handler {:agent sub})
        (is (= #{"deploy"} (refine/loaded-skills "sess-root"))
            "a sub-agent ask must leave the root turn's loaded set intact")
        (finally
          (refine/session-end-clear-handler {:agent root}))))))

(deftest turn-handler-tolerates-a-missing-trajectory
  (with-redefs [config/get-config  refinement-on
                config/project-dir (fn [_] "/tmp/proj")
                proto/session-id   (fn [_] "sess-none")
                traj/latest-trajectory (fn [_] nil)
                bg/run-off-turn!   (fn [& _] (throw (AssertionError. "must not judge")))]
    (let [ag (stub-agent {:parent nil})]
      (refine/track-load-handler {:agent ag :tool-name :skill$deploy
                                  :result {:loaded true :skill "deploy"}})
      (is (nil? (refine/turn-refine-handler {:agent ag})))
      (is (empty? (refine/loaded-skills "sess-none"))))))

(deftest accept-distillation-still-calls-create
  (proposals/write-proposal! *project-dir*
                             {:name "new-skill" :skill-md revised-md :kind :distillation})
  (let [calls (atom [])]
    (with-redefs [tool/invoke-tool (fn [id & {:as a}]
                                     (swap! calls conj (assoc a :id id))
                                     {:name (:skill-name a) :path "/skills/new-skill"})]
      (let [res (proposals/accept-proposal! *project-dir* "new-skill")]
        (is (= "create" (:op res)))
        (let [c (first @calls)]
          (is (= "create" (:op c)))
          (testing "create defaults to project scope"
            (is (= "project" (:scope c)))))))))
