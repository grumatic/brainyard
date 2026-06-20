;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.debug-agent
  "Live-runtime debug specialist.

   Drives a self-debugging loop against the running brainyard JVM via
   clj-nrepl. Sibling to explore-agent and exec-agent; CoAct-derived, so
   the BT loop, hooks, and channel discipline come for free.

   Per-instance lifecycle:
   - On :agent.instance/created — if the in-process nREPL server is up,
     open a server-issued session and pin it on this instance's
     per-agent config (`:nrepl-session-id`). Also write
     `:clj-backend :nrepl` so every clojure fence routes to the live
     runtime. CoAct's run-clj-nrepl-block reads both from the agent
     config; there is no per-fence override (the fence accepts only the
     language token).
   - On :agent.instance/closed — close the session.

   nREPL is the full-trust backend: a reachable loopback server gives full
   eval; the only eval-path check is the deny-list. Edits are EPHEMERAL
   (they die on process restart, are not written to source) — make a fix
   permanent by handing off to update-agent. For ISOLATED evaluation, the
   SCI sandbox backend is the tool, not this agent.

   Operator pre-requisite — enable the server, in this precedence:
   - .brainyard/config.edn (durable): `:agent {:config {:nrepl-enabled? true}}`.
   - BY_NREPL_ENABLED env var (transient env-fallback of the same key),
     or the `clj-nrepl$start-server` command on demand."
  (:require [ai.brainyard.agent.core.tool :refer [defagent defcommand]]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.common.coact-agent :as coact]
            ;; Loading code-eval ensures `code$eval` is in the registry
            ;; whenever debug-agent is on the classpath — the defagent's
            ;; :agent-tools vector references it.
            [ai.brainyard.agent.common.code-eval]
            [ai.brainyard.clj-nrepl.interface :as clj-nrepl]
            [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; nREPL server lifecycle — start / stop / status (debug-agent only)
;;
;; The embedded loopback nREPL server is normally started at bootstrap via
;; BY_NREPL_ENABLED=true. These commands let the debug-agent manage it on
;; demand without a process restart. nREPL is full-trust: reaching the server
;; gives full eval (the only eval-path check is the deny-list); for isolation
;; use the SCI sandbox backend. Gated to debug-* via :tool-use-control AND
;; bound on debug-agent's :agent-tools.
;; ============================================================================

(defcommand clj-nrepl$start-server
  "Start the embedded loopback-only nREPL server (idempotent — a no-op when one
   is already running). Writes a per-instance port file
   (~/.brainyard/nrepl-ports/by-<pid>.port) so external CIDER tooling can attach
   to the SAME live image. nREPL is full-trust: reaching the server gives full
   eval (the only eval-path check is the deny-list); isolation is the SCI
   sandbox backend's job."
  (fn [{:keys [port]}]
    (let [already? (clj-nrepl/running?)
          srv-port (if already?
                     (clj-nrepl/server-port)
                     (do (clj-nrepl/cleanup-stale-ports!)
                         (:port (clj-nrepl/start-server!
                                 :port (or port 0)
                                 :port-file (clj-nrepl/instance-port-file "by")))))]
      {:running true
       :port srv-port
       :port-file (str (clj-nrepl/instance-port-file "by"))
       :already-running already?}))
  :input-schema  [:map
                  [:port {:optional true}
                   [:int {:desc "Fixed loopback port to bind. Default 0 = ephemeral."}]]]
  :output-schema [:map
                  [:running [:boolean {:desc "True once the server is up."}]]
                  [:port [:int {:desc "Bound loopback port."}]]
                  [:port-file [:string {:desc "Per-instance port file path for external attach."}]]
                  [:already-running [:boolean {:desc "True when a server was already running (start was a no-op)."}]]]
  :tool-use-control {:allow ["debug-*"]})

(defcommand clj-nrepl$stop-server
  "Stop the embedded nREPL server if running and remove its per-instance port
   file. No-op (returns :stopped false) when no server is running."
  (fn [_]
    (if-not (clj-nrepl/running?)
      {:running false :stopped false :message "no nREPL server running"}
      (let [port (clj-nrepl/server-port)
            pf   (clj-nrepl/instance-port-file "by")]
        (clj-nrepl/stop-server!)
        (try (when (.exists pf) (.delete pf)) (catch Throwable _ nil))
        {:running false :stopped true :was-port port})))
  :input-schema  [:map]
  :output-schema [:map
                  [:running [:boolean {:desc "Server running state after the call (false on success)."}]]
                  [:stopped [:boolean {:desc "True when a running server was stopped."}]]
                  [:was-port {:optional true} [:int {:desc "Port the stopped server had been bound to."}]]
                  [:message {:optional true} [:string {:desc "Present when there was nothing to stop."}]]]
  :tool-use-control {:allow ["debug-*"]})

(defcommand clj-nrepl$status
  "Status of the live-runtime channel: whether the loopback nREPL server is
   running, its port, and the inventory of known per-instance port files."
  (fn [_]
    {:running    (clj-nrepl/running?)
     :port       (clj-nrepl/server-port)
     :port-files (vec (clj-nrepl/list-port-files))})
  :input-schema  [:map]
  :output-schema [:map
                  [:running [:boolean {:desc "True when an nREPL server is up in this process."}]]
                  [:port [:any {:desc "Loopback port (int) or nil when not running."}]]
                  [:port-files [:any {:desc "Known per-instance port files: {:pid :port :file :alive?}."}]]]
  :tool-use-control {:allow ["debug-*"]})

