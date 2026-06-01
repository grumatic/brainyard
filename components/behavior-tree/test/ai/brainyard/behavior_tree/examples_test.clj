;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.behavior-tree.examples-test
  "Comprehensive BT examples testing real-world patterns."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.behavior-tree.interface :as bt]))

;; ============================================================================
;; Example 1: Guard pattern — condition before action
;; ============================================================================

(deftest guard-pattern-test
  (testing "Guard pattern: condition gates action execution"
    (let [executed (atom false)
          built (bt/build [:sequence
                           [:condition (fn [ctx] (pos? (:energy @(:st-memory ctx))))]
                           [:action (fn [ctx]
                                      (reset! executed true)
                                      (swap! (:st-memory ctx) update :energy dec)
                                      bt/success)]]
                          {:st-memory {:energy 3}})]
      (is (= bt/success (bt/run built)))
      (is (true? @executed))
      (is (= 2 (:energy @(:st-memory (:context built)))))))

  (testing "Guard pattern: condition blocks action when energy is zero"
    (let [executed (atom false)
          built (bt/build [:sequence
                           [:condition (fn [ctx] (pos? (:energy @(:st-memory ctx))))]
                           [:action (fn [_]
                                      (reset! executed true)
                                      bt/success)]]
                          {:st-memory {:energy 0}})]
      (is (= bt/failure (bt/run built)))
      (is (false? @executed)))))

;; ============================================================================
;; Example 2: Priority selector — try strategies in order
;; ============================================================================

(deftest priority-selector-test
  (testing "Priority selector: try cached result, then compute, then fallback"
    (let [strategy-used (atom nil)
          built (bt/build [:fallback
                           ;; Strategy 1: use cached result
                           [:sequence
                            [:condition (fn [ctx] (some? (:cached-result @(:st-memory ctx))))]
                            [:action (fn [ctx]
                                       (reset! strategy-used :cached)
                                       (swap! (:st-memory ctx) assoc :answer (:cached-result @(:st-memory ctx)))
                                       bt/success)]]
                           ;; Strategy 2: compute
                           [:sequence
                            [:condition (fn [ctx] (some? (:input @(:st-memory ctx))))]
                            [:action (fn [ctx]
                                       (reset! strategy-used :computed)
                                       (swap! (:st-memory ctx) assoc :answer (str "computed: " (:input @(:st-memory ctx))))
                                       bt/success)]]
                           ;; Strategy 3: default
                           [:action (fn [ctx]
                                      (reset! strategy-used :default)
                                      (swap! (:st-memory ctx) assoc :answer "no data available")
                                      bt/success)]]
                          {:st-memory {:input "hello"}})]
      ;; No cached result, so strategy 1 condition fails, strategy 2 succeeds
      (is (= bt/success (bt/run built)))
      (is (= :computed @strategy-used))
      (is (= "computed: hello" (:answer @(:st-memory (:context built)))))))

  (testing "Priority selector: cached result takes precedence"
    (let [strategy-used (atom nil)
          built (bt/build [:fallback
                           [:sequence
                            [:condition (fn [ctx] (some? (:cached-result @(:st-memory ctx))))]
                            [:action (fn [ctx]
                                       (reset! strategy-used :cached)
                                       (swap! (:st-memory ctx) assoc :answer (:cached-result @(:st-memory ctx)))
                                       bt/success)]]
                           [:action (fn [_]
                                      (reset! strategy-used :default)
                                      bt/success)]]
                          {:st-memory {:cached-result "from cache" :input "hello"}})]
      (is (= bt/success (bt/run built)))
      (is (= :cached @strategy-used))
      (is (= "from cache" (:answer @(:st-memory (:context built))))))))

;; ============================================================================
;; Example 3: Pipeline pattern — sequential data transformation
;; ============================================================================

