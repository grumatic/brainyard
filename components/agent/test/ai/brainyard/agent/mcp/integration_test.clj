;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.mcp.integration-test
  "Tests for per-server tool-arg overrides — a configured value
   (`:tool-arg-overrides`) is force-applied to MCP tool calls whose schema
   declares that arg, so identity/config args like :user_google_email come from
   config instead of being guessed by the model."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.mcp.integration :as itg]))

(defn- set-overrides! [m] (reset! @#'itg/!server-tool-overrides m))

(use-fixtures :each (fn [t] (set-overrides! {}) (t) (set-overrides! {})))

(defn- wrapper-for [server tool accepted-keys]
  (#'itg/make-mcp-wrapper-fn server tool accepted-keys))

(deftest override-forces-configured-value
  (testing "override wins over the LLM-supplied value when the tool declares the key"
    (set-overrides! {"gw" {:user_google_email "me@authorized.com"}})
    (let [captured (atom nil)]
      (with-redefs [itg/call-server-tool (fn [_srv _tool args]
                                           (reset! captured args)
                                           {:success true :result {:ok true}})]
        (let [result ((wrapper-for "gw" "search_gmail_messages"
                                   #{:user_google_email :query})
                      :query "newer_than:2d" :user_google_email "wrong@byuserid.com")]
          (is (= {:ok true} result))
          (is (= "me@authorized.com" (get @captured "user_google_email"))
              "override replaces the LLM-provided email")
          (is (= "newer_than:2d" (get @captured "query"))
              "non-override args pass through untouched"))))))

(deftest override-skips-undeclared-keys
  (testing "an override key the tool does not declare is NOT injected"
    (set-overrides! {"gw" {:user_google_email "me@authorized.com"}})
    (let [captured (atom nil)]
      (with-redefs [itg/call-server-tool (fn [_srv _tool args]
                                           (reset! captured args)
                                           {:success true :result {}})]
        ((wrapper-for "gw" "some_tool" #{:query}) :query "x")
        (is (not (contains? @captured "user_google_email"))
            "override not added — tool doesn't accept it")
        (is (= "x" (get @captured "query")))))))

(deftest no-override-is-passthrough
  (testing "no override configured for the server → args pass through unchanged"
    (let [captured (atom nil)]
      (with-redefs [itg/call-server-tool (fn [_srv _tool args]
                                           (reset! captured args)
                                           {:success true :result {}})]
        ((wrapper-for "other" "t" #{:user_google_email}) :user_google_email "llm@val.com")
        (is (= "llm@val.com" (get @captured "user_google_email")))))))
