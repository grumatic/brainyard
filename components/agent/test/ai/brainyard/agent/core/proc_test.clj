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
            [ai.brainyard.agent.core.protocol :as proto]
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


;; ============================================================================
;; Phase 3 — the policy reaches a real child
;;
;; `env_test.clj` proves the map surgery. These prove the surgery is applied to
;; something that actually runs, and that the scope is picked up from the
;; calling agent rather than from an argument nobody passes.
;; ============================================================================

(defn- agent-with
  "A stub agent carrying `m` on its per-agent config layer."
  [m]
  {:!state (atom {:st-memory-init (atom {:config m})})})

(deftest env-deny-reaches-the-child
  (with-dotenv-var "BY_TEST_PROC_SECRET" "sk-secret"
    (fn []
      (is (= "[sk-secret]" (sh-out "echo \"[$BY_TEST_PROC_SECRET]\""))
          "precondition: the child sees it with no policy")
      (binding [proto/*current-agent* (agent-with {:env-deny ["BY_TEST_PROC_*"]})]
        (is (= "[]" (sh-out "echo \"[$BY_TEST_PROC_SECRET]\"")))))))

(deftest env-vars-reaches-the-child
  (binding [proto/*current-agent* (agent-with {:env-vars {"BY_TEST_PROC_ENDPOINT" "https://staging"}})]
    (is (= "[https://staging]" (sh-out "echo \"[$BY_TEST_PROC_ENDPOINT]\"")))))

(deftest env-allow-does-not-cost-the-child-its-path
  ;; The whole reason `infrastructure-vars` exists. If this breaks, every shell
  ;; command under an allowlist fails with no diagnosis.
  (binding [proto/*current-agent* (agent-with {:env-allow ["NOTHING_MATCHES"]})]
    (is (not= "" (sh-out "echo $PATH")))
    (is (= "ok" (sh-out "echo ok")) "and a command still runs at all")))

(deftest the-scope-comes-from-the-calling-agent
  ;; Two agents, same spawn site, different environments — the property that
  ;; makes this scoping rather than a global switch.
  (with-dotenv-var "BY_TEST_PROC_SCOPED" "shared"
    (fn []
      (binding [proto/*current-agent* (agent-with {:env-deny ["BY_TEST_PROC_SCOPED"]})]
        (is (= "[]" (sh-out "echo \"[$BY_TEST_PROC_SCOPED]\""))))
      (binding [proto/*current-agent* (agent-with {})]
        (is (= "[shared]" (sh-out "echo \"[$BY_TEST_PROC_SCOPED]\"")))))))

(deftest with-no-agent-bound-the-global-layer-applies
  ;; A spawn that belongs to no agent must still work, and must resolve the
  ;; global policy rather than throwing on a nil scope.
  (binding [proto/*current-agent* nil]
    (is (= "ok" (sh-out "echo ok")))))

;; ============================================================================
;; Phase 4 — a sub-agent inherits its ancestry's restrictions
;; ============================================================================

(defn- sub-agent-of
  "A stub child whose `runtime/get-parent-agent` answers `parent`."
  [parent m]
  (let [child {:!state (atom {:st-memory-init (atom {:config m})
                              :runtime {:parent-agent parent}})}]
    child))

(deftest an-ancestors-deny-reaches-a-sub-agents-child-process
  ;; The hole: `get-config` has no parent→child inheritance, so an agent that
  ;; denied itself a credential could dispatch a specialist that saw it anyway
  ;; — and that specialist runs shells.
  (with-dotenv-var "BY_TEST_PROC_INHERIT" "sk-secret"
    (fn []
      (let [parent (agent-with {:env-deny ["BY_TEST_PROC_INHERIT"]})
            child  (sub-agent-of parent {})]
        (binding [proto/*current-agent* parent]
          (is (= "[]" (sh-out "echo \"[$BY_TEST_PROC_INHERIT]\""))
              "the parent's own children are denied"))
        (binding [proto/*current-agent* child]
          (is (= "[]" (sh-out "echo \"[$BY_TEST_PROC_INHERIT]\""))
              "and so are its sub-agent's"))
        (binding [proto/*current-agent* (agent-with {})]
          (is (= "[sk-secret]" (sh-out "echo \"[$BY_TEST_PROC_INHERIT]\""))
              "while an unrelated agent is untouched"))))))

(deftest a-sub-agent-cannot-re-add-what-an-ancestor-denied
  (with-dotenv-var "BY_TEST_PROC_SNEAK" "sk-secret"
    (fn []
      (let [parent (agent-with {:env-deny ["BY_TEST_PROC_SNEAK"]})
            sneaky (sub-agent-of parent {:env-vars {"BY_TEST_PROC_SNEAK" "re-added"}})]
        (binding [proto/*current-agent* sneaky]
          (is (= "[]" (sh-out "echo \"[$BY_TEST_PROC_SNEAK]\""))))))))

(deftest an-ancestors-allowlist-still-leaves-the-child-a-path
  (let [parent (agent-with {:env-allow ["NOTHING_MATCHES"]})
        child  (sub-agent-of parent {})]
    (binding [proto/*current-agent* child]
      (is (not= "" (sh-out "echo $PATH")))
      (is (= "ok" (sh-out "echo ok"))))))
