;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.evaluation
  "DSPy evaluation signature + evidence helpers for the CoAct agent.

   Provides:
   - EvaluateAnswer DSPy signature (independent hallucination/completeness check)
   - Evidence helpers (build-iteration-evidence, build-evaluation-context)

   The in-loop refinement actions that consume these (prepare-eval / process-eval
   / refine-self) live in `ai.brainyard.agent.common.coact-agent`. The standalone
   FinalizeAnswer pass was folded into ThinkActCode's answer channel
   (goal-achieved / next-user-prompt) and removed."
  (:require [ai.brainyard.clj-llm.interface :refer [defschemas defsignature]]
            [clojure.string :as str]))

;; ============================================================================
;; Evaluation Schemas & Signature
;; ============================================================================

(defschemas eval-domain
  {::eval-verdict [:enum {:desc "Evaluation verdict"}
                   "COMPLETE" "HALLUCINATED" "INCOMPLETE"]
   ::eval-detail [:string {:desc "Brief explanation of the verdict — what was fabricated or missing, or why the answer is complete"}]})

(defsignature EvaluateAnswer
  "You are a hallucination detector for an AI agent that answers questions by writing code in a sandbox.

The agent had access to these information sources:
1. SANDBOX OUTPUTS (code execution results — strongest ground truth)
2. SYSTEM INSTRUCTIONS (describes available functions and workflow)
3. CONVERSATION CONTEXT (prior Q&A history)
4. GENERAL KNOWLEDGE (for non-data questions)

Check:
1. Does the answer fabricate specific data values (names, numbers, URLs, IDs) that aren't in sandbox outputs AND aren't inferable from instructions/context?
2. Does the answer contradict what sandbox tools actually returned?
3. Does the answer make up tool results or pretend tools returned data they didn't?

It is NOT hallucination if the agent:
- Describes functions using knowledge from its instructions
- Provides general explanations alongside sandbox data
- Reformats or summarizes sandbox output in human-readable form
- Uses its training knowledge for non-data answers"
  {:inputs {:question [:string {:desc "User question"}]
            :answer [:string {:desc "Answer to the question"}]
            :evidence [:string {:desc "Actual sandbox outputs from code execution (ground truth)"}]
            :eval-context [:string {:desc "System instructions and conversation context the agent had access to"}]}
   :outputs {:verdict ::eval-verdict
             :detail ::eval-detail}})

;; ============================================================================
;; Evidence Helpers
;; ============================================================================

(def truncation-file-re
  "Regex to extract temp file path from truncation markers."
  #"--- Full content saved to: (.+?) ---")

(defn recover-truncated-text
  "If text contains truncation markers with temp file paths, read the temp files
   and return the full content. Otherwise return text unchanged."
  [text]
  (if-let [match (re-find truncation-file-re (str text))]
    (let [path (second match)]
      (try
        (let [f (java.io.File. ^String path)]
          (if (.exists f)
            (slurp f)
            text))
        (catch Exception _ text)))
    text))

(defn build-iteration-evidence
  "Extract actual outputs from CoAct iterations as ground-truth evidence.
   Covers BOTH the code channel (`:code-results`) and the tool channel
   (`:tool-results`) so tool-only turns still produce evidence — without this,
   a tool-driven answer reaches FinalizeAnswer/EvaluateAnswer with empty
   evidence, which can make the model blank or distrust a perfectly good answer.
   Auto-recovers truncated outputs from temp files when available.
   Returns a string summarizing what tools/code actually returned."
  [iterations]
  (let [evidence (->> iterations
                      (keep (fn [{:keys [iteration code-results tool-results error]}]
                              (let [outputs (when (seq code-results)
                                              (->> code-results
                                                   (keep :output)
                                                   (remove str/blank?)
                                                   (map recover-truncated-text)))
                                    result-strs (when (and (seq code-results) (empty? outputs))
                                                  (keep #(when (some? (:result %))
                                                           (let [s (pr-str (:result %))]
                                                             (when-not (str/blank? s) s)))
                                                        code-results))
                                    codes (when (seq code-results)
                                            (keep :code code-results))
                                    script-contents (when (seq code-results)
                                                      (->> code-results
                                                           (keep :script-content)
                                                           (remove str/blank?)))
                                    tool-strs (when (seq tool-results)
                                                (->> tool-results
                                                     (keep (fn [{:keys [tool-name tool-args tool-result]}]
                                                             (let [r (str tool-result)]
                                                               (when-not (str/blank? r)
                                                                 (str "Tool: " tool-name
                                                                      (when (seq tool-args)
                                                                        (str " " (pr-str tool-args)))
                                                                      "\nResult: " (recover-truncated-text r))))))
                                                     vec))]
                                (when (or (seq outputs) (seq result-strs) (seq tool-strs) error)
                                  (str "--- Iteration " iteration " ---\n"
                                       (when (seq codes)
                                         (str "Code: " (first codes) "\n"))
                                       (when (seq script-contents)
                                         (str "Script content:\n" (first script-contents) "\n"))
                                       (when error
                                         (str "Error: " error "\n"))
                                       (when (seq outputs)
                                         (str "Output:\n" (str/join "\n" outputs) "\n"))
                                       (when (seq result-strs)
                                         (str "Result: " (str/join ", " result-strs) "\n"))
                                       (when (seq tool-strs)
                                         (str (str/join "\n" tool-strs) "\n"))))))))
        joined (str/join "\n" evidence)]
    (if (> (count joined) 400000)
      (subs joined 0 400000)
      joined)))

