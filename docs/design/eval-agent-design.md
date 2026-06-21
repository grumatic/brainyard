# Eval-Agent — Pre-flight & Post-flight Gated Verdict Production with Dossier Handoff (CoAct-derived)

> **Status:** Shipped — `eval-agent` is registered in `components/agent` (`common/eval_agent.clj`). This document is the original design proposal (revision 2); the shipped implementation may diverge in details. See [core/agent.md](../core/agent.md) for the current roster.
>
> **As-built (verify against `common/eval_agent.clj`, `common/eval.clj`):**
> - **POST-FLIGHT verdict is PASS / HOLD only.** The design's REVISE auto-round (§6.2) is **deferred to v1.5**.
> - **`:checkbox-only-ok?` is not yet supported.** C3 (no evidence) → REFUSE unconditionally in v1; the design's opt-in fallback is not wired.
> - **Cross-agent dispatch is direct kebab-case** — `(plan-agent {…})`, `(exec-agent {…})` — not `call-tool`. Hard Rule 4 reads "NO clone-self dispatch." Recommendations name `doc$update :kind :todo …` (e.g. `:add-item`, `:item-done false`, `:status :completed`), not the retired `todo$*` shims.
> - **Verdict body + dossier both ship**, as designed (`eval$verdict-write` writes `verdicts/`; `eval$dossier-write` writes `dossiers/`). `update$read-record` is cherry-picked read-only for criterion→item→diff drill-down (as designed).
> - **Shipped helper roster:** `eval$dossier-slug`, `eval$verdict-write`, `eval$dossier-frontmatter`, `eval$dossier-write`, `eval$dossier-index-append`, `eval$read-dossier`, `eval$find`, `eval$next-handoff`. No `eval$preflight` / `eval$postflight` / `eval$score-criterion` helpers shipped (§12's list is aspirational).
> - **An `:agent.ask/post` auto-persist hook** (not in this design) writes a minimal dossier from the answer text if `eval$dossier-write` was skipped; the verdict body is NOT auto-persisted (call `eval$verdict-write` explicitly).
> **Scope:** redesign of `components/agent/src/ai/brainyard/agent/common/eval_agent.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Sibling of:** `plan-agent`, `todo-agent`, `exec-agent`, `update-agent`, `explore-agent`
> **Related reading:** `docs/plan-agent-design.md` (acceptance comes from the plan dossier), `docs/todo-agent-design.md` (acceptance coverage map), `docs/exec-agent-design.md` (per-item evidence comes from the exec dossier), `docs/update-agent-design.md` (drill-down from evidence to diffs)

> **API rename (2026-05):** the per-verb `todo$*` and `plan$*` CRUD shims have been removed in favour of the polymorphic `doc$*` family with `:kind :todo` / `:kind :plan`. See `docs/design/todo-agent-design.md` (frontmatter note) for the verb-by-verb mapping. The dossier helpers (`todo$read-dossier`, `plan$read-dossier`, etc.) are NOT deprecated. The body below still uses the old names for historical clarity.

---

## 1. Motivation

The current `eval-agent` (`components/agent/.../common/eval_agent.clj`) judges whether an executed todo satisfied its source plan's acceptance criteria. It reads the plan, reads the todo, parses an "exec evidence stream" out of `:agent-context` (typically copied from exec-agent's prior `:answer`), and renders a verdict. Three problems surface:

1. **The evidence channel is fragile.** Today eval-agent expects exec-agent's `:answer` to be pasted into `:agent-context` after a `## Exec Evidence` separator. If the dispatcher forgets to include it, eval-agent silently degrades to checkbox-only scoring and quietly hand-waves the verdict. There is no pre-flight check on whether the input is actually scoreable.
2. **Acceptance criteria are re-derived from markdown.** Eval-agent re-parses the plan body to find `## Acceptance` and split it into criteria. Plan-agent (`docs/plan-agent-design.md`) now ships acceptance as a structured `post.acceptance` field in its dossier; todo-agent ships an `acceptance_coverage` map; exec-agent ships an `acceptance_progress` map. Eval-agent should consume those directly and only re-parse markdown when an upstream agent failed to populate them.
3. **The verdict has no on-disk artifact.** Today the verdict lives in `:answer` and disappears when the chat trajectory rotates. There is no `.brainyard/agents/eval-agent/verdicts/<slug>-<ts>.md` to look back at "what did eval say last week about the checkout-v2 launch?". And there is no schema'd handoff to whoever should act on the verdict — `plan-agent` for re-spec, `todo-agent` for re-shape, `exec-agent` to resume — so the recommendation is freeform prose that the dispatcher has to parse manually.

The same redesign also folds in the layout move begun by plan-agent / todo-agent / exec-agent. Today eval-agent has no on-disk artifact at all. The redesign adds `.brainyard/agents/eval-agent/verdicts/<slug>-<ts>.md` (the verdict body, human-readable) and `.brainyard/agents/eval-agent/dossiers/<slug>-<ts>.md` (the schema'd handoff).

**Thesis.** Redesign `eval-agent` so every scoring run runs through a fixed three-phase pipeline:

1. **PRE-FLIGHT (sufficiency check)** — does the agent have everything it needs to score? Plan dossier with `post.acceptance` populated, exec dossier with `execute.evidence` populated, optional update-agent records for diff-level drill-down. Output: GO / GATHER / REFUSE.
2. **SCORE** — for each acceptance criterion, classify SATISFIED / PARTIAL / MISSING / CONTRADICTED based on the evidence map; render the overall verdict ACHIEVED / PARTIALLY_ACHIEVED / NOT_ACHIEVED; assemble per-criterion follow-up recommendations.
3. **POST-FLIGHT (confirmation check)** — every criterion classified? evidence cited? recommendations name a concrete tool call? confidence noted when fuzzy LLM judgement was used? Output: PASS / REVISE (re-score with stronger evidence) / HOLD (require user adjudication).

Every run produces a verdict file AND a dossier. The dossier is what `plan-agent` / `todo-agent` / `exec-agent` consume when the verdict triggers re-spec / re-shape / resume.

Same minimal-diff principle. CoAct loop, sandbox, BT, DSPy untouched.

---

## 2. Design Principles

1. **No verdict on insufficient evidence.** Pre-flight refuses when the exec dossier is missing or its `execute.evidence` map is empty. Eval-agent never invents satisfaction from a checked box alone — degrading to checkbox-only is a deliberate fallback the user must opt into (`:checkbox-only-ok? true`), not a silent default.
2. **Structured fields first; markdown re-parse only as fallback.** Read `post.acceptance` from the plan dossier, `post.acceptance_coverage` from the todo dossier, `post.acceptance_progress` and `execute.evidence` from the exec dossier. Re-parse markdown only when those fields are absent (legacy data).
3. **Drill from criterion → item → evidence → diff.** The classification table is a navigation tree. SATISFIED entries cite the item idx, the evidence excerpt, AND when applicable the underlying update-agent record path so a future reader can audit the actual change.
4. **Verdict is a recommendation, not an action.** Eval-agent NEVER auto-dispatches plan-agent / todo-agent / exec-agent. The dossier names the next agent + the exact `(call-tool …)` form; the user (or the orchestrator) decides whether to invoke it. Sub-dispatch is allowed ONLY when the user explicitly says "and apply the recommendation" in the same turn.
5. **Confidence is a first-class field.** Every criterion classification carries a confidence enum (`:high :medium :low`) reflecting whether the evidence was concrete (file diff, test exit code) or fuzzy (LLM-judged narrative). Aggregate confidence appears on the verdict itself.
6. **Layout matches the rest of the ecosystem.** Verdicts at `.brainyard/agents/eval-agent/verdicts/`, dossiers at `.brainyard/agents/eval-agent/dossiers/`.
7. **Plans, todos, and exec records are all read-only.** Eval-agent NEVER mutates anything. The only writes are to `.brainyard/agents/eval-agent/`.
8. **No clone-self recursion.** No `query$clone`. Cross-agent dispatch via `(call-tool …)` is fine — for `query$llm` (synthesis), for the eventual recommendation execution (only with explicit user opt-in).

---

## 3. Position in the Agent Stack

See `docs/plan-agent-design.md` §3 for the full pipeline diagram. Eval-agent sits in the fourth and final slot:

```
plan-agent → todo-agent → exec-agent → Saved dossier: <exec dossier path>
                                                    │
                                                    ▼
eval-agent → Saved verdict: <verdict path>
            → Saved dossier: <eval-agent dossier path>
            → Verdict: ACHIEVED | PARTIALLY_ACHIEVED | NOT_ACHIEVED
            → (recommendations point back to one of plan-agent /
               todo-agent / exec-agent for the next turn)
```

When the verdict is NOT_ACHIEVED or PARTIALLY_ACHIEVED, the recommendation forms the next pipeline iteration:

```
NOT_ACHIEVED, scope shifted   → recommend plan-agent (plan$update-body)
NOT_ACHIEVED, items missing   → recommend todo-agent (todo$add-item)
NOT_ACHIEVED, items failed    → recommend exec-agent (resume from item K)
PARTIALLY_ACHIEVED, accept    → recommend todo$complete (user confirms)
ACHIEVED                      → recommend todo$complete + plan$complete
```

This is what makes the four-agent pipeline a feedback loop rather than a linear assembly line.

---

## 4. PRE-FLIGHT — Sufficiency Check (NEW)

Runs before any scoring work. Walks a fixed checklist; produces a `pre` map.

### 4.1 The Checklist

| Check | What it verifies                                                                                                          | How                                                                                                                                                                                              | Fail → action                                                                                                                                              |
| ----- | ------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| C1    | An exec-agent dossier was supplied.                                                                                       | Look for `Saved dossier: <path>` in `:agent-context`. The path's frontmatter `agent` must be `exec-agent`.                                                                                       | GATHER — recommend `(call-tool "exec-agent" {…})` first OR ask user to paste the dossier path.                                                             |
| C2    | The exec dossier's post-flight passed.                                                                                    | `exec$read-dossier :path <path>` → check `post.verdict = :pass`.                                                                                                                                 | If `:hold` → score with a reduced confidence and call this out in the verdict; do NOT REFUSE (eval still has value).                                       |
| C3    | The exec dossier carries `execute.evidence` with at least one item.                                                       | Inspect the `execute.evidence` map.                                                                                                                                                              | REFUSE unless `:checkbox-only-ok? true` was passed.                                                                                                        |
| C4    | The plan-agent dossier referenced by the exec dossier exists, post.verdict = :pass, AND `post.acceptance` is populated.   | Read `plan_dossier`; `plan$read-dossier`; assert `post.verdict :pass` and `(seq post.acceptance)`.                                                                                                | If acceptance empty → GATHER (recommend plan-agent re-author with explicit ## Acceptance). If post HOLD → score but mark verdict confidence :low.          |
| C5    | The todo-agent dossier (when present) carries `post.acceptance_coverage`.                                                 | Read `todo_dossier`; `todo$read-dossier`; assert `(seq post.acceptance_coverage)`.                                                                                                                | INFORMATIONAL — coverage map is a convenience; eval can rebuild it from evidence + acceptance via `query$llm`. Note in dossier when this happens.            |
| C6    | The cited update-agent records (when present) actually exist on disk.                                                     | For each `evidence.path` in the exec dossier, `bash "test -f <path>"`.                                                                                                                            | GATHER — list the missing record paths; the exec dossier may be stale.                                                                                     |
| C7    | No earlier eval-agent dossier for this slug+turn already exists (avoid double-scoring).                                   | `(eval$find :slug <slug> :run-record <run record>)` returns prior verdicts on the same exec turn.                                                                                                | INFORMATIONAL — surface "already scored on <ts>; re-running per user request"; record `pre.is_re_run :true`.                                               |

Same short-circuit rule as the other agents. Critically, C2 / C4 produce *softer* verdict-level effects (reduced confidence) rather than hard refusals — eval-agent is the last line of defense and a degraded verdict is more useful than no verdict.

### 4.2 The `pre` Map

```clojure
(def pre
  {:verdict             :go
   :checks              {:c1 :pass :c2 :pass :c3 :pass :c4 :pass
                         :c5 :pass :c6 :pass :c7 :informational}
   :exec-dossier        ".brainyard/agents/exec-agent/dossiers/...md"
   :exec-run-record     ".brainyard/agents/exec-agent/runs/...md"
   :todo-dossier        ".brainyard/agents/todo-agent/dossiers/...md"
   :plan-dossier        ".brainyard/agents/plan-agent/dossiers/...md"
   :plan-path           ".brainyard/agents/plan-agent/plans/<slug>.md"
   :todo-path           ".brainyard/agents/todo-agent/todos/<slug>.md"
   :slug                "ship-v2-checkout"
   :acceptance          ["criterion 1" "criterion 2" "criterion 3"]
   :acceptance-coverage {"criterion 1" [0] "criterion 2" [1 2] "criterion 3" [3]}
   :evidence            {0 {…} 1 {…} 2 {…} 3 {…}}
   :degradation         []          ; e.g. [:exec-hold :no-coverage-map]
   :is-re-run           false
   :gather-question     nil
   :refuse-reason       nil})
```

`pre.acceptance`, `pre.acceptance-coverage`, and `pre.evidence` are direct reads from upstream dossiers. The `pre.degradation` vector lists every soft-failure that downgrades verdict confidence — surfaced explicitly in the answer.

---

## 5. SCORE — Core Operation

For each criterion in `pre.acceptance`, classify into one of:

- **SATISFIED** — at least one evidence record clearly demonstrates the criterion is met.
- **PARTIAL** — evidence shows the criterion is partly met (some sub-condition holds, others don't).
- **MISSING** — no evidence speaks to this criterion. Execution simply did not address it.
- **CONTRADICTED** — evidence shows the criterion is NOT met (test failure, metric moved wrong way, deferred follow-up that violates acceptance, OR a flipped checkbox without supporting evidence).

### 5.1 Classification Procedure

For each criterion C in `pre.acceptance`:

1. **Find candidate items.** Read `pre.acceptance-coverage[C]` for the explicit list of item idxs. If empty (degradation), use `query$llm` to fuzzy-match items against C from `pre.acceptance`.
2. **Pull evidence for each candidate.** From `pre.evidence[idx]`, extract:
   - `evidence.ok?` — boolean.
   - `evidence.type` — `:update-agent` / `:bash` / `:mcp` / `:explore-agent` / `:read-only` / `:manual`.
   - For `:update-agent`, drill: `update$read-record :path <evidence.path>`. Read `verify.diff_match`, `verify.lint`, `verify.tests` if present.
   - For `:bash`, read `evidence.exit` and `evidence.stdout-tail`.
   - For `:manual`, read the user's supplied result (when available) or note `manual-pending`.
3. **Classify.**
   - All candidate items `ok? true` AND evidence concretely demonstrates C → SATISFIED, confidence `:high` for diff/test evidence, `:medium` for narrative.
   - At least one item `ok? true` and at least one `ok? false` / `manual-pending` → PARTIAL.
   - Zero candidate items, OR all candidates `manual-pending` → MISSING.
   - Any candidate item with `ok? false` AND no compensating successful coverage → CONTRADICTED. (Plus: a flipped checkbox in the todo that has no corresponding evidence record → CONTRADICTED with the note "checkbox flipped without evidence — exec-agent post-flight should have caught this.")
4. **Cite.** Record:
   - the contributing item idxs;
   - a short evidence excerpt (file:path:line, exit code, stdout snippet, mcp tool result, or update-agent record path);
   - confidence (`:high :medium :low`).
5. **Use `query$llm` for fuzzy criterion language.** Criteria like "users find checkout intuitive" are inherently fuzzy. Use `query$llm` to weigh the evidence narrative against the criterion text; record the prompt + a one-line summary in the dossier so the judgement is auditable.

### 5.2 Verdict Aggregation

```text
Every criterion SATISFIED                                → ACHIEVED
At least one SATISFIED, no CONTRADICTED, no MISSING      → ACHIEVED (when MISSING is empty AND PARTIAL is fine)
At least one SATISFIED, no CONTRADICTED, some MISSING    → PARTIALLY_ACHIEVED
At least one CONTRADICTED OR no SATISFIED at all         → NOT_ACHIEVED
```

Aggregate confidence:
- All criteria :high → verdict :high.
- Any :low → verdict :low.
- Otherwise → :medium.

### 5.3 Recommendations

For each PARTIAL / MISSING / CONTRADICTED criterion, name the cheapest viable next step AND an exact tool call:

- **Spec is wrong / scope shifted** → `plan-agent` with `plan$update-body` to revise `## Approach` + `## Acceptance`.
- **Spec is right, items missing or coarse** → `todo-agent` with `todo$add-item :slug … :description "…"`.
- **Spec is right, item failed mid-flight** → `exec-agent` with the exec dossier path; recommend `todo$reset-item` first if there's a partial state.
- **Partial result is acceptable as-is** → `todo$complete` with a user confirmation hint.

The recommendations are a vector of maps:

```clojure
(def recs
  [{:criterion "p99 checkout latency unchanged within ±5%"
    :gap        "manual sample missing"
    :next-agent "exec-agent"
    :next-call  "(call-tool \"exec-agent\" {:question \"Resume; user supplied p99 = 142ms.\" :agent-context \"<exec dossier path>\"})"}
   {:criterion "all checkout/* unit tests green"
    :gap        "satisfied — no follow-up"
    :next-agent nil
    :next-call  nil}])
```

### 5.4 The `score` Map

```clojure
(def score
  {:verdict        :achieved        ; :achieved | :partially-achieved | :not-achieved
   :confidence     :high            ; :high | :medium | :low
   :criteria       [{:criterion "feature-flag checkout-v2 toggleable from staging admin"
                     :class :satisfied
                     :confidence :high
                     :items [0]
                     :evidence [{:type :update-agent
                                 :record ".brainyard/agents/update-agent/edits/...md"
                                 :excerpt "wired in src/checkout/flags.clj line 42"}]}
                    …]
   :gaps           ["p99 sampling skipped"]    ; distilled actionable prose
   :recommendations recs                       ; (§5.3 vector)
   :degradation    []                          ; carried from pre.degradation
   })
```

---

## 6. POST-FLIGHT — Confirmation Check (NEW)

Runs after SCORE. Self-critiques against a fixed rubric.

### 6.1 The Rubric

| Item | Rubric question                                                                                                                                                                  | Pass criterion                                                                              |
| ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| R1   | Every criterion in `pre.acceptance` appears exactly once in `score.criteria`.                                                                                                    | Set equality.                                                                                |
| R2   | Every criterion classification cites at least one item idx and one evidence excerpt (or explicit "no items" for MISSING).                                                        | Mechanical check.                                                                            |
| R3   | For every CONTRADICTED criterion that involves a flipped checkbox without evidence, the dossier explicitly names "checkbox flipped without evidence."                            | Grep `score.criteria`.                                                                       |
| R4   | Confidence enum is set on every criterion AND on the overall verdict.                                                                                                            | Mechanical check.                                                                            |
| R5   | For every PARTIAL / MISSING / CONTRADICTED criterion, `score.recommendations` has one entry with a non-nil `:next-call`.                                                          | Mechanical check.                                                                            |
| R6   | Used `query$llm` (and recorded the prompt summary) for every fuzzy criterion. ("Fuzzy" = criterion contains words like *intuitive*, *acceptable*, *reasonable*.)                  | Mechanical match against a pre-set fuzzy-word list + presence of a recorded LLM call.        |
| R7   | The verdict is reproducible: a future reader given the same plan+todo+exec dossiers should be able to derive the same classification table.                                      | LLM cross-check: re-derive the verdict in a fresh `query$llm` call; assert it matches.       |

### 6.2 Verdict

- **PASS** — every R-item passes.
- **REVISE** — one or more items fail in a way the agent can fix in one round (e.g., R5 forgot a recommendation, R6 forgot to cite the LLM prompt). Apply the fix, re-run R1–R7 once.
- **HOLD** — failures persist after one round, OR the verdict requires user adjudication (R3 finds 3+ flipped-without-evidence items — likely a deeper exec-agent bug; surface and stop).

### 6.3 The `post` Map

```clojure
(def post
  {:verdict           :pass
   :rubric            {:r1 :pass :r2 :pass :r3 :pass :r4 :pass
                       :r5 :pass :r6 :pass :r7 :pass}
   :revision-applied? false
   :revision-summary  nil
   :holds             []})
```

---

## 7. Output Discipline — `.brainyard/agents/eval-agent/`

### 7.1 Directory Layout

```
.brainyard/
├── eval-agent/
│   ├── verdicts/                  ; verdict body — human-readable
│   │   ├── 20260510-115412-ship-v2-checkout.md
│   │   └── ...
│   ├── dossiers/                  ; schema'd handoff for the next pipeline turn
│   │   ├── 20260510-115412-ship-v2-checkout.md
│   │   └── ...
│   ├── drafts/
│   ├── INDEX.md
│   └── README.md
```

The split between `verdicts/` and `dossiers/` mirrors exec-agent's `runs/` and `dossiers/`. Verdict files are designed for human reading (the executive summary "did we ship checkout v2?"); dossier files are designed for machine consumption (the recommendation that drives the next pipeline turn).

### 7.2 Verdict Body Schema

```markdown
---
slug: ship-v2-checkout
agent: eval-agent
created: 2026-05-10T11:54:12Z
verdict: ACHIEVED
confidence: high
plan_path: .brainyard/agents/plan-agent/plans/ship-v2-checkout.md
todo_path: .brainyard/agents/todo-agent/todos/ship-v2-checkout.md
exec_run_record: .brainyard/agents/exec-agent/runs/20260510-110131-ship-v2-checkout.md
turn_id: <id>
session_id: <id>
---

# Verdict — Ship v2 checkout: ACHIEVED (confidence: high)

## Summary
All 3 acceptance criteria SATISFIED. No follow-up required beyond user
confirmation to call `todo$complete` and `plan$complete`.

## Per-criterion classification

| Criterion                                                  | Classification | Confidence | Items | Evidence                                                                                       |
|------------------------------------------------------------|----------------|------------|-------|------------------------------------------------------------------------------------------------|
| feature-flag `checkout-v2` toggleable from staging admin   | SATISFIED      | high       | [0]   | update-agent: src/checkout/flags.clj:42 (rec: .../20260510-110205-...md)                      |
| all `checkout/*` unit tests green                          | SATISFIED      | high       | [1,2] | bash: "Ran 18 tests, 0 failures." (exit 0); update-agent: payment_validator.clj:88            |
| p99 checkout latency unchanged within ±5%                  | SATISFIED      | medium     | [3]   | manual: user reported p99 = 142ms (24h window), baseline 138ms — within ±5%                   |

## Gaps
None.

## Recommendations
- todo$complete :slug "ship-v2-checkout" (user confirms).
- plan$complete :slug "ship-v2-checkout" (user confirms).
```

### 7.3 Dossier Schema

```markdown
---
slug: ship-v2-checkout
agent: eval-agent
created: 2026-05-10T11:54:12Z
verdict_path: .brainyard/agents/eval-agent/verdicts/20260510-115412-ship-v2-checkout.md
exec_dossier:  .brainyard/agents/exec-agent/dossiers/20260510-110131-ship-v2-checkout.md
todo_dossier:  .brainyard/agents/todo-agent/dossiers/20260510-105612-ship-v2-checkout.md
plan_dossier:  .brainyard/agents/plan-agent/dossiers/20260510-104503-ship-v2-checkout.md
plan_path:     .brainyard/agents/plan-agent/plans/ship-v2-checkout.md
todo_path:     .brainyard/agents/todo-agent/todos/ship-v2-checkout.md
turn_id: <id>
session_id: <id>

pre:
  verdict: go
  checks:
    c1_exec_dossier:           pass
    c2_exec_postflight:        pass
    c3_evidence_present:       pass
    c4_plan_acceptance:        pass
    c5_coverage_map:           pass
    c6_update_records_resolve: pass
    c7_no_double_score:        informational
  degradation: []
  is_re_run: false

score:
  verdict: ACHIEVED              # ACHIEVED | PARTIALLY_ACHIEVED | NOT_ACHIEVED
  confidence: high
  criteria:
    - criterion: "feature-flag checkout-v2 toggleable from staging admin"
      class: SATISFIED
      confidence: high
      items: [0]
      evidence:
        - {type: update-agent,
           record: .brainyard/agents/update-agent/edits/20260510-110205-...md,
           excerpt: "wired in src/checkout/flags.clj line 42"}
    - criterion: "all checkout/* unit tests green"
      class: SATISFIED
      confidence: high
      items: [1, 2]
      evidence:
        - {type: bash, exit: 0, excerpt: "Ran 18 tests, 0 failures."}
        - {type: update-agent,
           record: .brainyard/agents/update-agent/edits/20260510-110318-...md,
           excerpt: "payment_validator.clj line 88"}
    - criterion: "p99 checkout latency unchanged within ±5%"
      class: SATISFIED
      confidence: medium
      items: [3]
      evidence:
        - {type: manual,
           excerpt: "user reported p99 = 142ms; baseline 138ms — within ±5%"}
  gaps: []
  recommendations:
    - {criterion: null,
       gap: null,
       next_agent: user,
       next_call: 'todo$complete and plan$complete after user confirms'}

post:
  verdict: pass
  rubric:
    r1_all_criteria_classified: pass
    r2_evidence_cited:          pass
    r3_unsupported_flips:       pass
    r4_confidence_set:          pass
    r5_recommendations_present: pass
    r6_fuzzy_llm_recorded:      pass
    r7_reproducible:            pass
  revision_applied: false
  revision_summary: null
  holds: []

handoff:
  next_agent: user                 # plan-agent / todo-agent / exec-agent / user / none
  next_call: 'after user confirms: (call-tool "todo$complete" {:slug "ship-v2-checkout"}) and (call-tool "plan$complete" {:slug "ship-v2-checkout"})'
---

# Eval dossier — Ship v2 checkout (ACHIEVED, high confidence)

## Pre-flight summary
All 7 hard checks passed. Acceptance + coverage + evidence available from
upstream dossiers; no degradation.

## Verdict
ACHIEVED with high confidence. See verdict body for the per-criterion
classification table.

## Recommendations
- The plan and todo can both be marked complete pending user confirmation.

## Handoff
No further pipeline iteration needed. Suggest the user run
`todo$complete` and `plan$complete` for cleanup.
```

Frontmatter contract (downstream parsers may rely on these):

| Key                          | Type                       | Description                                                                                                |
| ---------------------------- | -------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `slug`                       | string                     | Shared with plan / todo / exec for the canonical pipeline.                                                  |
| `agent`                      | string                     | Always `eval-agent`.                                                                                       |
| `verdict_path`               | string                     | Path to the human-readable verdict body.                                                                    |
| `exec_dossier`               | string                     | Path to the consumed exec dossier.                                                                          |
| `pre.*`                      | map                        | Verbatim copy of `pre` (§4.2).                                                                              |
| `pre.degradation`            | vector of keywords         | Soft failures from upstream that lowered verdict confidence (e.g. `:no-coverage-map`).                     |
| `score.verdict`              | enum                       | `ACHIEVED` \| `PARTIALLY_ACHIEVED` \| `NOT_ACHIEVED`.                                                       |
| `score.confidence`           | enum                       | `:high` \| `:medium` \| `:low`.                                                                            |
| `score.criteria[]`           | vector of maps             | Per-criterion classification + items + evidence + confidence.                                              |
| `score.recommendations`      | vector of maps             | Each names a target agent + exact `next-call`.                                                              |
| `post.*`                     | map                        | Verbatim copy of `post` (§6.3).                                                                             |
| `handoff.next_agent`         | string                     | Cheapest viable next step. `user` when only confirmations remain.                                           |
| `handoff.next_call`          | string                     | Exact `(call-tool …)` form, or a brief description for user-side actions.                                   |

### 7.4 ANSWER Format

```
Saved verdict: <verdict path>
Saved dossier: <dossier path>
Verdict: <ACHIEVED|PARTIALLY_ACHIEVED|NOT_ACHIEVED> (confidence: <high|medium|low>)
Next: <handoff.next_call>
```

For NOT_ACHIEVED cases, append a `Recommended:` block listing the per-criterion `next-call`s:

```
Saved verdict: <verdict path>
Saved dossier: <dossier path>
Verdict: NOT_ACHIEVED (confidence: medium)
Recommended:
  - "p99 latency": (call-tool "exec-agent" {…})
  - "all unit tests green": (call-tool "todo-agent" {…})
Next: (call-tool "plan-agent" {:question "Revise approach: …" :agent-context "<this dossier path>"})
```

GATHER / REFUSE / HOLD variants follow the family pattern.

---

## 8. Instruction (System Prompt Body)

Layered on top of `coact-agent`'s instruction.

```text
You are an EVAL-agent. You score whether an executed todo met its source
plan's acceptance criteria. You are READ-ONLY and ADVISORY — you NEVER
mutate plans, todos, or exec records. You ALWAYS produce a verdict body
AND a dossier.

Verdicts at .brainyard/agents/eval-agent/verdicts/, dossiers at
.brainyard/agents/eval-agent/dossiers/. Inputs come from upstream dossiers:
plan-agent's, todo-agent's, exec-agent's.

────────────────────────────────────────────────────────────────────────────
THE THREE PHASES (every turn)
────────────────────────────────────────────────────────────────────────────
PRE-FLIGHT  — sufficiency check. Output: GO | GATHER | REFUSE.
SCORE       — only on GO. Per-criterion classification → verdict.
POST-FLIGHT — only after SCORE. Self-critique against 7-item rubric.
              Output: PASS | REVISE | HOLD.
PERSIST     — always. Verdict body + dossier under .brainyard/agents/eval-agent/.
ANSWER      — `Saved verdict:`, `Saved dossier:`, `Verdict: <X> (confidence: Y)`,
              `Next:` (and `Recommended:` for NOT_ACHIEVED).

────────────────────────────────────────────────────────────────────────────
PRE-FLIGHT CHECKLIST (short-circuit on first fail)
────────────────────────────────────────────────────────────────────────────
C1. EXEC DOSSIER. :agent-context contains `Saved dossier: <path>` for an
    exec-agent dossier (frontmatter `agent: exec-agent`).

C2. EXEC POST-FLIGHT. exec$read-dossier; check post.verdict. If :hold →
    score with reduced confidence; record in degradation vector. If
    :revise → INFORMATIONAL.

C3. EVIDENCE PRESENT. execute.evidence map non-empty. Empty → REFUSE
    unless :checkbox-only-ok? true.

C4. PLAN ACCEPTANCE. Read plan_dossier; assert post.verdict :pass AND
    (seq post.acceptance). Empty acceptance → GATHER (recommend plan
    re-author). Plan post :hold → score but verdict confidence :low;
    record degradation.

C5. COVERAGE MAP. Read todo_dossier; check post.acceptance_coverage.
    Empty → INFORMATIONAL; rebuild via query$llm; record degradation
    :no-coverage-map.

C6. UPDATE RECORDS RESOLVE. For each :update-agent evidence.path,
    bash test -f. Missing → GATHER (the exec dossier may be stale).

C7. NO DOUBLE SCORE. (eval$find :slug … :run-record …) for prior
    verdicts on this exact exec turn. Found → INFORMATIONAL; record
    is-re-run :true.

Stash `pre` (§4.2 schema). Track degradation [] for soft fails.

────────────────────────────────────────────────────────────────────────────
SCORE — only on GO
────────────────────────────────────────────────────────────────────────────
For each criterion C in pre.acceptance:

  1. CANDIDATE ITEMS = pre.acceptance-coverage[C], or fuzzy-match via
     query$llm when degradation :no-coverage-map is set.

  2. EVIDENCE PER ITEM:
     - :update-agent → drill via update$read-record; read verify.diff_match,
                       verify.lint, verify.tests.
     - :bash         → exit + stdout-tail.
     - :mcp          → response excerpt.
     - :explore-agent → exploration record summary.
     - :read-only    → recorded excerpt.
     - :manual       → user-supplied result, or :manual-pending.

  3. CLASSIFY:
     - All ok? AND concrete demo of C → SATISFIED (high or medium conf).
     - Mixed ok? / pending             → PARTIAL.
     - Zero candidates / all manual-pending → MISSING.
     - Any ok? false without compensation → CONTRADICTED.
     - Flipped checkbox without evidence record → CONTRADICTED with the
       note "checkbox flipped without evidence."

  4. CITE: items, short evidence excerpts, update-agent record paths
     when applicable, confidence enum.

  5. FUZZY: criteria containing words from {intuitive, acceptable,
     reasonable, smooth, polished, etc.} → use query$llm to weigh
     evidence; record prompt + one-line summary.

VERDICT AGGREGATION:
  All SATISFIED                                  → ACHIEVED
  ≥ 1 SATISFIED, no CONTRADICTED, no MISSING     → ACHIEVED
  ≥ 1 SATISFIED, no CONTRADICTED, ≥ 1 MISSING    → PARTIALLY_ACHIEVED
  ≥ 1 CONTRADICTED OR no SATISFIED               → NOT_ACHIEVED

CONFIDENCE AGGREGATION:
  All criteria :high                             → :high
  Any :low (or any degradation present)          → :low
  Otherwise                                      → :medium

RECOMMENDATIONS — for each PARTIAL / MISSING / CONTRADICTED criterion:
  Spec wrong / scope shifted   → plan-agent (plan$update-body)
  Spec right, items missing    → todo-agent (todo$add-item)
  Spec right, item failed      → exec-agent (resume; todo$reset-item first)
  Partial OK as-is             → todo$complete (user confirms)

Stash `score` (§5.4 schema).

────────────────────────────────────────────────────────────────────────────
POST-FLIGHT RUBRIC
────────────────────────────────────────────────────────────────────────────
R1. ALL CRITERIA CLASSIFIED — set equality between pre.acceptance and
    score.criteria.
R2. EVIDENCE CITED — every classification has items + evidence excerpts
    (or explicit "no items" for MISSING).
R3. UNSUPPORTED FLIPS — for each CONTRADICTED with flipped-without-
    evidence, the dossier explicitly names it.
R4. CONFIDENCE SET — every criterion AND the verdict have a confidence
    enum.
R5. RECOMMENDATIONS — every PARTIAL/MISSING/CONTRADICTED has a non-nil
    next-call.
R6. FUZZY LLM RECORDED — every fuzzy criterion (regex match) has a
    recorded query$llm prompt summary.
R7. REPRODUCIBILITY — re-derive the verdict in a fresh query$llm call
    using only plan + todo + exec dossiers; assert it matches.

VERDICT:
- All pass             → PASS.
- 1+ fail, fixable     → REVISE. One round; re-run R1–R7 once.
- 1+ fail, needs user  → HOLD. Surface in answer.

Stash `post` (§6.3 schema).

────────────────────────────────────────────────────────────────────────────
PERSIST + ANSWER
────────────────────────────────────────────────────────────────────────────
Write verdict body:
   .brainyard/agents/eval-agent/verdicts/<yyyyMMdd-HHmmss>-<slug>.md
Write dossier:
   .brainyard/agents/eval-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md
PREPEND a line to .brainyard/agents/eval-agent/INDEX.md.

When eval$* helpers (§12) are bound:
   (eval$verdict-write :slug … :score score)
   (eval$dossier-write :slug … :pre pre :score score :post post
                       :handoff handoff)
   (eval$index-append …)

ANSWER (PASS, ACHIEVED):
    Saved verdict: <path>
    Saved dossier: <path>
    Verdict: ACHIEVED (confidence: high)
    Next: <recommendation, often "user confirms todo$complete + plan$complete">

ANSWER (PASS, NOT_ACHIEVED):
    Saved verdict: <path>
    Saved dossier: <path>
    Verdict: NOT_ACHIEVED (confidence: medium)
    Recommended:
      - "<criterion 1>": <next-call 1>
      - "<criterion 2>": <next-call 2>
    Next: <primary recommendation — often plan-agent or exec-agent>

GATHER / REFUSE / HOLD variants follow the family pattern.

────────────────────────────────────────────────────────────────────────────
HARD RULES
────────────────────────────────────────────────────────────────────────────
1. NO mutating plans, todos, or exec records. Read-only across upstream.
2. NO inventing acceptance criteria. If plan acceptance is empty, GATHER.
3. NO inferring satisfaction from a flipped checkbox alone. Without
   evidence, that's CONTRADICTED.
4. NO unbounded revision. POST-FLIGHT allows ONE auto-round.
5. NO query$clone. Cross-agent dispatch via call-tool is fine.
6. NO writing outside .brainyard/agents/eval-agent/.
7. NO auto-dispatching plan/todo/exec recommendations. RECOMMEND only.
   Sub-dispatch ONLY when the user explicitly says "and apply" in the
   same turn.
8. NEVER skip the verdict body or the dossier.
```

---

## 9. Tool-Context

```text
## Eval Tools — read upstream dossiers, score, write verdict + dossier

UPSTREAM DOSSIER ACCESS (READ-ONLY)
- exec$read-dossier  -- frontmatter-only parse of an exec-agent dossier.
                        Args: path. Returns :execute :evidence :post
                        :acceptance_progress, plus links to plan_dossier
                        and todo_dossier.
- todo$read-dossier  -- frontmatter parse of a todo-agent dossier. Gives
                        :post.acceptance_coverage, :pre.acceptance.
- plan$read-dossier  -- frontmatter parse of a plan-agent dossier. Gives
                        :post.acceptance, :post.verdict.
- update$read-record -- drill from an exec evidence entry's :path to the
                        underlying update-agent record. Gives :verify
                        :apply :rollback for diff-level audit.

PLAN / TODO ACCESS (READ-ONLY, fallback only)
- plan$read, plan$exists, plan$status — for legacy / degraded paths
                        when the plan dossier is absent.
- todo$read, todo$status, todo$exists — same, for the todo file.

NOT BOUND HERE (deliberate — eval is read-only):
- All plan-mutating commands → plan-agent
- All todo-mutating commands → todo-agent / exec-agent
- update-file / write-file / bash with redirection → forbidden

REASONING
- query$llm — used heavily in SCORE for fuzzy criterion language and in
              POST-FLIGHT R7 for reproducibility cross-check. Cite
              prompt + one-line summary in the dossier.

DISCOVERY (inherited)
- read-file, search, grep, bash (read-only — `test -f`, `wc -l`)
- list-tools, get-tool-info, call-tool

PERSISTENCE HELPERS (eval$* — auto-bound when present)
- eval$verdict-write       — write the human-readable verdict body
- eval$dossier-frontmatter — YAML per §7.3
- eval$dossier-write       — write the dossier (paired with verdict)
- eval$index-append        — prepend INDEX.md
- eval$read-dossier        — frontmatter-only parse
- eval$find                — search prior verdicts by slug + run-record
                              (C7 double-score check)
- eval$next-handoff        — single source of truth for `Next:`
- eval$preflight           — full pre-flight as one call
- eval$postflight          — full post-flight as one call (with one
                              auto-revise round)
- eval$score-criterion     — classify one criterion against evidence;
                              returns {:class :confidence :items
                                       :evidence}

CROSS-AGENT DISPATCH (only on user opt-in)
- (call-tool "plan-agent" {…})    — verdict triggered re-spec.
- (call-tool "todo-agent" {…})    — verdict triggered re-shape.
- (call-tool "exec-agent" {…})    — verdict triggered resume.

## Typical end-to-end flow
1. Parse :question and :agent-context (a `Saved dossier:` for exec).
2. PRE-FLIGHT C1–C7. Stash `pre`.
3. If GATHER/REFUSE → skip SCORE, jump to PERSIST + ANSWER.
4. SCORE per criterion; aggregate verdict + confidence; build
   recommendations.
5. POST-FLIGHT R1–R7. One auto-round if needed. Stash `post`.
6. PERSIST verdict body + dossier; INDEX prepend.
7. ANSWER — `Saved verdict:` + `Saved dossier:` + `Verdict:` +
   (`Recommended:`) + `Next:`.
```

---

## 10. Behavior Tree — Inherited As-Is

Same as plan / todo / exec. No new BT. Iteration shape:

| Iter | Channel       | Body                                                                                       |
| ---- | ------------- | ------------------------------------------------------------------------------------------ |
| 1    | code          | PRE-FLIGHT C1–C7. `def pre`.                                                                |
| 2    | tool / code   | (only on GO) Read upstream dossiers + drill update records. `def evidence-trees`.           |
| 3    | code          | SCORE — per-criterion classification with query$llm where fuzzy. Build `score`.            |
| 4    | code          | POST-FLIGHT rubric. One auto-revise if needed. `def post`.                                  |
| 5    | code          | PERSIST verdict body + dossier; INDEX prepend.                                              |
| 6    | answer        | `Saved verdict:` + `Saved dossier:` + `Verdict:` + (`Recommended:`) + `Next:`.             |

---

## 11. Demonstration: "Did we actually ship checkout v2?"

`:agent-context = "Saved dossier: .brainyard/agents/exec-agent/dossiers/20260510-110131-ship-v2-checkout.md"` (after the manual p99 was supplied and item 3 closed in a follow-up exec turn).

### Iteration 1 — PRE-FLIGHT

```clojure
(def exec-d  (call-tool "exec$read-dossier"  {:path ".brainyard/agents/exec-agent/dossiers/...md"}))
(def todo-d  (call-tool "todo$read-dossier"  {:path (:todo_dossier exec-d)}))
(def plan-d  (call-tool "plan$read-dossier"  {:path (:plan_dossier exec-d)}))

(def pre
  {:verdict :go
   :checks {:c1 :pass
            :c2 (if (= :pass (-> exec-d :post :verdict)) :pass :fail)
            :c3 (if (seq (-> exec-d :execute :evidence)) :pass :fail)
            :c4 (if (and (= :pass (-> plan-d :post :verdict))
                         (seq (-> plan-d :post :acceptance)))
                  :pass :fail)
            :c5 (if (seq (-> todo-d :post :acceptance_coverage)) :pass :informational)
            :c6 (if (every? #(zero? (:exit-code (call-tool "bash" {:command (str "test -f " %)})))
                            (keep #(get-in % [:evidence :path]) (vals (-> exec-d :execute :evidence))))
                  :pass :fail)
            :c7 :informational}
   :exec-dossier ".brainyard/agents/exec-agent/dossiers/...md"
   :exec-run-record (:run_record exec-d)
   :todo-dossier (:todo_dossier exec-d)
   :plan-dossier (:plan_dossier exec-d)
   :slug (:slug exec-d)
   :acceptance (-> plan-d :post :acceptance)
   :acceptance-coverage (-> todo-d :post :acceptance_coverage)
   :evidence (-> exec-d :execute :evidence)
   :degradation []
   :is-re-run false})
```

### Iteration 2 — SCORE

```clojure
(def criteria
  (for [c (:acceptance pre)]
    (let [items (get-in pre [:acceptance-coverage c])
          evid  (mapv (:evidence pre) items)]
      (cond
        ;; SATISFIED — every contributing item ok? true with concrete evidence
        (and (every? :ok? evid)
             (every? #(or (= :update-agent (-> % :evidence :type))
                          (and (= :bash (-> % :evidence :type))
                               (zero? (-> % :evidence :exit)))
                          (= :manual (-> % :evidence :type)))
                     evid))
        {:criterion c
         :class :satisfied
         :confidence (if (some #(#{:update-agent :bash} (-> % :evidence :type)) evid)
                       :high :medium)
         :items items
         :evidence (mapv #(select-keys (:evidence %) [:type :record :exit :excerpt]) evid)}

        :else
        {:criterion c
         :class :missing
         :confidence :low
         :items items
         :evidence []}))))

(def score
  {:verdict (cond
              (every? #(= :satisfied (:class %)) criteria)              :achieved
              (some #(= :contradicted (:class %)) criteria)             :not-achieved
              (and (some #(= :satisfied (:class %)) criteria)
                   (every? #(not= :contradicted (:class %)) criteria))  :partially-achieved
              :else                                                      :not-achieved)
   :confidence (cond
                 (every? #(= :high (:confidence %)) criteria) :high
                 (some  #(= :low  (:confidence %)) criteria)  :low
                 :else                                         :medium)
   :criteria   criteria
   :gaps       []
   :recommendations
   (for [c criteria
         :when (#{:partial :missing :contradicted} (:class c))]
     {:criterion (:criterion c)
      :gap       (str (:class c) " — see verdict body")
      :next-agent (case (:class c)
                    :missing      "todo-agent"
                    :partial      "exec-agent"
                    :contradicted "plan-agent")
      :next-call  "..."})
   :degradation (:degradation pre)})
```

### Iteration 3 — POST-FLIGHT

```clojure
(def post
  {:verdict :pass
   :rubric  {:r1 :pass :r2 :pass :r3 :pass :r4 :pass
             :r5 :pass :r6 :pass :r7 :pass}
   :revision-applied? false
   :revision-summary  nil
   :holds []})
```

### Iteration 4 — PERSIST

```clojure
(eval$verdict-write :slug (:slug pre) :score score)
(def res
  (eval$dossier-write :slug (:slug pre)
                      :pre pre :score score :post post
                      :handoff {:next-agent "user"
                                :next-call "after user confirms: (call-tool \"todo$complete\" …) and (call-tool \"plan$complete\" …)"}))
(eval$index-append :path (:path res) :slug (:slug pre)
                   :verdict (:verdict score)
                   :confidence (:confidence score))
```

### Iteration 5 — ANSWER

```markdown
## Verdict — Ship v2 checkout: ACHIEVED (confidence: high)

PRE-FLIGHT: GO (all 7 checks passed; no degradation)
SCORE:
  - "feature-flag checkout-v2 toggleable from staging admin" → SATISFIED (high) [item 0]
  - "all checkout/* unit tests green" → SATISFIED (high) [items 1, 2]
  - "p99 checkout latency unchanged within ±5%" → SATISFIED (medium) [item 3]
POST-FLIGHT: PASS

Saved verdict: .brainyard/agents/eval-agent/verdicts/20260510-115412-ship-v2-checkout.md
Saved dossier: .brainyard/agents/eval-agent/dossiers/20260510-115412-ship-v2-checkout.md
Verdict: ACHIEVED (confidence: high)
Next: After user confirms: (call-tool "todo$complete" {:slug "ship-v2-checkout"}) and (call-tool "plan$complete" {:slug "ship-v2-checkout"})
```

For a NOT_ACHIEVED case the answer would also include a `Recommended:` block enumerating per-criterion next-calls.

---

## 12. Optional `(eval$*)` Helpers

Live in a new namespace `ai.brainyard.agent.common.eval` (sibling of plan/todo/exec helpers). Same shape as the dossier helpers in the other agents, plus:

- `eval$verdict-write` — write the human-readable verdict body.
- `eval$find` — search prior verdicts by slug + run-record (C7 check).
- `eval$score-criterion` — classify one criterion against evidence; returns `{:class :confidence :items :evidence}`.
- `eval$preflight` / `eval$postflight` — full pipeline as single calls.

When `eval$score-criterion` is bound, the SCORE iteration boilerplate from §11 collapses to a `mapv` over `pre.acceptance`.

---

## 13. Handoff Mechanics

When the verdict is NOT_ACHIEVED or PARTIALLY_ACHIEVED, the eval-agent dossier is consumed by the next agent in the loop:

- `plan-agent` reads it when the verdict says "scope shifted" or "spec wrong" — uses `score.gaps` and per-criterion CONTRADICTED entries to decide what to revise in `## Approach` / `## Acceptance`.
- `todo-agent` reads it when the verdict says "items missing" — uses MISSING criteria to know which `todo$add-item` calls to make.
- `exec-agent` reads it when the verdict says "items failed" — uses CONTRADICTED criteria to know which items to `todo$reset-item` and resume.

Each downstream agent treats the eval dossier the way it treats any other upstream dossier: pre-flight C1 widens to accept either a plan/todo/exec dossier OR an eval dossier in `:agent-context`. The eval dossier's `handoff.next_call` is the source of truth.

For the user-facing case (`handoff.next_agent: user`), the recommendation is a one-line shell-style instruction the user can copy into their TUI.

---

## 14. Migration Plan

Same phases as plan-agent (`docs/plan-agent-design.md` §14). Phase 0 land redesigned agent + helpers. Phase 1 (no file move — eval had no on-disk artifacts before). Phase 2 update upstream agents (plan / todo / exec) to ALSO surface their structured fields in dossiers consumed by eval (already done by their respective design docs). Phase 3 emit a one-time deprecation warning when eval-agent is invoked WITHOUT an exec dossier and falls back to checkbox-only scoring. Phase 4 remove the fallback.

---

## 15. Verification

| Benchmark                                  | Shape                                                                                                  | What it verifies                                                                                                                  |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------- |
| Pre-flight GO happy path                   | Valid exec dossier; plan + todo dossiers also present                                                  | All C1–C7 pass; SCORE runs; POST-FLIGHT PASS; verdict + dossier produced.                                                         |
| Pre-flight GATHER (no exec dossier)        | :agent-context = "" or a non-exec dossier path                                                         | C1 fails; agent recommends exec-agent; verdict not produced.                                                                      |
| Pre-flight REFUSE (no evidence)            | Exec dossier with empty execute.evidence and no :checkbox-only-ok?                                     | C3 fails; agent refuses with reason.                                                                                              |
| Pre-flight degradation (plan no acceptance) | Plan dossier post.acceptance empty                                                                     | C4 fails → GATHER; or with override, score with reduced confidence + degradation flag.                                            |
| Pre-flight degradation (no coverage map)   | Todo dossier missing post.acceptance_coverage                                                          | C5 sets degradation :no-coverage-map; SCORE rebuilds coverage via query$llm.                                                      |
| ACHIEVED verdict                           | All criteria SATISFIED                                                                                  | Verdict ACHIEVED; confidence high (when all evidence concrete); recommendations name `todo$complete` + `plan$complete`.            |
| PARTIALLY_ACHIEVED verdict                 | One MISSING criterion                                                                                   | Verdict PARTIALLY_ACHIEVED; recommendations include todo-agent for the missing criterion.                                          |
| NOT_ACHIEVED (test failure)                | Bash item exit=1                                                                                        | Verdict NOT_ACHIEVED with CONTRADICTED on the affected criterion; recommendation routes to exec-agent (resume).                   |
| NOT_ACHIEVED (flipped without evidence)    | Todo file has item N flipped, but exec dossier has no evidence for N                                    | Criterion containing N → CONTRADICTED with the explicit "checkbox flipped without evidence" note.                                 |
| Fuzzy criterion                            | Acceptance like "checkout feels intuitive"                                                              | query$llm invoked; prompt summary recorded in dossier; confidence :medium at best.                                                |
| Drill from criterion to diff               | SATISFIED criterion with :update-agent evidence                                                         | dossier evidence cites the update-agent record path; the record is present (C6).                                                  |
| Post-flight REVISE                         | First-pass forgot recommendation for one CONTRADICTED criterion                                         | R5 fails; one auto-round adds it; final dossier has all recommendations.                                                          |
| Post-flight HOLD (3+ unsupported flips)    | Suspicious exec turn                                                                                    | R3 fails > threshold; agent surfaces hold; recommends user review the exec turn.                                                  |
| Reproducibility                            | Re-run eval-agent on the same dossier set                                                              | Verdict and per-criterion classifications match; R7 cross-check passes.                                                            |
| Index integrity                            | Append 100 dossiers                                                                                     | INDEX.md has 100 lines, newest first.                                                                                             |

mulog signals: `::eval.preflight`, `::eval.score-criterion`, `::eval.fuzzy-llm`, `::eval.postflight`, `::eval.dossier-write`.

---

## 16. Files Summary

| File                                                                                       | What changes                                                                                                                                        |
| ------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `components/agent/src/ai/brainyard/agent/common/eval_agent.clj`                            | REDESIGNED — three-phase pipeline (PRE-FLIGHT / SCORE / POST-FLIGHT), new instruction + tool-context per §8 / §9, verdict + dossier emission per §7. |
| `components/agent/src/ai/brainyard/agent/common/eval.clj` (NEW)                            | NEW — `eval$verdict-write`, `eval$dossier-frontmatter`, `eval$dossier-write`, `eval$index-append`, `eval$read-dossier`, `eval$find`, `eval$score-criterion`, `eval$preflight`, `eval$postflight`. |
| `components/agent/test/ai/brainyard/agent/eval_agent_test.clj`                             | EXTENDED — new tests per §15.                                                                                                                       |
| `.brainyard/agents/eval-agent/README.md`                                                          | NEW (templated by helpers).                                                                                                                          |
| `bb.edn`                                                                                   | NO new task (no file migration).                                                                                                                    |
| `docs/eval-agent-design.md`                                                                | THIS FILE.                                                                                                                                          |
| `components/agent/src/ai/brainyard/agent/common/{plan_agent,todo_agent,exec_agent}.clj`    | TOUCHED at Phase 2 to ensure their dossiers carry structured `acceptance` / `acceptance_coverage` / `acceptance_progress` (already part of their own design docs). |
| `docs/agent-design.md` / `docs/AUTORESEARCH.md`                                            | TOUCHED — pipeline diagram updated to show eval dossiers feeding back to plan / todo / exec.                                                         |

---

## 17. Open Questions

1. **Should eval-agent ever auto-dispatch the recommended next agent?** Today: never. A `:auto-apply? true` flag would let it call plan/todo/exec directly when the verdict is unambiguous (e.g., NOT_ACHIEVED with a single CONTRADICTED criterion). Trade-off: tighter loop (autoresearch loves this) vs. user-out-of-the-loop. Suggestion: opt-in only, default off.
2. **Per-criterion confidence vs. verdict confidence — display tradeoff.** Today both are shown. Could simplify to verdict-only with a footnote when criteria differ. Defer.
3. **What about acceptance criteria that span multiple plans?** Today eval scopes to one plan. Multi-plan execution (rare but possible) would need a `pre.plan-dossiers` vector. Defer until use case appears.
4. **Should eval-agent compute deltas from a prior verdict?** When the same slug is re-scored, the dossier could include a "since previous verdict" diff (which criteria moved categories). Cheap to add; defer.
5. **R7 reproducibility cost.** A second `query$llm` to re-derive the verdict is non-trivial in tokens. Could be opt-in via `:reproducibility-check? true` for high-stakes verdicts only. Suggestion: enable for ACHIEVED verdicts (where stakes of a wrong verdict are highest); skip for NOT_ACHIEVED where the recommendations make the verdict actionable regardless.
6. **Should eval-agent surface the per-criterion confidence visually in the answer?** Today the table is wide. A compact version with just the class column might be better for the answer (full table in the verdict body). Defer to UX tuning.
