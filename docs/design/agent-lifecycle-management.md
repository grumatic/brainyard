# Agent lifecycle management — always-alive subagents + registry control

> Every subagent a parent dispatches stays alive in the agent registry as a
> askable instance, until it is closed, cancelled, LRU-evicted at the
> per-session cap, or the session tears down. An `agent-registry$*` tool family
> lets the LLM list, inspect, resume, and close those instances. There is no
> opt-in flag — persistence is the default and only behavior.

## 0. Status

**Implemented.** This doc describes the shipped design. §1–§3 are the pre-existing
seams it builds on; §4+ is the current model.

The design went through one earlier iteration: an opt-in `:keep-alive?` flag that
made persistence a per-dispatch choice (ephemeral by default). Live testing with
a real model (claude-code/opus) showed that framing didn't work — the model
rarely set the flag when it should, and conflated "my subagent detached into a
background task" with "my subagent is an askable instance." The response was to
**remove the choice**: every subagent is always kept alive, the registry is
bounded by an LRU cap instead of a flag, and the substrate/marker only have to
teach *management*, not *when to opt in*. That is what this doc now describes.

## 1. As-is — the agent registry

Every live agent lives in one flat atom, keyed by a globally-unique instance-id
`:<defagent-type>/<suffix>` (e.g. `:exec-agent/crimson-parrot-42`):

- `!agent-registry` — `components/agent/src/ai/brainyard/agent/core/agent.clj`.
- `register-agent` / `unregister-agent` / `get-agent` / `list-agents` /
  `list-agents-for-session` (filters the flat registry by `proto/session-id`).
- `generate-instance-id` mints the suffix via `util/gen-random-words`.

`!session` carries `user-id` / `session-id`; the registry does not.
`get-or-create-agent` is **idempotent by id** — addressing a live instance by id
returns the existing record with its accumulated `!state` / `!session`. That is
what makes resume a lookup, not a rebuild.

## 2. As-is — subagent dispatch is a tool call

Subagents are `:agent`-type tools. A code-block `(explore-agent {…})` routes
through the sandbox binding → `call-tool` → `do-call-tool--agent`
(`core/tool.clj`), which enforces the dispatch guards (kill-switch
`enable-subagent-calls`, depth limit `*call-depth*`, circular detection
`*call-chain*`), mints a fresh instance-id, and invokes the agent's `run-agent`
body. JSON tool-call dispatch reaches `run-agent` through the bound-fn path;
`run-agent` is the common sink for both.

`.close` (a `java.io.Closeable` on the Agent record) stops the run, unregisters
from `!agent-registry`, releases the sandbox, and stops the memory-capture
pipeline if it was the last sibling on the manager.

## 3. As-is — where a subagent's turn history lives

Three stores at three scopes; resume depends on the third:

- `!session :messages` — session-scoped, **shared across sibling agents**. The
  cross-agent transcript ("parent dispatched X, got Y"), *not* a subagent's
  working history.
- `st-memory :conversation` — within-turn per-instance scratch.
- **`st-memory-init :previous-turns`** — across-turn per-instance history.
  `store-results` (coact post-loop bookkeeping) folds each finished turn
  (question + compact iterations + answer) into it; `reset-st-memory!` re-seeds
  the working `st-memory` from `st-memory-init` at the start of every ask, so it
  renders as `## Previous Turns`. This lives on the instance's own `!state` — so
  **as long as the instance stays registered, a follow-up ask sees its own
  history for free.** Keeping instances alive is exactly what unlocks resume.

## 4. Model — every subagent is a live, managed instance

`run-agent`'s subagent dispatch passes `:auto-close? false`, so a dispatched
subagent is **never closed on answer**. It stays in the registry as a *managed
instance* and is addressable by its instance-id for follow-up. The lifetime ends
only at one of four events:

1. **Explicit close** — `agent-registry$close` (or `/agent close`).
2. **Task cancellation** — cancelling the background task wrapping the
   subagent's call (`task$cancel`) closes the instance.
3. **LRU eviction** — dispatching a new subagent when the session is at the
   per-session cap evicts the least-recently-used non-running one.
4. **Session teardown** — closing the session closes its instances (cascade).

### 4.1 `:lifecycle` on the instance

`@!state :lifecycle` carries:

```clojure
{:owner                 :coact-agent/…   ; parent instance-id; nil for a root agent
 :share-parent-session? false            ; session-sharing sibling vs dispatched worker
 :answers               n                ; completed asks
 :created-at            <ms>
 :last-ask-at           <ms>             ; drives LRU
 :last-question         "…"}             ; truncated, for list/detail}
```

