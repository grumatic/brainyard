;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.discovery-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.data.json :as json]
            [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.clj-oauth.core.discovery :as discovery]))

(use-fixtures :each (fn [t] (discovery/clear-cache!) (t)))

(defn- ok [m] {:status 200 :body (json/write-str m)})
(def ^:private not-found {:status 404 :body "nope"})

(deftest oidc-document-preferred
  (testing "openid-configuration is tried first and returned"
    (let [calls (atom [])]
      (with-redefs [http/get (fn [url _]
                               (swap! calls conj url)
                               (ok {:token_endpoint "https://t"
                                    :authorization_endpoint "https://a"
                                    :device_authorization_endpoint "https://d"}))]
        (let [m (discovery/discover "https://issuer.example")]
          (is (= "https://t" (:token_endpoint m)))
          (is (true? (discovery/supports-device-flow? m)))
          (is (= "https://issuer.example/.well-known/openid-configuration"
                 (first @calls))))))))

(deftest falls-back-to-oauth-as-metadata
  (testing "when OIDC 404s, RFC 8414 oauth-authorization-server is used"
    (with-redefs [http/get (fn [url _]
                             (if (re-find #"openid-configuration" url)
                               not-found
                               (ok {:token_endpoint "https://t2"})))]
      (let [m (discovery/discover "https://mcp.example/")]
        (is (= "https://t2" (:token_endpoint m)))
        (is (false? (discovery/supports-device-flow? m)))))))

(deftest caches-per-issuer
  (testing "second discover does not re-fetch"
    (let [hits (atom 0)]
      (with-redefs [http/get (fn [_ _] (swap! hits inc) (ok {:token_endpoint "https://t"}))]
        (discovery/discover "https://issuer.example")
        (discovery/discover "https://issuer.example")
        (is (= 1 @hits))))))

(deftest throws-when-nothing-resolves
  (with-redefs [http/get (fn [_ _] not-found)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"discovery failed"
                          (discovery/discover "https://nope.example")))))

(deftest throws-when-token-endpoint-missing
  (with-redefs [http/get (fn [_ _] (ok {:authorization_endpoint "https://a"}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"token_endpoint"
                          (discovery/discover "https://broken.example")))))
