# Spec: Memory & Context

*Area code `MEM`. Covers two distinct things the codebase (and CLAUDE.md)
sometimes conflate: (a) the **per-iteration state memory** surfaced
through the behavior tree's `st-memory` atom and the sandbox, and (b) the
**long-term memory store** (`components/memory`, SQLite FTS5). Also covers
context-budget enforcement and the several compaction mechanisms. The BT
side of state memory is in [behavior-tree](behavior-tree.md) §4.*

Status legend and contract-ID conventions: see [README](README.md).

> **Disambiguation up front.** There are two unrelated layer numberings
> in this codebase. The **long-term store** (`components/memory`) uses
> L1 = in-memory session-scoped, L2 = episodic SQLite, L3 = semantic
> SQLite — see §2. The **state-memory view** (CLAUDE.md "State memory")
> is a two-layer prompt-facing projection — L1 = read-only agent-provided
> inputs, L2 = working `def`s in the sandbox — exposed through
> `(context-get [..])` over the `st-memory` atom; it is **not** a
> separate storage protocol — see §1. Earlier CLAUDE.md drafts described
> the state-memory view as three-layer (the L3 row corresponded to the
> retired `remember-note` bindings); that wording was reconciled in May
> 2026 (CR-MEM-02).

---

## 1. Per-iteration state memory

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-MEM-01 | Per-iteration mutable state MUST live in the shared `st-memory` atom (seeded from `st-memory-init`), keyed by accumulator slots (`:tool-results :observations :thoughts :iterations`), declared signature inputs, and `:recalled-memory`. | Implemented | `agent/common/compaction.clj`, `agent/common/schema.clj` (`domain` schema) |
| CR-MEM-02 | The sandbox state-memory view SHOULD be a prompt-facing two-layer projection — **L1 inputs** (read-only, per-turn: `:recalled-memory`, `:previous-turns`, `:agent-state`) and **L2 working `def`s** (across iterations + turns) — surfaced via `(context-get [..])`. It is not a separate storage structure. | Implemented (as prompt-facing view; CLAUDE.md reconciled May 2026) | `coact_agent.clj` (`sandbox-context-accessor`), `sandbox_bindings.clj` |

**CR-MEM-02 (reconciled May 2026):** the original CLAUDE.md text described
an L1/L2/L3 state structure that does not exist in code — there is no
separate three-layer storage; the actual `st-memory` shape is the
accumulator/inputs map specified in CR-MEM-01. The decision (T-2 in
[candidate-todos.md](candidate-todos.md)) was to keep the model as a
*prompt-facing view*, not to introduce a parallel storage layer, and
fix the docs. CLAUDE.md now describes a **two-layer view** — L1 read-only
inputs + L2 working `def`s — exposed via the `sandbox-context-accessor`
block in `coact_agent.clj`. The retired "L3 agent notes" row corresponded
to legacy `remember-note` / `get-note` / `forget-note` sandbox bindings
that were removed (see `sandbox_bindings.clj` line ~257).

---

## 2. Long-term memory store

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-MEM-03 | The store MUST implement `IMemoryStore` with `write-entry`, `read-entries`, `promote`, `forget`, `consolidate-layer`, plus `UnifiedMemory/contextual-recall` and the manager lifecycle (`initialize`/`shutdown`/`get-stats`). | Implemented | `memory/interface/protocol.clj`, `unified_store.clj`, `l1_store.clj` |
| CR-MEM-04 | Episodic (L2) and semantic (L3) layers MUST be backed by SQLite FTS5 virtual tables with porter/unicode61 tokenization and insert/delete/update triggers. | Implemented | `memory/core/sqlite.clj` |
| CR-MEM-05 | Ranking MUST use BM25 within a layer and Reciprocal Rank Fusion (RRF, default k=60) across layers, with default layer weights `{:l1 0.3 :l2 0.4 :l3 0.6}`. | Implemented | `memory/core/recall.clj` (`reciprocal-rank-fusion`), `recall_v2.clj` (`recall-layered`) |
| CR-MEM-06 | A capture pipeline MUST subscribe to `:agent.ask/{pre,post}`, `:agent.tool-use/post`, `:agent.code-eval/post`, `:agent/exception`, parse them (S1), write to L2, and record an audit row. | Implemented | `memory/interface.clj` (`start-capture!`/`stop-capture!`), `sqlite.clj` (`memory_audit`) |
| CR-MEM-07 | L2→L3 consolidation MUST reduce episodic entries into semantic facts. | **Partial** | `memory/core/capture/reducer.clj` (`reduce-l2!`) |
| CR-MEM-08 | A `:system` layer was an earlier design — **removed**. Operator-managed system context now lives as L1 entries with `:kind :system-context` (read via `read-entries :l1 {:kind :system-context}`); there is no `:system` storage layer. | Implemented (as `:kind`, not a layer) | `memory/core/l1_store.clj`, `recall_v2.clj` (renders `:system-context` L1 rows under a "[system]" header) |
| CR-MEM-09 | L1 entries MUST be quota-free and keyed by `{kind}/{field}/{section}`, session-scoped via a `[session-id entry-id]` composite key, with `:kind` ∈ `{:system-context :user-context}`. | Implemented | `memory/core/l1_store.clj` |
| CR-MEM-10 | Retention MUST support keep/unkeep/archive/unarchive and an L2 sweeper (default 30-day retention, 6h cadence). | Implemented | `memory/interface.clj` |

