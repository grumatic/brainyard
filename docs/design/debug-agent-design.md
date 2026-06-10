# Debug-Agent — Live-Runtime Self-Debugging Specialist (CoAct-derived)

> **Status:** Implemented (Phase 2b–3 of `docs/design/clj-nrepl-eval.md`).
> **Scope:** `components/agent/src/ai/brainyard/agent/common/debug_agent.clj` — a CoAct-derived specialist agent that drives the live-runtime self-debugging loop introduced by `clj-nrepl-eval`.
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`, `ai.brainyard.clj-nrepl.interface` (loopback nREPL + grant + drift), `ai.brainyard.agent.common.code-eval` (the unified `code$eval` surface), and the per-instance config layer in `ai.brainyard.agent.core.config`.
> **Sibling of:** `explore-agent`, `exec-agent`, `eval-agent`, `update-agent`, `plan-agent`, `todo-agent`. Strictly NOT a replacement for `eval-agent` — that agent produces pass/fail verdicts over the plan→todo→exec dossier flow; debug-agent investigates the running JVM image.
> **Related reading:** `docs/design/clj-nrepl-eval.md` (the foundational substrate — server, grant, classifier, drift), `docs/design/update-agent-design.md` (the source-promotion hand-off target), `docs/design/exec-agent-design.md` (sibling CoAct-derived specialist for structural reference), `docs/CoAct.md` (the inherited loop discipline).

---

## 1. Motivation

`clj-nrepl-eval` ships the *substrate* — a loopback nREPL server, an `:nrepl` backend on the unified `code$eval` command, grant/scope/confirmation gates, a runtime-drift marker, and a `:clj-nrepl-eval` task-manager job type. That gets the agent *able* to evaluate code in the live JVM. It does not get the agent *good* at the actual workflow of reproducing failures and proposing fixes.

The existing built-in agents are all wrong for this:

- **`coact-agent`** defaults to the SCI sandbox and tells the LLM so in its system prompt. To use it for live debugging the operator would have to remember to write ```clojure :nrepl on every block, and the agent has no concept of a pinned nREPL session, so multi-step investigations lose state between iterations.
- **`eval-agent`** is named confusingly. It scores plan→todo→exec dossiers and writes verdict markdown. It has no notion of running code at all — its "eval" is *evaluation of work*, not *evaluation of expressions*.
- **`exec-agent`** routes per-item todo work to other agents. Wrong shape for "reproduce a wedged Integrant component."

Debug-agent is the specialist that closes the gap. It pins one nREPL session per instance for the lifetime of the agent, defaults every ```clojure block to the live runtime, holds a tight tool bag focused on inspection, and knows how to write a Phase-3 promotion artifact when a hot-patch should land in source.

**Thesis.** A CoAct-derived defagent — sharing CoAct's loop, hooks, and channel discipline — with three small specialisations:

1. **Per-instance nREPL session** opened in the `:agent.instance/created` hook and pinned on the instance's config. Lets `(def reproducer …)` then `(probe reproducer)` work across iterations.
2. **`:clj-backend :nrepl`** on the instance config. CoAct's `agent-clj-backend` reads this through the unified config chain, so every ```clojure fence goes to the live runtime by default. The fence itself takes only the language token — there is no per-block modifier.
3. **Custom `:execution-model` system-prompt section** that replaces the SCI-sandbox boilerplate with text describing live-JVM routing, drift marking, the read-only/mutate gate, and which SCI helpers (`context-get`, bare tool-name-as-fn, autoloaded `clojure.pprint/pprint`) don't exist here.

Plus one new tool, `debug$promote-hot-patch`, that bridges live-runtime mutation → committed source via an artifact hand-off to `update-agent` — never reaching across the boundary itself.

---

## 2. Design Principles

