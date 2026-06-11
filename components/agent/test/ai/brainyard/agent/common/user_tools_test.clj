;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

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
  [:user$tool$wc-test :user$tool$long-test :user$tool$echo-test
   :user$tool$shout-test :user$tool$bad-schema-test :user$tool$unreadable-test])

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
  (testing "define-tool persists source and registers under user$tool$<name>"
    (let [r (ut/define-tool
              :name "wc-test"
              :description "Count words."
              :input-schema [:map [:text :string]]
              :body "(fn [{:keys [text]}] {:words (count (clojure.string/split text #\"\\s+\"))})"
              :dirs test-dirs)]
      (is (= :user$tool$wc-test (:id r)))
      (is (.exists (io/file (:persisted r))))
      (is (contains? (tool/get-tool-defs) :user$tool$wc-test))))
  (testing "invokes through the real tool/call-tool dispatcher"
    (is (= {:words 4} (tool/call-tool :user$tool$wc-test {:text "the quick brown fox"})))))

(deftest malli-validation
  (ut/define-tool :name "wc-test" :description "Count words."
    :input-schema [:map [:text :string]]
    :body "(fn [{:keys [text]}] {:words (count (clojure.string/split text #\"\\s+\"))})"
    :dirs test-dirs)
  (testing "missing required arg is rejected by the registry Malli path"
    (is (str/includes? (:error-message (tool/call-tool :user$tool$wc-test {})) "missing required key")))
  (testing "wrong type is rejected"
    (is (str/includes? (:error-message (tool/call-tool :user$tool$wc-test {:text 42})) "should be a string"))))

(deftest composes-another-tool
  (testing "a user tool body composes another user tool by its DIRECT symbol"
    (ut/define-tool :name "wc-test" :description "Count words."
      :input-schema [:map [:text :string]]
      :body "(fn [{:keys [text]}] {:words (count (clojure.string/split text #\"\\s+\"))})"
      :dirs test-dirs)
    (ut/define-tool :name "long-test" :description "More than 3 words?"
      :input-schema [:map [:text :string]]
      :body "(fn [{:keys [text]}] {:long? (> (:words (user$tool$wc-test {:text text})) 3)})"
      :dirs test-dirs)
    (is (= {:long? true}  (tool/call-tool :user$tool$long-test {:text "the quick brown fox jumps"})))
    (is (= {:long? false} (tool/call-tool :user$tool$long-test {:text "just three words"})))))

(deftest composes-builtin-bash
  (testing "a body calls a builtin tool by its DIRECT symbol (via :extra-bindings)"
    (ut/define-tool :name "echo-test" :description "Echo via direct bash symbol."
      :input-schema [:map]
      :body "(fn [_] {:echoed (clojure.string/trim (:output (bash {:command \"echo direct\"})))})"
      :dirs test-dirs
      :extra-bindings (sb-bind/auto-tool-bindings nil))
    (is (= {:echoed "direct"} (tool/call-tool :user$tool$echo-test {})))))

(deftest rehydrates-after-restart
  (testing "persisted source survives a simulated restart (sandbox + registry wiped)"
    (ut/define-tool :name "wc-test" :description "Count words."
      :input-schema [:map [:text :string]]
      :body "(fn [{:keys [text]}] {:words (count (clojure.string/split text #\"\\s+\"))})"
      :dirs test-dirs)
    (ut/define-tool :name "long-test" :description "More than 3 words?"
      :input-schema [:map [:text :string]]
      :body "(fn [{:keys [text]}] {:long? (> (:words (user$tool$wc-test {:text text})) 3)})"
      :dirs test-dirs)
    ;; wipe live state — closures are gone, only the .edn source remains
    (ut/reset-tools-sandbox!)
    (swap! tool/!tool-defs dissoc :user$tool$wc-test :user$tool$long-test)
    (is (not (contains? (tool/get-tool-defs) :user$tool$wc-test)))
    ;; reload from disk and confirm BOTH the tool and its composed dependency work
    (let [loaded (set (ut/load-user-tools! :dirs test-dirs))]
      (is (contains? loaded "wc-test"))
      (is (contains? loaded "long-test")))
    (is (= {:long? true} (tool/call-tool :user$tool$long-test {:text "the quick brown fox jumps"})))))

