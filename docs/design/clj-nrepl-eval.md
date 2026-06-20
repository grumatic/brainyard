# clj-nrepl-eval — Live-Runtime Eval for a Self-Debugging, Self-Improving, Self-Extending Brainyard

> **⚠ Superseded (2026-06) — nREPL simplified to full-trust, deny-list only.**
> The grant (scope/TTL), read-only/`:mutate` classifier gate, first-mutation
> confirmation, runtime-drift markers, audit shim, and the Phase-3
> `debug$promote-hot-patch` promotion hand-off were all **removed**. A reachable
> loopback server gives full `eval`; the only eval-path check is the deny-list.
> Rationale: static syntactic analysis can't soundly isolate a live nREPL, so
> isolation is delegated to the SCI sandbox backend (`:clj-backend :sandbox`).
> Sections below describing those layers are historical design rationale.

> **Status:** Phases 1–3 implemented (May 2026). Phase 4 (hardening) deferred.
> **Scope:** new component `components/clj-nrepl` (in-process nREPL server + loopback client + grant + classifier + confirm + drift + audit), a unified `code$eval` command in `components/agent/.../common/code_eval.clj` (note: lives in `common/`, not `core/tool` as originally proposed), a `NreplEvalJobExecutor` in `components/agent/.../task/executor.clj`, a CoAct backend selector in `coact_agent.clj`, opt-in bootstrap hooks in `bases/agent-tui` and `bases/agent-web`, a CoAct-derived specialist `debug-agent` (see `docs/design/debug-agent-design.md`), and a TUI drift chip on the status bar.
> **Built on:** `ai.brainyard.clj-sandbox.interface` (the existing SCI eval path), the unified tool registry (`ai.brainyard.agent.core.tool/!tool-defs`), the task manager job-type model (`ai.brainyard.agent.task.protocol/IJobExecutor`), and `nrepl/nrepl` (already on the classpath at `1.3.0` for dev REPLs; promoted to a runtime dep of the new component).
> **Related reading:** `docs/CoAct.md`, `docs/rlm-agent-design.md`, `docs/design/debug-agent-design.md` (the specialist that drives the self-debug loop), `docs/design/update-agent-design.md` (the source-promotion hand-off target), `docs/design/observability.md`, `docs/build-and-deploy.md`, `docs/core/task.md` (the `NreplEvalJobExecutor` job type).

---

## 1. Motivation

Brainyard already gives the LLM a Clojure REPL: `clj-sandbox` loads context into an SCI interpreter and lets the agent write code to inspect and transform it. That sandbox is deliberately *walled off* from the host JVM. Its `sci/init` whitelists a small set of classes (`Math`, `Long`, `java.time.*`, …), denies `System` / `Runtime` / `ProcessBuilder` / `ClassLoader`, and exposes only auto-bound registry/MCP tools plus a few data libraries. That is exactly right for the RLM use case — *process untrusted, oversized context safely* — and exactly wrong for a different, equally valuable use case:

> The LLM cannot debug, fix, or extend **brainyard itself** from inside the sandbox, because the running brainyard process is precisely the thing SCI is built to keep out of reach.

When the agent hits a bug in a live session — a wedged Integrant component, a tool that throws, a session whose state has drifted — the only recovery today is for a human to drop into a dev nREPL (`bb repl:ata`) and poke at the running image by hand. The agent has no path to its own runtime. Likewise it cannot redefine a misbehaving function without a full rebuild, cannot register a new tool mid-session, and cannot read its own Integrant system map to see what is actually wired.

Clojure makes the alternative cheap and idiomatic. A running Clojure process can host an **nREPL server**; any client speaking the nREPL protocol can then evaluate code *in that live image* — redefining vars, requiring namespaces, inspecting state — with full reflection and interop, no SCI restrictions. This is how every Clojure developer already debugs a running system. The proposal is to give the LLM the same capability, behind the same `code-eval` surface it already uses, with a hard safety boundary around it.

**Thesis.** Start an nREPL server inside the brainyard process at bootstrap, and add a second eval backend — `clj-nrepl-eval` — that runs code against the *live runtime* rather than the SCI sandbox. Both backends sit behind one unified `code$eval` command that selects a backend per call. The sandbox backend stays the safe default for context work; the nREPL backend is an opt-in, gated, fully-audited capability for the system to observe, debug, improve, and extend itself.