**CR-MEM-07 (Partial):** `reduce-l2!` is a heuristic "P3 stub, full impl
in P4"; the `:reducer :llm` path is **not implemented** (returns
`::llm-reducer-not-implemented :using :heuristic`). Candidate TODO:
implement the LLM reducer.

**CR-MEM-03 (closed May 2026, T-6):** the protocol method
`consolidate-layer` was a stub returning `:produced 0`. The decision was
to make the protocol method the canonical surface: `UnifiedStore` now
dispatches `:l2 → :l3` to `capture-reducer/reduce-l2!` (supplying
`:user-id`/`:ds` from the record), `:l1` is a no-op, and unknown
layers throw. `mem/consolidate-l2!` is now a thin
kwargs-friendly wrapper around `(proto/consolidate-layer store :l2
policy)`. The protocol docstring was reconciled to the real policy keys
(`:session-id`, `:window-ms`, `:min-batch`, `:reducer`) and return shape
(includes `:auto-kept`). All 89 existing memory tests still pass
unchanged.

**CR-MEM-08 (resolved May 2026, T-11):** the `:system` layer was an
earlier design that was removed; operator-managed system context now
lives as L1 entries with `:kind :system-context` (rendered under a
"[system]" header by `recall_v2/render-briefing`). The leftover code
comments and docstrings that still mentioned `:system` as a layer
(`entry.clj`, `unified_store.clj` NS docstring, `recall_v2.clj` NS
docstring + return-shape doc) were swept in the same change. The
`recall-layered` actual default is `[:l1 :l2 :l3]` and always has been
post-refactor — see `recall_v2_test/recall-layered-returns-each-layer-test`
for the explicit "no :system after refactor" assertion.

**CR-MEM (drift):** `get-stats`'s docstring promises a
`:working-memory-keys` field, but `get-db-stats` returns only
`{:episodes :semantic-facts :schema-version}`. Doc/impl drift; candidate
TODO (doc-only or add the field).

---

## 3. Context budget & compaction

There are three parallel mechanisms; they are not redundant but they are
worth keeping straight.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-MEM-11 | Token-budget enforcement MUST walk compactable sections in ascending `:priority` and apply each section's compaction strategy until the budget is met; gated by `:enable-context-budget` (default **true**). | Implemented | `agent/core/context_budget.clj` (`enforce`, `model->budget`, `default-section-policies`) |
| CR-MEM-12 | Section assembly MUST go through a `SectionAssembler` protocol (`sections`/`system-order`/`user-order`/`policies`/`strategies`). | Implemented | `agent/core/context/section_assembler.clj` |
| CR-MEM-13 | RLM-style per-iteration compaction (LLM-summarizing, gated by `:enable-context-compaction` / `:threshold-chars` / `:iteration-trigger`) — **removed** (2026-05). Superseded by the deterministic token-budget reducer (`enforce`). | Removed | `agent/core/context_budget.clj` |
| CR-MEM-14 | Cross-turn auto-compaction (deterministic, no LLM) MUST shrink carryover to target ratio 0.2 on context overflow. Gated — together with the per-iteration / turn-init budget reducer — by the single `:enable-context-budget` knob (default **true**). | Implemented | `agent/common/context_compaction.clj` |

