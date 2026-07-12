# Using Brainyard (`by`)

> Flags & subcommands described here track `agent-tui-app` on the v0.3.x line.

`by` is the agent-driven terminal UI binary. It has eight subcommands:

| Subcommand | Purpose |
|---|---|
| `run` *(default)* | Launch the interactive TUI. |
| `ask` | Run a one-shot question, print the answer, exit. Non-interactive. `--attach` instead asks a running session over its channel. |
| `agents` | List available agents and exit. |
| `models` | List available LLM models (provider/model) and exit. |
| `config` | Bootstrap pipeline (detect → ladder → handoff) for provider + runtime settings. |
| `sessions` | Inspect and manage persisted agent sessions (`list` / `show` / `config` / `label` / `prune`). |
| `memory` | Maintenance and inspection of the user-scoped L1/L2/L3 memory store and context graph. |
| `events` | Fire user-defined events into a live session over its ask channel. |

If no subcommand is given, `run` is implied.

```bash
by                  # equivalent to: by run
by run -i           # inline mode (no alt-screen)
by ask 'hello'      # one-shot
by agents           # list agents and exit
by models           # list provider/model combinations
by config           # bootstrap pipeline
by sessions list    # list persisted sessions
by memory status    # memory store health + inventory
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

### `run`-only

| Short | Long | Default | Notes |
|---|---|---|---|
| `-i` | `--[no-]inline` | off | Inline mode (no alt-screen). Useful when running `by` from inside another TUI/CLI. |
| `-v` | `--[no-]verbose` | off | Verbose output (debug logs to stderr). |
| `-r` | `--resume [ID]` | — | Resume a persisted session. Bare `--resume` = pick from an interactive menu; `--resume <id>` = that session. |
|  | `--[no-]with-tmux` | off | Require tmux side panes / popups (exit 1 if not in a tmux session). |
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

**Providers:** `claude-code` (default, no API key — drives the Claude CLI), `anthropic`, `openai`, `ollama`, `bedrock`, `apple-fm`, `deepseek`, `google`, `groq`, `mistral`. Run `by models` for the full provider/model matrix.

### Provider/model shorthand

The legacy `provider:model` form still works as a positional argument after `--`:

```bash
by -- claude-code:sonnet
by run -- anthropic:claude-sonnet-4-6
```

Ordinary question text containing a `:` is **not** affected — only an argument that matches the `provider:model` shape is interpreted this way.

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

## `by ask` — one-shot question

```bash
by ask 'What is 2+2?'
by ask -m opus 'Explain monads in two paragraphs.'
by ask -a coact-agent -p anthropic -m claude-sonnet-4-6 'Summarize the Polylith approach'
```

`ask` is for piping into other tools or scripting. It writes the agent's answer to stdout and exits 0 on success. The shared options above apply (`-a`, `-p`, `-m`, `-n`); the question is the only positional argument. Note `-i`/`-v` are `run`-only and not accepted here.

---

## `by agents` — list available agents

```bash
$ by agents
21 agent(s) available:

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
by models
```

Prints a table of every known `provider / model` pair with a short description. Use the `provider` and `model` columns directly with `-p` / `-m` (or the `provider:model` shorthand).

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
by sessions show -s <id>         # full detail for one session
by sessions config -s <id> -q .  # read a *live* session's effective config over its ask channel
by sessions label -s <id> "…"    # set (or, with no text, clear) a session's label
by sessions prune -s <id>        # delete persisted session(s)
```

Sessions are **project-scoped**: they live under `<project>/.brainyard/sessions/<id>/`, so
`by sessions list` and `by run --resume` only surface the current project's sessions. Resume one
with `by run --resume <id>` (bare `--resume` opens a picker).

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

Variables prefixed with `BY_` are read by the **wrapper** (`by` shell script) or the **installer** — not the binary itself. `BY_*` variables are read by the binary.

| Variable | Read by | Purpose |
|---|---|---|
| `BY_ENV_FILE` | wrapper | Force a specific `.env` file path. |
| `BY_NO_DOTENV` | wrapper | Skip `.env` discovery entirely. |
| `BY_JAR` | wrapper | Run via `java -jar by.jar` instead of the native binary (JVM-mode debugging). |
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

---

## See also

- [`web-sharing.md`](web-sharing.md) — share a session over the web via ttyd.
- [`install.md`](install.md) — install & verification.
- [`deploy-design.md`](deploy-design.md) — release architecture (historical; pre-v0.2.0 sync model).
- [`../README.md`](../README.md) — overview & quick start.
- [`../CLAUDE.md`](../CLAUDE.md) — build/release pipeline and tagging discipline.
