# Exec-Agent — Lightweight File-Tool Redesign + Exec Substrate (revision 3)

> **Status:** Proposal. Fourth in the series after
> [`plan`](./plan-agent-lightweight-redesign.md),
> [`explore`](./explore-agent-lightweight-redesign.md), and
> [`todo`](./todo-agent-lightweight-redesign.md). Applies the same "write
> markdown directly, retire the construction helpers" treatment, inherits the
> todo redesign's index-free checkbox edits, and adds two pillars:
>
> 1. **An exec substrate protocol installed in the base agents** (`coact-agent`
>    + `react-agent`), so *any* agent can drive a checklist to completion with
>    evidence and safe writes — no `exec-agent` dispatch for routine work.
>    Mirrors the todo substrate (§8 of the todo doc) and the Project Memory
>    precedent.
> 2. **A contract-based exec-agent** — the formal, gated, audited execution path
>    that produces the evidence dossier `eval-agent` consumes. This is the
>    "second kind" of execution; the substrate is the first.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/exec_agent.clj`,
> `exec.clj`, plus the shared substrate section in the base agents.

---

## 1. Why the current paths are error-prone (two layers, again)

Exec-agent carries the **same two-layer brittleness as todo-agent**.

**Layer 1 — the dossier helper chain:** build `pre` / `execute` / `post` /
`handoff` maps with exact keys, feed them to `exec$dossier-frontmatter` (strict
ordered YAML, nested `execute.evidence` and `post.acceptance_progress` maps),
`exec$dossier-write` (frontmatter-prefix validated), `exec$dossier-index-append`
(verdicts + `advanced`/`pending` counts re-passed), `exec$next-handoff`. Same
auto-persist hook bolted on as the tell-tale crutch.

**Layer 2 — the checkbox-flip micro-tool:** after each item, exec-agent records
completion with

```clojure
(doc$update {:kind :todo :slug s :item-idx idx :item-done true})
```

This is the *exact* drifting-`:item-idx` hazard the todo redesign called out: the
agent must track a numeric index per item across an inner loop where `add-item`
insertions shift indices, and "flip item idx" can hit the wrong line. Exec-agent
is where it bites hardest, because flipping checkboxes *is its core loop* — it
does this more than any other agent.

Everything *between* pick and flip — routing per `:tags.via`, delegating writes
to edit-agent, verifying — is sound and stays. The brittleness is at the two
ends: **recording the flip** (Layer 2) and **persisting the evidence dossier**
(Layer 1). Both are markdown the model should write directly.

## 2. Thesis

Three shifts:

1. **Dossier authoring goes LLM-inherent** (as in plan/explore/todo):
   `write-file` the evidence dossier from a template; retire `exec$dossier-*`.
2. **Checkbox flips go index-free** by inheriting the todo substrate: flip
   `- [ ] <text>` → `- [x] <text>` with `update-file`, then `todo$read` to
   reconcile + refresh the TUI. Retire `doc$update :item-idx :item-done`.
3. **Execution becomes a base substrate (NEW):** the route → verify → record →
   flip discipline, the *evidence-before-flip* rule, and *safe-write-via-edit-agent*
   become a base system-context protocol any agent inherits — while
   `exec-agent` remains the **contract** path that adds gating + the eval
   dossier (§8).

One line: **doing an item and recording it should be a verify + a one-line edit**
— and any agent should be able to drive its own checklist safely without
spawning a subagent.

## 3. Design principles

1. **Writes are LLM-inherent.** Evidence dossier authored with `write-file`;
   checkbox flips are `update-file` text edits. No frontmatter constructors, no
   index-addressed flips.
2. **Reads stay deterministic.** Two read seams survive: `exec$read-dossier`
   (downstream metadata) and `exec$find` (resume — prior exec dossiers for a
   slug, newest-first). Plus `todo$read` for progress reconciliation.
3. **Evidence before flip — non-negotiable.** A box is only flipped after the
   item's work is *verified* (edit-agent record with `diff_match`, bash exit 0,
   recorded read excerpt). This is the one rule that makes eval-agent trustable;
   it survives verbatim.
4. **Safe writes delegate to edit-agent.** Source edits go through
   edit-agent's diff/verify/rollback pipeline, never raw `write-file`. For the
   contract agent this is a hard rule; for the substrate it's a strong default
   (§5).
5. **Index-free.** Items addressed by description text, not ordinals — insertions
   can't misalign a flip.
6. **The substrate is the common path; the subagent is the contract path.** Any
   agent can execute a checklist (§8.1–8.3); `exec-agent` is reserved for gated,
   audited, eval-bound execution (§8.4).
7. **Degrade gracefully.** Flips can't fail a schema; a malformed dossier line is
   caught on read; the slimmed auto-persist hook still backstops a skipped dossier.

## 4. What stays, what goes

| Concern | Today | Redesign |
| --- | --- | --- |
| Flip a completed item | `doc$update :item-idx N :item-done true` | `update-file "- [ ] <text>" → "- [x] <text>"`; then `todo$read` to reconcile. Index-free. |
| Route an item (`:via`) | inline routing per `:tags.via` | **Unchanged** — `edit-agent` / `bash` / `mcp$tools` / `explore-agent` / read / manual. |
| Source writes | delegate to `edit-agent` | **Unchanged (hard rule for contract; default for substrate).** |
| Verify an item | per-`:via` rules | **Unchanged** — evidence-before-flip. |
| Resume / skip-done | `exec$find` | **Keep** — read-only resume seam. |
| Dossier build/write/index | `exec$dossier-frontmatter`/`write`/`index-append`/`next-handoff` | **Removed.** `write-file` from the dossier template (§7). |
| Upstream dossier reads (C1–C3) | `todo$read-dossier` / `plan$read-dossier` | **Keep** — read-only. |
| Dossier read (downstream) | `exec$read-dossier` | **Keep.** |
| Auto-persist net | rebuilds via helpers | **Keep, simplified** — fills the template, `spit`s one file. |

Net: the four write-side dossier helpers **and** the `:item-idx` flip path
retire; routing/verification/delegation stay; read seams stay; `update-file`/
`read-file` get bound for the flip (today stripped by the `remove` clause —
note `write-file` stays delegated to edit-agent, see §5/§10).

## 5. The exec substrate protocol (the base section text)

This is the prose installed in the base agents (§8.2), modeled on
`coact-project-memory-protocol`. It layers the *execution discipline* over the
todo substrate's *checklist editing*:

```text
## Executing a checklist (exec substrate)
To DO a checklist item (not just track it), follow route → verify → record → flip:

