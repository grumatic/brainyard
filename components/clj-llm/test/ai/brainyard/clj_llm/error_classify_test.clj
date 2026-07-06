;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.error-classify-test
  "classify-error maps an LLM-call exception to a remedy class so the agent can
   re-prompt (malformed) vs retry-with-backoff (transient) vs abort (fatal).
   Cases are drawn from real dspy-error causes seen in agent-tui-app.log."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.clj-llm.interface :as llm]
            [ai.brainyard.clj-llm.core.llm :as core-llm]))

(defn- cls [e] (:class (llm/classify-error e)))

(deftest parse-json-response-no-envelope
  (testing "a pure-prose reply (no {...} anywhere) is flagged :no-json-envelope?"
    (let [prose "I have the full modal source. I'll rewrite MemoryModal.tsx cleanly."
          ex    (try (core-llm/parse-json-response prose) (catch Exception e e))
          data  (ex-data ex)]
      (is (true? (:no-json-envelope? data)) "distinct flag → surface prose as a thought")
      (is (= prose (:raw-text data)) "full text rides :raw-text for the thought")
      (is (not (str/includes? (ex-message ex) "MemoryModal"))
          "the message must NOT dump the prose (it goes to the thought, not the error)")
      (is (= :malformed (cls ex)) "still a re-prompt-class failure, like any parse error")))
  (testing "malformed JSON that DID carry a {...} envelope is NOT flagged no-envelope"
    (let [ex   (try (core-llm/parse-json-response "sure: {\"answer\": broken}") (catch Exception e e))
          data (ex-data ex)]
      (is (nil? (:no-json-envelope? data)))
      (is (contains? data :raw-text))
      (is (= :malformed (cls ex))))))

(deftest malformed-output-cases
  (testing "parse failures = the model's output, not the transport"
    ;; parse-json-response attaches :raw-text
    (is (= :malformed (cls (ex-info "JSON parse failed: JSON error (unexpected character): I"
                                    {:raw-text "I'll discover the tools first."}))))
    (is (= :malformed (cls (ex-info "JSON parse failed: JSON error (end-of-file)" {:raw-text ""}))))
    ;; message-only (no ex-data) still recognized
    (is (= :malformed (cls (java.lang.Exception. "JSON error (missing entry in object)"))))))

(deftest transient-cases
  (testing "HTTP 5xx → transient"
    (is (= :transient (cls (ex-info "HTTP 503 https://api.openai.com/v1/chat/completions" {:status 503}))))
    (is (= :transient (cls (ex-info "HTTP 500 https://x" {:status 500})))))
  (testing "network throws (no :status) → transient"
    (is (= :transient (cls (java.net.SocketTimeoutException. "Read timed out"))))
    (is (= :transient (cls (java.io.IOException. "Connection reset by peer"))))
    (is (= :transient (cls (java.net.ConnectException. "Connection refused")))))
  (testing "network throws with a NULL message → transient by TYPE, not the
            :malformed default (regression: a bare ConnectException from a dead
            endpoint has no message, so text matching alone misclassified it)"
    (is (= :transient (cls (java.net.ConnectException.))))
    (is (= :transient (cls (java.net.SocketException.))))
    (is (= :transient (cls (java.net.UnknownHostException.))))
    (is (= :transient (cls (java.net.SocketTimeoutException.))))
    ;; and when wrapped in an ex-info with a generic message (as dspy/clj-http may)
    (is (= :transient (cls (ex-info "LLM call failed" {} (java.net.ConnectException.)))))
    ;; the surfaced reason is meaningful, never the bare class name
    (let [{:keys [reason]} (llm/classify-error (java.net.ConnectException.))]
      (is (str/includes? reason "connect"))
      (is (not (str/includes? reason "java.net")))))
  (testing "provider phrasing → transient"
    (is (= :transient (cls (ex-info "Bedrock invoke failed: Bedrock is unable to process your request." {}))))
    (is (= :transient (cls (ex-info "model is overloaded, please try again" {}))))))

(deftest fatal-cases
  (testing "client 4xx (incl. 429 per policy) → fatal"
    (is (= :fatal (cls (ex-info "HTTP 404 https://api.openai.com/v1/chat/completions" {:status 404}))))
    (is (= :fatal (cls (ex-info "HTTP 401 https://x" {:status 401}))))
    (is (= :fatal (cls (ex-info "HTTP 429 https://x" {:status 429})))))
  (testing "auth / quota / billing / config → fatal"
    (is (= :fatal (cls (ex-info "authentication failed: invalid api key" {}))))
    (is (= :fatal (cls (ex-info "You have exceeded your quota" {}))))
    (is (= :fatal (cls (IllegalArgumentException. "URI with undefined scheme"))))
    (is (= :fatal (cls (ex-info "Bedrock invoke failed: model amazon.nova-lite-v1:0 with on-demand throughput isn't supported." {}))))))

(deftest unknown-defaults-to-malformed
  (testing "an unclassifiable error keeps the prior re-prompt-then-abort path"
    (is (= :malformed (cls (RuntimeException. "something weird happened")))))
  (testing "reason is a trimmed single line"
    (let [{:keys [reason]} (llm/classify-error (ex-info "HTTP 503 https://x" {:status 503}))]
      (is (string? reason))
      (is (not (re-find #"\n" reason))))))
