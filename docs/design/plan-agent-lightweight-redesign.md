# Plan-Agent — Lightweight, File-Tool Redesign (revision 3)

> **Status:** Proposal. Supersedes the helper-heavy authoring path described in
> [`plan-agent-design.md`](./plan-agent-design.md). The three-phase *contract*
> (PRE-FLIGHT → AUTHOR → POST-FLIGHT, plus a per-turn dossier) is **kept**.
> What changes is *how the agent writes its artifacts*: direct markdown
> authoring via `read-file` / `write-file` / `update-file`, replacing the
> `plan$dossier-*` / `doc$*` micro-tool chain.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/plan_agent.clj`
> (instruction + tool roster) and `plan.clj` (retire the write-side dossier
> helpers; keep one deterministic read seam).
> **Decision (this revision):** the dossier survives — but the LLM authors it
> as a plain markdown file from a fixed template, not by constructing
> exact-keyed Clojure maps that a helper renders into ordered YAML.

---

## 1. Why the current authoring path is error-prone

The shipped plan-agent does its *checking* in prose but its *persisting*
through a chain of structured micro-tools. A single PASS turn asks the LLM to,
in order:

1. Build a `pre` map with the exact keys `:verdict :checks :exploration-path
   :owner :related-plans :gather-question :refuse-reason`, where `:checks` is
   itself a flat map of `:c1..:c7 → :pass|:fail|:not-evaluated`.
2. Build an `author` map (`:action :body_bytes`).
3. Build a `post` map (`:verdict :rubric :revision_applied :holds
   :acceptance …`), `:rubric` again a flat `:r1..:r7` map.
4. Call `plan$next-handoff` to derive `{:next-agent :next-call}`.
5. Feed all four maps to `plan$dossier-frontmatter`, which renders them into
   YAML with a *fixed key order* and flow-style sub-maps.
6. Concatenate `(str fm body)` and call `plan$dossier-write` — which
   **rejects** any content that doesn't begin with `---\n…\n---\n`.
7. Call `plan$dossier-index-append` with the verdicts passed *again* as
   separate arguments.
8. And the plan body itself goes through `doc$create` / `doc$update` with
   `:kind :plan`.

Every one of those calls has its own schema, and the maps share key names that
must agree across calls (the handoff verdict in step 4 must match the index
verdict in step 7 must match the frontmatter in step 5). When the model
mis-keys `:revision-applied?` vs `revision_applied`, emits `:checks` as a
vector, forgets the leading frontmatter that `plan$dossier-write` requires, or
swaps a `:pass`/`:fail` keyword for a string, the call errors and the turn
burns an iteration on a retry. The structure is doing the LLM no favors — it is
*fighting* the model's native fluency.

The strongest evidence that this path is too brittle is already in the
codebase: `plan.clj` ships an **auto-persist hook**
(`materialize-auto-dossier!`, `plan-auto-persist`, `install-auto-persist!`) on
`:agent.ask/finalize` whose entire reason for existing is that *"Capable models
usually [call the helpers]; smaller models often skip the helpers and just
ASSERT in the answer text that they wrote a dossier — sometimes with
hallucinated paths."* We built a safety net because the primary path leaks.

The thing the model *is* reliably good at — emitting a well-formed markdown
document with a YAML frontmatter block — is exactly what we are routing
*around*.

## 2. Thesis

Let the LLM do what it is inherently good at: **write the plan and the dossier
as markdown files, directly.** Replace the construct-maps → render-YAML →
validated-write → re-pass-verdicts chain with two `write-file` calls against
fixed templates the model fills in. Keep deterministic machinery only where a
machine genuinely beats the model — *reading* a dossier's frontmatter for
downstream routing, and *enumerating* existing plans for the duplicate check.

In one line: **authoring becomes prose; only reading stays typed.**

## 3. Design principles

1. **Writes are LLM-inherent.** Plan body and dossier are authored with
   `write-file` from a template embedded in the instruction. No
   `plan$dossier-frontmatter`, no `plan$dossier-write`, no `plan$next-handoff`.
2. **Reads stay deterministic.** Downstream agents (todo/exec/eval) still need
   to pull `acceptance` and `plan_path` cheaply and reliably from a dossier.
   That single read seam — `plan$read-dossier`, or a `read-file :lines [1 N]`
   plus a tolerant parser — is the *one* helper worth keeping, because parsing
   is where a machine is strictly more reliable than a model.
3. **The contract is the template, not a schema object.** The dossier
   frontmatter keys (§5) are still a contract with downstream agents. We
   enforce it by *showing the exact template* in the instruction and by
   validating on read, rather than by forcing the model through a constructor.
4. **One dossier per turn, always** — unchanged. GATHER and REFUSE turns still
   produce a dossier; it's the audit trail.
5. **The three phases are a prompt contract, not a tool pipeline** — unchanged
   in spirit. PRE-FLIGHT / AUTHOR / POST-FLIGHT remain, but their outputs live
   as *sections of the dossier markdown the model is already writing*, not as
   Clojure maps it must construct precisely before it can write anything.
6. **Degrade gracefully.** A keyword typo can no longer fail a write —
   markdown has no schema to violate. The remaining failure mode (a malformed
   frontmatter line) is caught on read by a lenient parser, and a slimmed
   safety-net hook still backstops a fully-skipped dossier.

## 4. What stays, what goes

| Concern | Today | Redesign |
| --- | --- | --- |
| Plan body CRUD | `doc$create` / `doc$update` (`:kind :plan`) | `write-file` to `.brainyard/agents/plan-agent/plans/<slug>.md` from a template. `read-file` to load before an update. |
| Plan listing (dup check C2) | `doc$list :kind :plan` | **Keep** `doc$list :kind :plan` (deterministic enumeration + frontmatter parse; tedious and error-prone to reproduce in bash). |
| Dossier slug | `plan$dossier-slug` | LLM derives a kebab slug from the title/request inline (it already does this fluently); collision handled by a timestamp prefix on the filename. |
| Dossier frontmatter build | `plan$dossier-frontmatter` (ordered YAML, flow-maps) | **Removed.** LLM writes the frontmatter directly from the §5 template. |
| Dossier write | `plan$dossier-write` (frontmatter-prefix validation) | `write-file` (`.brainyard/` is auto-allowlisted — no prompt, handles mkdir). |
| INDEX prepend | `plan$dossier-index-append` | `read-file` INDEX → prepend one line → `write-file` (or skip; see §7). |
| Handoff computation | `plan$next-handoff` | LLM writes the `handoff:` block + `Next:` line from a 4-case rule table in the instruction. |
| Dossier read (downstream) | `plan$read-dossier` | **Keep** — the one deterministic seam (§6). |
| Auto-persist safety net | `materialize-auto-dossier!` (rebuilds via helpers) | **Keep, simplified** — it now just writes one templated markdown file (§9). |

Net: five write-side helpers retire (`plan$dossier-slug`,
`plan$dossier-frontmatter`, `plan$dossier-write`, `plan$dossier-index-append`,
`plan$next-handoff`); two read/enumerate seams stay (`plan$read-dossier`,
`doc$list`/`doc$read`); `write-file`/`read-file`/`update-file` get bound (today
they are explicitly *removed* from the plan-agent roster — see the `remove`
clause in `plan_agent.clj` `:agent-tools`).

## 5. The dossier as a template (the thing the model fills in)

The instruction carries this verbatim. The model copies it, fills the
`<…>` slots, and `write-file`s it. The keys are unchanged from
`plan-agent-design.md` §7.2, so downstream parsers need no changes.

```markdown
---
slug: <kebab-slug>
agent: plan-agent
created: <ISO-8601, e.g. 2026-06-29T14:03:11Z>
plan_path: <.brainyard/agents/plan-agent/plans/<slug>.md or "null" on GATHER/REFUSE>
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
  body_bytes: <int>

