# DSPy Programming Model on Brainyard — Research Proposal

> Status: **research proposal / in progress** (2026-09-14). Phase 1's core
> has landed on branch `feat/dspy-predictors` — see §9 "As built". Grounded in Khattab et al., *DSPy: Compiling Declarative
> Language Model Calls into Self-Improving Pipelines* (arXiv:2310.03714, 2023)
> and a survey of the current `clj-llm` DSPy layer.
>
> Related: `components/clj-llm/CLAUDE.md`, `docs/core/reasoning.md`,
> `docs/design/prompt-cache-arrangement.md`, `docs/design/bt-context-schema-design.md`,
> `docs/design/self-improve-design.md`, `docs/design/router-agent-model-routing-plan.md`,
> `docs/design/evoharness-agent-design.md`.

## 0. TL;DR

Brainyard adopted **one third** of DSPy: *signatures* (`defsignature`, Malli
fields, derived JSON Schema) plus two fixed call strategies (`predict`,
`chain-of-thought`). The other two thirds — **parameterized modules** and the
**compiler (teleprompters)** — are absent, and so is the thing that makes them
possible: a signature call is a *function*, not an *object with learnable
parameters*. There is no demonstrations slot in the prompt, no per-predictor
trace, no dataset/metric/evaluate loop.

This proposal argues for adding, in order of value-per-effort:

1. **Predictor-as-value** — a named, addressable `{signature, strategy, params}`
   whose params (instructions override, demos, LM tier) live in *data*, loaded
   at call time. This is the keystone; everything else hangs off it.
2. **Tracing** — capture `(predictor-id, inputs, outputs, reasoning, usage)` per
   call. The `:agent.dspy-action/post` hook already carries all of it.
3. **Example / Metric / Evaluate** — a dataset shape, metric fns (including
   LM-judge metrics that are themselves programs), and a parallel evaluation
   harness with cost accounting.
4. **Optimizers** — `LabeledFewShot`, `BootstrapFewShot`, `…WithRandomSearch`,
   `Ensemble`, later instruction proposal. **Output is a reviewable params
   artifact, never code**, behind a human review gate — the same "machines
   propose, humans curate" line the model catalog and skill distillation draw.
5. **A small module library** — `Predict`, `ChainOfThought` (exist), plus
   `BestOfN`/`MultiChainComparison`, `Refine` (metric-in-the-loop retry), and
   `Retrieve` over memory — composable as plain Clojure functions.

The CoAct loop is **not** rewritten as a DSPy `ReAct` module. It stays a
behavior tree; its `ThinkActCode` predictor becomes *one* parameterized
predictor among many, and it is the **last** optimization target, not the
first. First targets are the short, metric-friendly pipelines that already
exist: `GraphExtraction`, the memory-agent signatures, `SkillDistillation`
scoring, `EvaluateAnswer` calibration, and router tier/specialist choice.

The research bet mirrors the paper's H2 in brainyard's economic terms:
**bootstrapped demonstrations let a `:light`-tier model match a `:deep`-tier
model on brainyard's internal sub-pipelines**, with the `:deep` model acting
as the paper's *teacher*. That turns `:agent-lm-tiers` from a cost dial into a
compile target.

---

## 1. The DSPy paper in one page

DSPy treats an LM pipeline as a **text transformation graph**: imperative code
in which LM calls are *declarative modules*. Three abstractions (§3):

| Abstraction | What it is | Paper § |
|---|---|---|
| **Signature** | Natural-language typed declaration of a transformation: input fields, output fields, optional instruction. `question -> answer`. Field *names* carry semantics. Handles formatting/parsing so user code does no string manipulation. | §3.1, App. A |
| **Module** | A parameterized, callable implementation of a signature. `Predict` is the core; `ChainOfThought`, `ProgramOfThought`, `MultiChainComparison`, `ReAct` are a few lines each built by *rewriting the signature* (e.g. prepend a `rationale` output) and calling `Predict`. **Parameters: (1) the LM, (2) instructions + field prefixes, (3) demonstrations.** Modules compose define-by-run (PyTorch-style `__init__` + `forward`, arbitrary control flow). | §3.2, App. D |
| **Teleprompter** | An optimizer: `compile(program, trainset, metric[, teacher, valset]) -> program'`. Training sets can be tiny and *labels only for the final output*; intermediate labels are bootstrapped. Metrics can themselves be DSPy programs. Teleprompters compose via `teacher=`. | §3.3, §4, App. E |

**The compiler (§4)** has three stages:

1. **Candidate generation** — find all `Predict` modules (recursively); run a
   teacher (default: the zero-shot student) over training inputs in *compile
   mode*, which records a trace of every predictor's inputs/outputs; keep the
   traces whose **end-to-end** output passes the metric; those become
   candidate demos for *every* intermediate predictor (rejection sampling).
