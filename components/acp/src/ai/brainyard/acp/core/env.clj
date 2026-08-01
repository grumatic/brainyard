;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp.core.env
  "Environment-variable normalization + strict validation for ACP
   subprocess launches.

   Two audiences, deliberately separated:

   - **Builders** (launch-spec factories, config readers) call `normalize`
     / `merge-envs`. EDN-shaped input is coerced into the plain
     `string->string` map the OS requires: keyword/symbol keys and values
     contribute their `name` (a bare `(str :ANTHROPIC_MODEL)` yields
     \":ANTHROPIC_MODEL\" — a variable no subprocess will ever read), blank
     keys and nil values are dropped, and a `:K` / \"K\" collision resolves
     deterministically in favour of the string form rather than by hash
     order at the `.put` site.

   - **The spawn boundary** (`transport.stdio/open!`) calls `validate!`,
     which coerces NOTHING. A malformed env there is a caller bug that
     would otherwise vanish silently into a subprocess that never sees its
     variable, so it throws `{:type :acp/invalid-env}` instead."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Lenient coercion — for builders
;; =============================================================================

(defn normalize-key
  "Coerce an env-var NAME to the plain string the OS expects. Keywords and
   symbols contribute only their `name`; anything else falls back to `str`.
   The result is trimmed. `nil` yields the empty string."
  [k]
  (let [s (cond
            (nil? k)     ""
            (keyword? k) (name k)
            (symbol? k)  (name k)
            (string? k)  k
            :else        (str k))]
    (str/trim s)))

(defn normalize-val
  "Coerce an env-var VALUE to a string. The same leading-colon trap applies
   to values — `{:ANTHROPIC_MODEL :claude-opus-5}` must not become
   `ANTHROPIC_MODEL=\":claude-opus-5\"`."
  [v]
  (cond
    (keyword? v) (name v)
    (symbol? v)  (name v)
    (string? v)  v
    :else        (str v)))

(defn normalize
  "Normalize an env map to string->string, dropping blank keys and nil
   values. When one map carries both `:K` and \"K\" — distinct map entries
   that collapse to a single env var — the STRING key wins, resolved here
   instead of by hash order downstream. `nil` normalizes to `{}`."
  [m]
  (when-not (or (nil? m) (map? m))
    (throw (ex-info "env must be a map" {:type :acp/invalid-env :env m})))
  (reduce (fn [acc [k v]]
            (let [ek (normalize-key k)]
              (if (or (str/blank? ek) (nil? v))
                acc
                (assoc acc ek (normalize-val v)))))
          {}
          ;; string keys last => the string form wins a normalized collision
          (sort-by (fn [[k _]] (if (string? k) 1 0)) m)))

(defn merge-envs
  "Merge `override` ON TOP of `base`, normalizing BOTH sides first so a
   keyword override cannot miss its string twin (and vice versa). Override
   entries win — an explicit `:env` beats a forwarded parent-process value."
  [base override]
  (merge (normalize base) (normalize override)))

;; =============================================================================
;; Strict validation — for the spawn boundary
;; =============================================================================

(def ^:private illegal-key-re
  "Env-var NAMES may not contain `=` or whitespace."
  #"[=\s]")

(defn problems
  "Describe every reason `m` is not ALREADY a strict `string->string` env
   map. Returns a vector of `{:issue :key :value :suggested}` maps (empty
   == valid). Never throws — `nil` is valid and yields `[]`."
  [m]
  (cond
    (nil? m)       []
    (not (map? m)) [{:issue :not-a-map :value m}]
    :else
    (let [entry-problems
          (into []
                (mapcat
                 (fn [[k v]]
                   (concat
                    (cond
                      (not (string? k))
                      [{:issue :non-string-key :key k :suggested (normalize-key k)}]

                      (str/blank? k)
                      [{:issue :blank-key :key k}]

                      (not= k (str/trim k))
                      [{:issue :untrimmed-key :key k :suggested (str/trim k)}]

                      (re-find illegal-key-re k)
                      [{:issue :illegal-key-char :key k}]

                      :else nil)
                    (cond
                      (nil? v)
                      [{:issue :nil-value :key k}]

                      (not (string? v))
                      [{:issue :non-string-value :key k :value v
                        :suggested (normalize-val v)}]

                      :else nil))))
                m)
          dupes (->> (keys m)
                     (map normalize-key)
                     (remove str/blank?)
                     frequencies
                     (keep (fn [[k n]]
                             (when (> n 1)
                               {:issue :duplicate-key :key k :count n}))))]
      (into entry-problems dupes))))

(defn valid?
  "True when `m` is already a strict string->string env map (`nil` is valid)."
  [m]
  (empty? (problems m)))

(defn validate!
  "STRICT gate for the spawn boundary — coerces NOTHING.

   Returns `m` (or `{}` when nil) if it is already a plain string->string
   map with non-blank, trimmed, collision-free keys. Otherwise throws
   `ex-info` carrying `{:type :acp/invalid-env :problems [...]}`, merged
   with any `:context` map supplied by the caller.

   Rationale: coercing silently at spawn time HIDES the caller's bug — the
   subprocess starts, the variable is wrong or missing, and the failure
   resurfaces much later as mysterious backend behaviour. Builders run
   their maps through `normalize` / `merge-envs`; by the time an env map
   reaches a ProcessBuilder it must already be correct."
  ([m] (validate! m {}))
  ([m {:keys [context]}]
   (let [ps (problems m)]
     (when (seq ps)
       (throw (ex-info
               (str "invalid ACP subprocess env (" (count ps) " problem"
                    (when (> (count ps) 1) "s") "): "
                    (str/join "; "
                              (map (fn [{:keys [issue key suggested]}]
                                     (str (name issue)
                                          (when key (str " " (pr-str key)))
                                          (when suggested (str " -> " (pr-str suggested)))))
                                   (take 5 ps)))
                    ". Run the map through ai.brainyard.acp.core.env/normalize first.")
               (cond-> {:type :acp/invalid-env :problems (vec ps)}
                 (map? context) (merge context)))))
     (or m {}))))