There is **no `:mode`** — the ephemeral/persistent split is gone. Everything keys
off `:owner`: a non-nil `:owner` marks a *managed subagent* (`subagent?`) —
evictable, closeable, askable, and a cascade target. A root agent (`:owner`
nil) is none of those. `mark-ask-start!` / `mark-ask-done!` maintain the ask
bookkeeping in `ask`.

### 4.1.1 Two kinds of subagent — `:share-parent-session?`

A subagent used to mean exactly one thing: *dispatched by a BT to do a job*,
borrowing the parent's session to emit output and dying when it answers. ACP
connections (`acp$create`) are the other kind — **peers inside the user's own
session** — and they were originally modelled by simply having no parent at all.
That made them read as roots everywhere it mattered: they clobbered the
session's resume identity in `meta.edn` (patched with a per-defagent exception),
they passed the reach fences as roots, and — because `create-agent` falls back
to `get-or-create-session` with no store — they got a **standalone session atom
carrying the same session-id**, so the usage tracker, the `:total-turns` audit
index and the session config silently diverged from the root's.

Both kinds now carry a parent, and `:share-parent-session?` (default **false**)
says which is which. Three predicates in `core/agent.clj`:

| predicate | true for |
|---|---|
| `subagent?` | anything with an `:owner` — both kinds |
| `share-parent-session?` | session-sharing siblings only |
| `dispatched-subagent?` | workers only — `subagent?` minus the above |

`dispatched-subagent?` is what most callers want, and it is what the four
behaviours below branch on:

- **LRU cap / eviction** (§5) counts and evicts workers only. A sharing sibling
  has its own bound (`:max-acp-agents-per-session`), and an idle ACP connection
  is idle because nobody has asked it yet, not because it is stale.
- **Parent-close cascade** (§6) collects workers only. A connection opened by a
  subagent belongs to the user's session and outlives whoever opened it.
- **L2 capture** keeps root turns *and* sharing-sibling turns: a second model in
  the user's own conversation is exactly the material that layer remembers. A
  worker's `ask/post` stays operational detail.
- **TUI rendering** puts workers in the consolidated subagents block and the
  shared sub-output tab; a sharing sibling renders itself where the user is
  (an acp-agent has its own transcript block), so it is skipped.

Everything that is genuinely about *lineage* — `:owner`, the reach fences,
`root-of-agent`, persist's "may I claim the session's resume identity" guard —
uses the parent link and therefore covers both kinds without special cases.

### 4.2 Surfacing the askable id (always)

Because the instance-id is minted inside `do-call-tool--agent`, the dispatch
result **always** carries `:subagent-id` (colon-less `ns/name`, round-trips
through `(keyword …)`), `:askable? true`, and an `:ask-hint` naming the exact
`agent-registry$ask` / `$close` calls. Without this the caller would hold a
live instance it can't address. When a dispatch evicts to stay under the cap, the
result also carries `:evicted-subagents [ids]`.

## 5. LRU cap — the sole resource bound

`:max-subagents-per-session` (config, default 8) bounds live subagents per
session. On **every** subagent dispatch, `evict-subagents-to-cap!` closes
least-recently-asked **non-running** subagents until the session is below the cap,
so after adding the new one the count is ≤ cap (`core/agent.clj`, called from
`do-call-tool--agent`). Best-effort: a running subagent is never evicted, so the
cap can be briefly exceeded when everything is busy. Eviction emits
`::subagent-evicted-lru`.

There is **no time-based idle reaping** — the LRU cap is the only bound, matching
the "lives until close/cancel/evict" model. (An earlier `:persistent-agent-idle-ms`
sweep + `agent-registry$sweep` were removed as redundant with the hard cap.)

## 6. LLM-facing tools — the `agent-registry$*` family

Lifecycle verbs extend the existing `agent-registry$*` family (registry-scoped,
already inherited by every coact/react-derived agent via `default-agent-roster`).
The naming matches the convention: `agent-registry$*` operates over the set of
instances; `agent-runtime$*` operates on the running self.

- **`agent-registry$list`** — live instances (root + subagents) with `:owner`,
  `:idle-ms`, `:answers`, `:last-question`; optional `:session-id` filter (omit
  → all sessions).
- **`agent-registry$detail {:id …}`** — one instance deeper: status, lifecycle
  timestamps, latest reasoning, last answer.
- **`agent-registry$ask {:id … :question …}`** — follow-up ask to a live
  instance; reuses its `## Previous Turns`. Guards: not-found, `:running` (busy),
  depth limit, and the ask reach fence (see §6.1). Increments `*call-depth*` /
  `*call-chain*` so nested asks obey the same limits.
- **`agent-registry$close {:id …}`** — close + reclaim; cascades to owned
  subagents. Guards: not-found, `:running`, ownership.

