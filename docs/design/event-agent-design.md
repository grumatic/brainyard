# Event-Agent — LLM-Mediated CRUD over the Event Bus (events · reactions · watches)

> **Status:** Design / proposal. The underlying **event-bus & reactor subsystem is
> fully shipped** ([`event-bus-and-reactor.md`](./event-bus-and-reactor.md), all five
> phases) — this doc adds a *conversational front door* over its CRUD surface, exactly
> as `config-agent` did for `config.edn` and `mcp-agent` did for MCP servers. No new
> mechanism is introduced; the agent orchestrates existing `event$*` / `reaction$*` /
> `watch$*` commands.
> **Scope:** new `components/agent/src/ai/brainyard/agent/common/event_agent.clj`
> (a thin `coact`-derived agent), reusing the already-registered command families in
> `common/events.clj`, `common/reactor.clj`, and `common/schedule.clj` (watches).
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived` (no new BT, no new DSPy
> signature) — same pattern as [`config-agent`](./config-agent-design.md),
> [`mcp-agent`](./mcp-agent-design.md), [`edit-agent`](./edit-agent-design.md).
> **Sibling of:** `config-agent` (config CRUD), `mcp-agent` (MCP lifecycle),
> `edit-agent` (safe file edits). Adjacent: the FSM family (`fsm$*`) — see §11.
> **Related reading:**
> [`event-bus-and-reactor.md`](./event-bus-and-reactor.md),
> [`state-machine-design.md`](./state-machine-design.md),
> `components/agent/src/ai/brainyard/agent/common/events.clj`,
> `components/agent/src/ai/brainyard/agent/common/reactor.clj`,
> `components/agent/src/ai/brainyard/agent/common/schedule.clj`.

---

## 1. Motivation

The event subsystem is powerful and complete, but its surface is **wide, low-level, and
spread across three vocabularies**. To wire up even a simple "when an order ships, refresh
the dashboard" flow the user has to:

1. know that events are declared with `event$define` and stored under
   `.brainyard/events/<slug>.edn` (and that declaration is *optional* — the bus fires any
   keyword);
2. know that reactions are separate rules (`reaction$add`) stored under
   `.brainyard/reactions/<id>.edn`, gated behind `:enable-reactions`, with a `{{key}}`
   interpolation mini-language and six `:as` sink kinds;
3. know that autonomous polling is a *watch* (`watch$add`) that lives in the **scheduler**
   store and only ticks when `:enable-scheduler` is on;
4. hold the payload-schema, the `:match` subset-filter semantics, the per-session fire
   budget, and the re-entrancy cap in their head at once.

That is a lot of surface for a subsystem whose whole appeal is "declare a trigger, declare
an action." Today the only ways in are (a) typing the raw commands, (b) editing EDN under
`.brainyard/` by hand — which skips payload-schema validation and the config gates entirely
— or (c) the `by events emit` CLI, which only *fires* an event, it doesn't help you
*author* the vocabulary.

There is also no **coherent read/audit story**. `event$list`, `reaction$list`, and
`watch$list` each return a slice; nobody stitches "here is everything that will fire in this
project, what triggers it, and whether it's currently enabled" into one picture. When a
reaction silently never fires, the cause is usually a `:match` filter that never matches (or,
less often, a gate the user has disabled) — and diagnosing that today means reading
three stores plus the config.

**Thesis.** Add a CoAct-derived `event-agent` that is **the one chat surface for the event
subsystem's CRUD and diagnosis**:

1. **Authors the vocabulary conversationally.** "When an order ships, remind me to refresh
   the dashboard" → the agent declares the event (if new), writes the reaction, checks the
   gate, and tells you in one turn what it did and whether it will actually fire.
2. **Owns read + audit.** A single `event$*`/`reaction$*`/`watch$*` sweep rendered as one
   "what fires in this project" board, with gate/enabled status inline.
3. **Guards the gates.** Every write that depends on `:enable-reactions` or
   `:enable-scheduler` surfaces whether that gate is on, and offers to turn it on (through
   `config-agent`) rather than leaving a dead rule on disk.
4. **Validates before persisting.** Payload schemas, `:match` filters, `{{key}}` template
   references, and event-name coercion (`->event-key`) are checked and previewed before the
   rule lands — the same "preview, don't surprise" contract as `config-agent`.
5. **Never re-implements the mechanism.** It calls the shipped commands; it does not touch
   `.brainyard/events/`, `.brainyard/reactions/`, or the scheduler store by hand.

Same minimal-diff pattern as the other specialists: one new agent file, zero new commands
(the command families already exist), an `event-agent/` dossier directory under
`.brainyard/`.

---

## 2. Design Principles

1. **One CRUD surface per artifact kind.** Events go through `event$*`, reactions through
   `reaction$*`, watches through `watch$*`. The agent never writes the EDN stores by hand
   and never bypasses the payload-schema / gate checks the commands enforce.
2. **Read first, write rarely.** Every conversation opens with a read sweep
   (`event$list` + `reaction$list` + `watch$list`) plus the relevant config gates. Writes
   only happen after the user has seen what exists and confirmed the change.
3. **Declaration is optional; the agent makes it deliberate.** The bus fires any keyword, so
   `event$define` is not required to emit. The agent still *offers* to declare a named event
   when the user is building a reaction against it — a declared event gets a payload schema,
   a description, and discovery advertisement, which is what makes the vocabulary legible
   later. It never *forces* a declaration.
4. **Surface the gate, every time.** `:enable-reactions` gates reactions;
   `:enable-scheduler` gates watches (both default **on**). A rule authored while its gate is
   off is stored but inert. Because the defaults are on, a fresh rule usually fires live —
   but the agent reads the *actual* gate value and, if a user has turned it off, states the
   outcome plainly ("Stored — but reactions are disabled in your config, so it won't fire
   until you re-enable them") and offers the `config-agent` handoff.
5. **Validate the trigger→action wiring, not just the syntax.** Beyond "is this valid EDN,"
   the agent checks that a reaction's `{{key}}` template vars exist in the event's declared
   `:payload-schema`, that `:match` keys are payload keys, and that the `:on` event name
   coerces cleanly. A mismatch here is the #1 cause of "my reaction never fires."
6. **Emit is a test tool, not a side effect.** The agent uses `event$emit` to *dry-run* a
   freshly authored reaction ("let's fire it once with a sample payload and confirm the
   dashboard reaction ran") — but it treats emitting into a live session as a real action
   and confirms before doing so, because reactions may force a turn.
7. **Hand off gate changes to config-agent.** Turning `:enable-reactions` /
   `:enable-scheduler` / `:enable-fsm` on or off is a *config* write. The agent calls
   `config-agent`, it does not write `config.edn` itself — the same "bootstrap concerns
   belong to bootstrap" boundary `config-agent` §2 draws.
8. **Dossier per conversation.** Every session writes a markdown dossier under
   `.brainyard/agents/event-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md` recording what was
   read, what was created/removed, which gates were involved, and any dry-run result.
   Mirrors the explore/plan/config/mcp agent pattern.
9. **No clone-self recursion.** Like the other CoAct-derived specialists, `event-agent`
   excludes `query$clone` from its roster. Cross-agent dispatch via flat
   `(call-tool "<other>" {…})` is fine — `config-agent` for gates, `explore-agent` for
   "find every place that emits this event," `edit-agent` for a shell probe script.
10. **Be honest about durability and blast radius.** Reactions and watches persist across
    sessions; a `:as :turn` reaction *forces a turn* and a watch *runs a shell probe on the
    ticker*. The agent names the blast radius of every rule it creates so an autonomous
    trigger never silently surprises the user later.

---

## 3. Position in the Agent Stack

```
   coact-agent  (parent — full BT, sandbox, router, accumulator)
     ├─ explore-agent   (read-mostly discovery — "who emits :order/shipped?")
     ├─ edit-agent      (safe file edits — watch probe scripts, .env)
     ├─ config-agent    (config CRUD — owns :enable-reactions / :enable-scheduler / :enable-fsm)
     ├─ mcp-agent        (MCP lifecycle)
     └─ event-agent     (THIS — events · reactions · watches CRUD + diagnosis)
