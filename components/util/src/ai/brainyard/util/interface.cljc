;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.util.interface
  "Public API for the util component.
   Provides UUID generation, file operations, logging, and naming utilities."
  (:require
   [ai.brainyard.util.core.naming :as naming]
   #?@(:clj [[ai.brainyard.util.interface.macros :refer [export-symbols]]])))

;; ============================================================================
;; UUID and File Operations (CLJ only)
;; ============================================================================

#?(:clj (export-symbols ai.brainyard.util.core.common
                        new-uuid custom-uuid create-dir exist-dir? copy-file iter-seq))

;; ============================================================================
;; Logging (CLJ only)
;; ============================================================================

#?(:clj (export-symbols ai.brainyard.util.core.logging
                        pretty configure-logging! default-log-config))

;; ============================================================================
;; Naming Utilities (CLJ/CLJS)
;; ============================================================================

(defn kw->nspc
  "Convert a keyword to a namespace-style string.
   Handles namespaced keywords by joining with '.'"
  [kw]
  (naming/kw->nspc kw))

(defn abbreviate
  "Abbreviate a value to a specified length with ellipsis."
  ([x] (naming/abbreviate x))
  ([x pre] (naming/abbreviate x pre))
  ([x pre post] (naming/abbreviate x pre post)))

(defn kw->str
  "Convert a keyword to string, preserving namespace."
  [kw]
  (naming/kw->str kw))

(defn gen-random-words
  "Generate random words using talltale (CLJ) or random-words (CLJS)."
  [& [words-per-string]]
  (naming/gen-random-words words-per-string))

(defn gen-unique-names
  "Generate unique names using talltale (CLJ) or unique-names-generator (CLJS)."
  []
  (naming/gen-unique-names))