2. **Parameter optimization** — choose among candidates (random search,
   Optuna/TPE) by validation score; or `BootstrapFinetune` the LM weights.
3. **Higher-order program optimization** — change control flow, e.g.
   `Ensemble` N compiled programs and reduce by majority vote.

**Hypotheses (§5):** H1 concise modules replace hand-crafted prompts without
quality loss; H2 treating prompting as optimization adapts better across LMs
and can beat expert prompts; H3 modularity enables exploring richer pipelines.

**Evidence (§6–7, Tables 1–2):** GSM8K — GPT-3.5 `vanilla` 24→62–65%, `CoT`
50→81–88%; llama2-13b-chat from 7–9% to 47–49% via `reflection`+bootstrap+
ensemble. HotPotQA — a 16-line `multihop` program bootstrapped with
llama2-13b reached 42–50 EM (vs. 27.5 few-shot), competitive with GPT-3.5; a
T5-Large (770M) finetune from a llama2 teacher reached 39.3 EM. Compiling takes
minutes. Appendix B counts **50 strings >1000 chars** in LangChain vs. **zero**
in DSPy — the paper's framing of "prompts as hand-tuned weights".

**What the paper does not cover** (relevant later): assertions/constraints
(a follow-up paper), instruction-proposal optimizers (MIPRO, and reflective
optimizers like GEPA came after), long-horizon agent loops with tool
side-effects, and prompt caching.

---

## 2. Brainyard today

### 2.1 What exists — the signature layer (`components/clj-llm`)

- `defsignature` (`core/signature.clj:54`) →
  `compile-signature` → `{:name :instructions :inputs :outputs :input-keys
  :input-order :output-keys :output-json-schema}`. Malli fields with `:desc`;
  JSON Schema derived, never hand-written; `:input-order` is cache-significant.
- `predict` (`core/predict.clj:50`) and `chain-of-thought`
  (`core/chain_of_thought.clj:69`, adds a required leading `reasoning`
  property). Parse → lift → fill defaults → coerce → Malli-validate (invalid is
  logged, not thrown).
- Prompt layout (`core/prompt.clj`): system = input descs, output descs, JSON
  schema, *objective* last; user = inputs in declared order + output reminder.
  **No demonstrations slot anywhere.**
- Error classes `:malformed | :transient | :fatal` (`core/llm.clj:426`),
  `retry-with-backoff` as an effect value.
- BT integration: `bt/dspy` node (`behavior-tree/…/dspy_action.clj:417`) reads
  inputs from st-memory, writes outputs/`:last-reasoning`/`:lm-usage`, fires
  `:agent.dspy-action/pre|chunk|post`, builds cache zones.

### 2.2 Call-site inventory

| Pipeline | LM path | Hand-written instruction size | Has a natural metric? |
|---|---|---|---|
| CoAct `ThinkActCode` (`coact_agent.clj:514`, sig built `:2110`) | signature, CoT, BT | very large, Selmer-templated | trajectory-level only (success, eval verdict, cost) |
| `EvaluateAnswer` (`common/evaluation.clj:28`) | signature, CoT, BT | medium | agreement with later user/eval-agent verdicts |
| `GraphExtraction` (`memory/…/signatures.clj:26`) | signature, `predict` | large (long rules prose) | **yes** — schema validity, entity/relation yield, dedup vs. gold |
| Community summary (`memory/…/extract.clj:79`) | **raw** `chat-completion` | hand system prompt | LM-judge faithfulness |
| `EssenceExtraction`, `FactVerification`, `LlmReducer` (`memory_agent/signatures.clj`) | signature, CoT | medium | **yes** — fact verification is classification |
| `SkillDistillation(+Batch)`, `SkillRefinement` (`skill_distill/signatures.clj`) | signature, CoT | medium | human accept/reject at the review gate |
| `query$llm` / `query$structured-output` (`commands.clj:1125`) | **raw** + optional schema (validated since fd937be, no retry) | caller-authored per call | caller-defined |
| Router specialist + `:work-tier` choice | prompt-only CoAct | large | `routing.log` + outcome |
| `clj-sandbox` chat, analytics PQS | **raw** | hand | PQS is itself a metric |

Housekeeping found in the survey (not part of this proposal):
`delegation_use.clj` references `RewriteQuestion`/`DescribeResult` signatures
that do not exist; `docs/core/reasoning.md:434` still documents removed
`FinalizeAnswer`/ReAct signatures.

### 2.3 What exists that an optimizer could stand on

- **Per-turn trajectories** (`common/trajectory.clj`, `sessions/<id>/trajectory.edn`)
  with success, terminated-by, iterations, cost; **redacting SFT export**
  (`trajectory_export.clj`).