---

## 2. Design Principles

1. **One eval surface, two backends.** The agent already evaluates Clojure through CoAct's `coact-code-eval-action` and the `:clj-sandbox-eval` task job-type. We do not add a parallel, differently-shaped tool. We generalize the existing one into `code$eval` with a `:backend` selector (`:sandbox` | `:nrepl`). Code blocks, results, and hooks keep their current shape.
2. **Sandbox is the default; nREPL is opt-in.** Absent an explicit, currently-enabled grant, every eval routes to the SCI sandbox exactly as today. `:nrepl` must be turned on deliberately (config + runtime grant) and is never the silent default.
3. **Go through the protocol, not around it.** `clj-nrepl-eval` talks to the in-process server over a loopback nREPL **client**, rather than calling `clojure.core/eval` directly in the host thread. This buys session isolation, per-session namespace state, interruptible evaluation, and — via `cider-nrepl` middleware — first-class introspection ops (`info`, `complete`, `eldoc`, `stacktrace`) that map directly onto the self-observing use case.
4. **Server lifecycle is an Integrant key.** The nREPL server is a managed resource with `ig/init-key` / `ig/halt-key!`, following the established pattern in `ai.brainyard.config.core.aero` and `ai.brainyard.server.core.httpkit`. It binds loopback-only by default and is absent unless config enables it.
5. **Privilege is explicit, scoped, and revocable.** Crossing from sandbox to live runtime is a privilege boundary, not a parameter tweak. It is gated by config, a runtime grant with TTL, an allow/deny policy, and a kill-switch. (§8 is a first-class section, not a footnote.)
6. **Everything is audited.** Every `clj-nrepl-eval` — code, session, caller agent, result/exception, duration — is logged through `mulog` and surfaced to observability. The live runtime is the crown jewels; nothing touches it silently.
7. **Improvements are durable only when promoted.** A redefinition in the live image is ephemeral and dies with the process. Turning a runtime fix into a real change means writing source and rebuilding — `clj-nrepl-eval` hands off to `update-agent` for that, it does not silently rewrite files.
8. **Native-image aware.** The `by` GraalVM binary cannot host a stock nREPL/SCI-driven REPL the way the JVM can (no runtime classloading/compilation). `clj-nrepl-eval` is a **JVM-mode capability**; on the native binary it is unavailable and degrades to a clear "not supported in native image" error. The sandbox backend remains available everywhere.

---

## 3. Position in the Stack

```
                          code$eval   (unified eval command — one surface)
                              │  selects :backend per call
              ┌───────────────┴────────────────┐
        :backend :sandbox                 :backend :nrepl   (opt-in, gated, audited)
              │                                  │
   clj-sandbox.interface              clj-nrepl.interface
   (SCI: walled off from host)        (loopback nREPL client → in-process server)
              │                                  │
   eval-code / eval-code-thunk        nREPL session ─────► live brainyard JVM
   FINAL / FINAL-VAR termination      (full reflection/interop, redef, require)
              │                                  │
   ClojureSandboxJobExecutor          NreplEvalJobExecutor
   (task job-type :clj-sandbox-eval)  (task job-type :clj-nrepl-eval)
                              │
                   task manager (IJobExecutor, detach-capable)
```

`clj-nrepl-eval` is a **sibling backend**, not a new agent. It plugs into the same task manager, the same registry, and the same code-eval hooks the sandbox backend already uses. The privilege boundary is the only thing that differs, and it lives entirely in the dispatch + policy layer.

---

## 4. Unified `code$eval`

### 4.1 Today

Code evaluation currently surfaces two ways, both pinned to the sandbox:

- CoAct's BT action `coact-code-eval-action` (`ai.brainyard.agent.common.coact-agent`) parses fenced `:code-blocks`, dispatches Clojure blocks to the sandbox, populates `:last-eval-results`, and fires `:agent.code-eval/pre` / `:agent.code-eval/post`.
- The task manager runs the actual work under job-type `:clj-sandbox-eval` via `ClojureSandboxJobExecutor` (`ai.brainyard.agent.task.executor`), which builds a thunk with `clj-sandbox/eval-code-thunk`, launches it in a daemon future, and projects `{:code :output :result :error :final-value}`.

