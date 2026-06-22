;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.core.device
  "OAuth 2.0 Device Authorization Grant (RFC 8628).

   `start!` requests a device + user code; `poll!` then polls the token
   endpoint until the user authorizes on another device. No redirect_uri, no
   listening socket, no local browser — the whole point: it works headless in
   the playground container, over SSH, and in CI.

   Token-endpoint error responses arrive as HTTP 400 with a JSON `error`, so we
   poll with `:throw-exceptions false` and branch on the body:
     authorization_pending → keep polling at `interval`
     slow_down             → `interval += 5`, keep polling
     access_denied         → terminal (user said no)
     expired_token         → terminal (restart the flow)
     2xx + access_token    → done

   `post-fn` and `sleep-fn` are injectable so the state machine is unit-testable
   without HTTP or real sleeps. See docs/design/oauth.md §3."
  (:require [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.clj-oauth.core.encode :as encode]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(def ^:private device-grant-type "urn:ietf:params:oauth:grant-type:device_code")
(def ^:private form-ct "application/x-www-form-urlencoded")
(def ^:private default-interval 5)
(def ^:private slow-down-bump 5)

(defn ^:private parse-body [resp]
  (try (json/read-str (:body resp) :key-fn keyword)
       (catch Exception _ {})))

(defn start!
  "POST the device authorization endpoint. Returns the device-authorization
   response: `{:device_code :user_code :verification_uri
   :verification_uri_complete? :expires_in :interval}`. `:post-fn` defaults to
   `http/post`."
  [{:keys [device-authorization-endpoint client-id scopes post-fn]
    :or   {post-fn http/post scopes []}}]
  (let [resp (post-fn device-authorization-endpoint
                      {:body         (encode/form-encode
                                      (cond-> {"client_id" client-id}
                                        (seq scopes) (assoc "scope" (str/join " " scopes))))
                       :content-type form-ct
                       :as :string
                       :throw-exceptions false})]
    (when-not (<= 200 (:status resp) 299)
      (throw (ex-info "Device authorization request failed"
                      {:status (:status resp) :body (:body resp)})))
    (parse-body resp)))

(defn ^:private bundle-from
  "Normalize a successful token response into a stored bundle (adds :expires_at)."
  [body]
  (let [now (System/currentTimeMillis)]
    (assoc body
           :expires_at  (+ now (* (:expires_in body 3600) 1000))
           :obtained_at now)))

(defn poll-once
  "One token-endpoint poll. Returns one of:
     [:ok bundle] [:pending] [:slow-down] [:denied] [:expired] [:error msg].
   Pure but for the injected `post-fn`."
  [{:keys [token-endpoint client-id device-code post-fn]
    :or   {post-fn http/post}}]
  (let [resp   (post-fn token-endpoint
                        {:body         (encode/form-encode
                                        {"grant_type"  device-grant-type
                                         "device_code" device-code
                                         "client_id"   client-id})
                         :content-type form-ct
                         :as :string
                         :throw-exceptions false})
        status (:status resp)
        body   (parse-body resp)]
    (cond
      (<= 200 status 299) [:ok (bundle-from body)]
      :else (case (:error body)
              "authorization_pending" [:pending]
              "slow_down"             [:slow-down]
              "access_denied"         [:denied]
              "expired_token"         [:expired]
              [:error (or (:error_description body) (:error body) (str "HTTP " status))]))))

(defn poll!
  "Poll `token-endpoint` until terminal, honoring `interval`/`slow_down` and a
   hard `:poll-cap-ms`. Returns the token bundle on success; throws ex-info with
   `:reason` (`:access_denied`/`:expired_token`/`:timeout`/`:error`) otherwise.

   `:on-status` (optional) is called with `:pending`/`:slow-down`/`:authorized`
   so the caller can stream poll progress. `:post-fn`/`:sleep-fn`/`:now-fn` are
   injectable for tests (`sleep-fn` takes ms; `now-fn` returns millis)."
  [{:keys [token-endpoint client-id device-code interval expires-in poll-cap-ms
           on-status post-fn sleep-fn now-fn]
    :or   {interval    default-interval
           poll-cap-ms 900000
           post-fn     http/post
           sleep-fn    (fn [ms] (Thread/sleep (long ms)))
           now-fn      (fn [] (System/currentTimeMillis))}}]
  (let [budget   (min poll-cap-ms (* 1000 (or expires-in 1800)))
        deadline (+ (now-fn) budget)
        notify   (fn [k] (when on-status (on-status k)))]
    (loop [interval interval]
      (when (>= (now-fn) deadline)
        (throw (ex-info "Device authorization timed out before approval"
                        {:reason :timeout})))
      (let [[k v] (poll-once {:token-endpoint token-endpoint
                              :client-id      client-id
                              :device-code    device-code
                              :post-fn        post-fn})]
        (case k
          :ok        (do (notify :authorized) v)
          :pending   (do (notify :pending)
                         (sleep-fn (* 1000 interval))
                         (recur interval))
          :slow-down (let [next-interval (+ interval slow-down-bump)]
                       (mulog/info ::slow-down :interval next-interval)
                       (notify :slow-down)
                       (sleep-fn (* 1000 next-interval))
                       (recur next-interval))
          :denied    (throw (ex-info "Authorization denied by the user"
                                     {:reason :access_denied}))
          :expired   (throw (ex-info "Device code expired before approval"
                                     {:reason :expired_token}))
          :error     (throw (ex-info (str "Device flow error: " v)
                                     {:reason :error :detail v})))))))
