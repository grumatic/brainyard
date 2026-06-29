# Explore-Agent — Lightweight File-Tool Redesign + Reuse-via-References (revision 2)

> **Status:** Proposal. Companion to
> [`plan-agent-lightweight-redesign.md`](./plan-agent-lightweight-redesign.md);
> applies the same "write markdown directly, retire the construction
> helpers" treatment to explore-agent, and adds a second pillar the
> plan-agent doc didn't need: **reuse before re-explore** — every
> exploration result is a *dossier* that references prior related dossiers, and
> agents consult the corpus before launching a fresh probe so the same ground
> isn't explored twice.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/explore_agent.clj`
> (instruction + roster) and `explore.clj` (retire the write-side helpers; keep
> the read + discovery seams; add a lineage field).
> **Supersedes the authoring path in** [`explore-agent-design.md`](./explore-agent-design.md);
> the surfaces/routing design there is unchanged.

---

## 1. Why the current authoring path is error-prone

Explore-agent does its *routing* in fluent prose but its *persisting* through
the same structured micro-tool chain that plan-agent uses. A single non-trivial
turn asks the LLM to:

1. Call `explore$slug` to derive a slug.
2. Call `explore$frontmatter` with `:question`, `:slug`, a `:surfaces` vector,
   and an `:entities` **map of four vectors** (`:files :urls :mcp_tools
   :skills`) plus a `:summary` that gets folded into a YAML `>` scalar — i.e.
   construct a precisely-keyed nested object the helper renders into YAML.
3. Concatenate `(str fm body)` and call `explore$write`.
4. Call `explore$index-append`, re-passing `:surfaces` and `:summary` a second
   time so the INDEX line and the frontmatter agree.

Every call has its own schema, and the same values (`surfaces`, `summary`,
`slug`) must be threaded *identically* across calls. When the model emits
`entities` as a flat vector instead of `{:files [...] …}`, forgets a surface in
one of the two places it's required, or fumbles the folded-scalar summary, a
call errors and the turn burns an iteration.

The proof it's too brittle is in the file: `explore.clj` ships an
`:agent.ask/post` **auto-persist hook** whose docstring states plainly that
*"Sonnet+ follows [the checklist] reliably; haiku and other smaller models
often skip it"* — so it reconstructs `entities`/`surfaces` from the answer with
regex and persists after the fact. We built a regex-scraping safety net because
the primary, structured path leaks. Meanwhile the thing the model is reliably
good at — emitting a markdown file with a YAML frontmatter block — is what we
route around.

## 2. Thesis

Two changes:

1. **Authoring goes LLM-inherent.** The result *is* a markdown document; let
   the model `write-file` it from a fixed template, not construct it through
   `explore$slug → explore$frontmatter → explore$write → explore$index-append`.
2. **Exploration becomes cumulative, not amnesiac.** Each result is a *dossier*
   that (a) is found before a new probe starts and (b) links the prior dossiers
   it builds on. Agents — explore-agent itself and the *root agents* that
   dispatch it — check the corpus first and reuse, instead of re-exploring the
   same files/servers/skills every turn.

In one line: **writes become prose; reads and discovery stay typed; and the
corpus remembers, so nobody re-explores what's already on disk.**

## 3. Design principles

1. **Writes are LLM-inherent.** Result body + frontmatter authored with
   `write-file` from the §5 template. No `explore$frontmatter` / `explore$write`
   / `explore$index-append`.
2. **Reads and discovery stay deterministic.** `explore$read-frontmatter` (cheap
   metadata read) and `explore$find` (corpus search) are *read-only* — they
   carry none of the write-side brittleness and are exactly where a machine
   beats the model. **Keep both.**
3. **Reuse before re-explore (NEW).** Iteration 0 of every run is a mandatory
   `explore$find` prior-art check. A sufficiently fresh, on-topic dossier
   short-circuits the probe.
4. **Dossiers reference dossiers (NEW).** The result frontmatter carries a
   `related:` list of prior dossier paths covering overlapping
   entities/slug, so the corpus is a navigable lineage rather than a flat pile.
