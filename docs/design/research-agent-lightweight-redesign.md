# Research-Agent — Lightweight, File-Tool Redesign (revision 3)

> **Status:** Proposal. Applies the [series](./agent-lightweight-redesign-synthesis.md)
> argument to the **orchestrator**. Research-agent is structurally different from
> the six specialists — it doesn't author a plan/edit/verdict; it *composes the
> specialists* and threads a durable research dossier across them. So the
> judgment-vs-mechanism split lands differently, and mostly in research-agent's
> favor: its core work (the move state machine) is already LLM judgment, and it
> already binds `write-file`/`update-file` directly. What's left to fix is a
> handful of **structured-authoring helpers** — and, pleasingly, its acceptance
> tracking turns out to be a checklist, so the **todo substrate applies verbatim**.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/research_agent.clj`,
> `research.clj`. Naming aligns with the adopted `edit-agent` rename (research
> already reads `edit$read-record` / routes `:via :edit-agent`).

---

## 1. Why the current authoring path is error-prone (and why less of it is)

Two honest observations up front:

- Research-agent is **already partly lightweight**. It binds the file tools with
  **no `remove` clause**, and the instruction already tells it to update
  `dossier.md` frontmatter via `update-file` and append `findings.log` via
  `write-file :append`. The orchestration loop — pick a move (EXPLORE / PLAN /
  DECOMPOSE / EXECUTE / EVALUATE / SYNTHESIZE / CLARIFY / FINALIZE / UPDATE),
  dispatch the specialist by kebab name, read its dossier — is pure LLM judgment
  over the **sibling read-helpers**. None of that is brittle; none of it changes.
- The brittleness is concentrated in four **structured-authoring helpers** that
  make the model construct objects:

  1. **`research$bootstrap`** takes `:acceptance [{:id "a1" :text "…" :status
     :open} …]` — a **vector of maps** the model must construct before any work
     begins, plus `:direction [...]`, and writes five files from them.
  2. **`research$append-log`** takes `:pointers {…}` — a structured map flattened
     to one NDJSON line per specialist call.
  3. **`research$update-status`** flips one criterion's `status:` line by
     `:criterion-id` — the *same shape* as todo's `:item-idx :item-done` flip
     (a structured micro-tool for a one-line status edit).
  4. **`research$write-verdict`** takes `:status :narrative` and emits the
     `---` frontmatter + `## Verdict` heading, deriving `acceptance_outcome` from
     the dossier.

And, exactly as the other agents, this path leaks: the instruction is littered
with "skipping step 1 will cause step 2 to REJECT," "the most common reason
finalize fails," and a verbatim-fence workaround for authoring the verdict body
into a string — all symptoms of routing markdown authoring through structured
calls.

## 2. Thesis

Research-agent already does the hard part right: **orchestration is judgment**,
performed by reading sibling dossiers and choosing the next move. Keep that
untouched. Then:

1. **Author the dossier files as markdown from templates** — bootstrap writes
   `purpose.md` / `acceptance.md` / `direction.md` / `dossier.md` directly; the
   verdict from a template. Retire the structured construction.
2. **Acceptance criteria are a checklist — adopt the todo substrate.** The
   criteria-with-status list is structurally a todo list. Track it as a markdown
   checklist, flip a criterion's status with `update-file` (index-free, by stable
   id), and parse it back with one read seam — *the same substrate the todo
   redesign installs in the base agents*.
3. **Keep the mechanism**: the deterministic resume key (`research$id`), the
   resume/status **parser** (read seam), the verdict **outcome derivation +
   `:achieved` guard**, and — most importantly — **all five sibling
   read-helpers**.

In one line, the same as the series: **authoring becomes prose; reading stays
typed** — and for the orchestrator, "reading" is the whole job, so the typed
read seams are sacrosanct.

## 3. Design principles

1. **Orchestration is judgment, not mechanism — untouched.** The move state
   machine and specialist dispatch stay exactly as they are.
2. **Reads stay deterministic — research is the proof case, more than eval.**
   Research-agent reads **five** sibling artifact types (`plan$`, `todo$`,
   `exec$`, `eval$read-dossier`, `edit$read-record`) *plus* its own dossier
   state. Hand-parsing those to drive move decisions would be the dominant error
   source. Every read seam stays.
