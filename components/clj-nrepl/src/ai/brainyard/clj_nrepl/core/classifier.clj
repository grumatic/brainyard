;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-nrepl.core.classifier
  "Two independent checks on submitted code:

   - `mutating?` / `classify` — top-level form classifier. :mutate when
     any form's head matches the mutating-heads set. Gated by the
     :read-only vs :mutate grant scope.

   - `denied?` / `deny-reason` — deny-list substring check. Catches the
     genuinely catastrophic regardless of scope (System/exit, credential
     namespaces, raw Runtime/.exec). Applied unconditionally in the
     client gate — the :mutate scope does NOT lift this.

   Best-effort defense in depth, paired with grant gating + audit —
   not a sandbox. See docs/design/clj-nrepl-eval.md §8.2.")

(def ^:private mutating-heads
  "Symbols that mutate the live runtime or escape into the host JVM at
   the top level. Forms with any of these as the head are rejected
   under :read-only."
  '#{def defn defn- defmacro defmethod defmulti defrecord deftype
     defprotocol definterface defonce defstruct
     alter-var-root intern ns-unmap ns-unalias
     require use import in-ns ns refer refer-clojure
     load-string load-file load load-reader
     eval set!})

(def ^:private deny-substrings
  "Strings whose presence in source rejects the form outright — even
   under :mutate. Obvious process-control / credential reaches."
  ["System/exit"
   "Runtime/.exec"
   "Runtime/getRuntime"
   "shutdown-agents"
   "java.lang.Runtime"
   "ai.brainyard.aws-client"
   "ai.brainyard.keycloak"])

;; ============================================================================
;; Deny-list (always-on gate)
;; ============================================================================

(defn deny-reason
  "Return the deny-list substring present in `code`, or nil."
  [code]
  (let [s (str code)]
    (some #(when (.contains s ^String %) %) deny-substrings)))

(defn denied?
  "True when `code` contains any deny-list substring."
  [code]
  (some? (deny-reason code)))

;; ============================================================================
;; Mutating-heads classifier (gated by grant scope)
;; ============================================================================

(defn- read-top-level
  "Read all top-level forms from `code` as data. Returns nil on read error
   (caller then defaults to rejecting under the strict path)."
  [code]
  (try
    (let [r (java.io.PushbackReader. (java.io.StringReader. (str code)))]
      (loop [acc []]
        (let [v (read {:eof ::eof :read-cond :allow} r)]
          (if (= v ::eof) acc (recur (conj acc v))))))
    (catch Throwable _ nil)))

(defn- head-symbol [form]
  (when (seq? form) (first form)))

(defn mutate-reason
  "Return the first mutating top-level head symbol in `code`, or nil.
   Returns :unreadable when `code` did not parse — callers treat that
   as :mutate under the safe default."
  [code]
  (let [forms (read-top-level code)]
    (if (nil? forms)
      :unreadable
      (some #(when (contains? mutating-heads (head-symbol %))
               (head-symbol %))
            forms))))

(defn mutating?
  "True when any top-level form has a head in `mutating-heads`, or when
   the code did not parse (safe default)."
  [code]
  (some? (mutate-reason code)))

(defn classify
  "Convenience: :mutate when `mutating?`, else :read-only.
   Note: deny-list is intentionally NOT part of this — call `denied?`
   alongside for the catastrophic-action gate."
  [code]
  (if (mutating? code) :mutate :read-only))

(defn explain
  "Return a short reason string for why `code` was classified :mutate.
   Use `deny-reason` separately for deny-list diagnostics."
  [code]
  (case (mutate-reason code)
    nil          "form classified :mutate"
    :unreadable  "code did not parse"
    (str "top-level mutating form: " (mutate-reason code))))