5. **Freshness is explicit (NEW).** Filesystem findings can be invalidated by
   file mtime / git; web and MCP findings are time-sensitive. The dossier
   records what it depends on so a consumer can judge staleness instead of
   trusting blindly.
6. **The contract is the template, not a schema object.** Frontmatter keys are
   unchanged from `explore-agent-design.md` §5.2 (plus `related`), enforced by
   showing the template and validating on read — not by a constructor.
7. **Degrade gracefully.** A keyword typo can't fail a write (markdown has no
   schema). Malformed frontmatter is caught by the lenient `parse-flat-yaml` on
   read, and a slimmed safety-net hook still backstops a skipped dossier.

## 4. What stays, what goes

| Concern | Today | Redesign |
| --- | --- | --- |
| Slug | `explore$slug` | LLM derives a kebab slug inline; timestamp filename prefix handles collision. |
| Frontmatter build | `explore$frontmatter` (nested entities map, folded scalar) | **Removed.** LLM writes frontmatter directly from the §5 template. |
| Result write | `explore$write` | `write-file` (`.brainyard/` auto-allowed, handles mkdir). |
| INDEX prepend | `explore$index-append` | `write-file :append true` one line (§8), or keep a tiny `explore$index` reader. |
| Cheap metadata read | `explore$read-frontmatter` | **Keep** — the read seam for downstream + lineage. |
| Corpus search | `explore$find` | **Keep and promote** — now the mandatory iteration-0 prior-art gate (§7). |
| Auto-persist net | `explore-auto-persist` (rebuilds via helpers) | **Keep, simplified** — fills the §5 template and `spit`s one file. |
| Lineage / references | — | **NEW** `related:` frontmatter field + a `## Builds on` body section (§7.2). |

Net: three write-side helpers retire (`explore$slug`, `explore$frontmatter`,
`explore$write`); two read/discovery seams stay and get more central
(`explore$read-frontmatter`, `explore$find`); `explore$index-append` becomes an
optional reader. `write-file`/`read-file`/`update-file` are already bound on
explore-agent (it uses them for filesystem exploration), so no roster change is
needed to author directly — only the *instruction* changes.

## 5. The result-dossier as a template

The instruction carries this verbatim; the model fills the `<…>` slots and
`write-file`s it to `results/<yyyyMMdd-HHmmss>-<slug>.md`. Keys match
`explore-agent-design.md` §5.2 so downstream parsers are unaffected, plus the
new `related:` lineage field.

```markdown
---
slug: <kebab-slug>
question: "<verbatim question, or first 200 chars>"
created: <ISO-8601, e.g. 2026-06-29T14:03:11Z>
agent: explore-agent
surfaces: [<filesystem, web, mcp, skills — those actually used>]
entities:
  files: [<repo-relative paths cited>]
  urls: [<URLs cited>]
  mcp_tools: [<server:tool entries called>]
  skills: [<skill names read>]
related: [<prior dossier paths this builds on — from the iteration-0 find; [] if none>]
freshness: <static | volatile>   # static = filesystem/code; volatile = web/MCP/time-sensitive
summary: >
  <one-paragraph distilled answer, folded to one line on read>
---

# <Title>

## What was found
<the answer, with citations>

## Where
<file:path:line · url · mcp:server:tool · skill:backend:name>

## Builds on
<bullet links to related: dossiers, one line each on what was reused vs. newly found.
 "None — first exploration of this area." if related is empty.>

## Caveats / freshness
<what could go stale: named files (static) or "captured <date>; re-check if older
 than N days" (volatile)>
```

Why this is safe to hand-author: the `surfaces`/`entities` flow vectors are one
line each (the existing `parse-flow-vector` already accepts them quoted or
bare), the keys are fixed and shown so the model fills blanks rather than
inventing structure, and `related`/`freshness` are simple scalars/lists.

## 6. The seams worth keeping — read & discovery

