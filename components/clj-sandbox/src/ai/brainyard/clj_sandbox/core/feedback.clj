;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-sandbox.core.feedback
  "Structure-aware truncation for RLM REPL feedback messages.

   Instead of hard-truncating eval results at N chars (which breaks JSON/EDN),
   this module preserves the *shape* of data structures while limiting size.
   The LLM can always use (inspect result) to drill deeper."
  (:require [clojure.string :as str]
            [ai.brainyard.clj-sandbox.core.truncation :as trunc]))

(defn- truncate-string
  "Truncate a string, saving original to temp file for recovery."
  [s max-chars]
  (trunc/truncate-to-file s max-chars "repl-output" :label "eval-result"))

(defn- truncate-map-result
  "Truncate a map result, showing keys + sample values."
  [m max-chars]
  (let [ks (keys m)
        key-summary (str "{" (str/join ", " (take 12 (map pr-str ks)))
                         (when (> (count ks) 12) ", ...") "}")
        ;; Try to show a compact representation
        compact (pr-str m)]
    (if (<= (count compact) max-chars)
      compact
      ;; Show keys + first few values compactly
      (let [sample-pairs (take 4 m)
            sample-str (str "{"
                            (str/join ", "
                                      (map (fn [[k v]]
                                             (let [vs (pr-str v)]
                                               (str (pr-str k) " "
                                                    (if (> (count vs) 80)
                                                      (str (subs vs 0 80) "...")
                                                      vs))))
                                           sample-pairs))
                            (when (> (count ks) 4)
                              (str ", ...(+" (- (count ks) 4) " more keys)"))
                            "}")]
        (str "Map(" (count ks) " keys): " key-summary
             "\nSample: " sample-str)))))

(defn- truncate-seq-result
  "Truncate a sequential result, showing count + first/last items."
  [coll max-chars]
  (let [n (count coll)
        compact (pr-str coll)]
    (if (<= (count compact) max-chars)
      compact
      (let [label (if (vector? coll) "Vector" "Seq")
            first-str (pr-str (first coll))
            first-truncated (if (> (count first-str) 200)
                              (str (subs first-str 0 200) "...")
                              first-str)
            last-str (when (> n 1) (pr-str (last coll)))
            last-truncated (when last-str
                             (if (> (count last-str) 200)
                               (str (subs last-str 0 200) "...")
                               last-str))]
        (str label "(" n "), first: " first-truncated
             (when (and last-truncated (> n 1))
               (str ", last: " last-truncated)))))))

(defn truncate-result
  "Intelligently truncate a single eval result for feedback.

   Strategy by result type:
   - nil/error: pass through unchanged
   - Map: show keys + sample values
   - Vector/Seq: show count + first/last items
   - String: head + ellipsis + tail
   - Other: pr-str with limit

   Parameters:
     result       - The eval result value
     max-chars    - Maximum chars for this result (default 3000)

   Returns: String representation."
  [result & {:keys [max-chars] :or {max-chars 10000}}]
  (cond
    (nil? result)
    "nil"

    (map? result)
    (truncate-map-result result max-chars)

    (sequential? result)
    (truncate-seq-result result max-chars)

    (string? result)
    (truncate-string result max-chars)

    :else
    (let [s (pr-str result)]
      (if (> (count s) max-chars)
        (str (subs s 0 max-chars) "...")
        s))))

(defn build-feedback-message
  "Build a feedback message with structure-aware truncation.

   Parameters:
     eval-results  - [{:result :output :error :code}]
     opts          - {:max-chars-per-block 10000
                      :structure-aware true}

   Returns: {:role \"user\" :content \"...\"}"
  [eval-results & {:keys [max-chars-per-block structure-aware]
                   :or {max-chars-per-block 10000 structure-aware true}}]
  (let [max-chars max-chars-per-block
        parts (map-indexed
               (fn [i {:keys [result output error]}]
                 (let [block-header (if (= 1 (count eval-results))
                                      "REPL Output:"
                                      (str "Block " (inc i) " Output:"))
                       sections (cond-> []
                                  (and output (not (str/blank? output)))
                                  (conj (str "stdout:\n"
                                             (trunc/truncate-to-file output max-chars "repl-output"
                                                                     :label "stdout")))

                                  error
                                  (conj (str "Error: " error))

                                  (and (nil? error) (some? result))
                                  (conj (if structure-aware
                                          (str "=> " (truncate-result result :max-chars max-chars))
                                          (let [s (pr-str result)]
                                            (str "=> " (if (> (count s) max-chars)
                                                         (str (subs s 0 max-chars) "...")
                                                         s))))))]
                   (str block-header "\n" (str/join "\n" sections))))
               eval-results)]
    {:role "user"
     :content (str/join "\n\n" parts)}))
