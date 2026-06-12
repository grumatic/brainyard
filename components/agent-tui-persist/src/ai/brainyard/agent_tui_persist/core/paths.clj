;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.core.paths
  "File-system layout for persisted agent sessions.

   Per docs/tmux-based-agent-tui.md §11.3 — every persisted session lives under
   <project>/.brainyard/sessions/<agent-session-id>/ with a fixed set of
   well-known files."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

(def ^:dynamic *root-resolver*
  "Thunk returning the sessions-root `File`. This component is
   dependency-free and cannot resolve project-dir itself, so a layer that can
   (the agent-tui base) installs the project-scoped resolver via
   `set-root-resolver!` — `(fn [] (io/file (config/sessions-root)))`. The thunk
   defers to call time, so it always reflects the current project-dir (honoring
   `-C` / `BY_PROJECT_DIR`) and there is no path captured at one startup moment.

   Scope contract: `sessions/` is PROJECT-SCOPE per
   `ai.brainyard.agent.core.config/subdir-scope-policy` — sessions are
   project-specific (their scrollback, message log, todo, queue, permissions
   and trajectory all describe work in one codebase), so they live under
   `<project>/.brainyard/sessions/`.

   Defaults to the legacy `~/.brainyard/sessions/` location so standalone /
   pre-wiring / test use still works. Tests rebind via `with-root` (which pins
   this resolver to a constant)."
  (fn [] (io/file (System/getProperty "user.home") ".brainyard" "sessions")))

(defn set-root-resolver!
  "Install the sessions-root resolver process-wide (via `alter-var-root`, not a
   thread-local `binding`) so every thread — host, control-server connections,
   GC sweeps — observes it. `f` is a 0-arg thunk returning a path-like; it is
   invoked on every `root-dir` call. Called once by the base when it wires
   persist to `config/sessions-root`."
  [f]
  (alter-var-root #'*root-resolver* (constantly f)))

(defn- ^File ensure-dir!
  [^File f]
  (when-not (.exists f) (.mkdirs f))
  f)

(defn root-dir
  "Return (and create on demand) the sessions root directory, resolved fresh
   via `*root-resolver*`."
  ^File []
  (ensure-dir! (io/file (*root-resolver*))))

(defn session-dir
  "Directory for a specific agent-session-id. Creates if missing."
  ^File [session-id]
  (ensure-dir! (io/file (root-dir) (name session-id))))

(defn session-file
  "Path to a named file inside a session directory."
  ^File [session-id filename]
  (io/file (session-dir session-id) filename))

;; Canonical filenames per §11.3.
(def filenames
  {:meta             "meta.edn"
   :session          "session.edn"
   :messages         "messages.log"
   :scrollback-stream    "scrollback.stream.txt"
   :scrollback-activity  "scrollback.activity.txt"
   :layout           "layout.edn"
   :pending-dialogs  "pending-dialogs.edn"
   :permissions      "permissions.edn"
   :queue            "queue.edn"
   :todo             "todo.edn"
   :status           "status.edn"
   :input-history    "input-history.edn"
   :usage-tracker    "usage-tracker.edn"
   :lock             "by-host.lock"})

(defn file-of
  "Resolve a well-known session file by tag (one of the keys of `filenames`)."
  ^File [session-id tag]
  (when-let [filename (get filenames tag)]
    (session-file session-id filename)))

(defn list-sessions
  "Return a sorted vector of session-id strings present under the root."
  []
  (let [root (root-dir)]
    (vec (sort (for [^File f (.listFiles root)
                     :when   (and (.isDirectory f)
                                  (not (str/starts-with? (.getName f) ".")))]
                 (.getName f))))))

(defn delete-session-dir!
  "Recursively delete a session's directory. Returns true if a directory
   actually existed and was removed; nil/false otherwise.

   Note: bypasses `session-dir` deliberately — that fn lazily creates
   the directory via `ensure-dir!`, which would mask a not-found state
   and report a phantom success for a never-existed session-id."
  [session-id]
  (let [dir (io/file (root-dir) (name session-id))]
    (when (.exists dir)
      (doseq [^File f (reverse (file-seq dir))]
        (.delete f))
      (not (.exists dir)))))
