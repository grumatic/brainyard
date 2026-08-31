;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.anthropic-auth-test
  "The :anthropic provider's two credential shapes and its base-URL override.

   ANTHROPIC_API_KEY is sent as `x-api-key`; ANTHROPIC_AUTH_TOKEN is a gateway
   bearer credential sent as `Authorization: Bearer`. Which header a request
   carries is invisible from outside the process, so the header builder is
   asserted directly — a config that resolves the token but sends it in the
   wrong header fails at the gateway with a 401 that reads as a bad credential.

   System/getProperty is the documented fallback for the dotenv loader, so these
   drive JVM properties rather than requiring a real env — same technique as
   free_llm_test. A real env var SHADOWS a property, so every assertion that
   depends on a variable being unset is guarded by `clean-env?`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.clj-llm.core.providers :as providers]
            [ai.brainyard.clj-llm.core.llm :as llm]))

(def ^:private anthropic-vars
  ["ANTHROPIC_API_KEY" "ANTHROPIC_AUTH_TOKEN" "ANTHROPIC_BASE_URL"])

(defn- clean-env?
  "True when no real env var can shadow the properties these tests set."
  []
  (every? #(str/blank? (System/getenv %)) anthropic-vars))

(use-fixtures :each
  (fn [t]
    (let [saved (into {} (map (juxt identity #(System/getProperty %))) anthropic-vars)]
      (run! #(System/clearProperty %) anthropic-vars)
      (try
        (t)
        (finally
          (doseq [[k v] saved]
            (if v (System/setProperty k v) (System/clearProperty k))))))))

(def ^:private build-headers #'llm/build-anthropic-headers)

;; =============================================================================
;; Registry
;; =============================================================================

(deftest anthropic-registry-entry-test
  (let [cfg (:anthropic providers/providers)]
    (is (= "https://api.anthropic.com/v1" (:base-url cfg)))
    (is (= "ANTHROPIC_BASE_URL" (:base-url-env cfg)))
    (is (= "ANTHROPIC_API_KEY" (:api-key-env cfg)))
    (is (= "ANTHROPIC_AUTH_TOKEN" (:auth-token-env cfg)))
    (is (= "x-api-key" (:auth-header cfg)))
    (is (= :anthropic (:message-format cfg))))
  (testing ":anthropic-max is untouched — OAuth resolves its token per call"
    (let [cfg (:anthropic-max providers/providers)]
      (is (= :oauth (:auth-type cfg)))
      (is (nil? (:api-key-env cfg)))
      (is (nil? (:auth-token-env cfg))
          "a subscription token is not a static gateway credential"))))

;; =============================================================================
;; Credential resolution
;; =============================================================================

(deftest auth-token-resolution-test
  (testing "ANTHROPIC_AUTH_TOKEN alone resolves and marks the config :bearer"
    (System/setProperty "ANTHROPIC_AUTH_TOKEN" "sk-gw-abc")
    (let [lm (providers/create-lm {:model "claude-opus-5" :provider :anthropic})]
      (is (= "sk-gw-abc" (:api-key lm)))
      (is (= :bearer (:auth-type lm)))))

  (testing "ANTHROPIC_API_KEY alone resolves with NO :auth-type (x-api-key path)"
    (System/setProperty "ANTHROPIC_API_KEY" "sk-ant-real")
    (let [lm (providers/create-lm {:model "claude-opus-5" :provider :anthropic})]
      (is (= "sk-ant-real" (:api-key lm)))
      (is (nil? (:auth-type lm)))))

  (testing "the API key wins when both are set"
    ;; Precedence chosen so this feature can only ADD an auth path: an
    ;; environment that already had both set keeps the behavior it had.
    (System/setProperty "ANTHROPIC_API_KEY" "sk-ant-real")
    (System/setProperty "ANTHROPIC_AUTH_TOKEN" "sk-gw-abc")
    (let [lm (providers/create-lm {:model "claude-opus-5" :provider :anthropic})]
      (is (= "sk-ant-real" (:api-key lm)))
      (is (nil? (:auth-type lm)))))

  (testing "an explicit :api-key arg outranks both env vars"
    (System/setProperty "ANTHROPIC_API_KEY" "sk-ant-real")
    (System/setProperty "ANTHROPIC_AUTH_TOKEN" "sk-gw-abc")
    (let [lm (providers/create-lm {:model "claude-opus-5" :provider :anthropic
                                   :api-key "sk-explicit"})]
      (is (= "sk-explicit" (:api-key lm)))
      (is (nil? (:auth-type lm)))))

  (testing "a BLANK API key does not mask the token"
    ;; `export ANTHROPIC_API_KEY=` is a common shell accident. Letting the empty
    ;; string resolve would send an empty x-api-key and never reach the token.
    (when (clean-env?)
      (System/setProperty "ANTHROPIC_API_KEY" "   ")
      (System/setProperty "ANTHROPIC_AUTH_TOKEN" "sk-gw-abc")
      (let [lm (providers/create-lm {:model "claude-opus-5" :provider :anthropic})]
        (is (= "sk-gw-abc" (:api-key lm)))
        (is (= :bearer (:auth-type lm))))))

  (testing "no credential at all leaves :api-key nil and adds no :auth-type"
    ;; The fixture runs once per deftest, not per `testing` — clear what the
    ;; blocks above set, or this asserts against their leftovers.
    (System/clearProperty "ANTHROPIC_API_KEY")
    (System/clearProperty "ANTHROPIC_AUTH_TOKEN")
    (when (clean-env?)
      (let [lm (providers/create-lm {:model "claude-opus-5" :provider :anthropic})]
        (is (nil? (:api-key lm)))
        (is (nil? (:auth-type lm)))))))

;; =============================================================================
;; Headers — the part a gateway actually sees
;; =============================================================================

(deftest anthropic-header-shape-test
  (testing ":bearer sends Authorization and NO x-api-key"
    (let [h (build-headers {:api-key "sk-gw-abc" :auth-type :bearer})]
      (is (= "Bearer sk-gw-abc" (get h "Authorization")))
      (is (nil? (get h "x-api-key")))))

  (testing "the default sends x-api-key and NO Authorization"
    (let [h (build-headers {:api-key "sk-ant-real"})]
      (is (= "sk-ant-real" (get h "x-api-key")))
      (is (nil? (get h "Authorization")))))

  (testing "exactly one auth header, whichever shape"
    ;; Gateways differ on which they honor; a request carrying both
    ;; authenticates as whichever credential that hop happens to prefer.
    (doseq [cfg [{:api-key "k" :auth-type :bearer}
                 {:api-key "k"}]]
      (is (= 1 (count (filter (set (keys (build-headers cfg)))
                              ["Authorization" "x-api-key"]))))))

  (testing "version + cache-ttl headers are unaffected by the auth shape"
    (doseq [cfg [{:api-key "k" :auth-type :bearer :cache-ttl "1h"}
                 {:api-key "k" :cache-ttl "1h"}]]
      (let [h (build-headers cfg)]
        (is (= "2023-06-01" (get h "anthropic-version")))
        (is (= "extended-cache-ttl-2025-04-11" (get h "anthropic-beta"))))))

  (testing "no beta header at all when nothing asks for one"
    (is (nil? (get (build-headers {:api-key "k"}) "anthropic-beta"))))

  (testing "end to end: a token-only env produces a Bearer request"
    (System/setProperty "ANTHROPIC_AUTH_TOKEN" "sk-gw-abc")
    (let [lm (providers/create-lm {:model "claude-opus-5" :provider :anthropic})]
      (is (= "Bearer sk-gw-abc" (get (build-headers lm) "Authorization"))))))

;; =============================================================================
;; The oauth beta flag — which validator Anthropic uses for a bearer token
;; =============================================================================

(def ^:private anthropic-url "https://api.anthropic.com/v1")

(defn- betas
  "The anthropic-beta header split back into a set of flags."
  [headers]
  (set (some-> (get headers "anthropic-beta") (str/split #","))))

(deftest oauth-beta-flag-test
  ;; Measured against the live API with a deliberately invalid token:
  ;;   Bearer <bogus>                      -> "invalid x-api-key"
  ;;   Bearer <bogus> + oauth-2025-04-20   -> "OAuth access token is invalid."
  ;; The flag is what picks the validator, so getting it wrong is a guaranteed
  ;; 401 with a correct credential.
  (testing ":oauth always carries the flag — its token IS an OAuth token"
    ;; Regression: this path shipped without the flag, so anthropic-max sent a
    ;; valid subscription token to the API-key validator and could only 401.
    (with-redefs [llm/oauth-access-token (constantly "sk-ant-oat01-live")]
      (is (contains? (betas (build-headers {:auth-type :oauth})) "oauth-2025-04-20"))))

  (testing ":bearer carries it for an OAuth-shaped token at Anthropic's own host"
    (is (contains? (betas (build-headers {:auth-type :bearer
                                          :api-key "sk-ant-oat01-abc"
                                          :base-url anthropic-url}))
                   "oauth-2025-04-20")))

  (testing ":bearer does NOT carry it for a gateway key at Anthropic's host"
    (is (not (contains? (betas (build-headers {:auth-type :bearer
                                               :api-key "sk-gw-abc"
                                               :base-url anthropic-url}))
                        "oauth-2025-04-20"))))

  (testing ":bearer does NOT carry it off-host, even for an OAuth-shaped token"
    ;; A provider-specific flag sent to a host that isn't that provider is a
    ;; guess about someone else's server; a strict gateway may reject it.
    (doseq [url ["https://gw.example.com/v1"
                 "http://127.0.0.1:8080/v1"
                 nil]]
      (is (not (contains? (betas (build-headers {:auth-type :bearer
                                                 :api-key "sk-ant-oat01-abc"
                                                 :base-url url}))
                          "oauth-2025-04-20"))
          (str "off-host base-url: " (pr-str url)))))

  (testing "the plain x-api-key path never carries it"
    (is (not (contains? (betas (build-headers {:api-key "sk-ant-oat01-abc"
                                               :base-url anthropic-url}))
                        "oauth-2025-04-20"))))

  (testing "beta flags COMBINE — one header, comma-separated"
    ;; Two `assoc`s of "anthropic-beta" would silently drop the first flag.
    (let [flags (betas (build-headers {:auth-type :bearer
                                       :api-key "sk-ant-oat01-abc"
                                       :base-url anthropic-url
                                       :cache-ttl "1h"}))]
      (is (= #{"oauth-2025-04-20" "extended-cache-ttl-2025-04-11"} flags)))))

;; =============================================================================
;; Base URL
;; =============================================================================

(deftest anthropic-base-url-test
  (testing "unset ANTHROPIC_BASE_URL keeps the static default"
    (when (clean-env?)
      (is (= "https://api.anthropic.com/v1"
             (:base-url (providers/create-lm {:model "claude-opus-5"
                                              :provider :anthropic}))))))

  (testing "an origin-only override inherits the /v1 path"
    ;; Without this, the call site's (str base-url \"/messages\") would POST to
    ;; https://gw.example.com/messages and 404 — a failure that reads as a
    ;; broken gateway rather than a URL that lost its version segment.
    (System/setProperty "ANTHROPIC_BASE_URL" "https://gw.example.com")
    (is (= "https://gw.example.com/v1"
           (:base-url (providers/create-lm {:model "claude-opus-5"
                                            :provider :anthropic})))))

  (testing "a trailing slash is not doubled"
    (System/setProperty "ANTHROPIC_BASE_URL" "https://gw.example.com/")
    (is (= "https://gw.example.com/v1"
           (:base-url (providers/create-lm {:model "claude-opus-5"
                                            :provider :anthropic})))))

  (testing "an override WITH a path is honored verbatim"
    ;; A gateway mounted under a prefix is exactly what an override is for;
    ;; appending /v1 to it would make that configuration unreachable.
    (System/setProperty "ANTHROPIC_BASE_URL" "https://gw.example.com/anthropic/v1")
    (is (= "https://gw.example.com/anthropic/v1"
           (:base-url (providers/create-lm {:model "claude-opus-5"
                                            :provider :anthropic})))))

  (testing "an explicit :base-url arg wins over the env var"
    (System/setProperty "ANTHROPIC_BASE_URL" "https://from-env.example.com")
    (is (= "https://explicit.example.com/v1"
           (:base-url (providers/create-lm {:model "claude-opus-5"
                                            :provider :anthropic
                                            :base-url "https://explicit.example.com/v1"})))))

  (testing ":free-llm is unchanged by the reordering — nil static default"
    (let [saved (System/getProperty "FREELLM_BASE_URL")]
      (try
        (System/setProperty "FREELLM_BASE_URL" "http://localhost:9999/v1")
        (is (= "http://localhost:9999/v1"
               (:base-url (providers/create-lm {:model "auto" :provider :free-llm}))))
        (finally
          (if saved (System/setProperty "FREELLM_BASE_URL" saved)
              (System/clearProperty "FREELLM_BASE_URL")))))))

;; =============================================================================
;; Downstream readers
;; =============================================================================

(deftest bearer-satisfies-initialized-test
  (testing "a bearer token counts as a resolved credential"
    ;; The token rides :api-key precisely so lm-initialized?, the missing-key
    ;; warning and status masking need no knowledge of the second auth shape.
    (System/setProperty "ANTHROPIC_AUTH_TOKEN" "sk-gw-abc")
    (providers/configure-default-lm!
     (providers/create-lm {:model "claude-opus-5" :provider :anthropic}))
    (is (true? (boolean (providers/lm-initialized?))))))
