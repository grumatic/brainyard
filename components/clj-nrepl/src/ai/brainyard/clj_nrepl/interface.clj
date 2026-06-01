;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-nrepl.interface
  "Loopback nREPL client/server for live-runtime eval.

   Backs the :backend :nrepl arm of code$eval. The server hosts evaluation
   inside the running brainyard process; the client sends Clojure code to
   it over a loopback socket and harvests {:result :output :error :ns}.

   The safety boundary lives here: code is classified (read-only vs
   mutate) and gated by a grant (clj-nrepl.core.grant) before evaluation.
   Every eval is audited via mulog. See docs/design/clj-nrepl-eval.md."
  (:require [ai.brainyard.clj-nrepl.core.server :as server]
            [ai.brainyard.clj-nrepl.core.client :as client]
            [ai.brainyard.clj-nrepl.core.session :as session]
            [ai.brainyard.clj-nrepl.core.grant :as grant]
            [ai.brainyard.clj-nrepl.core.classifier :as classifier]
            [ai.brainyard.clj-nrepl.core.confirm :as confirm]
            [ai.brainyard.clj-nrepl.core.drift :as drift]
            [ai.brainyard.clj-nrepl.core.audit :as audit]))

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
   Gated by the active grant + read-only classifier. See client/eval-string."
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
;; Grant
;; ============================================================================

(def grant!  grant/grant!)
(def revoke! grant/revoke!)
(def active? grant/active?)
(def scope   grant/scope)
(def maybe-grant-from-env! grant/maybe-grant-from-env!)

;; ============================================================================
;; Classifier
;; ============================================================================

(def classify     classifier/classify)
(def mutating?    classifier/mutating?)
(def explain      classifier/explain)
(def denied?      classifier/denied?)
(def deny-reason  classifier/deny-reason)

;; ============================================================================
;; Confirmation (Phase 2 — first mutating eval per session)
;; ============================================================================

(def set-confirm-fn!     confirm/set-confirm-fn!)
(def confirm-mutation!   confirm/confirm-mutation!)
(def confirmed?          confirm/confirmed?)
(def mark-confirmed!     confirm/mark-confirmed!)
(def revoke-confirmation! confirm/revoke-confirmation!)

;; ============================================================================
;; Runtime drift (Phase 2 — marks divergence from source)
;; ============================================================================

(def drift-mark!     drift/mark!)
(def drift-markers   drift/markers)
(def drift-clear!    drift/clear!)
(def drifted?        drift/drifted?)
(def drift-count     drift/marker-count)

;; ============================================================================
;; Audit
;; ============================================================================

(def audit-eval audit/audit-eval)
