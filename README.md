# Brainyard

> **v0.4.0 is live** ([release notes](https://github.com/grumatic/brainyard/releases/tag/v0.4.0) · [changelog](CHANGELOG.md)) — a large release. **User-defined events, reactions & a watch loop**: define and emit named events (`event$emit`, `by events emit`, or externally over `ask.sock`), turn them into effects with persisted `event → action` rules (`reaction$*`), and probe external conditions on a schedule (`watch$*`) — a pub/sub layer over the agent's hooks bus. **Context-graph memory**: an opt-in typed entity/relationship graph plus a semantic vector index over long-term memory, with a self-contained in-binary embedder. **A multi-zone prompt cache** with cross-turn breakpoints and an opt-in 1-hour TTL trims cross-turn cost ~60–75%. Plus always-alive subagents (`agent-registry$*`), permission-gated MCP tool calls, and a `by memory` maintenance CLI. Opus remains the default model (`claude-code:opus` out of the box). Platform coverage: **macOS arm64** native binary plus a portable **JDK 21+ uberjar**; Linux and macOS amd64 binaries via the uberjar.

Brainyard is an **agent runtime** that happens to wear a terminal UI — not a chat box in front of a provider API. The shipping binary is `by`: it runs interactive TUI sessions, answers one-shot questions, and drives **25 built-in agents** across 8 subcommands (`run`, `ask`, `agents`, `models`, `config`, `sessions`, `memory`, `events`). Providers: `claude-code` (the default — drives the local Claude CLI, no API key), `anthropic`, `openai`, `bedrock`, `google`, `groq`, `ollama`, `apple-fm`, plus OpenAI-compatible endpoints (`openrouter`, `together`, `fireworks`, `deepseek`, `mistral`, `azure`).

## Why

In most terminal LLM tools the interesting parts — how the agent decided, what it remembered, what it was allowed to touch — are opaque, and they evaporate when the process exits. Brainyard treats them as substrate you can inspect, persist, and recompose:

- **Reasoning is data.** Every agent is a [behavior tree](docs/core/bt.md) whose leaves call typed DSPy signatures, registered tools, sandbox evaluations, or human prompts. ReAct and CoAct aren't modes bolted on — they're subtrees over one substrate, so switching styles is a config change rather than a rewrite.
- **Code is the action space.** The default loop can respond with executable Clojure (or bash / python / js) instead of a JSON tool call, collapsing filter-compose-retry sequences that would otherwise cost a round trip each into a single iteration.
- **Memory is layered, not a transcript.** A working set per turn, a session layer, a SQLite chronicle (~30 days), and distilled long-term facts — populated automatically from lifecycle hooks and recalled by rank fusion over full-text search, with an opt-in entity/relationship graph and vector index over the top.
- **Composition is bounded.** Agents delegate to sub-agents over a shared session, but call depth is capped and cycles are rejected outright, so "agents calling agents" stays a tree with a known cost ceiling instead of an open-ended fan-out.
- **Privileged actions are gated, not sealed off.** File writes, shell, network, and tool calls route through a permission layer with human-in-the-loop approval as an ordinary node in the tree.

## How it works

A turn runs the default **CoAct** loop: one LLM call per iteration that emits exactly one of three channels — *tool calls*, *code blocks*, or a final *answer* (which terminates the loop). Code runs in an in-process [SCI sandbox](docs/core/reasoning.md): interpreted, so it can't load classes or escape the host; whitelisted, so it sees only the bindings passed in; and stateful, so `def`s persist across iterations. Every registered tool is auto-bound as a callable function there, which is why a skill loaded mid-session is reachable from the next code block without rebuilding anything. Blocks that outlive their timeout [detach into background tasks](docs/core/task.md) and fold their results back in when they finish.

Tools, commands, skills, and agents all live in [one registry](docs/core/tool.md) sharing storage and dispatch — including MCP servers, whose tools register as ordinary namespaced tools (with OAuth, so hosted remote servers work headless). Sessions, plans, dossiers, authored tools, and agents persist under `.brainyard/` in your project, so a session is resumable and the artifacts outlive the process. Configuration resolves through a documented [precedence chain](docs/core/config.md) from environment variables down to schema defaults.

Start with [`docs/core/agent.md`](docs/core/agent.md) for the architecture, or [`docs/design/`](docs/design/) for the design notes behind each subsystem.

<!-- TODO: replace with a real asciinema cast / GIF once recorded -->
<!-- ![Brainyard TUI demo](docs/img/demo.gif) -->

## Install

### macOS / Linux — one-line install

```bash
curl -fsSL https://raw.githubusercontent.com/grumatic/brainyard/main/bin/install.sh | bash
```

On **macOS arm64** this installs the native binary under `~/.local/bin`:

- `by` — the wrapper that sources nearby `.env` files and execs the real binary.
- `by-bin` — the native (GraalVM) binary.
- `by.jar` *(optional, via `--with-jar`)* — the uberjar, used when `BY_JAR=1` for JVM-mode debugging.

On other platforms (Linux, Intel macOS) — where a native binary isn't published yet — the installer automatically falls back to the **JVM uberjar**, installing a `by` launcher that runs `java -jar` (requires a JDK 21+ on `PATH`).

Pin a specific version with `BY_VERSION=v0.4.0` before piping to bash. See [`docs/install.md`](docs/install.md) for manual install, checksum verification, and troubleshooting.

### Java users — uberjar

```bash
curl -LO https://github.com/grumatic/brainyard/releases/latest/download/by.jar
java -jar by.jar --help
```

Requires JDK 21+.

### Configure credentials

`by` reads provider credentials from a nearby `.env` file. Copy the template and fill in the providers you use:

```bash
curl -fsSL https://raw.githubusercontent.com/grumatic/brainyard/main/.env.example -o .env
# then edit .env — set ANTHROPIC_API_KEY / OPENAI_API_KEY / AWS_PROFILE / etc.
```

If you cloned the repo, just `cp .env.example .env`. You only need keys for the providers you actually use — the default `claude-code` provider drives the local Claude CLI and needs no API key, as do `ollama` and `apple-fm`. See [`docs/usage.md`](docs/usage.md#environment-variables) for the full variable list.

## Quick start

```bash
by --help                         # see all subcommands and flags
by agents                         # list available agents
by ask -m haiku 'What is 2+2?'    # one-shot question, cheap model
by --web                          # share this session in your browser (needs ttyd)
```

Full command reference: [`docs/usage.md`](docs/usage.md). Sharing a session over the web: [`docs/web-sharing.md`](docs/web-sharing.md).

## Tutorials

Watch `by` in action — 24 terminal walkthroughs (22 recorded asciinema casts plus web-sharing and sandboxing guides), playable in your browser:

**▶ [grumatic.github.io/brainyard](https://grumatic.github.io/brainyard/)**

Topics range from a first "hello" turn to tools & skills, codebase exploration, planning a feature, multi-turn native sessions, research coordination, MCP servers, workflows, authoring your own persistent tools / hooks / agents, sandboxing a session, and sharing a live session over the web. The scenario sources live under [`docs/tutorials/`](docs/tutorials/).

## Building from source

This repo holds the full source: a [Polylith](https://polylith.gitbook.io/) workspace (`bases/`, `components/`, `projects/agent-tui-app/`) built to a GraalVM native binary and a JVM uberjar.

```bash
git clone https://github.com/grumatic/brainyard
cd brainyard
sdk use java 25.0.3-graal         # matches .sdkmanrc
bb build:ata                      # version → AOT compile → uberjar → native binary
bin/release-stage.sh              # stage release/ artifacts + BUILD-INFO.txt
```

Run the test suite with `bb test`. See [`CLAUDE.md`](CLAUDE.md) for the build/release pipeline and tagging discipline, and [`docs/`](docs/) for architecture and design notes.

## Playground — web multi-tenant `by`

The repo also contains the **Brainyard Playground**: a hosted, multi-tenant web service where each user logs in (OIDC) and drives `by` from a browser terminal in their own isolated, pre-provisioned workspace. It turns the single-user `--web` / `--sandbox` launchers into a **control plane** (auth + session orchestration + WebSocket proxy) in front of a **data plane** of per-session Docker containers.

Phases 0–1 are implemented and verified end-to-end:

- **`frontend/playground-ui`** — a ClojureScript + [Replicant](https://replicant.fun/) SPA: login · dashboard · workspace · settings. The terminal embeds ttyd's own client same-origin in an iframe (with copy-on-select); **Settings** holds per-user BYO provider keys.
- **`bases/playground-server`** — the JVM control plane: real OIDC (JWKS-verified, with a bare-cookie stub when no `OIDC_ISSUER` is set), a portable store (**SQLite by default**, **Postgres** via `PG_DATABASE_URL`), a user-scoped session broker with a restart-safe reconcile and an idle reaper, a Docker `workspace-runtime` with persistent per-session volumes (suspend/resume), and an authorized WebSocket proxy to each container's ttyd.
- **`deploy/playground-workspace`** — the workspace image (`temurin:21-jre` + ttyd + tmux + git + a dev toolchain + the `by` uberjar), launched with `--web-tmux` + `BY_RESUME_LATEST=1` so a recreated container reattaches to the newest session.

Run it locally (real Docker workspaces unless `PG_FAKE=1`):

```bash
bb playground:ui            # build the SPA into the control-plane base
bb uberjar:ata              # the by uberjar baked into the workspace image
bb playground:image         # build the workspace Docker image
bb playground:run           # control plane on :8090
```

Tenant isolation today is **Docker-per-session** (a real OS boundary, not seatbelt). Phase 2 — egress allowlist, gVisor/Firecracker driver, warm pool, autoscaling, quotas, audit log, and the production Vault cut-over — is still ahead. Full design and as-built notes: [`docs/design/playground-design.md`](docs/design/playground-design.md).

## Contributing

Contributions are welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md) for the development setup, workspace layout, and PR conventions.

## License

Licensed under the [MIT License](LICENSE). Copyright (c) 2024-2026 Grumatic, Inc.

## Acknowledgements

Brainyard builds on the [Clojure](https://clojure.org/) ecosystem, [GraalVM](https://www.graalvm.org/) native-image, the [Polylith](https://polylith.gitbook.io/) architecture, and a long list of OSS libraries. Runtime dependencies are declared in each brick's `deps.edn`. The CycloneDX SBOM embedded in the native binary lists its components — extract it with `native-image -H:DumpSBOM=…`.
