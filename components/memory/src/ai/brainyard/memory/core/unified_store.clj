;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.unified-store
  "UnifiedStore composes the L1 in-memory store with SQLite-backed L2
  (episodes) and L3 (semantic_facts) into a single IMemoryStore surface.

  Layer dispatch:
    :l1           → delegated to the embedded L1Store
    :l2           → episodes table via core.episodic + core.entry adapters
    :l3           → semantic_facts table via core.semantic + core.entry adapters

  Cross-store promotion is supported: an entry promoted from :l1 to :l3
  is written to L3 with provenance pointing back to the L1 entry-id."
  (:require [ai.brainyard.memory.interface.protocol :as proto]
            [ai.brainyard.memory.core.entry :as entry]
            [ai.brainyard.memory.core.l1-store :as l1]
            [ai.brainyard.memory.core.episodic :as episodic]
            [ai.brainyard.memory.core.semantic :as semantic]
            [ai.brainyard.memory.core.capture.reducer :as capture-reducer]
            [ai.brainyard.memory.core.graph :as graph]
            [ai.brainyard.memory.core.embed :as embed]
            [ai.brainyard.memory.core.community :as community]
            [ai.brainyard.mulog.interface :as mulog]
            [next.jdbc :as jdbc]))

;; =====================================================
;; Helpers
;; =====================================================

(defn- gen-id [] (str (random-uuid)))

(defn- ensure-id
  "Stamp an :id on the entry if absent."
  [entry]
  (if (:id entry) entry (assoc entry :id (gen-id))))

;; =====================================================
;; L2 (episodes) read paths
;; =====================================================

(defn- l2-read
  "Read entries from the episodes table.

  Supported query keys:
    :text         — FTS5 query string
    :session-id   — restrict to a session
    :user-id      — restrict to a user
    :kind / :episode-type — restrict to one episode type
    :time-after, :time-before — bounds on `timestamp`
    :id           — exact entry_id lookup
    :match        — multi-word match mode for :text — :or (default),
                    :and, :phrase. See fts/normalize-fts-query.

  opts:
    :limit              — default 20
    :include-archived   — default false
    :include-tombstoned — default false"
  [ds user-id query opts]
  (let [{:keys [text id session-id kind episode-type
                time-after time-before match]} query
        {:keys [limit include-archived include-tombstoned]
         :or   {limit 20}} opts
        u-id  (or (:user-id query) user-id)
        types (when-let [k (or kind episode-type)]
                [(if (keyword? k) (name k) (str k))])
        rows
        (cond
          id
          (let [r (jdbc/execute-one!
                   ds ["SELECT * FROM episodes WHERE entry_id = ? AND user_id = ?"
                       id u-id])]
            (when r [r]))

          text
          (episodic/search-fts ds text
                               :session-id session-id
                               :limit limit
                               :episode-types types
                               :time-after time-after
                               :time-before time-before
                               :match (or match :or))

          session-id
          (episodic/get-recent-episodes ds session-id limit)

          :else
          (mapv #(into {} (map (fn [[k v]] [(keyword (name k)) v]) %))
                (jdbc/execute! ds
                               ["SELECT * FROM episodes
                                  WHERE user_id = ?
                                  ORDER BY timestamp DESC
                                  LIMIT ?"
                                u-id limit])))]
    (->> rows
         (map (fn [row]
                ;; Normalize FTS-search rows (already kebab-keyed) and raw
                ;; rows (namespaced by next.jdbc) into a flat keyword map.
                (into {} (map (fn [[k v]]
                                [(if (and (keyword? k) (namespace k))
                                   (keyword (name k))
                                   k)
                                 v]) row))))
         (filter (fn [row]
                   (and (or include-archived
                            (zero? (or (:archived_flag row) 0)))
                        (or include-tombstoned
                            (zero? (or (:tombstoned_flag row) 0))))))
         (mapv entry/episode-row->entry))))

;; =====================================================
;; L3 (semantic_facts) read paths
;; =====================================================

