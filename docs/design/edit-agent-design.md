# Edit-Agent — Safe Single-File Edit Specialist (design)

> **Status:** Proposal / design of record for the edit specialist. **Renamed from
> `update-agent`** (and `update$*` → `edit$*`) — the domain noun was already
> "edit" everywhere (`edits/` records, `Saved edit:` handoff, the edit record,
> the edit substrate), so `update-*` was the lone inconsistency and overloaded
> the generic `doc$update` CRUD verb; see §9.4 for the rename migration. This doc
> supersedes `update-agent-design.md` and `update-agent-lightweight-redesign.md`.
>
> Three things define edit-agent: it is the **write chokepoint** every other
> agent delegates source writes to; its value is a **safe-edit transaction**
> (probe → apply → verify → persist → rollback) wrapped around a raw edit; and
> most of that transaction is **already one deterministic tool, `edit$apply`** —
> which is what lets *any* agent edit safely without dispatching the subagent.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/edit_agent.clj`
> (formerly `update_agent.clj`), `edit.clj` (formerly `update.clj`), plus a
> shared edit-substrate section in the base agents.

---

## 1. The principle: judgment vs. mechanism

Edit-agent is the case that sharpens the design rule used across the agent
series. The rule is **not** "retire all micro-tools." It is a
**judgment-vs-mechanism split**:

- **Mechanism** — deterministic work a machine does correctly and a model does
  badly: parsing frontmatter, diffing, counting matches, running a linter,
  restoring bytes on failure, git plumbing. **Keep these as tools.** Making the
  LLM hand-roll them is the real source of error.
- **Judgment / authoring** — composing a plan body, an exploration summary, a
  checklist, an evidence dossier, a YAML frontmatter block. **Let the LLM write
  these as prose/markdown.** Forcing them through a structured constructor is the
  brittleness to remove.

The edit-record frontmatter helpers (`edit$slug` / `edit$frontmatter` /
`edit$write` / `edit$index-append`) are *authoring* — they retire (§3, §5).
`edit$apply` is *mechanism* — it diffs, counts, lints, and rolls back — so it
**stays**, and is the lever that closes the raw-`update-file` gap (§4). The read
seams (`edit$read-record`, `edit$find`) are mechanism too and stay.

## 2. Why edit-agent is necessary (vs. just calling `update-file`)

`update-file` is an **unguarded find/replace**: take `path`/`pattern`/
`replacement`/`regex?`/`all?`, substitute, return `{:replaced N :diff …}`. It
does not check how many matches existed, whether the result still parses,
whether you clobbered an unintended occurrence, or give you a way back. On real
source it is a foot-gun:

- **Over/under-match** — `:all? true` on a 9-match pattern you thought matched 3
  silently rewrites 6 wrong sites.
- **No post-condition** — nothing confirms old text gone / new present / file
  still lints / tests still pass.
- **No undo** — a bad edit leaves the tree broken; recovery is manual.
- **No audit** — no record of what changed, why, or how to revert.

Edit-agent wraps `update-file` in a **transaction** that supplies exactly those
guarantees:

| Stage | What it adds over raw `update-file` |
| --- | --- |
| **PROBE** | Clean-tree precondition (`git status --porcelain`); **expected match-count** check (`rg --count-matches` must equal what you pre-stated) → catches over/under-match *before* writing; region sha for syntax-mode. |
| **APPLY** | Exactly one write; mode discipline (pattern / syntax / new-file). |
| **VERIFY** | Postconditions: git diff matches expectation, old pattern gone, new present, **lint delta** (only edit-introduced findings count), optional `bb test:component`. |
| **PERSIST** | Audit record (request, mode, diff, verify result) under `edits/` + INDEX. |
| **ROLLBACK** | On any verify failure, **transaction-scoped restore** of pre-APPLY bytes — prior uncommitted work survives (§8). |

In one sentence: **`update-file` mutates; edit-agent commits-or-rolls-back a
verified, audited edit.** That transaction is precisely why every other agent
delegates source writes here, and why the chokepoint is a *safety and audit
boundary*, not ceremony.

### 2.1 Most of that is mechanism — and already a tool

PROBE/APPLY/VERIFY/PERSIST/ROLLBACK is **all deterministic**, and it is **already
packaged as one SCI-callable command, `edit$apply`**, returning
`{:ok? :mode :replaced :diff :verify :rollback}`. So the gap between
`update-file` and edit-agent is, mechanically, the gap between `update-file` and
`edit$apply` — and that gap is already a tool, not an agent.

What the *agent loop* adds beyond the tool is only **judgment**: choosing the
edit mode, re-anchoring the pattern when the match-count precondition fails,
deciding to escalate to syntax-mode or refuse a multi-file request back to
plan-agent, and emitting the handoff lines.

## 3. What stays, what goes

| Concern | Before | Now |
| --- | --- | --- |
| The safe-edit transaction | `update$apply` | **`edit$apply` — kept, unchanged** (mechanism; the gap-closer, §4). |
| Match-count / lint / diff / rollback plumbing | inside the pipeline | **Keep.** Deterministic; never hand-rolled. |
| Edit-record build/write/index | `update$slug`/`frontmatter`/`write`/`index-append` | **Removed.** `write-file` the record markdown from a template (§5). |
| Record read (downstream) | `update$read-record` | **`edit$read-record` — kept** (read seam). |
| Prior-edit search | `update$find` | **`edit$find` — kept** (read seam). |
| Raw `update-file` / `write-file` | bound | **Keep bound** — this is the one agent that writes. **Names unchanged** — they're generic file primitives, not the agent's domain. |
| Auto-persist net | rebuilds the record | **Keep, simplified** — fills the §5 template, `spit`s one file. |

Net: only the four **record-persistence** helpers retire; the transactional core
(`edit$apply` + plumbing) and the read seams stay. Smaller diff than the other
agents because edit-agent's center of gravity is mechanism.

## 4. Closing the gap — any agent edits safely without the subagent

### 4.1 An edit substrate in the base agents

Install a base system-context protocol (modeled on `coact-project-memory-protocol`,
beside the todo/exec substrates) telling **any** agent: *don't call raw
`update-file` on tracked source — call the transaction.*

```text
## Editing a source file (edit substrate)
To change a tracked source file, do NOT use raw update-file/write-file. Use the
safe-edit transaction, which probes, applies once, verifies (diff + match counts
+ lint, optional tests), and ROLLS BACK on failure:

  (def res (edit$apply :request "<what + why>" :target "<repo-rel path>"
                       :mode :pattern :pattern "<lit/regex>" :replacement "<new>"
                       :all? <bool> :dirty-ok? <bool> :run-tests? <bool>))

