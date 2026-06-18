;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.config-helpers-test
  "Unit tests for the agent.common.config helpers (Step A surface)."
  (:require [ai.brainyard.agent.common.config :as c]
            [ai.brainyard.agent.core.tool :as tool]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; ============================================================================
;; Test fixture — a fresh temp project dir per test
;; ============================================================================

(def ^:dynamic *tmp-project* nil)

(defn- rm-rf [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf c)))
    (.delete f)))

(defn- seed-config! [project content]
  (io/make-parents (io/file project ".brainyard" "config.edn"))
  (spit (io/file project ".brainyard" "config.edn") (pr-str content)))

(defn with-tmp-project [t]
  (let [root (str "/tmp/by-config-test-" (System/nanoTime))]
    (.mkdirs (io/file root))
    (binding [*tmp-project* root]
      (try (t)
           (finally (rm-rf (io/file root)))))))

(use-fixtures :each with-tmp-project)

(def base-config
  {:version 1
   :llm {:default-provider :anthropic :default-model "opus"
         :available-providers [:anthropic]}
   :permissions {:mode :ask-each-time :allowed-dirs ["/tmp"]}
   :agent {:default-agent :coact-agent
           :config {:max-iterations 100
                    :enable-context-budget false}}
   :mcp {:servers {}}})

;; ============================================================================
;; validate-persisted (no I/O)
;; ============================================================================

(deftest validate-accepts-writable-leaves
  (is (:ok? (c/validate-persisted {:agent {:config {:max-iterations 200}}})))
  (is (:ok? (c/validate-persisted {:permissions {:mode :auto-approve}})))
  (is (:ok? (c/validate-persisted {:environment {:sandbox-mode :restricted}}))))

