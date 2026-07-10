;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.interface
  "Public API for the memory component.

  Memory is layered (L1, L2, L3) and accessed exclusively through the
  unified `IMemoryStore` protocol. Storage is SQLite + FTS5 for L2/L3,
  in-memory atom for L1.

  Surface:
    - factory:       create-memory-manager, create-store
    - data plane:    write-entry, read-entries, promote-entry, forget-entry
    - L1 ids:        l1-entry-id
    - recall:        contextual-recall (cross-layer RRF)
    - capture:       start-capture!, stop-capture!, capture-running?,
                     consolidate-l2!
    - retention:     keep!, unkeep!, archive!, unarchive!,
                     sweep-l2!, start-sweeper!, stop-sweeper!
    - audit:         explain, explain-session
    - lifecycle:     initialize, shutdown, get-stats
    - FTS utils:     normalize-fts-query, extract-keywords

  The legacy episodic/semantic/working accessors that used to live
  here were removed in the unified-store refactor — see
  docs/architecture/memory.md."
  (:require [clojure.string :as str]
            [ai.brainyard.memory.core.manager :as manager]
            [ai.brainyard.memory.core.fts :as fts]
            [ai.brainyard.memory.core.embed :as embed]
            [ai.brainyard.memory.core.embed-static :as embed-static]
            [ai.brainyard.memory.core.extract :as extract]
            [ai.brainyard.memory.core.episodic :as episodic]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.core.unified-store :as us]
            [ai.brainyard.memory.core.graph :as graph]
            [ai.brainyard.memory.core.l1-store :as l1]
            [ai.brainyard.memory.core.capture.dispatcher :as capture-dispatcher]
            [ai.brainyard.memory.core.capture.sidecar :as capture-sidecar]
            [ai.brainyard.memory.core.capture.extractor :as capture-extractor]
            [ai.brainyard.memory.core.policy :as policy]
            [ai.brainyard.memory.core.audit :as audit]
            [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.memory.interface.protocol :as proto]))

;; =====================================================
;; Factory
;; =====================================================

