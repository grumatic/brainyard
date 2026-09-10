# Procedural Graphs vs. Brainyard's "what next" decision — comparison

> Status: **related-work note** (analysis 2026-09-10, against `29ef7c2`). A
> repo-grounded read of
> [Procedural Graphs: Self-Evolving Execution Structures for LLM Agents](https://arxiv.org/abs/2609.09153)
> (arXiv 2609.09153v1, Lu, Chen, Wu & Arık — Google / Georgia Tech / Peking
> University, 8 Sep 2026) against the four places Brainyard actually decides
> what to do next.
>
> **This is a comparison and a proposal, not an implementation log.** Nothing
> here is built. The phases in §6 are a recommendation whose Phase 0 is
> deliberately inert; §7 lists the things this paper argues *against* doing,
> and they are the tempting ones.
>
> Related: `docs/design/context-graph-memory-design.md`,
> `docs/design/evoharness-rl-comparison.md`,
> `docs/design/evoharness-agent-design.md`,
> `docs/design/router-agent-design.md`, `docs/design/self-improve-design.md`,
> `docs/core/memory.md`.

## 1. TL;DR

The paper's framing is one sentence and it carries the whole idea:

```
Knowledge Graph   ->  (entity,    relation, entity)     ->  answers WHAT-IS
Procedural Graph  ->  (procedure, relation, procedure)  ->  answers WHAT-TO-DO-NEXT
```

Brainyard has already built the left-hand side. `components/memory`'s context
graph is a typed entity/relationship overlay whose entire vocabulary is
*what-is* (`protocol.clj:206-213`):

```clojure
(def node-types #{:entity :concept :component :person :file :config-key})
(def relations  #{:depends_on :configures :supersedes :part_of :prefers :mentions})
```

There is no `:precedes`, no `:requires`, no `:enables`, no `:leads_to`. We have
the KG half of the paper's Figure 1 and not the PG half.

Meanwhile the decision the paper targets — *which channel, which tool, in what
order, under which preconditions* — is made in Brainyard by unconstrained
generation from the ThinkActCode signature over an accumulating iteration
history. Whatever procedural knowledge exists (read before edit, detach before
`task$wait`, read-guard before `research$verdict-outcome`) lives in ~6k lines of
prompt prose or nowhere. That is verbatim the abstract's problem statement.

The substrate needed to close this is unusually complete (§4): a bounded-hop
typed graph store with bi-temporal edges, a per-turn advisory injection slot
that already ships with a live producer (`:notices` / `usage-nudge`), an
append-only `trajectory.edn` per session, an assertion-based reward that no LLM
judges, and — in evoharness-agent — a promotion gate that is the paper's
Equation (5) already implemented as an organizational rule.

The three results that should actually change a design decision are in §5, and
the sharpest one is counter-intuitive: **a hand-written expert procedure graph,
frozen, measured 28.6 points WORSE than having no graph at all.** Our instinct
here — curate it, bake it into the binary, the way `model-catalog` does — is the
one approach the paper measured as negative value.

## 2. What the paper actually does

### 2.1 Representation

A Procedural Graph is a directed, attributed graph

```
G = (V, R, E, Phi),   E subset of V x R x V
```

`V` is abstract nodes — each abstracts a tool action, a skill, an internal
reasoning step, or a task status. `R` is a vocabulary of transition relations.
Each edge `(u, r, v)` states that node `v` is *admissible after* node `u` under
relation `r`. `Phi` maps each edge to named attributes; the implementation uses
three textual fields:

| field | meaning |
|---|---|
| `condition` | when the transition applies |
| `guidance` | how to proceed |
| `pitfalls` | what to avoid |

The paper's worked example is a financial-planning edge
`(cash_flow_forecast, LEADS_TO, fund_raising_request)` carrying *"condition:
projected runway falls below the safety buffer; guidance: submit the request
early to allow for the financing delivery delay; pitfalls: do not stack a second
request while one is pending."*

The graph keeps a domain's procedural knowledge **outside model weights**, where
it can be inspected, retrieved per step, and edited without retraining.

### 2.2 Online inference — the graph is FROZEN

Three operations per decision step: *locate*, *extract*, *generate*.

```
u_t = Match(a_{t-1}, V)                    ; exact-match last procedure to a node; a_0 = Start
G_t = N_h(u_t)  if u_t != nil, else G      ; h = 2 hop directed neighborhood; full graph on miss
g_t = Psi(G_t, q, T_{t-w:t})               ; guidance LLM; w = 3 trajectory window
a_t ~ P_solver( . | q, T_t, g_t)           ; guidance APPENDED to the solver prompt
```

Two properties matter more than the equations:

- **Guidance biases, it does not dictate.** `g_t` is appended text. The solver
  keeps full reasoning freedom. The paper calls this "soft integration" and it
  is why PG composes with any solver.
- **The connected neighborhood is the point, not top-k retrieval.** The paper's
  motivating failure: retrieving guidance for `submit` without the preceding
  `check_answer` transition omits the verification step that makes submission
  appropriate. Independent similarity search over edge attributes loses the
  connections between procedural steps.

### 2.3 Offline self-evolution — four steps

1. **Diagnostic rollout.** Run the retained graph `G_{k-1}` on a batch of
   training tasks; record `(query, trajectory, score)` triples. Partition
   high-scoring against low-scoring traces (successes vs failures for binary
   outcomes).
2. **Feedback-driven mutation.** An offline LLM refiner inspects the partitioned
   traces for repeated error loops in failures and multi-step shortcuts in
   successes, and emits a structured edit set `dG_k` of exactly two topological
   operations: **Add** and **Delete**. An attribute revision uses the same
   interface — delete the edge, re-add it with new attributes. Candidate is
   `G_k^cand = G_{k-1} (+) dG_k`, applied to a copy, with configured cycle
   repair.
3. **Validation gating.** Score the candidate on an independent validation set
   and commit only if it does not regress:

   ```
   G_k = G_k^cand   if S_val(G_k^cand) >= S_val(G_{k-1})
       = G_{k-1}    otherwise
   ```

   Structurally invalid candidates are discarded *before* the validation
   rollout, so they cost nothing.
4. **Rejection memory as a safeguard.** Rejected candidates, their edits, the
   associated training trajectories and validation outcomes go into
   `H_rejected`, which is fed to the refiner as negative evidence:
   `dG_{k+1} ~ P_refiner( . | G_k, C_{k+1}, H_rejected)`. Without it, iterative
   self-correction repeatedly re-proposes equivalent unsuccessful edits.
   Trajectory context is truncated from the *front*, preserving the final
   `L_max` tokens — the ending, not the prefix.

A rejected candidate never becomes the next round's starting graph.

### 2.4 Setup and results

Seven benchmarks (HotpotQA, MultiChallenge, GDPval, ALFWorld, tau-bench, BFCL
v3, EnterpriseArena), four LLMs (Claude Sonnet 4.6, Gemini 3.1 Pro, Gemini 3.5
Flash, Grok 4.1 Fast), greedy decoding. Seven baselines (Vanilla ReAct,
MemoryBank, RAP, ExpeL, AutoGuide, AWM, KnowAgent) — **all sharing an identical
ReAct solver**, differing only in how procedural experience is stored and
reused. Guidance model and refiner always share the solver's LLM. `h=2`, `w=3`.

- **Main table.** PG ranks first or joint-first in **21 of 24** model x benchmark
  cells. Against the strongest baseline in each setting: **19 wins, 2 ties, 3
  losses** (one-sided exact binomial excluding ties, p = 4.3e-4). Largest
  margins: BFCL v3 / Gemini 3.5 Flash **67.00 vs 58.00 (+9.00)**; GDPval /
  Gemini 3.1 Pro **78.78 vs 71.37 (+7.41)**; tau-bench / Gemini 3.1 Pro **80.00
  vs 73.04 (+6.96)**. HotpotQA is the outlier: margins over the strongest
  baseline range **-0.90 to +1.30**.
- **Long horizon (EnterpriseArena).** Monthly financial decisions over up to 132
  months under liquidity constraints with three undisclosed crises. Full-horizon
  survival: **44.0 -> 58.0%** (Sonnet 4.6), **6.0 -> 34.0%** (Gemini 3.1 Pro),
  **26.0 -> 40.0%** (Grok 4.1 Fast). The paper is explicit that what changes is
  *which* tools are called and *when*, not how many: unguided Gemini 3.5 Flash
  issues 18.94 tool calls/month, PG reduces it to 12.53 while improving score;
  on Sonnet and Gemini 3.1 Pro tool calls *increase* (0.13 -> 0.36, 0.89 ->
  3.18) and survival also improves. The behavior tracking survival across all
  four models is **anticipatory fundraising** — capital arrives one to six months
  after request, so surviving a crisis means asking before liquidity runs out.
  Flash baseline raises **$0.00M**; PG-guided Flash **$9.39M**; PG-guided Grok
  **$30.11M**.
- **Self-evolution trace (10 rounds, EnterpriseArena).** Round 1 discovers the
  sequential backbone (audit cash, forecast runway, then finance) — validation
  survival 0.0 -> 45.0%. Round 2 adds `recall_notes` to reuse notes saved by the
  Round 1 graph: 80.0%, with tool usage falling 17.23 -> 3.08 calls/month.
  Rounds 3-6 commit nothing. Round 7 prunes the `pass_action` branch; Round 8
  adds an administrative bypass, reaching 90.0%; Round 10 is rejected and the
  loop terminates. Test survival of the *returned* graph: **85.0% vs baseline
  0.0%** (Fisher's exact p = 2.6e-8). The paper notes the best single
  intermediate round hit 95.0% and deliberately reports the returned graph
  instead, and that with 20 episodes per split individual accept/reject
  decisions should be read as a search trace, not a significance test.

## 3. Where Brainyard decides "what next" — four altitudes

The paper hits exactly one of these. Getting the altitude wrong is the main
implementation risk.

### (a) The behavior tree — mechanism, NOT procedural knowledge

`coact_agent.clj:5933` (`:bt-factory`) builds a hand-written tree:

```
:sequence/main
  cond/question-present
  action/prepare-conversation
  action/prepare-recalled-memory
  action/init
  fallback/loop-guard
    [:repeat ...
      :sequence/iteration
        action/inc-iter, action/await-pending, action/rebudget
        fallback/llm-guard   [ action/think-act-code | action/repair-llm-guard ]
        action/display-think, action/strip-unbound-tools
        fallback/router      [ answer-path | code-path | tool-path | action/repair ]
        action/accumulate ]
    action/loop-fallback
  action/ensure-answer
  cond/answer-present
  action/store-results
  action/maintain-conversation
```

**PG does not go here.** The paper holds the solver fixed on purpose — every
baseline shares an identical ReAct solver. The BT is Brainyard's solver: control
flow, guards, repair paths, budget. It encodes what the *harness* does, not what
the *task domain* requires. Reaching for `:bt-factory` after reading this paper
is aiming a prompt-layer idea at a mechanism layer.

### (b) Free choice inside one iteration — this is the target

The ThinkActCode DSPy signature emits exactly one of `{tool-calls, code-blocks,
answer}`, conditioned on `::context-briefing` plus `::iterations`. Which one,
and which tool, is unconstrained generation over an accumulating history — the
paper's stated failure mode, including its named symptoms (losing track of
objectives, invoking tools out of order, repeating unproductive actions).

Brainyard's procedural knowledge for this decision is prose in the instruction
template. Prose is un-localizable: the model reads all of it every turn, or none
of it.

### (c) Router-agent's specialist choice

A static lettered decision table plus `:agent-tier-map` clamps. Notably,
`CLAUDE.md` already argues the PG position here without the graph: *"the router
never names a model... 'why did this run on Opus?' deserves a configuration
answer rather than a per-turn LLM whim."* A routing PG would be that
configuration answer with `condition` and `pitfalls` attached to each edge.
Lower priority than (b), but it is the same shape.

### (d) The context graph — the KG half of Figure 1

Built, shipped, opt-in (`BY_ENABLE_GRAPH_MEMORY`), bi-temporal, with vector and
relational recall fused into RRF. Its `related` renderer produces the `##
Related` briefing section. Every relation in the vocabulary answers *what-is*.

## 4. What the substrate already provides

| PG requirement | Brainyard today | Gap |
|---|---|---|
| Bounded-hop typed graph store | `GraphStore/expand` (`:max-hops` default 2, clamped <= 3), `neighbors`, `as-of`; bi-temporal edges; `:metadata` JSON per edge | vocabulary is what-is only |
| Per-step guidance injection slot | **`:notices` on `::iteration`** (`coact_agent.clj:148`) — *"Advisory; read and act on it"* — with a shipping producer (`common/usage_nudge.clj`, JIT on first use of a tool family) | nothing computes it from a graph |
| `Match(a_{t-1}, V)` anchor | `:tool-name` on every `::tool-result-entry`; `:channel` on every `::iteration`; tool ids are stable symbols | trivial — the cheapest part of the build |
| Diagnostic rollouts | `trajectory.edn`, append-only, one EDN record per turn with all iterations + final answer (`:enable-trajectory-recording`, default **true**) | not partitioned by score, not fed to a refiner |
| Validation-gate score | analytics scoring family (`:requires :analytics/trajectory`); better, evoharness's assertion-based task reward — *"The assertions are the whole of the task reward. No LLM judges the outcome."* | not wired to graph edits |
| Refiner + commit gate | evoharness-agent owns rollouts -> runs -> eval -> promotion, and its fourth mandate is *"Refusing a checkpoint whose eval does not clear the gate, INCLUDING when asked to promote it"* | points at checkpoints, not topology |
| Rejection memory | `.brainyard/skills/proposals/` review queue + `:enable-self-improve-nudges` | no negative-evidence feedback to a proposer |
| Inspectable / editable graph | `by memory graph --json`; Playground force-directed SVG panel | would render procedure nodes for free |
| A localization probe to debug against | `by memory graph --node <name>` already scopes the dump to that node's bounded neighborhood via `graph-related` — structurally the same query `Match` + `expand` performs | none; this is the debugging surface for Phase 1 |
| Non-regressing opt-in discipline | `:enable-graph-memory` precedent: off by default, empty graph => recall byte-identical to pure FTS | — |

Two of these deserve emphasis.

**`:notices` is the injection point, and it already exists.** It is an optional
string on the iteration record, documented to the model as advisory, rendered
into `::iterations`, and produced today by `usage-nudge` pushing a tool-family
guide on first use. A procedural-guidance producer would be a second writer to a
slot whose contract, rendering and model-facing semantics are already settled.
No new prompt zone, no cache-zone disturbance.

**Evoharness already implements Equation (5) as policy.** Its promotion gate is
the paper's validation gate with a checkpoint where the graph should be. The
reasoning in its instruction — that a bad turn "can start a run that spends real
money for hours and teaches nothing" — is the same argument the rejection-memory
safeguard makes about wasted validation rollouts.

## 5. Three findings that should change a design decision

### 5.1 A frozen hand-written expert prior is NEGATIVE value

Table 2, MultiChallenge Overall Success Rate:

| Construction mode | Overall |
|---|---|
| Unguided baseline (no PG) | 87.50 |
| Mode 1: Hand-crafted expert | **58.93** |
| Mode 2: Expert + one static update | **53.57** |
| Mode 3: Expert + Online Evolution | **92.86** |
| Mode 4: Scratch + Static Build | 89.29 |
| Mode 5: Scratch + Online Evolution | 91.07 |

A hand-crafted expert graph dropped success **28.57 points below having no graph
at all**, and a single static update made it worse. Only the evolution loop
recovered it — +33.93 over the expert initialization. On HotpotQA the ranking
inverts: Mode 5 (scratch + evolution) wins outright at 66.30 EM / 78.79 F1, +7.50
EM and +7.58 F1 over unguided, ahead of every expert-initialized mode.

**Corollary for Brainyard.** Our reflex is to curate and bake: the model catalog
ships hand-written `:curated-rank` and `:description` precisely because a
provider's `/v1/models` cannot tell you which entries a chat client can drive.
That split — *"provider API owns ids, humans own curation"* — **does not
transfer here.** In a PG the measurement owns the topology, and unmeasured human
curation was the configuration that measured worst. Anyone proposing to ship a
`resources/procedures.edn` in the binary should read this table first.

### 5.2 Localization is a correctness requirement for execution tasks, not an optimization

Table 3, Gemini 3.5 Flash, same underlying graph and solver prompt:

| Graph configuration | MultiChallenge Acc | GDPval Rubric | ALFWorld Success |
|---|---|---|---|
| Baseline (no graph) | 80.27 | 54.80 | 72.58 |
| Full graph, raw injection | 86.60 | 57.17 | **70.34** |
| Full graph, generative | 87.35 | 56.75 | **54.48** |
| **Subgraph, generative (theirs)** | **89.31** | **63.99** | **81.53** |

Injecting the whole procedure *helped* multi-turn dialogue and *degraded*
embodied execution with strict action ordering — full-graph generative guidance
cost ALFWorld 18.1 points against no graph at all.

Brainyard's work is overwhelmingly the ALFWorld shape: ordered tool sequences
with hard preconditions. A "paste the procedure document into the system prompt"
implementation would therefore be actively harmful for our dominant task type,
while looking fine on any conversational eval we happened to run first.

### 5.3 The cost is tokens, and it does not go away

Localization cuts tokens against full-graph generative by 70.9% (ALFWorld),
18.1% (GDPval), 14.8% (MultiChallenge). But against the **no-graph** baseline,
localized generative guidance still runs **33.4% and 55.4% more total tokens**
(GDPval, ALFWorld) even though it cuts average solver steps 28.20 -> 18.57 and
21.84 -> 18.80. The conclusion concedes it: *"Guidance increases token use even
when it reduces solver steps."*

So PG trades tokens for steps. Fewer steps is the right trade for long-horizon
work — it is exactly where the paper's margins are largest (tau-bench, BFCL,
EnterpriseArena) and fewer steps means fewer opportunities to drift. It is the
wrong trade for a one-shot `by ask`, and HotpotQA — flat at -0.90 to +1.30 — is
the benchmark shaped like `by ask`. Short retrieval QA has no procedure to
learn.

