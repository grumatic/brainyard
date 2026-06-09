# Agent TUI — Architecture

*The single-process, in-process renderer that ships as the `by` binary,
with optional tmux integration for streaming side-channels onto extra
panes when the user is already inside a tmux session.*

Status: **as-built** (May 2026)
Audience: agent runtime maintainers, TUI base maintainers.

The agent-tui evolved through two architectures. An earlier two-process
design split the runtime between `by-host` (daemon) and `by-ui`
(orchestrator) over a control socket, with per-pane FIFOs ferrying
ANSI bytes between them. That split bought detach/re-attach survival
at the cost of substantial complexity, and the as-built design that
won is a **single in-process renderer** with tmux as an optional
side-channel. The `by-ui` base is gone; the renderer paints the user's
terminal directly.

The `agent-tui-tmux` and `agent-tui-persist` components survive — the
former is consumed *internally* by the renderer in Mode B; the latter
keeps the per-session disk layout the next `by` invocation resumes from.

---

## 1. Process topology

**One process. One renderer. No daemon.**

```
┌──────────────────────────────────────────────────────────────────┐
│ by (single process)                                              │
│                                                                  │
│  ┌────────────────── Renderer (in-process) ─────────────────┐   │
│  │   alt-screen / scroll region / live blocks               │   │
│  │   input pane (raw mode, terminal.clj)                    │   │
│  │   popover gate                                           │   │
│  │                                                          │   │
│  │   ┌── TmuxSideChannel (optional, Mode B only) ───────┐   │   │
│  │   │   - split-pane + spawn `cat <fifo>`               │   │   │
│  │   │   - display-popup (questionnaire)                 │   │   │
│  │   │   - capture-pane snapshotting                     │   │   │
│  │   └───────────────────────────────────────────────────┘   │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                   │
│                              ▼  in-process call                  │
│  ┌────────────── components/agent runtime ──────────────────┐   │
│  │   hooks · BT · queues · MCP · memory · sessions          │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                   │
│                              ▼                                   │
│  ┌────────────── components/agent-tui-persist ──────────────┐   │
│  │   per-session EDN store, scrollback, snapshots, lock     │   │
│  └──────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘

         Tmux server (system) — ONLY in Mode B, only side panes
         ┌────────────────────────────────────────────────┐
         │   user's existing session                      │
         │   ┌────────────────────────────────────────┐   │
         │   │ window N (user's current)              │   │
         │   │  ┌──────────────────┐  ┌────────────┐  │   │
         │   │  │ pane 0:  `by`    │  │ pane 1:    │  │   │
         │   │  │ (renderer)       │  │ activity   │  │   │
         │   │  │                  │  │ (cat fifo) │  │   │
         │   │  │                  │  │ pane 2:    │  │   │
         │   │  │                  │  │ log tail   │  │   │
         │   │  │                  │  │ (cat fifo) │  │   │
         │   │  └──────────────────┘  └────────────┘  │   │
         │   └────────────────────────────────────────┘   │
         └────────────────────────────────────────────────┘
```

No control socket, no orchestrator process, no per-pane consumer
process owned by Brainyard. The only "extra processes" are `cat`
spawned by tmux inside side panes — those are not owned by `by` and do
nothing but copy bytes from a FIFO to a pane.

---

## 2. Modes (A / B / C)

Mode is decided by `bases/agent-tui/src/ai/brainyard/agent_tui/mode.clj`
from three inputs:

1. Is `tmux` on `$PATH`?
2. Is the process running inside an existing tmux session? (`$TMUX` set?)
3. Did the user pass `--with-tmux`?
4. Is the tmux server actually reachable?

| `--with-tmux` | `tmux` on `$PATH` | `$TMUX` set | server alive | Mode |
|---|---|---|---|---|
| no | no | – | – | A |
| no | yes | no | – | A |
| no | yes | yes | yes | B |
| no | yes | yes | no | A |
| yes | yes | yes | yes | B |
| yes | yes | yes | no | C |
| yes | yes | no | – | C |
| yes | no | – | – | C |

`--inline` is a developer / test flag orthogonal to this table. It
disables alt-screen takeover and runs the same renderer in append-to-
scrollback mode; `CliClient`-based integration tests rely on it.

### Mode A — no tmux

Default trigger: `$TMUX` is empty (whether or not `tmux` is installed)
and `--with-tmux` was not passed.

