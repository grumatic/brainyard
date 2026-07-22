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
            [ai.brainyard.agent-tui.oauth-render :as oauth-render]
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
            [ai.brainyard.ask-channel.interface :as ask-channel]
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

;; Per-ROOT-agent input queues: {root-aid → queue}. Each root gets its own
;; queue + worker (queue/create-queue starts an independent worker future) so
;; chat tabs run CONCURRENTLY. Rendering is already session-scoped (per-root
;; think block, session-aware emit, owner-anchored task/iter blocks), so a
;; background tab's turn buffers into its own session. The per-tab pending
;; count is each queue's own queue-length.
(defonce !input-queues (atom {}))
;; The task$wakeup turn-submitter is global (one registration) and routes via
;; enqueue-input!; this guards against re-registering per queue.
(defonce ^:private !turn-submitter-registered? (atom false))
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
;; follows the same chain. nREPL is full-trust: reaching the loopback
;; server gives full eval (the only eval-path check is the deny-list).
;; See docs/design/clj-nrepl-eval.md.
(defonce !nrepl-server (atom nil))

;; Side ask channel (docs/design/ask-attach-channel.md). One AF_UNIX listener
;; per session, keyed by the agent's on-disk session-id. On by default; gated
;; by config :ask-channel-enabled?. Lifecycle mirrors the nREPL pair but is
;; per-session: started in create-tui-agent!, stopped on session close / stop!.
(defonce !ask-listeners (atom {}))