This maps onto machinery we already have: guidance belongs on `:standard` and
`:deep` work tiers and should be absent on `:light`, and the per-agent usage
attribution (`:by-agent` rollup) would make the overhead visible immediately
rather than as a surprise on a bill.

## 6. Recommendation — three phases

Framed to match the CR-MEM series, since this is an overlay on the graph overlay.

### Phase 0 — vocabulary only. No behavior, no migration.

Add procedure types alongside the existing sets in
`memory/interface/protocol.clj`, without touching them:

```clojure
(def procedure-relations
  "Transition vocabulary for procedural edges (what-to-do). Deliberately
   disjoint from `relations` (what-is) — see docs/design/procedural-graph-comparison.md §7."
  #{:precedes :requires :enables :leads_to :extracts_to :generates})
```

`Phi`'s three fields (`condition`, `guidance`, `pitfalls`) go into the edge's
existing `:metadata` JSON — **no schema change, no migration.** The bi-temporal
model gives versioned edits for free: the paper's `Delete` becomes
`invalidate-edge`, `expand` already filters to valid edges, and `as-of`
reconstructs *"what did we believe then"* — precisely what a rejected-candidate
post-mortem wants.

This phase is pure addition and cannot regress anything, including recall: with
no procedure nodes written, every existing query behaves identically.

