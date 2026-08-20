# router-agent: model routing plan

**Status:** proposal · **Date:** 2026-08-20 · **Depends on:** `docs/design/router-agent-design.md` (as-built)

The rename from `main-agent` to `router-agent` names what the agent already
does. This document is about what it does **not** do yet: pick the model the
routed specialist runs on.

---

## 1. What routing costs today

`router-agent` chooses *which specialist* handles a turn. It has no say in
*which model* that specialist runs. Verified against the code:

- `do-call-tool--agent` (`core/tool.clj:303`) builds the sub-agent from
  `parsed-args` — `{:question :agent-context}` plus an injected `:id`,
  `:agent-session`, `:parent-agent`. No LM key is ever passed.
- `setup-agent` (`core/agent.clj:1032`) folds `(select-keys options
  config/config-keys)` into `schema-overrides`, which becomes the sub-agent's
  **per-agent config layer** (`(:config @st-memory-init)`).
- `get-config` precedence (`core/config.clj:1549`) is
  `env → per-agent → session → global → schema default`. There is **no
  parent → child inheritance**: a dispatched specialist with an empty
  per-agent layer resolves `:lm-config` from the shared *session* layer.

So every specialist runs on whatever the user last set with `/model`. Routing
a `config-agent` call that flips one boolean, a `memory-agent` "what do you
remember about X", and a `research-agent` end-to-end arc all bill the same
frontier model. The router picks the cheap *agent* and then hands it the
expensive *model*.

The good news is that the seam already exists and is one key wide:
`:lm-config` is a `config-schema` key, `parsed-args` is not stripped of extra
keys on the way to `setup-agent`, and the per-agent layer outranks the session
layer. **Passing `:lm-config` through the dispatch is a working override
today** — nothing decides *what* to pass.

## 2. What the catalog can and cannot tell us

`clj-llm`'s per-model entry is `{:model :curated-rank :description :region}`
(`core/providers.clj:119`). That is a *display ordering plus prose*. It carries:

- **no price** — nothing in the repo maps a model id to $/token.
- **no capability tier** — `:curated-rank 0` means "show first", not "strongest".
- **no latency or context-window** figure.

Token usage *is* tracked (the usage-tracker feeding
`analytics/core/waste.clj`), so spend is measurable after the fact but not
predictable before a call.

This is the load-bearing constraint on the design: **cost-effectiveness cannot
be computed from the catalog as it stands.** Either we add the data, or we
choose by a proxy the catalog does carry. The plan below does both, in that
order of trust.

## 3. Design principle: the router picks a *tier*, config maps tier → model

The tempting design is for the router LLM to name a model id per dispatch.
Reject it, for three reasons:

1. **Model ids are not stable.** They churn weekly; the catalog refresh exists
   precisely because they do. An instruction that names ids is stale on
   release day, and the router has no way to know an id it invented is fake.
2. **The router does not know the user's credentials.** Naming
   `openai/gpt-...` when only `AWS_PROFILE` is set is a runtime failure the
   router cannot foresee.
3. **It is unauditable.** "Why did this run on Opus?" should have a
   configuration answer, not a per-turn LLM whim.

So split it:

```
router-agent  ->  picks a WORK TIER          (:light | :standard | :deep)
config        ->  maps tier -> provider/model  (per user, per provider)
```

The router reasons about the *work*, which it can see. Config resolves the
*model*, which it cannot. Same split as the catalog's own "provider API owns
ids, humans own curation" rule — and the same reason.

### 3.1 Where the tier comes from

Two sources, in precedence order:

1. **A static per-specialist default.** Most specialists have a fixed
   character. `config-agent` flipping a boolean, `schedule-agent` listing
   jobs, `memory-agent` reading — these are `:light` every time. `plan-agent`,
   `eval-agent`, `research-agent` are `:deep` every time. This table is a
   config map, not an LLM decision, and it captures most of the win.
2. **A router escalation/de-escalation for the residual cases.** A handful of
   specialists genuinely vary — `exec-agent` on a one-line fix vs. a 30-item
   todo, `explore-agent` on "where does X live" vs. a cross-surface sweep.
   For those the router may name a tier in its dispatch, bounded by the
   per-specialist floor/ceiling so it can never route a `config-agent` call to
   `:deep`.

Static-first matters: it means the feature works with the router LLM saying
nothing at all, and the escalation path is an optimization layered on a
correct default rather than the mechanism itself.

## 4. Phases

### Phase 0 — make cost visible (prerequisite)

Nothing here changes routing; it makes the rest measurable and honest.

- **P0.1** Add optional `:input-cost` / `:output-cost` (USD per 1M tokens) to
  catalog entries in `core/providers.clj`, alongside `:curated-rank`. Human-
  curated, like every other non-id field — the refresh overlay carries ids
  only and must keep doing so. Absent cost is `nil`, never zero; a `nil` must
  read as "unknown", not "free".
- **P0.2** Extend `bb catalog:refresh --drift` to report entries with a
  `nil` cost so the gap is visible between releases.
- **P0.3** Attribute usage-tracker totals to the **dispatching specialist**,
  not just the session, so `session$analytics` can answer "what did routing to
  research-agent cost me". Without this there is no way to tell whether any
  of the following phases helped.

*Exit criterion:* a session that dispatched 3 specialists reports per-specialist
token counts and, where cost data exists, an estimated dollar figure.

