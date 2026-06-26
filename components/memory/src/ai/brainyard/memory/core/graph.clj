;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.graph
  "Context-graph overlay storage (CR-MEM-20).

  Plain SQL functions over a per-user datasource, keyed on `user-id`.
  These back the `GraphStore` protocol methods that `UnifiedStore`
  delegates to (see unified_store.clj). Phase 0: storage + manual edge
  API only — no LLM extraction (CR-MEM-22), no vector index (CR-MEM-21).

  Bi-temporal model: edges carry `t_valid`/`t_invalid`; reads default to
  valid edges (`t_invalid IS NULL`). Supersession invalidates, never
  deletes. Multi-hop `expand` is a bounded `WITH RECURSIVE` (depth <= 3) —
  sufficient for agent-memory recall, not OLAP traversal."
  (:require [next.jdbc :as jdbc]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ai.brainyard.memory.core.embed :as embed]
            [ai.brainyard.memory.core.entry :as entry]
            [ai.brainyard.mulog.interface :as mulog]))

;; =====================================================
;; Row <-> entity coercion
;; =====================================================

(defn- denamespace
  "Flatten next.jdbc's namespaced result keys (:graph_nodes/id) to plain
  keyword keys (:id)."
  [row]
  (when row
    (into {} (map (fn [[k v]]
                    [(if (and (keyword? k) (namespace k)) (keyword (name k)) k)
                     v]))
          row)))

(defn- ->json
  "Serialize a Clojure collection to a JSON string, or nil."
  [coll]
  (when (seq coll) (json/write-str coll)))

(defn- json->
  "Parse a JSON column back to a Clojure value, tolerating nil/blank."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (json/read-str s) (catch Exception _ nil))))

(defn- row->node
  [row]
  (let [m (denamespace row)]
    (when m
      (-> m
          (update :node_type keyword)
          (assoc :node-type (keyword (:node_type m))
                 :aliases   (vec (or (json-> (:aliases m)) []))
                 :metadata  (or (json-> (:metadata m)) {}))))))

(defn- row->edge
  [row]
  (let [m (denamespace row)]
    (when m
      (assoc m
             :relation (keyword (:relation m))
             :source-entry-ids (vec (or (json-> (:source_entry_ids m)) []))))))

;; =====================================================
;; Nodes
;; =====================================================

(defn find-node
  "Resolve a node by exact (node-type, name) or alias match. A nil
  `node-type` searches across all types. Returns the node map or nil."
  [ds user-id node-type name]
  (let [nt   (when node-type (clojure.core/name node-type))
        ;; Match canonical name OR any alias (json_each over the aliases
        ;; JSON array). node_type is an optional narrowing filter.
        sql  (str "SELECT * FROM graph_nodes
                   WHERE user_id = ?
                     AND (name = ?
                          OR EXISTS (SELECT 1 FROM json_each(graph_nodes.aliases)
                                     WHERE json_each.value = ?))"
                  (when nt " AND node_type = ?")
                  " LIMIT 1")
        args (cond-> [sql user-id name name] nt (conj nt))]
    (row->node (jdbc/execute-one! ds args))))

(defn upsert-node
  "Insert or merge a node, resolved by (user-id, node-type, name). On
  conflict, merges summary (new wins when non-nil) and unions aliases.
  Returns the persisted node map with :id."
  [ds user-id {:keys [node-type name summary aliases metadata]}]
  (when-not name
    (throw (ex-info "upsert-node requires :name" {:node-type node-type})))
  (let [nt       (clojure.core/name (or node-type :entity))
        existing (row->node
                  (jdbc/execute-one!
                   ds ["SELECT * FROM graph_nodes
                        WHERE user_id = ? AND node_type = ? AND name = ?"
                       user-id nt name]))]
    (if existing
      (let [merged-aliases (vec (distinct (concat (:aliases existing) aliases)))
            merged-summary (or summary (:summary existing))
            merged-meta    (merge (:metadata existing) metadata)]
        (jdbc/execute-one!
         ds ["UPDATE graph_nodes
              SET summary = ?, aliases = ?, metadata = ?, updated_at = CURRENT_TIMESTAMP
              WHERE id = ?"
             merged-summary (->json merged-aliases) (->json merged-meta) (:id existing)])
        (assoc existing :summary merged-summary
               :aliases merged-aliases :metadata merged-meta))
      (let [r (jdbc/execute-one!
               ds ["INSERT INTO graph_nodes (user_id, node_type, name, summary, aliases, metadata)
                    VALUES (?, ?, ?, ?, ?, ?)"
                   user-id nt name summary (->json aliases) (->json metadata)]
               {:return-keys true})
            new-id (or (:graph_nodes/id r) (:last_insert_rowid r)
                       (val (first r)))]
        (mulog/debug ::graph-node-created :name name :node-type nt)
        (row->node (jdbc/execute-one! ds ["SELECT * FROM graph_nodes WHERE id = ?" new-id]))))))