- **Verdict signals**: `EvaluateAnswer` via `:agent.evaluation/verdict`;
  eval-agent `verdicts/*.md`; skill-distill review accept/reject.
- **Usage tracker** with per-call history and `:by-agent` attribution; analytics
  PQS / waste / cost.
- **A bootstrap-shaped loop already**: skill distillation is
  filter-trajectories → LM-score → stage → human gate. It is BootstrapFewShot
  whose output is a *skill* rather than a *demo*.
- **Teacher/student in config**: `:agent-lm-tiers` `{:light :standard :deep}`.
- **evoharness-agent** owns SFT/GRPO — i.e. the paper's `BootstrapFinetune`
  territory is already claimed.

### 2.4 The gaps, precisely

| DSPy concept | Brainyard status |
|---|---|
| Signature | ✅ (stronger typing than the paper: Malli + JSON Schema) |
| Signature *rewriting* (prepend field, derive CoT) | ⚠️ CoT hard-coded in schema construction, not a general signature transform |
| Predictor with **parameters** (LM, instructions, demos) | ❌ `predict` is a stateless fn; LM passed per call; instructions baked in the var |
| Demonstrations in prompt | ❌ no slot |
| Named predictor identity (for trace → demo mapping) | ❌ only the signature `:name`; two uses of one signature are indistinguishable |
| Compile-mode trace | ⚠️ hook data exists; nothing records it as `(predictor, in, out)` |
| Module composition | ⚠️ BT vectors + sub-agents-as-tools; no light function-level program object |
| Example / dataset | ❌ (trajectories are raw material, not examples) |
| Metric / Evaluate | ⚠️ ad-hoc (verdicts, PQS); no harness |
| Teleprompter | ❌ |
| Assertions / refine-on-failure | ⚠️ CoAct repair action; `query$llm` validates but never retries |
| Ensemble / best-of-N | ❌ |

---

## 3. The central mismatch — and what it implies

DSPy's sweet spot is **short pipelines (2–5 predictor calls) with a cheap
end-to-end metric and a few hundred inputs**. Brainyard's dominant LM call,
`ThinkActCode`, is the opposite: one predictor called 1–100 times per turn in
a loop with **side-effecting tools**, a **trajectory-level** outcome, a
multi-thousand-token instruction arranged into **prompt-cache zones**, and no
replayable environment (re-running a turn re-runs `git`, file writes, network).

Three consequences shape every recommendation below:

1. **Do not port the loop; parameterize the predictor.** A DSPy `ReAct` module
   would duplicate the CoAct BT and lose its repair, channel-conflict,
   deferred-tasking and hook machinery. The transferable idea is that
   `ThinkActCode` *has parameters* (instructions, demos, LM) that could be
   compiled — not that the loop should become a module.
2. **Optimize where replay is cheap and pure.** Extraction, verification,
   scoring, summarization and routing take text in and produce text out. They
   can be re-run over a dataset freely. That is where compiling pays first.
3. **Compiled parameters are a cache-zone event, not a per-turn event.** Demos
   and instruction overrides must land in a *stable* zone and change only when
   a compile is accepted, or every call pays a cache miss
   (`prompt-cache-arrangement.md`). This is a constraint DSPy never had.

---

## 4. Proposed abstractions

Each subsection: *what*, *Clojure shape*, *why it earns its place here*.
API shapes are sketches for discussion, not commitments.

### 4.1 Predictor-as-value (keystone)

**What.** A predictor is data: a stable `:id`, a signature, a strategy, and a
parameter record resolved at call time from a parameter store.

```clojure
(defpredictor graph-extract
  {:signature GraphExtraction
   :strategy  :predict            ; :predict | :cot | module-specific
   :tier      :light})            ; default LM tier (resolves via :agent-lm-tiers)

;; Parameters (all optional; absent = today's behaviour exactly)
{:instructions "…override…"       ; replaces the signature docstring
 :field-descs  {:entities "…"}    ; per-field desc overrides
 :demos        [{:inputs {…} :outputs {…} :reasoning "…" :source {…}}]
 :lm           "bedrock/amazon.nova-lite-v1:0"   ; pins a model; outranks :tier
 :version      "2026-09-20T…"  :compiled-by {:optimizer … :score … :valset-hash …}}
```

- `(run graph-extract inputs & opts)` = resolve params → rewrite signature →
  `predict`/`chain-of-thought` with a new `:demos` arg → record trace.
- **Parameter store is layered like config** — explicit per-call > session >
  project `<project>/.brainyard/programs/<id>.edn` > user
  `~/.brainyard/programs/<id>.edn` > baked resource > none. EDN files are
  git-diffable and native-image-safe (no code eval; the same reason the
  catalog overlay is data).
