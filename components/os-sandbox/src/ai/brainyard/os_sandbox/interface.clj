;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.os-sandbox.interface
  "Public interface for containing a Brainyard TUI session in a macOS sandbox
   via sandbox-exec (seatbelt).

   The `--sandbox` launcher (in agent-tui-app) wraps `by run` in sandbox-exec so
   the session runs under a write-containment profile: reads/network/exec are
   allowed, but writes are confined to the workspace (~/.brainyard, the project
   subtree, $TMPDIR, /tmp). See core.clj for the policy rationale."
  (:require [ai.brainyard.os-sandbox.core :as core]))

(defn macos?
  "True when running on macOS (the only platform seatbelt supports)."
  []
  (core/macos?))

(defn available?
  "Probe for sandbox-exec support.
   Returns {:ok? true :path str} or {:ok? false :reason str}."
  []
  (core/available?))

(defn self-exec-argv
  "Resolve the argv prefix sandbox-exec should use to relaunch the TUI.
   Honors the `override-var` env override; otherwise resolves the native binary
   path or `which by`. Returns {:ok? true :argv [str ...]} or {:ok? false :reason str}."
  ([] (core/self-exec-argv))
  ([env override-var] (core/self-exec-argv env override-var)))

(defn parse-allow-writes
  "Normalize `--sandbox-allow-write` value(s) into a deduped vec of absolute
   subpath roots. Accepts a comma-separated string or a vector. See
   core/parse-allow-writes."
  [raw cwd home]
  (core/parse-allow-writes raw cwd home))

(defn build-profile-string
  "Pure constructor for the write-containment SBPL profile (`-p` payload).
   See core/build-profile-string."
  [opts]
  (core/build-profile-string opts))

(defn build-sandbox-argv
  "Pure constructor for the sandbox-exec command vector. See
   core/build-sandbox-argv."
  [opts]
  (core/build-sandbox-argv opts))

(defn serve!
  "Spawn the sandboxed TUI in the current terminal; returns {:proc :argv :stop}.
   Forces BY_SANDBOX_CHILD=1 on the child so it runs the TUI, not another sandbox."
  [opts]
  (core/serve! opts))
