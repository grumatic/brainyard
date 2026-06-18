;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.auto-notify
  "Runtime-driven async notification for detached/background tasks.

   Problem: when a coact code block auto-detaches (runs past
   `:auto-background-timeout-ms`) or the LLM launches `task$run :sync false`,
   the work keeps running in the background. The intended behavior is for the
   LLM to WAIT (the iteration loop harvests the result into a later iteration)
   or to call `task$wakeup` (park the turn, auto-resume on completion). A
   low-capability model does neither — it polls `task$detail` every iteration
   until `:max-iterations`, never seeing the result.

   This namespace makes the RUNTIME drive notification, so the weak model does
   not have to. Two regimes:

   1. AUTO-ASK (agent idle). When a backgrounded task terminates and no turn is
      running, inject a resume turn automatically — the same effect as the LLM
      having called `task$wakeup`, but without its cooperation. Routes through
      `agent/submit-turn` so it serializes with user turns on the host's input
      queue. Gated to INTERACTIVE hosts (a turn-submitter is registered);
      headless `by ask` has none, so a one-shot run is never resurrected.

   2. DEFLECT + AUTO-PARK (agent running). While a turn is live and the LLM
      eagerly polls a still-running armed task, the first poll is allowed, the
      next few are deflected with a 'STILL RUNNING — you will be auto-resumed'
      nudge (the real poll is skipped), and after `:auto-park-after-polls`
      redundant polls the turn is force-parked: the tool call is BLOCKED with a
      parked answer (reusing the loop-guard's `:block` path in
      `coact-tool-dispatch-action`), and the armed task's completion resumes it.

   State is keyed by root-agent session-id. Per-task completion watches are
   registered as one-shot `:task/completed` hooks (source `:auto-notify`) that
   self-unregister when they fire. The two global handlers (deflect on
   `:agent.tool-use/pre`, flush on `:agent.ask/post`) are installed once via
   `ensure-global-hooks!` (idempotent, runtime-only — never at build time)."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.task.manager :as task-mgr]
            [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; ============================================================================
;; Lazy cross-namespace handles (avoid a load cycle: core.agent sits above the
;; task/common layer, mirroring task/commands.clj's wakeup machinery).
;; ============================================================================

(def ^:private !submit-turn
  (delay (requiring-resolve 'ai.brainyard.agent.core.agent/submit-turn)))
(def ^:private !turn-submitter
  (delay (requiring-resolve 'ai.brainyard.agent.core.agent/!turn-submitter)))
(def ^:private !get-parent-agent
  (delay (requiring-resolve 'ai.brainyard.agent.core.runtime/get-parent-agent)))

(defn- interactive-host?
  "True when a host turn-submitter is registered (TUI / web). Headless `by ask`
   leaves it nil — the gate that keeps a one-shot run from being resurrected.
   `@!turn-submitter` derefs the delay to the var; deref the var to the atom,
   then the atom to its current value (the registered fn, or nil)."
  []
  (some? (deref (deref @!turn-submitter))))

(defn- root-agent?
  "True when `agent` has no parent (sub-agents auto-close before a completion
   could fire, so auto-notify is top-level-only — mirrors task$wakeup)."
  [agent]
  (and agent
       (:!state agent)
       (nil? (@!get-parent-agent (:!state agent)))))

(defn- enabled? [agent]
  (config/get-config agent :enable-auto-task-notify))

(defn- session-key [agent]
  (or (try (proto/session-id agent) (catch Throwable _ nil))
      (:session-id agent)
      (:agent-id agent)
      :default))

(defn- agent-running? [agent]
  (= :running (:status @(:!state agent))))

(defn- bt-st-memory [agent]
  (or (try
        (some-> ^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent
                .get-bt-st-memory)
        (catch Throwable _ nil))
      ;; Test/mocking fallback: a plain-map agent may carry the atom directly.
      (:bt-st-memory agent)))

;; ============================================================================
;; State (keyed by session-id)
;; ============================================================================

;; session-id -> #{task-id-keyword}: tasks that terminated but have not yet been
;; surfaced to the LLM (idle auto-ask consumes the whole set at once).
(defonce ^:private !inbox (atom {}))
;; session-id -> bool: CAS guard so at most one auto-ask is in flight per session.
(defonce ^:private !resume-armed (atom {}))
;; session-id -> {agent-id n}: shared auto-resume budget (bounds a park→resume→park
;; loop). Kept separate from task$wakeup's own counter but uses the same cap key.
(defonce ^:private !resume-counts (atom {}))
;; Global set of task-id keywords the runtime has promised to auto-resume —
;; scopes deflection precisely to armed tasks.
(defonce ^:private !armed-tasks (atom #{}))

(def ^:private default-max-resumes
  "Per-session ceiling on runtime auto-asks. Bounds a pathological
   park→resume→park chain. Overridable via :max-wakeups-per-session config."
  20)

(defn- max-resumes [agent]
  (or (config/get-config agent :max-wakeups-per-session) default-max-resumes))

(defn- budget-left? [agent]
  (< (get-in @!resume-counts [(session-key agent) (:agent-id agent)] 0)
     (max-resumes agent)))

(defn- bump-budget! [agent]
  (swap! !resume-counts update-in [(session-key agent) (:agent-id agent)] (fnil inc 0)))

(defn armed?
  "True when `task-id` (keyword) is an armed auto-notify task."
  [task-id]
  (contains? @!armed-tasks task-id))

;; ============================================================================
;; Resume decision
;; ============================================================================

(defn- resume-message
  "Synthesized system-turn text that re-enters the loop after backgrounded
   task(s) terminated. Mirrors task/commands.clj `wakeup-message` shape so the
   model treats it as a continuation, not a fresh question."
  [tasks]
  (let [ids (str/join ", " (map (comp clojure.core/name :id) tasks))
        any-fail? (some #(not= :completed (:status %)) tasks)]
    (str "[task-complete] Background task(s) " ids " terminated"
         (when any-fail? " (one or more did NOT complete successfully)")
         ". Their resolved results are now available — review them"
         " (already folded into your iterations, or via `task$detail`)"
         " and continue from where you left off"
         (when any-fail? "; handle the non-success status before proceeding")
         ".")))

(defn mark-surfaced!
  "Remove `task-ids` (keywords or strings) from the session inbox once the live
   loop has shown them to the LLM (harvest / resolve). Prevents the turn-settle
   flush from re-announcing an already-seen result. No-op when agent is nil."
  [agent task-ids]
  (when (and agent (seq task-ids))
    (let [ks (set (map #(if (keyword? %) % (keyword %)) task-ids))]
      (swap! !inbox update (session-key agent)
             (fn [s] (not-empty (apply disj (or s #{}) ks)))))))

(defn maybe-resume!
  "If the session inbox is non-empty and no turn is running, inject ONE
   coalesced auto-ask covering every inbox task, then clear the inbox. CAS guard
   keeps a single resume in flight. While a turn IS running, do nothing — the
   live loop's harvest owns surfacing (and `mark-surfaced!` keeps the inbox in
   sync)."
  [agent]
  (when (and agent (enabled? agent) (interactive-host?) (root-agent? agent))
    (let [sk (session-key agent)
          ids (get @!inbox sk)]
      (when (and (seq ids)
                 (not (agent-running? agent))
                 (budget-left? agent)
                 ;; CAS-arm: only the thread that flips false→true proceeds.
                 (let [m (swap! !resume-armed update sk
                                (fn [v] (if v v ::claimed)))]
                   (= ::claimed (get m sk))))
        (let [mgr   (task-mgr/get-default-manager)
              tasks (keep (fn [id] (when mgr (tp/get-task mgr id))) ids)
              ;; Fall back to bare ids when the task row is already GC'd.
              tasks (if (seq tasks)
                      tasks
                      (map (fn [id] {:id id :status :completed}) ids))]
          (swap! !inbox dissoc sk)
          (bump-budget! agent)
          (hooks/fire! :agent.task/auto-resumed
                       {:agent agent
                        :task-ids (mapv (comp clojure.core/name :id) tasks)
                        :source :auto-resume})
          (mulog/info ::auto-resume-submitted
                      :session sk
                      :task-ids (mapv (comp clojure.core/name :id) tasks))
          (try
            (@!submit-turn agent (resume-message tasks) {:source :auto-resume})
            (finally
              ;; Disarm so a later batch can resume again. The submitted turn
              ;; serializes on the host queue, so re-arming here is safe.
              (swap! !resume-armed dissoc sk))))))))

(defn- record-completion!
  "A watched task terminated: add it to the session inbox and try to resume."
  [agent task-id]
  (swap! !armed-tasks disj task-id)
  (swap! !inbox update (session-key agent) (fnil conj #{}) task-id)
  (maybe-resume! agent))

;; ============================================================================
;; Arming
;; ============================================================================

(defn- watch-handler-id [task-id]
  (keyword "auto-notify-watch" (clojure.core/name task-id)))

(defn arm-auto-notify!
  "Promise to auto-resume `agent` when `task-id` (keyword) terminates. Registers
   a one-shot `:task/completed` hook scoped to this task. No-op unless the
   feature is enabled, the host is interactive, the agent is root, and budget
   remains. Idempotent per task-id (re-arming replaces the prior watch)."
  [agent task-id]
  (when (and agent task-id
             (enabled? agent)
             (interactive-host?)
             (root-agent? agent)
             (budget-left? agent))
    (swap! !armed-tasks conj task-id)
    (let [hid (watch-handler-id task-id)]
      (hooks/register-hook!
       :task/completed hid
       (fn [{:keys [task]}]
         ;; One-shot: tear down before acting so a re-fire can't double-record.
         (hooks/unregister-hook! :task/completed hid)
         (record-completion! agent (:id task)))
       :match (fn [{:keys [task]}] (= task-id (:id task)))
       :source :auto-notify))
    (mulog/info ::armed :task-id (clojure.core/name task-id)
                :session (session-key agent))
    task-id))

(defn arm-pending-entries!
  "Arm auto-notify for every `:status :pending` code-eval entry in `entries`
   (the auto-detach path). Convenience for `coact-code-eval-action`."
  [agent entries]
  (doseq [{:keys [status task-id]} entries
          :when (and (= :pending status) task-id)]
    (arm-auto-notify! agent (keyword task-id))))

;; ============================================================================
;; Deflect + auto-park (agent running)
;; ============================================================================

(def ^:private poll-tools
  "Tool names whose target is a single task and which the weak model spams."
  #{"task$detail" "task$wait"})

(defn- arg-task-id
  "Extract :task-id from a normalized tool-args map (string or keyword keys)."
  [args]
  (when (map? args)
    (or (:task-id args) (get args "task-id"))))

(defn- note-poll!
  "Increment and return the per-turn poll count for `task-id` on `agent`."
  [agent task-id]
  (if-let [st (bt-st-memory agent)]
    (-> (swap! st update-in [:auto-notify/polls task-id] (fnil inc 0))
        (get-in [:auto-notify/polls task-id]))
    1))

(defn reset-poll-counts!
  "Clear per-turn poll counters. Called at turn/iteration init."
  [st-memory]
  (when st-memory
    (swap! st-memory dissoc :auto-notify/polls)))

(defn- deflection-result [tid]
  {:status  "running"
   :task-id tid
   :message (str "task-" tid " STILL RUNNING — stopped polling to save "
                 "iterations. You will be AUTO-RESUMED automatically when it "
                 "completes; do NOT poll again. Either answer briefly and stop, "
                 "or do unrelated work while it runs.")})

(defn- deflect-decision
  "Gated `:agent.tool-use/pre` handler. Returns an :allow / :replace / :block
   decision for polls of a still-running armed task; :allow otherwise."
  [{:keys [agent tool-name args]}]
  (let [tid-str (some-> (arg-task-id args) str)]
    (if-not (and agent
                 (enabled? agent)
                 (root-agent? agent)
                 (contains? poll-tools (str tool-name))
                 (not (str/blank? tid-str))
                 (armed? (keyword tid-str)))
      hooks/allow-decision
      (let [tid    (keyword tid-str)
            mgr    (task-mgr/get-default-manager)
            task   (when mgr (tp/get-task mgr tid))
            status (:status task)]
        (if (not= :running status)
          ;; Terminal (or gone): let the real poll through so the LLM gets the
          ;; result, and drop the arm — nothing left to deflect.
          (do (swap! !armed-tasks disj tid) hooks/allow-decision)
          (let [n        (note-poll! agent tid)
                park-at  (or (config/get-config agent :auto-park-after-polls) 2)]
            (cond
              ;; First poll: allow once so the model sees real liveness.
              (<= n 1) hooks/allow-decision

              ;; Threshold reached: force-park the turn. Ensure the task is
              ;; armed (it already is — but harmless), then BLOCK with a parked
              ;; answer — coact-tool-dispatch-action terminates on :hook-blocked.
              (>= n park-at)
              (do (arm-auto-notify! agent tid)
                  (hooks/fire! :agent.iteration/auto-parked
                               {:agent agent :task-id tid-str :polls n})
                  (mulog/info ::auto-parked :task-id tid-str :polls n)
                  {:result :block
                   :reason (str "auto-parked after " n " redundant polls of "
                                tid-str)
                   :answer (str "Parked — task-" tid-str " is still running. "
                                "I'll be resumed automatically when it "
                                "completes and will continue then.")})

              ;; Middle polls: deflect (skip the real poll) with a nudge.
              :else
              {:result :replace
               :reason (str "deflected redundant poll of " tid-str)
               :replacement (deflection-result tid-str)})))))))

;; ============================================================================
;; Global hook installation (runtime-only, idempotent)
;; ============================================================================

(defonce ^:private !installed (atom false))

(defn ensure-global-hooks!
  "Install the always-on handlers once per process at RUNTIME (never at build
   time — guarded by a runtime atom, so native-image bakes `false` and the first
   real turn installs). Safe to call on every turn.

   Installs only on an interactive host (a turn-submitter is registered): the
   handlers are inert without one (auto-resume can't fire, so nothing to deflect
   or park toward), and skipping keeps headless `by ask` / test runs free of the
   global tool-use/pre + ask/post handlers. A host that registers its submitter
   before the first turn installs on that turn."
  []
  (when (and (interactive-host?)
             (compare-and-set! !installed false true))
    ;; Deflect / auto-park on poll-spam (gated).
    (hooks/register-hook!
     :agent.tool-use/pre ::deflect-poll
     deflect-decision
     :priority 50            ;; run before generic observers
     :source :auto-notify)
    ;; Turn-settle flush: a task that completed during the dying breath of a
    ;; turn (after the last harvest) gets surfaced on the next, auto-asked turn.
    (hooks/register-hook!
     :agent.ask/post ::flush-inbox
     (fn [{:keys [agent]}] (maybe-resume! agent))
     :source :auto-notify)
    ;; Session teardown: drop per-session state so a long-lived TUI process
    ;; doesn't accumulate stale inboxes across sessions.
    (hooks/register-hook!
     :agent.session/closed ::session-cleanup
     (fn [{:keys [session-id]}]
       (when session-id
         (swap! !inbox dissoc session-id)
         (swap! !resume-armed dissoc session-id)
         (swap! !resume-counts dissoc session-id)))
     :source :auto-notify)
    (mulog/info ::global-hooks-installed))
  nil)

(defn reset-state!
  "Clear all auto-notify state and uninstall global hooks. For tests."
  []
  (reset! !inbox {})
  (reset! !resume-armed {})
  (reset! !resume-counts {})
  (reset! !armed-tasks #{})
  (reset! !installed false)
  (hooks/unregister-source! :auto-notify))
