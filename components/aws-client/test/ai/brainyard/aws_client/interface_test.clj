;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.aws-client.interface-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.aws-client.interface :as aws-client]))

(deftest static-credentials-provider-test
  (testing "Creates a static credentials provider"
    (let [provider (aws-client/static-credentials-provider
                    {:access-key-id "test-key"
                     :secret-access-key "test-secret"})]
      (is (some? provider))
      (is (satisfies? cognitect.aws.credentials/CredentialsProvider provider)))))

(deftest env-credentials-provider-test
  (testing "Creates an environment credentials provider"
    (let [provider (aws-client/env-credentials-provider)]
      (is (some? provider)))))

(deftest default-credentials-provider-test
  (testing "Creates the default credentials provider chain"
    (let [provider (aws-client/default-credentials-provider)]
      (is (some? provider)))))

(deftest credentials-valid-test
  (testing "Validates credentials correctly"
    (is (aws-client/credentials-valid?
         {:access-key-id "AKIA123"
          :secret-access-key "secret123"}))
    (is (not (aws-client/credentials-valid?
              {:access-key-id nil
               :secret-access-key "secret123"})))
    (is (not (aws-client/credentials-valid?
              {:access-key-id ""
               :secret-access-key "secret123"})))))
