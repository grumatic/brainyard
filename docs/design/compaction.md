# Context compaction design

How brainyard keeps an agent's prompt under the model's context budget —
**deterministically, with no LLM call** — and how it pre-shrinks the carryover
that flows from one turn to the next.

Compaction operates at three points in time, all sharing one budget notion and
gated by the single `:enable-context-budget` knob:

1. **Turn start** — `context-budget/enforce` runs over the freshly assembled
   sections in `coact-init-action`, before the first LLM call.
2. **Mid-turn** — `enforce` re-runs every *N* iterations (`coact-rebudget-action`)
   as the `:iterations` trace grows within a turn.
3. **Between turns** — `context-compaction/compact-context!` proactively
   compresses the persisted carryover toward a tighter target (manual `/compact`
   or the after-turn auto-compaction).

A fourth, related step shapes the carryover *as it is stored*:
`previous-turns/append-turn` applies progressive depth compression when a
finished turn is folded into the chain.

This document covers all four. For where the assembled sections come from and
what the model ultimately sees, see [context-management.md](context-management.md);
for the live-artifacts section specifically, [artifacts.md](artifacts.md).

## The budget

`context-budget/model->budget` resolves the maximum allowable *prompt* tokens:

```
usable = max-context-tokens − max-output-tokens      ; reserve room for the reply
budget = usable − ceil(usable × safety-ratio)        ; extra headroom
```

With defaults (`max-context-tokens` 128000, `max-output-tokens` ≈ 4096,
`safety-ratio` 0.10) the budget is ≈ 111 500 prompt tokens. Token counts are the
`clj-llm` chars/4 estimate throughout the budget path (fast, deterministic).

## Section-budget enforcement (`enforce`)

The core reducer is `agent.core.context-budget/enforce`. It takes the section
map, a render order, the budget, a policy table, and a map of strategy closures,
and returns refined sections plus `:total-tokens`, `:over-budget?`, and a
`:compactions` audit trail.

### Compactable vs immutable

Each section has a policy in `default-section-policies` with a `:priority` and an
optional `:compact` strategy key:

```
;; system-context — immutable (no :compact); never trimmed
:role 100  :footer 100  :execution-model/:channel-routing/... 99
:tools 90 (:tools-tier)  :critical-rules 95  :instruction 95  ...

;; user-context — volatile (strategies live here)
:turn-info            88
:project-instructions 85   :user-instructions 85
:live-artifacts       70   :compact :drop-live-artifacts  :keep-floor? true
:conversation-history 60   :compact :shrink-conversation
:parent-trail         55   :compact :bump-parent-trail
:previous-turns       50   :compact :bump-previous-turns
:iterations           50   :compact :collapse-iterations   ; budget-only slot
```

Sections without a `:compact` strategy are immutable for the turn. If the budget
can't be met without them, `enforce` reports `:over-budget? true` and the turn
proceeds anyway (with a `mulog/warn`) — the safety margin keeps that below the
real context window.

### The loop

While `total-tokens > budget`, `enforce` repeatedly picks the **lowest-priority**
compactable section and applies its strategy, with three outcomes per pass:

- **Progress** (token count dropped) → keep going on the same candidate set. A
  strategy is hammered until it can't reduce further (it drains its underlying
  state and removes its own section), then the next-priority section is tried.
- **No progress + `:keep-floor?`** → keep the section's remaining floor content
  and retire it from compaction (recorded `:kept-floor`). Used for
  `:live-artifacts` so pinned/system reference docs are never dropped wholesale.
- **No progress, no floor** → drop the whole section (`:dropped`) to free its
  bytes and break the loop.

A `retired` set guarantees termination; `:max-passes` (32) is a backstop. Every
pass appends `{:section :strategy :before-tokens :after-tokens :delta}` to
`:compactions`, and each `enforce` call fires `:agent.context/budgeted` (phase
`:init` or `:rebudget`) carrying the compactions, section-tokens, and
`:over-budget?` for observability.

### The strategy contract

A strategy is `(fn [sections] -> sections')`. It mutates the relevant slice of
`@st-memory` (so the trimmed state is what the LLM actually sees via DSPy) and
returns the section map with the affected section re-rendered (or removed). The
closures live in `coact-strategies` (`coact_agent.clj`):

| Strategy | Section | Effect |
|---|---|---|
| `:bump-previous-turns` | previous-turns | drop the oldest turn, re-render; dissoc when empty |
| `:bump-parent-trail` | parent-trail | drop the oldest parent turn (sub-agents only) |
| `:shrink-conversation` | conversation-history | drop the oldest 2 messages |
| `:drop-live-artifacts` | live-artifacts | evict the oldest **droppable** artifact (not `:pinned?`, not `:origin :system`); pinned/system held as the floor — see [artifacts.md](artifacts.md) |
| `:collapse-iterations` | iterations | keep the last 3 iterations verbatim, replace older ones with one deterministic summary record |
| `:tools-tier` | tools | disable successive tool sub-tiers (usage guides → overlay → function index → agent-tools detail), cheapest first |

Order matters: `previous-turns`/`iterations` (50) are sacrificed before
`conversation` (60), then `live-artifacts` (70), then `tools` (90). Cheap
carryover goes before structural context.

### When it runs

- **`coact-init-action`** — once per turn, after section assembly. `:iterations`
  is empty here, so the init pass works on previous-turns / conversation /
  live-artifacts / tools.
