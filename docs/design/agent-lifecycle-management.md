# Agent lifecycle management — persistent subagents + LLM-facing instance control

> How a parent agent dispatches a subagent, why that subagent is auto-closed the
> moment it answers, and what it takes to keep it alive so the parent can inquire
> further on a later turn. Introduces an opt-in **persistent subagent** lifecycle
> and lifecycle verbs in the existing `agent-registry$*` family that let the LLM
> list, inspect, resume, and close the live instances in the agent registry.

## 0. Status

**Implemented (all five phases of §11).** §1–§3 describe the pre-existing seams
the work builds on; §5–§10 are now shipped:

- ✅ **Phase 1 — lifetime fork.** `:lifecycle` map stamped on `@!state` at
  `create-agent`; `:keep-alive?` auto-injected into every `defagent`'s
  `:input-schema` (`tool/keep-alive-schema-entry` + the `defagent` macro);
  threaded through `do-call-tool--agent` and `run-agent` (both dispatch paths)
  to force auto-close off and stamp `:persistent`. Per-ask bookkeeping
  (`:last-ask-at`/`:answers`/`:last-question`) in `ask`.
- ✅ **Phase 2 — read surface.** `agent-registry$list` extended with
  `:mode`/`:owner`/`:idle-ms`/`:answers`/`:last-question`; new
  `agent-registry$detail`.
