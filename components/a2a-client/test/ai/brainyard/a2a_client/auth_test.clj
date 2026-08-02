;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-client.auth-test
  "Unit tests for outbound credential handling. Pure — no network.

   The redaction tests are the load-bearing ones: an auth map is attached
   to a peer record that gets logged, listed by `a2a$list`, and rendered
   into LLM context."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.brainyard.a2a-client.core.auth :as auth]))

;; =============================================================================
;; Normalization
;; =============================================================================

(deftest normalize-test
  (testing "nil and blank mean anonymous"
    (is (nil? (auth/normalize nil)))
    (is (nil? (auth/normalize "")))
    (is (nil? (auth/normalize "   ")))
    (is (nil? (auth/normalize {}))))

  (testing "a bare string is a bearer token"
    ;; The LLM-ergonomic shorthand: a2a$connect is called from a code
    ;; block, and demanding a tagged map for the common case just produces
    ;; malformed calls.
    (is (= {:type :bearer :token "sk-abc"} (auth/normalize "sk-abc"))))

  (testing "{:token …} is a bearer token"
    (is (= {:type :bearer :token "sk-abc"} (auth/normalize {:token "sk-abc"}))))

  (testing "an explicit :type is preserved and keywordized"
    (is (= :bearer (:type (auth/normalize {:type "bearer" :token "x"}))))
    (is (= :api-key (:type (auth/normalize {:type :api-key :value "x"})))))

  (testing "username+password infers basic"
    (is (= :basic (:type (auth/normalize {:username "u" :password "p"})))))

  (testing "an unrecognized map passes through rather than being dropped"
    ;; A future scheme must not silently become 'anonymous'.
    (is (= {:type :mtls :cert "x"} (auth/normalize {:type :mtls :cert "x"}))))

  (testing "non-map non-string input is anonymous"
    (is (nil? (auth/normalize 42)))
    (is (nil? (auth/normalize [1 2])))))

;; =============================================================================
;; Header application
;; =============================================================================

(deftest headers-test
  (testing "bearer"
    (is (= {"Authorization" "Bearer sk-abc"} (auth/headers "sk-abc")))
    (is (= {"Authorization" "Bearer sk-abc"}
           (auth/headers {:type :bearer :token "sk-abc"}))))

  (testing "a blank bearer token yields NO header rather than 'Bearer '"
    ;; Sending an empty credential reads as a malformed request to a
    ;; server, which is worse than sending none.
    (is (= {} (auth/headers {:type :bearer :token ""}))))

  (testing "basic encodes user:pass as base64"
    (let [h (auth/headers {:type :basic :username "u" :password "p"})]
      (is (= {"Authorization" "Basic dTpw"} h))))

  (testing "api-key defaults to the X-API-Key header"
    (is (= {"X-API-Key" "k"} (auth/headers {:type :api-key :value "k"}))))

  (testing "api-key honours a custom header name"
    (is (= {"X-Custom" "k"}
           (auth/headers {:type :api-key :name "X-Custom" :value "k"}))))

  (testing "a QUERY api-key contributes no header"
    (is (= {} (auth/headers {:type :api-key :name "key" :value "k" :in "query"}))))

  (testing "anonymous yields no headers"
    (is (= {} (auth/headers nil)))))

(deftest apply-to-url-test
  (testing "a query api-key is appended"
    (is (= "https://x/a2a?key=k"
           (auth/apply-to-url "https://x/a2a"
                              {:type :api-key :name "key" :value "k" :in "query"}))))

  (testing "it uses & when the URL already has a query string"
    (is (= "https://x/a2a?a=1&key=k"
           (auth/apply-to-url "https://x/a2a?a=1"
                              {:type :api-key :name "key" :value "k" :in "query"}))))

  (testing "values are URL-encoded"
    (is (str/includes?
         (auth/apply-to-url "https://x" {:type :api-key :name "key"
                                         :value "a b&c" :in "query"})
         "a+b%26c")))

  (testing "every other scheme leaves the URL UNTOUCHED"
    ;; The URL doubles as the peer's identity and its call-chain token; a
    ;; credential must never be baked into that.
    (is (= "https://x/a2a" (auth/apply-to-url "https://x/a2a" "sk-abc")))
    (is (= "https://x/a2a" (auth/apply-to-url "https://x/a2a" nil)))
    (is (= "https://x/a2a" (auth/apply-to-url "https://x/a2a"
                                              {:type :api-key :value "k"})))))

