# evoharness-agent — training specialist for the brainyard-native policy

- **Namespaces**: `ai.brainyard.agent.common.evoharness-agent`,
  `ai.brainyard.agent.common.evoharness-commands`
- **Status**: **proposed** — nothing below is built. The companion plan is
  `../../../brainyard-playground-apps/docs/design/evoharness-section-plan.md`
  (a sibling checkout of this repo, not a subdirectory — the two-dot form some
  older docs here use does not resolve)
  (§10 specifies this agent; §12 phase 7 is where it lands).
- **Console**: the **EvoHarness** section of `apps/workspace` in
  `brainyard-playground-apps`.
- **Related**: [`evoharness-rl-comparison.md`](evoharness-rl-comparison.md) —
  the analysis this whole line of work starts from, and the source of the
  A1/A2/A3 candidates referenced here; [`rag-agent-design.md`](rag-agent-design.md),
  whose shape this doc follows because the section anatomy is the same.

---

## 1. What it is for

`evoharness-agent` owns one project's **training loop**: the task suites, the
rollout episodes, the SFT and GRPO runs, the eval reports, and whether a
checkpoint is fit to promote.

**It is deliberately not the path for routine work**, for the same reason
`rag-agent` is not. The console creates a run, cancels it, tails its log and
renders its curves deterministically over HTTP, and none of that costs an LLM
turn. This agent exists for the four things a form cannot decide — and, unlike
RAG's four, three of them are about *spending money correctly*.

| Kind | The judgement |
|---|---|
| **Diagnosis** | A flat reward curve has at least six causes and they look identical on the curve. Telling them apart means reading episodes and their reward decompositions, not staring at the plot. §5.2 orders the causes. |
| **Spend** | A distill run costs frontier tokens; a GRPO run costs GPU-hours on a rented box. Whether the *next* one is worth starting is a judgement about what the last one showed, and it is the judgement most likely to be made badly by momentum. |
| **Task authoring** | Writing an assertion-backed task that targets one specific weakness — the code channel, a tool the policy avoids, a multi-turn shape — is the highest-leverage and least automatable work in the project. A suite that does not discriminate cannot be fixed by more GPU. |
| **Promotion** | Refusing a checkpoint whose eval does not clear the gate, **including when asked to promote it**. The gate exists precisely for the moment somebody wants the number to be good. |

The asymmetry with `rag-agent` is worth stating plainly: a bad RAG turn wastes
a query. A bad EvoHarness turn can start a run that spends real money for
hours and teaches nothing. The instruction is weighted accordingly.

---

## 2. Why HTTP, and why it does not do the work itself

`evoharness-commands` calls the `evoharness-backend` FastAPI control plane over
HTTP rather than reimplementing any part of the pipeline. Two separate reasons,
and only the first is the familiar one.

**One implementation of the reward.** The control plane owns `reward.py`, split
enforcement, and the episode store. A second Clojure implementation of the
reward decomposition **would drift** from the one that produced the numbers in
the store — and the failure mode is not a crash, it is the agent and the
console quietly disagreeing about what an episode scored. That is the same
argument `rag-agent-design.md` §2 makes about the embedder, and it is stronger
here: the reward is versioned (`EVO_REWARD_VERSION`), so "which reward scored
this" is a real question with a real answer, and only the store knows it.

**Rollouts are minutes long and they mutate.** An episode creates a throwaway
project and a throwaway memory DB, writes files, edits config and consumes a
model server. An agent turn is the wrong container for that, so the agent
**asks for a run and polls** — it never drives an episode inline. The run kinds
in the plan's §6.3 exist so that "start something slow" is a first-class,
cancellable, resumable object rather than a turn that might time out.

