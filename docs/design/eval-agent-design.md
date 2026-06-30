# Eval-Agent — Pre-flight & Post-flight Gated Verdict Production with Unified Handoff (CoAct-derived)

> **Status:** Shipped. `eval-agent` is registered in `components/agent`
> (`common/eval_agent.clj`). The lightweight authoring redesign (2026-06) shipped
> and is folded in here as the as-built reference — the former
> `eval-agent-lightweight-redesign.md` has been removed. The three-phase contract
> (PRE-FLIGHT → SCORE → POST-FLIGHT, verdict + handoff) is intact; what changed is
> *how the agent persists* — direct markdown authoring, and the verdict/dossier
> split collapsed into ONE unified file. See [core/agent.md](../core/agent.md) for
> the current roster.
>
> **As-built (verify against `common/eval_agent.clj`, `common/eval.clj`):**
> - **Authoring is direct markdown, not a helper chain.** The verdict is a
>   markdown file; the model fills the VERDICT TEMPLATE (§7.2) and `write-file`s
>   it. The entire persist-side helper chain — `eval$dossier-slug`,
>   `eval$dossier-frontmatter`, `eval$dossier-write`, `eval$dossier-index-append`,
>   `eval$verdict-write`, `eval$next-handoff` — is **retired** (§12). `write-file`
>   is bound; `update-file` and `fetch-url` are stripped from the file-tools
>   roster (eval writes whole files, never patches; web is explore-agent's
>   surface).
> - **Verdict and dossier are now ONE unified file**, not two. The pre-redesign
>   `verdicts/` (human body) + `dossiers/` (machine handoff) split is collapsed
>   into a single frontmatter+body file under `verdicts/<ts>-<slug>.md` — YAML
>   frontmatter is the machine handoff, the markdown body is the human report
>   (§7). The answer emits ONE `Saved verdict:` line (no `Saved dossier:`).
> - **Only two read seams survive**, registered in `eval-dossier-helpers` and
>   auto-bound: `eval$read-verdict` (frontmatter-only parse of a unified verdict;
>   dual-reads legacy `pre`/`score`/`post`/`handoff` dossiers) and `eval$find`
>   (prior-verdict search for the C7 double-score check). The old
>   `eval$read-dossier` was **renamed** to `eval$read-verdict`. The aspirational
>   `eval$preflight` / `eval$postflight` / `eval$score-criterion` helpers (§12 of
>   the old proposal) never shipped — SCORE is pure LLM judgment.
> - **POST-FLIGHT verdict is PASS / HOLD only.** The design's REVISE auto-round
>   is **deferred to v1.5**; any rubric failure lands in HOLD (no auto-retry).
> - **`:checkbox-only-ok?` is not yet supported.** C3 (no evidence) → REFUSE
>   unconditionally in v1; the opt-in checkbox-only fallback is not wired.
> - **Cross-agent dispatch is direct kebab-case** — `(plan-agent {…})`,
>   `(exec-agent {…})`, `(todo-agent {…})` — not `call-tool`. Hard Rule 4 reads
>   "NO clone-self dispatch." Recommendations name `doc$update :kind :todo / :plan`
>   verbs (e.g. `:add-item`, `:item-done false`, `:status :completed`), not the
>   retired `todo$*` / `plan$*` shims.
> - **Two gated hooks back the contract** (neither in the original design):
>   - An **`:agent.ask/finalize` auto-persist hook** (scoped to `:eval-agent`)
>     fills the §7.2 template from the answer text and `spit`s ONE unified file if
>     the LLM skips PERSIST, injecting the absent `Saved verdict:` line. It writes
>     the *same one-file path* the happy path uses (its frontmatter omits the
>     per-criterion blocks). A missing line therefore does NOT mean nothing was
>     saved; consumers fall back to `eval$find` or the newest INDEX entry.
>   - An **`:agent.tool-use/pre` read-only bash guard** refuses obviously-mutating
>     bash (`rm`/`mv`/`git commit`/`sed -i`/file-redirect) so eval's
>     non-mutation contract holds even though the unrestricted `bash` tool is bound.
> - C7 double-score is **informational**; R7 reproducibility is
>   **instruction-level only** (no opt-in flag).
> - Migration §14 is **complete** — eval ships read-only toward upstream, writing
>   only under `.brainyard/agents/eval-agent/`.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/eval_agent.clj`,
> `components/agent/src/ai/brainyard/agent/common/eval.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Sibling of:** `plan-agent`, `todo-agent`, `exec-agent`, `edit-agent`, `explore-agent`
> **Related reading:** `docs/design/plan-agent-design.md` (acceptance comes from
> the plan dossier), `docs/design/todo-agent-design.md` (acceptance coverage map),
> `docs/design/exec-agent-design.md` (per-item evidence comes from the exec
> dossier), `docs/design/edit-agent-design.md` (drill-down from evidence to
> diffs), `docs/design/agent-lightweight-redesign-synthesis.md` (the cross-agent
> "writes are LLM-inherent; reads stay typed" principle).

> **API note (2026-05):** the per-verb `todo$*` and `plan$*` CRUD shims were
> removed in favour of the polymorphic `doc$*` family with `:kind :todo` /
> `:kind :plan`. See `docs/design/todo-agent-design.md` for the verb-by-verb
> mapping. The dossier *read* helpers (`todo$read-dossier`, `plan$read-dossier`,
> `exec$read-dossier`) are NOT deprecated and stay bound read-only.

---

## 1. Motivation

The pre-redesign `eval-agent` judged whether an executed todo satisfied its
source plan's acceptance criteria. It read the plan, read the todo, parsed an
"exec evidence stream" out of `:agent-context`, and rendered a verdict. Three
problems surfaced:

1. **The evidence channel was fragile.** Eval expected exec-agent's `:answer` to
   be pasted into `:agent-context` after a `## Exec Evidence` separator. If the
   dispatcher forgot it, eval silently degraded to checkbox-only scoring and
   hand-waved the verdict. There was no pre-flight check on whether the input was
   actually scoreable.
2. **Acceptance criteria were re-derived from markdown.** Eval re-parsed the plan
   body to find `## Acceptance`. Plan-agent now ships acceptance as a structured
   `post.acceptance` field in its dossier; todo-agent ships an
   `acceptance_coverage` map; exec-agent ships `execute.evidence`. Eval should
   consume those directly and only re-parse markdown as a legacy fallback.
3. **The verdict had no on-disk artifact.** The verdict lived in `:answer` and
   vanished when the chat trajectory rotated. There was no
   `.brainyard/agents/eval-agent/…` to look back at "what did eval say last week
   about the checkout-v2 launch?", and no schema'd handoff to whoever should act
   on it.

