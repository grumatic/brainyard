# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

The public source for **Brainyard** — an agent-driven terminal UI for working with LLMs from the command line. The shipping binary is `by`. The codebase is a [Polylith](https://polylith.gitbook.io/) workspace: a curated subset of bricks (`bases/`, `components/`) composed into the `agent-tui-app` project, built to a GraalVM native binary and a JVM uberjar.

This repo was seeded from the upstream `v0.2.0` snapshot and is now the source of truth — develop here directly. (Earlier `v0.1.x` releases were published from a thin sync-wrapper model that has since been retired.)

## Required environment

- GraalVM 25.0.3+ on `PATH` (or via `.sdkmanrc` + SDKMAN). The `bb native:ata` task probes `PATH`, `JAVA_HOME`, and `/Library/Java/JavaVirtualMachines/`.
- `bb` (Babashka) and the `clojure` CLI.
- `gh` CLI for release publishing.

## Build & release pipeline

```bash
sdk use java 25.0.3-graal         # matches .sdkmanrc
bb build:ata                      # version:ata → compile → uberjar → native binary (~3 min)
bin/release-stage.sh              # stage release/ artifacts + SHA256SUMS + BUILD-INFO.txt
gh release create vX.Y.Z release/* --notes-file CHANGELOG-latest.md
```

### Tagging discipline (critical)

The binary's `--version` is **baked at build time from `git describe` of this repo**, so the tag IS the release version. Workflow for a new release:

1. Update `CHANGELOG.md` and `CHANGELOG-latest.md`, commit.
2. `git tag vX.Y.Z` at HEAD.
3. `bb build:ata` — `bb version:ata` runs first, stamping `projects/agent-tui-app/resources/build-version.edn` from `git describe`. (That file is gitignored.)
4. `bin/release-stage.sh` — **refuses to stage** if the describe output is `-dirty`, `-N-gabc123` (commits past the tag), or `dev`. These would bake a misleading version into a public binary.
5. `gh release create vX.Y.Z release/* …`

Committing after tagging puts the repo into post-tag state (`vX.Y.Z-1-g…`); `release-stage.sh` will reject builds from this state. To re-release after a doc fix, move the tag (`git tag -f vX.Y.Z`) and re-build.

## Key files

- `bin/release-stage.sh` — packages `target/` outputs into `release/` with the exact asset names `bin/install.sh` expects. Reads the version from `projects/agent-tui-app/resources/build-version.edn` and records this repo's commit in `BUILD-INFO.txt`.
- `bin/install.sh` — public `curl | bash` installer. Resolves the latest release tag via the GitHub API, downloads platform-matched assets, verifies SHA-256, re-codesigns on macOS.
- `deps.edn` / `bb.edn` / `workspace.edn` — Polylith workspace + task config.
- `docs/` — architecture, design notes, specs, and tutorials.

## Testing

```bash
bb test                                      # run all Polylith tests (clj -M:poly test)
bb poly <args>                               # Polylith CLI (e.g. bb poly check, bb poly info)
```

After a build, smoke-test the binary directly:

```bash
projects/agent-tui-app/target/by --help      # subcommand routing
projects/agent-tui-app/target/by agents      # config + agent registry load
projects/agent-tui-app/target/by sessions list   # sqlite persist layer
```

For a real LLM round-trip (Bedrock works without API keys if `AWS_PROFILE` is set — note `AWS_DEFAULT_PROFILE` is **not** honored by the binary's SDK chain):

```bash
AWS_PROFILE=<profile> projects/agent-tui-app/target/by \
  ask -p bedrock -m amazon.nova-lite-v1:0 'What is 2+2?'
```

JVM-mode parity check (catches reflection-config gaps):

```bash
BY_JAR=1 projects/agent-tui-app/target/by ask …
```

## bb task naming convention

Tasks for the shipping project end in `:ata` (agent-tui-app): `compile:ata`, `uberjar:ata`, `native:ata`, `build:ata`, `install:ata`, `version:ata`, `check:ata` (native-image config drift gate), `size:ata`, `repl:ata`, `tracing:ata`, `docker:ata`. Workspace-wide tasks (`test`, `poly`) have no suffix.