post:
  verdict: <pass | hold>      # omit this whole block when no AUTHOR ran
  rubric: {r1: pass, r2: pass, r3: pass, r4: pass, r5: pass, r6: pass, r7: pass}
  holds: []
  acceptance:
    - "<observable criterion 1>"
    - "<observable criterion 2>"

handoff:
  next_agent: <todo-agent | user | none>
  next_call: '<exact (todo-agent {…}) form, or a one-line instruction>'
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

- **Flow-style `checks` / `rubric` maps** (`{c1: pass, …}`) are one line each —
  far easier for a model to emit correctly than a nested block, and the
  existing `parse-dossier-yaml` already accepts flow maps as raw strings.
- **The frontmatter keys are fixed and shown**, so the model isn't *inventing*
  structure — it's filling blanks. That is the regime LLMs are reliable in.

## 6. The one helper worth keeping — `plan$read-dossier`

Downstream agents must extract `plan_path` and `post.acceptance` cheaply and
deterministically. Hand-rolling YAML parsing in every consumer (or trusting the
model to parse) is the wrong trade. `plan$read-dossier` already does exactly
this — reads only the leading `---/---` block, returns a parsed map — and it is
*read-only*, so it carries none of the write-side brittleness this redesign
targets. **Keep it as-is.**

(If we want zero plan-specific helpers, the fallback is `read-file :lines [1
40]` + the generic tolerant parser. But a named, tested reader is cheap
insurance for the cross-agent contract, so the recommendation is to keep it.)

