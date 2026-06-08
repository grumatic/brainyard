# Brainyard

> **v0.3.0 is live** ([release notes](https://github.com/grumatic/brainyard/releases/tag/v0.3.0) · [changelog](CHANGELOG.md)) — **reference docs ride in context**: the agent's prompt now carries a `## Live Artifacts` section, so your `CLAUDE.md` / `AGENTS.md` (and anything else you name via `:reference-artifact-paths`) are seeded fresh and pinned every turn, and the agent can pin its own files and notes through a new `artifact$add` / `artifact$list` / `artifact$remove` / `artifact$pin` family (see [`docs/design/artifacts.md`](docs/design/artifacts.md)). Pinned material is **never dropped wholesale** when the context budget tightens, and the layered-memory **capture pipeline now runs by default** so the L2 chronicle fills as you work. Opus remains the default model (`claude-code:opus` out of the box). Platform coverage: **macOS arm64** native binary plus a portable **JDK 21+ uberjar**; Linux and macOS amd64 binaries to follow.

Brainyard is an agent-driven terminal UI for working with LLMs from the command line. The shipping binary is named `by` — it can run interactive TUI sessions, ask one-shot questions, list 21 available agents across 6 subcommands (`run`, `ask`, `agents`, `models`, `config`, `sessions`), and bootstrap configuration without leaving the terminal. Providers wired up at v0.1.0: `claude-code` (default), `anthropic`, `openai`, `bedrock`, `ollama`, `apple-fm`.

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

Pin a specific version with `BY_VERSION=v0.3.0` before piping to bash. See [`docs/install.md`](docs/install.md) for manual install, checksum verification, and troubleshooting.

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

Watch `by` in action — 20 terminal walkthroughs (19 recorded asciinema casts plus a web-sharing guide), playable in your browser:

**▶ [grumatic.github.io/brainyard](https://grumatic.github.io/brainyard/)**

Topics range from a first "hello" turn to tools & skills, codebase exploration, planning a feature, multi-turn native sessions, research coordination, MCP servers, workflows, authoring your own persistent tools, and sharing a live session over the web. The scenario sources live under [`docs/tutorials/`](docs/tutorials/).

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

## Contributing

Contributions are welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md) for the development setup, workspace layout, and PR conventions.

## License

Licensed under the [MIT License](LICENSE). Copyright (c) 2024-2026 Grumatic, Inc.

## Acknowledgements

Brainyard builds on the [Clojure](https://clojure.org/) ecosystem, [GraalVM](https://www.graalvm.org/) native-image, the [Polylith](https://polylith.gitbook.io/) architecture, and a long list of OSS libraries. Runtime dependencies are declared in each brick's `deps.edn`. The CycloneDX SBOM embedded in the native binary lists its components — extract it with `native-image -H:DumpSBOM=…`.
