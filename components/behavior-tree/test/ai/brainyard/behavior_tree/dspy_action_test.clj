;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.behavior-tree.dspy-action-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.behavior-tree.interface :as bt]
            [ai.brainyard.behavior-tree.core.dspy-action :as dspy-action]
            [ai.brainyard.clj-llm.interface :as clj-llm]))

;; ============================================================================
;; extract-signature-metadata tests
;; ============================================================================

(deftest extract-signature-metadata-from-map-test
  (testing "Extract metadata from compiled signature map"
    (let [sig {:name "TestSig"
               :instructions "Test"
               :input-keys #{:question}
               :output-keys #{:answer}}
          meta (dspy-action/extract-signature-metadata sig)]
      (is (= [:question] (:input-keys meta)))
      (is (= [:answer] (:output-keys meta))))))

;; ============================================================================
;; execute-dspy-operation mock tests
;; ============================================================================

(deftest dspy-action-with-mock-predict-test
  (testing "DSPy action with mocked predict operation"
    ;; Install a mock predict method
    (let [original-method (get-method dspy-action/execute-dspy-operation :predict)]
      (try
        (defmethod dspy-action/execute-dspy-operation :mock-predict
          [_ _signature _context inputs]
          {:outputs {:answer (str "Answer to: " (get-in inputs [:inputs :question]))}})

        (let [built (bt/build [:action {:id :test-qa
                                        :signature {:name "TestQA"
                                                    :instructions "Test"
                                                    :input-keys #{:question}
                                                    :output-keys #{:answer}}
                                        :operation :mock-predict}
                               bt/dspy]
                              {:st-memory {:question "What is 2+2?"}})]
          (is (= bt/success (bt/run built)))
          (is (= "Answer to: What is 2+2?"
                 (:answer @(:st-memory (:context built))))))
        (finally
          (remove-method dspy-action/execute-dspy-operation :mock-predict))))))

(deftest dspy-action-with-mock-chain-of-thought-test
  (testing "DSPy action with mocked chain-of-thought operation stores reasoning"
    (try
      (defmethod dspy-action/execute-dspy-operation :mock-cot
        [_ _signature _context inputs]
        {:outputs {:answer "4"}
         :reasoning "2+2=4 because addition."})

      (let [built (bt/build [:action {:id :test-cot
                                      :signature {:name "TestCOT"
                                                  :instructions "Test"
                                                  :input-keys #{:question}
                                                  :output-keys #{:answer}}
                                      :operation :mock-cot}
                             bt/dspy]
                            {:st-memory {:question "What is 2+2?"}})]
        (is (= bt/success (bt/run built)))
        (is (= "4" (:answer @(:st-memory (:context built)))))
        (is (= "2+2=4 because addition." (:last-reasoning @(:st-memory (:context built))))))
      (finally
        (remove-method dspy-action/execute-dspy-operation :mock-cot)))))

(deftest dspy-action-missing-inputs-test
  (testing "DSPy action returns failure when inputs are missing"
    (try
      (defmethod dspy-action/execute-dspy-operation :mock-predict2
        [_ _signature _context _inputs]
        {:outputs {:answer "should not reach here"}})

      (let [built (bt/build [:action {:id :test-missing
                                      :signature {:name "TestMissing"
                                                  :instructions "Test"
                                                  :input-keys #{:nonexistent-key}
                                                  :output-keys #{:answer}}
                                      :operation :mock-predict2}
                             bt/dspy]
                            {:st-memory {}})]
        (is (= bt/failure (bt/run built))))
      (finally
        (remove-method dspy-action/execute-dspy-operation :mock-predict2)))))

(deftest dspy-action-exception-test
  (testing "DSPy action returns failure on exception"
    (try
      (defmethod dspy-action/execute-dspy-operation :mock-error
        [_ _signature _context _inputs]
        (throw (ex-info "Mock error" {})))

      (let [built (bt/build [:action {:id :test-error
                                      :signature {:name "TestError"
                                                  :instructions "Test"
                                                  :input-keys #{:question}
                                                  :output-keys #{:answer}}
                                      :operation :mock-error}
                             bt/dspy]
                            {:st-memory {:question "test"}})]
        (is (= bt/failure (bt/run built))))
      (finally
        (remove-method dspy-action/execute-dspy-operation :mock-error)))))

;; ============================================================================
;; Stable-keys filtering tests
;; ============================================================================

(deftest dspy-action-stable-keys-default-test
  (testing "With no :stable-keys in opts, default-stable-keys is empty — nothing filtered"
    (let [captured-inputs (atom nil)]
      (try
        (defmethod dspy-action/execute-dspy-operation :mock-stable
          [_ _signature _context inputs]
          (reset! captured-inputs inputs)
          {:outputs {:answer "ok"}})

        (let [built (bt/build [:action {:id :test-stable
                                        :signature {:name "TestStable"
                                                    :instructions "Test"
                                                    :input-keys #{:question :instruction :tools}
                                                    :output-keys #{:answer}}
                                        :operation :mock-stable}
                               bt/dspy]
                              {:st-memory {:question "Q"
                                           :instruction "I"
                                           :tools "T"}})]
          (is (= bt/success (bt/run built)))
          ;; All inputs present — nothing filtered when defaults are empty
          (is (= {:question "Q" :instruction "I" :tools "T"}
                 (:inputs @captured-inputs)))
          ;; :stable-keys normalizes to an empty vector by default
          (is (= [] (:stable-keys @captured-inputs))))
        (finally
          (remove-method dspy-action/execute-dspy-operation :mock-stable))))))

(deftest dspy-action-custom-stable-keys-test
  (testing "Custom :stable-keys in opts are unioned with defaults (which are empty)"
    (let [captured-inputs (atom nil)]
      (try
        (defmethod dspy-action/execute-dspy-operation :mock-custom-stable
          [_ _signature _context inputs]
          (reset! captured-inputs inputs)
          {:outputs {:answer "ok"}})

        (let [built (bt/build [:action {:id :test-custom-stable
                                        :signature {:name "TestCustomStable"
                                                    :instructions "Test"
                                                    :input-keys #{:question :instruction :custom-context}
                                                    :output-keys #{:answer}}
                                        :operation :mock-custom-stable
                                        :stable-keys #{:custom-context}}
                               bt/dspy]
                              {:st-memory {:question "Q"
                                           :instruction "I"
                                           :custom-context "C"}})]
          (is (= bt/success (bt/run built)))
          ;; :custom-context filtered from inputs; :question and :instruction remain
          (is (= {:question "Q" :instruction "I"}
                 (:inputs @captured-inputs)))
          ;; :stable-keys normalizes to an ordered vector (legacy set input
          ;; → alphabetical order)
          (is (= [:custom-context]
                 (:stable-keys @captured-inputs))))
        (finally
          (remove-method dspy-action/execute-dspy-operation :mock-custom-stable))))))

(deftest dspy-action-all-inputs-stable-test
  (testing "When all inputs are stable keys, action still succeeds (all-inputs non-empty)"
    (try
      (defmethod dspy-action/execute-dspy-operation :mock-all-stable
        [_ _signature _context inputs]
        {:outputs {:answer "from stable only"}})

      (let [built (bt/build [:action {:id :test-all-stable
                                      :signature {:name "TestAllStable"
                                                  :instructions "Test"
                                                  :input-keys #{:instruction :tools}
                                                  :output-keys #{:answer}}
                                      :operation :mock-all-stable}
                             bt/dspy]
                            {:st-memory {:instruction "I" :tools "T"}})]
        (is (= bt/success (bt/run built)))
        (is (= "from stable only" (:answer @(:st-memory (:context built))))))
      (finally
        (remove-method dspy-action/execute-dspy-operation :mock-all-stable)))))

;; ============================================================================
;; resolve-lm-config normalization
;; ============================================================================
;; Raw `{:provider :model}` maps passed via :config-extra used to flow straight
;; into clj-llm/chat-completion, where the `case (:message-format lm-config)`
;; dispatch fell through to OpenAI (MalformedURLException on /chat/completions
;; against nil base-url). resolve-lm-config now upgrades raw configs through
;; create-lm so dispatch routes correctly.

(def ^:private resolve-lm-config
  @#'dspy-action/resolve-lm-config)

(deftest resolve-lm-config-normalizes-raw-map-test
  (testing "Raw {:provider :model} from action opts is upgraded via create-lm"
    (let [ctx {:opts {:lm-config {:provider :openai :model "gpt-4o"}}}
          resolved (resolve-lm-config ctx)]
      (is (= :openai (:provider resolved)))
      (is (= "gpt-4o" (:model resolved)))
      (is (= :openai (:message-format resolved))
          ":message-format must be populated so chat-completion dispatch routes correctly")
      (is (some? (:base-url resolved))
          ":base-url must be populated for HTTP providers")))

  (testing "Raw claude-code config gets :message-format :claude-code"
    (let [ctx {:opts {:lm-config {:provider :claude-code :model "opus"}}}
          resolved (resolve-lm-config ctx)]
      (is (= :claude-code (:message-format resolved))
          "without normalization, dispatch falls through to the OpenAI default and tries to POST nil/chat/completions")))

  (testing "Already-normalized LM (from create-lm) is passed through unchanged"
    (let [lm (clj-llm/create-lm {:provider :openai :model "gpt-4o"})
          ctx {:opts {:lm-config lm}}
          resolved (resolve-lm-config ctx)]
      (is (identical? lm resolved)
          "normalized LMs must not be re-run through create-lm")))

  (testing "nil resolves to nil (clj-llm falls back to global default)"
    (is (nil? (resolve-lm-config {:opts {}})))
    (is (nil? (resolve-lm-config {})))))

;; ============================================================================
;; build-system-prompt zone ordering (prompt-cache Phase 1)
;; ============================================================================

(def ^:private build-system-prompt
  @#'dspy-action/build-system-prompt)

(def ^:private normalize-stable-keys
  @#'dspy-action/normalize-stable-keys)

(deftest normalize-stable-keys-test
  (testing "vector input → declared order preserved, duplicates dropped"
    (is (= [:b :a :c] (normalize-stable-keys [:b :a :c :b]))))
  (testing "set input (legacy) → alphabetical order"
    (is (= [:a :b :c] (normalize-stable-keys #{:c :a :b}))))
  (testing "nil → empty vector"
    (is (= [] (normalize-stable-keys nil)))))

(deftest build-system-prompt-declared-order-test
  (testing "zones and text render in the DECLARED key order, not alphabetical"
    (let [state {:zebra-context "z-text" :alpha-context "a-text"}
          {:keys [text zones]} (build-system-prompt state [:zebra-context :alpha-context])]
      (is (= [:zebra-context :alpha-context] (mapv :key zones)))
      (is (= (str "## zebra-context\nz-text\n\n"
                  "## alpha-context\na-text")
             text)))))

(deftest build-system-prompt-skips-absent-keys-test
  (testing "keys missing from state produce no zone and no text gap"
    (let [state {:present "here"}
          {:keys [text zones]} (build-system-prompt state [:missing :present])]
      (is (= [:present] (mapv :key zones)))
      (is (= "## present\nhere" text)))))
