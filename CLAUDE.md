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

- **`BY_USER_ID`** — user identity stamped onto sessions and memory; **memory**
  (L1/L2/L3) is partitioned by it under `~/.brainyard/memory/<user-id>.db`.
  Resolved once at startup: `--user-id`/`-u` flag >
  `BY_USER_ID` > the `user.name` system property (OS login) > `"by-user"`.
  Note: persisted TUI **sessions** are **project-scoped**, not user-scoped — they
  live under `<project>/.brainyard/sessions/<id>/` (a session belongs to one repo),
  so `by sessions list` / `--resume` only surface the current project's sessions.
  The app installs that root at startup via `agent/sessions-root` → `persist/set-root!`.
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
- **`BY_MEMORY_SELF`** — override for how the interactive TUI re-execs itself to
  run the **detached session-end memory consolidation**. On a graph-mode root
  session close the TUI hands the session's L2→graph→L3 tail to a detached
  `by memory reduce -u <uid> -s <sid>` child (surviving `/quit` via a
  `trap '' HUP INT TERM` + `setsid`/`perl` new-session detach), so `/quit` never
  blocks on minutes of extraction + community summaries. The child is resolved to
  the **real** binary (native-image self path, else `which by`) — deliberately
  **not** via `BY_WEB_SELF`, which is often a ttyd stand-in (`BY_WEB_SELF=cat`)
  and would silently misfire the reduce. Set `BY_MEMORY_SELF` (whitespace-split,
  e.g. a dev `clojure -M -m … run` command) to point that re-exec elsewhere for
  source/dev testing; parallel to `BY_WEB_SELF` / `BY_SANDBOX_SELF`. Unset ⇒ real
  binary. Falls back to a bounded in-process flush when the child can't be
  spawned. Impl: `spawn-detached-reduce!` / `reduce-self-argv` in the app `main`.
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
- **`BY_ENABLE_GRAPH_MEMORY`** — opt into the **context-graph memory** overlay
  (`:enable-graph-memory`, default **false**): a typed entity/relationship graph
  + vector index layered over the L1/L2/L3 FTS store as extra recall signals.
  Off by default and non-regressing (empty graph ⇒ recall == pure FTS). Design:
  `docs/design/context-graph-memory-design.md`; impl in `components/memory`
  (CR-MEM-20..24). The remaining graph knobs only take effect when this is on:
  - **`BY_GRAPH_EMBED_MODEL`** — the semantic-similarity embedder
    (`:graph-embed-model`). Two forms: **`static`** = the self-contained,
    in-binary **Model2Vec** embedder (`potion-base-8M`, 256-dim, pure-JVM, no
    server) bundled by `bb model2vec:fetch`; or a **`provider/model`** LM string
    routed through clj-llm's OpenAI-compatible `/embeddings` (e.g.
    `ollama/nomic-embed-text` (768-dim, local), `openai/text-embedding-3-small`).
    **Unset ⇒ no vector signal** (graph + relational recall still work). Note:
    Bedrock/Anthropic chat models can't embed — use `static`, Ollama, or OpenAI.
  - **`BY_GRAPH_EXTRACT_MODEL`** — chat LM (`provider/model`, e.g.
    `bedrock/amazon.nova-lite-v1:0`) that extracts entities/relationships from
    episodes and writes community summaries. **Unset ⇒ graph stays storage-only**
    (manual edge API; no self-population). The extractor asks for a fixed JSON
    schema: providers with native structured output (OpenAI/Google/Groq/… —
    `:supports-json-schema? true`) get API-level enforcement; providers without
    it (**Bedrock**, Anthropic, Ollama) have the schema appended to the system
    prompt instead (clj-llm `chat-completion` injects it), so `bedrock/*` models
    do extract. Watch `::extracted {:entities N :relations M}` in the app log to
    confirm a model is actually yielding entities (0/0 ⇒ the model is ignoring
    the JSON contract — pick a stronger extract model).
  - **`BY_GRAPH_EMBED_DIMS`** — `graph_vec` vector dimension (default 768). Must
    match the embed model's output; `static` auto-drives it to 256. Changing the
    embed model fingerprint-mismatches the index, which **pauses** vector recall
    (a startup banner + `memory$status` flag it) until `memory$reembed` rebuilds.
  - **`BY_SQLITE_VEC_PATH`** / **`BY_MODEL2VEC_PATH`** — override the locations of
    the bundled `sqlite-vec` extension / Model2Vec model (else the native-image
    resources fetched by `bb sqlite-vec:fetch` / `bb model2vec:fetch` are used).