;; ============================================================================
;; Per-instance lifecycle — open / close nREPL session
;;
;; The execution-model prompt section is selected by coact-system-context
;; based on the agent's :clj-backend config; setting :clj-backend :nrepl
;; below is sufficient — no separate :execution-model write needed.
;; ============================================================================

(defn- debug-agent?
  "True when `agent` is a debug-agent instance (agent-id namespaced by
   :debug-agent)."
  [agent]
  (and agent (= :debug-agent (proto/defagent-type agent))))

(defn- write-config!
  "Per-instance config write. Bypasses agent.core.config/set-config! to
   avoid the global / .brainyard/config.edn write that 2- and 3-arity
   set-config! perform — instance-scoped state must not leak into
   global config. Mirrors the set-allowed-dirs! pattern."
  [agent k v]
  (when-let [smi (some-> agent :!state deref :st-memory-init)]
    (swap! smi assoc-in [:config k] v)))

(defn- on-instance-created
  "Pin a server-issued nREPL session id + the :clj-backend route on the
   new debug-agent instance. When the server isn't running, the agent
   still starts — first code-eval call will surface the gate error so
   the LLM can report it."
  [{:keys [agent]}]
  (when (debug-agent? agent)
    (write-config! agent :clj-backend :nrepl)
    (if (clj-nrepl/running?)
      (try
        (let [sid (clj-nrepl/new-session)]
          (write-config! agent :nrepl-session-id sid)
          (mulog/info ::debug-agent-session-opened
                      :agent-id (proto/agent-id agent)
                      :session  sid))
        (catch Throwable t
          (mulog/warn ::debug-agent-session-open-failed
                      :agent-id (proto/agent-id agent)
                      :error    (.getMessage t))))
      (mulog/warn ::debug-agent-no-server
                  :agent-id (proto/agent-id agent)
                  :message  "clj-nrepl server not running; debug-agent will fail at first eval"))))

(defn- on-instance-closed
  "Close the pinned nREPL session when the agent is torn down."
  [{:keys [agent]}]
  (when (debug-agent? agent)
    (when-let [sid (some-> agent :!state deref
                           :st-memory-init :config :nrepl-session-id)]
      (try (clj-nrepl/close-session sid) (catch Throwable _))
      (mulog/info ::debug-agent-session-closed
                  :agent-id (proto/agent-id agent)
                  :session  sid))))

;; register-hook! dedupes by [event-key handler-id], so registering at
;; ns load (and across reloads) is safe.
(hooks/register-hook! :agent.instance/created ::debug-agent-created
                      on-instance-created :source :debug-agent)
(hooks/register-hook! :agent.instance/closed ::debug-agent-closed
                      on-instance-closed :source :debug-agent)

;; ============================================================================
;; Instruction
;; ============================================================================

(def ^:private debug-instruction
  "You are debugging the LIVE brainyard JVM image via clj-nrepl. Every
   ```clojure fence you emit runs in the running process — `(*e)`,
   `Thread/getAllStackTraces`, full reflection, and the entire tool
   registry are all reachable.

   Loop:
     1. Reproduce — bind the offending inputs to a var, call the failing
        function, read *e and the stack trace.
     2. Probe — inspect related state (Integrant system map, atoms, the
        tool registry, agent sessions, hooks).
     3. Hypothesize — state your guess explicitly before testing.
     4. Test — propose a fix by `alter-var-root`-ing or `def`-ing the
        replacement, then re-run the reproducer to confirm.

   Notes:
   - nREPL is full-trust: a reachable server gives full eval. The only
     eval-path check is the deny-list — catastrophic forms (System/exit,
     Runtime/.exec, credential namespaces) are rejected. For ISOLATED
     evaluation the SCI sandbox backend is the tool, not this agent.
   - Your edits are EPHEMERAL: a `def`/`alter-var-root` changes the running
     image but dies on process restart and is NOT written to source. To make
     a fix permanent, hand off to update-agent — it owns the source edit
     (probe→apply→verify→persist→rollback). This agent never edits files.
   - You do NOT need to add the `:nrepl` info-arg — your code blocks route
     to the live runtime by default.")

;; ============================================================================
;; Defagent registration
;; ============================================================================

(defagent debug-agent
  "Live-runtime debug specialist: drives the clj-nrepl reproduce → probe → hypothesize → test loop against the running brainyard JVM. Pins an nREPL session per instance so multi-turn investigations accumulate state; routes every ```clojure block to the live runtime via clj-nrepl. Edits are ephemeral — hand a permanent fix to update-agent."
  coact/run-coact-derived
  ;; Pin :bt-factory explicitly so direct-resolution entry points
  ;; (setup-agent-by-id used by `bb tui ask`) work without going
  ;; through run-coact-derived. See explore-agent for the pattern.
  :bt-factory (fn [{:keys [max-iterations]}]
                (coact/coact-behavior-tree max-iterations))
  :tool-use-control {}
  :input-schema  [:map
                  [:question [:string {:desc "Bug description, stack trace, or wedged-component observation to investigate."}]]
                  [:agent-context {:optional true} [:string {:desc "Optional pointer to upstream context — a related explore-agent dossier, an issue link, prior debug notes."}]]]
  :output-schema [:map
                  [:answer [:string {:desc "Investigation summary: root cause, what was probed, any ephemeral hot-patch applied, and recommended next step (hand off to update-agent / revert / dig deeper)."}]]]
  :agent-tools {:tools [:code$eval
                        :task$detail
                        :task$list
                        :task$cancel
                        :clj-nrepl$start-server
                        :clj-nrepl$stop-server
                        :clj-nrepl$status]}
  :instruction debug-instruction
  :max-iterations 30)