### 4.2 Proposed

Introduce a single registered command, `code$eval`, defined with `defcommand` against `!tool-defs`, that owns backend selection:

```clojure
(defcommand code$eval
  "Evaluate Clojure code. Default backend is the SCI sandbox (safe, isolated).
   Backend :nrepl evaluates in the LIVE brainyard runtime and requires an
   active runtime grant — use it to observe, debug, improve, or extend the
   running system."
  (fn [& {:keys [code backend session eval-timeout-ms on-timeout]
          :or   {backend :sandbox}}]
    (case backend
      :sandbox (dispatch-sandbox-eval code {...})        ; existing path, unchanged
      :nrepl   (dispatch-nrepl-eval   code {:session session ...})))
  :inputs  {:code            [:string {:desc "Clojure code to evaluate."}]
            :backend         [:keyword {:desc ":sandbox (default) | :nrepl (live runtime, gated)."
                                        :default :sandbox :optional true}]
            :session         [:string {:desc "nREPL session id for stateful :nrepl sequences." :optional true}]
            :eval-timeout-ms [:int {:optional true}]
            :on-timeout      [:keyword {:desc ":detach (default) | :kill" :optional true}]}
  :outputs {:result [:any] :output [:string] :error [:any] :backend [:keyword]})
```

The `:sandbox` arm is the current code path verbatim — no behavioral change, no new risk. The `:nrepl` arm runs the gate (§8) and, if granted, dispatches a `:clj-nrepl-eval` task. CoAct's `coact-code-eval-action` keeps emitting sandbox evals by default; an info-fence directive (e.g. ` ```clojure :nrepl `) or an explicit `code$eval` tool call selects the nREPL backend, so the model expresses intent the same way it already expresses code.

Return shape stays aligned with the sandbox path so downstream accumulators and the eval-agent need no special-casing — `:final-value` (FINAL/FINAL-VAR) is simply absent for nREPL results, which terminate by returning a value, not by sandbox termination signalling.

---

## 5. nREPL Server Bootstrap

### 5.1 New component: `components/clj-nrepl`

Mirrors the `clj-sandbox` shape: a thin interface over a small core.

- `ai.brainyard.clj-nrepl.interface`
  - `start-server!` / `stop-server!` — lifecycle over `nrepl.server`.
  - `eval-string` — send code to the server via a loopback client, return `{:result :output :error :ns}`.
  - `eval-thunk` — caller-owned-timeout variant, matching `clj-sandbox/eval-code-thunk`, so the task executor can stay symmetric.
  - `new-session` / `close-session` / `interrupt!` — session lifecycle and cooperative interrupt.
  - `describe` / `info` / `complete` — introspection passthroughs (cider-nrepl middleware) for the self-observing use case (§7.1).
- `ai.brainyard.clj-nrepl.core.*` — server, client (a pooled loopback `nrepl.core` connection), session registry, audit shim.

### 5.2 Bootstrap config

The server is gated by three schema keys in `agent.core.config/config-schema` (`[:agent :config :*]` in the persisted EDN — same shape as every other tunable):

```clojure
:nrepl-enabled?  {:type "boolean"
                  :default-fn #(= "true" (System/getenv "BY_NREPL_ENABLED"))}
:nrepl-port      {:type "integer"
                  :default-fn #(or (some-> (System/getenv "BY_NREPL_PORT") parse-long) 0)}
:nrepl-grant     {:type "string"
                  :default-fn #(System/getenv "BY_NREPL_GRANT")}
```

Precedence is the standard `get-config` chain — schema `:default-fn` (env var) < `!global-config` (`.brainyard/config.edn`) < session-config < per-agent override. Operators enable nREPL durably with:

```clojure
;; ~/.brainyard/config.edn  (or <project>/.brainyard/config.edn)
{:agent {:config {:nrepl-enabled? true
                  :nrepl-grant    "read-only:15m"}}}
```

…and existing `BY_NREPL_*` scripts keep working as the env layer. config-agent can flip these knobs transactionally via `config$apply` (snapshot + diff + dossier); the `[:agent :config :*]` validator type-checks against `config-schema` automatically.