- **Identity matters**: the trace → demo mapping in BootstrapFewShot needs to
  know *which* predictor produced a step. Signature name is insufficient when
  one signature is used twice (a multi-hop `generate-query` at hop 1 vs. 2).

**Prompt change — the demos slot.** Rendered in the system message *between*
field/schema descriptions and the objective, as a `## Examples` stable cache
zone: each demo is the input fields rendered exactly as the live user message
renders them, followed by the JSON the model must emit (including `reasoning`
for CoT). Same zone for every call of that predictor ⇒ one cache write per
accepted compile. Demo inputs longer than a cap are truncated with a marker;
total demo budget is a param (`:max-demo-tokens`).

Why here: without it nothing else in this document is possible; with it alone,
hand-curated few-shot (`LabeledFewShot` with a human as the optimizer) already
becomes a config edit instead of a source edit.

### 4.2 Signature transforms

Generalize the hard-coded CoT construction into pure functions over compiled
signatures: `prepend-output`, `append-input`, `with-instructions`,
`with-field-desc`. `chain-of-thought` becomes `(prepend-output sig :reasoning …)`
+ `predict`, exactly the paper's App. D.2. Needed by `MultiChainComparison`
(appends `completions` input), `Refine` (appends `feedback` input), and
instruction optimizers (which rewrite `:instructions`). Keeps `:input-order`
cache discipline: transforms append volatile inputs *last*.

### 4.3 Tracing

