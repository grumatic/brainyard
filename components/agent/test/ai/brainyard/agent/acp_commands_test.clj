;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.acp-commands-test
  "Tests for the acp$* management command family.

   The lifecycle test spawns ONE in-tree :stub backend (clj subprocess) and
   drives list/detail/ask/update/close through the registry with a bound caller.
   The guard tests (cap, provisioned-vs-root, owned-subagent) need no subprocess."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.agent.interface :as agent]
            [ai.brainyard.agent.common.acp-agent :as acp-agent]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.test-support :as ts]))

;; The lifecycle test opens a real ACP session, which persists a trajectory
;; under acp-cmd-<ms>. Keep it out of the developer's sessions dir.
(use-fixtures :each ts/with-tmp-sessions-root)

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
            ;; Scope to this test's session — acp$list with no :session-id spans
            ;; ALL sessions (like agent-registry$list), so other tests' instances
            ;; would otherwise leak in.
            (let [l (tool/invoke-tool :acp$list {:session-id sid})]
              (is (= 1 (:total l)))
              (let [row (first (:acp-agents l))]
                (is (= acp-id-str (:acp-id row)))
                (is (= :stub (:backend row)))
                (is (= "echo bot" (:purpose row)))
                (is (true? (:provisioned? row))))))

          (testing "backend filter matches / mismatches"
            (is (= 1 (:total (tool/invoke-tool :acp$list {:session-id sid :backend "stub"}))))
            (is (= 0 (:total (tool/invoke-tool :acp$list {:session-id sid :backend "gemini"})))))

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
                           (:acp-agents (tool/invoke-tool :acp$list {:session-id sid}))))))
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
            (try (.close owned) (catch Throwable _)))))))

  (testing "acp$update/close are ownership-fenced — a subagent may not touch a connection it didn't dispatch"
    (let [sid  (str "acp-own-" (System/currentTimeMillis))
          root (agent/setup-agent-by-id
                :coact-agent :agent-session {:user-id "u" :session-id sid})
          sub  (agent/setup-agent-by-id
                :coact-agent :agent-session {:user-id "u" :session-id sid} :parent-agent root)
          conn (provision! sid :provisioned? true)] ; owner nil, NOT dispatched by sub
      (binding [proto/*current-agent* sub]
        (try
          (let [cid (subs (str (proto/agent-id conn)) 1)]
            (is (re-find #"Not owned by you"
                         (str (:error (tool/invoke-tool :acp$close {:id cid})))))
            (is (re-find #"Not owned by you"
                         (str (:error (tool/invoke-tool :acp$update {:id cid :purpose "x"}))))))
          (finally
            (.close root)
            (try (.close sub) (catch Throwable _))
            (try (.close conn) (catch Throwable _))))))))

(deftest ^:integration acp-create-close-roundtrip-test
  (testing "acp$close reaps a connection that acp$create provisioned (regression: the owner-first guard refused every one of them)"
    (let [sid    (str "acp-roundtrip-" (System/currentTimeMillis))
          caller (agent/setup-agent-by-id
                  :coact-agent
                  :agent-session {:user-id "u" :session-id sid})]
      (binding [proto/*current-agent* caller]
        (try
          (let [created (tool/invoke-tool :acp$create
                                          {:backend      "stub"
                                           :purpose      "roundtrip"
                                           :backend-opts {:chunk-delay-ms 5}})
                acp-id  (:acp-id created)]
            (is (nil? (:error created)) (str "acp$create failed: " (:error created)))
            (is (string? acp-id))

            (testing "acp$create ALWAYS parents the connection — the precondition that made the old owner-first guard unreachable"
              (let [row (first (filter #(= acp-id (:acp-id %))
                                       (:acp-agents (tool/invoke-tool :acp$list {:session-id sid}))))]
                (is (some? row) "created connection shows up in acp$list")
                (is (some? (:owner row)) "a provisioned connection has a NON-NIL owner (:parent-agent is unconditional)")
                (is (true? (:provisioned? row)) "mark-provisioned! flagged it")))

            (testing "acp$close closes it instead of deflecting to agent-registry$close"
              (let [closed (tool/invoke-tool :acp$close {:id acp-id})]
                (is (not (re-find #"owned subagent" (str (:error closed))))
                    "acp$close must not send an acp$create-provisioned connection to agent-registry$close")
                (is (not (re-find #"TUI-attached root" (str (:error closed)))))
                (is (nil? (:error closed)) (str "acp$close failed: " (:error closed)))
                (is (:closed closed))))

            (testing "the connection is gone from acp$list"
              (is (empty? (filter #(= acp-id (:acp-id %))
                                  (:acp-agents (tool/invoke-tool :acp$list {:session-id sid})))))))
          (finally (.close caller)))))))
