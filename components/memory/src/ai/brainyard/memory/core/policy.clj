;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.policy
  "Retention and archival policy helpers for L2 / L3.

  L1 is session-scoped and expires implicitly when its session ends.
  L2 (episodes) and L3 (semantic facts) carry three persistence flags
  that govern long-term retention:

    keep_flag        — pinned, retained beyond the TTL sweep
    archived_flag    — excluded from default recall, body retained for
                       backreference + audit
    tombstoned_flag  — soft-deleted, excluded from default recall and
                       the L2 sweep can hard-delete it

  The default L2 retention window is 30 days. L3 facts are never
  auto-deleted — they are the long-term memory layer."
  (:require [next.jdbc :as jdbc]
            [clojure.core.async :as async]
            [ai.brainyard.mulog.interface :as mulog]))

;; =====================================================
;; Constants
;; =====================================================

(def ^:const default-l2-retention-days 30)

;; =====================================================
;; Per-layer table mapping
;; =====================================================

(defn- layer->table
  [layer]
  (case (keyword layer)
    :l2 "episodes"
    :l3 "semantic_facts"
    (throw (ex-info "policy: unknown layer (only :l2/:l3 are SQL-backed)"
                    {:layer layer}))))

(defn- update-flag!
  [ds layer entry-id user-id flag-col value]
  (let [table (layer->table layer)
        sql   (format "UPDATE %s SET %s = ? WHERE entry_id = ? AND user_id = ?"
                      table flag-col)
        r     (jdbc/execute-one! ds [sql (if value 1 0) entry-id user-id])]
    (pos? (or (:next.jdbc/update-count r) 0))))

;; =====================================================
;; Public flag setters
;; =====================================================

(defn mark-keep!
  "Pin (or unpin) an L2/L3 entry against the retention sweep."
  [ds layer entry-id user-id keep?]
  (let [ok? (update-flag! ds layer entry-id user-id "keep_flag" keep?)]
    (when ok?
      (mulog/debug ::mark-keep :layer layer :entry-id entry-id :keep keep?))
    ok?))

(defn mark-archived!
  "Mark (or unmark) an L2/L3 entry as archived. Archived entries are
  excluded from default recall but remain retrievable with
  `:include-archived true`."
  [ds layer entry-id user-id archived?]
  (let [ok? (update-flag! ds layer entry-id user-id "archived_flag" archived?)]
    (when ok?
      (mulog/debug ::mark-archived :layer layer :entry-id entry-id :archived archived?))
    ok?))

(defn tombstone!
  "Soft-delete an L2/L3 entry. Returns true when an entry was affected."
  [ds layer entry-id user-id]
  (let [ok? (update-flag! ds layer entry-id user-id "tombstoned_flag" true)]
    (when ok?
      (mulog/debug ::tombstone :layer layer :entry-id entry-id))
    ok?))

;; =====================================================
;; Bulk keep by primary id (used by the consolidate auto-keep path)
;; =====================================================

(defn keep-episodes-by-db-id!
  "Set keep_flag=1 on episodes whose autoincrement `id` (NOT
  entry_id) is in `db-ids`. Used by the S2 reducer to pin source
  episodes that were consolidated into L3 facts so they survive the
  retention sweep and the fact's :sources chain remains valid."
  [ds db-ids]
  (when (seq db-ids)
    (let [placeholders (clojure.string/join ", " (repeat (count db-ids) "?"))
          sql (str "UPDATE episodes SET keep_flag = 1 WHERE id IN ("
                   placeholders ")")
          r   (jdbc/execute-one! ds (into [sql] db-ids))]
      (or (:next.jdbc/update-count r) 0))))

;; =====================================================
;; L2 retention sweep
;; =====================================================

(defn sweep-l2!
  "Tombstone L2 episodes older than `retention-days` where
  `keep_flag = 0` and `tombstoned_flag = 0`. Returns the number of
  rows tombstoned.

  This does NOT hard-delete: the rows remain in `episodes` (so audit
  references stay resolvable) but are excluded from default reads via
  the `tombstoned_flag = 0` filter that `unified_store/l2-read`
  applies. A subsequent administrative VACUUM job (operator-driven)
  can purge tombstoned rows older than a longer window."
  [ds & {:keys [retention-days user-id]
         :or   {retention-days default-l2-retention-days}}]
  (let [base "UPDATE episodes
              SET tombstoned_flag = 1
              WHERE keep_flag = 0
                AND tombstoned_flag = 0
                AND timestamp < datetime('now', '-' || ? || ' days')"
        [sql params] (if user-id
                       [(str base " AND user_id = ?") [retention-days user-id]]
                       [base [retention-days]])
        r (jdbc/execute-one! ds (into [sql] params))
        n (or (:next.jdbc/update-count r) 0)]
    (mulog/info ::sweep-l2-done
                :retention-days retention-days
                :user-id user-id
                :tombstoned n)
    n))

;; =====================================================
;; Scheduled sweeper
;; =====================================================

(def ^:const default-sweep-interval-ms (* 6 60 60 1000)) ; 6 hours

(defrecord Sweeper [ds config !running thread])

(defn start-sweeper!
  "Spawn a background thread that runs `sweep-l2!` every
  `interval-ms` until `stop-sweeper!` is called.

  Options:
    :interval-ms    — default 6 hours
    :retention-days — default 30 days
    :user-id        — optional scope to one user partition
    :run-on-start?  — if truthy, runs one sweep immediately after
                      starting (default false)

  The first sweep happens after `interval-ms`; passing `:run-on-start?
  true` is convenient for tests and one-shot housekeeping."
  [ds & {:keys [interval-ms retention-days user-id run-on-start?]
         :or   {interval-ms    default-sweep-interval-ms
                retention-days default-l2-retention-days}}]
  (let [!running (atom true)
        cfg      {:interval-ms interval-ms
                  :retention-days retention-days
                  :user-id user-id}
        t        (async/thread
                   (try
                     (when run-on-start?
                       (sweep-l2! ds :retention-days retention-days
                                  :user-id user-id))
                     (loop []
                       (when @!running
                         ;; Wake up periodically so stop-sweeper! has a
                         ;; bounded latency. We don't use one big sleep
                         ;; because that ignores the !running flag.
                         (let [step-ms (long (min 1000 interval-ms))
                               steps   (long (Math/ceil (/ interval-ms step-ms)))]
                           (loop [i 0]
                             (when (and @!running (< i steps))
                               ;; (long ...) required for native-image:
                               ;; Thread/sleep has (long) and (long, int)
                               ;; overloads; without a primitive hint
                               ;; Clojure dispatches via reflection,
                               ;; which native-image strips.
                               (Thread/sleep step-ms)
                               (recur (inc i)))))
                         (when @!running
                           (try
                             (sweep-l2! ds :retention-days retention-days
                                        :user-id user-id)
                             (catch Exception e
                               (mulog/warn ::sweeper-iteration-failed
                                           :error (ex-message e)))))
                         (recur)))
                     (catch Throwable t
                       (mulog/error ::sweeper-crashed :exception t))))]
    (mulog/info ::sweeper-started :config cfg)
    (->Sweeper ds cfg !running t)))

(defn stop-sweeper!
  "Stop a running sweeper. Idempotent. Returns nil."
  [^Sweeper s]
  (when (and s @(:!running s))
    (reset! (:!running s) false)
    (mulog/info ::sweeper-stopping)
    nil))
