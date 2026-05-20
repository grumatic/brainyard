# Using Brainyard (`by`)

> **Status:** pre-release. Flags & subcommands described here match the upstream `agent-tui-app` as of 2026-05-17. They will land in the first public release (`v0.1.0`).

`by` is the agent-driven terminal UI binary. It has four subcommands:

| Subcommand | Purpose |
|---|---|
| `run` *(default)* | Launch the interactive TUI. |
| `ask` | Run a one-shot question, print the answer, exit. Non-interactive. |
| `agents` | List available agents and exit. |
| `config` | Interactive environment bootstrap wizard. |

If no subcommand is given, `run` is implied.

```bash
by                  # equivalent to: by run
by run -i           # inline mode (no alt-screen)
by ask 'hello'      # one-shot
by agents           # list agents and exit
by config           # bootstrap wizard
by --help           # full help
```

---

## Global options (shared across `run` and `ask`)

| Short | Long | Default | Notes |
|---|---|---|---|
| `-a` | `--agent AGENT` | `coact-agent` | Which agent to invoke. Use `by agents` to list. |
| `-p` | `--provider PROVIDER` | `claude-code` | LLM provider. e.g. `anthropic`, `openai`, `bedrock`, `claude-code`. |
| `-m` | `--model MODEL` | provider default | Model name. Provider-relative — e.g. `sonnet`, `opus`, `haiku` for Anthropic. |
| `-i` | `--inline` | off | Inline mode (no alt-screen). Useful when running `by` from inside another TUI/CLI tool. |
| `-v` | `--verbose` | off | Verbose output (debug logs to stderr). |
| `-n` | `--max-iterations N` | per-agent | Cap the agent's iteration loop. |
| `-s` | `--session-id ID` | new | Resume a specific session. New sessions get a fresh UUID. |

### Provider/model shorthand

The legacy `provider:model` form still works after `--`:

```bash
by -- claude-code:sonnet
by run -- anthropic:claude-sonnet-4-6
```

---

## `by run` — interactive TUI

```bash
by                                              # default: coact-agent on claude-code:haiku
by -p claude-code -m sonnet                     # change provider/model
by run -p anthropic -m claude-sonnet-4-6
by run -a coact-agent -i                        # inline mode
by run -s 7f3a-8c19-...                         # resume a previous session
```

Inside the TUI:

- Type messages and hit Enter to send.
- Streamed responses, tool calls, and plans render incrementally in the main pane.
- The status row (chrome) shows the agent ID, provider, model, session ID, and version.
- Use the wizard (`by config`) if `.env`-discovered credentials are missing.

---

## `by ask` — one-shot question

```bash
by ask 'What is 2+2?'
by ask -m opus 'Explain monads in two paragraphs.'
by ask -a coact-agent -p anthropic -m claude-sonnet-4-6 'Summarize this file' < some-file.txt
```

`ask` is for piping into other tools or scripting. It writes the agent's answer to stdout and exits with status 0 on success. All global options above work here too; the question is the only positional argument.

---

## `by agents` — list available agents

```bash
$ by agents
coact-agent      Cooperative-action agent (default)
acp-agent        ACP-backed agent — forwards to a configured ACP server
…
```

The set of agents is determined at build time — adding a new one requires building a new release.

---

## `by config` — environment bootstrap wizard

```bash
by config
```

An interactive wizard that walks through provider credentials (Anthropic API key, AWS/Bedrock, OpenAI, …), validates each one, and writes them to a project-local `.env` file. The wizard never writes to a system-wide location; the wrapper picks the `.env` up next time `by` is run from that directory or below.

Re-run `by config` any time to refresh credentials or add a new provider.

---

## Environment variables

Variables prefixed with `BY_` are read by the **wrapper** (`by` shell script), not the binary itself.

| Variable | Read by | Purpose |
|---|---|---|
| `BY_VERSION` | install.sh | Pin install to a specific release tag. |
| `BY_ENV_FILE` | wrapper | Force a specific `.env` file path. |
| `BY_NO_DOTENV` | wrapper | Skip `.env` discovery entirely. |
| `BY_JAR` | wrapper | Run via `java -jar by.jar` instead of the native binary. |
| `BY_INSTALL_DIR` | wrapper / bb install:ata | Override install location (default: `~/.local/bin`). |
| `BRAINYARD_ROOT` | binary | Hint at the project root; useful when `.env` discovery isn't enough. |

LLM provider credentials are read from the environment by their conventional names (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `AWS_PROFILE`, `AWS_REGION`, …) and conventionally placed in `.env` by `by config`.

---

## Logging

Brainyard logs to `~/.brainyard/logs/agent-tui-app.log` by default (falling back to `/tmp/` if `$HOME` is not writable). Verbose mode (`-v`) also prints to stderr.

The log is structured (mulog events) and useful for filing bugs. Attach it when reporting an issue. The binary never sends telemetry over the network.

---

## See also

- [`install.md`](install.md) — install & verification.
- [`deploy-design.md`](deploy-design.md) — release architecture, rollout plan, why the binary is called `by`.
- [`../README.md`](../README.md) — overview & quick start.