A fourth issue surfaced once eval started persisting: **the authoring path was
the heaviest in the agent set.** A single PASS turn asked the LLM to build a
deeply-nested `score` object (a vector of per-criterion maps, each with a nested
evidence vector-of-maps, plus a parallel recommendations vector-of-maps), feed
`pre`/`score`/`post` to a YAML renderer, write *two* artifacts (a human verdict
body and a machine dossier), and re-pass the shared keys across six structured
helper calls. Any mis-keyed `:next-call` vs `next_call`, any criterion emitted as
a string instead of a map, any flow-map quoting slip errored the call and burned
an iteration — and we built an auto-persist hook precisely because the structured
path leaked. Meanwhile the thing the model is *best* at — writing a clear
per-criterion verdict report — is exactly what we routed around by making it
assemble `score` for a renderer.

**Thesis.** Every scoring run walks a fixed three-phase pipeline:

1. **PRE-FLIGHT (sufficiency check, C1–C7)** — does the agent have everything it
   needs to score? Plan dossier with `post.acceptance`, exec dossier with
   `execute.evidence`, optional edit-agent records for diff-level drill-down.
   Output: GO / GATHER / REFUSE.
2. **SCORE** — for each acceptance criterion, classify SATISFIED / PARTIAL /
   MISSING / CONTRADICTED from the evidence map; render the overall verdict
   ACHIEVED / PARTIALLY_ACHIEVED / NOT_ACHIEVED; assemble per-criterion follow-up
   recommendations. This phase is **pure LLM judgment** (reasoning + `query$llm`)
   and is unchanged by the redesign.
3. **POST-FLIGHT (confirmation check, R1–R7)** — every criterion classified?
   evidence cited? recommendations name a concrete tool call? confidence noted?
   Output: PASS / HOLD. (REVISE auto-round deferred to v1.5.)

Every run produces **ONE unified verdict file**: YAML frontmatter is the
machine-readable handoff that `plan-agent` / `todo-agent` / `exec-agent` consume
when the verdict triggers re-spec / re-shape / resume; the markdown body is the
human report. The file is authored **directly** with `write-file` from a fixed
template — no `score` object, no renderer, no helper chain.

Same minimal-diff principle. CoAct loop, sandbox, BT, DSPy untouched. The whole
change is the instruction + a slimmed read-only helpers namespace.

---

## 2. Design Principles

1. **No verdict on insufficient evidence.** Pre-flight REFUSEs when the exec
   dossier is missing or its `execute.evidence` map is empty. Eval never invents
   satisfaction from a checked box alone. (The opt-in checkbox-only fallback
   `:checkbox-only-ok? true` from the original design is **not yet wired** — C3
   REFUSEs unconditionally in v1.)
2. **Writes are LLM-inherent; reads stay deterministic.** The verdict *is* a
   markdown report with a frontmatter header, so the model `write-file`s it from
   the §7.2 template — no construct-and-render helper chain. Reading the upstream
   evidence (`exec$read-dossier`, `plan$read-dossier`, `todo$read-dossier`,
   `edit$read-record`) and finding priors (`eval$find`) stay typed and
   deterministic — exactly where a machine beats the model. This is the
   cross-agent principle in `agent-lightweight-redesign-synthesis.md`: separate
   *judgment* (scoring + report prose) from *mechanism* (parsing four upstream
   dossiers). Eval is the strongest validation of "keep reads typed" — parsing
   the evidence inputs by hand would dwarf the scoring in error rate.
3. **Scoring is judgment, not mechanism — and it's untouched.** The per-criterion
   classification, verdict aggregation, confidence, fuzzy-criterion handling, and
   the R7 reproducibility cross-check are `query$llm` / reasoning. The redesign
   does **not** touch SCORE; it only changes how the result is written.
4. **One unified file per turn.** The verdict body (human) and the dossier
   (machine) collapse into one frontmatter+body file (§5/§7), exactly the shape
   explore-agent proves: YAML frontmatter for cheap routing, markdown body for
   the human report, a frontmatter-only reader (`eval$read-verdict`) for
   downstream re-spec. REFUSE/GATHER turns still write one file — the audit trail.
5. **Structured fields first; markdown re-parse only as fallback.** Read
   `post.acceptance` from the plan dossier, `post.acceptance_coverage` from the
   todo dossier, `execute.evidence` from the exec dossier. Re-parse markdown
   (`doc$read`) only when those fields are absent (legacy data).
6. **Drill from criterion → item → evidence → diff.** The classification table is
   a navigation tree. SATISFIED entries cite the item idx, the evidence excerpt,
   AND when applicable the underlying edit-agent record path so a future reader
   can audit the actual change (`edit$read-record`).
7. **Verdict is a recommendation, not an action.** Eval NEVER auto-dispatches
   plan/todo/exec. The frontmatter names the next agent + the exact call; the
   user (or orchestrator) decides whether to invoke it. Sub-dispatch is allowed
   ONLY when the user explicitly says "and apply" in the same turn (v1 ships
   without this opt-in flag).
8. **Confidence is a first-class field.** Every criterion classification carries
   a confidence enum (`high`/`medium`/`low`) reflecting whether evidence was
   concrete (file diff, test exit code) or fuzzy (LLM-judged narrative). Aggregate
   confidence appears on the verdict, and any `degradation` entry forces `:low`.
9. **Plans, todos, and exec records are read-only.** Eval NEVER mutates upstream.
   The only writes are to `.brainyard/agents/eval-agent/`. A read-only bash guard
   (§12) refuses mutating shell commands even though `bash` is bound.
10. **No clone-self recursion.** No `query$clone`. Cross-agent dispatch to a
    DIFFERENT registered agent is direct kebab-case (`(plan-agent {…})`) and is
    allowed — only clone-self is forbidden.

---

## 3. Position in the Agent Stack

See `docs/design/plan-agent-design.md` §3 for the full pipeline diagram. Eval
sits in the fourth and final slot:

```
plan-agent → todo-agent → exec-agent → Saved dossier: <exec dossier path>
                                                    │
                                                    ▼
eval-agent → Saved verdict: <unified verdict path>
            → Verdict: ACHIEVED | PARTIALLY_ACHIEVED | NOT_ACHIEVED
            → (recommendations in frontmatter point back to one of
               plan-agent / todo-agent / exec-agent for the next turn)
```

When the verdict is NOT_ACHIEVED or PARTIALLY_ACHIEVED, the recommendation forms
the next pipeline iteration:

```
NOT_ACHIEVED, scope shifted   → recommend plan-agent (revise ## Approach + ## Acceptance)
NOT_ACHIEVED, items missing   → recommend todo-agent (doc$update :kind :todo … :add-item …)
NOT_ACHIEVED, items failed    → recommend exec-agent (resume from item K)
PARTIALLY_ACHIEVED, accept    → recommend doc$update :kind :todo … :status :completed (user confirms)
ACHIEVED                      → recommend doc$update :status :completed for todo + plan
```

