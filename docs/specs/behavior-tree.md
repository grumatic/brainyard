# Spec: Behavior Tree

*Area code `BT`. Covers the `behavior-tree` component (engine, node types,
DSPy action) and the agent-layer node overrides in
`agent/core/bt.clj` that add tracing, cancellation checkpoints, and HITL.
The BT is the central execution substrate: ReAct and CoAct loops are both
subtrees of one tree. Sibling specs:
[agent-runtime](agent-runtime.md), [reasoning](reasoning.md).*

Status legend and contract-ID conventions: see [README](README.md).

---

## 1. Status & dispatch

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-BT-01 | A tick MUST return one of `success` / `failure` / `running` (`:success`/`:failure`/`:running`). | Implemented | `behavior_tree/interface/protocol.clj` |
| CR-BT-02 | Nodes MUST dispatch through two multimethods keyed on `(:type node)`: `build` and `tick`. | Implemented | `behavior_tree/interface/protocol.clj` |
| CR-BT-03 | `build` MUST wrap `:st-memory` in an atom and thread `:agent` through context; `run` MUST be `(tick tree context)`. | Implemented | `behavior_tree/core/engine.clj` |
| CR-BT-04 | An unregistered node type MUST be a hard error — the `:default` tick MUST throw `"Node type not implemented"`. | Implemented | `behavior_tree/core/nodes.clj` |

CR-BT-04 is the one intentional "MISSING node type" surface: it exists so
a typo'd or unimplemented node fails loudly rather than silently
succeeding. It is covered by tests.

---

## 2. Base node types

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-BT-05 | `:sequence` MUST tick children in order; the first `:failure`/`:running` short-circuits, otherwise `:success`. | Implemented | `nodes.clj` |
| CR-BT-06 | `:fallback` MUST tick children in order; the first `:success`/`:running` short-circuits, otherwise `:failure`. | Implemented | `nodes.clj` |
| CR-BT-07 | `:repeat` MUST decorate exactly one child (build asserts), loop to `max-n` (default 5), succeed when the child succeeds and a condition-fn holds, fail on child failure, and throw on an unknown child result. | Implemented | `nodes.clj` |
| CR-BT-08a | `:condition` MUST return `:success`/`:failure` from its condition-fn; `:action` MUST return the result of its action-fn directly. | Implemented | `nodes.clj` |
| CR-BT-08b | `:parallel` MUST run children concurrently and aggregate: `:success` when success-count ≥ threshold (default = child count), `:failure` when failures exceed slack, else `:running`. | **Partial** | `nodes.clj` |

**CR-BT-08b (Partial):** the `:parallel` node has no cancellation/pause
checkpoint and — unlike `:sequence`/`:fallback`/`:condition`/`:action`/`:repeat`
— gets **no** agent-layer tracing override (see §3). Consequences: a
long-running `:parallel` subtree cannot be cancelled cooperatively and
emits no traces. Candidate TODO: add an interrupt/cancel checkpoint and a
traced override for `:parallel`, or document that parallel subtrees are
intended to be short and untraceable.

---

## 3. Agent-layer overrides (tracing + cancellation + iteration events)

When `agent.core.bt` loads, it **re-defines** the tick methods for
`:sequence`, `:fallback`, `:condition`, `:action`, and `:repeat` to emit
traces (via `update-session-data`) and to insert the cooperative-cancel
checkpoint. This is the seam between the pure BT engine and the agent
runtime.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-BT-09 | Before each `:condition`/`:action` tick and each `:repeat` iteration, the runtime MUST checkpoint: (1) thread interrupt → throw "Interrupted"; (2) cancelled → throw "Cancelled"; (3) paused → `await-resume`, then re-check cancel. | Implemented | `agent/core/bt.clj` (`check-interrupt-cancel-pause!`) |
| CR-BT-10 | `:repeat` MUST fire `:agent.iteration/{pre,post,exhausted}` around iterations. | Implemented | `agent/core/bt.clj` |
| CR-BT-11 | The overridden nodes MUST emit traces through `update-session-data` so the BT is inspectable/replayable. | Implemented | `agent/core/bt.clj` |