(deftest pipeline-pattern-test
  (testing "Pipeline: fetch -> validate -> transform -> store"
    (let [built (bt/build [:sequence
                           ;; Step 1: fetch raw data
                           [:action {:id :fetch}
                            (fn [ctx]
                              (swap! (:st-memory ctx) assoc :raw-data {:name "  John Doe  " :age "30"})
                              bt/success)]
                           ;; Step 2: validate
                           [:condition {:id :validate}
                            (fn [ctx]
                              (let [data (:raw-data @(:st-memory ctx))]
                                (and (string? (:name data))
                                     (string? (:age data)))))]
                           ;; Step 3: transform
                           [:action {:id :transform}
                            (fn [ctx]
                              (let [raw (:raw-data @(:st-memory ctx))
                                    cleaned {:name (clojure.string/trim (:name raw))
                                             :age (Integer/parseInt (:age raw))}]
                                (swap! (:st-memory ctx) assoc :cleaned-data cleaned)
                                bt/success))]
                           ;; Step 4: store result
                           [:action {:id :store}
                            (fn [ctx]
                              (swap! (:st-memory ctx) assoc :stored? true)
                              bt/success)]]
                          {})]
      (is (= bt/success (bt/run built)))
      (let [mem @(:st-memory (:context built))]
        (is (= {:name "John Doe" :age 30} (:cleaned-data mem)))
        (is (true? (:stored? mem)))))))

;; ============================================================================
;; Example 4: Retry with fallback pattern
;; ============================================================================

(deftest retry-with-fallback-test
  (testing "Retry pattern: repeat action until condition met, fallback on failure"
    (let [attempt-count (atom 0)
          built (bt/build
                 [:fallback
                  ;; Primary: retry up to 3 times
                  [:repeat {:id :retry-loop :max-n 3
                            :condition-fn (fn [ctx]
                                            (= :ready (:status @(:st-memory ctx))))}
                   [:action {:id :attempt}
                    (fn [ctx]
                      (swap! attempt-count inc)
                      (swap! (:st-memory ctx) update :attempts (fnil inc 0))
                      ;; Succeed on 2nd attempt
                      (if (>= @attempt-count 2)
                        (do (swap! (:st-memory ctx) assoc :status :ready)
                            bt/success)
                        (do (swap! (:st-memory ctx) assoc :status :pending)
                            bt/success)))]]
                  ;; Fallback: use default
                  [:action {:id :fallback-default}
                   (fn [ctx]
                     (swap! (:st-memory ctx) assoc :status :fallback)
                     bt/success)]]
                 {})]
      (is (= bt/success (bt/run built)))
      (is (= :ready (:status @(:st-memory (:context built)))))
      (is (= 2 @attempt-count)))))

;; ============================================================================
;; Example 5: Parallel execution with threshold
;; ============================================================================

(deftest parallel-health-check-test
  (testing "Parallel health checks: need at least 2 of 3 services healthy"
    (let [results (atom [])
          built (bt/build [:parallel {:success-threshold 2}
                           [:action {:id :db-check}
                            (fn [_]
                              (swap! results conj :db)
                              bt/success)]
                           [:action {:id :cache-check}
                            (fn [_]
                              (swap! results conj :cache)
                              bt/success)]
                           [:action {:id :api-check}
                            (fn [_]
                              (swap! results conj :api)
                              bt/failure)]]
                          {})]
      (is (= bt/success (bt/run built)))
      ;; All three ran in parallel
      (is (= 3 (count @results)))))

  (testing "Parallel health checks: fail when too many services down"
    (let [built (bt/build [:parallel {:success-threshold 2}
                           [:action (fn [_] bt/failure)]
                           [:action (fn [_] bt/failure)]
                           [:action (fn [_] bt/success)]]
                          {})]
      (is (= bt/failure (bt/run built))))))

;; ============================================================================
;; Example 6: Nested repeat with inner sequence
;; ============================================================================