(defn create-memory-manager
  "Create a unified memory manager for a user.

  Parameters:
    user-id - User identifier

  Options:
    :base-path - Base directory for database files (default: \"~/.brainyard/memory\")
    :db-path - Custom database path (overrides base-path)
    :in-memory - Use in-memory database (for testing)

  Returns: MemoryManager instance implementing UnifiedMemory and
           MemoryManagerLifecycle."
  [user-id & {:as opts}]
  (apply manager/create-memory-manager user-id (mapcat identity opts)))

;; =====================================================
;; Context-graph providers (CR-MEM-21 / CR-MEM-22)
;; =====================================================

(defn make-embed-fn
  "Build an `embed-fn` for the vector index (CR-MEM-21) over a clj-llm
  lm-config. Returns nil when lm-config is nil. Pass the result as
  `:embed-fn` to `create-memory-manager`."
  [lm-config & {:as opts}]
  (apply embed/make-embed-fn lm-config (mapcat identity opts)))

(defn make-extract-fn
  "Build an `extract-fn` for graph extraction (CR-MEM-22) over a clj-llm
  lm-config. Returns nil when lm-config is nil. Pass the result as
  `:extract-fn` to `create-memory-manager`."
  [lm-config & {:as opts}]
  (apply extract/make-extract-fn lm-config (mapcat identity opts)))

(defn static-embed-fn
  "Return the self-contained, in-process Model2Vec `embed-fn` (CR-MEM-21) —
  `(fn [texts] -> [[float…] …])` — or nil when the bundled model is absent.
  Pair with `static-embed-dims` as `:embed-dims` for `create-memory-manager`.
  Needs no embedding server and no native libs."
  []
  (embed-static/static-embed-fn))

(defn static-embed-dims
  "Output dimensionality of the bundled Model2Vec model (e.g. 256), or nil."
  []
  (embed-static/dimensions))

(defn static-embed-available?
  "True when the bundled Model2Vec model is present on the classpath / via
  BY_MODEL2VEC_PATH."
  []
  (embed-static/available?))

(defn make-summarize-fn
  "Build a `summarize-fn` for community summaries (CR-MEM-24) over a clj-llm
  lm-config. Returns nil when lm-config is nil. Pass the result as
  `:summarize-fn` to `create-memory-manager` (nil ⇒ templated summaries)."
  [lm-config & {:as opts}]
  (apply extract/make-summarize-fn lm-config (mapcat identity opts)))

(defn consolidate-graph!
  "Run CR-MEM-24 community consolidation: detect graph communities and
  summarize each into a `graph_communities` row + an L3 `:summary` fact.
  The GraphRAG replacement for the heuristic L2→L3 reducer. Returns a
  summary map `{:communities :produced :consumed …}`."
  [manager & opts]
  (proto/consolidate-layer (:store manager) :l2
                           (assoc (apply hash-map opts) :reducer :community)))

(defn- ->store
  [store-or-manager]
  (if (instance? ai.brainyard.memory.core.unified_store.UnifiedStore store-or-manager)
    store-or-manager
    (:store store-or-manager)))

(defn graph-related
  "Relational recall (CR-MEM-23): seed nodes from `keywords`, expand the
  bounded neighborhood, return relationship entries. opts: {:limit :max-hops}."
  ([store-or-manager keywords] (graph-related store-or-manager keywords {}))
  ([store-or-manager keywords opts]
   (proto/related (->store store-or-manager) keywords opts)))

(defn graph-snapshot
  "Full-graph dump for visualisation/export: every node + every valid edge for
  the store's user, plus counts. opts: {:node-limit :edge-limit}. Returns
  `{:nodes [...] :edges [...] :counts {:nodes N :edges M}}`. Unscoped (not a
  recall query) — degrades to empty collections when the graph tier was never
  populated."
  ([store-or-manager] (graph-snapshot store-or-manager {}))
  ([store-or-manager {:keys [node-limit edge-limit]}]
   (let [s       (->store store-or-manager)
         ds      (:ds s)
         user-id (:user-id s)]
     {:nodes  (graph/all-nodes ds user-id (or node-limit 1000))
      :edges  (graph/all-edges ds user-id (or edge-limit 2000))
      :counts {:nodes (graph/count-nodes ds user-id)
               :edges (graph/count-edges ds user-id)}})))

(defn graph-vec-status
  "Embedding-model staleness of the vector index (CR-MEM-21).
  Returns `{:stale? bool :was <fp> :now <fp> :count n}`. When `:stale?` is
  true the embedding model changed since `graph_vec` was built, so semantic
  recall is paused (FTS-only) until `reembed-graph-vec!` rebuilds it."
  [store-or-manager]
  (us/vec-status (->store store-or-manager)))

(defn graph-vec-stale-notice
  "A one-line, actionable user notice when the vector index is stale (embed
  model changed), or nil. For TUI banners / `/status` / startup surfaces."
  [store-or-manager]
  (let [{:keys [stale? was now count]} (graph-vec-status store-or-manager)]
    (when stale?
      (str "⚠ Embedding model changed (" (or was "?") " → " (or now "?")
           "). Semantic memory recall is paused (keyword search only) until the "
           "vector index is rebuilt — ~" (or count 0) " facts. Rebuild with "
           "`reembed-graph-vec!`, or restore the previous :graph-embed-model."))))

(defn reembed-graph-vec!
  "Rebuild `graph_vec` for the current embedder and resume semantic recall:
  recreate at the embedder's dim, re-embed all L3 facts + node summaries,
  re-stamp the model fingerprint, clear the stale flag. Returns
  `{:facts n :nodes n}` (nil when no embedder). Re-embeds N rows — run it as
  a background task for large stores."
  [store-or-manager]
  (us/reembed! (->store store-or-manager)))

(defn graph-as-of
  "Historical neighborhood (CR-MEM-23): edges incident to `node-id` that were
  valid at `timestamp` (`t_valid <= ts AND (t_invalid IS NULL OR t_invalid >
  ts)`) — \"what did we believe then.\" opts: {:direction :relation :limit}."
  ([store-or-manager node-id timestamp]
   (graph-as-of store-or-manager node-id timestamp {}))
  ([store-or-manager node-id timestamp opts]
   (proto/as-of (->store store-or-manager) node-id timestamp opts)))