3. **Acceptance is a checklist (substrate reuse).** Criteria + status =
   a todo-shaped list; manage it with the todo substrate's checklist + index-free
   flip + parse-back read seam. No bespoke `research$update-status`.
4. **Authoring is templated markdown.** The five dossier files are written
   directly from templates; no vector-of-maps construction, no frontmatter
   helper.
5. **The contract is the template + the parser.** The machine-readable bits
   (criterion ids/statuses, artifact pointers, verdict outcome) survive as
   *frontmatter/checklist the model writes* and a *parser that reads them back*,
   not as a constructor.
6. **Degrade gracefully.** A malformed dossier line is caught by the lenient
   parser; resume falls back to re-reading the sibling dossiers via their typed
   readers; the slimmed auto-persist behavior still writes a minimal verdict.

## 4. What stays, what goes

| Concern | Today | Redesign |
| --- | --- | --- |
| Pick the next move (state machine) | reasoning over sibling dossiers | **Unchanged** — inherent LLM judgment. |
| Read sibling dossiers (drive moves) | `plan$/todo$/exec$/eval$read-dossier`, `edit$read-record` | **Keep all** — the load-bearing read seams. |
| Deterministic dossier id (resume key) | `research$id` | **Keep** — determinism is the resume contract. |
| Resume probe | `research$resume?` | **Keep** — read seam (parse dossier frontmatter + acceptance statuses). |
| Bootstrap 5 files from `:acceptance [{…}]` | `research$bootstrap` (vector-of-maps) | **Removed.** `write-file` `purpose.md`/`direction.md` + the acceptance **checklist** from templates (`bash mkdir -p` for the dir). |
| Flip a criterion status | `research$update-status :criterion-id … :status …` | `update-file` the acceptance checklist line (index-free, by id); parse-back read seam reconciles. |
| Per-call findings | `research$append-log :pointers {…}` (NDJSON) | `write-file :append` a findings line (mostly the `Saved X:` paths the specialist already emitted); keep a *thin* append helper only if NDJSON resume is valued (§5.3). |
| Update artifact pointers | `update-file` on dossier frontmatter (already!) | **Unchanged** — already file-inherent. |
| Write the verdict | `research$write-verdict` (emits frontmatter + derives outcome) | `write-file` verdict.md from a template; **keep** a tiny read-side `research$verdict-outcome` that derives `acceptance_outcome` + enforces the `:achieved` guard (§6). |
| INDEX prepend | `research$index-append` | `write-file :append` (or keep thin). |

Net: the structured-construction helpers retire (`bootstrap`'s acceptance
vector, `update-status`, the frontmatter-emitting half of `write-verdict`,
optionally `append-log`/`index-append`); the deterministic seams stay
(`research$id`, `research$resume?`/parser, the verdict-outcome derivation+guard,
all sibling readers). File tools are already bound — no roster change for the
writes.

## 5. Acceptance criteria are a checklist (substrate reuse)

Today acceptance lives as `:acceptance [{:id :text :status}]` constructed for
`research$bootstrap` and mutated by `research$update-status`. That is a todo list
wearing a different schema. Adopt the **todo substrate** (the base-agent
checklist protocol) verbatim, with a status token because research has five
states (not binary):

```markdown
# Acceptance — <research id>
- [ ] a1 (open) — <concrete testable criterion>
- [x] a2 (satisfied) — <criterion>; evidence: eval verdict <path>
- [~] a3 (partial) — <criterion>; gap: <one line>
- [-] a4 (descoped) — <criterion>; user-confirmed drop, iter N
- [!] a5 (contradicted) — <criterion>; finding <path> contradicts
```

- **Author** (bootstrap): `write-file` this checklist directly — no map vector.
- **Flip status**: `update-file` on the criterion line, matched by its **stable
  id** `aN` (ids don't drift like todo's ordinals, so this is even safer than the
  todo case) — e.g. `"- [ ] a1 (open)"` → `"- [x] a1 (satisfied)"`.