(deftest nested-repeat-sequence-test
  (testing "Repeat with inner sequence: collect items until enough gathered"
    (let [built (bt/build
                 [:repeat {:id :gather-loop :max-n 10
                           :condition-fn (fn [ctx]
                                           (>= (count (:items @(:st-memory ctx))) 3))}
                  [:sequence
                   ;; Pick up an item
                   [:action {:id :pick-item}
                    (fn [ctx]
                      (let [n (count (:items @(:st-memory ctx) []))]
                        (swap! (:st-memory ctx) update :items (fnil conj []) (str "item-" n))
                        bt/success))]
                   ;; Log progress
                   [:action {:id :log-progress}
                    (fn [ctx]
                      (swap! (:st-memory ctx) assoc :last-log
                             (str "gathered " (count (:items @(:st-memory ctx))) " items"))
                      bt/success)]]]
                 {})]
      (is (= bt/success (bt/run built)))
      (let [mem @(:st-memory (:context built))]
        (is (>= (count (:items mem)) 3))
        (is (clojure.string/includes? (:last-log mem) "gathered"))))))

;; ============================================================================
;; Example 8: st-memory-has-value? in complex trees
;; ============================================================================

(deftest st-memory-has-value-complex-test
  (testing "st-memory-has-value? with Malli schemas in decision tree"
    (let [built (bt/build
                 [:sequence
                  ;; Produce data
                  [:action (fn [ctx]
                             (swap! (:st-memory ctx) assoc
                                    :results [{:score 85} {:score 92} {:score 78}]
                                    :summary "All tests passed")
                             bt/success)]
                  ;; Validate results exist and are a vector
                  [:condition {:path [:results]
                               :schema [:vector [:map [:score :int]]]}
                   bt/st-memory-has-value?]
                  ;; Validate summary is a non-empty string
                  [:condition {:path [:summary]
                               :schema [:string {:min 1}]}
                   bt/st-memory-has-value?]
                  ;; Process validated data
                  [:action (fn [ctx]
                             (let [scores (map :score (:results @(:st-memory ctx)))
                                   avg (/ (reduce + scores) (count scores))]
                               (swap! (:st-memory ctx) assoc :avg-score avg)
                               bt/success))]]
                 {})]
      (is (= bt/success (bt/run built)))
      (is (= 85 (:avg-score @(:st-memory (:context built))))))))

;; ============================================================================
;; Example 10: Complex multi-level fallback with repeat
;; ============================================================================

(deftest complex-multi-level-test
  (testing "Multi-level tree: fallback -> repeat -> sequence with conditions"
    (let [built (bt/build
                 [:fallback {:id :top}
                  ;; Strategy A: direct answer if already known
                  [:sequence {:id :direct}
                   [:condition {:id :has-answer}
                    (fn [ctx] (some? (:answer @(:st-memory ctx))))]
                   [:action {:id :use-answer}
                    (fn [_] bt/success)]]
                  ;; Strategy B: search and synthesize (retry up to 3 times)
                  ;; The repeat child must succeed for retry to work;
                  ;; use fallback so that missing results don't abort the loop.
                  [:repeat {:id :search-retry :max-n 3
                            :condition-fn (fn [ctx] (some? (:answer @(:st-memory ctx))))}
                   [:sequence {:id :search-flow}
                    [:action {:id :search}
                     (fn [ctx]
                       (let [iter (get @(:st-memory ctx) :search-iter 0)]
                         (swap! (:st-memory ctx) assoc
                                :search-iter (inc iter)
                                :search-results (when (>= iter 1)
                                                  ["result-1" "result-2"]))
                         bt/success))]
                    [:fallback {:id :try-synthesize}
                     ;; Try to synthesize if results exist
                     [:sequence {:id :synth-if-ready}
                      [:condition {:id :has-results}
                       (fn [ctx] (seq (:search-results @(:st-memory ctx))))]
                      [:action {:id :synthesize}
                       (fn [ctx]
                         (let [results (:search-results @(:st-memory ctx))]
                           (swap! (:st-memory ctx) assoc :answer (clojure.string/join ", " results))
                           bt/success))]]
                     ;; No results yet — succeed so repeat can retry
                     [:action {:id :no-results-yet}
                      (fn [_] bt/success)]]]]
                  ;; Strategy C: give up
                  [:action {:id :give-up}
                   (fn [ctx]
                     (swap! (:st-memory ctx) assoc :answer "I don't know")
                     bt/success)]]
                 {:st-memory {:question "What is AI?"}})]
      (is (= bt/success (bt/run built)))
      ;; Strategy B should succeed after retry (search-iter 0 returns nil results, iter 1 returns results)
      (is (= "result-1, result-2" (:answer @(:st-memory (:context built)))))
      (is (= 2 (:search-iter @(:st-memory (:context built))))))))

