;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.timeout-test
  "A sub-LLM call had no reachable timeout. The non-streaming chat paths never
   passed one, so `clj-http-native`'s 60s `:or` default cut every long
   generation — and since an HttpTimeoutException is not an ExceptionInfo,
   `llm-retryable?` fell through to true and the call was retried three more
   times before failing. Meanwhile the batched path documented an override
   (`lm-config :timeout-ms`) that `create-lm` silently dropped, because it
   returns a fixed key set.

   The absence case is the one with teeth: `request` supplies its default via
   `:or`, which fires only on a MISSING key, so an explicit `:timeout-ms nil`
   reaches `(long nil)` and NPEs. Unconfigured must mean the key is not there."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.providers :as providers]))

(def ^:private openai-lm
  {:model "gpt-4o" :provider :openai :base-url "https://example.invalid/v1"
   :api-key "k" :message-format :openai :temperature 0.0})

(def ^:private anthropic-lm
  (assoc openai-lm :provider :anthropic :message-format :anthropic))

(def ^:private openai-body
  "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{}}")

(def ^:private anthropic-body
  "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"usage\":{}}")

(defn- capture-post
  "Run `f` with `http/post` stubbed; return the opts map of the first request."
  [body f]
  (let [seen (atom nil)]
    (with-redefs [http/post (fn [_url opts]
                              (when-not @seen (reset! seen opts))
                              {:status 200 :body body})]
      (f))
    @seen))

(defn- query-timeout
  "The `:timeout-ms` entry `create-llm-query-fn` puts on the wire, as
   `[key-present? value]` — the pair, because absent and nil are different
   outcomes downstream and only one of them is safe."
  [lm-config query-opts]
  (let [opts (capture-post openai-body
                           #((llm/create-llm-query-fn lm-config nil query-opts) "hi"))]
    [(contains? opts :timeout-ms) (:timeout-ms opts)]))

;; ============================================================================
;; create-lm passthrough
;; ============================================================================

(deftest create-lm-carries-timeout-ms
  (testing "an explicit :timeout-ms survives create-lm's fixed key set"
    (is (= 300000 (:timeout-ms (providers/create-lm {:model "openai/gpt-4o"
                                                     :timeout-ms 300000})))))
  (testing "and is ABSENT, not nil, when unset — a nil would defeat the :or default"
    (is (not (contains? (providers/create-lm {:model "openai/gpt-4o"}) :timeout-ms)))))

;; ============================================================================
;; Non-streaming chat paths
;; ============================================================================

(deftest unconfigured-timeout-omits-the-key-entirely
  (testing "openai"
    (is (= [false nil] (query-timeout openai-lm nil))))
  (testing "anthropic"
    (let [opts (capture-post anthropic-body
                             #(llm/anthropic-chat-completion anthropic-lm [{:role "user" :content "hi"}] {}))]
      (is (not (contains? opts :timeout-ms))))))

(deftest lm-config-timeout-reaches-the-request
  (testing "openai"
    (is (= [true 300000] (query-timeout (assoc openai-lm :timeout-ms 300000) nil))))
  (testing "anthropic — both chat paths read the same helper"
    (let [opts (capture-post anthropic-body
                             #(llm/anthropic-chat-completion (assoc anthropic-lm :timeout-ms 300000)
                                                             [{:role "user" :content "hi"}] {}))]
      (is (= 300000 (:timeout-ms opts))))))

(deftest per-call-opts-outrank-the-lm-config
  (is (= [true 45000] (query-timeout (assoc openai-lm :timeout-ms 300000) {:timeout-ms 45000})))
  (testing "a per-call timeout needs no timeout on the LM at all"
    (is (= [true 45000] (query-timeout openai-lm {:timeout-ms 45000})))))

(deftest query-fn-arity-2-still-works
  (testing "existing callers (clj-sandbox chat.clj) pass no opts"
    (let [opts (capture-post openai-body #((llm/create-llm-query-fn openai-lm nil) "hi"))]
      (is (not (contains? opts :timeout-ms))))))

;; ============================================================================
;; Batched
;; ============================================================================

(deftest batched-timeout-bounds-both-the-batch-and-each-call
  (testing "the per-call request deadline matches the batch's, so a slot cannot
            be reported timed out while its own request is still legal"
    (let [opts (capture-post openai-body
                             #((llm/create-llm-query-batched-fn openai-lm nil {:timeout-ms 600000})
                               ["a"]))]
      (is (= 600000 (:timeout-ms opts)))))
  (testing "arity-2 keeps the 180s default and passes it down"
    (let [opts (capture-post openai-body
                             #((llm/create-llm-query-batched-fn openai-lm nil) ["a"]))]
      (is (= 180000 (:timeout-ms opts)))))
  (testing "lm-config supplies it when no per-call opts are given"
    (let [opts (capture-post openai-body
                             #((llm/create-llm-query-batched-fn (assoc openai-lm :timeout-ms 90000) nil)
                               ["a"]))]
      (is (= 90000 (:timeout-ms opts))))))

(deftest batched-deadline-is-enforced
  (testing "a hung call yields a timeout STRING in its slot, not a throw —
            the vector stays full-length and in input order"
    (with-redefs [http/post (fn [_url _opts] (Thread/sleep (long 5000)) {:status 200 :body openai-body})]
      (let [r ((llm/create-llm-query-batched-fn openai-lm nil {:timeout-ms 150}) ["a" "b"])]
        (is (= 2 (count r)))
        (is (every? #(re-find #"timed out after 150ms" %) r))))))

(deftest batched-still-rejects-oversized-batches
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"max 20 prompts"
                        ((llm/create-llm-query-batched-fn openai-lm nil {:timeout-ms 1000})
                         (vec (repeat 21 "x"))))))
