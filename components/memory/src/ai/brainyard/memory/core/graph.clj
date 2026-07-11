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
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.mulog.interface :as mulog]))

;; =====================================================
;; Row <-> entity coercion
;; =====================================================

(defn- val1
  "First non-nil value among result keys (handles next.jdbc namespacing)."
  [row & ks]
  (some #(get row %) ks))

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
;; Node budget / eviction (total-size cap)
;; =====================================================

;; `vec-available?` is defined below in the Vector-index section; forward-declare
;; it so `prune-nodes-to-budget!` (which deletes evicted nodes' graph_vec rows)
;; compiles on a clean top-to-bottom load.
(declare vec-available?)
;;
;; Per-episode caps (extract/default-graph-limits) bound the growth RATE; this
;; bounds the TOTAL. When a user's node count exceeds the budget we hard-delete
;; the lowest-retention nodes down to a low-water mark (hysteresis, so eviction
;; runs in batches rather than every episode).
;;
;; Classification — a retention SCORE per node (kept high → evicted low), all
;; SQL-computable, then deterministic tiebreaks:
;;   SCORE = weighted-degree + type-bonus
;;     • WEIGHTED-DEGREE — SUM over valid edges of the relation weight, NOT a raw
;;       count: curated relations count 1.0, the generic `mentions` catch-all
;;       (extractor fallback / auto-created endpoints) only `mention-degree-
;;       weight` (0.25). So a node held up by many weak `mentions` edges no
;;       longer outranks a sparsely-but-strongly-connected one.
;;     • TYPE-BONUS (`type-retention-bonus`) — durable CONCEPTUAL types
;;       (concept/config-key/person = 3) and architectural units (component = 2)
;;       get a bonus; `file` and the generic `entity` fallback get 0. Files are
;;       specific, re-searchable artifacts, so the graph doesn't spend budget on
;;       them — a genuinely central file still survives on weighted degree, but a
;;       file kept alive only by weak edges is evicted first.
;;   Tiebreaks (equal score): SUMMARY (has-summary richer) → RECENCY (updated_at,
;;   stalest first) → ID (ascending; deterministic total order so a same-second
;;   batch write doesn't leave ties to SQLite's unspecified order).
;; Victims are the lowest-scoring nodes.

(def ^:private relation-degree-weight
  "Per-relation contribution to a node's WEIGHTED degree (default 1.0 for any
  relation not listed). `mentions` is the extractor's generic catch-all and the
  relation auto-created for unlisted edge endpoints, so it inflates degree
  cheaply — count it low so a node kept alive only by weak `mentions` becomes
  evictable."
  {"mentions" 0.25})

(def ^:private type-retention-bonus
  "Additive retention weight per node type, folded into the eviction score
  alongside weighted degree (default 0.0 for any type not listed). Durable
  CONCEPTUAL knowledge (concept/config-key/person) and architectural units
  (component) get a bonus; `file` and the generic `entity` fallback get NONE —
  files are specific, re-searchable artifacts (found again via search/explore
  on demand), so the graph should not spend budget on them. A genuinely central
  file still survives on weighted degree; a file kept alive only by weak edges
  is evicted first."
  {"concept" 3.0 "config-key" 3.0 "person" 3.0
   "component" 2.0})

(defn- sql-case
  "Render a SQL `CASE <col> WHEN 'k' THEN v … ELSE <default> END` from a
  string-keyed number map. Values are inlined (trusted, code-local constants —
  never user input)."
  [col m default]
  (str "CASE " col " "
       (apply str (map (fn [[k v]] (str "WHEN '" k "' THEN " v " ")) m))
       "ELSE " default " END"))

(defn count-nodes
  "Total node count for `user-id`."
  [ds user-id]
  (long (or (val1 (jdbc/execute-one! ds ["SELECT COUNT(*) AS c FROM graph_nodes WHERE user_id = ?" user-id])
                  :c :graph_nodes/c)
            0)))

(defn count-edges
  "Total valid (non-invalidated) edge count for `user-id`."
  [ds user-id]
  (long (or (val1 (jdbc/execute-one!
                   ds ["SELECT COUNT(*) AS c FROM graph_edges
                        WHERE user_id = ? AND t_invalid IS NULL" user-id])
                  :c :graph_edges/c)
            0)))

(defn all-nodes
  "Every node for `user-id`, newest-first, capped at `limit` (default 1000).
  A full-graph dump for visualisation/export — unlike `search-nodes`/`neighbors`
  it is unscoped. Each row is the coerced node map (`:id :node-type :name
  :summary :aliases :metadata`)."
  ([ds user-id] (all-nodes ds user-id 1000))
  ([ds user-id limit]
   (mapv row->node
         (jdbc/execute! ds ["SELECT * FROM graph_nodes WHERE user_id = ?
                             ORDER BY updated_at DESC, id DESC LIMIT ?"
                            user-id (long (or limit 1000))]))))

(defn all-edges
  "Every valid (non-invalidated) edge for `user-id`, capped at `limit` (default
  2000). Companion to `all-nodes` for a full-graph dump; each row is the coerced
  edge map (`:id :src_id :dst_id :relation :fact :confidence …`)."
  ([ds user-id] (all-edges ds user-id 2000))
  ([ds user-id limit]
   (mapv row->edge
         (jdbc/execute! ds ["SELECT * FROM graph_edges
                             WHERE user_id = ? AND t_invalid IS NULL
                             ORDER BY id DESC LIMIT ?"
                            user-id (long (or limit 2000))]))))

(defn prune-nodes-to-budget!
  "Enforce a total-node budget for `user-id`. No-op unless `max-nodes` is a
  positive int AND the count exceeds it. Over budget, hard-delete the
  lowest-retention nodes (and their edges + `graph_vec` rows) down to
  `(* max-nodes low-water-ratio)` (default 0.9). Returns the count evicted."
  [ds user-id {:keys [max-nodes low-water-ratio] :or {low-water-ratio 0.9}}]
  (if-not (and (integer? max-nodes) (pos? max-nodes))
    0
    (let [total (count-nodes ds user-id)]
      (if (<= total max-nodes)
        0
        (let [target  (long (Math/floor (* max-nodes (double low-water-ratio))))
              n-evict (max 0 (- total target))
              ;; Rank ascending by retention score → the first n-evict are the
              ;; victims. Score = weighted-degree + type-bonus (see comment above).
              rel-w   (sql-case "relation" relation-degree-weight 1.0)
              type-b  (sql-case "n.node_type" type-retention-bonus 0.0)
              victims (mapv #(or (:id %) (:graph_nodes/id %))
                            (jdbc/execute!
                             ds [(str "SELECT n.id AS id FROM graph_nodes n "
                                      "LEFT JOIN (SELECT node, SUM(w) AS wdeg FROM ("
                                      "  SELECT src_id AS node, " rel-w " AS w FROM graph_edges WHERE user_id = ? AND t_invalid IS NULL "
                                      "  UNION ALL "
                                      "  SELECT dst_id AS node, " rel-w " AS w FROM graph_edges WHERE user_id = ? AND t_invalid IS NULL) "
                                      "  GROUP BY node) d ON d.node = n.id "
                                      "WHERE n.user_id = ? "
                                      "ORDER BY (COALESCE(d.wdeg, 0) + " type-b ") ASC, " ;; retention score (weighted degree + type bonus)
                                      "         (n.summary IS NULL OR n.summary = '') DESC, " ;; no-summary first
                                      "         n.updated_at ASC, "                 ;; stalest first
                                      "         n.id ASC "                          ;; deterministic tiebreak (oldest-inserted first)
                                      "LIMIT ?")
                                 user-id user-id user-id n-evict]))]
          (when (seq victims)
            (let [ph (str/join "," (repeat (count victims) "?"))]
              (jdbc/execute-one! ds (into [(str "DELETE FROM graph_edges WHERE user_id = ? "
                                                "AND (src_id IN (" ph ") OR dst_id IN (" ph "))")]
                                          (concat [user-id] victims victims)))
              (when (vec-available? ds)
                (try (jdbc/execute-one! ds (into [(str "DELETE FROM graph_vec WHERE ref_kind = 'node' "
                                                       "AND ref_id IN (" ph ")")] victims))
                     (catch Exception _)))
              (jdbc/execute-one! ds (into [(str "DELETE FROM graph_nodes WHERE user_id = ? "
                                                "AND id IN (" ph ")")]
                                          (concat [user-id] victims)))))
          (mulog/info ::graph-nodes-evicted
                      :evicted (count victims) :total-before total :budget max-nodes)
          (count victims))))))

(defn prune-orphan-nodes!
  "Hard-delete every ORPHAN node for `user-id` — a node with NO edge row at all
  (valid OR invalidated) referencing it as `src_id`/`dst_id` — plus its
  `graph_vec` rows. Keeps the graph edge-connected at build time by dropping
  extracted entities the model never wired into a relation, and nodes left
  edgeless by budget eviction (which hard-deletes edge rows).

  Definition is `no edge row`, NOT `no VALID edge` (unlike `degree` in
  `prune-nodes-to-budget!`): a node whose only edge was superseded still has an
  invalidated (`t_invalid` set) row and is RETAINED, preserving the
  supersession invariant (`invalidate` never deletes the node) and its as-of
  history. Distinct from `prune-nodes-to-budget!`, which only fires over the
  size cap and merely ranks orphans first; this removes edgeless nodes
  unconditionally. Orphans have no edges, so no `graph_edges` cleanup is needed.
  Returns the count deleted."
  [ds user-id]
  (let [victims (mapv #(or (:id %) (:graph_nodes/id %))
                      (jdbc/execute!
                       ds ["SELECT n.id AS id FROM graph_nodes n
                            WHERE n.user_id = ?
                              AND NOT EXISTS (
                                SELECT 1 FROM graph_edges e
                                WHERE e.src_id = n.id OR e.dst_id = n.id)"
                           user-id]))]
    (when (seq victims)
      (let [ph (str/join "," (repeat (count victims) "?"))]
        (when (vec-available? ds)
          (try (jdbc/execute-one! ds (into [(str "DELETE FROM graph_vec WHERE ref_kind = 'node' "
                                                 "AND ref_id IN (" ph ")")] victims))
               (catch Exception _)))
        (jdbc/execute-one! ds (into [(str "DELETE FROM graph_nodes WHERE user_id = ? "
                                          "AND id IN (" ph ")")]
                                    (concat [user-id] victims))))
      (mulog/info ::graph-orphan-nodes-pruned :pruned (count victims)))
    (count victims)))

;; Edge budget / eviction — the total-edge counterpart to
;; `prune-nodes-to-budget!`. Per-episode caps (`:max-relations`) bound the
;; growth RATE; this bounds the TOTAL valid-edge count. Retention ranking
;; (kept high → evicted low), all SQL-computable from `graph_edges`:
;;   1. CONFIDENCE — a high-confidence relation is worth more than a weak one;
;;      low/NULL-confidence edges go first.
;;   2. RECENCY (ingested_at, then id) — stalest evicted first.
;; Only valid (non-invalidated) edges are counted and evicted; invalidated
;; edges are already tombstoned and excluded from recall.
(defn prune-edges-to-budget!
  "Enforce a total valid-edge budget for `user-id`. No-op unless `max-edges` is a
  positive int AND the valid-edge count exceeds it. Over budget, hard-delete the
  lowest-retention edges (lowest confidence, then stalest) down to
  `(* max-edges low-water-ratio)` (default 0.9). Returns the count evicted."
  [ds user-id {:keys [max-edges low-water-ratio] :or {low-water-ratio 0.9}}]
  (if-not (and (integer? max-edges) (pos? max-edges))
    0
    (let [total (count-edges ds user-id)]
      (if (<= total max-edges)
        0
        (let [target  (long (Math/floor (* max-edges (double low-water-ratio))))
              n-evict (max 0 (- total target))
              ;; Rank ascending by retention → the first n-evict are the victims.
              victims (mapv #(or (:id %) (:graph_edges/id %))
                            (jdbc/execute!
                             ds [(str "SELECT id FROM graph_edges "
                                      "WHERE user_id = ? AND t_invalid IS NULL "
                                      "ORDER BY COALESCE(confidence, 0) ASC, " ;; weakest first
                                      "         ingested_at ASC, id ASC "       ;; stalest first
                                      "LIMIT ?")
                                 user-id n-evict]))]
          (when (seq victims)
            (let [ph (str/join "," (repeat (count victims) "?"))]
              (jdbc/execute-one! ds (into [(str "DELETE FROM graph_edges WHERE user_id = ? "
                                                "AND id IN (" ph ")")]
                                          (concat [user-id] victims)))))
          (mulog/info ::graph-edges-evicted
                      :evicted (count victims) :total-before total :budget max-edges)
          (count victims))))))

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

(defn upsert-embedding!
  "Store (replace) the embedding for a `ref-kind` ('fact' | 'node') row in
  `graph_vec`. vec0 has no UPDATE on auxiliary columns, so we delete-then-
  insert by (kind, ref). No-op when the table is absent."
  [ds ref-kind row-id embedding]
  (when (and row-id (seq embedding) (vec-available? ds))
    (try
      (jdbc/execute! ds ["DELETE FROM graph_vec WHERE ref_kind = ? AND ref_id = ?" ref-kind row-id])
      (jdbc/execute! ds ["INSERT INTO graph_vec(embedding, ref_kind, ref_id) VALUES (?, ?, ?)"
                         (embed/->vec0-json embedding) ref-kind row-id])
      true
      (catch Exception e
        (mulog/warn ::vec-embedding-upsert-failed :ref-kind ref-kind :row-id row-id :error (ex-message e))
        false))))

(defn upsert-fact-embedding!
  "Index an L3 fact row's embedding (ref_kind='fact')."
  [ds row-id embedding]
  (upsert-embedding! ds "fact" row-id embedding))

(defn upsert-node-embedding!
  "Index a graph node's summary embedding (ref_kind='node')."
  [ds row-id embedding]
  (upsert-embedding! ds "node" row-id embedding))

(defn prune-orphan-vec!
  "Delete `graph_vec` embeddings whose `ref_id` no longer joins a live row —
  `ref_kind='node'` with no `graph_nodes` row, or `ref_kind='fact'` with no
  `semantic_facts` row. There is NO DB trigger for this (and there can't be —
  vec0 needs the extension loaded), and only node eviction cleans up inline;
  any other hard delete (L3-fact dedup, retention/cascade cleanup) orphans its
  vector. This is the catch-all sweep, run at consolidation. No-op when the vec
  table is absent. Returns the count deleted."
  [ds]
  (if-not (vec-available? ds)
    0
    (let [cnt    #(long (or (val1 (jdbc/execute-one! ds ["SELECT COUNT(*) AS c FROM graph_vec"])
                                  :c :graph_vec/c) 0))
          before (cnt)]
      (try
        (jdbc/execute! ds ["DELETE FROM graph_vec WHERE ref_kind = 'node'
                            AND ref_id NOT IN (SELECT id FROM graph_nodes)"])
        (jdbc/execute! ds ["DELETE FROM graph_vec WHERE ref_kind = 'fact'
                            AND ref_id NOT IN (SELECT id FROM semantic_facts)"])
        (catch Exception e
          (mulog/warn ::graph-vec-orphan-prune-failed :error (ex-message e))))
      (let [deleted (- before (cnt))]
        (when (pos? deleted) (mulog/info ::graph-vec-orphans-pruned :pruned deleted))
        deleted))))

;; =====================================================
;; Embed-model fingerprint + rebuild (CR-MEM-21)
;; =====================================================
;;
;; The vectors in graph_vec are only comparable when produced by ONE embedder.
;; Switching models — even at the same dimension — silently corrupts kNN
;; rankings, and a different dimension breaks inserts outright. We fingerprint
;; the embedder that built the index (`<model-id>|<dims>` in memory_metadata)
;; and, on a mismatch, pause the vector signal until the user rebuilds.

(def ^:private vec-model-key "graph_vec_model")

(defn- fingerprint [model-id dims] (when model-id (str model-id "|" dims)))

(defn vec-row-count [ds]
  (if (vec-available? ds)
    (try (long (or (val1 (jdbc/execute-one! ds ["SELECT COUNT(*) AS c FROM graph_vec"]) :c :graph_vec/c) 0))
         (catch Exception _ 0))
    0))

(defn reconcile-vec-model!
  "Compare the configured embedder against the one that built the current
  `graph_vec`. Returns `{:stale? bool :was fp :now fp :count n}`. Stamps the
  fingerprint when the index is fresh or already consistent; does NOT stamp
  when stale — the rebuild does, so the staleness persists until resolved."
  [ds model-id dims]
  (let [now (fingerprint model-id dims)
        was (sqlite/get-metadata ds vec-model-key)
        cnt (vec-row-count ds)]
    (cond
      (nil? now)  {:stale? false}                       ; no embedder configured
      (= was now) {:stale? false :now now :count cnt}
      (or (nil? was) (zero? cnt))                        ; fresh / nothing embedded yet
      (do (sqlite/set-metadata! ds vec-model-key now) {:stale? false :now now :count cnt})
      :else
      (do (mulog/warn ::graph-vec-stale :was was :now now :count cnt)
          {:stale? true :was was :now now :count cnt}))))

(defn vec-status
  "Read-only fingerprint status (no side effects):
  `{:was <stored-fp> :now <configured-fp> :count n}`."
  [ds model-id dims]
  {:was   (sqlite/get-metadata ds vec-model-key)
   :now   (fingerprint model-id dims)
   :count (vec-row-count ds)})

(defn reembed!
  "Rebuild `graph_vec` for the store's current embedder: recreate at its dim,
  re-embed every L3 fact and node summary, and re-stamp the fingerprint. The
  CR-MEM-21 fix for a changed embedding model. Returns `{:facts n :nodes n}`;
  no-op (nil) when no embed-fn is configured."
  [{:keys [ds user-id embed-fn embed-dims embed-model-id] :as _store}]
  (when embed-fn
    (let [dims (or embed-dims (sqlite/graph-embed-dims))]
      (sqlite/recreate-graph-vec! ds dims)
      (let [facts (jdbc/execute! ds ["SELECT id, content FROM semantic_facts
                                      WHERE user_id = ? AND tombstoned_flag = 0" user-id])
            fc (reduce (fn [n r]
                         (let [id (val1 r :id :semantic_facts/id)
                               c  (val1 r :content :semantic_facts/content)]
                           (if-let [v (embed/embed-one embed-fn c)]
                             (do (upsert-fact-embedding! ds id v) (inc n)) n)))
                       0 facts)
            nodes (jdbc/execute! ds ["SELECT id, name, summary FROM graph_nodes
                                      WHERE user_id = ? AND summary IS NOT NULL" user-id])
            nc (reduce (fn [n r]
                         (let [id (val1 r :id :graph_nodes/id)
                               nm (val1 r :name :graph_nodes/name)
                               sm (val1 r :summary :graph_nodes/summary)]
                           (if-let [v (embed/embed-one embed-fn (str nm ": " sm))]
                             (do (upsert-node-embedding! ds id v) (inc n)) n)))
                       0 nodes)]
        (when-let [fp (fingerprint embed-model-id dims)]
          (sqlite/set-metadata! ds vec-model-key fp))
        (mulog/info ::graph-vec-reembedded :facts fc :nodes nc)
        {:facts fc :nodes nc}))))

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

;; =====================================================
;; Relational recall (CR-MEM-23)
;; =====================================================

(defn search-nodes
  "Resolve candidate seed nodes by matching query `keywords` against node
  name / summary / aliases (case-insensitive substring). Returns node maps.
  This is the entry point for relational recall — cheap, deterministic, and
  independent of the embedding provider."
  [ds user-id keywords {:keys [limit] :or {limit 8}}]
  (let [kws (->> keywords (map str) (remove str/blank?) distinct (take 8) vec)]
    (when (seq kws)
      (let [clause (str/join " OR "
                             (mapcat (constantly
                                      ["lower(name) LIKE ?"
                                       "lower(IFNULL(summary,'')) LIKE ?"
                                       "lower(IFNULL(aliases,'')) LIKE ?"])
                                     kws))
            params (mapcat (fn [kw] (let [p (str "%" (str/lower-case kw) "%")]
                                      [p p p]))
                           kws)
            sql    (str "SELECT * FROM graph_nodes WHERE user_id = ? AND ("
                        clause ") LIMIT ?")]
        (mapv row->node (jdbc/execute! ds (into [sql user-id] (concat params [limit]))))))))

(defn expand-edges
  "Valid edges in the 1..max-hops neighborhood of `seed-ids`, with src/dst
  names attached. Returns maps {:id :relation :fact :confidence :src_id
  :dst_id :src_name :dst_name}. Bounded recursive CTE (max-hops clamped ≤3)."
  [ds user-id seed-ids {:keys [max-hops limit] :or {max-hops 2 limit 12}}]
  (let [hops  (min (max 1 max-hops) 3)
        seeds (vec (distinct seed-ids))]
    (if (empty? seeds)
      []
      (let [seeds-json (json/write-str seeds)
            sql (str "WITH RECURSIVE walk(node_id, depth) AS ("
                     "  SELECT CAST(value AS INTEGER), 0 FROM json_each(?) "
                     "  UNION "
                     "  SELECT CASE WHEN e.src_id = w.node_id THEN e.dst_id ELSE e.src_id END, w.depth + 1 "
                     "  FROM walk w "
                     "  JOIN graph_edges e ON (e.src_id = w.node_id OR e.dst_id = w.node_id) "
                     "  WHERE e.user_id = ? AND e.t_invalid IS NULL AND w.depth < ? ) "
                     "SELECT DISTINCT e.id AS id, e.relation AS relation, e.fact AS fact, "
                     "       e.confidence AS confidence, e.src_id AS src_id, e.dst_id AS dst_id, "
                     "       ns.name AS src_name, nd.name AS dst_name "
                     "FROM graph_edges e "
                     "JOIN walk w ON (e.src_id = w.node_id OR e.dst_id = w.node_id) "
                     "JOIN graph_nodes ns ON ns.id = e.src_id "
                     "JOIN graph_nodes nd ON nd.id = e.dst_id "
                     "WHERE e.user_id = ? AND e.t_invalid IS NULL "
                     "LIMIT ?")
            rows (jdbc/execute! ds [sql seeds-json user-id hops user-id limit])]
        (mapv denamespace rows)))))

(defn- edge->entry
  "Render a neighborhood edge as a recall entry (RRF-compatible + briefing)."
  [e]
  (let [rel (name (keyword (:relation e)))]
    {:id         (str "graph-edge-" (:id e))
     :layer      :graph
     :kind       :relationship
     :content    (str (:src_name e) " —" rel "→ " (:dst_name e)
                      (when (and (:fact e) (not (str/blank? (:fact e))))
                        (str ": " (:fact e))))
     :confidence (or (:confidence e) 0.85)
     :_graph     {:src (:src_name e) :relation (keyword (:relation e)) :dst (:dst_name e)}}))

(defn search-nodes-semantic
  "Resolve seed nodes by SEMANTIC similarity: embed `query`, kNN the
  `ref_kind='node'` vectors in `graph_vec`, and return the matching node maps,
  nearest-first. Over-fetches from the mixed (node+fact) index, then keeps the
  top `limit` node hits. Returns [] when unavailable (no embed-fn, blank query,
  no vec table, or no node hits) — callers fall back to lexical `search-nodes`."
  [ds user-id embed-fn query {:keys [limit] :or {limit 8}}]
  (if (or (nil? embed-fn) (not (string? query)) (str/blank? query) (not (vec-available? ds)))
    []
    (if-let [qe (embed/embed-one embed-fn query)]
      (let [hits     (vec-knn ds qe {:limit (max 24 (* 3 limit))})
            node-ids (->> hits (filter #(= "node" (:ref-kind %)))
                          (map :ref-id) (remove nil?) (take limit) vec)]
        (if (empty? node-ids)
          []
          (let [ph    (str/join "," (repeat (count node-ids) "?"))
                rows  (jdbc/execute! ds (into [(str "SELECT * FROM graph_nodes "
                                                    "WHERE user_id = ? AND id IN (" ph ")")]
                                              (cons user-id node-ids)))
                by-id (into {} (map (fn [r] (let [n (row->node r)] [(:id n) n]))) rows)]
            ;; preserve kNN (ascending-distance) order
            (->> node-ids (keep by-id) vec))))
      [])))

(defn related
  "Relational recall: resolve seed nodes from the query, expand the bounded
  neighborhood over valid edges, and return relationship entries — for fusion
  into the RRF and the '## Related' briefing section.

  Seeds are resolved SEMANTICALLY when an `embed-fn` and a `:query` are
  available (embed → kNN node vectors), falling back to lexical keyword
  `search-nodes` when the vector index is unavailable or yields no node hits.
  Returns [] when nothing matches (the non-regressing fallback)."
  [ds user-id embed-fn keywords {:keys [limit max-hops query] :or {limit 8 max-hops 2}}]
  (let [seeds (or (seq (search-nodes-semantic ds user-id embed-fn query {:limit 8}))
                  (search-nodes ds user-id keywords {:limit 8}))
        edges (when (seq seeds)
                (expand-edges ds user-id (map :id seeds)
                              {:max-hops max-hops :limit (* 2 limit)}))]
    (->> edges (map edge->entry) (take limit) vec)))

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
