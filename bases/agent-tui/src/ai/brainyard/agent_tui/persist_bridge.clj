;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.persist-bridge
  "Single shared bridge from agent hooks → agent-tui-persist.

   Subscribes once to the agent component's hook registry on TUI `start!` and
   routes every event to the right `<project>/.brainyard/sessions/<agent-session-id>/`
   directory. Tabs come and go without bridge lifecycle changes; new
   agents/tabs are picked up automatically because every hook event carries an
   `:agent`, and the bridge routes by `(agent/session-id agent)`.

   Writes:
   - `messages.log` (append-only event log) — diagnostic events plus one
     `{:kind :message :payload msg}` entry per user/assistant message. The
     log is the source of truth for `:messages` on restore (see
     `agent-tui-persist.core.restore`).
   - `session.edn` (atomic snapshot) — session map minus `:messages` and
     `:config`, refreshed on every `:agent.ask/post` so `:data`,
     `:total-turns`, `:agent-activity`, and timestamps survive a restart.
     `:config` is intentionally stripped — it holds live runtime objects
     (usage-tracker atoms, permission/feedback callback fns, dirs handles)
     that don't EDN-serialise; the TUI rebuilds them from scratch on every
     session creation.
   - `usage-tracker.edn` (atomic snapshot) — pure-data dump of the
     usage-tracker atom (totals, by-model, history), refreshed on every
     `:agent.ask/post` so `/usage` + the status bar's running cost survive
     a `--resume`. Rehydrated into the freshly-minted tracker atom by the
     TUI's resume path.
   - `meta.edn` — agent-id, user-id, started-at, working-dir; written on
     `:agent.session/created` and refreshed on `:agent.instance/created`.

   The per-session high-water mark of messages already written
   (`!msg-counts`) keeps `:message` events from duplicating across asks.

   All write paths swallow exceptions — a broken persistence layer must not
   crash the TUI."
  (:require [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent-tui-persist.interface :as persist]
            [ai.brainyard.clj-llm.interface :as clj-llm]))

(defonce ^:private !msg-counts (atom {}))
(defonce ^:private !installed? (atom false))

(defn- safe-session-id [ag]
  (try (agent/session-id ag) (catch Throwable _ nil)))

(defn- safe-deref-session [ag]
  (try @(:!session ag) (catch Throwable _ nil)))

(defn- swallow* [f]
  (try (f) (catch Throwable _ nil)))

