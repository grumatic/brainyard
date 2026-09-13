;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.json-schema
  "Validate a JSON value against a JSON Schema — the structured-output check.

   Pure Clojure over the subset of JSON Schema that structured-output schemas
   actually use, rather than a JVM validator library: this component must stay
   native-image-safe, and a reflective validator is exactly the dependency that
   breaks there.

   Supported: `type` (string or array), `enum`, `const`, `properties`,
   `required`, `additionalProperties` (boolean or schema), `items`,
   `minItems`/`maxItems`, `uniqueItems`, `minLength`/`maxLength`, `pattern`,
   `minimum`/`maximum`/`exclusiveMinimum`/`exclusiveMaximum`, `anyOf`/`oneOf`/
   `allOf`/`not`, and local `$ref` (`#/definitions/…`, `#/$defs/…`). Anything
   else — `format`, `description`, `title` — is an annotation and is ignored,
   which is what the spec says an unknown keyword is.

   Key-type agnostic on both sides. A schema arrives string-keyed from a JSON
   tool call and keyword-keyed from a code block; a value arrives keyword-keyed
   from `parse-json-response` and string-keyed from JSON. Names are compared by
   `name`, so the same schema validates the same data whichever channel carried
   either."
  (:require [clojure.string :as str]))

(defn- kget
  "Schema keyword lookup that accepts keyword or string keys."
  [m k]
  (when (map? m)
    (let [v (get m k ::none)]
      (if (= ::none v) (get m (name k)) v))))

(defn- kname [k] (if (keyword? k) (name k) (str k)))

(defn- json-type
  "The JSON type name of a Clojure value."
  [v]
  (cond
    (nil? v)                   "null"
    (boolean? v)               "boolean"
    (string? v)                "string"
    (integer? v)               "integer"
    (number? v)                "number"
    (map? v)                   "object"
    (sequential? v)            "array"
    (keyword? v)               "string"
    :else                      "unknown"))

(defn- type-matches? [t v]
  (let [actual (json-type v)]
    (or (= t actual)
        ;; every integer is a number; a float with no fraction is an integer
        (and (= t "number") (= actual "integer"))
        (and (= t "integer") (number? v) (not (integer? v))
             (== v (Math/floor (double v)))))))

(defn- json-norm
  "A value in the form JSON equality compares: string keys, keywords as
   strings, and 1 equal to 1.0."
  [x]
  (cond
    (map? x)        (into {} (map (fn [[k v]] [(kname k) (json-norm v)])) x)
    (sequential? x) (mapv json-norm x)
    (keyword? x)    (name x)
    (and (number? x) (not (boolean? x))) (double x)
    :else           x))

(defn- json= [a b] (= (json-norm a) (json-norm b)))

(defn- resolve-ref [root pointer]
  (when (and (string? pointer) (str/starts-with? pointer "#/"))
    (reduce (fn [node seg] (kget node (keyword seg)))
            root
            (str/split (subs pointer 2) #"/"))))

(defn- err [path msg] [{:path path :message msg}])

(declare check)

(defn- check-object [root schema v path]
  (let [props    (kget schema :properties)
        by-name  (into {} (map (fn [[k s]] [(kname k) s])) props)
        vals     (into {} (map (fn [[k x]] [(kname k) x])) v)
        required (map kname (kget schema :required))
        addl     (kget schema :additionalProperties)]
    (concat
     (for [r required :when (not (contains? vals r))]
       {:path (conj path r) :message "missing required property"})
     (mapcat (fn [[k x]]
               (if-let [s (get by-name k)]
                 (check root s x (conj path k))
                 (cond
                   (false? addl) (err (conj path k) "property not allowed")
                   (map? addl)   (check root addl x (conj path k))
                   :else         nil)))
             vals))))

(defn- check-array [root schema v path]
  (let [items (kget schema :items)
        n     (count v)
        mn    (kget schema :minItems)
        mx    (kget schema :maxItems)]
    (concat
     (when (and mn (< n mn)) (err path (str "expected at least " mn " items, got " n)))
     (when (and mx (> n mx)) (err path (str "expected at most " mx " items, got " n)))
     (when (and (true? (kget schema :uniqueItems))
                (not= n (count (distinct (map json-norm v)))))
       (err path "items must be unique"))
     (when (map? items)
       (mapcat (fn [i x] (check root items x (conj path i))) (range) v)))))

(defn- check-string [schema v path]
  (let [n  (.codePointCount ^String v 0 (count v))
        mn (kget schema :minLength)
        mx (kget schema :maxLength)
        p  (kget schema :pattern)]
    (concat
     (when (and mn (< n mn)) (err path (str "expected at least " mn " characters")))
     (when (and mx (> n mx)) (err path (str "expected at most " mx " characters")))
     (when (and (string? p)
                (not (try (re-find (re-pattern p) v) (catch Exception _ true))))
       (err path (str "does not match pattern " p))))))

(defn- check-number [schema v path]
  (let [mn  (kget schema :minimum)
        mx  (kget schema :maximum)
        emn (kget schema :exclusiveMinimum)
        emx (kget schema :exclusiveMaximum)]
    (concat
     (when (and (number? mn) (< v mn)) (err path (str "must be >= " mn)))
     (when (and (number? mx) (> v mx)) (err path (str "must be <= " mx)))
     (when (and (number? emn) (<= v emn)) (err path (str "must be > " emn)))
     (when (and (number? emx) (>= v emx)) (err path (str "must be < " emx))))))

(defn- check
  "Errors for value `v` at `path` against `schema`; empty when valid."
  [root schema v path]
  (cond
    (true? schema)  nil
    (false? schema) (err path "no value is allowed here")
    (not (map? schema)) nil

    (kget schema :$ref)
    (if-let [target (resolve-ref root (kget schema :$ref))]
      (check root target v path)
      (err path (str "unresolvable $ref " (kget schema :$ref))))

    :else
    (let [t        (kget schema :type)
          types    (cond (string? t) [t] (sequential? t) (vec t) :else nil)
          type-ok? (or (nil? types) (some #(type-matches? % v) types))]
      (if-not type-ok?
        (err path (str "expected " (str/join " or " types) ", got " (json-type v)))
        (concat
         (when-let [e (kget schema :enum)]
           (when-not (some #(json= % v) e)
             (err path (str "must be one of " (pr-str (vec e))))))
         (when (contains? (set (map kname (keys schema))) "const")
           (when-not (json= (kget schema :const) v)
             (err path (str "must equal " (pr-str (kget schema :const))))))
         (when (map? v)        (check-object root schema v path))
         (when (sequential? v) (check-array root schema v path))
         (when (string? v)     (check-string schema v path))
         (when (and (number? v) (not (boolean? v))) (check-number schema v path))
         (when-let [alts (kget schema :allOf)]
           (mapcat #(check root % v path) alts))
         (when-let [alts (kget schema :anyOf)]
           (when-not (some #(empty? (check root % v path)) alts)
             (err path "matches none of anyOf")))
         (when-let [alts (kget schema :oneOf)]
           (let [n (count (filter #(empty? (check root % v path)) alts))]
             (when (not= 1 n)
               (err path (str "must match exactly one of oneOf, matched " n)))))
         (when-let [s (kget schema :not)]
           (when (empty? (check root s v path))
             (err path "must not match the not-schema"))))))))

(defn validate
  "Validate `value` against JSON Schema `schema`.
   Returns `{:valid? bool :errors [{:path [...] :message \"...\"}]}` — `:path`
   is the property names and array indices leading to the offending value."
  [schema value]
  (let [errors (vec (check schema schema value []))]
    {:valid? (empty? errors) :errors errors}))
