;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.eval-test
  "Tests for eval-agent dossier helpers + auto-persist hook (mirror of
   plan-test / todo-test / exec-test). Helper unit tests for the eight
   eval$* commands plus integration tests for the materialize-auto-
   dossier! hook covering all five answer shapes (ACHIEVED / PARTIALLY
   _ACHIEVED / NOT_ACHIEVED / GATHER / REFUSE), the hallucinated-path
   replacement, and the agent-scoping check via reified IAgent fakes."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.eval :as ev]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn- tmp-dir [prefix]
  (.getCanonicalPath
   (doto (io/file (System/getProperty "java.io.tmpdir")
                  (str prefix "-" (System/currentTimeMillis)))
     .mkdirs)))

(defn- delete-rec [^java.io.File f]
  (when (.isDirectory f) (run! delete-rec (.listFiles f)))
  (.delete f))

;; ============================================================================
;; eval$dossier-slug
;; ============================================================================

(deftest test-dossier-slug-determinism
  (testing "same question → same slug"
    (let [q "Score the ship-v2-checkout todo"
          s1 (:slug (ev/eval$dossier-slug :question q))
          s2 (:slug (ev/eval$dossier-slug :question q))]
      (is (= s1 s2))
      (is (= "ship-v2-checkout-todo" s1)
          "stopwords (incl. 'score') drop, leaving the verbs")))

  (testing "blank → fallback slug 'eval'"
    (is (= "eval" (:slug (ev/eval$dossier-slug :question ""))))
    (is (= "eval" (:slug (ev/eval$dossier-slug :question "eval evaluate score verdict")))))

  (testing "validation"
    (is (contains? (ev/eval$dossier-slug :question 123) :error))
    (is (contains? (ev/eval$dossier-slug :question "x" :max-chars 0) :error))))

;; ============================================================================
;; eval$verdict-write + eval$dossier-write paired slug semantics
;; ============================================================================

(deftest test-verdict-and-dossier-paired-slug
  (testing "first verdict + first dossier in the same turn share the slug"
    (let [tmp (tmp-dir "eval-paired")]
      (try
        (let [v1 (ev/eval$verdict-write :slug "rt" :content "v1" :base-dir tmp)
              d1 (ev/eval$dossier-write  :slug "rt" :content "---\nslug: rt\n---\nd1" :base-dir tmp)]
          (is (= "rt" (:slug v1)))
          (is (= "rt" (:slug d1))
              "dossier counter is per-dir; should NOT cross-suffix to rt-2"))
        (finally (delete-rec (io/file tmp))))))

  (testing "second verdict + second dossier both increment to -2"
    (let [tmp (tmp-dir "eval-paired-2")]
      (try
        (ev/eval$verdict-write :slug "rt" :content "v1" :base-dir tmp)
        (ev/eval$dossier-write  :slug "rt" :content "---\nslug: rt\n---\nd1" :base-dir tmp)
        (let [v2 (ev/eval$verdict-write :slug "rt" :content "v2" :base-dir tmp)
              d2 (ev/eval$dossier-write  :slug "rt" :content "---\nslug: rt\n---\nd2" :base-dir tmp)]
          (is (= "rt-2" (:slug v2)))
          (is (= "rt-2" (:slug d2))
              "second pair lines up; per-dir counters keep them aligned"))
        (finally (delete-rec (io/file tmp)))))))

;; ============================================================================
;; eval$dossier-frontmatter + read-dossier round-trip
;; ============================================================================

