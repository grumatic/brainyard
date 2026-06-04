# Sharing a session over the web (`by --web`)

`by --web` exposes a running Brainyard TUI session in the browser using
[**ttyd**](https://github.com/tsl0922/ttyd), so other people (or your own other
devices) can watch — or drive — the same agent session over HTTP.

> ⚠ **A web session is a shared shell into an agent that can run code and tools.**
> Anyone who can reach the URL **and** the credentials can drive it. Auth is
> always required and binding defaults to localhost; read [Security](#security)
> before exposing it beyond your own machine.

## How it works

`--web` is a thin launcher. It wraps `by run …` in `ttyd`, which spawns the
child inside a real PTY and bridges that PTY to the browser over WebSocket.
Because the child gets a genuine terminal, everything in the TUI (raw-mode
input, alt-screen, resize, colors) works exactly as it does locally.

ttyd serves **one shared session** to all connected browsers by default — every
client sees and (if writable) drives the same agent.

There are two modes:

| Mode | Flag | What's shared |
|---|---|---|
| **Tier 1** (default) | `--web` | A **fresh** `by run` session created inside ttyd. All browser clients co-drive it. Your launching terminal shows ttyd logs, not the session. |
| **Tier 2** | `--web-tmux` | The TUI runs in a private detached **tmux** session that ttyd serves. The launching terminal stays a **dashboard** (connection info stays visible); drive locally from another terminal or the browser. All clients share **one live pane**. |

Use Tier 2 when you want a persistent session that several people (and your own
other terminals) attach to and drive live.

## Prerequisites

- **ttyd** on `PATH` (required for any `--web` mode):

  ```bash
  brew install ttyd          # macOS
  sudo apt install ttyd      # Debian/Ubuntu
  # others: https://github.com/tsl0922/ttyd#installation
  ```

- **tmux** on `PATH` (required only for `--web-tmux`):

  ```bash
  brew install tmux          # macOS
  sudo apt-get install tmux  # Debian/Ubuntu
  ```

If a required tool is missing, `by` prints an install hint and exits.

## Quick start

```bash
# Tier 1 — share a fresh session on http://127.0.0.1:7681 (password auto-generated)
by --web

# Forward the usual run flags into the shared session
by --web -a coder -p bedrock -m amazon.nova-pro-v1:0

# Pick a port and your own credentials
by --web --web-port 8080 --web-user alice --web-pass s3cret

# Observers only — nobody can type
by --web --web-readonly

# Tier 2 — persistent shared tmux session; the dashboard stays in this terminal
by --web-tmux
```

On launch you'll see a banner like:

```
🌐 Brainyard web session (ttyd)
   URL:    http://127.0.0.1:7681
   Auth:   by / a1b2c3d4e5f6   (auto-generated)
   Mode:   writable · shared · localhost-only
   Remote: localhost only. To reach it from another machine, tunnel:
             ssh -L 7681:127.0.0.1:7681 <this-host>
   Press Ctrl-C to stop sharing.
```

## Flags

| Flag | Default | Notes |
|---|---|---|
| `--web` | off | Tier 1: share a fresh session via ttyd. |
| `--web-tmux` | off | Tier 2: share via a private tmux session; the launching terminal stays a dashboard. Implies `--web`. |
| `--web-port N` | `7681` | Listen port. `0` = random (printed by ttyd). |
| `--web-bind ADDR` | `127.0.0.1` | Address to bind. `127.0.0.1` = localhost only. |
| `--web-user U` | `by` | Basic-auth username. |
| `--web-pass P` | auto-generated | Basic-auth password (printed in the banner). |
| `--web-readonly` | off | Clients may watch but not type. |
| `--web-max-clients N` | `0` | Max simultaneous clients (`0` = unlimited). |
| `--web-once` | off | Stop sharing after the first client disconnects. |

Every flag has a `BY_WEB_*` environment equivalent (resolved as
**flag > env > default**), so you can default these in your `.env`:

| Env var | Flag |
|---|---|
| `BY_WEB=1` | `--web` |
| `BY_WEB_TMUX=1` | `--web-tmux` |
| `BY_WEB_PORT` | `--web-port` |
| `BY_WEB_BIND` | `--web-bind` |
| `BY_WEB_USER` / `BY_WEB_PASS` | `--web-user` / `--web-pass` |
| `BY_WEB_READONLY=1` | `--web-readonly` |
| `BY_WEB_MAX_CLIENTS` | `--web-max-clients` |
| `BY_WEB_ONCE=1` | `--web-once` |

## Security

`by` can execute code and call tools, so treat a writable web session like
handing someone a shell. The defaults are conservative:

- **Auth is always on.** If you don't pass `--web-pass`, a random password is
  generated and printed. There is no way to disable authentication.
- **Localhost by default** (`--web-bind 127.0.0.1`). The session is unreachable
  from other machines unless you change the bind or tunnel in.
- **Origin checking is always enabled** (ttyd `-O`) to mitigate cross-site
  WebSocket hijacking.

**Preferred way to share remotely — an SSH tunnel** (keeps the port off the
network entirely):

```bash
# on the remote machine
by --web
# on your laptop
ssh -L 7681:127.0.0.1:7681 user@remote-host
# then open http://127.0.0.1:7681 locally
```

If you must bind beyond localhost (`--web-bind 0.0.0.0`), put it behind a
reverse proxy with TLS, use a strong password, and limit clients
(`--web-max-clients`). `by` prints a warning when bound beyond localhost.

## Tier 2 details (`--web-tmux`)

- The TUI runs in a **detached tmux session on a private socket** (`tmux -L
  by-web-<id>`), so it never collides with your existing tmux sessions.
- The **launching terminal stays a dashboard** — it keeps the banner (URL +
  credentials) visible the whole time and does *not* take over the screen, so
  you can read and share the connection info. (Auto-attaching would hide it.)
- **Drive locally** from another terminal with the command printed in the
  banner — `tmux -L by-web-<id> attach -t brainyard` — or just open the URL in a
  browser. Every client (local attach + browsers) shares one live pane.
- Because the session runs inside tmux, the agent uses tmux **Mode B** (side
  panes / popups render in the shared session).
- `Ctrl-C` in the dashboard terminal stops the share and tears down the tmux
  session; quitting the agent ends the session too. Detaching a local attach
  (`Ctrl-b d`) just leaves that one terminal — the share keeps running.

## Stopping

Press **`Ctrl-C`** in the launching terminal. `by` terminates ttyd and, for
Tier 2, kills the private tmux server and removes its socket. The same cleanup
runs on `SIGTERM`.

## Troubleshooting

- **`ttyd is not on PATH`** — install ttyd (see [Prerequisites](#prerequisites)).
- **`tmux is not on PATH`** — required only for `--web-tmux`; install tmux.
- **`could not resolve how to relaunch the TUI`** — only happens outside a
  normal install (e.g. an unusual dev setup). Set `BY_WEB_SELF` to the command
  that launches `by`, e.g. `BY_WEB_SELF='bb tui'` or
  `BY_WEB_SELF=/path/to/by`.
- **Browser shows a blank/garbled screen** — use a modern browser; ttyd's
  terminal needs WebGL2.

## See also

- [`usage.md`](usage.md) — all subcommands and flags.
- [ttyd](https://github.com/tsl0922/ttyd) — the underlying terminal-over-web server.
