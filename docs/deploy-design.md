# Brainyard Deployment Design

> Status: Draft v0.2 — 2026-05-17
> Owner: Jake (jake.na@grumatic.com)
> Scope: Public distribution of the `by` binary (built from the `agent-tui-app` Polylith project) from the private development repo (`~/MyDev/brainyard`) through this public GitHub repo (`grumatic/brainyard`).
>
> v0.2 revisions: facts verified against the upstream repo. Polylith reality factored into the layout and sync model. Babashka-script artifact deferred (the app's runtime deps — sqlite-jdbc, cognitect.aws, logback, mulog — are not bb-compatible). Binary name is `by`, not `brainyard`. CI invokes the upstream's existing `bb …:ata` tasks rather than reinventing them. JDK pin is GraalVM 25, not Java 17.

## 1. Goals and Non-Goals

### Goals
- Provide a public, install-friendly home for Brainyard's `by` binary (the agent TUI).
- Publish two flavors of artifact so users can pick the one that fits their environment: a Clojure uberjar and a native (GraalVM) executable per OS/arch. (A pure-Babashka script artifact is **deferred** — see §4.4.)
- Distribute every release through **GitHub Releases** so installs can be performed with a single `curl | bash` command.
- Provide a clear README that explains what Brainyard is, who it is for, and how to install and use it.
- Keep the public repo cleanly separated from the upstream dev repo, with a simple, reproducible sync step.
- Automate build, test, and release via GitHub Actions so a tag push produces a complete release.

### Non-Goals (for this iteration)
- Mirroring the full Brainyard source tree (the dev repo stays private; only the Polylith subset transitively required by `agent-tui-app` plus the user-facing files needed to install/run it are published).
- Publishing to Homebrew, Scoop, or other third-party package managers (may be added later).
- Publishing Docker images (may be added later if there is demand).
- Auto-syncing on every dev commit. Sync is explicitly manual and gated by a script.

### Upstream reality (verified 2026-05-17 against `~/MyDev/brainyard`)
- The upstream is a **Polylith** monorepo. The shipping project is `projects/agent-tui-app`. Its `deps.edn` pulls in **1 base + 12 components** via `:local/root` paths (`../../bases/agent-tui`, `../../components/{agent, analytics, behavior-tree, clj-llm, mulog, clj-sandbox, display-block, memory, util, env-detect, agent-tui-persist, agent-tui-tmux}`). Those bricks have their own transitive component deps, so the publishable subset is larger than the project directory alone.
- `bb.edn` is at the **monorepo root** (not inside `projects/agent-tui-app`). It already defines the full native-build pipeline used in production: `compile:ata`, `uberjar:ata`, `native:ata`, `build:ata` (chained), `install:ata`, `check:ata`, `size:ata`. CI should call these rather than reinvent them.
- Uberjar tooling is **`uberdeps`** (`{:aliases {:uberdeps {:replace-deps {uberdeps/uberdeps …}}}}` in the project's `deps.edn`), not `tools.build`.
- The `-main` namespace is `ai.brainyard.agent-tui-app.main` (in `src/ai/brainyard/agent_tui_app/main.clj`) with `(:gen-class)`. The cli-matic-driven binary supports `run`, `ask`, `agents`, `config` subcommands.
- The native binary is named **`by`** (per upstream `bb native:ata` → `-H:Name=target/by`). Current declared version: **`0.1.0`** (hardcoded in `main.clj` as `app-version`).
- Native-image config is **already complete and committed** at `projects/agent-tui-app/resources/META-INF/native-image/ai.brainyard/agent-tui-app/` (`native-image.properties`, `reflect-config.json`, `resource-config.json`, `proxy-config.json`). Init policy is the modern allow-list using `--features=clj_easy.graal_build_time.InitClojureClasses` — no deprecated bare `--initialize-at-build-time` form. The `bb check:ata` task gates against drift in these files.
- A **wrapper script** `projects/agent-tui-app/scripts/by-wrapper.sh` is part of the contract: it walks up to find `.env`, sources it, then `exec`s `by-bin`. `bb install:ata` installs `by` (wrapper) + `by-bin` (native) + `by.jar` (uberjar, used when `BY_JAR=1`) into `~/.local/bin`. The public installer should mirror this layout.
- Toolchain pin: **GraalVM 25** (`.sdkmanrc: java=25.0.3-graal`). The build is also verified against GraalVM 21.0.9 per upstream `CLAUDE.md`.
- The public repo `grumatic/brainyard` currently contains only `.git/` and `docs/` — a true greenfield.

---

## 2. Repository Layout

This repo is a thin **sync-build-publish wrapper**. The Polylith source tree and build config are pulled into the working tree on demand by `bin/sync-from-dev.sh` and **never committed** — only the sync script, installer, release-staging script, and human-maintained docs are tracked. Provenance for each release lives in `release/BUILD-INFO.txt` (stamped by `bin/release-stage.sh`), not in git.

```
brainyard/                              # this repo (public)
├── README.md                           # what Brainyard is + install/use         [tracked]
├── LICENSE                                                                       [tracked]
├── CHANGELOG.md                        # human-readable per-release notes        [tracked]
├── docs/
│   ├── deploy-design.md                # this document                            [tracked]
│   ├── install.md                      # long-form install instructions           [tracked]
│   ├── usage.md                        # commands, flags, examples                [tracked]
│   └── upstream-readme.md              # synced from agent-tui-app/TUI.md         [gitignored]
├── bin/
│   ├── sync-from-dev.sh                # pull publishable subset from upstream    [tracked]
│   ├── install.sh                      # curl|bash installer                      [tracked]
│   ├── release-stage.sh                # stage release/ artifacts + BUILD-INFO    [tracked]
│   └── .brick-set                      # committed allow-list of mirrored bricks  [tracked]
├── .github/
│   └── workflows/
│       ├── ci.yml                      # PR build + test                          [tracked]
│       └── release.yml                 # tag-driven build + GitHub Release        [tracked]
├── .gitignore                                                                     [tracked]
│
│   ─── everything below is pulled by bin/sync-from-dev.sh and gitignored ────
│
├── SYNCED-FROM.txt                     # upstream SHA, branch, timestamp (working-tree only)
├── .sdkmanrc                           # pins GraalVM 25 for build reproducibility
├── bb.edn                              # root-level orchestration (compile:ata, uberjar:ata, native:ata, …)
├── deps.edn                            # root-level Polylith deps + aliases (e.g. :poly)
├── workspace.edn                       # Polylith workspace descriptor (trimmed to mirrored bricks)
├── .clj-kondo/                         # lint config, kept in sync with upstream
├── projects/
│   └── agent-tui-app/                  # the app's deps.edn, src, resources, scripts
│       ├── deps.edn
│       ├── src/ai/brainyard/agent_tui_app/main.clj
│       ├── resources/META-INF/native-image/ai.brainyard/agent-tui-app/
│       │   ├── native-image.properties
│       │   ├── reflect-config.json
│       │   ├── resource-config.json
│       │   └── proxy-config.json
│       └── scripts/by-wrapper.sh
├── bases/
│   └── agent-tui/
└── components/                         # only the bricks transitively required by agent-tui-app
    ├── agent/                          # (1 base + 12+ components; resolver in bin/sync-from-dev.sh)
    ├── agent-tui-persist/
    ├── agent-tui-tmux/
    ├── analytics/
    ├── behavior-tree/
    ├── clj-llm/
    ├── clj-sandbox/
    ├── display-block/
    ├── env-detect/
    ├── memory/
    ├── mulog/
    └── util/
        └── …(plus any transitive component deps surfaced by `bb poly:deps`)
```

Conventions:
- Everything marked `[gitignored]` above is **pulled by `bin/sync-from-dev.sh` into the working tree and never committed**. A fresh clone cannot run `bb` until sync has been run once. This keeps the public repo's diff history limited to release glue; per-release source provenance is captured in `release/BUILD-INFO.txt` via the upstream SHA.
- Everything marked `[tracked]` is **owned by this repo** — these files are the deployment contract and are hand-maintained.
- The synced surface is intentionally a **subset** of upstream, not the full Polylith workspace. Other projects in upstream (`agent-web-app`, `fulcro-rad-app`, `electric-app`, `replicant-app`, `acp-stub-agent`) and their unique components stay private. The sync script's transitive-closure resolver is the gate; `bin/.brick-set` pins the resolved set as a tripwire.

---

## 3. Sync Model: `bin/sync-from-dev.sh`

A manual, idempotent shell script that copies the Polylith subset transitively required by `agent-tui-app` from the dev repo into this repo.

### Behavior
1. Read `BRAINYARD_DEV_REPO` env var (default: `$HOME/MyDev/brainyard`). Abort if the path does not exist or is not a git repo.
2. Verify the upstream working tree is clean (no uncommitted changes), or run with `--allow-dirty` to override. Reason: every public release must be traceable to a specific upstream SHA.
3. Resolve the upstream commit SHA (`git -C "$BRAINYARD_DEV_REPO" rev-parse HEAD`).
4. Compute the **publishable brick set**: start from `projects/agent-tui-app` and walk `:local/root` edges (and the `dev`/`build`/`uberdeps` aliases' `:extra-deps`) recursively until a fixed point. The current root set is documented in §1 (1 base + 12 components); the script must re-derive it on every run because upstream may add components. A `--list-bricks` flag prints the resolved set and exits, for debugging.
5. `rsync` (with `--delete` and an explicit allow-list per directory) the following from upstream into this repo, preserving relative paths so the `:local/root` graph stays intact:
   - `projects/agent-tui-app/{deps.edn,src,resources,scripts}` → `projects/agent-tui-app/…`
   - `bases/agent-tui/` → `bases/agent-tui/`
   - `components/<each-resolved-brick>/` → `components/<brick>/`
   - `bb.edn` → `bb.edn` (root-level orchestration)
   - `deps.edn`, `workspace.edn` → repo root (Polylith workspace descriptors)
   - `.clj-kondo/` → `.clj-kondo/`
   - `.sdkmanrc` → `.sdkmanrc`
   - `projects/agent-tui-app/TUI.md` → `docs/upstream-readme.md` (for reference; not the public README)
6. Excludes: `.git`, `target/`, `.cpcache/`, `.shadow-cljs/`, `node_modules/`, `benchmark-results/`, `.brainyard/`, `.env`, `.nrepl-port`, editor scratch files. The exclude list lives in `bin/sync-from-dev.sh` so it's reviewable in one place.
7. Trim `workspace.edn` so the `:projects` map only references mirrored bricks; otherwise `bb poly:check` fails on missing bricks. The trim step is idempotent and uses `jq`/`bb` to manipulate edn, not hand-rolled sed.
8. Write `SYNCED-FROM.txt` at repo root: upstream SHA, upstream branch, timestamp, user who ran sync, resolved brick list. **Gitignored** — consumed downstream by `bin/release-stage.sh`, which stamps the upstream SHA into `release/BUILD-INFO.txt`.
9. Print `git status` so the operator can confirm only the gitignored sync surface changed. The synced source tree is *not* tracked in this repo and should never appear in `git status` after this step.
10. Refuse to push, refuse to commit. The sync only writes into the gitignored working tree; the operator's only commits are to the tracked deploy contract (`bin/`, `docs/`, `README.md`, `CHANGELOG.md`).

### Validation step (run automatically before exiting non-zero)
After the rsync, run `bb compile:ata` (or at minimum `clj -A:dev -e '(require ...)'` on the main namespace) in the public repo's working tree to confirm the mirrored brick set is closed. A successful compile is the cheapest proof that no `:local/root` reference points outside the mirror. The script reports the result and exits non-zero if compilation fails.

### Why manual sync rather than subtree/submodule
- The upstream repo is private and contains other subprojects (`agent-web-app`, `fulcro-rad-app`, …) plus a large component pool we don't want to expose, even via subtree history.
- A copy with a recorded SHA gives perfect provenance without leaking history.
- It is trivially reversible and easy for any contributor to understand.
- A subtree/submodule would also force us to expose the entire monorepo's git history, which contains internal discussions and unrelated subproject work.

---

## 4. Artifacts

The binary file name is **`by`** (matching the upstream). The release asset names use `by-<version>-…` for the platform binaries and `by-<version>.jar` for the uberjar so the convention is consistent across artifacts.

### 4.1 Clojure uberjar (`by-<version>.jar`)
- Built by `bb uberjar:ata` (which runs `bb compile:ata` first via the chained `bb build:ata`, or independently if CI prefers parallelism). Under the hood: `clj -M:uberdeps --aliases :build --main-class ai.brainyard.agent_tui_app.main --target target/agent-tui-app.jar`.
- AOT'd `-main` namespace (`ai.brainyard.agent-tui-app.main`); runnable with `java -jar by-<version>.jar` on any JDK 21+.
- The `bb uberjar:ata` task also asserts that `META-INF/native-image/ai.brainyard/agent-tui-app/native-image.properties` survived bundling — if uberdeps ever drops it, native builds silently fall back to defaults. CI must surface that failure.
- Distributed as an attached asset on the GitHub Release: `by-<version>.jar`, plus a stable-filename copy `by.jar` so `/latest/download/by.jar` URLs stay valid.
- Trade-off: requires Java 21+, ~3–5 s cold start, but full Clojure runtime — useful when users want to pull Brainyard into their own JVM workflows or when reporting native-image bugs (the wrapper supports `BY_JAR=1` to switch modes).

### 4.2 Native binary (GraalVM `native-image`)
- One binary per platform, built in CI via matrix job (binary name is just `by` inside the archive; the release asset name encodes the platform):
  - `by-<version>-linux-amd64`
  - `by-<version>-linux-arm64`
  - `by-<version>-macos-amd64`
  - `by-<version>-macos-arm64`
  - `by-<version>-windows-amd64.exe` (gated on §9 Q4 — likely **deferred** for v1; see Open Questions)
- Built by `bb native:ata`, which finds `native-image` on PATH, in `JAVA_HOME`, or by probing standard macOS GraalVM install dirs. All build flags live in `projects/agent-tui-app/resources/META-INF/native-image/ai.brainyard/agent-tui-app/native-image.properties` (auto-discovered by `native-image` from inside the uberjar) — CI does not pass init/reflection flags on the command line.
- The committed `reflect-config.json`, `resource-config.json`, `proxy-config.json` are authoritative. `bb check:ata` runs as a static drift gate in CI to catch unexpected modifications (file present, non-empty, under a line-count ceiling).
- macOS quirk: `bb install:ata` re-applies an ad-hoc codesign after `cp` because `native-image`'s `linker-signed,adhoc` signature is inode-bound and AMFI kills the copy. The public `bin/install.sh` must do the same on Darwin.
- Distributed as attached assets on the GitHub Release plus SHA-256 checksums (`SHA256SUMS`) and a `BUILD-INFO.txt` provenance file (upstream SHA, branch, sync timestamp, version, build timestamp — written by `bin/release-stage.sh`). The `scripts/by-wrapper.sh` is also shipped as a release asset (or inlined by `bin/install.sh`) so installs land `by` (wrapper) + `by-bin` (native) + `by.jar` (optional uberjar) into the install dir.
- Build sizing: upstream reports ~147 MB arm64 binary, ~1.5 s cold start, ~2 min build per matrix leg on GitHub-hosted runners.
- Trade-off: best UX (single binary, no runtime), but the build matrix takes 10–20 min per release end-to-end and may need reflection config refresh when new deps are added (run `bb tracing:ata` upstream, commit, re-sync).

### 4.3 Babashka script — **deferred to a future iteration**
The original v0.1 design proposed a `brainyard.bb` artifact distributed via `bbin`. After verifying upstream runtime dependencies (sqlite-jdbc, cognitect.aws, logback, mulog, com.brunobonacci/mulog, datomic-adjacent code paths via components), a pure-Babashka build is not viable without significantly slimming the dependency graph — these libs require a full JVM. The bb-script artifact and `bbin.edn` are therefore **dropped from v1**.

If a bb-friendly subset surfaces later (e.g. a thin `by ask` client that talks to a daemon), it can be added as a third artifact without changing the release workflow's shape. Until then the README documents two install paths only: `curl | bash` and `java -jar`.

### 4.4 Versioning
- Semantic versioning, tags shaped `vMAJOR.MINOR.PATCH` (e.g., `v0.1.0`, `v0.2.0`). Tag push on `main` triggers the release workflow.
- Pre-releases use `vX.Y.Z-rc.N`; the workflow marks the GitHub Release as prerelease automatically when the tag contains a hyphen.
- Upstream owns the source-of-truth version string at `projects/agent-tui-app/src/ai/brainyard/agent_tui_app/main.clj` (`app-version`). The release workflow asserts that the git tag's stripped-`v` form matches `app-version`; mismatches abort the release rather than ship a confused version on the asset names.

---

## 5. Install UX

Two install paths in v1 — both land on the same GitHub Release. The README leads with the easiest first.

### 5.1 `curl | bash` (native binary, recommended for most users)

```bash
curl -fsSL https://raw.githubusercontent.com/grumatic/brainyard/main/bin/install.sh | bash
```

`bin/install.sh` responsibilities:
- Detect OS (`uname -s`) and arch (`uname -m`), map to release asset name (`by-<version>-{linux,macos}-{amd64,arm64}`).
- Resolve the latest release tag via `https://api.github.com/repos/grumatic/brainyard/releases/latest` (or accept `BY_VERSION=vX.Y.Z` to pin — env var name matches upstream's `BY_*` namespace).
- Download three files from the release:
  1. The matching native binary → install as `~/.local/bin/by-bin`.
  2. `by-wrapper.sh` → install as `~/.local/bin/by` (the wrapper users actually invoke). The wrapper sources `.env` and `exec`s `by-bin`.
  3. `SHA256SUMS` → verify SHA-256 of all downloaded files before installing.
- Also pull `by.jar` if `--with-jar` is passed (off by default to save bandwidth); enables `BY_JAR=1 by …` JVM fallback mode.
- On macOS: re-apply an ad-hoc codesign to the copied binary (`codesign --force --sign - ~/.local/bin/by-bin`). Without this, AMFI SIGKILLs the binary on first launch.
- `chmod +x` both `by` and `by-bin`; honour `--prefix=/usr/local` (prompting for `sudo` only if needed).
- Print a short post-install hint: ensure `~/.local/bin` is on `PATH` and run `by --help`.

The installer must be small, dependency-free (POSIX `sh`, `curl`, `shasum`/`sha256sum`, and `codesign` only when running on Darwin), and idempotent.

### 5.2 `java -jar` (JVM users)

```bash
curl -LO https://github.com/grumatic/brainyard/releases/latest/download/by.jar
java -jar by.jar --help
```

(The release workflow uploads the latest jar under the stable filename `by.jar` in addition to the versioned `by-<version>.jar`, so the `/latest/download/` URL stays valid.)

### 5.3 Verification
The release page lists `SHA256SUMS` covering every asset (binaries, jar, and the wrapper script). The README shows a one-liner so security-conscious users can verify their download before piping the installer to `bash`.

---

## 6. README Outline

`README.md` is the front door. Keep it under one screen of scrolling for the install section.

1. **What Brainyard is** — one paragraph describing `by`, the agent TUI: what problem it solves, who it is for, what makes it interesting.
2. **Screenshot or asciinema cast** — embedded GIF in `docs/img/` showing the TUI in 10 seconds.
3. **Install** — two sections in this order: curl one-liner, `java -jar`. Each is one code block. (No `bbin` path in v1 — see §4.3.)
4. **Quick start** — three commands the user can run immediately after install:
   - `by --help`
   - `by agents` (lists available agents — visible & cheap)
   - `by ask -m haiku 'What is 2+2?'` (one-shot question — produces a visible answer; the choice of `haiku` keeps the cost trivial).
5. **Documentation** — links to `docs/usage.md` and `docs/install.md`.
6. **Building from source** — short pointer for contributors covering: `sdk use java 25.0.3-graal`, `bb compile:ata`, `bb uberjar:ata`, `bb native:ata` (or the chained `bb build:ata`). Full details in a `CONTRIBUTING.md` (future).
7. **License** and **Acknowledgements**.

Tone: utilitarian, no marketing fluff. The point of the README is to get someone from "I heard about this" to "I ran it" in under 60 seconds.

---

## 7. GitHub Actions CI/CD

Two workflows. Both pinned to specific action SHAs for supply-chain hygiene. Both invoke upstream's existing `bb …:ata` tasks rather than reinventing build steps — the bb.edn is the contract, and CI is a thin wrapper.

### 7.1 `ci.yml` — runs on every PR and on push to `main`

```
on: [pull_request, push to main]
jobs:
  lint-and-test:
    runs-on: ubuntu-latest
    steps:
      - checkout
      - setup-java (GraalVM 25 — matches .sdkmanrc; bb compile:ata uses it for AOT)
      - setup-clojure (tools.deps + babashka)
      - cache ~/.m2 and ~/.gitlibs (keyed on deps.edn + project/*/deps.edn fan-out)
      - bb check:ata                  # static drift gate on committed META-INF/native-image configs
      - bb compile:ata                # AOT with *warn-on-reflection*; surfaces new reflection sites
      - bb uberjar:ata                # also asserts native-image META-INF survived bundling
      - bb poly:check                 # validate trimmed Polylith workspace
      - clj-kondo --lint bases components projects
      # Tests: scoped to bricks we actually ship. `bb test` upstream runs the
      # full polylith test set; in the public repo we run only mirrored
      # bricks' tests via `bb poly test :project agent-tui-app`.
      - bb poly test :project agent-tui-app
```

Fast feedback only — no native build in CI for PRs (10–20 min per leg is not worth it for review cycles). `bb uberjar:ata` running on PRs is enough to catch the majority of regressions cheaply, because it includes the META-INF survival check.

### 7.2 `release.yml` — runs on tag push matching `v*.*.*`

```
on:
  push:
    tags: ['v*.*.*']

jobs:
  build-jar:
    runs-on: ubuntu-latest
    outputs: { version: ${{ steps.v.outputs.version }} }
    steps:
      - checkout
      - id: v
        run: echo "version=${GITHUB_REF_NAME#v}" >> $GITHUB_OUTPUT
      - setup-java (GraalVM 25), setup-clojure, setup-babashka
      - bb compile:ata && bb uberjar:ata
      # Assert app-version inside the jar matches the tag.
      - verify-version: app-version == ${{ steps.v.outputs.version }}
      - rename target/agent-tui-app.jar → by-${VERSION}.jar
      - upload-artifact (by-${VERSION}.jar)

  build-native:
    needs: build-jar
    strategy:
      fail-fast: false
      matrix:
        include:
          - { os: ubuntu-latest,    asset: linux-amd64 }
          - { os: ubuntu-22.04-arm, asset: linux-arm64 }
          - { os: macos-13,         asset: macos-amd64 }
          - { os: macos-14,         asset: macos-arm64 }
    runs-on: ${{ matrix.os }}
    steps:
      - checkout
      - download-artifact (by-${VERSION}.jar) → projects/agent-tui-app/target/agent-tui-app.jar
      - graalvm/setup-graalvm (Java 25, native-image, march=compatibility for portability)
      - setup-babashka
      - bb native:ata                # reads flags from META-INF/native-image.properties inside the jar
      - rename projects/agent-tui-app/target/by → by-${VERSION}-${{ matrix.asset }}
      - (macOS only) codesign --force --sign - by-${VERSION}-${{ matrix.asset }}
      - upload-artifact

  publish-release:
    needs: [build-jar, build-native]
    runs-on: ubuntu-latest
    permissions: { contents: write }
    steps:
      - download all artifacts
      - copy by-${VERSION}.jar → by.jar     # stable filename for /latest/download/
      - copy projects/agent-tui-app/scripts/by-wrapper.sh → by-wrapper.sh
      - generate SHA256SUMS (covers binaries, jar, wrapper)
      - softprops/action-gh-release with:
          files: |
            by-*.jar
            by.jar
            by-*-linux-*
            by-*-macos-*
            by-wrapper.sh
            SHA256SUMS
          body_path: CHANGELOG-latest.md   # extracted from CHANGELOG.md
          prerelease: ${{ contains(github.ref_name, '-') }}
```

Notes on the matrix:
- Linux arm64 uses the `ubuntu-22.04-arm` runner (GitHub-hosted, available since 2025).
- macOS arm64 uses `macos-14`; macOS amd64 uses `macos-13` (last Intel runner).
- **Windows is deferred for v1.** The upstream `bb native:ata` task and `by-wrapper.sh` are POSIX-only; Windows would require an MSVC toolchain step (`ilammy/msvc-dev-cmd`), a `.cmd` wrapper, and `BY_*` env semantics adjusted for cmd.exe. Re-evaluate after v1 lands and demand is visible.

### 7.3 Secrets and permissions
- The workflow only needs the default `GITHUB_TOKEN` with `contents: write` to create releases. No PAT, no third-party secrets in v1.
- The upstream's `bb tui …` flow needs API keys for LLM providers at runtime, but CI never exercises those paths; release builds only AOT-compile and `native-image`. No secrets are required during the release job itself.
- If we later sign binaries (Apple Developer ID, Windows EV cert), add a separate `sign.yml` reusable workflow rather than threading signing keys through the release job.

---

## 8. Release Process (end-to-end)

The intended day-to-day flow for the maintainer:

1. In the dev repo, bump `app-version` in `projects/agent-tui-app/src/ai/brainyard/agent_tui_app/main.clj`, finish the change, run `bb compile:ata && bb uberjar:ata && bb native:ata` locally, commit, push.
2. In this repo, run `bin/sync-from-dev.sh`. The sync writes into the gitignored working tree only; `git status` should show no source files. Confirm the validation `bb compile:ata` printed at the end of the sync ran cleanly.
3. Update `CHANGELOG.md` with a new section for the upcoming version (matching `app-version`). Extract the latest section into `CHANGELOG-latest.md` (the release workflow uses it as release notes). Commit the CHANGELOG change only — there are no synced sources to commit.
4. Run `bb build:ata` to produce `target/agent-tui-app.jar` and `target/by`, then `bin/release-stage.sh` to copy them into `release/` with the asset names `bin/install.sh` expects, generate `SHA256SUMS`, and write `release/BUILD-INFO.txt` stamping the upstream SHA from `SYNCED-FROM.txt`.
5. Tag: `git tag v0.1.0 && git push origin main v0.1.0`. The tag's stripped-`v` form must equal `app-version` or the release workflow aborts.
6. The release workflow re-runs sync + build + stage on a clean runner (matrix per platform) and uploads `release/*` as assets. Watch it finish; artifacts appear on the Releases page.
7. Verify by running the `curl | bash` installer on a clean machine (or in a container) and exercising one quick-start command (`by ask -m haiku 'What is 2+2?'`).

---

## 9. Open Questions

Resolved during the v0.2 reality check:

- ~~**Upstream namespace / main class**~~ — confirmed: `ai.brainyard.agent-tui-app.main`, with `(:gen-class)`, declared `app-version "0.1.0"`. cli-matic owns the subcommand routing.
- ~~**Native-image readiness**~~ — confirmed: production-quality config is already committed and gated by `bb check:ata`. First public build does not need a fresh tracing pass.

Still open — these don't block the design, but need a call before v1 ships:

1. **License** — which license should the public artifacts ship under? (MIT, Apache-2.0, or company-specific?) **Recommendation:** Apache-2.0, with NOTICE crediting upstream OSS deps.
2. **Public repo / org name** — confirm the GitHub coordinates. The design assumes `grumatic/brainyard`; if the public org is different the install URL and asset coordinates need to follow.
3. **Brick set freezing** — the sync script's transitive-closure resolver is best-effort. Should we commit the resolved brick list (e.g. `bin/.brick-set`) so accidental brick additions in upstream are caught at sync time? **Recommendation:** yes, with the sync script diffing the resolved set against the committed list and refusing to proceed on mismatch unless `--allow-brick-set-change` is passed.
4. **Windows support** — deferred per §7.2. Confirm we're OK shipping Linux + macOS in v1 and adding Windows in a follow-up.
5. **Telemetry / update check** — should the binary phone home for update notifications? (Default position: **no**, but worth confirming. The `mulog` integration already writes locally to `~/.brainyard/logs/agent-tui-app.log` and that should be the ceiling.)
6. **Source distribution policy** — mirroring 12+ components publicly exposes implementation details (LLM provider wiring, sandbox policy, persistence schema) that haven't gone through a public-API review. Are there bricks that should be **redacted or replaced with stubs** before being mirrored? **Recommendation:** dry-run the sync against a private fork first, review the diff, then decide per-brick. Open this as a sub-investigation before M1.
7. ~~**Gist as a fallback?**~~ — Recommendation stands: skip for v1. GitHub Releases is the single source.

---

## 10. Rollout Plan

A suggested staged rollout once this design is approved:

- **M0 — Skeleton (1 day):** create `README.md`, `LICENSE`, `docs/install.md`, `docs/usage.md`, `.gitignore`, `bin/sync-from-dev.sh` (with brick-set resolver + validation step). No mirrored sources yet.
- **M0.5 — Brick-set review (0.5–1 day):** dry-run the sync into a private fork. Walk the resolved brick list with the team and decide per-brick whether to mirror, redact, or stub. Output: a committed `bin/.brick-set` allow-list.
- **M1 — First manual release (1–2 days):** run sync, build uberjar locally (`bb uberjar:ata`), build a native binary locally for the maintainer's platform (`bb native:ata`), draft a `v0.1.0` GitHub Release with the jar + a single native binary attached by hand. Validate the `java -jar` install path and the macOS-arm64 `curl | bash` path. Surface any reflection-config gaps before automating.
- **M2 — CI for jar (1 day):** ship `ci.yml` (lint + `bb compile:ata` + `bb uberjar:ata` + `bb check:ata` + `bb poly:check`) and a minimal `release.yml` that builds the jar on tag push.
- **M3 — Native binary matrix (2–3 days):** extend `release.yml` with the GraalVM matrix (Linux amd64/arm64, macOS amd64/arm64). Iterate on per-runner quirks (macOS codesign, JAVA_HOME pinning) until all four legs are green.
- **M4 — Polish (ongoing):** installer hardening (checksum verification by default, `BY_VERSION` pinning, `--with-jar` flag), CHANGELOG automation (split `CHANGELOG.md` into `CHANGELOG-latest.md` programmatically), asciinema cast in README, Homebrew tap (post-v1).

---

## Appendix A — Why these choices

- **Two artifact flavors in v1 (jar + native), bb script deferred** — the upstream app's dependency graph (sqlite-jdbc, cognitect.aws, logback, mulog) is not Babashka-compatible. Shipping a broken bb path would be worse than shipping no bb path; the door stays open for a slimmed-down bb client later.
- **GitHub Releases as the sole distribution channel (v1)** — keeps the install story explainable in one sentence and avoids onboarding a package-manager dependency before we know the user base. Homebrew/Scoop are clean additions later.
- **Manual sync, not submodule** — keeps the dev repo private and avoids surfacing internal subprojects via shared git history. The provenance file (`SYNCED-FROM.txt`) gives the auditability that a submodule would otherwise provide.
- **Mirror the Polylith subset rather than flattening to a single `src/`** — `agent-tui-app/deps.edn` uses `:local/root` to 13 sibling bricks. Flattening would force us to rewrite the deps graph, fork from the upstream build conventions, and re-debug every release. Mirroring preserves the existing, battle-tested `bb …:ata` pipeline as-is.
- **CI invokes `bb …:ata` directly** — the build pipeline is upstream's contract; rewriting it in `release.yml` would create two sources of truth that drift. CI is intentionally a thin shell around the bb tasks.
- **GraalVM 25 in CI** — matches the `.sdkmanrc` pin in upstream. The build is also verified against GraalVM 21.0.9, but pinning to the version upstream develops against minimizes "works locally, fails in CI" surprises.
- **Tag-driven CI** — simplest mental model: a tag is the only thing that produces a public release. No accidental publishes from `main` pushes.

---

## Appendix B — Changes from v0.1

Captured here so future readers can see what the original design got wrong and why:

- **Upstream path correction** — the project lives at `projects/agent-tui-app/`, not `agent-tui-app/`. The original assumption flattened the Polylith layout.
- **Polylith reality** — the original layout proposed a single `src/` mirror. This breaks because `deps.edn` uses `:local/root` to 13 sibling bricks; the mirror must preserve relative paths.
- **`bb.edn` location** — root-level, not inside the project. CI was originally going to call `clj -T:build uberjar`; the actual tooling is `bb uberjar:ata` → `clj -M:uberdeps`.
- **Binary name** — `by`, not `brainyard`. Asset names now reflect this.
- **Java version** — GraalVM 25 (per `.sdkmanrc`), not Temurin 17.
- **Babashka script artifact dropped from v1** — see Appendix A.
- **Native-image config already exists** — original design assumed first build would need a tracing pass. Not true; config is committed and gated by `bb check:ata`.
- **Wrapper script must ship** — `by-wrapper.sh` is part of the install contract upstream; the public installer needs to mirror that layout.
- **macOS codesign step** — added because `bb install:ata` upstream does it, and skipping it on macOS causes AMFI SIGKILL.
- **Windows native build deferred** — POSIX-only wrapper + bb tasks mean Windows needs separate work, not a single matrix entry.
- **Brick-set review added (M0.5)** — exposing 12+ internal components publicly is a non-trivial decision; the original design glossed over it.
