;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.behavior-tree.core.engine
  (:require [ai.brainyard.behavior-tree.interface.protocol :as p]
            [ai.brainyard.behavior-tree.core.nodes]
            [malli.core :as m]))

(defn build
  "Build a behavior tree from a config vector.

   Context options:
   - :st-memory  — initial short-term memory map (will be wrapped in atom)
   - :agent      — agent object for tracing/user interaction (optional)"
  [[node-type & args :as _config]
   {:keys [st-memory] :as context}]
  {:tree (p/build node-type args)
   :context (assoc context :st-memory (atom (or st-memory {})))})

(defn run
  "Run the behavior tree with the given context."
  [{:keys [tree context] :as _bt}]
  (p/tick tree context))

;; ## Custom Behavior Tree Conditions

(defn st-memory-has-value?
  "Check if short-term memory has a value matching the given Malli schema.
   Used as a condition-fn in BT condition nodes."
  [{{:keys [path schema]} :opts
    :keys [st-memory]}]
  (let [st-memory-state @st-memory]
    (m/validate
     schema
     (if path
       (get-in st-memory-state path)
       st-memory-state))))