The corollary: by keeping the *reader* and the *template* identical to today's
schema, **no downstream agent (todo/exec/eval) needs to change.** This redesign
is contained entirely within plan-agent's authoring path.

## 7. INDEX.md — three options, pick the cheap one

`INDEX.md` is a newest-first convenience index, not part of the downstream
contract. Options, cheapest LLM-effort first:

1. **Append, not prepend.** `write-file :append true` one line per dossier;
   "newest first" becomes "newest last." Re-sorting is a read-time concern.
   Zero extra reads. *Recommended for v1.*
2. **Read-modify-write prepend.** `read-file` INDEX → prepend line →
   `write-file`. Preserves newest-first at the cost of one extra read per turn.
3. **Derive on demand.** Drop the persisted INDEX; a `plan$index` reader (or
   `ls -t` over `dossiers/`) reconstructs it when asked. No write at all.

All three are a single, schema-free file operation — none reintroduces a
structured micro-tool. Recommendation: option 1, with the line format
unchanged (`- <date> [<slug>](dossiers/<file>.md) — pre:<v> · post:<v> · → <next>`).

## 8. New instruction & tool-context (sketch)

The instruction keeps the three-phase walk and the hard rules, and swaps the
PERSIST section. The relevant rewrite:

```text
────────────────────────────────────────────────────────────────────────────
AUTHOR — only on PRE-FLIGHT = GO
────────────────────────────────────────────────────────────────────────────
- Pick a kebab-slug from the plan title (lowercase, words joined by '-').
- New plan → write-file to
    .brainyard/agents/plan-agent/plans/<slug>.md
  using the PLAN TEMPLATE (frontmatter + ## Context / ## Approach / ## Risks /
  ## References / ## Acceptance). .brainyard/ is auto-allowed — no prompt.
- Existing plan → read-file it first, merge your changes into the body, then
  write-file the whole file back. NEVER blind-overwrite a rich plan.
- Confirm the duplicate check first: (doc$list {:kind :plan}). Exact title
  match → REFUSE; near match → GATHER.

────────────────────────────────────────────────────────────────────────────
PERSIST — dossier (always), one write-file
────────────────────────────────────────────────────────────────────────────
Fill the DOSSIER TEMPLATE (shown below) with this turn's verdicts and
write-file it to
    .brainyard/agents/plan-agent/dossiers/<yyyyMMdd-HHmmss>-<slug>.md
Then append one INDEX line:
    write-file {:path ".brainyard/agents/plan-agent/INDEX.md"
                :content "<line>\n" :append true}

Do NOT construct Clojure maps for the dossier. WRITE THE MARKDOWN. The
frontmatter keys are fixed — copy the template and fill the <…> slots.

HANDOFF (fill handoff: and the Next: answer line) — pick one:
  pre=go,  post=pass  → next_agent: todo-agent ; next_call: (todo-agent {…})
  pre=go,  post=hold  → next_agent: user       ; next_call: resolve holds, re-run plan-agent
  pre=gather          → next_agent: user       ; next_call: supply the gather_question input
  pre=refuse          → next_agent: none       ; next_call: see refuse_reason redirect
```

