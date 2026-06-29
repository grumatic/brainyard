;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.exec-test
  "Tests for exec-agent dossier helpers + auto-persist hook (mirror of
   plan-test / todo-test). Helper unit tests for the seven exec$* commands
   plus integration tests for the materialize-auto-dossier! hook covering
   all four answer shapes (PASS-continue, PASS-done, HOLD, GATHER), the
   hallucinated-path replacement, and the agent-scoping check via reified
   IAgent fakes."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.exec :as exec]))

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
;; Dossier read seam + resume search + handoff fn unit tests
;;
;; The write-side dossier helper chain (exec$dossier-slug / -frontmatter /
;; -write / -index-append / exec$next-handoff) is RETIRED — the LLM authors the
;; evidence dossier as markdown directly. What survives: the deterministic
;; readers (exec$read-dossier, exec$find) and the pure handoff fn.
;; ============================================================================

(defn- write-dossier!
  "Spit a §7 exec dossier under <base-dir>/.brainyard/agents/exec-agent/dossiers/.
   Returns the repo-relative path."
  [base-dir filename content]
  (let [f (io/file base-dir ".brainyard/agents/exec-agent/dossiers" filename)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    (str ".brainyard/agents/exec-agent/dossiers/" filename)))

(def ^:private sample-exec-dossier
  "A model-authored §7 exec dossier: top-level todo_dossier/plan_dossier; blocks
   for pre/execute/post/handoff; flow maps for checks/rubric/evidence/
   acceptance_progress; flow vecs for acceptance/items_advanced."
  (str "---\n"
       "slug: ship-v2-checkout\nagent: exec-agent\ncreated: 2026-06-29T00:00:00Z\n"
       "todo_path: .brainyard/agents/todo-agent/todos/ship-v2-checkout.md\n"
       "plan_path: .brainyard/agents/plan-agent/plans/ship-v2-checkout.md\n"
       "todo_dossier: .brainyard/agents/todo-agent/dossiers/x.md\n"
       "plan_dossier: .brainyard/agents/plan-agent/dossiers/y.md\n\n"
       "pre:\n"
       "  verdict: go\n"
       "  checks: {c1: pass, c2: pass, c8: informational}\n"
       "  acceptance: [\"feature-flag toggleable\", \"all unit tests green\"]\n\n"
       "execute:\n"
       "  budget: {max_items_per_turn: 5, used: 3, reason_for_stop: hard-blocker}\n"
       "  items_advanced: [0, 1, 2]\n"
       "  items_pending_after: [3]\n"
       "  evidence: {0: \"ok via edit-agent\", 2: \"ok via bash exit 0\"}\n\n"
       "post:\n"
       "  verdict: pass\n"
       "  rubric: {r1: pass, r6: n/a, r7: pass}\n"
       "  acceptance_progress: {\"feature-flag toggleable\": evidence-recorded, \"p99 unchanged\": pending}\n\n"
       "handoff:\n  next_agent: exec-agent\n  next_call: \"(exec-agent {…})\"\n"
       "---\n# Exec dossier — ship v2\n"))

(deftest test-dossier-read-roundtrip
  (let [tmp (tmp-dir "exec-doss-rt")]
    (try
      (let [path   (write-dossier! tmp "20260629-000000-ship-v2-checkout.md" sample-exec-dossier)
            parsed (exec/exec$read-dossier :path path :base-dir tmp)]
        (testing "scalar keys (top-level todo_dossier/plan_dossier)"
          (is (= "ship-v2-checkout" (:slug parsed)))
          (is (= "exec-agent" (:agent parsed)))
          (is (= ".brainyard/agents/todo-agent/todos/ship-v2-checkout.md" (:todo_path parsed)))
          (is (= ".brainyard/agents/todo-agent/dossiers/x.md" (:todo_dossier parsed)))
          (is (= ".brainyard/agents/plan-agent/dossiers/y.md" (:plan_dossier parsed))))
        (testing "pre carries acceptance"
          (is (= "go" (get-in parsed [:pre :verdict])))
          (is (= ["feature-flag toggleable" "all unit tests green"]
                 (get-in parsed [:pre :acceptance]))))
        (testing "execute: items vectors + evidence raw flow-string"
          (is (= [0 1 2] (get-in parsed [:execute :items_advanced])))
          (is (= [3] (get-in parsed [:execute :items_pending_after])))
          (is (string? (get-in parsed [:execute :evidence]))))
        (testing "post + acceptance_progress raw flow-string; :n/a survives"
          (is (= "pass" (get-in parsed [:post :verdict])))
          (is (str/includes? (get-in parsed [:post :acceptance_progress])
                             "\"feature-flag toggleable\": evidence-recorded"))
          (is (str/includes? (get-in parsed [:post :rubric]) "r6: n/a")))
        (testing "handoff next_agent"
          (is (= "exec-agent" (get-in parsed [:handoff :next_agent])))))
      (finally (delete-rec (io/file tmp))))))

(deftest test-handoff-from-state-variants
  (testing "PASS + all done (empty pending) → eval-agent"
    (let [r (#'exec/handoff-from-state :go :pass [] "x" "doss.md")]
      (is (= "eval-agent" (:next-agent r)))
      (is (str/includes? (:next-call r) ":agent-context \"doss.md\""))))
  (testing "PASS + items pending → continue exec-agent"
    (let [r (#'exec/handoff-from-state :go :pass [3] "x" "doss.md")]
      (is (= "exec-agent" (:next-agent r)))
      (is (str/includes? (:next-call r) "Continue"))))
  (testing "HOLD → user"
    (let [r (#'exec/handoff-from-state :go :hold nil "x" nil)]
      (is (= "user" (:next-agent r)))
      (is (str/includes? (str/lower-case (:next-call r)) "hold"))))
  (testing "GATHER → user (todo-agent first)"
    (let [r (#'exec/handoff-from-state :gather nil nil "x" nil)]
      (is (= "user" (:next-agent r)))
      (is (str/includes? (:next-call r) "todo-agent"))))
  (testing "REFUSE → none"
    (is (= "none" (:next-agent (#'exec/handoff-from-state :refuse nil nil "x" nil))))))

(deftest test-exec-find
  (let [tmp (tmp-dir "exec-find")]
    (try
      (testing "no dossiers dir → empty matches"
        (is (= {:matches [] :n-matches 0}
               (exec/exec$find :slug "no-such" :base-dir tmp))))

      ;; Seed three dossiers (two slugs) with sortable timestamp filenames.
      (write-dossier! tmp "20260101-000000-alpha.md"
                      "---\nslug: alpha\nagent: exec-agent\nexecute:\n  items_advanced: [0]\n  items_pending_after: [1, 2]\n---\n")
      (write-dossier! tmp "20260102-000000-beta.md"  "---\nslug: beta\n---\n")
      (write-dossier! tmp "20260103-000000-alpha.md" "---\nslug: alpha\n---\n")

      (testing "find returns matching dossiers, newest-first"
        (let [r (exec/exec$find :slug "alpha" :base-dir tmp)]
          (is (= 2 (:n-matches r)))
          (is (every? #(= "alpha" (:slug %)) (:matches r)))
          (is (str/includes? (-> r :matches first :path) "20260103")
              "newest (20260103) alpha first")))

      (testing "find for unrelated slug → 0"
        (is (= 0 (:n-matches (exec/exec$find :slug "no-such" :base-dir tmp)))))

      (testing "validation"
        (is (contains? (exec/exec$find) :error)))

      (finally (delete-rec (io/file tmp))))))

;; ============================================================================
;; Auto-persist hook — materialize-auto-dossier!
;; ============================================================================

(defn- fake-exec-agent []
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

(defn- fake-other-agent []
  (reify ai.brainyard.agent.core.protocol/IAgent
    (agent-id [_] :todo-agent/test-instance)
    (agent-name [_] "test")
    (agent-description [_] "test")
    (user-id [_] "u1")
    (session-id [_] "s1")
    (defagent-type [_] :todo-agent)
    (process [_ _ _] nil)
    (get-tools [_] [])
    (get-state [_] {})))

(deftest test-materialize-pass-continue
  (testing "PASS + items remain (no Done:) → handoff stays exec-agent"
    (let [tmp (tmp-dir "exec-auto-cont")]
      (try
        (let [answer (str "## Exec turn\n\n"
                          "Saved todo: .brainyard/agents/todo-agent/todos/ship-v2.md\n"
                          "Saved dossier: .brainyard/agents/exec-agent/dossiers/HALLUCINATED.md\n"
                          "Next: (call-tool \"exec-agent\" {...})\n")
              r (exec/materialize-auto-dossier!
                 {:answer answer :question "Drive ship-v2" :base-dir tmp})]
          (is (= "ship-v2" (:slug r)) "slug derives from Saved todo: filename")
          (is (= :go (:pre-verdict r)))
          (is (= :pass (:post-verdict r)))
          (is (.isFile (io/file (:path r))))
          (is (not (.isFile (io/file tmp ".brainyard/agents/exec-agent/dossiers/HALLUCINATED.md")))
              "hallucinated path NOT created on disk")
          (let [idx (slurp (io/file tmp ".brainyard/agents/exec-agent/INDEX.md"))]
            (is (str/includes? idx "→ exec-agent"))
            (is (str/includes? idx "post:pass"))))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-pass-done
  (testing "PASS + Done: → handoff switches to eval-agent"
    (let [tmp (tmp-dir "exec-auto-done")]
      (try
        (let [answer (str "Saved todo: .brainyard/agents/todo-agent/todos/ship-v2.md\n"
                          "Done: ship-v2 — all 4 items advanced; recommend doc$update :status :completed + eval-agent.\n"
                          "Next: (call-tool \"eval-agent\" ...)\n")
              r (exec/materialize-auto-dossier!
                 {:answer answer :question "Drive" :base-dir tmp})]
          (is (= :pass (:post-verdict r)))
          (let [idx (slurp (io/file tmp ".brainyard/agents/exec-agent/INDEX.md"))]
            (is (str/includes? idx "→ eval-agent")
                "Done: line in answer should switch handoff to eval-agent")))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-hold
  (testing "HOLD answer materializes dossier with handoff = user"
    (let [tmp (tmp-dir "exec-auto-hold")]
      (try
        (let [answer (str "Saved todo: x.md\n"
                          "Hold: item 2 edit-agent rolled back; verify failed\n"
                          "Suggested: amend the plan and re-run\n")
              r (exec/materialize-auto-dossier!
                 {:answer answer :question "Q" :base-dir tmp})]
          (is (= :hold (:post-verdict r)))
          (let [idx (slurp (io/file tmp ".brainyard/agents/exec-agent/INDEX.md"))]
            (is (str/includes? idx "post:hold"))
            (is (str/includes? idx "→ user"))))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-gather
  (testing "GATHER answer materializes dossier without todo_path"
    (let [tmp (tmp-dir "exec-auto-gather")]
      (try
        (let [answer (str "## PRE-FLIGHT GATHER\n\n"
                          "I cannot execute without a todo-agent dossier.\n\n"
                          "Need: a todo-agent `Saved dossier:` for this slug\n"
                          "Suggested: (call-tool \"todo-agent\" {...})\n")
              r (exec/materialize-auto-dossier!
                 {:answer answer :question "Drive" :base-dir tmp})]
          (is (= :gather (:pre-verdict r)))
          (is (nil? (:post-verdict r)))
          (let [idx (slurp (io/file tmp ".brainyard/agents/exec-agent/INDEX.md"))]
            (is (str/includes? idx "pre:gather"))
            (is (str/includes? idx "post:n/a"))))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-refuse
  (testing "REFUSE answer materializes dossier with handoff = none"
    (let [tmp (tmp-dir "exec-auto-refuse")]
      (try
        (let [answer (str "Refused: source plan-agent dossier post.verdict = hold; resolve plan holds first.\n"
                          "Suggested: (call-tool \"plan-agent\" {...})\n")
              r (exec/materialize-auto-dossier!
                 {:answer answer :question "Drive" :base-dir tmp})]
          (is (= :refuse (:pre-verdict r)))
          (is (nil? (:post-verdict r)))
          (let [idx (slurp (io/file tmp ".brainyard/agents/exec-agent/INDEX.md"))]
            (is (str/includes? idx "pre:refuse"))
            (is (str/includes? idx "→ none"))))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-materialize-skip-when-real-on-disk
  (testing "answer naming an EXISTING dossier path → no-op"
    (let [tmp (tmp-dir "exec-skip")]
      (try
        (let [rel (write-dossier! tmp "20260101-000000-real.md" "---\nslug: real\n---\nx")
              answer (str "Saved todo: x.md\nSaved dossier: " rel "\nNext: ...")
              r (exec/materialize-auto-dossier!
                 {:answer answer :question "Q" :base-dir tmp})]
          (is (nil? r))
          (let [dossiers (->> (io/file tmp ".brainyard/agents/exec-agent/dossiers/")
                              .listFiles
                              (filter #(.isFile %)))]
            (is (= 1 (count dossiers))
                "exactly one dossier — the pre-existing real one")))
        (finally (delete-rec (io/file tmp))))))

  (testing "MARKDOWN-BOLDED marker (`**Saved dossier:** `path``) → still a no-op"
    ;; Regression: capable models emit the marker bolded + backtick-wrapped.
    ;; The gate must see through the decoration or it double-writes the dossier.
    (let [tmp (tmp-dir "exec-skip-bold")]
      (try
        (let [rel (write-dossier! tmp "20260101-000000-real.md" "---\nslug: real\n---\nx")
              answer (str "## Exec — real ✅\n\n**Saved dossier:** `" rel "`\n\nDone: ok\nNext: x")
              r (exec/materialize-auto-dossier!
                 {:answer answer :question "Q" :base-dir tmp})]
          (is (nil? r) "bolded marker pointing at a real file must skip")
          (is (= 1 (->> (io/file tmp ".brainyard/agents/exec-agent/dossiers/")
                        .listFiles (filter #(.isFile %)) count))
              "no redundant second dossier"))
        (finally (delete-rec (io/file tmp)))))))

(deftest test-auto-persist-agent-scoping
  (testing "non-exec-agent invocations are ignored"
    (let [tmp (tmp-dir "exec-auto-other")]
      (try
        (with-redefs [exec/dossier-default-base-dir (constantly tmp)]
          (exec/exec-auto-persist
           {:agent  (fake-other-agent)
            :input  "Q"
            :result {:answer "Saved todo: x\nNext: y"}}))
        (is (not (.exists (io/file tmp ".brainyard/agents/exec-agent")))
            "todo-agent invocation should not create exec-agent dirs")
        (finally (delete-rec (io/file tmp))))))

  (testing "exec-agent invocation triggers materialization"
    (let [tmp (tmp-dir "exec-auto-fake")]
      (try
        (with-redefs [exec/dossier-default-base-dir (constantly tmp)]
          (exec/exec-auto-persist
           {:agent  (fake-exec-agent)
            :input  "Drive ship-v2"
            :result {:answer (str "Saved todo: .brainyard/agents/todo-agent/todos/ship-v2.md\n"
                                  "Next: (call-tool \"exec-agent\" ...)")}}))
        (let [dossiers (->> (io/file tmp ".brainyard/agents/exec-agent/dossiers/")
                            .listFiles
                            (filter #(.isFile %)))]
          (is (= 1 (count dossiers)))
          (is (str/includes? (.getName (first dossiers)) "ship-v2")))
        (finally (delete-rec (io/file tmp)))))))
