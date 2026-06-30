# Plan-Agent — Pre-flight & Post-flight Gated Plan Authoring with Dossier Handoff (CoAct-derived)

> **Status:** Shipped. `plan-agent` is registered in `components/agent`
> (`common/plan_agent.clj`). The lightweight, file-tool authoring redesign
> shipped (2026-06): the per-turn dossier is now authored as **direct markdown**
> from a fixed template, not constructed through a `plan$dossier-*` helper chain.
> This doc is the as-built reference — the former
> `plan-agent-lightweight-redesign.md` has been folded in here and removed.
>
> **As-built (verify against `common/plan_agent.clj`, `common/plan.clj`):**
> - **The dossier is authored as direct markdown, not a helper chain.** The model
>   fills the DOSSIER TEMPLATE (§7.2) and `write-file`s it; INDEX gets one
>   appended line via `write-file :append true`. The old write-side helpers
>   `plan$dossier-slug` / `plan$dossier-frontmatter` / `plan$dossier-write` /
>   `plan$dossier-index-append` / `plan$next-handoff` are **retired** as bound
>   tools (§12). Only one read seam survives: **`plan$read-dossier`** (the lone
>   entry in `plan/plan-dossier-helpers`).
> - **Plan bodies still flow through `doc$*` with `:kind :plan`.** Authoring is
>   `doc$create` / `doc$update` (not pure `write-file`) so the body keeps the
>   `guard/content-violation` secret-scan + size guard. The duplicate check is
>   `(doc$list {:kind :plan})`. The per-verb `plan$list/read/create/...` shims
>   were removed in favour of the polymorphic `doc$*` family.
> - **`write-file` / `read-file` / `update-file` are bound; `fetch-url` is NOT.**
>   The `:agent-tools` roster takes `file-tools` minus `:fetch-url` (web access
>   lives in explore-agent) — dossiers and INDEX are authored directly under
>   `.brainyard/agents/plan-agent/` (Hard Rule 5).
> - **POST-FLIGHT verdict is PASS / HOLD only.** The original REVISE auto-revise
>   round (§6) is **deferred to v1.5** — the shipped instruction says "1+ fail →
>   HOLD" with no automatic `doc$update` revision. `post` carries
>   `:verdict :pass|:hold` (no `:revision-applied?` / `:revision-summary`).
> - **Cross-agent dispatch is direct kebab-case** — `(explore-agent {…})`,
>   `(todo-agent {…})` — not `(call-tool "explore-agent" {…})`. Hard Rule 4 reads
>   "NO clone-self dispatch."
> - **A `:agent.ask/finalize` auto-persist hook** fills the *same* DOSSIER
>   TEMPLATE (`render-dossier-md`) and `spit`s one file when the LLM skips the
>   PERSIST checklist (common on smaller models). It writes the same one-file
>   path the happy path uses (no divergence) and injects the `Saved dossier:`
>   line if absent — and, uniquely, treats a *claimed-but-nonexistent* path as a
>   skip (`dossier-already-saved?` checks the file is on disk). A missing line
>   therefore does NOT mean nothing was saved.
> - The `.brainyard/plans/` → `.brainyard/agents/plan-agent/plans/` storage move
>   (§14) has **shipped**: writes go to the new path, reads fall back to the
>   legacy location for one release, and `list-plans` tags each entry
>   `:layout :new | :legacy`.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/plan_agent.clj` + `plan.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Sibling of:** `todo-agent`, `exec-agent`, `eval-agent`, `edit-agent`, `explore-agent`
> **Related reading:** `docs/CoAct.md`, `docs/AUTORESEARCH.md`,
> `docs/design/explore-agent-design.md`, `docs/design/edit-agent-design.md`,
> `docs/design/agent-lightweight-redesign-synthesis.md`

---

## 1. Motivation

The pre-redesign `plan-agent` was a thin wrapper over the `plan$*` command set.
Its instruction told the LLM to "be generous with detail" and "include Context /
Approach / Risks / References / Acceptance" — and then trusted the LLM to do
that. In practice three problems surfaced repeatedly:

1. **Plans are authored on insufficient information.** The agent gladly drafted a
   plan from a one-sentence question without checking whether `explore-agent` had
   already mapped the relevant code, whether prior plans cover the same ground,
   or whether the user supplied the constraints (deadlines, owners, target files)
   the executor will need. The result: plans whose `## Approach` is plausible but
   generic and whose `## References` is empty — `todo-agent` then derives weak
   items, `exec-agent` does extra discovery, and `eval-agent` cannot score
   because `## Acceptance` is fuzzy.
2. **Plans are shipped without a confirmation check.** Once written, the plan went
   straight to disk. There was no pass that asks: *is `## Approach` actually
   testable? Are the acceptance criteria observable? Is the scope small enough to
   be one todo, or is it secretly two?* By the time the user (or `eval-agent`,
   after the fact) caught these, the executor had spent a turn budget on the
   wrong shape.
3. **Downstream agents consume the raw plan body.** `todo-agent` / `exec-agent` /
   `eval-agent` all receive the plan via `:agent-context`, but the channel was
   unstructured — sometimes a slug, sometimes a path, sometimes a body, never a
   stable schema with the fields each downstream actually needs.

The same redesign folded in a layout migration. Plans used to live at
`.brainyard/plans/`; the rest of the agent ecosystem (`explore-agent`,
`edit-agent`) uses per-agent directories at `.brainyard/agents/<agent>/`.
Plan-agent was the odd one out. Plans now live at
`.brainyard/agents/plan-agent/plans/<slug>.md`, with room for sibling
subdirectories (`dossiers/`, `drafts/`, `INDEX.md`).

**Thesis.** Every authoring run runs through a fixed three-phase pipeline:

1. **PRE-FLIGHT (sufficiency check)** — does the agent have enough information to
   plan? Output: GO (proceed), GATHER (recommend / dispatch `explore-agent` or
   ask the user a single targeted question — do not draft a plan on
   assumptions), or REFUSE (ill-posed request).
2. **AUTHOR** — the `doc$create` / `doc$update` (`:kind :plan`) call, writing
   under the new layout.
3. **POST-FLIGHT (confirmation check)** — re-read the just-authored plan and
   self-critique with `query$llm` against a fixed rubric. Output: PASS (ready for
   `todo-agent`) or HOLD (surface specific gaps to the user — do not yet hand off
   to `todo-agent`).

Every run — GO/GATHER/REFUSE and PASS/HOLD alike — produces a **dossier**, a
small markdown file under `.brainyard/agents/plan-agent/dossiers/<ts>-<slug>.md`
that downstream agents consume in their `:agent-context`. The dossier is the
stable, schema'd handoff channel that replaces ad-hoc string passing.

### 1.1 Why authoring is direct markdown (the lightweight redesign)

The pre-redesign agent did its *checking* in prose but its *persisting* through a
chain of structured micro-tools. A single PASS turn asked the LLM to, in order:
build a `pre` map with exact keys (`:verdict :checks :exploration-path …`,
`:checks` itself a flat `:c1..:c7` map); build an `author` map; build a `post`
map (`:rubric` again a flat `:r1..:r7` map); call `plan$next-handoff`; feed all
four maps to `plan$dossier-frontmatter` which renders them into YAML with a
*fixed key order*; concatenate `(str fm body)` and call `plan$dossier-write`
(which **rejected** content not beginning with `---\n…\n---\n`); call
`plan$dossier-index-append` with the verdicts passed *again* as separate
arguments.

