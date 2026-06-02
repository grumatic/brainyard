;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.util.interface.macros
  "Macros for exporting symbols from implementation namespaces."
  (:require [clojure.set]))

;; Based on tech.v3.datatype.export-symbols
(defmacro export-symbols
  "Export symbols from a source namespace to the current namespace.
   Copies var metadata (doc, arglists, etc) to the new var."
  [src-ns & symbol-list]
  `(do
     (require '~src-ns)
     ~@(->> symbol-list
            (mapv
             (fn [sym-name]
               `(let [varval# (requiring-resolve (symbol ~(name src-ns)
                                                         ~(name sym-name)))
                      var-meta# (meta varval#)]
                  (when-not varval#
                    (throw (ex-info
                            (format "Failed to find symbol '%s' in namespace '%s'"
                                    ~(name sym-name) ~(name src-ns))
                            {:symbol '~sym-name
                             :src-ns '~src-ns})))
                  (when (:macro var-meta#)
                    (throw
                     (ex-info
                      (format "Cannot export macros as this breaks aot: %s"
                              '~sym-name)
                      {:symbol '~sym-name})))
                  (def ~(symbol (name sym-name)) @varval#)
                  (alter-meta! #'~(symbol (name sym-name))
                               merge
                               (select-keys var-meta#
                                            [:file :line :column
                                             :doc
                                             :column :tag
                                             :arglists]))))))))

;; Based on scicloj.ml.utils
(defn ns-symbols
  "Get all public symbols from a namespace except those in `except` set."
  [ns except]
  (let [publics (-> ns ns-publics keys)
        to-export (clojure.set/difference
                   (set publics)
                   (set except))]
    (sort to-export)))

(defmacro export-all
  "Export all public symbols from given namespaces except those in `except` set."
  [spaces except]
  `(do ~@(for [ns spaces]
           `(ai.brainyard.util.interface.macros/export-symbols ~ns ~@(ns-symbols ns except)))))
