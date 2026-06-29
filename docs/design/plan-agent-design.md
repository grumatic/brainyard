# Plan-Agent — Pre-flight & Post-flight Gated Plan Authoring with Dossier Handoff (CoAct-derived)

> **Status:** Shipped — `plan-agent` is registered in `components/agent` (`common/plan_agent.clj`). This document is the original design proposal (revision 2); the shipped implementation may diverge in details. See [core/agent.md](../core/agent.md) for the current roster.
>
> **As-built (verify against `common/plan_agent.clj`, `common/plan.clj`):**
> - **POST-FLIGHT verdict is PASS / HOLD only.** The design's REVISE auto-revise round (§6.2) is **deferred to v1.5** — the shipped instruction says "1+ fail → HOLD" with no automatic `doc$update` revision. `post` carries `:verdict :pass|:hold` (no `:revision-applied?` / `:revision-summary`).
> - **Cross-agent dispatch is direct kebab-case** — `(explore-agent {…})`, `(todo-agent {…})` — not `(call-tool "explore-agent" {…})`. Hard Rule 4 reads "NO clone-self dispatch" (not "NO query$clone").
> - **Plan CRUD is the polymorphic `doc$*` family with `:kind :plan`** (see the API-rename block below). Authoring uses `doc$create` / `doc$update`; duplicate check uses `(doc$list {:kind :plan})`.
> - **`write-file` / `update-file` / `fetch-url` are deliberately NOT bound** — all writes flow through `doc$create` + the `plan$dossier-*` helpers (Hard Rule 5).
> - **Shipped helper roster:** `plan$dossier-slug`, `plan$dossier-frontmatter`, `plan$dossier-write`, `plan$dossier-index-append`, `plan$read-dossier`, `plan$next-handoff`. No `plan$preflight` / `plan$postflight` / `plan$render-references` / `plan$render-approach` helpers shipped.
> **Scope:** redesign of `components/agent/src/ai/brainyard/agent/common/plan_agent.clj` + `plan.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Sibling of:** `todo-agent`, `exec-agent`, `eval-agent`, `edit-agent`, `explore-agent`
> **Related reading:** `docs/agent-design.md`, `docs/AUTORESEARCH.md`, `docs/explore-agent-design.md`, `docs/edit-agent-design.md`, `docs/CoAct.md`

> **API rename (2026-05):** the per-verb `plan$list/read/create/update-body/status/complete/abandon/reopen/delete/exists` shims have been removed in favour of the polymorphic `doc$*` family with `:kind :plan`:
> - `plan$list` / `plan$read` / `plan$create` / `plan$delete` → `(doc$list|read|create|delete :kind :plan …)`
> - `plan$update-body` → `(doc$update :kind :plan :slug … :body "…")`
> - `plan$status` → fields are included in `(doc$read :kind :plan …)`
> - `plan$complete` / `plan$abandon` / `plan$reopen` → `(doc$update :kind :plan … :status :completed|:abandoned|:reopen)`
> - `plan$exists` → check `:not-found true` on the result of `(doc$read :kind :plan …)`
> The dossier helpers (`plan$dossier-*`, `plan$read-dossier`, `plan$next-handoff`) are NOT deprecated. The body below still references the old `plan$*` names for historical clarity.

---

## 1. Motivation

The current `plan-agent` (`components/agent/.../common/plan_agent.clj`) is a thin wrapper over the `plan$*` command set. Its instruction tells the LLM to "be generous with detail" and "include Context / Approach / Risks / References / Acceptance" — and then trusts the LLM to do that. In practice three problems surface repeatedly:

1. **Plans are authored on insufficient information.** The agent gladly drafts a plan from a one-sentence question without checking whether `explore-agent` has already mapped the relevant code, whether prior plans cover the same ground, or whether the user has supplied the constraints (deadlines, owners, target files) the executor will need. The result: plans whose `## Approach` is plausible but generic, and whose `## References` is empty — `todo-agent` then derives weak items, `exec-agent` does extra discovery, and `eval-agent` cannot score because `## Acceptance` is fuzzy.
2. **Plans are shipped without a confirmation check.** Once written, the plan goes straight into `.brainyard/plans/`. There is no pass that asks: *is `## Approach` actually testable? Are the acceptance criteria observable? Is the scope small enough to be one todo, or is it secretly two?* Today the user (or `eval-agent`, after the fact) catches these. By then the executor has spent a turn budget on the wrong shape.
3. **Downstream agents consume the raw plan body.** `todo-agent` / `exec-agent` / `eval-agent` all receive the plan via `:agent-context`, but the channel is unstructured — sometimes a slug, sometimes a path, sometimes a body, never a stable schema with the fields each downstream actually needs (acceptance criteria, suggested-next agent, prior-attempt links). Each agent re-parses the plan, sometimes inconsistently. There is no place to record "this plan was authored after pre-flight check X passed and post-flight check Y is on hold."

The same redesign also folds in a layout migration. Today plans live at `.brainyard/plans/`; the rest of the agent ecosystem (`explore-agent`, `edit-agent`, `autoresearch`) uses per-agent directories at `.brainyard/<agent>/`. Plan-agent is the odd one out. The redesign moves plans to `.brainyard/agents/plan-agent/plans/<slug>.md` to match the convention and to make room for sibling subdirectories (`dossiers/`, `drafts/`, `INDEX.md`).

**Thesis.** Redesign `plan-agent` so every authoring run runs through a fixed three-phase pipeline:

1. **PRE-FLIGHT (sufficiency check)** — does the agent have enough information to plan? Concretely: is the goal disambiguated, do the references exist, has discovery been done, is there a near-duplicate plan? The output is GO (proceed) or GATHER (recommend / dispatch `explore-agent` or ask the user a single targeted question — do not draft a plan on assumptions).
2. **AUTHOR / REVISE** — the existing plan$create / plan$update-body call, now writing under the new layout.
3. **POST-FLIGHT (confirmation check)** — re-read the just-authored plan and self-critique with `query$llm` against a fixed rubric: approach actionable, acceptance observable, references concrete, scope sized for one todo. The output is PASS (ready for `todo-agent`), REVISE (one round of automatic plan$update-body), or HOLD (surface specific gaps to the user — do not yet hand off to `todo-agent`).

Every run — GO/GATHER and PASS/REVISE/HOLD alike — produces a **dossier**, a small markdown file under `.brainyard/agents/plan-agent/dossiers/<slug>-<ts>.md` that downstream agents consume in their `:agent-context`. The dossier is the stable, schema'd handoff channel that replaces ad-hoc string passing.

Same minimal-diff principle as `explore-agent` / `edit-agent`: one redesigned agent file, one helper namespace tweak, one storage migration. The CoAct loop, sandbox, BT, and DSPy signatures are untouched.

---

## 2. Design Principles

1. **No plan is authored on assumptions.** If pre-flight finds a gap, the agent SURFACES it and dispatches a discovery probe (`explore-agent`) or asks the user one targeted question. It does NOT proceed to draft `## Approach` from a hunch.
2. **Plans are read-only after they ship.** The plan body itself is durable. Per-run sufficiency, revisions, holds, and hand-off recommendations live in the dossier — the plan stays a clean blueprint that any teammate can read without wading through agent-internal bookkeeping.
3. **Dossier is the contract with downstream.** `todo-agent` / `exec-agent` / `eval-agent` consume the dossier path in `:agent-context`, NOT the raw plan body. The dossier carries the plan path, the pre-flight verdict, the post-flight verdict, the extracted acceptance list (machine-readable), and the suggested next agent. Downstream agents `read-file` only the frontmatter (cheap) for routing decisions.
4. **One dossier per turn.** Every plan-agent invocation produces exactly one dossier file, even when the run STOPS at pre-flight (GATHER) without authoring. The dossier records what was checked, why the run halted, and what the next call should supply. This makes "what state is plan X in?" trivially auditable.
5. **Self-critique is mandatory and budgeted.** Post-flight runs `query$llm` against a fixed rubric (§6). One automatic revision round is allowed; further revisions require the user to weigh in. This caps the loop and keeps token cost predictable.
6. **Layout matches the rest of the ecosystem.** Plans move to `.brainyard/agents/plan-agent/plans/<slug>.md`; the agent's runtime artifacts (dossiers, drafts, index) sit beside them under `.brainyard/agents/plan-agent/`.
7. **Migration is reversible and dual-read.** During the transition the `plan$*` commands read from BOTH old (`.brainyard/plans/`) and new (`.brainyard/agents/plan-agent/plans/`) locations; writes go to the new location. A `bb migrate:plan-agent` task moves old files in one shot; rollback is a `git checkout` away.
8. **No clone-self recursion.** Like `explore-agent` and `edit-agent`, plan-agent excludes `query$clone`. Cross-agent dispatch via `(call-tool "<other-agent>" …)` is fine.
9. **Never invent slugs, references, or acceptance criteria.** Pre-flight refuses on missing inputs; post-flight flags fabricated specifics; the dossier is honest about gaps.

