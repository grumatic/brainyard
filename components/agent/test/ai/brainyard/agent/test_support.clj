;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.test-support
  "Shared clojure.test fixtures for the agent brick.

   Not a `*_test` namespace, so no runner picks it up — it is required by the
   suites that need it."
  (:require [clojure.java.io :as io]
            [ai.brainyard.agent.core.config :as config]))

(defn delete-tree!
  "Recursively delete `f` (a File or path string). Silent when absent."
  [f]
  (let [f (io/file f)]
    (when (.exists f)
      (doseq [^java.io.File c (reverse (file-seq f))] (.delete c)))))

(defn with-tmp-sessions-root
  "Fixture: redirect every session writer into a fresh temp dir for the test,
   then delete it.

   Any test that drives a real agent turn writes
   `<sessions-root>/<session-id>/trajectory.edn`. Unredirected that lands in
   the developer's own `<project>/.brainyard/sessions/`, where it is invisible
   (the dir is gitignored) and accumulates one directory per `bb test` run,
   interleaved with the user's REAL sessions — so it cannot be cleaned by
   wiping the directory.

   `config/*sessions-root-override*` is the single knob: trajectory, the
   memory agent and the persist root resolver all resolve through
   `config/sessions-root`.

   Uses `with-redefs`, NOT `binding`, deliberately. The var is dynamic, so
   `binding` is thread-local — and the writer is not always on the test
   thread (a2a-loopback serves requests from a handler thread, and a detached
   task writes from its own). `with-redefs` sets the root value, which every
   thread sees. Safe here because clojure.test runs tests sequentially."
  [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "by-sessions-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (with-redefs [config/*sessions-root-override* (.getAbsolutePath dir)]
      (try (f)
           (finally (delete-tree! dir))))))
