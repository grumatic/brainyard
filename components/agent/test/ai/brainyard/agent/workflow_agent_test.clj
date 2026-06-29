;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.workflow-agent-test
  "Tests for workflow-agent (lightweight redesign): registration, inherited
   bt-factory (CoAct), the curated agent-tools roster (the structured-
   construction helpers are retired — the dossier + templates are authored
   directly with the file tools), instruction/tool-context anchors (MODE SELECT
   / AUTHORING MODE / checklists / verdict-outcome guard), and unit tests for
   the surviving READ/DERIVE/VALIDATE seams: id, list-templates / load-template
   (markdown + legacy-EDN dual-read), install-starters, resume? (§5 checklists),
   verdict-outcome, plus the auto-finalize backstop."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.sandbox-bindings :as sb]
            [ai.brainyard.agent.common.workflow :as workflow]
            [ai.brainyard.agent.common.workflow-agent]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :as tool])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn- make-tmp-dir []
  (-> (Files/createTempDirectory "workflow-test-" (into-array FileAttribute []))
      .toFile .getAbsolutePath))

(defn- delete-recursive [^java.io.File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (delete-recursive c)))
  (.delete f))

(defn- acc-box [st] (case st :satisfied "x" :partial "~" :descoped "-" :contradicted "!" " "))
(defn- stage-box [st] (case st :satisfied "x" :in-progress ">" :skipped "-" :failed "!" " "))

(defn- seed-dossier!
  "Write a workflow dossier dir DIRECTLY (no bootstrap helper): dossier.md +
   the §5 acceptance.md / stages.md CHECKLISTS. `acc` = [[id status]…];
   `stages` = [[id name status]…]."
  [base-dir id {:keys [last-iteration acc stages] :or {last-iteration 1 acc [] stages []}}]
  (let [dir (io/file base-dir ".brainyard/agents/workflow-agent" id)]
    (.mkdirs (io/file dir "artifacts"))
    (spit (io/file dir "dossier.md")
          (str "---\nworkflow_id: " id "\ncreated: 2026-06-29T00:00:00Z\n"
               "last_iteration: " last-iteration "\nstatus: in-progress\nhitl_mode: gates\n---\n## Purpose\np\n"))
    (spit (io/file dir "acceptance.md")
          (str "# Acceptance — " id "\n"
               (str/join "" (for [[cid st] acc]
                              (str "- [" (acc-box st) "] " (name cid) " (" (name st) ") — criterion " (name cid) "\n")))))
    (spit (io/file dir "stages.md")
          (str "# Stages — " id "\n"
               (str/join "" (for [[sid sname st] stages]
                              (str "- [" (stage-box st) "] " (name sid) " " sname " (" (name st)
                                   ") — purpose {agent: exec-agent, gate: none, focus: [a1]}\n")))))
    (spit (io/file dir "findings.log") "")
    (str ".brainyard/agents/workflow-agent/" id)))

;; ============================================================================
;; Registration + inheritance
;; ============================================================================

(deftest registration-test
  (testing "workflow-agent is registered as an agent"
    (let [agent-defs (tool/get-tool-defs :type :agent)]
      (is (contains? agent-defs :workflow-agent))
      (let [agent-def (get agent-defs :workflow-agent)]
        (is (= :workflow-agent (:id agent-def)))
        (is (some? (:fn agent-def)))
        (is (some? (:meta agent-def)))))))