---

## 3. Position in the Agent Stack

```
coact-agent          (parent — full BT, sandbox, router, accumulator)
  ├─ explore-agent       (read-mostly multi-surface discovery)
  ├─ plan-agent          (THIS — authoring with pre/post-flight + dossier)
  ├─ todo-agent          (spawn / advance executable items)
  ├─ exec-agent          (drive todo to completion)
  ├─ eval-agent          (score execution vs plan acceptance)
  ├─ edit-agent        (safe single-file edits)
  └─ rlm-agent / mcp-agent / skill-agent / explore-agent
```

The four-agent pipeline `plan → todo → exec → eval` is the spine of `autoresearch` (`docs/AUTORESEARCH.md`) and the natural shape of any non-trivial user request. The dossier is what threads them:

```
explore-agent → Saved exploration: <path>
                           │
                           ▼  (consumed as :agent-context)
plan-agent   → Saved plan:    <plan path>
              → Saved dossier: <plan-agent dossier path>
                           │
                           ▼
todo-agent   → Saved todo:    <todo path>
              → Saved dossier: <todo-agent dossier path>
                           │
                           ▼
exec-agent   → Saved run:     <run record>
              → Saved dossier: <exec-agent dossier path>     ← exec evidence
                           │
                           ▼
eval-agent   → Saved verdict: <verdict path>
              → Saved dossier: <eval-agent dossier path>
              → Verdict: ACHIEVED | PARTIALLY_ACHIEVED | NOT_ACHIEVED
```

Each agent consumes the previous agent's dossier and emits its own. This is the same pattern explore-agent established with `Saved exploration:`, generalized.

---

## 4. PRE-FLIGHT — Sufficiency Check (NEW)

Runs before any `plan$create` / `plan$update-body` call. The agent walks a fixed checklist and produces a `pre` map; the result is one of:

- **GO** — every check passed, proceed to AUTHOR.
- **GATHER** — at least one check failed in a way the agent can resolve by dispatching `explore-agent` or asking the user. STOP authoring; produce the dossier with `:pre.verdict :gather` and the specific question / dispatch recommendation.
- **REFUSE** — the request is ill-posed (e.g., contradicts an existing plan, asks to plan something that needs `edit-agent` not a plan, references a non-existent codebase area). STOP and report.

### 4.1 The Checklist

