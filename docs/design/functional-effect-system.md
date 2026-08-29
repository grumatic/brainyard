# Functional effect system (missionary) — design

**Status:** Phase 0 implemented and verified on a native binary (§10). Phases
1–4 designed, not started. Branch `feat/functional-effect`.

**Thesis.** Brainyard has, over time, hand-rolled a small effect system out of
`future` + `promise` + `Thread/sleep` + polling loops + a cooperative
cancellation flag. It works, and every piece of it is individually justified —
but the *composition* rules were never written down, so each new async surface
re-derives them and gets a slightly different answer. Missionary supplies those
rules as a library: an effect is a **value**, composition is by **function**,
and cancellation is **structural**. This document proposes adopting missionary's
`Task` (one value) and `Flow` (many values over time) as brainyard's effect
representation, and maps the migration.

---

## 1. What brainyard hand-rolls today

Counted across `components/`, `bases/`, `projects/`, excluding `test/`:

| idiom | sites | what it stands in for |
|---|---:|---|
| `(future …)` | 74 | an effect that has already started |
| `deref fut ms ::timeout` | 18 | `m/timeout` |
| `future-cancel` | 28 | cancellation (best-effort, interrupt-based) |
| `Thread/sleep` | 45 | `m/sleep`, and polling intervals |
| `(promise)` + `deref p ms`| 13 | `m/dfv` + `m/timeout` |
| `Thread.` + `.setDaemon true` | 27 | a supervised long-lived process |
| `pmap` | 11 | bounded parallel fan-out |
| `core.async` `alts!!` | 6 | `m/?<` / priority select |

These are not evenly distributed. They cluster in six places, and those six are
the actual subject of this design.

### 1.1 The task subsystem is a polling runtime

`components/agent/task/executor.clj` defines five job executors. Every one has
the same shape: start a `future`, return `{:status :detached :on-poll … :on-cancel …}`,
and let someone else notice when it finished. That someone is
`manager/start-detach-watcher!` (`task/manager.clj:413`) — one global daemon
thread that wakes every 300 ms and calls `.isDone` on every registered handler.
Above it, `commands/await-task` (`task/commands.clj:293`) runs a *second* polling
loop at 100 ms against `@!tasks`.

So a task's completion travels: future completes → 300 ms poll notices →
`finalize-task!` swaps an atom → 100 ms poll notices the atom → caller returns.
Up to 400 ms of latency and two dedicated loops, to express "this finished."

A `Task` already *is* the completion callback. `m/join`, `m/timeout` and
`m/race` compose it directly. The two polling loops are not an optimization of
that — they are a re-implementation of it with worse latency.

Note what the polling design *is* good at, because the replacement must keep it:
`:on-poll` also drains incremental stdout (`drain-incremental-output!`) and
emits liveness heartbeats. That is genuinely a Flow — a `StringWriter` sampled
over time — and it should stay a Flow. The mistake is only that *completion* is
also expressed as polling.

### 1.2 Cancellation is five mechanisms wearing a trenchcoat

`core/runtime.clj:118` `cancel-run` does all of:

1. sets `[:runtime :cancelled?]` in an atom (checked cooperatively at BT checkpoints),
2. walks the parent chain so a child sees a parent's cancellation (`cancelled?`, line 146),
3. `.close`s a registered in-flight HTTP stream (`:active-http`),
4. `future-cancel`s a stored future **or** `.interrupt`s a stored thread,
5. signals a `ReentrantLock`/`Condition` to wake a parked pause (`wait-if-paused`, line 215).

Items 1, 2 and 4 are exactly what missionary gives for free: running a task
returns its canceller, cancellation propagates into every nested `m/?`, and
parents own children by construction. Item 5 is `m/dfv` or a `m/watch` on the
pause flag consumed with `m/?<`.

