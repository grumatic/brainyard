# Talking to a running session (the ask channel)

Every running `by` session opens a small Unix-domain socket that lets another
process — a script, a webhook handler, a data pipeline, another agent — **talk to
that live session**: ask it a question, push data into it, watch what it does, or
cancel its current turn.

This is the *ask channel*. The simplest way to use it is the built-in
`by ask --attach` command; everything past that is a plain line-of-EDN protocol
you can speak from any language.

> **Mental model: one owner, many clients.** Exactly one `by` process *owns* a
> session (holds its files and binds its socket). Everyone else is a *client* of
> that socket. Clients can read freely and subscribe to events concurrently;
> anything that mutates the session (a question, a turn) is serialized through the
> owner's normal turn queue, so it never races the human at the keyboard.

> ⚠ **The socket is a control channel into an agent that runs code and tools.** It
> lives at `0600` inside the project's `.brainyard/` directory and inherits that
> directory's trust boundary — the same as the nREPL port. It is a *local* IPC
> channel, not an authenticated network API. See [Security](#security).

---

## Quick start: `by ask --attach`

If a session is open in a `by run` TUI, ask it a question from another terminal:

```bash
by ask --attach <session-id> "what's the current task status?"
```

The question is injected into that session's turn queue, runs through the *same*
path a keyboard turn takes (visible in the tab, serialized with the human's
turns), and the answer is printed to stdout. Add `--json` for a machine-readable
result:

```bash
by ask --attach <session-id> --json "summarize what you just did"
# {"success":true,"answer":"…","provider":"claude-code","model":"opus","agent":"coact-agent","session-id":"…"}
```

`--attach` answers with the **live session's own** provider/model/agent, so the
LM-selection flags (`-p`/`-m`/`-a`) don't apply to it (they belong to the
one-shot `by ask` path that spins up a throwaway agent).

