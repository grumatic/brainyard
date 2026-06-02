;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.analytics.core.prompts
  "RLM prompt templates for LLM-based analytics.

   Each prompt is designed for the RLM sandbox:
   - Context is a Clojure map available as `context` variable
   - LLM writes Clojure code to analyze the data
   - Result is returned via (FINAL json-string)")

;; ============================================================================
;; PQS Analysis Prompt
;; ============================================================================

(def pqs-analysis-prompt
  "You are analyzing a conversation between a user and an AI assistant to refine Prompt Quality Scores.

The `context` variable contains a map with:
- `:messages` — vector of {:role \"user\"|\"assistant\" :content \"...\"}
- `:heuristic-scores` — map with :overall-score, :dimensions, :adjustments from heuristic analysis

Your job:
1. Review each user message for quality patterns the heuristics may have missed
2. Provide dimension-level adjustments (each -5 to +5) to refine the heuristic scores
3. Generate 2-3 specific, actionable recommendations

Dimensions:
- :specificity (max 25) — concrete entities, file paths, function names
- :task-atomicity (max 25) — single focused task vs bundled
- :context-completeness (max 20) — code, errors, env details
- :acceptance-criteria (max 20) — clear definition of done
- :clarity (max 10) — unambiguous language

Output a JSON string with:
{\"adjustments\": {\"specificity\": N, \"task-atomicity\": N, \"context-completeness\": N, \"acceptance-criteria\": N, \"clarity\": N},
 \"recommendations\": [\"specific actionable tip 1\", \"tip 2\"]}

Example:
```clojure
(let [msgs (:messages context)
      scores (:heuristic-scores context)
      user-msgs (filter #(= \"user\" (:role %)) msgs)]
  ;; Analyze patterns...
  (FINAL (clojure.data.json/write-str
           {\"adjustments\" {\"specificity\" 2 \"task-atomicity\" -1
                           \"context-completeness\" 0 \"acceptance-criteria\" 3
                           \"clarity\" 0}
            \"recommendations\" [\"Include the file path when referencing code\"
                               \"Add expected output format to your requests\"]})))
```")

;; ============================================================================
;; Waste Detection Prompts
;; ============================================================================

(def waste-token-leakage-prompt
  "You are analyzing user prompts for token waste — unnecessary content that adds tokens without improving results.

The `context` variable contains:
- `:messages` — vector of user messages {:role \"user\" :content \"...\"}

Look for:
- Unnecessary preambles (\"I was wondering if you could...\", \"Hello, I need help with...\")
- Excessive examples (more than 2 examples showing the same pattern)
- Boilerplate text repeated across messages
- Instructions that duplicate what's already in the system prompt
- Overly verbose explanations where a concise statement would suffice

Output JSON:
{\"detected\": true/false,
 \"detail\": \"description of what was found\",
 \"severity\": \"low\"|\"medium\"|\"high\",
 \"waste-tokens-estimate\": N}

```clojure
(let [msgs (:messages context)
      contents (mapv :content msgs)]
  ;; Analyze for boilerplate, excessive preambles, etc.
  (FINAL (clojure.data.json/write-str
           {\"detected\" false
            \"detail\" \"No significant token leakage found\"
            \"severity\" \"low\"
            \"waste-tokens-estimate\" 0})))
```")

(def waste-unused-context-prompt
  "You are analyzing conversation pairs to detect context that was provided but never used.

The `context` variable contains:
- `:messages` — full conversation [{:role \"user\"|\"assistant\" :content \"...\"}]

For each user message that includes context (code snippets, data, file contents), check if the subsequent assistant response actually referenced or used that context. Context that was provided but ignored represents wasted tokens.

Output JSON:
{\"detected\": true/false,
 \"detail\": \"description of unused context\",
 \"severity\": \"low\"|\"medium\"|\"high\",
 \"waste-tokens-estimate\": N}

```clojure
(let [msgs (:messages context)
      pairs (partition 2 1 msgs)]
  ;; For each user→assistant pair, check if user-provided context was used
  (FINAL (clojure.data.json/write-str
           {\"detected\" false
            \"detail\" \"All provided context appears to be utilized\"
            \"severity\" \"low\"
            \"waste-tokens-estimate\" 0})))
```")

(def waste-verbosity-prompt
  "You are analyzing whether AI responses are appropriately sized for the tasks asked.

The `context` variable contains:
- `:messages` — conversation [{:role :content}]
- `:usage-history` — [{:model :input-tokens :output-tokens}]

Check if responses are:
- Much longer than necessary (e.g., simple yes/no question gets 500 word response)
- Much shorter than expected (e.g., complex analysis question gets one-liner)

Output JSON:
{\"detected\": true/false,
 \"detail\": \"description of mismatch\",
 \"severity\": \"low\"|\"medium\"|\"high\",
 \"waste-tokens-estimate\": N}

```clojure
(let [msgs (:messages context)
      usage (:usage-history context)]
  ;; Compare task complexity to response size
  (FINAL (clojure.data.json/write-str
           {\"detected\" false
            \"detail\" \"Response sizes appear appropriate for tasks\"
            \"severity\" \"low\"
            \"waste-tokens-estimate\" 0})))
```")
