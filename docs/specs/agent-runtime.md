# Spec: Agent Runtime

*Area code `RT`. Covers the `agent` component's core: the `Agent` record
and its protocols, the ask/lifecycle state machine, cancellation and
cooperative pause, multi-agent session sharing and delegation caps, the
hooks registry, and the input queue. Sibling specs:
[behavior-tree](behavior-tree.md), [reasoning](reasoning.md),
[tool-system](tool-system.md).*

Status legend and contract-ID conventions: see [README](README.md).

---

## 1. Identity & protocols

The runtime is built around an `Agent` defrecord holding only an id plus
two atoms — its private state and a **shared** session atom — so that
sub-agents can inherit a parent's session by reference rather than copy.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-RT-01 | An agent MUST be identified by an `agent-id` keyword of the form `:<defagent-type>/<suffix>`; its `defagent-type` MUST be recoverable as `(namespace agent-id)`. | Implemented | `Agent` defrecord, `agent/core/agent.clj` |
| CR-RT-02 | The record MUST hold only `[agent-id !state !session]`; `user-id`/`session-id` MUST be read live from the shared `!session` atom, never cached on the record. | Implemented | `agent.clj` (`proto/user-id`, `proto/session-id`) |
| CR-RT-03 | The agent MUST implement the protocol set `IAgent`, `IAgentLifecycle`, `IAgentState`, `IAgentBTIntegration`, `IAgentMemoryAccess`, and every declared method MUST be satisfied on the record. | Implemented | `agent/core/protocol.clj`, `agent.clj` |

The protocol surface is the contract boundary other components depend
on: `IAgent` (`process`, `get-tools`, `get-state`, identity accessors),
`IAgentLifecycle` (`start-agent`, `stop-agent`, `agent-running?`,
`clone-agent`), `IAgentState` (state get/set, `get-bt`,
`get-st-memory-init`), `IAgentBTIntegration` (`update-session-data`,
`check-run-cancelled?`, `check-run-paused?`, `await-resume`, action-promise
and action-permission accessors, `get-bt-st-memory`), and
`IAgentMemoryAccess` (`get-memory-manager`).

---

## 2. Lifecycle state machine

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-RT-04 | Agent `:status` MUST be one of `{:created :idle :running :paused :cancelled :stopped}`. | Implemented | `agent.clj` status constants |
| CR-RT-05 | `start-agent` MUST transition to `:idle` and register the instance; `stop-agent` MUST cancel any run, transition to `:stopped`, and unregister. | Implemented | `agent.clj` |
| CR-RT-06 | `agent-running?` MUST report true for `{:idle :running :paused :cancelled}` (i.e. anything not `:created`/`:stopped`). | Implemented | `agent.clj` (`agent-running?`) |
| CR-RT-07 | `close` (Closeable) MUST detach the activity watch and fire `:agent.instance/closed`, and MUST stop the memory capture pipeline **only** when no sibling agent shares the same memory manager. | Implemented | `agent.clj` (`close`) |

---

## 3. The ask cycle

`ask` is the single entry into a turn. Its contract is what keeps the UI
and audit log coherent across normal completion, sub-agent turns, and
mid-flight cancellation.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-RT-08 | `ask` MUST reject nil/blank input with an `ex-info`, reset the runtime, and set status `:running` before processing. | Implemented | `agent.clj` (`ask`) |
| CR-RT-09 | Each `ask` MUST bump the per-agent `:turn-id` and the session `:total-turns` audit index. | Implemented | `agent.clj`, `agent/core/session.clj` |
| CR-RT-10 | A root agent MUST clear `:data` and write the user message; a sub-agent MUST instead write an assistant message tagged with its parent id. | Implemented | `agent.clj` |
| CR-RT-11 | `ask` MUST fire `:agent.ask/pre` before and `:agent.ask/post` after `process`, and MUST return status to `:idle`. | Implemented | `agent.clj` |
| CR-RT-12 | On exception mid-turn, `ask` MUST still set `:idle` and fire both `:agent/exception` and `:agent.ask/post` so the UI un-sticks even when an LLM call is cancelled. | Implemented | `agent.clj` (exception arm) |
| CR-RT-13 | `ask-async` MUST serialize back-to-back turns through a single Clojure agent (`send-off`, `:error-mode :continue`) and record the executing thread for interrupt. | Implemented | `agent/core/runtime.clj` (`ask-async`) |

