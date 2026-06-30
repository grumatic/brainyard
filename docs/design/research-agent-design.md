# Research-Agent — LLM-Driven Multi-Specialist Research Loop (CoAct-derived)

> **Status:** Shipped. Four-agent pipeline + dossier handoff shipped (2026-05);
> the lightweight authoring redesign shipped (2026-06). This doc is the as-built
> reference — the former `research-agent-lightweight-redesign.md` has been folded
> in here and removed.
>
> **As-built (verify against `common/research_agent.clj`, `common/research.clj`):**
> - **Structured-authoring helpers are retired.** `research$bootstrap` (with its
>   `:acceptance [{:id :text :status}]` vector-of-maps), `research$update-status`,
>   `research$write-verdict`'s frontmatter emission, `research$append-log`, and
>   `research$index-append` are **gone**. The agent now `bash mkdir -p`s the
>   dossier dir and `write-file`s `purpose.md` / `acceptance.md` / `direction.md`
>   / `dossier.md` / `verdict.md` directly from templates; appends `findings.log`
>   and `INDEX.md` with `write-file :append`. Only three READ/DERIVE seams
>   survive in `research.clj`: `research$id`, `research$resume?`,
>   `research$verdict-outcome` (§8).
> - **research-agent already binds `write-file` / `update-file`.** The file tools
>   are bound with **no `remove` clause**, so the move to direct authoring needed
>   **no roster change** — only the instruction + the shrunk helpers namespace
>   changed (§5, §10).
> - **The move state machine stays LLM judgment.** Orchestration — pick a move
>   (EXPLORE / PLAN-AUTHOR / DECOMPOSE / EXECUTE / EVALUATE / SYNTHESIZE / CLARIFY
>   / FINALIZE / UPDATE), dispatch the specialist by **direct kebab-case** name,
>   read its dossier to decide the next move — is untouched (§6, §7). Specialists
>   are reached by direct `(plan-agent {…})` / `(exec-agent {…})` calls, **not**
>   `call-tool` (still bound for generic registry access, but not the dispatch
>   path).
> - **Acceptance tracking reuses the todo substrate.** Criteria-with-status is a
>   markdown **checklist** (`- [ ] a1 (open) — …`), flipped index-free by stable
>   id `aN` via `update-file`, parsed back by the read seam — the same substrate
>   the todo redesign installs in the base agents (§5).
> - **An `:agent.ask/finalize` auto-finalize hook** derives the outcome, writes
>   `verdict.md` + appends `INDEX.md`, and injects an absent
>   `Saved research dossier:` line when the LLM skips the FINALIZE step (common on
>   smaller models). Strict trigger: only fires when the dossier exists AND no
>   criterion is `:open` AND `verdict.md` doesn't already exist (§10.1).
> - `autoresearch` is **gone** (§14): no
>   `components/agent/src/ai/brainyard/agent/autoresearch/`, no
>   `docs/AUTORESEARCH.md`. Benchmark mode (§10) was **never implemented** — the
>   shipped Hard Rule 7 says hill-climbing is "planned for a later milestone".
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/research_agent.clj`,
> `components/agent/src/ai/brainyard/agent/common/research.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Supersedes:** `autoresearch` (deleted)
> **Related reading:** `docs/CoAct.md`, `docs/design/explore-agent-design.md`,
> `docs/design/rlm-agent-design.md`, `docs/design/plan-agent-design.md`,
> `docs/design/todo-agent-design.md`, `docs/design/exec-agent-design.md`,
> `docs/design/eval-agent-design.md`, `docs/design/edit-agent-design.md`,
> `docs/design/agent-lightweight-redesign-synthesis.md`

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
3. **Reaches the six specialists by direct kebab-case dispatch** — `(explore-agent {...})`, `(plan-agent {...})`, `(todo-agent {...})`, `(exec-agent {...})`, `(eval-agent {...})`, `(edit-agent {...})`. Every defagent registers in the same tool registry and is auto-bound as a callable sandbox fn, so dispatch is flat. (`call-tool` is still bound for generic registry access, but is not the specialist dispatch path.)
4. **Threads the dossier path through `:agent-context`** so each specialist receives the same purpose/direction/acceptance — solving defect 1 without changing the specialists' contracts.
5. **Authors the dossier as markdown directly.** Bootstrap `bash mkdir -p`s the dir and `write-file`s `purpose.md` / `acceptance.md` / `direction.md` / `dossier.md` from templates; the verdict from a template. There is no structured-construction helper chain — orchestration is judgment (which the LLM owns) and persistence is prose (which the LLM writes), so the only things kept mechanical are the deterministic resume key, the resume/acceptance parser, and the verdict-outcome derivation + `:achieved` guard (§5, §8).
6. **Stops when the LLM judges the goal achieved or definitively unreachable**, bounded by an iteration cap. Hill-climbing scoring (the autoresearch hallmark) is an opt-in mode, removed from the default user-facing loop — and not yet implemented (§10).
7. **Inherits CoAct's full BT, sandbox, router, accumulator** — no substrate changes. Whole feature is one agent file plus a slim read/derive helpers namespace, mirroring `rlm-agent` / `explore-agent`.
8. **Retired autoresearch.** The staged migration (§14) completed — `autoresearch/` is gone.

---

## 2. Design Principles

