;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-nrepl.core.classifier
  "Deny-list substring check on submitted code — the only check on the
   nREPL eval path. `denied?` / `deny-reason` catch the genuinely
   catastrophic (System/exit, credential namespaces, raw Runtime/.exec)
   and are enforced unconditionally in the client gate.

   This is best-effort defense (a substring tripwire), NOT a sandbox: it
   raises the cost of an accident, not of a determined bypass. For real
   isolation use the SCI sandbox backend. See docs/design/clj-nrepl-eval.md.")

(def ^:private deny-substrings
  "Strings whose presence in source rejects the form outright — obvious
   process-control / credential reaches."
  ["System/exit"
   "Runtime/.exec"
   "Runtime/getRuntime"
   "shutdown-agents"
   "java.lang.Runtime"
   "ai.brainyard.aws-client"
   "ai.brainyard.keycloak"])

(defn deny-reason
  "Return the deny-list substring present in `code`, or nil."
  [code]
  (let [s (str code)]
    (some #(when (.contains s ^String %) %) deny-substrings)))

(defn denied?
  "True when `code` contains any deny-list substring."
  [code]
  (some? (deny-reason code)))