The earlier proposal threaded the server through an Integrant key + Aero config; the shipped implementation puts the knobs in the runtime config schema instead and keeps the server as a `defonce !nrepl-server` in each base (§5.3) — symmetric with `agent-web`'s `defonce !server` and with no new Integrant wiring to maintain.

### 5.3 Wiring into the bases

The runnable bases (`bases/agent-tui`, `bases/agent-web`) start the server during bootstrap when config enables it. The agent-tui base currently boots through `ai.brainyard.agent-tui.core/start!` with global atoms (`!session-store`, `!input-queue`) rather than a full Integrant system; the cleanest minimal change is a `defonce !nrepl-server` started from `start!` and stopped from `stop!` — symmetric with agent-web's `defonce !server`. Bases that already run Integrant (replicant, electric, fulcro-rad) just add the key to their system map. The port is written to `.brainyard/nrepl-port` so external CIDER tooling can attach to the same image the LLM is using — humans and the agent share one runtime.

`nrepl/nrepl` and `cider/cider-nrepl` are presently injected ad-hoc by the `bb repl:*` tasks via the `:dev:nrepl` alias and are *not* in the runtime `deps.edn`. This component promotes `nrepl/nrepl` to a runtime dependency of `clj-nrepl` (small, pure-Clojure); `cider-nrepl` stays optional and is required only when richer introspection middleware is wanted.

---

## 6. Introspection & Mutation Surface

Once a session is open, `clj-nrepl-eval` exposes the live image. The interesting targets, all reachable as ordinary Clojure from inside the running process:

- **Tool registry** — `@ai.brainyard.agent.core.tool/!tool-defs`: enumerate, inspect, add, or replace command/skill/agent entries at runtime.
- **Integrant system map** — wherever the running system is held (per-base atom / system var): read which components are up, their resolved config, and live handles (server, connections).
- **Agent & session state** — the per-iteration `st-memory` atom and its prompt-facing two-layer view (L1 inputs / L2 working `def`s) via `context-get`, the active session store, hooks registry (`:agent.tool-use/*`, `:agent.code-eval/*`, `:agent.ask/*`).
- **Namespaces & vars** — `find-ns`, `ns-publics`, `var` values, `meta`; redefine with `alter-var-root` / `def`; `require ... :reload`.
- **Diagnostics** — `Thread/getAllStackTraces`, JMX/memory, `*e` last exception, mulog event tap — the things you reach for when something is wedged.

The `cider-nrepl` ops (`info`, `eldoc`, `complete`, `stacktrace`) make these legible to the LLM without it hand-rolling reflection, which keeps generated code short and reduces the chance of a destructive mistake.

---

## 7. The Four Self-* Capabilities

### 7.1 Self-observing

The lowest-privilege, highest-value mode: read-only introspection. "What components are running and how are they configured?" → read the system map. "What tools are registered?" → deref `!tool-defs`. "Why is this session producing odd answers?" → inspect its `st-memory` atom and the L1/L2 sandbox view. This subsumes much of `docs/design/observability.md` from *inside* the runtime instead of through emitted telemetry. A read-only policy profile (§8) can permit self-observation while still forbidding mutation, making this the safe on-ramp.

### 7.2 Self-debugging

When a tool throws or a component wedges, the agent reproduces the failure live: bind the offending inputs, call the function, read `*e` and the `stacktrace` op, narrow the cause, then test a candidate fix by `alter-var-root`-ing the function and re-running — all without a restart. The session-scoped nREPL state means a multi-step investigation accumulates context naturally across `code$eval` calls sharing a `:session`. The `eval-agent` (`docs/design/eval-agent-design.md`) is the natural driver: it already owns the "run code, judge result, iterate" loop.

### 7.3 Self-improving

A validated fix from §7.2 is still ephemeral — it lives only in the running image. Two outcomes:

1. **Hot-patch only** (default for incident recovery): keep the redefinition in the live process to unblock the current session; log it loudly as a divergence between source and runtime.
2. **Promote to source**: hand the diff to `update-agent` (`docs/design/update-agent-design.md`) to write the change into the component source, so it survives a rebuild and lands in git.

`clj-nrepl-eval` never edits files itself — the runtime/source split (Principle 7) keeps the audit trail honest. A runtime that has hot-patches applied must report that fact (a "runtime drift" marker), so nobody mistakes a live patch for a committed fix.