(deftest discoverable-via-list-tools
  (testing "user tools show up in list-tools with their schema"
    (ut/define-tool :name "wc-test" :description "Count words."
      :input-schema [:map [:text :string]]
      :body "(fn [{:keys [text]}] {:words 1})"
      :dirs test-dirs)
    (let [hits (tool/invoke-tool :list-tools {:pattern "user\\$tool\\$wc-test"})]
      (is (= 1 (count hits)))
      (is (= "user$tool$wc-test" (:id (first hits))))
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
    (swap! tool/!tool-defs dissoc :user$tool$wc-test)
    (is (= ["wc-test"] (ut/ensure-loaded! :dirs test-dirs)))
    (is (contains? (tool/get-tool-defs) :user$tool$wc-test))
    (is (nil? (ut/ensure-loaded! :dirs test-dirs)))))

(deftest management-list-read-delete
  (ut/define-tool :name "wc-test" :description "Count words."
    :input-schema [:map [:text :string]]
    :body "(fn [{:keys [text]}] {:words 1})"
    :dirs test-dirs)
  (testing "list-user-tools + tool-agent$list surface the registered user tool"
    (is (some #(= "user$tool$wc-test" (:id %)) (ut/list-user-tools)))
    (is (some #(= "user$tool$wc-test" (:id %)) (:tools (tool/invoke-tool :tool-agent$list {})))))
  (testing "read-user-tool returns the persisted source + schema"
    (let [r (ut/read-user-tool test-dirs "wc-test")]
      (is (= "wc-test" (:name r)))
      (is (= [:map [:text :string]] (:input-schema r)))
      (is (str/includes? (:body r) ":words"))))
  (testing "tool-agent$read / tool-agent$delete require :name (registry Malli guard)"
    (is (str/includes? (:error-message (tool/call-tool :tool-agent$read {})) "missing required key"))
    (is (str/includes? (:error-message (tool/call-tool :tool-agent$delete {})) "missing required key")))
  (testing "delete-user-tool! unregisters and removes the persisted source"
    (let [edn (io/file (str (:project-dir test-dirs) "/.brainyard/tools/wc-test.edn"))]
      (is (.exists edn))
      (is (= {:deleted "wc-test"} (ut/delete-user-tool! test-dirs "wc-test")))
      (is (not (contains? (tool/get-tool-defs) :user$tool$wc-test)))
      (is (not (.exists edn)))))
  (testing "deleting a missing tool errors"
    (is (str/includes? (:error (ut/delete-user-tool! test-dirs "nope")) "no user tool"))))

(deftest tools-create-command
  (testing "tool-agent$create is registered as a command"
    (is (contains? (tool/get-tool-defs) :tool-agent$create)))
  (testing "tool-agent$create routes through define-tool (bad name -> :error, no disk write)"
    (let [r (tool/call-tool :tool-agent$create {:name "Bad Name" :body "(fn [_] 1)"})]
      (is (str/includes? (:error r) "tool-agent$create failed")))))

(deftest tools-create-input-schema-as-edn-string
  ;; Regression: the LLM reaches tool-agent$create via a JSON tool-call, so it passes
  ;; :input-schema as an EDN STRING (JSON cannot express a keyword-headed vector).
  ;; Before the [:string]+coerce fix the field was [:any], and define-tool's
  ;; (vector? input-schema) check threw on the string — every create with a schema
  ;; failed. This exercises the full tool/call-tool (Malli) path.
  (testing "a string :input-schema is parsed and drives the new tool's validation"
    (let [r (tool/call-tool :tool-agent$create
                            {:name        "shout-test"
                             :description "Uppercase the text."
                             :input-schema "[:map [:text :string]]"
                             :body        "(fn [{:keys [text]}] {:loud (clojure.string/upper-case text)})"})]
      (is (= :user$tool$shout-test (:id r)) (str "expected success, got " (pr-str r)))
      (is (contains? (tool/get-tool-defs) :user$tool$shout-test))
      (is (= {:loud "HI"} (tool/call-tool :user$tool$shout-test {:text "hi"})))
      (is (str/includes? (:error-message (tool/call-tool :user$tool$shout-test {}))
                         "missing required key"))))
  (testing "a non-[:map] EDN string is rejected by define-tool (no registration)"
    (let [r (tool/call-tool :tool-agent$create
                            {:name "bad-schema-test" :input-schema "[:vector :string]"
                             :body "(fn [_] 1)"})]
      (is (str/includes? (:error r) "tool-agent$create failed"))
      (is (not (contains? (tool/get-tool-defs) :user$tool$bad-schema-test)))))
  (testing "unreadable EDN is reported as an error, not crashed through"
    (let [r (tool/call-tool :tool-agent$create
                            {:name "unreadable-test" :input-schema "[:map ["
                             :body "(fn [_] 1)"})]
      (is (str/includes? (:error r) "tool-agent$create failed"))
      (is (not (contains? (tool/get-tool-defs) :user$tool$unreadable-test))))))

(deftest tools-validate-dry-run
  (testing "tool-agent$validate is registered as a command"
    (is (contains? (tool/get-tool-defs) :tool-agent$validate)))
  (testing "a valid draft reports :valid true and PERSISTS/REGISTERS NOTHING"
    (let [before (tool/get-tool-defs)
          edn    (io/file (str (:project-dir test-dirs) "/.brainyard/tools/never-made.edn"))
          r      (tool/invoke-tool :tool-agent$validate
                                   {:name "never-made"
                                    :body "(fn [{:keys [text]}] {:n (count text)})"
                                    :input-schema [:map [:text :string]]})]
      (is (true? (:valid r)))
      (is (true? (:name-ok r)))
      (is (true? (:schema-ok r)))
      (is (true? (:body-ok r)))
      (is (false? (:collision r)))
      (is (empty? (:errors r)))
      ;; the load-bearing dry-run guarantee: live state is untouched
      (is (not (contains? (tool/get-tool-defs) :user$tool$never-made)))
      (is (= before (tool/get-tool-defs)))
      (is (not (.exists edn))))))

(deftest tools-validate-checks
  (testing "bad name flips :name-ok and populates :errors"
    (let [r (tool/invoke-tool :tool-agent$validate {:name "Bad Name" :body "(fn [_] 1)"})]
      (is (false? (:valid r)))
      (is (false? (:name-ok r)))
      (is (some #(str/includes? % "^[a-z]") (:errors r)))))
  (testing "non-[:map] schema flips :schema-ok"
    (let [r (tool/invoke-tool :tool-agent$validate
                              {:name "okname" :body "(fn [_] 1)"
                               :input-schema [:vector :string]})]
      (is (false? (:valid r)))
      (is (false? (:schema-ok r)))))
  (testing "uncompilable body flips :body-ok with the eval message"
    (let [r (tool/invoke-tool :tool-agent$validate
                              {:name "okname" :body "(this is not valid clojure"})]
      (is (false? (:valid r)))
      (is (false? (:body-ok r)))
      (is (some #(str/includes? % "body failed to eval") (:errors r)))))
  (testing ":name-ok is omitted when :name is not supplied"
    (let [r (tool/invoke-tool :tool-agent$validate {:body "(fn [_] 1)"})]
      (is (true? (:valid r)))
      (is (not (contains? r :name-ok))))))

(deftest tools-validate-collision
  (testing ":collision is true iff a tool with that name is already registered"
    (ut/define-tool :name "wc-test" :description "Count words."
      :input-schema [:map [:text :string]]
      :body "(fn [{:keys [text]}] {:words 1})"
      :dirs test-dirs)
    (is (true?  (:collision (tool/invoke-tool :tool-agent$validate
                                              {:name "wc-test" :body "(fn [_] 1)"}))))
    (is (false? (:collision (tool/invoke-tool :tool-agent$validate
                                              {:name "totally-fresh" :body "(fn [_] 1)"}))))))

(deftest tools-validate-sample
  (testing ":sample runs the body once and returns its result without registering"
    (let [r (tool/invoke-tool :tool-agent$validate
                              {:name "wc-sample"
                               :body "(fn [{:keys [text]}] {:words (count (clojure.string/split text #\"\\s+\"))})"
                               :input-schema [:map [:text :string]]
                               :sample {:text "the quick brown fox"}})]
      (is (true? (:valid r)))
      (is (= {:words 4} (:sample-result r)))
      (is (not (contains? (tool/get-tool-defs) :user$tool$wc-sample))))))

(deftest tools-validate-composes-palette
  (testing "a draft body composing a builtin (bash) validates true in the fork"
    ;; Guards the extra-bindings fix: the fork must carry the tool palette so a
    ;; body that composes (bash {…}) evals here exactly as under tool-agent$create.
    (let [r (tool/invoke-tool :tool-agent$validate
                              {:name "echo-validate"
                               :body "(fn [_] {:echoed (clojure.string/trim (:output (bash {:command \"echo hi\"})))})"
                               :sample {}})]
      (is (true? (:body-ok r)))
      (is (true? (:valid r)))
      (is (= {:echoed "hi"} (:sample-result r)))
      (is (not (contains? (tool/get-tool-defs) :user$tool$echo-validate))))))

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

(deftest sidecar-layout
  (testing "define-tool writes metadata .edn (no :body) + verbatim .clj sidecar"
    (let [body "(fn [{:keys [text]}]\n  {:words (count (clojure.string/split text #\"\\s+\"))})"]
      (ut/define-tool :name "wc-test" :description "Count words."
        :input-schema [:map [:text :string]]
        :body body :dirs test-dirs)
      (let [edn (io/file (str (:project-dir test-dirs) "/.brainyard/tools/wc-test.edn"))
            clj (io/file (str (:project-dir test-dirs) "/.brainyard/tools/wc-test.clj"))]
        (is (.exists edn))
        (is (.exists clj))
        (is (not (str/includes? (slurp edn) ":body")) "body lives in the .clj, not the .edn")
        (is (str/includes? (slurp clj) "clojure.string/split"))
        ;; body round-trips verbatim through read-user-tool
        (is (= body (:body (ut/read-user-tool test-dirs "wc-test"))))))))
