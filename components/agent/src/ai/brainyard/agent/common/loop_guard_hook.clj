;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.loop-guard-hook
  "Default `:agent.tool-use/pre` hook that detects when the LLM is stuck in a tool-call
   loop — issuing the same `(tool-name, args)` pair across consecutive
   iterations without reading the prior result.

   Returns `{:result :block ...}` on the 3rd consecutive identical call, with
   a synthesized `:answer` surfacing the most recent tool result. Smaller
   models (e.g. gpt-oss-20b) sometimes ignore prior tool-results; this stops
   the loop early instead of grinding to `max-iterations`.

   Granularity is per-call: each individual tool-call is compared against
   prior iterations' tool calls, so a multi-call iteration with one
   repeating entry is also caught.

   Reads iteration history via `proto/get-bt-st-memory` (same accessor used
   elsewhere). When that returns nil — e.g. direct `tool/call-tool` from the
   REPL with no BT context — the hook returns nil (= :allow) and gets out
   of the way."
  (:require [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]))

;; ============================================================================
;; Iteration record shape
;; ============================================================================

(defn- iter-tool-calls
  "Extract the tool-call entries from an iteration record. Handles both
   ReAct (`:actions`) and RecoAct (`:tool-results` with `:channel \"tool\"`).
   Each entry is `{:tool-name :tool-args :tool-result}`."
  [record]
  (cond
    (seq (:actions record))                      (:actions record)
    (and (= "tool" (:channel record))
         (seq (:tool-results record)))           (:tool-results record)
    :else                                        nil))

;; tool/normalize-tool-args is private but we need the same normalization
;; the dispatcher uses so prior records (LLM JSON form) compare cleanly
;; against the dispatcher's incoming flat-map :args.
(def ^:private normalize-tool-args
  (deref #'tool/normalize-tool-args))

(defn- args-match?
  "Compare a prior call's `:tool-args` (often the raw LLM vector) against
   the current dispatcher's already-normalized flat map."
  [prior-tool-args current-args]
  (= (normalize-tool-args prior-tool-args)
     current-args))

(defn- prior-record-has-call?
  "True when an iteration record contains a tool call matching
   `(tool-name, current-args)`."
  [record tool-name current-args]
  (boolean
   (some (fn [{prior-name :tool-name prior-args :tool-args}]
           (and (= prior-name tool-name)
                (args-match? prior-args current-args)))
         (iter-tool-calls record))))

(defn- iterations-history
  "Last 2 iteration records that contain tool calls, in chronological order.
   Returns nil if the agent has no BT context."
  [agent]
  (when-let [st-mem (some-> agent proto/get-bt-st-memory deref)]
    (->> (:iterations st-mem)
         (filter iter-tool-calls)
         (take-last 2)
         vec)))

(defn- latest-tool-result
  "Most recent tool-result string from an iteration record, or nil."
  [record]
  (some-> (iter-tool-calls record) last :tool-result))

;; ============================================================================
;; Hook
;; ============================================================================

(def ^:private answer-prefix
  "*(Loop guard: detected 3 consecutive identical tool calls. Stopping early. Latest tool result:)*\n\n```\n")

(def ^:private idempotent-answer-prefix
  "*(Loop guard: an idempotent read-only tool was called repeatedly with varying args. Stopping early to break the loop. Latest tool result:)*\n\n```\n")

(defn- synthesize-answer
  "Build the user-facing answer for a blocked tool call."
  ([last-record]
   (synthesize-answer last-record answer-prefix))
  ([last-record prefix]
   (str prefix
        (or (latest-tool-result last-record) "(no prior result captured)")
        "\n```")))

(def ^:private idempotent-tool-names
  "Read-only / state-reporting tools that the LLM should call AT MOST a small
   handful of times per task. Calling them >2 times across the last 2
   iterations (with any args) is a strong signal the model is thrashing — the
   prior results are already in iteration history and should be reused.

   Add new entries here when a new read-only command shows up in agent
   traces with repeated-call patterns. Mutation/exploration tools (read-file,
   grep, bash, mcp$server) are NOT here — they legitimately re-fire with
   different args."
  #{"config$read"
    "env-detect$rescan"
    "agent-runtime$config"
    "config$list-snapshots"
    "mcp$lifecycle"})

(defn- count-prior-tool-uses
  "Total times `tool-name` appears in the given iteration records (any args)."
  [priors tool-name]
  (reduce + (map (fn [r]
                   (count (filter #(= tool-name (str (:tool-name %)))
                                  (iter-tool-calls r))))
                 priors)))

(defn redundant-tool-call-decision
  "Decide whether to block the incoming tool call.

   Two paths:
   1. STRICT — same `(tool-name, args)` in EACH of the last 2 iterations.
      Blocks the 3rd consecutive identical call.
   2. IDEMPOTENT — `tool-name` is in `idempotent-tool-names` AND has been
      called >=2 times across the last 2 iterations (args may differ).
      Catches small models thrashing on `config$read` / `env-detect$rescan`
      with slightly-varying args (`:scope :user` then `:scope :project`,
      etc.) where each call returns redundant state.

   Returns nil (= allow) when neither path triggers."
  [{:keys [agent tool-name args depth]}]
  (when (zero? (or depth 0))
    (let [priors (iterations-history agent)
          tn-str (str tool-name)]
      (cond
        ;; Path 1 — strict identical-args repetition
        (and (= 2 (count priors))
             (every? #(prior-record-has-call? % tool-name args) priors))
        {:result :block
         :reason "Same tool call issued in each of the last 2 iterations."
         :answer (synthesize-answer (last priors))}

        ;; Path 2 — idempotent tool called >=2 times across last 2 iterations
        (and (= 2 (count priors))
             (contains? idempotent-tool-names tn-str)
             (>= (count-prior-tool-uses priors tn-str) 2))
        {:result :block
         :reason (str "Idempotent tool '" tn-str "' already called "
                      (count-prior-tool-uses priors tn-str)
                      " time(s) in the last 2 iterations. "
                      "Reuse the prior result from iteration history "
                      "instead of re-fetching.")
         :answer (synthesize-answer (last priors) idempotent-answer-prefix)}))))

;; ============================================================================
;; Default registration
;; ============================================================================

(defn install!
  "Register the loop-guard hook globally. Idempotent — safe to call from
   agent boot. Tag `:source :default-loop-guard` lets apps opt out via
   `(hooks/unregister-source! :default-loop-guard)`."
  []
  (hooks/register-hook!
   :agent.tool-use/pre
   ::redundant-tool-call-guard
   redundant-tool-call-decision
   :source   :default-loop-guard
   :priority 100))

;; Self-install at namespace load so anyone requiring the agent component
;; gets the loop guard for free. Idempotent — register-hook! replaces by id.
(install!)
