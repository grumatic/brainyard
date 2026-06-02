;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.user-tools-test
  "Tests for runtime-defined (LLM-authored) tools.

   Exercises the full path against the REAL clj-sandbox + tool registry:
   define-from-source -> persist -> register -> dispatch via tool/call-tool
   (with Malli coercion/validation) -> compose other tools -> rehydrate from
   disk after a simulated restart. The rehydration test is the load-bearing
   one: it is the capability a plain `defn` in the sandbox cannot provide."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.agent.common.user-tools :as ut]
            [ai.brainyard.agent.common.sandbox-bindings :as sb-bind]
            [ai.brainyard.agent.core.tool :as tool]))

(def ^:private test-dirs
  {:project-dir (str (System/getProperty "java.io.tmpdir") "/by-user-tools-test")})

(def ^:private our-ids
  [:user$wc-test :user$long-test :user$echo-test])

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-rf! c)))
  (.delete f))

(defn- delete-tools-dir! []
  (rm-rf! (io/file (str (:project-dir test-dirs) "/.brainyard/tools"))))

(defn- clean! []
  (ut/reset-tools-sandbox!)
  (apply swap! tool/!tool-defs dissoc our-ids)
  (delete-tools-dir!))

(use-fixtures :each (fn [f] (clean!) (try (f) (finally (clean!)))))

(deftest define-and-invoke
  (testing "define-tool persists source and registers under user$<name>"
    (let [r (ut/define-tool
              :name "wc-test"
              :description "Count words."
              :input-schema [:map [:text :string]]
              :body "(fn [{:keys [text]}] {:words (count (clojure.string/split text #\"\\s+\"))})"
              :dirs test-dirs)]
      (is (= :user$wc-test (:id r)))
      (is (.exists (io/file (:persisted r))))
      (is (contains? (tool/get-tool-defs) :user$wc-test))))
  (testing "invokes through the real tool/call-tool dispatcher"
    (is (= {:words 4} (tool/call-tool :user$wc-test {:text "the quick brown fox"})))))

(deftest malli-validation
  (ut/define-tool :name "wc-test" :description "Count words."
    :input-schema [:map [:text :string]]
    :body "(fn [{:keys [text]}] {:words (count (clojure.string/split text #\"\\s+\"))})"
    :dirs test-dirs)
  (testing "missing required arg is rejected by the registry Malli path"
    (is (str/includes? (:error-message (tool/call-tool :user$wc-test {})) "missing required key")))
  (testing "wrong type is rejected"
    (is (str/includes? (:error-message (tool/call-tool :user$wc-test {:text 42})) "should be a string"))))

(deftest composes-another-tool
  (testing "a user tool body composes another user tool by its DIRECT symbol"
    (ut/define-tool :name "wc-test" :description "Count words."
      :input-schema [:map [:text :string]]
      :body "(fn [{:keys [text]}] {:words (count (clojure.string/split text #\"\\s+\"))})"
      :dirs test-dirs)
    (ut/define-tool :name "long-test" :description "More than 3 words?"
      :input-schema [:map [:text :string]]
      :body "(fn [{:keys [text]}] {:long? (> (:words (user$wc-test {:text text})) 3)})"
      :dirs test-dirs)
    (is (= {:long? true}  (tool/call-tool :user$long-test {:text "the quick brown fox jumps"})))
    (is (= {:long? false} (tool/call-tool :user$long-test {:text "just three words"})))))

(deftest composes-builtin-bash
  (testing "a body calls a builtin tool by its DIRECT symbol (via :extra-bindings)"
    (ut/define-tool :name "echo-test" :description "Echo via direct bash symbol."
      :input-schema [:map]
      :body "(fn [_] {:echoed (clojure.string/trim (:output (bash {:command \"echo direct\"})))})"
      :dirs test-dirs
      :extra-bindings (sb-bind/auto-tool-bindings nil))
    (is (= {:echoed "direct"} (tool/call-tool :user$echo-test {})))))

(deftest rehydrates-after-restart
  (testing "persisted source survives a simulated restart (sandbox + registry wiped)"
    (ut/define-tool :name "wc-test" :description "Count words."
      :input-schema [:map [:text :string]]
      :body "(fn [{:keys [text]}] {:words (count (clojure.string/split text #\"\\s+\"))})"
      :dirs test-dirs)
    (ut/define-tool :name "long-test" :description "More than 3 words?"
      :input-schema [:map [:text :string]]
      :body "(fn [{:keys [text]}] {:long? (> (:words (user$wc-test {:text text})) 3)})"
      :dirs test-dirs)
    ;; wipe live state — closures are gone, only the .edn source remains
    (ut/reset-tools-sandbox!)
    (swap! tool/!tool-defs dissoc :user$wc-test :user$long-test)
    (is (not (contains? (tool/get-tool-defs) :user$wc-test)))
    ;; reload from disk and confirm BOTH the tool and its composed dependency work
    (let [loaded (set (ut/load-user-tools! :dirs test-dirs))]
      (is (contains? loaded "wc-test"))
      (is (contains? loaded "long-test")))
    (is (= {:long? true} (tool/call-tool :user$long-test {:text "the quick brown fox jumps"})))))

(deftest discoverable-via-list-tools
  (testing "user tools show up in list-tools with their schema"
    (ut/define-tool :name "wc-test" :description "Count words."
      :input-schema [:map [:text :string]]
      :body "(fn [{:keys [text]}] {:words 1})"
      :dirs test-dirs)
    (let [hits (tool/invoke-tool :list-tools {:pattern "user\\$wc-test"})]
      (is (= 1 (count hits)))
      (is (= "user$wc-test" (:id (first hits))))
      (is (= [:map [:text :string]] (:input-schema (first hits)))))))

(deftest ensure-loaded-idempotent
  (testing "tools persist project-scoped under .brainyard/tools (no user-id segment)"
    (let [r (ut/define-tool :name "wc-test" :description "Count."
              :input-schema [:map [:text :string]]
              :body "(fn [{:keys [text]}] {:words 1})"
              :dirs test-dirs)]
      (is (str/ends-with? (:persisted r) "/.brainyard/tools/wc-test.edn"))))
  (testing "ensure-loaded! loads once then no-ops for the same project dir"
    (ut/reset-tools-sandbox!)                       ;; also clears the loaded-dirs set
    (swap! tool/!tool-defs dissoc :user$wc-test)
    (is (= ["wc-test"] (ut/ensure-loaded! :dirs test-dirs)))
    (is (contains? (tool/get-tool-defs) :user$wc-test))
    (is (nil? (ut/ensure-loaded! :dirs test-dirs)))))

(deftest rejects-bad-definitions
  (testing "invalid name"
    (is (thrown? Exception
                 (ut/define-tool :name "Bad Name" :description "x"
                   :body "(fn [_] 1)" :dirs test-dirs))))
  (testing "non-[:map] input-schema"
    (is (thrown? Exception
                 (ut/define-tool :name "okname" :description "x"
                   :input-schema [:vector :string]
                   :body "(fn [_] 1)" :dirs test-dirs))))
  (testing "body that does not eval"
    (is (thrown? Exception
                 (ut/define-tool :name "okname" :description "x"
                   :body "(this is not valid clojure" :dirs test-dirs)))))
