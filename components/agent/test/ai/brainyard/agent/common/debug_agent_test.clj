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
            [clojure.java.io :as io]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.core.usage :as usage]
            [ai.brainyard.agent.core.agent :as ag]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
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
(def ^:private eval-capable?
  #'ai.brainyard.agent.common.debug-agent/eval-capable?)
(def ^:private source-origin
  #'ai.brainyard.agent.common.debug-agent/source-origin)
(def ^:private extract-jar-sources!
  #'ai.brainyard.agent.common.debug-agent/extract-jar-sources!)

(defn- rm-rf [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf c)))
    (.delete f)))

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

(defn- with-nrepl-env-unset
  "Run `f` with BY_NREPL_ENABLED neutralized so `:nrepl-enabled?` resolves from
   the config layers alone. The env layer outranks everything (that is the
   point of the operator kill-switch), so a developer running these tests with
   BY_NREPL_ENABLED set would otherwise flip the gate assertions. Only that one
   key is redirected — every other key keeps its real env resolution."
  [f]
  (let [orig config/schema-env-value]
    (with-redefs [config/schema-env-value
                  (fn [k] (if (= k :nrepl-enabled?) config/env-unset (orig k)))]
      (f))))

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

(deftest nrepl-guide-teaches-bound-tools-not-call-tool-workarounds
  ;; Registered tools are interned into the agent's live namespace
  ;; (agent.common.nrepl-bindings), so the guide must teach the SAME call shape
  ;; the system prompt's Function Directory advertises. Before that, the guide
  ;; carried a whole workaround section — including a `(def dbg (first (filter
  ;; …)))` dance to recover *current-agent* — for symbols the prompt claimed
  ;; were already callable.
  (let [g (usage/get-usage-guide :nrepl)]
    (testing "bare tool calls are the documented shape"
      (is (str/includes? g "they are bound as functions"))
      (is (str/includes? g "(read-file :path"))
      (is (str/includes? g "(memory$status)")))
    (testing "the stale denial is gone"
      (is (not (str/includes? g "does NOT auto-bind"))
          "the guide must not tell the model its bound tools are unresolvable"))
    (testing "call-tool survives as the escape hatch, with its two real cases"
      (is (str/includes? g "escape hatch"))
      (is (str/includes? g "AS ANOTHER agent"))
      (is (str/includes? g ":nrepl-host")
          "a remote endpoint cannot hold local closures — must stay documented"))))

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

(deftest debug-agent-carries-its-own-nrepl-opt-in
  ;; The agent supplies :nrepl-enabled? on the per-agent config layer, which is
  ;; what gates its autostart — so no operator pre-enable step is needed, and
  ;; the start is still gated rather than unconditional.
  (let [d (tool/get-tool-defs :id :debug-agent)]
    (is (true? (get-in d [:meta :config-extra :nrepl-enabled?]))
        "debug-agent must ship :config-extra {:nrepl-enabled? true}")
    (is (= :nrepl (get-in d [:meta :config-extra :clj-backend]))
        "…and declare the :nrepl code-eval route there too"))
  (with-nrepl-env-unset
    (fn []
      (let [agent (ag/setup-agent-by-id
                   :debug-agent
                   :agent-session {:user-id "test" :session-id "debug-gate"})]
        (try
          (is (true? (config/get-config agent :nrepl-enabled?))
              "instance resolves the gate on")
          ;; Assert the LAYER, not the value: a project .brainyard/config.edn
          ;; may itself set :nrepl-enabled? true, which would mask a missing
          ;; :config-extra and make a value-only assertion pass for the wrong
          ;; reason. :agent means it came from the instance's own override.
          (is (= :agent (config/config-source agent :nrepl-enabled?))
              "…and it must come from the per-agent layer (its :config-extra)")
          ;; The pair matters: resolve-clj-backend demotes :nrepl to :sandbox
          ;; for any agent without the gate, so debug-agent shipping both keys
          ;; together is what keeps it on the live backend.
          (is (= :nrepl (config/resolve-clj-backend agent))
              "debug-agent must survive the :clj-backend demotion guard")
          (finally
            (.close ^java.io.Closeable agent)))))))