Branch on :ok?
  • true  → keep res :rollback (e.g. "git checkout -- <path>") so you can revert.
  • false → the workspace is ALREADY restored; read res :verify to see which
            postcondition failed; re-anchor or refuse. Do NOT re-edit blindly.

RULES:
- Pre-state the expected match count; if edit$apply reports a mismatch, your
  pattern is wrong — make it longer/unique, don't force :all?.
- Raw update-file/write-file is only for throwaway scratch (/tmp, .brainyard
  notes), NEVER for tracked source.
- One file per call.
```

The substrate carries the *discipline*; `edit$apply` (in
`default-agent-roster` via the common commands, inherited by every coact/react-
derived agent) carries the *mechanism*. Net: **any agent — main-agent included —
makes a verified, reversible edit by calling one tool**, no edit-agent dispatch.
That is what closes the gap: expose the transaction as a first-class tool plus a
base protocol for using it, rather than gating it behind a subagent.

### 4.2 Optionally harden `update-file` itself (lowest tier)

For agents that still reach for bare `update-file`, fold the two cheapest guards
into the tool:

- **`:expect-count N`** — refuse the edit (no write) when the literal match count
  ≠ N. Kills the over/under-match foot-gun at the source.
- **Return a `:rollback` hint** + pre-edit bytes availability, so a bare edit is
  at least reversible.

Not a replacement for the full transaction (no lint/test/audit), but it raises
the floor: "refused; count was 9, you said 3" beats "silently rewrote 6 wrong
sites."

### 4.3 The two kinds of edit

| | (A) Working edit | (B) Contract edit |
| --- | --- | --- |
| **How** | `edit$apply` via the §4.1 substrate, inline in whatever agent is working. | `edit-agent` subagent. |
| **Ceremony** | One tool call + branch on `:ok?`. | Full prompt pipeline, edit record + INDEX, handoff lines. |
| **When** | Common case — a known, single, well-anchored edit. | Tricky edits needing **mode judgment** (syntax-mode region rewrite, re-anchoring after a count miss), refusal/escalation (multi-file → plan-agent), or a **first-class audit record + `Saved edit:` handoff** (the exec→edit→eval contract). |

Irreducibly the agent (not a tool or a one-line substrate): mode selection and
re-anchoring, the decision to **refuse** (multi-file, out-of-tree, ambiguous)
and hand back, and serving as the single audited write boundary for the formal
pipeline. Everything mechanical below that is `edit$apply`.

## 5. The edit record as a template

The one place the lightweight argument bites — the audit record. The agent (or
`edit$apply`'s persist step) fills `<…>` and `write-file`s it to
`.brainyard/agents/edit-agent/edits/<ts>-<slug>.md`. The `verify` block — built
before by the frontmatter helper — becomes a flow map written directly:

```markdown
---
slug: <kebab-slug>
agent: edit-agent
created: <ISO-8601>
request: "<verbatim edit request>"
target: <repo-relative path>
mode: <pattern | syntax | new-file>
ok: <true | false>

