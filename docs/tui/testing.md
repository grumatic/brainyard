# TUI Testing

Most of the TUI is tested **without a terminal at all** — the render
functions are pure (string in, ANSI string out), so the bulk of the
suite asserts on ANSI-stripped substrings. tmux and the live binary are
exercised by progressively heavier harnesses only where pure functions
can't reach. The back half of this page is the heaviest of those: a
real-terminal harness for Mode-B FIFO bugs.

---

## Test layers

From fastest / most isolated to slowest / most realistic:

1. **Pure-function render tests.** No FIFO or terminal I/O — the render
   and formatter fns are pure. Assertions strip ANSI and match
   substrings (there is **no golden / snapshot framework**). Key
   namespaces under `bases/agent-tui/test/.../agent_tui/`:
   `render_test`, `iteration_block_test` (formatters + the iteration /
   think / eval hook handlers, driven through a recording `IterationSink`
   and a stubbed `agent/get-bt-st-memory`), `status_bar_test`,
   `sticky_live_block_test`, `tab_strip_test`,
   `output_sink_test`, `permissions_test`, `popup_test`, `mode_test`,
   `persist_bridge_test`, `tree_commands_test`, `side_pane_commands_test`,
   `tmux_side_test`.
2. **StubTmux protocol tests.** `components/agent-tui-tmux` ships a
   `stub-tmux` call recorder (`stub-tmux`, `stub-calls`, `stub-calls-of`,
   `stub-last-call`); the `control` / `host` / `questionnaire` / picker
   tests assert the exact tmux call sequence without shelling out. Run
   via `bb test:component agent-tui-tmux`. Persistence round-trips live
   in `agent-tui-persist` (`bb test:component agent-tui-persist`).
