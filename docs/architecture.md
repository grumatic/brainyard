# Architecture

`agent-tui-app` is a Polylith project that composes a TUI **base** with
the `agent` component and its supporting components into a single
deployable: the `by` binary.

---

## Polylith at a glance

Brainyard uses the [Polylith](https://polylith.gitbook.io/) monorepo
architecture. Three building blocks matter here:

- **components** live under `components/` and encapsulate shared
  functionality behind an `interface` namespace. Callers depend on the
  interface, never on implementation modules. Brainyard's top namespace
  is `ai.brainyard`, so an `agent` component's public API is
  `ai.brainyard.agent.interface`.
- **bases** live under `bases/` and provide runnable entry points
  (CLIs, servers, TUIs). A base is the thinnest glue between the
  outside world and the components.
- **projects** live under `projects/` and aggregate one base plus a set
  of components into a deployable artifact. They own the `deps.edn`
  that decides which versions and alias sets ship together.

This public repo is a **curated subset** of the upstream Brainyard workspace —
only the bricks needed to build `agent-tui-app` plus the ACP-stub harness are
checked in. The workspace config at `workspace.edn` registers two projects with
short aliases:

| Project | Alias | Notes |
|---|---|---|
| `agent-tui-app` | `ata` | The flagship `by` binary. `:necessary ["analytics" "web-share" "os-sandbox"]` — soft deps kept in the build even if not directly referenced from `main`. |
| `acp-stub-agent` | `asa` | In-tree ACP backend for protocol-level testing. |

`bb poly:check` validates inter-brick dependencies; `bb poly:info`
prints the graph.

---

## Composition of `agent-tui-app`

`projects/agent-tui-app/deps.edn` pins exactly which bricks are
reachable from the native binary:

```
projects/agent-tui-app/
└── deps.edn
    ;; Base
    ├── :local/root "../../bases/agent-tui"
    ;; Components (alphabetical)
    ├── :local/root "../../components/agent"
    ├── :local/root "../../components/agent-tui-persist"
    ├── :local/root "../../components/agent-tui-tmux"
    ├── :local/root "../../components/analytics"
    ├── :local/root "../../components/ask-channel"
    ├── :local/root "../../components/behavior-tree"
    ├── :local/root "../../components/clj-http-native"
    ├── :local/root "../../components/clj-llm"
    ├── :local/root "../../components/clj-nrepl"
    ├── :local/root "../../components/clj-oauth"
    ├── :local/root "../../components/clj-sandbox"
    ├── :local/root "../../components/display-block"
    ├── :local/root "../../components/env-detect"
    ├── :local/root "../../components/memory"
    ├── :local/root "../../components/mulog"
    ├── :local/root "../../components/os-sandbox"
    ├── :local/root "../../components/util"
    ├── :local/root "../../components/web-share"
    └── :deps   cli-matic
```

Only the listed components are reachable from the native binary; this
is what keeps the image small relative to the full development REPL.
(`acp` / `acp-client` ship in the `acp-stub-agent` project, not here.)

### Aliases

- `:dev` — includes dev/test source for local iteration.
- `:nrepl` — nREPL + CIDER for IDE connections (`bb repl:ata`).
- `:uberdeps` — uberjar packaging, driven by `bb uberjar:ata`.

### Workspace alias

In `workspace.edn` the project is registered as `:alias "ata"` with
`:necessary ["analytics" "web-share" "os-sandbox"]` — soft dependencies
kept in the build even when not directly referenced from `main`.

---

## The `by` binary: entry point

`projects/agent-tui-app/src/ai/brainyard/agent_tui_app/main.clj` is a
`cli-matic` driver with eight subcommands:

| Subcommand | Purpose |
|---|---|
| `run` *(default)* | Start the interactive TUI session. |
| `ask` | One-shot, non-interactive question (`--attach` targets a running session). |
| `agents` | List registered agents (`get-tool-defs :type :agent`) as a text table. |
| `models` | List known `provider/model` pairs. |
| `config` | Interactive environment-bootstrap wizard (API keys, providers, defaults). |
| `sessions` | `list` / `show` / `config` / `label` / `prune` persisted agent sessions. |
| `memory` | Maintenance + inspection of the user-scoped L1/L2/L3 store and context graph. |
| `events` | Emit user-defined events into a live session over its ask channel. |

`-main` normalises args so that bare flags or a bare agent-id reuse
the `run` path (preserving the legacy `bb tui coact-agent` and
`bb tui -- provider:model` syntaxes).

`run` adds session-management flags on top of provider/model/agent:

| Flag | Meaning |
|---|---|
| `-i / --inline` | Inline mode — no alt-screen (used by CliClient tests). |
| `--with-tmux` | Require a tmux session for side panes and popups; exit 1 if not in tmux. |
| `-r / --resume` | Bare: pick a persisted session to resume from an interactive menu (fresh if none). With an id (`--resume <id>`): resume that session — error + exit 1 if absent. Implies hydration + scrollback replay. |
| `--new` | Deprecated no-op (sessions start fresh by default) — still accepted. |

`main.clj` loads `ai.brainyard.agent.interface`, which `:require`s every
built-in defagent namespace statically (coact, react, main, plan, todo, exec,
eval, edit, explore, research, workflow, skill, mcp, rlm, acp, memory, config,
init, tool, hook, meta, debug) so GraalVM AOT captures them; otherwise they
would never be reachable from `-main`'s transitive class graph and would be
dead-stripped.

### Configuration precedence

For any startable parameter (agent, provider, model):

```
CLI flag  >  config.edn (project or user)  >  hardcoded default
```

`agent/init-dirs!` resolves `.brainyard/` at both scopes; the two files are
**not auto-merged** — `read-edn-config` returns whichever scope is asked
for, and `:auto` prefers project if its `config.edn` exists else user. See
[config-agent-design.md §2A](design/config-agent-design.md) for the full
scope contract and [build-and-deploy.md §Configuration](build-and-deploy.md)
for the user-facing precedence rules.

### `.brainyard/` subdir scope contract

Every `.brainyard/<name>` entry is allowed at one or both scopes per
`agent.core.config/subdir-scope-policy`:

| Path | User (`~/.brainyard/`) | Project (`<repo>/.brainyard/`) |
|---|:-:|:-:|
| `config.edn`, `BRAINYARD.md`, `skills/` | ✓ | ✓ |
| `config-agent/`, `init-agent/` (mirror the file they edit) | ✓ | ✓ |
| `memory/` (SQLite DBs), `sessions/` (TUI state), `logs/` (mulog file publishers + crash dumps) | ✓ | — |
| `charts/` (Plotly HTML exports), `temp/clj-sandbox/` (truncation + file-backed display caches), `temp/coact-agent/scratch/` (code-block + verbatim scratch) | — | ✓ |
| Other `*-agent/` (`explore-agent`, `plan-agent`, `todo-agent`, `workflow-agent`, `research-agent`, `edit-agent`, `eval-agent`, `exec-agent`, …) | — | ✓ |

Use `(brainyard-subdir dirs name scope)` to resolve a path that honors
this policy; it returns nil for forbidden combos. Use `brainyard-subdir!`
to resolve AND create the directory on demand.

**Migration from `/tmp/`**: as of mid-2026, application logs and project
artifacts that historically lived in `/tmp/` were relocated under
`.brainyard/`. The legacy `/tmp/` paths remain as **fallback** when the
scoped dir can't be created (rare — e.g. no `user.home`, native-image
quirks). Concretely:

| Legacy `/tmp/` path | New canonical path |
|---|---|
| `/tmp/agent-tui-app.log` | `~/.brainyard/logs/agent-tui-app.log` |
| `/tmp/agent-web-app.log` | `~/.brainyard/logs/agent-web-app.log` |
| `/tmp/by-crash.log`, `/tmp/by-input-crash.log` | `~/.brainyard/logs/by-*.log` |
| `/tmp/chart-<ts>.html` | `<project>/.brainyard/charts/chart-<ts>.html` |
| `/tmp/coact-<ts>-<rand>.{sh,py,js}` | `<project>/.brainyard/temp/coact-agent/scratch/coact-*` |
| `/tmp/<working-dir>/...` (sandbox truncation cache) | `<project>/.brainyard/temp/clj-sandbox/truncation/<class>` |
| `/tmp/<working-dir>/...` (file-backed display blocks) | `<project>/.brainyard/temp/clj-sandbox/file-backed/<class>` |

**Project-dir resolution**: `BY_PROJECT_DIR` env → nearest `.git`
ancestor of working-dir → **working-dir itself** (fallback). The
working-dir fallback means `*-agent/` artifact dirs always have a sane
location, even outside a git repo (they land under `<cwd>/.brainyard/`).

---

## Layer diagram

```
┌───────────────────────────────────────────────────────────────────────┐
│ by (GraalVM native binary, ~0.5 s startup, ~115 MB arm64)             │
│                                                                       │
│ ┌──────────────────────── bases/agent-tui ────────────────────────┐   │
│ │ core.clj  session.clj  sessions.clj  layout.clj  terminal.clj   │   │
│ │ input.clj autocomplete.clj commands.clj permissions.clj         │   │
│ │ side_pane_commands.clj tmux_side.clj iteration_sink.clj         │   │
│ │ output_sink.clj persist_bridge.clj display_block_ui.clj …       │   │
│ └────────────┬────────────────────────────────────────────────────┘   │
│              │                                                         │
│              ▼  ai.brainyard.agent.interface                           │
│ ┌──────────────────────── components/agent ───────────────────────┐   │
│ │ core/     — Agent record, protocols, BT, runtime, hooks, tool   │   │
│ │             registry, memory recall/remember, context, config,  │   │
│ │             session, queue                                       │   │
│ │ common/   — every defagent (coact, react, main, plan, todo,    │   │
│ │             exec, eval, edit, explore, research, workflow,      │   │
│ │             skill, mcp, rlm, acp, memory, config, init, tool,   │   │
│ │             hook, meta, debug) + DSPy signatures + sandbox      │   │
│ │             bindings + compaction + trajectory + evaluation     │   │
│ │ mcp/      — MCP client & integration, tool registration         │   │
│ │ stdio/    — stdio JSON-RPC adapters                             │   │
│ │ task/     — TaskManager, IJobExecutor, bash/tool/cli-client jobs│   │
│ │ tui/      — TUI-side helpers shared with the base               │   │
│ └──┬─────────┬─────────────┬──────────┬─────────┬────────────────┘   │
│    │         │             │          │         │                     │
│    ▼         ▼             ▼          ▼         ▼                     │
│ clj-llm  clj-sandbox  behavior-tree memory   analytics                │
│ (DSPy,    (SCI runner, (ticks,       (L1/L2/L3 (PQS,                  │
│  multi-    code-block   extended     + system   waste,                │
│  provider) parser,      nodes,       FTS5,      cost)                 │
│            truncation,  dspy-action) capture,                         │
│            conversation              audit)                           │
│            window)                                                    │
│                                                                       │
│ display-block  env-detect  mulog  util                                │
│ (UI block    (LLM/exec    (struct-                                    │
│  rendering)   /env detect)  ured logs)                                │
│                                                                       │
│ agent-tui-tmux  agent-tui-persist                                     │
│ (Tmux protocol, (<project>/.brainyard/sessions/<id>/                          │
│  RealTmux/Stub,  EDN I/O, lock, eviction,                             │
│  popups)         scrollback, snapshots)                               │
└───────────────────────────────────────────────────────────────────────┘
```