This is the implementation side of the runtime's cooperative-cancel
contract ([agent-runtime](agent-runtime.md) CR-RT-14..16): cancellation
is observed *between* nodes, not mid-node.

---

## 4. Shared st-memory

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-BT-12 | st-memory MUST be a single shared atom on the BT context; `run-bt` MUST reset it to `st-memory-init` + `:question` and bump `:bt-generation` to guard against stale analytics. | Implemented | `agent/core/bt.clj` (`run-bt`) |
| CR-BT-13 | A sub-BT (`skill-behavior-fn`) MUST build a child BT and merge its st-memory back into the parent (minus `:question`). | Implemented | `agent/core/bt.clj` (`skill-behavior-fn`) |
| CR-BT-14 | `skill-behavior-fn*` MUST save and restore `dirty-keys` around the child execution. | Implemented | `agent/core/bt.clj` |

st-memory is the per-iteration mutable surface the reasoning loops read
and write; its layering semantics are specified in
[memory-and-context](memory-and-context.md).

---

## 5. Human-in-the-loop nodes

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-BT-15 | `request-user-action` MUST create a promise, surface it via `update-session-data :user-action`, and time out after 300s (throwing "User action timed out"). | Implemented | `agent/core/bt.clj` |
| CR-BT-16 | `user-approval-action` MUST support yes / no / always-yes / always-no, persisting the "always" choice as an action permission. | Implemented | `agent/core/bt.clj` |
| CR-BT-17 | `user-interrupt-action` MUST support continue / stop, where stop throws to abort the tree. | Implemented | `agent/core/bt.clj` |
| CR-BT-18 | `artifact-action` MUST surface an artifact through the same user-action channel. | Implemented | `agent/core/bt.clj` |

---

## 6. DSPy action node

The DSPy action is how a BT leaf calls a typed LLM signature. It is the
bridge from control flow into reasoning.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-BT-19 | A `dspy` node MUST support `:predict` and `:chain-of-thought` operations dispatched by `execute-dspy-operation`. | Implemented | `behavior_tree/core/dspy_action.clj` |
| CR-BT-20 | It MUST read signature input-keys from st-memory, exclude `:stable-keys` from the user message (placing them in the system message instead), and write outputs + `:last-reasoning` + `:lm-usage` in one atomic swap. | Implemented | `dspy_action.clj` |
| CR-BT-21 | Missing inputs MUST yield `:failure` plus an `:agent.dspy-action/post` event with `:error "missing-inputs"`; an exception MUST yield `:failure` and store `:dspy-error`. | Implemented | `dspy_action.clj` |
| CR-BT-22 | lm-config precedence MUST be node-opts → agent → session → global. | Implemented | `dspy_action.clj` |
| CR-BT-23 | The BT component MUST NOT hard-depend on the agent component; agent-side hooks/chunk-factory MUST be reached via `requiring-resolve`. | Implemented | `dspy_action.clj` |

---

## 7. Public interface surface

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-BT-24 | `behavior-tree/interface.clj` MUST re-export `success`/`failure`/`running`, `build`, `run`, `st-memory-has-value?`, `dspy`, and MUST document that repeat/tracing/HITL/visualization nodes live in `agent.core.bt` as overrides, not in this component. | Implemented | `behavior_tree/interface.clj` |

---

## Gaps & candidate TODOs (this spec)

- **CR-BT-08b — `:parallel` is uncancellable and untraced.** No
  interrupt/cancel checkpoint, no agent-layer tracing override. Either
  add both (to match the other node types) or document parallel subtrees
  as intentionally short/opaque. *(Medium; interacts with the CoAct
  parallel-block path — see [reasoning](reasoning.md) CR-RSN-06.)*

No `TODO`/`FIXME` markers exist in the BT engine or the agent-layer
override file.
