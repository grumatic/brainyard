;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-sandbox.core.context-accessors
  "Context accessor functions for the RLM sandbox.

   Generic exploration functions that work with ANY context shape:
   context-index, context-get, context-keys, context-sample, context-search

   Agent state (info, config, st-memory) is part of the context under
   :agent-state and accessible via context-get [:agent-state :info] etc.

   All are returned by `make-context-accessors` as {symbol fn} pairs for sandbox binding."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Helpers for generic context exploration
;; ---------------------------------------------------------------------------

(defn- type-label
  "Classify a value into a type keyword for structural description."
  [v]
  (cond
    (nil? v)        :nil
    (map? v)        :map
    (vector? v)     :vector
    (sequential? v) :seq
    (set? v)        :set
    (string? v)     :string
    (number? v)     :number
    (boolean? v)    :boolean
    (keyword? v)    :keyword
    :else           :other))

(defn- safe-count
  "Count without realizing unbounded lazy seqs. Returns count or :unknown."
  [v]
  (if (counted? v)
    (count v)
    (let [n (bounded-count 10001 v)]
      (if (> n 10000) :unknown n))))

(defn- describe-value
  "Describe a value's structure without returning full content.
   `depth` controls recursion (0 = type+size only, max 3)."
  [v depth]
  (let [t (type-label v)]
    (case t
      :nil {:type :nil}
      :map (let [ks (vec (keys v))
                 c  (count ks)]
             (cond-> {:type :map :count c :keys (if (> c 30) (vec (take 30 ks)) ks)}
               (and (pos? depth) (seq v))
               (assoc :structure
                      (into {}
                            (comp (take 15)
                                  (map (fn [[k sv]] [k (describe-value sv (dec depth))])))
                            v))))
      (:vector :seq)
      (let [c (safe-count v)]
        (cond-> {:type :vector :count c}
          (and (pos? depth) (seq v))
          (assoc :element-sample (describe-value (first v) (dec depth)))))
      :set    {:type :set :count (safe-count v)}
      :string {:type :string :length (count v)
               :preview (subs v 0 (min 100 (count v)))}
      :number {:type :number :value v}
      :boolean {:type :boolean :value v}
      :keyword {:type :keyword :value v}
      {:type :other :class (str (type v))})))

(defn- truncate-value
  "Return value with large collections/strings truncated for safe display."
  [v {:keys [limit str-limit] :or {limit 20 str-limit 2000}}]
  (cond
    (nil? v) v

    (and (string? v) (> (count v) str-limit))
    (str (subs v 0 str-limit) "...(truncated, " (count v) " chars total)")

    (and (vector? v) (> (count v) limit))
    {:items (mapv #(truncate-value % {:limit limit :str-limit str-limit})
                  (take limit v))
     :truncated true
     :total-count (count v)}

    (and (sequential? v) (let [c (safe-count v)] (and (number? c) (> c limit))))
    {:items (mapv #(truncate-value % {:limit limit :str-limit str-limit})
                  (take limit v))
     :truncated true
     :total-count (safe-count v)}

    (and (map? v) (> (count v) 30))
    {:items (into {} (comp (take 15)
                           (map (fn [[k sv]] [k (truncate-value sv {:limit limit :str-limit str-limit})])))
                  v)
     :truncated true
     :total-keys (count v)}

    (map? v)
    (into {} (map (fn [[k sv]] [k (truncate-value sv {:limit limit :str-limit str-limit})])) v)

    :else v))

(defn- get-in-path
  "Navigate into a value using a path vector. Supports keyword keys,
   integer indices, and string keys."
  [v path]
  (reduce
   (fn [acc k]
     (cond
       (nil? acc) (reduced nil)
       (and (map? acc) (keyword? k))   (get acc k)
       (and (map? acc) (string? k))    (get acc k)
       (and (sequential? acc) (number? k)) (nth (vec acc) k nil)
       :else (reduced nil)))
   v
   path))

(defn- walk-search
  "Recursively search string values for pattern. Collects matches into results-atom.
   Caps at `limit` matches and `max-depth` levels."
  [v pattern path results-atom limit max-depth case-sensitive?]
  (when (and (< (count @results-atom) limit)
             (>= max-depth 0))
    (cond
      (string? v)
      (let [haystack (if case-sensitive? v (str/lower-case v))
            needle   (if case-sensitive? pattern (str/lower-case pattern))]
        (when (str/includes? haystack needle)
          (let [idx   (str/index-of haystack needle)
                start (max 0 (- idx 50))
                end   (min (count v) (+ idx (count pattern) 50))]
            (swap! results-atom conj
                   {:path    path
                    :match   (subs v idx (min (count v) (+ idx (count pattern))))
                    :context (subs v start end)}))))

      (map? v)
      (doseq [[k sv] (take 100 v)]
        (when (< (count @results-atom) limit)
          (walk-search sv pattern (conj path k) results-atom limit
                       (dec max-depth) case-sensitive?)))

      (sequential? v)
      (doseq [[i sv] (take 200 (map-indexed vector v))]
        (when (< (count @results-atom) limit)
          (walk-search sv pattern (conj path i) results-atom limit
                       (dec max-depth) case-sensitive?))))))

