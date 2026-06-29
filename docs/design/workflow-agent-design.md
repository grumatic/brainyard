# Workflow-Agent — Domain-Specific Multi-Agent Workflow Automation (CoAct-derived)

> **Status:** Shipped — `workflow-agent` is registered in `components/agent` (`common/workflow_agent.clj`). This document is the original design proposal (revision 1); the shipped implementation diverges in the details flagged with **As-built** notes below. See [core/agent.md](../core/agent.md) for the current roster.
> **Scope:** `components/agent/src/ai/brainyard/agent/common/workflow_agent.clj` + `workflow.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Replaces:** `pipeline` (now fully retired — the `pipeline/` directory has been removed; see §14)
> **Related reading:** `docs/CoAct.md`, `docs/research-agent-design.md`, `docs/explore-agent-design.md`, `docs/rlm-agent-design.md`
>
> **As-built (2026-06):** Recurring divergences that run through this whole doc:
> 1. **Stages are dispatched by DIRECT kebab-case agent calls, not `call-tool`.** The shipped
>    instruction/tool-context invoke `(plan-agent {…})`, `(research-agent {…})`, etc. directly in a
>    clojure fence (the defagents self-register as callable sandbox fns). Wherever this doc says
>    "via `call-tool`", read "via the direct kebab-case call". `call-tool` is still bound for
>    generic registry access, but is not the stage-dispatch path.
> 2. **`pipeline` is gone, not coexisting.** The migration in §14 completed: there is no
>    `components/agent/src/ai/brainyard/agent/pipeline/` and the `/pipeline` slash commands were removed.
> 3. **`workflow$summarize-log` was never implemented**; **`workflow$install-starters` and
>    `workflow$load-template` were** — see the corrected helper roster in §9.
> 4. **Only two starter templates ship** (`feature-launch.edn`, `doc-update.edn`), not the six listed
>    in §4.2. They live under `components/agent/resources/workflows/`.

---

## 1. Motivation

`pipeline` (`components/agent/.../pipeline/`, design in `docs/agent-tui-app/pipeline.md`) is brainyard's current macro-orchestration layer. It defines a workflow as a DAG of stages stored in `.brainyard/pipelines/<name>.pipeline.edn`, with four stage types (`:agent`, `:gate`, `:decision`, `:sub-pipeline`), five HITL modes (`:full-auto` through `:step-by-step`), and an executor that walks the DAG with topological-sort + parallelism markers + on-failure policy.

It works. It also has the same shape of problems the `research-agent` design (`docs/research-agent-design.md`) called out for `autoresearch`:

**Three structural defects:**

1. **Static DAG, dynamic reality.** A pipeline's edges (`:depends-on`) are decided at authoring time. The executor walks them deterministically: a `:decision` stage can pick which branch, but the *set* of branches is fixed; a stage cannot decide "actually, we need to re-explore before planning" or "skip the deploy stage — verify already showed the change isn't needed." The pipeline's *logic* is in the EDN file; the LLM only does work *inside* one stage at a time. Workflows that need adaptive shape (most non-trivial ones) end up with sprawling branching logic, dead branches, or wrappers that mutate the EDN at runtime.

2. **Cross-stage context starvation.** Each `:agent` stage receives `:question` (the stage `:prompt`) and a per-stage prompt-templated `:agent-context`. The *workflow purpose*, *domain constraints*, *acceptance criteria for the workflow as a whole*, and the *cumulative findings from prior stages* are not threaded in any durable way. The pipeline state map (`pipeline/state.clj`) accumulates per-stage `:result` strings, but those are stage-local; nothing carries the workflow's macro-level intent across stages. This is the same defect autoresearch had at the four-specialist scale, lifted up to the pipeline scale.

3. **HITL embedded in the DAG, not the conversation.** The five HITL modes and SmartPause are first-class features of the executor. That made sense when the pipeline was a separate state machine; in a CoAct-driven world it duplicates the agent loop's own approval surface (`request-user-action`, `user-approval-action`, `user-interrupt-action`) and can't be tuned per stage by the LLM mid-flight.

**The CoAct lesson — applied again.** `research-agent` showed that a single CoAct loop with a curated instruction and a durable dossier replaces a hard-sequenced multi-specialist BT (autoresearch's plan→todo→exec→eval). The same recipe applies one tier up: at the *workflow* scale, where each "stage" is itself a multi-specialist call (often a `research-agent` invocation, sometimes an explore / plan / exec call directly).

`workflow-agent` is the CoAct equivalent of `pipeline`. It owns one durable artifact (the workflow dossier), reaches stages via `call-tool` to whichever functional agent fits each stage, and lets the LLM decide stage order, retries, and termination. The `:agent`/`:gate`/`:decision`/`:sub-pipeline` ontology becomes implicit moves in a state machine the LLM picks from per iteration.

**Thesis.** Add a CoAct-derived `workflow-agent` that:

1. **Owns one durable artifact** — a workflow dossier under `.brainyard/agents/workflow-agent/<workflow-id>/` capturing *purpose*, *domain constraints*, *workflow-level acceptance criteria*, *stage roster + status*, *cumulative findings*, and *handoff state* across iterations and across stages.
2. **Treats workflow templates as data, not logic** — domain-specific templates (`.brainyard/workflows/<domain>.edn`) declare the *expected shape* (typical stage sequence, recommended agents, default acceptance criteria) but do NOT bind the LLM to them. The instruction tells the agent: *templates are starting points, not contracts.*
3. **Uses CoAct's loop** — no new BT, no executor, no DAG walker. The LLM picks the next move per iteration: load template, draft workflow plan, run a stage, evaluate, gate (ask user), branch, re-run a stage, finalize.
4. **Reaches functional agents via `call-tool`** — `(call-tool "research-agent" {...})`, `(call-tool "explore-agent" {...})`, `(call-tool "exec-agent" {...})`, plus any other registered defagent. Cross-agent dispatch is flat call-tool, not pipeline executor wiring.
5. **HITL collapses into the conversation.** Approval gates become `answer`-channel pauses (the user replies, the workflow resumes). The five HITL modes become one runtime config knob (`:workflow-hitl :auto|:gates|:checkpoint|:co-pilot|:step`) the LLM consults — no executor branch.
6. **Inherits CoAct's full BT, sandbox, router, accumulator** — no substrate changes. Whole feature is one new agent file plus an optional helpers namespace, mirroring `research-agent`.
7. **Coexists with pipeline** during a staged migration (§14), then retires it.

---

## 2. Design Principles

1. **Workflow shape is a recommendation, not a contract.** Domain templates declare what *typically* happens. The LLM adapts shape to the actual question.
2. **Durable workflow dossier.** Cross-stage state lives in markdown + EDN files, not in `pipeline/state.clj` atoms or per-stage `:result` strings. Every stage call writes; every subsequent stage call reads.
3. **The LLM owns sequencing.** Re-run a stage? Skip ahead? Branch? Pause for user input? These are reasoning calls. The instruction names the moves; the LLM picks one per iteration.
4. **Stages are agent invocations.** Each stage is a `call-tool` to a functional agent (research / explore / plan / todo / exec / eval / mcp / skill / rlm / coact). The workflow-agent does not implement domain logic — it composes agents.
5. **Domain knowledge lives in templates.** A `.edn` for `feature-launch` knows the typical stage sequence (research → plan → implement → test → release-notes → announce) and recommends agents per stage. A `.edn` for `incident-response` knows a different sequence. The agent reads the template; the user picks which template to instantiate.
6. **HITL is a single conversational surface.** Approval prompts are `answer`-channel pauses with a stable prefix the dispatcher can identify. No second approval system.
7. **Acceptance criteria are workflow-scoped.** The dossier carries *workflow-level* acceptance ("the feature is launched: PR merged, docs published, announcement sent") distinct from each stage's per-call acceptance. Workflow termination requires workflow-level acceptance, not stage-level.
8. **No clone-self recursion.** `query$clone` is excluded — the workflow agent calling itself is the depth-2 anti-pattern. Cross-agent `call-tool` to other defagents is flat dispatch and IS the design.
9. **Resumable.** The dossier is the only state of record. A workflow run interrupted mid-flight (TUI exit, gate timeout, max iterations) can be resumed by passing the workflow-id; the agent reads the dossier and continues.
10. **Generous iteration cap.** Default 50 iterations. A workflow with 6–8 stages, each occasionally needing retry/branch, easily hits 30–40 actual moves. Override via `agent-runtime$config :key "max-iterations" :value "N"`.

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
  ├─ pipeline          (DEPRECATED — see §14)
  └─ workflow-agent    (NEW — domain-specific multi-agent workflow automation)
```