### Phase 1 — the tier → model map

- **P1.1** New config-schema key `:agent-lm-tiers`, an object:

  ```clojure
  {:light    "bedrock/amazon.nova-lite-v1:0"
   :standard nil                                ; nil/blank => the agent's :lm-config
   :deep     "bedrock/anthropic.claude-…"}
  ```

  Resolution follows the existing `resolve-sub-lm` / `resolve-eval-lm` /
  `resolve-analytics-lm` pattern verbatim (`core/config.clj:1947+`): a
  non-blank `provider/model` string parsed by `clj-llm/parse-lm-str`,
  **falling back to the main `:lm-config` when blank or unparseable**. Never
  nil — a nil LM crashes the dspy call with "No LM configuration provided".

- **P1.2** New `resolve-tier-lm` in `core/config.clj` beside its three
  siblings. One function, same shape, same fallback discipline.

- **P1.3** Ship `:agent-lm-tiers` **defaulting to all-nil**, i.e. every tier
  resolves to the session model. The feature is then inert until configured:
  identical behavior, zero regression, and the migration is opt-in. This
  mirrors how `:enable-graph-memory` shipped.

*Exit criterion:* setting `:light` to a cheap model and dispatching a
`:light` specialist demonstrably bills the cheap model, verified by P0.3's
per-specialist attribution.

### Phase 2 — the static per-specialist tier table

- **P2.1** New config key `:agent-tier-map`, `{:config-agent :light,
  :research-agent :deep, …}`, with a shipped default covering every built-in
  specialist. User-defined agents (meta-agent personas) default to
  `:standard`.
- **P2.2** `do-call-tool--agent` consults the table for the target
  defagent-type and injects the resolved `:lm-config` into the dispatch args
  before `invoke-tool` — landing it on the sub-agent's per-agent layer through
  the path that already works. This is the only change to the dispatch path,
  and it is additive: no table entry ⇒ no injected key ⇒ today's behavior.
- **P2.3** Log the decision. A `::tier-routed {:agent … :tier … :model …}`
  mulog event, and a `tier` field on the router's routing.log NDJSON line so
  `router$last-shape` and the audit trail carry it.

*Exit criterion:* a session of mixed dispatches shows per-specialist models in
routing.log with no instruction change to any specialist.

### Phase 3 — bounded router escalation

- **P3.1** Add an optional `:work-tier` to the specialist `:input-schema`
  (`[:enum "light" "standard" "deep"]`, optional). The router may set it; every
  specialist ignores it (it is consumed by the dispatch, not the agent body).
- **P3.2** Clamp it. `:agent-tier-map` entries become
  `{:default :light :min :light :max :standard}` for specialists that must not
  escalate. A router asking for `:deep` on `config-agent` gets `:standard` and
  a logged clamp — not an error, and not obedience.
- **P3.3** Teach the router instruction the *shape* test, not the model names:
  escalate when the sub-question requires synthesis across sources, judgement
  under ambiguity, or authoring prose a human will read; stay light when it is
  a lookup, a CRUD write, or a mechanical transform. Keep it to ~6 lines —
  the instruction is already 500 lines and rides every turn.

*Exit criterion:* a clamp fires in a test where the router requests `:deep`
for a `:light`-capped specialist, and the dispatch still succeeds.

### Phase 4 — feedback

- **P4.1** `eval-agent` verdicts already score outcomes. Join verdict ×
  tier × cost to answer "did `:light` on exec-agent produce more
  `NOT_ACHIEVED` verdicts than `:standard`?" — an offline report, surfaced by
  `session$analytics`, not an auto-tuner.
- **P4.2** Explicitly **not** in scope: automatic tier learning. A router that
  silently rewrites its own cost/quality tradeoff is unauditable, and the
  data volume from one user's sessions cannot support the inference. Report
  it; let the human move the table.

## 5. Risks

- **A cheap model that fails costs more than an expensive one that works.** A
  `:light` specialist that misunderstands its sub-question burns a retry, a
  user round-trip, and possibly a wrong write. This is why P2 is a curated
  table rather than an aggressive default, why P1 ships inert, and why P4.1
  exists before anyone widens the `:light` set.
- **Provider capability is not uniform across tiers.** Structured-output
  enforcement differs by provider (`:supports-json-schema?`), and specialists
  that depend on a JSON contract — the graph extractor is the cautionary tale
  in `CLAUDE.md` — degrade quietly on a weaker model rather than failing. Any
  specialist with a hard schema contract should be floor-capped in P3.2.
- **Cost data rots.** Prices change; a stale `:input-cost` produces confident
  wrong reports. Treat it exactly like `:curated-rank`: human-maintained,
  drift-reported by `bb catalog:refresh`, and never load-bearing for
  correctness — only for the advisory report.
- **Tier is not the only cost lever.** `:max-iterations` on a deep specialist
  and the 4-zone prompt cache both move spend more than model choice does on
  some workloads. This plan should not be sold as *the* cost fix.

## 6. What is deliberately not changing

- The routing log format. `router$resume?` / `router$last-shape` parse it;
  adding a `tier` field is additive NDJSON, and absent-field reads stay nil.
- The specialist instructions. A specialist should not know what tier it is
  running at — that is the router's and config's concern, and telling it would
  invite it to hedge.
- The catalog refresh contract. It carries **ids only**. Cost is curation and
  stays baked, for the same reason `:description` does.
