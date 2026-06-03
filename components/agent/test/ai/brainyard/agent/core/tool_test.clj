;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.tool-test
  "Unit tests for the deftool registry-path argument-coercion pipeline.

   Regression target: LLM tool-call JSON arrives with stringly-typed values
   (`{\"rung\" \"b\"}`), but tool schemas declare `[:keyword ...]` inputs. Prior
   to the fix, `schema->type` flattened `:keyword` to `{:type \"string\"}` and
   the ad-hoc `coerce-tool-args` never fired on the registry path — Malli
   rejected the string with `should be a keyword` and the LLM had no recovery
   path on the tool-call channel; it would loop tool-calls then resort to a
   clojure fence.

   Coercion is now done by `llm-args-transformer` (a Malli transformer) so it
   walks the declared schema and coerces leaves at any depth."
  (:require [ai.brainyard.agent.core.tool :as tool]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]))

;; ----------------------------------------------------------------------------
;; llm-args-transformer — direct decoding tests
;; ----------------------------------------------------------------------------

(defn- decode
  "Mirror the registry-path decode pipeline: normalize the input-schema,
   keywordize top-level keys, then decode with the LLM transformer."
  [input-schema args]
  (let [schema (#'tool/inputs->malli-map-schema input-schema)]
    (m/decode schema (update-keys args keyword) tool/llm-args-transformer)))

(deftest llm-args-transformer-scalar-coercions
  (testing ":keyword from bare string"
    (is (= {:rung :b} (decode [:map [:rung :keyword]] {"rung" "b"}))))
  (testing ":keyword from leading-colon string (LLMs hedge with both)"
    (is (= {:rung :a} (decode [:map [:rung :keyword]] {"rung" ":a"})))
    (is (not= ::a (:rung (decode [:map [:rung :keyword]] {"rung" ":a"})))
        "must not get namespace-qualified (mt/string-transformer's default)"))
  (testing ":int from string"
    (is (= {:n 42} (decode [:map [:n :int]] {"n" "42"}))))
  (testing ":boolean from string"
    (is (= {:auto? false} (decode [:map [:auto? :boolean]] {"auto?" "false"}))))
  (testing "native typed values pass through"
    (is (= {:auto? true} (decode [:map [:auto? :boolean]] {"auto?" true})))
    (is (= {:n 42}       (decode [:map [:n :int]]         {"n"     42}))))
  (testing ":string is a no-op"
    (is (= {:s "hello"} (decode [:map [:s :string]] {"s" "hello"}))))
  (testing "keyword :enum coerces"
    (is (= {:rung :b} (decode [:map [:rung [:enum :a :b :c]]] {"rung" "b"}))))
  (testing "string :enum does NOT coerce to keyword"
    (is (= {:src "mcp"} (decode [:map [:src [:enum "mcp" "registered"]]] {"src" "mcp"}))))
  (testing ":maybe unwraps"
    (is (= {:scope :auto} (decode [:map [:scope [:maybe :keyword]]] {"scope" "auto"})))
    (is (= {:scope nil}   (decode [:map [:scope [:maybe :keyword]]] {"scope" nil})))))

(deftest llm-args-transformer-enum-dual-form
  ;; Enum fields are reached as a wire STRING (JSON tool-call) or a KEYWORD
  ;; (sandbox code-fence). The :enum decoder reconciles either to the enum's
  ;; OWN member type, and — like the :keyword decoder — strips a hedged leading
  ;; colon. The colon case is the one mt/string-transformer gets wrong on its
  ;; own (":b" -> ::b), so a keyword-[:enum] field can gain enum guidance while
  ;; still handing the handler the keyword it expects.
  (testing "keyword-member enum: string / colon-string / keyword all -> keyword member"
    (is (= {:rung :b} (decode [:map [:rung [:enum :a :b :c]]] {"rung" "b"})))
    (is (= {:rung :b} (decode [:map [:rung [:enum :a :b :c]]] {"rung" ":b"})))
    (is (= {:rung :b} (decode [:map [:rung [:enum :a :b :c]]] {"rung" :b})))
    (is (not= ::b (:rung (decode [:map [:rung [:enum :a :b :c]]] {"rung" ":b"})))
        "must strip the colon, not namespace-qualify like mt/string-transformer"))
  (testing "string-member enum: keyword / colon-string / string all -> name string"
    (is (= {:src "mcp"} (decode [:map [:src [:enum "mcp" "reg"]]] {"src" :mcp})))
    (is (= {:src "mcp"} (decode [:map [:src [:enum "mcp" "reg"]]] {"src" ":mcp"})))
    (is (= {:src "mcp"} (decode [:map [:src [:enum "mcp" "reg"]]] {"src" "mcp"})))))

(deftest llm-args-transformer-nested-coercions
  ;; Coverage for cases C & D — nested keyword / vector-of-keyword schemas.
  ;; Without the transformer these would Malli-reject just like the trace bug.
  (testing "[:vector :keyword] coerces each element"
    (is (= {:tags [:a :b :c]}
           (decode [:map [:tags [:vector :keyword]]] {"tags" ["a" "b" ":c"]}))))
  (testing "[:map [:foo :keyword] [:n :int]] recurses into the inner map"
    (is (= {:cfg {:foo :x :n 5}}
           (decode [:map [:cfg [:map [:foo :keyword] [:n :int]]]]
                   {"cfg" {:foo "x" :n "5"}})))))

(deftest llm-args-transformer-bare-map-is-opaque
  ;; Sanity / known limitation: a bare [:map] declaration has no nested field
  ;; info, so the transformer can't keywordize inner keys or coerce inner
  ;; values. This is a pre-existing gap — tool authors who want coercion
  ;; should declare nested fields. The transformer correctly leaves the inner
  ;; map untouched rather than guessing.
  (is (= {:proposed {"agent" {"max-iterations" 50}}}
         (decode [:map [:proposed [:map]]] {"proposed" {"agent" {"max-iterations" 50}}}))))

;; ----------------------------------------------------------------------------
;; End-to-end through `call-tool` — the trace regression
;; ----------------------------------------------------------------------------

(deftest call-tool-coerces-strings-to-keywords-on-malli-path
  ;; Mirrors the trace at /tmp/c.txt: `bootstrap$re-run-rung` with
  ;; `{"rung" "b", "provider" "claude-code"}` was rejected 12× before the LLM
  ;; gave up and used a clojure fence. After the fix the tool-call path works.
  (let [tool-id  (keyword (str "test-kw-coerce-" (System/nanoTime)))
        captured (atom nil)
        tool-def {:meta {:name        (name tool-id)
                         :description "regression test fixture"
                         :type        :tool
                         :input-schema  [:map
                                         [:rung     [:keyword {:desc ":a :b :c"}]]
                                         [:provider {:optional true} :keyword]
                                         [:model    {:optional true} :string]
                                         [:auto?    {:optional true} :boolean]]
                         :output-schema [:map]}
                  :fn   (fn [& {:as kwargs}]
                          (reset! captured kwargs)
                          {:ok? true})
                  :type :tool}]
    (try
      (swap! tool/!tool-defs assoc tool-id tool-def)
      (testing "bare string values are coerced to keywords / ints / bools"
        (let [result (tool/call-tool tool-id {"rung"     "b"
                                              "provider" "claude-code"
                                              "model"    "opus"
                                              "auto?"    "false"})]
          (is (not (contains? result :error-message))
              (str "should not surface Malli error; got: " (pr-str result)))
          (is (= :b           (:rung     @captured)))
          (is (= :claude-code (:provider @captured)))
          (is (= "opus"       (:model    @captured)) ":string passes through")
          (is (= false        (:auto?    @captured)) ":boolean coerces too")))
      (testing "leading-colon strings are also accepted (LLMs hedge with both)"
        (reset! captured nil)
        (tool/call-tool tool-id {"rung" ":a" "provider" ":claude-code"})
        (is (= :a           (:rung     @captured)))
        (is (= :claude-code (:provider @captured))))
      (finally
        (swap! tool/!tool-defs dissoc tool-id)))))

(deftest call-tool-nested-keyword-input
  ;; Nested schema: validates that `[:vector :keyword]` and
  ;; `[:map [:k :keyword]]` reach the tool fn with real keywords, not strings.
  (let [tool-id  (keyword (str "test-kw-nested-" (System/nanoTime)))
        captured (atom nil)
        tool-def {:meta {:name        (name tool-id)
                         :description "regression test fixture (nested)"
                         :type        :tool
                         :input-schema  [:map
                                         [:tags [:vector :keyword]]
                                         [:cfg  [:map [:section :keyword] [:n :int]]]]
                         :output-schema [:map]}
                  :fn   (fn [& {:as kwargs}]
                          (reset! captured kwargs)
                          {:ok? true})
                  :type :tool}]
    (try
      (swap! tool/!tool-defs assoc tool-id tool-def)
      (let [result (tool/call-tool tool-id {"tags" ["a" "b"]
                                            "cfg"  {:section "agent" :n "5"}})]
        (is (not (contains? result :error-message))
            (str "nested schema should decode end-to-end; got: " (pr-str result)))
        (is (= [:a :b]              (:tags @captured)))
        (is (= {:section :agent :n 5} (:cfg  @captured))))
      (finally
        (swap! tool/!tool-defs dissoc tool-id)))))

(deftest call-tool-rejects-truly-invalid-keyword-args
  ;; Confirm the coercion fix didn't turn Malli into a rubber stamp:
  ;; missing required args still get rejected.
  (let [tool-id  (keyword (str "test-kw-required-" (System/nanoTime)))
        tool-def {:meta {:name        (name tool-id)
                         :description "regression test fixture"
                         :type        :tool
                         :input-schema  [:map [:rung [:keyword {:desc ":a :b"}]]]
                         :output-schema [:map]}
                  :fn   (fn [& _] {:ok? true})
                  :type :tool}]
    (try
      (swap! tool/!tool-defs assoc tool-id tool-def)
      (let [result (tool/call-tool tool-id {})]
        (is (re-find #"Invalid tool args" (str (:error-message result)))))
      (finally
        (swap! tool/!tool-defs dissoc tool-id)))))

;; ----------------------------------------------------------------------------
;; Sanity: Malli does reject the un-coerced shape (so the regression test
;; above would have failed before the fix).
;; ----------------------------------------------------------------------------

(deftest malli-rejects-string-for-keyword-without-coercion
  (let [schema (#'tool/inputs->malli-map-schema
                [:map [:rung [:keyword {:desc ":a :b"}]]])]
    (is (some? (m/explain schema {:rung "b"}))
        "string \"b\" must not satisfy [:keyword] — this is the constraint that requires coercion")
    (is (nil? (m/explain schema {:rung :b}))
        "keyword :b satisfies [:keyword] — what coercion must produce")))
