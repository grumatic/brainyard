;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.research-agent-test
  "Tests for research-agent: registration, inherited bt-factory (CoAct),
   curated agent-tools roster across the dossier substrate + the seven
   research$* helpers (positive + negative assertions per Hard Rules 1, 2
   of the design doc), instruction-content anchors that pin the dossier-
   bootstrap / state-machine / termination contracts, and unit tests for
   the research$* helper commands (id determinism, bootstrap idempotence,
   resume probe round-trip, NDJSON append-only, status flip integrity,
   verdict frontmatter, INDEX.md prepend ordering)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.research :as research]
            [ai.brainyard.agent.common.research-agent]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :as tool])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ============================================================================
;; Registration
;; ============================================================================

(deftest registration-test
  (testing "research-agent is registered as an agent"
    (let [agent-defs (tool/get-tool-defs :type :agent)]
      (is (contains? agent-defs :research-agent))
      (let [agent-def (get agent-defs :research-agent)]
        (is (= :research-agent (:id agent-def)))
        (is (some? (:fn agent-def)))
        (is (some? (:meta agent-def)))))))

;; ============================================================================
;; Inheritance via run-coact-derived
;;
;; research-agent pins :bt-factory explicitly (mirroring rlm-agent and
;; explore-agent) so direct entry-points (e.g. setup-agent-by-id used by
;; `bb tui ask`) that resolve agent metadata without going through
;; run-coact-derived still pick up the correct CoAct BT.
;; ============================================================================

