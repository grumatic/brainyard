;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.analytics.core.waste
  "Waste pattern detection for agent sessions.

   Detects 7 waste patterns:
   Heuristic (no LLM):
     1. Context bloat — input/output ratio too high
     2. Model overkill — expensive model for simple tasks
     3. Oversized system prompt — system prompt dominates input
     4. Redundant requests — similar prompts repeated

   LLM-based (optional, via RLM):
     5. Token leakage — unnecessary boilerplate in prompts
     6. Unused context — provided context not referenced
     7. Output verbosity mismatch — response size vs task complexity"
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.data.json :as json]
            [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Cost Helpers
;; ============================================================================

(def ^:private expensive-models
  "Models considered expensive (opus-tier)."
  #{"claude-opus-4-6" "claude-opus-4-20250514" "gpt-4o" "gpt-4"
    "claude-3-opus-20240229" "o1" "o1-preview" "o3"})

(def ^:private cheap-model-rate
  "Approximate cost per 1M output tokens for a cheap model (Haiku-tier)."
  0.80)

(def ^:private mid-model-rate
  "Approximate cost per 1M output tokens for a mid model (Sonnet-tier)."
  4.0)

(defn- estimate-cost-at-rate
  "Estimate cost of N tokens at rate per 1M tokens."
  [tokens rate-per-1m]
  (* (/ (double tokens) 1000000.0) rate-per-1m))

;; ============================================================================
;; Text Similarity (shared with PQS)
;; ============================================================================

(def ^:private stop-words
  #{"the" "a" "an" "is" "are" "was" "were" "be" "been" "being"
    "have" "has" "had" "do" "does" "did" "will" "would" "could"
    "should" "may" "might" "shall" "can" "to" "of" "in" "for"
    "on" "with" "at" "by" "from" "as" "into" "about" "like"
    "and" "but" "or" "not" "so" "yet" "i" "me" "my" "we"
    "you" "your" "he" "she" "it" "they" "them" "their" "this"
    "that" "what" "which" "who" "how" "when" "where" "why"
    "please" "help"})