(deftest clj-backend-survives-caller-config-extra-clobber
  ;; Why the created-hook still re-asserts :clj-backend even though the defagent
  ;; declares it: `setup-agent-by-id` merges caller options over defagent meta
  ;; SHALLOWLY, so a caller passing ANY :config-extra replaces the author's map
  ;; wholesale — silently dropping the :nrepl route. A debug-agent demoted to
  ;; the SCI sandbox does not error; it answers confidently from an image it
  ;; cannot see. The hook's write runs last and unconditionally, so it can't be
  ;; merged away.
  (let [agent (ag/setup-agent-by-id
               :debug-agent
               :agent-session {:user-id "test" :session-id "debug-clobber"}
               ;; a caller map that does NOT mention :clj-backend
               :config-extra {:max-refinements 0})]
    (try
      (is (= :nrepl (:clj-backend (instance-config agent)))
          "hook backstop must restore the route the shallow merge dropped")
      (is (= :nrepl (agent-clj-backend agent))
          "…and CoAct's block router must see it")
      ;; The route alone is not enough: resolve-clj-backend demotes :nrepl to
      ;; :sandbox unless the same agent ALSO resolves :nrepl-enabled? true, so
      ;; the hook has to re-assert both. Assert the LAYER, not just the value —
      ;; a value-only check passed for the wrong reason on any machine whose
      ;; (gitignored) .brainyard/config.edn happened to enable nREPL, and failed
      ;; on a fresh clone, on CI, and whenever an earlier test perturbed the
      ;; global layer.
      (is (true? (:nrepl-enabled? (instance-config agent)))
          "hook must re-assert the gate too, not just the route")
      (is (= :agent (config/config-source agent :nrepl-enabled?))
          "…on the per-agent layer, so the result cannot depend on ambient config")
      (finally
        (.close ^java.io.Closeable agent)))))