This is what makes the four-agent pipeline a feedback loop rather than a linear
assembly line.

### Why eval has no "substrate" form

todo, exec, and edit each got a base-agent substrate so a root agent can do the
common-case work inline. Eval is different, and it's worth saying why:
**self-assessment already lives in the base loop.** Every CoAct turn ends with a
`goal-achieved` self-judgment in the answer channel — that *is* the lightweight,
root-agent-inline "did I meet the goal?" check. What eval-agent uniquely adds is
the **formal, evidence-grounded, criterion-by-criterion, audited scoring against a
plan's `## Acceptance`** — inherently a contract-pipeline terminal step, not
something a root agent does ad hoc. So eval-agent is **always the contract path**;
there is nothing to push down to the base.

---

## 4. PRE-FLIGHT — Sufficiency Check

Runs before any scoring work. Walks a fixed checklist; produces a `pre` map.

### 4.1 The Checklist

| Check | What it verifies | How | Fail → action |
| ----- | ---------------- | --- | ------------- |
| C1 | An exec-agent dossier was supplied. | Look for `Saved dossier: <path>` in `:agent-context`; the path's frontmatter `agent` must be `exec-agent`. | GATHER — recommend `(exec-agent {…})` first OR ask the user to paste the dossier path. |
| C2 | The exec dossier's post-flight passed. | `exec$read-dossier :path <path>` → check `post.verdict`. | If `hold` → score with reduced confidence, record `:exec-hold` in degradation; do NOT REFUSE (a degraded verdict still has value). |
| C3 | The exec dossier carries `execute.evidence` with at least one item. | Inspect the `execute.evidence` map. | REFUSE (no evidence to score). v1 does **not** support `:checkbox-only-ok?`. |
| C4 | The plan dossier exists, `post.verdict = pass`, AND `post.acceptance` is populated. | `plan$read-dossier`; assert `post.verdict pass` and `(seq post.acceptance)`. | Empty acceptance → GATHER (recommend plan-agent re-author with explicit `## Acceptance`). Plan post `hold` → score but verdict confidence `:low`, record `:plan-hold`. |
| C5 | The todo dossier (when present) carries `post.acceptance_coverage`. | `todo$read-dossier`; assert `(seq post.acceptance_coverage)`. | INFORMATIONAL — eval rebuilds coverage from evidence + acceptance via `query$llm`; record `:no-coverage-map` in degradation. |
| C6 | The cited edit-agent records (when present) exist on disk. | For each `:edit-agent` `evidence.path`, `bash "test -f <path>"`. | GATHER — list the missing record paths; the exec dossier may be stale. |
| C7 | No earlier eval verdict for this slug+exec-turn already exists. | `(eval$find :slug <slug> :run-record <exec-run-record>)`. | INFORMATIONAL — surface "already scored on <ts>; re-running per user request"; record `pre.is_re_run :true` (do not block). |

Same short-circuit rule as the other agents. Critically, C2 / C4 produce *softer*
verdict-level effects (reduced confidence) rather than hard refusals — eval is the
last line of defense, and a degraded verdict is more useful than no verdict.

### 4.2 The `pre` Map

```clojure
(def pre
  {:verdict             :go            ; :go | :gather | :refuse
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

`pre.acceptance`, `pre.acceptance-coverage`, and `pre.evidence` are direct reads
from upstream dossiers. The `pre.degradation` vector lists every soft-failure that
downgrades verdict confidence — surfaced explicitly in the answer and the verdict
notes.

---

## 5. SCORE — Core Operation (unchanged by the redesign)

For each criterion in `pre.acceptance`, classify into one of:

- **SATISFIED** — at least one evidence record clearly demonstrates the criterion is met.
- **PARTIAL** — evidence shows the criterion is partly met.
- **MISSING** — no evidence speaks to this criterion; execution did not address it.
- **CONTRADICTED** — evidence shows the criterion is NOT met (test failure, metric
  moved the wrong way, OR a flipped checkbox without supporting evidence).

This phase is **pure LLM judgment** — reasoning + `query$llm`. The redesign
deliberately leaves it untouched; there is **no** structured `score` object to
hand to a renderer, and **no** `eval$score-criterion` helper. The model scores in
its head / via `query$llm` and writes the result straight into the verdict file
(§7).

### 5.1 Classification Procedure

For each criterion C in `pre.acceptance`:

1. **Find candidate items.** Read `pre.acceptance-coverage[C]`. If empty
   (degradation `:no-coverage-map`), use `query$llm` to fuzzy-match items against C.
2. **Pull evidence for each candidate.** From `pre.evidence[idx]`, extract:
   - `evidence.ok?` — boolean.
   - `evidence.type` — `:edit-agent` / `:bash` / `:mcp` / `:explore-agent` / `:read-only` / `:manual`.
   - For `:edit-agent`, drill: `(edit$read-record {:path <evidence.path>})`. Read `verify.diff_match`, `verify.lint`, `verify.tests`.
   - For `:bash`, read `evidence.exit` and `evidence.stdout-tail`.
   - For `:manual`, read the user's supplied result, or note `manual-pending`.
3. **Classify.**
   - All candidates `ok? true` AND evidence concretely demonstrates C → SATISFIED (confidence `:high` for diff/test evidence, `:medium` for narrative).
   - At least one `ok? true` and at least one `ok? false` / `manual-pending` → PARTIAL.
   - Zero candidates, OR all `manual-pending` → MISSING.
   - Any candidate `ok? false` with no compensating coverage → CONTRADICTED. (Plus: a flipped checkbox in the todo with no corresponding evidence record → CONTRADICTED with the note "checkbox flipped without evidence — exec-agent post-flight should have caught this.")
4. **Cite.** Record the contributing item idxs, a short evidence excerpt
   (file:path:line, exit code, stdout snippet, mcp result, or edit-agent record
   path), and confidence.
5. **Use `query$llm` for fuzzy criterion language.** Criteria containing words
   like *intuitive / acceptable / reasonable / smooth / polished / elegant /
   clean* are inherently fuzzy. Use `query$llm` to weigh the evidence narrative
   against the criterion text; record the prompt + a one-line summary in the
   verdict notes so the judgement is auditable.

### 5.2 Verdict Aggregation

```text
All criteria SATISFIED                                   → ACHIEVED
≥ 1 SATISFIED, no CONTRADICTED, no MISSING               → ACHIEVED
≥ 1 SATISFIED, no CONTRADICTED, ≥ 1 MISSING / PARTIAL    → PARTIALLY_ACHIEVED
≥ 1 CONTRADICTED OR no SATISFIED at all                  → NOT_ACHIEVED
```

Aggregate confidence:
- All criteria `:high` AND no degradation → verdict `:high`.
- Any `:low` (or any degradation entry present) → verdict `:low`.
- Otherwise → `:medium`.

### 5.3 Recommendations

For each PARTIAL / MISSING / CONTRADICTED criterion, name the cheapest viable next
step AND an exact direct-dispatch call:

- **Spec is wrong / scope shifted** → `plan-agent` (revise `## Approach` + `## Acceptance`).
- **Spec is right, items missing or coarse** → `todo-agent` (`(doc$update {:kind :todo :slug … :add-item …})`).
- **Spec is right, item failed mid-flight** → `exec-agent` (resume; `(doc$update {:kind :todo … :item-idx N :item-done false})` first if there's partial state).
- **Partial result is acceptable as-is** → `(doc$update {:kind :todo … :status :completed})` (user confirms).

The recommendations are written directly into the verdict's `recommendations:`
frontmatter (a block-list of one-line flow-maps) and the `## Recommendations`
body section — the model authors them, not a helper. Shape:

```yaml
recommendations:
  - {criterion: "p99 checkout latency unchanged within ±5%", gap: "manual sample missing", next_agent: exec-agent, next_call: '(exec-agent {:question "Resume; user supplied p99 = 142ms." :agent-context "<exec dossier path>"})'}
  - {criterion: "all checkout/* unit tests green",           gap: "satisfied — no follow-up", next_agent: null, next_call: null}
```

---

## 6. POST-FLIGHT — Confirmation Check

Runs after SCORE. Self-critiques against a fixed rubric.

### 6.1 The Rubric

| Item | Rubric question | Pass criterion |
| ---- | --------------- | -------------- |
| R1 | Every criterion in `pre.acceptance` appears exactly once in the criteria table. | Set equality. |
| R2 | Every classification cites at least one item idx and one evidence excerpt (or explicit "no items" for MISSING). | Mechanical check. |
| R3 | For every CONTRADICTED criterion involving a flipped checkbox without evidence, the body explicitly names "checkbox flipped without evidence." | Grep the criteria. |
| R4 | Confidence enum set on every criterion AND on the overall verdict. | Mechanical check. |
| R5 | For every PARTIAL / MISSING / CONTRADICTED criterion, `recommendations` has one entry with a non-nil `next_call`. | Mechanical check. |
| R6 | Used `query$llm` (and recorded the prompt summary) for every fuzzy criterion. | Regex match against the fuzzy-word list + presence of a recorded LLM call. |
| R7 | The verdict is reproducible: re-derive it in a fresh `query$llm` call from the same plan+todo+exec dossiers; assert it matches. | Instruction-level cross-check (no opt-in flag in v1). |

### 6.2 Verdict (PASS / HOLD — v1)

- **PASS** — every applicable R-item passes.
- **HOLD** — one or more items fail. Record the specific holds in the verdict
  notes, surface them in the answer, and do **not** auto-retry.

> **As-built:** the original design's **REVISE** auto-round (apply a one-shot fix,
> re-run R1–R7 once) is **deferred to v1.5**. In v1 every rubric failure lands in
> HOLD. The `post` map records `:revision_applied false` accordingly.

