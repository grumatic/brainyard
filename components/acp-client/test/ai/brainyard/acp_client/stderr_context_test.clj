;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp-client.stderr-context-test
  "A failing ACP backend routinely explains itself on stderr and then answers
   the request with a generic `Internal error`. These tests pin the join
   between the two halves.

   Motivating case: `claude-code-acp` refuses to start inside another Claude
   Code session, prints the reason (and the fix — unset CLAUDECODE) on
   stderr, and returns `{-32603 Internal error}` on the wire. Before this,
   the user saw only \"ACP error: Internal error\" and had nothing to act on."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.acp.interface :as acp]
            [ai.brainyard.acp-client.core.client :as client]))

(def ^:private NESTED-SESSION-ERROR
  "A backend that narrates a fatal condition on stderr, then answers the
   first request with a bare `Internal error` — the shape claude-code-acp
   produces when it detects a nested session.

   The trailing object dump is not decoration: it is what the real backend
   emits AFTER the prose, and keeping the last few raw lines would surface
   `code: -32603, | message: 'Internal error'` — a restatement of the error
   the caller already has — instead of the sentence that names the remedy."
  (str "echo 'Error: Claude Code cannot be launched inside another Claude Code session.' >&2; "
       "echo 'Nested sessions share runtime resources and will crash all active sessions.' >&2; "
       "echo 'To bypass this check, unset the CLAUDECODE environment variable.' >&2; "
       "echo 'Error handling request {' >&2; "
       "echo \"  jsonrpc: '2.0',\" >&2; "
       "echo '  id: 2,' >&2; "
       "echo \"  method: 'session/new',\" >&2; "
       "echo '} {' >&2; "
       "echo '  code: -32603,' >&2; "
       "echo \"  message: 'Internal error',\" >&2; "
       "echo '}' >&2; "
       "while IFS= read -r line; do "
       "printf '{\"jsonrpc\":\"2.0\",\"id\":1,"
       "\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}\\n'; "
       "done"))

(def ^:private SILENT-ERROR
  "Same failure, but the backend says nothing on stderr."
  (str "while IFS= read -r line; do "
       "printf '{\"jsonrpc\":\"2.0\",\"id\":1,"
       "\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}\\n'; "
       "done"))

(defn- with-backend [script f]
  (let [c (client/create {:transport (acp/create-stdio-transport
                                      {:command ["sh" "-c" script]})})]
    (client/open! c)
    (try (f c) (finally (client/close! c)))))

(defn- provoke
  "Send a request and return the ExceptionInfo `await-result` throws."
  [c]
  (try
    (client/await-result c (client/request! c "session/new" {} {:timeout-ms 5000}) 5000)
    nil
    (catch clojure.lang.ExceptionInfo e e)))

(deftest error-response-carries-backend-stderr-test
  (testing "the backend's stderr is appended to the generic error message"
    (with-backend NESTED-SESSION-ERROR
      (fn [c]
        ;; Let the stderr drain thread see both lines before we provoke.
        (Thread/sleep (long 300))
        (let [e (provoke c)]
          (is (some? e) "expected await-result to throw on an error response")
          (is (str/includes? (ex-message e) "Internal error")
              "the backend's own message is preserved")
          (is (str/includes? (ex-message e) "cannot be launched inside another")
              "the actionable cause is surfaced")
          (is (str/includes? (ex-message e) "unset the CLAUDECODE")
              "so is the remedy the backend suggested")
          (is (= :acp/error-response (:type (ex-data e))))
          (is (str/includes? (str (:stderr (ex-data e))) "CLAUDECODE")
              "and it is available structurally, not only in the message")))))

  (testing "the pretty-printed object dump is filtered out of the message"
    (with-backend NESTED-SESSION-ERROR
      (fn [c]
        (Thread/sleep (long 300))
        (let [m (ex-message (provoke c))]
          (is (not (str/includes? m "-32603"))
              "the dumped error code restates what the caller already has")
          (is (not (str/includes? m "jsonrpc"))
              "the echoed request is noise")
          (is (not (str/includes? m "Error handling request"))
              "a line that only opens a dump says nothing"))))))

(deftest silent-backend-message-is-unchanged-test
  (testing "with nothing on stderr the message is not decorated"
    (with-backend SILENT-ERROR
      (fn [c]
        (let [e (provoke c)]
          (is (some? e))
          (is (= "Internal error" (ex-message e))
              "no trailing separator when there is nothing to append")
          (is (nil? (:stderr (ex-data e)))))))))

(deftest backend-stderr-accessor-test
  (testing "backend-stderr joins the tail into one line"
    (with-backend NESTED-SESSION-ERROR
      (fn [c]
        (Thread/sleep (long 300))
        (let [s (client/backend-stderr c)]
          (is (string? s))
          (is (str/includes? s " | ") "multiple lines are joined")))))

  (testing "backend-stderr is nil for a quiet backend"
    (with-backend SILENT-ERROR
      (fn [c]
        (is (nil? (client/backend-stderr c))))))

  (testing "backend-stderr tolerates a nil client"
    (is (nil? (client/backend-stderr nil)))))
