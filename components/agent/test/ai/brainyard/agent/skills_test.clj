;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.skills-test
  "Tests for unified skill management (brainyard + claude + agents)."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [ai.brainyard.agent.common.skills :as skills]
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