```

`event-agent` is a peer of the other CoAct-derived specialists. It calls *them* when a
conversation needs a sibling's capability:

- **`config-agent`** — to flip a gate on/off. `event-agent` proposes; `config-agent` writes.
- **`explore-agent`** — "find every `event$emit` / `hooks/fire!` of `:order/shipped` in the
  codebase" before the user removes an event definition.
- **`edit-agent`** — to author or edit a `:kind :watch` shell/file probe script that lives as
  a normal file.

It does not subclass them and they do not call it. This keeps event-agent focused on the
trigger→action vocabulary and nothing else.

---

## 4. Capability Surface

Five conversation kinds the instruction (§6) tells the LLM to recognise. These are
user-facing categories, not branches in the loop.

1. **Show me what fires.** "What events do I have?" "List my reactions." "Is anything
   watching my orders table?" Pure read sweep across the three stores + gate status → one
   rendered board → done.
2. **Wire a trigger→action.** "When an order ships, remind me to refresh the dashboard."
   Declare the event if new → author the reaction → validate template/match → check the gate
   → optionally dry-run → confirm.
3. **Author an autonomous watch.** "Every minute, check the shipped-orders count; if it
   went up, fire `order/shipped`." Build the `:probe` / `:when` / `:emit` / `:every` spec →
   check `:enable-scheduler` → optionally `watch$run-now` once to prove the probe.
4. **Diagnose a dead rule.** "My reaction never fires." Read the rule, the event, and the
   gate; check `:match` against a sample payload; check `{{key}}` vars against the schema;
   report the specific cause (gate off / match filter / typo'd event name / fire budget
   exhausted).
5. **Retire / tune.** "Remove the broken watch." "Disable that reaction for now." "Bump its
   max-fires." `reaction$disable` / `reaction$remove` / `watch$remove` / re-`add` with new
   fields.

What the agent must NOT do:

- Flip `:enable-reactions` / `:enable-scheduler` / `:enable-fsm` itself (hand to
  `config-agent`).
- Emit into a live session without confirming (a `:as :turn` reaction forces a turn).
- Edit `.brainyard/events/`, `.brainyard/reactions/`, or the scheduler store by hand.
- Author a watch whose probe shells out to something destructive without an explicit
  confirmation and a note that the probe inherits the session's `--sandbox` policy.

---

## 5. Tool Roster

`event-agent` binds the standard CoAct primitives plus the three already-registered command
families. **No new commands are defined** — this is the whole point of the minimal-diff
pattern.

Inherited from the shared registry:

| Tool | Used for | Notes |
|---|---|---|
| `read-file` / `grep` | Reading probe scripts, scanning for emitters | Standard sandbox tool surface. |
| `bash` (allowlisted) | Dry-running a watch probe command by hand before wiring it | Diagnostic; the real probe runs on the ticker. |
| `agent-runtime$config` | **Read** the gate flags (`:enable-reactions`, `:enable-scheduler`, `:enable-fsm`) | Read-only here; *writes* go through `config-agent`. |
| `config-agent` (call-tool) | Flip a gate on/off | The only path through which a gate changes while event-agent runs. |
| `explore-agent` (call-tool) | "Find every place that emits event X" | Read-only discovery before a destructive remove. |
| `edit-agent` (call-tool) | Author / edit a watch probe script or `.env` | Normal file edits, not part of the event stores. |

Event-subsystem commands (already registered — see the roster wiring in
`common/commands.clj`, which folds `events/events-commands`, `reactor/reaction-commands`,
and `schedule/schedule-commands` into `all-common-commands`):

| Command | Store | Effect |
|---|---|---|
| `event$define` | `.brainyard/events/<slug>.edn` | Declare a named event: `:name` (namespaced keyword), optional `:payload-schema` (malli), `:desc`, `:llm-injectable?`. Optional — the bus fires undeclared keywords too. |
| `event$list` | — | List declared events (`:name`, `:desc`, `:payload-schema`, `:llm-injectable?`). |
| `event$remove` | `.brainyard/events/<slug>.edn` | Drop an event declaration (does not stop the bus firing that keyword). |
| `event$emit` | — | Fire an event with an optional payload; validates against the declared `:payload-schema`. Gated to `:llm-injectable?` events. The agent's dry-run tool. |
| `reaction$add` | `.brainyard/reactions/<id>.edn` | Persist a `trigger → action` rule: `:on`, optional `:match` payload filter, `:do {:as … :text …}`, `:max-fires`. |
| `reaction$list` | — | List rules with `:enabled`, `:on`, `:do`, fire counts. |
| `reaction$remove` | `.brainyard/reactions/<id>.edn` | Delete a rule. |
| `reaction$enable` / `reaction$disable` | `.brainyard/reactions/<id>.edn` | Toggle a rule without deleting it. |
| `watch$add` | scheduler store (`.brainyard/schedule/`) | Autonomous poller: `:probe {:type :shell\|:file …}`, `:when {:op :changed\|:increased\|…}`, `:emit <event>`, `:every <ms>` or `:cron`. |
| `watch$list` | — | List watches (`schedule$list` hides them; `watch$list` surfaces them). |
| `watch$remove` | scheduler store | Delete a watch. |
| `watch$run-now` | — | Run a watch's probe once immediately (proves the probe + predicate without waiting for the tick). |

The `:as` sink kinds a reaction's `:do` can take, escalating in intensity (from
event-bus-and-reactor.md §3.3–3.4):

| `:as` | Effect | Interrupts a turn? |
|---|---|---|
| `:context` | appended to the `## Events` context section, seen next turn | no |
| `:memory` | written as a `<slug>.md` project memory | no |
| `:artifact` | refreshes a live artifact | no |
| `:emit` | fires another event (under the cascade budget) | no |
| `:run` / `:turn` | `submit-turn` — forces a turn now | **yes** |

