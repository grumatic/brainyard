;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.skill-interop-test
  "Tests for skill interop (R5a): open-standard `name` front-matter parsing and
   the skills$import command (path read, name derivation, validation, create)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.agent.common.skills :as skills]
            [clojure.java.io :as io]))

(defn- tmp-skill-file [content]
  (let [f (io/file (System/getProperty "java.io.tmpdir")
                   (str "skill-interop-" (System/currentTimeMillis) "-" (rand-int 100000) ".md"))]
    (spit f content) f))

;; ============================================================================
;; parse-skill-md — open-standard `name`
;; ============================================================================

(deftest parse-name-frontmatter
  (testing "open-standard `name` becomes the title and is surfaced as :fm-name"
    (let [p (#'skills/parse-skill-md "---\nname: Deploy Flow\ndescription: Ship it.\n---\n# Body\nstep" "dir-name")]
      (is (= "Deploy Flow" (:title p)))
      (is (= "Deploy Flow" (:fm-name p)))
      (is (= "Ship it." (:description p)))
      (is (= "dir-name" (:name p)) "canonical :name stays the dir/skill name")))
  (testing "legacy `title` still wins when present"
    (let [p (#'skills/parse-skill-md "---\ntitle: T\nname: N\ndescription: d\n---\n# b" "dir")]
      (is (= "T" (:title p)))
      (is (= "N" (:fm-name p)))))
  (testing "no front-matter name → no :fm-name key"
    (let [p (#'skills/parse-skill-md "# Just A Heading\nbody" "dir")]
      (is (not (contains? p :fm-name))))))

;; ============================================================================
;; skills$import
;; ============================================================================

(deftest import-guards
  (testing "blank path rejected"
    (is (:error (skills/skills$import :path ""))))
  (testing "missing file rejected"
    (is (:error (skills/skills$import :path "/no/such/SKILL.md"))))
  (testing "SKILL.md with no description rejected (standard requires name + description)"
    (let [f (tmp-skill-file "---\nname: foo\n---\n# Foo")]
      (try (is (re-find #"description" (:error (skills/skills$import :path (.getPath f)))))
           (finally (io/delete-file f true))))))

(deftest import-happy-path-calls-create
  (let [captured (atom nil)
        f (tmp-skill-file "---\nname: deploy-flow\ndescription: Ship the app.\n---\n# Deploy\n1. build")]
    (try
      (with-redefs [skills/create-skill (fn [_dirs sname content & {:keys [scope] :as _opts}]
                                          (reset! captured {:scope scope :name sname :content content})
                                          {:name sname :scope scope :path "/skills/deploy-flow" :created "now"})]
        (let [res (skills/skills$import :path (.getPath f) :scope "user")]
          (is (= "deploy-flow" (:name res)))
          (is (= "deploy-flow" (:name @captured)) "name derived from front-matter `name`")
          (is (= :user (:scope @captured)) "scope string coerced to keyword")
          (is (re-find #"Ship the app" (:content @captured)) "full SKILL.md content passed through")))
      (finally (io/delete-file f true))))
  (testing ":name arg overrides front-matter name"
    (let [captured (atom nil)
          f (tmp-skill-file "---\nname: original\ndescription: d\n---\n# x")]
      (try
        (with-redefs [skills/create-skill (fn [_d sname _c & _o] (reset! captured sname) {:name sname})]
          (skills/skills$import :path (.getPath f) :name "override-name")
          (is (= "override-name" @captured)))
        (finally (io/delete-file f true))))))
