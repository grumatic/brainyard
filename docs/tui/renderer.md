# TUI Renderer — `bases/agent-tui`

The renderer is the face of the `by` binary: alt-screen rendering,
raw-mode input, inline permissions, slash commands, multi-session
support, an input queue, and an optional tmux side-channel. It also
doubles as the integration-testing surface via `CliClient`.

Primary files: `bases/agent-tui/src/ai/brainyard/agent_tui/*.clj`.

See [architecture.md](architecture.md) for the surrounding process
topology and Mode A / B / C decision; this page is the renderer's
internals.

---

## File map

| File | Purpose |
|---|---|
| `core.clj` | Public API: `start!`, `ask`, `run!`, `stop!`. Orchestrates session, layout, input queue lifecycle. Registers all `:source :tui` hooks against the agent runtime. |
| `mode.clj` | Mode A / B / C probe. Inspects `$TMUX`, `$PATH`, `--with-tmux`, server reachability. Returns `{:mode :A|:B|:C :guidance "…"}` plus the probe inputs (`:explicit-with-tmux?`, `:tmux-on-path?`, `:inside-tmux?`, `:tmux-server-alive?`). |
| `session.clj` | The rendering heart: per-session state plus all hook handlers and tickers that maintain the think / iteration / compaction / subagent / task live blocks and emit lines to `output_sink`. |
| `sessions.clj` | Multi-session manager: switching, per-session scrollback, persistent `:next-id`. |
| `layout.clj` | Split-screen manager: alt-screen vs inline. Scroll-region, status-bar, live-block primitives (`update-live-block!`, `freeze-live-block!`, `dispose-live-block!`). |
| `terminal.clj` | Raw vs cooked mode toggling. Keystroke reading. ANSI escape handling. |
| `input.clj` | Byte-level input, UTF-8 decoding, Ctrl-C handling, permission/feedback interception. |
| `autocomplete.clj` | Autocomplete menu over commands, tools, agents, slash commands. |
| `commands.clj` | Slash-command dispatch. Top-level operations (model, config, tasks, sandbox, MCP, queue, session). |
| `side_pane_commands.clj` | Mode-B slash commands: `/activity`, `/log`, `/scrollback dump`, `/popup test`. No-ops with a helpful note in Mode A. |
| `permissions.clj` | Renders permission prompts (Mode A in-stream; Mode B popup via `tmux_side`), delivers action-promise responses. |
| `popup.clj` | Questionnaire popup driver — calls the `agent-tui-tmux` questionnaire primitive in Mode B; falls back to in-stream in Mode A. |
| `tmux_side.clj` | Mode-B side-channel: split tmux panes for activity / log streams, route bytes through per-pane FIFOs. Inactive in Mode A. |
| `iteration_sink.clj` | `IterationSink` protocol: bridges iteration live-block updates to a rendering surface (in-process layout or future tmux pane sink). |
| `output_sink.clj` | Indirection between `emit!` and the terminal writer; lets a different writer (e.g. a FIFO) override the default in tests. |
| `render.clj` | Pure render fns for live-block lines, headers, tool entries, code blocks, etc. |
| `display_block_ui.clj` | Render helpers for `display-block` UI primitives. |
| `persist_bridge.clj` | Wires `:source :persist` hooks into `agent-tui-persist` so the on-disk store stays in sync with the agent lifecycle. |
| `log.clj` | `start-file-publisher!` (global `~/.brainyard/logs/agent-tui-app.log`, `/tmp/` fallback) and `start-session-publisher!` (per-session, filtered on `:session-id`). |
| `config_wizard.clj` | Interactive environment bootstrap — `bb tui config`. |
| `bootstrap.clj` / `bootstrap_driver.clj` | Environment detection + the install/start/pull/smoke "rung" ladder (e.g. Ollama) that feeds `config_wizard.clj`. |
| `smoke_test.clj` | Post-bootstrap smoke check that a chosen provider/model actually answers. |
| `helpers.clj` | LM setup, usage tracking, JUL cookie-warning suppression. |
| `dirs.clj` | Thin delegation to `agent/dirs`. |

---

## Layout model

Two layout modes selected at startup:

- **Fullscreen alt-screen** (default) — `layout.clj` enters alt-screen,
  carves out a scroll region, and reserves the bottom rows for the
  status bar and input. Live blocks (sticky widgets) anchor to the
  tail of the scroll region above the input prompt. Status-bar
  fields (`layout/format-status`): model, provider, iteration count,
  queued items, task count, verbosity, busy/idle indicator, and a
  **drift chip** — shown when `clj-nrepl/drifted?` is true, carrying
  `clj-nrepl/drift-count` so a live nREPL hot-patch is visible at a
  glance (see [core/reasoning.md](../core/reasoning.md) on the `:nrepl`
  backend).
- **Inline** (`-i` / `--inline`) — no alt-screen. Output appends to
  the user's terminal scrollback. This is the codepath `CliClient`
  and snapshot tests exercise. Combining `--inline` with
  `--with-tmux` is supported but unusual (Mode B with alt-screen
  suppressed) — developer / test combination, not a user path.

ANSI codes are centralised in `agent.interface.tui.ansi` (shared
between the renderer and the agent component's formatter) — no bare
escape sequences are scattered through the code.

The iteration live blocks (one per BT iteration) are managed through
the `IterationSink` protocol. The in-process renderer's sink wraps
`layout.clj`'s primitives; future tmux-pane sinks can target a side
pane without touching session state.

---

## Hook-driven rendering

The renderer does not poll, does not watch atoms. It **subscribes to
typed hook events** from `agent.core.hooks` (catalog in
[core/agent.md §Hooks](../core/agent.md)).

`core.clj` registers handlers under `:source :tui` at startup; they
fire pure render fns from `render.clj` / `session.clj` that write
ANSI lines into the live blocks or the scrollback.

Hooks observed by the renderer (selected):

| Event | Handler responsibility |
|---|---|
| `:agent.instance/created` / `/closed` | Create / dispose the TUI session for this agent. |
| `:agent.ask/pre` / `/post` | Echo the user prompt; render the final answer on `:result`. |
| `:agent.iteration/pre` / `/post` / `/exhausted` | Open / freeze / mark-exhausted the iteration live block; surface `:observation`, `:goal-achieved`, `:goal-reasoning` from the payload. |
| `:agent.dspy-action/pre` / `/chunk` / `/post` | Begin a `[Think]` section in the live block; stream tokens via `:chunk`; capture `:reasoning` on `/post`. |
| `:agent.tool-calls/pre` / `/post` | Open / close the `[Tools]` section. |
| `:agent.tool-use/pre` / `/post` | Per-tool entry: name, args, result, elapsed. `/pre` is gated (loop-guard hook). |
| `:agent.code-eval/pre` / `/post` | Render the `[Code]` block, then the `[Result/Output/Error]` block. Parallel / multi-block evals accumulate into a single live render (see below). |
| `:agent.compaction/pre` / `/phase` / `/post` | Open / advance / freeze the **live compaction block** (`render-compaction-lines`); `/post` also updates the status-bar context size. |
| `:agent.evaluation/started` / `/llm-calling` / `/done` / `/verdict` | Surface the CoAct refinement loop's progress. |
| `:agent.analytics/post` | Post-session analytics into the status indicator. |
| `:task/created` / `:task/completed` | Inline "Task X completed in Ys" notifications. |
| `:todo/updated` | Refresh the `:todo` slash-command panel. |

The `persist_bridge.clj` namespace registers a parallel set of
handlers under `:source :persist` so `agent-tui-persist` records the
same lifecycle events to disk without touching the renderer.

### Earlier `add-watch` pipeline (retired)

Before May 2026 the renderer also ran an `add-watch`-on-st-memory
pipeline: `make-st-memory-watch`, `make-streaming-watch`,
`handle-st-memory-change`, and an `iter-blocks-enabled?` predicate
flipped between the two pipelines via `BRAINYARD_ITER_LIVE_BLOCKS`.
That migration completed (Phase 4 removed the watch path, Phase 5
cleaned up the docs); hooks are the only rendering pipeline now.

---

## Live blocks — think, iteration, compaction

The live-block engine lives in `session.clj` (the rendering heart of the
base); `layout.clj` provides the primitives it builds on
(`update-live-block!` / `freeze-live-block!` / `dispose-live-block!`,
`!scrollback`, `!live-blocks`). Several distinct block families render
concurrently above the input prompt:

- **Think block** (`!think-block`). While the LLM call is in flight the
  renderer shows a single sticky line: a braille spinner
  (`think-ticker-frames`) + a rotating activity verb
  (`think-words` / `stamp-think-activity!`) + the elapsed timer. A
  background ticker (`start-think-block-ticker!`, ~150 ms) advances the
  spinner and elapsed counter without new hook events; the block is
  capped at `think-max-lines` (10) and disposed when the answer/iteration
  resolves. `start-thinking-indicator!` / `stop-thinking-indicator!`
  bracket its lifetime.
- **Iteration blocks** (`!iteration-blocks`). One per BT iteration,
  opened by `iteration-pre-handler` and frozen by
  `iteration-post-handler` (`freeze-pending-iterations!`). The header
  marker is `+` (running) / `✓` (ok) / `✗` (fail) / `●`, followed by a
  multi-line Think section, tool lines, and eval sections. A live ticker
  (`start-iteration-block-ticker!`) keeps the `(N.Ns)` elapsed counter
  ticking between hook events.
- **Eval sections** (parallel / multi-block). `accumulate-eval-entry`
  slots each block's result into `:eval-display` — parallel blocks key on
  `:parallel-batch-index`, sequential blocks append/update-last — and
  `render-eval-display!` renders them through `format-eval-sections` with
  `Code[1]` / `Code[2]` labels, so a fan-out of code blocks collapses into
  one coherent render instead of interleaved fragments.
- **Compaction block** (`!compaction-blocks`). Driven by the
  `:agent.compaction/{pre,phase,post}` hooks; `render-compaction-lines`
  shows the phases in `compaction-phase-order` and
  `format-compaction-summary` reports the before/after size.
- **Subagent / task blocks** (`!subagents-blocks`, `!task-blocks`) — live
  tickers for in-flight sub-agents and foreground tasks.

Long eval content collapses behind an expandable marker via the
`display-block` component (`components/display-block`); `display_block_ui.clj`
scans markers in scrollback and can splice the expanded body or hand it to
`$EDITOR`.

### Wide-character width

Column math (wrapping, status-bar fit, marker alignment) goes through the
canonical width helpers in `components/agent/src/ai/brainyard/agent/tui/format.clj`
(re-exported as `agent.interface.tui.format`): `display-width`,
`wide-codepoint?` (CJK + emoji ranges count as 2 columns),
`zero-width-codepoint?` (ZWJ / variation selectors count as 0), and
`char-index-at-width`. Recent fixes stopped over-counting EAW-narrow
glyphs (`✓ ★ ⤴ ⬅ ⤵`) as wide. (`render.clj` carries a simpler ASCII-only
`display-width` for hot-path lines.)

---

## Input queue

Typing while the agent is processing does not block. Input is
captured, appended to a queue, and a status-bar badge shows
`Queued (#N)`.

- `Ctrl-C` cancels the *current* run; queued items remain.
- `/queue` — list queued items.
- `/queue cancel all` — clear everything.
- `/queue cancel <uuid>` — drop a specific item.

The queue lives in `core/queue.clj` of the agent component; it is
also exposed to the web bridge over WebSocket messages
(`:agent/queue-update`, `:agent/queue-cancel`,
`:agent/queue-cancel-all`).

---

## Slash commands

Two prefix conventions:

- `/<name>` — subsystem commands. Dispatched in `commands.clj` via
  a single `case` over the literal string.
- (Unrecognised `/<name>`) — falls through to a registry lookup
  against `!tool-defs`, so any registered command surfaces as a
  slash command automatically.

The built-in roster (non-exhaustive):

| Command | Purpose |
|---|---|
| `/help`, `/status`, `/history`, `/clear` | General info; runtime-config snapshot; conversation tools. |
| `/model <provider[:model]>` | Switch provider/model mid-session. |
| `/verbose <level>` | `:quiet` / `:normal` / `:verbose` / `:debug`. |
| `/effort <level>` | LM thinking-effort knob (where supported). |
| `/config` | Edit runtime-config (mutates `:runtime-config` live). |
| `/compact [args]` | Force a context compaction. |
| `/todo` | Show the current TODO list. |
| `/usage`, `/perf` | Token cost + latency breakdown. |
| `/continue [args]` | Continue from the last iteration. |
| `/task` | Task-manager front-end (delegates to `task$list` / `task$detail` / `task$cancel` / `task$run`). |
| `/allow-path <path>` | Add a path to the action-permission allowlist. |
| `/capture` | Save current scrollback to a file. |
| `/sandbox` | Inspect / manipulate the SCI sandbox. |
| `/mcp list|add|remove|tools` | MCP server management. |
| `/agent new|close|rename` | Sub-agent control. |
| `/session [N\|subcmd]` | Multi-session switching, plus subcommands: `list`, `new`, `close`, `rename`, `tree`, `fork`, `resume`. |
| `/pause`, `/resume` | Pause / resume the active BT. |
| `/queue [cancel …]` | Input queue management. |
| `/activity show|hide|toggle` *(Mode B)* | Split / kill a tmux side pane for the activity stream. |
| `/log show|hide` *(Mode B)* | Split / kill a tmux side pane tailing the session's app log. |
| `/scrollback dump` *(Mode B)* | Capture the main pane's scrollback to `~/.brainyard/sessions/<id>/scrollback-<ts>.ans`. |
| `/popup test` *(Mode B)* | Open a no-op questionnaire popup (smoke test). |
| `/quit` | Stop the TUI. |

Mode-B-only commands print a friendly note in Mode A: *"tmux not
available in this mode — activity is rendered inline. Run `by` from
inside a tmux session, or pass `--with-tmux`, to use side panes."*

---

## Permissions UI

When a tool or BT leaf creates an action-promise, the renderer
intercepts the input stream and renders the prompt:

- **Yes / No** — one keystroke (or Tab-navigate + Enter, see below).
- **Always-Yes / Always-No** — cached in `:action-permissions` under
  the session; subsequent identical prompts skip the UI.
- **Edit / Skip / Abort** — richer responses for free-form
  `request-user-action` prompts.

### Mode A — in-stream sticky-bottom block

The prompt renders as a sticky-bottom live block in `layout.clj`
(`update-live-block!` with `{:sticky-bottom? true}`). The block is
anchored below every other live block — when an iteration / think /
todo block is created or updated while the prompt is up, it is
spliced in **before** the prompt and the prompt's `:start-idx` is
shifted forward so the prompt stays at the bottom of the scroll
region, just above the input line. On any response (or timeout) the
block is disposed and its lines vanish from scrollback.

The keystroke handling lives in `input.clj` `try-intercept-byte` —
single bytes (`y` / `n` / `a`) deliver to the pending-permission
promise. For free-input feedback options, `input.clj` disposes the
sticky live block before emitting `"Type your response: "` so the
character echo from `write-raw-chars!` lands at the cursor in the
scroll region.

### Mode B — tmux popup with Tab navigation

`popup.clj` builds a tmux `display-popup` overlay whose body is an
embedded bash 3.2+ interactive selector script (extracted at
runtime to a temp file). The popup contract:

| Key                                | Action                                |
|------------------------------------|---------------------------------------|
| **Tab** / **↓**                    | Move focus to next option             |
| **Shift-Tab** / **↑**              | Move focus to previous option         |
| **Enter**                          | Submit the focused option             |
| **Esc** / **Ctrl-C**               | Cancel                                |
| `y` / `n` / `a` (letter shortcuts) | Instant-submit matching option        |
| `1`–`9` (digit shortcuts)          | Instant-submit option by 1-based idx  |

The focused option is rendered with reverse video and a `▶` marker;
the cursor is hidden. The questionnaire's title flows to tmux via
`display-popup --title` (painted in the popup border) — it is **not**
duplicated inside the popup body. The script writes the focused
option's shortcut letter (or a 1-based digit when the option has no
shortcut) to a temp result file on submit, or empty on cancel; the
Clojure side maps that back to the option's `:value` via
`match-key`.