3. **CliClient inline-mode integration.** `CliClient`
   (`components/agent/src/ai/brainyard/agent/stdio/client.clj` — note it
   lives in the agent component, not the TUI base) spawns `by` in inline
   mode (`bb tui run -a <agent> -i`, no alt-screen / raw mode), writes
   questions to stdin, and pattern-matches output lines. The same harness
   backs the `CliClientJobExecutor` ([core/task.md](../core/task.md)). See
   [renderer.md §CliClient](renderer.md#cliclient--programmatic-tui-testing).
4. **Real-terminal harness (below).** For Mode-B bugs that only appear
   with a real `tmux split-window` against a real FIFO and a real `cat`
   reader.

---

## Real-terminal harness

When unit tests pass with `StubTmux` backends but the real `by` binary
misbehaves, drive the actual binary from a Bash + tmux harness and
inspect the live state. Stub tests give fast local feedback but miss
**tmux / POSIX / file-descriptor interactions** — the kind of bugs
that only surface when a real `tmux split-window` runs against a real
FIFO with a real `cat` reader.

This section captures the technique used to root-cause the
`/activity show` silent-pane-death bug (May 2026) so the next
debugging round is faster.

---

## When to use this approach

- A unit / integration test passes but the user reports the live
  binary doesn't behave the same way.
- A subprocess (tmux pane, FIFO) appears to succeed per its return
  value, but the user-visible result is wrong.
- You suspect file-descriptor inheritance, FIFO EOF semantics, or
  tmux's pane lifecycle.
- You need to capture the exact bytes a pane received without an
  attached human terminal.

If a `StubTmux`-based test can reproduce the failure, prefer that
path — `bb test:component agent-tui-tmux` is much faster than a
live-binary harness. Reach for this guide when stubs are not enough.

---

## Setup

```bash
# 1. Clean state — remove any persisted session data + stale tmux
#    sessions that would confuse mode probing or session resumption.
tmux kill-session -t by-test 2>/dev/null
rm -rf .brainyard/sessions/agt-*

# 2. Create a tmux session whose window 0 is a bash shell. The
#    renderer launches from this shell; its stderr/stdout go to
#    pane 0.0 so we can capture them.
tmux new-session -d -s by-test -x 220 -y 60 \
  'cd /path/to/projects/agent-tui-app && exec bash'

# 3. Launch `by` from window 0 with --with-tmux so Mode B is
#    required. BY_TMUX_DEBUG=1 enables the per-`split-pane!`
#    diagnostic line in components/agent-tui-tmux/.../core/real.clj.
#    Sessions are auto-named `agt-<ts>-<rand>` — there is no flag to
#    pin the id, so capture it from the banner / `sessions list` after
#    boot (see below).
tmux send-keys -t by-test:0 \
  'BY_TMUX_DEBUG=1 clojure -M -m ai.brainyard.agent-tui-app.main \
   run --with-tmux 2>&1 | tee /tmp/by.log' \
  Enter

# 4. Wait for the JVM cold start. ~22s is reliable on a cold JVM;
#    tune down on faster machines. Skip the wait if you've built
#    the native binary and are running `target/by` instead.
sleep 22

# 5. Capture the auto-generated session-id (used in the paths below).
SID=$(ls -t .brainyard/sessions/ | grep '^agt-' | head -1)
```

The renderer detects `$TMUX` (set by the outer `tmux send-keys`) and
runs Mode B in the *same* tmux session — the user's existing session,
not a Brainyard-named one. The agent renderer occupies pane 0.0; any
`/activity show` / `/log show` will split off it.

For a fast iteration cycle, build the native binary
(`bb build:ata`) and replace `clojure -M -m …` with
`./projects/agent-tui-app/target/by run --with-tmux …` — ~0.5 s
startup instead of ~22 s.

---

## Driving + capturing

```bash
# Send a slash command to the main pane:
tmux send-keys -t by-test:0 '/activity show' Enter
sleep 3   # let the renderer process it

# What panes exist now? Compare against the BEFORE state to see
# whether a split actually persisted.
tmux list-panes -aF '#{session_name}:#{window_index} #{pane_id} #{pane_current_command}' \
  | grep by-test

# What did the main pane render?
tmux capture-pane -t by-test:0 -p

# What did the new side pane receive (drains any banner/queued bytes
# without disturbing the live read)?
tmux capture-pane -t %222 -p

# What did the renderer print to stderr?
tail -20 /tmp/by.log
```

`tmux capture-pane -p` prints the pane's visible contents to stdout.
Useful flags:

- `-J` joins wrapped lines.
- `-S -<N>` captures lines from the scrollback (e.g. `-S -1000`).
- ANSI sequences are preserved; strip with
  `sed $'s/\x1b\\[[0-9;]*[A-Za-z]//g'` for plain text.

---

## Inspecting filesystem + process state

```bash
# Did the renderer create the per-pane FIFOs?
ls -la .brainyard/sessions/$SID/panes/$SID/

# What FIFOs has the renderer's multi-sink actually opened? Sinks
# open the FIFO lazily on first write; the activity FIFO won't
# appear until something calls write-activity!.
PID=$(pgrep -f 'agent-tui-app.main' | head -1)
lsof -p $PID 2>/dev/null | grep FIFO

# Did the renderer's tmux split-window produce a non-empty stdout
# (the new pane id)? A blank stdout means tmux ran but
# `-P -F #{pane_id}` printed nothing — usually a sign the parent
# target was wrong.
grep BY_TMUX_DEBUG /tmp/by.log
```

---

## Reproducing tmux commands manually

When the renderer's `tmux split-window` succeeds but the resulting
pane vanishes, run the **exact same args from your shell** to
compare:

```bash
# Reproduce what BY_TMUX_DEBUG logged:
tmux split-window -d -h -t %213 -P -F '#{pane_id}' -p 30 \
  "cat .brainyard/sessions/$SID/panes/$SID/activity.fifo"
sleep 1
tmux list-panes -aF '#{pane_id} #{pane_current_command}' | tail
```

If the manual reproduction works but the renderer's invocation
doesn't, the difference is in the **caller's environment** — file
descriptors, env vars, working directory, or the timing of subsequent
operations against the same FIFO.

---

## How `/activity show` was diagnosed

A worked example of the technique:

1. `BY_TMUX_DEBUG` showed `tmux split-window` returning exit 0 +
   pane id `%215` for both the status pane (which persisted) AND
   the activity pane (which vanished).
2. `tmux list-panes -a` confirmed `%215` was *not* in the live
   pane table — created and immediately destroyed.
3. Manually running the same
   `tmux split-window … cat <activity-fifo>` from the harness Bash
   created a pane that **stayed alive**.
4. Same args, different caller — pointed at FIFO interaction.
   `lsof` showed the renderer held `RandomAccessFile rw` on
   `stream.fifo` and `status.fifo` but **not on `activity.fifo`**.
5. The renderer's `write-activity-banner!` was opening the FIFO
   with `spit … :append true` (O_WRONLY), writing the banner, then
   **closing**. POSIX: closing the last writer signals EOF to
   readers → the new pane's `cat` exited → tmux destroyed the
   dead pane.
6. Fix: emit the banner via the long-lived multi-sink writer (which
   never closes) on the `:activity-state {:open? true}` frame,
   instead of from a short-lived `spit`.

---

## Cleanup

```bash
# Tear down the test setup so the next run starts clean.
tmux kill-session -t by-test 2>/dev/null
pkill -9 -f 'agent-tui-app.main' 2>/dev/null
rm -rf .brainyard/sessions/agt-* /tmp/by.log
```

---

## Tips

- **Keep the JVM warm-up budget realistic.** `clojure -M -m …` cold
  starts take ~22 s; don't make assertions before the renderer is
  fully booted. Switch to the native binary
  (`./projects/agent-tui-app/target/by`) for ~0.5 s cold start.
- **Clear stale session directories between runs.** Session-ids are
  auto-generated (`agt-<ts>-<rand>`) — there is no flag to pin them — so
  `rm -rf .brainyard/sessions/agt-*` before each run, and re-capture
  `SID` from the freshest directory, to keep leftover directories from a
  prior run from confusing mode probing or session resumption.
- **The renderer's stderr is gold.** Always `tee` it to a file you
  can `tail` — silent catches in the run-loop only ever print there.
- **`BY_TMUX_DEBUG=1` is cheap.** Leave it on for any session where
  you suspect a `split-pane!` is misbehaving. The diagnostic line
  in `components/agent-tui-tmux/src/.../core/real.clj` prints the
  full args + exit + stdout + stderr for every split.
- **Compare renderer and manual.** If the manual reproduction
  works, the difference is environmental — `lsof`, `ps -eo …`, and
  `pwd` of both processes are usually enough to spot it.
- **Verify with the StubTmux first.** If you can write a test
  using `tmux-iface/stub-tmux` and `stub-calls` /
  `stub-calls-of` /  `stub-last-call` that reproduces the failure,
  the live-harness step is only needed to confirm the fix on real
  tmux. The component test is `bb test:component agent-tui-tmux`.

---

## See also

- [architecture.md](architecture.md) — Mode A / B / C, the FIFO data
  plane, persistence layout.
- [renderer.md](renderer.md) — slash commands, the renderer's hook
  subscriptions, layout primitives.
- `components/agent-tui-tmux/test/` — `StubTmux`-based tests for the
  protocol contracts (run via `bb test:component agent-tui-tmux`).
- `components/agent-tui-persist/test/` — persistence-layer
  round-trips (`bb test:component agent-tui-persist`).