1. **Never edit files.** `clj-nrepl-eval` §11 Non-Goal "Not automatic source-writing." Debug-agent honours this even when the LLM has a fully-validated fix — it writes a promotion-request artifact and lets the operator decide whether to invoke `update-agent`.
2. **One nREPL session per instance, opened at creation.** Multi-turn investigation lives or dies on shared namespace state. Opening lazily on first eval would force the LLM to manage session ids; pinning at instance-creation keeps `:session` plumbing invisible to the LLM.
3. **Default to live, not sandbox.** The instance config writes `:clj-backend :nrepl` so `agent-clj-backend` (in `coact_agent.clj`) routes ```clojure fences to `:clj-nrepl-eval` without the LLM needing any per-block modifier. The fence accepts only the language token; trailing text like ```clojure :sandbox is rejected as a fence error. (To escape into SCI, the operator hands the work to a different agent.)
4. **Tight tool bag.** `code$eval`, `task$detail`/`task$list`/`task$cancel`, `clj-nrepl$drift-markers`, `debug$promote-hot-patch`. No `bash`, no filesystem, no MCP. Operators wanting broader investigations switch to `coact-agent`. Tight bag → focused agent.
5. **Prompt tells the truth.** CoAct's default Execution Model section says "sandboxed Clojure interpreter (SCI)" — flat wrong for this agent. Debug-agent overrides `:execution-model` with text that describes the live nREPL, what's available (`System`, `Runtime`, full reflection, every loaded namespace), and what isn't (SCI shortcuts, autoloaded helpers).
6. **Promotion is an artifact, not a call.** `debug$promote-hot-patch` writes `.brainyard/agents/debug-agent/promotions/<ts>-<slug>.md` and returns the operator-ready `bb tui ask "@<path>" -a update-agent` shell command as `:next-step`. The hand-off respects the runtime/source split — the same operator decides whether to actually promote.
7. **Inherit the substrate's safety.** Grant (read-only / mutate / TTL), classifier (deny-list always-on, mutating-heads only under read-only), confirmation (first mutation per session), drift marker, audit — all live in `clj-nrepl`. Debug-agent does not re-implement them.
8. **CoAct everything else.** The behavior tree, the three channels (tool-calls / code-blocks / answer), the hooks, the iteration discipline — all inherited from `coact/run-coact-derived` via the `:bt-factory` pin pattern used by `explore-agent` and `exec-agent`.

---

## 3. Position in the Agent Stack

```
                       operator (or main-agent)
                                │
                                ▼
                          debug-agent
       (CoAct-derived; pinned nREPL session; :clj-backend :nrepl;
        nREPL-aware system prompt; drift chip on the TUI status bar)
                                │
                                ▼
                         code$eval :backend :nrepl
                                │
                                ▼
                clj-nrepl loopback client + grant/classifier/confirm/drift
                                │
                                ▼
                       LIVE brainyard JVM (in-process)
                                │
   (validated fix) ─── debug$promote-hot-patch ───▶ promotion artifact
                                                   .brainyard/agents/debug-agent/
                                                       promotions/<ts>-<slug>.md
                                                            │
                                                            ▼
                                              `bb tui ask "@<artifact>" -a update-agent`
                                                            │
                                                            ▼
                                                       update-agent
                                  (probe → apply → verify → persist → rollback)
                                                            │
                                                            ▼
                                                       Saved edit: <record>
                                                       Rollback:   <git checkout cmd>
```

Within a single debug-agent turn the loop is CoAct's:

```
debug-agent (iter N)
  → ```clojure block (auto-routed to :nrepl)
     → :clj-nrepl-eval task → loopback client
     → harvest {:result :output :error :ns} → CoAct accumulator
  → next iter sees prior :output / :result through :previous-turns
  → eventually the LLM emits a debug$promote-hot-patch tool call (or just :answer)
