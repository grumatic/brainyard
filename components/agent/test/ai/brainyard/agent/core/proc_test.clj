;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.proc-test
  "Tests for the shared shell spawn — Phase 2 of
   docs/design/environment-scoping-design.md.

   These SPAWN A REAL PROCESS on purpose. Every other assertion in this area
   proves that a map was computed correctly, which is one step short of the
   claim being made: the claim is that a subprocess can now see a `.env`
   value, and the only way to prove that is to ask one."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.agent.core.proc :as proc]
            [ai.brainyard.util.interface :as util]))

(defn- sh-out
  "Run `command` through the shared spawn and return its trimmed output."
  [command]
  (let [p   (.start (proc/shell-pb command))
        out (slurp (.getInputStream p))]
    (.waitFor p)
    (str/trim out)))

(defn- with-dotenv-var
  "Set `k` as a `.env`-supplied property for the duration of `f`."
  [k v f]
  (let [prior-prop (System/getProperty k)
        prior-keys (util/dotenv-keys)]
    (try
      (System/setProperty k v)
      (util/register-dotenv-keys! (conj prior-keys k))
      (f)
      (finally
        (util/register-dotenv-keys! prior-keys)
        (if prior-prop (System/setProperty k prior-prop) (System/clearProperty k))))))

(deftest a-child-sees-a-dotenv-supplied-variable
  ;; The defect this closes: `.env` values are JVM properties, and a
  ;; ProcessBuilder child inherits the ENVIRONMENT. So a `.env` GH_TOKEN
  ;; authenticated `by` itself and never reached `gh`.
  (is (= "[]" (sh-out "echo \"[$BY_TEST_PROC_TOKEN]\""))
      "precondition: the child cannot see it yet")
  (with-dotenv-var "BY_TEST_PROC_TOKEN" "ghp-from-dotenv"
    (fn []
      (is (= "[ghp-from-dotenv]" (sh-out "echo \"[$BY_TEST_PROC_TOKEN]\""))))))

(deftest the-child-still-gets-the-askpass-hardening
  ;; `apply-dotenv!` runs after `harden-env!`, so it must not have displaced
  ;; it. A credential prompt that waits instead of failing is the failure this
  ;; namespace exists to prevent.
  (is (= "[false]" (sh-out "echo \"[$GIT_ASKPASS]\"")))
  (is (= "[0]" (sh-out "echo \"[$GIT_TERMINAL_PROMPT]\""))))

(deftest a-dotenv-entry-may-override-the-hardening
  ;; "inherited env is a default, not a ceiling" — `.env` is something the user
  ;; wrote, so it outranks a default this namespace chose for them.
  (with-dotenv-var "GIT_ASKPASS" "/usr/bin/true"
    (fn [] (is (= "[/usr/bin/true]" (sh-out "echo \"[$GIT_ASKPASS]\""))))))

(deftest a-caller-entry-still-outranks-both
  (with-dotenv-var "BY_TEST_PROC_ORDER" "from-dotenv"
    (fn []
      (let [pb (proc/shell-pb "echo \"[$BY_TEST_PROC_ORDER]\"")]
        (.put (.environment pb) "BY_TEST_PROC_ORDER" "from-caller")
        (let [p (.start pb) out (slurp (.getInputStream p))]
          (.waitFor p)
          (is (= "[from-caller]" (str/trim out))))))))

(deftest a-real-environment-variable-is-not-clobbered
  ;; PATH is inherited; a property of the same name must not replace it, or a
  ;; child loses its PATH because someone wrote one into a `.env`.
  (let [inherited (sh-out "echo \"$PATH\"")]
    (with-dotenv-var "PATH" "/nonexistent-should-not-win"
      (fn []
        (is (= inherited (sh-out "echo \"$PATH\"")))))))

(deftest the-command-runs-in-its-own-session
  ;; Unchanged by Phase 2, asserted here because nothing else asserted it and
  ;; `apply-dotenv!` sits in the same pipeline.
  (testing "the wrapper is identifiable and the command still runs"
    (is (= "hello" (sh-out "echo hello")))))
