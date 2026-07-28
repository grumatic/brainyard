# Feature-Based Configuration — Design Proposal

> Status: **P0 implemented** (2026-07-28), P1–P3 proposed. P0 shipped
> `components/agent/src/ai/brainyard/agent/core/feature.clj` +
> `components/agent/test/ai/brainyard/agent/core/feature_test.clj` (27 tests,
> 403 assertions) and the `:feature` surface on `agent-runtime$config`.
> P1 shipped the `on?` / `on?*` / `off-reason` chokepoint, migrated every gate
> read (20 sites, then the 21 the design's table had missed), and closed the
> §1.5 graph-memory split by routing the memory hooks on the manager's stamped
> `:graph-enabled?` rather than on config (§10.6). P2 shipped the surfaces —
> `feature$*` and `/feature`; its config-layer items are outstanding (§10.7).
> `bb test` (456 namespaces) and `bb poly check` green. Amendments the
> implementation forced are in §10; §9 Q1/Q3 are resolved there.
>
> Proposes a `feature-registry`
> layered over the existing `config-schema` in
> `components/agent/src/ai/brainyard/agent/core/config.clj`, so the 137 flat config
> keys become 9 families / 40 features (30 gated, 10 ungated groupings), each gated
> feature with one on/off knob, declared dependencies, and a single enforcement
> chokepoint.
>
> Related: `docs/design/config-agent-design.md` (§13 Q1 chose the flat namespace —
> this proposal keeps that storage decision and adds a *view* over it),
> `docs/design/context-graph-memory-design.md`, `docs/design/self-improve-design.md`,
> `docs/design/event-bus-and-reactor.md`, `docs/design/state-machine-design.md`.

## 1. Problem

`config-schema` is a single flat map of **137 keys**. It is the right storage model
(one namespace, one precedence chain, one persistence path — `config-agent-design.md`
§13 Q1 settled this deliberately). But it is the wrong *presentation* model, and the
flatness has started to leak into behaviour. Six concrete symptoms, all verified
against the current tree:

**1.1 — Feature membership exists only in comments.** The schema has ~137 `:doc`
strings, `:type`, `:default`, `:env-fn` (28), `:requires-restart` (13),
`:read-only` (2). It has no `:category`, `:group`, `:feature`, or `:tags`. The only
grouping is a handful of `;; --- …` comments inside the `def` — e.g.
`;; --- coact-repair-action recovery budgets ---`, `;; Context-graph memory overlay`,
`;; Self-improvement loop (R1 …)`. Comments are not data: nothing can query them,
no test can enforce them, and they drift silently.

**1.2 — Every user-facing surface is a flat 137-item list.** `/config` with no args
prints all 137 keys alphabetically. The `/config` autocomplete submenu
(`autocomplete.clj:248`) builds one entry per schema key — a 137-item scroll.
`agent-runtime$config :query` is a substring match over key name + type + doc, with
no prefix, glob, or ranking. Setting an unknown key dumps all 137 names as the
error message. There is no way to ask "what does the memory feature consist of?"
short of already knowing the answer.

**1.3 — A feature's knobs are meaningless when its gate is off, and nothing says
so.** Ten `:graph-*` keys are individually settable and individually documented
"only when `:enable-graph-memory`". Turning the gate off leaves ten live-looking
knobs that do nothing. Same for the three `:skill-distill-*` keys under
`:enable-skill-distillation`, `:memory-consolidate-every-n-turns` under
`:enable-memory-consolidation`, `:hold-max-wait-ms` under `:enable-iteration-hold`,
`:auto-park-after-polls` under `:enable-auto-task-notify`.

**1.4 — Dependencies between features are hand-coded at read sites, or missing.**
Three cases, three different treatments:

- **Encoded ad-hoc:** `common/memory_agent/hooks.clj:204` reads
  `(or (get-config :enable-memory-consolidation) (get-config :enable-graph-memory))`.
  The comment above it is honest about why — "we imply consolidation from it,
  derived at this read site rather than baking a second default that would drift."
  That is the right instinct and the wrong location: the implication is a fact about
  the features, not about that one call site.
- **Not encoded at all:** `:enable-fsm` with `:enable-scheduler` false. Timed and
  eventless (`:always` / `:after`) transitions advance only on the scheduler tick
  (`fsm.clj:444`), so an FSM with those transitions silently never advances. The
  dependency exists only as prose inside `state_machine_agent.clj`'s LLM prompt.
- **Undeclarable:** `:enable-graph-memory` genuinely requires `:enable-memory-capture`
  (no episodes ⇒ nothing to extract from). Nothing checks this.

**1.5 — Gates are enforced in scattered places, and one is actively inconsistent.**
`:enable-subagent-calls` is checked at five independent `cond` branches
(`core/tool.clj:317`, `common/commands.clj:176` and `:205`,
`common/acp_commands.clj:131` and `:185`), each returning its own error string; a
sixth entry point has to remember to add a sixth check. Worse,
`:enable-graph-memory` is `:requires-restart true` and baked once at
`core/memory.clj:203`, but then re-read **live** at six sites in
`common/memory_agent/hooks.clj` (L205, L243, L267, L344, L397, L445). Toggling it on
mid-session flips all six runtime branches into graph mode while the memory manager
has no `:extract-fn` / `:embed-fn` — the graph reducer runs against a storage-only
graph. The flag's own restart contract is violated by its own consumers.

**1.6 — Dead knobs are indistinguishable from live ones.** `:enable-budget-monitoring`
is in the schema, settable, documented — and has **zero read sites** in the
workspace. Meanwhile the live `.brainyard/config.edn` in this very repo carries four
keys that are not in the schema at all and therefore inert:
`:enable-analytics` (removed with the async analytics path),
`:enable-memory-essence` (retired in memory-agent-design rev 3),
`:enable-finalize-answer` (docs only), and `:nrepl-grant`. A user who set those
believes they took effect. Nothing tells them otherwise.

Note also a fifth class of confusion: `:enable-parallel`, `:enable-compaction`,
`:enable-structure-aware`, `:enable-budget` look like config keys but live entirely
in `components/clj-sandbox/.../chat.clj` — `:enable-parallel` as a caller-facing opt
(`chat.clj:367`), the other three as internal locals derived from *differently named*
caller opts (`chat.clj:413` `(get compaction-opts :enable false)`, `:418`
`(get feedback-opts :structure-aware false)`, `:421` `(get budget-opts :enable
false)`). None is resolved through `get-config`. Two of them then leak into TUI
event rendering as event fields (`tui/format.clj:1807` `:enable-compaction`, `:1808`
/`:1911`/`:1915` `:enable-budget`), which is where the grep confusion actually bites.

**What all six share:** the schema knows what each key *is*, but nothing knows what
each key *belongs to*. That missing relation is the whole proposal.

## 2. Design principles

1. **A view, not a rewrite.** Storage stays exactly as it is: one flat
   `[:agent :config]` subtree, one precedence chain, `config-agent`'s allowlist
   untouched. The registry is metadata *over* the schema.
2. **Reuse the existing `:enable-*` keys as gates.** No parallel flag namespace, no
   migration of persisted values. A feature's gate *is* a schema key that already
   exists — 24 of the 30 gates are keys shipping today. Six new gate keys are
   proposed in §4 for features that currently have none; they are marked
   `:proposed true` and land in P3, not P0 (see §7).
3. **Declared, not derived-at-call-site.** `:implies` / `:requires` become registry
   data. The ad-hoc `or` at `memory_agent/hooks.clj:204` is deleted, not duplicated.
4. **One chokepoint.** `feature/on?` replaces scattered `get-config :enable-…` reads.
5. **Enforced by test.** Every schema key must be claimed by exactly one feature or
   explicitly listed as ambient. This is what stops the classification from rotting
   the way the `;; ---` comments did — mirroring the existing
   `every-schema-key-has-doc` invariant in `core/config_test.clj:83`.
6. **Precedence is not extended upward.** Feature env vars are implemented *as* the
   gate key's `:env-fn`, so they enter the existing chain at the existing top layer.
   Only one new layer is added, at the very bottom (profiles, §6.3).

## 3. The model

Three kinds of key, currently indistinguishable:

| Kind | Count | What it is | Gateable? |
|---|---|---|---|
| **Gate** | 24 today (+6 proposed) | On/off for a capability — 22 boolean, plus 2 numeric-zero gates (`:max-refinements`, `:tool-cache-ttl`) | it *is* the gate |
| **Knob** | 86 | Tunes a capability; meaningless when its gate is off | inherits its feature's state |
| **Presentation** | 16 | TUI rendering (`ui`, §4.10) | never |
| **Ambient** | 10 | Always in effect; belongs to no capability (`:lm-config`, `:permission-mode`, `:allowed-dirs`, …) | never |
| **Unclassified** | 1 | `:enable-budget-monitoring` — zero readers (§1.6, §4.9) | n/a — must be wired or deleted |

24 + 86 + 16 + 10 + 1 = 137.

A **feature** is `{gate, knobs, lifecycle, requires, implies}`. A **family** is a
namespace of features sharing one master knob. Ambient keys live in an explicit
`ambient-keys` set — named, so the test can prove the partition is total.

```
family   memory
  ├─ memory/capture         gate :enable-memory-capture        [on ]  startup
  ├─ memory/recall          gate :enable-memory-recall  (new)  [on ]  live
  ├─ memory/mid-turn-recall gate :enable-mid-turn-recall       [off]  live
  ├─ memory/consolidation   gate :enable-memory-consolidation  [off]  live
  ├─ memory/graph           gate :enable-graph-memory          [off]  startup
  └─ memory/project         gate :enable-project-memory        [on ]  live
```

`/feature memory off` disables the family. `/feature memory.graph on` enables one
feature and, via `:implies`, `memory/consolidation` with it — and, via `:requires`,
refuses (visibly) if `memory/capture` is off.

## 4. The classification

Nine families, 40 features (30 gated, 10 ungated groupings), plus `ui` and
`ambient`. An *ungated grouping* has no on/off knob — it exists so its knobs have a
discoverable home. `[on]`/`[off]` is the proposed default (matching today's default
unless noted). Lifecycle: **startup** = baked at boot (needs restart), **session** =
installed once per session, **live** = re-read per turn/event.

Every one of the 137 schema keys appears exactly once below (§4.1–§4.11); the
partition is total by construction and enforced by test (§5.6).

### 4.1 `memory` — L1/L2/L3 store, graph overlay, project memory

| Feature | Gate | Def | Lifecycle | Knobs | Deps |
|---|---|---|---|---|---|
| `memory/capture` | `:enable-memory-capture` | on | startup | `:memory-question-max-chars` `:memory-answer-max-chars` | — |
| `memory/recall` | **new** `:enable-memory-recall` | on | live | `:recall-limit` `:memory-recall-snippet-chars` | requires `capture` |
| `memory/mid-turn-recall` | `:enable-mid-turn-recall` | off | live | — | requires `recall` |
| `memory/consolidation` | `:enable-memory-consolidation` | off | live | `:memory-consolidate-every-n-turns` | requires `capture` |
| `memory/graph` | `:enable-graph-memory` | off | startup | `:graph-embed-model` `:graph-extract-model` `:graph-extract-mode` `:graph-extract-max-input-chars` `:graph-max-entities-per-episode` `:graph-max-relations-per-episode` `:graph-extract-batch-episodes` `:graph-max-nodes` `:graph-max-edges` `:graph-prune-orphans?` | requires `capture`; **implies `consolidation`** |
| `memory/project` | `:enable-project-memory` | on | live | `:project-memory-max-chars` | — |

`:show-memory-activity` is a display knob → `ui` (§4.10). The `memory/graph`
`:implies consolidation` edge is exactly the `or` currently living at
`memory_agent/hooks.clj:204`, promoted to data.

**Lifecycle is per-feature but admits per-key exceptions.** `memory/graph` is
`:lifecycle :startup`, and 11 of its 12 keys are `:requires-restart` today. The
exception is `:graph-extract-batch-episodes` (`config.clj:201`), whose own doc says
*"Read fresh at each graph-build/reduce (no restart)"* — it is consumed by the
manual `memory graph-build` / `reduce` path, not baked into the extractor. So the
registry needs a `:live-keys #{:graph-extract-batch-episodes}` escape hatch on the
feature, and §5.5's derivation must subtract it. Without that, the derived
`restart-required-keys` would be 14 keys where the schema and
`config_test.clj:152` say 13.

### 4.2 `self-improve` — R1 distillation / refinement / nudges

| Feature | Gate | Def | Lifecycle | Knobs | Deps |
|---|---|---|---|---|---|
| `self-improve/distillation` | `:enable-skill-distillation` | off | live | `:skill-distill-mode` `:skill-distill-every-n-turns` `:skill-distill-threshold` | — |
| `self-improve/refinement` | `:enable-skill-refinement` | off | live | — | — |
| `self-improve/nudges` | `:enable-self-improve-nudges` | off | live | — | requires `distillation` **or** `refinement` |

The nudge surfaces pending proposals; with neither producer enabled it can never
fire. Today that is silent. `:requires` with disjunction support (§5.2) makes it a
visible degraded state.

### 4.3 `automation` — scheduler, reactions, FSM, hooks, gateway

| Feature | Gate | Def | Lifecycle | Knobs | Deps |
|---|---|---|---|---|---|
| `automation/scheduler` | `:enable-scheduler` | on | session | `:scheduler-tick-ms` | — |
| `automation/reactions` | `:enable-reactions` | on | session | `:max-reaction-fires-per-session` | — |
| `automation/fsm` | `:enable-fsm` | on | session | `:fsm-allow-code` | **requires-partial `scheduler`** (timed/eventless transitions only) |
| `automation/hooks` | `:enable-user-hooks` | on | live | — | — |
| `automation/gateway` | **new** `:enable-gateway` | off | session | `:gateway-pair-code-ttl-ms` | — |

`requires-partial` (§5.2) is the honest encoding of the FSM/scheduler relation: the
FSM still works event-driven without the ticker, it just cannot advance `:always` /
`:after` transitions. A hard `:requires` would be wrong; silence is what we have now.

### 4.4 `context` — prompt assembly and budgeting

| Feature | Gate | Def | Lifecycle | Knobs | Deps |
|---|---|---|---|---|---|
| `context/budget` | `:enable-context-budget` | on | live | `:context-budget-safety-ratio` `:max-context-tokens` `:rebudget-every-n-iter` | — |
| `context/compaction` | **new** `:enable-cross-turn-compaction` | on | live | `:compaction-target-ratio` | requires `budget` |
| `context/live-artifacts` | **new** `:enable-live-artifacts` | on | live | `:reference-artifact-paths` `:live-artifact-max-chars` | — |
| `context/console-activity` | `:enable-console-activity` | on | live | `:console-activity-max-entries` `:console-activity-result-chars` | — |
| `context/conversation` | *(ungated)* | — | live | `:conversation-limit` `:conversation-style` `:conversation-keep-verbatim` | — |

**`:enable-context-budget` is today a master switch for three separate mechanisms** —
turn-init budget (`coact_agent.clj:1831`), per-iteration rebudget (`:2434`), *and*
cross-turn auto-compaction (`agent_tui/core.clj:376`). `:compaction-target-ratio`
documents itself as "gated by `:enable-context-budget`". Splitting out
`context/compaction` with `requires budget` preserves today's behaviour exactly
(compaction off when budget is off) while making it separately controllable —
the classification exercise is what surfaces this.

The new key is deliberately **not** named `:enable-compaction`: that exact keyword
already exists as a clj-sandbox internal local that leaks into TUI event rendering
(§1.6), and reusing it would turn a grep hazard into a real ambiguity.

### 4.5 `exec` — code channel, sandbox, tasks, GC

| Feature | Gate | Def | Lifecycle | Knobs | Deps |
|---|---|---|---|---|---|
| `exec/code-channel` | `:code-channel?` | on | session | `:clj-backend` `:exec-backend` `:sandbox-interop` | — |
| `exec/sandbox-persistence` | `:enable-sandbox-persistence` | on | live | — | requires `code-channel` |
| `exec/nrepl` | `:nrepl-enabled?` | off | session | `:nrepl-port` `:nrepl-host` | requires `code-channel` |
| `exec/tasks` | *(ungated)* | — | live | `:task-timeout-ms` `:task-heartbeat-interval-ms` `:fast-eval-timeout-ms` `:auto-background-timeout-ms` | — |
| `exec/task-notify` | `:enable-auto-task-notify` | on | live | `:auto-park-after-polls` | — |
| `exec/iteration-hold` | `:enable-iteration-hold` | off | per-action | `:hold-max-wait-ms` | — |
| `exec/gc` | **new** `:enable-artifact-gc` | on | live | `:task-retention-count` `:task-retention-days` `:coact-scratch-max-age-hours` `:sandbox-cache-max-files` `:sandbox-cache-max-bytes` `:sandbox-cache-max-age-days` | — |

`exec/tasks` groups the three thresholds that govern a unit of work's **task
lifecycle**, in the order they apply: `:fast-eval-timeout-ms` decides whether the work
is promoted to a tracked task at all (0 = always create one), `:task-timeout-ms`
bounds that task, and `:auto-background-timeout-ms` decides when a still-running
foreground task detaches into background mode. Keeping them together means the three
knobs a user tunes as a set are read as a set.

**`:fast-eval-timeout-ms` belongs here rather than under `exec/code-channel` because
it governs tool calls, not just code.** It is read at exactly two sites, and only one
of them is the code path:

| Read site | Governs |
|---|---|
| `coact_agent.clj:2656` (`coact-tool-dispatch-action`) | **every tool call** — fed as `:fast-eval-ms` into `tool/call-tool-with-fast-eval` (`core/tool.clj:1248`), including subagents invoked as tools |
| `coact_agent.clj:3561` (code-block dispatch) | ```clojure``` blocks in the SCI/sandbox path |

So with `:code-channel? false` — the tool-only posture that `react-agent` pins — the
knob is still fully live on the tool path. Filing it under `exec/code-channel` would
have made the `/feature` view claim it was inert exactly when it was not.

**This also surfaces a wrong `:doc` string.** `config.clj:127` describes the key as
*"Clojure code runs inline first and is promoted to a tracked task only if it exceeds
this (0 = always create a task; not applied to bash)"* — which reads as
Clojure-exclusive and never mentions tools. `docs/core/task.md:389` and the
`call-tool-with-fast-eval` docstring both describe the tool path correctly, and
`CHANGELOG.md:132` is explicit ("When a tool call — including a subagent invoked as a
tool — runs past its fast-eval window and is detached into a background task…"). The
schema doc is simply stale. Worth a P3 fix, and a data point for §1.1: when a key's
only statement of what it belongs to is a prose `:doc`, that prose drifts and nothing
catches it. A `:keys` list in a registry is at least checkable.

### 4.6 `agents` — subagents and ACP

| Feature | Gate | Def | Lifecycle | Knobs | Deps |
|---|---|---|---|---|---|
| `agents/subagents` | `:enable-subagent-calls` | on | per-call | `:max-agent-call-depth` `:max-subagents-per-session` `:parent-trail-k` | — |
| `agents/acp` | **new** `:enable-acp` | on | session | `:acp-backend` `:acp-backend-opts` `:acp-client-fs` `:acp-timeout-ms` `:acp-permission-timeout-ms` `:max-acp-agents-per-session` | requires `subagents` |
| `agents/explore` | *(ungated)* | — | live | `:explore-persist-threshold` `:explore-auto-persist` `:explore-reuse-volatile-hours` | — |
| `agents/workflow` | *(ungated)* | — | live | `:workflow-auto-finalize` `:research-auto-finalize` | — |

`agents/subagents` is the scattered-gate key from §1.5 — six live reads: five
enforcement `cond` branches (`core/tool.clj:317`, `common/commands.clj:176` and
`:205`, `common/acp_commands.clj:131` and `:185`) plus a snapshot read at
`common/coact_agent.clj:1814` shaping the prompt, and a roster branch at
`coact_agent.clj:1054`. It is the clearest single win for the chokepoint: the five
enforcement sites collapse to one predicate plus one shared denial message, and the
two prompt-shaping reads move to the `on?*` snapshot arity (§5.3).

### 4.7 `reasoning` — the agent loop

| Feature | Gate | Def | Lifecycle | Knobs | Deps |
|---|---|---|---|---|---|
| `reasoning/loop` | *(ungated)* | — | live | `:max-iterations` `:react-loop-mode` `:react-keep-thoughts-n` `:react-keep-observations-n` `:react-keep-iterations-n` | — |
| `reasoning/refinement` | `:max-refinements` **(numeric gate, 0 = off)** | off | live | `:eval-lm-config` | — |
| `reasoning/sub-llm` | *(ungated)* | — | live | `:sub-lm-config` `:llm-query-max-depth` | — |
| `reasoning/recovery` | *(ungated)* | — | live | `:max-retries-on-llm-empty-result` `:max-retries-on-llm-malformed-output` `:max-retries-on-llm-transient` `:max-retries-on-llm-no-action` `:empty-result-retry-base-ms` | — |

`reasoning/refinement` is the one feature whose gate is not a boolean. Rather than
add `:enable-refinement` alongside `:max-refinements 0`, the registry supports
`:gate-pred` (§5.1) — a feature is on when `(pos? v)`. This is worth supporting
because the same shape already exists elsewhere: `:tool-cache-ttl 0` disables the
tool cache (§4.8).

### 4.8 `tools` — tool layer and channels

| Feature | Gate | Def | Lifecycle | Knobs | Deps |
|---|---|---|---|---|---|
| `tools/cache` | `:tool-cache-ttl` **(numeric gate, 0 = off)** | off | live | `:tool-cache-readers` | — |
| `tools/mcp` | *(ungated)* | — | live | `:mcp-allow-tools` | — |
| `tools/ask-channel` | `:ask-channel-enabled?` | on | session | `:ask-timeout-ms` | — |
| `tools/oauth` | *(ungated)* | — | live | `:oauth-qr?` `:oauth-token-store` `:oauth-flow` | — |

### 4.9 `analytics`

| Feature | Gate | Def | Lifecycle | Knobs | Deps |
|---|---|---|---|---|---|
| `analytics/trajectory` | `:enable-trajectory-recording` | on | live | — | — |
| `analytics/scoring` | *(ungated)* | — | live | `:analytics-lm-config` `:analytics-shs-weights` | requires `trajectory` |

`:enable-budget-monitoring` is deliberately **absent** — it has no reader (§1.6). The
registry test will fail on it, which is the point: either wire it to the
clj-sandbox `:budget-opts {:enable …}` path it was presumably meant for, or delete
it. An unclaimed key is now a build failure rather than a mystery.

### 4.10 `ui` — presentation only, never a capability gate

`:show-llm-streaming` `:display-format` `:max-collapsed-lines` `:max-expanded-lines`
`:dispose-think-block` `:dispose-iteration-block` `:dispose-task-block`
`:dispose-agent-block` `:dispose-acp-block` `:acp-message-max-lines`
`:acp-show-thoughts` `:acp-show-final-answer` `:enable-tmux-popup`
`:enable-input-suggestions` `:resume-scrollback-bytes` `:show-memory-activity`

Kept as a family for discovery, but marked `:presentation true` so `/feature ui off`
is rejected — turning off "the UI" is not a coherent operation, and two of its keys
(`:enable-tmux-popup`, `:enable-input-suggestions`) are `:enable-*`-shaped, which is
precisely why they need an explicit home rather than being swept into a gate.

### 4.11 `ambient` — always in effect, no feature

`:lm-config` `:dirs` `:allowed-dirs` `:permission-mode` `:max-output-tokens`
`:max-output-chars` `:claude-code-max-turns` `:include-function-directory`
`:compact-agent-tools` `:inline-usage-guides`

An explicit named set, not a fallthrough. `:allowed-dirs` and `:permission-mode` in
particular must never become gateable — they are the security floor, and R8 of the
config-agent's hard rules already treats them as human-approval-only.

## 5. Mechanism

New brick-local namespace `components/agent/src/ai/brainyard/agent/core/feature.clj`.
It depends on `core/config.clj` (never the reverse), so the schema stays the leaf.

### 5.1 Registry shape

```clojure
(def feature-registry
  {:memory/graph
   {:title     "Context-graph memory overlay"
    :family    :memory
    :gate      :enable-graph-memory
    :keys      [:graph-embed-model :graph-extract-model :graph-extract-mode
                :graph-extract-max-input-chars :graph-max-entities-per-episode
                :graph-max-relations-per-episode :graph-extract-batch-episodes
                :graph-max-nodes :graph-max-edges :graph-prune-orphans?]
    :live-keys #{:graph-extract-batch-episodes}  ; startup feature, but this key
                                                 ; is re-read per graph-build
    :requires  #{:memory/capture}
    :implies   #{:memory/consolidation}
    :lifecycle :startup
    :doc       "Typed entity/relationship graph + vector index layered over the
                L1/L2/L3 FTS store as extra RRF recall signals."}

   :context/conversation
   {:title     "Conversation window"
    :family    :context
    :gate      nil                      ; ungated grouping — knobs only
    :keys      [:conversation-limit :conversation-style :conversation-keep-verbatim]
    :lifecycle :live
    :doc       "..."}

   :context/compaction
   {:title     "Cross-turn auto-compaction"
    :family    :context
    :gate      :enable-cross-turn-compaction
    :proposed  true                     ; gate key not in config-schema until P3
    :keys      [:compaction-target-ratio]
    :requires  #{:context/budget}
    :lifecycle :live
    :doc       "..."}

   :reasoning/refinement
   {:title     "Answer refinement pass"
    :family    :reasoning
    :gate      :max-refinements
    :gate-pred pos?                     ; numeric gate: 0 = off
    :keys      [:eval-lm-config]
    :lifecycle :live
    :doc       "Post-evaluation refinement passes over the draft answer."}

   :automation/fsm
   {:title            "User-defined state machines"
    :family           :automation
    :gate             :enable-fsm
    :keys             [:fsm-allow-code]
    :requires-partial {:automation/scheduler
                       "timed/eventless (:always/:after) transitions never advance"}
    :lifecycle        :session
    :doc              "..."}})

(def ambient-keys #{:lm-config :dirs :allowed-dirs :permission-mode …})
```

Derived once at load: `feature-of-key` (inverted index), `family->features`,
`gate-keys`, `restart-required-keys` (see §5.5).

### 5.2 Resolution

```clojure
(defn feature-state
  "→ {:feature :memory/graph :on? true :source :implied-by :implied-by #{:memory/graph}
      :unmet #{} :degraded {…} :lifecycle :startup :restart-pending? false}"
  [agent f] …)

(defn on? [agent f] (:on? (feature-state agent f)))
```

Four steps, deterministic:

1. **Base.** For each feature, read its gate via `config/get-config` (or apply
   `:gate-pred`). The existing precedence chain is used unchanged: env > per-agent >
   session > `config.edn` > profile > schema default.
2. **Implication closure.** Fixpoint over `:implies`. A feature is on if its base is
   on, or any on-feature implies it. `:source` records which.
3. **Requirement check.** A feature whose `:requires` closure is unmet resolves to
   **off**, with `:unmet` populated. Fail-safe, and — critically — *visible*.
4. **Partial-requirement annotation.** `:requires-partial` does not flip the feature
   off; it populates `:degraded` with the stated consequence string, which surfaces
   in `feature$explain`, `/feature`, and the mulog event.

Steps 2–4 are pure functions of the registry plus the base map, so they are
trivially testable without a live agent.

### 5.3 The chokepoint, and the snapshot problem

`feature/on?` replaces the scattered reads. One wrinkle worth stating up front:
`coact_agent.clj` does **not** call `get-config` per key during a turn — it takes
`cfg-snap (config/get-config-snapshot agent)` at L1605 and L4516 and then reads
`(get cfg-snap :enable-project-memory true)`. So the chokepoint needs two arities:

```clojure
(defn on?  [agent f])          ; live resolution
(defn on?* [cfg-snap f])       ; snapshot-based, same algorithm, no I/O
```

Skipping this would either force a `get-config` per gate per turn (a real cost on
the hot path) or leave the snapshot readers outside the chokepoint — which is how
`:enable-graph-memory` ended up with two contradictory read paths in the first place.

### 5.4 Migration of existing read sites

| Site | Today | After |
|---|---|---|
| `memory_agent/hooks.clj:204` | `(or (get-config :enable-memory-consolidation) (get-config :enable-graph-memory))` | `(feature/on? a :memory/consolidation)` — the `or` becomes the registry's `:implies` |
| `memory_agent/hooks.clj` L243/267/344/397/445 | 5× live `get-config :enable-graph-memory` | 5× `feature/on? a :memory/graph`, resolved against the **startup-baked** value → the mid-session inconsistency in §1.5 disappears |
| `tool.clj:317`, `commands.clj:176/205`, `acp_commands.clj:131/185` | 5 independent `cond` branches + 5 error strings | 1 `feature/require!` helper returning one shared denial |
| `fsm.clj:444` | silent no-advance | `:requires-partial` → `:degraded` note on `fsm$*` results and `/feature` |
| `coact_agent.clj:1054/1698/1739/1814/1831` | `(get cfg-snap :enable-… default)` | `(feature/on?* cfg-snap :memory/project)` etc. |

Total: roughly 27 call sites, all mechanical, all individually revertible.

### 5.5 Lifecycle subsumes `:requires-restart`

Today `:requires-restart` is a per-key boolean on 13 keys, and
`restart-required-keys` is derived from it (`config.clj:478`). But restart-ness is
not a property of a key — it is a property of *when its feature is read*. Every one
of the 13 flagged keys belongs to `memory/capture` or `memory/graph`, both
`:lifecycle :startup`.

Proposal: derive it from the registry instead.

```clojure
(defn- feature-keys
  "All schema keys a feature owns: its gate (when it has one) plus its knobs."
  [f]
  (cond->> (:keys f) (:gate f) (cons (:gate f))))

(def restart-required-keys
  (into #{}
        (comp (filter #(= :startup (:lifecycle %)))
              (mapcat #(remove (:live-keys % #{}) (feature-keys %))))
        (vals feature-registry)))
```

Note `feature-keys` must not blindly `cons` the gate — 10 of the 40 features are
ungated groupings whose `:gate` is `nil`, and a `nil` in the derived set breaks both
this comprehension and the §5.6 equality test. Note also the `:live-keys` subtraction
from §4.1, without which the derived set is 14 keys where the schema says 13.

One source of truth; a key added to a startup feature inherits the restart warning
automatically instead of relying on someone remembering the flag. The existing test
`restart-required-keys-derived-from-schema` (`config_test.clj:152`) becomes
`…-derived-from-registry`, asserting the same 13-key set.

### 5.6 The invariant test — the load-bearing part

Mirrors `every-schema-key-has-doc` (`config_test.clj:83`):

```clojure
(def ^:private live-features
  "Registry minus features whose gate key is not in config-schema yet (§7 P3)."
  (remove :proposed (vals feature-registry)))

(deftest every-schema-key-is-classified
  (let [claimed (into ambient-keys (mapcat feature-keys) live-features)]
    (is (= config-keys (conj claimed :enable-budget-monitoring))
        "every config-schema key belongs to exactly one feature or ambient-keys")))

(deftest no-key-claimed-twice          …)  ; catches copy-paste between families
(deftest gates-are-schema-keys         …)  ; skips :proposed; catches a renamed gate
(deftest gates-are-boolean-or-pred     …)  ; :gate-pred required for non-boolean gates
(deftest live-keys-are-subset-of-keys  …)
(deftest deps-reference-known-features …)
(deftest implies-graph-is-acyclic      …)  ; resolution fixpoint must terminate
(deftest startup-features-match-restart-keys …)
```

`every-schema-key-is-classified` is what makes this durable. Add a key to
`config-schema` without classifying it and `bb test` fails — the same discipline that
already keeps every key documented.

Two deliberate escape hatches keep P0 green while still recording the intent:
`:proposed true` excludes the six not-yet-existing gate keys (§4) from
`gates-are-schema-keys` and from `claimed`, and the explicit
`(conj claimed :enable-budget-monitoring)` quarantines the one dead key (§1.6) *by
name*. Both are ugly on purpose — a named exception in a test is a standing
to-do that P3 deletes, whereas a silently-tolerated gap is how the `;; ---`
comments rotted in the first place.

## 6. Surfaces

### 6.1 `/feature` (TUI)

```
$ /feature
memory              ● on   (5/6)
  capture           ● on             startup
  recall            ● on
  mid-turn-recall   ○ off
  consolidation     ● on   ← implied by memory/graph
  graph             ● on             startup · env BY_FEATURE_MEMORY_GRAPH
  project           ● on
self-improve        ○ off  (0/3)
automation          ● on   (4/5)
  fsm               ● on   ⚠ degraded: scheduler off → :always/:after never advance
…
```

`/feature <name>` shows one feature with its knobs and current values.
`/feature <name> on|off` sets the gate. This replaces the 137-item flat autocomplete
scroll with a ~10-item first level.

### 6.2 `feature$*` (LLM-facing)

Mirrors the `schedule$*` / `fsm$*` command-family idiom already in the codebase:

- `feature$list` — families with `{:on? :count :degraded}`
- `feature$explain <f>` — **the high-value one**: why is this on/off? which layer
  won (env / per-agent / session / file / profile / default)? what implied it? what
  requirements are unmet? does it need a restart to take effect? Today an LLM
  answering "why isn't graph memory working?" has to read six files.
- `feature$set <f> on|off` — routes to `config/set-config!` on the gate key, so
  persistence, allowlist and dossier behaviour are unchanged.

And two small additions to the existing command, which are the cheapest wins here:

- `agent-runtime$config :query` hits gain a `:feature` field.
- `agent-runtime$config :feature "memory"` returns the family — gate states plus
  every member key with its value and default. This turns "what does memory
  consist of?" from an unanswerable question into one call.

### 6.3 Env vars and profiles

Today only 10 of the 24 existing gates have an `:env-fn`, hand-written, with
inconsistent naming — the eight `BY_ENABLE_*` ones plus `:ask-channel-enabled?`
(`BY_ASK_CHANNEL`, `config.clj:433`) and `:nrepl-enabled?` (`BY_NREPL_ENABLED`,
`:411`). `:enable-context-budget` and `:enable-subagent-calls` have none at all.
Generate them instead:

- `BY_FEATURE_MEMORY_GRAPH=on|off` — derived mechanically from `:memory/graph`.
  Existing names (`BY_ENABLE_GRAPH_MEMORY`, `BY_ENABLE_SCHEDULER`, …) stay as
  aliases, checked first, so `.env` files and `docs/` keep working.
- `BY_FEATURES="+memory.graph,-automation.reactions"` — one-line override for
  containers and CI.
- `BY_PROFILE=minimal|standard|full` (+ config key `:feature-profile`).

Profiles are the "simple knob" in its strongest form. `minimal` = capture + recall
only, everything optional off (fast, cheap, no background LLM calls); `standard` =
today's defaults exactly; `full` = graph memory + self-improvement + automation on.
A profile sets the **base** for gates the user has not explicitly set, so it slots in
as a new layer *below* `config.edn` and above the schema default — the only
precedence change in this proposal, and it cannot override anything a user wrote.

### 6.4 config-agent and the wizard

`config_agent.clj`'s instruction gains a compact family map, so the agent can answer
"turn off everything that costs extra LLM calls" (→ `self-improve/*`,
`memory/graph`, `memory/consolidation`, `reasoning/refinement`) instead of grepping
137 keys. The wizard (`config_wizard.clj`, which today exposes ~5 of 137 settings)
gains a **Features** step offering the three profiles plus per-family toggles —
a far better use of a bootstrap screen than the current `:max-iterations` prompt.

## 7. Phasing

Each phase is independently shippable and independently revertible.

**P0 — metadata only, zero behaviour change.** Add `feature.clj` with the registry
and `ambient-keys`; add the invariant tests; add `:feature` to
`search-config-keys` hits and the `:feature` filter to `agent-runtime$config`.
Nothing reads `feature/on?` yet, and **no new config key is added** — the six new
gates from §4 are present in the registry as `:proposed true` and are excluded from
the invariant (§5.6) until P3 creates them. `:enable-budget-monitoring` is
quarantined by name in the same test. This is what lets P0 ship green while still
carrying the full classification. Ships the discovery win (§6.2) on its own.

**P1 — chokepoint.** Implement `on?` / `on?*` / `require!`; migrate the ~25 call
sites in §5.4. Delete the ad-hoc `or`. This is where the graph-memory
startup/runtime inconsistency and the five-branch subagent check get fixed. Behaviour
change is intentional and confined to those two bugs; everything else is
byte-identical by construction.

**P2 — surfaces.** `/feature`, `feature$*`, generated env vars, `BY_FEATURES`,
profiles, config-agent instruction, wizard step.

**P3 — cleanup, now that the test makes it safe.** Add the six proposed gates (§4)
and drop their `:proposed` markers; resolve `:enable-budget-monitoring` (wire it to
the clj-sandbox `:budget-opts {:enable …}` path or delete it) and drop its named
quarantine from the test; split `context/compaction` out of
`:enable-context-budget`; derive `restart-required-keys` from `:lifecycle` and drop
the per-key flag; add a startup warning for keys present in `config.edn` but absent
from the schema (which would have caught the four inert keys — `:enable-analytics`,
`:enable-memory-essence`, `:enable-finalize-answer`, `:nrepl-grant` — sitting in
this repo's own `.brainyard/config.edn` right now); and correct the
`:fast-eval-timeout-ms` `:doc` at `config.clj:127`, which describes a Clojure-only
knob that in fact gates every tool call (§4.5).

## 8. What this does not do

- **No storage change.** `config.edn` layout, `writable-prefixes`, the
  `[:agent :config]` subtree, snapshot/revert/dossier — all untouched.
- **No new precedence layer above config.** Feature env vars *are* gate `:env-fn`s.
  The one addition (profiles) sits below `config.edn` and cannot override a user
  value.
- **No per-feature namespacing of keys.** `:graph-max-nodes` does not become
  `:memory.graph/max-nodes`. Renaming 137 keys would break every persisted
  `config.edn` in the field for a cosmetic gain; the registry gives the grouping
  without touching the identifiers.
- **No runtime cost on the hot path.** The registry is a compile-time-ish `def`;
  resolution is a fixpoint over 40 nodes, memoizable per turn, and the `on?*` arity
  reads the snapshot the turn already took.

## 9. Open questions

1. *(resolved 2026-07-28 — family key, tri-state `nil`; see §10.4)* **Family-level gate storage.** `/feature memory off` — does it write six gate
   keys, or one new `:enable-memory` family key that the resolver ANDs in? The
   second is cleaner to reason about and to revert, but adds ten schema keys whose
   only job is to gate other keys. Leaning: family key, `nil` = "no family opinion",
   so it is a true tri-state and turning a family off then on restores per-feature
   settings rather than flattening them.
2. **Should `ui` be a family at all,** or just a second ambient bucket? Argument for
   keeping it: `:enable-tmux-popup` and `:enable-input-suggestions` are
   `:enable-*`-shaped and users will look for them under a feature view.
3. *(resolved 2026-07-28 — fail-safe, as proposed; see §10.4)* **`:requires` fail-safe vs fail-loud.** §5.2 resolves an unmet-requirement
   feature to off. The alternative is to leave it on and only annotate `:degraded`.
   Off is safer for `memory/graph` without `memory/capture` (extraction against an
   empty store), but it means a user who sets `:enable-graph-memory true` sees it
   report off — which is only acceptable if `feature$explain` and the startup banner
   are genuinely good.
4. **Per-agent feature overrides.** Features resolve through `get-config`, which
   already honours per-agent overrides seeded from `defagent :config-extra`. Should a
   subagent be able to enable a feature its parent disabled? Current root-agent
   predicates (`AND`ed at six read sites today) suggest no — but that coupling should
   move into the registry as `:root-only true` rather than staying implicit at each
   call site.
5. **Does `clj-sandbox`'s option-map vocabulary get aligned?** Only
   `:enable-parallel` is caller-facing (`chat.clj:367`); `:enable-compaction`,
   `:enable-budget` and `:enable-structure-aware` are internal locals derived from
   differently-named caller opts (`:compaction-opts {:enable}`, `:budget-opts
   {:enable}`, `:feedback-opts {:structure-aware}`). So renaming the latter three to
   `:compaction?` / `:budget?` / `:structure-aware?` changes **no** caller-facing
   vocabulary and is near-zero-risk — it just stops two of them
   (`tui/format.clj:1807-1808`) from reading as config keys in a grep. Renaming
   `:enable-parallel` is a real API change and can wait.

## 10. Amendments from implementing P0 (2026-07-28)

Four things the implementation changed or settled. Recorded here rather than
edited into §4–§7 silently, so the reasoning survives.

### 10.1 `:proposed` must exclude the *gate*, not the *feature*

§5.6 defines `live-features` as `(remove :proposed (vals feature-registry))` and
derives `claimed` from it. That drops each proposed feature's **knobs** as well as
its gate — and 18 of those knobs are real schema keys today (`:recall-limit`,
`:memory-recall-snippet-chars`, `:gateway-pair-code-ttl-ms`,
`:compaction-target-ratio`, `:reference-artifact-paths`,
`:live-artifact-max-chars`, the six `exec/gc` retention keys, the six
`agents/acp` keys). `every-schema-key-is-classified` would have failed on all 18
with no way to satisfy it short of deleting the classification.

Implemented instead: `gate-of` returns `nil` for a `:proposed` feature, and
`feature-keys` appends the gate only when `gate-of` yields one. A proposed
feature therefore claims its knobs normally and contributes no gate. Two tests
pin this — `proposed-features-still-claim-their-knobs` and
`proposed-gates-are-not-schema-keys-yet`, the latter *failing once P3 creates a
key*, which is how the marker gets removed on schedule rather than lingering.

### 10.2 `on?` alone does not fix the graph-memory split (P1)

§5.4 row 2 says migrating the five live `get-config :enable-graph-memory` reads
in `memory_agent/hooks.clj` to `feature/on?` resolves them "against the
**startup-baked** value". That does not follow: `on?` → `feature-state` →
`config/get-config` is live by construction, so the migration as written swaps a
live read for a differently-spelled live read and fixes nothing.

The real asymmetry, confirmed against the tree, is narrower than §1.5 states:

- `core/memory.clj:203` uses the **0-arity global** `get-config`, called once from
  `graph-provider-opts` at manager creation. Whether `:extract-fn` / `:embed-fn`
  exist is **baked into the manager instance**.
- `hooks.clj` L243/267/344/397/445 use the **per-agent live** arity every turn.

So P1 needs one mechanism §5 does not specify: `feature.clj` seals a boot
snapshot of every `:lifecycle :startup` gate, and `on?` consults that snapshot
for startup features while everything else stays live. This also makes
`:restart-pending?` — a field §5.2's docstring declares but nothing computes —
real: it is `live-gate ≠ baked-gate`.

### 10.3 `require!` returns a reason, not a result map (P1)

§5.4 collapses the five `:enable-subagent-calls` enforcement branches to "one
shared denial". The five sites return **two different shapes**:
`core/tool.clj:318` yields `{:error-message …}`, `common/commands.clj:177` and
`:206` yield `{:error …}`. `require!` therefore returns a reason *string* and each
site wraps it in its own shape — message shared, shape local, no caller contract
changed.

### 10.4 §9 Q1 and Q3 resolved

- **Q3 (`:requires` fail-safe vs fail-loud): fail-safe**, as §5.2 proposed. An
  unmet hard `:requires` resolves the feature **off** with `:unmet` populated;
  `:requires-partial` only annotates `:degraded`. No third mode. This is
  contingent on `feature$explain` and the startup banner being good — which is a
  P2 obligation, not a nice-to-have.
- **Q1 (family-level gate storage): one family key per family, tri-state**, with
  `nil` meaning "no family opinion", ANDed in by the resolver. Turning a family
  off and back on restores per-feature settings instead of flattening them. Costs
  ~9 schema keys whose only job is gating other keys; that is the price of
  non-destructive family toggles. P2 work — no family keys exist yet.

### 10.5 Two modelling choices not in §4

- **`ui` is two features, not one 16-key list.** §4.10 presents `ui` as a flat
  list, but a flat 16-key bucket is the exact problem this proposal exists to
  fix. Implemented as `:ui/display` (8 keys) and `:ui/blocks` (8 keys), both
  `:presentation true`. The "40 features" count in §4 therefore refers to the
  nine capability families; the registry holds 42 including `ui`.
- **`:requires` supports disjunction via nested sets.** §4.2 needs "distillation
  **or** refinement" and §5.2 promises support without specifying a shape. A
  `:requires` element that is itself a set means *any-of*:
  `#{#{:self-improve/distillation :self-improve/refinement}}`.

### 10.7 P2 status: surfaces shipped, config-layer items outstanding

Shipped: `feature$list` / `feature$explain` / `feature$set`, the `/feature` TUI
command (registered in `tui/format` `command-registry`, so it reaches both
`/help` and autocomplete), `config/config-source` (which precedence layer
supplied a key), and `core.feature/set-feature!` holding the write plus its
guards so the LLM and TUI surfaces cannot drift.

Not yet done, and grouped here because they are one class of change — they all
touch config storage or precedence, unlike the purely additive surfaces above:

- **Family gates (§9 Q1).** Blocked on a concrete schema detail, not a
  decision: `valid-config-value?` maps `"boolean"` to `boolean?`, which
  **rejects nil**. A tri-state family key (`nil` = no family opinion) therefore
  needs a new schema type — say `"tristate"`, accepting `nil` or a boolean —
  added to both `valid-config-value?` and `coerce-config-value`. Worth noting
  while implementing: with the AND semantics the resolution uses, `true` and
  `nil` are behaviourally identical (only `false` forces members off), so the
  third state earns its keep only in *reporting* ("explicitly enabled" vs "no
  opinion"). If that distinction is not wanted, a plain boolean defaulting true
  is the same mechanism with one less type.
- **Generated env vars + `BY_FEATURES`.** `BY_FEATURE_MEMORY_GRAPH` derived
  mechanically, existing `BY_ENABLE_*` names kept as aliases and checked first.
- **Profiles.** `BY_PROFILE` / `:feature-profile`, slotting in below
  `config.edn` — the only precedence change this design permits (§2.6, §8).
- **config-agent instruction + wizard Features step.**

### 10.6 P1: the graph-memory seal is blocked on a semantics decision

P1 shipped the chokepoint, the declared dependencies and the shared denial.
The one piece that did **not** ship is the mechanism from §10.2 — sealing the
startup gates so the memory-agent hooks stop re-reading `:enable-graph-memory`
live. `feature/seal-startup-gates!` exists and is unit-tested, but nothing
calls it. Here is why, because the reason is not a bug in the mechanism.

**The finding.** `core.memory/graph-provider-opts` reads the **0-arity global**
`get-config`, and `create-memory-manager` (`core/agent.clj:554`) is handed only
`user-id` and memory-opts — never agent config. So a **per-agent**
`:enable-graph-memory` override cannot affect the manager at all. It only ever
affected the memory-agent hooks' live re-reads. That is the §1.5 disagreement
seen from the other side: the flag has *two* consumers with two different
precedence chains, and the manager's is the shorter one.

Sealing therefore has to snapshot the global value — which is faithful to the
manager, but means the sealed value **outranks per-agent overrides** for that
key. Measured: with the repo's own `.brainyard/config.edn`
(`:enable-graph-memory true`), a stub agent carrying a per-agent
`:enable-graph-memory false` resolves `:memory/graph` to **false** unsealed and
**true** sealed. Eight assertions across six tests in
`memory_agent/essence_test.clj` drive the reducer path, the mode-aware job
ceilings and the detach/in-process branch through exactly that per-agent lever,
and all seven failures in the first full-suite run traced to it.

**Resolved (2026-07-28): route on the manager.** Config sealing was built,
tested, and then removed — it was the wrong mechanism. Snapshotting the global
value would have made it outrank per-agent overrides for *every* startup gate,
paying a workspace-wide precedence change to fix one flag. The narrower and
more truthful fix is to stop asking config a question only the artifact can
answer.

`core.memory/create-memory-manager` now makes exactly one
`:enable-graph-memory` read and stamps the result onto the manager as
**`:graph-enabled?`** (`graph-provider-opts` takes it as a parameter rather
than re-reading, so the two cannot disagree). The five routing sites in
`memory_agent/hooks.clj` — reducer choice, both mode-aware job ceilings, and
the two detach branches — read `graph-mode?`, which prefers the manager and
falls back to the configured feature only when no manager is bound (REPL,
tests, and any pre-manager path, where there is no baked artifact to disagree
with).

What this buys over sealing:

- **The bad state is unreachable, not merely avoided.** The hooks cannot select
  the community reducer against a manager built without `:extract-fn` /
  `:embed-fn`, because they are reading that manager's own record of what it is.
- **No precedence semantics change.** Per-agent overrides keep working
  uniformly across all 137 keys; `base-on?` reads live for every feature,
  startup or not. The six `essence_test.clj` tests that failed under sealing
  pass unmodified.
- **`:lifecycle :startup` stays honest metadata.** It still drives the
  `restart-required-keys` derivation (§5.5); it just no longer implies a
  snapshot at resolution time.

Two tests pin the fix: `graph-mode-follows-the-manager-not-the-config-test`
(manager wins in both directions; config is consulted only with no manager) and
`job-timeout-follows-the-manager-test` (the mode-aware ceiling tracks the
manager, not the live flag).

The generalisable rule, worth applying to any future `:startup` feature: **if a
startup gate configures a long-lived object, record the decision on that object
and have consumers ask it.** Re-deriving the flag downstream is what created the
divergence in the first place, and a snapshot only moves the divergence rather
than removing it.