(deftest test-dossier-frontmatter-and-read-roundtrip
  (let [tmp (tmp-dir "eval-doss-rt")]
    (try
      (let [{:keys [frontmatter]}
            (ev/eval$dossier-frontmatter
             :slug "ship-v2-checkout"
             :verdict-path ".brainyard/agents/eval-agent/verdicts/ts-ship-v2.md"
             :exec-dossier ".brainyard/agents/exec-agent/dossiers/exec.md"
             :todo-dossier ".brainyard/agents/todo-agent/dossiers/todo.md"
             :plan-dossier ".brainyard/agents/plan-agent/dossiers/plan.md"
             :pre {:verdict :go
                   :checks {:c1_exec_dossier :pass :c2_exec_postflight :pass
                            :c3_evidence_present :pass :c4_plan_acceptance :pass
                            :c5_coverage_map :pass :c6_update_records_resolve :pass
                            :c7_no_double_score :informational}
                   :degradation []
                   :is_re_run false}
             :score {:verdict :achieved
                     :confidence :high
                     :criteria [{:criterion "feature-flag toggleable"
                                 :class :satisfied
                                 :confidence :high
                                 :items [0]
                                 :evidence [{:type :update-agent
                                             :record ".brainyard/agents/update-agent/edits/foo.md"
                                             :excerpt "wired in flags.clj line 42"}]}
                                {:criterion "all unit tests green"
                                 :class :satisfied
                                 :confidence :high
                                 :items [1 2]
                                 :evidence [{:type :bash :exit 0
                                             :excerpt "Ran 18 tests, 0 failures."}]}]
                     :gaps []
                     :recommendations [{:criterion nil :gap nil
                                        :next_agent :user
                                        :next_call "(doc$update :kind :todo :status :completed) + (doc$update :kind :plan :status :completed)"}]}
             :post {:verdict :pass
                    :rubric {:r1 :pass :r2 :pass :r3 :pass :r4 :pass
                             :r5 :pass :r6 :pass :r7 :pass}
                    :revision_applied false
                    :holds []}
             :handoff {:next_agent "user"
                       :next_call "(call-tool \"doc$update\" {…})"})
            {:keys [path]} (ev/eval$dossier-write
                            :slug "ship-v2-checkout"
                            :content (str frontmatter "\n# body\n")
                            :base-dir tmp)
            parsed (ev/eval$read-dossier :path path :base-dir tmp)]

        (testing "scalar keys round-trip"
          (is (= "ship-v2-checkout" (:slug parsed)))
          (is (= "eval-agent" (:agent parsed)))
          (is (= ".brainyard/agents/eval-agent/verdicts/ts-ship-v2.md" (:verdict_path parsed)))
          (is (= ".brainyard/agents/exec-agent/dossiers/exec.md" (:exec_dossier parsed)))
          (is (= ".brainyard/agents/todo-agent/dossiers/todo.md" (:todo_dossier parsed)))
          (is (= ".brainyard/agents/plan-agent/dossiers/plan.md" (:plan_dossier parsed))))

        (testing "pre sub-block"
          (is (= "go" (get-in parsed [:pre :verdict])))
          (is (= [] (get-in parsed [:pre :degradation])))
          (is (false? (get-in parsed [:pre :is_re_run]))))

        (testing "score sub-block — verdict + confidence are scalars"
          (is (= "achieved" (get-in parsed [:score :verdict])))
          (is (= "high" (get-in parsed [:score :confidence]))))

        (testing "score.criteria flow-vector-of-flow-maps round-trips as raw string"
          (is (string? (get-in parsed [:score :criteria])))
          (is (str/includes? (get-in parsed [:score :criteria]) "feature-flag toggleable"))
          (is (str/includes? (get-in parsed [:score :criteria]) "satisfied"))
          (is (str/includes? (get-in parsed [:score :criteria]) "update-agent")))

        (testing "score.recommendations flow-vector-of-flow-maps"
          (is (string? (get-in parsed [:score :recommendations])))
          (is (str/includes? (get-in parsed [:score :recommendations]) "doc$update")))

        (testing "post sub-block"
          (is (= "pass" (get-in parsed [:post :verdict]))))

        (testing "handoff next_agent"
          (is (= "user" (get-in parsed [:handoff :next_agent])))))

      (finally (delete-rec (io/file tmp))))))

(deftest test-dossier-frontmatter-validation
  (is (contains? (ev/eval$dossier-frontmatter :pre {} :score {}) :error)
      "missing :slug → error"))

;; ============================================================================
;; eval$dossier-index-append (with verdict + confidence)
;; ============================================================================

(deftest test-dossier-index-prepend
  (let [tmp (tmp-dir "eval-idx")]
    (try
      (ev/eval$dossier-index-append :path "dossiers/a.md" :slug "alpha"
                                    :pre-verdict :go :score-verdict :achieved
                                    :confidence :high :post-verdict :pass
                                    :next-agent :user :base-dir tmp)
      (ev/eval$dossier-index-append :path "dossiers/b.md" :slug "beta"
                                    :pre-verdict :go :score-verdict :not-achieved
                                    :confidence :medium :post-verdict :pass
                                    :next-agent :plan-agent :base-dir tmp)
      (ev/eval$dossier-index-append :path "dossiers/c.md" :slug "gamma"
                                    :pre-verdict :gather
                                    :next-agent :user :base-dir tmp)
      (let [content (slurp (io/file tmp ".brainyard/agents/eval-agent/INDEX.md"))
            lines   (->> (str/split-lines content) (remove str/blank?))]
        (is (= 3 (count lines)))
        ;; Newest first
        (is (str/includes? (nth lines 0) "gamma"))
        (is (str/includes? (nth lines 0) "pre:gather"))
        (is (str/includes? (nth lines 0) "verdict:n/a"))
        (is (str/includes? (nth lines 0) "post:n/a"))
        (is (str/includes? (nth lines 0) "→ user"))
        (is (str/includes? (nth lines 1) "beta"))
        (is (str/includes? (nth lines 1) "verdict:NOT-ACHIEVED"))
        (is (str/includes? (nth lines 1) "(conf:medium)"))
        (is (str/includes? (nth lines 1) "→ plan-agent"))
        (is (str/includes? (nth lines 2) "alpha"))
        (is (str/includes? (nth lines 2) "verdict:ACHIEVED"))
        (is (str/includes? (nth lines 2) "(conf:high)")))
      (finally (delete-rec (io/file tmp))))))

