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

(def ^:dynamic *root*
  "Root directory for persisted sessions.

   Scope contract: `sessions/` is PROJECT-SCOPE per
   `ai.brainyard.agent.core.config/subdir-scope-policy` — sessions are
   project-specific (their scrollback, message log, todo, queue, permissions
   and trajectory all describe work in one codebase), so they live under
   `<project>/.brainyard/sessions/`.

   This component is dependency-free and cannot resolve project-dir itself, so
   the app layer installs the resolved root at startup via `set-root!`
   (computed by `config/sessions-root`, which honors `-C` / `BY_PROJECT_DIR`).
   Until injected, this falls back to `~/.brainyard/sessions/` so standalone
   use and pre-init reads still work. Tests rebind via `with-root`."
  (io/file (System/getProperty "user.home") ".brainyard" "sessions"))

(defn set-root!
  "Permanently install the sessions root (via `alter-var-root`, not a
   thread-local `binding`) so every thread — host, control-server connections,
   GC sweeps — observes it. Called once at app startup from
   `persist/set-root!` with `(config/sessions-root)`. Tests still override
   locally with `with-root`/`binding`. Accepts any path-like; returns the
   installed File."
  [dir]
  (alter-var-root #'*root* (constantly (io/file dir))))

(defn- ^File ensure-dir!
  [^File f]
  (when-not (.exists f) (.mkdirs f))
  f)

(defn root-dir
  "Return (and create on demand) the sessions root directory."
  ^File []
  (ensure-dir! *root*))

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
