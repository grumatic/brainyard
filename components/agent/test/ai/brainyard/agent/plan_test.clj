;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.plan-test
  "Tests for persistent plan management.
   Plans are pure blueprints (frontmatter + free-form body). No step state —
   execution lives in todos (see todo-test)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.agent.common.plan :as plan]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "plan-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (binding [*test-dir* (.getPath dir)]
      (try
        (f)
        (finally
          (doseq [f (reverse (file-seq dir))]
            (.delete f)))))))

(use-fixtures :each temp-dir-fixture)

(defn test-dirs []
  {:project-dir *test-dir*})

;; ============================================================================
;; Markdown Round-Trip
;; ============================================================================

(deftest test-plan-md-roundtrip
  (let [plan {:id "test-uuid"
              :title "Test Plan"
              :slug "test-plan"
              :scope :project
              :status :draft
              :created "2026-03-16T00:00:00Z"
              :updated "2026-03-16T00:00:00Z"
              :body "## Context\nAccomplish the test\n\n## Approach\nDo it carefully\n\n## Risks\nSome notes here\n\n## References\n- See foo.clj:42\n\n## Acceptance\nTests pass"}
        md (plan/plan->md plan)
        parsed (plan/md->plan md)]
    (testing "frontmatter preserved"
      (is (= "test-uuid" (:id parsed)))
      (is (= "Test Plan" (:title parsed)))
      (is (= :project (:scope parsed)))
      (is (= :draft (:status parsed))))
    (testing "body preserved verbatim"
      (is (str/includes? (:body parsed) "## Context"))
      (is (str/includes? (:body parsed) "Accomplish the test"))
      (is (str/includes? (:body parsed) "## Approach"))
      (is (str/includes? (:body parsed) "Some notes here"))
      (is (str/includes? (:body parsed) "## References"))
      (is (str/includes? (:body parsed) "foo.clj:42"))
      (is (str/includes? (:body parsed) "## Acceptance")))
    (testing "no :steps key on parsed plan"
      (is (not (contains? parsed :steps))))))

(deftest test-plan-md-roundtrip-legacy
  (testing "legacy plan files with ## Steps / ## Notes sections still parse — those sections become part of the body"
    (let [old-md "---\nid: old-uuid\ntitle: Old Plan\nscope: project\nstatus: draft\ncreated: 2026-01-01T00:00:00Z\nupdated: 2026-01-01T00:00:00Z\n---\n\n# Old Plan\n\n## Goal\nDeploy to production\n\n## Steps\n- [ ] Run tests\n- [x] Build image — result: done\n\n## Notes\nBe careful\n"
          parsed (plan/md->plan old-md)]
      (is (= "old-uuid" (:id parsed)))
      (testing "all legacy sections survive verbatim in :body"
        (is (str/includes? (:body parsed) "## Goal"))
        (is (str/includes? (:body parsed) "Deploy to production"))
        (is (str/includes? (:body parsed) "## Steps"))
        (is (str/includes? (:body parsed) "Run tests"))
        (is (str/includes? (:body parsed) "Build image"))
        (is (str/includes? (:body parsed) "## Notes"))
        (is (str/includes? (:body parsed) "Be careful")))
      (testing "no structured :steps extraction"
        (is (not (contains? parsed :steps)))))))

;; ============================================================================
;; CRUD Operations
;; ============================================================================

(deftest test-create-and-read-plan
  (let [dirs (test-dirs)
        result (plan/create-plan dirs :project "Deploy Service"
                                 "## Context\nDeploy to production\n\n## Approach\nRolling update\n\n## Risks\nCareful deployment\n\n## References\n- runbook.md\n\n## Acceptance\nGreen dashboard")]
    (testing "create returns plan map with random slug"
      (is (string? (:id result)))
      (is (= "Deploy Service" (:title result)))
      (is (string? (:slug result)))
      (is (not= "deploy-service" (:slug result)))
      (is (= :draft (:status result))))
    (testing "no :steps key on result"
      (is (not (contains? result :steps))))
    (testing "body is free-form markdown"
      (is (str/includes? (:body result) "## Context"))
      (is (str/includes? (:body result) "Deploy to production"))
      (is (str/includes? (:body result) "## References"))
      (is (str/includes? (:body result) "runbook.md")))
    (testing "file-path is absolute"
      (is (string? (:file-path result)))
      (is (.isAbsolute (io/file (:file-path result)))))
    (testing "file exists on disk at file-path"
      (is (.exists (io/file (:file-path result)))))
    (testing "read-plan returns same data with file-path"
      (let [slug (:slug result)
            read-result (plan/read-plan dirs slug)]
        (is (= "Deploy Service" (:title read-result)))
        (is (str/includes? (:body read-result) "Deploy to production"))
        (is (string? (:file-path read-result)))
        (is (.isAbsolute (io/file (:file-path read-result))))
        (is (not (contains? read-result :steps)))))))