1. ROUTE — decide how the item gets done:
   • source edit   → DELEGATE to edit-agent ((edit-agent {:question <item>
                     :agent-context <context> :dirty-ok? …})). It diffs, verifies,
                     and gives you `Saved edit: <path>` + `Rollback: <cmd>`.
                     Prefer this over raw write-file for tracked source — you get
                     a reversible, verified edit.
   • shell check   → (bash {:command <cmd>}); ok = exit 0.
   • external/MCP  → (mcp$tools …); reads proceed, writes need user confirm.
   • lookup        → read-file / grep / query$llm; keep a short evidence excerpt.
   • manual        → STOP, surface it, do NOT flip.

2. VERIFY — confirm it actually worked (diff_match, exit 0, recorded excerpt).

3. RECORD — note the evidence (edit path + rollback, command + exit, excerpt).

4. FLIP — only after VERIFY passes: update-file "- [ ] <text>" → "- [x] <text>"
   on the checklist, then todo$read <slug> to reconcile progress + refresh the TUI.

RULES:
- NEVER flip a box without supporting evidence (step 2).
- Prefer edit-agent for tracked-source writes (reversible + verified).
- Bound your work: a few items per turn, then summarize and continue.
```

This is genuinely useful to *any* agent that finds itself working through a
list, and it composes with the todo substrate (which supplies the checklist
format + the index-free flip + `todo$read`).

## 6. The read seams worth keeping

- **`exec$read-dossier`** — frontmatter-only parse; the downstream contract for
  eval-agent (`acceptance_progress`, `items_advanced`, etc.). Read-only. Keep.
- **`exec$find`** — prior exec dossiers for a slug, newest-first; powers resume
  (skip already-advanced items, retry held ones). Read-only. Keep — and, like
  explore's reuse gate, make a C8-style "check for prior exec evidence" the
  natural first step so re-runs don't redo completed items.
- **`todo$read`** (from the todo redesign) — reconcile progress + st-memory after
  flips.

All deterministic readers; none carry write-side brittleness. Keeping them
identical means **eval-agent doesn't change** — the redesign is contained in
exec-agent's recording/persisting path plus the shared substrate.

## 7. The evidence dossier as a template

Keys match `exec-agent-design.md` §7.3 so eval-agent is unaffected. The model
fills `<…>` and `write-file`s it to `dossiers/<yyyyMMdd-HHmmss>-<slug>.md`. The
`execute.evidence` and `post.acceptance_progress` blocks — which today the model
builds as precisely-keyed nested objects for the helper — become flow-style maps
it writes directly.

```markdown
---
slug: <slug>
agent: exec-agent
created: <ISO-8601>
todo_path: <.brainyard/agents/todo-agent/todos/<slug>.md>
plan_path: <.brainyard/agents/plan-agent/plans/<slug>.md>

