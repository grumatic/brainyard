;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-persist.core.messages
  "Append-only event log for an agent session.

   Per docs/tmux-based-agent-tui.md §11.3 — `messages.log` is the canonical
   record of every event that drove the UI: input, agent.ask/pre+post, agent.tool-use/pre+post,
   task lifecycle, todo updates, etc.  Each event is one EDN map per line:

     {:t #inst \"...\" :kind :agent.ask/pre :payload {...}}

   The log is append-only.  Reading it back replays the conversation without
   needing the rendered ANSI scrollback."
  (:require [ai.brainyard.agent-tui-persist.core.edn-io :as edn-io]
            [ai.brainyard.agent-tui-persist.core.paths :as paths]))

(defn- now-millis ^long [] (System/currentTimeMillis))

(defn- normalise-event
  [{:keys [t kind payload] :as event}]
  (cond-> event
    (nil? t)       (assoc :t (now-millis))
    (nil? kind)    (assoc :kind :unknown)
    (nil? payload) (assoc :payload {})))

(defn append!
  "Append an event map to the session's messages.log.  `event` should at
   minimum have a `:kind` keyword; `:t` and `:payload` are filled in if absent."
  [session-id event]
  (edn-io/append-line!
   (paths/file-of session-id :messages)
   (normalise-event event)))

(defn read-all
  "Return all logged events for the session as a vector, in insertion order."
  [session-id]
  (vec (edn-io/read-lines (paths/file-of session-id :messages))))

(defn read-since
  "Return events whose `:t` (epoch millis) is after `since-millis`."
  [session-id ^long since-millis]
  (vec (filter (fn [{:keys [t]}]
                 (and t (> ^long t since-millis)))
               (read-all session-id))))

(defn last-event
  "Return the most recently appended event, or nil if the log is empty."
  [session-id]
  (last (read-all session-id)))

(defn count-events
  "Number of events currently in the log."
  [session-id]
  (count (edn-io/read-lines (paths/file-of session-id :messages))))