(deftest test-same-title-gets-different-slugs
  (let [dirs (test-dirs)
        plan1 (plan/create-plan dirs :project "My Plan" "## Context\nFirst")
        plan2 (plan/create-plan dirs :project "My Plan" "## Context\nSecond")]
    (testing "both plans created successfully with different random slugs"
      (is (not (contains? plan1 :error)))
      (is (not (contains? plan2 :error)))
      (is (not= (:slug plan1) (:slug plan2))))))

(deftest test-read-plan-not-found
  (let [result (plan/read-plan (test-dirs) "nonexistent")]
    (is (contains? result :error))
    (is (str/includes? (:error result) "not found"))))

(deftest test-update-plan
  (let [dirs (test-dirs)
        created (plan/create-plan dirs :project "Update Me" "## Context\nInitial")
        slug (:slug created)
        plan (plan/read-plan dirs slug)
        updated (assoc plan :status :in-progress)
        result (plan/update-plan dirs updated)]
    (is (= :in-progress (:status result)))
    (is (string? (:file-path result)))
    (testing "status persists across re-read"
      (let [re-read (plan/read-plan dirs slug)]
        (is (= :in-progress (:status re-read)))))))

(deftest test-delete-plan
  (let [dirs (test-dirs)
        created (plan/create-plan dirs :project "Delete Me" "## Context\nGone soon")
        slug (:slug created)]
    (is (plan/plan-exists? dirs slug))
    (let [result (plan/delete-plan dirs slug)]
      (is (= slug (:deleted result))))
    (is (false? (plan/plan-exists? dirs slug)))))

(deftest test-delete-not-found
  (let [result (plan/delete-plan (test-dirs) "ghost")]
    (is (contains? result :error))))

;; ============================================================================
;; List Plans
;; ============================================================================

(deftest test-list-plans
  (let [dirs (test-dirs)]
    (plan/create-plan dirs :project "Plan A" "## Context\nA")
    (plan/create-plan dirs :project "Plan B" "## Context\nB")
    ;; Scope to :project so the user's real `~/.brainyard/plans/` (legacy
    ;; fallback location) doesn't leak into the count.
    (let [plans (plan/list-plans dirs :scope :project)]
      (is (= 2 (count plans)))
      (is (every? :slug plans))
      (is (every? :title plans))
      (is (every? :file-path plans))
      (is (every? #(= :new (:layout %)) plans)
          "create-plan writes to the new path; layout tag should be :new")
      (testing "list summaries do not carry step-progress"
        (is (not-any? :step-progress plans))))))

(deftest test-list-plans-filter-status
  (let [dirs (test-dirs)
        p1 (plan/create-plan dirs :project "Draft Plan" "## Context\nA")
        slug1 (:slug p1)]
    (let [p (plan/read-plan dirs slug1)
          completed (assoc p :status :completed)]
      (plan/update-plan dirs completed))
    (plan/create-plan dirs :project "Another Draft" "## Context\nB")
    (let [completed-plans (plan/list-plans dirs :scope :project :status :completed)
          draft-plans (plan/list-plans dirs :scope :project :status :draft)]
      (is (= 1 (count completed-plans)))
      (is (= slug1 (:slug (first completed-plans))))
      (is (= 1 (count draft-plans))))))

;; ============================================================================
;; Body Operations
;; ============================================================================

(deftest test-update-body
  (let [dirs (test-dirs)
        created (plan/create-plan dirs :project "Body Test" "Initial body")
        slug (:slug created)]
    (testing "body is initially set"
      (is (= "Initial body" (:body created))))
    (testing "update-body replaces the body"
      (let [p (plan/read-plan dirs slug)
            updated (plan/update-body p "## Context\nNew rich context\n\n## Risks\nSomething risky")]
        (plan/update-plan dirs updated)
        (let [re-read (plan/read-plan dirs slug)]
          (is (str/includes? (:body re-read) "New rich context"))
          (is (str/includes? (:body re-read) "Something risky"))
          (testing "frontmatter status untouched"
            (is (= :draft (:status re-read)))))))))

(deftest test-update-body-blank-removes-body
  (let [dirs (test-dirs)
        created (plan/create-plan dirs :project "No Body" "Some content")
        slug (:slug created)
        p (plan/read-plan dirs slug)
        updated (plan/update-body p "")]
    (plan/update-plan dirs updated)
    (let [re-read (plan/read-plan dirs slug)]
      (is (nil? (:body re-read))))))

;; ============================================================================
;; Plan Exists
;; ============================================================================

(deftest test-plan-exists
  (let [dirs (test-dirs)]
    (is (false? (plan/plan-exists? dirs "nope")))
    (let [created (plan/create-plan dirs :project "Exists" "## Context\nYes")
          slug (:slug created)]
      (is (true? (plan/plan-exists? dirs slug))))))

