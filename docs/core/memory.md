# Memory Architecture

The agent uses a **layered memory stack** unified behind a single
`IMemoryStore` protocol over three formal layers, `#{:l1 :l2 :l3}`.
Short-term working memory lives in the BT's `st-memory` atom; the
session-scoped system/user context (knowledge-section fragments) lives in
an in-memory L1 store; the day-to-day chronicle lives in SQLite (L2); and
distilled facts live in semantic SQLite tables (L3). A capture pipeline
populates the chronicle automatically from lifecycle hooks; a recall
pipeline merges layers with Reciprocal Rank Fusion (RRF) and injects
the result via `:stable-keys`.

Primary files:

- `components/memory/src/ai/brainyard/memory/interface.clj` — public API
- `components/memory/src/ai/brainyard/memory/interface/protocol.clj` — `IMemoryStore`
- `components/memory/src/ai/brainyard/memory/core/{unified_store, l1_store, episodic, semantic, recall_v2, audit, policy}.clj`
- `components/memory/src/ai/brainyard/memory/core/capture/{dispatcher, parser, sidecar, reducer}.clj`
- `components/agent/src/ai/brainyard/agent/core/{context, memory}.clj` — knowledge sections, recall injection
- `components/agent/src/ai/brainyard/agent/common/sandbox_bindings.clj` — notes API

---

## Layered model

| Layer | Lifetime | Backing store | Primary API | Example |
|---|---|---|---|---|
| **L0** | One iteration | LLM context window | (none — provided by signature) | Current thought, tool result |
| **L1** | One session | In-memory L1 store | `mem/write-entry` at `:l1`; `memory$remember` (LLM-facing); `assemble-field` reader | System-context fragment, agent-context overlay |
| **L2** | Default 30 days, configurable | SQLite `episodic` table | capture pipeline, `episodes/*` | "User asked X at 14:32" |
| **L3** | Indefinite | SQLite `semantic_facts` table | consolidation, `facts/*` | "User prefers Polylith layout" |

L0 is implicit in the prompt. L1 is the agent's working memory, holding
two entry kinds — `:system-context` (operator-managed configuration, e.g.
loaded skill instructions and assembled knowledge sections) and
`:user-context` (model-curated). Both kinds are writable from the
LLM-facing `memory$remember` command (see below); operators may also
write either kind directly via `mem/write-entry`. L2 is the chronicle of
what happened. L3 is what we believe to be true in general.

> **Note (L1 simplification refactor).** An earlier revision modelled a
> separate `:system` layer plus a `:kind :note` family written by the
> sandbox `remember-note` bindings. Those were removed: the formal layer
> set is now exactly `#{:l1 :l2 :l3}`, and system context is just an L1
> entry with `:kind :system-context`. The sandbox note bindings stay
> gone; the model-facing path that replaced them is `memory$remember`
> (with `:field`/`:section`), which writes addressable L1 entries of
> either kind.

---

## `IMemoryStore` protocol

```clojure
(defprotocol IMemoryStore
  (write-entry        [store layer entry])              ;; layer ∈ #{:l1 :l2 :l3}
  (read-entries       [store layer query opts])
  (promote            [store entry from-layer to-layer])
  (forget             [store layer entry-id])
  (consolidate-layer  [store from-layer policy]))
```

The `memory.interface` namespace re-exports these as `write-entry`,
`read-entries`, **`promote-entry`** (wraps `promote`), **`forget-entry`**
(wraps `forget`), and **`consolidate-l2!`** (a thin wrapper that calls
`proto/consolidate-layer` with `:from-layer :l2`; the bare
`consolidate-layer` method stays protocol-only). Cross-layer recall is a
separate `UnifiedMemory` protocol method, `contextual-recall`. (The
working / episodic / semantic accessors and `compact` were removed in the
unified-store refactor.)

Every entry has a stable schema:

```clojure
{:id          #uuid "…"
 :layer       :l2
 :kind        :system-context | :user-context | :episode | :fact | :observation
 :content     "…"                ;; canonical text, FTS-indexable
 :data        {…}                ;; structured payload
 :tags        #{"event:tool" "tool:bash" "topic:deploy" "role:user"}
 :sources     [{:type :tool-call :id "…"} …]
 :session-id  "…"
 :user-id     "…"
 :created-at  #inst "…"
 :ttl         nil                 ;; nil = indefinite, else duration
 :confidence  0.0–1.0
 :access-count 0
 :keep        false
 :archived    false
 :tombstoned  false}
```

