;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-tmux.core.host
  "`by-host`-side adapter built on top of the control-socket server.

   **Retired substrate (May 2026).** The `by-host`↔`by-ui` daemon split
   was retired in favor of a single-process renderer. This adapter is
   kept as test-only/internal — no shipping consumer. See
   `docs/tui/architecture.md` §9 and `docs/specs/tui.md` CR-TUI-20.

   Per docs/tmux-based-agent-tui.md §7.1 — `by-host` owns the agent runtime,
   the input queue, the MCP integration, and the persistence layer.  This
   namespace is the *transport adapter* that bridges those concerns to a
   running `by-ui` over the control protocol.

   What it provides:

   - `start!` boots a control-socket server and remembers the most recent
     attached client (the active `by-ui`).
   - `emit-status!` / `emit-popup!` push outbound frames to the active
     client.  When no client is attached the message is queued (or, for
     popups, persisted via `agent-tui-persist`) so a later reattach can
     resume the conversation.
   - `await-popup-reply!` blocks until the active client returns a
     `:popup-result` for a popup posted via `emit-popup!`.
   - Inbound `:input` / `:slash` / `:cancel` / `:pause` / `:resume` frames are forwarded to a
     user-supplied `:on-input` callback so the runtime can dispatch them
     into the agent's `ask` queue.

   No tmux dependency: this module is pure transport.  The popup payload it
   emits is already tmux-renderable (a `questionnaire`), but the host need
   not know that."
  (:require [ai.brainyard.agent-tui-tmux.core.control.protocol :as proto]
            [ai.brainyard.agent-tui-tmux.core.control.server :as server]
            [ai.brainyard.agent-tui-tmux.core.questionnaire :as q])
  (:import [java.util.concurrent CompletableFuture ConcurrentHashMap TimeUnit]))

(defrecord ^:private Host [path stop-fn !active-conn !pending-popups
                           !pending-out  ; outbound queue for detached state
                           !conn-session-ids ; {conn-id → session-id} from :hello
                           !popup-quiet? ; daemon-side flag toggled by /popup quiet
                           on-input on-attach on-detach
                           on-popup-emit   ; (fn [q]) — fired on emit-popup!
                           on-popup-result ; (fn [reply]) — fired after CF.complete
                           ])

(defn- enqueue-out!
  [{:keys [!pending-out]} msg]
  (swap! !pending-out conj msg))

(defn- drain-out!
  "Send any queued outbound frames to `conn`.  Returns the count drained."
  [{:keys [!pending-out]} conn]
  (let [drained (volatile! 0)]
    (loop []
      (let [next-msg (-> (swap-vals! !pending-out
                                     (fn [v] (if (seq v) (subvec v 1) v)))
                         first first)]
        (when next-msg
          (server/send! conn next-msg)
          (vswap! drained inc)
          (recur))))
    @drained))

(defn- conn-session-id
  "Look up the session-id this conn advertised in its :hello, or nil."
  [{:keys [!conn-session-ids]} conn]
  (when (and !conn-session-ids conn)
    (.get ^ConcurrentHashMap @!conn-session-ids (:id conn))))

(defn- tag-conn-session
  "Attach `:conn-session-id` to `msg` (looked up by conn) so the
   downstream on-input handler can route output to that session's
   stream FIFO instead of the global active. nil-safe — when the
   conn never advertised a session-id (orchestrator, legacy
   clients), the key is absent and routing falls back to global."
  [host conn msg]
  (if-let [sid (conn-session-id host conn)]
    (assoc msg :conn-session-id sid)
    msg))

