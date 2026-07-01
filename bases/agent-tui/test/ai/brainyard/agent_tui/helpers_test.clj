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
      (when (str/blank? (System/getenv "ANTHROPIC_API_KEY"))
        (is (= "ANTHROPIC_API_KEY" (helpers/missing-provider-key :anthropic))))
      (finally (System/clearProperty "ANTHROPIC_API_KEY"))))

  (testing "absent key (env + property both unset) reports the env-var name"
    (System/clearProperty "ANTHROPIC_API_KEY")
    (when (str/blank? (System/getenv "ANTHROPIC_API_KEY"))
      (is (= "ANTHROPIC_API_KEY" (helpers/missing-provider-key :anthropic))))))

(deftest no-provider-message-is-actionable
  (let [msg (helpers/no-provider-message :anthropic)]
    (is (re-find #"anthropic" msg))
    (is (re-find #"ANTHROPIC_API_KEY" msg))
    (is (re-find #"by config" msg) "points the user at the setup wizard")))

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