(defn- l3-read
  [ds user-id query opts]
  (let [{:keys [text id kind fact-type min-confidence match]} query
        {:keys [limit include-archived include-tombstoned]
         :or   {limit 20}} opts
        u-id  (or (:user-id query) user-id)
        types (when-let [k (or kind fact-type)]
                [(if (keyword? k) (name k) (str k))])
        rows
        (cond
          id
          (let [r (jdbc/execute-one!
                   ds ["SELECT * FROM semantic_facts WHERE entry_id = ? AND user_id = ?"
                       id u-id])]
            (when r [r]))

          text
          (semantic/search-fts ds text limit
                               :user-id u-id
                               :fact-types types
                               :min-confidence (or min-confidence 0.0)
                               :match (or match :or))

          :else
          (mapv #(into {} (map (fn [[k v]] [(keyword (name k)) v]) %))
                (jdbc/execute! ds
                               ["SELECT * FROM semantic_facts
                                  WHERE user_id = ?
                                  ORDER BY updated_at DESC
                                  LIMIT ?"
                                u-id limit])))]
    (->> rows
         (map (fn [row]
                (into {} (map (fn [[k v]]
                                [(if (and (keyword? k) (namespace k))
                                   (keyword (name k))
                                   k)
                                 v]) row))))
         (filter (fn [row]
                   (and (or include-archived
                            (zero? (or (:archived_flag row) 0)))
                        (or include-tombstoned
                            (zero? (or (:tombstoned_flag row) 0))))))
         (mapv entry/fact-row->entry))))

;; =====================================================
;; SQL forget/tombstone
;; =====================================================

(defn- l2-forget!
  "Mark an L2 entry tombstoned by entry_id. Returns true if a row was
  affected."
  [ds user-id entry-id]
  (let [r (jdbc/execute-one!
           ds ["UPDATE episodes
                SET tombstoned_flag = 1
                WHERE entry_id = ? AND user_id = ?"
               entry-id user-id])]
    (pos? (or (:next.jdbc/update-count r) 0))))

(defn- l3-forget!
  [ds user-id entry-id]
  (let [r (jdbc/execute-one!
           ds ["UPDATE semantic_facts
                SET tombstoned_flag = 1
                WHERE entry_id = ? AND user_id = ?"
               entry-id user-id])]
    (pos? (or (:next.jdbc/update-count r) 0))))

;; =====================================================
;; UnifiedStore record
;; =====================================================

