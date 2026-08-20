# router-agent: model routing plan

**Status:** phases 0–3 implemented · **Date:** 2026-08-20 · **Depends on:** `docs/design/router-agent-design.md` (as-built)

> ## Correction on implementation (§2 was wrong)
>
> The plan asserted that "nothing in the repo maps a model id to $/token".
> That is false. `components/clj-llm/src/ai/brainyard/clj_llm/core/usage.clj`
> already carries `default-pricing` — a full per-1M-token table keyed
> `[provider model]` with `:input`/`:output`/`:cache-read`/`:cache-write`,
> covering openai, anthropic, google, deepseek, groq and bedrock — plus
> `get-pricing` (with Bedrock region/version normalization), `calculate-cost`,
> and a tracker that already rolls up `:by-model` totals *including cost*.
>
> **P0.1 was therefore NOT implemented as written.** Adding `:input-cost` /
> `:output-cost` to catalog entries in `providers.clj` would have created a
> second pricing source next to a complete one, and the two would have drifted
> — the exact failure the catalog-refresh design exists to prevent. What
> shipped instead:
>
> - **P0.1′** `providers/pricing-coverage` reconciles the catalog against the
>   *existing* table and reports which models have no rate. First run: **102
>   priced, 81 unpriced, 7 not-applicable of 190** — the unpriced ones bill
>   `0.0` today, which reads as free rather than as unknown. Providers that
>   cannot carry a rate (`claude-code`, which reports `cost_usd` directly;
>   `ollama`/`apple-fm`, local; `free-llm`) are a separate `:not-applicable`
>   bucket so the report does not cry wolf.
> - **P0.2′** `bb catalog:refresh` prints that report. It contacts no
>   provider, so it runs offline and even when every provider is unreachable.
>
> The rest of the plan stands as written and is implemented below. §2's
> conclusion — that cost cannot be *predicted* from the catalog — was right;
> its premise about what exists was not.

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

## 4a. As-built map

| Plan item | Where it landed |
|---|---|
| P0.1′ pricing coverage | `clj-llm/core/providers.clj` `pricing-coverage` |
| P0.2′ drift report | `scripts/catalog_refresh.clj` `report-pricing-coverage!` |
| P0.3 per-agent attribution | `clj-llm/core/usage.clj` `*attribution*` + `with-attribution*` + `:by-agent`; bound in `agent/core/agent.clj` around `proto/process` |
| P1.1 `:agent-lm-tiers` | `agent/core/config.clj` config-schema (ships all-nil) |
| P1.2 `resolve-tier-lm` | `agent/core/config.clj`, beside `resolve-sub-lm` |
| P2.1 `:agent-tier-map` | `agent/core/config.clj` config-schema (19 built-in specialists) |
| P2.2 dispatch injection | `agent/core/tool.clj` `do-call-tool--agent` |
| P2.3 tier logging | `::tier-routed` / `::tier-clamped` mulog; `tier`/`tier-model` on the routing.log line (`common/router.clj` `append-log!`, `common/router_agent_hooks.clj`) |
| P3.1 `:work-tier` | consumed in `do-call-tool--agent`, stripped before the specialist sees it |
| P3.2 clamping | `agent/core/config.clj` `clamp-tier` / `resolve-work-tier` |
| P3.3 router instruction | `common/router_agent.clj` — WORK TIER section, 20 lines |
| feature claim | `agent/core/feature.clj` `:agents/work-tiers` (requires `:agents/subagents`) |
| tests | `agent/test/…/work_tier_test.clj` — 14 tests, 139 assertions |

### Verified live (nREPL, 2026-08-20)

Against a real `bb tui` router-agent session on Bedrock, tiers configured via
`.brainyard/config.edn`:

- **Dispatch injection.** `explore-agent` with no tier ran on the `:light`
  model, `explore-agent` asking `"deep"` ran on the `:deep` model, and
  `schedule-agent` asking `"deep"` was **clamped** to `:standard` (its `:max`)
  and fell back to the session model. `config-source` reported `:agent` for the
  two injected cases, i.e. the per-agent layer, which outranks session.
- **Per-agent cost.** One turn produced
  `{:router-agent {… :models #{"apac.amazon.nova-lite-v1:0"}}
    :explore-agent {… :models #{"apac.amazon.nova-micro-v1:0"}}}` —
  the router on the session model, its specialist on the `:light` tier, billed
  separately. That single observation exercises P0.3, P1 and P2 together.
- **Routing log.** `{:turn 1 :shape "explore" :routed-to "explore-agent"
  :tier "light" :tier-model "bedrock/apac.amazon.nova-micro-v1:0"}`, and a
  following self-answered turn logged `:shape "direct-answer"` with **no**
  tier.

**P2.3 was broken as first written, and live testing is what caught it.**
The tier was only recorded when `routed-to` was non-nil, and `routed-to-of`
derives the specialist from TOOL-channel call names — but the router's
instruction makes a **clojure-fence dispatch the primary path**, and that
leaves no tool-call trace. So a normal routed turn logged
`{:shape "code-compose" :routed-to nil}` with no tier: the routing log was
under-reporting real routing, and had been doing so before tiers existed.

The fix stamps the dispatching agent's id (`:by`) on the dispatch record and
clears it at `:agent.ask/pre`, so a record surviving to `:agent.ask/post`
provably belongs to this turn and this agent. `routed-to` now falls back to the
dispatch record, which repairs `routed-to` **and** `shape` for every
code-channel dispatch, not just the tier field. Regression-guarded by the
staleness case above (a self-answered turn must not inherit the prior tier).

Two environment gotchas found while testing, neither caused by this change:

- **`bb tui` lets `.env` clobber real shell env vars.** The bb.edn task runs
  `set -a && source .env`, the opposite of the shipped binary's `dotenv.clj`
  (which checks `System/getenv` first). An exported `AWS_REGION=us-east-1` was
  silently overridden by the project `.env`'s `ap-northeast-2`, which sent
  `us.`-prefixed inference profiles to a region that rejects them. Worth
  reconciling separately.
- **A tier label must be valid in the session's AWS region.** Pointing `:light`
  at a `us.` profile while the session runs in `ap-northeast-2` fails the
  dispatch with "The provided model identifier is invalid". Tier labels are not
  region-checked at config time.

Two implementation notes worth carrying forward:

- **`:work-tier` is consumed at the dispatch and never forwarded.** §6 says a
  specialist should not know its tier; the dispatch `dissoc`es the key before
  `invoke-tool`, so no specialist `:input-schema` changed. P3.1's "add it to
  the specialist input-schema" would have touched ~19 defagents to declare a
  key none of them read.
- **The dispatch records its tier in an atom (`tool/last-dispatch-tier`)** for
  the routing-log hook to read, and the hook only trusts it when it names the
  same specialist the turn routed to. Last-write-wins is fine for an audit
  line about a turn that dispatches one specialist; it is deliberately not a
  billing mechanism — that is `:by-agent`, which is per-call.

**Phase 4 is not implemented.** It needs data from real sessions that this
change is what starts producing.

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