- **Parse back**: one read seam (`research$resume?` / a `research$acceptance`
  reader) parses the `(status)` tokens → `{a1 :open, a2 :satisfied, …}` for
  resume *and* for the verdict-outcome derivation.

This is the cleanest possible outcome: research-agent's acceptance tracking
**becomes an instance of the todo substrate**, so it inherits the same
index-free editing the rest of the series adopts, and the same base-agent
protocol — one fewer bespoke mechanism in the codebase.

### 5.3 findings.log — keep thin or inline?

`findings.log` is append-only NDJSON, read on resume (last ~50 lines). Two paths:

- **Inline** (preferred for lightness): `write-file :append` a one-line entry the
  model writes — and since `:pointers` is mostly the `Saved X:` paths the
  specialist *just emitted in its answer*, the model already has them verbatim.
- **Thin helper** (if structured resume parsing is valued): keep a minimal
  `research$append-log` that only does append + flatten — it's mechanism, not
  construction, so it's defensible. Decide by whether resume actually parses the
  log structurally or just shows it to the model. Lean inline; the sibling
  dossiers (read via the typed readers) are the authoritative resume state, not
  the log.

## 6. The read/derive seams worth keeping

Research-agent is the heaviest *reader* in the stack — its entire job is reading
to decide. These all stay (mechanism):

- **Sibling readers** — `plan$read-dossier`, `todo$read-dossier`,
  `exec$read-dossier`, `eval$read-dossier`, `edit$read-record`. The move
  decisions are data-driven off these (e.g. eval's `score.recommendations` names
  the next move). Parsing them by hand would dwarf the orchestration in error
  rate. **Untouched.**
- **`research$id`** — deterministic kebab id; the resume key. Keep.
- **`research$resume?` / acceptance parser** — reads dossier frontmatter +
  acceptance checklist statuses. Keep (now parses the §5 checklist).
- **`research$verdict-outcome`** (new, carved from `write-verdict`) — a *read +
  derive*: parse the acceptance statuses, compute `acceptance_outcome`, and
  **enforce the `:achieved` guard** (refuse `:achieved` unless every criterion is
  `:satisfied`/`:descoped`). This is the one genuinely valuable mechanical bit of
  `write-verdict` — the validation that stops the model declaring victory
  prematurely. Keep it as a read-side validator the model calls *before*
  `write-file`-ing verdict.md.

The orchestration narrative, the dossier prose, and the verdict body are all
markdown the model writes — that half goes file-inherent.

## 7. Orchestration is LLM-inherent (nothing to retire there)

The move state machine (§ in the current instruction) is the model reasoning
over dossier state and dispatching specialists — exactly what an orchestrator
LLM should do, and exactly the kind of judgment the series *keeps*. The
redesign does **not** touch:

- the nine moves and their heuristics,
- direct kebab-case specialist dispatch,
- the data-driven "read eval's recommendations to pick the next move" logic,
- the dossier-threading discipline (pass the sibling `Saved dossier:` path in
  `:agent-context`).

This mirrors eval-agent's SCORE and explore-agent's routing: the *deciding* is
inherent capability; only the *persisting* was over-tooled.

## 8. Two kinds of research (and whether research-agent could be a substrate)

The series' recurring question: is there a "working" form that the **root agent**
does inline, vs. a "contract" form the subagent owns?

- **(A) Casual multi-step work** — the root/main agent calls a couple of
  specialists and tracks loosely. This already happens without research-agent;
  it's just the root agent dispatching `(explore-agent …)` / `(plan-agent …)` and
  reading the `Saved …:` lines. No durable thread.
- **(B) A durable, resumable, acceptance-tracked research thread** — bootstrap a
  dossier, freeze acceptance criteria, thread purpose/direction across many
  specialist calls, resume by `@<id>`, finalize with a verdict. **This is what
  justifies a dedicated agent**: the durable dossier + resumability + frozen
  acceptance is real machinery, not ceremony.

So unlike todo/exec/edit, research-agent's value **is** the durable dossier —
there's less to push down to a base substrate. What *can* be shared down is the
**acceptance checklist** (§5, already the todo substrate) and the **dossier-read
discipline** (the sibling readers, already common). The orchestration-with-a-
durable-thread stays the dedicated agent. (If a "research substrate" is ever
wanted — a root agent threading a lightweight purpose/acceptance dossier without
the full state machine — it would be the todo substrate + a `purpose.md`/
`acceptance.md` pair, nothing more. Defer until asked.)