### 6.3 The `post` Map

```clojure
(def post
  {:verdict           :pass            ; :pass | :hold
   :rubric            {:r1 :pass :r2 :pass :r3 :pass :r4 :pass
                       :r5 :pass :r6 :pass :r7 :pass}
   :revision_applied  false            ; always false in v1 (no REVISE round)
   :holds             []})
```

---

## 7. Output Discipline — ONE Unified File under `.brainyard/agents/eval-agent/`

### 7.1 Directory Layout

```
.brainyard/
├── eval-agent/
│   ├── verdicts/                  ; the unified verdict files (frontmatter + body)
│   │   ├── 20260510-115412-ship-v2-checkout.md
│   │   └── ...
│   ├── INDEX.md                   ; one line per verdict, newest at bottom
│   └── README.md
```

There is **no** separate `dossiers/` directory anymore. The pre-redesign split
(`verdicts/` human body + `dossiers/` machine handoff) is collapsed into a single
file under `verdicts/`: the YAML frontmatter is the machine handoff (what a
downstream re-spec reads via `eval$read-verdict`), the markdown body is the human
report. This is exactly explore-agent's one-file shape.

### 7.2 The Verdict Template (authored directly)

The instruction carries this template verbatim. The model fills the `<…>` slots
and `write-file`s it to `verdicts/<yyyyMMdd-HHmmss>-<slug>.md`. There is **no**
`eval$dossier-frontmatter` / `eval$verdict-write` / `eval$dossier-write`
construction step — the model writes the markdown.

```markdown
---
slug: <slug>
agent: eval-agent
created: <ISO-8601>
verdict: <ACHIEVED | PARTIALLY_ACHIEVED | NOT_ACHIEVED>
confidence: <high | medium | low>
source: {exec_dossier: <path>, todo_dossier: <path>, plan_dossier: <path>, exec_run_record: <path>}
degradation: []                 # soft-fail keywords, if any
is_re_run: <true | false>

criteria:
  - {criterion: "<C1>", class: satisfied,   confidence: high,   items: [0],    evidence: "edit rec …; tests exit 0"}
  - {criterion: "<C2>", class: partial,      confidence: medium, items: [1, 2], evidence: "1 of 2 items has a diff"}
  - {criterion: "<C3>", class: contradicted, confidence: high,   items: [3],    evidence: "checkbox flipped, no record"}

recommendations:
  - {criterion: "<C2>", gap: "second item not done", next_agent: exec-agent, next_call: '(exec-agent {…})'}
  - {criterion: "<C3>", gap: "no evidence",           next_agent: plan-agent, next_call: '(plan-agent {…})'}
---

# Verdict — <slug>: <ACHIEVED | PARTIALLY_ACHIEVED | NOT_ACHIEVED> (confidence: <X>)

## Per-criterion
| Criterion | Class | Confidence | Evidence |
|---|---|---|---|
| <C1> | SATISFIED | high | <edit record path · test exit> |
| <C2> | PARTIAL | medium | <covered vs. missing> |
| <C3> | CONTRADICTED | high | checkbox flipped without an evidence record |

## Recommendations
- **<C2>** — <gap> → `(exec-agent {…})`
- **<C3>** — <gap> → `(plan-agent {…})`

## Notes
<degradation; fuzzy-criterion query$llm summaries; R7 reproducibility note>
```

One `write-file` replaces the two helper-built artifacts. The flow-style
`criteria` / `recommendations` are one line each — easy to emit, and the lenient
`parse-eval-dossier-yaml` reader (the `criteria`/`recommendations` block-list keys
+ the legacy `pre`/`score`/`post`/`handoff` flat-block keys) reads them. Keys map
1:1 to the prior dossier schema, so any verdict-triggered consumer needs only a
path-and-reader tweak.

