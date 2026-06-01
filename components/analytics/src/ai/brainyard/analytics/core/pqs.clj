;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.analytics.core.pqs
  "Prompt Quality Score (PQS) scoring engine.

   Scores each user prompt 0-100 across five dimensions:
   - Specificity (25%) — concrete entities, file paths, function names
   - Task atomicity (25%) — single focused task vs. bundled requests
   - Context completeness (20%) — code, errors, environment details provided
   - Acceptance criteria (20%) — clear definition of done
   - Clarity (10%) — linguistic clarity, no ambiguity

   Two modes:
   - Heuristic-only (default): regex/NLP-based, zero LLM cost
   - LLM-enhanced (opt-in): refines heuristic scores via RLM"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.set :as set]
            [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Dimension Weights
;; ============================================================================

(def dimension-weights
  {:specificity          25
   :task-atomicity       25
   :context-completeness 20
   :acceptance-criteria  20
   :clarity              10})

;; ============================================================================
;; Text Analysis Helpers
;; ============================================================================

(def ^:private vague-words
  #{"something" "stuff" "thing" "things" "whatever" "somehow"
    "somewhere" "sometime" "maybe" "probably" "kind of" "sort of"
    "basically" "just" "simply"})

(def ^:private stop-words
  #{"the" "a" "an" "is" "are" "was" "were" "be" "been" "being"
    "have" "has" "had" "do" "does" "did" "will" "would" "could"
    "should" "may" "might" "shall" "can" "to" "of" "in" "for"
    "on" "with" "at" "by" "from" "as" "into" "about" "like"
    "through" "after" "over" "between" "out" "against" "during"
    "without" "before" "under" "around" "among" "and" "but" "or"
    "nor" "not" "so" "yet" "both" "either" "neither" "each"
    "every" "all" "any" "few" "more" "most" "other" "some"
    "such" "no" "only" "own" "same" "than" "too" "very"
    "i" "me" "my" "we" "our" "you" "your" "he" "she" "it"
    "they" "them" "their" "this" "that" "these" "those" "what"
    "which" "who" "whom" "how" "when" "where" "why"})