The key observation: **the `agent-tui` base is thin**. It renders,
reads input, and forwards slash commands. All reasoning lives in
`components/agent`, and all LLM / sandbox / memory / logging concerns
live in their own components.

---

## Where ReAct and CoAct live

Both are registered agents in the same tool registry (`!tool-defs`):

- **ReAct** — `components/agent/src/ai/brainyard/agent/common/react_agent.clj`
  registers `react-agent` as a `defagent`. Its BT is built from DSPy
  action nodes (`ThinkAndSelectTools`, `ObserveAndEvaluate`,
  `ThinkActAndEvaluate`, `FinalizeAnswer`) plus `tool-calls-action`.
  Loop mode is controlled by `:react-loop-mode "single" | "multi"`
  (default `"single"`).
- **CoAct** — `components/agent/src/ai/brainyard/agent/common/coact_agent.clj`
  registers `coact-agent` (the default). One DSPy signature
  (`ThinkActCode`) with six inputs and four outputs: a single LLM call
  per iteration emits *thought* plus exactly one of three action
  channels (`tool-calls`, `code-blocks`, or `answer`). The loop
  terminates when `answer` is non-blank. Two stable-key inputs
  (`:system-context`, `:user-context`) ride the system message for
  provider prompt-cache reuse.

See [core/reasoning.md](core/reasoning.md) for the full mechanics of
both styles and the SCI sandbox they share.

---

## Hooks & memory capture

`components/agent/src/ai/brainyard/agent/core/hooks.clj` is the single
subscription point for runtime events. The catalog covers session,
instance, ask, iteration, DSPy-action (with streaming `/chunk` events),
tool-calls, tool-use (gated), code-eval, compaction, evaluation,
analytics, task, todo, and exception events. See
[core/agent.md §Hooks](core/agent.md).

`components/memory` subscribes to lifecycle events via
`requiring-resolve` (no `agent → memory` cycle) and runs a sidecar
capture pipeline that writes episodes to L2 and consolidates to L3 facts.
See [core/memory.md](core/memory.md).

---

## Build path: Clojure → GraalVM native

