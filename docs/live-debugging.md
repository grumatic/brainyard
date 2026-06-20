# Live Debugging — Driving the Live brainyard JVM from an External Coding Agent

> **Status:** Substrate shipped; external-driver loop in progress.
> The pieces this workflow stands on are all implemented today — the
> opt-in loopback nREPL server (`components/clj-nrepl`), the gated
> `:nrepl` eval path, the `debug-agent` specialist
> (`components/agent/.../common/debug_agent.clj`), and the tmux
> substrate (`components/agent-tui-tmux`). What is *not* yet a turnkey
> feature is an end-to-end harness that lets an external coding agent
> (e.g. Claude Code) run the full *send-input → capture-output →
> diagnose → hot-patch → re-run* loop unattended. This doc describes how
> the loop is meant to work on top of what exists, marks what is real
> versus aspirational, and is the reference for building the missing
> harness.
>
> **Audience:** an external human or coding agent that wants to debug a
> running `by` (Agent TUI) process from outside it.
> **Related reading:** `docs/design/clj-nrepl-eval.md` (the substrate —
> server, grant, classifier, drift), `docs/design/debug-agent-design.md`
> (the internal specialist this driver can delegate to),
> `docs/tui/architecture.md` (the tmux-based TUI),
> `docs/design/update-agent-design.md` (the source-promotion target).

---

## 1. The idea

Brainyard's `by` TUI can host an **in-process nREPL server**. When it is
enabled, the live JVM — the same image that is rendering the TUI,
holding the Integrant system map, the tool registry, every agent
session, and all the loaded namespaces — becomes reachable for
evaluation from outside the process.

That opens a debugging style that is otherwise hard to get with an
agent harness: an external driver can keep the app *running*, poke
real inputs at it, watch what comes back, form a hypothesis about a
bug, **patch the offending function in the live image**, and re-run the
same input to confirm the fix — all without a restart, and without
editing source until the fix is proven. Once proven, the change is
promoted to a committed source edit through a separate, reviewed path.

The driver has two distinct surfaces onto the running app, and the
whole workflow is about combining them:

- **The TUI surface** — send keystrokes / commands into the `by` pane
  and read the rendered output back. This is how the driver *exercises*
  the app the way a user would (slash commands, prompts, agent runs).
- **The nREPL surface** — evaluate Clojure in the live JVM. This is how
  the driver *inspects and fixes* the app from the inside.

The natural host for the TUI surface is **tmux**: run `bb tui` inside a
tmux pane, drive it with `tmux send-keys`, and read it with
`tmux capture-pane`. Both are ordinary shell commands an external agent
can already issue.

```
        external coding agent (Claude Code)
                 │                     │
   tmux send-keys│                     │ nREPL client
   capture-pane  │                     │ (raw, or via debug-agent)
                 ▼                     ▼
        ┌──────────────────────────────────────┐
        │   tmux pane running `bb tui`          │
        │   ┌────────────────────────────────┐ │
        │   │  LIVE brainyard JVM            │ │
        │   │  • TUI renderer + sessions     │ │
        │   │  • Integrant system, atoms     │ │
        │   │  • tool registry, agents       │ │
        │   │  • in-process nREPL server ────┼─┼──▶ ~/.brainyard/nrepl-port
        │   └────────────────────────────────┘ │
        └──────────────────────────────────────┘
```

---

## 2. Two ways to reach the live runtime — and why they differ

This is the single most important thing to understand before wiring up
a driver, because the two paths have **different safety properties** and
**different intended audiences**:

- **Path A (raw nREPL) is for development and test only.** It is the
  fast, unguarded attach a developer or a test harness uses against a
  throwaway/dev image. It is *not* intended to be exposed in production.
- **Path B (the gated `debug-agent` path) is the production target.**
  Once `debug-agent` is functionally ready (see §9), Path B is what
  ships — live debugging in production happens through the gated,
  audited, drift-marked specialist, not a bare REPL socket.

### 2.1 Path A — raw nREPL attach (ungated)

The server started by the TUI is a **plain `nrepl.server`** bound to
`127.0.0.1` (see `components/clj-nrepl/src/ai/brainyard/clj_nrepl/core/server.clj`).
The TUI bootstrap starts it *without a custom handler*
(`start-nrepl-server-if-enabled!` in
`bases/agent-tui/src/ai/brainyard/agent_tui/core.clj`), so it behaves
like any CIDER-attachable nREPL: a client that connects to the port
gets **unrestricted `eval`**.

The grant / deny-list / read-only-classifier / first-mutation-confirm /
drift-marker machinery does **not** live in the server. It lives in the
**client** — `clj-nrepl.core.client/eval-string` runs the five-gate
`gate` function *before* it forwards code to the server. A raw external
nREPL client bypasses all of that.

So Path A is: maximum power, maximum responsibility. The only structural
safety is that the socket is **loopback-only** (non-loopback binds are
rejected at start). Treat a raw attach exactly as you would a CIDER REPL
into production — because that is what it is.

### 2.2 Path B — the gated `code$eval :backend :nrepl` path

When code goes through `clj-nrepl/eval-string` — which is what
`code$eval :backend :nrepl` and therefore the internal **`debug-agent`**
use — it passes through the gate order documented in `client.clj`:

1. **server up** — `start-server!` has run;
2. **grant active** — a non-expired grant exists (`read-only:15m` /
   `mutate:5m`);
3. **deny-list** — `System/exit`, `Runtime/.exec`, credential
   namespaces, etc. are rejected regardless of scope;
4. **mutating scope** — under a `:read-only` grant, top-level mutating
   forms (`def`, `defn`, `alter-var-root`, `require`, …) are rejected;
   under `:mutate` they pass;
5. **first-mutation confirm** — under `:mutate`, the first mutating eval
   per session asks the operator (host-installed confirm fn);

and on a successful mutating eval it records a **drift marker** and
audits via mulog.

So Path B is: the same live image, but every eval is policy-checked and
audited, and mutations leave an auditable trail.

### 2.3 Which to use

| | Path A — raw nREPL | Path B — `debug-agent` / `code$eval` |
|---|---|---|
| Reach | Any nREPL client on the port | TUI command → internal agent |
| Gating | **None** (plain server) | grant + deny-list + scope + confirm |
| Audit / drift | None | mulog + drift markers |
| Driver effort | Driver writes its own diagnosis logic | LLM specialist does the reasoning |
| Intended for | **Dev & test only** | **Production (once `debug-agent` is ready)** |
| Best for | Fast, surgical inspection against a throwaway/dev image | Hands-off, auditable, "ask the app to debug itself" |

**In development and test**, a capable external coding agent can run the
whole loop over **Path A**, using the TUI surface only to *exercise* the
app — fastest, most flexible, and the risk is bounded because the image
is disposable. **In production**, the loop runs over **Path B**: the
driver delegates reasoning to `debug-agent` over the TUI surface (or
`bb tui ask -a debug-agent`) and reads its conclusions back, so every
eval is gated, confirmed, and drift-marked. A reasonable dev-time blend
is to drive inputs and read results via tmux, do quick read-only
inspection over a raw attach, and route any *mutation* through
`debug-agent` so the production and dev paths exercise the same gates.

---

## 3. Prerequisites and enabling the server

The nREPL server is **off by default** and is never started in an
unattended run unless explicitly enabled. Three config keys govern it
(`components/agent/src/ai/brainyard/agent/core/config.clj`), each with a
durable form (`.brainyard/config.edn`) and an env-var fallback:

| Concern | Config key (`[:agent :config …]`) | Env-var fallback | Default |
|---|---|---|---|
| Enable the server | `:nrepl-enabled?` | `BY_NREPL_ENABLED=true` | `false` |
| Port | `:nrepl-port` | `BY_NREPL_PORT=7890` | `0` (ephemeral) |
| Grant (gated path) | `:nrepl-grant` | `BY_NREPL_GRANT=read-only:15m` | none |

**Runtime control (no restart).** The server can also be managed on demand by
the **debug-agent** via three commands, so you don't have to set
`BY_NREPL_ENABLED` at bootstrap:

| Command | Effect |
|---|---|
| `clj-nrepl$start-server` | Start the loopback server (idempotent). Optional `:port` (default ephemeral); writes the per-instance port file for external attach. **Does not grant eval** — set a grant separately. |
| `clj-nrepl$stop-server` | Stop the server and remove its port file. No-op when none is running. |
| `clj-nrepl$status` | Report `:running` + `:port`, the eval `:grant-active`/`:grant-scope`, the runtime-drift summary (`:drifted?`/`:drift-count`), and the `:port-files` inventory. |

These are gated to the debug-agent (`:tool-use-control {:allow ["debug-*"]}`).
Starting the server only opens the loopback channel; the grant + read-only
classifier + deny-list remain the eval security boundary.

A note on what the original framing assumed:

- The port file is written to a **per-instance** path under
  `~/.brainyard/nrepl-ports/<base>-<pid>.port` — `by-<pid>.port` for
  the agent-tui binary, `by-web-<pid>.port` for the agent-web one
  (`0700` directory perms, `0600` files via Java POSIX defaults).
  Concurrent `by` instances no longer clobber each other's port file;
  each gets its own. `clj-nrepl/list-port-files` enumerates live
  instances. `clj-nrepl/cleanup-stale-ports!` runs at startup and
  prunes files whose PID is no longer alive. *Not* a repo-relative
  `./.brainyard/` path — `start-nrepl-server-if-enabled!` builds it
  from `(System/getProperty "user.home")`. (The pre-2026-05-23 single
  `~/.brainyard/nrepl-port` file is gone; external tooling reading
  the old path will get "no such file" — see §3 commit history.)
- The port **defaults to `0` (ephemeral)** — the OS picks one and the
  bound value is written to the port file. There is no built-in default
  of `7890`; to *pin* `7890` you must set `:nrepl-port 7890` (or
  `BY_NREPL_PORT=7890`). Pinning a known port is convenient for a
  driver, but reading the port file is the robust approach because it
  always reflects the actual bound port.
- The **grant only matters for Path B** (the gated client). A raw Path A
  attach does not consult it. If you intend to delegate to `debug-agent`
  (or use `code$eval :backend :nrepl` from inside the app), set a grant;
  for read-only inspection use `read-only:15m`, and for hot-patching use
  `mutate:5m`.

