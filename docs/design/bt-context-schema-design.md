# Design: Behavior-Tree Context Schema

*Area code `BT` (extends [specs/behavior-tree.md](../specs/behavior-tree.md)).
Proposes giving the behavior tree's shared context — the `st-memory` atom — the
same declared, checkable contract the tree itself already has. Status:
**Phases 1 and 0 shipped** (CR-BT-26, CR-BT-27, CR-BT-32 — validation at the
DSPy boundary; CR-BT-25 — the context-key registry). **Phases 2 and 3 are
Proposed and not queued** — §5.2 retracts the argument that promoted this work
and §5.4 says where it stops; read those before treating the rest as a
programme to finish. New contracts are numbered
**CR-BT-25+** (CR-BT-01..24 are taken by the existing spec; CR-BT-32 was added
on top when Phase 1 landed).*

---

## 1. Motivation

### 1.1 The asymmetry

A behavior tree in this codebase is declarative and validated by construction.
`p/build` is a multimethod keyed on node type, so an unregistered type is a hard
error (CR-BT-04), and `:repeat` throws when given ≠1 child. You can read a tree
config and know its shape without running it.

The context that tree runs against has no such contract. `engine/build`
(`behavior_tree/core/engine.clj:13-22`) documents exactly two keys:

```clojure
(defn build
  [[node-type & args :as _config] {:keys [st-memory] :as context}]
  {:tree (p/build node-type args)
   :context (assoc context :st-memory (atom (or st-memory {})))})
```

— `:st-memory` and `:agent`. Everything else is whatever the caller happened to
put in the map, and everything inside `st-memory` is whatever any node happened
to `swap!` into it. The tree is a specification; the blackboard is a habit.

### 1.2 What is actually on the bus

Measured against the current source (`components/*/src`, `bases/*/src`, tests
excluded):

| Metric | Value |
|---|---|
| `st-memory` references | 617 |
| Files touching it | 46 |
| Bricks touching it | 5 — `behavior-tree`, `agent`, `agent-tui`, `clj-sandbox`, `analytics` |
| Namespaces that **write** (`swap!`/`reset!`) | 13 |
| Namespaces that **read only** | 33 |
| Distinct keys (conservative two-pattern scan) | **≥ 54** |
| Keys with any runtime validation | **9**, and only in one direction (§2) |
| Nodes that assert anything about context | **4** (`st-memory-has-value?` — 2 in coact, 2 in acp) |

The 54:

```
:active-todo-slug :answer :answer-decision :code-blocks :code-langs :config
:consecutive-llm-failures :continuation :conversation :display-stage :dspy-error
:dspy-error-class :dspy-error-reason :dspy-no-json-envelope? :dspy-raw-text
:dspy-validation-errors :evaluation-status :iteration-count :iterations
:last-code-results :last-failure :last-reasoning :last-repair-iter
:last-tool-results :last-trajectory-turn :live-artifacts :llm-streaming-text
:next-user-prompt :parent-trail :pending-format-guidance
:pending-self-improve-notice :pending-usage-guides :phase :previous-turns
:procedure-action-count :purpose :question :round :script-entries
:share-parent-output-session :signature :skipped :terminated :think :todo-list
:tool-calls :tool-results :tools :tools-disabled-tiers :tools-fn-map
:tools-section-config :total-turns :trajectory-iterations :turn-id
```

Thirteen writers and thirty-three readers across five bricks is not a
scratchpad. It is a **many-to-many message bus with no declared message
format**, and it is the widest undocumented interface in the runtime.

### 1.3 The four failure modes this produces

Each of these is a real property of the current code, not a hypothetical.

**(a) A missing input degrades the prompt instead of failing.** This is the
most expensive one. `dspy_action.clj:353`:

```clojure
all-inputs (reduce (fn [acc key]
                     (let [value (get state key)]
                       (if (some? value) (assoc acc key value) acc)))   ; nil → dropped
                   {} input-keys)
...
(if (seq all-inputs)                                                    ; ONE input suffices
```

A `nil` input is silently dropped, and the call proceeds as long as *any single*
input is present. So an action that failed to write `:context-briefing` does not
raise — it produces a **quieter prompt**, and the resulting turn is
byte-indistinguishable from a healthy one at every layer below the model.
`::dspy-missing-inputs` fires only when *all* inputs are absent, which is the
case that was never hard to notice. The failure surfaces, if at all, as a worse
answer three iterations later.

**(b) Model output is merged onto the bus unguarded.** `dspy_action.clj:371`:

```clojure
(let [m (reduce-kv assoc m (:outputs result))  ...
```

Signature outputs land directly on the shared blackboard under model-chosen
keys. `validate-output` (`clj_llm/core/chain_of_thought.clj:151`) checks the
JSON against the signature's field schemas, but that is a check of one LLM
reply's shape, not of what the bus is now carrying. This is the one writer on
this bus whose content is not authored by us, and it is the only writer with no
declaration of what it may touch.

**(c) Subtree composition is a blind merge, with a hand-maintained
workaround.** `skill-behavior-fn` (`agent/core/bt.clj:161`) merges a child BT's
entire st-memory into the parent's, minus `:question`:

```clojure
(swap! st-memory merge (dissoc skill-st :question))
```

Every key the child wrote, declared or not, overwrites the parent. Its sibling
`skill-behavior-fn*` (bt.clj:182-200) works around this with `:dirty-keys` — a
caller-supplied list of which keys the subtree mutates, saved and restored
around the run.

That is worth dwelling on: **`:dirty-keys` is already a `:writes` declaration.**
The need this doc addresses is not speculative; it has been met once, by hand,
at one call site, with no way to check that the list matches what the subtree
actually writes.

**(d) Renames and reads are grep-and-pray across brick boundaries.**
`agent-tui/session.clj` reaches into the agent's st-memory atom directly for
`:turn-id`, `:todo-list` and iteration coordinates, and stamps `:st-memory-atom`
onto the session map so `/context` and `/memory` can read it too. `clj-sandbox`
exposes agent state — including st-memory — to **model-authored code** through
`context_accessors`. Polylith's interface discipline governs functions; the
blackboard routes around it entirely. Commit `e57bb28` renamed
`:eval-results` → `:code-results` across this surface; the only tool available
for that was grep.

---

## 2. What already exists

This design is mostly about **enforcing declarations that are already written
down**, not inventing a mechanism. Four pieces are in place:

| Piece | Where | What it gives us |
|---|---|---|
| A process-wide mutable Malli registry | `clj_llm/core/schema_registry.clj` (`registry*`, `defschemas`) | Named, reusable schemas with no new dependency |
| Per-key schemas for the hot path | `coact_agent.clj:94` (`defschemas coact-domain`), `common/schema.clj` | `::iterations`, `::code-blocks`, `::eval-entry`, `::tool-result-entry`, … |
| A key→schema mapping | `defsignature ThinkActCode` (`coact_agent.clj:505`) | `{:inputs {:question ::acs/question :context-briefing ::context-briefing :recalled-memory ::acs/recalled-memory :iterations ::iterations} :outputs {…}}` |
| A schema-checking BT node | `st-memory-has-value?` (`engine.clj:41`) | `{:path [...] :schema <malli>}` as a condition |

The signature's `:inputs`/`:outputs` maps **are already a partial context
schema**: plain st-memory keys bound to Malli schemas. Nine of the 54 keys are
covered. What is missing is not the schema — it is that the schema is applied to
the model's JSON and never to the blackboard the JSON is read from and written
to. Inputs are gathered with `get` and no validation at all.

So: extend the coverage, and point the existing checks at the bus.

---

## 3. Design

### 3.1 A key declaration carries four things, not one

A bare `[:string]` per key would catch typos and little else. The declarations
that pay for themselves answer questions the code currently cannot:

| Field | Answers | Consumed by |
|---|---|---|
| `:schema` | What shape is this? | validation at edges (§3.4) |
| `:writers` | Which fns write it? | rename safety, dataflow graph, `:dirty-keys` derivation |
| `:lifetime` | `:session` / `:turn` / `:iteration` | `reset-st-memory!`, compaction, sub-BT merge policy |
| `:persist?` | Does this survive `--resume`? | session persistence, and the SCI fn-serialization wall |
| `:opaque?` | Is this a live object or a function? | never serialized, never structurally validated |

**`:owner` (singular) was the original proposal and the source says it is a
fiction.** Extracting the real write sites found `:display-stage` written by ten
fns, `:answer` by eight, `:iterations` by seven, `:terminated` by six. A single
owner is the exception, not the rule — and not through sloppiness:
`:display-stage` is a UI state machine every display action advances, and
`:answer` is written by the channel that produces it and cleared by several
recovery paths. A registry claiming one owner would have been wrong on exactly
the rows that matter most, so the field is `:writers`, plural, and descriptive.

