;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-persist.core.restore
  "Reconstruct an in-memory agent-session map from on-disk persistence.

   The result mirrors the shape created by
   `ai.brainyard.agent.core.session/create-session` so callers (the TUI base)
   can wrap it in an atom and drop it into an `ISessionStore` via
   `set-session`. This namespace has no compile-time dependency on the agent
   component — it just folds the on-disk events log + session snapshot + meta
   back into a session map.

   Contract with the writer side (the TUI persist bridge):
   - `messages.log` is the source of truth for `:messages`. Each user/assistant
     message is appended once as `{:kind :message :payload {:role :content ...}}`.
   - `session.edn` (the new `:session` snap kind) holds everything else:
     `:user-id`, `:config`, `:data`, `:agent-activity`, `:agent-activity-seq`,
     `:total-turns`, timestamps. The bridge should write it without `:messages`
     to keep snap files small.
   - `session-id` from the directory name always wins over any value found on
     disk."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.brainyard.agent-tui-persist.core.paths :as paths]
            [ai.brainyard.agent-tui-persist.core.snapshots :as snapshots]))

(defn- now-millis ^long [] (System/currentTimeMillis))

(defn- empty-session
  [session-id user-id]
  (let [now (now-millis)]
    {:session-id session-id
     :user-id user-id
     :config {}
     :messages []
     :data {:traces [] :todo-info nil :artifacts [] :cache-hits 0 :exceptions []}
     :agent-activity []
     :agent-activity-seq 0
     :total-turns 0
     :created-at now
     :updated-at now}))

(defn- read-events-tolerant
  "Like `messages/read-all`, but skips malformed lines instead of aborting the
   whole read. Returns a vector of events in insertion order."
  [session-id]
  (let [f (paths/file-of session-id :messages)]
    (if (and f (.exists f))
      (with-open [r (io/reader f)]
        (->> (line-seq r)
             (keep (fn [line]
                     (let [trimmed (clojure.string/trim line)]
                       (when (seq trimmed)
                         (try
                           (edn/read-string {:readers *data-readers*} trimmed)
                           (catch Throwable _ nil))))))
             vec))
      [])))

(defn- fold-events
  "Reduce events log into the parts of the session map it owns. Today only
   `:kind :message` events contribute; other kinds (`:ask/pre`,
   `:agent.tool-use/*`, lifecycle) are diagnostic and stay in the log for
   replay/debugging without entering the session map."
  [events]
  (reduce (fn [acc {:keys [kind payload]}]
            (case kind
              :message (update acc :messages conj payload)
              acc))
          {:messages []}
          events))

(defn restore-session-map
  "Reconstruct the agent-session map for `session-id` from its persisted files.

   Reads (best-effort, missing files default to empty):
   - `meta.edn`     — for `:user-id` fallback when the session snap is absent.
   - `session.edn`  — snapshot of the session map, source of truth for
                      everything except `:messages`.
   - `messages.log` — append-only events; `:kind :message` entries are folded
                      into `:messages`, preserving order. Malformed lines are
                      skipped.

   Returns a map shaped like `ai.brainyard.agent.core.session/create-session`
   so it drops straight into the agent's `ISessionStore`."
  [session-id]
  (let [meta-map (snapshots/read-meta session-id)
        snap (snapshots/read-snap session-id :session nil)
        user-id (or (:user-id snap) (:user-id meta-map))
        ;; Always start from `empty-session` defaults and merge the snap on top,
        ;; so callers can rely on every key being present (`:config` and
        ;; `:data` shapes in particular) even when the bridge intentionally
        ;; strips them from disk.
        base (merge (empty-session session-id user-id)
                    (or snap {}))
        {:keys [messages]} (fold-events (read-events-tolerant session-id))]
    (cond-> base
      true            (assoc :session-id session-id
                             :messages messages)
      (some? user-id) (assoc :user-id user-id))))
