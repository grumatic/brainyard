# Procedural Graph — implementation design

> Status: **Phase 1 BUILT and tested; the §8 step-2 gate says STOP before
> Phase 2** (design 2026-09-10 against `086f1bb`; built and measured
> 2026-09-10). The build plan for the recommendation in
> `docs/design/procedural-graph-comparison.md` §6. Read that first: it carries
> the paper's numbers, and every decision here cites a section of it.
>
> **This document corrects two claims in the comparison note.** Both were
> wrong about the code, both changed the design, and §0 states them before
> anything else depends on them.
>
> **What shipped and what the measurement said: §10.** The short version is
> that steps 1–3 are built, gated off by default, and green (73 memory tests /
> 15+ agent tests); mining this repo's 87 recorded turns yields **0 usable
> transitions at min-support 3** against a stated threshold of ~20, so the
> stopping rule fires and Phase 2 is not started. Mining also found a real bug
> in the seeder that no unit test would have caught (§10.2).
>
> Related: `docs/design/context-graph-memory-design.md`,
> `docs/design/evoharness-agent-design.md`, `docs/core/memory.md`.

## 0. Two corrections to the comparison note

### 0.1 `graph_edges` has no `metadata` column

The comparison note §6 Phase 0 says Φ's three fields (`condition`, `guidance`,
`pitfalls`) "go into the edge's existing `:metadata` JSON — **no schema change,
no migration**." That is false. `graph_nodes` has `metadata TEXT`;
`graph_edges` (`sqlite.clj:399`) does not:

```
graph_edges: id, user_id, src_id, dst_id, relation, fact, confidence,
             t_valid, t_invalid, ingested_at, source_entry_ids
```

There is nowhere to put Φ without a migration. This design puts the three
fields in **first-class columns** on a new table instead (§2.2), which is
better than the JSON blob would have been anyway: they are a fixed schema, they
are read on every localization, and the Phase-2 refiner wants to query them
("every edge whose `pitfalls` mentions X") rather than parse them.

### 0.2 Phase 0 is NOT inert if procedure nodes live in `graph_nodes`

The comparison note claims Phase 0 "is pure addition and cannot regress
anything, including recall: with no procedure nodes written, every existing
query behaves identically." The second half is true and vacuous — the moment
Phase 1 writes a procedure node, three shipping recall paths pick it up,
because **none of them filter by node type or relation**:

| Path | Code | Why it leaks |
|---|---|---|
| Lexical seeding | `graph/search-nodes` | `SELECT * FROM graph_nodes WHERE user_id = ? AND (name/summary/aliases LIKE …)` — no `node_type` predicate. A procedure node named `edit$apply` with a summary mentioning "file" is seeded by any query containing "file". |
| Semantic seeding | `graph/search-nodes-semantic` | kNN over `graph_vec` where `ref_kind='node'` keys on `graph_nodes.id` with no type discrimination. Procedure summaries would be embedded into, and compete inside, the entity vector space. |
| Neighborhood walk | `graph/expand-edges` | `JOIN graph_edges e ON (e.src_id = w.node_id OR e.dst_id = w.node_id) WHERE e.user_id = ? AND e.t_invalid IS NULL` — no relation filter, and **undirected**. Any node shared between the two graphs bridges them. |

