;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-nrepl.server-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.clj-nrepl.interface :as n]))

(defn- with-running-server [t]
  (try
    (n/start-server! :bind "127.0.0.1" :port 0)
    (t)
    (finally
      (try (n/stop-server!) (catch Exception _)))))

(use-fixtures :each with-running-server)

;; --- baseline -------------------------------------------------------------

(deftest server-lifecycle
  (is (n/running?))
  (is (pos? (n/server-port))))

(deftest non-loopback-bind-rejected
  (n/stop-server!)
  (try
    (is (thrown? Exception (n/start-server! :bind "0.0.0.0" :port 0)))
    (finally
      (n/start-server! :bind "127.0.0.1" :port 0))))

(deftest eval-roundtrip
  (let [r (n/eval-string "(+ 1 2)")]
    (is (= "3" (:result r)))
    (is (nil? (:error r)))
    (is (= "user" (:ns r))))
  (let [r (n/eval-string "(println \"hello\") :done")]
    (is (= ":done" (:result r)))
    (is (re-find #"hello" (:output r)))))

(deftest session-isolation
  (let [s1 (n/new-session)
        s2 (n/new-session)]
    (try
      (is (and (string? s1) (string? s2) (not= s1 s2)))
      (finally
        (n/close-session s1)
        (n/close-session s2)))))

;; --- gate: deny-list is the only check -----------------------------------

(deftest deny-list-rejected
  (let [r (n/eval-string "(System/exit 0)")]
    (is (some? (:error r)))
    (is (re-find #"denied by clj-nrepl allow/deny" (:error r)))))

(deftest mutation-allowed-full-trust
  ;; nREPL is full-trust: a def evaluates on the live runtime, no grant /
  ;; scope / confirmation gating, no drift marking — only the deny-list applies.
  (let [sid (n/new-session)]
    (try
      (let [r (n/eval-string "(def full-trust-probe 42)" :session sid)]
        (is (nil? (:error r)) (str "mutation must not be gated: " (:error r)))
        (is (= "#'user/full-trust-probe" (:result r))))
      (finally
        (n/close-session sid)))))

;; --- server-not-running gate ----------------------------------------------

(deftest eval-rejected-when-server-down
  (n/stop-server!)
  (try
    (let [r (n/eval-string "(+ 1 2)")]
      (is (re-find #"server is not running" (:error r))))
    (finally
      (n/start-server! :bind "127.0.0.1" :port 0))))