- **`explore$read-frontmatter`** — reads only the leading `---/---` block,
  returns a parsed map. Read-only; the cheap-routing contract for downstream
  agents and for resolving `related:` links. **Keep as-is.**
- **`explore$find`** — substring search over the INDEX (slug+summary+surfaces)
  with a per-file frontmatter fallback that also matches on `question`. This is
  the engine of reuse (§7). **Keep, and make it the iteration-0 gate.**

Both are deterministic readers, so they sidestep the write-side brittleness
this redesign targets. Keeping them identical means **no downstream agent
changes** — the redesign is contained in explore-agent's authoring path plus
the new reuse discipline.

## 7. Reuse via references — don't re-explore the same things

This is the second pillar and the part the redesign adds over plan-agent. The
goal: a `root agent` (plan-agent, exec-agent, main/dispatcher) that needs to
"go look at X" should land on an existing dossier when X was already explored
recently, and explore-agent itself should never redo a probe it already has on
disk.

### 7.1 Iteration-0 prior-art check (mandatory)

Every explore run starts by searching the corpus, before any surface probe:

```clojure
(def prior (explore$find :query "<key nouns from the question>"))
```

Decision rule (in the instruction):

- **Hit, fresh, on-topic** → do NOT re-explore. Read the prior dossier
  (`explore$read-frontmatter`, or full body if needed), answer from it, and
  emit its path as the `Saved exploration:` line with a `Reused:` note. No new
  file written (or a thin pointer dossier — see 7.5).
- **Hit, but stale or partial** → read it, probe ONLY the gap, and write a new
  dossier whose `related:` lists the prior one (§7.2). Don't re-walk what the
  prior dossier already covered.
- **No hit** → full exploration; `related: []`.

"Fresh" is judged via §7.4. This single gate is what converts the corpus from a
write-only log into a reuse cache.

### 7.2 Dossiers reference dossiers (lineage)

When a run builds on prior dossiers, it records them:

- Frontmatter `related: [<paths>]` — machine-readable lineage.
- A `## Builds on` body section — one line per prior dossier saying what was
  reused vs. newly discovered.

This makes the corpus navigable: from any dossier a reader (human or agent) can
walk back to the explorations it stands on, and `explore$find` surfaces the
whole cluster for a topic instead of one isolated file. It also keeps new
dossiers *small* — they cite prior coverage rather than re-stating it.

### 7.3 Reuse at the root-agent boundary

The bigger win is letting the *dispatching* agent avoid even spawning
explore-agent. Two mechanisms, no new infrastructure:

1. **Pre-dispatch check.** Before a root agent calls `(explore-agent {…})`, it
   runs `(explore$find :query "<topic>")`. A fresh on-topic hit means it passes
   that dossier path straight into its own `:agent-context` and skips the
   explore call entirely. plan-agent's pre-flight C3 ("EXPLORED?") already looks
   for a `Saved exploration:` path — extend it to consult `explore$find` when
   the context doesn't carry one, so prior explorations are found, not ignored.
2. **Reused handoff line.** When explore-agent short-circuits (7.1), its answer
   still ends with `Saved exploration: <existing path>` so the root agent's
   normal grep-the-handoff flow works unchanged — it can't tell (and needn't)
   whether the dossier was freshly written or reused.

So reuse compounds: explore-agent won't redo a probe, and a root agent won't
even dispatch one when the corpus already answers.

### 7.4 Freshness policy

Reuse is only safe if staleness is judged, not assumed. The `freshness:` field
encodes the dependency class:

- **`static`** (filesystem/code/docs): valid until the cited files change. A
  consumer can cheaply check `git diff --quiet <ref> -- <entities.files>` or
  compare mtimes; unchanged ⇒ reuse freely. Recommended default reuse window:
  generous (days–weeks), gated on the files being untouched.
- **`volatile`** (web / MCP / "what's the state right now"): the answer reflects
  a moment. Record `created`; the instruction's reuse rule treats volatile
  dossiers older than a short window (default 24h, tunable via
  `agent-runtime$config :key "explore-reuse-volatile-hours"`) as stale → re-probe.

