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
;; exec$dossier-slug
;; ============================================================================

(deftest test-dossier-slug-determinism
  (testing "same question → same slug"
    (let [q "Drive ship-v2-checkout to completion"
          s1 (:slug (exec/exec$dossier-slug :question q))
          s2 (:slug (exec/exec$dossier-slug :question q))]
      (is (= s1 s2))
      (is (= "ship-v2-checkout-completion" s1)
          "stopwords (incl. 'drive') drop, leaving the verbs")))

  (testing "blank → fallback slug 'exec'"
    (is (= "exec" (:slug (exec/exec$dossier-slug :question ""))))
    (is (= "exec" (:slug (exec/exec$dossier-slug :question "exec drive advance")))))

  (testing "max-chars cap"
    (let [long-q (str/join " " (repeat 30 "supercalifragilistic"))]
      (is (<= (count (:slug (exec/exec$dossier-slug :question long-q))) 60))
      (is (<= (count (:slug (exec/exec$dossier-slug :question long-q :max-chars 7))) 7))))

  (testing "validation"
    (is (contains? (exec/exec$dossier-slug :question 123) :error))
    (is (contains? (exec/exec$dossier-slug :question "x" :max-chars 0) :error))))

;; ============================================================================
;; exec$dossier-frontmatter + read-dossier round-trip
;; ============================================================================

(deftest test-dossier-frontmatter-and-read-roundtrip
  (let [tmp (tmp-dir "exec-doss-rt")]
    (try
      (let [{:keys [frontmatter]}
            (exec/exec$dossier-frontmatter
             :slug "ship-v2-checkout"
             :todo-path ".brainyard/agents/todo-agent/todos/ship-v2-checkout.md"
             :plan-path ".brainyard/agents/plan-agent/plans/ship-v2-checkout.md"
             :todo-dossier ".brainyard/agents/todo-agent/dossiers/x.md"
             :plan-dossier ".brainyard/agents/plan-agent/dossiers/y.md"
             :pre {:verdict :go
                   :checks {:c1_todo_dossier :pass :c2_todo_postflight :pass
                            :c3_plan_postflight :pass :c4_todo_present :pass
                            :c5_routing_tags :pass :c6_tools_available :pass
                            :c7_tree_clean :pass :c8_resume :informational}
                   :acceptance ["feature-flag toggleable" "all unit tests green"]
                   :resume_from nil}
             :execute {:budget {:max_items_per_turn 5 :used 3 :reason_for_stop :hard-blocker}
                       :items_advanced [0 1 2]
                       :items_pending_after [3]
                       :evidence {0 {:ok true :via :edit-agent
                                     :update_record ".brainyard/agents/edit-agent/edits/foo.md"}
                                  2 {:ok true :via :bash :exit 0}}}
             :post {:verdict :pass
                    :rubric {:r1 :pass :r2 :pass :r3 :pass :r4 :pass
                             :r5 :pass :r6 :n/a :r7 :pass}
                    :revision_applied false
                    :holds []
                    :acceptance_progress {"feature-flag toggleable" :evidence-recorded
                                          "all unit tests green" :evidence-recorded
                                          "p99 unchanged" :pending}}
             :handoff {:next_agent "exec-agent"
                       :next_call "(call-tool \"exec-agent\" {…})"})
            {:keys [path]} (exec/exec$dossier-write
                            :slug "ship-v2-checkout"
                            :content (str frontmatter "\n# body\n")
                            :base-dir tmp)
            parsed (exec/exec$read-dossier :path path :base-dir tmp)]

        (testing "scalar keys round-trip"
          (is (= "ship-v2-checkout" (:slug parsed)))
          (is (= "exec-agent" (:agent parsed)))
          (is (= ".brainyard/agents/todo-agent/todos/ship-v2-checkout.md" (:todo_path parsed)))
          (is (= ".brainyard/agents/plan-agent/plans/ship-v2-checkout.md" (:plan_path parsed)))
          (is (= ".brainyard/agents/todo-agent/dossiers/x.md" (:todo_dossier parsed)))
          (is (= ".brainyard/agents/plan-agent/dossiers/y.md" (:plan_dossier parsed))))

        (testing "pre sub-block carries acceptance"
          (is (= "go" (get-in parsed [:pre :verdict])))
          (is (= ["feature-flag toggleable" "all unit tests green"]
                 (get-in parsed [:pre :acceptance]))))

        (testing "execute sub-block: items + evidence"
          (is (= [0 1 2] (get-in parsed [:execute :items_advanced]))
              "flow-vector parser coerces ints back to longs")
          (is (= [3] (get-in parsed [:execute :items_pending_after])))
          (is (string? (get-in parsed [:execute :evidence]))
              "evidence map kept as raw flow-string for v1"))

        (testing "post sub-block + acceptance_progress"
          (is (= "pass" (get-in parsed [:post :verdict])))
          (is (string? (get-in parsed [:post :acceptance_progress])))
          (is (str/includes? (get-in parsed [:post :acceptance_progress])
                             "\"feature-flag toggleable\": evidence-recorded"))
          (is (str/includes? (get-in parsed [:post :acceptance_progress])
                             "\"all unit tests green\": evidence-recorded"))
          (is (str/includes? (get-in parsed [:post :acceptance_progress])
                             "\"p99 unchanged\": pending"))
          (testing "namespaced keyword :n/a survives round-trip"
            (is (str/includes? (get-in parsed [:post :rubric]) "r6: n/a"))))

        (testing "handoff next_agent"
          (is (= "exec-agent" (get-in parsed [:handoff :next_agent])))))

      (finally (delete-rec (io/file tmp))))))

