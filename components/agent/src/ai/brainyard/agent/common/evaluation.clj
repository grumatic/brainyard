;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.evaluation
  "DSPy evaluation signatures and BT evaluation actions for the RLM agent.

   Provides:
   - EvaluateAnswer / FinalizeAnswer DSPy signatures
   - Evidence helpers (build-iteration-evidence, build-evaluation-context)
   - BT actions: prepare-evaluation, process-evaluation, prepare-finalize,
     finalize-fallback, prepare-refinement"
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.clj-llm.interface :refer [defschemas defsignature]]
            [ai.brainyard.behavior-tree.interface :as bt]
            [ai.brainyard.agent.core.hooks :as hooks]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog]))

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
;; FinalizeAnswer Signature
;; ============================================================================

(defschemas finalize-domain
  {::goal-achieved [:boolean {:desc "Whether the question was fully and accurately answered"}]})

(defsignature FinalizeAnswer
  "You are finalizing the answer from an AI agent that writes Clojure code in a sandbox REPL.

The agent produced a DRAFT answer via `(FINAL \"...\")` during code execution. Your job is to
synthesize a comprehensive, polished answer using ALL available evidence from the sandbox iterations.

RULES:
1. Use the EVIDENCE (sandbox outputs) as ground truth — do NOT fabricate data
2. If the draft answer is incomplete but evidence contains the missing information, include it
3. If the evidence is insufficient, say so honestly rather than guessing
4. Preserve any data tables, code examples, or structured content from the evidence
5. Format the answer clearly with markdown when appropriate
6. Set goal-achieved to true only if the question is fully answered with evidence support"
  {:inputs {:question [:string {:desc "User question"}]
            :answer [:string {:desc "Answer to the question"}]
            :evidence [:string {:desc "All sandbox code outputs and results from iterations (ground truth)"}]
            :eval-context [:string {:desc "System instructions and conversation context"}]
            :todo-list [:any {:desc "Current task progress list (may be nil)"}]}
   :outputs {:answer [:string {:desc "Answer to the question"}]
             :goal-achieved ::goal-achieved}})

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
  "Extract actual sandbox outputs from RLM iterations as ground-truth evidence.
   Auto-recovers truncated outputs from temp files when available.
   Returns a string summarizing what tools actually returned."
  [iterations]
  (let [evidence (->> iterations
                      (keep (fn [{:keys [iteration eval-results error]}]
                              (let [outputs (when (seq eval-results)
                                              (->> eval-results
                                                   (keep :output)
                                                   (remove str/blank?)
                                                   (map recover-truncated-text)))
                                    result-strs (when (and (seq eval-results) (empty? outputs))
                                                  (keep #(when (some? (:result %))
                                                           (let [s (pr-str (:result %))]
                                                             (when-not (str/blank? s) s)))
                                                        eval-results))
                                    codes (when (seq eval-results)
                                            (keep :code eval-results))
                                    script-contents (when (seq eval-results)
                                                      (->> eval-results
                                                           (keep :script-content)
                                                           (remove str/blank?)))]
                                (when (or (seq outputs) (seq result-strs) error)
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
                                         (str "Result: " (str/join ", " result-strs) "\n"))))))))
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
  "Build evidence from iterations, auto-detecting format (RLM/CodeAct vs React).
   RLM/CodeAct iterations have :eval-results; React iterations have :actions/:observation."
  [iterations]
  (if (empty? iterations)
    ""
    (if (:eval-results (first iterations))
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
   (each capped at 500 chars). The full RLM system prompt and sandbox docs
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

;; ============================================================================
;; BT Evaluation Actions
;; ============================================================================

(defn prepare-evaluation-action
  "BT action: pre-check evaluation and prepare inputs for DSPy hallucination check.
   Handles early exits (no answer, max rounds, exhausted) by setting :answer-complete directly.
   When LLM evaluation is needed, populates st-memory with inputs for the dspy node."
  [{:keys [st-memory ^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent] :as _context}]
  (let [st @st-memory
        answer (:answer st)
        question (:question st)
        iterations (:full-iterations st)
        round (or (:refinement-round st) 0)
        max-refs (config/get-config agent :max-refinements)]
    (cond
      ;; No answer -> incomplete
      (or (nil? answer) (str/blank? answer))
      (do (swap! st-memory assoc
                 :answer-complete false
                 :refinement-feedback "The previous attempt produced no answer. Try a different approach — use available tools to gather information, then produce a complete answer."
                 :refinement-round (inc round))
          bt/success)

      ;; Max refinement rounds reached -> accept whatever we have
      (>= round max-refs)
      (do (swap! st-memory assoc :answer-complete true)
          bt/success)

      ;; RLM exhausted (hit max-iterations without FINAL) -> retry with feedback
      (:iterations-exhausted st)
      (do (swap! st-memory assoc
                 :answer-complete false
                 :refinement-feedback (str "The previous attempt ran out of iterations without producing a final answer. "
                                           "Be more efficient: skip unnecessary exploration, use the data you already have, "
                                           "and call (FINAL answer) as soon as you have enough information.")
                 :refinement-round (inc round))
          bt/success)

      ;; LLM evaluation needed — signal start to TUI and prepare inputs
      :else
      (do
        (swap! st-memory assoc :evaluation-status {:phase :started :round round})
        (when agent
          (hooks/fire! :agent.evaluation/started {:agent agent :round round}))
        (let [evidence (build-iteration-evidence iterations)
              eval-ctx (build-evaluation-context st)
              has-evidence (not (str/blank? evidence))
              evidence-length (count (or evidence ""))
              eval-lm-label (config/get-config agent :eval-lm)]
          (swap! st-memory assoc
                 :evidence (if (str/blank? evidence) "No sandbox outputs available" evidence)
                 :eval-context (if (str/blank? eval-ctx) "No additional context" eval-ctx)
                 :evaluation-status {:phase :llm-calling :round round
                                     :has-evidence has-evidence
                                     :evidence-length evidence-length
                                     :eval-lm-label eval-lm-label})
          (when agent
            (hooks/fire! :agent.evaluation/llm-calling
                         {:agent agent
                          :round round
                          :has-evidence has-evidence
                          :evidence-length evidence-length
                          :eval-lm-label eval-lm-label}))
          bt/success)))))

(defn process-evaluation-action
  "BT action: post-process DSPy evaluation outputs.
   Reads :verdict/:detail from st-memory (set by bt/dspy) and sets
   :answer-complete and :refinement-feedback accordingly."
  [{:keys [st-memory ^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent] :as _context}]
  (let [st @st-memory
        verdict (:verdict st)
        detail (or (:detail st) "")
        reasoning (:last-reasoning st)
        round (or (:refinement-round st) 0)]
    (when agent
      (.update-session-data
       agent {:trace {:agent-id (:agent-id agent) :depth 0
                      :content (format "Answer eval (round %d): %s — %s"
                                       round (or verdict "unknown") detail)}}))
    (let [fire-done! (fn [verdict-kw]
                       (when agent
                         (hooks/fire! :agent.evaluation/done
                                      {:agent agent
                                       :round round
                                       :verdict verdict-kw
                                       :detail detail})))]
      (case verdict
        "COMPLETE"
        (do (swap! st-memory assoc
                   :answer-complete true
                   :evaluation-status {:phase :done :round round :verdict :complete
                                       :detail detail :reasoning reasoning})
            (fire-done! :complete)
            bt/success)

        "HALLUCINATED"
        (do (swap! st-memory assoc
                   :answer-complete false
                   :evaluation-status {:phase :done :round round :verdict :hallucinated
                                       :detail detail :reasoning reasoning}
                   :refinement-feedback
                   (str "CRITICAL: Your previous answer contained HALLUCINATED data. " detail "\n\n"
                        "You MUST use ONLY data from actual tool outputs. "
                        "Run the tools again, check the real output with (pprint result), "
                        "and build your answer ONLY from what the tools actually return. "
                        "Do NOT invent names, values, or data.")
                   :refinement-round (inc round))
            (fire-done! :hallucinated)
            bt/success)

        "INCOMPLETE"
        (do (swap! st-memory assoc
                   :answer-complete false
                   :evaluation-status {:phase :done :round round :verdict :incomplete
                                       :detail detail :reasoning reasoning}
                   :refinement-feedback (str "Previous answer was incomplete. " detail
                                             "\n\nPlease address the feedback and produce a better answer.")
                   :refinement-round (inc round))
            (fire-done! :incomplete)
            bt/success)

        ;; Unrecognized -> accept
        (do (swap! st-memory assoc
                   :answer-complete true
                   :evaluation-status {:phase :done :round round :verdict :accepted
                                       :detail (str verdict ": " detail) :reasoning reasoning})
            (fire-done! :accepted)
            bt/success)))))

;; ============================================================================
;; FinalizeAnswer Actions
;; ============================================================================

(defn prepare-finalize-action
  "BT action: prepare st-memory inputs for the FinalizeAnswer DSPy action.
   Collects evidence from all iterations and builds eval-context."
  [{:keys [st-memory ^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent] :as _context}]
  (let [st @st-memory
        answer (:answer st)
        iterations (:full-iterations st)
        enable? (config/get-config agent :enable-finalize-answer)]
    (if (or (not enable?)
            (str/blank? answer)
            (:goal-achieved st))  ;; Already finalized (e.g., markdown answer)
      ;; Skip finalization — no answer, disabled, or already done -> fail to trigger fallback
      bt/failure
      (let [evidence (build-iteration-evidence (or iterations []))
            eval-context (build-evaluation-context st)]
        (swap! st-memory assoc
               :evidence evidence
               :eval-context eval-context)
        ;; Notify UI of finalization phase
        (when agent
          (.update-session-data agent
                                {:status {:phase :finalizing
                                          :message "Finalizing answer..."}}))
        bt/success))))

(defn finalize-fallback-action
  "BT action: fallback when FinalizeAnswer DSPy fails.
   Keeps the draft answer from the RLM loop as-is."
  [{:keys [st-memory] :as _context}]
  (mulog/debug ::finalize-answer-fallback :message "FinalizeAnswer failed or skipped — keeping draft answer")
  ;; Ensure goal-achieved has a default value
  (when-not (:goal-achieved @st-memory)
    (swap! st-memory assoc :goal-achieved (boolean (:answer @st-memory))))
  bt/success)

(defn prepare-refinement-action
  "BT action: prepare st-memory for a refinement RLM loop.
   Clears the previous answer and sets up state for retry."
  [{:keys [st-memory ^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent] :as context}]
  (let [st @st-memory
        prev-answer (:answer st)
        round (or (:refinement-round st) 0)]
    (when agent
      (.update-session-data
       agent {:trace {:agent-id (:agent-id agent)
                      :depth (or (:depth context) 0)
                      :content (format "Answer incomplete (round %d) — refining with feedback" round)}}))
    (swap! st-memory assoc
           :answer nil
           :full-iterations nil
           :terminated-by nil
           :iterations-exhausted nil
           :previous-answer prev-answer
           :previous-iterations (:full-iterations st))
    bt/success))
