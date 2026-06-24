;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.exec-backend-test
  "Tests for the execution-backend seam (R4): LocalBackend shell primitive,
   the protocol's swappability + clj-eval injection seam, resolve-backend, and
   the now-host-configurable clj-nrepl endpoint."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.agent.core.exec-backend :as eb]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.clj-nrepl.interface :as nrepl]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; LocalBackend shell
;; ============================================================================

(deftest local-exec-shell-cases
  (testing "success: captures combined output + exit 0"
    (let [r (eb/local-exec-shell "echo hi" {:timeout-ms 5000})]
      (is (= 0 (:exit r)))
      (is (= "hi\n" (:output r)))
      (is (= "" (:error r)))))
  (testing "non-zero exit surfaces the code"
    (let [r (eb/local-exec-shell "exit 3" {:timeout-ms 5000})]
      (is (= 3 (:exit r)))
      (is (re-find #"Exit code: 3" (:error r)))))
  (testing "timeout kills the process and reports it"
    (let [r (eb/local-exec-shell "sleep 5" {:timeout-ms 200})]
      (is (neg? (:exit r)))
      (is (re-find #"timed out" (:error r)))))
  (testing ":cwd is honored"
    (let [dir (.getCanonicalPath (io/file (System/getProperty "java.io.tmpdir")))
          r   (eb/local-exec-shell "pwd" {:cwd dir :timeout-ms 5000})]
      (is (str/includes? (:output r) (last (str/split dir #"/")))))))

(deftest local-backend-exec-shell-delegates
  (let [r (eb/exec-shell eb/local-backend "echo via-backend" {:timeout-ms 5000})]
    (is (= 0 (:exit r)))
    (is (= "via-backend\n" (:output r)))))

;; ============================================================================
;; Protocol swappability + clj-eval injection
;; ============================================================================

(deftest protocol-is-swappable
  (let [stub (reify eb/ExecutionBackend
               (exec-shell [_ command _] {:exit 0 :output (str "stub:" command) :error ""})
               (exec-clj-code [_ code _] {:lang "clojure" :code code :result "stub" :output "" :error ""}))]
    (is (= "stub:ls" (:output (eb/exec-shell stub "ls" {}))))
    (is (= "stub" (:result (eb/exec-clj-code stub "(+ 1 2)" {}))))))

(deftest clj-eval-injection-seam
  (testing "LocalBackend.exec-clj-code dispatches to the injected fn"
    (let [seen (atom nil)]
      (eb/set-local-clj-eval! (fn [code opts] (reset! seen [code opts])
                                {:lang "clojure" :code code :result "ok" :output "" :error ""}))
      (try
        (let [r (eb/exec-clj-code eb/local-backend "(inc 41)" {:agent :A :sandbox :S})]
          (is (= "ok" (:result r)))
          (is (= "(inc 41)" (first @seen)))
          (is (= :A (:agent (second @seen)))))
        (finally
          ;; restore the "unwired" default so we don't leak a stub into other
          ;; namespaces' tests if coact-agent isn't loaded in this run.
          (eb/set-local-clj-eval! (fn [_ _] {:error "unwired"})))))))

;; ============================================================================
;; resolve-backend
;; ============================================================================

(deftest resolve-backend-local
  (with-redefs [config/get-config (fn [& _] :local)]
    (is (identical? eb/local-backend (eb/resolve-backend nil))))
  (testing "unknown backend falls back to local"
    (with-redefs [config/get-config (fn [& _] :docker)]
      (is (identical? eb/local-backend (eb/resolve-backend nil))))))

;; ============================================================================
;; clj-nrepl endpoint is host:port-configurable (R4 remote seam)
;; ============================================================================

(deftest nrepl-endpoint-is-threaded
  (let [server (nrepl/start-server! :port 0)]
    (try
      (let [port (nrepl/server-port)]
        (testing "explicit loopback :host + correct :port evaluates"
          (let [r (nrepl/eval-string "(+ 1 2)" :host "127.0.0.1" :port port :timeout-ms 5000)]
            (is (= "3" (:result r)))))
        (testing "a WRONG :port fails to connect even though the server is up — proving :port is used, not server-port"
          (let [r (nrepl/eval-string "(+ 1 2)" :host "127.0.0.1" :port (inc port) :timeout-ms 1500)]
            (is (nil? (:result r)))
            (is (re-find #"transport error" (str (:error r)))))))
      (finally (nrepl/stop-server! server)))))

(deftest nrepl-remote-host-skips-local-running-gate
  ;; An explicit non-loopback host is treated as a remote endpoint, so the local
  ;; `running?` gate is skipped regardless of whether a local server is up — it
  ;; attempts the remote connect and fails at transport instead. 127.0.0.2 is
  ;; loopback-range but outside the loopback-host set, so nothing listens there.
  (let [r (nrepl/eval-string "(+ 1 2)" :host "127.0.0.2" :port 1 :timeout-ms 1000)]
    (is (not (re-find #"not running" (str (:error r))))
        "remote endpoint bypasses the local running? gate")
    (is (some? (:error r)) "still errors (transport) — just not the local gate")))