;; =====================================================
;; Edges
;; =====================================================

(defn upsert-edge
  "Insert a typed, temporally-scoped edge. Idempotent on
  (user-id, src, dst, relation, t-valid). Returns the persisted edge."
  [ds user-id {:keys [src-id dst-id relation fact confidence t-valid source-entry-ids]}]
  (when-not (and src-id dst-id relation)
    (throw (ex-info "upsert-edge requires :src-id :dst-id :relation"
                    {:src-id src-id :dst-id dst-id :relation relation})))
  (let [rel  (clojure.core/name relation)
        conf (or confidence 0.85)
        sids (->json source-entry-ids)
        ;; ON CONFLICT keeps the edge idempotent and refreshes fact/conf/
        ;; provenance. t_valid is part of the conflict key; when omitted
        ;; the DB default (now) is used, so re-asserting "now" makes a new
        ;; row only if the clock ticked — acceptable for Phase 0.
        r (if t-valid
            (jdbc/execute-one!
             ds ["INSERT INTO graph_edges
                  (user_id, src_id, dst_id, relation, fact, confidence, t_valid, source_entry_ids)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                  ON CONFLICT(user_id, src_id, dst_id, relation, t_valid)
                  DO UPDATE SET fact = excluded.fact,
                                confidence = excluded.confidence,
                                source_entry_ids = excluded.source_entry_ids,
                                t_invalid = NULL"
                 user-id src-id dst-id rel fact conf t-valid sids]
             {:return-keys true})
            (jdbc/execute-one!
             ds ["INSERT INTO graph_edges
                  (user_id, src_id, dst_id, relation, fact, confidence, source_entry_ids)
                  VALUES (?, ?, ?, ?, ?, ?, ?)"
                 user-id src-id dst-id rel fact conf sids]
             {:return-keys true}))
        new-id (or (:graph_edges/id r) (:last_insert_rowid r) (val (first r)))]
    (mulog/debug ::graph-edge-upserted :src src-id :dst dst-id :relation rel)
    (row->edge (jdbc/execute-one! ds ["SELECT * FROM graph_edges WHERE id = ?" new-id]))))

(defn invalidate-edge
  "Set `t_invalid` on an edge (bi-temporal supersession). Returns true
  when a row was affected (and was not already invalidated)."
  [ds user-id edge-id t-invalid]
  (let [r (if t-invalid
            (jdbc/execute-one!
             ds ["UPDATE graph_edges SET t_invalid = ?
                  WHERE id = ? AND user_id = ? AND t_invalid IS NULL"
                 t-invalid edge-id user-id])
            (jdbc/execute-one!
             ds ["UPDATE graph_edges SET t_invalid = CURRENT_TIMESTAMP
                  WHERE id = ? AND user_id = ? AND t_invalid IS NULL"
                 edge-id user-id]))]
    (pos? (or (:next.jdbc/update-count r) 0))))

;; =====================================================
;; Traversal
;; =====================================================

(defn neighbors
  "1-hop neighbors of `node-id` over valid edges (t_invalid IS NULL).
  Returns a vector of {:edge <edge-map> :node <neighbor-node-map>}."
  [ds user-id node-id {:keys [direction relation limit]
                       :or   {direction :both limit 50}}]
  (let [dir-clause (case direction
                     :out "e.src_id = ?"
                     :in  "e.dst_id = ?"
                     "(e.src_id = ? OR e.dst_id = ?)")
        rel-clause (when relation " AND e.relation = ?")
        ;; The neighbor node is whichever endpoint is not `node-id`.
        sql (str "SELECT e.*, "
                 "       n.id AS n_id, n.node_type AS n_node_type, n.name AS n_name, "
                 "       n.summary AS n_summary, n.aliases AS n_aliases, n.metadata AS n_metadata "
                 "FROM graph_edges e "
                 "JOIN graph_nodes n ON n.id = (CASE WHEN e.src_id = ? THEN e.dst_id ELSE e.src_id END) "
                 "WHERE e.user_id = ? AND e.t_invalid IS NULL AND " dir-clause
                 rel-clause
                 " LIMIT ?")
        args (cond-> [sql node-id user-id]
               (= direction :both) (conj node-id node-id)
               (not= direction :both) (conj node-id)
               relation (conj (clojure.core/name relation))
               :always (conj limit))
        rows (jdbc/execute! ds args)]
    (mapv (fn [row]
            (let [m (denamespace row)]
              {:edge (row->edge (dissoc m :n_id :n_node_type :n_name
                                        :n_summary :n_aliases :n_metadata))
               :node (row->node {:id (:n_id m) :node_type (:n_node_type m)
                                 :name (:n_name m) :summary (:n_summary m)
                                 :aliases (:n_aliases m) :metadata (:n_metadata m)})}))
          rows)))

