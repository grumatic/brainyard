# Event bus & reactor — user-defined events, reactions, and an external-condition watch loop

> Companion to [`session-channel-extensions.md`](session-channel-extensions.md) (the `ask.sock`
> control channel) and a generalization of three subsystems that already ship:
> [`hooks.clj`](../../components/agent/src/ai/brainyard/agent/core/hooks.clj) (the pub/sub bus),
> [`schedule.clj`](../../components/agent/src/ai/brainyard/agent/common/schedule.clj) (the time
> ticker), and [`auto_notify.clj`](../../components/agent/src/ai/brainyard/agent/common/auto_notify.clj)
> (the one hard-wired `event → reaction` we have today).
>
> Goal: let an external process **and** the agent **and** brainyard-itself define, emit,
> subscribe to, and *react to* named events, and let those events enter the LLM agent loop
> at three intensities (force a turn / write memory / passive context) — plus an event
> *loop* that polls the outside world and self-triggers.

## 0. Status

Design / proposal. Everything is **additive and off-by-default**, and every piece reuses an
existing, proven mechanism rather than introducing a new subsystem.

- ✅ **Phase 1 (shipped)** — user-defined event registry + emit path.
  `components/agent/.../common/events.clj`: persisted defs under
  `<project>/.brainyard/events/<slug>.edn`, folded into the hooks dynamic registry
  (`hooks/register-event!` + extended `known-event?`) via `ensure-events-loaded!` (called
  per-turn from `coact_agent`), and `emit-event!` (payload validated against the def's
  `:payload-schema`, then `hooks/fire!`). Surfaces: `event$define/list/remove/emit` commands,
  the `ask.sock` **`:op :emit`** verb (external → agent, session-scoped; advertised in `:ops`),
  and the interface exports. Since `:op :subscribe` already streams arbitrary event keys,
  this closes the loop for **full bidirectional custom events over `ask.sock`**. Tests:
  `agent/events_test.clj` (4 tests / 28 assertions).
- ✅ **Phase 2 (shipped)** — the reactor. `components/agent/.../common/reactor.clj`: persisted
  `event → action` rules under `<project>/.brainyard/reactions/<id>.edn`, installed per-session
  by `ensure-reactions!` (runtime-only, gated by `:enable-reactions`, called per turn from
  `coact_agent`, re-syncing on rule-set or agent-instance change, torn down on session close).
  A matching event fires each rule's `:do` through the component-level sinks — `:turn`/`:run`
  → `agent/submit-turn` (interactive + root-agent gated), `:artifact` → `add-artifact!`,
  `:emit` → `emit-event!` — with `{{key}}` payload interpolation, a payload `:match` filter,
  and a per-session fire budget + re-entrancy-depth cap bounding cascades. Config:
  `:enable-reactions` / `:max-reaction-fires-per-session` (+ `BY_ENABLE_REACTIONS` /
  `BY_MAX_REACTION_FIRES`). Surfaces: `reaction$add/list/remove/enable/disable`. The passive
  sinks (`:memory`, `:context`) are deferred to Phase 3. Tests: `agent/reactor_test.clj`
  (11 tests / 30 assertions).
- ✅ **Phase 3 (shipped)** — passive sinks. (a) `common/project_memory.clj`: the base
  `inject-memory!` writer (slug write + `index.md` upsert + title/hook derivation) factored
  into one component writer (`agent/write-memory!`) now shared by the ask.sock `:inject :as
  :memory` verb and the reactor's `:as :memory` action. (b) The `:as :context` sink: a matching
  reaction appends to a per-session **event inbox** (`:events-inbox` on the cross-turn store,
  capped at 20, rolling off oldest-first) rendered as a `## Events` user-context section — the
  non-interrupting "inject like memory" intensity, riding the same volatile-tail path as
  `## Live Artifacts`. Tests: `agent/project_memory_test.clj` (4/12), reactor `:memory`/`:context`
  cases (reactor now 14/37); no regression in coact-agent (86/487), context-budget (10/40), or
  ask-channel (4/20). All three reaction intensities now exist: `:context` → `:memory` → `:turn`.