### Phase 1 — locate + guide. Graph frozen, default off.

`BY_ENABLE_PROCEDURE_GUIDANCE` / `:enable-procedure-guidance`, default
**false**, and inert unless `:enable-graph-memory` is on (a procedure graph with
no graph store is not a thing). Gate it off for the `:light` tier and for
`by ask` per §5.3.

In `coact-inc-iter-action` (`coact_agent.clj:3091` — already the iteration-start
hook that harvests pending evals):

1. `Match` the previous iteration's `:tool-name` (or `:channel`) against
   `:procedure` nodes via `find-node`.
2. `expand` at `:max-hops 2` — localized by construction.
3. Render reached edges as `src —relation→ dst` with their three attributes.
4. Land the result on the iteration record's **`:notices`**.

Whether step 3 is templated rendering or a `:light`-tier guidance LLM (`Psi`) is
the one genuinely open question — the paper always uses the solver's own model
and never measures a cheaper guidance model.

**One deliberate divergence from Equation (2): on a `Match` miss, emit
nothing — never the full graph.** The paper falls back to `G` when localization
fails; §5.2 measured that fallback as harmful on execution tasks, which is our
shape. And the miss is not an edge case here: with ~200 tool defs plus
user-authored tools plus MCP tools whose ids are not ours, a sparse graph will
miss constantly. **The miss branch is the hot path, so it must be free and
silent.**