(defn- word-set
  "Get set of non-stop-words from text, lowercased."
  [text]
  (when (seq text)
    (->> (str/split (str/lower-case text) #"[^a-z0-9_\-]+")
         (remove str/blank?)
         (remove stop-words)
         set)))

(defn- jaccard-similarity
  "Jaccard similarity between two sets."
  [s1 s2]
  (if (and (seq s1) (seq s2))
    (let [intersection (count (set/intersection s1 s2))
          union (count (set/union s1 s2))]
      (if (zero? union) 0.0 (double (/ intersection union))))
    0.0))

;; ============================================================================
;; Heuristic Waste Detectors
;; ============================================================================

(defn detect-context-bloat
  "Detect context bloat: ratio of total input to output tokens.
   Flags when ratio > 4:1.

   Parameters:
     usage-summary - From usage tracker: {:totals {:input-tokens N :output-tokens N}}

   Returns: waste pattern map or nil"
  [usage-summary]
  (let [input (get-in usage-summary [:totals :input-tokens] 0)
        output (get-in usage-summary [:totals :output-tokens] 0)]
    (when (and (pos? output) (> (/ (double input) output) 4.0))
      (let [ratio (/ (double input) output)
            waste-tokens (long (- input (* output 4)))
            severity (cond (> ratio 8.0) :high
                           (> ratio 6.0) :medium
                           :else :low)]
        {:pattern-id :context-bloat
         :severity severity
         :detail (format "Input/output ratio %.1f:1 (threshold: 4:1). %d excess input tokens."
                         ratio waste-tokens)
         :waste-tokens waste-tokens
         :waste-cost (estimate-cost-at-rate waste-tokens mid-model-rate)}))))

(defn detect-model-overkill
  "Detect expensive model used for simple tasks.
   Flags opus-tier model when output < 100 tokens per call.

   Parameters:
     usage-history - Call history from tracker: [{:model :output-tokens :cost ...}]

   Returns: waste pattern map or nil"
  [usage-history]
  (let [overkill-calls (->> usage-history
                            (filter (fn [call]
                                      (and (expensive-models (:model call))
                                           (< (get call :output-tokens 0) 100)))))]
    (when (seq overkill-calls)
      (let [total-actual-cost (reduce + (map #(get-in % [:cost :total-cost] 0) overkill-calls))
            total-cheap-cost (reduce + (map #(estimate-cost-at-rate
                                              (+ (get % :input-tokens 0) (get % :output-tokens 0))
                                              cheap-model-rate)
                                            overkill-calls))
            waste-cost (max 0 (- total-actual-cost total-cheap-cost))]
        {:pattern-id :model-overkill
         :severity (if (> waste-cost 0.10) :high :medium)
         :detail (format "%d calls used expensive model for simple tasks (< 100 output tokens). Could save $%.4f."
                         (count overkill-calls) waste-cost)
         :waste-tokens 0
         :waste-cost waste-cost}))))

(defn detect-oversized-system-prompt
  "Detect oversized system prompt.
   Estimates system prompt size from first call's input tokens vs subsequent calls.
   Flags when system prompt est. > 30% of total input tokens.

   Parameters:
     usage-history - Call history: [{:input-tokens N ...}]

   Returns: waste pattern map or nil"
  [usage-history]
  (when (>= (count usage-history) 2)
    (let [first-input (get (first usage-history) :input-tokens 0)
          rest-inputs (map #(get % :input-tokens 0) (rest usage-history))
          avg-rest (if (seq rest-inputs)
                     (/ (double (reduce + rest-inputs)) (count rest-inputs))
                     0)
          ;; System prompt estimate = first call input minus average user content
          ;; Only flag if first call is meaningfully larger than subsequent calls
          system-prompt-est (max 0 (- first-input avg-rest))
          total-input (reduce + (map #(get % :input-tokens 0) usage-history))
          ratio (if (pos? total-input)
                  (/ system-prompt-est total-input)
                  0)]
      (when (and (> ratio 0.30)
                 (> system-prompt-est 2000))
        {:pattern-id :oversized-system-prompt
         :severity (if (> ratio 0.50) :high :medium)
         :detail (format "System prompt est. ~%d tokens (%.0f%% of total input). Consider trimming."
                         (long system-prompt-est) (* ratio 100))
         :waste-tokens (long (* system-prompt-est 0.5))  ;; conservative: half could be trimmed
         :waste-cost (estimate-cost-at-rate (long (* system-prompt-est 0.5)) mid-model-rate)}))))

(defn detect-redundant-requests
  "Detect redundant/repeated requests within session.
   Uses Jaccard similarity > 0.7 on user message word sets.

   Parameters:
     messages - Conversation history [{:role :content}]

   Returns: waste pattern map or nil"
  [messages]
  (let [user-msgs (->> messages
                       (filter #(= "user" (:role %)))
                       (mapv :content))
        word-sets (mapv word-set user-msgs)
        n (count word-sets)
        redundant-pairs (when (>= n 2)
                          (for [i (range n)
                                j (range (inc i) n)
                                :when (> (jaccard-similarity (nth word-sets i) (nth word-sets j)) 0.7)]
                            {:i i :j j
                             :similarity (jaccard-similarity (nth word-sets i) (nth word-sets j))}))]
    (when (seq redundant-pairs)
      (let [redundant-indices (set (mapcat (fn [p] [(:j p)]) redundant-pairs))
            waste-tokens (reduce + (map (fn [idx]
                                          (long (/ (count (nth user-msgs idx)) 4)))  ;; char/4 approx
                                        redundant-indices))]
        {:pattern-id :redundant-requests
         :severity (cond (>= (count redundant-pairs) 3) :high
                         (>= (count redundant-pairs) 2) :medium
                         :else :low)
         :detail (format "%d redundant message pair(s) detected (Jaccard > 0.7). ~%d wasted tokens."
                         (count redundant-pairs) waste-tokens)
         :waste-tokens waste-tokens
         :waste-cost (estimate-cost-at-rate waste-tokens mid-model-rate)}))))

;; ============================================================================
;; LLM-Based Waste Detectors (Optional)
;; ============================================================================

(defn- run-rlm-detection
  "Run an RLM-based waste detection with the given prompt and context.
   Returns parsed result map or nil."
  [prompt-text context & {:keys [lm-config usage-tracker]}]
  (when-let [completion-fn (try (requiring-resolve 'ai.brainyard.clj-sandbox.interface/completion)
                                (catch Exception _ nil))]
    (try
      (let [result (completion-fn prompt-text context
                                  :max-iterations 5
                                  :lm-config lm-config
                                  :usage-tracker usage-tracker)]
        (when (and (:answer result) (not= (:terminated-by result) :max-iterations))
          (try
            (json/read-str (:answer result) :key-fn keyword)
            (catch Exception _
              (mulog/debug ::rlm-waste-parse-failed :message "Failed to parse RLM waste detection result")
              nil))))
      (catch Exception e
        (mulog/debug ::rlm-waste-detection-failed :message "RLM waste detection failed" :exception e)
        nil))))

(defn detect-token-leakage
  "Detect unnecessary boilerplate or excessive examples in prompts.
   Requires LLM analysis via RLM.

   Parameters:
     messages - Conversation history

   Options:
     :lm-config     - LM config for RLM
     :usage-tracker - For cost tracking

   Returns: waste pattern map or nil"
  [messages & {:keys [lm-config usage-tracker]}]
  (when-let [prompt-var (try (requiring-resolve 'ai.brainyard.analytics.core.prompts/waste-token-leakage-prompt)
                             (catch Exception _ nil))]
    (let [user-msgs (->> messages
                         (filter #(= "user" (:role %)))
                         (mapv #(select-keys % [:role :content])))
          result (run-rlm-detection @prompt-var {:messages user-msgs}
                                    :lm-config lm-config
                                    :usage-tracker usage-tracker)]
      (when (:detected result)
        {:pattern-id :token-leakage
         :severity (or (:severity result) :medium)
         :detail (or (:detail result) "Unnecessary content detected in prompts")
         :waste-tokens (or (:waste-tokens-estimate result) 0)
         :waste-cost (estimate-cost-at-rate (or (:waste-tokens-estimate result) 0) mid-model-rate)}))))

(defn detect-unused-context
  "Detect context provided but not referenced in response.
   Requires LLM analysis via RLM.

   Returns: waste pattern map or nil"
  [messages & {:keys [lm-config usage-tracker]}]
  (when-let [prompt-var (try (requiring-resolve 'ai.brainyard.analytics.core.prompts/waste-unused-context-prompt)
                             (catch Exception _ nil))]
    (let [msg-pairs (->> messages
                         (mapv #(select-keys % [:role :content])))
          result (run-rlm-detection @prompt-var {:messages msg-pairs}
                                    :lm-config lm-config
                                    :usage-tracker usage-tracker)]
      (when (:detected result)
        {:pattern-id :unused-context
         :severity (or (:severity result) :medium)
         :detail (or (:detail result) "Context provided but not used in response")
         :waste-tokens (or (:waste-tokens-estimate result) 0)
         :waste-cost (estimate-cost-at-rate (or (:waste-tokens-estimate result) 0) mid-model-rate)}))))

(defn detect-output-verbosity-mismatch
  "Detect response much longer/shorter than task warrants.
   Requires LLM analysis via RLM.

   Returns: waste pattern map or nil"
  [messages usage-history & {:keys [lm-config usage-tracker]}]
  (when-let [prompt-var (try (requiring-resolve 'ai.brainyard.analytics.core.prompts/waste-verbosity-prompt)
                             (catch Exception _ nil))]
    (let [msg-pairs (->> messages
                         (mapv #(select-keys % [:role :content])))
          context {:messages msg-pairs
                   :usage-history (mapv #(select-keys % [:model :input-tokens :output-tokens])
                                        usage-history)}
          result (run-rlm-detection @prompt-var context
                                    :lm-config lm-config
                                    :usage-tracker usage-tracker)]
      (when (:detected result)
        {:pattern-id :output-verbosity-mismatch
         :severity (or (:severity result) :low)
         :detail (or (:detail result) "Response verbosity doesn't match task complexity")
         :waste-tokens (or (:waste-tokens-estimate result) 0)
         :waste-cost (estimate-cost-at-rate (or (:waste-tokens-estimate result) 0) mid-model-rate)}))))

;; ============================================================================
;; Orchestrator
;; ============================================================================

(defn detect-all-waste
  "Run all waste detectors. Heuristic detectors always run.
   LLM-based detectors run only when :lm-config is provided.

   Parameters:
     messages      - Conversation history [{:role :content}]
     usage-summary - From usage tracker {:totals {...}}
     usage-history - Call history [{:model :input-tokens ...}]

   Options:
     :lm-config     - LM for LLM-based detection (nil = heuristics only)
     :usage-tracker - For tracking analysis cost

   Returns:
     {:patterns [...] :total-waste-tokens N :total-waste-cost N :waste-percentage N}"
  [messages usage-summary usage-history & {:keys [lm-config usage-tracker]}]
  (let [;; Always run heuristic detectors
        heuristic-results (remove nil?
                                  [(detect-context-bloat usage-summary)
                                   (detect-model-overkill usage-history)
                                   (detect-oversized-system-prompt usage-history)
                                   (detect-redundant-requests messages)])
        ;; Optionally run LLM-based detectors
        llm-results (when lm-config
                      (remove nil?
                              [(detect-token-leakage messages
                                                     :lm-config lm-config :usage-tracker usage-tracker)
                               (detect-unused-context messages
                                                      :lm-config lm-config :usage-tracker usage-tracker)
                               (detect-output-verbosity-mismatch messages usage-history
                                                                 :lm-config lm-config :usage-tracker usage-tracker)]))
        all-patterns (into (vec heuristic-results) llm-results)
        total-waste-tokens (reduce + (map :waste-tokens all-patterns))
        total-waste-cost (reduce + (map :waste-cost all-patterns))
        total-cost (get-in usage-summary [:totals :total-cost] 0)
        waste-pct (if (pos? total-cost)
                    (* 100.0 (/ total-waste-cost total-cost))
                    0.0)]
    {:patterns all-patterns
     :total-waste-tokens total-waste-tokens
     :total-waste-cost total-waste-cost
     :waste-percentage waste-pct}))
