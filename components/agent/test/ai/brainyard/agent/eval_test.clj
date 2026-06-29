;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.eval-test
  "Tests for eval-agent's surviving READ seams + auto-persist backstop
   (lightweight redesign). The write-side helper chain (eval$dossier-slug /
   -frontmatter / -write / -index-append, eval$verdict-write, eval$next-handoff)
   is retired — the LLM authors the unified verdict file as markdown. What's
   tested: eval$read-verdict round-trip against a hand-authored §5 file,
   eval$find prior-verdict search, and materialize-auto-dossier! across the five
   answer shapes (ACHIEVED / PARTIALLY_ACHIEVED / NOT_ACHIEVED / GATHER /
   REFUSE), the markdown-bolded marker, hallucinated-path replacement, and the
   agent-scoping check via reified IAgent fakes."
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

(defn- write-verdict!
  "Spit a §5 verdict file under <base-dir>/.brainyard/agents/eval-agent/verdicts/.
   Returns the repo-relative path."
  [base-dir filename content]
  (let [f (io/file base-dir ".brainyard/agents/eval-agent/verdicts" filename)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    (str ".brainyard/agents/eval-agent/verdicts/" filename)))

(def ^:private sample-verdict
  "A model-authored §5 unified verdict: top-level verdict/confidence/source +
   criteria/recommendations block-lists of flow-maps."
  (str "---\n"
       "slug: ship-v2-checkout\nagent: eval-agent\ncreated: 2026-06-29T00:00:00Z\n"
       "verdict: PARTIALLY_ACHIEVED\nconfidence: medium\n"
       "source: {exec_dossier: .brainyard/agents/exec-agent/dossiers/exec.md, "
       "todo_dossier: .brainyard/agents/todo-agent/dossiers/todo.md, "
       "plan_dossier: .brainyard/agents/plan-agent/dossiers/plan.md, "
       "exec_run_record: .brainyard/agents/exec-agent/runs/run-A.md}\n"
       "degradation: [no-coverage-map]\nis_re_run: false\n\n"
       "criteria:\n"
       "  - {criterion: \"feature-flag toggleable\", class: satisfied, confidence: high, items: [0], evidence: \"edit rec foo.md; tests exit 0\"}\n"
       "  - {criterion: \"p99 unchanged\", class: partial, confidence: medium, items: [1, 2], evidence: \"1 of 2 items has a diff\"}\n\n"
       "recommendations:\n"
       "  - {criterion: \"p99 unchanged\", gap: \"second item not done\", next_agent: exec-agent, next_call: '(exec-agent {…})'}\n"
       "---\n# Verdict — ship-v2-checkout: PARTIALLY_ACHIEVED (confidence: medium)\n"))

;; ============================================================================
;; eval$read-verdict — frontmatter round-trip (unified §5 file)
;; ============================================================================

(deftest test-read-verdict-roundtrip
  (let [tmp (tmp-dir "eval-rv-rt")]
    (try
      (let [path   (write-verdict! tmp "20260629-000000-ship-v2-checkout.md" sample-verdict)
            parsed (ev/eval$read-verdict :path path :base-dir tmp)]
        (testing "top-level machine keys"
          (is (= "ship-v2-checkout" (:slug parsed)))
          (is (= "eval-agent" (:agent parsed)))
          (is (= "PARTIALLY_ACHIEVED" (:verdict parsed)))
          (is (= "medium" (:confidence parsed)))
          (is (false? (:is_re_run parsed)))
          (is (= ["no-coverage-map"] (:degradation parsed))))
        (testing "source kept as raw flow-map string (substring-searchable)"
          (is (string? (:source parsed)))
          (is (str/includes? (:source parsed) "exec_run_record"))
          (is (str/includes? (:source parsed) "run-A.md")))
        (testing "criteria block-list → vector of raw flow-map strings"
          (is (= 2 (count (:criteria parsed))))
          (is (str/includes? (first (:criteria parsed)) "feature-flag toggleable"))
          (is (str/includes? (first (:criteria parsed)) "satisfied"))
          (is (str/includes? (second (:criteria parsed)) "partial")))
        (testing "recommendations block-list"
          (is (= 1 (count (:recommendations parsed))))
          (is (str/includes? (first (:recommendations parsed)) "exec-agent"))))
      (finally (delete-rec (io/file tmp))))))

(deftest test-read-verdict-legacy-dual-read
  (testing "legacy pre/score/post/handoff dossier still parses (dual-read §10)"
    (let [tmp (tmp-dir "eval-rv-legacy")]
      (try
        (let [legacy (str "---\nslug: legacy\nagent: eval-agent\n"
                          "score:\n  verdict: achieved\n  confidence: high\n"
                          "post:\n  verdict: pass\n"
                          "handoff:\n  next_agent: user\n---\n# body\n")
              path   (write-verdict! tmp "20260101-000000-legacy.md" legacy)
              parsed (ev/eval$read-verdict :path path :base-dir tmp)]
          (is (= "achieved" (get-in parsed [:score :verdict])))
          (is (= "pass" (get-in parsed [:post :verdict])))
          (is (= "user" (get-in parsed [:handoff :next_agent]))))
        (finally (delete-rec (io/file tmp)))))))

;; ============================================================================
;; eval$find — C7 double-score detection + history
;; ============================================================================

(defn- verdict-fm
  "Minimal §5 frontmatter with a verdict + an exec_run_record in source."
  [slug verdict run-record]
  (str "---\nslug: " slug "\nagent: eval-agent\n"
       "verdict: " verdict "\nconfidence: high\n"
       "source: {exec_run_record: " run-record "}\n"
       "degradation: []\nis_re_run: false\n\ncriteria: []\nrecommendations: []\n---\n"))

(deftest test-eval-find
  (let [tmp (tmp-dir "eval-find")]
    (try
      (testing "no verdicts dir → empty matches"
        (is (= {:matches [] :n-matches 0}
               (ev/eval$find :slug "no-such" :base-dir tmp))))

      ;; Seed three verdicts (two slugs) with sortable timestamp filenames.
      (write-verdict! tmp "20260101-000000-alpha.md"
                      (verdict-fm "alpha" "ACHIEVED" ".brainyard/agents/exec-agent/runs/run-A.md"))
      (write-verdict! tmp "20260102-000000-beta.md"
                      (verdict-fm "beta" "ACHIEVED" ".brainyard/agents/exec-agent/runs/run-B.md"))
      (write-verdict! tmp "20260103-000000-alpha.md"
                      (verdict-fm "alpha" "NOT_ACHIEVED" ".brainyard/agents/exec-agent/runs/run-C.md"))

      (testing "find by slug — newest first"
        (let [r (ev/eval$find :slug "alpha" :base-dir tmp)]
          (is (= 2 (:n-matches r)))
          (is (every? #(= "alpha" (:slug %)) (:matches r)))
          (is (= "NOT_ACHIEVED" (:verdict (first (:matches r))))
              "newest (20260103) alpha first")))

      (testing "find by slug + run-record (C7 same-exec-turn check, substring on source)"
        (let [r (ev/eval$find :slug "alpha"
                              :run-record ".brainyard/agents/exec-agent/runs/run-A.md"
                              :base-dir tmp)]
          (is (= 1 (:n-matches r)))
          (is (str/includes? (str (:source (first (:matches r)))) "run-A.md"))))

      (testing "no match for unrelated slug / wrong run-record"
        (is (= 0 (:n-matches (ev/eval$find :slug "no-such" :base-dir tmp))))
        (is (= 0 (:n-matches (ev/eval$find :slug "alpha" :run-record "run-NOPE.md" :base-dir tmp)))))

      (testing "validation"
        (is (contains? (ev/eval$find) :error)))

      (finally (delete-rec (io/file tmp))))))

;; ============================================================================
;; Auto-persist backstop — materialize-auto-dossier! (one unified file)
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

(defn- verdicts-on-disk [tmp]
  (->> (io/file tmp ".brainyard/agents/eval-agent/verdicts/")
       .listFiles
       (filter #(.isFile %))))

(deftest test-materialize-achieved
  (testing "ACHIEVED + high confidence → handoff to user; ONE verdict file"
    (let [tmp (tmp-dir "eval-auto-ach")]
      (try
        (let [answer (str "## Verdict — Ship v2\n\n"
                          "Saved verdict: .brainyard/agents/eval-agent/verdicts/ts-ship-v2.md\n"
                          "Verdict: ACHIEVED (confidence: high)\n"
                          "Next: user confirms (doc$update :kind :todo :status :completed)\n")
              r (ev/materialize-auto-dossier!
                 {:answer answer :question "Score" :base-dir tmp})]
          (is (= "ts-ship-v2" (:slug r))
              "slug derives from Saved verdict: filename")
          (is (= :go (:pre-verdict r)))
          (is (= :achieved (:score-verdict r)))
          (is (.isFile (io/file (:path r))))
          (is (= 1 (count (verdicts-on-disk tmp))) "exactly one verdict file")
          (let [idx (slurp (io/file tmp ".brainyard/agents/eval-agent/INDEX.md"))]
            (is (str/includes? idx "→ user"))
            (is (str/includes? idx "ACHIEVED"))
            (is (str/includes? idx "high"))))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-not-achieved
  (testing "NOT_ACHIEVED → handoff to plan-agent"
    (let [tmp (tmp-dir "eval-auto-na")]
      (try
        (let [answer (str "Saved verdict: .brainyard/agents/eval-agent/verdicts/x.md\n"
                          "Verdict: NOT_ACHIEVED (confidence: medium)\n"
                          "Recommended:\n"
                          "  - \"crit 1\": (plan-agent {…})\n"
                          "Next: (plan-agent {…})\n")
              r (ev/materialize-auto-dossier!
                 {:answer answer :question "Score" :base-dir tmp})]
          (is (= :not-achieved (:score-verdict r)))
          (let [idx (slurp (io/file tmp ".brainyard/agents/eval-agent/INDEX.md"))]
            (is (str/includes? idx "NOT_ACHIEVED"))
            (is (str/includes? idx "medium"))
            (is (str/includes? idx "→ plan-agent"))))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-partially-achieved
  (testing "PARTIALLY_ACHIEVED → handoff to user (per-criterion recs)"
    (let [tmp (tmp-dir "eval-auto-pa")]
      (try
        (let [answer (str "Saved verdict: .brainyard/agents/eval-agent/verdicts/x.md\n"
                          "Verdict: PARTIALLY_ACHIEVED (confidence: medium)\n"
                          "Recommended:\n"
                          "  - \"crit 2\": (todo-agent {…})\n"
                          "Next: review per-criterion recommendations\n")
              r (ev/materialize-auto-dossier!
                 {:answer answer :question "Score" :base-dir tmp})]
          (is (= :partially-achieved (:score-verdict r)))
          (let [idx (slurp (io/file tmp ".brainyard/agents/eval-agent/INDEX.md"))]
            (is (str/includes? idx "PARTIALLY_ACHIEVED"))
            (is (str/includes? idx "→ user"))))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-gather
  (testing "GATHER answer materializes a verdict file with no score verdict"
    (let [tmp (tmp-dir "eval-auto-gather")]
      (try
        (let [answer (str "## PRE-FLIGHT GATHER\n\n"
                          "I cannot score without an exec-agent dossier.\n\n"
                          "Need: an exec-agent `Saved dossier:` for this slug\n"
                          "Suggested: (exec-agent {…})\n")
              r (ev/materialize-auto-dossier!
                 {:answer answer :question "Score the foo todo" :base-dir tmp})]
          (is (= :gather (:pre-verdict r)))
          (is (nil? (:score-verdict r)))
          (is (= 1 (count (verdicts-on-disk tmp))))
          (let [idx (slurp (io/file tmp ".brainyard/agents/eval-agent/INDEX.md"))]
            (is (str/includes? idx "GATHER"))
            (is (str/includes? idx "→ user"))))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-refuse
  (testing "REFUSE answer materializes a verdict file with handoff = none"
    (let [tmp (tmp-dir "eval-auto-refuse")]
      (try
        (let [answer (str "Refused: exec.evidence is empty; re-run exec-agent first.\n"
                          "Suggested: (exec-agent {…})\n")
              r (ev/materialize-auto-dossier!
                 {:answer answer :question "Score the bar todo" :base-dir tmp})]
          (is (= :refuse (:pre-verdict r)))
          (is (nil? (:score-verdict r)))
          (let [idx (slurp (io/file tmp ".brainyard/agents/eval-agent/INDEX.md"))]
            (is (str/includes? idx "REFUSE"))
            (is (str/includes? idx "→ none"))))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-skip-when-real-on-disk
  (testing "answer naming an EXISTING verdict path → no-op"
    (let [tmp (tmp-dir "eval-skip")]
      (try
        (let [rel (write-verdict! tmp "20260101-000000-real.md" "---\nslug: real\n---\nx")
              answer (str "Saved verdict: " rel
                          "\nVerdict: ACHIEVED (confidence: high)\nNext: ...")
              r (ev/materialize-auto-dossier!
                 {:answer answer :question "Score" :base-dir tmp})]
          (is (nil? r))
          (is (= 1 (count (verdicts-on-disk tmp)))
              "exactly one verdict — the pre-existing real one"))
        (finally (delete-rec (io/file tmp))))))

  (testing "MARKDOWN-BOLDED marker (`**Saved verdict:** `path``) → still a no-op"
    ;; Regression (same class as the exec fix): capable models bold the marker.
    (let [tmp (tmp-dir "eval-skip-bold")]
      (try
        (let [rel (write-verdict! tmp "20260101-000000-real.md" "---\nslug: real\n---\nx")
              answer (str "## Verdict — real ✅\n\n**Saved verdict:** `" rel "`\n\n"
                          "Verdict: ACHIEVED (confidence: high)\nNext: x")
              r (ev/materialize-auto-dossier!
                 {:answer answer :question "Score" :base-dir tmp})]
          (is (nil? r) "bolded marker pointing at a real file must skip")
          (is (= 1 (count (verdicts-on-disk tmp))) "no redundant second verdict"))
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

  (testing "eval-agent invocation triggers materialization (one verdict file)"
    (let [tmp (tmp-dir "eval-auto-fake")]
      (try
        (with-redefs [ev/dossier-default-base-dir (constantly tmp)]
          (ev/eval-auto-persist
           {:agent  (fake-eval-agent)
            :input  "Score ship-v2"
            :result {:answer (str "Saved verdict: .brainyard/agents/eval-agent/verdicts/ship-v2.md\n"
                                  "Verdict: ACHIEVED (confidence: high)\n"
                                  "Next: user confirms")}}))
        (let [verdicts (verdicts-on-disk tmp)]
          (is (= 1 (count verdicts)))
          (is (str/includes? (.getName (first verdicts)) "ship-v2")))
        (finally (delete-rec (io/file tmp)))))))
