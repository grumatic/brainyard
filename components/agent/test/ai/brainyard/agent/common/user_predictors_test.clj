;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.user-predictors-test
  "Tests for runtime-authored predictors (Phase 1).

   Hermetic: every assertion here is about persistence, naming, ordering and
   registration — none of it contacts a provider. The one path that would
   (`run-predictor`) is exercised through a stubbed LM in
   `calls-through-run-predictor`, so the tool `:fn` wiring is asserted rather
   than assumed.

   The load-bearing cases, in the order they matter:
     - a 9-field record round-trips IN ORDER (EDN loses map order past 8, and
       input order is prompt-cache significant)
     - a definition claiming a SOURCE predictor's id is refused
     - deleting a definition leaves programs/ artifacts alone"
  (:require [ai.brainyard.agent.common.user-predictors :as up]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.clj-llm.core.predict :as predict]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private test-root
  (str (System/getProperty "java.io.tmpdir") "/by-user-predictors-test"))

(def ^:private test-dirs
  {:project-dir (str test-root "/proj")
   :user-dir    (str test-root "/home")})

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-rf! c)))
  (.delete f))

(defn- clean! []
  (up/reset-registry!)
  ;; Drop anything a previous case registered: both registries, by id.
  (doseq [p (clj-llm/list-predictors)
          :when (clojure.string/starts-with? (:predictor/id p) "user/")]
    (up/unregister! (:predictor/id p)
                    (subs (:predictor/id p) (count "user/"))))
  (rm-rf! (io/file test-root)))

(use-fixtures :each (fn [f] (clean!) (try (f) (finally (clean!)))))

(def ^:private classify
  {:name         "classify-test"
   :instructions "Classify a changelog entry."
   :inputs       {:entry [:string {:desc "One changelog line"}]}
   :outputs      {:kind      [:string {:desc "feat | fix | chore"}]
                  :rationale [:string {:desc "One sentence"}]}})

;; ============================================================================
;; Naming
;; ============================================================================

(deftest names-are-kebab-and-user-namespaced
  (testing "a bare name and an explicit user/ id are the same predictor"
    (is (= "foo" (up/parse-name "foo")))
    (is (= "foo" (up/parse-name "user/foo")))
    (is (= "user/foo" (up/predictor-id "foo"))))
  (testing "any other namespace is REFUSED, never silently re-homed"
    (is (nil? (up/parse-name "memory/graph-extract")))
    (is (nil? (up/parse-name "coact/think-act-code"))))
  (testing "path-escaping and non-kebab shapes are refused"
    (is (nil? (up/parse-name "../escape")))
    (is (nil? (up/parse-name "user/a/b")))
    (is (nil? (up/parse-name "Foo")))
    (is (nil? (up/parse-name "9foo")))
    (is (nil? (up/parse-name "")))))

;; ============================================================================
;; Validate — offline, contacts nothing
;; ============================================================================

