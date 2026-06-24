;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.skill-refine-test
  "Tests for skill refinement (R1 Phase 2): divergence detection, the staging
   decision, and kind-aware accept (refinement → skills$write :update). No LLM
   calls — the scorer is stubbed."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [ai.brainyard.agent.common.skill-refine :as refine]
            [ai.brainyard.agent.common.skill-distill.proposals :as proposals]
            [ai.brainyard.agent.core.tool :as tool]
            [clojure.java.io :as io]))

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
