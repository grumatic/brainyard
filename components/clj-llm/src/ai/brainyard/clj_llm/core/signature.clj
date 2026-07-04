;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.signature
  "Signature definition macro and utilities for DSPy-style declarations."
  (:require [ai.brainyard.clj-llm.core.schema :as schema]))

(defn compile-signature
  "Compile a signature definition into a normalized signature map.
   Returns {:name str, :instructions str, :inputs map, :outputs map,
            :input-keys set, :input-order vec, :output-keys set,
            :output-json-schema map}.

   :input-order is the DECLARED input order and is cache-significant: the
   user message renders input values in this order, so declare inputs in
   ascending volatility (most-stable first, per-iteration-volatile last)
   to keep the turn-stable prefix cacheable. A ≤8-entry :inputs map literal
   is an array-map, so declaration order survives; beyond 8 the reader
   produces a hash-map and order is lost — pass `explicit-input-order`
   (defsignature: an `:input-order` key in the fields map) or this throws."
  [sig-name instructions inputs outputs & [explicit-input-order]]
  (let [input-order (or (some-> explicit-input-order vec)
                        (let [ks (vec (keys inputs))]
                          (when (> (count ks) 8)
                            (throw (ex-info
                                    (str "Signature " sig-name " has more than 8 inputs — "
                                         "the :inputs map literal no longer preserves "
                                         "declaration order. Pass :input-order explicitly "
                                         "(ascending volatility).")
                                    {:signature (str sig-name) :input-count (count ks)})))
                          ks))
        input-keys  (set (keys inputs))
        output-keys (set (keys outputs))
        output-json-schema (schema/fields->json-schema outputs)]
    {:name               (str sig-name)
     :instructions       instructions
     :inputs             inputs
     :outputs            outputs
     :input-keys         input-keys
     :input-order        input-order
     :output-keys        output-keys
     :output-json-schema output-json-schema}))

(defn extract-signature-metadata
  "Extract input and output key lists from a compiled signature.
   :input-keys comes back in declared order when the signature carries
   :input-order (compiled signatures do); hand-built signature maps
   without it fall back to set order."
  [sig]
  {:input-keys  (vec (or (:input-order sig) (:input-keys sig)))
   :output-keys (vec (:output-keys sig))})

(defmacro defsignature
  "Define a DSPy-style signature.

   Usage:
     (defsignature QA
       \"Answer questions accurately.\"
       {:inputs  {:question [:string {:desc \"The question to answer\"}]}
        :outputs {:answer   [:string {:desc \"The answer\"}]}})

   The docstring can also be a keyword reference for dynamic instructions.
   Creates a var with :dspy/signature metadata.

   The fields map may carry an optional `:input-order` vector — required
   when :inputs has more than 8 entries (map-literal order is lost past
   that size); otherwise declaration order is used. Order is
   cache-significant: declare inputs in ascending volatility."
  [sig-name docstring fields-map]
  (let [instructions (if (keyword? docstring)
                       `(or (resolve ~docstring) (str ~docstring))
                       docstring)]
    `(def ~(with-meta sig-name {:dspy/signature true})
       (compile-signature ~(str sig-name)
                          ~instructions
                          (:inputs ~fields-map)
                          (:outputs ~fields-map)
                          (:input-order ~fields-map)))))