The real SQL columns hold `tags / sources / keep / archived / tombstoned / entry_id`;
the JSON `metadata` column packs `:ttl :data :metadata`. (L1 key/byte
quotas were removed in the L1 simplification refactor — see
`l1_store.clj`; writes are still applied atomically inside each `swap!`.)

The `unified-store` composite dispatches by `:layer`. Cross-layer
`promote` stamps `:sources [{:type :promotion …}]` so the L3 provenance
chain back to source episodes survives indefinitely. `forget` writes a
tombstone so audits remain reproducible.

---

## What the BT sees

Inside a turn, the agent has access to three logical surfaces:

1. **`:st-memory`** (atom in the BT context) — the BT's working memory.
   Reset every turn; carries the question, intermediate thoughts, tool
   calls, observations, and the assembled `:answer`. See [bt.md](bt.md).
2. **Sandbox-side `context` map** — what CoAct code sees inside the
   sandbox: `:question`, `:conversation`, `:recalled-memory`,
   `:previous-runs`, `:restored-vars`, `:tools`. Built by
   `coact-init-action` from the agent's session and the memory recall.
   See [reasoning.md](reasoning.md).
3. **Long-term memory manager** — hangs off the agent record; reached
   from BT actions via `agent.core.memory/recall` / `remember`. Recall
   results land back in `:st-memory` under `:recalled-memory`.

---

## Knowledge sections (system-prompt overlay)

Source: `components/agent/src/ai/brainyard/agent/core/context.clj`.

Three runtime-mutable fields back the agent's system context:

| Field | Purpose |
|---|---|
| `:instruction` | Top-level system instruction for the agent |
| `:agent-context` | Agent-specific behavioral / role context |
| `:tool-context` | Operational guidance about the current toolset |

These fragments are stored as L1 entries with `:kind :system-context`.
Operators set them directly via `mem/write-entry` at layer `:l1`; the
LLM can also write them through `memory$remember` (see *Writing L1 from
the model* below). The only consumer in `context.clj` is the reader:

```clojure
(assemble-field st-memory-init field)   ;; field ∈ #{:instruction :agent-context :tool-context}
```

`assemble-field` reads all L1 `:system-context` entries for the current
session and `(kind, field)` pair in a single `read-entries` call
(TOCTOU-safe — no two-step deref/iterate), assembling one system-prompt
fragment per field. If the result exceeds
`default-assemble-field-max-chars` (65 000 chars ≈ 16K tokens) it emits a
`mulog/warn ::assemble-field-overflow`. Because these entries live in L1
they participate in the audit and explain APIs.

The CoAct signature declares `:system-context` / `:user-context` as
`:stable-keys`, so the assembled fields ride the system message and
benefit from LLM provider prompt-cache reuse.

### Writing L1 from the model — `memory$remember`

The LLM-facing `memory$remember` command (`agent/common/commands.clj`)
writes a single L1 entry when called with `:layer "l1"`. It accepts two
L1-only inputs that give the entry a canonical, addressable identity:

| Input | Meaning |
|---|---|
| `:field` | Overlay field — groups entries assembled into one prompt fragment. For `:kind "system-context"`: `instruction` \| `agent-context` \| `tool-context`. For `:kind "user-context"` (the L1 default): an arbitrary grouping key (e.g. `preferences`, `notes`). |
| `:section` | Section name within `:field`. Entries sort by it and render as `### <section>` inside the assembled fragment. |

`:field` lands in the entry's `:data {:field …}` (coerced to a keyword,
matching how `read-entries`/`assemble-field` filter), `:section` in
`:data {:section …}` (kept as a string). When **both** are present the id
is derived via `memory/l1-entry-id` as `{kind}/{field}/{section}`, so a
repeat write **upserts** the same overlay instead of accumulating
random-uuid rows. Omitting both yields a freeform pin with a generated
uuid id (the legacy behaviour). **Both kinds are open to the model** —
`memory$remember` does not restrict `:field`/`:section` writes to
`:user-context`, so the LLM can author `:system-context` overlays with
canonical ids as well.

---

## Sandbox notes — removed (L1 simplification refactor)

> The LLM-curated note bindings (`remember-note` / `get-note` /
> `list-notes` / `forget-note` / `clear-notes`) and their `notes-snapshot`
> reader were the **only writer of `:kind :note` L1 entries**, and were
> removed when L1 was simplified to the `:system-context` / `:user-context`
> kinds. The model-facing path that replaced them is `memory$remember`
> with `:field`/`:section` (see *Writing L1 from the model* above), not a
> sandbox binding. The stable-id helper is now `memory/l1-entry-id` (the
> old `note-id` is gone).

