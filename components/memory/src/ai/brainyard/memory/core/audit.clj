;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.core.audit
  "Audit log for memory entries that influence prompts.

  Every time a recall briefing is rendered for a turn, one row per entry
  that landed in the prompt is appended to `memory_audit`. The audit
  table is the authoritative record of what the agent 'knew' on a given
  turn — independent of L2/L3 sweep so the trail survives even after
  episodes age out.

  Reads:
    `explain session-id agent-id turn-id` — entries from a specific
                                            agent's turn within a session
    `explain-session session-id`  — every (agent, turn) in a session

  Audit rows carry the addressing tuple
  {user-id session-id agent-id turn-id total-turns entry-id layer
   byte-cost}; full entry bodies are looked up on demand from the live
  store. If an entry has since been tombstoned or archived, `explain`
  will still return the audit row but the body lookup may return nil
  (the snapshot is what's authoritative for 'what was on the prompt at
  the time')."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.memory.interface.protocol :as proto]))

;; =====================================================
;; Writer
;; =====================================================

(defn- byte-cost
  [entry]
  (try
    (count (or (:content entry) ""))
    (catch Exception _ 0)))

(defn- ->audit-row
  [{:keys [user-id session-id agent-id turn-id total-turns]} entry]
  {:user_id     user-id
   :session_id  session-id
   :agent_id    (some-> agent-id str)
   :turn_id     (long turn-id)
   :total_turns (when total-turns (long total-turns))
   :entry_id    (str (or (:id entry) (:db-id entry)))
   :layer       (cond
                  (keyword? (:layer entry)) (name (:layer entry))
                  :else                     (str (:layer entry)))
   :byte_cost   (byte-cost entry)})

(defn record-prompt!
  "Write one audit row per entry that landed in a turn's prompt.

  Required keys on `ctx`:
    :user-id  :session-id  :agent-id  :turn-id  :ds
  Optional:
    :total-turns — session-cumulative counter (recorded for ordering)

  `entries` is a vector of memory entries (as returned by
  `read-entries`). Empty/nil entries are skipped silently."
  [{:keys [ds] :as ctx} entries]
  (when (and ds (seq entries))
    (try
      (let [rows (mapv #(->audit-row ctx %) entries)]
        ;; next.jdbc.sql/insert-multi! is the right primitive but to keep
        ;; transactional semantics simple we batch in a single tx.
        (jdbc/with-transaction [tx ds]
          (doseq [row rows]
            (sql/insert! tx :memory_audit row)))
        (mulog/debug ::audit-recorded
                     :user-id (:user-id ctx)
                     :session-id (:session-id ctx)
                     :agent-id (:agent-id ctx)
                     :turn-id (:turn-id ctx)
                     :total-turns (:total-turns ctx)
                     :n (count rows))
        (count rows))
      (catch Exception e
        (mulog/warn ::audit-write-failed
                    :user-id (:user-id ctx)
                    :session-id (:session-id ctx)
                    :agent-id (:agent-id ctx)
                    :error (ex-message e))
        0))))

;; =====================================================
;; Reader
;; =====================================================

(defn- normalize-row
  "Strip the :memory_audit/ namespace from row keys."
  [row]
  (when row
    (into {}
          (map (fn [[k v]]
                 [(if (and (keyword? k) (= "memory_audit" (namespace k)))
                    (keyword (name k))
                    k)
                  v]))
          row)))

(defn- audit-rows-for-turn
  "Return audit rows for a specific (session, agent, turn) triple.
   When `agent-id` is nil, falls back to (session, turn) — useful for
   legacy lookups, but ambiguous when multiple agents share a session."
  [ds session-id agent-id turn-id]
  (mapv normalize-row
        (if agent-id
          (jdbc/execute! ds
                         ["SELECT * FROM memory_audit
                           WHERE session_id = ? AND agent_id = ? AND turn_id = ?
                           ORDER BY id ASC"
                          session-id (str agent-id) (long turn-id)])
          (jdbc/execute! ds
                         ["SELECT * FROM memory_audit
                           WHERE session_id = ? AND turn_id = ?
                           ORDER BY id ASC"
                          session-id (long turn-id)]))))

(defn- audit-rows-for-session
  [ds session-id]
  (mapv normalize-row
        (jdbc/execute! ds
                       ["SELECT * FROM memory_audit
                         WHERE session_id = ?
                         ORDER BY total_turns ASC, agent_id ASC, turn_id ASC, id ASC"
                        session-id])))

(defn- hydrate
  "Look up the live entry for an audit row from `store`. Returns nil
  when the entry has been hard-deleted (we still keep the audit row)."
  [store {:keys [layer entry_id]}]
  (let [layer-kw (keyword layer)
        entries  (try
                   (proto/read-entries store layer-kw {:id entry_id}
                                       {:limit 1
                                        :include-archived true
                                        :include-tombstoned true})
                   (catch Exception _ []))]
    (first entries)))

(defn explain-turn
  "Return what entries informed `turn-id` of `agent-id` within
  `session-id`. Hydrates each audit row with the live entry body when
  available.

  When `agent-id` is nil, returns rows from any agent matching the
  (session, turn) pair (useful for legacy callers).

  Shape:
    {:session-id ...
     :agent-id   ...
     :turn-id    ...
     :user-id    ...
     :entries    [{:audit {...} :entry {...} or nil} ...]
     :prompt-bytes <sum of byte_cost>
     :recall-query nil}"
  [ds store session-id agent-id turn-id]
  (let [rows (audit-rows-for-turn ds session-id agent-id turn-id)
        items (mapv (fn [row]
                      {:audit row
                       :entry (hydrate store row)})
                    rows)]
    {:session-id   session-id
     :agent-id     agent-id
     :turn-id      turn-id
     :user-id      (some :user_id rows)
     :entries      items
     :prompt-bytes (reduce + 0 (keep :byte_cost rows))
     :recall-query nil}))

(defn explain-session
  "Return per-(agent, turn) explanations for an entire session, sorted
  by total_turns ascending so the rendered timeline matches the actual
  ask order across root + sub-agents."
  [ds store session-id]
  (let [rows (audit-rows-for-session ds session-id)
        ;; Group by (agent_id, turn_id) — each ask of an agent is one slot.
        by-slot (->> rows
                     (group-by (juxt :agent_id :turn_id))
                     (sort-by (fn [[_ rs]]
                                ;; sort key: smallest total_turns in the slot
                                (or (some :total_turns rs) Long/MAX_VALUE))))]
    {:session-id session-id
     :turns      (mapv (fn [[[agent-id turn-id] slot-rows]]
                         {:agent-id    agent-id
                          :turn-id     turn-id
                          :total-turns (some :total_turns slot-rows)
                          :entries     (mapv (fn [row]
                                               {:audit row
                                                :entry (hydrate store row)})
                                             slot-rows)
                          :prompt-bytes (reduce + 0 (keep :byte_cost slot-rows))})
                       by-slot)}))
