;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.memory.core.entry
  "Unified-memory entry schema and adapters.

  An `entry` is the canonical EDN map shape used by the IMemoryStore
  protocol. It is layer-agnostic on the read/write surface; this namespace
  translates entries to and from the per-layer storage rows:

    :l1           — in-memory map, no row mapping required
    :l2           — `episodes` SQLite row (see core.episodic)
    :l3           — `semantic_facts` SQLite row (see core.semantic)

  Column-vs-metadata split:
    Real columns hold filterable / indexed fields:
      tags, sources, entry_id, keep_flag, archived_flag, tombstoned_flag
    The `metadata` JSON column carries non-indexable fields:
      :ttl, :data, plus any user-supplied metadata."
  (:require [clojure.data.json :as json]
            [ai.brainyard.memory.interface.protocol :as proto]))

;; =====================================================
;; Entry Construction & Validation
;; =====================================================

(def ^:private entry-defaults
  {:tags         #{}
   :sources      []
   :data         nil
   :ttl          nil
   :confidence   1.0
   :access-count 0
   :keep         false
   :archived     false
   :tombstoned   false})

(defn ->entry
  "Construct a normalized entry map from a partial input.

  Required keys (when not provided by the caller, the IMemoryStore
  implementation injects them): :layer, :kind, :content.

  All other keys default per `entry-defaults`. The returned map always
  contains every entry field so downstream adapters can dispatch
  uniformly without nil-checking."
  [m]
  (-> entry-defaults
      (merge m)
      (update :tags #(if (set? %) % (set %)))
      (update :sources #(or (vec %) []))))

(defn valid-entry?
  "True when `m` carries the minimum entry shape: a valid layer, kind,
  and a string content."
  [m]
  (and (map? m)
       (proto/valid-layer? (:layer m))
       (proto/valid-entry-kind? (:kind m))
       (string? (:content m))))

;; =====================================================
;; JSON pack/unpack helpers (column ↔ Clojure)
;; =====================================================

(defn- pack-tags
  "Tags column is a JSON array of strings."
  [tags]
  (when (seq tags)
    (json/write-str (vec tags))))

(defn- unpack-tags
  [s]
  (cond
    (nil? s) #{}
    (string? s) (try (set (json/read-str s)) (catch Exception _ #{}))
    :else (set s)))

(defn- pack-sources
  [sources]
  (when (seq sources)
    (json/write-str (vec sources))))

(defn- unpack-sources
  [s]
  (cond
    (nil? s) []
    (string? s) (try (vec (json/read-str s :key-fn keyword))
                     (catch Exception _ []))
    :else (vec s)))

(defn- pack-metadata
  "Pack non-indexed entry fields plus user metadata into a single JSON
  blob stored in the `metadata` column."
  [{:keys [ttl data] :as entry}]
  (let [user-meta (:metadata entry)
        blob (cond-> {}
               ttl   (assoc :ttl ttl)
               data  (assoc :data data)
               user-meta (assoc :metadata user-meta))]
    (when (seq blob)
      (json/write-str blob))))

(defn- unpack-metadata
  "Unpack the metadata JSON blob into {:ttl :data :metadata}."
  [s]
  (cond
    (nil? s) {}
    (string? s) (try (json/read-str s :key-fn keyword)
                     (catch Exception _ {}))
    (map? s) s
    :else {}))

(defn- ->bool
  "Coerce a SQLite integer flag into a boolean."
  [v]
  (cond
    (boolean? v) v
    (number? v)  (not (zero? v))
    (nil? v)     false
    :else        (boolean v)))

(defn- bool->int
  [v]
  (if v 1 0))

;; =====================================================
;; L2 (episodes) adapters
;; =====================================================

(defn entry->episode-row
  "Translate an entry map into an `episodes` row suitable for sql/insert!.

  Maps:
    :kind       → :episode_type   (string)
    :session-id → :session_id
    :user-id    → :user_id
    :role       → :role           (entries can carry an optional :role)
    :content    → :content
    :tags       → :tags           (JSON)
    :sources    → :sources        (JSON)
    :id         → :entry_id       (cross-layer stable id; SQL `id` is autoincrement)
    :keep       → :keep_flag
    :archived   → :archived_flag
    :tombstoned → :tombstoned_flag
    :ttl/:data/:metadata → packed into the `metadata` JSON column."
  [{:keys [kind session-id user-id role content id
           keep archived tombstoned tags sources]
    :as entry}]
  {:session_id      session-id
   :user_id         user-id
   :episode_type    (if (keyword? kind) (name kind) (str kind))
   :role            (when role (str role))
   :content         content
   :tags            (pack-tags tags)
   :sources         (pack-sources sources)
   :entry_id        id
   :keep_flag       (bool->int keep)
   :archived_flag   (bool->int archived)
   :tombstoned_flag (bool->int tombstoned)
   :metadata        (pack-metadata entry)})

(defn episode-row->entry
  "Translate a row coming back from the `episodes` table into an entry."
  [row]
  (when row
    (let [meta (unpack-metadata (:metadata row))]
      (->entry
       {:id           (or (:entry_id row) (:id row))
        :db-id        (:id row)
        :layer        :l2
        :kind         (some-> (:episode_type row) keyword)
        :content      (:content row)
        :role         (:role row)
        :session-id   (:session_id row)
        :user-id      (:user_id row)
        :created-at   (:timestamp row)
        :tags         (unpack-tags (:tags row))
        :sources      (unpack-sources (:sources row))
        :ttl          (:ttl meta)
        :data         (:data meta)
        :metadata     (:metadata meta)
        :keep         (->bool (:keep_flag row))
        :archived     (->bool (:archived_flag row))
        :tombstoned   (->bool (:tombstoned_flag row))}))))

;; =====================================================
;; L3 (semantic_facts) adapters
;; =====================================================

(defn entry->fact-row
  "Translate an entry map into a `semantic_facts` row.

  Maps:
    :kind       → :fact_type
    :user-id    → :user_id
    :content    → :content
    :data :source → :source       (pulled from entry's :source key)
    :confidence → :confidence
    :tags / :sources → JSON columns
    :id         → :entry_id
    flags + metadata as for L2."
  [{:keys [kind user-id content source confidence id
           keep archived tombstoned tags sources]
    :or   {confidence 1.0}
    :as   entry}]
  {:user_id         user-id
   :fact_type       (if (keyword? kind) (name kind) (str kind))
   :content         content
   :source          (when source (str source))
   :confidence      (float confidence)
   :tags            (pack-tags tags)
   :sources         (pack-sources sources)
   :entry_id        id
   :keep_flag       (bool->int keep)
   :archived_flag   (bool->int archived)
   :tombstoned_flag (bool->int tombstoned)
   :metadata        (pack-metadata entry)})

(defn fact-row->entry
  [row]
  (when row
    (let [meta (unpack-metadata (:metadata row))]
      (->entry
       {:id           (or (:entry_id row) (:id row))
        :db-id        (:id row)
        :layer        :l3
        :kind         (some-> (:fact_type row) keyword)
        :content      (:content row)
        :source       (:source row)
        :confidence   (or (:confidence row) 1.0)
        :access-count (or (:access_count row) 0)
        :user-id      (:user_id row)
        :created-at   (:created_at row)
        :tags         (unpack-tags (:tags row))
        :sources      (unpack-sources (:sources row))
        :ttl          (:ttl meta)
        :data         (:data meta)
        :metadata     (:metadata meta)
        :keep         (->bool (:keep_flag row))
        :archived     (->bool (:archived_flag row))
        :tombstoned   (->bool (:tombstoned_flag row))}))))

;; =====================================================
;; Generic dispatch
;; =====================================================

(defn entry->row
  "Dispatch entry-to-row translation by layer. Layer :l1 does not have
  rows — the L1 store stores entries directly."
  [layer entry]
  (case (keyword layer)
    :l2 (entry->episode-row entry)
    :l3 (entry->fact-row entry)
    (throw (ex-info "entry->row called for non-SQL layer"
                    {:layer layer}))))

(defn row->entry
  "Dispatch row-to-entry translation by layer."
  [layer row]
  (case (keyword layer)
    :l2 (episode-row->entry row)
    :l3 (fact-row->entry row)
    (throw (ex-info "row->entry called for non-SQL layer"
                    {:layer layer}))))