| Check | What it verifies                                                                                                 | How                                                                                                                                                            | Fail → action                                                                                                                                                                                                                                                                                            |
| ----- | ---------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| C1    | The user's request decodes to a clear goal.                                                                     | LLM self-question: "If I had to write `## Acceptance` right now, could I name 1–3 observable signals?"                                                          | GATHER — ask the user one targeted question (the rubric below).                                                                                                                                                                                                                                          |
| C2    | A near-duplicate plan does NOT already exist.                                                                    | `plan$list :status :draft|:in-progress`, fuzzy-match titles & questions.                                                                                       | If exact match → REFUSE with pointer to the existing plan. If near-match → GATHER with "extend existing plan via plan$update-body, or confirm this is a separate plan."                                                                                                                                  |
| C3    | If the request implies a target codebase area, that area was actually explored.                                  | Look in `:agent-context` for a `Saved exploration: <path>` line. If absent and the request mentions specific files / components / directories that are not in the working set, run `explore$find` (when bound) or `plan$list` over recent dossiers to see if exploration exists. | GATHER — recommend `(call-tool "explore-agent" {…})` first, OR auto-dispatch when `:auto-explore? true`.                                                                                                                                                                                                  |
| C4    | All references the agent will cite in `## References` actually exist on disk.                                   | For each plausible file path mentioned in the request or `:agent-context`, `bash "test -f <path>"`. For URLs, defer to authoring (don't fetch every URL).      | GATHER — list missing paths, ask the user to confirm or correct.                                                                                                                                                                                                                                          |
| C5    | The request is plan-shaped, not edit-shaped or explore-shaped.                                                   | Heuristic on the request: if the entire request collapses to "rename foo→bar" or "find X", it does not need a plan.                                            | REFUSE with a redirect: "this is one `edit-agent` call; no plan needed" / "this is one `explore-agent` call; no plan needed."                                                                                                                                                                          |
| C6    | The request scope fits in one plan.                                                                              | Heuristic on request length + the count of distinct subgoals the LLM extracts. Multi-quarter or cross-team work → suggest a parent plan + child plans.        | GATHER — propose a plan-of-plans structure, ask the user to confirm decomposition before authoring.                                                                                                                                                                                                      |
| C7    | The user has named, or can be inferred, an owner / dispatcher for the eventual execution.                       | Inspect `:agent-context` for "owner: …" or check the user's session config.                                                                                    | INFORMATIONAL — record `pre.owner :unknown` in the dossier; do not block. The dossier flags it; downstream agents can ask later.                                                                                                                                                                          |

The checks are evaluated **in order** and the agent SHORT-CIRCUITS on the first failure (records the rest as `:not-evaluated`). This keeps token cost low for the common GATHER cases.

### 4.2 Targeted Question Rubric (when GATHER is forced by C1)

A bad clarifying question chains the user into a long Q&A. The agent picks ONE of these forms, never more:

- **Goal disambiguation:** "Are you asking to <interpretation A> or <interpretation B>?" — multi-choice.
- **Acceptance disambiguation:** "What signal would tell you this is done? <1–3 candidates>" — multi-choice with a free-form fallback.
- **Scope disambiguation:** "Should this plan cover <subset A> only, or also <subset B>?" — multi-choice.

The agent uses the framework's `AskUserQuestion`-equivalent path when present (the runtime supplies a feedback channel via `get-user-feedback`); otherwise it records the question in the dossier and STOPS, letting the dispatcher relay it.

### 4.3 The `pre` Map

```clojure
(def pre
  {:verdict   :go                    ; or :gather | :refuse
   :checks    {:c1 :pass             ; :pass | :fail | :not-evaluated
               :c2 :pass
               :c3 :pass
               :c4 :pass
               :c5 :pass
               :c6 :pass
               :c7 :informational}
   :exploration-path ".brainyard/agents/explore-agent/results/2026...."
                                     ; or nil if none consumed
   :owner            "jake"          ; or :unknown
   :related-plans    [{:slug "..." :title "..." :status :draft}]
   :gather-question  nil             ; only when verdict = :gather
   :refuse-reason    nil             ; only when verdict = :refuse
   })
```

`pre` is stashed via `def` so the dossier-writer (§7) can emit it verbatim into the frontmatter and the post-flight pass can refer back to it.

---

## 5. AUTHOR / REVISE — Core Operation

This phase only runs when `pre.verdict = :go`. It is essentially the existing plan-agent flow, with the layout change baked in:

- `plan$create :title <T> :body <B> :scope :project` writes to `.brainyard/agents/plan-agent/plans/<slug>.md`.
- `plan$update-body` for an existing slug rewrites the body in place; the agent ALWAYS reads first via `plan$read` and merges, never blindly overwrites.

The body shape is unchanged — the existing `## Context / ## Approach / ## Risks / ## References / ## Acceptance` rhythm carries forward verbatim. What changes is that the agent now has a `pre` map to lean on:

- `## References` MUST include every path that survived check C4 + the `pre.exploration-path` if present.
- `## Approach` MUST be actionable enough for `todo-agent` to derive items mechanically — list verbs (`Wire`, `Update`, `Add`, `Validate`), not nouns (`Auth`, `Tests`, `Docs`).
- `## Acceptance` MUST contain at least the candidates from C1's disambiguation if that ran.

Helpers (§12, when bound):

```clojure
(plan$render-references :pre pre)         ; → markdown bullet list
(plan$render-approach :pre pre :goal …)   ; → seed an actionable Approach
```

The `plan$create` / `plan$update-body` call returns the path, which the agent stashes:

```clojure
(def authored {:slug "..." :path ".brainyard/agents/plan-agent/plans/<slug>.md"})
```

---

## 6. POST-FLIGHT — Confirmation Check (NEW)

Runs immediately after AUTHOR. The agent re-reads the just-written plan and grades it against a fixed rubric using `query$llm`. The rubric is in the instruction (§8) and produces a `post` map.

### 6.1 The Rubric

| Item | Rubric question                                                                                                                                                                       | Pass criterion                                                                  |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| R1   | Does `## Approach` decompose into 3–15 actionable verbs that `todo-agent` could mechanically convert into items?                                                                       | At least 3 actionable bullets; no single bullet dominates.                      |
| R2   | Does `## Acceptance` list 1–5 observable signals — each something a future reader could verify by running a command, reading a file, or checking a metric?                            | Every criterion is concrete (no "users find it intuitive" without an instrument). |
| R3   | Does `## References` cite every path / URL / dossier the executor will need? Every cited path resolves on disk.                                                                        | Cross-check against `bash "test -f <path>"` for each repo-relative reference.   |
| R4   | Does `## Risks / Open Questions` name at least one specific risk and at least one named open question (or explicitly say "no open questions")?                                       | Generic risks like "could break things" are not specific.                       |
| R5   | Is the plan scope sized for one todo (3–30 items)? Bigger → recommend split; smaller → recommend folding into another plan.                                                            | Estimate from the actionable bullet count.                                      |
| R6   | Are there contradictions between sections? (e.g., `## Approach` mentions a file that does not appear in `## References`; `## Acceptance` references a metric `## Approach` does not move.) | LLM cross-check via query$llm.                                                  |
| R7   | Are there dangling LLM artifacts? (TODO markers, hand-wavy phrases, fabricated function names not present in the codebase.)                                                            | Grep the body for telltales (`TODO`, `???`, `<...>`, `[fill in]`, `tk`).        |

### 6.2 Verdict

- **PASS** — every R-item passes. Plan is ready for `todo-agent`.
- **REVISE** — one or more items fail in a way the agent can fix in a single `plan$update-body` round. The agent applies the revision (with the LLM's proposed amended body), records the revision in the dossier, and re-runs R1–R7 *once*. If still failing → HOLD.
- **HOLD** — failures persist after one revision round, OR the failure requires user input (e.g., R2 fails because the user never said how to measure success, OR R5 says split-into-three-plans). The plan body stays where it is; the dossier records the open holds; the answer recommends the user confirm next steps before `todo-agent` is dispatched.

### 6.3 The `post` Map

```clojure
(def post
  {:verdict      :pass               ; or :revise | :hold
   :rubric       {:r1 :pass :r2 :pass :r3 :fail :r4 :pass
                  :r5 :pass :r6 :pass :r7 :pass}
   :revision-applied? false          ; true if one auto-revision round ran
   :revision-summary nil             ; one-line description of what changed
   :holds        []                  ; vector of {:item :r3 :description "missing reference: X"}
   :acceptance   ["all unit tests green" "/ready endpoint returns 200"]
                                     ; extracted machine-readable list
   })
```

`post.acceptance` is critical — it's the structured handoff to `eval-agent` that lets it score without reparsing markdown.

---

## 7. Output Discipline — `.brainyard/agents/plan-agent/`

Same shape as `.brainyard/agents/explore-agent/` (`docs/explore-agent-design.md` §5), tuned for plans.

### 7.1 Directory Layout

```
.brainyard/
├── plan-agent/
│   ├── plans/                     ; the durable plan corpus (replaces .brainyard/plans/)
│   │   ├── ship-v2-checkout.md
│   │   ├── retire-search-agent.md
│   │   └── ...
│   ├── dossiers/                  ; per-turn handoff records — newest stays
│   │   ├── 20260510-104503-ship-v2-checkout.md
│   │   ├── 20260510-191812-ship-v2-checkout.md   ; second turn on same slug
│   │   └── 20260510-194412-retire-search-agent.md
│   ├── drafts/                    ; per-turn scratch (overwritable)
│   ├── INDEX.md                   ; one line per dossier, newest first
│   └── README.md                  ; layout cheat-sheet
```

`plans/` replaces the legacy `.brainyard/plans/`. The body schema is unchanged — only the path moves. `dossiers/`, `drafts/`, and `INDEX.md` are new.

### 7.2 Dossier Schema (the contract with downstream agents)

```markdown
---
slug: ship-v2-checkout
agent: plan-agent
created: 2026-05-10T10:45:03Z
plan_path: .brainyard/agents/plan-agent/plans/ship-v2-checkout.md
plan_status: draft
turn_id: <id>
session_id: <id>

pre:
  verdict: go                       # go | gather | refuse
  checks:
    c1_goal_clear:        pass
    c2_no_duplicate:      pass
    c3_explored:          pass
    c4_refs_exist:        pass
    c5_plan_shaped:       pass
    c6_scope_one_plan:    pass
    c7_owner_known:       informational
  exploration_path: .brainyard/agents/explore-agent/results/20260509-181244-checkout-codepaths.md
  owner: jake
  related_plans: []
  gather_question: null
  refuse_reason: null

author:
  action: created                   # created | updated | unchanged
  body_bytes: 4128

post:
  verdict: pass                     # pass | revise | hold
  rubric:
    r1_actionable_approach: pass
    r2_observable_acceptance: pass
    r3_references_resolve:    pass
    r4_specific_risks:        pass
    r5_one_todo_scope:        pass
    r6_no_contradictions:     pass
    r7_no_dangling_artifacts: pass
  revision_applied: false
  revision_summary: null
  holds: []
  acceptance:
    - "feature-flag `checkout-v2` toggleable from the staging admin"
    - "all checkout/* unit tests green"
    - "p99 checkout latency unchanged within ±5%"

handoff:
  next_agent: todo-agent
  next_call: '(call-tool "todo-agent" {:question "Spawn a todo from this plan." :agent-context "<this dossier path>"})'
---

# Plan dossier — Ship v2 checkout

## Pre-flight summary
- Goal: ship checkout v2 behind feature flag `checkout-v2`.
- Exploration consumed: `.brainyard/agents/explore-agent/results/20260509-181244-checkout-codepaths.md`.
- Owner: jake.
- All references resolve on disk.
- No duplicate plan in `.brainyard/agents/plan-agent/plans/`.

## Plan summary (extracted)
- **Approach:** wire the LD flag, update the payment validator, migrate the cart serializer, add migration tests.
- **Acceptance:** see `post.acceptance` above.
- **Risks:** payment-validator regression on legacy carts (named); cart-serializer schema drift (named).
- **Open questions:** none.

## Post-flight notes
All rubric items passed on the first pass. No revision applied.

## Handoff
Pass `<this dossier path>` to todo-agent in `:agent-context`. todo-agent will run its own pre-flight to confirm the plan's `## Approach` decomposes cleanly into items.
```

Frontmatter contract (downstream parsers may rely on these):

| Key                 | Type                                  | Description                                                                                                  |
| ------------------- | ------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `slug`              | string                                | Plan slug. Multiple dossiers can share a slug across turns.                                                  |
| `agent`             | string                                | Always `plan-agent`.                                                                                         |
| `created`           | ISO-8601 string                       | UTC timestamp.                                                                                               |
| `plan_path`         | string                                | Repo-relative path to the plan body.                                                                         |
| `plan_status`       | enum                                  | `draft` \| `in-progress` \| `completed` \| `abandoned`. Reflects the plan file's frontmatter at dossier time. |
| `pre.*`             | map                                   | Verbatim copy of the `pre` map (§4.3). Crucially includes `verdict` and per-check pass/fail.                  |
| `author.action`     | enum                                  | `created` \| `updated` \| `unchanged` (when pre = gather/refuse, no plan body was touched).                  |
| `post.*`            | map                                   | Verbatim copy of the `post` map (§6.3). `acceptance` is the machine-readable criterion list.                  |
| `handoff.next_agent`| string                                | Name of the recommended next agent. `todo-agent` on PASS; `plan-agent` (re-call) on HOLD; `none` on REFUSE.   |
| `handoff.next_call` | string                                | The exact `(call-tool …)` form a dispatcher can paste verbatim.                                               |

Body sections under the frontmatter are freeform but should follow the `## Pre-flight summary / ## Plan summary / ## Post-flight notes / ## Handoff` rhythm.

### 7.3 Two Levels of Cheap Read

Same pattern as `explore-agent`:

- **Cheap (~700 bytes):** read just the frontmatter via `plan$read-dossier :path …` (or `read-file :lines [1 50]`). Sufficient for routing and for `eval-agent` to extract `acceptance` without fetching the plan body.
- **Full (~3–8 KB typically):** the body when a downstream agent wants the prose summary.

### 7.4 INDEX.md

Newest-first prepend, one line per dossier:

```markdown
- 2026-05-10 10:45 [ship-v2-checkout](dossiers/20260510-104503-ship-v2-checkout.md) — pre:go · post:pass · → todo-agent
- 2026-05-10 19:18 [ship-v2-checkout](dossiers/20260510-191812-ship-v2-checkout.md) — pre:go · post:revise · → todo-agent
- 2026-05-10 19:44 [retire-search-agent](dossiers/20260510-194412-retire-search-agent.md) — pre:gather · post:n/a · → user (need owner)
```

### 7.5 ANSWER Format

Three stable lines at the end of the agent's `answer`:

```
Saved plan: <plan path>          (omitted when author.action = unchanged)
Saved dossier: <dossier path>
Next: <handoff.next_call>
```

The `Saved plan:` and `Saved dossier:` prefixes are the contract; downstream agents grep for them. `Next:` is informational but conventional — it's the handoff suggestion in copy-paste form.

For pre-flight GATHER:

```
Saved dossier: <dossier path>
Need: <one-line description of the missing input>
Suggested: <(call-tool "explore-agent" {…}) OR a one-line user question>
```

For pre-flight REFUSE:

```
Saved dossier: <dossier path>
Refused: <one-line reason>
Suggested: <redirect — e.g., "use edit-agent for this single-file edit">
```

---

## 8. Instruction (System Prompt Body)

Layered on top of `coact-agent`'s instruction by `run-coact-derived`.

```text
You are a PLAN-agent. You author a plan blueprint AFTER you have confirmed
you have enough information, and you confirm the result is sound BEFORE
handing off to todo-agent. You ALWAYS produce a dossier — even when you
refuse or stop early. You NEVER author plans on assumptions.

Plans are stored at `.brainyard/agents/plan-agent/plans/<slug>.md`. (Legacy
location `.brainyard/plans/` is read for one release; all new writes go
to the new path.)

────────────────────────────────────────────────────────────────────────────
THE THREE PHASES (run them in order, every turn)
────────────────────────────────────────────────────────────────────────────
PRE-FLIGHT  — sufficiency check. Output: GO | GATHER | REFUSE.
AUTHOR      — only when PRE-FLIGHT = GO. plan$create or plan$update-body.
POST-FLIGHT — only when AUTHOR ran. Self-critique against a 7-item rubric.
              Output: PASS | REVISE | HOLD.
PERSIST     — always. Write the dossier under .brainyard/agents/plan-agent/dossiers/.
ANSWER      — emit `Saved plan:` (when authored), `Saved dossier:` (always),
              and `Next:` lines.

────────────────────────────────────────────────────────────────────────────
PRE-FLIGHT — sufficiency checklist
────────────────────────────────────────────────────────────────────────────
Walk these in ORDER. Short-circuit on the first failure.

C1. GOAL CLEAR. Could you write `## Acceptance` right now with 1–3
    observable signals? If not → GATHER. Ask the user ONE targeted
    question (multi-choice when possible).

C2. NO DUPLICATE. plan$list :status :draft|:in-progress. Fuzzy-match
    against existing titles + plan dossiers. Exact match → REFUSE with
    pointer. Near-match → GATHER ("extend existing? confirm?").

C3. EXPLORED. Look for `Saved exploration: <path>` in :agent-context.
    If absent and the request mentions specific files / components /
    directories, recommend `(call-tool "explore-agent" {…})` first.
    GATHER unless :auto-explore? true (then dispatch silently).

C4. REFS EXIST. For every plausible repo-relative path mentioned in the
    request or :agent-context, `bash "test -f <path>"`. Missing paths →
    GATHER, list them.

C5. PLAN SHAPED. The request must require a plan, not a single edit or
    a single discovery. Single-file rename → REFUSE, redirect to
    edit-agent. Single lookup → REFUSE, redirect to explore-agent.

C6. SCOPE FITS ONE PLAN. 3–30 actionable items in the eventual todo.
    Multi-quarter / cross-team → GATHER, propose plan-of-plans split.

C7. OWNER KNOWN. Inspect :agent-context / session for an owner. If
    unknown → INFORMATIONAL (record in dossier, do not block).

Stash the result:
   (def pre {:verdict :go|:gather|:refuse
             :checks {<c1..c7> :pass|:fail|:not-evaluated}
             :exploration-path <str-or-nil>
             :owner <str-or-:unknown>
             :related-plans [...]
             :gather-question <str-or-nil>
             :refuse-reason <str-or-nil>})

────────────────────────────────────────────────────────────────────────────
GATHER PROTOCOL — one targeted question
────────────────────────────────────────────────────────────────────────────
A bad clarifying question turns into a 5-turn Q&A. Pick ONE form:

- GOAL: "Are you asking to <A> or <B>?"
- ACCEPTANCE: "What signal would tell you this is done? (1) … (2) … (3) …"
- SCOPE: "Should this cover <subset A> only, or also <subset B>?"

Use get-user-feedback when bound (multi-choice). Otherwise record the
question in the dossier and STOP — the dispatcher will surface it.

NEVER chain clarifying questions. If you cannot form ONE good question,
record the ambiguity in the dossier verdict and STOP.

────────────────────────────────────────────────────────────────────────────
AUTHOR — only on PRE-FLIGHT = GO
────────────────────────────────────────────────────────────────────────────
- Existing slug → plan$read first; merge updates; plan$update-body.
- New slug → plan$create with :title, :body, :scope :project (default).
- Body uses ## Context / ## Approach / ## Risks / ## References /
  ## Acceptance. Required: every reference cited in approach also appears
  in references; every acceptance criterion is observable; references
  include the pre.exploration-path if non-nil.
- Stash:
   (def authored {:slug "..." :path "<repo-relative>"
                  :action :created|:updated|:unchanged})

────────────────────────────────────────────────────────────────────────────
POST-FLIGHT — 7-item rubric
────────────────────────────────────────────────────────────────────────────
Re-read the just-authored plan via plan$read. Score each:

R1. APPROACH ACTIONABLE — 3–15 verb-led bullets, none dominant.
R2. ACCEPTANCE OBSERVABLE — every criterion verifiable via command /
    file / metric. No "users find it intuitive" without an instrument.
R3. REFERENCES RESOLVE — every cited repo-relative path passes
    `bash "test -f <path>"`.
R4. RISKS SPECIFIC — at least one named risk and one named open question
    (or explicit "none").
R5. SCOPE FITS — actionable bullet count ∈ [3, 30]; otherwise propose
    split or fold-in.
R6. NO CONTRADICTIONS — files in approach also in references; metrics
    in acceptance match approach moves.
R7. NO ARTIFACTS — no `TODO`, `???`, `<...>`, `[fill in]`, `tk`.

Use query$llm for R1, R2, R6 (qualitative judgement). R3 / R7 are
mechanical (bash + grep). R4 / R5 are short LLM checks.

VERDICT:
- All pass               → PASS.
- 1+ fail, fixable       → REVISE. Apply ONE plan$update-body round with
                           the LLM's amended body. Re-run R1–R7 ONCE. If
                           still failing → HOLD.
- 1+ fail, needs user    → HOLD. Record specific holds in the dossier.
                           Do NOT dispatch todo-agent. Surface holds in
                           the answer.

Stash:
   (def post {:verdict :pass|:revise|:hold
              :rubric {<r1..r7> :pass|:fail}
              :revision-applied? <bool>
              :revision-summary <str-or-nil>
              :holds [{:item :r3 :description "..."}]
              :acceptance ["criterion 1" "criterion 2"]})

────────────────────────────────────────────────────────────────────────────
PERSIST — dossier (always)
────────────────────────────────────────────────────────────────────────────
Write `.brainyard/agents/plan-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md` with
the frontmatter schema in docs/plan-agent-design.md §7.2. PREPEND a line
to .brainyard/agents/plan-agent/INDEX.md.

When plan$* helpers (§12) are bound:

   (def slug (or (:slug authored) "_<hash-of-question>"))
   (def fm (:frontmatter
             (plan$dossier-frontmatter
               :slug slug :pre pre :author authored :post post
               :handoff (plan$next-handoff :pre pre :post post :slug slug))))
   (def res (plan$dossier-write :slug slug :content (str fm body)))
   (plan$dossier-index-append :path (:path res) :slug slug
                              :pre-verdict (:verdict pre)
                              :post-verdict (or (:verdict post) :n-a)
                              :next-agent (:next-agent (:handoff fm)))

────────────────────────────────────────────────────────────────────────────
ANSWER — three lines (stable prefixes)
────────────────────────────────────────────────────────────────────────────
On AUTHOR + POST-FLIGHT = PASS:
    Saved plan: <plan-path>
    Saved dossier: <dossier-path>
    Next: (call-tool "todo-agent" {:question "Spawn a todo for this plan."
                                   :agent-context "<dossier-path>"})

On POST-FLIGHT = REVISE (revision applied, now PASS):
    Saved plan: <plan-path>
    Saved dossier: <dossier-path>
    Note: one auto-revision applied (<revision-summary>).
    Next: (call-tool "todo-agent" …)

On POST-FLIGHT = HOLD:
    Saved plan: <plan-path>
    Saved dossier: <dossier-path>
    Hold: <one-line-per-hold>
    Suggested: <user action OR call to plan-agent again>

On PRE-FLIGHT = GATHER:
    Saved dossier: <dossier-path>
    Need: <missing input>
    Suggested: (call-tool "explore-agent" …)  OR  <one-line user question>

On PRE-FLIGHT = REFUSE:
    Saved dossier: <dossier-path>
    Refused: <reason>
    Suggested: <redirect>

────────────────────────────────────────────────────────────────────────────
HARD RULES
────────────────────────────────────────────────────────────────────────────
1. NO authoring on assumptions. PRE-FLIGHT failure → GATHER or REFUSE,
   never silent inference.
2. NO chaining clarifying questions. ONE question per turn, multi-choice
   when possible. If ONE question won't suffice, refuse and ask the user
   to scope down.
3. NO unbounded revision. POST-FLIGHT allows ONE auto-revise round. After
   that → HOLD.
4. NO mutating todos / exec records. Plans only. todo$* and exec$* tools
   are not bound here.
5. NO query$clone. Cross-agent dispatch via call-tool is fine
   (explore-agent, todo-agent, etc.).
6. NO writing outside .brainyard/agents/plan-agent/. Writes that escape this
   prefix are refused.
7. NO inventing slugs or references. Both are checked against disk before
   they appear in the body or the dossier.
8. NEVER skip the dossier. Even REFUSE turns produce a dossier — it's the
   audit trail.
```

---

## 9. Tool-Context (How to Use the Bound Tools)

```text
## Plan Tools — author plan blueprints under .brainyard/agents/plan-agent/plans/

PLAN MANAGEMENT (plan$*)
- plan$list         -- List plans. Args: scope (project|user, optional),
                       status (draft|in-progress|completed|abandoned,
                       optional). Reads from .brainyard/agents/plan-agent/plans/
                       AND legacy .brainyard/plans/ (one release).
- plan$read         -- Read a plan by slug. Args: slug, scope (optional).
                       Returns frontmatter + body + :file-path.
- plan$create       -- Create a new plan blueprint. Args: title, body
                       (free-form markdown — Context / Approach / Risks /
                       References / Acceptance), scope (project|user).
                       Writes to .brainyard/agents/plan-agent/plans/<slug>.md.
- plan$update-body  -- Replace the free-form body. Args: slug, body. Read
                       first, merge — never blind-overwrite. Used by both
                       author updates AND the POST-FLIGHT revise round.
- plan$status       -- Frontmatter summary. Args: slug.
- plan$complete     -- Mark plan completed. Args: slug.
- plan$abandon      -- Mark plan abandoned. Args: slug.
- plan$reopen       -- Flip status back to :draft. Args: slug. Body
                       persists.
- plan$delete       -- Delete a plan. Args: slug, scope (optional).
                       Destructive — confirm.
- plan$exists       -- Check existence. Args: slug, scope (optional).

PRE-FLIGHT HELPERS (informational; built on bash + plan$list)
- bash "test -f <path>"          — C4 reference-resolves check.
- plan$list :status :draft        — C2 duplicate check.

CROSS-AGENT DISPATCH (sparingly; never recursive on plan-agent itself)
- (call-tool "explore-agent" {:question "<probe>" :agent-context "..."})
    — when PRE-FLIGHT C3 fails AND :auto-explore? true.
- (call-tool "todo-agent" {:question "Spawn a todo for this plan."
                           :agent-context "<plan dossier path>"})
    — RECOMMENDED via the `Next:` answer line. Do NOT auto-dispatch
    unless the user said "and dispatch todo-agent next" in this turn.

SUB-LLM (rubric scoring)
- query$llm        -- Used heavily in POST-FLIGHT for R1, R2, R6 (fuzzy
                      judgement). Single-prompt typical; batched across
                      rubric items when iteration budget is tight.

PERSISTENCE HELPERS (`plan$*` dossier suite — auto-bound when present)

- (plan$dossier-slug :question "<text>")
    → {:slug "<slug>"}. When the agent has a real plan slug, use that;
      use this only for GATHER/REFUSE turns where no plan was authored.

- (plan$dossier-frontmatter :slug … :pre {…} :author {…} :post {…}
                            :handoff {…})
    → {:frontmatter "..."}. YAML block per §7.2 schema.

- (plan$dossier-write :slug … :content "<full markdown>")
    → {:path ".brainyard/agents/plan-agent/dossiers/<ts>-<slug>.md"
       :slug <final-slug> :ts <yyyyMMdd-HHmmss>}.
      Auto-suffixes -2/-3 on collision.

- (plan$dossier-index-append :path … :slug … :pre-verdict :go
                             :post-verdict :pass :next-agent "todo-agent")
    → {:appended true}. PREPEND newest first.

- (plan$read-dossier :path "<path>")
    → parsed map, frontmatter only. Used by todo-agent / eval-agent to
      route on metadata without paying for the body.

- (plan$next-handoff :pre {…} :post {…} :slug …)
    → {:next-agent "todo-agent" :next-call "(call-tool …)"}.
      Centralizes the "what to recommend next" logic so all dossiers
      use the same call-form.

If helpers are not yet bound, build the equivalent inline with
write-file + a clojure fence (similar to the explore-agent flow in
docs/explore-agent-design.md §9).

## Typical end-to-end flow
1. Parse :question and :agent-context (typically a `Saved exploration:`).
2. PRE-FLIGHT — walk C1–C7. Stash `pre`. Short-circuit on first fail.
3. If PRE-FLIGHT ≠ :go → skip AUTHOR + POST-FLIGHT, jump to PERSIST + ANSWER.
4. AUTHOR — plan$create or plan$update-body. Stash `authored`.
5. POST-FLIGHT — re-read, run R1–R7 with query$llm + bash + grep.
   Apply at most ONE plan$update-body revision round. Stash `post`.
6. PERSIST — write dossier; prepend INDEX.md.
7. ANSWER — `Saved plan:` + `Saved dossier:` + `Next:` (or the
   GATHER/REFUSE/HOLD variants).
```

---

## 10. Behavior Tree — Inherited As-Is

`plan-agent` does **not** define its own BT. `run-coact-derived` falls back to `coact-agent`'s `:bt-factory`. The three-phase pipeline (§4–§6) is a *prompt* contract, not a BT-level contract — same rationale as `edit-agent` (`docs/edit-agent-design.md` §9).

Iteration shape for a typical authoring run:

| Iter  | Channel       | Body                                                                                         |
| ----- | ------------- | -------------------------------------------------------------------------------------------- |
| 1     | code          | PRE-FLIGHT C1–C7. `def pre`.                                                                  |
| 2     | tool / code   | (only when GO) AUTHOR via plan$create / plan$update-body. `def authored`.                     |
| 3     | code          | (only when AUTHOR ran) POST-FLIGHT rubric R1–R7 with query$llm + bash. Optional one revise. `def post`. |
| 4     | code          | PERSIST dossier; prepend INDEX.                                                               |
| 5     | answer        | `Saved plan:` + `Saved dossier:` + `Next:` (or variants).                                    |

GATHER turns collapse to iterations 1 → 4 → 5. REFUSE turns are even shorter.

---

## 11. Demonstration: "Plan how we'll ship checkout v2"

Mixed-input request: the user has already run `explore-agent` and pastes the resulting `Saved exploration:` path into `:agent-context`.

### Iteration 1 — PRE-FLIGHT (code channel)

````markdown
```clojure
(def question "Plan how we'll ship checkout v2 behind a feature flag.")
(def agent-context
  "Saved exploration: .brainyard/agents/explore-agent/results/20260509-181244-checkout-codepaths.md")

;; C1 — goal clear?
(def acceptance-candidates
  (:result (query$llm
             :prompt (str "If you had to write `## Acceptance` for the request: "
                          question " — name 1–3 observable signals."))))

;; C2 — no duplicate?
(def existing
  (call-tool "plan$list" {:status :draft :scope :project}))

;; C3 — exploration consumed?
(def explore-path
  (when (re-find #"Saved exploration: (\S+)" agent-context)
    (last (re-find #"Saved exploration: (\S+)" agent-context))))

;; C4 — referenced files exist?
(def refs ["src/checkout/flags.clj"
           "src/checkout/payment_validator.clj"
           "src/checkout/cart.clj"])
(def refs-exist?
  (every? #(zero? (:exit-code (call-tool "bash" {:command (str "test -f " %)})))
          refs))

;; C5 — plan-shaped (not a single edit / single lookup)?  yes — multi-step.
;; C6 — scope fits one plan?  yes — bounded to checkout subsystem.
;; C7 — owner known?  inspect session config or :agent-context.

(def pre {:verdict :go
          :checks {:c1 :pass :c2 :pass :c3 :pass :c4 (if refs-exist? :pass :fail)
                   :c5 :pass :c6 :pass :c7 :informational}
          :exploration-path explore-path
          :owner "jake"
          :related-plans (mapv #(select-keys % [:slug :title :status]) (:plans existing))
          :gather-question nil
          :refuse-reason nil})

(println pre)
```
````

### Iteration 2 — AUTHOR (tool channel)

```clojure
(def authored
  (let [body (str "## Context\n…\n\n"
                  "## Approach\n"
                  "- Wire the LD flag `checkout-v2` in `src/checkout/flags.clj`.\n"
                  "- Update the payment validator to short-circuit when flag off.\n"
                  "- Migrate the cart serializer to the v2 envelope.\n"
                  "- Add migration tests covering legacy carts.\n"
                  "- Validate p99 latency against the Grafana board.\n\n"
                  "## Risks / Open Questions\n"
                  "- Payment-validator regression on legacy carts (named).\n"
                  "- Cart-serializer schema drift if v1 producers persist longer than expected.\n"
                  "- No open questions.\n\n"
                  "## References\n"
                  "- file:src/checkout/flags.clj\n"
                  "- file:src/checkout/payment_validator.clj\n"
                  "- file:src/checkout/cart.clj\n"
                  "- exploration:" (:exploration-path pre) "\n\n"
                  "## Acceptance\n"
                  "- Feature-flag `checkout-v2` toggleable from the staging admin.\n"
                  "- All `checkout/*` unit tests green.\n"
                  "- p99 checkout latency unchanged within ±5%.\n")]
    (call-tool "plan$create" {:title "Ship v2 checkout"
                              :body  body
                              :scope :project})))

(def authored {:slug   (:slug authored)
               :path   (:file-path authored)
               :action :created})
```

### Iteration 3 — POST-FLIGHT (code channel)

```clojure
(def plan-body (:body (call-tool "plan$read" {:slug (:slug authored)})))

;; R1 / R2 / R6 — query$llm
(def llm-rubric
  (:result (query$llm
             :prompt (str "Score this plan body against R1 (approach actionable, "
                          "3-15 verb-led bullets), R2 (acceptance observable), and "
                          "R6 (no contradictions between approach / references / "
                          "acceptance). Return EDN: "
                          "{:r1 :pass|:fail :r2 :pass|:fail :r6 :pass|:fail "
                          " :notes \"...\"}.\n\n" plan-body))))

;; R3 — references resolve
(def r3
  (let [paths (re-seq #"file:(\S+)" plan-body)]
    (if (every? #(zero? (:exit-code (call-tool "bash" {:command (str "test -f " (second %))})))
                paths)
      :pass :fail)))

;; R4 — risks specific (length + named anchors)
(def r4
  (if (and (re-find #"## Risks" plan-body)
           (>= (count (re-seq #"\n- " (re-find #"(?s)## Risks.*?(?=\n## )" plan-body))) 1))
    :pass :fail))

;; R5 — scope (count of approach bullets in [3,30])
(def r5
  (let [n (count (re-seq #"\n- "
                  (re-find #"(?s)## Approach.*?(?=\n## )" plan-body)))]
    (if (<= 3 n 30) :pass :fail)))

;; R7 — no dangling artifacts
(def r7
  (if (re-find #"\b(TODO|tk|\?\?\?|<\.{3}>|\[fill in\])\b" plan-body) :fail :pass))

(def post
  {:verdict (if (every? #(= :pass %) [(:r1 llm-rubric) (:r2 llm-rubric) r3 r4 r5
                                       (:r6 llm-rubric) r7])
              :pass :revise)
   :rubric {:r1 (:r1 llm-rubric) :r2 (:r2 llm-rubric) :r3 r3 :r4 r4 :r5 r5
            :r6 (:r6 llm-rubric) :r7 r7}
   :revision-applied? false
   :revision-summary nil
   :holds []
   :acceptance ["feature-flag checkout-v2 toggleable from staging admin"
                "all checkout/* unit tests green"
                "p99 checkout latency unchanged within ±5%"]})
```

### Iteration 4 — PERSIST (code channel)

```clojure
(def fm (:frontmatter
          (plan$dossier-frontmatter
            :slug    (:slug authored)
            :pre     pre
            :author  authored
            :post    post
            :handoff {:next-agent "todo-agent"
                      :next-call  (str "(call-tool \"todo-agent\" "
                                       "{:question \"Spawn a todo for this plan.\" "
                                       ":agent-context \"<this dossier path>\"})")})))

(def body
  (str "# Plan dossier — Ship v2 checkout\n\n"
       "## Pre-flight summary\n…\n\n"
       "## Plan summary (extracted)\n…\n\n"
       "## Post-flight notes\nAll rubric items passed on the first pass.\n\n"
       "## Handoff\nPass `<this dossier path>` to todo-agent in `:agent-context`.\n"))

(def res (plan$dossier-write :slug (:slug authored) :content (str fm body)))

(plan$dossier-index-append :path (:path res) :slug (:slug authored)
                           :pre-verdict :go :post-verdict :pass
                           :next-agent "todo-agent")
```

### Iteration 5 — ANSWER

```markdown
## Plan authored — Ship v2 checkout

PRE-FLIGHT: GO (all 7 checks passed; exploration consumed)
AUTHOR: created (.brainyard/agents/plan-agent/plans/ship-v2-checkout.md, 4128 bytes)
POST-FLIGHT: PASS (rubric R1–R7 all passed; no revision needed)

Acceptance criteria (handoff to todo-agent / eval-agent):
- feature-flag `checkout-v2` toggleable from the staging admin
- all `checkout/*` unit tests green
- p99 checkout latency unchanged within ±5%

Saved plan: .brainyard/agents/plan-agent/plans/ship-v2-checkout.md
Saved dossier: .brainyard/agents/plan-agent/dossiers/20260510-104503-ship-v2-checkout.md
Next: (call-tool "todo-agent" {:question "Spawn a todo for this plan." :agent-context ".brainyard/agents/plan-agent/dossiers/20260510-104503-ship-v2-checkout.md"})
```

A downstream `todo-agent` invocation reads the dossier via `plan$read-dossier` (frontmatter only — cheap), pulls `acceptance` directly into its own `## Acceptance` reference block, and uses the plan body via the `plan_path` field only when authoring item descriptions.

---

## 12. Optional `(plan$*)` Dossier Helpers

Live in `ai.brainyard.agent.common.plan` (alongside the existing `plan$create` / `plan$read` / etc.), registered as `defcommand`s, surfaced via the auto-binding path. Optional — the agent works without them, as in §11 — but they compress per-turn boilerplate.

| Helper                          | Signature                                                                                                                       | What it does                                                                                                                |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `plan$dossier-slug`             | `(plan$dossier-slug :question "<text>") → {:slug "..."}`                                                                       | Deterministic kebab-case from the request — for GATHER/REFUSE turns where no plan slug exists yet.                          |
| `plan$dossier-frontmatter`      | `(plan$dossier-frontmatter :slug … :pre {…} :author {…} :post {…} :handoff {…}) → {:frontmatter "..."}`                       | Build the YAML block per §7.2 schema. Trailing newline included so body can be concatenated directly.                       |
| `plan$dossier-write`            | `(plan$dossier-write :slug "..." :content "...") → {:path :slug :ts}`                                                         | Write to `.brainyard/agents/plan-agent/dossiers/<ts>-<slug>.md`. Auto-suffix on collision.                                         |
| `plan$dossier-index-append`     | `(plan$dossier-index-append :path … :slug … :pre-verdict :go :post-verdict :pass :next-agent "todo-agent") → {:appended true}` | Prepend one line to `.brainyard/agents/plan-agent/INDEX.md`.                                                                       |
| `plan$read-dossier`             | `(plan$read-dossier :path "...") → parsed map`                                                                                  | Cheap parse — frontmatter only. Used by todo-agent / exec-agent / eval-agent.                                                |
| `plan$next-handoff`             | `(plan$next-handoff :pre {…} :post {…} :slug …) → {:next-agent "..." :next-call "..."}`                                        | Single source of truth for "what to recommend next."                                                                         |
| `plan$preflight`                | `(plan$preflight :question … :agent-context …) → pre map (§4.3)`                                                                | The whole pre-flight as one call. Internally runs C1–C7. Returns the stash-ready `pre` map.                                  |
| `plan$postflight`               | `(plan$postflight :slug … :pre …) → post map (§6.3)`                                                                            | The whole post-flight as one call. Internally re-reads, runs R1–R7, applies one revise round if needed, returns `post`.     |

When `plan$preflight` and `plan$postflight` are bound, the §11 demonstration collapses to roughly:

```clojure
(def pre  (plan$preflight :question question :agent-context agent-context))

(def authored
  (when (= :go (:verdict pre))
    (let [body  (plan$render-body :pre pre :goal question)
          {:keys [slug file-path]}
            (call-tool "plan$create" {:title "Ship v2 checkout"
                                      :body  body
                                      :scope :project})]
      {:slug slug :path file-path :action :created})))

(def post (when authored (plan$postflight :slug (:slug authored) :pre pre)))

(def res
  (plan$dossier-write
    :slug (or (:slug authored)
              (:slug (plan$dossier-slug :question question)))
    :content (str (:frontmatter (plan$dossier-frontmatter
                                  :slug (:slug authored)
                                  :pre pre :author authored :post post
                                  :handoff (plan$next-handoff :pre pre :post post
                                                              :slug (:slug authored))))
                  "<dossier body>")))

(plan$dossier-index-append :path (:path res) :slug (or (:slug authored) "_no-plan")
                           :pre-verdict (:verdict pre)
                           :post-verdict (or (:verdict post) :n-a)
                           :next-agent (cond
                                         (= :pass (:verdict post)) "todo-agent"
                                         (= :hold (:verdict post)) "user"
                                         :else "user"))
```

Keeping the helpers thin lets the LLM pick the orchestration; they're not a state machine, just a way to keep the YAML and the path conventions in one place.

---

## 13. Handoff Mechanics — How Other Agents Consume Dossiers

The contract between plan-agent and downstream agents is **just the dossier path**.

### 13.1 The `Saved dossier:` and `Next:` Lines

Every answer ends with the lines listed in §7.5. The dispatcher (or user) can grep `^Saved dossier: ` to extract the path; `Next:` provides the exact `call-tool` form to copy-paste.

### 13.2 Two Levels of Cheap Read

**Cheap (~700 bytes):** read just the frontmatter via `plan$read-dossier`.

```clojure
(def md (plan$read-dossier :path "<path>"))
;; → {:slug … :plan_path … :pre {…} :post {…} :handoff {…}
;;    :acceptance ["..."] …}
```

**Full (~3–8 KB typically):** `read-file` the whole dossier when the prose summary is needed.

### 13.3 Per-downstream-agent usage

| Agent       | What it reads from the dossier                                                                                                  |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------- |
| todo-agent  | `plan_path` (to `plan$read` the body for `## Approach`); `post.acceptance` (to seed its own acceptance reference); `pre.exploration-path` (carried forward); `slug` (default todo slug). |
| exec-agent  | `plan_path` (to read plan during execution); `post.acceptance` (to know what "done" means item-by-item).                         |
| eval-agent  | `plan_path` + `post.acceptance` (the criterion list it scores against — read directly from the dossier, no plan-body parse).     |
| user        | The `## Pre-flight summary` and `## Post-flight notes` body sections — human-readable status of the plan at this turn.           |

### 13.4 Cross-Reference From Trajectory

`coact-store-results-action` (`docs/CoAct.md` §7) writes a trajectory record per turn. Add one optional field:

```clojure
{... existing trajectory keys ...
 :plan-dossier-path ".brainyard/agents/plan-agent/dossiers/20260510-..."}
```

This lets the trajectory inspector and future analytics correlate trajectories with their dossiers without re-parsing the answer text.

---

## 14. Migration Plan — `.brainyard/plans/` → `.brainyard/agents/plan-agent/plans/`

The path change is mechanical but touches the `plan$*` commands and any callers (todo-agent, exec-agent, eval-agent, the autoresearch orchestrator). Roll out in phases so existing local checkouts keep working.

### Phase 0 — Land plan-agent v2

- New `plan_agent.clj` with the three-phase pipeline (§4–§6).
- New helpers in `plan.clj`: `plan$dossier-frontmatter`, `plan$dossier-write`, `plan$dossier-index-append`, `plan$read-dossier`, `plan$next-handoff`, `plan$preflight`, `plan$postflight`.
- `plan$create` / `plan$read` / `plan$update-body` modified to:
  - WRITE to `.brainyard/agents/plan-agent/plans/<slug>.md`.
  - READ from `.brainyard/agents/plan-agent/plans/<slug>.md` first; fall back to `.brainyard/plans/<slug>.md` when absent.
  - `plan$list` enumerates BOTH locations and tags each with `:layout :new` or `:layout :legacy` so callers can see the migration state at a glance.
- New `.brainyard/agents/plan-agent/` directory layout with `INDEX.md` template + `README.md`.
- Tests:
  - registration smoke test
  - PRE-FLIGHT GO happy path
  - PRE-FLIGHT GATHER (missing exploration)
  - PRE-FLIGHT REFUSE (single-edit redirect to edit-agent)
  - POST-FLIGHT PASS
  - POST-FLIGHT REVISE (one auto-revision round)
  - POST-FLIGHT HOLD (R5 says split)
  - dossier round-trip (write → read-dossier → assert keys)
  - dual-read: plan written to legacy path is still readable by `plan$read`

### Phase 1 — One-shot file migration

- New `bb migrate:plan-agent` task copies every file under `.brainyard/plans/` to `.brainyard/agents/plan-agent/plans/`, preserving frontmatter timestamps.
- Source files are NOT deleted — left in place so a `git checkout` rolls back instantly.
- The task reports a summary: N files moved, K duplicates skipped (when both locations had the same slug), 0 failures.
- Document in `docs/agent-design.md` and the agent-tui-app series.

### Phase 2 — Update sibling agents

- todo-agent / exec-agent / eval-agent updated per their own design docs (`docs/todo-agent-design.md`, `docs/exec-agent-design.md`, `docs/eval-agent-design.md`) to:
  - Accept a dossier path in `:agent-context` (not just a plan slug).
  - Read `acceptance` and `plan_path` from `plan$read-dossier`.
  - Emit their own dossiers under `.brainyard/<agent>/dossiers/`.
- AUTORESEARCH orchestrator reads dossiers between stages so the four-agent pipeline `plan → todo → exec → eval` is dossier-threaded end to end.

### Phase 3 — Soft-deprecate legacy path

- `plan$create` writing to legacy path is removed at Phase 0 already. At Phase 3, `plan$read` legacy fallback emits a one-time warning when consulted: "plan X is in `.brainyard/plans/`; run `bb migrate:plan-agent` to move it."
- The warning suppresses for test fixtures (env flag).

### Phase 4 — Hard-deprecate

- `plan$read` legacy fallback removed. `plan$list` no longer scans `.brainyard/plans/`.
- Release note: "`.brainyard/plans/` no longer read in vX.Y. If you have a local checkout still holding plans there, run `bb migrate:plan-agent` once."

### Phase Acceptance Gates

| Phase → Phase | Gate                                                                                                                          |
| ------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| 0 → 1         | All Phase 0 tests green; manual smoke on a real authoring turn produces dossier per §7.2.                                     |
| 1 → 2         | `bb migrate:plan-agent` run cleanly on a project with ≥ 5 legacy plans; dual-read still works after migration.                |
| 2 → 3         | Sibling agents shipped; one full `plan → todo → exec → eval` pipeline run end-to-end with dossiers threaded through.          |
| 3 → 4         | Two minor releases since Phase 2; no escalation tickets tagged `agent:plan` referencing the legacy fallback.                  |

---

## 15. Verification

Add benchmark cases targeting plan-agent's specific contract.

| Benchmark                              | Shape                                                                                                | What it verifies                                                                                                                  |
| -------------------------------------- | ---------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| Pre-flight GO happy path               | Clear request + `:agent-context` with valid `Saved exploration:` + all refs exist                   | All C1–C7 pass; AUTHOR runs; POST-FLIGHT passes; one dossier with `pre.verdict :go` + `post.verdict :pass`.                       |
| Pre-flight GATHER (missing exploration) | Request mentions specific files; no `Saved exploration:` in context                                  | C3 fails; agent recommends `(call-tool "explore-agent" …)` in answer; dossier records `pre.verdict :gather`; NO plan body written. |
| Pre-flight GATHER (goal unclear)       | One-sentence ambiguous request                                                                       | C1 fails; ONE multi-choice question surfaced; dossier records `gather-question`.                                                  |
| Pre-flight REFUSE (single edit)        | "Rename foo→bar in src/x.clj"                                                                        | C5 fails; agent refuses with redirect to edit-agent; dossier records refuse_reason.                                              |
| Pre-flight REFUSE (duplicate plan)     | Existing plan with same slug already in `.brainyard/agents/plan-agent/plans/`                              | C2 fails; agent points at the existing plan.                                                                                      |
| Post-flight PASS                       | Pre-flight GO + LLM authors a clean plan                                                             | All R1–R7 pass first try; no revision; dossier `post.verdict :pass`.                                                              |
| Post-flight REVISE (R3 fail)           | LLM cites `src/foo.clj` that does not exist                                                          | R3 fails; one auto-revision removes the bad reference; second pass passes; dossier records `revision-applied? true`.              |
| Post-flight HOLD (R5 says split)       | Request implies 60 actions                                                                           | R5 fails; agent recommends plan-of-plans split in dossier `holds`; answer includes `Hold:` line; NO `Next:` to todo-agent.        |
| Dual-read fallback                     | Legacy plan at `.brainyard/plans/<slug>.md`; new agent reads it                                      | `plan$read` returns the body; `plan$list` tags it `:layout :legacy`.                                                              |
| Migration                              | `bb migrate:plan-agent` against a fixture with 5 legacy plans                                        | All 5 copied to new location; old files preserved; dual-read still works.                                                         |
| Dossier round-trip                     | Write a dossier; `plan$read-dossier` it back; assert all frontmatter fields present                  | Schema (§7.2) is honored 1:1.                                                                                                     |
| Index integrity                        | Append 100 dossiers                                                                                  | INDEX.md has 100 lines, newest first; no entries lost or rewritten.                                                               |
| Trajectory linkage                     | Run a plan-agent turn; inspect the trajectory record                                                 | `:plan-dossier-path` field present and points at the produced dossier.                                                            |

Per-iteration mulog signals (mirroring the `::explore.*` and `::update.*` pattern):

- `::plan.preflight`     — `{:slug … :verdict … :checks {…} :elapsed-ms N}`
- `::plan.author`        — `{:slug … :action :created|:updated :body-bytes N :elapsed-ms N}`
- `::plan.postflight`    — `{:slug … :verdict … :rubric {…} :revision-applied? :elapsed-ms N}`
- `::plan.dossier-write` — `{:slug … :path … :bytes N}`
- `::plan.handoff`       — `{:slug … :next-agent …}`

These are `mulog/log` calls in the helpers (§12) — no agent-loop changes required.

---

## 16. Files Summary

| File                                                                                              | What changes                                                                                                                                                                                                           |
| ------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `components/agent/src/ai/brainyard/agent/common/plan_agent.clj`                                   | REDESIGNED — three-phase pipeline (PRE-FLIGHT / AUTHOR / POST-FLIGHT), new instruction + tool-context per §8 / §9, dossier emission per §7.                                                                            |
| `components/agent/src/ai/brainyard/agent/common/plan.clj`                                         | EXTENDED — `plan$create` / `plan$read` / `plan$update-body` write to new path, dual-read legacy. Add `plan$dossier-frontmatter`, `plan$dossier-write`, `plan$dossier-index-append`, `plan$read-dossier`, `plan$next-handoff`, `plan$preflight`, `plan$postflight` helpers. |
| `components/agent/test/ai/brainyard/agent/plan_agent_test.clj`                                    | EXTENDED — new tests per §15.                                                                                                                                                                                          |
| `.brainyard/agents/plan-agent/README.md`                                                                 | NEW (templated by helpers on first write) — directory layout cheat-sheet.                                                                                                                                              |
| `bb.edn`                                                                                          | NEW task `migrate:plan-agent` — Phase 1 file move script.                                                                                                                                                              |
| `docs/plan-agent-design.md`                                                                       | THIS FILE.                                                                                                                                                                                                             |
| `docs/agent-design.md` / `docs/AUTORESEARCH.md`                                                   | TOUCHED — references to `.brainyard/plans/` updated to `.brainyard/agents/plan-agent/plans/`; pipeline diagram updated to thread dossiers.                                                                                    |
| `components/agent/src/ai/brainyard/agent/common/{todo_agent,exec_agent,eval_agent}.clj`           | TOUCHED at Phase 2 (per their own design docs) to consume plan dossier in `:agent-context`.                                                                                                                            |
| `components/agent/src/ai/brainyard/agent/common/coact_agent.clj`                                  | NO CHANGES.                                                                                                                                                                                                            |

The whole feature ships as one redesigned agent file, one extended helpers file, one bb task, and a sibling-agent update wave. The CoAct loop, sandbox, BT, and DSPy signature — untouched.

---

## 17. Open Questions

1. **Should pre-flight C3 auto-dispatch `explore-agent` by default?** Today's design says no — it recommends. Auto-dispatch saves a turn but spends `explore-agent`'s iteration budget under plan-agent's hood, which complicates per-agent budgeting and observability. Suggestion: keep recommend-by-default; opt-in via `:auto-explore? true`. Revisit if benchmarks show GATHER turns dominate.
2. **Post-flight revision budget.** Hard limit of one auto-round. A cheaper alternative: budget by tokens (e.g., spend at most N tokens on revision; if rubric still fails, HOLD). Cleaner numerically but harder to reason about. Stick with the one-round rule for v1.
3. **Should the dossier carry the FULL plan body inline?** Tradeoff: simpler downstream consumption (one file, one read) vs. duplication when the plan is large + risk of dossier and plan diverging. Decision: keep them separate; downstream `read-file plan_path` when needed. Revisit if downstream agents commonly need both.
4. **Owner field — should pre-flight C7 be a hard block?** Many internal projects don't have a single owner. Today the design says INFORMATIONAL (no block). Could be promoted to GATHER for production-touching plans (heuristic: any reference path under `prod/`, `production/`, `live/`). Revisit when a real-world plan-agent run hits an unowned production change.
5. **Should the dossier reference prior dossiers for the same slug?** A `pre.related_plans` field already lists same-area drafts. Adding `pre.prior_dossiers: [<paths>]` would let users follow the lineage of a plan over multiple turns. Cheap to add; defer until users ask.
6. **How does plan-agent participate in `autoresearch`?** Today the orchestrator runs the four-agent pipeline at each strategy iteration. With dossiers in place, `autoresearch$keep-or-discard` can read the eval-agent dossier directly to score, rather than parsing exec-agent's :answer. That's a follow-up doc (`docs/AUTORESEARCH.md` revision) — out of scope here.
