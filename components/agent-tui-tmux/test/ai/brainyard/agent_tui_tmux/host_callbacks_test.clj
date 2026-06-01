;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent-tui-tmux.host-callbacks-test
  (:require [ai.brainyard.agent-tui-tmux.interface :as tmux]
            [clojure.test :refer [deftest is testing]])
  (:import [java.util.concurrent CompletableFuture TimeUnit]))

(defn- tmp-socket-path ^String []
  (let [base (clojure.java.io/file "/tmp" (str "by-cb-" (System/nanoTime)))]
    (.mkdirs base)
    (.getAbsolutePath (clojure.java.io/file base "c.sock"))))

(defn- with-host-and-replier
  "Run `f` with a host handle attached to a fake by-ui that auto-replies to
   every popup with `reply-builder` (a fn `[questionnaire] → reply-map`)."
  [reply-builder f]
  (let [path     (tmp-socket-path)
        host     (tmux/host-start! {:path path :session-id "agt-cb"})
        client   (tmux/control-client-connect! {:path (:path host)})
        stop?    (atom false)
        reader   (Thread.
                  (fn []
                    ((:send! client) (tmux/control-msg-hello))
                    (try
                      (loop []
                        (when-not @stop?
                          (let [msg ((:recv client))]
                            (cond
                              (= :eof msg)         nil
                              (= :popup (:type msg))
                              (do
                                ((:send! client)
                                 (let [r (reply-builder (:questionnaire msg))]
                                   (tmux/control-msg-popup-result
                                    (-> msg :questionnaire :id)
                                    (:status r) (:answers r))))
                                (recur))
                              :else (recur)))))
                      (catch Throwable _)))
                  "host-callback-fake-ui")]
    (.setDaemon reader true)
    (.start reader)
    (try
      (Thread/sleep 80)
      (f (:host host))
      (finally
        (reset! stop? true)
        ((:close! client))
        ((:stop! host))))))

(deftest permission-fn-yes
  (with-host-and-replier
    (fn [_q] {:status :submitted :answers {:decision {:value :yes}}})
    (fn [host]
      (let [pfn (tmux/make-host-permission-fn {:host host})]
        (is (= {:allowed true} (pfn {:tool "bash.run" :path "/x"})))))))

(deftest permission-fn-no
  (with-host-and-replier
    (fn [_q] {:status :submitted :answers {:decision {:value :no}}})
    (fn [host]
      (let [pfn (tmux/make-host-permission-fn {:host host})
            r   (pfn {:tool "bash.run" :path "/x"})]
        (is (true? (:denied r)))
        (is (string? (:reason r)))))))

(deftest permission-fn-always-caches
  (with-host-and-replier
    (fn [_q] {:status :submitted :answers {:decision {:value :always}}})
    (fn [host]
      (let [!allowed (atom #{})
            pfn (tmux/make-host-permission-fn {:host host :!allowed-dirs !allowed})]
        (is (= {:allowed true} (pfn {:tool "bash.run" :path "/dir/file"})))
        (is (contains? @!allowed "/dir"))
        ;; Subsequent request to a path under same parent does NOT pop a popup.
        (is (= {:allowed true} (pfn {:tool "bash.run" :path "/dir/another"})))))))

(deftest permission-fn-cancelled
  (with-host-and-replier
    (fn [_q] {:status :cancelled :answers {}})
    (fn [host]
      (let [pfn (tmux/make-host-permission-fn {:host host})
            r   (pfn {:tool "bash.run" :path "/x"})]
        (is (true? (:denied r)))))))

(deftest user-feedback-fn-returns-selected-value
  (with-host-and-replier
    (fn [_q] {:status :submitted :answers {:feedback {:value 1}}})
    (fn [host]
      (let [ufn (tmux/make-host-user-feedback-fn {:host host})]
        (is (= 1 (ufn {:question "Pick" :options ["a" "b" "c"]})))))))

(deftest user-feedback-fn-cancelled-returns-nil
  (with-host-and-replier
    (fn [_q] {:status :cancelled :answers {}})
    (fn [host]
      (let [ufn (tmux/make-host-user-feedback-fn {:host host})]
        (is (nil? (ufn {:question "Pick" :options ["a" "b"]})))))))

(deftest user-feedback-fn-tabs-passes-through
  (with-host-and-replier
    (fn [_q] {:status :submitted
              :answers {:strategy {:value :run}
                        :backup   {:value :yes}}})
    (fn [host]
      (let [ufn (tmux/make-host-user-feedback-fn {:host host})
            tabs [{:id :strategy :prompt "How?" :type :radio :required? true
                   :options [{:value :run :label "Run"} {:value :skip :label "Skip"}]}
                  {:id :backup :prompt "Backup?" :type :radio
                   :options [{:value :yes :label "Yes"} {:value :no :label "No"}]}]
            answers (ufn {:title "Strategy" :tabs tabs})]
        (is (= :run (-> answers :strategy :value)))
        (is (= :yes (-> answers :backup :value)))))))