(deftest inheritance-test
  (require 'ai.brainyard.agent.common.coact-agent)
  (let [wf-def    (get (tool/get-tool-defs :type :agent) :workflow-agent)
        coact-def (get (tool/get-tool-defs :type :agent) :coact-agent)]
    (testing "workflow-agent's :fn is registered"
      (is (some? (:fn wf-def))))
    (testing "workflow-agent pins :bt-factory explicitly"
      (let [bt-factory (get-in wf-def [:meta :bt-factory])]
        (is (fn? bt-factory))
        (let [bt (bt-factory {:max-iterations 3})]
          (is (vector? bt))
          (is (= :sequence (first bt)))
          (is (= "coact.sequence" (namespace (get-in bt [1 :id])))))))
    (testing "default :max-iterations is 50"
      (let [bt-factory (get-in wf-def [:meta :bt-factory])]
        (is (= (first (bt-factory {})) (first (bt-factory {:max-iterations 50}))))))
    (testing "coact-agent (parent) has the same bt-factory shape"
      (let [bt ((get-in coact-def [:meta :bt-factory]) {:max-iterations 3})]
        (is (= :sequence (first bt)))))))

;; ============================================================================
;; Agent tools — positive + negative
;; ============================================================================

(defn- workflow-tool-ids []
  (let [agent-def (get (tool/get-tool-defs :type :agent) :workflow-agent)]
    (set (map (comp :id meta deref) (:tools (get-in agent-def [:meta :agent-tools]))))))

(deftest agent-tools-test
  (testing "workflow-agent :agent-tools covers the dossier + template substrate"
    (let [ids (workflow-tool-ids)]
      (is (contains? ids :read-file))
      (is (contains? ids :write-file))
      (is (contains? ids :update-file))
      (is (contains? ids :grep))
      (is (contains? ids :bash))
      (is (contains? ids :search))
      (is (contains? ids :query$llm))
      (is (contains? ids :list-tools))
      (is (contains? ids :get-tool-info))
      (is (contains? ids :call-tool))
      (is (contains? ids :task$run))
      (is (contains? ids :agent-runtime$config))))

  (testing "the surviving workflow$* seams are present; construction helpers retired"
    (let [ids (workflow-tool-ids)]
      (is (contains? ids :workflow$id))
      (is (contains? ids :workflow$resume?))
      (is (contains? ids :workflow$list-templates))
      (is (contains? ids :workflow$load-template))
      (is (contains? ids :workflow$install-starters))
      (is (contains? ids :workflow$verdict-outcome))
      (is (not (contains? ids :workflow$bootstrap)))
      (is (not (contains? ids :workflow$append-log)))
      (is (not (contains? ids :workflow$update-stage)))
      (is (not (contains? ids :workflow$update-acceptance)))
      (is (not (contains? ids :workflow$write-verdict)))
      (is (not (contains? ids :workflow$index-append)))))

  (testing "workflow-agent :agent-tools EXCLUDES forbidden + out-of-scope tools"
    (let [ids (workflow-tool-ids)]
      (is (not (contains? ids :query$clone)))
      (is (not (contains? ids :fetch-url)))
      (is (not (contains? ids :web-search)))
      (is (not (contains? ids :doc$create)))
      (is (not (contains? ids :doc$update)))
      (is (not (contains? ids :doc$delete)))
      (is (not (contains? ids :skills$write)))
      (is (not (contains? ids :plan$dossier-write)))
      (is (not (contains? ids :eval$verdict-write)))
      (is (not (contains? ids :edit$apply)))
      ;; research$* helpers not composed directly — reach research-agent via call-tool
      (is (not (contains? ids :research$id)))
      (is (not (contains? ids :research$verdict-outcome))))))

;; ============================================================================
;; Instruction + tool-context anchors
;; ============================================================================

(deftest instruction-content-test
  (let [agent-def   (get (tool/get-tool-defs :type :agent) :workflow-agent)
        instruction (get-in agent-def [:meta :instruction])]
    (is (string? instruction))
    (is (not (str/blank? instruction)))
    (testing "identity + two modes"
      (is (str/includes? instruction "WORKFLOW-agent"))
      (is (str/includes? instruction "MODE SELECT"))
      (is (str/includes? instruction "RUN MODE"))
      (is (str/includes? instruction "AUTHORING MODE")))
    (testing "templates as markdown recommendations"
      (is (str/includes? instruction "WORKFLOW TEMPLATES"))
      (is (str/includes? instruction "recommendations, not contracts"))
      (is (str/includes? instruction ".brainyard/workflows/"))
      (is (str/includes? instruction ":ad-hoc")))
    (testing "bootstrap writes checklists via file tools (no construction helper)"
      (is (str/includes? instruction "DOSSIER BOOTSTRAP"))
      (is (str/includes? instruction "workflow$id"))
      (is (str/includes? instruction "workflow$resume?"))
      (is (str/includes? instruction "acceptance.md"))
      (is (str/includes? instruction "stages.md"))
      (is (str/includes? instruction "ACCEPTANCE"))
      (is (str/includes? instruction "INDEX-FREE"))
      (is (str/includes? instruction "todo substrate"))
      ;; retired helpers must NOT appear
      (is (not (str/includes? instruction "workflow$bootstrap")))
      (is (not (str/includes? instruction "workflow$update-stage")))
      (is (not (str/includes? instruction "workflow$write-verdict")))
      (is (not (str/includes? instruction "stages.edn"))))
    (testing "state machine + HITL"
      (is (str/includes? instruction "STATE MACHINE"))
      (is (str/includes? instruction "RUN-STAGE"))
      (is (str/includes? instruction "FINALIZE"))
      (is (str/includes? instruction "HITL"))
      (is (str/includes? instruction ":gates"))
      (is (str/includes? instruction "Awaiting workflow gate:")))
    (testing "termination via verdict-outcome guard + write-file"
      (is (str/includes? instruction "TERMINATION RULES"))
      (is (str/includes? instruction "workflow$verdict-outcome"))
      (is (str/includes? instruction "VERDICT TEMPLATE"))
      (is (str/includes? instruction ":achieved"))
      (is (str/includes? instruction "Saved workflow dossier:"))
      (is (str/includes? instruction "DOSSIER MUST AGREE WITH :ANSWER")))
    (testing "authoring mode CRUD"
      (is (str/includes? instruction "CREATE"))
      (is (str/includes? instruction "DELETE"))
      (is (str/includes? instruction "TEMPLATE TEMPLATE")))
    (testing "hard rules + resume"
      (is (str/includes? instruction "HARD RULES"))
      (is (str/includes? instruction "clone-self"))
      (is (str/includes? instruction "RESUMING"))
      (is (str/includes? instruction "@<workflow-id>"))
      (is (str/includes? instruction "50")))
    (testing "functional agent surface (dispatch examples appear as <name>-agent;"
      ;; the full per-agent surface is named in tool-context — see
      ;; tool-context-content-test. The instruction shows dispatch examples.
      (is (str/includes? instruction "research-agent"))
      (is (str/includes? instruction "plan-agent")))))

(deftest tool-context-content-test
  (let [agent-def    (get (tool/get-tool-defs :type :agent) :workflow-agent)
        tool-context (get-in agent-def [:meta :tool-context])]
    (is (string? tool-context))
    (testing "functional agents + substrate + synthesis"
      (doseq [a ["research-agent" "explore-agent" "plan-agent" "todo-agent"
                 "exec-agent" "eval-agent" "mcp-agent" "skill-agent"
                 "edit-agent" "rlm-agent" "coact-agent"]]
        (is (str/includes? tool-context a)))
      (is (str/includes? tool-context "read-file"))
      (is (str/includes? tool-context "write-file"))
      (is (str/includes? tool-context "update-file"))
      (is (str/includes? tool-context "query$llm"))
      (is (str/includes? tool-context "kebab-case")))
    (testing "the six seams documented; construction helpers gone"
      (is (str/includes? tool-context "workflow$id"))
      (is (str/includes? tool-context "workflow$resume?"))
      (is (str/includes? tool-context "workflow$list-templates"))
      (is (str/includes? tool-context "workflow$load-template"))
      (is (str/includes? tool-context "workflow$install-starters"))
      (is (str/includes? tool-context "workflow$verdict-outcome"))
      (is (not (str/includes? tool-context "workflow$bootstrap")))
      (is (not (str/includes? tool-context "workflow$update-stage")))
      (is (not (str/includes? tool-context "workflow$write-verdict"))))
    (testing "verdict + template templates documented"
      (is (str/includes? tool-context "VERDICT TEMPLATE"))
      (is (str/includes? tool-context "TEMPLATE TEMPLATE")))))

;; ============================================================================
;; I/O contract
;; ============================================================================

(deftest io-contract-test
  (testing "workflow-agent declares :question + optional :agent-context"
    (let [agent-def    (get (tool/get-tool-defs :type :agent) :workflow-agent)
          input-schema (get-in agent-def [:meta :input-schema])
          by-key       (into {} (map (juxt tool/malli-map-entry-key identity))
                             (tool/malli-map-entries input-schema))]
      (is (contains? by-key :question))
      (is (contains? by-key :agent-context))
      (is (true? (:optional (tool/malli-map-entry-props (get by-key :agent-context)))))))
  (testing "workflow-agent declares :answer output"
    (let [output-schema (get-in (get (tool/get-tool-defs :type :agent) :workflow-agent)
                                [:meta :output-schema])]
      (is (some #(= :answer (first %)) (rest output-schema))))))

;; ============================================================================
;; Sandbox-binding calling modes (the surviving seams)
;; ============================================================================

(deftest sandbox-binding-calling-modes-test
  (let [bindings (sb/auto-tool-bindings nil)
        w$id     (get bindings 'workflow$id)
        w$resume (get bindings 'workflow$resume?)]
    (testing "the six seams are bound; retired helpers are NOT"
      (is (every? bindings ['workflow$id 'workflow$resume? 'workflow$list-templates
                            'workflow$load-template 'workflow$install-starters
                            'workflow$verdict-outcome]))
      (is (not (contains? bindings 'workflow$bootstrap)))
      (is (not (contains? bindings 'workflow$write-verdict))))
    (testing "kwargs-mode (the form LLMs emit)"
      (is (= "investigate-cold-start-latency"
             (:slug (w$id :question "Investigate cold start latency"))))
      (is (= "feature-launch--feature-x"
             (:slug (w$id :template :feature-launch :question "Ship feature X"))))
      (is (= false (:exists? (w$resume :id "nonexistent-kw" :base-dir "/tmp/wf-bind")))))
    (testing "positional-mode (Clojure REPL idiom)"
      (is (= "investigate-cold-start-latency"
             (:slug (w$id "Investigate cold start latency"))))
      (is (= false (:exists? (w$resume "nonexistent-pos" :base-dir "/tmp/wf-bind")))))
    (testing "odd kwargs count → friendly error"
      (let [r (w$id :question)]
        (is (contains? r :error))
        (is (re-find #"even number of args" (:error r)))))))

;; ============================================================================
;; Built-in markdown starters on the classpath
;; ============================================================================

(deftest builtin-starters-on-classpath-test
  (testing "feature-launch.md and doc-update.md are reachable via io/resource"
    (is (some? (io/resource "workflows/feature-launch.md")))
    (is (some? (io/resource "workflows/doc-update.md")))
    (is (nil? (io/resource "workflows/feature-launch.edn"))
        "EDN starters were converted to markdown")))

(deftest builtin-starters-pass-validation-test
  (testing "both built-in markdown starters load + validate"
    (doseq [id [:feature-launch :doc-update]]
      (let [r (workflow/workflow$load-template :id id :base-dir "/tmp/no-such")]
        (is (nil? (:error r)) (str id " must validate: " (:error r)))
        (is (= id (get-in r [:template :workflow/id])))
        (is (seq (get-in r [:template :acceptance])))
        (is (seq (get-in r [:template :stages])))
        (is (= :built-in (:source r)))))))

;; ============================================================================
;; workflow$id
;; ============================================================================

(deftest workflow-id-test
  (testing "same template + question → same slug, with template prefix"
    (let [q "Ship the MCP server health-check command"
          s (:slug (workflow/workflow$id :template :feature-launch :question q))]
      (is (= s (:slug (workflow/workflow$id :template :feature-launch :question q))))
      (is (str/starts-with? s "feature-launch--"))))
  (testing ":ad-hoc / nil template → no prefix"
    (is (not (str/includes? (:slug (workflow/workflow$id :question "Update the docs")) "--")))
    (is (not (str/includes? (:slug (workflow/workflow$id :template :ad-hoc :question "Update the docs")) "--"))))
  (testing "stopwords (incl. ship/run) dropped"
    (is (= "feature-x" (:slug (workflow/workflow$id :question "Ship feature X")))))
  (testing "validation"
    (is (contains? (workflow/workflow$id :question 123) :error))
    (is (contains? (workflow/workflow$id :question "x" :max-chars 0) :error))))

;; ============================================================================
;; Template discovery + load (markdown + legacy-EDN dual-read)
;; ============================================================================

(deftest workflow-list-templates-test
  (let [tmp (make-tmp-dir)]
    (try
      (let [wdir (io/file tmp ".brainyard/workflows")]
        (.mkdirs wdir)
        (spit (io/file wdir "proj-md.md")
              "---\nworkflow_id: proj-md\nname: Proj MD\n---\n# Acceptance\n- [ ] a1 — x\n# Stages\n- [ ] s1 do — y {agent: exec-agent, gate: none, focus: [a1]}\n")
        (spit (io/file wdir "proj-edn.edn")
              (pr-str {:workflow/id :proj-edn :workflow/name "Proj EDN"
                       :acceptance [{:id :a1 :text "x"}] :stages [{:id :s1 :recommended-agent :exec-agent}]}))
        (let [r (workflow/workflow$list-templates :base-dir tmp)
              by-id (into {} (map (juxt :id identity) (:templates r)))]
          (testing "lists project (md + edn) + built-in markdown starters"
            (is (= :project (:source (by-id "proj-md"))))
            (is (= :project (:source (by-id "proj-edn"))))
            (is (= :built-in (:source (by-id "feature-launch"))))
            (is (= :built-in (:source (by-id "doc-update")))))))
      (finally (delete-recursive (io/file tmp))))))

(deftest workflow-load-template-test
  (let [tmp (make-tmp-dir)]
    (try
      (let [wdir (io/file tmp ".brainyard/workflows")]
        (.mkdirs wdir)
        (testing "markdown template parses into the uniform shape"
          (spit (io/file wdir "good.md")
                (str "---\nworkflow_id: good\nname: Good WF\ndescription: a desc\n"
                     "defaults: {hitl: gates, max_stage_attempts: 2}\n---\n"
                     "# Acceptance\n- [ ] a1 — first\n- [ ] a2 — second\n"
                     "# Stages\n- [ ] s1 plan — design {agent: plan-agent, gate: user, focus: [a1]}\n"
                     "- [ ] s2 build — implement {agent: exec-agent, gate: none, focus: [a2]}\n"))
          (let [r (workflow/workflow$load-template :id :good :base-dir tmp)
                t (:template r)]
            (is (nil? (:error r)))
            (is (= :good (:workflow/id t)))
            (is (= "Good WF" (:workflow/name t)))
            (is (= :gates (get-in t [:defaults :hitl])))
            (is (= 2 (count (:acceptance t))))
            (is (= {:id :a1 :text "first" :status :open} (first (:acceptance t))))
            (let [s1 (first (:stages t))]
              (is (= :s1 (:id s1)))
              (is (= "plan" (:name s1)))
              (is (= :plan-agent (:agent s1)))
              (is (= :user (:gate s1)))
              (is (= [:a1] (:focus s1)))
              (is (= :pending (:status s1))))))

        (testing "validation: stage focus must resolve to an acceptance id"
          (spit (io/file wdir "bad.md")
                "---\nworkflow_id: bad\nname: Bad\n---\n# Acceptance\n- [ ] a1 — x\n# Stages\n- [ ] s1 do — y {agent: exec-agent, gate: none, focus: [a9]}\n")
          (let [r (workflow/workflow$load-template :id :bad :base-dir tmp)]
            (is (contains? r :error))
            (is (str/includes? (:error r) "a9"))))

        (testing "legacy EDN dual-read normalizes to the uniform shape"
          (spit (io/file wdir "legacy.edn")
                (pr-str {:workflow/id :legacy :workflow/name "Legacy"
                         :acceptance [{:id :a1 :text "x" :status :open}]
                         :stages [{:id :s1 :purpose "p" :recommended-agent :exec-agent :acceptance-focus [:a1]}]}))
          (let [r (workflow/workflow$load-template :id :legacy :base-dir tmp)]
            (is (nil? (:error r)))
            (is (= :legacy (get-in r [:template :workflow/id])))
            (is (= :exec-agent (:agent (first (get-in r [:template :stages])))))
            (is (= [:a1] (:focus (first (get-in r [:template :stages])))))))

        (testing "missing template → error"
          (is (contains? (workflow/workflow$load-template :id :no-such :base-dir tmp) :error)))
        (testing "missing args → error"
          (is (contains? (workflow/workflow$load-template) :error))))
      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; install-starters (markdown)
;; ============================================================================

(deftest workflow-install-starters-test
  (let [tmp (make-tmp-dir)]
    (try
      (let [r (workflow/workflow$install-starters :base-dir tmp)]
        (is (some #{"feature-launch"} (:installed r)))
        (is (some #{"doc-update"} (:installed r)))
        (is (.isFile (io/file tmp ".brainyard/workflows/feature-launch.md")))
        (is (.isFile (io/file tmp ".brainyard/workflows/doc-update.md"))))
      (testing "idempotent — second run skips existing"
        (let [r (workflow/workflow$install-starters :base-dir tmp)]
          (is (some #{"feature-launch"} (:skipped r)))))
      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; resume? — pre-bootstrap + §5 checklist round-trip + legacy dual-read
;; ============================================================================

(deftest workflow-resume-pre-bootstrap-test
  (let [tmp (make-tmp-dir)]
    (try
      (is (= {:exists? false} (workflow/workflow$resume? :id "nope" :base-dir tmp)))
      (is (contains? (workflow/workflow$resume? :id 123 :base-dir tmp) :error))
      (finally (delete-recursive (io/file tmp))))))

(deftest workflow-resume-checklist-roundtrip-test
  (let [tmp (make-tmp-dir)
        id  "feature-launch--ship-x"]
    (try
      (seed-dossier! tmp id {:last-iteration 4
                             :acc [[:a1 :satisfied] [:a2 :open]]
                             :stages [[:s1 "design" :satisfied] [:s2 "build" :pending]]})
      (let [r (workflow/workflow$resume? :id id :base-dir tmp)]
        (is (true? (:exists? r)))
        (is (= 4 (:last-iteration r)))
        (is (= :gates (:hitl-mode r)))
        (is (= {:a1 :satisfied :a2 :open} (:acceptance-state r)))
        (is (= [:s2] (:pending-stages r)))
        (is (= 2 (:stage-count r)))
        (is (= 1 (:n-pending r))))
      (finally (delete-recursive (io/file tmp))))))

(deftest workflow-resume-legacy-dual-read-test
  (testing "legacy dossier (frontmatter acceptance + stages.edn) still resumes"
    (let [tmp (make-tmp-dir)
          id  "legacy-wf"
          dir (io/file tmp ".brainyard/agents/workflow-agent" id)]
      (try
        (.mkdirs dir)
        (spit (io/file dir "dossier.md")
              (str "---\nworkflow_id: " id "\nstatus: in-progress\nlast_iteration: 2\nhitl_mode: gates\n"
                   "acceptance:\n  - id: a1\n    text: \"x\"\n    status: satisfied\n"
                   "  - id: a2\n    text: \"y\"\n    status: open\n---\n# body\n"))
        (spit (io/file dir "stages.edn")
              (pr-str {:stages [{:id :s1 :status :satisfied} {:id :s2 :status :pending}]}))
        (let [r (workflow/workflow$resume? :id id :base-dir tmp)]
          (is (= {:a1 :satisfied :a2 :open} (:acceptance-state r)))
          (is (= [:s2] (:pending-stages r))))
        (finally (delete-recursive (io/file tmp)))))))

;; ============================================================================
;; verdict-outcome — derive + :achieved guard + stage outcomes
;; ============================================================================

(deftest workflow-verdict-outcome-test
  (let [tmp (make-tmp-dir)]
    (try
      (testing ":in-progress + blockers while any criterion :open"
        (seed-dossier! tmp "vo-open" {:acc [[:a1 :open] [:a2 :satisfied]]
                                      :stages [[:s1 "x" :pending]]})
        (let [r (workflow/workflow$verdict-outcome :id "vo-open" :base-dir tmp)]
          (is (= :in-progress (:outcome r)))
          (is (false? (:achieved-ok? r)))
          (is (= ["a1:open"] (:blockers r)))
          (is (= {:s1 "pending"} (:stage-outcomes r)))))
      (testing ":achieved when all :satisfied/:descoped"
        (seed-dossier! tmp "vo-done" {:acc [[:a1 :satisfied] [:a2 :descoped]]
                                      :stages [[:s1 "x" :satisfied]]})
        (let [r (workflow/workflow$verdict-outcome :id "vo-done" :base-dir tmp)]
          (is (= :achieved (:outcome r)))
          (is (true? (:achieved-ok? r)))
          (is (empty? (:blockers r)))
          (is (= {:a1 "satisfied" :a2 "descoped"} (:acceptance-outcome r)))
          (is (= {:s1 "satisfied"} (:stage-outcomes r)))))
      (testing ":partial for a mixed (no :open) state"
        (seed-dossier! tmp "vo-mix" {:acc [[:a1 :satisfied] [:a2 :partial]] :stages []})
        (is (= :partial (:outcome (workflow/workflow$verdict-outcome :id "vo-mix" :base-dir tmp)))))
      (testing ":abandoned when all :contradicted"
        (seed-dossier! tmp "vo-bad" {:acc [[:a1 :contradicted]] :stages []})
        (is (= :abandoned (:outcome (workflow/workflow$verdict-outcome :id "vo-bad" :base-dir tmp)))))
      (testing "no checklist → error; validation"
        (is (contains? (workflow/workflow$verdict-outcome :id "no-such" :base-dir tmp) :error))
        (is (contains? (workflow/workflow$verdict-outcome :id 123 :base-dir tmp) :error)))
      (finally (delete-recursive (io/file tmp))))))

;; ============================================================================
;; Auto-finalize backstop
;; ============================================================================

(defrecord StubAgent [type sid]
  ai.brainyard.agent.core.protocol/IAgent
  (agent-id [_] (keyword (str (name type) "/stub")))
  (defagent-type [_] type)
  (session-id [_] sid)
  (user-id [_] "test-user"))

(deftest workflow-auto-finalize-test
  (let [tmp (make-tmp-dir)
        question "ship the checkout revamp"
        wid (:slug (workflow/workflow$id :template :feature-launch :question question))
        stub (->StubAgent :workflow-agent "s1")]
    (try
      (seed-dossier! tmp wid {:last-iteration 6
                              :acc [[:a1 :satisfied] [:a2 :partial]]
                              :stages [[:s1 "design" :satisfied] [:s2 "build" :satisfied]]})
      (testing "hook writes verdict.md (derived :partial) + INDEX + injects contract line"
        (let [decision (with-redefs [config/project-dir (constantly tmp)]
                         (workflow/workflow-auto-finalize
                          {:agent stub :input {:question question}
                           :result {:answer "Shipped most of it; perf criterion partial."}}))
              vfile (io/file tmp ".brainyard/agents/workflow-agent" wid "verdict.md")
              index (io/file tmp ".brainyard/agents/workflow-agent/INDEX.md")]
          (is (.isFile vfile))
          (let [c (slurp vfile)]
            (is (str/includes? c "status: partial"))
            (is (str/includes? c "iterations: 6"))
            (is (str/includes? c "acceptance_outcome:"))
            (is (str/includes? c "a1: satisfied"))
            (is (str/includes? c "stage_outcomes:"))
            (is (str/includes? c "s1: satisfied")))
          (is (.isFile index))
          (is (str/includes? (slurp index) "PARTIAL"))
          (is (= :replace (:result decision)))
          (is (str/includes? (get-in decision [:replacement :answer]) "Saved workflow dossier:"))))
      (testing "skips when any criterion still :open"
        (let [q2 "ship the open thing"
              wid2 (:slug (workflow/workflow$id :template :feature-launch :question q2))]
          (seed-dossier! tmp wid2 {:acc [[:a1 :open]] :stages []})
          (with-redefs [config/project-dir (constantly tmp)]
            (workflow/workflow-auto-finalize
             {:agent stub :input {:question q2} :result {:answer "done"}}))
          (is (not (.isFile (io/file tmp ".brainyard/agents/workflow-agent" wid2 "verdict.md"))))))
      (testing "non-workflow-agent → no-op"
        (let [other (->StubAgent :coact-agent "s1")]
          (is (nil? (with-redefs [config/project-dir (constantly tmp)]
                      (workflow/workflow-auto-finalize
                       {:agent other :input {:question question} :result {:answer "x"}}))))))
      (finally (delete-recursive (io/file tmp))))))
