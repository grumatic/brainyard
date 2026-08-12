;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.mulog.core.context
  "Context management helpers for μ/log."
  (:require [com.brunobonacci.mulog :as mu]))

(defn set-global-context!
  "Set global context that applies to all events.
   Common keys: :app-name :version :env :host"
  [context-map]
  (mu/set-global-context! context-map))

(defn update-global-context!
  "Update global context with additional key-values."
  [context-map]
  (mu/update-global-context! merge context-map))

(defmacro with-context
  "Execute body with additional local context."
  [context-map & body]
  `(mu/with-context ~context-map ~@body))

(defn process-id
  "This process's OS pid."
  ^long []
  (.pid ^java.lang.ProcessHandle (java.lang.ProcessHandle/current)))

(defn install-process-context!
  "Stamp this process's pid onto EVERY mulog event, via the global context.

   Many `by` processes append to ONE `~/.brainyard/logs/agent-tui-app.log`:
   an interactive TUI, a detached `by memory reduce`, `by a2a serve`, and
   every one-shot CLI command. Nothing in an event said which of them wrote
   it, so an interleaved log could not be attributed to a process at all —
   which is exactly what you need first when the same event appears in the
   file more times than it was emitted.

   Merges rather than replaces, so it composes with the per-turn context
   `coact-agent` sets later (user-id, session-id, agent-id, turn-id).

   Must be called at RUNTIME and never from a top-level form: under GraalVM
   native-image a load-time side effect runs at BUILD time, which would bake
   the COMPILING process's pid into the binary and report it forever after —
   the same trap that froze the A2A node id."
  []
  (update-global-context! {:pid (process-id)}))

(defn app-context
  "Create a standard application context map."
  [{:keys [app-name version env]
    :or {env "development"}}]
  {:app-name app-name
   :version version
   :env env
   :host (.. java.net.InetAddress getLocalHost getHostName)
   :pid (.pid (java.lang.ProcessHandle/current))})
