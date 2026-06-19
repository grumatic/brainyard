# Brainyard Documentation

*A Clojure Polylith monorepo for building autonomous AI agents. The
flagship deliverable is the `by` terminal binary — an agentic harness
built on ReAct and CoAct.*

Top namespace: `ai.brainyard`

---

## What is Brainyard?

Brainyard is an agentic-engineering platform packaged as a Polylith
monorepo. The flagship `agent-tui-app` project ships as a single GraalVM
native binary (`by`) that drives an autonomous LLM agent from your
terminal. Two complementary reasoning styles share the same runtime:

- **ReAct** — *Thought → Action → Observation → Evaluation* using LLM
  tool calls.
- **CoAct** (default) — single LLM call per iteration with three output
  channels: *tool-calls*, *code-blocks* (clojure / bash / python / js
  fenced blocks), or *answer*. Executable code **is** the action space.

Both styles are subtrees of a single **behavior tree (BT)** — the
central execution substrate — whose leaves call typed DSPy signatures,
registered tools, sandbox evaluations, or human-in-the-loop (HITL)
prompts. The BT makes every decision inspectable, replayable, and
cancellable.

Around that core, the harness provides: a layered **memory** store
(L1 / L2 / L3 + `:system`, SQLite FTS5) with capture and recall
pipelines; a unified **tool registry** spanning commands, skills,
sub-agents, and MCP servers; a **task manager** for long-running
background work; CoAct-derived **research-agent** and **workflow-agent**
for multi-specialist orchestration; and a split-screen **TUI** with
sessions, slash commands, and real-time rendering of the agent's
thinking.

---

## Reading order

Top-level deployment and architecture:

1. [Architecture](architecture.md) — Polylith layout, how
   `agent-tui-app` assembles bases and components.
2. [Build & deploy](build-and-deploy.md) — Babashka tasks, GraalVM
   native build, runtime configuration.

Core reference — start at `core/agent.md` and follow the cross-links:

3. [Agent overview](core/agent.md) — the `agent` component: record,
   protocols, sessions, lifecycle, hooks, multi-agent, configuration,
   observability.
4. [Behavior tree](core/bt.md) — control-flow substrate, extended
   nodes, cancellation, HITL primitives.
5. [Reasoning](core/reasoning.md) — ReAct and CoAct loops, the SCI
   sandbox, code-block format, channel choice rules.
6. [Tool system](core/tool.md) — `!tool-defs` registry,
   `deftool` / `defcommand` / `defskill` / `defagent`, MCP plumbing,
   sub-agents as tools.
7. [Memory](core/memory.md) — layered store, capture pipeline,
   recall + RRF, audit / explain.
8. [Task manager](core/task.md) — background executors, ring-buffered
   output, scheduling.
9. [Configuration](core/config.md) — schema, precedence chain,
   persisted-EDN shape, directory resolution.

[Garbage collection](gc.md) explains how on-disk transient artifacts
(task logs, CoAct scratch, sandbox caches) are reclaimed — the two-tier
eager-cleanup + bounded-sweep model behind `task$sweep`.

Design notes (proposals, deep-dives) live in `docs/design/`. The
`tui/` subdirectory covers the agent TUI ([architecture](tui/architecture.md),
[renderer internals](tui/renderer.md), [live-binary testing](tui/testing.md)).
[Live debugging](live-debugging.md) describes driving the live `by` JVM
from an external coding agent over tmux + nREPL. The `reference/`
subdirectory contains historical / migration notes.

Integration guides: [Talking to a running session](session-channel.md)
covers the per-session ask socket — discovering live sessions and the
`ask` / `status` / `inject` / `cancel` / `subscribe` verbs an external
process uses to drive or watch a session; [Web sharing](web-sharing.md)
and [Sandboxing](sandboxing.md) cover `by --web` and `by --sandbox`.

---

## Quick start

```bash
# Development REPL (Agent TUI app)
bb repl:ata

# Interactive TUI (default agent: coact-agent, default provider: claude-code:haiku)
bb tui
bb tui -p anthropic -m claude-sonnet-4-6
bb tui run -a coact-agent -i          # inline mode (no alt-screen, used by CliClient tests)

# One-shot question
bb tui ask -m opus 'What is 2+2?'

# List available agents
bb tui agents

# Interactive environment bootstrap
bb tui config

# Persisted sessions
bb tui sessions list
bb tui sessions prune -s <id>

# ACP-driven TUI (default backend: in-tree stub)
bb tui:acp
bb tui:acp -i

# Build the native `by` binary
bb build:ata            # compile → uberjar → native-image
bb install:ata          # copy to /usr/local/bin/by
```

The binary lands at `projects/agent-tui-app/target/by` (~115 MB arm64,
~0.5 s cold start).

---

## Repository layout

