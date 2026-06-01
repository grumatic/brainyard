;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-tmux.core.control.protocol
  "Line-delimited EDN frame protocol for the control socket.

   **Retired substrate (May 2026).** The `by-host`↔`by-ui` daemon split
   was retired in favor of a single-process renderer (`bases/agent-tui`).
   This namespace is kept as test-only/internal — no shipping consumer.
   See `docs/tui/architecture.md` §9 and `docs/specs/tui.md` CR-TUI-20.

   Per docs/tmux-based-agent-tui.md §7.1 — `by-ui` connects to
   `~/.brainyard/sessions/<id>/control.sock` and exchanges EDN-encoded maps,
   one per line.

   Inbound messages (`by-ui` → `by-host`):
     :hello             — handshake ({:ui-id ... :version ...})
     :input             — line of user input ({:line ...})
     :slash             — slash command ({:command ... :args ...})
     :popup-result      — questionnaire reply ({:id ... :status ... :answers ...})
     :cancel            — Ctrl-C
     :pause             — request cooperative pause of the running agent
     :resume            — release a paused agent
     :pause-toggle      — toggle pause/resume based on the daemon's view
     :detach            — clean detach (informational)
     :ping              — heartbeat
     :resize            — terminal geometry ({:cols ... :rows ...})
     :list-sessions     — request session list
     :attach            — switch this UI to a different session ({:session-id ...})

   Outbound messages (`by-host` → `by-ui`):
     :hello-ack         — handshake reply ({:session-id ... :version ...})
     :status            — status bar update ({:left ... :right ...})
     :popup             — show questionnaire ({:questionnaire ...})
     :pong              — heartbeat reply
     :sessions          — reply to :list-sessions ({:list [...]})
     :error             — protocol error ({:message ...})
     :bye               — server is shutting down

   Frame format: one EDN map per line, UTF-8.  Newline characters inside
   string values are forbidden — callers must pre-escape (this is automatic
   when the value goes through `prn`)."
  (:require [clojure.edn :as edn])
  (:import [java.io BufferedReader BufferedWriter PrintWriter Reader Writer]
           [java.nio.charset StandardCharsets]))

(def protocol-version 1)

(def inbound-types
  #{:hello :input :slash :popup-result :cancel :pause :resume :pause-toggle :detach :ping :resize
    :list-sessions :attach :new-agent :close-agent :list-agents
    ;; orchestrator → daemon: notifies whether the activity pane is
    ;; currently visible.  Used by hook handlers to decide whether to
    ;; emit a full block to stream or a `(see /activity)` collapse line.
    :activity-state})

(def outbound-types
  #{:hello-ack :status :popup :pong :sessions :error :bye
    :new-agent-result :agents :agent-output :client-slash
    :rename-window :close-window :agent-state
    ;; daemon → ui: open a picker (resume picker, command palette, etc.)
    ;; The reply re-uses the existing :popup-result frame; the orchestrator
    ;; populates :selection (an id from `:items`) instead of :answers.
    :open-picker})

(defn write-frame!
  "Write `msg` as one line of EDN to `writer`.  `msg` must be a map containing
   a `:type` keyword.  Returns msg."
  [^Writer writer msg]
  (when-not (and (map? msg) (keyword? (:type msg)))
    (throw (ex-info "control frames must be maps with :type" {:msg msg})))
  (let [^PrintWriter pw (if (instance? PrintWriter writer)
                          writer
                          (PrintWriter. writer true))]
    (binding [*out* pw] (prn msg))
    (.flush pw)
    msg))

(defn read-frame
  "Read a single EDN frame from `reader`.  Returns the parsed map, or
   :eof at end of stream, or :malformed on a non-map / parse error.

   Blank lines are skipped."
  [^BufferedReader reader]
  (loop []
    (let [line (.readLine reader)]
      (cond
        (nil? line) :eof
        (clojure.string/blank? line) (recur)
        :else
        (try
          (let [v (edn/read-string {:readers *data-readers*} line)]
            (if (and (map? v) (keyword? (:type v)))
              v
              :malformed))
          (catch Throwable _ :malformed))))))

(defn frame-seq
  "Return a lazy sequence of frames from `reader` until end-of-stream."
  [^BufferedReader reader]
  (lazy-seq
   (let [f (read-frame reader)]
     (when-not (= :eof f)
       (cons f (frame-seq reader))))))

;; -- constructors -------------------------------------------------------------

(defn hello
  "Build a `:hello` handshake frame.

   Optional `:session-id` lets a per-window REPL pane subprocess tell
   the daemon which session-id its input/output is bound to. The daemon
   uses this to route slash output back to the same window's stream
   FIFO instead of the global active session. Untagged hellos (e.g.
   from the orchestrator) keep the legacy global routing."
  ([] (hello {}))
  ([{:keys [ui-id version session-id]
     :or   {version protocol-version}}]
   (cond-> {:type :hello
            :ui-id (or ui-id (str (java.util.UUID/randomUUID)))
            :version version}
     session-id (assoc :session-id session-id))))