| Question / task shape | Use | Why |
|---|---|---|
| "Find me where X lives" | explore-agent | Single-surface discovery. |
| "Drive todo Z to completion" | exec-agent | Already has plan + todo. |
| "Investigate Y end-to-end and produce a recommendation" | research-agent | Multi-specialist research thread. |
| "Run the feature-launch workflow for feature F: research, plan, implement, test, release-notes, announce." | **workflow-agent** | Multi-stage domain workflow. |
| "Run the incident-response runbook for outage O: detect, mitigate, root-cause, postmortem." | **workflow-agent** | Multi-stage domain workflow. |
| "Walk me through this static `.pipeline.edn` DAG with HITL gates" | pipeline (during migration) → workflow-agent | Migration target. |

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

## 4. Workflow Templates — `.brainyard/workflows/<domain>.edn`

Workflow templates are the data side of the design. They are read by the LLM and treated as *recommendations*. They are NOT executed by a DAG walker.

### 4.1 Template Shape

```edn
{:workflow/id   :feature-launch
 :workflow/name "Feature Launch Workflow"
 :workflow/description
 "End-to-end feature delivery: research → plan → implement → test → release."

 :acceptance
 [{:id :a1 :text "Feature meets the user-stated success criteria"}
  {:id :a2 :text "Implementation merged to main"}
  {:id :a3 :text "Tests added and passing"}
  {:id :a4 :text "Release notes published"}
  {:id :a5 :text "Stakeholders notified"}]

 :stages
 [{:id :research-feasibility
   :purpose "Validate the feature is worth doing and surface unknowns"
   :recommended-agent :research-agent
   :gate-after :user                     ; default :auto | :user | :smart
   :acceptance-focus [:a1]}

  {:id :plan-design
   :purpose "Author a concrete implementation plan"
   :recommended-agent :plan-agent
   :acceptance-focus [:a1 :a2]}

  {:id :implement
   :purpose "Execute the plan's todo list"
   :recommended-agent :exec-agent
   :acceptance-focus [:a2 :a3]}

  {:id :test
   :purpose "Run/extend tests; capture failures"
   :recommended-agent :exec-agent
   :acceptance-focus [:a3]}

  {:id :release-notes
   :purpose "Draft user-facing release notes"
   :recommended-agent :coact-agent
   :acceptance-focus [:a4]}

  {:id :announce
   :purpose "Notify stakeholders via configured channels"
   :recommended-agent :mcp-agent
   :gate-after :user
   :acceptance-focus [:a5]}]

 :defaults
 {:hitl :gates                           ; :auto | :gates | :checkpoint | :co-pilot | :step
  :max-stage-attempts 3
  :sub-lm "claude-haiku-4-5-20251001"}}
```

