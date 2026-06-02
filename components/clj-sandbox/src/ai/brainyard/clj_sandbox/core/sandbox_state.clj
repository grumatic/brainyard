;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.core.sandbox-state
  "Snapshot and restore sandbox variables across turns.

   Persists user-defined variables (strings, numbers, keywords, collections)
   between turns so the LLM doesn't have to re-compute them. The LLM sees
   restored vars listed in (context-index) and can reference them directly."
  (:require [clojure.string :as str]))

(defn- edn-serializable?
  "Check if a value is EDN-serializable (safe for pr-str / read-string round-trip)."
  [v]
  (or (nil? v)
      (string? v)
      (number? v)
      (keyword? v)
      (symbol? v)
      (boolean? v)
      (and (map? v) (every? (fn [[k val]] (and (edn-serializable? k)
                                               (edn-serializable? val))) v))
      (and (vector? v) (every? edn-serializable? v))
      (and (set? v) (every? edn-serializable? v))
      (and (sequential? v) (every? edn-serializable? v))))

(defn extract-defs-from-iterations
  "Extract (def var-name ...) bindings from iteration eval-results.
   Returns map of {\"var-name\" {:value \"result-str\" :type \"inferred\"}}.
   Note: prefer sandbox/extract-user-vars for live sandbox extraction."
  [iterations]
  (let [def-re #"\(def\s+(\S+)\s"]
    (->> iterations
         (mapcat (fn [{:keys [eval-results]}]
                   (for [{:keys [code result]} (or eval-results [])
                         :let [match (when code (re-find def-re code))]
                         :when match]
                     [(second match) result])))
         ;; Later defs override earlier ones (last wins)
         (reduce (fn [m [var-name result]]
                   (if (and result (edn-serializable? result))
                     (let [result-str (pr-str result)]
                       (if (< (count result-str) 2000)
                         (assoc m var-name {:value result-str :type "inferred"})
                         m))
                     m))
                 {}))))

(defn build-restore-bindings
  "Build sandbox bindings from a persisted state snapshot.
   Deserializes values and returns map suitable for :bindings in create-sandbox.

   Parameters:
     state-snapshot - Map from extract-defs-from-iterations
                      {\"var-name\" {:value \"serialized\" :type \"inferred\"}}

   Returns: {symbol value} for use as :bindings."
  [state-snapshot]
  (reduce-kv
   (fn [m var-name {:keys [value]}]
     (try
       (let [deserialized (read-string value)]
         (assoc m (symbol var-name) deserialized))
       (catch Exception _
         ;; Skip vars that can't be deserialized
         m)))
   {}
   (or state-snapshot {})))