Tool-context shrinks correspondingly: the whole "PERSISTENCE HELPERS
(`plan$dossier-*` …)" block collapses to a short note —

```text
## Persistence — write markdown directly
- write-file / read-file / update-file are bound. .brainyard/ and /tmp are
  auto-allowed (no permission prompt); write-file creates parent dirs.
- Author the plan body AND the dossier as markdown files from the templates
  in the instruction. There are no dossier-construction tools to call.
- plan$read-dossier  — READ-ONLY. Parse a dossier's frontmatter (used by you
  to inspect a prior dossier, and by todo/exec/eval downstream). Cheap.
- doc$list / doc$read :kind :plan — enumerate / read existing plans for the
  C2 duplicate check.
```

## 9. `plan.clj` changes

- **Remove** `plan$dossier-slug`, `plan$dossier-frontmatter`,
  `plan$dossier-write`, `plan$dossier-index-append`, `plan$next-handoff` and
  the `plan-dossier-helpers` roster entry (or shrink the roster to just the
  reader). The YAML *emission* helpers
  (`dossier-yaml-*`, `build-dossier-frontmatter*`, key-order vectors) go with
  them.
- **Keep** `plan$read-dossier` and the parse side
  (`dossier-read-frontmatter-lines`, `parse-dossier-yaml`,
  `dossier-parse-*`) — these back the read seam and the safety-net hook.
- **Keep** the plan CRUD plain fns and the `doc$*` plan surface for listing.
- **Simplify the auto-persist hook.** `materialize-auto-dossier!` currently
  reconstructs the dossier by calling the very helpers we're removing. Rewrite
  it to fill the §5 template string and `spit` it directly (it already infers
  `pre`/`post` verdicts from the answer text via `detect-pre-verdict` /
  `detect-post-verdict` — keep that). The hook stays a backstop, but now it
  does the *same* thing the happy path does (write one markdown file), so the
  two paths can't diverge.

In `plan_agent.clj` `:agent-tools`, **delete the `remove` clause** that strips
`:write-file :update-file` from `file-tools`, so the agent gains
`write-file` / `read-file` / `update-file`. Drop `plan/plan-dossier-helpers`
from the `concat` (or replace with `[#'plan/plan$read-dossier]`). Keep
`doc/doc-commands` for listing/reading plans, `shell-tools` (C4/R3 `test -f`),
`query$llm` (rubric), and the bookkeeping/task/runtime sets.

## 10. Migration from the helper-based agent

Mechanically smaller than the original `.brainyard/plans/` move (which has
already shipped). No on-disk format changes — dossier and plan files keep the
same schema and locations, so **existing dossiers remain readable** and
downstream agents are untouched.

1. Land the new `plan_agent.clj` (instruction + roster) and the slimmed
   `plan.clj`.
2. Remove the five write-side helpers and their tests; keep/retarget the
   reader tests.
3. Update `plan-agent-design.md`'s "as-built" banner to point here.
4. No data migration: dossiers written by the old helpers and by the new
   write-file path are byte-compatible at the frontmatter level.

## 11. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Hand-authored YAML frontmatter is malformed (bad indent, missing quote). | `parse-dossier-yaml` is already lenient (flat scalars, flow maps as raw strings, tolerant of unknown lines). Keep `plan_path` / `acceptance` on their own simple lines. Add a read-time validator that flags a dossier missing `slug`/`plan_path` so consumers fail loud, not silent. |
| Model invents a slug that collides. | Filename carries a `<yyyyMMdd-HHmmss>-` prefix, so same-slug turns never overwrite. (Same guarantee the old `-N` suffix gave, achieved by the timestamp.) |
| Model skips the dossier entirely. | The simplified auto-persist hook still fires on `:agent.ask/finalize` and writes a minimal templated dossier from the answer text. |
| Losing the `body_bytes` / structured `rubric` that some tooling reads. | Keys are preserved in the template; flow-style `rubric`/`checks` parse the same as before. `body_bytes` is informational — the model can fill it or write `null`. |
| Plan body no longer guarded by `doc$create`'s content guard. | If the `guard/content-violation` check matters, wrap `write-file` for the plans dir, or keep `doc$create` for *new* plans and use `write-file` only for the dossier. (Decision point for the user — see Open Questions.) |

