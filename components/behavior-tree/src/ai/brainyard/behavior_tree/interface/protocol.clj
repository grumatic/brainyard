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

(defmulti tick-task
  "SPIKE (docs/design/functional-effect-system.md §15): the effect-shaped
   counterpart of `tick`. Returns a missionary Task completing with success,
   failure or running, instead of returning the keyword directly.

   Deliberately a SECOND multimethod rather than a change to `tick`. Both
   dispatch on the same built tree, so the identical tree can be run through
   each and the results compared — which is the only way to know the
   translation preserves semantics rather than merely compiling."
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
