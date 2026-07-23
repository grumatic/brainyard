;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-llm.core.schema :as schema]
            [ai.brainyard.clj-llm.core.llm :as llm]))

(deftest malli->json-schema-test
  (testing "converts simple string schema"
    (let [js (schema/malli->json-schema :string)]
      (is (= "string" (:type js)))))

  (testing "converts integer schema"
    (let [js (schema/malli->json-schema :int)]
      (is (= "integer" (:type js)))))

  (testing "converts map schema with additionalProperties false"
    (let [js (schema/malli->json-schema [:map [:name :string] [:age :int]])]
      (is (= "object" (:type js)))
      (is (false? (:additionalProperties js)))
      (is (contains? (:properties js) :name))
      (is (contains? (:properties js) :age))))

  (testing "converts vector schema"
    (let [js (schema/malli->json-schema [:vector :string])]
      (is (= "array" (:type js)))
      (is (= "string" (get-in js [:items :type])))))

  (testing "nested maps get additionalProperties false recursively"
    (let [js (schema/malli->json-schema
              [:map
               [:user [:map [:name :string]]]])]
      (is (false? (:additionalProperties js)))
      (is (false? (get-in js [:properties :user :additionalProperties])))))

  (testing ":map-of renders an OPEN object — additionalProperties is the value schema, not false"
    (let [js (schema/malli->json-schema [:map-of :any :any])]
      (is (= "object" (:type js)))
      (is (map? (:additionalProperties js))
          "an arbitrary map must not be closed to additionalProperties:false (empty-only)"))
    (is (= {:type "integer"} (:additionalProperties (schema/malli->json-schema [:map-of :string :int])))
        "typed values are preserved")
    (testing "a closed map with a map-of field: outer stays closed, inner stays open"
      (let [js (schema/malli->json-schema [:map [:a :int] [:bag [:map-of :any :any]]])]
        (is (false? (:additionalProperties js)))
        (is (map? (get-in js [:properties :bag :additionalProperties])))))
    (testing "an open map-of is strict-INeligible (strict callers fall back to guidance+validate)"
      (is (not (schema/strict-eligible? (schema/malli->json-schema [:map [:m [:map-of :any :any]]]))))))

  (testing ":maybe is rendered with :anyOf, not :oneOf (OpenAI strict mode rejects oneOf)"
    (let [js (schema/malli->json-schema [:maybe :int])]
      (is (nil? (:oneOf js)) "no :oneOf key")
      (is (sequential? (:anyOf js)) ":anyOf with the variants")
      (is (= #{"integer" "null"} (set (map :type (:anyOf js)))))))

  (testing ":maybe with constraints preserves bounds inside :anyOf"
    (let [js (schema/malli->json-schema [:maybe [:int {:min 1000 :max 600000}]])]
      (is (nil? (:oneOf js)))
      (let [int-variant (first (filter #(= "integer" (:type %)) (:anyOf js)))]
        (is (= 1000 (:minimum int-variant)))
        (is (= 600000 (:maximum int-variant))))))

  (testing "nested :maybe inside a map also gets the rename"
    (let [js (schema/malli->json-schema
              [:map [:opt-num [:maybe :int]]])]
      (is (nil? (get-in js [:properties :opt-num :oneOf])))
      (is (some? (get-in js [:properties :opt-num :anyOf]))))))

(deftest parse-malli-field-test
  (testing "plain schema"
    (let [result (schema/parse-malli-field :string)]
      (is (= :string (:schema result)))
      (is (nil? (:desc result)))
      (is (nil? (:default result)))))

  (testing "native schema with desc — schema kept whole"
    (let [result (schema/parse-malli-field [:string {:desc "A question"}])]
      (is (= [:string {:desc "A question"}] (:schema result)))
      (is (= "A question" (:desc result)))))

  (testing "native schema with default — schema kept whole"
    (let [result (schema/parse-malli-field [:string {:default "hello"}])]
      (is (= [:string {:default "hello"}] (:schema result)))
      (is (= "hello" (:default result)))))

  (testing "native enum with metadata + values"
    (let [result (schema/parse-malli-field [:enum {:desc "x" :optional true} "a" "b"])]
      (is (= [:enum {:desc "x" :optional true} "a" "b"] (:schema result)))
      (is (= "x" (:desc result)))
      (is (true? (:optional result)))))

  (testing "vector schema without metadata props is bare"
    (let [result (schema/parse-malli-field [:vector :string])]
      (is (= [:vector :string] (:schema result)))
      (is (nil? (:desc result))))))

(deftest fields->json-schema-test
  (testing "converts fields map to JSON Schema object"
    (let [fields {:question [:string {:desc "The question"}]
                  :answer   [:string {:desc "The answer"}]}
          js (schema/fields->json-schema fields)]
      (is (= "object" (:type js)))
      (is (false? (:additionalProperties js)))
      (is (= #{"question" "answer"} (set (:required js))))
      (is (= "The question" (get-in js [:properties "question" :description])))
      (is (= "The answer" (get-in js [:properties "answer" :description]))))))

(deftest fields->malli-schema-test
  (testing "converts fields map to Malli :map schema"
    (let [fields {:question [:string {:desc "The question"}]
                  :answer   :string}
          ms (schema/fields->malli-schema fields)]
      (is (vector? ms))
      (is (= :map (first ms))))))

(deftest validate-output-test
  (testing "valid output"
    (let [ms [:map [:answer :string]]
          result (schema/validate-output ms {:answer "Paris"})]
      (is (true? (:valid? result)))
      (is (= {:answer "Paris"} (:data result)))))

  (testing "invalid output"
    (let [ms [:map [:answer :string]]
          result (schema/validate-output ms {:answer 42})]
      (is (false? (:valid? result)))
      (is (some? (:errors result))))))

(deftest strict-eligible?-test
  (testing "all-required object is eligible"
    (is (schema/strict-eligible?
         {:type "object" :additionalProperties false
          :properties {"a" {:type "string"} "b" {:type "string"}} :required ["a" "b"]})))
  (testing "a property missing from :required (Malli :optional) → ineligible"
    (is (not (schema/strict-eligible?
              {:type "object" :additionalProperties false
               :properties {"a" {:type "string"} "b" {:type "string"}} :required ["a"]}))))
  (testing "ineligibility is caught inside a nested array item"
    (is (not (schema/strict-eligible?
              {:type "object" :additionalProperties false :required ["xs"]
               :properties {"xs" {:type "array"
                                  :items {:type "object" :additionalProperties false
                                          :properties {"name" {:type "string"} "opt" {:type "string"}}
                                          :required ["name"]}}}}))))
  (testing "keyword-keyed nested schema (mjs output) is normalized"
    (is (schema/strict-eligible?
         {:type "object" :additionalProperties false :required ["m"]
          :properties {"m" {:type "object" :additionalProperties false
                            :properties {:x {:type "string"}} :required [:x]}}})))
  (testing "enum leaves and anyOf-nullable are fine"
    (is (schema/strict-eligible?
         {:type "object" :additionalProperties false :required ["k" "e"]
          :properties {"k" {:anyOf [{:type "string"} {:type "null"}]}
                       "e" {:type "string" :enum ["a" "b"]}}})))
  (testing "missing :additionalProperties false → ineligible"
    (is (not (schema/strict-eligible?
              {:type "object" :properties {"a" {:type "string"}} :required ["a"]}))))
  (testing "a real GraphExtraction-shaped schema (optional summary) → ineligible"
    (is (not (schema/strict-eligible?
              (schema/fields->json-schema
               {:entities [:vector [:map [:name :string]
                                    [:summary {:optional true} :string]]]}))))))

(deftest json-schema-strict?-resolution-test
  (let [strict-schema {:type "object" :additionalProperties false
                       :properties {"a" {:type "string"}} :required ["a"]}
        loose-schema  (assoc strict-schema :required [])]
    (testing "auto-detect: eligible → strict:true, ineligible → strict:false"
      (is (true?  (#'llm/json-schema-strict? {} strict-schema)))
      (is (false? (#'llm/json-schema-strict? {} loose-schema))))
    (testing "explicit lm-config :json-schema-strict? overrides auto-detect"
      (is (false? (#'llm/json-schema-strict? {:json-schema-strict? false} strict-schema)))
      (is (true?  (#'llm/json-schema-strict? {:json-schema-strict? true}  loose-schema))))))

;; ---------------------------------------------------------------------------
;; coerce-output-types — repair present-but-wrong-typed output fields
;; ---------------------------------------------------------------------------

(def ^:private coact-like-sig
  "A signature shaped like CoAct's: two non-string fields (:tool-calls vector,
   :goal-achieved boolean) plus string channels."
  {:outputs {:tool-calls       [:vector {:desc "x"} [:map [:tool-name :string]]]
             :code-blocks      [:string {:desc "x"}]
             :answer           [:string {:desc "x"}]
             :goal-achieved    [:boolean {:desc "x"}]
             :next-user-prompt [:string {:desc "x"}]}})

(defn- valid-outputs?
  "Does `outputs` validate against the signature's Malli output schema?"
  [outputs signature]
  (:valid? (schema/validate-output
            (schema/fields->malli-schema (:outputs signature))
            outputs)))

(deftest coerce-output-types-reported-failure-test
  (testing "the exact turn-6 failure: placeholder :tool-calls + string :goal-achieved"
    (let [raw   {:tool-calls "$PARAMETER_NAME" :code-blocks "" :answer ""
                 :goal-achieved "false" :next-user-prompt ""}
          fixed (schema/coerce-output-types raw coact-like-sig)]
      (is (not (valid-outputs? raw coact-like-sig)) "raw skeleton is invalid")
      (is (= [] (:tool-calls fixed)))
      (is (= false (:goal-achieved fixed)))
      (is (valid-outputs? fixed coact-like-sig) "coerced output validates"))))

(deftest coerce-output-types-boolean-test
  (let [coerce (fn [v] (:goal-achieved
                        (schema/coerce-output-types
                         {:goal-achieved v} {:outputs {:goal-achieved :boolean}})))]
    (testing "truthy strings → true"
      (is (true? (coerce "true"))) (is (true? (coerce "True")))
      (is (true? (coerce "yes")))  (is (true? (coerce "1")))
      (is (true? (coerce " on "))))
    (testing "falsey strings + nil → false"
      (is (false? (coerce "false"))) (is (false? (coerce "no")))
      (is (false? (coerce "0")))     (is (false? (coerce "off")))
      (is (false? (coerce "")))      (is (false? (coerce nil))))
    (testing "numbers → boolean"
      (is (true? (coerce 1))) (is (false? (coerce 0))))
    (testing "already-boolean is untouched"
      (is (true? (coerce true))) (is (false? (coerce false))))
    (testing "ambiguous string is left unchanged (stays invalid → repair path)"
      (is (= "maybe" (coerce "maybe"))))))

(deftest coerce-output-types-vector-test
  (let [sig    {:outputs {:tool-calls [:vector [:map [:tool-name :string]]]}}
        coerce (fn [v] (:tool-calls (schema/coerce-output-types {:tool-calls v} sig)))]
    (testing "nil / scalar / placeholder → empty vector"
      (is (= [] (coerce nil)))
      (is (= [] (coerce "$PLACEHOLDER")))
      (is (= [] (coerce 7))))
    (testing "single unwrapped valid element → wrapped in a vector"
      (is (= [{:tool-name "grep"}] (coerce {:tool-name "grep"}))))
    (testing "already a valid vector is untouched"
      (is (= [{:tool-name "grep"}] (coerce [{:tool-name "grep"}]))))))

(deftest coerce-output-types-tool-args-json-test
  (let [args   [:vector [:map [:name :string] [:value :string]]]
        asig   {:outputs {:tool-args args}}
        tcsig  {:outputs {:tool-calls [:vector [:map [:tool-name :string] [:tool-args args]]]}}
        js     "[{\"name\":\"q\",\"value\":\"foo\"}]"
        a-args (fn [v] (:tool-args (schema/coerce-output-types {:tool-args v} asig)))]
    (testing "JSON-STRING array with real content is reparsed, not dropped to []"
      (is (= [{:name "q" :value "foo"}] (a-args js))))
    (testing "JSON-string empty array → []"
      (is (= [] (a-args "[]"))))
    (testing "plain arg object → adapted to the {:name :value} pair list"
      (is (= [{:name "q" :value "foo"}] (a-args {:q "foo"}))))
    (testing "JSON-string object → parsed then adapted to pair list"
      (is (= [{:name "q" :value "foo"}] (a-args "{\"q\":\"foo\"}"))))
    (testing "non-JSON placeholder string still → []"
      (is (= [] (a-args "$PLACEHOLDER"))))
    (testing "NESTED string tool-args inside a valid tool-calls vector is repaired (not the whole call dropped)"
      (let [fixed (schema/coerce-output-types
                   {:tool-calls [{:tool-name "grep" :tool-args js}]} tcsig)]
        (is (= [{:tool-name "grep" :tool-args [{:name "q" :value "foo"}]}]
               (:tool-calls fixed)))
        (is (valid-outputs? fixed tcsig))))
    (testing "already-valid pair list passes through byte-identical"
      (let [g [{:name "a" :value "b"}]]
        (is (= g (a-args g)))))))

(deftest coerce-output-types-scalar-coercions-test
  (testing ":int from numeric string; :string from number/bool"
    (is (= 42  (:n (schema/coerce-output-types {:n "42"}   {:outputs {:n :int}}))))
    (is (= "7" (:s (schema/coerce-output-types {:s 7}      {:outputs {:s :string}}))))
    (is (= ""  (:s (schema/coerce-output-types {:s nil}    {:outputs {:s :string}})))))
  (testing ":int from a non-numeric string is left unchanged"
    (is (= "x" (:n (schema/coerce-output-types {:n "x"} {:outputs {:n :int}}))))))

(deftest coerce-output-types-invariants-test
  (testing "already-valid outputs pass through byte-identical (idempotent)"
    (let [good {:tool-calls [{:tool-name "bash"}] :code-blocks "" :answer "hi"
                :goal-achieved true :next-user-prompt ""}]
      (is (= good (schema/coerce-output-types good coact-like-sig)))
      (is (= good (schema/coerce-output-types
                   (schema/coerce-output-types good coact-like-sig) coact-like-sig)))))
  (testing "unrecoverable value is NOT force-kept — field stays invalid for the repair path"
    (let [bad   {:tool-calls {:wrong "shape"} :code-blocks "" :answer ""
                 :goal-achieved true :next-user-prompt ""}
          fixed (schema/coerce-output-types bad coact-like-sig)]
      ;; [{:wrong "shape"}] does not satisfy [:map [:tool-name :string]] → original kept
      (is (= {:wrong "shape"} (:tool-calls fixed)))
      (is (not (valid-outputs? fixed coact-like-sig)))))
  (testing "absent fields are neither added nor coerced"
    (is (= {:goal-achieved false}
           (schema/coerce-output-types {:goal-achieved "false"} coact-like-sig)))))

;; ---------------------------------------------------------------------------
;; Registry-ref fields — the real CoAct signature defines every output as a
;; registry ref (::tool-calls, ::goal-achieved …). Before deref-all, `m/type`
;; on a ref returns :malli.core/schema, so both coerce-output-types and
;; the default-fill saw an unknown type and no-op'd — making the whole layer
;; inert on the actual signature while the inline-schema tests above passed.
;; ---------------------------------------------------------------------------

(def ^:private ref-sig
  "Signature whose fields are registry refs, mirroring the real CoAct shape."
  {:outputs
   {:tool-calls [:schema {:registry {::tc [:vector [:map
                                                    [:tool-name :string]
                                                    [:tool-args [:vector [:map [:name :string] [:value :string]]]]]]}} ::tc]
    :goal-achieved [:schema {:registry {::b :boolean}} ::b]}})

(deftest coerce-output-types-resolves-registry-refs-test
  (testing "ref-typed :boolean field coerces (regression: deref-all before m/type)"
    (is (= false (:goal-achieved
                  (schema/coerce-output-types {:goal-achieved "false"} ref-sig)))))
  (testing "ref-typed :vector field coerces a placeholder to []"
    (is (= [] (:tool-calls
               (schema/coerce-output-types {:tool-calls "$PLACEHOLDER"} ref-sig))))))

;; ---------------------------------------------------------------------------
;; lift-flattened-collection — a model that splices a tool-call's inner keys
;; (tool-name/tool-args) to the TOP level instead of emitting tool-calls[].
;; ---------------------------------------------------------------------------

(deftest lift-flattened-collection-test
  (testing "flattened top-level tool-name/tool-args → single-element tool-calls (inner coerced)"
    (is (= {:tool-calls [{:tool-name "hook-agent$list" :tool-args []}]}
           (schema/lift-flattened-collection {:tool-name "hook-agent$list" :tool-args "[]"} ref-sig))))
  (testing "no identifying key (only tool-args) → left untouched for defaults, no junk call"
    (is (= {:tool-args "[]"}
           (schema/lift-flattened-collection {:tool-args "[]"} ref-sig))))
  (testing "blank identifying key → not lifted"
    (is (= {:tool-name "" :tool-args "[]"}
           (schema/lift-flattened-collection {:tool-name "" :tool-args "[]"} ref-sig))))
  (testing "placeholder/no-op tool name → NOT lifted (no doomed dispatch)"
    (is (= {:tool-name "noop" :tool-args "[]"}
           (schema/lift-flattened-collection {:tool-name "noop" :tool-args "[]"} ref-sig)))
    (is (= {:tool-name "none" :tool-args "[]"}
           (schema/lift-flattened-collection {:tool-name "none" :tool-args "[]"} ref-sig))))
  (testing "already-populated vector field is never overwritten"
    (let [present {:tool-calls [{:tool-name "x" :tool-args []}] :tool-name "y"}]
      (is (= present (schema/lift-flattened-collection present ref-sig)))))
  (testing "answer turn without inner keys is untouched"
    (is (= {:goal-achieved true}
           (schema/lift-flattened-collection {:goal-achieved true} ref-sig)))))
