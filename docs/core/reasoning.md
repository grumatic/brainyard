# Reasoning Styles — ReAct, CoAct, and the Sandbox

The harness ships two first-class reasoning loops registered as
`defagent`s in the same tool registry, plus a sandbox that turns Clojure
(and bash / python / js) source into observable, safe-by-default action.
Both loops are subtrees plugged into the BT scaffolding described in
[bt.md](bt.md); both dispatch tools through the registry described in
[tool.md](tool.md).

Primary files:

- `components/agent/src/ai/brainyard/agent/common/react_agent.clj`
- `components/agent/src/ai/brainyard/agent/common/coact_agent.clj`
- `components/agent/src/ai/brainyard/agent/common/sandbox_bindings.clj`
- `components/clj-sandbox/*` — the SCI evaluator

---

## At a glance

| | ReAct | CoAct (default) |
|---|---|---|
| Action space | Named tool calls with structured args | One of *tool-calls* / *code-blocks* / *answer* per iteration |
| Action format | JSON | JSON (tool channel) or markdown fenced blocks (code channel) |
| Termination | `goal-achieved` boolean | `answer` non-blank |
| Loop mode | Single-call (`ThinkActAndEvaluate`, 1 call / iter) | three-channel single-call loop |
| Composition / `def` state | None | Persistent across iterations (clojure fence in shared SCI sandbox) |
| Raw scripts | Tool arg (verbatim) | `` ```bash `` / `` ```python `` / `` ```javascript `` fence (verbatim, no escaping) |
| Parallel fan-out | Per-call orchestration | `<!-- ParallelBlock -->` marker inside `code-blocks` |
| Best for | Crisp tools, simple args, opaque payloads | Composition, filtering, persistence, parallel sub-queries, raw scripts |

---

## ReAct

Pattern: **Thought → Action → Observation → Evaluation → repeat or finalize**.

Each iteration:

1. **Thought** — reason about state and decide the next step.
2. **Action** — select one or more tools to invoke.
3. **Observation** — capture tool output.
4. **Evaluation** — decide whether the goal is achieved; emit a partial
   answer.

First-class features baked into the loop:

- **TODO list** — `common/todo.clj` extracts and tracks sub-goals across
  iterations; surfaced in the TUI under `:todo`.
- **Iteration trajectory** — every iteration's thought, action,
  observation, and eval is logged for replay and training-data export
  (see [agent.md §Observability](agent.md)).
- **Context compaction** — when conversation or tool-result size
  exceeds configured thresholds, older iterations get summarised
  in-place (see [memory.md](memory.md)).

### Single-mode loop

ReAct runs a single LLM call per iteration. The earlier multi-mode
variant (`ThinkAndSelectTools` → `ObserveAndEvaluate` → `FinalizeAnswer`,
2N+1 calls) was **removed** once M2/M3 lifted the stable context into the
system message — folding observation/synthesis back into one call no
longer cost prompt quality. Single-mode (`ThinkActAndEvaluate`) is now
the only mode (`react_agent.clj`). The `:react-loop-mode` config key
(default `"single"`) survives as a vestige; there is no `:multi` path to
switch to.

### DSPy signature

The loop's only signature is defined with `defsignature` from
`components/clj-llm`, with typed input/output schemas backed by Malli:

```
ThinkActAndEvaluate          ; reasoning + tool selection + observation + eval, one call
  inputs:  question, history, recalled-memory, tools
  outputs: thought, tool-calls, observation, goal-achieved,
           goal-reasoning, todo-list, answer
```

Because outputs are schema-validated, downstream parsing does not rely
on fragile regex or hand-rolled JSON extraction.

### BT shape (sketch)

```clojure
[:sequence {:id :react.sequence/main}
 [:condition :question-exists]
 [:repeat {:max-n         (get-in ctx [:runtime-config :max-iterations])
           :condition-fn  (fn [ctx] (not (goal-achieved? ctx)))}
  [:action :think-act-and-evaluate]]    ;; one LLM call: ThinkActAndEvaluate
 [:action :maintain-conversation]]
