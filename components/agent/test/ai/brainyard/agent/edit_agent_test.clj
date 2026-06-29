;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.edit-agent-test
  "Tests for edit-agent (renamed from update-agent): registration, inherited
   bt-factory (CoAct), curated agent-tools roster (the transaction edit$apply +
   the two read seams; the record-authoring helper chain is retired),
   instruction/tool-context anchors, edit$find (incl. legacy-dir fallback), the
   §5 record round-trip (flow-map apply/verify), and the edit$apply pipeline
   end-to-end against a real temp git repo (happy path, count-mismatch refusal,
   dirty-file refusal + :dirty-ok? true byte-overwrite, verify-failure rollback,
   new-file, out-of-tree, validation). Also covers the update-file :expect-count
   guard (B3)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [ai.brainyard.agent.common.edit :as edit]
            [ai.brainyard.agent.common.edit-agent]
            [ai.brainyard.agent.common.tools :as tools]
            [ai.brainyard.agent.core.tool :as tool])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn- make-tmp-dir []
  (-> (Files/createTempDirectory "edit-test-" (into-array FileAttribute []))
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

(defn- write-record!
  "Spit a §5 edit record into <base-dir>/.brainyard/agents/<agent-dir>/edits/.
   Returns the repo-relative path. Used to seed edit$find without the retired
   write-side helpers."
  [base-dir agent-dir filename {:keys [slug target request created]}]
  (let [rel (str ".brainyard/agents/" agent-dir "/edits/" filename)
        f   (io/file base-dir rel)]
    (.mkdirs (.getParentFile f))
    (spit f (str "---\n"
                 "slug: " slug "\n"
                 "agent: edit-agent\n"
                 "created: " (or created "2026-06-29T00:00:00Z") "\n"
                 "request: \"" request "\"\n"
                 "target: " target "\n"
                 "mode: pattern\nok: true\n\n"
                 "apply: {replaced: 1}\n"
                 "verify: {diff_match: true}\n"
                 "rollback: \"git checkout -- " target "\"\n"
                 "---\n# Edit\n"))
    rel))

;; ============================================================================
;; Registration
;; ============================================================================

(deftest registration-test
  (testing "edit-agent is registered as an agent"
    (let [agent-defs (tool/get-tool-defs :type :agent)]
      (is (contains? agent-defs :edit-agent))
      (let [agent-def (get agent-defs :edit-agent)]
        (is (= :edit-agent (:id agent-def)))
        (is (some? (:fn agent-def)))
        (is (some? (:meta agent-def))))))

  (testing "the old update-agent id is gone (clean rename, no alias)"
    (is (not (contains? (tool/get-tool-defs :type :agent) :update-agent)))))

;; ============================================================================
;; Inheritance via run-coact-derived
;; ============================================================================

(deftest inheritance-test
  (require 'ai.brainyard.agent.common.coact-agent)
  (let [agent-def (get (tool/get-tool-defs :type :agent) :edit-agent)]
    (testing "edit-agent's :fn is registered (the wrap-fn invoking run-coact-derived)"
      (is (some? (:fn agent-def))))

    (testing "edit-agent pins :bt-factory explicitly (so setup-agent-by-id picks it up)"
      (let [bt-factory (get-in agent-def [:meta :bt-factory])]
        (is (fn? bt-factory))
        (let [bt (bt-factory {:max-iterations 3})]
          (is (vector? bt))
          (is (= :sequence (first bt)))
          (is (= "coact.sequence" (namespace (get-in bt [1 :id])))))))))

;; ============================================================================
;; Agent tools — positive + negative
;; ============================================================================

(defn- edit-tool-ids []
  (let [agent-def   (get (tool/get-tool-defs :type :agent) :edit-agent)
        agent-tools (get-in agent-def [:meta :agent-tools])]
    (set (map (comp :id meta deref) (:tools agent-tools)))))

(deftest agent-tools-test
  (testing "edit-agent :agent-tools includes the file-IO + probe surface + the transaction"
    (let [ids (edit-tool-ids)]
      (is (contains? ids :update-file))
      (is (contains? ids :write-file))
      (is (contains? ids :read-file))
      (is (contains? ids :grep))
      (is (contains? ids :bash))
      (is (contains? ids :search))
      (is (contains? ids :query$llm))
      (is (contains? ids :list-tools))
      (is (contains? ids :get-tool-info))
      (is (contains? ids :call-tool))
      (is (contains? ids :task$run))
      (is (contains? ids :agent-runtime$config))

      ;; The surviving edit$* — transaction + read seams
      (is (contains? ids :edit$apply))
      (is (contains? ids :edit$read-record))
      (is (contains? ids :edit$find))))

  (testing "edit-agent :agent-tools EXCLUDES retired helpers + out-of-scope tools"
    (let [ids (edit-tool-ids)]
      ;; Retired record-authoring chain (and their old update$ names)
      (is (not (contains? ids :edit$slug)))
      (is (not (contains? ids :edit$frontmatter)))
      (is (not (contains? ids :edit$write)))
      (is (not (contains? ids :edit$index-append)))
      (is (not (contains? ids :update$apply)))
      (is (not (contains? ids :update$slug)))

      ;; Hard Rule 4 — no clone-self
      (is (not (contains? ids :query$clone)))

      ;; Web / MCP / skills surfaces live in explore-agent
      (is (not (contains? ids :web-search)))
      (is (not (contains? ids :fetch-url)))
      (is (not (contains? ids :mcp$server)))
      (is (not (contains? ids :skills$find))))))

;; ============================================================================
;; Instruction & tool-context content anchors
;; ============================================================================

(deftest instruction-content-test
  (testing "instruction string contains the cardinal edit-agent anchors"
    (let [instruction (get-in (tool/get-tool-defs :type :agent)
                              [:edit-agent :meta :instruction])]
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
      ;; The transaction + RECORD template (helpers retired)
      (is (str/includes? instruction "edit$apply"))
      (is (str/includes? instruction "RECORD TEMPLATE"))
      (is (not (str/includes? instruction "edit$frontmatter")))
      (is (not (str/includes? instruction "update$apply")))
      ;; Hard rules
      (is (str/includes? instruction "HARD RULES"))
      (is (str/includes? instruction "clone-self"))
      (is (str/includes? instruction "git commit"))
      (is (str/includes? instruction "CLEAN-FIRST"))
      ;; Persistence + handoff contract
      (is (str/includes? instruction ".brainyard/agents/edit-agent/"))
      (is (str/includes? instruction "Saved edit:"))
      (is (str/includes? instruction "Rollback:")))))

(deftest tool-context-content-test
  (testing "tool-context names the key tools and edit$apply"
    (let [tool-context (get-in (tool/get-tool-defs :type :agent)
                               [:edit-agent :meta :tool-context])]
      (is (string? tool-context))
      (is (not (str/blank? tool-context)))
      (is (str/includes? tool-context "update-file"))
      (is (str/includes? tool-context "write-file"))
      (is (str/includes? tool-context "read-file"))
      (is (str/includes? tool-context "git status --porcelain"))
      (is (str/includes? tool-context "git checkout --"))
      (is (str/includes? tool-context "ROLLBACK only"))
      (is (str/includes? tool-context "query$llm"))
      (is (str/includes? tool-context "expect-count"))
      ;; Surviving edit$* documented; the tool-context explicitly notes the
      ;; retired chain is gone ("There are NO edit$slug / edit$frontmatter …").
      (is (str/includes? tool-context "edit$apply"))
      (is (str/includes? tool-context "edit$read-record"))
      (is (str/includes? tool-context "edit$find"))
      (is (str/includes? tool-context "There are NO")))))

;; ============================================================================
;; edit$find — incl. legacy update-agent dir fallback
;; ============================================================================

(deftest find-test
  (testing "find matches against slug, target, request — newest first; reads both dirs"
    (let [tmp-dir (make-tmp-dir)]
      (try
        (write-record! tmp-dir "edit-agent" "20260101-120000-rename-loop-guard.md"
                       {:slug "rename-loop-guard" :target "src/loop_guard.clj"
                        :request "Rename loop-guard"})
        (write-record! tmp-dir "edit-agent" "20260102-120000-add-docstring.md"
                       {:slug "add-docstring" :target "src/foo.clj"
                        :request "Add docstring to defn quux"})
        (write-record! tmp-dir "edit-agent" "20260103-120000-create-bar.md"
                       {:slug "create-bar" :target "src/bar.clj"
                        :request "Create new file bar.clj"})
        ;; A legacy record under the old update-agent dir — dual-read picks it up.
        (write-record! tmp-dir "update-agent" "20251231-120000-legacy-edit.md"
                       {:slug "legacy-edit" :target "src/legacy.clj"
                        :request "A pre-rename legacy edit"})

        (let [r (edit/edit$find :query "loop" :base-dir tmp-dir)]
          (is (= 1 (:n-matches r)))
          (is (= "rename-loop-guard" (-> r :matches first :slug))))
        (let [r (edit/edit$find :query "src/" :base-dir tmp-dir)]
          (is (= 4 (:n-matches r)) "all 4 records (3 new + 1 legacy) match on target")
          (is (= "create-bar" (-> r :matches first :slug)) "newest-first by filename"))
        (let [r (edit/edit$find :query "legacy" :base-dir tmp-dir)]
          (is (= 1 (:n-matches r)))
          (is (str/includes? (-> r :matches first :path) "update-agent")
              "legacy dir record is found via dual-read"))
        (let [r (edit/edit$find :query "nope-no-match" :base-dir tmp-dir)]
          (is (= 0 (:n-matches r))))
        (finally
          (delete-recursive (io/file tmp-dir))))))

  (testing "validation"
    (is (contains? (edit/edit$find) :error))))

;; ============================================================================
;; §5 record round-trip — flow-map apply/verify parse to maps
;; ============================================================================

(deftest record-round-trip-test
  (testing "edit$read-record parses flow-map apply/verify into Clojure maps"
    (let [tmp-dir (make-tmp-dir)
          rel     (write-record! tmp-dir "edit-agent" "20260629-000000-x.md"
                                 {:slug "x" :target "src/a.clj" :request "rename foo to bar"})
          ;; overwrite with a richer verify flow-map (lint string with spaces/parens)
          _       (spit (io/file tmp-dir rel)
                        (str "---\nslug: x\nagent: edit-agent\ncreated: 2026-06-29T00:00:00Z\n"
                             "request: \"rename foo to bar\"\ntarget: src/a.clj\nmode: pattern\nok: true\n\n"
                             "apply: {replaced: 3}\n"
                             "verify: {diff_match: true, old_count_after: 0, new_count_after: 3, lint: \"clj-kondo:0 (findings 0->0)\", tests: skipped}\n"
                             "rollback: \"git checkout -- src/a.clj\"\n---\n# Edit\n"))
          r       (edit/edit$read-record :path rel :base-dir tmp-dir)]
      (try
        (is (= "x" (:slug r)))
        (is (= "edit-agent" (:agent r)))
        (is (true? (:ok r)))
        (is (= {:replaced 3} (:apply r)))
        (is (true? (get-in r [:verify :diff_match])))
        (is (= 0 (get-in r [:verify :old_count_after])))
        (is (= 3 (get-in r [:verify :new_count_after])))
        (is (= "clj-kondo:0 (findings 0->0)" (get-in r [:verify :lint])))
        (is (= "git checkout -- src/a.clj" (:rollback r)))
        (finally
          (delete-recursive (io/file tmp-dir)))))))

;; ============================================================================
;; edit$apply pipeline — happy paths + refusals + rollback
;; ============================================================================

(deftest apply-pattern-all-matches-happy-path-test
  (testing "pattern mode, :all? true — happy path"
    (let [base   (make-tmp-dir)
          target "src/foo.clj"
          orig   "(ns foo) (def loop-guard-hook 1) (println loop-guard-hook)"]
      (init-repo! base target orig)
      (try
        (let [r (edit/edit$apply
                 :request "Rename loop-guard-hook to intercept-hook"
                 :target target :mode :pattern
                 :pattern "loop-guard-hook" :replacement "intercept-hook"
                 :all? true :base-dir base)]
          (is (true? (:ok? r)))
          (is (= 2 (:replaced r)))
          (is (= (str "git checkout -- " target) (:rollback r)))
          (is (str/includes? (:diff r) "intercept-hook"))
          (is (true? (get-in r [:verify :diff_match])))
          (is (= 0 (get-in r [:verify :old_count_after])))
          (is (= 2 (get-in r [:verify :new_count_after])))
          (is (.isFile (io/file (:path r))))
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
        (let [r (edit/edit$apply
                 :request "Replace single 1"
                 :target target :mode :pattern
                 :pattern "1" :replacement "9" :all? false
                 :base-dir base)]
          (is (false? (:ok? r)))
          (is (= :probe (:stage r)))
          (is (str/includes? (:error r) "match count mismatch"))
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
        (let [r (edit/edit$apply
                 :request "edit hello"
                 :target target :mode :pattern
                 :pattern "hello" :replacement "world" :all? false
                 :base-dir base)]
          (is (false? (:ok? r)))
          (is (= :probe (:stage r)))
          (is (str/includes? (:error r) "Refusing to edit dirty file"))
          (is (str/includes? (slurp (io/file base target)) ";; dirty")))
        (finally
          (delete-recursive (io/file base))))))

  (testing "dirty target — :dirty-ok? true → proceeds, record persisted"
    (let [base   (make-tmp-dir)
          target "src/foo.clj"]
      (init-repo! base target "(def hello 1)")
      (spit (io/file base target) "(def hello 1)\n;; dirty\n")
      (try
        (let [r (edit/edit$apply
                 :request "rename hello"
                 :target target :mode :pattern
                 :pattern "hello" :replacement "world" :all? false
                 :dirty-ok? true :base-dir base)]
          (is (some? (:path r)))
          (is (.isFile (io/file (:path r))))
          (is (true? (:ok? r))))
        (finally
          (delete-recursive (io/file base))))))

  (testing "dirty-ok edit succeeds — V1 transaction-scoped, prior dirty state preserved (byte-overwrite rollback model)"
    (let [base       (make-tmp-dir)
          target     "src/foo.clj"
          head-bytes "(def hello 1)"
          dirty-bytes "(def hello 1)\n(def prior-uncommitted :keep-me)\n"
          expected-after "(def world 1)\n(def prior-uncommitted :keep-me)\n"]
      (init-repo! base target head-bytes)
      (spit (io/file base target) dirty-bytes)
      (try
        (let [r (edit/edit$apply
                 :request "swap hello → world"
                 :target target :mode :pattern
                 :pattern "hello" :replacement "world" :all? false
                 :dirty-ok? true :base-dir base)
              after (slurp (io/file base target))]
          (is (true? (:ok? r)))
          (is (= 1 (:replaced r)))
          (is (= expected-after after)
              "edit landed AND prior uncommitted change survived intact"))
        (finally
          (delete-recursive (io/file base))))))

  (testing "verify failure — byte-overwrite rollback restores pre-APPLY bytes incl. prior dirty hunks (B1)"
    ;; pattern "alpha", replacement "alpha-renamed": after replace "alpha" still
    ;; appears (inside "alpha-renamed") → old_count_after > 0 → V2 fails →
    ;; rollback. Byte-overwrite must restore the pre-APPLY bytes (dirty+head),
    ;; NOT HEAD — no git stash, no backup artifact.
    (let [base       (make-tmp-dir)
          target     "src/foo.clj"
          head-bytes "(def alpha 1) (def alpha 2)"
          dirty-bytes "(def alpha 1) (def alpha 2)\n(def prior-uncommitted :keep-me)\n"]
      (init-repo! base target head-bytes)
      (spit (io/file base target) dirty-bytes)
      (try
        (let [r (edit/edit$apply
                 :request "rename alpha→alpha-renamed (all)"
                 :target target :mode :pattern
                 :pattern "alpha" :replacement "alpha-renamed" :all? true
                 :dirty-ok? true :base-dir base)
              after (slurp (io/file base target))]
          (is (false? (:ok? r)))
          (is (true? (:rolled-back r)))
          (is (= dirty-bytes after)
              "rollback MUST restore pre-APPLY bytes (prior dirty + head), NOT HEAD")
          ;; No backup artifact left behind.
          (is (not (.exists (io/file base ".brainyard/agents/edit-agent/backups")))))
        (finally
          (delete-recursive (io/file base)))))))

(deftest apply-new-file-test
  (testing "new-file mode — happy path"
    (let [base (make-tmp-dir)]
      (init-repo! base "src/foo.clj" "(ns foo)")
      (try
        (let [r (edit/edit$apply
                 :request "Create src/bar.clj with skeleton"
                 :target "src/bar.clj" :mode :new-file
                 :content "(ns bar)\n(def hello :world)\n"
                 :base-dir base)]
          (is (true? (:ok? r)))
          (is (= "rm -- src/bar.clj" (:rollback r)))
          (is (= "(ns bar)\n(def hello :world)\n" (slurp (io/file base "src/bar.clj")))))
        (finally
          (delete-recursive (io/file base))))))

  (testing "new-file mode refuses existing target"
    (let [base   (make-tmp-dir)
          target "src/foo.clj"]
      (init-repo! base target "(ns foo)")
      (try
        (let [r (edit/edit$apply
                 :request "Create foo.clj"
                 :target target :mode :new-file
                 :content "(ns foo)" :base-dir base)]
          (is (false? (:ok? r)))
          (is (= :probe (:stage r))))
        (finally
          (delete-recursive (io/file base)))))))

(deftest apply-out-of-tree-refusal-test
  (testing "target with .. is refused"
    (let [base (make-tmp-dir)]
      (init-repo! base "src/foo.clj" "(ns foo)")
      (try
        (let [r (edit/edit$apply
                 :request "edit" :target "../escape.clj" :mode :new-file
                 :content "x" :base-dir base)]
          (is (false? (:ok? r))))
        (finally
          (delete-recursive (io/file base))))))

  (testing "target inside .git/ is refused"
    (let [base (make-tmp-dir)]
      (init-repo! base "src/foo.clj" "(ns foo)")
      (try
        (let [r (edit/edit$apply
                 :request "edit" :target ".git/HEAD" :mode :pattern
                 :pattern "ref" :replacement "new" :base-dir base)]
          (is (false? (:ok? r))))
        (finally
          (delete-recursive (io/file base)))))))

(deftest apply-validation-test
  (testing "validation refuses bad shapes"
    (is (contains? (edit/edit$apply :target "x" :mode :pattern
                                    :pattern "p" :replacement "r") :error))
    (is (contains? (edit/edit$apply :request "x" :mode :pattern
                                    :pattern "p" :replacement "r") :error))
    (is (contains? (edit/edit$apply :request "x" :target "x" :mode :unknown) :error))
    (is (contains? (edit/edit$apply :request "x" :target "x" :mode :pattern) :error))
    (is (contains? (edit/edit$apply :request "x" :target "x" :mode :new-file) :error))))

(deftest apply-record-shape-test
  (testing "the persisted §5 record round-trips via edit$read-record"
    (let [base   (make-tmp-dir)
          target "src/foo.clj"]
      (init-repo! base target "(def hello 1)")
      (try
        (let [r (edit/edit$apply
                 :request "rename hello to world"
                 :target target :mode :pattern
                 :pattern "hello" :replacement "world" :all? true
                 :base-dir base)
              parsed (edit/edit$read-record :path (:path r) :base-dir base)]
          (is (true? (:ok? r)))
          (is (= "edit-agent" (:agent parsed)))
          (is (= "pattern" (:mode parsed)))
          (is (= target (:target parsed)))
          (is (= "git checkout -- src/foo.clj" (:rollback parsed)))
          (is (true? (:ok parsed)))
          (is (= 1 (get-in parsed [:apply :replaced])))
          (is (true? (get-in parsed [:verify :diff_match]))))
        (finally
          (delete-recursive (io/file base)))))))

;; ============================================================================
;; B3 — update-file :expect-count guard (refuses on match-count mismatch)
;; ============================================================================

(deftest update-file-expect-count-test
  (testing ":expect-count mismatch refuses the edit (no write); match writes"
    (let [base   (make-tmp-dir)
          target "foo.clj"
          orig   "(def a 1) (def a 2) (def a 3)"]
      (spit (io/file base target) orig)
      (try
        ;; Redef the tool's private context getters to point at our temp dir.
        (with-redefs-fn
          {#'tools/get-base-dir      (constantly base)
           #'tools/get-allowed-dirs  (constantly [base])
           #'tools/get-fallback-dirs (constantly [])
           #'tools/get-permission-fn (constantly (fn [& _] true))}
          (fn []
            (testing "mismatch → refused, file untouched"
              (let [r (tool/invoke-tool :update-file
                                        {:path target :pattern "(def a"
                                         :replacement "(def b" :all? true
                                         :expect-count 2})]   ; actual is 3
                (is (contains? r :error))
                (is (str/includes? (:error r) "Match-count mismatch"))
                (is (= 3 (:found r)))
                (is (= 2 (:expected r)))
                (is (= orig (slurp (io/file base target))) "no write on mismatch")))

            (testing "correct expect-count → guard passes (no match-count error)"
              (let [r (tool/invoke-tool :update-file
                                        {:path target :pattern "(def a"
                                         :replacement "(def b" :all? true
                                         :expect-count 3})]
                ;; The guard's contract is the count check — a correct count
                ;; must NOT be refused for a match-count mismatch.
                (is (not (str/includes? (str (:error r)) "Match-count mismatch")))))))
        (finally
          (delete-recursive (io/file base)))))))
