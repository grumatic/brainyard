# Workflow-Agent — Domain-Specific Multi-Agent Workflow Automation (CoAct-derived)

> **Status:** Shipped. `pipeline` retired (the `pipeline/` directory was removed; see §14);
> lightweight authoring + template-CRUD redesign shipped (2026-06). This doc is the as-built
> reference — the former `workflow-agent-lightweight-redesign.md` has been folded in here and removed.
>
> **As-built (verify against `common/workflow_agent.clj`, `common/workflow.clj`):**
> - **Dossier authoring is direct markdown, not a construct-a-map helper chain.** Bootstrap is
>   `bash mkdir` + `write-file` of `purpose.md` / `acceptance.md` / `stages.md` / `dossier.md` from
>   the loaded template. The old write-side helpers `workflow$bootstrap` /
>   `workflow$update-stage` / `workflow$update-acceptance` / `workflow$write-verdict` /
>   `workflow$append-log` / `workflow$index-append` (and the never-built `workflow$summarize-log`)
>   are **retired** (§9). Six read/derive/validate seams survive: `workflow$id`,
>   `workflow$resume?`, `workflow$list-templates`, `workflow$load-template`,
>   `workflow$install-starters`, and the new `workflow$verdict-outcome`.
> - **Acceptance criteria AND the stage roster are CHECKLISTS on the shared todo substrate.**
>   `acceptance.md` and `stages.md` are markdown checklists with a stable id per line; status is
>   flipped **index-free by id** via `update-file` (never by ordinal). One parser serves three
>   status-list concerns — todo items, workflow acceptance, workflow stages. Acceptance tokens:
>   `open[ ] satisfied[x] partial[~] descoped[-] contradicted[!]`; stage tokens:
>   `pending[ ] in-progress[>] satisfied[x] skipped[-] failed[!]` (parser also accepts `:abandoned`).
> - **Workflow templates are MARKDOWN with a managed CRUD lifecycle, not hand-authored EDN.** A
>   template is frontmatter (`workflow_id` / `name` / `description` / `defaults`) + a `# Acceptance`
>   checklist + a `# Stages` checklist — the same checklist shape a run dossier uses. CREATE / UPDATE /
>   DELETE are plain file ops (`write-file` / `update-file` / `rm`), owned by workflow-agent in an
>   explicit **AUTHORING MODE** (§6), validated by `workflow$load-template` after each write. Hard
>   Rule 3 still forbids self-modify *mid-run* — authoring is a separate invocation. `iteration 1`
>   does a MODE SELECT (run vs. authoring).
> - **Stages are dispatched by DIRECT kebab-case agent calls.** `(plan-agent {…})`,
>   `(research-agent {…})`, `(edit-agent {…})`, etc. in a clojure fence (defagents self-register as
>   callable sandbox fns). `call-tool` is bound for generic registry access but is not the
>   stage-dispatch path. Wherever older prose below says "via `call-tool`", read "via the direct
>   kebab-case call".
> - **A `:agent.ask/finalize` auto-finalize backstop** derives the outcome, renders `verdict.md`,
>   appends `INDEX.md`, and injects the absent `Saved workflow dossier:` line when the LLM emits a
>   non-blank answer but skipped the FINALIZE fence — *gated* on the dossier existing, no acceptance
>   criterion `:open`, and `verdict.md` absent. Per-turn opt-out via the `workflow-auto-finalize`
>   config. (The original proposal placed this on `:agent.ask/post`; as-built it is
>   `:agent.ask/finalize`.)
> - **Templates dual-read legacy EDN** and dossiers dual-read legacy `stages.edn` / frontmatter
>   acceptance for one deprecation window, so existing user/project templates and in-flight runs
>   keep working.
> - **Only two starter templates ship** (`feature-launch`, `doc-update`) under
>   `components/agent/resources/workflows/`, now as **markdown**. The other four sketched in §4.2
>   were never authored.
> - Migration §14 (retiring `pipeline`) is **complete** — `pipeline/` is gone and `/pipeline` slash
>   commands were removed.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/workflow_agent.clj`,
> `components/agent/src/ai/brainyard/agent/common/workflow.clj`, and the markdown template format
> under `.brainyard/workflows/`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Supersedes:** `pipeline` (deleted)
> **Related reading:** `docs/CoAct.md`, `docs/design/research-agent-design.md`,
> `docs/design/explore-agent-design.md`, `docs/design/rlm-agent-design.md`,
> `docs/design/agent-lightweight-redesign-synthesis.md`

---

## 1. Motivation

`pipeline` (`components/agent/.../pipeline/`, design in `docs/agent-tui-app/pipeline.md`) is brainyard's current macro-orchestration layer. It defines a workflow as a DAG of stages stored in `.brainyard/pipelines/<name>.pipeline.edn`, with four stage types (`:agent`, `:gate`, `:decision`, `:sub-pipeline`), five HITL modes (`:full-auto` through `:step-by-step`), and an executor that walks the DAG with topological-sort + parallelism markers + on-failure policy.

It works. It also has the same shape of problems the `research-agent` design (`docs/research-agent-design.md`) called out for `autoresearch`:

**Three structural defects:**

1. **Static DAG, dynamic reality.** A pipeline's edges (`:depends-on`) are decided at authoring time. The executor walks them deterministically: a `:decision` stage can pick which branch, but the *set* of branches is fixed; a stage cannot decide "actually, we need to re-explore before planning" or "skip the deploy stage — verify already showed the change isn't needed." The pipeline's *logic* is in the EDN file; the LLM only does work *inside* one stage at a time. Workflows that need adaptive shape (most non-trivial ones) end up with sprawling branching logic, dead branches, or wrappers that mutate the EDN at runtime.

2. **Cross-stage context starvation.** Each `:agent` stage receives `:question` (the stage `:prompt`) and a per-stage prompt-templated `:agent-context`. The *workflow purpose*, *domain constraints*, *acceptance criteria for the workflow as a whole*, and the *cumulative findings from prior stages* are not threaded in any durable way. The pipeline state map (`pipeline/state.clj`) accumulates per-stage `:result` strings, but those are stage-local; nothing carries the workflow's macro-level intent across stages. This is the same defect autoresearch had at the four-specialist scale, lifted up to the pipeline scale.

3. **HITL embedded in the DAG, not the conversation.** The five HITL modes and SmartPause are first-class features of the executor. That made sense when the pipeline was a separate state machine; in a CoAct-driven world it duplicates the agent loop's own approval surface (`request-user-action`, `user-approval-action`, `user-interrupt-action`) and can't be tuned per stage by the LLM mid-flight.

A fourth issue, surfaced once `pipeline`'s replacement was in use: **the template gap.** Workflow templates were hand-authored EDN, and nothing owned their lifecycle. Editing one meant emitting precise EDN — the same structured-construction brittleness the lightweight redesign retires elsewhere — and there was no agent-managed CREATE/UPDATE/DELETE, even though tools (tool-agent), agents (meta-agent), and skills (skill-agent) all have lifecycle owners. So a user who wanted "make a workflow template for our release process" or "add a canary stage to feature-launch" had to hand-edit EDN. (Closed by §6: templates become markdown checklist docs with a managed CRUD lifecycle.)

**The CoAct lesson — applied again.** `research-agent` showed that a single CoAct loop with a curated instruction and a durable dossier replaces a hard-sequenced multi-specialist BT (autoresearch's plan→todo→exec→eval). The same recipe applies one tier up: at the *workflow* scale, where each "stage" is itself a multi-specialist call (often a `research-agent` invocation, sometimes an explore / plan / exec call directly).

`workflow-agent` is the CoAct equivalent of `pipeline`. It owns one durable artifact (the workflow dossier), reaches stages via **direct kebab-case dispatch** to whichever functional agent fits each stage, and lets the LLM decide stage order, retries, and termination. The `:agent`/`:gate`/`:decision`/`:sub-pipeline` ontology becomes implicit moves in a state machine the LLM picks from per iteration.

The shipped design is **lightweight**: the orchestration loop is judgment the LLM does well and stays untouched, but persistence is markdown the model authors directly rather than structured objects it constructs through helpers. The dossier's acceptance criteria and stage roster are **markdown checklists** flipped index-free by stable id (the shared todo substrate), and workflow **templates are markdown** with a managed CRUD lifecycle — so authoring, running, and tracking all speak one checklist format. (This is the cross-agent argument in `agent-lightweight-redesign-synthesis.md`: separate *judgment* — orchestration, authoring prose — from *mechanism* — discovery, parsing, validation, verdict derivation.)

**Thesis.** A CoAct-derived `workflow-agent` that:

1. **Owns one durable artifact** — a workflow dossier under `.brainyard/agents/workflow-agent/<workflow-id>/` capturing *purpose*, *domain constraints*, *workflow-level acceptance criteria*, *stage roster + status*, *cumulative findings*, and *handoff state* across iterations and across stages. The acceptance + stage roster are markdown checklists (§5).
2. **Treats workflow templates as data, not logic — and authored as markdown.** Domain-specific templates (`.brainyard/workflows/<domain>.md`) declare the *expected shape* (typical stage sequence, recommended agents, default acceptance criteria) but do NOT bind the LLM to them. The instruction tells the agent: *templates are starting points, not contracts.* Templates get a managed CRUD lifecycle (§6) — they are markdown checklist docs, so editing one is editing a file.
3. **Uses CoAct's loop** — no new BT, no executor, no DAG walker. The LLM picks the next move per iteration: load template, draft workflow plan, run a stage, evaluate, gate (ask user), branch, re-run a stage, finalize.
4. **Reaches functional agents via direct kebab-case dispatch** — `(research-agent {...})`, `(explore-agent {...})`, `(exec-agent {...})`, `(edit-agent {...})`, plus any other registered defagent. Cross-agent dispatch is flat, not pipeline executor wiring.
5. **HITL collapses into the conversation.** Approval gates become `answer`-channel pauses (the user replies, the workflow resumes). The five HITL modes become one config knob (`:hitl_mode :auto|:gates|:checkpoint|:co-pilot|:step`) the LLM consults — no executor branch.
6. **Inherits CoAct's full BT, sandbox, router, accumulator** — no substrate changes. Whole feature is one agent file plus a slim read/derive/validate helpers namespace, mirroring `research-agent`.
7. **Owns workflow-template CRUD** in an explicit authoring mode (§6), the workflow analog of the tool/agent/skill lifecycles — distinct from run mode, with no self-modify mid-run.

---

## 2. Design Principles

1. **Orchestration is judgment — untouched.** The 9-move state machine (RUN-STAGE / EVAL / GATE / RE-RUN / INSERT / SKIP / SYNTHESIZE / CLARIFY / FINALIZE), the HITL modes, and direct kebab-case dispatch stay exactly as designed. Only the *persisting* was over-tooled.
2. **Workflow shape is a recommendation, not a contract.** Domain templates declare what *typically* happens. The LLM adapts shape to the actual question — skip / insert / re-run / reorder / substitute the agent as real work demands.
3. **Acceptance and stages are checklists (substrate reuse).** Cross-stage state lives in markdown checklists — `acceptance.md` and `stages.md` — not in `pipeline/state.clj` atoms, per-stage `:result` strings, or vectors-of-maps. Both ride the todo substrate's checklist + index-free flip + parse-back read seam: one mechanism for three status-list concerns (todo items, workflow acceptance, workflow stages). Every move writes; every subsequent move reads.
4. **The LLM owns sequencing.** Re-run a stage? Skip ahead? Branch? Pause for user input? These are reasoning calls. The instruction names the moves; the LLM picks one per iteration.
5. **Stages are agent invocations via direct dispatch.** Each stage is a direct kebab-case call to a functional agent (research / explore / plan / todo / exec / eval / mcp / skill / edit / rlm / coact). The workflow-agent does not implement domain logic — it composes agents.
6. **Templates are markdown, not EDN — with a managed CRUD lifecycle.** A `feature-launch.md` declares the typical stage sequence (research → plan → implement → test → release-notes → announce) and recommends agents per stage, in the *same checklist format* the run dossier uses. CREATE/READ/UPDATE/DELETE are file ops owned by workflow-agent in an authoring mode (§6) — the missing lifecycle, matching tool-agent / meta-agent / skill-agent.
7. **HITL is a single conversational surface.** Approval prompts are `answer`-channel pauses with a stable prefix the dispatcher can identify. No second approval system.
8. **Acceptance criteria are workflow-scoped.** The dossier carries *workflow-level* acceptance ("the feature is launched: PR merged, docs published, announcement sent") distinct from each stage's per-call acceptance. Workflow termination requires workflow-level acceptance, not stage-level.
9. **No clone-self recursion.** `query$clone` is excluded — the workflow agent calling itself is the depth-2 anti-pattern. Direct cross-agent dispatch to other defagents is flat and IS the design.
10. **Run/edit separation preserved.** No template self-modification *during* a run (Hard Rule 3); template editing is a separate, explicit authoring-mode invocation.
11. **Keep deterministic discovery/validation/derivation.** Listing/loading/validating templates, deriving the verdict outcome, and enforcing the `:achieved` guard are all mechanism — kept as typed `workflow$*` seams (§9).
12. **Resumable.** The dossier is the only state of record. A workflow run interrupted mid-flight (TUI exit, gate timeout, max iterations) can be resumed by passing the workflow-id; the agent reads the dossier and continues.
13. **Generous iteration cap.** Default 50 iterations. A workflow with 6–8 stages, each occasionally needing retry/branch, easily hits 30–40 actual moves. Override via `agent-runtime$config :key "max-iterations" :value "N"`.

---

## 3. Position in the Agent Stack

```
coact-agent  (parent — full BT, sandbox, router, accumulator)
  ├─ explore-agent     (multi-surface discovery; supersedes the retired search-agent)
  ├─ rlm-agent         (MapReduce over too-big context)
  ├─ skill-agent       (skills$* lifecycle)
  ├─ mcp-agent         (MCP lifecycle + write ops)
  ├─ plan-agent        (.brainyard/plans/<slug>.md authoring)
  ├─ todo-agent        (.brainyard/todos/<slug>.md spawning + tracking)
  ├─ exec-agent        (advance an existing todo)
  ├─ eval-agent        (verdict against plan ## Acceptance)
  ├─ research-agent    (multi-specialist research thread; dossier)
  └─ workflow-agent    (domain-specific multi-agent workflow automation; supersedes the retired pipeline)
```

| Question / task shape | Use | Why |
|---|---|---|
| "Find me where X lives" | explore-agent | Single-surface discovery. |
| "Drive todo Z to completion" | exec-agent | Already has plan + todo. |
| "Investigate Y end-to-end and produce a recommendation" | research-agent | Multi-specialist research thread. |
| "Run the feature-launch workflow for feature F: research, plan, implement, test, release-notes, announce." | **workflow-agent** | Multi-stage domain workflow. |
| "Run the incident-response runbook for outage O: detect, mitigate, root-cause, postmortem." | **workflow-agent** | Multi-stage domain workflow. |
| "Create a workflow template for our release process" / "add a canary stage to feature-launch" | **workflow-agent** (authoring mode) | Template CRUD — the workflow-template lifecycle (§6). |

Rule: **research-agent is for one research thread end-to-end. workflow-agent is for a multi-stage *domain workflow* where most stages are themselves multi-specialist work** (typically themselves research-agent or exec-agent invocations).

**Composition:** workflow-agent often calls research-agent as the implementation of an analysis stage, and exec-agent as the implementation of a do-the-work stage. research-agent in turn calls explore/plan/todo/exec/eval. So:

```
workflow-agent  (a feature-launch workflow)
  ├─ stage: research feasibility    →  research-agent
  │                                       └─ explore-agent / plan-agent / todo-agent / exec-agent / eval-agent
  ├─ stage: gate (user confirms feasible)
  ├─ stage: implement                →  exec-agent
  ├─ stage: test                     →  exec-agent
  ├─ stage: write release notes      →  plan-agent or coact-agent (direct call)
  └─ stage: announce                 →  mcp-agent (Slack, etc.) (direct call)
```

Each level is flat — no agent recurses on itself. Workflow → Research → Specialists is three flat layers, not depth-3 recursion.

---

## 4. Workflow Templates — `.brainyard/workflows/<domain>.md`

Workflow templates are the data side of the design — **markdown checklist docs** (§6 gives them a
managed CRUD lifecycle). They are read by the LLM and treated as *recommendations*. They are NOT
executed by a DAG walker.

### 4.1 Template Shape (markdown)

A workflow template is frontmatter (id / name / description / defaults) + a `# Acceptance`
checklist + a `# Stages` checklist — the *same* checklist shape a bootstrapped dossier uses, minus
the run state (all criteria `(open)`, all stages unchecked). Stage metadata (`agent`, `gate`,
`focus`) rides an inline `{…}` tag, exactly how todo items carry inline tags.

```markdown
---
workflow_id: feature-launch
name: Feature Launch
description: End-to-end feature delivery — research → plan → implement → test → release.
defaults: {hitl: gates, max_stage_attempts: 2, sub_lm: claude-haiku-4-5-20251001}
---

# Acceptance
- [ ] a1 — feature meets the user-stated success criteria
- [ ] a2 — implementation merged to main
- [ ] a3 — tests added and passing
- [ ] a4 — release notes published
- [ ] a5 — stakeholders notified

# Stages
- [ ] s1 research-feasibility — validate the feature is worth doing {agent: research-agent, gate: user, focus: [a1]}
- [ ] s2 plan-design — author a concrete implementation plan {agent: plan-agent, gate: none, focus: [a1, a2]}
- [ ] s3 implement — execute the plan's todo list {agent: exec-agent, gate: none, focus: [a2, a3]}
- [ ] s4 test — run/extend tests; capture failures {agent: exec-agent, gate: none, focus: [a3]}
- [ ] s5 release-notes — draft user-facing release notes {agent: coact-agent, gate: none, focus: [a4]}
- [ ] s6 announce — notify stakeholders via configured channels {agent: mcp-agent, gate: user, focus: [a5]}
```

`workflow$load-template` parses this markdown (read + validate) into the run's starting acceptance +
stages. Because templates are markdown checklists, **one checklist format serves the template, the
run dossier, and the todo substrate** — the template a user reads is byte-for-byte the shape the run
consumes. The shipped parser also **dual-reads legacy `.edn` templates** for the deprecation window
(it normalizes the old `:acceptance`/`:stages` vectors-of-maps to the uniform shape).

**Stage lines are advisory.** The LLM is told (via instruction) that templates declare *typical
shape* but it can:

- Skip a stage (`test` is unnecessary if the implementation is doc-only).
- Insert a stage (an unforeseen `migrate-data` step before `implement`).
- Re-run a stage (post-`test`, return to `implement`).
- Reorder (`release-notes` before `test` if the feature is feature-flag gated).
- Substitute the agent (use `coact-agent` for `research-feasibility` if the question is trivial).

### 4.2 Where Templates Live

```
.brainyard/
└── workflows/
    ├── feature-launch.md      ; shipped starter
    └── doc-update.md          ; shipped starter
```

Templates are markdown, versioned in the project repo (or in `~/.brainyard/workflows/` for personal
templates), and resolved project → user → built-in. They are how *domain knowledge* flows from
senior engineers into agent runs without anyone having to write executor logic — and now without
hand-authoring EDN (§6). **As-built:** only two starters ship (`feature-launch`, `doc-update`),
under `components/agent/resources/workflows/` (the classpath `workflows/` resource dir). The four
others sketched in earlier drafts (`incident-response`, `refactor-and-verify`, `data-migration`,
`library-upgrade`) were never authored; users author their own under `.brainyard/workflows/` or
`~/.brainyard/workflows/`.

### 4.3 Template Bootstrapping

The two starter templates ship on the classpath under `resources/workflows/` as markdown.
`workflow$install-starters` (idempotent; `:overwrite?` opt-in) copies them into
`.brainyard/workflows/` for the user to edit. This mirrors how `.brainyard/skills/` is bootstrapped.

---

## 5. The Workflow Dossier — `.brainyard/agents/workflow-agent/<workflow-id>/`

### 5.1 Directory Layout

```
.brainyard/
└── workflow-agent/
    ├── INDEX.md                            ; one line per workflow run (append-only)
    └── <workflow-id>/                       ; one directory per workflow thread
        ├── purpose.md                       ; verbatim question (immutable after iteration 1)
        ├── acceptance.md                    ; the ACCEPTANCE CHECKLIST — index-free flips (§5.2)
        ├── stages.md                        ; the STAGE CHECKLIST — index-free flips (§5.2)
        ├── dossier.md                       ; YAML frontmatter + body — the cross-stage contract
        ├── findings.log                     ; append-only, one line per move (write-file :append)
        ├── verdict.md                       ; written at termination (§5.4)
        └── artifacts/                       ; pointers (symlinks) into other agents' outputs
```

The `<workflow-id>` is a kebab-case slug (`<template-id>--<question-slug>`, or just
`<question-slug>` for `:ad-hoc`), derived by `workflow$id`. Re-runs of the same workflow on the same
target produce the same id; the dossier accumulates and `workflow$resume?` resumes it.

> The pre-redesign dossier kept acceptance as a frontmatter vector-of-maps and the stage roster in a
> separate `stages.edn`. As-built, **both are markdown checklists** (`acceptance.md`, `stages.md`)
> on the shared todo substrate. `workflow$resume?` and `workflow$verdict-outcome` **dual-read** the
> legacy frontmatter-acceptance + `stages.edn` shapes so in-flight runs finish on the old format.

### 5.2 `acceptance.md` + `stages.md` — Checklists (the shared todo substrate)

Both files are markdown checklists with a stable id per line and a `(status)` token. Status is
flipped **index-free by id** with `update-file` — matched on the line text (id + token), never on an
ordinal — the same safety as todo and research.

**`acceptance.md`** (identical to research-agent's acceptance checklist):

```markdown
# Acceptance — feature-launch--mcp-server-health-check
- [x] a1 (satisfied) — Health-check command reachable via mcp$server :op :health-check; evidence: <plan/eval path>
- [ ] a2 (open) — PR merged to main
- [x] a3 (satisfied) — Unit tests added with >= 80% line coverage on new code
- [ ] a4 (open) — Release notes published
- [ ] a5 (open) — Slack #releases announcement sent
```

Acceptance status tokens: `open [ ]` · `satisfied [x]` · `partial [~]` · `descoped [-]` ·
`contradicted [!]`.

**`stages.md`** (the stage roster — a checklist with inline metadata tags, exactly how todo items
carry `{…}`):

```markdown
# Stages — feature-launch--mcp-server-health-check
- [x] s1 research-feasibility (satisfied) — validate feasibility {agent: research-agent, gate: user, focus: [a1]}
- [x] s2 plan-design (satisfied) — author the plan {agent: plan-agent, gate: none, focus: [a1, a2]}
- [>] s3 implement (in-progress) — execute the todo {agent: exec-agent, gate: none, focus: [a2, a3]}
- [ ] s4 test (pending) — run/extend tests {agent: exec-agent, gate: none, focus: [a3]}
- [ ] s5 release-notes (pending) — draft release notes {agent: coact-agent, gate: none, focus: [a4]}
- [ ] s6 announce (pending) — notify stakeholders {agent: mcp-agent, gate: user, focus: [a5]}
```

Stage status tokens: `pending [ ]` · `in-progress [>]` · `satisfied [x]` · `skipped [-]` ·
`failed [!]` (the parser also accepts `~`→`partial` and `:abandoned`). The inline `{agent, gate,
focus}` tag carries the per-stage metadata; **`attempts` is derived** from the count of RE-RUN
entries for that stage id in `findings.log`, and **`completed-at`** is the flip timestamp — no
separate structured counter to maintain.

A single read seam (`workflow$resume?` / the shared checklist reader) parses both into
`{:acceptance-state {a1 :satisfied …} :pending-stages [s4 s5 s6] …}` for resume *and* for the
verdict-outcome derivation. This unifies **three** status-list concerns onto one parser: todo items,
workflow acceptance, and workflow stages.

### 5.3 `dossier.md` — Cross-Stage Context Carrier

`dossier.md` carries lightweight frontmatter (the run's identity + mode) plus a narrative body; the
authoritative status lists live in the two checklists (§5.2).

```markdown
---
workflow_id: feature-launch--mcp-server-health-check
workflow_template: feature-launch
created: 2026-05-09T18:12:44Z
last_iteration: 14
status: in-progress              # in-progress | achieved | partial | abandoned
hitl_mode: gates                 # auto | gates | checkpoint | co-pilot | step
---

## Purpose
[verbatim from purpose.md, immutable]

## Stage progress
[narrative log of completed/active stages with brief findings — regenerated from findings.log via SYNTHESIZE]

## Pending decisions / branches
- Should we batch the announcement with next week's release? (open question)

## Open risks
- Tests caught a flaky case in CI — needs investigation in the test stage.
```

**Stable contract** (the read seams rely on these):

| Frontmatter key | Type | Purpose |
|---|---|---|
| `workflow_id` | string | Stable identifier. |
| `workflow_template` | keyword string | Source template id. |
| `last_iteration` | int | Drives resume + verdict iteration count. |
| `status` | enum | `in-progress | achieved | partial | abandoned` |
| `hitl_mode` | enum | `auto | gates | checkpoint | co-pilot | step` |

The acceptance criteria and stage roster are **not** in this frontmatter — they are the §5.2
checklists, which `workflow$resume?` reads alongside the frontmatter.

### 5.4 `findings.log`, `verdict.md`, `INDEX.md`

- **`findings.log`** — append-only, one plain line per move via `write-file :append`, including the
  specialist dossier path(s) the stage emitted (the verbatim `Saved …:` lines):
  ```
  iter 4 · s3 implement · exec-agent · Saved dossier: .brainyard/agents/exec-agent/<id>/
  ```
  (The pre-redesign log was NDJSON built by `workflow$append-log`; as-built it is a plain append the
  model writes, with no construction helper.) The dossier body's `## Stage progress` is regenerated
  from it periodically via SYNTHESIZE.
- **`verdict.md`** — written once at termination from the VERDICT TEMPLATE (frontmatter +
  `## Verdict` body). The model fills it with `write-file` after `workflow$verdict-outcome` derives
  the outcome (`acceptance_outcome` / `stage_outcomes`) and clears the `:achieved` guard. The
  auto-finalize backstop renders the same file if the model skips FINALIZE.
- **`INDEX.md`** — append-only at `.brainyard/agents/workflow-agent/INDEX.md`, one line per run.

---

## 6. Tool Roster

```clojure
;; from common/workflow_agent.clj — the shipped :agent-tools (abridged)
:agent-tools
{:tools (vec
         (remove
          ;; web tools route through explore-agent / research-agent; fetch-url
          ;; tags along with file-tools, so filter it out explicitly.
          #(= :fetch-url (:id (meta (deref %))))
          (distinct
            (concat
              common-tools/file-tools        ; read-file, write-file, grep, update-file (NOT fetch-url)
              common-tools/shell-tools       ; bash (mkdir, ls, ln -s, rm)
              [#'common-cmds/query$llm]       ; sub-LLM synthesis (flat — excludes query$clone)
              common-tools/bootstrap-tools   ; list-tools, get-tool-info, search
              common-tools/invocation-tools  ; call-tool (generic registry access)
              task-cmds/task-commands        ; background jobs for >5s stage calls
              common-cmds/runtime-commands   ; agent-runtime$config — :max-iterations tuning
              workflow/workflow-helpers))))}  ; the 6 READ/DERIVE/VALIDATE seams (§9)
```

The functional agents (`research-agent`, `explore-agent`, `plan-agent`, `todo-agent`, `exec-agent`,
`eval-agent`, `mcp-agent`, `skill-agent`, `edit-agent`, `rlm-agent`, `coact-agent`) are reached via
**direct kebab-case dispatch** — `(plan-agent {...})`, `(research-agent {...})`, etc. They are not
bound as named tools in the roster; they live in the registry as auto-bound sandbox fns, and the
agent calls them as needed. `call-tool` is bound for generic registry access but is not the
stage-dispatch path. The roster also binds the slim `workflow/workflow-helpers` (the six
read/derive/validate seams — §9) and `runtime-commands`, and explicitly **removes `fetch-url`** even
though `file-tools` carries it (web access routes through explore/research for auditability).

What is *deliberately omitted*:

| Excluded | Reason |
|---|---|
| `query$clone` | Clones workflow-agent itself = clone-self recursion. Forbidden. |
| Direct `plan$*` / `todo$*` / `mcp$*` / `skills$*` | Routed through specialists. Bypassing them breaks per-domain safety contracts. |
| Web tools | Routed through explore-agent / research-agent. Keeps web access auditable in the explore corpus. |
| pipeline executor primitives | The whole point is to NOT use the executor. |

---

## 7. Instruction (System Prompt Body) — As-Built Shape

The full instruction lives in `common/workflow_agent.clj` (`def instruction`), layered on
`coact-agent`'s by `run-coact-derived`. It is the source of truth; this section summarizes its
as-built shape rather than re-transcribing it (so the two cannot drift). The instruction opens with
a **MODE SELECT** and then branches.

### 7.1 MODE SELECT (iteration 1)

- **RUN MODE** — the user wants to *run* a workflow ("run the feature-launch workflow for X", "ship
  feature Y", a domain-shaped multi-stage request, or `@<workflow-id>` to resume).
- **AUTHORING MODE** — the user wants to *manage a template* ("create a workflow template for our
  release process", "add a canary stage to feature-launch", "delete the data-migration template",
  "show me the feature-launch template"). No dossier, no stage run.

When ambiguous, the agent CLARIFYs. The two modes never mix in one turn — Hard Rule 3 forbids
editing a template while a workflow runs.

### 7.2 RUN MODE

- **Templates (recommendations, not contracts).** Markdown at `.brainyard/workflows/<domain>.md`
  (project), `~/.brainyard/workflows/<domain>.md` (user), or classpath starters. Resolve with
  `workflow$list-templates` (discover) + `workflow$load-template` (parse + validate). Stages are
  RECOMMENDATIONS — skip / insert / re-run / reorder / substitute the agent as real work demands,
  documenting deviations in the dossier.
- **Turn-1 bootstrap (the only fixed obligation).** Resolve the template, compute the id
  (`workflow$id`), probe resume (`workflow$resume?`). On a fresh start, author the dossier files
  **directly** — `bash mkdir` then `write-file` `purpose.md`, the `acceptance.md` and `stages.md`
  **checklists** (from the template), and `dossier.md` — no construction helper. On an existing id,
  RESUME: read the dossier + checklists + last `findings.log` lines and pick up at the next pending
  stage. SURFACE the acceptance criteria (and, for `:ad-hoc`, the stage list) in `:answer` for user
  confirmation BEFORE running stages.
- **State machine — one move per iteration** (the unchanged judgment): **A** RUN-STAGE · **B**
  EVAL-STAGE · **C** GATE · **D** RE-RUN · **E** INSERT · **F** SKIP · **G** SYNTHESIZE · **H**
  CLARIFY · **I** FINALIZE.
- **Calling a functional agent for a stage** — a direct kebab-case call, `(<agent> {…})`. The
  `:agent-context` MUST include the workflow dossier path AND the stage's acceptance focus; when the
  callee has its own pre-flight (plan/todo/exec/eval), the upstream sibling `Saved dossier: <path>`
  goes FIRST so its pre-flight finds it. Default to the stage's `agent`; substitute (coact for
  trivial, rlm for too-big, explore for discovery, research for multi-specialist).
- **HITL — a config knob, not five code paths.** `hitl_mode ∈ :auto | :gates (default) |
  :checkpoint | :co-pilot | :step`. A "prompt" is a GATE move (C): `:answer` ends with `Awaiting
  workflow gate: <stage-id>`. The mode may be upgraded mid-run, not downgraded without user
  approval.
- **Dossier update discipline (after every move — all file-native).** Append one `findings.log`
  line (`write-file :append`); flip the stage status in `stages.md` **index-free by id** with
  `update-file`; if the stage advanced acceptance, flip the criterion in `acceptance.md` (index-free
  by id); update `artifacts/` symlinks; periodically regenerate the dossier `## Stage progress` via
  SYNTHESIZE. Match the line TEXT (id + status token), never an ordinal.
- **FINALIZE (move I).** In one fence: flip every stage + criterion to reflect reality, then
  `workflow$verdict-outcome` derives the outcome + enforces the `:achieved` guard (refuses
  `:achieved` while any criterion is `:open`), then `write-file` `verdict.md` from the VERDICT
  TEMPLATE and append one `INDEX.md` line. `:answer` is DERIVED from `verdict.md` and ends with
  `Saved workflow dossier: <path>` — emitted only if `verdict.md` was actually written.

### 7.3 AUTHORING MODE — template CRUD

Templates are markdown under `.brainyard/workflows/<domain>.md`; workflow-agent owns their
lifecycle (the workflow analog of tool-agent / meta-agent / skill-agent). CRUD is plain file ops:

- **CREATE** → `write-file` from the TEMPLATE TEMPLATE (frontmatter + `# Acceptance` + `# Stages`).
- **READ** → `workflow$list-templates` (discover) + `workflow$load-template` (parse + validate + show).
- **UPDATE** → `update-file` index-free — add a `- [ ] aN — …` criterion, insert / reorder / retag a
  `- [ ] sN <name> — … {agent:…}` stage line by its stable id.
- **DELETE** → `rm` (confirm first).

After ANY create/update, call `workflow$load-template` to VALIDATE (required frontmatter keys, ≥1
stage, every stage `focus` resolves to an acceptance id). No dossier, no stage run; Hard Rule 3
forbids template edits while a workflow runs.

### 7.4 Hard rules (as-built)

1. **STAY FLAT** — no clone-self dispatch; cross-agent dispatch is the direct kebab-case call.
2. **NO direct writes to specialist storage** (`.brainyard/agents/<other>/…`) — read freely; invoke
   the specialist for new content.
3. **NO template edits during a RUN** — edit in authoring mode (a separate invocation).
4. **Acceptance criteria are FROZEN once confirmed** (one exception: a user-confirmed descope).
5. **Every stage call's `:agent-context` MUST include the workflow dossier path.**
6. **The dossier** (`dossier.md` + `acceptance.md` + `stages.md` + `findings.log`) **is the only
   durable cross-iteration state.**
7. **Iteration budget: 50.** At 80% without a candidate verdict, start FINALIZE — an honest
   `:partial` beats a silent timeout.
8. **CITE EVERYTHING** — every claim points at a stage artifact.
9. **THE DOSSIER MUST AGREE WITH `:ANSWER`.** An `:answer` claiming the workflow finished while
   `acceptance.md` still shows `(open)` criteria or `verdict.md` is missing is a lie; if `:answer`
   says `:achieved`, `workflow$verdict-outcome` MUST return `:outcome :achieved`.

### 7.5 (Historical) original instruction

The original revision-1 instruction routed stage dispatch through `CALL-TOOL`, loaded EDN templates,
constructed acceptance/stages vectors-of-maps via `workflow$bootstrap`, and flipped status via
`workflow$update-stage` / `workflow$update-acceptance`. That prompt is preserved in git history; the
shipped prompt above replaces it.

<details>
<summary>Original CALL-TOOL/EDN instruction sketch (historical)</summary>

```text
You are a WORKFLOW-agent. You drive a domain-specific multi-stage workflow by
composing functional agents (research / explore / plan / todo / exec / eval /
mcp / skill / coact / rlm) through CALL-TOOL, maintaining a durable workflow
dossier in .brainyard/agents/workflow-agent/<id>/ that threads PURPOSE, ACCEPTANCE,
and CUMULATIVE FINDINGS across every stage. You decide which template to
start from, which stages run, in what order, when to pause for user input,
and when to finish. The CoAct loop and the dossier are your only fixed
scaffolding.

────────────────────────────────────────────────────────────────────────────
WORKFLOW TEMPLATES (recommendations, not contracts)
────────────────────────────────────────────────────────────────────────────
Templates live at .brainyard/workflows/<domain>.edn. Each declares:
- :workflow/id, :workflow/name, :workflow/description
- :acceptance — workflow-level criteria with stable :id values
- :stages     — typical sequence with :purpose, :recommended-agent, :gate-after,
                :acceptance-focus per stage
- :defaults   — :hitl mode, :max-stage-attempts, :sub-lm

The user typically names a template ("run the feature-launch workflow for X")
or asks domain-shaped questions you can map to a template ("ship feature Y" →
:feature-launch). If no template is named or matches, you may either:
  (a) start workflow-shaped work without a template (template = :ad-hoc), or
  (b) ASK the user whether to use a particular template before proceeding.

Template stages are RECOMMENDATIONS. You may:
- Skip a stage when the workflow's purpose makes it unnecessary.
- Insert a stage when work surfaces an unforeseen step.
- Re-run a stage when later stages reveal a defect upstream.
- Reorder stages when domain reality differs from the template.
- Substitute the agent (e.g., trivial research-feasibility → coact-agent
  instead of research-agent).
Document deviations from the template in the dossier ## Stage progress.

────────────────────────────────────────────────────────────────────────────
TURN 1 — DOSSIER BOOTSTRAP (the only fixed obligation)
────────────────────────────────────────────────────────────────────────────
Before reaching for any functional agent, on iteration 1:

1. Resolve the workflow template:
   a. If the user named it ("run feature-launch for X"), load
      .brainyard/workflows/<id>.edn.
   b. If they named a path, load that.
   c. If they described domain-shaped work without naming a template,
      `(call-tool "search" {:query "<domain keyword>"})` against
      .brainyard/workflows/ to find a candidate; if exactly one matches,
      use it; if zero or many match, surface the choice in your :answer
      and STOP (CLARIFY).
   d. Otherwise treat as :ad-hoc — no template loaded.

2. Compute <workflow-id> as <template-id>--<question-slug> (or just
   <question-slug> for :ad-hoc). Same kebab-case + stopword-drop scheme
   research-agent uses. Re-using an existing id triggers RESUME mode (§).

3. If .brainyard/agents/workflow-agent/<id>/ exists → RESUME mode:
   read dossier.md and findings.log, summarize state in your :thought,
   then pick up at the next pending stage.

4. Otherwise → BOOTSTRAP mode:
   a. Create the directory.
   b. Copy the template to template.edn (or write {:template :ad-hoc}).
   c. Write purpose.md (verbatim user question + any :agent-context).
   d. Draft acceptance.md from the template's :acceptance, refining
      criteria with the user's specifics. SURFACE the criteria in your
      :answer for confirmation BEFORE running any stages — same hard
      rule as research-agent.
   e. Write stages.edn — copy template :stages with :status :pending.
      For :ad-hoc, sketch a stage list yourself (3–8 stages) and ASK
      the user to confirm before proceeding.
   f. Build dossier.md (frontmatter + body).
   g. Initialize findings.log empty.

────────────────────────────────────────────────────────────────────────────
STATE MACHINE — moves you pick from each iteration
────────────────────────────────────────────────────────────────────────────
After bootstrap, every iteration picks ONE of these moves. Templates suggest
the typical sequence; you decide which fits the current dossier state.

A. RUN-STAGE     — call-tool the recommended (or substituted) agent for
                   the next pending stage with full :agent-context (dossier
                   path + acceptance focus + prior artifacts).
                   Use when: a pending stage's prerequisites are met.

B. EVAL-STAGE    — call-tool eval-agent (or run a query$llm-based
                   evaluation) to score whether a just-completed stage
                   satisfied its :acceptance-focus criteria.
                   Use when: a stage call returned but the workflow
                   acceptance flip is non-obvious.

C. GATE          — populate :answer with a gate prompt; the loop exits;
                   the user replies; you resume and read their decision
                   from conversation history.
                   Use when: stage's :gate-after is :user, OR the
                   :hitl-mode requires a checkpoint here, OR the LLM
                   judges human approval is warranted (smart-pause).

D. RE-RUN        — re-run a previous stage with adjusted instructions
                   (e.g., re-run :implement after :test surfaces a bug).
                   Use when: a downstream stage reveals an upstream
                   defect.

E. INSERT        — add a new stage to stages.edn (e.g., :migrate-data
                   appears between :plan-design and :implement) and
                   immediately RUN-STAGE on it.
                   Use when: real work reveals a gap the template missed.

F. SKIP          — mark a pending stage :skipped with rationale.
                   Use when: workflow purpose makes the stage unnecessary
                   (e.g., :release-notes for an internal-only change).

G. SYNTHESIZE    — query$llm-backed roll-up of findings.log into a
                   refreshed dossier ## Stage progress, OR a
                   reconciliation across conflicting findings.
                   Use when: stage findings have accumulated to the
                   point that the body needs regeneration; OR two
                   stages' outputs disagree.

H. CLARIFY       — populate :answer with a clarification request
                   (open scope, conflicting requirements, missing
                   credentials).
                   Use when: progress is blocked on user input.

I. FINALIZE      — populate :answer with the final workflow report;
                   write verdict.md; append INDEX.md; loop exits.
                   Use when: every workflow-level acceptance criterion
                   is :satisfied (=> :achieved), some are
                   :satisfied/:descoped (=> :partial), or work hit an
                   unresolvable blocker (=> :abandoned).

────────────────────────────────────────────────────────────────────────────
CALLING A FUNCTIONAL AGENT FOR A STAGE
────────────────────────────────────────────────────────────────────────────
Every stage's RUN-STAGE call goes through call-tool. The :agent-context
MUST include the workflow dossier path. Recommended shape:

    Workflow dossier: .brainyard/agents/workflow-agent/<id>/dossier.md
    Stage:            <stage-id>
    Stage purpose:    <one-line distillation>
    Acceptance focus: <criterion-id(s) this stage should advance>
    Prior artifacts:
      - research dossier: <path>      (omit if none)
      - plan slug:        <slug>      (omit if none)
      - todo slug:        <slug>      (omit if none)
      - last eval:        <path>      (omit if none)
    Hint: <any stage-specific guidance from the template or your judgment>

Pick the agent:
- Default to the template's :recommended-agent.
- Substitute when: the question is trivial (use coact-agent), the data is
  too big for a single specialist (use rlm-agent for MapReduce stages),
  the stage is multi-source discovery (use explore-agent), the stage is
  multi-specialist research (use research-agent — yes, workflow-agent
  can call research-agent, which itself calls explore/plan/todo/exec/eval;
  these are FLAT layers, not recursion).

────────────────────────────────────────────────────────────────────────────
HITL — APPROVAL DISCIPLINE
────────────────────────────────────────────────────────────────────────────
The dossier records the active :hitl_mode. The five modes (config knob,
not five different code paths):

- :auto        — no approval prompts; only :answer-channel for terminal
                 reporting.
- :gates       — DEFAULT. Prompt only at stages whose template
                 :gate-after is :user, AND at FINALIZE.
- :checkpoint  — prompt after every RUN-STAGE that touched real state
                 (exec / mcp write / shell), AND at FINALIZE.
- :co-pilot    — prompt before every RUN-STAGE.
- :step        — prompt before every move (including INSERT, SKIP,
                 RE-RUN). Useful for debugging the workflow itself.

A "prompt" is a GATE move (C above): you populate :answer with a clearly
labelled question, ending with `Awaiting workflow gate: <stage-id>` on
its own line. The user replies; on the next turn you resume by reading
the conversation history (CoAct provides the prior turn).

You may UPGRADE the HITL mode mid-workflow if the LLM judges higher
oversight is warranted (e.g., a stage involves write-side MCP). You may
NOT downgrade without explicit user approval.

────────────────────────────────────────────────────────────────────────────
DECISION HEURISTICS
────────────────────────────────────────────────────────────────────────────
1. Stage prerequisites met, no gate needed              → RUN-STAGE
2. Stage just finished, acceptance flip non-obvious     → EVAL-STAGE
3. Template :gate-after :user, mode :gates              → GATE
4. Mode :checkpoint, just completed a state-touching stage → GATE
5. Test stage failed                                    → RE-RUN :implement
6. Real work surfaced a missing step                    → INSERT
7. Workflow purpose makes a template stage unnecessary  → SKIP (with note)
8. findings.log >100 lines                              → SYNTHESIZE (refresh body)
9. User input needed (scope, credential, ambiguity)     → CLARIFY
10. All workflow acceptance criteria :satisfied         → FINALIZE :achieved
11. Mix of :satisfied + :descoped + :partial            → FINALIZE :partial
12. Hard blocker after RE-RUN attempt budget exhausted  → FINALIZE :abandoned
13. Hit 80% iteration cap with no candidate verdict     → FINALIZE :partial

────────────────────────────────────────────────────────────────────────────
DOSSIER UPDATE DISCIPLINE (after every stage call)
────────────────────────────────────────────────────────────────────────────
1. Append one NDJSON line to findings.log with iter / stage / agent /
   summary / pointers (research dossier path / plan slug / todo slug /
   eval verdict / etc).
2. Update stages.edn: flip :status, set :artifact, increment :attempts.
3. If the stage advanced workflow acceptance, flip the relevant criterion
   in dossier.md frontmatter.
4. Update artifacts/ symlinks to the new outputs.
5. Periodically (every ~5 stages, or before FINALIZE) rebuild the
   dossier body's ## Stage progress from findings.log via SYNTHESIZE.

The dossier IS the cross-stage context contract. Failing to update it
breaks the next stage's :agent-context.

────────────────────────────────────────────────────────────────────────────
TERMINATION RULES
────────────────────────────────────────────────────────────────────────────
You terminate by populating :answer with a markdown report AND writing
verdict.md AND appending INDEX.md. Three legitimate states:

- :achieved   — every workflow-level acceptance criterion :satisfied
                (or :descoped with explicit user descope). Report what
                stages ran, cite all per-stage artifacts, surface any
                noteworthy follow-ups.
- :partial    — at least one :satisfied and at least one
                :partial / :open / :descoped, but not all :open.
                Report what was done, what remains, recommended next
                workflow turn.
- :abandoned  — hard blocker hit (capability missing, irreconcilable
                requirements, RE-RUN budget exhausted, iteration cap).
                Report what was tried, why it stopped, what would
                unblock it.

Always include `Saved workflow dossier: .brainyard/agents/workflow-agent/<id>/`
on its own line near the end of :answer.

────────────────────────────────────────────────────────────────────────────
HARD RULES
────────────────────────────────────────────────────────────────────────────
1. NO query$clone. Cross-agent dispatch goes through call-tool.
2. NO direct .brainyard/plans/, .brainyard/todos/, eval-results writes.
   These belong to specialists; you read-file freely, you do not write.
3. NO direct edit of .brainyard/workflows/<template>.edn from inside a
   workflow run. Templates are version-controlled domain knowledge —
   improvements come from the user (or skill-agent for skill backends),
   NOT mid-flight self-modification.
4. Acceptance criteria are FROZEN once the user has confirmed them.
   Descope only with explicit user approval.
5. Every stage call's :agent-context MUST include the dossier path.
6. The dossier is the only durable cross-iteration state.
7. Default iteration budget: 50. At 80% with no candidate verdict,
   start preparing FINALIZE. Honest :partial > silent timeout.
8. CITE EVERYTHING. Every claim in the verdict points to a stage
   artifact (research dossier / plan / todo / eval / explore result).

────────────────────────────────────────────────────────────────────────────
RESUMING A WORKFLOW
────────────────────────────────────────────────────────────────────────────
The user can re-invoke with `@<workflow-id>` (or by re-asking the same
domain-shaped question). On resume:

1. Read dossier.md frontmatter + last 80 lines of findings.log + stages.edn.
2. Reconstruct the open acceptance criteria + pending stages.
3. Decide the next state-machine move from current state — do NOT
   re-bootstrap, do NOT re-run :satisfied stages.
4. Surface a "where we are" paragraph in your first :thought (visible
   in trajectory) so the resume context is auditable.

If the dossier disagrees with reality (a referenced plan was deleted,
a research dossier is missing) → CLARIFY (H): name the discrepancy
and ask the user how to proceed.
```

</details>

---

## 8. Tool-Context (How to Use the Bound Tools) — As-Built Shape

The shipped tool-context lives in `common/workflow_agent.clj` (`def tool-context`) and is the source
of truth. Its as-built shape:

- **Functional agents (direct kebab-case invocation):** `(<name> {:question "<q>" :agent-context
  "<dossier path + …>"})`. Each is annotated with the `Saved …:` handoff prefix it emits —
  research-agent (`Saved research dossier:`), explore-agent (`Saved exploration:`), plan-agent,
  todo-agent, exec-agent, eval-agent, mcp-agent, skill-agent, **edit-agent** (`Saved edit:` +
  `Rollback:`), rlm-agent, coact-agent.
- **Dossier + template substrate (the direct work surface):** `read-file`, `write-file`
  (authors `dossier.md` / `acceptance.md` / `stages.md` / `verdict.md` / `INDEX.md` **and** workflow
  templates; `:append true` for `findings.log` / `INDEX.md`), `update-file` (index-free single-line
  flips of a criterion or stage, or a template line, by stable id), `grep`, `bash` (`mkdir -p`, `ls`,
  `ln -s`, `rm`), `search`.
- **Synthesis:** `query$llm` (distill `findings.log` → `## Stage progress`; draft the `verdict.md`
  narrative; reconcile conflicting outputs).
- **Bookkeeping:** `list-tools`, `get-tool-info`; `task$run` for >5s stage calls;
  `agent-runtime$config` for `:max-iterations` tuning.
- **`workflow$*` seams (READ/DERIVE/VALIDATE only):** the six of §9.
- **VERDICT TEMPLATE** (written to `verdict.md`) and **TEMPLATE TEMPLATE** (written to
  `.brainyard/workflows/<domain>.md` in authoring mode) are carried verbatim in the tool-context.
- **Anti-patterns** called out inline: running without confirmed acceptance; slavishly following the
  template; authoring plan/todo/dossier files via `write-file` directly (go through the specialist);
  editing a template mid-run; finalizing `:achieved` while a criterion is `(open)`.

<details>
<summary>Original CALL-TOOL/EDN tool-context sketch (historical)</summary>

```text
## Workflow Tools — functional agents + dossier substrate

### Functional agents (reach via call-tool — NOT bound directly)
Every agent below is invocable as `(call-tool "<name>" {:question "<q>"
:agent-context "<dossier path + …>"})`. Pick per stage purpose.

- research-agent  — multi-specialist research thread (explore + plan + todo
                    + exec + eval). Use for stages whose purpose is "figure
                    something out end-to-end."
- explore-agent   — discovery across files / web / MCP / skills. Use for
                    stages whose purpose is "find / inventory."
- plan-agent      — author / inspect a .brainyard/plans/<slug>.md.
                    Use for design-only stages.
- todo-agent      — spawn / maintain a todo from a plan. Use only when
                    plan-agent already produced a plan AND you need a
                    fresh todo (vs. the plan-agent → todo-agent transition
                    inside a research-agent thread).
- exec-agent      — drive an existing todo to completion. Use for
                    do-the-work stages once a plan + todo exist.
- eval-agent      — score executed todo against plan ## Acceptance.
                    Use for verification stages.
- mcp-agent       — MCP lifecycle + write operations (creating Linear
                    tickets, posting to Slack, etc.). Use for stages
                    whose purpose is external-system mutation.
- skill-agent     — skill authoring / installation. Use for stages
                    around skill management.
- rlm-agent       — MapReduce over too-big context. Use for stages
                    whose purpose is "summarize / classify across
                    hundreds of inputs."
- coact-agent     — generic CoAct fallback when no specialist fits.
                    Use sparingly.

### Dossier substrate (your direct work surface)
- read-file      -- Read dossier.md / acceptance.md / findings.log /
                    stages.edn / artifacts.
- write-file     -- Update dossier.md / acceptance.md / verdict.md /
                    stages.edn. USE :append true for findings.log.
- update-file    -- Targeted edit on dossier.md frontmatter / stages.edn
                    (e.g. flipping one stage's :status without rewriting
                    the whole file).
- grep           -- Cheap scan inside dossier files / template.
- bash           -- mkdir -p, ls, find, ln -s, cp (template → dossier).
- search         -- Cross-project keyword search. Use for finding a
                    workflow template in .brainyard/workflows/ during
                    bootstrap.

### Synthesis
- query$llm      -- Cross-stage synthesis. Use for:
                    • Distilling 5–20 findings.log entries into a refreshed
                      dossier ## Stage progress.
                    • Drafting verdict.md from per-stage artifacts.
                    • Reconciling conflicting stage outputs.
                    Single OR batched (:prompts) for parallel
                    distillations.

### Bookkeeping
- list-tools, get-tool-info, call-tool — generic registry access.
- task$run with :job-type :tool|:bash — async wrappers when a stage call is
                                  expected to take >5s (rare; agents
                                  return promptly).

## workflow$* helpers (auto-bound; see §9)
- workflow$id            -- Deterministic id from template + question.
- workflow$resume?       -- Cheap probe to decide bootstrap vs. resume.
- workflow$list-templates -- Enumerate project + user + built-in templates.
- workflow$load-template -- Load and validate one template.
- workflow$install-starters -- Copy built-in starters to .brainyard/workflows/.
- workflow$bootstrap     -- Create dossier directory + initial files
                            from a template (or :ad-hoc).
- workflow$append-log    -- Append one NDJSON line to findings.log.
- workflow$update-stage  -- Flip a stage's :status (with audit fields).
- workflow$update-acceptance -- Flip a workflow acceptance criterion's
                                :status.
- workflow$write-verdict -- Compose verdict.md from final state.
- workflow$index-append  -- Append one line to .brainyard/agents/workflow-agent/INDEX.md.

(As-built: `workflow$summarize-log` was never shipped — the agent calls
 `query$llm` directly to roll up findings.log.)

If these helpers aren't bound, build the equivalent inline with
write-file + clojure fence.

## Typical flow (no specific iteration count required)
1. iter 1 — bootstrap (resolve template; create dossier; surface
   acceptance for confirmation).
2. iter 2..N — pick a state-machine move per dossier state:
   RUN-STAGE / EVAL-STAGE / GATE / RE-RUN / INSERT / SKIP /
   SYNTHESIZE / CLARIFY / FINALIZE.
3. After every stage call: append findings.log; update stages.edn;
   refresh artifacts/.
4. On termination: write verdict.md; append INDEX.md; populate :answer
   with markdown report + `Saved workflow dossier: <path>` line.
```

</details>

---

## 9. `workflow$*` Sandbox Helpers — READ/DERIVE/VALIDATE only

The helpers live in `ai.brainyard.agent.common.workflow`, register as `defcommand`s, and surface in
the sandbox via auto-binding. **The lightweight redesign retired the structured-construction
helpers** — the dossier + templates are authored directly with the file tools. What survives is the
mechanism a machine does better than the model: deterministic id, the resume/parse seam, template
discovery + load+validate + starter install, and verdict-outcome derivation with the `:achieved`
guard. The shipped `workflow-helpers` roster binds exactly **six**:

| Helper | Signature | What it does |
|---|---|---|
| `workflow$id` | `(workflow$id :template <kw\|:ad-hoc> :question …)` → `{:slug "<id>"}` | Deterministic resume key: `<template-id>--<question-slug>` (or just `<question-slug>` for `:ad-hoc`). |
| `workflow$resume?` | `(workflow$resume? :id …)` → `{:exists? :status :last-iteration :hitl-mode :acceptance-state {…} :pending-stages […] :stage-count :n-pending}` | Cheap probe: parses `dossier.md` frontmatter + the `acceptance.md` / `stages.md` checklists (dual-reads legacy frontmatter acceptance + `stages.edn`). Iter-1 bootstrap-vs-resume gate. |
| `workflow$list-templates` | `(workflow$list-templates :include-built-in? …)` → `{:templates [{:id :name :description :source :path} …]}` | Enumerate project + user + built-in `*.md` (and legacy `*.edn`) templates. |
| `workflow$load-template` | `(workflow$load-template :id … \| :path …)` → `{:template {…} :source :path}` or `{:error …}` | Parse a markdown template (dual-reads legacy EDN) into `{:workflow/id :workflow/name :defaults :acceptance [{…}] :stages [{…}]}` and **validate** (required keys, ≥1 stage, every stage `focus` resolves to an acceptance id). Called after any template create/update. |
| `workflow$install-starters` | `(workflow$install-starters :overwrite? …)` → `{:installed […] :skipped […] :dir …}` | Copy built-in **markdown** starters (`feature-launch`, `doc-update`) to `.brainyard/workflows/`. Idempotent. |
| `workflow$verdict-outcome` | `(workflow$verdict-outcome :id …)` → `{:outcome :achieved\|:partial\|:abandoned\|:in-progress :achieved-ok? :blockers [...] :acceptance-outcome {…} :stage-outcomes {…}}` | **(New — carved from `write-verdict`.)** READ-ONLY: derives the verdict from the checklists + enforces the `:achieved` guard. Called BEFORE `write-file`-ing `verdict.md`. |

**Retired** (do not exist anymore): `workflow$bootstrap` (the `:acceptance`/`:stages`
vectors-of-maps construction), `workflow$update-stage`, `workflow$update-acceptance`,
`workflow$write-verdict` (frontmatter emission), `workflow$append-log`, `workflow$index-append`, and
the never-implemented `workflow$summarize-log`. Bootstrap is `bash mkdir` + `write-file` of the
checklists; status flips are index-free `update-file`s; the verdict is a direct `write-file` after
`workflow$verdict-outcome`; the log + INDEX are `write-file :append`.

### 9.1 Auto-Finalize Backstop

`workflow.clj` installs an `:agent.ask/finalize` hook (`workflow-auto-finalize`, scoped to
workflow-agent) as a safety net for when the LLM emits a non-blank answer but skips the FINALIZE
fence. It is **gated**: it fires only when the dossier exists, **no** acceptance criterion is
`:open`, and `verdict.md` does not already exist. It then derives the outcome (`derive-outcome` over
the acceptance-state map), renders `verdict.md` via `render-verdict-md`, appends `INDEX.md`, and
injects the absent `Saved workflow dossier:` line. It is idempotent (the `Saved workflow dossier:`
prefix check + verdict-exists check), defensive (failures logged, never re-thrown), and self-installs
at namespace load. Per-turn opt-out via `agent-runtime$config :key "workflow-auto-finalize" :value
"false"`. (The original proposal placed it on `:agent.ask/post`; as-built it is
`:agent.ask/finalize`.)

<details>
<summary>Original 11-helper roster (historical)</summary>

The revision-1 proposal sketched eleven helpers, including the now-retired construction helpers:

| Helper | Signature | What it does |
|---|---|---|
| `workflow$id` | `(workflow$id :template … :question …)` → `"<id>"` | Deterministic id: `<template-id>--<question-slug>` (or just `<question-slug>` for `:ad-hoc`). |
| `workflow$bootstrap` | `(workflow$bootstrap :id … :template-path … :purpose … :acceptance […])` → `{:dir … :dossier-path …}` | Create directory; copy template.edn; write purpose/acceptance/dossier/stages/findings. Idempotent: existing dir returns its current state. |
| `workflow$list-templates` | `(workflow$list-templates :base-dir … :include-built-in? …)` → `{:templates [{:id :name :description :source :path|:resource} …]}` | Enumerate project + user + built-in templates. |
| `workflow$load-template` | `(workflow$load-template :id … | :path …)` → `{:template … :source … :path …}` or `{:error …}` | Load and validate one template (resolves project > user > built-in). |
| `workflow$install-starters` | `(workflow$install-starters :overwrite? …)` → `{:installed […] :skipped […] :dir …}` | Copy built-in starters (`feature-launch`, `doc-update`) from classpath to `.brainyard/workflows/`. Idempotent. |
| `workflow$append-log` | `(workflow$append-log :id … :iter … :stage … :agent … :action … :summary … :pointers {…})` → `{:appended true}` | Append to findings.log. |
| `workflow$update-stage` | `(workflow$update-stage :id … :stage-id … :status :satisfied :artifact …)` → `{:updated true}` | Targeted edit of stages.edn. |
| `workflow$update-acceptance` | `(workflow$update-acceptance :id … :criterion-id … :status :satisfied)` → `{:updated true}` | Targeted edit of dossier.md frontmatter. |
| `workflow$write-verdict` | `(workflow$write-verdict :id … :status :achieved|:partial|:abandoned :narrative …)` → `{:path …}` | Compose verdict.md. |
| `workflow$index-append` | `(workflow$index-append :id … :status … :one-line …)` → `{:appended true}` | Prepend to `.brainyard/agents/workflow-agent/INDEX.md`. |
| `workflow$resume?` | `(workflow$resume? :id …)` → `{:exists? true :status :in-progress :pending-stages [...] :hitl-mode … :acceptance-state {…}}` | Cheap probe for bootstrap vs. resume. |

The redesign retired the six construction helpers above (`bootstrap`, `append-log`, `update-stage`,
`update-acceptance`, `write-verdict`, `index-append`) and added `workflow$verdict-outcome` — leaving
the six READ/DERIVE/VALIDATE seams shipped today.

</details>

---

## 10. Behavior Tree — Inherited As-Is

`workflow-agent` does NOT define its own BT. `run-coact-derived` falls back to `coact-agent`'s `:bt-factory`:

```
coact-behavior-tree
  ├─ preflight (question-present?)
  ├─ prepare-conversation / prepare-recalled-memory
  ├─ coact-init-action
  ├─ coact-loop-subtree            ; ThinkActCode → router → accumulate
  ├─ answer-present?
  ├─ optional finalize pass
  ├─ coact-store-results-action
  └─ trace/default-maintain-conversation
```

The CoAct router (answer / code / tool / repair) is exactly what gives the LLM the per-iteration freedom the pipeline executor denied. There is no DAG walker, no `:depends-on` resolution, no on-failure policy switch — those are reasoning calls now.

The default iteration cap is bumped to 50 via the agent's `:max-iterations` override (vs. CoAct's 20, research-agent's 30). Workflows have legitimate reason to take more steps. Override via `agent-runtime$config :key "max-iterations" :value "N"`.

No new BT actions, no new schemas, no SCI binding additions are required.

---

## 11. Demonstration: Running the `feature-launch` Workflow

User question: *"Run the feature-launch workflow to ship the MCP server health-check command. Target this Tuesday."*

### Iteration 1 — Bootstrap (code channel)

The template is loaded (read seam) and the dossier files are authored DIRECTLY — `bash mkdir` +
`write-file` of the two checklists — no construction helper:

````markdown
```clojure
(def tmpl (:template (workflow$load-template :id :feature-launch)))  ; read + validate (kept)
(def wid  (:slug (workflow$id :template :feature-launch
                  :question "Ship the MCP server health-check command, target this Tuesday")))
;; => "feature-launch--mcp-server-health-check"

(when-not (:exists? (workflow$resume? :id wid))
  (def d (str ".brainyard/agents/workflow-agent/" wid "/"))
  (bash {:command (str "mkdir -p " d "artifacts")})
  (write-file {:path (str d "purpose.md")
               :content "Add a health-check command to the MCP server, surfaced in the TUI status bar. Target rollout: Tuesday.\n"})
  ;; acceptance.md — the ACCEPTANCE CHECKLIST (refined from the template)
  (write-file {:path (str d "acceptance.md")
               :content (str "# Acceptance — " wid "\n"
                             "- [ ] a1 (open) — Health-check command reachable via mcp$server :op :health-check\n"
                             "- [ ] a2 (open) — PR merged to main\n"
                             "- [ ] a3 (open) — Unit tests added with >= 80% line coverage on new code\n"
                             "- [ ] a4 (open) — Release notes published\n"
                             "- [ ] a5 (open) — Slack #releases announcement sent\n")})
  ;; stages.md — the STAGE CHECKLIST (from the template, adapt as needed)
  (write-file {:path (str d "stages.md")
               :content (str "# Stages — " wid "\n"
                             "- [ ] s1 research-feasibility (pending) — validate feasibility {agent: research-agent, gate: user, focus: [a1]}\n"
                             "- [ ] s2 plan-design (pending) — author the plan {agent: plan-agent, gate: none, focus: [a1, a2]}\n"
                             "- [ ] s3 implement (pending) — execute the todo {agent: exec-agent, gate: none, focus: [a2, a3]}\n"
                             "- [ ] s4 test (pending) — run/extend tests {agent: exec-agent, gate: none, focus: [a3]}\n"
                             "- [ ] s5 release-notes (pending) — draft release notes {agent: coact-agent, gate: none, focus: [a4]}\n"
                             "- [ ] s6 announce (pending) — notify stakeholders {agent: mcp-agent, gate: user, focus: [a5]}\n")})
  (write-file {:path (str d "dossier.md")
               :content (str "---\nworkflow_id: " wid "\nworkflow_template: feature-launch\n"
                             "created: <ISO>\nlast_iteration: 1\nstatus: in-progress\nhitl_mode: gates\n---\n"
                             "\n## Purpose\nAdd a health-check command…\n\n## Stage progress\n_(populated as stages run)_\n")}))
```
````

Then `:answer` surfaces the acceptance criteria for the user to confirm, and the loop pauses. User replies "looks good, run it."

### Iteration 2 — RUN-STAGE s1 research-feasibility (code channel — direct dispatch)

````markdown
```clojure
(research-agent
  {:question      "Investigate the feasibility of adding a health-check command to the MCP server, surfacing per-server status in the TUI status bar. Identify code touch points and unknowns."
   :agent-context (str "Workflow dossier: .brainyard/agents/workflow-agent/" wid "/dossier.md\n"
                       "Stage: s1 research-feasibility\nAcceptance focus: a1\n"
                       "Hint: focus on existing mcp$server :op :health and TUI status-bar wiring.")})
```
````

research-agent runs its own loop (exploring, planning, evaluating) and returns an answer with `Saved research dossier: .brainyard/agents/research-agent/feature-launch--mcp-server-health-check/`.

### Iteration 3 — Log + flip status index-free (code channel)

````markdown
```clojure
;; append one plain findings line (write-file :append)
(write-file {:path (str d "findings.log") :append true
             :content "iter 2 · s1 research-feasibility · research-agent · Saved research dossier: .brainyard/agents/research-agent/feature-launch--mcp-server-health-check/\n"})

;; flip the stage status by stable id — index-free (NOT by ordinal)
(update-file {:path (str d "stages.md")
              :pattern "- [ ] s1 research-feasibility (pending)"
              :replacement "- [x] s1 research-feasibility (satisfied)"})

;; a1 is an implementation criterion — still open after research; leave it
```
````

Stage `s1`'s inline `gate: user` — workflow-agent enters GATE.

### Iteration 4 — GATE (answer channel; loop pauses)

```markdown
**Stage gate — research-feasibility complete.**

Findings: feasible. Two touch-points:
- `components/agent/.../mcp/integration.clj` — add `:op :health-check` to the existing mcp$server polymorphic command.
- `bases/agent-tui/.../status_bar.clj` — slot for per-server health indicator.

Risks: none surfaced.

Next stage: `s2 plan-design` via plan-agent.

Awaiting workflow gate: s1 research-feasibility
```

User replies "go ahead." Loop resumes on the next turn.

### Iterations 5–13 — RUN-STAGE through implementation, test, release notes

Plan-agent drafts `mcp-server-health-check` plan; gate; exec-agent runs the todo (5 items); query$llm synthesizes the log into `## Stage progress`; eval-agent verifies a1 + a3; coact-agent drafts release notes for a4. After each move: append `findings.log`, flip the stage (and any advanced criterion) index-free.

### Iteration 14 — Last stage with `gate: user` (announce)

Workflow-agent presents the announcement draft, asks for approval, then on the next turn calls mcp-agent to post.

### Iteration 15 — FINALIZE (code channel + answer channel)

Flip every remaining criterion to reflect reality, derive + guard, then write `verdict.md` and append INDEX — all file-native:

````markdown
```clojure
;; Step 1 — make the checklists reflect reality (index-free flips; e.g.)
(update-file {:path (str d "acceptance.md")
              :pattern "- [ ] a2 (open)" :replacement "- [x] a2 (satisfied)"})
;; … a4, a5 likewise …

;; Step 2 — derive the outcome + enforce the :achieved guard (read-side; kept)
(def vo (workflow$verdict-outcome :id wid))
;; => {:outcome :achieved :achieved-ok? true :blockers []
;;     :acceptance-outcome {:a1 "satisfied" …} :stage-outcomes {:s1 "satisfied" …}}

;; Step 3 — write verdict.md DIRECTLY from the VERDICT TEMPLATE (no helper)
(write-file {:path (str d "verdict.md")
             :content "<filled VERDICT TEMPLATE — status (:outcome vo); acceptance_outcome/stage_outcomes from vo>"})

;; Step 4 — append one INDEX line
(write-file {:path ".brainyard/agents/workflow-agent/INDEX.md" :append true
             :content (str "- <YYYY-MM-DD HH:MM> [" wid "](" wid "/) — ACHIEVED · Shipped MCP health-check command; PR #2347 merged Tue.\n")})
```
````

Final answer:

```markdown
## Workflow complete — `feature-launch--mcp-server-health-check`

**Status:** ACHIEVED — all 5 acceptance criteria satisfied.

| ID | Criterion | Outcome |
|---|---|---|
| a1 | Health-check command reachable via mcp$server :op :health-check | satisfied |
| a2 | PR merged to main | satisfied (PR #2347) |
| a3 | Unit tests added (>= 80% coverage) | satisfied (89% on new code) |
| a4 | Release notes published | satisfied |
| a5 | Slack #releases announcement sent | satisfied |

**Stages run:**
1. `s1 research-feasibility` (research-agent) — feasibility confirmed, two touch-points identified.
2. `s2 plan-design` (plan-agent) — `mcp-server-health-check` plan; 5 items.
3. `s3 implement` (exec-agent) — items 0–4 done; PR #2347 opened.
4. `s4 test` (exec-agent) — coverage 89% on new code; one flaky test fixed.
5. `s5 release-notes` (coact-agent) — published to docs/releases/.
6. `s6 announce` (mcp-agent) — Slack #releases at 14:32 Tue.

**Artifacts:**
- Research dossier: `.brainyard/agents/research-agent/feature-launch--mcp-server-health-check/`
- Plan: `.brainyard/plans/mcp-server-health-check.md`
- Todo: `.brainyard/todos/mcp-server-health-check.md`
- Release notes: `docs/releases/2026-05-12-mcp-health-check.md`

Saved workflow dossier: .brainyard/agents/workflow-agent/feature-launch--mcp-server-health-check/
```

---

## 12. Anti-Pattern Catalogue

| # | Bad pattern | Why bad | Correct shape |
|---|---|---|---|
| 1 | Skip bootstrap; jump straight to RUN-STAGE | No dossier → no acceptance → workflow rudderless | Always bootstrap on iter 1 (or detect resume). |
| 2 | Slavishly follow the template even when reality has changed | Templates are recommendations, not contracts | INSERT/SKIP/RE-RUN/reorder as the work demands; document deviation. |
| 3 | Never write workflow-level acceptance | Final verdict has nothing to score against | Acceptance is bootstrap turn-1 obligation. Surface to user for confirm. |
| 4 | Use `query$clone` to "delegate" the workflow | Clones workflow-agent itself = clone-self recursion | Stay flat; reach the functional agents via direct kebab-case dispatch. |
| 5 | Inline the full dossier body in every `:agent-context` | Bloats stage context; specialists read-file what they need | Pass path + 4-line distillation. |
| 6 | Ignore HITL mode | Surprises the user with state-mutating stages | Read `hitl_mode` from dossier; GATE per the rules in §7. |
| 7 | Re-run a `(satisfied)` stage on resume | Wastes work and may un-do prior state | Resume reads `stages.md`; only `(pending)` / `(failed)` / `(in-progress)` are eligible. |
| 8 | Write `.brainyard/plans/<slug>.md` directly | Bypasses plan-agent's safety + slug-collision checks | Always go through plan-agent. |
| 9 | Treat workflow as one big research turn | research-agent already exists for that | If the question is single-thread research → research-agent. workflow-agent is for multi-stage domain workflows where most stages are themselves multi-specialist. |
| 10 | Edit `.brainyard/workflows/<template>.md` mid-run | Templates are domain knowledge under version control | Edit templates in AUTHORING MODE (a separate invocation); mid-run deviations live in the dossier, not the template (Hard Rule 3). |
| 13 | Flip a checklist line by ordinal / rewrite the whole file | Off-by-one corrupts the wrong stage/criterion | `update-file` by stable id (`a2`, `s2`); never by position. |
| 14 | FINALIZE `:achieved` while a criterion is `(open)` | The verdict lies | `workflow$verdict-outcome` refuses it — fix the checklists, don't downgrade to hide it. |
| 11 | FINALIZE early because some stages are :satisfied | :achieved requires ALL workflow acceptance, not all stages | Workflow-level acceptance is the gate. Stages are the means. |
| 12 | Push past iteration cap silently | User gets opaque timeout | At 80%, prepare FINALIZE :partial. Honest reporting > silent timeout. |

---

## 13. Verification

| Benchmark / smoke test | Shape | What it verifies |
|---|---|---|
| Single-template happy path | Run `feature-launch` end-to-end on a small change. | Bootstrap writes the acceptance + stages checklists → 6 stages → FINALIZE `:achieved`; `verdict.md` written; INDEX.md appended. |
| Resume mid-workflow | Kill TUI between stages, resume with `@<id>`. | `workflow$resume?` reloads from the checklists; only `(pending)` stages run; no duplicate work. |
| Template CRUD | Create a `.md` template (`write-file`); `workflow$load-template` validates it; update a stage line (`update-file`) by id; delete (`rm`). | All without EDN construction or template-write helpers; authoring mode (no dossier). |
| Stage flip (index-free) | `update-file` flips `s2 (pending)` → `s2 (satisfied)`. | Resume reflects it; other stages untouched (matched by id, not ordinal). |
| Acceptance flip + verdict guard | `workflow$verdict-outcome` while a criterion is `(open)`. | Refuses `:achieved`; accepts once all `(satisfied)`/`(descoped)`. |
| Template mismatch (RE-RUN) | Force exec-agent to surface a bug in the test stage. | Workflow re-runs `s3 implement`; second pass succeeds; verdict `:achieved`; `attempts` derivable from `findings.log`. |
| INSERT new stage | User mid-flight requests a `migrate-data` step. | A stage line is inserted into `stages.md`; dossier records insertion; workflow continues. |
| SKIP stage | Internal-only change; user requests skipping `release-notes`. | Stage flipped `(skipped)` with rationale; workflow continues without it. |
| HITL `:checkpoint` mode | Set `hitl_mode: checkpoint` at bootstrap. | Gate after every state-mutating stage; user must approve to continue. |
| `:ad-hoc` workflow | User asks workflow-shaped question with no template. | Agent sketches a stage checklist; surfaces for user confirmation; runs it. |
| Iteration-cap finalize | Force a workflow that won't converge in 50 iterations. | Agent finalizes `:partial` at 80% with what's been done. |
| Mode select | "create a workflow template for X" vs. "run the X workflow". | Authoring mode (no dossier) vs. run mode (bootstrap). |
| Dual-read | A legacy `.edn` template; a legacy `stages.edn` dossier. | Both still load / resume for the deprecation window. |
| Hard-rule enforcement | Try `query$clone` from inside the agent. | Refusal; the curated roster excludes it. |
| Direct plan-write attempt | Try `write-file` to `.brainyard/plans/<slug>.md`. | Soft refusal via instruction; future hard enforcement via `:agent.tool-use/pre` hook. |
| Workflow + research compose | Stage uses research-agent; research-agent in turn uses explore/plan/exec/eval. | Three flat layers; workflow dossier records research dossier path; specialists' artifacts are linked from both. |
| Auto-finalize backstop | Skip the FINALIZE fence with all criteria off `(open)`. | The `:agent.ask/finalize` hook writes `verdict.md` + INDEX from the derived outcome and injects the `Saved workflow dossier:` line. |

Per-iteration mulog signals to add (mirroring `::research.*`):

- `::workflow.bootstrap` — `{:id … :template … :stage-count N :acceptance-count N}`
- `::workflow.move` — `{:iter N :move :run-stage|:eval-stage|:gate|:re-run|:insert|:skip|:synthesize|:clarify|:finalize :stage-id "<id>"}`
- `::workflow.stage-call` — `{:stage … :agent … :elapsed-ms … :outcome :satisfied|:failed|:in-progress}`
- `::workflow.acceptance-flip` — `{:criterion-id … :from … :to …}`
- `::workflow.gate` — `{:stage-id … :hitl-mode …}`
- `::workflow.terminate` — `{:status :achieved|:partial|:abandoned :iterations N :stages-run N}`

---

## 14. Migration: Retiring `pipeline` (HISTORICAL — completed)

`pipeline` was hard-removed in a single cleanup pass. The original staged migration plan (Phase 0 land → Phase 1 promote → Phase 2 soft-deprecate → Phase 3 hard-deprecate) is preserved in git history for reference but was not executed in stages — workflow-agent had landed cleanly, no in-flight callers depended on the pipeline executor, and the staged path's primary benefit (an EDN translator for existing `.brainyard/pipelines/<name>.pipeline.edn` corpora) was deferred as out of scope.

What this means for users:

- **Legacy `.pipeline.edn` files** in `.brainyard/pipelines/` lose their runtime. Port them to workflow templates by hand: rename `:steps` → `:stages`, map each step's `:agent` to `:recommended-agent`, lift workflow-level acceptance into a top-level `:acceptance` vector, and drop the file under `.brainyard/workflows/`. `workflow$load-template` will pick it up from then on.
- **TUI `/pipeline` slash commands** were removed; use `workflow-agent` directly (e.g. `bb tui ask -a workflow-agent "..."`).
- **SmartPause heuristic and pipeline state shape** were not lifted into a shared library — both designs are preserved in git history (`components/agent/src/ai/brainyard/agent/pipeline/{hitl,state}.clj` at the removal commit) and can be revived if workflow-agent's gate logic outgrows the LLM-driven CLARIFY mechanism.

If a future user surfaces a real need to run legacy `.pipeline.edn` files, the cheapest path is to write a small `pipeline-edn->workflow-edn` translator helper as a `defcommand` against the workflow-agent dossier substrate — no need to restore the executor.

---

## 15. Files Summary

| File | Role (as-built) |
|---|---|
| `components/agent/src/ai/brainyard/agent/common/workflow_agent.clj` | `instruction` (MODE SELECT + run-mode checklist authoring + authoring-mode template CRUD + FINALIZE fence), `tool-context` (functional agents + dossier substrate + VERDICT/TEMPLATE templates), `defagent workflow-agent` via `coact/run-coact-derived` with `:max-iterations 50` default; roster removes `fetch-url`, adds `runtime-commands`. |
| `components/agent/src/ai/brainyard/agent/common/workflow.clj` | The six surviving seams `workflow$id`, `workflow$resume?`, `workflow$list-templates`, `workflow$load-template`, `workflow$install-starters`, `workflow$verdict-outcome` as `defcommand`s, the markdown-checklist parser (one format for templates/dossiers/todo substrate), legacy EDN + `stages.edn` dual-read, and the `:agent.ask/finalize` auto-finalize backstop. **Retired:** `workflow$bootstrap` / `workflow$update-stage` / `workflow$update-acceptance` / `workflow$write-verdict` / `workflow$append-log` / `workflow$index-append`; `workflow$summarize-log` never shipped. |
| `components/agent/resources/workflows/*.md` | Starter templates (markdown) — only `feature-launch` and `doc-update` shipped. |
| `components/agent/test/ai/brainyard/agent/workflow_agent_test.clj` | Registration smoke, bootstrap-from-markdown-template, resume, template CRUD, index-free stage/acceptance flip, verdict guard, dual-read, INSERT/SKIP/RE-RUN, HITL, mode select. |
| `.brainyard/agents/workflow-agent/README.md` | Directory layout cheat-sheet. |
| `components/agent/src/ai/brainyard/agent/common/coact_agent.clj` | NO CHANGES — substrate, BT, sandbox, DSPy signature untouched. |

The substrate (CoAct BT, sandbox, DSPy signature) is not touched. The whole feature ships as one
agent file plus a slim read/derive/validate helpers file plus the markdown starter templates. The
retired `pipeline/` directory and `/pipeline` slash commands are gone (§14).

---

## 16. Open Questions

1. **Should the workflow agent be allowed to call itself (sub-workflows) via `call-tool "workflow-agent" {...}`?** Pipelines support `:sub-pipeline` as a stage type. A sub-call to `workflow-agent` would NOT be `query$clone` (different invocation), but it IS workflow-on-workflow recursion. The Paper-2 anti-pattern argument is weaker here than for RLM (workflows are not LLM-on-LLM), but the dossier-on-dossier complexity is real. Suggestion: forbid it in v1 (Hard Rule 1 already covers it via the no-clone-self rule, but the *call-tool* path is technically distinct). Reconsider once the single-level workflow surface is stable.

2. **Templates as skills?** A workflow template is conceptually a skill (a domain procedure). Storing them in `~/.brainyard/skills/` instead of `.brainyard/workflows/` and discovering them via `skills$find` would give us search, sharing, and `skills$install` for free. Now that templates are markdown (the same family as SKILL.md), the gap is small — a SKILL.md-style frontmatter convention over the existing acceptance/stages checklists could converge the two. Worth piloting.

3. **First-class parallel stages?** Pipelines support per-stage `:parallel?` markers; the executor can run independent stages concurrently. workflow-agent currently sequences moves one per iteration (CoAct's contract). Two paths: (a) the LLM emits a parallel `<!-- ParallelBlock -->` clojure fence with multiple `call-tool` calls per iteration — works today via CoAct; (b) extend the helpers to manage cross-stage dependency tracking automatically. Suggestion: start with (a); revisit (b) if benchmarks show meaningful latency wins.

4. **Workflow-of-workflows for portfolios?** A "ship 4 features in parallel" use case is a workflow whose stages are themselves workflow-agent calls. Equivalent to (1). Forbid in v1; design later.

5. **Cost-aware sub-LM defaults?** Many stage calls (especially research-agent and rlm-agent stages) are expensive. Workflow-agent could set `:sub-lm-config "claude-haiku-4-5-20251001"` at bootstrap to bias all `query$llm` calls cheap, while leaving stage agents' main LM alone. Worth measuring after Phase 0.

6. **Verdict.md drift vs. final answer.** Same question research-agent has. Make verdict.md the source of truth; the `:answer` markdown is *derived* from it. Unify in Phase 1.

7. **EDN translator scope.** Pipeline EDN has `:gate`, `:decision`, `:shell`, `:sub-pipeline` stage types. Workflow templates use a uniform `:agent` stage with optional `:gate-after`. Translator needs to map each. `:gate` → workflow gate stage with no agent (pure HITL pause); `:decision` → a workflow stage whose agent is `coact-agent` with a constrained instruction; `:shell` → workflow stage whose agent is `exec-agent` (or a tiny shell-runner agent we add); `:sub-pipeline` → out of scope per (1). Document the mapping precisely as part of Phase 0.
