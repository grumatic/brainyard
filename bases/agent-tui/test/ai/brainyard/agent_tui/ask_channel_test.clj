;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.ask-channel-test
  "Integration test for the side ask channel inside a TUI session: the real
   per-session AF_UNIX listener, the real client, and the real response-shaping
   in `ask-handle-fn` — only the agent/queue boundary (`inject-side-ask!`) is
   stubbed, so no LLM is needed."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent-tui.core :as core]
            [ai.brainyard.ask-channel.interface :as ask]
            [ai.brainyard.agent-tui-persist.interface :as persist]
            [ai.brainyard.agent.interface :as agent])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmp-root []
  (.toFile (Files/createTempDirectory "ask-it" (make-array FileAttribute 0))))

(def ^:private fake-ag
  (reify java.io.Closeable (close [_] nil)))

(deftest listener-lifecycle-and-attach
  (let [root (tmp-root)
        sid  "agt-test-123"]
    (persist/with-root root
      (with-redefs [agent/session-id  (fn [_] sid)
                    agent/get-config  (fn [k] (case k
                                                :ask-channel-enabled? true
                                                :ask-timeout-ms 5000
                                                nil))
                    core/inject-side-ask! (fn [_ag question]
                                            (doto (promise)
                                              (deliver {:answer (str "ECHO:" question)})))]
        (testing "start opens the socket and records its path in meta.edn"
          (core/start-ask-listener! fake-ag)
          (let [sock (persist/file-of sid :ask-sock)]
            (is (.exists sock) "ask.sock should exist after start")
            (is (= (.getAbsolutePath sock) (:ask-socket-path (persist/read-meta sid)))
                "meta.edn should carry :ask-socket-path")

            (testing "a client reaches the agent and gets the answer back"
              (let [resp (ask/ask-via-socket! {:path (.getAbsolutePath sock)
                                               :question "hi"
                                               :timeout-ms 5000})]
                ;; The response also carries LM metadata (:provider/:model/:agent,
                ;; added by the `--json` ask feature) sourced from the process-global
                ;; default LM — irrelevant to and unstable for this channel-roundtrip
                ;; test, so assert only the roundtrip essentials.
                (is (= {:status :ok :answer "ECHO:hi" :usage nil}
                       (select-keys resp [:status :answer :usage])))))

            (testing "an empty question is rejected without injecting"
              (let [resp (ask/ask-via-socket! {:path (.getAbsolutePath sock)
                                               :question "   "
                                               :timeout-ms 5000})]
                (is (= :error (:status resp)))))))

        (testing "start is idempotent per session-id (no second listener)"
          (let [before (count @@#'core/!ask-listeners)]
            (core/start-ask-listener! fake-ag)
            (is (= before (count @@#'core/!ask-listeners)))))

        (testing "stop unlinks the socket and clears the registry"
          (core/stop-ask-listener! sid)
          (is (not (.exists (persist/file-of sid :ask-sock))))
          (is (not (contains? @@#'core/!ask-listeners sid))))))))

(deftest config-op-reads-effective-config
  (testing ":config returns a non-blocking effective-config read (no turn injected)"
    (let [root (tmp-root) sid "agt-cfg-1"]
      (persist/with-root root
        (with-redefs [agent/session-id (fn [_] sid)
                      agent/get-config (fn [k] (case k
                                                 :ask-channel-enabled? true
                                                 :ask-timeout-ms 5000 nil))
                      ;; a :config read must NEVER inject a turn — fail loudly if it does
                      core/inject-side-ask! (fn [_ _]
                                              (throw (ex-info "config op injected a turn!" {})))]
          (try
            (core/start-ask-listener! fake-ag)
            (let [sock (.getAbsolutePath (persist/file-of sid :ask-sock))]
              (testing "full read carries overrides + a redacted snapshot"
                (let [resp (ask/send-op! sock {:op :config})]
                  (is (= :ok (:status resp)))
                  (is (= sid (:session-id resp)))
                  (is (integer? (:total resp)))
                  (is (map? (:overrides resp)))
                  (is (map? (:snapshot resp)))
                  (is (<= (count (:overrides resp)) (:total resp)))))
              (testing "query mode narrows to matching keys"
                (let [resp (ask/send-op! sock {:op :config :query "iteration"})]
                  (is (= :ok (:status resp)))
                  (is (= "iteration" (:query resp)))
                  (is (vector? (:matches resp))))))
            (finally (core/stop-ask-listener! sid))))))))

(deftest disabled-by-config
  (testing "no socket is opened when :ask-channel-enabled? is false"
    (let [root (tmp-root) sid "agt-off-1"]
      (persist/with-root root
        (with-redefs [agent/session-id (fn [_] sid)
                      agent/get-config (fn [_] false)]
          (core/start-ask-listener! fake-ag)
          (is (not (contains? @@#'core/!ask-listeners sid)))
          (is (not (.exists (persist/file-of sid :ask-sock)))))))))

(deftest attach-timeout-when-turn-never-completes
  (testing "a turn that never delivers surfaces a timeout to the client"
    (let [root (tmp-root) sid "agt-slow-1"]
      (persist/with-root root
        (with-redefs [agent/session-id (fn [_] sid)
                      agent/get-config (fn [k] (case k
                                                 :ask-channel-enabled? true
                                                 :ask-timeout-ms 5000 nil))
                      ;; never deliver — exercises the deref timeout path
                      core/inject-side-ask! (fn [_ _] (promise))]
          (try
            (core/start-ask-listener! fake-ag)
            (let [sock (persist/file-of sid :ask-sock)
                  resp (ask/ask-via-socket! {:path (.getAbsolutePath sock)
                                             :question "hi" :timeout-ms 300})]
              (is (= :error (:status resp)))
              (is (re-find #"timed out" (:error resp))))
            (finally (core/stop-ask-listener! sid))))))))