Every one of those calls had its own schema, and the maps shared key names that
had to agree across calls. When the model mis-keyed `:revision-applied?` vs
`revision_applied`, emitted `:checks` as a vector, forgot the leading
frontmatter, or swapped a `:pass` keyword for a string, the call errored and the
turn burned an iteration on a retry. The structure was *fighting* the model's
native fluency. The strongest evidence the path was too brittle is the
auto-persist hook (`materialize-auto-dossier!`) that exists precisely because
*capable models usually call the helpers, but smaller models skip them and just
ASSERT in the answer text that they wrote a dossier — sometimes with
hallucinated paths.* We built a safety net because the primary path leaked.

The fix: let the LLM do what it is inherently good at — **write the plan and the
dossier as markdown, directly.** Replace the construct-maps → render-YAML →
validated-write → re-pass-verdicts chain with `doc$create`/`doc$update` for the
plan body plus `write-file` calls against fixed templates. Keep deterministic
machinery only where a machine genuinely beats the model: *reading* a dossier's
frontmatter for downstream routing (`plan$read-dossier`) and *enumerating*
existing plans for the duplicate check (`doc$list :kind :plan`). In one line:
**authoring becomes prose; only reading stays typed.**

This is the same minimal-diff pattern as `explore-agent` / `edit-agent`: one
agent file, one slimmed helper namespace. The CoAct loop, sandbox, BT, and DSPy
signatures are untouched. (The cross-agent principle — separate *judgment*
(authoring prose) from *mechanism* (parsing/enumeration) — is the synthesis in
`docs/design/agent-lightweight-redesign-synthesis.md`.)

---

## 2. Design Principles

1. **No plan is authored on assumptions.** If pre-flight finds a gap, the agent
   SURFACES it and dispatches a discovery probe (`explore-agent`) or asks the
   user one targeted question. It does NOT proceed to draft `## Approach` from a
   hunch.
2. **Writes are LLM-inherent; reads stay deterministic.** The dossier *is* a
   markdown document, so the model `write-file`s it from a fixed template — no
   construct-and-render helper chain. The plan body goes through
   `doc$create`/`doc$update` (which carries the content guard). Reading and
   enumeration (`plan$read-dossier`, `doc$list`/`doc$read :kind :plan`) stay
   typed and deterministic, exactly where a machine beats the model.
3. **The contract is the template, not a schema object.** The dossier frontmatter
   keys (§7.2) are still the contract with downstream agents. It is enforced by
   *showing the exact template* in the instruction and by validating on read,
   rather than by forcing the model through a constructor.
4. **Plans are read-only after they ship.** The plan body itself is durable.
   Per-run sufficiency, holds, and hand-off recommendations live in the dossier —
   the plan stays a clean blueprint any teammate can read.
5. **Dossier is the contract with downstream.** `todo-agent` / `exec-agent` /
   `eval-agent` consume the dossier path in `:agent-context`, NOT the raw plan
   body. The dossier carries the plan path, the pre-flight verdict, the
   post-flight verdict, the extracted acceptance list (machine-readable), and the
   suggested next agent. Downstream agents `read-file` only the frontmatter
   (cheap) for routing.
6. **One dossier per turn, always.** Every plan-agent invocation produces exactly
   one dossier file, even when the run STOPS at pre-flight (GATHER/REFUSE)
   without authoring. The dossier records what was checked, why the run halted,
   and what the next call should supply.
7. **Self-critique is mandatory.** Post-flight runs `query$llm` against a fixed
   rubric (§6). In v1 a rubric failure lands in HOLD (the auto-revise round is
   deferred to v1.5). This caps the loop and keeps token cost predictable.
8. **Layout matches the rest of the ecosystem.** Plans live at
   `.brainyard/agents/plan-agent/plans/<slug>.md`; the agent's runtime artifacts
   (dossiers, drafts, index) sit beside them under
   `.brainyard/agents/plan-agent/`.
9. **Degrade gracefully.** A keyword typo can no longer fail a write — markdown
   has no schema to violate. The remaining failure mode (a malformed frontmatter
   line) is caught on read by a lenient parser, and a slimmed safety-net hook
   still backstops a fully-skipped dossier.
10. **No clone-self recursion.** Like `explore-agent` and `edit-agent`,
    plan-agent excludes `query$clone`. Cross-agent dispatch of a *different*
    registered agent — `(explore-agent {…})`, `(todo-agent {…})` — is direct,
    kebab-case, and allowed.
11. **Never invent slugs, references, or acceptance criteria.** Pre-flight
    refuses on missing inputs; post-flight flags fabricated specifics; the
    dossier is honest about gaps.

---

## 3. Position in the Agent Stack

```
coact-agent          (parent — full BT, sandbox, router, accumulator)
  ├─ explore-agent       (read-mostly multi-surface discovery)
  ├─ plan-agent          (THIS — authoring with pre/post-flight + dossier)
  ├─ todo-agent          (spawn / advance executable items)
  ├─ exec-agent          (drive todo to completion)
  ├─ eval-agent          (score execution vs plan acceptance)
  ├─ edit-agent          (safe single-file edits)
  └─ rlm-agent / mcp-agent / skill-agent
```

The four-agent pipeline `plan → todo → exec → eval` is the spine of any
non-trivial user request (`docs/AUTORESEARCH.md`). The dossier is what threads
them:

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

Each agent consumes the previous agent's dossier and emits its own — the same
pattern explore-agent established with `Saved exploration:`, generalized.

---

## 4. PRE-FLIGHT — Sufficiency Check

Runs before any `doc$create` / `doc$update` call. The agent walks a fixed
checklist and produces a `pre` map; the result is one of:

- **GO** — every check passed, proceed to AUTHOR.
- **GATHER** — at least one check failed in a way the agent can resolve by
  dispatching `explore-agent` or asking the user. STOP authoring; produce the
  dossier with `pre.verdict: gather` and the specific question / dispatch
  recommendation.
- **REFUSE** — the request is ill-posed (contradicts an existing plan, asks to
  plan something that needs `edit-agent` not a plan, references a non-existent
  codebase area). STOP and report.

### 4.1 The Checklist

| Check | What it verifies | How | Fail → action |
| ----- | ---------------- | --- | ------------- |
| C1 | The user's request decodes to a clear goal. | LLM self-question: "If I had to write `## Acceptance` right now, could I name 1–3 observable signals?" | GATHER — ask the user one targeted question (§4.2). |
| C2 | A near-duplicate plan does NOT already exist. | `(doc$list {:kind :plan :status :draft})` + `:in-progress`; fuzzy-match titles & summaries. | Exact match → REFUSE with pointer. Near-match → GATHER ("extend existing? confirm?"). |
| C3 | If the request implies a target codebase area, that area was actually explored. | Look in `:agent-context` for a `Saved exploration: <path>` line. | GATHER — recommend `(explore-agent {…})` first (unless the user pre-authorized auto-explore). |
| C4 | All references the agent will cite in `## References` actually exist on disk. | For each plausible file path: `bash "test -f <path>"`. For URLs, defer to authoring. | GATHER — list missing paths, ask the user to confirm or correct. |
| C5 | The request is plan-shaped, not edit-shaped or explore-shaped. | Heuristic: if the entire request collapses to "rename foo→bar" or "find X", it does not need a plan. | REFUSE with a redirect ("this is one `edit-agent` call" / "one `explore-agent` call"). |
| C6 | The request scope fits in one plan. | Heuristic on request length + count of distinct subgoals. Multi-quarter or cross-team → suggest a parent plan + child plans. | GATHER — propose a plan-of-plans split, ask the user to confirm. |
| C7 | An owner / dispatcher for the eventual execution is named or inferable. | Inspect `:agent-context` for "owner: …" or session config. | INFORMATIONAL — record `owner: unknown` in the dossier; do NOT block. v1 treats this purely as advisory. |

