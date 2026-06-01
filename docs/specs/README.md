# Brainyard Specifications

*Behavioral-contract specifications for the Brainyard agent runtime and
terminal UI (the `by` binary), derived bottom-up from the source as it
stands. Where the `core/`, `tui/`, and `design/` docs **describe** how
the system is built, these specs **state what the system must do** — as
named, testable contracts — and tag how far the implementation has gotten.*

---

## Why this layer exists

The `docs/core/` reference explains the subsystems; `docs/design/`
captures proposals and migration history; `docs/tui/` covers the
terminal substrate. None of them give a single answer to the question
*"what is this subsystem contractually obliged to do, and which of those
obligations are actually met today?"*

These spec docs fill that gap. Each is a list of **behavioral
contracts** — invariants and required behaviors abstracted up from the
real code (code is ground truth, not docstrings) — and each contract
carries a status tag. The gaps fall out directly: anything tagged
**Partial** or **Missing** is a candidate TODO.

Scope of this first pass is **the agent runtime + the TUI** — the
reason-for-being of the `by` binary. Peripheral components (ML, AWS,
Slack, web frameworks, data/migration) are out of scope here and can get
their own spec pass later.

---

## Status legend

| Tag | Meaning |
|---|---|
| **Implemented** | The contract holds in the current code, exercised on the live path. |
| **Partial** | Partly met — a documented limitation, a default-stub backend, a "phase 1" path, or a by-design asymmetry. The contract text says what is *missing* from full satisfaction. |
| **Missing** | The contract is declared/reserved/promised somewhere but no live code satisfies it. |

A contract being **Partial/Missing** is not necessarily a bug — several
are deliberate phasing decisions. The status records *reality*, and the
candidate TODO list is where we decide which gaps are worth closing.

---

## Contract IDs

Every contract has a stable ID of the form `CR-<AREA>-<n>` (e.g.
`CR-RT-04`). IDs are referenced by the candidate TODO list so a task can
point back to the exact obligation it closes. IDs are append-only:
retire a contract by marking it, don't renumber.

Area codes:

| Code | Spec |
|---|---|
| `RT` | [Agent runtime](agent-runtime.md) |
| `BT` | [Behavior tree](behavior-tree.md) |
| `RSN` | [Reasoning loops](reasoning.md) |
| `TOOL` | [Tool system](tool-system.md) |
| `MEM` | [Memory & context](memory-and-context.md) |
| `TASK` | [Task manager](task-manager.md) |
| `CFG` | [Configuration](configuration.md) |
| `TUI` | [Terminal UI](tui.md) |

---

## How TODOs are derived

1. Read each spec; collect every contract tagged **Partial** or **Missing**.
2. For each, decide: close the gap, accept it as permanent (re-tag with a
   rationale), or split it into smaller contracts.
3. The accepted gaps become TODO tasks, each citing its `CR-…` ID.

The candidate list lives in [`candidate-todos.md`](candidate-todos.md)
and is meant to be reviewed before anything is committed to a tracker.

---

## Methodology & provenance

- **Bottom-up, code as truth.** Every contract was abstracted from
  reading the implementation, not from prose docs. Symbol, file, and
  config-key names were grep-verified before being written down.
- **Worked core → tui.** The runtime/reasoning/tool/memory/task/config
  specs first, the TUI spec last.
- Gaps were found by reading the code and by searching for `TODO`,
  `FIXME`, `stub`, `placeholder`, `not implemented`, "don't run yet",
  "future v2", and demotion markers. Note that this codebase expresses
  most limitations in **prose** ("not supported yet") rather than tag
  comments, so the gap lists below come mostly from reading intent.
- Authored against the tree as of **2026-05-24**. Specs drift as code
  moves; re-audit when a subsystem changes materially.

---

## The specs

1. [Agent runtime](agent-runtime.md) — agent record, protocols, the
   ask/lifecycle state machine, cancellation/pause, multi-agent &
   delegation caps, hooks, the input queue.
2. [Behavior tree](behavior-tree.md) — node types and tick semantics,
   shared st-memory, the agent-layer tracing/cancellation overrides,
   HITL nodes, DSPy actions.
3. [Reasoning loops](reasoning.md) — the CoAct three-channel loop, the
   ReAct loop, code-execution backends (`:sandbox` / `:nrepl`), the
   fenced-block contract.
4. [Tool system](tool-system.md) — the `!tool-defs` registry,
   `deftool`/`defcommand`/`defskill`/`defagent`, dispatch + hooks +
   visibility, MCP surfacing, sub-agents-as-tools, sandbox auto-binding.
5. [Memory & context](memory-and-context.md) — the per-iteration state
   memory, the long-term FTS5 store (capture/recall/RRF), context-budget
   enforcement and compaction.
6. [Task manager](task-manager.md) — background executors, the detached
   async model, ring-buffered output, on-disk persistence, scheduling.
7. [Configuration](configuration.md) — the config schema, the precedence
   chain, persisted-EDN shape, directory resolution.
8. [Terminal UI](tui.md) — run modes, the hook-driven render/session
   loop, slash commands, permissions/HITL, session persistence, the
   tmux substrate, display-blocks, environment detection.

---

## Conventions in the contract tables

Each spec lists contracts as a table: **ID · Contract · Status · Source**.
The *Contract* column states the obligation in must/should language. The
*Source* column cites the authoritative symbol and file (relative to
repo root) so the contract can be checked against code. Narrative around
the table explains nuance and records why a Partial/Missing tag was
assigned.
