;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.schema
  "Malli schema utilities and JSON Schema conversion for LLM structured output."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.json-schema :as mjs]
            [malli.error :as me]))

(defn add-additional-properties-false
  "Recursively adds additionalProperties: false to all object schemas.
   Required for OpenAI strict mode JSON Schema.

   Also normalizes `oneOf` → `anyOf`. Malli emits `oneOf` for `:maybe` (e.g.
   nullable int → {oneOf [{type integer} {type null}]}), but OpenAI's strict
   structured-output mode rejects `oneOf`. `anyOf` is universally supported
   (OpenAI, Anthropic, Bedrock) and is semantically a superset — safe for
   mutually exclusive variants like nullable types."
  [schema]
  (if-not (map? schema)
    schema
    (cond-> schema
      ;; Handle object type
      (= "object" (:type schema))
      (-> (update :properties
                  (fn [props]
                    (when props
                      (reduce-kv (fn [m k v]
                                   (assoc m k (add-additional-properties-false v)))
                                 {} props))))
          ;; A `:map-of` renders (via mjs) as an OPEN object whose values follow a
          ;; schema: {:type "object", :additionalProperties <value-schema>}.
          ;; Preserve + recurse into that value schema rather than clobbering it
          ;; to `false` — closing it would make the object accept NO keys (a
          ;; `[:map-of :any :any]` would misdescribe an arbitrary map as
          ;; empty-only). A genuinely closed object (a `:map` with :properties,
          ;; or a bare object) still gets `additionalProperties false` for OpenAI
          ;; strict mode. An open-map schema is (correctly) strict-INeligible, so
          ;; strict callers fall back to guidance + Malli output validation.
          (as-> s (if (map? (:additionalProperties s))
                    (update s :additionalProperties add-additional-properties-false)
                    (assoc s :additionalProperties false))))

      ;; Handle array items
      (:items schema)
      (update :items add-additional-properties-false)

      ;; anyOf — recurse
      (:anyOf schema)
      (update :anyOf (fn [variants] (mapv add-additional-properties-false variants)))

      ;; oneOf — rename to anyOf (OpenAI strict mode rejects oneOf) and recurse
      (:oneOf schema)
      (-> (assoc :anyOf (mapv add-additional-properties-false (:oneOf schema)))
          (dissoc :oneOf))

      ;; Handle definitions
      (:definitions schema)
      (update :definitions
              (fn [defs]
                (reduce-kv (fn [m k v]
                             (assoc m k (add-additional-properties-false v)))
                           {} defs))))))

(defn malli->json-schema
  "Convert a Malli schema to JSON Schema with OpenAI strict mode support.
   Adds additionalProperties: false recursively."
  [schema]
  (-> (mjs/transform schema)
      add-additional-properties-false))

