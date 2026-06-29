;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.research-agent-test
  "Tests for research-agent (lightweight redesign): registration, inherited
   bt-factory (CoAct), the curated agent-tools roster, instruction/tool-context
   anchors, and unit tests for the surviving research$* READ/DERIVE seams —
   research$id (determinism), research$resume? (frontmatter + §5 acceptance
   CHECKLIST parse, with legacy dual-read), and research$verdict-outcome (the
   :achieved guard). The structured-construction helpers (bootstrap,
   append-log, update-status, write-verdict, index-append) are retired — the
   dossier markdown is authored directly with the file tools — so the auto-
   finalize backstop is tested against directly-seeded dossier dirs."
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
            bt-default (bt-factory {})
            bt-30      (bt-factory {:max-iterations 30})]
        (is (vector? bt-default))
        (is (= :sequence (first bt-default)))
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

      ;; Bookkeeping — call-tool reaches the specialists
      (is (contains? ids :list-tools))
      (is (contains? ids :get-tool-info))
      (is (contains? ids :call-tool))

      ;; Background fan-out for slow specialist calls
      (is (contains? ids :task$run))

      ;; Runtime config (max-iterations tuning)
      (is (contains? ids :agent-runtime$config))

      ;; research$* surviving READ/DERIVE seams
      (is (contains? ids :research$id))
      (is (contains? ids :research$resume?))
      (is (contains? ids :research$verdict-outcome))

      ;; Sibling dossier read-helpers (cherry-picked, READ-ONLY)
      (is (contains? ids :plan$read-dossier))
      (is (contains? ids :todo$read-dossier))
      (is (contains? ids :exec$read-dossier))
      (is (contains? ids :eval$read-verdict))
      (is (contains? ids :edit$read-record))))

  (testing "research-agent :agent-tools RETIRES the structured-construction helpers"
    (let [ids (research-tool-ids)]
      ;; Authoring is now direct write-file/update-file — these are gone.
      (is (not (contains? ids :research$bootstrap)))
      (is (not (contains? ids :research$append-log)))
      (is (not (contains? ids :research$update-status)))
      (is (not (contains? ids :research$write-verdict)))
      (is (not (contains? ids :research$index-append)))))

  (testing "research-agent :agent-tools EXCLUDES forbidden + out-of-scope tools"
    (let [ids (research-tool-ids)]
      ;; Hard Rule 1 — no clone-self recursion
      (is (not (contains? ids :query$clone))
          "query$clone must not be in research-agent's roster (clone-self forbidden)")

      ;; Hard Rule 2 — plan/todo authoring lives in plan-agent / todo-agent.
      (is (not (contains? ids :doc$create)))
      (is (not (contains? ids :doc$update)))
      (is (not (contains? ids :doc$delete)))

      ;; Skill authoring lives in skill-agent.
      (is (not (contains? ids :skills$write)))
      (is (not (contains? ids :skills$install)))

      ;; Sibling-agent WRITE-side helpers are NOT bound — only the read-side
      ;; cherry-picks are. Sibling writes go through call-tool (Hard Rule 2).
      (is (not (contains? ids :plan$dossier-write)))
      (is (not (contains? ids :todo$dossier-write)))
      (is (not (contains? ids :exec$dossier-write)))
      (is (not (contains? ids :eval$verdict-write)))
      (is (not (contains? ids :edit$apply)))
      (is (not (contains? ids :edit$write))))))

;; ============================================================================
;; Instruction content anchors
;; ============================================================================

