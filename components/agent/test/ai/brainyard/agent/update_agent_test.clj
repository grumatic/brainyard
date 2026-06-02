;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.update-agent-test
  "Tests for update-agent: registration, inherited bt-factory (CoAct), curated
   agent-tools roster (positive + negative assertions), instruction-content
   anchors, and unit tests for the update$* helper commands (slug determinism,
   frontmatter round-trip, write collision suffix, INDEX prepend ordering,
   find), plus end-to-end tests for the update$apply pipeline against a real
   temp git repo (happy path, count-mismatch refusal, dirty-file refusal,
   new-file mode, V1-mismatch rollback)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [ai.brainyard.agent.common.update :as update]
            [ai.brainyard.agent.common.update-agent]
            [ai.brainyard.agent.core.tool :as tool])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn- make-tmp-dir []
  (-> (Files/createTempDirectory "update-test-" (into-array FileAttribute []))
      .toFile
      .getAbsolutePath))

(defn- delete-recursive [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)]
      (delete-recursive c)))
  (.delete f))

(defn- init-repo!
  "Initialize a git repo at base-dir, write `target` with `content`, commit."
  [base-dir target content]
  (sh/sh "git" "init" "-q" :dir base-dir)
  (sh/sh "git" "config" "user.email" "test@local" :dir base-dir)
  (sh/sh "git" "config" "user.name"  "Test"        :dir base-dir)
  (sh/sh "git" "config" "commit.gpgsign" "false"   :dir base-dir)
  (let [f (io/file base-dir target)]
    (.mkdirs (.getParentFile f))
    (spit f content))
  (sh/sh "git" "add" target :dir base-dir)
  (sh/sh "git" "commit" "-q" "-m" "init" :dir base-dir))

;; ============================================================================
;; Registration
;; ============================================================================

(deftest registration-test
  (testing "update-agent is registered as an agent"
    (let [agent-defs (tool/get-tool-defs :type :agent)]
      (is (contains? agent-defs :update-agent))
      (let [agent-def (get agent-defs :update-agent)]
        (is (= :update-agent (:id agent-def)))
        (is (some? (:fn agent-def)))
        (is (some? (:meta agent-def)))))))

;; ============================================================================
;; Inheritance via run-coact-derived
;; ============================================================================

