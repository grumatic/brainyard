;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.debug-agent-test
  "debug-agent registration, lifecycle hooks, and CoAct plumbing
   (per-instance :clj-backend + :nrepl-session-id routes clojure blocks
   to :clj-nrepl-eval and pins the session), plus the nREPL server
   lifecycle commands.

   nREPL is the full-trust backend: the only eval-path check is the
   deny-list (no grant / scope / confirmation / drift). Isolation is the
   SCI sandbox backend's job."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.core.usage :as usage]
            [ai.brainyard.agent.core.agent :as ag]
            [ai.brainyard.agent.task.manager :as task-mgr]
            [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.agent.common.debug-agent :as debug-agent] ;; trigger registration
            [ai.brainyard.clj-nrepl.interface :as clj-nrepl]
            [ai.brainyard.clj-sandbox.interface :as clj-sandbox]))

;; Private fn handles via #'
(def ^:private run-single-block
  #'ai.brainyard.agent.common.coact-agent/run-single-block)
(def ^:private agent-clj-backend
  #'ai.brainyard.agent.common.coact-agent/agent-clj-backend)

(defn- reset-globals! []
  (when-let [mgr (task-mgr/peek-default-manager)]
    (try (tp/shutdown mgr) (catch Exception _)))
  (task-mgr/set-default-manager! nil))

(defn- with-server [t]
  (try
    ;; A prior test in the same JVM may have wiped the global hook registry
    ;; (hooks/reset-hooks! in hooks_test / capture_*); debug-agent's instance
    ;; hooks are registered only at ns-load, so re-establish them here to keep
    ;; these tests order-independent (the :clj-backend :nrepl pin depends on the
    ;; :agent.instance/created hook firing).
    (debug-agent/register-hooks!)
    (clj-nrepl/start-server! :bind "127.0.0.1" :port 0)
    (reset-globals!)
    (t)
    (finally
      (reset-globals!)
      (try (clj-nrepl/stop-server!) (catch Exception _)))))

(use-fixtures :each with-server)

;; ============================================================================
;; Registration
;; ============================================================================

(deftest debug-agent-registered
  (let [td (tool/get-tool-defs :id :debug-agent)]
    (is (some? td))
    (is (= :agent (:type td)))
    (let [tools (set (:tools (get-in td [:meta :agent-tools])))]
      (is (contains? tools :code$eval))
      (is (contains? tools :task$detail))
      (is (contains? tools :clj-nrepl$start-server))
      (testing "source-editing tools — debug-agent makes its own permanent
                fixes (no edit-agent handoff): validate live via code$eval,
                then edit the file and reload"
        (doseq [id [:read-file :update-file :write-file :grep :search :bash]]
          (is (contains? tools id)
              (str id " must be bound so debug-agent can edit + verify source"))))
      (testing "background execution for running a brick's tests post-edit"
        (is (contains? tools :task$run))))))

(deftest nrepl-guide-colocated-and-inlined
  ;; The :nrepl usage guide is the SINGLE SOURCE for live-runtime methodology:
  ;; defined + registered in debug-agent (loaded by this ns), and inlined into
  ;; debug-agent's tool-context. No second hand-written copy.
  (testing "loading debug-agent registers the :nrepl guide"
    (is (some #{:nrepl} (usage/list-usage-topics)))
    (let [g (usage/get-usage-guide :nrepl)]
      (is (string? g))
      (is (str/includes? g "live brainyard"))
      (is (str/includes? g "Inspecting the live brainyard image"))))
  (testing ":nrepl is :user-scoped — kept out of the always-on consult-table
            (debug-agent inlines it directly; others pull it on demand)"
    (is (= :user (:scope (usage/usage-def :nrepl))))
    (is (not (str/includes? (or (usage/consult-table) "") "`:nrepl`"))))
  (testing "debug-agent's tool-context inlines that exact guide (single source)"
    (let [td  (tool/get-tool-defs :id :debug-agent)
          ctx (get-in td [:meta :tool-context])]
      (is (string? ctx))
      ;; the debug-only lifecycle preamble is present...
      (is (str/includes? ctx "TOOL channel ONLY"))
      ;; ...followed verbatim by the registry's :nrepl guide.
      (is (str/includes? ctx (usage/get-usage-guide :nrepl))
          "tool-context must inline the registered guide, not a separate copy"))))

;; ============================================================================
;; Backend selection — agent-clj-backend reads :clj-backend via the unified
;; config chain (per-agent override → session → global → schema default
;; :sandbox). There is no per-fence override; ```clojure :nrepl is a fence
;; error, not a routing hint.
;; ============================================================================

(deftest agent-clj-backend-falls-back-to-sandbox
  (is (= :sandbox (agent-clj-backend nil))
      "nil agent → schema default :sandbox"))

(deftest agent-clj-backend-reads-per-instance-config
  (let [agent (ag/setup-agent-by-id
               :debug-agent
               :agent-session {:user-id "test" :session-id "backend-cfg"})]
    (try
      (is (= :nrepl (agent-clj-backend agent))
          "debug-agent instance is pinned to :nrepl by its lifecycle hook")
      (finally
        (.close ^java.io.Closeable agent)))))

(deftest fence-error-on-trailing-fence-text
  (testing "Clean fence: no fence-error"
    (let [[blk] (clj-sandbox/extract-all-code-blocks-multi
                 "```clojure\n(+ 1 2)\n```")]
      (is (= "clojure" (:lang blk)))
      (is (nil? (:fence-error blk)))))
  (testing "Trailing :nrepl on the fence → fence-error"
    (let [[blk] (clj-sandbox/extract-all-code-blocks-multi
                 "```clojure :nrepl\n(+ 1 2)\n```")]
      (is (= "clojure" (:lang blk)))
      (is (string? (:fence-error blk)))
      (is (str/includes? (:fence-error blk) ":nrepl"))
      (is (str/includes? (:fence-error blk) "per-agent")))))

;; ============================================================================
;; Lifecycle — instance gets session id + default backend pinned
;; ============================================================================

(defn- instance-config
  "`:st-memory-init` is an atom on `:!state`; reach through both."
  [agent]
  (some-> agent :!state deref :st-memory-init deref :config))

(deftest instance-created-pins-session-and-default-backend
  (let [agent (ag/setup-agent-by-id
               :debug-agent
               :agent-session {:user-id "test" :session-id "debug-1"})]
    (try
      (let [cfg (instance-config agent)
            exec-model-for (deref #'ai.brainyard.agent.common.coact-agent/execution-model-for)
            exec-text (exec-model-for agent)]
        (is (= :nrepl (:clj-backend cfg))
            "clj-backend should be pinned to :nrepl on the new instance")
        (is (string? (:nrepl-session-id cfg))
            "session id should be a server-issued string")
        (is (re-find #"LIVE brainyard JVM via clj-nrepl" exec-text)
            "execution-model section keyed off :clj-backend should describe live nREPL routing"))
      (finally
        (.close ^java.io.Closeable agent)))))

(deftest instance-closed-closes-session
  (let [agent (ag/setup-agent-by-id
               :debug-agent
               :agent-session {:user-id "test" :session-id "debug-2"})
        sid   (:nrepl-session-id (instance-config agent))]
    (is (string? sid))
    (.close ^java.io.Closeable agent)
    (is (string? (clj-nrepl/new-session))
        "fresh session must still open after debug-agent close")))

;; ============================================================================
;; CoAct plumbing — :nrepl-session-id flows into the task config
;; ============================================================================

(deftest run-clj-nrepl-block-passes-session-into-task
  (let [agent (ag/setup-agent-by-id
               :debug-agent
               :agent-session {:user-id "test" :session-id "debug-3"})
        sid   (:nrepl-session-id (instance-config agent))]
    (try
      (let [entry (run-single-block
                   nil ;; sandbox unused on :nrepl path
                   {:lang "clojure" :code "(+ 1 2)" :info-args []}
                   {:auto-bg-ms     5000
                    :from-iteration 0
                    :agent          agent})]
        (is (= "clojure" (:lang entry)))
        (is (nil? (some-> entry :error not-empty)))
        (is (= "3" (:result entry))))
      (let [mgr (task-mgr/get-default-manager)
            t   (tp/create-task mgr "probe" :clj-nrepl-eval
                                {:code "(+ 1 2)" :timeout-ms 500 :session sid}
                                {})]
        (is (= sid (-> (tp/get-task mgr (:id t)) :job-config :session))))
      (finally
        (.close ^java.io.Closeable agent)))))

;; ============================================================================
;; nREPL server lifecycle commands — start / stop / status
;; (full-trust: deny-list only, no grant/drift fields)
;; ============================================================================

(deftest nrepl-lifecycle-commands-registered-and-bound
  (let [td (tool/get-tool-defs :id :debug-agent)
        tools (set (:tools (get-in td [:meta :agent-tools])))]
    (is (contains? tools :clj-nrepl$start-server))
    (is (contains? tools :clj-nrepl$stop-server))
    (is (contains? tools :clj-nrepl$status)))
  (doseq [id [:clj-nrepl$start-server :clj-nrepl$stop-server :clj-nrepl$status]]
    (is (some? (tool/get-tool-defs :id id)) (str id " registered"))))

(deftest nrepl-lifecycle-commands-gated-to-debug
  (doseq [id [:clj-nrepl$start-server :clj-nrepl$stop-server :clj-nrepl$status]]
    (let [td (tool/get-tool-defs :id id)]
      (is (tool/tool-visible? td :debug-agent) (str id " visible to debug-agent"))
      (is (not (tool/tool-visible? td :coact-agent))
          (str id " hidden from coact-agent")))))

(deftest nrepl-status-reflects-running-server
  ;; full-trust: status reports only running / port / port-files
  (let [s (tool/invoke-tool :clj-nrepl$status)]
    (is (true? (:running s)))
    (is (integer? (:port s)))
    (is (vector? (:port-files s)))
    (is (not (contains? s :grant-active)) "no grant machinery anymore")
    (is (not (contains? s :drifted?)) "no drift machinery anymore")))

(deftest nrepl-start-server-is-idempotent
  (let [r (tool/invoke-tool :clj-nrepl$start-server)]
    (is (true? (:running r)))
    (is (true? (:already-running r)) "fixture server already up → no-op start")
    (is (= (clj-nrepl/server-port) (:port r)))
    (is (string? (:port-file r)))
    (is (not (contains? r :grant-active)) "no grant seeding anymore")))

(deftest nrepl-stop-then-restart-cycle
  (let [stopped (tool/invoke-tool :clj-nrepl$stop-server)]
    (is (true? (:stopped stopped)))
    (is (integer? (:was-port stopped)))
    (is (false? (:running (tool/invoke-tool :clj-nrepl$status))))
    (let [started (tool/invoke-tool :clj-nrepl$start-server)]
      (try
        (is (true? (:running started)))
        (is (false? (:already-running started)))
        (is (integer? (:port started)))
        (is (true? (:running (tool/invoke-tool :clj-nrepl$status))))
        (finally
          (tool/invoke-tool :clj-nrepl$stop-server)))))
  (let [noop (tool/invoke-tool :clj-nrepl$stop-server)]
    (is (false? (:stopped noop)))))