## 12. Verification

Replace the helper-roundtrip tests with template/file-tool tests; keep the
behavioral ones.

- **PASS happy path** — GO + clean plan → exactly one plan file and one dossier
  file on disk; dossier frontmatter parses; `pre.verdict=go`, `post.verdict=pass`.
- **GATHER** — no plan file written; dossier has `pre.verdict=gather` and a
  non-null `gather_question`.
- **REFUSE** — no plan file; `pre.verdict=refuse`, `refuse_reason` set.
- **HOLD** — plan file written; `post.verdict=hold`, `holds` non-empty; answer
  has a `Hold:` line and no `Next: (todo-agent …)`.
- **Dossier read-back** — `plan$read-dossier` on a model-authored dossier
  returns all contract keys (`slug`, `plan_path`, `post.acceptance`, `handoff`).
- **Downstream unchanged** — feed a new-path dossier to a todo-agent fixture;
  it extracts `acceptance` exactly as before.
- **Auto-persist backstop** — answer with no dossier write → hook materializes
  a parseable dossier.
- **Malformed-frontmatter guard** — a dossier with a dropped key is flagged by
  the read-time validator rather than returning a silently-missing field.

Per-turn mulog signals (`::plan.author`, `::plan.dossier-write`,
`::plan.handoff`) move from the helpers into the hook + a thin `write-file`
wrapper, or are emitted by the agent loop — no loop changes required.

## 13. Open questions

1. **Keep `doc$create` for new plan bodies, or go pure `write-file`?**
   `doc$create` gives a random 3-word slug + a content guard for free; pure
   `write-file` is more uniform with the dossier path and lets the model pick a
   meaningful slug. Recommendation: pure `write-file` for both, and port the
   content guard into a `write-file` wrapper for the plans/ subtree if the
   guard is load-bearing.
2. **INDEX strategy** — append-only (§7 option 1) vs. derive-on-demand. Lean
   append-only for v1.
3. **Should the same lightweight treatment roll out to explore-agent /
   edit-agent**, which share the identical helper-chain + auto-persist-crutch
   pattern? Out of scope here, but the win generalizes.
4. **Strict vs. lenient dossier read** — do we want a hard schema validation on
   read (reject malformed dossiers) or stay lenient with a warning? Lenient +
   warn keeps the pipeline flowing; strict catches drift earlier.

---

## Appendix — before/after, one PASS turn

**Before (happy path, ~6 coupled tool calls):**

```clojure
(def authored (doc$create {:kind :plan :title "…" :body "…" :scope :project}))
(def post {:verdict :pass :rubric {:r1 :pass …} :holds [] :acceptance ["…"]})
(def fm (:frontmatter (plan$dossier-frontmatter :slug … :pre pre
                        :author authored :post post
                        :handoff (plan$next-handoff :pre pre :post post :slug …))))
(def res (plan$dossier-write :slug … :content (str fm body)))   ; rejects non-FM content
(plan$dossier-index-append :path (:path res) :slug … :pre-verdict :go
                           :post-verdict :pass :next-agent "todo-agent")
```

**After (happy path, 2 writes + 1 list):**

```clojure
(doc$list {:kind :plan})                       ; C2 duplicate check (read)
(write-file {:path ".brainyard/agents/plan-agent/plans/ship-v2-checkout.md"
             :content "<filled PLAN TEMPLATE>"})
(write-file {:path ".brainyard/agents/plan-agent/dossiers/20260629-140311-ship-v2-checkout.md"
             :content "<filled DOSSIER TEMPLATE>"})
(write-file {:path ".brainyard/agents/plan-agent/INDEX.md"
             :content "- 2026-06-29 14:03 [ship-v2-checkout](dossiers/…md) — pre:go · post:pass · → todo-agent\n"
             :append true})
```

The model never constructs a frontmatter object, never re-passes a verdict
across calls, and cannot fail a write on a keyword typo — it writes the
documents it is already fluent at writing.
