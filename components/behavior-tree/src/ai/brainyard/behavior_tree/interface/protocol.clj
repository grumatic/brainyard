;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.behavior-tree.interface.protocol)

(def success :success)
(def failure :failure)
(def running :running)

(defmulti tick
  "Execute the node and return success, failure, or running."
  (fn [node _context] (:type node)))

(defmulti build
  "Build a behavior tree node based on its type."
  (fn [type _args] type))

(defn opts+children
  "Extract options and children from the config vector."
  [args]
  (if (and (seq args) (map? (first args)))
    [(first args) (rest args)]
    [{} args]))
