# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo actually is

A **thin sync-build-publish wrapper** around a private upstream Clojure/Polylith repo. The tracked surface is intentionally tiny (~11 files: sync script, installer, release-stage script, docs, LICENSE, CHANGELOG). Everything that looks like the application — Clojure sources, `bb.edn`, `deps.edn`, `workspace.edn`, `.sdkmanrc`, `.clj-kondo/`, `projects/`, `bases/`, `components/` — is **gitignored** and pulled into the working tree on demand by `bin/sync-from-dev.sh`.

Practical consequence: after a fresh clone, **`bb` commands will not work** until you run sync. Editing the synced files is also pointless — every sync overwrites them. Real code changes belong in the upstream dev repo.

## Required environment

- `BRAINYARD_DEV_REPO` — absolute path to the private upstream. Default `~/Projects/MyDev/brainyard`. The sync script fails fast if missing.
- GraalVM 25.0.3+ on `PATH` (or via `.sdkmanrc` + SDKMAN). The upstream `bb native:ata` task probes `PATH`, `JAVA_HOME`, and `/Library/Java/JavaVirtualMachines/`.
- `bb` (Babashka) and `clojure` CLI.
- `gh` CLI for release publishing.

## The pipeline

The whole point of this repo. Run in this order:

```bash
bin/sync-from-dev.sh                # pull Polylith subset + build config from upstream
                                    # (add --allow-dirty if upstream has untracked files)
bb build:ata                        # compile → uberjar → native binary (~3 min)
bin/release-stage.sh                # copy to release/, stamp BUILD-INFO.txt, gen SHA256SUMS
gh release create vX.Y.Z release/* --notes-file CHANGELOG-latest.md
```

### Tagging discipline (critical)

The binary's `--version` string is **baked at build time from `git describe` of this wrapper repo** (not upstream). So the wrapper's tag IS the release version. Workflow for a new release:

1. Update `CHANGELOG.md` and `CHANGELOG-latest.md`, commit.
2. `git tag vX.Y.Z` at HEAD.
3. `bin/sync-from-dev.sh` (re-pulls upstream).
4. `bb build:ata` — `bb version:ata` runs first, stamps `resources/build-version.edn` from `git describe`.
5. `bin/release-stage.sh` — **refuses to stage** if the describe output is `-dirty`, `-N-gabc123` (commits past tag), or `dev`. These would bake a misleading version into a public binary.
6. `gh release create vX.Y.Z release/* …`

Committing after tagging puts the repo into post-tag state (`v0.1.1-1-g…`); `release-stage.sh` will reject builds from this state. To re-release after a doc fix, move the tag (`git tag -f vX.Y.Z`) and re-build.

## Key files (tracked)

- `bin/sync-from-dev.sh` — pulls the Polylith subset transitively required by `agent-tui-app`. Validates the closure by running `bb compile:ata` post-sync. Diffs the resolved brick set against `bin/.brick-set` (tripwire for accidental new components leaking out via upstream).
- `bin/release-stage.sh` — packages `target/` outputs into `release/` with the exact asset names `bin/install.sh` expects. Reads version from `projects/agent-tui-app/resources/build-version.edn`.
- `bin/install.sh` — public `curl | bash` installer. Resolves the latest release tag via the GitHub API, downloads platform-matched assets, verifies SHA-256, re-codesigns on macOS.
- `bin/.brick-set` — committed allow-list of mirrored bricks. Sync aborts on mismatch unless `--allow-brick-set-change`.
- `docs/deploy-design.md` — full release architecture and rollout plan. Read this before changing anything in `bin/` or the release flow.

## What "works" without sync

Almost nothing. You can read docs, edit the sync/install/release scripts, and run `gh` commands against existing releases. To compile, test, or invoke the binary, sync first.

## Testing

This repo has no test suite of its own. The sync script's post-sync `bb compile:ata` is the closure-check; further testing happens upstream. After a build, smoke-test the binary directly:

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

Upstream uses `:<two-letter>` suffixes per project. **All tasks relevant here end in `:ata`** (agent-tui-app): `compile:ata`, `uberjar:ata`, `native:ata`, `build:ata`, `install:ata`, `version:ata`, `check:ata`, `size:ata`. Tasks ending in `:fra`, `:awa`, etc. belong to upstream projects not mirrored here.

## When upstream changes break the wrapper

Watch for upstream changes that move where information lives — they can silently break `release-stage.sh`. The most recent example: upstream moved `app-version` from a string literal in `main.clj` to a runtime read from `resources/build-version.edn` stamped by `bb version:ata`. The fix was updating `release-stage.sh` to read the new file. If you see release-stage failing to find a version, check whether upstream restructured the version mechanism.
