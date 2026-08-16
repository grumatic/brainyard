# Using Brainyard (`by`)

> Flags & subcommands here track `agent-tui-app` at **v0.6.0+**. The binary is
> the authority — `by --help` and `by <subcommand> --help` print the exact
> surface your build ships.

`by` is the agent-driven terminal UI binary. It has ten subcommands:

| Subcommand | Purpose |
|---|---|
| `run` *(default)* | Launch the interactive TUI. |
| `ask` | Run a one-shot question, print the answer, exit. Non-interactive. `--attach` instead asks a running session over its channel. |
| `agents` | List available agents and exit. |
| `models` | List available LLM models (provider/model) and exit; `--refresh` / `--drift` reconcile against the live providers. |
| `config` | Bootstrap pipeline (detect → ladder → handoff) for provider + runtime settings. |
| `sessions` | Inspect and manage persisted agent sessions (`list` / `show` / `config` / `label` / `prune`). |
| `projects` | Inspect the user-scope project registry (`list` / `path` / `add` / `prune` / `remove`). |
| `memory` | Maintenance and inspection of the user-scoped L1/L2/L3 memory store and context graph. |
| `events` | Fire user-defined events into a live session over its ask channel. |
| `a2a` | Serve local agents to other agents over the Agent2Agent protocol (`serve`). |

If no subcommand is given, `run` is implied.

```bash
by                  # equivalent to: by run
by run -i           # inline mode (no alt-screen)
by ask 'hello'      # one-shot
by agents           # list agents and exit
by models           # list provider/model combinations
by config           # bootstrap pipeline
by sessions list    # list persisted sessions
by projects list    # list registered projects
by memory status    # memory store health + inventory
by a2a serve        # expose local agents over A2A (off by default)
by --help           # full help
```

---

## Options

`run` and `ask` share the model-selection options; `run` adds TUI- and session-specific flags.

### Shared (`run` and `ask`)

| Short | Long | Default | Notes |
|---|---|---|---|
| `-a` | `--agent AGENT` | `coact-agent` | Which agent to invoke. Use `by agents` to list. |
| `-p` | `--provider PROVIDER` | `claude-code` | LLM provider (see below). |
| `-m` | `--model MODEL` | provider default | Model name override. Provider-relative — e.g. `sonnet`, `opus`, `haiku` for `claude-code`. |
| `-n` | `--max-iterations N` | per-agent | Cap the agent's iteration loop. |
| `-u` | `--user-id ID` | `$BY_USER_ID`, else OS login | Identity stamped on sessions, and the partition key for memory (`~/.brainyard/memory/<user-id>.db`). |
| `-C` | `--working-dir DIR` | `$BY_WORKING_DIR`, else cwd | Effective working directory for tools/agents. Strict: a non-directory exits 1. |

### `run`-only