(defn expand
  "Bounded multi-hop expansion from `seed-ids` over valid edges. Returns a
  vector of {:node <node-map> :depth <int>} for nodes reachable within
  `:max-hops` (excluding the seeds), each at its minimum depth."
  [ds user-id seed-ids {:keys [max-hops direction limit]
                        :or   {max-hops 2 direction :both limit 50}}]
  (let [hops  (min (max 1 max-hops) 3)
        seeds (vec (distinct seed-ids))]
    (if (empty? seeds)
      []
      (let [step (case direction
                   :out "e.src_id = w.node_id"
                   :in  "e.dst_id = w.node_id"
                   "(e.src_id = w.node_id OR e.dst_id = w.node_id)")
            next-id "CASE WHEN e.src_id = w.node_id THEN e.dst_id ELSE e.src_id END"
            seeds-json (json/write-str seeds)
            ;; Recursive walk over node ids tracking depth; MIN(depth) per
            ;; node collapses multiple paths. Seeds are depth 0 and excluded.
            sql (str "WITH RECURSIVE walk(node_id, depth) AS ("
                     "  SELECT CAST(value AS INTEGER), 0 FROM json_each(?) "
                     "  UNION "
                     "  SELECT " next-id ", w.depth + 1 "
                     "  FROM walk w "
                     "  JOIN graph_edges e ON " step " "
                     "  WHERE e.user_id = ? AND e.t_invalid IS NULL AND w.depth < ? "
                     ") "
                     ;; Exclude the seeds themselves: with :both direction a
                     ;; seed can be re-reached via a back-edge at depth >= 2.
                     "SELECT n.*, MIN(w.depth) AS depth "
                     "FROM walk w JOIN graph_nodes n ON n.id = w.node_id "
                     "WHERE w.depth > 0 "
                     "  AND n.id NOT IN (SELECT CAST(value AS INTEGER) FROM json_each(?)) "
                     "GROUP BY n.id "
                     "ORDER BY depth ASC, n.id ASC "
                     "LIMIT ?")
            rows (jdbc/execute! ds [sql seeds-json user-id hops seeds-json limit])]
        (mapv (fn [row]
                (let [m (denamespace row)]
                  {:node (row->node (dissoc m :depth)) :depth (:depth m)}))
              rows)))))

;; =====================================================
;; Vector index (CR-MEM-21, sqlite-vec graph_vec)
;; =====================================================

(defn vec-available?
  "True when the `graph_vec` virtual table exists (i.e. sqlite-vec loaded)."
  [ds]
  (try
    (boolean (jdbc/execute-one!
              ds ["SELECT 1 FROM sqlite_master WHERE type='table' AND name='graph_vec'"]))
    (catch Exception _ false)))

(defn upsert-fact-embedding!
  "Store (replace) the embedding for an L3 fact row in `graph_vec`. vec0 has
  no UPDATE on auxiliary columns, so we delete-then-insert by ref. No-op
  when the table is absent."
  [ds row-id embedding]
  (when (and row-id (seq embedding) (vec-available? ds))
    (try
      (jdbc/execute! ds ["DELETE FROM graph_vec WHERE ref_kind = 'fact' AND ref_id = ?" row-id])
      (jdbc/execute! ds ["INSERT INTO graph_vec(embedding, ref_kind, ref_id) VALUES (?, 'fact', ?)"
                         (embed/->vec0-json embedding) row-id])
      true
      (catch Exception e
        (mulog/warn ::vec-embedding-upsert-failed :row-id row-id :error (ex-message e))
        false))))

