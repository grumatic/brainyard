# Schedule-Agent — LLM-Mediated CRUD over Time-Triggered Prompt Jobs

> **Status:** Design / proposal. The underlying **scheduler subsystem is fully shipped**
> (`common/schedule.clj`, R2 — [`hermes-comparison.md`](./hermes-comparison.md)) — this doc
> adds a *conversational front door* over its CRUD surface, exactly as `config-agent` did for
> `config.edn` and the siblings [`event-agent`](./event-agent-design.md) /
> [`state-machine-agent`](./state-machine-agent-design.md) do for their subsystems. No new
> mechanism is introduced; the agent orchestrates the existing `schedule$*` commands.
> **Scope:** new `components/agent/src/ai/brainyard/agent/common/schedule_agent.clj`
> (a thin `coact`-derived agent), reusing the `schedule$*` half of `schedule-commands` in
> `common/schedule.clj`. **Watches (`watch$*`) are deliberately out of scope — they belong to
> `event-agent`** (see §11).
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived` (no new BT, no new DSPy
> signature) — same pattern as [`config-agent`](./config-agent-design.md),
> [`event-agent`](./event-agent-design.md), [`mcp-agent`](./mcp-agent-design.md).
> **Sibling of:** `event-agent` (events · reactions · watches) and `state-machine-agent`
> (FSMs) — this agent owns the *time-triggered prompt job*, the one scheduler concern that
> isn't about the event bus. Also sibling to `config-agent` (owns the `:enable-scheduler`
> gate it depends on).
> **Related reading:**
> [`hermes-comparison.md`](./hermes-comparison.md),
> [`event-bus-and-reactor.md`](./event-bus-and-reactor.md),
> [`event-agent-design.md`](./event-agent-design.md),
> `components/agent/src/ai/brainyard/agent/common/schedule.clj`.

---

## 1. Motivation

The scheduler lets a user park a prompt on a clock: *"every weekday at 9am, summarize
yesterday's commits"* → a daemon ticker runs that prompt through a `by` agent and drops the
output to a file sink. It's a small, sharp feature — and its raw surface (`schedule$add`
with a 5-field cron string, `:at` epoch-ms, an agent/model/sink override, plus
enable/disable/run-now/run-due) is exactly the kind of thing an LLM front door makes far more
usable. The friction today:

1. **Cron is a foreign language.** `schedule$add :cron "0 9 * * 1-5"` means "9am on
   weekdays," but the user thinks in the second phrasing, not the first. Translating intent →
   5-field cron (and back, to confirm) is precisely what an agent should do.
2. **One-shot vs recurring vs "in N hours" are three different arguments.** A recurring job
   is `:cron`; a specific future moment is `:at <epoch-ms>`; "remind me in two hours" is
   `:at (+ now 2h)`. The user shouldn't have to know which knob maps to which, or compute
   epoch-ms by hand.
3. **The firing model has a crucial caveat that's easy to miss.** The ticker runs
   **in-process while a `by` session is open** — it is *not* an OS-level cron. A job scheduled
   for 3am only fires if a session is running at 3am (otherwise it fires late, on the next
   session's catch-up pass). Nobody surfaces this, so "why didn't my job run?" is a common,
   confusing failure — and the honest answer ("no session was open") needs saying up front.
4. **No coherent read/audit.** `schedule$list` returns a slice; nobody stitches "here are
   your jobs, when each next fires, when each last ran and whether it succeeded, and is the
   ticker even on" into one picture.

Today the only ways in are typing the raw commands (with hand-built cron and epoch-ms) or
editing `.brainyard/schedule/<id>/spec.edn` by hand (skipping cron validation and next-fire
computation entirely). There's also **conceptual overlap with Cowork's built-in
scheduled-tasks** feature and the `schedule` skill — so part of this agent's job is to be
clear about *which* scheduler it drives (the project-scoped, brainyard-agent, session-bound
one) and when the Cowork feature is the better tool (§10, edge cases).

**Thesis.** Add a CoAct-derived `schedule-agent` that is **the one chat surface for
brainyard's time-triggered prompt jobs**:

1. **Translates intent to schedule.** "Every weekday morning" → `:cron "0 9 * * 1-5"`, with
   the cron echoed back in plain words for confirmation. "In two hours" → `:at <computed>`.
2. **Owns read + audit.** A single `schedule$list` sweep rendered as one board — next fire,
   last run + status, sink, enabled — with the ticker's on/off state inline.
3. **Surfaces the session-bound firing model, always.** Every write states plainly that the
   job fires only while a `by` session is open (unless the ticker is off), and that
   `schedule$run-now` / `schedule$run-due` fire on demand regardless.
4. **Validates before persisting.** Cron parses (5 fields, valid ranges), `:at` is a future
   moment, agent/model/sink are known — checked and the computed *next fire time* previewed
   before the job lands.
5. **Never re-implements the mechanism.** It calls `schedule$*`; it does not touch
   `.brainyard/schedule/` by hand.

Same minimal-diff pattern as the other specialists: one new agent file, zero new commands
(the `schedule$*` family already exists), a `schedule-agent/` dossier directory under
`.brainyard/`.

---

## 2. Design Principles

1. **One CRUD surface for time jobs.** Job writes go through `schedule$add` /
   `schedule$remove` / `schedule$enable` / `schedule$disable`; manual firing through
   `schedule$run-now` / `schedule$run-due`; reads through `schedule$list`. The agent never
   edits `.brainyard/schedule/<id>/spec.edn` by hand and never bypasses cron validation.
2. **Read first, write rarely.** Every conversation opens with `schedule$list` + the
   `:enable-scheduler` gate. Writes happen only after the user has seen the job and its
   computed next-fire and confirmed.
3. **Translate, then confirm in plain words.** Never leave the user staring at a raw cron.
   Author from intent, then echo it back — "`0 9 * * 1-5` → 9:00 AM, Monday through Friday" —
   and show the concrete *next* fire timestamp. A silently-wrong cron is the #1 scheduling
   bug.
4. **The firing model is a first-class caveat, not fine print.** The ticker runs in-process
   while a session is open (`:enable-scheduler` on, default). State on every write: (a)
   whether the ticker is on, and (b) that a job only fires unattended while a `by` session is
   running — a 3am job needs a 3am session. Offer `schedule$run-now`/`run-due` for the
   on-demand path, and name Cowork's scheduled-tasks as the alternative when the user really
   wants OS-level, session-independent firing (§10).
5. **Name the blast radius.** A scheduled job **runs an LLM agent unattended** and spends
   tokens on a clock. State the agent it runs as (default `coact-agent`), the sink it delivers
   to (default `file`), and — for recurring jobs — that it repeats until disabled/removed.
6. **`run-now` is the dry-run.** `schedule$run-now` executes the job immediately *without*
   advancing its next-fire — the natural "let's prove it works" tool. Use it to validate a
   freshly authored job; treat a real unattended run as the thing it's scheduling.
7. **Hand off gate changes to config-agent.** Turning `:enable-scheduler` (or
   `:scheduler-tick-ms`) on/off is a *config* write. The agent calls `config-agent`; it does
   not write `config.edn` itself.
8. **Dossier per conversation.** Every session writes a markdown dossier under
   `.brainyard/agents/schedule-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md` recording the jobs
   read, the job written (prompt + trigger + sink), the computed next-fire, the ticker state,
   and any `run-now` result. Mirrors the explore/plan/config/event agent pattern.
9. **No clone-self recursion.** Like the other CoAct-derived specialists, this agent excludes
   `query$clone`. Cross-agent dispatch via flat `(call-tool "<other>" {…})` is fine —
   `config-agent` for the gate, `event-agent` when the user actually wants a *condition*
   trigger (a watch) rather than a *time* trigger.
10. **Be honest about scope vs Cowork.** brainyard's scheduler is project-scoped, runs a
    brainyard agent's prompt, and is session-bound. When the user wants a personal,
    always-on, cross-app reminder, say so and point at Cowork's scheduled-tasks / the
    `schedule` skill rather than forcing a brainyard job into that shape.

---

## 3. Position in the Agent Stack

```
   coact-agent  (parent — full BT, sandbox, router, accumulator)
     ├─ config-agent          (config CRUD — owns :enable-scheduler / :scheduler-tick-ms)
     ├─ event-agent           (events · reactions · WATCHES — condition→emit triggers)
     ├─ state-machine-agent   (FSMs — stateful graphs)
     └─ schedule-agent        (THIS — time-triggered prompt jobs)
