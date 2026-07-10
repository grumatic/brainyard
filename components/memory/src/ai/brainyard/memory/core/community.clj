;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.community
  "Community detection + summarization (CR-MEM-24) — the GraphRAG tier.

  Clusters the entity graph by **label propagation** (deterministic, no
  LLM), assigns `graph_nodes.community_id`, then summarizes each cluster
  into a `graph_communities` row and mirrors it into an L3 `:summary` fact
  so ordinary recall surfaces it. This supersedes the heuristic L2→L3
  time-bucket reducer (`capture/reducer.clj`) — clustering by *relatedness*
  rather than by *time window* — and closes CR-MEM-07.

  Summarization uses an injected `summarize-fn` (clj-llm) when available and
  falls back to a deterministic templated summary otherwise, so community
  consolidation works with or without an LLM provider."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [ai.brainyard.memory.interface.protocol :as proto]
            [ai.brainyard.mulog.interface :as mulog]))

(defn- val1 [row & ks] (some #(get row %) ks))

;; =====================================================
;; Label propagation (pure)
;; =====================================================

(defn- adjacency
  [edges]
  (reduce (fn [m [s d]]
            (-> m (update s (fnil conj #{}) d)
                (update d (fnil conj #{}) s)))
          {} edges))

(defn label-propagation
  "Deterministic asynchronous label propagation. `node-ids` is a coll of
  ints; `edges` a coll of `[src dst]`. Returns `{node-id -> label}` —
  connected components collapse to a shared label; isolated nodes keep
  their own. Nodes are visited in id order and ties broken by smallest
  label, so the result is reproducible."
  [node-ids edges & {:keys [max-iters] :or {max-iters 20}}]
  (let [adj   (adjacency edges)
        order (sort node-ids)]
    (loop [labels (zipmap node-ids node-ids) iter 0]
      (if (>= iter max-iters)
        labels
        (let [labels' (reduce
                       (fn [lbls nid]
                         (if-let [nbrs (seq (get adj nid))]
                           (let [freq (frequencies (map lbls nbrs))
                                 mx   (apply max (vals freq))
                                 best (reduce min (for [[l f] freq :when (= f mx)] l))]
                             (assoc lbls nid best))
                           lbls))
                       labels order)]
          (if (= labels' labels)
            labels'
            (recur labels' (inc iter))))))))

;; =====================================================
;; Detection — persist community_id
;; =====================================================

(defn detect-communities!
  "Run label propagation over the valid graph and persist `community_id`
  (compacted to 1..k) on each node. Returns `{:communities n :nodes n}`."
  [ds user-id]
  (let [node-ids (mapv #(val1 % :id :graph_nodes/id)
                       (jdbc/execute! ds ["SELECT id FROM graph_nodes WHERE user_id = ?" user-id]))
        edges    (mapv (fn [r] [(val1 r :src_id :graph_edges/src_id)
                                (val1 r :dst_id :graph_edges/dst_id)])
                       (jdbc/execute! ds ["SELECT src_id, dst_id FROM graph_edges
                                           WHERE user_id = ? AND t_invalid IS NULL" user-id]))]
    (if (empty? node-ids)
      {:communities 0 :nodes 0}
      (let [labels  (label-propagation node-ids edges)
            comm-id (zipmap (sort (distinct (vals labels))) (map inc (range)))]
        (jdbc/with-transaction [tx ds]
          (doseq [nid node-ids]
            (jdbc/execute! tx ["UPDATE graph_nodes SET community_id = ? WHERE id = ? AND user_id = ?"
                               (comm-id (labels nid)) nid user-id])))
        {:communities (count comm-id) :nodes (count node-ids)}))))

;; =====================================================
;; Summarization
;; =====================================================

(defn- members [ds user-id cid]
  (mapv (fn [r] {:name (val1 r :name :graph_nodes/name)
                 :type (val1 r :node_type :graph_nodes/node_type)
                 :summary (val1 r :summary :graph_nodes/summary)})
        (jdbc/execute! ds ["SELECT name, node_type, summary FROM graph_nodes
                            WHERE user_id = ? AND community_id = ? ORDER BY id" user-id cid])))

(defn- internal-edges [ds user-id cid]
  (mapv (fn [r] {:src (val1 r :src :graph_edges/src) :dst (val1 r :dst :graph_edges/dst)
                 :relation (val1 r :relation :graph_edges/relation) :fact (val1 r :fact :graph_edges/fact)})
        (jdbc/execute! ds ["SELECT ns.name AS src, nd.name AS dst, e.relation AS relation, e.fact AS fact
                            FROM graph_edges e
                            JOIN graph_nodes ns ON ns.id = e.src_id
                            JOIN graph_nodes nd ON nd.id = e.dst_id
                            WHERE e.user_id = ? AND e.t_invalid IS NULL
                              AND ns.community_id = ? AND nd.community_id = ?" user-id cid cid])))

(defn- describe
  "A compact prose description of a community, fed to the summarizer."
  [members edges]
  (str "Entities: "
       (str/join ", " (map (fn [m] (str (:name m) (when (:summary m) (str " (" (:summary m) ")")))) members))
       (when (seq edges)
         (str "\nRelationships:\n"
              (str/join "\n" (map #(str "- " (:src %) " " (name (keyword (:relation %)))
                                        " " (:dst %) (when (:fact %) (str " — " (:fact %)))) edges))))))

(defn- templated-summary
  "Deterministic, LLM-free summary — the fallback that still beats the old
  time-bucket heuristic by clustering on relatedness."
  [members edges]
  (str (count members) " related "
       (if (= 1 (count (distinct (map :type members)))) (str (name (keyword (or (:type (first members)) :entity))) "s") "entities")
       ": " (str/join ", " (map :name members))
       (when (seq edges) (str "; " (count edges) " relationship(s)"))))

(defn- sha256-16
  "First 16 hex chars of the SHA-256 of `s` — a stable, bounded content key
  (deterministic across processes, unlike clojure.core/hash)."
  [s]
  (let [bs (.digest (java.security.MessageDigest/getInstance "SHA-256")
                    (.getBytes (str s) "UTF-8"))]
    (subs (apply str (map #(format "%02x" (bit-and % 0xff)) bs)) 0 16)))

(defn- community-entry-id
  "Stable L3 entry-id for a community, derived from its content-based `label`
  (the same key `graph_communities` dedups on via ON CONFLICT(user_id, label))
  — NOT the volatile per-run `community_id`. Label propagation renumbers cids
  every run, so a cid-keyed entry-id accumulated a near-duplicate L3 fact each
  time; keying on the label upserts ONE fact for the cluster across runs."
  [user-id label]
  (str "community/" user-id "/" (sha256-16 label)))

(defn- upsert-community! [ds user-id label summary node-count entry-id]
  (jdbc/execute! ds ["INSERT INTO graph_communities (user_id, label, summary, node_count, entry_id, updated_at)
                      VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                      ON CONFLICT(user_id, label)
                      DO UPDATE SET summary = excluded.summary, node_count = excluded.node_count,
                                    entry_id = excluded.entry_id, updated_at = CURRENT_TIMESTAMP"
                     user-id label summary node-count entry-id]))

(defn summarize-communities!
  "Summarize every community of >= `:min-size` members: build a description,
  summarize it (LLM `summarize-fn` when present, templated otherwise), upsert
  a `graph_communities` row, and mirror the summary into an idempotent L3
  `:summary` fact (so recall surfaces it). Returns `{:summarized n}`."
  [store summarize-fn & {:keys [min-size] :or {min-size 2}}]
  (let [{:keys [ds user-id]} store
        cids (->> (jdbc/execute! ds ["SELECT DISTINCT community_id FROM graph_nodes
                                      WHERE user_id = ? AND community_id IS NOT NULL" user-id])
                  (map #(val1 % :community_id :graph_nodes/community_id))
                  (remove nil?) sort)
        n (atom 0)]
    (doseq [cid cids]
      (let [ms (members ds user-id cid)]
        (when (>= (count ms) min-size)
          (let [es       (internal-edges ds user-id cid)
                label    (str/join "+" (take 3 (map :name ms)))
                summary  (or (when summarize-fn (summarize-fn (describe ms es)))
                             (templated-summary ms es))
                entry-id (community-entry-id user-id label)]
            (proto/write-entry store :l3
                               {:kind :summary
                                :id entry-id
                                :content (str "Community [" label "]: " summary)
                                :user-id user-id
                                :tags #{"community" "graph"}
                                :confidence 0.8})
            (upsert-community! ds user-id label summary (count ms) entry-id)
            (swap! n inc)))))
    (mulog/info ::communities-summarized :count @n)
    {:summarized @n}))

;; =====================================================
;; Consolidation entry point (replaces the heuristic reducer)
;; =====================================================

(defn consolidate!
  "Detect communities, then summarize them into L3 facts + `graph_communities`
  rows. The CR-MEM-24 replacement for the heuristic L2→L3 reducer. Returns a
  summary map shaped like `reducer/reduce-l2!`'s for caller compatibility."
  [store summarize-fn & {:as opts}]
  (let [{:keys [ds user-id]} store
        d (detect-communities! ds user-id)
        s (apply summarize-communities! store summarize-fn (mapcat identity (or opts {})))]
    (mulog/info ::graph-consolidated :communities (:communities d) :produced (:summarized s))
    {:from-layer  :l2
     :to-layer    :l3
     :reducer     :community
     :communities (:communities d)
     :produced    (:summarized s)
     :consumed    (:nodes d)
     :auto-kept   0
     :batches     []}))
