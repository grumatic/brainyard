# Changelog

All notable changes to Brainyard's public distribution are documented here. Versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- **User-defined hooks (`hook-agent`).** The agent can now author its own runtime hooks: `hooks$create` takes Clojure source and registers it as a persistent observer on a pre-defined Brainyard event (e.g. `:agent.tool-use/post`, `:agent.iteration/post`). Mirroring user-defined tools, the handler *source* is persisted to `.brainyard/hooks/<id>.edn` and re-evaled in a dedicated sandbox to rehydrate on fire and at session boot; the body composes the tool palette by direct symbol — `(write-file {…})`, `(bash {…})` — to enact its side effect. A `hooks$events` / `hooks$create` / `hooks$validate` / `hooks$list` / `hooks$read` / `hooks$delete` command family (mirroring `tools$*`) manages them, fronted by the new `hook-agent`. v1 is observer-only (gated events that block/modify/replace are reserved) and requires an explicit `:match` scope; safety rests on fail-open handler errors, a re-entrancy guard, and the `enable-user-hooks` kill-switch.
- **Hook-agent tutorial.** A new recorded walkthrough (`21-hook-authoring`) takes the `hook-agent` through the full hook lifecycle across three turns — discover the hookable events, author a persistent `audit-bash` hook from a plain-English request, then read it back to confirm it is registered and active.
- **Contain a session in a sandbox (`by --sandbox`).** A new launcher re-execs `by run` under macOS [`sandbox-exec`](https://keith.github.io/xcode-man-pages/sandbox-exec.1.html) (seatbelt) with a generated **write-containment** profile: reads, network and subprocess exec stay allowed (an agent's job is running tools and calling LLMs), but filesystem *writes* are confined to `~/.brainyard`, the project/cwd subtree, `$TMPDIR`/`/tmp`, `~/Library/Caches` and `/dev` — so an agent (or a tool it runs) can't clobber `~/.ssh`, `~/.aws/credentials`, `/etc`, or unrelated repos. The sandboxed session runs in the **same terminal** (unlike `--web`'s PTY-over-network). Add writable roots with `--sandbox-allow-write` (repeatable/comma-separated), cut off the network with `--sandbox-no-network`, or supply your own seatbelt profile with `--sandbox-profile`; all `--sandbox*` flags have `BY_SANDBOX_*` env equivalents. macOS-only (warns and runs unsandboxed elsewhere) and mutually exclusive with `--web` in v1; `sandbox-exec` is probed at runtime and is not a build dependency. New brick `components/os-sandbox`; full guide in [`docs/sandboxing.md`](docs/sandboxing.md).
- **Set the working directory (`by --working-dir`/`-C`).** You can now point a session at a working directory other than the launch cwd, so file-touching tools (`bash`, `read-file`, `write-file`, `grep`) and the functional agents resolve relative paths — and land their `.brainyard/` artifacts — where you intend. Precedence: `--working-dir`/`-C` flag > `BY_WORKING_DIR` env > the process cwd. The flag is strict (a non-existent / non-directory path exits 1); a bad `BY_WORKING_DIR` env value falls back to the cwd. The project root (where `.brainyard/` lives) re-derives by walking up to the nearest `.git` from the effective working dir, unless **`BY_PROJECT_DIR`** explicitly overrides it. No real `chdir` happens — a JVM can't change its process cwd at runtime — the value is threaded through config and applied to spawned subprocesses and path resolution. The `--web`/`--sandbox` launchers forward `-C` into their re-exec'd child. Documented in `.env.example` and `CLAUDE.md`.

### Changed

- **Unified, generalized user-feedback mechanism.** The TUI now binds a single interactive-input primitive to each session (`:user-feedback-fn`) that dispatches on a request `:kind` — `select` (pick from 2–6 options, the historical behavior and the default), `text` (free-form line), or `confirm` (single-key choice — file-access permission uses yes/no/always/never, the last two remembered per session). Each kind renders through whichever backend is available — raw in-stream live-block, non-raw stdin, or (optionally, when feasible) a tmux popup — so the popup is just one optional backend, not a special case. File-access **permission** is now a thin adapter over this primitive (a `:confirm` request): it keeps only path normalization and its per-session approved-dir cache and delegates all prompting, collapsing the duplicate pending-state channel, lock, and raw-byte interceptor branch into one. The `get-user-feedback` tool gains a `kind` argument (`select`/`text`/`confirm`); existing positional `select` calls — `(get-user-feedback "q" ["a" "b"])` — are unchanged. A new `enable-tmux-popup` config key (default `true`) gates the popup backend — set it `false` to force every prompt to the in-stream live-block.
- **Reviewable persistence for user-defined tools & hooks.** Each definition is now saved as a human-readable pair — a pretty-printed metadata `.edn` plus a `.clj` sidecar holding the body as **verbatim** Clojure source (`#()`, regex, quotes preserved exactly; no escaping; opens with editor highlighting) — instead of a single dense one-line `.edn` with the body as an escaped string. A shared `def-store` ns handles both `tools$*` and `hooks$*`; reads still go through the safe `clojure.edn` reader and transparently fall back to the legacy single-file format, which migrates to the pair on its next overwrite.
- **`BRAINYARD_PROJECT_DIR` renamed to `BY_PROJECT_DIR`.** The project-root override env var now follows the `BY_*` convention shared by `BY_USER_ID`, `BY_WORKING_DIR`, `BY_WEB_*`, and `BY_SANDBOX_*`. Straight rename with no legacy alias — update any script or `.env` that set the old name.

## [v0.2.6] — 2026-06-04

### Added

- **Share a session over the web (`by --web`).** A new launcher wraps `by run` in [ttyd](https://github.com/tsl0922/ttyd) so a TUI session is reachable in the browser — and shared by all connected clients. Two modes: `--web` serves a fresh session (Tier 1), and `--web-tmux` runs the TUI in a private detached tmux session that several clients attach to and drive live (Tier 2). With `--web-tmux` the launching terminal stays a **dashboard** so the connection info (URL + credentials) remains visible to copy and share; drive locally from another terminal with the printed `tmux attach` command, or just open the URL. Auth is always required (password auto-generated if unset), binding defaults to localhost, and origin-checking is always on; `ttyd`/`tmux` are probed at runtime and are not build dependencies. All `--web*` flags have `BY_WEB_*` env equivalents. New brick `components/web-share`; full guide in [`docs/web-sharing.md`](docs/web-sharing.md).

## [v0.2.5] — 2026-06-03

### Added

- **Tool-agent tutorial.** A new recorded walkthrough (`18-creating-tools`) takes the `tool-agent` through the full user-defined-tool lifecycle across three turns — discover existing tools, author a persistent `count-words` tool from a plain-English request, then read it back and run it on a real file. The `docs/tutorials/` README catalog was also backfilled to list every walkthrough (09–18).

### Changed

- **Auto-background detach default raised 30s → 120s.** A code block (clojure / bash / python / javascript) now runs synchronously in the foreground for up to 120s before detaching into a background task, up from 30s — set by the `:auto-background-timeout-ms` config default. Longer evals stay inline (with live output) before backgrounding; the value is still per-agent configurable.

## [v0.2.4] — 2026-06-03

### Added

- **User-defined tools.** The agent can now author its own tools at runtime: `tools$create` takes Clojure source and turns it into a first-class, persistent, discoverable tool (auto-bound as `user$<name>`, flowing through the same Malli coercion, hook/permission, and depth guards as built-ins). Because SCI closures aren't EDN-serializable, the tool *source* is persisted to `.brainyard/tools/<name>.edn` and re-evaled in a dedicated sandbox to rehydrate on call and at session boot. A uniform `tools$create` / `tools$list` / `tools$read` / `tools$delete` command family (mirroring `skills$*`) manages them.

### Changed

- **Relicensed from Apache-2.0 to MIT.** The project now ships under the [MIT License](LICENSE), copyright Grumatic, Inc. The `LICENSE` file, all source-file SPDX headers, and the `bb license:*` tooling were updated accordingly; the Apache-specific `NOTICE` file was removed, and a `CONTRIBUTING.md` was added (inbound = outbound MIT). A `bb license:migrate` task performs the one-time header rewrite. Note: MIT carries no express patent grant, unlike Apache-2.0.

### Fixed

- **Installer JVM fallback.** `bin/install.sh` now falls back to the JVM uberjar on platforms with no published native binary (Linux, Intel macOS), installing a `by` launcher that runs `java -jar` (requires JDK 21+). A stable `by.jar` alias is staged alongside the versioned asset.
- **Polylith workspace check.** Resolved `check` errors 101 (route `bootstrap-driver` through the `agent` interface) and 107 (declare `clj-http-native` explicitly in the project deps). `bb poly check` is now clean.

## [v0.2.3] — 2026-06-01

### Added

- **Startup user identity.** `by` now resolves a real per-user identity at startup instead of the fixed `tui-user`/`cli-user` placeholders. Precedence: `--user-id`/`-u` flag > `BY_USER_ID` env > the `user.name` system property (OS login) > `by-user`. Sessions and memory (`~/.brainyard/memory/<user-id>.db`) key on it, so your history is scoped to you out of the box. `BY_USER_ID` is documented in `.env.example` and `CLAUDE.md`.
- **Gmail & Google Calendar MCP servers.** Added `gmail` and `google-calendar` to the built-in MCP server set. Both bridge Google's official hosted MCP endpoints (`gmailmcp.googleapis.com` / `calendarmcp.googleapis.com`) via `mcp-remote` (mirroring the notion/linear pattern). They read a pre-registered OAuth client from `GCP_OAUTH_CLIENT_ID`/`GCP_OAUTH_CLIENT_SECRET` (documented in `.env.example`), ship `:enabled false`, and connect on first `/mcp gmail start` (browser consent).

### Fixed

- **`/mcp` server list rendering.** Server names no longer show a literal `:bold` prefix (e.g. `:boldnotion`) — a keyword was passed to `ansi/style` where it expects an ANSI escape-code string.

### Notes

- The user-id change means default runs no longer read the old `tui-user`/`cli-user` memory stores; a default run now scopes to your OS login (`<user.name>.db`). Existing placeholder stores are left untouched on disk.

## [v0.2.2] — 2026-06-01

**Opus is now the default model.** The out-of-box default LM changes from Sonnet to Opus.

### Changed

- **Default LM → Opus.** With no `-m`/config override, `by` now defaults to `claude-code:opus` (most capable Claude via the CLI, no API key). The `-p anthropic` provider default is the latest `claude-opus-4-7`, and `by config --auto` now selects `claude-code:opus`. Override anytime with `-m sonnet`/`-m haiku` or `LM_MODEL`.

### Fixed

- **`:dev` alias.** Trimmed `deps.edn`'s `:dev` alias to the mirrored brick set (17 components + 2 bases); it had listed ~28 components/bases not present in this repo, breaking `bb repl` / `bb repl:test` with "Local lib not found".

### Removed

- Dropped `bb` tasks targeting non-mirrored upstream projects (`repl:fra/ea/ra/awa`, `shadow:fra/awa`) — they could never run here.

## [v0.2.1] — 2026-06-01

Documentation and tooling only — no binary or behavior changes since v0.2.0. The `by` binary is functionally identical to v0.2.0 (only the reported `--version` differs).

### Added

- **Hosted tutorials.** The recorded walkthroughs are now published to GitHub Pages at [grumatic.github.io/brainyard](https://grumatic.github.io/brainyard/) and linked from the README.
- **`bb tutorial:publish`.** Regenerates the self-contained tutorials page and force-pushes it to the `gh-pages` branch (`scripts/asciinema/publish-pages.sh`, via git plumbing — no working-tree churn).
- **`.env.example`.** Credential template covering the key-based LLM providers (Anthropic, OpenAI, Google, Groq, Mistral, DeepSeek, …) and AWS/Bedrock (`AWS_PROFILE`/`AWS_REGION`). README install flow now points users to copy it.
- **Upstream `scripts/` migrated.** The asciinema tutorial pipeline (`scripts/asciinema/*`), the license-header tool, and TUI test helpers — so `bb tutorial:output`/`record`/`verify` run locally.
- **Upstream `development/` migrated.** The Polylith `:dev` project source (`development/src`) and the `dev.repl-test` helper behind `bb repl:test`.

### Changed

- **`docs/usage.md` rewritten** against the actual v0.2.0 CLI: corrected session flags (`-r/--resume`/`--select-resume` replace the removed `-s/--session-id`), split `run`-only flags from `ask`, documented `by config` as the `config.edn` bootstrap pipeline, expanded the provider list, and fixed the environment-variable table.

### Removed

- Dropped the `docs/reference/CCA.md` and `docs/reference/OPTRA-CODER.md` reference docs and the `docs/*.pptx` slide decks (purged from history to keep the repo lean).

## [v0.2.0] — 2026-06-01

**Brainyard is now open source.** This release publishes the full Polylith workspace — sources, build config, and docs — directly in this repository. Earlier releases (v0.1.x) shipped binaries only, built from a private upstream via a sync wrapper; that machinery has been retired and development now happens here. The codebase is licensed under Apache-2.0, copyright Grumatic, Inc.

### Highlights since v0.1.1

- **Interactive tutorials.** A full asciinema-based tutorial suite under [`docs/tutorials/`](docs/tutorials/) — 17 recorded scenarios with a vendored player, turn-by-turn walkthroughs, and a CI golden-frame drift gate.
- **MCP improvements.** `config.edn`-backed MCP servers, runtime skill/MCP registration, a `:lazy` connect flag, per-server connect knobs, a startup status banner, and non-blocking per-server connect with background skill scanning.
- **TUI polish.** Reworked session-resume UX (ordered by update time), full prompt echo with wrapping, configurable collapsed/expanded line limits, and assorted rendering fixes.
- **Safer agent writes.** Shared write-guards (secret scanning + size caps) across memory, workflow, plan/exec/eval, and bootstrap dossiers; secret scanning in `config$apply`.
- **Security-gated nREPL** (`clj-nrepl` component): confirm/grant flows, audit, and drift detection for REPL eval.
- **Performance.** Cached MCP tool listing, faster `explore$find` (INDEX.md first), and fewer redundant routing-log reads.

### Artifacts

- `by-0.2.0.jar` — Clojure uberjar. Runs on JDK 21+.
- `by-0.2.0-macos-arm64` — native (GraalVM) binary.
- `by-wrapper.sh` — wrapper shell script (sources `.env`, execs the native binary).
- `SHA256SUMS` — checksums.
- `BUILD-INFO.txt` — version, platform, build timestamp, and source commit.

### Known gaps

- **Linux** and **macOS amd64** binaries are not in v0.2.0 — use the uberjar on those platforms.
- **Windows** is deferred.

---

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