(defn- handle-inbound
  [{:keys [!active-conn !pending-popups !conn-session-ids
           on-input on-attach on-popup-result] :as host}
   conn msg]
  (case (:type msg)
    :hello
    (do
      ;; Track the new connection as the active one for emit! routing,
      ;; but keep prior attached connections OPEN.  Multiple clients
      ;; legitimately attach to a single daemon socket — the orchestrator
      ;; (which owns layout/popups/`:client-slash` handling) AND the
      ;; REPL pane subprocess (which owns input keystrokes).  Closing
      ;; the prior connection on every `:hello` killed the orchestrator
      ;; once the REPL pane attached, leaving `:client-slash` frames
      ;; with no consumer (silent `/activity show`).  See docs/§13.4.
      (reset! !active-conn conn)
      ;; Stash the session-id this conn advertised (per-window REPL
      ;; pane) so subsequent input/slash messages can be tagged
      ;; with it for per-conn output routing.
      (when-let [sid (:session-id msg)]
        (when !conn-session-ids
          (.put ^ConcurrentHashMap @!conn-session-ids (:id conn) sid)))
      (server/send! conn (proto/hello-ack {:session-id (:session-id host)}))
      (drain-out! host conn)
      (when on-attach (on-attach conn)))

    :ping
    (server/send! conn (proto/pong))

    :detach
    nil

    :input
    (when on-input (on-input :input (tag-conn-session host conn msg)))

    :slash
    (when on-input (on-input :slash (tag-conn-session host conn msg)))

    :cancel
    (when on-input (on-input :cancel (tag-conn-session host conn msg)))

    :pause
    (when on-input (on-input :pause (tag-conn-session host conn msg)))

    :resume
    (when on-input (on-input :resume (tag-conn-session host conn msg)))

    :pause-toggle
    (when on-input (on-input :pause-toggle (tag-conn-session host conn msg)))

    :resize
    ;; Orchestrator → daemon: terminal/pane geometry update.  Forward
    ;; to on-input so the daemon can update its status snapshot's
    ;; `:cols` / `:rows` and the next status repaint right-aligns
    ;; against the actual pane width instead of the 80-col fallback.
    (when on-input (on-input :resize msg))

    ;; Orchestrator → daemon: notification that the activity pane just
    ;; got opened or closed.  Forward to on-input so the daemon can
    ;; update its `:activity-pane-open?` snapshot.
    :activity-state
    (when on-input (on-input :activity-state msg))

    :popup-result
    (do
      (let [^CompletableFuture cf (.remove ^ConcurrentHashMap @!pending-popups (:id msg))]
        (when cf (.complete cf msg)))
      ;; Notify the daemon so it can clear the persisted pending-dialog
      ;; entry — without this, the disk file keeps growing across runs
      ;; and a fresh `bb tui run` replays every previously-answered
      ;; popup on attach.  Idempotent: the daemon's callback removes by
      ;; :id, so a second fire after the orchestrator's own cleanup is
      ;; harmless.
      (when on-popup-result
        (try (on-popup-result msg) (catch Throwable _))))

    :list-sessions
    (server/send! conn (proto/sessions-reply []))

    nil))

(defn active-conn
  [{:keys [!active-conn]}]
  @!active-conn)

(defn attached?
  [host]
  (some? (active-conn host)))

(defn emit!
  "Send `msg` to the active client.  When no client is attached, queue it for
   the next reattach.  Returns true if delivered immediately."
  [host msg]
  (if-let [conn (active-conn host)]
    (do (server/send! conn msg) true)
    (do (enqueue-out! host msg) false)))

(defn broadcast!
  "Send `msg` to ALL currently-attached clients.  When the orchestrator
   and the REPL pane subprocess both connect to the same daemon socket,
   only the orchestrator handles certain frame types (`:client-slash`).
   Routing those through `emit!` (active-conn only) drops them whenever
   the REPL pane is the most recent `:hello` sender.

   Returns the number of connections the frame was delivered to.  When
   no clients are attached, falls back to `enqueue-out!` so the next
   reattach drains the message."
  [{:keys [all-conns] :as host} msg]
  (let [conns (when all-conns (all-conns))
        delivered (count (filter (fn [conn]
                                   (try
                                     (server/send! conn msg)
                                     true
                                     (catch Throwable _ false)))
                                 conns))]
    (when (System/getenv "BY_HOST_DEBUG")
      (binding [*out* *err*]
        (println (str "[host] broadcast " (:type msg)
                      " conns=" (count (or conns []))
                      " delivered=" delivered))))
    (when (zero? delivered)
      (enqueue-out! host msg))
    delivered))

(defn emit-status!
  [host left right]
  (emit! host (proto/status-update left right)))

(defn popup-quiet?
  "Whether the daemon-side popup-quiet flag is on.  When true,
   `emit-popup!` stamps outbound `:popup` frames with
   `:render-mode :inline`; otherwise `:tmux`."
  [{:keys [!popup-quiet?]}]
  (boolean (some-> !popup-quiet? deref)))

(defn set-popup-quiet!
  "Update the daemon-side popup-quiet flag.  Invoked when the
   `/popup quiet on|off` slash arrives so subsequent popups carry the
   matching `:render-mode` and every client renders consistently."
  [{:keys [!popup-quiet?]} v]
  (when !popup-quiet? (reset! !popup-quiet? (boolean v))))

(defn emit-popup!
  "Broadcast a popup to all attached clients (orchestrator + REPL pane)
   and register a CompletableFuture that resolves on the first matching
   `:popup-result`.  The frame is stamped with `:render-mode` so each
   client knows whether to draw the tmux overlay or the inline banner.

   `:on-popup-emit` fires on the FIRST emit (not on on-attach replay,
   which uses `control-server-send!` directly).  The daemon plugs
   `persist/add-pending-dialog!` here so each questionnaire is added
   to disk exactly once — previously the orchestrator did it on every
   `:popup` it received, including replays, causing the on-disk queue
   to double on every reattach."
  [{:keys [!pending-popups on-popup-emit] :as host} questionnaire]
  (let [q (q/validate questionnaire)
        cf (CompletableFuture.)
        mode (if (popup-quiet? host) :inline :tmux)]
    (.put ^ConcurrentHashMap @!pending-popups (:id q) cf)
    (when on-popup-emit
      (try (on-popup-emit q) (catch Throwable _)))
    (broadcast! host (proto/popup q mode))
    cf))

