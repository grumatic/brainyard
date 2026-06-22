;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.mcp.integration-oauth-test
  "OAuth helpers on the MCP integration layer (Phase 4): account-id derivation,
   status, logout, and forced re-auth that back the /login /logout /mcp auth
   TUI commands."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.clj-oauth.interface :as oauth]
            [ai.brainyard.agent.mcp.integration :as integration]
            [ai.brainyard.agent.mcp.client :as mcp-client]))

(def ^:private servers
  {"notion" {:transport :http :config {:url "u" :auth {:type :oauth}}}
   "pinned" {:transport :http :config {:url "u" :auth {:type :oauth :account-id "acct-x"}}}
   "plain"  {:transport :http :config {:url "u" :headers {"Authorization" "Bearer t"}}}})

(defmacro with-servers [& body]
  `(with-redefs [integration/get-mcp-server-config (fn [sn#] (get servers sn#))
                 integration/list-configured-servers (fn [] (vec (keys servers)))]
     ~@body))

(deftest oauth-server?-and-account-id
  (with-servers
    (testing "mcp-oauth-server? keys off :config :auth :type"
      (is (true?  (integration/mcp-oauth-server? "notion")))
      (is (true?  (integration/mcp-oauth-server? "pinned")))
      (is (false? (integration/mcp-oauth-server? "plain"))))
    (testing "account-id defaults to server name, explicit pin wins"
      (is (= "notion" (integration/mcp-oauth-account-id "notion")))
      (is (= "acct-x" (integration/mcp-oauth-account-id "pinned"))))))

(deftest oauth-status
  (with-servers
    (with-redefs [oauth/authenticated? (fn [acct] (= acct "notion"))]
      (let [status (integration/mcp-oauth-status)]
        (testing "only OAuth-configured servers are reported"
          (is (= #{"notion" "pinned"} (set (map :server status)))))
        (testing "authenticated? is surfaced per account"
          (is (true?  (:authenticated? (first (filter #(= "notion" (:server %)) status)))))
          (is (false? (:authenticated? (first (filter #(= "pinned" (:server %)) status))))))))))

(deftest oauth-logout
  (with-servers
    (let [logged-out (atom nil)]
      (with-redefs [oauth/logout! (fn [acct] (reset! logged-out acct))]
        (testing "clears credentials for an OAuth server, keyed by account-id"
          (is (= {:server "pinned" :account-id "acct-x"}
                 (integration/mcp-oauth-logout! "pinned")))
          (is (= "acct-x" @logged-out)))
        (testing "no-op for a non-OAuth server"
          (reset! logged-out nil)
          (is (nil? (integration/mcp-oauth-logout! "plain")))
          (is (nil? @logged-out)))))))

(deftest reauth
  (with-servers
    (testing "non-OAuth server throws"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not OAuth-configured"
                            (integration/reauth-mcp-server! "plain"))))
    (testing "OAuth server: clears token then reconnects, returns account info"
      (let [steps (atom [])]
        (with-redefs [oauth/logout! (fn [acct] (swap! steps conj [:logout acct]))
                      integration/reconnect-mcp-server! (fn [sn] (swap! steps conj [:reconnect sn]))]
          (is (= {:server "notion" :account-id "notion"}
                 (integration/reauth-mcp-server! "notion")))
          (testing "logout happens before reconnect (so connect! re-runs login)"
            (is (= [[:logout "notion"] [:reconnect "notion"]] @steps))))))))
