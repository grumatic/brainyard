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
   - `:agent.ask/post`        → record the per-turn routing line. The line is
                                HOOK-DERIVED (not LLM-constructed): routed-to
                                from the turn's specialist dispatch, shape from
                                specialist→shape / the `Routing:` answer line /
                                a channel fallback, artifact from the surfaced
                                `Saved <kind>:` path, reason from the model's
                                one-sentence routing decision. This is the SOLE
                                writer of routing.log (main-agent no longer
                                calls main$append-log).
   - `:agent.session/closed`  → append a one-line summary to INDEX.md
                                covering turn count + distinct shapes seen.

   The hooks own routing-log discipline entirely — the LLM just routes + states
   a reason; the trail records itself. Every handler is wrapped in try/catch so
   hook failures never propagate up into the user-facing answer."
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
    "meta-agent"
    "plan-agent"
    "react-agent"
    "research-agent"
    "rlm-agent"
    "skill-agent"
    "todo-agent"
    "tool-agent"
    "edit-agent"
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
;; Routing-line recorder (handlers 3 + 4) — :agent.ask/pre + :agent.ask/post
;;
;; Lightweight redesign: the routing line is HOOK-DERIVED, not LLM-constructed.
;; main-agent no longer calls main$append-log; this hook is the SOLE writer of
;; the per-turn routing.log line. It runs on every main-agent turn:
;;
;;   pre  → snapshot max(turn) in routing.log on the agent's !state (so the
;;          post hook knows the next turn number + can no-op a double-fire).
;;   post → derive the line from the turn and append it:
;;            routed-to → the dispatched specialist (scanned from the turn's
;;                        tool calls)
;;            shape     → specialist→shape when a specialist ran; else the
;;                        `Routing: <shape> — <reason>` answer line for self-
;;                        answered moves; else a channel fallback
;;                        (code → :code-compose, tool → :tool-fetch,
;;                         else :direct-answer); coerced to :unspecified if
;;                        unknown (never fails the turn)
;;            artifact  → the surfaced `Saved <kind>: <path>` line
;;            reason    → the `Routing:` reason, else the first prose line of
;;                        the answer (the model's one-sentence routing decision)
;; ============================================================================

(def ^:private specialist->shape
  {"explore-agent"  :explore
   "edit-agent"     :update
   "plan-agent"     :plan-author
   "todo-agent"     :decompose
   "exec-agent"     :execute
   "eval-agent"     :evaluate
   "research-agent" :research
   "workflow-agent" :workflow
   "rlm-agent"      :rlm
   "skill-agent"    :skill-lifecycle
   "mcp-agent"      :mcp-lifecycle
   "tool-agent"     :tool-lifecycle
   "meta-agent"     :agent-lifecycle
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

(defn- all-iteration-tool-names
  "Best-effort: tool-name strings across ALL of the turn's BT iterations
   (not just the last), in order. Tolerates ReAct (`:actions`) and CoAct
   (`:tool-results`) shapes. Used to find the dispatched specialist even when
   the dispatch happened a few iterations before the final answer."
  [agent]
  (try
    (let [st-mem (some-> agent proto/get-bt-st-memory deref)
          iters  (:iterations st-mem)]
      (->> iters
           (mapcat (fn [it] (concat (:tool-results it) (:actions it))))
           (keep (fn [c] (when-let [n (:tool-name c)] (str n))))
           vec))
    (catch Throwable _ [])))

(defn- last-iteration-channel
  "Returns the channel keyword of the latest iteration when available
   (:tool / :code / :answer / :repair). Used to distinguish :code-compose
   from :direct-answer when no specialist ran."
  [agent]
  (try
    (let [st-mem  (some-> agent proto/get-bt-st-memory deref)
          last-it (last (:iterations st-mem))]
      (when-let [ch (:channel last-it)]
        (keyword ch)))
    (catch Throwable _ nil)))

(defn- routed-to-of
  "The specialist defagent main-agent dispatched this turn — the LAST specialist
   tool-call across the turn's iterations, or nil for a self-answered turn."
  [agent]
  (->> (all-iteration-tool-names agent)
       (filter specialist-agents)
       last))

(def ^:private routing-answer-re
  "Matches the self-answered-move signal `Routing: <shape> — <reason>` the model
   adds to its :answer (separator may be em-dash, hyphen, or colon; reason
   optional). `(?im)` so it can be found anywhere in the answer body."
  #"(?im)^\s*Routing:\s*([a-z][a-z0-9-]+)\b\s*(?:[—:-]+\s*(.*\S))?\s*$")

(defn- extract-routing-answer-line
  "Parse a `Routing: <shape> — <reason>` line from the answer → `{:shape kw
   :reason str-or-nil}`, or nil when absent."
  [^String answer]
  (when (string? answer)
    (when-let [[_ shape reason] (re-find routing-answer-re answer)]
      {:shape  (keyword shape)
       :reason (some-> reason str/trim not-empty)})))

(defn- first-prose-line
  "First non-blank line of the answer that isn't a `Saved <kind>:` / `Routing:`
   marker, a heading, table, or fence — the model's one-sentence routing
   decision. Collapsed + capped. nil when none."
  [^String answer]
  (->> (str/split-lines (or answer ""))
       (map str/trim)
       (remove str/blank?)
       (remove #(re-matches #"(?i)^(saved\s+[a-z][a-z0-9-]*:|routing:|#|\||```).*" %))
       first
       (#(when % (let [flat (str/replace % #"\s+" " ")]
                   (subs flat 0 (min 200 (count flat))))))))