(defn strict-eligible?
  "True when a derived JSON Schema meets OpenAI strict structured-output rules:
   every object sets `:additionalProperties false` and lists EVERY key in its
   `:properties` inside `:required` (strict mode forbids optional properties).
   Recurses through :properties, :items, :anyOf/:allOf and :definitions/:defs.

   A schema carrying any Malli `{:optional true}` field is ineligible — mjs
   omits it from :required — so callers should fall back to strict:false
   (schema as guidance + our own Malli output validation) rather than let
   OpenAI 400 on the request. Property/`:required` keys may be strings
   (top-level) or keywords (nested mjs output); both are normalized via `name`."
  [schema]
  (letfn [(names [xs] (into #{} (map name) (or xs [])))
          (ok? [s]
            (cond
              (not (map? s)) true
              (= "object" (:type s))
              (let [props (:properties s)
                    req   (names (:required s))]
                (and (false? (:additionalProperties s))
                     (every? req (names (keys props)))   ;; every property is required
                     (every? ok? (vals props))))
              (map? (:items s)) (ok? (:items s))
              (:anyOf s) (every? ok? (:anyOf s))
              (:allOf s) (every? ok? (:allOf s))
              :else true))]
    (boolean
     (and (ok? schema)
          (every? ok? (vals (or (:definitions schema) (:defs schema) {})))))))

(defn parse-malli-field
  "Normalize a field schema definition.

   Accepts:
     - Bare Malli schema:               :string, :int, [:vector :string]
     - Native Malli with our metadata:  [<type-kw> <props-map> & rest]
   The props-map may carry our project metadata (:desc, :default, :optional)
   alongside any Malli props. The schema is kept as-is — Malli accepts
   arbitrary keys in its props slot, so embedded :desc etc. are harmless.

   Examples:
     :string                                          ;; bare
     [:string {:desc \"A question\"}]                   ;; native, no values
     [:enum {:desc \"src\" :optional true} \"a\" \"b\"]   ;; native, with values

   Returns {:schema <malli-schema> :desc <str|nil> :default <val|nil> :optional <bool|nil>}."
  [raw-schema]
  (let [props (when (and (vector? raw-schema)
                         (>= (count raw-schema) 2)
                         (keyword? (first raw-schema))
                         (map? (second raw-schema))
                         (some #(contains? (second raw-schema) %)
                               [:desc :default :optional]))
                (second raw-schema))]
    (cond-> {:schema  raw-schema
             :desc    (:desc props)
             :default (:default props)}
      (:optional props) (assoc :optional true))))

(defn fields->malli-schema
  "Convert a fields map {:field-key malli-schema} to a Malli :map schema.
   Field schemas may include {:desc ...} properties."
  [fields]
  (into [:map]
        (map (fn [[field-key raw-schema]]
               (let [{:keys [schema]} (parse-malli-field raw-schema)]
                 [field-key schema]))
             fields)))

(defn- collect-definitions
  "Recursively collect all :definitions from a JSON Schema tree,
   removing them from their original locations. Returns [cleaned-schema definitions-map]."
  [schema]
  (if-not (map? schema)
    [schema {}]
    (let [;; Collect definitions from this level
          local-defs (or (:definitions schema) {})
          schema-no-defs (dissoc schema :definitions)
          ;; Recursively collect from nested definitions values
          [resolved-defs nested-from-defs]
          (reduce-kv (fn [[defs-acc nested-acc] k v]
                       (let [[cleaned more-defs] (collect-definitions v)]
                         [(assoc defs-acc k cleaned)
                          (merge nested-acc more-defs)]))
                     [{} {}] local-defs)
          ;; Recursively collect from properties
          [resolved-props nested-from-props]
          (if (:properties schema-no-defs)
            (reduce-kv (fn [[props-acc nested-acc] k v]
                         (let [[cleaned more-defs] (collect-definitions v)]
                           [(assoc props-acc k cleaned)
                            (merge nested-acc more-defs)]))
                       [{} {}] (:properties schema-no-defs))
            [(:properties schema-no-defs) {}])
          ;; Recursively collect from items
          [resolved-items nested-from-items]
          (if (map? (:items schema-no-defs))
            (collect-definitions (:items schema-no-defs))
            [(:items schema-no-defs) {}])
          ;; Build cleaned schema
          cleaned (cond-> schema-no-defs
                    resolved-props (assoc :properties resolved-props)
                    (and resolved-items (:items schema-no-defs)) (assoc :items resolved-items))
          ;; Merge all definitions
          all-defs (merge resolved-defs nested-from-defs nested-from-props nested-from-items)]
      [cleaned all-defs])))

(defn- shorten-def-key
  "Shorten a namespace-qualified definition key to its last segment.
   e.g., 'ai.brainyard.agent.common.coact-agent.code' → 'code'
   Handles collisions by keeping progressively longer suffixes."
  [long-key existing-short-keys]
  (let [segments (str/split (clojure.core/str long-key) #"\.")
        short (last segments)]
    (if (contains? existing-short-keys short)
      ;; Collision: use last two segments
      (let [short2 (str/join "." (take-last 2 segments))]
        (if (contains? existing-short-keys short2)
          (clojure.core/str long-key)  ;; Give up, keep full name
          short2))
      short)))

(defn- shorten-refs
  "Shorten all $ref definition IDs in a JSON Schema.
   Replaces verbose namespace-qualified keys with short names.
   Keeps standard :definitions key and #/definitions/ path for API compatibility."
  [schema]
  (if-not (:definitions schema)
    schema
    (let [;; Build rename mapping: long-key → short-key
          rename-map (loop [remaining (keys (:definitions schema))
                            result {}
                            used #{}]
                       (if-let [k (first remaining)]
                         (let [short (shorten-def-key k used)]
                           (recur (rest remaining)
                                  (assoc result (clojure.core/str k) short)
                                  (conj used short)))
                         result))
          ;; Replace $ref pointers with shortened names
          replace-refs (fn replace-refs [node]
                         (cond
                           (map? node)
                           (let [node (if-let [ref (:$ref node)]
                                        (let [def-name (second (re-find #"#/definitions/(.*)" ref))]
                                          (if-let [short (get rename-map def-name)]
                                            (assoc node :$ref (clojure.core/str "#/definitions/" short))
                                            node))
                                        node)]
                             (reduce-kv (fn [m k v] (assoc m k (replace-refs v))) {} node))
                           (vector? node) (mapv replace-refs node)
                           :else node))
          ;; First update all $refs, then rename definition keys
          updated-schema (replace-refs schema)
          renamed-defs (reduce-kv (fn [m k v]
                                    (assoc m (get rename-map (clojure.core/str k) (clojure.core/str k)) v))
                                  {} (:definitions updated-schema))]
      (assoc updated-schema :definitions renamed-defs))))

(defn fields->json-schema
  "Convert a fields map to a combined JSON Schema object.
   Adds descriptions from :desc properties to JSON Schema.
   Hoists all $ref definitions to root level for OpenAI structured output compatibility.
   Shortens verbose namespace-qualified $ref IDs to save tokens."
  [fields]
  (let [all-defs (atom {})
        properties (reduce-kv
                    (fn [m field-key raw-schema]
                      (let [{:keys [schema desc]} (parse-malli-field raw-schema)
                            js (malli->json-schema schema)
                            [cleaned defs] (collect-definitions js)
                            _ (swap! all-defs merge defs)]
                        (assoc m (name field-key)
                               (if desc
                                 (assoc cleaned :description desc)
                                 cleaned))))
                    {} fields)
        root-schema {:type                 "object"
                     :properties           properties
                     :required             (mapv name (keys fields))
                     :additionalProperties false}]
    ;; Apply additionalProperties: false, then shorten $ref IDs and rename :definitions to :defs
    (if (seq @all-defs)
      (-> (assoc root-schema :definitions @all-defs)
          add-additional-properties-false
          shorten-refs)  ;; shorten-refs also renames :definitions → :defs
      root-schema)))

(defn validate-output
  "Validate parsed JSON data against a Malli output schema.
   Returns {:valid? true :data data} or {:valid? false :errors [...]}."
  [malli-schema data]
  (if (m/validate malli-schema data)
    {:valid? true :data data}
    {:valid? false
     :errors (me/humanize (m/explain malli-schema data))
     :data   data}))

(defn- try-parse-json
  "Parse `s` as JSON with keyword keys, returning the value ONLY when it is a
   container (map/sequential); nil on any parse failure or non-container result.
   Recovers a `tool-args` (etc.) that a model emitted as a JSON *string* —
   `\"[{\\\"name\\\":\\\"q\\\",\\\"value\\\":\\\"foo\\\"}]\"` — instead of a real array."
  [s]
  (try
    (let [parsed (json/read-str s :key-fn keyword)]
      (when (or (map? parsed) (sequential? parsed)) parsed))
    (catch Exception _ nil)))

(def ^:private container-types #{:vector :sequential :set :map :map-of})

(defn- name-value-elem?
  "True when `elem-schema` resolves to a :map keyed exactly {:name :value} — the
   CoAct tool-args pair shape. Lets a plain arg object be adapted to the pair
   list the schema wants."
  [elem-schema]
  (boolean
   (try
     (let [s (m/deref-all elem-schema)]
       (and (= :map (m/type s))
            (= #{:name :value} (set (map first (m/children s))))))
     (catch Exception _ false))))

(defn- ->pair-list
  "Adapt a plain arg object {k v …} to the [{:name k :value v} …] pair shape."
  [m]
  (mapv (fn [[k v]] {:name (if (keyword? k) (name k) (str k)) :value (str v)}) m))

(defn- coerce-value
  "Best-effort coercion of a value toward a Malli schema's type. Only
   well-defined, low-ambiguity conversions are attempted; anything unclear
   (enums, :maybe, unknown types) is returned unchanged so downstream validation
   can still reject it.

   Handles the shapes LLMs actually emit when they echo a schema skeleton
   instead of instantiating it:
     - `goal-achieved: \"false\"`/null            (string/null, not boolean)
     - `tool-calls: \"$PLACEHOLDER\"`/`{...}`      (string/object, not array)
     - `tool-args: \"[{...}]\"`                    (JSON *string*, not array)
     - `tool-args: {\"q\":\"v\"}`                  (plain object, not pair list)
     - nested mistypes, e.g. a string `tool-args` INSIDE a valid `tool-calls`
       vector — coercion recurses into vector elements and map fields.

   Used by `coerce-output-types`; kept only when the result then validates."
  [malli-schema v]
  ;; `deref-all` unwraps registry refs (e.g. ::goal-achieved) — without it
  ;; `m/type` returns :malli.core/schema and coercion no-ops on every ref field.
  (let [resolved (try (m/deref-all (m/schema malli-schema)) (catch Exception _ nil))
        t        (when resolved (m/type resolved))
        ;; A JSON-string standing in for a container → reparse before coercing.
        v        (if (and (string? v) (contains? container-types t))
                   (or (try-parse-json v) v)
                   v)]
    (case t
      :boolean (cond
                 (boolean? v) v
                 (nil? v)     false
                 (string? v)  (case (str/lower-case (str/trim v))
                                ("true" "yes" "1" "on")     true
                                ("false" "no" "0" "off" "") false
                                v)                    ;; ambiguous string → leave
                 (number? v)  (not (zero? v))
                 :else        v)

      (:vector :sequential)
      (let [elem (first (m/children resolved))]
        (cond
          (nil? v)        []
          ;; Recurse into each element so a nested mistype is repaired.
          (sequential? v) (mapv #(coerce-value elem %) v)
          ;; Plain arg object where the schema wants {:name :value} pairs.
          (and (map? v) (name-value-elem? elem)) (->pair-list v)
          ;; Any other single unwrapped map → wrap and recurse.
          (map? v)        [(coerce-value elem v)]
          :else           []))              ;; scalar/placeholder → empty

      :map
      (if (map? v)
        ;; Coerce each known field against its own schema (recursive descent).
        (reduce (fn [m [k _ child]]
                  (if (contains? m k) (update m k #(coerce-value child %)) m))
                v (m/children resolved))
        v)

      :set (cond
             (set? v)        v
             (nil? v)        #{}
             (sequential? v) (set v)
             :else           v)

      :string (cond
                (string? v)                                v
                (nil? v)                                   ""
                (or (number? v) (boolean? v) (keyword? v)) (str v)
                :else                                      v)

      :int (cond
             (int? v)                                           v
             (and (string? v) (re-matches #"\s*[-+]?\d+\s*" v)) (parse-long (str/trim v))
             (number? v)                                        (long v)
             :else                                              v)

      :double (cond
                (double? v)                                              v
                (and (string? v) (re-matches #"\s*[-+]?\d*\.?\d+\s*" v)) (parse-double (str/trim v))
                (number? v)                                              (double v)
                :else                                                    v)

      v)))

(defn coerce-output-types
  "Repair present-but-wrong-typed output fields before validation.

   For each field of `signature`'s `:outputs` that is present in `outputs` but
   fails its own schema, attempt a type-directed coercion (`coerce-value`) and
   keep the result ONLY when it then validates. Absent fields, already-valid
   fields, and fields with no safe coercion are left untouched.

   Invariants:
     - Never turns a valid value invalid (valid values are skipped entirely).
     - Never keeps a coercion that doesn't itself validate — genuinely
       unrecoverable output still fails validation and reaches the caller's
       repair / re-prompt path.

   Complements `fill-output-defaults` (predict/chain-of-thought), which only
   fills MISSING keys: this rescues keys that are present but mistyped, the
   failure mode behind schema errors like
   `{:tool-calls [\"invalid type\"], :goal-achieved [\"should be a boolean\"]}`."
  [outputs signature]
  (reduce-kv
   (fn [acc k raw-schema]
     (if-not (contains? acc k)
       acc
       (let [{:keys [schema]} (parse-malli-field raw-schema)
             v                (get acc k)]
         (if (m/validate schema v)
           acc
           (let [cv (coerce-value schema v)]
             (if (and (not= cv v) (m/validate schema cv))
               (assoc acc k cv)
               acc))))))
   outputs
   (:outputs signature)))

(defn- resolved-vector-of-map
  "If output field `raw-schema` resolves to `[:vector <:map>]`, return the
   resolved inner `:map` schema; else nil. Fully derefs registry refs (e.g.
   ::tool-call) via the default registry. Defensive: any malli walking error
   yields nil so this never breaks the parse path."
  [raw-schema]
  (try
    (let [{:keys [schema]} (parse-malli-field raw-schema)
          s (m/deref-all (m/schema schema))]
      (when (= :vector (m/type s))
        (let [elem (m/deref-all (first (m/children s)))]
          (when (= :map (m/type elem)) elem))))
    (catch Exception _ nil)))

(defn- map-entry-info
  "For a resolved `:map` schema, return
   {:child-schemas {k child-schema} :ident <first required string key, or nil>}.
   The identifying key is what distinguishes a real element from a bare skeleton
   (e.g. :tool-name for a tool-call); optional keys never qualify."
  [map-schema]
  (let [children (m/children map-schema)] ; [[k props child] ...]
    {:child-schemas (into {} (map (fn [[k _ child]] [k child])) children)
     :ident (some (fn [[k props child]]
                    (when (and (not (:optional props))
                               (= :string (m/type (m/deref-all child))))
                      k))
                  children)}))

(defn lift-flattened-collection
  "Repair the 'flattened single element' failure mode: a model that should emit
   a vector-of-maps output field (e.g. `tool-calls: [{:tool-name .. :tool-args ..}]`)
   instead splices the inner map's keys to the TOP level as sibling outputs
   (`tool-name` / `tool-args`). This is the Claude-Code-backend tool-use idiom
   leaking through the DSPy output schema — it hard-fails validation because the
   real vector field is missing.

   For each output field whose schema is `[:vector <:map>]`, when the field is
   absent-or-empty AND the top level carries the inner map's identifying key
   (first required string field, e.g. :tool-name) non-blank, lift the stray inner
   keys into a single-element vector and remove them from the top level. Inner
   values are type-coerced against their inner schemas, so a stringified `\"[]\"`
   becomes `[]`.

   No-ops when there is nothing identifying to lift, so a genuinely content-free
   turn (e.g. only `tool-args:\"[]\"` + reasoning, the max-turns cutoff case) still
   falls through to `fill-output-defaults` as an empty turn rather than
   manufacturing a junk call. Runs BEFORE `fill-output-defaults` /
   `coerce-output-types`.

   Invariant: never overwrites a field that is already present and non-empty."
  [outputs signature]
  (reduce-kv
   (fn [acc field raw-schema]
     (let [current (get acc field)
           empty*  (or (not (contains? acc field))
                       (nil? current)
                       (and (sequential? current) (empty? current)))]
       (if-not empty*
         acc
         (if-let [inner (resolved-vector-of-map raw-schema)]
           (let [{:keys [child-schemas ident]} (map-entry-info inner)
                 idval (get acc ident)]
             (if (and ident (string? idval) (not (str/blank? idval))
                      (some #(contains? acc %) (keys child-schemas)))
               (let [element (reduce-kv
                              (fn [m k child]
                                (if (contains? acc k)
                                  (assoc m k (coerce-value child (get acc k)))
                                  m))
                              {} child-schemas)]
                 (-> (apply dissoc acc (keys child-schemas))
                     (assoc field [element])))
               acc))
           acc))))
   outputs
   (:outputs signature)))

(defn expand-with-props
  "Recursively expand Malli schema references using a registry.
   Resolves all :ref types to their underlying schemas."
  [registry schema]
  (let [resolved (if (and (keyword? schema) (get registry schema))
                   (get registry schema)
                   schema)]
    (cond
      (and (vector? resolved) (keyword? (first resolved)))
      (let [[tag & children] resolved]
        (into [tag]
              (map (fn [child]
                     (if (or (vector? child) (keyword? child))
                       (expand-with-props registry child)
                       child))
                   children)))
      :else resolved)))
