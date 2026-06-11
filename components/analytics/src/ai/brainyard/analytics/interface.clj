;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.analytics.interface
  "Public API for session analytics.

   Provides:
   - analyze-session — Full post-session analytics (PQS + waste + cost)
   - score-pqs — Prompt Quality Score
   - detect-waste — 7 waste pattern detectors
   - calculate-session-cost — Cost breakdown + optimization
   - persist-analytics! / get-session-analytics / get-analytics-trends — Persistence
   - format-analytics — Terminal display formatting"
  (:require [ai.brainyard.analytics.core.pqs :as pqs]
            [ai.brainyard.analytics.core.waste :as waste]
            [ai.brainyard.analytics.core.cost :as cost]
            [ai.brainyard.analytics.core.trajectory :as traj]
            [ai.brainyard.analytics.core.persistence :as persistence]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Soft Dependency Resolution
;; ============================================================================

(defn- resolve-usage-fn
  "Resolve a clj-llm usage function."
  [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))

;; ============================================================================
;; Individual Analyzers
;; ============================================================================

(defn score-pqs
  "Score a session's prompts using PQS (Prompt Quality Score).

   Parameters:
     messages - Conversation history [{:role :content}]

   Options:
     :lm-config     - LM for LLM-based scoring (nil = heuristics only)
     :usage-tracker - For tracking analysis cost

   Returns: PQS result map"
  [messages & {:as opts}]
  (pqs/score-pqs messages
                 :lm-config (:lm-config opts)
                 :usage-tracker (:usage-tracker opts)))

(defn detect-waste
  "Detect waste patterns in session data.

   Parameters:
     messages      - Conversation history
     usage-summary - Usage summary from tracker
     usage-history - Usage history records

   Options:
     :lm-config     - LM for LLM-based detection
     :usage-tracker - For tracking analysis cost

   Returns: waste detection result map"
  [messages usage-summary usage-history & {:as opts}]
  (waste/detect-all-waste messages usage-summary usage-history
                          :lm-config (:lm-config opts)
                          :usage-tracker (:usage-tracker opts)))

(defn calculate-session-cost
  "Calculate detailed cost breakdown with optimization analysis.

   Parameters:
     usage-summary - From usage tracker
     usage-history - Usage history records

   Options:
     :waste-results       - Waste detection results
     :session-duration-ms - Session wall-clock time

   Returns: cost result map"
  [usage-summary usage-history & {:as opts}]
  (cost/calculate-session-cost usage-summary usage-history
                               :waste-results (:waste-results opts)
                               :session-duration-ms (:session-duration-ms opts)))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn analyze-session
  "Run full post-session analytics on conversation history + usage data.

   Parameters:
     session-data - Map with:
       :session-id    - Session identifier
       :user-id       - User identifier
       :messages      - Vector of {:role :content} conversation messages
       :usage-tracker - Usage tracker atom (from clj-llm)
       :st-memory     - Final st-memory state from BT (optional)

   Options:
     :skip-llm-analysis  - Skip RLM-based analysis (heuristics only)
     :lm-config          - LM config for RLM analysis calls
     :memory-manager     - Memory manager for persistence (optional)
     :persist            - Whether to persist results (default true when mm provided)

   Returns: analytics result map"
  [session-data & {:keys [skip-llm-analysis lm-config memory-manager persist]
                   :or {persist true}}]
  (let [{:keys [session-id user-id messages usage-tracker]} session-data
        ;; Resolve usage data from tracker
        get-summary (resolve-usage-fn 'ai.brainyard.clj-llm.interface/get-usage-summary)
        get-history (resolve-usage-fn 'ai.brainyard.clj-llm.interface/get-usage-history)
        usage-summary (when (and get-summary usage-tracker)
                        (get-summary usage-tracker))
        usage-history (when (and get-history usage-tracker)
                        (get-history usage-tracker))
        ;; LM config for analysis (nil = heuristics only)
        analysis-lm (when-not skip-llm-analysis lm-config)
        ;; Run PQS scoring
        pqs-result (score-pqs messages
                              :lm-config analysis-lm
                              :usage-tracker usage-tracker)
        ;; Run waste detection
        waste-result (detect-waste messages
                                   (or usage-summary {:totals {}})
                                   (or usage-history [])
                                   :lm-config analysis-lm
                                   :usage-tracker usage-tracker)
        ;; Calculate cost
        cost-result (calculate-session-cost
                     (or usage-summary {:totals {}})
                     (or usage-history [])
                     :waste-results waste-result
                     :session-duration-ms nil)
        ;; Build result
        analytics {:session-id session-id
                   :user-id user-id
                   :timestamp (java.time.Instant/now)
                   :pqs pqs-result
                   :waste waste-result
                   :cost cost-result
                   :usage-summary usage-summary}]
    ;; Persist if memory manager provided and persist enabled
    (when (and memory-manager persist)
      (try
        (persistence/persist-analytics! memory-manager analytics)
        (catch Exception e
          (mulog/warn ::persistence-failed :message "Failed to persist analytics" :exception e))))
    analytics))

;; ============================================================================
;; Persistence
;; ============================================================================

(defn persist-analytics!
  "Persist analytics results to memory component.

   Parameters:
     memory-manager - MemoryManager instance
     analytics      - Full analytics result map

   Returns: {:facts-stored N}"
  [memory-manager analytics]
  (persistence/persist-analytics! memory-manager analytics))

(defn get-session-analytics
  "Query stored analytics for a session from memory.

   Returns: stored analytics map or nil"
  [memory-manager session-id]
  (persistence/query-session-analytics memory-manager session-id))

(defn get-analytics-trends
  "Query cross-session analytics trends.

   Options:
     :limit     - Max sessions (default 20)
     :fact-type - Filter by specific metric type

   Returns: vector of analytics summaries, most recent first"
  [memory-manager user-id & {:as opts}]
  (persistence/query-analytics-trends memory-manager user-id
                                      :limit (:limit opts 20)
                                      :fact-type (:fact-type opts)))

;; ============================================================================
;; Display Formatting
;; ============================================================================

(defn format-analytics
  "Format analytics results for terminal display.
   Returns a formatted string."
  [analytics]
  (let [{:keys [pqs waste cost]} analytics
        sb (StringBuilder.)]
    ;; PQS Section
    (.append sb "\n=== Prompt Quality Score (PQS) ===\n")
    (.append sb (format "  Overall: %d/100\n" (:overall-score pqs 0)))
    (let [dims (:dimensions pqs)]
      (.append sb (format "  Specificity:          %2d/25\n" (:specificity dims 0)))
      (.append sb (format "  Task Atomicity:       %2d/25\n" (:task-atomicity dims 0)))
      (.append sb (format "  Context Completeness: %2d/20\n" (:context-completeness dims 0)))
      (.append sb (format "  Acceptance Criteria:  %2d/20\n" (:acceptance-criteria dims 0)))
      (.append sb (format "  Clarity:              %2d/10\n" (:clarity dims 0))))
    (let [adj (:adjustments pqs)]
      (when (not= 0 (:total adj 0))
        (.append sb (format "  Adjustments: %+d (corrections: %+d, completions: %+d, storms: %+d)\n"
                            (:total adj 0) (:correction-turns adj 0)
                            (:one-turn-completions adj 0) (:retry-storms adj 0)))))
    (when (seq (:recommendations pqs))
      (.append sb "  Recommendations:\n")
      (doseq [rec (:recommendations pqs)]
        (.append sb (format "    - %s\n" rec))))

    ;; Waste Section
    (.append sb "\n=== Waste Detection ===\n")
    (if (seq (:patterns waste))
      (do
        (.append sb (format "  Total waste: %d tokens ($%.4f, %.1f%%)\n"
                            (long (:total-waste-tokens waste 0))
                            (double (:total-waste-cost waste 0))
                            (double (:waste-percentage waste 0))))
        (doseq [p (:patterns waste)]
          (.append sb (format "  [%s] %s: %s\n"
                              (str/upper-case (name (:severity p :low)))
                              (name (:pattern-id p))
                              (:detail p "")))))
      (.append sb "  No waste patterns detected.\n"))

    ;; Cost Section
    (.append sb "\n=== Cost Analysis ===\n")
    (let [actual (:actual cost)]
      (.append sb (format "  Actual cost:     $%.4f (%d tokens, %d calls)\n"
                          (double (:total-cost actual 0))
                          (long (:total-tokens actual 0))
                          (long (:call-count actual 0))))
      (when (pos? (or (:savings-potential cost) 0))
        (.append sb (format "  Optimal cost:    $%.4f (save $%.4f)\n"
                            (double (:optimal-estimate cost 0))
                            (double (:savings-potential cost 0)))))
      (let [tp (:throughput cost)]
        (when (and tp (pos? (:output-tokens-per-sec tp 0)))
          (.append sb (format "  Throughput:      %d output tok/s, avg latency %dms\n"
                              (:output-tokens-per-sec tp 0)
                              (:avg-latency-ms tp 0))))))

    (.toString sb)))

;; ============================================================================
;; Trajectory-Sourced Session Analytics (on-demand, whole session)
;; ============================================================================

(defn analyze-trajectory
  "Run the full analyzer suite over a vector of `:v 2` trajectory turn records.
   Always computes all metrics (PQS, TCE, ICE, TUR, LT, cache, OGA, waste, SHS).
   Pure — the caller supplies records and (for the deep pass) the resolved
   lm-config. Returns the analytics result map.

   Options:
     :lm-config         - LM config for the optional LLM-refined pass
     :skip-llm-analysis - heuristics only when true (default true)
     :shs-weights       - override map for the composite weights"
  [turn-records & {:keys [lm-config skip-llm-analysis shs-weights]
                   :or {skip-llm-analysis true}}]
  (traj/analyze-all turn-records
                    :lm-config lm-config
                    :skip-llm-analysis skip-llm-analysis
                    :shs-weights shs-weights))

(defn- pct [x] (* 100.0 (double (or x 0))))

(defn format-session-analytics
  "Format an `analyze-trajectory` result for display. `level` is :summary
   (default), :full, or :raw. Returns a string."
  ([result] (format-session-analytics result :summary))
  ([result level]
   (case (keyword (or level :summary))
     :raw (pr-str result)
     (let [{:keys [turns health-score pqs cost iteration tools latency cache outcome]} result
           sb (StringBuilder.)
           score-line (fn [label score detail]
                        (.append sb (format "  %-26s%s   %s\n"
                                            label
                                            (if score (format "%3d/100" (long score)) "   —  ")
                                            (or detail ""))))]
       (.append sb (format "=== Session Health: %d/100 (%s) ===  (%d turns, $%.4f, %d tool calls)\n"
                           (long (:score health-score 0)) (:grade health-score "—")
                           (long turns)
                           (double (get-in cost [:actual :total-cost] 0))
                           (long (:total-tool-calls tools 0))))
       (score-line "Prompt Quality (PQS)" (:overall-score pqs)
                   (format "specificity %d/25, atomicity %d/25"
                           (get-in pqs [:dimensions :specificity] 0)
                           (get-in pqs [:dimensions :task-atomicity] 0)))
       (score-line "Token/Cost Efficiency" (:efficiency-score cost)
                   (format "cache-hit %.0f%%, $%.4f/successful-turn"
                           (pct (:cache-hit-rate cost))
                           (double (:cost-per-successful-turn cost 0))))
       (score-line "Outcome (OGA)" (:score outcome)
                   (format "success %.0f%%" (pct (:success-rate outcome))))
       (score-line "Iteration Convergence" (:score iteration)
                   (format "avg %.1f iters; %d hit max-iterations"
                           (double (:avg-iterations iteration 0))
                           (get-in iteration [:terminated-by :max-iterations] 0)))
       (score-line "Tool Reliability" (:score tools)
                   (format "%d calls, %.0f%% error rate, %d redundant"
                           (:total-tool-calls tools 0)
                           (pct (:error-rate tools))
                           (:redundant-calls tools 0)))
       (score-line "Latency" (:score latency)
                   (format "p90 turn %dms; output %d tok/s"
                           (long (:p90-ms latency 0))
                           (:output-tokens-per-sec latency 0)))
       (when (seq (:recommendations pqs))
         (.append sb "  Recommendations:\n")
         (doseq [r (:recommendations pqs)]
           (.append sb (format "    - %s\n" r))))
       (when (= :full (keyword level))
         (.append sb "\n  --- Per-turn PQS ---\n")
         (doseq [{:keys [turn score]} (:per-turn pqs)]
           (.append sb (format "    turn %-3d  %d/100\n" (long (or turn 0)) (long score))))
         (.append sb "\n  --- Slowest turns ---\n")
         (doseq [{:keys [turn duration-ms]} (:slowest-turns latency)]
           (.append sb (format "    turn %-3d  %dms\n" (long (or turn 0)) (long duration-ms))))
         (.append sb "\n  --- Top tools ---\n")
         (doseq [{:keys [name count]} (:top-tools tools)]
           (.append sb (format "    %-24s %d\n" name count)))
         (when (:degrading? cache)
           (.append sb (format "\n  Cache hit-rate is degrading (now %.0f%%) — context churning.\n"
                               (pct (:hit-rate cache))))))
       (.toString sb)))))
