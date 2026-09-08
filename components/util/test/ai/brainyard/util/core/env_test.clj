;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.util.core.env-test
  "Tests for the one env-var resolver — docs/design/environment-scoping-design.md
   Phase 0.

   The properties worth pinning are the ones the six private copies disagreed
   about: whether a `.env`-supplied value is visible at all, whether blank
   counts as unset, and whether a blank ENVIRONMENT variable shadows a
   non-blank property. The last is the one deliberate behaviour change in the
   consolidation, so it is asserted directly rather than left to follow from
   the implementation.

   These tests can only set PROPERTIES — the JVM environment is immutable,
   which is the whole reason this function exists. The env side is covered by
   asserting against a variable every process running this suite has."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.util.core.env :as env]))

(defn- with-props
  "Run `f` with `m`'s properties set, restoring or clearing them after."
  [m f]
  (let [prior (into {} (map (fn [[k _]] [k (System/getProperty k)])) m)]
    (try
      (doseq [[k v] m] (System/setProperty k v))
      (f)
      (finally
        (doseq [[k v] prior]
          (if v (System/setProperty k v) (System/clearProperty k)))))))

(deftest reads-the-process-environment
  ;; PATH is set for every process that can run this suite.
  (is (some? (env/resolve-var "PATH")))
  (is (= (System/getenv "PATH") (env/resolve-var "PATH"))))

(deftest reads-a-dotenv-supplied-property
  ;; The reason the function exists: `.env` values are System Properties,
  ;; because the JVM environment cannot be written to. A reader that consults
  ;; only System/getenv sees nothing a user put in `.env`.
  (with-props {"BY_TEST_ONLY_PROP" "from-dotenv"}
    (fn []
      (is (nil? (System/getenv "BY_TEST_ONLY_PROP")) "precondition: not a real env var")
      (is (= "from-dotenv" (env/resolve-var "BY_TEST_ONLY_PROP"))))))

(deftest blank-counts-as-unset-by-default
  (with-props {"BY_TEST_BLANK" "   "}
    (fn []
      (is (nil? (env/resolve-var "BY_TEST_BLANK")))
      (testing ":blank-as-unset? false returns it verbatim — MCP's ${VAR} arm, which must tell an UNSET variable from one set to empty"
        (is (= "   " (env/resolve-var "BY_TEST_BLANK" {:blank-as-unset? false})))))))

(deftest a-blank-value-does-not-answer-for-a-set-one
  ;; The one deliberate behaviour change. Every private copy read
  ;; `(or (getenv k) (getProperty k))` and blank-checked the RESULT, so an
  ;; `export GH_TOKEN=` in a shell profile silently defeated the GH_TOKEN in
  ;; `.env`, permanently and invisibly. The env layer cannot be written from a
  ;; test, so the assertion here is the half that can be: a blank never
  ;; resolves, under either source.
  (with-props {"BY_TEST_FALLTHROUGH" ""}
    (fn []
      (is (nil? (env/resolve-var "BY_TEST_FALLTHROUGH"))
          "a blank is unset, not an empty-string answer")
      (is (= "" (env/resolve-var "BY_TEST_FALLTHROUGH" {:blank-as-unset? false}))
          "and the raw arm still reports what is literally there"))))

(deftest an-absent-var-is-nil-under-both-arms
  (is (nil? (env/resolve-var "BY_TEST_DEFINITELY_ABSENT_XYZ")))
  (is (nil? (env/resolve-var "BY_TEST_DEFINITELY_ABSENT_XYZ" {:blank-as-unset? false}))))

(deftest key-may-be-a-string-keyword-or-symbol
  ;; `(str :CLICKHOUSE_HOST)` produced a variable literally named
  ;; ":CLICKHOUSE_HOST" — the bug mcp/client.clj's env-var-name was written to
  ;; prevent, now prevented once for every caller.
  (with-props {"BY_TEST_KEYSHAPE" "v"}
    (fn []
      (is (= "v" (env/resolve-var "BY_TEST_KEYSHAPE")))
      (is (= "v" (env/resolve-var :BY_TEST_KEYSHAPE)))
      (is (= "v" (env/resolve-var 'BY_TEST_KEYSHAPE)))))
  (testing "a nil or empty key is nil rather than a throw"
    (is (nil? (env/resolve-var nil)))
    (is (nil? (env/resolve-var "")))))

(deftest resolve-first-returns-the-pair-not-the-value
  ;; Callers need to say WHICH variable supplied the credential: the provider
  ;; tables carry alternates, and naming the wrong one sends the user to edit a
  ;; variable that is not in play.
  (with-props {"BY_TEST_ALT" "tok"}
    (fn []
      (is (= ["BY_TEST_ALT" "tok"]
             (env/resolve-first ["BY_TEST_ABSENT_PRIMARY" "BY_TEST_ALT"])))
      (is (nil? (env/resolve-first ["BY_TEST_ABSENT_PRIMARY"])))
      (is (nil? (env/resolve-first [])))))
  (testing "first match wins, in the order given"
    (with-props {"BY_TEST_P" "primary" "BY_TEST_A" "alt"}
      (fn []
        (is (= ["BY_TEST_P" "primary"] (env/resolve-first ["BY_TEST_P" "BY_TEST_A"])))
        (is (= ["BY_TEST_A" "alt"]     (env/resolve-first ["BY_TEST_A" "BY_TEST_P"])))))))

(deftest resolve-any?-is-a-boolean-over-the-same-rule
  (with-props {"BY_TEST_ANY" "x" "BY_TEST_ANY_BLANK" "  "}
    (fn []
      (is (true?  (env/resolve-any? ["BY_TEST_ABSENT" "BY_TEST_ANY"])))
      (is (false? (env/resolve-any? ["BY_TEST_ABSENT"])))
      (is (false? (env/resolve-any? ["BY_TEST_ANY_BLANK"]))
          "blank is unset here too, which is what /login was getting wrong")
      (is (false? (env/resolve-any? []))))))