Frontmatter contract (downstream parsers may rely on these):

| Key | Type | Description |
| --- | ---- | ----------- |
| `slug` | string | Shared with plan / todo / exec for the canonical pipeline. |
| `agent` | string | Always `eval-agent`. |
| `created` | ISO-8601 string | UTC timestamp. |
| `verdict` | `ACHIEVED` \| `PARTIALLY_ACHIEVED` \| `NOT_ACHIEVED` | Overall score. |
| `confidence` | `high` \| `medium` \| `low` | Aggregate confidence. |
| `source` | flow-map | `exec_dossier` / `todo_dossier` / `plan_dossier` / `exec_run_record` paths (kept raw on read; substring-searchable for C7). |
| `criteria` | block-list of flow-maps | Per-criterion `{criterion class confidence items evidence}`. Kept as raw strings on read. |
| `recommendations` | block-list of flow-maps | Each names `criterion / gap / next_agent / next_call`. |
| `degradation` | vector of keywords | Soft failures that lowered verdict confidence (e.g. `:no-coverage-map`). |
| `is_re_run` | boolean | True when a prior verdict existed for this exec run. |

### 7.3 INDEX.md

Append-only (`write-file :append true`), one line per verdict, newest at bottom:

```markdown
- 2026-05-10 11:54 [ship-v2-checkout](verdicts/20260510-115412-ship-v2-checkout.md) — ACHIEVED · high · → user
```

Format: `- <YYYY-MM-DD HH:MM> [<slug>](verdicts/<file>.md) — <VERDICT> · <confidence> · → <next-agent>`.

### 7.4 ANSWER Format

```
Saved verdict: <verdict path>
Verdict: <ACHIEVED|PARTIALLY_ACHIEVED|NOT_ACHIEVED> (confidence: <high|medium|low>)
Next: <handoff.next_call>
```

For non-ACHIEVED cases, append a `Recommended:` block listing the per-criterion
next-calls:

```
Saved verdict: <verdict path>
Verdict: NOT_ACHIEVED (confidence: medium)
Recommended:
  - "p99 latency": (exec-agent {…})
  - "all unit tests green": (todo-agent {…})
Next: (plan-agent {:question "Revise approach: …" :agent-context "<this verdict path>"})
```

GATHER / REFUSE / HOLD variants follow the family pattern (`Need:` / `Refused:` /
`Hold:` + `Suggested:`). There is only ONE `Saved verdict:` line — the unified
file — never a separate `Saved dossier:`.

---

## 8. Instruction (System Prompt Body)

Layered on top of `coact-agent`'s instruction by `run-coact-derived`. The
three-phase pipeline, hard rules, and handoff discipline are intact from the
original design; the PERSIST section tells the model to write ONE unified markdown
file directly from the §7.2 template (the helper chain is gone), and POST-FLIGHT
is PASS/HOLD. Verbatim text lives in `eval_agent.clj`; the shape:

```text
You are an EVAL-agent. You score whether an executed todo met its source
plan's acceptance criteria. You are READ-ONLY and ADVISORY toward UPSTREAM
artifacts. You ALWAYS produce ONE unified verdict file (YAML frontmatter =
machine handoff, body = human report) — authored directly with write-file.

THE THREE PHASES (every turn)
  PRE-FLIGHT  — sufficiency check. Output: GO | GATHER | REFUSE.
  SCORE       — only on GO. Per-criterion classification → verdict.
  POST-FLIGHT — only after SCORE. Self-critique against the 7-item rubric.
                Output: PASS | HOLD.   (REVISE auto-round deferred to v1.5.)
  PERSIST     — always. ONE unified verdict file under verdicts/ (write-file).
  ANSWER      — `Saved verdict:`, `Verdict: <X> (confidence: Y)`, `Next:`
                (and `Recommended:` for non-ACHIEVED).

[PRE-FLIGHT C1–C7 — §4.1; SCORE — §5; POST-FLIGHT R1–R7 — §6.1]

PERSIST — ONE unified verdict file:
  The verdict file's YAML frontmatter is the machine handoff and its body is
  the human report. Author it DIRECTLY with write-file — there are NO
  eval$dossier-* / eval$verdict-write / eval$next-handoff helpers. Fill the
  VERDICT TEMPLATE (§7.2), write-file to verdicts/<ts>-<slug>.md, append one
  INDEX line. The `Next:` line comes from the verdict→next rule table.

HARD RULES
1. NO mutating plans, todos, or exec records. Read-only across upstream.
2. NO inventing acceptance criteria. If plan acceptance is empty, GATHER.
3. NO inferring satisfaction from a flipped checkbox alone → CONTRADICTED.
4. NO clone-self dispatch. Direct kebab-case dispatch to other registered
   agents (e.g. `(exec-agent {…})`) is fine.
5. NO writing outside .brainyard/agents/eval-agent/. write-file is bound ONLY
   to author your own verdict file there; update-file is NOT bound.
6. NO auto-dispatching plan/todo/exec recommendations. RECOMMEND only.
   Sub-dispatch ONLY when the user explicitly says "and apply" (v1 ships
   without this opt-in flag).
7. NEVER skip the verdict file — even REFUSE/GATHER turns write ONE file.
8. NEVER mark confidence higher than the evidence supports.
```

---

## 9. Tool-Context (How to Use the Bound Tools)

