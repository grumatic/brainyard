;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.main-agent-hooks
  "Main-agent lifecycle hooks. Five handlers, all `:source ::main-agent` and
   self-installing at namespace load (idempotent — `register-hook!` replaces
   by id):

   - `:agent.session/created` → bootstrap the routing-log directory.
   - `:agent.ask/pre`         → snapshot max-turn-in-log so the post hook can
                                detect whether the LLM appended a line this
                                turn.
   - `:agent.tool-use/post`   → when main-agent invoked a specialist that
                                emitted `Saved <kind>: <path>` lines, append
                                bullets to pointers.md so the user can see
                                the artifact trail without main-agent having
                                to inline `main$append-pointer` calls in its
                                instruction.
   - `:agent.ask/post`        → safety-net auto-log: when main-agent emits a
                                non-blank :answer but the LLM forgot to call
                                main$append-log, infer the shape from the
                                last iteration's tool calls and write a line
                                anyway. Mirrors research-agent's auto-
                                finalize hook.
   - `:agent.session/closed`  → append a one-line summary to INDEX.md
                                covering turn count + distinct shapes seen.

   The hooks decouple routing-log discipline from the LLM's prompt — even
   when the LLM forgets to call `main$append-pointer` or `main$append-log`,
   the post hooks catch it. Every handler is wrapped in try/catch so hook
   failures never propagate up into the user-facing answer."
  (:require [ai.brainyard.agent.common.main :as main]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; ============================================================================
;; Specialist set — every other defagent reachable as a sub-call.
;;
;; Hard-coded rather than introspected from !tool-defs to keep the hook
;; insensitive to load-order races. Sources of new specialists land here as
;; part of their own integration, mirroring research_agent's approach of
;; naming the six specialists in its instruction.
;; ============================================================================

(def specialist-agents
  "Kebab-case names of every defagent main-agent might dispatch to. Used by
   the post-tool hook to decide whether a tool-call was a specialist hand-off
   (worth capturing) vs. a generic tool (no pointer to capture)."
  #{"acp-agent"
    "coact-agent"
    "config-agent"
    "eval-agent"
    "exec-agent"
    "explore-agent"
    "init-agent"
    "mcp-agent"
    "memory-agent"
    "plan-agent"
    "react-agent"
    "research-agent"
    "rlm-agent"
    "skill-agent"
    "todo-agent"
    "update-agent"
    "workflow-agent"})

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- main-agent? [agent]
  (try
    (= :main-agent (proto/defagent-type agent))
    (catch Throwable _ false)))

(defn- session-id-of [agent]
  (try (proto/session-id agent) (catch Throwable _ nil)))

(defn- result-answer
  "Extract the :answer string from a tool-call result. Specialist defagents
   return an ask map with an :answer key; tolerate strings (the result is
   already the answer) and arbitrary maps (no answer → nil)."
  [result]
  (cond
    (string? result) result
    (map? result)    (or (:answer result) (get result "answer"))
    :else            nil))