- ✅ **Phase 3 — resume.** `agent-core/resume-agent` + `agent-registry$resume`
  (ownership + busy + depth guards; reuses the instance's `:previous-turns`).
- ✅ **Phase 4 — close + TUI parity.** `agent-core/close-instance!` (shared
  running-guard) + `agent-registry$close`; `/agent status` shows a Lifecycle
  line (mode/owner); `lifecycle` exported on the `agent` interface.
- ✅ **Phase 5 — GC.** Parent-close cascade via the `:agent.instance/closed`
  hook; `reap-idle-agents!` + `agent-registry$sweep`; per-session cap with
  `:fallback`/`:evict-lru` policy in `do-call-tool--agent`; three config keys
  (§10); idle-reap wired into the session-edge GC sweep; `::persistent-agent-*`
  mulog events.

The guiding constraint held: **the default is byte-for-byte the current
behavior** (fresh instance per call, auto-closed on answer). Persistence is
opt-in; `:keep-alive?` flows as an open-map key so functionality does not depend
on recompiling every agent — the schema injection only adds LLM advertisement +
string→boolean coercion.

## 1. As-is — the agent registry

Every live agent lives in one flat atom, keyed by a globally-unique instance-id
of the form `:<defagent-type>/<suffix>` (e.g. `:exec-agent/crimson-parrot-42`):

- `!agent-registry` — `components/agent/src/ai/brainyard/agent/core/agent.clj:55`.
- `register-agent` / `unregister-agent` / `get-agent` / `list-agents` /
  `list-agents-for-session` — `agent.clj:73-102`.
- `generate-instance-id` mints the suffix via `util/gen-random-words`
  (`agent.clj:66`); the defagent-type is always recoverable via
  `(namespace agent-id)` / `proto/defagent-type`.

The registry does **not** carry `user-id` / `session-id` — those live on the
shared `!session` atom (`agent.clj:26-28`). `list-agents-for-session` filters the
flat registry by `proto/session-id`, which is what the TUI's per-session instance
list is built on.

`get-or-create-agent` (`agent.clj:739`) is **idempotent by id**: given an
instance-id already in the registry it returns the *existing* record (with its
accumulated `!state` / `!session`), only creating+starting a fresh one on a miss
(double-checked lock). This is the single most important fact for the proposal:
**addressing a live instance by id already reuses its state** — the machinery to
resume a subagent is latent in the code; what kills it is the auto-close, not the
lookup.

The user's phrasing "`!agent-instances`" refers to this `!agent-registry`.

## 2. As-is — subagent dispatch is a tool call, and it auto-closes

Subagents are `:agent`-type tools. When the LLM emits `(exec-agent {…})` (or a
JSON tool call), dispatch lands in `do-call-tool--agent`
(`components/agent/src/ai/brainyard/agent/core/tool.clj:264`):

1. **Kill switch** — `enable-subagent-calls` (config, `config.clj:237`).
2. **Depth limit** — `proto/*call-depth*` vs `:max-agent-call-depth`.
3. **Circular detection** — target type must not already be in
   `proto/*call-chain*` (`protocol.clj:55-65`).
4. On pass, it mints a **fresh** instance-id and invokes the tool with
   `:auto-close? (some? agent)` — i.e. **auto-close defaults to true whenever a
   parent agent is present** (`tool.clj:302-319`).

That flag rides into `run-agent` (`agent.clj:923`), the function every defagent
registers as its tool body. With a `:parent-agent` set, `run-agent` takes the
**synchronous** branch:

```clojure
(try (ask ag question)
     (finally (when auto-close? (close-agent-quietly! ag))))   ; agent.clj:956-959
```

`.close` (`agent.clj:409`) stops the run, **unregisters the instance from
`!agent-registry`**, releases the sandbox, and stops the memory-capture pipeline
if it was the last sibling. So the instance is gone from the registry before the
tool result even returns to the parent's BT loop.

**Consequence (the thing the user is pointing at):** a subagent is a one-shot
function call. Its conversation, its scratch state, its loaded skills, its
sandbox — all discarded the instant it answers. If the parent wants to ask a
follow-up ("you explored the auth module — now check the token refresh path"),
it can only dispatch a *brand-new* `exec-agent/<new-suffix>` that starts from
zero and re-derives everything. There is no "same subagent, next turn."

### 2b. The detach / task path (interaction to preserve)

A subagent call that runs long is adopted into a background **task** by the
fast-eval/detach machinery (`tool.clj:1150-1208`). `proto/*subagent-capture*`
(`protocol.clj:67`) CAS-records the dispatched instance-id so the adopted task's
`on-cancel` can `runtime/cancel-run` it and cascade cancellation down the chain.
The LLM then polls that task via `task$detail` / `task$wait` (`task/commands.clj`).

This is orthogonal to lifetime: detach is about *where the work runs* (inline vs
background thread); lifecycle management is about *whether the instance survives
the answer*. The proposal must not break the capture/cascade contract — a
persistent subagent that was detached still needs its task-side cancel to reach
the (now surviving) instance. §7 keeps them layered.

## 3. As-is — the TUI already manages instances (for humans)

`/agent status|new|switch|close|trace` (`bases/agent-tui/src/ai/brainyard/agent_tui/commands.clj:1246`)
is a **human-facing** lifecycle surface over the same registry:

- `/agent new` — create an instance of a type.
- `/agent switch <#|id>` — re-focus an existing live instance (`commands.clj:1163`).
- `/agent close <#|id>` — `.close` a specific instance, with guards: refuses to
  close the last instance, refuses to close a `:running` one, swaps focus to a
  sibling when closing the current (`commands.clj:1182-1234`).

Two things to carry into the design: (a) the TUI already treats "live instances
in this session" as a first-class, navigable set — the LLM-facing tools are the
same concept exposed to the model, over the same registry; (b) the TUI's
close-guards (no-close-while-running, focus hand-off) are exactly the guards the
tool surface needs, so they should be **extracted and shared**, not
reimplemented.

## 4. Goals & non-goals

**Goals**

1. A parent agent can **keep a subagent alive** past its answer and **resume it**
   on a later turn, with full state (conversation, scratch, skills, sandbox)
   intact.
2. Expose the registry to the LLM by extending the `agent-registry$*` family: **list** live
   subagents, **inspect** one, **resume** (follow-up ask) one, **close** one —
   modeled on the `task$*` family so the mental model transfers.
3. Bound the new lifetime so persistent subagents cannot leak: parent-close
   cascade, idle reaping, a live-instance cap, and retention parity with tasks.
4. Backward-compatible: **default lifetime is unchanged** (fresh + auto-close).

**Non-goals**

- No cross-session subagent persistence (a subagent belongs to one
  agent-session, like today; the registry is process-local). Durable, resumable
  *sessions* are a separate concern owned by `session-channel-extensions.md`.
- No change to depth/circular/kill-switch guards — persistence is a lifetime
  knob, not a permission knob; all four §2 guards still fire on every dispatch.
- No new transport. This is in-process; the socket control channel is out of
  scope.

## 5. Core model — instance lifetime becomes explicit

Today lifetime is implicit and binary: `auto-close? = (some? parent)`. The
proposal makes it an explicit property of the instance, set at dispatch and
readable thereafter.

### 5.1 A `:lifecycle` field on the instance

Store on `@!state` a `:lifecycle` map:

```clojure
{:mode        :ephemeral        ; :ephemeral (default) | :persistent
 :owner       :exec-agent/…     ; parent instance-id (nil for top-level)
 :last-ask-at <epoch-ms>        ; bumped on every ask; drives idle reaping
 :answers     n}                ; count of completed asks (for detail/telemetry)
```

- `:ephemeral` — current behavior. `run-agent` closes it in the `finally`.
- `:persistent` — `run-agent` **skips** the auto-close; the instance stays in
  `!agent-registry` after answering, `:status` returns to `:idle`.

This is the *only* behavioral fork. Everything else (dispatch guards, task
adoption, `.close` semantics) is unchanged.

### 5.2 Threading the flag through dispatch

`do-call-tool--agent` already reads `:auto-close?` from parsed-args with a
default (`tool.clj:312`). Add a sibling knob the LLM can set on the agent tool
call:

```clojure
(exec-agent {:question "map the auth module"
             :keep-alive? true})     ; ⇒ :persistent, survives the answer
```

`:keep-alive? true` ⇒ `:auto-close? false` **and** stamps `:lifecycle {:mode
:persistent :owner <parent-id>}`. The two are kept distinct because
`:auto-close?` is a mechanism (close-or-not) and `:keep-alive?` is intent
(persist-and-let-me-resume); a persistent instance must also be findable and
reap-eligible, which a bare `:auto-close? false` does not express.

`:keep-alive?` is added to the shared agent-tool input schema (the one every
defagent inherits) so it is uniformly available and self-documenting to the model.

### 5.3 Resume addresses an existing instance by id

Because `get-or-create-agent` is idempotent by id (§1), resume needs **no new
lookup path** — it needs a dispatch that (a) passes the *existing* instance-id
rather than minting a new one, and (b) does not close afterward. The clean seam
is a dedicated resume verb (§6.3) rather than overloading `exec-agent` with an
`:id`, because:

- overloading `(exec-agent {:id …})` collides with the circular-guard (the target
  type is already in `*call-chain*` on a genuine re-entry) and with the
  fresh-id invariant `do-call-tool--agent` documents;
- a persistent subagent may be *any* type, so resume should be type-agnostic and
  key purely off the instance-id the parent already holds.

A resumed `ask` transparently sees the instance's own history — **no new state
plumbing required**, because the per-instance across-turn chain already exists and
already runs at the end of every subagent turn:

- `store-results` (post-loop bookkeeping, `common/coact_agent.clj:4283-4297`)
  folds the just-finished turn (question + compact iterations + answer) via
  `prev-turns/append-turn` into **`(proto/get-st-memory-init agent) :previous-turns`**.
- `reset-st-memory!` (`core/bt.clj:93-100`) re-seeds the working `st-memory` from
  `st-memory-init` at the start of *every* ask, so `:previous-turns` flows into the
  new turn and renders as `## Previous Turns` (`coact_agent.clj:1487, 1092-1094`).
- `st-memory-init` lives on the instance's own `!state` — this chain is entirely
  **per-instance**.

So for an ephemeral subagent, `store-results` writes `:previous-turns` for a "next
turn" that never comes: `run-agent`'s `finally` closes the instance
(`agent.clj:956-959`), `.close` unregisters it, and the record — `:previous-turns`
and all — is GC'd moments after it was written. **The auto-close is the only thing
severing the history chain.** Keep the instance alive and a follow-up `ask` sees
its own `## Previous Turns` for free.

Note the three distinct stores, so resume targets the right one:
- `!session :messages` — session-scoped, **shared across sibling agents**
  (`agent.clj:26-28, 607-638`). This is the cross-agent transcript ("parent
  dispatched X, got Y"), *not* the subagent's working history. Resume does **not**
  read from here.
- `st-memory :conversation` — within-turn per-instance scratch (`coact_agent.clj:1287`).
- `st-memory-init :previous-turns` — across-turn per-instance history. **This is
  what a resumed subagent reads**, and it is already correct.

## 6. LLM-facing tools — extend the existing `agent-registry$*` family

**These tools are not subagent-only, and they are not a new prefix.** The
codebase already has the convention and one of the tools:

- `agent-registry$list` (`common/commands.clj:47`, grouped in
  `registry-commands`) already **lists all registered instances — "root +
  sub-agents"** (its own docstring). It takes an optional `:session-id` to scope
  to one agent-session (`list-agents-for-session`), and lists every instance
  across all sessions when omitted (`list-agents`). It is in
  `all-common-commands` (`commands.clj:807`), so **every** coact-derived agent
  inherits it, root agents included. (Renamed from `agent-registry$instances`,
  which had no session filter.)
- `agent-runtime$config` (`commands.clj:82`) reads/sets the *current* agent's
  config.

So the naming is `agent-<noun>$<verb>`: `agent-registry$*` operates over the set
of instances; `agent-runtime$*` operates on the running self. Lifecycle
management belongs in `agent-registry$*` — do **not** introduce `agent$*` (it
would break the convention and duplicate `agent-registry$list`).

Answering the literal question directly: the lifecycle surface is **registry-wide
by design, not subagent-scoped**. What is genuinely subagent-specific is narrower
and lives elsewhere:

- The `:keep-alive?` opt-in (§5.2) rides the **subagent dispatch** path
  (`do-call-tool--agent`), because that is the only path that auto-closes. A
  root/TUI/top-level agent already persists across turns, so it needs no
  keep-alive knob.
- The **ownership guard** on resume/close (§6.3) is what scopes an *LLM's* reach
  — a subagent may only act on instances it owns. But the tool itself is general:
  a top-level agent (e.g. `main-agent`) legitimately manages any instance in its
  session. Subagent-only-ness is an authorization property, not a tool-identity
  property.

The four verbs below mirror the `task$*` shape (`task/commands.clj:722-728`) —
rich `:output-schema` descriptions, since those are what the model reads each
turn. All are session-scoped (see the note after §6.4).

### 6.1 `agent-registry$list` — extend, don't add

This existing tool (renamed from `agent-registry$instances`) already lists live
instances and takes an optional `:session-id` filter. Extend its `:output-schema`
with the lifecycle fields (`:mode`, `:owner`, `:idle-ms`, `:last-question`) rather
than shipping a parallel tool. Analogous to `task$list`.

```
:output {:agents [{:id "exec-agent/crimson-parrot-42"
                   :type "exec-agent"
                   :status "idle"        ; :idle | :running | :paused
                   :mode "persistent"    ; ephemeral instances are normally
                                         ; gone by the time anyone lists, but
                                         ; a :running ephemeral shows here
                   :owner "main-agent/…"
                   :answers 2
                   :idle-ms 41200        ; now - last-ask-at
                   :last-question "map the auth module"}]
         :total 1}
```

### 6.2 `agent-registry$detail`

One instance, deeper: last answer, current `:status`, iteration/progress snapshot
(reuse `manager/task-progress` when the instance is mid-run under a task),
lifecycle timestamps, and whether it is reap-eligible. Analogous to `task$detail`
(`task/commands.clj:51`) — including the liveness guidance ("large-but-growing
idle window ≠ wedged; do not close on a quiet window alone").

### 6.2b Surfacing the resumable id (how the LLM learns what to resume)

A `:keep-alive?` dispatch mints the instance-id *inside* `do-call-tool--agent`,
so the LLM never sees it unless we return it. The dispatch result therefore
carries, **only when persistent**, `:subagent-id` (the colon-less
`ns/name` form that round-trips through `(keyword …)`), `:resumable true`, and a
`:resume-hint` naming the exact `agent-registry$resume`/`$close` calls. Without
this the keep-alive flag would be unusable — the caller would have a live
instance it can't address. The command-side id parse (`->instance-id`) also
tolerates a leading colon, in case the model copies a printed `:ns/name` keyword.

### 6.3 `agent-registry$resume` (the core new capability)

Follow-up ask to a **named, live** instance. Does not mint a new instance; does
not close on completion (a persistent instance stays persistent).

```
:input  {:id       "exec-agent/crimson-parrot-42"   ; required
         :question "now check the token refresh path"}
:output {:id … :answer "…" :status "idle"
         :error "…"}   ; e.g. instance not found / not owned / busy
```

Semantics:

- **Ownership** — reject resume of an instance whose `:owner` is not the caller
  (prevents a subagent from hijacking a sibling's helper). Top-level (TUI/human)
  callers bypass the ownership check.
- **Busy guard** — if the target `:status` is `:running`, return a `:busy`
  error rather than queueing (mirrors the TUI close-while-running guard,
  `commands.clj:1213`). The parent should `agent-registry$detail`-poll or, if the
  target was detached, `task$wait` on its task.
- **Depth/circular** — resume still counts as a call: it binds `*call-depth*` /
  `*call-chain*` for the duration, so nested resumes obey the same limits.
- Runs **synchronously** for the parent by default (like a parent-present
  `run-agent` ask), but is eligible for the same detach-into-task adoption
  (§2b) when it runs long.

### 6.4 `agent-registry$close`

Explicitly end a persistent instance and reclaim it (`.close` →
unregister + sandbox release + capture-stop-if-last). Reuse the TUI guards
(`commands.clj:1182`): refuse while `:running`, ownership-checked. This is the
LLM's counterpart to `/agent close`.

Add the three new verbs to the existing `registry-commands` group
(`common/commands.clj:781`), which already carries `agent-registry$list`:

```clojure
(def registry-commands
  [#'agent-registry$list        ; extended with lifecycle fields (§6.1)
   #'agent-registry$detail
   #'agent-registry$resume
   #'agent-registry$close])
```

`registry-commands` is already folded into `all-common-commands`
(`commands.clj:807`), so every coact-derived agent inherits these — root agents
too, exactly like the existing `agent-registry$list`. The mutating verbs
(`resume`/`close`) additionally honor the `:enable-subagent-calls` kill switch
and the ownership check; the read verbs (`list`/`detail`) stay ungated like
today.

**Session scoping.** `agent-registry$list` now takes an optional `:session-id`:
given, it scopes to that agent-session via `list-agents-for-session`
(`agent.clj:97`); omitted, it lists every instance in the process registry via
`list-agents`. `detail`/`resume`/`close` address a specific instance-id and
should reject an id outside the caller's session (ownership check, §6.3), so they
don't leak or mutate across sessions either.

## 7. Lifecycle & GC — persistence must not leak

Ephemeral instances self-clean via auto-close. Persistent ones need explicit
reaping, matching the task-retention philosophy
("[Task output files are GC-reclaimed, not deleted on task removal]" in
`CLAUDE.md`): decouple removal from a single trigger, sweep in bulk, bound by
config.

1. **Parent-close cascade.** When an instance closes, close the persistent
   subagents it owns (walk `list-agents-for-session`, filter `:owner = me`,
   `:mode :persistent`). Fold this into `.close` (`agent.clj:409`) via the
   existing `:agent.instance/closed` hook so the cascade is one hook handler, not
   a change to `.close`'s body. Guards against orphaned helpers when the parent
   itself is reaped.
2. **Idle reaping.** A persistent instance idle longer than
   `:persistent-agent-idle-ms` (new config, default e.g. 10 min) is
   reap-eligible. Reaped by the same GC sweep that handles tasks
   (`gc/sweep-tasks!` gains a `sweep-agents!` sibling; surfaced via `task$sweep`
   or a new `agent-registry$sweep`). Never reap a `:running` instance.
3. **Live-instance cap.** `:max-persistent-agents` (new config, default e.g. 8)
   per session. On the cap, a new `:keep-alive?` dispatch either falls back to
   ephemeral (with a marker in the tool result telling the LLM why) or evicts the
   least-recently-asked idle persistent instance. Fallback is safer as the
   default — eviction can surprise a parent mid-plan.
4. **Session teardown.** When an agent-session ends, all its instances
   (persistent or not) are closed — persistence is process-local and
   session-scoped, never durable across a `by` restart (Non-goals §4).

Reaping and the cascade emit mulog events (`::persistent-agent-reaped`,
`::persistent-agent-cascade-closed`) so the sweep's drops are observable rather
than silent — same discipline as the "no silent caps" rule.

## 7b. System-prompt substrate (how the LLM knows to use any of this)

A `## Persistent subagents (agent lifecycle substrate)` section
(`agent-roster/subagent-substrate-protocol`) is installed in the coact
system-context builder (`:subagent-substrate`, in `section-order`), so **every
coact/react-derived agent inherits it** — the same mechanism as the todo / exec
/ skill / MCP substrates. It teaches: default to ephemeral; pass `:keep-alive?
true` for multi-turn follow-up; **capture the returned `:subagent-id`**; resume
with `agent-registry$resume`, inspect via `$list`/`$detail`, close via `$close`;
and the ownership / busy / cap rules. The tools it references already ride
`default-agent-roster`, so the substrate is guidance only — no roster change.
It is **gated on `:enable-subagent-calls`** (threaded through
`assembler-state` → `coact-system-context`): an agent with subagent dispatch
disabled has nothing to keep alive, so the section is dropped rather than
carried as dead prompt weight.

## 8. TUI parity

The `agent-registry$*` tools and the `/agent` verbs are two front-ends over one registry,
so:

- Extract the TUI close-guards (`commands.clj:1182-1234`: no-close-while-running,
  focus hand-off, last-instance protection) into a shared helper in the `agent`
  component that both `agent-registry$close` and `/agent close` call. One guard
  definition, two callers.
- `/agent status` (`commands.clj`) should surface `:mode` and `:owner` so a human
  can see which live instances are LLM-kept persistent helpers vs. their own
  `/agent new` instances.
- A persistent subagent an LLM keeps alive is `/agent switch`-able by the human
  — they are literally the same registry entries. This falls out for free once
  persistent instances stop being auto-closed; no extra wiring.

## 9. Concurrency & safety

- **Single-owner.** Registry ops already go through `get-or-create-agent`'s
  double-checked per-key lock (`agent.clj:753`). Resume/close on a specific id
  should take the same per-key lock so a resume can't race a concurrent reap.
- **Busy = reject, not queue.** No implicit request queue on an instance;
  `:running` ⇒ error (§6.3). Keeps the model simple and matches the TUI.
- **Cancellation still cascades.** The `*subagent-capture*` → task `on-cancel`
  chain (§2b) is unchanged; a persistent instance that is detached still gets
  cancelled through its task. `agent-registry$close` on a detached-and-running instance
  hits the busy guard, pointing the LLM at `task$cancel` instead — the two
  cancel paths stay distinct and non-overlapping.
- **Guards unchanged.** Depth, circular, kill-switch all fire on resume exactly
  as on dispatch.

## 10. Config keys (new)

Following the config-schema precedence in `CLAUDE.md` (env > per-agent > session
> file > default):

| Key | Default | Meaning |
|---|---|---|
| `:persistent-agent-idle-ms` | `600000` | Idle window before a persistent subagent is reap-eligible. |
| `:max-persistent-agents` | `8` | Per-session cap on live persistent subagents. |
| `:persistent-agent-cap-policy` | `:fallback` | `:fallback` (dispatch ephemeral) or `:evict-lru` at the cap. |

`:enable-subagent-calls` (existing, `config.clj:237`) continues to gate both
dispatch and the mutating `agent-registry$*` verbs.

## 11. Phasing

1. **Lifetime fork.** `:keep-alive?` on the agent tool schema → `:lifecycle`
   stamp → `run-agent` skips auto-close for `:persistent`. Ships persistence with
   no LLM-facing management yet (verified via REPL: dispatch persistent, confirm
   it survives in the registry).
2. **`agent-registry$list` (extend) / `agent-registry$detail`.** Read-only surface over the registry.
   Low-risk, immediately useful for observability even before resume.
3. **`agent-registry$resume`.** The core capability. Per-instance history needs no new
   plumbing (§5.3 — `:previous-turns` already carries it); the work is the resume
   verb itself plus ownership + busy guards.
4. **`agent-registry$close` + shared guards + `/agent status` parity.**
5. **GC.** Idle reaping, cascade, cap policy, sweep integration + mulog events.

## 12. Open questions

- **Per-instance history: resolved.** Resume reads `st-memory-init
  :previous-turns`, which is per-instance and already maintained across asks
  (§5.3). No new state plumbing needed; `!session :messages` (shared) is
  deliberately *not* the source. This was previously flagged as the largest
  unknown — it is not one.
- **Cap policy default** — `:fallback` vs `:evict-lru`. Start with `:fallback`
  (no surprise eviction) and revisit once there's usage data.
- **Should ephemeral-but-running instances be resumable?** A long-running
  ephemeral subagent (adopted into a task) is technically live and addressable.
  Proposal: no — resume targets `:persistent` only; a running ephemeral is polled
  via `task$detail`, not resumed. Keeps the two lifetimes cleanly separated.
- **Naming: resolved.** Not a new `agent$*` (or `subagent$*`) prefix — the
  lifecycle verbs extend the existing `agent-registry$*` family (§6), which is
  already registry-wide (root + sub-agents) and already inherited by every agent.
  The `agent-registry$` noun is unambiguous against the per-type dispatch tools
  (`exec-agent`, …): `exec-agent` *creates* a subagent; `agent-registry$*`
  *operates on* live instances. The subagent-only-ness lives in the ownership
  guard, not the tool name.
```
