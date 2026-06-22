;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.flow-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.clj-oauth.core.device :as device]
            [ai.brainyard.clj-oauth.core.store :as store]
            [ai.brainyard.clj-oauth.core.flow :as flow]
            [ai.brainyard.clj-oauth.interface :as oauth]))

;; Throwaway HOME so the store never touches the real ~/.brainyard.
(use-fixtures :each
  (fn [t]
    (let [home (str (System/getProperty "java.io.tmpdir") "/clj-oauth-flow-" (System/nanoTime))
          ph (System/getProperty "user.home")
          pn (System/getProperty "user.name")]
      (System/setProperty "user.home" home)
      (System/setProperty "user.name" "tester")
      (try (binding [store/*backend* :file] (t))   ; never probe a real keychain
           (finally (System/setProperty "user.home" ph)
                    (System/setProperty "user.name" pn))))))

(def ^:private device-meta
  {:device_authorization_endpoint "https://da"
   :token_endpoint "https://t"
   :authorization_endpoint "https://a"})

(def ^:private authcode-meta
  {:token_endpoint "https://t" :authorization_endpoint "https://a"})

;; ---------------------------------------------------------------------------
;; select-flow
;; ---------------------------------------------------------------------------

(deftest select-flow-rules
  (testing ":auto picks device iff a device endpoint is advertised"
    (is (= :device (flow/select-flow :auto device-meta)))
    (is (= :paste  (flow/select-flow :auto authcode-meta)))
    (is (= :paste  (flow/select-flow nil authcode-meta))))
  (testing "explicit flow wins"
    (is (= :device (flow/select-flow :device authcode-meta)))
    (is (= :paste  (flow/select-flow :paste device-meta)))
    (is (= :loopback (flow/select-flow :loopback device-meta)))))

(deftest headless?-returns-boolean
  (is (boolean? (flow/headless?))))

(deftest default-flow-override
  (testing "set-default-flow! steers :auto; an explicit flow still wins"
    (try
      (flow/set-default-flow! :paste)
      (is (= :paste  (flow/select-flow :auto device-meta)) ":auto honors the default")
      (is (= :device (flow/select-flow :device authcode-meta)) "explicit wins over default")
      (flow/set-default-flow! :auto)
      (is (= :device (flow/select-flow :auto device-meta)) ":auto cleared → auto-detect")
      (finally (flow/set-default-flow! :auto)))))

;; ---------------------------------------------------------------------------
;; login! — device path, end to end with stubbed device fns
;; ---------------------------------------------------------------------------

(deftest login!-device-persists-enriched-bundle
  (let [prompted (atom nil)]
    (with-redefs [device/start! (fn [_] {:device_code "DC" :user_code "WDJB-MJHT"
                                         :verification_uri "https://v" :expires_in 1800 :interval 5})
                  device/poll!  (fn [_] {:access_token "AT" :refresh_token "RT"
                                         :expires_at (+ (System/currentTimeMillis) 999999)})]
      (let [bundle (oauth/login! {:account-id "notion"
                                  :endpoints  device-meta
                                  :client-id  "cid"
                                  :scopes     ["read" "write"]
                                  :flow       :auto
                                  :on-user-prompt #(reset! prompted %)})]
        (testing "refresh metadata is baked into the bundle"
          (is (= "https://t" (:token_endpoint bundle)))
          (is (= "cid" (:client_id bundle)))
          (is (= "form" (:body_encoding bundle)))
          (is (= "read write" (:scope bundle))))
        (testing "persisted under the account-id"
          (is (= "AT" (:access_token (oauth/load-tokens "notion"))))
          (is (true? (oauth/authenticated? "notion"))))
        (testing "on-user-prompt got the verification details"
          (is (= "WDJB-MJHT" (:user_code @prompted)))
          (is (= "https://v" (:verification_uri @prompted))))
        (testing "access-token returns the live (non-expired) token without refresh"
          (is (= "AT" (oauth/access-token "notion")))
          (is (= {"Authorization" "Bearer AT"} (oauth/bearer-headers "notion"))))))))

(deftest login!-loopback-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"[Ll]oopback"
                        (oauth/login! {:account-id "x" :endpoints device-meta :client-id "c" :flow :loopback}))))

(deftest login!-requires-issuer-or-endpoints
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":issuer or :endpoints"
                        (oauth/login! {:account-id "x" :client-id "c"}))))

;; ---------------------------------------------------------------------------
;; access-token — refreshes from the metadata baked into the stored bundle
;; ---------------------------------------------------------------------------

(deftest access-token-refreshes-using-stored-metadata
  (let [captured (atom nil)]
    ;; Seed an expired bundle directly, carrying the form-refresh metadata.
    (store/save-tokens! "notion" {:access_token "OLD" :refresh_token "RT"
                                  :expires_at 1
                                  :token_endpoint "https://t" :client_id "cid"
                                  :body_encoding "form"})
    (with-redefs [store/refresh-access-token (fn [_ _ opts]
                                               (reset! captured opts)
                                               {:access_token "NEW"})]
      (is (= "NEW" (flow/access-token "notion")))
      (testing "refresh saw the right endpoint/encoding from the bundle"
        (is (= "https://t" (:token-endpoint @captured)))
        (is (= "cid" (:client-id @captured)))
        (is (= :form (:body-encoding @captured)))))))

(deftest access-token-unauthenticated-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"[Nn]ot authenticated"
                        (flow/access-token "never-logged-in"))))
