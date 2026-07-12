# User-defined state machines — durable state + state-gated transitions over the event bus

> Companion to [`event-bus-and-reactor.md`](event-bus-and-reactor.md). That doc gives Brainyard
> a pub/sub event bus, a stateless `event → action` **reactor**, and a **watch** loop. This doc
> adds the missing piece the reactor can't express: **state** — a durable current-state + context
> that gates which reactions fire and that transitions move between. A state machine is the
> reactor with memory.

## 0. Status

Additive and off-by-default; like the event/reactor rollout it **reuses** the existing bus,
reactor action-sinks, scheduler, and persistence rather than introducing a parallel subsystem.
**v1 guards/actions are declarative-only** (safe, inspectable, native-image-clean); an SCI
code-guard/action escape hatch is deferred to Phase 4.

- ✅ **Phase 1 (shipped)** — core flat FSM: `common/fsm.clj` (def store + per-session runtime
  state, `step!`, declarative guards, actions via the reactor's shared `run-action!` + `:assign`,
  lifecycle events, per-session `ensure-fsm!` gated by `:enable-fsm`), `fsm$define/list/status/
  send/reset/remove`. Tests: `agent/fsm_test.clj`.
- ✅ **Phase 2 (shipped)** — timed & eventless transitions: the scheduler daemon fires a
  `:scheduler/tick` pulse; the FSM evaluates each state's `:always` (eventless) and `:after <ms>`
  (timed, via a new `:elapsed/gte` guard over a persisted `:entered-at`) transitions on tick.
- ✅ **Phase 3 (shipped)** — observability: a `## State Machines` user-context section (current
  state + context + last transition per machine, gated by `:enable-fsm`) via `fsm/session-states`,
  plus an `ask.sock :op :fsm-status` verb for external readers.
- ✅ **Phase 4 (shipped)** — SCI code-guards (`:guard-code` / `:guard-fn`) and code-actions
  (`:as :eval`, merges a returned map into context), evaluated in a restricted, serialized,
  1s-bounded clj-sandbox over a pure-data `code-ctx`. Opt-in + fail-closed via `:fsm-allow-code`
  (`BY_FSM_ALLOW_CODE`).
- ⬜ Phase 5 (statechart extensions: nested/parallel/history states) — not yet built.

## 1. The unifying observation — an FSM is a *stateful reactor*

The reactor is stateless: a fired event runs an action, unconditionally. A state machine adds
exactly two things:

1. **Durable state** — a current state + context variables persisted across turns and resume.
2. **State-gated transitions** — a reaction fires only when the machine is *in the right state*,
   and one of its effects is *moving to a new state*.

So the FSM is not a new subsystem — it is the reactor wrapped around a persisted "current state",
using the **same event bus as its nervous system**: it *consumes* events (transition triggers)
and *emits* events (`:fsm/transition`, `:fsm/entered`), so it composes with reactions, watches,
and other machines. The cascade the reactor already supports (a reaction `:emit`s another event)
now carries memory.

```
  events (bus) ──▶ FSM engine (per machine, per session)
                     │  current state S  → look up S's transitions for this event
                     │  guard? over {:event :context :agent}
                     ▼
                   transition: S.exit  →  :do actions  →  T.entry ; assign context ; state := T
                     │        (actions reuse the reactor sinks + :assign + :emit/:fire-hook)
                     ▼
                   fire :fsm/transition {…} ──▶ back onto the bus (drives reactions / other machines)
```

## 2. The model

A **machine definition** — project-scoped, `<project>/.brainyard/fsm/<id>/machine.edn`:

```clojure
{:id "deploy-gate" :initial :idle :context {:attempts 0}
 :states
 {:idle              {:on {:ci/passed [{:target :awaiting-approval}]
                           :ci/failed [{:target :idle
                                        :do [[:assign {:attempts [:inc]}]
                                             [:emit :notify/ci-failed]]}]}}
  :awaiting-approval {:entry [[:turn {:text "CI passed — reply 'deploy' to ship, or 'hold'."}]]
                      :on {:user/approved [{:guard {:agent/idle? true} :target :deploying}]
                           :user/held     [{:target :idle}]}}
  :deploying         {:entry [[:emit :deploy/start]]
                      :on {:deploy/done   [{:target :done}]
                           :deploy/failed [{:target :idle :do [[:emit :notify/deploy-failed]]}]}}
  :done              {:type :final}}}
```

**Runtime state** — per session, `<project>/.brainyard/sessions/<sid>/fsm/<id>.edn`:

```clojure
{:machine "deploy-gate" :state :awaiting-approval :context {:attempts 1}
 :history [{:from :idle :to :awaiting-approval :event :ci/passed :ts …} …]}
```

- **State** — a named node; optional `:entry`/`:exit` action lists; `:type :final` marks terminal.
- **Transition** — `{:target <state> :guard <cond> :do [action…]}`, keyed under `:on {<event> […]}`.
  Ordered; **first matching guard wins**.
