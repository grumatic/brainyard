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
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.pprint :as pp]))

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

(defn start-file-publisher!
  "Start global file publisher. Idempotent — no-op if already running."
  ([] (start-file-publisher! (default-log-path)))
  ([log-path]
   (when-not @!publisher-handle
     (reset! !publisher-handle
             (mulog/start-publisher!
              {:type :inline
               :publisher (mulog/make-pretty-file-publisher log-path)})))))

(defn stop-file-publisher!
  "Stop global file publisher. Idempotent."
  []
  (when-let [handle @!publisher-handle]
    (mulog/stop-publisher! handle)
    (reset! !publisher-handle nil)))

(defn- pretty-event-str
  "Pretty-print one mulog event as a string suitable for a log file.
   Trailing blank line gives `awk -v RS=''` paragraph parsing if the
   user wants to filter further."
  [event]
  (str (with-out-str (pp/pprint event)) \newline))

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
                            (.write w ^String (pretty-event-str event))
                            (.flush w))
                          (catch Exception _)))))})]
      (swap! !session-publishers assoc session-id handle))))

(defn stop-session-publisher!
  "Stop the per-session publisher for `session-id`. Idempotent."
  [session-id]
  (when-let [handle (get @!session-publishers session-id)]
    (mulog/stop-publisher! handle)
    (swap! !session-publishers dissoc session-id)))