Those feed `graph/related` → RRF → the `## Related` briefing section, which is
on the hot path of a shipping, on-by-default-when-graph-memory-is-on feature.
A disjoint *relation* vocabulary (the comparison note's §7 mitigation) does not
help: `search-nodes` matches on node text and `expand-edges` walks every
relation.

There is a fourth problem the note missed entirely. `graph/prune-nodes-to-budget!`
hard-deletes the "lowest-retention nodes (and their edges + `graph_vec` rows)"
when the node budget is exceeded. A validated procedure graph co-tenanted in
`graph_nodes` would be **silently evicted by memory retention pressure** — the
one kind of data loss this feature can least afford, since Phase 2 spends
validation rollouts to earn each edge.

**Consequence: separate tables.** §1.2 records the decision and the rejected
alternative.

## 1. Placement decisions

### 1.1 Which brick owns what

```
components/memory   ->  STORAGE + TRAVERSAL   (proc_nodes, proc_edges, out-neighborhood)
components/agent    ->  LOCATE + RENDER + INJECT  (common/procedure_nudge.clj)
```

Memory already owns the SQLite handle, the native-image driver registration,
the migration runner and sqlite-vec loading. A new Polylith brick would have to
reach those through an interface that does not expose them, or open its own
database. Agent already owns the hook chain, `bt-st-memory`, and the `:notices`
contract.

This split also matches the layering rule the codebase already follows for
`clj-llm`: the lower brick cannot see an agent, so anything agent-shaped stays
above it.

### 1.2 Same SQLite file, separate tables

Procedure data lives in `~/.brainyard/memory/<user-id>.db` in **new tables**,
not in `graph_nodes`/`graph_edges`.

- **Rejected — co-tenant with a `:procedure` node type.** Requires a type or
  relation filter at four sites (§0.2), each of which is a place a future edit
  silently reintroduces the leak, in a path that runs on every recall. It also
  cannot express the walk PG needs: `expand-edges` is undirected, and `N_h(u_t)`
  is the **directed out**-neighborhood — "what is admissible AFTER u". An
  undirected walk surfaces what leads *into* u as though it were a next step,
  which is the opposite instruction.
- **Rejected — a separate database file** (`~/.brainyard/procedures/<id>.db`).
  Cleanest scope story, but duplicates connection handling, driver
  registration, migration and native-image config for a table pair. The
  portability that motivates it is served by `by procedures export/import`
  (§6) without a second file.

Two consequences worth stating, because they simplify the committed plan:

- **`:enable-procedure-guidance` no longer depends on `:enable-graph-memory`.**
  The comparison note made Phase 1 "inert unless `:enable-graph-memory` is on";
  with separate tables PG needs nothing from the entity graph. The memory
  database is open whenever memory is on, so the dependency disappears.
- **The retention sweep cannot touch it.** `prune-nodes-to-budget!` and
  `prune-graph-to-budget!` name `graph_nodes` explicitly.

### 1.3 Scope: per-user for now, portable by export

The database is partitioned by `BY_USER_ID`, so a procedure graph is per-user.
Procedural knowledge about *Brainyard's own tools* is arguably user- and
project-independent (comparison note §8), and Phase 2 would pool evidence
better if it were shared.

Deferred deliberately. `graph_id TEXT` is on every row from day one so a shared
graph is a value change rather than a migration, and `export`/`import` (§6)
makes a graph a diffable, shippable artifact now. Revisit when Phase 2 has
produced a graph worth sharing — not before, since the answer depends on
whether evolved graphs turn out to be portable at all, which nothing currently
knows.

## 2. Schema (memory schema version 2.3.0 → 2.4.0)

Added to `memory/core/sqlite.clj` as a new `procedure-schema` vector, appended
to the `all-schemas` concat. All `IF NOT EXISTS`, so existing databases migrate
transparently on open — the same discipline as every prior graph migration.

### 2.1 `proc_nodes`

```sql
CREATE TABLE IF NOT EXISTS proc_nodes (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  graph_id   TEXT NOT NULL DEFAULT 'default',
  name       TEXT NOT NULL,     -- 'edit$apply' | 'code:bash' | 'Start' | 'End'
  kind       TEXT NOT NULL,     -- 'tool' | 'code' | 'state' | 'skill'
  summary    TEXT,
  aliases    TEXT,              -- JSON array
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(graph_id, name)
)
```

**`UNIQUE(graph_id, name)`, deliberately not `(graph_id, kind, name)`** —
unlike `graph_nodes`, which keys on `(user_id, node_type, name)`. `Match` must
resolve a tool id to exactly one node; two nodes named `edit$apply` with
different `kind`s would make localization ambiguous, and there is no
disambiguating information at the call site.

### 2.2 `proc_edges`

```sql
CREATE TABLE IF NOT EXISTS proc_edges (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  graph_id   TEXT NOT NULL DEFAULT 'default',
  src_id     INTEGER NOT NULL REFERENCES proc_nodes(id),
  dst_id     INTEGER NOT NULL REFERENCES proc_nodes(id),
  relation   TEXT NOT NULL,
  condition  TEXT,              -- Phi: when the transition applies
  guidance   TEXT,              -- Phi: how to proceed
  pitfalls   TEXT,              -- Phi: what to avoid
  confidence REAL DEFAULT 0.85,
  support    INTEGER DEFAULT 0, -- observed transition count (seed provenance)
  origin     TEXT NOT NULL DEFAULT 'refiner',  -- 'seed' | 'refiner' | 'manual'
  gen        INTEGER,           -- evolution round that committed it
  t_valid    DATETIME DEFAULT CURRENT_TIMESTAMP,
  t_invalid  DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_proc_edges_live
  ON proc_edges(graph_id, src_id, dst_id, relation) WHERE t_invalid IS NULL;

CREATE INDEX IF NOT EXISTS idx_proc_edges_src
  ON proc_edges(graph_id, src_id) WHERE t_invalid IS NULL;
```

Bi-temporal, matching `graph_edges`: the paper's **Delete** is
`invalidate-edge` (set `t_invalid`), never a row delete, so evolution history
stays queryable and an `as-of` post-mortem can reconstruct which graph produced
a given rollout. The live partial unique index is copied from
`idx_edges_live_unique` and for the same reason — a table-level
`UNIQUE(..., t_valid)` both fails to stop duplicate live rows and rejects
legitimate re-assertion after supersession (`sqlite.clj:399` records that
lesson; do not relearn it).

`origin` + `gen` are the discriminator the comparison note §8 asked for, and
they are on the edge rather than in a side table so a dump answers "where did
this come from" without a join.

### 2.3 `proc_graphs` — the retained graph's cached score

```sql
CREATE TABLE IF NOT EXISTS proc_graphs (
  graph_id   TEXT PRIMARY KEY,
  gen        INTEGER DEFAULT 0,
  val_score  REAL,              -- cached S_val of the RETAINED graph
  val_suite  TEXT,              -- which suite produced it
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
)
```

The cache is not an optimization. The paper: *"Invalid candidates are discarded
before validation rollout, leaving the retained graph and its cached validation
score unchanged"* — re-scoring the retained graph every round would make the
gate compare two independent noisy samples instead of a candidate against a
fixed reference.

### 2.4 `proc_rejections` — rejection memory

```sql
CREATE TABLE IF NOT EXISTS proc_rejections (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  graph_id   TEXT NOT NULL,
  gen        INTEGER NOT NULL,
  edits      TEXT NOT NULL,     -- JSON: the proposed delta-G
  val_score  REAL,
  base_score REAL,
  reason     TEXT,              -- 'regressed' | 'structurally-invalid'
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
)
```

A **separate table, not invalidated edges.** The comparison note §8 worried
that rejected candidates and superseded edges would be indistinguishable in an
`as-of` audit. They are worse than indistinguishable: a rejected candidate must
never be reachable by a traversal, and an invalidated row in `proc_edges` is
one `t_invalid` predicate away from being walked. Rejections are proposals that
were never part of any graph, so they do not belong in the edge table at all.

## 3. Relation vocabulary

```clojure
(def procedure-relations
  "Transition vocabulary for procedural edges (what-to-do). Deliberately
   disjoint from the memory graph's `relations` (what-is)."
  #{:precedes   ; dst is a normal next step after src
    :requires   ; src must have happened before dst is valid (precondition)
    :enables    ; src makes dst possible or useful
    :leads_to   ; src's result feeds dst
    :verifies   ; dst checks src's work
    :repairs})  ; dst is the recovery path when src failed
```

**Revised from the comparison note's set.** That set was
`#{:precedes :requires :enables :leads_to :extracts_to :generates}`, transcribed
from the paper's Figure 1 — whose worked example is a search-and-answer
pipeline (`Read Evidence —extracts→ Extract`). `extracts_to` and `generates`
describe that domain, not ours, and shipping relations nothing will ever assert
makes the vocabulary look richer than it is.

`:verifies` and `:repairs` replace them because they name the two procedural
facts most worth encoding here: the read-guard/verify pattern (an apply that
succeeded is not an edit that worked) and the repair path (what to do when a
step failed). Both are things the codebase currently states only in prose.

## 4. Phase 1 — locate, extract, generate, inject

Config gate `:enable-procedure-guidance` / `BY_ENABLE_PROCEDURE_GUIDANCE`,
default **false**, `:env-fn` in the shape every boolean key uses
(`config.clj:270` is the template).

### 4.1 Locate — via the hook, not the iteration record

`Match(a_{t-1}, V)` needs the most recent procedure. The obvious source is the
iteration record's `:tool-results`, and it is the wrong one: **code-channel
tool calls route through `tool/call-tool` into the same hook chain**, so a tool
invoked from inside a Clojure block never appears in `:tool-results` at all.
`usage-nudge` already learned this and hooks instead.

So `common/procedure_nudge.clj` mirrors `usage_nudge.clj` exactly:

```clojure
(hooks/register-hook! :agent.tool-use/post ::procedure-nudge on-tool-post
                      :source :procedure-nudge)
```

`on-tool-post` writes `{:name <tool> :ts <ms>}` to the per-turn `bt-st-memory`
under `:last-procedure`, overwriting. Overwriting is the design: **an iteration
that emits N tool calls localizes on the LAST one.** Unioning N neighborhoods
is un-localizing, which §5.2 of the comparison note measured as harmful; taking
the first would guide from a step already superseded within the same iteration.
The multiplicity is logged (`::proc-multi-action`) because the paper assumes one
action per step and this is a real divergence in our favour to measure, not to
assume away.

Registration must be a **fn called at load AND from `coact-init`, never a
`defonce` latch** — `hooks/reset-hooks!` is on the public interface and ~24 test
namespaces call it. `usage_nudge.clj`'s docstring records that exact bug.

Resolution order for `u_t`:

| Situation | Node name | Notes |
|---|---|---|
| Tool called this iteration | the tool id, e.g. `edit$apply` | from the hook |
| Code block, no tool call | `code:<lang>`, e.g. `code:bash` | coarse but real: "after a bash block, check the exit code" is a genuine transition |
| Iteration 1, nothing yet | `Start` | the paper's `a_0` initialization marker |
| Anything else | **nil** | emit nothing |

### 4.2 Extract — a new directed edge walk

`expand` supports `:direction :out` but returns **nodes**; `expand-edges`
returns edges but is **undirected**. PG needs directed edges with attributes,
so neither is reusable and a new store fn is required:

```clojure
(defn out-neighborhood
  "N_h(u): directed OUT edges within `max-hops` of `node-id`, with Phi
   attributes and endpoint names. Ordered by depth, then confidence desc.
   Bounded recursive CTE, max-hops clamped <= 3."
  [ds graph-id node-id {:keys [max-hops limit] :or {max-hops 2 limit 12}}]
  ...)
```

The CTE follows `e.src_id = w.node_id` **only**. Copying `expand-edges`'
`(e.src_id = w.node_id OR e.dst_id = w.node_id)` is the single most likely
implementation error here and it fails silently — the guidance still renders,
it just describes what leads into the current step as though it were the next
one. §7 pins it with a test.

**On a miss, emit nothing.** The paper falls back to the full graph
(Equation 2); comparison note §5.2 measured that fallback at −18.10 points on
the task shape closest to ours, and §8 predicts misses will be common given
~200 tool defs plus user-authored and MCP ids. The miss branch is the hot path,
so it must be free and silent — one `mulog/debug`, no query, no allocation.

### 4.3 Generate — template by default, LLM never on the hot path by default

`:procedure-guidance-mode` ∈ `#{:template :llm}`, default **`:template`**.

Three reasons, in order of weight:

1. **A stochastic guidance step makes the Phase-2 gate measure the wrong
   thing.** `S_val(cand) >= S_val(retained)` is a comparison of two graphs; if
   `Ψ` is a sampled LLM call, the gate is also measuring the guidance model's
   variance. The paper runs everything at temperature 0 for exactly this
   reason. A template makes guidance a pure function of the graph, which is
   what the gate assumes.
2. **Cost and latency on the hot path.** Comparison note §5.3: guidance already
   costs 33–55% more tokens than no graph. An extra LLM round trip per
   iteration adds to that *and* to wall-clock.
3. **`:llm` on `:light` is unvalidated** (comparison note §8) — the paper always
   uses the solver's own model for `Ψ` and never measures a cheaper one.

`:llm` stays available for measuring (2) and (3) against `:template`, which is
the experiment worth running before defaulting differently.

Rendering, capped at `:procedure-guidance-max-chars` (default 600 — the cost is
tokens, so the cap is the cost control and it should be auditable):

```
▸ Procedure — observed after `edit$apply`:
  → `edit$apply` —verifies→ `read-file`
      when: the apply reported success
      do:   re-read the region before reporting done
      avoid: treating a successful apply as a successful edit
  → `edit$apply` —precedes→ `task$wait`   (when the apply detached)
  then (2 hops): `build$run`
```

Depth-1 edges are the immediate options; depth-2 collapses to a `then:` line.
Empty neighborhood renders `nil`, not `""` — `coact-accumulate-iteration-action`
filters with `remove str/blank?`, so either works, but `nil` is the honest
value and keeps `mulog` counts truthful.

### 4.4 Inject — one line in the accumulate action

`coact-accumulate-iteration-action` already drains two producers into
`:notices`. This adds a third:

```clojure
(let [parts (->> [(usage-nudge/drain-iteration-notices! st-memory)
                  (self-improve-nudge/drain-iteration-notice! st-memory)
                  (procedure-nudge/drain-iteration-notice! st-memory)   ;; NEW
                  fmt-guide]
                 (remove str/blank?))]
```

That is the **entire** agent-side integration. No new prompt zone, no
cache-zone disturbance, no signature change — `:notices` is already an optional
field on `::iteration`, already documented to the model as *"Advisory; read and
act on it"*, and already serialized into `::iterations`.

The timing is right for free. The drain happens at the end of iteration *t−1*
and lands on *t−1*'s record; the model reads `:iterations` when choosing
`a_t`. That is `g_t` reaching the solver exactly when the paper puts it, using
plumbing that already exists.

One inherited constraint: the drain runs **only when `record-for-model` is
non-nil**, i.e. never on the terminal `:answer` iteration. Correct here — a
notice drained onto a record the model never reads back would be silently lost,
and there is no next action to guide.

### 4.5 Gating — revised from the comparison note

The note said "gate it off for the `:light` tier and for `by ask`". The
`by ask` half was wrong reasoning: it conflated *headless* with *short*, and a
`by ask` that runs fifteen iterations is exactly the long-horizon case where
the paper's margins are largest. The real discriminator is trajectory length,
and **Match-miss already approximates it** — a short QA turn that calls no
catalogued tool localizes nowhere and gets nothing, at zero cost.

So: no `by ask` gate. Keep the `:light`-tier exclusion for dispatched
specialists, where a tier is actually resolved (`tool.clj:483`).

### 4.6 Seeding — mined from trajectories, never authored

Phase 1 does nothing without a graph, and comparison note §5.1 says a
hand-written frozen graph measured 28.57 points below no graph. That looks like
a contradiction. The paper's own table resolves it:

| Mode | MultiChallenge Overall |
|---|---|
| Unguided baseline | 87.50 |
| Mode 1: **Hand-crafted** expert | 58.93 |
| Mode 4: **Scratch + Static Build** | **89.29** |

Mode 4 is also static and un-evolved, and it beats the baseline. The
discriminator is not hand-written-vs-generated, it is **authored from a human's
model of the domain vs built from observed execution**.

So the Phase-1 seed is mined, not written:

```
by procedures build --from-trajectories [--min-support 3] [--graph default]
```

Walk every `<project>/.brainyard/sessions/*/trajectory.edn`, take each record's
`:iterations` in order, emit `(name_i -> name_{i+1})` transitions from the
`:tools` entries and `:channel` markers, count them, and write pairs at or above
`--min-support` as edges with `relation :precedes`, `origin 'seed'`, the
observed `support`, and **empty `condition`/`guidance`/`pitfalls`**.

Empty prose is the point. A bare topology asserts only "this order was
observed", which is true. Inventing guidance text at seed time is precisely
Mode 1.

This also makes Phase 1 testable on day one: `:enable-trajectory-recording`
defaults **true**, so every existing session directory is already training data.

### 4.7 Observability

| Event | Fields | Why |
|---|---|---|
| `::proc-located` | `:node :kind` | localization worked |
| `::proc-miss` | `:probe` | **the metric that decides whether Phase 2 is worth building** |
| `::proc-guided` | `:edges :chars :depth-1 :depth-2` | what actually reached the prompt |
| `::proc-multi-action` | `:n :chosen` | how often an iteration emits >1 action |
| `::proc-committed` / `::proc-rejected` | `:gen :val :base` | Phase 2 |

Miss rate is first among these. Comparison note §8 predicts frequent misses; if
the measured rate is above ~90% the feature is inert and Phase 2 should not be
built. That number is obtainable from Phase 1 alone, which is most of why
Phase 1 is worth building even if it never guides anything.

## 5. Phase 2 — evolution

Not started until Phase 1 has reported a miss rate and a token delta.

```
by procedures evolve --suite <path> --rounds N [--graph default]
```

Per round `k`, following §2.3 of the comparison note:

1. **Diagnostic rollout** — run the retained graph on a train batch, record
   `(question, trajectory, score)`. Score comes from **evoharness assertions**,
   not the analytics SHS scorer: assertions are the whole of the task reward and
   no LLM judges them, which removes the most obvious way for an LLM refiner to
   grade its own homework.
2. **Mutation** — the refiner receives the partitioned traces plus
   `H_rejected` for this `graph_id`, and returns a JSON-schema-constrained edit
   set of `Add` / `Delete` only. An attribute revision is delete + re-add, as in
   the paper — one edit interface, so the gate and the rejection memory need
   only understand two operations.
3. **Structural check, BEFORE any rollout** — this is doing real work and is
   worth enumerating, since an invalid candidate rejected here costs nothing
   while one rejected after rollout costs a full validation batch:
   - every `src`/`dst` resolves to an existing `proc_node`
   - `relation` ∈ `procedure-relations`
   - no self-loop
   - no cycle introduced (the paper's "configured cycle repair"; simplest
     correct policy is reject-on-cycle, not repair — a repair silently changes
     the edit the refiner proposed and is then attributed to it)
   - `Delete` names a live edge
   Failures write `proc_rejections` with `reason 'structurally-invalid'`.
4. **Validation gate** — roll out the candidate on a held-out suite; commit iff
   `S_val(cand) >= S_val(retained)`, comparing against the **cached**
   `proc_graphs.val_score` (§2.3). Commit bumps `gen`, stamps `gen` on new
   edges, and updates the cache. Otherwise write `proc_rejections` with
   `reason 'regressed'`. A rejected candidate never becomes the next round's
   base.

The gate is evoharness's promotion gate pointed at a graph instead of a
checkpoint, and its standing mandate — *refuse a checkpoint whose eval does not
clear the gate, including when asked to promote it* — transfers unchanged.

**No user-facing agent writes `proc_edges`.** Not `config-agent`, not
`router-agent`, not a `procedure$*` tool family. The whole finding of Modes 1–2
is that unmeasured edits are worse than no edits, and a tool that lets an LLM
write an edge is a Mode-1 generator with extra steps. `origin 'manual'` exists
for a human running the CLI, which is auditable and rare.

## 6. CLI surface

Registered in `main.clj`'s subcommand table, same shape as the `memory`
family:

| Command | Purpose | Phase |
|---|---|---|
| `by procedures list [--graph id] [--json]` | nodes + live edges + counts | 1 |
| `by procedures show <node> [--hops 2]` | **the localization probe** — exactly what `Match` + `out-neighborhood` would return, and the primary debugging surface | 1 |
| `by procedures build --from-trajectories [--min-support N]` | seed from recorded turns (§4.6) | 1 |
| `by procedures export [--graph id]` / `import <file>` | portability without a second database (§1.3) | 1 |
| `by procedures evolve --suite <p> --rounds N` | the loop (§5) | 2 |
| `by procedures rejections [--graph id]` | inspect `H_rejected` | 2 |

`show` matters more than it looks: guidance is invisible from outside the
process otherwise, and "why did this turn get no guidance" is the question this
feature will generate most often.

## 7. Test plan

Per the working agreement: run the affected namespaces, not `bb test`.
New suites under `components/memory/test/…/procedure_test.clj` and
`components/agent/test/…/procedure_nudge_test.clj`.

**Store**

- `upsert-edge` is idempotent against `idx_proc_edges_live`; a second assert
  updates Φ rather than inserting a second live row.
- `invalidate-edge` then re-assert produces a new live row **and** leaves the
  old one queryable — bi-temporal supersession, the case the memory graph's
  `UNIQUE(..., t_valid)` originally broke.
- **`out-neighborhood` is DIRECTED.** Given `a —precedes→ b`, the
  out-neighborhood of `b` is empty and of `a` contains `b`. This is the test
  that catches a copy-paste of `expand-edges` (§4.2), which is the most likely
  single error in the build.
- `max-hops` clamps to 3; depth ordering is stable.

**Non-contamination — the regression the comparison note needed and lacked**

- With `proc_nodes`/`proc_edges` populated, `graph-related`, `recall` and the
  `## Related` briefing return **byte-identical** results to an empty-procedure
  baseline.
- `prune-nodes-to-budget!` with a budget of 1 evicts entity nodes and leaves
  `proc_nodes` untouched.

**Locate**

- An iteration emitting three tool calls localizes on the **last**, and logs
  `::proc-multi-action`.
- A tool invoked from inside a Clojure block localizes (the hook path, §4.1) —
  the case reading `:tool-results` would miss.
- Iteration 1 localizes on `Start`; an unknown tool returns nil and emits
  nothing, running **no** query.
- After `hooks/reset-hooks!`, `ensure-global-hooks!` re-arms — the latch bug
  `usage_nudge.clj` documents.

**Render + inject**

- Cap enforced at `:procedure-guidance-max-chars`; empty neighborhood → `nil`.
- All three notice producers firing in one iteration yield one `:notices`
  string joined with `\n\n`, in producer order.
- Gate off ⇒ the hook is not registered and `:notices` is untouched.
- Terminal `:answer` iteration drains nothing (§4.4).

**Seed**

- `build --from-trajectories` over a fixture directory produces the expected
  transitions with correct `support`, and writes **blank** Φ (§4.6).
- v2 trajectory lines (no `:answer` iteration) are read without error — the
  files on disk are append-only and full of them.

## 8. Build order and stopping rules

| Step | Deliverable | Stop if |
|---|---|---|
| 1 | Schema + store + tests (§2, §7) | — |
| 2 | `by procedures build/list/show` (§4.6, §6) | seeded graph from real trajectories has < ~20 edges at min-support 3 — there is no procedure to learn |
| 3 | Locate + template render + inject, gate default off (§4) | — |
| 4 | **Measure**: miss rate, token delta, notice frequency on real sessions | miss rate > ~90% ⇒ stop; the feature is inert |
| 5 | Phase 2 evolution (§5) | `S_val` variance across the suite exceeds the effect size ⇒ the gate is measuring noise, which the paper itself flags at 20 episodes per split |

Steps 1–4 are worth doing on their own: they produce the miss-rate measurement
that decides whether step 5 is justified, and that number is not obtainable any
other way.

## 9. Open questions

- **`:template` vs `:llm` for `Ψ`.** §4.3 defaults to template on gate-integrity
  grounds, but the paper only ever measured the LLM form. The comparison is a
  clean A/B once step 4 exists.
- **Does `code:<lang>` carry real procedural signal**, or is it too coarse to be
  anything but noise in the seed? Answerable from step 2's mined support counts
  before any of it ships.
- **Graph scope** (§1.3) — deferred until an evolved graph exists to test
  portability against.
- **Interaction with compaction.** `:iterations` is capped at the last 10 and
  older turns collapse to `[Turn N]`. A notice on an evicted record is gone;
  whether guidance should be re-emitted on re-entry to a node is unexamined and
  the paper, whose solvers keep a flat window, does not address it.

## 10. As built (2026-09-10)

Steps 1–3 of §8 are implemented, tested and **off by default**. Step 4 (the
measurement) was run and its verdict is **stop**.

### 10.1 What shipped

| Area | File | Notes |
|---|---|---|
| Schema | `memory/core/sqlite.clj` | `procedure-schema` — `proc_nodes`, `proc_edges`, `proc_graphs`, `proc_rejections` + 4 indexes. Memory schema **2.3.0 → 2.4.0**. All `IF NOT EXISTS`; pure addition. |
| Store | `memory/core/procedure.clj` | `upsert-node!`, `find-node` (Match), `upsert-edge!`, `invalidate-edge!`, **`out-neighborhood`** (directed CTE), `snapshot`, `graph-meta`/`set-graph-meta!`, `record-rejection!`/`rejections`. |
| Interface | `memory/interface.clj` | `procedure-*` fns; `procedure-relations`, `default-procedure-graph-id`. |
| Locate/render/inject | `agent/common/procedure_nudge.clj` | `:agent.tool-use/post` hook → `:last-procedure`; `probe-for-iteration`; `render-guidance`; `drain-iteration-notice!`. |
| Seed | `agent/common/procedure_seed.clj` | `mine-transitions`, `seed-procedures!`, `iteration->procedure`, `transitions`. |
| Injection | `agent/common/coact_agent.clj` | one added element in `coact-accumulate-iteration-action`'s `parts` vector; the action now destructures `:agent`. |
| Config | `agent/core/config.clj` | `:enable-procedure-guidance` (default **false**), `:procedure-graph-id`, `:procedure-guidance-hops`, `:procedure-guidance-max-edges`, `:procedure-guidance-max-chars`. |
| CLI | `agent_tui_app/main.clj` | `by procedures list / show / build`. **`known-subcommands` also had to learn `"procedures"`** — the subcommand table alone does not register a family, and the dispatch gate rejects anything not in that set. |

Deviations from the plan as written:

- `mine` / `build!` were renamed **`mine-transitions` / `seed-procedures!`**.
  `export-symbols` publishes a var under its own name, and `agent/build!` is
  far too generic for the agent interface.
- Φ is stored as first-class columns (§2.2 as designed), so nothing in the
  entity graph moved and no `ALTER` was needed anywhere.

Not verified: the live-LLM path. Guidance is unit-tested end-to-end against a
real store through `drain-iteration-notice!`, and the coact suite (138 tests)
passes with the injection wired in, but no turn has been run against a real
model with the gate on — there is not yet a graph worth guiding from (§10.3).

### 10.2 Mining real trajectories found a bug no unit test would have

`project-iteration` populates `:tools` only for the **tool channel**. A Clojure
block that calls a tool records as:

```clojure
{:channel "code" :code ["(aws$whoami)"] :lang ["clojure"]}   ; no :tools
```

But at runtime that iteration localizes on **`aws$whoami`**, because
code-channel tool calls route through `tool/call-tool` into the
`:agent.tool-use/post` hook the localizer reads. The seeder keyed it
`code:clojure`. **Seeder and localizer disagreed, so every seeded node would
have been unreachable — a populated graph that never matches.** Precisely the
failure `iteration->procedure`'s docstring warns about, shipped in the function
carrying the warning.

The unit test suite passed throughout: it only covered code blocks with no
inner tool call. Only mining the 87 real recorded turns exposed it — before the
fix the whole corpus reduced to **5** distinct procedures
(`code:bash`, `code:clojure`, and 3 tool names); after, **15**, with
`config$apply`, `evo$*`, `mcp$clickhouse`, `usage$guide`, `llm$models`
appearing. `iteration->procedure` now scans code text for `(family$verb …)`.

The `$` requirement is deliberate: the SCI sandbox binds every visible tool as
a callable fn, so matching a bare `(search …)` would also match `clojure.core`
fns and locals. **A false positive is strictly worse than a miss** — a missed
procedure means guidance does not fire, an invented one means guidance fires
from a node that never existed.

### 10.3 Step-2/step-4 measurement — the gate says stop

`by procedures build` over this repo's 12 trajectory files (87 turns,
15 sessions):

