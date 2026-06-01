;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

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

;; ============================================================================
;; Memory helpers
;; ============================================================================

(defn st-memory-has-value?
  "Condition function: check if short-term memory has a value matching schema.
   Used as condition-fn in BT condition nodes.

   Opts: {:path [key-path] :schema <malli-schema>}"
  [args]
  (engine/st-memory-has-value? args))

;; ============================================================================
;; DSPy action (for use as action-fn in BT)
;; ============================================================================

(def dspy
  "DSPy action function for behavior trees.
   Use as an action-fn with opts: {:id :node-id :signature sig :operation :predict}"
  dspy-action/dspy)