;; ============================================================================
;; eval$next-handoff
;; ============================================================================

(deftest test-next-handoff-variants
  (testing "ACHIEVED → user (todo + plan complete on confirm)"
    (let [r (ev/eval$next-handoff :pre {:verdict :go} :score {:verdict :achieved}
                                  :slug "ship-v2")]
      (is (= "user" (:next-agent r)))
      (is (str/includes? (:next-call r) "doc$update"))
      (is (str/includes? (:next-call r) ":kind :todo"))
      (is (str/includes? (:next-call r) ":kind :plan"))))

  (testing "PARTIALLY_ACHIEVED → user (per-criterion recs)"
    (let [r (ev/eval$next-handoff :pre {:verdict :go}
                                  :score {:verdict :partially-achieved}
                                  :slug "ship-v2")]
      (is (= "user" (:next-agent r)))
      (is (str/includes? (:next-call r) "score.recommendations"))))

  (testing "NOT_ACHIEVED → plan-agent primary"
    (let [r (ev/eval$next-handoff :pre {:verdict :go}
                                  :score {:verdict :not-achieved}
                                  :slug "ship-v2" :dossier-path "doss.md")]
      (is (= "plan-agent" (:next-agent r)))
      (is (str/includes? (:next-call r) "plan-agent"))))

  (testing "GATHER → user (typically run exec-agent first)"
    (let [r (ev/eval$next-handoff :pre {:verdict :gather})]
      (is (= "user" (:next-agent r)))
      (is (str/includes? (:next-call r) "exec-agent"))))

  (testing "REFUSE → none"
    (let [r (ev/eval$next-handoff :pre {:verdict :refuse})]
      (is (= "none" (:next-agent r))))))

;; ============================================================================
;; eval$find — C7 double-score detection + history
;; ============================================================================