---

## 6. Instruction Shape

The shared CoAct instruction template (see `common/coact_agent.clj` and
[`react-coact-unification-plan.md`](./react-coact-unification-plan.md)) plus five
event-agent guidances:

1. **Open with a read sweep.** Before proposing anything, call `event$list`,
   `reaction$list`, `watch$list`, and read the three gate flags via `agent-runtime$config`.
   Render "what fires today" and reason against that, not an assumed baseline.
2. **Classify into one of the five capability kinds (§4) before acting.** If ambiguous, ask
   one targeted question — never five. In particular, distinguish "fire it once now"
   (`event$emit`) from "make it fire on a rule" (`reaction$add`) from "poll for it"
   (`watch$add`); these are the three things users conflate.
3. **For any write, follow read → propose → validate → (gate check) → confirm → apply.**
   Validation means: event name coerces (`->event-key`), payload matches the declared
   schema, `{{key}}` template vars are payload keys, `:match` keys are payload keys. Show the
   proposed rule inline; the user types `yes` / `no` / `change X`.
4. **State the gate outcome plainly.** After any reaction/watch write, say whether it will
   actually fire given the current gate. If the gate is off, offer — in one line — to enable
   it via `config-agent`. Never leave a dead rule on disk without saying so.
5. **Name the blast radius.** For a `:as :turn`/`:run` reaction or any watch, state that it
   forces a turn / runs a probe on the ticker, and that a watch probe inherits the session's
   sandbox policy. Confirm before emitting into a live session.