(defrecord UnifiedStore [user-id ds l1-store embed-fn summarize-fn]
  proto/IMemoryStore

  (write-entry [_ layer in]
    (let [layer' (keyword layer)
          in'    (-> in
                     (assoc :layer layer')
                     (update :user-id #(or % user-id))
                     ensure-id)]
      (case layer'
        :l1
        (proto/write-entry l1-store :l1 in')

        :l2
        (let [row    (entry/entry->episode-row in')
              result (episodic/append-episode! ds row)]
          (when result
            (mulog/debug ::l2-write :entry-id (:id in') :db-id (:id result))
            ;; Build the persisted entry view.
            (entry/episode-row->entry (merge row (select-keys result [:id])))))

        :l3
        (let [row    (entry/entry->fact-row in')
              result (semantic/store-fact! ds row)]
          (when result
            (mulog/debug ::l3-write :entry-id (:id in') :db-id (:id result))
            ;; CR-MEM-21: index the fact's content for semantic recall. Best
            ;; effort — embedding/vec failures never block the write.
            (when embed-fn
              (when-let [e (embed/embed-one embed-fn (:content in'))]
                (graph/upsert-fact-embedding! ds (:id result) e)))
            (entry/fact-row->entry (merge row (select-keys result [:id])))))

        (throw (ex-info "UnifiedStore/write-entry: unknown layer"
                        {:layer layer'})))))

  (read-entries [_ layer query opts]
    (let [layer' (keyword layer)]
      (case layer'
        :l1 (proto/read-entries l1-store :l1 query opts)
        :l2 (l2-read ds user-id query opts)
        :l3 (l3-read ds user-id query opts)
        (throw (ex-info "UnifiedStore/read-entries: unknown layer"
                        {:layer layer'})))))

  (promote [this entry from-layer to-layer]
    (let [from-layer' (keyword from-layer)
          to-layer'   (keyword to-layer)
          new-id      (gen-id)
          new-entry   (-> entry
                          (dissoc :db-id)
                          (assoc :id new-id
                                 :layer to-layer'
                                 :sources (conj (vec (:sources entry))
                                                {:type :promotion
                                                 :id (:id entry)
                                                 :from-layer from-layer'})))]
      (proto/write-entry this to-layer' new-entry)))

  (forget [_ layer entry-id]
    (let [layer' (keyword layer)]
      (case layer'
        :l1 (proto/forget l1-store :l1 entry-id)
        :l2 (l2-forget! ds user-id entry-id)
        :l3 (l3-forget! ds user-id entry-id)
        (throw (ex-info "UnifiedStore/forget: unknown layer"
                        {:layer layer'})))))

  (consolidate-layer [this from-layer policy]
    ;; Dispatch by from-layer. :l2 is the only path that actually
    ;; reduces (episodes → semantic facts via the heuristic reducer);
    ;; :l1 is a no-op; anything else is a contract error.
    (let [from-layer' (keyword from-layer)]
      (case from-layer'
        :l2
        ;; CR-MEM-24: `:reducer :community` consolidates by graph community
        ;; (the GraphRAG tier, replacing the heuristic time-bucket reducer);
        ;; any other reducer keeps the heuristic path.
        (if (= :community (:reducer policy))
          (apply community/consolidate! this summarize-fn
                 (mapcat identity (dissoc policy :reducer)))
          (apply capture-reducer/reduce-l2! this
                 :user-id user-id
                 :ds      ds
                 (mapcat identity (or policy {}))))

        :l1
        {:from-layer :l1 :to-layer nil
         :produced 0 :consumed 0 :auto-kept 0 :batches []}

        (throw (ex-info "UnifiedStore/consolidate-layer: unknown from-layer"
                        {:from-layer from-layer' :policy policy})))))

  proto/GraphStore
  ;; The context-graph overlay (CR-MEM-20) shares the store's :ds + :user-id.
  ;; All logic lives in core.graph; these methods are thin delegations.
  (upsert-node     [_ node]              (graph/upsert-node ds user-id node))
  (find-node       [_ node-type name]    (graph/find-node ds user-id node-type name))
  (upsert-edge     [_ edge]              (graph/upsert-edge ds user-id edge))
  (invalidate-edge [_ edge-id t-invalid] (graph/invalidate-edge ds user-id edge-id t-invalid))
  (neighbors       [_ node-id opts]      (graph/neighbors ds user-id node-id opts))
  (expand          [_ seed-ids opts]     (graph/expand ds user-id seed-ids opts))
  (as-of           [_ node-id ts opts]   (graph/as-of ds user-id node-id ts opts))
  (vec-search      [_ query opts]        (graph/vec-search ds user-id embed-fn query opts))
  (related         [_ keywords opts]     (graph/related ds user-id keywords opts)))

;; =====================================================
;; Factory
;; =====================================================

(defn create-unified-store
  "Build a UnifiedStore.

  Required:
    :user-id    string identifying the user partition
    :ds         next.jdbc datasource (or in-memory connection)

  Optional:
    :l1-store     pre-built L1Store (default: fresh)
    :embed-fn     (fn [texts] -> [[float…] …]) for the CR-MEM-21 vector index;
                  nil (default) disables semantic recall (FTS-only)
    :summarize-fn (fn [text] -> string) for CR-MEM-24 community summaries;
                  nil (default) uses the deterministic templated summary."
  [& {:keys [user-id ds l1-store embed-fn summarize-fn]
      :as opts}]
  (when-not user-id (throw (ex-info "UnifiedStore requires :user-id" opts)))
  (when-not ds      (throw (ex-info "UnifiedStore requires :ds" opts)))
  (->UnifiedStore user-id ds (or l1-store (l1/create-l1-store)) embed-fn summarize-fn))
