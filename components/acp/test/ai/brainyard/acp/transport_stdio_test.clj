;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp.transport-stdio-test
  "Integration tests for the stdio transport. Spawns a tiny shell echo
   loop as the peer subprocess: each line written to stdin is echoed
   back on stdout. Validates framing, parsing, EOF handling, and
   close idempotency."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.brainyard.acp.core.transport :as transport]
            [ai.brainyard.acp.core.transport.stdio :as stdio]
            [ai.brainyard.acp.core.jsonrpc :as jsonrpc]))

(def ^:private ECHO-LOOP
  "Tiny POSIX shell that echoes each stdin line back on stdout, line
   buffered. Behaves like an ACP peer that just bounces every message."
  "while IFS= read -r line; do printf '%s\\n' \"$line\"; done")

(defn- spawn-echo []
  (let [t (stdio/create {:command ["sh" "-c" ECHO-LOOP]})]
    (transport/open! t)
    t))

(defn- with-echo [f]
  (let [t (spawn-echo)]
    (try
      (f t)
      (finally
        (transport/close! t)))))

(deftest open-close-test
  (testing "open! flips open? to true"
    (with-echo
      (fn [t]
        (is (transport/open? t)))))

  (testing "close! is idempotent and flips open? to false"
    (let [t (spawn-echo)]
      (transport/close! t)
      (is (not (transport/open? t)))
      (is (nil? (transport/close! t)))))

  (testing "Closeable interop works (with-open)"
    (with-open [t (spawn-echo)]
      (is (transport/open? t)))))

(deftest write-read-roundtrip-test
  (testing "single message roundtrips through the echo subprocess"
    (with-echo
      (fn [t]
        (let [req (jsonrpc/request 1 "ping" {:hello "world"})]
          (transport/write-message! t req)
          (let [echoed (transport/read-message! t 5000)]
            (is (= req echoed))))))))

(deftest preserves-message-ordering-test
  (testing "multiple writes are read back in order"
    (with-echo
      (fn [t]
        (let [msgs [(jsonrpc/request 1 "a" {:n 1})
                    (jsonrpc/request 2 "b" {:n 2})
                    (jsonrpc/request 3 "c" {:n 3})
                    (jsonrpc/notification "tick" {:t 1})
                    (jsonrpc/response 4 {:ok true})]]
          (doseq [m msgs]
            (transport/write-message! t m))
          (let [received (vec (repeatedly (count msgs)
                                          #(transport/read-message! t 5000)))]
            (is (= msgs received))))))))

(deftest read-timeout-test
  (testing "read-message! returns nil when no message arrives in window"
    (with-echo
      (fn [t]
        (is (nil? (transport/read-message! t 100)))))))

(deftest concurrent-writes-test
  (testing "writes from multiple threads do not interleave (lines stay intact)"
    (with-echo
      (fn [t]
        (let [n 20
              ids (range 1 (inc n))
              latch (java.util.concurrent.CountDownLatch. n)
              writers (mapv (fn [i]
                              (Thread.
                               (fn []
                                 (try
                                   (transport/write-message!
                                    t (jsonrpc/request i "concurrent"
                                                       {:i i :payload "xyz"}))
                                   (finally (.countDown latch))))))
                            ids)]
          (doseq [^Thread w writers] (.start w))
          (.await latch)
          (let [received (vec (repeatedly n #(transport/read-message! t 5000)))
                received-ids (set (map :id received))]
            (is (= (set ids) received-ids)
                "all writes arrived back, none corrupted")
            (is (every? #(= "concurrent" (:method %)) received))))))))

(deftest eof-returns-nil-test
  (testing "after process exits, read-message! returns nil rather than blocking"
    (let [t (stdio/create {:command ["sh" "-c" "echo first; exit 0"]})]
      (transport/open! t)
      (try
        ;; First the actual line — though it's not valid JSON, decode error
        ;; is delivered to the inbox as a Throwable. Drain it then read EOF.
        (let [first-msg (try
                          (transport/read-message! t 2000)
                          (catch clojure.lang.ExceptionInfo _
                            ::parse-error))]
          (is (or (= ::parse-error first-msg) (nil? first-msg))))
        ;; Subsequent reads see EOF as nil
        (is (nil? (transport/read-message! t 2000)))
        (is (nil? (transport/read-message! t 2000)))
        (finally
          (transport/close! t))))))

(deftest write-after-close-throws-test
  (testing "writing to a closed transport raises"
    (let [t (spawn-echo)]
      (transport/close! t)
      (is (thrown? clojure.lang.ExceptionInfo
                   (transport/write-message! t (jsonrpc/notification "x" nil)))))))

;; =============================================================================
;; stderr tail — a backend narrates the real failure on stderr and then answers
;; the request with a generic error, so the tail is what makes it actionable.
;; =============================================================================

(defn- await-tail
  "Poll for stderr to arrive — the drain runs on its own thread, so the line
   is not guaranteed to be buffered the instant the subprocess writes it."
  [t]
  (loop [n 0]
    (or (stdio/stderr-tail t)
        (when (< n 50)
          (Thread/sleep (long 100))
          (recur (inc n))))))

(deftest stderr-tail-test
  (testing "stderr lines are retained and readable"
    (let [t (stdio/create
             {:command ["sh" "-c" "echo 'fatal: nested session' >&2; sleep 5"]})]
      (transport/open! t)
      (try
        (is (= ["fatal: nested session"] (await-tail t)))
        (finally (transport/close! t)))))

  (testing "a transport that emitted nothing on stderr reports nil, not []"
    (with-echo
      (fn [t]
        (is (nil? (stdio/stderr-tail t))))))

  (testing "the tail is bounded to the most recent STDERR_TAIL_LINES"
    (let [n (+ stdio/STDERR_TAIL_LINES 10)
          t (stdio/create
             {:command ["sh" "-c" (str "i=1; while [ $i -le " n " ]; do "
                                       "echo line-$i >&2; i=$((i+1)); done; sleep 5")]})]
      (transport/open! t)
      (try
        ;; Give every line a chance to land before asserting on the window.
        (Thread/sleep (long 500))
        (let [tail (stdio/stderr-tail t)]
          (is (= stdio/STDERR_TAIL_LINES (count tail)))
          (is (= (str "line-" n) (last tail)))
          (is (= (str "line-" (inc (- n stdio/STDERR_TAIL_LINES))) (first tail))))
        (finally (transport/close! t)))))

  (testing "an over-long line is truncated rather than retained whole"
    (let [t (stdio/create
             {:command ["sh" "-c" (str "printf '%" (* 4 stdio/STDERR_LINE_MAX)
                                       "s\\n' x >&2; sleep 5")]})]
      (transport/open! t)
      (try
        (let [line (first (await-tail t))]
          (is (= (inc stdio/STDERR_LINE_MAX) (count line))
              "capped at STDERR_LINE_MAX plus the ellipsis")
          (is (str/ends-with? line "…")))
        (finally (transport/close! t)))))

  (testing "reopening clears the previous process's stderr"
    (let [t (stdio/create {:command ["sh" "-c" "echo first-run >&2; sleep 5"]})]
      (transport/open! t)
      (is (= ["first-run"] (await-tail t)))
      (transport/close! t)
      (transport/open! t)
      (try
        ;; The new process says the same thing; what matters is that the tail
        ;; was reset rather than accumulating both runs.
        (is (= ["first-run"] (await-tail t)))
        (finally (transport/close! t))))))
