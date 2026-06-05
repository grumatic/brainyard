# Containing a session in a sandbox (`by --sandbox`)

`by --sandbox` runs a Brainyard TUI session inside a macOS **seatbelt** sandbox
(via [`sandbox-exec`](https://keith.github.io/xcode-man-pages/sandbox-exec.1.html)),
so an agent — or any tool it runs — **cannot write outside your workspace**.

> ⚠ **`--sandbox` contains *writes*, not network or code execution.** A sandboxed
> agent can still call any LLM and run any command; it just can't write files
> outside the allowlist. It is a blast-radius limiter, not a jail. Use
> `--sandbox-no-network` to also cut off the network.

## How it works

`--sandbox` is a thin launcher, parallel to [`--web`](web-sharing.md). It
re-execs `by run …` under `sandbox-exec` with a generated **write-containment**
profile. Because seatbelt only mediates *new* syscalls and leaves inherited file
descriptors untouched, the sandboxed session runs in the **same terminal** —
raw-mode input, alt-screen, resize and colors all work exactly as they do
locally (unlike `--web`, which serves a PTY over the network).

The relaunched process carries `BY_SANDBOX_CHILD=1` so it runs the TUI instead
of recursing into another `sandbox-exec`. `Ctrl-C` propagates to the child and
the launcher exits with the child's exit code.

## Default policy: write-containment

The generated profile denies everything by default, then re-allows the broad,
harmless capabilities and tightly scopes **writes**:

| Capability | Default | Why |
|---|---|---|
| **Read** (any path) | ✅ allowed | The binary must read itself, system dylibs, the CA bundle, locale, `.env`/`.git`, config. Reads aren't the threat model. |
| **Network** | ✅ allowed | LLM HTTPS (Anthropic/OpenAI/Bedrock/Google/Groq), Tavily, Ollama, MCP. Toggle off with `--sandbox-no-network`. |
| **Process exec / fork** | ✅ allowed | The agent's job is running tools and code (bash, code-eval, git, tmux). |
| **Write** | ⛔ denied **except** the allowlist below | The actual containment. |

Writable roots (the allowlist):

- `~/.brainyard/**` — sessions, memory DB, logs, config, `.env`, tools, skills, hooks
- the **project / cwd subtree** — the repo you launched in
- `$TMPDIR`, `/tmp` (and their canonical `/private/...` forms) — JVM/SQLite scratch
- `~/Library/Caches`
- `/dev` (TTY raw mode needs `ioctl`)
- anything you add with `--sandbox-allow-write`

A write anywhere else (`~/.ssh`, `~/.aws/credentials`, `/etc`, a sibling repo)
fails with `Operation not permitted`.

## Prerequisites

- **macOS only.** `sandbox-exec` ships with macOS at `/usr/bin/sandbox-exec`. It
  is Apple-deprecated but still present and is the same mechanism Apple's own
  daemons, Chrome, and other CLIs rely on. On non-macOS, `--sandbox` prints a
  warning and runs **unsandboxed**.

## Quick start

```bash
# Contain a session — writes confined to ~/.brainyard, the cwd subtree, /tmp
by --sandbox

# Forward the usual run flags
by --sandbox -a coder -p bedrock -m amazon.nova-pro-v1:0

# Allow an extra writable root (repeat or comma-separate)
by --sandbox --sandbox-allow-write ~/scratch --sandbox-allow-write /data
by --sandbox --sandbox-allow-write ~/scratch,/data

# Cut off the network too (blocks LLM calls)
by --sandbox --sandbox-no-network

# Use your own seatbelt profile
by --sandbox --sandbox-profile ./my.sb
```

On launch you'll see a banner like:

```
🛡  Brainyard sandboxed session (sandbox-exec)
   Writes:  contained to ~/.brainyard, /Users/you/proj, $TMPDIR, /tmp
   Network: allowed (LLM calls work)
   Press Ctrl-C to stop.
```

## Flags

| Flag | Default | Notes |
|---|---|---|
| `--sandbox` | off | Run the session in a seatbelt sandbox (macOS only). |
| `--sandbox-profile PATH` | — | Use a custom `.sb` profile instead of the generated default. |
| `--sandbox-allow-write PATH` | — | Extra writable root; repeat or comma-separate. |
| `--sandbox-no-network` | off | Deny all network (blocks LLM calls). |

Every flag has a `BY_SANDBOX_*` environment equivalent (resolved as
**flag > env > default**), so you can default these in your `.env`:

| Env var | Flag |
|---|---|
| `BY_SANDBOX=1` | `--sandbox` |
| `BY_SANDBOX_PROFILE` | `--sandbox-profile` |
| `BY_SANDBOX_ALLOW_WRITE` | `--sandbox-allow-write` (comma-separated) |
| `BY_SANDBOX_NO_NETWORK=1` | `--sandbox-no-network` |
| `BY_SANDBOX_SELF` | override how the TUI is relaunched (dev/jar escape hatch) |
| `BY_SANDBOX_CHILD=1` | internal re-entrancy guard (set by the launcher) |

## Writing a custom profile

A profile passed with `--sandbox-profile` is loaded with `sandbox-exec -f` and
receives these parameters via `-D`:

```
(param "HOME")   (param "CWD")   (param "PROJECT_DIR")   (param "TMPDIR")
```

Start from the reference at
[`components/os-sandbox/resources/ai/brainyard/os_sandbox/default.sb`](../components/os-sandbox/resources/ai/brainyard/os_sandbox/default.sb)
— it is the human-readable form of the generated default. **Keep the
read/exec/network baseline** or `by` won't even start (it must read its own
binary and system dylibs). Validate a profile in isolation before trusting it:

```bash
# write outside the allowlist must FAIL
sandbox-exec -f my.sb -D HOME="$HOME" -D CWD="$PWD" -D PROJECT_DIR="$PWD" -D TMPDIR="$TMPDIR" \
  sh -c 'echo x > /etc/should-fail'        # → Operation not permitted
# write inside ~/.brainyard must PASS
sandbox-exec -f my.sb -D HOME="$HOME" -D CWD="$PWD" -D PROJECT_DIR="$PWD" -D TMPDIR="$TMPDIR" \
  sh -c 'echo ok > ~/.brainyard/probe'     # → 0
```

## Relationship to `--web`

`--web` and `--sandbox` are **mutually exclusive in v1** — passing both errors.
Sandboxing the session running inside a web share is planned but not yet wired.

## Limitations

- **Network is all-or-nothing.** Seatbelt can't filter by DNS host, so v1 offers
  only allow-all or `--sandbox-no-network`. Per-host egress control is future
  work (e.g. pair with an egress proxy).
- **`sandbox-exec` is Apple-deprecated.** It still works everywhere today; if it
  ever disappears, `by` falls back to running unsandboxed with a warning.
- **Profiles can be brittle across macOS versions** (path canonicalization, SBPL
  operators). The generated default is conservative and validated; a custom
  profile is your responsibility.
- **Not the same as `clj-sandbox`.** `clj-sandbox` is the in-process SCI layer
  that contains *Clojure code-eval*; `--sandbox` (this) is an OS-level wrapper
  around the *whole `by` process*. Different layers, different concerns.

## Troubleshooting

- **`Operation not permitted` on a path you need to write** — add it with
  `--sandbox-allow-write <path>`.
- **The binary won't start under a custom profile** — you over-tightened it;
  restore `(allow file-read*)` and `(allow process-exec*)`.
- **`could not resolve how to relaunch the TUI`** — only in unusual dev setups;
  set `BY_SANDBOX_SELF` to the launch command, e.g. `BY_SANDBOX_SELF='bb tui'`
  or `BY_SANDBOX_SELF=/path/to/by`.
- **`--sandbox is macOS-only`** — seatbelt is darwin-only; on other platforms the
  session runs unsandboxed.

## See also

- [`usage.md`](usage.md) — all subcommands and flags.
- [`web-sharing.md`](web-sharing.md) — the `--web` launcher this mirrors.
- `components/os-sandbox` — the implementation and the reference `.sb` profile.