(deftest inheritance-test
  (require 'ai.brainyard.agent.common.coact-agent)
  (let [res-def   (get (tool/get-tool-defs :type :agent) :research-agent)
        coact-def (get (tool/get-tool-defs :type :agent) :coact-agent)]

    (testing "research-agent's :fn is registered (the wrap-fn that invokes run-coact-derived)"
      (is (some? (:fn res-def))))

    (testing "research-agent pins :bt-factory explicitly (so setup-agent-by-id picks it up)"
      (let [bt-factory (get-in res-def [:meta :bt-factory])]
        (is (fn? bt-factory))
        (let [bt (bt-factory {:max-iterations 3})]
          (is (vector? bt))
          (is (= :sequence (first bt)))
          (is (= "coact.sequence" (namespace (get-in bt [1 :id])))))))

    (testing "default :max-iterations is 30 (vs CoAct's 20)"
      (let [bt-factory (get-in res-def [:meta :bt-factory])
            ;; bt-factory must accept a nil/missing :max-iterations and fall
            ;; back to 30. Calling with {} exercises the (or ... 30) branch.
            bt-default (bt-factory {})
            ;; Also: explicit 30 overrides nothing; should produce same shape.
            bt-30      (bt-factory {:max-iterations 30})]
        (is (vector? bt-default))
        (is (= :sequence (first bt-default)))
        ;; Same root shape as the explicit-30 build — sanity that the default
        ;; isn't degenerate.
        (is (= (first bt-default) (first bt-30)))))

    (testing "coact-agent (the parent) has the same bt-factory shape"
      (let [bt-factory (get-in coact-def [:meta :bt-factory])]
        (is (fn? bt-factory))
        (let [bt (bt-factory {:max-iterations 3})]
          (is (= :sequence (first bt)))
          (is (= "coact.sequence" (namespace (get-in bt [1 :id])))))))))

;; ============================================================================
;; Agent tools binding — positive + negative assertions
;; ============================================================================

(defn- research-tool-ids []
  (let [agent-def   (get (tool/get-tool-defs :type :agent) :research-agent)
        agent-tools (get-in agent-def [:meta :agent-tools])]
    (set (map (comp :id meta deref) (:tools agent-tools)))))

(deftest agent-tools-test
  (testing "research-agent :agent-tools covers the dossier substrate + invocation"
    (let [ids (research-tool-ids)]
      ;; Dossier substrate — files / shell / discovery
      (is (contains? ids :read-file))
      (is (contains? ids :write-file))
      (is (contains? ids :update-file))
      (is (contains? ids :grep))
      (is (contains? ids :bash))
      (is (contains? ids :search))

      ;; Web — direct access for one-off lookups
      (is (contains? ids :web-search))
      (is (contains? ids :fetch-url))

      ;; Synthesis — flat sub-LLM only
      (is (contains? ids :query$llm))

      ;; Bookkeeping — call-tool reaches the five specialists
      (is (contains? ids :list-tools))
      (is (contains? ids :get-tool-info))
      (is (contains? ids :call-tool))

      ;; Background fan-out for slow specialist calls
      (is (contains? ids :task$run))

      ;; Runtime config (max-iterations tuning)
      (is (contains? ids :agent-runtime$config))

      ;; research$* helpers
      (is (contains? ids :research$id))
      (is (contains? ids :research$resume?))
      (is (contains? ids :research$bootstrap))
      (is (contains? ids :research$append-log))
      (is (contains? ids :research$update-status))
      (is (contains? ids :research$write-verdict))
      (is (contains? ids :research$index-append))

      ;; Sibling dossier read-helpers (cherry-picked, READ-ONLY) — let
      ;; research-agent cheaply parse upstream dossier frontmatter for
      ;; data-driven move decisions (e.g. eval-agent's
      ;; score.recommendations drives heuristics 6/7/8).
      (is (contains? ids :plan$read-dossier))
      (is (contains? ids :todo$read-dossier))
      (is (contains? ids :exec$read-dossier))
      (is (contains? ids :eval$read-dossier))
      (is (contains? ids :update$read-record))))

  (testing "research-agent :agent-tools EXCLUDES forbidden + out-of-scope tools"
    (let [ids (research-tool-ids)]
      ;; Hard Rule 1 — no clone-self recursion
      (is (not (contains? ids :query$clone))
          "query$clone must not be in research-agent's roster (clone-self forbidden)")

      ;; Hard Rule 2 — plan/todo authoring lives in plan-agent / todo-agent;
      ;; reach via call-tool, not direct command access.
      (is (not (contains? ids :doc$create)))
      (is (not (contains? ids :doc$update)))
      (is (not (contains? ids :doc$delete)))

      ;; Skill authoring lives in skill-agent.
      (is (not (contains? ids :skills$write)))
      (is (not (contains? ids :skills$install)))

      ;; Sibling-agent WRITE-side dossier/verdict/edit helpers are NOT bound
      ;; — only the read-side cherry-picks are. Sibling writes go through
      ;; call-tool to their specialists (Hard Rule 2). This protects against
      ;; research-agent silently bypassing the specialists' pre/post-flight
      ;; gates by writing dossier files directly.
      (is (not (contains? ids :plan$dossier-write)))
      (is (not (contains? ids :todo$dossier-write)))
      (is (not (contains? ids :exec$dossier-write)))
      (is (not (contains? ids :eval$dossier-write)))
      (is (not (contains? ids :eval$verdict-write)))
      (is (not (contains? ids :update$apply)))
      (is (not (contains? ids :update$write))))))

;; ============================================================================
;; Instruction content anchors — pin the dossier + state-machine + termination
;; contract
;; ============================================================================

(deftest instruction-content-test
  (testing "instruction string contains the cardinal research-agent anchors"
    (let [agent-def   (get (tool/get-tool-defs :type :agent) :research-agent)
          instruction (get-in agent-def [:meta :instruction])]
      (is (string? instruction))
      (is (not (str/blank? instruction)))

      ;; Six-specialist routing is the headline contract (revised when the
      ;; four-agent pipeline (plan/todo/exec/eval) added pre/post-flight
      ;; gating + dossier handoff and update-agent joined as a 6th
      ;; reachable specialist for one-off safe edits).
      (is (str/includes? instruction "SIX SPECIALISTS"))
      (is (str/includes? instruction "explore-agent"))
      (is (str/includes? instruction "plan-agent"))
      (is (str/includes? instruction "todo-agent"))
      (is (str/includes? instruction "exec-agent"))
      (is (str/includes? instruction "eval-agent"))
      (is (str/includes? instruction "update-agent"))

      ;; Sibling-dossier handoff contract — research-agent threads
      ;; `Saved dossier:` paths between specialists.
      (is (str/includes? instruction ".brainyard/agents/plan-agent/dossiers/"))
      (is (str/includes? instruction ".brainyard/agents/todo-agent/dossiers/"))
      (is (str/includes? instruction ".brainyard/agents/exec-agent/dossiers/"))
      (is (str/includes? instruction ".brainyard/agents/eval-agent/dossiers/"))
      (is (str/includes? instruction ".brainyard/agents/eval-agent/verdicts/"))
      (is (str/includes? instruction ".brainyard/agents/update-agent/edits/"))
      (is (str/includes? instruction "Saved dossier:"))
      (is (str/includes? instruction "Saved verdict:"))
      (is (str/includes? instruction "Saved edit:"))

      ;; State-machine move I (UPDATE) for one-off safe edits
      (is (str/includes? instruction "I. UPDATE"))

      ;; Dossier — the cross-agent context carrier
      (is (str/includes? instruction ".brainyard/agents/research-agent/"))
      (is (str/includes? instruction "DOSSIER BOOTSTRAP"))
      (is (str/includes? instruction "purpose.md"))
      (is (str/includes? instruction "acceptance.md"))
      (is (str/includes? instruction "findings.log"))
      (is (str/includes? instruction "dossier.md"))

      ;; State machine — the LLM picks the next move per iteration
      (is (str/includes? instruction "STATE MACHINE"))
      (is (str/includes? instruction "EXPLORE"))
      (is (str/includes? instruction "PLAN-AUTHOR"))
      (is (str/includes? instruction "DECOMPOSE"))
      (is (str/includes? instruction "EXECUTE"))
      (is (str/includes? instruction "EVALUATE"))
      (is (str/includes? instruction "FINALIZE"))

      ;; Termination contract
      (is (str/includes? instruction ":achieved"))
      (is (str/includes? instruction ":partial"))
      (is (str/includes? instruction ":abandoned"))
      (is (str/includes? instruction "Saved research dossier:"))

      ;; Strict 4-step finalize: step 1 (update-status) is the discipline
      ;; gate that the 2026-05-10 smoke showed the LLM was skipping. Pin
      ;; the language so the rule survives future instruction edits.
      (is (str/includes? instruction "Step 1"))
      (is (str/includes? instruction "research$update-status"))
      (is (str/includes? instruction "research$write-verdict"))
      (is (str/includes? instruction "research$index-append"))
      (is (str/includes? instruction "REJECT")
          "instruction must warn that write-verdict rejects mismatched :achieved")
      (is (str/includes? instruction "DO NOT include a")
          "instruction must warn against the duplicate-heading footgun")

      ;; Hard Rule 1 — stay flat, no clone-self dispatch
      (is (str/includes? instruction "clone-self"))

      ;; Resume contract
      (is (str/includes? instruction "RESUMING"))
      (is (str/includes? instruction "@<research-id>")))))

(deftest tool-context-content-test
  (testing "tool-context names tools and the five specialists"
    (let [agent-def    (get (tool/get-tool-defs :type :agent) :research-agent)
          tool-context (get-in agent-def [:meta :tool-context])]
      (is (string? tool-context))
      (is (not (str/blank? tool-context)))

      ;; Specialists — the call-tool targets (six, incl. update-agent)
      (is (str/includes? tool-context "explore-agent"))
      (is (str/includes? tool-context "plan-agent"))
      (is (str/includes? tool-context "todo-agent"))
      (is (str/includes? tool-context "exec-agent"))
      (is (str/includes? tool-context "eval-agent"))
      (is (str/includes? tool-context "update-agent"))

      ;; Substrate tools named explicitly so the model can find them
      (is (str/includes? tool-context "read-file"))
      (is (str/includes? tool-context "write-file"))
      (is (str/includes? tool-context "update-file"))
      (is (str/includes? tool-context "grep"))
      (is (str/includes? tool-context "bash"))

      ;; Synthesis primitive
      (is (str/includes? tool-context "query$llm"))

      ;; Invocation pattern documented (the only way to reach specialists)
      (is (str/includes? tool-context "kebab-case"))

      ;; research$* helpers documented
      (is (str/includes? tool-context "research$id"))
      (is (str/includes? tool-context "research$resume?"))
      (is (str/includes? tool-context "research$bootstrap"))
      (is (str/includes? tool-context "research$append-log"))
      (is (str/includes? tool-context "research$update-status"))
      (is (str/includes? tool-context "research$write-verdict"))
      (is (str/includes? tool-context "research$index-append"))

      ;; Sibling dossier read-helper cherry-picks documented
      (is (str/includes? tool-context "plan$read-dossier"))
      (is (str/includes? tool-context "todo$read-dossier"))
      (is (str/includes? tool-context "exec$read-dossier"))
      (is (str/includes? tool-context "eval$read-dossier"))
      (is (str/includes? tool-context "update$read-record")))))

;; ============================================================================
;; I/O contract — :inputs / :outputs shape
;; ============================================================================

(deftest io-contract-test
  (testing "research-agent declares :question + optional :agent-context inputs"
    (let [agent-def    (get (tool/get-tool-defs :type :agent) :research-agent)
          input-schema (get-in agent-def [:meta :input-schema])
          entries      (tool/malli-map-entries input-schema)
          by-key       (into {} (map (juxt tool/malli-map-entry-key identity)) entries)]
      (is (contains? by-key :question))
      (is (contains? by-key :agent-context))
      ;; question is required; agent-context is optional (for resume / handoff).
      ;; In Malli [:map ...] form the :optional flag lives in the entry-props
      ;; slot ([:agent-context {:optional true} <schema>]).
      (let [opts (tool/malli-map-entry-props (get by-key :agent-context))]
        (is (true? (:optional opts))
            "agent-context should be optional (used for @<research-id> resume)"))))

  (testing "research-agent declares :answer output"
    (let [agent-def    (get (tool/get-tool-defs :type :agent) :research-agent)
          output-schema (get-in agent-def [:meta :output-schema])]
      (is (some #(= :answer (first %)) (rest output-schema))))))

;; ============================================================================
;; Helper test fixtures
;; ============================================================================

(defn- make-tmp-dir []
  (-> (Files/createTempDirectory "research-test-" (into-array FileAttribute []))
      .toFile
      .getAbsolutePath))

(defn- delete-recursive [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)]
      (delete-recursive c)))
  (.delete f))

;; ============================================================================
;; research$id — determinism + stopwords + cap
;; ============================================================================

(deftest research-id-test
  (testing "same question → same slug"
    (let [q "Reduce bb tui cold start under 1 second"
          s1 (:slug (research/research$id :question q))
          s2 (:slug (research/research$id :question q))]
      (is (= s1 s2))
      (is (string? s1))))

  (testing "stopwords are dropped"
    (let [s (:slug (research/research$id :question "What is the loop guard"))]
      ;; "what" "is" "the" are stopwords → only "loop guard" survives.
      (is (= "loop-guard" s))))

  (testing "kebab-case normalization"
    (is (= "mcp-server-health-check"
           (:slug (research/research$id :question "MCP server health check!")))))

  (testing "60-char default cap"
    (let [long-q (str/join " " (repeat 30 "supercalifragilistic"))
          s      (:slug (research/research$id :question long-q))]
      (is (<= (count s) 60))))

  (testing "max-chars override"
    (is (<= (count (:slug (research/research$id
                           :question "research deep dive into the loop"
                           :max-chars 10)))
            10)))

  (testing "blank/all-stopwords → fallback slug 'research'"
    (is (= "research" (:slug (research/research$id :question ""))))
    (is (= "research" (:slug (research/research$id :question "what is the")))))

  (testing "validation"
    (is (contains? (research/research$id :question 123) :error))
    (is (contains? (research/research$id :question "x" :max-chars 0) :error))))

;; ============================================================================
;; research$resume? — pre-bootstrap probe
;; ============================================================================

(deftest research-resume-pre-bootstrap-test
  (let [tmp (make-tmp-dir)]
    (try
      (testing "no dossier exists → :exists? false"
        (is (= {:exists? false}
               (research/research$resume? :id "nonexistent" :base-dir tmp))))

      (testing "validation"
        (is (contains? (research/research$resume? :id 123 :base-dir tmp) :error)))

      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; research$bootstrap + research$resume? — round-trip
;; ============================================================================

(deftest research-bootstrap-test
  (let [tmp (make-tmp-dir)]
    (try
      (let [id "test-bootstrap"
            boot (research/research$bootstrap
                  :id id
                  :purpose "Test purpose."
                  :acceptance [{:id "a1" :text "First criterion" :status :open}
                               {:id "a2" :text "Second criterion" :status :open}]
                  :direction ["First step" "Second step"]
                  :base-dir tmp)
            dir (io/file tmp ".brainyard/agents/research-agent" id)]

        (testing "fresh bootstrap returns dir + dossier-path"
          (is (string? (:dir boot)))
          (is (string? (:dossier-path boot)))
          (is (not (contains? boot :exists?))))

        (testing "all expected files written"
          (is (.isDirectory dir))
          (is (.isFile (io/file dir "purpose.md")))
          (is (.isFile (io/file dir "acceptance.md")))
          (is (.isFile (io/file dir "direction.md")))
          (is (.isFile (io/file dir "dossier.md")))
          (is (.isFile (io/file dir "findings.log")))
          (is (.isDirectory (io/file dir "artifacts"))))

        (testing "findings.log starts empty"
          (is (= "" (slurp (io/file dir "findings.log")))))

        (testing "dossier.md contains acceptance ids in frontmatter"
          (let [body (slurp (io/file dir "dossier.md"))]
            (is (str/includes? body "research_id: test-bootstrap"))
            (is (str/includes? body "- id: a1"))
            (is (str/includes? body "- id: a2"))
            (is (str/includes? body "status: open"))))

        (testing "second bootstrap is idempotent (no overwrite)"
          (spit (io/file dir "purpose.md") "MARKER — should survive")
          (let [boot2 (research/research$bootstrap
                       :id id :purpose "different purpose"
                       :acceptance [] :direction []
                       :base-dir tmp)]
            (is (true? (:exists? boot2)))
            (is (= "MARKER — should survive"
                   (slurp (io/file dir "purpose.md"))))))

        (testing "resume? after bootstrap reflects frontmatter"
          (let [s (research/research$resume? :id id :base-dir tmp)]
            (is (true? (:exists? s)))
            (is (= :in-progress (:status s)))
            (is (= 1 (:last-iteration s)))
            (is (= {:a1 :open :a2 :open} (:acceptance-state s))))))

      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; research$append-log — NDJSON append-only
;; ============================================================================

(deftest research-append-log-test
  (let [tmp (make-tmp-dir)
        id  "test-log"]
    (try
      (research/research$bootstrap :id id :purpose "p"
                                   :acceptance [] :direction []
                                   :base-dir tmp)

      (testing "consecutive appends produce N NDJSON lines"
        (research/research$append-log :id id :iter 2 :agent "explore-agent"
                                      :summary "s1" :pointers {:plan_slug "x"}
                                      :base-dir tmp)
        (research/research$append-log :id id :iter 3 :agent "plan-agent"
                                      :summary "s2" :pointers {:plan_path "y"}
                                      :base-dir tmp)
        (let [lines (->> (slurp (io/file tmp ".brainyard/agents/research-agent" id "findings.log"))
                         str/split-lines
                         (remove str/blank?))]
          (is (= 2 (count lines)))
          (is (every? #(str/starts-with? % "{") lines))
          (is (every? #(str/ends-with? % "}") lines))
          (is (str/includes? (first lines) "\"iter\":2"))
          (is (str/includes? (second lines) "\"iter\":3"))
          (is (str/includes? (first lines) "\"plan_slug\":\"x\""))))

      (testing "missing dossier → error (must bootstrap first)"
        (is (contains? (research/research$append-log
                        :id "no-such-id" :iter 1 :agent "x" :summary "y"
                        :base-dir tmp)
                       :error)))

      (testing "validation"
        (is (contains? (research/research$append-log :id 1 :iter 1 :agent "x" :summary "y"
                                                     :base-dir tmp) :error))
        (is (contains? (research/research$append-log :id "x" :iter "bad" :agent "x" :summary "y"
                                                     :base-dir tmp) :error)))

      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; research$update-status — flip one criterion, leave others alone
;; ============================================================================

(deftest research-update-status-test
  (let [tmp (make-tmp-dir)
        id  "test-status"]
    (try
      (research/research$bootstrap
       :id id :purpose "p"
       :acceptance [{:id "a1" :text "first" :status :open}
                    {:id "a2" :text "second" :status :open}
                    {:id "a3" :text "third" :status :open}]
       :direction ["d1"]
       :base-dir tmp)

      (testing "flips a1 → :partial; siblings untouched"
        (let [r (research/research$update-status
                 :id id :criterion-id "a1" :status :partial :base-dir tmp)]
          (is (true? (:updated r)))
          (is (= :open (:from r)))
          (is (= :partial (:to r))))
        (let [s (research/research$resume? :id id :base-dir tmp)]
          (is (= {:a1 :partial :a2 :open :a3 :open}
                 (:acceptance-state s)))))

      (testing "flips a2 → :satisfied via keyword id"
        (research/research$update-status :id id :criterion-id :a2 :status :satisfied
                                         :base-dir tmp)
        (is (= {:a1 :partial :a2 :satisfied :a3 :open}
               (:acceptance-state (research/research$resume? :id id :base-dir tmp)))))

      (testing "non-existent criterion → error"
        (is (contains? (research/research$update-status
                        :id id :criterion-id "a99" :status :open
                        :base-dir tmp)
                       :error)))

      (testing "invalid status → error"
        (is (contains? (research/research$update-status
                        :id id :criterion-id "a1" :status :bogus
                        :base-dir tmp)
                       :error)))

      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; research$write-verdict — derives acceptance_outcome + iterations
;; ============================================================================

(deftest research-write-verdict-test
  (let [tmp (make-tmp-dir)
        id  "test-verdict"]
    (try
      (research/research$bootstrap
       :id id :purpose "p"
       :acceptance [{:id "a1" :text "x" :status :open}
                    {:id "a2" :text "y" :status :open}]
       :direction ["d1"]
       :base-dir tmp)
      (research/research$update-status :id id :criterion-id "a1" :status :satisfied
                                       :base-dir tmp)

      (testing "writes verdict.md with frontmatter + narrative"
        (let [v (research/research$write-verdict
                 :id id :status :partial
                 :narrative "Got most of the way. a1 satisfied, a2 still open."
                 :base-dir tmp)
              path (:path v)
              content (slurp (io/file path))]
          (is (string? path))
          (is (str/ends-with? path "verdict.md"))
          (is (str/includes? content "research_id: test-verdict"))
          (is (str/includes? content "status: partial"))
          (is (str/includes? content "iterations: 1"))
          (is (str/includes? content "acceptance_outcome:"))
          (is (str/includes? content "a1: satisfied"))
          (is (str/includes? content "a2: open"))
          (is (str/includes? content "## Verdict"))
          (is (str/includes? content "Got most of the way"))))

      (testing "string status accepted"
        (is (string? (:path (research/research$write-verdict
                             :id id :status "partial"
                             :narrative "ok" :base-dir tmp)))))

      (testing "invalid status → error"
        (is (contains? (research/research$write-verdict
                        :id id :status :bogus :narrative "x"
                        :base-dir tmp)
                       :error)))

      (finally (delete-recursive (io/file tmp))))))

(deftest research-write-verdict-validation-test
  ;; Mid-flight smoke (2026-05-10) showed the LLM could call write-verdict
  ;; with :achieved while criteria were still :open, producing a verdict.md
  ;; with status: achieved but acceptance_outcome all :open. Block that.
  (let [tmp (make-tmp-dir)
        id  "test-validate"]
    (try
      (research/research$bootstrap
       :id id :purpose "p"
       :acceptance [{:id "a1" :text "x" :status :open}
                    {:id "a2" :text "y" :status :open}]
       :direction ["d"]
       :base-dir tmp)

      (testing ":achieved blocked when any criterion is still :open"
        (let [r (research/research$write-verdict
                 :id id :status :achieved
                 :narrative "all done" :base-dir tmp)]
          (is (contains? r :error))
          (is (str/includes? (:error r) "a1:open"))
          (is (str/includes? (:error r) "a2:open"))
          (is (str/includes? (:error r) "research$update-status"))
          (is (not (.isFile (io/file tmp ".brainyard/agents/research-agent" id "verdict.md")))
              "verdict.md must NOT be written when validation fails")))

      (testing ":achieved blocked when any criterion is :partial"
        (research/research$update-status :id id :criterion-id "a1" :status :satisfied :base-dir tmp)
        (research/research$update-status :id id :criterion-id "a2" :status :partial   :base-dir tmp)
        (let [r (research/research$write-verdict
                 :id id :status :achieved
                 :narrative "ok" :base-dir tmp)]
          (is (contains? r :error))
          (is (str/includes? (:error r) "a2:partial"))))

      (testing ":achieved allowed when all criteria are :satisfied or :descoped"
        (research/research$update-status :id id :criterion-id "a2" :status :descoped :base-dir tmp)
        (let [r (research/research$write-verdict
                 :id id :status :achieved
                 :narrative "ok" :base-dir tmp)]
          (is (string? (:path r)))
          (is (.isFile (io/file tmp ".brainyard/agents/research-agent" id "verdict.md")))))

      (testing ":partial unaffected by validation (catch-all)"
        ;; Reset: bootstrap a fresh dossier with all-:open criteria.
        (let [id2 "test-partial"]
          (research/research$bootstrap
           :id id2 :purpose "p"
           :acceptance [{:id "a1" :text "x" :status :open}]
           :direction ["d"]
           :base-dir tmp)
          (let [r (research/research$write-verdict
                   :id id2 :status :partial
                   :narrative "what got done" :base-dir tmp)]
            (is (string? (:path r))
                ":partial should be allowed regardless of acceptance state"))))

      (testing ":abandoned unaffected by validation (giving up state)"
        (let [id3 "test-abandoned"]
          (research/research$bootstrap
           :id id3 :purpose "p"
           :acceptance [{:id "a1" :text "x" :status :open}]
           :direction ["d"]
           :base-dir tmp)
          (let [r (research/research$write-verdict
                   :id id3 :status :abandoned
                   :narrative "blocked" :base-dir tmp)]
            (is (string? (:path r))
                ":abandoned should be allowed regardless of acceptance state"))))

      (finally (delete-recursive (io/file tmp))))))

(deftest research-write-verdict-strips-duplicate-heading-test
  ;; Mid-flight smoke (2026-05-10) showed verdict.md ending up with two
  ;; consecutive `## Verdict` lines: one from the template, one from the
  ;; LLM's narrative. Strip the LLM's leading heading idempotently.
  (let [tmp (make-tmp-dir)
        id  "test-heading"]
    (try
      (research/research$bootstrap
       :id id :purpose "p"
       :acceptance [{:id "a1" :text "x" :status :satisfied}]
       :direction ["d"] :base-dir tmp)

      (testing "leading '## Verdict\\n' in narrative is stripped"
        (let [r (research/research$write-verdict
                 :id id :status :achieved
                 :narrative "## Verdict\n\nReal content here."
                 :base-dir tmp)
              content (slurp (io/file (:path r)))]
          ;; Exactly one '## Verdict' heading in the file.
          (is (= 1 (count (re-seq #"## Verdict" content))))
          (is (str/includes? content "Real content here."))))

      (testing "leading '# Verdict' (h1) is also stripped"
        (let [r (research/research$write-verdict
                 :id id :status :achieved
                 :narrative "# Verdict\n\nh1 form."
                 :base-dir tmp)
              content (slurp (io/file (:path r)))]
          (is (= 1 (count (re-seq #"## Verdict" content))))
          (is (not (re-find #"^# Verdict" content)))
          (is (str/includes? content "h1 form."))))

      (testing "narrative without leading heading is unchanged"
        (let [r (research/research$write-verdict
                 :id id :status :achieved
                 :narrative "Just prose, no heading."
                 :base-dir tmp)
              content (slurp (io/file (:path r)))]
          (is (= 1 (count (re-seq #"## Verdict" content))))
          (is (str/includes? content "Just prose, no heading."))))

      (testing "non-leading '## Verdict' (further down) is preserved"
        (let [r (research/research$write-verdict
                 :id id :status :achieved
                 :narrative "Intro paragraph.\n\n## Verdict details\n\nMore content."
                 :base-dir tmp)
              content (slurp (io/file (:path r)))]
          ;; Both headings present: template's '## Verdict' + the in-body
          ;; '## Verdict details' (different heading, only leading is stripped).
          (is (str/includes? content "## Verdict details"))
          (is (str/includes? content "Intro paragraph."))))

      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; research$index-append — newest-first prepend
;; ============================================================================

(deftest research-index-append-test
  (let [tmp (make-tmp-dir)]
    (try
      (testing "first append creates INDEX.md"
        (let [r (research/research$index-append
                 :id "first-id" :status :achieved
                 :one-line "first run"
                 :base-dir tmp)
              content (slurp (io/file tmp ".brainyard/agents/research-agent/INDEX.md"))]
          (is (true? (:appended r)))
          (is (str/starts-with? content "- "))
          (is (str/includes? content "[first-id]"))
          (is (str/includes? content "achieved"))
          (is (str/includes? content "first run"))))

      (testing "second append prepends (newest first)"
        (research/research$index-append
         :id "second-id" :status :partial
         :one-line "second run"
         :base-dir tmp)
        (let [content (slurp (io/file tmp ".brainyard/agents/research-agent/INDEX.md"))
              lines   (remove str/blank? (str/split-lines content))]
          (is (= 2 (count lines)))
          ;; Second-id is the most recent → first line.
          (is (str/includes? (first lines) "second-id"))
          (is (str/includes? (second lines) "first-id"))))

      (testing "long one-line is truncated to ≤ 200 chars"
        (let [long-text (str/join " " (repeat 100 "longwordxxxxxxxxxxxxx"))
              r (research/research$index-append
                 :id "long-id" :status :achieved
                 :one-line long-text
                 :base-dir tmp)
              ;; 200-char cap + "- YYYY-MM-DD HH:MM [long-id](long-id/) — achieved · "
              ;; prefix. Just assert the line itself is bounded reasonably.
              line (:line r)]
          (is (true? (:appended r)))
          (is (<= (count line) 350))))

      (testing "invalid status → error"
        (is (contains? (research/research$index-append
                        :id "x" :status :bogus :one-line "y"
                        :base-dir tmp)
                       :error)))

      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; Auto-finalize hook (P2)
;;
;; Hook signature: `(research-auto-finalize {:keys [agent input result]})`.
;; The hook gates on `(research-agent? agent)` and `(default-base-dir)`;
;; both are private fns we rebind with with-redefs so the test doesn't have
;; to construct a real agent record.
;; ============================================================================

(deftest hook-registration-test
  (testing "auto-finalize hook is registered on :agent.ask/post"
    (require 'ai.brainyard.agent.core.hooks)
    ;; Other test namespaces (hooks_test.clj) use a :each fixture that calls
    ;; hooks/reset-hooks! — which wipes the registry between test invocations.
    ;; Re-install before checking so the assertion is independent of polylith
    ;; test ordering.
    (research/install-auto-finalize!)
    (let [list-hooks (resolve 'ai.brainyard.agent.core.hooks/list-hooks)
          entries    (list-hooks :agent.ask/finalize)
          ids        (set (map :id entries))]
      (is (contains? ids :ai.brainyard.agent.common.research/research-auto-finalize)
          "research-auto-finalize hook must register on :agent.ask/finalize"))))

(deftest terminal-status-test
  (let [ts @#'research/terminal-status]
    (testing "empty acceptance-state → nil (degenerate)"
      (is (nil? (ts {}))))

    (testing "any :open → nil (mid-flight)"
      (is (nil? (ts {:a1 :open})))
      (is (nil? (ts {:a1 :satisfied :a2 :open})))
      (is (nil? (ts {:a1 :open :a2 :open}))))

    (testing "all :satisfied / :descoped → :achieved"
      (is (= :achieved (ts {:a1 :satisfied})))
      (is (= :achieved (ts {:a1 :satisfied :a2 :descoped})))
      (is (= :achieved (ts {:a1 :descoped :a2 :descoped}))))

    (testing "all :contradicted → :abandoned"
      (is (= :abandoned (ts {:a1 :contradicted})))
      (is (= :abandoned (ts {:a1 :contradicted :a2 :contradicted}))))

    (testing "mixed → :partial"
      (is (= :partial (ts {:a1 :satisfied :a2 :partial})))
      (is (= :partial (ts {:a1 :satisfied :a2 :contradicted})))
      (is (= :partial (ts {:a1 :partial :a2 :descoped}))))))

(deftest hook-skips-when-criteria-open-test
  (let [tmp (make-tmp-dir)
        question "What dominates startup?"
        rid (:slug (research/research$id :question question))]
    (try
      (research/research$bootstrap
       :id rid :purpose question
       :acceptance [{:id "a1" :text "x" :status :open}
                    {:id "a2" :text "y" :status :open}]
       :direction ["d"]
       :base-dir tmp)
      ;; Both criteria still :open — hook should bail.
      (with-redefs [research/research-agent?  (constantly true)
                    config/project-dir (constantly tmp)]
        (research/research-auto-finalize
         {:agent {} :input question :result {:answer "A long terminal-looking answer"}}))
      (testing "verdict.md NOT written while any :open remains"
        (is (not (.isFile (io/file tmp ".brainyard/agents/research-agent" rid "verdict.md"))))
        (is (not (.isFile (io/file tmp ".brainyard/agents/research-agent/INDEX.md")))))
      (finally (delete-recursive (io/file tmp))))))

(deftest hook-finalizes-when-all-non-open-test
  (let [tmp (make-tmp-dir)
        question "What dominates startup?"
        rid (:slug (research/research$id :question question))]
    (try
      (research/research$bootstrap
       :id rid :purpose question
       :acceptance [{:id "a1" :text "x" :status :open}
                    {:id "a2" :text "y" :status :open}]
       :direction ["d"]
       :base-dir tmp)
      (research/research$update-status :id rid :criterion-id "a1" :status :satisfied :base-dir tmp)
      (research/research$update-status :id rid :criterion-id "a2" :status :partial   :base-dir tmp)

      (with-redefs [research/research-agent?  (constantly true)
                    config/project-dir (constantly tmp)]
        (research/research-auto-finalize
         {:agent {} :input question
          :result {:answer "Reduced startup substantially. a1 satisfied, a2 partial."}}))

      (let [verdict (io/file tmp ".brainyard/agents/research-agent" rid "verdict.md")
            index   (io/file tmp ".brainyard/agents/research-agent/INDEX.md")]
        (testing "verdict.md written with derived :partial status"
          (is (.isFile verdict))
          (let [content (slurp verdict)]
            (is (str/includes? content "status: partial"))
            (is (str/includes? content "research_id: "))
            (is (str/includes? content rid))
            (is (str/includes? content "acceptance_outcome:"))
            (is (str/includes? content "a1: satisfied"))
            (is (str/includes? content "a2: partial"))))

        (testing "INDEX.md was appended"
          (is (.isFile index))
          (let [content (slurp index)]
            (is (str/includes? content (str "[" rid "]")))
            (is (str/includes? content "partial")))))
      (finally (delete-recursive (io/file tmp))))))

(deftest hook-idempotent-test
  (let [tmp (make-tmp-dir)
        question "another smoke question"
        rid (:slug (research/research$id :question question))]
    (try
      (research/research$bootstrap
       :id rid :purpose question
       :acceptance [{:id "a1" :text "x" :status :open}]
       :direction ["d"] :base-dir tmp)
      (research/research$update-status :id rid :criterion-id "a1" :status :satisfied :base-dir tmp)

      (with-redefs [research/research-agent?  (constantly true)
                    config/project-dir (constantly tmp)]
        (research/research-auto-finalize
         {:agent {} :input question :result {:answer "First answer"}})
        (let [verdict (io/file tmp ".brainyard/agents/research-agent" rid "verdict.md")
              first-content (slurp verdict)]
          ;; Second invocation should not overwrite — verdict.md exists already.
          (research/research-auto-finalize
           {:agent {} :input question :result {:answer "DIFFERENT answer body"}})
          (testing "second invocation does not overwrite verdict.md"
            (is (= first-content (slurp verdict))))))
      (finally (delete-recursive (io/file tmp))))))

(deftest hook-skips-when-already-finalized-by-llm-test
  (let [tmp (make-tmp-dir)
        question "third question"
        rid (:slug (research/research$id :question question))]
    (try
      (research/research$bootstrap
       :id rid :purpose question
       :acceptance [{:id "a1" :text "x" :status :open}]
       :direction ["d"] :base-dir tmp)
      (research/research$update-status :id rid :criterion-id "a1" :status :satisfied :base-dir tmp)

      ;; Answer carries the `Saved research dossier:` line — LLM did finalize.
      ;; The hook should bail without writing verdict.md.
      (with-redefs [research/research-agent?  (constantly true)
                    config/project-dir (constantly tmp)]
        (research/research-auto-finalize
         {:agent {} :input question
          :result {:answer "All done.\n\nSaved research dossier: .brainyard/agents/research-agent/foo/"}}))

      (testing "verdict.md NOT written when :answer already contains the contract line"
        (is (not (.isFile (io/file tmp ".brainyard/agents/research-agent" rid "verdict.md")))))
      (finally (delete-recursive (io/file tmp))))))

(deftest hook-skips-when-no-dossier-test
  (let [tmp (make-tmp-dir)]
    (try
      ;; No bootstrap — derived id has no dossier on disk.
      (with-redefs [research/research-agent?  (constantly true)
                    config/project-dir (constantly tmp)]
        (research/research-auto-finalize
         {:agent {} :input "totally novel question with no dossier"
          :result {:answer "I answered without a dossier."}}))

      (testing "no .brainyard/agents/research-agent/ created — run wasn't research-shaped"
        (let [base (io/file tmp ".brainyard/agents/research-agent")]
          ;; Either the dir doesn't exist OR if it does (from a prior test),
          ;; the specific id-derived subdir for this question is absent.
          (when (.isDirectory base)
            (is (zero? (count (filter #(.isDirectory %) (.listFiles base))))
                "hook must NOT retroactively create a dossier directory"))))
      (finally (delete-recursive (io/file tmp))))))

(deftest hook-defensive-on-exception-test
  (testing "exceptions inside the hook are caught and never re-thrown"
    ;; Force write-verdict to throw — hook must still return without raising.
    (with-redefs [research/research-agent?  (constantly true)
                  config/project-dir (constantly "/nonexistent/path/that/does/not/exist")]
      (is (nil? (research/research-auto-finalize
                 {:agent {} :input "x" :result {:answer "y"}}))))))

;; ============================================================================
;; P3 — Integration: setup-agent-by-id round-trip + specialist reachability
;;
;; These tests exercise the actual agent runtime path that `bb tui ask -a
;; research-agent` takes. They do NOT fire any LLM call — `setup-agent-by-id`
;; only creates the agent, doesn't `ask` it. Their job is to catch contract
;; drift between research-agent and:
;;   - the CoAct BT factory (which gets called at setup time)
;;   - the unified tool registry (which the LLM dispatches `call-tool` against)
;;   - the SCI sandbox helper-binding path (mimicked here via tool/invoke-tool)
;; ============================================================================

(deftest setup-agent-by-id-smoke-test
  (require 'ai.brainyard.agent.core.agent)
  (let [setup (resolve 'ai.brainyard.agent.core.agent/setup-agent-by-id)
        ag (setup :research-agent
                  :agent-session {:user-id "p3-test"
                                  :session-id (str "p3-" (System/nanoTime))}
                  :max-iterations 30)]
    (try
      (let [state   @(:!state ag)
            st-init @(:st-memory-init state)]

        (testing "agent is created + started + memory-managed"
          (is (keyword? (:agent-id ag)))
          (is (= "research-agent" (namespace (:agent-id ag))))
          (is (= :idle (:status state)))
          (is (some? (:memory-manager state))
              "memory-manager always created (default in-memory)"))

        (testing "behavior-tree wired in (CoAct via :bt-factory)"
          (is (some? (:behavior-tree state))))

        (testing "instruction + tool-context flow into st-memory-init"
          (is (string? (:instruction st-init)))
          (is (str/includes? (:instruction st-init) "RESEARCH-agent"))
          (is (string? (:tool-context st-init)))
          (is (str/includes? (:tool-context st-init) "research$id")))

        (testing "tools roster includes research$* helpers"
          ;; LLM-facing :name is a string (or :keyword pre-string-coerce);
          ;; normalize for either form.
          (let [names (->> (:tools st-init)
                           (map (comp str :name))
                           set)]
            (is (contains? names "research$id"))
            (is (contains? names "research$bootstrap"))
            (is (contains? names "research$resume?"))
            (is (contains? names "research$append-log"))
            (is (contains? names "research$update-status"))
            (is (contains? names "research$write-verdict"))
            (is (contains? names "research$index-append"))
            ;; call-tool is :visibility :hidden — it self-registers in the
            ;; tool registry for the MCP :server-name fallback path but
            ;; never appears in any agent's LLM-facing roster.
            (is (not (contains? names "call-tool")))
            (is (contains? names "query$llm")))))

      (finally (.close ag)))))

(deftest specialists-reachable-via-registry-test
  ;; The five specialists are NOT directly bound in research-agent's roster
  ;; (the LLM reaches them via `(call-tool "<name>" ...)`). This test asserts
  ;; the registry side of that contract — every specialist must be a
  ;; registered defagent so call-tool dispatch succeeds.
  (require 'ai.brainyard.agent.common.explore-agent
           'ai.brainyard.agent.common.plan-agent
           'ai.brainyard.agent.common.todo-agent
           'ai.brainyard.agent.common.exec-agent
           'ai.brainyard.agent.common.eval-agent)
  (testing "every research-agent specialist resolves via tool/get-tool-defs"
    (doseq [spec [:explore-agent :plan-agent :todo-agent :exec-agent :eval-agent]]
      (let [d (tool/get-tool-defs :id spec)]
        (is (some? d) (str spec " must be registered"))
        (is (= :agent (:type d))
            (str spec " must be of :type :agent"))))))

(deftest helpers-invokable-via-invoke-tool-test
  ;; The SCI sandbox dispatches helper calls through the tool registry. This
  ;; test exercises the exact same path: tool/invoke-tool with the helper id.
  ;; Catches drift between the helper's `defcommand` schema and what the
  ;; sandbox passes through.
  (let [tmp (make-tmp-dir)]
    (try
      (testing "research$id round-trips"
        (let [r (tool/invoke-tool :research$id {:question "hello world"})]
          (is (= "hello-world" (:slug r)))))

      (testing "research$resume? round-trips (no dossier)"
        (let [r (tool/invoke-tool :research$resume?
                                  {:id "no-such-id" :base-dir tmp})]
          (is (= false (:exists? r)))))

      (testing "research$bootstrap round-trips"
        (let [r (tool/invoke-tool :research$bootstrap
                                  {:id "invoke-test"
                                   :purpose "test purpose"
                                   :acceptance [{:id "a1" :text "x" :status :open}]
                                   :direction ["d"]
                                   :base-dir tmp})]
          (is (string? (:dir r)))
          (is (string? (:dossier-path r)))))

      (testing "research$append-log round-trips"
        (let [r (tool/invoke-tool :research$append-log
                                  {:id "invoke-test" :iter 2
                                   :agent "explore-agent"
                                   :summary "x"
                                   :base-dir tmp})]
          (is (true? (:appended r)))))

      (testing "research$update-status round-trips"
        (let [r (tool/invoke-tool :research$update-status
                                  {:id "invoke-test" :criterion-id "a1"
                                   :status :satisfied
                                   :base-dir tmp})]
          (is (true? (:updated r)))
          (is (= :satisfied (:to r)))))

      (testing "research$write-verdict round-trips"
        (let [r (tool/invoke-tool :research$write-verdict
                                  {:id "invoke-test" :status :achieved
                                   :narrative "all done"
                                   :base-dir tmp})]
          (is (string? (:path r)))))

      (testing "research$index-append round-trips"
        (let [r (tool/invoke-tool :research$index-append
                                  {:id "invoke-test" :status :achieved
                                   :one-line "smoke"
                                   :base-dir tmp})]
          (is (true? (:appended r)))))

      (finally (delete-recursive (io/file tmp))))))