(deftest test-dossier-frontmatter-validation
  (is (contains? (exec/exec$dossier-frontmatter :pre {} :post {}) :error)
      "missing :slug → error"))

;; ============================================================================
;; exec$dossier-write collision
;; ============================================================================

(deftest test-dossier-write-collision-suffix
  (let [tmp (tmp-dir "exec-doss-coll")]
    (try
      ;; dossier writers require YAML frontmatter on :content
      (let [fm "---\nslug: rerun\n---\n"
            c1 (str fm "x1") c2 (str fm "x2") c3 (str fm "x3")
            r1 (exec/exec$dossier-write :slug "rerun" :content c1 :base-dir tmp)
            r2 (exec/exec$dossier-write :slug "rerun" :content c2 :base-dir tmp)
            r3 (exec/exec$dossier-write :slug "rerun" :content c3 :base-dir tmp)]
        (is (= "rerun"   (:slug r1)))
        (is (= "rerun-2" (:slug r2)))
        (is (= "rerun-3" (:slug r3)))
        (is (= c1 (slurp (io/file (:path r1))))))
      (finally (delete-rec (io/file tmp))))))

;; ============================================================================
;; exec$dossier-index-append (with [+adv/-pend] progress)
;; ============================================================================

(deftest test-dossier-index-prepend-ordering
  (let [tmp (tmp-dir "exec-doss-idx")]
    (try
      (exec/exec$dossier-index-append :path "dossiers/a.md" :slug "alpha"
                                      :pre-verdict :go :post-verdict :pass
                                      :advanced 3 :pending 1
                                      :next-agent :exec-agent :base-dir tmp)
      (exec/exec$dossier-index-append :path "dossiers/b.md" :slug "alpha"
                                      :pre-verdict :go :post-verdict :pass
                                      :advanced 1 :pending 0
                                      :next-agent :eval-agent :base-dir tmp)
      (exec/exec$dossier-index-append :path "dossiers/c.md" :slug "beta"
                                      :pre-verdict :gather
                                      :next-agent :user :base-dir tmp)
      (let [content (slurp (io/file tmp ".brainyard/agents/exec-agent/INDEX.md"))
            lines   (->> (str/split-lines content) (remove str/blank?))]
        (is (= 3 (count lines)))
        ;; Newest first
        (is (str/includes? (nth lines 0) "beta"))
        (is (str/includes? (nth lines 0) "pre:gather"))
        (is (str/includes? (nth lines 0) "post:n/a"))
        (is (str/includes? (nth lines 0) "→ user"))
        (is (str/includes? (nth lines 1) "alpha"))
        (is (str/includes? (nth lines 1) "[+1 / -0]"))
        (is (str/includes? (nth lines 1) "→ eval-agent"))
        (is (str/includes? (nth lines 2) "[+3 / -1]"))
        (is (str/includes? (nth lines 2) "→ exec-agent")))
      (finally (delete-rec (io/file tmp))))))

