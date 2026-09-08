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

;; ============================================================================
;; apply-policy! — Phase 3 scoping
;;
;; Pure map surgery here; `agent/core/proc_test.clj` proves it reaches a real
;; subprocess. The properties worth pinning are the ones a misconfiguration
;; would silently invert: that the default does nothing, that a deny cannot be
;; talked round, and that an allowlist does not cost the child its PATH.
;; ============================================================================

(defn- pb-env
  "A fresh java.util.Map seeded with `m`, standing in for (.environment pb)."
  [m]
  (let [jm (java.util.HashMap.)]
    (doseq [[k v] m] (.put jm k v))
    jm))

(defn- policied [seed policy]
  (with-dotenv []
    (fn [] (into {} (env/apply-policy! (pb-env seed) policy)))))

(deftest an-empty-policy-changes-nothing
  ;; Ships inert: the shape of the default policy must leave the child's
  ;; environment exactly as the .env layer alone would.
  (let [seed {"PATH" "/bin" "SECRET" "sk-1" "OTHER" "x"}]
    (is (= seed (policied seed {})))
    (is (= seed (policied seed {:allow nil :deny [] :vars {}})))))

(deftest env-deny-removes-unconditionally
  (is (= {"PATH" "/bin"}
         (policied {"PATH" "/bin" "AWS_PROFILE" "p" "AWS_REGION" "r"}
                   {:deny ["AWS_*"]})))
  (testing "and it outranks :env-vars declaring the same name — a deny another key can talk round is not a deny"
    (is (= {"PATH" "/bin"}
           (policied {"PATH" "/bin"} {:deny ["TOKEN"] :vars {"TOKEN" "sk-1"}}))))
  (testing "and it outranks :env-allow admitting the same name"
    (is (= {"PATH" "/bin"}
           (policied {"PATH" "/bin" "TOKEN" "sk-1"}
                     {:allow ["TOKEN"] :deny ["TOKEN"]})))))

(deftest env-deny-can-remove-an-infrastructure-var
  ;; The floor is a convenience for :env-allow, not a protected set. A deny is
  ;; the escape hatch for anyone who really means it.
  (is (= {} (policied {"PATH" "/bin"} {:deny ["PATH"]}))))

(deftest env-vars-sets-and-overrides
  (is (= {"PATH" "/bin" "ENDPOINT" "https://staging"}
         (policied {"PATH" "/bin"} {:vars {"ENDPOINT" "https://staging"}})))
  (testing "over an inherited value"
    (is (= {"ENDPOINT" "new"} (policied {"ENDPOINT" "old"} {:vars {"ENDPOINT" "new"}}))))
  (testing "keyword keys and non-string values are coerced"
    (is (= {"N" "42"} (policied {} {:vars {:N 42}})))))

(deftest env-allow-narrows-but-keeps-the-infrastructure-floor
  ;; Without the floor the first `:env-allow ["GITHUB_TOKEN"]` produces a child
  ;; with no PATH, every shell command failing, and no clue why — a trap, not a
  ;; policy.
  (let [out (policied {"PATH" "/bin" "HOME" "/h" "TERM" "xterm"
                       "GITHUB_TOKEN" "ghp" "UNRELATED" "x"}
                      {:allow ["GITHUB_TOKEN"]})]
    (is (= "ghp" (get out "GITHUB_TOKEN")))
    (is (= "/bin" (get out "PATH")) "PATH survives without being listed")
    (is (= "/h" (get out "HOME")))
    (is (= "xterm" (get out "TERM")))
    (is (not (contains? out "UNRELATED")))))

(deftest env-allow-admits-what-env-vars-declared
  ;; The operator just named it; making them list it twice is bureaucracy.
  (is (= "v" (get (policied {} {:allow ["NOTHING"] :vars {"DECLARED" "v"}}) "DECLARED"))))

(deftest an-empty-allow-vector-is-not-an-allowlist
  ;; nil and [] both mean "no opinion". An empty vector reading as "allow
  ;; nothing" would make a half-written config strip every variable.
  (let [seed {"PATH" "/bin" "OTHER" "x"}]
    (is (= seed (policied seed {:allow []})))))

