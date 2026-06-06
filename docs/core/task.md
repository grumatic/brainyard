# Task Manager

The task manager is the background-execution layer for long-running
work. It lets the agent kick off bash scripts, tool invocations,
stdio-driven subprocesses, or sandboxed Clojure evaluations without
blocking the interactive loop, and it provides a uniform UI plus a
durable on-disk record for inspecting their progress.

Primary files: `components/agent/src/ai/brainyard/agent/task/{protocol.clj, manager.clj, executor.clj, commands.clj, persist.clj, format.clj}`.

---

## Why separate from the BT?

A BT tick is meant to be short — every node should react to cancellation
within a second or two. But real work is not always bounded: a build
runs for minutes, a sub-agent waits for an LLM round-trip, a
data-processing job chews for an hour. Running that on the BT thread
blocks the turn and makes Ctrl-C unresponsive.

The task manager decouples execution time from interactive time. A short
sub-agent call *might* still return inline; anything longer is handed off
to a task. The agent continues its turn and can poll or await completion
on its own terms. The deadline that governs the inline/hand-off boundary
is the agent's `:task-timeout-ms` config (default 120 000 ms), not a
hard-coded constant.

---

## Data model

`task/protocol.clj` defines the `Task` record:

```clojure
{:id               :task-7              ;; keyword :task-N — monotonic, seeded past on-disk dirs
 :name             "bash: make build"   ;; human-readable
 :job-type         :bash                ;; :bash | :tool | :cli-client | :clj-sandbox-eval | :clj-nrepl-eval
 :job-config       {…}                  ;; job-type-specific config map
 :status           :pending             ;; :pending | :running | :completed | :failed | :cancelled
 :created-at       1716300000000        ;; epoch ms
 :started-at       1716300000010        ;; epoch ms | nil
 :completed-at     1716300042000        ;; epoch ms | nil
 :result           {:exit-code 0}       ;; terminal result map or {:error "…"} | nil
 :output-lines     #<atom [..]>         ;; atom<vector<string>> — tail cache (last N lines)
 :max-output-lines 500                  ;; tail-cache cap; full record lives on disk
 :future-ref       #<Future>            ;; pool future for cancellation | nil
 :schedule         nil                  ;; {:cron "…"} — reserved for future cron support
 :metadata         {:display-mode :foreground …}}
```

