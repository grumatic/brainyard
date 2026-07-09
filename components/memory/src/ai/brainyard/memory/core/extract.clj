;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.extract
  "LLM entity + relationship extraction for the context graph (CR-MEM-22).

  Two parts:
    - `make-extract-fn` builds an `extract-fn` `(fn [text] -> {:entities
      [...] :relations [...]})` over clj-llm structured output. The store
      stays provider-agnostic: a nil extract-fn disables extraction; tests
      inject a stub.
    - `process-extraction!` applies a (real or stub) extraction result to
      the graph: resolve/create nodes, insert edges with provenance,
      reconcile functional-relation supersession (bi-temporal), and embed
      node summaries.

  Curated vocabulary comes from `protocol/node-types` and
  `protocol/relations` — the LLM is constrained to those via JSON-schema
  enums, keeping the graph predictable."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [ai.brainyard.clj-llm.interface :as llm]
            [ai.brainyard.memory.core.graph :as graph]
            [ai.brainyard.memory.core.embed :as embed]
            [ai.brainyard.memory.interface.protocol :as proto]
            [ai.brainyard.mulog.interface :as mulog]))

;; =====================================================
;; Extraction call (LLM)
;; =====================================================

(def ^:private node-type-names (mapv name (sort proto/node-types)))
(def ^:private relation-names  (mapv name (sort proto/relations)))

(def extraction-schema
  "JSON Schema constraining the LLM to the curated entity/relation vocab."
  {:type "object"
   :additionalProperties false
   :properties
   {:entities
    {:type "array"
     :items {:type "object"
             :additionalProperties false
             :properties {:name    {:type "string"}
                          :type    {:type "string" :enum node-type-names}
                          :summary {:type "string"}
                          :aliases {:type "array" :items {:type "string"}}}
             :required ["name" "type"]}}
    :relations
    {:type "array"
     :items {:type "object"
             :additionalProperties false
             :properties {:src        {:type "string"}
                          :relation   {:type "string" :enum relation-names}
                          :dst        {:type "string"}
                          :fact       {:type "string"}
                          :confidence {:type "number"}}
             :required ["src" "relation" "dst"]}}}
   :required ["entities" "relations"]})

(def ^:private system-prompt
  (str "You extract a knowledge graph from developer/agent activity logs.\n"
       "Identify durable ENTITIES and typed RELATIONSHIPS between them.\n"
       "Use ONLY these entity types: " (str/join ", " node-type-names) ".\n"
       "Use ONLY these relations: " (str/join ", " relation-names) ".\n"
       "Record only durable, reusable knowledge (config keys, components, "
       "files, people, concepts and how they relate) — skip ephemeral "
       "chatter, greetings, and one-off values. Each relation's src and dst "
       "MUST name an entity you also list in entities. Return empty arrays "
       "when nothing is worth recording."))

(defn- parse-json
  "Parse the model's JSON, tolerating ```json fences."
  [s]
  (-> (str s)
      str/trim
      (str/replace (re-pattern "(?s)^```(?:json)?\\s*") "")
      (str/replace (re-pattern "(?s)\\s*```$") "")
      str/trim
      (json/read-str :key-fn keyword)))

(defn make-extract-fn
  "Build an `extract-fn` over clj-llm structured output, or nil when
  `lm-config` is absent (extraction disabled). Failures log and yield nil
  rather than propagating."
  [lm-config & {:keys [model]}]
  (when lm-config
    (let [lm (if model (assoc lm-config :model model) lm-config)]
      (fn [text]
        (try
          (let [result (-> (llm/chat-completion lm
                                                [{:role "system" :content system-prompt}
                                                 {:role "user"   :content (str "Activity:\n" text)}]
                                                :json-schema extraction-schema)
                           (llm/extract-content lm)
                           parse-json)]
            ;; Surface the yield so silent no-extract cases (e.g. a model that
            ;; ignores the JSON contract) are visible in the app log rather than
            ;; looking like "0 nodes = nothing worth recording".
            (mulog/info ::extracted
                        :model (:model lm)
                        :entities (count (:entities result))
                        :relations (count (:relations result)))
            result)
          (catch Exception e
            (mulog/warn ::extract-call-failed :model (:model lm) :error (ex-message e))
            nil))))))

;; =====================================================
;; Community summarizer (CR-MEM-24)
;; =====================================================

(def ^:private summarize-system
  (str "You write a one-sentence summary of a cluster of related entities "
       "from a developer/agent knowledge graph. Capture what ties the cluster "
       "together and any durable facts. Be concise and factual — no preamble."))

(defn make-summarize-fn
  "Build a `summarize-fn` `(fn [text] -> string)` over clj-llm plain-text
  completion, or nil when `lm-config` is absent. Used to summarize graph
  communities (CR-MEM-24). Failures log and yield nil."
  [lm-config & {:keys [model]}]
  (when lm-config
    (let [lm (if model (assoc lm-config :model model) lm-config)]
      (fn [text]
        (try
          (-> (llm/chat-completion lm
                                   [{:role "system" :content summarize-system}
                                    {:role "user"   :content text}])
              (llm/extract-content lm)
              str
              str/trim)
          (catch Exception e
            (mulog/warn ::summarize-call-failed :error (ex-message e))
            nil))))))

;; =====================================================
;; Apply extraction to the graph
;; =====================================================

