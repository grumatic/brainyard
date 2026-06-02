;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.memory.core.recall-v2
  "Generalized recall through the IMemoryStore protocol.

  Replaces the L2/L3-only `recall.clj` pipeline with a layer-agnostic
  version that queries each requested layer in parallel via
  `read-entries`, RRF-merges the per-layer results, and renders a
  layered briefing string suitable for injection into
  `:context-briefing` on the CoAct signature.

  Default layers: [:l1 :l2 :l3]. L1 (operator-managed and user
  context, distinguished by `:kind`) is a first-class recall source
  rather than invisible side-data.

  The legacy `recall.clj` and the legacy
  `MemoryManager.contextual-recall` shape are preserved for backward
  compatibility (see manager.clj). Callers wanting layered output use
  this namespace's `recall-layered` directly."
  (:require [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.memory.interface.protocol :as proto]
            [ai.brainyard.memory.core.recall :as recall-v1]
            [ai.brainyard.memory.core.fts :as fts]))

;; =====================================================
;; Defaults
;; =====================================================

(def default-layers [:l1 :l2 :l3])

(def default-weights
  "Per-layer RRF weights. L1 (system + user context) ranks lowest
  because it is already in the system prompt; L2/L3 surface more
  aggressively."
  {:l1 0.3
   :l2 0.4
   :l3 0.6})

(def default-per-layer-limit 8)

;; =====================================================
;; Per-layer query builders
;; =====================================================

(defn- read-l1
  "Reads ALL L1 entries (both :system-context and :user-context) for the
  given session. L1 is small, session-scoped, and always part of the
  per-turn context — there's no query filtering."
  [store _query _keywords {:keys [session-id limit]}]
  (let [all (proto/read-entries store :l1
                                (cond-> {}
                                  session-id (assoc :session-id session-id))
                                {:limit (or limit 100)})]
    (vec (take (or limit default-per-layer-limit) all))))

(defn- read-l2
  [store query _keywords {:keys [session-id limit match]}]
  (proto/read-entries store :l2
                      (cond-> {:text query}
                        session-id (assoc :session-id session-id)
                        match      (assoc :match match))
                      {:limit (or limit default-per-layer-limit)}))

(defn- read-l3
  [store query _keywords {:keys [limit match]}]
  (proto/read-entries store :l3
                      (cond-> {:text query}
                        match (assoc :match match))
                      {:limit (or limit default-per-layer-limit)}))

(defn- read-layer
  [layer store query keywords opts]
  (try
    (case (keyword layer)
      :l1 (read-l1 store query keywords opts)
      :l2 (read-l2 store query keywords opts)
      :l3 (read-l3 store query keywords opts)
      [])
    (catch Exception e
      (mulog/warn ::recall-layer-failed :layer layer :error (ex-message e))
      [])))

;; `:match` is the FTS multi-word mode: :or (default), :and, :phrase.
;; See fts/normalize-fts-query for semantics.

;; =====================================================
;; Briefing render
;; =====================================================

(defn- truncate
  [s n]
  (if (and (string? s) (> (count s) n))
    (str (subs s 0 n) "…")
    s))

(defn- render-line
  [{:keys [id layer kind content]}]
  (let [tag (case layer
              :l1 (case kind
                    :system-context "[system]"
                    :user-context   "[user]"
                    "[l1]")
              :l2 (str "[" (when kind (name kind)) "]")
              :l3 (str "[fact:" (when kind (name kind)) "]")
              "[?]")]
    (str "- " tag " " (truncate content 200) "  (#" id ")")))

(defn render-briefing
  "Render a layered briefing string for inclusion in the prompt.

  Sections, in order:
    System Context  (L1, kind :system-context)
    User Context    (L1, kind :user-context)
    Recent Events   (L2)
    What We Know    (L3)

  Each line carries an entry-id so the agent can request expansion."
  [layers->entries & {:keys [budget] :or {budget 4000}}]
  (let [l1   (get layers->entries :l1)
        sys  (filterv #(= :system-context (:kind %)) l1)
        usr  (filterv #(= :user-context   (:kind %)) l1)
        sections [["System Context" sys]
                  ["User Context"   usr]
                  ["Recent Events"  (get layers->entries :l2)]
                  ["What We Know"   (get layers->entries :l3)]]
        chunks (for [[label entries] sections
                     :when (seq entries)]
                 (str "## " label "\n"
                      (str/join "\n" (map render-line entries))))
        joined (str/join "\n\n" chunks)]
    (truncate joined budget)))

;; =====================================================
;; Public API
;; =====================================================

(defn recall-layered
  "Run a layered recall through the unified store.

  Required:
    :store    — IMemoryStore instance
    :query    — search string

  Optional:
    :layers      — vector of layer keywords (default [:l1 :l2 :l3])
    :weights     — per-layer RRF weights map (default `default-weights`)
    :limit       — per-layer cap (default 8)
    :total-limit — cap on combined output (default 20)
    :session-id  — restrict L1/L2 to a session
    :budget      — briefing string char budget (default 4000)
    :rrf-k       — RRF smoothing constant (default 60)

  Returns:
    {:layers   {:l1 [...] :l2 [...] :l3 [...]}
     :combined [...]              ; entries with :_rrf_score, :_layer
     :briefing \"…\"               ; render-briefing output
     :keywords [...]               ; FTS keywords extracted from query
     :query    \"…\"}"
  [& {:keys [store query layers weights limit total-limit session-id budget rrf-k match]
      :or   {layers default-layers
             weights default-weights
             limit default-per-layer-limit
             total-limit 20
             budget 4000
             rrf-k 60
             match :or}}]
  (when-not store
    (throw (ex-info "recall-layered: :store is required" {:query query})))
  (let [keywords (when query
                   (try (vec (fts/extract-keywords query :max-keywords 12))
                        (catch Exception _ [])))
        per-layer-opts {:limit limit :session-id session-id :match match}
        ;; Read each layer in parallel
        per-layer (->> layers
                       (pmap (fn [layer]
                               [(keyword layer)
                                (read-layer layer store query keywords per-layer-opts)]))
                       (into {}))
        ;; Build the RRF input shape
        rrf-input (into {}
                        (for [[layer entries] per-layer
                              :when (seq entries)]
                          [layer {:results entries
                                  :weight  (get weights layer 0.5)}]))
        combined  (when (seq rrf-input)
                    (vec (take total-limit
                               (recall-v1/reciprocal-rank-fusion rrf-input :k rrf-k))))
        briefing  (render-briefing per-layer :budget budget)]
    (mulog/debug ::recall-layered
                 :layers (vec layers)
                 :hits-per-layer (into {} (for [[k v] per-layer] [k (count v)]))
                 :combined-count (count (or combined []))
                 :briefing-bytes (count (or briefing "")))
    {:layers   per-layer
     :combined (or combined [])
     :briefing (or briefing "")
     :keywords keywords
     :query    query}))

(defn recall-flat
  "Backward-compat shape mirroring `recall.recall-pipeline`'s return:
    {:facts [...] :episodes [...] :combined [...] :keywords [...] :query q}

  Used by `MemoryManager.contextual-recall` so legacy callers see the
  same keys while the underlying engine is now layer-agnostic."
  [& opts]
  (let [{:keys [layers combined keywords briefing query]} (apply recall-layered opts)]
    {:facts    (or (get layers :l3) [])
     :episodes (or (get layers :l2) [])
     :combined combined
     :briefing briefing
     :keywords keywords
     :query    query
     ;; Expose the new layered split for callers that want it.
     :layers   layers}))
