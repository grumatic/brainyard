# EvoHarness-RL vs. Brainyard conditional recall — comparison

> Status: **related-work note** (analysis 2026-09-04). A repo-grounded read of
> [EvoHarness-RL](https://arxiv.org/abs/2608.05446) (arXiv 2608.05446, Ning et
> al., Meta AI + UIUC, 5 Aug 2026;
> [full text](https://arxiv.org/html/2608.05446v1)) against `:recall-mode
> "conditional"` (commit `5cd71f5`).
>
> **This is a comparison, not an attribution.** Conditional recall was designed
> and measured here independently; the paper is not its source. The phrase
> "conditional recall" **does not appear in EvoHarness-RL** — checked against
> the full text and the [Pebblous
> summary](https://blog.pebblous.ai/report/agent-harness-policy-learning-2026-08/en/).
> Anyone reaching for a citation should read §5 before writing one.
>
> Related: `docs/core/memory.md`, `docs/design/context-graph-memory-design.md`,
> `docs/design/context-management.md`, `docs/design/hermes-comparison.md`.

## 1. TL;DR

Both systems reach the same conclusion — **an agent should not read all of its
external memory all of the time** — and get there on different axes, for
different costs, by different means.

EvoHarness-RL gates **whether the agent issues a recall call at all**, because a
harness call spends a step from the same budget as an environment action. The
gate is *learned*: cost-aware GRPO discovers it, and the paper's headline
dynamic ("harness annealing") is that frequency falls from SFT-initialized
scaffolding to roughly **one harness call per episode**.

Brainyard gates **what a recall's results are allowed to put in the prompt**,
because our query is a local SQLite FTS read and effectively free while every
returned hit is re-encoded into the user message. The gate is a *rule*:
keyword overlap with the question, `:recall-min-terms`, on `:l2`/`:l3` only.

Neither is a substitute for the other, and they compose: the paper's axis is
call-side and learned, ours is inject-side and fixed. The genuinely useful
import is §6 — usage-frequency feedback, which they have and we mostly don't.

## 2. What the paper actually does

- **BPE workspace.** Harness state is exposed to the policy as **B**elief
  (current environment state), **P**rogress (done/pending subgoals), and
  **E**xperience (reusable knowledge across episodes).
- **Four meta-actions:** `track`, `commit`, `recall`, `note`.
- **Two training stages.** Supervised harness fine-tuning teaches the action
  space and how to build useful external state; cost-aware GRPO learns *when*
  to read, update and consolidate it.
- **One budget.** Harness and environment actions "consume the same interaction
  budget," which is the entire pressure that makes selectivity emerge.
- **Results.** ALFWorld seen split, Qwen3-8B: **96.9%** vs ReAct 47.9%
  (+49.0), SkillOS 80.2%, SkillRL 89.9% — near Claude Opus 4.5's 96.4%.
- **Harness annealing.** Harness-call frequency decays over GRPO to ~1 call per
  episode. It decays *unevenly*: `commit` and `note` fall toward zero, `track`
  sits in between, and **`recall` persists** as the most durable action.
- **Harness evolution.** The Experience store expands early, then becomes
  selective — redundant entries merged, rarely-useful ones evicted — ending
  "compact yet diverse."
- **Store shape.** Four categories (general skills, task-specific skills,
  common mistakes, search priors), **80 entries per category**, **LFU
  eviction** on usage counts incremented at retrieval, **top-3 returned per
  category**. Episode cap 70 steps; prompt cap 12,288 tokens.

## 3. Side-by-side

| | EvoHarness-RL | Brainyard conditional recall |
|---|---|---|
| **What is gated** | whether to *issue* a recall call | what an already-run recall may *inject* |
| **Scarce resource** | a step from the interaction budget | prompt tokens |
| **Why that resource** | harness and env actions share one budget | the query is local SQLite FTS; hits are re-encoded into the user message |
| **How the policy is set** | learned (cost-aware GRPO) | fixed rule (`:recall-min-terms`, default 1) |
| **Decision granularity** | per step, by the model | once per turn, by the harness |
| **Result-side filter** | top-k = 3 per category | `:limit` = `default-per-layer-limit` 8 per layer, then keyword-overlap gate |
| **Scope of the gate** | all of Experience | `:l2`/`:l3` only — `:l1`/`:vec`/`:graph` excluded by construction |
| **Store bounding** | 80/category, LFU on usage | graph budget eviction by weighted degree + type bonus + recency |
| **Usage feedback** | retrieval increments usage; drives eviction | `access_count` incremented in `semantic.clj` only; recall hits don't touch it |
| **Evidence** | 96.9% ALFWorld; annealing to ~1 call/episode | 36% of recall-block tokens saved at min-terms 1; iterations/turn flat (2.14 → 2.20) |

## 4. Where they genuinely differ

**The cost models are not the same cost.** This is the load-bearing
difference and it is easy to blur. For EvoHarness-RL a recall costs a *step*,
so the question is "is this worth an action?" For us a recall costs *tokens
in the next prompt*, and the query itself is free — which is exactly why
`5cd71f5` gates the injection and deliberately leaves the query and the audit
trail running. Porting their gate to our system would optimize a resource we
do not spend.

**Learned vs. stated.** Their selectivity is discovered by reward shaping; ours
is a rule a human can read, argue with, and tune. Ours is auditable and
zero-training; theirs adapts per task family without anyone choosing a
threshold. The commit's rejection of a *fraction* for `:recall-min-terms` —
0.34 demands 2 matches on a 3-keyword question but 3 on a 6-keyword one — is a
symptom of the general problem with hand-set thresholds that a learned policy
does not have.

**Their gate has no layer exemptions; ours must.** They gate one homogeneous
Experience store. We recall over five heterogeneous layers, and a keyword test
is *actively wrong* on three of them: `:vec` exists to find material that does
not share the question's wording, `:graph` reaches hits two hops out, and
`:l1` is deliberately-written session overlay rather than a retrieval result
competing for relevance. A single global gate would be strictest exactly where
recall is smartest.

**Recall persisting is convergent evidence.** The paper's most transferable
empirical finding is not a mechanism but a ranking: under real cost pressure,
`commit` and `note` decay to zero while `recall` survives. That is independent
support for spending engineering effort on making recall *selective* rather
than on making it *rarer* — which is the bet `5cd71f5` makes from the token
side, and why its measurement watches iterations/turn (2.14 always vs 2.20
conditional) rather than tokens alone. An over-gated turn buys tokens with
extra iterations, which is the bad trade.

## 5. What this paper does and does not support

**Do not cite it for "conditional recall."** The term is not in it. Nor is any
mechanism that filters recall *results* on question overlap. Its only
result-side filters are a capacity cap (top-3 per category) and LFU eviction.

**Fair to cite it for:**

- recall being the harness action that survives cost pressure (annealing, Fig. 3);
- the general claim that long-horizon agents benefit from *policies* for
  coordinating with external state, "beyond simply adding stronger tools or
  larger memories";
- capacity-bounded, usage-evicted experience stores as prior art;
- the BPE decomposition as a way to name what an agent's external state is for.

**Not fair to cite it for:** inject-side gating, keyword-overlap relevance
tests, prompt-token cost models, or per-layer gate exemptions. Those are ours
and the measurements behind them are in `5cd71f5` and
`scripts/recall_cache_report.clj`.

## 6. Adoption candidates

Ordered by leverage-to-effort. **None is implemented; this section is a
proposal list, not a status log.**

### A1 — Usage feedback on recall hits (highest leverage, low effort)

Their LFU eviction is driven by counters incremented *at retrieval*, so
frequently-recalled experience survives and dead weight ages out. We already
have the column — `access_count` in the entries schema, surfaced as
`:access-count` in `entry.clj` — but it is only incremented in `semantic.clj`.
An L2/L3 recall hit does not touch it, so we currently throw away the single
cheapest relevance signal available: *what the agent actually reads.*

Incrementing on injected hits (not merely retrieved ones — the gate makes that
distinction meaningful, and it is a distinction the paper cannot draw) would
give us a usage histogram for free. That histogram is also the training data a
learned gate would need later, which makes this the prerequisite for A3.

### A2 — Fold usage into graph eviction (medium effort)

`prune-nodes-to-budget!` scores retention on weighted degree + type bonus +
recency, with no usage term. That is defensible — it is a *structural*
notion of importance and it is deterministic — but it means a node nothing ever
recalls ranks equal to one recalled every session, given the same shape. If A1
lands, adding a usage term is a small change to a scoring function that already
exists. Worth measuring rather than assuming: structural centrality may already
correlate with usage, in which case this buys nothing and should not ship.

### A3 — Learn the gate instead of setting it (high effort, speculative)

`:recall-min-terms` is a hand-set integer, and the commit is candid that the
default of 1 was chosen by sweeping 12 questions against a 1501-episode corpus.
That is a threshold, and thresholds do not transfer across corpora — the
docstring says as much ("raise it if your corpus is noisier than that").
`::recall-gate` telemetry plus A1's usage counts would give the ingredients for
a learned policy. The honest caveat is that EvoHarness-RL had a task reward
(ALFWorld success) to optimize against and we do not have a comparable
per-turn signal; inventing one is the actual work, not the training.

### A4 — Name our layers against BPE (documentation only)

Our layers map onto BPE unevenly, and the mismatch is informative:

| BPE role | Nearest Brainyard surface |
|---|---|
| Belief — current environment state | `:l1` session overlays; `## Live Artifacts` |
| Progress — done/pending subgoals | `todo$*` / plan dossiers — **not** part of recall |
| Experience — cross-episode knowledge | `:l2`/`:l3`, `:vec`, `:graph` |

The gap worth noticing: **Progress is not a recall layer for us.** The paper
puts it in the same policy-facing workspace as Experience, so the agent can
`commit` to it and read it back under one budget. We keep it in a separate
tool family. That is not obviously wrong — but it does mean the "what have I
already done this turn" question is answered by a different mechanism than
"what do I know," and we have never compared them head to head.

## References

- Ning, Fu, Wei, Zeng, Bei, Li, Li, Wang, Shen, Wu, Liu, Li, Xia, Fan, Tong,
  He. *EvoHarness-RL: Learning Self-Evolving Runtime Harness for Long-Horizon
  LLM Agents.* arXiv:2608.05446, 5 Aug 2026.
  [abs](https://arxiv.org/abs/2608.05446) ·
  [html](https://arxiv.org/html/2608.05446v1)
- Pebblous, *EvoHarness-RL: Agent Harness Policy Learning*, Aug 2026 —
  [blog.pebblous.ai](https://blog.pebblous.ai/report/agent-harness-policy-learning-2026-08/en/)
- Related prior art surfaced alongside it: *Harness-1: Reinforcement Learning
  for Search Agents with State-Externalizing Harnesses*,
  [arXiv:2606.02373](https://arxiv.org/html/2606.02373v1) — a 20B search agent
  trained inside a stateful search harness. Not analysed here.
- Brainyard: commit `5cd71f5`; `components/agent/…/common/context_actions.clj`
  (§ "Conditional recall — inject-side gate"); `:recall-mode` /
  `:recall-min-terms` in `core/config.clj`; `scripts/recall_cache_report.clj`.