```

`FinalizeAnswer` (shared with CoAct, see `common/evaluation.clj`) runs
only when `:enable-finalize-answer true`.

---

## CoAct (the default)

Pattern: **single LLM call per iteration with three output channels**.
The LLM picks one of:

- **tool channel** (`tool-calls` non-empty) — registered tool directly
  satisfies the sub-goal; no post-processing needed; or launch a
  long-running background task.
- **code channel** (`code-blocks` non-blank) — markdown-fenced code
  (`clojure` / `bash` / `python` / `javascript`) for composition,
  persistence, parallel fan-out, or raw scripts.
- **answer channel** (`answer` non-blank) — terminate the loop with a
  final markdown answer (or a clarifying question, or a failure note).

The loop terminates when `answer` is non-blank. There is no
`goal-achieved` boolean.

### `ThinkActCode` signature

Six inputs, four outputs:

```
inputs:
  :system-context     — agent role, sandbox contract, channel routing,
                        code-block format, tool-call format, critical
                        rules, function directory (system message)
  :user-context       — BRAINYARD.md content, conversation history,
                        previous turns, live artifacts (system message)
  :question           — user request (user message)
  :context-briefing   — per-turn briefing: recalled memory, latest tool
                        specs, agent context (user message)
  :recalled-memory    — memory hits (user message)
  :iterations         — full iteration history capped + truncated (user message)

outputs:
  :thought            — reasoning for this iteration
  :tool-calls         — JSON array (tool channel)
  :code-blocks        — markdown-fenced blocks (code channel)
  :answer             — final markdown (terminates loop)
```

The first two inputs are marked `:stable-keys #{:system-context :user-context}`
on the DSPy node so the LLM provider can prompt-cache them across
iterations.

### Channel choice rules

Pick the **tool channel** when a registered tool directly satisfies the
sub-goal, no post-processing is needed, or to launch a background task.

Pick the **code channel** when the step composes results, needs `def`
persistence, fans out into parallel sub-queries, or runs a raw script
with nested quotes / regex backslashes / template literals.

Pick the **answer channel** when done — even on iteration 1 (greetings,
direct-knowledge questions, clarification requests don't need tools or
code).

### Code-block format

`code-blocks` is one markdown string containing one or more fenced
blocks with language tags:

| Fence | Runtime | Escaping | State |
|---|---|---|---|
| `` ```clojure `` / `` ```clj `` | SCI sandbox (shared across iterations) | SCI escaping applies | `def` persists |
| `` ```bash `` / `` ```sh `` | fresh subprocess, /bin/bash | raw — no escaping | stateless |
| `` ```python `` / `` ```py `` | fresh subprocess, `python3` | raw | stateless |
| `` ```javascript `` / `` ```js `` | fresh subprocess, `node` | raw | stateless |

Insert a line `<!-- ParallelBlock -->` anywhere in `code-blocks` to run
ALL fenced blocks concurrently (forked sandbox per clojure block; fresh
process per shell/python/js block). Absent the marker, blocks run
sequentially in source order.

For a mixed pipeline (A sequential → B+C parallel → D), the LLM uses
multiple iterations.

### Router precedence

When the LLM populates multiple output fields, the BT picks one path
using fixed precedence (and logs a mulog warning):

1. **answer** non-blank → loop exits on next `:repeat` check.
2. **code-blocks** non-blank → run blocks, accumulate eval results.
3. **tool-calls** non-empty → dispatch tools, accumulate results.
4. None → repair iteration (synthetic eval-result nudges the next LLM call).

### BT shape (sketch)

```clojure
[:sequence {:id :coact/main}
 [:condition :question-present]
 [:action    :coact/init]                  ;; build sandbox + system/user context
 [:fallback  :loop-guard
  [:repeat   {:max-n max-iterations
              :condition-fn answer-or-terminated?}
   [:sequence
    [:action :inc-iter]
    [:action :rebudget]                    ;; deterministic per-iteration token-budget enforce
    [:fallback :llm-guard
     [:action :think-act-code]             ;; ThinkActCode DSPy call
     [:action :llm-fallback]]              ;; tracks consecutive format/auth errors
    [:fallback :router
     [:sequence :answer-path  …]
     [:sequence :code-path    …]
     [:sequence :tool-path    …]
     [:action   :repair]]
    [:action :accumulate-iteration]]]
  [:action :loop-fallback]]
 [:action :finalize]                       ;; optional FinalizeAnswer polish
 [:action :store-results]                  ;; trajectory + previous-turns
 [:action :maintain-conversation]]
```

### FINAL as an escape hatch

A `clojure` fence can call `(FINAL "answer text")` to terminate the
sandbox and promote the value into `:answer`. Canonical termination is
still the `answer` output field; `FINAL` exists for inline code where
building `answer` in a DSPy signature response is awkward. Parallel
clojure blocks prohibit `FINAL` (inherited from
`eval-code-blocks-parallel` — surfaces as an error entry, not a
termination).

---

## Code-execution backends

