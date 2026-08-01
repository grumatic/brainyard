;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.providers-error-test
  "Regression tests: bad input must fail with an actionable ex-info, never with
   an opaque NullPointerException/ClassCastException from raw String interop."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.clj-llm.core.providers :as providers]))

(deftest get-provider-from-model-is-total
  (testing "non-string / blank input returns nil instead of throwing"
    (doseq [bad [nil "" "   " :gpt-4o 42 {} []]]
      (is (nil? (providers/get-provider-from-model bad))
          (str "expected nil for " (pr-str bad)))))
  (testing "known ids still route correctly"
    (is (= :anthropic (providers/get-provider-from-model "claude-sonnet-4-6")))
    (is (= :bedrock   (providers/get-provider-from-model "amazon.nova-pro-v1:0")))
    (is (= :bedrock   (providers/get-provider-from-model "mistral.mistral-large-2407-v1:0")))
    (is (= :openai    (providers/get-provider-from-model "some-unlisted-model")))))

(deftest split-lm-str-is-total
  (is (= [nil nil] (providers/split-lm-str nil)))
  (is (= [nil nil] (providers/split-lm-str :openai/gpt-4o)))
  (is (= ["openai" "gpt-4o"] (providers/split-lm-str "openai/gpt-4o")))
  (is (= ["claude-code" "opus"] (providers/split-lm-str "claude-code:opus")))
  (is (= ["bare" nil] (providers/split-lm-str "bare"))))

(deftest create-lm-unknown-provider-throws-actionable
  (testing "unregistered provider keyword"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (providers/create-lm {:provider :nope-nope :model "whatever"})))
          d (ex-data e)]
      (is (= :unknown-provider (:error d)))
      (is (= :nope-nope (:provider d)))
      (is (contains? (set (:known-providers d)) :openai))
      (is (re-find #"unknown provider" (ex-message e)))))
  (testing "unregistered provider as a string is keywordized then rejected"
    (is (= :unknown-provider
           (:error (ex-data (try (providers/create-lm {:provider "nope-nope" :model "m"})
                                 (catch clojure.lang.ExceptionInfo e e))))))))

(deftest create-lm-invalid-model-throws-actionable
  (doseq [opts [{} {:model nil} {:model ""} {:model "  "}
                {:provider :openai :model nil} {:model :gpt-4o}]]
    (let [e (try (providers/create-lm opts) (catch clojure.lang.ExceptionInfo ex ex))]
      (is (instance? clojure.lang.ExceptionInfo e) (str "expected throw for " (pr-str opts)))
      (is (= :invalid-model (:error (ex-data e))) (str "for " (pr-str opts)))))
  (testing "a bare lm-str arg (not an options map) is rejected clearly"
    (let [e (try (providers/create-lm "openai/gpt-4o")
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :invalid-model (:error (ex-data e))))
      (is (re-find #"options map" (ex-message e))))))

(deftest create-lm-happy-paths-unchanged
  (let [lm (providers/create-lm {:model "gpt-4o-mini" :provider :openai})]
    (is (= :openai (:provider lm)))
    (is (= "https://api.openai.com/v1" (:base-url lm))))
  (let [lm (providers/create-lm {:model "claude-code/opus"})]
    (is (= :claude-code (:provider lm)))
    (is (= "opus" (:model lm))))
  (let [lm (providers/create-lm {:model "amazon.nova-lite-v1:0" :region "us-east-1"})]
    (is (= :bedrock (:provider lm)))
    (is (= "us.amazon.nova-lite-v1:0" (:model lm))))
  (testing "an unlisted-but-plausible id still falls back to :openai"
    (is (= :openai (:provider (providers/create-lm {:model "totally-unknown-model-xyz"}))))))
