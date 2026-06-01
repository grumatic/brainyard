;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-nrepl.core.confirm
  "Session-scoped human-in-the-loop confirmation for the FIRST mutating
   eval per session.

   Design intent (§8.2 #5): operator must explicitly approve crossing
   from read-only inspection into runtime mutation. Once a session is
   approved, subsequent mutating evals in that same session pass
   without re-prompting — the operator opted into THIS investigation,
   not into each individual call.

   Host injects a confirm-fn via `set-confirm-fn!` (e.g. the TUI wraps
   its existing permission-fn). When no fn is installed, the default
   policy is **allow with audit warning** — :mutate is already a high
   bar via the grant, and unattended uses (REPL, tests, automation)
   shouldn't be silently blocked. Production hosts should always
   install a real confirm-fn."
  (:require [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Injectable confirm-fn
;; ============================================================================

;; Shape: (fn [{:keys [session code]}] -> boolean)
;;   true  → mutation approved, mark session confirmed
;;   false → mutation declined, eval is rejected
;; Host (TUI / web) installs via set-confirm-fn!; absent fn → allow.
(defonce ^:private !confirm-fn (atom nil))

(defn set-confirm-fn!
  "Install a confirmation function. Returns the previous fn (or nil).
   Pass `nil` to clear."
  [f]
  (let [prev @!confirm-fn]
    (reset! !confirm-fn f)
    prev))

(defn current-confirm-fn [] @!confirm-fn)

;; ============================================================================
;; Per-session confirmation memo
;; ============================================================================

(defonce ^:private !confirmed-sessions (atom #{}))

(def ^:private default-session ::no-session)

(defn- session-key [session]
  (or session default-session))

(defn confirmed?
  "True when `session` has already been approved for mutating evals in
   this process."
  [session]
  (contains? @!confirmed-sessions (session-key session)))

(defn mark-confirmed!
  "Record that `session` has been approved. Useful for tests + when an
   operator pre-approves out-of-band."
  [session]
  (swap! !confirmed-sessions conj (session-key session)))

(defn revoke-confirmation!
  "Drop `session` from the confirmed set (e.g. on kill-switch)."
  ([] (reset! !confirmed-sessions #{}))
  ([session] (swap! !confirmed-sessions disj (session-key session))))

;; ============================================================================
;; Gate
;; ============================================================================

(defn confirm-mutation!
  "Gate the first mutating eval per session.

   Returns true when the eval may proceed, false when declined.
   On approval, the session is marked confirmed so subsequent calls
   short-circuit.

   When no confirm-fn is installed, defaults to allow with a mulog
   warning — `code$eval`'s audit shim plus the grant TTL provide
   visibility for unattended uses."
  [session code]
  (cond
    (confirmed? session)
    true

    (nil? @!confirm-fn)
    (do
      (mulog/warn ::no-confirm-fn-installed
                  :session  session
                  :decision :allow
                  :reason   "no confirm-fn installed; defaulting to allow")
      (mark-confirmed! session)
      true)

    :else
    (let [approved? (try
                      (boolean (@!confirm-fn {:session session :code code}))
                      (catch Throwable t
                        (mulog/error ::confirm-fn-threw
                                     :session session
                                     :error   (.getMessage t))
                        false))]
      (mulog/info ::mutation-confirm
                  :session  session
                  :decision (if approved? :allow :deny))
      (when approved?
        (mark-confirmed! session))
      approved?)))
