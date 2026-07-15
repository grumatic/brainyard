;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.previous-turns
  "Progressive compression of prior turn summaries for cross-turn context.

   Stores multiple previous turns with depth-based detail levels:
   - :full    — question + iterations (code/results) + answer  (most recent)
   - :summary — question + answer (truncated)                  (medium age)
   - :minimal — question + short answer                        (oldest)

   The LLM reads the chain via the rendered `[Turn N · depth]` entries in
   the Previous Turns prompt section. This chain is NOT exposed through the
   sandbox context accessors — it is rendered into the prompt (:user-context)
   only, so `(context-get [:previous-turns])` returns nothing (see the
   sandbox-context construction in coact-init).
   Iterations survive only at :full depth; older turns' operational
   detail (tool calls, code evals + outputs) stays reachable via the
   `trajectory$search` command over the session's trajectory records."
  (:require [ai.brainyard.clj-sandbox.interface :as clj-sandbox]))

(defn- batched-demotion-counts
  "How many of the oldest entries sit at each compression depth, with
   demotion happening in BATCHES of `batch` instead of sliding by one
   every append.

   Cache rationale (prompt-cache Phase 3b): the rendered previous-turns
   chain rides an append-only cache zone. A slide-by-one boundary
   re-renders one mid-chain entry on EVERY append, breaking the byte
   prefix each turn; batching means old entries are byte-stable between
   demotions, so the advancing breakpoint reads them from cache for
   `batch` consecutive turns. `batch` 1 reproduces the old sliding
   behavior exactly.

   Returns {:minimal-count M :summary+minimal-count S} — oldest-first
   index i is :minimal when i < M, :summary when i < S, else :full.
   Demoted counts are monotone in n, so an entry never re-promotes."
  [n full-depth summary-depth batch]
  (let [batch (max 1 (long batch))
        demote (fn [over] (* batch (quot (max 0 (long over)) batch)))]
    {:summary+minimal-count (demote (- n full-depth))
     :minimal-count         (demote (- n full-depth summary-depth))}))

(defn append-turn
  "Append a new turn entry to the previous-turns chain.
   Applies progressive compression to older entries.

   Parameters:
     existing-turns - Vector of prior turn summaries (most recent last)
     new-turn       - {:question :iterations :answer}
     opts           - {:max-turns 100
                       :full-depth 10      ;; most recent ≥N with full iterations
                       :summary-depth 30   ;; next ≥N with question+answer only
                       :demote-batch 10}   ;; demote this many at once (cache-
                                           ;; aware hysteresis; 1 = old sliding
                                           ;; behavior)

   Depth boundaries move in `:demote-batch` steps: e.g. with the defaults
   the 10..19 most recent turns are :full, and crossing 20 demotes ten at
   once — so entries below the boundary stay byte-stable between demotions
   (they render into an append-only cache zone; see coact-system-zones
   :history-context).

   Returns: Updated turns vector with progressive compression."
  [existing-turns new-turn & {:keys [max-turns full-depth summary-depth demote-batch]
                              :or {max-turns 100 full-depth 10 summary-depth 30
                                   demote-batch 10}}]
  (let [all-turns (conj (or existing-turns []) new-turn)
        n (count all-turns)
        {:keys [minimal-count summary+minimal-count]}
        (batched-demotion-counts n full-depth summary-depth demote-batch)
        compressed (vec (map-indexed
                         (fn [i turn]
                           (cond
                             ;; Oldest minimal-count: question + very short answer
                             (< i minimal-count)
                             {:question (:question turn)
                              :answer (when-let [a (:answer turn)]
                                        (clj-sandbox/truncate-to-file a 1600 "previous-answer"
                                                                      :label "prior answer (minimal)"))
                              :depth :minimal}

                             ;; Next: keep question + truncated answer
                             (< i summary+minimal-count)
                             (-> turn
                                 (dissoc :iterations)
                                 (update :answer (fn [a]
                                                   (when a
                                                     (clj-sandbox/truncate-to-file a 4000 "previous-answer"
                                                                                   :label "prior answer"))))
                                 (assoc :depth :summary))

                             ;; Most recent: keep everything
                             :else
                             (assoc turn :depth :full)))
                         all-turns))
        ;; Trim to max-turns
        trimmed (if (> (count compressed) max-turns)
                  (subvec compressed (- (count compressed) max-turns))
                  compressed)]
    trimmed))