(defn build-react-evidence
  "Extract tool call results from React iterations as ground-truth evidence.
   React format: [{:iteration N :thought str :actions [{:tool-name :tool-args :tool-result}] :observation str}]
   Returns a formatted string similar to build-iteration-evidence."
  [iterations]
  (let [evidence (->> iterations
                      (keep (fn [{:keys [iteration thought actions observation]}]
                              (when (or (seq actions) observation)
                                (str "--- Iteration " iteration " ---\n"
                                     (when thought
                                       (str "Thought: " thought "\n"))
                                     (when (seq actions)
                                       (str/join "\n"
                                                 (map (fn [{:keys [tool-name tool-args tool-result]}]
                                                        (str "Tool: " tool-name
                                                             (when tool-args (str " " (pr-str tool-args)))
                                                             "\nResult: " (str tool-result)))
                                                      actions)))
                                     (when (seq actions) "\n")
                                     (when observation
                                       (str "Observation: " observation "\n")))))))
        joined (str/join "\n" evidence)]
    (if (> (count joined) 400000)
      (subs joined 0 400000)
      joined)))

(defn build-evidence
  "Build evidence from iterations, auto-detecting format (CoAct/code-channel vs React).
   CoAct/code-channel iterations have :code-results; React iterations have :actions/:observation."
  [iterations]
  (if (empty? iterations)
    ""
    (if (:code-results (first iterations))
      (build-iteration-evidence iterations)
      (build-react-evidence iterations))))

(defn- truncate
  "Truncate string to max-len chars, appending '...' if truncated."
  [s max-len]
  (let [s (or s "")]
    (if (<= (count s) max-len) s (str (subs s 0 max-len) "..."))))

(defn build-evaluation-context
  "Build a brief context summary for FinalizeAnswer/EvaluateAnswer.
   Only includes agent-specific instruction, agent-context, and tool-context
   (each capped at 500 chars). The full CoAct system prompt and sandbox docs
   are NOT included — the evaluator only needs to understand the agent's
   task and constraints, not the sandbox execution environment."
  [st-memory-snapshot]
  (let [instruction (:instruction st-memory-snapshot)
        agent-context (:agent-context st-memory-snapshot)
        tool-context (:tool-context st-memory-snapshot)]
    (cond-> ""
      (seq instruction)   (str "Agent instruction:\n" (truncate instruction 500) "\n\n")
      (seq agent-context) (str "Agent context:\n" (truncate agent-context 500) "\n\n")
      (seq tool-context)  (str "Tool context:\n" (truncate tool-context 500)))))