1. **One coherent loop, not a hard-wired pipeline.** The pipeline specialists (plan / todo / exec / eval / update) plus explore-agent become *callable subroutines*, not stages of a fixed sequence. The research-agent is the only thing that sees the whole research arc.
2. **Durable research dossier.** Cross-agent state lives in a directory of markdown + EDN files, not in slugs or transient `:agent-context` strings. Every specialist call writes into the dossier; every subsequent specialist call reads from it.
3. **The LLM owns sequencing — orchestration is judgment, not mechanism.** Should we re-plan? Decompose more? Re-explore? Mark eval criteria as descoped? These are reasoning calls, not BT branches or structured tool calls. The instruction names the decision points; the LLM picks the next move and dispatches the specialist by name. This is the heart of the agent and the redesign left it **untouched**.
4. **Authoring is templated markdown; reading stays typed.** The dossier files are written directly from templates (no vector-of-maps construction, no frontmatter-emitting helper); the machine-readable bits survive as *frontmatter/checklist the model writes* plus a *parser that reads them back*. This is the cross-agent principle in `agent-lightweight-redesign-synthesis.md`: separate *judgment* (authoring prose, choosing moves) from *mechanism* (parsing, derivation, the verdict guard). For an orchestrator, "reading" is the whole job, so the typed read seams are sacrosanct.
5. **Small tool registry.** The agent reaches the world through (a) the six specialists by direct kebab-case dispatch (explore / plan / todo / exec / eval / update), (b) basic CoAct file/shell/sandbox primitives for dossier maintenance — already bound, no `remove` clause, (c) read-only dossier-helper cherry-picks from each sibling (`plan$read-dossier`, `todo$read-dossier`, `exec$read-dossier`, `eval$read-verdict`, `edit$read-record`), (d) `query$llm` for synthesis, (e) the three surviving `research$*` read/derive seams. That's it.
6. **Acceptance criteria are first-class — and a checklist.** They are written into the dossier on the first iteration as a markdown checklist (the shared todo substrate, §5), threaded through every specialist call, flipped index-free by stable id `aN`, and re-evaluated explicitly before the agent considers terminating. Research-agent's acceptance tracking is an *instance* of the todo substrate — one fewer bespoke mechanism in the codebase.
7. **Bounded but generous iteration cap.** Default 30 iterations (vs. CoAct's 20). Research workflows have legitimate reason to take more steps than a single-question agent.
8. **Honest termination, guarded.** The agent can finish in one of three states: `:achieved`, `:partial`, or `:abandoned` — and each has explicit conditions. The `:achieved` guard (`research$verdict-outcome` refuses `:achieved` unless every criterion is `:satisfied`/`:descoped`) is the one load-bearing validator kept from the old `write-verdict`, now a read-side check the model calls before writing `verdict.md`. No silent timeouts that look like answers.
9. **No clone-self recursion.** `query$clone` is excluded — calling research-agent from inside research-agent is the depth-2 anti-pattern. Calling *other* agents (explore / plan / todo / exec / eval / update) by direct kebab-case dispatch is flat and IS the design.
10. **Resumable.** The dossier is the only state of record. A research run interrupted mid-flight (TUI exit, max iterations, user pause) can be resumed by passing the research-id on a later turn and reading the dossier — no in-memory state lost.
11. **Optional benchmark mode (not yet built).** The autoresearch hill-climbing score (LLM judge × 0.5 + pattern × 0.3 + efficiency × 0.2) is reserved as an *opt-in* observability layer, not a control mechanism — planned for a later milestone (§10).

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
  │                     delegates writes to edit-agent; emits dossier)
  ├─ eval-agent        (verdict against plan acceptance; reads three
  │                     upstream dossiers; drills via edit$read-record;
  │                     emits verdict body + dossier)
  ├─ edit-agent      (safe single-file edits; probe→apply→verify→
  │                     rollback-on-fail; emits edit record)
  └─ research-agent    (orchestrates explore/plan/todo/exec/eval/update
                        via direct kebab-case dispatch; threads dossier paths between them)
```

| Question shape | Use | Why |
|---|---|---|
| "Find me where X lives" | explore-agent | Single-surface discovery. |
| "Rename foo to bar in `src/x.clj`" | edit-agent | Safe single-file edit; no plan/todo warranted. |
| "Draft a plan for Y" | plan-agent | Plan authoring, no execution. |
| "Spawn a todo from the existing plan Z" | todo-agent | Items decomposition from an existing plan dossier. |
| "Drive the existing todo Z to completion" | exec-agent | Already plan + todo dossiers exist. |
| "Score whether todo Z met plan Z's acceptance" | eval-agent | Pure verdict; reads upstream dossiers. |
| "Research question X end-to-end: figure out the right approach, plan it, execute it, evaluate it." | **research-agent** | The orchestration layer threading the dossiers. |
| "Run a test suite to score model X on N questions" | research-agent benchmark mode (planned; §10) | Hill-climbing eval, not a user research turn. |

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
            └── edits/<ts>-<slug>.md                 → ../../../edit-agent/edits/<ts>-<slug>.md
```

The `artifacts/` directory's symlink layout mirrors the per-agent storage that landed in the four-agent redesign. Legacy `.brainyard/plans/` and `.brainyard/todos/` are dual-read for one release; symlinks to legacy locations can co-exist with the new ones during the migration window.

**`<research-id>`** is a kebab-case stable identifier derived from the user's research question (same scheme explore-agent uses for slugs). Re-asking the same research question re-uses the same id; the dossier accumulates across turns. The id is also the name a user types if they want to resume: `bb tui run -a research-agent -- "@<research-id>"`.

### 4.2 `dossier.md` — The Cross-Agent Context Carrier

The single document every specialist reads. YAML frontmatter + body. **As-built,
acceptance criteria do NOT live inline in this frontmatter** — they live in a
sibling `acceptance.md` markdown checklist (§5), the shared todo substrate, so
they can be flipped index-free with `update-file`. The illustrative block below
shows the acceptance criteria *in situ* for readability; the real
`dossier.md` carries `purpose` / `direction` / `artifacts` / `status` /
`last_iteration` and points at `acceptance.md`. (The pre-redesign inline
`acceptance:` block is still dual-read by `research$resume?` for old threads.)