`(with-trace [t] (program inputs))` binds a trace collector; every `run`
appends `{:predictor-id :inputs :outputs :reasoning :usage :lm :params-version
:t}`. Outside compile mode, an opt-in persistent sink writes
`sessions/<id>/predictions.ndjson` from the existing `:agent.dspy-action/post`
hook (add `:predictor-id` to that hook's payload), so **production traffic
becomes a candidate pool** without re-running anything.

Thread-safety: the collector is a per-run atom captured at bind time and
conveyed across `future`/missionary boundaries (the paper calls out
thread-safe tracing; brainyard's parallel sub-queries and batched
`query$llm` need the same).

### 4.4 Modules and programs

A **module** is a function of `[params-scope inputs]` that calls predictors and
other modules; a **program** is a module plus the set of predictor ids it
reaches (discoverable statically from `defmodule` metadata, not by runtime
introspection — native image).

```clojure
(defmodule multihop-recall
  {:predictors [gen-query answer]}
  [{:keys [question]}]
  (let [ctx (reduce (fn [ctx hop]
                      (let [{:keys [search-query]} (run gen-query {:context ctx :question question})]
                        (into ctx (memory/recall search-query {:k 3}))))
                    [] (range 2))]
    (run answer {:context ctx :question question})))
```

Plain Clojure control flow, like the paper's define-by-run. **Not** a
replacement for BT: BT remains the agent-loop substrate; modules are for
*inside* one step (a command, a hook handler, a sandbox sub-query). A `bt/module`
node adapter lets a BT call a module the way `bt/dspy` calls a signature.

**Built-in module library (small on purpose):**

| Module | Paper analogue | Brainyard use |
|---|---|---|
| `predict`, `cot` | Predict, ChainOfThought | exist; become param-aware |
| `best-of-n` | `n=` completions + reduce | extraction/scoring robustness; cheap-tier ensembles |
| `multi-chain-comparison` | MultiChainComparison | `EvaluateAnswer` over several candidate answers |
| `refine` | Assertions follow-up; *not in paper* | retry with validator/metric feedback as an appended input — closes the fd937be gap where `query$llm` validates but never re-prompts |
| `retrieve` | Retrieve | L1/L2/L3 + graph recall, `:k` as a param |
| `program-of-thought` | ProgramOfThought | code-eval via `clj-sandbox`, SCI-restricted — mostly subsumed by CoAct's code channel; include only if a short pipeline needs it |

`ReAct` is deliberately absent (§3).

### 4.5 Examples, metrics, evaluation

- **Example**: `{:inputs {…} :labels {…}? :meta {:source :session-id :turn :redacted? …}}`.
  Labels optional, and only for final outputs — the paper's label-efficiency
  point, which is what makes this viable without an annotation program.
- **Dataset builders** (the real work): from `trajectory.edn`, from
  `predictions.ndjson`, from eval-agent verdicts, from skill-distill
  accept/reject decisions, from hand-written EDN. All pass through
  `trajectory_export`'s redaction.
- **Metric**: `(fn [example prediction trace] -> number|boolean)`. Trace access
  lets a metric check intermediate steps (the paper's `answer_and_context_match`).
  **LM-judge metrics are programs** (modules), evaluated with their own
  predictor params and pinned to a tier different from the student to limit
  self-preference.
- **Evaluate**: `(evaluate program devset metric {:parallel 8 :budget-usd 2.0})`
  → `{:score :per-example :cost :usage :errors}`; honours error classes
  (`:transient` retried, `:fatal` aborts the run, `:malformed` scores 0), and
  hard-stops at the budget. Results persisted under
  `.brainyard/programs/<id>/evals/<ts>.edn`.

### 4.6 Optimizers (teleprompters)

All share one contract and one output shape:

```clojure
(compile optimizer program {:trainset … :valset … :metric … :teacher … :budget-usd …})
;; => {:params {predictor-id params-map …} :report {:scores … :cost … :candidates …}}
```

**Output is a params proposal, staged, never auto-applied.** Accepting writes
the EDN params file; the report is a markdown dossier beside it (same
convention as eval-agent verdicts). This is the skill-distillation review gate
applied to prompts, and it is load-bearing for §6.

Proposed set, in build order:

| Optimizer | Paper | Notes for brainyard |
|---|---|---|
| `labeled-few-shot` | LabeledFewShot | k random labelled demos. Baseline; trivial once §4.1 exists. |
| `bootstrap-few-shot` | BootstrapFewShot (App. E.1) | teacher = same program at `:deep` tier; keep traces passing the metric; demos for **every** predictor from end-to-end success. |
| `bootstrap-random-search` | …WithRandomSearch (App. E.2) | N shuffled bootstrap candidates, pick best on valset. Budget-capped. |
| `ensemble` | Ensemble (§4 stage 3) | N compiled param sets run in parallel, reduce fn. A *runtime* cost multiplier — must be explicit. |
| `knn-few-shot` | (DSPy library, post-paper) | pick demos per input by similarity using the existing Model2Vec `static` embedder — no server, fits memory's embedding infra. Interesting because demos then vary per call ⇒ **cache-hostile**; research question R4. |
| `instruction-propose` | stage-1 "instructions" candidates; MIPRO/GEPA-style (post-paper) | LM proposes instruction rewrites from failing traces + metric feedback; searched jointly with demos. Closest existing relative: `SkillRefinement`. Last, because it produces the prose humans most need to review. |

`BootstrapFinetune` is **out of scope** — evoharness-agent owns weight updates.
The integration point is that a compiled program's accepted traces are a
better SFT source than raw trajectories, so `trajectory$export` could gain a
`--from-program <id>` source.

### 4.7 Tier-aware compilation (brainyard-specific)

The paper's teacher/student composition maps directly onto
`:agent-lm-tiers`:

```
teacher = program @ :deep   ─ bootstrap ─▶  demos
student = program @ :light  + demos       ─ evaluate ─▶  score vs. teacher score
```

A compile report then answers the question the tier plan left to judgement:
*"does `GraphExtraction` need `:standard`, or does `:light` + 4 demos get within
2 points?"* Accepted results can propose an `:agent-tier-map` / predictor
`:tier` change — again as a reviewable proposal, never an automatic write
(consistent with "the router never names a model").

### 4.8 Where it is exposed

- **Clojure API** in `clj-llm` (predictor, transforms, trace, modules) and a new
  component for dataset/metric/evaluate/optimizers (working name
  `components/lm-compiler`) — optimizers need agent-level data (trajectories,
  sessions) that `clj-llm` must not depend on, same layering reason the catalog
  cache root is injected.
- **Commands**: `program$list`, `program$params`, `program$eval`,
  `program$compile` (stages a proposal), `program$accept` / `program$reject`.
- **CLI**: `by programs list|eval|compile|accept` for offline/CI compiles.
- **Sandbox**: `query$llm` gains `:predictor` (named, param-aware) so a CoAct
  code block can call a compiled predictor instead of re-authoring a prompt
  per call — the H1 claim applied to agent-authored sub-queries.
- **Agent**: an eventual `program-agent` front door is plausible but explicitly
  deferred until the command family has proven itself (same order as
  schedule/event agents: commands first).

---

## 5. Candidate pipelines, ranked

Ranked by (metric availability × replay purity × call volume) ÷ risk.

1. **`GraphExtraction`** — high volume at consolidation, pure text→JSON, cheap
   metrics (schema validity, `::extracted` yield, overlap with a small gold
   set, dup rate), and a *known* failure mode ("0/0 ⇒ model ignoring the JSON
   contract" in CLAUDE.md) that demos directly address. Also a live cost
   question (which extract model). **First target.**
2. **`FactVerification` / `EssenceExtraction`** — classification-shaped;
   accuracy on a hand-labelled set of ~50 is enough to start.
3. **Community summary** — first migrate from raw `chat-completion` to a
   signature (H1 on its own), then optimize with an LM-judge faithfulness metric.
4. **`EvaluateAnswer`** — calibrate the judge against downstream truth (user
   re-asks, eval-agent verdicts). Doubles as the metric for (6); getting it
   right first is a prerequisite.
5. **Router specialist / `:work-tier`** — dataset from `routing.log` + outcomes;
   metric = right specialist and no clamp. Router is prompt-only today, so this
   first needs a routing *predictor* extracted from its instruction.
6. **`ThinkActCode`** — last. Only demos/instructions are in scope, only
   offline, only from recorded trajectories (no replay of side-effecting
   tools); metric = eval verdict ∧ success ∧ cost. Demos here are expensive
   tokens in the hottest prompt, so the bar is a measured win on a fixed task
   suite, not a plausible one.

---

## 6. Constraints and risks specific to brainyard

- **Prompt-injection via demos.** A bootstrapped demo contains inputs the agent
  read — file contents, web pages, tool output. Promoting one into a
  predictor's *system prompt* elevates untrusted text to instruction status for
  every future call. Mitigations: demos only from metric-passing traces, run
  through redaction, shown verbatim in the review dossier, capped in length,
  rendered inside a clearly delimited examples zone, and **never
  auto-accepted**. This is the same line `query$llm :lm-config` draws on
  `:base-url`: choosing among curated things is safe; content choosing its own
  influence is not.
- **Secrets in datasets.** Reuse `trajectory_export` redaction; datasets and
  params live under `.brainyard/` (gitignored) unless explicitly exported.
- **Cache economics.** Demos add tokens to every call; the win must exceed the
  cost. Evaluate reports must include cached vs. uncached token deltas, and
  per-call-varying demos (`knn-few-shot`) must be measured against a static set.
- **Metric overfitting / judge bias.** Small valsets, LM judges grading their
  own family. Mitigate with held-out test splits recorded by hash in
  `:compiled-by`, and judges pinned to a different provider where configured.
- **Provider portability.** A params set compiled for one LM is not
  guaranteed to transfer (the paper's H2 is precisely that compiling per LM
  beats a fixed prompt). Params therefore record the LM they were compiled
  against; a mismatch at runtime **warns and still runs** (params are a hint,
  not a correctness contract) — contrast the graph-vec fingerprint, which
  pauses, because mixed vector spaces are wrong while stale demos are merely
  suboptimal.
- **Native image.** Params are EDN; modules are compiled Clojure; no runtime
  `eval`. Optimizers run fine in the binary but are expected to run mostly
  from the JVM dev path.
- **Cost runaway.** Every optimizer takes a mandatory `:budget-usd` and stops
  at it; usage is attributed via `with-usage-attribution*` to a synthetic
  `program:<id>` agent so compile spend is visible in the usage rollup.
- **Default behaviour must not move.** No params file ⇒ byte-identical prompts
  to today (a regression test asserts it, as graph memory asserts pure-FTS
  equivalence).

---

## 7. Research questions and hypotheses

Stated so each has a falsifiable measurement.

- **RH1 (H1 analogue).** Replacing the hand-written system prompt of the
  community summarizer with a signature + ≤4 bootstrapped demos does not
  reduce LM-judge faithfulness by more than 2 points, and removes ≥80% of the
  hand-written prompt characters. *Also measure the paper's App. B statistic
  for brainyard: count string literals >1000 chars that are LM prompts.*
- **RH2 (H2, tier form).** For `GraphExtraction`, `:light` + bootstrapped demos
  (teacher `:deep`) reaches ≥95% of `:deep` zero-shot F1 on a gold set, at
  ≤30% of its cost including demo tokens.
- **RH3 (H3 analogue).** A two-predictor `refine` module around `query$llm
  :output-schema` reduces invalid structured results by ≥50% vs. today's
  validate-and-report, at bounded extra calls.
- **R4 (cache).** Per-input kNN demos beat a static compiled demo set by enough
  to pay for losing the examples-zone cache hit — or they do not, and the
  static set is the design. Measured on Bedrock and Anthropic separately
  (zone semantics differ).
- **R5 (portability).** Params compiled on provider A, run on provider B: how
  much of the gain survives? Determines whether params are keyed per LM or
  per tier.
- **R6 (agent loop).** Do a handful of curated `ThinkActCode` demos move task
  success on a fixed suite, net of token cost? A negative result here is
  useful and would confine DSPy abstractions to sub-pipelines permanently.

---

## 8. Evaluation plan

- **Datasets.** (a) `GraphExtraction`: 60 turns sampled from real sessions,
  redacted, hand-labelled entities/relations for 20 (gold), 40 unlabelled for
  bootstrap. (b) `FactVerification`: 50 hand-labelled claims. (c) Community
  summaries: 30 communities, LM-judge + 10 human spot checks. (d) `query$llm`
  structured: 40 schema/prompt pairs harvested from `predictions.ndjson`.
  (e) Agent suite for R6: 15 fixed tasks from `bb tutorial`/harness scenarios.
- **Splits.** train / val / test fixed by content hash; test touched once per
  reported number.
- **Baselines.** zero-shot (today) at each tier; `labeled-few-shot`;
  hand-written expert demos where they exist.
- **Reporting.** Per the paper: program × compiler × LM tables, plus cost and
  cache-hit columns the paper lacks.
- **Budget.** Target < $20 of LM spend for Phases 2–3 total.

---

## 9. Phased plan

| Phase | Scope | Exit criterion |
|---|---|---|
| **0 — Instrument** | `:predictor-id` on `dspy-action` hooks; opt-in `predictions.ndjson`; prompt-string inventory (RH1 statistic); fix the two stale references in §2.2 | a real session yields per-predictor traces |
| **1 — Parameterize** | `defpredictor`, param store + layering, demos slot as a stable cache zone, signature transforms, `run`; migrate `GraphExtraction` and memory-agent sigs to predictors | no-params ⇒ byte-identical prompts (test); hand-written demos via EDN change output |
| **2 — Measure** | Example, dataset builders (trajectory/predictions/hand EDN), metrics, `evaluate` with budget + error classes, `program$eval` | baseline numbers for §8 (a)–(b) at all tiers |
| **3 — Compile** | `labeled-few-shot`, `bootstrap-few-shot`, `bootstrap-random-search`; staged proposals + dossier + accept/reject | RH2 answered |
| **4 — Modules** | `best-of-n`, `refine`, `multi-chain-comparison`, `retrieve`, `defmodule`, `bt/module`; `query$llm :predictor`; migrate community summary | RH1, RH3 answered |
| **5 — Explore** | `knn-few-shot`, `instruction-propose`, `ensemble`, tier-map proposals, router predictor, `ThinkActCode` demos offline | R4–R6 answered; go/no-go on agent-loop params |

### As built (Phase 1 core, 2026-09-14)

- `clj-llm/core/predictor.clj` — `defpredictor` / `predictor` / `run`
  (exported as `run-predictor`), params schema + validation, layered
  resolution (`with-params` > `set-params-roots!` dirs > `:params` defaults,
  first-found-wins-whole, mtime-cached file reads), LM resolution (call-site >
  params `:lm` > params/predictor `:tier` via `set-lm-resolver!`), signature
  transforms (`with-instructions`, `with-field-descs`, `prepend-output`,
  `append-input`), tracing (`with-trace`, conveyed into futures; errors traced
  then rethrown) and a global `set-trace-sink!`.
- `prompt.clj` — `:demos` system part between format and objective
  (`render-demos`); system message now assembled only by `collect-system-parts`.
  Verified byte-identical to `main` for predict / json-schema / CoT with no demos.
- Predictor ids are params-file paths, restricted so they cannot escape a root.
- `memory/graph-extract` is the first migrated call site
  (`memory/core/signatures.clj`, used by `extract/make-extract-fn`).
- App: `install-predictor-params!` in `agent_tui_app/main.clj` (TUI, `by ask`,
  memory commands incl. the detached reduce) installs
  `<project>/.brainyard/programs` then `~/.brainyard/programs` (`"programs"
  :both` in `subdir-scope-policy`) and resolves tiers via `resolve-tier-lm`
  (now exported from the agent interface).

### As built (Phase 0 + memory-agent migration, 2026-09-14)

- BT `dspy` node takes `:predictor-id`: the call goes through `run-predictor`
  with the node's own (possibly per-turn) signature, so params apply and the
  call is traced; the id rides `:agent.dspy-action/pre|post`. The node binds
  `with-trace-context {:agent :node-id}` so sinks can route.
- Tagged: `coact/think-act-code` (main node **and both repair retries** — a
  retry that ignored params would send a different prompt than the attempt it
  retries) and `coact/evaluate-answer`.
- `memory-agent/{essence-extraction,fact-verification,llm-reducer}` migrated.
- Log: `agent/common/predictions.clj` installs the clj-llm trace sink; with
  `:enable-prediction-log` (`BY_ENABLE_PREDICTION_LOG`, feature
  `:analytics/predictions`, default off) it appends one EDN line per call to
  `sessions/<id>/predictions.edn` (EDN lines, not NDJSON — same reader
  discipline as `trajectory.edn`). Routes on the trace-context agent, else
  `proto/*current-agent*`; no agent ⇒ not logged (e.g. the detached reduce).
  Verified with a real `by ask`: one ThinkActCode record with inputs, outputs,
  reasoning, usage and node id.

**Not yet:** skill-distill signatures, commands/CLI (`program$*`), and
everything from Phase 2 on. Note the live run's record carried recalled L3
memory verbatim in `:inputs` — the reason the log is off by default and why
dataset builders must redact before anything becomes a demo.

Phases 0–2 are useful even if every optimization result is negative: they give
brainyard a measured, reproducible answer to "which model should this internal
step use", which today is a config guess.

## 10. Non-goals

- Porting Python DSPy or depending on it (process boundary, native image,
  Malli typing already better than DSPy's string fields).
- Replacing the behavior tree or CoAct loop with a module graph.
- Weight finetuning (`BootstrapFinetune`) — evoharness-agent's domain.
- Automatic, unreviewed prompt changes in a shipped binary.
- Replacing skills: skills are *procedural knowledge for the agent to read*;
  demos are *input/output exemplars for one predictor*. They coexist; skill
  distillation and demo bootstrapping share dataset builders.

## 11. Open questions for review

1. Param store scope: are compiled params **project**-scoped (a repo's
   extraction style) or **user**-scoped (a user's model roster)? Proposal
   layers both; which wins by default?
2. Should baked-in (shipped) params exist at all, or does every install start
   zero-shot and compile locally? Shipped params carry the provider-portability
   risk (R5) to every user.
3. Is `refine` a module, or should `predict` itself grow `:max-refines` driven
   by schema validation (smaller surface, less composable)?
4. Does `EvaluateAnswer` calibration need its own human-labelled set before it
   can serve as a metric for anything downstream? (Likely yes.)
5. Component boundary: one new `lm-compiler` component, or split
   `lm-dataset` / `lm-optimizer`?

---

## Appendix A — Concept mapping

| DSPy (paper) | Brainyard today | Proposed |
|---|---|---|
| `dspy.Signature` / `"q -> a"` | `defsignature` + Malli | unchanged; + transforms (§4.2) |
| `InputField/OutputField(desc, prefix)` | Malli `{:desc}` | + `:field-descs` param override |
| `Predict` | `clj-llm/predict` fn | `defpredictor` + `run` (§4.1) |
| `ChainOfThought` | `clj-llm/chain-of-thought` | `(prepend-output sig :reasoning)` + predict |
| `MultiChainComparison` | — | `multi-chain-comparison` |
| `ProgramOfThought` | CoAct code channel | optional module over `clj-sandbox` |
| `ReAct` | CoAct BT loop | **kept as BT**; `ThinkActCode` parameterized |
| `Retrieve` | memory recall (FTS + graph + vec) | `retrieve` module |
| `dspy.Module.forward` | BT hiccup / sub-agent-as-tool | `defmodule` fn; `bt/module` adapter |
| `settings.lm` / `ParameterLM` | `:lm-config`, tiers, `resolve-sub-lm` | predictor `:tier` / `:lm` param |
| `ParameterDemonstrations` | — | `:demos` param + examples cache zone |
| `settings.trace` (compile mode) | `:agent.dspy-action/post` hook data | `with-trace`, `predictions.ndjson` |
| `dspy.Example` | trajectory records | Example map + builders |
| metric | verdicts, PQS | metric fns; LM-judge as module |
| `evaluate_program` | — | `evaluate` with budget |
| Teleprompter `compile` | skill distillation (analogous) | optimizers → staged params proposal |
| `teacher=` | `:agent-lm-tiers` | tier-aware compile (§4.7) |
| `BootstrapFinetune` | evoharness-agent | out of scope; export hook only |
| `Ensemble` | — | `ensemble` |

## Appendix B — Sketch: BootstrapFewShot in brainyard terms

```clojure
(defn bootstrap-few-shot
  [program {:keys [trainset metric teacher max-demos budget-usd]
            :or   {max-demos 4}}]
  (let [teacher (or teacher (with-tier program :deep))
        demos   (atom {})]                      ; predictor-id -> [demo]
    (with-budget [budget-usd]
      (doseq [ex trainset
              :while (not (enough? @demos (predictor-ids program) max-demos))]
        (with-trace [t]
          (let [pred (try (teacher (:inputs ex))
                          (catch Exception e (classify-and-skip e)))]
            (when (and pred (metric ex pred @t))
              (doseq [{:keys [predictor-id inputs outputs reasoning]} @t]
                (swap! demos update predictor-id (fnil conj [])
                       (redact {:inputs inputs :outputs outputs :reasoning reasoning
                                :source (select-keys (:meta ex) [:session-id :turn])}))))))))
    {:params (update-vals @demos #(hash-map :demos (vec (take max-demos %))))
     :report {:trainset-size (count trainset) :accepted (count-demos @demos)}}))
```
