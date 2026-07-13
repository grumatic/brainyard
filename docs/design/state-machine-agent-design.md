# State-Machine-Agent — LLM-Mediated CRUD over User-Defined FSMs (definitions + runtime state)

> **Status:** Design / proposal. The underlying **FSM subsystem is fully shipped**
> ([`state-machine-design.md`](./state-machine-design.md), Phases 1–4) — this doc adds a
> *conversational front door* over its CRUD surface, exactly as `config-agent` did for
> `config.edn` and the sibling [`event-agent`](./event-agent-design.md) does for the flat
> event/reaction/watch families. No new mechanism is introduced; the agent orchestrates the
> existing `fsm$*` commands.
> **Scope:** new `components/agent/src/ai/brainyard/agent/common/state_machine_agent.clj`
> (a thin `coact`-derived agent), reusing the already-registered command family in
> `common/fsm.clj` (`fsm-commands`).
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived` (no new BT, no new DSPy
> signature) — same pattern as [`config-agent`](./config-agent-design.md),
> [`event-agent`](./event-agent-design.md), [`mcp-agent`](./mcp-agent-design.md).
> **Sibling of:** `event-agent` (flat events · reactions · watches CRUD) — this agent owns
> the *stateful* graph. Also sibling to `config-agent` (owns the `:enable-fsm` /
> `:enable-scheduler` / `:fsm-allow-code` gates it depends on).
> **Related reading:**
> [`state-machine-design.md`](./state-machine-design.md),
> [`event-bus-and-reactor.md`](./event-bus-and-reactor.md),
> [`event-agent-design.md`](./event-agent-design.md),
> `components/agent/src/ai/brainyard/agent/common/fsm.clj`.

---

## 1. Motivation

An FSM is *the reactor with memory* — a durable current-state + context that gates which
transitions fire (see [`state-machine-design.md`](./state-machine-design.md) §1). That power
comes with the steepest authoring surface in the whole event subsystem, and it has **two
distinct lifecycles** that no single command stitches together:

1. **Authoring the graph** — a `machine.edn` is a states/transitions map with `:initial`,
   per-state `:entry`/`:exit` action lists, transitions keyed under `:on {<event> [{:target
   … :guard … :do …}]}`, `:always`/`:after` eventless transitions, and `:type :final`
   terminals. Getting this right by hand means holding the guard DSL (`:event/match`,
   `:context/gte`, `:agent/idle?`, `:elapsed/gte`, …), the action sinks (`:emit`, `:turn`,
   `:assign`, …), and reachability (every `:target` must be a defined state) in your head at
   once.
2. **Driving & inspecting runtime** — a machine's *state* is per-session
   (`fsm$status`/`fsm$send`/`fsm$reset`), separate from its *definition* (project-scoped).
   The #1 confusion is "I defined it but nothing happens" — usually because the current state
   has no `:on` transition for the event you sent, a guard is silently failing, or (for code
   guards) `:fsm-allow-code` is off. `:enable-fsm` itself defaults **on**, so a defined
   machine runs out of the box — but a user *can* disable it, so it's still worth checking.

Today the only ways in are (a) typing raw `fsm$define` with a hand-built states map, (b)
editing `.brainyard/fsm/<id>/machine.edn` by hand — which skips the normalization
(`normalize-machine` coerces string keys to keywords) and every reachability check — or (c)
`fsm$send` / `ask.sock :op :emit` to poke a running machine with no help diagnosing why it
didn't advance.

There is also no **coherent authoring or audit story**. `fsm$list` shows machines and their
current state; `fsm$status` shows one machine's history — but nobody validates a proposed
graph *before* it lands (unreachable states, a `:target` that names no state, an `:initial`
that doesn't exist, an `:on` event that never gets emitted), and nobody renders "here is the
machine, here is where it is now, here is what would advance it" as one picture.

**Thesis.** Add a CoAct-derived `state-machine-agent` that is **the one chat surface for FSM
CRUD and diagnosis**:

1. **Authors the graph conversationally.** "Model a deploy gate: idle → CI passes → wait for
   my approval → deploying → done" → the agent builds the states/transitions map, validates
   reachability and guards, previews the graph, and writes it — in one flow.
2. **Owns both lifecycles.** Definition CRUD (`fsm$define`/`fsm$remove`/`fsm$list`) *and*
   runtime driving/inspection (`fsm$send`/`fsm$status`/`fsm$reset`), clearly distinguished so
   the user never confuses "edit the graph" with "reset this session's run."
3. **Validates before persisting.** Initial state exists, every `:target` names a defined
   state, no orphan/unreachable states, guard clauses are well-formed, `:on` event names
   coerce (`->event-key`), template vars resolve — all checked and previewed *before* the
   definition lands.
4. **Guards the gates.** `:enable-fsm` and `:enable-scheduler` both default **on**, so a
   defined machine — including timed/eventless (`:always`/`:after`) transitions that ride the
   ticker — runs out of the box; but a user can disable either, so the agent checks the actual
   value rather than assuming. `:fsm-allow-code` defaults **off** (fail-closed), so SCI
   `:guard-code`/`:guard-fn` and `:as :eval` actions are inert until it's enabled. Every write
   surfaces which gates it depends on and their current state, and offers to flip any that are
   off via `config-agent`.
5. **Never re-implements the mechanism.** It calls `fsm$*`; it does not touch
   `.brainyard/fsm/` by hand.

Same minimal-diff pattern as the other specialists: one new agent file, zero new commands
(the `fsm-commands` family already exists), a `state-machine-agent/` dossier directory under
`.brainyard/`.

---

## 2. Design Principles

1. **One CRUD surface, two lifecycles.** Definition writes go through `fsm$define` /
   `fsm$remove`; runtime driving through `fsm$send` / `fsm$reset`; reads through `fsm$list` /
   `fsm$status`. The agent never edits `.brainyard/fsm/<id>/machine.edn` or the per-session
   runtime file by hand.
2. **Read first, write rarely.** Every conversation opens with `fsm$list` (+ `fsm$status`
   for a named machine) and the relevant gate flags. Definition writes happen only after the
   user has seen the proposed graph and confirmed.
3. **Validate the graph, not just the syntax.** Beyond "is this valid EDN": `:initial` names
   a defined state; every transition `:target` names a defined state; no state is unreachable
   from `:initial`; every `:on` event coerces; guard clauses use known keys
   (`:event/match`, `:context/*`, `:agent/*`, `:elapsed/gte`, `:guard-code`/`:guard-fn`);
   `{{context.var}}`/`{{event.key}}` template refs resolve. A dangling `:target` is the #1
   cause of a machine that silently can't advance.
4. **Surface every gate the machine needs.** `:enable-fsm` (the machine runs at all);
   `:enable-scheduler` (only if the graph has `:always`/`:after` transitions);
   `:fsm-allow-code` (only if it uses `:guard-code`/`:guard-fn`/`:as :eval`). State each
   dependency plainly after a write, and offer the `config-agent` handoff — never leave a
   machine that can't advance without saying why.
5. **Distinguish definition edits from runtime resets, loudly.** Editing a machine's graph
   mid-run and resetting a session's current state are different actions with different blast
   radii. The agent names which one it's doing, and warns that a definition edit
   version-mismatches the runtime — the engine resets a machine to `:initial` on a definition
   change (see fsm.clj `ensure-fsm!` re-sync), so an in-flight run is lost.
6. **`fsm$send` is a test tool with real teeth.** It's sugar over `event$emit`, and a
   transition's `:entry`/`:do` actions can **force a turn** (`:as :turn`/`:run`). The agent
   uses `fsm$send` to prove a freshly authored machine advances, but treats sending into a
   live session as a real action and confirms first.
7. **Hand off gate changes to config-agent.** Turning `:enable-fsm` / `:enable-scheduler` /
   `:fsm-allow-code` on or off is a *config* write. The agent calls `config-agent`; it does
   not write `config.edn` itself.
8. **Code guards are opt-in and dangerous — treat them so.** `:guard-code`/`:guard-fn` and
   `:as :eval` run Clojure in a restricted, 1s-bounded clj-sandbox, and are *fail-closed*
   when `:fsm-allow-code` is off (fsm.clj `clause-pass?`). The agent prefers the declarative
   DSL, only proposes code guards when the condition genuinely can't be expressed
   declaratively, and always names the `:fsm-allow-code` dependency.
9. **Dossier per conversation.** Every session writes a markdown dossier under
   `.brainyard/agents/state-machine-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md` recording the
   graph read, the graph written (states + transitions), which gates it needs, and any
   `fsm$send` dry-run trace. Mirrors the explore/plan/config/event agent pattern.
10. **No clone-self recursion.** Like the other CoAct-derived specialists, this agent
    excludes `query$clone`. Cross-agent dispatch via flat `(call-tool "<other>" {…})` is
    fine — `config-agent` for gates, `event-agent` to wire a reaction onto a `:fsm/*`
    lifecycle event, `explore-agent` to find who emits a trigger event.

---

## 3. Position in the Agent Stack

```
   coact-agent  (parent — full BT, sandbox, router, accumulator)
     ├─ explore-agent        (read-mostly discovery — "who emits :ci/passed?")
     ├─ config-agent         (config CRUD — owns :enable-fsm / :enable-scheduler / :fsm-allow-code)
     ├─ event-agent          (flat events · reactions · watches CRUD)
     └─ state-machine-agent  (THIS — FSM definitions + runtime state CRUD + diagnosis)
```

`state-machine-agent` is a peer of the other CoAct-derived specialists, and especially close
to `event-agent`: an FSM *consumes* bus events (its transition triggers) and *emits* them
(`:fsm/transition`, `:fsm/entered`, `:fsm/final`), so the two compose at the bus. The split:

- **`event-agent`** owns the *flat* `trigger → action` vocabulary (events, reactions,
  watches) and the events an FSM keys off. When a machine should react to something happening,
  or emit a `:fsm/*` event into a reaction, that reaction is `event-agent`'s to author.
- **`state-machine-agent`** owns the *stateful graph*: states, transitions, guards, context,
  runtime. It hands off to `event-agent` when a flat reaction is the right tool, and *mentions*
  when a problem is really flat (a one-shot reaction doesn't need a whole machine).
- **`config-agent`** owns the gates. This agent proposes; config-agent writes.
- **`explore-agent`** finds emitters of a trigger event before a destructive `fsm$remove`.

It does not subclass them and they do not call it.

---

## 4. Capability Surface

Six conversation kinds the instruction (§6) tells the LLM to recognise. User-facing
categories, not branches in the loop.

1. **Show me the machines.** "What state machines do I have?" "Where is the deploy gate
   right now?" Read sweep: `fsm$list` (+ `fsm$status <id>`) + gate flags → one rendered board
   with each machine's current state and last transition → done.
2. **Author a machine.** "Model a deploy gate: idle → CI passes → wait for approval →
   deploying → done." Build the states/transitions map → validate reachability + guards →
   preview the graph → check gates → write via `fsm$define` → optionally dry-run with
   `fsm$send`.
3. **Edit an existing machine.** "Add a `rollback` state." "Make it retry three times before
   giving up." Read the current graph → propose the diff → **warn the edit resets the running
   machine** → re-`fsm$define` (upsert).
4. **Drive / inspect at runtime.** "Send `ci/passed` to the gate." "Reset it to idle." Pure
   runtime ops via `fsm$send` / `fsm$reset` — no definition change — with the blast-radius
   note when a resulting `:entry` action forces a turn.
5. **Diagnose a stuck machine.** "I sent the event but nothing moved." Read the machine, its
   current state, and the gates; check the current state has an `:on` for that event; evaluate
   the guard against the payload/context; report the specific cause.
6. **Retire.** "Remove the deploy gate." `fsm$remove` after offering an `explore-agent`
   sweep for who emits its triggers, and noting the runtime state goes with it.

What the agent must NOT do:

- Flip `:enable-fsm` / `:enable-scheduler` / `:fsm-allow-code` itself (hand to
  `config-agent`).
- Send an event into a live session, or `fsm$reset` a machine mid-run, without confirming.
- Edit `.brainyard/fsm/` by hand.
- Reach for `:guard-code`/`:guard-fn` when a declarative guard would do, or author code
  guards without naming the `:fsm-allow-code` dependency.

---

## 5. Tool Roster

`state-machine-agent` binds the standard CoAct primitives plus the already-registered
`fsm-commands` family. **No new commands are defined.**

Inherited from the shared registry:

| Tool | Used for | Notes |
|---|---|---|
| `read-file` / `grep` | Reading probe scripts, scanning for emitters of a trigger | Standard sandbox tool surface. |
| `agent-runtime$config` | **Read** the gate flags (`:enable-fsm`, `:enable-scheduler`, `:fsm-allow-code`) | Read-only here; *writes* go through `config-agent`. |
| `config-agent` (call-tool) | Flip a gate on/off | The only path through which a gate changes while this agent runs. |
| `event-agent` (call-tool) | Wire a reaction onto a `:fsm/*` lifecycle event; declare a trigger event | Flat trigger→action work belongs to event-agent. |
| `explore-agent` (call-tool) | "Find every place that emits `:ci/passed`" | Read-only discovery before a destructive remove. |

FSM commands (already registered — `fsm/fsm-commands` is folded into `all-common-commands` in
`common/commands.clj`):

| Command | Store / target | Effect |
|---|---|---|
| `fsm$define` | `.brainyard/fsm/<id>/machine.edn` | Upsert a machine: `:id` (lowercase-kebab), `:initial`, `:states {state -> {:on {event [{:target … :guard … :do …}]} :entry […] :exit […] :type :final}}`, optional `:context`. `normalize-machine` coerces string keys to keywords. |
| `fsm$list` | — | List machines with `:initial`, current `:state` (this session), and state names. |
| `fsm$status` | per-session runtime | One machine's current `:state`, `:context`, and recent `:history` (transitions). |
| `fsm$send` | bus (sugar over `event$emit`) | Fire a trigger event and report which machines advanced (`{:sent :advanced [{:machine :from :to}]}`). Installs FSM handlers on demand, so "enable then send" works in one turn. |
| `fsm$reset` | per-session runtime | Reset a machine's runtime state to `:initial` for this session. |
| `fsm$remove` | `.brainyard/fsm/<id>/` | Remove a machine definition and its runtime state. |

The guard DSL a transition's `:guard` can use (declarative, from fsm.clj `clause-pass?`):

| Clause | Passes when |
|---|---|
| `:event/match {…}` | the event payload is a superset of the map |
| `:context/= {:k v}` · `:context/gte {:k n}` | context var equals / is ≥ |
| `:context/all [:a :b]` · `:context/any [:a :b]` | all / any context vars are truthy |
| `:agent/idle?` · `:agent/running?` | the agent is idle / running |
| `:elapsed/gte <ms>` | ≥ ms elapsed in the current state (drives `:after`) |
| `:guard-code "<expr>"` · `:guard-fn "(fn [ctx] …)"` | **opt-in** SCI predicate (needs `:fsm-allow-code`; fail-closed otherwise) |

A `:guard` map is AND over its clauses; a vector is AND over sub-guards; `nil` accepts.

The action sinks a `:do` / `:entry` / `:exit` can use (reactor sinks + FSM-specific):

| Action | Effect | Interrupts a turn? |
|---|---|---|
| `[:assign {:k v \| [:inc] \| [:from-event :k]}]` | mutate context (the machine's memory) | no |
| `[:emit <event>]` / `[:fire-hook <event>]` | fire another event (drives reactions / other machines) | no |
| `[:context …]` / `[:memory …]` / `[:artifact …]` | reactor passive sinks | no |
| `[:eval "<code>"]` | **opt-in** SCI action; a returned map merges into context (needs `:fsm-allow-code`) | no |
| `[:turn "…"]` / `[:run …]` | `submit-turn` — forces a turn now | **yes** |

---

## 6. Instruction Shape

The shared CoAct instruction template (see `common/coact_agent.clj` and
[`react-coact-unification-plan.md`](./react-coact-unification-plan.md)) plus five
state-machine-agent guidances:

1. **Open with a read sweep.** Before proposing anything, call `fsm$list` (and `fsm$status
   <id>` for a named machine) and read the three gate flags via `agent-runtime$config`.
   Render "the machines and where they are" and reason against that.
2. **Classify into one of the six capability kinds (§4) before acting.** In particular,
   distinguish *authoring/editing the graph* (`fsm$define`) from *driving runtime*
   (`fsm$send`/`fsm$reset`) — these are the two lifecycles users conflate. And ask: does this
   really need a machine, or is it a flat reaction (hand to `event-agent`)?
3. **For any definition write, follow read → build → validate → preview → (gate check) →
   confirm → apply.** Validation means: `:initial` and every `:target` name defined states;
   no unreachable state; `:on` event names coerce; guard clauses are known keys; template
   refs resolve. Show the proposed graph (states + transitions, ideally as a small diagram or
   an indented outline) inline before writing.
4. **State the gate outcome and the reset consequence plainly.** After a definition write,
   say whether the machine will run given `:enable-fsm`, whether it needs `:enable-scheduler`
   (has `:always`/`:after`) or `:fsm-allow-code` (has code guards/actions), and — for an
   *edit* — that the running machine reset to `:initial`. Offer the `config-agent` handoff for
   any off gate.
5. **Name the blast radius before driving runtime.** A `:as :turn` entry/`:do` action forces
   a turn; `fsm$reset` discards an in-flight run. Confirm before `fsm$send` into a live
   session or `fsm$reset` on a mid-run machine.

The instruction explicitly forbids:

- Writing `:enable-fsm` / `:enable-scheduler` / `:fsm-allow-code` directly (use
  `config-agent`).
- Hand-editing `.brainyard/fsm/`.
- Authoring `:guard-code`/`:guard-fn`/`:as :eval` when a declarative guard would do, or
  without naming the `:fsm-allow-code` dependency.
- Writing a machine with a dangling `:target`, a missing `:initial`, or an unreachable state
  — validation failures are surfaced, not worked around.

---

## 7. Store Layout & Dossier

The agent reads and writes through `fsm$*`; the stores it touches (as-built in fsm.clj —
definition project-scoped, runtime per-session, atomic EDN writes):

```
<project>/.brainyard/
├── fsm/<id>/
│   ├── machine.edn                 ;; fsm$define — the definition (project-scoped)
│   └── runtime/<sid>.edn           ;; per-session current state + context + history
└── agents/state-machine-agent/
    ├── dossiers/
    │   └── 20260713-153012-deploy-gate.md
    └── INDEX.md                     ;; append-only, last 100 entries
```

**Dossier.** One markdown file per conversation, frontmatter matching the sibling agents:

```markdown
---
agent: state-machine-agent
session-id: 2026-07-13-fsm-4c7d1
question: "Model a deploy gate: idle → CI passes → wait for approval → deploying → done"
started: 2026-07-13T15:29:50Z
ended: 2026-07-13T15:30:31Z
gates: {enable-fsm: true, enable-scheduler: true, fsm-allow-code: false}
defined:
  - machine: deploy-gate
    states: [idle, awaiting-approval, deploying, done]
    triggers: [ci/passed, ci/failed, user/approved, user/held, deploy/done, deploy/failed]
removed: []
dry-run:
  - {send: ci/passed, advanced: [{machine: deploy-gate, from: idle, to: awaiting-approval}]}
next-steps: []
---

## What I read
- fsm$list → [] (no machines)
- gates → :enable-fsm true, :enable-scheduler true, :fsm-allow-code false

## What I defined
deploy-gate (initial :idle)
  idle              --ci/passed-->        awaiting-approval
  idle              --ci/failed-->        idle  (:assign attempts++ ; :emit notify/ci-failed)
  awaiting-approval --user/approved-->    deploying   guard {:agent/idle? true}
  awaiting-approval --user/held-->        idle
  deploying         --deploy/done-->      done (final)
  deploying         --deploy/failed-->    idle

## Validation
- initial :idle defined ✓ · all :targets defined ✓ · no unreachable states ✓
- all :on events coerce ✓ · guard {:agent/idle? true} well-formed ✓

## Gate status
- :enable-fsm ON (default) → machine runs live; advances on its trigger events.
- No :always/:after → :enable-scheduler not exercised. No code guards → :fsm-allow-code not required.

## Dry-run
- fsm$send :event ci/passed → advanced deploy-gate idle → awaiting-approval ✓
```

---

## 8. Conversation Patterns (few-shot worked examples)

The canonical worked flows the instruction points the LLM at. Each shows the tool sequence
and the shape of the final answer.

### 8.1 Author a machine (capability kind 2)

> **User:** model a deploy gate — idle, CI passes, wait for my approval, deploy, done

1. `fsm$list` → `[]`; `agent-runtime$config` → `:enable-fsm true`, `:enable-scheduler true`,
   `:fsm-allow-code false`.
2. Build the graph and **validate before writing**: `:initial :idle` is defined; every
   `:target` (`:awaiting-approval`, `:deploying`, `:done`, `:idle`) names a defined state; no
   orphan states; the guard `{:agent/idle? true}` uses a known clause.
3. Preview the graph inline (the outline in the §7 dossier), then on confirm:
   ```clojure
   fsm$define :id "deploy-gate" :initial "idle" :context {:attempts 0}
     :states {:idle {:on {:ci/passed [{:target :awaiting-approval}]
                          :ci/failed [{:target :idle :do [[:assign {:attempts [:inc]}]
                                                          [:emit :notify/ci-failed]]}]}}
              :awaiting-approval {:entry [[:turn "CI passed — reply approve to ship, or hold."]]
                                  :on {:user/approved [{:guard {:agent/idle? true} :target :deploying}]
                                       :user/held     [{:target :idle}]}}
              :deploying {:entry [[:emit :deploy/start]]
                          :on {:deploy/done   [{:target :done}]
                               :deploy/failed [{:target :idle :do [[:emit :notify/deploy-failed]]}]}}
              :done {:type :final}}
   ```
4. `fsm$send :event "ci/passed"` → dry-run; `{:sent :ci/passed :advanced [{:machine "deploy-gate" :from :idle :to :awaiting-approval}]}`.
5. Answer:
   > Defined `deploy-gate` (states: idle · awaiting-approval · deploying · done). Validated:
   > all targets reachable, initial state defined. It's **live now** — `:enable-fsm` is on by
   > default, so it'll advance as its trigger events fire. No timed transitions (scheduler not
   > exercised); no code guards (`:fsm-allow-code` not needed). Note: entering
   > `awaiting-approval` *forces a turn* asking you to approve, so it'll interrupt the moment
   > `ci/passed` fires.

### 8.2 A timed transition — surface the extra gate (kind 2)

> **User:** if it sits waiting for approval more than 30 minutes, auto-cancel back to idle

1. Read the machine. This is a *timed, eventless* transition → `:after` on
   `awaiting-approval`.
2. Add to the state:
   `:awaiting-approval {... :after [{:after 1800000 :target :idle :do [[:emit :notify/approval-timeout]]}]}`.
3. Re-`fsm$define` (upsert) with the validation pass, and **name the extra gate + reset**:
   > Added a 30-minute timeout on `awaiting-approval` → idle. This is a *timed* transition, so
   > it only advances while **`:enable-scheduler`** (the ticker) is on — it's on by default, so
   > you're covered, but flag it if you've disabled the scheduler. Also: editing the graph
   > **reset the running machine to `idle`** for this session.

### 8.3 A condition that needs code — prefer declarative, gate the escape hatch (kind 2/3)

> **User:** only allow deploy if the changed-files count from the CI payload is under 50

1. Try declarative first: `:context/*` and `:event/match` compare equality/thresholds on
   named keys, but "< 50" over an event field isn't expressible as a declarative clause here.
2. Propose a `:guard-code`, and **name the dependency + the risk**:
   > This needs a code guard — declarative clauses can't do "< 50 over a payload field." I'd
   > add `:guard-code "(< (-> ctx :event :changed-files) 50)"`. That runs Clojure in a
   > sandboxed, 1s-bounded evaluator and is **fail-closed unless `:fsm-allow-code` is ON**
   > (it's currently off). Want me to add the guard and have config-agent enable
   > `:fsm-allow-code`? If you'd rather avoid code, we can `:assign` a `:small-change?` flag
   > upstream and gate on `:context/all [:small-change?]` instead.
3. Proceed only on explicit confirmation; record the `:fsm-allow-code` dependency in the
   dossier.

### 8.4 Drive runtime (capability kind 4)

> **User:** send ci/passed to the gate

1. `fsm$status :id "deploy-gate"` → current `:state :idle`; `:enable-fsm true`.
2. Note the blast radius before sending: the resulting `awaiting-approval` entry **forces a
   turn**. Confirm.
3. `fsm$send :event "ci/passed"` → `{:advanced [{:machine "deploy-gate" :from :idle :to :awaiting-approval}]}`.
4. Answer: "Advanced `deploy-gate` idle → awaiting-approval. It just injected the approval
   prompt turn."

### 8.5 Diagnose a stuck machine (capability kind 5)

> **User:** I sent user/approved but the gate didn't move to deploying

1. `fsm$status :id "deploy-gate"` → current `:state :awaiting-approval`; history shows no
   transition on the last `:user/approved`.
2. `agent-runtime$config` → `:enable-fsm true` (gate fine).
3. Inspect the transition: `awaiting-approval` has
   `:user/approved [{:guard {:agent/idle? true} :target :deploying}]`. The guard requires the
   agent to be **idle**, but the event arrived while the agent was mid-turn (running) — so the
   guard failed and the first-match transition was skipped.
4. Answer, with the specific cause:
   > Found it — the `user/approved` transition is guarded by `{:agent/idle? true}`, and the
   > agent was busy when the event arrived, so the guard blocked it. Re-send `user/approved`
   > now that it's idle (I can do that), or drop the `:agent/idle?` guard if approvals should
   > apply even mid-turn. Which?

The diagnostic checklist the instruction encodes, in order: **`:enable-fsm` off? →
`:enable-scheduler` off but the transition is `:after`/`:always`? → current state has no
`:on` entry for this event? → event-name typo (`->event-key` mismatch)? → a guard silently
failing (`:agent/idle?`, `:context/*`, or a fail-closed `:guard-code` with `:fsm-allow-code`
off)? → machine already in a `:final` state?**

### 8.6 Show me the machines (capability kind 1)

> **User:** what state machines do I have and where are they?

1. `fsm$list` + `fsm$status` per machine + the three gates, one sweep.
2. Render one board:
   > **deploy-gate** — now: `awaiting-approval` (since 2m ago; last: idle→awaiting-approval on
   > ci/passed). States: idle · awaiting-approval · deploying · done.
   > **onboarding-flow** — now: `step-2` · 4 states.
   > **Gates:** fsm **ON** · scheduler ON · fsm-allow-code off

No writes, no dossier beyond the read record. A "where are my machines" question must not
turn into a multi-turn flow.

### 8.7 Retire a machine (capability kind 6)

> **User:** remove the deploy gate

1. `fsm$status` → confirm the id and note it's mid-run (`awaiting-approval`).
2. Offer the `explore-agent` sweep: "Want me to check what still emits `ci/passed` /
   `deploy/done` first, so nothing's left firing into a gone machine?"
3. On confirm: `fsm$remove :id "deploy-gate"` → `{:removed "deploy-gate"}`.
4. Dossier records the removal. Answer confirms the definition **and** this session's runtime
   state are gone, and that the trigger events themselves still fire on the bus (only the
   machine that consumed them is removed).

---

## 9. Edge Cases

1. **`:enable-fsm` disabled by the user.** It defaults on, so this is uncommon — but if a
   read shows it off, state plainly the machine is stored-but-inert and offer the
   `config-agent` handoff. Never imply a machine advances when the gate is off.
2. **Dangling `:target` / missing `:initial` / unreachable state.** Caught at validation
   *before* `fsm$define`. Surface the specific defect ("`:target :rollback` names no defined
   state") and offer to fix, rather than writing a broken graph.
3. **Definition edit resets the running machine.** The engine version-stamps definitions and
   resets to `:initial` on a graph change (`ensure-fsm!` re-sync). Warn before every edit that
   an in-flight run is lost; offer to note the current state first.
4. **Timed/eventless transition with `:enable-scheduler` off.** `:always`/`:after`
   transitions never fire without the ticker. Flag at author time — same gate discipline as
   `:enable-fsm`.
5. **Code guard/action with `:fsm-allow-code` off.** `:guard-code`/`:guard-fn` are
   *fail-closed* (the guard evaluates to false), and `:as :eval` is skipped. Warn that the
   machine will silently not advance/act until the gate is on; prefer a declarative
   alternative when one exists.
6. **`:as :turn` entry action forces a turn.** Any state whose `:entry` injects a turn will
   interrupt the moment the machine enters it. Name this when authoring and before any
   `fsm$send` that would trigger it.
7. **Cascade / transition loop** (`a → :emit → b → :emit → a`). Rely on the engine's bounded
   re-entrancy depth cap (`max-depth` in fsm.clj, which caps synchronous `:emit` re-entry and
   the eventless tick chain); if a graph looks like it could ping-pong, flag it and suggest a
   guard or context counter to break the loop.
8. **Removing a machine other code drives.** Before `fsm$remove`, offer an `explore-agent`
   sweep for emitters of its trigger events; removing the machine doesn't stop those events
   firing on the bus.
9. **Invalid `:id`.** `fsm$define` requires lowercase-kebab (`valid-id?`); surface the
   constraint and suggest a corrected id rather than passing the error through raw.
10. **User pastes a raw states map.** Accept, run the same validation/preview/confirm flow —
    don't fast-path around reachability checks just because the input was pre-structured.
    `normalize-machine` will coerce string keys, but the agent still validates targets.

---

## 10. Testing Plan

Three surfaces, matching the sibling agents.

1. **Unit — validation + command pass-through.** The `fsm$*` commands already have coverage
   (`agent/fsm_test.clj`). This agent adds tests only for its *orchestration*: the graph
   validators (initial-defined, all-targets-defined, reachability, guard-clause-known,
   event-name-coercion) as pure functions, and gate-status rendering.
2. **Integration — CoAct loop with a stubbed LLM.** Canned LLM turns drive the agent through
   each capability kind (§4). Assert the resulting on-disk `machine.edn`, the per-session
   runtime after a dry-run `fsm$send`, and the dossier frontmatter match goldens — especially
   the `gates:`, `defined:`, and `dry-run:` fields.
3. **End-to-end — manual / CI-opt-in.** Real `bb tui run -a state-machine-agent` against a
   scratch `.brainyard/`. Exercises: author the `deploy-gate`, dry-run `ci/passed`, flip
   `:enable-fsm` through the config-agent handoff, confirm the live transition and the
   forced-turn entry, then `fsm$reset`. Slow; tagged `:slow`.

The dossier is its own contract — every test asserts the documented frontmatter fields are
populated.

---

## 11. Relationship to event-agent (deliberate split)

The FSM rides the *same event bus* as the flat reactor — `fsm$send` is literally sugar over
`event$emit`, and every transition fires `:fsm/transition` / `:fsm/entered` / `:fsm/final`
back onto the bus. So why two agents rather than one "event everything" agent?

Because the **authoring artifact is fundamentally different**. `event-agent`'s artifacts are
*flat* `trigger → action` rules — an event declaration, a one-line reaction, a watch probe.
An FSM is a *states/transitions graph with guards, context, and per-session runtime* — a
different mental model, a different validation surface (reachability, initial state, guard
DSL), and a different failure mode (a machine *stuck in a state* vs. a reaction *that never
matched*). Folding both into one instruction would blur the read sweep, the validation, and
the diagnosis for both.

The clean seam: **flat lives in `event-agent`, stateful lives here**, and the two compose at
the bus. When this agent needs a flat reaction on a `:fsm/*` lifecycle event (e.g. "when
`deploy-gate` reaches `done`, post to Slack"), it hands the reaction to `event-agent`. When
`event-agent` sees a user describing a multi-state workflow ("first this, then wait, then
that, unless…"), it mentions that a machine — this agent — is the better fit. Same
sibling relationship `config-agent` and `mcp-agent` have: adjacent, composing, never
subsuming.

---

## 12. Open Questions

1. **Should this agent flip `:enable-fsm` itself** rather than always routing through
   `config-agent`? Lean no — one place for config writes — but a single low-risk toggle with
   an inline confirm could be justified for the common "define then run" flow. Deferred.
2. **A `fsm$validate` command?** Today reachability/guard validation lives in the
   instruction as agent logic. If graph authoring becomes the dominant use, a command-level
   `fsm$validate` returning `{:ok? :errors […]}` (unreachable states, dangling targets,
   unknown guard clauses) would make validation deterministic and reusable by
   `fsm$define` itself. Additive; not required for v1.
3. **Diagram rendering.** The preview is an indented outline today; a Mermaid `stateDiagram`
   render of the proposed graph would make review far easier. Nice-to-have, out of scope for
   v1.
4. **Definition-edit migration.** The engine resets a machine to `:initial` on a graph change
   (or a declared `:migrate`). Should this agent help author a `:migrate` map so an in-flight
   run survives a graph edit, instead of always resetting? Flagged for the FSM engine, not
   solvable in the agent alone.
5. **Statechart extensions (Phase 5).** Nested/parallel/history states aren't built yet
   (state-machine-design.md §9). When they land, the authoring surface — and this agent's
   validation — grows to match. Tracked against the engine roadmap.