(defmacro ^:private swallow [& body]
  `(swallow* (fn [] ~@body)))

(defn- truncate [^String s ^long n]
  (if (> (count s) n) (str (subs s 0 (- n 1)) "…") s))

;; --- Handlers ----------------------------------------------------------------

(defn- on-session-created [{:keys [session-id user-id]}]
  (when session-id
    (swap! !msg-counts assoc session-id 0)
    (swallow
     (persist/save-meta! session-id
                         {:user-id          user-id
                          :started-at       (System/currentTimeMillis)
                          :last-attached-at (System/currentTimeMillis)
                          ;; Effective working dir (honors --working-dir/-C and
                          ;; BY_WORKING_DIR), not the raw JVM cwd.
                          :working-dir      (agent/resolve-working-dir)}))))

(defn- on-session-closed [{:keys [session-id session]}]
  (when session-id
    (when session
      (swallow
       (persist/write-snap! session-id :session
                            (-> @session (dissoc :messages)))))
    (swap! !msg-counts dissoc session-id)))

(defn- on-instance-created [{:keys [agent]}]
  (when-let [sid (safe-session-id agent)]
    (let [aid   (:agent-id agent)
          ;; Derive the defagent type from the instance-id's namespace so
          ;; siblings created via `/agent new` (which don't go through the
          ;; CLI's -a flag) still get their type persisted for resume. Without
          ;; this, `--resume <sibling-sid>` would fall back to the CLI default.
          defid (when (keyword? aid)
                  (if (namespace aid) (keyword (namespace aid)) aid))
          ;; Subagents (coact→explore, main→rlm, …) share the session-id but
          ;; must NOT redefine the session's resume identity. Only TOP-LEVEL
          ;; agents (no parent — including `/agent new` siblings) write
          ;; :agent-id/:defagent-id. Otherwise the last subagent created wins
          ;; and `--resume` restores the subagent type instead of the root.
          subagent? (some? (agent/get-parent-agent (:!state agent)))]
      (swallow
       (persist/append-event! sid
                              {:kind :agent.instance/created
                               :payload {:agent-id aid}}))
      (when-not subagent?
        (swallow (persist/save-meta! sid
                                     (let [model (some-> (clj-llm/get-default-lm) :model)]
                                       (cond-> {:agent-id aid}
                                         defid (assoc :defagent-id defid)
                                         model (assoc :model model)))))))))

(defn- on-instance-closed [{:keys [agent]}]
  (when-let [sid (safe-session-id agent)]
    (swallow
     (persist/append-event! sid
                            {:kind :agent.instance/closed
                             :payload {:agent-id (:agent-id agent)}}))))

(defn- on-ask-pre [{:keys [agent input]}]
  (when-let [sid (safe-session-id agent)]
    (swallow
     (persist/append-event! sid
                            {:kind :agent.ask/pre
                             :payload {:agent-id (:agent-id agent)
                                       :input    (truncate (str input) 4000)}}))))

(defn- flush-new-messages! [sid messages]
  (let [msgs (or messages [])
        prior (get @!msg-counts sid 0)
        start (min prior (count msgs))]
    (doseq [m (subvec msgs start)]
      (swallow (persist/append-event! sid {:kind :message :payload m})))
    (swap! !msg-counts assoc sid (count msgs))))

(defn- on-ask-post [{:keys [agent result]}]
  (when-let [sid (safe-session-id agent)]
    (when-let [s (safe-deref-session agent)]
      (flush-new-messages! sid (:messages s))
      (swallow
       (persist/write-snap! sid :session (dissoc s :messages :config)))
      ;; Stamp last-activity so resume ordering (latest-session-id /
      ;; --select-resume) reflects update time, not create time. Without
      ;; this, :last-attached-at is never written and every session falls
      ;; back to :started-at — making the picker/bare-resume order by
      ;; creation. End-of-turn is the natural "session was just used" moment.
      (swallow
       (persist/save-meta! sid {:last-attached-at (System/currentTimeMillis)}))
      ;; Persist cumulative usage so /usage + the status bar survive --resume.
      ;; The tracker is the only piece of :config that's pure data; everything
      ;; else (callback fns, dirs handles) is intentionally stripped above.
      (when-let [tracker (get-in s [:config :usage-tracker])]
        (swallow
         (persist/write-snap! sid :usage-tracker (clj-llm/serialize-tracker tracker))))
      (swallow
       (persist/append-event! sid
                              {:kind :agent.ask/post
                               :payload {:agent-id (:agent-id agent)
                                         :answer   (some-> result :answer (str) (truncate 4000))
                                         :error    (some-> result :error)
                                         :iteration-count (some-> result :iteration-count)}})))))

(defn- tool-payload-base [{:keys [tool-name args call-id depth]}]
  {:tool-name tool-name
   :call-id   (str call-id)
   :depth     depth
   :args-summary (when (some? args)
                   (try (truncate (pr-str args) 400)
                        (catch Throwable _ "<unprintable>")))})

(defn- on-tool-pre [{:keys [agent] :as event}]
  (when-let [sid (safe-session-id agent)]
    (swallow
     (persist/append-event! sid
                            {:kind :agent.tool-use/pre
                             :payload (tool-payload-base event)}))))

(defn- on-tool-post [{:keys [agent] :as event}]
  (when-let [sid (safe-session-id agent)]
    (swallow
     (persist/append-event! sid
                            {:kind :agent.tool-use/post
                             :payload (cond-> (tool-payload-base event)
                                        (:hook-replaced event) (assoc :replaced-by (:replaced-by event))
                                        (:hook-blocked  event) (assoc :blocked-by  (:blocked-by  event)))}))))

;; --- Output sink tee ---------------------------------------------------------

(defn tee-scrollback!
  "Tee a chunk of ANSI bytes into a session's `:stream` scrollback file.
   Safe to call with nil session-id (no-op). Exceptions are swallowed so a
   broken persistence layer can't take the TUI down.

   Ensures the appended chunk ends with a newline. Each `emit!` call is
   one logical emit; the in-memory `!scrollback` atom already splits each
   emit on `\\n` into its own entries, so the file must mirror that line
   discipline. Without the terminator, an emit whose content doesn't end
   in `\\n` would have the next emit's bytes appended directly on disk,
   producing a single concatenated line — and on resume, the replay would
   restore that concatenation back into `!scrollback`, e.g. the right-
   aligned per-ask usage line getting fused with the `Press Ctrl-C…`
   hint."
  [session-id ^String s]
  (when (and session-id (string? s) (pos? (.length s)))
    (let [terminated (if (.endsWith s "\n") s (str s "\n"))]
      (swallow (persist/append-scrollback! session-id :stream terminated))
      ;; Fire the `:display` hook so an ask-socket `:subscribe [:display]` mirrors
      ;; what the session renders, in real time — the socket counterpart of
      ;; tailing scrollback.stream.txt. Cheap when nobody is subscribed; never
      ;; throws into the emit path. See docs/design/session-channel-extensions.md §5b.
      (try (agent/fire! :display {:session-id session-id :text s})
           (catch Throwable _ nil)))))

;; --- Lifecycle ---------------------------------------------------------------

(defn start!
  "Register persist-bridge hook handlers with the agent component.

   Idempotent: a second call is a no-op. Use `stop!` to remove. Hooks are
   tagged `:source :persist` so they can be unregistered in bulk."
  []
  (when (compare-and-set! !installed? false true)
    (agent/register-hook! :agent.session/created   ::session-created   on-session-created   :source :persist)
    (agent/register-hook! :agent.session/closed    ::session-closed    on-session-closed    :source :persist)
    (agent/register-hook! :agent.instance/created  ::instance-created  on-instance-created  :source :persist)
    (agent/register-hook! :agent.instance/closed   ::instance-closed   on-instance-closed   :source :persist)
    (agent/register-hook! :agent.ask/pre           ::ask-pre           on-ask-pre           :source :persist)
    (agent/register-hook! :agent.ask/post          ::ask-post          on-ask-post          :source :persist)
    (agent/register-hook! :agent.tool-use/pre      ::tool-pre          on-tool-pre          :source :persist)
    (agent/register-hook! :agent.tool-use/post     ::tool-post         on-tool-post         :source :persist)
    :installed))

(defn stop!
  "Remove every persist-bridge hook handler and reset state. Idempotent."
  []
  (when (compare-and-set! !installed? true false)
    (swallow (agent/unregister-source! :persist))
    (reset! !msg-counts {})
    :stopped))

(defn installed? [] @!installed?)

(defn prime-session-counts!
  "Set the in-memory high-water mark for `session-id` to `n` (typically the
   number of messages already present on disk). The resume path calls this
   after `restore-session-map` so the next `:agent.ask/post` only appends
   the messages added after restore — not duplicates of the restored history."
  [session-id ^long n]
  (swap! !msg-counts assoc session-id n)
  n)

(defn save-tui-session-meta!
  "Persist TUI-session-specific fields (label, defagent-id) into meta.edn
   so they can be restored on resume. Exceptions are swallowed; no-op when
   session-id or all fields are nil."
  [session-id {:keys [label defagent-id]}]
  (let [m (cond-> {}
            label       (assoc :label label)
            defagent-id (assoc :defagent-id defagent-id))]
    (when (and session-id (seq m))
      (swallow (persist/save-meta! session-id m)))))

(defn ^:no-doc reset-state!
  "Test-only: clear in-memory state without touching the hook registry."
  []
  (reset! !msg-counts {}))