The model curates session-scoped context directly via `memory$remember`
at L1, and durable memory (L2/L3) through the capture pipeline plus
explicit `promote-entry` calls — no sandbox note binding is involved.

`(def …)` snapshots remain, and are
**deliberately ephemeral**: extracted from each iteration's code blocks
and held under `:sandbox-state` on `!session` for restore at the next
iteration. `(def …)` snapshots are *not* memory entries — treating
arithmetic intermediates as durable memory would pollute the chronicle.

---

## Capture pipeline (inspired by OpenChronicle)

The pipeline separates raw event capture (S0), structural parsing (S1),
and reduction into durable timeline blocks (S2):

```
hook event ──► S0 dispatcher ──► S1 parser ──► sidecar thread ──► L2
                (debounce/dedup)  (extract entry)                  └─► S2 reducer ──► L3
                                                                        (periodic / on demand)
```

- **S0 — Dispatcher** (`memory.core.capture.dispatcher`) subscribes to
  lifecycle events from `agent.core.hooks` via `requiring-resolve` (no
  agent → memory cycle). Critical events (`:agent.ask/pre`,
  `:agent.ask/post`, `:agent/exception`) go to a large fixed buffer that
  never drops. Other events go to a `sliding-buffer` channel with a
  dedup transducer over a 30-event window.
- **S1 — Parser** (`memory.core.capture.parser`) is a pure multimethod
  by `:event-key`. Each event becomes an L2 episode with populated
  `:sources` and inferred `:tags` (`event:`, `kind:`, `role:`, `tool:`,
  `topic:` — used for cheap recall filtering).
- **Sidecar** (`memory.core.capture.sidecar`) runs one `core.async/thread`
  per `MemoryManager`. `alts!! :priority true` drains the critical
  channel first so the chronicle never adds tail latency to a turn.
- **S2 — Reducer** (`memory.core.capture.reducer`) groups by tag-set +
  10-min window. Default reducer is a deterministic heuristic (longest
  common prefix or templated summary) so consolidation costs nothing and
  is reproducible. An optional `:reducer :llm` slot is wired but
  currently warns and falls back to the heuristic. Auto-marks source
  episodes `keep_flag=1` so the L3 `:sources` chain stays valid forever.

The pipeline is gated behind `start-capture!`, driven by the
`:enable-memory-capture` config key, which **defaults to `true`** in
`agent.core.config`'s schema (so capture is on for every agent unless a
config explicitly disables it). `coact-agent` no longer needs a
`:config-extra` override — the keys are commented out there and it
inherits the schema default.

---

## Recall pipeline

`memory.core.recall-v2/recall-layered` queries each layer in parallel
via `pmap`, applies layer-specific weights, merges with RRF, and renders
a layered briefing:

```
## System Context  (L1 :system-context entries for this session)
## User Context    (L1 :user-context entries for this session)
## Recent Events   (L2 episodes — chronicle)
## What We Know    (L3 facts — semantic memory)
```

Each line carries an entry-id so the agent can request expansion. The
briefing rides the LLM via `:recalled-memory` on the user message; the
stable `:system-context` / `:user-context` channels carry knowledge
sections separately.

`recall-flat` preserves the legacy `{:facts :episodes :combined}` shape
for callers that have not yet migrated.

### RRF defaults

```
score_i = Σ weight_l / (k + rank_l)
k       = 60      ;; :rrf-k default
weights = {:l1 0.3 :l2 0.4 :l3 0.6}   ;; default-weights, tunable via :weights
```

L1 (system + user context) ranks lowest, L3 (distilled facts) highest.
Default fan-out is `default-layers [:l1 :l2 :l3]`.

Layers with no hits contribute nothing — RRF degrades gracefully.

---

## Promotion and forgetting

- **Promotion.** An episode becomes a fact when (a) the model
  calls `(memory/promote-entry store entry :l2 :l3)` explicitly, (b) the reducer
  consolidates ≥ N related episodes, or (c) `accessed_count` crosses a
  threshold. Promotion preserves provenance: the new L3 entry's
  `:sources` points to the L2 rows it was derived from.
- **Forgetting.** L1 expires at session end. L2 has default retention
  of **30 days**, with an explicit `:keep` flag (set by the user, the
  agent, or the reducer when consolidation succeeds) that pins
  landmark sessions indefinitely. L3 is never auto-deleted but can be
  `:archived` and excluded from default recall. `forget-entry` always
  writes a tombstone so audits remain reproducible.

`memory.core.policy` exposes `mark-keep!`, `mark-archived!`,
`tombstone!`, `sweep-l2!`, and scheduled `start-sweeper!` /
`stop-sweeper!` (low-latency teardown).

