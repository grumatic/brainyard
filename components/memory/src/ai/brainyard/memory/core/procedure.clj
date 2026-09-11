;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.procedure
  "Procedural-graph storage and traversal.

   A knowledge graph organizes `(entity, relation, entity)` to answer
   *what-is*. A procedural graph organizes `(procedure, relation, procedure)`
   to answer *what-to-do-next*: nodes abstract tool actions, code channels and
   task states; edges are ADMISSIBLE TRANSITIONS annotated with three textual
   attributes (Φ) — `condition` (when it applies), `guidance` (how to proceed),
   `pitfalls` (what to avoid).

   Deliberately NOT co-tenanted in `graph_nodes`/`graph_edges`; see the
   `procedure-schema` docstring in `core/sqlite.clj` and §0.2 of
   docs/design/procedural-graph-implementation.md for the four ways that
   leaked. Tables here are keyed by `graph_id`, not `user_id` — the database
   file is already per-user.

   This namespace is STORAGE ONLY. Localization (`Match`), rendering and
   prompt injection live in the agent component
   (`agent.common.procedure-nudge`), because the lower brick cannot see an
   agent.

   Design: docs/design/procedural-graph-implementation.md
   Related work: docs/design/procedural-graph-comparison.md"
  (:require [next.jdbc :as jdbc]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog]))

(def default-graph-id "default")

(def procedure-relations
  "Transition vocabulary for procedural edges (what-to-do). Deliberately
   DISJOINT from the memory graph's `relations` (what-is) — mixing the two
   vocabularies is what makes a fused ranking meaningless.

   `:verifies` and `:repairs` are brainyard-specific and replace the paper's
   Figure-1 `extracts_to`/`generates`, which describe a search-and-answer
   pipeline rather than tool-driven engineering work. They name the two
   procedural facts most worth encoding here: the verify/read-guard pattern (a
   successful apply is not a successful edit) and the recovery path."
  #{:precedes   ; dst is a normal next step after src
    :requires   ; src must have happened before dst is valid (precondition)
    :enables    ; src makes dst possible or useful
    :leads_to   ; src's result feeds dst
    :verifies   ; dst checks src's work
    :repairs})  ; dst is the recovery path when src failed

(def node-kinds
  "What a procedure node abstracts.

   There is deliberately **no `:code` kind**. A `code:<lang>` node was the
   original plan's way of saying 'a block ran here', and measured against 87
   real recorded turns it carried no procedural signal: 23 of 41 mined
   transitions were `code:bash -> code:bash`, which is a retry loop, not a
   transition. A tool called from inside a block keys on the TOOL (the seeder
   reads it out of the code text), so the only thing `code:<lang>` ever named
   was a block that invoked nothing — at which point the language is a fact
   about syntax, not about procedure.

   Excluding it is not merely a deletion: `procedure-seed/transitions` chains
   ACROSS iterations that name no procedure, so dropping the code node rewires
   `evo$tasks -> code:bash -> evo$stats` into `evo$tasks -> evo$stats`, which
   is the transition that was actually worth recording."
  #{:tool :state :skill})

(defn valid-relation? [r] (contains? procedure-relations (keyword r)))
(defn valid-kind?     [k] (contains? node-kinds (keyword k)))

;; =====================================================
;; Row helpers
;; =====================================================

(defn- denamespace
  "Flatten next.jdbc's namespaced result keys (:proc_nodes/id) to plain keys."
  [row]
  (when row
    (into {} (map (fn [[k v]]
                    [(if (and (keyword? k) (namespace k)) (keyword (name k)) k) v]))
          row)))

(defn- ->json [coll] (when (seq coll) (json/write-str coll)))

(defn- json-> [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (json/read-str s) (catch Exception _ nil))))

(defn- row->node [row]
  (let [m (denamespace row)]
    (when m
      (-> m
          (update :kind keyword)
          (update :aliases json->)))))