- ✅ **Phase 4 (shipped)** — the watch loop (autonomous event source). `schedule.clj` extended
  with a `:kind :watch` job: each tick the scheduler ticker runs a `:probe` (`:shell` cmd →
  exit+stdout, or `:file` → mtime; `:http`/`:sql` expressible as a shell curl/psql), evaluates a
  `:when` predicate (`:changed` default / `:increased` / `:matches` / `:threshold` /
  `:zero-exit` / `:nonzero-exit`) against the stored last observation, and on a match fires the
  `:emit` event on the bus — which flows through subscribers (§Mode B) and reactions (§3.3).
  Adds an `:every <ms>` interval trigger alongside cron/`:fire-at`, a testable `*run-probe*` seam,
  and `watch$add/list/remove/run-now`; gated by the existing `:enable-scheduler` ticker.
  `run-spec!` dispatches on `:kind`; `schedule$list` hides watches. Tests: `agent/watch_test.clj`
  (9/31); no regression in schedule (10/50).
- ✅ **Phase 5 (shipped)** — discovery + CLI. `by events emit --event <name> [--payload '{…}'] [-s
  <sid>]` (`main.clj` `cmd-events-emit`) fires an event into a live session over its ask socket —
  the external-shell/cron twin of `by ask --attach`, defaulting to the sole live session when
  `-s` is omitted, `--json` for scripting. The `:emit` capability is already advertised in each
  session's `meta.edn` `:ops` (Phase 1), so `by sessions list --json` surfaces it with no extra
  work. Plus the §Usage quickstart below.

**All five phases shipped.** The full path is live: external `:op :emit` / `by events emit` /
LLM `event$emit` / autonomous `watch$*` → hooks bus → subscribers (`:op :subscribe`) + reactions
(`reaction$*`) → `{:turn | :run | :memory | :context | :artifact | :emit}`.

## Usage (quickstart)

```clojure
;; ── declare a vocabulary (optional; the bus fires any keyword regardless) ──
event$define  :name "order/shipped" :payload-schema [:map [:order-id :string]]

;; ── react to it (needs :enable-reactions) ──
reaction$add  :on "order/shipped" :match {:region "us"}
              :do {:as :context :text "Order {{order-id}} shipped — dashboard may be stale."}
;;            :as ∈ :context (## Events, next turn) | :memory | :turn (force now)
;;                 | :artifact | :run | :emit (chain another event)

;; ── fire it, three ways ──
event$emit    :event "order/shipped" :payload {:order-id "A-91"}      ; from the agent (LLM tool)
;; external shell / cron:
by events emit --event order/shipped --payload '{:order-id "A-91"}'   ; into the live session
watch$add     :probe {:type :shell :cmd "…count shipped…"}            ; autonomous, on the ticker
              :when {:op :increased} :emit "order/shipped" :every 60000

;; ── observe from outside (streams until disconnect) ──
;;   echo '{:op :subscribe :events [:order/shipped]}' | nc -U <session>/ask.sock
```

Config gates (all off by default): `:enable-reactions` (`BY_ENABLE_REACTIONS`),
`:max-reaction-fires-per-session` (`BY_MAX_REACTION_FIRES`), `:enable-scheduler`
(`BY_ENABLE_SCHEDULER`, drives watches too). Stores live under `<project>/.brainyard/`:
`events/`, `reactions/`, `schedule/` (watches).

## 1. The unifying observation — one pattern, three existing copies

Every feature in this doc is `(trigger) → (action)`. The codebase already implements that
shape three times, each with a *fixed* trigger or a *fixed* action:

| Existing | Trigger | Action | Where |
|---|---|---|---|
| Scheduler | **time** (cron / `fire-at`) | run a prompt → file sink | `schedule.clj` |
| Auto-notify | **hook event** `:task/completed` (matched to one task) | inject a resume turn | `auto_notify.clj` |
| `ask.sock :op :inject` | **external process** | refresh artifact / force turn / write memory | `core.clj:806` |

Two substrate facts make the generalization cheap:

1. **`hooks.clj` is already a fully general pub/sub bus.** `register-hook!` accepts *any*
   keyword event — it only **warns** on an event outside `event-catalog`, then registers
   anyway (`hooks.clj:227`). `fire!` fires any keyword. So user-defined event *types* work
   at the plumbing level today; what is missing is a *vocabulary* (so they stop warning and
   can be advertised) and *emit paths*.
2. **Outbound subscription to custom events already works end-to-end.** `handle-subscribe-op`
   (`core.clj:857`) never consults the catalog — it just `register-hook!`s whatever keys the
   client passes and streams matching fires. So an external process can **already**
   `{:op :subscribe :events [:my/custom-event]}` today; the only missing half is that
   nothing yet *fires* `:my/custom-event`.

So this doc is **not** a new subsystem. It unifies the three copies behind one bus and one
`trigger → action` reactor, and closes four small gaps.

## 2. The four gaps

Mapped to the two driving questions ("add user-defined events into the LLM agent loop" and
"an event loop to check if events happen around the agent and external user"):

1. **No emit path for user-defined events.** No `:op :emit` on `ask.sock`; no `event$emit`
   LLM tool; the scheduler can only run a prompt, not fire an event.
2. **No reactor.** Nothing turns a fired event into an effect on the loop *by rule*.
   `auto_notify.clj` does exactly this but is hard-wired: trigger `:task/completed`, action
   "inject resume turn". Generalize it to a persisted `event → action` table.
3. **No passive sink.** Today the only way an event reaches the LLM is a **forced turn**. The
   "inject memory" intensity — event accumulates into a context section the agent sees on its
   *next* turn without interruption — does not exist as a first-class event action. (`:op :inject
   :as :memory` writes a slug, but there is no event-driven inbox.)
4. **The event loop only polls time.** The scheduler ticker (`schedule.clj:261`
   `ensure-scheduler!`) is a daemon that already wakes every `:scheduler-tick-ms`. It only
   evaluates *time* triggers. To "check if events happen around the agent and external user"
   it needs a **watch** trigger: a probe (shell / file / HTTP / DB) whose predicate **fires a
   user-defined event**. `watcher = scheduler ∘ (probe → emit)`.

## 3. Architecture — 5 pieces

```
              ┌──────────────────────────────────────────────────────────────┐
   emit       │                     hooks.clj  (the bus)                      │
 ───────────► │   fire! :order/shipped {…}  /  fire-decision! (gated)         │
   from:      └───────────────┬───────────────────────────────┬──────────────┘
   • ask.sock :op :emit       │ register-hook! per rule        │ register-hook! per client
   • event$emit (LLM tool)    ▼                                ▼
   • scheduler :kind :emit  REACTOR (§3.3)              SUBSCRIBERS (ships today)
   • watcher probe (§3.5)   event → action              ask.sock :op :subscribe → external
                              │  :as :turn   → submit-turn (single owner)
                              │  :as :memory → project file memory
                              │  :as :context→ ## Events section (§3.4, passive)
                              │  :as :artifact → live artifact
                              │  :as :run    → run a prompt
                              │  :as :emit   → fire another event (budgeted)
```

### 3.1 Event registry — the vocabulary
New `components/agent/.../common/events.clj`. Persist user event **definitions** under
`<project>/.brainyard/events/<name>.edn`, mirroring `schedule.clj`'s atomic store:

```clojure
{:name           :order/shipped
 :desc           "A shipment left the warehouse"
 :payload-schema [:map [:order-id :string] [:region {:optional true} :string]]
 :llm-injectable? true          ; may an LLM event$emit raise it?
 :created        <epoch-ms>}
```

