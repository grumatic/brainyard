# Research-Agent — LLM-Driven Multi-Specialist Research Loop (CoAct-derived)

> **Status:** Shipped + revised (revision 2 — refreshed for the four-agent pipeline redesign)
> **Scope:** `components/agent/src/ai/brainyard/agent/common/research_agent.clj` + `research.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Replaces (eventually):** `autoresearch` (`components/agent/src/ai/brainyard/agent/autoresearch/`)
> **Related reading:** `docs/CoAct.md`, `docs/AUTORESEARCH.md`, `docs/explore-agent-design.md`, `docs/rlm-agent-design.md`, `docs/plan-agent-design.md`, `docs/todo-agent-design.md`, `docs/exec-agent-design.md`, `docs/eval-agent-design.md`, `docs/update-agent-design.md`

## Revision history

- **rev 1 (2026-04)** — initial design proposal; five specialists (explore / plan / todo / exec / eval); plan + todo bodies at `.brainyard/plans/<slug>.md` / `.brainyard/todos/<slug>.md`; evidence threaded via `:answer` strings and slug-shaped `:agent-context`.
- **rev 2 (2026-05)** — refreshed after the four-agent pipeline (plan/todo/exec/eval) was redesigned with pre/post-flight gating + dossier handoff (see §10), and after update-agent was added as a sixth reachable specialist for safe one-off edits. Headline changes:
  - **Six specialists**, not five. update-agent joins the roster; reach it via move I (UPDATE) for single-edit work that doesn't warrant a plan/todo arc.
  - **Sibling-agent storage migrated** to per-agent directories: `.brainyard/agents/plan-agent/plans/`, `.brainyard/agents/todo-agent/todos/`, plus `dossiers/` and `verdicts/` siblings. Legacy paths are dual-read for one release.
  - **Dossier handoff is the cross-agent contract.** Each specialist emits `Saved dossier: <path>` (plus the body file's `Saved plan:` / `Saved todo:` / `Saved verdict:` / `Saved edit:`). Pre-flight gates on each downstream specialist consume the upstream dossier — research-agent threads paths between them instead of stringy slugs.
  - **Read-only sibling dossier helpers** (`plan$read-dossier`, `todo$read-dossier`, `exec$read-dossier`, `eval$read-dossier`, `update$read-record`) are cherry-picked into research-agent's roster so it can parse upstream frontmatter cheaply for data-driven move decisions. Write-side dossier helpers are NOT bound — sibling writes go through `call-tool`.
  - **Decision heuristics are now data-driven.** When eval-agent returns NOT_ACHIEVED, its `score.recommendations` field names the next agent per criterion. research-agent reads that instead of inferring from the answer text.

---

## 1. Motivation

`autoresearch` (`docs/AUTORESEARCH.md`) wires `plan-agent → todo-agent → exec-agent → eval-agent` into a fixed orchestration loop in a behavior tree (`autoresearch/core.clj`, `autoresearch/orchestrator.clj`). The four sub-agents are good at their jobs; the orchestration layer is the problem.

**Two structural defects:**

1. **Cross-agent context starvation.** Each sub-agent receives only `:question` + `:agent-context` (a slug or short string). The *research purpose*, *direction*, and *acceptance criteria* — the things that make a turn coherent — exist nowhere durable. Plans sometimes capture them in `## Acceptance`, but the moment exec-agent runs against the todo, the *why* of the work is several handoffs away. Eval-agent reconstructs criteria from the plan body, but that reconstruction is at best a copy of what the plan happened to write down; if the user's intent was richer than the plan body, eval-agent never sees it. There is no durable "research dossier" that all four specialists read from and write into.

2. **The BT enforces a hard sequential loop.** The autoresearch BT is `[:sequence load-program → [:repeat questions [:sequence snapshot → [:repeat attempts [:sequence prepare-attempt → select-strategy → run-inner-agent → score → keep-or-discard]] → store-best]] → report]`. The *order* of plan → todo → exec → eval is baked into the tree; the LLM has no input on whether a re-plan is needed mid-flight, or whether to skip ahead to exec on a small follow-up, or to re-explore before evaluating, or to *stop* because the last eval already answered the user's real question. The LLM gets to write code and pick tools *inside* an attempt, but the attempt's *shape* is fixed.

**The CoAct lesson.** Every other agent in the brainyard stack — `coact-agent`, `explore-agent`, `skill-agent`, `mcp-agent`, the recently-designed `rlm-agent` — has shown that **a single CoAct loop with a curated instruction + tool roster outperforms a bespoke BT for orchestration tasks**. The LLM, given the right tools and the right rules, picks the right next step. The BT shrinks to "preflight → init → ThinkActCode loop → finalize → store" (which CoAct provides) and the agent's identity is just *what it knows about its domain* (instruction) + *what it can reach for* (tools).

`research-agent` applies that same pattern to the multi-specialist research workflow (now six reachable specialists: explore / plan / todo / exec / eval / update).

**Thesis.** Add a CoAct-derived `research-agent` that:

1. **Owns one durable artifact** — a research dossier under `.brainyard/agents/research-agent/<research-id>/` capturing *purpose*, *direction*, *acceptance criteria*, *findings log*, and *handoff state* across turns and across calls to specialist agents. This is the cross-agent context carrier.
2. **Uses CoAct's loop** — no new BT. The LLM picks the next move per iteration: explore, plan, decompose, execute, evaluate, refine, or finish.
3. **Reaches the six specialists via `call-tool`** — `(call-tool "explore-agent" {...})`, `(call-tool "plan-agent" {...})`, `(call-tool "todo-agent" {...})`, `(call-tool "exec-agent" {...})`, `(call-tool "eval-agent" {...})`, `(call-tool "update-agent" {...})`. Every defagent registers in the same tool registry, so dispatch is flat.
4. **Threads the dossier path through `:agent-context`** so each specialist receives the same purpose/direction/acceptance — solving defect 1 without changing the specialists' contracts.
5. **Stops when the LLM judges the goal achieved or definitively unreachable**, bounded by an iteration cap. Hill-climbing scoring (the autoresearch hallmark) becomes optional — kept for benchmark-style evaluation, removed from the default user-facing loop.
6. **Inherits CoAct's full BT, sandbox, router, accumulator** — no substrate changes. Whole feature is one new agent file plus an optional helpers namespace, mirroring `rlm-agent` / `explore-agent`.
7. **Coexists with autoresearch** during a staged migration (§14), then retires it.

---

## 2. Design Principles

