;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.gateway-test
  "Tests for the messaging gateway core (R3): pairing-code flow, identity
   resolution, and the router (executor + transport stubbed)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.brainyard.agent.common.gateway :as gw]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:dynamic *pdir* nil)

(defn temp-dir-fixture [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "gw-test-" (System/currentTimeMillis) "-" (rand-int 100000)))]
    (.mkdirs dir)
    (binding [*pdir* (.getPath dir)]
      (try (f)
           (finally (doseq [^java.io.File x (reverse (file-seq dir))] (.delete x)))))))

(use-fixtures :each temp-dir-fixture)

;; In-memory transport stub (test-only; real adapters live outside src for now).
(defrecord StubTransport [inbound outbound]
  gw/Transport
  (poll [_] (let [m @inbound] (reset! inbound []) m))
  (send-reply! [_ chat-id text] (swap! outbound conj [chat-id text])))

(defn- stub [] (->StubTransport (atom []) (atom [])))
(defn- push! [t msg] (swap! (:inbound t) conj msg))
(defn- replies [t] @(:outbound t))

;; ============================================================================
;; Pairing codes
;; ============================================================================

(deftest pairing-happy-path
  (let [{:keys [code]} (gw/generate-code! *pdir* {:user-id "jake" :project-root "/proj"})]
    (is (string? code))
    (testing "pairing with the code creates a persistent pairing carrying identity"
      (let [p (gw/pair! *pdir* "tg:42" code)]
        (is (= "jake" (:user-id p)))
        (is (= "/proj" (:project-root p)))
        (is (= "gateway-tg:42" (:session-id p)))
        (is (= p (gw/resolve-user *pdir* "tg:42")))))
    (testing "code is single-use (consumed)"
      (is (= "invalid pairing code" (:error (gw/pair! *pdir* "tg:99" code)))))))

(deftest pairing-rejects-bad-and-expired
  (testing "wrong code"
    (is (:error (gw/pair! *pdir* "tg:1" "ZZZZZZ"))))
  (testing "expired code"
    (let [{:keys [code]} (gw/generate-code! *pdir* {:ttl-ms -1000})]   ;; already expired
      (is (= "pairing code expired" (:error (gw/pair! *pdir* "tg:1" code))))))
  (testing "case-insensitive + trims"
    (let [{:keys [code]} (gw/generate-code! *pdir* {})]
      (is (map? (gw/pair! *pdir* "tg:2" (str "  " (str/lower-case code) "  ")))))))

(deftest list-and-unpair
  (gw/pair! *pdir* "tg:7" (:code (gw/generate-code! *pdir* {:user-id "u"})))
  (is (= ["tg:7"] (mapv :platform-user-id (gw/list-pairings *pdir*))))
  (is (true? (gw/unpair! *pdir* "tg:7")))
  (is (nil? (gw/resolve-user *pdir* "tg:7")))
  (is (empty? (gw/list-pairings *pdir*))))

;; ============================================================================
;; Router
;; ============================================================================

(deftest router-paired-runs-turn
  (gw/pair! *pdir* "tg:5" (:code (gw/generate-code! *pdir* {:user-id "jake"})))
  (let [t (stub)
        seen (atom nil)]
    (binding [gw/*run-turn* (fn [pairing text] (reset! seen [pairing text]) (str "echo: " text))]
      (let [outcome (gw/handle-message *pdir* t {:platform-user-id "tg:5" :chat-id "c5" :text "hello"})]
        (is (= :answered outcome))
        (is (= "hello" (second @seen)))
        (is (= "jake" (:user-id (first @seen))))
        (is (= "gateway-tg:5" (:session-id (first @seen))) "stable per-user session for continuity")
        (is (= [["c5" "echo: hello"]] (replies t)))))))

(deftest router-unpaired-pairs-or-prompts
  (testing "unpaired user sending a valid code → paired + welcome"
    (let [{:keys [code]} (gw/generate-code! *pdir* {:user-id "u"})
          t (stub)
          outcome (gw/handle-message *pdir* t {:platform-user-id "tg:8" :chat-id "c8" :text code})]
      (is (= :paired outcome))
      (is (re-find #"Paired" (second (first (replies t)))))
      (is (some? (gw/resolve-user *pdir* "tg:8")))))
  (testing "unpaired user sending junk → pairing prompt, not served"
    (let [t (stub)
          outcome (gw/handle-message *pdir* t {:platform-user-id "tg:9" :chat-id "c9" :text "hi there"})]
      (is (= :unpaired outcome))
      (is (re-find #"not paired" (second (first (replies t)))))
      (is (nil? (gw/resolve-user *pdir* "tg:9"))))))

(deftest run-once-drains-transport
  (gw/pair! *pdir* "tg:5" (:code (gw/generate-code! *pdir* {})))
  (let [t (stub)]
    (push! t {:platform-user-id "tg:5" :chat-id "c5" :text "a"})
    (push! t {:platform-user-id "tg:5" :chat-id "c5" :text "b"})
    (binding [gw/*run-turn* (fn [_ text] (str "r:" text))]
      (is (= 2 (gw/run-once! *pdir* t)))
      (is (= [["c5" "r:a"] ["c5" "r:b"]] (replies t)))
      (is (empty? (gw/poll t)) "transport drained"))))