```

---

## 4. Per-Instance Lifecycle

### 4.1 The lifecycle hooks

`debug-agent.clj` registers two hooks on `clj-nrepl-eval`'s shared `hooks` registry, both filtered by `(= :debug-agent (proto/defagent-type agent))`:

- `:agent.instance/created` →
  1. Write `:clj-backend :nrepl` to the instance's `:!state :st-memory-init :config`.
  2. Write `:execution-model debug-execution-model` to the same config (custom system-prompt section, see §6).
  3. If `clj-nrepl/running?`, open a server-issued nREPL session via `clj-nrepl/new-session` and write the id to `:nrepl-session-id` on the same config.
  4. Mulog `::debug-agent-session-opened` for audit.
  5. If the server is NOT running, mulog a warning and continue — the first code eval will surface the gate error so the LLM can report it. Eager-open keeps the operator's mental model simple (start the server first, then start the agent).

- `:agent.instance/closed` →
  1. Read `:nrepl-session-id` from the same config slot.
  2. `clj-nrepl/close-session` it; swallow exceptions (the session may already be gone if the server stopped first).

Both writes use direct `swap!` into `:!state @st-memory-init :config` rather than `config/set-config!`, because `set-config!`'s 3-arity ALSO writes the global atom + `.brainyard/config.edn`, which would pollute global config with instance-scoped state. The same pattern is used by `set-allowed-dirs!` in `agent.core.config`.

### 4.2 What CoAct reads

`coact-code-eval-action` (in `coact_agent.clj`) reads the per-instance config when dispatching a clojure block:

- `(config/get-config agent :clj-backend)` → routes the block to `:clj-nrepl-eval` when `:nrepl`.
- `(config/get-config agent :nrepl-session-id)` → flows into the task's job-config as `:session`, which `NreplEvalJobExecutor` forwards to `clj-nrepl/eval-string`.
- The new `:execution-model` config key flows through `CoActAssembler` into `coact-system-context`'s `:execution-model` parameter (defaults to the SCI text).

None of these reads requires debug-agent-specific code in CoAct — they're generic per-agent config overrides. Any future specialist that wants a custom backend/session/prompt can use the same mechanism.

---

## 5. Tool Bag

```clojure
:agent-tools {:tools [:code$eval
                      :task$detail
                      :task$list
                      :task$cancel
                      :clj-nrepl$drift-markers
                      :debug$promote-hot-patch]}
```

The rationale per tool:

| Tool | Role |
|---|---|
| `code$eval` | The only way to reach the live runtime. Default backend is `:nrepl` (per instance config). |
| `task$detail` / `task$list` / `task$cancel` | Long-running probes detach via the task manager; the LLM polls/cancels through these. |
| `clj-nrepl$drift-markers` | A read-only defcommand wrapper exposing `clj-nrepl/drift-markers` so the LLM can ask "what mutations have I applied so far?" without hand-rolling the namespace reach. |
| `debug$promote-hot-patch` | The Phase-3 promotion hand-off — see §7. |

Conspicuously absent: `bash`, `read-file`, `write-file`, `update-file`, MCP tools. Debug-agent does not browse the filesystem or run shell commands — it operates ENTIRELY inside the JVM image via nREPL. Operators wanting to do both ("read this file, then probe its loaded form") switch to `coact-agent` for the multi-surface phase.

---

## 6. System-Prompt Override (`:execution-model`)

CoAct's default `## Execution Model` section starts with "Your clojure code runs in a **sandboxed Clojure interpreter** (SCI)" and lists SCI restrictions ("No interop: System, Runtime, ProcessBuilder, ClassLoader access denied"). For debug-agent that's *flat wrong* — its blocks go to the live JVM where all of those are reachable.

Debug-agent registers a custom `:execution-model` text via the instance-config override mechanism added in `clj-nrepl-eval` §4.2-equivalent CoAct plumbing:

```
## Execution Model
Your ```clojure blocks run against the LIVE brainyard JVM via clj-nrepl —
NOT the SCI sandbox. `System`, `Runtime`, `Thread/getAllStackTraces`, full
reflection, every loaded namespace, and arbitrary interop are all reachable.
- **State persists across iterations**: a server-issued nREPL session is
  pinned per debug-agent instance; `(def …)` / `(alter-var-root …)` survive
  for the duration of this agent's session.
- **Captured output**: `println` / `*out*` / `*err*` are captured and
  returned to you in the next iteration.
- **Errors are non-fatal**: a CompilerException or runtime throw shows up
  as `:error` on the eval entry; session state is preserved.
- **Mutations are recorded**: every successful `def` / `alter-var-root` /
  `require` marks runtime-drift. Inspect via `clj-nrepl$drift-markers`.
- **Mutating-eval gate**: under `:read-only` grant, mutating top-level
  forms are rejected. Under `:mutate` grant, the FIRST mutating eval per
  session triggers an operator confirmation prompt …