`->instance-id` tolerates a leading colon (the model sometimes copies a printed
`:ns/name` keyword). The mutating verbs honor the `:enable-subagent-calls`
kill-switch; the read verbs stay ungated.

### 6.1 Ownership & reach scoping

`agent-registry$close` is gated by `authorize-instance-op`: same-session only,
and a subagent may act only on instances it dispatched (`:owner` = caller); a
top-level caller (root, or no `*current-agent*`) bypasses the ownership check and
manages any instance in its session.

`agent-registry$ask` is gated by the narrower `authorize-ask` fence — the target
is resolved across the **whole** registry (so a root can reach a sibling root),
then bounded for safety:
- **root** caller — may ask a **sibling root** (any other root, `:owner` nil) OR
  a subagent in its **own session** (its own subtree); **not** another root's
  subagents.
- **subagent** caller — may ask **only** instances it directly dispatched
  (`:owner` = caller). Never its root, siblings, or other roots — a subagent
  asking upward could loop, so it is fenced to its own children.
- **nil** caller (programmatic/test; a TUI colon-command dispatches *as* the
  active root) — unrestricted.

Both verbs honor the `:enable-subagent-calls` kill-switch; the read verbs stay
ungated.

## 7. Task cancellation closes the instance

A slow subagent call detaches into a background task (`adopt-tool-into-task`).
Its `on-cancel` (fired by `task$cancel`) both `cancel-run`s the subagent's state
(cooperative abort, cascading to descendants via the parent-chain `cancelled?`
walk) **and** `force-close-instance!`s it — so a cancelled subagent leaves the
registry (bypassing the normal no-close-while-running guard, since a cancel
target is by definition running). Normal task *completion* does not close the
instance — it stays alive for resume.

## 8. Detach marker

When a subagent call detaches, the "STILL RUNNING" marker carries a single
lifecycle note: the subagent stays alive as instance `<id>`; `task$wait` for THIS
call to finish, then ask it more via `agent-registry$ask` with that
instance-id — **not** the task-id. This is the mechanism-level counter to the
model conflating a background task with an askable instance (the failure mode
that motivated the redesign): it fires exactly when the model forms its mental
model. In live testing the note reliably kept the model from calling a task
askable.

## 9. Cascade & TUI parity

- **Parent-close cascade** — closing an instance closes the subagents it owned
  (`cascade-close-owned!` on the `:agent.instance/closed` hook; walks
  `list-agents-for-session`, filters `owned-subagent?`). Closing a child fires its
  own hook, cascading down the tree. Emits `::subagent-cascade-closed`.
- **TUI** — `agent-registry$*` and the human `/agent` verbs are two front-ends
  over one registry. `/agent status` shows a Lifecycle line (root vs. subagent +
  owner); `/agent close` shares the running-guard concept. A subagent an LLM
  keeps alive is `/agent switch`-able by the human — same registry entries.

## 10. Config

| Key | Default | Meaning |
|---|---|---|
| `:enable-subagent-calls` | `true` | Kill-switch: gates subagent dispatch **and** the mutating `agent-registry$*` verbs **and** the substrate section. |
| `:max-subagents-per-session` | `8` | Per-session cap on live subagents; a dispatch at the cap LRU-evicts the least-recently-used non-running one. |

## 11. System-prompt substrate

`## Subagents (agent lifecycle substrate)` (`agent-roster/subagent-substrate-protocol`)
is installed in the coact system-context and inherited by every coact/react-derived
agent (same mechanism as the todo/exec/skill/MCP substrates), **gated on
`:enable-subagent-calls`**. It teaches: every dispatched subagent stays alive and
remembers its turns; capture the returned `:subagent-id`; **ask** it by that
id for follow-ups (prefer asking over re-dispatch when the follow-up builds on
prior work); **list/detail** to inspect; **close** when done (so a useful one
isn't LRU-evicted); and the instance-vs-task distinction. No opt-in flag to
explain — management is the whole surface.

## 12. Design notes

- **Per-instance history needs no plumbing** — `st-memory-init :previous-turns`
  (§3) already carries it; keeping the instance registered is the whole trick.
- **Why remove the flag** — a per-dispatch opt-in put the decision on the model
  at exactly the wrong moment (first dispatch, before it knows it'll follow up),
  and it defaulted to the task mental model for slow subagents. Always-alive +
  LRU removes the decision; the model only manages what already exists.
- **Why LRU over idle-reap** — a hard per-session cap bounds resources
  deterministically; time-based reaping was redundant and added a config knob +
  a sweep path for no extra safety.
- **Subagent-id is colon-less** and both the surfaced id and the `->instance-id`
  parser tolerate the `:ns/name` form, so the round-trip never breaks on how the
  model copies the id.