(defn- caption-for
  "One-line caption for the pointers.md bullet: `<kind> — <one-line>`.
   Falls back to just `<kind>` when no headline is derivable."
  [kind ^String answer]
  (if (clojure.string/blank? (or answer ""))
    kind
    (let [first-line (-> answer
                         (clojure.string/replace #"(?m)^Saved\s+[a-z][a-z0-9-]*:.*$" "")
                         clojure.string/trim
                         (clojure.string/split #"\n")
                         first
                         (or "")
                         (clojure.string/replace #"\s+" " ")
                         clojure.string/trim)
          capped     (if (> (count first-line) 140)
                       (str (subs first-line 0 137) "…")
                       first-line)]
      (if (clojure.string/blank? capped) kind (str kind " — " capped)))))

;; ============================================================================
;; Handler 1 — :agent.session/created → bootstrap routing-log
;; ============================================================================

(defn routing-log-bootstrap
  "Idempotent bootstrap of `.brainyard/agents/main-agent/<session-id>/`. Runs for
   every agent-session created, not just main-agent — sessions are shared
   across all agents in a TUI run, and any of them may end up invoking
   main-agent later. The dir is cheap; failure to bootstrap is logged but
   not re-thrown."
  [{:keys [session-id]}]
  (try
    (when (string? session-id)
      (main/main$bootstrap :session-id session-id))
    (catch Throwable t
      (mulog/error ::main.bootstrap-failed
                   :session-id session-id
                   :exception t))))

;; ============================================================================
;; Handler 2 — :agent.tool-use/post → auto-capture Saved <kind>: lines
;; ============================================================================

(defn capture-saved-artifacts
  "When main-agent's tool-call returns a specialist defagent's answer, parse
   every `Saved <kind>: <path>` line from it and append a bullet to
   pointers.md. Match conditions:
     - the calling agent is main-agent
     - the tool-name is a known specialist defagent
     - the result is parseable as an :answer string
   Failures are logged but never re-thrown — the user-facing tool result
   must not be affected by hook errors."
  [{:keys [agent tool-name result]}]
  (try
    (when (and (main-agent? agent)
               (string? tool-name)
               (specialist-agents tool-name))
      (let [answer (result-answer result)
            saved  (main/parse-saved-lines (or answer ""))
            sid    (session-id-of agent)]
        (when (and sid (seq saved))
          (doseq [{:keys [kind path]} saved]
            (main/main$append-pointer
             :session-id sid
             :path path
             :caption (caption-for kind answer)))
          (mulog/log ::main.captured-artifacts
                     :session-id sid
                     :tool-name tool-name
                     :saved-count (count saved)))))
    (catch Throwable t
      (mulog/error ::main.capture-failed
                   :tool-name tool-name
                   :exception t))))

;; ============================================================================
;; Auto-log fallback (handlers 3 + 4) — :agent.ask/pre + :agent.ask/post
;;
;; The instruction tells the LLM to call main$append-log after every routing
;; decision. Sonnet+ follows it on multi-iteration arcs; small/quick turns
;; (DIRECT-ANSWER greetings, CLARIFY, single META-RESUME lookups) often
;; finalize in iteration 1 without ever calling the helper. These two hooks
;; close the gap:
;;
;;   pre  → snapshot max(turn) currently in routing.log on the agent's !state
;;   post → if max(turn) didn't advance and :answer is non-blank, infer the
;;          shape from the last iteration's tool calls and append one line.
;;
;; The inferred shape is best-effort:
;;   - any specialist defagent in the last iteration's tool calls
;;       → corresponding routing-decision shape (plan-agent → :plan-author,
;;         research-agent → :research, etc.)
;;   - any other tool call                       → :tool-fetch
;;   - code-block channel only (no tool calls)   → :code-compose
;;   - otherwise (or inference failure)          → :direct-answer
;;
;; This is strictly a safety net — when the LLM appends its own line, the
;; pre/post counts differ and the post hook is a no-op.
;; ============================================================================

(def ^:private specialist->shape
  {"explore-agent"  :explore
   "update-agent"   :update
   "plan-agent"     :plan-author
   "todo-agent"     :decompose
   "exec-agent"     :execute
   "eval-agent"     :evaluate
   "research-agent" :research
   "workflow-agent" :workflow
   "rlm-agent"      :rlm
   "skill-agent"    :skill-lifecycle
   "mcp-agent"      :mcp-lifecycle
   "memory-agent"   :memory
   "init-agent"     :init
   "config-agent"   :config
   "acp-agent"      :acp})

(defn- max-turn-in-log
  [session-id]
  (try
    (->> (main/read-routing-log session-id)
         (keep :turn)
         (filter integer?)
         (reduce max 0))
    (catch Throwable _ 0)))

(defn capture-pre-turn
  "Snapshot the current max(turn) in routing.log onto the agent's !state so
   the post hook can detect whether the LLM appended a new line during the
   ask. No-op when the agent doesn't carry a !state atom."
  [{:keys [agent]}]
  (try
    (when (main-agent? agent)
      (when-let [sid (session-id-of agent)]
        (let [!st (:!state agent)]
          (when (instance? clojure.lang.IAtom !st)
            (swap! !st assoc ::pre-max-turn (max-turn-in-log sid))))))
    (catch Throwable t
      (mulog/error ::main.pre-turn-failed :exception t))))

(defn- last-iteration-tool-names
  "Best-effort extraction of tool-name strings from the latest BT iteration.
   Tolerates both ReAct (`:actions`) and CoAct (`:tool-results`) shapes."
  [agent]
  (try
    (let [st-mem  (some-> agent proto/get-bt-st-memory deref)
          iters   (:iterations st-mem)
          last-it (last iters)
          calls   (or (seq (:tool-results last-it))
                      (seq (:actions last-it))
                      [])]
      (->> calls
           (keep (fn [c] (when-let [n (:tool-name c)] (str n))))
           vec))
    (catch Throwable _ [])))

(defn- last-iteration-channel
  "Returns the channel keyword of the latest iteration when available
   (:tool / :code / :answer / :repair). Used to distinguish :code-compose
   from :direct-answer when no tool calls happened."
  [agent]
  (try
    (let [st-mem  (some-> agent proto/get-bt-st-memory deref)
          iters   (:iterations st-mem)
          last-it (last iters)]
      (when-let [ch (:channel last-it)]
        (keyword ch)))
    (catch Throwable _ nil)))

(defn- infer-shape
  "Pick a shape keyword based on what the latest iteration actually did."
  [agent]
  (let [names (last-iteration-tool-names agent)
        ch    (last-iteration-channel agent)
        spec  (some specialist->shape names)]
    (cond
      spec                spec
      (seq names)         :tool-fetch
      (= ch :code)        :code-compose
      :else               :direct-answer)))

(defn- summary-question
  "Pull a short question string out of the :input map / string. Capped at
   200 chars so a runaway prompt doesn't bloat the log."
  [input]
  (let [raw (cond
              (string? input) input
              (map? input)    (or (:question input) (get input "question") "")
              :else           (str input))
        flat (-> (str raw) (str/replace #"\s+" " ") str/trim)]
    (subs flat 0 (min 200 (count flat)))))

(defn auto-log-missing-decision
  "When main-agent emits a non-blank :answer without appending a routing.log
   line, write one anyway with an inferred shape. The LLM's explicit
   `main$append-log` call is still the primary contract — this hook only
   fires when the count of log entries didn't change between :agent.ask/pre
   and :agent.ask/post."
  [{:keys [agent input result]}]
  (try
    (when (main-agent? agent)
      (let [sid       (session-id-of agent)
            answer    (result-answer result)]
        (when (and sid
                   (string? answer)
                   (not (str/blank? answer)))
          (let [pre-turn  (or (some-> (:!state agent) deref ::pre-max-turn) 0)
                post-turn (max-turn-in-log sid)]
            (when (= pre-turn post-turn)
              (let [shape    (infer-shape agent)
                    question (summary-question input)
                    next-turn (inc pre-turn)
                    r (main/main$append-log
                       :session-id sid
                       :turn next-turn
                       :iter 1
                       :question (if (str/blank? question) "(no question captured)" question)
                       :shape shape
                       :reason "auto-logged by :agent.ask/post hook (LLM did not call main$append-log)")]
                (when (:appended r)
                  (mulog/log ::main.auto-logged-decision
                             :session-id sid
                             :turn next-turn
                             :shape shape))))))))
    (catch Throwable t
      (mulog/error ::main.auto-log-failed :exception t))))

;; ============================================================================
;; Handler 5 — :agent.session/closed → INDEX.md summary
;; ============================================================================

(defn finalize-index
  "Append a one-line summary to .brainyard/agents/main-agent/INDEX.md when the
   session closes — turn count + distinct routing-decision shapes seen.
   No-op when the session never had a routing log (main-agent was never
   invoked). Failures are logged but never re-thrown."
  [{:keys [session-id]}]
  (try
    (when (string? session-id)
      (let [log     (main/read-routing-log session-id)
            turns   (->> log (keep :turn) (apply max 0))
            shapes  (->> log (keep :shape) distinct vec)]
        (when (seq log)
          (main/main$index-append
           :session-id session-id
           :turn-count turns
           :shapes shapes))))
    (catch Throwable t
      (mulog/error ::main.finalize-index-failed
                   :session-id session-id
                   :exception t))))

;; ============================================================================
;; Self-install at namespace load. Idempotent — register-hook! replaces
;; by [event-key id].
;; ============================================================================

(defn install!
  "Register all five main-agent hooks globally. Idempotent — safe to call
   from boot. Apps can opt out via
   `(hooks/unregister-source! ::main-agent)`."
  []
  (hooks/register-hook!
   :agent.session/created
   ::routing-log-bootstrap
   routing-log-bootstrap
   :source ::main-agent)
  (hooks/register-hook!
   :agent.ask/pre
   ::capture-pre-turn
   capture-pre-turn
   :source ::main-agent
   :match  (fn [{:keys [agent]}] (main-agent? agent)))
  (hooks/register-hook!
   :agent.tool-use/post
   ::capture-saved-artifacts
   capture-saved-artifacts
   :source ::main-agent
   :match  (fn [{:keys [agent]}] (main-agent? agent)))
  (hooks/register-hook!
   :agent.ask/post
   ::auto-log-missing-decision
   auto-log-missing-decision
   :source ::main-agent
   :match  (fn [{:keys [agent]}] (main-agent? agent)))
  (hooks/register-hook!
   :agent.session/closed
   ::finalize-index
   finalize-index
   :source ::main-agent))

(install!)