### 7.4 Self-extending

Because tools live in a plain atom, the agent can register new capability mid-session: `defcommand` a new tool, `require` a freshly written namespace, or wire a new MCP-backed tool — and it is immediately callable through the same registry the sandbox auto-binds from. This is the runtime-mutation complement to `update-agent`'s source-level extension: prototype a tool live, validate it, then promote it to a real component. Newly registered tools are themselves subject to the tool-use hooks and permission checks, so self-extension does not bypass the existing guardrails.

---

## 8. Safety & Trust Model

`clj-nrepl-eval` deliberately removes the SCI wall. The running JVM has filesystem, network, process, and credential access (Datomic, AWS, Slack, Keycloak, …). Arbitrary code in that image can exfiltrate secrets, corrupt state, or brick the process. This section is therefore load-bearing, not advisory. The boundary is defended in depth — config, grant, policy, audit, kill-switch — so that no single failure opens the door.

### 8.1 Threat model

- **Prompt injection / hijacked task.** Untrusted content (a web page, an MCP payload, a malicious file) steers the agent into evaluating hostile Clojure against the live runtime. *This is the primary threat* — the LLM is the confused deputy.
- **Self-inflicted damage.** A well-intentioned fix halts a live Integrant component, redefines a core var incorrectly, or wedges the process.
- **Lateral movement.** Code in the live image uses brainyard's own credentials/connections to reach external systems.
- **Exposed server.** An nREPL port reachable off-host is unauthenticated remote code execution.

### 8.2 Controls (defense in depth)

