;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.mcp.op-test
  "Tests for `mcp-op` — the turn-free face of `mcp$server` / `mcp$tools` /
   `mcp$lifecycle`, exposed on the session ask socket as `{:op :mcp}`.

   What these pin down is the part a CONSOLE depends on and the tool-defs never
   promised: that a list is a data shape rather than a model's prose, that a
   disconnected server is a fact and not an error, and that a caller can tell
   whether flipping a switch persisted. The MCP runtime itself is stubbed —
   connecting a real server means spawning a process, and none of the behaviour
   under test is about the wire protocol."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent.mcp.client :as mcp-client]
            [ai.brainyard.agent.mcp.commands :as mcp-cmd]
            [ai.brainyard.agent.mcp.integration :as mcp-int]))

;; =============================================================================
;; Fixtures — a two-server runtime, one connected
;; =============================================================================

(def ^:private configs
  {"linear" {:transport :stdio :enabled true}
   ;; String transport + `:lazy` — config.edn is hand-editable, so both the
   ;; keyword and the string form reach the runtime atom.
   "redis"  {:transport "stdio" :enabled false :lazy true}})

(def ^:private linear-tools
  [{:server-name "linear" :name "list_projects" :description "List projects"
    :parameters {:type "object"} :annotations {:readOnlyHint true}}
   {:server-name "linear" :name "create_issue" :description "Create an issue"
    :parameters {:type "object"}}])