## 9. Instruction & tool-context (sketch)

Keep the six-specialist roster, the move state machine, the dossier-threading
and handoff discipline, and the hard rules. Change only the persistence steps:

```text
TURN 1 — BOOTSTRAP: bash mkdir -p the dossier dir, then write-file purpose.md,
  direction.md, dossier.md, and the ACCEPTANCE CHECKLIST (acceptance.md) from
  templates. (research$id for the id; research$resume? to choose bootstrap vs
  resume.) No :acceptance vector-of-maps.
AFTER EACH SPECIALIST CALL: append a findings line (write-file :append);
  update-file the dossier frontmatter artifact pointers (unchanged); when a
  criterion's status changes, update-file its line in the acceptance checklist
  (index-free, by id aN).
FINALIZE: call research$verdict-outcome to derive the outcome + enforce the
  :achieved guard; then write-file verdict.md from the VERDICT TEMPLATE; append
  INDEX. ANSWER ends with `Saved research dossier: <dir>`.
```

Tool-context: drop `research$bootstrap` / `research$update-status` /
`research$write-verdict` (and optionally `append-log` / `index-append`) from the
helper list; keep `research$id`, `research$resume?`, the new
`research$verdict-outcome` validator, and **all** sibling read-helpers; add the
acceptance-checklist template + the index-free status-flip note (pointing at the
shared todo substrate).

## 10. `research.clj` changes

- **Remove** the structured-construction paths: `research$bootstrap`'s
  acceptance/direction object handling, `research$update-status`, and
  `research$write-verdict`'s frontmatter emission. Keep `bash mkdir` + template
  `write-file`s in the instruction instead.
- **Add** `research$verdict-outcome` — read the acceptance checklist, derive
  `acceptance_outcome`, return `{:outcome … :achieved-ok? bool :blockers […]}`.
  This preserves the only load-bearing guard from `write-verdict` as a pure
  read-side validator.
- **Keep** `research$id`, `research$resume?` (extended to parse the §5 checklist),
  and the cherry-picked sibling readers bound in the agent.
- **Optionally keep** a thin `research$append-log` (append + flatten only) and
  `research$index-append` — both mechanism, both replaceable by `write-file
  :append`. Lean inline (§5.3).
- No roster change: `write-file`/`update-file`/`read-file` already bound (no
  `remove` clause). Acceptance editing rides the shared **todo substrate** once
  that lands.

## 11. Migration

- Land the slimmed `research.clj` + the instruction persistence swap.
- **Dossier layout unchanged** except acceptance moves from a frontmatter
  vector-of-maps to the §5 checklist file. Add a dual-read in `research$resume?`
  so it parses both the legacy frontmatter acceptance and the new checklist for
  one release; `bb migrate:research-agent` optional (research dossiers are
  per-thread + resumable, so prefer dual-read + let old threads finish on the old
  shape).
- Depends on the **todo substrate** landing (§5 reuses it); sequence after the
  todo redesign, alongside or after exec (which also depends on it).
- Verdict/INDEX schema keys unchanged → any consumer of the research INDEX or
  verdict is unaffected.

## 12. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Hand-authored acceptance checklist malformed → resume/verdict misread statuses. | Stable-id + status-token format is simple and single-line; the parser is lenient; `research$verdict-outcome` flags an unparseable/`:open` criterion before allowing `:achieved`. |
| Model declares `:achieved` prematurely. | The `:achieved` guard is **preserved** as `research$verdict-outcome` — same protection as today, now read-side. |
| Losing structured findings.log resume. | Sibling dossiers (typed readers) are the authoritative resume state; findings.log is a convenience. Keep it thin if structured parse is needed (§5.3). |
| Status flip edits the wrong criterion. | Match on the **stable id** `aN` (not a drifting ordinal), so it's safer than todo's `:item-idx`; `update-file` returns a diff. |
| Orchestration regresses. | It can't — the state machine + sibling readers are untouched; only persistence changes. |

