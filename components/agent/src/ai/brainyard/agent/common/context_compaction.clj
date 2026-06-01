;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.context-compaction
  "Cross-turn context compaction.

   Reduces next-turn token usage by progressively compressing the carried-over
   `:previous-turns` chain toward a tight target. Designed to be called between
   turns (via the /compact command or the after-turn auto-compaction hook).

   Deterministic — no LLM call. This is the same pure reduction the per-turn
   budget reducer (`context-budget/enforce`) applies via its
   `:bump-previous-turns` strategy, run proactively to a tighter target so the
   next turn starts lean. The mid-turn budget reducer continues to handle the
   live prompt; this path only pre-shrinks the persisted carryover.

   `:previous-turns` is the dominant cross-turn carryover: a finished turn's
   iteration trace is folded into it by `previous-turns/append-turn`, and the
   static instruction/agent-context sections are rebuilt fresh each turn."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.clj-sandbox.interface :as clj-sandbox]
            [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Token Estimation
;; ============================================================================

(defn estimate-context-tokens
  "Estimate total context tokens that will be sent on the next turn.
   Prefers actual input-tokens from the last LLM call (most accurate).
   Falls back to chars/4 heuristic on st-memory-init contents."
  [agent]
  (let [tracker (when-let [!s (:!session agent)]
                  (get-in @!s [:config :usage-tracker]))
        ;; Try actual tokens from last LLM call
        last-call (first (clj-llm/get-usage-history tracker :limit 1))
        actual-tokens (:input-tokens last-call)]
    (if (and actual-tokens (pos? actual-tokens))
      actual-tokens
      ;; Fallback: estimate from st-memory-init contents
      (let [st-mem-init (proto/get-st-memory-init agent)
            init-map (when st-mem-init @st-mem-init)
            previous-turns (:previous-turns init-map)
            instruction (or (:instruction init-map) "")
            tool-context (or (:tool-context init-map) "")
            agent-context (or (:agent-context init-map) "")
            turns-str (if (seq previous-turns) (pr-str previous-turns) "")]
        (+ (clj-llm/estimate-tokens turns-str)
           (clj-llm/estimate-tokens instruction)
           (clj-llm/estimate-tokens tool-context)
           (clj-llm/estimate-tokens agent-context))))))

;; ============================================================================
;; Previous Turns Compaction (deterministic, no LLM call)
;; ============================================================================

(defn- estimate-turns-tokens
  "Estimate tokens for a previous-turns vector."
  [turns]
  (if (seq turns)
    (clj-llm/estimate-tokens (pr-str turns))
    0))

(defn- recompress-turns
  "Re-apply progressive compression to turns with given depth parameters."
  [turns full-depth summary-depth answer-limit]
  (let [n (count turns)]
    (vec (map-indexed
          (fn [i turn]
            (let [recency (- n 1 i)]
              (cond
                (< recency full-depth)
                (assoc turn :depth :full)

                (< recency (+ full-depth summary-depth))
                (-> turn
                    (dissoc :iterations)
                    (update :answer (fn [a]
                                      (when a
                                        (clj-sandbox/truncate-to-file a answer-limit "compact-answer"
                                                                      :label "prior answer"))))
                    (assoc :depth :summary))

                :else
                {:question (:question turn)
                 :answer (when-let [a (:answer turn)]
                           (clj-sandbox/truncate-to-file a (min 400 answer-limit) "compact-answer"
                                                         :label "prior answer (minimal)"))
                 :depth :minimal})))
          turns))))

(defn compact-previous-turns
  "Aggressively compress previous-turns to reduce tokens toward target.
   Pure function — no LLM call. Returns compacted turns vector.

   Applies progressively tighter passes with early-exit when under target:
   1. full-depth=3, summary-depth=10, answer-limit=2000
   2. full-depth=1, summary-depth=5, answer-limit=1000
   3. All minimal (question + short answer only)
   4. Truncate minimal answers to 400 chars
   5. Drop oldest turns (keep last 10)"
  [previous-turns target-tokens]
  (if (empty? previous-turns)
    previous-turns
    (let [passes [[3 10 2000]   ;; pass 1: moderate compression
                  [1 5  1000]   ;; pass 2: aggressive
                  [0 0  400]    ;; pass 3: all minimal
                  [0 0  200]]]  ;; pass 4: very tight minimal
      (loop [turns previous-turns
             [[fd sd al] & more] passes]
        (if (nil? fd)
          ;; All passes exhausted — drop oldest turns as last resort
          (let [kept (subvec (vec turns) (max 0 (- (count turns) 10)))]
            kept)
          (let [compressed (recompress-turns turns fd sd al)
                est (estimate-turns-tokens compressed)]
            (if (<= est target-tokens)
              compressed
              (if (seq more)
                (recur compressed more)
                ;; After all passes, try dropping oldest
                (let [kept (subvec (vec compressed) (max 0 (- (count compressed) 10)))]
                  kept)))))))))

;; ============================================================================
;; Main Compaction Entry Point
;; ============================================================================

(defn- fire-phase!
  "Fire :agent.compaction/phase with current before/after token estimates.
   No-op when `agent` is nil (some test paths)."
  [agent phase status before-tokens after-tokens]
  (when agent
    (hooks/fire! :agent.compaction/phase
                 {:agent agent
                  :phase phase
                  :status status
                  :before-tokens before-tokens
                  :after-tokens after-tokens})))

(defn compact-context!
  "Compact agent carryover context to reduce next-turn token usage.
   Mutates the st-memory-init atom in place. Deterministic — no LLM call.

   Progressively compresses `:previous-turns` toward a target derived from
   `:compaction-target-ratio` (× `:max-context-tokens`).

   Options:
     :target-ratio - Target fraction of max-context-tokens (default from
                     config, typically 0.2)
     :trigger      - Symbolic label for this compaction run, surfaced in
                     :agent.compaction/{pre,post} hook payloads. Callers
                     should pass :manual (/compact) or :auto (after-turn).
                     Defaults to :manual.

   Fires hooks: :agent.compaction/pre, :agent.compaction/phase (with
   :status :start and :done), :agent.compaction/post.

   Returns:
     {:before-tokens N :after-tokens N :compacted-keys [...]
      :duration-ms N :trigger kw}
     or {:already-compact true :before-tokens N :trigger kw}"
  [agent & {:keys [target-ratio trigger]
            :or   {trigger :manual}}]
  (let [st-mem-init   (proto/get-st-memory-init agent)
        max-tokens    (config/get-config agent :max-context-tokens)
        target-ratio  (or target-ratio (config/get-config agent :compaction-target-ratio))
        target-tokens (long (* max-tokens target-ratio))
        before-tokens (estimate-context-tokens agent)
        start-nanos   (System/nanoTime)]

    (when agent
      (hooks/fire! :agent.compaction/pre
                   {:agent agent
                    :before-tokens before-tokens
                    :target-tokens target-tokens
                    :trigger trigger}))

    (if (<= before-tokens target-tokens)
      (let [result {:already-compact true
                    :before-tokens before-tokens
                    :after-tokens before-tokens
                    :compacted-keys []
                    :duration-ms 0
                    :trigger trigger}]
        (when agent
          (hooks/fire! :agent.compaction/post
                       {:agent agent
                        :before-tokens before-tokens
                        :after-tokens before-tokens
                        :compacted-keys []
                        :trigger trigger
                        :duration-ms 0}))
        result)

      (let [compacted-keys (atom [])
            slot-reduction (atom 0)

            ;; Progressive previous-turns compression (no LLM).
            _ (fire-phase! agent :previous-turns :start before-tokens before-tokens)
            _ (when st-mem-init
                (let [turns (:previous-turns @st-mem-init)]
                  (when (seq turns)
                    (let [slot-before (clj-llm/estimate-tokens (pr-str turns))
                          compacted   (compact-previous-turns turns target-tokens)
                          slot-after  (clj-llm/estimate-tokens
                                       (if (seq compacted) (pr-str compacted) ""))]
                      (swap! st-mem-init assoc :previous-turns compacted)
                      (reset! slot-reduction (max 0 (- slot-before slot-after)))
                      (swap! compacted-keys conj :previous-turns)))))
            ;; `before-tokens` is the last LLM call's actual input count, and
            ;; this path only mutated the :previous-turns slot — so the next
            ;; prompt drops by exactly the slot reduction. Re-reading
            ;; estimate-context-tokens here would return the stale last-call
            ;; number (no new call yet), showing before == after; derive the
            ;; honest after from the measured slot delta instead.
            after-tokens (max 0 (- before-tokens @slot-reduction))
            _ (fire-phase! agent :previous-turns :done before-tokens after-tokens)

            duration-ms (long (/ (- (System/nanoTime) start-nanos) 1000000))
            keys-vec    @compacted-keys]

        (mulog/info ::context-compacted
                    :before-tokens before-tokens
                    :after-tokens after-tokens
                    :compacted-keys keys-vec
                    :duration-ms duration-ms
                    :trigger trigger)

        (when agent
          (hooks/fire! :agent.compaction/post
                       {:agent agent
                        :before-tokens before-tokens
                        :after-tokens after-tokens
                        :compacted-keys keys-vec
                        :trigger trigger
                        :duration-ms duration-ms}))

        {:before-tokens before-tokens
         :after-tokens after-tokens
         :compacted-keys keys-vec
         :duration-ms duration-ms
         :trigger trigger}))))