(defmacro with-runtime
  "Body with a live MCP runtime: `linear` connected with two tools, `redis`
   configured but not."
  [& body]
  `(with-redefs [mcp-int/mcp-initialized?        (constantly true)
                 mcp-int/list-configured-servers (constantly ["linear" "redis"])
                 mcp-int/get-mcp-server-config   configs
                 mcp-client/list-active-clients  (constantly ["linear"])
                 mcp-int/cached-server-tools     (fn [_# _#] linear-tools)
                 mcp-int/cached-all-server-tools (fn [_#] linear-tools)]
     ~@body))

;; =============================================================================
;; Reads
;; =============================================================================

(deftest list-servers-reports-config-state-not-just-liveness-test
  (with-runtime
    (let [r  (mcp-cmd/mcp-op nil {:action :list-servers})
          by (into {} (map (juxt :name identity)) (:servers r))]
      (is (= 2 (:total r)))
      (is (= 1 (:connected r)))
      (testing "the live half"
        (is (true? (:connected (by "linear"))))
        (is (false? (:connected (by "redis")))))
      (testing "and the config half, which decides the NEXT session"
        ;; The console's alternative is reading config.edn, where an untouched
        ;; builtin has no entry at all — its `:enabled` exists only in the
        ;; runtime atom the builtins were merged into.
        (is (true? (:enabled (by "linear"))))
        (is (false? (:enabled (by "redis"))))
        (is (true? (:lazy? (by "redis")))))
      (testing "transport is a string either way it was written"
        (is (= "stdio" (:transport (by "linear"))))
        (is (= "stdio" (:transport (by "redis")))))
      (testing "and the reply admits the runtime is process-wide"
        (is (true? (:host-wide? r)))))))

(deftest list-tools-marks-read-only-tools-test
  (with-runtime
    (let [r  (mcp-cmd/mcp-op nil {:action :list-tools :server-name "linear"})
          by (into {} (map (juxt :name identity)) (:tools r))]
      (is (true? (:connected r)))
      (is (= 2 (:total r)))
      ;; Same flag the permission gate reads, so a console can mark which tools
      ;; are safe to try without prompting. Absent ⇒ false, as the gate reads it.
      (is (true? (:read-only? (by "list_projects"))))
      (is (false? (:read-only? (by "create_issue")))))))

(deftest list-tools-omits-schemas-unless-asked-test
  (with-runtime
    (let [plain (mcp-cmd/mcp-op nil {:action :list-tools :server-name "linear"})
          full  (mcp-cmd/mcp-op nil {:action :list-tools :server-name "linear" :schemas true})]
      ;; The largest field by far, on a reply that gets polled.
      (is (not-any? :parameters (:tools plain)))
      (is (every? :parameters (:tools full))))))

(deftest list-tools-of-a-disconnected-server-is-not-an-error-test
  (with-runtime
    (let [r (mcp-cmd/mcp-op nil {:action :list-tools :server-name "redis"})]
      ;; Servers connect in background futures after boot, so "not connected
      ;; yet" is the normal state a poller sees — a fact, not a failure.
      (is (nil? (:error r)))
      (is (false? (:connected r)))
      (is (= [] (:tools r))))))

(deftest list-tools-without-a-server-spans-connected-servers-test
  (with-runtime
    (let [r (mcp-cmd/mcp-op nil {:action :list-tools})]
      (is (= ["linear"] (:servers r)))
      (is (= 2 (:total r))))))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(deftest start-and-stop-report-that-they-persisted-test
  (with-runtime
    (with-redefs [mcp-int/start-mcp-server!      (fn [& _] :connected)
                  mcp-int/stop-mcp-server!       (fn [& _] :stopped)
                  mcp-int/reconnect-mcp-server!  (fn [& _] :reconnected)]
      (testing "start/stop write [:mcp :servers <name> :enabled] to config.edn"
        (is (true? (:persisted (mcp-cmd/mcp-op nil {:action :start :server-name "linear"}))))
        (is (true? (:persisted (mcp-cmd/mcp-op nil {:action :stop :server-name "linear"})))))
      (testing "restart is a live reconnect and writes nothing"
        ;; A caller that treated the two alike would tell the user a setting was
        ;; saved that will be gone at the next session start.
        (is (false? (:persisted (mcp-cmd/mcp-op nil {:action :restart :server-name "linear"}))))))))

(deftest lifecycle-refuses-an-unknown-server-test
  (with-runtime
    (let [r (mcp-cmd/mcp-op nil {:action :start :server-name "ghost"})]
      (is (str/includes? (:error r) "not found in configuration")))
    (is (str/includes? (:error (mcp-cmd/mcp-op nil {:action :start})) "server-name is required"))))

;; =============================================================================
;; Wire-level robustness — the caller is a socket, not a REPL
;; =============================================================================

(deftest actions-may-arrive-as-strings-test
  (with-runtime
    ;; An external driver hand-builds the EDN frame; `:action "list-servers"`
    ;; and `:action ":list-servers"` are both things it plausibly sends.
    (is (= 2 (:total (mcp-cmd/mcp-op nil {:action "list-servers"}))))
    (is (= 2 (:total (mcp-cmd/mcp-op nil {:action ":list-tools" :server-name "linear"}))))))

(deftest unknown-action-names-the-valid-ones-test
  (with-runtime
    (let [e (:error (mcp-cmd/mcp-op nil {:action :explode}))]
      (is (str/includes? e ":list-servers"))
      (is (str/includes? e ":restart")))))

(deftest an-uninitialized-runtime-errors-rather-than-answering-empty-test
  (with-redefs [mcp-int/mcp-initialized? (constantly false)]
    ;; An empty list here would read as "nothing is configured", which is a
    ;; different answer and a wrong one.
    (is (str/includes? (:error (mcp-cmd/mcp-op nil {:action :list-servers})) "not initialized"))
    (is (str/includes? (:error (mcp-cmd/mcp-op nil {:action :start :server-name "linear"})) "not initialized"))))

(deftest a-throwing-runtime-becomes-an-error-map-test
  (with-runtime
    (with-redefs [mcp-int/cached-server-tools (fn [& _] (throw (ex-info "boom" {})))]
      ;; The handler `pr-str`s this straight onto the socket; an escaped
      ;; exception would close the connection with no reply at all.
      (let [r (mcp-cmd/mcp-op nil {:action :list-tools :server-name "linear"})]
        (is (str/includes? (:error r) "boom"))))))
