;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.projects-test
  "Unit tests for the user-scope project registry (core.projects).

   These take an explicit `dirs` map rather than redefining `user.home`, which
   is the whole reason the registry API is `dirs`-first: a temp-rooted map is
   all a test needs."
  (:require [ai.brainyard.agent.core.config :as cfg]
            [ai.brainyard.agent.core.projects :as projects]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *home* nil)

(defn- rm-rf [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf c)))
    (.delete f)))

(defn with-tmp-home [t]
  ;; Canonicalized: on macOS /tmp is a symlink to /private/tmp, and the registry
  ;; stores canonical paths (that's what makes a slug stable). Without this the
  ;; expected values here would be the pre-symlink form and never match.
  (let [home (.getCanonicalPath
              (doto (io/file (str "/tmp/by-projects-test-home-" (System/nanoTime)))
                (.mkdirs)))]
    (try
      (binding [*home* home] (t))
      (finally (rm-rf (io/file home))))))

(use-fixtures :each with-tmp-home)

(defn- dirs
  "A dirs map rooted at the temp home, optionally with a project-dir."
  ([] (dirs nil))
  ([project-dir]
   (cond-> {:user-dir *home*}
     project-dir (assoc :project-dir project-dir :working-dir project-dir))))

(defn- mkdir!
  "Create `path` and return its CANONICAL form — what the registry will store."
  [path]
  (let [f (io/file path)]
    (.mkdirs f)
    (.getCanonicalPath f)))

;; ============================================================================
;; Slug
;; ============================================================================

(deftest slug-shape-test
  (testing "slug is <basename>-<8 hex>, stable, and space-free"
    (let [p    (mkdir! (str *home* "/repos/brainyard"))
          slug (projects/project-slug p)]
      (is (re-matches #"brainyard-[0-9a-f]{8}" slug))
      (is (= slug (projects/project-slug p)) "stable across calls")
      (is (not (str/includes? slug " ")))))

  (testing "spaces and odd characters never reach the folder name"
    (let [p    (mkdir! (str *home* "/repos/My Cool App! (v2)"))
          slug (projects/project-slug p)]
      (is (not (str/includes? slug " ")))
      (is (re-matches #"[A-Za-z0-9._-]+" slug))
      (is (str/starts-with? slug "My-Cool-App-v2"))))

  (testing "same basename in different locations gets different slugs"
    (let [a (mkdir! (str *home* "/one/brainyard"))
          b (mkdir! (str *home* "/two/brainyard"))]
      (is (not= (projects/project-slug a) (projects/project-slug b)))
      (is (str/starts-with? (projects/project-slug a) "brainyard-"))
      (is (str/starts-with? (projects/project-slug b) "brainyard-"))))

  (testing "blank / nil input yields nil"
    (is (nil? (projects/project-slug nil)))
    (is (nil? (projects/project-slug "  "))))

  (testing "filesystem root degrades to a usable name rather than blank"
    (is (str/starts-with? (projects/project-slug "/") "root-"))))

;; ============================================================================
;; Reverse mapping — the property separator-substitution can't hold
;; ============================================================================

(deftest slug-round-trip-test
  (testing "path -> slug -> path is exact"
    (let [p (mkdir! (str *home* "/repos/brainyard"))]
      (projects/register-project! (dirs) p)
      (is (= p (projects/project-path-for-slug
                (dirs) (projects/project-slug p))))))

  (testing "a path segment containing a literal '-' round-trips exactly"
    ;; This is the case that defeats separator substitution: `-Users-me-my-app`
    ;; cannot say whether the source was `/Users/me/my/app` or `/Users/me/my-app`.
    (let [dashed (mkdir! (str *home* "/Projects/my-app"))
          nested (mkdir! (str *home* "/Projects/my/app"))]
      (projects/register-project! (dirs) dashed)
      (projects/register-project! (dirs) nested)
      (is (= dashed (projects/project-path-for-slug
                     (dirs) (projects/project-slug dashed))))
      (is (= nested (projects/project-path-for-slug
                     (dirs) (projects/project-slug nested))))
      (is (not= (projects/project-slug dashed) (projects/project-slug nested)))))

  (testing "an unregistered slug reverses to nil"
    (is (nil? (projects/project-path-for-slug (dirs) "nope-deadbeef")))
    (is (nil? (projects/project-path-for-slug (dirs) nil)))))

;; ============================================================================
;; Registration
;; ============================================================================

(deftest register-writes-record-test
  (testing "record lands at ~/.brainyard/projects/<slug>/project.edn"
    (let [p   (mkdir! (str *home* "/repos/brainyard"))
          rec (projects/register-project! (dirs) p)
          f   (io/file (projects/project-user-dir (dirs) p) "project.edn")]
      (is (.isFile f))
      (is (= p (:path rec)))
      (is (= "brainyard" (:name rec)))
      (is (= (projects/project-slug p) (:slug rec)))
      (is (= projects/schema-version (:schema-version rec)))
      (is (inst? (:created-at rec)))
      (is (inst? (:last-opened-at rec)))
      (is (= rec (edn/read-string (slurp f))) "on-disk record matches return"))))

(deftest register-is-idempotent-test
  (testing "re-registering preserves :created-at and advances :last-opened-at"
    (let [p     (mkdir! (str *home* "/repos/brainyard"))
          one   (projects/register-project! (dirs) p)
          _     (Thread/sleep 5)
          two   (projects/register-project! (dirs) p)
          root  (io/file (projects/projects-root (dirs)))]
      (is (= (:created-at one) (:created-at two)) ":created-at is preserved")
      (is (>= (inst-ms (:last-opened-at two)) (inst-ms (:last-opened-at one))))
      (is (= (:slug one) (:slug two)))
      (is (= 1 (count (filter #(.isDirectory ^java.io.File %) (.listFiles root))))
          "no second folder for the same path")))

  (testing "unknown keys written by a future version survive a re-register"
    (let [p (mkdir! (str *home* "/repos/brainyard"))
          _ (projects/register-project! (dirs) p)
          f (io/file (projects/project-user-dir (dirs) p) "project.edn")]
      (spit f (pr-str (assoc (edn/read-string (slurp f)) :future-key "keep me")))
      (is (= "keep me" (:future-key (projects/register-project! (dirs) p)))))))

(deftest ensure-registered-does-not-churn-test
  (testing "ensure- returns the existing record without re-stamping"
    (let [p     (mkdir! (str *home* "/repos/brainyard"))
          first (projects/register-project! (dirs) p)
          _     (Thread/sleep 5)
          again (projects/ensure-project-registered! (dirs) p)]
      (is (= (:last-opened-at first) (:last-opened-at again)))))

  (testing "ensure- registers when there is no record yet"
    (let [p (mkdir! (str *home* "/repos/fresh"))]
      (is (nil? (projects/project-path-for-slug (dirs) (projects/project-slug p))))
      (is (= p (:path (projects/ensure-project-registered! (dirs) p)))))))

(deftest register-uses-project-dir-by-default-test
  (testing "arity-1 registers (:project-dir dirs)"
    (let [p (mkdir! (str *home* "/repos/brainyard"))]
      (is (= p (:path (projects/register-project! (dirs p))))))))

(deftest git-remote-test
  (testing "origin URL is read out of .git/config without shelling out"
    (let [p (mkdir! (str *home* "/repos/brainyard"))]
      (mkdir! (str p "/.git"))
      (spit (io/file p ".git" "config")
            (str "[core]\n\trepositoryformatversion = 0\n"
                 "[remote \"upstream\"]\n\turl = git@github.com:other/x.git\n"
                 "[remote \"origin\"]\n\turl = git@github.com:grumatic/brainyard.git\n"
                 "\tfetch = +refs/heads/*:refs/remotes/origin/*\n"))
      (is (= "git@github.com:grumatic/brainyard.git"
             (:git-remote (projects/register-project! (dirs) p))))))

  (testing "no git dir -> nil, not a failure"
    (let [p (mkdir! (str *home* "/repos/plain"))]
      (is (nil? (:git-remote (projects/register-project! (dirs) p)))))))

;; ============================================================================
;; Listing + derived index
;; ============================================================================

;; NOTE: these are separate deftests rather than `testing` blocks in one, so the
;; `:each` fixture gives each a clean registry. Sharing one registry across
;; blocks made the ordering/`:missing?` assertions depend on what an earlier
;; block happened to register.

(deftest list-projects-empty-test
  (testing "empty registry lists as []"
    (is (= [] (projects/list-projects (dirs))))))

(deftest list-projects-ordering-test
  (testing "lists every registered project, newest :last-opened-at first"
    (let [a (mkdir! (str *home* "/repos/alpha"))
          b (mkdir! (str *home* "/repos/beta"))]
      (projects/register-project! (dirs) a)
      (Thread/sleep 5)
      (projects/register-project! (dirs) b)
      (is (= ["beta" "alpha"] (mapv :name (projects/list-projects (dirs))))))))

(deftest list-projects-missing-test
  (testing "a project whose dir is gone is flagged :missing?, not dropped"
    (let [gone (mkdir! (str *home* "/repos/gone"))]
      (projects/register-project! (dirs) gone)
      (rm-rf (io/file gone))
      (let [row (first (projects/list-projects (dirs)))]
        (is (= "gone" (:name row)))
        (is (true? (:missing? row)))))))

(deftest list-projects-corrupt-record-test
  (testing "a corrupt project.edn is skipped, not fatal"
    (let [ok  (mkdir! (str *home* "/repos/ok"))
          bad (mkdir! (str *home* "/repos/bad"))]
      (projects/register-project! (dirs) ok)
      (projects/register-project! (dirs) bad)
      (spit (io/file (projects/project-user-dir (dirs) bad) "project.edn")
            "{:this is not( valid edn")
      (is (= ["ok"] (mapv :name (projects/list-projects (dirs))))))))

(deftest index-contents-test
  (testing "index.edn maps slug -> path for every registered project"
    (let [a (mkdir! (str *home* "/repos/alpha"))
          b (mkdir! (str *home* "/repos/beta"))]
      (projects/register-project! (dirs) a)
      (projects/register-project! (dirs) b)
      (let [idx (edn/read-string
                 (slurp (io/file (projects/projects-root (dirs)) "index.edn")))]
        (is (= projects/schema-version (:schema-version idx)))
        (is (= {(projects/project-slug a) a
                (projects/project-slug b) b}
               (:projects idx)))))))

(deftest index-heals-test
  (testing "a corrupt index heals on refresh and never affects slug->path"
    (let [p   (mkdir! (str *home* "/repos/brainyard"))
          _   (projects/register-project! (dirs) p)
          idx (io/file (projects/projects-root (dirs)) "index.edn")]
      (spit idx "}}}garbage")
      ;; The authoritative lookup is unaffected — it never reads the index.
      (is (= p (projects/project-path-for-slug (dirs) (projects/project-slug p))))
      (projects/refresh-projects-index! (dirs))
      (is (= {(projects/project-slug p) p}
             (:projects (edn/read-string (slurp idx))))))))

;; ============================================================================
;; Scope policy
;; ============================================================================

(deftest projects-is-user-scoped-test
  (testing "the registry resolves at :user scope"
    (is (= (str *home* "/.brainyard/projects")
           (projects/projects-root (dirs)))))

  (testing ":project scope is refused by subdir-scope-policy"
    (is (= #{:user} (cfg/subdir-allowed-scopes "projects")))
    (is (nil? (cfg/brainyard-subdir {:user-dir *home* :project-dir "/tmp/x"}
                                    "projects" :project))))

  (testing "no user-dir -> nil rather than a stray relative path"
    (is (nil? (projects/projects-root {})))
    (is (nil? (projects/project-user-dir {} "/tmp/x")))
    (is (nil? (projects/register-project! {} "/tmp/x")))))