- **Deny-list**: `System/exit`, `Runtime/.exec`, credential namespaces
  are rejected regardless of grant scope.
- **NO SCI shortcuts**: `context-get`, `(usage :foo)`, and bare
  tool-name-as-fn shortcuts are SCI-only. From here, call tools via the
  tool-call channel and refer to library functions with full namespace
  qualifiers.
- **Timeout**: 30s per eval round-trip by default.
```

A real-Bedrock validation (see §11) confirmed that with this override in place, the prompt no longer contains "sandboxed Clojure interpreter" and the LLM does not reach for SCI-only helpers like bare `pprint`.

---

## 7. The Promotion Hand-off — `debug$promote-hot-patch`

### 7.1 The boundary

`clj-nrepl-eval` §11 Non-Goal: "Not automatic source-writing. clj-nrepl-eval mutates the *runtime*; turning that into a *committed change* is update-agent's job, behind its own review." Debug-agent honours this — it never edits files. When the LLM has validated a hot-patch and believes the same change should land in source, it calls `debug$promote-hot-patch` which writes a markdown artifact under `.brainyard/agents/debug-agent/promotions/`. The operator (or an orchestrator) then runs the literal `bb tui ask "@<artifact>" -a update-agent` command printed in the tool's `:next-step` output.

### 7.2 The tool

```clojure
(defcommand debug$promote-hot-patch
  "Promote a validated live-runtime hot-patch into a committed source change."
  ...
  :inputs
  {:target-file         [:string  …]   ;; repo-relative path
   :target-symbol       [:string  …]   ;; fully-qualified symbol
   :pattern             [:string  …]   ;; current source snippet (verbatim)
   :replacement         [:string  …]   ;; replacement snippet
   :rationale           [:string  …]   ;; why the change is correct
   :validation-evidence [:string  …]   ;; probe outputs proving the fix
   :drift-index         [:int     …]}  ;; default = latest marker
  :outputs
  {:path      [:string  …]
   :status    [:keyword …]             ;; :proposed | :promoted | :rejected
   :next-step [:string  …]             ;; literal `bb tui ask` command
   :error     [:string  …]})           ;; present on refusal
```

Refusal cases (structured `:error`, no artifact written):
- No drift markers recorded yet → "apply + validate the hot-patch via `code$eval :backend :nrepl` first".
- `:drift-index` supplied but out of range → "out of range — current count: N".

### 7.3 The artifact

`.brainyard/agents/debug-agent/promotions/<ts>-<slug>.md`:

```
---
created-at: 2026-05-22T12:35:37+09:00
debug-session: agt-1779420918058-6618
nrepl-session: a7287a81-d1c9-4a34-be6e-741e8a617ace
drift-marker-index: 0
target-file: components/.../foo.clj
target-symbol: ai.brainyard.foo/bar
edit-mode: pattern
status: proposed
---

## What changed in the live runtime
Drift marker recorded at <ts>:
```clojure
<drift-marker code preview>
```

## Validation evidence
<probe outputs the LLM supplied>

## Proposed source change (pattern mode)
Apply via `update-file` with `:pattern` / `:replacement`.

### Current (pattern)
```clojure
<verbatim current source>
```

### Replacement
```clojure
<replacement>
```

## Notes for update-agent
<LLM's rationale>

Saved hot-patch: .brainyard/agents/debug-agent/promotions/<ts>-<slug>.md
Promotion request: bb tui ask "@.brainyard/agents/debug-agent/promotions/<ts>-<slug>.md" -a update-agent
```

The frontmatter and stable-prefix tail lines (`Saved hot-patch:` / `Promotion request:`) mirror update-agent's `Saved edit:` / `Rollback:` pattern — downstream agents can grep cheaply without parsing JSON.

### 7.4 What update-agent does with it

Zero update-agent code changes. Its existing `:agent-context` reader accepts an artifact path and reads the file. The artifact's `## Proposed source change` section is a literal `:pattern` / `:replacement` pair that update-agent's pattern-mode pipeline applies directly via `update-file`. Pre-flight (count matches, context inspection) is update-agent's responsibility; debug-agent just authors the pattern. A wrong pattern surfaces as a clean update-agent refusal, not a silent miswrite.