;; =====================================================
;; Recall (cross-layer)
;; =====================================================

(defn contextual-recall
  "Search across the L1/L2/L3 memory layers with weighted RRF ranking.

  Options forwarded to the underlying recall-v2 pipeline:
    :session-id  - Restrict L1/L2 reads to this session
    :limit       - Per-layer cap (default 10)
    :total-limit - Cap on combined result (default 20)
    :weights     - Legacy {:episodic :semantic} or new
                   {:l1 :l2 :l3} per-layer weight map
    :budget      - Briefing budget in chars (default 4000)
    :agent-id    - Required with :turn-id to qualify the audit row
                   (per-agent turn-id is unique only within an agent).
    :turn-id     - Per-agent turn counter. With :session-id and
                   :agent-id, writes audit rows so the prompt can be
                   replayed via (memory/explain ...).
    :total-turns - Optional session-cumulative counter; recorded with
                   the audit row for cross-agent ordering.
    :match       - Multi-word FTS mode: :or (default), :and, :phrase

  Returns: Vector of entries with :_rrf_score and :_layer."
  [manager query & {:as opts}]
  (proto/contextual-recall manager query (or opts {})))

;; =====================================================
;; Lifecycle
;; =====================================================

(defn get-stats
  "Get memory system statistics.

  Returns:
    {:episodes n :semantic-facts n :schema-version string}"
  [manager]
  (proto/get-stats manager))

(defn initialize
  "Initialize the memory manager (create tables, load state)."
  [manager]
  (proto/initialize manager))

(defn shutdown
  "Shutdown the memory manager (cleanup resources)."
  [manager]
  (proto/shutdown manager))

;; =====================================================
;; FTS Utilities
;; =====================================================

(defn normalize-fts-query
  "Normalize a natural language query for FTS5 MATCH clause.
  Returns normalized query string, or nil if no valid terms.

  Optional :match — :or (default), :and, :phrase."
  ([query] (fts/normalize-fts-query query))
  ([query match] (fts/normalize-fts-query query match)))

(defn extract-keywords
  "Extract distinctive keywords from text.
  Returns vector of keyword strings."
  [text & {:as opts}]
  (apply fts/extract-keywords text (mapcat identity opts)))

;; =====================================================
;; Unified Store (IMemoryStore)
;; =====================================================