The instruction explicitly forbids:

- Writing `:enable-reactions` / `:enable-scheduler` / `:enable-fsm` directly (use
  `config-agent`).
- Hand-editing `.brainyard/events/`, `.brainyard/reactions/`, or the scheduler store.
- Emitting into a live session, or authoring a destructive watch probe, without explicit
  confirmation.
- Fabricating a payload schema the user didn't ask for — declaration stays optional.

---

## 7. Store Layout & Dossier

The agent reads and writes through commands; the stores it touches (all under the project
root, mirroring the `schedule` subsystem's atomic-write discipline):

```
<project>/.brainyard/
├── events/<slug>.edn            ;; event$define — one file per declared event
├── reactions/<id>.edn           ;; reaction$add — one file per rule
├── schedule/…                   ;; watch$add — watches live in the scheduler store
└── agents/event-agent/
    ├── dossiers/
    │   └── 20260713-142059-order-shipped-reaction.md
    └── INDEX.md                 ;; append-only, last 100 entries
```

**Dossier.** One markdown file per conversation, frontmatter matching the sibling agents:

```markdown
---
agent: event-agent
session-id: 2026-07-13-events-8fa21
question: "When an order ships, remind me to refresh the dashboard"
started: 2026-07-13T14:20:40Z
ended: 2026-07-13T14:21:12Z
gates: {enable-reactions: true, enable-scheduler: true}
created:
  - event: order/shipped
  - reaction: on-ship-refresh
removed: []
dry-run: {event: order/shipped, reactions-fired: 1}
next-steps: []
---

## What I read
- event$list → [] (no declared events)
- reaction$list → [] · watch$list → []
- gates → :enable-reactions true, :enable-scheduler true

## What I created
1. event$define :name order/shipped :payload-schema [:map [:order-id :string]]
2. reaction$add :on order/shipped :do {:as :context :text "Order {{order-id}} shipped — dashboard may be stale."}

## Gate status
- :enable-reactions ON (default) → the reaction fires live; appends to ## Events next turn.

## Dry-run
- event$emit :event order/shipped :payload {:order-id "A-91"} → {:fired :order/shipped :subscribers 1}
```

---

## 8. Conversation Patterns (few-shot worked examples)

These are the canonical worked flows the instruction points the LLM at. Each shows the tool
sequence and the shape of the final answer.

### 8.1 Wire a trigger → action (capability kind 2)

> **User:** when an order ships, remind me to refresh the dashboard

1. `event$list` → `[]`; `reaction$list` → `[]`; `agent-runtime$config` → `:enable-reactions
   true` (default).
2. Recognise: new event + a passive reminder (no forced turn) → `:as :context`.
3. `event$define :name "order/shipped" :payload-schema [:map [:order-id :string]]` →
   `{:defined :order/shipped}`.
4. Propose the rule inline and confirm:
   `reaction$add :on "order/shipped" :do {:as :context :text "Order {{order-id}} shipped — dashboard may be stale."}`.
5. `event$emit :event "order/shipped" :payload {:order-id "A-91"}` → dry-run;
   `{:fired :order/shipped :subscribers 1}`.
6. Answer:
   > Created event `order/shipped` and reaction `on-ship-refresh` (appends a `## Events`
   > note next turn — no interruption). It's **live now** — reactions are on by default. If
   > you'd ever disabled `:enable-reactions`, I'd flag that and offer to re-enable it via
   > config-agent.

### 8.2 A reaction that forces a turn — name the blast radius (kind 2)

> **User:** when the deploy finishes, have the agent summarize what changed right away

1. Read sweep. This is "act now," not "note for later" → `:as :turn`.
2. Propose and **flag the blast radius before confirming**:
   > This will *force a turn* the moment `deploy/finished` fires — the agent will start
   > working on its own. OK to proceed?
3. On yes:
   `reaction$add :on "deploy/finished" :match {:env "prod"} :do {:as :turn :text "Deploy to prod finished ({{sha}}). Summarize what changed."}`.
4. Gate check → offer to enable reactions if off.
5. Answer names the trigger, the `:match {:env "prod"}` scope, and the gate outcome.

### 8.3 Author an autonomous watch (capability kind 3)

> **User:** every minute check how many orders shipped; if it went up, fire order/shipped

1. `watch$list` → `[]`; gate: `:enable-scheduler true` (watches need the ticker).
2. Build the spec:
   ```clojure
   watch$add :probe {:type :shell :cmd "psql -tAc 'select count(*) from orders where shipped'"}
             :when  {:op :increased}
             :emit  "order/shipped"
             :every 60000
   ```
3. **Prove the probe before trusting the tick:** `watch$run-now :id <id>` → confirms the
   shell command runs, exits 0, and returns a number.
4. Answer:
   > Watch `watch-orders-db` added — polls that count every 60s and fires `order/shipped`
   > when it climbs. Scheduler is ON, so it's live now. Note: the probe runs a shell command
   > on the ticker and inherits this session's sandbox policy.

### 8.4 Diagnose a dead rule (capability kind 4)

> **User:** my on-ship-refresh reaction never fires

1. `reaction$list` → rule present, `:enabled true`, `:on :order/shipped`,
   `:match {:region "us"}`.
2. `agent-runtime$config` → `:enable-reactions true` (gate is fine).
3. `event$list` → `order/shipped` schema `[:map [:order-id :string] [:region {:optional true} :string]]`.
4. Inspect the `:match`: it requires `:region "us"`, but the emit in 8.1 sent only
   `{:order-id "A-91"}` — no `:region`, so the subset-match filter never matches.
5. Answer, with the specific cause:
   > Found it — the rule has `:match {:region "us"}` but the events you're firing don't carry
   > a `:region`, so the filter never matches. Two fixes: include `:region "us"` in the
   > payload when you emit, or drop the `:match` if you want it to fire for all regions. Want
   > me to remove the filter?

The diagnostic checklist the instruction encodes, in order: **gate off? → event name typo
(`->event-key` mismatch)? → `:match` never matches the payload? → `{{key}}` var absent from
schema? → `:max-fires` / per-session budget exhausted?**

### 8.5 Show me what fires (capability kind 1)

> **User:** what's set up to fire in this project?

1. `event$list` + `reaction$list` + `watch$list` + the three gates in one sweep.
2. Render one board:
   > **Events (2 declared):** `order/shipped`, `deploy/finished`
   > **Reactions (2):** `on-ship-refresh` (order/shipped → `:context`, **enabled**) ·
   > `deploy-summary` (deploy/finished → `:turn`, disabled)
   > **Watches (1):** `watch-orders-db` (every 60s → order/shipped)
   > **Gates:** reactions **ON** · scheduler **ON** · fsm off

No writes, no dossier beyond the read record. The fast path matters — a "what's set up"
question must not turn into a multi-turn flow.

### 8.6 Retire a rule (capability kind 5)

> **User:** remove the deploy summary reaction, it's too noisy

1. `reaction$list` → confirm the id (`deploy-summary`).
2. Offer the softer option first: "Disable it (keeps the definition) or remove it entirely?"
3. On "remove": `reaction$remove :id "deploy-summary"` → `{:removed "deploy-summary"}`.
4. Dossier records the removal. Answer confirms and notes the event `deploy/finished` still
   exists and still fires — only the reaction is gone.

---

## 9. Edge Cases

1. **Gate disabled by the user.** Gates default on, so this is uncommon — but if a read
   shows `:enable-reactions`/`:enable-scheduler` off, store the rule (the commands do) and
   state plainly it's inert, offering the `config-agent` handoff. Never imply an inert rule
   is live.
2. **Undeclared event in a reaction.** Legal — the bus fires any keyword. The agent offers
   (doesn't force) an `event$define` so the vocabulary is legible, and warns that without a
   schema the payload/`{{key}}` validation is best-effort.
3. **`event$emit` on a non-`:llm-injectable?` event.** `event$emit` refuses (returns
   `:error`). The agent surfaces the refusal and explains the flag rather than working
   around it.
4. **`{{key}}` template var not in the schema.** Warn before writing — this is the classic
   "renders as literal `{{region}}`" bug. Offer to add the key to the schema or fix the
   template.
5. **`:match` key not in the payload.** Same class as 8.4 — the filter silently never
   matches. Flag at author time, not just at diagnosis time.
6. **Destructive watch probe.** A `:probe {:type :shell :cmd …}` that mutates state is
   refused without explicit confirmation, with a note that it runs on the ticker under the
   session sandbox policy.
7. **Cascade risk (`:as :emit` chains).** When a reaction emits an event that another
   reaction reacts to, name the chain and rely on the shipped per-session fire budget
   (`:max-reaction-fires-per-session`) + re-entrancy cap; suggest a lower `:max-fires` if the
   chain looks unbounded.
8. **Removing an event other code emits.** Before `event$remove`, offer an `explore-agent`
   sweep for emitters; removing a *declaration* doesn't stop the bus firing the keyword, so
   set expectations correctly.
9. **Watch authored while `:enable-scheduler` is off.** Stored but never ticks. Same gate
   discipline as reactions — state it, offer the handoff.
10. **User pastes raw EDN** (`reaction$add {:on :x :do {:as :turn …}}`). Accept, validate,
    run the same preview/confirm flow — don't fast-path around validation just because the
    input was pre-structured.

---

## 10. Testing Plan

Three surfaces, matching the sibling agents.

1. **Unit — command pass-through.** The event/reaction/watch commands already have coverage
   (`agent/events_test.clj`, `agent/reactor_test.clj`, `agent/watch_test.clj`). event-agent
   adds tests only for its *orchestration*: gate-status rendering, the validation checks
   (template-var-in-schema, match-key-in-payload, name coercion) as pure functions.
2. **Integration — CoAct loop with a stubbed LLM.** Canned LLM turns drive the agent through
   each capability kind (§4). Assert the resulting on-disk stores (events/reactions/schedule)
   and the dossier frontmatter match goldens — especially the `gates:` and `dry-run:` fields.
3. **End-to-end — manual / CI-opt-in.** Real `bb tui run -a event-agent` against a scratch
   `.brainyard/`. Exercises: wire a `:context` reaction, dry-run it via `event$emit`, flip
   `:enable-reactions` through the config-agent handoff, and confirm the live fire. Slow;
   tagged `:slow`.

The dossier is its own contract — every test asserts the documented frontmatter fields are
populated.

---

## 11. Relationship to FSM (`fsm$*`) — deliberately adjacent, not owned

The state-machine family (`fsm$define/list/status/send/reset/remove`,
[`state-machine-design.md`](./state-machine-design.md)) rides the *same event bus* — an FSM
is a stateful reactor whose transitions are gated by declarative guards and driven by fired
events. It is tempting to fold it into event-agent, but this design **keeps it adjacent** for
one reason: an FSM is a fundamentally different authoring artifact — a states/transitions
graph with guards and per-session runtime — not a flat `trigger → action` rule. Conflating
"declare an event / wire a reaction" with "design a state machine" in one instruction would
blur both.

The recommended split: event-agent owns the *flat* CRUD (events, reactions, watches) and can
**mention** that a multi-state workflow wants an FSM, handing off to a future `workflow`/`fsm`
surface (or `fsm$*` directly). `fsm$send` is itself sugar over `event$emit`, so the two
families already compose cleanly at the bus. If a dedicated FSM authoring agent is later
built, it is a *sibling* of event-agent, not a subsumption — same relationship
`config-agent` and `mcp-agent` have.

---

## 12. Open Questions

1. **Should event-agent be allowed to flip gates itself** rather than always routing through
   `config-agent`? Lean no — keeping config writes in one place is the same discipline
   `config-agent` §2 draws — but a one-key `:enable-reactions` toggle is low-risk enough that
   an inline confirm-then-set could be justified. Deferred.
2. **Auto-declare on first reaction?** When a user wires a reaction against an undeclared
   event, should the agent silently `event$define` it (with an inferred schema) or keep
   declaration a separate, offered step? This design keeps it offered (principle 3); revisit
   if users find the extra step noisy.
3. **A unified `event$board` read command?** Today the read sweep is three list calls
   stitched in the instruction. If the "what fires" board becomes the dominant use, a single
   `event$board` command returning events+reactions+watches+gates in one shot would cut
   tokens and make the render deterministic. Additive; not required for v1.
4. **Watch probe library.** Common probes (row count, file mtime, HTTP status) are re-typed
   each time. A small named-probe catalog the agent can offer ("watch a table's row count")
   would speed kind-3 flows. Bikeshed; out of scope for v1.
5. **Dry-run safety for `:as :turn` reactions.** `event$emit` dry-running a `:turn` reaction
   *actually forces a turn*. Should the agent have a "match-only, don't run the action"
   dry-run mode? Would need a command-level `:dry-run?` on `event$emit`. Flagged for the
   reactor, not solvable in event-agent alone.