Either way, the collected answer is delivered to the action promise
via `deliver-action`; the blocked BT leaf resumes on the next tick.

The loop-guard hook (in `components/agent` →
`common/loop_guard_hook.clj`) is registered on `:agent.tool-use/pre`
as a gated handler; it can short-circuit a pathological repeat-call
pattern by returning `{:result :block :answer …}`.

---

## Multi-session

`sessions.clj` lets a single TUI host hold multiple TUI sessions
(each potentially bound to its own agent):

- Per-session scrollback + collapsed iteration content.
- Session switcher: `Ctrl-N` / `Ctrl-P`, or `/session N`.
- Each session has its own agent (or sub-agents), memory, and queue.

The TUI session's `:id` is process-local (auto-increment from 1).
The persisted directory is keyed on the **agent session id** — see
[architecture.md §5](architecture.md). `/fork` creates a new
agent-session-id with a `:parent-id` link; `/tree` navigates the
fork tree; `/name <label>` labels a session.

Side-by-side comparisons across reasoning styles (e.g. ReAct vs
CoAct) are achieved by running *two `by` instances* in two tmux
panes of the user's choice — one process drives one renderer.

---

## CliClient — programmatic TUI testing

`CliClient` (`agent/stdio/client.clj`) spawns `by` in inline mode,
writes questions into stdin, and pattern-matches output lines. Its
public surface is `start!`, `send-line!`, `wait-for`, `wait-for-idle`,
`get-output` / `get-all-output`, `clear-output!`, `shutdown!`, `alive?`:

