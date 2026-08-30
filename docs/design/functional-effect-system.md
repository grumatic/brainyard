# Functional effect system (missionary) — design

**Status:** COMPLETE. Phases 0–3 implemented, verified on a native binary, and
merged to `main` (§9–§12). Phase 4 was investigated and **cancelled** — its
premise contradicted this document's own scope (§14). Later sections record
what was built, what the plan got wrong, and why; where they disagree with the
earlier design sections, the later ones are what happened.

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

### 1.2 Cancellation is five mechanisms wearing a trenchcoat *(four, really — §14)*

`core/runtime.clj:118` `cancel-run` does all of:

1. sets `[:runtime :cancelled?]` in an atom (checked cooperatively at BT checkpoints),
2. walks the parent chain so a child sees a parent's cancellation (`cancelled?`, line 146),
3. `.close`s a registered in-flight HTTP stream (`:active-http`),
4. `future-cancel`s a stored future **or** `.interrupt`s a stored thread,
5. signals a `ReentrantLock`/`Condition` to wake a parked pause (`wait-if-paused`, line 215).

Items 1, 2 and 4 look like exactly what missionary gives for free: running a
task returns its canceller, cancellation propagates into every nested `m/?`,
and parents own children by construction. Item 5 looks like `m/dfv` or a
`m/watch` on the pause flag consumed with `m/?<`.

**None of that survived contact (§14).** Missionary cancels *effects*,
propagating through `m/?` parks, and the run has none — it is a thread. Item 4
was also two branches of which one was dead: `run-async` had no production
callers, so every real cancel took the `.interrupt`.

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
  src/ai/brainyard/effect/core/smoke.clj     ; the native gate (§9)
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

### Phase 1 — leaf policy, zero semantic change *(retry: done, §10)*

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

### Phase 2 — the tickers *(done, §11)*

Seven daemon-thread tickers → seven `fx/ticking` tasks under the supervisor.
Purely additive and visually verifiable (the spinner either animates or it does
not), which made it the cheapest way to build confidence in the brick under
real TUI conditions.

Converted the *scheduling*, not the rendering: every tick body is the old loop
body lifted verbatim into a named `…-tick!` fn returning truthy-while-active.
The renderer's width contract (reflow, `:render` fns) is untouched, and so is
the pause-state and session-origin handling the loops carried.

### Phase 3 — the task subsystem *(done, §12)*

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

### Phase 4 — cancellation unification *(NOT DOING — see §14)*

The plan was: `cancel-run`'s five mechanisms collapse to cancelling the effect
tree plus one explicit closer for the blocking socket, and both
`runtime/cancelled?`'s parent-chain walk and `tool.clj`'s cascading subagent
cancel become consequences of structure rather than code.

It does not work, for a reason that was visible in this document all along:
missionary cancels *effects*, propagating through `m/?` parks, and **the run
has no parks** — it is a thread. Getting structural cancellation would mean
rewriting the BT loop as a coroutine, which §2 of this same document rules out
two pages earlier ("Not a rewrite of the BT engine"). §14 has the evidence and
what remains worth doing.

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

**Q3 — ANSWERED, and the question was wrong.** It read: brainyard runs a fixed
4-thread task pool "as a deliberate concurrency bound", `m/blk` is unbounded,
so Phase 3 must re-establish the bound with `fx/bounded` or silently lose it.

Measured, that premise does not hold. **The pool never bounded the detached
executors.** Only `:tool`, `:fn` and `:cli-client` hold a pool thread for the
job's duration — the docstring that phrase comes from is `FnJobExecutor`'s, and
it is accurate about `:fn`. `:bash`, `:sandbox`, `:nrepl` and `:a2a` all
returned `:detached` immediately and ran their work elsewhere. Twelve
concurrent bash tasks:

```
at rest:                  blk=0   task-pool=0
12 concurrent bash tasks: blk=12  task-pool=4   running=12
```

All twelve ran while the pool sat at four. There was no bound on that path to
lose. What Phase 3 changed is **+1 blocked waiter thread per in-flight detached
task, −1 shared watcher thread**.

**And bounding the waiters would be a bug, not a safeguard.** They block on I/O
— a process exiting, a future completing, an HTTP response. Cap them at N with
N+1 tasks in flight and task N+1's *completion cannot be observed* until one of
the first N finishes: a task that ended, with nobody watching. That is the
exact failure mode Phase 3 existed to remove, reintroduced through the back
door. This is why missionary ships two pools rather than one knob — `m/cpu`
fixed at core count because contention is the cost there, `m/blk` cached
because blocking is the point. Waiters belong on `blk`, and `blk` stays
unbounded. The cost is self-limiting: ~1MB of mostly-untouched virtual stack
per blocked thread, reaped after 60s idle, and zero at rest.

**The real question, which this displaces:** should task *admission* be
bounded — how many tasks may run at once? That is a live policy question, and
today the answer is incoherent rather than deliberate:

- `:tool` / `:fn` / `:cli-client` — bounded at 4, by accident of a pool size
- `:bash` / `:sandbox` / `:nrepl` / `:a2a` — unbounded, and always were
- a task queued behind the pool reports `:running` regardless, so the bound is
  **invisible** — twelve `:fn` tasks all showed `:running` while four executed

If a bound is wanted it belongs at `start-task` as explicit admission control
with a config knob, plus a `:queued` status distinct from `:running` so it can
be observed. Not inherited from a thread pool that half the executors never
touch. That is a task-manager decision, independent of the effect migration.

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

## 11. Phase 2 — as built

All seven tickers now read the same way:

```clojure
(defn- iteration-block-tick! []
  (let [active (filterv #(not= :done (:stage (val %))) @!iteration-blocks)]
    (when (seq active)
      (doseq [[[aid rid iter] _] active] …)
      true)))                                   ; truthy => keep ticking

(defn- start-iteration-block-ticker! []
  (fx/ensure! ::iteration-block-ticker (fx/ticking 1000 iteration-block-tick!)))
```

What disappeared at each of the seven sites: a `Thread.`, `setDaemon`,
`setName`, a `when-not @!x-thread` idempotence guard, a `catch
InterruptedException`, a `reset!` to nil on exit, and the `defonce` atom
holding the handle. The tick bodies moved across verbatim.

**Two primitives the conversion earned.** `fx/ensure!` — start under a label
only if nothing is running there — because `start!` means *replace* and these
tickers are called on the event that creates the thing they animate, so
`start!` semantics would cancel and relaunch mid-animation on every new block.
Their hand-rolled `when-not` guard was `ensure!` spelled out. And `fx/delayed`,
because idle-tip is the one ticker that must sleep before its first tick (its
suggestion has just been painted); one such site is not a reason to give
`ticking` a mode, nor to spread `missionary.core` into the TUI.

**`tick!` runs on `m/blk`, deliberately.** After the first park the coroutine
runs on the single process-wide `missionary scheduler`, which every timer
shares — rendering a live block there would let one slow repaint delay every
other ticker, task timeout and LLM backoff. That is Q5's concern, answered by
construction rather than by measurement.

**One semantic preserved on purpose:** task-activity's trailing 2 s
"show the final state, then clear" is still fired *detached*
(`fx/run-detached`) rather than being the tail of the ticker task. Inlining it
would have let `stop-task-activity-ticker!` cancel the finalize, leaving the
block frozen mid-animation — the old code's `stop` cancelled the loop but never
that future.

### Verification

Unit: 543 tests / 2237 assertions in the `agent-tui` base, all green; 16 brick
tests including new coverage for `ticking` (self-stops, paints before its first
sleep, cancellable mid-sleep, a throwing tick fails rather than wedging) and
`ensure!` vs `start!`. Headless lifecycle drive of a real ticker: starts, stays
up while blocks are active, `ensure!` declines to double-start, explicit stop
works, self-stops when the last block goes `:done`, and deregisters.

**Live, in a real TUI under tmux** — the part no test can show. Captured once a
second during a turn:

```
[+] Iteration 1 / 100  (2.0s)      [⠋] Deliberating... (2.9s · Reasoning…)
[+] Iteration 1 / 100  (3.0s)      [⠦] Deliberating... (3.8s)
[+] Iteration 1 / 100  (4.0s)      [⠸] Ruminating...   (4.9s)
[+] Iteration 1 / 100  (5.0s)      [⠋] Reasoning...    (6.0s)
```

The 150 ms think ticker cycles its braille frame and rotates its word; the
1000 ms iteration ticker advances elapsed by exactly 1.0 s per second. The turn
finished, both blocks finalized, the status bar returned to `idle`, and `/quit`
exited without hanging.

**The deletion, measured on two live processes.** A TUI running the unconverted
`main` still has a dedicated `idle-tip-ticker` OS thread alive at idle — that
one runs for the process lifetime by design. The converted TUI has **no ticker
threads at all**; `Thread.print` shows one `missionary scheduler` plus three
pooled `missionary blk-N` workers shared by everything.

Native: `by effect-smoke` green, `--help` / `agents` / `sessions list` /
`models` pass, a real Bedrock round-trip returns `10`. `bb poly check` OK.

## 12. Phase 3 — as built

Converted in five slices, each independently verified, with the legacy poll
path kept alive between them so no commit had to move everything at once:

| slice | what changed | completion latency |
|---|---|---|
| bash | `.waitFor` on `m/blk` replaces polling `.isAlive` | ~400ms → **~32ms** |
| sandbox + nREPL | blocking `deref`; drain split out to `:on-drain` | ~400ms → **~15ms** |
| a2a | `fx/poll-until`; the interval IS the pacing | 3 of 4 watcher ticks were waste |
| adopt-detached! | the fast-eval seam takes an adopt map | — |
| watcher | `start-detach-watcher!`, `poll-detached-once!`, `!watcher-future` and both legacy builders **deleted** | — |

**What deleted:** one daemon thread that woke 3.3×/second for the life of the
process and asked every detached task whether it had finished; a 300ms poll
loop; a 100ms poll loop's reason to exist; and ~90 lines including the two
now-unreachable poll-fn builders.

**What stayed, deliberately:** incremental output is still SAMPLED at 300ms.
Sampling was always the right model for a growing `StringWriter` — using it to
also detect completion was the accident. And `:on-cancel` is still separate
from cancelling the Task: measured, cancelling an `m/via` interrupts the
waiting thread and leaves the process running.

### Rewriting the watcher's tests rather than deleting them

`manager_test.clj` had 24 assertions testing the watcher *itself*. The
mechanism is gone but every invariant it protected still holds, so each was
re-expressed against the effect contract rather than dropped: promotion on
success and on failure, independence across concurrent tasks (now settled
out-of-order, which catches an implementation that assumed registration
order), `:on-cancel` driven exactly once, and — the one that matters most —
**a task can never be stranded at `:running`**.

That last one changed shape. The watcher protected it by finalizing any task
whose `:on-poll` threw. The equivalents now are a Task that throws (`:failed`
with its message) and an executor returning `:detached` with no `:task`, which
fails loudly rather than registering an `:on-cancel` nobody will ever call.
Three tests are new: prompt promotion (asserting a bound the old design could
not meet), the drain being sampled-then-flushed-once and stopping when the task
does, and a cancelled task staying `:cancelled` rather than being re-finalized
`:failed` by its own interrupt.

