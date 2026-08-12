;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.log
  "Mulog file publishers.

   - `start-file-publisher!` writes ALL events to a single global file
     (default `~/.brainyard/logs/agent-tui-app.log`, falling back to
     `/tmp/agent-tui-app.log` when the user `.brainyard/logs/` dir can't
     be created). This mirrors the legacy TUI.

   - `start-session-publisher!` writes only events whose `:session-id`
     matches a given session to a per-session file (typically
     `<session-dir>/app.log`). The tmux-based TUI's `/log toggle`
     pane tails this file so users see only their own session's
     events instead of every session's events interleaved."
  (:require [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.mulog.interface :as mulog]))

(defonce ^:private !publisher-handle (atom nil))

;; {session-id -> publisher-handle} — supports multiple concurrent daemons
;; sharing one JVM (e.g. tests).
(defonce ^:private !session-publishers (atom {}))

(defn default-log-path
  "Resolve the default log path under `~/.brainyard/logs/`, creating the
   dir on demand. Falls back to `/tmp/agent-tui-app.log` only when the
   user-scope dir can't be created (rare; e.g. no `user.home`)."
  []
  (if-let [d (agent/brainyard-subdir! (agent/init-dirs!) "logs" :user)]
    (str d "/agent-tui-app.log")
    "/tmp/agent-tui-app.log"))

(def ^:private ^:const default-max-log-bytes
  "Rotate the global app log once it reaches this size (50 MiB). Bounds the
   file that previously grew unbounded (observed at ~390 MB)."
  (* 50 1024 1024))

(def ^:private ^:const default-max-log-rotations
  "Rotated backups to keep: agent-tui-app.log.1 … .N (older dropped)."
  3)

;; The publisher OBJECT, kept alongside its stop handle. `flush-file-publisher!`
;; needs it to await the agent-buffer; the handle alone is just a stop function.
(defonce ^:private !publisher-obj (atom nil))

(defn start-file-publisher!
  "Start global file publisher (rotating at ~50 MiB, keeping 3 backups).
   Idempotent — no-op if already running."
  ([] (start-file-publisher! (default-log-path)))
  ([log-path]
   (when-not @!publisher-handle
     (let [p (mulog/make-rotating-pretty-file-publisher
              log-path
              :max-bytes default-max-log-bytes
              :max-rotations default-max-log-rotations)]
       (reset! !publisher-obj p)
       (reset! !publisher-handle
               (mulog/start-publisher! {:type :inline :publisher p}))))))

(defn stop-file-publisher!
  "Stop global file publisher WITHOUT draining it. Idempotent.

   Prefer `flush-file-publisher!` when the process is about to exit: the
   handle mulog returns cancels the recurring publish task, it does not
   drain the buffer, so anything emitted inside the last batch window is
   discarded. Verified: an event emitted immediately before this call never
   reaches the file, while the same event followed by a wait does."
  []
  (when-let [handle @!publisher-handle]
    (mulog/stop-publisher! handle)
    (reset! !publisher-handle nil)
    (reset! !publisher-obj nil)))

(defn flush-file-publisher!
  "Drain the global file publisher, then stop it. Idempotent, and a no-op
   when nothing is running — so a second call (explicit teardown, then the
   process-exit hook) costs nothing.

   Call this instead of `stop-file-publisher!` whenever the process is
   about to end. An event travels two ASYNCHRONOUS hops to reach the file,
   and a plain stop breaks both:

     1. mulog holds events in a GLOBAL buffer and a recurring task moves
        them into this publisher's agent-buffer every `dispatch-interval-ms`.
        Stopping DEREGISTERS the publisher first, so anything still in the
        global buffer is never dispatched to it — no later await can
        recover it. Hence the wait comes BEFORE the stop.
     2. The stop then dispatches its final write with `send-off`, which
        returns immediately. Exiting here kills the JVM mid-action. Hence
        the await comes AFTER the stop.

   Getting either order wrong silently drops the tail, which is exactly how
   this read as working for so long: the events of any command that ran
   longer than a dispatch cycle showed up, and only the last ones vanished."
  []
  (when @!publisher-handle
    (let [p        @!publisher-obj
          ;; One dispatch interval is the longest the handover can take;
          ;; the margin covers a dispatcher tick that has just started.
          deadline (+ (System/currentTimeMillis)
                      (long mulog/dispatch-interval-ms) 100)]
      ;; (1) Let the dispatcher hand the global buffer over while we are
      ;;     still registered to receive it. POLLED, not slept: a command
      ;;     that logged nothing has an empty buffer and pays nothing, and
      ;;     one that logged is released the moment its events move rather
      ;;     than at a fixed interval. Only a stuck dispatcher waits out
      ;;     the deadline.
      (try
        (while (and (mulog/pending-events?)
                    (< (System/currentTimeMillis) deadline))
          (Thread/sleep (long 5)))
        (catch InterruptedException _
          ;; Shutting down under a signal — keep whatever already landed
          ;; rather than delaying exit further.
          (.interrupt (Thread/currentThread)))
        (catch Exception _))
      ;; (2) Stop: deregister, cancel the timer, dispatch a final publish.
      (stop-file-publisher!)
      ;; (3) Wait for that final publish to actually run. The dispatcher's
      ;;     enqueue was sent to this same agent first, and agent actions
      ;;     run in dispatch order, so awaiting ours awaits both. Returns
      ;;     immediately when the agent is already idle.
      (try (mulog/await-publisher! p 3000) (catch Exception _)))))

(defn start-session-publisher!
  "Start a per-session file publisher that appends only events matching
   `:session-id == session-id` to `log-path`.  Idempotent per session-id
   — calling twice for the same id is a no-op (the existing handle is
   kept)."
  [session-id log-path]
  (when-not (get @!session-publishers session-id)
    (let [handle (mulog/start-publisher!
                  {:type :inline
                   :publisher
                   (mulog/make-fn-publisher
                    (fn [event]
                      (when (= session-id (:session-id event))
                        (try
                          (with-open [^java.io.FileWriter w (java.io.FileWriter. ^String log-path true)]
                            (.write w ^String (mulog/pretty-event-str event))
                            (.flush w))
                          (catch Exception _)))))})]
      (swap! !session-publishers assoc session-id handle))))

(defn stop-session-publisher!
  "Stop the per-session publisher for `session-id`. Idempotent."
  [session-id]
  (when-let [handle (get @!session-publishers session-id)]
    (mulog/stop-publisher! handle)
    (swap! !session-publishers dissoc session-id)))