(defn create-store
  "Create a UnifiedStore directly (without a MemoryManager wrapper).

  Required: :user-id, :ds (or :in-memory true)
  Optional: :l1-store"
  [& {:keys [in-memory ds] :as opts}]
  (let [ds' (or ds (when in-memory
                     (ai.brainyard.memory.core.sqlite/create-datasource
                      ":memory:?cache=shared")))]
    (when in-memory
      (ai.brainyard.memory.core.sqlite/init-schema! ds'))
    (apply us/create-unified-store
           (mapcat identity (assoc opts :ds ds')))))

(defn store
  "Get the UnifiedStore from a MemoryManager."
  [manager]
  (:store manager))

(defn write-entry
  "Persist an entry into the given memory layer.

  layer: :l1 :l2 :l3
  entry: see protocol/IMemoryStore for schema. Required keys vary by layer
         (:kind and :content always required; L1 needs :id stable across
         writes, L2/L3 will generate one if absent)."
  [store-or-manager layer entry]
  (let [s (if (instance? ai.brainyard.memory.core.unified_store.UnifiedStore
                         store-or-manager)
            store-or-manager
            (store store-or-manager))]
    (proto/write-entry s layer entry)))

(defn read-entries
  "Read entries from a memory layer.

  layer: :l1 :l2 :l3
  query: per-layer filter map (see protocol/IMemoryStore docstring)
  opts:  {:limit :consistent :include-archived :include-tombstoned}"
  ([store-or-manager layer query]
   (read-entries store-or-manager layer query {}))
  ([store-or-manager layer query opts]
   (let [s (if (instance? ai.brainyard.memory.core.unified_store.UnifiedStore
                          store-or-manager)
             store-or-manager
             (store store-or-manager))]
     (proto/read-entries s layer query opts))))

(defn promote-entry
  "Promote an entry from one layer to another, preserving provenance via
  a `:sources [{:type :promotion :id <orig-id> :from-layer ...}]` chain."
  [store-or-manager entry from-layer to-layer]
  (let [s (if (instance? ai.brainyard.memory.core.unified_store.UnifiedStore
                         store-or-manager)
            store-or-manager
            (store store-or-manager))]
    (proto/promote s entry from-layer to-layer)))

(defn forget-entry
  "Remove or tombstone an entry by id. Returns truthy if the entry
  existed."
  [store-or-manager layer entry-id]
  (let [s (if (instance? ai.brainyard.memory.core.unified_store.UnifiedStore
                         store-or-manager)
            store-or-manager
            (store store-or-manager))]
    (proto/forget s layer entry-id)))

;; =====================================================
;; L1 entry-id helper
;; =====================================================

(defn l1-entry-id
  "Compose a canonical L1 entry-id from (kind, field, section).

  Format: \"{kind}/{field}/{section}\".
  Session-scoping is enforced at the storage layer via
  `[session-id entry-id]` keys, so the id itself does NOT include the
  session id.

  Examples:
    (l1-entry-id :system-context :tool-context \"naming\")
    ; => \"system-context/tool-context/naming\"

    (l1-entry-id :user-context :preferences \"timezone\")
    ; => \"user-context/preferences/timezone\""
  [kind field section]
  (l1/l1-entry-id kind field section))

;; =====================================================
;; Capture Pipeline (S0/S1/S2)
;; =====================================================

(defn capture-running?
  "True when a capture pipeline is active for `manager`."
  [manager]
  (boolean (some-> manager :!capture deref)))

(defn start-capture!
  "Start the memory capture pipeline for this manager. Subscribes to the
  agent-runtime hooks (`:agent.ask/pre`, `:agent.ask/post`, `:agent.tool-use/post`,
  `:agent.code-eval/post`, `:agent/exception`) and feeds events through S1
  (parser) into L2 via the unified store. Capture is OFF by default —
  call this to enable.

  Options:
    :channel-size    — non-critical channel size (default 1024)
    :debounce-window — recent-digest window for dedup (default 30)
    :match           — (fn [event-map]) -> boolean to scope which agents
                       are captured (e.g. only one user, only root agents).
    :limits          — storage-truncation override map for the parser, merged
                       over `parser/default-limits` (e.g. the agent's configured
                       `:question`/`:answer` caps).
    :graph-limits    — per-episode graph caps for the extractor (`:max-input-chars`,
                       `:max-entities`, `:max-relations`), confining node/edge
                       explosion. Merged over the extractor/extract defaults.
    :defer-extraction? — when true, do NOT start the async per-episode extractor
                       even if an extract-fn is present. Graph extraction then
                       happens in batch at consolidation (`extract-l2-batch!`) —
                       the `:graph-extract-mode :at-consolidation` path.

  Idempotent: calling twice on the same manager returns the existing
  capture handle."
  [manager & {:keys [limits graph-limits defer-extraction?] :as opts}]
  (when-not manager
    (throw (ex-info "start-capture! requires a memory manager" {})))
  (let [!cap (:!capture manager)
        ;; :limits / :graph-limits / :defer-extraction? are consumed here, not
        ;; by the dispatcher.
        disp-opts (dissoc opts :limits :graph-limits :defer-extraction?)]
    (or @!cap
        (let [d   (apply capture-dispatcher/start! (mapcat identity disp-opts))
              ;; CR-MEM-22: when the manager carries an extract-fn, run the
              ;; graph-extraction sidecar and feed it persisted L2 entries
              ;; via the sidecar's :on-write seam — UNLESS extraction is
              ;; deferred to consolidation (:at-consolidation mode).
              ex  (when (and (:extract-fn manager) (not defer-extraction?))
                    (capture-extractor/start! (:store manager) (:extract-fn manager) :limits graph-limits))
              s   (apply capture-sidecar/start! (:store manager) d
                         (concat (when ex [:on-write #(capture-extractor/enqueue! ex %)])
                                 (when limits [:limits limits])))
              h   {:dispatcher d :sidecar s :extractor ex
                   :started-at (System/currentTimeMillis)}]
          (reset! !cap h)
          (mulog/info ::capture-started :user-id (:user-id manager)
                      :graph-extraction (boolean ex))
          h))))

(defn stop-capture!
  "Stop the capture pipeline (and graph extractor, if running) for
  `manager`. Idempotent."
  [manager]
  (when-let [{:keys [dispatcher sidecar extractor]} (some-> manager :!capture deref)]
    (capture-sidecar/stop! sidecar)
    (capture-dispatcher/stop! dispatcher)
    (capture-sidecar/await-drain! sidecar 1000)
    (when extractor
      (capture-extractor/stop! extractor)
      (capture-extractor/await-drain! extractor 1000))
    (reset! (:!capture manager) nil)
    (mulog/info ::capture-stopped :user-id (:user-id manager))
    nil))

(defn consolidate-l2!
  "Run the heuristic S2 reducer over L2 episodes, emitting summary
  facts into L3 with provenance back to the source episode ids.

  Thin kwargs-friendly wrapper around
  `proto/consolidate-layer` with `:from-layer :l2`. See the protocol
  docstring for policy keys (`:session-id`, `:window-ms`, `:min-batch`,
  `:reducer`) and the return shape. The store supplies `:user-id` and
  `:ds` from its record fields."
  [manager & opts]
  (proto/consolidate-layer (:store manager) :l2 (apply hash-map opts)))

(defn- graph-watermark-key
  "`memory_metadata` key for the incremental graph-extraction high-water mark
  (max episode id already extracted). Scoped to the query scope so a per-user
  sweep and a per-session run keep independent marks — episode ids interleave
  across sessions, so a single global mark would let a session-scoped run skip
  another session's older, unextracted episodes. The db file is per-user, but
  the key still carries the user-id defensively."
  [user-id session-id]
  (str "graph_extract_after_id:u:" user-id
       (when session-id (str ":s:" session-id))))

(defn- join-turns
  "Concatenate turn/episode contents into one extraction input, delimiting each
  turn with a header so the extractor can attribute entities/relations per turn
  (the `GraphExtraction` instruction tells it the input is multiple turns). A
  single-content input still gets a header — harmless and consistent with the
  batched form."
  [contents]
  (->> contents
       (map-indexed (fn [i c] (str "=== turn " (inc i) " ===\n" c)))
       (str/join "\n\n")))

(defn- pack-windows
  "Greedily pack episodes (oldest-first) into windows bounded by BOTH `max-eps`
  (episode count) and `max-chars` (joined `:content` length) — whichever binds
  first. Episode-count is the primary control: a small window keeps the model
  thorough (it dilutes over a big concatenated context). An episode longer than
  `max-chars` forms its own window (truncated at call time). Never drops an
  episode. Returns a vector of episode-vectors."
  [eps max-eps max-chars]
  (let [{:keys [cur out]}
        (reduce (fn [{:keys [cur cur-len out]} ep]
                  (let [c   (count (or (:content ep) ""))
                        add (+ c (if (seq cur) 2 0))]   ;; 2 = "\n\n" separator
                    (if (and (seq cur)
                             (or (>= (count cur) max-eps)
                                 (> (+ cur-len add) max-chars)))
                      {:cur [ep] :cur-len c :out (conj out cur)}
                      {:cur (conj cur ep) :cur-len (+ cur-len add) :out out})))
                {:cur [] :cur-len 0 :out []} eps)]
    (cond-> out (seq cur) (conj cur))))

(defn extract-l2-graph!
  "Synchronously extract graph nodes/edges from a user's L2 episodes
  (optionally restricted to one `:session-id`), populating
  `graph_nodes`/`graph_edges`. BLOCKS until done — the deterministic
  counterpart to the async capture-time extractor, intended for scripted or
  backfill graph builds where the 1s shutdown drain of the async path would
  drop in-flight extractions.

  BATCHED: episodes are grouped by session (so a per-user sweep never mixes
  sessions into one blob) and char-packed into `:max-input-chars` (default
  400K ≈ 100K tokens) windows, ONE LLM call per window — not one per episode
  (~N× fewer calls). Like the `:at-consolidation` batch path, a window spans
  many episodes so edges carry no single `source-entry-id` (nil provenance).

  INCREMENTAL by default: a per-scope high-water mark (max episode id already
  extracted) is persisted in `memory_metadata`, and each run processes only
  episodes with a higher id — so repeated `graph-build`/`reduce` (e.g. the
  detached session-end offload) re-extract only the new tail, not the whole
  history. Pass `:rebuild? true` to ignore the mark and re-extract everything
  (after an extract-model/prompt change); the mark is still advanced afterward.
  `:limit` (default 1000) bounds a single per-user sweep — oldest-first, so a
  backlog larger than the limit drains over successive runs.

  Requires the manager's `:extract-fn` (the context-graph tier — i.e.
  `:enable-graph-memory` + a configured extract model). When absent this is a
  no-op returning `{:attempted 0 :total 0 :no-extract-fn true}`.

  `:max-entities`/`:max-relations` cap how much a single extraction may add.
  Callers supply these from the `:graph-max-entities-per-episode` /
  `:graph-max-relations-per-episode` config (the memory brick can't read agent
  config); omitted ⇒ the in-component `extract/default-graph-limits` fallback
  applies. Total-graph budgets are enforced separately via
  `prune-graph-to-budget!`.

  Returns `{:attempted <n episodes fed> :total <n new episodes> :calls <n LLM
  calls / windows> :nodes n :edges n :incremental bool :after-id <mark used>
  :through-id <new mark>}`."
  [manager & {:keys [session-id max-entities max-relations rebuild? limit
                     max-input-chars max-episodes-per-window]
              :or   {limit 1000 max-input-chars 400000 max-episodes-per-window 10}}]
  (if-let [extract-fn (:extract-fn manager)]
    (let [s        (:store manager)
          ds       (:ds s)
          user-id  (:user-id s)
          wm-key   (graph-watermark-key user-id session-id)
          after-id (if rebuild?
                     0
                     (or (some-> (sqlite/get-metadata ds wm-key) parse-long) 0))
          ;; Oldest-first, id-filtered so the watermark advances monotonically.
          rows     (if session-id
                     (episodic/episodes-after-id ds session-id after-id)
                     (episodic/episodes-after-id-for-user ds user-id after-id limit))
          ;; One call per session-scoped window (bounded by episode count, then
          ;; chars) instead of one per episode.
          windows  (mapcat (fn [[_sid eps]] (pack-windows eps max-episodes-per-window max-input-chars))
                           (group-by :session_id rows))
          result   (reduce
                    (fn [acc win]
                      (let [text0 (join-turns (keep :content win))
                            text  (if (> (count text0) max-input-chars)
                                    (subs text0 0 max-input-chars) text0)
                            n     (count win)]
                        (if (< (count text) 40)   ;; skip a trivially-short window
                          acc
                          ;; Scale per-episode caps to the window so an N-episode
                          ;; window keeps N× the single-episode budget (not 1×).
                          ;; nil source-entry-id: a window spans many episodes.
                          (let [win-limits (cond-> nil
                                             max-entities  (assoc :max-entities  (* max-entities n))
                                             max-relations (assoc :max-relations (* max-relations n)))
                                applied (some-> (extract-fn text)
                                                (as-> r (extract/process-extraction! s r nil win-limits)))]
                            (-> acc
                                (update :attempted + n)
                                (update :calls inc)
                                (update :nodes + (:nodes applied 0))
                                (update :edges + (:edges applied 0)))))))
                    {:attempted 0 :calls 0 :nodes 0 :edges 0} windows)
          max-id   (when (seq rows) (reduce max (keep :id rows)))]
      (when (and max-id (> (long max-id) (long after-id)))
        (sqlite/set-metadata! ds wm-key (str max-id)))
      (assoc result
             :total       (count rows)
             :incremental (not (boolean rebuild?))
             :after-id    after-id
             :through-id  (or max-id after-id)))
    {:attempted 0 :total 0 :no-extract-fn true}))

(defn prune-graph-to-budget!
  "Enforce total-size budgets on the user's context graph, evicting the
  lowest-retention nodes/edges over budget (see `graph/prune-nodes-to-budget!`
  and `graph/prune-edges-to-budget!`). A no-op arg (nil / 0) leaves that
  dimension untouched. Returns
  `{:nodes-evicted n :edges-evicted m :nodes <count-after> :edges <count-after>}`
  — the post-prune counts give the resulting graph size for reporting."
  [manager & {:keys [max-nodes max-edges]}]
  (let [{:keys [ds user-id]} (:store manager)]
    {:nodes-evicted (graph/prune-nodes-to-budget! ds user-id {:max-nodes max-nodes})
     :edges-evicted (graph/prune-edges-to-budget! ds user-id {:max-edges max-edges})
     :nodes         (graph/count-nodes ds user-id)
     :edges         (graph/count-edges ds user-id)}))

(defn capture-quiesce!
  "Block until the capture pipeline has flushed everything queued so far — all
  pending L2 writes committed — up to `timeout-ms`, WITHOUT stopping capture.
  Returns true if drained, false on timeout / no running capture. Call before
  `:at-consolidation` batch extraction so the triggering turn's async write is
  visible in L2."
  [manager timeout-ms]
  (boolean (when-let [s (some-> manager :!capture deref :sidecar)]
             (capture-sidecar/quiesce! s timeout-ms))))

(defn extract-l2-batch!
  "Batch graph extraction for the `:at-consolidation` mode: concatenate a
  session's L2 episodes NEWER than `:after-id` (oldest-first) into ONE text
  (bounded by `:max-input-chars`, default 400K chars ≈ 100K tokens) and run a
  SINGLE extraction over it, populating the graph. The at-consolidation
  counterpart to the per-episode async extractor — one LLM call per
  consolidation window instead of one per turn. Entity/relation/node/edge caps
  are passed through to `process-extraction!`. Callers supply them from the
  `:graph-max-*` config (the memory brick can't read agent config); omitted
  caps fall back to `extract/default-graph-limits` (kept in sync with the
  config defaults).

  Incremental: pass the previous run's `:max-id` back as `:after-id` so each run
  only extracts episodes captured since. Returns
  `{:calls 0|1 :new-episodes n :nodes n :edges n :max-id id}`; `:no-extract-fn
  true` when the graph tier is off."
  [manager & {:keys [session-id after-id max-input-chars max-entities max-relations max-nodes max-edges]
              :or   {after-id 0 max-input-chars 400000}}]
  (if-let [extract-fn (:extract-fn manager)]
    (let [store  (:store manager)
          eps    (episodic/episodes-after-id (:ds store) session-id after-id)
          max-id (reduce max after-id (keep :id eps))
          text0  (join-turns (keep :content eps))
          text   (if (> (count text0) max-input-chars) (subs text0 0 max-input-chars) text0)]
      (if (or (empty? eps) (< (count text) 40))
        {:calls 0 :new-episodes (count eps) :nodes 0 :edges 0 :max-id max-id}
        (let [result  (extract-fn text)
              n       (count eps)
              ;; Batch extraction spans many episodes, so there is no single
              ;; source-entry-id to attribute (nil ⇒ no per-edge provenance).
              ;; Scale the per-episode entity/relation caps by the window size so
              ;; a multi-episode window keeps N× the single-episode budget.
              applied (when result
                        (extract/process-extraction!
                         store result nil
                         (cond-> {}
                           max-entities  (assoc :max-entities  (* max-entities n))
                           max-relations (assoc :max-relations (* max-relations n))
                           max-nodes     (assoc :max-nodes max-nodes)
                           max-edges     (assoc :max-edges max-edges))))]
          (mulog/debug ::l2-batch-extracted :session-id session-id
                       :new-episodes (count eps) :nodes (:nodes applied 0) :edges (:edges applied 0))
          {:calls 1 :new-episodes (count eps)
           :nodes (:nodes applied 0) :edges (:edges applied 0) :max-id max-id})))
    {:calls 0 :no-extract-fn true :max-id after-id}))

;; =====================================================
;; Retention & Archive (P4)
;; =====================================================

(defn keep!
  "Pin an L2/L3 entry against the retention sweep. Sets `keep_flag = 1`.

  Args:
    manager   — MemoryManager
    layer     — :l2 or :l3
    entry-id  — stable cross-layer id (the entry's :id, not the SQL row's
                autoincrement)

  Returns true when the entry existed."
  [manager layer entry-id]
  (policy/mark-keep! (:ds manager) layer entry-id (:user-id manager) true))

(defn unkeep!
  "Reverse of `keep!`."
  [manager layer entry-id]
  (policy/mark-keep! (:ds manager) layer entry-id (:user-id manager) false))

(defn archive!
  "Mark an L2/L3 entry as archived. Excluded from default recall but
  still retrievable with `:include-archived true`."
  [manager layer entry-id]
  (policy/mark-archived! (:ds manager) layer entry-id (:user-id manager) true))

(defn unarchive!
  [manager layer entry-id]
  (policy/mark-archived! (:ds manager) layer entry-id (:user-id manager) false))

(defn sweep-l2!
  "Run the L2 retention sweep against this manager's database.

  Tombstones episodes older than `:retention-days` (default 30) that
  do NOT have `keep_flag = 1`. Idempotent — already-tombstoned rows
  are skipped. Returns the count of rows tombstoned this call."
  [manager & {:keys [retention-days]}]
  (policy/sweep-l2! (:ds manager)
                    :retention-days (or retention-days policy/default-l2-retention-days)
                    :user-id (:user-id manager)))

(defn start-sweeper!
  "Start the scheduled L2 retention sweeper for this manager.

  Default cadence is every 6h with a 30-day retention window. Off by
  default — call this explicitly to enable. Returns a sweeper handle.

  Options forwarded to policy/start-sweeper!:
    :interval-ms     — sweep cadence (default 6h)
    :retention-days  — TTL for non-kept episodes (default 30)
    :run-on-start?   — run one sweep immediately (default false)

  Idempotent: returns the existing sweeper if one is already running
  for this manager."
  [manager & opts]
  (let [!sweeper (or (some-> manager :!sweeper)
                     (let [a (atom nil)]
                       ;; Stash on the manager via a side-channel — the
                       ;; defrecord doesn't include this field but the
                       ;; record is itself a map so we can assoc on it
                       ;; safely for control-plane state.
                       (swap! (:!working manager) assoc :!sweeper a)
                       a))]
    (or @!sweeper
        (let [s (apply policy/start-sweeper! (:ds manager)
                       :user-id (:user-id manager)
                       (mapcat identity opts))]
          (reset! !sweeper s)
          s))))

(defn stop-sweeper!
  "Stop the scheduled sweeper for this manager. Idempotent."
  [manager]
  (when-let [!sweeper (some-> manager :!working deref :!sweeper)]
    (when-let [s @!sweeper]
      (policy/stop-sweeper! s)
      (reset! !sweeper nil))))

;; =====================================================
;; Audit / Explain (P4)
;; =====================================================

(defn explain
  "Return the entries that informed `turn-id` of `agent-id` within
  `session-id`. When `agent-id` is nil the lookup falls back to any
  agent matching (session-id, turn-id) — accept this only for legacy
  callers that don't yet thread agent identity.

  Shape:
    {:session-id ... :agent-id ... :turn-id ... :user-id ...
     :entries [{:audit row :entry hydrated-entry-or-nil} ...]
     :prompt-bytes <total>
     :recall-query nil-for-now}

  When an entry has been hard-deleted, `:entry` is nil but the audit
  row remains so the addressing tuple is preserved."
  ([manager session-id turn-id]
   (audit/explain-turn (:ds manager) (:store manager) session-id nil turn-id))
  ([manager session-id agent-id turn-id]
   (audit/explain-turn (:ds manager) (:store manager) session-id agent-id turn-id)))

(defn explain-session
  "Return per-(agent, turn) explanations for the entire session, sorted
  by total-turns ascending so the rendered timeline matches the actual
  ask order across root + sub-agents.

  Each turn entry: {:agent-id :turn-id :total-turns :entries
                    :prompt-bytes}."
  [manager session-id]
  (audit/explain-session (:ds manager) (:store manager) session-id))