```text
## Eval Tools — read upstream dossiers, score, write ONE unified verdict

UPSTREAM DOSSIER ACCESS (READ-ONLY)
- exec$read-dossier  -- frontmatter parse of an exec-agent dossier. Returns
                        :execute :evidence :post, plus links to :plan_dossier
                        and :todo_dossier.
- todo$read-dossier  -- frontmatter parse of a todo-agent dossier. Returns
                        :post.acceptance_coverage, :pre.acceptance.
- plan$read-dossier  -- frontmatter parse of a plan-agent dossier. Returns
                        :post.acceptance, :post.verdict.
- edit$read-record   -- DRILL from an exec evidence entry's :path to the
                        underlying edit-agent record. Returns :verify :apply
                        :rollback. (Cherry-picked — only read-record is bound.)

PLAN / TODO BODY ACCESS (READ-ONLY, fallback only)
- doc$read :kind :plan :slug <s>  — plan body when the plan dossier is absent.
- doc$read :kind :todo :slug <s>  — todo body for checkbox vs evidence (R3).

NOT BOUND HERE (deliberate — eval is read-only toward upstream):
- doc$create / doc$update / doc$delete   — would mutate plans/todos.
- write-side edit helpers                — would mutate source / edit records.
- update-file                            — eval writes whole files, not patches.
- fetch-url                              — web is explore-agent's surface.
- bash with redirection / mutating cmds  — refused by the read-only bash guard.
(write-file IS bound — but ONLY to author your own verdict under verdicts/.)

REASONING
- query$llm — used heavily in SCORE for fuzzy criteria and in POST-FLIGHT R7
              for the reproducibility cross-check. FLAT only (no query$clone).

PERSISTENCE — write ONE unified verdict file directly (NO eval$dossier-* tools)
- Author from the VERDICT TEMPLATE with ONE write-file, then append one INDEX
  line (write-file :append true). The handoff comes from the verdict→next rule
  table — there is no eval$next-handoff.
- eval$read-verdict :path <p>  — READ-ONLY frontmatter parse of a verdict file
  (downstream re-spec, or inspecting a prior verdict). Also dual-reads legacy
  pre/score/post/handoff dossiers.
- eval$find :slug <s> :run-record <r>  — READ-ONLY: prior verdicts newest-first
  (the C7 double-score check).

AUTO-PERSIST SAFETY NET
A gated :agent.ask/finalize hook (scoped to :eval-agent) fills the VERDICT
TEMPLATE from your answer text, writes ONE file, and injects the absent
`Saved verdict:` line if you skip PERSIST. Backstop — author your own verdict.

CROSS-AGENT DISPATCH (only on user opt-in — v1 does not auto-apply)
- (plan-agent {…}) / (todo-agent {…}) / (exec-agent {…})
```

---

## 10. Behavior Tree — Inherited As-Is

`eval-agent` does **not** define its own BT. `run-coact-derived` falls back to
`coact-agent`'s `:bt-factory` (pinned explicitly via `coact/coact-behavior-tree`,
mirroring explore/plan/todo/exec/edit-agent). Iteration shape:

| Iter | Channel | Body |
| ---- | ------- | ---- |
| 1 | code | PRE-FLIGHT C1–C7. `def pre`. |
| 2 | tool / code | (only on GO) Read upstream dossiers; drill edit records. `def evidence-trees`. |
| 3 | code | SCORE — per-criterion classification with `query$llm` where fuzzy. Build `score` in-head. |
| 4 | code | POST-FLIGHT rubric. `def post`. (v1: no auto-revise — failures land in HOLD.) |
| 5 | code | PERSIST — fill the VERDICT TEMPLATE; `(write-file …)` ONE file; append INDEX line. |
| 6 | answer | `Saved verdict:` + `Verdict:` + (`Recommended:` for non-ACHIEVED) + `Next:`. |

No new BT actions, schemas, or SCI bindings are required.

---

## 11. Demonstration: "Did we actually ship checkout v2?"

`:agent-context = "Saved dossier: .brainyard/agents/exec-agent/dossiers/20260510-110131-ship-v2-checkout.md"`.

### Iteration 1 — PRE-FLIGHT (typed reads)

```clojure
(def exec-d  (exec$read-dossier {:path ".brainyard/agents/exec-agent/dossiers/...md"}))
(def todo-d  (todo$read-dossier {:path (:todo_dossier exec-d)}))
(def plan-d  (plan$read-dossier {:path (:plan_dossier exec-d)}))

(def pre
  {:verdict :go
   :checks {:c1 :pass
            :c2 (if (= "pass" (-> exec-d :post :verdict)) :pass :informational)
            :c3 (if (seq (-> exec-d :execute :evidence)) :pass :fail)
            :c4 (if (and (= "pass" (-> plan-d :post :verdict))
                         (seq (-> plan-d :post :acceptance)))
                  :pass :fail)
            :c5 (if (seq (-> todo-d :post :acceptance_coverage)) :pass :informational)
            :c6 :pass            ; test -f over each :edit-agent evidence.path
            :c7 :informational}  ; (eval$find :slug … :run-record …)
   :slug (:slug exec-d)
   :acceptance (-> plan-d :post :acceptance)
   :acceptance-coverage (-> todo-d :post :acceptance_coverage)
   :evidence (-> exec-d :execute :evidence)
   :degradation []
   :is-re-run false})
```

### Iterations 2–4 — SCORE + POST-FLIGHT (judgment)

Per-criterion classification runs in reasoning + `query$llm` (fuzzy criteria, R7
cross-check). No structured `score` object is assembled for a renderer — the
results are written straight into the verdict file in the next step.

### Iteration 5 — Author the unified verdict directly (one write)

```clojure
(def ts (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss") (java.util.Date.)))
(def path (str ".brainyard/agents/eval-agent/verdicts/" ts "-ship-v2-checkout.md"))

(write-file
  {:path path
   :content (str
     "---\n"
     "slug: ship-v2-checkout\n"
     "agent: eval-agent\n"
     "created: " (java.time.Instant/now) "\n"
     "verdict: ACHIEVED\n"
     "confidence: high\n"
     "source: {exec_dossier: " (:exec-dossier pre) ", plan_dossier: " (:plan-dossier pre) "}\n"
     "degradation: []\n"
     "is_re_run: false\n\n"
     "criteria:\n"
     "  - {criterion: \"feature-flag checkout-v2 toggleable from staging admin\", class: satisfied, confidence: high, items: [0], evidence: \"edit rec flags.clj:42\"}\n"
     "  - {criterion: \"all checkout/* unit tests green\", class: satisfied, confidence: high, items: [1, 2], evidence: \"bash exit 0; 18 tests\"}\n"
     "  - {criterion: \"p99 checkout latency unchanged within ±5%\", class: satisfied, confidence: medium, items: [3], evidence: \"manual: p99 142ms vs 138ms baseline\"}\n\n"
     "recommendations: []\n"
     "---\n\n"
     "# Verdict — ship-v2-checkout: ACHIEVED (confidence: high)\n\n"
     "## Per-criterion\n"
     "| Criterion | Class | Confidence | Evidence |\n|---|---|---|---|\n"
     "| feature-flag checkout-v2 toggleable | SATISFIED | high | edit-agent: flags.clj:42 |\n"
     "| all checkout/* unit tests green | SATISFIED | high | bash exit 0 (18 tests) |\n"
     "| p99 latency within ±5% | SATISFIED | medium | manual: 142ms vs 138ms |\n\n"
     "## Recommendations\n- None — all criteria satisfied.\n\n"
     "## Notes\nNo degradation; no fuzzy criteria; R7 cross-check matched.\n")})

;; INDEX append (newest at bottom, append-only)
(write-file
  {:path ".brainyard/agents/eval-agent/INDEX.md"
   :append true
   :content (str "- " (subs (str (java.time.Instant/now)) 0 16)
                 " [ship-v2-checkout](verdicts/" ts "-ship-v2-checkout.md)"
                 " — ACHIEVED · high · → user\n")})
```