(deftest instruction-content-test
  (testing "instruction string contains the cardinal research-agent anchors"
    (let [agent-def   (get (tool/get-tool-defs :type :agent) :research-agent)
          instruction (get-in agent-def [:meta :instruction])]
      (is (string? instruction))
      (is (not (str/blank? instruction)))

      ;; Six-specialist routing is the headline contract.
      (is (str/includes? instruction "SIX SPECIALISTS"))
      (is (str/includes? instruction "explore-agent"))
      (is (str/includes? instruction "plan-agent"))
      (is (str/includes? instruction "todo-agent"))
      (is (str/includes? instruction "exec-agent"))
      (is (str/includes? instruction "eval-agent"))
      (is (str/includes? instruction "edit-agent"))

      ;; Sibling-dossier handoff contract.
      (is (str/includes? instruction ".brainyard/agents/plan-agent/dossiers/"))
      (is (str/includes? instruction ".brainyard/agents/todo-agent/dossiers/"))
      (is (str/includes? instruction ".brainyard/agents/exec-agent/dossiers/"))
      (is (str/includes? instruction ".brainyard/agents/eval-agent/verdicts/"))
      (is (str/includes? instruction ".brainyard/agents/edit-agent/edits/"))
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

      ;; Acceptance is a CHECKLIST now (shared todo substrate) — index-free flips
      (is (str/includes? instruction "ACCEPTANCE CHECKLIST"))
      (is (str/includes? instruction "todo substrate"))
      (is (str/includes? instruction "INDEX-FREE"))

      ;; State machine
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

      ;; Finalize: the surviving read-side guard + direct write-file authoring.
      (is (str/includes? instruction "Step 1"))
      (is (str/includes? instruction "Step 2"))
      (is (str/includes? instruction "research$verdict-outcome"))
      (is (str/includes? instruction "VERDICT TEMPLATE"))
      (is (str/includes? instruction "update-file"))
      ;; The retired write-side helpers must NOT be referenced.
      (is (not (str/includes? instruction "research$bootstrap")))
      (is (not (str/includes? instruction "research$update-status")))
      (is (not (str/includes? instruction "research$write-verdict")))
      (is (not (str/includes? instruction "research$append-log")))
      (is (not (str/includes? instruction "research$index-append")))

      ;; Hard Rule 1 — stay flat, no clone-self dispatch
      (is (str/includes? instruction "clone-self"))

      ;; Resume contract
      (is (str/includes? instruction "RESUMING"))
      (is (str/includes? instruction "@<research-id>")))))

(deftest tool-context-content-test
  (testing "tool-context names tools and the specialists"
    (let [agent-def    (get (tool/get-tool-defs :type :agent) :research-agent)
          tool-context (get-in agent-def [:meta :tool-context])]
      (is (string? tool-context))
      (is (not (str/blank? tool-context)))

      ;; Specialists — the call-tool targets (six, incl. edit-agent)
      (is (str/includes? tool-context "explore-agent"))
      (is (str/includes? tool-context "plan-agent"))
      (is (str/includes? tool-context "todo-agent"))
      (is (str/includes? tool-context "exec-agent"))
      (is (str/includes? tool-context "eval-agent"))
      (is (str/includes? tool-context "edit-agent"))

      ;; Substrate tools named explicitly so the model can find them
      (is (str/includes? tool-context "read-file"))
      (is (str/includes? tool-context "write-file"))
      (is (str/includes? tool-context "update-file"))
      (is (str/includes? tool-context "grep"))
      (is (str/includes? tool-context "bash"))

      ;; Synthesis primitive
      (is (str/includes? tool-context "query$llm"))

      ;; Invocation pattern documented
      (is (str/includes? tool-context "kebab-case"))

      ;; research$* surviving seams documented; retired ones gone
      (is (str/includes? tool-context "research$id"))
      (is (str/includes? tool-context "research$resume?"))
      (is (str/includes? tool-context "research$verdict-outcome"))
      (is (not (str/includes? tool-context "research$bootstrap")))
      (is (not (str/includes? tool-context "research$append-log")))
      (is (not (str/includes? tool-context "research$update-status")))
      (is (not (str/includes? tool-context "research$write-verdict")))
      (is (not (str/includes? tool-context "research$index-append")))

      ;; Sibling dossier read-helper cherry-picks documented
      (is (str/includes? tool-context "plan$read-dossier"))
      (is (str/includes? tool-context "todo$read-dossier"))
      (is (str/includes? tool-context "exec$read-dossier"))
      (is (str/includes? tool-context "eval$read-verdict"))
      (is (str/includes? tool-context "edit$read-record")))))

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

