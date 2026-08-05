;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.catalog
  "Refreshed-from-provider overlay on top of the baked model catalog.

   ## Why an overlay rather than a replacement

   `providers/model-catalog` is hand-curated and baked into the binary. That
   is the only thing that works offline, on first run, and for a provider the
   user has no credentials for — so it stays, and it stays authoritative for
   everything a provider's API cannot tell us.

   A provider's list is NOT a catalog. `GET /v1/models` also returns
   embeddings, TTS, transcription and image models; models served only by a
   different endpoint (OpenAI's `-pro` tier answers \"not a chat model\" on
   /v1/chat/completions); and deprecated ids that still list. Nothing in the
   response says which of those a chat client can drive, what a model is good
   for, or whether it rejects `temperature`. Those were each established by
   probing, and they live in the baked catalog.

   So the split is:

     provider API  ->  WHICH IDS EXIST        (refreshable, changes weekly)
     baked catalog ->  :curated-rank,
                       :description, :region  (human judgement, changes rarely)

   The overlay therefore carries **ids only**. It can retire a curated model
   and it can surface a newly-released one as usable, but it can never invent
   curation — so a `whisper-1` or a `gpt-5-pro` cannot reach the model picker
   by way of a refresh.

   ## Safety rules the merge obeys

   1. A provider absent from the overlay is passed through untouched. Offline,
      first-run and no-credentials all take this path.
   2. An overlay entry with an empty `:models` set is IGNORED, never applied.
      A failed fetch that returned `{}` must not empty the catalog.
   3. Only providers that can actually enumerate models are overlayable
      (see `overlayable?`). `claude-code`, `acp`, `apple-fm` and `free-llm`
      have no list endpoint; an overlay for them would be a bug, not data.

   This namespace is pure: no IO, no path knowledge, no network. The store
   layer hands it maps and it hands back a merged catalog, which is what makes
   the merge testable without a provider or a filesystem. Persistence and
   fetching live in `catalog-store` / `catalog-fetch`."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def schema-version 1)