(defn- row->edge [row]
  (let [m (denamespace row)]
    (when m
      (update m :relation keyword))))

;; =====================================================
;; Nodes
;; =====================================================

(defn upsert-node!
  "Insert or merge a procedure node, resolved by (graph-id, name).

   `node` keys: :name (required), :kind (required, one of `node-kinds`),
   :summary, :aliases. Returns the persisted node map with :id.

   Merge semantics on conflict: `summary` is replaced when a non-nil one is
   given (COALESCE keeps the existing value otherwise), aliases are replaced
   wholesale. `kind` is NOT updated — a node's kind is established by whoever
   first observed it, and silently reclassifying `edit$apply` from :tool to
   :code because one caller guessed differently would move it in every
   rendering."
  [ds graph-id {:keys [name kind summary aliases]}]
  (when (str/blank? (str name))
    (throw (ex-info "upsert-node! requires :name" {:name name})))
  ;; Validated at the store boundary, the way `upsert-edge!` validates its
  ;; relation. Without this, dropping `:code` from `node-kinds` would be a
  ;; convention rather than a guarantee, and the next caller to pass `:code`
  ;; would repopulate the graph with nodes carrying no procedural signal.
  (when-not (valid-kind? (or kind :tool))
    (throw (ex-info "unknown procedure node kind" {:kind kind :known node-kinds})))
  (let [gid (or graph-id default-graph-id)
        knd (clojure.core/name (or kind :tool))]
    (jdbc/execute-one!
     ds ["INSERT INTO proc_nodes (graph_id, name, kind, summary, aliases)
          VALUES (?, ?, ?, ?, ?)
          ON CONFLICT(graph_id, name) DO UPDATE SET
            summary    = COALESCE(excluded.summary, proc_nodes.summary),
            aliases    = COALESCE(excluded.aliases, proc_nodes.aliases),
            updated_at = CURRENT_TIMESTAMP"
         gid (str name) knd summary (->json aliases)])
    ;; Read back by NATURAL KEY, never last_insert_rowid: sqlite leaves it
    ;; untouched when ON CONFLICT takes the DO UPDATE branch, so on an update
    ;; it names some earlier insert's row (or nothing). Same reasoning as
    ;; graph/upsert-edge.
    (row->node (jdbc/execute-one!
                ds ["SELECT * FROM proc_nodes WHERE graph_id = ? AND name = ?"
                    gid (str name)]))))

(defn find-node
  "Resolve a procedure node by EXACT name, or by exact alias membership.

   This is `Match(a_{t-1}, V)`. Exact by design: the paper matches the agent's
   most recent procedure to a node exactly, and a fuzzy match here would
   localize onto a neighbouring procedure and then confidently describe the
   wrong next step. A miss is the correct, cheap answer."
  [ds graph-id name]
  (let [gid (or graph-id default-graph-id)
        nm  (str name)]
    (when-not (str/blank? nm)
      (or (row->node (jdbc/execute-one!
                      ds ["SELECT * FROM proc_nodes WHERE graph_id = ? AND name = ?" gid nm]))
          ;; Alias fallback. `json_each` over the aliases array gives exact
          ;; element equality rather than a LIKE over the serialized JSON,
          ;; which would match a substring of an unrelated alias.
          (row->node (jdbc/execute-one!
                      ds ["SELECT n.* FROM proc_nodes n
                           WHERE n.graph_id = ?
                             AND n.aliases IS NOT NULL
                             AND EXISTS (SELECT 1 FROM json_each(n.aliases) je
                                         WHERE je.value = ?)
                           LIMIT 1"
                          gid nm]))))))

(defn all-nodes
  [ds graph-id limit]
  (mapv row->node
        (jdbc/execute! ds ["SELECT * FROM proc_nodes WHERE graph_id = ? ORDER BY name LIMIT ?"
                           (or graph-id default-graph-id) (or limit 1000)])))