### Verification

229 tests / 1162 assertions across the task, a2a, coact, eval and exec-backend
suites. `bb poly check`; `bb build:ata` end-to-end with its native smoke suite;
`effect-smoke` green; real native turns through both the bash path (`echo
watcher-gone`) and the sandbox path (`(* 6 7)` → 42).

The proof the watcher is actually gone is a thread dump taken with a live
detached task mid-flight:

```
task status while detached -> :running
threads that could be a completion watcher -> []
missionary threads -> ["missionary blk-0"]
after the process exits -> :completed
```

One thread, blocked in `.waitFor`, which is the work itself.

## 13. Recommendation

Phase 0 is landed and its gate passes on a real native binary, so the largest
architecture risk is retired: missionary works in the shipped artifact, at no
startup cost and +0.8% binary size, with a two-line carve-out whose necessity
has been demonstrated by removal.

Phase 1 is done (§10) — with the caveat recorded there that its return was the
brick defect it flushed out rather than any user-visible change.

Phase 2 is done (§11) and is the first phase with a user-visible payoff: seven
dedicated OS threads and ~140 lines of lifecycle code gone, verified animating
in a real TUI and verified absent from a live thread dump.

Phase 3 is done (§12): the polling runtime is gone and completion latency
dropped from ~400ms to ~15–32ms.

**Q3 turned out to be a false alarm, and the commit that closed Phase 3 says so
incorrectly.** `task: delete the detach watcher…` claims the fixed 4-thread
pool's bound is "weaker than it was". It is not: measured, that pool never
bounded the detached executors at all (§8 Q3). Nothing needs restoring, and
wrapping the waiters in `fx/bounded` would actively break completion detection.
The commit message is immutable; this paragraph is the correction.

What Q3 did surface is a real but separate question — task *admission* is
currently bounded for three executors, unbounded for four, and invisible in
task status either way. Worth deciding deliberately, at `start-task`, as a
task-manager concern rather than an effect-migration one.

**Phase 4 is not the remaining migration — it is cancelled (§14).** The
migration is complete at Phase 3.

Q1 is answered (§8): the coroutine macros cannot work under SCI, effects
exposed as functions work fully, and Phase 3's shape is unchanged. No known
blocker remains for any phase.

Q4 is also answered (§8), and it turned up the sharpest hazard in the whole
investigation: a dynamic var can change value mid-`m/sp`-body at the first
park, silently and timing-dependently. `fx/task-of` now conveys so the
mechanical `future` → Task port is safe, the rule is documented in
`prim/conveying-note`, and the behaviour is pinned by characterization tests.

Remaining open: Q2 (`Cancelled` discipline at flow boundaries) and Q5
(scheduler-thread load). Q3 is closed — the bound it worried about never
existed on the path it worried about, and the waiters must stay unbounded
(§8 Q3). Both remaining questions are things to get right *during* a phase,
not gates on starting one.

The single most valuable phase is 3 (the task subsystem: two polling loops and
~400 ms of latency delete outright). The single most valuable *artifact* is
probably §3.2, the supervisor, which is worth having even standalone.

## 14. Phase 4 — cancelled, and why

Phase 4 promised that `cancel-run`'s mechanisms would "collapse into the effect
tree's canceller". Investigated before writing any code; the premise does not
hold, and the reason is structural rather than a matter of effort.

**Missionary cancels effects. The run is a thread.** `send-ask` hands the BT
loop to a `send-off` pool thread (`runtime.clj`), and it runs synchronously to
completion. Cancellation propagates through `m/?` parks — and there are none:

```
grep for missionary / fx/run / m/sp across runtime.clj, bt.clj, behavior-tree/
  → (none — the run loop is entirely synchronous)
```

Structural cancellation has nothing to attach to. Getting it would mean
rewriting the BT tick loop as a coroutine so every checkpoint became a park —
which **§2 of this document rules out two pages earlier**: "Not a rewrite of the
BT engine. `behavior-tree` is synchronous by design and stays that way." The
plan contradicted its own scope and nobody noticed until it was time to build.

Two further costs, had it gone ahead anyway. Q4's hazard applies at every park,
and the run path binds `*current-agent*`, `*current-task*`,
`*subagent-capture*` and `*attribution*` — each would need lexical-capture
treatment, in the most central code path in the product, against a failure mode
that is silent and timing-dependent. And it would touch pause/resume, the BT
checkpoint contract and subagent lifetime simultaneously, which was already
flagged as "highest risk, therefore last".

### The four mechanisms are irreducible, not accidental

Re-examined one at a time, none is redundant and none is replaceable without
the rewrite:

| mechanism | what it covers | why it stays |
|---|---|---|
| `:cancelled?` flag | the BT checks it every node tick and throws | this is what *stops* the loop; the rest exist to make sure it is reached |
| `.close` on `:active-http` | a thread blocked in a socket read | uninterruptible on the JVM by any mechanism, missionary included |
| `.interrupt` on the run thread | a sleep or blocking take between checkpoints | no effect to cancel — just a thread |
| `signal-pause-condition!` | a thread parked in `wait-if-paused` | waiting on a `Condition`; would otherwise never re-check the flag |

### What was actually wrong: there were four, not five

`cancel-run` documented itself as interrupting "either via `future-cancel`
(run-async path) or direct `Thread.interrupt` (send-ask/clj-agent path)".
**The `run-async` path had no production callers.** `[:runtime :future]` was
set nowhere else, so the `future-cancel` branch was dead and every real cancel
took the interrupt. `run-async`'s only caller in the repository was a test
asserting that a future returns what its thunk returned — a test for code
nobody ran.

