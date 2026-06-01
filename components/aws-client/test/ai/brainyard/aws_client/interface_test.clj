;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

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