- **Context** — extended-state variables (a data map), mutated only by `:assign`. This is how
  "some events fired" is remembered: an AND-join records `:got-a`/`:got-b` as events arrive, then
  an eventless guard checks both (§4). Definitions are data (EDN), fully inspectable/diffable.

## 3. Composition with the event bus (reuse, don't duplicate)

The engine (`components/agent/.../common/fsm.clj`) is layered exactly like `reactor.clj`:

| It needs | It reuses |
|---|---|
| transition triggers | the hooks bus + `ensure-*!`-style per-session hook registration |
| the events it reacts to / emits | the event registry (`events.clj`) |
| action side-effects (`:turn`/`:artifact`/`:memory`/`:context`/`:emit`) | the reactor's `execute-action!` sinks |
| timed / eventless transitions (Phase 2) | the scheduler ticker (the watch loop) |
| atomic state persistence | the `.brainyard/…` EDN store pattern (schedule/events/reactor) |
| config gating, cascade safety | `:enable-*` flags + the reactor's fire-budget/re-entrancy pattern |

What the FSM **adds** on top: state definitions + runtime-state persistence, guard evaluation,
transition semantics (exit → `:do` → entry, `:assign`), and the FSM lifecycle events
(`:fsm/transition`, `:fsm/entered/<state>`, `:fsm/final`) that put it back on the bus.

## 4. Condition procedures (guards) — "checks some events fired or agent state"

A guard is a pure predicate over `{:event <payload> :context <vars> :agent <live agent>}`.
**v1 is declarative** — a small, inspectable DSL, no code-eval:

- `:event/match {…}` — payload subset match (reuse the reactor's `payload-match?`).
- `:context/= {:attempts 3}` · `:context/gte {:attempts 3}` · `:context/all [:got-a :got-b]` ·
  `:context/any […]` — compare / assert context vars. (This is how multi-event "some events
  fired" joins work.)
- `:agent/idle?` · `:agent/running?` · `:agent/config {:key … := …}` — read live agent state via
  the same accessors `reactor`/`auto-notify` use (`(:status @(:!state agent))`, `config/get-config`).
- `:when-all [A B]` / `:when-any [A B]` — event-set join sugar (expands to per-event `:assign` +
  an eventless context guard).
- Guards compose: a transition's `:guard` may be a vector of the above (AND) or `{:or [...]}`.

**Deferred to Phase 4 — SCI code-guard:** `:guard-fn "(fn [ctx] (> (-> ctx :context :attempts) 3))"`
run through `clj-sandbox` (`interface.clj:97`) for full-Clojure conditions — opt-in, gated behind
config since it pulls the sandbox into the FSM path.

## 5. Action procedures — "change agent state or fire events / hooks"

Actions **reuse the reactor's `execute-action!` sinks** plus FSM-specific ones. A `:do` /
`:entry` / `:exit` is an ordered vector of `[action-kw opts]`:

- `[:emit <event> {…}]` / `[:fire-hook <event> {…}]` — fire a user event (`events/emit-event!`) or a
  raw hook (`hooks/fire!`).  ← *fires events via event$emit / hooks.*
- `[:turn {…}]` / `[:run {…}]` / `[:artifact {…}]` / `[:memory {…}]` / `[:context {…}]` — the reactor
  sinks: inject a turn, refresh a live artifact, write project memory, append to `## Events`.
  ← *changes what the agent sees / does.*
- `[:assign {:var value | [:inc] | [:from-event :k]}]` — update context variables.  ← *changes
  machine state.*
- The `:target` of the transition is the state change itself; on transition the engine runs
  `S.exit → :do → T.entry`. String fields interpolate `{{context.var}}` / `{{event.key}}`.

Because an action can `:emit`, a transition can drive **another** machine or a reaction — the same
cascade the reactor supports, now stateful.

## 6. The engine

`common/fsm.clj`, mirroring `reactor.clj`:

- **`ensure-fsm!`** — runtime-only, per-turn at session open, gated by `:enable-fsm`. For each
  machine it registers one bus handler per referenced trigger event (like `ensure-reactions!`),
  routing to `step!`, re-syncing on definition or agent-instance change, torn down on session close.
- **`step!`** — on a matching event for machine M: read M's runtime state → find the first
  transition for `(current-state, event)` whose guard passes → run `exit`/`:do`/`entry` actions +
  apply `:assign` → persist the new state → append to `:history` → fire
  `:fsm/transition {:machine :from :to :event}` and `:fsm/entered/<to>`.
- **Timed / eventless transitions (Phase 2)** — `:always`, `:after <ms>`, or condition-only
  transitions evaluated on the **scheduler ticker** (reuse the watch loop): "after 5m in X → Y",
  "when agent idle → Z".
- **Safety** — reuse the reactor's pattern: a **step budget** (max transitions per originating
  event) + a re-entrancy-depth cap, so `a→b→a` on one event can't loop forever; **single-writer**
  per (session, machine) leaning on the session single-owner invariant (§1 of
  `session-channel-extensions.md`) so concurrent transitions can't race the state file.
- **Persistence** — atomic EDN, same as schedule/events/reactor. Runtime state per-session
  (survives resume); definitions project-scoped. A definition edited mid-run is version-stamped;
  on a version mismatch the machine resets to `:initial` (or a declared `:migrate`), never silently
  runs a stale state against a new graph.

## 7. Surfaces

- **LLM tools:** `fsm$define` (upsert a machine), `fsm$list`, `fsm$remove`, `fsm$status` (current
  state + context + recent transitions), `fsm$send <event> [payload]` (fire a trigger — sugar over
  `event$emit`), `fsm$reset` (back to `:initial`).
- **External (`ask.sock`):** already driven — any `:op :emit` whose event is a machine trigger
  advances it; add `:op :fsm-status` for a read-out.
- **Context section (Phase 3):** a `## State Machines` section (each machine's current state +
  last few transitions) so the agent *sees* where each machine is — riding the same
  volatile-tail path as `## Events`.

## 8. Store & wire formats

```
<project>/.brainyard/fsm/<id>/machine.edn            ; definition (project-scoped)
<project>/.brainyard/sessions/<sid>/fsm/<id>.edn     ; runtime state (per-session)
```

New bus events (emitted by the engine, subscribable/reactable like any other):

```clojure
:fsm/transition   {:machine :from :to :event :context}
:fsm/entered      {:machine :state :context}        ; also :fsm/entered/<state>
:fsm/final        {:machine :state}
```

Config: `:enable-fsm` (`BY_ENABLE_FSM`, default false) · `:max-fsm-steps-per-event` (default 20).

## 9. Phased plan

- **Phase 1 — Core flat FSM.** `fsm.clj`: def store + per-session runtime state, `step!`
  (event → guard → transition → actions → new state), declarative guards (event-match /
  context-compare / agent-state), actions reuse reactor sinks + `:assign` + `:emit`/`:fire-hook`,
  `ensure-fsm!` at session open (gated `:enable-fsm`), lifecycle events, step/cascade budget,
  `fsm$define/list/remove/status/send/reset`. Off by default.
- **Phase 2 — Timed & eventless transitions.** `:always` / `:after <ms>` / condition-only,
  evaluated on the scheduler ticker (reuse the watch machinery).
- **Phase 3 — Observability.** `## State Machines` context section + rich `fsm$status` +
  transition-history log; `ask.sock :op :fsm-status`.
- **Phase 4 — SCI guards/actions.** Opt-in Clojure predicate/action via `clj-sandbox`.
- **Phase 5 — Statechart extensions.** Hierarchical/nested states, parallel regions, history
  states, richer join sugar.

Each phase is additive and off-by-default, exactly like the event/reactor rollout.

## 10. Design decisions & risks

- **FSM = stateful reactor, not a parallel system** — reuse hooks / reactor-sinks / scheduler /
  persistence; the engine is thin. (Same "unify, don't duplicate" call as the event bus.)
- **Declarative guards first, SCI later** — safe, inspectable, native-image-clean for v1; the
  sandbox is an opt-in escape hatch, not on the core path.
- **State scope: per-session runtime, project-scoped definitions** — a machine tracks a session's
  process; resume-safe. A `:shared` (global) flag can come later.
- **Determinism & safety** — ordered first-match transitions; single-writer per machine; a bounded
  step budget kills transition loops; guards are pure (declarative).
- **Bidirectional bus integration** — the FSM emits `:fsm/*` events, so machines, reactions, and
  watches all compose on one bus.

**Risks (all mitigated by an existing pattern):** transition loops (step budget); guard/action
purity (declarative is pure; SCI is sandboxed + gated); concurrent transitions (single-owner);
definition/runtime drift on a mid-run edit (version-stamp + reset/migrate).

## 11. Worked example

The `deploy-gate` machine (§2) end to end, showing every requested capability:

1. A **watch** (`event-bus-and-reactor.md` §Phase 4) probes CI and `:emit`s `:ci/passed` — the FSM
   in `:idle` transitions to `:awaiting-approval`, whose `:entry` **injects a turn** asking the user
   to approve (*action changes what the agent does*).
2. On `:ci/failed`, the transition **assigns** `attempts++` (*condition/context memory*) and
   **emits** `:notify/ci-failed` (*fires an event*), which a **reaction** turns into a Slack MCP call.
3. The user replies "deploy" → the app `:emit`s `:user/approved`; the transition's **guard**
   `{:agent/idle? true}` (*checks agent state*) gates it, then moves to `:deploying`, whose entry
   **emits** `:deploy/start` — driving a reaction that runs the deploy (*fires events, cascade*).
4. `:deploy/done` / `:deploy/failed` route to `:done` (final) or back to `:idle`. Every transition
   fires `:fsm/transition` onto the bus, so a dashboard subscriber (`ask.sock :op :subscribe
   [:fsm/transition]`) sees the whole flow live.

State (`:idle`/`:awaiting-approval`/`:deploying`/`:done`), transition conditions (guards over
events + agent state), and action procedures (assign / emit / inject-turn) — all declarative, all
composing on the one event bus.