The point isn't a perfect cache-invalidation engine; it's giving the reuse
decision enough signal to avoid both blind re-exploration and blindly trusting
a week-old "is the Box server healthy?" answer.

### 7.5 Pointer dossiers (optional)

When a run is a pure reuse (7.1 first case), writing a *new* full dossier is
wasteful, but a tiny pointer dossier keeps the lineage and INDEX honest:

```markdown
---
slug: <same-topic-slug>
created: <now>
agent: explore-agent
surfaces: []
related: [<the reused dossier path>]
freshness: static
summary: > Reused prior exploration <slug> (<date>); no re-probe needed.
---
# Reused: <title>
See `related:` — the prior dossier still answers this; nothing changed.
```

Recommendation: make pointer dossiers opt-in (default off) to avoid INDEX
noise; default behavior is to just re-emit the existing path on the
`Saved exploration:` line.

## 8. INDEX.md

Same options as the plan-agent doc — pick the cheap one:

1. **Append-only** (`write-file :append true`): newest-last; re-sort on read.
   Zero extra reads. *Recommended for v1.*
2. **Read-modify-write prepend**: preserves newest-first at one extra read/turn.
3. **Derive on demand**: drop the persisted INDEX; `explore$find`/`ls -t`
   reconstructs it.

Note `explore$find` is INDEX-first with a per-file fallback, so even an
append-only (unsorted) INDEX keeps discovery working; the fallback scan already
sorts newest-first by filename. Line format unchanged.

## 9. New instruction & tool-context (sketch)

Keep the four-surface routing, the hard rules, and the handoff discipline. Swap
the PERSISTENCE section and prepend the reuse gate:

```text
────────────────────────────────────────────────────────────────────────────
STEP 0 — REUSE CHECK (always first)
────────────────────────────────────────────────────────────────────────────
Before probing any surface, search the corpus:
    (explore$find :query "<key nouns from the question>")
- Fresh, on-topic hit → DON'T re-explore. Read it (explore$read-frontmatter,
  or full body), answer from it, and end with:
      Reused: <slug> (<created date>)
      Saved exploration: <that dossier's path>
- Stale/partial hit → read it, probe ONLY the gap, write a new dossier whose
  related: lists the prior path.
- No hit → full exploration; related: [].
Judge "fresh" by the dossier's freshness: field — static dossiers are valid
while their cited files are unchanged; volatile (web/mcp) dossiers go stale in
~24h.

────────────────────────────────────────────────────────────────────────────
PERSISTENCE — write the dossier as markdown (one write-file)
────────────────────────────────────────────────────────────────────────────
Fill the RESULT TEMPLATE (frontmatter + ## What was found / ## Where /
## Builds on / ## Caveats) and write-file it to
    .brainyard/agents/explore-agent/results/<yyyyMMdd-HHmmss>-<slug>.md
Then append one INDEX line (write-file :append true).
Do NOT construct entity maps or call frontmatter helpers — WRITE THE MARKDOWN.
Set related: from STEP 0. Set freshness: static (filesystem) or volatile
(web/mcp).
```

Tool-context: the `## Persistence helpers (explore$* …)` block collapses to —

```text
## Persistence — write markdown directly
- write-file / read-file / update-file are bound; .brainyard/ and /tmp are
  auto-allowed; write-file creates parent dirs.
- Author the dossier as markdown from the RESULT TEMPLATE in the instruction.
  No frontmatter/slug/write construction tools.
- explore$find  — READ-ONLY corpus search. ALWAYS call first (STEP 0) to reuse
  prior work. Returns {:matches [{:path :slug :summary :surfaces :created}…]}.
- explore$read-frontmatter — READ-ONLY cheap metadata read of one dossier
  (used to judge freshness, resolve related: links, and by downstream agents).
```

## 10. `explore.clj` changes