(deftest test-eval-find
  (let [tmp (tmp-dir "eval-find")]
    (try
      (testing "no dossiers dir → empty matches"
        (is (= {:matches [] :n-matches 0}
               (ev/eval$find :slug "no-such" :base-dir tmp))))

      ;; Write three dossiers — two for the same slug, with explicit run-records
      (let [{:keys [frontmatter]}
            (ev/eval$dossier-frontmatter
             :slug "alpha"
             :exec-run-record ".brainyard/agents/exec-agent/runs/run-A.md"
             :pre {:verdict :go :checks {:c1 :pass}}
             :score {:verdict :achieved :confidence :high})]
        (Thread/sleep (long 1100))
        (ev/eval$dossier-write :slug "alpha" :content frontmatter :base-dir tmp))
      (Thread/sleep (long 1100))
      (let [{:keys [frontmatter]}
            (ev/eval$dossier-frontmatter
             :slug "beta"
             :exec-run-record ".brainyard/agents/exec-agent/runs/run-B.md"
             :pre {:verdict :go} :score {:verdict :achieved :confidence :medium})]
        (ev/eval$dossier-write :slug "beta" :content frontmatter :base-dir tmp))
      (Thread/sleep (long 1100))
      (let [{:keys [frontmatter]}
            (ev/eval$dossier-frontmatter
             :slug "alpha"
             :exec-run-record ".brainyard/agents/exec-agent/runs/run-C.md"
             :pre {:verdict :go} :score {:verdict :not-achieved :confidence :low})]
        (ev/eval$dossier-write :slug "alpha" :content frontmatter :base-dir tmp))

      (testing "find by slug — newest first"
        (let [r (ev/eval$find :slug "alpha" :base-dir tmp)]
          (is (= 2 (:n-matches r)))
          (is (every? #(= "alpha" (:slug %)) (:matches r)))
          (is (= "not-achieved" (:score_verdict (first (:matches r))))
              "newest is the run-C one")))

      (testing "find by slug + run-record (C7 same-exec-turn check)"
        (let [r (ev/eval$find :slug "alpha"
                              :run-record ".brainyard/agents/exec-agent/runs/run-A.md"
                              :base-dir tmp)]
          (is (= 1 (:n-matches r)))
          (is (= ".brainyard/agents/exec-agent/runs/run-A.md"
                 (-> r :matches first :exec_run_record)))))

      (testing "no match for unrelated slug"
        (is (= 0 (:n-matches (ev/eval$find :slug "no-such" :base-dir tmp)))))

      (testing "validation"
        (is (contains? (ev/eval$find) :error)))

      (finally (delete-rec (io/file tmp))))))

;; ============================================================================
;; Auto-persist hook — materialize-auto-dossier!
;; ============================================================================

(defn- fake-eval-agent []
  (reify ai.brainyard.agent.core.protocol/IAgent
    (agent-id [_] :eval-agent/test-instance)
    (agent-name [_] "test")
    (agent-description [_] "test")
    (user-id [_] "u1")
    (session-id [_] "s1")
    (defagent-type [_] :eval-agent)
    (process [_ _ _] nil)
    (get-tools [_] [])
    (get-state [_] {})))

(defn- fake-other-agent []
  (reify ai.brainyard.agent.core.protocol/IAgent
    (agent-id [_] :exec-agent/test-instance)
    (agent-name [_] "test")
    (agent-description [_] "test")
    (user-id [_] "u1")
    (session-id [_] "s1")
    (defagent-type [_] :exec-agent)
    (process [_ _ _] nil)
    (get-tools [_] [])
    (get-state [_] {})))

(deftest test-materialize-achieved
  (testing "ACHIEVED + high confidence → handoff to user"
    (let [tmp (tmp-dir "eval-auto-ach")]
      (try
        (let [answer (str "## Verdict — Ship v2\n\n"
                          "Saved verdict: .brainyard/agents/eval-agent/verdicts/ts-ship-v2.md\n"
                          "Saved dossier: .brainyard/agents/eval-agent/dossiers/HALLUCINATED.md\n"
                          "Verdict: ACHIEVED (confidence: high)\n"
                          "Next: user confirms (doc$update :kind :todo :status :completed) + (doc$update :kind :plan :status :completed)\n")
              r (ev/materialize-auto-dossier!
                 {:answer answer :question "Score" :base-dir tmp})]
          (is (= "ts-ship-v2" (:slug r))
              "slug derives from Saved verdict: filename")
          (is (= :go (:pre-verdict r)))
          (is (= :achieved (:score-verdict r)))
          (is (= :pass (:post-verdict r)))
          (is (.isFile (io/file (:path r))))
          (is (not (.isFile (io/file tmp ".brainyard/agents/eval-agent/dossiers/HALLUCINATED.md"))))
          (let [idx (slurp (io/file tmp ".brainyard/agents/eval-agent/INDEX.md"))]
            (is (str/includes? idx "→ user"))
            (is (str/includes? idx "verdict:ACHIEVED"))
            (is (str/includes? idx "(conf:high)"))))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-not-achieved
  (testing "NOT_ACHIEVED → handoff to plan-agent"
    (let [tmp (tmp-dir "eval-auto-na")]
      (try
        (let [answer (str "Saved verdict: .brainyard/agents/eval-agent/verdicts/x.md\n"
                          "Verdict: NOT_ACHIEVED (confidence: medium)\n"
                          "Recommended:\n"
                          "  - \"crit 1\": (call-tool \"plan-agent\" {…})\n"
                          "Next: (call-tool \"plan-agent\" {…})\n")
              r (ev/materialize-auto-dossier!
                 {:answer answer :question "Score" :base-dir tmp})]
          (is (= :not-achieved (:score-verdict r)))
          (let [idx (slurp (io/file tmp ".brainyard/agents/eval-agent/INDEX.md"))]
            (is (str/includes? idx "verdict:NOT-ACHIEVED"))
            (is (str/includes? idx "(conf:medium)"))
            (is (str/includes? idx "→ plan-agent"))))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-partially-achieved
  (testing "PARTIALLY_ACHIEVED → handoff to user (per-criterion recs)"
    (let [tmp (tmp-dir "eval-auto-pa")]
      (try
        (let [answer (str "Saved verdict: .brainyard/agents/eval-agent/verdicts/x.md\n"
                          "Verdict: PARTIALLY_ACHIEVED (confidence: medium)\n"
                          "Recommended:\n"
                          "  - \"crit 2\": (call-tool \"todo-agent\" {…})\n"
                          "Next: review per-criterion recommendations\n")
              r (ev/materialize-auto-dossier!
                 {:answer answer :question "Score" :base-dir tmp})]
          (is (= :partially-achieved (:score-verdict r)))
          (let [idx (slurp (io/file tmp ".brainyard/agents/eval-agent/INDEX.md"))]
            (is (str/includes? idx "verdict:PARTIALLY-ACHIEVED"))
            (is (str/includes? idx "→ user"))))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-gather
  (testing "GATHER answer materializes dossier with no score block"
    (let [tmp (tmp-dir "eval-auto-gather")]
      (try
        (let [answer (str "## PRE-FLIGHT GATHER\n\n"
                          "I cannot score without an exec-agent dossier.\n\n"
                          "Need: an exec-agent `Saved dossier:` for this slug\n"
                          "Suggested: (call-tool \"exec-agent\" {…})\n")
              r (ev/materialize-auto-dossier!
                 {:answer answer :question "Score" :base-dir tmp})]
          (is (= :gather (:pre-verdict r)))
          (is (nil? (:score-verdict r)))
          (is (nil? (:post-verdict r)))
          (let [idx (slurp (io/file tmp ".brainyard/agents/eval-agent/INDEX.md"))]
            (is (str/includes? idx "pre:gather"))
            (is (str/includes? idx "verdict:n/a"))
            (is (str/includes? idx "post:n/a"))))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-refuse
  (testing "REFUSE answer materializes dossier with handoff = none"
    (let [tmp (tmp-dir "eval-auto-refuse")]
      (try
        (let [answer (str "Refused: exec.evidence is empty; re-run exec-agent first.\n"
                          "Suggested: (call-tool \"exec-agent\" {…})\n")
              r (ev/materialize-auto-dossier!
                 {:answer answer :question "Score" :base-dir tmp})]
          (is (= :refuse (:pre-verdict r)))
          (is (nil? (:score-verdict r)))
          (let [idx (slurp (io/file tmp ".brainyard/agents/eval-agent/INDEX.md"))]
            (is (str/includes? idx "pre:refuse"))
            (is (str/includes? idx "→ none"))))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-skip-when-real-on-disk
  (testing "answer naming an EXISTING dossier path → no-op"
    (let [tmp (tmp-dir "eval-skip")]
      (try
        (let [{:keys [path]} (ev/eval$dossier-write :slug "real" :content "---\nslug: real\n---\nx" :base-dir tmp)
              answer (str "Saved verdict: x.md\nSaved dossier: " path
                          "\nVerdict: ACHIEVED (confidence: high)\nNext: ...")
              r (ev/materialize-auto-dossier!
                 {:answer answer :question "Score" :base-dir tmp})]
          (is (nil? r))
          (let [dossiers (->> (io/file tmp ".brainyard/agents/eval-agent/dossiers/")
                              .listFiles
                              (filter #(.isFile %)))]
            (is (= 1 (count dossiers))
                "exactly one dossier — the pre-existing real one")))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-auto-persist-agent-scoping
  (testing "non-eval-agent invocations are ignored"
    (let [tmp (tmp-dir "eval-auto-other")]
      (try
        (with-redefs [ev/dossier-default-base-dir (constantly tmp)]
          (ev/eval-auto-persist
           {:agent  (fake-other-agent)
            :input  "Q"
            :result {:answer "Saved verdict: x\nVerdict: ACHIEVED\nNext: y"}}))
        (is (not (.exists (io/file tmp ".brainyard/agents/eval-agent")))
            "exec-agent invocation should not create eval-agent dirs")
        (finally (delete-rec (io/file tmp))))))

  (testing "eval-agent invocation triggers materialization"
    (let [tmp (tmp-dir "eval-auto-fake")]
      (try
        (with-redefs [ev/dossier-default-base-dir (constantly tmp)]
          (ev/eval-auto-persist
           {:agent  (fake-eval-agent)
            :input  "Score ship-v2"
            :result {:answer (str "Saved verdict: .brainyard/agents/eval-agent/verdicts/ship-v2.md\n"
                                  "Verdict: ACHIEVED (confidence: high)\n"
                                  "Next: user confirms")}}))
        (let [dossiers (->> (io/file tmp ".brainyard/agents/eval-agent/dossiers/")
                            .listFiles
                            (filter #(.isFile %)))]
          (is (= 1 (count dossiers)))
          (is (str/includes? (.getName (first dossiers)) "ship-v2")))
        (finally (delete-rec (io/file tmp)))))))