1. **Off by default; loopback only.** The server is absent unless config enables it (§5.2) and binds `127.0.0.1` only. Non-loopback binds require an explicit, separate config flag and are out of scope for the default design. The ephemeral port is written to a `0600` file under `.brainyard/`.
2. **Runtime grant with TTL.** Even with the server up, `:backend :nrepl` is denied unless an active grant exists. A grant is a deliberate act (a `/grant-runtime` TUI command, an env flag, or a human confirmation prompt), is **time-boxed** (e.g. 15 min), names a scope (read-only vs mutate), and is revocable instantly. Grants do not persist across process restarts.
3. **Policy: read-only vs mutate.** ⚠️ **SUPERSEDED (2026-06).** The original design rejected mutating top-level forms under a `:read-only` grant via a form classifier. This scope-based code gate was **removed**: static syntactic classification cannot soundly isolate a live nREPL (it is bypassable by nesting/qualifying, and harmless `user`-ns scratch defs don't touch application namespaces anyway). nREPL is now the **full-trust** backend — a grant means full live-image access — and **isolation is delegated to the SCI sandbox backend** (`:clj-backend :sandbox`), the sound controlled-bindings interpreter. The grant `:scope` is retained as **advisory metadata** only. Mutation is bounded procedurally by the deny-list (#4), first-mutation confirmation (#5), audit (#6), and drift markers — not by classification. The classifier survives solely as the confirmation trigger + drift heuristic.
4. **Allow/deny on the eval path.** A configurable deny-list blocks the genuinely catastrophic regardless of scope (`System/exit`, `Runtime/.exec`, `shutdown-agents`, credential-bearing namespaces). The classifier is best-effort defense, not a sandbox — it raises the cost of an accident, and is paired with the controls below rather than relied on alone.
5. **Confirmation for mutation.** With computer-use/permission prompts available, the first mutating eval in a session (and any touching a sensitive namespace) requires explicit human confirmation, reusing the agent permission machinery that already gates write-side MCP tools.
6. **Full audit.** Every `:nrepl` eval emits a `mulog` event: code, session, caller agent, granted scope, result/exception, duration. These are first-class in observability and intended to be reviewable after the fact. Audit is not optional and cannot be disabled by the agent.
7. **Kill-switch.** A single command halts the Integrant key, closes all sessions, and revokes outstanding grants — the panic button when a session goes wrong. Interrupting an in-flight eval uses nREPL's `interrupt` op; a hung eval falls back to the task manager's `:kill` on-timeout policy.
8. **Trusted-context gating.** Tie grant availability to `env-detect` signals: enabled in interactive local/dev sessions, hard-disabled in unattended/automated/CI/production runs unless an operator explicitly overrides. The riskier the context (no human in the loop), the higher the bar.
9. **Native-image is closed by construction.** The `by` binary cannot meaningfully host live redefinition, so the largest-attack-surface capability is simply unavailable in the distributed artifact — a useful default-deny for production deployments.

### 8.3 Posture by environment

| Environment | nREPL server | Default grant | Mutation |
|---|---|---|---|
| Local dev (JVM, interactive) | enabled | read-only, on request | with confirmation |
| Local dev, operator working an incident | enabled | mutate, time-boxed | with confirmation |
| Unattended / scheduled / CI | disabled | none | denied |
| Production service (JVM) | disabled unless ops opt-in | none | denied |
| Native `by` binary | unavailable | n/a | n/a |

The throughline: **the sandbox backend is always available and always safe; the nREPL backend is a privileged tool that must be deliberately, narrowly, and temporarily granted, and is loud about everything it does.**

---

## 9. Integration Points

- **Tool registry.** One `defcommand code$eval` registered in `!tool-defs`; the `:nrepl` arm reuses `call-tool`'s existing permission/visibility hooks rather than inventing a parallel gate.
- **Task manager.** New job-type `:clj-nrepl-eval` with a `NreplEvalJobExecutor` implementing `IJobExecutor`, symmetric to `ClojureSandboxJobExecutor` — detach-capable, `:on-poll` / `:on-cancel`, dual-deadline timeout, so long or hung live evals never block the agent loop.
- **Hooks.** Reuse `:agent.code-eval/pre` / `:agent.code-eval/post`, adding `:backend` to the payload so the audit shim and observability can distinguish sandbox from live-runtime evals.
- **CoAct.** `coact-code-eval-action` learns to read a backend hint from the fence (` ```clojure :nrepl `) or default to `:sandbox`. No new BT.
- **eval-agent / update-agent.** eval-agent drives the debug loop (§7.2); update-agent promotes validated fixes to source (§7.3). clj-nrepl-eval is the shared primitive both lean on.

---

## 10. Phased Rollout

1. **Phase 1 — Server + self-observe (read-only).** ✅ Shipped. `components/clj-nrepl` (server + client + session + grant + classifier + audit), Integrant key, `code$eval` with `:sandbox` (default) and `:nrepl` (gated) backends, `NreplEvalJobExecutor` (`:clj-nrepl-eval` job type) for detach-capable lifecycle, opt-in bootstrap in both bases gated by `BY_NREPL_ENABLED`. Grant from `BY_NREPL_GRANT=read-only:15m`. (The Phase-1 ` ```clojure :nrepl ` per-fence routing was removed in a later cleanup — see the §14 note. Backend selection lives entirely on the per-agent `:clj-backend` config.)
2. **Phase 2 — Debug loop with mutation grant.** ✅ Shipped as two halves:
   - **2a** — `:mutate` grant scope, host-injectable confirmation fn (first mutating eval per session), runtime-drift marker. Deny-list moved out of the classifier into its own gate that applies regardless of scope.
   - **2b** — `debug-agent` defagent (CoAct-derived specialist) with per-instance pinned nREPL session, `:clj-backend :nrepl`, custom `:execution-model` system-prompt section. See `docs/design/debug-agent-design.md`. *(Note: not the eval-agent — that's a different agent for plan/todo/exec verdict production. A new specialist was created.)*
3. **Phase 3 — Promotion hand-off.** ✅ Shipped. `debug$promote-hot-patch` defcommand writes a pattern-mode promotion artifact under `.brainyard/agents/debug-agent/promotions/<ts>-<slug>.md` with the literal `bb tui ask "@<path>" -a update-agent` command in `:next-step`. update-agent's existing `:agent-context` reader consumes it via its probe→apply→verify pipeline — zero update-agent code changes. Syntax-aware promotion mode and §7.4 first-class self-extension are deferred.
4. **Phase 4 — Hardening.** ⏳ Deferred. env-detect-driven posture table (§8.3), kill-switch UX, audit dashboards in observability, prompt-injection red-team soak. Land when concrete needs surface.

---

## 11. Non-Goals

- **Not a sandbox replacement.** SCI stays the default and the only backend for RLM/context work. `clj-nrepl-eval` is additive.
- **Not remote/multi-tenant nREPL.** Loopback, single-image, single-operator. No off-host exposure, no auth protocol design here.
- **Not automatic source-writing.** clj-nrepl-eval mutates the *runtime*; turning that into a *committed change* is update-agent's job, behind its own review.
- **Not available in the native `by` binary.** JVM-mode capability only.
- **Not a bypass of existing guardrails.** Tools registered live, and code evaluated live, remain subject to tool-use hooks, permissions, and audit.

---

## 12. Open Questions

1. **Mutation classifier strictness.** Phase 1–2 shipped the soft variant (per-form classifier + audit + confirmation). The stronger op-allowlist variant remains open as a Phase 4 hardening option if prompt-injection soak reveals abuse.
2. **Grant UX.** ✅ Resolved in Phase 1: env-var bootstrap (`BY_NREPL_GRANT=<scope>[:<ttl>]`) shipped as the operator entry point. TUI slash-command and inline permission-prompt variants remain open follow-ups; both can be added without breaking the env path.
3. **Runtime-drift surfacing.** ✅ Resolved in Phase 2a': bold-yellow `drifted (N)` chip on the TUI status bar (between tasks/queue and the calls counter). Stays visible until `clj-nrepl/drift-clear!` or process restart. Banner-on-resume / refuse-to-persist variants remain open if drift becomes common enough to warrant louder warnings.
4. **Shared image with human CIDER.** Still open. The Phase 2b per-instance pinning means a debug-agent always opens its OWN server-issued session, so a developer attached via CIDER and the agent never share namespace state. Whether to *expose* the CIDER user's session to the agent (or vice versa) for collaborative debugging is unexplored.
5. **Phase 3 syntax-aware promotion.** Pattern-mode shipped covers the single-`defn`/`def` swap case. Cross-line whole-form rewrites need rewrite-clj plumbing on the artifact side; trigger is real demand.
6. **Two-way drift-marker reconciliation after promotion.** When update-agent finishes its `Saved edit:`, should the original drift marker flip to `:reason :promoted` linking the update-agent record path? Needs a cross-process update path since debug-agent's process may not be the one running update-agent.
7. **TUI confirm-fn install.** `clj-nrepl/set-confirm-fn!` is exposed but no base installs one yet — default-allow with audit warning. Wiring `bases/agent-tui` (and `agent-web`) to use the existing `permissions/make-permission-fn` is a small independent follow-on commit.

---

## 13. Related Reading

- `docs/CoAct.md` — the loop that emits code blocks.
- `docs/rlm-agent-design.md` — the sandbox/RLM eval path this design sits beside.
- `docs/design/debug-agent-design.md` — **the CoAct-derived specialist that drives the §7.2 self-debug loop and §7.3 promotion hand-off.** *(Note: the original §7.2 referenced `eval-agent` as the natural driver. In practice eval-agent is a different agent for plan/todo/exec verdict production; debug-agent is the new specialist for this workflow.)*
- `docs/design/update-agent-design.md` — promotion of runtime fixes to committed source (§7.3). §12 (Handoff Mechanics) describes the `Saved edit:` / `Rollback:` artifact shape that debug-agent's promotion artifact intentionally mirrors.
- `docs/design/observability.md` — where `:nrepl` eval audit events surface.
- `docs/core/task.md` — the task manager + `NreplEvalJobExecutor` job type that backs the `:nrepl` arm.
- `docs/build-and-deploy.md` — native-image constraints behind Principle 8 / Non-Goal §11.
- Source: `components/clj-nrepl/src/ai/brainyard/clj_nrepl/interface.clj`, `components/agent/src/ai/brainyard/agent/common/code_eval.clj`, `components/agent/src/ai/brainyard/agent/common/debug_agent.clj`, `components/agent/src/ai/brainyard/agent/common/coact_agent.clj` (backend routing + `:execution-model` plumbing), `components/agent/src/ai/brainyard/agent/task/executor.clj` (`NreplEvalJobExecutor`), `bases/agent-tui/src/ai/brainyard/agent_tui/core.clj` (opt-in server bootstrap), `bases/agent-tui/src/ai/brainyard/agent_tui/layout.clj` (drift chip).

---

## 14. Implementation Notes (May 2026)

Differences from the original revision-1 proposal worth flagging for readers comparing the design to the code:

- **`code$eval` lives in `components/agent/.../common/`, not `core/tool`.** It's a `defcommand` that registers a tool, not core infrastructure — alongside `coact_agent` and the sandbox bindings. The original proposal placed it in `core/tool` for proximity to `defcommand`/`!tool-defs`; the move to `common/` keeps `core/` focused on foundational protocols.
- **The §7.2 driver is `debug-agent`, NOT `eval-agent`.** The original proposal assumed `eval-agent` could be the driver because the name suggests it. In practice `eval-agent` is the plan→todo→exec verdict-production specialist (`docs/design/eval-agent-design.md`) — unrelated. Phase 2b introduced `debug-agent` as the actual specialist; see `docs/design/debug-agent-design.md`.
- **Backend selection is per-agent only — no per-fence override.** The proposal's §4.2 described fence-based selection (` ```clojure :nrepl `). Phase 2b briefly shipped a hybrid where the fence info-arg won and the per-agent `:default-clj-backend` was a fallback. That dual path was removed in a follow-up cleanup: LLMs were emitting `:nrepl` fences from training-data habits and getting unexpected routing. Now `agent-clj-backend` in `coact_agent.clj` reads the per-agent `:clj-backend` config key (schema default `:sandbox`; debug-agent's lifecycle hook overrides to `:nrepl`); the fence accepts only the language token and any trailing text surfaces as `:fence-error` returned to the LLM as an `:error` entry.
- **Drift marks on *attempt*, not on *full success*.** Clojure top-level forms evaluate sequentially; a `(def x 1)` followed by `(broken-thing)` leaves `x` defined despite the block surfacing `:error`. The drift gate therefore marks whenever a mutating block REACHED the server, regardless of whether all forms succeeded. Caught by a real-LLM test where the agent emitted SCI-style helpers (`pprint`, bare tool-name-as-fn) in a block whose first form was a successful `def`; the marker was being suppressed.
- **System prompt's "## Execution Model" section is per-agent overridable.** CoAct's hardcoded "sandboxed Clojure interpreter (SCI)" text is wrong for debug-agent. Phase 2b parameterized `:execution-model` in `coact-system-context`; debug-agent's `:agent.instance/created` hook writes an nREPL-aware override. Any future specialist routing to a non-SCI backend can use the same mechanism.
- **TUI confirm-fn wire is not installed yet.** `clj-nrepl/set-confirm-fn!` is exposed; no base calls it. Default-allow with audit warning is the current behaviour — production hosts should install a real fn (e.g. reusing `permissions/make-permission-fn` in `bases/agent-tui`).
- **Bootstrap knobs live in `agent.core.config/config-schema`, not Aero/Integrant.** §5.2's original `:ai.brainyard.clj-nrepl/server` Integrant key was never built; instead `:nrepl-enabled?` / `:nrepl-port` / `:nrepl-grant` are first-class schema entries (`[:agent :config :*]` in the persisted EDN). Both bases read them via `agent/get-config`; env vars (`BY_NREPL_*`) survive as the schema `:default-fn` layer. Operators get durable opt-in through `~/.brainyard/config.edn` and transactional edits through config-agent's `config$apply`, with the existing schema-leaf validator type-checking writes.

**Validated end-to-end against real Bedrock haiku.** Each phase has a captured tmux session in the commit history:
- Phase 1 routing: `(System/getProperty "java.version")` returned `"25.0.3"` via `:clj-nrepl-eval` (SCI denies this call).
- Phase 2a/2b mutate + drift: `(def my-probe 99) my-probe` → `99`, drift-count 1, agent's pinned session id on the marker.
- Phase 3 promotion: `debug$promote-hot-patch` tool call emitted artifact with all 8 frontmatter fields + stable-prefix tail lines.
- Drift chip: status bar transitioned from `idle │ 0 calls …` to `idle │ drifted (1) │ 3 calls …` after the mutation turn.

**Test surface on the branch:** 62 deftests / 217 assertions across `components/clj-nrepl/test/` + `components/agent/test/` (debug-agent + step-F) + `bases/agent-tui/test/` (status-bar chip). All green.
