# Design: Context-Graph Memory

*Area code `MEM` (extends [specs/memory-and-context.md](../specs/memory-and-context.md)).
Proposes adding a **context graph** — typed entities and temporally-scoped
relationships — to the long-term memory store (`components/memory`), which is
today a keyword-only SQLite FTS5 system. Status: **proposal / design**. No code
shipped. New contracts are numbered CR-MEM-11+ to continue the existing series.*

---

## 1. Motivation

### 1.1 Where the current store stands

The long-term store (`components/memory`) is a three-layer design behind the
`IMemoryStore` / `UnifiedMemory` protocols:

| Layer | Lifetime | Storage | Role |
|---|---|---|---|
| **L1** | one session | in-memory atom | system/user context |
| **L2** | ~30 days | SQLite `episodes` + `episodes_fts` | episodic chronicle |
| **L3** | indefinite | SQLite `semantic_facts` + `semantic_fts` | distilled facts |

Recall (`recall_v2.clj`) fans out a query across the three layers, ranks within
each layer by **BM25**, and fuses across layers with **Reciprocal Rank Fusion**
(RRF, k=60, weights `{:l1 0.3 :l2 0.4 :l3 0.6}`). L2→L3 consolidation
(`capture/reducer.clj`) is a deterministic, heuristic reducer — it buckets
episodes by `(tag, time-window)` and emits a templated summary (CR-MEM-07 is
still **Partial**: no LLM reduction). Provenance exists as a one-directional
`:sources` chain (fact → episode → L1 entry), used for audit/`explain`, not for
search.

### 1.2 The structural blind spot

Everything retrievable today is retrieved by **lexical token overlap**. FTS5
with porter/unicode61 gives stemming and Unicode folding, but:

- **No relationships.** `:relationship` is a declared `fact-type` but has no
  schema, no edges, no traversal. The store cannot answer "what depends on the
  thing the user just asked about" because dependence is not represented.
- **No multi-hop reasoning.** Each recall is a flat MATCH on `content`. There is
  no way to follow A→B→C. Questions whose answer is two facts apart return
  whichever single fact shares the most keywords.
- **No semantic similarity.** "seatbelt sandbox" and "OS write-containment
  policy" share no stems, so a query about one will not surface the other even
  though they are the same concept.
- **No entity resolution.** Ten episodes mentioning `BY_SANDBOX_INTEROP` are ten
  disconnected rows; the system never learns "this is one entity with a history."
- **One-directional provenance.** You can walk fact→episodes, never the reverse,
  and never entity→all-mentions.

