;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.mcp.client-test
  "Unit tests for MCP client construction — focused on the per-request
   `:timeout` plumbing. A stdio server (e.g. Gmail/Calendar via mcp-remote)
   must be able to raise the 30s `send-request!` default through its
   `:config :timeout`, mirroring the HTTP transport."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent.mcp.client :as mcp-client]))

(deftest stdio-client-carries-timeout
  (testing "create-client :stdio threads :timeout from config onto the record"
    (let [client (mcp-client/create-client :stdio {:timeout 90000})]
      (is (= 90000 (:timeout client))
          "send-request! reads (:timeout client); it must be present for stdio")))

  (testing "absent :timeout → nil, so send-request! falls back to its 30s default"
    (let [client (mcp-client/create-client :stdio {})]
      (is (nil? (:timeout client)))))

  (testing "connect!'s assoc-threading preserves :timeout onto the connected record"
    ;; connect! returns (-> client (assoc :process ...) (assoc :stdin ...) ...);
    ;; assoc onto a defrecord keeps extension-map keys, so the registered client
    ;; still carries the timeout. Assert the invariant without a live process.
    (let [client    (mcp-client/create-client :stdio {:timeout 75000})
          connected (assoc client :process :fake :stdin :fake)]
      (is (= 75000 (:timeout connected))))))

(deftest http-client-carries-timeout
  (testing "create-client :http carries :timeout in :options (parity with stdio)"
    (let [client (mcp-client/create-client :http {:timeout 90000})]
      ;; HTTP send-request reads (:timeout (:options client)).
      (is (= 90000 (:timeout (:options client))))))

  (testing "absent :timeout → nil in :options, 30s default applies"
    (let [client (mcp-client/create-client :http {})]
      (is (nil? (:timeout (:options client)))))))

(deftest stderr-drain-reads-all-lines-and-terminates
  (testing "drain-stderr-lines! forwards every line to the sink and returns at EOF (no hang/blocking)"
    (let [collected (atom [])
          rdr (java.io.BufferedReader.
               (java.io.StringReader.
                "Connecting...\nPlease authorize: https://accounts.google.com/o/oauth2/v2/auth?x=1\nDone\n"))
          drain @#'mcp-client/drain-stderr-lines!]
      (drain rdr (fn [l] (swap! collected conj l)))
      (is (= ["Connecting..."
              "Please authorize: https://accounts.google.com/o/oauth2/v2/auth?x=1"
              "Done"]
             @collected)
          "all stderr lines drained, including the auth URL; loop exits at EOF"))))