Removed: `run-async`, the dead branch, and that test. `cancel-run`'s docstring
now names the four live mechanisms and says why each is irreducible, so the
next person to ask "can't missionary do this?" finds the answer next to the
code instead of re-deriving it.

### What would make Phase 4 possible

Making the agent run an effect — the BT loop as `m/sp`, checkpoints as parks.
That is a redesign of the execution model with its own justification needed
(what does it buy beyond cancellation?), its own Q4 audit, and its own risk
budget. It is not a phase of this migration, and filing it as one is what made
this document promise something it had already excluded.

**The effect migration is complete at Phase 3** — with the caveat that §15
revisits the reason Phase 4 was cancelled and finds it weaker than stated.

## 15. Converting the BT loop to effects — what it would actually take

§14 cancelled Phase 4 on the grounds that it "would mean rewriting the BT
engine", treating that as self-evidently out of scope. Challenged on it, I
measured the engine instead of asserting. **The scope claim was wrong.**

### The engine is ~200 lines and the transformation is mechanical

```
behavior-tree/core/nodes.clj      141   six node types
behavior-tree/core/engine.clj      38   build + run
behavior-tree/interface/protocol   24   tick/build multimethods, 3 status keywords
```

`p/tick` is a multimethod returning `:success` / `:failure` / `:running`. Every
node type has the same shape, and every one converts the same way — wrap the
body in `m/sp`, `m/?` the recursive tick:

```clojure
;; today
(defmethod p/tick :sequence [node context]
  (loop [[child :as children] (:children node)]
    (if-not child p/success
      (case (p/tick child context)
        :success (recur (rest children))
        :failure p/failure
        :running p/running))))

;; as an effect — the diff is `m/sp` and one `m/?`
(defmethod p/tick :sequence [node context]
  (m/sp (loop [[child :as children] (:children node)]
          (if-not child p/success
            (case (m/? (p/tick child context))
              :success (recur (rest children))
              :failure p/failure
              :running p/running)))))
```

That is not a rewrite in any threatening sense. It is six functions.

### `:parallel` is currently broken, and converting it is a bug fix

```clojure
(defmethod p/tick :parallel [{:keys [children]} context]
  (let [futures (mapv #(future (p/tick % context)) children)
        results (mapv deref futures)] …))
```

Unbounded `future` per child, `deref` in order, no cancellation, and a failing
child does not stop its siblings — the same three defects `pmap` had in the
tool-dispatch path, which `fx/bounded` already fixed there (§1.4). `m/join`
gives all three properties by construction. This alone is worth the conversion.

### The cost is not the engine — it is 39 action leaves

39 `:action` nodes, 36 `bt/success`/`bt/failure` returns. Each `action-fn`
returns a status keyword today and would need to return a Task.

**But not all at once.** The `:action` tick can lift a synchronous result,
exactly the coexistence that carried Phases 1–3:

```clojure
(defmethod p/tick :action [{:keys [action-fn opts]} context]
  (let [r (action-fn (assoc context :opts opts))]
    (if (task? r) r (fx/success r))))     ; sync leaves unchanged
```

Only leaves that genuinely block — the LLM call in `dspy_action.clj`, tool
dispatch, code eval — need converting. The other ~35 keep working untouched.

### The real obstacle, and why it is smaller than it looks

Q4: a dynamic var reverts at the first park, silently and timing-dependently.
The run path reads `*current-agent*` in **123 places**, `*current-task*` in 14,
`*call-depth*` in 19. Auditing 123 sites would be the project.

It is not necessary, because **the BT already threads the agent lexically**:
`build-bt` passes `{:st-memory … :agent agent}` and every `tick` receives it.
So the binding can be re-established at the leaf, in one place, immune to
whatever the tree did before it:

```clojure
(defmethod p/tick :action [{:keys [action-fn opts]} {:keys [agent] :as context}]
  (fx/task-of (fn []
                (binding [proto/*current-agent* agent]
                  (action-fn (assoc context :opts opts))))))
```

One binding site rather than 123, because the data was already flowing where it
needed to. `*current-task*` and `*subagent-capture*` are bound inside the tool
path, which is already below a leaf, so they are unaffected.

### What it buys

`cancel-run` collapses from four mechanisms to two: cancel the tree, plus the
`.close` on `:active-http` that no mechanism on the JVM can replace. The
cooperative `:cancelled?` flag, the `.interrupt`, the pause `Condition` and
`check-interrupt-cancel-pause!`'s three-way checkpoint all become consequences
of structure. `runtime/cancelled?`'s parent-chain walk and `tool.clj`'s
hand-wired cascading subagent cancel likewise.

### What it costs, honestly

- `bt/run` returns a Task; `run-bt` and its ~4 callers change shape.
- **`:running` needs a decision.** Today it is a synchronous third status. Under
  effects, "still going" and "not yet settled" are different ideas and the BT's
  `:running` must not be conflated with an unsettled Task.
- Pause stays cooperative. Parking a coroutine on user input is a different
  design question from cancellation, and `m/dfv` is a fit but not a free one.
- Every converted leaf needs the Q4 discipline, verified rather than assumed.

### Recommendation

This is worth doing and I was wrong to close it as out-of-scope. It is also not
a phase of *this* migration — it changes the agent execution model, and it
should be justified on its own terms (cancellation correctness plus the
`:parallel` fix) with its own risk budget.

The cheap first step is a spike, not a plan. **It was run — §16.**

## 16. The spike — run, and what it found

Branch `spike/bt-as-effects`. A `p/tick-task` multimethod alongside an
untouched `p/tick`, both dispatching on the same built tree, plus a test that
runs identical trees through each and compares. Implementation:
`behavior-tree/core/nodes_task.clj` (~160 lines, six node types); test:
`nodes_task_spike_test.clj` (12 tests / 55 assertions).