(def non-enumerable
  "Providers with no model-list endpoint, so nothing can legitimately overlay
   them.

   `claude-code` and `acp` drive an external CLI whose model names are aliases
   it resolves itself; `apple-fm` is a single on-device model; `free-llm` is
   whatever arbitrary endpoint the user pointed `FREELLM_BASE_URL` at, and its
   `auto` entry deliberately means \"let the backend pick\"."
  #{:claude-code :acp :apple-fm :free-llm})

(defn overlayable?
  "True when `provider` is one a refresh may legitimately replace the id set
   for."
  [provider]
  (not (contains? non-enumerable provider)))

;; ============================================================================
;; Overlay state
;; ============================================================================

(defonce ^:private !overlay
  ;; {provider {:models #{id…} :fetched-at inst :source str}}
  (atom {}))

(defn overlay
  "The current overlay map, provider -> record."
  []
  @!overlay)

(defn- usable-entry?
  "An overlay entry is applied only when it names a provider that can be
   enumerated AND carries at least one model. Rule 2 above: a fetch that
   errored into an empty set must not be mistaken for 'this provider now
   serves nothing'."
  [provider entry]
  (and (overlayable? provider)
       (seq (:models entry))))

(defn set-overlay!
  "Replace the whole overlay. Entries that are not usable are dropped here
   rather than at merge time, so `overlay` always reflects what is actually in
   force."
  [m]
  (reset! !overlay (into {} (filter (fn [[p e]] (usable-entry? p e)) m))))

(defn put-overlay!
  "Add or replace one provider's entry. Unusable entries are ignored, and
   ignoring is not silent failure — a caller that fetched nothing should log
   it; here we simply refuse to let it narrow the catalog."
  [provider entry]
  (if (usable-entry? provider entry)
    (swap! !overlay assoc provider entry)
    (overlay)))

(defn clear-overlay!
  "Drop the overlay entirely, reverting every provider to the baked catalog."
  []
  (reset! !overlay {}))

;; ============================================================================
;; Merge
;; ============================================================================

(defn merge-provider
  "Merge one provider's baked entry vector against an overlay entry.

   Returns entries in a stable order: the baked ones first, in their original
   order (so curated rank and hand-ordering survive), then newly discovered
   ids sorted. Discovered entries carry `:discovered? true` and deliberately
   NO `:curated-rank`, which is what keeps them out of the picker while making
   them usable and listable.

   A `:partial?` entry is ADDITIVE ONLY — nothing is retired from it. That is
   not a hedge, it is a correctness requirement: a Bedrock fetch enumerates
   one region, and the catalog deliberately carries models served in only some
   regions (`:region \"us-east-1\"` pins exist for exactly that reason). A
   refresh run from ap-northeast-2 sees none of those us-east-1-only models,
   and treating their absence as retirement would delete a working model from
   the catalog because of where the user happened to be standing. One region's
   inventory cannot prove a model is globally gone."
  [baked-entries {:keys [models partial?]}]
  (let [live    (set models)
        baked   (vec baked-entries)
        known   (into #{} (map :model) baked)
        kept    (if partial?
                  baked
                  (filterv #(contains? live (:model %)) baked))
        added   (->> (set/difference live known)
                     sort
                     (mapv (fn [id] {:model id :discovered? true})))]
    (into kept added)))

(defn merge-catalog
  "Apply `overlay-map` to `baked-catalog`, returning a catalog of the same
   shape.

   Providers absent from the overlay pass through untouched (rule 1). The
   result preserves the baked provider ordering, since `model-catalog` is an
   array-map whose order the pickers rely on."
  [baked-catalog overlay-map]
  (reduce-kv
   (fn [acc provider entries]
     (let [entry (get overlay-map provider)]
       (assoc acc provider
              (if (usable-entry? provider entry)
                (merge-provider entries entry)
                entries))))
   (empty baked-catalog)          ; keeps array-map ordering
   baked-catalog))

;; ============================================================================
;; Drift
;; ============================================================================

(def ^:private snapshot-suffix-re
  "Trailing dated-snapshot suffix, e.g. `-2025-04-14` or `-20250414`."
  #"-\d{4}-?\d{2}-?\d{2}$")

(defn- snapshot-of-known?
  "True when `id` is a dated snapshot of a model already in `known`.

   Providers list every pinned snapshot alongside the moving alias —
   `gpt-4o-2024-05-13` next to `gpt-4o` — so a raw discovered list is mostly
   dates. They stay in the catalog (they are real, callable ids), but
   reporting them as discoveries buries the one line a human actually needs to
   act on."
  [id known]
  (let [base (str/replace (str id) snapshot-suffix-re "")]
    (and (not= base (str id)) (contains? known base))))

(defn drift
  "What a refresh changed, per provider, for `by models --drift`.

   `:retired` are curated entries the provider no longer serves — the drift
   that motivated this whole mechanism, since a catalogued-but-unserved model
   fails only at call time. `:discovered` are ids the provider serves that the
   baked catalog has never heard of; they are usable immediately but need a
   human to decide whether they belong in the picker, and what to call them.

   Reports only providers actually covered by the overlay, so an offline run
   reports nothing rather than claiming everything vanished."
  [baked-catalog overlay-map]
  (into
   (sorted-map)
   (keep
    (fn [[provider entries]]
      (when-let [entry (get overlay-map provider)]
        (when (usable-entry? provider entry)
          (let [live    (set (:models entry))
                known   (into #{} (map :model) entries)
                ;; A partial (region-scoped) fetch retires nothing, so it must
                ;; not REPORT retirements either — otherwise `by models drift`
                ;; would list every us-east-1-only Bedrock model as gone
                ;; whenever it runs from another region.
                retired (if (:partial? entry)
                          []
                          (->> entries
                               (remove #(contains? live (:model %)))
                               (mapv #(select-keys % [:model :curated-rank :description]))))
                added   (->> (set/difference live known)
                             (remove #(snapshot-of-known? % known))
                             sort vec)]
            (when (or (seq retired) (seq added))
              [provider {:retired    retired
                         :discovered added
                         :fetched-at (:fetched-at entry)}])))))
    baked-catalog)))
