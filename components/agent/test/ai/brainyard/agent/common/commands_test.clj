;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.commands-test
  "Tests for the common command tools — focused on memory$remember kind
  validation (invalid kinds must return an actionable :error listing the
  valid kinds so the LLM retries instead of looping)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.commands :as cmds]
            [ai.brainyard.agent.common.sandbox-bindings :as sb]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.agent.core.usage :as usage]
            [ai.brainyard.memory.interface :as mem]))

(def ^:dynamic *mm* nil)

(use-fixtures :each
  (fn [f]
    (let [mm (mem/create-memory-manager (str "u-cmd-" (random-uuid))
                                        :in-memory true)]
      (try
        (binding [*mm* mm] (f))
        (finally
          (when (mem/capture-running? mm) (mem/stop-capture! mm))
          (.close (:ds mm)))))))

(defn- remember
  "Invoke memory$remember with the test memory-manager + a stub session-id
  bound in place of the private *current-agent* accessors."
  [args]
  (with-redefs-fn {#'cmds/current-mm         (constantly *mm*)
                   #'cmds/current-session-id (constantly "s-test")}
    (fn [] (cmds/memory$remember args))))

(defn- recall
  [args]
  (with-redefs-fn {#'cmds/current-mm         (constantly *mm*)
                   #'cmds/current-session-id (constantly "s-test")}
    (fn [] (cmds/memory$recall args))))

(deftest invalid-kind-returns-error-with-valid-list
  (testing "an explicit unknown kind for l3 is rejected, not written"
    (let [r (remember {:layer "l3" :kind "user-identity" :content "Jake's address is X"})]
      (is (string? (:error r)))
      (is (str/includes? (:error r) "Invalid kind \"user-identity\""))
      (is (str/includes? (:error r) "l3"))
      ;; The valid l3 fact-types must be enumerated so the LLM can retry.
      (is (str/includes? (:error r) "fact"))
      (is (str/includes? (:error r) "preference"))
      (is (nil? (:entry-id r)) "nothing should be persisted on a bad kind")))

  (testing "kind valid for the wrong layer is still rejected"
    ;; :conversation is an l2 episode-type, not an l3 fact-type.
    (let [r (remember {:layer "l3" :kind "conversation" :content "x"})]
      (is (str/includes? (:error r) "Invalid kind \"conversation\""))))

  (testing "l2 unknown kind is rejected with l2's valid set"
    (let [r (remember {:layer "l2" :kind "fact" :content "x"})]
      ;; :fact is an l3 fact-type, not an l2 episode-type.
      (is (str/includes? (:error r) "Invalid kind \"fact\""))
      (is (str/includes? (:error r) "conversation")))))

(deftest valid-kind-writes
  (testing "a valid explicit l3 kind persists"
    (let [r (remember {:layer "l3" :kind "preference" :content "Jake prefers dark mode"})]
      (is (nil? (:error r)))
      (is (some? (:entry-id r)))
      (is (= "l3" (:layer r)))
      (is (str/includes? (:result r) "kind: preference"))))

  (testing "omitted kind falls back to the per-layer default (l3 → fact)"
    (let [r (remember {:layer "l3" :content "Jake lives in Seongnam"})]
      (is (nil? (:error r)))
      (is (some? (:entry-id r)))
      (is (str/includes? (:result r) "kind: fact")))))

(deftest content-required
  (testing "blank content errors before any kind check"
    (is (= "content is required"
           (:error (remember {:layer "l3" :kind "fact" :content "   "}))))))

(deftest recall-invalid-kind-returns-error
  (testing "an unknown kind filter for a layer errors with the valid set"
    (let [r (recall {:layer "l3" :kind "user-identity" :query "x"})]
      (is (str/includes? (:error r) "Invalid kind \"user-identity\""))
      (is (str/includes? (:error r) "preference"))))

  (testing "kind valid for another layer is rejected for the chosen layer"
    (let [r (recall {:layer "l2" :kind "fact" :query "x"})]
      (is (str/includes? (:error r) "Invalid kind \"fact\""))
      (is (str/includes? (:error r) "conversation")))))

(deftest recall-kind-without-layer-errors
  (testing "a kind filter without :layer is rejected (cross-layer ignores kind)"
    (let [r (recall {:kind "fact" :query "x"})]
      (is (str/includes? (:error r) "requires a specific :layer")))))

(deftest recall-valid-kind-filters
  (testing "a valid kind filter for the layer searches without error"
    (remember {:layer "l3" :kind "preference" :content "Jake prefers dark mode"})
    (let [r (recall {:layer "l3" :kind "preference" :query "dark"})]
      (is (nil? (:error r)))
      (is (= "l3" (:layer r)))
      (is (>= (:count r) 1))))

  (testing "no kind + no layer does cross-layer recall without error"
    (let [r (recall {:query "dark"})]
      (is (nil? (:error r)))
      (is (= "combined" (:layer r))))))

;; ============================================================================
;; usage$guide — on-demand guide tool (callable via the tool-calls channel), also
;; auto-bound into the sandbox as `(usage$guide :topic <name>)` like any other tool.
;; ============================================================================

(deftest usage-tool-registered
  (let [td (tool/get-tool-defs :id :usage$guide)]
    (is (some? td) "usage$guide must be registered in the tool registry")
    (is (= :command (:type td)))))

(deftest usage-tool-no-topic-lists-catalog
  (let [r (tool/invoke-tool :usage$guide)]
    (is (nil? (:guide r)))
    (is (= (usage/list-usage-topics) (:topics r))
        "no topic → full topic catalog")))

(deftest usage-tool-known-topic-returns-guide
  ;; A known topic returns the guide as a bare STRING (not a wrapper map) so it
  ;; renders verbatim — real newlines preserved — in the iteration record.
  (let [r (tool/invoke-tool :usage$guide {:topic "memory"})]
    (is (string? r))
    (is (pos? (count r)))
    (is (= (usage/get-usage-guide :memory) r)
        "tool guide must match the registry source of truth")))

(deftest usage-tool-unknown-topic-errors-with-catalog
  (let [r (tool/invoke-tool :usage$guide {:topic "nope"})]
    (is (nil? (:guide r)))
    (is (str/includes? (:error r) "unknown topic"))
    (is (= (usage/list-usage-topics) (:topics r))
        "unknown topic → error + catalog so the caller can retry")))

(deftest usage-tool-new-topics-present
  (testing "the generalized registry includes the new agent-domain topics
            (:nrepl is colocated in debug-agent — see debug-agent-test)"
    (let [topics (set (usage/list-usage-topics))]
      (doseq [t [:tool :code :sandbox :agents]]
        (is (contains? topics t) (str t " should be registered"))
        (is (string? (usage/get-usage-guide t)) (str t " should have a guide"))))))

(deftest usage-binding-auto-bound-returns-tool-result
  ;; usage$guide is no longer special-cased — it reaches the sandbox via the
  ;; generic auto-tool-binding path like any other tool, so the binding returns
  ;; the RAW tool result map (no legacy unwrapping to a bare string). Canonical
  ;; call shapes: `(usage$guide)` (list) and `(usage$guide :topic :memory)`.
  (let [usage-fn (get (sb/make-tool-bindings nil) 'usage$guide)]
    (is (some? usage-fn) "usage$guide must be auto-bound into the sandbox")
    (is (= (usage/list-usage-topics) (:topics (usage-fn)))
        "no-arg returns the topic catalog in :topics")
    (is (= (usage/get-usage-guide :memory) (usage-fn :topic :memory))
        "known topic returns the guide as a bare string (renders verbatim)")
    (is (str/includes? (:error (usage-fn :topic :nope)) "unknown topic")
        "unknown topic returns an error + catalog")))

;; ============================================================================
;; query$llm — per-call :lm-config
;; ============================================================================
;;
;; The sub-LLM is normally the SESSION's `:sub-lm-config`, so a caller wanting a
;; cheap classification and a careful analysis in the same turn had no way to
;; say so. `:lm-config` names the model for ONE call. Two properties are worth
;; holding onto: the spec is always minted by `create-lm` (a hand-built map has
;; no :api-key/:base-url and would 401), and an unresolvable spec is an ERROR
;; rather than a quiet fall back to the session model.

(defn- stub-query
  "Invoke query$llm with both sub-LLM factories stubbed out, so the LM that WOULD
   have been called is captured instead of contacted. Returns
   `[result captured-lm-config]`."
  [args]
  (let [!seen (atom nil)]
    [(with-redefs-fn
       {#'clj-llm/create-llm-query-fn
        (fn [lm _tracker _opts] (reset! !seen lm) (fn [_p & _] "stub-answer"))
        #'clj-llm/create-llm-query-batched-fn
        (fn [lm _tracker _opts] (reset! !seen lm) (fn [ps & _] (mapv (constantly "stub") ps)))}
       (fn [] (cmds/query$llm args)))
     @!seen]))

(deftest query-llm-lm-config-selects-the-model-for-one-call
  (testing "a native map is minted through create-lm — provider, model AND the
            credential/base-url fields a hand-built map would lack"
    (let [[r lm] (stub-query {:prompts ["hi"] :lm-config {:provider "openai" :model "gpt-4o"}})]
      (is (= ["stub-answer"] (:results r)))
      (is (= :openai (:provider lm)))
      (is (= "gpt-4o" (:model lm)))
      (is (contains? lm :api-key) "create-lm must own credential resolution")
      (is (= "https://api.openai.com/v1" (:base-url lm)))
      (is (= "openai/gpt-4o" (:lm r)) "the serving model is echoed back when overridden")))

  (testing "string keys + string numbers (the JSON tool-calls channel)"
    (let [[_ lm] (stub-query {:prompts ["hi"] :lm-config {"provider" "openai" "model" "gpt-4o"
                                                          "temperature" "0.3"}})]
      (is (= :openai (:provider lm)))
      (is (= 0.3 (:temperature lm)))))

  (testing "an EDN map string — the tool-calls channel cannot express a map literal"
    (let [[_ lm] (stub-query {:prompts ["hi"] :lm-config "{:provider \"openai\" :model \"gpt-4o\"}"})]
      (is (= [:openai "gpt-4o"] [(:provider lm) (:model lm)]))))

  (testing "a bare provider/model label, the form :sub-lm-config already takes"
    (let [[_ lm] (stub-query {:prompts ["hi"] :lm-config "openai/gpt-4o"})]
      (is (= [:openai "gpt-4o"] [(:provider lm) (:model lm)]))))

  (testing "batched mode routes through the same resolution"
    (let [[r lm] (stub-query {:prompts ["a" "b"] :lm-config {:provider "openai" :model "gpt-4o"}})]
      (is (= ["stub" "stub"] (:results r)))
      (is (= "openai/gpt-4o" (:lm r)))
      (is (= :openai (:provider lm))))))

(deftest query-llm-without-lm-config-is-unchanged
  (testing "omitted → the agent's configured sub-LM, and no :lm in the result"
    (let [[r lm] (with-redefs-fn {#'config/resolve-sub-lm (constantly {:model "sentinel"})}
                   (fn [] (stub-query {:prompts ["hi"]})))]
      (is (= ["stub-answer"] (:results r)))
      (is (nil? (:lm r)) "a query on the session's own sub-LM stays as quiet as before")
      (is (= {:model "sentinel"} lm))))

  (testing "a blank string means 'not specified' — LLMs emit \"\" for skipped fields"
    (let [[_ lm] (with-redefs-fn {#'config/resolve-sub-lm (constantly {:model "sentinel"})}
                   (fn [] (stub-query {:prompts ["hi"] :lm-config ""})))]
      (is (= {:model "sentinel"} lm)))))

(deftest query-llm-has-one-shape-prompts-in-results-out
  (let [!path (atom nil)
        run   (fn [args]
                (with-redefs-fn
                  {#'config/resolve-sub-lm (constantly {:model "sentinel"})
                   #'clj-llm/create-llm-query-fn
                   (fn [_ _ _] (reset! !path :single) (fn [p & _] (str "one:" p)))
                   #'clj-llm/create-llm-query-batched-fn
                   (fn [_ _ _] (reset! !path :batched) (fn [ps & _] (mapv #(str "many:" %) ps)))}
                  (fn [] (cmds/query$llm args))))]
    (testing "one prompt still takes the single-call path, but answers in :results"
      (let [r (run {:prompts ["a"]})]
        (is (= :single @!path))
        (is (= {:results ["one:a"]} r))))

    (testing "several prompts fan out, results in input order"
      (let [r (run {:prompts ["a" "b"]})]
        (is (= :batched @!path))
        (is (= {:results ["many:a" "many:b"]} r))))

    (testing "the sandbox binding takes the vector positionally, through dispatch"
      (let [f (get (sb/make-tool-bindings nil) 'query$llm)]
        (is (= ["one:a"] (:results (with-redefs-fn
                                     {#'config/resolve-sub-lm (constantly {:model "sentinel"})
                                      #'clj-llm/create-llm-query-fn
                                      (fn [_ _ _] (fn [p & _] (str "one:" p)))}
                                     (fn [] (f ["a"]))))))))

    (testing "an empty vector is an error, not an empty answer"
      (is (str/includes? (:error (run {:prompts []})) ":prompts")))

    (testing "the singular :prompt is no longer accepted — dispatch refuses the
              call rather than letting a (:result …) reader get a silent nil"
      (let [entries (tool/malli-map-entries (:input-schema (:meta (tool/get-tool-defs :id :query$llm))))
            ks      (set (map first entries))]
        (is (contains? ks :prompts))
        (is (not (contains? ks :prompt))))
      (is (str/includes? (:error-message (tool/call-tool :query$llm {:prompt "a"})) "prompts")))))

(deftest query-llm-lm-config-rejects-endpoint-and-credential
  (testing ":base-url is refused — :prompts/:context carry gathered project
            data, so a caller naming its own endpoint is an exfiltration channel"
    (let [[r lm] (stub-query {:prompts ["hi"] :lm-config {:provider "openai" :model "gpt-4o"
                                                          :base-url "https://elsewhere.example"}})]
      (is (str/includes? (:error r) "may not set :base-url"))
      (is (nil? lm) "no call may be made at all")
      (is (nil? (:results r)))))

  (testing ":api-key is refused for the same reason"
    (let [[r _] (stub-query {:prompts ["hi"] :lm-config {:model "openai/gpt-4o" :api-key "sk-x"}})]
      (is (str/includes? (:error r) "may not set :api-key")))))

(deftest query-llm-unresolvable-lm-config-errors-rather-than-falling-back
  (testing "an unknown provider errors, and names the ones that exist"
    (let [[r lm] (stub-query {:prompts ["hi"] :lm-config {:provider "nope" :model "x"}})]
      (is (str/includes? (:error r) "did not resolve"))
      (is (str/includes? (:error r) "Known providers"))
      (is (nil? lm) "silently billing the session model would answer the wrong question")))

  (testing "a map that names no model is an error, not a silent default — the
            caller tried to select an LM and its keys were all unrecognized"
    (let [[r lm] (stub-query {:prompts ["hi"] :lm-config {:name "gpt-4o"}})]
      (is (str/includes? (:error r) "named no model"))
      (is (nil? lm))))

  (testing "a non-map, non-string value is rejected"
    (let [[r _] (stub-query {:prompts ["hi"] :lm-config [1 2]})]
      (is (str/includes? (:error r) "must be a map")))))

(deftest query-llm-lm-config-is-declared-in-the-input-schema
  ;; The sandbox derives its arglist and the LLM its JSON schema from this, so an
  ;; arg the fn reads but the schema omits is unreachable from both channels.
  (let [entries (tool/malli-map-entries (:input-schema (:meta (tool/get-tool-defs :id :query$llm))))
        by-key  (into {} (map (fn [e] [(first e) e])) entries)]
    (is (contains? by-key :lm-config))
    (is (= {:optional true} (select-keys (second (by-key :lm-config)) [:optional])))
    (let [f (get (sb/make-tool-bindings nil) 'query$llm)]
      (is (some? f) "query$llm must stay auto-bound into the sandbox")
      (is (str/includes? (:doc (meta f)) ":lm-config")
          "the sandbox docstring must advertise the new arg"))))

;; ============================================================================
;; query$llm :output-schema + query$structured-output
;; ============================================================================

(def ^:private item-schema
  {"type" "object"
   "properties" {"label" {"type" "string"} "count" {"type" "integer"}}
   "required" ["label" "count"]})

(defn- structured-query
  "query$llm with the factories stubbed to answer `answers` in order; returns
   `[result opts-seen]`."
  [args answers]
  (let [!opts (atom nil)]
    [(with-redefs-fn
       {#'config/resolve-sub-lm (constantly {:model "sentinel"})
        #'clj-llm/create-llm-query-fn
        (fn [_ _ opts] (reset! !opts opts) (fn [& _] (first answers)))
        #'clj-llm/create-llm-query-batched-fn
        (fn [_ _ opts] (reset! !opts opts) (fn [& _] (vec answers)))}
       (fn [] (cmds/query$llm args)))
     @!opts]))

(deftest query-llm-output-schema-returns-json-values-and-the-schema
  (testing "each result is parsed JSON, and the schema sent is echoed back"
    (let [[r opts] (structured-query {:prompts ["a" "b"] :output-schema item-schema}
                                     ["{\"label\":\"x\",\"count\":1}" "```json\n{\"label\":\"y\",\"count\":2}\n```"])]
      (is (= [{:label "x" :count 1} {:label "y" :count 2}] (:results r)))
      (is (= "object" (get-in r [:output-schema :type])))
      (is (= (:output-schema r) (:json-schema opts)) "the same schema reaches the LLM call")))

  (testing "a result that does not parse stays a string rather than becoming nil"
    (let [[r _] (structured-query {:prompts ["a"] :output-schema item-schema} ["sorry, no"])]
      (is (= ["sorry, no"] (:results r)))))

  (testing "a JSON string schema and a Malli schema are both accepted"
    (let [[r1 _] (structured-query {:prompts ["a"] :output-schema "{\"type\":\"object\"}"} ["{}"])
          [r2 _] (structured-query {:prompts ["a"] :output-schema [:map [:label :string]]} ["{\"label\":\"x\"}"])]
      (is (= "object" (get-in r1 [:output-schema :type])))
      (is (= "object" (get-in r2 [:output-schema :type])))
      (is (= [{:label "x"}] (:results r2)))))

  (testing "without :output-schema nothing changes — strings, no schema key, no :json-schema sent"
    (let [[r opts] (structured-query {:prompts ["a"]} ["plain"])]
      (is (= {:results ["plain"]} r))
      (is (nil? (:json-schema opts)))))

  (testing "a non-object top level is refused before any call"
    (let [[r opts] (structured-query {:prompts ["a"] :output-schema {"type" "array"}} ["[]"])]
      (is (str/includes? (:error r) "JSON object"))
      (is (nil? opts)))))

(deftest query-structured-output-validates-values
  (testing "a query$llm result pairs straight into the validator"
    (let [[r _] (structured-query {:prompts ["a" "b" "c"] :output-schema item-schema}
                                  ["{\"label\":\"x\",\"count\":1}" "{\"label\":\"y\"}" "not json"])
          v     (cmds/query$structured-output {:output-schema (:output-schema r) :values (:results r)})]
      (is (false? (:valid? v)))
      (is (= [1 2] (:invalid v)) "only the failing indices — rerun just those")
      (is (some #(= {:index 1 :path ["count"] :message "missing required property"} %) (:errors v)))
      (is (some #(and (= 2 (:index %)) (str/includes? (:message %) "not valid JSON")) (:errors v)))))

  (testing "all valid"
    (is (= {:valid? true :invalid [] :errors []}
           (cmds/query$structured-output {:output-schema item-schema
                                          :values [{"label" "x" "count" 1}]}))))

  (testing "JSON text is parsed before validating"
    (is (:valid? (cmds/query$structured-output {:output-schema item-schema
                                                :values ["{\"label\":\"x\",\"count\":1}"]}))))

  (testing "through dispatch, from the tool-calls channel (string-keyed JSON)"
    (let [r (tool/call-tool :query$structured-output
                            {"output-schema" item-schema "values" [{"label" "x" "count" "1"}]})]
      (is (false? (:valid? r)))
      (is (= ["count"] (:path (first (:errors r)))))))

  (testing "an unusable schema is an :error"
    (is (str/includes? (:error (cmds/query$structured-output {:output-schema 42 :values []}))
                       ":output-schema"))))


(deftest query-llm-output-schema-includes-validation
  (testing "a valid result carries :validation with :valid? true"
    (let [[r _] (structured-query {:prompts ["a"] :output-schema item-schema}
                                  ["{\"label\":\"x\",\"count\":1}"])]
      (is (= [{:label "x" :count 1}] (:results r)))
      (is (true? (get-in r [:validation :valid?])))
      (is (= [] (get-in r [:validation :invalid])))
      (is (= [] (get-in r [:validation :errors])))))

  (testing "an invalid result (wrong type) carries :validation with :valid? false and errors"
    (let [[r _] (structured-query {:prompts ["a"] :output-schema item-schema}
                                  ["{\"label\":\"x\",\"count\":\"not-a-number\"}"])]
      (is (false? (get-in r [:validation :valid?])))
      (is (= [0] (get-in r [:validation :invalid])))
      (is (some #(and (= 0 (:index %)) (= ["count"] (:path %))) (get-in r [:validation :errors])))))

  (testing "without :output-schema, no :validation key appears"
    (let [[r _] (structured-query {:prompts ["a"]} ["plain"])]
      (is (not (contains? r :validation)))))

  (testing "validation never throws -- it reuses the same helper as query$structured-output"
    (let [[r _] (structured-query {:prompts ["a"] :output-schema item-schema} ["not json at all"])]
      (is (= ["not json at all"] (:results r)))
      (is (map? (:validation r)))
      (is (false? (get-in r [:validation :valid?]))))))