(def ^:private functional-relations
  "Relations that are single-valued per source: a new edge supersedes any
  prior valid edge with the same (src, relation) but a different dst."
  #{:prefers})

(def default-graph-limits
  "Per-episode caps that CONFINE graph node/edge explosion. One file-heavy
  episode (e.g. an explore-agent turn listing dozens of files) can otherwise
  yield a huge entity/relation set, ballooning the graph and every downstream
  recall. We keep the highest-signal items: entities as returned (the extractor
  is prompted to list durable ones first), relations by descending confidence.
  Overridable per manager via start-capture! :graph-limits.

  These values mirror the `:graph-max-entities-per-episode` /
  `:graph-max-relations-per-episode` config defaults — they are the in-component
  fallback for callers that don't supply limits (the memory brick can't read
  agent config). Keep the two in sync when changing either."
  {:max-entities 12 :max-relations 24})

(defn- norm-type [t]
  (let [k (keyword t)] (if (proto/valid-node-type? k) k :entity)))

(defn process-extraction!
  "Apply an extraction result `{:entities [...] :relations [...]}` to the
  graph behind `store`. Resolves entities (create/merge by name+alias),
  inserts edges tagged with `source-entry-id` provenance, reconciles
  functional-relation supersession, and embeds node summaries when the
  store has an embed-fn. Returns `{:nodes n :edges n}`.

  `limits` (optional) caps how much a SINGLE episode may add — `:max-entities`
  and `:max-relations` (see `default-graph-limits`). Entities beyond the cap are
  dropped; relations are kept highest-confidence-first, then capped. This is the
  primary guard against node/edge explosion from a large episode."
  [store {:keys [entities relations]} source-entry-id & [limits]]
  (let [{:keys [max-entities max-relations max-nodes max-edges]} (merge default-graph-limits limits)
        n-ent-in  (count entities)
        n-rel-in  (count relations)
        ;; Confine explosion: cap entities (as-returned) and keep the
        ;; highest-confidence relations before capping.
        entities  (take max-entities entities)
        relations (->> relations
                       (sort-by #(- (double (or (:confidence %) 0.0))))
                       (take max-relations))
        _ (when (or (> n-ent-in max-entities) (> n-rel-in max-relations))
            (mulog/debug ::extraction-capped
                         :entities-in n-ent-in :entities-kept (count entities)
                         :relations-in n-rel-in :relations-kept (count relations)
                         :source-entry-id source-entry-id))
        embed-fn (:embed-fn store)
        ds       (:ds store)
        ;; 1. Upsert entities; index name + aliases (lowercased) → node.
        nodes (vec (for [e entities :when (:name e)]
                     (proto/upsert-node store {:node-type (norm-type (:type e))
                                               :name      (:name e)
                                               :summary   (:summary e)
                                               :aliases   (:aliases e)})))
        idx   (reduce (fn [m n]
                        (reduce #(assoc %1 (str/lower-case (str %2)) n)
                                (assoc m (str/lower-case (:name n)) n)
                                (:aliases n)))
                      {} nodes)
        resolve-node (fn [nm]
                       (when (and nm (not (str/blank? nm)))
                         (or (get idx (str/lower-case nm))
                             ;; A relation may reference an entity the model
                             ;; didn't list — create a minimal node so the
                             ;; edge stays valid (resolution can merge later).
                             (proto/upsert-node store {:node-type :entity :name nm}))))
        edges (vec
               (for [r relations
                     :when (and (:src r) (:dst r) (:relation r))
                     :let  [src (resolve-node (:src r))
                            dst (resolve-node (:dst r))
                            rel (keyword (:relation r))]
                     :when (and src dst (not= (:id src) (:id dst)))]
                 (do
                   ;; Bi-temporal supersession for functional relations.
                   (when (functional-relations rel)
                     (doseq [{:keys [edge node]} (proto/neighbors store (:id src)
                                                                  {:direction :out :relation rel})]
                       (when (not= (:id node) (:id dst))
                         (proto/invalidate-edge store (:id edge) nil))))
                   (proto/upsert-edge store {:src-id           (:id src)
                                             :dst-id           (:id dst)
                                             :relation         rel
                                             :fact             (:fact r)
                                             :confidence       (:confidence r)
                                             :source-entry-ids (when source-entry-id [source-entry-id])}))))]
    ;; 2. Embed node summaries for semantic recall over entities.
    (when embed-fn
      (doseq [n nodes :when (and (:summary n) (not (str/blank? (:summary n))))]
        (when-let [v (embed/embed-one embed-fn (str (:name n) ": " (:summary n)))]
          (graph/upsert-node-embedding! ds (:id n) v))))
    ;; 3. Total-size guard: after adding this episode's nodes/edges, evict the
    ;; lowest-retention nodes/edges if the graph is over its budgets.
    (let [evicted      (when max-nodes
                         (graph/prune-nodes-to-budget! ds (:user-id store) {:max-nodes max-nodes}))
          evicted-edge (when max-edges
                         (graph/prune-edges-to-budget! ds (:user-id store) {:max-edges max-edges}))]
      (mulog/debug ::extraction-applied :nodes (count nodes) :edges (count edges)
                   :evicted (or evicted 0) :evicted-edges (or evicted-edge 0)
                   :source-entry-id source-entry-id)
      {:nodes (count nodes) :edges (count edges)
       :evicted (or evicted 0) :evicted-edges (or evicted-edge 0)})))
