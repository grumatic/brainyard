# Eval-Agent — Lightweight, File-Tool Redesign (revision 3)

> **Status:** Proposal. Sixth and final in the series after
> [`plan`](./plan-agent-lightweight-redesign.md),
> [`explore`](./explore-agent-lightweight-redesign.md),
> [`todo`](./todo-agent-lightweight-redesign.md),
> [`exec`](./exec-agent-lightweight-redesign.md), and
> [`edit`](./edit-agent-design.md). Applies the **same argument as plan-agent**:
> the three-phase *contract* (PRE-FLIGHT → SCORE → POST-FLIGHT, plus the
> verdict + dossier) is **kept**; what changes is *how the agent writes its
> artifacts* — direct markdown authoring, retiring the persist-side helper chain.
>
> Eval-agent is the cleanest case for the argument, because its core work —
> **scoring acceptance criteria against evidence — is pure LLM judgment** (it
> already runs on `query$llm`). The micro-tools never helped the scoring; they
> only built the output. So the redesign is almost entirely "let the model write
> its report," plus one eval-specific simplification: **collapse the
> verdict/dossier split into a single frontmatter+body file** (§5).
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/eval_agent.clj`,
> `eval.clj`. Naming aligns with the adopted `edit-agent` rename (eval already
> reads `edit$read-record` / `:via :edit-agent`).

---

## 1. Why the current authoring path is error-prone

Eval-agent does its *judgment* in prose and via `query$llm` (good — that's
inherent capability), but it *persists* through the series' heaviest structured
helper chain. A single PASS turn asks the LLM to:

1. Build a `score` object — and it is the **richest structured artifact in the
   whole agent set**: `{:verdict … :confidence … :criteria [{:criterion …
   :class … :confidence … :items [...] :evidence [{:type … :record … :excerpt …}]}
   …] :gaps [...] :recommendations [{:criterion … :gap … :next-agent …
   :next-call …}] :degradation [...]}` — a vector of per-criterion maps each
   carrying a nested evidence vector-of-maps, plus a parallel recommendations
   vector-of-maps.
2. Feed `pre`, `score`, `post` (each a precisely-keyed map) to
   `eval$dossier-frontmatter`, which renders them into YAML with **criteria as a
   flow-vector of flow-maps** and recommendations the same.
3. `eval$verdict-write` the human verdict body (a *second* artifact).
4. `eval$dossier-write` the dossier (frontmatter-prefix validated).
5. `eval$dossier-index-append` with the verdict + confidence re-passed.
6. `eval$next-handoff` to compute the recommended next agent.

Two artifacts, six structured calls, and a `score` object whose nested
vectors-of-maps the model must construct *before it can persist anything*. As
with plan-agent, the shared keys must agree across calls (the verdict in step 2
== the index verdict in step 5 == the handoff basis in step 6). A mis-keyed
`:next-call` vs `next_call`, a criterion emitted as a string instead of a map, a
flow-map quoting slip — any of these errors the call and burns an iteration.

And the same tell-tale crutch is present: an `:agent.ask/post` **auto-persist
hook** that "reconstructs a minimal dossier from your answer text" when the LLM
skips the helpers. We built the safety net because the structured path leaks.

Meanwhile the thing the model is *best* at — writing a clear per-criterion
verdict report with a table and recommendations — is exactly what we route
around by making it assemble `score` and hand it to a YAML renderer.

## 2. Thesis

Eval-agent already does the hard part the right way: **scoring is LLM
judgment**, performed in reasoning + `query$llm`. Keep that untouched. Then let
the model **write its verdict report and the machine-readable handoff as
markdown**, instead of constructing a `score` object for a renderer. Keep
deterministic machinery only where a machine beats the model — **reading the
upstream dossiers** (the evidence inputs) and **finding prior verdicts** (the
double-score check).

In one line, exactly as plan-agent: **authoring becomes prose; only reading
stays typed** — and for eval, the "authoring" is a report, which is the model's
home turf.

## 3. Design principles

1. **Writes are LLM-inherent.** The verdict report + the handoff frontmatter are
   authored with `write-file` from a template. No `eval$dossier-frontmatter`, no
   `eval$dossier-write`, no `eval$verdict-write`, no `eval$next-handoff`.
2. **Reads stay deterministic — eval is the proof case.** Eval consumes FOUR
   typed inputs (`exec$read-dossier`, `plan$read-dossier`, `todo$read-dossier`,
   `edit$read-record`) and finds priors via `eval$find`. These are pure
   mechanism and **all stay**. Eval-agent is the strongest validation of the
   series' "keep reads typed" rule: parsing four upstream dossiers by hand would
   be far more error-prone than the scoring itself.
3. **Scoring is judgment, not mechanism — and it's already done right.** The
   per-criterion classification, verdict aggregation, confidence, fuzzy-criterion
   handling, and the R7 reproducibility cross-check are `query$llm` /
   reasoning. Nothing to retire here; the redesign does **not** touch SCORE.
4. **The contract is the template, not a `score` object.** The machine-readable
   keys (verdict, confidence, per-criterion class, recommendations) survive as
   *frontmatter the model writes*, enforced by showing the template + validating
   on read — not by a constructor.
5. **One verdict file per turn** (§5 unifies the two artifacts). REFUSE/GATHER
   turns still produce one file — the audit trail.
6. **Degrade gracefully.** A keyword typo can't fail a write; a malformed
   frontmatter line is caught on read by the lenient parser; the slimmed
   auto-persist hook still backstops a skipped file.

## 4. What stays, what goes

| Concern | Today | Redesign |
| --- | --- | --- |
| Score the criteria (SCORE phase) | reasoning + `query$llm` | **Unchanged** — inherent LLM judgment. |
| Upstream inputs (C1–C6) | `exec$read-dossier` / `plan$read-dossier` / `todo$read-dossier` / `edit$read-record` | **Keep all** — deterministic read seams (the evidence). |
| Double-score check (C7) | `eval$find` | **Keep** — read-only. |
| Build `score` → YAML | `eval$dossier-frontmatter` (criteria flow-vector-of-maps) | **Removed.** Model writes the frontmatter from the §5 template. |
| Verdict body (human) | `eval$verdict-write` | **Removed.** `write-file` the report markdown (the §5 body). |
| Dossier write (machine) | `eval$dossier-write` | **Removed.** Same file as the report — unified (§5). |
| INDEX prepend | `eval$dossier-index-append` | `write-file :append` one line (or skip; §6). |
| Handoff computation | `eval$next-handoff` | Model writes the `handoff:` block + `Next:`/`Recommended:` lines from a verdict→next rule table. |
| Verdict read (downstream re-spec) | `eval$read-dossier` | **Keep** (read seam; renamed `eval$read-verdict` if unified — §5). |
| Auto-persist net | rebuilds via helpers | **Keep, simplified** — fills the §5 template, `spit`s one file. |

Net: five persist-side helpers retire (`eval$dossier-frontmatter`,
`eval$verdict-write`, `eval$dossier-write`, `eval$dossier-index-append`,
`eval$next-handoff`); the read seams (four upstream + `eval$find` +
`eval$read-verdict`) stay; SCORE is untouched. `write-file`/`read-file` get
bound (today stripped by the `remove` clause in `eval_agent.clj`); the agent
stays read-only toward *upstream* artifacts — it only writes its own
`.brainyard/agents/eval-agent/` output.

## 5. Unify verdict + dossier into one file (the eval-specific simplification)

Today eval writes **two** artifacts: a human verdict body under `verdicts/` and
a schema'd dossier under `dossiers/`. That split exists so downstream can read
cheap frontmatter without the prose — but **explore-agent already proves you get
that from one file**: YAML frontmatter (machine) + markdown body (human), with a
frontmatter-only reader for cheap routing. Collapse eval's two artifacts into
one, exactly that shape:

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
  - {criterion: "<C1>", class: satisfied,    confidence: high,   items: [0],    evidence: "edit rec …; tests exit 0"}
  - {criterion: "<C2>", class: partial,       confidence: medium, items: [1, 2], evidence: "1 of 2 items has a diff"}
  - {criterion: "<C3>", class: contradicted,  confidence: high,   items: [3],    evidence: "checkbox flipped, no record"}

recommendations:
  - {criterion: "<C2>", gap: "second item not done", next_agent: exec-agent, next_call: '(exec-agent {…})'}
  - {criterion: "<C3>", gap: "no evidence",           next_agent: plan-agent, next_call: '(plan-agent {…})'}
---

# Verdict — <slug>: <ACHIEVED | PARTIALLY_ACHIEVED | NOT_ACHIEVED> (confidence: <X>)

## Per-criterion
| Criterion | Class | Confidence | Evidence |
|---|---|---|---|
| <C1> | SATISFIED | high | <edit record path · test exit> |
| <C2> | PARTIAL | medium | <what's covered vs. missing> |
| <C3> | CONTRADICTED | high | checkbox flipped without an evidence record |

## Recommendations
- **<C2>** — <gap> → `(exec-agent {…})`
- **<C3>** — <gap> → `(plan-agent {…})`

## Notes
<degradation, fuzzy-criterion query$llm summaries, R7 reproducibility note>
```

