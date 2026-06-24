# Pausing & resuming a running turn (`ESC`)

*How the TUI pauses an in-flight agent turn at a safe checkpoint, lets you
steer or cancel it, then resumes — without ever interrupting a half-finished
LLM call.*

Status: **as-built** (June 2026)
Audience: TUI base maintainers, agent runtime maintainers.

While the agent is working, press **`ESC`** to pause it. The turn stops at the
next safe checkpoint and a tips block tells you what to do next: continue,
continue *with a steering note*, or cancel. `Ctrl-\` does the same thing as
`ESC` and is the zero-ambiguity fallback (see [ESC disambiguation](#esc-disambiguation)).

> ℹ **Pause is cooperative, not preemptive.** Requesting a pause never kills an
> in-flight LLM call or tool execution mid-flight — it lands at the next
> behaviour-tree checkpoint (between iterations, before a condition/action
> ticks). So after you press `ESC` you may briefly see the current step finish
> before the paused state appears.

## Quick reference

| State | Key | Effect |
|---|---|---|
| running | `ESC` *(or `Ctrl-\`)* | Request a cooperative pause; tips block appears |
| running | `Ctrl-C` | Cancel the active turn (double-press within 1s exits `by`) |
| **paused** | `ESC` *(or `Ctrl-\`, or empty `Enter`)* | Resume — continue as-is |
| **paused** | type a message + `Enter` | Resume, folding the message into the loop as a steering note |
| **paused** | `Ctrl-C` | Cancel the turn |
| idle | `ESC` | Normal editor behaviour (close menu / clear line) — **not** a pause |

The paused tips block:

```
⏸ Paused — agent stops at the next safe checkpoint
  ESC                      continue
  type a message + Enter   continue, steering the agent
  Ctrl-C                   cancel this turn
