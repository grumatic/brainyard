;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.code-eval
  "Unified code-evaluation surface.

   One registered command — `code$eval` — fronts two backends:

     :sandbox  SCI sandbox (clj-sandbox). Safe and isolated; default.
               Direct callers go through clj-sandbox's standalone API or
               (most commonly) the CoAct fence path, which holds the
               per-turn sandbox handle.

     :nrepl    Live brainyard runtime (clj-nrepl). Gated by an active
               grant + read-only classifier. Use to observe, debug,
               improve, or extend the running system.

   The CoAct fence parser (clj-sandbox prompt) reads a trailing info-arg
   on the opening fence (```clojure :nrepl) and tags the block; the
   CoAct dispatch then routes to :clj-nrepl-eval. Hook payloads carry
   :backend so observers can distinguish sandbox from live evals.

   See docs/design/clj-nrepl-eval.md."
  (:require [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.clj-nrepl.interface :as clj-nrepl]
            [ai.brainyard.mulog.interface :as mulog]))

(defn- dispatch-nrepl-eval
  "Synchronous :nrepl arm. Defers to clj-nrepl/eval-string, whose only
   eval-path check is the deny-list (nREPL is the full-trust backend; for
   isolation use the :sandbox backend). The enclosing ToolJobExecutor
   already wraps tool calls in a future with timeout, so a sync call here is
   bounded per-call without us managing a second task."
  [code {:keys [session timeout-ms]}]
  (if-not (clj-nrepl/running?)
    {:error (str "clj-nrepl server is not running — start it with the "
                 "clj-nrepl$start-server command (debug-agent), or set "
                 "BY_NREPL_ENABLED=true on bootstrap")
     :code code :output "" :backend :nrepl}
    (-> (clj-nrepl/eval-string code
                               :session    session
                               :timeout-ms (or timeout-ms 30000))
        (assoc :backend :nrepl))))

(defn- sandbox-arm-error
  "The :sandbox arm needs a per-turn sandbox handle that only the CoAct
   dispatch path holds. Direct LLM tool calls of code$eval get a
   pointer back to the fence form instead of a silent failure."
  [code]
  {:error
   (str "code$eval :sandbox is reachable only via the CoAct ```clojure fence "
        "in Phase 1. Emit the code as a fenced block; the agent will route "
        "it to the SCI sandbox automatically.")
   :code code :output "" :backend :sandbox})

(defcommand code$eval
  "Evaluate Clojure code. Two backends:
     :sandbox  SCI sandbox (default, safe). Use a ```clojure fence in CoAct.
     :nrepl    LIVE brainyard runtime (requires BY_NREPL_GRANT).
               Use to observe / debug / extend the running system."
  (fn [{:keys [code backend session timeout-ms]}]
    ;; When the caller doesn't pin :backend, default to the current agent's
    ;; :clj-backend — so a direct code$eval tool-call from an :nrepl-backed
    ;; agent (e.g. debug-agent) hits the live runtime instead of erroring with
    ;; a sandbox-only pointer. Sandbox agents (schema default) are unchanged.
    (let [backend (or backend
                      (config/get-config proto/*current-agent* :clj-backend)
                      :sandbox)]
      (mulog/info ::code-eval
                  :backend  backend
                  :code-len (count (or code "")))
      (case backend
        :nrepl   (dispatch-nrepl-eval code {:session session :timeout-ms timeout-ms})
        (sandbox-arm-error code))))
  :input-schema  [:map
                  [:code [:string {:desc "Clojure code to evaluate."}]]
                  [:backend {:optional true} [:enum {:desc "Eval backend (default :sandbox)." :default :sandbox} :sandbox :nrepl]]
                  [:session {:optional true} [:string {:desc "nREPL session id for stateful :nrepl sequences."}]]
                  [:timeout-ms {:optional true} :int]]
  :output-schema [:map
                  [:result [:any {:desc "Result value (pr-str-ed)."}]]
                  [:output [:string {:desc "Captured stdout/stderr."}]]
                  [:ns {:optional true} [:string {:desc "Resulting namespace (nREPL only)."}]]
                  [:error {:optional true} [:any {:desc "Error message if eval failed."}]]
                  [:backend [:keyword {:desc "Backend actually used."}]]])
