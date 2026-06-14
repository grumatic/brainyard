;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.core.eviction
  "Disk-usage and TTL eviction for persisted sessions.

   Per docs/tmux-based-agent-tui.md §11.5 — per-session size cap (default 50 MB)
   with rotation eviction handled by `scrollback`, and a global TTL on
   `meta.edn :ttl-days` (default 14)."
  (:require [ai.brainyard.agent-tui-persist.core.paths :as paths]
            [ai.brainyard.agent-tui-persist.core.scrollback :as scrollback]
            [ai.brainyard.agent-tui-persist.core.snapshots :as snapshots])
  (:import [java.io File]))

(def default-max-bytes
  "Per-session disk budget (50 MiB)."
  (* 50 1024 1024))

(def default-ttl-days 14)

(defn- session-size [session-id]
  (let [^File dir (paths/session-dir session-id)]
    (reduce + 0
            (for [^File f (file-seq dir)
                  :when (.isFile f)]
              (.length f)))))

(defn enforce-size!
  "If the session directory exceeds `max-bytes`, drop oldest scrollback
   rotations until under budget.  Returns a summary map."
  ([session-id] (enforce-size! session-id default-max-bytes))
  ([session-id max-bytes]
   (let [start (session-size session-id)]
     (when (> start max-bytes)
       ;; Truncate scrollback streams; events log + snapshots are tiny.
       (scrollback/truncate! session-id :activity)
       (scrollback/truncate! session-id :stream))
     {:session-id session-id
      :start-bytes start
      :end-bytes (session-size session-id)
      :max-bytes max-bytes})))

(declare safe-read-meta)

(defn- last-attached-at-millis
  "Resolve the session's last-attached-at timestamp (epoch millis) from
   meta.edn, falling back to started-at, then to now. Tolerates a
   corrupt meta.edn (treats it as 'now')."
  ^long [session-id]
  (let [meta (safe-read-meta session-id)]
    (or (:last-attached-at meta) (:started-at meta) (System/currentTimeMillis))))

(def ^:private millis-per-day (* 24 60 60 1000))

(defn expired?
  "True when the session has not been attached for `ttl-days`."
  ([session-id] (expired? session-id default-ttl-days))
  ([session-id ttl-days]
   (let [last-at (last-attached-at-millis session-id)
         age-ms  (- (System/currentTimeMillis) last-at)]
     (> age-ms (* ^long ttl-days millis-per-day)))))

(defn purge-expired!
  "Delete every session directory whose meta is older than `ttl-days`.  Returns
   the list of deleted session-ids."
  ([] (purge-expired! default-ttl-days))
  ([ttl-days]
   (let [victims (filter #(expired? % ttl-days) (paths/list-sessions))]
     (doseq [s victims] (paths/delete-session-dir! s))
     (vec victims))))

(defn- safe-read-meta
  "Read a session's meta.edn but never throw — a corrupt or
   unparseable file just yields nil. Without this, ONE bad
   meta.edn (e.g. an old session written by buggy keyword
   parsing) would block /session list and discover-attach-target
   for the entire user. Prints a one-line warning on stderr so
   the corruption isn't completely silent."
  [session-id]
  (try
    (snapshots/read-meta session-id)
    (catch Throwable t
      (binding [*out* *err*]
        (println (str "[persist] skipping unreadable meta.edn for "
                      session-id ": " (.getMessage t))))
      nil)))

(defn summarise
  "Return a summary of every persisted session (id, size, last-attached-at).
   Sessions whose meta.edn is unreadable are still included with
   meta-derived fields nil, so the user can still see them in
   /session list and prune them."
  []
  (vec (for [s (paths/list-sessions)
             :let [meta (safe-read-meta s)]]
         {:session-id s
          :bytes (session-size s)
          :started-at (:started-at meta)
          :last-attached-at (:last-attached-at meta)
          :created-at (:created-at meta)
          :agent-id (:agent-id meta)
          :defagent-id (:defagent-id meta)
          :model (:model meta)
          :label (:label meta)
          :working-dir (:working-dir meta)
          :parent-id (:parent-id meta)
          :fork-point (:fork-point meta)})))