```

`schedule-agent` is a peer of the other CoAct-derived specialists. The clean boundary that
separates it from its closest neighbor, `event-agent`:

- **`schedule-agent`** owns **time** triggers that **run a prompt** — "at 9am, do this work."
  The output goes to a delivery sink (file/stdout). This is `schedule$*`.
- **`event-agent`** owns **condition** triggers that **fire an event** — the `watch$*` family
  (probe → emit), which also rides the scheduler's ticker but exists to feed the event bus,
  not to run a prompt. When a user says "watch the orders table and *fire an event* when it
  changes," that's event-agent, not this agent.
- **`config-agent`** owns the `:enable-scheduler` gate. This agent proposes; config-agent
  writes.

It hands off to `event-agent` the moment the trigger is a *condition* rather than a *clock*,
and mentions Cowork's scheduled-tasks when the user needs session-independent firing. It does
not subclass any sibling and they do not call it.

---

## 4. Capability Surface

Six conversation kinds the instruction (§6) tells the LLM to recognise. User-facing
categories, not branches in the loop.

1. **Show me my jobs.** "What's scheduled?" "When does the standup summary next run?" Read
   sweep: `schedule$list` + the `:enable-scheduler` gate → one rendered board (next fire,
   last run/status, sink, enabled) → done.
2. **Schedule a recurring job.** "Every weekday at 9am, summarize yesterday's commits."
   Translate to `:cron` → validate → preview the cron in plain words + next fire → check the
   gate → `schedule$add` → optionally `schedule$run-now` to prove it.
3. **Schedule a one-shot.** "Remind me in two hours to send the release notes." "Run the
   backup check tonight at 11." Compute `:at <epoch-ms>` → preview the concrete timestamp →
   `schedule$add`.
4. **Tune / pause / fire now.** "Move it to 8am." "Pause the nightly job." "Run it now."
   `schedule$disable` / re-`add` with new `:cron` / `schedule$run-now`.
5. **Diagnose a job that didn't run.** "My 3am job never ran." Check: ticker on? was a
   session open at fire time? cron correct? job enabled? — report the specific cause.
6. **Retire.** "Delete the standup job." `schedule$remove` (drops the spec and its run logs).

What the agent must NOT do:

- Flip `:enable-scheduler` / `:scheduler-tick-ms` itself (hand to `config-agent`).
- Edit `.brainyard/schedule/` by hand.
- Author a `watch$*` (condition→emit) — that's `event-agent`.
- Imply a job will fire at its clock time without stating the session-bound caveat.
- Schedule an unattended LLM job without naming the agent it runs and the token cost of a
  recurring run.

---

## 5. Tool Roster

`schedule-agent` binds the standard CoAct primitives plus the `schedule$*` commands (the
non-watch half of the already-registered `schedule-commands` in `common/schedule.clj`).
**No new commands are defined.**

Inherited from the shared registry:

| Tool | Used for | Notes |
|---|---|---|
| `read-file` / `grep` | Inspecting a job's run-log output, scanning a prompt | Standard sandbox tool surface. |
| `bash` (allowlisted) | Computing "in N hours" as epoch-ms, `date` math | Diagnostic; the job itself runs on the ticker. |
| `agent-runtime$config` | **Read** `:enable-scheduler` and `:scheduler-tick-ms` | Read-only here; *writes* go through `config-agent`. |
| `config-agent` (call-tool) | Flip `:enable-scheduler` / tune `:scheduler-tick-ms` | The only path through which the gate changes while this agent runs. |
| `event-agent` (call-tool) | When the trigger is really a *condition* → author a `watch$*` | Time→prompt is this agent; condition→emit is event-agent. |

Schedule commands (already registered — `schedule/schedule-commands` is folded into
`all-common-commands` in `common/commands.clj`; watches are excluded from this agent's use):

| Command | Store / target | Effect |
|---|---|---|
| `schedule$add` | `.brainyard/schedule/<id>/spec.edn` | Create a job: `:prompt` + (`:cron "m h dom mon dow"` \| `:at <epoch-ms>`), optional `:title`, `:agent` (default `coact-agent`), `:model`, `:provider`, `:sink` (`file` default \| `stdout`), `:enabled`. Validates cron, computes `:next-fire`. |
| `schedule$list` | — | List non-watch jobs: `:id :title :cron :fire-at :enabled :next-fire :last-run :last-status :sink`. |
| `schedule$remove` | `.brainyard/schedule/<id>/` | Remove a job and its run logs. |
| `schedule$enable` / `schedule$disable` | spec | Toggle without deleting; enable recomputes `:next-fire`. |
| `schedule$run-now` | — | Run the job immediately, ignoring its schedule and **not** advancing `:next-fire`. The dry-run tool. |
| `schedule$run-due` | — | Run every job currently due (the same pass the ticker runs) — the manual catch-up. |

The firing engine (from schedule.clj, for the agent's mental model):

- A **daemon ticker** (`ensure-scheduler!`) wakes every `:scheduler-tick-ms` (default 60000)
  **while a `by` session is open**, runs `run-due!`, and fires a `:scheduler/tick` pulse
  (which also drives FSM timed transitions and watches). Gated by `:enable-scheduler`
  (default **on**).
- A job spec carries `:next-fire` (epoch-ms); it's *due* when enabled and `:next-fire <=
  now`. A cron job advances `:next-fire` to the next match after firing; a one-shot (`:at`)
  fires once.
- Delivery: the prompt runs through the job's `:agent`, and output goes to the `:sink`
  (a file artifact by default, plus stdout).

---

## 6. Instruction Shape

The shared CoAct instruction template (see `common/coact_agent.clj` and
[`react-coact-unification-plan.md`](./react-coact-unification-plan.md)) plus five
schedule-agent guidances:

1. **Open with a read sweep.** Before proposing anything, call `schedule$list` and read
   `:enable-scheduler` (+ `:scheduler-tick-ms`) via `agent-runtime$config`. Render the jobs
   and the ticker state, and reason against that.
2. **Classify into one of the six capability kinds (§4) before acting.** In particular,
   distinguish *recurring* (`:cron`) from *one-shot* (`:at`) from *"in N minutes/hours"*
   (`:at` computed from now) — and ask: is this really a *time* trigger, or a *condition*
   (hand to `event-agent` for a watch)?
3. **For any write, follow read → translate → validate → preview → (gate check) → confirm →
   apply.** Translate intent to `:cron`/`:at`; validate (cron parses, `:at` is in the
   future); **preview the trigger in plain words and the concrete next-fire timestamp**
   before writing.
4. **State the firing model and blast radius plainly.** After a write, say: whether the
   ticker is on; that the job fires only while a `by` session is open (offer
   `schedule$run-now`/`run-due` for on-demand, and Cowork scheduled-tasks for
   session-independent firing); the agent it runs as; and — for recurring jobs — that it
   repeats until paused/removed.
5. **Use `run-now` to prove it.** Offer `schedule$run-now` right after authoring so the user
   sees the actual output once, without disturbing the schedule.

The instruction explicitly forbids:

- Writing `:enable-scheduler` / `:scheduler-tick-ms` directly (use `config-agent`).
- Hand-editing `.brainyard/schedule/`.
- Authoring a `watch$*` (that's `event-agent`).
- Presenting a raw cron without a plain-words translation, or writing a job without showing
  its computed next-fire.
- Implying unattended firing without the session-bound caveat.

---

## 7. Store Layout & Dossier

The agent reads and writes through `schedule$*`; the store it touches (as-built in
schedule.clj — project-scoped, atomic EDN, mirroring the task subsystem):

```
<project>/.brainyard/
├── schedule/<id>/
│   ├── spec.edn                    ;; schedule$add — prompt + trigger + next-fire + last-run
│   └── …                           ;; run-log outputs (file sink), reclaimed with the job
└── agents/schedule-agent/
    ├── dossiers/
    │   └── 20260713-161244-weekday-standup.md
    └── INDEX.md                     ;; append-only, last 100 entries
