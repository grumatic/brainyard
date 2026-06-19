;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.ask-channel.roundtrip-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [ai.brainyard.ask-channel.interface :as ask]
            [ai.brainyard.ask-channel.core.protocol :as proto]
            [ai.brainyard.ask-channel.core.server :as server])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter StringReader StringWriter]
           [java.net UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- raw-request
  "Send one raw EDN request frame to the socket and read one response — used to
   exercise ops the typed client (`ask-via-socket!`, always `:ask`) never sends."
  [path msg]
  (with-open [ch (SocketChannel/open (UnixDomainSocketAddress/of (.toPath (io/file path))))
              w  (OutputStreamWriter. (Channels/newOutputStream ch) "UTF-8")
              r  (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) "UTF-8"))]
    (proto/write-msg! w msg)
    (proto/read-msg r)))

(defn- tmp-sock-path
  "A socket path inside a throwaway dir. Unix socket paths are length-limited
   (~104 bytes on macOS), so keep the dir shallow."
  []
  (let [dir (Files/createTempDirectory "ask-ch" (make-array FileAttribute 0))]
    (str (io/file (.toFile dir) "ask.sock"))))

(deftest protocol-roundtrip
  (testing "write-msg! then read-msg preserves a map, newlines escaped"
    (let [sw (StringWriter.)
          m  {:op :ask :question "line1\nline2" :timeout-ms 5000}]
      (proto/write-msg! sw m)
      (let [rdr (BufferedReader. (StringReader. (str sw)))]
        (is (= m (proto/read-msg rdr))))))
  (testing "read-msg returns nil on EOF"
    (is (nil? (proto/read-msg (BufferedReader. (StringReader. "")))))))

(deftest server-client-roundtrip
  (testing "client ask reaches handle-fn and gets the response back"
    (let [path    (tmp-sock-path)
          seen    (atom nil)
          handler (fn [req]
                    (reset! seen req)
                    {:status :ok :answer (str "echo:" (:question req))})
          handle  (ask/start-listener! path handler)]
      (try
        (let [resp (ask/ask-via-socket! {:path path :question "hello" :timeout-ms 5000})]
          (is (= {:status :ok :answer "echo:hello"} resp))
          (is (= :ask (:op @seen)))
          (is (= "hello" (:question @seen))))
        (finally
          (ask/stop-listener! handle)))
      (testing "socket file is unlinked on stop"
        (is (not (.exists (io/file path))))))))

(deftest server-forwards-all-ops-to-handle-fn
  (testing "the transport no longer gates :ask — handle-fn owns op dispatch (§3a)"
    (let [path   (tmp-sock-path)
          handle (ask/start-listener! path (fn [{:keys [op]}] {:status :ok :echoed-op op}))]
      (try
        (is (= {:status :ok :echoed-op :status}     (raw-request path {:op :status})))
        (is (= {:status :ok :echoed-op :frobnicate} (raw-request path {:op :frobnicate})))
        (finally (ask/stop-listener! handle))))))

(deftest streaming-response-test
  (testing "a stream-response keeps the connection open and pushes multiple frames"
    (let [path    (tmp-sock-path)
          handler (fn [{:keys [op]}]
                    (if (= :sub op)
                      (ask/stream-response
                       (fn [emit! alive?]
                         (emit! {:status :ok :subscribed [:x]})
                         (emit! {:event :x :n 1})
                         (emit! {:event :x :n 2})
                         (loop [] (when (alive?) (Thread/sleep 20) (recur)))))
                      {:status :ok :echoed op}))
          handle  (ask/start-listener! path handler)]
      (try
        (with-open [ch (SocketChannel/open (UnixDomainSocketAddress/of (.toPath (io/file path))))
                    w  (OutputStreamWriter. (Channels/newOutputStream ch) "UTF-8")
                    r  (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) "UTF-8"))]
          (proto/write-msg! w {:op :sub})
          (is (= {:status :ok :subscribed [:x]} (proto/read-msg r)) "ack frame first")
          (is (= {:event :x :n 1} (proto/read-msg r)) "first event frame")
          (is (= {:event :x :n 2} (proto/read-msg r)) "second event frame"))
        ;; closing the client connection makes alive? flip → the stream loop exits;
        ;; one-shot ops still work on a fresh connection afterward.
        (is (= {:status :ok :echoed :ping} (raw-request path {:op :ping})))
        (finally (ask/stop-listener! handle))))))

(deftest unknown-op-is-handler-rejected
  (testing "op-dispatch is the handler's responsibility; transport just forwards"
    (let [path   (tmp-sock-path)
          handle (ask/start-listener!
                  path
                  (fn [{:keys [op]}]
                    (if (= :ask op)
                      {:status :ok}
                      {:status :error :error (str "unknown op: " op)})))]
      (try
        (is (= {:status :ok} (raw-request path {:op :ask})))
        (is (= {:status :error :error "unknown op: :bogus"} (raw-request path {:op :bogus})))
        (finally (ask/stop-listener! handle))))))

(deftest connect-failure-throws
  (testing "connecting to a non-existent socket throws (caller maps to friendly error)"
    (is (thrown? Exception
                 (ask/ask-via-socket! {:path (tmp-sock-path)
                                       :question "x" :timeout-ms 1000})))))

;; -- fix #1: bind-as-liveness-token (refuse to clobber a live owner) ----------

(deftest live-owner-refuses-bind
  (testing "binding over a LIVE socket is refused with {:reason :live-owner}"
    (let [path (tmp-sock-path)
          h1   (ask/start-listener! path (fn [req] {:status :ok :answer (:question req)}))]
      (try
        (let [ex (try (ask/start-listener! path (fn [_] {:status :ok}))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "second bind while first is live must throw")
          (is (= :live-owner (:reason (ex-data ex)))))
        ;; the original owner is untouched and still answers
        (is (= "alive" (:answer (ask/ask-via-socket! {:path path :question "alive" :timeout-ms 2000}))))
        (finally (ask/stop-listener! h1)))
      (testing "after the owner stops, the path is free for a new owner"
        (let [h2 (ask/start-listener! path (fn [req] {:status :ok :answer (:question req)}))]
          (try
            (is (= "fresh" (:answer (ask/ask-via-socket! {:path path :question "fresh" :timeout-ms 2000}))))
            (finally (ask/stop-listener! h2))))))))

(deftest stale-socket-file-does-not-block-bind
  (testing "a leftover (non-live) file at the path is replaced, not refused"
    (let [path (tmp-sock-path)]
      (spit (io/file path) "stale")               ; a file with nobody listening
      (is (.exists (io/file path)))
      (let [h (ask/start-listener! path (fn [req] {:status :ok :answer (:question req)}))]
        (try
          (is (= "ok" (:answer (ask/ask-via-socket! {:path path :question "ok" :timeout-ms 2000}))))
          (finally (ask/stop-listener! h)))))))

;; -- fix #2: stop-by-identity (don't delete a successor's socket) -------------

(deftest should-unlink-logic
  (testing "unlink only the file we bound — never a successor's, never a gone file"
    (is (true?  (#'server/should-unlink? :k :k))   "same identity → ours, unlink")
    (is (false? (#'server/should-unlink? :k :other)) "different identity → successor's, keep")
    (is (false? (#'server/should-unlink? :k nil))  "file already gone → nothing to unlink")
    (is (true?  (#'server/should-unlink? nil :k))  "no fingerprint captured → legacy cleanup")))