;; ============================================================================
;; Example 11: Error handling with fallback
;; ============================================================================

(deftest error-handling-pattern-test
  (testing "Error handling: try risky action, fallback to safe default"
    (let [built (bt/build
                 [:fallback
                  [:action {:id :risky}
                   (fn [ctx]
                     ;; Simulates an action that might fail
                     (let [data (:input @(:st-memory ctx))]
                       (if (and data (string? data))
                         (do (swap! (:st-memory ctx) assoc :result (clojure.string/upper-case data))
                             bt/success)
                         bt/failure)))]
                  [:action {:id :safe-default}
                   (fn [ctx]
                     (swap! (:st-memory ctx) assoc :result "DEFAULT" :used-fallback true)
                     bt/success)]]
                 {:st-memory {:input 42}})]  ;; non-string input triggers failure
      (is (= bt/success (bt/run built)))
      (is (= "DEFAULT" (:result @(:st-memory (:context built)))))
      (is (true? (:used-fallback @(:st-memory (:context built))))))))

;; ============================================================================
;; Example 12: Accumulator pattern with repeat
;; ============================================================================

(deftest accumulator-pattern-test
  (testing "Accumulator: repeat collects pages of data"
    (let [pages-data [["a" "b"] ["c" "d"] ["e"]]
          built (bt/build
                 [:repeat {:id :paginate :max-n 10
                           :condition-fn (fn [ctx]
                                           (let [page (:current-page @(:st-memory ctx) 0)]
                                             (>= page (count pages-data))))}
                  [:action {:id :fetch-page}
                   (fn [ctx]
                     (let [page (:current-page @(:st-memory ctx) 0)]
                       (if (< page (count pages-data))
                         (do (swap! (:st-memory ctx) update :all-items
                                    (fnil into []) (nth pages-data page))
                             (swap! (:st-memory ctx) update :current-page (fnil inc 0))
                             bt/success)
                         bt/success)))]]
                 {})]
      (is (= bt/success (bt/run built)))
      (is (= ["a" "b" "c" "d" "e"] (:all-items @(:st-memory (:context built))))))))

;; ============================================================================
;; Example 13: Empty sequence and fallback edge cases
;; ============================================================================

(deftest empty-children-test
  (testing "Empty sequence returns success"
    (let [built (bt/build [:sequence] {})]
      (is (= bt/success (bt/run built)))))

  (testing "Empty fallback returns failure"
    (let [built (bt/build [:fallback] {})]
      (is (= bt/failure (bt/run built))))))

;; ============================================================================
;; Example 14: Deeply nested tree (5 levels)
;; ============================================================================

(deftest deep-nesting-test
  (testing "5-level deep nesting works correctly"
    (let [built (bt/build
                 [:sequence {:id :L1}
                  [:fallback {:id :L2}
                   [:sequence {:id :L3}
                    [:fallback {:id :L4}
                     [:sequence {:id :L5}
                      [:action (fn [ctx]
                                 (swap! (:st-memory ctx) assoc :depth-reached 5)
                                 bt/success)]]]]]]
                 {})]
      (is (= bt/success (bt/run built)))
      (is (= 5 (:depth-reached @(:st-memory (:context built))))))))

;; ============================================================================
;; Example 15: Repeat with child failure stops immediately
;; ============================================================================

(deftest repeat-immediate-failure-test
  (testing "Repeat stops immediately when child fails"
    (let [counter (atom 0)
          built (bt/build [:repeat {:id :fail-loop :max-n 100}
                           [:sequence
                            [:action (fn [_]
                                       (swap! counter inc)
                                       bt/success)]
                            [:action (fn [_] bt/failure)]]]
                          {})]
      (is (= bt/failure (bt/run built)))
      ;; Only ran once since the sequence fails on first iteration
      (is (= 1 @counter)))))