- In-process ANSI renderer: alt-screen + DECSTBM scroll region +
  sticky live blocks + bottom-row status bar.
- Activity / log content renders **inline** as sticky live blocks
  above the input prompt (`update-live-block!` /
  `freeze-live-block!` / `dispose-live-block!`).
- `/activity show`, `/log show` print a one-line note explaining
  tmux is required.
- Permission / feedback dialogs render in-stream as a **sticky-bottom
  live block** (`permissions.clj` → `layout/update-live-block!` with
  `{:sticky-bottom? true}`) — the prompt stays anchored below all
  iteration / think / todo blocks until answered, intercepted by the
  input reader (`input.clj` → `try-intercept-byte`).
- Persistence is best-effort: scrollback, snapshots, and the input
  draft are written to `~/.brainyard/sessions/<agent-session-id>/`
  on normal exit so the next launch can offer a resume.

### Mode B — in tmux

Default trigger: `$TMUX` is non-empty, `tmux` is on `$PATH`, and the
server is reachable. Forced trigger: `--with-tmux` and the same
conditions hold.

Same renderer as Mode A in the main pane, **plus**:

- The renderer holds a `Tmux` protocol instance (`RealTmux`) wired to
  the live tmux socket and can on demand:
  - **Split a side pane** to host the activity stream
    (`split-window` from the current pane).
  - **Split a side pane** for the log tail.
  - **Open a popup** (`display-popup`) for permission / questionnaire
    dialogs. The popup runs an embedded bash selector with Tab /
    Shift-Tab / ↑ / ↓ / Enter / Esc navigation plus letter and digit
    shortcut fast-paths; the title is rendered in the popup border
    via `--title` and is not duplicated inside the body (see
    [renderer.md §Permissions UI](renderer.md#permissions-ui)).
  - **Capture pane buffers** for snapshotting (`capture-pane -p`).
- Side panes are controlled by slash commands (`/activity`, `/log`,
  `/scrollback`, `/popup`). When closed, the side pane is killed and
  the corresponding stream reverts to in-stream sticky blocks.
- Side panes are **passive readers**: `by` writes ANSI bytes to a
  named FIFO; the side pane runs `cat <fifo>` to render. Input is
  *never* routed through a side pane.
- The user keeps *all* of their tmux: `prefix d` detaches the whole
  tmux session, `prefix [` enters copy-mode, `prefix z` zooms,
  `prefix &` kills the window. While detached, `by` keeps running and
  the kernel buffers writes to the FIFOs (capped) until reattach.

The crucial property: **Mode A and Mode B share the same renderer.**
Mode B just adds the ability to fan some streams onto extra panes and
upgrade dialogs to popups.

### Mode C — `--with-tmux` requested but cannot honour

Trigger: `--with-tmux` is passed but either tmux is not on `$PATH`,
the user is not inside a tmux session, or the tmux server is
unreachable. The renderer never starts; the binary prints guidance
(start a tmux session and re-run, or drop `--with-tmux`) and exits 1.

This is the only mode that refuses to start. The default — without
`--with-tmux` — never produces Mode C: it falls back to Mode A.

---

## 3. Component boundary

| Layer | Responsibilities | Code location |
|---|---|---|
| **Renderer** (in-process) | Alt-screen, scroll region, live blocks, raw input, popover gate, slash command dispatch, status bar. | `bases/agent-tui/` |
| **Mode probe** | Inspect `$TMUX`, `$PATH`, `--with-tmux`, server reachability. Decide A / B / C. | `bases/agent-tui/.../mode.clj` |
| **Tmux side-channel** | Split / kill side panes, write to per-pane FIFOs, open popups, capture pane snapshots. Active only in Mode B. | `bases/agent-tui/.../tmux_side.clj` (consumer) + `components/agent-tui-tmux` (protocol + backends) |
| **Persistence** | Per-session EDN store at `~/.brainyard/sessions/<id>/`: input draft, scrollback ring, snapshot, pending dialogs. | `components/agent-tui-persist` |
| **Agent runtime** | Hooks, BT, queues, MCP, memory, sessions. Unchanged; never imports tmux. | `components/agent` |

The dependency graph collapses: there is no `agent-tui-ui` base, no
`by-ui` binary, no control socket protocol to specify or test. The
`Tmux` protocol from `agent-tui-tmux` survives — it is now used
*internally* by the renderer.

---

## 4. `components/agent-tui-tmux`

Public surface (`interface.clj`):

- **`Tmux` protocol** (`core/protocol.clj`): `version`, `probe-version`,
  `running?`, `new-session!`, `kill-session!`,
  `list-sessions`, `new-window!`, `kill-window!`, `kill-pane!`,
  `rename-window!`, `split-pane!`, `resize-pane!`, `select-pane!`,
  `select-window!`, `send-keys!`, `pipe-pane!`, `capture-pane`,
  `display-popup!`, `set-option!`, `display-message`, `signal!`,
  `run-shell`. (`supports-popup?` is a derived helper `defn` over
  `probe-version`, not a protocol method.)
- **Backends**:
  - `real-tmux` (`core/real.clj`) — shells out to `tmux(1)`.
    `BY_TMUX_DEBUG=1` enables per-call diagnostic logging.
  - `stub-tmux` (`core/stub.clj`) — call recorder for tests.
    Inspect via `stub-calls`, `stub-calls-of`, `stub-last-call`,
    `stub-reset-calls!`.
- **Sinks** (`core/sink.clj`): `pipe-sink`, `writer-sink`,
  `null-sink`, `multi-sink` with channels `{:stream :activity
  :status}`; helpers `write-stream!`, `write-activity!`,
  `write-status!`, `flush-all!`, `close-all!`. `ensure-fifo!` does
  `mkfifo` idempotently.
- **Widgets** (`core/widgets.clj`): sticky live-block analogue at the
  tail of a stream pane (`set-widget!`, `freeze-widget!`,
  `clear-widget!`).
- **Questionnaire** (`core/questionnaire.clj`): unified popup
  primitive — permission, feedback, confirm, picker — with
  validation, default answers, and reply constructors.

The component also still ships a `core/control/` protocol and a
`core/host.clj` adapter from the earlier two-process design. They
remain in the codebase but are not wired into the current single-
process renderer; treat them as latent infrastructure available if a
future redesign needs to re-introduce a host/UI split.

---

## 5. `components/agent-tui-persist`

Per-session directory at `~/.brainyard/sessions/<agent-session-id>/`:

```
meta.edn               agent-id, defagent-id, model, started-at, working-dir,
                       side-pane layout hint, draft input
messages.log           append-only EDN events (input lines, hooks)
scrollback.stream.txt  rotated ANSI byte log for the stream
scrollback.activity.txt  optional activity scrollback
layout.edn             {:activity-pane? :layout-mode}
pending-dialogs.edn    open permission/feedback popups (atomic write)
permissions.edn        :action-permissions cache
queue.edn              input queue snapshot
todo.edn               TODO snapshot
status.edn             latest status-bar fields
lock                   PID lockfile (single-writer guarantee)
panes/<window-id>/
  stream.fifo          per-window FIFOs (created lazily — Mode B only)
  activity.fifo
  status.fifo
```

Modules: `paths.clj` (canonical filenames, session listing),
`messages.clj` (append-only log), `scrollback.clj` (size-capped
rotation), `snapshots.clj` (atomic EDN snapshots), `lock.clj`,
`eviction.clj` (TTL purge, default 14 days), `tree.clj` (session-tree
navigation for fork/clone), `restore.clj`, `edn_io.clj` (atomic write
helpers).

### The three "session" concepts

The codebase carries three different "session" ids; persistence keys
on the **agent session id** (the only one stable across `by`
restarts):

| Name | Type | Generator | Lives on | Lifetime |
|---|---|---|---|---|
| **App session id** (TUI tab) — `:id` | integer | `bases/agent-tui/.../sessions.clj` (`:next-id`, auto-increment from 1) | TUI's `!sessions` atom; surfaced as the `[1:label …]` indicator and target of `Ctrl-N` / `Ctrl-P` and `/session N`. | Process-local. Resets every `by` start. Not persisted. |
| **Agent session id** — `:session-id` | string | `agent.core.session/generate-session-id`, e.g. `"agt-1700000000-1234"` | The agent's shared `!session` atom (`:session-id`). Shared across a root agent and its sub-agent family. | One per agent-session. Created on `agent/get-or-create-session`; survives across iterations; ends on `delete-session`. |
| **Agent instance id** — `:agent-id` | keyword | `defagent` registration / per-instance creation | The Agent record's `:agent-id` field; surfaced via `proto/agent-id`. | Per-instance. A root + sub-agent family can have many distinct `:agent-id`s but one `:session-id`. |

Each TUI session map (`make-session` in `sessions.clj`) carries
`:agent-session-id` copied from the agent record at attach time;
persistence reads that string and uses it as the directory name. The
TUI `:id` stays UI-only.

Fork (`/fork`) creates a *new* agent-session-id with a `:parent-id`
link — a sibling directory, not a sub-file. Sub-agents in the
research / workflow trees share the root's `:session-id`, so their
events land in the same `messages.log`.

---

## 6. Lifecycle walkthrough (Mode B)

1. User types `by` in their tmux pane.
2. `mode.clj` probes: `$TMUX` set ✅, `tmux` on `$PATH` ✅, server
   reachable ✅. No `--with-tmux` needed — default lands on Mode B.
3. Renderer starts: enters alt-screen on the *current* pane, sets up
   scroll region + input + status, registers `:source :tui` hooks
   against the agent runtime ([renderer.md](renderer.md)).
4. `Tmux` protocol bound to `RealTmux` (plain shellouts).
5. User asks a question. Hooks fire; iteration live blocks render
   in-stream.
6. A tool fires `:agent.tool-use/pre`; the loop-guard hook checks for
   pathological loops, the permissions handler may pop a
   questionnaire (Mode B uses `Tmux.display-popup!`; Mode A would
   render in-stream). User picks Allow; the popup closes; the BT
   resumes.
7. User types `/activity show`. The renderer calls
   `Tmux.split-pane!` (right, 30%), `ensure-fifo!` creates the FIFO,
   `cat <fifo>` is spawned in the new pane, and from this point
   activity content routes to the FIFO instead of the in-stream
   widget. The in-stream slot becomes a marker (`see /activity`).
8. User detaches (`prefix d`). Tmux backgrounds the whole window;
   `by` keeps running; writes to the FIFO are buffered (or dropped
   if the pipe fills — bounded buffer).
9. User re-attaches an hour later. Tmux restores the pane buffer;
   `by` is still painting where it left off. Renderer notices any
   `SIGWINCH`, re-flushes the live blocks, and re-reads the latest
   `snapshot.edn` to repaint anything that scrolled out.
10. User exits with `Ctrl-D` on empty buffer. Renderer freezes any
    open live blocks, closes side panes (`/activity hide` implicit),
    persists `meta.edn` + final `snapshot.edn`, releases the lock,
    exits alt-screen, returns 0.

Mode A is identical from step 5 onward, minus steps 4, 6 (popup vs
in-stream prompt), and 7 (the `/activity show` is a friendly no-op).

---

## 7. Failure modes & edge cases

- **`$TMUX` set but tmux server died.** `mode.clj` probes the server;
  falls back to Mode A with a note. `--with-tmux` exits with the
  Mode-C guidance instead.
- **User kills a side pane manually** (`prefix x`). The FIFO writer
  notices `EPIPE`; renderer flips the activity stream back to
  in-stream mode and clears the persisted layout. No crash.
- **Side pane's `cat` falls behind.** Writes are non-blocking through
  the multi-sink. A bounded mailbox drops oldest bytes when full,
  prepending a `[truncated]` marker on next write.
- **Two `by` invocations on the same agent-session-id.** Prevented
  by `.lock`. The second process sees the lock held, prompts for
  force-take or read-only, and never silently double-writes.
- **Resize while a side pane is open.** Tmux handles the layout;
  the renderer recomputes its scroll region on `SIGWINCH`. The
  `cat`-side rendering is `cat`'s problem.
- **`--with-tmux` *and* `--inline` together.** Supported but unusual.
  Mode B with the alt-screen suppressed. Output appends to the
  user's scrollback while side panes and popups remain available
  via the `Tmux` protocol. Developer / test combination, not a
  user-facing path.

---

## 8. Boot integration with `agent-tui-app`

`projects/agent-tui-app/src/ai/brainyard/agent_tui_app/main.clj`
exposes six subcommands (`known-subcommands`,
[build-and-deploy.md](../build-and-deploy.md)):

| Subcommand | Purpose |
|---|---|
| `run` *(default)* | Start the interactive TUI session. Calls `mode/probe` + `agent-tui.core/run!`. |
| `ask` | One-shot, non-interactive question. |
| `agents` | List registered agents. |
| `models` | List available providers / models. |
| `config` | Interactive environment bootstrap wizard. |
| `sessions list` / `prune` | Inspect / clean up persisted agent sessions under `~/.brainyard/sessions/`. |

`run` adds session-management flags:

- `-i` / `--inline` — disable alt-screen.
- `--with-tmux` — require Mode B; fail (Mode C) if conditions not met.
- `-r` / `--resume` — bare: pick a persisted session to resume from an interactive menu (fresh if none).
- `-r <id>` / `--resume <id>` — resume that specific session (error + exit 1 if absent).
- No resume flag → fresh session. (`--new` is a deprecated no-op, still accepted.)

On resume the trailing bytes of `scrollback.stream.txt` are replayed into the
pane. The amount is the `:resume-scrollback-bytes` config key (default 10 MiB —
raw ANSI bytes, not characters; bounded by the on-disk stream cap), overridable
in `.brainyard/config.edn` or via `agent-runtime$config`.

`bb tui:acp` runs the same binary with `-a acp-agent` and an in-tree
ACP stub backend for protocol-level testing.

---

## 9. What was retired

These artifacts of the two-process design are gone from trunk:

- ❌ `bases/agent-tui-ui` (the `by-ui` binary). Removed from the tree.
  No replacement.
- ❌ `bb tui daemon`, `bb tui ui [--auto]`. The corresponding
  subcommands and their `main.clj` dispatchers no longer exist.
- ❌ Control socket (`~/.brainyard/sessions/<id>/control.sock`).
  Renderer ↔ runtime is in-process function calls.
- ❌ Per-pane named pipe protocol for the *main* stream. The main
  stream paints the current terminal directly.
- ❌ `add-watch` rendering pipeline (`make-streaming-watch`,
  `make-st-memory-watch`, `handle-st-memory-change`, the
  `iter-blocks-enabled?` predicate, `BRAINYARD_ITER_LIVE_BLOCKS`
  env flag). Replaced wholesale by the typed hooks pipeline (see
  [renderer.md](renderer.md)). Migration completed across five
  phases; the watch path is no longer reachable.
- ❌ One-window-per-agent + native `prefix n/p` for switching
  agents. Multi-agent UX returns to the in-process session manager
  (`/session list`, `Ctrl-N` / `Ctrl-P`). Users who want side-by-
  side comparison run *two `by` instances* in two tmux panes of
  their own choosing.
- ❌ Brainyard-named tmux session created on launch. `by` never
  creates a tmux session; it either runs in the user's, runs
  without one (Mode A), or guides the user (Mode C).
- ❌ Detach/re-attach as a first-class capability. The user's tmux
  client owns detach (`prefix d`) and re-attach. While detached,
  `by` keeps running and tmux keeps the pane buffer; on re-attach
  the buffer is restored. *We do not try to survive `by` itself
  crashing or being SIGHUP'd while detached.* Long-running agents
  that need crash survival should run via `agent-web-app`, not the
  TUI.

What stays:

- ✅ Questionnaire popup primitive — implemented via `display-popup`
  in Mode B; rendered as an in-stream confirm dialog in Mode A.
- ✅ `agent-tui-persist` per-session storage layout — keyed on
  `agent-session-id`.
- ✅ `Tmux` protocol with `RealTmux` / `StubTmux` backends — used
  internally by the renderer in Mode B; `StubTmux` is what tests
  run against.
- ✅ Live-block primitives in `layout.clj` (`update-live-block!`,
  `freeze-live-block!`, `dispose-live-block!`) and the
  `IterationSink` indirection (`iteration_sink.clj`).
- ✅ Hooks-driven rendering surface, permission/feedback callbacks,
  the unified slash-command dispatcher.

---

## See also

- [renderer.md](renderer.md) — what lives inside `bases/agent-tui`:
  file map, hooks pipeline, layout, slash commands, multi-session,
  CliClient.
- [testing.md](testing.md) — driving the live binary from a tmux
  harness; debugging file-descriptor / FIFO interactions.
- [architecture.md](../architecture.md) — where `agent-tui-app` sits in the
  Polylith graph.
- [build-and-deploy.md](../build-and-deploy.md) — `bb` tasks, GraalVM
  native build, configuration.
- [core/agent.md §Hooks](../core/agent.md) — the event catalog the
  renderer subscribes to.
