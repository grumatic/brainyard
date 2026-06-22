;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.device-e2e-test
  "End-to-end device flow against the Brainyard test provider — REAL HTTP through
   discovery → device authorization → token poll → store → refresh, plus bearer
   enforcement on the OAuth-gated MCP endpoint. Closes the one gap the unit
   tests (injected post-fn) couldn't: a real-HTTP device-poll round-trip."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.clj-oauth.interface :as oauth]
            [ai.brainyard.clj-oauth.core.store :as store]
            [ai.brainyard.clj-oauth.core.discovery :as discovery]
            [ai.brainyard.clj-oauth.test-server :as ts]))

(use-fixtures :each
  (fn [t]
    (let [home (str (System/getProperty "java.io.tmpdir") "/clj-oauth-e2e-" (System/nanoTime))
          ph (System/getProperty "user.home") pn (System/getProperty "user.name")]
      (System/setProperty "user.home" home)
      (System/setProperty "user.name" "tester")
      (discovery/clear-cache!)
      (try (binding [store/*backend* :file] (t))   ; never touch a real keychain
           (finally (System/setProperty "user.home" ph)
                    (System/setProperty "user.name" pn))))))

(defn- mcp-init [base-url headers]
  (http/post (str base-url "/mcp")
             {:headers headers
              :body (json/write-str {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})
              :as :string :throw-exceptions false}))

(deftest device-flow-end-to-end
  (let [{:keys [base-url approve! stop!]} (ts/start! 0)]
    (try
      (testing "discovery → device → poll → token, with the user approving inline"
        (let [bundle (oauth/login!
                      {:account-id "brainyard" :issuer base-url
                       :client-id "brainyard-test" :scopes ["read"] :flow :device
                       ;; the prompt fires before polling — approve the code as
                       ;; if the user did, so the first poll succeeds.
                       :on-user-prompt (fn [{:keys [user_code verification_uri]}]
                                         (is (string? user_code))
                                         (is (= (str base-url "/") verification_uri))
                                         (approve! user_code))})]
          (is (string? (:access_token bundle)))
          (is (string? (:refresh_token bundle)))
          (is (= "form" (:body_encoding bundle)) "baked refresh metadata")
          (is (= base-url (subs (:token_endpoint bundle) 0 (count base-url))))))

      (testing "persisted + access-token reads back"
        (is (true? (oauth/authenticated? "brainyard")))
        (is (string? (oauth/access-token "brainyard"))))

      (testing "the bearer is accepted by the MCP endpoint; absence is 401"
        (is (= 200 (:status (mcp-init base-url (oauth/bearer-headers "brainyard")))))
        (is (= 401 (:status (mcp-init base-url {})))))

      (testing "forced refresh rotates the token and the new bearer still works"
        (let [old (oauth/access-token "brainyard")
              new (oauth/refresh! "brainyard")]
          (is (not= old new))
          (is (= 200 (:status (mcp-init base-url {"Authorization" (str "Bearer " new)}))))
          (is (= 401 (:status (mcp-init base-url {"Authorization" "Bearer stale-token"}))))))
      (finally (stop!)))))

(deftest dcr-registers-when-no-client-id
  (testing "no :client-id → dynamic registration (RFC 7591), cached by issuer"
    (let [{:keys [base-url approve! stop!]} (ts/start! 0)]
      (try
        (let [bundle (oauth/login!
                      {:account-id "brainyard-dcr" :issuer base-url :scopes ["read"] :flow :device
                       :on-user-prompt (fn [{:keys [user_code]}] (approve! user_code))})]
          (is (string? (:access_token bundle)))
          (is (str/starts-with? (:client_id bundle) "dyn-") "client_id came from DCR"))
        (testing "the registered client_id is cached under client@<issuer>"
          (let [cached (oauth/load-tokens (str "client@" base-url))]
            (is (str/starts-with? (:client_id cached) "dyn-"))))
        (finally (stop!))))))

(deftest paste-flow-end-to-end
  (testing "authorization-code paste flow: authorize URL → user copies code → tokens"
    (let [{:keys [base-url stop!]} (ts/start! 0)
          authorize-uri (atom nil)]
      (try
        (let [bundle (oauth/login!
                      {:account-id "brainyard-paste" :issuer base-url :client-id "x"
                       :scopes ["read"] :flow :paste
                       :on-user-prompt (fn [{:keys [authorize_uri]}] (reset! authorize-uri authorize_uri))
                       ;; simulate the user: open the authorize URL, copy the shown code
                       :read-code (fn []
                                    (let [html (:body (http/get @authorize-uri {:as :string :throw-exceptions false}))]
                                      (second (re-find #"<pre[^>]*>([^<]+)</pre>" html))))})]
          (is (string? (:access_token bundle)))
          (is (str/starts-with? @authorize-uri (str base-url "/authorize")))
          (is (= 200 (:status (mcp-init base-url (oauth/bearer-headers "brainyard-paste"))))))
        (finally (stop!))))))

(deftest device-flow-poll-waits-for-approval
  (testing "token endpoint returns authorization_pending until approved"
    (let [{:keys [base-url approve! stop!]} (ts/start! 0)]
      (try
        (let [meta (discovery/discover base-url)
              da   (http/post (:device_authorization_endpoint meta)
                              {:body "client_id=x" :content-type "application/x-www-form-urlencoded"
                               :as :string :throw-exceptions false})
              {:keys [device_code user_code]} (json/read-str (:body da) :key-fn keyword)
              poll (fn [] (http/post (:token_endpoint meta)
                                     {:body (str "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                                                 "&device_code=" device_code "&client_id=x")
                                      :content-type "application/x-www-form-urlencoded"
                                      :as :string :throw-exceptions false}))]
          (is (= "authorization_pending" (:error (json/read-str (:body (poll)) :key-fn keyword)))
              "pending before approval")
          (approve! user_code)
          (is (:access_token (json/read-str (:body (poll)) :key-fn keyword))
              "succeeds after approval"))
        (finally (stop!))))))
