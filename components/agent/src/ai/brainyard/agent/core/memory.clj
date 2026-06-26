;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.memory
  "Agent memory integration — layer-aware recall/remember over the
   unified IMemoryStore.

   `recall` reads from each requested memory layer with layer-specific
   query options. `remember` writes per-layer entries with layer-specific
   shapes. Cross-layer ranking, briefing rendering, and capture-pipeline
   coordination live in the memory component itself; this namespace is a
   thin per-layer facade for the agent runtime.

   Depends on the memory component (ai.brainyard.memory.interface)."
  (:require [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.clj-llm.interface :as llm]
            [ai.brainyard.agent.core.config :as config]))

;; ============================================================================
;; Read/write opts split
;; ============================================================================

(def ^:private read-opts-keys
  "IMemoryStore opts (everything else in the per-layer map is treated as
   a query key)."
  #{:limit :consistent :include-archived :include-tombstoned})

(defn- split-read-opts
  "Partition a per-layer recall map into [query opts]."
  [m]
  [(apply dissoc m read-opts-keys)
   (select-keys m read-opts-keys)])

(defn- ->entries
  "Coerce a per-layer remember value to a sequence of entry maps. A
   single map is wrapped as a one-element sequence."
  [v]
  (cond
    (nil? v)        []
    (map? v)        [v]
    (sequential? v) v
    :else
    (throw (ex-info "remember: per-layer value must be a map or seq of maps"
                    {:value v}))))

;; ============================================================================
;; Recall — per-layer reads
;; ============================================================================

(defn recall
  "Recall from the unified memory store.

   Two modes:

   1. Per-layer reads — provide any of `:l1`, `:l2`, `:l3`. Each value is
      a map carrying that layer's query keys plus optional IMemoryStore
      opts (`:limit`, `:include-archived`, `:include-tombstoned`,
      `:consistent`). Returns
        {:l1 [...] :l2 [...] :l3 [...]}
      for the layers that were read.

   2. Cross-layer recall (fallback) — when none of `:l1`/`:l2`/`:l3` is
      provided, delegates to `memory/contextual-recall`, which queries
      every layer and RRF-merges the result. The `:query` kwarg is
      passed as the recall query; all other kwargs forward to
      `contextual-recall` (see its docstring for the full set:
      `:session-id`, `:agent-id`, `:turn-id`, `:total-turns`, `:limit`,
      `:total-limit`, `:weights`, `:budget`, `:match`).

   Per-layer recognized keys (subset, see IMemoryStore for the full
   schema):

     :l1 (in-memory, session-scoped)
       :session-id, :kind, :field, :section, :id,
       :limit, :include-archived

     :l2 (episodic SQLite + FTS5)
       :text, :session-id, :kind, :tags,
       :time-after, :time-before, :match (:or | :and | :phrase), :id,
       :limit, :include-archived, :include-tombstoned

     :l3 (semantic facts SQLite + FTS5)
       :text, :kind, :min-confidence,
       :match (:or | :and | :phrase), :id,
       :limit, :include-archived, :include-tombstoned"
  [memory-manager & {:keys [l1 l2 l3 query] :as opts}]
  (when memory-manager
    (if (or l1 l2 l3)
      (let [read-layer
            (fn [layer m]
              (let [[q rd-opts] (split-read-opts m)]
                (try
                  (vec (mem/read-entries memory-manager layer q rd-opts))
                  (catch Exception e
                    (mulog/warn ::layer-read-failed
                                :layer layer
                                :message (ex-message e))
                    []))))
            results (cond-> {}
                      l1 (assoc :l1 (read-layer :l1 l1))
                      l2 (assoc :l2 (read-layer :l2 l2))
                      l3 (assoc :l3 (read-layer :l3 l3)))]
        (mulog/debug ::memory-recall-completed
                     :mode :per-layer
                     :counts (into {} (map (fn [[k v]] [k (count v)])) results))
        results)
      (let [cr-opts (dissoc opts :query)
            result  (apply mem/contextual-recall memory-manager query
                           (mapcat identity cr-opts))]
        (mulog/debug ::memory-recall-completed
                     :mode :contextual
                     :count (count (or result [])))
        result))))

;; ============================================================================
;; Remember — per-layer writes
;; ============================================================================

