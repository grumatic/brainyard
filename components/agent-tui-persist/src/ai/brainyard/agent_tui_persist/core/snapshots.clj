;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.core.snapshots
  "Atomic snapshot files for transient agent state — pending dialogs, queue,
   permissions cache, todo, status, layout, meta.

   Per docs/tmux-based-agent-tui.md §11.3 each snapshot is a single EDN map
   written via `edn-io/atomic-write!`.  The file is the recovery anchor on
   `by-host` startup — if `pending-dialogs.edn` is non-empty, the persisted
   questionnaires are re-emitted to the next attaching `by-ui`."
  (:require [ai.brainyard.agent-tui-persist.core.edn-io :as edn-io]
            [ai.brainyard.agent-tui-persist.core.paths :as paths]))

(defn- snap-fn
  "Build a {read,write} pair against the file tagged `kind` (e.g. :meta)."
  [kind]
  {:read  (fn read-snap
            ([session-id] (edn-io/read-edn (paths/file-of session-id kind) nil))
            ([session-id default]
             (edn-io/read-edn (paths/file-of session-id kind) default)))
   :write (fn write-snap [session-id value]
            (edn-io/atomic-write! (paths/file-of session-id kind) value))})

(def ^:private snap-handles
  (into {} (for [kind [:meta :pending-dialogs :permissions :queue :todo :status :layout :session
                       :input-history :usage-tracker]]
             [kind (snap-fn kind)])))

(defn read-snap
  "Read snapshot value for `kind` (one of :meta, :pending-dialogs, :permissions,
   :queue, :todo, :status, :layout, :session, :input-history, :usage-tracker).
   Returns `default` when the file is missing or empty."
  ([session-id kind] (read-snap session-id kind nil))
  ([session-id kind default]
   (when-let [{:keys [read]} (get snap-handles kind)]
     (read session-id default))))

(defn write-snap!
  "Atomically write `value` as the snapshot for `kind`.  Returns the file."
  [session-id kind value]
  (when-let [{:keys [write]} (get snap-handles kind)]
    (write session-id value)))

(defn update-snap!
  "Read-modify-write a snapshot via `f`.  Not atomic across concurrent updaters
   — caller is responsible for serialisation (typically by holding a per-session
   ReentrantLock)."
  ([session-id kind f]
   (let [v0 (read-snap session-id kind nil)]
     (write-snap! session-id kind (f v0))))
  ([session-id kind f & args]
   (update-snap! session-id kind #(apply f % args))))

;; -- Specialised wrappers for the most common cases ---------------------------

(defn save-meta!
  "Write or merge into the session's meta.edn (agent-id, started-at, working-
   dir, model, etc.)."
  [session-id meta]
  (update-snap! session-id :meta
                (fn [prev]
                  (-> (merge prev meta)
                      (update :started-at #(or % (System/currentTimeMillis)))))))

(defn read-meta
  [session-id]
  (read-snap session-id :meta {}))

(defn pending-dialogs
  "Return the queue of pending dialog questionnaires, de-duplicated by
   `:id`.  Older daemons appended the same questionnaire on every
   reattach (orchestrator added on every incoming `:popup`, including
   on-attach replays), so existing files may contain dozens of copies
   of the same id.  Returning the FIRST occurrence of each id keeps
   on-attach replay sane without a destructive migration."
  [session-id]
  (let [raw (read-snap session-id :pending-dialogs [])]
    (->> raw
         (reduce (fn [{:keys [seen out]} d]
                   (if (contains? seen (:id d))
                     {:seen seen :out out}
                     {:seen (conj seen (:id d))
                      :out  (conj out d)}))
                 {:seen #{} :out []})
         :out)))

(defn save-pending-dialogs!
  [session-id dialogs]
  (write-snap! session-id :pending-dialogs (vec dialogs)))

(defn add-pending-dialog!
  "Add a questionnaire payload to the pending-dialogs queue, idempotent
   by `:id` — calling twice for the same questionnaire keeps a single
   entry instead of duplicating.  Returns the updated vector."
  [session-id dialog]
  (update-snap! session-id :pending-dialogs
                (fn [prev]
                  (let [prev (or prev [])
                        already? (some #(= (:id %) (:id dialog)) prev)]
                    (if already? prev (conj prev dialog))))))

(defn remove-pending-dialog!
  "Drop the dialog whose `:id` matches `id`.  Returns the updated vector."
  [session-id id]
  (update-snap! session-id :pending-dialogs
                (fnil (fn [xs] (vec (remove #(= (:id %) id) xs))) [])))

(defn read-permissions
  [session-id]
  (read-snap session-id :permissions {}))

(defn save-permissions!
  [session-id permissions]
  (write-snap! session-id :permissions permissions))