;; ---------------------------------------------------------------------------
;; Main builder
;; ---------------------------------------------------------------------------

(defn make-context-accessors
  "Build accessor functions that selectively retrieve from context.
   Returns a map of {symbol fn} for sandbox bindings.

   `context` MUST be a map (validated by create-sandbox). Path-based access
   (context-get / context-keys / context-sample) requires a map shape.

   Options:
     :synthetic-keys  - map of {keyword (fn [] ...)} that lazily compute extra
                        top-level keys exposed alongside the static context
                        (e.g. {:user-vars (fn [] (extract-user-vars sandbox))}).
                        Synthetic keys appear in `(context-index)` and are
                        readable via `(context-get [<key> ...])`."
  [context & {:keys [synthetic-keys]}]
  (let [;; Snapshot synthetic keys lazily into a single map. Called once per
        ;; accessor invocation that needs them — keeps the snapshot fresh
        ;; across iterations without paying the cost when not used.
        snapshot-synth (fn []
                         (when (seq synthetic-keys)
                           (reduce-kv (fn [m k f]
                                        (try (assoc m k (f))
                                             (catch Exception _ m)))
                                      {} synthetic-keys)))
        ;; Merge synthetic keys onto the static map context.
        with-synth (fn []
                     (if-let [synth (snapshot-synth)]
                       (merge context synth)
                       context))
        base-accessors
        {;; ================================================================
         ;; Generic exploration accessors
         ;; ================================================================

         'context-index
         (fn []
           (let [merged (with-synth)]
             {:keys (vec (keys merged))
              :structure (into {}
                               (comp (take 20)
                                     (map (fn [[k v]] [k (describe-value v 1)])))
                               merged)}))

         'context-get
         (fn [path & {:keys [raw limit str-limit]
                      :or {limit 20 str-limit 2000}}]
           (let [;; Synthetic keys are only computed when path[0] needs them —
                 ;; static lookups skip the thunk overhead.
                 base (if (and (vector? path) (seq path)
                               (contains? synthetic-keys (first path)))
                        (with-synth)
                        context)
                 v (get-in-path base path)]
             (if raw
               v
               (truncate-value v {:limit limit :str-limit str-limit}))))

         'context-keys
         (fn [path]
           (let [base (if (or (empty? path)
                              (and (vector? path)
                                   (contains? synthetic-keys (first path))))
                        (with-synth)
                        context)
                 v (get-in-path base path)]
             (cond
               (nil? v)        {:type :nil}
               (map? v)        (vec (keys v))
               (vector? v)     {:type :vector
                                :count (count v)
                                :indices (vec (take 50 (range (count v))))}
               (sequential? v) {:type :seq
                                :count (safe-count v)
                                :indices (vec (take 50 (range (safe-count v))))}
               (set? v)        {:type :set :count (count v) :items (vec (take 50 v))}
               (string? v)     {:type :string :length (count v)}
               (number? v)     {:type :number :value v}
               (boolean? v)    {:type :boolean :value v}
               (keyword? v)    {:type :keyword :value v}
               :else           {:type :other :class (str (type v))})))

         'context-sample
         (fn [path n & {:keys [strategy]
                        :or {strategy :random}}]
           (let [base (if (and (vector? path) (seq path)
                               (contains? synthetic-keys (first path)))
                        (with-synth)
                        context)
                 v    (get-in-path base path)
                 coll (cond
                        (vector? v)     v
                        (sequential? v) (vec v)
                        (set? v)        (vec v)
                        :else           nil)]
             (if (nil? coll)
               {:error (str "Value at path " (pr-str path) " is not a collection"
                            (when v (str " (type: " (type-label v) ")")))}
               (let [total (count coll)
                     n     (min n total)
                     items (case strategy
                             :first
                             (vec (take n coll))

                             :last
                             (vec (take-last n coll))

                             :evenly-spaced
                             (if (<= total 1)
                               (vec (take n coll))
                               (let [step (/ (dec total) (max 1 (dec n)))]
                                 (mapv (fn [i] (nth coll (int (Math/round (double (* i step))))))
                                       (range n))))

                             ;; :random (default)
                             (->> (range total)
                                  shuffle
                                  (take n)
                                  sort
                                  (mapv #(nth coll %))))]
                 (mapv #(truncate-value % {:limit 10 :str-limit 500}) items)))))

         'context-search
         (fn [pattern & {:keys [limit case-sensitive]
                         :or {limit 10 case-sensitive false}}]
           (let [results (atom [])
                 base (with-synth)]
             (walk-search base pattern [] results limit 10 case-sensitive)
             @results))}]

    base-accessors))