**Both questions answered yes, and one new obstacle found that reasoning had
missed.**

### The translation is real

12 tests / 55 assertions, passing first run. Sequence, fallback, condition,
parallel, nesting, st-memory mutation and empty-children edge cases all agree
between the two engines. Short-circuiting is asserted on *evaluation order*,
not just return value — a translation that ran every child would still return
`:failure` and look correct.

The existing suites are untouched: behavior-tree 69 tests / 171 assertions,
agent BT + examples 10 / 52.

### Q1 — `:running` survives

It settles the Task immediately, carrying `:running` as its value; it is not
conflated with "the Task has not settled yet". Asserted for a bare leaf and
propagating through sequence, fallback and nesting, plus a timing assertion
(< 1s) that a `:running` tree completes rather than hanging.

### Q2 — Q4 really does reduce to one site

The control reproduces the hazard: with the binding established *around the
run* and a leaf that genuinely parks, the reading leaf sees `:nobody`. With
`:leaf-wrap` in the context, it sees `:agent-7`. One site, as predicted.

One correction to the §15 sketch: the engine **cannot** bind `*current-agent*`
itself, because `behavior-tree` sits below `agent` and cannot see
`agent.core.protocol`. Inverting that dependency to reach one var would be far
worse than the problem. So the context carries a `:leaf-wrap` fn and the agent
supplies `(fn [thunk] (binding [proto/*current-agent* agent] (thunk)))`. The
engine stays agnostic about what a leaf needs in scope, which is better than
the original plan.

### `:parallel` — confirmed, with a precision the sketch lacked

A throwing child now aborts the fan-out: the slow sibling is cancelled and
never completes. But **`:failure` does not cancel siblings**, because it is a
value rather than an error — the threshold arithmetic is untouched and a
`:failure` sibling still runs to completion. Both are asserted. §15 said
"`m/join` fixes all three defects" without distinguishing these, which would
have been a silent semantic change if anyone had implemented it from that
description.

### THE FINDING: the engine surface is double what §15 counted

§15 measured `core.nodes` at 141 lines / six methods and called that the
engine. It is not the engine production runs. **`agent.core.bt` overrides five
of the six node types** — `:sequence`, `:fallback`, `:condition`, `:action`,
`:repeat` — and only `:parallel` falls through to `core.nodes`.

```
core.nodes      7 defmethods / 141 lines   mostly shadowed in production
agent.core.bt   5 defmethods / ~203 lines  ← what the real tree runs
```

So the surface is ~11 methods and ~340 lines, not six and ~200. And the agent's
five are the harder ones: they carry depth tracking, `update-session-data`
tracing on entry and exit, debug values, and `check-interrupt-cancel-pause!`.

This cuts both ways. It roughly doubles the work — but those overrides exist
largely to *thread cancellation and pause through a synchronous engine*, which
is the thing the conversion removes. `check-interrupt-cancel-pause!`'s
three-way checkpoint has no counterpart in the effect version; cancellation is
structural and the call sites simply go. So the second half of the surface is
where the deletions are, not just more translation.

### Revised estimate

Not a day. Roughly: the six base methods (done, ~160 lines), the five agent
overrides (~200 lines, with the checkpoint logic coming *out*), converting the
genuinely-blocking leaves (the LLM action, tool dispatch, code eval — the other
~35 lift unchanged), `bt/run` returning a Task through `run-bt` and its ~4
callers, and the `:leaf-wrap` wiring. Days rather than hours, on the most
central path in the product.

**Status: the spike says yes.** The translation preserves semantics, `:running`
survives, Q4 reduces to one site, and `:parallel` gets strictly better. What it
does not say is that the migration is cheap — the production engine is twice
the size §15 assumed, and it is the half with the tracing in it. Worth doing;
worth scoping honestly first.

### Second pass — the five agent overrides, converted

Done. `agent.core.bt` now carries `tick-task` methods for all five, alongside
the untouched `tick` ones: 9 tests / 35 assertions, passing.

**Traces are compared, not just results.** These overrides exist *for* their
tracing, depth threading, hooks and st-memory writes; a translation returning
the right status while emitting different trace lines would have broken the TUI
and passed a result-only test. Every case asserts trace equality between the
two engines — sequence, fallback, condition, action, repeat (first-pass
success, child-failure stop, and exhaustion), three-deep nesting for depth
threading, and `:last-failure` landing in st-memory.

**The checkpoint shrank, which is the payoff made concrete.**
`check-interrupt-cancel-pause!` had three jobs; the task engine's `check-pause!`
has one:

| check | fate |
|---|---|
| `(Thread/interrupted)` | **gone** — no thread to interrupt; the tree is a value |
| `check-run-cancelled?` | **gone** — cancellation is structural |
| pause → `await-resume` | **stays** — a wait for a human, not an effect |

Asserted directly: cancelling the tree's Task stops it mid-run *with
`:cancelled?` never set*. The cooperative flag that §14 called "what actually
stops the loop" is not consulted at all on this path.

Pause is the honest exception. It does not reduce the same way, because a
paused turn is waiting on a person rather than on an effect, so it stays a park
on the Condition. Turning it into an `m/dfv` is a separate question this spike
does not answer.

Regression: agent BT + examples + coact + eval + task + manager, 178 tests /
930 assertions; behavior-tree 20 / 66; `bb poly check`; `bb build:ata`
end-to-end with its native smoke suite; `effect-smoke`; and a real agent turn on
the native binary. Production still runs the synchronous ticks — nothing calls
`run-task`.

### Third pass — `run-bt-task`, and item 1 turns out to be unnecessary

