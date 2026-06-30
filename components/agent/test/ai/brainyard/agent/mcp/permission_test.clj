;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.mcp.permission-test
  "Tests for the fail-closed MCP permission gate (`:agent.tool-use/pre`). Both
   call paths — native `mcp$<server>$<tool>` bindings and the `mcp$tools :op
   :call` proxy — must route side-effecting MCP calls through approval, with
   readOnlyHint / `:mcp-allow-tools` / `[:permissions :mode]` downgrades."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.mcp.permission :as mp]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :as tool]))

(def ^:private saved-global (atom ::unset))

(use-fixtures :each
  (fn [t]
    (reset! saved-global @config/!global-config)
    (t)
    (reset! config/!global-config @saved-global)))

(defn- gate [tool-name args & {:keys [agent]}]
  (mp/mcp-permission-gate {:agent agent :tool-name tool-name :args args}))

(defn- stub-agent
  "Minimal agent shape: just a session atom carrying an optional :permission-fn."
  [pfn]
  {:!session (atom {:config (cond-> {} pfn (assoc :permission-fn pfn))})})

;; ---------------------------------------------------------------------------
;; Target extraction
;; ---------------------------------------------------------------------------

(deftest native-target-parsing
  (is (= {:server "linear" :tool "create_issue"} (mp/native-target "mcp$linear$create_issue")))
  (is (nil? (mp/native-target "write_file")) "non-MCP tool")
  (is (nil? (mp/native-target "mcp$tools")) "bare management command, not a binding"))

(deftest proxy-call-detection
  (is (true?  (mp/proxy-call? "mcp$tools" {:op :call})))
  (is (true?  (mp/proxy-call? "mcp$tools" {:op "call"})))
  (is (false? (mp/proxy-call? "mcp$tools" {:op "list"})))
  (is (false? (mp/proxy-call? "mcp$server" {:op "call"}))))

;; ---------------------------------------------------------------------------
;; Fail-closed (no permission channel)
;; ---------------------------------------------------------------------------

(deftest native-side-effecting-headless-refused
  (testing "no permission-fn ⇒ side-effecting native MCP call is refused (fail-closed)"
    (let [v (gate "mcp$linear$create_issue" {})]
      (is (= :replace (:result v)))
      (is (string? (get-in v [:replacement :error]))))))

(deftest non-mcp-tools-untouched
  (is (nil? (gate "write_file" {})))
  (is (nil? (gate "bash" {:command "ls"})))
  (is (nil? (gate "mcp$tools" {:op "list"})) "discovery op is not gated"))

;; ---------------------------------------------------------------------------
;; readOnlyHint downgrade
;; ---------------------------------------------------------------------------

(deftest read-only-hint-auto-allows
  (testing "a readOnlyHint-annotated native binding is auto-allowed (no gate)"
    (swap! tool/!tool-defs assoc :mcp$ro$peek
           {:id :mcp$ro$peek :type :tool :fn (fn [& _] {})
            :meta {:id :mcp$ro$peek :mcp-server "ro" :mcp-tool "peek"
                   :mcp-annotations {:readOnlyHint true}}})
    (try
      (is (nil? (gate "mcp$ro$peek" {})))
      (finally (swap! tool/!tool-defs dissoc :mcp$ro$peek)))))

;; ---------------------------------------------------------------------------
;; Allowlist downgrade
;; ---------------------------------------------------------------------------

(deftest allowlist-globs
  (testing ":mcp-allow-tools globs auto-allow matching server/tool"
    (reset! config/!global-config {:mcp-allow-tools ["linear/*"]})
    (is (nil? (gate "mcp$linear$create_issue" {})) "matched glob ⇒ allow")
    (is (= :replace (:result (gate "mcp$slack$post_message" {}))) "unmatched ⇒ still gated")))

;; ---------------------------------------------------------------------------
;; Mode honoring
;; ---------------------------------------------------------------------------

(deftest mode-auto-approve
  (reset! config/!global-config {:permission-mode :auto-approve})
  (is (nil? (gate "mcp$linear$create_issue" {}))))

(deftest mode-deny-by-default
  (reset! config/!global-config {:permission-mode :deny-by-default})
  (is (= :replace (:result (gate "mcp$linear$create_issue" {})))))

;; ---------------------------------------------------------------------------
;; Interactive prompt via permission-fn
;; ---------------------------------------------------------------------------

(deftest interactive-allow-and-deny
  (testing "permission-fn allow ⇒ proceed; deny ⇒ refuse"
    (is (nil? (gate "mcp$linear$create_issue" {}
                    :agent (stub-agent (fn [_] {:allowed true})))))
    (is (= :replace (:result (gate "mcp$linear$create_issue" {}
                                   :agent (stub-agent (fn [_] {:denied true :reason "nope"}))))))))

(deftest interactive-request-shape
  (testing "the gate hands permission-fn a :type :mcp-tool request with server/tool"
    (let [seen (atom nil)]
      (gate "mcp$linear$create_issue" {}
            :agent (stub-agent (fn [req] (reset! seen req) {:allowed true})))
      (is (= :mcp-tool (:type @seen)))
      (is (= ["linear/create_issue"] (:tools @seen)))
      (is (= ["linear"] (:servers @seen))))))

;; ---------------------------------------------------------------------------
;; Proxy batch
;; ---------------------------------------------------------------------------

(deftest proxy-batch-side-effecting-gated
  (testing "a :call batch with a side-effecting target is gated; headless ⇒ refuse"
    (let [v (gate "mcp$tools" {:op "call"
                               :tool-calls [{:server-name "slack" :tool-name "post_message"}]})]
      (is (= :replace (:result v))))))

(deftest proxy-unparseable-fail-closed
  (testing "a :call whose tool-calls can't be introspected (raw string) is fail-closed"
    (let [v (gate "mcp$tools" {:op "call" :tool-calls "[{...}]"})]
      (is (= :replace (:result v))))))
