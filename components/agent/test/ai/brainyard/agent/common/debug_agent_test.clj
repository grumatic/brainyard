;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.debug-agent-test
  "Phase 2b — debug-agent registration, lifecycle hooks, and CoAct
   plumbing (per-instance :clj-backend + :nrepl-session-id routes
   clojure blocks to :clj-nrepl-eval and pins the session).

   Phase 3 — debug$promote-hot-patch writes a promotion artifact that
   hands off to update-agent without crossing the runtime/source
   boundary."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.core.agent :as ag]
            [ai.brainyard.agent.task.manager :as task-mgr]
            [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.agent.common.debug-agent] ;; trigger registration
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
  (task-mgr/set-default-manager! nil)
  (clj-nrepl/drift-clear!)
  (clj-nrepl/revoke-confirmation!))

(defn- with-server [t]
  (try
    (clj-nrepl/start-server! :bind "127.0.0.1" :port 0)
    (clj-nrepl/grant! :scope :mutate :ttl-ms 60000)
    (reset-globals!)
    (t)
    (finally
      (reset-globals!)
      (clj-nrepl/revoke!)
      (try (clj-nrepl/stop-server!) (catch Exception _)))))

(use-fixtures :each with-server)

;; ============================================================================
;; Registration
;; ============================================================================

(deftest debug-agent-registered
  (let [td (tool/get-tool-defs :id :debug-agent)]
    (is (some? td))
    (is (= :agent (:type td)))
    (let [tools (:tools (get-in td [:meta :agent-tools]))]
      (is (contains? (set tools) :code$eval))
      (is (contains? (set tools) :task$detail))
      (is (contains? (set tools) :clj-nrepl$drift-markers))
      (is (not (contains? (set tools) :bash))
          "debug-agent's bag is intentionally tight — no bash"))))

