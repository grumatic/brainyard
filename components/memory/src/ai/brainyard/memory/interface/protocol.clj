;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.interface.protocol)

;; =====================================================
;; Memory Types
;; =====================================================

(def episode-types #{:conversation :action :observation :thought :evaluation :error})
(def fact-types #{:summary :fact :preference :entity :concept :relationship})

(def layers #{:l1 :l2 :l3})
(def entry-kinds #{:system-context :user-context :episode :fact :observation})

;; =====================================================
;; Unified Memory Protocol
;; =====================================================
;;
;; The MemoryManager facade. Data-plane reads and writes go through
;; `IMemoryStore` (below) on `(:store manager)` — see
;; `memory.interface/write-entry` and `read-entries`.
;;
;; Working / episodic / semantic accessors and `compact` were removed
;; in the unified-store refactor; only cross-layer recall remains as
;; an orchestration method.

(defprotocol UnifiedMemory
  "Cross-layer memory orchestration on top of the unified store."

  (contextual-recall [this query opts]
    "Search across the three memory layers (L1, L2, L3) and
    return the RRF-merged combined result.

    Options:
      :session-id  - Restrict L1/L2 reads to this session
      :limit       - Per-layer cap (default 10)
      :total-limit - Cap on combined result (default 20)
      :weights     - Legacy {:episodic w :semantic w} or new
                     {:l1 w :l2 w :l3 w} per-layer weight map
      :budget      - Briefing budget in chars (default 4000)
      :agent-id    - Required with :turn-id to qualify the audit row
                     (per-agent turn-id is unique only within an agent)
      :turn-id     - Per-agent turn counter. With :session-id and
                     :agent-id, writes one `memory_audit` row per entry
                     that landed in the briefing for later
                     `memory/explain` queries.
      :total-turns - Optional session-cumulative counter; recorded
                     alongside the audit row for cross-agent ordering.
      :match       - Multi-word FTS mode: :or (default), :and, :phrase

    Returns: Vector of entries with :_rrf_score and :_layer."))

;; =====================================================
;; Memory Manager Lifecycle Protocol
;; =====================================================

(defprotocol MemoryManagerLifecycle
  "Lifecycle management for memory managers."

  (initialize [this]
    "Initialize the memory manager (create tables, load state).")

  (shutdown [this]
    "Shutdown the memory manager (cleanup resources).")

  (get-stats [this]
    "Get memory system statistics.

    Returns:
      {:episodes n
       :semantic-facts n
       :working-memory-keys n
       :schema-version string}"))

;; =====================================================
;; Unified Memory Store Protocol (v2)
;; =====================================================
;;
;; A single addressable surface across the L1/L2/L3 memory layers.
;; Each entry is an EDN map with the schema:
;;
;;   {:id           uuid or string (stable cross-layer)
;;    :layer        :l1 | :l2 | :l3
;;    :kind         :system-context | :user-context | :episode | :fact | :observation
;;    :content      canonical text, FTS-indexable
;;    :data         structured payload (optional)
;;    :tags         set of strings (e.g. \"tool:bash\" \"topic:deploy\")
;;    :sources      vector of provenance maps ({:type :id})
;;    :session-id   string
;;    :user-id      string
;;    :created-at   instant
;;    :ttl          nil = indefinite, else duration
;;    :confidence   0.0..1.0
;;    :access-count integer
;;    :keep         boolean   ;; pinned, retained beyond TTL sweep
;;    :archived     boolean   ;; excluded from default recall
;;    :tombstoned   boolean   ;; soft-deleted, audit-retained}

(defprotocol IMemoryStore
  "Unified store across the L1/L2/L3 memory layers.

  Implementations dispatch by layer. L1 is an in-memory store for
  session-scoped context entries (`:system-context`, `:user-context`);
  L2 wraps the episodic SQLite table; L3 wraps `semantic_facts`."

  (write-entry [this layer entry]
    "Insert or update an entry in the given layer. Entries with a stable
     :id replace any prior entry sharing that id (upsert by entry-id);
     entries without :id are appended with a generated id.

     Returns the persisted entry (with :id, :created-at populated).")

  (read-entries [this layer query opts]
    "Read entries from a layer matching the query map.

     query keys vary by layer:
       :l1  — {:kind :session-id :field :section :id ...}
       :l2  — {:session-id :kind :tags :time-after :time-before :text ...}
       :l3  — {:user-id :kind :tags :text :min-confidence ...}

     opts:
       :limit       — max results (default 20)
       :consistent  — if true, return a consistent snapshot (avoid TOCTOU
                      during multi-step prompt assembly)
       :include-archived  — include :archived true entries (default false)

     Returns a vector of entries in implementation-defined order
     (most-relevant-first or most-recent-first per layer).")

  (promote [this entry from-layer to-layer]
    "Copy an entry from `from-layer` into `to-layer`, populating
     :sources [{:type :promotion :id <orig-id>}]. The source entry
     is left intact; callers decide whether to subsequently `forget` it.

     Returns the new entry in `to-layer`.")

  (forget [this layer entry-id]
    "Remove an entry by id. Implementations may write a tombstone rather
     than delete the row (audit retention). Returns truthy when the
     entry existed; nil/false otherwise.")

  (consolidate-layer [this from-layer policy]
    "Run a reduction pass over entries in `from-layer` according to
     `policy`, producing higher-layer entries with provenance back to
     the source rows. The deterministic heuristic implementation groups
     by tag-set and time window; an LLM-backed reducer is reserved but
     deferred (see CR-MEM-07) — `:llm` currently falls back to heuristic
     with a warning.

     Currently supported `from-layer`s:
       :l2 → :l3 (UnifiedStore — episodes to semantic facts)
       :l1          → no-op (return zero summary)
     Other layers throw.

     policy keys (all optional unless noted):
       :session-id   — restrict to this session
       :window-ms    — bucket size in ms (default 600000 = 10 min)
       :min-batch    — minimum events per batch (default 3)
       :reducer      — :heuristic (default) | :llm (deferred — see CR-MEM-07)

     The store implementation supplies `:user-id` and `:ds` from its
     own record fields — callers do not pass them.

     Returns a summary map:
       {:from-layer :l2 :to-layer :l3
        :produced n :consumed n :auto-kept n :batches [...]}

     `:auto-kept` is the count of source episodes pinned against the L2
     retention sweep (so the L3 fact's `:sources` chain stays valid)."))

;; =====================================================
;; Helper Functions
;; =====================================================

(defn valid-episode-type?
  "Check if a type is a valid episode type."
  [t]
  (contains? episode-types (keyword t)))

(defn valid-fact-type?
  "Check if a type is a valid fact type."
  [t]
  (contains? fact-types (keyword t)))

(defn valid-layer?
  "Check if a value is a valid memory layer."
  [l]
  (contains? layers (keyword l)))

(defn valid-entry-kind?
  "Check if a value is a valid entry kind."
  [k]
  (contains? entry-kinds (keyword k)))
