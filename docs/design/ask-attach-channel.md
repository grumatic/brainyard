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
| server → client | `{:status :ok :answer "…" :usage {…} :provider "openai" :model "gpt-4o-mini" :agent "coact-agent"}` |
| server → client | `{:status :error :error "…"}` |

The OK response stamps the live session's `:provider`/`:model`/`:agent` (read
at answer time) so a `--json` attach client can report which LM actually
answered — see §11.

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

### Provider / model / agent resolution

`--attach` delegates the turn to the **live session's** agent, so the answer is
produced with that session's own provider, model, and agent — whatever it was
launched with or `/model`-switched to mid-session. The attach client
(`cmd-ask-attach`) reads only the question and `--timeout`; it never sets up an
LM. The `ask` subcommand's LM-selection flags (`-p/--provider`, `-m/--model`,
`-a/--agent`, `-n/--max-iterations`) therefore **do not apply** to `--attach` —
they belong to the one-shot path that mints a throwaway agent. To avoid the
footgun of silently ignoring them, the client emits a one-line stderr warning
when any are passed alongside `--attach` (stdout stays a clean answer).

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

> **Generalizing the channel.** Extending `ask.sock` into a full session control
> channel (live-session discovery, `:op :inject` data connectors, `:op :subscribe`
> event/display streams) — and the single-owner enforcement those require — is designed
> in [`session-channel-extensions.md`](session-channel-extensions.md).

## 10. Out of scope (v1)

- **Streaming** intermediate iterations to the client (the protocol reserves
  `:op` for it; v1 is blocking-until-final-answer).
- **Cross-project** attach (sessions are project-scoped).
- **Auth beyond filesystem perms** — the 0600 socket inside the project's
  `.brainyard` inherits the directory's trust boundary, same as `nrepl-port`.

## 11. JSON output (`by ask --json`)

`by ask --json` emits a single JSON object on stdout instead of the bare
answer, for scripting:

```json
{"success":true,"answer":"4","provider":"openai","model":"gpt-4o-mini",
 "agent":"coact-agent","session-id":"ask-…","usage":{…}}
```

- Works for **both** the one-shot path and `--attach`. For `--attach` the
  provider/model/agent are the live session's (stamped by the server, §4); for
  the one-shot path they're the throwaway agent's resolved LM.
- Failures yield `{"success":false,"error":"…"}` with exit 1 (covers a missing
  question, an unreachable/absent session, a missing API key, and a turn error).
- stdout stays **pure JSON**: incidental console output ("LM configured", agent
  `emit!`) is redirected to stderr for the duration of the run. (The dotenv
  banner already goes to stderr.)
- Shares the `json-opt` flag and `print-json!` helper with
  `by sessions list --json` / `by agents --json`.