```clojure
(let [c (cli/start! :command ["target/by" "run" "-i" "-a" "coact-agent"])]
  (try
    (cli/send-line! c "What is 2+2?")
    (cli/wait-for-idle c)               ;; settle until no new output
    (cli/wait-for c #"Answer:.*4")      ;; assert the answer line appeared
    (finally (cli/shutdown! c))))
```

`send-line!` + `wait-for`/`wait-for-idle` is a two-step check: settle
on idle, then match the expected line. The two steps matter — waiting
only for idle can produce false positives when a stale status-bar
update looks like completion. (`start!` takes a `:command` vector, not
`:binary`/`:args`.)

Uses:

- Integration tests across real LLM round-trips.
- The agent's `CliClientJobExecutor` ([core/task.md](../core/task.md))
  delegates to the same harness to run sub-agents in subprocesses.
- Multi-turn regression suites where later questions depend on
  prior context.

---

## Logging

Mulog publishers wired in `log.clj`:

- `start-file-publisher!` — global, all events to
  `~/.brainyard/logs/agent-tui-app.log` (pretty-printed EDN; resolved
  via `tui-log/default-log-path`, falls back to `/tmp/agent-tui-app.log`
  if the user-scope dir can't be created). Wraps
  `mulog/make-pretty-file-publisher`.
- `start-session-publisher!` — per-session, filters on
  `:session-id` and appends to `<session-dir>/app.log`. The
  tmux-based `/log show` pane tails this file so users see only
  their own session's events.

The TUI's `/usage`, `/perf`, and history-style commands query the
same mulog stream via `agent.common.turn-log` — see
[design/observability.md](../design/observability.md).

---

## Verbosity levels

- `:quiet` — only final answers. No traces, no status-bar noise.
- `:normal` — iteration headers, tool calls, compact observations,
  final answer.
- `:verbose` — full BT traces, LLM tokens, timing, cost per call,
  recalled memory context.
- `:debug` — extra detail (e.g. per-tool delta lines that are
  hidden at `:verbose`).

Verbosity is live-mutable via `/verbose`. The render fns read it
per-render, so there is no cache to invalidate. The iteration
widget shares the same level — its `[Think]` section is gated on a
verbosity check.

---

## See also

- [architecture.md](architecture.md) — process topology, Mode
  A / B / C, persistence layout.
- [testing.md](testing.md) — debugging live runs with a tmux
  harness.
- [core/agent.md](../core/agent.md) — hooks catalog the renderer
  subscribes to.
- [core/tool.md](../core/tool.md) — autocomplete sources, permission
  plumbing.
- [design/observability.md](../design/observability.md) — mulog,
  trajectory export, the backing for usage / history commands.
