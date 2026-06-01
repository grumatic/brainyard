;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-sandbox.context-accessors-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.clj-sandbox.core.context-accessors :as ctx-acc]
            [ai.brainyard.clj-sandbox.core.sandbox :as sandbox]))

;; ---------------------------------------------------------------------------
;; Helper to call accessor by symbol from the accessor map
;; ---------------------------------------------------------------------------

(defn- call [accessors sym & args]
  (apply (get accessors sym) args))

;; ---------------------------------------------------------------------------
;; context-index tests
;; ---------------------------------------------------------------------------

(deftest context-index-generic-test
  (testing "returns structure for arbitrary context"
    (let [ctx  {:data [1 2 3] :metadata {:source "test"}}
          accs (ctx-acc/make-context-accessors ctx)
          idx  (call accs 'context-index)]
      (is (= [:data :metadata] (:keys idx)))
      (is (map? (:structure idx)))
      (is (= :vector (get-in idx [:structure :data :type])))
      (is (= 3 (get-in idx [:structure :data :count])))
      (is (= :map (get-in idx [:structure :metadata :type])))))

  (testing "returns structure for string context"
    (let [ctx  {:text "hello world" :count 42}
          accs (ctx-acc/make-context-accessors ctx)
          idx  (call accs 'context-index)]
      (is (contains? (set (:keys idx)) :text))
      (is (= :string (get-in idx [:structure :text :type])))
      (is (= :number (get-in idx [:structure :count :type])))))

  (testing "shows nested map structure at depth 1"
    (let [ctx  {:config {:db {:host "localhost" :port 5432}
                         :cache {:ttl 300}}}
          accs (ctx-acc/make-context-accessors ctx)
          idx  (call accs 'context-index)]
      (is (= :map (get-in idx [:structure :config :type])))
      (is (contains? (set (get-in idx [:structure :config :keys])) :db)))))

(deftest context-index-agent-shape-test
  (testing "agent-shaped context produces purely generic structure (no special fields)"
    (let [ctx  {:recalled-memory [{:content "fact1"}]
                :previous-turns []
                :restored-vars ["x"]}
          accs (ctx-acc/make-context-accessors ctx)
          idx  (call accs 'context-index)]
      ;; No legacy agent-aware fields
      (is (not (contains? idx :conversation-turns)))
      (is (not (contains? idx :has-recalled-memory)))
      (is (not (contains? idx :previous-turns-count)))
      ;; Generic structure
      (is (map? (:structure idx)))
      (is (= #{:recalled-memory :previous-turns :restored-vars}
             (set (:keys idx))))
      (is (= :vector (get-in idx [:structure :recalled-memory :type]))))))

;; ---------------------------------------------------------------------------
;; synthetic-keys (user-vars, notes)
;; ---------------------------------------------------------------------------

(deftest context-index-synthetic-keys-test
  (testing "synthetic keys appear in context-index alongside static context"
    (let [calls (atom 0)
          accs  (ctx-acc/make-context-accessors
                 {:static 1}
                 :synthetic-keys {:user-vars (fn [] (swap! calls inc) {"x" {:value "1"}})})
          idx   (call accs 'context-index)]
      (is (contains? (set (:keys idx)) :static))
      (is (contains? (set (:keys idx)) :user-vars))
      (is (= :map (get-in idx [:structure :user-vars :type])))
      (is (pos? @calls)))))

(deftest context-get-synthetic-keys-test
  (testing "context-get reads through synthetic-keys lazily"
    (let [counter (atom 0)
          accs (ctx-acc/make-context-accessors
                {:static 1}
                :synthetic-keys {:notes (fn []
                                          (swap! counter inc)
                                          {:topic-a "value-a"})})]
      ;; Static path: thunk not called
      (is (= 1 (call accs 'context-get [:static])))
      (is (zero? @counter))
      ;; Synthetic path: thunk called
      (is (= "value-a" (call accs 'context-get [:notes :topic-a])))
      (is (= 1 @counter))
      ;; Each call refreshes
      (call accs 'context-get [:notes])
      (is (= 2 @counter)))))

(deftest context-keys-synthetic-keys-test
  (testing "context-keys [] surfaces synthetic top-level keys"
    (let [accs (ctx-acc/make-context-accessors
                {:static 1}
                :synthetic-keys {:notes (fn [] {:a 1 :b 2})})]
      (is (contains? (set (call accs 'context-keys [])) :notes))
      (is (= [:a :b] (sort (call accs 'context-keys [:notes])))))))

;; ---------------------------------------------------------------------------
;; context-get tests
;; ---------------------------------------------------------------------------

(deftest context-get-test
  (testing "path traversal into nested maps"
    (let [ctx  {:a {:b {:c 42}}}
          accs (ctx-acc/make-context-accessors ctx)]
      (is (= 42 (call accs 'context-get [:a :b :c])))))

  (testing "path traversal with integer index"
    (let [ctx  {:data [{:id 1 :name "A"} {:id 2 :name "B"}]}
          accs (ctx-acc/make-context-accessors ctx)]
      (is (= {:id 1 :name "A"} (call accs 'context-get [:data 0])))
      (is (= "B" (call accs 'context-get [:data 1 :name])))))

  (testing "returns nil for missing path"
    (let [ctx  {:a 1}
          accs (ctx-acc/make-context-accessors ctx)]
      (is (nil? (call accs 'context-get [:nonexistent :path])))))

  (testing "truncates large vectors"
    (let [ctx  {:data (vec (range 100))}
          accs (ctx-acc/make-context-accessors ctx)
          result (call accs 'context-get [:data])]
      (is (map? result))
      (is (true? (:truncated result)))
      (is (= 100 (:total-count result)))
      (is (= 20 (count (:items result))))))

  (testing "truncates long strings"
    (let [long-str (apply str (repeat 5000 "x"))
          ctx  {:text long-str}
          accs (ctx-acc/make-context-accessors ctx)
          result (call accs 'context-get [:text])]
      (is (string? result))
      (is (<= (count result) 2100))  ;; 2000 + truncation marker
      (is (clojure.string/includes? result "truncated"))))

  (testing ":raw true skips truncation"
    (let [ctx  {:data (vec (range 100))}
          accs (ctx-acc/make-context-accessors ctx)
          result (call accs 'context-get [:data] :raw true)]
      (is (= 100 (count result)))))

  (testing "custom :limit"
    (let [ctx  {:data (vec (range 100))}
          accs (ctx-acc/make-context-accessors ctx)
          result (call accs 'context-get [:data] :limit 5)]
      (is (= 5 (count (:items result)))))))

;; ---------------------------------------------------------------------------
;; context-keys tests
;; ---------------------------------------------------------------------------

(deftest context-keys-test
  (testing "top-level keys of a map"
    (let [ctx  {:a 1 :b 2 :c 3}
          accs (ctx-acc/make-context-accessors ctx)]
      (is (= #{:a :b :c} (set (call accs 'context-keys []))))))

  (testing "nested map keys"
    (let [ctx  {:config {:db {:host "h" :port 5432} :cache {:ttl 300}}}
          accs (ctx-acc/make-context-accessors ctx)]
      (is (= #{:db :cache} (set (call accs 'context-keys [:config]))))))

  (testing "vector returns type and count"
    (let [ctx  {:data [1 2 3 4 5]}
          accs (ctx-acc/make-context-accessors ctx)
          result (call accs 'context-keys [:data])]
      (is (= :vector (:type result)))
      (is (= 5 (:count result)))
      (is (= [0 1 2 3 4] (:indices result)))))

  (testing "scalar returns type info"
    (let [ctx  {:name "Alice"}
          accs (ctx-acc/make-context-accessors ctx)
          result (call accs 'context-keys [:name])]
      (is (= :string (:type result)))
      (is (= 5 (:length result)))))

  (testing "nil path returns nil type"
    (let [ctx  {:a 1}
          accs (ctx-acc/make-context-accessors ctx)]
      (is (= {:type :nil} (call accs 'context-keys [:missing]))))))

;; ---------------------------------------------------------------------------
;; context-sample tests
;; ---------------------------------------------------------------------------

(deftest context-sample-test
  (testing "samples N items from vector"
    (let [ctx  {:data (vec (range 100))}
          accs (ctx-acc/make-context-accessors ctx)
          result (call accs 'context-sample [:data] 5)]
      (is (= 5 (count result)))
      (is (every? number? result))))

  (testing ":strategy :first"
    (let [ctx  {:data (vec (range 100))}
          accs (ctx-acc/make-context-accessors ctx)
          result (call accs 'context-sample [:data] 3 :strategy :first)]
      (is (= [0 1 2] result))))

  (testing ":strategy :last"
    (let [ctx  {:data (vec (range 100))}
          accs (ctx-acc/make-context-accessors ctx)
          result (call accs 'context-sample [:data] 3 :strategy :last)]
      (is (= [97 98 99] result))))

  (testing ":strategy :evenly-spaced"
    (let [ctx  {:data (vec (range 100))}
          accs (ctx-acc/make-context-accessors ctx)
          result (call accs 'context-sample [:data] 3 :strategy :evenly-spaced)]
      (is (= 3 (count result)))
      ;; First should be 0, last should be 99
      (is (= 0 (first result)))
      (is (= 99 (last result)))))

  (testing "error on non-collection"
    (let [ctx  {:name "Alice"}
          accs (ctx-acc/make-context-accessors ctx)
          result (call accs 'context-sample [:name] 3)]
      (is (contains? result :error))))

  (testing "n larger than collection returns all"
    (let [ctx  {:data [1 2 3]}
          accs (ctx-acc/make-context-accessors ctx)
          result (call accs 'context-sample [:data] 10)]
      (is (= 3 (count result))))))

;; ---------------------------------------------------------------------------
;; context-search tests
;; ---------------------------------------------------------------------------

(deftest context-search-test
  (testing "finds matches in nested string values"
    (let [ctx  {:docs [{:title "Deploy Guide" :body "How to deploy your app"}
                       {:title "API Docs" :body "REST endpoints"}]}
          accs (ctx-acc/make-context-accessors ctx)
          results (call accs 'context-search "deploy")]
      (is (pos? (count results)))
      (is (every? #(contains? % :path) results))
      (is (every? #(contains? % :match) results))
      (is (every? #(contains? % :context) results))))

  (testing "case-insensitive by default"
    (let [ctx  {:text "Hello WORLD"}
          accs (ctx-acc/make-context-accessors ctx)
          results (call accs 'context-search "hello")]
      (is (= 1 (count results)))))

  (testing "case-sensitive when requested"
    (let [ctx  {:text "Hello WORLD"}
          accs (ctx-acc/make-context-accessors ctx)
          results (call accs 'context-search "hello" :case-sensitive true)]
      (is (= 0 (count results)))))

  (testing "respects limit"
    (let [ctx  {:items (vec (for [i (range 50)] {:text (str "item " i " deploy")}))}
          accs (ctx-acc/make-context-accessors ctx)
          results (call accs 'context-search "deploy" :limit 5)]
      (is (= 5 (count results)))))

  (testing "returns empty for no matches"
    (let [ctx  {:text "hello world"}
          accs (ctx-acc/make-context-accessors ctx)
          results (call accs 'context-search "zzzznonexistent")]
      (is (empty? results)))))

;; ---------------------------------------------------------------------------
;; Sandbox integration tests
;; ---------------------------------------------------------------------------

(deftest sandbox-integration-test
  (testing "context-index works via eval-code"
    (let [sb (sandbox/create-sandbox :context {:data [1 2 3] :name "test"})
          result (sandbox/eval-code sb "(context-index)")]
      (is (nil? (:error result)))
      (is (map? (:result result)))
      (is (vector? (:keys (:result result))))))

  (testing "context-get works via eval-code"
    (let [sb (sandbox/create-sandbox :context {:items [{:id 1} {:id 2}]})
          result (sandbox/eval-code sb "(context-get [:items 0 :id])")]
      (is (nil? (:error result)))
      (is (= 1 (:result result)))))

  (testing "context-keys works via eval-code"
    (let [sb (sandbox/create-sandbox :context {:a 1 :b 2})
          result (sandbox/eval-code sb "(context-keys [])")]
      (is (nil? (:error result)))
      ;; Top-level keys include the built-in :user-vars synthetic key
      (is (clojure.set/subset? #{:a :b} (set (:result result))))
      (is (contains? (set (:result result)) :user-vars))))

  (testing "context-sample works via eval-code"
    (let [sb (sandbox/create-sandbox :context {:data (vec (range 50))})
          result (sandbox/eval-code sb "(context-sample [:data] 3 :strategy :first)")]
      (is (nil? (:error result)))
      (is (= [0 1 2] (:result result)))))

  (testing "context-search works via eval-code"
    (let [sb (sandbox/create-sandbox :context {:msg "hello deploy world"})
          result (sandbox/eval-code sb "(context-search \"deploy\")")]
      (is (nil? (:error result)))
      (is (= 1 (count (:result result))))))

  (testing "context-get accesses memory and previous-turns from agent-shaped context"
    (let [ctx {:recalled-memory [{:fact "test"}]
               :previous-turns [{:question "q1" :answer "a1"}]
               :restored-vars []}
          sb (sandbox/create-sandbox :context ctx)
          idx-result (sandbox/eval-code sb "(context-index)")
          mem-result (sandbox/eval-code sb "(context-get [:recalled-memory])")
          prev-result (sandbox/eval-code sb "(context-get [:previous-turns 0 :question])")]
      ;; Generic index, no legacy fields
      (is (= #{:recalled-memory :previous-turns :restored-vars :user-vars}
             (set (:keys (:result idx-result)))))
      ;; context-get retrieves memory + previous turns
      (is (= [{:fact "test"}] (:result mem-result)))
      (is (= "q1" (:result prev-result)))))

  (testing "user-vars synthetic key reflects (def ...) writes"
    (let [sb (sandbox/create-sandbox :context {:static 1})
          _  (sandbox/eval-code sb "(def my-var 42)")
          uv (sandbox/eval-code sb "(context-get [:user-vars])")
          var-name (sandbox/eval-code sb "(get-in (context-get [:user-vars]) [\"my-var\" :value])")
          idx (sandbox/eval-code sb "(context-index)")]
      (is (nil? (:error uv)))
      (is (map? (:result uv)))
      (is (contains? (:result uv) "my-var"))
      (is (= "42" (:result var-name)))
      ;; Index surfaces :user-vars
      (is (contains? (set (:keys (:result idx))) :user-vars))))

  (testing "context-get accesses agent-state with :info, :config, :runtime"
    (let [ctx {:conversation {:turn-count 1 :turns []}
               :agent-state {:info {:agent-id "a1" :name "TestAgent" :status :running}
                             :config {:working-dir "/tmp" :tools [:search]}
                             :runtime {:description "Live runtime state"
                                       :introspect-fn (fn [& _] {:available-keys [:question]})}}}
          sb (sandbox/create-sandbox :context ctx)
          info-result (sandbox/eval-code sb "(context-get [:agent-state :info])")
          config-result (sandbox/eval-code sb "(context-get [:agent-state :config])")
          runtime-result (sandbox/eval-code sb "(context-get [:agent-state :runtime])")]
      (is (= "a1" (:agent-id (:result info-result))))
      (is (= "TestAgent" (:name (:result info-result))))
      (is (= "/tmp" (:working-dir (:result config-result))))
      (is (= "Live runtime state" (:description (:result runtime-result)))))))
