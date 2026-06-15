# `by ask --attach` — the side ask channel

## 1. Motivation

`by ask "<question>"` is one-shot and **stateless**: it spins up a throwaway
agent (`ask-<millis>`), runs a single turn, prints the answer, and exits. There
is no way to ask a question *of a session that is already running* — e.g. a
long-lived `by run` TUI that has loaded context, memory, and an in-flight task
list.

The **side ask channel** closes that gap. A running TUI session listens on a
per-session Unix domain socket; `by ask --attach <session-id>` connects to it,
injects the question into that session's normal turn queue, waits for the
answer, prints it, and exits. The question runs through the *same* path a
keyboard turn takes, so:

- it serializes with the human's own turns (never races a live turn);
- it sees the session's full live state (memory, working dir, task roster);
- it is **visible** — the Q&A renders into that session's tab, exactly like a
  `task$wakeup` auto-ask.

## 2. Shape

```
 client process                          server: running `by run` TUI process
┌────────────────────────┐              ┌───────────────────────────────────────┐
│ by ask --attach <sid>  │              │  per-session ask-listener (daemon thr) │
│   "what's the status?" │              │                                        │
│                        │  AF_UNIX     │  accept → read req → inject-side-ask!   │
│  connect ───────────────────────────▶ │     │                                  │
│  send {:op :ask …}     │ ask.sock     │     ▼                                  │
│                        │              │  enqueue-input! q {:agent ag           │
│                        │              │                   :source :side-ask    │
│                        │              │                   :reply  promise}      │
│                        │              │     │                                  │
│                        │              │     ▼  (existing TUI input queue)       │
│                        │              │  tui-queue-process-fn                   │
│                        │              │     → run-ask-lifecycle → agent/ask     │
│                        │              │     → (deliver promise result)          │
│  ◀──────────────────────────────────  │  write {:status :ok :answer …}         │
│  print :answer; exit   │              │  close conn                            │
└────────────────────────┘              └───────────────────────────────────────┘
```

## 3. On-disk contract

Each persisted session already lives at
`<project>/.brainyard/sessions/<session-id>/`. The listener binds a socket at:

```
<project>/.brainyard/sessions/<session-id>/ask.sock      (0600)
```

resolved via `persist/file-of` (new `:ask-sock` entry in the `filenames` map).
On bind, the absolute path is also recorded into that session's `meta.edn` as
`:ask-socket-path` so a client can discover it without reconstructing the path
convention. The socket file is **unlinked on stop** (session close / `stop!` /
JVM shutdown hook) and a stale file left by a crashed process is unlinked
before re-bind.

> **Project scope.** Sessions are project-scoped (see `CLAUDE.md`). `--attach`
> resolves only sessions under the *current project's* `.brainyard`, the same
> constraint as `by sessions list`.

## 4. Wire protocol

EDN, one map per line (`pr-str` emits single-line EDN — embedded newlines in a
question are escaped, so newline framing is safe). Both ends are `by`, so EDN is
the natural lingua franca.

| Direction | Message |
|-----------|---------|
| client → server | `{:op :ask :question "…" :timeout-ms 120000}` |
| server → client | `{:status :ok :answer "…" :usage {…}}` |
| server → client | `{:status :error :error "…"}` |

`:op` is reserved for forward-compatibility (future `:cancel`, `:status`, a
streaming variant). Unknown ops return `{:status :error :error "unknown op"}`.

## 5. Concurrency & serialization

There is exactly **one** TUI input queue per process
(`agent.core.queue`, FIFO, max 10, single worker future). Every tab's turns —
keyboard, `task$wakeup`, and now `:side-ask` — flow through it, so the whole
process runs one agent turn at a time. Targeting a specific (possibly
non-active) session is done the way `task$wakeup` already does it: pass
`{:agent ag}` in the queue item's `opts`; the process-fn pins render routing to
that agent's session via `*render-session-idx*`.

Result capture is the one new wrinkle. The queue is otherwise fire-and-forget.
We attach a `promise` to the queue item's `opts` (`:reply`); after
`run-ask-lifecycle` returns, `tui-queue-process-fn` delivers the ask result to
it. The listener thread `deref`s the promise with the client-supplied timeout
and writes the response. `agent/ask` ignores unknown `opts` keys
(`:reply`/`:agent`), so this is additive — existing callers pass no `:reply`.

## 6. Lifecycle

Mirrors the opt-in clj-nrepl server precedent (`start-nrepl-server-if-enabled!`
/ `stop-nrepl-server!`), but **per session** and **on by default**:

- **start** — in `create-tui-agent!` (the single chokepoint for every agent,
  session 0 and new tabs alike), gated on config `:ask-channel-enabled?`
  (default `true`). The listener captures that session's `ag` in its handle-fn.
- **stop** — a before-close hook (registered in `start!`) stops the listener
  for a closing session's agent; `stop!` and the JVM shutdown hook stop all
  remaining listeners. Stop closes the channel, interrupts the accept loop, and
  unlinks the socket file.

A process-global registry atom `!ask-listeners` maps `session-id → handle`.

## 7. Config

Two keys in `core.config/config-schema`:

| key | type | default | meaning |
|-----|------|---------|---------|
| `:ask-channel-enabled?` | boolean | `true` | open `ask.sock` per session |
| `:ask-timeout-ms` | int | `120000` | server-side cap on a side-ask turn |

Client `--timeout` overrides per call (still bounded by the server cap).

## 8. Component layout

New Polylith component `ask-channel` keeps socket/protocol code out of the TUI
base and unit-testable without a terminal:

```
components/ask-channel/
  src/ai/brainyard/ask_channel/
    interface.clj          ; start-listener! stop-listener! ask-via-socket! socket-path
    core/protocol.clj      ; read-msg / write-msg (EDN, newline-framed)
    core/server.clj        ; AF_UNIX ServerSocketChannel accept loop
    core/client.clj        ; connect, send, recv
  test/ai/brainyard/ask_channel/roundtrip_test.clj
```

The TUI base (`agent-tui`) depends on it for the server; the project
(`agent-tui-app`) depends on it for the `--attach` client.

## 9. GraalVM note

Java 25 / GraalVM 25 ships `java.net.UnixDomainSocketAddress` and NIO
`ServerSocketChannel.open(StandardProtocolFamily/UNIX)`. AF_UNIX NIO channels
work under native-image (GraalVM ≥ 22) without reflection config. This is the
one real native risk: it is validated on the **native binary** (not just
`BY_JAR=1`) in the verification step, since channel providers occasionally need
`--enable-native-access` or a resource hint that only surfaces in the AOT image.

## 10. Out of scope (v1)

- **Streaming** intermediate iterations to the client (the protocol reserves
  `:op` for it; v1 is blocking-until-final-answer).
- **Cross-project** attach (sessions are project-scoped).
- **Auth beyond filesystem perms** — the 0600 socket inside the project's
  `.brainyard` inherits the directory's trust boundary, same as `nrepl-port`.
