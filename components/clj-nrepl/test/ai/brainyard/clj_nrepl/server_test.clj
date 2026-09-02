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

(deftest timed-out-eval-reports-an-error
  ;; REGRESSION: on a client timeout nrepl.core just ends the response seq —
  ;; no error, no terminal status — so the fold returned {:result nil :error
  ;; nil} and a timed-out eval was indistinguishable from an expression that
  ;; legitimately returned nil. The LLM got a blank entry. Harmless while the
  ;; ceiling was a hardcoded hour; reachable now that :nrepl-eval-timeout-ms
  ;; makes it tunable.
  (testing "a truncated round-trip is an error, naming the deadline"
    (let [r (n/eval-string "(Thread/sleep 20000)" :timeout-ms 1000)]
      (is (some? (:error r)) "a timeout must not look like a nil result")
      (is (re-find #"did not complete" (:error r)))
      (is (re-find #"1000ms" (:error r)) "the deadline is named")))

  (testing "a completed eval is untouched"
    (let [r (n/eval-string "(+ 1 1)" :timeout-ms 10000)]
      (is (= "2" (:result r)))
      (is (nil? (:error r)))))

  (testing "a real eval error keeps its own message, not the timeout one"
    ;; A failing eval still arrives with "done", so the timeout branch must
    ;; not fire and mask it.
    (let [r (n/eval-string "(throw (ex-info \"kaboom\" {}))" :timeout-ms 10000)]
      (is (some? (:error r)))
      (is (not (re-find #"did not complete" (:error r))))
      (is (re-find #"kaboom" (str (:error r) (:output r)))))))

(deftest on-session-surfaces-the-id
  ;; The id of a session the caller did NOT pin used to be unreachable: it was
  ;; cloned inside nrepl.core/client-session and never escaped, so anything
  ;; wanting to address a running eval (interrupt, inspect) had nothing to name
  ;; it by. It must arrive BEFORE the eval settles — in the result map it would
  ;; be one moment too late to be useful.
  (testing "an unpinned eval reports the session it cloned"
    (let [seen (atom nil)
          r    (n/eval-string "(+ 20 22)" :on-session #(reset! seen %))]
      (is (= "42" (:result r)))
      (is (string? @seen) "the cloned session id is surfaced")))

  (testing "a pinned session is reported unchanged"
    (let [sid  (n/new-session)
          seen (atom nil)]
      (try
        (n/eval-string "(+ 1 1)" :session sid :on-session #(reset! seen %))
        (is (= sid @seen))
        (finally (n/close-session sid)))))

  (testing "a throwing on-session cannot fail the eval"
    (let [r (n/eval-string "(+ 2 3)" :on-session (fn [_] (throw (ex-info "boom" {}))))]
      (is (= "5" (:result r)))
      (is (nil? (:error r))))))

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
