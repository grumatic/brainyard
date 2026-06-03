;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.workflow-agent-test
  "Tests for workflow-agent: registration, inherited bt-factory (CoAct),
   curated agent-tools roster across the dossier substrate + the eleven
   workflow$* helpers (positive + negative assertions per Hard Rules 1, 2
   of the design doc), instruction-content anchors that pin the
   dossier-bootstrap / state-machine / HITL / termination contracts, and
   unit tests for the workflow$* helper commands (id determinism, template
   discovery + load, bootstrap idempotence, resume probe round-trip,
   NDJSON append-only, stage-status flip integrity, acceptance flip,
   verdict frontmatter + :achieved validation, INDEX.md prepend ordering,
   starter installer idempotence)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.sandbox-bindings :as sb]
            [ai.brainyard.agent.common.workflow :as workflow]
            [ai.brainyard.agent.common.workflow-agent]
            [ai.brainyard.agent.core.tool :as tool])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ============================================================================
;; Helper test fixtures (declared up here so every deftest below can use them
;; without depending on Clojure's incremental load order)
;; ============================================================================

(defn- make-tmp-dir []
  (-> (Files/createTempDirectory "workflow-test-" (into-array FileAttribute []))
      .toFile
      .getAbsolutePath))

(defn- delete-recursive [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)]
      (delete-recursive c)))
  (.delete f))

;; ============================================================================
;; Registration
;; ============================================================================

(deftest registration-test
  (testing "workflow-agent is registered as an agent"
    (let [agent-defs (tool/get-tool-defs :type :agent)]
      (is (contains? agent-defs :workflow-agent))
      (let [agent-def (get agent-defs :workflow-agent)]
        (is (= :workflow-agent (:id agent-def)))
        (is (some? (:fn agent-def)))
        (is (some? (:meta agent-def)))))))

;; ============================================================================
;; Inheritance via run-coact-derived
;;
;; workflow-agent pins :bt-factory explicitly (mirroring research-agent,
;; rlm-agent, explore-agent) so direct entry points (e.g. setup-agent-by-id
;; used by `bb tui ask`) that resolve agent metadata without going through
;; run-coact-derived still pick up the correct CoAct BT.
;; ============================================================================