---

## Audit

Every entry that touches a prompt is logged via μ/log with the full
addressing tuple `{:user-id :session-id :turn-id :entry-id :layer :byte-cost}`.

`memory.core.audit/record-prompt!` writes one `memory_audit` row per
entry per turn. The public API hydrates audit rows via `read-entries`
with `:include-archived` / `:include-tombstoned` so the trail survives
forgets:

```clojure
(memory/explain         session-id turn-id)
(memory/explain         turn-id)                ;; current session
(memory/explain-session session-id)             ;; whole conversation
;; => {:session-id … :turn-id … :user-id …
;;     :entries [{:id … :layer :l3 :kind :fact :content "…" :sources [...]} …]
;;     :prompt-bytes 12 843
;;     :recall-query "…"}
```

This makes "why did the agent say that on turn 7 of session X?" a
one-liner during debugging.

---

## Mutation-safety notes

- **L1 writes** go through `swap!`-equivalent atomic operations. (The
  per-layer key/byte quotas were removed in the L1 simplification
  refactor, so there is no longer a quota check inside the callback.)
- **Knowledge sections** are now read via a single `read-entries` call
  per turn (TOCTOU fix from the implementation) rather than the
  deref-then-iterate pattern.
- **L1 entries** are addressed by a stable `entry-id` (`memory/l1-entry-id`),
  so concurrent writers update by id rather than clobber.
- **Sandbox `(def …)` snapshots** remain at `:sandbox-state` on
  `!session`. They are excluded from the unified memory migration; the
  multi-ask sharing race remains a concern of the sandbox machinery
  itself, not of memory.

---

## Public API surface

`components/memory/src/ai/brainyard/memory/interface.clj`:

```
create-memory-manager  create-store   ;; factory (manager / UnifiedStore)
store                                  ;; (:store manager) accessor
write-entry  read-entries
promote-entry  forget-entry            ;; wrap proto/promote, proto/forget
contextual-recall                      ;; cross-layer RRF recall
l1-entry-id                            ;; stable id helper (replaces note-id)
get-stats  initialize  shutdown        ;; MemoryManagerLifecycle
normalize-fts-query  extract-keywords  ;; FTS helpers
start-capture!  stop-capture!  capture-running?
consolidate-l2!                        ;; run the S2 reducer on demand
keep!  unkeep!  archive!  unarchive!  sweep-l2!
start-sweeper!  stop-sweeper!
explain  explain-session
```

Higher-level helpers in `components/agent/src/ai/brainyard/agent/core/memory.clj`
wrap the store for the agent runtime: `recall`, `remember`, and
`create-memory-manager`. (On-demand L2→L3 consolidation is the store-level
`memory/consolidate-l2!`, not an `agent.core.memory` helper.)

---

## File map

| File | Purpose |
|---|---|
| `memory/interface.clj` | Public API |
| `memory/interface/protocol.clj` | `IMemoryStore` protocol |
| `memory/core/entry.clj` | Entry schema + DB row adapters |
| `memory/core/l1_store.clj` | In-memory L1 store (`:system-context` / `:user-context` kinds) |
| `memory/core/episodic.clj` | L2 SQLite store |
| `memory/core/semantic.clj` | L3 SQLite store |
| `memory/core/unified_store.clj` | Composite store dispatching by layer |
| `memory/core/manager.clj` | `MemoryManager` lifecycle + factory wiring |
| `memory/core/recall_v2.clj` | Layered recall (per-layer pmap + RRF merge + briefing) |
| `memory/core/recall.clj` | Legacy flat recall (kept for back-compat) |
| `memory/core/capture/dispatcher.clj` | S0 — hook subscription, debounce/dedup |
| `memory/core/capture/parser.clj` | S1 — event → entry multimethod |
| `memory/core/capture/sidecar.clj` | core.async thread; priority drain of critical events |
| `memory/core/capture/reducer.clj` | S2 — heuristic / optional LLM consolidator |
| `memory/core/policy.clj` | Retention, archive, tombstone, scheduled sweep |
| `memory/core/audit.clj` | `memory_audit`, `explain`, `explain-session` |
| `memory/core/sqlite.clj` / `fts.clj` | SQLite + FTS5 plumbing |
| `agent/core/context.clj` | Knowledge sections + assembly |
| `agent/core/memory.clj` | Agent-side recall / remember helpers |
| `agent/common/sandbox_bindings.clj` | Sandbox bindings (the L1 note API was removed in the simplification refactor) |
| `clj-sandbox/core/sandbox_state.clj` | Ephemeral `(def …)` snapshots — **not** memory |