```
bb compile:ata    (AOT-compile ai.brainyard.agent-tui-app.main → classes/)
    │
    ▼
bb uberjar:ata    (uberdeps → target/agent-tui-app.jar, ~38 MB)
    │
    ▼
bb native:ata     (native-image → target/by, ~115 MB arm64)
    │
    ▼
bb build:ata      (runs all three in sequence)
    │
    ▼
bb install:ata    (cp target/by /usr/local/bin/by)
```

Native-image configuration lives alongside the source at
`projects/agent-tui-app/resources/META-INF/native-image/ai.brainyard/agent-tui-app/`:

- `native-image.properties` — build flags. Default
  `--initialize-at-build-time` with `--initialize-at-run-time=org.sqlite.JDBC,org.apache.http.impl.auth.NTLMEngineImpl`.
  Passes `--no-fallback`, `--report-unsupported-elements-at-runtime`,
  `-march=compatibility`, `-H:+ReportExceptionStackTraces`,
  `-H:+AllowDeprecatedInitializeAllClassesAtBuildTime`.
- `reflect-config.json` — reflection registrations (~2800 lines: all
  agent namespaces, sandbox, memory, BT, JDBC, SLF4J, …).
- `resource-config.json` — bundled `.clj` sources, class files,
  `META-INF` service providers (~1300 lines).
- `reachability-metadata.json` — dynamic-proxy registrations under `reflection` (replaced deprecated `proxy-config.json` on GraalVM 25).

See [build-and-deploy.md](build-and-deploy.md) for the full build
playbook.

---

## Dev vs. prod entry points

The same code drives both:

- **Dev**: `bb repl:ata` opens an nREPL with `:dev`+`:nrepl` aliases.
  `bases/agent-tui/src-dev/` adds overrides for REPL workflows
  (namespace reloads, handy vars).
- **Prod**: `by` binary drives `-main` under GraalVM. The only
  meaningful differences are that `System/exit` is called on error
  (dev surfaces exceptions to the REPL), and some classes initialize
  at run time rather than build time (JDBC driver, NTLM, logging
  providers).

---

## ACP — Agentic Context Protocol

The `acp-agent` defagent forwards each user prompt to an external ACP
backend over JSON-RPC; it owns no LLM iteration loop of its own. The
TUI hooks render the streamed response, plans, and tool calls just
like a native agent. Default backend is `:stub` (the in-tree
`acp-stub-agent`).

- `bb acp-stub:run` — drive the protocol manually from a REPL.
- `bb tui:acp` — run the TUI with `acp-agent` (stub backend).
- `bases/acp-stub-agent` + `projects/acp-stub-agent` — the stub
  implementation.
- `components/acp` + `components/acp-client` — protocol + client
  plumbing.

See `docs/design/acp-design.md` for the design notes.

---

## Agent TUI

`bases/agent-tui` is a single-process renderer that paints the user's
terminal directly. When `$TMUX` is set (Mode B), it additionally fans
the activity / log streams onto tmux side panes via per-pane FIFOs
and upgrades permission dialogs to `tmux display-popup`
questionnaires. When `$TMUX` is empty (Mode A), the side panes are
rendered inline as sticky live blocks.

Three bricks cooperate:

- `bases/agent-tui` — the renderer itself; `mode.clj` decides
  A / B / C from `$TMUX` + `$PATH` + `--with-tmux`; `tmux_side.clj`
  drives the optional tmux side-channel.
- `components/agent-tui-tmux` — Tmux protocol + `RealTmux` (shells
  out) / `StubTmux` (test recorder), questionnaire popup primitive,
  sinks for the per-pane FIFOs.
- `components/agent-tui-persist` — per-session store at
  `<project>/.brainyard/sessions/<agent-session-id>/` (EDN I/O, lock,
  eviction, scrollback, snapshots).

An earlier two-process design (`by-host` daemon + `by-ui`
orchestrator over a control socket) was retired in May 2026.
`bases/agent-tui-ui` is gone; the renderer talks to tmux in-process
through the same `Tmux` protocol.

See `docs/tui/` for the renderer's architecture, file map, and a
live-binary debugging guide.

---

## Where to look next

- Agent lifecycle, sessions, registry — [core/agent.md](core/agent.md).
- Behavior tree internals — [core/bt.md](core/bt.md).
- Reasoning styles + sandbox — [core/reasoning.md](core/reasoning.md).
- Tool system + MCP — [core/tool.md](core/tool.md).
- Memory architecture — [core/memory.md](core/memory.md).
- Task manager — [core/task.md](core/task.md).
- Build playbook — [build-and-deploy.md](build-and-deploy.md).
- Project-wide conventions — `CLAUDE.md` at the repo root.
