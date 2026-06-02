;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.previous-turns
  "Progressive compression of prior turn summaries for cross-turn context.

   Stores multiple previous turns with depth-based detail levels:
   - :full    — question + iterations (code/results) + answer  (most recent)
   - :summary — question + answer (truncated)                  (medium age)
   - :minimal — question + short answer                        (oldest)

   The LLM uses (get-previous-turn :index N) to selectively access any turn,
   and (context-index) shows a lightweight summary of all turns."
  (:require [ai.brainyard.clj-sandbox.interface :as clj-sandbox]))

(defn append-turn
  "Append a new turn entry to the previous-turns chain.
   Applies progressive compression to older entries.

   Parameters:
     existing-turns - Vector of prior turn summaries (most recent last)
     new-turn       - {:question :iterations :answer}
     opts           - {:max-turns 100
                       :full-depth 10      ;; most recent N with full iterations
                       :summary-depth 30}  ;; next N with question+answer only

   Returns: Updated turns vector with progressive compression."
  [existing-turns new-turn & {:keys [max-turns full-depth summary-depth]
                              :or {max-turns 100 full-depth 10 summary-depth 30}}]
  (let [all-turns (conj (or existing-turns []) new-turn)
        n (count all-turns)
        compressed (vec (map-indexed
                         (fn [i turn]
                           (let [recency (- n 1 i)]
                             (cond
                               ;; Most recent full-depth turns: keep everything
                               (< recency full-depth)
                               (assoc turn :depth :full)

                               ;; Next summary-depth: keep question + truncated answer
                               (< recency (+ full-depth summary-depth))
                               (-> turn
                                   (dissoc :iterations)
                                   (update :answer (fn [a]
                                                     (when a
                                                       (clj-sandbox/truncate-to-file a 4000 "previous-answer"
                                                                                     :label "prior answer"))))
                                   (assoc :depth :summary))

                               ;; Older: minimal (question + very short answer)
                               :else
                               {:question (:question turn)
                                :answer (when-let [a (:answer turn)]
                                          (clj-sandbox/truncate-to-file a 1600 "previous-answer"
                                                                        :label "prior answer (minimal)"))
                                :depth :minimal})))
                         all-turns))
        ;; Trim to max-turns
        trimmed (if (> (count compressed) max-turns)
                  (subvec compressed (- (count compressed) max-turns))
                  compressed)]
    trimmed))
