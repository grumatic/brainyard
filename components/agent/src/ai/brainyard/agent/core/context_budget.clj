;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.context-budget
  "Token-budget enforcement for LLM prompt context.

   Coact assembles `:system-context` and `:user-context` as ordered
   section maps `{section-kw text}`. This namespace estimates total
   tokens (chars/4 via clj-llm) and, when the assembled prompt exceeds
   budget, walks compactable sections in ascending priority order and
   invokes caller-supplied strategies until the budget is met or no
   strategies remain.

   Responsibilities:
     - model->budget : derive prompt-token budget from runtime/lm-config.
     - estimate-tokens / total-tokens : chars/4 heuristic via clj-llm.
     - compose : join sections in render order with \"\\n\\n\".
     - enforce : run the budget loop, returning refined sections + diagnostics.

   Strategies are caller-supplied closures (typically captured in
   `coact-init-action`) because they need to (a) mutate per-turn state
   in st-memory and (b) call coact-private formatters."
  (:require [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; ============================================================================
;; Section Policies
;; ============================================================================

(def default-section-policies
  "Section priority + compact-strategy mapping.

   `:priority` — lower priority is compacted first when over budget.
   `:compact`  — names a strategy key the caller must supply in `:strategies`.
                 Sections with no `:compact` are immutable; if budget cannot
                 be met without them, enforcement reports `:over-budget? true`."
  {;; --- system-context (immutable per turn — no compact strategies) ----
   :role                      {:priority 100}
   :system-info               {:priority 98}
   :execution-model           {:priority 99}
   :channel-routing           {:priority 99}
   :tool-call-format          {:priority 99}
   :code-blocks-format        {:priority 99}
   :sandbox-context-accessor  {:priority 95}
   :tools                     {:priority 90 :compact :tools-tier}
   :critical-rules            {:priority 95}
   :large-results-playbook    {:priority 90}
   :instruction               {:priority 95}
   :agent-context             {:priority 90}
   :footer                    {:priority 100}
   ;; --- user-context (volatile — strategies live here) ----------------
   :turn-info                 {:priority 88}
   :project-instructions      {:priority 85}
   :user-instructions         {:priority 85}
   ;; :keep-floor? — when the compact strategy can no longer reduce this
   ;; section (e.g. only pinned/system artifacts remain), keep its remaining
   ;; "floor" content instead of dropping the whole section as a last resort.
   :live-artifacts            {:priority 70 :compact :drop-live-artifacts
                               :keep-floor? true}
   :conversation-history      {:priority 60 :compact :shrink-conversation}
   :previous-turns            {:priority 50 :compact :bump-previous-turns}
   ;; :parent-trail (M9) ships with sub-agent calls and is the cheapest
   ;; user-context section to trim — drop oldest parent turn first.
   :parent-trail              {:priority 55 :compact :bump-parent-trail}
   ;; Accumulator slots tracked only for budget accounting — their text
   ;; is never composed into the prompt strings. The LLM sees the
   ;; underlying vectors via DSPy signature inputs.
   :iterations                {:priority 50 :compact :collapse-iterations}
   :thoughts                  {:priority 50 :compact :keep-last-n-thoughts}
   :observations              {:priority 50 :compact :keep-last-n-observations}})

;; ============================================================================
;; Token Estimation
;; ============================================================================

(defn estimate-tokens
  "Token estimate for a single string via clj-llm chars/4 heuristic."
  [text]
  (clj-llm/estimate-tokens text))

(defn section-tokens
  "Map of section-kw -> estimated token count, restricted to keys present
   in both `sections` and `order`."
  [sections order]
  (reduce (fn [acc k]
            (if-let [t (get sections k)]
              (assoc acc k (estimate-tokens t))
              acc))
          {} order))

(defn total-tokens
  "Sum of estimated tokens for sections rendered in `order`."
  [sections order]
  (reduce + 0 (vals (section-tokens sections order))))

;; ============================================================================
;; Composition
;; ============================================================================

(defn compose
  "Join non-blank sections in render order with \"\\n\\n\".
   Empty/missing sections are dropped silently."
  [sections order]
  (->> order
       (keep (fn [k]
               (let [t (get sections k)]
                 (when (and (string? t) (not (str/blank? t))) t))))
       (str/join "\n\n")))

;; ============================================================================
;; Budget Resolution
;; ============================================================================

(defn model->budget
  "Resolve the prompt-token budget.

   Inputs:
     :max-context-tokens - model's context window (default 128000)
     :max-output-tokens  - reservation for the model's response (default 4096)
     :safety-ratio       - additional headroom as a fraction (default 0.10)

   Returns the maximum allowable prompt tokens (long >= 0)."
  [{:keys [max-context-tokens max-output-tokens safety-ratio]
    :or   {max-context-tokens 128000
           max-output-tokens  4096
           safety-ratio       0.10}}]
  (let [usable (max 0 (- max-context-tokens (max 0 max-output-tokens)))
        margin (long (Math/ceil (* usable (max 0.0 safety-ratio))))]
    (max 0 (- usable margin))))

;; ============================================================================
;; Enforcement
;; ============================================================================

(defn- compactable-keys
  "Section keys present in `sections`, in `order`, with a known `:compact`
   strategy registered in `strategies`. Sorted by ascending priority
   (lowest priority first → compacted first)."
  [sections order policies strategies]
  (->> order
       (filter #(get sections %))
       (filter (fn [k]
                 (when-let [strat (get-in policies [k :compact])]
                   (contains? strategies strat))))
       (sort-by #(get-in policies [% :priority] 0))))

(defn enforce
  "Enforce a token budget on `sections`.

   When the assembled total exceeds `budget`, repeatedly invoke
   caller-supplied compaction strategies on the lowest-priority
   compactable section until the budget is met or no strategies remain.

   A strategy is `(fn [sections] -> sections')` — it should return a new
   sections map with the same shape (keys may be removed). Strategies
   commonly side-effect external state (e.g., mutate st-memory) since
   they are closed over by the caller. If a strategy makes no progress
   (same section token count), the section is dropped to avoid an
   infinite loop — UNLESS its policy sets `:keep-floor? true`, in which
   case its remaining (floor) content is kept and the section is retired
   from further compaction. Use `:keep-floor?` for sections that hold
   protected content (e.g. pinned live artifacts) that must never be
   dropped wholesale as a last resort.

   Inputs:
     :sections    {section-kw text}                 REQUIRED
     :order       [section-kw ...]  render order    REQUIRED
     :budget      target prompt tokens              REQUIRED
     :policies    section policy table              default `default-section-policies`
     :strategies  {strategy-kw (fn [sections] -> sections')}  default {}
     :max-passes  safety bound on the loop          default 32

   Returns:
     {:sections     refined-sections
      :order        order  (unchanged from input)
      :total-tokens int    (final)
      :budget       int
      :compactions  [{:section :strategy :before-tokens :after-tokens :delta}]
      :over-budget? boolean}"
  [{:keys [sections order budget policies strategies max-passes]
    :or {policies   default-section-policies
         strategies {}
         max-passes 32}}]
  (loop [secs sections
         compactions []
         retired #{}     ; keep-floor sections that can't compact further
         pass 0]
    (let [current (total-tokens secs order)]
      (cond
        (<= current budget)
        {:sections secs :order order :total-tokens current :budget budget
         :compactions compactions :over-budget? false}

        (>= pass max-passes)
        (do (mulog/warn ::budget-max-passes-reached
                        :total-tokens current :budget budget :passes pass)
            {:sections secs :order order :total-tokens current :budget budget
             :compactions compactions :over-budget? true})

        :else
        (let [candidates (remove retired
                                 (compactable-keys secs order policies strategies))]
          (if (empty? candidates)
            (do (mulog/warn ::budget-still-exceeded-no-strategies
                            :total-tokens current :budget budget)
                {:sections secs :order order :total-tokens current :budget budget
                 :compactions compactions :over-budget? true})
            (let [k         (first candidates)
                  strat-key (get-in policies [k :compact])
                  strat-fn  (get strategies strat-key)
                  before    (estimate-tokens (get secs k))
                  new-secs  (try (strat-fn secs)
                                 (catch Exception e
                                   (mulog/warn ::strategy-failed
                                               :section k :strategy strat-key
                                               :exception e)
                                   secs))
                  after     (estimate-tokens (get new-secs k ""))]
              (cond
                ;; Progress — keep going on the same candidate set.
                (not= before after)
                (recur new-secs
                       (conj compactions {:section k
                                          :strategy strat-key
                                          :before-tokens before
                                          :after-tokens after
                                          :delta (- after before)})
                       retired
                       (inc pass))

                ;; No progress, but the section protects a floor (e.g. pinned
                ;; live artifacts) — keep its remaining content and retire it
                ;; from compaction rather than dropping it wholesale.
                (get-in policies [k :keep-floor?])
                (recur new-secs
                       (conj compactions {:section k
                                          :strategy :kept-floor
                                          :before-tokens before
                                          :after-tokens after
                                          :delta 0})
                       (conj retired k)
                       (inc pass))

                ;; No progress and no floor — drop the section to avoid an
                ;; infinite loop and free its bytes (last resort).
                :else
                (recur (dissoc new-secs k)
                       (conj compactions {:section k
                                          :strategy :dropped
                                          :before-tokens before
                                          :after-tokens 0
                                          :delta (- before)})
                       retired
                       (inc pass))))))))))
