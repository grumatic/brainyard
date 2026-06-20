;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-nrepl.interface
  "Loopback nREPL client/server for live-runtime eval.

   Backs the :backend :nrepl arm of code$eval. The server hosts evaluation
   inside the running brainyard process; the client sends Clojure code to
   it over a loopback socket and harvests {:result :output :error :ns}.

   nREPL is the full-trust backend: the only check on the eval path is the
   deny-list (clj-nrepl.core.classifier). Isolation is delegated to the SCI
   sandbox backend. The only structural safety is the loopback-only socket.
   See docs/design/clj-nrepl-eval.md."
  (:require [ai.brainyard.clj-nrepl.core.server :as server]
            [ai.brainyard.clj-nrepl.core.client :as client]
            [ai.brainyard.clj-nrepl.core.session :as session]
            [ai.brainyard.clj-nrepl.core.classifier :as classifier]))

;; ============================================================================
;; Server lifecycle
;; ============================================================================

(def start-server!
  "Start a loopback-only nREPL server. See server/start-server! for options."
  server/start-server!)

(def stop-server!
  "Stop the running nREPL server."
  server/stop-server!)

(def running?
  "True when an nREPL server is currently running."
  server/running?)

(def server-port
  "Port of the running server, or nil."
  server/server-port)

;; ----- Per-instance port files (multi-instance safe) -----

(def default-port-dir
  "Returns ~/.brainyard/nrepl-ports/ — created on demand with 0700 perms."
  server/default-port-dir)

(def instance-port-file
  "Per-instance port file: <dir>/<base>-<pid>.port."
  server/instance-port-file)

(def cleanup-stale-ports!
  "Delete port files in ~/.brainyard/nrepl-ports/ whose PID is no longer alive."
  server/cleanup-stale-ports!)

(def list-port-files
  "Inventory of known port files: seq of {:pid :port :file :alive?}."
  server/list-port-files)

;; ============================================================================
;; Evaluation
;; ============================================================================

(def eval-string
  "Send code to the live nREPL server. Returns {:code :result :output :error :ns}.
   Only the deny-list gates the eval. See client/eval-string."
  client/eval-string)

(def eval-nrepl-thunk
  "Caller-owned-timeout thunk variant, symmetric to clj-sandbox/eval-sandbox-thunk."
  client/eval-nrepl-thunk)

;; ============================================================================
;; Sessions
;; ============================================================================

(def new-session   session/new-session)
(def close-session session/close-session)
(def interrupt!    session/interrupt!)

;; ============================================================================
;; Deny-list (the only eval-path check)
;; ============================================================================

(def denied?      classifier/denied?)
(def deny-reason  classifier/deny-reason)
