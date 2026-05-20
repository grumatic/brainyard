# Brainyard

> **Status:** pre-release. The first public release (`v0.1.0`) lands at milestone M1 — see [`docs/deploy-design.md`](docs/deploy-design.md). The install commands below describe the planned UX; they will start working once the first release is published.

Brainyard is an agent-driven terminal UI for working with LLMs from the command line. The shipping binary is named `by` — it can run interactive TUI sessions, ask one-shot questions, list available agents, and bootstrap configuration without leaving the terminal.

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

Pin a specific version with `BY_VERSION=v0.1.0` before piping to bash. See [`docs/install.md`](docs/install.md) for manual install, checksum verification, and troubleshooting.

### Java users — uberjar

```bash
curl -LO https://github.com/grumatic/brainyard/releases/latest/download/by.jar
java -jar by.jar --help
```

Requires JDK 21+.

## Quick start

```bash
by --help                         # see all subcommands and flags
by agents                         # list available agents
by ask -m haiku 'What is 2+2?'    # one-shot question, cheap model
```

Full command reference: [`docs/usage.md`](docs/usage.md).

## Building from source

This repo is a thin **sync-build-publish wrapper** around the private upstream development repo. It tracks only the sync script, installer, and release glue — no Clojure sources, no `bb.edn`, no `deps.edn`. Sources are pulled in on demand by `bin/sync-from-dev.sh` (which requires access to the private upstream) and discarded after every release; they are never committed.

Maintainers with upstream access build like this:

```bash
git clone https://github.com/grumatic/brainyard
cd brainyard
bin/sync-from-dev.sh              # pulls Polylith subset + bb.edn/deps.edn from upstream
sdk use java 25.0.1-graal         # matches the synced .sdkmanrc
bb build:ata                      # AOT compile → uberjar → native binary
bin/release-stage.sh              # stage release/ artifacts + BUILD-INFO.txt with upstream SHA
```

External users without upstream access should install the published binary via the `curl | bash` step above. See [`docs/deploy-design.md`](docs/deploy-design.md) for the full architecture.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). Copyright 2026 Grumatic, Inc.

## Acknowledgements

Brainyard builds on the [Clojure](https://clojure.org/) ecosystem, [GraalVM](https://www.graalvm.org/) native-image, the [Polylith](https://polylith.gitbook.io/) architecture, and a long list of OSS libraries credited in the upstream repo. Specific runtime dependencies are listed in `projects/agent-tui-app/deps.edn` once sources are mirrored.