;; ============================================================================
;; Backend selection — agent-clj-backend reads :clj-backend via the unified
;; config chain (per-agent override → session → global → schema default
;; :sandbox). There is no per-fence override; ```clojure :nrepl is a fence
;; error, not a routing hint. See `fence-error-on-trailing-fence-text` below.
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
      (is (str/includes? (:fence-error blk) "per-agent"))))
  (testing "Trailing :sandbox on the fence → fence-error"
    (let [[blk] (clj-sandbox/extract-all-code-blocks-multi
                 "```clojure :sandbox\n(+ 1 2)\n```")]
      (is (string? (:fence-error blk))))))

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
  ;; Smoke test: closing the agent must not throw, and the (server-issued)
  ;; session is closed via the lifecycle hook. We verify by re-opening
  ;; a session right after — if the prior close leaked transport state
  ;; this would fail.
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

;; ============================================================================
;; Phase 3 — debug$promote-hot-patch
;; ============================================================================

(deftest promote-tool-registered
  (let [td (tool/get-tool-defs :id :debug$promote-hot-patch)]
    (is (some? td))
    (is (= :command (:type td)))
    (is (re-find #"(?i)hot-patch.*committed source|promote"
                 (or (get-in td [:meta :description]) ""))
        "description should describe the promotion intent")))

(defn- in-tmp-cwd
  "Run `f` with `user.dir` set to a fresh tmp dir so .brainyard/ writes
   don't pollute the repo. Restores `user.dir` after."
  [f]
  (let [orig  (System/getProperty "user.dir")
        tmp   (str (System/getProperty "java.io.tmpdir")
                   "/debug-promote-test-" (System/nanoTime))]
    (.mkdirs (io/file tmp))
    (try
      (System/setProperty "user.dir" tmp)
      (f tmp)
      (finally
        (System/setProperty "user.dir" orig)))))

(deftest promote-refuses-with-no-markers
  (clj-nrepl/drift-clear!)
  (in-tmp-cwd
   (fn [_tmp]
     (let [r (tool/invoke-tool :debug$promote-hot-patch
                               :target-file "x.clj"
                               :target-symbol "x/y"
                               :pattern "old"
                               :replacement "new"
                               :rationale "test"
                               :validation-evidence "test")]
       (is (re-find #"no drift markers" (:error r)))))))

(deftest promote-refuses-out-of-range-index
  (clj-nrepl/drift-clear!)
  (clj-nrepl/drift-mark! "sess-x" "(def y 1)")
  (in-tmp-cwd
   (fn [_tmp]
     (let [r (tool/invoke-tool :debug$promote-hot-patch
                               :drift-index   99
                               :target-file   "x.clj"
                               :target-symbol "x/y"
                               :pattern       "old"
                               :replacement   "new"
                               :rationale     "test"
                               :validation-evidence "test")]
       (is (re-find #"out of range" (:error r)))))))

(deftest promote-writes-artifact-with-expected-shape
  (clj-nrepl/drift-clear!)
  (clj-nrepl/drift-mark! "sess-promo" "(alter-var-root #'foo (constantly bar))")
  (in-tmp-cwd
   (fn [tmp]
     (let [r (tool/invoke-tool :debug$promote-hot-patch
                               :target-file   "components/agent/src/ai/brainyard/agent/foo.clj"
                               :target-symbol "ai.brainyard.agent.foo/baz"
                               :pattern       "(defn baz [] :old)"
                               :replacement   "(defn baz [] :new)"
                               :rationale     "old impl never returned for arity-0 callers"
                               :validation-evidence "(baz) => :new in live session sess-promo")]
       (is (= :proposed (:status r)))
       (is (str/starts-with? (:path r)
                             ".brainyard/agents/debug-agent/promotions/"))
       (is (str/ends-with? (:path r) ".md"))
       (is (re-find #"bb tui ask .* -a update-agent" (:next-step r)))
       (let [abs (io/file tmp (:path r))]
         (is (.exists abs))
         (let [body (slurp abs)]
           (is (re-find #"(?m)^target-symbol: ai\.brainyard\.agent\.foo/baz" body))
           (is (re-find #"(?m)^edit-mode: pattern" body))
           (is (re-find #"(?m)^status: proposed" body))
           (is (re-find #"Saved hot-patch: \.brainyard/agents/debug-agent/promotions/" body))
           (is (re-find #"Promotion request: bb tui ask " body))
           (is (re-find #"\(defn baz \[\] :new\)" body))
           (is (re-find #"alter-var-root" body) "drift marker code preview is included")))))))

;; ============================================================================
;; Phase 2b — CoAct plumbing
;; ============================================================================

(deftest run-clj-nrepl-block-passes-session-into-task
  (let [agent (ag/setup-agent-by-id
               :debug-agent
               :agent-session {:user-id "test" :session-id "debug-3"})
        sid   (:nrepl-session-id (instance-config agent))]
    (try
      ;; Run a single block through run-single-block — which is what
      ;; coact-code-eval-action invokes. The agent is in dispatch-opts so
      ;; the per-instance :nrepl-session-id flows through.
      (let [entry (run-single-block
                   nil ;; sandbox unused on :nrepl path
                   {:lang "clojure" :code "(+ 1 2)" :info-args []}
                   {:auto-bg-ms     5000
                    :from-iteration 0
                    :agent          agent})]
        (is (= "clojure" (:lang entry)))
        (is (nil? (some-> entry :error not-empty)))
        (is (= "3" (:result entry))))
      ;; Verify a manually-issued task carries :session in job-config.
      (let [mgr (task-mgr/get-default-manager)
            t   (tp/create-task mgr "probe" :clj-nrepl-eval
                                {:code "(+ 1 2)" :timeout-ms 500 :session sid}
                                {})]
        (is (= sid (-> (tp/get-task mgr (:id t)) :job-config :session))))
      (finally
        (.close ^java.io.Closeable agent)))))

;; ============================================================================
;; nREPL server lifecycle commands — start / stop / status
;; (with-server fixture pre-starts a loopback server + :mutate grant)
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

(deftest nrepl-status-reflects-running-server-and-grant
  ;; fixture has a running server + :mutate grant
  (let [s (tool/invoke-tool :clj-nrepl$status)]
    (is (true? (:running s)))
    (is (integer? (:port s)))
    (is (true? (:grant-active s)))
    (is (= :mutate (:grant-scope s)))
    (is (contains? s :drifted?))
    (is (vector? (:port-files s)))))

(deftest nrepl-start-server-is-idempotent
  (let [r (tool/invoke-tool :clj-nrepl$start-server)]
    (is (true? (:running r)))
    (is (true? (:already-running r)) "fixture server already up → no-op start")
    (is (= (clj-nrepl/server-port) (:port r)))
    (is (string? (:port-file r)))))

(deftest nrepl-start-server-seeds-configured-grant
  ;; The eval gate rejects everything without an active grant; start-server
  ;; must seed the configured :nrepl-grant (schema default read-only:24h) so
  ;; on-demand start makes read-only eval work. Revoke the fixture's grant
  ;; first so the seeding path actually runs.
  (clj-nrepl/revoke!)
  (tool/invoke-tool :clj-nrepl$stop-server)
  (let [r (tool/invoke-tool :clj-nrepl$start-server)]
    (try
      (is (true? (:running r)))
      (is (true? (:grant-active r)) "start seeds a grant from config")
      (is (= :read-only (:grant-scope r)) "default :nrepl-grant is read-only")
      (let [s (tool/invoke-tool :clj-nrepl$status)]
        (is (true? (:grant-active s)))
        (is (= :read-only (:grant-scope s))))
      (finally
        (clj-nrepl/revoke!)
        (tool/invoke-tool :clj-nrepl$stop-server)))))

(deftest nrepl-stop-then-restart-cycle
  ;; stop the fixture's server, confirm status, then bring a fresh one up
  (let [stopped (tool/invoke-tool :clj-nrepl$stop-server)]
    (is (true? (:stopped stopped)))
    (is (integer? (:was-port stopped)))
    (is (false? (:running (tool/invoke-tool :clj-nrepl$status))))
    ;; restart — a genuinely new server (not a no-op)
    (let [started (tool/invoke-tool :clj-nrepl$start-server)]
      (try
        (is (true? (:running started)))
        (is (false? (:already-running started)))
        (is (integer? (:port started)))
        (is (true? (:running (tool/invoke-tool :clj-nrepl$status))))
        (finally
          ;; leave the world stopped; stop-server also deletes the port file
          (tool/invoke-tool :clj-nrepl$stop-server)))))
  ;; no-op stop when nothing is running
  (let [noop (tool/invoke-tool :clj-nrepl$stop-server)]
    (is (false? (:stopped noop)))))