;; ============================================================================
;; Reopen
;; ============================================================================

(deftest test-reopen-plan
  (testing "reopen flips status to :draft; body is untouched (no step state to reset)"
    (let [plan {:status :completed
                :body   "## Context\nA rich body\n\n## References\n- foo.md"}
          reopened (plan/reopen-plan plan)]
      (is (= :draft (:status reopened)))
      (testing "body persists across reopen"
        (is (= (:body plan) (:body reopened)))))))

(deftest test-reopen-plan-persists
  (testing "reopen roundtrips through disk correctly — body persists, status flips"
    (let [dirs (test-dirs)
          created (plan/create-plan dirs :project "Reopen Me"
                                    "## Context\nDoing X\n\n## Approach\nStep-by-step")
          slug (:slug created)
          original-body (:body created)]
      (testing "complete the plan"
        (let [p (plan/read-plan dirs slug)
              done (assoc p :status :completed)]
          (plan/update-plan dirs done))
        (let [p (plan/read-plan dirs slug)]
          (is (= :completed (:status p)))))
      (testing "reopen and persist"
        (let [p (plan/read-plan dirs slug)
              reopened (plan/reopen-plan p)]
          (plan/update-plan dirs reopened))
        (let [p (plan/read-plan dirs slug)]
          (is (= :draft (:status p)))
          (testing "body survived round-trip"
            (is (= original-body (:body p)))))))))

;; ============================================================================
;; Dual-read fallback (legacy `.brainyard/plans/` → new `.brainyard/agents/plan-agent/plans/`)
;; ============================================================================

(defn- write-legacy-plan-md!
  "Materialize a plan file under <base>/.brainyard/plans/<slug>.md (legacy
   path) with valid frontmatter. Used to simulate a pre-migration repo."
  [base slug title]
  (let [f (io/file base ".brainyard/plans" (str slug ".md"))]
    (.mkdirs (.getParentFile f))
    (spit f (str "---\n"
                 "id: 11111111-1111-1111-1111-111111111111\n"
                 "file-type: plan\n"
                 "title: " title "\n"
                 "scope: project\nstatus: draft\n"
                 "created: 2026-05-09T00:00:00Z\n"
                 "updated: 2026-05-09T00:00:00Z\n"
                 "---\n\n# " title "\n\n## Context\nlegacy body\n"))))

(deftest test-dual-read-fallback
  (let [dirs (test-dirs)]
    (write-legacy-plan-md! *test-dir* "legacy-only" "Legacy Only")

    (testing "read-plan finds a legacy plan when the new path is absent"
      (let [p (plan/read-plan dirs "legacy-only")]
        (is (= "Legacy Only" (:title p)))
        (is (str/includes? (:file-path p) "/.brainyard/plans/")
            "file-path points at the legacy location until migration")))

    (testing "plan-exists? is true for legacy plans"
      (is (plan/plan-exists? dirs "legacy-only")))

    (testing "list-plans tags legacy entries with :layout :legacy"
      (let [entries (plan/list-plans dirs :scope :project)
            legacy  (some (fn [e] (when (= "legacy-only" (:slug e)) e)) entries)]
        (is (some? legacy))
        (is (= :legacy (:layout legacy)))))))

(deftest test-dual-read-new-wins-on-collision
  (let [dirs (test-dirs)
        ;; Set up the SAME slug at both layouts.
        _ (write-legacy-plan-md! *test-dir* "shared-slug" "Legacy Title")
        _ (plan/create-plan dirs :project "New Title" "## Context\nnew body")
        ;; create-plan generates a random slug — overwrite the new file at
        ;; the colliding slug so we control it.
        new-shared (io/file *test-dir* ".brainyard/agents/plan-agent/plans/shared-slug.md")
        _ (do (.mkdirs (.getParentFile new-shared))
              (spit new-shared (str "---\n"
                                    "id: 22222222-2222-2222-2222-222222222222\n"
                                    "file-type: plan\n"
                                    "title: New Title\n"
                                    "scope: project\nstatus: draft\n"
                                    "created: 2026-05-10T00:00:00Z\n"
                                    "updated: 2026-05-10T00:00:00Z\n"
                                    "---\n\n# New Title\n\n## Context\nnew body\n")))]

    (testing "read-plan returns the new-layout file when both exist"
      (let [p (plan/read-plan dirs "shared-slug")]
        (is (= "New Title" (:title p)))
        (is (str/includes? (:file-path p) "/.brainyard/agents/plan-agent/plans/"))))

    (testing "list-plans dedups: shared-slug appears once, tagged :new"
      (let [entries  (plan/list-plans dirs :scope :project)
            shared-entries (filter (fn [e] (= "shared-slug" (:slug e))) entries)]
        (is (= 1 (count shared-entries))
            "duplicate slug across layouts collapses to a single entry")
        (is (= :new (:layout (first shared-entries))))
        (is (= "New Title" (:title (first shared-entries))))))))