One `write-file` to `.brainyard/agents/eval-agent/verdicts/<ts>-<slug>.md`
replaces two helper-built artifacts. The frontmatter carries everything a
downstream re-spec needs (`verdict`, `confidence`, `criteria`,
`recommendations`); the body is the human report. The flow-style `criteria` /
`recommendations` are one line each — easy to emit, and the existing lenient
parser reads them. Keys map to the prior dossier schema (`eval-agent-design.md`
§7.3) so any consumer needs only a path-and-reader tweak.

> If the verdict/dossier split must be preserved (some workflow reads them
> separately), keep two `write-file`s from two templates — but it's still two
> direct writes, not six helper calls. Unify is the recommendation.

## 6. The read seams worth keeping

Eval-agent is the agent that most depends on typed reads, which is why they all
stay — they are the *evidence*, and parsing them by hand would dwarf the
scoring in error rate:

- **`exec$read-dossier`** — the primary input: `execute.evidence`, `post`,
  links to plan/todo dossiers.
- **`plan$read-dossier`** — `post.acceptance` (the criteria being scored),
  `post.verdict`.
- **`todo$read-dossier`** — `post.acceptance_coverage` (criterion → items).
- **`edit$read-record`** — drills an `:via :edit-agent` evidence entry to its
  `verify.diff_match` / `lint` / `tests` for diff-level scoring.