## 13. Verification

- **Bootstrap** — iter 1 writes the dir + four markdown files incl. the
  acceptance checklist; `research$resume?` parses statuses back.
- **Status flip (index-free)** — `update-file` flips `a2 (open)` → `a2
  (satisfied)`; the parser reflects it; other criteria untouched.
- **Verdict guard** — `research$verdict-outcome` refuses `:achieved` while any
  criterion is `:open`/`:partial`; accepts when all `:satisfied`/`:descoped`.
- **Resume** — re-invoke `@<id>`: reads checklist + last findings; picks up
  without re-bootstrapping or re-exploring artifacts.
- **Sibling reads unchanged** — research still drives moves off
  `eval$read-dossier`'s `score.recommendations` etc.
- **Orchestration unchanged** — a full EXPLORE→PLAN→DECOMPOSE→EXECUTE→EVALUATE→
  FINALIZE arc threads dossiers exactly as before.
- **Finalize** — writes verdict.md from the template; `Saved research dossier:`
  line emitted only when the verdict actually wrote.

## 14. Open questions

1. **Keep a thin `research$append-log` or inline findings?** Hinges on whether
   resume parses the log structurally. Lean inline; sibling dossiers are the real
   resume state.
2. **Fold acceptance fully into the todo substrate, or keep a research-flavored
   variant?** The 5-status token vs. binary checkbox is the only delta — a small
   superset. Lean: extend the shared checklist reader to accept an optional
   `(status)` token so research and todo share one parser.
3. **`research$verdict-outcome` as a pre-write validator vs. a write-time guard.**
   Read-side validator is cleaner (the model writes verdict.md itself); keep the
   guard's *logic*, drop its *write*.
4. **Is a lightweight "research substrate" worth it** for the root agent (durable
   purpose/acceptance thread without the full state machine)? Defer — it's just
   the todo substrate + two files; revisit if the casual case recurs.

---

## Appendix — before/after, two moments

**Before — bootstrap + a status flip + finalize (structured helpers):**

```clojure
(research$bootstrap :id rid
  :purpose "…"
  :acceptance [{:id "a1" :text "…" :status :open} {:id "a2" :text "…" :status :open}]
  :direction ["…" "…"])
;; … later …
(research$update-status :id rid :criterion-id "a1" :status :satisfied)
;; … finalize …
(research$write-verdict :id rid :status :achieved :narrative "<body>")
(research$index-append :id rid :status :achieved :one-line "…")
```

**After — markdown templates + index-free checklist flip + read-side guard:**

```clojure
(def rid (:slug (research$id :question "…")))            ; deterministic resume key (kept)
(bash {:command (str "mkdir -p .brainyard/agents/research-agent/" rid "/artifacts")})
(write-file {:path (str ".brainyard/agents/research-agent/" rid "/acceptance.md")
             :content "# Acceptance — …\n- [ ] a1 (open) — …\n- [ ] a2 (open) — …\n"})
;; … purpose.md / direction.md / dossier.md likewise from templates …

;; flip a criterion — index-free, by stable id, via the shared checklist substrate
(update-file {:path (str ".brainyard/agents/research-agent/" rid "/acceptance.md")
              :pattern "- [ ] a1 (open)" :replacement "- [x] a1 (satisfied)"})

;; finalize — keep the guard, write the markdown
(def vo (research$verdict-outcome :id rid))              ; derive outcome + :achieved guard (kept)
;; (if (:achieved-ok? vo) … else fix the dossier, don't downgrade)
(write-file {:path (str ".brainyard/agents/research-agent/" rid "/verdict.md")
             :content "<filled VERDICT TEMPLATE>"})
(write-file {:path ".brainyard/agents/research-agent/INDEX.md" :append true
             :content "- 2026-… [<id>](<id>/) — ACHIEVED · …\n"})
```

The model never constructs an acceptance vector-of-maps or hands a narrative to
a frontmatter emitter — it writes the dossier markdown it's fluent at, flips
criteria by stable id through the shared checklist substrate, and keeps exactly
the two things a machine does better: **reading the sibling dossiers** that drive
its moves, and **deriving + guarding the verdict outcome**.
