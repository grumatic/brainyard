;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.manager
  "MemoryManager: a thin facade over the unified IMemoryStore.

  All read/write data-plane access goes through the store
  (see core.unified-store and the IMemoryStore protocol). The manager
  itself only carries:
    - the per-user datasource and store reference
    - the capture-pipeline handle (when capture is enabled)
    - lifecycle hooks (initialize / shutdown / get-stats)
    - cross-layer recall (contextual-recall) which orchestrates
      reads across layers, RRF-merges, and (optionally) writes audit
      rows

  Working / episodic / semantic accessors used to live here. They have
  been removed in favor of `proto/write-entry` and `proto/read-entries`
  on the embedded `:store`. See docs/architecture/memory.md."
  (:require [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.memory.core.sqlite :as sqlite]
            [ai.brainyard.memory.interface.protocol :as proto]
            [ai.brainyard.memory.core.episodic :as episodic]
            [ai.brainyard.memory.core.semantic :as semantic]
            [ai.brainyard.memory.core.recall-v2 :as recall-v2]
            [ai.brainyard.memory.core.audit :as audit]
            [ai.brainyard.memory.core.unified-store :as us]
            [ai.brainyard.memory.core.capture.dispatcher :as capture-dispatcher]
            [ai.brainyard.memory.core.capture.sidecar :as capture-sidecar]
            [ai.brainyard.memory.core.capture.extractor :as capture-extractor]))

;; =====================================================
;; MemoryManager record
;; =====================================================

(defrecord MemoryManager [user-id ds store !capture db-path extract-fn]
  proto/UnifiedMemory

  ;; Cross-layer recall.
  ;;
  ;; Routes through recall-v2 to query layers L1, L2, and L3 in
  ;; parallel and RRF-merge the per-layer results. When the caller
  ;; passes `:turn-id` in opts (qualified by `:agent-id`), writes one
  ;; `memory_audit` row per entry that landed in the briefing so
  ;; `(memory/explain session-id agent-id turn-id)` can replay the
  ;; prompt assembly later. `:total-turns` is recorded alongside for
  ;; cross-agent ordering.
  (contextual-recall [_ query opts]
    (let [{:keys [weights limit session-id layers budget total-limit
                  agent-id turn-id total-turns match]
           :or   {limit 10 total-limit 20 budget 4000}} opts
          merged-weights (cond-> recall-v2/default-weights
                           (:semantic weights) (assoc :l3 (:semantic weights))
                           (:episodic weights) (assoc :l2 (:episodic weights)))
          result (recall-v2/recall-flat
                  :store store
                  :query query
                  :session-id session-id
                  :layers (or layers recall-v2/default-layers)
                  :weights merged-weights
                  :limit limit
                  :total-limit total-limit
                  :budget budget
                  :match (or match :or))]
      (when (and turn-id session-id)
        (audit/record-prompt!
         {:ds ds
          :user-id user-id
          :session-id session-id
          :agent-id agent-id
          :turn-id turn-id
          :total-turns total-turns}
         (:combined result)))
      (:combined result)))

  proto/MemoryManagerLifecycle

  (initialize [_]
    (sqlite/init-schema! ds)
    (mulog/info ::memory-manager-initialized :user-id user-id))

  (shutdown [_]
    ;; Safety net: if a capture pipeline is still attached, stop it
    ;; before the manager is torn down. The agent close-path stops
    ;; capture when the last referencing agent closes; this catches
    ;; the case where the manager is shut down directly (test
    ;; fixtures, explicit teardown).
    (when-let [{:keys [dispatcher sidecar extractor]} (some-> !capture deref)]
      (try
        (capture-sidecar/stop! sidecar)
        (capture-dispatcher/stop! dispatcher)
        (capture-sidecar/await-drain! sidecar 1000)
        (when extractor
          (capture-extractor/stop! extractor)
          (capture-extractor/await-drain! extractor 1000))
        (reset! !capture nil)
        (catch Exception e
          (mulog/warn ::capture-stop-on-shutdown-failed
                      :user-id user-id :error (ex-message e)))))
    (mulog/info ::memory-manager-shutdown :user-id user-id))

  (get-stats [_]
    {:episodes       (episodic/count-episodes ds :user-id user-id)
     :semantic-facts (semantic/count-facts ds :user-id user-id)
     :schema-version (sqlite/get-schema-version ds)}))

;; =====================================================
;; Factory
;; =====================================================

(defn create-memory-manager
  "Create a unified memory manager for a user.

  Parameters:
    user-id - User identifier

  Options:
    :base-path  - Base directory for database files (default: \"~/.brainyard/memory\")
    :db-path    - Custom database path (overrides base-path)
    :in-memory  - Use in-memory database (for testing)
    :embed-fn   - (fn [texts] -> [[float…]…]) for the CR-MEM-21 vector index
                  (nil ⇒ FTS-only recall)
    :extract-fn - (fn [text] -> {:entities [...] :relations [...]}) for the
                  CR-MEM-22 graph-extraction sidecar (nil ⇒ no extraction).
                  Started by `start-capture!` when present.
    :summarize-fn - (fn [text] -> string) for CR-MEM-24 community summaries
                  (nil ⇒ deterministic templated summary).

  Returns: MemoryManager instance"
  [user-id & {:keys [base-path db-path in-memory embed-fn extract-fn summarize-fn]
              :or {base-path "~/.brainyard/memory" in-memory false}}]
  (let [path (cond
               in-memory ":memory:?cache=shared"
               db-path   db-path
               :else     (sqlite/db-path base-path user-id))
        ds       (sqlite/create-datasource path)
        !capture (atom nil)
        store    (us/create-unified-store :user-id user-id :ds ds
                                          :embed-fn embed-fn :summarize-fn summarize-fn)]

    (sqlite/init-schema! ds)
    (mulog/info ::memory-manager-created :user-id user-id :path path
                :graph-extraction (boolean extract-fn))

    (->MemoryManager user-id ds store !capture path extract-fn)))
