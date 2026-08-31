;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.auth :as auth]))

(deftest targets-well-formed
  (is (seq auth/auth-targets))
  (doseq [t auth/auth-targets]
    (testing (str "target " (:id t))
      (is (keyword? (:id t)))
      (is (contains? #{:api-key :oauth :cli-delegate} (:method t)))
      (is (string? (:label t)))
      ;; every target yields a non-blank instruction without throwing
      (let [instr (auth/auth-instructions t)]
        (is (string? instr))
        (is (not (str/blank? instr))))
      ;; status never throws and returns a known keyword
      (is (contains? #{:signed-in :not-signed-in :cli-missing :unknown}
                     (auth/auth-status t))))))

(deftest find-target-case-insensitive
  (is (= :anthropic (:id (auth/auth-find-target "ANTHROPIC"))))
  (is (= :claude (:id (auth/auth-find-target "  claude "))))
  (is (nil? (auth/auth-find-target "nope")))
  (is (nil? (auth/auth-find-target nil)))
  (is (nil? (auth/auth-find-target ""))))

(deftest api-key-status-tracks-env
  (let [t {:id :test :method :api-key :env "BY_AUTH_TEST_ENV"}]
    (with-redefs [auth/getenv (fn [_] nil)]
      (is (= :not-signed-in (auth/auth-status t))))
    (with-redefs [auth/getenv (fn [_] "")]
      (is (= :not-signed-in (auth/auth-status t)) "blank env is not signed in"))
    (with-redefs [auth/getenv (fn [_] "sk-abc")]
      (is (= :signed-in (auth/auth-status t))))))

(deftest api-key-status-accepts-alternates
  ;; :anthropic authenticates with its API key OR a gateway bearer token
  ;; (ANTHROPIC_AUTH_TOKEN). An environment carrying only the token is signed
  ;; in — reporting it as not-signed-in would send the user to set a credential
  ;; they do not need.
  (let [t {:id :test :method :api-key :env "PRIMARY" :env-alts ["ALT"]}]
    (with-redefs [auth/getenv (fn [_] nil)]
      (is (= :not-signed-in (auth/auth-status t))))
    (with-redefs [auth/getenv (fn [k] (when (= k "ALT") "tok"))]
      (is (= :signed-in (auth/auth-status t)) "the alternate alone is enough"))
    (with-redefs [auth/getenv (fn [k] (when (= k "PRIMARY") "sk-abc"))]
      (is (= :signed-in (auth/auth-status t)))))
  (testing "the shipped :anthropic target carries the token as an alternate"
    (is (= ["ANTHROPIC_AUTH_TOKEN"]
           (:env-alts (auth/auth-find-target "anthropic")))))
  (testing "instructions still name the primary only"
    (let [instr (auth/auth-instructions (auth/auth-find-target "anthropic"))]
      (is (str/includes? instr "ANTHROPIC_API_KEY"))
      (is (not (str/includes? instr "ANTHROPIC_AUTH_TOKEN"))
          "the recommendation for someone with neither credential is the API key"))))

(deftest cli-delegate-status
  (let [t {:id :claude :method :cli-delegate :cli "claude"}]
    (with-redefs [auth/which (constantly nil)]
      (is (= :cli-missing (auth/auth-status t)) "no binary on PATH"))
    (with-redefs [auth/which (constantly "/usr/local/bin/claude")
                  auth/claude-logged-in? (constantly true)]
      (is (= :signed-in (auth/auth-status t))))
    (with-redefs [auth/which (constantly "/usr/local/bin/claude")
                  auth/claude-logged-in? (constantly false)]
      (is (= :not-signed-in (auth/auth-status t))))))

(deftest logout-messages
  (doseq [t auth/auth-targets]
    (let [msg (auth/auth-logout! t)]
      (is (string? msg))
      (is (not (str/blank? msg))))))

(deftest unknown-method-degrades
  (is (= :unknown (auth/auth-status {:id :x :method :bogus})))
  (is (string? (auth/auth-instructions {:id :x :method :bogus}))))