(deftest inheritance-test
  (require 'ai.brainyard.agent.common.coact-agent)
  (let [agent-def (get (tool/get-tool-defs :type :agent) :update-agent)]
    (testing "update-agent's :fn is registered (the wrap-fn invoking run-coact-derived)"
      (is (some? (:fn agent-def))))

    (testing "update-agent pins :bt-factory explicitly (so setup-agent-by-id picks it up)"
      (let [bt-factory (get-in agent-def [:meta :bt-factory])]
        (is (fn? bt-factory))
        (let [bt (bt-factory {:max-iterations 3})]
          (is (vector? bt))
          (is (= :sequence (first bt)))
          (is (= "coact.sequence" (namespace (get-in bt [1 :id])))))))))

;; ============================================================================
;; Agent tools — positive + negative
;; ============================================================================

(defn- update-tool-ids []
  (let [agent-def   (get (tool/get-tool-defs :type :agent) :update-agent)
        agent-tools (get-in agent-def [:meta :agent-tools])]
    (set (map (comp :id meta deref) (:tools agent-tools)))))

(deftest agent-tools-test
  (testing "update-agent :agent-tools includes the file-IO + probe surface"
    (let [ids (update-tool-ids)]
      ;; File I/O — only legitimate write-side tools
      (is (contains? ids :update-file))
      (is (contains? ids :write-file))

      ;; Reads + probes
      (is (contains? ids :read-file))
      (is (contains? ids :grep))
      (is (contains? ids :bash))
      (is (contains? ids :search))

      ;; Synthesis
      (is (contains? ids :query$llm))

      ;; Bookkeeping / invocation
      (is (contains? ids :list-tools))
      (is (contains? ids :get-tool-info))
      (is (contains? ids :call-tool))

      ;; Background fan-out
      (is (contains? ids :task$run))

      ;; Runtime config
      (is (contains? ids :agent-runtime$config))

      ;; update$* helpers (all seven)
      (is (contains? ids :update$slug))
      (is (contains? ids :update$frontmatter))
      (is (contains? ids :update$write))
      (is (contains? ids :update$index-append))
      (is (contains? ids :update$read-record))
      (is (contains? ids :update$find))
      (is (contains? ids :update$apply))))

  (testing "update-agent :agent-tools EXCLUDES forbidden + out-of-scope tools"
    (let [ids (update-tool-ids)]
      ;; Hard Rule 4 — no clone-self
      (is (not (contains? ids :query$clone))
          "query$clone must not be in update-agent's roster (clone-self forbidden)")

      ;; Web surface lives in explore-agent
      (is (not (contains? ids :web-search)))
      (is (not (contains? ids :fetch-url))
          "fetch-url is irrelevant for editing — discovery happens in explore-agent")

      ;; MCP / skills surfaces also live in explore-agent
      (is (not (contains? ids :mcp$server)))
      (is (not (contains? ids :mcp$tools)))
      (is (not (contains? ids :mcp$lifecycle)))
      (is (not (contains? ids :skills$list)))
      (is (not (contains? ids :skills$find)))
      (is (not (contains? ids :skills$read))))))

;; ============================================================================
;; Instruction & tool-context content anchors
;; ============================================================================

(deftest instruction-content-test
  (testing "instruction string contains the cardinal update-agent anchors"
    (let [agent-def   (get (tool/get-tool-defs :type :agent) :update-agent)
          instruction (get-in agent-def [:meta :instruction])]
      (is (string? instruction))
      (is (not (str/blank? instruction)))

      ;; Three modes
      (is (str/includes? instruction "PATTERN"))
      (is (str/includes? instruction "SYNTAX"))
      (is (str/includes? instruction "NEW-FILE"))

      ;; Pipeline anchors
      (is (str/includes? instruction "PROBE"))
      (is (str/includes? instruction "APPLY"))
      (is (str/includes? instruction "VERIFY"))
      (is (str/includes? instruction "ROLLBACK"))
      (is (str/includes? instruction "PERSIST"))

      ;; Hard rules
      (is (str/includes? instruction "HARD RULES"))
      (is (str/includes? instruction "clone-self"))
      (is (str/includes? instruction "git commit"))
      (is (str/includes? instruction "CLEAN-FIRST"))

      ;; Persistence + handoff contract
      (is (str/includes? instruction ".brainyard/agents/update-agent/"))
      (is (str/includes? instruction "Saved edit:"))
      (is (str/includes? instruction "Rollback:")))))

(deftest tool-context-content-test
  (testing "tool-context names the key tools and update$apply"
    (let [agent-def    (get (tool/get-tool-defs :type :agent) :update-agent)
          tool-context (get-in agent-def [:meta :tool-context])]
      (is (string? tool-context))
      (is (not (str/blank? tool-context)))

      ;; Write-side tools
      (is (str/includes? tool-context "update-file"))
      (is (str/includes? tool-context "write-file"))

      ;; Probes + bash
      (is (str/includes? tool-context "read-file"))
      (is (str/includes? tool-context "grep"))
      (is (str/includes? tool-context "bash"))

      ;; Allowed / forbidden bash list
      (is (str/includes? tool-context "git status --porcelain"))
      (is (str/includes? tool-context "git checkout --"))
      (is (str/includes? tool-context "ROLLBACK only"))

      ;; Synthesis
      (is (str/includes? tool-context "query$llm"))

      ;; Helpers
      (is (str/includes? tool-context "update$slug"))
      (is (str/includes? tool-context "update$frontmatter"))
      (is (str/includes? tool-context "update$write"))
      (is (str/includes? tool-context "update$index-append"))
      (is (str/includes? tool-context "update$read-record"))
      (is (str/includes? tool-context "update$find"))
      (is (str/includes? tool-context "update$apply")))))

;; ============================================================================
;; Helper unit tests — update$slug
;; ============================================================================

(deftest slug-determinism-test
  (testing "same request → same slug"
    (let [q "Rename loop-guard-hook to intercept-hook in loop_guard_hook.clj"
          s1 (:slug (update/update$slug :request q))
          s2 (:slug (update/update$slug :request q))]
      (is (= s1 s2))
      (is (string? s1))))

  (testing "stopwords are dropped"
    (let [s (:slug (update/update$slug :request "Rename the foo to bar"))]
      ;; "the" stopword dropped → "rename-foo-bar"
      (is (= "rename-foo-bar" s))))

  (testing "kebab-case normalization"
    (let [s (:slug (update/update$slug :request "Add docstring to defn quux!"))]
      (is (= "add-docstring-defn-quux" s))))

  (testing "60-char default cap"
    (let [long-q (str/join " " (repeat 30 "supercalifragilistic"))
          s      (:slug (update/update$slug :request long-q))]
      (is (<= (count s) 60))))

  (testing "max-chars override"
    (let [s (:slug (update/update$slug :request "rename foo bar baz"
                                       :max-chars 7))]
      (is (<= (count s) 7))))

  (testing "blank/empty request → fallback slug"
    (is (= "edit" (:slug (update/update$slug :request ""))))
    (is (= "edit" (:slug (update/update$slug :request "   "))))
    (is (= "edit" (:slug (update/update$slug :request "the is a")))))

  (testing "validation"
    (is (contains? (update/update$slug :request 123) :error))
    (is (contains? (update/update$slug :request "x" :max-chars 0) :error))))

;; ============================================================================
;; Helper unit tests — update$frontmatter (round-trip)
;; ============================================================================

(deftest frontmatter-round-trip-test
  (testing "frontmatter built by update$frontmatter parses back via update$read-record"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [request "Rename loop-guard-hook to intercept-hook"
              slug    "rename-loop-guard-hook-intercept-hook"
              {:keys [frontmatter]}
              (update/update$frontmatter
               :request   request
               :slug      slug
               :mode      :pattern
               :target    "src/foo.clj"
               :pre       {:head_rev "abc123"
                           :status   "clean"
                           :recent   ["abc123 init" "def456 hook"]
                           :match_count 7}
               :apply     {:pattern "loop-guard-hook"
                           :replacement "intercept-hook"
                           :regex false :all true :replaced 7}
               :verify    {:diff_match true
                           :old_count_after 0
                           :new_count_after 7
                           :lint "clj-kondo:0"
                           :tests "skipped"}
               :rollback  "git checkout -- src/foo.clj"
               :ok        true)
              {:keys [path]}
              (update/update$write :slug slug
                                   :content (str frontmatter "\n# body\n")
                                   :base-dir tmp-dir)
              parsed (update/update$read-record :path path :base-dir tmp-dir)]

          (testing "scalar keys"
            (is (= slug    (:slug parsed)))
            (is (= request (:request parsed)))
            (is (= "update-agent" (:agent parsed)))
            (is (= "pattern" (:mode parsed)))
            (is (= "src/foo.clj" (:target parsed)))
            (is (= "git checkout -- src/foo.clj" (:rollback parsed)))
            (is (true? (:ok parsed))))

          (testing "pre sub-block"
            (is (= "abc123" (get-in parsed [:pre :head_rev])))
            (is (= "clean"  (get-in parsed [:pre :status])))
            (is (= ["abc123 init" "def456 hook"]
                   (get-in parsed [:pre :recent])))
            (is (= 7 (get-in parsed [:pre :match_count]))))

          (testing "apply sub-block"
            (is (= "loop-guard-hook" (get-in parsed [:apply :pattern])))
            (is (= "intercept-hook"  (get-in parsed [:apply :replacement])))
            (is (false? (get-in parsed [:apply :regex])))
            (is (true?  (get-in parsed [:apply :all])))
            (is (= 7 (get-in parsed [:apply :replaced]))))

          (testing "verify sub-block"
            (is (true? (get-in parsed [:verify :diff_match])))
            (is (= 0 (get-in parsed [:verify :old_count_after])))
            (is (= 7 (get-in parsed [:verify :new_count_after])))
            (is (= "clj-kondo:0" (get-in parsed [:verify :lint])))
            (is (= "skipped" (get-in parsed [:verify :tests]))))

          (testing "no spurious :error"
            (is (not (contains? parsed :error)))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "validation"
    (is (contains? (update/update$frontmatter :slug "x" :mode :pattern
                                              :target "x" :rollback "x") :error))
    (is (contains? (update/update$frontmatter :request "x" :mode :pattern
                                              :target "x" :rollback "x") :error))
    (is (contains? (update/update$frontmatter :request "x" :slug "x"
                                              :target "x" :rollback "x") :error))
    (is (contains? (update/update$frontmatter :request "x" :slug "x"
                                              :mode :pattern :rollback "x") :error))
    (is (contains? (update/update$frontmatter :request "x" :slug "x"
                                              :mode :pattern :target "x") :error))))

;; ============================================================================
;; Helper unit tests — update$write collision suffix
;; ============================================================================

(deftest write-collision-suffix-test
  (testing "first write uses bare slug, repeats auto-suffix -2/-3"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [r1 (update/update$write :slug "rename-foo" :content "x1" :base-dir tmp-dir)
              r2 (update/update$write :slug "rename-foo" :content "x2" :base-dir tmp-dir)
              r3 (update/update$write :slug "rename-foo" :content "x3" :base-dir tmp-dir)]
          (is (= "rename-foo"   (:slug r1)))
          (is (= "rename-foo-2" (:slug r2)))
          (is (= "rename-foo-3" (:slug r3)))
          (is (.isFile (io/file (:path r1))))
          (is (.isFile (io/file (:path r2))))
          (is (.isFile (io/file (:path r3))))
          (is (= "x1" (slurp (io/file (:path r1))))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "different slugs do not collide"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (let [r1 (update/update$write :slug "alpha" :content "x" :base-dir tmp-dir)
              r2 (update/update$write :slug "beta"  :content "y" :base-dir tmp-dir)]
          (is (= "alpha" (:slug r1)))
          (is (= "beta"  (:slug r2))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "validation"
    (is (contains? (update/update$write :content "x") :error))
    (is (contains? (update/update$write :slug "x") :error))))

;; ============================================================================
;; Helper unit tests — update$index-append (PREPENDS newest-first)
;; ============================================================================

(deftest index-prepend-ordering-test
  (testing "INDEX.md prepends newest-first; existing content preserved verbatim"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (update/update$index-append :path "edits/file-a.md" :slug "alpha"
                                    :mode :pattern :target "src/a.clj"
                                    :ok? true :summary "first edit"
                                    :base-dir tmp-dir)
        (update/update$index-append :path "edits/file-b.md" :slug "beta"
                                    :mode :syntax :target "src/b.clj"
                                    :ok? true :summary "second edit"
                                    :base-dir tmp-dir)
        (update/update$index-append :path "edits/file-c.md" :slug "gamma"
                                    :mode :new-file :target "src/c.clj"
                                    :ok? false :summary "third (rolled back)"
                                    :base-dir tmp-dir)
        (let [content (slurp (io/file tmp-dir ".brainyard/agents/update-agent/INDEX.md"))
              lines   (->> (str/split-lines content) (remove str/blank?))]
          (is (= 3 (count lines)))
          ;; Newest first — gamma (last appended) should be first
          (is (str/includes? (nth lines 0) "gamma"))
          (is (str/includes? (nth lines 0) "new-file"))
          (is (str/includes? (nth lines 0) "❌"))
          (is (str/includes? (nth lines 1) "beta"))
          (is (str/includes? (nth lines 1) "syntax"))
          (is (str/includes? (nth lines 1) "✅"))
          (is (str/includes? (nth lines 2) "alpha"))
          (is (str/includes? (nth lines 2) "pattern")))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "validation"
    (is (contains? (update/update$index-append :slug "x" :mode :pattern
                                               :target "x") :error))
    (is (contains? (update/update$index-append :path "x" :mode :pattern
                                               :target "x") :error))
    (is (contains? (update/update$index-append :path "x" :slug "x"
                                               :target "x") :error))
    (is (contains? (update/update$index-append :path "x" :slug "x"
                                               :mode :pattern) :error))))

;; ============================================================================
;; Helper unit tests — update$find
;; ============================================================================

(deftest find-test
  (testing "find matches against slug, target, request — newest first"
    (let [tmp-dir (make-tmp-dir)]
      (try
        ;; Write three records with different slugs/targets
        (doseq [[slug target request]
                [["rename-loop-guard" "src/loop_guard.clj"   "Rename loop-guard"]
                 ["add-docstring"     "src/foo.clj"          "Add docstring to defn quux"]
                 ["create-bar"        "src/bar.clj"          "Create new file bar.clj"]]]
          (let [{:keys [frontmatter]}
                (update/update$frontmatter
                 :request  request :slug slug :mode :pattern :target target
                 :pre {:status "clean"} :apply {:replaced 1}
                 :verify {:diff_match true} :rollback "git checkout --"
                 :ok true)]
            (update/update$write :slug slug :content frontmatter :base-dir tmp-dir)
            ;; Need different timestamps for newest-first ordering — sleep 1s
            (Thread/sleep (long 1100))))
        (let [r (update/update$find :query "loop" :base-dir tmp-dir)]
          (is (= 1 (:n-matches r)))
          (is (= "rename-loop-guard" (-> r :matches first :slug))))
        (let [r (update/update$find :query "src/" :base-dir tmp-dir)]
          (is (= 3 (:n-matches r)))
          ;; Newest-first: create-bar was appended last
          (is (= "create-bar" (-> r :matches first :slug))))
        (let [r (update/update$find :query "nope-no-match" :base-dir tmp-dir)]
          (is (= 0 (:n-matches r)))
          (is (= [] (:matches r))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "validation"
    (is (contains? (update/update$find) :error))))

;; ============================================================================
;; update$apply pipeline — happy paths + refusals + rollback
;; ============================================================================

(deftest apply-pattern-all-matches-happy-path-test
  (testing "pattern mode, :all? true — happy path"
    (let [base   (make-tmp-dir)
          target "src/foo.clj"
          orig   "(ns foo) (def loop-guard-hook 1) (println loop-guard-hook)"]
      (init-repo! base target orig)
      (try
        (let [r (update/update$apply
                 :request "Rename loop-guard-hook to intercept-hook"
                 :target target :mode :pattern
                 :pattern "loop-guard-hook" :replacement "intercept-hook"
                 :all? true :base-dir base)]
          (is (true? (:ok? r)))
          (is (= 2 (:replaced r)))
          (is (= (str "git checkout -- " target) (:rollback r)))
          (is (string? (:diff r)))
          (is (str/includes? (:diff r) "intercept-hook"))
          (is (true? (get-in r [:verify :diff_match])))
          (is (= 0 (get-in r [:verify :old_count_after])))
          (is (= 2 (get-in r [:verify :new_count_after])))
          (is (.isFile (io/file (:path r))))
          ;; File actually contains the new content
          (let [after (slurp (io/file base target))]
            (is (str/includes? after "intercept-hook"))
            (is (not (str/includes? after "loop-guard-hook")))))
        (finally
          (delete-recursive (io/file base)))))))

(deftest apply-pattern-count-mismatch-test
  (testing "pattern mode, :all? false but multiple matches — P4 refusal"
    (let [base   (make-tmp-dir)
          target "src/foo.clj"
          orig   "(ns foo) (def x 1) (def y 2) (def z 1)"]
      (init-repo! base target orig)
      (try
        (let [r (update/update$apply
                 :request "Replace single 1"
                 :target target :mode :pattern
                 :pattern "1" :replacement "9" :all? false   ; expects 1, file has 2
                 :base-dir base)]
          (is (false? (:ok? r)))
          (is (= :probe (:stage r)))
          (is (str/includes? (:error r) "match count mismatch"))
          ;; File untouched
          (is (= orig (slurp (io/file base target)))))
        (finally
          (delete-recursive (io/file base)))))))

(deftest apply-dirty-file-refusal-test
  (testing "dirty target — :dirty-ok? false → refused"
    (let [base   (make-tmp-dir)
          target "src/foo.clj"]
      (init-repo! base target "(def hello 1)")
      (spit (io/file base target) "(def hello 1)\n;; dirty\n")
      (try
        (let [r (update/update$apply
                 :request "edit hello"
                 :target target :mode :pattern
                 :pattern "hello" :replacement "world" :all? false
                 :base-dir base)]
          (is (false? (:ok? r)))
          (is (= :probe (:stage r)))
          (is (str/includes? (:error r) "Refusing to edit dirty file"))
          ;; File still has dirty content untouched
          (is (str/includes? (slurp (io/file base target)) ";; dirty")))
        (finally
          (delete-recursive (io/file base))))))

  (testing "dirty target — :dirty-ok? true → proceeds"
    (let [base   (make-tmp-dir)
          target "src/foo.clj"]
      (init-repo! base target "(def hello 1)")
      (spit (io/file base target) "(def hello 1)\n;; dirty\n")
      (try
        (let [r (update/update$apply
                 :request "rename hello"
                 :target target :mode :pattern
                 :pattern "hello" :replacement "world" :all? false
                 :dirty-ok? true :base-dir base)
              parsed (update/update$read-record :path (:path r) :base-dir base)]
          ;; Pre-flight gate lifted — apply ran and a record was persisted.
          ;; Verify the record's pre.status reflects the dirty-ok bypass.
          (is (some? (:path r)))
          (is (.isFile (io/file (:path r))))
          (is (= "dirty-ok" (get-in parsed [:pre :status]))))
        (finally
          (delete-recursive (io/file base))))))

  (testing "dirty-ok edit succeeds — V1 is transaction-scoped, prior dirty state preserved alongside the edit"
    ;; Regression: V1 (diff_match) used to compare working-tree-vs-HEAD diff
    ;; to update-file's reported diff. For a dirty-going-in file the disk
    ;; diff included prior uncommitted changes that update-file never saw,
    ;; so V1 always failed. V1 now compares the pre-APPLY bytes against the
    ;; post-APPLY bytes — purely transaction-scoped — so a correct edit on
    ;; a dirty file passes.
    (let [base       (make-tmp-dir)
          target     "src/foo.clj"
          head-bytes "(def hello 1)"
          ;; Prior uncommitted change — simulates a previously-landed edit
          ;; the operator hasn't committed yet. This transaction must not
          ;; clobber it.
          dirty-bytes "(def hello 1)\n(def prior-uncommitted :keep-me)\n"
          expected-after "(def world 1)\n(def prior-uncommitted :keep-me)\n"]
      (init-repo! base target head-bytes)
      (spit (io/file base target) dirty-bytes)
      (try
        (let [r (update/update$apply
                 :request "swap hello → world"
                 :target target :mode :pattern
                 :pattern "hello" :replacement "world" :all? false
                 :dirty-ok? true :base-dir base)
              after (slurp (io/file base target))]
          (is (true? (:ok? r))
              "V1 is transaction-scoped now — correct dirty-ok edit must pass verify")
          (is (= 1 (:replaced r)))
          (is (= expected-after after)
              "edit landed AND prior uncommitted change survived intact"))
        (finally
          (delete-recursive (io/file base))))))

  (testing "dirty-ok edit emits a `cp` rollback pointing at a backup that round-trips the pre-APPLY bytes"
    ;; For clean-going-in edits the :rollback hint is the familiar
    ;; `git checkout -- <target>`. For :dirty-ok? edits git-checkout would
    ;; discard the prior uncommitted state — so the pipeline writes a backup
    ;; under .brainyard/agents/update-agent/backups/ and emits `cp <backup> <target>`
    ;; as the manual-rollback hint. Executing that command must restore the
    ;; pre-APPLY bytes (head + prior dirty change), not HEAD.
    (let [base       (make-tmp-dir)
          target     "src/foo.clj"
          head-bytes "(def hello 1)"
          dirty-bytes "(def hello 1)\n(def prior-uncommitted :keep-me)\n"
          expected-after "(def world 1)\n(def prior-uncommitted :keep-me)\n"]
      (init-repo! base target head-bytes)
      (spit (io/file base target) dirty-bytes)
      (try
        (let [r (update/update$apply
                 :request "swap hello → world (dirty-ok)"
                 :target target :mode :pattern
                 :pattern "hello" :replacement "world" :all? false
                 :dirty-ok? true :base-dir base)
              rb (:rollback r)
              backup-rel (second (re-find #"^cp -- '([^']+)'" (or rb "")))]
          (is (true? (:ok? r)))
          (is (= expected-after (slurp (io/file base target)))
              "edit landed alongside prior dirty content")
          (is (some? rb))
          (is (str/starts-with? rb "cp -- '.brainyard/agents/update-agent/backups/")
              ":rollback should be a `cp` form pointing at a backup")
          (is (some? backup-rel))
          (is (.isFile (io/file base backup-rel))
              "backup file must exist on disk")
          (is (= dirty-bytes (slurp (io/file base backup-rel)))
              "backup contents must equal pre-APPLY bytes")
          ;; Execute the operator's manual rollback and verify it restores
          ;; the pre-APPLY state (head + prior dirty), not HEAD.
          (sh/sh "sh" "-c" rb :dir base)
          (is (= dirty-bytes (slurp (io/file base target)))
              "running the :rollback hint must restore pre-APPLY bytes verbatim"))
        (finally
          (delete-recursive (io/file base))))))

  (testing "verify failure on dirty file — rollback restores pre-APPLY bytes, prior dirty state survives"
    ;; Force a verify failure via :run-tests? false + a contrived V3 fault.
    ;; The cleanest deterministic trigger: pattern matches once, replacement
    ;; happens to contain a substring that makes new-count-after greater
    ;; than what V3 expects — wait, V3 only requires >= 1. Instead use
    ;; identical-replacement-but-keeping-pattern: pattern "alpha", replacement
    ;; "alpha-renamed". After replace, "alpha" still appears (inside
    ;; "alpha-renamed") so old_count_after = 1, not 0 → V2 fails. The
    ;; transaction must roll back to the pre-APPLY bytes (which include the
    ;; prior dirty state), NOT to HEAD.
    (let [base       (make-tmp-dir)
          target     "src/foo.clj"
          head-bytes "(def alpha 1) (def alpha 2)"
          dirty-bytes "(def alpha 1) (def alpha 2)\n(def prior-uncommitted :keep-me)\n"]
      (init-repo! base target head-bytes)
      (spit (io/file base target) dirty-bytes)
      (try
        (let [r (update/update$apply
                 :request "rename alpha→alpha-renamed (all)"
                 :target target :mode :pattern
                 :pattern "alpha" :replacement "alpha-renamed" :all? true
                 :dirty-ok? true :base-dir base)
              after (slurp (io/file base target))]
          (is (false? (:ok? r))
              "V2 should fail because replacement still contains the pattern")
          (is (true? (:rolled-back r)))
          (is (= dirty-bytes after)
              "rollback MUST restore pre-APPLY bytes (prior dirty + head), NOT HEAD"))
        (finally
          (delete-recursive (io/file base)))))))

;; :stash mode caveat — `git stash pop` refuses to merge when the working
;; tree has local changes to the same file, which APPLY has just produced.
;; The happy-path test below therefore asserts the (correct, transparent)
;; current behavior: ok?=true, edit applied, but the stash is left in place
;; for the operator to recover manually. The rollback path is unaffected —
;; rollback-tracked clears the edit before pop runs, so pop succeeds and
;; the dirty content is fully restored.
(deftest apply-dirty-ok-stash-test
  (testing ":dirty-ok? :stash happy path — APPLY runs on post-stash clean baseline; pop conflicts with the edit, so dirty is surfaced via :stash-pop-failed?"
    (let [base       (make-tmp-dir)
          target     "src/foo.clj"
          head-bytes "(def hello 1)\n(def goodbye 2)\n"
          dirty-bytes "(def hello 1)\n(def goodbye 2)\n\n;; dirty-marker\n"]
      (init-repo! base target head-bytes)
      (spit (io/file base target) dirty-bytes)
      (try
        (let [r (update/update$apply
                 :request "swap hello → world (stash)"
                 :target target :mode :pattern
                 :pattern "hello" :replacement "world" :all? false
                 :dirty-ok? :stash :base-dir base)
              parsed (update/update$read-record :path (:path r) :base-dir base)
              after (slurp (io/file base target))
              stash-list (:out (sh/sh "git" "stash" "list" :dir base))]
          (is (true? (:ok? r))
              ":stash mode runs APPLY on clean post-stash content; V1 is satisfied")
          (is (= 1 (:replaced r)))
          (is (= "stashed" (get-in parsed [:pre :status]))
              "record's pre.status flags the :stash mode for audit")
          (is (= "(def world 1)\n(def goodbye 2)\n" after)
              "file has the edit applied on the clean baseline")
          (is (true? (:stash-pop-failed? r))
              "pop conflicts surfaced via :stash-pop-failed? — operator knows to recover the stash manually")
          (is (str/includes? stash-list "WIP on")
              "the dirty content is preserved in git stash list for manual recovery"))
        (finally
          (delete-recursive (io/file base))))))

  (testing ":dirty-ok? :stash + verify failure — rollback clears the edit before pop, so pop succeeds and dirty content is fully restored"
    (let [base       (make-tmp-dir)
          target     "src/foo.clj"
          head-bytes "(def alpha 1)\n(def alpha 2)\n"
          dirty-bytes "(def alpha 1)\n(def alpha 2)\n\n;; dirty-marker\n"]
      (init-repo! base target head-bytes)
      (spit (io/file base target) dirty-bytes)
      (try
        (let [r (update/update$apply
                 ;; pattern replacement that still contains the pattern →
                 ;; old_count_after > 0 → V2 fails → rollback fires
                 :request "force V2 fail (alpha → alpha-renamed)"
                 :target target :mode :pattern
                 :pattern "alpha" :replacement "alpha-renamed" :all? true
                 :dirty-ok? :stash :base-dir base)
              after (slurp (io/file base target))]
          (is (false? (:ok? r)))
          (is (true? (:rolled-back r)))
          ;; rollback-tracked spits the post-stash `original` (HEAD content)
          ;; back. Then maybe-pop-stash re-applies the dirty diff. Final
          ;; state == pre-update$apply dirty state.
          (is (= dirty-bytes after)
              "after stash-rollback the file MUST equal the pre-update$apply dirty state"))
        (finally
          (delete-recursive (io/file base)))))))

(deftest apply-new-file-test
  (testing "new-file mode — happy path"
    (let [base   (make-tmp-dir)
          existing "src/foo.clj"]
      (init-repo! base existing "(ns foo)")
      (try
        (let [r (update/update$apply
                 :request "Create src/bar.clj with skeleton"
                 :target "src/bar.clj"
                 :mode :new-file
                 :content "(ns bar)\n(def hello :world)\n"
                 :base-dir base)]
          (is (true? (:ok? r)))
          (is (= "rm -- src/bar.clj" (:rollback r)))
          (is (.isFile (io/file base "src/bar.clj")))
          (is (= "(ns bar)\n(def hello :world)\n"
                 (slurp (io/file base "src/bar.clj")))))
        (finally
          (delete-recursive (io/file base))))))

  (testing "new-file mode refuses existing target"
    (let [base   (make-tmp-dir)
          target "src/foo.clj"]
      (init-repo! base target "(ns foo)")
      (try
        (let [r (update/update$apply
                 :request "Create foo.clj"
                 :target target :mode :new-file
                 :content "(ns foo)" :base-dir base)]
          (is (false? (:ok? r)))
          (is (= :probe (:stage r))))
        (finally
          (delete-recursive (io/file base)))))))

(deftest apply-out-of-tree-refusal-test
  (testing "target with .. is refused"
    (let [base   (make-tmp-dir)
          existing "src/foo.clj"]
      (init-repo! base existing "(ns foo)")
      (try
        (let [r (update/update$apply
                 :request "edit"
                 :target "../escape.clj" :mode :new-file
                 :content "x" :base-dir base)]
          (is (false? (:ok? r))))
        (finally
          (delete-recursive (io/file base))))))

  (testing "target inside .git/ is refused"
    (let [base   (make-tmp-dir)
          existing "src/foo.clj"]
      (init-repo! base existing "(ns foo)")
      (try
        (let [r (update/update$apply
                 :request "edit"
                 :target ".git/HEAD" :mode :pattern
                 :pattern "ref" :replacement "new"
                 :base-dir base)]
          (is (false? (:ok? r))))
        (finally
          (delete-recursive (io/file base)))))))

(deftest apply-validation-test
  (testing "validation refuses bad shapes"
    (is (contains? (update/update$apply :target "x" :mode :pattern
                                        :pattern "p" :replacement "r") :error))
    (is (contains? (update/update$apply :request "x" :mode :pattern
                                        :pattern "p" :replacement "r") :error))
    (is (contains? (update/update$apply :request "x" :target "x"
                                        :mode :unknown) :error))
    (is (contains? (update/update$apply :request "x" :target "x"
                                        :mode :pattern) :error))
    (is (contains? (update/update$apply :request "x" :target "x"
                                        :mode :new-file) :error))))

(deftest apply-record-shape-test
  (testing "the persisted record round-trips via update$read-record"
    (let [base   (make-tmp-dir)
          target "src/foo.clj"]
      (init-repo! base target "(def hello 1)")
      (try
        (let [r (update/update$apply
                 :request "rename hello to world"
                 :target target :mode :pattern
                 :pattern "hello" :replacement "world" :all? true
                 :base-dir base)
              parsed (update/update$read-record :path (:path r) :base-dir base)]
          (is (true? (:ok? r)))
          (is (= "update-agent" (:agent parsed)))
          (is (= "pattern" (:mode parsed)))
          (is (= target (:target parsed)))
          (is (= "git checkout -- src/foo.clj" (:rollback parsed)))
          (is (true? (:ok parsed)))
          (is (= "hello" (get-in parsed [:apply :pattern])))
          (is (= "world" (get-in parsed [:apply :replacement])))
          (is (= 1 (get-in parsed [:apply :replaced])))
          (is (true? (get-in parsed [:verify :diff_match]))))
        (finally
          (delete-recursive (io/file base)))))))