To find a session id, see [Discovery](#discovering-live-sessions).

---

## Discovering live sessions

Sessions are project-scoped. List the ones in the current project:

```bash
by sessions list --json
```

Each row carries a **connect descriptor** so an external tool knows what's
reachable and how:

| field | meaning |
|---|---|
| `session-id` | the id to pass to `--attach` / connect to |
| `live?` | `true` when a `by` process currently owns it (PID-checked) |
| `owner-pid` | the owning process id (or `null`) |
| `ask-socket-path` | absolute path of its ask socket — **always use this value**; for deep project trees it is relocated under the temp dir (see note below) |
| `ops` | the verbs this session's socket answers, e.g. `["ask","status","inject","cancel","subscribe"]` |
| `label`, `agent`, `model` | display metadata |

Filter to just the sessions you can actually talk to:

```bash
by sessions list --live              # only sessions open right now
by sessions list --live --json       # …as JSON
```

`live?` is honest across crashes: a clean exit removes the lock, and a crashed
process leaves a stale lock whose dead PID still reads `live? = false`.

Use `-C <dir>` (or `BY_PROJECT_DIR`) to inspect a different project:

```bash
by sessions list --live --json -C /path/to/repo
```

---

## The wire protocol

The socket speaks **EDN, one map per line** (a `\n`-terminated `pr-str` map per
frame). You connect to the `ask-socket-path` from the discovery step, write one
request frame, and read response frame(s).

Request shape: `{:op <verb> …}`. Response shape: `{:status :ok …}` or
`{:status :error :error "…"}`.

> **Always connect to the `ask-socket-path` from discovery — don't reconstruct
> `<session-dir>/ask.sock`.** Unix socket paths are length-limited (~104 bytes on
> macOS). When a session lives under a deep project tree, the natural path would
> overflow that, so `by` binds the socket at a short fallback path under the temp
> dir (`<tmpdir>/by-<hash>.sock`) instead and records the real location in
> `ask-socket-path`. (`by ask --attach` handles this for you.)

| verb | mode | what it does |
|---|---|---|
| `:ask` | request → one reply | inject a question, block for the answer |
| `:status` | request → one reply | non-blocking snapshot of the session |
| `:inject` | request → one reply | push data in (artifact / turn / memory) |
| `:cancel` | request → one reply | cancel the running turn |
| `:subscribe` | request → **many** replies | stream runtime events until you disconnect |

A handful of these are demonstrated below in Python (`socket.AF_UNIX`), but any
language that can write a line and read a line to a Unix socket works.

### `:status` — is it busy?

```python
import socket
def call(path, line):
    s = socket.socket(socket.AF_UNIX); s.connect(path)
    s.sendall((line + "\n").encode())
    resp = s.makefile("r").readline().strip()
    s.close(); return resp

call(sock, "{:op :status}")
# {:status :ok, :state :idle, :pending-turns 0, :session-id "agt-…",
#  :agent "coact-agent", :provider "claude-code", :model "opus", :pid 41306}
```

`:state` is `:idle` or `:running`; `:pending-turns` is how many turns are queued.
A scheduler can poll `:status` to decide whether to poke a session.

### `:ask` — ask and wait

```python
call(sock, '{:op :ask :question "what is 2+2?" :timeout-ms 60000}')
# {:status :ok, :answer "4", :usage {…}, :provider "claude-code", :model "opus", :agent "coact-agent"}
```

This is exactly what `by ask --attach` sends.

### `:inject` — push data in (the data connector)

`:inject` pushes external data *into* the session. Pick a **sink** with `:as`:

```python
# (a) a live artifact — appears in the agent's context next turn, no turn forced.
#     The canonical "data connector": keep external state fresh for the agent.
call(sock, '{:op :inject :as :artifact :name "DB Orders" :content "4021 rows pending"}')
# {:status :ok, :injected :artifact, :id "note:db-orders", :name "DB Orders"}

#     A file-backed artifact reloads fresh every turn — point it at a file your
#     pipeline rewrites:
call(sock, '{:op :inject :as :artifact :path "/abs/path/to/metrics.md" :pin? true}')

# (b) a turn — inject as if typed. Fire-and-forget (event trigger):
call(sock, '{:op :inject :as :turn :text "deploy finished, verify prod" :await? false}')
# {:status :ok, :injected :turn, :queued true}
#     …or block for the answer with :await? true (behaves like :ask).

# (c) project memory — write <project>/.brainyard/memory/<slug>.md
call(sock, '{:op :inject :as :memory :slug "deploy-log" :content "v9 shipped at noon"}')
# {:status :ok, :injected :memory, :slug "deploy-log", :path "…/memory/deploy-log.md"}
```

Which sink? **Artifact** when you want the agent to *see* current external state
without interrupting it (sensors, query results, dashboards). **Turn** when an
external event should *make the agent act* (webhooks, CI, cron). **Memory** for
durable notes the agent curates over time.

### `:cancel` — stop the current turn

```python
call(sock, "{:op :cancel}")
# {:status :ok, :cancelled true}     ; true if a turn was actually running, else false
```

### `:subscribe` — stream events

`:subscribe` keeps the connection **open** and pushes one frame per matching
runtime event until you disconnect. Events are scoped to that session.

```python
import socket
def subscribe(path, events):
    s = socket.socket(socket.AF_UNIX); s.connect(path)
    f = s.makefile("rw")
    f.write("{:op :subscribe :events [%s]}\n" % " ".join(events)); f.flush()
    print("ack:", f.readline().strip())          # {:status :ok, :subscribed [...]}
    for line in f:                                # one frame per event
        print("event:", line.strip())

subscribe(sock, [":agent.iteration/post", ":agent.tool-use/post"])
# event: {:event :agent.iteration/post, :sid "agt-…", :payload {…}}
```

Each frame is `{:event <key> :sid <session-id> :payload {…}}`. Payloads are
sanitized to plain EDN (the live agent object is dropped, non-EDN values are
stringified). A slow consumer drops events rather than stalling the agent. The
event keys come from the agent runtime's hook catalog — common ones:

| event | fires when |
|---|---|
| `:agent.ask/pre` / `:agent.ask/post` | a turn starts / finishes |
| `:agent.iteration/post` | each reasoning iteration completes |
| `:agent.tool-use/post` | a tool call returns |
| `:agent.code-eval/post` | a code block finishes |
| `:task/created` / `:task/completed` | a background task starts / ends |
| `:display` | the session renders output (see [Display sink](#display-sink-mirroring-what-the-agent-shows)) |

> An external **event trigger** is `:subscribe` (watch) plus
> `:inject :as :turn` (react) — subscribe to a session, and when an event of
> interest arrives, inject a turn telling it what to do next.

---

## Display sink (mirroring what the agent shows)

To mirror a session's rendered output (for a remote view, a logger, a TTS
reader), you have two options.

**Real-time, over the socket** — subscribe to the `:display` event. One frame is
pushed per `emit!`, scoped to the session:

```python
subscribe(sock, [":display"])
# {:event :display, :sid "agt-…", :payload {:session-id "agt-…", :text "…"}}
```

`:text` is the exact chunk the session rendered, so it **may contain ANSI escape
codes** — strip them if you want plain text. (`:display` is the socket
counterpart of the file tail below; it carries the same content.)

**Zero-code, from disk** — tail the scrollback stream, written live:

```bash
tail -F <project>/.brainyard/sessions/<session-id>/scrollback.stream.txt
```

---

## Security

- The socket is `0600` and lives inside the project's `.brainyard/` directory; it
  trusts whoever can read that directory, exactly like the nREPL port.
- It is a **local** IPC channel. There is no authentication beyond filesystem
  permissions and no network transport — don't expose it across hosts. (To share
  a session over a network, use [`by --web`](web-sharing.md), which has auth.)
- `:ask` / `:inject :as :turn` run real agent turns (code, tools). Treat write
  access to the socket as equivalent to typing at the session's keyboard.

---

## Reference

- Design & rationale: [`design/session-channel-extensions.md`](design/session-channel-extensions.md)
  (single-owner model, discovery, the `:op` verbs, streaming) and
  [`design/ask-attach-channel.md`](design/ask-attach-channel.md) (the original
  `by ask --attach` channel).