```

## How it works

Three layers cooperate: the **input reader** turns a keystroke into a
pause/resume/cancel request, the **runtime state machine** parks and wakes the
agent thread, and the **behaviour-tree checkpoint** is where a parked turn
actually stops.

### 1. Input layer — `agent-tui.input`

A daemon thread reads raw bytes from `/dev/tty` and dispatches them. `ESC`
(byte 27) and `Ctrl-\` (byte 28) both call `toggle-pause!`; `Ctrl-C` (byte 3)
calls `handle-ctrl-c!`. Everything else flows to the readline editor.

`toggle-pause!` reads the active agent's state: if it's paused it calls
`resume-run` and hides the tips block; otherwise it calls `pause-run` and shows
the sticky tips block. The tips block is a sticky-bottom live-block
(`:pause-tips`) anchored below the think/iteration/task blocks; outside
fullscreen mode it falls back to a plain emit.

#### ESC disambiguation

`ESC` is also the lead byte of escape sequences — arrow keys, bracketed paste
(`ESC[200~`). The reader can't treat every `ESC` as a pause. `handle-esc!`
resolves this the same way `terminal/read-key!` does: sleep ~2ms, then peek the
tty.

- **More bytes waiting** → it's a sequence. Re-enqueue the `ESC` and let the
  following bytes flow to the editor unchanged. Sequences behave exactly as before.
- **`ESC` stands alone** → if a turn is in flight on the active tab, toggle
  pause/resume. Otherwise (idle, editing the input line) re-enqueue the `ESC` so
  the editor keeps its normal `:escape` behaviour.

"A turn is in flight" is decided by `turn-in-flight?`, which checks whether an
ask thread is registered for the active tab's session (`!ask-threads`) — the
same signal the rest of the TUI uses for *running?*. This is why `Ctrl-\`
exists as an alias: `ESC` carries an inherent ~2ms (more over SSH/tmux) peek
latency, and tmux makes heavy use of `ESC`, so `Ctrl-\` is the latency-proof
way to pause.

The `ESC` arm sits *after* the feedback-prompt interceptor, so typing into a
free-input feedback prompt is unaffected.

### 2. Runtime state machine — `agent.core.runtime`

| Fn | What it does |
|---|---|
| `pause-run` | Sets `[:runtime :paused?] true`, saves the prior `:status` under `:pre-pause-status`, flips `:status` to `:paused`. The pause lands at the next checkpoint — it does **not** stop an in-flight call. |
| `resume-run` *(opt note)* | Clears `:paused?`, restores `:status`. With a non-blank note, stashes it under `[:runtime :resume-note]`. |
| `cancel-run` | Sets `:cancelled?`, **clears `:paused?` / `:pre-pause-status` / `:resume-note`**, wakes any parked thread, aborts the in-flight HTTP/LLM stream, and interrupts the executing thread (cascades to sub-agents). |
| `wait-if-paused` | `:cancelled` if cancelled (checked first), `:running` if not paused, else parks the thread on a condition until resumed/cancelled (`:resumed` / `:cancelled`). |
| `paused?` / `cancelled?` | Read the flags; both walk parent agents so a sub-agent inherits its root's pause/cancel. |

### 3. The checkpoint — `agent.core.bt/check-interrupt-cancel-pause!`

Every behaviour-tree iteration boundary (and before a `:condition` / `:action`
ticks) runs this checkpoint, in order:

1. honour a Java thread interrupt (preemptive cancel) → abort
2. honour an explicit cancel → abort
3. if paused, park the thread (`wait-if-paused`); on wake, re-check cancel
4. fold any pending **resume note** into the active task (`apply-resume-note!`)

Step 4 is what makes *type-while-paused* work: a resume note is merged into the
running loop's active task, and the LLM is told it was resumed carrying that
request — so the same turn continues, now steered, rather than a new turn
starting.

## The three response paths

When paused, the TUI submit handler (`agent-tui.core`) checks `paused?` before
treating your line as a new prompt:

- **`ESC` / empty `Enter`** → `resume-run` with no note. The loop continues
  exactly where it parked.
- **text + `Enter`** → `resume-run` *with the text as a note*. Surfaced as
  `[resumed — steering the loop with your note]`. The note reaches the LLM at
  the next checkpoint.
- **`Ctrl-C`** → `cancel-run`. The turn aborts; the tips block is cleared. Because
  `cancel-run` clears the pause flags, the **next** line you type starts a fresh
  turn (not a resume of the dead one).

All three resume/cancel paths — the `ESC`/`Ctrl-\` toggle, the typed-line path,
the `/resume` command, and `Ctrl-C` — dispose the tips block and refresh the
status bar.

## What gets interrupted

| Action | In-flight LLM call | In-flight tool/code | Where it stops |
|---|---|---|---|
| **Pause** (`ESC`) | runs to completion | runs to completion | next BT checkpoint |
| **Cancel** (`Ctrl-C`) | aborted (HTTP stream closed) | thread interrupted | promptly, then aborts |

Pause is gentle (waits for a safe boundary); cancel is forceful (closes the
active HTTP request so a streaming LLM call unblocks immediately, then
interrupts the thread).

## Scope: tabs and sub-agents

Pause and cancel target **only the active tab's turn** — each chat tab runs its
own ask thread (`!ask-threads`, keyed by session index), so background tabs keep
running. Because `paused?`/`cancelled?` walk parent agents, pausing a root agent
also pauses any sub-agent it spawned.

## The paused think-block

The "thinking" spinner (the sticky `[⠹] Reflecting… (12.3s)` line) is driven by a
shared 150ms ticker in `agent-tui.session`. While paused, that ticker would keep
animating and the timer would keep climbing even though nothing is happening, so
it is made pause-aware: when the root agent is paused it renders a static
`[⏸] … (12.3s · paused)` line **once**, with elapsed pinned, and stops advancing.
On resume the paused duration is rolled back into the block's start time, so the
elapsed clock excludes the pause instead of jumping forward.

## Implementation pointers

- `bases/agent-tui/src/ai/brainyard/agent_tui/input.clj` — key dispatch,
  `toggle-pause!`, `handle-esc!`, `turn-in-flight?`, the `:pause-tips` block.
- `bases/agent-tui/src/ai/brainyard/agent_tui/core.clj` — paused-line submit
  routing (resume-with-note).
- `bases/agent-tui/src/ai/brainyard/agent_tui/commands.clj` — the `/resume` command.
- `bases/agent-tui/src/ai/brainyard/agent_tui/session.clj` — the pause-aware
  think-block ticker.
- `components/agent/src/ai/brainyard/agent/core/runtime.clj` — `pause-run` /
  `resume-run` / `cancel-run` / `wait-if-paused` / `paused?` / `cancelled?`.
- `components/agent/src/ai/brainyard/agent/core/bt.clj` —
  `check-interrupt-cancel-pause!` (the checkpoint) and `apply-resume-note!`.