```markdown
---
research_id: improve-tui-startup-latency
created: 2026-05-09T18:12:44Z
last_iteration: 7
status: in_progress           # in_progress | achieved | partial | abandoned
purpose: >
  Reduce `bb tui` cold-start latency from ~2.5s to under 1.0s without
  regressing the agent-tui-app native binary's startup (currently 0.5s).
# acceptance criteria live in acceptance.md (the §5 checklist); shown here
# inline for readability only:
#   - [ ] a1 (open) — REPL-mode `bb tui` cold start <= 1.0s on M-series Mac
#   - [ ] a2 (open) — Native binary cold start does NOT regress beyond 0.6s
#   - [ ] a3 (open) — Documented profiling reproducer + root-cause writeup
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
  edits:          [.brainyard/agents/edit-agent/edits/20260510-110205-wire-lazy-init.md]
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
  spawned with per-item :tags routing (3 :edit-agent, 2 :bash, 1
  :manual). Source: `artifacts/todo_dossiers/20260510-105612-tui-startup-latency.md`.
- Iteration 5 (exec): exec-agent dossier post.verdict=:pass. Items 0-2
  advanced. Item 1 (lazy init via edit-agent record
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
| `acceptance` | markdown checklist in `acceptance.md` (`- [ ] aN (status) — text`) | First-class; status flipped index-free by stable id `aN` via `update-file` (the shared todo substrate, §5). Parsed back by `research$resume?` / `research$verdict-outcome`. Legacy inline-frontmatter form is dual-read. |
| `direction` | vector of strings | Stratagem; revisions logged in body. |
| `artifacts` | map | Pointers to specialists' outputs. Keys: `explorations` (vec), `plan_slug`, `plan_path`, `plan_dossier`, `todo_slug`, `todo_path`, `todo_dossier`, `exec_dossiers` (vec), `verdict_path`, `eval_dossiers` (vec), `edits` (vec). All paths are repo-relative; the dossier helpers (`plan$read-dossier` etc.) are bound here for cheap frontmatter parse. |
| `status` | enum | Current research-agent state machine. |
| `last_iteration` | int | For resume sanity-check. |
| `calls_log` | string | Path to `findings.log`. |

The acceptance checklist's per-criterion status is the bookkeeping the LLM updates (via an index-free `update-file` on the `aN` line) as criteria are demonstrably met or descoped. eval-agent's verdict feeds these flips on every EVALUATE.

### 4.3 `findings.log` — Append-Only Call Log (NDJSON)

One line per specialist invocation. Cheap for the agent to read selectively (jq, grep, or `read-file :lines [N M]`).

```ndjson
{"iter":2,"agent":"explore-agent","summary":"startup dominated by clojure.core init","pointers":{"exploration_path":".brainyard/agents/explore-agent/results/20260509-181244-tui-startup-latency.md"}}
{"iter":3,"agent":"plan-agent","summary":"3 candidate fixes proposed; post-flight PASS","pointers":{"plan_path":".brainyard/agents/plan-agent/plans/tui-startup-latency.md","plan_dossier":".brainyard/agents/plan-agent/dossiers/20260510-104503-tui-startup-latency.md","pre_verdict":"go","post_verdict":"pass"}}
{"iter":4,"agent":"todo-agent","summary":"6 items spawned with :tags routing","pointers":{"todo_path":".brainyard/agents/todo-agent/todos/tui-startup-latency.md","todo_dossier":".brainyard/agents/todo-agent/dossiers/20260510-105612-tui-startup-latency.md","pre_verdict":"go","post_verdict":"pass"}}
{"iter":5,"agent":"exec-agent","summary":"items 0-2 advanced; 2.5s -> 1.2s; item 1 delegated to edit-agent","pointers":{"exec_dossier":".brainyard/agents/exec-agent/dossiers/20260510-110131-tui-startup-latency.md","items_advanced":[0,1,2],"items_pending":[3,4,5],"post_verdict":"pass"}}
{"iter":5.1,"agent":"edit-agent","summary":"lazy init wired in src/agent_tui_app/main.clj — exec-agent delegated","pointers":{"update_record":".brainyard/agents/edit-agent/edits/20260510-110205-wire-lazy-init.md","rollback":"git checkout -- src/agent_tui_app/main.clj"}}
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
  [ai.brainyard.agent.common.edit      :as edit-helpers]
  [ai.brainyard.agent.common.research  :as research]
  [ai.brainyard.agent.task.commands    :as task-cmds])

