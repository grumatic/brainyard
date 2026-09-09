;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.env-files-test
  "Per-agent `.env` files — docs/design/env-files-design.md §3.2.

   The property this suite exists for is the one no test of a single agent
   would catch: a per-agent value must NOT leak to another agent in the same
   process. `.env` values normally become process-global System Properties, and
   agents are not one-at-a-time, so the leak is the default outcome of the
   obvious implementation."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.agent.core.env-files :as ef]
            [ai.brainyard.agent.core.agent :as ag]
            [ai.brainyard.agent.core.config :as cfg]
            [ai.brainyard.util.interface :as util]
            [clojure.java.io :as io]))

(def ^:dynamic *proj* nil)

(defn- rm-rf [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-rf c)))
    (.delete f)))

(defn with-tmp-project [t]
  (let [root (str (System/getProperty "java.io.tmpdir") "/by-envfiles-" (System/nanoTime))
        prior (System/getProperty "BY_PROJECT_DIR")]
    (.mkdirs (io/file root ".brainyard"))
    (binding [*proj* root]
      (try
        (System/setProperty "BY_PROJECT_DIR" root)
        (ef/invalidate-cache!)
        (t)
        (finally
          (if prior (System/setProperty "BY_PROJECT_DIR" prior)
              (System/clearProperty "BY_PROJECT_DIR"))
          (ef/invalidate-cache!)
          (rm-rf (io/file root)))))))

(use-fixtures :each with-tmp-project)

(defn- seed-agent-env! [agent-type content]
  (let [d (io/file *proj* ".brainyard" "agents" agent-type)]
    (.mkdirs d)
    (spit (io/file d ".env") content)))

(defn- mk [id]
  (ag/setup-agent {:id id :instruction "x"
                   :agent-session {:user-id "u" :session-id "s"}}))

;; ============================================================================
;; The directory is named by the defagent TYPE
;; ============================================================================

(deftest defagent-type-comes-from-the-instance-id
  ;; `:<type>/<suffix>` for a dispatched specialist, bare `:<type>` for a root —
  ;; the same derivation hooks/match-defagent-type uses.
  (is (= "explore-agent" (ef/defagent-type (mk :explore-agent/i1))))
  (is (= "coact-agent"   (ef/defagent-type (mk :coact-agent)))))

(deftest defagent-type-never-throws
  ;; Callers reach here from a spawn site that may hold a stub, a bare map, or
  ;; nil. Answering "no type" is safe; throwing would take down the spawn.
  (is (nil? (ef/defagent-type nil)))
  (is (nil? (ef/defagent-type {})))
  (is (nil? (ef/defagent-type "not-an-agent"))))

(deftest agent-env-path-is-under-the-agents-dir
  (is (= (str *proj* "/.brainyard/agents/explore-agent/.env")
         (ef/agent-env-path (mk :explore-agent/i1) *proj*)))
  (testing "nil when there is no type or no project"
    (is (nil? (ef/agent-env-path {} *proj*)))
    (is (nil? (ef/agent-env-path (mk :explore-agent/i1) nil)))))

;; ============================================================================
;; Reading
;; ============================================================================

(deftest agent-env-reads-the-agents-own-file
  (seed-agent-env! "explore-agent" "GH_TOKEN=ro-token\nOTHER=x\n")
  (is (= {"GH_TOKEN" "ro-token" "OTHER" "x"}
         (ef/agent-env (mk :explore-agent/i1)))))

(deftest agent-env-is-empty-when-there-is-no-file
  (is (= {} (ef/agent-env (mk :explore-agent/i1)))))

(deftest agent-env-never-throws
  ;; An agent's environment failing open to "nothing extra" is correct; failing
  ;; to start a spawn because a file is malformed is not.
  (is (= {} (ef/agent-env nil)))
  (is (= {} (ef/agent-env {})))
  (testing "a file of pure garbage parses to nothing rather than throwing"
    (seed-agent-env! "explore-agent" "not a key value line\n#comment\n\n")
    (is (= {} (ef/agent-env (mk :explore-agent/i1))))))

(deftest the-cache-refreshes-on-mtime
  ;; `by env set` should take effect on the next spawn without a restart.
  (seed-agent-env! "explore-agent" "K=first\n")
  (let [a (mk :explore-agent/i1)]
    (is (= {"K" "first"} (ef/agent-env a)))
    (let [f (io/file (ef/agent-env-path a *proj*))]
      (spit f "K=second\n")
      (.setLastModified f (+ 10000 (.lastModified f))))
    (is (= {"K" "second"} (ef/agent-env a)))))

;; ============================================================================
;; The leak this exists to prevent
;; ============================================================================

(deftest one-agents-value-does-not-reach-another
  (seed-agent-env! "explore-agent" "GH_TOKEN=ro-token\n")
  (seed-agent-env! "exec-agent"    "GH_TOKEN=rw-token\n")
  (is (= "ro-token" (get (ef/agent-env (mk :explore-agent/i1)) "GH_TOKEN")))
  (is (= "rw-token" (get (ef/agent-env (mk :exec-agent/i1)) "GH_TOKEN")))
  (is (= {} (ef/agent-env (mk :coact-agent)))
      "and an agent with no file of its own sees nothing"))

