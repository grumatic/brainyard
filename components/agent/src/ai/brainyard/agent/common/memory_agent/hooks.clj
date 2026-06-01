;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.memory-agent.hooks
  "Hook handlers for the memory-agent.

   - write-guard (Phase 1) — `:agent.tool-use/pre` decision that
     blocks non-memory-agent callers from invoking the gated `memory$*`
     mutation primitives. Other agents reach the same effects via
     `(call-tool \"memory-agent\" {...})`.
   - essence-capture (Phase 3) — `:agent.ask/post` observer that
     fire-and-forget calls memory-agent with `:op :essence` after a
     turn finishes, gated on `:enable-memory-essence` config. Opt-in
     per agent-type (root coact-agent and research-agent enable it;
     specialists stay off)."
  (:require [ai.brainyard.agent.common.memory-agent.commands :as cmds]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.mulog.interface :as mulog]))

(def ^:const memory-agent-type
  "The defagent-type keyword used by `memory-agent`. Comparing
   `(proto/defagent-type agent)` against this lets the guard tell
   whether the caller is *us*."
  :memory-agent)

(defn- memory-agent-instance?
  "True when the running agent is an instance of `memory-agent`.
   Agent-ids follow the `:<defagent-type>/<suffix>` convention; we
   accept any namespaced keyword whose namespace is \"memory-agent\".
   Bare (non-namespaced) agent-ids in tests are accepted only when the
   id equals `:memory-agent` itself."
  [agent]
  (when agent
    (let [aid (some-> agent proto/agent-id)]
      (or (= aid memory-agent-type)
          (and (keyword? aid)
               (= "memory-agent" (namespace aid)))))))

(defn write-guard-decision
  "Decide whether to block a `memory$*` mutation call.

   Returns nil (= allow) when:
     - the tool isn't in `write-guarded-tools`
     - no agent is currently bound (REPL / direct invoke-tool)
     - the calling agent IS memory-agent

   Otherwise returns a `{:result :block ...}` decision map redirecting
   the caller to `call-tool \"memory-agent\"`."
  [{:keys [agent tool-name]}]
  (when (and (contains? cmds/write-guarded-tools (str tool-name))
             agent
             (not (memory-agent-instance? agent)))
    {:result :block
     :reason (format "%s is gated to memory-agent. Reach it via (memory-agent {:op ...})."
                     tool-name)
     :answer (format "(memory-agent write guard) Tool '%s' is only callable from inside memory-agent. Use (memory-agent {:op ...}) instead."
                     tool-name)}))

(defn install-write-guard!
  "Register the write-guard hook globally. Idempotent —
   `register-hook!` replaces by id. Tag `:source :memory-agent` lets
   apps tear down all memory-agent hooks via
   `(hooks/unregister-source! :memory-agent)`."
  []
  (hooks/register-hook!
   :agent.tool-use/pre
   ::write-guard
   write-guard-decision
   :source   :memory-agent
   :priority 200))

;; ============================================================================
;; Essence-capture hook — :agent.ask/post
;; ============================================================================

(defn- root-agent?
  "True when `agent` has no parent — only root agents drive essence
   capture; sub-agents would double-fire on the same session turn."
  [agent]
  (try
    (nil? (get-in @(:!state agent) [:runtime :parent-agent]))
    (catch Exception _ false)))

(defn- essence-eligible?
  "True when the just-finished agent should trigger essence capture:
     1. Not memory-agent itself (would loop on its own turns).
     2. Is a root agent (sub-agents share a session — root handles it).
     3. `:enable-memory-essence` resolves true via `config/get-config`
        (per-agent override → global config → schema default)."
  [agent]
  (when agent
    (try
      (let [aid (some-> agent proto/agent-id)
            ag-type (and (keyword? aid) (namespace aid))]
        (cond
          (= "memory-agent" ag-type) false
          (not (root-agent? agent))  false
          :else
          (boolean (config/get-config agent :enable-memory-essence))))
      (catch Exception _ false))))

(defn- agent-turn-id
  "Best-effort lookup of the per-agent turn-id from the agent's
   st-memory-init. Returns 0 when absent."
  [agent]
  (or (try
        (some-> agent :!state deref :st-memory-init deref :turn-id)
        (catch Exception _ nil))
      0))

(defn- agent-total-turns
  "Best-effort lookup of session total-turns. Returns 0 when absent."
  [agent]
  (or (try
        (some-> agent :!session deref :total-turns)
        (catch Exception _ nil))
      0))

(defn essence-capture-handler
  "`:agent.ask/post` handler that fire-and-forget delegates essence
   capture to memory-agent. Never blocks the caller and never propagates
   exceptions — essence is a best-effort lift, not a critical path.

   The actual `call-tool` is wrapped in `future` so:
     - the user's next turn starts immediately (we don't wait for the
       LLM call inside memory-agent)
     - a failed essence call doesn't tank the parent ask"
  [{:keys [agent input result]}]
  (when (essence-eligible? agent)
    (let [aid       (some-> agent proto/agent-id)
          uid       (some-> agent proto/user-id)
          sid       (some-> agent proto/session-id)
          turn-id   (agent-turn-id agent)
          total     (agent-total-turns agent)
          hint      (or (when (map? result) (:answer result))
                        (when (string? result) result)
                        "")
          ;; Truncate the hint — memory-agent will load real messages
          ;; from L2; we don't need the full answer here.
          hint'     (if (> (count hint) 400) (subs hint 0 400) hint)]
      (future
        (try
          ;; Use `invoke-tool` (bare dispatch, no hooks/permissions/schema
          ;; coercion) since this is an internal call from the hook, not an
          ;; LLM-facing tool call. Pass `:parent-agent` AND `:agent-session`
          ;; explicitly — invoke-tool skips the do-call-tool--agent step
          ;; that would normally derive agent-session from the parent. The
          ;; hook fires inside a `future`, so `*current-agent*` is unbound
          ;; and we cannot rely on dynamic-var fallback either.
          ;;
          ;; `:ask-async? true` forces run-agent into the ask-async branch
          ;; even though we set :parent-agent. Otherwise sub-agent calls
          ;; default to sync ask, which would tie up the future thread for
          ;; the entire memory-agent BT loop (10–30s). With ask-async the
          ;; sub-agent dispatches on its own clojure-agent thread and the
          ;; future returns immediately. `:auto-close?` keeps the existing
          ;; sub-agent cleanup contract (attach-auto-close-watch! closes
          ;; the instance when its clj-ref :output transitions).
          (tool/invoke-tool :memory-agent
                            :op            "essence"
                            :session-id    (str sid)
                            :turn-id       turn-id
                            :total-turns   total
                            :hint          hint'
                            :parent-agent  agent
                            :agent-session {:user-id uid :session-id sid}
                            :ask-async?    true
                            :auto-close?   true)
          (catch Exception e
            (mulog/warn ::essence-capture-failed
                        :agent-id aid
                        :user-id uid
                        :session-id sid
                        :exception e)
            nil)))))
  nil)

(defn install-essence-capture!
  "Register the essence-capture hook globally. Idempotent."
  []
  (hooks/register-hook!
   :agent.ask/post
   ::essence-capture
   essence-capture-handler
   :source   :memory-agent
   :priority 50))

;; Self-install at namespace load. Both hooks register by stable id and
;; replace themselves on subsequent loads, so requiring this ns
;; multiple times is safe.
(install-write-guard!)
(install-essence-capture!)