(defn emit-picker!
  "Push an `:open-picker` frame to the active client and register a
   CompletableFuture that resolves on the matching `:popup-result`.
   Re-uses the questionnaire reply path: `:popup-result :id` is the
   key and `:selection` carries the chosen item-id (or nil on cancel).

   `items` is a vec of maps with at minimum `:id` and `:label`. `id`
   defaults to a fresh UUID when omitted; tests can pass in a fixed id."
  ([host title items] (emit-picker! host title items nil))
  ([{:keys [!pending-popups] :as host} title items id]
   (let [picker-id (or id (str (java.util.UUID/randomUUID)))
         cf        (CompletableFuture.)]
     (.put ^ConcurrentHashMap @!pending-popups picker-id cf)
     (emit! host (proto/open-picker picker-id title items))
     cf)))

(defn await-picker-reply!
  "Blocking wait for a picker reply. Returns the chosen item id (a
   string) on submit, nil on cancel / timeout. `cf` is the future
   returned by `emit-picker!`."
  ([cf] (await-picker-reply! cf nil))
  ([^CompletableFuture cf {:keys [timeout-ms]}]
   (try
     (let [reply (if timeout-ms
                   (.get cf (long timeout-ms) TimeUnit/MILLISECONDS)
                   (.get cf))]
       (when (= :submitted (:status reply))
         (:selection reply)))
     (catch java.util.concurrent.TimeoutException _ nil))))

(defn await-popup-reply!
  "Block until the popup whose id is `:id q` returns a reply.  `:timeout-ms`
   defaults to `nil` (wait forever)."
  ([host q] (await-popup-reply! host q nil))
  ([host q {:keys [timeout-ms]}]
   (let [^CompletableFuture cf (or (.get ^ConcurrentHashMap @(:!pending-popups host) (:id q))
                                   (emit-popup! host q))]
     (try
       (if timeout-ms
         (.get cf (long timeout-ms) TimeUnit/MILLISECONDS)
         (.get cf))
       (catch java.util.concurrent.TimeoutException _
         (q/timeout-reply q))))))

(defn start!
  "Start a `by-host` control adapter.  Options:
     :path        — socket path (required).  Created if absent.
     :session-id  — agent-session-id reported in :hello-ack (required).
     :on-input    — fn `[kind msg]` for :input/:slash/:cancel.
     :on-attach   — fn `[connection]` (optional).
     :on-detach   — fn `[connection]` (optional).

   Returns a host handle:
     {:host <Host> :stop! (fn [])}"
  [{:keys [path session-id on-input on-attach on-detach
           on-popup-emit on-popup-result]
    initial-quiet? :popup-quiet?
    :or {initial-quiet? false}}]
  (let [!active   (atom nil)
        !pending  (atom (ConcurrentHashMap.))
        !pending-out (atom [])
        ;; Per-window REPL panes advertise their session-id on :hello;
        ;; we stash it here so subsequent :input/:slash/:cancel from
        ;; that conn carry :conn-session-id for per-conn output routing.
        !conn-session-ids (atom (ConcurrentHashMap.))
        ;; `/popup quiet on|off` flips this; emit-popup! reads it to
        ;; decide whether to stamp `:render-mode :inline` or `:tmux`.
        !popup-quiet? (atom (boolean initial-quiet?))
        host (assoc (->Host path nil !active !pending !pending-out
                            !conn-session-ids !popup-quiet?
                            on-input on-attach on-detach
                            on-popup-emit on-popup-result)
                    :session-id session-id)
        on-msg (fn [conn msg] (handle-inbound host conn msg))
        srv  (server/start!
              {:path path
               :on-message on-msg
               :on-disconnect (fn [conn]
                                (compare-and-set! !active conn nil)
                                ;; Drop this conn's session-id tag.
                                (when (and !conn-session-ids conn)
                                  (.remove ^ConcurrentHashMap @!conn-session-ids
                                           (:id conn)))
                                (when on-detach (on-detach conn)))})
        host (assoc host
                    :path (:path srv)
                    :stop-fn (:stop! srv)
                    ;; Carry the server's :connections accessor so
                    ;; broadcast! can fan out to every attached client
                    ;; (not just active-conn).  Required for
                    ;; orchestrator-bound `:client-slash` frames when
                    ;; both the orchestrator and the REPL pane
                    ;; subprocess attach to the same socket.
                    :all-conns (:connections srv))]
    {:host  host
     :path  (:path srv)
     :stop! (:stop! srv)
     :emit! (fn [msg] (emit! host msg))
     :emit-status! (fn [l r] (emit-status! host l r))
     :emit-popup! (fn [q] (emit-popup! host q))
     :broadcast! (fn [msg] (broadcast! host msg))
     :await-popup-reply! (fn
                           ([q] (await-popup-reply! host q))
                           ([q opts] (await-popup-reply! host q opts)))
     :set-popup-quiet! (fn [v] (set-popup-quiet! host v))
     :popup-quiet? (fn [] (popup-quiet? host))
     :attached? (fn [] (attached? host))
     :active-conn (fn [] (active-conn host))}))
