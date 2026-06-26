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
  (:require [ai.brainyard.memory.core.manager :as manager]
            [ai.brainyard.memory.core.fts :as fts]
            [ai.brainyard.memory.core.embed :as embed]
            [ai.brainyard.memory.core.extract :as extract]
            [ai.brainyard.memory.core.unified-store :as us]
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

  Idempotent: calling twice on the same manager returns the existing
  capture handle."
  [manager & {:as opts}]
  (when-not manager
    (throw (ex-info "start-capture! requires a memory manager" {})))
  (let [!cap (:!capture manager)]
    (or @!cap
        (let [d   (apply capture-dispatcher/start! (mapcat identity opts))
              ;; CR-MEM-22: when the manager carries an extract-fn, run the
              ;; graph-extraction sidecar and feed it persisted L2 entries
              ;; via the sidecar's :on-write seam.
              ex  (when-let [ef (:extract-fn manager)]
                    (capture-extractor/start! (:store manager) ef))
              s   (apply capture-sidecar/start! (:store manager) d
                         (when ex [:on-write #(capture-extractor/enqueue! ex %)]))
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