(deftest validate-is-a-dry-run
  (testing "a well-formed draft validates and persists nothing"
    (let [r (up/validate-draft classify)]
      (is (:valid r))
      (is (= "user/classify-test" (:id r)))
      (is (:signature-ok r))
      (is (false? (:collision r)))
      (is (= [:entry] (:input-order r))))
    (is (not (.exists (io/file (str test-root "/proj/.brainyard/predictors"))))))

  (testing "an empty :outputs map is refused — it compiles to an empty schema"
    (let [r (up/validate-draft (assoc classify :outputs {}))]
      (is (false? (:valid r)))
      (is (false? (:outputs-ok r)))))

  (testing ":cot with a declared :reasoning output is refused"
    (let [r (up/validate-draft (assoc classify :strategy "cot"
                                      :outputs {:reasoning [:string] :kind [:string]}))]
      (is (false? (:valid r)))
      (is (some #(re-find #"reasoning" %) (:errors r)))))

  (testing "a bad namespace is an error naming the id, not a silent rename"
    (let [r (up/validate-draft (assoc classify :name "memory/graph-extract"))]
      (is (false? (:valid r)))
      (is (false? (:id-ok r)))))

  (testing "past 8 inputs, :input-order is required — EDN map order is gone"
    (let [nine (into {} (for [i (range 9)] [(keyword (str "f" i)) [:string]]))
          r    (up/validate-draft (assoc classify :inputs nine))]
      (is (false? (:valid r)))
      (is (some #(re-find #"input-order" %) (:errors r))))
    (let [nine  (into {} (for [i (range 9)] [(keyword (str "f" i)) [:string]]))
          order (mapv #(keyword (str "f" %)) (range 9))
          r     (up/validate-draft (assoc classify :inputs nine :input-order order))]
      (is (:valid r))
      (is (= order (:input-order r)))))

  (testing "validate never throws on junk"
    (let [r (up/validate-draft (assoc classify :inputs "{:not readable"))]
      (is (false? (:valid r)))
      (is (seq (:errors r))))))

;; ============================================================================
;; Create / persist / register
;; ============================================================================

(deftest create-persists-and-registers
  (let [r (up/define-predictor! test-dirs classify)]
    (testing "the definition lands under .brainyard/predictors/user/"
      (is (= "user/classify-test" (:id r)))
      (is (= "user$predictor$classify-test" (:tool r)))
      (is (.exists (io/file (:persisted r))))
      (is (clojure.string/ends-with?
           (:persisted r) "/.brainyard/predictors/user/classify-test.edn")))

    (testing "it registers as a PREDICTOR, so program$* can see it"
      (let [p (clj-llm/get-predictor "user/classify-test")]
        (is (some? p))
        (is (= :predict (:strategy p)))
        (is (= #{:kind :rationale} (:output-keys (:signature p))))
        (is (some? (:output-json-schema (:signature p))))))

    (testing "and as a TOOL, so anything can call it"
      (let [td (get (tool/get-tool-defs) :user$predictor$classify-test)]
        (is (some? td))
        (is (= "user/classify-test" (get-in td [:meta :predictor-id])))
        (is (= [:map [:entry [:string {:desc "One changelog line"}]]]
               (get-in td [:meta :input-schema])))
        (testing "the description falls back to the first instruction line"
          (is (= "Classify a changelog entry." (get-in td [:meta :description]))))))

    (testing "the params file is NOT written — that is the compiler's file"
      (is (not (.exists (io/file (str test-root "/proj/.brainyard/programs"))))))))

(deftest cot-reasoning-is-a-sibling-not-an-output
  (up/define-predictor! test-dirs (assoc classify :strategy "cot"))
  (let [schema (get-in (tool/get-tool-defs)
                       [:user$predictor$classify-test :meta :output-schema])
        top    (set (map first (rest schema)))]
    (testing ":reasoning sits beside :outputs, never inside it"
      (is (contains? top :reasoning))
      (is (contains? top :outputs))
      (let [outputs-entry (first (filter #(= :outputs (first %)) (rest schema)))]
        (is (not (contains? (set (map first (rest (second outputs-entry)))) :reasoning)))))))

;; ============================================================================
;; Ordering — the EDN round-trip hazard
;; ============================================================================

(deftest nine-fields-round-trip-in-order
  (testing "input order survives a write/read cycle past the array-map boundary"
    (let [order (mapv #(keyword (str "f" %)) (range 9))
          nine  (into {} (for [k order] [k [:string {:desc (name k)}]]))
          _     (up/define-predictor! test-dirs
                  (assoc classify :inputs nine :input-order order))
          ;; The in-process registration could be right by accident (the map we
          ;; passed still had an order). Re-read from DISK, which is the path
          ;; that actually loses it.
          _     (up/unregister! "user/classify-test" "classify-test")
          _     (up/reset-registry!)
          _     (up/register-persisted! test-dirs)
          sig   (:signature (clj-llm/get-predictor "user/classify-test"))]
      (is (= order (vec (:input-order sig))))
      (is (= order (vec (keys (:inputs sig)))))
      (testing "and the tool's input schema renders in the same order"
        (is (= order (mapv first (rest (get-in (tool/get-tool-defs)
                                               [:user$predictor$classify-test
                                                :meta :input-schema])))))))))

;; ============================================================================
;; Collision
;; ============================================================================

(deftest a-source-predictor-cannot-be-redefined
  (testing "an id registered from source is refused, with the params file named"
    ;; Stand in for a `defpredictor`: registered in clj-llm but not owned here.
    (clj-llm/register-predictor!
     (clj-llm/predictor {:id "memory/graph-extract"
                         :signature (clj-llm/compile-signature
                                     "X" "x" {:a [:string]} {:b [:string]})}))
    (is (up/source-predictor? "memory/graph-extract"))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"params file"
         (up/register! {:id "memory/graph-extract" :name "graph-extract"
                        :instructions "hijack"
                        :inputs {:a [:string]} :outputs {:b [:string]}
                        :input-order [:a]})))
    (clj-llm/unregister-predictor! "memory/graph-extract"))

  (testing "re-creating one's OWN predictor is an overwrite, not a collision"
    (up/define-predictor! test-dirs classify)
    (is (up/owned? "user/classify-test"))
    (is (not (up/source-predictor? "user/classify-test")))
    (let [r (up/validate-draft (assoc classify :instructions "Revised."))]
      (is (:valid r))
      (is (true? (:collision r)))
      (is (false? (:builtin r))))
    (up/define-predictor! test-dirs (assoc classify :instructions "Revised."))
    (is (= "Revised."
           (:instructions (:signature (clj-llm/get-predictor "user/classify-test")))))))

;; ============================================================================
;; Scope
;; ============================================================================

(deftest project-scope-shadows-user-scope
  (up/define-predictor! test-dirs (assoc classify :instructions "USER") :scope :user)
  (up/define-predictor! test-dirs (assoc classify :instructions "PROJECT") :scope :project)
  (let [recs (up/read-persisted test-dirs)]
    (testing "one record wins, and it is the project one"
      (is (= 1 (count (filter #(= "classify-test" (:name %)) recs))))
      (is (= "PROJECT" (:instructions (first recs))))))
  (testing "both files still exist — shadowing is resolution, not deletion"
    (is (.exists (io/file (str test-root "/proj/.brainyard/predictors/user/classify-test.edn"))))
    (is (.exists (io/file (str test-root "/home/.brainyard/predictors/user/classify-test.edn"))))))

;; ============================================================================
;; Delete
;; ============================================================================

(deftest delete-keeps-programs-artifacts
  (up/define-predictor! test-dirs classify)
  (let [params (io/file (str test-root "/proj/.brainyard/programs/user/classify-test.edn"))
        ds     (io/file (str test-root "/proj/.brainyard/programs/user/classify-test/datasets"))]
    (.mkdirs (.getParentFile params))
    (spit params (pr-str {:instructions "compiled"}))
    (.mkdirs ds)
    (let [r (up/delete-user-predictor! test-dirs "classify-test")]
      (testing "the definition is gone from disk and both registries"
        (is (= "user/classify-test" (:deleted r)))
        (is (not (.exists (io/file (str test-root "/proj/.brainyard/predictors/user/classify-test.edn")))))
        (is (nil? (clj-llm/get-predictor "user/classify-test")))
        (is (not (contains? (tool/get-tool-defs) :user$predictor$classify-test))))
      (testing "params and datasets are KEPT and reported"
        (is (.exists params))
        (is (.exists ds))
        (is (= 2 (count (:kept r))))
        (is (some? (:note r))))))
  (testing "deleting what is not there is an error, not a silent success"
    (is (:error (up/delete-user-predictor! test-dirs "classify-test")))
    (is (:error (up/delete-user-predictor! test-dirs "memory/graph-extract")))))

;; ============================================================================
;; Registration from disk + the tool :fn
;; ============================================================================

(deftest registers-from-disk-after-a-restart
  (up/define-predictor! test-dirs classify)
  (testing "a fresh process re-registers from the .edn alone"
    (up/unregister! "user/classify-test" "classify-test")
    (up/reset-registry!)
    (is (nil? (clj-llm/get-predictor "user/classify-test")))
    (is (= ["user/classify-test"] (up/ensure-registered! test-dirs)))
    (is (some? (clj-llm/get-predictor "user/classify-test")))
    (testing "and the guard makes a second call a no-op"
      (is (nil? (up/ensure-registered! test-dirs)))))

  (testing "a corrupt .edn costs only itself"
    (spit (io/file (str test-root "/proj/.brainyard/predictors/user/broken.edn"))
          "{:name \"broken\" :id \"user/broken\" :inputs")
    (up/reset-registry!)
    (is (= ["user/classify-test"] (up/register-persisted! test-dirs)))))

(deftest calls-through-run-predictor
  (up/define-predictor! test-dirs classify)
  (testing "the tool :fn runs the predictor and returns only the useful keys"
    (with-redefs [predict/predict (fn [sig inputs & _]
                                    {:outputs {:kind "feat" :rationale (:entry inputs)}
                                     :valid? true
                                     ;; the noise the tool must NOT forward
                                     :usage {:input-tokens 1}
                                     :raw-response {:big "payload"}
                                     :signature-name (:name sig)})]
      (let [r (tool/call-tool :user$predictor$classify-test {:entry "add a thing"})]
        (is (= {:kind "feat" :rationale "add a thing"} (:outputs r)))
        (is (true? (:valid? r)))
        (is (not (contains? r :usage)))
        (is (not (contains? r :params-source)))
        (is (not (contains? r :raw-response)))))))

(deftest predictors-do-not-appear-as-user-tools
  (up/define-predictor! test-dirs classify)
  (testing "tool-agent$list must not offer a file it has no reader for"
    (let [ids (set (map :id ((requiring-resolve
                              'ai.brainyard.agent.common.user-tools/list-user-tools))))]
      (is (not (contains? ids "user$predictor$classify-test")))))
  (testing "predictor$list does list it, with its params source"
    (let [row (first (filter #(= "user/classify-test" (:id %)) (up/list-user-predictors)))]
      (is (= "user$predictor$classify-test" (:tool row)))
      (is (= "predict" (:strategy row)))
      (is (= [:entry] (:inputs row)))
      (is (= [:kind :rationale] (:outputs row))))))