- **Remove** `explore$slug`, `explore$frontmatter`, `explore$write`, and the
  YAML emission helpers (`yaml-string`, `yaml-flow-vector`, `format-summary`,
  `build-frontmatter*`, `final-slug-with-suffix`/`existing-slugs-for`). Drop
  them from `explore-helpers` (or shrink it to the readers).
- **Keep** `explore$read-frontmatter` and `explore$find` plus their parse
  helpers (`read-frontmatter-lines`, `parse-flat-yaml`, `parse-flow-vector`,
  `parse-index-line`). Extend `parse-flat-yaml` to surface `related` and
  `freshness` (it already handles flat scalars + flow vectors, so `related`
  parses for free; just document it in the `:output-schema`).
- **Decide on** `explore$index-append`: demote to optional (append-only INDEX
  needs no helper) or keep as a thin reader for derive-on-demand.
- **Simplify the auto-persist hook.** `explore-auto-persist` currently rebuilds
  via `explore$frontmatter`/`explore$write`/`explore$index-append`. Rewrite it
  to fill the §5 template string and `spit` it. Keep the regex entity/surface
  detection (`detect-entities`, `detect-surfaces`, `one-line-summary`) — that
  inference is still useful — but the *write* is now the same one-file path the
  happy path uses, so the two can't diverge.
- **Optionally add** a `explore$reuse?` convenience reader: given a question +
  a candidate dossier, returns `{:reuse? bool :reason …}` applying the §7.4
  freshness rule (git/mtime check for static, age check for volatile). Pure
  read; keeps the freshness logic in one tested place rather than in the prompt.

No change to `explore_agent.clj`'s roster is required for authoring (file tools
already bound); the edits are to `instruction` (STEP 0 + PERSISTENCE) and
`tool-context`. If `explore-helpers` shrinks, update the `concat` accordingly.

## 11. Migration

Smaller than a data migration — the on-disk dossier schema is a superset of
today's (adds `related`, `freshness`), so existing results stay readable and
downstream agents are untouched.

1. Land the new `explore_agent.clj` instruction/tool-context and the slimmed
   `explore.clj`.
2. Remove the three write-side helpers + their tests; keep/retarget the reader
   + find tests; add reuse-gate and lineage tests (§13).
3. Backfill is optional: old dossiers simply have no `related`/`freshness`
   (parsers treat missing keys as absent). A one-shot `bb explore:backfill`
   could stamp `freshness: static` on existing filesystem dossiers, but it's not
   required for correctness.
4. Update `explore-agent-design.md`'s as-built banner to point here.

## 12. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Hand-authored frontmatter malformed. | `parse-flat-yaml` is lenient (flat scalars, flow vectors, skips unknown lines). Keep `related`/`surfaces` as single-line flow vectors. Add a read-time validator flagging a dossier missing `slug`/`summary`. |
| Reuse returns a stale answer (esp. web/MCP). | `freshness:` + the §7.4 rule: volatile dossiers expire fast; static dossiers gated on cited files being unchanged (git/mtime). When in doubt, re-probe the gap, don't reuse blindly. |
| Over-eager reuse hides a changed codebase. | Static reuse checks `git diff --quiet -- <entities.files>`; any change ⇒ treat as stale. The `explore$reuse?` reader centralizes this so it's tested, not vibes. |
| `explore$find` misses a prior dossier (keyword mismatch). | INDEX-first + per-file `question` fallback already broadens recall; instruct the model to query on key *nouns/entities*, not the full sentence. Worst case is a redundant explore — degrades to today's behavior, never wrong. |
| Lineage links rot (referenced dossier archived/deleted). | `related:` holds repo-relative paths; a missing target is a soft warning on read, not an error. Lineage is advisory. |
| Losing structured `entities` some tooling reads. | Keys preserved in the template; flow vectors parse identically. Auto-persist still regex-detects entities as a fallback. |

## 13. Verification

Keep the behavioral/routing tests; replace helper-roundtrip with template +
reuse tests.