source: {todo_dossier: <path>, plan_dossier: <path>}

pre:
  verdict: <go | gather | refuse>
  checks: {c1: pass, c2: pass, c3: pass, c4: pass, c5: pass, c6: pass, c7: pass, c8: informational}
  acceptance: ["<criterion 1>", "<criterion 2>"]
  gather_question: <or null>
  refuse_reason: <or null>

execute:                       # omit block when no EXECUTE ran (gather/refuse)
  budget: {max_items_per_turn: 5, used: 3, reason_for_stop: <pending-complete | budget | hard-blocker>}
  items_advanced: [0, 2]
  items_pending_after: [3]
  evidence:
    0: {ok: true, via: edit-agent, ref: ".brainyard/agents/edit-agent/records/…md", rollback: "git checkout …"}
    2: {ok: true, via: bash, ref: "exit 0; 12 tests passed"}

post:                          # omit block when no EXECUTE ran
  verdict: <pass | hold>
  rubric: {r1: pass, r2: pass, r3: pass, r4: pass, r5: pass, r6: n/a, r7: pass}
  holds: []
  acceptance_progress: {"<criterion 1>": evidence-recorded, "<criterion 2>": partial}

handoff: {next_agent: <eval-agent | exec-agent | user>, next_call: '<exact call>'}
---

# Exec dossier — <title>
## Pre-flight summary
## Execution log
<one line per item: idx · via · ok? · evidence ref>
## Post-flight notes
## Handoff
```

`evidence` keyed by item index is fine here (it's a record, not an addressing
scheme the model must mutate later); the danger was only in *flipping* by index,
which §5 removes.

## 8. Two kinds of execution (and base-agent installation)

The same correction the todo doc made for authoring applies to execution.

### 8.1 Working execution vs. contract execution

| | (A) Working execution | (B) Contract execution |
| --- | --- | --- |
| **Purpose** | Get a checklist done as part of the task at hand. | The audited plan→todo→exec→eval handoff with an evidence dossier. |
| **Owner** | Whatever agent is doing the work (often the root agent). | The pipeline; `exec-agent` drives it, `eval-agent` scores it. |
| **Ceremony** | The §5 substrate: route/verify/record/flip. No dossier. | Pre-flight C1–C8 gating on passed plan+todo dossiers, R1–R7 rubric, evidence dossier. |
| **Writes** | Prefer edit-agent; raw edits allowed for throwaway scratch. | edit-agent only (hard rule). |

Today only (B) exists — every execution goes through the heavyweight gated
subagent. Most interactive work is (A): a root agent that just needs to do the
three things on its list, with evidence and safe writes, but no dossier.

### 8.2 Install the substrate in the base agents

Exactly the mechanism from the todo doc (§8.2/§10b):

- **Tools — already inherited.** `edit-agent`, `bash`, `mcp$tools`,
  `update-file`, `read-file`, `todo$read` all ride `default-agent-roster` (common
  tools + commands), which `run-coact-derived` concatenates onto every derived
  agent. The tools half is done.
- **Guidance — the missing piece.** Add the §5 exec-substrate protocol as a base
  system-context section, modeled on `coact-project-memory-protocol`. Define one
  shared string; insert a `:exec-substrate` section into **both**
  `coact-system-context` and `react-system-context` (next to `:todo-substrate`).

So a single base-level edit gives every agent — `main-agent` included — the
ability to execute its own checklist with evidence and safe writes, no dispatch.

### 8.3 Root-agent inline execution

```clojure
;; work the item, the safe way
(edit-agent {:question "Extract the loop-guard predicate into its own fn"
               :agent-context "…" :dirty-ok? "false"})
;; → parse `Saved edit: <path>` + `Rollback: <cmd>`; verify diff_match
;; record evidence, then flip (index-free) and reconcile
(update-file {:path "…/refactor-loop-guard.md"
              :pattern "- [ ] Extract the predicate"
              :replacement "- [x] Extract the predicate"})