Step 2 is done: `abt/run-bt-task` returns a Task, `runtime/set-bt-canceller!`
registers it, and `cancel-run` cancels it. 4 tests / 10 assertions.

**`cancel-run` now stops a run with the cooperative flag never consulted.** The
test's mock *counts* calls to `check-run-cancelled?` and asserts zero. That is
the claim §14 said could not be reached without a rewrite, demonstrated.

`run-bt-task` installs `:leaf-wrap` itself, so `*current-agent*` survives a park
with no caller involvement — item 3, absorbed.

**Item 1 — converting the blocking leaves — is NOT required, and measuring it
was the surprise of this pass.** A tree whose leaves are plain blocking
functions returning keywords still cancels correctly:

```
[:action (fn [_] (Thread/sleep 4000) :success)]   ; no Task, no m/sleep
  → cancelled, settles as InterruptedException
  → the leaf never finished; the node after it never ran
```

Every leaf already runs through `leaf-task` → `fx/task-of` → `m/via m/blk`, so
cancelling the tree interrupts the leaf's thread whether or not the leaf knows
anything about effects. **Leaf conversion buys composition — a timeout or retry
around the LLM call, expressed as a value — not cancellation.** §16's "what is
left" list had it first and it belongs last, if at all.

One caveat it does not fix: a leaf blocked in a *socket read* is still
uninterruptible, which is why `.close` on `:active-http` survives every version
of this design (§14, mechanism 2).

### Fourth pass — wired in, behind a flag

Done. `agent.core.agent/ask` branches on **`:enable-effect-bt`** (env
`BY_ENABLE_EFFECT_BT`, default **false**). Off, the synchronous engine runs
exactly as before. On, the turn is a Task: `run-bt-task` hands back a canceller,
`set-bt-canceller!` registers it, and `cancel-run` uses it.

`run!!` blocks the calling thread exactly as `run-bt` did, so the turn's shape
and everything downstream reading `:answer` out of st-memory are untouched.
What changes is only that someone now holds a handle on the running turn. The
canceller is cleared in a `finally` — a stale one would fail to interrupt the
thread on the next cancel, which is the failure that matters.

A cancelled turn arrives as `InterruptedException` and is re-thrown as
`ex-info "Cancelled"`, matching what the synchronous checkpoint threw, so the
caller-visible shape is identical on both paths.

**Verified against a live LLM, both engines:**

| check | sync | effect |
|---|---|---|
| `17 × 3` | `51` | `51` |
| shell + code eval in one turn | — | `effect-engine-ok`, `42` |
| TUI: Ctrl-C mid-turn | cancels | **cancels** |
| TUI: next turn after cancel | works | **works** |
| clean `/quit`, no orphans | yes | yes |

254 tests / 1261 assertions with the flag off (the default), `bb poly check`,
`bb build:ata` end-to-end with its native smoke suite, `effect-smoke`.

### What is actually left

1. **Flip the default**, once it has run on real work for a while. The flag is
   the way back; that is the whole reason it exists.
2. **Then delete mechanisms 1 and 3** from `cancel-run`. They are dead on the
   effect path but still live on the synchronous one, so they cannot go until
   the sync engine does.
3. **Decide about pause.** Still a park on a `Condition`, still the honest
   exception, since a paused turn waits on a person rather than an effect.
4. **Optionally, the leaves** — for composition, not cancellation (third pass).

`cancel-run` ends at two mechanisms rather than four, and the two that remain —
`.close` on a blocked socket, and the pause signal — are the two that were
never reducible. Phase 4 was cancelled in §14 as impossible without a rewrite;
it turned out to be four passes, a ~200-line engine that was really ~340, and
one config flag.

## 17. Two follow-ups: HTTP cancellation, and pause as a dataflow variable

Both raised after §16 landed. Investigated, not implemented.

### `.close` on `:active-http` is only *half* irreducible

Every version of this design has called mechanism 2 unreducible on any
platform. That is true for the case that matters and false in general, and the
distinction is worth writing down.

`clj-http-native/core/client.clj:231` uses `**.send**` — the blocking call. The
calling thread sits inside `HttpClient.send`, which no interrupt reaches, so
closing the response stream from outside is the only exit. Hence
`set-active-http!` at `agent.clj:826`.

`HttpClient` also has `sendAsync`, returning a `CompletableFuture` — which
implements `Future`, so `fx/from-future` adopts it directly — and on JDK 11+
`.cancel(true)` genuinely aborts the exchange. So:

| call shape | cancellable structurally? |
|---|---|
| non-streaming (`ofString`) | **yes** — `sendAsync` + cancel the Task |
| streaming (`ofInputStream`) | **no** — see below |

Streaming is the case brainyard actually cares about, and it does not reduce.
`sendAsync` completes when *headers* arrive; the body is then read from an
`InputStream` on the consuming thread. Cancelling the future after that does
nothing to a read already in progress, because the JDK has handed the body over.
The SSE path blocks in `InputStream.read`, and closing the stream remains the
only way out.

So the accurate claim is: **`.close` survives because of streaming bodies, not
because HTTP is inherently uncancellable.** Converting the non-streaming path
would be real but modest — it removes the closer from ordinary calls and leaves
it exactly where the LLM stream is.

### Pause holding a pool thread — FIXED, see below

*(Originally written as an open finding; implemented in the same pass.)*

### Pause holding a pool thread is a defect §16 introduced

`check-pause!` calls `await-resume` → `wait-if-paused` → `.await` on a
`Condition`. Under the synchronous engine that parked the `send-off` thread,
which is that thread's job. **Under the effect engine it parks an `m/blk` pool
thread**, and `m/blk` is shared with every task waiter in the process. A turn
paused for minutes holds a pooled thread for minutes.