;; ============================================================================
;; exec$next-handoff
;; ============================================================================

(deftest test-next-handoff-variants
  (testing "PASS + all done (empty pending) → eval-agent"
    (let [r (exec/exec$next-handoff :pre {:verdict :go} :post {:verdict :pass}
                                    :items-pending-after []
                                    :slug "x" :dossier-path "doss.md")]
      (is (= "eval-agent" (:next-agent r)))
      (is (str/includes? (:next-call r) "eval-agent"))))

  (testing "PASS + items pending → continue exec-agent"
    (let [r (exec/exec$next-handoff :pre {:verdict :go} :post {:verdict :pass}
                                    :items-pending-after [3]
                                    :slug "x" :dossier-path "doss.md")]
      (is (= "exec-agent" (:next-agent r)))
      (is (str/includes? (:next-call r) "Continue"))))

  (testing "HOLD → user"
    (let [r (exec/exec$next-handoff :pre {:verdict :go} :post {:verdict :hold})]
      (is (= "user" (:next-agent r)))
      (is (str/includes? (str/lower-case (:next-call r)) "hold"))))

  (testing "GATHER → user (typically run todo-agent first)"
    (let [r (exec/exec$next-handoff :pre {:verdict :gather})]
      (is (= "user" (:next-agent r)))
      (is (str/includes? (:next-call r) "todo-agent"))))

  (testing "REFUSE → none"
    (let [r (exec/exec$next-handoff :pre {:verdict :refuse})]
      (is (= "none" (:next-agent r))))))

;; ============================================================================
;; exec$find — resume-support search
;; ============================================================================

(deftest test-exec-find
  (let [tmp (tmp-dir "exec-find")]
    (try
      (testing "no dossiers dir → empty matches"
        (is (= {:matches [] :n-matches 0}
               (exec/exec$find :slug "no-such" :base-dir tmp))))

      ;; Write three dossiers with two slugs to confirm filtering + ordering
      (let [{:keys [frontmatter]}
            (exec/exec$dossier-frontmatter
             :slug "alpha"
             :pre {:verdict :go :checks {:c1 :pass}}
             :execute {:items_advanced [0]
                       :items_pending_after [1 2]}
             :post {:verdict :pass :rubric {:r1 :pass}})]
        (Thread/sleep (long 1100))
        (exec/exec$dossier-write :slug "alpha" :content frontmatter :base-dir tmp))
      (Thread/sleep (long 1100))
      (exec/exec$dossier-write :slug "beta" :content "---\nslug: beta\n---\n" :base-dir tmp)
      (Thread/sleep (long 1100))
      (exec/exec$dossier-write :slug "alpha" :content "---\nslug: alpha\n---\n" :base-dir tmp)

      (testing "find returns matching dossiers, newest-first"
        (let [r (exec/exec$find :slug "alpha" :base-dir tmp)]
          (is (= 2 (:n-matches r)))
          (is (every? #(= "alpha" (:slug %)) (:matches r)))
          ;; Filename starts with timestamp; newest first means the second
          ;; alpha dossier appears first.
          (let [first-ts (-> r :matches first :path
                             (str/split #"/") last (str/split #"-") first)
                second-ts (-> r :matches second :path
                              (str/split #"/") last (str/split #"-") first)]
            (is (>= (compare first-ts second-ts) 0)
                "first match is newer than second"))))

      (testing "find for unrelated slug"
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
        (let [{:keys [path]} (exec/exec$dossier-write :slug "real" :content "---\nslug: real\n---\nx" :base-dir tmp)
              answer (str "Saved todo: x.md\nSaved dossier: " path "\nNext: ...")
              r (exec/materialize-auto-dossier!
                 {:answer answer :question "Q" :base-dir tmp})]
          (is (nil? r))
          (let [dossiers (->> (io/file tmp ".brainyard/agents/exec-agent/dossiers/")
                              .listFiles
                              (filter #(.isFile %)))]
            (is (= 1 (count dossiers))
                "exactly one dossier — the pre-existing real one")))
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
