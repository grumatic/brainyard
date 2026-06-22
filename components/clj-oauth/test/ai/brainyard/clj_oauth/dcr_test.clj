;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.dcr-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ai.brainyard.clj-oauth.core.dcr :as dcr]))

(deftest register-client-success
  (testing "POSTs a public-client body and returns the registration"
    (let [seen (atom nil)
          post (fn [url opts] (reset! seen [url opts])
                 {:status 201 :body (json/write-str {:client_id "dyn-abc"
                                                     :token_endpoint_auth_method "none"})})
          reg  (dcr/register-client! "https://issuer/register"
                                     {:scopes ["read" "write"] :post-fn post})]
      (is (= "dyn-abc" (:client_id reg)))
      (let [[url opts] @seen
            body (json/read-str (:body opts) :key-fn keyword)]
        (is (= "https://issuer/register" url))
        (is (= "none" (:token_endpoint_auth_method body)))
        (is (= "read write" (:scope body)))
        (is (some #{"urn:ietf:params:oauth:grant-type:device_code"} (:grant_types body)))))))

(deftest register-client-failures
  (testing "non-2xx throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"registration failed"
                          (dcr/register-client! "https://i/register"
                                                {:post-fn (fn [_ _] {:status 400 :body "bad"})}))))
  (testing "missing client_id throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing client_id"
                          (dcr/register-client! "https://i/register"
                                                {:post-fn (fn [_ _] {:status 201 :body (json/write-str {:foo 1})})})))))