(todo$read {:slug "refactor-loop-guard"})
```

No pre-flight, no rubric, no dossier — but still evidence-before-flip and
safe-write-via-edit-agent, because those live in the substrate.

### 8.4 When you still want exec-agent

Reserve the subagent for **(B) contract execution**:

- The autoresearch `plan → todo → exec → eval` spine, where eval-agent needs the
  `acceptance_progress` evidence dossier.
- When you want the guarantee that execution was *gated* on passed plan+todo
  dossiers (C1–C3), *bounded* (`:max-items-per-turn`), and *audited* (R1–R7,
  evidence dossier).

The root agent dispatches `exec-agent` not to "do the items," but to "do them
*under contract* with an auditable evidence trail." Everything else it does
itself via the substrate.

### 8.5 Net recommendation

- Install the exec-substrate protocol in the base agents (§8.2); tools already in
  `default-agent-roster`.
- Let the root agent run working checklists inline (§8.3) — no dispatch.
- Keep `exec-agent` as the **contract executor** (§8.4), rewritten to author via
  file tools (§4–§7) and flip index-free.
- Net: far fewer subagent dispatches, index-free flips, evidence-before-flip and
  safe-writes available to every agent from one base edit, and an unchanged
  eval-agent contract.

## 9. New instruction & tool-context (sketch)

exec-agent keeps its three-phase discipline (it's the contract executor); the
EXECUTE recording and PERSIST steps swap to file edits:

```text
EXECUTE — per item: pick → route per :tags.via → VERIFY → record evidence →
  flip the box with update-file ("- [ ] <text>" → "- [x] <text>"), then
  todo$read <slug> to reconcile. NEVER flip without evidence. NEVER write source
  directly — delegate to edit-agent.
PERSIST — fill the DOSSIER TEMPLATE and write-file it; append one INDEX line.
  Do NOT construct frontmatter maps or call dossier helpers.
```

Tool-context: the `## Exec Tools` block keeps the routing/delegation surface and
the read seams, drops the `exec$dossier-*` write helpers, and notes the
index-free flip:

```text
- Flip a done item: update-file "- [ ] <text>" → "- [x] <text>" (match on text,
  not index), then todo$read :slug <s> to reconcile + refresh TUI.
- exec$read-dossier / exec$find — READ-ONLY (downstream + resume).
- edit-agent / bash / mcp$tools / explore-agent — item routing (unchanged).
```

## 10. `exec.clj` + base-agent changes

- **Remove** `exec$dossier-slug/frontmatter/write/index-append`,
  `exec$next-handoff`, and the YAML emitters; drop them from
  `exec-dossier-helpers` (or shrink to the readers).
- **Keep** `exec$read-dossier`, `exec$find` + their parse helpers.
- **Simplify the auto-persist hook** to fill the §7 template and `spit`.
- In `exec_agent.clj` `:agent-tools`: keep `write-file` **out** (delegation to
  edit-agent is the point), but ensure `update-file`/`read-file` are bound so
  the index-free flip works — adjust the `remove` clause to strip only
  `:write-file`/`:fetch-url`, not `:update-file`. Drop `exec/exec-dossier-helpers`
  write side; keep `[#'exec/exec$read-dossier #'exec/exec$find]`.
- **Base-agent wiring (the substrate):** define a shared `exec-substrate-protocol`
  string (the §5 block) alongside the todo substrate string; add an
  `:exec-substrate` section to both `coact-system-context` and
  `react-system-context` (`cond->` + `section-order`). No roster change —
  `edit-agent`, `bash`, `update-file`, `todo$read` already ride
  `default-agent-roster`.

> Note the asymmetry vs. other agents: exec-agent deliberately keeps `write-file`
> **unbound** even after this redesign — its cardinal rule is "delegate writes to
> edit-agent." Only `update-file` is added, and only for the checkbox flip on
> the todo markdown (not source). The substrate's safe-write guidance is what
> lets base agents hold `write-file` without abusing it for risky source edits.

## 11. Migration

- Land the new `exec_agent.clj` instruction/roster and slimmed `exec.clj`.
- On-disk dossier schema unchanged → existing exec dossiers stay readable;
  eval-agent untouched.
- Checklist flip changes from `:item-idx` to `update-file` text match — depends
  on the todo redesign's substrate landing first (or co-landing).
- Remove dossier-write + `:item-idx`-flip tests; keep read/find/resume tests; add
  index-free-flip and substrate tests.
- Install the exec-substrate section in the base agents behind the same flag as
  the todo substrate; measure dispatch reduction.