apply: {replaced: <N>}
verify: {diff_match: true, old_count_after: 0, new_count_after: <N>, lint: "clj-kondo:0", tests: "skipped"}
rollback: "git checkout -- <path>"      # or cp/reverse-diff form; or "Rolled back: <stage>" when ok:false
---

# Edit — <one-line summary>
## Diff
```diff
<unified diff>
```
```

Because `edit$apply` returns exactly `{:ok? :mode :replaced :diff :verify
:rollback}`, the template is a near-mechanical fill from one result map. (If we
want zero hand-authoring, `edit$apply`'s persist step can emit the record itself
via this template; the agent then only relays the path — keeping even the record
on the mechanism side. See §12 Q1.)

## 6. `edit.clj` + base-agent changes

- **Remove** `edit$slug` / `edit$frontmatter` / `edit$write` / `edit$index-append`
  and the YAML emitters; the persist step (inside `edit$apply`, or the agent on
  the inline path) `write-file`s the §5 template.
- **Keep** `edit$read-record`, `edit$find` + their parse helpers (read seams).
- **Keep `edit$apply` and its entire pipeline** — mechanism and gap-closer.
  Export it as a common command so the edit substrate (§4.1) can rely on it being
  bound everywhere.
- **Simplify/keep the auto-persist net** to fill the §5 template.
- **Base-agent wiring (edit substrate):** define a shared
  `edit-substrate-protocol` string (the §4.1 block) alongside the todo/exec
  substrates; add an `:edit-substrate` section to both `coact-system-context`
  and `react-system-context`. No roster change for the *tool* — `edit$apply`,
  `update-file`, `read-file`, `bash` already ride `default-agent-roster`.
- **(Optional, §4.2)** add `:expect-count` + a `:rollback`/backup return to
  `update-file` in `common/tools.clj`.
- edit-agent's own `:agent-tools` keep `update-file`/`write-file` bound (it's the
  writer); only its record-persist helpers drop.

> Substrate note: the todo, exec, and edit substrates are three facets of one
> "work on the repo safely" base protocol. Consider a single combined **"Working
> on the repo"** base section (track with a checklist · execute with evidence ·
> edit with the transaction) rather than three adjacent blocks.

## 7. Instruction & tool-context (sketch)

edit-agent keeps its safety pipeline and hard rules (it's the contract editor);
only PERSIST changes:

```text
PREFERRED PATH — call edit$apply (unchanged); branch on :ok?.
PERSIST — instead of edit$slug/frontmatter/write/index-append, write-file the
  EDIT RECORD template (filled from the edit$apply result map) to
  edits/<ts>-<slug>.md and append one INDEX line. Do NOT build frontmatter via a
  helper.