(defn- derive-shape
  "Resolve the routing shape: a dispatched specialist is authoritative
   (specialist→shape); else the `Routing:` answer-line shape (self-answered);
   else a channel fallback. Coerced to a known shape (:unspecified otherwise)."
  [agent ans-line routed-to]
  (main/coerce-shape
   (cond
     routed-to        (specialist->shape routed-to)
     (:shape ans-line) (:shape ans-line)
     :else            (let [ch (last-iteration-channel agent)]
                        (cond
                          (= ch :code)                          :code-compose
                          (seq (all-iteration-tool-names agent)) :tool-fetch
                          :else                                 :direct-answer)))))

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

(defn record-routing-line
  "Primary writer of the per-turn routing.log line (hook-derived, not LLM-
   constructed — main-agent no longer calls main$append-log). On main-agent's
   :agent.ask/post: derive routed-to (dispatched specialist), shape
   (specialist→shape / `Routing:` answer line / channel fallback), artifact
   (surfaced `Saved <kind>:` path), and reason (`Routing:` reason or the first
   prose line), then append ONE line. Defensive — failures logged, never
   re-thrown."
  [{:keys [agent input result]}]
  (try
    (when (main-agent? agent)
      (let [sid    (session-id-of agent)
            answer (result-answer result)]
        (when (and sid (string? answer) (not (str/blank? answer)))
          (let [pre-turn  (or (some-> (:!state agent) deref ::pre-max-turn) 0)
                post-turn (max-turn-in-log sid)]
            ;; main-agent no longer writes its own line, so this is normally the
            ;; only write per turn; the guard just no-ops a double-fire.
            (when (= pre-turn post-turn)
              (let [ans-line  (extract-routing-answer-line answer)
                    routed-to (routed-to-of agent)
                    shape     (derive-shape agent ans-line routed-to)
                    artifact  (first (map :path (main/parse-saved-lines answer)))
                    reason    (or (:reason ans-line)
                                  (first-prose-line answer)
                                  "(routing reason not stated)")
                    question  (summary-question input)
                    next-turn (inc pre-turn)
                    r (main/append-log!
                       :session-id sid
                       :turn next-turn
                       :iter 1
                       :question (if (str/blank? question) "(no question captured)" question)
                       :shape shape
                       :routed-to routed-to
                       :artifact artifact
                       :reason reason)]
                (when (:appended r)
                  (mulog/log ::main.routing-line-recorded
                             :session-id sid :turn next-turn
                             :shape shape :routed-to routed-to
                             :artifact (boolean artifact)))))))))
    (catch Throwable t
      (mulog/error ::main.routing-line-failed :exception t))))

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
   ::record-routing-line
   record-routing-line
   :source ::main-agent
   :match  (fn [{:keys [agent]}] (main-agent? agent)))
  (hooks/register-hook!
   :agent.session/closed
   ::finalize-index
   finalize-index
   :source ::main-agent))

(install!)