- **`coact-rebudget-action`** — every `:rebudget-every-n-iter` iterations
  (default 10). It refreshes the `:iterations` section text from current state
  and re-runs `enforce` with the same orders/strategies, then re-composes
  `:system-context` / `:user-context`. `:iterations` is a **budget-only slot**:
  it is counted for the budget but never composed into the prompt strings (the
  LLM sees the iteration vector via the DSPy signature, not the section text).

ReAct uses the same machinery — `enforce` at init plus `react-rebudget-action`
on the same cadence; see [context-management.md](context-management.md) §3 for
its tree.

## Cross-turn compaction (`compact-context!`)

`agent.common.context-compaction/compact-context!` runs **between** turns to keep
the persisted carryover lean, so the next turn starts well under budget instead
of relying on the mid-turn reducer to claw tokens back. It is deterministic (no
LLM), mutates `st-memory-init` in place, and targets
`:compaction-target-ratio × :max-context-tokens` (default 0.2 → ≈ 25 600 tokens).

It compresses the dominant cross-turn slot, `:previous-turns`, via
`compact-previous-turns` — progressively tighter passes with early-exit once
under target:

```
pass 1  full=3 summary=10  answer≤2000
pass 2  full=1 summary=5   answer≤1000
pass 3  all minimal        answer≤400
pass 4  all minimal        answer≤200
last    drop oldest, keep the most recent 10 turns
```

`estimate-context-tokens` prefers the **actual** input-token count from the last
LLM call (most accurate), falling back to a chars/4 estimate over `st-memory-init`.
Because the path only mutates the `:previous-turns` slot, the reported
`after-tokens` is derived from the measured slot reduction rather than a stale
re-estimate.

**Triggers:**

- **Manual** — the `/compact` slash command → `compact-context!` with
  `:trigger :manual`.
- **Auto** — `maybe-auto-compact!` after a turn completes: when
  `estimate-context-tokens > :max-context-tokens`, fire `compact-context!` with
  `:trigger :auto`. Gated by `:enable-context-budget`.

It fires `:agent.compaction/pre`, `:agent.compaction/phase` (`:phase
:previous-turns`, `:status :start`/`:done`), and `:agent.compaction/post`, and
returns
`{:before-tokens :after-tokens :compacted-keys :duration-ms :trigger}` (or
`{:already-compact true …}` when already under target).

## Carryover shaping: `previous-turns` depth tiers

When a turn finishes, `coact-store-results-action` folds its iteration trace +
answer into the chain via `previous-turns/append-turn`, which assigns a depth by
recency:

- **`:full`** — recent N (default 10): question + iterations + answer.
- **`:summary`** — next M (default 30): question + answer (truncated ~4000),
  iterations dropped.
- **`:minimal`** — older: question + short answer (~1600), capped at
  `:max-turns` (default 100).

`format-previous-turns` then renders these into `:user-context` (keeping the last
3 iterations of each `:full` turn). This is the baseline shape; `compact-context!`
re-applies the same idea with tighter parameters when carryover must shrink
further, and `:bump-previous-turns` drops whole turns under live budget pressure.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `:enable-context-budget` | `true` | Master switch for *all* compaction (init + rebudget + auto cross-turn). |
| `:max-context-tokens` | `128000` | Model context window; basis for budget and cross-turn target. |
| `:context-budget-safety-ratio` | `0.10` | Extra headroom subtracted from the prompt budget. |
| `:compaction-target-ratio` | `0.2` | Cross-turn target as a fraction of `:max-context-tokens`. |
| `:rebudget-every-n-iter` | `10` | Mid-turn `enforce` cadence (iterations). |

The mid-turn output reservation comes from `lm-config :max-tokens` (default
~4096); per-artifact / per-field truncation caps (`:live-artifact-max-chars`,
`:max-output-chars`) are documented with their features.

## Key files

| File | What it holds |
|---|---|
| `components/agent/src/.../core/context_budget.clj` | `model->budget`, `default-section-policies`, the `enforce` loop, `:keep-floor?` |
| `components/agent/src/.../common/coact_agent.clj` | `coact-strategies` (the strategy closures), `coact-init-action` (init enforce), `coact-rebudget-action` (mid-turn enforce), `summarize-iterations-deterministic` |
| `components/agent/src/.../common/context_compaction.clj` | `compact-context!`, `compact-previous-turns`, `estimate-context-tokens` |
| `components/agent/src/.../common/previous_turns.clj` | `append-turn` depth-tier compression |
| `bases/agent-tui/src/.../core.clj` | `maybe-auto-compact!` (after-turn trigger) |
| `bases/agent-tui/src/.../commands.clj` | the `/compact` command |

## Design decisions

- **Deterministic, no-LLM compaction.** Every reducer here is a pure
  transformation — predictable cost, no latency, no second model failure mode.
  (LLM-summarized compaction was considered and deliberately kept out of the hot
  path.)
- **Priority-ordered sacrifice.** The section priorities encode "what to lose
  first": stale conversational carryover before structural context, tools last,
  immutable system rules never.
- **Two scales, one notion of budget.** `enforce` governs the *live prompt* each
  turn/iteration; `compact-context!` pre-shrinks the *persisted carryover*
  between turns. They share the budget math and the previous-turns compression
  idea but act on different stores (`st-memory` vs `st-memory-init`).
- **Keep-floor for protected content.** A section can declare `:keep-floor?` so
  its irreducible remainder (e.g. pinned live artifacts) is kept rather than
  dropped wholesale as a loop-breaking last resort.
- **Budget-only accounting slots.** `:iterations` (and `:thoughts`/`:observations`)
  are counted toward the budget and compacted, but their text is never composed
  into the prompt — the model sees those vectors through the DSPy signature.