;; =============================================================================
;; Redaction — nothing here may leak a secret
;; =============================================================================

(deftest redact-test
  (testing "a bearer token is redacted but the scheme survives"
    (let [r (auth/redact {:type :bearer :token "sk-supersecret"})]
      (is (= :bearer (:type r)))
      (is (= "<redacted>" (:token r)))))

  (testing "basic passwords are redacted; the username is not a secret"
    (let [r (auth/redact {:type :basic :username "u" :password "hunter2"})]
      (is (= "u" (:username r)))
      (is (= "<redacted>" (:password r)))))

  (testing "api-key values are redacted; the header name is not a secret"
    (let [r (auth/redact {:type :api-key :name "X-Key" :value "k-secret"})]
      (is (= "X-Key" (:name r)))
      (is (= "<redacted>" (:value r)))))

  (testing "NO secret survives redaction, in any scheme"
    (doseq [spec [{:type :bearer :token "SECRET"}
                  {:type :basic :username "u" :password "SECRET"}
                  {:type :api-key :name "X" :value "SECRET"}
                  "SECRET"]]
      (is (not (str/includes? (pr-str (auth/redact spec)) "SECRET"))
          (str "secret leaked from: " (pr-str spec)))))

  (testing "anonymous redacts to nil"
    (is (nil? (auth/redact nil)))))

(deftest describe-test
  (testing "names the scheme without the secret"
    (is (= "none" (auth/describe nil)))
    (is (= "bearer" (auth/describe "sk-abc")))
    (is (= "basic" (auth/describe {:type :basic :username "u" :password "p"})))
    (is (= "api-key(X-API-Key)" (auth/describe {:type :api-key :value "k"})))
    (is (= "api-key(X-Custom)"
           (auth/describe {:type :api-key :name "X-Custom" :value "k"}))))

  (testing "no description contains a secret"
    (doseq [spec ["SECRET"
                  {:type :bearer :token "SECRET"}
                  {:type :api-key :name "X" :value "SECRET"}]]
      (is (not (str/includes? (auth/describe spec) "SECRET"))))))

(deftest configured?-test
  (is (not (auth/configured? nil)))
  (is (not (auth/configured? "")))
  (is (auth/configured? "sk-abc"))
  (is (auth/configured? {:type :api-key :value "k"})))

;; =============================================================================
;; Card hints
;; =============================================================================

(deftest card-hint-test
  (testing "no hint when credentials ARE configured"
    (is (nil? (auth/missing-credentials-hint
               {:securitySchemes {:x {:type "http"}}} "sk-abc"))))

  (testing "a hint when the peer declares schemes and we have nothing"
    (let [h (auth/missing-credentials-hint
             {:securitySchemes {:x {:type "http"} :y {:type "oauth2"}}} nil)]
      (is (str/includes? h "requires authentication"))
      (is (str/includes? h "http"))
      (is (str/includes? h "oauth2"))))

  (testing "still a hint when the card declares no schemes"
    ;; Absence of a declared scheme is not a promise of anonymity.
    (is (some? (auth/missing-credentials-hint {} nil))))

  (testing "card-requires-auth? reads either field"
    (is (auth/card-requires-auth? {:security [{:x []}]}))
    (is (auth/card-requires-auth? {:securitySchemes {:x {:type "http"}}}))
    (is (not (auth/card-requires-auth? {:name "x"})))))
