;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.skills-test
  "Tests for unified skill management (brainyard + claude + agents)."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [ai.brainyard.agent.common.skills :as skills]
            [ai.brainyard.agent.core.protocol :as proto]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "skills-test-" (System/currentTimeMillis)))]
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
;; Name Sanitization
;; ============================================================================

(deftest test-sanitize-skill-name
  (are [input expected]
       (= expected (#'skills/sanitize-skill-name input))
    "my-skill"           "my-skill"
    "My Skill"           "my-skill"
    "  Deploy Helper! "  "deploy-helper"
    "UPPER--CASE"        "upper-case"
    "special@#$chars"    "specialchars"))

;; ============================================================================
;; Brainyard: Create & Read
;; ============================================================================

(deftest test-create-and-read-skill
  (let [dirs (test-dirs)
        content "# Deploy Helper\n\nAutomates deployment.\n\n## Steps\n1. Build\n2. Deploy"
        result (skills/create-skill dirs "deploy-helper" content)]
    (testing "create returns metadata"
      (is (= "deploy-helper" (:name result)))
      (is (= "Deploy Helper" (:title result)))
      (is (= :brainyard (:type result)))
      (is (= :project (:scope result)))
      (is (string? (:path result)))
      (is (string? (:created result))))
    (testing "SKILL.md exists on disk"
      (is (.exists (io/file *test-dir* ".brainyard" "skills" "deploy-helper" "SKILL.md"))))
    (testing "read-skill returns content"
      (let [r (skills/read-skill dirs "deploy-helper" :type :brainyard)]
        (is (= "deploy-helper" (:name r)))
        (is (= "Deploy Helper" (:title r)))
        (is (= content (:content r)))
        (is (= :brainyard (:type r)))
        (is (= :project (:scope r)))
        (is (= 1 (count (:files r))))))))

(deftest test-create-with-scripts-and-resources
  (let [dirs (test-dirs)
        result (skills/create-skill dirs "my-tool" "# My Tool\nDoes stuff."
                                    :scripts {"run.sh" "#!/bin/bash\necho hello"}
                                    :resources {"config.edn" "{:key \"value\"}"})]
    (is (not (contains? result :error)))
    (testing "scripts dir created"
      (is (.exists (io/file *test-dir* ".brainyard" "skills" "my-tool" "scripts" "run.sh"))))
    (testing "resources dir created"
      (is (.exists (io/file *test-dir* ".brainyard" "skills" "my-tool" "resources" "config.edn"))))
    (testing "read-skill lists all files"
      (let [r (skills/read-skill dirs "my-tool" :type :brainyard)]
        (is (= 3 (count (:files r))))))))

(deftest test-create-duplicate-error
  (let [dirs (test-dirs)]
    (skills/create-skill dirs "dup" "# Dup\nFirst")
    (let [result (skills/create-skill dirs "dup" "# Dup\nSecond")]
      (is (contains? result :error))
      (is (str/includes? (:error result) "already exists")))))

(deftest test-create-sanitizes-name
  (let [dirs (test-dirs)
        result (skills/create-skill dirs "My Cool Skill!" "# Cool\nStuff")]
    (is (= "my-cool-skill" (:name result)))
    (is (.exists (io/file *test-dir* ".brainyard" "skills" "my-cool-skill" "SKILL.md")))))

(deftest test-create-rejects-non-brainyard-type
  (let [result (skills/create-skill (test-dirs) "any" "# Any" :type :claude)]
    (is (contains? result :error))
    (is (str/includes? (:error result) ":brainyard"))))

;; ============================================================================
;; Brainyard: Update
;; ============================================================================

(deftest test-update-skill-content
  (let [dirs (test-dirs)]
    (skills/create-skill dirs "updatable" "# Updatable\nOriginal body.")
    (let [result (skills/update-skill dirs "updatable" :content "# Updatable\nNew body.")]
      (is (= "updatable" (:name result)))
      (is (= :brainyard (:type result)))
      (is (string? (:updated result))))
    (let [r (skills/read-skill dirs "updatable" :type :brainyard)]
      (is (= "# Updatable\nNew body." (:content r))))))

(deftest test-update-skill-adds-scripts
  (let [dirs (test-dirs)]
    (skills/create-skill dirs "scripted" "# Scripted\nBody.")
    (skills/update-skill dirs "scripted"
                         :scripts {"new.sh" "#!/bin/bash\necho new"})
    (is (.exists (io/file *test-dir* ".brainyard" "skills" "scripted" "scripts" "new.sh")))))

(deftest test-update-rejects-non-brainyard
  (let [result (skills/update-skill (test-dirs) "any" :type :agents)]
    (is (contains? result :error))))

(deftest test-update-not-found
  (let [result (skills/update-skill (test-dirs) "ghost" :content "# Ghost")]
    (is (contains? result :error))
    (is (str/includes? (:error result) "not found"))))

;; ============================================================================
;; Brainyard: Remove
;; ============================================================================

(deftest test-remove-skill
  (let [dirs (test-dirs)]
    (skills/create-skill dirs "deletable" "# Del\nBye")
    (is (.exists (io/file *test-dir* ".brainyard" "skills" "deletable")))
    (let [result (skills/remove-skill dirs "deletable" :type :brainyard)]
      (is (= "deletable" (:deleted result)))
      (is (= :brainyard (:type result))))
    (is (not (.exists (io/file *test-dir* ".brainyard" "skills" "deletable"))))))

(deftest test-remove-not-found
  (let [result (skills/remove-skill (test-dirs) "ghost" :type :brainyard)]
    (is (contains? result :error))))

;; ============================================================================
;; Read Not Found
;; ============================================================================

(deftest test-read-skill-not-found
  (let [result (skills/read-skill (test-dirs) "nonexistent" :type :brainyard)]
    (is (contains? result :error))
    (is (str/includes? (:error result) "not found"))))

;; ============================================================================
;; List Skills
;; ============================================================================

(deftest test-list-skills-brainyard
  (let [dirs (test-dirs)]
    (skills/create-skill dirs "skill-a" "# Skill A\nFirst skill")
    (skills/create-skill dirs "skill-b" "# Skill B\nSecond skill"
                         :scripts {"run.sh" "echo"})
    (let [result (skills/list-skills dirs :type :brainyard :scope :project)]
      (is (= 2 (count result)))
      (is (every? #(= :brainyard (:type %)) result))
      (is (every? #(= :project (:scope %)) result))
      (is (every? :name result))
      (is (every? :title result))
      (is (some #(= 2 (:file-count %)) result)))))

;; ============================================================================
;; Find Skills
;; ============================================================================

(deftest test-find-skills-brainyard
  (let [dirs (test-dirs)]
    (skills/create-skill dirs "kube-deploy" "# Kube Deploy\nKubernetes deployment helper.")
    (skills/create-skill dirs "aws-tool" "# AWS Tool\nAmazon Web Services helper.")
    (let [hits (skills/find-skills dirs "kube" :type :brainyard)]
      (is (vector? hits))
      (is (= 1 (count hits)))
      (is (= "kube-deploy" (:name (first hits)))))))

;; ============================================================================
;; Discovery ranking — `skills$find` is on the base agent roster, so every agent
;; runs it before multi-step work. It must be local, fast, ordered and quiet.
;; ============================================================================

(defn- ranked-names [hits] (mapv :name hits))

(deftest test-find-is-ranked-by-field-weight
  (let [dirs (test-dirs)]
    (skills/create-skill dirs "markdown-lint"
                         "---\ntitle: Markdown Lint\ndescription: Lint markdown\ntags: markdown, lint\n---\n# Markdown Lint")
    ;; mentions markdown only in prose — must rank below, if it qualifies at all
    (skills/create-skill dirs "deploy-helper"
                         "# Deploy Helper\nDeploys the site and its markdown docs.")
    (let [hits (skills/find-skills dirs "markdown" :type :brainyard)]
      (is (= "markdown-lint" (:name (first hits)))
          "a name/tag/title hit must outrank a description-only mention")
      (is (every? pos? (map :score hits)))
      (is (apply >= (map :score hits)) "results must be ordered best-first"))))

(deftest test-find-miss-returns-empty-not-marketplace
  (let [dirs (test-dirs)]
    (skills/create-skill dirs "kube-deploy" "# Kube Deploy\nKubernetes helper.")
    (testing "a genuine miss is an empty vector — never a marketplace catalog"
      (let [hits (skills/find-skills dirs "wombat teleportation" :type :brainyard)]
        (is (vector? hits))
        (is (empty? hits))))))

(deftest test-find-drops-noise-tokens
  (let [dirs (test-dirs)]
    (skills/create-skill dirs "autonomous-runner" "# Autonomous Runner\nRuns autonomously.")
    (testing "sub-3-char tokens are dropped rather than substring-matched"
      ;; "no" sits inside "autonomous" — a raw substring match would hit
      (is (empty? (skills/find-skills dirs "no" :type :brainyard))))
    (testing "word-start anchoring: a token inside a word is not a match"
      (is (empty? (skills/find-skills dirs "tonomous" :type :brainyard))))
    (testing "a genuine word-start prefix still matches"
      (is (= ["autonomous-runner"]
             (ranked-names (skills/find-skills dirs "auto" :type :brainyard)))))))

(deftest test-find-respects-limit
  (let [dirs (test-dirs)]
    (doseq [i (range 5)]
      (skills/create-skill dirs (str "report-" i) (str "# Report " i "\nA report tool.")))
    (is (= 2 (count (skills/find-skills dirs "report" :type :brainyard :limit 2))))))

(deftest test-find-collapses-duplicate-names-across-backends
  (let [sample [{:name "pdf" :type :agents}
                {:name "pdf" :type :claude}
                {:name "pdf" :type :brainyard :scope :user}
                {:name "solo" :type :claude}]
        hits   (skills/rank-skills sample "pdf")]
    (testing "one row per name, won by the most local backend"
      (is (= 1 (count hits)))
      (is (= :brainyard (:type (first hits))))
      (is (= :user (:scope (first hits)))))
    (testing "the shadowed backends stay visible as :also-in"
      (is (= ["claude" "agents"] (:also-in (first hits)))))))

;; ============================================================================
;; Dynamic registration — skill names are NOT unique across backends. Every
;; discovered skill must remain reachable under some id.
;; ============================================================================

(deftest test-resolve-registrations-no-silent-overwrite
  (let [sample [{:name "pdf" :type :agents}
                {:name "pdf" :type :claude}
                {:name "pdf" :type :brainyard :scope :project}
                {:name "solo" :type :claude}]
        [regs shadowed] (skills/resolve-registrations sample)]
    (testing "every discovered skill gets a registration"
      (is (= 4 (count regs))))
    (testing "ids are unique — nothing is silently overwritten"
      (is (= 4 (count (distinct (map :id regs))))))
    (testing "the most local backend keeps the bare :skill$<name> id"
      (let [bare (first (filter #(= :skill$pdf (:id %)) regs))]
        (is (= :brainyard (:type bare)))
        (is (= :project (:scope bare)))))
    (testing "shadowed skills get backend-qualified ids"
      (is (= #{:skill$claude$pdf :skill$agents$pdf}
             (set (map :id (filter #(not= :skill$pdf (:id %))
                                   (filter #(= "pdf" (:name %)) regs)))))))
    (testing "an uncontested name is untouched"
      (is (= :skill$solo (:id (first (filter #(= "solo" (:name %)) regs))))))
    (testing "losers are reported for logging"
      (is (= 2 (count shadowed)))
      (is (every? #(= "pdf" (:name %)) shadowed)))))

;; ============================================================================
;; Dynamic skill dispatch — calling :skill$<name> LOADS the procedure into the
;; calling agent (returned as the tool result + pinned as a live artifact),
;; rather than running it in a skill-agent sub-agent. `dispatch: agent` in the
;; frontmatter is the opt-out.
;; ============================================================================

(def ^:private plain-skill-md
  (str "---\ntitle: Lint Markdown\ndescription: Lint markdown files\n"
       "tags: markdown, lint\n---\n"
       "# Lint Markdown\n1. Run scripts/lint.sh\n2. Fix what it reports\n"))

(def ^:private delegating-skill-md
  (str "---\ntitle: Heavy Job\ndescription: Runs in its own agent\n"
       "dispatch: agent\n---\n"
       "# Heavy Job\nDo the heavy thing.\n"))

(deftest test-dispatch-frontmatter-parsed
  (let [plain (#'skills/parse-skill-md plain-skill-md "lint-markdown")
        deleg (#'skills/parse-skill-md delegating-skill-md "heavy-job")]
    (testing "absent dispatch leaves the key off — the default path"
      (is (nil? (:dispatch plain))))
    (testing "dispatch: agent is surfaced, normalized"
      (is (= "agent" (:dispatch deleg))))))

(deftest test-skill-call-loads-procedure-into-context
  (let [dirs (test-dirs)]
    (with-redefs [skills/current-dirs (fn [] dirs)]
      (skills/create-skill dirs "lint-markdown" plain-skill-md)
      (let [f (#'skills/make-dynamic-skill-fn "lint-markdown" :brainyard :project)
            r (f :question "lint my docs")]
        (testing "the SKILL.md body comes back as the tool result"
          (is (true? (:loaded r)))
          (is (= plain-skill-md (:content r)))
          (is (= "lint-markdown" (:skill r))))
        (testing ":path points at SKILL.md itself, not the skill directory"
          (is (str/ends-with? (str (:path r)) "/SKILL.md"))
          (is (.exists (io/file (:path r)))))
        (testing "no sub-agent answer is produced on the load path"
          (is (nil? (:answer r))))))))

(defn- mock-agent
  "Minimal agent exposing the cross-turn store the artifact registry writes to.
   Mirrors the reify in artifacts_test."
  [init-atom]
  (reify
    proto/IAgent
    (agent-id [_] :mock)
    (agent-name [_] "mock")
    (agent-description [_] "mock")
    (user-id [_] "u")
    (session-id [_] "s")
    (defagent-type [_] :mock)
    (process [_ _ _] nil)
    (get-tools [_] nil)
    (get-state [_] {})
    proto/IAgentState
    (get-st-memory-init [_] init-atom)
    proto/IAgentBTIntegration
    (get-bt-st-memory [_] (atom {}))))

(deftest test-skill-call-pins-the-skill-as-a-live-artifact
  (let [dirs (test-dirs)
        init (atom {})]
    (with-redefs [skills/current-dirs (fn [] dirs)]
      (skills/create-skill dirs "lint-markdown" plain-skill-md)
      (binding [proto/*current-agent* (mock-agent init)]
        (let [r ((#'skills/make-dynamic-skill-fn "lint-markdown" :brainyard :project))
              d (first (:live-artifacts @init))]
          (testing "the SKILL.md is registered on the CALLING agent"
            (is (some? d))
            (is (= :file (:source d)))
            (is (str/ends-with? (:path d) "/SKILL.md"))
            (is (= "skill: lint-markdown" (:name d))))
          (testing "it renders in full, not as a preview"
            (is (true? (:full? d)))
            (is (pos? (:max-chars d))))
          (testing "the caller is told the artifact id"
            (is (= (:id d) (:artifact-id r)))))))))

(deftest test-skill-call-degrades-without-a-running-agent
  ;; proto/*current-agent* is unbound in tests, so artifact registration cannot
  ;; happen. The content must still come back — degrade, never fail.
  (let [dirs (test-dirs)]
    (with-redefs [skills/current-dirs (fn [] dirs)]
      (skills/create-skill dirs "solo" "# Solo\nA procedure.")
      (let [r ((#'skills/make-dynamic-skill-fn "solo" :brainyard :project))]
        (is (true? (:loaded r)))
        (is (str/includes? (:content r) "A procedure."))
        (is (nil? (:artifact-id r)) "no agent means no artifact id, not an error")
        (is (nil? (:error-message r)))))))

(deftest test-skill-call-question-is-optional-on-the-load-path
  (let [dirs (test-dirs)]
    (with-redefs [skills/current-dirs (fn [] dirs)]
      (skills/create-skill dirs "no-q" "# No Q\nSteps.")
      (let [f (#'skills/make-dynamic-skill-fn "no-q" :brainyard :project)]
        (are [r] (true? (:loaded r))
          (f)
          (f :question "")
          (f :question "something"))))))

(deftest test-dispatch-agent-opts-out-of-loading
  (let [dirs (test-dirs)]
    (with-redefs [skills/current-dirs (fn [] dirs)]
      (skills/create-skill dirs "heavy-job" delegating-skill-md)
      (let [f (#'skills/make-dynamic-skill-fn "heavy-job" :brainyard :project)]
        (testing "it does not take the load path"
          (is (nil? (:loaded (f :question "do it")))))
        (testing "a question is required, unlike the load path"
          (let [r (f :question "")]
            (is (some? (:error-message r)))
            (is (str/includes? (:error-message r) "dispatch: agent"))))))))

(deftest test-skill-call-missing-skill-errors
  (with-redefs [skills/current-dirs (fn [] (test-dirs))]
    (let [r ((#'skills/make-dynamic-skill-fn "ghost" :brainyard :project))]
      (is (str/includes? (:error-message r) "not found"))
      (is (= "ghost" (:skill r)))
      (is (nil? (:loaded r))))))

;; ============================================================================
;; Command output shapes must match their declared :output-schema — the schema
;; is what the LLM is told to expect.
;; ============================================================================

(deftest test-command-output-matches-declared-schema
  (with-redefs [skills/current-dirs (fn [] (test-dirs))]
    (skills/create-skill (test-dirs) "shape-check" "# Shape Check\nVerifies output shape.")
    (testing "skills$list returns {:result [...] :count n}"
      (let [r (skills/skills$list :type "brainyard")]
        (is (vector? (:result r)))
        (is (= (count (:result r)) (:count r)))))
    (testing "skills$find returns {:result [...] :count n}"
      (let [r (skills/skills$find :query "shape" :type "brainyard")]
        (is (vector? (:result r)))
        (is (= (count (:result r)) (:count r)))
        (is (= "shape-check" (:name (first (:result r)))))))
    (testing "a blank query is still rejected"
      (is (contains? (skills/skills$find :query "") :error)))))

;; ============================================================================
;; SKILL.md Parsing with Frontmatter
;; ============================================================================

(deftest test-parse-skill-md-with-frontmatter
  (let [content (str "---\n"
                     "title: Custom Title\n"
                     "description: A custom description\n"
                     "tags: deploy, ci, automation\n"
                     "version: 1.2.0\n"
                     "---\n"
                     "\n# Heading\n\nBody text")
        meta (#'skills/parse-skill-md content "test-skill")]
    (is (= "Custom Title" (:title meta)))
    (is (= "A custom description" (:description meta)))
    (is (= ["deploy" "ci" "automation"] (:tags meta)))
    (is (= "1.2.0" (:version meta)))))

(deftest test-parse-skill-md-without-frontmatter
  (let [content "# My Skill\n\nThis skill does things.\n\n## Usage\nRun it."
        meta (#'skills/parse-skill-md content "my-skill")]
    (is (= "My Skill" (:title meta)))
    (is (= "This skill does things." (:description meta)))))

;; ============================================================================
;; skills$import — arg mapping (regression: create-skill is
;; [dirs skill-name content & opts]; import must pass :scope as a keyword arg,
;; NOT positionally. The old bug shifted args so the SKILL dir was named after
;; the scope keyword ("project") and its body was the literal skill name.)
;; ============================================================================

(deftest test-import-maps-args-correctly
  (with-redefs [skills/current-dirs (fn [] (test-dirs))]
    (let [src (io/file *test-dir* "src")
          _   (.mkdirs src)
          md  (io/file src "SKILL.md")
          _   (spit md (str "---\nname: readme-linter\n"
                            "description: Lint README files for common issues.\n---\n"
                            "\n# readme-linter\n\nReal body content that must survive import.\n"))
          result (skills/skills$import :path (.getPath md) :scope "project")
          linter-md  (io/file *test-dir* ".brainyard/skills/readme-linter/SKILL.md")
          scope-dir  (io/file *test-dir* ".brainyard/skills/project")]
      (testing "skill lands under its NAME, scoped to :project"
        (is (nil? (:error result)))
        (is (= "readme-linter" (:name result)))
        (is (= :project (:scope result)))
        (is (.exists linter-md)))
      (testing "the scope keyword never becomes a skill dir"
        (is (not (.exists scope-dir))
            "a dir named after the scope keyword means args were shifted"))
      (testing "the real SKILL.md body is preserved, not replaced by the skill name"
        (let [body (slurp linter-md)]
          (is (str/includes? body "Real body content that must survive import."))
          (is (not= "readme-linter" (str/trim body))))))))