Thread *count* is unchanged, so nothing breaks — but the kind of thread is
worse, and §16 called pause "the honest exception" without noticing it had
made pause more expensive rather than merely unconverted.

`m/dfv` is the fit. A dataflow variable is precisely "a value that will arrive
later": `resume-run` delivers it, `(m/? dfv)` parks the *coroutine* and holds
no thread at all. That is strictly better than the Condition on the effect
path, and it retires mechanism 4.

The reason it is not a trivial swap: pause is driven from outside the effect
world (a TUI keystroke), the dfv must be allocated per pause and replaced on
resume, and `cancel-run` must settle it so a cancelled-while-paused turn
unwinds — which is what `signal-pause-condition!` does today. The semantics are
already worked out in `wait-if-paused`'s three outcomes; they just need
re-expressing.

### Pause as a dfv — done

`runtime/await-resume-task` returns a Task with `wait-if-paused`'s three
outcomes, parking on an `m/dfv` instead of a `Condition`. `pause-run` allocates
a fresh dfv (they deliver once), `resume-run` settles it `:resumed`,
`cancel-run` settles it `:cancelled`; both settles are guarded, because the two
can race and the loser must be a no-op rather than a throw.

Measured — the point of the exercise:

```
still waiting?                     true
blk threads consumed by the wait   0      <- was 1, for the whole pause
resume delivers                    :resumed
cancel while paused                :cancelled
not paused / already cancelled     :running / :cancelled   (ordering preserved)
three pause/resume cycles          [:resumed :resumed :resumed]
```

One consequence worth knowing: `check-pause!` now returns a **Task**, because
`m/?` on a dfv is only valid inside a coroutine, and its three call sites await
it. The first attempt left it a plain `defn` and failed with
`No matching clause: :resumed` — `m/?` outside a coroutine compiles to a park
that has no fiber to park on. A good error to have seen once.

The synchronous engine is untouched: it still calls `.await-resume` and the
`Condition` remains for it. Only the effect path uses the dfv.

Verified: 216 tests / 1156 assertions, `bb poly check`, `bb build:ata` with
native smoke, `effect-smoke`, and live in a TUI on the effect engine — Esc
parks, Ctrl-C while paused cancels, the next turn answers, `/quit` exits with
no orphans.

### The effect engine is now the default

`:enable-effect-bt` defaults to **true**. `BY_ENABLE_EFFECT_BT=false` falls back
to the synchronous engine, which remains fully supported and fully tested.

**Flipping it caught a defect the test suites did not.** `bb build:ata`'s native
smoke suite failed the ACP round trip with `Error: Cancelled`. The cause was in
the `ask` wiring: it reported EVERY failure as "Cancelled", not just
cancellation. A genuine ACP error surfaced under a cancellation message, hiding
its actual cause. The synchronous engine propagates the original exception, so
the effect path must too — only `InterruptedException` and `missionary.Cancelled`
now become "Cancelled", everything else is rethrown as itself.

Worth noting how it was found. 216 unit assertions, a five-turn nREPL-driven TUI
session, and repeated `by ask` runs all passed with the bug present, because
none of them made a turn fail for a non-cancellation reason. The build's own
smoke suite did it on the first run after the flip. Verified after the fix: a
bad model id now reports `Bedrock invoke failed: The provided model identifier
is invalid` rather than `Cancelled`.

### Where cancel-run ends up

Three mechanisms now, not four: the cooperative flag and the thread interrupt
are unused on the effect path, and mechanism 4 is a dfv rather than a Condition.
Converting the non-streaming HTTP path would take it to **two** — cancel the
effect, plus `.close` for streaming bodies only, which is where it always
belonged. That is close to the shape §14 said was unreachable.

## 18. Streaming HTTP *is* reducible — the pull API was the problem

§17 concluded that `.close` on `:active-http` survives "because of streaming
bodies". Challenged on it, and wrong again — for a reason worth stating exactly,
because it is the same mistake twice.

**The irreducibility came from choosing a blocking-pull body handler, not from
HTTP streaming.** `BodyHandlers/ofInputStream` hands back an `InputStream`, and
a thread blocked in `read` cannot be interrupted — so of course only `.close`
gets you out. That is a property of the API selected, not of the protocol.

`java.net.http` also offers a **push** handler, and missionary already speaks
the protocol it pushes into. Everything needed is on the classpath today:

```
HttpResponse$BodyHandlers/ofPublisher   → JDK Flow.Publisher<List<ByteBuffer>>   ✓ exists
org.reactivestreams.FlowAdapters        → JDK Flow ⇄ reactive-streams            ✓ on cp
missionary m/subscribe [pub]            → reactive-streams Publisher → Flow      ✓ exists
missionary m/publisher [f]              → the reverse direction                  ✓ exists
```

`reactive-streams` arrives as a transitive dependency of missionary itself — it
was in the dependency list from the very first `add-lib` in the tutorial. No new
dependency is required.

### The chain

```
(sendAsync req (BodyHandlers/ofPublisher))     ; CompletableFuture<HttpResponse<Flow.Publisher<…>>>
  → fx/from-future                             ; CompletableFuture implements Future
  → FlowAdapters/toPublisher                   ; JDK Flow → reactive-streams
  → m/subscribe                                ; → a missionary Flow of List<ByteBuffer>
  → SSE framing as a flow transformation       ; m/eduction over the byte chunks
```

Cancelling that Flow cancels the subscription; the JDK propagates
`Subscription.cancel()` and aborts the exchange. **That is structural
cancellation of a streaming body** — the case every previous section called
impossible.

### Two things it buys beyond cancellation

