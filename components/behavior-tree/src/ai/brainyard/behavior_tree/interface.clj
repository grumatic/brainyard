;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.behavior-tree.interface
  "Public API for the behavior-tree component.

   Provides a behavior tree engine with:
   - Core node types: sequence, fallback, parallel, condition, action
   - DSPy integration via clj-llm component

   Extended nodes (repeat decorator, tracing-aware overrides, user interaction,
   and visualization) are provided by the agent component (agent.core.bt)
   and installed as defmethod overrides when the agent namespace is loaded."
  (:require [ai.brainyard.behavior-tree.core.engine :as engine]
            [ai.brainyard.behavior-tree.core.dspy-action :as dspy-action]
            [ai.brainyard.behavior-tree.interface.protocol :as p]))

;; ============================================================================
;; Status constants
;; ============================================================================

(def success p/success)
(def failure p/failure)
(def running p/running)

;; ============================================================================
;; Core BT operations
;; ============================================================================

(defn build
  "Build a behavior tree from a config vector and context map.

   Config format: [node-type opts? & children]
   Context keys:
   - :st-memory  — initial short-term memory map (wrapped in atom)
   - :agent      — agent object for tracing/interaction (optional)

   Returns {:tree <built-tree> :context <enriched-context>}"
  [config context]
  (engine/build config context))

(defn run
  "Run a built behavior tree. Returns :success, :failure, or :running."
  [bt]
  (engine/run bt))

(defn run-task
  "SPIKE (§15): run a built behavior tree as an effect, returning a Task that
   completes with :success / :failure / :running. Not used in production."
  [bt]
  (engine/run-task bt))

;; ============================================================================
;; Memory helpers
;; ============================================================================

(defn st-memory-has-value?
  "Condition function: check if short-term memory has a value matching schema.
   Used as condition-fn in BT condition nodes.

   Opts: {:path [key-path] :schema <malli-schema>}"
  [args]
  (engine/st-memory-has-value? args))

(defn input-violations
  "CR-BT-26: every declared signature input in `input-fields` that is absent
   from `state` (and not marked `{:optional true}`) or present with the wrong
   shape. Returns `[{:key _ :reason :missing|:invalid :errors _} …]`; empty
   when the contract holds.

   The `dspy` node runs this itself on every call — exported so a caller can
   assert the same contract WITHOUT an LLM round-trip, which is how an agent
   pins that its own producers write what its signature declares. A signature
   with no `:inputs` field map declares no contract and yields no violations.

   Design: docs/design/bt-context-schema-design.md §3.6."
  [input-fields state]
  (dspy-action/input-violations input-fields state))

;; ============================================================================
;; DSPy action (for use as action-fn in BT)
;; ============================================================================

(def dspy
  "DSPy action function for behavior trees.
   Use as an action-fn with opts: {:id :node-id :signature sig :operation :predict}"
  dspy-action/dspy)

(def signature-from-st-memory
  "Value for a dspy node's `:signature` opt meaning \"read the compiled
   signature from `(:signature @st-memory)` at call time\" — for agents whose
   output schema depends on runtime config. See dspy-action/from-st-memory."
  dspy-action/from-st-memory)