(defn- status->box [st]
  (case st :satisfied "x" :partial "~" :descoped "-" :contradicted "!" " "))

(defn- seed-dossier!
  "Write a research dossier dir DIRECTLY (no bootstrap helper): dossier.md
   frontmatter + the §5 acceptance.md CHECKLIST. `acc` is a vector of
   [id-keyword status-keyword] pairs. Returns the repo-relative dir path."
  [base-dir id {:keys [last-iteration acc] :or {last-iteration 1 acc []}}]
  (let [dir (io/file base-dir ".brainyard/agents/research-agent" id)]
    (.mkdirs (io/file dir "artifacts"))
    (spit (io/file dir "dossier.md")
          (str "---\nresearch_id: " id "\ncreated: 2026-06-29T00:00:00Z\n"
               "last_iteration: " last-iteration "\nstatus: in-progress\n---\n"
               "\n## Purpose\np\n"))
    (spit (io/file dir "acceptance.md")
          (str "# Acceptance — " id "\n"
               (str/join "" (for [[cid st] acc]
                              (str "- [" (status->box st) "] " (name cid)
                                   " (" (name st) ") — criterion " (name cid) "\n")))))
    (spit (io/file dir "findings.log") "")
    (str ".brainyard/agents/research-agent/" id)))

;; ============================================================================
;; research$id — determinism + stopwords + cap (unchanged)
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
;; research$resume? — pre-bootstrap probe + §5 checklist round-trip
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

(deftest research-resume-checklist-roundtrip-test
  (let [tmp (make-tmp-dir)
        id  "resume-rt"]
    (try
      (seed-dossier! tmp id {:last-iteration 4
                             :acc [[:a1 :open] [:a2 :satisfied] [:a3 :partial]]})
      (testing "resume? reads frontmatter + parses the §5 acceptance checklist"
        (let [s (research/research$resume? :id id :base-dir tmp)]
          (is (true? (:exists? s)))
          (is (= :in-progress (:status s)))
          (is (= 4 (:last-iteration s)))
          (is (= {:a1 :open :a2 :satisfied :a3 :partial} (:acceptance-state s)))))
      (finally (delete-recursive (io/file tmp))))))

(deftest research-resume-legacy-dual-read-test
  (testing "legacy frontmatter acceptance block (no acceptance.md) still parses"
    (let [tmp (make-tmp-dir)
          id  "legacy-dossier"
          dir (io/file tmp ".brainyard/agents/research-agent" id)]
      (try
        (.mkdirs dir)
        ;; Pre-redesign shape: acceptance lives in dossier.md frontmatter, no
        ;; acceptance.md checklist file.
        (spit (io/file dir "dossier.md")
              (str "---\nresearch_id: " id "\nlast_iteration: 2\nstatus: in-progress\n"
                   "acceptance:\n"
                   "  - id: a1\n    text: \"x\"\n    status: satisfied\n"
                   "  - id: a2\n    text: \"y\"\n    status: open\n"
                   "---\n# body\n"))
        (let [s (research/research$resume? :id id :base-dir tmp)]
          (is (true? (:exists? s)))
          (is (= {:a1 :satisfied :a2 :open} (:acceptance-state s))
              "dual-read falls back to the legacy frontmatter acceptance block"))
        (finally (delete-recursive (io/file tmp)))))))

;; ============================================================================
;; research$verdict-outcome — derive outcome + enforce the :achieved guard
;; ============================================================================

