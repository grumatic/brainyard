# Brainyard

> **v0.2.1 is live** ([release notes](https://github.com/grumatic/brainyard/releases/tag/v0.2.1)) — a docs & tooling refresh over v0.2.0, which open-sourced the complete Polylith workspace (sources, build config, docs); earlier releases shipped binaries only. Platform coverage: **macOS arm64** native binary plus a portable **JDK 21+ uberjar**; Linux and macOS amd64 binaries to follow.

Brainyard is an agent-driven terminal UI for working with LLMs from the command line. The shipping binary is named `by` — it can run interactive TUI sessions, ask one-shot questions, list 18 available agents across 6 subcommands (`run`, `ask`, `agents`, `models`, `config`, `sessions`), and bootstrap configuration without leaving the terminal. Providers wired up at v0.1.0: `claude-code` (default), `anthropic`, `openai`, `bedrock`, `ollama`, `apple-fm`.

<!-- TODO: replace with a real asciinema cast / GIF once recorded -->
<!-- ![Brainyard TUI demo](docs/img/demo.gif) -->

## Install

### macOS / Linux — one-line install

```bash
curl -fsSL https://raw.githubusercontent.com/grumatic/brainyard/main/bin/install.sh | bash
```

This installs three files under `~/.local/bin`:

- `by` — the wrapper that sources nearby `.env` files and execs the real binary.
- `by-bin` — the native (GraalVM) binary for your OS/arch.
- `by.jar` *(optional, via `--with-jar`)* — the uberjar, used when `BY_JAR=1` for JVM-mode debugging.

Pin a specific version with `BY_VERSION=v0.2.0` before piping to bash. See [`docs/install.md`](docs/install.md) for manual install, checksum verification, and troubleshooting.

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
```

Full command reference: [`docs/usage.md`](docs/usage.md).

## Tutorials

Watch `by` in action — 17 recorded terminal walkthroughs (asciinema), playable in your browser:

**▶ [grumatic.github.io/brainyard](https://grumatic.github.io/brainyard/)**

Topics range from a first "hello" turn to tools & skills, codebase exploration, planning a feature, multi-turn native sessions, research coordination, MCP servers, and workflows. The scenario sources live under [`docs/tutorials/`](docs/tutorials/).

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

## License

Licensed under the [Apache License, Version 2.0](LICENSE). Copyright 2024-2026 Grumatic, Inc.

## Acknowledgements

Brainyard builds on the [Clojure](https://clojure.org/) ecosystem, [GraalVM](https://www.graalvm.org/) native-image, the [Polylith](https://polylith.gitbook.io/) architecture, and a long list of OSS libraries. Runtime dependencies are declared in each brick's `deps.edn`. The CycloneDX SBOM embedded in the native binary lists its components — extract it with `native-image -H:DumpSBOM=…`.
