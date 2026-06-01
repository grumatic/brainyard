;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.acp.transport-stdio-test
  "Integration tests for the stdio transport. Spawns a tiny shell echo
   loop as the peer subprocess: each line written to stdin is echoed
   back on stdout. Validates framing, parsing, EOF handling, and
   close idempotency."
  (:require [clojure.test :refer [deftest is testing]]
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
