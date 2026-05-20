# Changelog

All notable changes to Brainyard's public distribution are documented here. Versions follow [Semantic Versioning](https://semver.org/).

## [v0.1.1] — 2026-05-20

Tooling release. No user-visible behavior changes vs. v0.1.0 — same 18 agents, same 6 subcommands, same provider lineup. Recommended for everyone on v0.1.0 (the upgrade is a drop-in `curl | bash` re-run).

### What changed

- **Build version baked at compile time.** Upstream's `app-version` now reads `resources/build-version.edn`, which `bb version:ata` stamps from `git describe --tags --always --dirty` before AOT compile. The binary's reported version always reflects the actual source tag, not a hand-edited string literal that could drift.
- **`bb native:ata` honors the `.sdkmanrc` pin** when probing for `native-image` on machines with multiple GraalVM installs. Fewer "wrong-toolchain-built" surprises on multi-Graal dev boxes.
- **Wrapper-repo invariant.** This repo's `bin/release-stage.sh` now refuses to stage a release when the wrapper's `git describe` carries `-dirty`, `-N-gabc123` (commits past tag), or resolves to `dev` (no git). Prevents stamping a misleading version into a public artifact.

### Artifacts

- `by-0.1.1.jar` — Clojure uberjar (50 MB). Runs on JDK 21+.
- `by-0.1.1-macos-arm64` — native binary (138 MB). Cold start ~1.5 s.
- `by-wrapper.sh` — wrapper shell script (unchanged from v0.1.0).
- `SHA256SUMS` — checksums.
- `BUILD-INFO.txt` — upstream SHA, branch, sync/build timestamps.

### Upstream provenance

Built from upstream commit `184d6ecf0c87148041f82a4bdcf9e018597f3562` (branch `main`, synced 2026-05-20). Full provenance in `release/BUILD-INFO.txt`.

### Known gaps (unchanged from v0.1.0)

- **Linux binaries** not in v0.1.1. Use the uberjar on Linux for now.
- **macOS amd64** not in v0.1.1. Use the uberjar on Intel Macs.
- **Windows** deferred (see `docs/deploy-design.md` §7.2).

---

## [v0.1.0] — 2026-05-20

First public release of the `by` binary (the Brainyard agent TUI).

This release is a **manual M1 release** per the deployment design — built locally on macOS arm64, with limited platform coverage. Linux and macOS amd64 binaries land at M3 once the CI matrix is in place.

### Artifacts

- `by-0.1.0.jar` — Clojure uberjar (50 MB). Runs on JDK 21+.
- `by-0.1.0-macos-arm64` — native binary (138 MB). Cold start ~1.5 s.
- `by-wrapper.sh` — wrapper shell script. Sources `.env` and execs the native binary.
- `SHA256SUMS` — checksums covering all of the above.

### Install

See [`docs/install.md`](docs/install.md) for the full install paths. Quick start:

```bash
# Native binary (macOS arm64 only in v0.1.0)
curl -fsSL https://raw.githubusercontent.com/grumatic/brainyard/main/bin/install.sh | bash

# Uberjar (any platform with JDK 21+)
curl -LO https://github.com/grumatic/brainyard/releases/download/v0.1.0/by-0.1.0.jar
java -jar by-0.1.0.jar --help
```

### Upstream provenance

Built from the private upstream `~/Projects/MyDev/brainyard` at commit `ebd66caef58e2319c51bd37a248286e2f4d5fe0b` (branch `main`, synced 2026-05-20). Provenance lives in `release/BUILD-INFO.txt` (uploaded with each release) — synced sources are not committed to this repo.

### Build environment (this release)

- macOS arm64
- GraalVM Oracle 25.0.3+9.1 (build 25.0.3+9-LTS) — matches upstream `.sdkmanrc` (`java=25.0.3-graal`).
- Babashka 1.3.190
- Clojure CLI 1.12.0.1530

### Known gaps

- **Linux binaries** are not in v0.1.0. Use the uberjar on Linux for now.
- **macOS amd64** is not in v0.1.0. Use the uberjar on Intel Macs.
- **Windows** is deferred (see `docs/deploy-design.md` §7.2).
- **GraalVM 21.0.9** surfaces a `sci.impl.multimethods__init` clinit failure that GraalVM 25 does not. Upstream `native-image.properties` may need an `--initialize-at-run-time=sci.impl.multimethods__init` carve-out before CI on older GraalVM is reliable.

### Closure

18 Polylith bricks mirrored: 2 bases (`agent-tui`, `acp-stub-agent`) and 16 components. Full list in `bin/.brick-set`.
