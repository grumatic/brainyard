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
| CR-BT-26 | It MUST validate gathered inputs against the signature's declared `:inputs` schemas before the call. A **missing** declared input (absent or nil, not `{:optional true}`) MUST yield `:failure` with `:dspy-error-class :fatal` and a message naming the key and node; a **present-but-invalid** value MUST be logged (`::dspy-input-schema-drift`) and MUST NOT abort. A signature with no `:inputs` map declares no contract and MUST be unchecked. | Implemented | `dspy_action.clj` |
| CR-BT-27 | It MUST merge only signature-declared output keys into st-memory, dropping an undeclared key in a model reply with `::dspy-undeclared-outputs`. A signature declaring NO output keys MUST be treated as undeclared, not empty, and MUST NOT be filtered. | Implemented | `dspy_action.clj` |

---

## 7. Public interface surface

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-BT-24 | `behavior-tree/interface.clj` MUST re-export `success`/`failure`/`running`, `build`, `run`, `st-memory-has-value?`, `dspy`, and MUST document that repeat/tracing/HITL/visualization nodes live in `agent.core.bt` as overrides, not in this component. | Implemented | `behavior_tree/interface.clj` |
| CR-BT-32 | It MUST also export `input-violations`, so a caller can assert the CR-BT-26 contract without an LLM round-trip — an agent pinning that its own producers write what its signature declares. | Implemented | `behavior_tree/interface.clj` |

---

## Gaps & candidate TODOs (this spec)

- **CR-BT-08b — `:parallel` is uncancellable and untraced.** No
  interrupt/cancel checkpoint, no agent-layer tracing override. Either
  add both (to match the other node types) or document parallel subtrees
  as intentionally short/opaque. *(Medium; interacts with the CoAct
  parallel-block path — see [reasoning](reasoning.md) CR-RSN-06.)*

- **CR-BT-12b — the shared context has no declared contract.** The tree
  is declarative and validated by construction (CR-BT-02..04); the
  `st-memory` bus it runs against is not. Measured: **≥ 54 distinct keys**,
  617 src references across **46 files in 5 bricks**, 13 writer namespaces
  and 33 read-only, with **4** nodes asserting anything
  (`st-memory-has-value?`). Three visible costs; Phase 1 closed the first
  two. ~~A nil DSPy input is silently dropped and the call proceeds on a
  partial input set~~ and ~~model outputs merge onto the bus unguarded~~ —
  both now checked in `dspy_action.clj` (`dspy`, ~line 476 and ~line 540).
  Still open: `skill-behavior-fn` blind-merges a subtree's whole memory
  (`agent/core/bt.clj:161`) while its sibling works around that with a
  hand-maintained `:dirty-keys` list. Proposal (CR-BT-25..31):
  [docs/design/bt-context-schema-design.md](../design/bt-context-schema-design.md).
  **Phase 1 has landed** — CR-BT-26, CR-BT-27 and CR-BT-32 above close the
  DSPy boundary, and turning that check on found three pre-existing drifts in
  `::iterations` (design doc §3.6.1). The remaining gap is the bus itself, and
  the design doc's own ordering was revised once Phase 1 shipped (§5.1): the
  next step is **CR-BT-25**, the context-key registry, because `:lifetime` /
  `:persist?` are the one missing fact behind three separately hand-maintained
  lists (`reset-st-memory!`, `context-budget`'s drop set, and what `--resume`
  can restore). CR-BT-28..31 (per-node `:requires`/`:provides`, the `build-bt`
  dataflow fold, derived `:dirty-keys`) are demoted, not dropped — Phase 1
  already catches a missing input at the call, so their remaining unique value
  is a key missing on a path reaching no dspy node. *(High value / medium
  cost.)*

No `TODO`/`FIXME` markers exist in the BT engine or the agent-layer
override file.
