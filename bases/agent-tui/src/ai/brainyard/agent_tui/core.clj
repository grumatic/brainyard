;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.core
  "Public TUI API for interactive agent sessions.
   Provides start!/ask/stop! for REPL use and run! for terminal interactive loop.

   Usage (REPL):
     (require '[ai.brainyard.agent-tui.core :as tui])
     (tui/start! :coact-agent :lm-provider :ollama)
     (tui/ask \"What MCP servers are available?\")
     (tui/stop!)

   Usage (Terminal):
     (tui/run! :coact-agent :lm-provider :ollama)"
  (:refer-clojure :exclude [run!])
  (:require [ai.brainyard.agent-tui.session :as tui-session]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.agent-tui.log :as tui-log]
            [ai.brainyard.agent-tui.help-tips :as help-tips]
            [ai.brainyard.agent-tui.layout :as layout]
            [ai.brainyard.agent-tui.dirs :as dirs]
            [ai.brainyard.agent-tui.helpers :as helpers]
            [ai.brainyard.agent-tui.input :as input]
            [ai.brainyard.agent-tui.permissions :as permissions]
            [ai.brainyard.agent-tui.terminal :as terminal]
            [ai.brainyard.agent-tui.tmux-side :as tmux-side]
            [ai.brainyard.agent-tui.autocomplete :as autocomplete]
            [ai.brainyard.agent-tui.commands :as commands]
            [ai.brainyard.agent-tui.persist-bridge :as persist-bridge]
            [ai.brainyard.agent.interface.tui.format :as fmt]
            [ai.brainyard.agent.interface.tui.ansi :as ansi]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent-tui-persist.interface :as persist]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.clj-nrepl.interface :as clj-nrepl]
            [ai.brainyard.clj-sandbox.interface :as clj-sandbox]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]))

;; ============================================================================
;; Persistence root wiring
;; ============================================================================
;;
;; The persist component is dependency-free and cannot resolve project-dir, so
;; the base — which depends on both `agent` and `persist` — wires the
;; project-scoped sessions root once, here, at namespace load. The thunk defers
;; to call time, so EVERY entry path (`start!`/`run!` REPL use, the cli-matic
;; `main`, the tmux host, control-server connections, GC sweeps) lands under
;; `<project>/.brainyard/sessions/` with no per-entry-point injection
;; discipline. `agent/sessions-root` is the single authority (honors
;; `-C`/`BY_PROJECT_DIR` and `config/*sessions-root-override*`).
(persist/set-root-resolver! (fn [] (agent/sessions-root)))

;; ============================================================================
;; Queue State
;; ============================================================================

(defonce !input-queue (atom nil))
(defonce !session-store (agent/create-session-store))

;; ============================================================================
;; clj-nrepl Server (opt-in)
;; ============================================================================
;;
;; The in-process nREPL server backs `code$eval :backend :nrepl`. Off by
;; default. Operators enable it durably via the brainyard runtime config
;; (.brainyard/config.edn, [:agent :config :nrepl-enabled?] true) or
;; transiently via BY_NREPL_ENABLED=true (the env-fallback layer of
;; the :nrepl-enabled? schema key). The port (:nrepl-port, 0 = ephemeral)
;; and grant (:nrepl-grant, e.g. "read-only:15m") follow the same chain.
;; See docs/design/clj-nrepl-eval.md §5 / §8.
(defonce !nrepl-server (atom nil))