(deftest instance-created-autostarts-server-when-down
  ;; debug-agent is inert without a live channel — every ```clojure fence
  ;; routes to :nrepl. The created-hook must ENSURE the server rather than
  ;; leaving it to the LLM remembering the clj-nrepl$start-server lifecycle
  ;; step before its first fence.
  (tool/invoke-tool :clj-nrepl$stop-server)
  (is (false? (clj-nrepl/running?)) "precondition: no server running")
  (with-nrepl-env-unset
    (fn []
      (let [agent (ag/setup-agent-by-id
                   :debug-agent
                   :agent-session {:user-id "test" :session-id "debug-autostart"})]
        (try
          (is (true? (clj-nrepl/running?))
              "created-hook should have started the loopback server")
          (is (string? (:nrepl-session-id (instance-config agent)))
              "and pinned a server-issued session on the freshly started server")
          (finally
            (.close ^java.io.Closeable agent)))))))

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

(deftest nrepl-start-server-honors-the-enabled-gate
  ;; :nrepl-enabled? governs STARTING: gate off ⇒ the command refuses and
  ;; explains, and the created-hook does not autostart either. Never bring up a
  ;; full-trust eval channel the config says is off.
  ;;
  ;; The gate is driven from the PER-AGENT layer (caller :config-extra beats the
  ;; defagent's) rather than by leaning on the schema default — a project
  ;; .brainyard/config.edn that sets :nrepl-enabled? true would otherwise
  ;; legitimately turn the gate on and fail this test for environment reasons.
  (tool/invoke-tool :clj-nrepl$stop-server)
  (with-nrepl-env-unset
    (fn []
      (let [agent (ag/setup-agent-by-id
                   :debug-agent
                   :agent-session {:user-id "test" :session-id "debug-gate-off"}
                   :config-extra {:nrepl-enabled? false})]
        (try
          (is (false? (config/get-config agent :nrepl-enabled?))
              "precondition: the instance resolves the gate off")
          (is (false? (clj-nrepl/running?))
              "gate off ⇒ the created-hook must NOT autostart a server")
          (let [refused (binding [proto/*current-agent* agent]
                          (tool/invoke-tool :clj-nrepl$start-server))]
            (is (false? (:running refused)))
            (is (nil? (:port refused)))
            (is (string? (:message refused)))
            (is (str/includes? (:message refused) "nrepl-enabled?")
                "the refusal must name the key that has to change")
            (is (false? (clj-nrepl/running?))
                "the gate must block the actual start, not just the report"))
          (finally
            (.close ^java.io.Closeable agent)))))))

(deftest nrepl-start-server-refuses-under-native-image
  ;; A native image can open a socket and write a port file, so a started
  ;; server there LOOKS healthy while every eval fails — Clojure has no runtime
  ;; compiler under native-image. The refusal must name the remedy, and must
  ;; win even when a server is already reachable (that is the trap: reachable
  ;; but unusable).
  (with-redefs [ai.brainyard.agent.common.debug-agent/eval-capable? (constantly false)]
    (let [refused (tool/invoke-tool :clj-nrepl$start-server)]
      (is (false? (:running refused)))
      (is (string? (:message refused)))
      (is (str/includes? (:message refused) "native image"))
      (is (str/includes? (:message refused) "BY_JAR=1")
          "the refusal must tell the operator which runtime to switch to")))
  (testing "the real runtime (a JVM test run) is of course eval-capable"
    (is (true? (eval-capable?)))))

(deftest add-classpath-registered-gated-and-defaults-to-project-src
  (let [td (tool/get-tool-defs :id :clj-nrepl$add-classpath)]
    (is (some? td) "clj-nrepl$add-classpath must be registered")
    (is (tool/tool-visible? td :debug-agent))
    (is (not (tool/tool-visible? td :coact-agent)) "gated to debug-*"))
  (is (contains? (set (:tools (get-in (tool/get-tool-defs :id :debug-agent)
                                      [:meta :agent-tools])))
                 :clj-nrepl$add-classpath)
      "and be bound on debug-agent"))

(deftest add-classpath-makes-an-off-classpath-ns-requirable
  ;; The end-to-end property: a namespace written into a directory that is NOT
  ;; on the classpath is not requirable, and becomes requirable after the root
  ;; is added. This is what lets debug-agent use `require … :reload` on project
  ;; code instead of load-file-by-absolute-path only.
  ;;
  ;; Both the add and the require go through the agent's PINNED session on
  ;; purpose: nREPL pushes a DynamicClassLoader per session, so a URL added in
  ;; one session is not visible from another (nor from this test thread). The
  ;; command adds to the pinned session precisely so the agent's own fences —
  ;; which run in that same session — can see it.
  (let [root (io/file (System/getProperty "java.io.tmpdir")
                      (str "by-cp-test-" (System/nanoTime)))
        nsd  (io/file root "labcp")
        f    (io/file nsd "probe.clj")
        agent (ag/setup-agent-by-id
               :debug-agent
               :agent-session {:user-id "test" :session-id "debug-cp"})
        sid   (:nrepl-session-id (instance-config agent))
        eval* (fn [code] (clj-nrepl/eval-string code :session sid :timeout-ms 15000))]
    (try
      (.mkdirs nsd)
      (spit f "(ns labcp.probe)\n(defn answer [] :from-added-classpath)\n")
      (is (string? sid) "precondition: the instance pinned a session")
      (is (str/includes? (str (:error (eval* "(require 'labcp.probe)")))
                         "FileNotFoundException")
          "precondition: not requirable in that session before the root is added")
      (let [r (binding [proto/*current-agent* agent]
                (tool/invoke-tool :clj-nrepl$add-classpath {:paths [(.getPath root)]}))]
        (is (nil? (:error r)) (str "add-classpath errored: " (:error r)))
        (is (= 1 (count (:added r))))
        (is (empty? (:skipped r)))
        (is (= sid (:session r)) "must add to the agent's pinned session"))
      (is (empty? (str (:error (eval* "(require 'labcp.probe)"))))
          "requirable once the root is on that session's classpath")
      (is (= ":from-added-classpath" (:result (eval* "(labcp.probe/answer)")))
          "and the namespace really resolves from the newly added root")
      (finally
        (.close ^java.io.Closeable agent)
        (.delete f) (.delete nsd) (.delete root)))))

(deftest add-classpath-reports-a-non-directory-instead-of-adding-it
  (let [f (io/file (System/getProperty "java.io.tmpdir")
                   (str "by-cp-file-" (System/nanoTime) ".clj"))]
    (try
      (spit f "(ns nope)")
      (let [r (tool/invoke-tool :clj-nrepl$add-classpath {:paths [(.getPath f)]})]
        (is (empty? (:added r)))
        (is (= 1 (count (:skipped r))))
        (is (str/includes? (first (:skipped r)) "not a directory")))
      (finally (.delete f)))))

;; ============================================================================
;; materialize-sources — self-debugging without a checkout
;; ============================================================================

(defn- write-fake-jar!
  "A jar shaped like the shipped uberjar: brainyard .clj sources, a compiled
   class beside them, and an unrelated dependency source that must NOT be
   extracted."
  [^java.io.File jar]
  (io/make-parents jar)
  (with-open [out (java.util.zip.ZipOutputStream. (io/output-stream jar))]
    (doseq [[n content] [["ai/brainyard/agent/core/config.clj" "(ns ai.brainyard.agent.core.config)"]
                         ["ai/brainyard/util/interface.cljc" "(ns ai.brainyard.util.interface)"]
                         ["ai/brainyard/agent/core/config.class" "NOT-SOURCE"]
                         ["clojure/core.clj" "(ns clojure.core)"]]]
      (.putNextEntry out (java.util.zip.ZipEntry. ^String n))
      (.write out (.getBytes ^String content "UTF-8"))
      (.closeEntry out)))
  jar)

(deftest materialize-sources-registered-gated-and-bound
  (let [td (tool/get-tool-defs :id :clj-nrepl$materialize-sources)]
    (is (some? td))
    (is (tool/tool-visible? td :debug-agent))
    (is (not (tool/tool-visible? td :coact-agent)) "gated to debug-*"))
  (is (contains? (set (:tools (get-in (tool/get-tool-defs :id :debug-agent)
                                      [:meta :agent-tools])))
                 :clj-nrepl$materialize-sources)))

(deftest source-origin-detects-this-checkout
  ;; These tests run from source, so the probe resource must resolve to a
  ;; directory root — the branch that tells the agent no extraction is needed.
  (let [o (source-origin)]
    (is (= :directory (:kind o)))
    (is (str/ends-with? (:root o) "components/agent/src")
        (str "expected a classpath root, got " (:root o)))
    (is (.isDirectory (io/file (:root o) "ai" "brainyard")))))

(deftest materialize-sources-reports-checkout-instead-of-extracting
  (let [r (tool/invoke-tool :clj-nrepl$materialize-sources)]
    (is (= "directory" (:kind r)))
    (is (pos? (:files r)))
    (is (str/includes? (:message r) "already editable"))))

(deftest extract-jar-sources-takes-only-matching-sources
  (let [tmp  (io/file (System/getProperty "java.io.tmpdir") (str "by-mat-" (System/nanoTime)))
        jar  (write-fake-jar! (io/file tmp "fake.jar"))
        dest (io/file tmp "out")]
    (try
      (let [n (extract-jar-sources! (.getPath jar) "ai/brainyard/" dest)]
        (is (= 2 n) ".clj and .cljc under the prefix, nothing else")
        (is (.isFile (io/file dest "ai/brainyard/agent/core/config.clj")))
        (is (.isFile (io/file dest "ai/brainyard/util/interface.cljc")))
        (is (not (.exists (io/file dest "ai/brainyard/agent/core/config.class")))
            "compiled classes are not sources")
        (is (not (.exists (io/file dest "clojure/core.clj")))
            "dependency sources are out of scope — only brainyard's own")
        (is (= "(ns ai.brainyard.agent.core.config)"
               (slurp (io/file dest "ai/brainyard/agent/core/config.clj")))
            "content is copied verbatim, not truncated"))
      (finally (rm-rf tmp)))))

(deftest materialize-sources-does-not-clobber-existing-edits
  ;; The destructive failure mode: re-running the command over a tree the agent
  ;; has already edited. It must refuse and say so, and :force must be the only
  ;; way to overwrite.
  (let [tmp  (io/file (System/getProperty "java.io.tmpdir") (str "by-mat2-" (System/nanoTime)))
        jar  (write-fake-jar! (io/file tmp "fake.jar"))
        dest (io/file tmp "out")
        edited (io/file dest "ai/brainyard/agent/core/config.clj")]
    (try
      (with-redefs [ai.brainyard.agent.common.debug-agent/source-origin
                    (constantly {:kind :jar :jar (.getPath jar)})]
        (let [first-run (tool/invoke-tool :clj-nrepl$materialize-sources {:dest (.getPath dest)})]
          (is (= "jar" (:kind first-run)))
          (is (= 2 (:files first-run)))
          (is (false? (:already-materialized first-run))))
        (spit edited ";; MY EDIT")
        (let [second-run (tool/invoke-tool :clj-nrepl$materialize-sources {:dest (.getPath dest)})]
          (is (true? (:already-materialized second-run)))
          (is (str/includes? (:message second-run) "force"))
          (is (= ";; MY EDIT" (slurp edited)) "the edit must survive a re-run"))
        (let [forced (tool/invoke-tool :clj-nrepl$materialize-sources
                                       {:dest (.getPath dest) :force true})]
          (is (false? (:already-materialized forced)))
          (is (= "(ns ai.brainyard.agent.core.config)" (slurp edited))
              ":force re-extracts, discarding the edit as documented")))
      (finally (rm-rf tmp)))))

(deftest nrepl-stop-then-restart-cycle
  (let [stopped (tool/invoke-tool :clj-nrepl$stop-server)]
    (is (true? (:stopped stopped)))
    (is (integer? (:was-port stopped)))
    (is (false? (:running (tool/invoke-tool :clj-nrepl$status))))
    ;; The restart runs under a debug-agent, whose :config-extra supplies the
    ;; :nrepl-enabled? opt-in the command now requires. (Creating the instance
    ;; already autostarts a server, so stop it again first to exercise the
    ;; tool's own start path.)
    (with-nrepl-env-unset
      (fn []
        (let [agent (ag/setup-agent-by-id
                     :debug-agent
                     :agent-session {:user-id "test" :session-id "debug-restart"})]
          (try
            (tool/invoke-tool :clj-nrepl$stop-server)
            (is (false? (clj-nrepl/running?)))
            (let [started (binding [proto/*current-agent* agent]
                            (tool/invoke-tool :clj-nrepl$start-server))]
              (is (true? (:running started)))
              (is (false? (:already-running started)))
              (is (integer? (:port started)))
              (is (true? (:running (tool/invoke-tool :clj-nrepl$status)))))
            (finally
              (.close ^java.io.Closeable agent)
              (tool/invoke-tool :clj-nrepl$stop-server)))))))
  (let [noop (tool/invoke-tool :clj-nrepl$stop-server)]
    (is (false? (:stopped noop)))))