- **Authoring** — non-trivial run writes exactly one dossier; frontmatter
  parses; `surfaces`/`entities` populated; body has the 4 sections.
- **Reuse — fresh hit** — second identical question within the window does NOT
  write a new full dossier and re-emits the prior path with a `Reused:` line; no
  surface probe runs (assert via mulog `::explore.probe` absent / `::explore.reuse`).
- **Reuse — stale volatile** — a `volatile` dossier older than the window
  triggers a re-probe and a new dossier.
- **Reuse — static invalidation** — touching a cited file (git change) makes a
  `static` dossier non-reusable.
- **Lineage** — a gap-fill run writes `related: [<prior path>]` and a `## Builds
  on` section; `explore$read-frontmatter` returns the `related` list.
- **Root-agent reuse** — plan-agent pre-flight C3 finds a prior dossier via
  `explore$find` and skips dispatching explore-agent.
- **Downstream unchanged** — feed a new-schema dossier to a plan-agent fixture;
  it reads `entities`/`summary` exactly as before.
- **Auto-persist backstop** — skipped dossier → hook materializes a parseable
  one from answer text.

New mulog signals: `::explore.reuse` (`{:slug :path :reason :age-h}`),
`::explore.gap-fill` (`{:slug :related [paths]}`), alongside the existing
`::explore.persist` / `::explore.skip-persist`.

## 14. Open questions

1. **Pointer dossiers on pure reuse — on or off by default?** Off keeps INDEX
   clean; on keeps a complete turn-by-turn lineage. Lean off (§7.5).
2. **Where does freshness checking live** — prompt rule, `explore$reuse?`
   reader, or a `:agent.tool-use/pre` hook on `(explore-agent …)` dispatch? A
   reader is the tested middle ground; a hook would let *any* root agent get
   reuse for free without prompt changes. Worth prototyping.
3. **Should reuse be global or per-user/project?** Dossiers are project-scoped
   under `.brainyard/`. Cross-project reuse (a `~/.brainyard/agents/explore-agent/`
   corpus) could help, but mixes contexts. Defer.
4. **Roll the same lightweight + reuse treatment to plan-agent dossiers?** The
   plan-agent redesign already does the file-tool half; the reuse-via-references
   half (don't re-author a near-duplicate plan) maps cleanly onto its C2
   duplicate check. Follow-up.
5. **Strict vs. lenient dossier read** — reject malformed frontmatter, or warn
   and continue? Lenient+warn keeps the pipeline flowing; strict catches drift.

---

## Appendix — before/after, one non-trivial turn

**Before (~4 coupled calls, no reuse):**

```clojure
(def slug (:slug (explore$slug :question "…")))
(def fm (:frontmatter (explore$frontmatter
                        :question "…" :slug slug :surfaces ["filesystem" "mcp"]
                        :entities {:files [...] :urls [] :mcp_tools [...] :skills []}
                        :summary "…")))
(def res (explore$write :slug slug :content (str fm body)))
(explore$index-append :path (:path res) :slug slug
                      :surfaces ["filesystem" "mcp"] :summary "…")
```

**After (reuse gate + 2 writes):**

```clojure
;; STEP 0 — reuse before re-explore
(def prior (explore$find :query "mcp servers config health"))
;; … fresh on-topic hit? answer from it, emit its path, done.
;; … else probe the gap, then:

(write-file {:path ".brainyard/agents/explore-agent/results/20260629-140311-mcp-servers-config-health.md"
             :content "<filled RESULT TEMPLATE, related: [<prior path>], freshness: volatile>"})
(write-file {:path ".brainyard/agents/explore-agent/INDEX.md"
             :content "- 2026-06-29 14:03 [mcp-servers-config-health](results/…md) — filesystem, mcp · *…*\n"
             :append true})
```

The model never constructs an entities map, never re-passes `surfaces`/`summary`
across calls, can't fail a write on a keyword typo — and, crucially, **checks
the corpus first**, so a question already answered on disk costs one
`explore$find` instead of a full multi-surface re-exploration.