A CoAct ` ```clojure ` fence does not always run in the SCI sandbox.
Code-block evaluation is fronted by a single `code$eval` command
(`common/code_eval.clj`) that dispatches to one of two backends:

| Backend | Engine | Interop / reflection | `def` state | Mutation policy |
|---|---|---|---|---|
| `:sandbox` *(default)* | SCI sandbox (`components/clj-sandbox`) | none | persists across iterations | n/a — interpreted, can't escape host |
| `:nrepl` | live brainyard JVM (`components/clj-nrepl`) | full | persists in the live runtime | gated by a `:read-only` / `:mutate` grant + audit & drift markers |

The backend is **fixed per-agent** via the `:clj-backend` config key
(schema default `:sandbox`; `debug-agent`'s lifecycle hook overrides it
to `:nrepl`). There is **no per-fence override** — ` ```clojure :nrepl `
and ` ```clojure :sandbox ` are *fence errors* that surface to the LLM as
`:error` entries, not routing hints; the fence accepts only the language
token. The `:nrepl` backend is gated by `:nrepl-enabled?` /
`:nrepl-grant` (env `BRAINYARD_NREPL_ENABLED` / `BRAINYARD_NREPL_GRANT`);
mutating evals require the `:mutate` grant and are recorded with audit and
drift markers so live hot-patches are traceable.

When an agent runs in **parallel mode**, the nREPL backend is demoted to
the SCI sandbox for the forked blocks (a single shared nREPL session can't
safely fan out), and the LLM is warned that the demotion happened.

The rest of this section describes the default `:sandbox` backend; the
`:nrepl` backend and its reproduce → probe → hypothesize → test workflow
are covered by [debug-agent-design.md](../design/debug-agent-design.md)
and [clj-nrepl-eval.md](../design/clj-nrepl-eval.md).

## The sandbox

CoAct's code channel runs inside an SCI-backed sandbox provided by
`components/clj-sandbox`. SCI gives three properties that matter for an
agent sandbox:

- **Interpreted** — no dynamic bytecode loading. Code the LLM writes
  cannot mutate or escape the host runtime.
- **Whitelisted namespaces & symbols** — the sandbox sees only what we
  pass in via `:bindings`. No `System/exit`, no `eval` of arbitrary
  classes, no reflection by default.
- **Clojure semantics** — immutable data, closures, macros, the full
  core library. The LLM gets the same mental model humans have.

The sandbox is wrapped with agent-specific concerns: message truncation,
char/token budget tracking, structure-aware feedback formatting, and
exception capture.

### `coact-init-action` and the sandbox-side `context`

`coact-init-action` builds the sandbox by:

1. Merging `make-tool-bindings agent` (the canonical helper in
   `agent/common/sandbox_bindings.clj`) — produces a map of
   `symbol → fn` where every fn closes over the live agent and
   dispatches through `tool/call-tool` at invocation time. Tools and
   `tools-fn-map` are resolved per-call, so a skill loaded mid-session
   becomes available without re-creating the sandbox.
2. Calling `(clj-sandbox/create-sandbox :context sandbox-context :bindings bindings)`.

`sandbox-context` is the data exposed to the sandbox's
`context-accessors` (e.g. `context-get`, `context-index`). It is
**distinct** from the LLM-side `:user-context` string that rides the
system message. A typical map:

```clojure
{:question        "the user's current question"
 :conversation    {:summary "turns 1-5 …" :recent [...]}
 :recalled-memory {:results [{:text "…" :score 0.82} …]}
 :previous-runs   [{:question "…" :iterations [...] :depth :full}
                   {:question "…" :answer    "…" :depth :summary}]
 :restored-vars   ["servers" "aws-tools"]   ;; surviving (def ...) symbols
 :tools           [{:id :slack-post :description "…" :inputs {…}} …]}
```

### Bindings catalogue

Bindings the sandbox sees come from the registry plus a small set of
built-in helpers:

```clojure
;; Tool dispatch — every registered tool is reachable as both
;; a symbol binding (auto-named) and via call-tool.
(call-tool :my-tool {:arg "…"})
(list-tools)
(get-tool-info :my-tool)

;; Sub-LLM dispatch (registered as tools, so reachable from either channel)
(llm-query "summarise this" {:model :sonnet :max-tokens 1024})
;; llm-query-batched (concurrent), rlm-query (recursive) when configured

;; Bash + filesystem (permission-gated)
(bash "ls -la")
(read-file "/path/to/file")
(write-file "/path/to/file" content)

;; Explicit termination
(FINAL "the answer")

;; Structure-aware preview
(inspect some-large-value {:max-depth 3 :max-seq-length 20})

