;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.acp-commands-test
  "Tests for the acp$* management command family.

   The lifecycle test spawns ONE in-tree :stub backend (clj subprocess) and
   drives list/detail/ask/update/close through the registry with a bound caller.
   The guard tests (cap, provisioned-vs-root, owned-subagent) need no subprocess."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent.common.acp-agent :as acp-agent]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.core.protocol :as proto]))

(defn- provision!
  "Provision an acp-agent instance in `sid` on the :stub backend (no ask).
   `connect?` opens the session; `parent` sets the owner (→ owned subagent)."
  [sid & {:keys [connect? provisioned? parent purpose]}]
  (let [ag (apply agent/setup-agent-by-id
                  :acp-agent
                  (concat [:agent-session {:user-id "u" :session-id sid}
                           :acp-backend :stub
                           :acp-backend-opts {:chunk-delay-ms 5}]
                          (when parent [:parent-agent parent])))]
    (when connect? (acp-agent/ensure-connected! ag))
    (when provisioned? (acp-agent/mark-provisioned! ag))
    (when purpose (acp-agent/set-purpose! ag purpose))
    ag))

(deftest ^:integration acp-commands-lifecycle-test
  (testing "acp$list/detail/ask/update/close manage a provisioned :stub connection"
    (let [sid    (str "acp-cmd-" (System/currentTimeMillis))
          caller (agent/setup-agent-by-id
                  :coact-agent
                  :agent-session {:user-id "u" :session-id sid}
                  :max-acp-agents-per-session 1)
          ag1    (provision! sid :connect? true :provisioned? true :purpose "echo bot")
          acp-id (proto/agent-id ag1)
          acp-id-str (subs (str acp-id) 1)]
      (binding [proto/*current-agent* caller]
        (try
          (testing "acp$list surfaces the connection with backend/model/purpose"
            (let [l (tool/invoke-tool :acp$list {})]
              (is (= 1 (:total l)))
              (let [row (first (:acp-agents l))]
                (is (= acp-id-str (:acp-id row)))
                (is (= :stub (:backend row)))
                (is (= "echo bot" (:purpose row)))
                (is (true? (:provisioned? row))))))

          (testing "backend filter matches / mismatches"
            (is (= 1 (:total (tool/invoke-tool :acp$list {:backend "stub"}))))
            (is (= 0 (:total (tool/invoke-tool :acp$list {:backend "gemini"})))))

          (testing "acp$detail returns descriptor + advertised models accessor"
            (let [d (tool/invoke-tool :acp$detail {:id acp-id-str})]
              (is (= acp-id-str (:acp-id d)))
              (is (= :stub (get-in d [:descriptor :backend])))))

          (testing "acp$ask reuses the connection (stub echoes the question)"
            (let [a (tool/invoke-tool :acp$ask {:id acp-id-str :question "ping alpha"})]
              (is (re-find #"alpha" (str (:answer a))))))

          (testing "acp$update relabels the purpose"
            (tool/invoke-tool :acp$update {:id acp-id-str :purpose "renamed bot"})
            (is (= "renamed bot"
                   (get-in (tool/invoke-tool :acp$detail {:id acp-id-str})
                           [:descriptor :purpose]))))

          (testing "acp$create refuses at the per-session cap (1) — external session never silently evicted"
            (is (re-find #"cap reached"
                         (str (:error (tool/invoke-tool :acp$create {:backend "stub"}))))))

          (testing "acp$close reaps the provisioned connection"
            (is (:closed (tool/invoke-tool :acp$close {:id acp-id-str})))
            (is (not (some #(= acp-id-str (:acp-id %))
                           (:acp-agents (tool/invoke-tool :acp$list {}))))))
          (finally
            (.close caller)
            (try (.close ag1) (catch Throwable _))))))))

(deftest acp-command-guards-test
  (testing "acp$create requires :backend"
    (let [sid    (str "acp-guard-" (System/currentTimeMillis))
          caller (agent/setup-agent-by-id
                  :coact-agent :agent-session {:user-id "u" :session-id sid})]
      (binding [proto/*current-agent* caller]
        (try
          (is (re-find #":backend is required"
                       (str (:error (tool/invoke-tool :acp$create {})))))
          (finally (.close caller))))))

  (testing "acp$close refuses a TUI-attached root and an owned subagent; only provisioned roots"
    (let [sid    (str "acp-close-" (System/currentTimeMillis))
          caller (agent/setup-agent-by-id
                  :coact-agent :agent-session {:user-id "u" :session-id sid})
          tui-root (provision! sid)                       ; owner nil, NOT provisioned
          owned    (provision! sid :parent caller)]       ; owner = caller
      (binding [proto/*current-agent* caller]
        (try
          (is (re-find #"TUI-attached root"
                       (str (:error (tool/invoke-tool :acp$close
                                                      {:id (subs (str (proto/agent-id tui-root)) 1)})))))
          (is (re-find #"owned subagent"
                       (str (:error (tool/invoke-tool :acp$close
                                                      {:id (subs (str (proto/agent-id owned)) 1)})))))
          (finally
            (.close caller)
            (try (.close tui-root) (catch Throwable _))
            (try (.close owned) (catch Throwable _))))))))