The published comparisons are consistent on what this costs. Graph-structured
retrieval with logical traversal reports **~36% higher answer accuracy and ~21%
better retrieval F1** over dense vector retrievers on multi-hop benchmarks, and
on annual-report QA, moving from flat vector retrieval to graph-structured
retrieval moved correct answers from **50% → 80%**
([MarkTechPost](https://www.marktechpost.com/2025/11/10/comparing-memory-systems-for-llm-agents-vector-graph-and-event-logs/),
[TianPan](https://tianpan.co/blog/2026-04-10-graph-memory-llm-agents-relational-reasoning)).
The recurring framing: *vector/keyword search retrieves similar facts but cannot
recover how facts relate* — the blind spot that breaks evolving state and
long-horizon reasoning
([Atlan](https://atlan.com/know/vector-database-vs-knowledge-graph-agent-memory/)).

The goal of this design is to close that blind spot **without throwing away** the
working FTS/BM25/RRF machinery — to add a graph as a fourth retrieval signal
fused into the existing RRF, not to replace the store.

---

## 2. Prior art (what to borrow)

### 2.1 Graphiti / Zep — the reference architecture

[Graphiti](https://github.com/getzep/graphiti) (the open-source engine under Zep)
is the closest match to what brainyard wants, and its design maps almost
one-to-one onto the existing layers. Key ideas worth copying
([Zep paper](https://arxiv.org/abs/2501.13956),
[Neo4j blog](https://neo4j.com/blog/developer/graphiti-knowledge-graph-memory/)):

- **Hierarchical subgraphs.** An *episode subgraph* (raw events/messages), a
  *semantic entity subgraph* (entities + facts extracted from episodes), and a
  *community subgraph* (clusters from community detection). This is **exactly**
  brainyard's L2 (episodes) → L3 (facts) split, with "communities" as a natural
  new tier above L3.
- **Bi-temporal model.** Every edge carries both *event time* (when the fact was
  true) and *ingestion time* (when it was observed), as explicit validity
  intervals `(t_valid, t_invalid)`. This is how the graph represents *change*:
  superseded facts are **invalidated, not deleted** — you can ask "what did we
  believe last week."
- **Edge invalidation on ingest.** New facts insert new edges, update
  confidence/context, or invalidate prior edges they supersede. This is the
  graph-native version of brainyard's missing "preference changed" handling.
- **Hybrid retrieval with no LLM at query time.** Retrieval combines semantic
  embeddings + BM25 keyword search + direct graph traversal, reaching **P95
  ~300ms**. Crucially, the LLM is used at *ingest* (extraction), not at *recall*
  — which fits brainyard's latency-sensitive TUI loop.

### 2.2 Mem0-graph — the lightweight extraction pipeline

[Mem0](https://arxiv.org/abs/2504.19413)'s graph variant shows the minimal viable
extraction pipeline: a two-stage LLM pass (entity extractor → relationship
generator) producing `(source, relation, dest)` triplets with typed labels
(`lives_in`, `prefers`, `owns`, `happened_on`), per-user graph isolation, sub-140ms
queries on FalkorDB
([Mem0/FalkorDB](https://www.falkordb.com/blog/graph-memory-llm-agents-mem0-falkordb/)).
The takeaway for brainyard: the extractor is the only genuinely new component;
everything else is storage and ranking it already has.

### 2.3 Microsoft GraphRAG — community summaries

[GraphRAG](https://www.marktechpost.com/2025/11/10/comparing-memory-systems-for-llm-agents-vector-graph-and-event-logs/)
contributes the *community → summary* idea: cluster the entity graph, summarize
each cluster, and answer "global" questions from cluster summaries rather than
individual facts. This is a strict upgrade of brainyard's heuristic L2→L3 reducer
— consolidation becomes "summarize a graph community" instead of "summarize a
time-bucket."

### 2.4 Consensus design pattern

Across all of these the production pattern is the same **hybrid**: cast a wide net
with vector + keyword for semantic entry points, then use graph structure to
refine and reason over relationships
([Atlan](https://atlan.com/know/ai-memory-vs-rag-vs-knowledge-graph/)). Nobody
replaces keyword/vector search with a graph; they **layer** the graph on top.
That is the architectural thesis of this document.

---

## 3. Backend options

brainyard is a single **GraalVM native-image binary** that already embeds
SQLite. That constraint dominates the backend choice — the relevant axis is not
"which graph DB is best" but "what does each option cost the single-binary,
zero-server distribution model." Three families:

### 3.1 Option A — neo4j (or AuraDB) as an optional pluggable backend

The Graphiti-native choice. neo4j 5.26+ is Graphiti's primary backend.

- **Pros.** Full Cypher, mature graph algorithms (community detection, PageRank),
  battle-tested, the exact target Graphiti/Zep are written against. From the
  native-image perspective it is paradoxically the *easiest* to embed: the client
  is just a **Bolt socket driver** — a network client, no native libs, no
  reflection surface beyond the driver.
- **Cons.** It is a **separate server**. That breaks brainyard's "download one
  binary, run it" promise. A user would need Docker or a running neo4j/AuraDB.
  Memory is partitioned per-user as `<user-id>.db` files today; neo4j wants one
  server with logical partitioning, a different operational model.
- **Verdict.** Right for a hosted/team deployment; wrong as the *default* for a
  local CLI tool. Should be a **pluggable backend**, not the baseline.

### 3.2 Option B — Kuzu, embedded graph database

[Kuzu](https://thedataquarry.com/blog/embedded-db-2/) is "SQLite for graphs":
in-process, single-file, openCypher, no server. Benchmarks show ~18x faster
ingest and large multi-hop speedups vs neo4j.

- **Pros.** Embedded — preserves zero-server. Real Cypher and vectorized
  multi-hop traversal. Single-file per user maps cleanly onto the existing
  `<user-id>` partitioning. Has a JVM binding.
- **Cons.** It is a **native library (JNI)**, which is the hard part under
  **GraalVM native-image** — native libs need explicit configuration and bloat
  the build/`check:ata` config-drift gate. Requires a fixed schema declared up
  front (acceptable here). Younger/less battle-tested than neo4j.
- **Verdict.** The strongest *graph-native* embedded option, but the JNI +
  native-image integration is real work and a new build dependency to vet.

### 3.3 Option C — SQLite-native graph + `sqlite-vec` (recommended baseline)

Stay inside SQLite. Model the graph as ordinary relational tables (a `nodes`
table and an `edges`/triplet table) and add **vector search** via
[`sqlite-vec`](https://alexgarcia.xyz/blog/2024/sqlite-vec-hybrid-search/index.html),
a C extension that does in-database cosine/Hamming similarity. Graph traversal
becomes recursive CTEs (`WITH RECURSIVE`) over the edges table; hybrid search
becomes FTS5 (already present) + `sqlite-vec`, fused with the **RRF code that
already exists**.

- **Pros.** **Zero new server and zero new database engine.** Reuses the
  per-user `.db` partitioning, the `IMemoryStore` adapters, the BM25/RRF stack.
  `sqlite-vec` is the smallest possible addition to enable semantic similarity.
  Bi-temporal edges are just two timestamp columns. Multi-hop to depth 2–3 is a
  bounded recursive CTE — sufficient for agent-memory recall (deep OLAP traversal
  is not the workload).
- **Cons.** Not a "real" graph DB: no Cypher, no built-in graph algorithms;
  community detection must be implemented (e.g., label propagation in Clojure or
  via SQL). Recursive CTE traversal is slower than a native graph engine at high
  depth/fan-out — irrelevant at brainyard's scale, relevant if the graph grows to
  millions of edges. `sqlite-vec` is still a C extension to load (lighter than
  JNI, but native-image must allow `load_extension` or statically link it).
- **Verdict.** **Best fit for the default local binary.** Lowest risk, lowest
  operational cost, maximal reuse. Start here.

### 3.4 Recommendation

> Adopt **Option C (SQLite-native graph + `sqlite-vec`) as the default backend**,
> and define a **`GraphStore` protocol** so **Option A (neo4j)** can be plugged in
> for team/hosted deployments via config (mirroring how the rest of the store
> resolves config: env var > per-agent > session > `.brainyard/config.edn` >
> default). Treat **Kuzu (Option B)** as a future embedded upgrade *if* recursive-CTE
> traversal becomes a bottleneck — defer it because the JNI/native-image cost is
> not justified at current scale.

This keeps the single-binary promise intact, gets semantic similarity and
relationships immediately, and leaves a clean seam for power users who want full
neo4j.

---

## 4. Proposed architecture

### 4.1 The graph as a fourth signal, not a fourth store

The graph is **not** a new layer alongside L1/L2/L3. It is a **relational overlay**
that connects entities mentioned across existing L2 episodes and L3 facts, plus a
new **vector** index over the same rows. Recall gains two new candidate sources —
*vector-similar* rows and *graph-neighbor* rows — fused into the existing RRF.

```
                    contextual-recall(query)
                              │
        ┌──────────┬──────────┼───────────┬─────────────┐
        ▼          ▼          ▼           ▼             ▼
      L1 read   L2 FTS     L3 FTS     vector kNN    graph expand
     (atom)    (BM25)     (BM25)    (sqlite-vec)   (1–2 hop CTE
        │          │          │           │         from seeds)
        └──────────┴──────────┴───────────┴─────────────┘
                              ▼
                 Reciprocal Rank Fusion  (existing recall_v2,
                              │           + weights :vec :graph)
                              ▼
                       render-briefing
```

### 4.2 Graph schema (Option C, SQLite)

Two new tables per user `.db`, plus a vector table. Entities and edges carry the
**bi-temporal** columns borrowed from Graphiti.

```sql
-- Entities (resolved, deduplicated concepts: env vars, files, components, people…)
CREATE TABLE graph_nodes (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id      TEXT NOT NULL,
  node_type    TEXT NOT NULL,        -- entity | concept | component | person | file | config-key …
  name         TEXT NOT NULL,        -- canonical name, e.g. "BY_SANDBOX_INTEROP"
  summary      TEXT,                 -- LLM-maintained rolling description
  aliases      TEXT,                 -- JSON array (entity-resolution surface forms)
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, node_type, name)
);

-- Typed, temporally-scoped relationships  (source)-[relation]->(dest)
CREATE TABLE graph_edges (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id      TEXT NOT NULL,
  src_id       INTEGER NOT NULL REFERENCES graph_nodes(id),
  dst_id       INTEGER NOT NULL REFERENCES graph_nodes(id),
  relation     TEXT NOT NULL,        -- depends_on | configures | supersedes | prefers | part_of …
  fact         TEXT,                 -- natural-language statement of the edge
  confidence   REAL DEFAULT 0.85,
  -- bi-temporal validity (Graphiti model)
  t_valid      DATETIME,             -- when the fact became true (event time)
  t_invalid    DATETIME,             -- when superseded/false (NULL = still valid)
  ingested_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  -- provenance: link back to the L2/L3 rows this edge was extracted from
  source_entry_ids TEXT,             -- JSON array of memory entry_ids
  UNIQUE(user_id, src_id, dst_id, relation, t_valid)
);
CREATE INDEX idx_edges_src ON graph_edges(user_id, src_id) WHERE t_invalid IS NULL;
CREATE INDEX idx_edges_dst ON graph_edges(user_id, dst_id) WHERE t_invalid IS NULL;

-- Vector index over node summaries AND L3 fact content (sqlite-vec)
CREATE VIRTUAL TABLE graph_vec USING vec0(
  ref_kind   TEXT,                   -- 'node' | 'fact'
  ref_id     INTEGER,
  embedding  FLOAT[768]
);
```

Notes:
- **Entity ↔ memory linkage.** `graph_edges.source_entry_ids` reuses the existing
  cross-layer `entry_id`, so the graph and the FTS store reference the same rows —
  no data duplication, provenance stays intact and becomes **bidirectional**
  (node → mentions, and mention → node).
- **Bi-temporal supersession.** Updating a preference inserts a new edge and sets
  `t_invalid = now()` on the old one (an `supersedes` edge optionally records the
  link). Recall defaults to `WHERE t_invalid IS NULL`; `explain`/audit can query
  historical state by passing an as-of timestamp.
- **Communities** can be added later as a `graph_communities` table + a
  `community_id` column on nodes, populated by label propagation — the GraphRAG
  tier, and the eventual replacement for the heuristic L2→L3 reducer.

### 4.3 Ingestion / extraction pipeline

This is the one genuinely new component, and it slots into the **existing capture
pipeline** (CR-MEM-06: the dispatcher already subscribes to
`:agent.ask/{pre,post}`, `:agent.tool-use/post`, `:agent.code-eval/post`,
`:agent/exception`). Extraction runs **asynchronously off the capture sidecar**,
so it never blocks the agent loop:

1. **Episode lands in L2** (unchanged).
2. **Sidecar enqueues** the episode for graph extraction (async channel, same
   pattern as today's L2 drain).
3. **Entity extraction (LLM).** Extract candidate entities + types from the
   episode content. Resolve against `graph_nodes` by name/alias/embedding
   similarity (entity resolution) — create or merge.
4. **Relationship extraction (LLM).** Emit `(src, relation, dst, fact, t_valid,
   confidence)` triplets (Mem0's two-stage pattern).
5. **Edge reconciliation.** Insert new edges; for any edge that contradicts an
   existing one, set `t_invalid` on the old edge (Graphiti invalidation).
6. **Embed.** Compute embeddings for new/updated node summaries and L3 facts;
   upsert into `graph_vec`.

Because extraction is LLM-bound, it must be **batched, debounced, and
budget-bounded** (reuse the reducer's window/threshold knobs) and degrade
gracefully: if no embedding/extraction provider is configured, the graph simply
stays empty and recall falls back to today's pure-FTS behavior. This makes the
whole feature **opt-in and non-regressing**.

### 4.4 Retrieval

Extend `recall_v2/recall-flat` with two new candidate producers, fused by the
**same RRF** already in `recall.clj`:

- **`read-vec`** — embed the query, kNN over `graph_vec`, map hits back to node
  summaries and L3 facts. Catches semantic matches FTS misses.
- **`read-graph`** — take the top FTS+vector hits as **seed nodes**, expand 1–2
  hops over `graph_edges` (bounded `WITH RECURSIVE`, `t_invalid IS NULL`), return
  neighbor facts. Catches relational/multi-hop answers.

New default weights (tune empirically): `{:l1 0.3 :l2 0.4 :l3 0.6 :vec 0.5 :graph 0.55}`.
`render-briefing` gains a **"## Related"** section showing the graph neighborhood
(e.g. `BY_SANDBOX_INTEROP —configures→ clj-sandbox —part_of→ code-eval`), giving
the agent explicit relational context, not just a flat list.

Retrieval stays **LLM-free** (Graphiti's principle): embedding is a cheap vector
op, traversal is SQL. The only LLM cost is at ingest.

---

## 5. Phased migration plan

Each phase ships independently and is non-regressing (graph absent ⇒ behavior ==
today). Proposed new contracts continue the `CR-MEM` series:

| Phase | Deliverable | New contract (proposed) |
|---|---|---|
| **0** | `GraphStore` protocol + SQLite impl behind a feature flag; `graph_nodes`/`graph_edges` DDL + migrations; no extraction yet (manual edge API only). | CR-MEM-11 |
| **1** | `sqlite-vec` integration + `graph_vec`; embedding provider wired through config (degrade to no-op if absent); `read-vec` added to recall + RRF weight. | CR-MEM-12 |
| **2** | LLM extraction pipeline off the capture sidecar (entity + relationship + resolution + bi-temporal invalidation); async, batched, budget-bounded. | CR-MEM-13 |
| **3** | `read-graph` multi-hop expansion in `recall_v2`; "## Related" briefing section; as-of historical queries in `explain`. | CR-MEM-14 |
| **4** | Community detection + community summaries; **replaces** the heuristic L2→L3 reducer (closes the long-standing CR-MEM-07 *Partial*). | CR-MEM-15 |
| **5 (opt.)** | Pluggable **neo4j** backend behind `GraphStore` for hosted/team mode; Kuzu evaluated only if CTE traversal is measured as a bottleneck. | CR-MEM-16 |

Build-system note: Phases 1+ touch native-image. `sqlite-vec` must be either
statically linked or its `load_extension` path allow-listed in the reflection/
native config, and the `check:ata` config-drift gate updated accordingly. neo4j
(Phase 5) adds only a pure-JVM Bolt driver — minimal native-image surface, which
is the argument for keeping it as the *server* option and SQLite as the *embedded*
one.

---

## 6. Risks & tradeoffs

- **Extraction quality/cost.** Bad triplets poison recall. Mitigate with
  confidence thresholds, keeping `source_entry_ids` provenance for audit, and
  making extraction opt-in. LLM cost is bounded by batching/debounce; retrieval
  stays LLM-free.
- **Native-image friction.** The single-binary build is the crown jewel. Option C
  minimizes this (one C extension); Kuzu maximizes it (JNI). This is the core
  reason Option C is the baseline and Kuzu is deferred.
- **Schema rigidity vs. open-world.** A fixed `node_type`/`relation` vocabulary is
  predictable but can miss novel relations; an open vocabulary is expressive but
  noisy. Start with a small curated relation set (the agent/dev domain:
  `depends_on`, `configures`, `supersedes`, `part_of`, `prefers`) and grow it.
- **Graph drift / staleness.** Bi-temporal invalidation handles *change*; a
  periodic GC pass (reuse the existing sweep machinery) should prune
  low-confidence, long-invalidated edges so the graph does not grow without bound.
- **Scope discipline.** The temptation is to build "a graph database." The actual
  requirement is "two new RRF signals." Phases 0–3 deliver the value; 4–5 are
  upside.

---

## 7. Recommendation in one paragraph

Keep the SQLite FTS5 + BM25 + RRF store exactly as-is and **add a context graph as
an overlay**: a `graph_nodes`/`graph_edges` pair of SQLite tables with Graphiti's
bi-temporal validity intervals, an LLM extraction pass hung off the existing
capture sidecar, a `sqlite-vec` vector index for semantic similarity, and two new
candidate producers (`read-vec`, `read-graph`) fused into the RRF that already
exists. This buys relationships, multi-hop reasoning, semantic similarity, entity
resolution, and temporal "what did we believe then" — the documented 30–80%
retrieval gains of graph memory — while preserving the single-binary, zero-server,
per-user `.db` model that defines brainyard. Expose a `GraphStore` protocol so
neo4j can be dropped in for hosted/team deployments, and defer Kuzu until
recursive-CTE traversal is actually measured as a limit.

---

## 8. Sources

Graph vs. vector / GraphRAG evidence:
- [Comparing Memory Systems for LLM Agents: Vector, Graph, and Event Logs — MarkTechPost](https://www.marktechpost.com/2025/11/10/comparing-memory-systems-for-llm-agents-vector-graph-and-event-logs/)
- [Graph Memory for LLM Agents: The Relational Blind Spots That Flat Vectors Miss — TianPan](https://tianpan.co/blog/2026-04-10-graph-memory-llm-agents-relational-reasoning)
- [Vector Database vs Knowledge Graph: Choosing for Agent Memory — Atlan](https://atlan.com/know/vector-database-vs-knowledge-graph-agent-memory/)
- [AI Memory vs RAG vs Knowledge Graph: Enterprise Guide — Atlan](https://atlan.com/know/ai-memory-vs-rag-vs-knowledge-graph/)
- [Knowledge Graphs as Memory — octoco.ai](https://www.octoco.ai/blog/knowledge-graphs-as-memory)

Graphiti / Zep (temporal knowledge-graph memory):
- [Zep: A Temporal Knowledge Graph Architecture for Agent Memory — arXiv 2501.13956](https://arxiv.org/abs/2501.13956)
- [Graphiti: Knowledge graph memory for an agentic world — Neo4j blog](https://neo4j.com/blog/developer/graphiti-knowledge-graph-memory/)
- [getzep/graphiti — GitHub](https://github.com/getzep/graphiti)
- [Zep / Graphiti documentation](https://help.getzep.com/graphiti/getting-started/overview)

Mem0 (entity/relationship extraction):
- [Mem0: Production-Ready AI Agents with Scalable Long-Term Memory — arXiv 2504.19413](https://arxiv.org/abs/2504.19413)
- [Graph Memory for LLM Agents with mem0 + FalkorDB](https://www.falkordb.com/blog/graph-memory-llm-agents-mem0-falkordb/)

Backend options (embedded vs server):
- [Kùzu, an extremely fast embedded graph database — The Data Quarry](https://thedataquarry.com/blog/embedded-db-2/)
- [Kuzu vs Neo4j: Cypher differences — Kuzu docs](https://docs.kuzudb.com/cypher/difference/)
- [Hybrid full-text + vector search with SQLite (sqlite-vec) — Alex Garcia](https://alexgarcia.xyz/blog/2024/sqlite-vec-hybrid-search/index.html)