- **`eval$find`** — prior verdicts for this exec run (C7 double-score check).
- **`eval$read-verdict`** — frontmatter-only read of an eval output (downstream
  re-spec; the renamed read seam if §5 unifies).

All deterministic; none carry the persist-side brittleness this redesign
targets. Keeping them identical means **no upstream agent changes** — the
redesign is contained in eval-agent's persist path.

## 7. Eval has no useful "substrate" form (closing the series symmetrically)

todo, exec, and edit each got a **base-agent substrate** so any agent can do the
common-case work inline without the subagent. Eval is different, and it's worth
saying why: **self-assessment already lives in the base loop.** Every CoAct turn
ends with a `goal-achieved` self-judgment in the answer channel — that *is* the
lightweight, root-agent-inline "did I meet the goal?" check. There is no
separate "working eval" worth a substrate.

What eval-agent uniquely adds is the **formal, evidence-grounded,
criterion-by-criterion, audited scoring against a plan's `## Acceptance`,
cross-referencing exec evidence and edit-record diffs** — inherently a
contract-pipeline terminal step, not something a root agent does ad hoc. So,
unlike its siblings, eval-agent is **always the contract path**; there's nothing
to push down to the base. That asymmetry is the right note to end the series on:
substrates exist for work that recurs informally (track, execute, edit);
formal scoring doesn't recur informally — `goal-achieved` already covers the
casual case.