;; ============================================================================
;; Example 16: Parallel with running status
;; ============================================================================

(deftest parallel-running-test
  (testing "Parallel returns running when results are mixed with running"
    (let [built (bt/build [:parallel {:success-threshold 2}
                           [:action (fn [_] bt/success)]
                           [:action (fn [_] bt/running)]]
                          {})]
      (is (= bt/running (bt/run built))))))

;; ============================================================================
;; Example 17: DSPy mock — full pipeline with condition + dspy + post-processing
;; ============================================================================

(deftest dspy-pipeline-test
  (testing "DSPy pipeline: validate input -> predict -> validate output -> post-process"
    (let [dspy-op-key :mock-pipeline-predict]
      (try
        ;; Install mock predict
        (defmethod ai.brainyard.behavior-tree.core.dspy-action/execute-dspy-operation dspy-op-key
          [_ _signature _context inputs]
          {:outputs {:answer (str "The answer to '" (get-in inputs [:inputs :question]) "' is 42.")}})

        (let [sig {:name "PipelineQA"
                   :instructions "Answer questions"
                   :input-keys #{:question}
                   :output-keys #{:answer}}
              built (bt/build
                     [:sequence
                      ;; Step 1: Validate input exists
                      [:condition {:path [:question] :schema [:string {:min 1}]}
                       bt/st-memory-has-value?]
                      ;; Step 2: Run DSPy predict
                      [:action {:id :qa-predict :signature sig :operation dspy-op-key}
                       bt/dspy]
                      ;; Step 3: Validate output
                      [:condition {:path [:answer] :schema [:string {:min 1}]}
                       bt/st-memory-has-value?]
                      ;; Step 4: Post-process
                      [:action {:id :format-answer}
                       (fn [ctx]
                         (let [answer (:answer @(:st-memory ctx))]
                           (swap! (:st-memory ctx) assoc :formatted-answer
                                  (clojure.string/upper-case answer))
                           bt/success))]]
                     {:st-memory {:question "What is the meaning of life?"}})]
          (is (= bt/success (bt/run built)))
          (let [mem @(:st-memory (:context built))]
            (is (clojure.string/includes? (:answer mem) "42"))
            (is (clojure.string/includes? (:formatted-answer mem) "42"))
            (is (= (clojure.string/upper-case (:answer mem)) (:formatted-answer mem)))))
        (finally
          (remove-method ai.brainyard.behavior-tree.core.dspy-action/execute-dspy-operation dspy-op-key))))))

;; ============================================================================
;; Example 18: DSPy chain-of-thought with reasoning stored
;; ============================================================================

(deftest dspy-cot-reasoning-stored-test
  (testing "Chain-of-thought stores :last-reasoning in st-memory"
    (let [dspy-op-key :mock-cot-reasoning]
      (try
        (defmethod ai.brainyard.behavior-tree.core.dspy-action/execute-dspy-operation dspy-op-key
          [_ _signature _context _inputs]
          {:outputs {:classification "positive"}
           :reasoning "The text contains words like 'great' and 'amazing', indicating positive sentiment."})

        (let [sig {:name "SentimentAnalysis"
                   :instructions "Classify sentiment"
                   :input-keys #{:text}
                   :output-keys #{:classification}}
              built (bt/build
                     [:action {:id :classify :signature sig :operation dspy-op-key}
                      bt/dspy]
                     {:st-memory {:text "This product is great and amazing!"}})]
          (is (= bt/success (bt/run built)))
          (let [mem @(:st-memory (:context built))]
            (is (= "positive" (:classification mem)))
            (is (clojure.string/includes? (:last-reasoning mem) "positive sentiment"))))
        (finally
          (remove-method ai.brainyard.behavior-tree.core.dspy-action/execute-dspy-operation dspy-op-key))))))