(deftest research-verdict-outcome-test
  (let [tmp (make-tmp-dir)]
    (try
      (testing ":in-progress + not achieved-ok when any criterion is :open"
        (seed-dossier! tmp "vo-open" {:acc [[:a1 :open] [:a2 :satisfied]]})
        (let [r (research/research$verdict-outcome :id "vo-open" :base-dir tmp)]
          (is (= :in-progress (:outcome r)))
          (is (false? (:achieved-ok? r)))
          (is (= ["a1:open"] (:blockers r)))))

      (testing ":achieved + achieved-ok when all :satisfied/:descoped"
        (seed-dossier! tmp "vo-done" {:acc [[:a1 :satisfied] [:a2 :descoped]]})
        (let [r (research/research$verdict-outcome :id "vo-done" :base-dir tmp)]
          (is (= :achieved (:outcome r)))
          (is (true? (:achieved-ok? r)))
          (is (empty? (:blockers r)))
          (is (= {:a1 "satisfied" :a2 "descoped"} (:acceptance-outcome r)))))

      (testing ":partial for a mixed (no :open) state, with blockers listed"
        (seed-dossier! tmp "vo-mix" {:acc [[:a1 :satisfied] [:a2 :partial]]})
        (let [r (research/research$verdict-outcome :id "vo-mix" :base-dir tmp)]
          (is (= :partial (:outcome r)))
          (is (false? (:achieved-ok? r)))
          (is (= ["a2:partial"] (:blockers r)))))

      (testing ":abandoned when all :contradicted"
        (seed-dossier! tmp "vo-bad" {:acc [[:a1 :contradicted] [:a2 :contradicted]]})
        (let [r (research/research$verdict-outcome :id "vo-bad" :base-dir tmp)]
          (is (= :abandoned (:outcome r)))
          (is (false? (:achieved-ok? r)))))

      (testing "no acceptance checklist → error"
        (is (contains? (research/research$verdict-outcome :id "no-such" :base-dir tmp) :error)))

      (testing "validation"
        (is (contains? (research/research$verdict-outcome :id 123 :base-dir tmp) :error)))

      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; Auto-finalize backstop
;; ============================================================================

(deftest hook-registration-test
  (testing "auto-finalize hook is registered on :agent.ask/finalize"
    (require 'ai.brainyard.agent.core.hooks)
    (research/install-auto-finalize!)
    (let [list-hooks (resolve 'ai.brainyard.agent.core.hooks/list-hooks)
          entries    (list-hooks :agent.ask/finalize)
          ids        (set (map :id entries))]
      (is (contains? ids :ai.brainyard.agent.common.research/research-auto-finalize)
          "research-auto-finalize hook must register on :agent.ask/finalize"))))

(deftest hook-skips-when-criteria-open-test
  (let [tmp (make-tmp-dir)
        question "What dominates startup?"
        rid (:slug (research/research$id :question question))]
    (try
      (seed-dossier! tmp rid {:acc [[:a1 :open] [:a2 :open]]})
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
      (seed-dossier! tmp rid {:last-iteration 5 :acc [[:a1 :satisfied] [:a2 :partial]]})

      (with-redefs [research/research-agent?  (constantly true)
                    config/project-dir (constantly tmp)]
        (research/research-auto-finalize
         {:agent {} :input question
          :result {:answer "Reduced startup substantially. a1 satisfied, a2 partial."}}))

      (let [verdict (io/file tmp ".brainyard/agents/research-agent" rid "verdict.md")
            index   (io/file tmp ".brainyard/agents/research-agent/INDEX.md")]
        (testing "verdict.md written with derived :partial status + acceptance_outcome"
          (is (.isFile verdict))
          (let [content (slurp verdict)]
            (is (str/includes? content "status: partial"))
            (is (str/includes? content (str "research_id: " rid)))
            (is (str/includes? content "iterations: 5"))
            (is (str/includes? content "acceptance_outcome:"))
            (is (str/includes? content "a1: satisfied"))
            (is (str/includes? content "a2: partial"))
            (is (str/includes? content "## Verdict"))))

        (testing "INDEX.md was appended (status upper-cased)"
          (is (.isFile index))
          (let [content (slurp index)]
            (is (str/includes? content (str "[" rid "]")))
            (is (str/includes? content "PARTIAL")))))
      (finally (delete-recursive (io/file tmp))))))

(deftest hook-injects-handoff-line-test
  (testing "hook returns a :replace decision injecting the contract line"
    (let [tmp (make-tmp-dir)
          question "inject test question"
          rid (:slug (research/research$id :question question))]
      (try
        (seed-dossier! tmp rid {:acc [[:a1 :satisfied]]})
        (with-redefs [research/research-agent?  (constantly true)
                      config/project-dir (constantly tmp)]
          (let [decision (research/research-auto-finalize
                          {:agent {} :input question
                           :result {:answer "Done with everything."}})]
            (is (= :replace (:result decision)))
            (is (str/includes? (get-in decision [:replacement :answer])
                               "Saved research dossier:"))
            (is (str/includes? (get-in decision [:replacement :answer])
                               (str "research-agent/" rid "/")))))
        (finally (delete-recursive (io/file tmp)))))))

(deftest hook-idempotent-test
  (let [tmp (make-tmp-dir)
        question "another smoke question"
        rid (:slug (research/research$id :question question))]
    (try
      (seed-dossier! tmp rid {:acc [[:a1 :satisfied]]})

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
      (seed-dossier! tmp rid {:acc [[:a1 :satisfied]]})

      ;; Answer carries the `Saved research dossier:` line — LLM did finalize.
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
      ;; No dossier seeded — derived id has no dossier on disk.
      (with-redefs [research/research-agent?  (constantly true)
                    config/project-dir (constantly tmp)]
        (research/research-auto-finalize
         {:agent {} :input "totally novel question with no dossier"
          :result {:answer "I answered without a dossier."}}))

      (testing "no dossier dir created — run wasn't research-shaped"
        (let [base (io/file tmp ".brainyard/agents/research-agent")]
          (when (.isDirectory base)
            (is (zero? (count (filter #(.isDirectory %) (.listFiles base))))
                "hook must NOT retroactively create a dossier directory"))))
      (finally (delete-recursive (io/file tmp))))))

(deftest hook-defensive-on-exception-test
  (testing "exceptions inside the hook are caught and never re-thrown"
    (with-redefs [research/research-agent?  (constantly true)
                  config/project-dir (constantly "/nonexistent/path/that/does/not/exist")]
      (is (nil? (research/research-auto-finalize
                 {:agent {} :input "x" :result {:answer "y"}}))))))

;; ============================================================================
;; Integration: setup-agent-by-id round-trip + specialist reachability
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
          (is (some? (:memory-manager state))))

        (testing "behavior-tree wired in (CoAct via :bt-factory)"
          (is (some? (:behavior-tree state))))

        (testing "instruction + tool-context flow into st-memory-init"
          (is (string? (:instruction st-init)))
          (is (str/includes? (:instruction st-init) "RESEARCH-agent"))
          (is (string? (:tool-context st-init)))
          (is (str/includes? (:tool-context st-init) "research$id")))

        (testing "tools roster includes the surviving research$* seams"
          (let [names (->> (:tools st-init)
                           (map (comp str :name))
                           set)]
            (is (contains? names "research$id"))
            (is (contains? names "research$resume?"))
            (is (contains? names "research$verdict-outcome"))
            (is (not (contains? names "research$bootstrap")))
            (is (not (contains? names "research$write-verdict")))
            (is (not (contains? names "call-tool")))
            (is (contains? names "query$llm")))))

      (finally (.close ag)))))

(deftest specialists-reachable-via-registry-test
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
  ;; test exercises the exact same path for the surviving seams.
  (let [tmp (make-tmp-dir)]
    (try
      (testing "research$id round-trips"
        (let [r (tool/invoke-tool :research$id {:question "hello world"})]
          (is (= "hello-world" (:slug r)))))

      (testing "research$resume? round-trips (no dossier)"
        (let [r (tool/invoke-tool :research$resume?
                                  {:id "no-such-id" :base-dir tmp})]
          (is (= false (:exists? r)))))

      (testing "research$verdict-outcome round-trips (seeded checklist)"
        (seed-dossier! tmp "invoke-vo" {:acc [[:a1 :satisfied] [:a2 :descoped]]})
        (let [r (tool/invoke-tool :research$verdict-outcome
                                  {:id "invoke-vo" :base-dir tmp})]
          (is (= :achieved (:outcome r)))
          (is (true? (:achieved-ok? r)))))

      (finally (delete-recursive (io/file tmp))))))