(deftest inheritance-test
  (require 'ai.brainyard.agent.common.coact-agent)
  (let [wf-def    (get (tool/get-tool-defs :type :agent) :workflow-agent)
        coact-def (get (tool/get-tool-defs :type :agent) :coact-agent)]

    (testing "workflow-agent's :fn is registered (the wrap-fn invoking run-coact-derived)"
      (is (some? (:fn wf-def))))

    (testing "workflow-agent pins :bt-factory explicitly"
      (let [bt-factory (get-in wf-def [:meta :bt-factory])]
        (is (fn? bt-factory))
        (let [bt (bt-factory {:max-iterations 3})]
          (is (vector? bt))
          (is (= :sequence (first bt)))
          (is (= "coact.sequence" (namespace (get-in bt [1 :id])))))))

    (testing "default :max-iterations is 50 (vs CoAct's 20, research's 30)"
      (let [bt-factory (get-in wf-def [:meta :bt-factory])
            bt-default (bt-factory {})
            bt-50      (bt-factory {:max-iterations 50})]
        (is (vector? bt-default))
        (is (= :sequence (first bt-default)))
        (is (= (first bt-default) (first bt-50))
            "default-built BT and explicit-50 BT share the same root shape")))

    (testing "coact-agent (the parent) has the same bt-factory shape"
      (let [bt-factory (get-in coact-def [:meta :bt-factory])]
        (is (fn? bt-factory))
        (let [bt (bt-factory {:max-iterations 3})]
          (is (= :sequence (first bt)))
          (is (= "coact.sequence" (namespace (get-in bt [1 :id])))))))))

;; ============================================================================
;; Agent tools binding — positive + negative assertions
;; ============================================================================

(defn- workflow-tool-ids []
  (let [agent-def   (get (tool/get-tool-defs :type :agent) :workflow-agent)
        agent-tools (get-in agent-def [:meta :agent-tools])]
    (set (map (comp :id meta deref) (:tools agent-tools)))))

(deftest agent-tools-test
  (testing "workflow-agent :agent-tools covers the dossier substrate + invocation"
    (let [ids (workflow-tool-ids)]
      ;; Dossier substrate — files / shell / discovery
      (is (contains? ids :read-file))
      (is (contains? ids :write-file))
      (is (contains? ids :update-file))
      (is (contains? ids :grep))
      (is (contains? ids :bash))
      (is (contains? ids :search))

      ;; Synthesis — flat sub-LLM only
      (is (contains? ids :query$llm))

      ;; Bookkeeping — call-tool reaches every functional agent
      (is (contains? ids :list-tools))
      (is (contains? ids :get-tool-info))
      (is (contains? ids :call-tool))

      ;; Background fan-out for slow stage calls
      (is (contains? ids :task$run))

      ;; Runtime config (max-iterations tuning)
      (is (contains? ids :agent-runtime$config))))

  (testing "workflow-agent :agent-tools includes ALL eleven workflow$* helpers"
    (let [ids (workflow-tool-ids)]
      (is (contains? ids :workflow$id))
      (is (contains? ids :workflow$resume?))
      (is (contains? ids :workflow$list-templates))
      (is (contains? ids :workflow$load-template))
      (is (contains? ids :workflow$install-starters))
      (is (contains? ids :workflow$bootstrap))
      (is (contains? ids :workflow$append-log))
      (is (contains? ids :workflow$update-stage))
      (is (contains? ids :workflow$update-acceptance))
      (is (contains? ids :workflow$write-verdict))
      (is (contains? ids :workflow$index-append))))

  (testing "workflow-agent :agent-tools EXCLUDES forbidden + out-of-scope tools"
    (let [ids (workflow-tool-ids)]
      ;; Hard Rule 1 — no clone-self recursion
      (is (not (contains? ids :query$clone))
          "query$clone must not be in workflow-agent's roster (clone-self forbidden)")

      ;; Web tools — routed through explore-agent / research-agent
      (is (not (contains? ids :fetch-url))
          "fetch-url is filtered out explicitly; web access routes through explore-agent")
      (is (not (contains? ids :web-search))
          "web-search must not be bound; route through explore-agent")

      ;; Hard Rule 2 — plan/todo authoring lives in plan-agent / todo-agent;
      ;; reach via call-tool, not direct command access.
      (is (not (contains? ids :doc$create)))
      (is (not (contains? ids :doc$update)))
      (is (not (contains? ids :doc$delete)))

      ;; Skill authoring lives in skill-agent.
      (is (not (contains? ids :skills$write)))
      (is (not (contains? ids :skills$install)))
      (is (not (contains? ids :skills$list)))
      (is (not (contains? ids :skills$find)))
      (is (not (contains? ids :skills$read)))

      ;; Specialist storage helpers — workflow-agent reads via call-tool,
      ;; never writes to specialist storage paths directly.
      (is (not (contains? ids :plan$dossier-write)))
      (is (not (contains? ids :todo$dossier-write)))
      (is (not (contains? ids :exec$dossier-write)))
      (is (not (contains? ids :eval$dossier-write)))
      (is (not (contains? ids :eval$verdict-write)))
      (is (not (contains? ids :update$apply)))
      (is (not (contains? ids :update$write)))

      ;; Research-agent helpers — workflow-agent reaches research-agent
      ;; via call-tool when needed; it does NOT compose research$* helpers
      ;; directly (would bypass research-agent's own dossier discipline).
      (is (not (contains? ids :research$id)))
      (is (not (contains? ids :research$bootstrap)))
      (is (not (contains? ids :research$write-verdict))))))

;; ============================================================================
;; Instruction content anchors — pin the dossier + state-machine + HITL +
;; termination contract
;; ============================================================================

(deftest instruction-content-test
  (testing "instruction string contains the cardinal workflow-agent anchors"
    (let [agent-def   (get (tool/get-tool-defs :type :agent) :workflow-agent)
          instruction (get-in agent-def [:meta :instruction])]
      (is (string? instruction))
      (is (not (str/blank? instruction)))

      ;; Headline identity
      (is (str/includes? instruction "WORKFLOW-agent"))

      ;; Templates as recommendations, not contracts
      (is (str/includes? instruction "WORKFLOW TEMPLATES"))
      (is (str/includes? instruction "recommendations, not contracts"))
      (is (str/includes? instruction ".brainyard/workflows/"))
      (is (str/includes? instruction ":ad-hoc"))

      ;; Bootstrap — the one fixed obligation
      (is (str/includes? instruction "DOSSIER BOOTSTRAP"))
      (is (str/includes? instruction "workflow$id"))
      (is (str/includes? instruction "workflow$resume?"))
      (is (str/includes? instruction "workflow$bootstrap"))
      (is (str/includes? instruction "stages.edn"))
      (is (str/includes? instruction "purpose.md"))
      (is (str/includes? instruction "acceptance.md"))
      (is (str/includes? instruction "findings.log"))
      (is (str/includes? instruction "dossier.md"))
      (is (str/includes? instruction "template.edn"))

      ;; State machine — A through I
      (is (str/includes? instruction "STATE MACHINE"))
      (is (str/includes? instruction "RUN-STAGE"))
      (is (str/includes? instruction "EVAL-STAGE"))
      (is (str/includes? instruction "GATE"))
      (is (str/includes? instruction "RE-RUN"))
      (is (str/includes? instruction "INSERT"))
      (is (str/includes? instruction "SKIP"))
      (is (str/includes? instruction "SYNTHESIZE"))
      (is (str/includes? instruction "CLARIFY"))
      (is (str/includes? instruction "FINALIZE"))

      ;; HITL discipline — five modes are one config knob, not five paths
      (is (str/includes? instruction "HITL"))
      (is (str/includes? instruction ":auto"))
      (is (str/includes? instruction ":gates"))
      (is (str/includes? instruction ":checkpoint"))
      (is (str/includes? instruction ":co-pilot"))
      (is (str/includes? instruction ":step"))
      (is (str/includes? instruction "Awaiting workflow gate:"))

      ;; Decision heuristics + dossier update discipline + dossier passing
      (is (str/includes? instruction "DECISION HEURISTICS"))
      (is (str/includes? instruction "DOSSIER UPDATE DISCIPLINE"))
      (is (str/includes? instruction "PASSING DOSSIER TO STAGES"))
      (is (str/includes? instruction "Saved dossier:")
          "instruction must teach the upstream-dossier handoff token")

      ;; Termination contract — strict 4-step finalize across TWO iterations
      ;; (tightened 2026-05-11 after a Sonnet smoke showed the LLM
      ;; collapsing the helper fence and :answer into one iteration and
      ;; silently shipping :answer with the dossier still :open).
      (is (str/includes? instruction "TERMINATION RULES"))
      (is (str/includes? instruction ":achieved"))
      (is (str/includes? instruction ":partial"))
      (is (str/includes? instruction ":abandoned"))
      (is (str/includes? instruction "TWO-ITERATION")
          "must mark the helper-fence/:answer split as two iterations")
      (is (str/includes? instruction "Iteration N-1")
          "must name the helper-fence iteration explicitly")
      (is (str/includes? instruction "Iteration N — :ANSWER")
          "must name the :answer iteration explicitly")
      (is (str/includes? instruction "PRE-FLIGHT GATE")
          "must include the pre-flight gate checklist before :answer")
      (is (str/includes? instruction "workflow$update-stage"))
      (is (str/includes? instruction "workflow$update-acceptance"))
      (is (str/includes? instruction "workflow$write-verdict"))
      (is (str/includes? instruction "workflow$index-append"))
      (is (str/includes? instruction "REJECT")
          "instruction must warn that write-verdict rejects mismatched :achieved")
      (is (str/includes? instruction "DO NOT include")
          "instruction must warn against the duplicate-heading footgun")
      (is (str/includes? instruction "Saved workflow dossier:"))
      (is (str/includes? instruction "DOSSIER MUST AGREE WITH :ANSWER")
          "Hard Rule 9 must state the dossier-:answer agreement requirement")
      (is (str/includes? instruction "is a LIE to")
          "Hard Rule 9 must frame the lie consequence")

      ;; Hard rules — stay flat (no clone-self) + specialist storage forbidden
      (is (str/includes? instruction "HARD RULES"))
      (is (str/includes? instruction "clone-self"))
      (is (str/includes? instruction ".brainyard/agents/plan-agent/"))
      (is (str/includes? instruction ".brainyard/agents/todo-agent/"))
      (is (str/includes? instruction ".brainyard/agents/exec-agent/"))
      (is (str/includes? instruction ".brainyard/agents/eval-agent/"))
      (is (str/includes? instruction ".brainyard/agents/update-agent/"))
      (is (str/includes? instruction ".brainyard/agents/research-agent/"))

      ;; Iteration budget = 50
      (is (str/includes? instruction "50"))

      ;; Resume contract
      (is (str/includes? instruction "RESUMING"))
      (is (str/includes? instruction "@<workflow-id>"))

      ;; Functional agent surface (called via call-tool — names appear in
      ;; the instruction, not bound directly)
      (is (str/includes? instruction "research-agent"))
      (is (str/includes? instruction "explore-agent"))
      (is (str/includes? instruction "plan-agent"))
      (is (str/includes? instruction "todo-agent"))
      (is (str/includes? instruction "exec-agent"))
      (is (str/includes? instruction "eval-agent"))
      (is (str/includes? instruction "mcp-agent"))
      (is (str/includes? instruction "update-agent"))
      (is (str/includes? instruction "rlm-agent")))))

(deftest tool-context-content-test
  (testing "tool-context names tools, functional agents, and workflow$* helpers"
    (let [agent-def    (get (tool/get-tool-defs :type :agent) :workflow-agent)
          tool-context (get-in agent-def [:meta :tool-context])]
      (is (string? tool-context))
      (is (not (str/blank? tool-context)))

      ;; Functional agents (the call-tool targets)
      (is (str/includes? tool-context "research-agent"))
      (is (str/includes? tool-context "explore-agent"))
      (is (str/includes? tool-context "plan-agent"))
      (is (str/includes? tool-context "todo-agent"))
      (is (str/includes? tool-context "exec-agent"))
      (is (str/includes? tool-context "eval-agent"))
      (is (str/includes? tool-context "mcp-agent"))
      (is (str/includes? tool-context "skill-agent"))
      (is (str/includes? tool-context "update-agent"))
      (is (str/includes? tool-context "rlm-agent"))
      (is (str/includes? tool-context "coact-agent"))

      ;; Substrate tools named explicitly so the model can find them
      (is (str/includes? tool-context "read-file"))
      (is (str/includes? tool-context "write-file"))
      (is (str/includes? tool-context "update-file"))
      (is (str/includes? tool-context "grep"))
      (is (str/includes? tool-context "bash"))

      ;; Synthesis primitive
      (is (str/includes? tool-context "query$llm"))

      ;; Invocation pattern documented (the only way to reach functional agents)
      (is (str/includes? tool-context "kebab-case"))

      ;; All eleven workflow$* helpers documented
      (is (str/includes? tool-context "workflow$id"))
      (is (str/includes? tool-context "workflow$resume?"))
      (is (str/includes? tool-context "workflow$list-templates"))
      (is (str/includes? tool-context "workflow$load-template"))
      (is (str/includes? tool-context "workflow$install-starters"))
      (is (str/includes? tool-context "workflow$bootstrap"))
      (is (str/includes? tool-context "workflow$append-log"))
      (is (str/includes? tool-context "workflow$update-stage"))
      (is (str/includes? tool-context "workflow$update-acceptance"))
      (is (str/includes? tool-context "workflow$write-verdict"))
      (is (str/includes? tool-context "workflow$index-append")))))

;; ============================================================================
;; I/O contract — :inputs / :outputs shape
;; ============================================================================

(deftest io-contract-test
  (testing "workflow-agent declares :question + optional :agent-context inputs"
    (let [agent-def    (get (tool/get-tool-defs :type :agent) :workflow-agent)
          input-schema (get-in agent-def [:meta :input-schema])
          entries      (tool/malli-map-entries input-schema)
          by-key       (into {} (map (juxt tool/malli-map-entry-key identity)) entries)]
      (is (contains? by-key :question))
      (is (contains? by-key :agent-context))
      ;; In Malli [:map ...] form the :optional flag lives in the entry-props
      ;; slot ([:agent-context {:optional true} <schema>]).
      (let [opts (tool/malli-map-entry-props (get by-key :agent-context))]
        (is (true? (:optional opts))
            "agent-context should be optional (used for @<workflow-id> resume)"))))

  (testing "workflow-agent declares :answer output"
    (let [agent-def     (get (tool/get-tool-defs :type :agent) :workflow-agent)
          output-schema (get-in agent-def [:meta :output-schema])]
      (is (some #(= :answer (first %)) (rest output-schema))))))

;; ============================================================================
;; Sandbox-binding calling conventions
;;
;; bind-one-tool supports BOTH positional-first (Clojure REPL idiom) AND pure
;; kwargs (what the LLM emits and what the agent instruction teaches). The
;; required/optional contract on :inputs is preserved either way.
;;
;; This test pins the dual-mode contract so a future refactor of
;; sandbox_bindings.clj cannot quietly regress LLM-generated workflows.
;; ============================================================================

(deftest sandbox-binding-calling-modes-test
  (let [bindings (sb/auto-tool-bindings nil)
        w$id    (get bindings 'workflow$id)
        w$resume (get bindings 'workflow$resume?)
        w$bs    (get bindings 'workflow$bootstrap)]

    (testing "all eleven workflow$* helpers are bound"
      (is (every? bindings
                  ['workflow$id 'workflow$resume? 'workflow$list-templates
                   'workflow$load-template 'workflow$install-starters
                   'workflow$bootstrap 'workflow$append-log
                   'workflow$update-stage 'workflow$update-acceptance
                   'workflow$write-verdict 'workflow$index-append])))

    (testing "kwargs-mode: first arg is keyword + known input → all-kwargs dispatch"
      ;; This is the form LLMs naturally emit and what instructions teach.
      (let [r (w$id :question "Investigate cold start latency")]
        (is (= "investigate-cold-start-latency" (:slug r))))
      (let [r (w$id :template :feature-launch :question "Ship feature X")]
        (is (= "feature-launch--feature-x" (:slug r))))
      (let [r (w$resume :id "nonexistent-kw" :base-dir "/tmp/wf-bind-test")]
        (is (= false (:exists? r)))))

    (testing "positional-mode: first arg is NOT a known input keyword → req-first dispatch"
      ;; Preserves the existing Clojure REPL idiom.
      (let [r (w$id "Investigate cold start latency")]
        (is (= "investigate-cold-start-latency" (:slug r))))
      (let [r (w$id "Ship feature X" :template :feature-launch)]
        (is (= "feature-launch--feature-x" (:slug r))))
      (let [r (w$resume "nonexistent-pos" :base-dir "/tmp/wf-bind-test")]
        (is (= false (:exists? r)))))

    (testing "kwargs-mode tolerates >2 required keys without depending on map order"
      ;; workflow$bootstrap has 4 required keys (id / purpose / acceptance /
      ;; stages). Their declaration-order is unstable for >8-entry maps, so
      ;; positional dispatch would be brittle. Kwargs mode sidesteps that.
      (let [tmp (make-tmp-dir)]
        (try
          (let [r (w$bs :id "kw-bind-many-keys"
                        :purpose "Test kwargs through bind-one-tool"
                        :acceptance [{:id "a1" :text "ok" :status :open}]
                        :stages [{:id "s1" :recommended-agent "coact-agent"}]
                        :template-id :ad-hoc
                        :hitl-mode :gates
                        :base-dir tmp)]
            (is (string? (:dir r)))
            (is (.isFile (io/file tmp (:dossier-path r))))
            (is (.isFile (io/file tmp (:stages-path r)))))
          (finally (delete-recursive (io/file tmp))))))

    (testing "kwargs-mode with odd arg count → friendly error (no NPE)"
      (let [r (w$id :question)]
        (is (contains? r :error))
        (is (re-find #"even number of args" (:error r)))))))

;; ============================================================================
;; Built-in starter templates ship on the classpath
;; ============================================================================

(deftest builtin-starters-on-classpath-test
  (testing "feature-launch.edn and doc-update.edn are reachable via io/resource"
    (is (some? (io/resource "workflows/feature-launch.edn")))
    (is (some? (io/resource "workflows/doc-update.edn")))))

;; ============================================================================
;; workflow$id — determinism + stopwords + cap + template prefix
;; ============================================================================

(deftest workflow-id-test
  (testing "same template + question → same slug"
    (let [q "Ship the MCP server health-check command"
          s1 (:slug (workflow/workflow$id :template :feature-launch :question q))
          s2 (:slug (workflow/workflow$id :template :feature-launch :question q))]
      (is (= s1 s2))
      (is (str/starts-with? s1 "feature-launch--"))))

  (testing "stopwords are dropped + kebab-case normalization"
    (let [s (:slug (workflow/workflow$id :template :feature-launch
                                         :question "Ship the MCP server health-check command"))]
      ;; "ship", "the" dropped → "mcp-server-health-check-command"
      (is (= "feature-launch--mcp-server-health-check-command" s))))

  (testing ":ad-hoc template yields bare question slug (no prefix)"
    (let [s (:slug (workflow/workflow$id :template :ad-hoc
                                         :question "Investigate cold start latency"))]
      (is (= "investigate-cold-start-latency" s))
      (is (not (str/starts-with? s "ad-hoc--")))))

  (testing "nil template yields bare question slug"
    (let [s (:slug (workflow/workflow$id :question "Investigate cold start latency"))]
      (is (= "investigate-cold-start-latency" s))))

  (testing "60-char default cap on the question slug"
    (let [long-q (str/join " " (repeat 30 "supercalifragilistic"))
          prefix "feature-launch--"
          s      (:slug (workflow/workflow$id :template :feature-launch
                                              :question long-q))
          q-part (subs s (count prefix))]
      ;; The cap applies to the question slug only — the template prefix
      ;; is added separately and bounded by template-id length.
      (is (str/starts-with? s prefix))
      (is (<= (count q-part) 60)
          (str "question slug exceeded 60 chars: " (count q-part)))))

  (testing "max-chars override"
    (is (<= (count (:slug (workflow/workflow$id :template :ad-hoc
                                                :question "investigate the loop"
                                                :max-chars 8)))
            8)))

  (testing "blank / all-stopwords → fallback slug 'workflow'"
    (is (= "workflow" (:slug (workflow/workflow$id :template :ad-hoc :question ""))))
    (is (= "workflow" (:slug (workflow/workflow$id :template :ad-hoc :question "what is the")))))

  (testing "string template id is accepted"
    (let [s (:slug (workflow/workflow$id :template "feature-launch"
                                         :question "test"))]
      (is (str/starts-with? s "feature-launch--"))))

  (testing "validation"
    (is (contains? (workflow/workflow$id :question 123) :error))
    (is (contains? (workflow/workflow$id :question "x" :max-chars 0) :error))))

;; ============================================================================
;; workflow$list-templates — built-in classpath enumeration
;; ============================================================================

(deftest workflow-list-templates-test
  (testing "built-in starters are listed (project + user dirs nonexistent)"
    (let [tmp (make-tmp-dir)]
      (try
        (let [r (workflow/workflow$list-templates :base-dir tmp)
              ids (set (map :id (:templates r)))
              sources (set (map :source (:templates r)))]
          (is (contains? ids "feature-launch"))
          (is (contains? ids "doc-update"))
          (is (contains? sources :built-in)))
        (finally (delete-recursive (io/file tmp))))))

  (testing ":include-built-in? false filters classpath starters"
    (let [tmp (make-tmp-dir)]
      (try
        (let [r (workflow/workflow$list-templates
                 :base-dir tmp :include-built-in? false)]
          (is (empty? (:templates r))
              "no project + no user + no built-in = nothing"))
        (finally (delete-recursive (io/file tmp)))))))

;; ============================================================================
;; workflow$load-template — resolution chain + validation
;; ============================================================================

(deftest workflow-load-template-test
  (testing "loads :feature-launch from built-in classpath"
    (let [tmp (make-tmp-dir)]
      (try
        (let [r (workflow/workflow$load-template :id :feature-launch :base-dir tmp)]
          (is (nil? (:error r)))
          (is (= :built-in (:source r)))
          (let [t (:template r)]
            (is (= :feature-launch (:workflow/id t)))
            (is (string? (:workflow/name t)))
            (is (vector? (:stages t)))
            (is (>= (count (:stages t)) 1))
            (is (vector? (:acceptance t)))
            (is (>= (count (:acceptance t)) 1))))
        (finally (delete-recursive (io/file tmp))))))

  (testing "loads :doc-update from built-in classpath"
    (let [tmp (make-tmp-dir)]
      (try
        (let [r (workflow/workflow$load-template :id :doc-update :base-dir tmp)]
          (is (= :built-in (:source r)))
          (is (= :doc-update (-> r :template :workflow/id))))
        (finally (delete-recursive (io/file tmp))))))

  (testing "project-local shadows built-in (resolution order: project > user > built-in)"
    (let [tmp (make-tmp-dir)
          project-dir (io/file tmp ".brainyard/workflows")
          _   (.mkdirs project-dir)
          custom {:workflow/id :feature-launch
                  :workflow/name "Custom Feature Launch"
                  :workflow/description "Project override"
                  :acceptance [{:id :a1 :text "criterion"}]
                  :stages [{:id :only-stage :recommended-agent :coact-agent}]}]
      (try
        (spit (io/file project-dir "feature-launch.edn") (pr-str custom))
        (let [r (workflow/workflow$load-template :id :feature-launch :base-dir tmp)]
          (is (= :project (:source r))
              "project-local overrides built-in")
          (is (= "Custom Feature Launch" (-> r :template :workflow/name))))
        (finally (delete-recursive (io/file tmp))))))

  (testing "explicit :path is honored even if :id isn't on the classpath"
    (let [tmp (make-tmp-dir)
          path (str tmp "/custom.edn")
          custom {:workflow/id :custom
                  :workflow/name "Custom"
                  :acceptance [{:id :a1 :text "x"}]
                  :stages [{:id :s1 :recommended-agent :coact-agent}]}]
      (try
        (spit path (pr-str custom))
        (let [r (workflow/workflow$load-template :path path :base-dir tmp)]
          (is (= :explicit (:source r)))
          (is (= :custom (-> r :template :workflow/id))))
        (finally (delete-recursive (io/file tmp))))))

  (testing "missing id → :error"
    (is (contains? (workflow/workflow$load-template :id :no-such-template)
                   :error)))

  (testing "neither :id nor :path → :error"
    (is (contains? (workflow/workflow$load-template) :error)))

  (testing "invalid template (missing :workflow/id) → :error"
    (let [tmp (make-tmp-dir)
          path (str tmp "/bad.edn")]
      (try
        (spit path (pr-str {:workflow/name "no id" :stages [] :acceptance []}))
        (let [r (workflow/workflow$load-template :path path :base-dir tmp)]
          (is (contains? r :error))
          (is (str/includes? (:error r) "invalid")))
        (finally (delete-recursive (io/file tmp)))))))

;; ============================================================================
;; workflow$install-starters — idempotent copy
;; ============================================================================

(deftest workflow-install-starters-test
  (let [tmp (make-tmp-dir)]
    (try
      (testing "first call installs both starters"
        (let [r (workflow/workflow$install-starters :base-dir tmp)]
          (is (= #{"feature-launch" "doc-update"} (set (:installed r))))
          (is (empty? (:skipped r)))
          (is (.isFile (io/file tmp ".brainyard/workflows/feature-launch.edn")))
          (is (.isFile (io/file tmp ".brainyard/workflows/doc-update.edn")))))

      (testing "second call is no-op (existing files preserved)"
        (spit (io/file tmp ".brainyard/workflows/feature-launch.edn")
              "; user-edited marker — must survive")
        (let [r (workflow/workflow$install-starters :base-dir tmp)]
          (is (empty? (:installed r)))
          (is (= #{"feature-launch" "doc-update"} (set (:skipped r)))))
        (is (str/includes?
             (slurp (io/file tmp ".brainyard/workflows/feature-launch.edn"))
             "user-edited marker")))

      (testing ":overwrite? true does overwrite"
        (let [r (workflow/workflow$install-starters :base-dir tmp :overwrite? true)]
          (is (= #{"feature-launch" "doc-update"} (set (:installed r)))))
        (is (not (str/includes?
                  (slurp (io/file tmp ".brainyard/workflows/feature-launch.edn"))
                  "user-edited marker"))))

      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; workflow$resume? — pre-bootstrap probe
;; ============================================================================

(deftest workflow-resume-pre-bootstrap-test
  (let [tmp (make-tmp-dir)]
    (try
      (testing "no dossier exists → :exists? false"
        (is (= {:exists? false}
               (workflow/workflow$resume? :id "nonexistent" :base-dir tmp))))

      (testing "validation"
        (is (contains? (workflow/workflow$resume? :id 123 :base-dir tmp) :error)))

      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; workflow$bootstrap + workflow$resume? — round-trip + idempotence + hitl
;; ============================================================================

(deftest workflow-bootstrap-test
  (let [tmp (make-tmp-dir)]
    (try
      (let [id "test-bootstrap"
            boot (workflow/workflow$bootstrap
                  :id id
                  :purpose "Test purpose."
                  :acceptance [{:id "a1" :text "First criterion" :status :open}
                               {:id "a2" :text "Second criterion" :status :open}]
                  :stages [{:id :s1 :agent :coact-agent}
                           {:id :s2 :agent :exec-agent}]
                  :template-id :feature-launch
                  :template-edn {:workflow/id :feature-launch
                                 :workflow/name "Feature Launch"
                                 :stages [] :acceptance []}
                  :hitl-mode :gates
                  :base-dir tmp)
            dir (io/file tmp ".brainyard/agents/workflow-agent" id)]

        (testing "fresh bootstrap returns dir + dossier-path + stages-path"
          (is (string? (:dir boot)))
          (is (string? (:dossier-path boot)))
          (is (string? (:stages-path boot)))
          (is (not (contains? boot :exists?))))

        (testing "all expected files written"
          (is (.isDirectory dir))
          (is (.isFile (io/file dir "purpose.md")))
          (is (.isFile (io/file dir "acceptance.md")))
          (is (.isFile (io/file dir "stages.edn")))
          (is (.isFile (io/file dir "dossier.md")))
          (is (.isFile (io/file dir "findings.log")))
          (is (.isFile (io/file dir "template.edn")))
          (is (.isDirectory (io/file dir "artifacts"))))

        (testing "findings.log starts empty"
          (is (= "" (slurp (io/file dir "findings.log")))))

        (testing "dossier.md frontmatter contains workflow_template + hitl_mode"
          (let [body (slurp (io/file dir "dossier.md"))]
            (is (str/includes? body (str "workflow_id: " id)))
            (is (str/includes? body "workflow_template: feature-launch"))
            (is (str/includes? body "hitl_mode: gates"))
            (is (str/includes? body "- id: a1"))
            (is (str/includes? body "- id: a2"))))

        (testing "stages.edn is parseable EDN with the right shape"
          (let [data (edn/read-string (slurp (io/file dir "stages.edn")))]
            (is (= :feature-launch (:template-id data)))
            (is (vector? (:stages data)))
            (is (= 2 (count (:stages data))))
            (is (every? #(= :pending (:status %)) (:stages data)))
            (is (every? #(= 0 (:attempts %)) (:stages data)))))

        (testing "template.edn was written from :template-edn arg"
          (let [t (edn/read-string (slurp (io/file dir "template.edn")))]
            (is (= :feature-launch (:workflow/id t)))))

        (testing "second bootstrap is idempotent (no overwrite)"
          (spit (io/file dir "purpose.md") "MARKER — should survive")
          (let [boot2 (workflow/workflow$bootstrap
                       :id id :purpose "different purpose"
                       :acceptance [] :stages []
                       :base-dir tmp)]
            (is (true? (:exists? boot2)))
            (is (= "MARKER — should survive"
                   (slurp (io/file dir "purpose.md"))))))

        (testing "resume? after bootstrap reflects frontmatter + stages"
          (let [s (workflow/workflow$resume? :id id :base-dir tmp)]
            (is (true? (:exists? s)))
            (is (= :in-progress (:status s)))
            (is (= 1 (:last-iteration s)))
            (is (= :gates (:hitl-mode s)))
            (is (= {:a1 :open :a2 :open} (:acceptance-state s)))
            (is (= 2 (:stage-count s)))
            (is (= 2 (:n-pending s)))
            (is (= [:s1 :s2] (:pending-stages s))))))

      (finally (delete-recursive (io/file tmp))))))

(deftest workflow-bootstrap-ad-hoc-test
  (let [tmp (make-tmp-dir)]
    (try
      (let [id "test-ad-hoc"
            boot (workflow/workflow$bootstrap
                  :id id :purpose "Quick one-off."
                  :acceptance [{:id "a1" :text "x" :status :open}]
                  :stages [{:id :only-stage :agent :coact-agent}]
                  :base-dir tmp)
            dir (io/file tmp ".brainyard/agents/workflow-agent" id)]

        (testing "no :template-id defaults to :ad-hoc"
          (let [body (slurp (io/file dir "dossier.md"))]
            (is (str/includes? body "workflow_template: ad-hoc")))
          (let [t (edn/read-string (slurp (io/file dir "template.edn")))]
            (is (= :ad-hoc (:template t))))))
      (finally (delete-recursive (io/file tmp))))))

(deftest workflow-bootstrap-validation-test
  (let [tmp (make-tmp-dir)]
    (try
      (testing "rejects bad :hitl-mode"
        (is (contains? (workflow/workflow$bootstrap
                        :id "x" :purpose "p" :acceptance [] :stages []
                        :hitl-mode :bogus :base-dir tmp)
                       :error)))
      (testing "rejects non-string :id"
        (is (contains? (workflow/workflow$bootstrap
                        :id 123 :purpose "p" :acceptance [] :stages []
                        :base-dir tmp)
                       :error)))
      (testing "rejects non-sequential :acceptance"
        (is (contains? (workflow/workflow$bootstrap
                        :id "x" :purpose "p" :acceptance "bad" :stages []
                        :base-dir tmp)
                       :error)))
      (finally (delete-recursive (io/file tmp))))))

(deftest workflow-bootstrap-call-tool-hitl-enum-test
  ;; Over JSON tool-calls :hitl-mode arrives as a STRING. The [:enum "..."]
  ;; tightening must accept valid mode strings (the handler then coerces to a
  ;; keyword) and reject out-of-vocabulary strings at the Malli layer — surfacing
  ;; as :error-message, distinct from the handler's own :error (covered above via
  ;; the direct-fn keyword path).
  (let [tmp (make-tmp-dir)]
    (try
      (testing "valid hitl-mode string bootstraps"
        (let [r (tool/call-tool :workflow$bootstrap
                                {:id "bs-hitl-ok" :purpose "p" :acceptance []
                                 :stages [{:id "s1" :agent "coact-agent"}]
                                 :hitl-mode "co-pilot" :base-dir tmp})]
          (is (string? (:dir r)) (str "expected success, got " (pr-str r)))))
      (testing "out-of-enum hitl-mode string is rejected at the Malli layer"
        (let [r (tool/call-tool :workflow$bootstrap
                                {:id "bs-hitl-bad" :purpose "p" :acceptance []
                                 :stages [{:id "s1" :agent "coact-agent"}]
                                 :hitl-mode "bogus" :base-dir tmp})]
          (is (string? (:error-message r)) (str "expected :error-message, got " (pr-str r)))
          (is (str/includes? (:error-message r) "hitl-mode"))))
      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; workflow$append-log — NDJSON append-only with optional :action
;; ============================================================================

(deftest workflow-append-log-test
  (let [tmp (make-tmp-dir)
        id  "test-log"]
    (try
      (workflow/workflow$bootstrap :id id :purpose "p"
                                   :acceptance [] :stages []
                                   :base-dir tmp)

      (testing "consecutive appends produce N NDJSON lines"
        (workflow/workflow$append-log :id id :iter 2
                                      :stage :research-feasibility
                                      :agent "research-agent"
                                      :summary "s1"
                                      :pointers {:research_dossier "x"}
                                      :base-dir tmp)
        (workflow/workflow$append-log :id id :iter 3
                                      :stage "plan-design"
                                      :agent "plan-agent"
                                      :action "gate"
                                      :summary "s2"
                                      :pointers {:plan_path "y"}
                                      :base-dir tmp)
        (let [lines (->> (slurp (io/file tmp ".brainyard/agents/workflow-agent" id "findings.log"))
                         str/split-lines
                         (remove str/blank?))]
          (is (= 2 (count lines)))
          (is (every? #(str/starts-with? % "{") lines))
          (is (every? #(str/ends-with? % "}") lines))
          (is (str/includes? (first lines) "\"iter\":2"))
          (is (str/includes? (first lines) "\"stage\":\"research-feasibility\""))
          (is (str/includes? (second lines) "\"action\":\"gate\""))
          (is (str/includes? (first lines) "\"research_dossier\":\"x\""))))

      (testing "missing dossier → error (must bootstrap first)"
        (is (contains? (workflow/workflow$append-log
                        :id "no-such-id" :iter 1
                        :stage :s :agent "x" :summary "y"
                        :base-dir tmp)
                       :error)))

      (testing "validation"
        (is (contains? (workflow/workflow$append-log :id 1 :iter 1
                                                     :stage :s :agent "x"
                                                     :summary "y"
                                                     :base-dir tmp) :error))
        (is (contains? (workflow/workflow$append-log :id "x" :iter "bad"
                                                     :stage :s :agent "x"
                                                     :summary "y"
                                                     :base-dir tmp) :error)))

      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; workflow$update-stage — status flip, attempts increment, terminal completed-at
;; ============================================================================

(deftest workflow-update-stage-test
  (let [tmp (make-tmp-dir)
        id  "test-stage-update"]
    (try
      (workflow/workflow$bootstrap
       :id id :purpose "p"
       :acceptance []
       :stages [{:id :s1 :agent :coact-agent}
                {:id :s2 :agent :exec-agent}
                {:id :s3 :agent :eval-agent}]
       :base-dir tmp)

      (testing "flip :s1 → :in-progress; attempts incremented"
        (let [r (workflow/workflow$update-stage
                 :id id :stage-id :s1 :status :in-progress
                 :base-dir tmp)]
          (is (true? (:updated r)))
          (is (= :pending (:from r)))
          (is (= :in-progress (:to r)))
          (is (= 1 (:attempts r)))))

      (testing "flip :s1 → :satisfied; terminal auto-fills :completed-at"
        (workflow/workflow$update-stage
         :id id :stage-id :s1 :status :satisfied
         :artifact ".brainyard/agents/research-agent/x/"
         :base-dir tmp)
        (let [stages (-> (slurp (io/file tmp ".brainyard/agents/workflow-agent" id "stages.edn"))
                         edn/read-string
                         :stages)
              s1     (first stages)]
          (is (= :satisfied (:status s1)))
          (is (= 2 (:attempts s1)))
          (is (some? (:completed-at s1)))
          (is (= ".brainyard/agents/research-agent/x/" (:artifact s1)))))

      (testing "string stage-id is accepted"
        (let [r (workflow/workflow$update-stage
                 :id id :stage-id "s2" :status :in-progress
                 :base-dir tmp)]
          (is (true? (:updated r)))))

      (testing "siblings untouched"
        (let [stages (-> (slurp (io/file tmp ".brainyard/agents/workflow-agent" id "stages.edn"))
                         edn/read-string
                         :stages)
              s3     (nth stages 2)]
          (is (= :pending (:status s3)))
          (is (= 0 (:attempts s3)))))

      (testing "non-existent stage → error"
        (is (contains? (workflow/workflow$update-stage
                        :id id :stage-id :no-such-stage :status :satisfied
                        :base-dir tmp)
                       :error)))

      (testing "invalid status → error"
        (is (contains? (workflow/workflow$update-stage
                        :id id :stage-id :s1 :status :bogus
                        :base-dir tmp)
                       :error)))

      (testing "explicit :completed-at honored over auto-fill"
        (workflow/workflow$update-stage
         :id id :stage-id :s3 :status :skipped
         :completed-at "2026-05-10T00:00:00Z"
         :note "no examples to verify"
         :base-dir tmp)
        (let [stages (-> (slurp (io/file tmp ".brainyard/agents/workflow-agent" id "stages.edn"))
                         edn/read-string
                         :stages)
              s3     (nth stages 2)]
          (is (= "2026-05-10T00:00:00Z" (:completed-at s3)))
          (is (= "no examples to verify" (:note s3)))))

      (finally (delete-recursive (io/file tmp))))))

(deftest workflow-update-stage-call-tool-enum-test
  ;; The agent reaches these commands two ways, both routed through
  ;; tool/call-tool's Malli decode+validate: a JSON tool-call delivers :status
  ;; as a wire STRING ("in-progress"), while a sandbox code-fence delivers it as
  ;; a KEYWORD (:satisfied) because the agent writes Clojure. The [:enum "..."]
  ;; tightening + llm-args-transformer's :enum decoder must accept BOTH and
  ;; reject out-of-vocabulary values before the handler runs (surfacing as
  ;; :error-message, not the handler's own :error).
  (let [tmp (make-tmp-dir)
        id  "test-stage-enum"]
    (try
      (workflow/workflow$bootstrap
       :id id :purpose "p" :acceptance []
       :stages [{:id :s1 :agent :coact-agent}]
       :base-dir tmp)
      (testing "string status (JSON tool-call form) flips the stage"
        (let [r (tool/call-tool :workflow$update-stage
                                {:id id :stage-id "s1" :status "in-progress" :base-dir tmp})]
          (is (true? (:updated r)) (str "expected success, got " (pr-str r)))
          (is (= :in-progress (:to r)))))
      (testing "keyword status (sandbox code-fence form) flips the stage"
        (let [r (tool/call-tool :workflow$update-stage
                                {:id id :stage-id "s1" :status :satisfied :base-dir tmp})]
          (is (true? (:updated r)) (str "expected success, got " (pr-str r)))
          (is (= :satisfied (:to r)))))
      (testing "out-of-enum status is rejected at the Malli layer"
        (let [r (tool/call-tool :workflow$update-stage
                                {:id id :stage-id "s1" :status "bogus" :base-dir tmp})]
          (is (string? (:error-message r)) (str "expected :error-message, got " (pr-str r)))
          (is (str/includes? (:error-message r) "status"))))
      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; workflow$update-acceptance — flip one criterion, leave others alone
;; ============================================================================

(deftest workflow-update-acceptance-test
  (let [tmp (make-tmp-dir)
        id  "test-acceptance"]
    (try
      (workflow/workflow$bootstrap
       :id id :purpose "p"
       :acceptance [{:id "a1" :text "first" :status :open}
                    {:id "a2" :text "second" :status :open}
                    {:id "a3" :text "third" :status :open}]
       :stages []
       :base-dir tmp)

      (testing "flips a1 → :partial; siblings untouched"
        (let [r (workflow/workflow$update-acceptance
                 :id id :criterion-id "a1" :status :partial :base-dir tmp)]
          (is (true? (:updated r)))
          (is (= :open (:from r)))
          (is (= :partial (:to r))))
        (let [s (workflow/workflow$resume? :id id :base-dir tmp)]
          (is (= {:a1 :partial :a2 :open :a3 :open}
                 (:acceptance-state s)))))

      (testing "flips a2 → :satisfied via keyword id"
        (workflow/workflow$update-acceptance
         :id id :criterion-id :a2 :status :satisfied :base-dir tmp)
        (is (= {:a1 :partial :a2 :satisfied :a3 :open}
               (:acceptance-state (workflow/workflow$resume? :id id :base-dir tmp)))))

      (testing "non-existent criterion → error"
        (is (contains? (workflow/workflow$update-acceptance
                        :id id :criterion-id "a99" :status :open
                        :base-dir tmp)
                       :error)))

      (testing "invalid status → error"
        (is (contains? (workflow/workflow$update-acceptance
                        :id id :criterion-id "a1" :status :bogus
                        :base-dir tmp)
                       :error)))

      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; workflow$write-verdict — derives acceptance_outcome + stage_outcomes
;; ============================================================================

(deftest workflow-write-verdict-test
  (let [tmp (make-tmp-dir)
        id  "test-verdict"]
    (try
      (workflow/workflow$bootstrap
       :id id :purpose "p"
       :acceptance [{:id "a1" :text "x" :status :open}
                    {:id "a2" :text "y" :status :open}]
       :stages [{:id :s1 :agent :coact-agent}
                {:id :s2 :agent :exec-agent}]
       :template-id :feature-launch
       :base-dir tmp)
      (workflow/workflow$update-acceptance :id id :criterion-id "a1"
                                           :status :satisfied :base-dir tmp)
      (workflow/workflow$update-stage :id id :stage-id :s1
                                      :status :satisfied :base-dir tmp)

      (testing "writes verdict.md with frontmatter + narrative"
        (let [v (workflow/workflow$write-verdict
                 :id id :status :partial
                 :narrative "Got most of the way. a1 satisfied, a2 still open."
                 :base-dir tmp)
              path (:path v)
              content (slurp (io/file path))]
          (is (string? path))
          (is (str/ends-with? path "verdict.md"))
          (is (str/includes? content (str "workflow_id: " id)))
          (is (str/includes? content "workflow_template: feature-launch"))
          (is (str/includes? content "status: partial"))
          (is (str/includes? content "iterations: 1"))
          (is (str/includes? content "acceptance_outcome:"))
          (is (str/includes? content "a1: satisfied"))
          (is (str/includes? content "a2: open"))
          (is (str/includes? content "stage_outcomes:"))
          (is (str/includes? content "s1: satisfied"))
          (is (str/includes? content "s2: pending"))
          (is (str/includes? content "## Verdict"))
          (is (str/includes? content "Got most of the way"))))

      (testing "leading ## Verdict heading in narrative is stripped"
        (let [v (workflow/workflow$write-verdict
                 :id id :status :partial
                 :narrative "## Verdict\nNarrative body."
                 :base-dir tmp)
              content (slurp (io/file (:path v)))]
          (is (not (str/includes? content "## Verdict\n## Verdict"))
              "duplicate heading must not appear")))

      (testing "string status accepted"
        (is (string? (:path (workflow/workflow$write-verdict
                             :id id :status "partial"
                             :narrative "ok" :base-dir tmp)))))

      (testing "invalid status → error"
        (is (contains? (workflow/workflow$write-verdict
                        :id id :status :bogus :narrative "x"
                        :base-dir tmp)
                       :error)))

      (finally (delete-recursive (io/file tmp))))))

(deftest workflow-write-verdict-achieved-validation-test
  ;; Same defense as research-agent: block :achieved when any criterion is
  ;; still :open / :partial / :contradicted. The LLM must call
  ;; workflow$update-acceptance for each criterion BEFORE finalizing.
  (let [tmp (make-tmp-dir)
        id  "test-validate"]
    (try
      (workflow/workflow$bootstrap
       :id id :purpose "p"
       :acceptance [{:id "a1" :text "x" :status :open}
                    {:id "a2" :text "y" :status :open}]
       :stages []
       :base-dir tmp)

      (testing ":achieved blocked when any criterion is still :open"
        (let [r (workflow/workflow$write-verdict
                 :id id :status :achieved
                 :narrative "claim achieved" :base-dir tmp)]
          (is (contains? r :error))
          (is (str/includes? (:error r) ":satisfied or :descoped"))
          (is (str/includes? (:error r) "a1:open"))
          (is (str/includes? (:error r) "a2:open"))))

      (testing ":achieved blocked when one is :partial"
        (workflow/workflow$update-acceptance :id id :criterion-id "a1"
                                             :status :satisfied :base-dir tmp)
        (workflow/workflow$update-acceptance :id id :criterion-id "a2"
                                             :status :partial :base-dir tmp)
        (let [r (workflow/workflow$write-verdict
                 :id id :status :achieved
                 :narrative "claim achieved" :base-dir tmp)]
          (is (contains? r :error))
          (is (str/includes? (:error r) "a2:partial"))))

      (testing ":achieved accepted once everything is :satisfied / :descoped"
        (workflow/workflow$update-acceptance :id id :criterion-id "a2"
                                             :status :descoped :base-dir tmp)
        (let [r (workflow/workflow$write-verdict
                 :id id :status :achieved
                 :narrative "now legit" :base-dir tmp)]
          (is (string? (:path r)))
          (let [content (slurp (io/file (:path r)))]
            (is (str/includes? content "status: achieved"))
            (is (str/includes? content "a1: satisfied"))
            (is (str/includes? content "a2: descoped")))))

      (testing ":partial allowed with any acceptance mix"
        (workflow/workflow$update-acceptance :id id :criterion-id "a2"
                                             :status :open :base-dir tmp)
        (let [r (workflow/workflow$write-verdict
                 :id id :status :partial
                 :narrative "still partial" :base-dir tmp)]
          (is (string? (:path r)))))

      (testing ":abandoned allowed with any acceptance mix"
        (let [r (workflow/workflow$write-verdict
                 :id id :status :abandoned
                 :narrative "ran out of time" :base-dir tmp)]
          (is (string? (:path r)))))

      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; workflow$index-append — newest-first prepend
;; ============================================================================

(deftest workflow-index-append-test
  (let [tmp (make-tmp-dir)
        index-path (io/file tmp ".brainyard/agents/workflow-agent/INDEX.md")]
    (try
      (testing "first append creates INDEX.md with one line"
        (let [r (workflow/workflow$index-append
                 :id "id-1" :status :achieved :one-line "first entry"
                 :base-dir tmp)]
          (is (true? (:appended r)))
          (is (string? (:line r))))
        (let [c (slurp index-path)]
          (is (str/includes? c "[id-1](id-1/)"))
          (is (str/includes? c "achieved"))
          (is (str/includes? c "first entry"))))

      (testing "second append prepends (newest first)"
        (workflow/workflow$index-append
         :id "id-2" :status :partial :one-line "second entry"
         :base-dir tmp)
        (let [c     (slurp index-path)
              lines (str/split-lines c)]
          (is (str/includes? (first lines) "id-2"))
          (is (str/includes? (second lines) "id-1"))))

      (testing "one-line capped at 200 chars"
        (let [long-line (str/join "" (repeat 300 "x"))
              r         (workflow/workflow$index-append
                         :id "id-3" :status :achieved
                         :one-line long-line :base-dir tmp)
              ;; line ends with "…\n" — cap is 200 chars of distillation
              line-content (-> (:line r)
                               (str/replace #"^- \d{4}-\d{2}-\d{2} \d{2}:\d{2} \[id-3\]\(id-3/\) — achieved · " "")
                               (str/replace "\n" ""))]
          (is (<= (count line-content) 200))))

      (testing "invalid status → error"
        (is (contains? (workflow/workflow$index-append
                        :id "x" :status :bogus :one-line "y"
                        :base-dir tmp)
                       :error)))

      (testing "string status accepted"
        (is (true? (:appended
                    (workflow/workflow$index-append
                     :id "id-4" :status "achieved" :one-line "via string"
                     :base-dir tmp)))))

      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; Built-in starter templates pass validation
;; ============================================================================

(deftest builtin-starters-pass-validation-test
  (testing "every built-in starter loads + validates"
    (let [tmp (make-tmp-dir)]
      (try
        (doseq [id [:feature-launch :doc-update]]
          (let [r (workflow/workflow$load-template :id id :base-dir tmp)]
            (is (nil? (:error r))
                (str "starter " id " failed to load: " (:error r)))
            (is (= :built-in (:source r)))
            (let [t (:template r)]
              (is (= id (:workflow/id t)))
              (is (string? (:workflow/name t)))
              (is (every? :id (:stages t))
                  "every stage must have an :id")
              (is (every? :recommended-agent (:stages t))
                  "every stage must have a :recommended-agent")
              (is (every? #(and (:id %) (:text %)) (:acceptance t))
                  "every acceptance criterion must have :id + :text"))))
        (finally (delete-recursive (io/file tmp)))))))