;; Help
(help "topic")   ;; truncation, discovery, plans, skills, files, llm-query, clojure, parallel, sci-strings, …
```

### Safety model

The sandbox is **permission-gated**, not sealed:

- **Permission gating** (default): any privileged operation — file
  write, shell, tool call, network — runs through the permission
  layer. A global regex config governs `:approval`, `:deny`, `:allow`.
  Runtime-config `:action-permissions` caches "always yes" / "always no"
  answers so the user is not spammed.
- **Truncation**: results are truncated to a configurable char budget
  (default 16 000 chars, structure-aware) before being fed back to the
  LLM. Over-sized tool results spill to a project-scoped cache at
  `<project>/.brainyard/temp/clj-sandbox/truncation/<class>/<id>.txt` (with a
  `/tmp/<working-dir>/...` fallback when the agent component isn't on
  the classpath) and the returned string carries a head + recovery-notice
  + tail (full content recoverable via `read-file`).
- **Error classification**: fatal errors (auth, rate limit, SCI init
  failure) are distinguished from format errors (parse fail, schema
  mismatch). Two consecutive fatals abort the run so a broken API key
  doesn't silently burn budget.
- **Timeouts and cancellation**: every sandbox call honours the agent's
  cancellation flag. Long-running sub-agent `ask-async` refs that don't
  resolve within 30 s hand off to the [task manager](task.md).
- **Auto-background detach**: every code block (clojure / bash / python /
  javascript) runs as a task in synchronous foreground mode. If it has
  not finished by the agent's `:auto-background-timeout-ms` (default
  30 s), the runner detaches into the background — the task keeps
  running, the eval-entry surfaces `{:status :pending :task-id <id>}`,
  and the iteration loop continues. `coact-inc-iter-action` calls
  `harvest-pending-tasks!` at the start of each subsequent iteration;
  resolved tasks fold back into `:iterations` as a synthesized
  `:channel "code"` record with `:async-completion? true`, so the next
  LLM call sees the value. The LLM can also poll any time with
  `task$detail` (add `:last-n N` for an output tail) or stop a task with
  `task$cancel`. Instead of polling, the LLM can park the whole turn on a
  detached task and be auto-resumed when it terminates via
  `task$wakeup :task-id <id>` (see
  [Blocking vs. parking](task.md#blocking-vs-parking)). Note: SCI tight
  loops (`(loop [] (recur))`) ignore
  Thread.interrupt and will linger until the sandbox is closed — keep
  them off the LLM's toolbox.

What the sandbox **cannot** do by policy: spawn arbitrary JVM
processes, load classes the host didn't ship, write outside configured
directories, make unapproved network calls.

### Running in sandboxed environments

`docs/SANDBOX.md` (in `docs/design/` after consolidation) walks through
running `by` inside OpenShell-style sandboxes with a TLS-terminating
proxy. Gotchas:

- `CLJ_HTTP_INSECURE=true` — Java must accept the proxy's injected cert.
- `~/.m2/settings.xml` — Maven ignores system properties for proxy
  routing; configure explicitly.
- Provider selection (openai, anthropic, claude-code, ollama) all work
  through the proxy.
- No local-inference option — no model weights ship with the binary;
  outbound HTTPS is assumed.

---

## Choosing between ReAct and CoAct

Start with **CoAct** — it is the default `defagent` because the
three-channel surface (tool / code / answer) collapses the
ReAct ↔ tool-arg ↔ post-processing back-and-forth into a single
iteration in most cases.

Use **ReAct** when:

- The task is a clean handoff to one well-specified tool with simple args.
- The tool payload is opaque to the LLM (binary blobs, long scripts,
  user-supplied text) and you specifically want a structured arg slot
  to avoid any code path.
- You want the deterministic 2N+1 (`:multi`) trajectory shape for
  evaluation or fine-tuning data.

Both styles share the same outer harness, sandbox, registry, memory, and
session — switching is a config change, not a rewrite.

---

## File map

| File | Purpose |
|---|---|
| `common/react_agent.clj` | ReAct signatures, BT factory, `tool-calls-action`, defagent |
| `common/coact_agent.clj` | CoAct signature + section strings + BT actions + BT factory + defagent |
| `common/code_eval.clj` | `code$eval` command — dispatches code blocks to the `:sandbox` or `:nrepl` backend |
| `common/sandbox_bindings.clj` | `make-tool-bindings`, `build-context-briefing`, `build-agent-state-snapshot`, helper categorisation |
| `components/clj-nrepl/*` | live-runtime (`:nrepl`) eval backend — grants, audit, drift markers |
| `common/evaluation.clj` | `EvaluateAnswer`, `FinalizeAnswer` signatures shared by both loops |
| `core/context_budget.clj` | Token-budget reducer (`enforce`) — the single live compaction mechanism, run at turn-init + per-iteration |
| `common/context_compaction.clj` | Deterministic cross-turn `/compact` + after-turn auto-compaction (progressive `:previous-turns` shrink, no LLM) |
| `common/trajectory.clj` | Per-iteration record for export / replay |
| `common/previous_turns.clj` | Previous-turn append + recall |
| `components/clj-sandbox/*` | SCI evaluator, prompt builder, code-block parser, truncation, conversation window |
