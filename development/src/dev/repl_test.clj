;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns dev.repl-test
  "Run Polylith brick tests inside a LIVE dev nREPL — no restart.

   WHY THIS EXISTS
   `bb repl` / `bb repl:ata` launch `clj -M:dev:nrepl`, whose classpath
   includes every brick's `src/` + `resources/` but intentionally OMITS the
   `test/` dirs (Polylith only adds them under the :test / :poly runner). So
   in the dev REPL `(require 'some.ns-test)` ALWAYS fails with
   FileNotFoundException — the .clj is simply not on the classpath. (That is
   why `load-file` of an absolute path works while `require` does not.)

   This namespace re-adds the brick test dirs to the running JVM's
   `clojure.lang.DynamicClassLoader` (the nREPL base loader) at runtime, so
   `require` resolves test namespaces against the live, hot-reloaded state.

   USAGE — load by ABSOLUTE path so it works from any brick REPL (the ata
   REPL's classpath does not include development/src):

     (load-file \"/abs/.../development/src/dev/repl_test.clj\")
     (dev.repl-test/run 'ai.brainyard.agent.reference-test)  ; add cp + reload + run
     (dev.repl-test/add-test-cp!)                            ; just add the paths
     (dev.repl-test/run-all '[ns-a ns-b])                    ; several at once

   If you also hot-reloaded the namespace UNDER test, `run` reloads the test
   ns only; reload the impl yourself first (or via your usual workflow)."
  (:require [clojure.java.io :as io]
            [clojure.test :as t]))

(defn- workspace-root
  "Walk up from the JVM cwd until a dir containing workspace.edn is found.
   Returns a canonical File, or nil if not inside a Polylith workspace."
  []
  (loop [d (.getCanonicalFile (io/file (System/getProperty "user.dir")))]
    (cond
      (nil? d)                            nil
      (.exists (io/file d "workspace.edn")) d
      :else                               (recur (.getParentFile d)))))

(defn test-dirs
  "Every existing components/*/test and bases/*/test dir in the workspace,
   as canonical Files."
  []
  (if-let [root (workspace-root)]
    (->> ["components" "bases"]
         (mapcat (fn [kind]
                   (some->> (.listFiles (io/file root kind))
                            (keep (fn [^java.io.File brick]
                                    (let [td (io/file brick "test")]
                                      (when (.isDirectory td)
                                        (.getCanonicalFile td))))))))
         vec)
    (throw (ex-info "Not inside a Polylith workspace (no workspace.edn above cwd)"
                    {:cwd (System/getProperty "user.dir")}))))

(defn add-test-cp!
  "Add every brick `test/` dir to the live classpath via the nREPL
   DynamicClassLoader. Re-adding an already-present URL is a harmless no-op,
   so this is safe to call repeatedly. Returns the paths added."
  []
  (let [cl (clojure.lang.RT/baseLoader)]
    (when-not (instance? clojure.lang.DynamicClassLoader cl)
      (throw (ex-info "baseLoader is not a DynamicClassLoader — cannot add test paths at runtime; start the REPL via nREPL (bb repl / bb repl:ata)"
                      {:loader (class cl)})))
    (mapv (fn [^java.io.File d]
            (.addURL ^clojure.lang.DynamicClassLoader cl (.toURL (.toURI d)))
            (.getPath d))
          (test-dirs))))

(defn run
  "Ensure the test classpath, (re)load `ns-sym`, run its tests. Returns the
   clojure.test summary map {:test :pass :fail :error}."
  [ns-sym]
  (add-test-cp!)
  (require ns-sym :reload)
  (t/run-tests ns-sym))

(defn run-all
  "Like `run` but for a seq of test namespaces, in one clojure.test run."
  [ns-syms]
  (add-test-cp!)
  (doseq [n ns-syms] (require n :reload))
  (apply t/run-tests ns-syms))