Note `:output-lines` is a *tail cache*, not the full output — the
complete combined stdout/stderr lives on disk (see [Persistence](#persistence)).
`:metadata` carries lifecycle flags such as `:display-mode`
(`:foreground`/`:background`), a `:!sync-waiter?` atom (sync waiters),
`:auto-background-ms` (auto-flip timer), and `:retried-from` (on retry).

Plus two protocols:

- **`IJobExecutor`** — polymorphic dispatch on `:job-type`. Three methods:
  `execute-job` (runs on a pool thread, takes an `on-output` line callback),
  `cancel-job`, and `job-type`. Implementations live in `task/executor.clj`:
  `BashJobExecutor`, `ToolJobExecutor`, `CliClientJobExecutor`,
  `ClojureSandboxJobExecutor`, and `NreplEvalJobExecutor` (each a `defrecord`).
- **`ITaskManager`** — lifecycle + registry: `create-task`, `start-task`,
  `cancel-task`, `retry-task`, `remove-task`, `get-task`, `list-tasks`,
  `shutdown`.

`TaskManager` (`task/manager.clj`) is a `defrecord` holding an `executors`
map keyed by job-type. State lives in JVM-global atoms — `!tasks`,
`!task-counter`, `!default-manager`, `!executor-service`,
`!detached-handlers`, `!watcher-future` — so a single registry is shared
across the TUI, daemon, and sandbox tools. `get-default-manager` lazily
auto-initializes via lock-free CAS; `set-default-manager!` lets tests
inject a custom pool. The pool is a fixed thread pool (default size 4)
of daemon threads named `agent-task-N`, so the JVM exits cleanly without
an explicit `shutdown`.

---

## Pure-async detach contract

Executors never block the pool thread for the lifetime of the work.
`execute-job` kicks off the underlying process / future and returns one
of two things:

- A **terminal map** — `{:exit-code N}`, `{:error "…"}`, or `{:result v}`
  — for work that finished on the pool thread.
- A **detached map** — `{:status :detached :on-poll … :on-cancel …}` —
  when the work outlives the pool thread.

On detach, the manager parks the `:on-poll`/`:on-cancel` closures in
`!detached-handlers` and frees the pool thread. A single shared daemon
watcher (`start-detach-watcher!`) polls every `:on-poll` roughly every
300 ms; when one returns a terminal map instead of the `tp/still-running`
sentinel, `finalize-task!` promotes the task to `:completed`/`:failed`.
`finalize-task!` is the single source of truth for terminal transitions —
idempotent, fires `:task/completed`, and closes the disk appender.

`cancel-task` prefers a detached task's `:on-cancel` closure (the pool
future has already returned, so cancelling it is a no-op); for the
synchronous path it interrupts the pool future and calls `cancel-job`.

---

## Executor implementations

### BashJobExecutor

Runs shell commands via `ProcessBuilder` (`/bin/sh -c <command>`):

- Redirects stderr into stdout and streams the combined output line-by-line
  into the ring buffer via a daemon reader future.
- Returns `:detached` immediately; `:on-poll` checks `Process.isAlive`
  and, once dead, reports the exit code (`{:exit-code 0}` or
  `{:error "Exit code: N" …}`).
- `:on-cancel` force-kills the whole process tree (`destroy-process-tree!`
  snapshots descendants *before* killing the parent, so wrapper chains like
  `sh -c 'bash foo.sh' → bash → sleep` aren't orphaned).
- Closes the child's stdin so CLIs that read stdin don't hang.
- Permission checks happen *before* the task is created — see
  [Path validation](#path-validation).

### ToolJobExecutor

Invokes a registered tool (`call-tool`) on the pool thread, wrapped in a
future bounded by `:timeout-ms` (default 120 000):

- Streams progress lines (invocation, completion, result) to the ring buffer.
- If the tool returns a `clojure.lang.Agent` (a `defagent` handle), it
  `await`s it and extracts `:output`.
- On timeout, `future-cancel` sends an interrupt; tools that don't honour
  interrupts may keep running in the background until they return.
- Final value is stored under `:result`.

### CliClientJobExecutor

Spawns a child process via the stdio client
(`ai.brainyard.agent.stdio.client`):

- A forwarder future tails the client's `:!lines` and fans new lines to
  `on-output` while `:!running` is true.
- With an `:interaction-fn`, drives the subprocess and returns its result;
  otherwise shuts the client down and reports the exit code.
- Closes the client on completion or error.

This is the executor that makes multi-agent test orchestration and
integration testing practical.

### ClojureSandboxJobExecutor

Evaluates Clojure code in a `clj-sandbox` SCI context:

- Runs `eval-code-thunk` inside a daemon future and returns `:detached`
  immediately (SCI tight loops ignore `Thread.interrupt`, so cancel is
  best-effort at the future level).
- `:on-poll` harvests the future when `.isDone`, projecting the result
  into `{:code … :output … :result/:error …}` (FINAL/FINAL-VAR
  termination arrives as an `:error` map carrying `:final-value`).
- Backs the `:sandbox` arm of the unified code-eval contract used by CoAct.

### NreplEvalJobExecutor

Evaluates Clojure code in the **live brainyard JVM** via the in-process
loopback nREPL server (`components/clj-nrepl`). Symmetric to
`ClojureSandboxJobExecutor` so CoAct's dispatch path stays uniform:

- Job-config: `{:code <str> :session <nrepl-sess-id or nil> :timeout-ms <ms>}`.
  `:session` is a server-issued id (from `clj-nrepl/new-session`) — used
  by specialist defagents like `debug-agent` that pin one session per
  instance so multi-iteration investigations accumulate namespace state.
- Runs `clj-nrepl/eval-string` inside a daemon future and returns
  `:detached` immediately. The full gate chain (server-up →
  grant-active → deny-list → mutating-scope → confirmation) fires
  inside `eval-string`; drift markers are recorded on attempt for
  mutating forms.
- `:on-poll` harvests the future when `.isDone`, projecting to
  `{:code :output :result :error :ns}` — same shape as the sandbox
  executor (no FINAL/FINAL-VAR semantics; nREPL terminates by
  returning a value).
- `:on-cancel` calls `clj-nrepl/interrupt!` on the session (cooperative)
  AND `future-cancel`s the harvest future.
- `project-terminal-task->eval-entry` (in `coact_agent.clj`) recognises
  both `:clj-sandbox-eval` and `:clj-nrepl-eval` as "clojure" job-types
  and projects nREPL's already-string-printed `:value` verbatim (vs.
  the sandbox path which `pr-str`s the raw Clojure value).
- Only active when the host has opted in via `BRAINYARD_NREPL_ENABLED=true`
  on the bb tui / web base — the server is OFF by default. Eval requires
  an active grant from `BRAINYARD_NREPL_GRANT=<scope>[:<ttl>]`. See
  `docs/design/clj-nrepl-eval.md` and `docs/design/debug-agent-design.md`.

---

## Lifecycle and notifications

```
create-task → :pending
                 │  start-task (submit to pool)
                 ▼
              :running   (future starts; executor streams output)
                 │  terminal map → finalize on pool thread
                 │  :detached    → watcher polls :on-poll, then finalize
                 ▼
          :completed | :failed | :cancelled
```

Notifications go through the shared hooks registry (see [agent.md
§Hooks](agent.md)):

- `:task/created` — `{:task}` payload, fired when a task is registered.
- `:task/completed` — `{:task}` payload, fired on any terminal status.
- `:task/display-mode-changed` — `{:task :display-mode}`, fired when a
  task flips between `:foreground` and `:background`.

The TUI subscribes to these and renders a sticky per-task activity area
(header + streamed output lines) for `:foreground` tasks, disposing the
block when a task goes `:background` or terminal. Display-mode is purely
a visibility concern — the task keeps running either way.

### Sync vs. async waiting

`task$run` exposes two orthogonal dimensions:

- **`:sync`** (default false). Async returns immediately with the task id.
  Sync blocks in `await-task`, which polls `@!tasks` every 100 ms until
  the task is terminal, the deadline hits, or an external supervisor flips
  the `:!sync-waiter?` atom (`set-sync-mode!`, true → false) to detach the
  waiter.
- **`:on-timeout`** (sync only; default `:detach`). On reaching the
  waiter's deadline, `:detach` flips the task to `:background` and returns
  a `:pending` snapshot while the task keeps running (harvest later with
  `task$detail`); `:kill` cancels the task and returns a `:timeout`
  snapshot.

This **inline-up-to-deadline, task-beyond** pattern keeps latency low for
short calls without sacrificing async support for long ones.

---

## LLM-facing commands

Registered via `task/commands.clj` as `defcommand`s (bundled in
`task-commands` for `:agent-tools` binding):

| Command | Purpose |
|---|---|
| `task$list` | List tasks, optionally filtered by `:status`. Each row is `{:id :name :status :job-type :output-lines}`. |
| `task$detail` | Full task state: status, lifecycle timestamps, executor result, tail-cache stats, and (with `:last-n N`) the last N cached lines. Surfaces `:output-file` / `:meta-file` paths and a `:truncated?` flag when disk has more than the cache. Replaces the old `task$output`. |
| `task$cancel` | Cancel a running task. |
| `task$run` | Polymorphic create+start. `:job-type :bash` (needs `:command`) or `:tool` (needs `:tool-id`, optional `:tool-args` JSON). Plus `:sync` and `:on-timeout` (see above), `:name`, `:timeout`. |
| `task$wait` | **Block** until a task is terminal or `:timeout-ms` elapses. Returns the terminal `:status`/`:result`/`:output`, or a `still-running` snapshot on timeout. Use for a short wait after a hold-timeout when a task is still running. |
| `task$wakeup` | **Park** the current turn and auto-resume the agent later (yield-and-resume) — frees the session during a long wait. Two mutually-exclusive modes: `:delay-ms` resumes after a timer; `:task-id` resumes when an existing task terminates. Top-level agent only; bounded by `:max-wakeups-per-session` (default 20). See [Blocking vs. parking](#blocking-vs-parking). |
| `task$sweep` | Garbage-collect on-disk artifacts (see [Persistence](#persistence)). Scopes: `tasks`, `coact-scratch`, `sandbox-cache`, `all`; `:dry-run?` reports without deleting. |

CoAct (the default agent) auto-discovers these via `list-tools`.
ReAct must include them in `:agent-tools :tools` (a per-agent opt-in,
so lightweight agents don't get task plumbing they don't need).

### Blocking vs. parking

Two ways to wait on an in-flight task, with opposite session semantics:

- **`task$wait` — block.** Holds the turn in `await-task` until the task is
  terminal or `:timeout-ms` elapses. The session is occupied for the whole
  wait. Best for short waits where the result is needed before the turn can
  continue.
- **`task$wakeup` — park (yield-and-resume).** Returns immediately with
  `:parked true`; the LLM acknowledges in one line and ends the turn, freeing
  the session. A one-shot watch on `!tasks` resumes the agent in a fresh turn
  (with prior turns in context) when the wake condition fires. Best for long
  waits — the user (or sibling sessions) can keep working meanwhile.

`task$wakeup` has two mutually-exclusive modes:

| Mode | Arg | Resumes when… |
|---|---|---|
| Timer | `:delay-ms` | a background `:bash sleep` task it creates **completes** (an aborted/cancelled timer does **not** resume). |
| Watch | `:task-id` | an **existing** task terminates in **any** status (`:completed` / `:failed` / `:cancelled`) — pairs with deferred-tasking: park on a detached task instead of polling it with `task$wait`. Creates no new task. |

The resume routes through `agent.core.agent/submit-turn`, which prefers a
host-registered turn-submitter (e.g. the TUI input queue) so the wake
serializes with user turns on the same executor rather than racing them;
headless hosts fall back to `ask-async`. The wakeup turn's input is tagged
`{:source :wakeup :woken-by <task-id>}` and its text names the terminal
status, so the LLM can react to a failure. An already-terminal `:task-id`
resumes immediately; a shared CAS guard makes the inline check and the watch
resume exactly once. Re-parks are bounded per session by
`:max-wakeups-per-session` (default 20) to cap an infinite park→wake→park
chain. The feature is session-scoped — watches live in the running process
and are not persisted across restarts.

### Path validation

For `:bash` runs, `validate-bash-paths` scans the command for absolute
paths and checks each against the agent's allowed dirs *synchronously on
the caller's thread, before any task is created* — so
`proto/*current-agent*` is still bound for permission lookups. If a
`permission-fn` is configured it prompts; otherwise access outside allowed
dirs is denied with guidance to use relative paths.

---

## Persistence

`task/persist.clj` adds a durable per-task disk channel parallel to the
in-memory tail cache, so the LLM can read full status at any iteration
boundary even after the ring buffer evicts old lines.

Layout (project-scoped):

```
<project>/.brainyard/tasks/<task-id>/
  meta.edn     — lifecycle snapshot (status, result, timestamps), rewritten
                 atomically (tmp + rename) at open and on terminal transitions
  output.log   — combined stdout/stderr, one flushed append per line
```

- `open-appender!` truncates `output.log` and writes the initial `meta.edn`
  at `start-task`; `append-line!` is the per-line, lock-serialized writer
  fanned to from the manager's `on-output`; `close-appender!` flushes and
  rewrites terminal `meta.edn` from `finalize-task!`.
- `read-tail` / `line-count` / `read-meta` support cheap last-N reads and
  post-mortem inspection. Disk is the source of truth — `task$detail`
  compares disk `line-count` against the cache size and sets `:truncated?`.
- Job-config / result values that can't roundtrip through EDN (the
  `:agent` defrecord, futures, atoms) are stripped before writing `meta.edn`.
- Counter seeding: `max-existing-task-id` scans `tasks/task-N/` at manager
  startup so freshly-issued ids don't collide with artifacts from a prior
  session (or this JVM's prior `shutdown`, which resets the counter to 0).

### Retention / GC

See [Garbage collection](../gc.md) for the full two-tier reclamation model
(eager cleanup + bounded sweeps) across all artifact classes; the
task-specific summary follows.

The task lifecycle never deletes dirs — `remove-task` leaves artifacts for
post-mortem. Cleanup is the GC layer's job: `ai.brainyard.agent.gc`'s
`sweep-tasks!` / `sweep-coact-scratch!` / `sweep-sandbox-cache!`, surfaced
to the LLM as `task$sweep`. `remove-task-and-artifacts!` is the opt-in
helper that removes the registry row *and* the disk dir at once. Bounds
live in `core.config/config-schema`: `:task-retention-count` (100),
`:task-retention-days` (7), `:coact-scratch-max-age-hours` (24),
`:sandbox-cache-max-files` (200), `:sandbox-cache-max-bytes` (50 MiB),
`:sandbox-cache-max-age-days` (7).

---

## TUI meta-commands

`bases/agent-tui/src/ai/brainyard/agent_tui/commands.clj` binds a single
`/task` slash command with subcommands:

| Slash | Action |
|---|---|
| `/task` or `/task list` | List tasks in a compact, color-coded table. |
| `/task detail <id>` (or bare `/task <id>`) | Detail view. |
| `/task cancel <id>` | Cancel a running task. |
| `/task del <id>` | Remove a task from the registry. |
| `/task log <id> [N]` | Show captured output (optionally last N lines). |
| `/task run <cmd>` | Quick `:bash` task. |
| `/task bg <id>` / `/task fg <id>` | Flip a task's display-mode. |

`task/format.clj` holds the pure formatters behind these: status table,
detail view, output dump, inline/status-bar notifications, and the sticky
task-activity header + output lines.

---

## Scheduling

The `:schedule` key on a `Task` (`{:cron "…"}`) is reserved for future
cron support and is not yet wired into execution — tasks today run once
when `start-task` submits them. `retry-task` carries the original
`:schedule` (and config) forward when re-creating a failed/cancelled task.

For in-session deferral, `task$wakeup`'s timer mode (`:delay-ms`) is the
practical mechanism today: it parks the agent and auto-resumes it after a
delay (see [Blocking vs. parking](#blocking-vs-parking)). It is
session-scoped and not persisted across restarts.

---

## Observability — the `::tool-dispatch` trace

A tool call routes either **synchronously** (block on the caller's thread)
or through the **fast-eval → background-detach** path (run in a future,
inline up to `:fast-eval-ms`, then adopt into a task). The decision is made
in `call-tool-with-fast-eval` (`core/tool.clj`), which emits a dormant
debug-level mulog event — `:ai.brainyard.agent.core.tool/tool-dispatch` —
recording **why** each call routed the way it did:

| Field | Meaning |
|---|---|
| `:tool` | tool name |
| `:branch` | `:sync` or `:fast` (the path actually taken) |
| `:no-fast-eval?` | `:fast-eval-ms` was nil ⇒ sync |
| `:in-task?` | already inside a task context (`proto/in-task-context?`) ⇒ sync |
| `:subagent?` | caller is a sub-agent (its `:!state` has `[:runtime :parent-agent]`) ⇒ sync — a sub-agent closes before it could harvest a detached task, so detach is top-level-only |
| `:sync-meta?` | tool declares `:meta :sync` ⇒ sync |
| `:parent` | the parent agent's id when `:subagent?` is true |
| `:fast-eval-ms` | the inline deref deadline in effect |

`:branch` is `:sync` when any of `:no-fast-eval?` / `:in-task?` /
`:subagent?` / `:sync-meta?` is true, else `:fast`. mulog adds its usual
context (`:agent-id`, `:session-id`, `:turn-id`).

It is **dormant**: zero `alter-var-root` instrumentation, reload-safe (it
lives in source), and only materializes where a mulog publisher is attached.
In the `by` TUI it lands in `~/.brainyard/logs/agent-tui-app.log` and is
queryable via `log$events` (filter `::tool-dispatch`) — the supported way to
debug, in a live session, why a given tool did or didn't detach.

---

## File map

| File | Purpose |
|---|---|
| `task/protocol.clj` | `Task` record, `IJobExecutor`, `ITaskManager`, `still-running` sentinel |
| `task/manager.clj` | `TaskManager` record, global atoms, executor service, detach watcher, `finalize-task!` |
| `task/executor.clj` | `BashJobExecutor`, `ToolJobExecutor`, `CliClientJobExecutor`, `ClojureSandboxJobExecutor`, `NreplEvalJobExecutor` |
| `task/commands.clj` | `defcommand` registrations (`task$list/detail/cancel/run/wait/wakeup/sweep`), `await-task` sync waiter, `task$wakeup` one-shot watch + host-submitter resume, bash path validation |
| `task/persist.clj` | Per-task disk channel: `meta.edn` + `output.log`, tail reads, counter seeding, dir GC |
| `task/format.clj` | Pure formatting for TUI tables, detail views, and the task-activity area |

---

## See also

- [agent.md](agent.md) — sub-agent registry, hooks catalog including
  `:task/created` / `:task/completed`.
- [tool.md](tool.md) — permission plumbing for shell and tool
  invocations; the deadline-based hand-off rule for async refs.
- [reasoning.md](reasoning.md) — how the loops nudge the LLM to
  switch from inline calls to task-based polling.
- [config.md](config.md) — `:task-timeout-ms` and the `:task-retention-*`
  / `:sandbox-cache-*` GC bounds.
- [gc.md](../gc.md) — on-disk artifact garbage collection: sweep classes,
  retention bounds, the session-start hook, and `task$sweep`.
