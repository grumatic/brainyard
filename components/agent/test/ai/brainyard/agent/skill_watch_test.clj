;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.skill-watch-test
  "Tests for the skill-registry coherence observers (skill-watch).

   The load-bearing distinction: dirtiness is decided from a tool call's
   ARGUMENTS, never its results. `write-file` carries the skills path as an
   argument; `skills$read` carries it only in its result. Getting that backwards
   would make every read trigger a registry reload."
  (:require [clojure.test :refer [deftest testing is are]]
            [ai.brainyard.agent.common.skill-watch :as watch]))

;; ============================================================================
;; Argument scanning
;; ============================================================================

(deftest test-args-touch-skills-dir
  (testing "a skills path anywhere in the args is detected"
    (are [args] (true? (watch/args-touch-skills-dir? args))
      {:path ".brainyard/skills/lint-md/SKILL.md"}
      {:path "/Users/x/.brainyard/skills/lint-md/scripts/run.sh"}
      {:command "rm -r .brainyard/skills/stale-skill/"}
      {:file-path "/home/u/.brainyard/skills/a/SKILL.md" :content "# A"}
      ;; nested — a tool taking a collection of file specs
      {:files [{:path ".brainyard/skills/nested/SKILL.md"}]}))

  (testing "unrelated writes are ignored"
    (are [args] (false? (watch/args-touch-skills-dir? args))
      {:path "src/core.clj"}
      {:path ".brainyard/memory/index.md"}
      {:path ".brainyard/sessions/abc/trajectory.edn"}
      {:command "ls -la"}
      {}
      nil))

  (testing "a skill NAME is not a skill PATH"
    (is (false? (watch/args-touch-skills-dir? {:skill-name "lint-md"})))))

;; ============================================================================
;; Dirty decision
;; ============================================================================

(deftest test-registry-dirtying
  (testing "file writes under a skills root dirty the registry"
    (is (true? (watch/registry-dirtying?
                :write-file {:path ".brainyard/skills/a/SKILL.md"} {:ok true}))))

  (testing "mutation commands dirty it by name, since they carry no path"
    (are [cmd] (true? (watch/registry-dirtying? cmd {:skill-name "a"} {:ok true}))
      :skills$write :skills$import :skills$install :skills$sync
      :skill-proposal$accept :skill-proposal$reject))

  (testing "reads never dirty it — this is why ARGS are scanned, not results"
    ;; skills$read's args name a skill; the .brainyard/skills/ path appears only
    ;; in its RESULT. Scanning results would reload on every read.
    (is (false? (watch/registry-dirtying?
                 :skills$read
                 {:skill-name "lint-md"}
                 {:path "/Users/x/.brainyard/skills/lint-md" :content "# Lint"})))
    (is (false? (watch/registry-dirtying?
                 :skills$find
                 {:query "lint"}
                 {:result [{:path ".brainyard/skills/lint-md"}] :count 1}))))

  (testing "a failed write changed nothing on disk"
    (are [result] (false? (watch/registry-dirtying?
                           :write-file {:path ".brainyard/skills/a/SKILL.md"} result))
      {:error "permission denied"}
      {:error-message "no such directory"}))

  (testing "unrelated tools are ignored"
    (is (false? (watch/registry-dirtying? :bash {:command "ls"} {:ok true})))))

;; ============================================================================
;; Per-session flag
;; ============================================================================

(deftest test-dirty-flag-is-per-session
  (let [a (str "sess-a-" (System/nanoTime))
        b (str "sess-b-" (System/nanoTime))]
    (try
      (is (false? (watch/dirty? a)))
      (watch/mark-dirty! a)
      (is (true? (watch/dirty? a)))
      (is (false? (watch/dirty? b)) "one session's write must not flag another")
      (testing "marking twice is idempotent, clearing resets"
        (watch/mark-dirty! a)
        (watch/clear-dirty! a)
        (is (false? (watch/dirty? a))))
      (finally
        (watch/clear-dirty! a)
        (watch/clear-dirty! b)))))

(deftest test-handlers-tolerate-missing-agent
  (testing "handlers never throw when there is no agent in the event"
    (is (nil? (watch/touch-handler {:tool-name :write-file
                                    :args {:path ".brainyard/skills/a/SKILL.md"}
                                    :result {:ok true}})))
    (is (nil? (watch/reload-handler {})))))