### 7.5 Phase-3 scope

**Implemented:** pattern-mode promotion. Most live hot-patches are `(alter-var-root #'fn (constantly new-impl))` or `(def x new-val)` which translate cleanly to a literal pattern swap of the corresponding `defn` / `def` form.

**Deferred:** syntax-aware mode (whole-form rewrites, paren-balanced rewrites). Needs more rewrite-clj-ish work and isn't critical for the common-case hot-patch promotion.

**Deferred:** auto-invoking update-agent from debug-agent. Operator-driven on purpose (§11 Non-Goal) — the operator's `bb tui ask` is the explicit "yes, promote this" act.

**Deferred:** drift-marker reconciliation. After promotion, the marker stays as the audit anchor; linking the marker metadata to the promotion record path is a nice-to-have.

---

## 8. TUI Surface

### 8.1 Drift chip on the status bar

`bases/agent-tui/src/.../layout.clj`'s `format-status` accepts `:drifted?` and `:drift-count` keys. When set, a bold-yellow `drifted (N)` chip renders between the tasks/queue cluster and the calls counter:

```
idle │ drifted (1) │ 2 calls (last 17,641 in, +244 tok) │ 35,433 tokens │ $0.0260
```

The chip lights up the next status-bar refresh after a successful mutating eval (drift marker fires inside `clj-nrepl/eval-string` after the eval reaches the server) and stays until the operator calls `clj-nrepl/drift-clear!` or the process restarts. `session.clj`'s `update-status-bar!` reads `clj-nrepl/drifted?` and `clj-nrepl/drift-count` on every refresh, so the chip behavior is shared across every defagent — not debug-agent-specific.

### 8.2 No special TUI mode

Debug-agent uses the same TUI sessions, the same iteration widgets, the same hooks pipeline as coact-agent. No mode flag, no separate render path. The drift chip is the only visual differentiator.

---

## 9. Safety Inherited from `clj-nrepl-eval`

Debug-agent does NOT re-implement any of the safety machinery. Every gate fires inside `clj-nrepl/eval-string` regardless of which caller invoked it:

1. **Grant required.** `BY_NREPL_GRANT=read-only:15m` (or `mutate:5m`) bootstraps. Without an active grant, every `:nrepl` eval returns `{:error "no clj-nrepl grant active …"}` — including those emitted by debug-agent. Operators kicking off a debug-agent session without first granting will see the gate error on the first code block.
2. **Deny-list always on.** `System/exit`, `Runtime/.exec`, `Runtime/getRuntime`, `shutdown-agents`, `java.lang.Runtime`, and the credential namespaces (`ai.brainyard.aws-client`, `ai.brainyard.keycloak`) are rejected regardless of grant scope.
3. **Read-only mutating-heads classifier.** Under `:read-only` grant, top-level forms whose head is in the mutating set (`def`, `defn`, `alter-var-root`, `require`, `import`, `eval`, `load-string`, …) are rejected. Under `:mutate` grant, they pass.
4. **First-mutation-per-session confirmation.** Under `:mutate`, the FIRST mutating eval in a given session triggers the host-injected confirm-fn. Subsequent mutations in the same session pass silently — the operator opted into THIS investigation, not into each call. The TUI confirm-fn wire is currently not installed (default-allow with audit warning); installing it is independent host-side work.
5. **Drift marker on attempt, not on success.** Every mutating eval that reaches the server marks drift, even if a later form in the same block errored — Clojure's top-level forms evaluate sequentially and earlier defs aren't unwound by later errors.
6. **mulog audit on every eval.** `::nrepl-eval` (audit shim), `::nrepl-eval-detached` (task executor), `::runtime-drift` (drift marker), `::grant-issued`/`::grant-revoked`, `::mutation-confirm`. All non-optional and not disable-able by the agent.

The agent's instruction body explicitly tells the LLM these gates exist, so it doesn't waste turns "discovering" them.

---

## 10. Output Discipline

### 10.1 `.brainyard/agents/debug-agent/promotions/`

The only persistent artifact debug-agent writes. One file per promotion request, named `<ISO-ish-ts>-<slug>.md` where `<slug>` is derived from `:target-symbol` (sanitized, lowercased, ≤60 chars). Directory is auto-created on first promotion.