;; Per-session ownership lock (by-host.lock). Acquired in create-tui-agent!
;; alongside the ask listener, released on the same teardown paths, so exactly
;; one live process owns a session's on-disk state + ask.sock. Keyed by
;; session-id → the persist lock handle. See docs/design/session-channel-extensions.md §1.
(defonce !session-locks (atom {}))

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
        (mulog/info ::nrepl-bootstrapped :port (:port srv)))
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
  (agent/register-hook! :agent.iteration/auto-parked ::tui-auto-parked
                        tui-session/auto-parked-handler :source :tui)
  (agent/register-hook! :agent.task/auto-resumed ::tui-auto-resumed
                        tui-session/auto-resumed-handler :source :tui)
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
      (tui-session/update-status-bar! :running))
    ;; Start the per-root think spinner regardless of which tab is foreground —
    ;; a background tab's spinner is painted by the shared ticker the moment the
    ;; user switches to it. Keyed per root agent, so concurrent tabs don't
    ;; collide.
    (tui-session/start-thinking-indicator! ag)
    ;; Register this turn's thread under its session so Ctrl-C on THIS tab
    ;; cancels it (tabs run concurrently — multiple asks in flight at once).
    (swap! input/!ask-threads assoc origin-sidx (Thread/currentThread))
    (try
      (let [result        (agent/ask ag input opts)
            _             (Thread/interrupted)
            cancelled?    (agent/cancelled? (:!state ag))
            usage-after   (helpers/get-usage-totals ag)
            diff          (helpers/usage-diff usage-before usage-after)
            still-active? (= origin-sidx (sessions/active-idx))]
        (tui-session/stop-thinking-indicator! ag)
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
        (tui-session/stop-thinking-indicator! ag)
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
        (swap! input/!ask-threads dissoc origin-sidx)))))

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
      ;; A side-ask (`:reply` promise in opts) must always settle its promise so
      ;; the attach client never hangs — even on the skip branches below.
      (nil? ag)
      (do (some-> (:reply opts) (deliver {:error "no active agent"})) nil)

      ;; Targeted resume (wakeup) whose session was closed before the timer
      ;; fired — nothing to render into; skip rather than hijack the active
      ;; session.
      (and targeted? (nil? sidx))
      (do (some-> (:reply opts) (deliver {:error "target session closed"}))
          (mulog/warn ::wakeup-target-session-closed :agent-id (:agent-id ag)))

      :else
      ;; For a targeted resume (wakeup) pin emit-routing to the parked agent's
      ;; session via *render-session-idx* (terminal-only ops self-skip when it
      ;; isn't the active session). For normal user input leave it nil so the
      ;; original active-terminal path is unchanged.
      (binding [tui-session/*render-session-idx* (when targeted? sidx)]
        (let [origin-sidx (if targeted? sidx (sessions/active-idx))
              active?     (= origin-sidx (sessions/active-idx))
              ;; Queue path leaves status-bar transitions to its notify-fn, so no
              ;; :idle here; surfaces errors as a line (the worker swallows throws).
              result      (run-ask-lifecycle {:ag ag :input input :opts opts
                                              :origin-sidx origin-sidx :active? active?
                                              :set-idle? false :error-mode :emit})]
          ;; Hand the result back to a side-ask attach client, if one is waiting.
          (some-> (:reply opts) (deliver result))
          result)))))

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

(defn- session-idx-for-root-aid
  "The chat session index whose root agent instance id is `root-aid`, or nil."
  [root-aid]
  (when root-aid
    (:id (first (filter #(= root-aid (:agent-id %)) (sessions/session-list))))))

(defn- tui-queue-notify-fn
  "Notification for ONE root's input queue (`root-aid` is curried in at queue
   creation). Tabs run concurrently, so each event must land on the queue's own
   tab: the pending count is stored on that session (the status bar reads the
   ACTIVE session's `:queue-count`), and the :idle/:running status transition is
   applied ONLY when that tab is foreground — a background tab finishing must
   not flip the active tab to idle."
  [root-aid event item queue-info]
  (let [queue-len (:queue-length queue-info)
        sidx      (or (some-> (:agent (:opts item)) tui-session/session-idx-for-agent)
                      (session-idx-for-root-aid root-aid))
        active?   (and sidx (= sidx (sessions/active-idx)))
        set-count! (fn [n] (when sidx (sessions/update-session! sidx assoc :queue-count n)))
        ;; :idle only when the finishing turn is on the active tab; otherwise
        ;; just re-render (active tab's own status/count unchanged).
        idle-or-redraw (fn [] (if active?
                                (tui-session/update-status-bar! :idle)
                                (tui-session/update-status-bar!)))]
    (case event
      :enqueued
      (do (set-count! queue-len) (tui-session/update-status-bar!))

      :processing
      (let [echo      (str "\n" (ansi/user-text (format-user-input-display (:input item))))
            target-ag (:agent (:opts item))]
        ;; Route the input echo to the queue's session (targeted resume or the
        ;; tab the input was typed in) rather than whatever's on screen.
        (cond
          sidx      (tui-session/emit! echo sidx)
          target-ag nil
          :else     (tui-session/emit! echo))
        (set-count! queue-len)
        (tui-session/update-status-bar!))

      :completed  (do (set-count! queue-len) (idle-or-redraw))
      :error      (do (set-count! queue-len) (idle-or-redraw))
      :cancelled  (do (set-count! queue-len) (tui-session/update-status-bar!))
      :queue-empty (do (set-count! 0) (idle-or-redraw))

      nil)))

(declare enqueue-input!)

(defn- ensure-input-queue-for-root!
  "Return (lazily creating) the input queue for root agent `root-aid`. Each
   root gets its own queue + worker so tabs run concurrently. The task$wakeup
   turn-submitter is registered once, globally, and routes via enqueue-input!
   (which dispatches to the right root's queue)."
  [root-aid]
  (when (compare-and-set! !turn-submitter-registered? false true)
    (agent/register-turn-submitter!
     (fn [agent input opts] (enqueue-input! input (assoc opts :agent agent)))))
  (or (get @!input-queues root-aid)
      (-> (swap! !input-queues
                 (fn [m] (if (contains? m root-aid)
                           m
                           (assoc m root-aid
                                  (agent/create-queue
                                   tui-queue-process-fn
                                   (fn [event item info]
                                     (tui-queue-notify-fn root-aid event item info)))))))
          (get root-aid))))

(defn stop-input-queue-for-root!
  "Stop + drop root `root-aid`'s input queue (called when its chat session
   closes). Idempotent."
  [root-aid]
  (when-let [!queue (get @!input-queues root-aid)]
    (try (agent/stop-queue! !queue) (catch Exception _))
    (swap! !input-queues dissoc root-aid)))

(defn enqueue-input!
  "Enqueue an input for processing. User text is displayed by :processing event.
   Rejects input for output-only sessions.
   `opts` (optional, e.g. {:source :wakeup}) is forwarded to agent/ask via the
   queue — used by task$wakeup resumes so they serialize with user turns."
  ([input] (enqueue-input! input nil))
  ([input opts]
   ;; Resolve the owning agent: an explicit :agent (task$wakeup resume of a
   ;; possibly-background agent) wins; otherwise the active tab's agent. Tag it
   ;; into opts so the turn runs against the tab it was typed in even after a
   ;; tab switch, and ROUTE it to that agent's root queue so tabs run
   ;; concurrently.
   (let [targeted? (some? (:agent opts))
         ag        (or (:agent opts) (tui-session/get-active-agent))
         root-aid  (tui-session/root-agent-id ag)]
     (cond
       ;; Normal keyboard input into an output-only tab (a shared sub-output
       ;; tab has no root agent of its own) → reject. A TARGETED resume bypasses
       ;; this: it runs against a specific background agent, not whatever tab is
       ;; on screen, so it must not be blocked by an output tab being active.
       (and (not targeted?)
            (= :output (:session-type (sessions/get-active-session))))
       (tui-session/emit!
        (ansi/warning "This is an output-only session. Use /session <N> to switch to a chat session, or /session close to close this one."))

       (nil? ag)
       (throw (ex-info "No TUI agent running. Call (start! :agent-id) first." {}))

       ;; No root agent to own a queue (e.g. an output / agentless session, or a
       ;; resume whose target has no resolvable root) — nothing to run against;
       ;; skip rather than create a bogus nil-keyed queue.
       (nil? root-aid)
       (mulog/warn ::enqueue-no-root-agent :targeted? targeted?
                   :agent-id (when ag (try (agent/agent-id ag) (catch Throwable _ nil))))

       :else
       (let [!queue (ensure-input-queue-for-root! root-aid)
             result (agent/enqueue! !queue input (merge {:agent ag} opts))]
         (when (:error result)
           (tui-session/emit! (ansi/warning "Input queue is full (max 10). Please wait."))))))))

;; ============================================================================
;; Side ask channel — per-session AF_UNIX listener
;; ============================================================================
;;
;; `by ask --attach <session-id>` connects to <session-dir>/ask.sock and injects
;; a question into THIS session's turn queue, then blocks for the answer. The
;; question rides the same path as a keyboard turn (serialized, visible in the
;; tab) by enqueuing with `{:agent ag :source :side-ask :reply <promise>}` — the
;; queue process-fn delivers the ask result to the promise. See
;; docs/design/ask-attach-channel.md.

(defn inject-side-ask!
  "Enqueue `question` as a turn for the specific agent `ag`, returning a promise
   that resolves to the ask result map (`{:answer …}` / `{:error …}`) when the
   turn completes, or nil if it was cancelled. Targets `ag` directly (bypassing
   the active-session output-only guard in `enqueue-input!`) so a side-ask lands
   on the right tab regardless of which one is on screen."
  [ag question]
  (let [p      (promise)
        !queue (ensure-input-queue-for-root! (tui-session/root-agent-id ag))
        result (agent/enqueue! !queue question {:agent ag :source :side-ask :reply p})]
    (when (:error result)
      (deliver p {:error "input queue is full (max 10)"}))
    p))

(defn- session-lm
  "The live session's effective LM (per-agent :lm-config override, else the
   process-global default set by setup-lm! / `/model`). nil-safe."
  [ag]
  (try (or (agent/get-config ag :lm-config) (clj-llm/get-default-lm))
       (catch Throwable _ nil)))

(defn- handle-ask-op
  "Inject the question as a turn, await the reply (bounded by the lesser of the
   client timeout and the server cap), and shape the EDN wire response."
  [ag cap-ms {:keys [question timeout-ms]}]
  (if (or (nil? question) (str/blank? question))
    {:status :error :error "empty question"}
    (let [tmo (min cap-ms (or timeout-ms cap-ms))
          res (deref (inject-side-ask! ag question) tmo ::timeout)]
      (cond
        (= ::timeout res) {:status :error :error (str "timed out after " tmo "ms")}
        (nil? res)        {:status :error :error "no answer (turn cancelled or failed)"}
        (:error res)      {:status :error :error (:error res)}
        :else
        ;; Stamp the live session's provider/model/agent so a --json attach
        ;; client can report which LM actually answered.
        (let [lm (session-lm ag)]
          {:status   :ok
           :answer   (:answer res)
           :usage    (:usage res)
           :provider (some-> (:provider lm) name)
           :model    (:model lm)
           :agent    (some-> (try (agent/defagent-type ag) (catch Throwable _ nil)) name)})))))

(defn- handle-status-op
  "Non-blocking snapshot of the live session — never injects a turn. Lets an
   external scheduler/connector decide whether to poke. See
   docs/design/session-channel-extensions.md §3 (Mode A)."
  [ag]
  (let [sidx     (try (tui-session/session-idx-for-agent ag) (catch Throwable _ nil))
        running? (boolean (and sidx (get @input/!ask-threads sidx)))
        root-aid (try (tui-session/root-agent-id ag) (catch Throwable _ nil))
        !queue   (when root-aid (get @!input-queues root-aid))
        pending  (if !queue (count (:items (agent/get-queue-info !queue))) 0)
        lm       (session-lm ag)]
    {:status        :ok
     :state         (if running? :running :idle)
     :pending-turns pending
     :session-id    (try (agent/session-id ag) (catch Throwable _ nil))
     :agent         (some-> (try (agent/defagent-type ag) (catch Throwable _ nil)) name)
     :provider      (some-> (:provider lm) name)
     :model         (:model lm)
     :pid           (.pid (java.lang.ProcessHandle/current))}))

(defn- inject-memory!
  "Write external data into the project's file-based memory as
   `<project-config-dir>/memory/<slug>.md` and add/update its `index.md` pointer
   so it's discoverable in the `## Project Memory` context section. Delegates to
   the shared component writer (`agent/write-memory!`) — the same writer the
   reactor's `:as :memory` action uses. Returns the wire response."
  [ag slug content]
  (let [pcd (some-> (agent/get-config ag :dirs) agent/project-config-dir)
        r   (agent/write-memory! pcd slug content)]
    (if (:error r)
      {:status :error :error (:error r)}
      {:status :ok :injected :memory :slug (:slug r)
       :path (:path r) :indexed (:indexed r)})))

(defn- handle-inject-op
  "Data-connector verb: push external data INTO the session via one of three
   sinks (see docs/design/session-channel-extensions.md §4).
     :as :artifact — a live artifact, seen next turn, no forced turn (the
                     canonical data connector); :path (file) or :content (inline).
     :as :turn     — inject as a turn; :await? false (default) is fire-and-forget
                     (event trigger), :await? true blocks for the answer.
     :as :memory   — write a `<slug>.md` into project file memory."
  [ag cap-ms {:keys [as name content path slug text await? timeout-ms pin?]}]
  (case as
    :artifact
    (let [r (agent/add-artifact! ag {:path path :content content :name name :pinned pin?})]
      (if (:error r)
        {:status :error :error (:error r)}
        {:status :ok :injected :artifact :id (:id r) :name (:name r)}))

    :turn
    (let [turn-text (or text content)]
      (if (str/blank? (str turn-text))
        {:status :error :error "empty :text for :as :turn"}
        (let [p (inject-side-ask! ag turn-text)]
          (if (false? await?)
            {:status :ok :injected :turn :queued true}
            (let [tmo (min cap-ms (or timeout-ms cap-ms))
                  res (deref p tmo ::timeout)]
              (cond
                (= ::timeout res) {:status :error :error (str "timed out after " tmo "ms")}
                (nil? res)        {:status :error :error "no answer (turn cancelled or failed)"}
                (:error res)      {:status :error :error (:error res)}
                :else             {:status :ok :injected :turn :answer (:answer res)}))))))

    :memory
    (inject-memory! ag slug content)

    {:status :error
     :error  (str "unknown inject target :as " (pr-str as)
                  " (want :artifact, :turn, or :memory)")}))

(defn- edn-safe
  "Coerce an arbitrary hook payload into EDN-roundtrippable data for the wire:
   scalars pass through, collections recurse, and anything else (records,
   exceptions, fns, the Agent instance) is stringified. Keeps subscription
   frames small and `pr-str`-safe."
  [v]
  (cond
    (or (nil? v) (string? v) (boolean? v) (keyword? v) (symbol? v) (number? v)) v
    (map? v)  (persistent! (reduce-kv (fn [m k vv] (assoc! m (edn-safe k) (edn-safe vv)))
                                      (transient {}) v))
    (coll? v) (mapv edn-safe v)
    :else     (str v)))

(defn- handle-subscribe-op
  "Streaming verb (Mode B): keep the connection open and push one frame per
   matching runtime event until the client disconnects. Scoped to THIS session
   (sub-agents inherit the session-id; process-level events with no agent are
   included). Backpressure: a bounded queue drops events for a slow consumer
   rather than stalling the agent. See docs/design/session-channel-extensions.md §5."
  [ag {:keys [events]}]
  (let [event-keys (->> events (map keyword) (filter some?) distinct vec)
        sid        (try (agent/session-id ag) (catch Throwable _ nil))]
    (if (empty? event-keys)
      {:status :error :error ":events must be a non-empty vector of event keys"}
      (ask-channel/stream-response
       (fn [emit! alive?]
         (let [q   (java.util.concurrent.LinkedBlockingQueue. 512)
               src (keyword "ask-sub" (str (gensym)))]
           (doseq [ek event-keys]
             (agent/register-hook!
              ek src
              (fn [payload]
                (let [ev-sid (or (:session-id payload)
                                 (try (some-> (:agent payload) agent/session-id) (catch Throwable _ nil)))]
                  ;; session-scoped: this session's events (+ agentless process events)
                  (when (or (nil? ev-sid) (nil? sid) (= ev-sid sid))
                    (.offer q {:event ek :sid sid :payload (edn-safe (dissoc payload :agent))}))))
              :source src))
           (try
             (emit! {:status :ok :subscribed event-keys})
             (loop []
               (when (alive?)
                 (when-let [frame (.poll q 1 java.util.concurrent.TimeUnit/SECONDS)]
                   (emit! frame))
                 (recur)))
             (finally
               (agent/unregister-source! src)))))))))

(defn- handle-config-op
  "Non-blocking read of the session's effective configuration — never injects a
   turn. Mirrors the LLM-facing `agent-runtime$config` read: `:overrides` (schema
   keys whose effective value differs from their default) plus the full redacted
   `:snapshot`. An optional `:query` narrows to keys matching a term. Secrets
   (`:api-key` leaves) are masked by the config layer's own redaction. All wire
   values are run through `edn-safe` so the frame is always `pr-str`-clean."
  [ag {:keys [query]}]
  (let [lm   (session-lm ag)
        base {:status     :ok
              :session-id (try (agent/session-id ag) (catch Throwable _ nil))
              :agent      (some-> (try (agent/defagent-type ag) (catch Throwable _ nil)) name)
              :provider   (some-> (:provider lm) name)
              :model      (:model lm)}]
    (if-not (str/blank? (str query))
      (assoc base :query query :matches (edn-safe (agent/search-config-keys ag query)))
      (let [ov (agent/config-overview ag)]
        (assoc base
               :total     (:total ov)
               :overrides (edn-safe (:overrides ov))
               :snapshot  (edn-safe (agent/redact-config-snapshot
                                     (agent/get-config-snapshot ag))))))))

(defn- handle-emit-op
  "Fire a user-defined event onto the hooks bus (external → agent). Validates the
   payload against the event's registered :payload-schema when declared, stamps
   this session-id so it's scoped to this session's `:subscribe` streams (and,
   Phase 2, its reactions). See docs/design/event-bus-and-reactor.md §3.2."
  [ag {:keys [event payload]}]
  (if (str/blank? (str event))
    {:status :error :error ":event is required for :op :emit"}
    (let [sid  (try (agent/session-id ag) (catch Throwable _ nil))
          base (cond-> (if (map? payload) payload {})
                 sid (assoc :session-id sid))
          r    (agent/emit-event! ag event base)]
      (if (:error r)
        {:status :error :error (:error r)}
        (cond-> {:status :ok :emitted (:fired r) :subscribers (:subscribers r)}
          (:note r) (assoc :note (:note r)))))))

(defn- handle-fsm-status-op
  "Non-blocking snapshot of this session's state machines — current state +
   context + last transition per machine. See docs/design/state-machine-design.md §7."
  [ag]
  {:status :ok :machines (edn-safe (vec (or (try (agent/session-states-for ag)
                                                 (catch Throwable _ nil))
                                            [])))})

(defn- handle-new-session-op
  "Spawn an additional LIVE session inside THIS process and return its identity —
   the process-level counterpart to the interactive `/session new`. Lets an
   external driver host many sessions in one JVM instead of one process each:
   the new session gets its own agent (per-request `:agent-id`), ownership lock,
   and ask socket, so it's reachable over its own `ask-socket-path` exactly like
   a standalone session. Does NOT switch the active tab (headless callers don't
   want the local terminal's focus to move). `:agent-id` is a defagent type
   string (e.g. \"mcp-agent\"); omitted → the active session's defagent, else
   coact-agent. `:label` is optional. Returns {:status :ok :session-id … :ask-socket-path …}."
  [{:keys [agent-id label] :as _req}]
  (try
    (let [agent-kw (cond
                     (keyword? agent-id) agent-id
                     (and (string? agent-id) (seq agent-id)) (keyword agent-id)
                     :else nil)
          lbl      (or (when (and (string? label) (seq label)) label)
                       (sessions/next-root-tab-label!))
          idx      (sessions/create-session!
                    (cond-> {:label lbl}
                      agent-kw (assoc :agent-id agent-kw)))
          sess     (sessions/get-session idx)
          sid      (:agent-session-id sess)
          sock     (when sid (.getAbsolutePath ^java.io.File (persist/file-of sid :ask-sock)))]
      (if sid
        {:status :ok :session-id (str sid) :ask-socket-path sock
         :defagent-id (some-> (:defagent-id sess) name) :label lbl :index idx}
        {:status :error :error "session created but no session-id was assigned"}))
    (catch Throwable e
      {:status :error :error (str "new-session failed: " (.getMessage e))})))

(defn- handle-close-session-op
  "Gracefully close ONE co-hosted session by its `:session-id` (the on-disk
   agent-session-id) — the counterpart to `/session close`. Closes that session's
   agent + ask socket + tab without killing the host process (which may hold other
   sessions). Refuses to close the process's last remaining chat session so the
   host never ends up with zero sessions. Returns {:status :ok :closed …}."
  [{:keys [session-id] :as _req}]
  (try
    (if (str/blank? (str session-id))
      {:status :error :error "close-session requires :session-id"}
      (let [target (str session-id)
            sessions* (sessions/session-list)
            match (some (fn [s] (when (= target (str (:agent-session-id s))) s)) sessions*)
            chat-count (count (remove #(= :output (:session-type %)) sessions*))]
        (cond
          (nil? match)
          {:status :error :error (str "no live session with id " target)}
          (and (not= :output (:session-type match)) (<= chat-count 1))
          {:status :error :error "refusing to close the host's last session"}
          :else
          (do (sessions/close-session! (:id match))
              {:status :ok :closed target}))))
    (catch Throwable e
      {:status :error :error (str "close-session failed: " (.getMessage e))})))

(defn- handle-rename-session-op
  "Rename THIS session's live TUI tab to `:label` — the process-level counterpart
   to the interactive `/session rename`. Lets `by sessions label <id> <text>`
   (a separate process) propagate to a running `by` so the tab strip updates
   without a restart. A blank/omitted `:label` clears back to a default `mainN`.
   The persisted label is written by the CLI caller (and again by
   `rename-session!`); this op syncs the in-memory tab. Returns {:status :ok
   :label …}."
  [ag {:keys [label] :as _req}]
  (try
    (let [sid (try (agent/session-id ag) (catch Throwable _ nil))]
      (if (str/blank? (str sid))
        {:status :error :error "session has no resolvable session-id"}
        (let [lbl     (if (str/blank? (str label))
                        (sessions/next-root-tab-label!)
                        (str/trim label))
              applied (sessions/rename-by-agent-session-id! (str sid) lbl)]
          (if applied
            {:status :ok :label applied}
            {:status :error :error (str "no live tab for session " sid)}))))
    (catch Throwable e
      {:status :error :error (str "rename-session failed: " (.getMessage e))})))

(defn- ask-handle-fn
  "Op-dispatcher for a session's ask socket. `:ask` injects a question and blocks
   for the answer; `:status` returns a non-blocking snapshot; `:config` returns a
   non-blocking effective-config read; `:inject` pushes external data in (artifact
   / turn / memory); `:cancel` stops the running turn; `:subscribe` streams
   runtime events until disconnect; `:emit` fires a user-defined event onto the
   bus; `:fsm-status` snapshots this session's state machines. `:new-session`
   spawns another session in THIS process (returns its id + socket); `:close-session`
   closes one co-hosted session by id. The last two are process-level (they don't
   use `ag`) — any live session's socket can service them, so an external driver
   can host many sessions in one JVM. Unknown ops get a clear error. See
   docs/design/session-channel-extensions.md, event-bus-and-reactor.md, and
   state-machine-design.md."
  [ag cap-ms]
  (fn [{:keys [op] :as req}]
    (case op
      :ask    (handle-ask-op ag cap-ms req)
      :status (handle-status-op ag)
      :config (handle-config-op ag req)
      :inject     (handle-inject-op ag cap-ms req)
      :cancel     {:status :ok :cancelled (boolean (input/cancel-ask-for-agent! ag))}
      :subscribe  (handle-subscribe-op ag req)
      :emit       (handle-emit-op ag req)
      :fsm-status (handle-fsm-status-op ag)
      :new-session    (handle-new-session-op req)
      :close-session  (handle-close-session-op req)
      :rename-session (handle-rename-session-op ag req)
      {:status :error :error (str "unknown op: " op)})))

(defn start-ask-listener!
  "Open the per-session ask socket for agent `ag` when :ask-channel-enabled?.
   Idempotent per session-id; persists :ask-socket-path into meta.edn. Failures
   are non-fatal — the session runs fine without an attach socket."
  [ag]
  (when (agent/get-config :ask-channel-enabled?)
    (let [sid (try (agent/session-id ag) (catch Throwable _ nil))]
      (when (and sid (not (contains? @!ask-listeners sid)))
        (try
          (let [path   (.getAbsolutePath ^java.io.File (persist/file-of sid :ask-sock))
                cap-ms (or (agent/get-config :ask-timeout-ms) 120000)
                handle (ask-channel/start-listener! path (ask-handle-fn ag cap-ms))]
            (swap! !ask-listeners assoc sid handle)
            ;; Record the socket path + the verbs this listener answers, so a
            ;; discovery client (`by sessions list`) can advertise capability
            ;; without connecting. Keep in sync with `ask-handle-fn`'s dispatch.
            (try (persist/save-meta! sid {:ask-socket-path path
                                          :ops [:ask :status :config :inject :cancel :subscribe :emit :fsm-status
                                                :new-session :close-session :rename-session]})
                 (catch Throwable _))
            (mulog/info ::ask-listener-bootstrapped :session-id sid :path path))
          (catch clojure.lang.ExceptionInfo e
            (if (= :live-owner (:reason (ex-data e)))
              ;; Another live process already owns this session's socket — never
              ;; clobber it. The resume pre-flight normally refuses earlier; this
              ;; is the deep guard for the pre-flight↔bind race. Session still
              ;; opens, just without an attach socket.
              (mulog/warn ::ask-socket-owned-by-live-process :session-id sid
                          :path (:path (ex-data e)))
              (mulog/warn ::ask-listener-bootstrap-failed :session-id sid :error (.getMessage e))))
          (catch Throwable e
            (mulog/warn ::ask-listener-bootstrap-failed :session-id sid :error (.getMessage e))))))))

(defn stop-ask-listener!
  "Stop and unregister the ask listener for `sid` (a session-id string).
   Idempotent and non-throwing."
  [sid]
  (when-let [handle (get @!ask-listeners sid)]
    (try (ask-channel/stop-listener! handle) (catch Throwable _))
    (swap! !ask-listeners dissoc sid)))

(defn stop-all-ask-listeners!
  "Stop every ask listener — used by `stop!` and the JVM shutdown hook."
  []
  (doseq [sid (keys @!ask-listeners)]
    (stop-ask-listener! sid)))

(defn acquire-session-lock!
  "Claim the per-session ownership lock for agent `ag` and stamp the owning PID
   into meta.edn (discovery surface). Idempotent per session-id; same-process
   re-acquire is allowed by the lock. A cross-process holder shouldn't reach here
   (the resume pre-flight in main.clj refuses first) — if it does we log and run
   on best-effort, never blocking session boot. Non-throwing."
  [ag]
  (let [sid (try (agent/session-id ag) (catch Throwable _ nil))]
    (when (and sid (not (contains? @!session-locks sid)))
      (try
        (if-let [handle (persist/try-acquire-lock! sid)]
          (do (swap! !session-locks assoc sid handle)
              (try (persist/save-meta! sid {:pid (:pid handle)}) (catch Throwable _))
              (mulog/info ::session-lock-acquired :session-id sid :pid (:pid handle)))
          (mulog/warn ::session-lock-contended :session-id sid
                      :owner-pid (try (persist/owner-pid sid) (catch Throwable _ nil))))
        (catch Throwable e
          (mulog/warn ::session-lock-failed :session-id sid :error (.getMessage e)))))))

(defn release-session-lock!
  "Release and unregister the ownership lock for `sid`. Idempotent, non-throwing."
  [sid]
  (when-let [handle (get @!session-locks sid)]
    (try (persist/release-lock! handle) (catch Throwable _))
    (swap! !session-locks dissoc sid)))

(defn release-all-session-locks!
  "Release every session lock — used by `stop!` and the JVM shutdown hook."
  []
  (doseq [sid (keys @!session-locks)]
    (release-session-lock! sid)))

;; ============================================================================
;; Public API — Lifecycle
;; ============================================================================

(defn create-tui-agent!
  "Create and configure a TUI agent for a session.
   Shared by start! (session 0) and sessions/create-session! (new sessions).
   Uses invoke-tool with :setup-only? true to follow the same dispatch path
   as any defagent invocation, with a generated instance-id.
   Returns the agent instance with permission-fn, user-feedback-fn, and dirs configured."
  [agent-id & {:keys [user-id session-id max-iterations instance-id display-format
                      acp-backend acp-backend-opts]}]
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
        ;; `:display-format` is a schema config-key, so passing it as a setup
        ;; option seeds a per-agent config override (transient, not persisted) —
        ;; how the `-v` flag reaches the config source of truth. Omitted when nil
        ;; so callers without it fall through to the global/default layer.
        ;; `:max-iterations` and `:display-format` are schema config-keys, so
        ;; passing them as setup options seeds per-agent config overrides — how
        ;; the CLI flags (`-n`, `-v`) reach the config source of truth. Both are
        ;; omitted when nil so callers without them fall through to the
        ;; session/global/default layers (a nil override would make get-config
        ;; return nil and break /status).
        ag (agent/invoke-tool agent-id
                              (cond-> {:id inst-id
                                       :setup-only? true
                                       :agent-session {:user-id user-id :session-id sess-id}
                                       :session-store !session-store}
                                max-iterations (assoc :max-iterations max-iterations)
                                display-format (assoc :display-format display-format)
                                ;; acp-agent root: pin the backend/model chosen at
                                ;; `/agent new acp-agent <backend> <model>`. These
                                ;; are config-schema keys, so they seed per-agent
                                ;; overrides read by config/get-config.
                                acp-backend (assoc :acp-backend acp-backend)
                                acp-backend-opts (assoc :acp-backend-opts acp-backend-opts)))
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
    ;; Claim the per-session ownership lock (stamps :pid into meta.edn) so a
    ;; second live process can't silently co-own this session's state + socket.
    ;; Non-fatal: the resume pre-flight already refused a live cross-process open.
    (acquire-session-lock! ag)
    ;; Open this session's side-ask socket (on by default; non-fatal on failure)
    ;; so `by ask --attach <session-id>` can inject questions into its turn queue.
    (start-ask-listener! ag)
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

(defn- write-startup-notice!
  "Emit (once) a notice stashed by `create-tui-agent!` that must appear AFTER the
   banner — e.g. the no-provider-key warning. It's deferred because
   create-tui-agent! runs before the alt-screen is initialized, which would wipe
   an early emit before the user could read it. Routes through
   `layout/write-output!` like the banner so it lands on the active surface
   (fullscreen or inline) and is called from BOTH banner sites (`start!`'s
   inline/REPL path and `run!`'s fullscreen path); `skip-banner` ensures exactly
   one of them runs per launch."
  []
  (when-let [notice (:startup-notice @tui-session/!tui-state)]
    (layout/write-output! (str notice "\n"))
    (swap! tui-session/!tui-state dissoc :startup-notice)))

(defn start!
  "Start a TUI session with an agent.

   Parameters:
     agent-id - keyword agent ID (e.g., :coact-agent)

   Options:
     :user-id        - User ID (default: resolved via helpers/resolve-user-id —
                       BY_USER_ID env, else the `user.name` system property)
     :session-id     - Session ID (default: auto-generated)
     :max-iterations - Override max iterations
     :display-format      - :quiet | :normal | :verbose (default: :normal)
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
  [& {:keys [agent-id user-id session-id max-iterations display-format
             lm-provider lm-model inline mode resume? skip-banner]
      :or {agent-id :coact-agent
           display-format :normal
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

  ;; 2. Auto-setup LM if requested. A provider that needs an API key but has
  ;;    none must NOT abort the session: notify and boot WITHOUT a default LM so
  ;;    the interactive user can `/model` to a usable provider (or set the key
  ;;    and relaunch) and keep working, rather than seeing the session die. The
  ;;    one-shot `by ask` path pre-flights and exits instead (no way to recover
  ;;    mid-turn). The notice is STASHED (not emitted here) and surfaced AFTER
  ;;    the banner by `write-startup-notice!` — create-tui-agent! runs before the
  ;;    alt-screen is initialized, which would wipe an early emit before the user
  ;;    could read it. See helpers/no-provider-message.
  (when lm-provider
    (if (helpers/missing-provider-key lm-provider)
      (swap! tui-session/!tui-state assoc :startup-notice
             (ansi/warning (helpers/no-provider-message lm-provider)))
      (helpers/setup-lm! lm-provider :model lm-model)))

  ;; 2b. Mulog publisher setup
  (when (= display-format :verbose)
    (tui-session/start-tui-publisher!))
  ;; 2b1. Dedicated memory-activity publisher (always on; gated live by the
  ;;      :show-memory-activity config key). Surfaces background L2→L3
  ;;      consolidation / graph-extraction milestones so the user sees memory
  ;;      working even in normal/quiet mode.
  (tui-session/start-memory-activity-publisher!)

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

  ;; 2e. Side ask channel: stop a session's ask listener when its tab closes.
  ;;     Per-session sockets are opened in create-tui-agent!; this hook is the
  ;;     teardown counterpart (idempotent on key, safe across reloads).
  (sessions/register-before-close-hook!
   :ask-channel
   (fn [_idx session]
     (doseq [ag (or (seq (:agent-instances session))
                    (some-> (:agent session) vector))]
       (when-let [sid (try (agent/session-id ag) (catch Throwable _ nil))]
         (stop-ask-listener! sid)
         (release-session-lock! sid)))))

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

  ;; Apply OAuth config (token-store backend + default flow) and route device/
  ;; auth verification prompts to the rich TUI renderer (code box + optional QR)
  ;; before any OAuth-gated MCP server connects.
  (try (agent/apply-oauth-config!)
       (oauth-render/register!)
       (catch Throwable e
         (mulog/warn ::oauth-setup-failed :error (ex-message e))))

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
        ;; Restore the persisted model on resume unless the CLI passed an
        ;; explicit --model override. switch-model! writes :model/:provider to
        ;; meta.edn on every /model swap; rebuild + install the default LM here
        ;; so the resumed session continues on the model the user last chose.
        _ (when (and resuming? (:model resumed-meta) (not lm-model))
            (try
              (clj-llm/configure-default-lm!
               (clj-llm/create-lm (cond-> {:model (:model resumed-meta)}
                                    (:provider resumed-meta) (assoc :provider (:provider resumed-meta)))))
              (catch Throwable _)))
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
                              :max-iterations max-iter
                              :display-format display-format)
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
                (let [sandbox (clj-sandbox/create-sandbox
                               :bindings bindings
                               :interop (agent/resolve-sandbox-interop ag))]
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
               :lm-provider (:provider lm)
               :lm-model    (:model lm)
               :agents      agents}))))
        ;; One-line MCP connecting/lazy summary, under the banner.
        (when-let [s (format-mcp-summary (:mcp-summary @tui-session/!tui-state))]
          (layout/write-output! (str s "\n")))
        ;; CR-MEM-21: if the embedding model changed, semantic recall is paused
        ;; until the vector index is rebuilt — surface the one-line guidance.
        (when-let [notice (agent/graph-vec-stale-notice ag)]
          (layout/write-output! (str notice "\n")))
        ;; Deferred startup notice (e.g. no provider key) — after the banner.
        (write-startup-notice!)))
    :ok))

(defn stop!
  "Stop TUI session. Closes all sessions, detaches watches, tears down hooks,
   shuts down task manager and queue."
  []
  ;; Remove every TUI-registered hook in one call
  (try (agent/unregister-source! :tui) (catch Exception _))
  ;; Tear down the persist bridge (unregisters the :persist hook source).
  (try (persist-bridge/stop!) (catch Exception _))
  ;; Stop every per-root input queue
  (doseq [[_ !queue] @!input-queues]
    (try (agent/stop-queue! !queue) (catch Exception _)))
  (reset! !input-queues {})
  (reset! !turn-submitter-registered? false)
  ;; Shut down the task manager: cancel every running task, which drives each
  ;; detached task's :on-cancel and destroys its subprocess tree. The pool's
  ;; worker threads are daemon, so the JVM *exits* without waiting — but a
  ;; subprocess spawned via ProcessBuilder (e.g. `npm run dev`) is NOT a daemon
  ;; thread; it's an independent OS process that survives JVM exit unless we
  ;; explicitly kill it here. Best-effort. The double-Ctrl-C / SIGTERM paths
  ;; bypass `stop!` and get the same teardown from the JVM shutdown hook below.
  (try (agent/task-shutdown) (catch Throwable _))
  ;; Session-end memory consolidation fires inside close-session! below (via the
  ;; :agent.instance/closed flush hook). In graph mode it is handed to a DETACHED
  ;; `by memory reduce` child — /quit no longer blocks; we report the spawned
  ;; PID(s) afterward. The heuristic path still runs inline (LLM-free, ms). We
  ;; snapshot whether any work is pending BEFORE close clears the per-session turn
  ;; tallies, then report the outcome once the flush has actually run.
  (let [pending? (try (agent/pending-consolidation?) (catch Throwable _ false))]
    ;; Close all sessions (closes their agents and detaches watches)
    (doseq [idx (sessions/session-indices)]
      (sessions/close-session! idx))
    (when pending?
      (let [detached (try (agent/drain-detached-consolidations!) (catch Throwable _ nil))]
        (tui-session/emit!
         (str "\n"
              (ansi/muted
               (if (seq detached)
                 (str "🧠 Spawned background process (PID "
                      (str/join ", " (map (comp str :pid) detached))
                      ") to fold this session into long-term memory — it continues after exit.")
                 "🧠 Folded this session into long-term memory.")))))))
  ;; Also close any agent in !tui-state (backward compat)
  (when-let [^java.io.Closeable ag (tui-session/get-active-agent)]
    (try
      (.close ag)
      (catch Exception _)))
  (tui-session/stop-tui-publisher!)
  (tui-session/stop-memory-activity-publisher!)
  ;; Stop file publisher
  (try (tui-log/stop-file-publisher!) (catch Exception _))
  ;; Stop in-process nREPL server if we started one
  (stop-nrepl-server!)
  ;; Close every per-session ask socket
  (try (stop-all-ask-listeners!) (catch Exception _))
  ;; Release every per-session ownership lock
  (try (release-all-session-locks!) (catch Exception _))
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
    ;; Buffer OAuth device prompts fired by boot-time MCP connects until the
    ;; alt-screen live loop is up (flushed below), so they aren't lost to the
    ;; primary buffer. Must arm before start! runs init-mcp-from-config!.
    (oauth-render/arm-deferral!)
    (apply start! (concat opts [:skip-banner true]))
    ;; Initialize fullscreen layout
    (let [fullscreen-ok? (and (not force-inline?) (layout/init-fullscreen!))]
      ;; Emit the banner once — either into the fullscreen alt-screen
      ;; (with resume-tail replay) or inline. `start!` was told to skip
      ;; its own banner; `run!` owns it to avoid duplicates (teardown
      ;; replays alt-screen scrollback to the primary buffer).
      (let [{:keys [defagent-id agent-id resumed?]} @tui-session/!tui-state
            ag (tui-session/get-active-agent)
            sess-id (when ag (try (agent/session-id ag) (catch Throwable _ nil)))
            sess    (when ag (try @(:!session ag) (catch Throwable _ nil)))
            lm (try (clj-llm/get-default-lm) (catch Exception _ nil))]
        ;; Replay the persisted scrollback tail before the banner so prior
        ;; conversation appears above it. Mode-independent: fires once for
        ;; ANY resume launch (fullscreen, primary-buffer fallback, etc.) —
        ;; `layout/write-output!` targets the active surface in both cases.
        ;; The inline-direct `start!` path (step 12) covers REPL/inline-only
        ;; callers that never reach `run!`.
        (when resumed?
          (when-let [tail (:resume-tail @tui-session/!tui-state)]
            (when (not= "" tail)
              (try (layout/write-output! tail)
                   (catch Throwable _ nil))))
          (swap! tui-session/!tui-state dissoc :resume-tail))
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
               :lm-provider (:provider lm)
               :lm-model    (:model lm)
               :agents      agents}))))
        ;; One-line MCP connecting/lazy summary, under the banner. The async
        ;; ✓/✗ per-server emits land in the live loop below as connects settle.
        (when-let [s (format-mcp-summary (:mcp-summary @tui-session/!tui-state))]
          (layout/write-output! (str s "\n")))
        ;; CR-MEM-21: embed-model-change notice (semantic recall paused until
        ;; `memory$reembed`) — under the banner, both inline and fullscreen.
        (when-let [notice (agent/graph-vec-stale-notice ag)]
          (layout/write-output! (str notice "\n")))
        ;; Deferred startup notice (e.g. no provider key) — after the banner so
        ;; the alt-screen init doesn't wipe it before the user can read it.
        (write-startup-notice!)
        (when fullscreen-ok?
          (layout/draw-separator!)
          (layout/draw-bottom-separator!)
          (tui-session/update-status-bar! :idle))
        ;; Live loop is up: replay any OAuth device prompt buffered during boot,
        ;; and let later prompts emit straight through.
        (oauth-render/flush-deferred!)))
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
                                              ;; Unlink any per-session ask sockets so a
                                              ;; crashed/killed TUI doesn't leave stale
                                              ;; ask.sock files behind. Idempotent with stop!.
                                              (try (stop-all-ask-listeners!) (catch Throwable _))
                                              ;; Release per-session ownership locks (unlinks
                                              ;; by-host.lock) so a crash doesn't leave a session
                                              ;; looking owned. Idempotent with stop!.
                                              (try (release-all-session-locks!) (catch Throwable _))
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
                            (input/hide-pause-tips!)
                            (try (tui-session/update-status-bar!) (catch Throwable _))
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
