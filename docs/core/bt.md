# Behavior Tree — the Thinking Loop

All reasoning in this harness — whether ReAct or CoAct — is expressed as a
**behavior tree (BT)**. The BT is the central execution substrate: leaves
call LLMs (via DSPy signatures), run tools, evaluate code, or block on
user input. Because the BT is just data, every decision is inspectable,
traceable, cancellable, and replayable.

Primary file: `components/agent/src/ai/brainyard/agent/core/bt.clj`.
Underlying engine: `components/behavior-tree`.

---

## Tree context

`build-bt` constructs a tree with a single short-term context carried
through the BT:

- **`:st-memory`** — an atom holding the current turn's question,
  thoughts, tool calls, observations, evaluations, todo list, and
  answer. It is **reset every turn** (preserving persistent init: tools,
  instructions, tool-context).
- **`:agent`** — the Agent record (so leaves can read protocols, fire
  hooks, recall memory, dispatch tools).

Long-term memory (the memory manager) hangs off the agent record itself
and is reached from BT actions through
`agent.core.memory/recall` / `remember`. Recall results are written back
into `:st-memory` (e.g. under `:recalled-memory`) for the rest of the turn.

The atom carries a **generation counter** bumped on reset. Async writes
(notably post-session analytics) check the generation before publishing,
preventing stale writes from a prior turn from corrupting the current one.

---

## Turn execution

`run-bt` wraps each turn as:

1. **Reset st-memory** — overlay persistent init (`:st-memory-init`),
   set `:question`, bump `:bt-generation`.
2. **Tick the BT** — `bt/run` walks the tree.
3. **Async analytics** (when enabled) — PQS / waste / cost analysis off
   the hot path; generation-guarded so a fresh turn does not get stomped
   by results from an old one.

Recall and remember happen *inside* the BT, not around it. Built-in
reasoning agents wire recall as an early leaf (so the turn sees
`:recalled-memory`) and remember at the end. The memory capture pipeline
(see [memory.md](memory.md)) is also subscribed to lifecycle hooks
(`:agent.ask/pre`, `:agent.code-eval/post`, …) and writes asynchronously
in a side-car thread.

---

## Brainyard's extended nodes

The upstream `components/behavior-tree` engine (`core/nodes.clj`) defines
the structural node set: `:sequence`, `:fallback`, `:parallel` (runs its
children on futures, gated by `:success-threshold`), `:repeat` (decorator
with `:max-n` + `:condition-fn`), `:condition`, and `:action`. Brainyard's
`agent.core.bt` **overrides** the tick of the tracing-relevant nodes
(`:sequence`, `:fallback`, `:condition`, `:action`, `:repeat`) with
variants that every tick:

1. Check cancellation / interrupt / pause flags via
   `check-interrupt-cancel-pause!` (honours `Thread/interrupted`,
   `check-run-cancelled?`, and a paused→`:cancelled` outcome) and
   short-circuit if raised.
2. Append depth-indented trace entries to the session's `:data` stream
   (rendered live in the TUI).
3. Read debug values from st-memory and expose them to inspection.

The `:repeat` decorator (loop-start + completion tracing) is what drives
the ReAct / CoAct iteration loop. Additional HITL leaves layered on top
by the agent component:

| Node | Purpose |
|---|---|
| `request-user-action` | Blocking leaf that creates an action-promise and waits for `deliver-action`. |
| `user-approval-action` | Yes / No / Always-Yes / Always-No, with cached answers reused from `:action-permissions`. |
| `user-interrupt-action` | Continue / Stop prompt injected at checkpoint boundaries. |
| `artifact-action` | Stash a code/doc artifact into session thinking for later rendering. |

All user-interaction nodes route through the same action-permission
primitives the rest of the runtime uses, giving a uniform surface for
frontends.

---

## DSPy actions

LLM calls live inside `:action` leaves that point at a DSPy signature.
`behavior-tree/dspy-action` is the generic implementation. Each call:

- Resolves the signature, including `:stable-keys` that ride the system
  message (cached by the LLM provider — used heavily by CoAct's
  `:system-context` / `:user-context`).
- Streams chunks via `chunk-factory-handler` (so the TUI / web bridge
  can render deltas under `:llm-streaming-text`).
- Fires `:agent.dspy-action/pre`, `:agent.dspy-action/chunk` (per chunk),
  and `:agent.dspy-action/post` hooks for analytics and audit.

---

## BT-as-data, tree-as-viz

Because the BT is Clojure data, it can be introspected and rendered.
Utility helpers like `btree->jstree` convert the tree into a structure
suitable for front-end visualisation (Fulcro RAD web UI or the TUI's
`:thinking` command).

An illustrative tree:

```clojure
[:sequence {:id :react.sequence/main}
 [:action {:id :think-and-select-tools}]
 [:repeat {:max-n (fn [_] 20)}
  [:sequence
   [:action {:id :tool-calls}]
   [:action {:id :observe-and-evaluate}]
   [:condition {:id :goal-achieved?}]]]
 [:action {:id :finalize-answer}]]
```

---

## Cancellation in practice

```
cancel-run (from TUI Ctrl-C)
  └─ sets (:cancelled? ctx) = true
  └─ .interrupt on the runtime thread

Next BT tick:
  └─ tracing-aware :sequence / :action notices :cancelled?
  └─ returns :failure, unwinds, writes a trace entry
  └─ :agent-activity emits a "cancelled" stage
  └─ TUI renders inline; run returns {:cancelled? true}
```

Because cancellation is checked *at every tick*, the user is never more
than one LLM call away from regaining control — except when waiting on
a network round-trip inside a leaf. Leaves are responsible for honouring
interrupts on their own blocking operations.

---

## Defining a new agent

A `defagent` supplies a BT-factory plus persistent init:

```clojure
(defagent my-agent
  "My agent — does X."
  agent/run-agent
  :bt-factory (fn [{:keys [max-iterations]}]
                (my-behavior-tree max-iterations))
  :tool-use-control {}
  :agent-tools {:tools [...]}
  :instruction "...")
```

At turn 0, `setup-agent-by-id` resolves the BT-factory, attaches the
built BT to the Agent record's `!state`, and installs persistent init
into the BT context. Every subsequent `ask` invokes `run-bt`.

---

## Why a BT?

The reason to choose a BT over ad-hoc loops, state machines, or pure DAGs:

- **Composability** — ReAct and CoAct share the same outer harness and
  differ only in subtree choice.
- **Inspectability** — every node produces a trace entry; the TUI can
  render the live tree.
- **Replayability** — a tree plus a session can be replayed against the
  same inputs (modulo LLM nondeterminism) for debugging.
- **HITL naturally fits** — user approvals are just leaves that block on
  promises; nothing special is needed in the core loop.
- **Cross-cutting concerns** — tracing, cancellation, debug logging live
  in the tree nodes themselves, not sprinkled through business logic.

---

## File map

| File | Purpose |
|---|---|
| `core/bt.clj` | `build-bt`, `run-bt`, skill-behavior helpers, `chunk-factory-handler`, extended nodes |
| `common/trace.clj` | Depth-indented BT traces threaded into session thinking |
| `components/behavior-tree` | Upstream engine: tick, node impls, `dspy-action`, context protocol |

Next: [reasoning.md](reasoning.md) for ReAct and CoAct, the two reasoning
styles that plug their own subtrees into this scaffolding.