- **`BY_SANDBOX_INTEROP`** — seeds the `:sandbox-interop` config default
  (`restricted` | `full` | `auto`) controlling Java interop in the **in-process
  SCI code-eval sandbox** (distinct from `--sandbox`, which is the OS seatbelt).
  `restricted` (default) denies System/Runtime/ProcessBuilder/ClassLoader;
  `full` permits arbitrary interop (container-only); `auto` relaxes to `full`
  only when a container is detected via `env-detect`. Explicit opt-in — never
  auto-relaxes unless set. Per the config precedence (below), a set
  `BY_SANDBOX_INTEROP` **wins over** `.brainyard/config.edn`; the file only
  applies when the env var is unset. Mechanism in `components/clj-sandbox`
  (`sci-init-opts`/`full-classes`); policy in
  `agent.core.config/resolve-sandbox-interop`. See `docs/sandboxing.md`.

  **Config precedence (all schema keys, highest → lowest):** environment
  variable (a key's `:env-fn`) > per-agent override > session config >
  `.brainyard/config.edn` (merged over static defaults) > schema default. A set
  env var wins over every persisted layer; each resolution is mulog-tracked
  once per (key, source) via `::config-resolved`. Resolved in
  `agent.core.config/get-config`.

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

### Context-graph memory is an overlay, not a replacement (CR-MEM-20..24)

The graph (`components/memory`: `graph_nodes`/`graph_edges`/`graph_vec` +
`graph_communities`) is layered **over** the existing L1/L2/L3 FTS store as extra
RRF recall signals — semantic-similarity (`:vec`) and relational/multi-hop
(`:graph`) — never a replacement. Off by default (`BY_ENABLE_GRAPH_MEMORY`), it
**degrades gracefully**: no embedder ⇒ no `:vec`; empty graph ⇒ recall is
byte-identical to pure FTS; no extract model ⇒ storage-only. Self-population is
LLM extraction, run in one of two modes (`:graph-extract-mode`, default
`:at-consolidation`): batch-extract new episodes at each consolidation, or
`:per-episode` off the capture sidecar. Community summaries replace the
heuristic L2→L3 reducer (**closes CR-MEM-07**) and are harvested by consolidation,
which is now implied by `BY_ENABLE_GRAPH_MEMORY`. Two decisions worth knowing:

- **Embeddings can be fully self-contained.** The default `BY_GRAPH_EMBED_MODEL
  "static"` is a pure-JVM **Model2Vec** embedder bundled into the binary (no
  server, no JNI, no native-image risk — unlike a real transformer runtime).
  Power users point it at Ollama/OpenAI instead. See the embedding-model
  discussion in `docs/design/context-graph-memory-design.md`.
- **Changing the embed model pauses, never corrupts.** `graph_vec` vectors are
  only comparable within one model (a same-dim model swap silently poisons kNN).
  The store fingerprints the embedder; on a mismatch it **safe-disables** vector
  recall (FTS fallback, no mixed-space writes), surfaces a startup banner +
  `memory$status` flag, and waits for the user to run `memory$reembed`. Guided,
  not automatic — no surprise embedding cost, no silent wrong rankings.

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

Resource-fetch tasks (CR-MEM-21, context-graph memory) download sha-pinned, gitignored binaries into `components/memory/resources/` and are run by `build:ata` before `uberjar:ata` so they get bundled into the native image: `sqlite-vec:fetch` (the `vec0` extension, per-platform) and `model2vec:fetch` (the bundled `potion-base-8M` static embedding model). They are noun-scoped (not `:ata`) because they populate a component's resources, not the project's build outputs.