(deftest test-create-plan-writes-to-new-path
  (let [dirs (test-dirs)
        p (plan/create-plan dirs :project "Path Verification" "## Context\nx")]
    (is (str/includes? (:file-path p) "/.brainyard/agents/plan-agent/plans/")
        "create-plan must write to the new path, never the legacy location")
    (is (not (str/includes? (:file-path p) "/.brainyard/plans/"))
        "the legacy substring is mutually exclusive with the new substring")))

;; ============================================================================
;; plan$dossier-* helper unit tests
;; ============================================================================

(deftest test-dossier-slug-determinism
  (testing "same question → same slug (deterministic)"
    (let [q "Plan to ship checkout v2 next sprint"
          s1 (:slug (plan/plan$dossier-slug :question q))
          s2 (:slug (plan/plan$dossier-slug :question q))]
      (is (= s1 s2))
      (is (string? s1))
      (is (= "ship-checkout-v2-next-sprint" s1)
          "stopwords (incl. 'plan' and 'to') drop, leaving the content verbs")))

  (testing "blank → fallback slug 'plan'"
    (is (= "plan" (:slug (plan/plan$dossier-slug :question ""))))
    (is (= "plan" (:slug (plan/plan$dossier-slug :question "   "))))
    (is (= "plan" (:slug (plan/plan$dossier-slug :question "the a how"))))
    (is (= "plan" (:slug (plan/plan$dossier-slug :question "plan plan plan")))
        "'plan' is a stopword so an all-plan question collapses to fallback"))

  (testing "60-char default cap and override"
    (let [long-q (str/join " " (repeat 30 "supercalifragilistic"))]
      (is (<= (count (:slug (plan/plan$dossier-slug :question long-q))) 60))
      (is (<= (count (:slug (plan/plan$dossier-slug :question long-q :max-chars 7))) 7))))

  (testing "validation"
    (is (contains? (plan/plan$dossier-slug :question 123) :error))
    (is (contains? (plan/plan$dossier-slug :question "x" :max-chars 0) :error))))

(deftest test-dossier-frontmatter-and-read-roundtrip
  (let [{:keys [frontmatter]}
        (plan/plan$dossier-frontmatter
         :slug "ship-checkout-v2"
         :plan-path ".brainyard/agents/plan-agent/plans/ship-checkout-v2.md"
         :plan-status "draft"
         :pre {:verdict :go
               :checks {:c1_goal_clear :pass :c2_no_duplicate :pass
                        :c7_owner_known :informational}
               :exploration_path ".brainyard/agents/explore-agent/results/cp-20260509.md"
               :owner "jake"
               :related_plans []
               :gather_question nil
               :refuse_reason nil}
         :author {:action :created :body_bytes 4128}
         :post {:verdict :pass
                :rubric {:r1 :pass :r2 :pass :r3 :pass :r4 :pass
                         :r5 :pass :r6 :pass :r7 :pass}
                :revision_applied false
                :revision_summary nil
                :holds []
                :acceptance ["feature-flag toggleable"
                             "all checkout/* unit tests green"]}
         :handoff {:next_agent "todo-agent"
                   :next_call "(call-tool \"todo-agent\" {:question \"...\"})"})]

    (testing "well-formed envelope"
      (is (str/starts-with? frontmatter "---\n"))
      (is (str/ends-with? frontmatter "---\n"))
      (is (str/includes? frontmatter "slug: ship-checkout-v2"))
      (is (str/includes? frontmatter "agent: plan-agent"))
      (is (str/includes? frontmatter "plan_path: .brainyard/agents/plan-agent/plans/ship-checkout-v2.md")))

    (testing "nested blocks rendered as YAML block style"
      (is (str/includes? frontmatter "pre:\n"))
      (is (str/includes? frontmatter "author:\n"))
      (is (str/includes? frontmatter "post:\n"))
      (is (str/includes? frontmatter "handoff:\n")))

    (testing "round-trip via plan$read-dossier"
      (let [tmp (.getCanonicalPath
                 (doto (io/file (System/getProperty "java.io.tmpdir")
                                (str "plan-doss-rt-" (System/currentTimeMillis)))
                   .mkdirs))
            {:keys [path]} (plan/plan$dossier-write
                            :slug "ship-checkout-v2"
                            :content (str frontmatter "\n# body\n")
                            :base-dir tmp)
            parsed (plan/plan$read-dossier :path path :base-dir tmp)]
        (try
          (is (= "ship-checkout-v2" (:slug parsed)))
          (is (= "plan-agent" (:agent parsed)))
          (is (= ".brainyard/agents/plan-agent/plans/ship-checkout-v2.md" (:plan_path parsed)))
          (is (= "go" (get-in parsed [:pre :verdict])))
          (is (= "jake" (get-in parsed [:pre :owner])))
          (is (= ".brainyard/agents/explore-agent/results/cp-20260509.md"
                 (get-in parsed [:pre :exploration_path])))
          (is (= "created" (get-in parsed [:author :action])))
          (is (= 4128 (get-in parsed [:author :body_bytes])))
          (is (= "pass" (get-in parsed [:post :verdict])))
          (is (false? (get-in parsed [:post :revision_applied])))
          (is (= ["feature-flag toggleable"
                  "all checkout/* unit tests green"]
                 (get-in parsed [:post :acceptance])))
          (is (= "todo-agent" (get-in parsed [:handoff :next_agent])))
          (testing "rubric and checks survive as raw flow-map strings"
            (is (string? (get-in parsed [:post :rubric])))
            (is (str/includes? (get-in parsed [:post :rubric]) "r1: pass"))
            (is (str/includes? (get-in parsed [:pre :checks]) "c1_goal_clear: pass")))
          (finally
            (doseq [f (reverse (file-seq (io/file tmp)))]
              (.delete f))))))

    (testing "validation"
      (is (contains? (plan/plan$dossier-frontmatter :pre {} :post {}) :error)
          "missing :slug → error"))))