Folding these names into `known-event?` (`hooks.clj:199`) stops the dev-time warning and lets
discovery advertise them. Registry is **optional** — an unregistered keyword still fires and
still streams (matching today's permissive `register-hook!`); the registry buys validation,
docs, and advertisement, not permission.

### 3.2 Emit paths — how an event gets fired
All three converge on `hooks/fire!` (or `fire-decision!` for a gated event):

- **External → bus.** New `:op :emit {:event :order/shipped :payload {…}}` on `ask.sock`: one
  `case` arm in `ask-handle-fn` (`core.clj:924`) → validate against the registry (if defined)
  → `hooks/fire!`. Advertised in `:ops`. This is the missing twin of `:op :subscribe`.
- **LLM → bus.** `event$emit` tool (agent raises a semantic event that subscribers/reactions
  key off). Gated to `:llm-injectable?` events; non-turn-producing, so cheap.
- **Scheduler → bus.** A spec `:kind :emit` fires an event on its cron/`fire-at` schedule.
- **Watcher → bus.** §3.5.

### 3.3 Reactor — the generalized `auto-notify`
New `components/agent/.../common/reactor.clj`. Persist **rules** under
`<project>/.brainyard/reactions/<id>.edn`:

```clojure
{:id    "on-ship-refresh"
 :on    :order/shipped                 ; any bus event, built-in or user-defined
 :match {:region "us"}                 ; payload filter (subset match); optional
 :do    {:as :turn                     ; :turn | :memory | :context | :artifact | :run | :emit
         :text "Order {{order-id}} shipped — refresh the dashboard."
         :await? false}                ; template vars {{k}} interpolated from payload
 :enabled true :max-fires 100 :created <epoch-ms>}
```

`ensure-reactions!` — idempotent, runtime-only, guarded by a `defonce` atom exactly like
`auto_notify/ensure-global-hooks!` (`auto_notify.clj:335`; so native-image bakes `false` and
the first real turn installs) — reads the rules and `register-hook!`s one handler per rule,
`:match` derived from the rule's payload filter, `:source :reactor`. Each handler runs the
`:do` action through the **same sink functions `handle-inject-op` already uses** (`core.clj:806`):

- `:as :turn` / `:run` → `agent/submit-turn` (`interface.clj:128`) so it serializes on the
  single-owner input queue — the §1 invariant of `session-channel-extensions.md` (already holds).
- `:as :memory` → the `inject-memory!` path (`core.clj:776`).
- `:as :artifact` → `agent/add-artifact!`.
- `:as :context` → §3.4.
- `:as :emit` → `hooks/fire!` another event, under the cascade budget (§6).

**Retirement path:** `auto_notify.clj` becomes one built-in reaction (`:on :task/completed →
:as :turn`), or stays as-is and the reactor sits beside it — either way the reactor is a
strict generalization, so no behavior regresses.

### 3.4 Passive event-inbox context section — the "inject memory" intensity
For `:do {:as :context}`, deliver the event into a per-session inbox that renders as a
`## Events` context section on the agent's *next* turn — **no forced turn**. This is the
non-interrupting sink the question calls "injecting memory," and it reuses the exact
mechanism behind `## Live Artifacts` (`artifacts.clj`, composed in `coact_agent.clj`) and the
`!inbox` coalescing in `auto_notify.clj:101`. Three intensities then exist, escalating:

| `:as`      | Effect | Interrupts turn? |
|------------|--------|------------------|
| `:context` | appended to `## Events`, seen next turn | no |
| `:memory`  | written as a `<slug>.md`, discoverable via `## Project Memory` | no |
| `:turn`    | `submit-turn` — forces a turn now | yes |

### 3.5 Watcher — the event loop for external conditions
Extend `schedule.clj` with a spec `:kind :watch` (no new thread — the existing daemon ticker
and atomic store carry it):

```clojure
{:id    "watch-orders-db" :kind :watch
 :probe {:type :shell :cmd "psql -tAc 'select count(*) from orders where shipped'"}
 :when  {:op :increased}          ; :changed | :nonzero-exit | :matches <re> | :increased | :threshold
 :emit  :order/shipped            ; fire this on the predicate
 :every 60000                     ; or a :cron
 :enabled true}
```

Each tick the ticker evaluates the probe via the pluggable `*execute-job*` seam
(`schedule.clj:198` — fully testable without an LLM), diffs against the last observation
stored in the spec, and on the predicate calls `hooks/fire!`. Probe types: `:shell` (exit /
stdout), `:file` (mtime / content hash), `:http` (status / body match), `:sql`. The fired
event then flows through §3.3 into the loop. This is literally the scheduler with a
`probe → emit` action instead of `prompt → file`.

## 4. Wire & tool surface (all additive)

```clojure
;; ── ask.sock (Mode A) ──────────────────────────────────────────
→ {:op :emit :event :order/shipped :payload {:order-id "A-91"}}     ; NEW
← {:status :ok :fired :order/shipped :reactions 2}
;; ── ask.sock (Mode B) — already works, now something fires it ──
→ {:op :subscribe :events [:order/shipped]}

:ops  [:ask :status :config :inject :cancel :subscribe :emit]        ; advertise :emit
```

New LLM tools: `event$define`, `event$emit`, `reaction$add/list/remove/enable/disable`,
`watch$add/list/remove` (or folded into `schedule$add :kind :watch`). All follow the compact
one-line-docstring convention and the `defcommand`/`:input-schema`/`:output-schema` shape of
`schedule.clj`.

## 5. Phased plan

- **Phase 1 — Emit + registry. ✅ shipped.** `events.clj` store, `:op :emit`, `event$emit` tool,
  advertise `:ops`, `known-event?` folds registered names. *Outbound subscribe already works, so
  this alone delivers full bidirectional custom events over `ask.sock`.* Smallest useful slice.
- **Phase 2 — Reactor. ✅ shipped.** `reactor.clj` + `.brainyard/reactions/`,
  `ensure-reactions!` per turn, `:turn`/`:run`/`:artifact`/`:emit` actions via component-level
  sinks + `submit-turn`, `{{}}` interpolation, `:match` filter, cascade budget. Expressing
  `auto-notify` as a built-in reaction remains optional/future.
- **Phase 3 — Passive sinks. ✅ shipped.** `## Events` context section (`:as :context`, a capped
  per-session inbox on the volatile tail) + the `:as :memory` sink, with the base `inject-memory!`
  writer factored into one shared `common/project_memory.clj` used by both the ask.sock `:inject`
  path and the reactor.
- **Phase 4 — Watcher. ✅ shipped.** `:kind :watch` + `:probe`/`:when`/`:emit` + `:every` in
  `schedule.clj`; probe types shell/file (http/sql via shell); `watch$*` commands; runs on the
  existing scheduler ticker.
- **Phase 5 — Discovery + docs. ✅ shipped.** `by events emit` CLI (parallel to `by ask
  --attach`); `:emit` advertised in `:ops` so `by sessions list --json` surfaces it; §Usage
  quickstart above.

Each phase is off-by-default: reactor and watcher gate behind config flags (parallel to
`:enable-scheduler`), so no background LLM spend or surprise turns happen until opted in.

## 6. Risks & notes (all already-solved shapes)

- **Turn-producing reactions route through the single owner.** Reuse `submit-turn` + the
  per-root input queue; the §1 single-owner invariant of `session-channel-extensions.md`
  already holds. Subscriptions/emit remain read-side and may be many.
- **Cascade / feedback loops** (event A → reaction fires B → …). Reuse `auto_notify`'s
  bounded-budget pattern (`!resume-counts` / `max-resumes`, `auto_notify.clj:106`): a
  per-session, per-cause fire counter that caps a pathological chain, plus `:max-fires` per
  rule and hook re-entrancy depth (`hooks/current-depth`).
- **Backpressure** on subscribe is already solved (bounded `LinkedBlockingQueue`, drop-for-slow
  consumer — `handle-subscribe-op`).
- **Trust boundary** unchanged: `0600` sockets and `.brainyard` file perms; emitting/reacting
  is a same-project trust operation, not an authenticated remote API. A watcher probe running
  a shell command inherits the session's `--sandbox` policy if enabled.
- **GraalVM.** No new native-image surface — AF_UNIX NIO, daemon-thread tickers, `defonce`
  runtime-install guards, and atomic EDN stores are all already validated.
- **Serialization.** Payloads on the wire pass through `edn-safe` (`core.clj:844`); the same
  coercion applies to `:op :emit` inputs and reaction template interpolation.