Path is resolved from `(config/working-dir *current-agent*)` — falls back to `(System/getProperty "user.dir")` when there's no current-agent context (tests).

### 10.2 No dossier

Unlike `exec-agent` / `eval-agent` / `explore-agent`, debug-agent does NOT write a cumulative per-session dossier. A debug investigation is fundamentally session-scoped (the pinned nREPL session is the unit of work), the artifacts that matter are the *promotion requests* not the *narrative*, and the CoAct turn record + drift markers + mulog audit log are already a sufficient post-hoc trail.

A future enhancement could add `.brainyard/agents/debug-agent/sessions/<sid>.md` that captures `{ :reproducer-code, :hypothesis, :fix-applied, :validation, :promotion-paths }` per session — but it's deferred until a concrete reader (e.g., an operator dashboard) needs it.

---

## 11. Validation

Phase 2b–3 was validated end-to-end against real Bedrock haiku across four scenarios:

1. **Backend routing** (Phase 2b): asked the agent to evaluate `(System/getProperty "java.version")` — a call denied by SCI. Result: `"25.0.3"`, two `::nrepl-eval-detached` events in the audit log, confirming the routing actually went to the live JVM.
2. **Per-instance session pinning** (Phase 2b): the agent reached `System/getProperty` in turn 2 without re-establishing context, proving the pinned session persisted across iterations.
3. **Mutate scope + drift marker** (Phase 2a/2b fix): agent ran `(def my-probe 99) my-probe → 99`, drift-count became 1, marker recorded the agent's pinned session id. A regression test (`mutate-marks-drift-even-when-later-form-errors`) pins the fix where a later-form error must not suppress the marker.
4. **Promotion artifact** (Phase 3): agent ran `(def demo-var 42)`, validated, then tool-called `debug$promote-hot-patch` with explicit args. Artifact appeared at `.brainyard/agents/debug-agent/promotions/2026-05-22T12-35-37-user-demo-var.md` with all 8 frontmatter fields populated, all 5 body sections, and stable-prefix tail lines. Agent rendered the `bb tui ask "@…" -a update-agent` command in its final answer panel.

Unit-test surface: 6 deftests / 19 assertions covering registration, hook firing, config plumbing, and (for `debug$promote-hot-patch`) registration + the three refusal/success paths + artifact shape.

---

## 12. Instruction (System Prompt Body)

The instruction text below replaces what would otherwise be a default CoAct instruction. Combined with the `:execution-model` override (§6), it gives the LLM a coherent picture of the live-runtime workflow.

```
You are debugging the LIVE brainyard JVM image via clj-nrepl. Every
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

Guardrails:
- The FIRST mutating form per session triggers a human confirmation
  prompt; subsequent mutations in the same session pass silently.
- Catastrophic forms (System/exit, Runtime/.exec, credential nses)
  are rejected regardless of grant scope.
- Every successful mutation marks the runtime as drifted from
  source. Use `clj-nrepl$drift-markers` to inspect what you (or
  anything else in this process) has changed. Drift survives only
  until the process restarts.

You do NOT need to add the `:nrepl` info-arg — your code blocks
route to the live runtime by default.

## Promoting a fix to source
You never edit files. When a hot-patch is validated and you believe
the same change should land in source, call `debug$promote-hot-patch`
with:
  :target-file         — repo-relative path to the file
  :target-symbol       — fully-qualified symbol (ai.brainyard.foo/bar)
  :pattern             — the EXACT current source snippet to match
  :replacement         — what should be written in its place
  :rationale           — why this is the right source change
  :validation-evidence — the probe outputs proving the fix works
The tool writes a markdown artifact under
`.brainyard/agents/debug-agent/promotions/` and returns `:next-step` — a
`bb tui ask "@<artifact>" -a update-agent` line the operator runs
to apply the source change. The runtime drift marker stays as the
audit anchor between hot-patch and committed change.

Out of scope for THIS agent: writing the file. update-agent owns
the probe→apply→verify→persist→rollback pipeline.
```

---

## 13. Behavior Tree — Inherited As-Is

