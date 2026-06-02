;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui-persist.restore-test
  (:require [ai.brainyard.agent-tui-persist.interface :as persist]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]
           [java.nio.file Files]))

(def ^:dynamic *tmp-root* nil)

(defn- with-tmp-root [f]
  (let [tmp (.toFile (Files/createTempDirectory "agent-tui-persist-restore-test"
                                                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (binding [*tmp-root* tmp]
        (persist/with-root tmp (f)))
      (finally
        (doseq [^File f' (reverse (file-seq tmp))]
          (.delete f'))))))

(use-fixtures :each with-tmp-root)

(deftest restore-empty-session
  (testing "no files on disk returns a fresh-shaped map keyed by session-id"
    ;; ensure dir exists so the restore call has a target — but no files written
    (persist/session-dir "agt-empty")
    (let [m (persist/restore-session-map "agt-empty")]
      (is (= "agt-empty" (:session-id m)))
      (is (= [] (:messages m)))
      (is (= [] (:agent-activity m)))
      (is (= 0 (:total-turns m)))
      (is (= {} (:config m)))
      (is (= {:traces [] :todo-info nil :artifacts [] :cache-hits 0 :exceptions []}
             (:data m)))
      (is (number? (:created-at m)))
      (is (number? (:updated-at m))))))

(deftest restore-from-messages-log-only
  (testing "messages.log :message events fold into :messages, in order; other kinds skipped"
    (persist/append-event! "agt-m" {:kind :agent.ask/pre :payload {:msg "hi"}})
    (persist/append-event! "agt-m" {:kind :message :payload {:role "user" :content "hi"}})
    (persist/append-event! "agt-m" {:kind :agent.tool-use/pre :payload {:tool "x"}})
    (persist/append-event! "agt-m" {:kind :message :payload {:role "assistant" :content "hello"}})
    (let [m (persist/restore-session-map "agt-m")]
      (is (= [{:role "user" :content "hi"}
              {:role "assistant" :content "hello"}]
             (:messages m)))
      ;; No session.edn → other fields default-shaped
      (is (= 0 (:total-turns m))))))

(deftest restore-from-session-snap-only
  (testing "session.edn populates everything except :messages"
    (persist/write-snap! "agt-s" :session
                         {:session-id "agt-s"
                          :user-id    "u-1"
                          :config     {:lm-config {:provider :anthropic}}
                          :data       {:traces [{:t 1}]
                                       :todo-info {:tasks []}
                                       :artifacts []
                                       :cache-hits 2
                                       :exceptions []}
                          :agent-activity [{:seq 1 :kind :sub-agent}]
                          :agent-activity-seq 1
                          :total-turns 5
                          :created-at 100
                          :updated-at 200})
    (let [m (persist/restore-session-map "agt-s")]
      (is (= "u-1" (:user-id m)))
      (is (= 5 (:total-turns m)))
      (is (= 1 (:agent-activity-seq m)))
      (is (= [{:seq 1 :kind :sub-agent}] (:agent-activity m)))
      (is (= {:lm-config {:provider :anthropic}} (:config m)))
      (is (= 2 (get-in m [:data :cache-hits])))
      (is (= 100 (:created-at m)))
      ;; :messages defaults to [] when no log exists
      (is (= [] (:messages m))))))

(deftest restore-merges-snap-and-log
  (testing "session.edn supplies fields, messages.log supplies :messages"
    (persist/write-snap! "agt-x" :session
                         {:user-id    "u-2"
                          :config     {:foo :bar}
                          :data       {:traces [] :todo-info nil :artifacts []
                                       :cache-hits 0 :exceptions []}
                          :agent-activity []
                          :agent-activity-seq 0
                          :total-turns 3
                          :created-at 1000
                          :updated-at 2000})
    (persist/append-event! "agt-x" {:kind :message :payload {:role "user" :content "q1"}})
    (persist/append-event! "agt-x" {:kind :message :payload {:role "assistant" :content "a1"}})
    (let [m (persist/restore-session-map "agt-x")]
      (is (= "agt-x" (:session-id m)) "session-id from arg wins")
      (is (= "u-2" (:user-id m)))
      (is (= {:foo :bar} (:config m)))
      (is (= 3 (:total-turns m)))
      (is (= 2 (count (:messages m))))
      (is (= "q1" (-> m :messages first :content)))
      (is (= "a1" (-> m :messages second :content))))))

(deftest restore-prefers-arg-session-id
  (testing "even if session.edn has a stale :session-id, the arg wins"
    (persist/write-snap! "agt-canonical" :session
                         {:session-id "agt-stale"
                          :user-id    "u"
                          :total-turns 1})
    (let [m (persist/restore-session-map "agt-canonical")]
      (is (= "agt-canonical" (:session-id m))))))

(deftest restore-uses-meta-user-id-fallback
  (testing "when session.edn is missing, meta.edn supplies :user-id"
    (persist/save-meta! "agt-meta" {:user-id "u-from-meta" :agent-id :coact-agent})
    (let [m (persist/restore-session-map "agt-meta")]
      (is (= "u-from-meta" (:user-id m))))))

(deftest restore-skips-malformed-events
  (testing "a corrupt line in messages.log does not abort the read"
    ;; Write valid then a corrupt line then valid via raw I/O.
    (let [f (persist/session-file "agt-bad" "messages.log")]
      (persist/append-event! "agt-bad" {:kind :message :payload {:role "user" :content "ok1"}})
      (spit f "{not edn at all\n" :append true)
      (persist/append-event! "agt-bad" {:kind :message :payload {:role "user" :content "ok2"}}))
    (let [m (persist/restore-session-map "agt-bad")]
      (is (= ["ok1" "ok2"] (mapv :content (:messages m)))))))
