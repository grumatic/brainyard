;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.retry-429-test
  "A 429 means two unrelated things and they need opposite handling: a
   per-minute throttle should be retried, an exhausted credit balance must not
   be. Before this split, an exhausted-quota key spent ~90s in backoff
   (1+2+4+8+16+32s, 429 getting max-retries+3) on EVERY call before failing
   with what the first response already said definitively."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.clj-llm.core.llm :as llm]))

(def ^:private exhausted-quota?  #'llm/exhausted-quota?)
(def ^:private retryable-status? #'llm/retryable-status?)

(defn- http-ex
  [status body]
  (ex-info (str "HTTP " status) {:status status :body body}))

;; The verbatim body returned by api.openai.com on 2026-08-20 for a key with no
;; credits — this test exists because that response was retried six times.
(def ^:private real-openai-quota-body
  (str "{\n  \"error\": {\n"
       "    \"message\": \"You have no credits remaining. Add credits to continue"
       " using the API at https://platform.openai.com/settings/organization/billing/.\",\n"
       "    \"type\": \"insufficient_quota\",\n"
       "    \"param\": null,\n"
       "    \"code\": \"credit_balance_exhausted\"\n  }\n}"))

(def ^:private openai-throttle-body
  (str "{\"error\":{\"message\":\"Rate limit reached for gpt-4.1-mini\","
       "\"type\":\"requests\",\"code\":\"rate_limit_exceeded\"}}"))

(def ^:private anthropic-throttle-body
  "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Number of requests\"}}")

;; ============================================================================
;; Detection
;; ============================================================================

(deftest detects-exhausted-quota
  (testing "the real OpenAI no-credits body is recognized"
    (is (true? (exhausted-quota? (http-ex 429 real-openai-quota-body)))))

  (testing "each marker is matched on its own"
    (doseq [m ["insufficient_quota" "credit_balance_exhausted"
               "billing_hard_limit_reached" "billing_not_active"
               "account_deactivated"]]
      (is (true? (exhausted-quota? (http-ex 429 (str "{\"error\":{\"code\":\"" m "\"}}"))))
          (str m " must be recognized as permanent"))))

  (testing "matching is case-insensitive"
    (is (true? (exhausted-quota? (http-ex 429 "{\"code\":\"INSUFFICIENT_QUOTA\"}"))))))

(deftest leaves-throttling-alone
  (testing "a genuine per-minute rate limit is NOT permanent"
    (is (false? (exhausted-quota? (http-ex 429 openai-throttle-body))))
    (is (false? (exhausted-quota? (http-ex 429 anthropic-throttle-body)))))

  (testing "an unreadable body stays retryable — positive-match only"
    ;; The asymmetry is deliberate: wrongly calling a transient limit permanent
    ;; turns a recoverable blip into a hard failure, so anything we cannot
    ;; positively identify keeps today's behavior.
    (is (false? (exhausted-quota? (http-ex 429 nil))))
    (is (false? (exhausted-quota? (http-ex 429 ""))))
    (is (false? (exhausted-quota? (http-ex 429 "<html>502 Bad Gateway</html>"))))
    (is (false? (exhausted-quota? (http-ex 429 {:not "a string"}))))
    (is (false? (exhausted-quota? (RuntimeException. "boom"))))))

;; ============================================================================
;; Retry decision
;; ============================================================================

(deftest retryable-status-honors-the-split
  (testing "an exhausted-quota 429 is not retryable"
    (is (not (retryable-status? 429 (http-ex 429 real-openai-quota-body)))))

  (testing "a throttling 429 stays retryable"
    (is (retryable-status? 429 (http-ex 429 openai-throttle-body))))

  (testing "the 1-arity is unchanged — every 429 retryable, as before"
    (is (retryable-status? 429))
    (is (retryable-status? 500))
    (is (retryable-status? 503))
    (is (not (retryable-status? 400)))
    (is (not (retryable-status? 401)))
    (is (nil? (retryable-status? nil))))

  (testing "5xx is unaffected by the body"
    (is (retryable-status? 500 (http-ex 500 real-openai-quota-body)))))

;; ============================================================================
;; Classification — the reason string is what the user reads
;; ============================================================================

(deftest classify-names-the-actual-problem
  (testing "an exhausted quota points at billing, not at waiting"
    (let [{:keys [class reason]} (llm/classify-error (http-ex 429 real-openai-quota-body))]
      (is (= :fatal class))
      (is (re-find #"(?i)quota|credits" reason))
      (is (re-find #"(?i)billing" reason))))

  (testing "a throttling 429 still reads as a rate limit"
    (let [{:keys [class reason]} (llm/classify-error (http-ex 429 openai-throttle-body))]
      (is (= :fatal class) "a 429 is never re-promptable")
      (is (re-find #"(?i)rate limited" reason))))

  (testing "other statuses are unchanged"
    (is (= :transient (:class (llm/classify-error (http-ex 503 "")))))
    (is (= :fatal     (:class (llm/classify-error (http-ex 401 "")))))))
