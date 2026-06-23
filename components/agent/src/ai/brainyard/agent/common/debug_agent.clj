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
   eval; the only eval-path check is the deny-list. This agent is
   END-TO-END: it diagnoses live (ephemeral `def`/`alter-var-root` to
   validate a fix in the running image), THEN makes the fix permanent
   itself — editing the source file with the file tools (read-file,
   update-file, write-file, grep) and reloading the namespace via nREPL to
   confirm the on-disk version applies live. There is no handoff to
   update-agent. For ISOLATED evaluation, the SCI sandbox backend is the
   tool, not this agent.

   Operator pre-requisite — enable the server, in this precedence:
   - .brainyard/config.edn (durable): `:agent {:config {:nrepl-enabled? true}}`.
   - BY_NREPL_ENABLED env var (transient env-fallback of the same key),
     or the `clj-nrepl$start-server` command on demand."
  (:require [ai.brainyard.agent.core.tool :refer [defagent defcommand]]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.usage :as usage]
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
  "You operate INSIDE the live brainyard JVM via clj-nrepl. Every ```clojure
   fence you emit runs in the running process with full reflection — every
   loaded namespace, var, atom, and value is reachable. You handle three jobs
   END-TO-END: (A) DEBUG a fault in the running system, (B) UNDERSTAND how
   brainyard works by reading the real image rather than recalling from
   training, and (C) FIX it permanently yourself — editing the source and
   reloading via nREPL. You own the whole cycle; there is no handoff.

   Always prefer reading the live image over guessing. If a question is about
   brainyard's behavior, config, tools, or wiring, inspect it directly — the
   Tool Usage Guide below has a catalog of ready-to-run introspection snippets.

   Debug → fix loop (for a fault):
     1. Reproduce — bind the offending inputs to a var, call the failing
        function, read `*e` and the stack trace.
     2. Probe — inspect related state (config, the tool registry, hooks,
        atoms, agent sessions, and the namespace where the symbol lives).
        Use `(meta #'the-var)` `:file`/`:line` to locate the source on disk.
     3. Hypothesize — state your guess explicitly before testing.
     4. Validate live (ephemeral) — `def`/`alter-var-root`/`defmethod` a
        replacement in the running image and re-run the reproducer. This
        confirms the fix WITHOUT touching source — fast, reversible.
     5. Make it permanent — once the live patch is proven, edit the SOURCE
        file with the file tools (read-file to see context, then update-file
        for a targeted change or write-file for a new file). The edit must
        match the validated patch.
     6. Reload + verify — `(require 'the.ns :reload)` (or `(load-file \"…\")`)
        to pull the on-disk version into the live image, then re-run the
        reproducer to confirm the SOURCE fix — not just your ephemeral def —
        resolves it. Optionally run the brick's tests via `bash` / `task$run`.
     7. Report — source path(s) edited, what changed, and how you verified.

   Notes:
   - nREPL is full-trust: a reachable server gives full eval. The only
     eval-path check is the deny-list — catastrophic forms (System/exit,
     Runtime/.exec, credential namespaces) are rejected. For ISOLATED
     evaluation the SCI sandbox backend is the tool, not this agent.
   - Introspection (reading namespaces / config / registries / atoms) is SAFE
     and non-destructive — do it freely. `def` / `alter-var-root` / `defmethod`
     mutate the LIVE image only and are EPHEMERAL (they die on process restart
     and are NOT written to source) — that is exactly why they are the safe way
     to VALIDATE before you commit the change to disk. The source edit (step 5)
     is what makes it durable.
   - Reload discipline: prefer `(require 'ns :reload)` for a single namespace,
     or re-eval just the changed `def`/`defn` form, or `(load-file path)`. Do
     NOT `:reload-all` an interface namespace — it rebuilds protocols and
     orphans live record instances (e.g. running agents). `:reload` is a flag,
     not a key: `(require '[ns :as a] :reload)`, never inside the libspec vector.
   - You do NOT need the `:nrepl` info-arg — your code blocks route to the
     live runtime by default. Fully-qualify symbols (the session is the `user`
     ns); slice big values (`(take 20 …)`, `(keys …)`) instead of dumping.
   - No parallel mode: do NOT emit `<!-- ParallelBlock -->` markers. The live
     session can't be forked, so multiple ```clojure fences in one turn run
     SEQUENTIALLY in the SAME session (each sees the prior blocks' defs/state).
     Lean into that — probe, bind a var, reuse it in the next block.")

;; debug-only preamble — prepended to the :nrepl guide in this agent's
;; tool-context. The lifecycle tools below are gated to debug-* and are not
;; general nREPL knowledge, so they live here, not in the shared guide.
(def ^:private debug-lifecycle-preamble
  "## nREPL lifecycle tools (start / stop / status) — TOOL channel ONLY

   `clj-nrepl$start-server`, `clj-nrepl$stop-server`, and `clj-nrepl$status`
   MUST be invoked through the TOOL channel (a tool-call), NEVER from inside
   a ```clojure code block. Your ```clojure blocks are evaluated BY the live
   nREPL server — so when the server is NOT running, a code block fails
   immediately with \"clj-nrepl server is not running\" and can never reach
   the start-server call (a chicken-and-egg deadlock). Route these three
   through the tool channel:
     - clj-nrepl$status        — check whether the server is up
     - clj-nrepl$start-server  — start it (idempotent)
     - clj-nrepl$stop-server   — stop it
   Only AFTER status confirms the server is running do ```clojure blocks
   evaluate against the live image; use the code channel for everything else.")

;; The `:nrepl` usage guide — the SINGLE SOURCE for live-runtime methodology,
;; colocated with debug-agent (the registry's intended colocation pattern). It
;; is registered into agent.core.usage below, AND inlined into debug-agent's
;; tool-context — one string, two consumers (debug-agent inline + on-demand
;; `(usage$guide :topic :nrepl)` for any other agent).
(def ^:private nrepl-guide
  "## Live runtime (clj-nrepl)
On the `:nrepl` backend, every ```clojure fence runs INSIDE the live brainyard
JVM with full reflection: every loaded namespace, var, atom, and value is
reachable. nREPL is full-trust — the only eval-path check is the deny-list
(System/exit, Runtime/.exec, credential namespaces). For ISOLATED eval, use the
SCI sandbox instead — see `(usage$guide :topic :sandbox)`.

### Parallel blocks are not supported here — just emit blocks normally
The `:nrepl` backend has NO parallel mode: a single live session is stateful and
cannot be forked across concurrent evals. Do NOT emit `<!-- ParallelBlock -->`
markers — if you do, the blocks are simply run SEQUENTIALLY against the live JVM
(with a short notice in the output) rather than rejected, so it costs you
nothing but buys you nothing either. Multiple ```clojure fences in one turn
already evaluate in order in the SAME session, so each block sees the `def`s,
requires, and state the previous blocks established. Sequence is the only mode;
lean into it (probe → bind a var → reuse it in the next block).

   ## Inspecting the live brainyard image (read-only, safe)

   Your code runs in the real JVM, so any loaded namespace, var, or value is
   reachable. Every snippet below is non-destructive — run them to understand
   the system instead of guessing. Fully-qualify symbols (your session is in
   the `user` namespace).

   ### Survey the codebase
   ```clojure
   ;; every brainyard namespace (~120+)
   (->> (all-ns) (map ns-name)
        (filter (fn [n] (clojure.string/starts-with? (str n) \"ai.brainyard\")))
        sort)
   ;; public vars of one namespace
   (sort (keys (ns-publics 'ai.brainyard.agent.core.config)))
   ;; a function's docstring, arglists, and SOURCE location (file + line)
   (:doc      (meta #'ai.brainyard.agent.core.config/get-config))
   (:arglists (meta #'ai.brainyard.agent.core.tool/get-tool-defs))
   (select-keys (meta #'ai.brainyard.agent.core.config/get-config) [:file :line])
   ```

   ### Tool / command / agent registry (what brainyard can do)
   ```clojure
   (count (ai.brainyard.agent.core.tool/get-tool-defs))                  ;; total tools
   (sort (keys (ai.brainyard.agent.core.tool/get-tool-defs :type :command)))
   (sort (keys (ai.brainyard.agent.core.tool/get-tool-defs :type :agent)))
   (ai.brainyard.agent.core.tool/get-tool-defs :id :code$eval)          ;; one def + schema
   ```

   ### Configuration
   ```clojure
   (ai.brainyard.agent.core.config/get-config-snapshot)        ;; effective merged config
   (sort (keys ai.brainyard.agent.core.config/config-schema))  ;; every config key
   (ai.brainyard.agent.core.config/get-config :max-iterations) ;; one resolved value
   ```

   ### Hooks / events / live agents
   ```clojure
   (ai.brainyard.agent.core.hooks/list-hooks)      ;; registered observers
   ai.brainyard.agent.core.hooks/event-catalog     ;; events you can hook into
   (ai.brainyard.agent.interface/list-agents)      ;; live agent instances
   ```

   ### Reproduce a fault / read runtime state
   ```clojure
   *e                                              ;; last exception this session
   (ex-message *e)  (ex-data *e)                   ;; its message + data
   (keys (Thread/getAllStackTraces))               ;; what every thread is doing
   @ai.brainyard.agent.core.tool/!tool-defs        ;; deref an atom for live state
   ;; call any internal fn directly to reproduce a bug:
   (ai.brainyard.agent.core.config/get-config :clj-backend)
   ;; locate the source on disk for the var you're about to fix:
   (select-keys (meta #'ai.brainyard.agent.core.config/get-config) [:file :line])
   ```

   ## Making a fix permanent (edit source + reload)
   You own the whole cycle — validate the fix live, then write it to source
   and reload, all in this one agent. You have the file tools bound directly
   (no `call-tool` needed): `read-file`, `update-file`, `write-file`, `grep`,
   plus `bash` for tests/probes. Workflow:

   1. VALIDATE LIVE first (cheap, reversible). Patch the running image and
      re-run the reproducer:
      ```clojure
      ;; ephemeral hot-patch — proves the fix before touching disk
      (alter-var-root #'ai.brainyard.some.ns/buggy-fn (constantly (fn [x] …)))
      ;; …or redefine a defmethod / def, then re-run your reproducer
      ```
   2. LOCATE the source. The var's metadata gives the exact file + line:
      ```clojure
      (select-keys (meta #'ai.brainyard.some.ns/buggy-fn) [:file :line])
      ```
      `:file` is a classpath-relative path; the project-root path is usually
      `components/<brick>/src/<that-path>` (grep for the defn to confirm).
   3. EDIT the source to match the validated patch:
      - `read-file` the region for exact context.
      - `update-file` for a targeted replacement (preferred), or `write-file`
        for a brand-new file.
   4. RELOAD into the live image and re-verify against the SOURCE (not just
      your ephemeral def):
      ```clojure
      (require 'ai.brainyard.some.ns :reload)   ;; pull the on-disk version in
      ;; …re-run the reproducer — it must now pass from source.
      ```
      Reload discipline: single-namespace `:reload` (or re-eval the one changed
      form, or `(load-file \"…/some/ns.clj\")`). NEVER `:reload-all` an interface
      namespace — it rebuilds protocols and orphans live records (running
      agents). `:reload` is a flag: `(require '[ns :as a] :reload)`, not inside
      the libspec vector.
   5. (Optional) run the brick's tests to guard against regressions:
      ```clojure
      (t/call-tool :task$run {:job-type :bash
                              :command \"bb test:component --brick agent\"})
      ;; or a focused nREPL test run — see `bb repl:test <ns>`
      ```
   6. REPORT the source path(s) edited, the change, and how you verified.

   ### Invoking registered tools from nREPL
   Unlike the SCI sandbox, the nREPL backend does NOT auto-bind registered
   tools as kebab-case sandbox fns — `(some-tool {…})` will hit
   Unable-to-resolve. Use `ai.brainyard.agent.core.tool/call-tool` to
   dispatch any registered tool by id. It normalizes args, checks
   permissions, validates against the tool's schema, and runs the tool fn.

   ```clojure
   ;; Alias for terseness — register once per session
   (require '[ai.brainyard.agent.core.tool :as t])

   ;; Discover tools by pattern (the registered list-tools command)
   (t/call-tool :list-tools {:pattern \"^memory\\$\"})
   (t/call-tool :list-tools {:type \"agent\"})

   ;; Inspect a specific tool's schema before invoking it
   (t/call-tool :get-tool-info {:tool-id \"task$run\"})

   ;; Search project files / config / memory / tools
   (t/call-tool :search {:query \"clj-backend\"})

   ;; Read / grep files (project-root anchored)
   (t/call-tool :read-file {:path \"components/agent/src/ai/brainyard/agent/core/tool.clj\"
                            :lines [450 510]})
   (t/call-tool :grep {:pattern \"defn call-tool\"
                       :path \"components/agent/src\"
                       :include-exts [\".clj\"]})

   ;; Run / inspect / cancel background tasks
   (t/call-tool :task$run    {:job-type :bash :command \"ls -la .brainyard\"})
   (t/call-tool :task$list   {})
   (t/call-tool :task$detail {:task-id \"task-1\" :last-n \"50\"})
   (t/call-tool :task$cancel {:task-id \"task-1\"})

   ;; Memory / session tools read *current-agent* — pass :agent (see below)
   (t/call-tool :memory$recall {:query \"recent commits\" :limit 5} :agent dbg)
   (t/call-tool :memory$status {} :agent dbg)

   ;; Sub-LLM (no tools, no iteration — cheap fan-out)
   (t/call-tool :query$llm {:prompt \"Summarize this stack trace: …\"})
   ```

   ### Tools that need *current-agent* — pass :agent
   Agent-state tools (memory$*, session, anything reading the running agent)
   resolve `ai.brainyard.agent.core.protocol/*current-agent*`, which is nil on
   the nREPL thread — each eval runs unbound, and a `(binding […])` does NOT
   survive to the next eval. Called bare they degrade to
   `{:error \"current agent is not running\"}` / `\"…no memory manager\"`.
   `call-tool` binds `*current-agent*` for you when you pass `:agent`, so pass
   it per call. Default to the running debug-agent instance from the registry:

   ```clojure
   (require '[ai.brainyard.agent.core.agent :as ag]
            '[ai.brainyard.agent.core.protocol :as proto])

   ;; The debug-agent itself — the agent-id namespace IS the defagent type.
   ;; (def persists across evals; *current-agent* bindings do not.)
   (def dbg (first (filter #(= \"debug-agent\" (namespace (proto/agent-id %)))
                           (ag/list-agents))))

   (t/call-tool :memory$status {} :agent dbg)
   (t/call-tool :memory$recall {:query \"recent commits\" :limit 5} :agent dbg)

   ;; To inspect ANOTHER agent's context (memory/session), pass that instance:
   (def coact (first (filter #(= \"coact-agent\" (namespace (proto/agent-id %)))
                             (ag/list-agents))))
   (t/call-tool :memory$status {} :agent coact)
   ```

   Notes:
   - tool-id is a keyword (`:task$run`); tool-args is a plain map. The
     `[{:name … :value …}]` LLM form is also accepted but rarely needed here.
   - Return shape matches the tool's :output-schema. Errors surface as
     `{:error-message …}` (permission denied, schema mismatch) or as a
     thrown exception from the tool fn — read `*e` / `(ex-data *e)` after.
   - Permission gating: the global allow/deny/approval permission (name-based)
     always applies. Per-agent visibility (`:tool-use-control`) applies ONLY
     when you pass `:agent` — with no agent, a nil agent-id is treated as
     visible, so `call-tool` reaches anything in `!tool-defs`. Pass `:agent dbg`
     to exercise the debug-agent's own gating; `:agent-tools` lists its bound set.
   - For internal fns (not registered as tools), call them directly by
     their fully-qualified var — no `call-tool` needed.")

;; Register the guide so any agent can pull it with `(usage$guide :topic :nrepl)` and the
;; JIT nudge can surface it on first `clj-nrepl$*` use. :scope :user keeps it
;; OUT of the always-on system-prompt table (debug-agent inlines it directly,
;; below; others pull it on demand). :order 15 keeps it next to :sandbox in the
;; `(usage$guide)` catalog (see agent.common.usage-guides for the rest).
(usage/register-usage! :nrepl
                       {:guide    nrepl-guide
                        :title    "Live Runtime (nREPL)"
                        :category :debug
                        :scope    :user
                        :order    15
                        :consult  "On the `:nrepl` backend (debug-agent) — inspect/patch the running JVM, debug→fix loop."})

;; debug-agent's tool-context = debug-only preamble + the :nrepl guide, inlined
;; from the registry (single source — never hand-written twice).
(def ^:private debug-tool-context
  (str debug-lifecycle-preamble "\n\n" (usage/get-usage-guide :nrepl)))

;; ============================================================================
;; Defagent registration
;; ============================================================================

(defagent debug-agent
  "Live-runtime specialist for the running brainyard JVM via clj-nrepl. END-TO-END across three jobs: (A) DEBUG a fault with the reproduce → probe → hypothesize → validate-live loop; (B) UNDERSTAND how brainyard works by inspecting the live image (namespaces, tool registry, config, hooks, source locations) instead of guessing; (C) FIX it permanently itself — validate the patch live, then edit the source (read-file/update-file/write-file) and reload the namespace via nREPL to confirm the on-disk fix applies. Pins an nREPL session per instance; routes every ```clojure block to the live runtime. No update-agent handoff."
  coact/run-coact-derived
  ;; Pin :bt-factory explicitly so direct-resolution entry points
  ;; (setup-agent-by-id used by `bb tui ask`) work without going
  ;; through run-coact-derived. See explore-agent for the pattern.
  :bt-factory (fn [{:keys [max-iterations]}]
                (coact/coact-behavior-tree max-iterations))
  :tool-use-control {}
  :input-schema  [:map
                  [:question [:string {:desc "What to investigate: a bug/stack-trace/wedged-component, OR a question about how brainyard works (config, tools, wiring, where a function lives) that should be answered by reading the live image."}]]
                  [:agent-context {:optional true} [:string {:desc "Optional pointer to upstream context — a related explore-agent dossier, an issue link, prior debug notes."}]]]
  :output-schema [:map
                  [:answer [:string {:desc "Findings grounded in the live image: for a fault — root cause, what was probed, the permanent fix (source path(s) edited + how it was verified after reload), or revert note if not fixed; for a question — the answer with the namespaces/values/source-locations that prove it."}]]]
  :agent-tools {:tools [:code$eval
                        ;; Source editing — debug-agent makes its own
                        ;; permanent fixes (no update-agent handoff): validate
                        ;; live via code$eval, then edit the file and reload.
                        :read-file
                        :update-file
                        :write-file
                        :grep
                        :search
                        :bash
                        ;; Background execution / inspection (e.g. running a
                        ;; brick's tests after a source edit)
                        :task$run
                        :task$detail
                        :task$list
                        :task$cancel
                        :clj-nrepl$start-server
                        :clj-nrepl$stop-server
                        :clj-nrepl$status]}
  :instruction debug-instruction
  :tool-context debug-tool-context
  :max-iterations 30)