Budget defaults: max-context 128000, max-output 4096, safety-ratio 0.10
(`model->budget`); per-iteration rebudget cadence `:rebudget-every-n-iter`
(default 10). Context compaction across all three scales (per-iteration,
turn-init, cross-turn) is governed by the single `:enable-context-budget`
knob (default true) — there is no separate auto-compaction flag.

---

## 4. Context graph (overlay)

A relational overlay on the long-term store (§2): typed entities and
bi-temporal, typed relationships connecting concepts mentioned across L2/L3
rows. The graph is **not** a fourth storage layer — it is additional
retrieval signals fused into the existing RRF. Design:
[docs/design/context-graph-memory-design.md](../design/context-graph-memory-design.md).
Phases ship independently and are non-regressing: an empty graph (the default,
`:enable-graph-memory false`) leaves recall identical to today's pure-FTS
behavior. Numbering starts at CR-MEM-20 because 11–14 are taken by §3.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-MEM-20 | The store MAY expose a `GraphStore` protocol — `upsert-node`/`find-node` (entity resolution by name+alias), `upsert-edge`/`invalidate-edge` (bi-temporal `t_valid`/`t_invalid` supersession), `neighbors`/`expand` (bounded ≤3-hop traversal over valid edges), `as-of` (historical) — backed by `graph_nodes`/`graph_edges` SQLite tables (schema 2.1.0), gated by `:enable-graph-memory` (default **false**). | Implemented (Phase 0) | `memory/interface/protocol.clj` (`GraphStore`), `memory/core/graph.clj`, `sqlite.clj` (`graph-schema`), `unified_store.clj` |
| CR-MEM-21 | A `sqlite-vec` `graph_vec` index + a `read-vec` recall producer (embeddings via an injected `embed-fn` over `clj-llm/create-embeddings`, no-op when no provider) MUST add semantic-similarity candidates to the RRF. Extension fetched by `bb sqlite-vec:fetch` (sha-pinned) + bundled into the native image. | Implemented (Phase 1) | `memory/core/embed.clj`, `graph.clj` (`vec-search`), `sqlite.clj` (vec loader + `graph_vec`), `recall_v2.clj` (`read-vec`, `:vec` weight) |
| CR-MEM-22 | An async, best-effort LLM extraction sidecar off the capture pipeline MUST populate nodes/edges (entity + relationship extraction, name/alias resolution, functional-relation bi-temporal supersession, provenance). Gated by an injected `extract-fn` (`:graph-extract-model`); off when unset. | Implemented (Phase 2) | `memory/core/extract.clj`, `capture/extractor.clj`, `capture/sidecar.clj` (`:on-write`), `manager.clj`/`interface.clj` (wiring), `agent/core/memory.clj` (`graph-provider-opts`) |
| CR-MEM-23 | A `read-graph` producer MUST resolve seed nodes from the query, expand the bounded (≤3-hop) neighborhood over valid edges, and fuse relationship entries into the RRF (`:graph` weight 0.55); `render-briefing` MUST gain a "## Related" section; as-of history MUST be reachable. | Implemented (Phase 3) | `memory/core/graph.clj` (`search-nodes`/`expand-edges`/`related`), `recall_v2.clj` (`read-graph`, "## Related"), `interface.clj` (`graph-related`/`graph-as-of`) |
| CR-MEM-24 | Community detection + summaries MUST replace the heuristic L2→L3 reducer (closing CR-MEM-07). | Proposed | — |
| CR-MEM-25 | A pluggable neo4j `GraphStore` backend MAY be selectable via config for hosted/team mode. | Proposed (optional) | — |

---

## Gaps & candidate TODOs (this spec)

- **CR-MEM-07 — LLM L2→L3 reducer unimplemented.** Only the heuristic
  reducer runs; `:reducer :llm` returns a not-implemented marker.
  Planned closure via CR-MEM-24 (community summaries). *(Medium.)*
- **`get-stats` drift — `:working-memory-keys` promised, not returned.**
  *(Doc-only or trivial.)*

Outside the memory component's three stubs, the task/budget/config code is
clean of `TODO`/`FIXME` markers.