### Iteration 6 — Answer

```markdown
## Verdict — Ship v2 checkout: ACHIEVED (confidence: high)

PRE-FLIGHT: GO (all 7 checks passed; no degradation)
SCORE:
  - "feature-flag checkout-v2 toggleable from staging admin" → SATISFIED (high) [item 0]
  - "all checkout/* unit tests green" → SATISFIED (high) [items 1, 2]
  - "p99 checkout latency unchanged within ±5%" → SATISFIED (medium) [item 3]
POST-FLIGHT: PASS

Saved verdict: .brainyard/agents/eval-agent/verdicts/20260510-115412-ship-v2-checkout.md
Verdict: ACHIEVED (confidence: high)
Next: After user confirms: (doc$update {:kind :todo :slug "ship-v2-checkout" :status :completed}) and (doc$update {:kind :plan :slug "ship-v2-checkout" :status :completed})
```

A downstream `plan-agent` invocation (for a NOT_ACHIEVED re-spec) is passed *just
that path* in its `:agent-context`; it reads the frontmatter alone via
`eval$read-verdict` (cheap) before deciding whether to read the full body.

---

## 12. `eval.clj` — Read Seams + Backstops Only

The write-side helper chain is **retired**. `eval.clj` ships exactly two
read/discovery helpers, registered in `eval-dossier-helpers` and auto-bound in the
SCI sandbox:

| Helper | Signature | What it does |
| --- | --- | --- |
| `eval$read-verdict` | `(eval$read-verdict :path …)` → parsed frontmatter map | Frontmatter-only parse of a unified verdict file. Surfaces `verdict` / `confidence` / `source` / `criteria` / `recommendations` / `degradation` / `is_re_run`, and **dual-reads** legacy `pre`/`score`/`post`/`handoff` nested-block dossiers. (Renamed from the old `eval$read-dossier`.) |
| `eval$find` | `(eval$find :slug … :run-record …)` → `{:matches […] :n-matches N}` | Prior verdicts for a slug, newest-first; optional `run-record` substring-match against the `source` flow-map. The C7 double-score gate. |

**Retired** (do not exist anymore): `eval$dossier-slug`,
`eval$dossier-frontmatter`, `eval$dossier-write`, `eval$dossier-index-append`,
`eval$verdict-write`, `eval$next-handoff`. The never-shipped aspirational helpers
`eval$preflight` / `eval$postflight` / `eval$score-criterion` are likewise absent
— PRE-FLIGHT / SCORE / POST-FLIGHT run in the instruction + `query$llm`, not as
single calls.

The internal YAML emitters (`build-eval-verdict-frontmatter*`, `yaml-flow-map`,
etc.) still exist but back **only** the auto-persist backstop (which emits empty
`criteria` / `recommendations`); the happy path never calls them. `handoff-from-state`
is a pure function (not a command) backing the backstop's INDEX next-agent column
and injected `Next:` line.

### 12.1 Auto-Persist Backstop (`:agent.ask/finalize`)

`eval.clj` installs an `:agent.ask/finalize` hook (scoped to `:eval-agent`, via
`install-auto-persist!`) that materializes a verdict when the LLM skips PERSIST
(common on smaller models). It is **not** the primary path — a safety net — and it
writes the *same* one unified file the happy path uses:

- `materialize-auto-dossier!` detects the pre/score verdict + confidence from the
  answer text, fills the §7.2 template (frontmatter with empty per-criterion
  blocks + an `## Original answer` body section), `spit`s **one** file under
  `verdicts/`, and appends one INDEX line.
- It injects the `Saved verdict: <path>` line into the answer if absent (a
  `:replace` decision).
- It is idempotent (an on-disk `verdict-already-saved?` check that also recognizes
  the legacy `Saved dossier:` marker).

Because the hook is observe-only on the answer text, a missing `Saved verdict:`
line does **not** mean nothing was saved — consumers fall back to `eval$find` or
the newest INDEX entry.

### 12.2 Read-Only Bash Guard (`:agent.tool-use/pre`)

Eval's contract is non-mutation and its prompt promises read-only bash, but the
roster binds the unrestricted `bash` tool. `install-bash-guard!` registers a gated
`:agent.tool-use/pre` hook (scoped to `:eval-agent`) that **refuses**
obviously-mutating bash via a `:replace` verdict carrying an error result — the
command never runs and the turn continues, so the LLM sees the refusal and adapts.
The best-effort denylist covers `rm`/`mv`/`cp`/`mkdir`/`chmod`/…, mutating `git`
subcommands (`commit`/`reset`/`push`/`add`/…), `sed -i` / `perl -i`, and file
redirects to a real path (not `/dev/null`). It is a code backstop behind the
prompt's read-only contract, not a full shell sandbox.

---

## 13. Handoff Mechanics — How Other Agents Consume the Verdict

When the verdict is NOT_ACHIEVED or PARTIALLY_ACHIEVED, the unified verdict file's
frontmatter is consumed by the next agent in the loop:

- `plan-agent` reads it when the verdict says "scope shifted" / "spec wrong" —
  uses the CONTRADICTED criteria + recommendations to decide what to revise in
  `## Approach` / `## Acceptance`.
- `todo-agent` reads it when the verdict says "items missing" — uses MISSING
  criteria to know which `doc$update :kind :todo … :add-item` calls to make.
- `exec-agent` reads it when the verdict says "items failed" — uses CONTRADICTED
  criteria to know which items to reset (`doc$update :kind :todo … :item-done
  false`) and resume.

Each downstream agent treats the eval verdict the way it treats any upstream
dossier: pre-flight C1 widens to accept either a plan/todo/exec dossier OR an eval
verdict in `:agent-context`. The verdict's `recommendations` frontmatter +
`Next:` line are the source of truth. Two cheap-read levels:

- **Cheap (~frontmatter):** `(eval$read-verdict :path "<path>")` — verdict,
  confidence, criteria classes, recommendations. Sufficient for routing.
- **Full:** `(read-file {:path "<path>"})` — the human report body.

For the user-facing case (`next_agent: user`), the recommendation is a one-line
instruction the user can copy into their TUI.

---

## 14. Migration — Complete

### 14.1 Lightweight authoring + unification (2026-06, done)

The redesign landed as:

1. New `eval_agent.clj` instruction/tool-context: the PERSIST section writes ONE
   unified file from the VERDICT TEMPLATE; POST-FLIGHT is PASS/HOLD; hard rules
   forbid clone-self dispatch and writing outside `eval-agent/`. `write-file` is
   bound; `update-file` / `fetch-url` are removed from the file-tools roster.
