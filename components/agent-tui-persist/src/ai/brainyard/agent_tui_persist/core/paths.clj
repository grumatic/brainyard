;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-persist.core.paths
  "File-system layout for persisted agent sessions.

   Per docs/tmux-based-agent-tui.md §11.3 — every persisted session lives under
   ~/.brainyard/sessions/<agent-session-id>/ with a fixed set of well-known files."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

(def ^:dynamic *root*
  "Root directory for persisted sessions. Defaults to ~/.brainyard/sessions/.
   Bind for tests or non-default installs.

   Scope contract: `sessions/` is USER-SCOPE only per
   `ai.brainyard.agent.core.config/subdir-scope-policy`. Sessions hold
   per-account TUI state (scrollback, input history, queues) that must not
   travel with a repo. The default root pulls from `user.home` directly
   with no project-path option — uphold this by construction, do not
   introduce a project-scope sessions dir."
  (io/file (System/getProperty "user.home") ".brainyard" "sessions"))

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