**Stage fields are advisory.** The LLM is told (via instruction) that templates declare *typical shape* but it can:

- Skip a stage (`:test` is unnecessary if the implementation is doc-only).
- Insert a stage (an unforeseen `:migrate-data` step before `:implement`).
- Re-run a stage (post-`:test`, return to `:implement`).
- Reorder (`:release-notes` before `:test` if the feature is feature-flag gated).
- Substitute the agent (use `coact-agent` for `:research-feasibility` if the question is trivial).

### 4.2 Where Templates Live

```
.brainyard/
└── workflows/
    ├── feature-launch.edn
    ├── incident-response.edn
    ├── doc-update.edn
    ├── refactor-and-verify.edn
    ├── data-migration.edn
    └── library-upgrade.edn
```

Templates are versioned in the project repo (or in `~/.brainyard/workflows/` for personal templates). They are how *domain knowledge* flows from senior engineers into agent runs without anyone having to write executor logic.

> **As-built:** Of the six templates sketched above, only **two ship** today — `feature-launch.edn`
> and `doc-update.edn`, under `components/agent/resources/workflows/` (the classpath `workflows/`
> resource dir). The other four (`incident-response`, `refactor-and-verify`, `data-migration`,
> `library-upgrade`) were never authored. Users can still author their own under
> `.brainyard/workflows/` or `~/.brainyard/workflows/`.

### 4.3 Template Bootstrapping

A small set of starter templates ships with brainyard on the classpath under `resources/workflows/`. On first invocation that doesn't find any project-local templates, `workflow$install-starters` copies the starters into `.brainyard/workflows/` for the user to edit. This mirrors how `.brainyard/skills/` is bootstrapped. **As-built:** the shipped helper is `workflow$install-starters` (idempotent; `:overwrite?` opt-in), and only the two starters above are copied.

---

## 5. The Workflow Dossier — `.brainyard/agents/workflow-agent/<workflow-id>/`

### 5.1 Directory Layout

```
.brainyard/
└── workflow-agent/
    ├── INDEX.md                            ; one line per workflow run, newest first
    └── <workflow-id>/                       ; one directory per workflow thread
        ├── dossier.md                       ; durable workflow context (see §5.2)
        ├── purpose.md                       ; immutable after iteration 1
        ├── acceptance.md                    ; workflow-level criteria
        ├── stages.edn                       ; current stage roster + status
        ├── findings.log                     ; append-only NDJSON of stage calls
        ├── handoff.md                       ; what to pass to the next stage
        ├── verdict.md                       ; written at termination
        ├── template.edn                     ; copy of the source template (for diff/audit)
        └── artifacts/                       ; pointers into other agents' outputs
            ├── research/                    ; symlinks to .brainyard/agents/research-agent/<id>/
            ├── plans/                       ; symlinks to .brainyard/plans/<slug>.md
            ├── todos/                       ; symlinks
            ├── evals/                       ; symlinks
            ├── exploration/                 ; symlinks to explore-agent results
            └── stage-outputs/<stage-id>.md  ; freeform per-stage notes the workflow agent owns
```