## 8. New instruction & tool-context (sketch)

eval-agent keeps the three phases and hard rules; only PERSIST changes:

```text
SCORE — unchanged: per-criterion classify via reasoning + query$llm; aggregate
  verdict + confidence; build recommendations. (No structured `score` object to
  hand to a helper — you'll write these straight into the verdict file.)
PERSIST — fill the VERDICT TEMPLATE (frontmatter: verdict/confidence/criteria/
  recommendations · body: per-criterion table + recommendations + notes) and
  write-file it to verdicts/<yyyyMMdd-HHmmss>-<slug>.md. Append one INDEX line.
  Do NOT call eval$dossier-* or eval$verdict-write.
ANSWER — `Saved verdict: <path>` + `Verdict: <X> (confidence: Y)` +
  (`Recommended:` per criterion for non-ACHIEVED) + `Next:`.
HANDOFF rule (write into frontmatter + Next:): ACHIEVED → user (confirm
  complete); PARTIALLY_ACHIEVED → per-criterion recs, primary todo-agent;
  NOT_ACHIEVED → plan-agent re-spec (or exec-agent resume if spec is right).
```

Tool-context: drop the five persist helpers from `### Persistence helpers`; keep
the four upstream read seams + `eval$find` + `eval$read-verdict`; note the
verdict is now one `write-file` from the template.

## 9. `eval.clj` changes

- **Remove** `eval$dossier-slug`, `eval$dossier-frontmatter`, `eval$verdict-write`,
  `eval$dossier-write`, `eval$dossier-index-append`, `eval$next-handoff`, and the
  YAML emitters; drop them from `eval-dossier-helpers`.
- **Keep & rename** `eval$read-dossier` → `eval$read-verdict` (frontmatter-only
  read of the unified file) + its parse helpers; **keep** `eval$find`.
- **Keep** the cherry-picked upstream readers bound in the agent
  (`exec$read-dossier`, `plan$read-dossier`, `todo$read-dossier`,
  `edit$read-record`).
