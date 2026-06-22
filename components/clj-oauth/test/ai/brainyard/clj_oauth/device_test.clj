;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.device-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [ai.brainyard.clj-oauth.core.device :as device]))

(defn- resp [status m] {:status status :body (json/write-str m)})
(defn- const-post [r] (fn [_ _] r))
(defn- scripted-post
  "A post-fn that returns each response in turn from an atom-held seq."
  [responses]
  (let [a (atom responses)]
    (fn [_ _] (let [r (first @a)] (swap! a rest) r))))

;; ---------------------------------------------------------------------------
;; poll-once: classify every RFC 8628 response
;; ---------------------------------------------------------------------------

(deftest poll-once-classification
  (let [opts {:token-endpoint "https://t" :client-id "c" :device-code "d"}]
    (testing "success → [:ok bundle] with computed :expires_at"
      (let [[k bundle] (device/poll-once
                        (assoc opts :post-fn (const-post
                                              (resp 200 {:access_token "AT"
                                                         :refresh_token "RT"
                                                         :expires_in 3600}))))]
        (is (= :ok k))
        (is (= "AT" (:access_token bundle)))
        (is (number? (:expires_at bundle)))
        (is (> (:expires_at bundle) (System/currentTimeMillis)))))
    (testing "pending / slow_down / denied / expired / unknown error"
      (is (= [:pending]   (device/poll-once (assoc opts :post-fn (const-post (resp 400 {:error "authorization_pending"}))))))
      (is (= [:slow-down] (device/poll-once (assoc opts :post-fn (const-post (resp 400 {:error "slow_down"}))))))
      (is (= [:denied]    (device/poll-once (assoc opts :post-fn (const-post (resp 400 {:error "access_denied"}))))))
      (is (= [:expired]   (device/poll-once (assoc opts :post-fn (const-post (resp 400 {:error "expired_token"}))))))
      (is (= [:error "boom"] (device/poll-once (assoc opts :post-fn (const-post (resp 400 {:error "boom"})))))))))

;; ---------------------------------------------------------------------------
;; poll!: loop honoring interval + slow_down, terminal errors, timeout
;; ---------------------------------------------------------------------------

(deftest poll!-success-after-pending-and-slow-down
  (let [sleeps   (atom [])
        statuses (atom [])
        post     (scripted-post [(resp 400 {:error "authorization_pending"})
                                 (resp 400 {:error "slow_down"})
                                 (resp 200 {:access_token "AT" :expires_in 3600})])
        bundle   (device/poll! {:token-endpoint "https://t" :client-id "c" :device-code "d"
                                :interval 5 :expires-in 1800
                                :post-fn   post
                                :sleep-fn  (fn [ms] (swap! sleeps conj ms))
                                :on-status (fn [k] (swap! statuses conj k))})]
    (is (= "AT" (:access_token bundle)))
    (testing "pending slept 5s, then slow_down bumped interval to 10s"
      (is (= [5000 10000] @sleeps)))
    (is (= [:pending :slow-down :authorized] @statuses))))

(deftest poll!-terminal-errors
  (testing "access_denied and expired_token throw with :reason"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"denied"
                          (device/poll! {:token-endpoint "https://t" :client-id "c" :device-code "d"
                                         :post-fn (const-post (resp 400 {:error "access_denied"}))
                                         :sleep-fn (fn [_]) :expires-in 1800})))
    (is (= :access_denied
           (try (device/poll! {:token-endpoint "https://t" :client-id "c" :device-code "d"
                               :post-fn (const-post (resp 400 {:error "access_denied"}))
                               :sleep-fn (fn [_]) :expires-in 1800})
                (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))
    (is (= :expired_token
           (try (device/poll! {:token-endpoint "https://t" :client-id "c" :device-code "d"
                               :post-fn (const-post (resp 400 {:error "expired_token"}))
                               :sleep-fn (fn [_]) :expires-in 1800})
                (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))

(deftest poll!-times-out
  (testing "deadline crossed → :timeout, post never consulted"
    (let [posted (atom 0)
          ticks  (atom [0 1])             ; deadline calc reads 0, loop check reads 1
          now    (fn [] (let [v (first @ticks)] (swap! ticks rest) v))]
      (is (= :timeout
             (try (device/poll! {:token-endpoint "https://t" :client-id "c" :device-code "d"
                                 :poll-cap-ms 0 :expires-in 1800
                                 :post-fn  (fn [_ _] (swap! posted inc) (resp 400 {:error "authorization_pending"}))
                                 :sleep-fn (fn [_])
                                 :now-fn   now})
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))
      (is (zero? @posted) "should time out before polling"))))

;; ---------------------------------------------------------------------------
;; start!
;; ---------------------------------------------------------------------------

(deftest start!-returns-device-authorization
  (let [da (device/start! {:device-authorization-endpoint "https://da"
                           :client-id "c" :scopes ["read" "write"]
                           :post-fn (const-post (resp 200 {:device_code "DC"
                                                           :user_code "WDJB-MJHT"
                                                           :verification_uri "https://v"
                                                           :expires_in 1800 :interval 5}))})]
    (is (= "DC" (:device_code da)))
    (is (= "WDJB-MJHT" (:user_code da)))
    (is (= 5 (:interval da))))
  (testing "non-2xx throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (device/start! {:device-authorization-endpoint "https://da" :client-id "c"
                                 :post-fn (const-post (resp 400 {:error "invalid_client"}))})))))
