;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.signature
  "Signature definition macro and utilities for DSPy-style declarations."
  (:require [ai.brainyard.clj-llm.core.schema :as schema]))

(defn compile-signature
  "Compile a signature definition into a normalized signature map.
   Returns {:name str, :instructions str, :inputs map, :outputs map,
            :input-keys set, :output-keys set, :output-json-schema map}."
  [sig-name instructions inputs outputs]
  (let [input-keys  (set (keys inputs))
        output-keys (set (keys outputs))
        output-json-schema (schema/fields->json-schema outputs)]
    {:name               (str sig-name)
     :instructions       instructions
     :inputs             inputs
     :outputs            outputs
     :input-keys         input-keys
     :output-keys        output-keys
     :output-json-schema output-json-schema}))

(defn extract-signature-metadata
  "Extract input and output key lists from a compiled signature."
  [sig]
  {:input-keys  (vec (:input-keys sig))
   :output-keys (vec (:output-keys sig))})

(defmacro defsignature
  "Define a DSPy-style signature.

   Usage:
     (defsignature QA
       \"Answer questions accurately.\"
       {:inputs  {:question [:string {:desc \"The question to answer\"}]}
        :outputs {:answer   [:string {:desc \"The answer\"}]}})

   The docstring can also be a keyword reference for dynamic instructions.
   Creates a var with :dspy/signature metadata."
  [sig-name docstring fields-map]
  (let [instructions (if (keyword? docstring)
                       `(or (resolve ~docstring) (str ~docstring))
                       docstring)]
    `(def ~(with-meta sig-name {:dspy/signature true})
       (compile-signature ~(str sig-name)
                          ~instructions
                          (:inputs ~fields-map)
                          (:outputs ~fields-map)))))