| Short | Long | Default | Notes |
|---|---|---|---|
| `-i` | `--[no-]inline` | off | Inline mode (no alt-screen). Useful when running `by` from inside another TUI/CLI. |
| `-v` | `--[no-]verbose` | off | Verbose output (debug logs to stderr). |
| `-r` | `--resume [ID]` | — | Resume a persisted session. Bare `--resume` = pick from an interactive menu; `--resume <id>` = that session. |
|  | `--[no-]resume-latest` | off | Resume the most-recent persisted session non-interactively (starts fresh if there is none). Env `BY_RESUME_LATEST`. |
| `-s` | `--session ID` | — | Start a **new** session with this exact id — deterministic paths for scripting and `--attach`. Errors if the id already exists; use `--resume <id>` to reattach. |
|  | `--config EDN` | — | Agent config overrides for this session, as an EDN map of `config-schema` keys. Seeded as per-agent overrides and applied **last**, so they win over the equivalent flags (see below). |
|  | `--[no-]with-tmux` | off | Require tmux side panes / popups (exit 1 if not in a tmux session). |
|  | `--[no-]serve` | off | Headless daemon: no interactive input; keep the session alive to serve `by ask --attach <id>` until `SIGTERM`/`SIGINT`. Pair with `-s <id>`. See [session-channel.md](session-channel.md). |
|  | `--[no-]new` | — | Deprecated no-op — sessions start fresh by default. |
|  | `--[no-]web` | off | Share this session over the web via [ttyd](https://github.com/tsl0922/ttyd). See [web-sharing.md](web-sharing.md). |
|  | `--[no-]web-tmux` | off | Share via a private tmux session; the launching terminal stays a dashboard (drive locally from another terminal or the browser). |
|  | `--web-port N` | `7681` | ttyd listen port (`0` = random). |
|  | `--web-bind ADDR` | `127.0.0.1` | Address ttyd binds (`127.0.0.1` = localhost only). |
|  | `--web-user U` / `--web-pass P` | `by` / auto | Basic-auth credentials (auth is always required). |
|  | `--[no-]web-readonly` | off | Web clients may watch but not type. |
|  | `--web-max-clients N` | `0` | Max simultaneous web clients (`0` = unlimited). |
|  | `--[no-]web-once` | off | Stop sharing after the first client disconnects. |
|  | `--[no-]sandbox` | off | Run this session in a macOS seatbelt sandbox (write-containment; macOS only). Mutually exclusive with `--web`. See [sandboxing.md](sandboxing.md). |
|  | `--sandbox-profile PATH` | — | Use a custom `.sb` seatbelt profile instead of the generated default. |
|  | `--sandbox-allow-write PATH` | — | Extra writable root inside the sandbox; repeat or comma-separate. |
|  | `--[no-]sandbox-no-network` | off | Deny all network from the sandboxed session (blocks LLM calls). |

**Providers:** `claude-code` (default, no API key — drives the Claude CLI), `anthropic`, `openai`, `bedrock`, `google`, `groq`, `ollama`, `apple-fm`, plus OpenAI-compatible endpoints (`openrouter`, `together`, `fireworks`, `deepseek`, `mistral`, `azure`). Run `by models` for the full provider/model matrix.

### `--config` — everything a flag can't say

`-p`/`-m` cover a provider and a model and stop there. `--config` takes any
`config-schema` keys as an EDN map, so a launch can state what a *running*
session could be configured to:

```bash
by run -a acp-agent --config '{:acp-backend-opts {:model "claude-opus-5"
                                                  :env {:ANTHROPIC_MODEL "claude-opus-5"}}}'
by run --config '{:permission-mode :auto-approve :max-iterations 40}'
```

Keys must be schema keys (see [`core/config.md`](core/config.md)). They are
seeded as **per-agent overrides** for the session and applied after the flags:
where `--config` and a flag disagree, `--config` wins, because it exists to
express what the flag cannot.

### Provider/model shorthand

The legacy `provider:model` form still works as a positional argument after `--`:

```bash
by -- claude-code:sonnet
by run -- anthropic:claude-sonnet-4-6
```

Ordinary question text containing a `:` is **not** affected — only an argument that matches the `provider:model` shape is interpreted this way.

### Unrecognized arguments

A bare first argument is accepted only when it names a **registered agent**
(the legacy `by coact-agent` form) or matches the `provider:model` shape.
Anything else exits 1 rather than starting a session:

```console
$ by sesions list
Unknown command or agent: 'sesions'
Did you mean: sessions?

Subcommands: a2a agents ask config events memory models projects run sessions
Run `by --help` for usage, or `by agents` for the agent list.
```

A quoting mistake lands here too — `by "sessions list"` arrives as one argument
— so the suggestion falls back to the token's first word.

---

## `by run` — interactive TUI

```bash
by                                              # default: coact-agent on claude-code (haiku)
by -p claude-code -m sonnet                     # change provider/model
by run -p anthropic -m claude-sonnet-4-6
by run -a coact-agent -i                        # inline mode
by run --resume                                 # pick a session from a menu
by run --resume agt-1779952718824-5844          # resume a specific session
```

Inside the TUI:

- Type messages and hit Enter to send.
- Streamed responses, tool calls, and plans render incrementally in the main pane.
- The status row (chrome) shows the agent ID, provider, model, session ID, and version.
- Run `by config` if `.env`-discovered credentials are missing.

### Slash commands

`/help` prints this table for the build you're running — it and the
autocomplete menu are both generated from one registry
(`agent.tui.format/command-registry`), so they cannot disagree. Typing `/`
opens the completion menu; commands with sub-verbs open a submenu.

| Command | Args | Purpose |
|---|---|---|
| `/help` | | Show the command + key table |
| `/status` | | Agent status |
| `/history` | | Conversation history |
| `/usage` | | Token/cost summary + per-call latency |
| `/todo` | | Show the TODO list |
| `/model` | `[name\|#]` | Model picker / switch model |
| `/config` | `[key [val]]` | Show/set runtime config |
| `/feature` | `[name [on\|off]]` | Capabilities — config grouped by feature |
| `/effort` | `[low\|medium\|high]` | Effort level (finalize + refinement passes) |
| `/display-format` | `[quiet\|normal\|verbose]` | Display detail level |
| `/agent` | `[status\|new\|switch\|close\|trace]` | Manage agents |
| `/session` | `[N\|tabs\|new\|close\|switch\|rename\|list\|show\|label\|tree\|fork]` | TUI tabs + persisted sessions |
| `/task` | `[list\|detail\|cancel\|del\|log\|run]` | Background tasks |
| `/queue` | `[list\|cancel [all\|uuid]]` | Input queue |
| `/memory` | `[stats\|remember\|consolidate\|purge\|verify\|correct]` | Long-term memory |
| `/mcp` | `[server [action]]` | Manage MCP servers |
| `/login` / `/logout` | `[provider]` | Auth-provider status / sign in / sign out (not MCP servers) |
| `/init` | `[prompt\|show\|reseed\|revert\|list-snapshots]` | Author/maintain `BRAINYARD.md` |
| `/copy` | `[code [N]]` | Copy the last answer — or one of its code blocks — to the clipboard |
| `/capture` | `PATH` | Save the scrollback buffer to a file |
| `/compact` | `[ratio]` | Compact context to a ratio of max tokens (default 0.2) |
| `/clear` | | Restart the session: history, scrollback, and st-memory |
| `/continue` | `[N]` | Resume the last answer with N more iterations |
| `/pause` / `/resume` | | Cooperatively pause / unpark the active BT run |
| `/allow-path` | `PATH` | Whitelist a file path for agent access |
| `/sandbox` | `[fn\|eval CODE]` | Run a sandbox function or eval code |
| `/quit` | | Exit |
| `/activity`, `/log`, `/scrollback dump`, `/popup test` | | Mode-B (tmux) side panes; a friendly no-op elsewhere |

Keys: `PgUp`/`PgDn` scroll history (fullscreen), `Shift+←`/`Shift+→` walk
prompt history, `Ctrl-N`/`Ctrl-P` next/previous session, `Ctrl-T` new session,
`Ctrl-W` close session, `Ctrl-O` expand/collapse the TODO list.

**`/copy` copies the source, not the screen.** A mouse drag selects terminal
*cells*, so it picks up the box border, the indent, the vertical rail, and the
hard line breaks the word-wrap inserted at the pane width — none of which are in
the answer. `/copy` stashes the raw model answer as it is emitted, per session,
and `/copy code [N]` hands back a fenced block with the fences and rail already
stripped, so it pastes into a file and runs. It prefers a tmux buffer, then
OSC 52 when `SSH_TTY` says the session is remote, then a native
`pbcopy`/`wl-copy`/`xclip`/`clip.exe`, then OSC 52 as a last resort — native
tools are verifiable but over SSH they would succeed against the *wrong*
machine, so the remote check runs first. OSC 52 has no reply channel, so a send
over it reports "sent … if your terminal supports it" rather than claiming
success (Terminal.app, GNOME Terminal and Konsole drop it silently).

---

## `by run --web` — share over the web

```bash
by --web                       # share a fresh session on http://127.0.0.1:7681
by --web --web-port 8080 --web-user alice --web-pass s3cret
by --web-tmux                  # persistent shared tmux session; dashboard stays in this terminal
```

`--web` wraps `by run` in [ttyd](https://github.com/tsl0922/ttyd) so the session
is reachable in a browser. Auth is always required, binding defaults to
localhost, and `by` can run code/tools — so treat a writable session like a
shared shell. Full guide, flags, env vars, and the security model:
**[web-sharing.md](web-sharing.md)**.

---

## `by run --sandbox` — contain the session (macOS)

```bash
by --sandbox                                   # confine writes to ~/.brainyard + cwd + /tmp
by --sandbox --sandbox-allow-write ~/scratch   # add a writable root (repeat or comma-separate)
by --sandbox --sandbox-no-network              # also cut off the network
by --sandbox --sandbox-profile ./my.sb         # use your own seatbelt profile
```

`--sandbox` re-execs `by run` under macOS `sandbox-exec` with a
**write-containment** profile: reads, network and subprocess exec stay allowed,
but writes are confined to `~/.brainyard`, the project/cwd subtree, `$TMPDIR` and
`/tmp` — so an agent can't clobber `~/.ssh`, `~/.aws/credentials`, `/etc`, or
other repos. macOS-only; mutually exclusive with `--web`. Full guide:
**[sandboxing.md](sandboxing.md)**.

---

## `by run --serve` — headless daemon

```bash
by run --serve -s deploy-bot < /dev/null &          # start a headless session
by ask --attach deploy-bot "status of the rollout?" # drive it from anywhere in the project
kill -TERM %1                                        # stop it gracefully
```

`--serve` runs a session with **no terminal**: it skips the interactive keyboard
loop, opens its ask socket, and **parks** — staying alive to serve `by ask
--attach <id>` (and the full session-channel wire protocol) instead of exiting on
stdin EOF like a plain `by run`. Startup is silent (no alt-screen/banner; stdout
stays clean, diagnostics go to stderr), and `SIGTERM`/`SIGINT` triggers a
graceful shutdown (session close + memory consolidation + socket unlink). Pair it
with an explicit `-s <id>` for a deterministic id to attach to. Full guide:
**[session-channel.md](session-channel.md)**.

---

## `by ask` — one-shot question

```bash
by ask 'What is 2+2?'
by ask -m opus 'Explain monads in two paragraphs.'
by ask -a coact-agent -p anthropic -m claude-sonnet-4-6 'Summarize the Polylith approach'
by ask --json 'list the open todos'                   # machine-readable
by ask -s notes 'remember: the deploy key rotates Friday'
by ask -s notes 'when does the deploy key rotate?'    # same id ⇒ shared session memory
by ask -A agt-1779952718824-5844 -t 300 'status?'     # ask a RUNNING session
```

`ask` is for piping into other tools or scripting. It writes the agent's answer
to stdout and exits 0 on success. The question is the only positional argument.
`-i`/`-v` are `run`-only and not accepted here.

| Short | Long | Default | Notes |
|---|---|---|---|
| `-A` | `--attach ID` | — | Ask a **running** session over its ask socket instead of starting a fresh one. See [session-channel.md](session-channel.md). |
| `-t` | `--timeout N` | `120` | Seconds to wait for an `--attach` answer. |
| `-s` | `--session ID` | fresh `ask-<millis>` | Pin the session id. Reusing one id across one-shot asks shares session-scoped (L1/L2) memory recall between them. |
|  | `--[no-]json` | off | Machine-readable JSON instead of a table. |

> `-s` shares what the **memory store** recalls, not a live transcript: each
> one-shot ask is still its own turn in its own process.

---

## `by agents` — list available agents

```bash
$ by agents
26 agent(s) available:

  AGENT           DESCRIPTION
  --------------  -----------
  coact-agent     CoAct (Reasoning-and-Code-and-Action) agent — unifies tool-calling and code-as-action…
  main-agent      Front-door router — picks the right specialist per question shape…
  research-agent  LLM-driven multi-specialist research loop…
  explore-agent   Multi-surface read-mostly exploration specialist…
  …
```

The full set spans routing (`main-agent`), reasoning (`coact-agent`, `react-agent`), research/exploration, planning/execution (`plan-agent`, `todo-agent`, `exec-agent`, `eval-agent`), editing (`edit-agent`), memory, MCP, skills, debugging, user-defined tools/hooks (`tool-agent`, `hook-agent`), and more. The set is determined at build time — adding a new one requires a new release. Run `by agents` for the exact roster your binary ships.

---

## `by models` — list provider/model combinations

```bash
by models                        # every known provider/model pair
by models -p bedrock             # one provider
by models --refresh              # ask each reachable provider what it serves, now
by models --drift                # how the live providers differ from the baked catalog
by models --json                 # machine-readable
```

Prints a table of every known `provider / model` pair with a short description. Use the `provider` and `model` columns directly with `-p` / `-m` (or the `provider:model` shorthand).

**The catalog refreshes itself.** A curated catalog is baked into the binary and
drifts as providers add and retire models, so each configured provider's
model-list endpoint is consulted in the background (`/v1/models` for
OpenAI-compatible providers *including a local Ollama*, the Models API for
Anthropic, `ListFoundationModels` + `ListInferenceProfiles` for Bedrock), cached
under `~/.brainyard/catalog/<provider>.edn`, and merged over the baked catalog.

What refreshes is **model ids only**. Curation — `:curated-rank`,
`:description`, `:region` — stays human, because nothing in a provider's
response says which of its entries a *chat* client can drive (`/v1/models` also
returns embeddings, TTS and image models). So a refreshed model becomes usable
and listed immediately, but never enters the `/model` picker on its own.

Refresh is gated by `:enable-catalog-refresh` (`BY_ENABLE_CATALOG_REFRESH`,
default **true**), runs on a daemon thread behind `:catalog-refresh-ttl-hours`
(default 24, with a much shorter TTL derived for local servers), and never
blocks startup. Turn it off and the shipped catalog is used verbatim with no
provider contacted.

---

## `by config` — bootstrap pipeline

```bash
by config                       # interactive: detect → ladder → hand off to config-agent
by config --auto                # non-interactive; apply profile defaults
by config --profile cloud       # named profile: dev | ci | offline | cloud
by config --dry-run             # compute the config but don't write it
```

`config` runs a three-phase bootstrap — **detect** the environment, climb a **ladder** to pick the best reachable provider, then **hand off** to the conversational `config-agent`. It writes runtime settings (default provider/model, permissions, MCP servers, agent defaults) to **`~/.brainyard/config.edn`**, plus a rotating `~/.brainyard/bootstrap-log.edn`. It does not write credentials — those come from the environment (see below).

| Flag | Purpose |
|---|---|
| `--auto` | Non-interactive; apply profile defaults without prompting. |
| `--profile S` | Named profile: `dev`, `ci`, `offline`, `cloud`. |
| `--skip-handoff` | Run phases 1–2 only; skip the config-agent prompt. |
| `--re-bootstrap` | Force rung re-evaluation even if an existing LLM is reachable. |
| `--dry-run` | Compute the config but do not write it. |
| `--log S` | Override the bootstrap-log path. |

Re-run `by config` any time to refresh settings or switch providers.

---

## `by sessions` — manage persisted sessions

```bash
by sessions list                 # list all persisted sessions (id, label, agent, last-attached)
by sessions list --live          # only sessions open in a running `by` process right now
by sessions list --tree          # fork/lineage tree instead of a flat list
by sessions list --all-projects  # every registered project, each row tagged with its project
by sessions show -s <id>         # full detail for one session
by sessions config -s <id> -q .  # read a *live* session's effective config over its ask channel
by sessions label -s <id> "…"    # set a session's label (text required); renames a live tab too
by sessions prune -s <id>        # delete one persisted session
by sessions prune --expired --ttl-days 30
by sessions prune --all --yes    # delete ALL persisted sessions, no prompt
```

Sessions are **project-scoped**: they live under `<project>/.brainyard/sessions/<id>/`, so
`by sessions list` and `by run --resume` only surface the current project's sessions. Resume one
with `by run --resume <id>` (bare `--resume` opens a picker), or
`by run --resume-latest` for the newest one non-interactively.

| Verb | Flags |
|---|---|
| `list` | `--tree`, `--live`, `--all-projects`, `-C`, `--json` |
| `show` | `-s <id>`, `-C` |
| `config` | `-s <id>`, `-q TERM`, `-C`, `--json` |
| `label` | `-s <id>` + the label text as a positional argument, `-C` |
| `prune` | `-s <id>`, `--expired`, `--ttl-days N` (default 14), `--all`, `-y/--yes`, `-C` |

**`--all-projects`** walks the user-scope project registry and reads each
project's sessions in **one process**, tagging every row with `project-slug` /
`project-path`. It exists to collapse a fan-out: a multi-project console that
polls liveness would otherwise spawn one `by` per project per tick, and process
startup is what that costs. It is off by default because widening the default
scope would change what every existing call answers. `--tree` is ignored under
it — lineage is a within-project relation — and the table groups under a project
header so two identically-labelled sessions in different repos don't read as
duplicates. A registry entry whose directory is gone contributes no rows, and a
project that fails to read is skipped rather than aborting the whole listing.

**A label is required.** There is no clear verb anywhere: an omitted argument is
far likelier to be an unset shell variable or a dropped quote than a deliberate
request to wipe a session's name, so to drop a name you set a new one. A rename
writes **both** surfaces — the persisted label in `meta.edn` (what `list` and
the resume picker show) and the live TUI tab — and a session hosted without a
tab still gets its durable rename rather than an error.

---

## `by projects` — the user-scope project registry

Every project you open is registered under `~/.brainyard/projects/<slug>/`,
giving user-scoped state *about* a project a home that isn't the repo's own
`.brainyard/` (which travels with the codebase). v1 stores registry metadata
only — canonical path, name, git remote, created/last-opened stamps.

```bash
by projects list                 # every registered project, newest first
by projects list --json
by projects path <slug>          # absolute path for a registry slug
by projects add                  # register a repo without opening a session
by projects prune                # drop entries whose directory is gone (confirms; --yes to skip)
by projects remove <slug>        # forget one entry (confirms; --yes to skip)
```

The slug is `<sanitized-basename>-<8 hex of SHA-256(canonical path)>` —
readable, space-free, stable (so registration is idempotent), and collision-free
across two checkouts sharing a basename. Recovering the path from a slug is a
**lookup**, not a decode: `by projects path <slug>` reads the record.

`prune` and `remove` are the two reclaim paths, and the split is deliberate.
`prune` takes every `(missing)` record at once and **confirms first**, because
`(missing)` is not proof a project is gone — an unmounted volume, a detached
disk and a downed network share all report identically and come back. `remove`
is the per-slug counterpart and does *not* require the project to be missing: a
repo you have stopped working on has not stopped existing. Either way only the
user-scope record is deleted; the project itself is never touched, and
re-registering restores the same path-derived slug.

---

## `by a2a serve` — expose local agents to other agents

```bash
by a2a serve                                  # loopback:41241, foreground
by a2a serve --host 0.0.0.0 --port 9000       # widened bind (warns)
by a2a serve --json
```

Runs an [Agent2Agent](https://a2a-protocol.org/) server (JSON-RPC 2.0 over HTTP
with SSE) so agents in *other* processes and frameworks can delegate to yours.
Both wire dialects (v0.3 and v1.0) are spoken and advertised on one Agent Card,
because v1.0 replaced rather than extended the v0.3 binding.

Inbound A2A executes prompts against your workspace with tools and disk access,
so containment is explicit: it is **off by default** (`:enable-a2a` /
`BY_ENABLE_A2A`), a bearer token is **required** to bind (there is no
unauthenticated mode, and comparison is constant-time), the bind is loopback by
default with a warning when widened, and `:a2a-expose-skills` is an **empty
allow-list** — nothing is reachable until an operator names it.

Going the other way, `a2a$connect` fetches a peer's Agent Card and registers
each advertised skill as a callable agent, after which a remote agent is asked
with **exactly the same commands as a local one** — inheriting the reach policy,
call-depth guard, LRU eviction and parent-close cascade unchanged. There is
deliberately no `a2a$ask`. Drivers that already hold a peer name and URL can do
peer CRUD without spending a turn, over the session ask channel's `:a2a` op
(see [session-channel.md](session-channel.md)).

Design: [`design/a2a-design.md`](design/a2a-design.md).

---

## `by memory` — inspect & maintain the memory store

Memory is **user-scoped** (partitioned by `BY_USER_ID`) and lives under
`~/.brainyard/memory/<user-id>.db`. This subcommand family is the maintenance and audit surface
over the layered L1/L2/L3 store and the optional context graph.

```bash
by memory status                 # store health: L1/L2/L3 counts + graph vector-index staleness
by memory stats                  # L1/L2/L3 counts for the user
by memory search 'query'         # cross-layer weighted-RRF recall (the real briefing pipeline)
by memory list --layer l2        # raw entries from a layer (--session/--kind/--limit filters)
by memory get --layer l3 <id>    # one entry by id
by memory explain --session <id> # recall audit: which entries informed a session's prompts
by memory graph --node <name>    # dump the context graph (nodes+edges), optionally scoped
```

Curation verbs edit the store in place: `forget` (tombstone), `edit`, `keep` (pin against the
sweep), `archive`, and `promote` (copy an entry up a layer with provenance).

Consolidation and graph maintenance:

```bash
by memory consolidate            # L2→L3 consolidation (heuristic; --reducer community for graph summaries)
by memory graph-build            # extract L2 episodes into the context graph (--rebuild re-extracts all)
by memory reduce                 # graph-build + community consolidation in one shot (session-end offload)
by memory sweep                  # L2 retention sweep (tombstone old, unpinned episodes)
by memory prune                  # evict lowest-retention graph nodes/edges over budget
by memory reembed                # rebuild the graph vector index for the current embedder
```

The graph tier (`graph-build`, `graph`, `prune`, `reembed`, community summaries) only carries
signal when graph memory is enabled — see [`sandboxing.md`](sandboxing.md)'s sibling design note and
the memory section of [`../CLAUDE.md`](../CLAUDE.md) (`BY_ENABLE_GRAPH_MEMORY`, `BY_GRAPH_*`).

---

## `by events` — drive a live session externally

```bash
by events emit -e <event> -p '<payload>' -s <session-id>   # external → agent event injection
```

`events emit` fires a user-defined event into a running session over its ask channel, feeding the
in-agent event bus / reactor / watch loop (`event$emit`, `reaction$add`, `watch$add`). Use it to
wire external triggers into an agent without attaching interactively.

---

## Environment variables

Most `BY_*` variables are read by the **binary**; a few are read only by the
`by` **wrapper** script or by `install.sh`. A real shell env var always wins;
otherwise the binary loads the nearest `.env` (walking up from cwd, then
`~/.brainyard/.env`). `.env.example` is the full annotated template.

**Config precedence, highest → lowest:** environment variable → per-agent
override (including `by run --config`) → session config → `.brainyard/config.edn`
→ schema default. A set env var wins over every persisted layer. Full rules:
[`core/config.md`](core/config.md).

| Variable | Read by | Purpose |
|---|---|---|
| `BY_ENV_FILE` | wrapper | Force a specific `.env` file path. |
| `BY_NO_DOTENV` | wrapper | Skip `.env` discovery entirely. |
| `BY_JAR` | wrapper | Run via `java -jar by.jar` instead of the native binary (JVM-mode debugging). |
| `BY_USER_ID` | binary | Identity stamped on sessions; partitions memory (`~/.brainyard/memory/<user-id>.db`). `-u` wins. |
| `BY_WORKING_DIR` | binary | Effective working directory for tools/agents. `-C` wins (and `-C` is strict where a bad env value falls back to cwd). |
| `BY_RESUME_LATEST` | binary | Default for `--resume-latest`. |
| `BY_FEATURES` / `BY_PROFILE` | binary | Steer capability in bulk — a feature/family override list, or a named profile (`BY_PROFILE=minimal` turns off everything that spends an LLM call). |
| `BY_ENABLE_*` | binary | One per feature gate (`BY_ENABLE_GRAPH_MEMORY`, `BY_ENABLE_A2A`, `BY_ENABLE_CATALOG_REFRESH`, `BY_ENABLE_SCHEDULER`, `BY_ENABLE_REACTIONS`, `BY_ENABLE_FSM`, …). See `/feature` and `feature$*`. |
| `BY_GRAPHEME_WIDTH` | binary | How the TUI measures emoji/CJK width: `auto` (default — negotiate DEC mode 2027, cached per terminal), `on`, `off`. |
| `BY_GRAPH_*` | binary | Context-graph memory knobs (`BY_GRAPH_EMBED_MODEL`, `BY_GRAPH_EXTRACT_MODEL`, `BY_GRAPH_EMBED_DIMS`). Only in effect with graph memory on. |
| `BY_A2A_*` | binary (`a2a`) | A2A server + client settings (`BY_A2A_SERVE_HOST`, `BY_A2A_SERVE_PORT`, `BY_A2A_SERVE_TOKEN`, `BY_A2A_EXPOSE_SKILLS`, `BY_A2A_TIMEOUT_MS`, …). |
| `BY_SANDBOX_INTEROP` | binary | Java interop policy for the in-process SCI code-eval sandbox (`restricted` \| `full` \| `auto`) — distinct from `--sandbox`, the OS seatbelt. See [sandboxing.md](sandboxing.md). |
| `BY_MEMORY_SELF` | binary | Override how the TUI re-execs itself for the detached session-end memory consolidation (dev/source testing). |
| `BY_WEB`, `BY_WEB_*` | binary (`--web`) | Defaults for web sharing (`BY_WEB`, `BY_WEB_TMUX`, `BY_WEB_PORT`, `BY_WEB_BIND`, `BY_WEB_USER`, `BY_WEB_PASS`, …). One per `--web*` flag; flag wins over env. See [web-sharing.md](web-sharing.md). |
| `BY_SANDBOX`, `BY_SANDBOX_*` | binary (`--sandbox`) | Defaults for sandboxing (`BY_SANDBOX`, `BY_SANDBOX_PROFILE`, `BY_SANDBOX_ALLOW_WRITE`, `BY_SANDBOX_NO_NETWORK`). One per `--sandbox*` flag; flag wins over env. macOS-only. See [sandboxing.md](sandboxing.md). |
| `BY_VERSION` | install.sh | Pin install to a specific release tag. |
| `BY_INSTALL_DIR` | install.sh | Override install location (default: `~/.local/bin`). |
| `BY_DOWNLOAD_BASE` | install.sh | Override the release download base URL (mirrors). |
| `BY_PROJECT_DIR` | binary | Hint at the project root when `.env`/cwd discovery isn't enough. |
| `BY_SESSION_ID` | binary | Use a deterministic session id (useful for tests/automation). |
| `BY_NREPL_ENABLED` | binary | Enable the in-process nREPL server backing `code$eval :backend :nrepl` (full-trust; off by default — use the SCI sandbox for isolated eval). |
| `BY_NREPL_PORT` | binary | Port for the in-process nREPL server (`0` = ephemeral). |
| `BY_NREPL_HOST` | binary | nREPL endpoint host for the `:nrepl` Clojure backend (default loopback; set to a trusted remote for off-laptop execution). |

LLM provider credentials are read from the environment by their conventional names (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `AWS_PROFILE`, `AWS_REGION`, …), typically placed in a project-local `.env` that the wrapper sources.

> **AWS / Bedrock note:** the binary's AWS SDK chain honors `AWS_PROFILE` but **not** `AWS_DEFAULT_PROFILE` — even though the AWS CLI honors both. If `by ask -p bedrock …` fails with `Unable to fetch credentials`, export `AWS_PROFILE` explicitly (or set it in your `.env`).

---

## Logging

Brainyard logs to `~/.brainyard/logs/agent-tui-app.log` by default (falling back to `/tmp/agent-tui-app.log` if `$HOME` is not writable). Verbose mode (`-v`, on `run`) also prints to stderr. Crash traces land in `/tmp/by-crash.log`.

The log is structured (mulog events) and useful for filing bugs — attach it when reporting an issue. The binary never sends telemetry over the network.

Two properties matter when you read it:

- **Every subcommand logs.** The app log is started in the dispatcher, after
  `.env` loading and before any subcommand runs, so `by a2a serve`, `by config`
  and the detached `by memory reduce` child all leave an audit trail rather than
  only `ask` and the memory commands. Library warnings (WARN and above) are
  routed into the same file instead of the console, so they can't print onto the
  screen the TUI is drawing.
- **Every event carries the emitting `:pid`.** Several `by` processes append to
  one file at once — an interactive TUI, a detached consolidation, an A2A
  server, and every one-shot command — and without a pid an interleaved log
  cannot be attributed to a process at all. The stamp is applied at *publish*
  time, so it also reaches events buffered during namespace load.

Shutdown **flushes**: the tail of a one-shot command is waited out and awaited
rather than dropped, which costs roughly 200 ms at exit in exchange for complete
logs.

---

## See also

- [`web-sharing.md`](web-sharing.md) — share a session over the web via ttyd.
- [`session-channel.md`](session-channel.md) — the ask socket: `--attach`, `--serve`, and the full op vocabulary.
- [`core/config.md`](core/config.md) — the config schema and its precedence chain.
- [`design/project-registry.md`](design/project-registry.md) — the `by projects` registry.
- [`design/a2a-design.md`](design/a2a-design.md) — Agent2Agent, in both directions.
- [`install.md`](install.md) — install & verification.
- [`deploy-design.md`](deploy-design.md) — release architecture (historical; pre-v0.2.0 sync model).
- [`../README.md`](../README.md) — overview & quick start.
- [`../CLAUDE.md`](../CLAUDE.md) — build/release pipeline and tagging discipline.
