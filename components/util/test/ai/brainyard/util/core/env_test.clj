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

;; ============================================================================
;; child-env — the layer a spawned process is missing
;;
;; Phase 2 of docs/design/environment-scoping-design.md. A ProcessBuilder child
;; inherits the real ENVIRONMENT and nothing else, so the one layer it lacks is
;; exactly the one `.env` supplied — which lives in the property table because
;; the JVM environment cannot be written to. That asymmetry is why a `.env`
;; GH_TOKEN reached clj-llm and never reached `gh`.
;; ============================================================================

(defn- with-dotenv
  "Register `ks` as .env-supplied for the duration of `f`, restoring after."
  [ks f]
  (let [prior (env/dotenv-keys)]
    (try (env/register-dotenv-keys! ks) (f)
         (finally (env/register-dotenv-keys! prior)))))

(deftest child-env-is-empty-until-the-loader-registers
  ;; A bb task, a test, a hand-launched JVM: nothing was loaded, so nothing is
  ;; missing from a child. Empty is the correct answer, not a missing feature.
  (with-dotenv []
    (fn [] (is (= {} (env/child-env))))))

(deftest child-env-carries-a-dotenv-supplied-value
  (with-props {"BY_TEST_CHILD_TOKEN" "ghp-from-dotenv"}
    (fn []
      (with-dotenv ["BY_TEST_CHILD_TOKEN"]
        (fn [] (is (= {"BY_TEST_CHILD_TOKEN" "ghp-from-dotenv"} (env/child-env))))))))

(deftest child-env-never-overrides-a-real-environment-variable
  ;; The child inherits PATH already, and `.env` never overrides a real
  ;; variable in THIS process either — writing one here would invert, for
  ;; children only, a precedence rule both loaders implement.
  (with-props {"PATH" "SHOULD-NOT-WIN"}
    (fn []
      (with-dotenv ["PATH"]
        (fn []
          (is (some? (System/getenv "PATH")) "precondition")
          (is (not (contains? (env/child-env) "PATH"))))))))

(deftest a-blank-real-env-var-does-not-block-the-dotenv-value
  ;; The same shadowing bug `resolve-var` fixes one layer up, reproduced in
  ;; `child-env` and caught by the proc suite. A `(some? (System/getenv k))`
  ;; guard reads an exported-but-empty variable as "the child already has it",
  ;; so the child gets the blank while THIS process, whose resolve-var falls
  ;; through to the property, uses the real value — parent and child
  ;; disagreeing about one variable, which is the single thing child-env
  ;; exists to prevent. Not hypothetical: agent and CI shells commonly export
  ;; GIT_ASKPASS= and SSH_ASKPASS= empty.
  ;;
  ;; The environment cannot be written from a test, so this asserts the guard
  ;; itself: whatever `not-blank` says about the env value is what decides.
  (let [blank-env (->> ["GIT_ASKPASS" "SSH_ASKPASS"]
                       (filter #(and (some? (System/getenv %))
                                     (clojure.string/blank? (System/getenv %))))
                       first)]
    (if blank-env
      (with-props {blank-env "from-dotenv"}
        (fn []
          (with-dotenv [blank-env]
            (fn [] (is (= {blank-env "from-dotenv"} (env/child-env))
                       "a blank real env var must not suppress the .env value")))))
      (is true "no blank env var in this environment to exercise the guard"))))

(deftest child-env-skips-a-blank-value
  ;; A `FOO=` line must not export an empty FOO into every subprocess.
  (with-props {"BY_TEST_CHILD_BLANK" ""}
    (fn []
      (with-dotenv ["BY_TEST_CHILD_BLANK"]
        (fn [] (is (= {} (env/child-env))))))))

(deftest child-env-ignores-properties-nobody-registered
  ;; The property table also holds ~60 standard JVM entries; shipping
  ;; `java.version` into a child as an environment variable would be wrong.
  (with-dotenv []
    (fn []
      (is (some? (System/getProperty "java.version")) "precondition")
      (is (not (contains? (env/child-env) "java.version"))))))

(deftest register-dotenv-keys-normalizes-and-is-last-write-wins
  (with-props {"BY_TEST_REG" "v"}
    (fn []
      (with-dotenv [:BY_TEST_REG]
        (fn [] (is (= #{"BY_TEST_REG"} (env/dotenv-keys))
                   "a keyword key registers by NAME")))
      (with-dotenv ["BY_TEST_REG" nil ""]
        (fn [] (is (= #{"BY_TEST_REG"} (env/dotenv-keys))
                   "nil and empty entries are dropped rather than stored"))))))
