;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp.env-test
  "Env normalization (lenient, for builders) and strict spawn-boundary
   validation (for transport.stdio/open!)."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.acp.core.env :as env]
            [ai.brainyard.acp.core.transport :as transport]
            [ai.brainyard.acp.core.transport.stdio :as stdio]))

;; =============================================================================
;; Lenient coercion
;; =============================================================================

(deftest normalize-key-strips-the-colon-test
  (testing "keyword/symbol keys contribute only their name"
    (is (= "ANTHROPIC_MODEL" (env/normalize-key :ANTHROPIC_MODEL)))
    (is (= "ANTHROPIC_MODEL" (env/normalize-key 'ANTHROPIC_MODEL)))
    (is (= "ANTHROPIC_MODEL" (env/normalize-key "  ANTHROPIC_MODEL  ")))
    (is (= "" (env/normalize-key nil)))))

(deftest normalize-val-strips-the-colon-test
  (is (= "claude-opus-5" (env/normalize-val :claude-opus-5)))
  (is (= "claude-opus-5" (env/normalize-val "claude-opus-5")))
  (is (= "5" (env/normalize-val 5))))

(deftest normalize-map-test
  (testing "keyword keys and values both normalize"
    (is (= {"ANTHROPIC_MODEL" "claude-opus-5"}
           (env/normalize {:ANTHROPIC_MODEL :claude-opus-5}))))
  (testing "blank keys and nil values are dropped"
    (is (= {"A" "1"} (env/normalize {"A" "1" "   " "x" :B nil}))))
  (testing "nil env normalizes to an empty map"
    (is (= {} (env/normalize nil))))
  (testing "a :K / \"K\" collision resolves deterministically — string wins"
    (is (= {"K" "string-wins"} (env/normalize {:K "kw" "K" "string-wins"})))
    (is (= {"K" "string-wins"} (env/normalize {"K" "string-wins" :K "kw"}))))
  (testing "a non-map env is rejected"
    (is (thrown? clojure.lang.ExceptionInfo (env/normalize [["A" "1"]])))))

(deftest merge-envs-precedence-test
  (testing "override wins over base, across key representations"
    (is (= {"ANTHROPIC_API_KEY" "user" "PATH" "/usr/bin"}
           (env/merge-envs {"ANTHROPIC_API_KEY" "parent" "PATH" "/usr/bin"}
                           {:ANTHROPIC_API_KEY "user"}))))
  (testing "nil sides are safe"
    (is (= {"A" "1"} (env/merge-envs {"A" "1"} nil)))
    (is (= {"A" "1"} (env/merge-envs nil {:A "1"})))))

;; =============================================================================
;; Strict validation
;; =============================================================================

(deftest valid-env-test
  (is (env/valid? nil))
  (is (env/valid? {}))
  (is (env/valid? {"ANTHROPIC_MODEL" "claude-opus-5"}))
  (is (not (env/valid? {:ANTHROPIC_MODEL "claude-opus-5"})))
  (is (not (env/valid? {"ANTHROPIC_MODEL" :claude-opus-5})))
  (is (not (env/valid? {" A " "1"})))
  (is (not (env/valid? {"A=B" "1"})))
  (is (not (env/valid? {"A" nil}))))

(deftest problems-report-a-suggestion-test
  (let [ps (env/problems {:ANTHROPIC_MODEL :claude-opus-5})]
    (is (= 2 (count ps)))
    (is (= #{:non-string-key :non-string-value} (set (map :issue ps))))
    (is (= #{"ANTHROPIC_MODEL" "claude-opus-5"} (set (map :suggested ps))))))

(deftest duplicate-key-detected-test
  (let [ps (env/problems {"K" "a" :K "b"})]
    (is (some #(= :duplicate-key (:issue %)) ps))))

(deftest validate-bang-test
  (testing "already-strict maps pass through untouched"
    (is (= {"A" "1"} (env/validate! {"A" "1"})))
    (is (= {} (env/validate! nil))))
  (testing "malformed maps throw with :type and :problems"
    (let [e (try (env/validate! {:A "1"} {:context {:command ["sh"]}})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (= :acp/invalid-env (:type (ex-data e))))
      (is (seq (:problems (ex-data e))))
      (is (= ["sh"] (:command (ex-data e))))
      (is (re-find #"invalid ACP subprocess env" (ex-message e))))))

;; =============================================================================
;; Spawn boundary — transport.stdio/open! is strict
;; =============================================================================

(def ^:private ENV-ECHO
  "Emit the value of $ACP_TEST_VAR back as a JSON-RPC notification, then idle
   briefly so the assertions don't race the child's exit (on EOF the reader
   thread flips the transport closed, which is correct but unrelated here)."
  "printf '{\"jsonrpc\":\"2.0\",\"method\":\"env\",\"params\":{\"v\":\"%s\"}}\\n' \"$ACP_TEST_VAR\"; sleep 2")

(deftest stdio-rejects-unnormalized-env-test
  (testing "a keyword env key is refused at spawn, not silently mangled"
    (let [t (stdio/create {:command ["sh" "-c" "true"]
                           :env     {:ACP_TEST_VAR "opus-5"}})]
      (try
        (let [e (try (transport/open! t)
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (instance? clojure.lang.ExceptionInfo e))
          (is (= :acp/invalid-env (:type (ex-data e))))
          (is (not (transport/open? t))))
        (finally (transport/close! t)))))

  (testing "a non-string value is refused too"
    (let [t (stdio/create {:command ["sh" "-c" "true"]
                           :env     {"ACP_TEST_VAR" :opus-5}})]
      (try
        (is (thrown? clojure.lang.ExceptionInfo (transport/open! t)))
        (finally (transport/close! t))))))

(deftest stdio-accepts-normalized-env-test
  (testing "a normalized env actually reaches the child process"
    (let [t (stdio/create {:command ["sh" "-c" ENV-ECHO]
                           :env     (env/normalize {:ACP_TEST_VAR :opus-5})})]
      (try
        (transport/open! t)
        (let [msg (transport/read-message! t 5000)]
          (is (= "env" (:method msg)))
          ;; the point of the test: the variable reached the child intact,
          ;; with no leading colon and no silent drop
          (is (= "opus-5" (get-in msg [:params :v]))))
        (finally (transport/close! t))))))

(deftest stdio-nil-env-is-fine-test
  (testing "no :env at all still spawns"
    (let [t (stdio/create {:command ["sh" "-c" "true"]})]
      (try
        (transport/open! t)
        (is (transport/open? t))
        (finally (transport/close! t))))))

;; =============================================================================
;; Nested-session markers — never inherited, still overridable
;; =============================================================================

(deftest strip-nested-session-markers-test
  (testing "the marker is removed from a ProcessBuilder-shaped env map"
    (let [m (java.util.HashMap. {"CLAUDECODE" "1" "PATH" "/usr/bin"})]
      (is (= #{"CLAUDECODE"} (env/strip-nested-session-markers! m)))
      (is (nil? (.get m "CLAUDECODE")))
      (is (= "/usr/bin" (.get m "PATH"))
          "unrelated inherited vars are left alone")))

  (testing "nothing to drop reports nothing, and is not an error"
    (let [m (java.util.HashMap. {"PATH" "/usr/bin"})]
      (is (= #{} (env/strip-nested-session-markers! m)))
      (is (= 1 (.size m))))))

(def ^:private CLAUDECODE-ECHO
  "Report the child's own $CLAUDECODE (empty when unset). Same idle-then-exit
   shape as ENV-ECHO so the read doesn't race the child's EOF."
  "printf '{\"jsonrpc\":\"2.0\",\"method\":\"env\",\"params\":{\"v\":\"%s\"}}\\n' \"$CLAUDECODE\"; sleep 2")

(deftest stdio-does-not-inherit-claudecode-test
  ;; The regression this guards: `by` started from inside a Claude Code
  ;; terminal inherits CLAUDECODE=1, hands it to claude-agent-acp, and the
  ;; backend dies with "cannot be launched inside another Claude Code
  ;; session". The assertion is exact where it matters (a test JVM that has
  ;; the marker) and vacuously true elsewhere, so it never flakes on CI.
  (testing "an inherited marker does not reach the child"
    (let [t (stdio/create {:command ["sh" "-c" CLAUDECODE-ECHO]})]
      (try
        (transport/open! t)
        (let [msg (transport/read-message! t 5000)]
          (is (= "" (get-in msg [:params :v]))
              (str "parent CLAUDECODE=" (pr-str (System/getenv "CLAUDECODE")))))
        (finally (transport/close! t))))))

(deftest stdio-explicit-claudecode-still-wins-test
  (testing "a marker set on purpose in :env survives — only inheritance is refused"
    (let [t (stdio/create {:command ["sh" "-c" CLAUDECODE-ECHO]
                           :env     {"CLAUDECODE" "1"}})]
      (try
        (transport/open! t)
        (let [msg (transport/read-message! t 5000)]
          (is (= "1" (get-in msg [:params :v]))))
        (finally (transport/close! t))))))
