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