(defn- tui-confirm-mutation
  "v1 confirm-fn (visibility-only) — emits a scrollback notice and a mulog
   audit on the first mutating eval per session, then auto-approves.
   Replaces clj-nrepl's default `::no-confirm-fn-installed` silent allow.
   See §9 #4 of docs/live-debugging.md: the literal gap was that no host
   fn was installed, so `:mutate` grants effectively passed without
   visibility. v1 surfaces mutations to the operator watching the TUI
   without blocking them; a future v2 will plumb popup.clj's
   questionnaire as a real interactive Y/n gate that blocks the nREPL
   thread until the operator responds."
  [{:keys [session code]}]
  (let [flat       (str/replace (or code "<no-code>") #"\s+" " ")
        preview    (if (> (count flat) 120) (str (subs flat 0 117) "...") flat)
        sess-str   (if (nil? session) "<in-process>" (str session))
        sess-short (if (> (count sess-str) 12) (subs sess-str 0 12) sess-str)]
    (mulog/info ::mutation-allowed
                :session session
                :code-preview preview
                :decision :auto-allow-v1)
    (tui-session/emit!
     (ansi/warning
      (str "[clj-nrepl] mutating eval auto-allowed (v1 visibility, session="
           sess-short ", code: " preview ")")))
    true))

(defn- emit-turn-complete!
  "Driver settle-detection signal — fires on every `:agent.ask/post`
   and emits the turn-complete event on two surfaces, both with stable
   payload shape (`:ts :agent-id :session-id :input-preview
   :result-length`):

     1. A uniform `:ai.brainyard.agent-tui.core/turn-complete` mulog
        event (v1, regardless of agent type).
     2. An atomic per-session stamp file at
        `<project>/.brainyard/sessions/<session-id>/turn.complete` (v2) — small
        EDN file, best-effort write, non-fatal on failure. Drivers can
        `inotifywait -e close_write <stamp>` (Linux) or
        `fswatch -1 <stamp>` (macOS) and read the stamp's EDN payload
        to know which turn completed without parsing the multi-MB
        mulog log.

   Drivers filter by the `:agent-id` they sent input to, since
   sub-agent invocations also fire `:agent.ask/post`. See §4 (driver
   primitives) and §9 #2 of docs/live-debugging.md."
  [{:keys [agent input result]}]
  (let [agent-id   (:agent-id agent)
        session-id (some-> agent :!session deref :session-id)
        input-prev (when input
                     (let [s (str input)]
                       (if (> (count s) 80) (str (subs s 0 77) "...") s)))
        result-len (count (str (or result "")))
        payload    {:ts            (System/currentTimeMillis)
                    :agent-id      agent-id
                    :session-id    session-id
                    :input-preview input-prev
                    :result-length result-len}]
    (mulog/info ::turn-complete
                :agent-id      agent-id
                :session-id    session-id
                :input-preview input-prev
                :result-length result-len)
    (when session-id
      (try
        ;; Resolve through the persist layer so the stamp lands under the
        ;; project-scoped sessions root (persist/*root-resolver*), not a user-global dir.
        (let [stamp (persist/session-file session-id "turn.complete")]
          (spit stamp (pr-str payload)))
        (catch Exception e
          (mulog/warn ::turn-stamp-write-failed
                      :session-id session-id
                      :error      (.getMessage e)))))))

(defn- start-nrepl-server-if-enabled!
  "Start the loopback nREPL server when :nrepl-enabled? resolves true.
   Writes the bound port to ~/.brainyard/nrepl-port (0600) for external
   CIDER attach. Failures are non-fatal — the TUI continues without it."
  []
  (when (and (agent/get-config :nrepl-enabled?) (not @!nrepl-server))
    (try
      (let [port      (agent/get-config :nrepl-port)
            _         (clj-nrepl/cleanup-stale-ports!)
            port-file (.getAbsolutePath
                       ^java.io.File (clj-nrepl/instance-port-file "by"))
            srv (clj-nrepl/start-server!
                 :bind "127.0.0.1"
                 :port (or port 0)
                 :port-file port-file)]
        (reset! !nrepl-server srv)
        (clj-nrepl/maybe-grant-from-env! (agent/get-config :nrepl-grant))
        (clj-nrepl/set-confirm-fn! tui-confirm-mutation)
        (mulog/info ::nrepl-bootstrapped
                    :port (:port srv)
                    :grant (clj-nrepl/scope)))
      (catch Exception e
        (mulog/warn ::nrepl-bootstrap-failed :error (.getMessage e))))))

(defn- stop-nrepl-server!
  []
  (when-let [srv @!nrepl-server]
    (try (clj-nrepl/stop-server! srv) (catch Exception _))
    (reset! !nrepl-server nil)))

;; ============================================================================
;; Resume notice — compact one-liner shown in place of the welcome banner
;; when `:resumed?` is set. Lets the user confirm WHICH persisted session
;; was rehydrated without re-printing the agents list and disclaimer that
;; have not changed since the last run.
;; ============================================================================

(defn- format-age-since
  "Compact human age for a millis-since-epoch timestamp. Returns nil
   for nil input."
  [ms]
  (when ms
    (let [age  (max 0 (- (System/currentTimeMillis) (long ms)))
          mins (quot age 60000)
          hrs  (quot mins 60)
          days (quot hrs 24)]
      (cond
        (>= days 1) (str days "d ago")
        (>= hrs 1)  (str hrs "h ago")
        (>= mins 1) (str mins "m ago")
        :else       "just now"))))

(defn- format-resume-notice
  "Build a resume notice — first line identity + age + per-process basics,
   second line (only when restore actually surfaced something) the carried-
   over cost and def-survival summary.

   Optional fields (all from the TUI's resume hydration step):
     :restored-cost   running total-cost in USD from the hydrated tracker
     :restored-defs   count of vars re-seeded into the eager sandbox
     :lost-defs       count of vars pruned at persist (functions / non-EDN)

   `:total-turns` and `:updated-at` come from the restored session
   snapshot (`session.edn`)."
  [{:keys [session-id agent-id total-turns updated-at lm-provider lm-model
           restored-cost restored-defs lost-defs]}]
  (let [sep      (ansi/muted " · ")
        provider (some-> lm-provider name)
        age      (format-age-since updated-at)
        line1    (str (ansi/style "↻ Resumed " ansi/bright-cyan)
                      (ansi/header (or session-id "unknown"))
                      sep
                      (ansi/style (name (or agent-id :unknown)) ansi/bright-cyan)
                      (when (or provider lm-model)
                        (str sep (ansi/muted (str (or provider "?") "/" (or lm-model "?")))))
                      sep (ansi/muted (str (or total-turns 0) " turns"))
                      (when age (str sep (ansi/muted (str "last active " age)))))
        carried? (or (and restored-cost (pos? (double restored-cost)))
                     (and restored-defs (pos? restored-defs))
                     (and lost-defs (pos? lost-defs)))
        line2    (when carried?
                   (str "  "
                        (ansi/style "↳ carried" ansi/bright-cyan)
                        (when (and restored-cost (pos? (double restored-cost)))
                          (str sep (ansi/muted (format "$%.4f" (double restored-cost)))))
                        (when (and restored-defs (pos? restored-defs))
                          (str sep (ansi/muted (str restored-defs " defs restored"))))
                        (when (and lost-defs (pos? lost-defs))
                          (str sep (ansi/muted (str lost-defs " lost (non-EDN/fns)"))))))]
    (if line2 (str line1 "\n" line2) line1)))

;; ============================================================================
;; Queue Management (private)
;; ============================================================================

;; ============================================================================
;; Hook Registration
;; ============================================================================

(defn register-tui-hooks!
  "Register every TUI-side observer against the hooks registry under
   :source :tui. Called once from `start!`; `stop!` tears them down in bulk
   via `unregister-source! :tui`.

   Streaming LLM output is rendered inside each iteration widget via the
   `:agent.dspy-action/chunk` hook (`dspy-chunk-handler`); BT LLM-calling
   actions still accumulate the deltas under
   `[:behavior-tree :context :st-memory :llm-streaming-text]` so the chunk
   handler can read the running total — no st-memory watch involved."
  []
  (agent/register-hook! :agent.instance/created ::tui-agent-created
                        tui-session/agent-created-handler :source :tui)
  (agent/register-hook! :agent.instance/closed ::tui-agent-closed
                        tui-session/agent-closed-handler :source :tui)
  (agent/register-hook! :agent.ask/pre ::tui-ask-pre
                        tui-session/ask-pre-handler :source :tui)
  (agent/register-hook! :agent.ask/post ::tui-ask-post
                        tui-session/ask-post-handler :source :tui)
  (agent/register-hook! :agent.ask/post ::driver-settle-emit
                        emit-turn-complete! :source :tui)
  (agent/register-hook! :agent.tool-use/pre ::tui-tool-pre
                        tui-session/tool-pre-handler :source :tui)
  (agent/register-hook! :agent.tool-use/post ::tui-tool-post
                        tui-session/tool-post-handler :source :tui)
  (agent/register-hook! :task/created ::tui-task-created
                        tui-session/task-created-handler :source :tui)
  (agent/register-hook! :task/completed ::tui-task-completed
                        tui-session/task-completed-handler :source :tui)
  (agent/register-hook! :todo/updated ::tui-todo-updated
                        tui-session/todo-updated-handler :source :tui)
  ;; Iteration live-blocks: per-iteration widgets driven by typed hooks
  ;; (think/tools/code/eval). Together with the simple per-event handlers
  ;; below (compaction, exhaustion, evaluation, analytics) they own the
  ;; iteration-rendering pipeline.
  (agent/register-hook! :agent.iteration/pre  ::tui-iter-pre
                        tui-session/iteration-pre-handler  :source :tui)
  (agent/register-hook! :agent.iteration/post ::tui-iter-post
                        tui-session/iteration-post-handler :source :tui)
  (agent/register-hook! :agent.dspy-action/pre   ::tui-dspy-pre
                        tui-session/dspy-pre-handler   :source :tui)
  (agent/register-hook! :agent.dspy-action/chunk ::tui-dspy-chunk
                        tui-session/dspy-chunk-handler :source :tui)
  (agent/register-hook! :agent.dspy-action/post  ::tui-dspy-post
                        tui-session/dspy-post-handler  :source :tui)
  (agent/register-hook! :agent.tool-calls/pre  ::tui-tools-pre
                        tui-session/tool-calls-pre-handler  :source :tui)
  (agent/register-hook! :agent.tool-calls/post ::tui-tools-post
                        tui-session/tool-calls-post-handler :source :tui)
  (agent/register-hook! :agent.tool-use/pre  ::tui-iter-tool-pre
                        tui-session/tool-use-pre-handler  :source :tui)
  (agent/register-hook! :agent.tool-use/post ::tui-iter-tool-post
                        tui-session/tool-use-post-handler :source :tui)
  (agent/register-hook! :agent.code-eval/pre  ::tui-code-pre
                        tui-session/code-eval-pre-handler  :source :tui)
  (agent/register-hook! :agent.code-eval/post ::tui-code-post
                        tui-session/code-eval-post-handler :source :tui)
  ;; Per-event handlers without a widget surface — render plain
  ;; scrollback lines.
  (agent/register-hook! :agent.compaction/pre ::tui-compaction-pre
                        tui-session/compaction-pre-handler :source :tui)
  (agent/register-hook! :agent.compaction/phase ::tui-compaction-phase
                        tui-session/compaction-phase-handler :source :tui)
  (agent/register-hook! :agent.compaction/post ::tui-compaction-post
                        tui-session/compaction-post-handler :source :tui)
  (agent/register-hook! :agent.iteration/exhausted ::tui-iter-exhausted
                        tui-session/iteration-exhausted-handler :source :tui)
  (agent/register-hook! :agent.recovery/retrying ::tui-recovery-retrying
                        tui-session/recovery-retrying-handler :source :tui)
  (agent/register-hook! :agent.evaluation/started ::tui-eval-started
                        tui-session/evaluation-started-handler :source :tui)
  (agent/register-hook! :agent.evaluation/llm-calling ::tui-eval-llm-calling
                        tui-session/evaluation-llm-calling-handler :source :tui)
  (agent/register-hook! :agent.evaluation/done ::tui-eval-done
                        tui-session/evaluation-done-handler :source :tui)
  ;; Agent suggestion → idle input-bar help tip. Scoped to root agents so a
  ;; sub-agent's follow-up doesn't hijack the shared input bar.
  (agent/register-hook! :agent.suggestion/next-user-prompt ::tui-agent-suggestion
                        tui-session/agent-suggestion-handler
                        :match (agent/match-root-agent) :source :tui))

;; ============================================================================
;; Dynamic Skill Registration (once per process, at runtime)
;; ============================================================================

(defonce ^{:private true
           :doc "Process-once guard for dynamic skill registration. A defonce
   *atom* (not a defonce side effect): safe to bake at native-image build time
   because it only stores `false`; the actual FS scan happens at runtime via
   `register-skills-once!`."}
  !skills-registered?
  (atom false))

(defn- register-skills-once!
  "Register dynamic skills (~/.brainyard, ~/.claude, ~/.agents, project
   .brainyard) exactly once per process. Called from `start!` at runtime —
   NOT from `create-tui-agent!`, so per-agent / per-tab spawns never re-scan
   skill directories. Replaces the old namespace-load `defonce` bootstrap in
   ai.brainyard.agent.common.skills, which the native `by` binary baked at
   build time."
  []
  (when (compare-and-set! !skills-registered? false true)
    (try
      (agent/reload-skills!)
      (catch Throwable e
        (reset! !skills-registered? false)
        (mulog/warn ::skills-register-failed :error (ex-message e))))))

(defn- format-per-ask-usage
  "Format per-ask usage as a right-aligned dim line."
  [{:keys [calls tokens cost]}]
  (let [text (ansi/muted (str calls " calls " ansi/v-line " "
                              (fmt/format-number tokens) " tokens " ansi/v-line " "
                              "$" (format "%.4f" (double (or cost 0.0)))))
        cols (fmt/terminal-columns)
        visible-len (count (str/replace text #"\033\[[0-9;]*m" ""))
        right-pad 1
        padding (max 0 (- cols visible-len right-pad))]
    (str (apply str (repeat padding " ")) text)))

(defn- maybe-auto-compact!
  "Check if auto-compaction should trigger after a turn completes.
   Gated by :enable-context-budget — the single context-compaction knob
   shared with the per-iteration / turn-init budget reducer. Fires only
   when the estimated context overflows :max-context-tokens. Live progress
   + frozen summary render via the TUI session's compaction-block handlers;
   this fn just decides whether to trigger."
  [ag]
  (try
    (when (agent/get-config ag :enable-context-budget)
      (let [max-tokens (agent/get-config ag :max-context-tokens)
            estimated  (agent/estimate-context-tokens ag)]
        (when (> estimated max-tokens)
          (let [target-ratio (agent/get-config ag :compaction-target-ratio)]
            (agent/compact-context! ag :target-ratio target-ratio :trigger :auto)))))
    (catch Exception e
      (tui-session/emit!
       (ansi/muted (str "[auto-compact] error: " (.getMessage e)))))))

(defn- run-ask-lifecycle
  "Shared pre/post-ask lifecycle for one `agent/ask` turn — used by both the
   input-queue path and the direct `ask` entry point. Caller supplies:
     :ag :input :opts  — the ask (`opts` forwarded to agent/ask; nil ok)
     :origin-sidx      — session post-ask output routes to (captured at start)
     :active?          — whether origin-sidx is on screen at start; gates the
                         thinking indicator + :running status (a background
                         wakeup must not touch the active view)
     :set-idle?        — set the status bar to :idle on completion (the direct
                         `ask` path; the queue path leaves status transitions
                         to its notify-fn)
     :error-mode       — :rethrow (direct `ask`) or :emit a failure line (queue)
   Routes cancelled / error / usage-diff output to origin-sidx, runs
   auto-compaction between turns, and redraws chrome only while still on
   screen. Returns the ask result (nil on cancel / handled :emit error)."
  [{:keys [ag input opts origin-sidx active? set-idle? error-mode]}]
  (let [usage-before (helpers/get-usage-totals ag)]
    (when active?
      (tui-session/update-status-bar! :running)
      (tui-session/start-thinking-indicator!))
    (reset! input/!ask-thread (Thread/currentThread))
    (try
      (let [result        (agent/ask ag input opts)
            _             (Thread/interrupted)
            cancelled?    (agent/cancelled? (:!state ag))
            usage-after   (helpers/get-usage-totals ag)
            diff          (helpers/usage-diff usage-before usage-after)
            still-active? (= origin-sidx (sessions/active-idx))]
        (when active? (tui-session/stop-thinking-indicator!))
        (cond
          cancelled?
          (tui-session/emit! (str "\n" (ansi/warning "Cancelled.")) origin-sidx)

          (:error result)
          (tui-session/emit! (str "\n" (ansi/failure (str "Error: " (:error result)))) origin-sidx)

          diff
          (tui-session/emit! (str "\n" (format-per-ask-usage diff)) origin-sidx))
        ;; Auto-compaction check (between turns) — both paths.
        (when-not (or cancelled? (:error result))
          (maybe-auto-compact! ag))
        ;; Only touch terminal if we're still the active session.
        (when still-active?
          (layout/redraw-chrome!)
          (when set-idle? (tui-session/update-status-bar! :idle)))
        result)
      (catch Exception e
        (Thread/interrupted)
        (when active? (tui-session/stop-thinking-indicator!))
        (when (= origin-sidx (sessions/active-idx))
          (layout/redraw-chrome!)
          (when set-idle? (tui-session/update-status-bar! :idle)))
        (if (agent/cancelled? (:!state ag))
          (do (tui-session/emit! (str "\n" (ansi/warning "Cancelled.")) origin-sidx)
              nil)
          (case error-mode
            :rethrow (throw e)
            :emit    (do (tui-session/emit! (ansi/failure (str "Error: " (.getMessage e))) origin-sidx)
                         nil))))
      (finally
        (reset! input/!ask-thread nil)))))

(defn- tui-queue-process-fn
  "Process function for the TUI input queue. Runs agent/ask with full TUI lifecycle.
   Resolves the target agent from `(:agent opts)` when present (a task$wakeup
   resume of a possibly-backgrounded agent), else the active agent. For a
   targeted resume it pins emit-routing to that agent's session via
   *render-session-idx* and skips terminal chrome (status bar / spinner) when
   that session isn't on screen — so a background wakeup renders into its own
   tab instead of hijacking the active view. `opts` (e.g. {:source :wakeup}) is
   forwarded to agent/ask for tagging."
  [input & [opts]]
  (tui-session/capture-writer!)
  (let [ag        (or (:agent opts) (tui-session/get-active-agent))
        targeted? (some? (:agent opts))
        sidx      (when ag (tui-session/session-idx-for-agent ag))]
    (cond
      (nil? ag) nil

      ;; Targeted resume (wakeup) whose session was closed before the timer
      ;; fired — nothing to render into; skip rather than hijack the active
      ;; session.
      (and targeted? (nil? sidx))
      (mulog/warn ::wakeup-target-session-closed :agent-id (:agent-id ag))

      :else
      ;; For a targeted resume (wakeup) pin emit-routing to the parked agent's
      ;; session via *render-session-idx* (terminal-only ops self-skip when it
      ;; isn't the active session). For normal user input leave it nil so the
      ;; original active-terminal path is unchanged.
      (binding [tui-session/*render-session-idx* (when targeted? sidx)]
        (let [origin-sidx (if targeted? sidx (sessions/active-idx))
              active?     (= origin-sidx (sessions/active-idx))]
          ;; Queue path leaves status-bar transitions to its notify-fn, so no
          ;; :idle here; surfaces errors as a line (the worker swallows throws).
          (run-ask-lifecycle {:ag ag :input input :opts opts
                              :origin-sidx origin-sidx :active? active?
                              :set-idle? false :error-mode :emit}))))))

(defn- wrap-to-width
  "Greedy word-wrap plain `s` to `width` display columns. Returns a vector of
   rows, each ≤ width columns (a single word longer than width is left whole on
   its own row rather than truncated). Width-aware for wide/emoji chars."
  [s width]
  (let [width (max 1 width)]
    (if (<= (fmt/display-width s) width)
      [s]
      (->> (str/split (str/trim s) #"\s+")
           (reduce (fn [rows word]
                     (let [cur (peek rows)]
                       (if (and cur (<= (fmt/display-width (str cur " " word)) width))
                         (conj (pop rows) (str cur " " word))
                         (conj rows word))))
                   [])))))

(defn- format-user-input-display
  "Format user input for the scroll-region echo. Echoes the input IN FULL — each
   source line word-wrapped to the pane width, with every continuation /
   subsequent row indented two columns to align under the ❯ prefix. No
   truncation, and no collapsing of multi-line input to a `[N lines]` summary."
  [input]
  (let [cols  (or (:cols @layout/!layout) 80)
        avail (max 20 (- cols 2))]           ;; 2 cols for the "❯ " prefix
    (->> (str/split-lines input)
         (mapcat #(wrap-to-width % avail))
         (str/join "\n  "))))

(defn- tui-queue-notify-fn
  "Notification function for TUI input queue state changes."
  [event item queue-info]
  (let [queue-len (:queue-length queue-info)]
    (case event
      :enqueued
      (do (swap! tui-session/!tui-state assoc :queue-count queue-len)
          (tui-session/update-status-bar!))

      :processing
      (let [echo      (str "\n" (ansi/user-text (format-user-input-display (:input item))))
            target-ag (:agent (:opts item))
            sidx      (some-> target-ag tui-session/session-idx-for-agent)]
        ;; Route the input echo to the right session: a targeted resume
        ;; (task$wakeup, :agent in opts) echoes into the parked agent's session
        ;; — not the active view. Targeted-but-session-gone → skip echo (the
        ;; worker skips the turn too). Normal input → active session.
        (cond
          sidx      (tui-session/emit! echo sidx)
          target-ag nil
          :else     (tui-session/emit! echo))
        (swap! tui-session/!tui-state assoc :queue-count queue-len)
        (tui-session/update-status-bar!))

      :completed
      (do (swap! tui-session/!tui-state assoc :queue-count queue-len)
          (tui-session/update-status-bar! :idle))

      :error
      (do (swap! tui-session/!tui-state assoc :queue-count queue-len)
          (tui-session/update-status-bar! :idle))

      :cancelled
      (do (swap! tui-session/!tui-state assoc :queue-count queue-len)
          (tui-session/update-status-bar!))

      :queue-empty
      (do (swap! tui-session/!tui-state assoc :queue-count 0)
          (tui-session/update-status-bar! :idle))

      nil)))

(declare enqueue-input!)

(defn- ensure-input-queue!
  "Ensure the TUI input queue exists, creating it lazily. On first creation,
   register a host turn-submitter so task$wakeup resumes flow through THIS
   queue (serialized with user turns) rather than racing on a separate
   executor."
  []
  (when-not @!input-queue
    (reset! !input-queue
            (agent/create-queue tui-queue-process-fn tui-queue-notify-fn))
    (agent/register-turn-submitter!
     (fn [agent input opts] (enqueue-input! input (assoc opts :agent agent)))))
  @!input-queue)

(defn enqueue-input!
  "Enqueue an input for processing. User text is displayed by :processing event.
   Rejects input for output-only sessions.
   `opts` (optional, e.g. {:source :wakeup}) is forwarded to agent/ask via the
   queue — used by task$wakeup resumes so they serialize with user turns."
  ([input] (enqueue-input! input nil))
  ([input opts]
   (if (= :output (:session-type (sessions/get-active-session)))
     (tui-session/emit!
      (ansi/warning "This is an output-only session. Use /session <N> to switch to a chat session, or /session close to close this one."))
     (let [ag (tui-session/get-active-agent)]
       (when-not ag
         (throw (ex-info "No TUI agent running. Call (start! :agent-id) first." {})))
       (let [!queue (ensure-input-queue!)
             result (agent/enqueue! !queue input opts)]
         (when (:error result)
           (tui-session/emit! (ansi/warning "Input queue is full (max 10). Please wait."))))))))

;; ============================================================================
;; Public API — Lifecycle
;; ============================================================================

(defn create-tui-agent!
  "Create and configure a TUI agent for a session.
   Shared by start! (session 0) and sessions/create-session! (new sessions).
   Uses invoke-tool with :setup-only? true to follow the same dispatch path
   as any defagent invocation, with a generated instance-id.
   Returns the agent instance with permission-fn, user-feedback-fn, and dirs configured."
  [agent-id & {:keys [user-id session-id max-iterations instance-id]}]
  (let [user-id  (or user-id (helpers/resolve-user-id))
        sess-id  (or session-id
                     (throw (ex-info "create-tui-agent! requires :session-id" {})))
        ;; Resolve the effective agent type. A persisted session may name an
        ;; agent that is no longer registered this run — a deleted user-defined
        ;; agent (user$agent$/user$tool$…), a renamed/removed built-in, or a
        ;; plugin agent not loaded. invoke-tool would then return an
        ;; {:error-message …} map (not an Agent), and the swap! below would NPE
        ;; on its nil :!session. Fall back to the default so the session's
        ;; history still opens, with a one-line notice.
        registered? (some? (agent/get-tool-defs :id agent-id))
        _ (when-not registered?
            (println (ansi/style
                      (str "⚠ agent '" (name agent-id)
                           "' is no longer registered — resuming with coact-agent")
                      ansi/bright-yellow)))
        agent-id (if registered? agent-id :coact-agent)
        ;; A stale instance-id (for the now-unregistered type) must not carry
        ;; over to the fallback — regenerate it from the effective type.
        inst-id  (or (when registered? instance-id)
                     (agent/generate-instance-id agent-id))
        dirs (dirs/init-dirs!)
        ;; Clean up existing agent if present (flat registry: by agent-id only)
        _ (when-let [^java.io.Closeable existing (agent/get-agent inst-id)]
            (.close existing))
        ;; Lifecycle / tool / task hooks are registered once at `start!` via
        ;; `register-tui-hooks!`. Streaming LLM chunks are handled by
        ;; agent.core.bt/chunk-factory-handler, invoked directly by BT actions.
        ;; :allowed-dirs / :permission-mode flow from .brainyard/config.edn
        ;; via load-global-config!'s bridge of [:permissions ...] → schema keys.
        ;; working-dir is not a config key — the resolved `:dirs` map (seeded
        ;; below via set-session-config) is the single carrier, read by
        ;; config/working-dir + config/project-dir.
        ag (agent/invoke-tool agent-id
                              {:id inst-id
                               :setup-only? true
                               :agent-session {:user-id user-id :session-id sess-id}
                               :max-iterations max-iterations
                               :session-store !session-store})
        ;; Defense-in-depth: the fallback above guarantees a registered type, so
        ;; invoke-tool returns a real Agent — but surface any residual setup
        ;; failure as a clear error rather than a downstream nil-swap! NPE.
        _ (when (or (nil? ag) (:error-message ag) (nil? (:!session ag)))
            (throw (ex-info (str "Could not create TUI agent for '" (name agent-id)
                                 "': " (or (:error-message ag) "agent setup returned no session"))
                            {:agent-id agent-id :result ag})))]
    ;; Configure the unified user-feedback fn (the single interactive-input
    ;; primitive) and the permission adapter that rides on top of it. One
    ;; feedback-fn instance is shared so permission + feedback serialize through
    ;; the same lock / pending channel.
    (let [feedback-fn (permissions/make-feedback-fn input/!input-reader-thread)]
      (swap! (:!session ag) agent/set-session-config :user-feedback-fn feedback-fn)
      (swap! (:!session ag) agent/set-session-config :dirs dirs)
      (swap! (:!session ag) agent/set-session-config :permission-fn
             (permissions/make-permission-fn input/!input-reader-thread feedback-fn)))
    ag))

(defn load-input-history-for-session!
  "Reset `terminal/!input-history` to the on-disk history for `agt-sess-id`.
   Returns the loaded vector. Resilient — a missing file or unreadable EDN
   resets the atom to `[]` so callers don't have to guard. Public so the
   session-switch path in `sessions.clj` can reuse it."
  [agt-sess-id]
  (let [hist (try
               (or (persist/read-snap agt-sess-id :input-history) [])
               (catch Throwable _ []))]
    (reset! terminal/!input-history hist)
    hist))

(defn- format-mcp-summary
  "One-line 'MCP: connecting … · lazy …' banner from an init-mcp-from-config!
   summary map, or nil when nothing is connecting or deferred. Emitted under
   the welcome banner (after init-fullscreen!) — emitting during start! would
   land in the primary buffer and be hidden by the alt-screen."
  [{:keys [connecting lazy]}]
  (let [c (seq connecting) l (seq lazy)]
    (when (or c l)
      (ansi/muted (str "MCP: "
                       (when c (str "connecting " (str/join ", " c)))
                       (when (and c l) "  ·  ")
                       (when l (str "lazy " (str/join ", " l))))))))

(defn start!
  "Start a TUI session with an agent.

   Parameters:
     agent-id - keyword agent ID (e.g., :coact-agent)

   Options:
     :user-id        - User ID (default: resolved via helpers/resolve-user-id —
                       BY_USER_ID env, else the `user.name` system property)
     :session-id     - Session ID (default: auto-generated)
     :max-iterations - Override max iterations
     :verbosity      - :quiet | :normal | :verbose (default: :normal)
     :lm-provider    - :claude-code | :anthropic | :openai | :ollama (auto-setup LM)
     :lm-model       - Override model name for LM
     :inline         - Force inline mode (no alt screen). Useful for scripted testing.
     :mode           - :A or :B from `mode/probe`. Defaults to :A. Stored in
                       !tui-state so later phases (popups, side panes) can
                       branch on it.
     :resume?        - When true, hydrate the agent's in-memory session from
                       `<project>/.brainyard/sessions/<session-id>/` before creating
                       the agent, then replay the tail of the persisted
                       scrollback to stdout. Caller must also pass
                       `:session-id` of an existing on-disk session.

   Returns: :ok"
  [& {:keys [agent-id user-id session-id max-iterations verbosity
             lm-provider lm-model inline mode resume? skip-banner]
      :or {agent-id :coact-agent
           verbosity :normal
           inline false
           mode :A
           resume? false
           skip-banner false}}]
  ;; 0. Suppress JUL cookie warnings
  (helpers/suppress-jul-cookie-warnings!)

  ;; 1. Capture *out* for nREPL
  (tui-session/capture-writer!)

  ;; 1b. Stash the resolved mode so later code (popups, side panes, slash
  ;; commands) can branch on it without re-probing the environment.
  ;; Also clear any stale `:resumed?` / `:resume-tail` from a prior session
  ;; so the fullscreen replay in `run!` only fires when this invocation
  ;; actually hydrated from disk.
  (swap! tui-session/!tui-state assoc
         :mode mode :resumed? false :resume-tail nil)

  ;; (Mode-B Tmux side-channel install is deferred to step 5b so it can be
  ;; passed the agent's persistence directory.)

  ;; 2. Auto-setup LM if requested
  (when lm-provider
    (helpers/setup-lm! lm-provider :model lm-model))

  ;; 2b. Mulog publisher setup
  (when (= verbosity :verbose)
    (tui-session/start-tui-publisher!))

  ;; 2b2. Route Java SLF4J logs through mulog
  (mulog/setup-slf4j-bridge!)

  ;; 2c. File publisher (always on). Default path is ~/.brainyard/logs/agent-tui-app.log
  ;;     (see tui-log/default-log-path); fall through to /tmp only when the
  ;;     user-scope dir can't be created.
  (try (tui-log/start-file-publisher!) (catch Exception _))
  (agent/set-app-log-path! (tui-log/default-log-path))

  ;; 2d. clj-nrepl server (opt-in). When BY_NREPL_ENABLED=true,
  ;;     starts a loopback nREPL server so `code$eval :backend :nrepl`
  ;;     can reach the LIVE runtime. Off by default — never started in
  ;;     unattended runs unless explicitly enabled. See §5 of the design.
  (start-nrepl-server-if-enabled!)

  ;; 3. Initialize autocomplete sub-menu registry.
  ;; Built-in defagent namespaces are registered at agent.interface load time
  ;; (see components/agent/src/ai/brainyard/agent/interface.clj) — no
  ;; explicit chain needed here.
  (autocomplete/init-submenus!)

  ;; 3b. Install the persist bridge BEFORE the first agent is created — so it
  ;;    catches `:agent.session/created` and `:agent.instance/created` for the
  ;;    main tab. Idempotent across multiple start! invocations.
  (persist-bridge/start!)

  ;; Register dynamic skills once per process, at runtime startup. Run on a
  ;; background thread so the FS scan (~/.brainyard, ~/.claude, ~/.agents +
  ;; project skill dirs — slurp/parse every SKILL.md) doesn't stall the boot
  ;; path. Skills register into the shared tool registry, which the agent reads
  ;; per-turn, so a skill that lands a moment after the prompt appears simply
  ;; shows up on the next turn. The CAS guard inside keeps it once-per-process.
  (future
    (try
      (register-skills-once!)
      (catch Throwable e
        (mulog/warn ::skills-register-async-failed :error (ex-message e)))))

  ;; Load MCP servers: built-in defaults deep-merged with config.edn
  ;; [:mcp :servers] (config.edn wins per leaf). Enabled servers connect in the
  ;; BACKGROUND (one future each), so an OAuth-gated server (notion/linear)
  ;; never blocks boot; servers flagged :lazy true connect on demand via
  ;; /mcp <name> start. Surface a one-line banner of what's connecting / lazy,
  ;; and a terse ✓/✗ as each connect settles. Idempotent via !mcp-initialized.
  (let [summary (agent/init-mcp-from-config!
                 {:on-settle
                  (fn [server-name status _err]
                    ;; Format: 'MCP: <message>' — consistent with the
                    ;; connecting banner. After emitting (async, while the
                    ;; user sits at the prompt), return the cursor to the
                    ;; input line so it doesn't strand at the message tail.
                    (when-let [msg (case status
                                     :ok    (ansi/success (str "MCP: " ansi/check " " server-name))
                                     :error (ansi/failure (str "MCP: " ansi/cross-mark " " server-name
                                                               "  (/mcp " server-name " status)"))
                                     nil)]
                      (tui-session/emit! msg)
                      (layout/restore-input-cursor!)))})]
    ;; Stash the connecting/lazy summary; the one-line banner is emitted AFTER
    ;; the welcome banner (step 13 here for inline/REPL callers, or run!'s
    ;; post-fullscreen path) via format-mcp-summary — emitting it now would
    ;; land in the primary buffer and be hidden once run! enters the alt-screen.
    ;; The async ✓/✗ on-settle emits above fire during the live loop and route
    ;; to the active session as each background connect settles.
    (swap! tui-session/!tui-state assoc :mcp-summary summary))

  (let [;; Honor an explicit session id from the environment when the caller
        ;; didn't pass one. Tutorial recording / scripted drivers set
        ;; BY_SESSION_ID so `<project>/.brainyard/sessions/<id>/` paths (lock,
        ;; turn.complete) are deterministic and `bb tui:drive -S <id>` can
        ;; target this instance. Explicit :session-id still wins.
        env-sid (let [v (System/getenv "BY_SESSION_ID")]
                  (when (and v (not (.isBlank ^String v))) (.trim ^String v)))
        agt-sess-id (or session-id env-sid (agent/generate-session-id "agt"))
        resuming? (and resume? agt-sess-id
                       (some #{agt-sess-id} (persist/list-sessions)))
        ;; Read persisted TUI meta first so we can restore the defagent
        ;; type and tab label before creating the agent below.
        resumed-meta (when resuming?
                       (try (persist/read-meta agt-sess-id) (catch Throwable _ nil)))
        ;; Persisted defagent-id wins over the CLI default on resume so the
        ;; agent type matches what the user had when they last quit.
        agent-id (or (when resuming? (:defagent-id resumed-meta)) agent-id)
        ;; 3c. Resume: rehydrate the agent-session map from disk and drop it
        ;;     into the session store BEFORE create-tui-agent! — so when the
        ;;     agent's `get-or-create-session` runs it finds the restored atom
        ;;     and reuses it instead of minting an empty one. Prime the
        ;;     persist bridge's high-water mark so the next ask only appends
        ;;     NEW messages.
        _ (when resuming?
            (try
              (let [restored (persist/restore-session-map agt-sess-id)
                    !s       (atom restored)
                    ;; Snapshot the on-disk scrollback tail NOW, before the
                    ;; welcome banner emit (step 13) tees its own bytes into
                    ;; the same file. The replay in `run!` (and the inline
                    ;; branch below) uses this fixed snapshot so the banner
                    ;; emitted by this start! call doesn't get replayed
                    ;; on top of the alt-screen banner emitted by `run!`.
                    tail     (try (persist/tail-scrollback
                                   agt-sess-id :stream
                                   (agent/get-config :resume-scrollback-bytes))
                                  (catch Throwable _ nil))]
                (agent/set-session !session-store agt-sess-id !s)
                ;; Attaching IS a use — bump last-activity so a just-resumed
                ;; session sorts as the latest for the next --select-resume /
                ;; bare --resume, even before its first new turn completes.
                (persist/save-meta! agt-sess-id
                                    {:last-attached-at (System/currentTimeMillis)})
                (persist-bridge/prime-session-counts!
                 agt-sess-id (count (:messages restored)))
                ;; Mark the TUI state as resumed so `run!` knows to replay
                ;; the snapshot into the alt-screen after `init-fullscreen!`
                ;; (which clears `!scrollback` and the alt-screen buffer).
                (swap! tui-session/!tui-state assoc
                       :resumed? true
                       :resume-tail tail))
              (catch Throwable _ nil)))
        max-iter (or max-iterations
                     (:max-iterations (:meta (agent/get-tool-defs :id agent-id)))
                     (get agent/default-config :max-iterations 20))
        ;; Create agent with its own agent-session-id
        ag (create-tui-agent! agent-id
                              :user-id user-id
                              :session-id agt-sess-id
                              :max-iterations max-iter)
        ;; Resume hydration step: replay the persisted usage-tracker snap
        ;; into the freshly-minted tracker atom inside ag's session config
        ;; (the agent's init created an empty one — we overwrite from disk).
        ;; Captured into a let-binding so step 13's resume banner can read
        ;; the restored running cost.
        restored-cost (when resuming?
                        (try
                          (when-let [snap (persist/read-snap agt-sess-id :usage-tracker nil)]
                            (when-let [tracker (agent/get-session-config @(:!session ag) :usage-tracker)]
                              (clj-llm/hydrate-tracker! tracker snap)
                              (get-in snap [:totals :total-cost])))
                          (catch Throwable _ nil)))
        ;; Eager sandbox materialization. The restored :sandbox-state survives
        ;; on disk (persist-bridge writes it via the session snap), but coact's
        ;; live SCI ctx is rebuilt lazily on the next iteration. Building a
        ;; minimal sandbox here makes `/sandbox eval x` work immediately after
        ;; --resume. The first agent ask will replace bindings + context via
        ;; coact's existing-sandbox branch (update-context!/update-bindings!),
        ;; so this seed doesn't lock the LLM out of full agent tools.
        restored-defs-count
        (when resuming?
          (try
            (let [sess     (some-> ag :!session deref)
                  state    (:sandbox-state sess)
                  bindings (when (seq state) (clj-sandbox/build-restore-bindings state))]
              (when (seq bindings)
                (let [sandbox (clj-sandbox/create-sandbox :bindings bindings)]
                  (swap! (:!state ag) assoc :sandbox sandbox)
                  (count bindings))))
            (catch Throwable _ nil)))
        lost-defs-count
        (when resuming?
          (count (some-> ag :!session deref :sandbox-lost-defs)))
        _ (when resuming?
            (swap! tui-session/!tui-state assoc
                   :resume-restored-cost  restored-cost
                   :resume-restored-defs  restored-defs-count
                   :resume-lost-defs      lost-defs-count
                   :resume-from-sid       agt-sess-id
                   :resume-from-turn      (some-> ag :!session deref :total-turns)))]

    ;; 4. Register all TUI hooks under :source :tui.  The task manager
    ;;    is lazily auto-initialized in agent.task.manager on first
    ;;    access — callers no longer need their own create+set dance.
    ;;    Install the layout-backed iteration sink before handlers run so
    ;;    `iter-sink/write-widget!` calls inside handlers route to the
    ;;    in-process scrollback (the legacy live-block surface).
    (tui-session/install-layout-iteration-sink!)
    (tui-session/install-shared-output-cascade!)
    (register-tui-hooks!)

    ;; 5. Create session 0 (main) in the multi-session manager.
    ;;    `:agent-session-id` (string) is the stable id keying the on-disk
    ;;    persistence dir; the TUI's `:id` (integer) is process-local. See
    ;;    `make-session` and docs/simplified-agent-tui-arch-design.md §6.
    (sessions/reset-sessions!)
    (sessions/create-session! {:id 0
                               :label (or (:label resumed-meta)
                                          (sessions/next-root-tab-label!))
                               :agent ag
                               :agent-id agent-id
                               :agent-session-id agt-sess-id
                               :agent-instances [ag]
                               :max-iterations max-iter
                               :verbosity verbosity
                               :started-at (System/currentTimeMillis)
                               :skip-agent-creation true})

    ;; 5a. Load this session's persisted input history into the in-memory
    ;; atom — covers both the resume branch (history restored from disk)
    ;; and the fresh-start branch (no file → []). Up/down arrow recall
    ;; and submit-time persistence both read this atom.
    (load-input-history-for-session! agt-sess-id)

    ;; 5b. In Mode B, install the Tmux side-channel pointing at the agent's
    ;; persistence dir so /scrollback dump and side-pane FIFOs land under
    ;; <project>/.brainyard/sessions/<agent-session-id>/. Failures are non-fatal.
    (when (= :B mode)
      (try (tmux-side/install!
            {:session-dir (.getAbsolutePath ^java.io.File (persist/session-dir agt-sess-id))})
           (catch Throwable _ nil)))

    ;; 6. Attach watches (session-aware)
    (tui-session/set-agent! ag (:agent-id ag)
                            :max-iterations max-iter
                            :verbosity verbosity
                            :session-idx 0)

    ;; 7. Store agent's session-id for run logging
    (swap! tui-session/!tui-state assoc :session-id agt-sess-id)

    ;; 12. Resume: in inline mode, print the snapshot of the persisted
    ;;     scrollback tail directly to *out* BEFORE the welcome banner so
    ;;     the user sees prior conversation, then the banner, then the
    ;;     next prompt. Uses the pre-banner snapshot captured during the
    ;;     resume hydration block above so the banner emitted by step 13
    ;;     doesn't get replayed on top of itself. Bypasses
    ;;     tui-session/emit! to avoid re-tee-ing the restored bytes back
    ;;     into the scrollback file (which would double them on next
    ;;     resume). Fullscreen replay is handled in `run!` after
    ;;     `init-fullscreen!` — printing here would only land in the
    ;;     terminal's primary buffer and be hidden by the alt-screen.
    (when (and resume? inline)
      (when-let [tail (:resume-tail @tui-session/!tui-state)]
        (when (not= "" tail)
          (try (print tail) (flush)
               (catch Throwable _ nil)))))

    ;; 13. Emit welcome banner (or compact resume notice). Skipped when
    ;;     `run!` passes :skip-banner true — `run!` handles the banner
    ;;     itself after `init-fullscreen!` to avoid a duplicate (teardown
    ;;     replays alt-screen scrollback to the primary buffer, so a
    ;;     banner written here AND in run!'s fullscreen path both appear).
    ;;     This emit serves REPL / inline callers that never go through
    ;;     `run!`. Routes through `layout/write-output!` (not
    ;;     `tui-session/emit!`) so the bytes don't tee into the on-disk
    ;;     scrollback file.
    (when-not skip-banner
      (let [lm    (try (clj-llm/get-default-lm) (catch Exception _ nil))
            sess  (try @(:!session ag) (catch Throwable _ nil))]
        (if (:resumed? @tui-session/!tui-state)
          (layout/write-output!
           (format-resume-notice
            {:session-id    agt-sess-id
             :agent-id      agent-id
             :total-turns   (:total-turns sess)
             :updated-at    (:updated-at sess)
             :lm-provider   (:provider lm)
             :lm-model      (:model lm)
             :restored-cost (:resume-restored-cost @tui-session/!tui-state)
             :restored-defs (:resume-restored-defs @tui-session/!tui-state)
             :lost-defs     (:resume-lost-defs @tui-session/!tui-state)}))
          (let [agents (->> (agent/get-tool-defs :type :agent)
                            vals
                            (mapv #(select-keys % [:id]))
                            (sort-by (comp name :id)))]
            (layout/write-output!
             (fmt/format-welcome-banner
              {:agent-id    agent-id
               :session-id  agt-sess-id
               :verbosity   verbosity
               :lm-provider (:provider lm)
               :lm-model    (:model lm)
               :agents      agents}))))
        ;; One-line MCP connecting/lazy summary, under the banner.
        (when-let [s (format-mcp-summary (:mcp-summary @tui-session/!tui-state))]
          (layout/write-output! (str s "\n")))))
    :ok))

(defn stop!
  "Stop TUI session. Closes all sessions, detaches watches, tears down hooks,
   shuts down task manager and queue."
  []
  ;; Remove every TUI-registered hook in one call
  (try (agent/unregister-source! :tui) (catch Exception _))
  ;; Tear down the persist bridge (unregisters the :persist hook source).
  (try (persist-bridge/stop!) (catch Exception _))
  ;; Stop input queue
  (when-let [!queue @!input-queue]
    (agent/stop-queue! !queue)
    (reset! !input-queue nil))
  ;; Shut down the task manager: cancel every running task, which drives each
  ;; detached task's :on-cancel and destroys its subprocess tree. The pool's
  ;; worker threads are daemon, so the JVM *exits* without waiting — but a
  ;; subprocess spawned via ProcessBuilder (e.g. `npm run dev`) is NOT a daemon
  ;; thread; it's an independent OS process that survives JVM exit unless we
  ;; explicitly kill it here. Best-effort. The double-Ctrl-C / SIGTERM paths
  ;; bypass `stop!` and get the same teardown from the JVM shutdown hook below.
  (try (agent/task-shutdown) (catch Throwable _))
  ;; Close all sessions (closes their agents and detaches watches)
  (doseq [idx (sessions/session-indices)]
    (sessions/close-session! idx))
  ;; Also close any agent in !tui-state (backward compat)
  (when-let [^java.io.Closeable ag (tui-session/get-active-agent)]
    (try
      (.close ag)
      (catch Exception _)))
  (tui-session/stop-tui-publisher!)
  ;; Stop file publisher
  (try (tui-log/stop-file-publisher!) (catch Exception _))
  ;; Stop in-process nREPL server if we started one
  (stop-nrepl-server!)
  ;; Tear down any Mode-B side panes/FIFOs. No-op when not installed.
  (try (tmux-side/uninstall!) (catch Exception _))
  (tui-session/clear-agent!)
  ;; Reset session manager
  (reset! tui-session/!root-output-sessions {})
  (sessions/reset-sessions!)
  (layout/teardown!)
  (tui-session/emit! (str "\n" (ansi/muted "TUI session ended.")))
  :ok)

;; ============================================================================
;; Public API — Interaction
;; ============================================================================

(defn ask
  "Ask the current TUI agent a question (synchronous).
   Captures session-idx at start so post-ask output routes correctly
   even if the user switches sessions mid-iteration.
   Returns: result map {:answer ... :usage ...}"
  [input]
  (tui-session/capture-writer!)
  (let [ag (tui-session/get-active-agent)
        origin-sidx (sessions/active-idx)]
    (when-not ag
      (throw (ex-info "No TUI agent running. Call (start! :agent-id) first." {})))
    ;; Direct/inline path (one-shot `bb tui ask`, /continue): echo the input,
    ;; then run the shared lifecycle on the active session. :set-idle? true
    ;; restores the status bar; :rethrow lets REPL/CLI callers see failures.
    (tui-session/emit! (str "\n" (ansi/user-text (format-user-input-display input))) origin-sidx)
    (run-ask-lifecycle {:ag ag :input input :opts nil
                        :origin-sidx origin-sidx :active? true
                        :set-idle? true :error-mode :rethrow})))

(defn ask-async
  "Ask the current TUI agent a question (asynchronous).
   Returns the Clojure agent ref."
  [input]
  (tui-session/capture-writer!)
  (let [ag (tui-session/get-active-agent)]
    (when-not ag
      (throw (ex-info "No TUI agent running. Call (start! :agent-id) first." {})))
    (tui-session/emit! (str "\n" (ansi/user-text (format-user-input-display input))))
    (agent/ask-async ag input)))

;; ============================================================================
;; Interactive Terminal Loop
;; ============================================================================

(defn run!
  "Blocking interactive loop for terminal use.
   Reads lines from *in*, dispatches to ask or meta-commands.
   Type /quit or EOF to exit.

   In terminal mode, enters fullscreen with scroll region + fixed status bar.
   When stdin is a real terminal, enables raw mode for Page Up/Down scrolling.
   Falls back to BufferedReader.readLine() for piped input (scripted tests).
   Accepts same options as start! (including :mode :A | :B from `mode/probe`).
   Extra option:
     :inline - Force inline mode (no alt screen/raw mode)."
  [& opts]
  (let [opts-map (apply hash-map opts)
        force-inline? (:inline opts-map)]
    (apply start! (concat opts [:skip-banner true]))
    ;; Initialize fullscreen layout
    (let [fullscreen-ok? (and (not force-inline?) (layout/init-fullscreen!))]
      ;; Emit the banner once — either into the fullscreen alt-screen
      ;; (with resume-tail replay) or inline. `start!` was told to skip
      ;; its own banner; `run!` owns it to avoid duplicates (teardown
      ;; replays alt-screen scrollback to the primary buffer).
      (let [{:keys [defagent-id agent-id verbosity resumed?]} @tui-session/!tui-state
            ag (tui-session/get-active-agent)
            sess-id (when ag (try (agent/session-id ag) (catch Throwable _ nil)))
            sess    (when ag (try @(:!session ag) (catch Throwable _ nil)))
            lm (try (clj-llm/get-default-lm) (catch Exception _ nil))]
        (when fullscreen-ok?
          ;; Replay the persisted scrollback tail into the alt-screen
          ;; before the banner so prior conversation appears above it.
          (when resumed?
            (when-let [tail (:resume-tail @tui-session/!tui-state)]
              (when (not= "" tail)
                (try (layout/write-output! tail)
                     (catch Throwable _ nil))))
            (swap! tui-session/!tui-state dissoc :resume-tail)))
        ;; Banner / resume notice via layout/write-output! (not
        ;; tui-session/emit!) so the bytes don't tee into the on-disk
        ;; scrollback file.
        (if resumed?
          (layout/write-output!
           (format-resume-notice
            {:session-id    sess-id
             :agent-id      (or defagent-id agent-id)
             :total-turns   (:total-turns sess)
             :updated-at    (:updated-at sess)
             :lm-provider   (:provider lm)
             :lm-model      (:model lm)
             :restored-cost (:resume-restored-cost @tui-session/!tui-state)
             :restored-defs (:resume-restored-defs @tui-session/!tui-state)
             :lost-defs     (:resume-lost-defs @tui-session/!tui-state)}))
          (let [agents (->> (agent/get-tool-defs :type :agent)
                            vals
                            (mapv #(select-keys % [:id]))
                            (sort-by (comp name :id)))]
            (layout/write-output!
             (fmt/format-welcome-banner
              {:agent-id    (or defagent-id agent-id :unknown)
               :session-id  (or sess-id "unknown")
               :verbosity   (or verbosity :normal)
               :lm-provider (:provider lm)
               :lm-model    (:model lm)
               :agents      agents}))))
        ;; One-line MCP connecting/lazy summary, under the banner. The async
        ;; ✓/✗ per-server emits land in the live loop below as connects settle.
        (when-let [s (format-mcp-summary (:mcp-summary @tui-session/!tui-state))]
          (layout/write-output! (str s "\n")))
        (when fullscreen-ok?
          (layout/draw-separator!)
          (layout/draw-bottom-separator!)
          (tui-session/update-status-bar! :idle))))
    (let [use-raw? (and (not force-inline?) (layout/fullscreen?) (terminal/stdin-terminal?))
          reader   (when-not use-raw?
                     (BufferedReader. (InputStreamReader. System/in)))]
      (let [cleanup-hook (Thread. ^Runnable (fn []
                                              ;; Double-Ctrl-C / SIGTERM / crash bypass `stop!`,
                                              ;; so this hook is the only teardown they get.
                                              ;; Kill any detached task subprocesses first —
                                              ;; without this a `npm run dev`-style background
                                              ;; task is orphaned as a live OS process after the
                                              ;; TUI exits. Idempotent: the /quit path already
                                              ;; ran this via stop! and cleared the manager, so
                                              ;; here it's a no-op.
                                              (try (agent/task-shutdown) (catch Throwable _))
                                              ;; Tear down tmux side panes + wheel bindings
                                              ;; + restore prior `mouse` setting before
                                              ;; restoring local terminal state. Without this
                                              ;; the /log pane (and friends) survive the JVM
                                              ;; exit. `uninstall!` is idempotent so the
                                              ;; /quit path (which already called it via stop!)
                                              ;; stays safe.
                                              (try (tmux-side/uninstall!) (catch Exception _))
                                              (when use-raw?
                                                (terminal/restore-cooked-mode!))
                                              (layout/teardown!)))]
        (.addShutdownHook (Runtime/getRuntime) cleanup-hook)
        (try
          (when use-raw? (terminal/set-raw-mode!))
          (when use-raw? (input/start-input-reader! System/in))
          (terminal/install-sigint-handler!)
          (terminal/install-sigwinch-handler!)
          ;; Alternate the idle placeholder between a live agent suggestion and
          ;; rotating static help tips (daemon; self-guards to empty idle prompt).
          (tui-session/start-idle-tip-ticker!)
          (loop []
            (layout/set-input-active! true)
            ;; Rotate the static help tip once per fresh idle prompt so
            ;; successive prompts surface different hints (a live agent
            ;; suggestion still takes priority over the static set).
            (help-tips/rotate-static!)
            (commands/draw-prompt!)
            (let [line (if use-raw?
                         (autocomplete/read-line-raw! System/in)
                         (.readLine ^BufferedReader reader))]
              (layout/set-input-active! false)
              (if (nil? line)
                (stop!)
                (do
                  (layout/scroll-to-bottom!)
                  (layout/redraw-chrome!)
                  (let [input (str/trim line)
                        paused-ag (when (seq input)
                                    (when-let [ag (tui-session/get-active-agent)]
                                      (when (try (agent/paused? (:!state ag))
                                                 (catch Throwable _ false))
                                        ag)))]
                    (if-let [{:keys [promise options]} @tui-session/!pending-feedback]
                      (if-let [n (parse-long input)]
                        (if (and (>= n 1) (<= n (count options)))
                          (let [idx (dec n)
                                selected (nth options idx)]
                            (deliver promise {:selected (:label selected) :index idx})
                            (recur))
                          (do (tui-session/emit! (ansi/warning (str "Invalid. Enter 1-" (count options) ".")))
                              (recur)))
                        (do (tui-session/emit! (ansi/warning "Enter a number to select an option."))
                            (recur)))
                      ;; Active agent paused → a typed line resumes the running
                      ;; iteration loop carrying the line as a mid-run steering
                      ;; note (the LLM is told it was resumed with this request),
                      ;; rather than queueing a separate next turn.
                      (if paused-ag
                        (do (agent/resume-run (:!state paused-ag) input)
                            (tui-session/emit! (ansi/muted "[resumed — steering the loop with your note]"))
                            (recur))
                        (let [result (commands/handle-input-line enqueue-input! input reader)]
                          (when (not= result :quit)
                            (recur))))))))))
          (catch Throwable t
            ;; Log crash to file so native-image issues are diagnosable.
            ;; Prefer ~/.brainyard/logs/; fall back to /tmp only when the
            ;; user-scope dir can't be created.
            (try
              (let [logs-dir (agent/brainyard-subdir!
                              (agent/init-dirs!) "logs" :user)
                    path     (if logs-dir
                               (str logs-dir "/by-crash.log")
                               "/tmp/by-crash.log")]
                (spit path
                      (str "CRASH: " (.getMessage t) "\n"
                           (with-out-str (.printStackTrace t (java.io.PrintWriter. *out*))))))
              (catch Exception _))
            (throw t))
          (finally
            (input/stop-input-reader!)
            (terminal/remove-sigint-handler!)
            (terminal/remove-sigwinch-handler!)
            (when use-raw? (terminal/restore-cooked-mode!))
            (layout/teardown!)
            (try (.removeShutdownHook (Runtime/getRuntime) cleanup-hook)
                 (catch Exception _))
            (System/exit 0)))))))