### Phase 2 — evolution, gated by evoharness, never by an LLM's opinion.

- **Rollouts** — `trajectory.edn` records, partitioned by the analytics scoring
  family or, better, by evoharness's assertion rewards (no LLM judges them,
  which removes the most obvious way for a refiner to grade its own homework).
- **Refiner** — proposes Add/Delete edits only, attribute revision as
  delete + re-add, exactly as in §2.3.
- **Gate** — `S_val(cand) >= S_val(retained)` on a held-out suite. This is
  evoharness's existing promotion gate pointed at a graph instead of a
  checkpoint, and its "refuse even when asked to promote" mandate transfers
  unchanged.
- **Rejection memory** — rejected candidates follow the `.brainyard/skills/
  proposals/` pattern and feed back as negative evidence.

Do not let `config-agent`, `router-agent` or a user-facing agent write procedure
edges directly. The entire finding of Modes 1-2 is that unmeasured edits are
worse than no edits.

## 7. What NOT to do

- **Do not put PG in the behavior tree.** The BT is the solver; the paper holds
  the solver fixed across every baseline. (§3a)
- **Do not hand-curate a procedure graph and bake it into the binary.** Measured
  at -28.57 points against no graph. The `model-catalog` curation split does not
  transfer. (§5.1)
- **Do not inject the whole graph, or a flat procedure document, into the
  prompt.** Measured at -18.10 points on the task shape closest to ours. (§5.2)