(defn remember
  "Persist entries into the unified store, layer by layer.

   Each layer key (`:l1`/`:l2`/`:l3`) accepts either a single entry map
   or a sequence of entry maps. When a layer key is omitted nothing is
   written for that layer.

   Layer-specific expected entry keys (see IMemoryStore protocol for
   the complete schema; common fields like :tags, :sources, :keep,
   :archived, :ttl, :data, :metadata are valid on every layer):

     :l1 (in-memory, session-scoped)
       :session-id (REQUIRED), :kind (:system-context | :user-context),
       :content, :data {:field :section}

     :l2 (episodic)
       :session-id, :kind (:conversation | :action | :observation |
       :thought | :evaluation | :error), :role, :content

     :l3 (semantic facts)
       :kind (:summary | :fact | :preference | :entity | :concept |
       :relationship), :content, :confidence, :source

   Returns: {:l1 [...persisted] :l2 [...] :l3 [...]} for the layers
            that were written."
  [memory-manager & {:keys [l1 l2 l3]}]
  (when memory-manager
    (let [write-layer
          (fn [layer entries]
            (try
              (mapv #(mem/write-entry memory-manager layer %)
                    (->entries entries))
              (catch Exception e
                (mulog/warn ::layer-write-failed
                            :layer layer
                            :message (ex-message e))
                [])))
          results (cond-> {}
                    l1 (assoc :l1 (write-layer :l1 l1))
                    l2 (assoc :l2 (write-layer :l2 l2))
                    l3 (assoc :l3 (write-layer :l3 l3)))]
      (mulog/debug ::memory-remember-completed
                   :counts (into {} (map (fn [[k v]] [k (count v)])) results))
      results)))

;; ============================================================================
;; Memory Manager Lifecycle
;; ============================================================================

(defn- default-memory-base-path
  "Resolve the default memory base-path per the scope contract: `memory/`
   is user-scope only (see `core.config/subdir-scope-policy`). We compute it
   via `brainyard-subdir` so the user-home dir comes from the live dirs map
   rather than a hardcoded '~' literal that won't expand in SQLite JDBC URLs."
  []
  (let [dirs (config/init-dirs!)]
    (or (config/brainyard-subdir dirs "memory" :user)
        "~/.brainyard/memory")))

(defn- graph-provider-opts
  "Build the context-graph provider fns (CR-MEM-21/22) from config, or `{}`.

   Off unless `:enable-graph-memory` is true AND the relevant model is
   configured (`:graph-embed-model` / `:graph-extract-model`). Each is an
   LM string resolved via `parse-lm-str`; an unresolvable / capability-
   lacking model simply yields no fn, leaving the graph storage-only. The
   fns are lazy — they cost nothing until the extractor/recall invokes them.
   Resolution never throws (parse-lm-str returns nil on failure)."
  []
  (if-not (config/get-config :enable-graph-memory)
    {}
    (let [embed-lm   (some-> (config/get-config :graph-embed-model) llm/parse-lm-str)
          extract-lm (some-> (config/get-config :graph-extract-model) llm/parse-lm-str)]
      (cond-> {}
        embed-lm   (assoc :embed-fn   (mem/make-embed-fn embed-lm :model (:model embed-lm)))
        extract-lm (assoc :extract-fn   (mem/make-extract-fn extract-lm)
                          ;; reuse the extraction chat model for community
                          ;; summaries (CR-MEM-24) — same capability class
                          :summarize-fn (mem/make-summarize-fn extract-lm))))))

(defn create-memory-manager
  "Create a memory manager for an agent, if the memory component is available.

   The `memory/` subdir is user-scope only by policy (see
   `core.config/subdir-scope-policy`). Callers may still pass
   `:base-path`/`:db-path` for tests or special cases — no scope check is
   enforced at this layer to keep the test/in-memory paths simple — but
   production callers should let the default fire.

   Parameters:
     user-id - User identifier

   Options:
     :in-memory - Use in-memory database (default: false; file-backed)
     :base-path - Base directory for database files (default: user-scope
                  `~/.brainyard/memory/`)
     :db-path   - Custom database path

   Returns: MemoryManager instance or nil if memory component unavailable"
  [user-id & {:keys [in-memory base-path db-path]}]
  (apply mem/create-memory-manager user-id
         :in-memory (boolean in-memory)
         :base-path (or base-path (default-memory-base-path))
         :db-path db-path
         (mapcat identity (graph-provider-opts))))