### Minimal launch for a debugging session

```bash
# Pin a known port + start with a mutate grant so debug-agent can hot-patch.
BY_NREPL_ENABLED=true \
BY_NREPL_PORT=7890 \
BY_NREPL_GRANT=mutate:5m \
bb tui
```

The TUI logs `::server-started` and `::wrote-port-file`; the bound port
also lands in `~/.brainyard/nrepl-ports/by-<pid>.port` (use
`clj-nrepl/list-port-files` to enumerate live instances, or
`ls -t ~/.brainyard/nrepl-ports/by-*.port | head -1` to find the
most-recently-started). Failures to start are **non-fatal** — the
TUI continues without the server, so a driver must check the port
file (or `clj-nrepl/running?`) rather than assume.

### Pre-flight & first-launch caveats

In practice, a fresh launch tends to trip on a few realities the spec
above doesn't mention. Surfaced from real harness bring-ups; each one
will look like "the launch hung" or "the port is taken" if you don't
know to look for it.

- **No flags now boots straight into a fresh session.** Bare `bb tui`
  (no resume flags) starts a fresh session immediately — there is no
  interactive picker on the default path, so nREPL bootstrap is never
  blocked waiting on a prompt. The interactive *"N persisted session(s)
  — pick one to resume"* picker now appears **only** with a bare
  `--resume`; avoid that flag in unattended launches. (`--new`
  is still accepted as a deprecated no-op, so older recipes that pass
  `bb tui run --new` keep working.) If you ever do launch a picker
  interactively, dismiss it with `tmux send-keys -t by-debug "N" Enter`
  — until then the port file never appears and the launch looks hung.
- **Stale port files are GC'd at startup.** Crashed or killed `by`
  instances used to leave a stale `~/.brainyard/nrepl-port` whose port
  no longer matched any live process. Since 2026-05-23 the substrate
  uses per-instance files at `~/.brainyard/nrepl-ports/<base>-<pid>.port`
  and `clj-nrepl/cleanup-stale-ports!` runs at TUI startup, deleting
  files whose PID is no longer in `ProcessHandle/of`. Two concurrent
  `by` instances now produce two distinct port files (`by-1234.port`
  and `by-5678.port`) and neither clobbers the other. If you still
  want to drive a specific instance, pick its file deliberately —
  e.g. `cat ~/.brainyard/nrepl-ports/by-$(pgrep -f agent-tui-app).port`.
- **`lsof` can report ghost LISTEN entries** for sockets owned by dead
  PIDs (timing-dependent kernel cleanup). Before reacting to "port 7890
  is taken", confirm the listener's PID is actually alive with
  `ps -p <pid>` — otherwise you'll kill a phantom and the port was free
  all along.
- **Don't collide on the tmux session name.** Pick a name no other
  session is using; this doc uses `by-debug` specifically to keep the
  harness separate from any user-facing session named `by`.
