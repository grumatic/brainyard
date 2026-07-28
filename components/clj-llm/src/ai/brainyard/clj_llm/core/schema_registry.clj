;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.schema-registry
  "Mutable Malli schema registry with defschemas macro.

   Provides a shared mutable registry that allows schemas to be registered
   at load time via `defschemas`, making them available globally for
   schema validation and JSON Schema generation.

   Ported from ai.obney.grain.schema-util.interface."
  (:require [malli.core :as m]
            [malli.registry :as mr]))

;; `defonce`, not `def`: a plain `def` made reloading THIS namespace destructive
;; — it swapped in a fresh atom holding only malli's defaults, silently dropping
;; every schema any `defschemas` had registered process-wide. The next
;; `defsignature` referencing one then failed to macroexpand with
;; `:malli.core/invalid-schema`, pointing at the innocent signature rather than
;; at the reload. With `defonce` the registrations survive, so reloading this ns
;; RE-ASSERTS the default registry (see the bottom of the file) and repairs the
;; mapping instead of breaking it — which is what you want after something has
;; reloaded `malli.registry` and reset malli's own registry atom.
(defonce registry* (atom (m/default-schemas)))

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