The checks are evaluated **in order** and the agent SHORT-CIRCUITS on the first
failure (records the rest as `:not-evaluated`). This keeps token cost low for the
common GATHER cases.

### 4.2 Targeted Question Rubric (when GATHER is forced by C1)

A bad clarifying question chains the user into a long Q&A. The agent picks ONE of
these forms, never more:

- **Goal disambiguation:** "Are you asking to <A> or <B>?" — multi-choice.
- **Acceptance disambiguation:** "What signal would tell you this is done? <1–3
  candidates>" — multi-choice with a free-form fallback.
- **Scope disambiguation:** "Should this plan cover <subset A> only, or also
  <subset B>?" — multi-choice.

The agent uses the framework's `get-user-feedback` channel when present;
otherwise it records the question in the dossier and STOPS, letting the
dispatcher relay it.

### 4.3 The `pre` Map

The model stashes a `pre` map via `def` so the dossier (§7) reflects it and
post-flight can refer back:

```clojure
(def pre
  {:verdict   :go                    ; or :gather | :refuse
   :checks    {:c1 :pass             ; :pass | :fail | :not-evaluated | :informational
               :c2 :pass :c3 :pass :c4 :pass
               :c5 :pass :c6 :pass :c7 :informational}
   :exploration-path ".brainyard/agents/explore-agent/results/2026...."  ; or nil
   :owner            "jake"          ; or :unknown
   :related-plans    [{:slug "..." :title "..." :status :draft}]
   :gather-question  nil             ; only when verdict = :gather
   :refuse-reason    nil})           ; only when verdict = :refuse
```

The map is a *thinking aid* and the source of the dossier's `pre:` block — it is
no longer fed to a `plan$dossier-frontmatter` constructor.

---

## 5. AUTHOR — Core Operation

This phase only runs when `pre.verdict = :go`. Plan bodies flow through the
polymorphic `doc$*` surface with `:kind :plan`, which keeps the
`guard/content-violation` secret-scan + size guard that a raw `write-file` would
bypass (Open Question §17.1, resolved in favour of `doc$*`):

- New slug → `(doc$create {:kind :plan :title <T> :body <B> :scope :project})`
  writes to `.brainyard/agents/plan-agent/plans/<slug>.md`.
- Existing slug → `(doc$read {:kind :plan :slug <s>})` first, merge the changes
  into the body, then `(doc$update {:kind :plan :slug <s> :body <merged>})`.
  ALWAYS read-and-merge — never blind-overwrite a rich plan with a one-line
  update.

The body shape is unchanged — the `## Context / ## Approach / ## Risks /
## References / ## Acceptance` rhythm carries forward verbatim — but the agent now
has the `pre` map to lean on:

- `## References` MUST include every path that survived check C4 + the
  `pre.exploration-path` if present.
- `## Approach` MUST be verb-led (`Wire`, `Update`, `Add`, `Validate`), 3–15
  bullets, so `todo-agent` can derive items mechanically.
- `## Acceptance` MUST contain at least the candidates from C1's disambiguation
  if that ran, and every criterion MUST be observable (verifiable via a command,
  a file, or a metric).

The `doc$create` / `doc$update` call returns the path, which the agent stashes:

```clojure
(def authored {:slug "..." :path ".brainyard/agents/plan-agent/plans/<slug>.md"
               :action :created})   ; :created | :updated | :unchanged
```

---

## 6. POST-FLIGHT — Confirmation Check

Runs immediately after AUTHOR. The agent re-reads the just-written plan (via
`doc$read :kind :plan`) and grades it against a fixed rubric using `query$llm`.
The rubric is in the instruction (§8) and produces a `post` map.

### 6.1 The Rubric

| Item | Rubric question | Pass criterion |
| ---- | --------------- | -------------- |
| R1 | Does `## Approach` decompose into 3–15 actionable verbs `todo-agent` could mechanically convert into items? | At least 3 actionable bullets; no single bullet dominates. |
| R2 | Does `## Acceptance` list 1–5 observable signals — each verifiable by running a command, reading a file, or checking a metric? | Every criterion is concrete (no "users find it intuitive" without an instrument). |
| R3 | Does `## References` cite every path / URL / dossier the executor needs, and does every cited path resolve on disk? | Cross-check `bash "test -f <path>"` for each repo-relative reference. |
| R4 | Does `## Risks / Open Questions` name at least one specific risk and one named open question (or explicitly "no open questions")? | Generic risks like "could break things" are not specific. |
| R5 | Is the plan scope sized for one todo (3–30 items)? Bigger → recommend split; smaller → recommend folding in. | Estimate from the actionable bullet count. |
| R6 | Are there contradictions between sections? (a file in `## Approach` absent from `## References`; an acceptance metric `## Approach` never moves) | LLM cross-check via `query$llm`. |
| R7 | Are there dangling LLM artifacts? (`TODO`, `???`, `<...>`, `[fill in]`, `tk`, fabricated function names.) | Grep the body for telltales. |

Use `query$llm` for R1, R2, R6 (qualitative). R3 / R7 are mechanical (bash +
grep). R4 / R5 are short LLM checks.

### 6.2 Verdict (v1 — REVISE auto-round deferred to v1.5)

- **PASS** — every R-item passes. Plan is ready for `todo-agent`.
- **HOLD** — one or more items fail. The plan body stays where it is; the dossier
  records the specific holds; the answer recommends the user manually amend the
  plan and re-run plan-agent (or proceed despite the hold). plan-agent does NOT
  dispatch `todo-agent` on a HOLD.

> **v1.5 (deferred):** the original design's **REVISE** verdict — apply ONE
> automatic `doc$update` round with the LLM's amended body and re-run R1–R7 once
> — is not shipped. The pure helper `handoff-from-verdicts` in `plan.clj` still
> carries a `:revise` branch for forward-compatibility, but the shipped
> instruction only emits `:pass | :hold`, and the INDEX writer coerces any
> stray `:revise` to `:n-a`.

### 6.3 The `post` Map

```clojure
(def post
  {:verdict      :pass               ; or :hold
   :rubric       {:r1 :pass :r2 :pass :r3 :fail :r4 :pass
                  :r5 :pass :r6 :pass :r7 :pass}
   :holds        [{:item :r3 :description "missing reference: X"}]  ; [] on PASS
   :acceptance   ["all unit tests green" "/ready endpoint returns 200"]})
```

`post.acceptance` is critical — it is the structured handoff to `eval-agent` that
lets it score without reparsing markdown. (No `:revision-applied?` /
`:revision-summary` keys ship in v1.)

---

## 7. Output Discipline — `.brainyard/agents/plan-agent/`

Same shape as `.brainyard/agents/explore-agent/`
(`docs/design/explore-agent-design.md` §5), tuned for plans.

### 7.1 Directory Layout

```
.brainyard/
├── agents/
│   └── plan-agent/
│       ├── plans/                     ; the durable plan corpus (replaces .brainyard/plans/)
│       │   ├── ship-v2-checkout.md
│       │   ├── retire-search-agent.md
│       │   └── ...
│       ├── dossiers/                  ; per-turn handoff records, newest stays
│       │   ├── 20260510-104503-ship-v2-checkout.md
│       │   ├── 20260510-191812-ship-v2-checkout.md   ; second turn on same slug
│       │   └── 20260510-194412-retire-search-agent.md
│       ├── drafts/                    ; per-turn scratch (overwritable)
│       ├── INDEX.md                   ; one line per dossier (append-only)
│       └── README.md                  ; layout cheat-sheet
```

`plans/` replaces the legacy `.brainyard/plans/`. The body schema is unchanged —
only the path moved. `dossiers/`, `drafts/`, and `INDEX.md` are new. The
timestamp prefix on dossier filenames means same-slug turns never collide (the
guarantee the old `-N` collision suffix gave).

