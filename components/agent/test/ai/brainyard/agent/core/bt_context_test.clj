;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.bt-context-test
  "CR-BT-25 — these tests are the reason the registry is worth having.

   A declaration nothing checks is prose, and prose about 54 keys written by 13
   namespaces rots on the first rename. Phase 1 taught this the expensive way:
   `::eval-entry` had declared three required keys the producer never wrote,
   and nobody knew until something finally validated it.

   So every field that CAN be checked against the source is:
     - `:lifetime :iteration` is pinned by RUNNING the real reset and diffing.
     - every `:writers` symbol must resolve to a real var.
     - every `:schema` must resolve in the malli registry.
     - the ThinkActCode contract must be fully covered."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.set :as set]
            [ai.brainyard.agent.core.bt-context :as bc]
            [ai.brainyard.agent.common.coact-agent :as rca]
            [malli.core :as m]))

;; ============================================================================
;; Shape
;; ============================================================================

(deftest every-declaration-is-complete-test
  (testing "each declared key carries the fields the accessors depend on"
    (doseq [[k v] bc/context-keys]
      (is (string? (:doc v)) (str k " needs a :doc"))
      (is (contains? #{:session :turn :iteration} (:lifetime v))
          (str k " needs a :lifetime of :session / :turn / :iteration"))
      (is (boolean? (:persist? v)) (str k " needs a :persist?"))
      (is (set? (:writers v)) (str k " needs a :writers set (possibly empty)")))))

(deftest opaque-implies-not-persistable-test
  (testing "a key holding a live object or a function cannot be serialized —
            this is the SCI fn-serialization limit stated as an invariant"
    (doseq [k (bc/opaque-keys)]
      (is (false? (:persist? (bc/declaration k)))
          (str k " is :opaque? and must not be :persist? true")))
    (is (empty? (set/intersection (bc/opaque-keys) (bc/persistable-keys))))))

(deftest declared-and-undeclared-are-disjoint-test
  (testing "a key is either declared or deliberately left out, never both"
    (is (empty? (set/intersection (set (keys bc/context-keys))
                                  (set (keys bc/deliberately-undeclared)))))))

(deftest undeclared-entries-carry-a-reason-test
  (testing "'left out' is only meaningful with a why"
    (doseq [[k why] bc/deliberately-undeclared]
      (is (and (string? why) (seq why)) (str k " needs a reason")))))

;; ============================================================================
;; Pinned against the source
;; ============================================================================

(def ^:private collection-valued
  "Keys whose value is traversed downstream, so the probe below must seed them
   with a collection rather than a sentinel keyword."
  #{:iterations :trajectory-iterations :tool-calls :last-tool-results
    :last-code-results :previous-turns :conversation :parent-trail :tools
    :live-artifacts :script-entries :code-langs :tools-disabled-tiers :todo-list})

(deftest iteration-lifetime-matches-the-real-reset-test
  (testing ":lifetime :iteration must be EXACTLY the set coact-inc-iter-action
            clears — established by running it and diffing, not by reading it.
            A key added to (or dropped from) that reset silently changes
            lifetime, and this is what catches it."
    (let [before (into {} (map (fn [k] [k (if (collection-valued k) [::s] ::s)]))
                       (keys bc/context-keys))
          st     (atom (assoc before :iteration-count 3))]
      (rca/coact-inc-iter-action {:st-memory st :agent nil})
      (let [after   @st
            changed (into #{} (keep (fn [[k v]] (when (not= v (get after k)) k))) before)
            ;; :iteration-count is incremented rather than reset, and is a
            ;; per-TURN counter — it is the one key the reset touches that does
            ;; not belong to the iteration lifetime.
            reset-set (disj changed :iteration-count)
            declared  (bc/keys-with-lifetime :iteration)]
        (is (= declared reset-set)
            (str "registry vs reality — only in registry: "
                 (sort (set/difference declared reset-set))
                 "; only in the reset: "
                 (sort (set/difference reset-set declared))))))))

(deftest every-writer-symbol-resolves-test
  (testing "a :writers entry naming a fn that no longer exists is exactly the
            drift this registry is supposed to make impossible"
    (doseq [[k decl] bc/context-keys
            sym      (:writers decl)]
      (is (some? (try (requiring-resolve sym) (catch Throwable _ nil)))
          (str k " names a writer that does not resolve: " sym)))))

(deftest every-declared-schema-resolves-test
  (testing ":schema keywords are resolved late through clj-llm's malli registry,
            so a dead reference is invisible until something validates — check it"
    (doseq [[k decl] bc/context-keys
            :let [s (:schema decl)]
            :when s]
      (is (some? (try (m/schema s) (catch Throwable _ nil)))
          (str k " names a schema that does not resolve: " s)))))

;; ============================================================================
;; Coverage of the contract CR-BT-26 already enforces
;; ============================================================================

(deftest think-act-code-contract-is-fully-declared-test
  (testing "every ThinkActCode input and output is a declared key — these are
            the keys the DSPy boundary validates on every call, so the registry
            failing to describe them would be the worst possible gap"
    (doseq [k (concat (keys (:inputs rca/ThinkActCode))
                      (keys (:outputs rca/ThinkActCode)))]
      (is (bc/declared? k) (str k " is a ThinkActCode field but is undeclared")))))

(deftest signature-input-schemas-agree-with-the-registry-test
  (testing "where both name a schema for the same key, they must name the SAME
            one — two schemas for one key is the drift of Phase 1 all over again"
    (doseq [[k raw] (:inputs rca/ThinkActCode)
            :let [declared (bc/schema-of k)]
            :when (and declared (keyword? raw))]
      (is (= raw declared)
          (str k ": signature says " raw ", registry says " declared)))))

;; ============================================================================
;; Sanity on the numbers the design doc quotes
;; ============================================================================

(deftest coverage-is-reported-test
  (testing "coverage is a number, not an impression"
    (let [{:keys [declared undeclared by-lifetime]} (bc/coverage)]
      (is (pos? declared))
      (is (pos? undeclared))
      (is (= declared (reduce + (vals by-lifetime)))
          "every declared key must land in exactly one lifetime bucket"))))