- **Do not reuse `:mentions` / `:depends_on` for procedure edges.** Recall fuses
  graph results into RRF; mixing what-is and what-to-do into one ranking
  corrupts both, and the `## Related` briefing is a what-is surface with a
  what-is renderer.
- **Do not enable it for `by ask` or the `:light` tier.** Short QA showed no
  gain and guidance costs tokens unconditionally. (§5.3)
- **Do not use top-k similarity retrieval over edge attributes** as a shortcut
  for `expand`. The paper's motivating example is exactly this failure:
  retrieving `submit` without the preceding `check_answer` transition drops the
  precondition that made submission correct.

## 8. Risks and open questions

- **Single lab, v1, one month old, no independent replication.** 3 outright
  losses in 24 cells; HotpotQA essentially flat. The EnterpriseArena
  self-evolution trace runs 20 episodes per split and the authors themselves say
  individual accept/reject decisions turn on one or two episodes.
- **Guidance-model tier is untested.** Refiner, guidance model and solver always
  share one LLM in their setup. Running `Psi` on `:light` while the solver is on
  `:deep` is our natural configuration and has no evidence behind it.
- **Match is exact string matching on the last procedure.** Sparse graph +
  large, partly foreign tool namespace => frequent misses. Mitigated by making
  the miss branch silent (§6 Phase 1), but it caps how much of the trajectory
  guidance can ever cover.
- **Rejection memory shares a table with the retained graph.** Bi-temporal edges
  invalidate rather than delete, so rejected candidates and superseded edges
  need a discriminator in `:metadata` or they will be indistinguishable in an
  `as-of` audit.
- **Token overhead is real, measured, and unavoidable** — but visible, via the
  usage tracker's `:by-agent` rollup.
- **Who owns the graph across projects?** The memory DB is partitioned by
  `BY_USER_ID`; procedural knowledge about *Brainyard's own tools* is arguably
  user-independent and project-independent, which is a different scope than
  anything the memory store currently holds.