- **Backpressure, for free.** `m/subscribe` yields a discrete flow driven by
  `request(n)`. The current SSE reader consumes as fast as the socket delivers
  and pushes into `on-chunk`; a slow consumer has no way to signal upstream.
  §1 listed "backpressure by construction" as a reason to adopt missionary and
  this is the first place in brainyard it would actually apply.
- **The last mechanism goes.** With this and the non-streaming `sendAsync`
  change, `cancel-run` reaches **one** mechanism: cancel the effect.
  `:active-http`, `set-active-http!`, `clear-active-http!` and the
  `with-active-stream*` registration all delete.

### Why this is not done here

It rewrites the SSE path — `clj-llm/core/sse.clj` plus every provider's
streaming branch — which is the highest-traffic, highest-risk code in the
product, and it is a genuine redesign rather than a translation: chunk framing,
`[DONE]` handling, provider-specific event shapes and the `on-chunk` callback
contract all have to be re-expressed as flow transformations.

It deserves its own session and its own differential test (same SSE bytes
through both paths, asserting identical chunk sequences), exactly as §16 did
for the BT.

### The pattern, recorded

Three times now a mechanism was called irreducible and turned out to be a
consequence of an API choice:

| claim | what was actually true |
|---|---|
| "the BT can't be effects without a rewrite" | ~340 lines, mechanically translatable (§15, §16) |
| "pause must park a thread" | `m/dfv` parks the coroutine (§17) |
| "streaming HTTP can't be cancelled" | only `ofInputStream` can't; `ofPublisher` can (§18) |

The common shape: an existing implementation choice was mistaken for a
constraint. Worth remembering the next time this document says something is
impossible.


### Slice 3 attempted — cancellation over a real socket WORKS; completion hangs

Written, run against a loopback `HttpServer`, and **backed out** rather than
committed. `main` stays at slices 1–2 (165 tests / 820 assertions green). The
code is preserved outside the tree; the findings are here because they are the
valuable part.

`request-event-flow` is four lines: `sendAsync` with `ofPublisher`,
`fx/from-future` on the returned `CompletableFuture`, then slice 2's
`publisher->event-flow` over `.body`.

**What passed — the claim this whole section exists for.** Cancelling the Flow
mid-stream aborted a real HTTP exchange. The test server records a failed write
as `:client-gone?`, and it saw the connection drop after the cancel, having
written only part of the stream. **Nothing called `.close`.** That is
`:active-http`'s job done structurally, over a socket, verified.

**What failed — my composition, not the mechanism.** Both *completion* tests
timed out. Events arrive correctly (`{"a":1}`, `{"b":2}` matched
`read-sse-events` exactly), so framing and transport are fine over a real
socket; the Flow simply never terminates when the body ends normally. Cancelling
settles it, ending does not — which is precisely the signature of the outer
`m/ap` not completing when the inner forked flow does.

The suspect is the `m/?>` fork in `request-event-flow`: one response value
forked into an inner flow, where the outer `ap` needs to terminate with the
inner. Either a different combinator, or the transducer needs to signal
termination on `[DONE]` rather than merely going quiet — `sse-events-xf` sets
`!done` and stops emitting, but never `reduced?`-terminates the reduction, so
`m/reduce` keeps pulling from a publisher that has stopped producing.

That second possibility is worth checking first, because it would be a **slice 1
bug that slices 1 and 2 could not detect**: with a `StringReader` and a fake
publisher, the upstream always ends on its own and papers over a transducer that
never terminates by itself.

**Checked, and it was NOT the cause.** The transducer bug was real — `[DONE]`
went quiet without returning `reduced`, fixed and pinned by two infinite-input
tests — but restoring slice 3 on top of that fix leaves both completion tests
failing exactly as before. One hypothesis eliminated, one left.

The remaining suspect is now the only one: **the `m/?>` fork in
`request-event-flow`**, where the outer `m/ap` must terminate when its inner
flow does. The decisive evidence is that
`server-closing-early-completes-the-flow` contains **no `[DONE]` at all** — it
relies purely on EOF, so its termination cannot involve the transducer's
`[DONE]` handling in any way, and it still hangs. Whatever is wrong is in the
composition, not the framing.

**Also checked, and also NOT the cause.** The composition was isolated with no
HTTP at all, five ways — plain inner flow, `ap` + `?>`, `ap` + `?` task + `?>`,
the same with `fx/from-future` over a completed `CompletableFuture`, and again
with an `m/eduction` in between. **All five terminate correctly.** `m/ap` +
`m/?` + `m/?>` is sound.

So both named suspects are eliminated, and the remaining difference is narrow
and specific:

| layer | terminates? | evidence |
|---|---|---|
| `m/ap` + `m/?` + `m/?>` composition | **yes** | isolated probe, 5 variants |
| `m/subscribe` + `FlowAdapters` + `onComplete` | **yes** | slice 2, fake publisher |
| the transducer at `[DONE]` / EOF | **yes** | slice 1 + the `reduced` fix |
| the same chain over a REAL JDK body publisher | **no** | slice 3 |

Every part works in isolation; only the real `ofPublisher` body fails to
terminate. That is now the whole question, and it is a JDK-behaviour question
rather than a missionary one. Things to look at, in order: whether
`com.sun.net.httpserver` with a chunked body (`sendResponseHeaders … 0`)
actually completes the publisher on close; whether HTTP/2 negotiation against an
HTTP/1.1-only test server leaves the body open; and whether `ofPublisher`
requires the subscription to be taken up more promptly than the flow does.

A better next probe than any of those: subscribe to the JDK body publisher
**directly**, with a hand-written `Flow.Subscriber` that prints `onNext` /
`onComplete` / `onError`, and see whether `onComplete` ever arrives. If it does
not, nothing above missionary can help; if it does, the bridge is the place to
look. That is ten lines and settles it.
