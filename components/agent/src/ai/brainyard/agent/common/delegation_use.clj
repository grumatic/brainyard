;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.delegation-use
  "Reusable behavior tree component for sub-agent delegation.
   Ported from cloudcast.backend.agent.common.delegation-use.

   Extracts the common 'rewrite question -> ask sub-agent -> describe result'
   pattern used across domain agents into a composable module.

   Key components:
   - `make-ask-agent-fn` - Higher-order function creating ask-agent actions
   - `delegation-use` - BT subtree factory for the delegation pattern
   - `delegation-skill` - Generic delegation defskill for one-shot usage

   Usage:
     (delegation-use
      {:id-prefix          :my-skill
       :rewrite-signature  #'RewriteQuestion
       :question-key       :delegated-question
       :ask-agent-fn       (make-ask-agent-fn {...})
       :result-key         :agent-result
       :describe-signature #'DescribeResult})"
  (:require [ai.brainyard.agent.core.tool :refer [defskill]]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.mulog.interface :as mulog]))

;; =====================================================
;; MAKE-ASK-AGENT-FN (Higher-Order Function)
;; =====================================================

(defn make-ask-agent-fn
  "Creates a standard ask-agent action function for behavior trees.

   Returns a function with signature (fn [{:keys [st-memory agent]}] ...)
   that follows the behavior tree action contract.

   Parameters:
   - :agent-fn      - The defagent-registered function or var to call (required).
                      Pass as #'var for with-redefs compatibility in tests.
   - :question-key  - Key in st-memory for the question (required)
   - :result-key    - Key in st-memory to store the result (required)
   - :extra-args-fn - Optional (fn [{:keys [st-memory agent]}] extra-args-map)
                      Returns additional keyword args to pass to the agent-fn"
  [{:keys [agent-fn question-key result-key extra-args-fn]}]
  {:pre [(some? agent-fn) (keyword? question-key) (keyword? result-key)]}
  (fn [{:keys [st-memory agent]}]
    (let [agent-session {:user-id    (proto/user-id agent)
                         :session-id (proto/session-id agent)}
          question (get @st-memory question-key)
          extra-args (when extra-args-fn
                       (extra-args-fn {:st-memory st-memory :agent agent}))
          ;; Deref vars at call time for with-redefs compatibility
          invoke-fn (if (var? agent-fn) @agent-fn agent-fn)
          {:keys [result output]} (apply invoke-fn
                                         (concat [:question question
                                                  :agent-session agent-session]
                                                 (mapcat identity extra-args)))]
      (swap! st-memory assoc result-key (str output))
      (mulog/debug ::delegation-result :result-key result-key :output-preview (when output (subs output 0 (min 100 (count output)))))
      result)))

;; =====================================================
;; DELEGATION-USE (BT Subtree Factory)
;; =====================================================

(defn delegation-use
  "Creates a delegation behavior tree: [Rewrite] -> Ask -> Describe

   Returns a [:sequence ...] behavior tree subtree that implements the
   standard delegation pattern used across domain agents.

   Parameters:
   - :id-prefix          - BT node ID prefix keyword (e.g., :my-skill)
   - :rewrite-signature  - DSPy signature var for rewriting (nil to skip)
   - :question-key       - Key for the question in st-memory (required)
   - :ask-agent-fn       - Action function from make-ask-agent-fn (required)
   - :result-key         - Key for the result in st-memory (required)
   - :describe-signature - DSPy signature var for describing result (required)
   - :dspy-fn            - DSPy action function (required, from BT extensions)"
  [{:keys [id-prefix rewrite-signature question-key
           ask-agent-fn result-key describe-signature dspy-fn]}]
  {:pre [(keyword? id-prefix) (keyword? question-key)
         (fn? ask-agent-fn) (keyword? result-key)
         (some? describe-signature)]}
  (let [;; Build node IDs: :<prefix>.<type>/<name>
        seq-id     (keyword (str (name id-prefix) ".sequence") (name result-key))
        rewrite-id (keyword (str (name id-prefix) ".action") "rewrite-question")
        ask-id     (keyword (str (name id-prefix) ".action") "ask-agent")
        describe-id (keyword (str (name id-prefix) ".action") "describe-result")

        ;; Rewrite step (optional)
        rewrite-nodes (when rewrite-signature
                        [[:action
                          {:id rewrite-id
                           :signature rewrite-signature
                           :operation :chain-of-thought
                           :debug {:source :reasoning}}
                          dspy-fn]])

        ;; Ask step (always present)
        ask-nodes [[:action {:id ask-id} ask-agent-fn]]

        ;; Describe step (always present)
        describe-nodes [[:action
                         {:id describe-id
                          :signature describe-signature
                          :operation :chain-of-thought
                          :debug {:source :reasoning}}
                         dspy-fn]]]

    (into [:sequence {:id seq-id}]
          (concat rewrite-nodes ask-nodes describe-nodes))))
