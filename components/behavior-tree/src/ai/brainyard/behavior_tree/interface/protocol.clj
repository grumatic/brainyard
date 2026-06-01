;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

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
