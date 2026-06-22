;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.authcode-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ai.brainyard.clj-oauth.core.authcode :as authcode]))

(defn- ok [m] {:status 200 :body (json/write-str m)})

(deftest authorize-url-has-pkce-params
  (let [url (authcode/authorize-url {:authorization-endpoint "https://a/authorize"
                                     :client-id "cid" :scopes ["read" "write"]
                                     :code-challenge "CH" :state "ST"})]
    (is (str/starts-with? url "https://a/authorize?"))
    (is (str/includes? url "response_type=code"))
    (is (str/includes? url "code_challenge=CH"))
    (is (str/includes? url "code_challenge_method=S256"))
    (is (str/includes? url "client_id=cid"))
    (is (str/includes? url "state=ST"))
    (is (str/includes? url "scope=read+write"))))

(deftest parse-pasted-variants
  (let [parse @#'authcode/parse-pasted]
    (is (= {:code "ABC"} (parse "ABC")))
    (is (= {:code "ABC" :state "ST"} (parse "ABC#ST")))
    (is (= "ABC" (:code (parse "https://cb/callback?code=ABC&state=ST"))))
    (is (= "ST"  (:state (parse "https://cb/callback?code=ABC&state=ST"))))
    (is (:error (parse "   ")))))

(deftest exchange-code!-success-and-failure
  (testing "form-encoded exchange returns a bundle with :expires_at"
    (let [seen (atom nil)
          post (fn [url opts] (reset! seen [url opts])
                 (ok {:access_token "AT" :refresh_token "RT" :expires_in 3600}))
          bundle (authcode/exchange-code! {:token-endpoint "https://t" :client-id "cid"
                                           :code "CODE" :code-verifier "VER" :post-fn post})]
      (is (= "AT" (:access_token bundle)))
      (is (number? (:expires_at bundle)))
      (let [[url opts] @seen]
        (is (= "https://t" url))
        (is (str/includes? (:body opts) "grant_type=authorization_code"))
        (is (str/includes? (:body opts) "code_verifier=VER")))))
  (testing "non-2xx throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (authcode/exchange-code! {:token-endpoint "https://t" :client-id "c"
                                           :code "x" :code-verifier "v"
                                           :post-fn (fn [_ _] {:status 400 :body "bad"})})))))

(deftest paste-login!-requires-read-code
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":read-code"
                        (authcode/paste-login! {:authorization-endpoint "https://a" :token-endpoint "https://t"
                                                :client-id "c"}))))

(deftest paste-login!-happy-path
  (let [prompted (atom nil)
        bundle (authcode/paste-login!
                {:authorization-endpoint "https://a" :token-endpoint "https://t"
                 :client-id "cid" :scopes ["read"]
                 :on-user-prompt #(reset! prompted %)
                 :read-code (fn [] "CODE")
                 :post-fn (fn [_ _] (ok {:access_token "AT" :expires_in 3600}))})]
    (is (= "AT" (:access_token bundle)))
    (is (str/includes? (:authorize_uri @prompted) "https://a"))))

(deftest paste-login!-state-mismatch-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"state mismatch"
                        (authcode/paste-login!
                         {:authorization-endpoint "https://a" :token-endpoint "https://t" :client-id "c"
                          :read-code (fn [] "CODE#attacker-state")
                          :post-fn (fn [_ _] (ok {:access_token "AT"}))}))))
