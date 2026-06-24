;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.skill-distill-test
  "Tests for the skill-distillation self-improvement loop (R1):
   pre-filter, staging store, accept/reject review gate, the staging decision,
   and hook eligibility. No LLM calls — the scorer is stubbed."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [ai.brainyard.agent.common.skill-distill :as sd]
            [ai.brainyard.agent.common.skill-distill.proposals :as proposals]
            [ai.brainyard.agent.core.config :as config]
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