---

## 4. Cancellation, pause, resume

Cancellation and pause are **cooperative and non-preemptive**: they land
at the next behavior-tree checkpoint, and an in-flight LLM call is
allowed to finish. This is a deliberate contract — see
[behavior-tree](behavior-tree.md) CR-BT-09 for the checkpoint side.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-RT-14 | `cancel-run` MUST set the cancelled flag, signal the pause condition, close the active HTTP stream, then interrupt the executing thread/future. | Implemented | `runtime.clj` (`cancel-run`) |
| CR-RT-15 | `cancelled?` and `paused?` MUST walk parent agents recursively, so a parent's cancellation propagates to sub-agents. | Implemented | `runtime.clj` |
| CR-RT-16 | Pause MUST park the run on a lock+condition, saving the prior status under `[:runtime :pre-pause-status]`; `wait-if-paused` MUST return `:running`, `:resumed`, or `:cancelled`. | Implemented | `runtime.clj` (`pause-run`, `wait-if-paused`) |
| CR-RT-17 | Human-in-the-loop action permissions MUST use promises (`create-action-promise` / `deliver-action-response`) persisted under `[:runtime :action-permissions]`. | Implemented | `runtime.clj` |

---

## 5. Sessions, multi-agent, delegation caps

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-RT-18 | A sub-agent MUST inherit the parent's `!session` by reference (shared session), not a copy. | Implemented | `agent.clj` (instance creation) |
| CR-RT-19 | Sessions MUST be reachable through an `ISessionStore` (get/set/delete/list), with an in-memory store as default. | Implemented | `session.clj` (`ISessionStore`, `InMemorySessionStore`) |
| CR-RT-20 | `clone-agent` MUST snapshot state, share the same `!session`, and mark the clone `:cloned? true`. | Implemented | `agent.clj` (`clone-agent`) |
| CR-RT-21 | Sub-agent st-memory display-stage changes MUST forward to the session `:agent-activity` log, capped at 200 entries. | Implemented | `session.clj` |
| CR-RT-22 | Delegation MUST be bounded: a kill-switch (`:enable-subagent-calls`), a depth cap (`:max-agent-call-depth`, default 3) via `*call-depth*`, and circular-call detection via `*call-chain*`. | Implemented | enforced in `agent/core/tool.clj` (`do-call-tool--agent`) |

**Note on CR-RT-22 (Partial-by-location, not by behavior):** the dynvars
`*call-depth*` / `*call-chain*` are *declared* in `protocol.clj`, but the
caps are actually *enforced* in the tool-dispatch layer, not in the
runtime. The behavior is fully present; the spec attributes enforcement
to [tool-system](tool-system.md) CR-TOOL-08 so the obligation isn't
mistakenly sought in `runtime.clj`.

---

## 6. Hooks registry

The hooks registry is the extension substrate: observers and gates keyed
on a fixed event catalog.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-RT-23 | The registry MUST support `register-hook!`, `unregister-hook!`, `unregister-source!`, `reset-hooks!`, `list-hooks`. | Implemented | `agent/core/hooks.clj` |
| CR-RT-24 | `fire!` MUST be an observer broadcast (return values ignored); `fire-decision!` MUST be gated, walking priority order so the first non-`:allow` decision wins. | Implemented | `hooks.clj` (`fire!`, `fire-decision!`) |
| CR-RT-25 | A decision `:result` MUST be one of `{:allow :block :replace :modify-args}`; a nil/non-decision return MUST be treated as `:allow`. | Implemented | `hooks.clj` |
| CR-RT-26 | The event catalog MUST be enumerable, and only events explicitly flagged `:gates? true` (currently `:agent.tool-use/pre`) may be fired as decisions. | Implemented | `hooks.clj` (`event-catalog`) |
| CR-RT-27 | Gated-decision firing (`fire-decision!`) SHOULD be reachable through the public `agent` interface alongside `fire!`. | **Partial** | only `fire!` re-exported in `agent/interface.clj` |
| CR-RT-32 | The gated set MUST include a second event, `:agent.ask/finalize`, fired after the result map is built but before `:agent.ask/post` and the session message; a handler MAY return a `:replace` whose `:replacement` is the new result, which specialists use to inject the auto-persist `Saved <kind>: <path>` handoff line the LLM omitted. | Implemented | `hooks.clj` (`event-catalog` `:agent.ask/finalize :gates? true`); consumers in `debug_agent.clj`, `rlm.clj`, `exec.clj`, `workflow.clj`, `plan.clj`, `eval.clj`, `research.clj`, `todo.clj` |
| CR-RT-33 | A shared write-guard MUST gate durable agent writes (dossiers, plans, BRAINYARD.md, memory/state) by scanning for secrets and enforcing a size cap, returning a hook decision that blocks or redacts. | Implemented | `common/guard.clj` (`write-guard-decision`, `install-write-guard!`, `scan-secrets`); installed for plan/exec/eval/workflow/init/config/memory writes |

