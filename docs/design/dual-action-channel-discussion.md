# Two Action Channels — Do Both Earn Their Prompt?

> **Status:** Discussion document, 2026-08-24. Nothing here is decided and
> nothing has been implemented. It exists because the question "is the JSON
> tool-calls channel still worth its prompt space now that the code channel is
> the primary one?" kept coming up while working through the
> [lisptc](https://d4shi.com/blog/lisptc) audit
> (`sandbox-surface-and-macros-design.md`), and the answer turns out to be more
> interesting per-agent than globally.
>
> Measurements are from `~/.brainyard/logs/agent-tui-app.log{,.1,.2,.3}`,
> 2026-08-14 → 08-23, single user, single machine. Read §6 before quoting any
> number in this doc.

## 1. The question

`ThinkActCode` gives the model three output fields — `tool-calls`, `code-blocks`,
`answer` — and the prompt carries a decision table telling it which to populate.
Both action channels are on by default (`coact_agent.clj`,
`:or {code-channel? true tool-channel? true}`), gated per-agent by the features
`:exec/code-channel` and `:exec/tool-channel`.

lisptc's thesis says the tool-call channel is the wasteful one: every call is a
round trip and every intermediate value passes through context, whereas a
program composes calls without returning to the model. If that holds here, the
tool channel is legacy weight and should go.

Three things need to be true for that conclusion, and only one of them is.

## 2. Usage — neither channel is vestigial

From `coact-agent/accumulate` events, which carry the channel each iteration
actually used:

| channel | iterations | share |
|---|---|---|
| `code` | 488 | 65.8% |
| `tool` | 162 | 21.8% |
| `none` | 73 | 9.8% |
| `answer` | 19 | 2.6% |
| **total** | **742** | |

**Sanity check on the counting.** 488 code-channel iterations against 487
independently-counted `code-eval` events. That near-exact agreement is the
evidence that these are top-level events and not replay-inflated — an important
check, because the *error* text in these same logs is inflated ~13x by prompt
replay (see the design doc's §1 correction).

Code is the primary channel by 3:1. The tool channel is not close to unused.

## 3. Cost — ~342 tokens, mostly cached

Rendering `render-instructions` at each configuration:

| configuration | chars | ~tokens |
|---|---|---|
| both (default) | 7777 | 1945 |
| code only | 6411 | 1603 |
| tool only | 4480 | 1120 |

The tool channel adds **~342 tokens** of instruction block; the code channel
adds ~825. Both are system-prompt text, which sits in the stable prompt-cache
zone, so the per-turn marginal cost on a cache hit is a fraction of face value.

~342 largely-cached tokens to enable 21.8% of all actions is not a close call at
the global level. The interesting question is per-agent (§5).

## 4. Does the code channel actually save round-trips?

This is lisptc's central claim, and it is the one the data does **not** support.
Grouping turns by which channels they used, and taking the highest iteration
number reached:

| turn used | turns | mean iterations | median | max |
|---|---|---|---|---|
| code only | 107 | **2.04** | 1 | 19 |
| tool only | 20 | **2.00** | 2 | 6 |
| mixed | 18 | **5.17** | 4 | 16 |
| neither | 7 | 2.43 | 3 | 3 |

**Code-only and tool-only turns cost the same number of round trips** — 2.04 vs
2.00. The median favours code (1 vs 2), which is consistent with code
one-shotting simple work, but there is no aggregate round-trip win here.

That does not refute lisptc; it says that in *this* workload the composition
advantage is not what dominates turn length. Most turns are short either way.

**Mixed turns cost 2.5x more**, and that is the striking number. But the
causation is ambiguous and probably runs mostly the other way: hard tasks need
more iterations *and* are more likely to touch both channels. Nothing here
separates "switching channels is expensive" from "expensive turns switch
channels." Treat it as a flag for investigation, not a finding. See §7.

## 5. The per-agent picture, which is where the actual decision lives

| agent | n | code | tool | none |
|---|---|---|---|---|
| `rag-agent` | 18 | **100%** | 0% | 0% |
| `mcp-agent` | 78 | **97%** | 1% | 0% |
| `tsf-agent` | 20 | **95%** | 0% | 5% |
| `explore-agent` | 58 | 79% | **0%** | **21%** |
| `research-agent` | 103 | 67% | 23% | 10% |
| `coact-agent` | 76 | 63% | 9% | 4% |
| `edit-agent` | 262 | 58% | 32% | 10% |
| `exec-agent` | 62 | 37% | **50%** | 13% |

The global 3:1 average hides a range from 100/0 to 37/50. Two things follow:

- **Four agents essentially never use the tool channel** (`rag`, `mcp`, `tsf`,
  `explore`). For them the ~342 tokens and the extra decision-table row are pure
  overhead, and `:exec/tool-channel` is already a per-agent feature gate — so
  turning it off is configuration, not code.
- **`exec-agent` is tool-dominant** (50% tool vs 37% code), the only agent that
  inverts the global ratio. Whatever reasoning says "the tool channel is
  legacy" has to explain exec-agent.

`explore-agent`'s **21% `none`** is the highest format-failure rate in the table
and worth its own look — that is one in five iterations producing no action at
all (see §6 for what `none` means).

## 6. What the numbers do and don't mean

- **`none` is not routing confusion.** `:last-channel :none` is set in exactly
  three places in `coact_agent.clj`, all provider/format failures: a FATAL
  abort, a plain-text response with no JSON envelope, and an invalid-schema
  response. After deduplication the FORMAT ERROR class is 4 distinct root causes
  dominated by a Bedrock *model-identifier config error*. So 9.8% `none` is not
  evidence that three output fields confuse the model.
- **Small samples.** 20 tool-only turns and 18 mixed turns. The per-agent table
  has rows as small as n=18. Directional at best.
- **One user, one machine, ten days.** Agent mix reflects what this operator
  did, not what the system is for.
- **Iteration count is a proxy for cost, not cost.** A code iteration that
  composes five tool calls does more work than a tool iteration that makes one.
  Equal iteration counts may still mean the code channel did more per round trip
  — this analysis cannot see that.

## 7. What each channel uniquely provides

Independent of frequency, some capability sits on one side only.

**Tool channel:**
- **It works without a sandbox.** `resolve-action-channels` makes it the
  tie-break winner for exactly this reason — *"Tool wins the tie-break because
  it needs no sandbox, so it is the channel that always works."* An agent with
  `:exec/code-channel` off, or whose sandbox failed to build, can still act.
- **API-level structural enforcement** on providers with
  `:supports-json-schema?`. The code channel has no equivalent: its syntax is
  unconstrained, which is precisely the GBNF gap lisptc identifies and that
  Brainyard cannot close with hosted providers. The tool channel is the
  reliably-shaped one.
- Cheaper for a single call — no Clojure to write, parse and evaluate.

**Code channel:**
- Composition without a round trip per call; control flow; intermediates held in
  vars rather than context.
- The only channel that can `def` a value and reuse it next iteration.

These are complementary rather than redundant, which is the simplest
explanation for the 3:1 split persisting rather than collapsing to one.

## 8. Options on the table

1. **Change nothing.** Defensible: ~342 largely-cached tokens for 21.8% of
   actions, and the no-sandbox fallback is a real safety property.
2. **Gate per agent** (cheapest real change). Turn `:exec/tool-channel` off for
   the agents measured at 0-1% tool use. Pure configuration. Risk: an agent that
   has never *needed* the tool channel in ten days may still need it the day its
   sandbox fails — which is the exact scenario the tie-break exists for. Losing
   that is the cost.
3. **Collapse the schema to two fields** (`code-blocks`, `answer`) for
   code-capable agents. The repair message already reads *"Populate exactly ONE
   of `tool-calls` / `code-blocks` / `answer`"*, so there is a shape to simplify
   — but §6 says the measured format failures are a config error, not field
   confusion, so this would be speculative.
4. **Investigate the mixed-turn 2.5x** before changing anything, since it is the
   only number here suggesting the dual channel has a cost beyond tokens.

## 9. Questions for discussion

- Is the no-sandbox fallback worth keeping the channel on for agents that never
  use it? That is a robustness-vs-tokens judgement, not a measurement.
- Why is `exec-agent` tool-dominant when every other agent is code-dominant? Is
  that its instruction, its work, or an accident of this sample?
- Why does `explore-agent` fail to emit an action 21% of the time?
- Does the mixed-turn cost survive controlling for task difficulty? A cheap
  first cut: compare mixed turns against code-only turns *of the same iteration
  depth* and see whether the switch itself correlates with anything.
- Is iteration count the right cost metric at all, or should this be measured in
  tokens-per-turn? (§6, last bullet.)

## 10. Related

- `sandbox-surface-and-macros-design.md` — the lisptc audit this came out of,
  including §7.1 on why the tool channel's context cost turned out already
  solved, and §1 on the replay inflation that makes raw log greps untrustworthy.
- `bb code-eval:stats` — the deduplicating log miner. It does not currently
  report channel mix; adding that would make §2 and §5 reproducible on demand
  rather than by ad-hoc script.
