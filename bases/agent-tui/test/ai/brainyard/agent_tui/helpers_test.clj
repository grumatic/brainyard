;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.helpers-test
  "Provider-credential pre-flight: `missing-provider-key` / `no-provider-message`
   let `by run` / `by ask` NOTIFY the user about an absent API key instead of
   throwing a raw stack trace, and `setup-lm!` stays the single source of truth
   for the same decision."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.agent-tui.helpers :as helpers]))

(defn- no-anthropic-env?
  "True when NO real env var can satisfy :anthropic. The assertions below drive
   the property fallback, and a real env var shadows a property — so each
   accepted credential has to be checked, not just the primary. Missing an
   alternate here is how a suite passes on CI and fails on a maintainer's shell."
  []
  (every? #(str/blank? (System/getenv %))
          ["ANTHROPIC_API_KEY" "ANTHROPIC_AUTH_TOKEN"]))

(deftest missing-provider-key-semantics
  (testing "keyless providers never report a missing key"
    (is (nil? (helpers/missing-provider-key :ollama)))
    (is (nil? (helpers/missing-provider-key :claude-code)))
    (is (nil? (helpers/missing-provider-key :apple-fm))))

  (testing "a .env-bridged system property satisfies the key (env OR property)"
    (System/setProperty "ANTHROPIC_API_KEY" "sk-test")
    (try (is (nil? (helpers/missing-provider-key :anthropic)))
         (finally (System/clearProperty "ANTHROPIC_API_KEY"))))

  (testing "a blank value counts as absent"
    (System/setProperty "ANTHROPIC_API_KEY" "   ")
    (try
      ;; only meaningful when no real env var shadows it
      (when (no-anthropic-env?)
        (is (= "ANTHROPIC_API_KEY" (helpers/missing-provider-key :anthropic))))
      (finally (System/clearProperty "ANTHROPIC_API_KEY"))))

  (testing "absent key (env + property both unset) reports the env-var name"
    (System/clearProperty "ANTHROPIC_API_KEY")
    (when (no-anthropic-env?)
      (is (= "ANTHROPIC_API_KEY" (helpers/missing-provider-key :anthropic)))))

  (testing "a bearer token satisfies :anthropic — ANTHROPIC_AUTH_TOKEN alone is enough"
    ;; The pre-flight gates `setup-lm!`, so treating the token as absent would
    ;; abort a gateway-configured run before create-lm ever saw the credential.
    (System/clearProperty "ANTHROPIC_API_KEY")
    (System/setProperty "ANTHROPIC_AUTH_TOKEN" "sk-gw-token")
    (try (is (nil? (helpers/missing-provider-key :anthropic)))
         (finally (System/clearProperty "ANTHROPIC_AUTH_TOKEN"))))

  (testing "the reported name is the PRIMARY, not an alternate"
    (System/clearProperty "ANTHROPIC_API_KEY")
    (System/clearProperty "ANTHROPIC_AUTH_TOKEN")
    (when (no-anthropic-env?)
      (is (= "ANTHROPIC_API_KEY" (helpers/missing-provider-key :anthropic))
          "an unconfigured user should be told to set the API key, not the gateway token"))))

(deftest no-provider-message-is-actionable
  (let [msg (helpers/no-provider-message :anthropic)]
    (is (re-find #"anthropic" msg))
    (is (re-find #"ANTHROPIC_API_KEY" msg))
    (is (re-find #"ANTHROPIC_AUTH_TOKEN" msg)
        "names the gateway alternative too — a user who has one shouldn't be told
         the only fix is a credential they don't have")
    (is (re-find #"by config" msg) "points the user at the setup wizard"))
  (testing "a single-credential provider gains no alternates clause"
    (let [msg (helpers/no-provider-message :openai)]
      (is (re-find #"OPENAI_API_KEY" msg))
      (is (not (re-find #"nor " msg))))))

(deftest setup-lm-throws-tagged-on-missing-key
  (testing "setup-lm! aborts with a ::no-provider ex-info (not a bare Exception)"
    (System/clearProperty "OPENAI_API_KEY")
    (when (str/blank? (System/getenv "OPENAI_API_KEY"))
      (let [e (try (helpers/setup-lm! :openai)
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e) "should throw when the key is absent")
        (is (true? (::helpers/no-provider (ex-data e))))
        (is (= :openai (:provider (ex-data e))))
        (is (re-find #"OPENAI_API_KEY" (ex-message e)))))))
