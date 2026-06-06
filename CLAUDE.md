# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

The public source for **Brainyard** — an agent-driven terminal UI for working with LLMs from the command line. The shipping binary is `by`. The codebase is a [Polylith](https://polylith.gitbook.io/) workspace: a curated subset of bricks (`bases/`, `components/`) composed into the `agent-tui-app` project, built to a GraalVM native binary and a JVM uberjar.

This repo was seeded from the upstream `v0.2.0` snapshot and is now the source of truth — develop here directly. (Earlier `v0.1.x` releases were published from a thin sync-wrapper model that has since been retired.)

## Required environment

- GraalVM 25.0.3+ on `PATH` (or via `.sdkmanrc` + SDKMAN). The `bb native:ata` task probes `PATH`, `JAVA_HOME`, and `/Library/Java/JavaVirtualMachines/`.
- `bb` (Babashka) and the `clojure` CLI.
- `gh` CLI for release publishing.
- **Optional, runtime-only:** `ttyd` for `by --web` (browser-shared sessions);
  `tmux` additionally for `by --web-tmux`. Probed at runtime — neither is a
  build dependency. See `components/web-share` and `docs/web-sharing.md`.
  `sandbox-exec` (ships with macOS) backs `by --sandbox` — probed at runtime,
  macOS-only, not a build dependency. See `components/os-sandbox` and
  `docs/sandboxing.md`.

## Runtime configuration (env vars)

`by` reads provider credentials and a few control flags from the environment. A
real shell env var always wins; otherwise the binary loads the nearest `.env`
(walking up from cwd, then `~/.brainyard/.env`) — see `.env.example` for the
full annotated template and `projects/agent-tui-app/src/.../dotenv.clj` /
`scripts/by-wrapper.sh` for the loader.

- **`BY_USER_ID`** — user identity stamped onto sessions and memory (L1/L2/L3 are
  partitioned by it). Resolved once at startup: `--user-id`/`-u` flag >
  `BY_USER_ID` > the `user.name` system property (OS login) > `"by-user"`.
- **`BY_WORKING_DIR`** — effective working directory for tools/agents (no real
  JVM chdir; threaded through config). Resolved once at startup: `--working-dir`/`-C`
  flag > `BY_WORKING_DIR` > the process cwd (`user.dir`). The flag is **strict** (a
  non-directory path exits 1); a bad `BY_WORKING_DIR` env value silently falls back
  to cwd. `project-dir` (where `.brainyard/` artifacts land) re-derives from it via
  git-root walk, unless **`BY_PROJECT_DIR`** explicitly overrides the project root.
  The `--web`/`--sandbox` launchers forward `-C` into the re-exec'd child.
- **`AWS_PROFILE`** — Bedrock credential profile (`AWS_DEFAULT_PROFILE` is **not** honored).
- **`BY_JAR=1`** — run the uberjar instead of the native binary (reflection-config debugging).
- **`BY_ENV_FILE`** / **`BY_NO_DOTENV=1`** — force a specific `.env`, or skip `.env` discovery.
- **`BY_WEB`, `BY_WEB_*`** — web-sharing defaults (one per `--web*` flag; flag
  wins). The `--web` launcher sets **`BY_WEB_CHILD=1`** on the ttyd child as a
  re-entrancy guard so the relaunched TUI runs in-process instead of spawning
  another ttyd. See `docs/web-sharing.md`.
- **`BY_SANDBOX`, `BY_SANDBOX_*`** — seatbelt sandbox defaults (one per
  `--sandbox*` flag; flag wins). Default policy is **write-containment**: reads,
  network and subprocess exec are allowed, writes are confined to `~/.brainyard`,
  the cwd subtree, `$TMPDIR`/`/tmp`. The `--sandbox` launcher sets
  **`BY_SANDBOX_CHILD=1`** on the re-exec'd child as the re-entrancy guard.
  Mutually exclusive with `--web` in v1; macOS-only. See `docs/sandboxing.md`.

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

Web-sharing smoke test (needs `ttyd` on PATH; `BY_WEB_SELF` points ttyd's child
at a stand-in so the full TUI doesn't boot). Open the URL or `curl` it, then
Ctrl-C — `by` should reap ttyd and free the port:

```bash
BY_WEB_SELF=cat projects/agent-tui-app/target/by --web --web-port 7681 --web-pass test
# elsewhere:  curl -s -o /dev/null -w '%{http_code}\n' -u by:test http://127.0.0.1:7681/   # → 200
#             curl -s -o /dev/null -w '%{http_code}\n'           http://127.0.0.1:7681/    # → 401
```

Sandbox smoke test (macOS only). `BY_SANDBOX_SELF` points the seatbelt child at a
stand-in script so the full TUI doesn't boot — the launcher injects a `run`
subcommand token, so the stand-in must ignore its args:

```bash
cat > /tmp/by-probe.sh <<'EOF'
#!/bin/sh
echo x > /etc/x 2>&1 || echo write-blocked-ok          # denied: outside allowlist
echo ok > "$HOME/.brainyard/e2e" && echo brainyard-write-ok   # allowed
EOF
chmod +x /tmp/by-probe.sh
BY_SANDBOX_SELF=/tmp/by-probe.sh projects/agent-tui-app/target/by --sandbox
# → "write-blocked-ok" then "brainyard-write-ok"
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

## Design decisions

### Task output files are GC-reclaimed, not deleted on task removal

Each task gets a project-scoped dir `<project>/.brainyard/tasks/<task-id>/`
holding `output.log` (combined stdout+stderr) and `meta.edn` (lifecycle
snapshot). The LLM reads these back after completion via `task$detail` /
`format-task-output`.

**Decision (2026-06-06): task removal and artifact removal are intentionally
decoupled.** `agent/remove-task` (the protocol method behind the `/task del`
command) only drops the in-memory registry entry; it leaves `output.log` /
`meta.edn` on disk for post-mortem inspection. Disk reclamation is the GC
layer's job — `gc/sweep-tasks!` via the `task$sweep` command, bounded by
`:task-retention-count` (default 100) and `:task-retention-days` (default 7) in
`core.config/config-schema`. The sweep skips dirs whose `meta.edn` reports a
live task.

So output files **outlive** task removal and are reclaimed in bulk by the
retention sweep, rather than dying synchronously with the task. An opt-in
helper `manager/remove-task-and-artifacts!` exists for immediate cleanup
(removes the row *and* calls `persist/delete-task-dir!`), but it is deliberately
**not** the default path and is not wired into `/task del`. See the retention
note in `components/agent/src/ai/brainyard/agent/task/persist.clj`.

## bb task naming convention

Tasks for the shipping project end in `:ata` (agent-tui-app): `compile:ata`, `uberjar:ata`, `native:ata`, `build:ata`, `install:ata`, `version:ata`, `check:ata` (native-image config drift gate), `size:ata`, `repl:ata`, `tracing:ata`, `docker:ata`. Workspace-wide tasks (`test`, `poly`) have no suffix.