**Item 3 does not go away, and the design must say so plainly.** A thread
blocked in `InputStream.read` is not interruptible on the JVM by any mechanism,
missionary included. `mcp/client.clj:354` already documents this ("`future-cancel`
can't unpark a blocked `readLine`"). What changes is *where the closer lives*:
today it is a global `[:runtime :active-http]` slot on agent state; under
missionary it is the canceller returned by the task that owns the socket, which
is the only scope that can be correct.

### 1.3 Five hand-rolled tickers in the TUI

`bases/agent-tui/.../session.clj` has `!think-ticker-thread` (line 510),
`!iteration-ticker-thread`, `!task-ticker-thread`, `!subagents-ticker-thread`,
`!idle-tip-ticker-thread` — each a `Thread.` + `setDaemon` + `loop` +
`Thread/sleep` + "self-stops when the block map is empty" + an idempotent
`when-not @!ticker-thread` start guard + a `reset!` to nil on exit.

That guard/self-stop/nil-out dance is start-stop lifecycle code, written five
times. As a Flow it is one expression per ticker and no lifecycle code at all,
because the *consumer* holds the canceller.

### 1.4 `pmap` for parallel tool dispatch

`common/coact_agent.clj:2944` dispatches the LLM's tool calls with
`(doall (pmap …))`. `pmap` is the wrong tool three ways here: its parallelism is
`availableProcessors + 2` and not configurable; it chunks in 32s; and it has no
cancellation and no failure short-circuit. The block-detection immediately after
(`(first (filter hook-blocked-result? results))`) shows the cost — a hook that
blocks dispatch is discovered only *after* every other tool has run to
completion. `m/join` cancels its siblings on the first failure; `m/?=` with an
explicit bound gives configurable parallelism.

### 1.5 The serialized ask queue

`core/queue.clj` is a mailbox and a worker. Its two most interesting comments
are both about the worker *dying*: `run-processing-loop!` has a per-item
`Throwable` guard plus an outer `finally` that nils `:worker-future`, and
`ensure-worker!` respawns on `future-done?` — because "a single unexpected throw
would kill the future while leaving `:worker-future` pointing at the dead
future, permanently wedging the queue."

That entire failure mode exists because the worker is a *started process* whose
death is invisible. A mailbox (`m/mbx`) consumed by a supervised `m/sp` loop has
no such state: failure is a value returned to the supervisor, which decides.

### 1.6 Retry / backoff / timeout, written per-layer

`clj-llm/core/llm.clj:241` `retry-with-backoff` is ~50 lines of loop/recur with
jitter, `retry-after` honouring, and a `*on-retry*` dynamic var so the layer
below the agent can report a wait it is about to take. `permissions.clj` has
five `(promise)` + `(deref p timeout :timeout)` pairs. `mcp/client.clj` has two
`deref … ::timeout` + `future-cancel` pairs. Each is correct; none composes with
the others.

---

## 2. What is actually being bought

Not "async". Brainyard's async works. Three specific things:

**Effects become values, so they become testable and re-runnable.** `(m/sp …)`
does nothing until run. A retry policy, a timeout, a fan-out becomes a value you
can pass, wrap and assert on without executing it. Today `retry-with-backoff`
can only be tested by actually failing HTTP calls.

**Cancellation becomes structural instead of cooperative.** The parent-chain
walk in `runtime/cancelled?`, the `:cancelled?` flag checked at BT checkpoints,
the cascading subagent cancel hand-wired in `tool.clj:1373` — all of it is
brainyard implementing "parents own children" by hand. That is the one thing
missionary is *for*.

**Composition becomes associative.** `timeout`, `retry`, `race`, `join`,
`debounce` nest arbitrarily. Today `await-task`'s three `:on-timeout` modes
(`:kill` / `:detach` / `:snapshot`) are an enum precisely because timeout policy
could not be expressed as a wrapper.

### What is explicitly NOT being bought

- **Not speed.** Missionary is not faster than a `future`. The 400 ms detach
  latency goes away, which is a real UX win, but nothing else gets faster.
- **Not a rewrite of the BT engine.** `behavior-tree` is synchronous by design
  and stays that way.
- **Not core.async's removal.** `memory/capture/sidecar.clj` and the Bedrock
  stream reader (`clj-llm/core/bedrock.clj:405`) use channels well. They get a
  *bridge*, not a rewrite (§6).

---

## 3. Architecture: a new `effect` component

Missionary is not used directly from application code. One Polylith brick owns
it:

```
components/effect/
  src/ai/brainyard/effect/interface.clj      ; the only public surface
  src/ai/brainyard/effect/core/prim.clj      ; run!!, from-future, from-promise
  src/ai/brainyard/effect/core/policy.clj    ; timeout, retry, bounded, race
  src/ai/brainyard/effect/core/flows.clj     ; ticker, sample-lines, watch, debounce
  src/ai/brainyard/effect/core/supervisor.clj; the process registry (§3.2)
  src/ai/brainyard/effect/core/smoke.clj     ; the native gate (§10)
```

A `bridge.clj` for core.async is **deferred to Phase 3**, when there is a
consumer. The blocker is mechanical: the backpressure-preserving `chan->flow`
adapter needs `a/alts!` inside `a/go` — both macros — so it cannot be written
against a `requiring-resolve` soft dependency, and making `effect` depend on
core.async would drag a channel library underneath every future consumer of a
brick whose whole point is to be minimal. Phase 6's rule (§6) means the bridge
belongs next to the code that owns the channel anyway.

Depends on `mulog` + `util` only. Sits at the same tier as `behavior-tree` —
below `agent` and below `clj-llm`, both of which are consumers.

Three reasons it is a brick and not a bare `(:require [missionary.core :as m])`
in each namespace:

1. **The native-image carve-out has to live somewhere.** §7 is a hard build
   constraint that must not be rediscovered by whoever adds the second call
   site.
2. **Executor policy is a decision, not a per-call-site preference.** `m/blk`
   vs `m/cpu` vs "inline on the caller" is the single most common way to get
   missionary wrong (the tutorial's case 4 — `m/join` of two `Thread/sleep`s
   serializes without `m/via m/blk`). One brick, one answer.
3. **Migration stays incremental.** A brick that nobody requires costs nothing.
   Phase 0 can land, be verified against a native build, and sit there.

### 3.1 The interface, sketched

```clojure
;; --- running ---
(run!!    task)               ; block, return {:ok v} | {:err e}   — test/CLI seam
(run      task success fail)  ; returns canceller — the raw contract
(run-supervised task label)   ; registers with the supervisor (§3.2)

;; --- policy combinators (task -> task) ---
(timeout ms task)             ; m/timeout
(retry-backoff opts task)     ; replaces llm/retry-with-backoff
(bounded n tasks)             ; parallel fan-out with an explicit bound
(race & tasks)

;; --- bridges (§6) ---
(from-future  fut)            ; adopt an already-started future
(from-promise p)
(chan->flow   ch)             ; backpressured; take-task + m/ap
(flow->chan   flow buf)
(watch->flow  atom path)      ; m/watch on an atom's derived value

;; --- shapes brainyard needs repeatedly ---
(ticker ms)                   ; continuous flow of ticks; consumer holds cancel
(sample-lines writer)         ; StringWriter -> flow of complete lines
(mailbox)                     ; m/mbx + a supervised consumer loop
```

`run!!` deliberately returns `{:ok}`/`{:err}` rather than throwing, matching the
tutorial's runner and brainyard's existing `{:error …}` result convention.

### 3.2 The supervisor is the part that pays for itself first

27 `.setDaemon true` sites means 27 processes whose only shutdown story is "the
JVM is a daemon-thread graveyard, and `tp/shutdown` hopefully found the ones
that matter." The CLAUDE.md note on `create-task-manager` says this out loud:
daemon-ness "does NOT kill subprocesses a task spawned via ProcessBuilder … An
app's exit path MUST call `tp/shutdown`."

`run-supervised` puts every long-lived process's canceller in one registry keyed
by label. Shutdown becomes one call that cancels the tree, and `by`'s exit path
stops depending on each subsystem having remembered to register its own hook.
This is worth having even if no other phase ever lands.

---

## 4. Concept mapping

| brainyard concept | today | effect value |
|---|---|---|
| a job executor | `future` + `:on-poll` + `:on-cancel` | **Task** |
| task stdout | `StringWriter` + 300 ms drain | **Flow** of lines (discrete) |
| liveness heartbeat | `future` + `Thread/sleep` loop | `ticker` **Flow**, or dropped (the Task's completion is the signal) |
| `await-task :detach` | poll `@!tasks` at 100 ms | `(timeout ms task)` |
| `await-task :kill` | poll, then `cancel-task` | `(timeout ms task)` + canceller |
| fast-eval → adopt | `deref fut ms ::timeout` then re-wrap | `(timeout fast-ms task)`, same Task adopted |
| tool-call fan-out | `pmap` | `(bounded n tasks)` |
| LLM retry | `retry-with-backoff` loop | `(retry-backoff opts task)` |
| TUI ticker | daemon `Thread` + self-stop guard | `ticker` Flow, cancelled by consumer |
| permission prompt | `promise` + `deref timeout` | `m/dfv` + `timeout` |
| ask queue | atom + self-healing worker future | `mailbox` + supervised `m/sp` loop |
| scheduler tick | daemon thread + `Thread/sleep` | `ticker` Flow + `m/?>` |
| pause/park | `ReentrantLock` + `Condition` | `m/watch` on the pause flag + `m/?<` |
| `cancel-run` | 5 mechanisms | the tree's canceller + one explicit resource closer |
| memory capture | core.async `thread` + `alts!!` | **unchanged** (§6) |

---

## 5. Migration phases

Each phase is independently shippable and independently revertable. Nothing
after Phase 0 is committed to until Phase 0's gate passes.

### Phase 0 — the gate (no production code changes)

Add the `effect` brick, add `missionary/missionary {:mvn/version "b.44"}`, add
the native-image carve-outs from §7, and add a **native-binary** smoke test:
`m/sleep` fires, `m/join` is actually parallel, a canceller actually cancels.

This is a gate, not a formality. §7 is a verified hard constraint, and if it
cannot be satisfied under `--strict-image-heap` the correct outcome is to stop
here, having spent one afternoon. **Do not begin Phase 1 before a native binary
has run a missionary task.** JVM-mode success proves nothing about this risk.

### Phase 1 — leaf policy, zero semantic change *(retry: done, §11)*

`clj-llm/retry-with-backoff` now delegates to `effect/retry-backoff`. The three
call sites and the public contract are untouched.

**The `permissions.clj` half was dropped, deliberately.** Reviewing it, the
`promise` + `deref timeout` pattern there is already correct: the wait is
bounded, `!pending-feedback` is reset and the block hidden immediately after,
the stdin reader is interrupted, and a `feedback-lock` means only one prompt
runs at a time. Swapping in `m/dfv` would require changing the five `deliver`
sites in the readline editor too — a cross-cutting change to interactive input
handling — for no behavioural gain. Effects buy composition and cancellation;
that code needs neither.

### Phase 2 — the tickers

Five daemon-thread tickers → five Flows, plus one shared clock. Purely additive
and visually verifiable (the spinner either animates or it does not), which
makes it the cheapest way to build confidence in the new brick under real TUI
conditions — including under tmux and `--web`.

Watch for: the tickers are entangled with pause state (`think-root-paused?`
pins elapsed time via `:paused-at`) and with session-origin routing
(`finalize-think-block-in-session!`). Convert the *scheduling*, not the
rendering. The renderer's width contract (`docs` — reflow, `:render` fns) is
untouched.

### Phase 3 — the task subsystem

The real prize. `tp/IJobExecutor.execute-job` returns a **Task** instead of
`{:status :detached :on-poll …}`. Consequences:

- `start-detach-watcher!` and its 300 ms loop **delete**.
- `poll-detached-once!` and `!detached-handlers` **delete**.
- `await-task` becomes `(timeout ms task)`; the three `:on-timeout` modes become
  three wrappers rather than an enum.
- `finalize-task!` keeps existing — it is the atom/hook/persist transition, not
  concurrency — but is now called from the task's completion, not from a poll.
- Completion latency drops from up to 400 ms to ~0.

Incremental stdout stays a Flow and keeps its ~300 ms sampling, because sampling
is the *right* model for a `StringWriter` — that part was never the problem.

The fast-eval/detach seam (`tool.clj:1486`) gets structurally simpler: today the
future is started, `deref`'d with a timeout, and then *re-wrapped* into a task
by `adopt-tool-into-task` with a reconstructed poll fn. With a Task, "wait 5 s
then keep waiting in the background" is the same value under two different
timeouts. `adopt-detached!` largely dissolves.

Do `:fn` and `:tool` executors first (in-process, no proc tree), `:bash` last
(process-tree teardown in `destroy-process-tree!` is subtle and worth leaving
undisturbed while the surrounding machinery moves).

**Audit every `binding` on the way through — this is where Q4 bites.** The
`:tool` executor's `(binding [proto/*current-task* …] …)` and
`call-tool-with-fast-eval`'s `(binding [proto/*current-task* !task-ref
proto/*subagent-capture* !sub-capture] …)` must end up INSIDE the segment that
calls the tool, not wrapped around a task that parks. The failure mode is
silent (progress attribution stops; no error), and it is timing-dependent, so
a green test run is not evidence. `fx/task-of` conveys, which makes the
mechanical `future` → Task port safe; `m/sp` bodies do not, and no library
change can make them.

### Phase 4 — cancellation unification

`cancel-run`'s five mechanisms collapse to: cancel the tree, plus one explicit
closer for the blocking socket. `runtime/cancelled?`'s parent-chain walk and
`tool.clj`'s hand-wired cascading subagent cancel both become consequences of
structure rather than code.

Highest value, highest risk, therefore last. It touches pause/resume, the BT
checkpoint contract, and subagent lifetime simultaneously.

---

## 6. What stays on core.async, and why

`core.async` is already an `agent` component dependency (`components/agent/deps.edn`)
and is used in three places that should **not** move:

- `memory/capture/sidecar.clj` — a priority `alts!!` over two channels with a
  FIFO barrier sentinel for `quiesce!`. This is CSP used precisely for what CSP
  is good at: decoupling two producers from one consumer with a priority rule.
  Missionary's answer would be less clear, not more.
- `memory/capture/dispatcher.clj` — the channels themselves.
- `clj-llm/core/bedrock.clj:405` — the AWS SDK *hands us* a core.async channel.
  Not our choice to make.

The bridge is `effect/chan->flow` (backpressure-preserving `take-task` + `m/ap`,
per the tutorial's verified adapter — the `m/observe` variant is push-based and
needs a sentinel + `m/buffer` + `take-while` to terminate, so it is the fallback
for genuinely push-shaped sources only). Bedrock's stream becomes a Flow at the
boundary; the sidecar keeps its channels and gains nothing it needs.

Rule of thumb, from the tutorial's own comparison: **channel where producers and
consumers must be decoupled and fanned in; effect value where the work is
composed, cancelled or retried as a unit.**

---

## 7. The native-image constraint (verified, hard)

This was checked against the actual artifacts, not assumed.

`missionary-b.44.jar` ships precompiled Java classes for `missionary.impl.*` —
good news generally (no reflection, nothing for `reflect-config.json`). But two
of them hold live concurrency in static fields:

```
missionary.impl.Sleep        static final Sleep$Scheduler S
missionary.impl.Sleep$Scheduler extends java.lang.Thread
  Scheduler() { ... setDaemon(true); start(); }        // <-- starts in the ctor
missionary.impl.Thunk        static final Executor cpu, blk
missionary.impl.Thunk$Blk extends java.lang.Thread     // thread-pool factory
```

`Sleep`'s static initializer constructs `Sleep$Scheduler`, whose **constructor
calls `.start()`**. So class-initializing `missionary.impl.Sleep` starts a
thread.

Now the build config. `native-image.properties` uses
`--features=clj_easy.graal_build_time.InitClojureClasses`, which (verified by
reading `clj_easy/graal_build_time/packages.clj` out of the jar) scans the
application classpath for `**/__init.class` entries and registers their
**packages** for build-time initialization. Missionary's jar ships no
`__init.class` — but `bb compile:ata` AOT-compiles transitively, and
`target/classes` already contains `__init.class` files for third-party deps
(`nrepl/*__init.class` are there today). So `missionary/core__init.class` would
be emitted, package `missionary` would be registered, and — because the feature
drops sub-packages covered by a parent — `missionary.impl` with it.

**Net: without a carve-out, the build initializes `missionary.impl.Sleep` at
build time and tries to snapshot a running `java.lang.Thread` into the image
heap.** Under GraalVM 25's `--strict-image-heap` that is a build failure naming
the class, which is the *good* outcome — loud, not a binary where `m/sleep`
silently never fires.

**Verified carve-out** (now live in `native-image.properties`):

```
--initialize-at-run-time=missionary.impl.Sleep \
--initialize-at-run-time=missionary.impl.Thunk \
```

Every part of the above was confirmed against real builds, and two predictions
in the first draft of this document were wrong. Recording both, because the
corrections are the useful part:

**The mechanism fires exactly as described.** The build log's "Registering
packages for build time initialization" line lists `missionary` and
`cloroutine`, and `missionary/core__init.class` is present in the uberjar.

**Sleep fails LOUDLY** — removing its line and rebuilding:

```
Fatal error: Detected a started Thread in the image heap.
Thread name: missionary scheduler.
  scanning root missionary.impl.Sleep$Scheduler@…:
    Thread[#52,missionary scheduler,5,InnocuousForkJoinWorkerThreadGroup]
```

**Thunk fails SILENTLY** — removing its line and rebuilding produces a green
build *and a fully green `by effect-smoke`*, because the gate machine and the
build machine had the same core count. What would ship is a cpu pool sized by
the build machine. There is no signal for this; the flag is the signal. A
future maintainer trimming flags "because the build is still green" would
remove exactly this one.

**`missionary.core__init` is NOT needed — the draft was wrong.** The reasoning
looked sound (`(def blk Thunk/blk)` reads a deferred class's static field at
namespace-init time, so `core__init` should have to follow it to run time), and
the build disproves it: green build, green gate without it. GraalVM propagates
the run-time decision to the reading class itself. It was dropped rather than
kept as insurance, so this list stays a set of load-bearing facts — a
decorative flag in a file like this one teaches the next reader the wrong model.

**No extra `missionary.impl.*` classes were needed.** The draft predicted one or
two more (`Reactor`, `Pub`) would surface. None did.

**Startup-time corollary, measured.** These classes now initialize lazily, so
nothing on `by`'s startup path may touch a missionary var — the first `m/sleep`
starts the scheduler thread. The concern was real (this repo treats startup as
first-class; the terminal-caps DECRQM probe was called out at "~500 ms, roughly
4x its entire startup") but the cost is nil: `by agents` measures 0.22 s with
and without missionary on the require path. Binary size +1.77 MB (+0.8%).

---

## 8. Open questions

**Q1 — ANSWERED. The coroutine macros do not survive SCI; the fallback does,
and it is better than expected.** Probed in bare SCI and in a real
`clj-sandbox` sandbox at `:restricted` interop.

`m/sp` / `m/ap` fail, and the failure is an unbounded **regress**, not a
missing require:

```
copy-ns missionary.core        -> Could not resolve symbol: cloroutine.core/cr
+ copy-ns cloroutine.core      -> Could not resolve symbol: cloroutine.impl/safe
```

Each copy exposes the next private helper the *generated state machine*
references, and `cloroutine.impl` is 34 KB of them. Chasing it to the end would
not help anyway: `cloroutine.impl.analyze-clj` analyzes with
`clojure.tools.analyzer.jvm` against `clojure.lang.Compiler$LocalBinding`, and
`cr` is handed `&env` — which under SCI holds SCI's own binding
representations, not `Compiler$LocalBinding`. The CPS transform is written
against the JVM compiler's environment; SCI does not have one. Treat this as
closed.

**It does not matter, because the sandbox never needed the macros.** Effects
exposed as ordinary *functions* work completely. Verified inside a real
sandbox:

| sandboxed expression | result |
|---|---|
| `(fx-run (fx-task (fn [] (reduce + (range 100)))))` | `{:ok 4950}` |
| `(fx-run (fx-join vector (fx-task slow) (fx-task slow)))` | `{:ok [:done :done]}`, **316 ms vs 607 ms serial** |
| `(fx-run (fx-timeout (fx-task …) 150 :fell-back))` | `{:ok :fell-back}` |
| `(fx-run (fx-bounded 3 …))` | `{:ok [0 1 2 3 4 5]}` — order preserved |
| `(fx-run (fx-race (fx-sleep 50 :fast) (fx-sleep 2000 :slow)))` | `{:ok :fast}` |
| `(fx-run (fx-reduce + (fx-seed (range 10))))` | `{:ok 45}` |

The load-bearing piece is `fx-task` — `(fn [f] (m/via m/blk (f)))` — which
turns a plain thunk into a Task on the host side. `m/sp` exists to let you
write sequential code *containing* parks; sandboxed code does not need to park,
it needs to hand back composable work. A thunk does that. Everything else
(`join`, `timeout`, `race`, `bounded`, `reduce`, `seed`) is already a function
in missionary and crosses the boundary untouched.

Two supporting results: a **host-built** `m/sp` Task called from SCI works
(`{:ok 42}`) — macroexpansion happens on the host, SCI only invokes the
resulting function — and the sandbox's own `eval-code :timeout-ms` still
cancels an effect correctly (`:timeout` at 806 ms on an 800 ms budget), so the
existing eval-level safety net is not bypassed by handing the sandbox effect
values.

(`System/currentTimeMillis` is unavailable to sandboxed code, which is
`:restricted` interop denying `System` **by design**, not an effect-system
limitation. Timings above are host-measured for that reason.)

**Consequence for Phase 3: unchanged and de-risked.** The host side — executors,
`await-task`, the detach watcher — is ordinary AOT-compiled Clojure and uses
`m/sp` freely. The sandbox gets a function-shaped binding set. The fallback the
draft feared ("only the host side becomes a Task") is not a fallback at all; it
is the correct architecture, and the sandbox gets *more* than the draft
expected.

**Q2 — `missionary.Cancelled` discipline at every boundary.** The tutorial's own
D4 attempt failed exactly this way: an unabsorbed `Cancelled` from a
switched-away branch fails the whole flow. Brainyard has many places where a
cancelled branch must be *dropped*, not propagated (a superseded ticker frame, a
debounced keystroke). The `effect` brick should provide the
`(catch missionary.Cancelled _ (m/amb))` idiom as a named combinator rather than
leaving 20 call sites to remember it. Note `missionary.Cancelled` is a Java
class — interop, not an `m/`-namespaced var — which also means it needs checking
against `reflect-config.json` expectations.

**Q3 — thread-pool accounting.** Brainyard currently runs a *fixed 4-thread*
task pool (`create-task-manager`) as a deliberate concurrency bound: "the pool
thread is held for the job's duration — which is what makes the fixed pool an
actual concurrency bound." Missionary's `m/blk` is unbounded-ish by design.
Moving executors to Tasks without an explicit `(bounded n …)` would silently
remove a bound the system relies on. Phase 3 must make the bound explicit rather
than inherit it from a pool.

**Q4 — ANSWERED, and it is the sharpest hazard found so far.** Bindings do not
convey, which was expected; the unexpected part is that a dynamic var can
change value **mid-body**, and whether it does is **timing-dependent**.

`m/via` is `(via-call exec #(do body))` → `Thunk/run`, which invokes the thunk
on a pool thread with no `binding-conveyor-fn`. So a bare `m/via` sees ROOT
bindings while `future` and `pmap` both convey. That much is a straightforward
porting hazard.

The real problem is inside `m/sp`:

```clojure
(binding [*tag* :outer]
  (run!! (m/sp (let [before *tag*]
                 (m/? (m/sleep 10))
                 [before *tag*]))))
;; => [:outer :root]      thread names: ["main" "missionary scheduler"]
```

Same lexical block, two different values. A park releases the thread and the
coroutine resumes on whichever thread completed the awaited task, carrying that
thread's root frame. And parking on an **already-completed** task resumes
synchronously, yielding `[:outer :outer]` — so identical code is
binding-stable or not depending on whether the inner task happened to be done.
That is the shape of bug that passes against a fast test double and fails
against a real LLM call.

Measured on brainyard's actual pattern:

| shape | `*current-task*` seen by the tool |
|---|---|
| today: `(future (binding [...] (call-tool …)))` | `:task-42` |
| naive port: `(binding [...] (run (m/sp (m/? …) (call-tool …))))` | **`nil`** |
| correct: `(m/? (m/via m/blk (binding [...] (call-tool …))))` | `:task-42` |

The naive port fails **silently** — `append-task-output!` no-ops on an unknown
task, so subagent progress attribution would just stop, with no error anywhere.

What does not work: `with-bindings` around the `m/sp` *value*. It wraps
construction of the Task, not its execution, and yields `[:root :root]`.

**What landed as a result.** `fx/task-of` now conveys the caller's frame,
captured at construction exactly as `future` captures at creation — because
this brick is what those `future` call sites migrate to, and the least
surprising default is the one that makes the port safe. There is deliberately
**no general `(conveying bindings task)` wrapper**: a wrapper only sees a
Task's callbacks and canceller, so it cannot reach inside an arbitrary Task's
body; wrapping the callbacks would install bindings for the *continuation*,
which is not what anyone means by conveyance. Only a constructor that owns its
body can honestly convey, so only `task-of` does. The reasoning, the measured
hazard and the rule live in `prim/conveying-note`, and the behaviour — including
the mid-body revert and its synchronous-completion exception — is pinned by
characterization tests, so if missionary ever changes the note is flagged as
stale rather than quietly becoming wrong.

**The rule: never read a dynamic var after a park.** Capture it lexically
before the first `m/?`; where a callee must see the var, bind it inside the
segment that calls it — which is what the code does today anyway.

**Q5 — is `m/sleep`'s single scheduler thread enough?** One
`missionary scheduler` thread services every timer in the process. Brainyard
would put ~5 TUI tickers, the scheduler tick, task timeouts and LLM retries on
it. Almost certainly fine — timers only enqueue — but worth measuring under a
loaded session rather than assuming.

---

## 9. Phase 0 — as built

Landed: `components/effect` (interface + prim/policy/flows/supervisor/smoke),
`missionary b.44`, the §7 carve-out, `effect` added to the project deps and to
`workspace.edn`'s `:necessary` list, and a hidden `by effect-smoke` entry point.

**`effect` is statically required by `main.clj`, not `requiring-resolve`d.**
This looks like a needless coupling for a brick nothing else uses yet, and it
is load-bearing: AOT reachability is what causes `missionary/core__init.class`
to be emitted, and that emission is the entire mechanism §7 defuses. A lazily
resolved brick would produce a binary where `by effect-smoke` fails for the
boring reason (class stripped — the same failure the `cognitect.aws` force-
include block in `main.clj` exists to prevent) while proving nothing about the
interesting one.

**`effect-smoke` is deliberately not in `known-subcommands`**, so it is absent
from `--help` and from the did-you-mean suggestions. It is a build gate, not a
user feature.

Gate results on the native binary (`by effect-smoke`, all PASS):

| check | evidence |
|---|---|
| `m/sleep` fires | 205 ms for a 200 ms sleep — the scheduler thread is alive at runtime |
| `m/blk` pool is parallel | two 300 ms blocking sleeps joined in 306 ms, not ~600 |
| `m/cpu` pool works | both branches computed; `availableProcessors=14` reported |
| cancellation propagates | canceller on a nested park yields `missionary.Cancelled` |
| timeout + fallback | 5 s task, 200 ms timeout → fallback value |
| retry-backoff | fails twice, succeeds on 3rd; `on-retry` fired for attempts 1 and 2 |
| bounded fan-out keeps order | reversed completion order, `[0 1 2 3 4 5]` out |
| flow is a reusable value | same ticker consumed twice, `[0 1 2 3]` both times |
| supervisor start/stop | ticks, stops, and is quiet afterwards |

Also verified: `bb poly check` OK; the 11 brick tests / 34 assertions pass; the
documented binary smoke tests (`--help`, `agents`, `sessions list`, `models`)
still pass.

**Q1 (missionary inside SCI) is now answered** — see §8. The coroutine macros
do not survive SCI and never will; effects exposed as functions work fully,
including real parallelism, inside a real sandbox at `:restricted` interop.
Phase 3 is unblocked.

**One unrelated thing surfaced:** `bb build:ata` fails at `reflect:check` on a
NEW reflection site in `bases/agent-tui/.../autocomplete.clj:944`
(`(.getPath f)` where `links/resolve-file` returns an unhinted `File`). It is
pre-existing on `main`, unrelated to this work, and was worked around here by
running `bb uberjar:ata` + `bb native:ata` directly. It wants either a
`^java.io.File` hint or `bb reflect:baseline`, by whoever owns the clickable-
links work.

## 10. Phase 1 — as built

`retry-with-backoff` keeps its name, signature and privacy; the ~50-line
`loop`/`recur` becomes a call to `effect/retry-backoff` with three policy
functions carrying the rules that were previously inline:

- `llm-retryable?` — `Exception`, never `Throwable`. The old loop got this for
  free by catching `ExceptionInfo`/`Exception` and letting `Error` propagate;
  the effect layer's `attempt` reifies `Throwable`, so it had to become
  explicit. A `StackOverflowError` is not a transient network condition.
- `llm-max-retries` — the throttling-429-gets-three-extra-attempts rule, via
  `:max-retries-fn`.
- `parse-retry-after` — reused as `:retry-after-ms`, a floor on the delay.

**Two things changed behaviourally, both stated rather than discovered later:**

- The backoff sleep is now `m/sleep`, which is **cancellable**. `Thread/sleep`
  was only interruptible.
- `f` runs on `m/blk` rather than the calling thread, so an in-flight call
  occupies a blk thread while the caller blocks in `run!!` — one extra
  I/O-blocked thread per LLM call. `cancel-run` is unaffected: it aborts a live
  stream by `.close`ing the registered reader, which is thread-independent, and
  its `.interrupt` now unparks the waiter instead of a socket read that was
  never interruptible anyway.

**`fx/task-of`, not a bare task — this is load-bearing.** `*on-retry*`,
`*attribution*` and `*active-stream-register*` are all installed by callers
above this fn. A task that did not convey would lose them (§8 Q4).

### The bug this phase found in the effect brick

Pre-flighting the port against Q4 turned up a defect in `retry-backoff`
itself: it invoked `:on-retry` from inside the coroutine **after** an
`m/sleep` park, so the callback ran on the scheduler thread with a root frame.
Measured before the fix:

```
*on-retry* visible inside the callback     [:listener-installed nil nil]
```

Since `clj-llm` installs `*on-retry*` exactly that way (`with-retry-listener*`),
the symptom would have been a TUI that reports the first retry and then goes
quiet while the retries keep happening — the silent-failure shape §8 Q4 warns
about, reproduced in the brick's own code within a day of writing it. Fixed by
capturing the caller's frame at construction and invoking the callback under
it; pinned by a test. The task is deliberately *not* run under that frame — it
belongs to the caller, who opts into conveyance with `task-of`.

### Verification

Unit, against the real private fn: transient 500 retries then succeeds;
non-retryable 400 makes exactly one attempt; a `StackOverflowError` makes
exactly one attempt; a throttling 429 makes 7 (1 + 3 + 3 extra); an
exhausted-quota 429 makes exactly 1; `retry-after: 1` floors the delay
(3008 ms over 4 attempts); and `*on-retry*` fires for attempts `[1 2 3]`
through the public `with-retry-listener*` seam — the assertion that would have
failed before the fix.

Native binary: `by effect-smoke` green; a **real Bedrock round-trip** returns
`4`; JVM-mode parity (`BY_JAR=1`) returns `6`, so no reflection-config gap.

The luckiest check was unplanned: an OpenAI call hit a genuinely exhausted
quota and returned `quota/credits exhausted (HTTP 429)` in **1.45 s**. That is
the hardest branch in the whole function — the one whose bug was "a definitive
first answer turned into ~90 s of backoff" — verified against a live provider
in the shipped artifact.

`bb poly check` OK. The clj-llm suite matches the unmodified tree exactly
(144 tests / 699 assertions; the 5 errors are a soft-dep classpath artifact of
running the brick outside Polylith, present identically on `main`).

### What Phase 1 did not deliver, and why it matters

Honest accounting: this port deletes ~50 lines and makes the policy a tested
value, but the call sites are synchronous and stay synchronous, so **no
user-visible behaviour improves yet**. The cancellable backoff only pays once a
caller holds the canceller, which is Phase 3. In hindsight the TUI tickers
(Phase 2) were the higher-value first migration — they are already async
processes, so converting them *deletes* lifecycle code rather than reshaping a
synchronous call path, and they touch nothing on the LLM path. Phase 1's real
return was the brick defect it flushed out.

## 11. Recommendation

Phase 0 is landed and its gate passes on a real native binary, so the largest
architecture risk is retired: missionary works in the shipped artifact, at no
startup cost and +0.8% binary size, with a two-line carve-out whose necessity
has been demonstrated by removal.

Phase 1 is done (§10) — with the caveat recorded there that its return was the
brick defect it flushed out rather than any user-visible change.

**Phase 2 (the TUI tickers) is the next step, and is where the deletions
start.** There are seven, not the five this document first counted:
think-block, iteration-block, task-activity, task-block, subagents, idle-tip
and acp-block. Each carries the same ~20 lines of hand-rolled lifecycle — a
`Thread.` + `setDaemon` + `when-not @!x` start guard + a self-stop check + a
`reset!` to nil on exit — that a Flow plus `fx/start!`/`fx/stop!` removes
outright. They are already async, so nothing on a synchronous path is
reshaped; they touch nothing on the LLM path; and correctness is visible (the
spinner animates or it does not). Budget for driving a real TUI under tmux to
verify, and watch the two entanglements: pause state (`think-root-paused?`
pins elapsed via `:paused-at`) and session-origin routing
(`finalize-think-block-in-session!`). Convert the scheduling, not the
rendering.

Q1 is answered (§8): the coroutine macros cannot work under SCI, effects
exposed as functions work fully, and Phase 3's shape is unchanged. No known
blocker remains for any phase.

Q4 is also answered (§8), and it turned up the sharpest hazard in the whole
investigation: a dynamic var can change value mid-`m/sp`-body at the first
park, silently and timing-dependently. `fx/task-of` now conveys so the
mechanical `future` → Task port is safe, the rule is documented in
`prim/conveying-note`, and the behaviour is pinned by characterization tests.

Remaining open: Q2 (`Cancelled` discipline at flow boundaries), Q3 (the
concurrency bound `create-task-manager` currently gets for free from its fixed
4-thread pool, which Phase 3 must make explicit rather than inherit), and Q5
(scheduler-thread load). All are things to get right *during* a phase, not
gates on starting one.

The single most valuable phase is 3 (the task subsystem: two polling loops and
~400 ms of latency delete outright). The single most valuable *artifact* is
probably §3.2, the supervisor, which is worth having even standalone.