The cost is the same one RAG pays: the agent depends on a sidecar. So **every
command returns `{:error …}` rather than throwing** when the control plane is
unreachable, and the message is actionable — *"start it with `npm run dev -w
@brainyard/evoharness-backend`"* — because "connection refused" does not tell
an operator which of the three run modes they are in.

`EVO_API_URL` selects the control plane; the workspace section puts it on this
agent's owner process via `SECTION_CONTEXTS.evoharness.gates`, so one project's
agent talks to that project's control plane. `EVO_PROJECT` is the scope the
backend stamps on what it writes; it is set on the **backend**, and this
namespace never has to know it.

---

## 3. The command family

| Command | Backend | Notes |
|---|---|---|
| `evo$health` | `GET /health` | control plane + store + **executor reachability + trainer GPU + served checkpoint**. Four things can be down; the message must say which. |
| `evo$stats` | `GET /stats` | task/episode/run counts **by split**, token spend to date, corpus size |
| `evo$suites` | `GET /suites` | task suites and their provenance |
| `evo$import-suite` | `POST /suites/import` | ingests a `scripts/test-agent-*.sh` file as tasks — the suite seed |
| `evo$tasks` · `evo$add-task` | `GET` · `POST /tasks` | a task is prompt + setup + assertions + budgets + **split** |
| `evo$run` | `POST /runs` | kind = `distill` \| `sft` \| `rollout` \| `grpo` \| `eval`. The expensive verb. |
| `evo$runs` | `GET /runs` · `/runs/{id}` | status, stage, progress |
| `evo$cancel` | `POST /runs/{id}/cancel` | cheap and always available — the counterweight to `evo$run` |
| `evo$log` | `GET /runs/{id}/log` | tail; the first place an infrastructure failure shows |
| `evo$report` | `GET /runs/{id}/report` | pass rate, iterations/turn, tokens/turn, SHS-as-diagnostic, annealing curves |
| `evo$episodes` | `GET /episodes` · `/episodes/{id}` | **the diagnosis surface** — iterations by channel, assertions, reward decomposition |
| `evo$checkpoints` | `GET /checkpoints` | lineage (base → SFT → GRPO step) and eval history |
| `evo$serve` | `POST /checkpoints/{id}/serve` | ask the trainer host to serve it; returns the base URL |
| `evo$promote` | `POST /checkpoints/{id}/promote` | **gated by the backend**, not by this agent's goodwill (§4) |

Timeouts are per-command and explicit, as `rag-commands` does. `evo$run` is a
*submission*, not a wait: it returns a run id immediately, and progress comes
from `evo$runs`. A command that blocked for the length of a GRPO run would be a
design error, not a slow call.

---

## 4. Two prohibitions specific to this agent

Both are cases where the obvious helpful behaviour is the one that destroys the
measurement, so they belong in the design and not only in the instruction.

### 4.1 The agent is not in the reward loop

The plan's §4.3 keeps LLM judges out of the reward path deliberately: an LLM
judge is itself a policy, and at 8B scale a policy that can be gamed will be.
The agent therefore **reads** episodes to diagnose and **never writes a
reward** — no re-scoring, no "this episode should have counted", no overriding
a stored decomposition. `reward.py` is versioned; changing the reward is a code
change and a new run, not an agent action.

The reason is not tidiness. If the agent could re-score, then every curve would
partly be a measurement of the agent, and a model that got better at pleasing
`evoharness-agent` would look identical to a model that got better at the task.
There is no way to tell those apart after the fact, which is why the boundary
is structural: the API exposes no reward-write endpoint at all.

### 4.2 An agent turn is never an eval

This agent runs **inside** brainyard, and the thing under test **is**
brainyard's policy. That makes one request irresistible and wrong: *"try the
new checkpoint and tell me if it's any good."*

What that would produce is one anecdote, on a prompt that is not in the `test`
split, under whatever context profile the current session happens to have, with
the agent both administering and interpreting. Every property the plan's §8.2
promotion gate depends on is absent.

Evals come from `POST /runs` of kind `eval` against the `test` split under the
serving profile. The agent's job when asked is to say so and start one — not to
improvise a substitute.

---

## 5. Instruction design

Four rules carry most of the weight. Like `rag-agent`'s, they are about honesty
rather than capability.

### 5.1 The four rules

> **NEVER report an eval number without its split and its context profile.**

A `train`-split number and a `test`-split number are not comparable, and a
lean-profile number and a serving-profile number are not comparable. The plan's
§7.4 makes the second mismatch the one most likely to produce a quietly-wrong
promotion — a model trained at 16k and read as if it had been measured at 100k.
A number without those two labels is not a weak claim, it is an unfalsifiable
one.

> **A voided episode is not a zero.**

Exit 2 in the harness contract is a *runner* failure — the model server was
down, the JVM crashed, the box went away. An agent that reads a void as a
failure will diagnose the model for an infrastructure problem, confidently and
at length. Check the void rate before anything else, and say it out loud when
it is not near zero.

> **Before you read a curve, read three episodes.**

Reward-shaped failure and model-shaped failure produce the same flat curve.
They look different in exactly one place: the per-episode reward decomposition.
This is the direct analogue of `rag-agent`'s "name WHICH signal should have
caught it" — the diagnosis lives in the per-item evidence, never in the
aggregate.

> **Say what it will cost before starting a run.**

`rag-agent` has a version of this rule for KG extraction; here the units are
GPU-hours and frontier tokens rather than one LLM call, so it binds harder. A
`distill` run's cost is estimable from task count × mean episode tokens; a
`grpo` run's from group size × episodes/hour × the hourly rate. Estimate,
state, then start — and prefer `evo$cancel` early over hope.

### 5.2 Causes in the order they actually occur

When a run underperforms, this is the ordering the instruction should teach —
cheapest and most likely first, "the model didn't learn" last, because it is
the conclusion that stops investigation:

1. **The episodes voided.** Infrastructure. `evo$log` and the void rate.
2. **The tasks are wrong.** Assertions too strict, prompts ambiguous, or a
   suite that every checkpoint passes and therefore measures nothing.
3. **The sandbox is rejecting the code.** Format, not policy — the plan's §3.2.
   If sandbox-error rate is high, the run is measuring syntax and the SFT gate
   should have caught it.
4. **The context profile differs** between the training run and the eval.
   §7.4's mismatch, and it is invisible unless someone compares the labels.
5. **The cost term is dominating the task term.** The §4.2 failure where a
   policy learns that the cheapest episode is the one it gives up on. Symptom:
   reward improving while pass rate falls.
6. **The model did not learn.** Only after the five above are excluded.

That ordering is the most transferable thing in this document, and it is the
part most likely to be wrong in an interesting way once real runs exist. It
should be revised from measurement, the way `rag-agent`'s diagnosis order was.

### 5.3 What the agent should volunteer

Two states it should raise unprompted, because both silently invalidate work in
progress:

- **A prerequisite is missing.** Without PREREQ-1 (headless trajectory
  persistence) an episode has no iteration detail, so rule 5.1's "read three
  episodes" is inert and the agent has nothing but answer strings. It should
  say that rather than diagnose from the little it has.
- **The train/serve profiles disagree.** Whenever it reports a checkpoint, if
  the profile it was trained under is not the profile the eval ran under, that
  fact leads the answer.

---

## 6. Roster

`coact/run-coact-derived`, like every other specialist — `:agent-tools` **adds
to** the CoAct roster rather than replacing it, and the `:tools` vector is built
with `(vec (distinct (concat …)))` exactly as `rag_agent.clj` does:

- **the `evo$*` family** — the training surface itself;
- **file tools** — to read a `test-agent-*.sh` before importing it, and to read
  a trajectory when the store's projection is not enough. The same reason
  `rag-agent` has them: looking at the material *is* the work;
- **shell tools** (allowlisted reads);
- `query$llm` — for synthesis over many episodes, never for scoring one (§4.1);
- **task commands** — runs are long, and a slow submission wants a background
  task;
- **invocation tools** — bookkeeping.

No `BY_ENABLE_*` gate: there is no unattended loop to arm. The section's gates
carry `EVO_API_URL`, `EVO_PROJECT` and `BY_ASK_TIMEOUT_MS=600000` — longer than
RAG's 300000 because a diagnosis turn may poll a run and read many episodes.

Registered by adding one line to `ai.brainyard.agent.interface`'s require
block, next to `ai.brainyard.agent.common.rag-agent`.

---

## 7. What this agent cannot know

Named here so the instruction does not have to pretend otherwise, and so the
next reader does not file these as bugs:

- **It cannot see the GPU.** Trainer state arrives through `/health` as the
  executor reports it. A stalled remote job and a slow one look the same from
  here; `evo$log` is the only discriminator, and it is a remote file.
- **It cannot audit split enforcement.** A split is a column in the control
  plane's store, and the agent reports what the API says. If the API is wrong
  about a split, the agent will be wrong with it — which is exactly why the
  plan makes the split a column rather than a convention.
- **It cannot compare against a baseline it has not run.** "Better than the
  frontier model" is a claim about two eval runs on the same split under the
  same profile. Absent the second run, the honest answer is that the comparison
  has not been made.
- **It has no opinion the training does not give it.** If asked whether the
  approach will work, the answer is the plan's own: the paper's 96.9% is
  evidence that a harness-shaped policy is learnable at 8B, not a projected
  result for this task. Phases 1 and 2 exist to answer it cheaply, and quoting
  a number from a different environment as if it were ours is the exact
  failure `evoharness-rl-comparison.md` §5 was written to prevent.
