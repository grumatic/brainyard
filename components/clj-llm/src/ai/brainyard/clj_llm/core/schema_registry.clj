;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-llm.core.schema-registry
  "Mutable Malli schema registry with defschemas macro.

   Provides a shared mutable registry that allows schemas to be registered
   at load time via `defschemas`, making them available globally for
   schema validation and JSON Schema generation.

   Ported from ai.obney.grain.schema-util.interface."
  (:require [malli.core :as m]
            [malli.registry :as mr]))

(def registry* (atom (m/default-schemas)))

(defn register!
  "Register schemas into the shared mutable registry."
  [schema-map]
  (swap! registry* merge schema-map))

(defmacro defschemas
  "Define and register Malli schemas in the global mutable registry.

   Usage:
     (defschemas domain
       {::question [:string {:desc \"User question\"}]
        ::answer   [:string {:desc \"Answer\"}]})

   This:
   1. Registers all schemas in the mutable Malli registry
   2. Defs a var with the schema map for reference"
  [symbol schema-map]
  `(do
     (#'register! ~schema-map)
     (def ~symbol ~schema-map)))

(mr/set-default-registry!
 (mr/mutable-registry registry*))