(deftest test-dossier-write-collision-suffix
  (let [tmp (.getCanonicalPath
             (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "plan-doss-coll-" (System/currentTimeMillis)))
               .mkdirs))]
    (try
      ;; dossier writers require YAML frontmatter on :content
      (let [fm "---\nslug: rerun\n---\n"
            c1 (str fm "x1") c2 (str fm "x2") c3 (str fm "x3")
            r1 (plan/plan$dossier-write :slug "rerun" :content c1 :base-dir tmp)
            r2 (plan/plan$dossier-write :slug "rerun" :content c2 :base-dir tmp)
            r3 (plan/plan$dossier-write :slug "rerun" :content c3 :base-dir tmp)]
        (is (= "rerun"   (:slug r1)))
        (is (= "rerun-2" (:slug r2)))
        (is (= "rerun-3" (:slug r3)))
        ;; All three files exist and content is preserved
        (is (= c1 (slurp (io/file (:path r1)))))
        (is (= c2 (slurp (io/file (:path r2)))))
        (is (= c3 (slurp (io/file (:path r3))))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))]
          (.delete f))))))

(deftest test-dossier-index-prepend-ordering
  (let [tmp (.getCanonicalPath
             (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "plan-doss-idx-" (System/currentTimeMillis)))
               .mkdirs))]
    (try
      (plan/plan$dossier-index-append :path "dossiers/a.md" :slug "alpha"
                                      :pre-verdict :go :post-verdict :pass
                                      :next-agent :todo-agent :base-dir tmp)
      (plan/plan$dossier-index-append :path "dossiers/b.md" :slug "beta"
                                      :pre-verdict :gather :next-agent :user
                                      :base-dir tmp)
      (plan/plan$dossier-index-append :path "dossiers/c.md" :slug "gamma"
                                      :pre-verdict :refuse :next-agent :none
                                      :base-dir tmp)

      (let [content (slurp (io/file tmp ".brainyard/agents/plan-agent/INDEX.md"))
            lines   (->> (str/split-lines content) (remove str/blank?))]
        (is (= 3 (count lines)))
        ;; Newest first — gamma (last appended) is first.
        (is (str/includes? (nth lines 0) "gamma"))
        (is (str/includes? (nth lines 0) "pre:refuse"))
        (is (str/includes? (nth lines 0) "→ none"))
        (is (str/includes? (nth lines 1) "beta"))
        (is (str/includes? (nth lines 1) "post:n/a"))
        (is (str/includes? (nth lines 2) "alpha"))
        (is (str/includes? (nth lines 2) "post:pass"))
        (is (str/includes? (nth lines 2) "→ todo-agent")))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))]
          (.delete f))))))

(deftest test-next-handoff-variants
  (testing "PASS recommends todo-agent with the dossier path"
    (let [r (plan/plan$next-handoff :pre {:verdict :go} :post {:verdict :pass}
                                    :slug "ship-v2" :dossier-path "doss/ship.md")]
      (is (= "todo-agent" (:next-agent r)))
      (is (str/includes? (:next-call r) "todo-agent"))
      (is (str/includes? (:next-call r) ":agent-context \"doss/ship.md\""))))

  (testing "HOLD recommends user (resolve holds, then re-call plan-agent)"
    (let [r (plan/plan$next-handoff :pre {:verdict :go} :post {:verdict :hold})]
      (is (= "user" (:next-agent r)))
      (is (str/includes? (str/lower-case (:next-call r)) "hold"))))

  (testing "GATHER recommends user (provide missing input)"
    (let [r (plan/plan$next-handoff :pre {:verdict :gather})]
      (is (= "user" (:next-agent r)))
      (is (str/includes? (:next-call r) "gather_question"))))

  (testing "REFUSE recommends none (redirect specified in dossier)"
    (let [r (plan/plan$next-handoff :pre {:verdict :refuse})]
      (is (= "none" (:next-agent r)))
      (is (str/includes? (:next-call r) "refuse_reason")))))