```clojure
:bt-factory (fn [{:keys [max-iterations]}]
              (coact/coact-behavior-tree max-iterations))
```

Same shape as `explore-agent` / `exec-agent`. The BT is pinned explicitly so direct-resolution entry points (e.g. `setup-agent-by-id` used by `bb tui ask`) resolve the factory without going through `run-coact-derived`'s opt-merge path.

---

## 14. Files Summary

```
components/agent/src/ai/brainyard/agent/common/debug_agent.clj
components/agent/test/ai/brainyard/agent/common/debug_agent_test.clj

components/agent/src/ai/brainyard/agent/interface.clj               (require list)
components/agent/src/ai/brainyard/agent/common/coact_agent.clj      (agent-clj-backend
                                                                     reader + agent
                                                                     plumbing + :execution-model)
components/agent/src/ai/brainyard/agent/core/hooks.clj              (:backend in
                                                                     :agent.code-eval payload)
bases/agent-tui/src/ai/brainyard/agent_tui/layout.clj               (drift chip)
bases/agent-tui/src/ai/brainyard/agent_tui/session.clj              (drift chip wiring)
bases/agent-tui/test/ai/brainyard/agent_tui/status_bar_test.clj     (drift chip test)

.brainyard/agents/debug-agent/promotions/<ts>-<slug>.md                    (per-promotion artifact)
```

Substrate (touched but not introduced by this design):

```
components/clj-nrepl/                                               (server, grant,
                                                                     classifier, confirm,
                                                                     drift, audit, client)
components/agent/src/ai/brainyard/agent/common/code_eval.clj        (unified code$eval)
components/agent/src/ai/brainyard/agent/task/executor.clj           (NreplEvalJobExecutor)
components/agent/src/ai/brainyard/agent/task/manager.clj            (:clj-nrepl-eval
                                                                     registration)
bases/agent-tui/src/ai/brainyard/agent_tui/core.clj                 (opt-in nREPL
                                                                     server bootstrap)
bases/agent-web/src/ai/brainyard/agent_web/core.clj                 (ditto)
```

---

## 15. Open Questions

1. **Per-session dossier.** Do operators want a cumulative session log under `.brainyard/agents/debug-agent/sessions/<sid>.md` summarising the investigation, OR is the CoAct turn record + drift markers + mulog audit log already enough? Wait for a concrete reader before implementing.
2. **Syntax-aware promotion mode.** Pattern-mode covers the common-case hot-patch (single `defn` / `def` swap). Whole-form rewrites need rewrite-clj plumbing on the artifact side; the trigger is real demand.
3. **Drift-marker reconciliation after promotion.** When update-agent finishes its `Saved edit:`, should the original drift marker flip to `:reason :promoted` (linking to the update-agent record path)? Cleaner audit trail; needs a cross-process update path since debug-agent's process may not be the one running update-agent.
4. **Auto-promotion route.** A future "promote-agent" or a `--auto-promote` flag could read the artifact and pipe it into update-agent without the operator's `bb tui ask` step. Out of Phase 3 by design (operator-decides), but worth revisiting if friction surfaces.
5. **Cross-instance drift state.** `clj-nrepl/drift-markers` is process-global. If multiple debug-agent instances run concurrently (e.g., nested), the marker count and chip aggregate everyone. Probably fine — drift is about the *runtime*, not any particular agent — but worth confirming with multi-agent scenarios.

---

## 16. Related Reading

- `docs/design/clj-nrepl-eval.md` — the substrate. Section §7.2 (self-debugging), §7.3 (self-improving / promotion), §8 (safety) are the most relevant.
- `docs/design/update-agent-design.md` — the promotion target. §12 (Handoff Mechanics) describes the `Saved edit:` / `Rollback:` shape debug-agent's artifact intentionally mirrors.
- `docs/design/exec-agent-design.md` — sibling CoAct-derived specialist, structural template.
- `docs/CoAct.md` — the inherited loop.
- `docs/core/task.md` — the task manager + `NreplEvalJobExecutor` debug-agent dispatches through.
- Source: `components/agent/src/ai/brainyard/agent/common/debug_agent.clj`, `components/clj-nrepl/src/ai/brainyard/clj_nrepl/interface.clj`.