### 7.2 Dossier Template (the thing the model fills in — and the contract)

The instruction carries this template verbatim. The model copies it, fills the
`<…>` slots, and `write-file`s it to
`.brainyard/agents/plan-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md`. There is
**no** `plan$dossier-frontmatter` / `plan$dossier-write` construction step. The
keys are exactly what `plan$read-dossier`'s lenient parser reads back, so
downstream agents need no changes.

```markdown
---
slug: <kebab-slug>
agent: plan-agent
created: <ISO-8601, e.g. 2026-06-29T14:03:11Z>
plan_path: <.brainyard/agents/plan-agent/plans/<slug>.md, or null on GATHER/REFUSE>
plan_status: <draft | in-progress | completed | abandoned>

pre:
  verdict: <go | gather | refuse>
  checks: {c1: pass, c2: pass, c3: pass, c4: pass, c5: pass, c6: pass, c7: informational}
  exploration_path: <path or null>
  owner: <name or unknown>
  related_plans: []
  gather_question: <one question, or null>
  refuse_reason: <one line, or null>

author:
  action: <created | updated | unchanged>
  body_bytes: <int or 0>

post:                       # OMIT this whole block when no AUTHOR ran (gather/refuse)
  verdict: <pass | hold>
  rubric: {r1: pass, r2: pass, r3: pass, r4: pass, r5: pass, r6: pass, r7: pass}
  holds: []
  acceptance: ["<observable criterion 1>", "<observable criterion 2>"]

handoff:
  next_agent: <todo-agent | user | none>
  next_call: "<exact (todo-agent {…}) form, or a one-line instruction>"
---

# Plan dossier — <title>

## Pre-flight summary
<what was checked; which checks passed; exploration consumed; owner>

## Plan summary (extracted)
<Approach in one or two lines; Acceptance pointer; named risks/open questions>

## Post-flight notes
<rubric outcome; holds, if any>

## Handoff
<one line: pass <dossier path> to <next agent> in :agent-context>
```

Two things make this safe to hand-author:

- **Flow-style `checks` / `rubric` maps** (`{c1: pass, …}`) and **flow-style
  `holds` / `acceptance` vectors** are one line each — far easier for a model to
  emit correctly than a nested block, and `parse-dossier-yaml` reads them back
  directly. (The parser also tolerates a block-list form for `holds`/`acceptance`
  for capable models that emit it, but the template shows flow style.) **Keep
  these as one-line flow exactly as shown** — that is what the downstream reader
  parses.
- **The frontmatter keys are fixed and shown**, so the model isn't *inventing*
  structure — it's filling blanks. That is the regime LLMs are reliable in. A
  keyword typo can't fail a write because markdown has no schema; a malformed
  frontmatter line is caught on read.

Frontmatter contract (downstream parsers may rely on these):

| Key | Type | Description |
| --- | ---- | ----------- |
| `slug` | string | Plan slug. Multiple dossiers can share a slug across turns. |
| `agent` | string | Always `plan-agent`. |
| `created` | ISO-8601 string | UTC timestamp. |
| `plan_path` | string | Repo-relative path to the plan body (or `null` on GATHER/REFUSE). |
| `plan_status` | enum | `draft` \| `in-progress` \| `completed` \| `abandoned`. |
| `pre.*` | map | The `pre` block (§4.3). Includes `verdict` and per-check pass/fail. |
| `author.action` | enum | `created` \| `updated` \| `unchanged` (the last when no plan body was touched). |
| `post.*` | map | The `post` block (§6.3). `acceptance` is the machine-readable criterion list. Whole block omitted on GATHER/REFUSE. |
| `handoff.next_agent` | string | `todo-agent` on PASS; `user` on HOLD/GATHER; `none` on REFUSE. |
| `handoff.next_call` | string | The exact dispatch form a dispatcher can paste verbatim. |

Body sections under the frontmatter are freeform but follow the `## Pre-flight
summary / ## Plan summary / ## Post-flight notes / ## Handoff` rhythm.

### 7.3 Two Levels of Cheap Read

- **Cheap (~700 bytes):** read just the frontmatter via `(plan$read-dossier
  {:path …})`. Sufficient for routing and for `eval-agent` to extract
  `acceptance` without fetching the plan body. The reader also returns a
  `:warning` when a contract key (`slug` / `plan_path`) is missing, so a
  malformed hand-authored dossier surfaces loud rather than returning a silently
  missing field.
- **Full (~3–8 KB typically):** `read-file` the whole dossier when a downstream
  agent wants the prose summary.

### 7.4 INDEX.md

`INDEX.md` is a newest-first convenience index, not part of the downstream
contract — so it ships **append-only** (the cheapest, schema-free option):
`write-file :append true`, one line per dossier. "Newest first" becomes "newest
last"; re-sorting is a read-time concern. The line format is unchanged:

```markdown
- 2026-05-10 10:45 [ship-v2-checkout](dossiers/20260510-104503-ship-v2-checkout.md) — pre:go · post:pass · → todo-agent
- 2026-05-10 19:44 [retire-search-agent](dossiers/20260510-194412-retire-search-agent.md) — pre:gather · post:n/a · → user
```

(The auto-persist hook writes the same line via `append-index-line!`, coercing
the verdict tokens to the grep-clean set `pre:{go|gather|refuse}` /
`post:{pass|hold|n/a}`.)

### 7.5 ANSWER Format

Stable lines at the end of the agent's `answer`:

```
Saved plan: <plan path>          (omitted when author.action = unchanged / no AUTHOR ran)
Saved dossier: <dossier path>
Next: <handoff.next_call>
```

The `Saved plan:` and `Saved dossier:` prefixes are the contract; downstream
agents grep for them. `Next:` is the handoff suggestion in copy-paste form.

For pre-flight GATHER:

```
Saved dossier: <dossier path>
Need: <one-line description of the missing input>
Suggested: (explore-agent {…})  OR  a one-line user question
```

For pre-flight REFUSE:

```
Saved dossier: <dossier path>
Refused: <one-line reason>
Suggested: <redirect — e.g., "use edit-agent for this single-file edit">
```

For POST-FLIGHT HOLD:

```
Saved plan: <plan path>
Saved dossier: <dossier path>
Hold: <one-line-per-hold>
Suggested: <user action OR re-call plan-agent after amending>
```

---

## 8. Instruction (System Prompt Body)

Layered on top of `coact-agent`'s instruction by `run-coact-derived`. The
three-phase walk and the hard rules are unchanged from the original design; the
PERSIST section tells the model to write markdown directly, and the POST-FLIGHT
verdict is PASS | HOLD (no auto-revise in v1).