;; ============================================================================
;; plan-auto-persist hook
;;
;; Bypass the actual `:agent.ask/post` firing path (which would need a real
;; agent runtime). Test materialize-auto-dossier! directly with synthetic
;; answers, and test plan-auto-persist's agent-typecheck via a fake reified
;; IAgent instance — same pattern as explore_agent_test.clj §"Hook unit tests".
;; ============================================================================

(defn- fake-plan-agent []
  (reify ai.brainyard.agent.core.protocol/IAgent
    (agent-id [_] :plan-agent/test-instance)
    (agent-name [_] "test")
    (agent-description [_] "test")
    (user-id [_] "u1")
    (session-id [_] "s1")
    (defagent-type [_] :plan-agent)
    (process [_ _ _] nil)
    (get-tools [_] [])
    (get-state [_] {})))

(defn- fake-other-agent []
  (reify ai.brainyard.agent.core.protocol/IAgent
    (agent-id [_] :explore-agent/test-instance)
    (agent-name [_] "test")
    (agent-description [_] "test")
    (user-id [_] "u1")
    (session-id [_] "s1")
    (defagent-type [_] :explore-agent)
    (process [_ _ _] nil)
    (get-tools [_] [])
    (get-state [_] {})))

(deftest test-detect-pre-verdict
  (testing "Refused: prefix → :refuse"
    (is (= :refuse (#'plan/detect-pre-verdict
                    "Saved dossier: foo.md\nRefused: this is a single-edit; use update-agent")))
    (is (= :refuse (#'plan/detect-pre-verdict "  Refused: nope"))))

  (testing "Need: prefix → :gather"
    (is (= :gather (#'plan/detect-pre-verdict
                    "Saved dossier: foo.md\nNeed: missing exploration of checkout/")))
    (is (= :gather (#'plan/detect-pre-verdict "Need: more info"))))

  (testing "absence of either prefix → :go (the only verdict that authors)"
    (is (= :go (#'plan/detect-pre-verdict
                "Saved plan: x.md\nSaved dossier: y.md\nNext: …")))
    (is (= :go (#'plan/detect-pre-verdict "All checks passed."))))

  (testing "Refused: takes precedence over Need: when both somehow appear"
    (is (= :refuse (#'plan/detect-pre-verdict "Refused: x\nNeed: y")))))

(deftest test-detect-post-verdict
  (testing "no AUTHOR (pre ≠ :go) → nil regardless of body"
    (is (nil? (#'plan/detect-post-verdict "Hold: x" :gather)))
    (is (nil? (#'plan/detect-post-verdict "Hold: x" :refuse))))

  (testing "AUTHOR + Hold: prefix → :hold"
    (is (= :hold (#'plan/detect-post-verdict
                  "Saved plan: x.md\nHold: missing risks open question"
                  :go))))

  (testing "AUTHOR + no Hold: → :pass"
    (is (= :pass (#'plan/detect-post-verdict
                  "Saved plan: x.md\nNext: (call-tool ...)" :go)))))

(deftest test-extract-line-after
  (testing "extracts trimmed value after prefix"
    (is (= "src/foo.clj"
           (#'plan/extract-line-after "Saved plan: src/foo.clj\nMore"
                                      plan/saved-plan-prefix)))
    (is (= ".brainyard/agents/plan-agent/dossiers/x.md"
           (#'plan/extract-line-after
            "Saved dossier: .brainyard/agents/plan-agent/dossiers/x.md"
            plan/saved-dossier-prefix))))

  (testing "strips surrounding backticks / quotes / trailing periods"
    (is (= "src/foo.clj"
           (#'plan/extract-line-after "Saved plan: `src/foo.clj`."
                                      plan/saved-plan-prefix)))
    (is (= "src/foo.clj"
           (#'plan/extract-line-after "Saved plan: \"src/foo.clj\""
                                      plan/saved-plan-prefix))))

  (testing "missing prefix → nil"
    (is (nil? (#'plan/extract-line-after "no marker here"
                                         plan/saved-plan-prefix)))))

(deftest test-dossier-already-saved?
  (let [tmp (.getCanonicalPath
             (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "plan-doss-saved-" (System/currentTimeMillis)))
               .mkdirs))]
    (try
      (testing "claimed-but-not-on-disk → falsy (hallucinated)"
        (is (not (#'plan/dossier-already-saved?
                  "Saved dossier: .brainyard/agents/plan-agent/dossiers/fake.md"
                  tmp))))

      (testing "no claim at all → falsy"
        (is (not (#'plan/dossier-already-saved? "no marker" tmp))))

      (testing "claim + file exists → truthy"
        (let [{:keys [path]}
              (plan/plan$dossier-write :slug "real" :content "---\nslug: real\n---\nx" :base-dir tmp)]
          (is (#'plan/dossier-already-saved?
               (str "Saved dossier: " path)
               tmp))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))]
          (.delete f))))))

(deftest test-materialize-auto-dossier-pass
  (testing "PASS-shaped answer (Saved plan: + Next:) materializes a dossier"
    (let [tmp (.getCanonicalPath
               (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "plan-auto-pass-" (System/currentTimeMillis)))
                 .mkdirs))]
      (try
        (let [answer (str "## Plan authored\n\n"
                          "Pipeline ran cleanly.\n\n"
                          "Saved plan: .brainyard/agents/plan-agent/plans/ship-v2-checkout.md\n"
                          "Next: (call-tool \"todo-agent\" {:question \"...\"})\n")
              r (plan/materialize-auto-dossier!
                 {:answer answer :question "Plan v2 checkout" :base-dir tmp})]
          (is (some? r))
          (is (= "ship-v2-checkout" (:slug r))
              "slug is derived from the Saved plan: filename, not the question")
          (is (= :go (:pre-verdict r)))
          (is (= :pass (:post-verdict r)))
          ;; Dossier exists on disk
          (let [dossier (io/file (:path r))]
            (is (.isFile dossier))
            (let [content (slurp dossier)]
              (is (str/includes? content "agent: plan-agent"))
              (is (str/includes? content "verdict: go"))
              (is (str/includes? content "verdict: pass"))
              (is (str/includes? content "## Original answer"))
              (is (str/includes? content "(auto-persisted)"))))
          ;; INDEX prepended
          (let [idx (io/file tmp ".brainyard/agents/plan-agent/INDEX.md")]
            (is (.isFile idx))
            (let [line (-> idx slurp str/split-lines first)]
              (is (str/includes? line "ship-v2-checkout"))
              (is (str/includes? line "pre:go"))
              (is (str/includes? line "post:pass"))
              (is (str/includes? line "→ todo-agent")))))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f)))))))

(deftest test-materialize-auto-dossier-hold
  (testing "HOLD answer materializes dossier with post-verdict :hold and no Next: todo-agent"
    (let [tmp (.getCanonicalPath
               (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "plan-auto-hold-" (System/currentTimeMillis)))
                 .mkdirs))]
      (try
        (let [answer (str "## Plan authored — needs revision\n\n"
                          "Saved plan: .brainyard/agents/plan-agent/plans/needs-fix.md\n"
                          "Hold: R4 — Risks section has no open question\n"
                          "Suggested: amend the plan and re-run\n")
              r (plan/materialize-auto-dossier!
                 {:answer answer :question "needs fix" :base-dir tmp})]
          (is (= :go (:pre-verdict r)))
          (is (= :hold (:post-verdict r)))
          (let [content (slurp (io/file (:path r)))]
            (is (str/includes? content "verdict: hold")))
          (let [idx (io/file tmp ".brainyard/agents/plan-agent/INDEX.md")]
            (is (str/includes? (slurp idx) "post:hold"))
            (is (str/includes? (slurp idx) "→ user")
                "HOLD recommends user, not todo-agent")))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f)))))))

(deftest test-materialize-auto-dossier-gather
  (testing "GATHER answer (Need: prefix, no Saved plan:) materializes dossier"
    (let [tmp (.getCanonicalPath
               (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "plan-auto-gather-" (System/currentTimeMillis)))
                 .mkdirs))]
      (try
        (let [answer (str "## PRE-FLIGHT GATHER\n\n"
                          "I cannot draft a plan without exploration of the checkout/ subsystem.\n\n"
                          "Need: a prior `Saved exploration:` covering checkout/\n"
                          "Suggested: (call-tool \"explore-agent\" {:question \"map checkout/\"})\n")
              r (plan/materialize-auto-dossier!
                 {:answer   answer
                  :question "Plan how we'll ship checkout v2"
                  :base-dir tmp})]
          (is (= :gather (:pre-verdict r)))
          (is (nil? (:post-verdict r))
              "no AUTHOR ran on GATHER, so post-verdict is nil")
          (let [content (slurp (io/file (:path r)))]
            (is (str/includes? content "verdict: gather"))
            (is (str/includes? content "plan_path: null")
                "no plan was authored → plan_path is null"))
          (let [idx (io/file tmp ".brainyard/agents/plan-agent/INDEX.md")]
            (is (str/includes? (slurp idx) "pre:gather"))
            (is (str/includes? (slurp idx) "post:n/a"))))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f)))))))

(deftest test-materialize-auto-dossier-refuse
  (testing "REFUSE answer materializes dossier with handoff = none"
    (let [tmp (.getCanonicalPath
               (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "plan-auto-refuse-" (System/currentTimeMillis)))
                 .mkdirs))]
      (try
        (let [answer (str "Refused: this is a single-file edit; use update-agent.\n"
                          "Suggested: (call-tool \"update-agent\" {...})\n")
              r (plan/materialize-auto-dossier!
                 {:answer answer :question "rename foo to bar" :base-dir tmp})]
          (is (= :refuse (:pre-verdict r)))
          (is (nil? (:post-verdict r)))
          (let [idx (io/file tmp ".brainyard/agents/plan-agent/INDEX.md")]
            (is (str/includes? (slurp idx) "pre:refuse"))
            (is (str/includes? (slurp idx) "→ none"))))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f)))))))

(deftest test-materialize-auto-dossier-skip-when-already-saved
  (testing "answer naming an EXISTING dossier path → no-op (LLM did its job)"
    (let [tmp (.getCanonicalPath
               (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "plan-auto-skip-" (System/currentTimeMillis)))
                 .mkdirs))]
      (try
        ;; Write a real dossier first
        (let [{:keys [path]} (plan/plan$dossier-write
                              :slug "real-one" :content "---\nslug: real-one\n---\nreal" :base-dir tmp)
              answer (str "Saved plan: x.md\nSaved dossier: " path "\nNext: ...")
              r (plan/materialize-auto-dossier!
                 {:answer answer :question "Q" :base-dir tmp})]
          (is (nil? r) "should skip — the on-disk file exists")
          ;; Confirm no NEW dossier was written (only the original one).
          (let [dossiers (->> (io/file tmp ".brainyard/agents/plan-agent/dossiers/")
                              .listFiles
                              (filter #(.isFile %)))]
            (is (= 1 (count dossiers))
                "exactly one dossier — the pre-existing real-one")))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f)))))))

(deftest test-materialize-auto-dossier-replaces-hallucinated-path
  (testing "answer naming a NON-EXISTENT dossier path → hook replaces it"
    (let [tmp (.getCanonicalPath
               (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "plan-auto-halu-" (System/currentTimeMillis)))
                 .mkdirs))]
      (try
        (let [answer (str "Saved plan: .brainyard/agents/plan-agent/plans/bronze-mosquito-166.md\n"
                          "Saved dossier: .brainyard/agents/plan-agent/dossiers/HALLUCINATED-20990101-bronze.md\n"
                          "Hold: missing open question\n")
              r (plan/materialize-auto-dossier!
                 {:answer answer :question "Q" :base-dir tmp})]
          (is (some? r) "hallucinated path triggers re-persistence")
          (is (= "bronze-mosquito-166" (:slug r))
              "slug derives from Saved plan: even though Saved dossier: lied")
          ;; The real dossier exists; the hallucinated one does not.
          (is (.isFile (io/file (:path r))))
          (is (not (.isFile (io/file tmp ".brainyard/agents/plan-agent/dossiers/HALLUCINATED-20990101-bronze.md")))))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f)))))))