(deftest validate-rejects-llm-default-provider
  (let [r (c/validate-persisted {:llm {:default-provider :anthropic}})]
    (is (false? (:ok? r)))
    (is (= :allowlist-violation (:type (first (:errors r)))))
    (is (re-find #"bootstrap\$re-run-rung" (:reason (first (:errors r)))))))

(deftest validate-rejects-bootstrap-writes
  (is (false? (:ok? (c/validate-persisted {:bootstrap {:rung :a}})))))

(deftest validate-rejects-schema-violation
  (let [r (c/validate-persisted {:permissions {:mode :bogus}})]
    (is (false? (:ok? r)))
    (is (= :schema-violation (:type (first (:errors r)))))))

(deftest validate-mcp-servers-pass-through
  (is (:ok? (c/validate-persisted
             {:mcp {:servers {:linear {:transport :stdio
                                       :command "npx"
                                       :args ["-y" "@linear/mcp-server"]}}}}))))

;; ============================================================================
;; config$read
;; ============================================================================

(deftest read-all-section
  (seed-config! *tmp-project* base-config)
  (let [r (tool/invoke-tool :config$read :section :all :project-dir *tmp-project*)]
    (is (= :all (:section r)))
    (is (= :anthropic (get-in r [:persisted :llm :default-provider])))
    (is (some? (:mtime r)))
    (is (string? (:path r)))))

(deftest read-llm-section-only
  (seed-config! *tmp-project* base-config)
  (let [r (tool/invoke-tool :config$read :section :llm :project-dir *tmp-project*)]
    (is (= :llm (:section r)))
    (is (= (:llm base-config) (get-in r [:persisted :llm])))
    (is (nil? (get-in r [:persisted :permissions])))))

(deftest read-unknown-section
  (seed-config! *tmp-project* base-config)
  (let [r (tool/invoke-tool :config$read :section :nope :project-dir *tmp-project*)]
    (is (some? (:error r)))))

;; ============================================================================
;; config$diff
;; ============================================================================

(deftest diff-empty-proposed
  (seed-config! *tmp-project* base-config)
  (let [r (tool/invoke-tool :config$diff :proposed {} :project-dir *tmp-project*)]
    (is (= {} (:adds (:structural r))))
    (is (= {} (:removes (:structural r))))))

(deftest diff-change-shows-up
  (seed-config! *tmp-project* base-config)
  (let [r (tool/invoke-tool :config$diff
                            :proposed {:agent {:config {:max-iterations 200}}}
                            :project-dir *tmp-project*)]
    (is (re-find #"max-iterations" (:diff r)))
    (is (= {:agent {:config {:max-iterations 200}}} (:adds (:structural r))))))

;; ============================================================================
;; snapshot / list / revert
;; ============================================================================

(deftest snapshot-creates-file
  (seed-config! *tmp-project* base-config)
  (let [r (tool/invoke-tool :config$snapshot
                            :reason "test-snap" :project-dir *tmp-project*)]
    (is (true? (:ok? r)))
    (is (.exists (io/file (:path r))))))

(deftest list-snapshots-newest-first
  (seed-config! *tmp-project* base-config)
  (tool/invoke-tool :config$snapshot :reason "first"  :project-dir *tmp-project*)
  (Thread/sleep 1100) ; ensure a different yyyyMMdd-HHmmss tick
  (tool/invoke-tool :config$snapshot :reason "second" :project-dir *tmp-project*)
  (let [{:keys [snapshots]} (tool/invoke-tool :config$list-snapshots
                                              :project-dir *tmp-project*)]
    (is (= 2 (count snapshots)))
    (is (= "second" (:reason (first snapshots))))
    (is (= "first"  (:reason (second snapshots))))))

(deftest revert-round-trip
  (seed-config! *tmp-project* base-config)
  (tool/invoke-tool :config$snapshot :reason "baseline" :project-dir *tmp-project*)
  ;; Mutate file
  (seed-config! *tmp-project* {:version 1 :llm {:default-provider :ollama}})
  ;; Revert
  (let [r (tool/invoke-tool :config$revert :steps 1 :project-dir *tmp-project*)]
    (is (true? (:ok? r)))
    (is (some? (:pre-revert-snapshot r))))
  (let [restored (edn/read-string
                  (slurp (io/file *tmp-project* ".brainyard" "config.edn")))]
    (is (= :anthropic (get-in restored [:llm :default-provider])))))

(deftest revert-no-snapshots-errors
  (seed-config! *tmp-project* base-config)
  (let [r (tool/invoke-tool :config$revert :steps 1 :project-dir *tmp-project*)]
    (is (some? (:error r)))))

;; ============================================================================
;; Slug
;; ============================================================================

(deftest slug-drops-stopwords
  (is (= "add-linear-mcp-server"
         (:slug (tool/invoke-tool :config$slug
                                  :reason "Add the Linear MCP server"))))
  (is (= "raise-max-iterations-200"
         (:slug (tool/invoke-tool :config$slug
                                  :reason "Raise max-iterations to 200")))))

;; ============================================================================
;; Dossier writer
;; ============================================================================

(deftest frontmatter-builds
  (let [r (tool/invoke-tool :config$frontmatter
                            :slug "test-slug"
                            :question "what?"
                            :writes 1
                            :reverts 0
                            :next-steps [])]
    (is (re-find #"agent: config-agent" (:frontmatter r)))
    (is (re-find #"slug: test-slug" (:frontmatter r)))
    (is (re-find #"writes: 1" (:frontmatter r)))))

(deftest write-and-index-append
  (let [w (tool/invoke-tool :config$write
                            :slug "test-slug"
                            :content "---\nslug: test-slug\n---\n# body\n"
                            :base-dir (str *tmp-project* "/.brainyard"))
        _ (tool/invoke-tool :config$index-append
                            :path (:path w)
                            :slug "test-slug"
                            :summary "Did the thing"
                            :base-dir (str *tmp-project* "/.brainyard"))
        idx (slurp (io/file *tmp-project* ".brainyard" "agents" "config-agent" "INDEX.md"))]
    (is (true? (:ok? w)))
    (is (re-find #"test-slug" idx))
    (is (re-find #"Did the thing" idx))))

;; ============================================================================
;; config$apply (Step B)
;; ============================================================================

(deftest apply-unconfirmed-shows-diff
  (seed-config! *tmp-project* base-config)
  (let [r (tool/invoke-tool :config$apply
                            :proposed {:agent {:config {:max-iterations 200}}}
                            :reason "raise iter"
                            :project-dir *tmp-project*)]
    (is (= :unconfirmed (:stage r)))
    (is (re-find #"max-iterations" (:diff r)))
    (is (false? (:ok? r)))
    ;; File should NOT have been written
    (let [on-disk (edn/read-string
                   (slurp (io/file *tmp-project* ".brainyard" "config.edn")))]
      (is (= 100 (get-in on-disk [:agent :config :max-iterations]))))))

(deftest apply-confirmed-writes-and-snapshots
  (seed-config! *tmp-project* base-config)
  (let [r (tool/invoke-tool :config$apply
                            :proposed {:agent {:config {:max-iterations 200}}}
                            :reason "raise iter"
                            :confirm? true
                            :project-dir *tmp-project*)]
    (is (true? (:ok? r)))
    (is (some? (:snapshot-path r)))
    (is (.exists (io/file (:snapshot-path r))))
    (is (some? (:dossier-path r)))
    (is (.exists (io/file (:dossier-path r))))
    (let [on-disk (edn/read-string
                   (slurp (io/file *tmp-project* ".brainyard" "config.edn")))]
      (is (= 200 (get-in on-disk [:agent :config :max-iterations])))
      (is (string? (:updated-at on-disk))))))

(deftest apply-auto-skips-confirm
  (seed-config! *tmp-project* base-config)
  (let [r (tool/invoke-tool :config$apply
                            :proposed {:agent {:config {:max-iterations 200}}}
                            :reason "raise iter"
                            :auto? true
                            :project-dir *tmp-project*)]
    (is (true? (:ok? r)))))

(deftest apply-allowlist-violation
  (seed-config! *tmp-project* base-config)
  (let [r (tool/invoke-tool :config$apply
                            :proposed {:llm {:default-provider :ollama}}
                            :reason "switch"
                            :confirm? true
                            :project-dir *tmp-project*)]
    (is (false? (:ok? r)))
    (is (= :validate (:stage r)))
    (is (re-find #"bootstrap\$re-run-rung" (:hint r)))
    ;; File untouched
    (let [on-disk (edn/read-string
                   (slurp (io/file *tmp-project* ".brainyard" "config.edn")))]
      (is (= :anthropic (get-in on-disk [:llm :default-provider]))))))

(deftest apply-schema-violation
  (seed-config! *tmp-project* base-config)
  (let [r (tool/invoke-tool :config$apply
                            :proposed {:permissions {:mode :bogus}}
                            :reason "test"
                            :confirm? true
                            :project-dir *tmp-project*)]
    (is (false? (:ok? r)))
    (is (= :validate (:stage r)))))

(deftest apply-mtime-conflict
  (seed-config! *tmp-project* base-config)
  (let [stale-mtime 1
        r (tool/invoke-tool :config$apply
                            :proposed {:agent {:config {:max-iterations 200}}}
                            :reason "raise iter"
                            :confirm? true
                            :expected-mtime stale-mtime
                            :project-dir *tmp-project*)]
    (is (= :mtime-conflict (:stage r)))
    ;; File untouched
    (let [on-disk (edn/read-string
                   (slurp (io/file *tmp-project* ".brainyard" "config.edn")))]
      (is (= 100 (get-in on-disk [:agent :config :max-iterations]))))))

(deftest write-edn-config-falls-back-to-user-dir
  ;; Regression: running `bb tui config` outside a git repo without
  ;; BY_PROJECT_DIR used to write to "/config.edn" (read-only fs)
  ;; because (project-config-dir dirs) returned nil but the write path
  ;; was built from nil. Now it falls back to user-config-dir.
  (let [user-dir (str *tmp-project* "/userdir")
        _ (.mkdirs (java.io.File. user-dir))
        dirs {:user-dir user-dir :project-dir nil :working-dir user-dir}
        path (ai.brainyard.agent.core.config/write-edn-config!
              dirs {:hello :world})]
    (is (= (str user-dir "/.brainyard/config.edn") path))
    (is (.exists (java.io.File. path)))))

(deftest apply-mcp-server-add-passes
  (seed-config! *tmp-project* base-config)
  (let [r (tool/invoke-tool :config$apply
                            :proposed {:mcp {:servers
                                             {:linear {:transport :stdio
                                                       :command "npx"}}}}
                            :reason "add-linear-mcp"
                            :confirm? true
                            :project-dir *tmp-project*)]
    (is (true? (:ok? r)))
    (let [on-disk (edn/read-string
                   (slurp (io/file *tmp-project* ".brainyard" "config.edn")))]
      (is (= :stdio (get-in on-disk [:mcp :servers :linear :transport]))))))

;; ============================================================================
;; env-detect$rescan + bootstrap$re-run-rung soft-dep behaviour (Step C)
;;
;; These commands resolve their substrate at call time. When the agent
;; component is tested standalone (without env-detect / agent-tui on
;; classpath), they should return an :error string rather than throw.
;; In the agent-tui-app project where everything is wired together, they
;; succeed — that path is tested via the project-level test suite.
;; ============================================================================

(deftest rescan-returns-error-or-detection
  (let [r (tool/invoke-tool :env-detect$rescan)]
    ;; Either substrate is present (project-level run) or absent (standalone).
    ;; In both cases we get a structured result, not a thrown exception.
    (is (or (some? (:detection r))
            (some? (:error r))))))

(deftest re-run-rung-validates-rung
  (let [r (tool/invoke-tool :bootstrap$re-run-rung :rung :g)]
    (is (some? (:error r))))
  (let [r (tool/invoke-tool :bootstrap$re-run-rung)]
    (is (some? (:error r))))
  (let [r (tool/invoke-tool :bootstrap$re-run-rung :rung "a")]
    ;; string instead of keyword
    (is (some? (:error r)))))

;; ============================================================================
;; Scope plumbing — :project | :user | :auto
;;
;; Tests use a sandboxed user-dir under *tmp-project*/userdir so the real
;; ~/.brainyard/ is never touched. Because the agent's resolve-dirs reads
;; user.home from the JVM, we shell out through resolve-scope/read-edn-config
;; directly with a synthetic dirs map rather than relying on the tool
;; invocation (the tools use the live System/getProperty).
;; ============================================================================

(deftest resolve-scope-project-and-user
  (let [user-dir (str *tmp-project* "/userdir")
        _ (.mkdirs (io/file user-dir ".brainyard"))
        dirs {:user-dir user-dir :project-dir *tmp-project* :working-dir *tmp-project*}]
    (testing ":project resolves to project-config-dir"
      (let [r (ai.brainyard.agent.core.config/resolve-scope dirs :project)]
        (is (= :project (:scope r)))
        (is (= :project (:requested r)))
        (is (= (str *tmp-project* "/.brainyard") (:config-dir r)))))
    (testing ":user resolves to user-config-dir"
      (let [r (ai.brainyard.agent.core.config/resolve-scope dirs :user)]
        (is (= :user (:scope r)))
        (is (= :user (:requested r)))
        (is (= (str user-dir "/.brainyard") (:config-dir r)))))
    (testing ":auto with no project file falls back to user"
      (let [r (ai.brainyard.agent.core.config/resolve-scope dirs :auto)]
        (is (= :user (:scope r)))
        (is (= :auto (:requested r)))))
    (testing ":auto with project file present resolves to project"
      (seed-config! *tmp-project* base-config)
      (let [r (ai.brainyard.agent.core.config/resolve-scope dirs :auto)]
        (is (= :project (:scope r)))
        (is (= :auto (:requested r)))))))

(deftest resolve-scope-project-nil-when-no-project-dir
  (let [user-dir (str *tmp-project* "/userdir")
        _ (.mkdirs (io/file user-dir ".brainyard"))
        dirs {:user-dir user-dir :project-dir nil :working-dir user-dir}]
    (is (nil? (ai.brainyard.agent.core.config/resolve-scope dirs :project))
        ":project must error when no project-dir is resolvable")))

(deftest read-edn-config-scoped
  (let [user-dir (str *tmp-project* "/userdir")
        _ (.mkdirs (io/file user-dir ".brainyard"))
        dirs {:user-dir user-dir :project-dir *tmp-project* :working-dir *tmp-project*}
        project-cfg (assoc base-config :scope-marker :project-file)
        user-cfg    (assoc base-config :scope-marker :user-file)]
    (seed-config! *tmp-project* project-cfg)
    (spit (io/file user-dir ".brainyard" "config.edn") (pr-str user-cfg))
    (is (= :project-file (:scope-marker (ai.brainyard.agent.core.config/read-edn-config dirs :project))))
    (is (= :user-file    (:scope-marker (ai.brainyard.agent.core.config/read-edn-config dirs :user))))
    (is (= :project-file (:scope-marker (ai.brainyard.agent.core.config/read-edn-config dirs :auto)))
        ":auto picks project when project's config.edn exists")))

(deftest read-edn-config-auto-falls-back-to-user
  (let [user-dir (str *tmp-project* "/userdir")
        _ (.mkdirs (io/file user-dir ".brainyard"))
        dirs {:user-dir user-dir :project-dir *tmp-project* :working-dir *tmp-project*}
        user-cfg (assoc base-config :scope-marker :user-only)]
    (spit (io/file user-dir ".brainyard" "config.edn") (pr-str user-cfg))
    ;; NO project config.edn seeded
    (is (= :user-only (:scope-marker (ai.brainyard.agent.core.config/read-edn-config dirs :auto)))
        ":auto falls back to user when project has no file")))

(deftest write-edn-config-scoped-project-and-user
  (let [user-dir (str *tmp-project* "/userdir")
        _ (.mkdirs (io/file user-dir))
        dirs {:user-dir user-dir :project-dir *tmp-project* :working-dir *tmp-project*}
        proj-path (ai.brainyard.agent.core.config/write-edn-config!
                   dirs {:scope :project-write} :project)
        user-path (ai.brainyard.agent.core.config/write-edn-config!
                   dirs {:scope :user-write} :user)]
    (is (= (str *tmp-project* "/.brainyard/config.edn") proj-path))
    (is (= (str user-dir "/.brainyard/config.edn") user-path))
    (is (= {:scope :project-write}
           (edn/read-string (slurp proj-path))))
    (is (= {:scope :user-write}
           (edn/read-string (slurp user-path))))))

(deftest write-edn-config-scoped-project-errors-without-project-dir
  (let [user-dir (str *tmp-project* "/userdir")
        _ (.mkdirs (io/file user-dir))
        dirs {:user-dir user-dir :project-dir nil :working-dir user-dir}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (ai.brainyard.agent.core.config/write-edn-config!
                  dirs {:hello :world} :project)))))

;; ============================================================================
;; config$read / config$apply scope plumbing (command-layer)
;;
;; These exercise the tool surface end-to-end. We seed BOTH a project file
;; (the standard *tmp-project* fixture) and a per-test user file under
;; *tmp-project*/userdir, then invoke commands with :project-dir AND a
;; manually-bound user.home so the user-config-dir resolves into the
;; sandbox.
;; ============================================================================

(defn- with-sandbox-user-home
  "Run f with `user.home` system property pointing at a sandbox dir. Restores
   the original on exit."
  [^String home f]
  (let [prev (System/getProperty "user.home")]
    (System/setProperty "user.home" home)
    (try (f)
         (finally
           (if prev
             (System/setProperty "user.home" prev)
             (System/clearProperty "user.home"))))))

(deftest config$read-with-scope
  (let [user-home (str *tmp-project* "/home")
        _ (.mkdirs (io/file user-home ".brainyard"))
        project-cfg (assoc base-config :scope-marker :proj)
        user-cfg    (assoc base-config :scope-marker :usr)]
    (seed-config! *tmp-project* project-cfg)
    (spit (io/file user-home ".brainyard" "config.edn") (pr-str user-cfg))
    (with-sandbox-user-home
      user-home
      (fn []
        (testing ":scope :project reads project file"
          (let [r (tool/invoke-tool :config$read :scope :project
                                    :project-dir *tmp-project*)]
            (is (= :project (:scope r)))
            (is (= :proj (get-in r [:persisted :scope-marker])))))
        (testing ":scope :user reads user file"
          (let [r (tool/invoke-tool :config$read :scope :user
                                    :project-dir *tmp-project*)]
            (is (= :user (:scope r)))
            (is (= :usr (get-in r [:persisted :scope-marker])))))
        (testing ":scope :auto prefers project when project file exists"
          (let [r (tool/invoke-tool :config$read :scope :auto
                                    :project-dir *tmp-project*)]
            (is (= :project (:scope r)))
            (is (= :auto (:requested-scope r)))))))))

(deftest config$apply-with-explicit-scope
  (let [user-home (str *tmp-project* "/home")
        _ (.mkdirs (io/file user-home ".brainyard"))]
    (seed-config! *tmp-project* base-config)
    (spit (io/file user-home ".brainyard" "config.edn") (pr-str base-config))
    (with-sandbox-user-home
      user-home
      (fn []
        (testing "writing to :user does NOT touch project file"
          (let [r (tool/invoke-tool :config$apply
                                    :proposed {:agent {:config {:max-iterations 333}}}
                                    :reason "bump iter (user)"
                                    :scope :user
                                    :confirm? true
                                    :project-dir *tmp-project*)]
            (is (true? (:ok? r)))
            (is (= :user (:scope r)))
            (is (= (str user-home "/.brainyard/config.edn") (:path r)))
           ;; user file mutated
            (is (= 333 (get-in (edn/read-string
                                (slurp (io/file user-home ".brainyard" "config.edn")))
                               [:agent :config :max-iterations])))
           ;; project file untouched (still has base-config's 100)
            (is (= 100 (get-in (edn/read-string
                                (slurp (io/file *tmp-project* ".brainyard" "config.edn")))
                               [:agent :config :max-iterations])))))
        (testing "writing to :project does NOT touch user file"
          (let [r (tool/invoke-tool :config$apply
                                    :proposed {:agent {:config {:max-iterations 444}}}
                                    :reason "bump iter (project)"
                                    :scope :project
                                    :confirm? true
                                    :project-dir *tmp-project*)]
            (is (true? (:ok? r)))
            (is (= :project (:scope r)))
           ;; project file mutated to 444
            (is (= 444 (get-in (edn/read-string
                                (slurp (io/file *tmp-project* ".brainyard" "config.edn")))
                               [:agent :config :max-iterations])))
           ;; user file still at 333 from previous step
            (is (= 333 (get-in (edn/read-string
                                (slurp (io/file user-home ".brainyard" "config.edn")))
                               [:agent :config :max-iterations])))))))))

(deftest config$apply-unconfirmed-surfaces-scope-and-path
  (seed-config! *tmp-project* base-config)
  (let [r (tool/invoke-tool :config$apply
                            :proposed {:agent {:config {:max-iterations 200}}}
                            :reason "preview"
                            :scope :project
                            :project-dir *tmp-project*)]
    (is (= :unconfirmed (:stage r)))
    (is (= :project (:scope r)))
    (is (= :project (:requested-scope r)))
    (is (string? (:path r)))
    (is (re-find #"project|Resolved scope" (:hint r)))))

(deftest config$apply-project-scope-errors-without-project-dir
  (let [user-home (str *tmp-project* "/home")
        _ (.mkdirs (io/file user-home ".brainyard"))]
    (spit (io/file user-home ".brainyard" "config.edn") (pr-str base-config))
    (with-sandbox-user-home
      user-home
      (fn []
       ;; No :project-dir, JVM cwd is the test runner's cwd which IS this
       ;; repo's git root → project IS resolvable. To force the error path,
       ;; we set BY_PROJECT_DIR explicitly to "" via the env... which
       ;; we can't do at runtime. Instead test the inner helper directly:
        (let [dirs {:user-dir user-home :project-dir nil :working-dir user-home}
              r    (ai.brainyard.agent.core.config/resolve-scope dirs :project)]
          (is (nil? r)
              "resolve-scope :project must return nil with no project-dir"))))))

(deftest config$apply-first-write-to-new-scope-skips-snapshot
  ;; Scenario: user-scope file does NOT exist yet; user asks to write there.
  ;; Should succeed with :snapshot-skipped true (no file to snapshot).
  (let [user-home (str *tmp-project* "/home")
        _ (.mkdirs (io/file user-home ".brainyard"))]
    (seed-config! *tmp-project* base-config)
    ;; intentionally no user config.edn
    (with-sandbox-user-home
      user-home
      (fn []
        (let [r (tool/invoke-tool :config$apply
                                  :proposed {:permissions {:mode :auto-approve}}
                                  :reason "first user write"
                                  :scope :user
                                  :confirm? true
                                  :project-dir *tmp-project*)]
          (is (true? (:ok? r)))
          (is (true? (:snapshot-skipped r)))
          (is (nil? (:snapshot-path r)))
          (is (.exists (io/file user-home ".brainyard" "config.edn"))))))))

(deftest config$snapshot-errors-when-no-file-at-scope
  ;; Standalone config$snapshot must NOT silently succeed when there's
  ;; nothing to snapshot (unlike config$apply which falls through).
  (let [user-home (str *tmp-project* "/home")
        _ (.mkdirs (io/file user-home ".brainyard"))]
    (seed-config! *tmp-project* base-config)
    (with-sandbox-user-home
      user-home
      (fn []
        (let [r (tool/invoke-tool :config$snapshot
                                  :reason "user snap"
                                  :scope :user
                                  :project-dir *tmp-project*)]
          (is (false? (:ok? r)))
          (is (re-find #"nothing to snapshot|No config" (:error r)))
          (is (= :user (:scope r))))))))

;; ============================================================================
;; .brainyard/ subdir scope policy
;;
;; Codifies WHICH scopes each .brainyard/<name> entry is allowed at:
;;   - logs/ → :user only
;;   - memory/ → both (user-scoped SQLite store + project-scoped file memory)
;;   - sessions/ → :project only (sessions are project-specific)
;;   - config-agent/, init-agent/ → both (mirror the file they edit)
;;   - explore-agent/, plan-agent/, etc. (*-agent fallthrough) → :project only
;;   - config.edn, BRAINYARD.md, skills/ → both
;; ============================================================================

(deftest subdir-allowed-scopes-known-entries
  (testing "user-only subdirs"
    (is (= #{:user} (ai.brainyard.agent.core.config/subdir-allowed-scopes "logs"))))
  (testing "project-only: sessions"
    (is (= #{:project} (ai.brainyard.agent.core.config/subdir-allowed-scopes "sessions"))))
  (testing "dual-scope files / dirs"
    ;; memory is :both — user-scoped SQLite store + project-scoped file memory.
    (is (= #{:user :project} (ai.brainyard.agent.core.config/subdir-allowed-scopes "memory")))
    (is (= #{:user :project} (ai.brainyard.agent.core.config/subdir-allowed-scopes "config.edn")))
    (is (= #{:user :project} (ai.brainyard.agent.core.config/subdir-allowed-scopes "BRAINYARD.md")))
    (is (= #{:user :project} (ai.brainyard.agent.core.config/subdir-allowed-scopes "skills"))))
  (testing "config-agent and init-agent are exceptions — both scopes"
    (is (= #{:user :project} (ai.brainyard.agent.core.config/subdir-allowed-scopes "agents/config-agent")))
    (is (= #{:user :project} (ai.brainyard.agent.core.config/subdir-allowed-scopes "agents/init-agent"))))
  (testing "project-only non-agent dirs (charts, temp/clj-sandbox)"
    (is (= #{:project} (ai.brainyard.agent.core.config/subdir-allowed-scopes "charts")))
    (is (= #{:project} (ai.brainyard.agent.core.config/subdir-allowed-scopes "temp/clj-sandbox")))))

(deftest subdir-allowed-scopes-agent-fallthrough
  (testing "*-agent names not explicitly listed default to project-only"
    (doseq [name ["explore-agent" "plan-agent" "todo-agent"
                  "workflow-agent" "research-agent" "update-agent"
                  "eval-agent" "exec-agent"
                  "future-unnamed-agent"]]
      (is (= #{:project} (ai.brainyard.agent.core.config/subdir-allowed-scopes name))
          (str name " must default to project-only")))))

(deftest subdir-allowed-scopes-unknown-permissive
  (testing "unknown non-agent names get permissive default"
    (is (= #{:user :project}
           (ai.brainyard.agent.core.config/subdir-allowed-scopes "completely-unknown")))))

(deftest subdir-scope-allowed-predicate
  ;; memory is :both; logs is the user-only example.
  (is (true?  (ai.brainyard.agent.core.config/subdir-scope-allowed? "memory" :user)))
  (is (true?  (ai.brainyard.agent.core.config/subdir-scope-allowed? "memory" :project)))
  (is (true?  (ai.brainyard.agent.core.config/subdir-scope-allowed? "logs" :user)))
  (is (false? (ai.brainyard.agent.core.config/subdir-scope-allowed? "logs" :project)))
  (is (true?  (ai.brainyard.agent.core.config/subdir-scope-allowed? "explore-agent" :project)))
  (is (false? (ai.brainyard.agent.core.config/subdir-scope-allowed? "explore-agent" :user)))
  (is (true?  (ai.brainyard.agent.core.config/subdir-scope-allowed? "agents/init-agent" :user)))
  (is (true?  (ai.brainyard.agent.core.config/subdir-scope-allowed? "agents/init-agent" :project))))

(deftest brainyard-subdir-resolves-allowed-only
  (let [user-dir (str *tmp-project* "/userdir")
        _ (.mkdirs (io/file user-dir))
        dirs {:user-dir user-dir :project-dir *tmp-project* :working-dir *tmp-project*}]
    (testing "allowed combos return paths"
      (is (= (str user-dir "/.brainyard/memory")
             (ai.brainyard.agent.core.config/brainyard-subdir dirs "memory" :user)))
      ;; memory is :both — also resolves at :project scope (file-based memory).
      (is (= (str *tmp-project* "/.brainyard/memory")
             (ai.brainyard.agent.core.config/brainyard-subdir dirs "memory" :project)))
      (is (= (str user-dir "/.brainyard/logs")
             (ai.brainyard.agent.core.config/brainyard-subdir dirs "logs" :user)))
      (is (= (str *tmp-project* "/.brainyard/agents/explore-agent")
             (ai.brainyard.agent.core.config/brainyard-subdir dirs "agents/explore-agent" :project)))
      (is (= (str *tmp-project* "/.brainyard/charts")
             (ai.brainyard.agent.core.config/brainyard-subdir dirs "charts" :project)))
      (is (= (str *tmp-project* "/.brainyard/temp/clj-sandbox")
             (ai.brainyard.agent.core.config/brainyard-subdir dirs "temp/clj-sandbox" :project)))
      (is (= (str user-dir "/.brainyard/skills")
             (ai.brainyard.agent.core.config/brainyard-subdir dirs "skills" :user)))
      (is (= (str *tmp-project* "/.brainyard/skills")
             (ai.brainyard.agent.core.config/brainyard-subdir dirs "skills" :project))))
    (testing "sessions resolve at :project scope"
      (is (= (str *tmp-project* "/.brainyard/sessions")
             (ai.brainyard.agent.core.config/brainyard-subdir dirs "sessions" :project))))
    (testing "forbidden combos return nil"
      (is (nil? (ai.brainyard.agent.core.config/brainyard-subdir dirs "sessions" :user)))
      (is (nil? (ai.brainyard.agent.core.config/brainyard-subdir dirs "logs" :project)))
      (is (nil? (ai.brainyard.agent.core.config/brainyard-subdir dirs "explore-agent" :user)))
      (is (nil? (ai.brainyard.agent.core.config/brainyard-subdir dirs "plan-agent" :user)))
      (is (nil? (ai.brainyard.agent.core.config/brainyard-subdir dirs "charts" :user)))
      (is (nil? (ai.brainyard.agent.core.config/brainyard-subdir dirs "temp/clj-sandbox" :user))))))

(deftest brainyard-subdir!-creates-directory
  (let [user-dir (str *tmp-project* "/userdir")
        _ (.mkdirs (io/file user-dir))
        dirs {:user-dir user-dir :project-dir *tmp-project* :working-dir *tmp-project*}
        logs (ai.brainyard.agent.core.config/brainyard-subdir! dirs "logs" :user)]
    (is (= (str user-dir "/.brainyard/logs") logs))
    (is (.isDirectory (io/file logs))
        "brainyard-subdir! must create the directory on demand"))
  (testing "forbidden scope returns nil and creates nothing"
    ;; logs is user-only, so :project is forbidden (memory is now :both).
    (let [dirs {:user-dir "/tmp/nope" :project-dir "/tmp/nope" :working-dir "/tmp/nope"}]
      (is (nil? (ai.brainyard.agent.core.config/brainyard-subdir! dirs "logs" :project))))))

;; ============================================================================
;; resolve-project-dir working-dir fallback
;;
;; Outside a git repo and without BY_PROJECT_DIR, project-dir falls
;; back to the working directory rather than nil. This keeps *-agent
;; artifact dirs sane in non-repo contexts.
;; ============================================================================

(deftest resolve-project-dir-falls-back-to-working-dir
  (testing "outside a git repo, working-dir is used"
    (let [non-repo (str *tmp-project* "/no-repo-here")]
      (.mkdirs (io/file non-repo))
      ;; *tmp-project* lives under /tmp/by-config-test-... — /tmp itself
      ;; has no .git ancestor, so find-git-root returns nil and the
      ;; working-dir fallback fires.
      (is (= non-repo
             (ai.brainyard.agent.core.config/resolve-project-dir non-repo)))))
  (testing "inside a git repo, git-root wins over working-dir fallback"
    ;; Find this test file's git ancestor by walking up from its known
    ;; location. The test runs from somewhere inside the brainyard repo
    ;; — the only requirement is that there IS a .git ancestor.
    (let [start    (System/getProperty "user.dir")
          git-root (ai.brainyard.agent.core.config/find-git-root start)]
      (when git-root
        (let [deep-subdir (str git-root "/components/agent/src")]
          (is (= git-root
                 (ai.brainyard.agent.core.config/resolve-project-dir deep-subdir))
              "subdir inside the repo should resolve to the repo root, not the subdir"))))))