(def research-tools
  (vec (distinct
         (concat
           ;; Filesystem — for research-agent's OWN dossier dir. Bound with
           ;; NO `remove` clause, so write-file/update-file author the dossier
           ;; markdown directly (no construction helpers). Hard Rule 2 forbids
           ;; writes to sibling dossier dirs.
           common-tools/file-tools          ; read-file, write-file, update-file, grep, fetch-url
           common-tools/shell-tools         ; bash (mkdir -p, ls, ln -s for artifacts/)

           ;; Web — direct one-off lookups; explore-agent for non-trivial discovery
           common-tools/web-tools           ; web-search, fetch-url

           ;; Read-only sibling dossier helpers — cherry-picked. Let
           ;; research-agent parse upstream dossier frontmatter cheaply
           ;; for data-driven move decisions. Write-side helpers are NOT
           ;; bound — sibling writes go through the specialist directly.
           [#'plan-helpers/plan$read-dossier
            #'todo-helpers/todo$read-dossier
            #'exec-helpers/exec$read-dossier
            #'eval-helpers/eval$read-verdict
            #'edit-helpers/edit$read-record]

           ;; Sub-LLM synthesis (flat only)
           [#'common-cmds/query$llm]        ; intentionally excludes #'query$clone

           ;; Bookkeeping
           common-tools/bootstrap-tools     ; list-tools, get-tool-info, search
           common-tools/invocation-tools    ; call-tool (generic registry access)

           ;; Background jobs (rarely needed; specialists own their long ops)
           task-cmds/task-commands

           ;; Runtime config — :max-iterations tuning
           common-cmds/runtime-commands

           ;; research$* READ/DERIVE seams ONLY — id / resume? / verdict-outcome.
           ;; The structured-construction helpers are RETIRED; the dossier
           ;; markdown is authored via the already-bound file tools.
           research/research-helpers))))

;; The SIX specialist agents (explore / plan / todo / exec / eval /
;; update) are reached by direct kebab-case dispatch — they self-register
;; in !tool-defs through their own defagent forms. They do NOT need to be
;; listed in :agent-tools to be reachable; they ARE in the registry.
```

The six specialist agents (`explore-agent`, `plan-agent`, `todo-agent`, `exec-agent`, `eval-agent`, `edit-agent`) are reached via direct kebab-case dispatch — `(explore-agent {...})`, `(plan-agent {...})`, etc. — from a clojure fence, or via the `tool-calls` channel. The instruction's job is to *teach the LLM when to reach for each*. (`call-tool` remains bound for generic registry access but is not the specialist dispatch path.)

What is *deliberately bound*:

| Bound | Why |
|---|---|
| `plan$read-dossier`, `todo$read-dossier`, `exec$read-dossier`, `eval$read-verdict`, `edit$read-record` | Read-only cherry-picks. Let research-agent parse upstream sibling dossier frontmatter cheaply (~700 bytes/read) for data-driven move decisions — most importantly, `eval$read-verdict` returns `score.recommendations` which drives heuristics 6/7/8 (re-plan / re-decompose / resume). Write-side helpers from the same vectors are NOT bound. |
| `research$id`, `research$resume?`, `research$verdict-outcome` | The three surviving READ/DERIVE seams — a deterministic resume key, the resume/acceptance-checklist parser, and the verdict-outcome derivation + `:achieved` guard. Everything authoring-shaped (`bootstrap`/`update-status`/`write-verdict`/`append-log`/`index-append`) is retired (§8, §10). |

What is *deliberately omitted*:

| Excluded | Reason |
|---|---|
| `query$clone` | Clones research-agent itself = clone-self recursion. Forbidden. |
| Direct `plan$*` / `todo$*` / `skills$*` / `mcp$*` write commands | Routed through the specialist agents. Reaching for them directly bypasses pre/post-flight gating + the dossier handoff contract. |
| `plan$dossier-write`, `todo$dossier-write`, `exec$dossier-write`, `eval$dossier-write`, `eval$verdict-write`, `edit$apply`, `edit$write` | Sibling dossier/verdict/edit writes go through their specialists. The read-only cherry-pick is a deliberate asymmetry — research-agent reads to decide; the specialist writes once it has been called. |

What is *bound directly* but should be reached for sparingly:

| Bound | When to use directly | When to route to specialist |
|---|---|---|
| `web-search`, `fetch-url` | Quick one-off lookup mid-loop where the result is consumed immediately and not worth a full exploration artifact (e.g. confirming a tool's CLI flag, fetching a known URL the user already cited). | Multi-source discovery, ambiguous question, or any lookup whose result will be cited in the verdict — call `explore-agent` so the audit trail lives in `.brainyard/agents/explore-agent/results/` and is greppable across runs. |
| `read-file`, `grep` on sibling dossier files | Inspecting a SPECIFIC paragraph or hunk in a sibling dossier body when the frontmatter doesn't carry it. | Use the `*$read-dossier` cherry-picks first — they give parsed frontmatter for free. |

The shrinkage of `plan$*` / `todo$*` / `skills$*` / `query$clone` write paths is intentional: research-agent's tool discipline is "use the specialist for the specialist's domain." The instruction enforces this; the roster makes the cheap-shortcut path conspicuously unavailable.

---

## 6. Instruction (System Prompt Body)

Layered on top of `coact-agent`'s instruction by `run-coact-derived`. The
authoritative text lives in `research_agent.clj` under `(def ^:private
instruction …)`; this section captures the section structure + the load-bearing
contracts as-built. The redesign changed only the *persistence* steps — the
six-specialist roster, the move state machine, the dossier-threading discipline,
and the hard rules are intact.

The instruction's sections (in order):

1. **THE SIX SPECIALISTS** — `explore` / `plan` / `todo` / `exec` / `eval` /
   `edit`, each reached by **direct kebab-case dispatch** (`(plan-agent {…})`),
   flat and non-recursive. Each (except explore + edit) ships a pre/post-flight
   gated pipeline whose `Saved dossier:` carries machine-readable frontmatter —
   read it instead of re-parsing prose. Invocation:
   `(<name> {:question "…" :agent-context "<dossier path + pointers>"})`.
2. **TURN 1 — DOSSIER BOOTSTRAP** — compute the id and probe for resume
   (`research$id` / `research$resume?` — read seams kept), then author the
   dossier files **directly** with `bash mkdir -p` + `write-file`. There is **no
   construction helper** — the model writes `purpose.md`, the acceptance
   CHECKLIST (`acceptance.md`), `direction.md`, and `dossier.md` from templates.
   If success is vague, surface an "Open questions" list in `:answer` before
   reaching any specialist (the single most common failure mode).
3. **STATE MACHINE** — every post-bootstrap iteration picks ONE move; no fixed
   order. The nine moves: **A EXPLORE / B PLAN-AUTHOR / C DECOMPOSE / D EXECUTE /
   E EVALUATE / F SYNTHESIZE / G CLARIFY / H FINALIZE / I UPDATE**. Move **I
   (UPDATE)** dispatches `(edit-agent {…})` for a one-off single-file edit that
   doesn't warrant a plan/todo arc; for multi-edit work use B→C→D so exec-agent
   chains edit-agent per item.
4. **DECISION HEURISTICS** — typical (not required) sequences. Heuristics 6/7/8
   are **data-driven**: eval-agent's verdict `score.recommendations` names the
   next agent per criterion (read via `eval$read-verdict`), not inferred from the
   answer text.
5. **DOSSIER UPDATE DISCIPLINE** (after every specialist call) — (1) `write-file
   :append` one `findings.log` line carrying the `Saved …:` paths the specialist
   just emitted; (2) flip changed acceptance statuses **index-free** in
   `acceptance.md` by stable id `aN` via `update-file` (the shared todo
   substrate); (3) append a `## Direction revision (iter N)` block if direction
   changed; (4) `update-file` the artifacts pointers in `dossier.md` frontmatter.
6. **PASSING DOSSIER TO SPECIALISTS** — every call's `:agent-context` MUST lead
   with the upstream sibling `Saved dossier: <path>` line (the specialist's
   pre-flight C1 greps for `^Saved dossier: `) plus the research dossier path,
   purpose, acceptance focus, and prior-artifact pointers. Never inline the full
   dossier body.
7. **TERMINATION RULES — strict 4-step finalize, in order:**
   (1) flip every criterion's status in `acceptance.md` to reflect reality;
   (2) `(research$verdict-outcome :id rid)` to derive the outcome + **enforce the
   `:achieved` guard** (if it flags blockers while you intend `:achieved`, FIX
   the dossier — never downgrade the verdict to hide the error);
   (3) `write-file` `verdict.md` directly from the VERDICT TEMPLATE using
   `(:outcome vo)` / `(:acceptance-outcome vo)`;
   (4) `write-file :append` one `INDEX.md` line.
   Then populate `:answer` with a report derived from `verdict.md`, ending with
   the exact contract line `Saved research dossier: .brainyard/agents/research-agent/<id>/`.
   Do **not** emit the contract line if `verdict.md` couldn't be written.
8. **HARD RULES** — (1) STAY FLAT, no `query$clone`; cross-specialist dispatch is
   the direct kebab-case call. (2) NO direct writes to sibling-agent storage
   (`plan-agent/plans`, `todo-agent/todos`, the `*/dossiers/`, `eval-agent/verdicts`,
   `edit-agent/edits`) — read them freely (prefer the typed readers), invoke the
   specialist to create new content. (3) acceptance is FROZEN once confirmed,
   except a user-confirmed descope. (4) every call's `:agent-context` carries the
   dossier path. (5) the dossier is the only durable cross-iteration state.
   (6) iteration budget 30 (override via `agent-runtime$config`); prep FINALIZE at
   80%. (7) NO benchmarking/scoring in the default loop — hill-climbing is an
   opt-in mode **planned for a later milestone** (§10). (8) CITE EVERYTHING.
9. **RESUMING A RESEARCH THREAD** — re-invoke with `@<research-id>`: read
   `dossier.md` frontmatter + the `acceptance.md` checklist + last findings;
   reconstruct open criteria; pick the next move without re-bootstrapping or
   re-exploring `artifacts/`; surface a one-paragraph "where we are" in
   `:thought`. A genuinely-broken resume is a CLARIFY (G).

---

## 7. Tool-Context (How to Use the Bound Tools)

```text
## Research Tools — six specialists + dossier substrate

### Specialists (direct kebab-case dispatch — NOT bound directly)
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
                   :tags.via — :edit-agent items delegate to
                   edit-agent automatically. Returns:
                     `Saved dossier: .brainyard/agents/exec-agent/dossiers/<ts>-<slug>.md`
                     `Done:` (all items advanced) | `Manual:` (item N
                     surfaced for user) | `Hold:` (rubric failure)
                   Dossier carries `execute.evidence` (per-item record
                   map) and `post.acceptance_progress`.

- eval-agent     → score executed todo vs plan acceptance (reads three
                   upstream dossiers — plan + todo + exec — first such
                   agent in the stack). Drills criterion → item →
                   evidence → diff via edit$read-record. Returns:
                     `Saved verdict: .brainyard/agents/eval-agent/verdicts/<ts>-<slug>.md`
                     `Saved dossier: .brainyard/agents/eval-agent/dossiers/<ts>-<slug>.md`
                     `Verdict: ACHIEVED|PARTIALLY_ACHIEVED|NOT_ACHIEVED
                       (confidence: high|medium|low)`
                   Dossier `score.recommendations` names the next agent
                   per criterion — read it to drive heuristics 6/7/8.

- edit-agent   → safe single-file edit (probe → apply → verify →
                   persist → rollback-on-fail). Use directly via UPDATE
                   move (I) when the user's question reduces to one
                   well-bounded edit. Returns:
                     `Saved edit: .brainyard/agents/edit-agent/edits/<ts>-<slug>.md`
                     `Rollback:   <git checkout|rm command>`
                   (or `Rolled back: <reason>` on verify failure).

Invocation pattern (all six identical; sibling dossier path goes FIRST
in :agent-context as `Saved dossier: <path>` so the specialist's
pre-flight C1 finds it):
    (<agent-name>
      {:question      "<directed sub-question>"
       :agent-context "Saved dossier: <upstream sibling path>\n\nResearch dossier: <research path>\nAcceptance focus: <ids>\n…"})

### Dossier substrate (your direct work surface)
- read-file      -- Read dossier.md / acceptance.md / findings.log / artifacts.
- write-file     -- Author dossier.md / acceptance.md / verdict.md / INDEX.md.
                    USE :append true for findings.log + INDEX.md entries.
- update-file    -- Index-free single-line edit — flip one acceptance
                    criterion's status in acceptance.md by stable id
                    (`- [ ] a1 (open)` → `- [x] a1 (satisfied)`), or patch one
                    dossier.md frontmatter field, without rewriting the file.
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
- eval$read-verdict   -- Args: path. Returns eval-agent verdict
                          frontmatter — :verdict, :confidence,
                          :criteria, :recommendations.
- edit$read-record  -- Args: path. Returns an edit-agent record's
                          :verify :apply :rollback for diff-level audit.

These let you make data-driven move decisions (e.g. read eval-agent's
score.recommendations for the next move per criterion) without paying
for the full dossier body. The corresponding write helpers from each
sibling are NOT bound — research-agent invokes the specialists directly
when new sibling content is needed.

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
- list-tools, get-tool-info — generic registry access (invoke registered tools by id).
- call-tool — generic registry dispatch (NOT the specialist path; those go direct).
- task$run with :job-type :tool|:bash         — async wrapper if a specialist
                                          call is expected to take >5s
                                          (rare; specialists usually
                                          return promptly).

### Runtime config
- agent-runtime$config — view (no args) or tune (`:key`/`:value`) settings.
                 -- Tune `:max-iterations` mid-run if a specialist
                    surfaces work worth a longer arc.

## research$* helpers (auto-bound; the THREE surviving read/derive seams — see §8)
- research$id             -- Deterministic kebab id from a question (the resume key).
- research$resume?         -- Probe: does the dossier exist + its acceptance-state?
- research$verdict-outcome -- Read the acceptance checklist, derive the outcome,
                              and enforce the :achieved guard. READ-ONLY — call
                              before write-file-ing verdict.md.

AUTHORING has no helpers — write the markdown directly: BOOTSTRAP via `bash
mkdir -p` + `write-file` (purpose/acceptance-checklist/direction/dossier);
findings.log + INDEX.md via `write-file :append`; acceptance status flips via
`update-file` on the `aN` line (the shared todo substrate). The structured
constructors (`research$bootstrap`/`update-status`/`write-verdict`/`append-log`/
`index-append`) are RETIRED.

## Typical flow (no specific iteration count required)
1. iter 1 — bootstrap dossier via bash + write-file (or resume if dir exists).
2. iter 2..N — pick a state-machine move per dossier state:
   EXPLORE / PLAN-AUTHOR / DECOMPOSE / EXECUTE / EVALUATE / SYNTHESIZE /
   CLARIFY / FINALIZE / UPDATE.
3. After every specialist call: append findings.log (with the sibling
   `Saved …:` paths); flip acceptance statuses index-free in acceptance.md;
   update dossier.md artifacts frontmatter; refresh ## Findings body.
4. On termination: research$verdict-outcome → write verdict.md → append
   INDEX.md → populate :answer with markdown report + `Saved research dossier:`.
```

---

## 8. The Surviving `(research$*)` Read/Derive Seams

`research.clj` ships exactly **three** `defcommand`s (in `research-helpers`),
auto-bound in the SCI sandbox. They are the only places a machine beats the
model — everything authoring-shaped is retired and the model writes the dossier
markdown directly.

| Helper | Signature | What it does |
|---|---|---|
| `research$id` | `(research$id :question "<text>" [:max-chars 60])` → `{:slug "<id>"}` | Deterministic kebab-case id, stopwords dropped, cap 60. The resume key. |
| `research$resume?` | `(research$resume? :id …)` → `{:exists? false}` or `{:exists? true :status :keyword :last-iteration N :acceptance-state {…}}` | Cheap probe to decide bootstrap vs. resume. Reads `dossier.md` frontmatter + the `acceptance.md` checklist statuses; **dual-reads** a legacy inline-frontmatter `acceptance:` block for old threads. |
| `research$verdict-outcome` | `(research$verdict-outcome :id …)` → `{:outcome :achieved\|:partial\|:abandoned\|:in-progress :achieved-ok? bool :blockers ["aN:status" …] :acceptance-outcome {a1 "satisfied" …}}` | READ-ONLY. Parses the acceptance checklist, derives the outcome, and **enforces the `:achieved` guard** (refuses `:achieved` unless every criterion is `:satisfied`/`:descoped`). The one load-bearing bit carved out of the old `write-verdict` — called *before* `write-file`-ing `verdict.md`. |

**Retired** (do not exist anymore): `research$bootstrap` (with its
`:acceptance [{:id :text :status}]` vector-of-maps + `:direction` object
handling and five-file write), `research$update-status`,
`research$write-verdict`'s frontmatter emission, `research$append-log`, and
`research$index-append`. Acceptance editing now rides the shared **todo
substrate**'s index-free checklist edit; the dossier markdown is authored via
the already-bound `write-file` / `update-file`.

The slim helper surface keeps the prompt short because the dossier mechanics no
longer have to be inlined every iteration — the model writes the markdown it is
fluent at and reaches for the three seams only for the resume key, the
resume/acceptance parse, and the verdict-outcome derivation + guard.

### 8.1 Auto-Finalize Backstop

`research.clj` installs an `:agent.ask/finalize` hook
(`install-auto-finalize!`, self-installing at namespace load) that materializes
the terminal artifacts when the LLM emits a non-blank answer without finalizing
itself (common on smaller models). It is a **safety net**, not the primary path:

- `render-verdict-md` composes `verdict.md` (frontmatter + `## Verdict`) from the
  derived outcome and a `one-line-summary` of the answer; `append-index!` adds
  one `INDEX.md` line; the absent `Saved research dossier:` line is injected.
- **Strict trigger:** only fires when the dossier exists AND the acceptance state
  has no `:open` criterion (else the LLM is mid-flight / in CLARIFY) AND
  `verdict.md` doesn't already exist. It never retroactively bootstraps.
- Idempotent and defensive — failures are logged (`::research.auto-finalize-failed`),
  never re-thrown. Per-turn opt-out via `agent-runtime$config :key
  "research-auto-finalize" :value "false"`.

Because the trigger is observe-only on the answer, a missing `Saved research
dossier:` line does not always mean nothing persisted — the backstop may have
written it.

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

> **As-built:** **Not implemented.** Benchmark mode never shipped. The shipped Hard Rule 7 reads
> "NO benchmarking/scoring in the default loop. Hill-climbing strategies are an opt-in mode
> (planned for a later milestone)." There is no `research-benchmark-mode-action`, no `mode=benchmark`
> branch, and — since `autoresearch/` was removed — no `scoring.clj` / `format.clj` to borrow from.
> The rest of this section is retained as the original proposal.

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
(def rid (:slug (research$id :question "Reduce bb tui cold start to under 1 second")))
;; => "reduce-bb-tui-cold-start-under-1-second"
(def state (research$resume? :id rid))   ; => {:exists? false} → BOOTSTRAP

;; Author the dossier files DIRECTLY — no construction helper.
(bash {:command (str "mkdir -p .brainyard/agents/research-agent/" rid "/artifacts")})
(write-file {:path (str ".brainyard/agents/research-agent/" rid "/purpose.md")
             :content "Reduce `bb tui` cold-start latency from ~2.5s to under 1.0s without regressing the agent-tui-app native binary's startup.\n"})
;; acceptance.md — the §5 CHECKLIST (shared todo substrate; flip by stable id aN)
(write-file {:path (str ".brainyard/agents/research-agent/" rid "/acceptance.md")
             :content (str "# Acceptance — " rid "\n"
                           "- [ ] a1 (open) — REPL-mode `bb tui` cold start <= 1.0s on M-series Mac\n"
                           "- [ ] a2 (open) — Native binary cold start does NOT regress beyond 0.6s\n"
                           "- [ ] a3 (open) — Documented profiling reproducer + root-cause writeup\n")})
(write-file {:path (str ".brainyard/agents/research-agent/" rid "/direction.md")
             :content "# Direction\n- Narrow down which subsystems dominate startup\n- Evaluate fix candidates: AOT, lazy init, deferred ns loads\n- Implement the cheapest viable fix\n"})
(write-file {:path (str ".brainyard/agents/research-agent/" rid "/dossier.md")
             :content "<frontmatter (research_id/created/last_iteration/status/artifacts) + body>\n"})
```
````

### Iteration 2 — EXPLORE (code channel — direct kebab-case dispatch)

````markdown
```clojure
(explore-agent
  {:question      "What dominates bb tui cold-start latency in this project? Looking for profiling docs, prior issues, and the actual init code paths."
   :agent-context (str "Research dossier: .brainyard/agents/research-agent/" rid "/dossier.md\n"
                       "Purpose: Reduce `bb tui` cold start to <1s.\nAcceptance focus: a1, a2.")})
```
````

(Flat dispatch on the auto-bound defagent fn. Its answer ends with
`Saved exploration: <path>`, which you carry verbatim into findings.log.)

### Iteration 3 — Log + decide (code channel)

````markdown
```clojure
;; Append the explore result to findings.log — write the `Saved …:` path verbatim.
(write-file {:path (str ".brainyard/agents/research-agent/" rid "/findings.log")
             :append true
             :content "iter 2 · explore-agent · Saved exploration: .brainyard/agents/explore-agent/results/20260509-181244-bb-tui-cold-start.md · startup = 1.7s clojure.core + 0.4s Integrant\n"})

;; Decision: enough to draft a plan. PLAN-AUTHOR next.
:next-move :plan
```
````

### Iteration 4 — PLAN (code channel — direct kebab-case dispatch)

````markdown
```clojure
(plan-agent
  {:question      "Author a plan to bring `bb tui` cold-start under 1.0s. See dossier for purpose and acceptance. Approach must enumerate candidate fixes (AOT, lazy init, deferred ns loads) with trade-offs."
   :agent-context (str "Research dossier: .brainyard/agents/research-agent/" rid "/dossier.md\n"
                       "Acceptance focus: a1, a2, a3.\nPrior artifacts:\n"
                       "  - exploration: .brainyard/agents/explore-agent/results/20260509-181244-bb-tui-cold-start.md")})
```
````

### Iteration 5 — Log + DECOMPOSE (code channel + dispatch — split across 2 iterations)

````markdown
```clojure
(write-file {:path (str ".brainyard/agents/research-agent/" rid "/findings.log")
             :append true
             :content (str "iter 4 · plan-agent · Saved plan: .brainyard/agents/plan-agent/plans/tui-startup-latency.md"
                           " · Saved dossier: .brainyard/agents/plan-agent/dossiers/20260510-104503-tui-startup-latency.md"
                           " · pre:go post:pass\n")})
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
  - Item 1 edit-agent record: `.brainyard/agents/edit-agent/edits/20260510-110205-wire-lazy-init.md`
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
| 6 | Use `query$clone` to "delegate the research" | Clones research-agent itself = clone-self recursion | Stay flat; reach the specialists by direct kebab-case dispatch. |
| 7 | Update findings.log in your head, not in the file | Resume on a new turn loses the context | Always `write-file :append` one line (carrying the `Saved …:` paths) after every specialist call. |
| 8 | Push past iteration cap with no candidate verdict | User gets an opaque timeout | Start preparing FINALIZE at 80% budget; honest :partial > silent timeout. |
| 9 | Treat plan-agent / todo-agent as optional | "I'll just exec-agent it" — but exec-agent's PRE-FLIGHT C1/C2 require a todo dossier whose post-flight passed | If the question is research-shaped, the plan/todo authoring is part of the contract. |
| 10 | Author plan/todo/dossier files via write-file directly | Bypasses specialists' pre/post-flight gating + dossier handoff contract | Always go through the specialist by direct dispatch — `(plan-agent {…})`, etc. (Your OWN dossier files you DO write-file directly; only sibling-agent storage is off-limits.) |
| 11 | Chain `edit-agent` calls during EXECUTE | Exec-agent already delegates writes to edit-agent for every `:via :edit-agent` item — chaining yourself duplicates the audit trail and skips exec-agent's per-item evidence map | Use move I (UPDATE) only for one-off edits that DON'T need a plan/todo arc; otherwise let exec-agent (move D) handle delegation. |
| 12 | Read sibling dossier bodies with `read-file` when the helpers would do | Wastes tokens parsing markdown the frontmatter already encoded | Use `plan$read-dossier` / `todo$read-dossier` / `exec$read-dossier` / `eval$read-verdict` / `edit$read-record` — they return the frontmatter as a parsed map. |

---

## 13. Verification

| Benchmark / smoke test | Shape | What it verifies |
|---|---|---|
| Single-turn research thread | "Investigate why MCP box server is unreachable and propose a fix." | Full A→B→C→D→E→H arc; dossier complete; verdict :achieved. |
| Resume across turns | Run a research thread, kill TUI mid-flight, re-invoke with `@<id>`. | Dossier reload; agent picks up where it left off; no duplicate explore calls. |
| Acceptance ambiguity | Vague question ("make startup faster"). | Agent goes to CLARIFY (G) before any specialist call. |
| Eval-driven re-plan | Force eval-agent to return NOT_ACHIEVED. | Agent goes to B (re-plan) or C (re-decompose) per the verdict's recommended follow-up. |
| Iteration-cap finalize | Force a long thread that won't converge. | At 80% budget the agent surfaces :partial with what it has, NOT a runaway. |
| Bootstrap authoring | First iteration of a fresh thread. | `bash mkdir -p` + `write-file` create the dir and `purpose.md` / `acceptance.md` (checklist) / `direction.md` / `dossier.md` — no construction helper called. |
| Acceptance flip (index-free) | A criterion is met mid-thread. | `update-file` flips `- [ ] aN (open)` → `- [x] aN (satisfied)` by stable id; `research$resume?` reflects it; other criteria untouched. |
| Verdict guard | Try to FINALIZE `:achieved` while a criterion is `:open`/`:partial`. | `research$verdict-outcome` returns `:in-progress`/non-empty `:blockers`; the `Saved research dossier:` line is withheld until the dossier is fixed. |
| Auto-finalize backstop | Smaller model emits an answer without finalizing. | The strict-trigger hook writes `verdict.md` + appends `INDEX.md` + injects the contract line — only when the dossier exists, no `:open` criterion, and `verdict.md` is absent. |
| Cross-agent context propagation | Inspect the `:agent-context` strings passed to each specialist. | Every call carries dossier path; acceptance focus; relevant prior artifacts. |
| Hard-rule enforcement | Try to invoke `query$clone` from inside the agent. | Tool-not-found / refusal; the curated roster excludes it. |
| Direct plan/todo/dossier write attempt | Try to `write-file` to `.brainyard/agents/plan-agent/plans/<slug>.md` or `.brainyard/<agent>/dossiers/…` directly. | Caught by the instruction (Hard Rule 2). Soft enforcement; optional follow-up is a `:agent.tool-use/pre` hook scoped to those paths. |
| Sibling dossier read via helper | Inspect tool-call log for `plan$read-dossier` / `exec$read-dossier` / `eval$read-verdict` etc. | The cherry-picked read-helpers are bound; their use should dominate `read-file` on sibling dossier bodies once the LLM gets the pattern. |
| Eval-recommendation-driven re-move | Eval returns NOT_ACHIEVED with `score.recommendations` naming todo-agent. | Next iteration is move C (DECOMPOSE), not a guess. Verifies heuristics 6/7/8 use the structured recommendation. |

mulog signals emitted by the shipped helpers (`research.clj`):

- `::research.verdict-outcome` — `{:id … :outcome … :achieved-ok? bool :n-criteria N}` (from `research$verdict-outcome`).
- `::research.index` — `{:id … :status …}` (on `INDEX.md` append).
- `::research.auto-finalize` — `{:id … :status … :answer-chars N :n-criteria N}` (backstop fired).
- `::research.auto-finalize-skip` — `{:id … :reason :verdict-exists|:no-acceptance|:open-criteria-remain …}`.
- `::research.no-dossier-skip` / `::research.auto-finalize-failed` — backstop no-op / defensive failure.

These are `mulog/log` calls in the helpers (§8) — no agent-loop changes required.

---

## 14. Migration Plan: Retiring `autoresearch`

> **As-built:** This migration is **complete through Phase 3.** `research-agent` and `research.clj`
> shipped; `components/agent/src/ai/brainyard/agent/autoresearch/` has been removed entirely (no BT,
> orchestrator, defagent, or library bits remain in-tree), and there is no `docs/AUTORESEARCH.md`.
> The phased plan below is retained for historical context — the benchmark-mode parity gates (1→2)
> were moot since benchmark mode (§10) was never built.

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

| File | Role |
|---|---|
| `components/agent/src/ai/brainyard/agent/common/research_agent.clj` | `instruction` (direct-dispatch six-specialist roster + move state machine + write-file/checklist persistence + strict 4-step finalize), `tool-context`, `defagent research-agent` via `coact/run-coact-derived` with `:max-iterations 30` default. |
| `components/agent/src/ai/brainyard/agent/common/research.clj` | The three surviving READ/DERIVE seams — `research$id`, `research$resume?`, `research$verdict-outcome` — plus the `:agent.ask/finalize` auto-finalize backstop. The structured-construction helpers (`research$bootstrap`/`update-status`/`write-verdict`/`append-log`/`index-append`) are RETIRED. |
| `components/agent/test/ai/brainyard/agent/research_agent_test.clj` | Registration smoke, bootstrap, resume detection, acceptance-checklist flip integrity, verdict-outcome guard, INDEX.md append-only. |
| `.brainyard/agents/research-agent/README.md` | Directory layout cheat-sheet. |
| `bases/agent-tui` / `bases/agent-web` | Carry no autoresearch references (removed). |
| `components/agent/src/ai/brainyard/agent/common/coact_agent.clj` | NO CHANGES — substrate, BT, sandbox, DSPy signature untouched. |
| `components/agent/src/ai/brainyard/agent/autoresearch/` | DELETED — BT, orchestrator, defagent, and library bits all removed. |

The substrate (CoAct BT, sandbox, DSPy signature) is not touched. The feature is one agent file plus a slim read/derive helpers file, identical in shape to `explore-agent` and `rlm-agent` — the lightweight redesign was contained in the instruction + the shrunk helpers namespace, not the roster.

---

## 16. Open Questions

1. **Should the dossier directory be created lazily?** Right now bootstrap is on iteration 1 unconditionally. For a question that turns out to be a single specialist call ("just plan this"), creating a full dossier directory feels heavy. Suggestion: defer directory creation until the second specialist call, OR until the LLM explicitly decides the question is research-shaped. Trade-off: harder to resume mid-flight if the directory hasn't been created yet.
2. **Should research-agent be allowed to call rlm-agent?** A research thread that hits "summarize 200 log files" is genuinely MapReduce-shaped. Reaching for rlm-agent would extend the playbook to data-heavy work. Currently the roster is silent on rlm-agent (it's reachable by direct dispatch like any other defagent). The question is whether to *teach* the agent to do this in the instruction. Suggestion: yes, as an additional specialist mention in §6 — but only after rlm-agent itself ships and stabilizes.
3. **Fold acceptance fully into the todo substrate, or keep a research-flavored variant?** Acceptance is now the §5 markdown checklist (the shared todo substrate) — the only delta is the 5-status token (`open`/`satisfied`/`partial`/`descoped`/`contradicted`) vs. todo's binary checkbox, a small superset. Lean: extend the shared checklist reader to accept an optional `(status)` token so research and todo share one parser.
4. **Should research-agent ever auto-confirm a descope?** Hard rule 3 says no — descope requires the user. But for tightly-scoped acceptance criteria (e.g., a3 "writeup exists" trivially satisfied by a markdown file), the friction may be unnecessary. Suggestion: keep the rule strict; relax via runtime config for power users only.
5. **Cost-aware sub-LM defaults?** Like rlm-agent's haiku-sub-LM recommendation, research-agent's `query$llm` synthesis calls could default to a cheaper sub-model. Worth measuring after Phase 0.
6. **Terminal verdict.md vs. final answer drift.** What if the agent's `:answer` says :partial but `verdict.md` says :achieved (e.g., because of a buggy helper)? Suggestion: make `verdict.md` the source of truth; the agent's answer is *derived* from verdict.md after it's written. Unify in Phase 1.