`:lifetime` and `:persist?` are the two that generalise beyond type checking.
Today `reset-st-memory!` (bt.clj:95) resets to `st-memory-init` + `:question` by
convention, `context-budget` decides what may be dropped from a hardcoded list,
and `--resume` cannot restore anything holding a function because nothing
distinguishes a serialisable value from a runtime handle. All three are the same
missing fact, asked three times.

### 3.2 The registry

One namespace, `agent.core.bt-context`, holding a plain map (no macro — Phase 0
has no mechanism to hang one on):

```clojure
(def context-keys
  {:question  {:doc "The user's input for this turn. Stamped by reset-st-memory! …"
               :schema :ai.brainyard.agent.common.schema/question
               :lifetime :turn :persist? true
               :writers #{'ai.brainyard.agent.core.bt/reset-st-memory! …}}
   :sandbox   {:doc "The live SCI evaluation context …"
               :lifetime :turn :persist? false :opaque? true
               :writers #{'…coact-agent/coact-init-action}}
   …})
```

**`:schema` values are fully-qualified KEYWORDS and `:writers` are unresolved
SYMBOLS**, both resolved late — the schema through clj-llm's process-wide malli
registry at validation time, the writers by the test. So the namespace requires
*nothing*: it cannot form a cycle with the ~6000-line `common/coact-agent` where
most schemas and nearly all writers live, and any later phase can require it
from anywhere.

**Open by default.** The map schema is `:map`, never `[:map {:closed true}]`.
Closing it would break the escape hatch that lets `usage-nudge`,
`self-improve-nudge` and `procedure-nudge` stash `:pending-*` keys without
touching a central file — a property worth keeping. Undeclared keys are legal
and unchecked; declared keys are checked. Coverage grows by adding rows, and the
day-one value is documentation: *who writes `:evaluation-status`* currently has
no answer short of grep.

`:opaque?` marks a runtime handle (an atom, a sandbox, a function) — it is
never validated structurally, never persisted, and is abbreviated rather than
`pr-str`'d in the trace (§3.7).

### 3.3 Node-level `:requires` / `:provides`

Node opts gain two optional vectors of context keys:

```clojure
[:action {:id (kw :action/tool-dispatch)
          :requires [:tool-calls :config]
          :provides [:last-tool-results]}
 coact-tool-dispatch-action]
```

This is the same declaration `:dirty-keys` makes by hand, moved to where the
mutation actually happens and therefore checkable. `skill-behavior-fn*` can then
*derive* its `:dirty-keys` from the subtree's folded `:provides` instead of
being told, which closes the gap between "what the caller listed" and "what the
subtree writes."

### 3.4 Where validation runs — and where it must not

**Not on every tick.** `:iterations` carries the full iteration history and the
loop ticks per iteration; validating that vector on every tick is real cost for
no signal. Validation runs at four edges:

| Edge | Check | Cost |
|---|---|---|
| `build-bt` | Static dataflow fold (§3.5) | Once per agent construction |
| `p/tick :action` / `:condition` entry | Declared `:requires` are present and valid | O(#requires), assert-gated |
| Same, on exit | Declared `:provides` were written and valid | O(#provides), assert-gated |
| `dspy` in/out | §3.6 | Once per LLM call — free relative to the call |

Entry/exit checks hook the **agent-layer tracing overrides** in
`agent/core/bt.clj:416` and `:441`, which is the single place both the sync
engine and the `tick-task` effect engine already route through. One
implementation, both engines, no drift.

They are gated on a dev/assert flag so the shipped binary pays nothing. The DSPy
boundary is the exception — it validates unconditionally, because it is already
amortised against a network round-trip and because it is the boundary where the
failure is invisible (§1.3a).

### 3.5 Build-time dataflow check

Walk the tree once at `build-bt`, folding `:provides` down each path against
each node's `:requires`, and error on a `:requires` that no ancestor (or
`:st-memory-init` seed) provides.

The argument for this was originally "catches the whole of §1.3(a) **before any
LLM call is made**." **Phase 1 has since made that mostly false** — a missing
declared input is now caught at the call, by name, with a clean fatal abort, so
the marginal gain here is one request's latency rather than a class of bug. See
§5.1. What survives as this phase's own value:

- A key missing on a path that reaches **no dspy node at all** — real, but a
  narrower case than the original claim.
- It makes the tree's declarativeness mean something operationally rather than
  only structurally: today a tree is a valid tree if its node types exist; after
  this, a tree is valid if its data actually flows.
- It derives `:dirty-keys` (CR-BT-30), removing a hand-maintained list.

The fold must be conservative about `:fallback` (only one branch runs, so a key
provided in exactly one branch is not guaranteed downstream) and about
`:repeat` (a key provided by iteration N is available to N+1 but not to the
first tick). Both cases degrade to a warning rather than an error — the point is
to catch the flat-out-missing key, not to build a type system. Note this
conservatism covers most of coact's interesting structure, which is the other
half of why §5.1 demotes this phase.

### 3.6 The DSPy boundary specifically

Two changes, one per direction:

- **Inputs.** Before the `(if (seq all-inputs) …)` gate, check the gathered
  inputs against the signature's own `:inputs` schemas, and split the result by
  reason:
  - **`:missing`** — a declared, non-`{:optional true}` input that is absent or
    `nil` — is a `p/failure` carrying `:dspy-error-class :fatal` and a message
    naming the key and the node. `:fatal` is the correct class because the
    repair path re-prompts on `:malformed` and re-runs on `:transient`, and
    neither can fix a key no action wrote; `:fatal` routes to
    `abort-turn-with-llm-error!` with the precise cause instead of burning the
    iteration budget re-asking a model that was never the problem.
  - **`:invalid`** — a present value that fails its schema — is logged
    (`::dspy-input-schema-drift`, with the key and malli's explanation) and the
    call proceeds. See §3.6.1: on this bus the schema is at least as likely to
    be the stale half, and aborting a working turn on a documentation bug is a
    worse outcome than the bug it reports.
- **Outputs.** The `reduce-kv assoc` merge keeps only keys the signature
  declares as outputs; undeclared keys are dropped with `::dspy-undeclared-outputs`
  rather than written to the bus. A signature that declares *no* output keys is
  treated as having an **undeclared** contract, not an empty one, and is not
  filtered — compiled signatures always carry `:output-keys`, but hand-built
  maps may not, and silently discarding all of their results would be far worse
  than the key this guards against.

Optionality is declared by the signature author with `{:optional true}`, the
marker `parse-malli-field` already understands — so no new field was needed,
and a signature with no `:inputs` map declares no contract and is unchecked,
which is what keeps every pre-existing node working.

This is the highest-value change in the proposal. It covers the keys that
already have schemas, at the one boundary where a violation is currently
invisible, and needs no new declarations at all.

#### 3.6.1 Turning it on found three drifts on the first run

`:iterations` is a declared ThinkActCode input, so this was the first time its
schema was ever checked against real data. It did not match, in three ways —
every one of them invisible for exactly the reason this phase exists:

1. **`::eval-entry` declared `:status` / `:task-id` / `:from-iteration` as
   REQUIRED keys.** `[:maybe …]` makes a *value* nullable; a malli map entry is
   required unless the ENTRY carries `{:optional true}`. `sanitize-eval-entry`
   adds all three with `cond->`, so **every plain sync eval entry violated the
   schema.** Fixed by marking the three entries optional — the schema was wrong,
   the data was right. (`::iteration` already used the correct `{:optional true}`
   form for `:notices`, so this was a slip, not a convention.)
2. **The harvest-resolve path never sanitized its entry.**
   `project-terminal-task->eval-entry`'s error path emits `:result nil` and no
   `:parallel?`, and the resolve site merged it straight into an existing
   record's `:code-results`. Skipping `sanitize-eval-entry` also skipped the
   context-budget truncation every neighbouring entry gets, so an async result
   could enter the model's replay buffer untruncated. Fixed by sanitizing, which
   is what the file's own forward-declaration comment says that path requires.
3. **The in-flight-roster record carries undeclared `:tasks` /
   `:in-flight-roster?` keys.** Not a bug — `::iteration` is an open `[:map …]`
   and must stay that way. Worth recording because it is the case that proves
   open-by-default (§3.2) is load-bearing rather than merely convenient.

Finding (1) is also the argument for the `:missing`/`:invalid` split. Had
`:invalid` been fatal, this change would have aborted every turn that ran a
code block — on a schema typo, not a runtime fault.

### 3.7 Falling out of the registry for free

- **Trace redaction.** `get-st-memory-value` (bt.clj:402) `pr-str`s an arbitrary
  context value into the trace with a per-call-site `(abbreviate … 100)`.
  `:opaque?` and a `:sensitive?` marker make that uniform, and make it possible
  to trace a key without leaking it.
- **Dataflow visualisation.** `btree->jstree` (bt.clj:636) renders the control
  graph. `:requires`/`:provides` gives the *data* graph, from the same walk.
- **Dead-key detection.** A key written by some node and read by nothing is
  visible as a fold over the same declarations — there are almost certainly
  several among the 54.

---

## 4. Non-goals

- **This does not reduce coupling.** The context stays one shared mutable atom
  reachable from five bricks. A schema *documents* the coupling and makes it
  checkable; it does not remove it. If reducing coupling is the real goal, the
  lever is §3.3 — once nodes declare their reads and writes you can compute the
  dataflow graph, and only then can you argue about narrowing what each node
  sees.
- **This is not a closed-world type system.** See §3.2. Undeclared keys stay
  legal.
- **This does not change the `:st-memory` atom's identity or lifecycle.**
  CR-BT-12..14 stand as-is; §3.3 only gives `skill-behavior-fn*` a way to derive
  what it is currently told.
- **This is not a runtime cost on the shipped binary**, except at the DSPy
  boundary, where it is unmeasurable against the LLM call.

---

## 5. Phasing

Each phase is independently shippable and independently useful. Two shipped;
**§5.2 retracts a claim this section originally made for Phase 0, and §5.4
explains why the remaining two are not queued work.**

| Phase | Scope | Value delivered alone |
|---|---|---|
| **1** ✅ | DSPy in/out validation (§3.6) against the schemas that already exist | Closes the invisible-degradation failure on the hot path — **shipped**; found three pre-existing `:iterations` drifts on its first run (§3.6.1) |
| **0** ✅ | The registry ns + declarations. No enforcement. | A checked description of 54 keys plus a harness that keeps it true (§5.2, §5.2.1) — **shipped**. Note §5.2 retracts the stronger claim originally made for this phase. |
| **2** | `:requires`/`:provides` on coact's nodes + entry/exit checks in the tracing overrides, assert-gated | Failures name the node that broke the contract, not the node that noticed |
| **3** | `build-bt` static fold (§3.5); derive `:dirty-keys` from `:provides` | Removes a hand-maintained list; catches a missing key on paths that reach no dspy node |

Phases 0 and 1 touch no node definitions and no agent authors' code. Phase 2 is
additive per node — an undeclared node is simply unchecked, so coverage can grow
one agent at a time starting with coact.

### 5.1 Revised after Phase 1: do Phase 0 next, not Phase 2/3

This document originally ordered the work 0 → 1 → 2 → 3, and argued Phase 3's
prize was catching a missing key **before any LLM spend**. Shipping Phase 1
invalidated that argument, and the ordering above is the correction.

- **Phase 1 ate most of Phase 3's value.** A missing declared input is now
  caught AT the call, with the key named and a clean fatal abort. Catching the
  same thing a few hundred milliseconds and one request earlier is a small
  marginal gain. What remains uniquely Phase 3's is a key missing on a path that
  reaches no dspy node at all — real, but much narrower than §3.5 claims.
- **Phase 2/3 also costs more than written.** coact's tree is ~35 nodes, and
  `:provides` is genuinely ambiguous for several of them: `coact-repair-action`
  provides different keys depending on which of its four branches ran. And
  §3.5's own conservatism — `:fallback` branches and `:repeat` first-ticks
  degrade to warnings rather than errors — covers most of coact's interesting
  structure, so the fold will warn often and error rarely.
- **Phase 0 was promoted on a premise that turned out to be false.** The
  original third bullet here pointed at §5.2's "unlocks three capabilities"
  argument, which is retracted — see §5.2. The ordering above still holds, but
  only on the first two bullets: Phase 1 already catches a missing input where
  it matters, and Phase 2/3 cost more than written. Phase 0 was cheap and
  produced a checked description worth having; it was not the capability
  unlock this section claimed when it promoted it.

### 5.2 What Phase 0 is worth — RETRACTED and restated

> **This section previously claimed that `:lifetime` / `:persist?` would make
> three hand-maintained lists derived, and used that to promote Phase 0 over
> Phase 2/3. Checked against the source after Phase 0 shipped, two of the three
> claims are false and the third is weaker than stated. The original table is
> retracted; what follows is what is actually true.**

What the retracted table claimed, and what the code says:

| Claimed | Reality |
|---|---|
| `context-budget` keeps a hand-maintained drop list that `:lifetime` would derive | **False.** `default-section-policies` is not a list of st-memory keys — it is a table of PROMPT SECTIONS (`:role`, `:tools`, `:critical-rules`, `:conversation-history`, …) carrying a `:priority` and a named `:compact` strategy. Four of its keys coincide with st-memory key names, but the fact it encodes is *prompt value per token*, which is orthogonal to when a value is wiped. Nothing there derives from this registry. |
| `--resume` cannot restore a key holding a function, for want of `:persist?` | **False as stated.** `agent_tui_persist/core/restore.clj` reconstructs the SESSION MAP from `session.edn`; there is no st-memory restore path at all. `:persist?` would be an *input* to building one, not a replacement for something hand-maintained. |
| `reset-st-memory!` decides what survives a turn "by convention" | **Weaker than stated.** It resets to `st-memory-init` + `:question`, so membership in `st-memory-init` already IS the mechanism. `:lifetime :session` documents and verifies that mechanism; it does not replace a list, because there is no list. |

So Phase 0 was prioritised on a bad premise. It shipped anyway, and what it
delivered stands on narrower but real ground:

- **A checked description of 54 keys** — lifetime, writers, persistability,
  opacity — where previously the only way to answer "who writes
  `:evaluation-status`" was a grep across 5 bricks.
- **A harness that keeps it true** (§5.2.1): 135 writer symbols and 9 schema
  keywords resolved, and the `:iteration` lifetime pinned by running the real
  reset. That is what stops this from becoming the next `::eval-entry`.

That is documentation-and-verification value, not a capability unlock. It is
worth having and it is now done; it is **not** an argument for building more of
this design. See §5.4.

#### 5.2.1 What Phase 0 found, as built

54 keys declared, 21 recorded as deliberately undeclared with a reason, so the
gap between them is a decision rather than an oversight. By lifetime: 33 turn,
14 iteration, 7 session. 12 keys are persistable; 2 are opaque.

Three things the work changed about the design:

- **`:owner` singular was wrong** (§3.1). The write-site extraction is what
  settled it.
- **No macro, and no dependencies.** The proposal sketched a `defcontext-keys`
  macro; a plain map is enough when nothing consumes it yet, and keeping schemas
  as late-resolved keywords and writers as unresolved symbols means the registry
  requires nothing at all. That matters more than it sounds: nearly every writer
  lives in `common/coact-agent`, and a registry that required it could not later
  be required *by* it.
- **The registry is pinned by RUNNING the code, not by reading it.** The
  strongest test seeds every declared key with a sentinel, calls the real
  `coact-inc-iter-action`, and asserts the set of keys that changed equals
  `:lifetime :iteration` exactly. A key added to or dropped from that reset
  silently changes lifetime; this is what catches it. Alongside it, all 135
  `:writers` symbols and 9 `:schema` keywords must resolve — negative-controlled,
  so a rename fails the suite rather than passing vacuously.

That last point is the Phase 1 lesson applied one layer up: a declaration
nothing checks is prose, and prose about 54 keys written by 13 namespaces rots
on the first rename.

### 5.3 The cheapest next step costs nothing

`:invalid → warn` (§3.6) shipped as an INSTRUMENT, not a compromise. Real usage
accumulates `::dspy-input-schema-drift` events, and a clean log is evidence the
input schemas have become true. Promoting `:invalid` to fatal is then one line,
per signature, with the evidence to justify it — and it cannot be
short-circuited by writing more code now.

### 5.4 Where this design stops

With §5.2 retracted, **Phases 2 and 3 have no strong case left** and this
document should not be read as a programme to finish.

The case against them was already made on their own merits in §5.1 — Phase 1
catches a missing input where it matters, `:provides` is ambiguous for several
coact nodes, and §3.5's conservatism means the fold would warn often and error
rarely. §5.2 was the remaining argument that the *declaration* half was worth
extending into enforcement, and it does not survive contact with the source.
Nothing since has raised either phase.

What is genuinely left is smaller and bug-shaped rather than phased:
`skill-behavior-fn` blind-merges a subtree's whole st-memory into its parent
(`agent/core/bt.clj:161`) while its sibling `skill-behavior-fn*` works around
the same problem with a caller-supplied `:dirty-keys` list it cannot verify.
That is one concrete defect worth about an hour, and it needs neither
`:requires`/`:provides` nor a build-time fold to fix.

CR-BT-28..31 stay on the books as **Proposed**, not as work queued.

---

## 6. Proposed contracts

| ID | Contract | Status |
|---|---|---|
| CR-BT-25 | A context key MAY be declared in the context-key registry with `:doc`, `:schema`, `:writers`, `:lifetime`, `:persist?` and `:opaque?`. The context map MUST remain open — an undeclared key MUST be legal and unchecked. A declared `:opaque?` key MUST NOT be `:persist? true`. Every declared `:writers` symbol and `:schema` keyword MUST resolve, and `:lifetime :iteration` MUST equal exactly the set the per-iteration reset clears. | Implemented |
| CR-BT-26 | The `dspy` node MUST validate gathered signature inputs against their declared schemas before the LLM call. A **missing** declared input (absent or nil, and not `{:optional true}`) MUST yield `:failure` with `:dspy-error-class :fatal` and a message naming the key and node. A **present-but-invalid** value MUST be logged (`::dspy-input-schema-drift`) and MUST NOT abort — see §3.6.1. A signature with no `:inputs` field map declares no contract and MUST be unchecked. | Implemented |
| CR-BT-27 | The `dspy` node MUST merge only signature-declared output keys into st-memory; an undeclared key in a model reply MUST be dropped with a logged event (`::dspy-undeclared-outputs`). A signature declaring NO output keys MUST be treated as undeclared, not empty, and MUST NOT be filtered. | Implemented |
| CR-BT-28 | A node MAY declare `:requires` / `:provides`; when declared, the agent-layer `tick` overrides MUST check them on entry/exit. Checks MUST be assert-gated so the shipped binary pays nothing. | Proposed |
| CR-BT-29 | `build-bt` MUST fold `:provides` down the tree and report a `:requires` no ancestor provides. `:fallback` branches and `:repeat` first-ticks MUST degrade to a warning, not an error. | Proposed |
| CR-BT-30 | `skill-behavior-fn*` MUST derive `:dirty-keys` from the subtree's folded `:provides` when the subtree declares them, falling back to the caller-supplied list otherwise. | Proposed |
| CR-BT-31 | A key declared `:opaque?` MUST NOT be structurally validated, MUST NOT be persisted, and MUST be abbreviated rather than `pr-str`'d in the trace. | Proposed |
| CR-BT-32 | `behavior-tree/interface.clj` MUST export `input-violations`, so a caller can assert the CR-BT-26 contract without an LLM round-trip. | Implemented |

---

## 7. Open questions

1. ~~**Which brick owns the registry?**~~ **Deferred, correctly.** Phase 0 has
   no mechanism to place, so nothing was speculatively pushed down: the
   declarations live in `agent/core/bt_context.clj` and require nothing. The
   question becomes live again at Phase 2, where the per-node checks ARE
   mechanism and belong in `behavior-tree`, which must not depend on `agent`
   (CR-BT-23) — the same split as the node overrides in `agent/core/bt.clj`.
2. **Does `:lifetime` subsume `st-memory-init`?** Re-opened, and narrower than
   §5.2 originally implied. `:session` is *defined* as "lives in
   `st-memory-init`, so it survives `reset-st-memory!`", and the registry
   records 7 such keys — only `:previous-turns` is written back during a turn,
   the rest being seeded once at agent setup. So inverting the relationship
   (make `st-memory-init` membership derive from `:lifetime :session` rather
   than the reverse) is a coherent refactor with one real writer to reroute.
   But it closes no gap: `st-memory-init` membership already IS the mechanism,
   and nothing today is hand-maintained for want of the declaration. It alters
   CR-BT-12, needs its own migration, and should be done only if something
   else makes it worth the churn.
3. ~~**How much does Phase 1 actually cost per call?**~~ **Resolved by
   shipping.** Deep validation of `:iterations` was kept: it is bounded (the
   list is capped at 10 records) and malli walks structure, not string content,
   so the traversal is trivial next to the network round-trip it precedes. No
   compiled-validator cache — `validate-output` runs `explain`/`humanize` only
   on the failure path, and a cache keyed on a schema form would go stale
   against a `defschemas` re-registration in the REPL, which this codebase has
   already been bitten by once (the `defonce` note in `schema_registry.clj`).
   Keeping it deep is what found the three drifts in §3.6.1.
4. ~~**What does a violation do in production?**~~ **Resolved at field
   granularity rather than globally**, which is better than the log-only default
   this question proposed: `:missing` aborts, `:invalid` logs (§3.6). Phases 2–3
   still need an answer for the per-node checks, where the assert gate applies
   and the same split may not.
5. ~~**Does Phase 1 leave the other signatures unprotected?**~~ **Closed: no.**
   Seven call sites reach a signature without the BT dspy node — `skill_distill`
   (×2), `skill_refine`, `memory_agent/commands` (×3) and `memory/core/extract`.
   All pass inputs as an explicit literal map at the call site
   (`{:skill-name (str skill-name) :current-skill-md (str current-md) …}`)
   rather than gathering them from the blackboard, so there is no silent
   nil-drop to catch: the caller names every input inline and a missing one is a
   visible `nil` in code, not an absent key on a shared atom. CR-BT-26 is
   correctly scoped to where the failure mode exists, not under-scoped.
6. **Do model-authored tools get to see the registry?** `clj-sandbox`'s context
   accessors expose agent state to LLM-written code. Declaring a key
   `:sensitive?` should plausibly hide it there too, which makes the registry a
   security surface and not only a documentation one.

---

## 8. As-built map

| Phase | Contracts | Implementation | Tests |
|---|---|---|---|
| **0** | **CR-BT-25** | `agent/core/bt_context.clj` — `context-keys` (54 declared), `deliberately-undeclared` (21, each with a reason), and the accessors `declared?` / `keys-with-lifetime` / `persistable-keys` / `opaque-keys` / `writers-of` / `schema-of` / `coverage`. Zero dependencies: schemas are late-resolved keywords, writers unresolved symbols. | `agent/test/…/core/bt_context_test.clj` — 10 tests, 402 assertions, incl. 135 writer symbols and 9 schema keywords resolved |
| **1** | **CR-BT-26, CR-BT-27, CR-BT-32** | `behavior_tree/core/dspy_action.clj` — `signature-fields`, `field-violation`, `input-violations`, `violations->message`, `filter-declared-outputs`, and the `cond` in `dspy`. Exported as `bt/input-violations` from `behavior_tree/interface.clj` so an agent can assert its own contract without an LLM call. | `behavior_tree/test/…/dspy_action_test.clj` (10 tests); `agent/test/…/coact_agent_test.clj` — one case per real writer into `:iterations` |
| 2 | CR-BT-28, CR-BT-31 | — | — |
| 3 | CR-BT-29, CR-BT-30 | — | — |

**Fixes landed with Phase 1** (both surfaced BY the new check — see §3.6.1):

| Fix | File |
|---|---|
| `::eval-entry`'s three task-lifecycle keys marked `{:optional true}` — they were required keys that `sanitize-eval-entry` never writes | `agent/common/coact_agent.clj` (`defschemas coact-domain`) |
| The harvest-resolve path now runs `sanitize-eval-entry`, like every other write into `:iterations` — it was emitting `:result nil`, no `:parallel?`, and skipping context-budget truncation | `agent/common/coact_agent.clj` (`resolve-pending-entries!`) |

**Verified:** `bb test:ns` green across `dspy-action-test`, `coact-agent-test`,
`bt-test`, `engine-test`, `nodes-test`, `examples-test`,
`coact-agent-step-f-test`, `coact-agent-task-owner-test`, `integration-test`
(166 + 143 tests). `bb poly check` OK. Two live Bedrock turns
(`amazon.nova-lite-v1:0`) — a direct answer and a multi-iteration code-block
turn that populates `:iterations` with real eval entries — both correct, with
**zero** `::dspy-input-schema-drift`, `::dspy-missing-declared-inputs` or
`::dspy-undeclared-outputs` events across 3 LLM calls.

---

## 9. Source references

Facts in §1 are anchored to these locations as of the writing of this doc.
Rows naming a line in `dspy_action.clj` describe the code **as it was before
Phase 1**; they are named by form rather than line so they stay findable now
that the file has changed.

| Claim | Source |
|---|---|
| Context contract is two keys | `behavior_tree/core/engine.clj:13-22` |
| Nil inputs dropped; one input suffices | `dspy_action.clj` — the `all-inputs` gather (CR-BT-26 now guards it) |
| Model outputs merged unguarded | `dspy_action.clj` — the `reduce-kv assoc` merge (CR-BT-27 now filters it) |
| `missing-inputs` only when all absent | `dspy_action.clj` — the `:else` branch of `dspy`'s `cond` |
| Sub-BT blind merge | `agent/core/bt.clj:161` |
| `:dirty-keys` hand-maintained | `agent/core/bt.clj:182-200` |
| Trace `pr-str`s arbitrary values | `agent/core/bt.clj:402` |
| Tracing overrides — the single check point | `agent/core/bt.clj:416, 441` |
| Key→schema mapping already exists | `agent/common/coact_agent.clj:505-518` |
| Malli registry already exists | `clj_llm/core/schema_registry.clj` |
| Outputs validated, inputs not | `clj_llm/core/chain_of_thought.clj:151`, `predict.clj:114` |
| Only 4 schema-checked nodes | `coact_agent.clj:5766, 5804`; `acp_agent.clj:775, 790` |