```text
You are a PLAN-agent. You author a plan blueprint AFTER you have confirmed
you have enough information, and you confirm the result is sound BEFORE
handing off to todo-agent. You ALWAYS produce a dossier — even when you
refuse or stop early. You NEVER author plans on assumptions.

Plans live at `.brainyard/agents/plan-agent/plans/<slug>.md`. (Legacy location
`.brainyard/plans/` is read-fallback for one release; `bb migrate:plan-agent`
copies legacy plans across.)
Dossiers live at `.brainyard/agents/plan-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md`.

THE THREE PHASES (run them in order, every turn)
PRE-FLIGHT  — sufficiency check.   Output: GO | GATHER | REFUSE.
AUTHOR      — only on GO.          doc$create or doc$update :kind :plan.
POST-FLIGHT — only when AUTHOR ran. Self-critique against a 7-item rubric.
              Output: PASS | HOLD.
PERSIST     — always. Write the dossier under .brainyard/agents/plan-agent/dossiers/.
ANSWER      — emit `Saved plan:` (when authored), `Saved dossier:` (always), `Next:`.

PRE-FLIGHT — sufficiency checklist (walk in order; short-circuit on first fail)
  C1 GOAL CLEAR        — could you write ## Acceptance now? else GATHER (one Q).
  C2 NO DUPLICATE      — (doc$list {:kind :plan ...}); exact → REFUSE, near → GATHER.
  C3 EXPLORED          — Saved exploration: in :agent-context? else recommend
                         (explore-agent {…}) → GATHER.
  C4 REFS EXIST        — bash "test -f <path>" for each path; missing → GATHER.
  C5 PLAN SHAPED       — single edit → REFUSE→edit-agent; single lookup →
                         REFUSE→explore-agent.
  C6 SCOPE FITS        — multi-quarter/cross-team → GATHER (plan-of-plans).
  C7 OWNER KNOWN       — unknown → INFORMATIONAL (record; never blocks).
  Stash (def pre {:verdict … :checks {…} :exploration-path … :owner …
                  :related-plans [...] :gather-question … :refuse-reason …})

GATHER PROTOCOL — one targeted question (GOAL | ACCEPTANCE | SCOPE), multi-choice
  when bound via get-user-feedback; else record in the dossier and STOP. NEVER chain.

AUTHOR — only on PRE-FLIGHT = GO
  New slug → (doc$create {:kind :plan :title <T> :body <B> :scope :project}).
  Existing → doc$read first, merge, doc$update :kind :plan :body <merged>.
  Body: ## Context / ## Approach / ## Risks / ## References / ## Acceptance.
    every approach reference also in references; every acceptance criterion
    observable; references include pre.exploration-path if non-nil; approach
    verb-led, 3–15 bullets.
  Stash (def authored {:slug … :path … :action :created|:updated|:unchanged}).

POST-FLIGHT — 7-item rubric (only when AUTHOR ran)
  Re-read via doc$read. R1 approach actionable · R2 acceptance observable ·
  R3 references resolve (bash test -f) · R4 risks specific · R5 scope ∈ [3,30] ·
  R6 no contradictions · R7 no dangling artifacts. query$llm for R1/R2/R6.
  VERDICT (v1 — REVISE auto-round deferred to v1.5):
    All pass → PASS.  1+ fail → HOLD (record holds; do NOT dispatch todo-agent).
  Stash (def post {:verdict :pass|:hold :rubric {…} :holds [...] :acceptance [...]}).

PERSIST — dossier (always), ONE write-file
  Fill the DOSSIER TEMPLATE with this turn's verdicts; write-file it to
  .brainyard/agents/plan-agent/dossiers/<ts>-<slug>.md. Then append ONE INDEX line
  (write-file :append true). Do NOT construct Clojure maps or call dossier helpers —
  there are none to call. WRITE THE MARKDOWN: copy the template, fill the <…> slots.
  Keep checks/rubric one-line flow maps and holds/acceptance one-line flow vectors
  EXACTLY as shown — that is what plan$read-dossier parses back.

HANDOFF — fill handoff: and the Next: line from this table:
  pre=go,  post=pass  → todo-agent ; (todo-agent {:question "Spawn a todo for this plan." :agent-context "<dossier path>"})
  pre=go,  post=hold  → user       ; resolve the holds, then re-run plan-agent
  pre=gather          → user       ; supply the gather_question input, then re-run plan-agent
  pre=refuse          → none       ; see refuse_reason redirect (edit-agent / explore-agent)

ANSWER — stable prefixes per §7.5 (Saved plan / Saved dossier / Next, or the
  GATHER / REFUSE / HOLD variants).

HARD RULES
1. NO authoring on assumptions. PRE-FLIGHT failure → GATHER or REFUSE.
2. NO chaining clarifying questions. ONE question per turn, multi-choice.
3. NO mutating todos / exec records. Plans only; doc$update :kind :plan is the
   only update path.
4. NO clone-self dispatch. Direct kebab-case dispatch to other registered agents
   is fine ((explore-agent {…}), (todo-agent {…}), etc.).
5. NO writing outside .brainyard/agents/plan-agent/. Plan BODIES go through
   doc$create / doc$update (:kind :plan) — that path carries the secret-scan +
   size guard. The dossier + INDEX go through write-file, scoped to the agent dir.
6. NO inventing slugs or references. Both are checked against disk first.
7. NEVER skip the dossier. Even REFUSE turns produce one — it's the audit trail.
```

The instruction also carries a note for dossier bodies containing ``` code
fences: author the whole dossier as a four-backtick verbatim `markdown` block so
inner fences pass through untouched, rather than hand-escaping the `:content`
string.

---

## 9. Tool-Context (How to Use the Bound Tools)

```text
## Plan Tools — author plan blueprints under .brainyard/agents/plan-agent/plans/

PLAN MANAGEMENT (doc$* — polymorphic with :kind :plan)
- doc$list   :kind :plan [:scope project|user] [:status draft|in-progress|completed|abandoned]
             — list plans (also reads legacy .brainyard/plans/ during migration;
               entries tagged :layout :new | :legacy).
- doc$read   :kind :plan :slug <s>   — frontmatter + body + :file-path; or
                                       {:not-found true …} when absent.
- doc$create :kind :plan :title <T> :body <B> [:scope :project]
             — new plan at the new path. Body is free-form markdown
               (## Context / ## Approach / ## Risks / ## References / ## Acceptance).
- doc$update :kind :plan :slug <s> :body <B>   — replace body (read-merge first).
             Also :status :draft|:in-progress|:completed|:abandoned|:reopen.
- doc$delete :kind :plan :slug <s>   — destructive; confirm.

PRE-FLIGHT HELPERS (no dedicated tool — built on existing tools)
- bash "test -f <path>"      — C4 reference resolves.
- (doc$list {:kind :plan})   — C2 duplicate check.

CROSS-AGENT DISPATCH (sparingly; never recursive on plan-agent)
- (explore-agent {:question "<probe>" :agent-context "…"})   — when C3 fails;
    recommend in the answer, do NOT auto-dispatch.
- (todo-agent {:question "Spawn a todo for this plan." :agent-context "<dossier path>"})
    — RECOMMENDED via the `Next:` answer line. Do NOT auto-dispatch.

SUB-LLM (rubric scoring)
- query$llm   — heavy use in POST-FLIGHT R1 / R2 / R6 (qualitative). FLAT only.

PERSISTENCE — write markdown directly (NO dossier-construction tools)
- write-file / read-file / update-file are bound. .brainyard/ and /tmp are
  auto-allowed (no permission prompt); write-file creates parent dirs.
  (fetch-url is NOT bound — web access lives in explore-agent.)
- Author the dossier as a markdown file from the DOSSIER TEMPLATE in the
  instruction, then append one INDEX line with write-file :append true. There
  are no plan$dossier-* / slug / frontmatter / next-handoff helpers to call —
  the handoff block comes from the 4-case table in the instruction.
- plan$read-dossier   — READ-ONLY. Parse a dossier's frontmatter (used by you to
    inspect a prior dossier, and by todo/exec/eval downstream). Cheap (~700 bytes);
    returns plan_path / post.acceptance / handoff, plus a :warning when a contract
    key (slug/plan_path) is missing.

## Bookkeeping
- list-tools, get-tool-info — generic registry access (invoke registered tools by id).
- task$run (:job-type :tool|:bash)   — async for >5s operations.
- agent-runtime$config               — view / tune settings.