2. Slimmed `eval.clj`: removed the six write-side helpers + their tests; renamed
   `eval$read-dossier` → `eval$read-verdict` (dual-reading legacy dossiers); kept
   `eval$find`; rewrote the auto-persist hook to fill the §7.2 template and `spit`
   one file; added the read-only bash guard.
3. **Verdict/dossier unification.** New turns write one file under `verdicts/`;
   `eval$read-verdict` dual-reads both the unified file and legacy
   `pre`/`score`/`post`/`handoff` dossiers for one release. Keys map 1:1 to the
   prior dossier schema, so a verdict-triggered re-spec needs only the
   reader/path tweak — **no upstream agent changes**.

### 14.2 No file-move task

Eval had no on-disk artifacts before the original three-phase redesign, so there
is no `bb migrate:eval-agent` copy step. Eval outputs are advisory + regenerable;
old dossiers age out under the dual-read.

---

## 15. Verification

| Benchmark | Shape | What it verifies |
| --------- | ----- | ---------------- |
| Pre-flight GO happy path | Valid exec dossier; plan + todo dossiers present | C1–C7 pass; SCORE runs; POST-FLIGHT PASS; one verdict file produced. |
| Pre-flight GATHER (no exec dossier) | `:agent-context` empty or a non-exec dossier | C1 fails; recommends exec-agent; still writes one (GATHER) verdict file. |
| Pre-flight REFUSE (no evidence) | Exec dossier with empty `execute.evidence` | C3 fails → REFUSE (v1 has no `:checkbox-only-ok?`); one verdict file written. |
| Degradation (plan no acceptance) | Plan dossier `post.acceptance` empty | C4 → GATHER. |
| Degradation (no coverage map) | Todo dossier missing `post.acceptance_coverage` | C5 sets `:no-coverage-map`; SCORE rebuilds coverage via `query$llm`. |
| **Authoring (unified)** | Non-trivial GO run | Writes exactly ONE verdict file via `write-file`; frontmatter parses; `verdict`/`confidence`/`criteria`/`recommendations` populated; body has the per-criterion table. |
| **Read-back** | `eval$read-verdict` on the written file | Returns all machine keys (verdict, confidence, criteria, recommendations). |
| ACHIEVED verdict | All criteria SATISFIED | Verdict ACHIEVED; high confidence; recommendation names `doc$update :status :completed` for todo + plan. |
| PARTIALLY_ACHIEVED | One MISSING criterion | Verdict PARTIALLY_ACHIEVED; recommendation includes todo-agent for the missing criterion. |
| NOT_ACHIEVED (test failure) | Bash item exit=1 | Verdict NOT_ACHIEVED, CONTRADICTED on the affected criterion; recommendation routes to exec-agent (resume). |
| **CONTRADICTED flip** | Todo item N flipped, no exec evidence for N | Criterion → CONTRADICTED with the explicit "checkbox flipped without evidence" note (R3). |
| Fuzzy criterion | Acceptance like "checkout feels intuitive" | `query$llm` invoked; prompt summary recorded; confidence `:medium` at best. |
| Drill to diff | SATISFIED criterion with `:edit-agent` evidence | Verdict cites the edit-agent record path; the record is present (C6). |
| **Post-flight HOLD** | First-pass forgot a recommendation for a CONTRADICTED criterion | R5 fails → HOLD (no v1 auto-revise); answer surfaces the hold. |
| Double-score (C7) | Re-run on the same exec turn | `eval$find` surfaces a prior verdict; `is_re_run: true` recorded. |
| Read-only bash guard | A mutating bash (`rm …`) from eval-agent | Refused via `:replace`; the command never runs; the turn continues. |
| Index integrity | Append 100 verdicts | INDEX.md has 100 lines, newest at bottom. |
| Auto-persist backstop | Skipped write | Hook materializes a parseable one-file verdict; `Saved verdict:` line injected. |
| Upstream reads unchanged | Eval drills exec→edit-record; pulls acceptance from plan dossier | No upstream agent changes needed. |

mulog signals: `::eval.find`, `::eval.verdict-index`, `::eval.auto-persist`,
`::eval.auto-persist-failed`.

---

## 16. Files Summary

| File | Role |
| ---- | ---- |
| `components/agent/src/ai/brainyard/agent/common/eval_agent.clj` | `instruction` (three-phase pipeline + write-one-unified-file PERSIST + VERDICT TEMPLATE), `tool-context`, `defagent eval-agent` via `coact/run-coact-derived`. `write-file` bound; `update-file`/`fetch-url` stripped. Cherry-picks `edit$read-record`; binds plan/todo/exec read-dossier helpers + `eval-dossier-helpers`. |
| `components/agent/src/ai/brainyard/agent/common/eval.clj` | Read seams (`eval$read-verdict`, `eval$find`) + the `:agent.ask/finalize` auto-persist backstop + the `:agent.tool-use/pre` read-only bash guard. Write-side helper chain removed. |
| `components/agent/test/ai/brainyard/agent/eval_agent_test.clj` | Registration smoke test, unified-authoring test, read-back, CONTRADICTED-flip, double-score, bash-guard, and auto-persist tests. |
| `.brainyard/agents/eval-agent/README.md` | Directory-layout cheat-sheet. |
| `components/agent/src/ai/brainyard/agent/common/coact_agent.clj` | NO CHANGES — substrate, BT, sandbox, DSPy signature untouched. |

The feature is one agent file plus a slim read-only helpers file. The redesign was
contained in the instruction + the shrunk helpers namespace — the roster shape
(read seams + `write-file`), BT, sandbox, and DSPy signature were untouched.

---

## 17. Open Questions

1. **REVISE auto-round (v1.5).** Restore the one-shot POST-FLIGHT fix (re-run
   R1–R7 once) that v1 defers to HOLD. The `post` map already carries
   `:revision_applied` for it.
2. **`:checkbox-only-ok?` opt-in.** Wire the deliberate checkbox-only fallback so
   C3 can degrade-and-score instead of REFUSE when the user explicitly asks.
3. **Verdict-triggered auto-dispatch.** A `:auto-apply? true` flag to call
   plan/todo/exec directly on an unambiguous verdict. Opt-in only, default off;
   revisit once the pipeline is dossier-threaded end to end.
4. **R7 reproducibility cost.** The second `query$llm` to re-derive the verdict is
   non-trivial in tokens. Promote it to a recorded opt-in pass for high-stakes
   (ACHIEVED) verdicts only; skip for NOT_ACHIEVED where the recommendations make
   the verdict actionable regardless.
5. **Delta from a prior verdict.** When the same slug is re-scored, the verdict
   could include a "since previous verdict" diff (which criteria moved
   categories). Cheap to add; defer.
6. **Multi-plan acceptance.** Eval scopes to one plan today; multi-plan execution
   would need a `pre.plan-dossiers` vector. Defer until a use case appears.
```

