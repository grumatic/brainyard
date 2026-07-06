;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp-client.registry-test
  "Pure-data tests for the ACP backend registry.

   Each launch spec is exercised via `resolve-backend` and asserted on
   the returned map shape. No subprocesses spawned. Real backends
   (claude-code / gemini / codex) require external CLIs at
   runtime; we don't try to launch them here, only validate that the
   registry produces correctly-shaped launch specs and that
   `backend-available?` correctly classifies them based on PATH."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.acp-client.interface :as acp-client]
            [ai.brainyard.acp-client.core.registry :as registry]))

;; =============================================================================
;; Built-in registry contents
;; =============================================================================

(deftest list-backends-test
  (testing "all four built-in backends are registered out of the box"
    (let [backends (acp-client/list-backends)]
      (is (contains? backends :stub))
      (is (contains? backends :claude-code))
      (is (contains? backends :gemini))
      (is (contains? backends :codex))
      ;; No :factory leak — factories are kept private inside the atom.
      (is (every? (fn [[_ entry]] (not (contains? entry :factory)))
                  backends))
      ;; Each entry has the expected shape.
      (doseq [[k entry] backends]
        (is (string? (:description entry)) (str k " has a string description"))
        (is (boolean? (:experimental entry)) (str k " has a boolean :experimental"))
        (is (vector? (:prereqs entry)) (str k " has a vector :prereqs"))))))

(deftest experimental-flags-test
  (testing ":stub is non-experimental; the three real backends are experimental"
    (let [backends (acp-client/list-backends)]
      (is (false? (-> backends :stub :experimental)))
      (is (true?  (-> backends :claude-code :experimental)))
      (is (true?  (-> backends :gemini :experimental)))
      (is (true?  (-> backends :codex :experimental))))))

;; =============================================================================
;; Launch-spec resolution
;; =============================================================================

(deftest stub-spec-test
  (testing ":stub launch spec includes clj invocation + chunk-delay-ms flag"
    (let [spec (acp-client/resolve-backend :stub {:chunk-delay-ms 25})]
      (is (= "clj" (first (:command spec))))
      (is (some #{"--echo"} (:command spec)))
      (is (some #(re-find #"--chunk-delay-ms=25" %) (:command spec)))
      (is (string? (:working-dir spec)))
      (is (.contains ^String (:working-dir spec) "projects/acp-stub-agent")))))

(deftest claude-code-spec-default-test
  (testing "default :claude-code spec uses npx and the published adapter"
    (let [spec (acp-client/resolve-backend :claude-code)]
      (is (= "npx" (first (:command spec))))
      (is (some #{"-y"} (:command spec)))
      (is (some #(re-find #"claude-code-acp" %) (:command spec)))
      (is (string? (:working-dir spec)))
      (is (map? (:env spec))))))

(deftest claude-code-spec-override-test
  (testing "user-supplied :command and :working-dir override the defaults"
    (let [spec (acp-client/resolve-backend
                :claude-code
                {:command ["my-bin" "--foo"]
                 :working-dir "/tmp/custom-workspace"
                 :env {"EXTRA_VAR" "yes"}})]
      (is (= ["my-bin" "--foo"] (:command spec)))
      (is (= "/tmp/custom-workspace" (:working-dir spec)))
      (is (= "yes" (get-in spec [:env "EXTRA_VAR"]))))))

(deftest gemini-spec-test
  (testing ":gemini default spec invokes gemini --experimental-acp"
    (let [spec (acp-client/resolve-backend :gemini)]
      (is (= "gemini" (first (:command spec))))
      (is (some #(re-find #"acp" %) (:command spec))))))

(deftest codex-spec-test
  (testing ":codex default spec invokes codex --acp"
    (let [spec (acp-client/resolve-backend :codex)]
      (is (= "codex" (first (:command spec))))
      (is (some #{"--acp"} (:command spec))))))

(deftest unknown-backend-throws-test
  (testing "unrecognized backends throw with a list of supported keys"
    (let [thrown (try (acp-client/resolve-backend :nope)
                      (catch clojure.lang.ExceptionInfo e e))
          data (ex-data thrown)]
      (is (some? thrown))
      (is (contains? (set (:supported data)) :stub))
      (is (contains? (set (:supported data)) :claude-code)))))

;; =============================================================================
;; Env passthrough
;; =============================================================================

(deftest env-merging-test
  (testing "user :env overrides take precedence over forwarded vars"
    ;; We can't reliably set parent env vars from inside a Clojure test,
    ;; but we can assert that user-supplied values land in :env even
    ;; when the parent had no value.
    (let [spec (acp-client/resolve-backend :claude-code
                                           {:env {"ANTHROPIC_API_KEY" "test-key-123"}})]
      (is (= "test-key-123"
             (get-in spec [:env "ANTHROPIC_API_KEY"]))))))

;; =============================================================================
;; backend-available? — PATH probe
;; =============================================================================

(deftest backend-available-stub-test
  (testing ":stub is available on any developer machine (clj is required to run tests)"
    (let [{:keys [status]} (acp-client/backend-available? :stub)]
      (is (= :ok status)
          "if these tests run at all, clj is on PATH"))))

(deftest backend-available-unregistered-test
  (testing "unknown keyword returns :unregistered"
    (let [{:keys [status backend]} (acp-client/backend-available? :nope)]
      (is (= :unregistered status))
      (is (= :nope backend)))))

(deftest backend-available-experimental-test
  (testing "experimental backends report status based on PATH presence"
    (doseq [k [:claude-code :gemini :codex]]
      (let [{:keys [status missing prereqs]} (acp-client/backend-available? k)]
        (is (#{:ok :missing-prereqs} status))
        (when (= :missing-prereqs status)
          (is (every? string? missing))
          (is (every? string? prereqs)))))))

(deftest which-finds-clj-test
  (testing "which returns an absolute path for clj (these tests use it)"
    (let [path (acp-client/which "clj")]
      (is (some? path))
      (is (.startsWith ^String path "/")
          "absolute path"))))

(deftest which-rejects-missing-test
  (testing "which returns nil for executables that don't exist"
    (is (nil? (acp-client/which "definitely-not-a-real-binary-xyz123")))))

;; =============================================================================
;; register-backend! / unregister-backend!
;; =============================================================================

(deftest register-and-unregister-test
  (testing "user code can add a custom backend at runtime"
    (try
      (let [my-factory (fn [_opts] {:command ["echo" "hi"]})]
        (acp-client/register-backend! :my-test-backend my-factory
                                      :description "test-only"
                                      :experimental false
                                      :prereqs ["echo"])
        (is (contains? (acp-client/list-backends) :my-test-backend))
        (let [spec (acp-client/resolve-backend :my-test-backend)]
          (is (= ["echo" "hi"] (:command spec))))
        (is (true? (acp-client/unregister-backend! :my-test-backend)))
        (is (not (contains? (acp-client/list-backends) :my-test-backend))))
      (finally
        ;; defensive cleanup in case anything failed mid-test
        (acp-client/unregister-backend! :my-test-backend)))))

(deftest cant-unregister-stub-test
  (testing ":stub is protected — cannot be removed"
    (is (thrown? clojure.lang.ExceptionInfo
                 (acp-client/unregister-backend! :stub)))))

(deftest register-validates-args-test
  (testing "register-backend! rejects bad args with a clear error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"backend-key must be a keyword"
                          (acp-client/register-backend! "string-key" (fn [_] {}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"factory must be a function"
                          (acp-client/register-backend! :foo "not-a-fn")))))