```

**Dossier.** One markdown file per conversation, frontmatter matching the sibling agents:

```markdown
---
agent: schedule-agent
session-id: 2026-07-13-sched-9b3e2
question: "Every weekday at 9am, summarize yesterday's commits"
started: 2026-07-13T16:12:20Z
ended: 2026-07-13T16:12:58Z
scheduler: {enable-scheduler: true, tick-ms: 60000}
created:
  - id: weekday-standup-1752...
    cron: "0 9 * * 1-5"
    cron-plain: "9:00 AM, Monday through Friday"
    next-fire: 2026-07-14T09:00:00Z
    agent: coact-agent
    sink: file
removed: []
run-now: {id: weekday-standup-1752..., status: ok, output: .brainyard/schedule/…/run-…log}
next-steps: []
---

## What I read
- schedule$list → [] (no jobs)
- gates → :enable-scheduler true, :scheduler-tick-ms 60000

## What I created
schedule$add
  :prompt "Summarize yesterday's commits across the repo as a short standup update."
  :cron "0 9 * * 1-5"   ;; → 9:00 AM, Mon–Fri
  :title "weekday-standup"
  → {:id weekday-standup-1752… :next-fire 2026-07-14T09:00:00Z :enabled true}

## Firing model (stated to user)
- Ticker ON; fires only while a `by` session is open at 9am. For a laptop-closed morning,
  it fires late on the next session's catch-up pass. For always-on firing, use Cowork
  scheduled-tasks instead.

