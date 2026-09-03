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
            [ai.brainyard.agent.common.user-hooks :as uh]
            [ai.brainyard.agent.common.sandbox-bindings :as sb-bind]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.clj-sandbox.interface :as sb]
            [malli.core :as m]))

(def ^:private test-dirs
  {:project-dir (str (System/getProperty "java.io.tmpdir") "/by-user-tools-test")})

(def ^:private our-ids
  [:user$tool$wc-test :user$tool$long-test :user$tool$echo-test
   :user$tool$shout-test :user$tool$bad-schema-test :user$tool$unreadable-test
   :user$tool$slow-test
   :user$tool$peer-kw-test :user$tool$peer-map-test :user$tool$peer-pos-test
   :user$tool$kwbody-test :user$tool$kwbody-peer-test :user$tool$noargs-test])

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

(deftest peer-composition-accepts-every-call-shape
  ;; Regression: `bind-peer-symbol!` used to install its own one-arity
  ;; `(fn [args] …)` for peer symbols, and it runs AFTER the palette refresh —
  ;; so it SHADOWED the generic binding every other path uses. A peer composed
  ;; the way this ns's docstrings (and tool-agent's instruction) teach it,
  ;; `(user$tool$peer :k v)`, died with "Wrong number of args (2) passed to
  ;; user-tools/bind-peer-symbol!/fn". Tool bodies were the only place in the
  ;; codebase where kwargs composition did not work — hook bodies never had the
  ;; bug because they have no equivalent function and ride the palette.
  (ut/define-tool :name "wc-test" :description "Count words."
    :input-schema [:map [:text :string]]
    :body "(fn [{:keys [text]}] {:words (count (clojure.string/split text #\"\\s+\"))})"
    :dirs test-dirs)
  (testing "kwargs — the shape the instructions teach"
    (ut/define-tool :name "peer-kw-test" :description "kwargs peer call"
      :input-schema [:map [:text :string]]
      :body "(fn [args] {:n (:words (user$tool$wc-test :text (:text args)))})"
      :dirs test-dirs)
    (is (= {:n 4} (tool/call-tool :user$tool$peer-kw-test {:text "the quick brown fox"}))))
  (testing "flat map — the shape that already worked"
    (ut/define-tool :name "peer-map-test" :description "map peer call"
      :input-schema [:map [:text :string]]
      :body "(fn [args] {:n (:words (user$tool$wc-test {:text (:text args)}))})"
      :dirs test-dirs)
    (is (= {:n 4} (tool/call-tool :user$tool$peer-map-test {:text "the quick brown fox"}))))
  (testing "positional — required inputs in declaration order"
    (ut/define-tool :name "peer-pos-test" :description "positional peer call"
      :input-schema [:map [:text :string]]
      :body "(fn [args] {:n (:words (user$tool$wc-test (:text args)))})"
      :dirs test-dirs)
    (is (= {:n 4} (tool/call-tool :user$tool$peer-pos-test {:text "the quick brown fox"})))))

(deftest a-body-may-be-written-kwargs-style
  ;; The invoke path applies the body to ONE map, and SCI implements Clojure
  ;; 1.11 trailing-map kwargs — so `(fn [& {:as args}] …)` receives exactly what
  ;; `(fn [args] …)` receives. Both shapes are documented; this pins that.
  (testing "a kwargs-style body is invoked with the args map"
    (ut/define-tool :name "kwbody-test" :description "kwargs-style body"
      :input-schema [:map [:text :string]]
      :body "(fn [& {:as args}] {:seen (:text args)})"
      :dirs test-dirs)
    (is (= {:seen "hi"} (tool/call-tool :user$tool$kwbody-test {:text "hi"}))))
  (testing "and a peer composes it with kwargs"
    (ut/define-tool :name "kwbody-peer-test" :description "peer of a kwargs-style body"
      :input-schema [:map [:text :string]]
      :body "(fn [& {:as args}] {:via (:seen (user$tool$kwbody-test :text (:text args)))})"
      :dirs test-dirs)
    (is (= {:via "hi"} (tool/call-tool :user$tool$kwbody-peer-test {:text "hi"})))))

