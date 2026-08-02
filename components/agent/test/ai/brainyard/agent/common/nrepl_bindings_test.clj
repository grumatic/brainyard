;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.nrepl-bindings-test
  "Auto-binding of registered tools into the live nREPL image.

   The contract: the SAME {symbol fn} map that feeds the SCI sandbox and the
   prompt's `### Function Directory` is interned into a per-agent namespace, and
   the eval path evaluates blocks there — so a `:nrepl` agent can call
   `(read-file …)` / `(usage$guide)` exactly as a `:sandbox` agent does, instead
   of hitting Unable-to-resolve on every symbol its own system prompt advertises.

   Per-agent namespace (never `user`) is load-bearing: the closures capture their
   agent, so a shared namespace would let the last installer's identity win and
   silently run tool calls as the WRONG agent."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ai.brainyard.agent.common.nrepl-bindings :as nb]
            [ai.brainyard.agent.common.sandbox-bindings :as sb-bind]
            [ai.brainyard.agent.common.coact-agent :as coact]
            [ai.brainyard.agent.common.debug-agent :as debug-agent]
            [ai.brainyard.agent.core.agent :as ag]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.task.manager :as task-mgr]
            [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.clj-nrepl.interface :as clj-nrepl]))

(def ^:private run-single-block
  #'ai.brainyard.agent.common.coact-agent/run-single-block)

(defn- reset-globals! []
  (when-let [mgr (task-mgr/peek-default-manager)]
    (try (tp/shutdown mgr) (catch Exception _)))
  (task-mgr/set-default-manager! nil))

(defn- with-server [t]
  (try
    ;; A prior test in the same JVM may have wiped the global hook registry;
    ;; debug-agent's instance hooks are registered only at ns-load. Mirrors
    ;; debug_agent_test's fixture so these stay order-independent.
    (debug-agent/register-hooks!)
    (clj-nrepl/start-server! :bind "127.0.0.1" :port 0)
    (reset-globals!)
    (t)
    (finally
      (reset-globals!)
      (try (clj-nrepl/stop-server!) (catch Exception _)))))

(use-fixtures :each with-server)

(defn- make-agent [session-id]
  (ag/setup-agent-by-id :debug-agent
                        :agent-session {:user-id "test" :session-id session-id}))

(defmacro ^:private with-agent [[sym session-id] & body]
  `(let [~sym (make-agent ~session-id)]
     (try ~@body
          (finally
            (try (nb/uninstall! ~sym) (catch Throwable _#))
            (.close ^java.io.Closeable ~sym)))))

;; ============================================================================
;; Namespace naming — derived purely from the agent id (no shared registry)
;; ============================================================================

(deftest tool-ns-sym-derives-from-agent-id
  (with-agent [a "nb-ns-name"]
    (let [aid (proto/agent-id a)
          ns-sym (nb/tool-ns-sym a)]
      (is (symbol? ns-sym))
      (is (str/starts-with? (str ns-sym) "by.tools.")
          "tool namespaces live under a reserved prefix, never `user`")
      (is (str/includes? (str ns-sym) (namespace aid))
          "…and carry the defagent type so two live agents can't collide")
      (is (str/includes? (str ns-sym) (name aid))
          "…plus the instance suffix")
      (testing "pure — same agent, same name, no install needed"
        (is (= ns-sym (nb/tool-ns-sym a))))))
  (testing "nil agent has no namespace"
    (is (nil? (nb/tool-ns-sym nil)))))

;; ============================================================================
;; install! — interning, meta, refresh, teardown
;; ============================================================================

(deftest install-interns-every-binding-with-its-meta
  (with-agent [a "nb-install"]
    (let [bindings (sb-bind/make-tool-bindings a)
          ns-sym   (nb/install! a bindings)]
      (is (= ns-sym (nb/tool-ns-sym a)) "install! returns the namespace it filled")
      (is (some? (find-ns ns-sym)))
      (testing "every binding is interned"
        (let [interned (set (keys (ns-interns (find-ns ns-sym))))]
          (is (seq bindings) "precondition: the agent has tools bound")
          (is (every? interned (keys bindings))
              "every sandbox binding must resolve in the nREPL image too")))
      (testing "clojure.core is referred, so blocks can use core unqualified"
        (is (= #'clojure.core/map (ns-resolve (find-ns ns-sym) 'map))))
      (testing "the binding's docs ride onto the var — (meta #'x) reads the
                same :doc/:arglists the Function Directory renders"
        (let [v (ns-resolve (find-ns ns-sym) 'read-file)]
          (is (var? v))
          (is (string? (:doc (meta v))))
          (is (seq (:arglists (meta v))))))
      (testing "active-ns sees the installed namespace"
        (is (= ns-sym (nb/active-ns a)))))))

(deftest install-is-idempotent-and-sweeps-only-stale-tool-vars
  (with-agent [a "nb-refresh"]
    (let [bindings (sb-bind/make-tool-bindings a)
          ns-sym   (nb/install! a bindings)
          target   (find-ns ns-sym)]
      ;; Stand in for a tool that exists this turn but is deleted/hidden before
      ;; the next one (tool-agent$delete, an MCP server disconnecting).
      (nb/install! a (assoc bindings 'user$tool$ephemeral (with-meta (fn [& _] :x)
                                                            {:doc "d"})))
      (is (some? (ns-resolve target 'user$tool$ephemeral)))
      ;; The model's own working state in the same namespace.
      (intern target 'my-repro-var 42)

      (nb/install! a bindings)
      (is (nil? (ns-resolve target 'user$tool$ephemeral))
          "a tool that disappeared must not linger as a callable")
      (is (= 42 (deref (ns-resolve target 'my-repro-var)))
          "the model's own defs carry no binding marker and must survive refresh")
      (is (every? (set (keys (ns-interns target))) (keys bindings))
          "the surviving tools are all still bound"))))

(deftest uninstall-removes-the-namespace
  (with-agent [a "nb-uninstall"]
    (let [ns-sym (nb/install! a (sb-bind/make-tool-bindings a))]
      (is (some? (find-ns ns-sym)))
      (nb/uninstall! a)
      (is (nil? (find-ns ns-sym)))
      (is (nil? (nb/active-ns a))
          "active-ns must go quiet so the eval path stops prefixing in-ns"))))

(deftest active-ns-nil-before-install
  (with-agent [a "nb-active-nil"]
    (is (nil? (nb/active-ns a))
        "no install ⇒ no prefix ⇒ blocks keep evaluating in `user` as before")))

;; ============================================================================
;; Turn-start wiring — the call site that makes all of the above production code
;; ============================================================================

(deftest coact-init-installs-bindings-for-an-nrepl-agent
  ;; Guards the seam, not the helper: coact-init builds ONE bindings map, hands
  ;; it to the prompt's `### Function Directory`, and must hand the SAME map to
  ;; the live image. Without this call every other test here can pass while a
  ;; real turn still resolves nothing.
  (with-agent [a "nb-coact-init"]
    (is (nil? (nb/active-ns a)) "precondition: nothing bound before the turn")
    (coact/coact-init-action
     {:st-memory (atom {:question "hi" :tools [] :iterations []})
      :agent     a
      :opts      {}})
    (is (= (nb/tool-ns-sym a) (nb/active-ns a))
        "turn start must intern this agent's tools into the live image")
    (is (pos? (count (ns-interns (find-ns (nb/active-ns a)))))
        "…and the namespace must actually hold them")))

;; ============================================================================
;; Remote endpoint — local closures can't cross a socket to another JVM
;; ============================================================================

(deftest remote-endpoint-skips-binding
  (with-agent [a "nb-remote"]
    (let [orig config/get-config]
      (with-redefs [config/get-config
                    (fn [& args]
                      (if (= :nrepl-host (last args)) "10.0.0.5" (apply orig args)))]
        (is (false? (nb/local-endpoint? a)))
        (is (nil? (nb/install! a (sb-bind/make-tool-bindings a)))
            "a remote nREPL is a different JVM — install! must no-op there")
        (is (nil? (find-ns (nb/tool-ns-sym a))))))
    (testing "an unset :nrepl-host is the in-process server"
      (is (true? (nb/local-endpoint? a))))))

;; ============================================================================
;; prefix-code — only the wire copy is rewritten
;; ============================================================================

(deftest prefix-code-puts-in-ns-first
  (let [out (nb/prefix-code 'by.tools.x "(+ 1 2)")]
    (is (str/starts-with? out "(clojure.core/in-ns 'by.tools.x)"))
    (is (str/ends-with? out "(+ 1 2)"))
    (is (str/includes? out "\n")
        "the prefix must be its own form — a line comment in the model's code
         would otherwise swallow it")))

;; ============================================================================
;; End-to-end — a bound tool resolves in a real ```clojure block
;; ============================================================================

(deftest bound-tool-resolves-in-a-live-block
  (with-agent [a "nb-e2e"]
    (nb/install! a (sb-bind/make-tool-bindings a))
    (let [run (fn [code]
                (run-single-block nil
                                  {:lang "clojure" :code code :info-args []}
                                  {:auto-bg-ms 15000 :from-iteration 0 :agent a}))]
      (testing "blocks evaluate inside the agent's tool namespace"
        (let [entry (run "(clojure.core/str (clojure.core/ns-name *ns*))")]
          (is (str/blank? (str (:error entry))))
          (is (str/includes? (str (:result entry)) (str (nb/tool-ns-sym a))))))

      (testing "a registered tool is callable by bare name — the exact shape the
                system prompt's Function Directory advertises"
        (let [entry (run "(clojure.core/map? (usage$guide))")]
          (is (str/blank? (str (:error entry)))
              "must not fail with Unable to resolve symbol: usage$guide")
          (is (= "true" (str (:result entry)))
              "the binding dispatches through call-tool and returns the tool's map")))

      (testing "the var carries the tool's docs into the live image"
        (let [entry (run "(clojure.core/string? (:doc (clojure.core/meta #'read-file)))")]
          (is (= "true" (str (:result entry))))))

      (testing "the injected prefix never reaches the model's iteration record"
        (let [entry (run "(+ 1 2)")]
          (is (= "(+ 1 2)" (:code entry))
              ":code must stay the model's own text, not the wire copy")
          (is (= "3" (str (:result entry)))))))))

(deftest unbound-agent-still-evaluates
  ;; Regression guard: with no install (or a failed one) the eval path must fall
  ;; back to plain `user`-ns eval rather than dropping blocks into an empty
  ;; namespace with no clojure.core refers.
  (with-agent [a "nb-no-install"]
    (let [entry (run-single-block nil
                                  {:lang "clojure" :code "(+ 1 2)" :info-args []}
                                  {:auto-bg-ms 15000 :from-iteration 0 :agent a})]
      (is (str/blank? (str (:error entry))))
      (is (= "3" (str (:result entry)))))))