(defn- vec-knn
  "kNN over graph_vec for a query embedding. Returns
  [{:ref-kind :ref-id :distance} …] ordered by ascending distance."
  [ds query-embedding {:keys [limit] :or {limit 8}}]
  (try
    (->> (jdbc/execute!
          ds [(str "SELECT ref_kind, ref_id, distance FROM graph_vec "
                   "WHERE embedding MATCH ? AND k = ? ORDER BY distance")
              (embed/->vec0-json query-embedding) limit])
         (mapv (fn [r]
                 (let [m (denamespace r)]
                   {:ref-kind (:ref_kind m) :ref-id (:ref_id m) :distance (:distance m)}))))
    (catch Exception e
      (mulog/warn ::vec-knn-failed :error (ex-message e))
      [])))

(defn- fetch-l3-by-rowids
  "Hydrate L3 fact entries by their SQL row ids, excluding archived /
  tombstoned. Returns entry maps (carrying :db-id)."
  [ds user-id row-ids]
  (when (seq row-ids)
    (let [ph   (str/join "," (repeat (count row-ids) "?"))
          rows (jdbc/execute!
                ds (into [(str "SELECT * FROM semantic_facts "
                               "WHERE user_id = ? AND archived_flag = 0 AND tombstoned_flag = 0 "
                               "AND id IN (" ph ")")
                          user-id]
                         row-ids))]
      (mapv (fn [row]
              (entry/fact-row->entry
               (into {} (map (fn [[k v]]
                               [(if (and (keyword? k) (namespace k)) (keyword (name k)) k) v]))
                     row)))
            rows))))

(defn vec-search
  "Semantic recall over the vector index. Embeds `query` (via `embed-fn`),
  runs kNN over `graph_vec`, hydrates the L3 fact hits, and returns entries
  ordered by similarity with `:_vec_distance` attached. Returns [] when
  vector search is unavailable (no embed-fn, blank query, or no table) —
  the non-regressing fallback."
  [ds user-id embed-fn query {:keys [limit] :as opts}]
  (if (or (nil? embed-fn) (not (string? query)) (str/blank? query) (not (vec-available? ds)))
    []
    (if-let [qe (embed/embed-one embed-fn query)]
      (let [hits     (vec-knn ds qe (or opts {:limit (or limit 8)}))
            fact-ids (->> hits (filter #(= "fact" (:ref-kind %))) (map :ref-id) (remove nil?))
            dist-by  (into {} (map (juxt :ref-id :distance) hits))
            entries  (fetch-l3-by-rowids ds user-id fact-ids)]
        (->> entries
             (map (fn [e] (assoc e :_vec_distance (get dist-by (:db-id e)))))
             (sort-by #(or (:_vec_distance %) Double/MAX_VALUE))
             vec))
      [])))

(defn as-of
  "Like `neighbors`, but returns edges valid at `timestamp`:
  t_valid <= ts AND (t_invalid IS NULL OR t_invalid > ts)."
  [ds user-id node-id timestamp {:keys [direction relation limit]
                                 :or   {direction :both limit 50}}]
  (let [dir-clause (case direction
                     :out "e.src_id = ?"
                     :in  "e.dst_id = ?"
                     "(e.src_id = ? OR e.dst_id = ?)")
        rel-clause (when relation " AND e.relation = ?")
        sql (str "SELECT e.*, "
                 "       n.id AS n_id, n.node_type AS n_node_type, n.name AS n_name, "
                 "       n.summary AS n_summary, n.aliases AS n_aliases, n.metadata AS n_metadata "
                 "FROM graph_edges e "
                 "JOIN graph_nodes n ON n.id = (CASE WHEN e.src_id = ? THEN e.dst_id ELSE e.src_id END) "
                 "WHERE e.user_id = ? "
                 "  AND e.t_valid <= ? AND (e.t_invalid IS NULL OR e.t_invalid > ?) "
                 "  AND " dir-clause
                 rel-clause
                 " LIMIT ?")
        args (cond-> [sql node-id user-id timestamp timestamp]
               (= direction :both) (conj node-id node-id)
               (not= direction :both) (conj node-id)
               relation (conj (clojure.core/name relation))
               :always (conj limit))
        rows (jdbc/execute! ds args)]
    (mapv (fn [row]
            (let [m (denamespace row)]
              {:edge (row->edge (dissoc m :n_id :n_node_type :n_name
                                        :n_summary :n_aliases :n_metadata))
               :node (row->node {:id (:n_id m) :node_type (:n_node_type m)
                                 :name (:n_name m) :summary (:n_summary m)
                                 :aliases (:n_aliases m) :metadata (:n_metadata m)})}))
          rows)))