ANSWER — `Saved edit: <path>` + (`Rollback: <cmd>` | `Rolled back: <stage>`).
```

Tool-context: drop the four record-persist helpers from `### Persistence
helpers`; keep `edit$apply`, `edit$read-record`, `edit$find`; note the record is
now a `write-file` from the template. The edit modes (PATTERN / SYNTAX /
NEW-FILE), the PROBE/VERIFY/ROLLBACK pipeline prose, the allowed-bash allowlist,
and the HARD RULES carry over verbatim from the prior agent (rename only).

## 8. Simplifying the rollback with git (reverse-diff)

The transaction is **scoped to the edit**, not to HEAD: VERIFY compares
pre-APPLY vs post-APPLY bytes, and ROLLBACK restores pre-APPLY bytes, so a file's
*prior* uncommitted changes survive a failed transaction.

`git stash push/pop` is the **wrong** primitive for this and was already demoted
(the legacy `:dirty-ok? :stash` mode): `git stash pop` re-applies stashed dirty
hunks by three-way merge over the now-edited file → conflicts when the edit
overlaps, and a failed pop strands content in `git stash list`. Restoring by
**overwrite** (not merge) is the point.

The clean simplification: **reverse-apply the edit's own diff.** `edit$apply`
already computes the unified diff; that diff *is* the inverse:

```
git apply -R <the-edit-diff>            # reverse exactly the hunks we applied
git apply -R --3way --recount <diff>    # belt-and-braces for whitespace/recount drift
```

This is strictly better than `cp` backups or stash for the modify case:

- **Unifies clean and `:dirty-ok?`** into one mechanism — no branch, no backup
  artifact under `.brainyard/…/backups/`.
- **Transaction-scoped by construction** — touches only the edit's own hunks, so
  other dirty hunks are untouched automatically.
- **No snapshot artifact**; **guaranteed-clean reverse** because we reverse
  immediately against the exact post-APPLY bytes.

New-file rollback stays a plain `rm`. For a whole-file snapshot without the
push/pop hazard, the correct low-level primitive is `git stash create` (builds a
commit object, returns a SHA, doesn't touch the tree or ref-stack) + `git restore
--source=<sha> -- <file>` — object capture + overwrite. Prefer reverse-diff as
primary; keep `stash create` only as a fallback.

Net: ROLLBACK collapses from "branch on clean/dirty/stash + manage a backup file"
to "reverse-apply the diff I already have." Internal to the pipeline; the agent
contract and §5 record schema are unchanged.

## 9. Migration

### 9.1 Lightweight + git changes (within the agent)
- Land the slimmed `edit.clj` (drop record-persist helpers; keep `edit$apply` +
  readers) and the instruction PERSIST swap.
- Switch ROLLBACK to reverse-diff (§8).
- On-disk edit-record schema unchanged → existing records readable; exec/eval
  consumers untouched.
- Install the edit substrate in the base agents behind the same flag as the
  todo/exec substrates.
- (Optional) ship the `update-file` `:expect-count` guard; no data backfill.

### 9.2 What renames, what doesn't (the `update-agent` → `edit-agent` rename)

| Renames | Stays |
| --- | --- |
| Agent: `update-agent` → `edit-agent` | **Base file tools `update-file` / `write-file`** — generic primitives. Do NOT rename. |
| Helpers: `update$apply` / `update$read-record` / `update$find` → `edit$apply` / `edit$read-record` / `edit$find` | Generic CRUD verb `doc$update` (plan/todo) — unrelated. |
| ns `common/update.clj` → `common/edit.clj`; `update-helpers` → `edit-helpers` | `:dirty-ok?` / `:run-tests?` / `:lint-ok-to-fail?` inputs. |
| Directory `.brainyard/agents/update-agent/` → `…/edit-agent/` | `edits/` / `backups/` subdir names. |
| Routing tag value `:via :update-agent` → `:via :edit-agent` | `Saved edit:` / `Rollback:` handoff prefixes. |

### 9.3 Blast radius (measured)
`update-agent` / `update$*` appears **~179 times across 20 source files** —
heaviest in `research_agent` (28), `exec_agent` (20), `eval_agent` (16),
`main_agent` (10), plus the dossier schemas. Two references are *data*, not code:
- **`:via :update-agent`** — persisted in **todo/exec dossier markdown**, read by
  exec routing + eval coverage. New writers emit `:via :edit-agent`; readers
  **normalize both** for one release.
- **`.brainyard/agents/update-agent/`** — existing records; dual-read + copy.

### 9.4 Rename migration — reuse the `.brainyard/plans/`-move playbook
1. Register `edit-agent`; keep **`update-agent` as a name alias** (dispatch +
   resolution) for one release.
2. Register `edit$*`; keep **`update$*` ids as registry aliases** for one
   release (sandbox auto-binding exposes both kebab names).
3. Directory → `.brainyard/agents/edit-agent/`; **dual-read** legacy;
   `bb migrate:edit-agent` copies records across (mirrors `bb migrate:plan-agent`).
4. Routing tag: emit `:via :edit-agent`; **normalize** `:update-agent` (legacy)
   and `:edit-agent` interchangeably in exec routing / eval.
5. ns/file rename `update.clj` → `edit.clj`; update the ~20 referencing files
   (mostly cross-agent dispatch examples + `:via` enums).

> **Sequencing:** the `:via` tag touches the todo/exec/eval contract, so the
> `:via` migration should land **with or after** the todo + exec substrate
> changes (which already revisit `:via` routing), not before — so the normalizer
> lands once. The agent/helper/directory renames are aliased and can land first.

## 10. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Base agents gain easy editing and bypass the transaction with raw `update-file`. | Edit substrate: "tracked source → `edit$apply` only"; optional `:expect-count` guard; exec/eval still require edit records on the contract path. |
| Losing the structured `verify` block some tooling reads. | Keys preserved in the §5 template as a flow map; `edit$read-record` parses it identically. |
| Record drift between `edit$apply` result and hand-written template. | Prefer letting `edit$apply`'s persist step emit the record; the agent only relays the path. |
| Agents skip pre-stating match count and force `:all?`. | `edit$apply` verifies counts and rolls back; substrate reinforces "longer pattern, not `:all?`." |
| Rename breaks a persisted `:via :update-agent` tag. | Reader normalizer accepts both values for one release (§9.4). |
| Stray `update-agent`/`update$` references after rename. | Aliases keep both resolving for a release; grep gate in CI before dropping aliases. |

## 11. Verification

- **`edit$apply` unchanged** — pattern edit with correct count → `:ok? true`,
  diff matches, rollback hint present; record written from the template parses
  via `edit$read-record`.
- **Count guard** — `edit$apply` (and, if shipped, bare `update-file
  :expect-count`) refuses on a count mismatch; no write occurs.
- **Rollback (reverse-diff)** — a lint-regressing edit rolls back via `git apply
  -R`; working tree restored incl. prior dirty hunks; record `ok: false` with the
  failing stage.
- **Edit substrate** — main-agent (with the substrate) makes a verified edit via
  `edit$apply` and keeps the rollback, with zero edit-agent dispatch.
- **Downstream unchanged** — exec/eval read `diff_match`/`ok` from a
  template-written record exactly as before.
- **Contract path** — a multi-file or ambiguous request routes to edit-agent,
  which refuses/escalates with mode judgment.
- **Rename aliases** — `(update-agent {…})` and `update$apply` still resolve for
  the alias-release; `:via :update-agent` and `:via :edit-agent` both route.

## 12. Open questions

1. **Should `edit$apply` own record emission entirely?** Then the agent never
   hand-authors the record — maximally on the mechanism side. Lean yes.
2. **Ship `update-file :expect-count`?** Cheap, high-value floor-raiser; also
   makes the substrate's "pre-state the count" rule enforceable at the tool. Lean
   yes.
3. **One combined "Working on the repo" base section** (track · execute · edit)
   vs. three substrates? Combined reads better and is one cache-stable block.
4. **Does edit-agent stay a full CoAct agent, or become "the `edit$apply` tool +
   a thin refusal/mode-judgment wrapper"?** Most turns are one `edit$apply` call,
   but the agent boundary is also the audit boundary. Revisit after the substrate
   lands and we see how often the subagent is still dispatched.
5. **Enforce "no raw write on tracked source" via a `:agent.tool-use/pre` hook**
   rather than prompt-only? Strongest guarantee; same hook-vs-prompt question as
   the other substrates.

---

## Appendix — the gap, concretely

**Raw `update-file` (unguarded):**

```clojure
(update-file {:path "src/checkout/payment.clj"
              :pattern "validate" :replacement "validate-v2" :all? true})
;; → replaced 11.  Did you mean 11? Does it still compile? Tests pass?
;;   How do you undo it? update-file answers none of these.
```

**The transaction (`edit$apply`) — the gap, as one tool call:**

```clojure
(def res (edit$apply :request "rename validate→validate-v2 in payment.clj"
                     :target "src/checkout/payment.clj"
                     :mode :pattern :pattern "validate" :replacement "validate-v2"
                     :all? true :run-tests? true))
;; res => {:ok? false
;;         :verify {:diff_match true :old_count_after 0 :new_count_after 11
;;                  :lint "clj-kondo:+2" :tests "FAIL"}
;;         :rollback nil}   ; already rolled back: tests failed → workspace restored
```

The second form is what "necessary" means: it caught a regression and undid it.
And because it's a **tool**, any agent has that safety by calling it — the
edit-agent *subagent* is needed only when the edit needs mode judgment, refusal,
or a formal audited handoff.