| min-support | transitions kept | **usable** (self-loops dropped) |
|---|---|---|
| 2 | 7 | **3** |
| 3 | 4 | **0** |
| 5 | 2 | **0** |

§8 step 2's threshold is "< ~20 edges at min-support 3 ⇒ there is no procedure
to learn". The measured value is **0**. The gate fires; Phase 2 is not started.

The distribution says why, and it is about the corpus rather than the code:

```
  20  code:bash    -> code:bash        <- self-loop, carries no transition
   3  evo$episodes -> evo$episodes     <- self-loop
   2  evo$tasks    -> code:bash
   2  code:bash    -> evo$stats
   2  evo$episodes -> evo$tasks
   1  evo$suites -> evo$run -> evo$runs -> evo$episodes   <- a REAL procedure, seen once
```

A genuine procedure is visible in the `evo$*` chain — and it was observed
exactly once. 87 turns of mixed exploratory sessions is far too little; the
paper trains on batches of tasks built to exercise one domain. The honest
reading is **not enough recorded execution**, not "the idea does not work
here".

This also answers §9's open question about `code:<lang>` granularity: of 41
transitions, **23 are `code:bash → code:bash`**. At that granularity the code
channel is mostly noise, and the tool-extraction fix in §10.2 is what makes the
corpus legible at all.

### 10.4 What would change the verdict

- **More recorded execution on a repeating task shape.** The cheapest source is
  evoharness rollouts, which are already assertion-scored and already replay a
  suite — exactly the "batch of training tasks" the paper's loop assumes and
  the one thing this corpus lacks.
- **Re-run `by procedures build --min-support 3`** and check step 2 again
  before writing any Phase-2 code.
- If the graph does populate, the next number to collect is the **`::proc-miss`
  rate** in a real session (§4.7) — still the measurement that decides whether
  Phase 2 is worth building at all.
