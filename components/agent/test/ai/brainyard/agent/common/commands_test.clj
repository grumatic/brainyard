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
            [ai.brainyard.agent.core.tool :as tool]
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
;; usage — on-demand guide tool (callable via the tool-calls channel), with the
;; sandbox `(usage …)` binding delegating to it as the single source of truth.
;; ============================================================================

(deftest usage-tool-registered
  (let [td (tool/get-tool-defs :id :usage)]
    (is (some? td) "usage must be registered in the tool registry")
    (is (= :command (:type td)))))

(deftest usage-tool-no-topic-lists-catalog
  (let [r (tool/invoke-tool :usage)]
    (is (nil? (:guide r)))
    (is (= (usage/list-usage-topics) (:topics r))
        "no topic → full topic catalog")))

(deftest usage-tool-known-topic-returns-guide
  (let [r (tool/invoke-tool :usage {:topic "memory"})]
    (is (= "memory" (:topic r)))
    (is (string? (:guide r)))
    (is (pos? (count (:guide r))))
    (is (= (usage/get-usage-guide :memory) (:guide r))
        "tool guide must match the registry source of truth")))

(deftest usage-tool-unknown-topic-errors-with-catalog
  (let [r (tool/invoke-tool :usage {:topic "nope"})]
    (is (nil? (:guide r)))
    (is (str/includes? (:error r) "unknown topic"))
    (is (= (usage/list-usage-topics) (:topics r))
        "unknown topic → error + catalog so the caller can retry")))

(deftest usage-tool-new-topics-present
  (testing "the generalized registry includes the new agent-domain topics"
    (let [topics (set (usage/list-usage-topics))]
      (doseq [t [:tool :code :sandbox :nrepl :agents]]
        (is (contains? topics t) (str t " should be registered"))
        (is (string? (usage/get-usage-guide t)) (str t " should have a guide"))))))

(deftest usage-binding-delegates-and-preserves-legacy-shape
  ;; The sandbox `(usage …)` binding routes through the :usage tool but must
  ;; keep returning the legacy shape: a vector for no-arg, a raw guide STRING
  ;; for a known topic, an error map otherwise.
  (let [usage-fn (get (sb/make-usage-bindings nil) 'usage)]
    (is (= (usage/list-usage-topics) (usage-fn))
        "no-arg binding returns the topic vector, not a wrapper map")
    (is (= (usage/get-usage-guide :memory) (usage-fn :memory))
        "known topic returns the raw guide string")
    (is (str/includes? (:error (usage-fn :nope)) "unknown topic"))
    (is (str/includes? (:error (usage-fn 123)) "must be a keyword/string/symbol")
        "bad arg type is rejected locally with the legacy message")))
