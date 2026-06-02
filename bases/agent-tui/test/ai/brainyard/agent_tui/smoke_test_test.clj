;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.smoke-test-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-llm.interface :as llm]
            [ai.brainyard.agent-tui.smoke-test :as smoke]))

(deftest smoke-success
  (testing "predict returns; smoke-test reports ok"
    (with-redefs [llm/create-lm (fn [_] {:provider :ollama :model "x"})
                  llm/predict   (fn [_ _ & _] {:outputs {:pong "ok"} :usage {}})]
      (let [r (smoke/smoke-test! :ollama "x" 1000)]
        (is (true? (:ok? r)))
        (is (nil? (:error r)))
        (is (number? (:latency-ms r)))))))

(deftest smoke-timeout
  (testing "predict that exceeds the budget is reported as timeout"
    (with-redefs [llm/create-lm (fn [_] {:provider :ollama :model "x"})
                  llm/predict   (fn [_ _ & _] (Thread/sleep 500) {})]
      (let [r (smoke/smoke-test! :ollama "x" 50)]
        (is (false? (:ok? r)))
        (is (re-find #"no response within" (:error r)))))))

(deftest smoke-throw
  (testing "predict that throws is captured as :error"
    (with-redefs [llm/create-lm (fn [_] {:provider :ollama :model "x"})
                  llm/predict   (fn [_ _ & _] (throw (ex-info "401 Unauthorized" {})))]
      (let [r (smoke/smoke-test! :ollama "x" 1000)]
        (is (false? (:ok? r)))
        (is (= "401 Unauthorized" (:error r)))))))

(deftest smoke-create-lm-fail
  (testing "create-lm that throws is captured before predict"
    (with-redefs [llm/create-lm (fn [_] (throw (ex-info "no key" {})))]
      (let [r (smoke/smoke-test! :anthropic nil 1000)]
        (is (false? (:ok? r)))
        (is (re-find #"create-lm failed" (:error r)))))))
