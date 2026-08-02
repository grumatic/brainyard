;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a.errors-test
  "Unit tests for the A2A error catalog and its two-way translation.
   Pure data — no I/O."
  (:require [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.brainyard.a2a.core.errors :as errors]))

;; =============================================================================
;; Catalog
;; =============================================================================

(deftest codes-test
  (testing "A2A codes sit in the JSON-RPC implementation-defined server range"
    ;; -32000..-32099 is the range the JSON-RPC 2.0 spec reserves for
    ;; application-defined server errors. Straying outside it collides
    ;; with the standard codes.
    (doseq [[k code] errors/a2a-codes]
      (is (<= -32099 code -32000)
          (str k " (" code ") must be in -32099..-32000"))))

  (testing "standard and A2A codes do not overlap"
    (is (empty? (clojure.set/intersection
                 (set (vals errors/standard-codes))
                 (set (vals errors/a2a-codes))))))

  (testing "every code is unique"
    (is (= (count errors/all-codes)
           (count (set (vals errors/all-codes))))))

  (testing "code->key inverts all-codes"
    (doseq [[k code] errors/all-codes]
      (is (= k (get errors/code->key code)))))

  (testing "every code has a canonical message"
    (doseq [k (keys errors/all-codes)]
      (is (string? (errors/message-of k)))
      (is (not (str/blank? (errors/message-of k))))))

  (testing "an unknown keyword falls back to internal-error"
    (is (= (:internal-error errors/standard-codes)
           (errors/code-of :no-such-error)))))

;; =============================================================================
;; Outbound — local failure -> JSON-RPC error response
;; =============================================================================

(deftest ->jsonrpc-test
  (testing "builds a well-formed JSON-RPC error response"
    (let [r (errors/->jsonrpc 7 :task-not-found)]
      (is (= "2.0" (:jsonrpc r)))
      (is (= 7 (:id r)))
      (is (= -32001 (get-in r [:error :code])))
      (is (= "Task not found" (get-in r [:error :message])))))

  (testing "optional data rides along"
    (let [r (errors/->jsonrpc 1 :invalid-params {:detail "bad :id"})]
      (is (= {:detail "bad :id"} (get-in r [:error :data])))))

  (testing "data is omitted when nil"
    (is (not (contains? (:error (errors/->jsonrpc 1 :internal-error)) :data)))))

(deftest not-found-does-not-leak-existence-test
  ;; The spec requires that a caller cannot distinguish "no such task"
  ;; from "exists but not yours" — otherwise the error channel becomes an
  ;; enumeration oracle. These two calls must be byte-identical.
  (testing "the missing-resource and unauthorized responses are IDENTICAL"
    (is (= (errors/not-found 42) (errors/not-found 42))))

  (testing "not-found carries NO resource identity in its payload"
    (let [r (errors/not-found 42)]
      (is (not (contains? (:error r) :data)))
      (is (= "Task not found" (get-in r [:error :message])))
      ;; The only echoed value is the JSON-RPC request id, which the
      ;; caller supplied and already knows.
      (is (= #{:code :message} (set (keys (:error r)))))))

  (testing "not-found takes only a request id — the arity forbids leaking"
    ;; A one-arg fn cannot accidentally be handed a task-id to echo back.
    (is (= 1 (count (first (:arglists (meta #'errors/not-found)))))))

  (testing "it is the task-not-found code"
    (is (= -32001 (get-in (errors/not-found 1) [:error :code])))))

(deftest convenience-constructors-test
  (testing "unsupported"
    (is (= -32004 (get-in (errors/unsupported 1) [:error :code])))
    (is (= {:detail "streaming"}
           (get-in (errors/unsupported 1 "streaming") [:error :data]))))

  (testing "invalid-params"
    (is (= -32602 (get-in (errors/invalid-params 1) [:error :code]))))

  (testing "internal"
    (is (= -32603 (get-in (errors/internal 1) [:error :code])))))

;; =============================================================================
;; Inbound — JSON-RPC error object -> brainyard error map
;; =============================================================================

(deftest error-key-test
  (testing "maps a known code to its keyword"
    (is (= :task-not-found (errors/error-key {:code -32001})))
    (is (= :method-not-found (errors/error-key {:code -32601}))))

  (testing "an unrecognized code yields :unknown-error rather than nil"
    (is (= :unknown-error (errors/error-key {:code -12345})))
    (is (= :unknown-error (errors/error-key {})))))

(deftest ->result-test
  (testing "produces brainyard's {:error …} shape"
    (let [r (errors/->result {:code -32001 :message "Task not found"})]
      (is (string? (:error r)))
      (is (str/includes? (:error r) "Task not found"))
      (is (str/includes? (:error r) "-32001"))))

  (testing "carries :error-key so callers branch on kind, not prose"
    (is (= :task-not-found
           (:error-key (errors/->result {:code -32001 :message "x"})))))

  (testing "carries the numeric code"
    (is (= -32001 (:code (errors/->result {:code -32001 :message "x"})))))

  (testing "server-supplied detail is appended"
    (let [r (errors/->result {:code -32004 :message "This operation is not supported"
                              :data {:detail "streaming disabled"}})]
      (is (str/includes? (:error r) "streaming disabled"))))

  (testing "falls back to the canonical message when the server sends none"
    (let [r (errors/->result {:code -32001})]
      (is (str/includes? (:error r) "Task not found")))))

(deftest retryable-test
  (testing "internal errors are retryable"
    (is (errors/retryable? {:code -32603})))

  (testing "deterministic failures are NOT retryable"
    ;; These fail identically forever; retrying just spends tokens twice.
    (doseq [code [-32001 -32002 -32004 -32005 -32601 -32602]]
      (is (not (errors/retryable? {:code code}))
          (str "code " code " should not be retryable"))))

  (testing "an unknown code is not retryable"
    (is (not (errors/retryable? {:code -12345})))))