(deftest a-tool-with-no-declared-inputs-still-receives-kwargs
  ;; `[:map]` is define-tool's DEFAULT, so this is the normal shape for any tool
  ;; authored without an explicit :input-schema. Kwargs used to be discarded in
  ;; silence — `(f :x 7)` produced `{}`, with no error anywhere — because the
  ;; kwargs branch required the leading keyword to be a declared input. A
  ;; no-entry schema has no required keys, so positional mode bound nothing.
  (ut/define-tool :name "noargs-test" :description "echoes whatever it got"
    :body "(fn [args] {:got args})"
    :dirs test-dirs)
  (let [[_ f] (#'sb-bind/bind-one-tool (tool/get-tool-defs :id :user$tool$noargs-test) nil)]
    (testing "kwargs survive"
      (is (= {:got {:x 7 :y 8}} (f :x 7 :y 8))))
    (testing "the map form is unchanged"
      (is (= {:got {:x 7 :y 8}} (f {:x 7 :y 8}))))
    (testing "an odd trailing arg is a value, not an uncaught IllegalArgumentException"
      (is (str/includes? (str (:error (f 7))) "odd number")))))

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

;; ----------------------------------------------------------------------------
;; Attribution + duplicate `:name` (issue #14 items 1–2)
;; ----------------------------------------------------------------------------

(defn- write-raw-tool!
  "Write a tool pair by hand under an arbitrary BASENAME, so the file's name and
   the `:name` inside it can differ — which is the only way two persisted tools
   can claim one `user$tool$<name>` id. define-tool always writes `<name>.edn`."
  [basename tool-name body]
  (let [dir (str (:project-dir test-dirs) "/.brainyard/tools")]
    (.mkdirs (io/file dir))
    (spit (io/file dir (str basename ".edn"))
          (pr-str {:name tool-name :description (str "from " basename)
                   :input-schema [:map]}))
    (spit (io/file dir (str basename ".clj")) body)))

(deftest registration-is-attributed-to-its-file
  (testing "define-tool records the .edn it just wrote as the entry's :source"
    (let [r (ut/define-tool :name "wc-test" :description "Count."
              :input-schema [:map] :body "(fn [_] {:words 1})"
              :dirs test-dirs)]
      (is (= (:persisted r) (:source (get @tool/!tool-defs :user$tool$wc-test))))))
  (testing "and a reload attributes it to the SAME path, so re-registering a tool
            from its own file is a same-source replace rather than a shadow"
    (ut/reset-tools-sandbox!)
    (swap! tool/!tool-defs dissoc :user$tool$wc-test)
    (ut/load-user-tools! :dirs test-dirs)
    (is (str/ends-with? (:source (get @tool/!tool-defs :user$tool$wc-test))
                        "/.brainyard/tools/wc-test.edn"))))

(deftest duplicate-name-across-files-has-a-deterministic-winner
  ;; Two files declaring one `:name` is the collision issue #14 item 1 is about.
  ;; It stays last-write-wins — a qualified id would be a lie here, since both
  ;; bodies also install into the SAME `__ut_<name>` sandbox var — but the winner
  ;; must be a property of the NAMES, not of `.listFiles` order, and the loser
  ;; must be nameable (:source) instead of silently gone.
  (write-raw-tool! "aaa" "wc-test" "(fn [_] {:words :from-aaa})")
  (write-raw-tool! "zzz" "wc-test" "(fn [_] {:words :from-zzz})")
  (testing "the last file in sorted order wins, repeatably"
    (dotimes [_ 3]
      (ut/reset-tools-sandbox!)
      (swap! tool/!tool-defs dissoc :user$tool$wc-test)
      (ut/load-user-tools! :dirs test-dirs)
      (is (str/ends-with? (:source (get @tool/!tool-defs :user$tool$wc-test))
                          "/.brainyard/tools/zzz.edn"))
      (is (= {:words :from-zzz} (tool/call-tool :user$tool$wc-test {})))))
  (testing "both files are still read — the loser is shadowed, not skipped"
    (is (= 2 (count (#'ut/read-persisted
                     (str (:project-dir test-dirs) "/.brainyard/tools")))))))

(deftest persisted-records-carry-their-file
  ;; The value ::load-user-tool-failed reports when a body fails to eval
  ;; (issue #14 item 2). The read phase already named the file; the install
  ;; phase is where SCI actually fails, and it could not.
  (ut/define-tool :name "wc-test" :description "Count."
    :input-schema [:map] :body "(fn [_] {:words 1})" :dirs test-dirs)
  (let [recs (#'ut/read-persisted (str (:project-dir test-dirs) "/.brainyard/tools"))]
    (is (= 1 (count recs)))
    (is (str/ends-with? (:file (first recs)) "/.brainyard/tools/wc-test.edn"))))

(deftest a-broken-body-is-rolled-back-and-attributable
  (testing "an uninstallable body leaves the registry clean, and its record
            carried the file the failure should be reported against"
    (write-raw-tool! "broken" "wc-test" "(fn [_] (no-such-fn-anywhere))")
    (ut/reset-tools-sandbox!)
    (swap! tool/!tool-defs dissoc :user$tool$wc-test)
    (is (empty? (ut/load-user-tools! :dirs test-dirs)))
    (is (not (contains? (tool/get-tool-defs) :user$tool$wc-test)))
    (is (str/ends-with? (:file (first (#'ut/read-persisted
                                       (str (:project-dir test-dirs) "/.brainyard/tools"))))
                        "/.brainyard/tools/broken.edn"))))

(deftest discoverable-via-list-tools
  (testing "user tools show up in list-tools with their schema"
    (ut/define-tool :name "wc-test" :description "Count words."
      :input-schema [:map [:text :string]]
      :body "(fn [{:keys [text]}] {:words 1})"
      :dirs test-dirs)
    (let [hits (tool/invoke-tool :list-tools {:pattern "user\\$tool\\$wc-test"})]
      (is (= 1 (count hits)))
      (is (= "user$tool$wc-test" (:id (first hits))))
      (is (nil? (:input-schema (first hits)))
          "schemas are opt-in now — the default listing is id/type/description"))
    (testing ":detail true still inlines the schema"
      (let [hits (tool/invoke-tool :list-tools {:pattern "user\\$tool\\$wc-test"
                                                :detail true})]
        (is (= [:map [:text :string]] (:input-schema (first hits))))))))

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

(deftest tool-agent-input-schema-vector-object-arg
  ;; :input-schema is ::vector-object-arg — a Malli schema the LLM supplies as an
  ;; EDN string (tool-calls channel, since JSON can't express keywords) OR a
  ;; native vector (code channel). Full dispatch through tool/call-tool exercises
  ;; the m/decode + m/explain gate, then coerce-input-schema + the [:map ...] check.
  (let [validate (fn [input-schema]
                   (tool/call-tool :tool-agent$validate
                                   {:body "(fn [args] (:x args))"
                                    :name "probe-tool"
                                    :input-schema input-schema}))
        rejected? (fn [r] (boolean (re-find #"Invalid tool args" (str (:error-message r)))))]
    (testing "EDN-string form \"[:map [:x :int]]\" (tool-calls channel)"
      (let [r (validate "[:map [:x :int]]")]
        (is (not (rejected? r)) "not rejected by the arg schema")
        (is (true? (:schema-ok r)) "parses to a [:map ...] schema")))
    (testing "native vector form [:map [:x :int]] (code-block channel)"
      (let [r (validate [:map [:x :int]])]
        (is (not (rejected? r)))
        (is (true? (:schema-ok r)))))
    (testing "the EDN string round-trips to a USABLE malli schema"
      (let [parsed (#'ut/coerce-input-schema "[:map [:x :int]]")]
        (is (= [:map [:x :int]] parsed))
        (is (some? (m/schema parsed)) "is a valid malli schema")
        (is (m/validate parsed {:x 1}))))
    (testing "pitfall: a JSON array of STRINGS (keywordless) is caught, not corrupted"
      ;; passes the [:vector :any] shape but is not a real Malli schema — the
      ;; downstream (= :map (first schema)) check rejects it with a clear error
      (let [r (validate ["map" ["x" "int"]])]
        (is (not (rejected? r)) "the arg schema still accepts any vector")
        (is (false? (:schema-ok r)) "but it is not a valid [:map ...] schema")
        (is (some #(re-find #"\[:map" %) (:errors r)) "with a clear error")))))

;; ---------------------------------------------------------------------------
;; Evaluation budget for a tool body
;;
;; The body is evaluated by clj-sandbox, which hard-kills it at :timeout-ms.
;; That budget used to be left implicit, so it took `eval-code`'s own 30 000 ms
;; default — the same number as :fast-eval-timeout-ms, and the inner deadline
;; starts marginally later. So the sequence was: call-tool-with-fast-eval times
;; out at ~30 000 and ADOPTS the still-running future into a background task,
;; the sandbox kills the body milliseconds afterwards, and the adopted task
;; completes with "Evaluation timed out" instead of the answer. A user tool
;; could not outlive 30 s under any configuration.
;; ---------------------------------------------------------------------------

(defn- with-config
  "Run `f` with `overrides` layered over the real config resolution. Falls
   through to the original for every other key — `tools-sandbox` resolves
   :sandbox-interop on the same path and must keep working."
  [overrides f]
  (let [orig config/get-config]
    (with-redefs [config/get-config
                  (fn ([k]   (if (contains? overrides k) (get overrides k) (orig k)))
                    ([a k] (if (contains? overrides k) (get overrides k) (orig a k))))]
      (f))))

(deftest body-budget-outranks-the-deadline-that-should-fire-first
  (testing "the backstop is raised to whichever deadline is supposed to fire
            first, so a mis-set value cannot preempt the fast-eval/detach layer"
    (with-config {:user-tool-timeout-ms 1000
                  :fast-eval-timeout-ms 90000
                  :auto-background-timeout-ms 5000}
      (fn [] (is (= 90000 (#'ut/body-timeout-ms nil))
                 "a :user-tool-timeout-ms below :fast-eval-timeout-ms is raised")))
    (with-config {:user-tool-timeout-ms 1000
                  :fast-eval-timeout-ms 200
                  :auto-background-timeout-ms 250000}
      (fn [] (is (= 250000 (#'ut/body-timeout-ms nil))
                 "…and below the auto-background horizon it is raised too")))
    (with-config {:user-tool-timeout-ms 300000
                  :fast-eval-timeout-ms 200
                  :auto-background-timeout-ms 250000}
      (fn [] (is (= 300000 (#'ut/body-timeout-ms nil))
                 "an explicitly raised budget is honoured")))))

(deftest body-budget-never-throws
  (testing "a tool can be invoked before config is resolvable (process boot),
            so budget resolution degrades to the default instead of failing
            the call"
    (with-redefs [config/get-config (fn [& _] (throw (ex-info "no config" {})))]
      (is (= ut/default-body-timeout-ms (#'ut/body-timeout-ms nil))))))

(deftest a-body-outliving-the-fast-eval-deadline-is-not-killed
  (testing "with the operative deadline set below the body's runtime, the body
            still returns its real value — the sandbox budget is a backstop,
            not the limit"
    (with-config {:fast-eval-timeout-ms 100
                  :auto-background-timeout-ms 100
                  :user-tool-timeout-ms 20000}
      (fn []
        (ut/define-tool
          :name "slow-test"
          :description "sleeps past the fast-eval deadline"
          :input-schema [:map]
          :body "(fn [args] (Thread/sleep (long 900)) :slept)"
          :dirs test-dirs)
        (let [t0 (System/currentTimeMillis)
              r  (tool/call-tool :user$tool$slow-test {})
              ms (- (System/currentTimeMillis) t0)]
          (is (= :slept r) (str "body was killed by the sandbox budget: " (pr-str r)))
          (is (>= ms 900) "and it really did run to completion"))))))

(deftest a-runaway-body-is-still-bounded
  (testing "the backstop is a backstop — it still fires, it just no longer
            fires before the layer that is supposed to decide"
    (with-config {:fast-eval-timeout-ms 0
                  :auto-background-timeout-ms 0
                  :user-tool-timeout-ms 300}
      (fn []
        (ut/define-tool
          :name "slow-test"
          :description "runs far past the backstop"
          :input-schema [:map]
          :body "(fn [args] (Thread/sleep (long 30000)) :never)"
          :dirs test-dirs)
        (let [t0 (System/currentTimeMillis)
              r  (tool/call-tool :user$tool$slow-test {})
              ms (- (System/currentTimeMillis) t0)]
          (is (str/includes? (str r) "timed out") (str "expected a timeout, got " (pr-str r)))
          (is (< ms 5000) "and it fired at the configured budget, not 30 s"))))))

(deftest the-invoke-path-passes-an-explicit-budget
  (testing "the body eval never falls back to eval-code's own 30 000 ms default.
            This is the pin: a timing test cannot tell the two apart without
            actually sleeping past 30 s, and the defect was precisely that the
            budget was INVISIBLE at the call site."
    (let [seen (atom [])
          orig sb/eval-code]
      (with-redefs [sb/eval-code
                    (fn [sbx code & {:as opts}]
                      (swap! seen conj {:code code :timeout-ms (:timeout-ms opts)})
                      (apply orig sbx code (mapcat identity opts)))]
        (ut/define-tool
          :name "slow-test"
          :description "trivial"
          :input-schema [:map]
          :body "(fn [args] :ok)"
          :dirs test-dirs)
        (is (= :ok (tool/call-tool :user$tool$slow-test {})))
        (is (every? (comp some? :timeout-ms) @seen)
            (str "some eval-code call passed no :timeout-ms: "
                 (pr-str (remove (comp some? :timeout-ms) @seen))))
        (let [invoke (last (filter #(str/includes? (:code %) "(__ut_slow-test args)") @seen))]
          (is (some? invoke) "the invoke eval was not observed")
          (is (= (#'ut/body-timeout-ms nil) (:timeout-ms invoke))
              "invoke must use the derived body budget, not a bare config read"))))))

(deftest a-hook-body-is-bounded-more-tightly-than-a-tool-body
  (testing "a hook runs inline on the fire! path with no tasking layer to adopt
            it, so the answer that is right for a tool body is wrong here"
    (is (< uh/hook-body-timeout-ms ut/default-body-timeout-ms))))
