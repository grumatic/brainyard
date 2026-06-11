# Changelog

All notable changes to Brainyard's public distribution are documented here. Versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- **User-defined agents (`meta-agent`).** The agent can now author its own persistent specialists at runtime: `meta-agent$create` takes a `:name`, a one-line `:description`, an `:instruction` block, and a `:tool-context` block, and registers a **CoAct-derived agent** — `user$agent$<name>` — that is discoverable, routable, and callable as a sub-agent on the next turn. The authored agent inherits the full CoAct loop and tool palette, so it binds **no tools**; it is shaped entirely by its two prose blocks. Persistence is a directory per agent (`<project>/.brainyard/agents/user$agent/<name>/` — `agent.edn` + `instruction.md` + `tool-context.md`), so the prose stays editable Markdown. Unlike user tools/hooks there is no body to eval and no sandbox to rehydrate — an authored agent grants no capability `coact-agent` lacks; it is a persona over the same guarded palette. A `meta-agent$create` / `meta-agent$validate` / `meta-agent$list` / `meta-agent$read` / `meta-agent$delete` command family (mirroring `tool-agent$*` / `hook-agent$*`) manages them, fronted by the new `meta-agent` specialist; `main-agent` gains an `:agent-lifecycle` routing shape that delegates "make me an agent that …" asks to it. Design in [`docs/design/meta-agent-design.md`](docs/design/meta-agent-design.md).

### Changed

- **User-defined tool/hook command families renamed to `tool-agent$*` / `hook-agent$*`.** The `tools$*` command family (`create`, `validate`, `list`, `read`, `delete`) is now `tool-agent$*`, and the `hooks$*` family (`events`, `create`, `validate`, `list`, `read`, `delete`) is now `hook-agent$*` — each command is namespaced under the specialist agent that owns it (`tool-agent`, `hook-agent`), matching the `<owner>$<verb>` convention. Straight rename with no legacy alias: update any prompt, script, or skill that invoked the old names (e.g. `tools$create` → `tool-agent$create`, `hooks$events` → `hook-agent$events`). Persisted definitions under `.brainyard/tools/` and `.brainyard/hooks/` are unaffected.
- **User-defined tool ids are now namespaced `user$tool$<name>`.** A tool authored via `tool-agent$create` is registered, auto-bound, and dispatched as `user$tool$<name>` (was `user$<name>`), and the body-to-body composition symbol matches — a peer is called as `(user$tool$other {…})`. This aligns the tool namespace with the sibling user-defined asset namespaces (`user$agent$<name>`, planned `user$skill$<name>`) so the `user$` space stays partitioned by asset type. Straight rename with no legacy alias: any authored tool body that composed a peer by its old `user$<name>` symbol must update to `user$tool$<name>`. Persisted sources under `.brainyard/tools/<name>.edn` are unaffected — the id is derived from the name at load time, so existing tools pick up the new prefix automatically on next boot.
- **`BRAINYARD_*` environment variables renamed to `BY_*`.** Every remaining `BRAINYARD_`-prefixed env var now follows the `BY_*` convention already used by `BY_USER_ID`, `BY_WORKING_DIR`, `BY_PROJECT_DIR`, `BY_WEB_*`, and `BY_SANDBOX_*`. Renamed: `BRAINYARD_NREPL_ENABLED` → `BY_NREPL_ENABLED`, `BRAINYARD_NREPL_PORT` → `BY_NREPL_PORT`, `BRAINYARD_NREPL_GRANT` → `BY_NREPL_GRANT`, `BRAINYARD_SESSION_ID` → `BY_SESSION_ID`, `BRAINYARD_VERSION` → `BY_VERSION`, `BRAINYARD_RUN_INTEGRATION` → `BY_RUN_INTEGRATION`, `BRAINYARD_TUTORIAL_MODE` → `BY_TUTORIAL_MODE`, `BRAINYARD_DEV_REPO` → `BY_DEV_REPO`, `BRAINYARD_ITER_LIVE_BLOCKS` → `BY_ITER_LIVE_BLOCKS`, `BRAINYARD_DEFAULT_PROVIDER` → `BY_DEFAULT_PROVIDER`. Straight rename with no legacy alias — update any script, `.env`, or shell that set an old name (notably the live-nREPL opt-in is now `BY_NREPL_ENABLED=true`).
- **`memory$remember` / `memory$recall` reject an unknown `:kind` with an actionable error.** Previously both tools accepted any `:kind` string: `remember` mapped it straight to storage (an invalid kind like `user-identity` silently polluted the L3 taxonomy or surfaced only the opaque `"Write to l3 returned no entry"`, so the LLM looped), and `recall` used it as a filter that silently matched nothing — indistinguishable from "memory is empty". Both now validate an explicit `:kind` against the per-layer set (l1: `system-context|user-context|episode|fact|observation`; l2: `conversation|action|observation|thought|evaluation|error`; l3: `summary|fact|preference|entity|concept|relationship`) and return `{:error "Invalid kind \"…\" for layer l3. Valid kinds: …"}` before acting, so the model retries with a valid kind. `recall` additionally errors when a `:kind` filter is given without a `:layer` (cross-layer recall ignores it). The valid kinds per layer are now enumerated in each `:kind` field description. Omitting `:kind` still applies the per-layer default (remember) or no filter (recall).
- **`by run`: bare `--resume` now opens the interactive session picker.** Previously bare `--resume`/`-r` resumed the *latest* persisted session and a separate `--select-resume` flag showed the picker. The two are unified: bare `--resume` now shows the newest-first picker (the old `--select-resume` behavior; fresh if no sessions), and **`--select-resume` is removed**. `--resume <id>` is unchanged (resume that session, error+exit 1 if absent).
- **A turn always ends with a usable answer.** Two cases that could previously leave a turn with no answer now resolve cleanly. (1) When the iteration loop hits its cap without the model ever producing a final answer, the agent now backfills a best-effort answer from the trajectory (the latest tool result, else the latest reasoning, else a deterministic recap) instead of exiting with nothing — the loop's `:repeat` returns success on exhaustion, so the old catastrophic-failure fallback never fired for this common "ran out of iterations" case. (2) The no-action loop guard (the model reasoned but populated no channel several iterations running) now surfaces that same best-effort progress rather than a stale or empty placeholder.
- **Model-failure recovery is unified and shows progress.** The CoAct loop's two recovery paths (the LLM-call guard and the router's repair fallback) are folded into one action that classifies the failure and applies a per-kind retry budget: a **malformed** model response (DSPy/JSON parse error) re-prompts across iterations before aborting; an **empty** response (the model returned nothing usable) is retried inline with exponential backoff; a deliberate **no-action** turn is nudged before the loop guard stops it. Each retry now emits a muted progress line in the TUI (`⟳ Model returned an empty response — retrying (2/5)…`) instead of a silent pause. The budgets are configurable: `:max-retries-on-llm-empty-result` (**renamed** from `:empty-result-max-retries`, default raised to **5**), plus new `:max-retries-on-llm-malformed-output` (**3**) and `:max-retries-on-llm-no-action` (**3**). Anyone who set the old `:empty-result-max-retries` key must rename it — there is no legacy alias.

