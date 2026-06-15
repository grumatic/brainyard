# Brainyard

> **v0.3.2 is live** ([release notes](https://github.com/grumatic/brainyard/releases/tag/v0.3.2) · [changelog](CHANGELOG.md)) — **a friendlier input bar**: the agent's suggested follow-up now sits on the empty prompt as a right-arrow-acceptable ghost (`↳ … (→ to use)`) and time-shares the line with rotating help tips, persisting until your next turn. `by run --resume-latest` (and `BY_RESUME_LATEST`) reattaches to the newest session for automated relaunches; the never-implemented `/session resume` subcommand is removed; plus fixes for provider-switch API keys, orphaned subprocesses on quit, the tab-indented answer box, and resuming an agent whose type is no longer registered. Opus remains the default model (`claude-code:opus` out of the box). Platform coverage: **macOS arm64** native binary plus a portable **JDK 21+ uberjar**; Linux and macOS amd64 binaries to follow.

Brainyard is an agent-driven terminal UI for working with LLMs from the command line. The shipping binary is named `by` — it can run interactive TUI sessions, ask one-shot questions, list 22 available agents across 6 subcommands (`run`, `ask`, `agents`, `models`, `config`, `sessions`), and bootstrap configuration without leaving the terminal. Providers wired up at v0.1.0: `claude-code` (default), `anthropic`, `openai`, `bedrock`, `ollama`, `apple-fm`.

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

Pin a specific version with `BY_VERSION=v0.3.2` before piping to bash. See [`docs/install.md`](docs/install.md) for manual install, checksum verification, and troubleshooting.

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