**CR-RT-26 / CR-RT-32 note:** CR-RT-26's parenthetical "(currently
`:agent.tool-use/pre`)" predates the finalize gate; as of CR-RT-32 the
`:gates? true` set is `{:agent.tool-use/pre :agent.ask/finalize}`. The
CR-RT-26 contract (only `:gates? true` events may be fired as decisions)
still holds — only its example enumeration was extended.

**CR-RT-27 (Partial):** `fire-decision!` and `gated-event?` exist and are
used internally (the loop-guard rides `:agent.tool-use/pre`), but the
public `agent/interface.clj` re-exports only `fire!`. A consumer that
wants to fire a gated event today must reach into `agent.core.hooks`
directly. Candidate TODO: either re-export the gated API or document that
gated firing is intentionally internal.

The catalog spans ~35 events including `:agent.session/*`,
`:agent.instance/*`, `:agent.ask/*`, `:agent.iteration/{pre,post,exhausted}`,
`:agent.dspy-action/{pre,chunk,post}`, `:agent.tool-use/{pre,post}`,
`:agent.tool-calls/{pre,post}`, `:agent.code-eval/{pre,post}`,
`:agent.compaction/{pre,phase,post}`, `:agent.evaluation/*`,
`:agent.analytics/post`, `:task/{created,completed}`, `:todo/updated`,
`:agent/exception`. Match helpers (`match-all/any/agent-id/defagent-type/
root-agent`) let a hook target a subset of agents.

---

## 7. Input queue

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-RT-28 | The input queue MUST be FIFO, capped at 10, drained by a single worker; `enqueue!` MUST return `{:error :queue-full}` at cap. | Implemented | `agent/core/queue.clj` |
| CR-RT-29 | Items MUST carry status `:queued` / `:processing` and emit `:enqueued :processing :completed :error :cancelled :queue-empty` events. | Implemented | `queue.clj` |
| CR-RT-30 | `cancel-item!` / `cancel-all-queued!` MUST remove only `:queued` items, never an in-flight one. | Implemented | `queue.clj` |

---

## 8. Public interface surface

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-RT-31 | `agent/interface.clj` MUST re-export the protocol set, message helpers, factory/lifecycle fns, BT integration + HITL actions, session management, the hooks API, the queue API, runtime cancel/pause, config, turn-log, sandbox-meta, MCP, trajectory, and task-management fns. | Implemented | `agent/interface.clj` |

The interface is the only sanctioned dependency surface; built-in
`defagent` namespaces are `require`d for their registration side-effects.
The one known omission is the gated-hook API (CR-RT-27).

---

## Gaps & candidate TODOs (this spec)

- **CR-RT-27 — gated-hook API not public.** `fire-decision!`/`gated-event?`
  are internal-only. Decide: re-export, or document as intentionally
  internal. *(Small.)*
- **CR-RT-22 — delegation-cap enforcement location.** Behavior is correct
  but enforcement lives in the tool layer while the dynvars are declared
  in `protocol.clj`. Consider a docstring/cross-reference so future
  readers don't look for the cap in the runtime. *(Doc-only.)*

No `TODO`/`FIXME`/stub markers were found in `agent/core` or the BT
engine; the runtime is otherwise fully implemented against its contracts.