```
brainyard/
├── bases/              # Runnable entry points (apps, CLIs)
│   ├── agent-tui/      # Single-process renderer (`by` binary) — see tui/
│   ├── agent-web/      # Web server for agent (http-kit + WebSocket)
│   ├── agent-next/     # Next-gen agent entry point
│   ├── fulcro-rad/     # Fulcro RAD application
│   ├── electric-app/   # Electric Clojure SPA
│   ├── replicant/      # Replicant framework app
│   └── acp-stub-agent/ # ACP stub agent for protocol-level testing
├── components/         # 47 shared bricks with interfaces
├── projects/           # Deployable artifacts
│   ├── agent-tui-app/  # Terminal agent (`by`)
│   ├── agent-web-app/  # Web agent
│   ├── fulcro-rad-app/ # Fulcro RAD web app
│   ├── electric-app/   # Electric web app
│   ├── replicant-app/  # Replicant web app
│   └── acp-stub-agent/ # ACP stub
├── development/        # Dev environment, REPL workspace
├── docs/               # Architecture & design docs (you are here)
│   ├── core/           # Subsystem reference (agent, bt, reasoning, tool, memory, task)
│   ├── design/         # Specialist-agent design notes + observability + sandbox + RLM
│   ├── tui/            # Tmux-based TUI substrate
│   └── reference/      # Historical / migration notes
├── bb.edn              # Babashka tasks
├── deps.edn            # Root deps with :dev, :test, :poly aliases
└── workspace.edn       # Polylith config (top-namespace: ai.brainyard)
```

See [architecture.md](architecture.md) for the full Polylith layout and
how `agent-tui-app` assembles bases and components.

---

## Components at a glance (47 bricks)

Grouped by category; full list in the repo root `CLAUDE.md`.

**Agent & AI infrastructure.** `agent`, `clj-llm`, `behavior-tree`,
`memory`, `clj-sandbox`, `analytics`, `clj-dspy`.

**TUI substrate.** `agent-tui-tmux`, `agent-tui-persist`,
`display-block`, `env-detect`.

**ACP (Agentic Context Protocol).** `acp`, `acp-client`.

**Data & database.** `datomic`, `sql`, `migrat`.

**Web & API.** `pathom`, `server`, `websocket`, `ring-handler`,
`electric`, `config`.

**Messaging & events.** `nats`, `event`, `slack`, `email`.

**External services & storage.** `blob-store`, `minio`, `aws-client`,
`aws-api`, `keycloak`, `redis`.

**Monitoring & logging.** `mulog`, `prometheus`.

**ML & data processing.** `ml`, `djl`, `origami`, `emmy`, `d2l`,
`javacv`, `gstreamer`, `mlflow`, `label-studio`.

**Utilities.** `util`.

---

## Design principles

1. **Reasoning is data.** Behavior trees + DSPy signatures make every
   thought inspectable.
2. **One registry, many flavours.** `deftool` / `defcommand` /
   `defskill` / `defagent` share storage, dispatch, and conversion.
3. **Code as the action space.** CoAct turns executable Clojure (and
   bash / python / js) into the orchestration language.
4. **Memory is layered and compactable.** Short-term inside the BT,
   long-term in FTS5, with compaction on both sides.
5. **Agents compose safely.** Shared sessions, depth + cycle caps,
   propagated cancellation.
6. **Humans are first-class.** HITL primitives in the BT plus
   conversational gates on the answer channel.
7. **Everything is observable.** Traces, trajectories, mulog logs, and
   scores from BT tick to session.
8. **Nothing is hard-wired.** Protocols, registries, `requiring-resolve`,
   typed runtime-config.

---

## Built-in agents

Twenty-one `defagent`s ship in `components/agent`:

| Agent | Purpose |
|---|---|
| `coact-agent` *(default)* | Three-channel tool/code/answer loop |
| `react-agent` | Classic ReAct |
| `main-agent` *(opt-in `-a main-agent`; future default)* | Front-door router to the right specialist |
| `plan-agent` / `todo-agent` / `exec-agent` / `eval-agent` / `update-agent` | Plan-act-evaluate specialists |
| `explore-agent` | Reconnaissance & discovery (supersedes the retired search-agent) |
| `debug-agent` | Live-runtime debug specialist over the `:nrepl` backend |
| `research-agent` / `workflow-agent` | CoAct-derived multi-specialist orchestration |
| `skill-agent` | Run a registered skill |
| `mcp-agent` | Discover / invoke MCP tools |
| `rlm-agent` | Recursive LM with context-as-variable |
| `acp-agent` | Agentic Context Protocol client (forwards prompts to an external ACP backend) |
| `memory-agent` | LLM-driven steward of the layered memory stack |
| `config-agent` | Conversational hub for `~/.brainyard/config.edn` |
| `init-agent` | `BRAINYARD.md` authoring & maintenance |
| `tool-agent` / `hook-agent` | Author user-defined tools (`tool-agent$*`) and persistent runtime hooks (`hook-agent$*`) |
| `meta-agent` | Author user-defined agents — CoAct personas (`meta-agent$*`) persisted under `.brainyard/agents/user$agent/` |

See [core/agent.md](core/agent.md) and [core/reasoning.md](core/reasoning.md)
for details.
