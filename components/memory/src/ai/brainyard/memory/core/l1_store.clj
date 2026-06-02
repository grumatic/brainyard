;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.l1-store
  "L1 in-memory store for the unified memory subsystem.

  All L1 entries are session-scoped — every entry must carry a non-nil
  `:session-id`. The store atom is keyed by the composite tuple
  `[session-id entry-id]`, so two sessions can independently hold an
  entry with the same `entry-id` without collision.

  Entries are differentiated by `:kind`:
    :system-context — operator-managed configuration (instruction,
                      agent-context, tool-context fragments). Quota-free.
    :user-context   — model- or user-supplied context (notes,
                      preferences). Quota-free in this revision; the
                      sandbox-side notes API has been removed and will
                      be revisited later.

  Entry-id format is canonical: `{kind}/{field}/{section}` — see
  `l1-entry-id` below."
  (:require [ai.brainyard.memory.interface.protocol :as proto]
            [ai.brainyard.memory.core.entry :as entry]
            [ai.brainyard.mulog.interface :as mulog]))

;; =====================================================
;; Entry-id
;; =====================================================

(defn- ->name
  [v]
  (cond
    (keyword? v) (name v)
    (symbol? v)  (name v)
    :else        (str v)))

(defn l1-entry-id
  "Compose an L1 entry-id from the canonical (kind, field, section)
  triple. The id is the SAME for two sessions that pin the same
  (kind, field, section); session-scoping is enforced at the storage
  layer via the composite `[session-id entry-id]` lookup key.

  Examples:
    (l1-entry-id :system-context :tool-context \"naming\")
    ; => \"system-context/tool-context/naming\"

    (l1-entry-id :user-context :preferences \"timezone\")
    ; => \"user-context/preferences/timezone\""
  [kind field section]
  (str (->name kind) "/" (->name field) "/" (->name section)))

;; =====================================================
;; Match
;; =====================================================

(defn- match?
  "True if `entry` matches every constraint in `query`. Supported keys:
    :id, :kind, :session-id, :user-id, :field, :section."
  [query entry]
  (let [field-of   #(get-in entry [:data :field])
        section-of #(get-in entry [:data :section])]
    (every?
     (fn [[k v]]
       (case k
         :id         (= v (:id entry))
         :kind       (= (keyword v) (:kind entry))
         :session-id (= v (:session-id entry))
         :user-id    (= v (:user-id entry))
         :field      (= (keyword v) (field-of))
         :section    (= v (section-of))
         true))
     query)))

(defn- include-archived? [opts]
  (boolean (:include-archived opts)))

(defn- include-tombstoned? [opts]
  (boolean (:include-tombstoned opts)))

(defn- visible? [entry opts]
  (and (or (include-archived? opts) (not (:archived entry)))
       (or (include-tombstoned? opts) (not (:tombstoned entry)))))

;; =====================================================
;; L1Store record
;; =====================================================

(defrecord L1Store [!entries]
  proto/IMemoryStore

  (write-entry [_ layer in]
    (let [layer' (keyword layer)]
      (when-not (= :l1 layer')
        (throw (ex-info "L1Store only handles layer :l1"
                        {:layer layer'})))
      (when-not (:session-id in)
        (throw (ex-info "L1 entries require a non-nil :session-id"
                        {:entry in})))
      (let [now   (System/currentTimeMillis)
            id    (or (:id in) (str (random-uuid)))
            sid   (:session-id in)
            entry (entry/->entry
                   (merge in
                          {:id         id
                           :layer      :l1
                           :created-at (or (:created-at in) now)}))]
        (when-not (entry/valid-entry? entry)
          (throw (ex-info "L1Store/write-entry: invalid entry"
                          {:entry entry})))
        (swap! !entries assoc [sid id] entry)
        entry)))

  (read-entries [_ layer query opts]
    (when-not (= :l1 (keyword layer))
      (throw (ex-info "L1Store only handles layer :l1"
                      {:layer layer})))
    (let [limit (or (:limit opts) 100)
          xs    (->> (vals @!entries)
                     (filter #(visible? % opts))
                     (filter #(match? query %))
                     (sort-by (juxt (comp - (fnil long 0) :created-at))))]
      (vec (take limit xs))))

  (promote [this _entry _from-layer _to-layer]
    ;; Cross-layer promotion is handled by UnifiedStore — within the
    ;; L1Store there's no upward layer to promote into.
    (throw (ex-info "L1Store/promote: use UnifiedStore for cross-layer promote"
                    {})))

  (forget [_ layer entry-id]
    (when-not (= :l1 (keyword layer))
      (throw (ex-info "L1Store only handles layer :l1"
                      {:layer layer})))
    ;; entry-id alone isn't enough — we'd need (session-id, entry-id).
    ;; The UnifiedStore's forget on :l1 supplies the full composite via
    ;; the public path. For direct callers we accept either a string
    ;; (matches any session — first hit) or a [sid id] vector.
    (let [composite-key
          (cond
            (vector? entry-id) entry-id
            (string? entry-id) (some (fn [[sid id :as k]]
                                       (when (= id entry-id) k))
                                     (keys @!entries))
            :else nil)
          existed? (and composite-key (contains? @!entries composite-key))]
      (when existed?
        (swap! !entries dissoc composite-key)
        (mulog/debug ::l1-forget :composite-key composite-key))
      (boolean existed?)))

  (consolidate-layer [_ from-layer policy]
    ;; L1 doesn't consolidate upward — entries are session-scoped and
    ;; the upward path (L2→L3) lives in UnifiedStore. :l1 returns a zero
    ;; summary; anything else is a contract error.
    (let [from-layer' (keyword from-layer)]
      (case from-layer'
        :l1
        {:from-layer :l1 :to-layer nil
         :produced 0 :consumed 0 :auto-kept 0 :batches []}

        (throw (ex-info "L1Store/consolidate-layer: unknown from-layer"
                        {:from-layer from-layer' :policy policy}))))))

;; =====================================================
;; Factory
;; =====================================================

(defn create-l1-store
  "Create a new L1Store. No options — quota and per-layer config were
  removed in the L1 simplification refactor."
  []
  (->L1Store (atom {})))
