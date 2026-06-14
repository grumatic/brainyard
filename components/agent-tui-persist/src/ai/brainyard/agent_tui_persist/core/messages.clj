;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.core.messages
  "Append-only event log for an agent session.

   Per docs/tmux-based-agent-tui.md §11.3 — `messages.log` is the canonical
   record of every event that drove the UI: input, agent.ask/pre+post, agent.tool-use/pre+post,
   task lifecycle, todo updates, etc.  Each event is one EDN map per line:

     {:t #inst \"...\" :kind :agent.ask/pre :payload {...}}

   The log is append-only.  Reading it back replays the conversation without
   needing the rendered ANSI scrollback."
  (:require [ai.brainyard.agent-tui-persist.core.edn-io :as edn-io]
            [ai.brainyard.agent-tui-persist.core.paths :as paths]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

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

(defn scan-log
  "Single streaming pass over the session's messages.log, returning a compact
   summary without materialising the whole log:

     {:event-count       n        ; total events
      :first-user-input  s|nil    ; first :agent.ask/pre :input (the user's first prompt)
      :last-answer       s|nil}   ; last :agent.ask/post :answer

   Parses each line once and stops growing memory — suitable for enumerating
   many sessions (e.g. `by sessions list`). A missing/empty log yields
   {:event-count 0 :first-user-input nil :last-answer nil}. Lines that fail to
   parse are skipped (the count still advances on readable lines only)."
  [session-id]
  (let [^java.io.File file (paths/file-of session-id :messages)]
    (if-not (.exists file)
      {:event-count 0 :first-user-input nil :last-answer nil}
      (with-open [r (io/reader file)]
        (reduce
         (fn [acc line]
           (let [trimmed (str/trim line)]
             (if (empty? trimmed)
               acc
               (let [ev (try (edn/read-string {:readers *data-readers*} trimmed)
                             (catch Exception _ ::skip))]
                 (if (= ev ::skip)
                   acc
                   (let [acc (update acc :event-count inc)]
                     (case (:kind ev)
                       :agent.ask/pre
                       (cond-> acc
                         (nil? (:first-user-input acc))
                         (assoc :first-user-input (get-in ev [:payload :input])))
                       :agent.ask/post
                       (assoc acc :last-answer (get-in ev [:payload :answer]))
                       acc)))))))
         {:event-count 0 :first-user-input nil :last-answer nil}
         (line-seq r))))))