The `<workflow-id>` is a kebab-case slug (workflow-template name + a deterministic suffix from the user's question). Re-runs of the same workflow on the same target produce the same id; the dossier accumulates.

### 5.2 `dossier.md` — Cross-Stage Context Carrier

```markdown
---
workflow_id: feature-launch--mcp-server-health-check
workflow_template: feature-launch
created: 2026-05-09T18:12:44Z
last_iteration: 14
status: in_progress              # in_progress | achieved | partial | abandoned
purpose: >
  Add a health-check command to the MCP server that surfaces per-server
  status in the TUI's status bar. Should ship as a 0.1% feature flag
  rollout next Tuesday.
acceptance:
  - id: a1
    text: "Health-check command implemented; reachable via mcp$server :op :health-check"
    status: satisfied
  - id: a2
    text: "PR merged to main"
    status: open
  - id: a3
    text: "Unit tests added with >= 80% line coverage on new code"
    status: satisfied
  - id: a4
    text: "Release notes published"
    status: open
  - id: a5
    text: "Slack #releases announcement sent"
    status: open
stages:
  - id: research-feasibility   { agent: research-agent, status: satisfied,
                                 artifact: artifacts/research/feature-launch--mcp-server-health-check/ }
  - id: plan-design             { agent: plan-agent, status: satisfied,
                                  plan_slug: mcp-server-health-check }
  - id: implement               { agent: exec-agent, status: in_progress,
                                  todo_slug: mcp-server-health-check }
  - id: test                    { agent: exec-agent, status: pending }
  - id: release-notes           { agent: coact-agent, status: pending }
  - id: announce                { agent: mcp-agent, status: pending }
hitl_mode: gates                 # auto | gates | checkpoint | co-pilot | step
artifacts:
  research_dossier: .brainyard/agents/research-agent/feature-launch--mcp-server-health-check/
  plan_slug: mcp-server-health-check
  todo_slug: mcp-server-health-check
  evals: []
calls_log: findings.log
template: template.edn
---

## Purpose
[verbatim from purpose.md, immutable]

## Acceptance criteria (workflow-level)
[mirror of frontmatter for readability]

## Stage progress
[narrative log of completed/active stages with brief findings]

## Pending decisions / branches
- Should we batch the announcement with next week's release? (open question)

## Open risks
- Tests caught a flaky case in CI — needs investigation in :test stage.
```

**Stable contract:**

| Frontmatter key | Type | Purpose |
|---|---|---|
| `workflow_id` | string | Stable identifier. |
| `workflow_template` | keyword string | Source template id. |
| `purpose` | string | Verbatim user request. |
| `acceptance` | vector of `{id, text, status}` | Workflow-level criteria. |
| `stages` | vector of `{id, agent, status, ...}` | Current roster + per-stage state. |
| `hitl_mode` | enum | `auto | gates | checkpoint | co-pilot | step` |
| `artifacts` | map | Pointers to specialists' outputs (and to a research-agent dossier when one exists). |
| `template` | path | Copy of the source template at workflow-init time. |

### 5.3 `findings.log` — Append-Only Stage Call Log (NDJSON)

```ndjson
{"iter":2,"stage":"research-feasibility","agent":"research-agent","summary":"Health check is feasible; identified existing mcp$server :op :health as the place to extend.","artifact":".brainyard/agents/research-agent/feature-launch--mcp-server-health-check/"}
{"iter":3,"stage":"plan-design","agent":"plan-agent","plan_slug":"mcp-server-health-check","plan_path":".brainyard/plans/mcp-server-health-check.md","summary":"Plan with 5 items; AOT-friendly approach"}
{"iter":4,"stage":"plan-design","action":"gate","outcome":"approved","note":"User confirmed the plan"}
{"iter":5,"stage":"implement","agent":"exec-agent","items_done":[0,1,2],"summary":"3 of 5 items done; client/server protocol wiring complete"}
…
```

The same NDJSON shape as research-agent's `findings.log` but with an extra `:stage` field. The workflow agent appends after every stage call; the dossier body's "## Stage progress" is regenerated periodically from the log.

### 5.4 `stages.edn` — Live Stage Roster

```edn
{:workflow/id :feature-launch--mcp-server-health-check
 :template-id :feature-launch
 :stages
 [{:id :research-feasibility
   :status :satisfied
   :agent :research-agent
   :acceptance-focus [:a1]
   :attempts 1
   :artifact ".brainyard/agents/research-agent/feature-launch--mcp-server-health-check/"
   :completed-at "2026-05-09T18:31:12Z"}

  {:id :plan-design
   :status :satisfied
   :agent :plan-agent
   :acceptance-focus [:a1 :a2]
   :attempts 1
   :plan-slug :mcp-server-health-check
   :gate {:status :approved :at "2026-05-09T18:42:00Z"}
   :completed-at "2026-05-09T18:42:31Z"}

  {:id :implement
   :status :in-progress
   :agent :exec-agent
   :acceptance-focus [:a2 :a3]
   :attempts 1
   :todo-slug :mcp-server-health-check
   :item-progress "3/5"}

  ;; ...remaining stages...
  ]}
```

**Stage status values:** `:pending → :in-progress → :satisfied | :failed | :skipped | :abandoned`. The workflow agent updates this file after each stage call. Read once per iteration to decide the next move.

### 5.5 `handoff.md`, `verdict.md`, `INDEX.md`

Same shape as research-agent's: a `handoff.md` pre-call (what the next stage receives), a `verdict.md` post-termination (one-shot), and an append-only `INDEX.md` at `.brainyard/agents/workflow-agent/INDEX.md`.

---

## 6. Tool Roster

```clojure
(:require
  [ai.brainyard.agent.common.tools     :as common-tools]
  [ai.brainyard.agent.common.commands  :as common-cmds]
  [ai.brainyard.agent.task.commands    :as task-cmds])

(def workflow-tools
  (vec (distinct
         (concat
           ;; Filesystem — for dossier / template maintenance
           common-tools/file-tools          ; read-file, write-file, grep, fetch-url, update-file
           common-tools/shell-tools         ; bash (mkdir, ls, ln -s, cp)

           ;; Bookkeeping
           common-tools/bootstrap-tools     ; list-tools, get-tool-info, search
           common-tools/invocation-tools    ; call-tool

           ;; Sub-LLM synthesis (flat only)
           [#'common-cmds/query$llm]        ; intentionally excludes #'query$clone

           ;; Background jobs (long-running stages can be wrapped)
           task-cmds/task-commands))))
```

The functional agents (`research-agent`, `explore-agent`, `plan-agent`, `todo-agent`, `exec-agent`, `eval-agent`, `mcp-agent`, `skill-agent`, `edit-agent`, `rlm-agent`, `coact-agent`) are reached via direct kebab-case dispatch — `(plan-agent {...})`, `(research-agent {...})`, etc. They are not bound as named tools in the roster; they live in the registry as auto-bound sandbox fns, and the agent calls them as needed. **As-built:** the proposal routed these through `(call-tool "<agent-name>" {...})`; the shipped instruction calls the defagent fns directly. The roster also binds the `workflow$*` helpers, `runtime-commands`, and (per the shipped code) explicitly *removes* `fetch-url` even though `file-tools` carries it.

What is *deliberately omitted*:

| Excluded | Reason |
|---|---|
| `query$clone` | Clones workflow-agent itself = clone-self recursion. Forbidden. |
| Direct `plan$*` / `todo$*` / `mcp$*` / `skills$*` | Routed through specialists. Bypassing them breaks per-domain safety contracts. |
| Web tools | Routed through explore-agent / research-agent. Keeps web access auditable in the explore corpus. |
| pipeline executor primitives | The whole point is to NOT use the executor. |

---

## 7. Instruction (System Prompt Body)

Layered on top of `coact-agent`'s instruction by `run-coact-derived`.

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

---

## 8. Tool-Context (How to Use the Bound Tools)

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

---

## 9. Optional `(workflow$*)` Sandbox Helpers

Mirrors the helpers introduced for `rlm-agent`, `explore-agent`, and `research-agent`. They live in `ai.brainyard.agent.common.workflow`, register as `defcommand`s, and surface in the sandbox via auto-binding.

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

**As-built:** The shipped `workflow-helpers` roster binds exactly **eleven** helpers — `workflow$id`, `workflow$resume?`, `workflow$list-templates`, `workflow$load-template`, `workflow$install-starters`, `workflow$bootstrap`, `workflow$append-log`, `workflow$update-stage`, `workflow$update-acceptance`, `workflow$write-verdict`, `workflow$index-append`. `workflow$summarize-log` was **never implemented** (the helper layer is kept mechanical/side-effect-only; the agent calls `query$llm` directly with findings.log content as `:sub-context` — same decision as research-agent). `workflow.clj` also ships an `:agent.ask/post` **auto-finalize hook** that writes `verdict.md` + appends `INDEX.md` when the LLM emits a non-blank answer but skipped the finalize fence, *provided* the dossier exists and all acceptance criteria have moved off `:open`.

The agent works without these — the prompt becomes 30–40% shorter when they are bound.

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

````markdown
```clojure
(def template (workflow$load-template :id :feature-launch))

(def wid (workflow$id :template :feature-launch
                      :question "Ship the MCP server health-check command, target this Tuesday"))
;; => "feature-launch--mcp-server-health-check"

(def init
  (workflow$bootstrap
    :id wid
    :template-path ".brainyard/workflows/feature-launch.edn"
    :purpose "Add a health-check command to the MCP server, surfaced in the TUI status bar. Target rollout: Tuesday."
    :acceptance [{:id "a1" :text "Health-check command implemented; reachable via mcp$server :op :health-check" :status :open}
                 {:id "a2" :text "PR merged to main" :status :open}
                 {:id "a3" :text "Unit tests added with >= 80% line coverage on new code" :status :open}
                 {:id "a4" :text "Release notes published" :status :open}
                 {:id "a5" :text "Slack #releases announcement sent" :status :open}]))
;; => {:dir ".brainyard/agents/workflow-agent/feature-launch--mcp-server-health-check/" ...}
```
````

Then `:answer` surfaces the acceptance criteria for the user to confirm, and the loop pauses. User replies "looks good, run it."

### Iteration 2 — RUN-STAGE :research-feasibility (tool channel)

````markdown
```json
[{"tool-name": "research-agent",
  "tool-args": [{"name": "question",
                 "value": "Investigate the feasibility of adding a health-check command to the MCP server, surfacing per-server status in the TUI status bar. Identify code touch points and unknowns."},
                {"name": "agent-context",
                 "value": "Workflow dossier: .brainyard/agents/workflow-agent/feature-launch--mcp-server-health-check/dossier.md\nStage: research-feasibility\nAcceptance focus: a1\nHint: focus on existing mcp$server :op :health and TUI status-bar wiring."}]}]
```
````

research-agent runs its own loop (exploring, planning, evaluating) and returns an answer with `Saved research dossier: .brainyard/agents/research-agent/feature-launch--mcp-server-health-check/`.

### Iteration 3 — Log + decide (code channel)

````markdown
```clojure
(workflow$append-log
  :id wid :iter 2 :stage "research-feasibility" :agent "research-agent"
  :summary "Feasible. Existing mcp$server :op :health already pings; need to add :op :health-check that returns structured per-server status. TUI status bar has a slot pattern for it."
  :pointers {:research_dossier ".brainyard/agents/research-agent/feature-launch--mcp-server-health-check/"})

(workflow$update-stage :id wid :stage-id "research-feasibility"
                        :status :satisfied
                        :artifact ".brainyard/agents/research-agent/feature-launch--mcp-server-health-check/")

(workflow$update-acceptance :id wid :criterion-id "a1" :status :pending)  ; not yet satisfied; criterion is implementation
```
````

Template's `:research-feasibility` had `:gate-after :user` — workflow-agent enters GATE.

### Iteration 4 — GATE (answer channel; loop pauses)

```markdown
**Stage gate — research-feasibility complete.**

Findings: feasible. Two touch-points:
- `components/agent/.../mcp/integration.clj` — add `:op :health-check` to the existing mcp$server polymorphic command.
- `bases/agent-tui/.../status_bar.clj` — slot for per-server health indicator.

Risks: none surfaced.

Next stage: `:plan-design` via plan-agent.

Awaiting workflow gate: research-feasibility
```

User replies "go ahead." Loop resumes on the next turn.

### Iterations 5–13 — RUN-STAGE through implementation, test, release notes

Plan-agent drafts `mcp-server-health-check` plan; gate; exec-agent runs the todo (5 items); query$llm synthesizes log; eval-agent verifies a1 + a3; coact-agent drafts release notes for a4.

### Iteration 14 — Last stage with :gate-after :user (announce)

Workflow-agent presents the announcement draft, asks for approval, then on the next turn calls mcp-agent to post.

### Iteration 15 — FINALIZE (code channel + answer channel)

````markdown
```clojure
(workflow$write-verdict
  :id wid
  :status :achieved
  :narrative "All 5 acceptance criteria satisfied. Feature shipped Tuesday as planned.")

(workflow$index-append
  :id wid
  :status :achieved
  :one-line "Shipped MCP health-check command; PR #2347 merged Tue.")
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
1. `:research-feasibility` (research-agent) — feasibility confirmed, two touch-points identified.
2. `:plan-design` (plan-agent) — `mcp-server-health-check` plan; 5 items.
3. `:implement` (exec-agent) — items 0–4 done; PR #2347 opened.
4. `:test` (exec-agent) — coverage 89% on new code; one flaky test fixed.
5. `:release-notes` (coact-agent) — published to docs/releases/.
6. `:announce` (mcp-agent) — Slack #releases at 14:32 Tue.

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
| 4 | Use `query$clone` to "delegate" the workflow | Clones workflow-agent itself = clone-self recursion | Stay flat; reach the functional agents via call-tool. |
| 5 | Inline the full dossier body in every `:agent-context` | Bloats stage context; specialists read-file what they need | Pass path + 4-line distillation. |
| 6 | Ignore HITL mode | Surprises the user with state-mutating stages | Read `hitl_mode` from dossier; GATE per the rules in §7. |
| 7 | Re-run a `:satisfied` stage on resume | Wastes work and may un-do prior state | Resume reads stages.edn; only :pending / :failed / :in-progress are eligible. |
| 8 | Write `.brainyard/plans/<slug>.md` directly | Bypasses plan-agent's safety + slug-collision checks | Always go through plan-agent. |
| 9 | Treat workflow as one big research turn | research-agent already exists for that | If the question is single-thread research → research-agent. workflow-agent is for multi-stage domain workflows where most stages are themselves multi-specialist. |
| 10 | Modify `.brainyard/workflows/<template>.edn` mid-run | Templates are domain knowledge under version control | Improve templates between runs (manually, or via skill-agent). Mid-run deviations live in the dossier, not the template. |
| 11 | FINALIZE early because some stages are :satisfied | :achieved requires ALL workflow acceptance, not all stages | Workflow-level acceptance is the gate. Stages are the means. |
| 12 | Push past iteration cap silently | User gets opaque timeout | At 80%, prepare FINALIZE :partial. Honest reporting > silent timeout. |

---

## 13. Verification

| Benchmark / smoke test | Shape | What it verifies |
|---|---|---|
| Single-template happy path | Run `feature-launch` end-to-end on a small change. | Bootstrap → 6 stages → FINALIZE :achieved; dossier complete; INDEX.md appended. |
| Resume mid-workflow | Kill TUI between stages, resume with `@<id>`. | Dossier reload; only :pending stages run; no duplicate work. |
| Template mismatch (RE-RUN) | Force exec-agent to surface a bug in test stage. | Workflow re-runs :implement; second pass succeeds; verdict :achieved. |
| INSERT new stage | User mid-flight requests a `:migrate-data` step. | Stage inserted into stages.edn; dossier records insertion; workflow continues. |
| SKIP stage | Internal-only change; user requests skipping :release-notes. | Stage marked :skipped with rationale; workflow continues without it. |
| HITL :checkpoint mode | Set `:hitl-mode :checkpoint` at bootstrap. | Gate after every state-mutating stage; user must approve to continue. |
| :ad-hoc workflow | User asks workflow-shaped question with no template. | Agent sketches stage list; surfaces for user confirmation; runs it. |
| Iteration-cap finalize | Force a workflow that won't converge in 50 iterations. | Agent finalizes :partial at 80% with what's been done. |
| Cross-agent dossier propagation | Inspect `:agent-context` strings to each stage call. | All include workflow dossier path + acceptance focus + relevant prior artifacts. |
| Hard-rule enforcement | Try `query$clone` from inside the agent. | Refusal; the curated roster excludes it. |
| Direct plan-write attempt | Try `write-file` to `.brainyard/plans/<slug>.md`. | Soft refusal via instruction; future hard enforcement via `:agent.tool-use/pre` hook. |
| Workflow + research compose | Stage uses research-agent; research-agent in turn uses explore/plan/exec/eval. | Three flat layers; workflow dossier records research dossier path; research dossier records its own findings; specialists' artifacts are linked from both. |
| ~~Pipeline-EDN compatibility (read-only)~~ | ~~Load a `.pipeline.edn` file via `workflow$load-template`.~~ | **As-built: not implemented.** The pipeline-EDN → workflow-EDN translator was never written; `workflow$load-template` only reads workflow templates. Port legacy `.pipeline.edn` by hand (see §14). |

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

| File | What changes |
|---|---|
| `components/agent/src/ai/brainyard/agent/common/workflow_agent.clj` | NEW — `instruction`, `tool-context`, `defagent workflow-agent` mirroring `research-agent` shape; uses `coact/run-coact-derived` with `:max-iterations 50` default. |
| `components/agent/src/ai/brainyard/agent/common/workflow.clj` | NEW (shipped) — `workflow$id`, `workflow$resume?`, `workflow$list-templates`, `workflow$load-template`, `workflow$install-starters`, `workflow$bootstrap`, `workflow$append-log`, `workflow$update-stage`, `workflow$update-acceptance`, `workflow$write-verdict`, `workflow$index-append` as `defcommand`s, plus an `:agent.ask/post` auto-finalize hook. **As-built:** `workflow$summarize-log` was dropped (agent uses `query$llm`); the pipeline-EDN ↔ workflow-EDN translator was never written (deferred — see §14). |
| `components/agent/resources/workflows/*.edn` | NEW (starter templates) — **As-built:** only `feature-launch` and `doc-update` shipped; `incident-response`, `refactor-and-verify`, `data-migration`, `library-upgrade` were never authored. |
| `components/agent/test/ai/brainyard/agent/workflow_agent_test.clj` | NEW — registration smoke, bootstrap, resume, INSERT/SKIP/RE-RUN, HITL, pipeline-EDN compat. |
| `.brainyard/agents/workflow-agent/README.md` | NEW (templated by helpers on first write) — directory layout cheat-sheet. |
| `bases/agent-tui/.../pipeline_commands.clj` | TOUCHED at Phase 1 — alias `/pipeline` → workflow-agent; add `/workflow` slash commands. |
| `bases/agent-tui` / `bases/agent-web` | NO CHANGES at Phase 0/1; remove pipeline references at Phase 3. |
| `bb.edn` | OPTIONAL — `repl:workflow` task; `workflow:migrate-edn` task at Phase 2. |
| `docs/workflow-agent-design.md` | THIS FILE. |
| `docs/agent-tui-app/pipeline.md` | TOUCHED at Phase 2 — point at workflow-agent. |
| `components/agent/src/ai/brainyard/agent/pipeline/` | TOUCHED only at Phases 2 and 3 (deprecation log; namespace extraction; deletion of executor/BT-factory while keeping SmartPause + state-shape library). |
| `components/agent/src/ai/brainyard/agent/common/coact_agent.clj` | NO CHANGES. |

The substrate (CoAct BT, sandbox, DSPy signature) is not touched. The whole feature ships as one new agent file plus an optional helpers file plus starter templates.

---

## 16. Open Questions

1. **Should the workflow agent be allowed to call itself (sub-workflows) via `call-tool "workflow-agent" {...}`?** Pipelines support `:sub-pipeline` as a stage type. A sub-call to `workflow-agent` would NOT be `query$clone` (different invocation), but it IS workflow-on-workflow recursion. The Paper-2 anti-pattern argument is weaker here than for RLM (workflows are not LLM-on-LLM), but the dossier-on-dossier complexity is real. Suggestion: forbid it in v1 (Hard Rule 1 already covers it via the no-clone-self rule, but the *call-tool* path is technically distinct). Reconsider once the single-level workflow surface is stable.

2. **Templates as skills?** A workflow template is conceptually a skill (a domain procedure). Storing them in `~/.brainyard/skills/` instead of `.brainyard/workflows/` and discovering them via `skills$find` would give us search, sharing, and `skills$install` for free. Trade-off: skills are SKILL.md markdown; workflow templates are EDN. Could compromise via SKILL.md frontmatter that points at a sibling `.edn`. Worth piloting in Phase 2.

3. **First-class parallel stages?** Pipelines support per-stage `:parallel?` markers; the executor can run independent stages concurrently. workflow-agent currently sequences moves one per iteration (CoAct's contract). Two paths: (a) the LLM emits a parallel `<!-- ParallelBlock -->` clojure fence with multiple `call-tool` calls per iteration — works today via CoAct; (b) extend the helpers to manage cross-stage dependency tracking automatically. Suggestion: start with (a); revisit (b) if benchmarks show meaningful latency wins.

4. **Workflow-of-workflows for portfolios?** A "ship 4 features in parallel" use case is a workflow whose stages are themselves workflow-agent calls. Equivalent to (1). Forbid in v1; design later.

5. **Cost-aware sub-LM defaults?** Many stage calls (especially research-agent and rlm-agent stages) are expensive. Workflow-agent could set `:sub-lm-config "claude-haiku-4-5-20251001"` at bootstrap to bias all `query$llm` calls cheap, while leaving stage agents' main LM alone. Worth measuring after Phase 0.

6. **Verdict.md drift vs. final answer.** Same question research-agent has. Make verdict.md the source of truth; the `:answer` markdown is *derived* from it. Unify in Phase 1.

7. **EDN translator scope.** Pipeline EDN has `:gate`, `:decision`, `:shell`, `:sub-pipeline` stage types. Workflow templates use a uniform `:agent` stage with optional `:gate-after`. Translator needs to map each. `:gate` → workflow gate stage with no agent (pure HITL pause); `:decision` → a workflow stage whose agent is `coact-agent` with a constrained instruction; `:shell` → workflow stage whose agent is `exec-agent` (or a tiny shell-runner agent we add); `:sub-pipeline` → out of scope per (1). Document the mapping precisely as part of Phase 0.