- **Simplify the auto-persist hook** to fill the §5 template and `spit` one file.
- In `eval_agent.clj` `:agent-tools`: drop the `remove` clause's exclusion of
  `:write-file` (eval needs to write its own verdict file); keep `:update-file`
  excluded if desired (eval appends/writes whole files, doesn't patch). Replace
  `ev/eval-dossier-helpers` with `[#'ev/eval$read-verdict #'ev/eval$find]`.
- Eval stays **read-only toward upstream** (no `doc$create`/`update`/`delete`,
  no `edit$apply`); it only writes under `.brainyard/agents/eval-agent/`.

## 10. Migration

- Land the slimmed `eval.clj` (drop persist helpers; keep readers + `eval$find`)
  and the instruction PERSIST swap.
- **Unify verdict+dossier** (§5): new turns write one file under `verdicts/`;
  add a dual-read so `eval$read-verdict` resolves both the unified file and a
  legacy `dossiers/<…>.md` for one release. `bb migrate:eval-agent` optional
  (eval outputs are advisory + regenerable, so a copy migration is low value —
  prefer dual-read + let old dossiers age out).
- Keys map 1:1 to the prior schema, so a verdict-triggered re-spec (plan/todo/
  exec reading the eval output) needs only the reader/path tweak.

## 11. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Hand-authored `criteria`/`recommendations` frontmatter malformed. | Flow-style one-liners + the lenient parser; a read-time validator flags a verdict missing `verdict`/`criteria` so consumers fail loud. |
| Losing the machine-readable per-criterion structure. | Preserved as frontmatter flow-maps; `eval$read-verdict` parses identically. |
| Unifying verdict+dossier hides the human report from a tool that wanted just prose. | Body is plain markdown after the frontmatter; a frontmatter-strip read returns the report. Keep the split only if a real consumer needs it (§5 note). |
| Model under-cites evidence now that no helper enforces shape. | The POST-FLIGHT rubric (R2 evidence-cited, R3 unsupported-flips, R5 recommendations) is unchanged and still gates PASS/HOLD; it operates on the written verdict, not a `score` object. |
| Scoring quality regresses. | It can't — SCORE is untouched; this redesign only changes how the result is written. |

## 12. Verification

- **Authoring** — a GO turn writes exactly one verdict file; frontmatter parses;
  `verdict`/`confidence`/`criteria`/`recommendations` populated; body has the
  per-criterion table.
- **Read-back** — `eval$read-verdict` returns all machine keys (verdict,
  confidence, criteria classes, recommendations).
- **Upstream reads unchanged** — eval still drills exec→edit-record via
  `edit$read-record` and pulls acceptance from `plan$read-dossier`.
- **CONTRADICTED flip** — a checkbox flipped without an evidence record scores
  CONTRADICTED and the body names it (R3) — behavior identical to today.
- **Double-score (C7)** — `eval$find` surfaces a prior verdict; `is_re_run:
  true` recorded.
- **GATHER/REFUSE** — empty acceptance → GATHER; empty evidence → REFUSE; one
  verdict file still written.
- **Auto-persist backstop** — skipped write → hook materializes a parseable
  verdict file.

## 13. Open questions

1. **Unify (§5) vs. keep the verdict/dossier split?** Unify is cleaner and
   matches explore-agent; the split only earns its keep if a consumer reads the
   human body and the machine frontmatter through different paths. Lean unify.
2. **Should `verdict`/`confidence` also surface as a stable answer line only, or
   always in frontmatter?** Frontmatter is the durable contract; the
   `Verdict: <X> (confidence: Y)` answer line stays for the dispatcher's grep.
3. **R7 reproducibility cross-check** stays instruction-level (re-derive via
   `query$llm`); promote to a recorded second pass? Cost vs. assurance — defer.
4. **Verdict-triggered auto-dispatch** (apply the recommendation) remains
   opt-in/off in v1; revisit once the pipeline is dossier-threaded end to end.

---

## Appendix — before/after, one ACHIEVED turn

**Before (~6 structured calls, two artifacts):**

```clojure
(def score {:verdict :achieved :confidence :high
            :criteria [{:criterion "…" :class :satisfied :confidence :high
                        :items [0] :evidence [{:type :edit-agent :record "…" :excerpt "…"}]}
                       …]
            :recommendations [] :degradation []})
(def vw (eval$verdict-write :slug … :content <body>))
(def fm (:frontmatter (eval$dossier-frontmatter :slug … :verdict-path (:path vw)
                        :pre pre :score score :post post
                        :handoff (eval$next-handoff :pre pre :score score :slug …))))
(def res (eval$dossier-write :slug … :content (str fm body)))
(eval$dossier-index-append :path (:path res) :slug … :pre-verdict :go
                           :score-verdict :achieved :confidence :high
                           :post-verdict :pass :next-agent "user")
```

**After (score in the model's head/`query$llm`, 1 write):**

```clojure
;; … per-criterion scoring via reasoning + query$llm (unchanged) …
(write-file {:path ".brainyard/agents/eval-agent/verdicts/20260629-…-ship-v2-checkout.md"
             :content "<filled VERDICT TEMPLATE: frontmatter verdict/criteria/recs + body report>"})
(write-file {:path ".brainyard/agents/eval-agent/INDEX.md" :append true
             :content "- 2026-06-29 … [ship-v2-checkout](verdicts/…md) — ACHIEVED · high · → user\n"})
```

The model never assembles a nested `score` object for a YAML renderer — it scores
in the medium it's best at (judgment + `query$llm`) and writes the medium it's
best at (a markdown report with a frontmatter header). Reading the four upstream
dossiers stays a typed, deterministic seam, because that is the half a machine
does better than the model.