(deftest a-per-agent-value-never-reaches-the-property-table
  ;; The structural reason this namespace exists rather than reusing the loader:
  ;; the property table is process-global and cannot hold a scoped value.
  (seed-agent-env! "explore-agent" "BY_ENVFILES_LEAK_PROBE=secret\n")
  (is (= "secret" (get (ef/agent-env (mk :explore-agent/i1)) "BY_ENVFILES_LEAK_PROBE")))
  (is (nil? (System/getProperty "BY_ENVFILES_LEAK_PROBE")))
  (is (nil? (util/resolve-var "BY_ENVFILES_LEAK_PROBE"))
      "a global reader asking a global question must not get one agent's answer"))

;; ============================================================================
;; The seam into the scoping machinery
;; ============================================================================

(deftest env-policy-merges-the-file-over-config-env-vars
  ;; The file holds the credential (config$apply's secret scan refuses one);
  ;; the config key holds the endpoint. Local uncommitted beats shared committed.
  (seed-agent-env! "explore-agent" "GH_TOKEN=from-file\n")
  (let [a (ag/setup-agent {:id :explore-agent/i9 :instruction "x"
                           :agent-session {:user-id "u" :session-id "s"}
                           :env-vars {"GH_TOKEN" "from-config"
                                      "ENDPOINT" "https://staging"}})
        {:keys [vars]} (cfg/env-policy a)]
    (is (= "from-file" (get vars "GH_TOKEN")))
    (is (= "https://staging" (get vars "ENDPOINT"))
        "a name only config declares still contributes")))

;; ============================================================================
;; Writing — `by env set` / `unset` / `import`
;; ============================================================================

(deftest set-creates-the-file-0600-and-a-gitignore-beside-it
  ;; The gitignore is the one that matters: this repo ignores `.brainyard/`
  ;; wholly, but a project is documented as COMMITTING it so config travels with
  ;; the codebase — and under that reading a `.brainyard/.env` gets committed.
  (let [r (ef/set-var! {:scope :project} "TOKEN" "sk-1")
        f (io/file (:path r))]
    (is (:created? r))
    (is (false? (:replaced? r)))
    (is (.isFile f))
    (is (= #{java.nio.file.attribute.PosixFilePermission/OWNER_READ
             java.nio.file.attribute.PosixFilePermission/OWNER_WRITE}
           (set (java.nio.file.Files/getPosixFilePermissions
                 (.toPath f) (into-array java.nio.file.LinkOption []))))
        "0600 — nothing but the owner")
    (let [gi (slurp (io/file *proj* ".brainyard" ".gitignore"))]
      (is (re-find #"(?m)^\.env$" gi))
      (is (re-find #"(?m)^agents/\*/\.env$" gi)))))

(deftest ensure-gitignore-is-idempotent-and-additive
  (let [bd (str *proj* "/.brainyard")]
    (spit (io/file bd ".gitignore") "# pre-existing\nsomething-else\n")
    (ef/ensure-gitignore! bd)
    (let [after (slurp (io/file bd ".gitignore"))]
      (is (re-find #"something-else" after) "existing content is kept")
      (is (re-find #"(?m)^\.env$" after)))
    (testing "a second call writes nothing"
      (let [before (slurp (io/file bd ".gitignore"))]
        (is (nil? (ef/ensure-gitignore! bd)))
        (is (= before (slurp (io/file bd ".gitignore"))))))))

(deftest set-then-unset-round-trips
  (ef/set-var! {:scope :project} "A" "1")
  (ef/set-var! {:scope :project} "B" "2")
  (is (= {"A" "1" "B" "2"} (ef/read-file (ef/scope-path {:scope :project}))))
  (is (:removed? (ef/unset-var! {:scope :project} "A")))
  (is (= {"B" "2"} (ef/read-file (ef/scope-path {:scope :project}))))
  (testing "unsetting a name that is not there is reported, not an error"
    (is (false? (:removed? (ef/unset-var! {:scope :project} "NOPE"))))))

(deftest import-keeps-existing-names-unless-overwrite
  ;; The destructive reading of "import" is the one a user regrets, so it has to
  ;; be asked for.
  (ef/set-var! {:scope :project} "TOKEN" "mine")
  (let [src (io/file *proj* "ext.env")]
    (spit src "TOKEN=theirs\nNEW=added\n")
    (let [r (ef/import-file! {:scope :project} (.getPath src) {})]
      (is (= ["NEW"] (:added r)))
      (is (= ["TOKEN"] (:skipped r)))
      (is (= "mine" (get (ef/read-file (:path r)) "TOKEN"))))
    (let [r (ef/import-file! {:scope :project} (.getPath src) {:overwrite? true})]
      (is (= ["NEW" "TOKEN"] (:overwritten r)))
      (is (= "theirs" (get (ef/read-file (:path r)) "TOKEN"))))))

(deftest import-of-a-missing-file-is-an-error-not-a-throw
  (is (:error (ef/import-file! {:scope :project} (str *proj* "/nope.env") {}))))

(deftest a-value-with-spaces-round-trips
  ;; The writer quotes only what the parser would otherwise mis-read.
  (ef/set-var! {:scope :project} "MSG" "hello world # not a comment")
  (is (= "hello world # not a comment"
         (get (ef/read-file (ef/scope-path {:scope :project})) "MSG"))))

(deftest a-write-to-agent-scope-lands-under-that-agent
  (ef/set-var! {:agent "explore-agent"} "GH_TOKEN" "ro")
  (is (= (str *proj* "/.brainyard/agents/explore-agent/.env")
         (ef/scope-path {:agent "explore-agent"})))
  (is (= {"GH_TOKEN" "ro"} (ef/agent-env (mk :explore-agent/i1))))
  (testing "and is invisible to another agent"
    (is (= {} (ef/agent-env (mk :exec-agent/i1))))))
