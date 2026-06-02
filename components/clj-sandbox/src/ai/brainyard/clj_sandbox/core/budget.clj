;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.core.budget
  "Token budget tracking for RLM completion loops.

   Provides the LLM with visibility into remaining context budget
   by appending a compact status string to each feedback message.
   This guides the LLM to wrap up when budget is tight.")

(defn create-budget-monitor
  "Create a budget monitor atom.

   Parameters:
     max-context-tokens - Maximum tokens for the context window
     max-iterations     - Maximum iterations allowed

   Returns: atom with budget state."
  [max-context-tokens max-iterations]
  (atom {:max-tokens max-context-tokens
         :max-iterations max-iterations
         :current-iteration 0
         :estimated-tokens 0
         :compaction-count 0}))

(defn estimate-tokens
  "Estimate token count for a messages vector using char/4 heuristic."
  [messages]
  (quot (reduce + 0 (map #(count (str (:content %))) messages)) 4))

(defn update-budget!
  "Update budget after an iteration.

   Parameters:
     budget-atom - Budget monitor atom
     messages    - Current messages vector
     iteration   - Current iteration number"
  [budget-atom messages iteration]
  (let [est (estimate-tokens messages)]
    (swap! budget-atom assoc
           :current-iteration iteration
           :estimated-tokens est)))

(defn record-compaction!
  "Record that a compaction occurred."
  [budget-atom]
  (swap! budget-atom update :compaction-count inc))

(defn budget-status-string
  "Format compact budget status for appending to feedback messages.
   Returns: '[Budget: 45K/128K tokens, iter 7/20]'"
  [budget-atom]
  (let [{:keys [max-tokens max-iterations current-iteration estimated-tokens]} @budget-atom
        used-k (quot estimated-tokens 1000)
        max-k (quot max-tokens 1000)]
    (str "[Budget: " used-k "K/" max-k "K tokens, iter "
         current-iteration "/" max-iterations "]")))

(defn budget-exhaustion-ratio
  "Return the fraction of budget used (0.0 to 1.0+)."
  [budget-atom]
  (let [{:keys [max-tokens estimated-tokens]} @budget-atom]
    (if (pos? max-tokens)
      (double (/ estimated-tokens max-tokens))
      0.0)))