## Typical end-to-end flow
1. Parse :question and :agent-context (typically a `Saved exploration:` path).
2. PRE-FLIGHT — walk C1–C7. Stash `pre`. Short-circuit on first fail.
3. If PRE-FLIGHT ≠ :go → skip AUTHOR + POST-FLIGHT, jump to PERSIST + ANSWER.
4. AUTHOR — doc$create or doc$update :kind :plan. Stash `authored`.
5. POST-FLIGHT — re-read, run R1–R7 with query$llm + bash + grep. v1 has no
   auto-revise; failures land in HOLD with explicit gaps.
6. PERSIST — write-file the DOSSIER TEMPLATE to dossiers/; append one INDEX line.
7. ANSWER — `Saved plan:` + `Saved dossier:` + `Next:` (or GATHER/REFUSE/HOLD).
```

---

## 10. Behavior Tree — Inherited As-Is

`plan-agent` does **not** define its own BT. `run-coact-derived` falls back to
`coact-agent`'s `:bt-factory`. (`plan-agent` pins `:bt-factory` explicitly so
direct-resolution entry points — e.g. `setup-agent-by-id` used by `bb tui ask` —
pick up the correct CoAct BT; mirrors the explore-agent / edit-agent pattern.)
The three-phase pipeline (§4–§6) is a *prompt* contract, not a BT-level contract
— same rationale as `edit-agent` (`docs/design/edit-agent-design.md` §9).

Iteration shape for a typical authoring run:

| Iter | Channel | Body |
| ---- | ------- | ---- |
| 1 | code | PRE-FLIGHT C1–C7. `def pre`. |
| 2 | tool / code | (only when GO) AUTHOR via `doc$create` / `doc$update`. `def authored`. |
| 3 | code | (only when AUTHOR ran) POST-FLIGHT rubric R1–R7 with `query$llm` + bash. `def post`. |
| 4 | code | PERSIST: `write-file` the DOSSIER TEMPLATE; append INDEX line. |
| 5 | answer | `Saved plan:` + `Saved dossier:` + `Next:` (or variants). |

GATHER turns collapse to iterations 1 → 4 → 5. REFUSE turns are even shorter. A
skipped dossier is backstopped by the auto-persist hook (§12.1).

---

## 11. Demonstration: "Plan how we'll ship checkout v2"

Mixed-input request: the user has already run `explore-agent` and pastes the
resulting `Saved exploration:` path into `:agent-context`.

### Iteration 1 — PRE-FLIGHT (code channel)

````markdown
```clojure
(def question "Plan how we'll ship checkout v2 behind a feature flag.")
(def agent-context
  "Saved exploration: .brainyard/agents/explore-agent/results/20260509-181244-checkout-codepaths.md")

;; C1 — goal clear?
(def acceptance-candidates
  (:result (query$llm
             :prompt (str "If you had to write `## Acceptance` for: " question
                          " — name 1–3 observable signals."))))

;; C2 — no duplicate?
(def existing (doc$list {:kind :plan :status :draft :scope :project}))