- **Project `.brainyard/config.edn` silently overrides
  `BY_NREPL_*` env vars.** Per the precedence chain — schema
  default (env-var is only the default-fn) ← `!global-config` (the
  file's `[:agent :config]` subtree) ← session ← per-agent — any
  `:nrepl-enabled?` / `:nrepl-port` / `:nrepl-grant` already persisted
  in the project's `.brainyard/config.edn` *wins* over the env-var
  fallback. So
  `BY_NREPL_PORT=7891 bb tui run --new` will still bind whatever
  port the file says — the env var is silently ignored, with no
  warning. Before debugging "wrong port" or "Address already in use"
  symptoms, `grep nrepl .brainyard/config.edn` and either remove the
  pinned keys or change the port there.

---

## 4. The tmux surface — driving and reading the TUI

Run the TUI inside a tmux session the driver owns, then use the two
primitives the whole loop is built on. (Brainyard's own
`agent-tui-tmux` component wraps these same two tmux verbs —
`send-keys!` and `capture-pane` — for its internal Mode B side-channel;
an *external* driver just issues the raw `tmux` commands.)

```bash
# 1. Start a detached tmux session running the TUI.
#    -x/-y forces a pane size so the renderer isn't squeezed by a tiny
#    default geometry (80x24 is too small for several TUI panels).
#    No resume flag → fresh session, so bootstrap never pauses at a
#    picker (the picker only appears with a bare --resume). `--new` is
#    kept here as a harmless no-op for back-compat.
tmux new-session -d -s by-debug -x 200 -y 50 \
  "BY_NREPL_ENABLED=true BY_NREPL_PORT=7890 \
   BY_NREPL_GRANT=mutate:5m bb tui run --new"

# 2. Send input (a slash command, a prompt, or a keystroke). Note that
#    `agents` is a `bb tui` subcommand — *not* an in-TUI slash command.
#    Real slash commands come from `/help`; `/status` and `/usage` are
#    benign smokes.
tmux send-keys -t by-debug "/status" Enter

# 3. Capture what the pane shows (the rendered TUI, as plain text).
tmux capture-pane -p -t by-debug

# 4. Capture scrollback too, when output scrolled off-screen.
tmux capture-pane -p -S -2000 -t by-debug
```

Because the TUI repaints in place, a driver should treat
`capture-pane` as a *snapshot* and poll: send input, sleep briefly, then
capture until the screen settles (e.g. the status bar shows `idle`
again). Two TUI affordances make output legible to a driver:

- **The status bar** (`bases/agent-tui/.../layout.clj`,
  `format-status`) shows run state and a **`drifted (N)` chip** that
  lights up after any mutating eval reaches the server. Polling for
  `idle` is the simplest "the agent finished" signal; watching the
  `drifted (N)` chip tells the driver a hot-patch landed.
- **Settle detection via mulog (preferred over status-bar polling).**
  agent-tui emits a uniform
  `:ai.brainyard.agent-tui.core/turn-complete` mulog event on every
  `:agent.ask/post`, regardless of agent type, with stable fields
  `:agent-id`, `:session-id`, `:input-preview`, `:result-length`. A
  driver can `tail -F ~/.brainyard/logs/agent-tui-app.log` and await
  the next event deterministically instead of polling `capture-pane`
  for `idle`:

  ```bash
  # Await any turn complete:
  tail -F ~/.brainyard/logs/agent-tui-app.log | grep -m1 turn-complete

  # Await a turn for a specific agent (sub-agent invocations also fire
  # this event — filter by the agent-id you sent input to):
  tail -F ~/.brainyard/logs/agent-tui-app.log \
    | awk '/turn-complete/,/^$/' \
    | grep -B1 -m1 ':agent-id :debug-agent/gold-goat-992'
  ```
- **Atomic per-session settle stamp (preferred over the mulog tail).**
  In addition to the mulog event, `emit-turn-complete!` writes a small
  EDN file at `<project>/.brainyard/sessions/<session-id>/turn.complete` with
  the same `{:ts :agent-id :session-id :input-preview :result-length}`
  payload. Drivers can `inotifywait` (Linux) / `fswatch` (macOS) on
  the stamp and read its content without parsing the multi-MB mulog
  log:

  ```bash
  # Linux: block until next turn, then print which agent + result-length.
  SID=agt-1779515068327-1883
  inotifywait -qq -e close_write .brainyard/sessions/$SID/turn.complete \
    && cat .brainyard/sessions/$SID/turn.complete

  # macOS:
  fswatch -1 .brainyard/sessions/$SID/turn.complete \
    && cat .brainyard/sessions/$SID/turn.complete
  ```
- **Non-interactive entry points** sidestep the screen-scraping problem
  entirely. `bb tui ask -a debug-agent "<question>"` runs one agent turn
  and prints its answer — much easier for a driver to parse than a
  repainting alt-screen. **Important caveat:** `bb tui ask` starts a
  *fresh, standalone TUI process* — it does **not** attach to a running
  TUI's live JVM. Use it when you want a specialist's verdict on a
  portable question. For *live* debugging of the running image, drive
  that image's tmux pane (Strategy B in §5.2) — or use the
  Path A attach-mode recipe below, which is the cleanest way to drive
  a single turn against the live JVM without touching tmux at all.
- **Path A attach-mode prompt-and-answer (drive a turn via nREPL,
  receive the answer through the nREPL response).** `agent/ask` is
  synchronous and returns `{:answer ..., :usage ..., :error ...}`;
  combined with `tui-session/get-active-agent` and the loopback nREPL
  server, this is a one-liner that fully closes the "stateful
  machine-readable debug session from outside the tmux pane" gap
  (§9 #3) — *no* `bb tui ask` (which forks a fresh JVM), *no*
  send-keys / capture-pane round-trip:

  ```bash
  PORT=$(cat ~/.brainyard/nrepl-ports/by-*.port | head -1)   # or pick a specific by-<pid>.port
  clj-nrepl-eval -p $PORT \
    "(ai.brainyard.agent.interface/ask
       (ai.brainyard.agent-tui.session/get-active-agent)
       \"reply with exactly the word pong and nothing else\")"
  # → {:answer "pong", :usage {...}}
  ```

  **CLI sugar:** `bb tui:attach-ask [-p PORT] "prompt"` (defined in
  `bb.edn`) wraps the above — auto-discovers the port from
  `~/.brainyard/nrepl-ports/by-*.port`, drives the same eval, prints
  just `:answer`. Use the bb task for everyday operator use; reach for
  the raw `clj-nrepl-eval` form when you need the full return map
  (`:usage`, `:error`) or want to embed it in a larger driver script.

  The agent picked is whatever the TUI currently shows as active (see
  `/agent status` / `/agent switch` in §5.2). To target a specific
  *instance* (different from the active one), the lowest-friction
  path is to `/agent switch <instance-id>` *first* via the tmux pane,
  then run the recipe — or look the instance up directly in the
  process registry, e.g. via the same iteration the `/agent switch`
  handler uses in
  `bases/agent-tui/src/ai/brainyard/agent_tui/commands.clj`
  (`(some #(when (= target-id (:agent-id %)) %) instances)`).

Two driver pitfalls worth knowing before you write the loop:

- **The Enter-drop race.** `tmux send-keys "<text>" Enter` occasionally
  delivers the keystrokes such that the `Enter` is absorbed into the
  TUI's input buffer rather than submitting it — most reliably
  reproducible when sent immediately after a previous submission. The
  visible symptom is the next `capture-pane` showing `> <text>` still
  sitting in the prompt with no response. The safe recovery is
  **capture-then-resubmit** — never a blind "always send a second
  Enter as a guard":

  ```bash
  tmux send-keys -t by-debug "<text>" Enter
  sleep 1
  pane=$(tmux capture-pane -p -t by-debug)
  if echo "$pane" | grep -qF "> <text>"; then
    tmux send-keys -t by-debug Enter   # retry only when needed
  fi
  ```

  A naive "always send a second Enter" recipe is **actively unsafe for
  picker-opening commands** (see next bullet) — the second Enter
  activates whatever item the picker has highlighted, silently running
  a command the driver didn't intend.

- **Picker-opening commands.** Several slash commands open an inline
  selection picker when called without their final argument. Verified
  against a running `bb tui`:
  - `/agent` — subcommand picker (5 items: `status` / `new` / `switch`
    / `close` / `trace`).
  - `/agent new` — agent-type picker (20 items, scrollable, the
    indicator `↓ N more` appears when entries overflow the visible
    area). That's every non-hidden agent type — 21 ship, but
    `react-agent` is `:visibility :hidden`, so it isn't offered.
  - `/agent switch` — instance picker; entries shaped
    `<type>/<word>-<word>-<digits>` (e.g.
    `debug-agent/gold-goat-992`), with `← current` marking the active
    instance.

  The picker model:
  - `▸` marks the highlighted item, defaulting to the first entry;
  - Up / Down arrows navigate;
  - `Enter` commits the highlighted item (which is why the Enter-drop
    guard above is unsafe here — it auto-runs the default `status` /
    `acp-agent` / first-instance);
  - `Escape` dismisses, but **leaves the partial trigger in the input
    box** (e.g. `> /agent` after dismissing the subcommand picker) —
    the driver must clear it before the next command;
  - input is cleared with `C-a C-k` (readline-style: start-of-line,
    kill-to-end). `C-u` is **unbound** in this TUI despite the shell
    convention; `BSpace × N` works but is clumsy.

---

## 5. The autonomous loop

Putting the surfaces together, the loop an external driver runs is:

```
   ┌─────────────────────────────────────────────────────────────┐
   │  for each test case / reported bug:                          │
   │                                                              │
   │  1. EXERCISE   tmux send-keys → drive the input that should  │
   │                trigger the behaviour                         │
   │                                                              │
   │  2. OBSERVE    capture-pane (+ scrollback) → read the actual │
   │                output; compare against expected              │
   │                                                              │
   │  3. DIAGNOSE   if wrong: attach nREPL → reproduce in the     │
   │                live image, read *e / stack trace, inspect    │
   │                related state (system map, atoms, registry)   │
   │                                                              │
   │  4. HYPOTHESIZE  state the suspected root cause explicitly   │
   │                                                              │
   │  5. PATCH      alter-var-root / def the replacement in the   │
   │                live image (gated Path B → drift-marked;      │
   │                or raw Path A if the driver owns the risk)    │
   │                                                              │
   │  6. RE-RUN     repeat step 1 with the same input → confirm   │
   │                the output is now correct                     │
   │                                                              │
   │  7. repeat 3–6 until fixed, then PROMOTE (see §7)            │
   └─────────────────────────────────────────────────────────────┘
```

Steps 3–5 mirror the `debug-agent` instruction's own
*reproduce → probe → hypothesize → test* loop — which is the point:
the external driver can either run this reasoning itself over a raw
nREPL attach, or hand the bug to `debug-agent` and let the specialist
run the loop inside the gated path.

### 5.1 Strategy A — the driver does the nREPL reasoning

The external agent attaches a raw nREPL client to the port from
`~/.brainyard/nrepl-port` and evaluates directly. It uses the TUI
surface only for steps 1–2 (exercise + observe) and step 6 (re-run).
Fastest and most flexible; the driver carries the full responsibility
that comes with ungated eval.

### 5.2 Strategy B — delegate to `debug-agent` over the TUI

The external agent never speaks nREPL itself. It feeds the bug to the
in-app specialist:

```bash
# Create a debug-agent INSTANCE (auto-switches the active agent), then
# send the bug as plain input.
tmux send-keys -t by-debug "/agent new debug-agent" Enter; sleep 1
tmux send-keys -t by-debug \
  "reproduce the NPE in ai.brainyard.foo/bar when :x is nil, then hot-patch it" Enter
# …poll capture-pane until idle; read debug-agent's answer panel…
```

(The `/agent` slash command takes `status` / `new` / `switch` / `close`
/ `trace` subcommands. `switch` takes an **instance ID** in the shape
`<type>/<word>-<word>-<digits>` (e.g. `debug-agent/gold-goat-992`, as
shown by `/agent switch` or `/agent status`) — *not* a type name;
`/agent switch debug-agent` fails with `Instance not found:
debug-agent`. `/agent new <type>` creates a new instance *and*
auto-switches the active agent to it in one step, which is what a fresh
driver run usually wants. Subsequent plain input goes to the active
instance. **Picker note:** calling any of `/agent`, `/agent new`, or
`/agent switch` *without* the trailing argument opens an interactive
selection picker — see §4 for picker semantics and why the Enter-drop
guard must be skipped (or replaced with capture-then-resubmit) for
these commands. Note also that `bb tui ask -a debug-agent "<question>"`
is *not* an alternative to this flow for live debugging — it starts a
fresh, standalone TUI process and does not attach to the running JVM;
see §4.)

`debug-agent` pins its own nREPL session, routes every ` ```clojure `
block to the live runtime, runs the loop under the grant/classifier/
confirm gates, and marks drift on each mutation. The driver reads the
agent's conclusions from the pane. Slower per step than Strategy A, but
auditable and hands-off — the app debugs itself, and the driver only
orchestrates *which* bugs to chase and *whether* to promote the fix.

### 5.3 The recommended blend

Exercise and observe over tmux (§4). Do read-only inspection over a raw
attach when you need speed. Route **mutations** through `debug-agent` so
they are confirmed and drift-marked — the `drifted (N)` chip then gives
the driver a visible, pollable signal that a hot-patch is live, and the
mulog audit trail records exactly what changed.

---

## 6. Worked sketch

A test case drives `/agent new debug-agent` (expecting a new instance to
be created and the active agent to switch to it), but the captured pane
reports `Unknown agent type: debug-agent` instead. The driver:

```bash
# 1–2. Exercise + observe.
tmux send-keys -t by-debug "/agent new debug-agent" Enter; sleep 1
tmux capture-pane -p -t by-debug          # → "Unknown agent type" instead of a new instance
# (if the capture shows "> /agent new debug-agent" still in the input,
# resubmit per §4 — capture-then-resubmit, not a blind second Enter)
```

```clojure
;; 3. Diagnose over nREPL (read-only is enough here).
;;    Attach to (slurp "~/.brainyard/nrepl-port"), then:
(require '[ai.brainyard.agent.core.tool :as tool])
(->> @tool/!tool-defs vals (filter #(= :agent (:type %))) (map :id) sort)
;; → reveals whether :debug-agent is registered in the live image at all
```

If `:debug-agent` is present in the registry, the bug is in the TUI's
`/agent new` handler (type lookup, instantiation path), not
registration — the driver narrows to that fn, patches it (preferably by
handing the now-localized bug to an *existing* `debug-agent` instance
so the mutation is gated and drift-marked), and re-runs `/agent new` to
confirm. If `:debug-agent` is *absent*, the namespace never loaded, and
the driver chases the require chain instead. Either way, *the app never
restarted*: each hypothesis was tested against the same live image in
seconds.

---

## 7. Promoting a live fix to source

A hot-patch lives only in the running JVM and disappears on restart.
brainyard deliberately keeps "mutate the runtime" and "edit the source"
as separate, reviewed steps — runtime drift is an *audit anchor*, not a
commit.

When a fix is proven, `debug-agent` exposes `debug$promote-hot-patch`,
which writes a markdown artifact under
`.brainyard/agents/debug-agent/promotions/<ts>-<slug>.md` capturing the drift
marker, the validation evidence, and a `:pattern` / `:replacement` pair,
and returns a ready-to-run hand-off command:

```
bb tui ask "@.brainyard/agents/debug-agent/promotions/<ts>-<slug>.md" -a update-agent
```

`update-agent` then runs its own probe → apply → verify → persist →
rollback pipeline against the source file and emits `Saved edit:` /
`Rollback:` lines. An external driver can run that command itself to
close the loop — turning a validated live patch into a committed,
revertable source change. (Full mechanics in
`docs/design/debug-agent-design.md` §7 and
`docs/design/update-agent-design.md`.)

**Layered hot-patches.** Since the rollback overhaul (`afb2800` /
`40bceda` / `5123f59` / `5287465`), update-agent's rollback is
**transaction-scoped**: a failed verify restores the pre-APPLY bytes,
not HEAD. So a debug session can promote multiple patches to the same
file in sequence without committing between each — pass
`:dirty-ok? true` and prior promotions in the working tree survive
even if a later one fails. The operator-facing `:rollback` hint
becomes `cp -- '<backup>' '<target>'` (pointing at
`.brainyard/agents/update-agent/backups/<ts>-<slug>.bak`) for `:dirty-ok?`
edits, so manual undo also stays transaction-scoped. Validated
end-to-end by the four-patch per-instance nREPL port-file series
(`f4f7c81` → `be302c5`).

---

## 8. Safety and operational notes

- **Loopback only.** The server rejects non-loopback binds. Anyone who
  can open a socket to the port can eval in your JVM, so do not forward
  the port off the host.
- **Path A is ungated by design — keep it to dev & test.** A raw nREPL
  attach gets full `eval`. It is meant for development against a
  disposable image, not for production. The production live-debugging
  path is Path B: if you want the deny-list / scope / confirm / drift
  guarantees, go through the gated client — i.e. `debug-agent` or
  `code$eval :backend :nrepl` — not a bare CIDER connection.
- **The confirm hook is visibility-only (v1) today.** Since `eb3567c`
  the TUI installs a confirm-fn on nREPL startup
  (`agent-tui/core.clj` → `tui-confirm-mutation`). On the first
  mutating eval per session it writes a scrollback notice
  (`[clj-nrepl] mutating eval auto-allowed …`), emits a mulog
  `::mutation-allowed` audit, then auto-approves; subsequent mutations
  short-circuit per `confirm-mutation!`'s contract. So mutations are
  now operator-visible — but there is still **no interactive Y/n
  popup**; the v2 gate (blocking nREPL eval until the operator answers
  via `popup.clj`) is pending (§9 #4).
- **Drift is process-global and process-local.** `drift-markers` aggregate
  every mutation in the process (any agent, any path) and survive only
  until restart. The `drifted (N)` chip reflects the whole process, not
  one agent.
- **Server start is best-effort.** Always verify via the port file or
  `clj-nrepl/running?`; never assume the server came up.

---

## 9. What's missing (in-progress)

The substrate is real; the *turnkey external loop* is not yet shipped.
Concretely, the following are still manual or unbuilt:

1. **Driver harness — single-turn primitives landed; multi-turn /
   predicate loop pending.** Two `bb` tasks now own the per-turn
   plumbing so external agents don't reassemble it each time:
   - `bb tui:attach-ask [-p PORT] "prompt"` — nREPL/attach mode,
     synchronous, returns just `:answer`. Auto-discovers the port from
     `~/.brainyard/nrepl-ports/by-*.port` (§9 #3 v2, `2b0ed91`).
   - `bb tui:drive -s TMUX-SESSION [-S BY-SESSION-ID] [-T SECS] "prompt"` —
     tmux mode, owns `tmux send-keys` + multi-stamp watch +
     `tmux capture-pane`. Watches every `turn.complete` file in
     `<project>/.brainyard/sessions/` and detects which one's mtime advances
     after send-keys, filtering by an `:input-preview` prefix match so
     sub-agent ticks and concurrent instances don't cross-fire. Pane
     capture includes scrollback so verbose iteration trails don't
     hide the answer box. Validated end-to-end against a live
     debug-agent in `by-debug`.
   - `bb tui:drive-loop [-p PORT] --until PREDICATE [--max N] "prompt"` —
     repeats `tui:attach-ask` until a Clojure predicate over the answer
     string is true, or `--max` iterations are reached. The predicate
     is evaluated with `answer` (string) bound in scope; e.g.
     `--until '(re-find #"COMPLETE" answer)'`. Same prompt each turn —
     the agent's own session memory makes consecutive same-prompt
     turns meaningful. Exit codes: 0 satisfied / 2 max-reached / 1 other
     error. Validated end-to-end against the live `by` JVM.
   §9 #1 is now closed for nREPL/attach mode; the tmux-mode multi-turn
   loop (predicate against an answer parsed from `capture-pane`) is
   the natural next add-on if it's ever needed — for now, drive in
   nREPL mode where the answer is plain text.
2. **Settle detection — v1 + v2 both landed.** Two surfaces, same
   stable payload (`:ts :agent-id :session-id :input-preview
   :result-length`), both wired in
   `bases/agent-tui/src/ai/brainyard/agent_tui/core.clj`'s
   `emit-turn-complete!`:
   - **v1:** uniform `:ai.brainyard.agent-tui.core/turn-complete`
     mulog event on every `:agent.ask/post`, regardless of agent type
     (drivers `tail -F` the mulog log — see §4).
   - **v2:** atomic per-session stamp file at
     `<project>/.brainyard/sessions/<session-id>/turn.complete`, small EDN
     payload, best-effort write (non-fatal failure). Drivers
     `inotifywait` (Linux) / `fswatch` (macOS) on the stamp instead
     of parsing the multi-MB mulog log — see §4 for the recipe.
   The "no structured turn-complete signal" gap is closed for both
   log-tail-friendly and fs-watch-friendly drivers.
3. **Attach-mode debug entry point — recipe v1 landed; CLI sugar v2
   pending.** V1 turned out to be a *documentation* gap, not a code
   gap: `agent/ask` is synchronous and returns
   `{:answer …, :usage …}`; combined with
   `tui-session/get-active-agent` and the loopback nREPL server, a
   one-line `clj-nrepl-eval -p $PORT "(agent/ask … \"prompt\")"`
   drives a turn against the live JVM and reads the answer back
   through the nREPL response — no tmux, no fresh JVM. See §4 for
   the recipe; validated live 2026-05-23 — `ask debug-agent "reply
   with exactly the word pong …"` → `{:answer "pong"}`. **V2 (CLI
   sugar) landed 2026-05-24** as `bb tui:attach-ask [-p PORT] "prompt"`:
   auto-discovers the port from `~/.brainyard/nrepl-ports/by-*.port`
   (newest by mtime if more than one), opens an nREPL eval, calls
   `(agent/ask <active-agent> "prompt")`, prints just `:answer`.
   Validated end-to-end against the live JVM:
   `bb tui:attach-ask -p 52646 "reply with exactly pong"` → `pong`.
4. **Real interactive confirm-fn (popup gate) — v1 visibility-only
   landed; v2 popup pending.** V1 of the confirm-fn is now wired in
   `bases/agent-tui/src/ai/brainyard/agent_tui/core.clj`'s
   `tui-confirm-mutation` — it installs on nREPL startup, and on the
   first mutating eval per session emits a scrollback notice
   (`[clj-nrepl] mutating eval auto-allowed …`) plus a mulog
   `::mutation-allowed` audit, then auto-approves. This closes the
   literal "no fn installed" gap (the `::no-confirm-fn-installed`
   warning no longer fires) and makes mutations operator-visible in the
   TUI; subsequent mutations in the same session short-circuit per
   `confirm-mutation!`'s contract. **V2 still pending:** replace V1 with
   a real interactive Y/n gate via `popup.clj`'s questionnaire,
   blocking the nREPL thread until the operator responds; fall back to
   the current V1 behavior in Mode A (no tmux popup support).
5. **Full-scale debugging.** The loop is proven on small, localized bugs
   (the validation in `debug-agent-design.md` §11 covers routing,
   session pinning, drift, and promotion). Driving it across larger,
   multi-component failures end-to-end is the next milestone.
### Validation evidence

Run 2026-05-23 against a freshly-launched harness (`bb tui` in detached
tmux, nREPL on port 7890, `:mutate` grant from this project's pinned
`.brainyard/config.edn` `:nrepl-grant "mutate:24h"` — `BY_NREPL_*`
env vars were ignored per the precedence note in §3 "Pre-flight";
debug-agent instance auto-created via `/agent new debug-agent`). Single
prompt:

> *"In the live nREPL, run these two evals in order then stop: first
> `(+ 1 2)`, then `(def *probe* :live)`. Reply in one line with: (a)
> the result of `(+ 1 2)`, (b) whether the def was accepted under our
> `:mutate` grant, (c) whether a drift marker was recorded."*

Outcomes verified both by reading the rendered TUI pane (debug-agent's
own answer panel) **and** by a separate Path A `clj-nrepl-eval` session
against the same port.

| Doc claim | Outcome |
|---|---|
| ` ```clojure ` blocks routed to the live nREPL | `(+ 1 2)` → `3`; `(def *probe* :live)` → `#'user/*probe*` |
| `:mutate` grant accepts the mutation | def passed without rejection |
| First-mutation confirm default-allows | No popup fired — matches the §8 caveat |
| Drift marker recorded | `{:count 1, :drifted? true, :markers [{:reason :mutating-eval, :code-preview "(def *probe* :live)", :session "f75a2d1b…"}]}` |
| `drifted (N)` status-bar chip lights | `drifted (1)` visible from iter 2 onward, held at idle |
| Mutation visible across sessions in same JVM | Path A's separate persistent session reads `user/*probe*` = `:live` |
| Cost / latency | 5 Opus iterations, 142,153 tokens, ~30s wall, **$0.36** |

What this run did **not** exercise (status as of 2026-05-24):
- ~~The promote-to-source hand-off (§7) — `debug$promote-hot-patch` →
  `update-agent` end-to-end against the live image.~~ **Validated
  2026-05-23/24.** debug-agent emitted four hot-patch promotions
  describing the per-instance nREPL port-file refactor; they were
  applied in sequence via `bb tui ask -a update-agent` and committed
  as `f4f7c81` (foundation), `f651fc8` (interface re-export), `ee95490`
  (agent-tui callsite), `be302c5` (agent-web callsite). The transaction-
  scoped rollback overhaul (`afb2800` etc.) was forced by issues the
  series surfaced; see §7.
- The full-scale multi-component failure scenarios called out as
  gap #5 above.
- A `:read-only:15m` grant — only the `:mutate:5m` path was driven, so
  the read-only rejection path is unverified by this run.

Minor observation worth folding into debug-agent's prompt: iter 3 of
this run tried `(clj-nrepl$drift-markers)` as a sandbox-bound symbol
inside a ` ```clojure ` block — but it is a *registered tool name*,
not a Clojure var — got `Unable to resolve symbol`, and correctly fell
back to a tool call on iter 4. The misstep costs one extra Opus
iteration (~$0.07 here) per drift-querying turn; addressable by
clarifying in debug-agent's instruction that drift queries go through
the tool registry, not via a sandbox-symbol invocation.

---

## 10. Related reading

- `docs/design/clj-nrepl-eval.md` — the substrate: loopback server,
  grant/scope/confirm, read-only classifier, drift markers, the
  `:clj-nrepl-eval` task job type. §7.2 (self-debugging) and §8 (safety)
  are the most relevant.
- `docs/design/debug-agent-design.md` — the internal specialist this
  driver delegates to; per-instance nREPL session, `:clj-backend :nrepl`
  routing, the promotion hand-off.
- `docs/design/update-agent-design.md` — the source-promotion target.
- `docs/tui/architecture.md` — the tmux-based TUI and the
  `agent-tui-tmux` substrate (`send-keys!` / `capture-pane`).
- Source of truth:
  `components/clj-nrepl/src/ai/brainyard/clj_nrepl/` (server, client,
  grant, classifier, confirm, drift),
  `bases/agent-tui/src/ai/brainyard/agent_tui/core.clj`
  (`start-nrepl-server-if-enabled!`),
  `components/agent/src/ai/brainyard/agent/common/debug_agent.clj`.