## 12. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Base agents gain `update-file` and edit source unsafely. | The substrate's safe-write rule (prefer edit-agent for tracked source); `update-file` returns a diff so the agent sees the change. For exec-agent specifically, `write-file` stays unbound. |
| Checkbox flip matches the wrong line. | Match on unique description text; `update-file` is first-match + diff-returning. Dup descriptions get a distinguishing token (same as todo §13). |
| TUI stale after a raw flip. | Mandatory `todo$read` after flips (§5 step 4) re-mirrors st-memory. |
| Flip without evidence slips through. | Substrate rule + (contract path) R2 "no false flips" rubric; the dossier records evidence per advanced item, so a flip with no `evidence[idx]` is detectable. |
| Resume redoes completed items. | `exec$find` C8 check surfaces prior `items_advanced`; skip them. |
| Losing structured `acceptance_progress`/`evidence`. | Keys preserved in the template as flow maps; parse identically. |

## 13. Verification

- **Flip (index-free)** — execute an item, `update-file` its box, `todo$read`
  shows completed+1 and correct `:next-item`; st-memory refreshed.
- **Insert then flip** — add an item mid-loop, flip an *earlier* item by text;
  assert the right box flipped (the test today's `:item-idx` drift fails).
- **Evidence-before-flip** — an item whose verify fails is NOT flipped; dossier
  records the failure.
- **Dossier** — write-file from template; `exec$read-dossier` returns all
  contract keys incl. `acceptance_progress`, `items_advanced`.
- **Resume** — second run reads `exec$find`, skips advanced items, retries held.
- **Downstream unchanged** — feed the dossier to an eval-agent fixture; reads
  `acceptance_progress` as before.
- **Substrate** — main-agent (with the substrate) drives a working checklist:
  routes a write through edit-agent, verifies, flips, reconciles — zero
  exec-agent dispatch.
- **Auto-persist backstop** — skipped dossier → hook materializes a parseable one.

New mulog: `::exec.item` (`{:slug :idx :via :ok :flipped}`), `::exec.sync`,
alongside the existing dossier signals.

## 14. Open questions

1. **Auto-sync on flip?** A `:agent.tool-use/post` hook on `update-file` targeting
   `todos/*.md` could auto-`todo$read`. Cleaner; couples hook registry to the path
   convention. Same question as the todo doc — answer them together.
2. **Should the substrate's "prefer edit-agent" be advisory or enforced?** A
   `:agent.tool-use/pre` hook could refuse base-agent `write-file` on tracked
   source and nudge to edit-agent. Stronger guarantee; bigger blast radius.
   Prototype.
3. **Working/contract boundary** — directory (`<root>/todos/` vs the pipeline
   dossiers), frontmatter `kind`, or presence of an exec dossier? Align with the
   same decision in the todo doc.
4. **Should exec-substrate and todo-substrate be one combined section** ("Working
   checklists: track + execute") in the base agents, or two? One section reads
   better; two keeps tracking vs. doing separable. Lean one combined section.
5. **Resume reuse like explore's gate** — promote C8 from informational to a real
   "don't redo advanced items" default. Low risk; do it.

---

## Appendix — before/after, one item

**Before — advance an item (drifting index + helper-built dossier):**

```clojure
;; … route + verify item 3 …
(doc$update {:kind :todo :slug "ship-v2-checkout" :item-idx 3 :item-done true})  ; idx may have drifted
;; … later, dossier via the helper chain …
(def fm (:frontmatter (exec$dossier-frontmatter :slug … :pre … :execute … :post …
                        :handoff (exec$next-handoff …))))
(def res (exec$dossier-write :slug … :content (str fm body)))
(exec$dossier-index-append :path (:path res) :slug … :pre-verdict :go :post-verdict :pass
                           :next-agent "eval-agent" :advanced 3 :pending 1)
```

**After — advance an item (verify → text flip → reconcile; markdown dossier):**

```clojure
(edit-agent {:question "Update payment validator for legacy carts" :agent-context "…"})
;; verify Saved edit / Rollback / diff_match, then:
(update-file {:path "…/ship-v2-checkout.md"
              :pattern "- [ ] Update payment validator for legacy carts"
              :replacement "- [x] Update payment validator for legacy carts"})
(todo$read {:slug "ship-v2-checkout"})
;; … at end of turn, one write-file from the DOSSIER TEMPLATE + one INDEX append.
```

And for routine work the root agent does exactly this itself — the exec-agent
subagent comes out only when you want a *gated, audited, eval-bound* execution
with an evidence dossier.