;; C3 — exploration consumed?
(def explore-path
  (when-let [m (re-find #"Saved exploration: (\S+)" agent-context)] (last m)))

;; C4 — referenced files exist?
(def refs ["src/checkout/flags.clj" "src/checkout/payment_validator.clj" "src/checkout/cart.clj"])
(def refs-exist?
  (every? #(zero? (:exit-code (bash {:command (str "test -f " %)}))) refs))

;; C5 plan-shaped (yes, multi-step) · C6 scope fits (yes) · C7 owner from context.
(def pre {:verdict :go
          :checks {:c1 :pass :c2 :pass :c3 :pass :c4 (if refs-exist? :pass :fail)
                   :c5 :pass :c6 :pass :c7 :informational}
          :exploration-path explore-path
          :owner "jake"
          :related-plans (mapv #(select-keys % [:slug :title :status]) existing)
          :gather-question nil :refuse-reason nil})
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
                  "- p99 checkout latency unchanged within ±5%.\n")
        res (doc$create {:kind :plan :title "Ship v2 checkout" :body body :scope :project})]
    {:slug (:slug res) :path (:file-path res) :action :created}))
```

### Iteration 3 — POST-FLIGHT (code channel)

```clojure
(def plan-body (:body (doc$read {:kind :plan :slug (:slug authored)})))

;; R1 / R2 / R6 — query$llm
(def llm-rubric
  (:result (query$llm
             :prompt (str "Score this plan body against R1 (approach actionable, "
                          "3-15 verb-led bullets), R2 (acceptance observable), "
                          "R6 (no contradictions). Return EDN "
                          "{:r1 :pass|:fail :r2 … :r6 … :notes \"…\"}.\n\n" plan-body))))

;; R3 references resolve · R4 risks specific · R5 scope · R7 no artifacts (bash + grep)
(def r3 (let [paths (re-seq #"file:(\S+)" plan-body)]
          (if (every? #(zero? (:exit-code (bash {:command (str "test -f " (second %))}))) paths)
            :pass :fail)))
(def r5 (let [n (count (re-seq #"\n- " (re-find #"(?s)## Approach.*?(?=\n## )" plan-body)))]
          (if (<= 3 n 30) :pass :fail)))
(def r7 (if (re-find #"\b(TODO|tk|\?\?\?|\[fill in\])\b" plan-body) :fail :pass))

(def post
  {:verdict (if (every? #(= :pass %) [(:r1 llm-rubric) (:r2 llm-rubric) r3 (:r6 llm-rubric) r7])
              :pass :hold)
   :rubric {:r1 (:r1 llm-rubric) :r2 (:r2 llm-rubric) :r3 r3 :r4 :pass :r5 r5
            :r6 (:r6 llm-rubric) :r7 r7}
   :holds []
   :acceptance ["feature-flag checkout-v2 toggleable from staging admin"
                "all checkout/* unit tests green"
                "p99 checkout latency unchanged within ±5%"]})
```

### Iteration 4 — PERSIST: write the dossier directly (code channel)

The model fills the DOSSIER TEMPLATE and `write-file`s it — no helper chain:

````markdown
```clojure
(def ts (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss") (java.util.Date.)))
(def slug (:slug authored))
(def dossier-path (str ".brainyard/agents/plan-agent/dossiers/" ts "-" slug ".md"))

(write-file
  {:path dossier-path
   :content (str
     "---\n"
     "slug: " slug "\n"
     "agent: plan-agent\n"
     "created: " (java.time.Instant/now) "\n"
     "plan_path: " (:path authored) "\n"
     "plan_status: draft\n\n"
     "pre:\n"
     "  verdict: go\n"
     "  checks: {c1: pass, c2: pass, c3: pass, c4: pass, c5: pass, c6: pass, c7: informational}\n"
     "  exploration_path: " (:exploration-path pre) "\n"
     "  owner: jake\n  related_plans: []\n  gather_question: null\n  refuse_reason: null\n\n"
     "author:\n  action: created\n  body_bytes: " (count (:body authored)) "\n\n"
     "post:\n"
     "  verdict: pass\n"
     "  rubric: {r1: pass, r2: pass, r3: pass, r4: pass, r5: pass, r6: pass, r7: pass}\n"
     "  holds: []\n"
     "  acceptance: [\"feature-flag checkout-v2 toggleable from staging admin\", "
                    "\"all checkout/* unit tests green\", \"p99 latency within ±5%\"]\n\n"
     "handoff:\n"
     "  next_agent: todo-agent\n"
     "  next_call: \"(todo-agent {:question \\\"Spawn a todo for this plan.\\\" :agent-context \\\"" dossier-path "\\\"})\"\n"
     "---\n\n"
     "# Plan dossier — Ship v2 checkout\n\n"
     "## Pre-flight summary\nAll C1–C7 passed; consumed the checkout-codepaths exploration; owner jake.\n\n"
     "## Plan summary (extracted)\nWire the flag, update the validator, migrate the serializer, add tests, validate latency.\n\n"
     "## Post-flight notes\nAll rubric items passed on the first pass.\n\n"
     "## Handoff\nPass `" dossier-path "` to todo-agent in :agent-context.\n")})

;; INDEX append (newest-last, append-only)
(write-file
  {:path ".brainyard/agents/plan-agent/INDEX.md"
   :append true
   :content (str "- " (subs (str (java.time.Instant/now)) 0 16)
                 " [" slug "](dossiers/" ts "-" slug ".md) — pre:go · post:pass · → todo-agent\n")})
```
````

### Iteration 5 — ANSWER

```markdown
## Plan authored — Ship v2 checkout

PRE-FLIGHT: GO (all 7 checks passed; exploration consumed)
AUTHOR: created (.brainyard/agents/plan-agent/plans/ship-v2-checkout.md)
POST-FLIGHT: PASS (rubric R1–R7 all passed)

Saved plan: .brainyard/agents/plan-agent/plans/ship-v2-checkout.md
Saved dossier: .brainyard/agents/plan-agent/dossiers/20260510-104503-ship-v2-checkout.md
Next: (todo-agent {:question "Spawn a todo for this plan." :agent-context ".brainyard/agents/plan-agent/dossiers/20260510-104503-ship-v2-checkout.md"})
```

A downstream `todo-agent` invocation reads the dossier via `plan$read-dossier`
(frontmatter only — cheap), pulls `post.acceptance` directly into its own
`## Acceptance` reference block, and reads the plan body via the `plan_path` field
only when authoring item descriptions.

---

## 12. `plan.clj` — Read Seam Only (Write-Side Chain Retired)

The write-side helper chain is **retired** as bound tools. `plan/plan-dossier-helpers`
ships exactly one read seam, auto-bound in the SCI sandbox:

| Helper | Signature | What it does |
| ------ | --------- | ------------ |
| `plan$read-dossier` | `(plan$read-dossier {:path …}) → parsed map` | Reads only the leading `---/---` block (cheap, ~700 bytes). Returns `plan_path` / `post.acceptance` / `handoff` and a `:warning` when a contract key (`slug` / `plan_path`) is missing. The cross-agent read contract for todo/exec/eval. |

**Retired as bound tools** (the LLM no longer calls them): `plan$dossier-slug`,
`plan$dossier-frontmatter`, `plan$dossier-write`, `plan$dossier-index-append`,
`plan$next-handoff`. The plan-body CRUD lives in the polymorphic `doc$*` family
(`:kind :plan`); the per-verb `plan$list/read/create/...` shims are gone.

Internally, `plan.clj` retains the *pure functions* that back the read seam and
the safety net — they are not LLM-bound tools:

- `render-dossier-md` fills the §7.2 template string (the auto-persist hook's
  writer; byte-compatible with what the LLM hand-authors).
- `parse-dossier-yaml` / `dossier-read-frontmatter-lines` — the lenient reader
  behind `plan$read-dossier` (flat scalars, flow maps as raw strings, flow
  vectors, and a tolerated block-list form for `holds`/`acceptance`).
- `handoff-from-verdicts` — the 4-case handoff logic (still carries a `:revise`
  branch for v1.5 forward-compat, unused by the v1 instruction).
- `append-index-line!` + `coerce-verdict` — the INDEX writer with grep-clean
  verdict coercion (`:revise` → `:n-a`).

### 12.1 Auto-Persist Backstop

`plan.clj` installs an `:agent.ask/finalize` hook (`plan-auto-persist`,
self-installed at namespace load via `install-auto-persist!`) that materializes a
dossier when the LLM skips the PERSIST checklist (common on smaller models). It is
**not** the primary path — it is a safety net — and it does the *same* thing the
happy path does, so the two cannot diverge:

- `materialize-auto-dossier!` reconstructs a minimal dossier from the answer text
  (`detect-pre-verdict` / `detect-post-verdict` / `one-line-summary` /
  `slug-from-plan-path`), fills `render-dossier-md`, and `spit`s **one**
  timestamp-prefixed file, then appends one INDEX line.
- Because finalize is gated, the hook injects the absent `Saved dossier: <path>`
  line back into the answer the caller receives.
- It is idempotent and, unlike explore-agent's hook, checks the **claimed path
  actually exists on disk** (`dossier-already-saved?`) — a hallucinated path with
  no file is treated as "not saved" and replaced. Capable models have been seen
  to emit fake timestamps and paths verbatim, so an answer-text check alone isn't
  enough here.
- It is scoped to plan-agent instances via `:match` and tagged
  `:source :plan-agent` (apps can opt out with
  `(hooks/unregister-source! :plan-agent)`).

A missing `Saved dossier:` line therefore does NOT mean nothing was saved.

---

## 13. Handoff Mechanics — How Other Agents Consume Dossiers

The contract between plan-agent and downstream agents is **just the dossier
path**.

### 13.1 The `Saved dossier:` and `Next:` Lines

Every answer ends with the lines in §7.5. The dispatcher (or user) greps
`^Saved dossier: ` to extract the path; `Next:` provides the exact dispatch form
to copy-paste. (On a HOLD/GATHER/REFUSE turn there is no `Next: (todo-agent …)`;
the variant lines surface the hold / need / refusal instead.)

### 13.2 Two Levels of Cheap Read

**Cheap (~700 bytes):** read just the frontmatter via `plan$read-dossier`:

```clojure
(def md (plan$read-dossier {:path "<path>"}))
;; → {:slug … :plan_path … :pre {…} :post {…} :handoff {…} :acceptance [...] …}
```

**Full (~3–8 KB typically):** `read-file` the whole dossier when the prose
summary is needed.

### 13.3 Per-downstream-agent usage

| Agent | What it reads from the dossier |
| ----- | ------------------------------ |
| todo-agent | `plan_path` (to read the body for `## Approach`); `post.acceptance` (to seed its own acceptance reference); `pre.exploration_path` (carried forward); `slug` (default todo slug). |
| exec-agent | `plan_path` (to read the plan during execution); `post.acceptance` (to know what "done" means item-by-item). |
| eval-agent | `plan_path` + `post.acceptance` (the criterion list it scores against — read directly from the dossier, no plan-body parse). |
| user | The `## Pre-flight summary` / `## Post-flight notes` body sections — human-readable status of the plan at this turn. |

### 13.4 Cross-Reference From Trajectory

`coact-store-results-action` (`docs/CoAct.md`) writes a trajectory record per
turn. An optional `:plan-dossier-path` field lets the trajectory inspector and
analytics correlate trajectories with their dossiers without re-parsing the
answer text.

---

## 14. Migration — Complete

### 14.1 Storage move — `.brainyard/plans/` → `.brainyard/agents/plan-agent/plans/` (shipped)

The plan corpus moved under the per-agent directory to match the ecosystem
convention. The move is **dual-read** and reversible:

- `create-plan` / `update-plan` **write** to the new path
  (`.brainyard/agents/plan-agent/plans/<slug>.md`).
- `read-plan` / `delete-plan` / `plan-exists?` resolve via `find-plan-file`,
  which checks the new location first and falls back to the legacy
  `.brainyard/plans/` for one release.
- `list-plans` enumerates BOTH locations, tagging each entry `:layout :new` or
  `:layout :legacy` (new wins when a slug exists in both) so callers see
  migration state at a glance.
- `bb migrate:plan-agent` copies legacy plans across; legacy files are left in
  place so a `git checkout` rolls back instantly.

The on-disk body schema is unchanged — only the path moved — so existing plans
stayed readable and downstream agents were untouched.

### 14.2 Lightweight authoring redesign (shipped)

The dossier and plan on-disk schema is unchanged, so existing dossiers stayed
readable and no downstream agent (todo/exec/eval) needed to change. The change
landed as:

1. New `plan_agent.clj` instruction/tool-context: the PERSIST section writes
   markdown directly from the DOSSIER TEMPLATE; the roster drops the `remove`
   clause that stripped `:write-file`/`:update-file`, drops the
   `plan$dossier-*` write helpers (keeping `plan/plan-dossier-helpers` =
   `[#'plan$read-dossier]`), and keeps `doc/doc-commands` for plan-body CRUD.
   `fetch-url` is removed from `file-tools`.
2. Slimmed `plan.clj`: the five write-side dossier helpers are no longer bound
   tools; the reader + lenient parser + INDEX writer + `render-dossier-md` are
   kept (the last two now serve the auto-persist hook, which does the same
   one-file write as the happy path).
3. POST-FLIGHT trimmed to PASS | HOLD; the REVISE auto-round is deferred to v1.5.
4. No data migration: dossiers written by the old helpers and by the new
   `write-file` path are byte-compatible at the frontmatter level.

---

## 15. Verification

| Benchmark | Shape | What it verifies |
| --------- | ----- | ---------------- |
| Pre-flight GO happy path | Clear request + valid `Saved exploration:` + refs exist | All C1–C7 pass; AUTHOR runs; POST-FLIGHT passes; exactly one plan file + one dossier on disk; `pre.verdict=go`, `post.verdict=pass`. |
| Pre-flight GATHER (missing exploration) | Request mentions specific files; no `Saved exploration:` | C3 fails; agent recommends `(explore-agent {…})`; dossier `pre.verdict=gather`; NO plan body written. |
| Pre-flight GATHER (goal unclear) | One-sentence ambiguous request | C1 fails; ONE multi-choice question surfaced; dossier records `gather_question`. |
| Pre-flight REFUSE (single edit) | "Rename foo→bar in src/x.clj" | C5 fails; agent refuses with redirect to edit-agent; dossier records `refuse_reason`. |
| Pre-flight REFUSE (duplicate plan) | Existing plan with same slug | C2 fails; agent points at the existing plan. |
| **Authoring (PASS happy path)** | GO + clean plan | Plan via `doc$create`; dossier via `write-file`; frontmatter parses; `checks`/`rubric` flow maps, `holds`/`acceptance` flow vectors; body has the 4 sections. |
| Post-flight HOLD | R3 cites a path that does not exist (v1 — no auto-revise) | R3 fails → HOLD; `holds` non-empty; answer has a `Hold:` line and NO `Next: (todo-agent …)`. |
| Dual-read fallback | Legacy plan at `.brainyard/plans/<slug>.md` | `doc$read` returns the body; `doc$list` tags it `:layout :legacy`. |
| Migration | `bb migrate:plan-agent` against 5 legacy plans | All 5 copied; old files preserved; dual-read still works. |
| Dossier round-trip | Write a dossier; `plan$read-dossier` it back | All contract keys present (`slug`, `plan_path`, `post.acceptance`, `handoff`). |
| Malformed-frontmatter guard | A dossier with a dropped key | `plan$read-dossier` returns a `:warning` naming the missing key, not a silently-missing field. |
| Downstream unchanged | New-path dossier → todo-agent fixture | Extracts `acceptance` exactly as before. |
| Auto-persist backstop | Answer with no (or a hallucinated) dossier write | Hook materializes a parseable dossier; `Saved dossier:` line injected; a claimed-but-nonexistent path is replaced. |

Per-iteration mulog signals:

- `::plan.auto-persist` — `{:slug … :path … :pre-verdict … :post-verdict … :answer-chars N}`
- `::plan.dossier-index` — `{:slug … :path … :pre-verdict … :post-verdict … :next-agent …}`
- `::plan.invalid-verdict` — `{:kind :pre|:post :verdict … :valid #{…}}` (coercion to `:n-a`)
- `::plan-file-skipped`, `::read-plan-failed`, `::list-plans-failed` — CRUD diagnostics.

---

## 16. Files Summary

| File | Role |
| ---- | ---- |
| `components/agent/src/ai/brainyard/agent/common/plan_agent.clj` | `instruction` (three-phase walk + write-markdown PERSIST + DOSSIER TEMPLATE), `tool-context`, `defagent plan-agent` via `coact/run-coact-derived`. Roster: `doc/doc-commands` (plan CRUD), `plan/plan-dossier-helpers` (read seam), `file-tools` minus `:fetch-url`, `shell-tools`, `query$llm`, bookkeeping/task/runtime sets. |
| `components/agent/src/ai/brainyard/agent/common/plan.clj` | Plan CRUD plain fns; `plan$read-dossier` (the lone bound dossier helper); lenient parser + INDEX writer + `render-dossier-md` (back the read seam + auto-persist hook); the `:agent.ask/finalize` auto-persist hook. Write-side dossier helpers retired as tools. |
| `components/agent/src/ai/brainyard/agent/common/doc.clj` | Polymorphic `doc$*` plan-body CRUD (`:kind :plan`), carrying the secret-scan + size guard. |
| `components/agent/test/ai/brainyard/agent/plan_agent_test.clj` | Registration smoke test, GO/GATHER/REFUSE/HOLD tests, dossier round-trip, auto-persist backstop. |
| `.brainyard/agents/plan-agent/README.md` | Directory layout cheat-sheet. |
| `bb.edn` | `migrate:plan-agent` task — one-shot legacy plan move. |
| `components/agent/src/ai/brainyard/agent/common/coact_agent.clj` | NO CHANGES — substrate, BT, sandbox, DSPy signature untouched. |

The feature ships as one agent file plus a slim helpers file (one bound read
seam). The CoAct loop, sandbox, BT, and DSPy signature were untouched.

---

## 17. Open Questions

1. **Keep `doc$create` for plan bodies, or go pure `write-file`?** **Resolved in
   favour of `doc$*`** — `doc$create`/`doc$update` carry the
   `guard/content-violation` secret-scan + size guard for free, which a raw
   `write-file` to the plans subtree would bypass. The dossier + INDEX (no secret
   risk) go through `write-file`.
2. **Should pre-flight C3 auto-dispatch `explore-agent` by default?** Today: no —
   it recommends. Auto-dispatch saves a turn but spends `explore-agent`'s
   iteration budget under plan-agent's hood, complicating per-agent budgeting.
   Keep recommend-by-default; opt-in. Revisit if benchmarks show GATHER turns
   dominate.
3. **POST-FLIGHT auto-revise (v1.5).** The single automatic `doc$update` revision
   round is deferred. The pure `handoff-from-verdicts` already carries a
   `:revise` branch; turning it on means re-running R1–R7 once after the
   amendment and HOLD-ing only if it still fails. Budget-by-tokens is an
   alternative to the one-round rule. Revisit when HOLD turns are common enough
   to justify the loop.
4. **Owner field — should pre-flight C7 ever hard-block?** Many internal projects
   lack a single owner; v1 keeps C7 INFORMATIONAL. Could be promoted to GATHER
   for production-touching plans (heuristic: any reference under
   `prod/`/`production/`/`live/`). Revisit on a real unowned production change.
5. **Should the dossier reference prior dossiers for the same slug?** A
   `pre.related_plans` field already lists same-area drafts. Adding
   `pre.prior_dossiers: [<paths>]` would let users follow a plan's lineage over
   turns. Cheap; defer until asked.
6. **Strict vs. lenient dossier read.** `plan$read-dossier` is lenient + warns
   (flags a missing contract key rather than rejecting). Strict rejection would
   catch drift earlier at the cost of pipeline flow. Currently lenient.
