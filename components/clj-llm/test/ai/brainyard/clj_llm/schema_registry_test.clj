;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.schema-registry-test
  "Tests for the shared mutable Malli registry behind `defschemas`.

   The load-bearing property is that `registry*` SURVIVES a reload of its own
   namespace. It used to be a plain `def`, which made reloading this ns
   destructive: it swapped in a fresh atom holding only malli's defaults,
   silently dropping every schema registered process-wide, and the next
   `defsignature` referencing one blew up with `:malli.core/invalid-schema`
   pointing at the innocent signature rather than at the reload."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-llm.core.schema-registry :as reg]
            [malli.core :as m]
            [malli.registry :as mr]))

(def ^:private probe ::probe-schema)

(defn- resolves? [k]
  (try (some? (m/schema k)) (catch Throwable _ false)))

(deftest registered-schemas-resolve
  (reg/register! {probe [:string {:desc "probe"}]})
  (is (resolves? probe) "a registered schema resolves through the default registry"))

(deftest registry-survives-reloading-its-own-namespace
  (reg/register! {probe [:string {:desc "probe"}]})
  (is (resolves? probe))

  (testing "reloading this namespace preserves every registration"
    (require 'ai.brainyard.clj-llm.core.schema-registry :reload)
    (is (contains? @reg/registry* probe)
        "defonce keeps the atom — a plain def would have wiped the whole registry")
    (is (resolves? probe)
        "and the reload re-asserts the default registry rather than orphaning it")))

(deftest reloading-this-namespace-repairs-a-reset-default-registry
  (testing "an external reset of malli's own registry breaks custom keys"
    (reg/register! {probe [:string {:desc "probe"}]})
    (is (resolves? probe))
    ;; What a `:reload` reaching malli.registry does: malli's internal registry
    ;; atom is re-def'd to the vanilla default, orphaning the mutable registry
    ;; installed at clj-llm load. Built-ins keep working, custom keys stop.
    (mr/set-default-registry! (m/default-schemas))
    (is (resolves? :string) "malli built-ins are unaffected")
    (is (not (resolves? probe)) "but registered schemas no longer resolve"))

  (testing "reloading this namespace is the repair"
    (require 'ai.brainyard.clj-llm.core.schema-registry :reload)
    (is (resolves? probe))))