(defn count-nodes [ds graph-id]
  (let [r (denamespace (jdbc/execute-one!
                        ds ["SELECT COUNT(*) AS c FROM proc_nodes WHERE graph_id = ?"
                            (or graph-id default-graph-id)]))]
    (long (or (:c r) 0))))

;; =====================================================
;; Edges
;; =====================================================

(defn upsert-edge!
  "Insert or refresh a procedural transition `(src)-[relation]->(dst)`.

   `edge` keys: :src-id :dst-id :relation (required); :condition :guidance
   :pitfalls (Φ), :confidence, :support, :origin, :gen, :t-valid.

   Idempotent against `idx_proc_edges_live` — the PARTIAL unique index on live
   rows. Re-asserting a transition updates Φ in place rather than accumulating
   a second live row, which matters because seeding re-runs over overlapping
   trajectory sets by design.

   `t_valid` is deliberately NOT updated on conflict: the transition has been
   valid since its original assertion. Superseding one goes through
   `invalidate-edge!` first, which takes the row out of the partial index and
   lets a new live row be inserted — so bi-temporal history still works."
  [ds graph-id {:keys [src-id dst-id relation condition guidance pitfalls
                       confidence support origin gen t-valid]}]
  (when-not (and src-id dst-id relation)
    (throw (ex-info "upsert-edge! requires :src-id :dst-id :relation"
                    {:src-id src-id :dst-id dst-id :relation relation})))
  (when-not (valid-relation? relation)
    (throw (ex-info "unknown procedure relation"
                    {:relation relation :known procedure-relations})))
  (when (= src-id dst-id)
    (throw (ex-info "self-loop is not an admissible transition" {:node src-id})))
  (let [gid  (or graph-id default-graph-id)
        rel  (clojure.core/name relation)
        conf (or confidence 0.85)
        sup  (or support 0)
        org  (clojure.core/name (or origin :refiner))
        ;; The `WHERE t_invalid IS NULL` predicate is REQUIRED to name a
        ;; partial index as a conflict target; without it sqlite matches no
        ;; index and the insert throws.
        on-conflict "ON CONFLICT(graph_id, src_id, dst_id, relation) WHERE t_invalid IS NULL
                     DO UPDATE SET condition  = excluded.condition,
                                   guidance   = excluded.guidance,
                                   pitfalls   = excluded.pitfalls,
                                   confidence = excluded.confidence,
                                   support    = excluded.support,
                                   origin     = excluded.origin,
                                   gen        = excluded.gen"]
    (if t-valid
      (jdbc/execute-one!
       ds [(str "INSERT INTO proc_edges
                 (graph_id, src_id, dst_id, relation, condition, guidance, pitfalls,
                  confidence, support, origin, gen, t_valid)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " on-conflict)
           gid src-id dst-id rel condition guidance pitfalls conf sup org gen t-valid])
      (jdbc/execute-one!
       ds [(str "INSERT INTO proc_edges
                 (graph_id, src_id, dst_id, relation, condition, guidance, pitfalls,
                  confidence, support, origin, gen)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " on-conflict)
           gid src-id dst-id rel condition guidance pitfalls conf sup org gen]))
    (mulog/debug ::proc-edge-upserted :src src-id :dst dst-id :relation rel)
    (row->edge (jdbc/execute-one!
                ds ["SELECT * FROM proc_edges
                     WHERE graph_id = ? AND src_id = ? AND dst_id = ? AND relation = ?
                       AND t_invalid IS NULL"
                    gid src-id dst-id rel]))))

(defn invalidate-edge!
  "Bi-temporal supersession — the paper's `Delete`. Sets `t_invalid`; never
   removes the row, so an evolution post-mortem can still reconstruct which
   graph produced a given rollout. Returns true when a live row was affected."
  ([ds graph-id edge-id] (invalidate-edge! ds graph-id edge-id nil))
  ([ds graph-id edge-id t-invalid]
   (let [gid (or graph-id default-graph-id)
         r   (if t-invalid
               (jdbc/execute-one!
                ds ["UPDATE proc_edges SET t_invalid = ?
                     WHERE id = ? AND graph_id = ? AND t_invalid IS NULL"
                    t-invalid edge-id gid])
               (jdbc/execute-one!
                ds ["UPDATE proc_edges SET t_invalid = CURRENT_TIMESTAMP
                     WHERE id = ? AND graph_id = ? AND t_invalid IS NULL"
                    edge-id gid]))]
     (pos? (or (:next.jdbc/update-count r) 0)))))

(defn all-edges
  "Every LIVE edge in the graph, endpoint names attached."
  [ds graph-id limit]
  (mapv (comp #(update % :relation keyword) denamespace)
        (jdbc/execute!
         ds ["SELECT e.*, ns.name AS src_name, nd.name AS dst_name
              FROM proc_edges e
              JOIN proc_nodes ns ON ns.id = e.src_id
              JOIN proc_nodes nd ON nd.id = e.dst_id
              WHERE e.graph_id = ? AND e.t_invalid IS NULL
              ORDER BY e.support DESC, e.id ASC
              LIMIT ?"
             (or graph-id default-graph-id) (or limit 2000)])))

(defn count-edges [ds graph-id]
  (let [r (denamespace (jdbc/execute-one!
                        ds ["SELECT COUNT(*) AS c FROM proc_edges
                             WHERE graph_id = ? AND t_invalid IS NULL"
                            (or graph-id default-graph-id)]))]
    (long (or (:c r) 0))))

;; =====================================================
;; Traversal — N_h(u)
;; =====================================================

(defn out-neighborhood
  "`N_h(u)`: LIVE edges reachable by walking OUT from `node-id`, up to
   `:max-hops`, with Φ attributes and endpoint names. Each edge carries
   `:depth` — 0 for an immediate out-edge of the seed, 1 for the next ring.
   Ordered by depth, then confidence desc, then support desc.

   DIRECTED, and that is the whole point. `graph/expand-edges` joins
   `(e.src_id = w.node_id OR e.dst_id = w.node_id)` because entity recall wants
   a neighborhood; this asks a different question — *what is admissible AFTER
   u* — and an undirected walk answers it with what leads INTO u, which is the
   opposite instruction. Copying that join is the single most likely error in
   this file and it fails silently: the guidance still renders, it is just
   backwards. `out-neighborhood-is-directed-test` pins it.

   `expand` (nodes, has :direction) and `expand-edges` (edges, undirected) each
   supply half of what is needed and neither supplies both, which is why this
   is a new walk rather than a reuse."
  [ds graph-id node-id {:keys [max-hops limit] :or {max-hops 2 limit 12}}]
  (let [gid  (or graph-id default-graph-id)
        hops (min (max 1 max-hops) 3)]
    (if-not node-id
      []
      (let [sql (str
                 "WITH RECURSIVE walk(node_id, depth) AS ("
                 "  SELECT CAST(? AS INTEGER), 0 "
                 "  UNION "
                 ;; OUT only: follow src -> dst. See docstring.
                 "  SELECT e.dst_id, w.depth + 1 "
                 "  FROM walk w "
                 "  JOIN proc_edges e ON e.src_id = w.node_id "
                 "  WHERE e.graph_id = ? AND e.t_invalid IS NULL AND w.depth < ? "
                 ") "
                 "SELECT e.id AS id, e.relation AS relation, "
                 "       e.condition AS condition, e.guidance AS guidance, "
                 "       e.pitfalls AS pitfalls, e.confidence AS confidence, "
                 "       e.support AS support, e.origin AS origin, "
                 "       e.src_id AS src_id, e.dst_id AS dst_id, "
                 "       ns.name AS src_name, nd.name AS dst_name, "
                 ;; A node reachable by two paths yields the same edge at two
                 ;; depths; MIN collapses it to its shortest, which is the ring
                 ;; the renderer should show it in.
                 "       MIN(w.depth) AS depth "
                 "FROM walk w "
                 "JOIN proc_edges e ON e.src_id = w.node_id "
                 "JOIN proc_nodes ns ON ns.id = e.src_id "
                 "JOIN proc_nodes nd ON nd.id = e.dst_id "
                 "WHERE e.graph_id = ? AND e.t_invalid IS NULL AND w.depth < ? "
                 "GROUP BY e.id "
                 "ORDER BY depth ASC, e.confidence DESC, e.support DESC, e.id ASC "
                 "LIMIT ?")]
        (mapv (comp #(update % :relation keyword) denamespace)
              (jdbc/execute! ds [sql node-id gid hops gid hops limit]))))))

;; =====================================================
;; Graph metadata (Phase 2 gate state)
;; =====================================================

(defn graph-meta
  "`{:graph-id :gen :val-score :val-suite}` for a graph, or a zeroed default."
  [ds graph-id]
  (let [gid (or graph-id default-graph-id)
        r   (denamespace (jdbc/execute-one!
                          ds ["SELECT * FROM proc_graphs WHERE graph_id = ?" gid]))]
    {:graph-id  gid
     :gen       (long (or (:gen r) 0))
     :val-score (:val_score r)
     :val-suite (:val_suite r)}))

(defn set-graph-meta!
  "Persist the RETAINED graph's generation and cached validation score.

   The cache is not an optimization. Re-scoring the retained graph each round
   would make the gate compare two independent noisy samples instead of a
   candidate against a fixed reference — the paper keeps `S_val(G_{k-1})`
   cached for exactly this reason."
  [ds graph-id {:keys [gen val-score val-suite]}]
  (let [gid (or graph-id default-graph-id)]
    (jdbc/execute-one!
     ds ["INSERT INTO proc_graphs (graph_id, gen, val_score, val_suite, updated_at)
          VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
          ON CONFLICT(graph_id) DO UPDATE SET
            gen        = COALESCE(excluded.gen, proc_graphs.gen),
            val_score  = COALESCE(excluded.val_score, proc_graphs.val_score),
            val_suite  = COALESCE(excluded.val_suite, proc_graphs.val_suite),
            updated_at = CURRENT_TIMESTAMP"
         gid gen val-score val-suite])
    (graph-meta ds gid)))

;; =====================================================
;; Rejection memory (H_rejected)
;; =====================================================

(defn record-rejection!
  "Log a rejected candidate as negative evidence for the next refiner round.
   `edits` is the proposed ΔG, stored as JSON."
  [ds graph-id {:keys [gen edits val-score base-score reason]}]
  (jdbc/execute-one!
   ds ["INSERT INTO proc_rejections (graph_id, gen, edits, val_score, base_score, reason)
        VALUES (?, ?, ?, ?, ?, ?)"
       (or graph-id default-graph-id) (or gen 0)
       (if (string? edits) edits (json/write-str edits))
       val-score base-score (some-> reason clojure.core/name)])
  nil)

(defn rejections
  "Rejected candidates for a graph, newest first."
  [ds graph-id limit]
  (mapv (fn [r] (-> (denamespace r) (update :edits json->)))
        (jdbc/execute! ds ["SELECT * FROM proc_rejections WHERE graph_id = ?
                            ORDER BY id DESC LIMIT ?"
                           (or graph-id default-graph-id) (or limit 50)])))

;; =====================================================
;; Snapshot
;; =====================================================

(defn snapshot
  "Whole-graph dump for `by procedures list`, export, and visualisation."
  [ds graph-id {:keys [node-limit edge-limit]}]
  (let [gid (or graph-id default-graph-id)]
    {:graph-id gid
     :meta     (graph-meta ds gid)
     :nodes    (all-nodes ds gid (or node-limit 1000))
     :edges    (all-edges ds gid (or edge-limit 2000))
     :counts   {:nodes (count-nodes ds gid) :edges (count-edges ds gid)}}))
