;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.free-llm-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.clj-llm.core.providers :as providers]))

;; The :free-llm provider resolves its base URL from FREELLM_BASE_URL (and an
;; optional FREELLM_API_KEY). System/getProperty is the documented fallback for
;; the dotenv loader, so tests drive it via JVM properties — no real env needed.

(use-fixtures :each
  (fn [t]
    (let [base (System/getProperty "FREELLM_BASE_URL")
          key  (System/getProperty "FREELLM_API_KEY")]
      (try
        (t)
        (finally
          (if base (System/setProperty "FREELLM_BASE_URL" base)
              (System/clearProperty "FREELLM_BASE_URL"))
          (if key (System/setProperty "FREELLM_API_KEY" key)
              (System/clearProperty "FREELLM_API_KEY")))))))

(deftest free-llm-routing-test
  (testing "free-llm/ prefix routes to :free-llm"
    (is (= :free-llm (providers/get-provider-from-model "free-llm/auto")))
    (is (= :free-llm (providers/get-provider-from-model "free-llm/some-model"))))
  (testing "registry entry is OpenAI-compatible with a Bearer auth header"
    (let [cfg (:free-llm providers/providers)]
      (is (= :openai (:message-format cfg)))
      (is (= "Bearer" (:auth-header cfg)))
      (is (= "FREELLM_BASE_URL" (:base-url-env cfg)))
      (is (= "FREELLM_API_KEY" (:api-key-env cfg)))
      (is (= "auto" (:default-model cfg))))))

(deftest free-llm-base-url-resolution-test
  (testing "base-url resolves from FREELLM_BASE_URL"
    (System/setProperty "FREELLM_BASE_URL" "http://localhost:9999/v1")
    (System/clearProperty "FREELLM_API_KEY")
    (let [lm (providers/create-lm {:model "auto" :provider :free-llm})]
      (is (= "http://localhost:9999/v1" (:base-url lm)))
      (is (= :free-llm (:provider lm)))
      (is (nil? (:api-key lm)) "api key is optional")))
  (testing "FREELLM_API_KEY is picked up when present"
    (System/setProperty "FREELLM_BASE_URL" "http://localhost:9999/v1")
    (System/setProperty "FREELLM_API_KEY" "sk-free-123")
    (let [lm (providers/create-lm {:model "auto" :provider :free-llm})]
      (is (= "sk-free-123" (:api-key lm)))))
  (testing "explicit :base-url arg wins over the env var"
    (System/setProperty "FREELLM_BASE_URL" "http://from-env/v1")
    (let [lm (providers/create-lm {:model "auto" :provider :free-llm
                                   :base-url "http://explicit/v1"})]
      (is (= "http://explicit/v1" (:base-url lm))))))

(deftest free-llm-initialized-test
  (testing "lm-initialized? is true when base-url is present, even without a key"
    (System/setProperty "FREELLM_BASE_URL" "http://localhost:9999/v1")
    (System/clearProperty "FREELLM_API_KEY")
    (providers/configure-default-lm!
     (providers/create-lm {:model "auto" :provider :free-llm}))
    (is (true? (boolean (providers/lm-initialized?))))))
