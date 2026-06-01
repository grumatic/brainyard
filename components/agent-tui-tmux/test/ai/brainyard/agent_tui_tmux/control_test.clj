;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-tmux.control-test
  (:require [ai.brainyard.agent-tui-tmux.interface :as tmux]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io BufferedReader StringReader StringWriter PrintWriter]
           [java.nio.file Files attribute.FileAttribute]))

(defn- tmp-socket-path ^String []
  (let [dir (.toFile (Files/createTempDirectory "agent-tui-control-"
                                                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.getAbsolutePath (clojure.java.io/file dir "control.sock"))))

(deftest frame-roundtrip-test
  (testing "write/read frames round-trip preserve :type and payload"
    (let [sw  (StringWriter.)
          pw  (PrintWriter. sw true)
          msg (tmux/control-msg-input "hello world")]
      (tmux/control-write-frame! pw msg)
      (let [br (BufferedReader. (StringReader. (.toString sw)))]
        (is (= msg (tmux/control-read-frame br))))))
  (testing "malformed frame returns :malformed"
    (let [br (BufferedReader. (StringReader. "{not edn at all\n"))]
      (is (= :malformed (tmux/control-read-frame br)))))
  (testing "non-map frames are :malformed"
    (let [br (BufferedReader. (StringReader. "[1 2 3]\n"))]
      (is (= :malformed (tmux/control-read-frame br)))))
  (testing "EOF on empty stream"
    (let [br (BufferedReader. (StringReader. ""))]
      (is (= :eof (tmux/control-read-frame br))))))

(deftest message-constructors-test
  (is (= :hello (:type (tmux/control-msg-hello))))
  (is (= :hello-ack (:type (tmux/control-msg-hello-ack {:session-id "agt-1"}))))
  (is (= "agt-1" (:session-id (tmux/control-msg-hello-ack {:session-id "agt-1"}))))
  (is (= :input (:type (tmux/control-msg-input "x"))))
  (is (= :slash (:type (tmux/control-msg-slash "/help" ""))))
  (is (= :popup (:type (tmux/control-msg-popup {}))))
  (is (= :popup-result (:type (tmux/control-msg-popup-result "id" :submitted {})))))

(deftest server-client-handshake-test
  (testing "client connects, server receives hello, replies with hello-ack"
    (let [path     (tmp-socket-path)
          received (atom [])
          server   (tmux/control-server-start!
                    {:path path
                     :on-message (fn [conn msg]
                                   (swap! received conj msg)
                                   (when (= :hello (:type msg))
                                     (tmux/control-server-send!
                                      conn (tmux/control-msg-hello-ack {:session-id "agt-test"}))))})
          client   (tmux/control-client-connect! {:path (:path server)})]
      (try
        (let [reply (tmux/control-request-response! client (tmux/control-msg-hello))]
          (is (= :hello-ack (:type reply)))
          (is (= "agt-test" (:session-id reply))))
        (Thread/sleep 50) ; let server thread record the message
        (is (= [:hello] (mapv :type @received)))
        (finally
          ((:close! client))
          ((:stop! server)))))))

(deftest multiple-frames-test
  (testing "client sends multiple frames; server processes each"
    (let [path     (tmp-socket-path)
          received (atom [])
          server   (tmux/control-server-start!
                    {:path path
                     :on-message (fn [_ msg] (swap! received conj msg))})
          client   (tmux/control-client-connect! {:path (:path server)})]
      (try
        ((:send! client) (tmux/control-msg-input "first"))
        ((:send! client) (tmux/control-msg-input "second"))
        ((:send! client) (tmux/control-msg-cancel))
        (Thread/sleep 100)
        (is (= [:input :input :cancel] (mapv :type @received)))
        (is (= "first"  (-> @received (nth 0) :line)))
        (is (= "second" (-> @received (nth 1) :line)))
        (finally
          ((:close! client))
          ((:stop! server)))))))

(deftest server-stop-disconnects-clients
  (let [path   (tmp-socket-path)
        server (tmux/control-server-start! {:path path :on-message (fn [_ _])})
        client (tmux/control-client-connect! {:path (:path server)})]
    (try
      (is (true? ((:open? client))))
      ((:stop! server))
      (Thread/sleep 100)
      ;; The client recv should now return :eof (server closed channel).
      (let [r (try ((:recv client)) (catch Throwable _ :threw))]
        (is (#{:eof :threw} r)))
      (finally
        ((:close! client))))))

(deftest stop-server-deletes-socket-file
  (let [path   (tmp-socket-path)
        server (tmux/control-server-start! {:path path :on-message (fn [_ _])})]
    (is (.exists (clojure.java.io/file (:path server))))
    ((:stop! server))
    (Thread/sleep 50)
    (is (not (.exists (clojure.java.io/file (:path server)))))))