1. **One coherent loop, not a hard-wired pipeline.** The pipeline specialists (plan / todo / exec / eval / update) plus explore-agent become *callable subroutines*, not stages of a fixed sequence. The research-agent is the only thing that sees the whole research arc.
2. **Durable research dossier.** Cross-agent state lives in a directory of markdown + EDN files, not in slugs or transient `:agent-context` strings. Every specialist call writes into the dossier; every subsequent specialist call reads from it.
3. **The LLM owns sequencing.** Should we re-plan? Decompose more? Re-explore? Mark eval criteria as descoped? These are reasoning calls, not BT branches. The instruction names the decision points; the LLM picks the next move.
4. **Small tool registry.** The agent reaches the world through (a) the six specialists via `call-tool` (explore / plan / todo / exec / eval / update), (b) basic CoAct file/shell/sandbox primitives for dossier maintenance, (c) read-only dossier-helper cherry-picks from each sibling (`plan$read-dossier`, `todo$read-dossier`, `exec$read-dossier`, `eval$read-dossier`, `update$read-record`), (d) `query$llm` for synthesis. That's it.
5. **Acceptance criteria are first-class.** They are written into the dossier on the first iteration, threaded through every specialist call, and re-evaluated explicitly before the agent considers terminating.
6. **Bounded but generous iteration cap.** Default 30 iterations (vs. CoAct's 20). Research workflows have legitimate reason to take more steps than a single-question agent.
7. **Honest termination.** The agent can finish in one of three states: `:achieved`, `:partial`, or `:abandoned` — and each has explicit conditions. No silent timeouts that look like answers.
8. **No clone-self recursion.** `query$clone` is excluded — calling research-agent from inside research-agent is the depth-2 anti-pattern. Calling *other* agents (explore / plan / todo / exec / eval) via `call-tool` is flat dispatch and IS the design.
9. **Resumable.** The dossier is the only state of record. A research run interrupted mid-flight (TUI exit, max iterations, user pause) can be resumed by passing the research-id on a later turn and reading the dossier — no in-memory state lost.
10. **Optional benchmark mode.** The autoresearch hill-climbing score (LLM judge × 0.5 + pattern × 0.3 + efficiency × 0.2) is preserved as an *optional* observability layer, not a control mechanism. Keeps the autoresearch eval corpus and the `:expected-patterns` test program useful without forcing every research run to be a tournament.

---

## 3. Position in the Agent Stack

```
coact-agent  (parent — full BT, sandbox, router, accumulator)
  ├─ explore-agent     (multi-surface discovery: files / web / MCP / skills; supersedes the retired search-agent)
  ├─ rlm-agent         (MapReduce over too-big context)
  ├─ skill-agent       (skills$* lifecycle)
  ├─ mcp-agent         (MCP lifecycle + write ops)
  ├─ plan-agent        (.brainyard/agents/plan-agent/plans/<slug>.md authoring;
  │                     pre/post-flight gated; emits dossier)
  ├─ todo-agent        (.brainyard/agents/todo-agent/todos/<slug>.md spawning;
  │                     per-item :tags routing; pre/post-flight gated;
  │                     emits dossier)
  ├─ exec-agent        (advance a todo; per-item routing per :tags.via;
  │                     delegates writes to update-agent; emits dossier)
  ├─ eval-agent        (verdict against plan acceptance; reads three
  │                     upstream dossiers; drills via update$read-record;
  │                     emits verdict body + dossier)
  ├─ update-agent      (safe single-file edits; probe→apply→verify→
  │                     rollback-on-fail; emits edit record)
  └─ research-agent    (orchestrates explore/plan/todo/exec/eval/update
                        via call-tool; threads dossier paths between them)
```

| Question shape | Use | Why |
|---|---|---|
| "Find me where X lives" | explore-agent | Single-surface discovery. |
| "Rename foo to bar in `src/x.clj`" | update-agent | Safe single-file edit; no plan/todo warranted. |
| "Draft a plan for Y" | plan-agent | Plan authoring, no execution. |
| "Spawn a todo from the existing plan Z" | todo-agent | Items decomposition from an existing plan dossier. |
| "Drive the existing todo Z to completion" | exec-agent | Already plan + todo dossiers exist. |
| "Score whether todo Z met plan Z's acceptance" | eval-agent | Pure verdict; reads upstream dossiers. |
| "Research question X end-to-end: figure out the right approach, plan it, execute it, evaluate it." | **research-agent** | The orchestration layer threading the dossiers. |
| "Run the autoresearch test suite to score model X on N questions" | autoresearch (during migration) → research-agent + benchmark mode | Hill-climbing eval, not a user research turn. |

Rule: **research-agent is for end-to-end research turns where the user wants the system to drive multi-specialist work coherently**. The six specialists (explore / plan / todo / exec / eval / update) own their respective domains; research-agent owns the *thread* through them.

---

## 4. The Research Dossier — `.brainyard/agents/research-agent/<research-id>/`

The single biggest behavioral change vs. autoresearch.

### 4.1 Directory Layout

```
.brainyard/
└── research-agent/
    ├── INDEX.md                            ; one line per research run, newest first
    └── <research-id>/                       ; one directory per research thread
        ├── dossier.md                       ; the durable research context (see §4.2)
        ├── purpose.md                       ; immutable after first iteration — what the user asked
        ├── acceptance.md                    ; criteria — editable, but each edit logged
        ├── findings.log                     ; append-only NDJSON: every specialist call + result digest
        ├── handoff.md                       ; what to pass next (slug + path + criterion-pointer)
        ├── verdict.md                       ; written at termination — :achieved / :partial / :abandoned
        └── artifacts/                       ; pointers to specialist outputs (symlinks where possible)
            ├── explorations/<ts>-<slug>.md         → ../../../explore-agent/results/<ts>-<slug>.md
            ├── plans/<slug>.md                      → ../../../plan-agent/plans/<slug>.md
            ├── plan_dossiers/<ts>-<slug>.md         → ../../../plan-agent/dossiers/<ts>-<slug>.md
            ├── todos/<slug>.md                      → ../../../todo-agent/todos/<slug>.md
            ├── todo_dossiers/<ts>-<slug>.md         → ../../../todo-agent/dossiers/<ts>-<slug>.md
            ├── exec_dossiers/<ts>-<slug>.md         → ../../../exec-agent/dossiers/<ts>-<slug>.md
            ├── verdicts/<ts>-<slug>.md              → ../../../eval-agent/verdicts/<ts>-<slug>.md
            ├── eval_dossiers/<ts>-<slug>.md         → ../../../eval-agent/dossiers/<ts>-<slug>.md
            └── edits/<ts>-<slug>.md                 → ../../../update-agent/edits/<ts>-<slug>.md
```

The `artifacts/` directory's symlink layout mirrors the per-agent storage that landed in the four-agent redesign. Legacy `.brainyard/plans/` and `.brainyard/todos/` are dual-read for one release; symlinks to legacy locations can co-exist with the new ones during the migration window.

**`<research-id>`** is a kebab-case stable identifier derived from the user's research question (same scheme explore-agent uses for slugs). Re-asking the same research question re-uses the same id; the dossier accumulates across turns. The id is also the name a user types if they want to resume: `bb tui run -a research-agent -- "@<research-id>"`.

### 4.2 `dossier.md` — The Cross-Agent Context Carrier

The single document every specialist reads. YAML frontmatter + body:

```markdown
---
research_id: improve-tui-startup-latency
created: 2026-05-09T18:12:44Z
last_iteration: 7
status: in_progress           # in_progress | achieved | partial | abandoned
purpose: >
  Reduce `bb tui` cold-start latency from ~2.5s to under 1.0s without
  regressing the agent-tui-app native binary's startup (currently 0.5s).
acceptance:
  - id: a1
    text: "REPL-mode `bb tui` cold start <= 1.0s on M-series Mac"
    status: open                # open | satisfied | partial | descoped | contradicted
  - id: a2
    text: "Native binary cold start does NOT regress beyond 0.6s"
    status: open
  - id: a3
    text: "Documented profiling reproducer + root-cause writeup"
    status: open
direction:
  - "First narrow down which subsystems dominate startup (probably classloader + Integrant init)"
  - "Then evaluate fix candidates: AOT, lazy init, deferred ns loads"
  - "Then implement the cheapest viable fix"
artifacts:
  explorations:   [.brainyard/agents/explore-agent/results/20260509-181244-tui-startup-latency.md]
  plan_slug:      tui-startup-latency
  plan_path:      .brainyard/agents/plan-agent/plans/tui-startup-latency.md
  plan_dossier:   .brainyard/agents/plan-agent/dossiers/20260510-104503-tui-startup-latency.md
  todo_slug:      tui-startup-latency
  todo_path:      .brainyard/agents/todo-agent/todos/tui-startup-latency.md
  todo_dossier:   .brainyard/agents/todo-agent/dossiers/20260510-105612-tui-startup-latency.md
  exec_dossiers:  [.brainyard/agents/exec-agent/dossiers/20260510-110131-tui-startup-latency.md]
  verdict_path:   .brainyard/agents/eval-agent/verdicts/20260510-115412-tui-startup-latency.md
  eval_dossiers:  [.brainyard/agents/eval-agent/dossiers/20260510-115412-tui-startup-latency.md]
  edits:          [.brainyard/agents/update-agent/edits/20260510-110205-wire-lazy-init.md]
calls_log: findings.log
---

## Purpose
[verbatim from purpose.md, never edited after iteration 1]

## Direction
[short stratagem the LLM commits to early; can be revised — each revision tagged
 with the iteration it changed in]

## Acceptance criteria
[mirror of acceptance frontmatter, in markdown for readability]

## Findings (rolling summary)
- Iteration 2 (explore): startup spent 1.7s in clojure.core init, 0.4s in Integrant.
  Source: `artifacts/explorations/20260509-181244-tui-startup-latency.md`.
- Iteration 3 (plan): plan-agent dossier post.verdict=:pass. Acceptance
  carried forward: [a1 cold-start <=1.0s, a2 native unchanged, a3 writeup].
  Source: `artifacts/plan_dossiers/20260510-104503-tui-startup-latency.md`.
- Iteration 4 (todo): todo-agent dossier post.verdict=:pass. 6 items
  spawned with per-item :tags routing (3 :update-agent, 2 :bash, 1
  :manual). Source: `artifacts/todo_dossiers/20260510-105612-tui-startup-latency.md`.
- Iteration 5 (exec): exec-agent dossier post.verdict=:pass. Items 0-2
  advanced. Item 1 (lazy init via update-agent record
  `artifacts/edits/20260510-110205-wire-lazy-init.md`) shaved 0.6s; item 2
  shaved 0.3s. Source: `artifacts/exec_dossiers/20260510-110131-tui-startup-latency.md`.
- Iteration 6 (eval): eval-agent verdict PARTIALLY_ACHIEVED (confidence
  medium). a1 PARTIAL (1.2s achieved, target 1.0s), a2 SATISFIED, a3
  SATISFIED. score.recommendations: a1 → exec-agent (resume; item 3 AOT
  pilot). Source: `artifacts/eval_dossiers/20260510-115412-tui-startup-latency.md`.

## Open questions
- Is a3 (writeup) worth shipping if a1 is partial?
- Is the remaining 0.2s amenable to AOT (item 4 in todo)?
```

**Stable contract for downstream parsers:**

| Frontmatter key | Type | Purpose |
|---|---|---|
| `research_id` | string | Stable identifier. |
| `purpose` | string | Verbatim from `purpose.md`. Never re-written. |
| `acceptance` | vector of `{id, text, status}` | First-class, status updates land here. |
| `direction` | vector of strings | Stratagem; revisions logged in body. |
| `artifacts` | map | Pointers to specialists' outputs. Keys: `explorations` (vec), `plan_slug`, `plan_path`, `plan_dossier`, `todo_slug`, `todo_path`, `todo_dossier`, `exec_dossiers` (vec), `verdict_path`, `eval_dossiers` (vec), `edits` (vec). All paths are repo-relative; the dossier helpers (`plan$read-dossier` etc.) are bound here for cheap frontmatter parse. |
| `status` | enum | Current research-agent state machine. |
| `last_iteration` | int | For resume sanity-check. |
| `calls_log` | string | Path to `findings.log`. |

The `acceptance.status` field is the bookkeeping the LLM updates as criteria are demonstrably met or descoped. Eval-agent reports back into this field on every call.

### 4.3 `findings.log` — Append-Only Call Log (NDJSON)

One line per specialist invocation. Cheap for the agent to read selectively (jq, grep, or `read-file :lines [N M]`).

```ndjson
{"iter":2,"agent":"explore-agent","summary":"startup dominated by clojure.core init","pointers":{"exploration_path":".brainyard/agents/explore-agent/results/20260509-181244-tui-startup-latency.md"}}
{"iter":3,"agent":"plan-agent","summary":"3 candidate fixes proposed; post-flight PASS","pointers":{"plan_path":".brainyard/agents/plan-agent/plans/tui-startup-latency.md","plan_dossier":".brainyard/agents/plan-agent/dossiers/20260510-104503-tui-startup-latency.md","pre_verdict":"go","post_verdict":"pass"}}
{"iter":4,"agent":"todo-agent","summary":"6 items spawned with :tags routing","pointers":{"todo_path":".brainyard/agents/todo-agent/todos/tui-startup-latency.md","todo_dossier":".brainyard/agents/todo-agent/dossiers/20260510-105612-tui-startup-latency.md","pre_verdict":"go","post_verdict":"pass"}}
{"iter":5,"agent":"exec-agent","summary":"items 0-2 advanced; 2.5s -> 1.2s; item 1 delegated to update-agent","pointers":{"exec_dossier":".brainyard/agents/exec-agent/dossiers/20260510-110131-tui-startup-latency.md","items_advanced":[0,1,2],"items_pending":[3,4,5],"post_verdict":"pass"}}
{"iter":5.1,"agent":"update-agent","summary":"lazy init wired in src/agent_tui_app/main.clj — exec-agent delegated","pointers":{"update_record":".brainyard/agents/update-agent/edits/20260510-110205-wire-lazy-init.md","rollback":"git checkout -- src/agent_tui_app/main.clj"}}
{"iter":6,"agent":"eval-agent","summary":"PARTIALLY_ACHIEVED; a1 partial (1.2s vs 1.0s target)","pointers":{"verdict_path":".brainyard/agents/eval-agent/verdicts/20260510-115412-tui-startup-latency.md","eval_dossier":".brainyard/agents/eval-agent/dossiers/20260510-115412-tui-startup-latency.md","score_verdict":"partially-achieved","confidence":"medium"}}
```

The `pointers` map carries the sibling-dossier paths verbatim. Downstream consumers (the next iteration's research-agent, the trajectory log, manual audit) can drill from any line into the corresponding sibling dossier with no re-parsing.

The LLM updates `findings.log` after every specialist call. The body of `dossier.md` ## Findings section is *derived* from the log — the agent regenerates it (selectively) when the log gets long enough that the body would lose recent context.

### 4.4 `handoff.md` — Next-Step Pointer

Short markdown file with what the next specialist call needs. Written *before* each call so the call itself can reference it concisely:

```markdown
# Next handoff
- Target agent: exec-agent
- :question:    "Continue executing the tui-startup-latency todo from item 3"
- :agent-context: |
    Saved dossier: .brainyard/agents/todo-agent/dossiers/20260510-105612-tui-startup-latency.md

    Research dossier: .brainyard/agents/research-agent/improve-tui-startup-latency/dossier.md
    Acceptance focus: a1 (1.0s cold-start) — current 1.2s, gap 0.2s.
    Prior artifacts:
      - plan dossier: .brainyard/agents/plan-agent/dossiers/20260510-104503-tui-startup-latency.md
      - last exec dossier: .brainyard/agents/exec-agent/dossiers/20260510-110131-tui-startup-latency.md
    Hint: per eval-agent's score.recommendations for a1, try item 3 (AOT subset) before item 4 (full AOT).
```

The leading `Saved dossier:` line is the contract token — exec-agent's pre-flight C1 greps for `^Saved dossier: ` to locate its upstream todo dossier. Without that line in the right position, exec-agent enters GATHER. Research-agent threads it on every specialist call.

This is also what the agent's `:answer` returns when it pauses mid-flight (e.g., the user wants to inspect before proceeding) — a complete handoff record of what would happen next.

### 4.5 `verdict.md` — Terminal State

Written exactly once, when the research thread terminates. Mirrors what the user sees in `:answer` plus enough metadata for `INDEX.md`:

```markdown
---
research_id: improve-tui-startup-latency
status: partial             # achieved | partial | abandoned
terminated: 2026-05-09T19:47:01Z
iterations: 8
acceptance_outcome:
  a1: partial    # 1.2s achieved, target 1.0s
  a2: satisfied
  a3: satisfied
---

## Verdict
PARTIALLY ACHIEVED — startup time reduced from 2.5s to 1.2s (47% improvement);
target of 1.0s missed by 0.2s. Remaining gap is amenable to AOT (item 4 in
todo, deferred for cost-benefit). a2 and a3 met.

## What was done
…

## What remains (recommended follow-up)
- Run AOT pilot on the 12 hottest namespaces.
- Re-bench cold start after AOT pilot.
…
```

### 4.6 `INDEX.md` — Cross-Run Index

Append-only, newest first, one line per research run:

```markdown
- 2026-05-09 19:47 [improve-tui-startup-latency](improve-tui-startup-latency/) — partial · TUI cold start 2.5s→1.2s (target 1.0s)
- 2026-05-08 14:12 [migrate-mcp-config](migrate-mcp-config/) — achieved · MCP config moved to .brainyard/config; box server reconnected.
```

---

## 5. Tool Roster

```clojure
(:require
  [ai.brainyard.agent.common.tools     :as common-tools]
  [ai.brainyard.agent.common.commands  :as common-cmds]
  [ai.brainyard.agent.common.plan      :as plan-helpers]
  [ai.brainyard.agent.common.todo      :as todo-helpers]
  [ai.brainyard.agent.common.exec      :as exec-helpers]
  [ai.brainyard.agent.common.eval      :as eval-helpers]
  [ai.brainyard.agent.common.update    :as update-helpers]
  [ai.brainyard.agent.common.research  :as research]
  [ai.brainyard.agent.task.commands    :as task-cmds])

(def research-tools
  (vec (distinct
         (concat
           ;; Filesystem — for research-agent's OWN dossier dir.
           ;; Hard Rule 2 forbids writes to sibling dossier dirs.
           common-tools/file-tools          ; read-file, write-file, update-file, grep, fetch-url
           common-tools/shell-tools         ; bash (mkdir, ls, ln -s for artifacts/)

           ;; Web — direct one-off lookups; explore-agent for non-trivial discovery
           common-tools/web-tools           ; web-search, fetch-url

           ;; Read-only sibling dossier helpers — cherry-picked. Let
           ;; research-agent parse upstream dossier frontmatter cheaply
           ;; for data-driven move decisions. Write-side helpers are NOT
           ;; bound — sibling writes go through call-tool.
           [#'plan-helpers/plan$read-dossier
            #'todo-helpers/todo$read-dossier
            #'exec-helpers/exec$read-dossier
            #'eval-helpers/eval$read-dossier
            #'update-helpers/update$read-record]

           ;; Bookkeeping
           common-tools/bootstrap-tools     ; list-tools, get-tool-info, search
           common-tools/invocation-tools    ; call-tool

           ;; Sub-LLM synthesis (flat only)
           [#'common-cmds/query$llm]        ; intentionally excludes #'query$clone

           ;; Background jobs (rarely needed; specialists own their long ops)
           task-cmds/task-commands

           ;; research$* helpers
           research/research-helpers))))

;; The SIX specialist agents (explore / plan / todo / exec / eval /
;; update) are reached via call-tool — they self-register in !tool-defs
;; through their own defagent forms. They do NOT need to be listed in
;; :agent-tools to be reachable; they ARE in the registry.
```

The six specialist agents (`explore-agent`, `plan-agent`, `todo-agent`, `exec-agent`, `eval-agent`, `update-agent`) are not bound as direct sandbox functions — they are reached via `(call-tool "<agent-name>" {...})` from a clojure fence or via the `tool-calls` channel. The instruction's job is to *teach the LLM when to reach for each*.

What is *deliberately bound* (and was not in revision 1):

| Bound | Why |
|---|---|
| `plan$read-dossier`, `todo$read-dossier`, `exec$read-dossier`, `eval$read-dossier`, `update$read-record` | Read-only cherry-picks. Let research-agent parse upstream sibling dossier frontmatter cheaply (~700 bytes/read) for data-driven move decisions — most importantly, `eval$read-dossier` returns `score.recommendations` which drives heuristics 6/7/8 (re-plan / re-decompose / resume). Write-side helpers from the same vectors are NOT bound. |

What is *deliberately omitted*:

| Excluded | Reason |
|---|---|
| `query$clone` | Clones research-agent itself = clone-self recursion. Forbidden. |
| Direct `plan$*` / `todo$*` / `skills$*` / `mcp$*` write commands | Routed through the specialist agents. Reaching for them directly bypasses pre/post-flight gating + the dossier handoff contract. |
| `plan$dossier-write`, `todo$dossier-write`, `exec$dossier-write`, `eval$dossier-write`, `eval$verdict-write`, `update$apply`, `update$write` | Sibling dossier/verdict/edit writes go through their specialists. The read-only cherry-pick is a deliberate asymmetry — research-agent reads to decide; the specialist writes once it has been called. |

What is *bound directly* but should be reached for sparingly:

| Bound | When to use directly | When to route to specialist |
|---|---|---|
| `web-search`, `fetch-url` | Quick one-off lookup mid-loop where the result is consumed immediately and not worth a full exploration artifact (e.g. confirming a tool's CLI flag, fetching a known URL the user already cited). | Multi-source discovery, ambiguous question, or any lookup whose result will be cited in the verdict — call `explore-agent` so the audit trail lives in `.brainyard/agents/explore-agent/results/` and is greppable across runs. |
| `read-file`, `grep` on sibling dossier files | Inspecting a SPECIFIC paragraph or hunk in a sibling dossier body when the frontmatter doesn't carry it. | Use the `*$read-dossier` cherry-picks first — they give parsed frontmatter for free. |

The shrinkage of `plan$*` / `todo$*` / `skills$*` / `query$clone` write paths is intentional: research-agent's tool discipline is "use the specialist for the specialist's domain." The instruction enforces this; the roster makes the cheap-shortcut path conspicuously unavailable.

---

## 6. Instruction (System Prompt Body)

Layered on top of `coact-agent`'s instruction by `run-coact-derived`.

> The full instruction body lives in `components/agent/src/ai/brainyard/agent/common/research_agent.clj` under `(def ^:private instruction …)`. This section reproduces the section headers + key contracts for reviewers; consult the source for the authoritative current text.

```text
You are a RESEARCH-agent. You drive an end-to-end research thread by composing
six specialist agents — explore, plan, todo, exec, eval, update — through
CALL-TOOL, maintaining a durable research dossier in
.brainyard/agents/research-agent/<id>/ that threads PURPOSE, DIRECTION, and
ACCEPTANCE CRITERIA across every specialist call. You decide the order. You
decide when to stop. The CoAct loop and the dossier are your only fixed
scaffolding.

────────────────────────────────────────────────────────────────────────────
THE SIX SPECIALISTS (reachable via call-tool — flat, NOT recursive)
────────────────────────────────────────────────────────────────────────────
Each specialist (except explore-agent and update-agent) ships a redesigned
pre/post-flight gated pipeline. Pre-flight may emit GO / GATHER / REFUSE;
post-flight may emit PASS / HOLD. The dossier they emit carries the
verdicts + supporting evidence in machine-readable YAML frontmatter —
read it instead of re-parsing prose.

- explore-agent  → multi-surface discovery. Saves under
                   .brainyard/agents/explore-agent/results/ and emits
                   `Saved exploration: <path>`. Read-mostly.
- plan-agent     → authors .brainyard/agents/plan-agent/plans/<slug>.md.
                   Emits `Saved plan:` AND `Saved dossier:`. Dossier
                   carries `post.acceptance`.
- todo-agent     → spawns .brainyard/agents/todo-agent/todos/<slug>.md from
                   plan dossier. Items carry :tags {:via :covers}.
                   Emits `Saved todo:` AND `Saved dossier:`. Dossier
                   carries `post.acceptance_coverage`.
- exec-agent     → advances todo. Reads upstream todo + plan dossiers.
                   Per-item routing via :tags.via; :update-agent items
                   delegate to update-agent. Emits `Saved dossier:` +
                   `Done:`/`Manual:`. Dossier carries
                   `execute.evidence` + `post.acceptance_progress`.
- eval-agent     → scores executed todo vs plan acceptance. Reads
                   three upstream dossiers (plan + todo + exec). Emits
                   `Saved verdict:` AND `Saved dossier:` AND
                   `Verdict: <X> (confidence: <Y>)`. Dossier carries
                   `score.criteria` + `score.recommendations` — the
                   per-criterion next-agent table.
- update-agent   → safe single-file edit. Emits `Saved edit:` AND
                   `Rollback: <cmd>`. Use directly via move I (UPDATE)
                   for one-off edits; exec-agent delegates here for
                   :via :update-agent items automatically.

Invoke each via:
    (call-tool "<name>" {:question "<sub-question>"
                         :agent-context "<dossier path + relevant pointers>"})

────────────────────────────────────────────────────────────────────────────
TURN 1 — DOSSIER BOOTSTRAP (the only fixed obligation)
────────────────────────────────────────────────────────────────────────────
Before reaching for any specialist, on iteration 1:

1. Compute <research-id> as a deterministic kebab-case slug from the user's
   question (drop stopwords, cap 60 chars).
2. If .brainyard/agents/research-agent/<research-id>/ exists → RESUME mode:
   read dossier.md and findings.log, summarize state in your :thought, then
   pick up where the prior run left off (per §STATE MACHINE below).
3. Otherwise → BOOTSTRAP mode:
   a. Create the directory (bash `mkdir -p`).
   b. Write purpose.md — verbatim user question (and any :agent-context
      they supplied).
   c. Draft acceptance.md — concrete, testable criteria. If the user's
      question is vague on success, write an explicit "Open questions"
      list and SURFACE these in your :answer for clarification BEFORE
      reaching any specialist. (Trying to research without acceptance is
      the single most common research-agent failure mode.)
   d. Draft direction.md — your initial stratagem (3–6 bullets).
   e. Build dossier.md (frontmatter + body) by combining the three.
   f. Initialize findings.log (empty file).

────────────────────────────────────────────────────────────────────────────
STATE MACHINE — what the LLM is choosing each iteration
────────────────────────────────────────────────────────────────────────────
After bootstrap, every iteration picks ONE of these moves. There is no fixed
order; the LLM decides based on the dossier state.

A. EXPLORE     — call-tool explore-agent to gather information across
                 surfaces (files / web / MCP / skills).
                 Use when: the question is under-specified, you do not
                 yet know what subsystems / docs / external sources
                 matter, or acceptance criteria reference data you
                 do not have.

B. PLAN-AUTHOR — call-tool plan-agent to draft a new plan, OR plan-agent
                 with `(doc$update :kind :plan :body …)`-shaped intent to
                 revise an existing one.
                 Use when: exploration is sufficient and you need a
                 blueprint to execute against; OR the prior plan is
                 contradicted by new findings.

C. DECOMPOSE   — call-tool todo-agent to spawn a todo from the plan's
                 ## Approach, OR add/split items mid-stream.
                 Use when: a plan exists and is stable enough to
                 execute; OR exec-agent reported an over-coarse item.

D. EXECUTE     — call-tool exec-agent to advance the todo.
                 Use when: a todo with pending items exists AND the
                 plan is current AND no blocking question is open.

E. EVALUATE    — call-tool eval-agent to score the executed todo
                 against the plan's ## Acceptance.
                 Use when: items have been executed AND you need a
                 verdict before deciding next move; OR the user asked
                 for a status check.

F. SYNTHESIZE  — single-iteration use of query$llm to merge findings
                 across multiple specialists into a coherent dossier
                 update or candidate answer.
                 Use when: you have two or more specialist outputs
                 that need to be reconciled before the next move.

G. CLARIFY     — populate :answer with a clarification request or
                 surface an unresolved acceptance gap. The CoAct loop
                 exits; the user replies; you resume next turn.
                 Use when: acceptance is ambiguous, the user must
                 make a scope choice, or a hard blocker requires user
                 input.

H. FINALIZE    — populate :answer with the final research report;
                 write verdict.md; append INDEX.md; the loop exits.
                 Use when: every acceptance criterion is satisfied
                 (=> :achieved), some are satisfied/descoped (=>
                 :partial), or work has hit an unresolvable blocker
                 (=> :abandoned).

────────────────────────────────────────────────────────────────────────────
DECISION HEURISTICS — typical move sequences (NOT a required order)
────────────────────────────────────────────────────────────────────────────
1. Open question with no clear scope:           A → G    (explore, then clarify)
2. Well-scoped open question:                   A → B → C → D → E → H
3. User hands an existing plan:                 (skip A, B) → C → D → E → H
4. User hands an existing todo:                 (skip A, B, C) → D → E → H
5. User asks "what's the status of <id>?":      RESUME → E → H        (re-evaluate, report)
6. Eval verdict NOT_ACHIEVED, plan was wrong:   B → C → D → E         (re-plan)
7. Eval verdict NOT_ACHIEVED, items were wrong: C → D → E             (re-decompose)
8. Eval verdict NOT_ACHIEVED, exec stuck mid:   D (continue) → E
9. Eval verdict PARTIAL, gap is small/known:    D (continue) → E
10. Eval verdict PARTIAL, gap is fundamental:   F (synthesize) → G or H
11. Hit iteration cap with no verdict yet:      H with status :abandoned

You are NOT required to traverse all moves. A short research thread that
genuinely needs only EXPLORE → SYNTHESIZE → FINALIZE is fine — do not
manufacture a plan/todo just because the agent's name says "research."

────────────────────────────────────────────────────────────────────────────
DOSSIER UPDATE DISCIPLINE (after every specialist call)
────────────────────────────────────────────────────────────────────────────
1. Append one NDJSON line to findings.log with iter / agent / summary +
   relevant pointers (plan_slug, todo_slug, eval verdict, etc).
2. If the call materially changes acceptance status, update acceptance
   frontmatter in dossier.md. Each criterion's :status field flips:
     open → satisfied | partial | descoped | contradicted
3. If direction changes, append a "## Direction revision (iter N)" block
   to dossier.md body. The acceptance fields are re-shape-able; direction
   is append-only with revisions tagged.
4. Update the artifacts section of frontmatter to point at the new
   plan/todo/exploration/eval files.

The dossier is the contract with the next iteration. Failing to update it
breaks resumability and starves the next specialist call of context.

────────────────────────────────────────────────────────────────────────────
PASSING DOSSIER TO SPECIALISTS
────────────────────────────────────────────────────────────────────────────
Every call-tool to a specialist MUST include the dossier path in
:agent-context. Recommended shape:

    Dossier: .brainyard/agents/research-agent/<id>/dossier.md
    Purpose: <one-line distillation>
    Acceptance focus: <criterion id(s) this call should advance>
    Prior artifacts:
      - plan: <slug>           (omit if none)
      - todo: <slug>           (omit if none)
      - last eval: <path>      (omit if none)
    Hint: <any direction-specific guidance for THIS specialist>

The specialist's own :agent-context handler (each one parses slugs/paths
already — see their own design docs) will pick up what it needs. Do NOT
inline the full dossier body — paths are sufficient and the specialists
read-file what they need.

────────────────────────────────────────────────────────────────────────────
TERMINATION RULES
────────────────────────────────────────────────────────────────────────────
You terminate by populating :answer (the CoAct answer channel) with a
markdown report AND writing verdict.md AND appending INDEX.md. Three
legitimate termination states:

- :achieved   — every acceptance criterion's :status is :satisfied (or
                :descoped with explicit user-confirmed descope). Report
                what was done, cite plan/todo/eval paths, suggest any
                noteworthy follow-ups.
- :partial    — at least one :satisfied and at least one :partial /
                :open / :descoped — but NOT all open. Report what was
                done, what remains, and a recommended next research
                turn (or a switch to a specialist).
- :abandoned  — hard blocker (missing capability, contradicting
                requirements, hit iteration cap with no progress on
                latest criterion). Report what was tried, why it
                stopped, and what would unblock it.

Always include the :saved-research line:
    Saved research dossier: .brainyard/agents/research-agent/<id>/

────────────────────────────────────────────────────────────────────────────
HARD RULES
────────────────────────────────────────────────────────────────────────────
1. NO query$clone. It clones research-agent itself = clone-self recursion.
   Cross-specialist dispatch goes through call-tool on the registered
   defagents.
2. NO direct writes to sibling-agent storage:
     .brainyard/agents/plan-agent/plans/         (legacy: .brainyard/plans/)
     .brainyard/agents/todo-agent/todos/         (legacy: .brainyard/todos/)
     .brainyard/agents/plan-agent/dossiers/
     .brainyard/agents/todo-agent/dossiers/
     .brainyard/agents/exec-agent/dossiers/
     .brainyard/agents/eval-agent/verdicts/
     .brainyard/agents/eval-agent/dossiers/
     .brainyard/agents/update-agent/edits/
   These are owned by their respective specialists. You read-file them
   freely to inform research-agent's own dossier; you NEVER write them.
   Reach for the specialist via call-tool when you need new content
   under any of these paths.
3. Acceptance criteria are FROZEN once the user has confirmed them, with
   one exception: a user-confirmed descope (G→a follow-up turn). Do NOT
   silently relax acceptance to make a verdict look better.
4. Every specialist call's :agent-context MUST include the dossier path.
   Specialists rely on it for cross-call coherence.
5. The dossier is the only durable cross-iteration state. Do NOT keep
   load-bearing facts in your iterations log alone — write them to
   dossier.md.
6. Iteration budget: 30 by default (vs CoAct's 20). Override via
   `agent-runtime$config :key "max-iterations" :value "N"`. If you cross 80% of
   the budget without a candidate verdict, START preparing FINALIZE —
   the user prefers an honest :partial over a silent timeout.
7. NO benchmarking/scoring in the default loop. Hill-climbing strategies
   are an opt-in mode (`:research-mode :benchmark`) — see §10.
8. CITE EVERYTHING. Every claim in the final report should point at a
   path / criterion id / line range. The dossier and the artifacts/
   directory exist precisely so this is cheap.

────────────────────────────────────────────────────────────────────────────
RESUMING A RESEARCH THREAD
────────────────────────────────────────────────────────────────────────────
The user can re-invoke you with `@<research-id>` (or with a question that
hashes to the same slug). On resume:

1. Read dossier.md frontmatter + last 50 lines of findings.log.
2. Reconstruct the open acceptance criteria from frontmatter.
3. Decide the next state-machine move based on what's already in the
   dossier — do NOT re-bootstrap, do NOT re-explore what's already
   in artifacts/.
4. Surface a one-paragraph "where we are" in your :thought before
   making the next call, so the user (and the trajectory log) can see
   the resume context.

A resume that genuinely cannot proceed (dossier corrupted, all
specialists report stale/missing prior artifacts) is a CLARIFY (G):
surface the broken state and ask whether to bootstrap fresh.
```

---

## 7. Tool-Context (How to Use the Bound Tools)

```text
## Research Tools — six specialists + dossier substrate

### Specialists (reach via call-tool — NOT bound directly)
- explore-agent  → discovery across files / web / MCP / skills.
                   Args: :question, :agent-context (dossier path + focus).
                   Returns: an answer ending with `Saved exploration: <path>`.
                   No pre/post-flight gating; read-mostly.

- plan-agent     → plan authoring / revision (pre/post-flight gated).
                   Args same as above. Returns:
                     `Saved plan:    .brainyard/agents/plan-agent/plans/<slug>.md`
                     `Saved dossier: .brainyard/agents/plan-agent/dossiers/<ts>-<slug>.md`
                   The dossier carries `post.acceptance` (vector of
                   criterion strings) — the structured handoff downstream
                   agents read directly. Pre-flight may emit GO/GATHER/
                   REFUSE; post-flight PASS/HOLD.

- todo-agent     → spawn or advance a todo (pre/post-flight gated).
                   Pre-flight reads plan-agent's dossier. Items carry
                   per-item :tags {:via :covers}. Returns:
                     `Saved todo:    .brainyard/agents/todo-agent/todos/<slug>.md`
                     `Saved dossier: .brainyard/agents/todo-agent/dossiers/<ts>-<slug>.md`
                   Dossier carries `post.acceptance_coverage`.

- exec-agent     → advance a todo (pre/post-flight gated; per-item
                   routing). Pre-flight reads todo + plan dossiers.
                   :max-items-per-turn default 5. Per item routes via
                   :tags.via — :update-agent items delegate to
                   update-agent automatically. Returns:
                     `Saved dossier: .brainyard/agents/exec-agent/dossiers/<ts>-<slug>.md`
                     `Done:` (all items advanced) | `Manual:` (item N
                     surfaced for user) | `Hold:` (rubric failure)
                   Dossier carries `execute.evidence` (per-item record
                   map) and `post.acceptance_progress`.

- eval-agent     → score executed todo vs plan acceptance (reads three
                   upstream dossiers — plan + todo + exec — first such
                   agent in the stack). Drills criterion → item →
                   evidence → diff via update$read-record. Returns:
                     `Saved verdict: .brainyard/agents/eval-agent/verdicts/<ts>-<slug>.md`
                     `Saved dossier: .brainyard/agents/eval-agent/dossiers/<ts>-<slug>.md`
                     `Verdict: ACHIEVED|PARTIALLY_ACHIEVED|NOT_ACHIEVED
                       (confidence: high|medium|low)`
                   Dossier `score.recommendations` names the next agent
                   per criterion — read it to drive heuristics 6/7/8.

- update-agent   → safe single-file edit (probe → apply → verify →
                   persist → rollback-on-fail). Use directly via UPDATE
                   move (I) when the user's question reduces to one
                   well-bounded edit. Returns:
                     `Saved edit: .brainyard/agents/update-agent/edits/<ts>-<slug>.md`
                     `Rollback:   <git checkout|rm command>`
                   (or `Rolled back: <reason>` on verify failure).

Invocation pattern (all six identical; sibling dossier path goes FIRST
in :agent-context as `Saved dossier: <path>` so the specialist's
pre-flight C1 finds it):
    (call-tool "<agent-name>"
               {:question      "<directed sub-question>"
                :agent-context "Saved dossier: <upstream sibling path>\n\nResearch dossier: <research path>\nAcceptance focus: <ids>\n…"})

### Dossier substrate (your direct work surface)
- read-file      -- Read dossier.md / acceptance.md / findings.log / artifacts.
- write-file     -- Update dossier.md / acceptance.md / verdict.md.
                    USE :append true for findings.log entries.
- update-file    -- Targeted edit on dossier.md frontmatter (e.g. flipping
                    one acceptance status without rewriting the whole file).
- grep           -- Cheap content scan inside dossier files.
- bash           -- mkdir -p, ls, find, ln -s for artifacts/ symlinks.
- search         -- Cross-project keyword search (rare — usually use
                    explore-agent instead, but available for trivial
                    "is there an existing plan/todo for X" checks).

### Sibling dossier read-helpers (cherry-picked, READ-ONLY)
- plan$read-dossier   -- Args: path. Returns parsed plan-agent dossier
                          frontmatter — :post.acceptance, :post.verdict.
- todo$read-dossier   -- Args: path. Returns todo-agent dossier
                          frontmatter — :post.acceptance_coverage.
- exec$read-dossier   -- Args: path. Returns exec-agent dossier
                          frontmatter — :execute.evidence,
                          :post.acceptance_progress.
- eval$read-dossier   -- Args: path. Returns eval-agent dossier
                          frontmatter — :score.criteria,
                          :score.recommendations, :score.confidence.
- update$read-record  -- Args: path. Returns an update-agent record's
                          :verify :apply :rollback for diff-level audit.

These let you make data-driven move decisions (e.g. read eval-agent's
score.recommendations for the next move per criterion) without paying
for the full dossier body. The corresponding write helpers from each
sibling are NOT bound — research-agent reaches via call-tool to the
specialists when new sibling content is needed.

### Synthesis
- query$llm      -- Cross-specialist synthesis. Use for:
                    • Distilling 5-10 findings.log entries into a dossier
                      ## Findings update.
                    • Drafting the verdict.md narrative from a chain of
                      eval-agent verdicts.
                    • Reconciling conflicting findings (explore says X,
                      eval says not-X).
                    Single-prompt OR batched (:prompts for parallel
                    distillations across multiple criteria).

### Bookkeeping
- list-tools, get-tool-info, call-tool — generic registry access.
- task$run with :job-type :tool|:bash         — async wrapper if a specialist
                                          call is expected to take >5s
                                          (rare; specialists usually
                                          return promptly).

### Runtime config
- agent-runtime$config — view (no args) or tune (`:key`/`:value`) settings.
                 -- Tune `:max-iterations` mid-run if a specialist
                    surfaces work worth a longer arc.

## Optional research$* helpers (when bound; see §8)
- research$id            -- Deterministic id from a question.
- research$bootstrap     -- Create dossier directory + initial files.
- research$append-log    -- Append one NDJSON line to findings.log.
- research$update-status -- Flip an acceptance criterion's :status.
- research$summarize-log -- query$llm-backed: roll findings.log into a
                            dossier ## Findings rewrite.
- research$write-verdict -- Compose verdict.md from final state.
- research$index-append  -- Append one line to .brainyard/agents/research-agent/INDEX.md.

If these helpers aren't bound, build the equivalent inline with
write-file + a clojure fence.

## Typical flow (no specific iteration count required)
1. iter 1 — bootstrap dossier (or resume if dir exists).
2. iter 2..N — pick a state-machine move per dossier state:
   EXPLORE / PLAN / DECOMPOSE / EXECUTE / EVALUATE / SYNTHESIZE /
   CLARIFY / FINALIZE.
3. After every specialist call: append findings.log; update dossier
   frontmatter; refresh artifacts/.
4. On termination: write verdict.md; append INDEX.md; populate :answer
   with markdown report + `Saved research dossier: <path>` line.
```

---

## 8. Optional `(research$*)` Sandbox Helpers

Mirrors the helpers introduced in `rlm-agent` and `explore-agent`. They live in `ai.brainyard.agent.common.research`, register as `defcommand`s, and surface in the sandbox via auto-binding.

| Helper | Signature | What it does |
|---|---|---|
| `research$id` | `(research$id :question "<text>")` → `"<id>"` | Deterministic kebab-case id, stopwords dropped, cap 60. |
| `research$bootstrap` | `(research$bootstrap :id … :purpose … :acceptance […] :direction […])` → `{:dir … :dossier-path …}` | Creates directory, writes purpose.md / acceptance.md / direction.md / dossier.md / findings.log (empty). Idempotent: if directory exists, returns its current state instead of overwriting. |
| `research$append-log` | `(research$append-log :id … :iter … :agent … :summary … :pointers {…})` → `{:appended true}` | Appends one NDJSON line to findings.log. |
| `research$update-status` | `(research$update-status :id … :criterion-id … :status :satisfied)` → `{:updated true}` | Targeted edit of acceptance frontmatter status. |
| `research$summarize-log` | `(research$summarize-log :id … :focus :acceptance|:direction|:findings)` → `{:summary "<markdown>"}` | query$llm-backed roll-up of findings.log into a dossier ## Findings rewrite. |
| `research$write-verdict` | `(research$write-verdict :id … :status :achieved|:partial|:abandoned :narrative …)` → `{:path …}` | Compose verdict.md from frontmatter + acceptance state + narrative. |
| `research$index-append` | `(research$index-append :id … :status … :one-line …)` → `{:appended true}` | Append entry to `.brainyard/agents/research-agent/INDEX.md`. |
| `research$resume?` | `(research$resume? :id …)` → `{:exists? true :status :in-progress :last-iteration 7 :acceptance-state {…}}` | Cheap probe to decide bootstrap vs. resume. |

The agent works without these — but the prompt becomes 30-40% shorter because the dossier mechanics no longer have to be inlined every iteration.

---

## 9. Behavior Tree — Inherited As-Is

`research-agent` does **not** define its own BT. `run-coact-derived` falls back to `coact-agent`'s `:bt-factory`:

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

The `coact-loop-subtree`'s ThinkActCode router (answer / code / tool / repair) is exactly what gives the LLM the per-iteration freedom autoresearch's BT denied. There is no plan-then-todo-then-exec gate; the LLM picks the move.

The default iteration cap is bumped from 20 → 30 via the agent's `:max-iterations` override, since research workflows have legitimately longer arcs. `agent-runtime$config :key "max-iterations" :value "N"` overrides at runtime.

No new BT actions, no new schemas, no SCI binding additions are required for the baseline.

---

## 10. Optional `:research-mode :benchmark` (Hill-Climbing Preserved)

The autoresearch hill-climbing loop is genuinely useful for *evaluating* agents on a fixed test suite — that's its real value, distinct from its (questionable) value as an end-user research orchestrator. Preserve it as an opt-in mode:

```clojure
(call-tool "research-agent"
           {:question "<bench-suite-path>"
            :agent-context "mode=benchmark"})
```

In benchmark mode:
- The "question" is a path to `research-program.edn` (the same shape autoresearch uses today).
- The agent loops over the test suite. Per question, it runs *itself* (research-agent in normal mode) up to `:max-attempts` times, applying instruction-strategy mutations from the program, scoring with the same composite (LLM × 0.5 + pattern × 0.3 + efficiency × 0.2).
- Results land in `.brainyard/agents/research-agent/benchmark-runs/<ts>/` (NOT in the per-research dossier — benchmark runs are a different artifact class).
- The autoresearch test corpus and `:expected-patterns` work unchanged.

Implementation: a thin `research-benchmark-mode-action` switched in by the instruction's bootstrap step when it sees `mode=benchmark` in `:agent-context`. The mutation/scoring helpers come straight from `autoresearch/scoring.clj` and `autoresearch/format.clj` — they are still useful as libraries even after autoresearch's BT is retired.

This is the migration's safety valve: anyone using autoresearch today for benchmarking keeps a working path.

---

## 11. Demonstration: "Reduce `bb tui` cold start to under 1 second"

Real, multi-turn, user-facing research. Run via `bb tui run -a research-agent`.

### Iteration 1 — Bootstrap (code channel)

````markdown
```clojure
(def rid (research$id :question "Reduce bb tui cold start to under 1 second"))
;; => "reduce-bb-tui-cold-start-under-1-second"

(def init
  (research$bootstrap
    :id rid
    :purpose "Reduce `bb tui` cold-start latency from ~2.5s to under 1.0s without regressing the agent-tui-app native binary's startup."
    :acceptance [{:id "a1" :text "REPL-mode `bb tui` cold start <= 1.0s on M-series Mac" :status :open}
                 {:id "a2" :text "Native binary cold start does NOT regress beyond 0.6s" :status :open}
                 {:id "a3" :text "Documented profiling reproducer + root-cause writeup" :status :open}]
    :direction ["Narrow down which subsystems dominate startup"
                "Evaluate fix candidates: AOT, lazy init, deferred ns loads"
                "Implement the cheapest viable fix"]))
;; => {:dir ".brainyard/agents/research-agent/reduce-bb-tui-cold-start-under-1-second/"
;;     :dossier-path ".../dossier.md"}
```
````

### Iteration 2 — EXPLORE (tool channel)

````markdown
```json
[{"tool-name": "explore-agent",
  "tool-args": [{"name": "question",
                 "value": "What dominates bb tui cold-start latency in this project? Looking for profiling docs, prior issues, and the actual init code paths."},
                {"name": "agent-context",
                 "value": "Dossier: .brainyard/agents/research-agent/reduce-bb-tui-cold-start-under-1-second/dossier.md\nPurpose: Reduce `bb tui` cold start to <1s.\nAcceptance focus: a1, a2."}]}]
```
````

(One tool-call iteration, dispatched by CoAct's tool channel. Result inlines as a `tool-results` entry in the iteration history.)

### Iteration 3 — Log + decide (code channel)

````markdown
```clojure
;; Append the explore result to the dossier log.
(research$append-log
  :id rid :iter 2 :agent "explore-agent"
  :summary "Startup spent 1.7s in clojure.core init, 0.4s in Integrant. No prior profiling docs in repo."
  :pointers {:exploration ".brainyard/agents/explore-agent/results/20260509-181244-bb-tui-cold-start.md"})

;; Decision: enough to draft a plan. PLAN-AUTHOR next.
:next-move :plan
```
````

### Iteration 4 — PLAN (tool channel)

````markdown
```json
[{"tool-name": "plan-agent",
  "tool-args": [{"name": "question",
                 "value": "Author a plan to bring `bb tui` cold-start under 1.0s. See dossier for purpose and acceptance. Approach must enumerate candidate fixes (AOT, lazy init, deferred ns loads) with trade-offs."},
                {"name": "agent-context",
                 "value": "Dossier: .brainyard/agents/research-agent/reduce-bb-tui-cold-start-under-1-second/dossier.md\nAcceptance focus: a1, a2, a3.\nPrior artifacts:\n  - exploration: .brainyard/agents/explore-agent/results/20260509-181244-bb-tui-cold-start.md"}]}]
```
````

### Iteration 5 — Log + DECOMPOSE (code channel + tool channel — split across 2 iterations)

````markdown
```clojure
(research$append-log
  :id rid :iter 4 :agent "plan-agent"
  :summary "Plan tui-startup-latency drafted with 6 candidate items across AOT/lazy-init/deferred-ns; post-flight PASS."
  :pointers {:plan_slug    "tui-startup-latency"
             :plan_path    ".brainyard/agents/plan-agent/plans/tui-startup-latency.md"
             :plan_dossier ".brainyard/agents/plan-agent/dossiers/20260510-104503-tui-startup-latency.md"
             :pre_verdict  :go
             :post_verdict :pass})
```
````

Then iteration 6 calls todo-agent via tool channel, etc. The full 8-iteration arc looks like:

| Iter | Move | Specialist | Outcome |
|---|---|---|---|
| 1 | Bootstrap | — | Dossier created. |
| 2 | EXPLORE | explore-agent | Dominators identified: clojure.core init + Integrant. |
| 3 | (log) | — | Findings.log updated. |
| 4 | PLAN | plan-agent | `tui-startup-latency` plan drafted. |
| 5 | (log) | — | Findings.log + dossier artifacts updated. |
| 6 | DECOMPOSE | todo-agent | Todo with 6 items spawned. |
| 7 | EXECUTE | exec-agent | Items 0–2 done; latency 2.5s → 1.2s. |
| 8 | EVALUATE | eval-agent | Verdict PARTIALLY_ACHIEVED: a1 partial (1.2s vs 1.0s), a2 satisfied, a3 satisfied. |
| 9 | SYNTHESIZE + FINALIZE | — (query$llm + verdict.md write) | :partial — report + recommended follow-up. |

### Iteration 9 — Final Answer

```markdown
## Research summary — `bb tui` cold start latency

**Status:** PARTIAL — 2.5s → 1.2s (47% improvement). Target 1.0s missed by 0.2s.

**Acceptance outcome:**

| ID | Criterion | Outcome |
|---|---|---|
| a1 | REPL-mode cold start ≤ 1.0s | **partial** — currently 1.2s |
| a2 | Native binary not regressed > 0.6s | **satisfied** — 0.5s preserved |
| a3 | Profiling reproducer + writeup | **satisfied** |

**What was done:**
- Profiled cold-start; identified 1.7s in clojure.core init + 0.4s in Integrant.
  - Exploration: `.brainyard/agents/explore-agent/results/20260509-181244-tui-startup-latency.md`
- Plan body: `.brainyard/agents/plan-agent/plans/tui-startup-latency.md`
  - Plan dossier (post-flight PASS): `.brainyard/agents/plan-agent/dossiers/20260510-104503-tui-startup-latency.md`
- Todo body: `.brainyard/agents/todo-agent/todos/tui-startup-latency.md` (3/6 items done)
  - Todo dossier (post-flight PASS): `.brainyard/agents/todo-agent/dossiers/20260510-105612-tui-startup-latency.md`
- Items 0–2 (lazy-init + deferred-ns) shaved ~1.3s of the targeted 1.5s.
  - Item 1 update-agent record: `.brainyard/agents/update-agent/edits/20260510-110205-wire-lazy-init.md`
  - Exec dossier: `.brainyard/agents/exec-agent/dossiers/20260510-110131-tui-startup-latency.md`
- Eval verdict: `.brainyard/agents/eval-agent/verdicts/20260510-115412-tui-startup-latency.md`
  - Eval dossier: `.brainyard/agents/eval-agent/dossiers/20260510-115412-tui-startup-latency.md`

**Remaining gap (recommended follow-up):**
- Items 3–4 (AOT pilot on the 12 hottest namespaces) are scoped to recover the
  remaining ~0.2s. Estimated effort: half a day.
- Run `research-agent @reduce-bb-tui-cold-start-under-1-second` to resume; the
  dossier already has the next handoff prepared.

Saved research dossier: .brainyard/agents/research-agent/reduce-bb-tui-cold-start-under-1-second/
```

The user can resume with the same id to continue items 3–4 in a future turn — research-agent reads the existing dossier, sees a1 still :partial, picks up at EXECUTE (move D).

---

## 12. Anti-Pattern Catalogue

| # | Bad pattern | Why bad | Correct shape |
|---|---|---|---|
| 1 | Skip bootstrap; jump to plan-agent | No dossier → no acceptance criteria → eval-agent has nothing to score against → research thread is rudderless | Always bootstrap on iter 1 (or detect resume). |
| 2 | Inline the full dossier body in every `:agent-context` | Bloats specialist context; specialists already know how to read-file the dossier | Pass the path + a 4-line distillation. |
| 3 | Silently relax acceptance to make a verdict look better | Erodes the contract with the user | Descope only with explicit user confirmation; flag clearly in verdict. |
| 4 | Re-run explore-agent on something already in `artifacts/explore/` | Wastes a round-trip | Read-file the existing exploration; only re-explore if material has changed. |
| 5 | Call eval-agent without a current todo | Eval has nothing to evaluate; produces hallucinated verdicts | Only EVALUATE after EXECUTE has produced a fresh exec record. |
| 6 | Use `query$clone` to "delegate the research" | Clones research-agent itself = clone-self recursion | Stay flat; reach the specialists via call-tool. |
| 7 | Update findings.log in your head, not in the file | Resume on a new turn loses the context | Always `research$append-log` (or write-file with `:append true`) after every specialist call. |
| 8 | Push past iteration cap with no candidate verdict | User gets an opaque timeout | Start preparing FINALIZE at 80% budget; honest :partial > silent timeout. |
| 9 | Treat plan-agent / todo-agent as optional | "I'll just exec-agent it" — but exec-agent's PRE-FLIGHT C1/C2 require a todo dossier whose post-flight passed | If the question is research-shaped, the plan/todo authoring is part of the contract. |
| 10 | Author plan/todo/dossier files via write-file directly | Bypasses specialists' pre/post-flight gating + dossier handoff contract | Always go through the specialist via call-tool. |
| 11 | Chain `update-agent` calls during EXECUTE | Exec-agent already delegates writes to update-agent for every `:via :update-agent` item — chaining yourself duplicates the audit trail and skips exec-agent's per-item evidence map | Use move I (UPDATE) only for one-off edits that DON'T need a plan/todo arc; otherwise let exec-agent (move D) handle delegation. |
| 12 | Read sibling dossier bodies with `read-file` when the helpers would do | Wastes tokens parsing markdown the frontmatter already encoded | Use `plan$read-dossier` / `todo$read-dossier` / `exec$read-dossier` / `eval$read-dossier` / `update$read-record` — they return the frontmatter as a parsed map. |

---

## 13. Verification

| Benchmark / smoke test | Shape | What it verifies |
|---|---|---|
| Single-turn research thread | "Investigate why MCP box server is unreachable and propose a fix." | Full A→B→C→D→E→H arc; dossier complete; verdict :achieved. |
| Resume across turns | Run a research thread, kill TUI mid-flight, re-invoke with `@<id>`. | Dossier reload; agent picks up where it left off; no duplicate explore calls. |
| Acceptance ambiguity | Vague question ("make startup faster"). | Agent goes to CLARIFY (G) before any specialist call. |
| Eval-driven re-plan | Force eval-agent to return NOT_ACHIEVED. | Agent goes to B (re-plan) or C (re-decompose) per the verdict's recommended follow-up. |
| Iteration-cap finalize | Force a long thread that won't converge. | At 80% budget the agent surfaces :partial with what it has, NOT a runaway. |
| `:research-mode :benchmark` | Run an autoresearch program file through research-agent's benchmark mode. | Hill-climbing loop runs; results comparable to autoresearch baseline. |
| Cross-agent context propagation | Inspect the `:agent-context` strings passed to each specialist. | Every call carries dossier path; acceptance focus; relevant prior artifacts. |
| Hard-rule enforcement | Try to invoke `query$clone` from inside the agent. | Tool-not-found / refusal; the curated roster excludes it. |
| Direct plan/todo/dossier write attempt | Try to `write-file` to `.brainyard/agents/plan-agent/plans/<slug>.md` or `.brainyard/<agent>/dossiers/…` directly. | Caught by the instruction (Hard Rule 2). Soft enforcement; optional follow-up is a `:agent.tool-use/pre` hook scoped to those paths. |
| Sibling dossier read via helper | Inspect tool-call log for `plan$read-dossier` / `exec$read-dossier` etc. | The cherry-picked read-helpers are bound; their use should dominate `read-file` on sibling dossier bodies once the LLM gets the pattern. |
| Eval-recommendation-driven re-move | Eval returns NOT_ACHIEVED with `score.recommendations` naming todo-agent. | Next iteration is move C (DECOMPOSE), not a guess. Verifies heuristics 6/7/8 use the structured recommendation. |

Per-iteration mulog signals to add (mirroring `::rlm.*` and `::explore.*`):

- `::research.bootstrap` — `{:id … :acceptance-count N}`
- `::research.move` — `{:iter N :move :explore|:plan|:decompose|:execute|:evaluate|:synthesize|:clarify|:finalize :rationale "<short>"}`
- `::research.handoff` — `{:agent <name> :dossier-path … :acceptance-focus [ids]}`
- `::research.acceptance-flip` — `{:criterion-id "a1" :from :open :to :partial}`
- `::research.terminate` — `{:status :achieved|:partial|:abandoned :iterations N}`

These are `mulog/log` calls in the helpers (§8) — no agent-loop changes required.

---

## 14. Migration Plan: Retiring `autoresearch`

### Phase 0 — Land `research-agent`

- New `research_agent.clj` registered.
- New optional `research.clj` helpers namespace.
- New `.brainyard/agents/research-agent/` directory layout + `INDEX.md` template.
- Tests: smoke (registration), bootstrap (purpose/acceptance/direction files written), resume (re-invoke with same question reuses directory), termination (verdict.md written, INDEX.md appended).
- Zero changes to `autoresearch/`.

### Phase 1 — Promote `research-agent`

- Default agent for "research" intents in the dispatcher rules. `autoresearch` remains available, including its `research` alias (which currently points at autoresearch). Add a `:research-as-research-agent` feature flag (default `true`) that swaps the alias.
- `bb tui agents` documents `research-agent` as the recommended choice; `autoresearch` carries an "internal benchmarking tool" qualifier.
- Benchmark research-agent against autoresearch on the existing `.brainyard/autoresearch/research-program.edn` corpus, run via research-agent's benchmark mode (§10). Acceptance: research-agent's pass-rate is within ±5% of autoresearch's on the same corpus, with average iterations comparable. (Research-agent should be more iteration-efficient on user-facing single-question turns; benchmark mode parity is what matters here.)

### Phase 2 — Soft-deprecate `autoresearch`

- One-time deprecation log on autoresearch invocation: "autoresearch is deprecated; for end-to-end research use research-agent. For hill-climbing benchmarks use `(call-tool \"research-agent\" {:agent-context \"mode=benchmark\"})`."
- Move `autoresearch/scoring.clj` and `autoresearch/format.clj` into a shared library namespace (or expose their public fns through the research namespace) so research-agent's benchmark mode no longer depends on the autoresearch agent's wiring.
- Docs in `docs/agent-tui-app/autoresearch.md` updated to point at research-agent for end-to-end use; updated to describe benchmark mode for evaluation.
- The `research` alias points at research-agent.

### Phase 3 — Hard-deprecate

- Remove `components/agent/src/ai/brainyard/agent/autoresearch/` (the BT, the orchestrator, the defagent registration). Keep only the shared library bits (scoring, format) under a stable namespace.
- Remove `bases` references to autoresearch, including `bb tui autoresearch$*` shortcuts.
- Update tests; rename to research-agent.
- The `.brainyard/autoresearch/` directory in user repos is left alone — research-agent does NOT migrate or read from it. Benchmark runs going forward land in `.brainyard/agents/research-agent/benchmark-runs/`.
- Release note: "autoresearch removed in vX.Y. End-to-end: research-agent. Benchmark: research-agent in benchmark mode."

### Phase Acceptance Gates

| Phase → Phase | Gate |
|---|---|
| 0 → 1 | All research-agent tests green; smoke run on a real multi-specialist research question succeeds. |
| 1 → 2 | Benchmark-mode parity: research-agent ±5% of autoresearch on the existing program; 2+ weeks of TUI runtime with no escalation tickets tagged `agent:research`. |
| 2 → 3 | One minor release shipped with the deprecation log; no callers still pinning autoresearch's BT directly. |

The phases sequence; there is no fixed schedule. Treat each gate as a hard prerequisite.

---

## 15. Files Summary

| File | What changes |
|---|---|
| `components/agent/src/ai/brainyard/agent/common/research_agent.clj` | NEW — `instruction`, `tool-context`, `defagent research-agent` mirroring `explore-agent` shape; uses `coact/run-coact-derived` with `:max-iterations 30` default. |
| `components/agent/src/ai/brainyard/agent/common/research.clj` (optional) | NEW — `research$id`, `research$bootstrap`, `research$append-log`, `research$update-status`, `research$summarize-log`, `research$write-verdict`, `research$index-append`, `research$resume?` as `defcommand`s. |
| `components/agent/test/ai/brainyard/agent/research_agent_test.clj` | NEW — registration smoke, bootstrap, resume detection, acceptance-flip integrity, INDEX.md append-only. |
| `.brainyard/agents/research-agent/README.md` | NEW (templated by helpers on first write) — directory layout cheat-sheet. |
| `bases/agent-tui` / `bases/agent-web` | NO CHANGES at Phase 0/1. Update at Phase 3 to drop autoresearch references. |
| `bb.edn` | OPTIONAL — `repl:research` task. |
| `docs/research-agent-design.md` | THIS FILE. |
| `components/agent/src/ai/brainyard/agent/autoresearch/` | TOUCHED only at Phases 2 and 3 (deprecation log, then deletion of BT/orchestrator while keeping library bits). |
| `components/agent/src/ai/brainyard/agent/common/coact_agent.clj` | NO CHANGES. |

The substrate (CoAct BT, sandbox, DSPy signature) is not touched. The whole feature ships as one new agent file plus an optional helpers file, identical in shape to `explore-agent` and `rlm-agent`.

---

## 16. Open Questions

1. **Should the dossier directory be created lazily?** Right now bootstrap is on iteration 1 unconditionally. For a question that turns out to be a single specialist call ("just plan this"), creating a full dossier directory feels heavy. Suggestion: defer directory creation until the second specialist call, OR until the LLM explicitly decides the question is research-shaped. Trade-off: harder to resume mid-flight if the directory hasn't been created yet.
2. **Should research-agent be allowed to call rlm-agent?** A research thread that hits "summarize 200 log files" is genuinely MapReduce-shaped. Reaching for rlm-agent would extend the playbook to data-heavy work. Currently the roster is silent on rlm-agent (it's reachable via `call-tool` like any other defagent). The question is whether to *teach* the agent to do this in the instruction. Suggestion: yes, as a sixth specialist mention in §6 — but only after rlm-agent itself ships and stabilizes.
3. **Acceptance criteria as Malli schema?** The current `acceptance` is a free-form list of `{id, text, status}`. For benchmark mode, mapping onto autoresearch's `:expected-patterns` + `:acceptance-criteria` would be cleaner with a proper schema. Worth doing as part of Phase 1.
4. **Should research-agent ever auto-confirm a descope?** Hard rule 3 says no — descope requires the user. But for tightly-scoped acceptance criteria (e.g., a3 "writeup exists" trivially satisfied by a markdown file), the friction may be unnecessary. Suggestion: keep the rule strict; relax via runtime config for power users only.
5. **Cost-aware sub-LM defaults?** Like rlm-agent's haiku-sub-LM recommendation, research-agent's `query$llm` synthesis calls could default to a cheaper sub-model. Worth measuring after Phase 0.
6. **Terminal verdict.md vs. final answer drift.** What if the agent's `:answer` says :partial but `verdict.md` says :achieved (e.g., because of a buggy helper)? Suggestion: make `verdict.md` the source of truth; the agent's answer is *derived* from verdict.md after it's written. Unify in Phase 1.