(defn- count-words
  "Count words in text."
  [text]
  (count (str/split (str/trim text) #"\s+")))

(defn- tokenize-words
  "Split text into lowercase words."
  [text]
  (when (seq text)
    (->> (str/split (str/lower-case text) #"[^a-z0-9_\-]+")
         (remove str/blank?))))

(defn- word-set
  "Get set of non-stop-words from text."
  [text]
  (->> (tokenize-words text)
       (remove stop-words)
       set))

(defn- jaccard-similarity
  "Jaccard similarity between two sets."
  [s1 s2]
  (if (and (seq s1) (seq s2))
    (let [intersection (count (set/intersection s1 s2))
          union (count (set/union s1 s2))]
      (if (zero? union) 0.0 (double (/ intersection union))))
    0.0))

(defn- sentences
  "Split text into sentences."
  [text]
  (->> (str/split text #"[.!?]+\s*")
       (remove str/blank?)))

;; ============================================================================
;; Pattern Detectors
;; ============================================================================

(def ^:private file-path-pattern
  #"(?:(?:[/\\][\w.\-]+){2,}|[\w.\-]+\.(?:clj[cs]?|java|py|js|ts|tsx|jsx|rb|rs|go|sql|yaml|yml|json|toml|edn|xml|html|css|sh|md))")

(def ^:private function-name-pattern
  #"(?:`[\w.\-/!?*+]+`|[\w.\-]+/[\w.\-!?*+]+|\b[a-z][\w]*[!?]|\bdef[a-z]*\s+[\w.\-!?*+]+)")

(def ^:private specific-value-pattern
  #"(?:\b\d+(?:\.\d+)?(?:\s*(?:ms|sec|min|hrs?|MB|GB|KB|bytes|px|em|rem|%))\b|\b(?:true|false|nil|null)\b|:\w[\w.\-]*|\"[^\"]{1,50}\"|\b0x[0-9a-fA-F]+\b|\b\d{2,}\b)")

(def ^:private error-pattern
  #"(?i)(?:error|exception|stacktrace|traceback|caused by|at\s+[\w.$]+\.[\w$]+\(|TypeError|NullPointer|ClassCast|ArityException|IllegalArgument|ENOENT|EACCES|404|500|403|401)")

(def ^:private code-block-pattern
  #"(?s)```[\s\S]*?```|`[^`]+`")

(def ^:private env-version-pattern
  #"(?i)(?:version\s*[\d.]+|jdk\s*\d+|java\s*\d+|node\s*v?\d+|python\s*\d|clojure\s*[\d.]+|ubuntu|macos|darwin|linux|windows)")

(def ^:private passive-voice-pattern
  #"(?i)\b(?:is|are|was|were|been|be|being)\s+(?:\w+\s+)?(?:ed|en)\b")

(def ^:private acceptance-pattern
  #"(?i)\b(?:should|must|expect(?:ed)?|ensure|verify|assert|guarantee|return(?:s|ing)?)\b")

(def ^:private format-spec-pattern
  #"(?i)(?:return(?:s|ing)?\s+(?:as\s+)?(?:json|csv|edn|xml|html|markdown|text|table|list|map|vector|string|boolean|integer|number)|format(?:ted)?\s+(?:as|like|in)|output\s+(?:as|in|format))")

(def ^:private multi-task-pattern
  #"(?i)\b(?:and\s+(?:also|then)|then\s+(?:also)?|plus\s+(?:also)?|additionally|furthermore|also\s+(?:please|can you)|as\s+well\s+as)\b")

(def ^:private bullet-item-pattern
  #"(?m)^\s*(?:[-*+]|\d+[.)]) ")

;; ============================================================================
;; Dimension Scorers
;; ============================================================================

(defn score-specificity
  "Score prompt specificity 0-25.
   Rewards: file paths, function names, specific values, technical terms.
   Penalizes: vague words, very short prompts."
  [prompt]
  (let [has-file-paths (boolean (re-find file-path-pattern prompt))
        has-func-names (boolean (re-find function-name-pattern prompt))
        has-values (boolean (re-find specific-value-pattern prompt))
        words (tokenize-words prompt)
        vague-count (count (filter vague-words words))
        word-count (count words)
        technical-count (count (filter #(or (re-matches #"[a-z][\w]*[-_][\w]+" %)
                                            (re-matches #"[a-z]+[A-Z][\w]*" (str %)))
                                       (str/split prompt #"\s+")))
        has-technical (pos? technical-count)]
    (-> 0
        (cond-> has-file-paths (+ 5)
                has-func-names (+ 5)
                has-values (+ 5)
                has-technical (+ 5))
        (- (* 3 (min vague-count 3)))
        (cond-> (< word-count 10) (- 3))
        (max 0)
        (min 25))))

(defn score-task-atomicity
  "Score whether prompt is a single atomic task 0-25.
   Start at 25, penalize multi-task indicators."
  [prompt]
  (let [multi-task-matches (count (re-seq multi-task-pattern prompt))
        question-marks (count (filter #(= % \?) (seq prompt)))
        bullet-items (count (re-seq bullet-item-pattern prompt))
        word-count (count-words prompt)]
    (-> 25
        (- (* 5 (min multi-task-matches 3)))
        (cond-> (> question-marks 2) (- 5)
                (> bullet-items 3) (- 5)
                (> word-count 500) (- 3))
        (max 0)
        (min 25))))

(defn score-context-completeness
  "Score context provision 0-20.
   Rewards: code blocks, error messages, file refs, env info."
  [prompt]
  (let [has-code-blocks (boolean (re-find code-block-pattern prompt))
        has-errors (boolean (re-find error-pattern prompt))
        has-file-refs (boolean (re-find file-path-pattern prompt))
        has-env-info (boolean (re-find env-version-pattern prompt))]
    (-> 0
        (cond-> has-code-blocks (+ 5)
                has-errors (+ 5)
                has-file-refs (+ 5)
                has-env-info (+ 5))
        (max 0)
        (min 20))))

(defn score-acceptance-criteria
  "Score whether success criteria are stated 0-20.
   Rewards: should/must/expected, output format spec, examples, test criteria."
  [prompt]
  (let [has-acceptance (boolean (re-find acceptance-pattern prompt))
        has-format-spec (boolean (re-find format-spec-pattern prompt))
        has-example-output (boolean (re-find #"(?i)(?:for example|e\.g\.|example output|expected output|like this)" prompt))
        has-test-criteria (boolean (re-find #"(?i)(?:test(?:s|ed|ing)?|verify|check(?:s|ed|ing)?|assert|validate)" prompt))]
    (-> 0
        (cond-> has-acceptance (+ 5)
                has-format-spec (+ 5)
                has-example-output (+ 5)
                has-test-criteria (+ 5))
        (max 0)
        (min 20))))

(defn score-clarity
  "Score linguistic clarity 0-10.
   Start at 10, penalize long sentences, passive voice, ambiguity."
  [prompt]
  (let [sents (sentences prompt)
        avg-sentence-words (if (seq sents)
                             (double (/ (count-words prompt) (count sents)))
                             0)
        passive-count (count (re-seq passive-voice-pattern prompt))
        has-double-negative (boolean (re-find #"(?i)\b(?:not\s+(?:un|in|im)|don't\s+not|isn't\s+not|no\s+(?:un|in|im))\w*" prompt))]
    (-> 10
        (cond-> (> avg-sentence-words 30) (- 2)
                (> passive-count 1) (- 2)
                has-double-negative (- 2))
        (max 0)
        (min 10))))

(defn score-prompt
  "Score a single user prompt across all dimensions.
   Returns: {:specificity N :task-atomicity N :context-completeness N
             :acceptance-criteria N :clarity N :total N}"
  [prompt]
  (let [dims {:specificity (score-specificity prompt)
              :task-atomicity (score-task-atomicity prompt)
              :context-completeness (score-context-completeness prompt)
              :acceptance-criteria (score-acceptance-criteria prompt)
              :clarity (score-clarity prompt)}
        total (reduce + (vals dims))]
    (assoc dims :total total)))

;; ============================================================================
;; Outcome Adjustments (Conversation Flow Analysis)
;; ============================================================================

(defn- extract-user-messages
  "Extract user messages from conversation."
  [messages]
  (->> messages
       (filter #(= "user" (:role %)))
       (mapv :content)))

(defn- is-correction-turn?
  "Detect if a user message is correcting a previous misunderstanding.
   Heuristic: user message follows assistant response and shares >0.4 similarity
   with a prior user message, or contains correction indicators."
  [user-msg prior-user-msgs]
  (let [correction-indicators #"(?i)\b(?:no,?\s+(?:i\s+)?(?:meant|mean)|not\s+(?:that|what)|actually|instead|wrong|incorrect|that's\s+not|i\s+said|what\s+i\s+(?:meant|want))\b"
        has-correction-words (boolean (re-find correction-indicators user-msg))
        msg-words (word-set user-msg)
        high-similarity (some #(> (jaccard-similarity msg-words (word-set %)) 0.4)
                              prior-user-msgs)]
    (or has-correction-words (boolean high-similarity))))

(defn- detect-retry-storms
  "Detect retry storms: 3+ messages with Jaccard similarity > 0.6.
   Returns count of storms detected."
  [user-messages]
  (let [word-sets (mapv word-set user-messages)
        n (count word-sets)]
    (if (< n 3)
      0
      (loop [i 0 storms 0 used #{}]
        (if (>= i n)
          storms
          (if (used i)
            (recur (inc i) storms used)
            (let [cluster (loop [j (inc i) group #{i}]
                            (if (>= j n)
                              group
                              (if (and (not (used j))
                                       (> (jaccard-similarity (nth word-sets i) (nth word-sets j)) 0.6))
                                (recur (inc j) (conj group j))
                                (recur (inc j) group))))]
              (if (>= (count cluster) 3)
                (recur (inc i) (inc storms) (into used cluster))
                (recur (inc i) storms used)))))))))

(defn compute-adjustments
  "Compute outcome-based adjustments from conversation flow.
   Analyzes turn patterns for corrections, one-turn completions, retry storms.
   Returns: {:correction-turns N :one-turn-completions N :retry-storms N :total N}"
  [messages]
  (let [user-msgs (extract-user-messages messages)
        ;; Detect correction turns
        corrections (loop [i 1 acc 0]
                      (if (>= i (count user-msgs))
                        acc
                        (if (is-correction-turn? (nth user-msgs i)
                                                 (subvec user-msgs 0 i))
                          (recur (inc i) (inc acc))
                          (recur (inc i) acc))))
        ;; One-turn completions = user messages that are NOT corrections (excluding first)
        one-turn-completions (max 0 (- (dec (count user-msgs)) corrections))
        ;; Retry storms
        retry-storms (detect-retry-storms user-msgs)
        ;; Calculate adjustments
        correction-adj (* -3 corrections)
        one-turn-adj (* 5 (min one-turn-completions 3))  ;; cap bonus
        retry-adj (* -10 retry-storms)]
    {:correction-turns correction-adj
     :one-turn-completions one-turn-adj
     :retry-storms retry-adj
     :total (+ correction-adj one-turn-adj retry-adj)}))

;; ============================================================================
;; PQS Heuristic Scoring
;; ============================================================================

(defn score-pqs-heuristic
  "Pure heuristic PQS scoring. No LLM calls.
   Scores each user message, averages across messages, applies adjustments.

   Parameters:
     messages - Conversation history [{:role :content}]

   Returns:
     {:overall-score N
      :dimensions {:specificity N :task-atomicity N ...}
      :adjustments {:correction-turns N :one-turn-completions N :retry-storms N :total N}
      :per-prompt [{:content str :scores {:specificity N ...}}]}"
  [messages]
  (let [user-msgs (extract-user-messages messages)]
    (if (empty? user-msgs)
      {:overall-score 0
       :dimensions (zipmap (keys dimension-weights) (repeat 0))
       :adjustments {:correction-turns 0 :one-turn-completions 0 :retry-storms 0 :total 0}
       :per-prompt []}
      (let [;; Score each user message
            per-prompt (mapv (fn [msg]
                               {:content msg
                                :scores (score-prompt msg)})
                             user-msgs)
            ;; Average dimension scores across messages
            avg-dims (reduce-kv
                      (fn [m dim _weight]
                        (assoc m dim
                               (Math/round
                                (double (/ (reduce + (map #(get-in % [:scores dim]) per-prompt))
                                           (count per-prompt))))))
                      {}
                      dimension-weights)
            ;; Compute adjustments
            adjustments (compute-adjustments messages)
            ;; Calculate overall score
            base-score (reduce + (vals avg-dims))
            overall (-> (+ base-score (:total adjustments))
                        (max 0)
                        (min 100))]
        {:overall-score overall
         :dimensions avg-dims
         :adjustments adjustments
         :per-prompt per-prompt}))))

;; ============================================================================
;; PQS LLM-Enhanced Scoring (Optional)
;; ============================================================================

(defn score-pqs-llm
  "LLM-enhanced PQS scoring via RLM.
   Refines heuristic scores and generates natural language recommendations.

   Parameters:
     messages         - Conversation history
     heuristic-scores - Result from score-pqs-heuristic

   Options:
     :lm-config     - LM config for RLM calls
     :usage-tracker - Usage tracker for cost tracking

   Returns: updated scores map with :recommendations added"
  [messages heuristic-scores & {:keys [lm-config usage-tracker]}]
  (if-let [completion-fn (try (requiring-resolve 'ai.brainyard.clj-sandbox.interface/completion)
                              (catch Exception _ nil))]
    (try
      (let [prompt (requiring-resolve 'ai.brainyard.analytics.core.prompts/pqs-analysis-prompt)
            context {:messages (mapv #(select-keys % [:role :content]) messages)
                     :heuristic-scores (select-keys heuristic-scores [:overall-score :dimensions :adjustments])}
            result (completion-fn
                    @prompt
                    context
                    :max-iterations 5
                    :lm-config lm-config
                    :usage-tracker usage-tracker)]
        (if (and (:answer result) (not= (:terminated-by result) :max-iterations))
          (try
            (let [parsed (json/read-str (:answer result) :key-fn keyword)]
              (-> heuristic-scores
                  (update :dimensions
                          (fn [dims]
                            (reduce-kv
                             (fn [m dim adj]
                               (if (contains? dims dim)
                                 (update m dim #(-> (+ % (max -5 (min 5 adj)))
                                                    (max 0)
                                                    (min (get dimension-weights dim 25))))
                                 m))
                             dims
                             (:adjustments parsed {}))))
                  (assoc :recommendations (or (:recommendations parsed) []))
                  (as-> result
                        (assoc result :overall-score
                               (-> (reduce + (vals (:dimensions result)))
                                   (+ (get-in result [:adjustments :total] 0))
                                   (max 0)
                                   (min 100))))))
            (catch Exception e
              (mulog/debug ::llm-pqs-parse-failed :message "Failed to parse LLM PQS refinement" :exception e)
              (assoc heuristic-scores :recommendations [])))
          (assoc heuristic-scores :recommendations [])))
      (catch Exception e
        (mulog/warn ::llm-pqs-scoring-failed :message "LLM PQS scoring failed" :exception e)
        (assoc heuristic-scores :recommendations [])))
    (do
      (mulog/debug ::rlm-not-available :message "RLM not available, skipping LLM PQS scoring")
      (assoc heuristic-scores :recommendations []))))

;; ============================================================================
;; Main PQS Entry Point
;; ============================================================================

(defn score-pqs
  "Score a session's prompts using PQS.
   Runs heuristic scoring, then optionally refines with LLM.

   Parameters:
     messages      - Conversation history [{:role :content}]

   Options:
     :lm-config     - LM for LLM-based scoring (nil = heuristics only)
     :usage-tracker - For tracking analysis cost

   Returns: PQS result map"
  [messages & {:keys [lm-config usage-tracker]}]
  (let [heuristic (score-pqs-heuristic messages)]
    (if lm-config
      (score-pqs-llm messages heuristic
                     :lm-config lm-config
                     :usage-tracker usage-tracker)
      (assoc heuristic :recommendations []))))