## Dry-run
- schedule$run-now → status :ok, output written to run-log (proved the prompt works).
```

---

## 8. Conversation Patterns (few-shot worked examples)

The canonical worked flows the instruction points the LLM at. Each shows the tool sequence
and the shape of the final answer.

### 8.1 Schedule a recurring job — translate the cron (capability kind 2)

> **User:** every weekday at 9am, summarize yesterday's commits

1. `schedule$list` → `[]`; `agent-runtime$config` → `:enable-scheduler true`,
   `:scheduler-tick-ms 60000`.
2. Translate intent → `:cron "0 9 * * 1-5"`. Validate: 5 fields, ranges OK.
3. Preview in plain words + next fire, then on confirm:
   ```clojure
   schedule$add :prompt "Summarize yesterday's commits across the repo as a short standup update."
                :cron "0 9 * * 1-5" :title "weekday-standup"
   ```
   → `{:id "weekday-standup-1752…" :next-fire <2026-07-14T09:00Z> :enabled true}`.
4. `schedule$run-now :id "weekday-standup-1752…"` → dry-run; `{:status :ok :output "…run-…log"}`.
5. Answer:
   > Scheduled **weekday-standup** — `0 9 * * 1-5` = **9:00 AM, Monday–Friday**. Next run:
   > **tomorrow 9:00 AM**. It runs `coact-agent` unattended and writes the summary to a file.
   > **Caveat:** it only fires while a `by` session is open at 9am — if your machine's asleep,
   > it runs late on the next session. Want always-on firing regardless of sessions? That's
   > Cowork's scheduled-tasks, not this. I ran it once just now so you can see the output.

### 8.2 Schedule "in N hours" — one-shot from now (capability kind 3)

> **User:** remind me in two hours to send the release notes

1. Recognise a one-shot relative time → compute `:at (+ now 7_200_000)`.
2. `schedule$add :prompt "Remind: send the release notes." :at <now+2h> :title "release-notes-reminder"`
   → preview the concrete timestamp.
3. Answer:
   > Set a one-shot for **~6:12 PM today** (2 hours out). Same caveat: it fires on the ticker
   > while a session is open — if none is open then, it fires on your next session shortly
   > after. For a hard phone reminder, Cowork scheduled-tasks is the better fit.

### 8.3 A condition, not a clock — hand off to event-agent (kind 2 → redirect)

> **User:** every time the orders table grows, run a report

1. Recognise: the trigger is a **condition** (table changed), not a time. That's a *watch*
   (probe → emit), which lives in `event-agent`, not a `schedule$*` job.
2. Redirect cleanly:
   > That's a *condition* trigger, not a clock — you want a **watch** that polls the table and
   > fires an event when it grows, wired to a reaction that runs the report. That's the
   > event-agent's job; want me to hand this to it? (If you'd rather just run the report on a
   > fixed clock — say hourly — I can schedule that here instead.)
3. On "hand off": `(call-tool "event-agent" {:question "watch orders table row count; on
   increase, run a report"})`.

### 8.4 Tune / pause a job (capability kind 4)

> **User:** move the standup to 8am, and pause it for this week

1. `schedule$list` → find `weekday-standup`, current `:cron "0 9 * * 1-5"`.
2. Re-author the cron: `0 8 * * 1-5` (= 8:00 AM, Mon–Fri) via a fresh `schedule$add` (or
   remove+add), then `schedule$disable :id …` to pause.
3. Answer:
   > Moved to **8:00 AM weekdays** (`0 8 * * 1-5`) and **paused** it. It won't fire until you
   > re-enable — say the word and I'll flip it back on (its next-fire recomputes on enable).

### 8.5 Diagnose a job that didn't run (capability kind 5)

> **User:** my 3am backup-check never ran last night

1. `schedule$list` → `backup-check` present, `:enabled true`, `:cron "0 3 * * *"`,
   `:last-run nil`.
2. `agent-runtime$config` → `:enable-scheduler true` (ticker gate fine).
3. The cron is right and the ticker's on, but `:last-run` is nil and the job's still enabled
   → the most likely cause is **no `by` session was open at 3am**, so the ticker wasn't alive
   to fire it.
4. Answer, with the specific cause and the fix:
   > The schedule is correct (`0 3 * * *` = 3:00 AM daily) and the ticker's on — but it only
   > runs while a `by` session is open, and it looks like none was at 3am, so it never fired
   > (`last-run` is empty). Options: run it now with `schedule$run-now` (I can), catch up all
   > due jobs with `schedule$run-due`, or — if you need 3am firing with no session open — move
   > this to **Cowork's scheduled-tasks**, which fires independently of brainyard sessions.

### 8.6 Show me my jobs (capability kind 1)

> **User:** what do I have scheduled?

1. `schedule$list` + `:enable-scheduler`, one sweep.
2. Render one board:
   > **weekday-standup** — `0 9 * * 1-5` (9 AM Mon–Fri) · next: tomorrow 9:00 AM · last: ok ·
   > sink: file · **enabled**
   > **backup-check** — `0 3 * * *` (3 AM daily) · next: tonight 3:00 AM · last: — (never) ·
   > **enabled**
   > **Ticker:** ON (tick 60s) — fires only while a `by` session is open.

No writes, no dossier beyond the read record. A "what's scheduled" question must not turn
into a multi-turn flow.

### 8.7 Retire a job (capability kind 6)

> **User:** delete the backup check

1. `schedule$list` → confirm the id.
2. `schedule$remove :id "backup-check-1752…"` → `{:removed "backup-check-1752…"}`.
3. Dossier records the removal. Answer confirms the job **and its run logs** are gone.

---

## 9. Edge Cases

1. **`:enable-scheduler` off.** Defaults on, so uncommon — but if a read shows the ticker
   off, `schedule$add` still stores the job (with a `:note`), and nothing fires unattended.
   State it plainly and offer either the `config-agent` handoff to turn the ticker on, or
   `schedule$run-now`/`run-due` for manual firing.
2. **Malformed cron.** `schedule$add` rejects a cron that isn't 5 valid fields with a
   structured error. The agent surfaces the specific problem ("`day-of-week` must be 0–6")
   and offers a corrected expression rather than passing the error through raw.
3. **`:at` in the past.** A one-shot whose time already passed is due immediately — it fires
   on the next tick / `run-due`. Flag this ("that time's already passed, so it'll run right
   away — did you mean tomorrow?") rather than silently firing.
4. **Missed fire while no session was open.** The headline caveat (8.5). On session open the
   ticker's first catch-up pass runs any overdue job; a one-shot fires late, a cron job fires
   once and advances. Set this expectation at author time, not just at diagnosis.
5. **Recurring token cost.** A frequent cron (e.g. `*/5 * * * *`) runs an LLM agent every 5
   minutes — real spend. Flag high-frequency schedules and confirm the cadence is intended.
6. **Timezone.** Cron matches against the ticker host's local time. When the user says "9am,"
   confirm the zone if it's at all ambiguous, and state the zone the next-fire timestamp is
   in.
7. **Sink choice.** `file` (default) persists output as an artifact; `stdout` only shows in a
   live session. For an unattended overnight job, `stdout`-only output is effectively lost —
   steer to `file` and say why.
8. **Overlap with Cowork scheduled-tasks.** When the user's real need is a personal, always-
   on, cross-app reminder (not a project-scoped brainyard-agent prompt), name Cowork's
   scheduled-tasks / the `schedule` skill as the right tool instead of contorting a brainyard
   job. Honesty over territory.
9. **Agent/model override that doesn't exist.** If `:agent` or `:model` names something
   unregistered, the job will error at fire time. Validate against the known roster at author
   time when possible; otherwise note the risk.
10. **User pastes a raw cron** (`schedule$add :cron "*/10 9-17 * * 1-5"`). Accept, but still
    echo the plain-words translation ("every 10 min, 9 AM–5 PM, weekdays") and confirm before
    writing — don't fast-path around the translation just because the input was pre-structured.

---

## 10. Relationship to event-agent and Cowork scheduled-tasks

Two boundaries define this agent's scope.

**vs. event-agent (the watch seam).** Both `schedule$*` jobs and `watch$*` watches live in
`schedule.clj` and both ride the same daemon ticker — but they answer different questions. A
`schedule$*` job is **"at this *time*, run a *prompt*"**; a watch is **"when this *condition*
changes, fire an *event*."** The artifact, the trigger, and the output differ (prompt+sink vs
probe+emit), and the watch's whole purpose is to feed the event bus — so watches belong to
`event-agent`, next to the reactions that consume their events. This agent owns *only* the
time→prompt half. When a user describes a condition trigger, it redirects (8.3).

**vs. Cowork scheduled-tasks.** Cowork has its own scheduled-tasks feature (and a `schedule`
skill) for personal, always-on, cross-app automation that fires independently of any
brainyard session. brainyard's scheduler is narrower by design: **project-scoped, runs a
brainyard agent's prompt, session-bound.** This agent doesn't try to replace the Cowork
feature — it drives the brainyard scheduler and is explicit about the boundary, pointing users
to Cowork scheduled-tasks whenever they need session-independent firing (8.1, 8.5, edge case
8). Being clear about *which clock* is doing the firing is part of the job.

---

## 11. Why not fold this into event-agent (the recommendation this doc implements)

The sibling analysis (`event-agent` §11, `state-machine-agent` §11) draws the line at the
**authoring artifact**. Reactions are event-coupled, so they live with events. FSMs are
stateful graphs, so they got their own agent. Time-triggered prompt jobs are a *third*
distinct artifact — **a prompt + a clock + a delivery sink, with no connection to the event
bus** — so they get their own thin surface here rather than being bolted onto event-agent.

Folding `schedule$*` into event-agent would blur two unrelated mental models (a "when X
happens do Y" rule vs. a "at 9am run this prompt" job) and two unrelated failure modes (a
reaction that never matched vs. a job that never fired because no session was open). Keeping
them apart keeps each agent's read sweep, validation, and diagnosis coherent. The one shared
substrate — the daemon ticker — stays exactly one mechanism underneath, driving `schedule$*`
jobs, `watch$*` watches, and FSM timed transitions alike; the agents are just three focused
front doors over the concerns that genuinely differ.

`reaction-agent` was considered and rejected for the opposite reason: reactions *can't* be
cleanly separated from events (their validation needs the event's payload schema), so a
dedicated reaction-agent would be the wrong cut. Schedule jobs *can* be cleanly separated —
they share nothing with events but the ticker — so a dedicated schedule-agent is the right
one.

---

## 12. Testing Plan

Three surfaces, matching the sibling agents.

1. **Unit — translation + command pass-through.** The `schedule$*` commands already have
   coverage (`agent/` scheduler tests). This agent adds tests only for its *orchestration*:
   the intent→cron translation and cron→plain-words rendering as pure functions, `:at`
   computation from relative time, and the ticker-state / next-fire rendering.
2. **Integration — CoAct loop with a stubbed LLM.** Canned LLM turns drive the agent through
   each capability kind (§4). Assert the on-disk `spec.edn` (cron, next-fire, agent, sink),
   the `run-now` result, and the dossier frontmatter match goldens — especially the
   `scheduler:` and `created:` fields and the stated session-bound caveat.
3. **End-to-end — manual / CI-opt-in.** Real `bb tui run -a schedule-agent` against a scratch
   `.brainyard/`. Exercises: schedule a weekday cron, `run-now` to prove it, pause/enable,
   diagnose a missed fire (no session), and the event-agent redirect for a condition trigger.
   Slow; tagged `:slow`.

The dossier is its own contract — every test asserts the documented frontmatter fields are
populated.

---

## 13. Open Questions

1. **Should this agent flip `:enable-scheduler` itself** rather than always routing through
   `config-agent`? Lean no — one place for config writes — but a single low-risk toggle with
   an inline confirm could be justified for the common "schedule then run" flow. Deferred.
2. **A richer `:at` parser.** Today relative times ("in two hours," "tonight") are computed
   in the agent and passed as epoch-ms. A natural-language time helper (or a command-level
   `:in`/`:at-human` arg) would make this deterministic and reusable. Additive; not required
   for v1.
3. **Next-fire preview across N occurrences.** Showing just the next fire is enough for
   confirmation, but "the next 3 runs" would help users sanity-check a gnarly cron. Nice-to-
   have.
4. **Deep integration with Cowork scheduled-tasks.** Rather than only *pointing at* Cowork's
   feature for session-independent firing, could this agent create a Cowork scheduled-task on
   the user's behalf? That crosses a product boundary (brainyard vs Cowork host) and is out of
   scope for v1, but worth revisiting as the two converge.
5. **Delivery sinks beyond file/stdout.** R3 (hermes-comparison.md) anticipates pluggable
   channels (Slack, email). When those land, this agent's `:sink` authoring surface grows to
   match — tracked against the scheduler roadmap.
