;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

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
  "Re-apply progressive compression to turns with given depth parameters.

   `:dry-run?` forwards to `truncate-to-file`: the returned turns are shaped and
   sized exactly as the committed ones would be, but no temp file is written.
   Used by `compact-previous-turns` to price a candidate pass before choosing
   one. Dry-run output is for measurement only and must not be returned to a
   caller — its recovery paths are placeholders."
  [turns full-depth summary-depth answer-limit & {:keys [dry-run?] :or {dry-run? false}}]
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
                                                                      :label "prior answer"
                                                                      :dry-run? dry-run?))))
                    (assoc :depth :summary))

                :else
                {:question (:question turn)
                 :answer (when-let [a (:answer turn)]
                           (clj-sandbox/truncate-to-file a (min 400 answer-limit) "compact-answer"
                                                         :label "prior answer (minimal)"
                                                         :dry-run? dry-run?))
                 :depth :minimal})))
          turns))))

(defn compact-previous-turns
  "Aggressively compress previous-turns to reduce tokens toward target.
   Pure function — no LLM call. Returns compacted turns vector.

   Picks the loosest of four progressively tighter passes that fits the target:
   1. full-depth=3, summary-depth=10, answer-limit=2000
   2. full-depth=1, summary-depth=5, answer-limit=1000
   3. All minimal (question + short answer only), 400 chars
   4. All minimal, 200 chars
   …and if none fits, applies pass 4 and drops oldest turns (keep last 10).

   SEARCH AND COMMIT ARE SEPARATE, which is the whole shape of this function.
   Pricing a pass used to mean *applying* it, and the old loop fed each pass the
   PREVIOUS pass's output — so an answer could be truncated up to four times.

   That chain is the problem, and not for the reason it looks like. It costs no
   extra WRITES: `truncate-to-file` reuses the temp file it recovered from, so
   one file per truncated answer either way. What it costs is READ-BACKS — every
   re-truncation slurps the temp file to recover the original. Measured on 12
   turns: 32 reads at a tight target, 56 when no pass fits, versus 0 here.

   Those reads are a correctness dependency, not just I/O. The sandbox cache
   evicts oldest-first at `:sandbox-cache-max-files` (200). When the file is gone
   the re-truncation silently re-bases: the marker still says \"Full content saved
   to\" but the file now holds the already-truncated text. Recoverability is lost
   with no error and nothing downstream can detect it.

   So: price every candidate against the ORIGINAL turns with `:dry-run?` (no
   writes, no reads), then apply the winner ONCE. Output is unchanged — depth is
   a pure function of recency and the pass parameters, and truncating once from
   the original equals truncating repeatedly *when recovery works*, which is
   exactly the assumption this removes.

   Note this fixes the WITHIN-compaction chain only. `:previous-turns` is
   mutated in place, so turn N+1 still starts from turn N's truncated text;
   breaking that needs originals retained somewhere and is a larger change."
  [previous-turns target-tokens]
  (if (empty? previous-turns)
    previous-turns
    (let [passes [[3 10 2000]   ;; pass 1: moderate compression
                  [1 5  1000]   ;; pass 2: aggressive
                  [0 0  400]    ;; pass 3: all minimal
                  [0 0  200]]   ;; pass 4: very tight minimal
          fits?  (fn [[fd sd al]]
                   (<= (estimate-turns-tokens
                        (recompress-turns previous-turns fd sd al :dry-run? true))
                       target-tokens))
          winner (first (filter fits? passes))]
      (if winner
        (let [[fd sd al] winner]
          (recompress-turns previous-turns fd sd al))
        ;; Nothing fits — tightest pass, then drop oldest as a last resort.
        (let [[fd sd al] (last passes)
              compressed (recompress-turns previous-turns fd sd al)]
          (subvec (vec compressed) (max 0 (- (count compressed) 10))))))))

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