(defn hello-ack [{:keys [session-id]}]
  {:type :hello-ack :session-id session-id :version protocol-version})

(defn input    [line]            {:type :input :line line})
(defn slash    [command args]    {:type :slash :command command :args (or args "")})
(defn cancel   []                {:type :cancel})
(defn pause-msg  []              {:type :pause})
(defn resume-msg []              {:type :resume})
(defn pause-toggle-msg []        {:type :pause-toggle})
(defn detach   []                {:type :detach})
(defn ping     []                {:type :ping})
(defn pong     []                {:type :pong})
(defn resize   [cols rows]       {:type :resize :cols cols :rows rows})
(defn status-update [left right] {:type :status :left left :right right})
(defn popup
  "Build a `:popup` outbound frame.  `render-mode` (one of `:tmux` or
   `:inline`) tells receiving clients which surface to use; the daemon
   stamps it from its own `popup-quiet?` state so all clients agree.
   Receivers that see no `:render-mode` (legacy daemon) fall back to
   their previous local decision."
  ([questionnaire] {:type :popup :questionnaire questionnaire})
  ([questionnaire render-mode]
   {:type :popup :questionnaire questionnaire :render-mode render-mode}))
(defn popup-result
  "Reply to a :popup or :open-picker frame. `:answers` is for
   questionnaires; `:selection` is for pickers — only one is populated
   per call."
  ([id status answers]
   {:type :popup-result :id id :status status :answers (or answers {})})
  ([id status answers selection]
   (cond-> {:type :popup-result :id id :status status :answers (or answers {})}
     selection (assoc :selection selection))))

(defn open-picker
  "daemon → ui frame asking by-ui to render a picker. `items` is a vec
   of maps with at minimum `:id` and `:label`; the orchestrator passes
   them to `popup-picker/open-picker`. The reply uses :popup-result
   keyed by the same `:id`."
  [id title items]
  {:type :open-picker :id id :title (str (or title "")) :items (vec items)})
(defn list-sessions-msg [] {:type :list-sessions})
(defn sessions-reply  [list]
  {:type :sessions :list (vec list)})
(defn attach-msg [session-id] {:type :attach :session-id session-id})
(defn error-msg  [message]    {:type :error :message message})
(defn bye        []           {:type :bye})

(defn new-agent
  "Request a new agent in the daemon.  Required: :agent (e.g. \"react-agent\").
   Optional: :name (a label for the new tmux window)."
  [{:keys [agent name max-iterations]}]
  (cond-> {:type :new-agent :agent agent}
    name           (assoc :name name)
    max-iterations (assoc :max-iterations max-iterations)))

(defn new-agent-result
  "Daemon's reply after attempting to create a new agent."
  [{:keys [status session-id name fifos reason]}]
  (cond-> {:type :new-agent-result :status status}
    session-id (assoc :session-id session-id)
    name       (assoc :name name)
    fifos      (assoc :fifos fifos)
    reason     (assoc :reason reason)))

(defn close-agent
  [session-id]
  {:type :close-agent :session-id session-id})

(defn list-agents-msg [] {:type :list-agents})

(defn activity-state
  "Notify the daemon that the activity pane has been shown or hidden.
   The daemon stores the boolean in its `!status-snapshot` so hook
   handlers can decide whether to render the full block in-stream or
   replace it with a one-line `(see /activity)` collapse marker."
  [open?]
  {:type :activity-state :open? (boolean open?)})

(defn agents-reply
  "Daemon's reply listing all currently active agents."
  [agents]
  {:type :agents :agents (vec agents)})

(defn client-slash
  "Tell the active client to interpret this slash command locally
   (e.g. /activity, /popup, /layout — orchestrator-bound layout commands
   that came in via the input-pane reader's socket connection)."
  [command args]
  {:type :client-slash :command command :args (or args "")})

(defn rename-window
  "Tell the orchestrator to rename the tmux window for `session-id`."
  [session-id new-name]
  {:type :rename-window :session-id session-id :name new-name})

(defn close-window
  "Tell the orchestrator to kill the tmux window for `session-id` (issued
   after the daemon-side `/agent close` so the visual state matches)."
  [session-id]
  {:type :close-window :session-id session-id})

(defn agent-state
  "Broadcast the current agent state so the REPL pane can swap its prompt
   glyph between `❯` (idle) and a braille spinner (thinking/running).
   `state` is one of :idle :thinking :running :paused :cancelled :stopped."
  [state]
  {:type :agent-state :state state})