### Fixed

- **The abort reason survives a fatal LLM error.** When a turn aborts on an unrecoverable model error (auth / rate-limit / quota, or repeated malformed output), `:terminated-by` now reports `:llm-error` instead of being overwritten to `:answer-channel` by the terminal answer path. The recovery action stamps the abort answer before the router runs, and the router's answer handler was hard-setting the reason; it now preserves a pre-set one, and per-turn state is cleared at turn start so a prior turn's reason can't leak forward.

## [v0.3.0] — 2026-06-08

### Added

- **Live artifacts — reference docs in context + agent-curated material.** The agent's prompt now carries a `## Live Artifacts` section fed by two streams. **System reference docs** named by the new `:reference-artifact-paths` config (default `CLAUDE.md` / `AGENTS.md`; relative names resolve against the project/working dir, absolute and `~` paths as-is, e.g. `~/.claude/CLAUDE.md`) are seeded fresh every turn, pinned, and never removable by the LLM — so project/user guidance rides along automatically. **Dynamic artifacts** are material the agent pins itself via a new `artifact$add` (an absolute file `:path`, reloaded fresh each turn — e.g. a skill's `SKILL.md` — or inline `:content`) / `artifact$list` / `artifact$remove` / `artifact$pin` command family; these persist across turns within a session. File-backed artifacts render as a 400-char preview plus a `(read-file {:path …})` pointer (the file reloads each turn, so the full bytes needn't ride the prompt), while inline notes render up to `:live-artifact-max-chars` (default 4000). `BRAINYARD.md` / `CLAUDE.md` / `AGENTS.md` that are **linked to one source** (symlink or hardlink, at project or user scope) are de-duped by file identity, and a reference doc linked to `BRAINYARD.md` (already loaded as instructions) is dropped rather than emitted twice. Design notes in [`docs/design/artifacts.md`](docs/design/artifacts.md).

### Changed

- **Pinned context is never dropped wholesale under budget pressure.** The token-budget reducer gains a `:keep-floor?` section policy: when a section's compaction strategy can no longer shrink it (e.g. only pinned/system live artifacts remain), its irreducible floor is kept and the section is retired from compaction instead of being dropped as a loop-breaking last resort. Live-artifact eviction is pin-aware — only unpinned, agent-added artifacts are dropped under pressure; pinned and system reference docs always survive. Design notes in [`docs/design/compaction.md`](docs/design/compaction.md).
- **Memory capture is on by default.** The layered-memory capture pipeline (the S0/S1/S2 hooks that auto-populate the L2 episodic chronicle from lifecycle events) now runs for every agent unless explicitly disabled. The `:enable-memory-capture` config default flipped to `true`, and the capture-start gate now reads it through `config/get-config` — honoring schema defaults plus per-agent/session/global overrides — instead of a direct map lookup that bypassed the default. Previously capture only ran where a `defagent` opted in via `:config-extra`, so the schema default was dead config. Set `:enable-memory-capture false` to turn it off. (Per-turn LLM essence extraction, `:enable-memory-essence`, remains **off** by default.)
- **Verbatim code blocks ride forward across turns.** A four-backtick verbatim block now carries its full body on the iteration record's `:code` (previously dropped to an empty string once written to a scratch path), so the model keeps sight of what it generated on later turns; token economy is left to the iteration-record compaction layer rather than the writer. The explore-agent uses this to author large or fence-heavy dossier bodies without hand-escaping YAML + markdown into a Clojure string — write the body verbatim, read it back, then prepend frontmatter via `explore$write`.

## [v0.2.7] — 2026-06-07

### Added

- **User-defined hooks (`hook-agent`).** The agent can now author its own runtime hooks: `hooks$create` takes Clojure source and registers it as a persistent observer on a pre-defined Brainyard event (e.g. `:agent.tool-use/post`, `:agent.iteration/post`). Mirroring user-defined tools, the handler *source* is persisted to `.brainyard/hooks/<id>.edn` and re-evaled in a dedicated sandbox to rehydrate on fire and at session boot; the body composes the tool palette by direct symbol — `(write-file {…})`, `(bash {…})` — to enact its side effect. A `hooks$events` / `hooks$create` / `hooks$validate` / `hooks$list` / `hooks$read` / `hooks$delete` command family (mirroring `tools$*`) manages them, fronted by the new `hook-agent`. v1 is observer-only (gated events that block/modify/replace are reserved) and requires an explicit `:match` scope; safety rests on fail-open handler errors, a re-entrancy guard, and the `enable-user-hooks` kill-switch.
- **Hook-agent tutorial.** A new recorded walkthrough (`21-hook-authoring`) takes the `hook-agent` through the full hook lifecycle across three turns — discover the hookable events, author a persistent `audit-bash` hook from a plain-English request, then read it back to confirm it is registered and active.
- **Contain a session in a sandbox (`by --sandbox`).** A new launcher re-execs `by run` under macOS [`sandbox-exec`](https://keith.github.io/xcode-man-pages/sandbox-exec.1.html) (seatbelt) with a generated **write-containment** profile: reads, network and subprocess exec stay allowed (an agent's job is running tools and calling LLMs), but filesystem *writes* are confined to `~/.brainyard`, the project/cwd subtree, `$TMPDIR`/`/tmp`, `~/Library/Caches` and `/dev` — so an agent (or a tool it runs) can't clobber `~/.ssh`, `~/.aws/credentials`, `/etc`, or unrelated repos. The sandboxed session runs in the **same terminal** (unlike `--web`'s PTY-over-network). Add writable roots with `--sandbox-allow-write` (repeatable/comma-separated), cut off the network with `--sandbox-no-network`, or supply your own seatbelt profile with `--sandbox-profile`; all `--sandbox*` flags have `BY_SANDBOX_*` env equivalents. macOS-only (warns and runs unsandboxed elsewhere) and mutually exclusive with `--web` in v1; `sandbox-exec` is probed at runtime and is not a build dependency. New brick `components/os-sandbox`; full guide in [`docs/sandboxing.md`](docs/sandboxing.md).
- **Set the working directory (`by --working-dir`/`-C`).** You can now point a session at a working directory other than the launch cwd, so file-touching tools (`bash`, `read-file`, `write-file`, `grep`) and the functional agents resolve relative paths — and land their `.brainyard/` artifacts — where you intend. Precedence: `--working-dir`/`-C` flag > `BY_WORKING_DIR` env > the process cwd. The flag is strict (a non-existent / non-directory path exits 1); a bad `BY_WORKING_DIR` env value falls back to the cwd. The project root (where `.brainyard/` lives) re-derives by walking up to the nearest `.git` from the effective working dir, unless **`BY_PROJECT_DIR`** explicitly overrides it. No real `chdir` happens — a JVM can't change its process cwd at runtime — the value is threaded through config and applied to spawned subprocesses and path resolution. The `--web`/`--sandbox` launchers forward `-C` into their re-exec'd child. Documented in `.env.example` and `CLAUDE.md`.

### Changed

- **Liveness signal for long-running subagent (and tool) tasks.** When a tool call — including a subagent invoked as a tool — runs past its fast-eval window and is detached into a background task, it now emits a periodic liveness heartbeat into its task output (`[<tool> ] running… elapsed Ns`, every 10s). Previously a `:tool` job, unlike `:bash`/sandbox/nREPL, bound no streamable `*out*`, so its `task$detail` line count stayed frozen at the initial `Invoking…` line for the whole run — making a healthy multi-minute subagent look wedged and inviting a premature `task$cancel`. `task$detail` also gains `:elapsed-ms` and `:last-output-age-ms` (from the output-log mtime) so a polling agent can tell "alive but quiet" from "stuck", and the detach marker now explicitly warns against cancelling on a quiet output window. The heartbeat rides the existing detach drain pipe and self-terminates with the task; the task's `:result` is unchanged (heartbeats live only on the streaming surface).
- **Per-iteration progress trace for detached subagent tasks.** Building on the heartbeat, a detached subagent now streams a compact, meaningful trace into its task output — `[iter N/MAX] thinking…` at each iteration start and `  tool <name> → ok` / `error: …` as each tool call completes — so a polling agent watches real progress, not just a liveness tick. It works without touching the agent loop: a single built-in subscriber on the existing `:agent.iteration/pre` and `:agent.tool-use/post` events appends to the running task's output, keyed off `proto/*current-task*`. Because a subagent-as-tool runs its own loop on the future thread that carries that binding (conveyed into its `pmap` tool dispatch), its events route to the adopted task while the parent's own iterations — firing on the unbound BT thread — stay out. Observer-only and fail-open (registered under `:source :task-progress`); no-op outside a task context.
- **Structured progress snapshot in `task$detail`.** Beyond the streamed lines, `task$detail` now returns a `:progress` map for a running subagent task — `{:iteration :tools-completed :last-tool :last-tool-result :last-reasoning}` (`:last-reasoning` = the agent's latest "Think:" text) — so a polling agent can judge how far along a subagent is at a glance instead of parsing the log tail. Maintained by the same `:task-progress` hooks (iteration/tool boundaries plus the latest per-iteration observation) and evicted when the task goes terminal (`:result` then carries the final answer). Absent for non-subagent tasks.
- **Cascading cancel for subagent tasks.** Cancelling a background task that wraps a subagent (via `task$cancel`) now cancels the whole subagent chain (task → subagent → its subagents → …), not just the top future. The dispatched subagent's instance-id is captured at adoption and the task's `on-cancel` calls `runtime/cancel-run` on it, which sets the cooperative `:cancelled?` flag and aborts its in-flight LLM stream; every descendant sees it through the existing upward parent-chain `cancelled?` walk and aborts at its next BT checkpoint. Previously only the top future was interrupted, leaving descendant subagents orphaned on their own pmap threads, still burning tokens. Cancellation is cooperative — a descendant mid-LLM-call stops once that call returns. (Ctrl-C of the foreground agent already cascaded this way; this brings targeted `task$cancel` to parity.)
- **Steer a paused agent by typing (resume with a request).** While an agent's iteration loop is paused (Ctrl-\\), typing a message + Enter now resumes the *running* loop carrying that message as a mid-run steering request, instead of queueing a separate next turn. The note is folded into the loop's **active task** (`st-memory :question`, the primary objective both the react and coact loops render each iteration) as a framed directive — `[MID-RUN STEERING — you were paused and the user resumed the iteration loop with this added/updated instruction; treat it as authoritative and adjust your remaining work now] …` — so the LLM actually changes course rather than treating it as background history. Plumbed via `resume-run`'s new optional `note` arg → `[:runtime :resume-note]` → consumed at the next BT checkpoint by `apply-resume-note!` (fires whether or not the loop parked, so a pause+resume inside one LLM call still lands) → appended to `:question`. The paused banner now reads `[paused] Ctrl-\\ resume · Ctrl-C cancel · or type a message + Enter to resume with it`. (Ctrl-\\ still toggles a plain resume; Ctrl-C still cancels.)
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