(deftest the-policy-adds-the-dotenv-layer-too
  (with-props {"BY_TEST_POLICY_TOKEN" "from-dotenv"}
    (fn []
      (with-dotenv ["BY_TEST_POLICY_TOKEN"]
        (fn []
          (is (= "from-dotenv"
                 (get (into {} (env/apply-policy! (pb-env {"PATH" "/bin"}) {}))
                      "BY_TEST_POLICY_TOKEN"))))))))

;; ============================================================================
;; apply-policy! over a CHAIN — Phase 4
;;
;; `get-config` has no parent→child inheritance, so a sub-agent's config layer
;; is its own. For most keys that is right; for a RESTRICTION it is a hole, and
;; a measured one — an agent denying itself a credential could dispatch a
;; specialist that saw it anyway, and that specialist runs shells. So spawn
;; sites pass the whole ancestry and every level is applied.
;; ============================================================================

(deftest a-chain-unions-denies
  (is (= {"PATH" "/bin"}
         (policied {"PATH" "/bin" "AWS_KEY" "a" "GH_TOKEN" "g"}
                   [{:deny ["AWS_*"]} {:deny ["GH_*"]}]))))

(deftest an-ancestor-deny-cannot-be-undone-by-a-descendant
  ;; The hole this closes. A deny a delegation can undo is not a deny — the
  ;; same sentence `:tool-deny-tools` settled about `:tool-allow-tools`.
  (is (= {} (policied {"SECRET" "sk"}
                      [{:deny ["SECRET"]} {:vars {"SECRET" "re-added"}}])))
  (testing "nor by a descendant's allow admitting it"
    (is (= {} (policied {"SECRET" "sk"}
                        [{:deny ["SECRET"]} {:allow ["SECRET"]}])))))

(deftest a-chain-intersects-allows
  ;; Applying each level in turn gives intersection without computing one.
  (let [out (policied {"A" "1" "B" "2" "C" "3" "PATH" "/bin"}
                      [{:allow ["A" "B"]} {:allow ["B" "C"]}])]
    (is (= "2" (get out "B")) "only the name both levels admit survives")
    (is (not (contains? out "A")))
    (is (not (contains? out "C")))
    (is (= "/bin" (get out "PATH")) "the infrastructure floor still applies")))

(deftest an-ancestor-allow-binds-a-descendants-env-vars
  ;; The same-level exemption is deliberate and deliberately NOT inherited: the
  ;; operator who wrote allow and vars in one place should not say it twice,
  ;; but an ancestor never saw the descendant's declaration.
  (is (not (contains? (policied {} [{:allow ["NOTHING"]} {:vars {"X" "v"}}]) "X")))
  (testing "while the same policy's own vars are admitted past its own allow"
    (is (= "v" (get (policied {} [{:allow ["NOTHING"] :vars {"X" "v"}}]) "X")))))

(deftest nearest-scope-wins-a-vars-collision
  (is (= "child" (get (policied {} [{:vars {"E" "parent"}} {:vars {"E" "child"}}]) "E"))))

(deftest a-single-map-still-works-and-nil-is-inert
  (let [seed {"PATH" "/bin" "X" "1"}]
    (is (= seed (policied seed nil)))
    (is (= seed (policied seed [])))
    (is (= {"PATH" "/bin"} (policied seed {:deny ["X"]}))
        "the Phase 3 single-map form is unchanged")))

(deftest a-malformed-policy-throws-rather-than-silently-permitting
  ;; Every field of a malformed policy destructures to nil, which reads as
  ;; "no opinion" and yields an UNFILTERED child — the wrong-direction failure
  ;; for a restriction, and the same reason both tool-permission gates take
  ;; `:on-error :throw`. Found for real: a stale value-copy in a REPL handed
  ;; this a vector while it still destructured a map, and the only symptom was
  ;; a deny that quietly stopped denying.
  (is (thrown? clojure.lang.ExceptionInfo
               (env/apply-policy! (pb-env {"SECRET" "sk"}) "not-a-policy")))
  (is (thrown? clojure.lang.ExceptionInfo
               (env/apply-policy! (pb-env {"SECRET" "sk"}) [{:deny ["SECRET"]} 42]))))