(deftest test-plan-auto-persist-agent-scoping
  (testing "non-plan-agent invocations are ignored entirely"
    (let [tmp (.getCanonicalPath
               (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "plan-auto-other-" (System/currentTimeMillis)))
                 .mkdirs))]
      (try
        (with-redefs [plan/dossier-default-base-dir (constantly tmp)]
          (plan/plan-auto-persist
           {:agent  (fake-other-agent)
            :input  "Q"
            :result {:answer "Saved plan: x\nNext: y"}}))
        (is (not (.exists (io/file tmp ".brainyard/agents/plan-agent")))
            "explore-agent invocation should not create plan-agent dirs")
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f))))))

  (testing "plan-agent invocation triggers materialization"
    (let [tmp (.getCanonicalPath
               (doto (io/file (System/getProperty "java.io.tmpdir")
                              (str "plan-auto-fake-" (System/currentTimeMillis)))
                 .mkdirs))]
      (try
        (with-redefs [plan/dossier-default-base-dir (constantly tmp)]
          (plan/plan-auto-persist
           {:agent  (fake-plan-agent)
            :input  "Plan v2 checkout"
            :result {:answer (str "Saved plan: .brainyard/agents/plan-agent/plans/ship-v2.md\n"
                                  "Next: (call-tool \"todo-agent\" ...)")}}))
        (let [dossiers (->> (io/file tmp ".brainyard/agents/plan-agent/dossiers/")
                            .listFiles
                            (filter #(.isFile %)))]
          (is (= 1 (count dossiers)))
          (is (str/includes? (.getName (first dossiers)) "ship-v2")))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f)))))))
