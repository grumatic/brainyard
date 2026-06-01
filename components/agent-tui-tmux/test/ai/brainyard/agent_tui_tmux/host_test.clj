;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-tmux.host-test
  (:require [ai.brainyard.agent-tui-tmux.interface :as tmux]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files attribute.FileAttribute]
           [java.util.concurrent CompletableFuture TimeUnit]))

(defn- tmp-socket-path ^String []
  (let [dir (.toFile (Files/createTempDirectory "agent-tui-host-"
                                                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.getAbsolutePath (clojure.java.io/file dir "control.sock"))))

(deftest host-attaches-on-hello
  (let [path     (tmp-socket-path)
        attaches (atom 0)
        host     (tmux/host-start!
                  {:path path
                   :session-id "agt-1"
                   :on-attach (fn [_] (swap! attaches inc))})
        client   (tmux/control-client-connect! {:path (:path host)})]
    (try
      (let [reply (tmux/control-request-response! client (tmux/control-msg-hello))]
        (is (= :hello-ack (:type reply)))
        (is (= "agt-1" (:session-id reply))))
      (Thread/sleep 80)
      (is (= 1 @attaches))
      (is ((:attached? host)))
      (finally
        ((:close! client))
        ((:stop! host))))))

(deftest host-popup-roundtrip
  (let [path   (tmp-socket-path)
        host   (tmux/host-start! {:path path :session-id "agt-1"})
        client (tmux/control-client-connect! {:path (:path host)})]
    (try
      ;; Client says hello first so the host has an attached connection.
      ((:send! client) (tmux/control-msg-hello))
      ;; Drain the hello-ack.
      ((:recv client))
      ;; Host emits a popup; it should arrive at the client.
      (let [q  (tmux/permission-questionnaire {:tool "bash.run" :path "/x"})
            ^CompletableFuture cf ((:emit-popup! host) q)
            popup-msg ((:recv client))]
        (is (= :popup (:type popup-msg)))
        (is (= (:id q) (-> popup-msg :questionnaire :id)))
        ;; Client replies with a popup-result; the future should resolve.
        ((:send! client) (tmux/control-msg-popup-result (:id q) :submitted
                                                        {:decision {:value :yes}}))
        (let [reply (.get cf 2 TimeUnit/SECONDS)]
          (is (= :submitted (:status reply)))
          (is (= :yes (-> reply :answers :decision :value)))))
      (finally
        ((:close! client))
        ((:stop! host))))))

(deftest host-input-routing
  (let [path     (tmp-socket-path)
        received (atom [])
        host     (tmux/host-start!
                  {:path path
                   :session-id "agt-x"
                   :on-input (fn [kind msg] (swap! received conj [kind (:line msg)]))})
        client   (tmux/control-client-connect! {:path (:path host)})]
    (try
      ((:send! client) (tmux/control-msg-hello))
      ((:recv client))
      ((:send! client) (tmux/control-msg-input "type-this"))
      ((:send! client) (tmux/control-msg-cancel))
      (Thread/sleep 100)
      (is (= [[:input "type-this"] [:cancel nil]] @received))
      (finally
        ((:close! client))
        ((:stop! host))))))

(deftest host-emit-when-detached-is-queued
  (let [path (tmp-socket-path)
        host (tmux/host-start! {:path path :session-id "agt-q"})]
    (try
      ;; No client connected yet — emit while detached.
      (is (false? (tmux/host-emit-status! (:host host) "left" "right")))
      ;; Connect and say hello; the queued status should be drained.
      (let [client (tmux/control-client-connect! {:path (:path host)})]
        (try
          ((:send! client) (tmux/control-msg-hello))
          ;; Read frames until we see the status (after hello-ack).
          (let [a ((:recv client))
                b ((:recv client))]
            (is (= :hello-ack (:type a)))
            (is (= :status (:type b)))
            (is (= "left" (:left b))))
          (finally
            ((:close! client)))))
      (finally
        ((:stop! host))))))

(deftest host-broadcast-reaches-all-attached-clients
  (testing "broadcast! delivers to every connected client, not just active-conn"
    (let [path (tmp-socket-path)
          host (tmux/host-start! {:path path :session-id "agt-bcast"})
          a    (tmux/control-client-connect! {:path (:path host)})
          b    (tmux/control-client-connect! {:path (:path host)})]
      (try
        ;; Both clients say hello.  After the second hello, b is the
        ;; active-conn — but because we no longer close-connection! on
        ;; hello displacement, a stays open.
        ((:send! a) (tmux/control-msg-hello))
        (is (= :hello-ack (:type ((:recv a)))))
        ((:send! b) (tmux/control-msg-hello))
        (is (= :hello-ack (:type ((:recv b)))))
        (Thread/sleep 80)
        ;; Broadcast a :client-slash frame — both clients should receive
        ;; it.  emit! (active-conn only) would drop a.
        (let [delivered (tmux/host-broadcast!
                         (:host host)
                         (tmux/control-msg-client-slash "/activity" "show"))]
          (is (= 2 delivered) "broadcast hit both attached clients")
          (let [msg-a ((:recv a))
                msg-b ((:recv b))]
            (is (= :client-slash (:type msg-a)))
            (is (= "/activity"   (:command msg-a)))
            (is (= :client-slash (:type msg-b)))
            (is (= "/activity"   (:command msg-b)))))
        (finally
          ((:close! a))
          ((:close! b))
          ((:stop! host)))))))

(deftest popup-result-fires-on-popup-result-callback
  (testing "answered popups invoke :on-popup-result so daemon can clear pending-dialogs"
    (let [path  (tmp-socket-path)
          fired (atom [])
          host  (tmux/host-start!
                 {:path path :session-id "agt-pr"
                  :on-popup-result (fn [reply] (swap! fired conj reply))})
          c     (tmux/control-client-connect! {:path (:path host)})]
      (try
        ((:send! c) (tmux/control-msg-hello))
        (is (= :hello-ack (:type ((:recv c)))))
        (let [q (tmux/permission-questionnaire {:tool "rm" :path "/x"})]
          ((:emit-popup! host) q)
          ((:recv c)) ;; drain the popup frame
          ((:send! c) (tmux/control-msg-popup-result (:id q) :submitted
                                                     {:decision {:value :yes}}))
          (Thread/sleep 80)
          (is (= 1 (count @fired)))
          (is (= (:id q) (:id (first @fired))))
          (is (= :submitted (:status (first @fired)))))
        (finally
          ((:close! c))
          ((:stop! host)))))))

(deftest emit-popup-stamps-render-mode-from-quiet-flag
  (testing "popup-quiet=false stamps :render-mode :tmux; toggling to true flips it"
    (let [path (tmp-socket-path)
          host (tmux/host-start! {:path path :session-id "agt-rm"})
          c    (tmux/control-client-connect! {:path (:path host)})]
      (try
        ((:send! c) (tmux/control-msg-hello))
        (is (= :hello-ack (:type ((:recv c)))))
        ;; Default flag is false → :tmux
        (let [q (tmux/permission-questionnaire {:tool "rm" :path "/x"})]
          ((:emit-popup! host) q)
          (let [m ((:recv c))]
            (is (= :popup (:type m)))
            (is (= :tmux (:render-mode m)))))
        ;; Flip the flag → next popup arrives with :inline
        ((:set-popup-quiet! host) true)
        (is (true? ((:popup-quiet? host))))
        (let [q (tmux/permission-questionnaire {:tool "rm" :path "/y"})]
          ((:emit-popup! host) q)
          (let [m ((:recv c))]
            (is (= :inline (:render-mode m)))))
        (finally
          ((:close! c))
          ((:stop! host)))))))

(deftest emit-popup-broadcasts-to-all-clients
  (testing "popup is delivered to every attached client (orchestrator + REPL pane)"
    (let [path (tmp-socket-path)
          host (tmux/host-start! {:path path :session-id "agt-bp"})
          a    (tmux/control-client-connect! {:path (:path host)})
          b    (tmux/control-client-connect! {:path (:path host)})]
      (try
        ((:send! a) (tmux/control-msg-hello))
        (is (= :hello-ack (:type ((:recv a)))))
        ((:send! b) (tmux/control-msg-hello))
        (is (= :hello-ack (:type ((:recv b)))))
        (Thread/sleep 50)
        (let [q (tmux/permission-questionnaire {:tool "rm" :path "/x"})]
          ((:emit-popup! host) q)
          (let [ma ((:recv a))
                mb ((:recv b))]
            (is (= :popup (:type ma)))
            (is (= :popup (:type mb)))
            (is (= (:id q) (-> ma :questionnaire :id)))
            (is (= (:id q) (-> mb :questionnaire :id)))))
        (finally
          ((:close! a))
          ((:close! b))
          ((:stop! host)))))))

(deftest host-hello-displacement-does-not-close-prior-conn
  (testing "the prior active-conn stays OPEN when a new :hello arrives"
    (let [path (tmp-socket-path)
          host (tmux/host-start! {:path path :session-id "agt-disp"})
          orch (tmux/control-client-connect! {:path (:path host)})
          repl (tmux/control-client-connect! {:path (:path host)})]
      (try
        ((:send! orch) (tmux/control-msg-hello))
        (is (= :hello-ack (:type ((:recv orch)))))
        ;; A second hello displaces orch as active-conn but must not
        ;; close orch's socket — the orchestrator legitimately stays
        ;; attached as a passive listener for `:client-slash` frames.
        ((:send! repl) (tmux/control-msg-hello))
        (is (= :hello-ack (:type ((:recv repl)))))
        (Thread/sleep 80)
        (is ((:open? orch))
            "orchestrator's connection is still open after REPL pane attached")
        (finally
          ((:close! orch))
          ((:close! repl))
          ((:stop! host)))))))
