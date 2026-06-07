# LLM Context Management — CoAct and ReAct Agent Loops

> **Status: Analysis + proposal.** The CoAct/ReAct context-assembly loops
> described below are shipped and current; the §7–8 budgeting and
> cache-breakpoint improvements are proposals, only partially landed.
>
> **Update (2026-05-31) — compaction simplified to one mechanism.** The
> parallel RLM-based compaction stack was removed. `common/compaction.clj`
> and `common/compaction_action.clj` are **deleted**; the per-iteration
> `:compact-ctx` / post-loop `:compact-iterations` BT nodes are gone from
> both CoAct and ReAct, along with the `:enable-context-compaction` /
> `:compaction-threshold-chars` / `:compaction-iteration-trigger` config
> keys (they gated a path that was off by default and dead for CoAct). The
> **single live reducer is now `core/context_budget.clj` (`enforce`)** —
> token-based, deterministic, run at turn-init and per-iteration for both
> agents. Cross-turn `/compact` and the after-turn auto-compaction
> (`common/context_compaction.clj`) are now **deterministic with no LLM
> call**: they progressively shrink the carried-over `:previous-turns`
> chain toward `:compaction-target-ratio`. Compaction triggers are now just
> `:manual` / `:auto` (the `:bt-mid-turn` / `:bt-finalize` triggers are
> retired).

This document analyses how the two production agent loops in Brainyard — **CoAct** (the
default three-channel tool-and-code agent) and **ReAct** (the classic
thought/action/observation loop) — construct, mutate, and budget the LLM context they
send to the model. It then proposes a set of improvements aimed at:

- making context assembly **symmetric** across the two agents,
- giving ReAct the same **token-budget enforcement** CoAct already has,
- closing the gap between **per-turn** and **per-iteration** budgeting,
- improving **prompt-cache friendliness** across providers,
- and rendering **memory recall** in a way the model can actually exploit.

Scope: `components/agent/` (the agent runtime), `components/clj-llm/` (DSPy prompt
construction), `components/behavior-tree/` (the dspy-action that bridges them), and
`components/memory/` (L1/L2/L3 stores). The retired RLM-style design (single
sandbox-REPL loop) is documented separately in
[`docs/reference/CONTEXT-MANAGEMENT.md`](../reference/CONTEXT-MANAGEMENT.md) and is
not the target of this redesign.


## 1. Terminology

A short glossary so the rest of this doc reads cleanly.

- **Turn** — one `ask` invocation on an agent: a single user question, processed
  end-to-end, ending with a final `:answer`. `agent.core.agent/ask` increments
  per-agent `:turn-id` and session-wide `:total-turns` and dispatches the BT.
- **Iteration** — one pass through the agent's behavior-tree thinking loop, which
  produces one LLM call (CoAct) or one-to-two LLM calls (ReAct single/multi mode).
- **System prompt** — the `role: system` message sent to the LLM. Built by
  `clj-llm.core.prompt/build-system-message` (signature instructions + JSON schema)
  and extended in `behavior-tree.core.dspy-action/build-system-prompt` with
  agent-managed *stable-keys*.
- **User prompt** — the `role: user` message. Built from DSPy signature inputs
  (one `name: value` line per input) plus an output-field reminder.
- **Stable-keys** — input keys that the BT dspy-action moves out of the user message
  into the system message, prefixed with `## <key-name>`. The set is configured per
  BT node via `:stable-keys`.
- **L1 / L2 / L3** — the memory layers (`components/memory/`). L1 is in-memory and
  session-scoped (system-context overlays, agent state, recalled-memory snapshots).
  L2 is episodic SQLite + FTS5. L3 is semantic-facts SQLite + FTS5.
- **Section** — a named text region of the prompt (e.g. `:role`, `:critical-rules`,
  `:previous-turns`) managed by `agent.core.context-budget`.


## 2. CoAct Agent — Current Implementation

### 2.1 Behavior tree

`agent.common.coact-agent/coact-behavior-tree` builds:

```
sequence/main
├── condition  question-present
├── action     prepare-conversation       ; ctx-actions
├── action     prepare-recalled-memory    ; ctx-actions
├── action     coact-init                 ; assembles system + user context, sandbox, budget
├── fallback   loop-guard
│   └── repeat/iterate (max-n)
│       └── sequence/iteration
│           ├── action  inc-iter           ; reset per-iter scratch
│           ├── action  compact-ctx        ; LLM-style compaction (currently a no-op
│           │                                 ; for CoAct — its compactable state lives
│           │                                 ; in the section-budget loop, not in
│           │                                 ; :tool-results/:observations/:thoughts)
│           ├── fallback llm-guard
│           │   ├── action  think-act-code   ; DSPy ThinkActCode, stable-keys
│           │   │                            ; {:system-context :user-context}
│           │   └── action  llm-fallback     ; format-error / fatal-error nudge
│           ├── action  display-think
│           ├── fallback router
│           │   ├── seq answer-path (terminal)
│           │   ├── seq code-path
│           │   ├── seq tool-path
│           │   └── action repair          ; nudge if no channel populated
│           └── action accumulate          ; append to :iterations (cap 10)
├── condition  answer-present
├── fallback   finalize-guard              ; optional FinalizeAnswer (off by default)
└── action     store-results               ; trajectory, previous-turns, sandbox snapshot
```

### 2.2 The two stable contexts

`coact-init-action` builds two strings up front and stores them in `st-memory` under
keys that the BT dspy-action treats as stable:

1. **`:system-context`** — `coact-system-context` in `coact_agent.clj`. A static
   blueprint of the agent. The current section order is:

   ```
   :role                     immutable, prio 100
   :execution-model          immutable, prio 99
   :channel-routing          immutable, prio 99
   :tool-call-format         immutable, prio 99
   :code-blocks-format       immutable, prio 99
   :sandbox-context-accessor immutable, prio 95
   :tools                    immutable, prio 90  ; tools overview + index/directory
                                                 ; + agent-tools detail + discovery
                                                 ; + usage guide table + per-agent
                                                 ; overlay
   :critical-rules           immutable, prio 95
   :large-results-playbook   immutable, prio 90
   :instruction              immutable, prio 95
   :agent-context            immutable, prio 90
   :footer                   immutable, prio 100
   ```

   `instruction` / `agent-context` / `tool-context` are first read from BT
   `st-memory` and then merged with L1 entries via
   `agent.core.context/assemble-field`, so operators (or system-context skills) can
   overlay extra `## <name>\n<content>` blocks per field.

2. **`:user-context`** — `coact-user-context`. Volatile, redrawn every turn:

   ```
   :project-instructions     prio 85   (.brainyard/BRAINYARD.md)
   :user-instructions        prio 85   (~/.brainyard/BRAINYARD.md)
   :conversation-history     prio 60   ; compact: "- **role**: snippet" lines
                                       ; compact strategy :shrink-conversation
   :previous-turns           prio 50   ; Q + A + last 3 iterations per turn
                                       ; compact strategy :bump-previous-turns
   :live-artifacts           prio 70   ; reference docs / skill files / notes
                                       ; compact strategy :drop-live-artifacts
   ```

   **Live Artifacts** is a single `## Live Artifacts` section composed each turn
   from two streams — config-seeded reference docs (`CLAUDE.md` / `AGENTS.md`,
   pinned) and LLM-curated artifacts added via the `artifact$*` tools — merged,
   resolved, and rendered (file artifacts as a 400-char preview + `read-file`
   pointer; inline up to `:max-chars`). Dynamic artifacts persist for the session
   in `st-memory-init`; the section is pin-aware under budget pressure and
   de-dupes linked `BRAINYARD.md`/`CLAUDE.md`/`AGENTS.md` by inode.

   See **[artifacts.md](artifacts.md)** for the full design — descriptor model,
   per-turn lifecycle, persistence, rendering, link-dedup, and the `artifact$*`
   tools.

Both strings ride the **system message** because the BT dspy-action has
`:stable-keys #{:system-context :user-context}` on the `think-act-code` node. See
`behavior_tree/core/dspy_action.clj/build-system-prompt`:

```clojure
(defn- build-system-prompt
  [state stable-keys]
  ...
  ;; For each k in stable-keys: "## <name>\n<value>" — sorted alphabetically.
  ;; Excluded from user message via (dissoc all-inputs stable-keys).
  ...)
```

### 2.3 Per-turn DSPy inputs (user message)

The remaining `ThinkActCode` signature inputs land in the user message:

```clojure
{:inputs  {:question         ::acs/question         ; the original question
           :context-briefing ::context-briefing     ; per-iter briefing string
                                                    ; (sandbox L1 keys + dirs)
           :recalled-memory  ::acs/recalled-memory  ; recall hits (one-shot per turn)
           :iterations       ::iterations}          ; in-turn history (cap 10)
 :outputs {:tool-calls       ...
           :code-blocks      ...
           :answer           ...}}
```

Per-iteration `inc-iter` resets `:tool-calls` / `:code-blocks` / `:last-reasoning` /
`:last-channel` so the LLM never sees stale channel scratch. The `:iterations` field
grows monotonically (cap 10, each entry sanitized via `truncate-iter-field`, which
caps each field at the `:max-output-chars` config knob, default 32000).

### 2.4 Token-budget enforcement

`coact-init-action` calls `agent.core.context-budget/enforce` after assembling the
section maps:

```
budget = model->budget(max-context-tokens=128000,
                       max-output-tokens=lm-config[:max-tokens] ?? 4096,
                       safety-ratio=0.10)

merged-sections = system-sections ∪ user-sections
merged-order    = order(system) ++ order(user)

enforce(...) → walks compactable sections in ascending :priority order;
               applies strategy fns supplied by the caller (closures that
               mutate st-memory and re-render the affected section);
               returns refined sections, total-tokens, and :over-budget?.
```

Strategies (in `coact-strategies`) trim user-context sections in ascending
priority — `:bump-previous-turns`, `:shrink-conversation`, `:drop-live-artifacts`
(pin-aware), `:collapse-iterations`, `:tools-tier`, `:bump-parent-trail`.
System-context sections have **no `:compact` strategy** and are immutable; if the
budget can't be met without them, `enforce` reports `:over-budget? true` and the
turn proceeds anyway.

Enforcement runs at init **and** mid-turn: `coact-rebudget-action` re-runs
`enforce` every `:rebudget-every-n-iter` iterations (default 10) as `:iterations`
grows. The full strategy catalog, priority table, the `:keep-floor?` floor (which
protects pinned live artifacts), and the cross-turn compactor are documented in
**[compaction.md](compaction.md)**.

### 2.5 Cross-turn carrier: `previous-turns`

After each turn, `coact-store-results-action` writes a compact iteration summary
plus the answer into `proto/get-st-memory-init :previous-turns` via
`agent.common.previous-turns/append-turn`. Progressive compression rules:

- recent N (default 10) → `:full` (Q + iterations + A)
- next M (default 30)  → `:summary` (Q + truncated A, drop iterations)
- older                → `:minimal` (Q + ≤1600-char A)

Subsequent turns format these into `:user-context` via `format-previous-turns`
(keeping the last 3 iterations of each `:full` turn).

Between turns, `agent.common.context-compaction/compact-context!` (the `/compact`
command and the after-turn auto-compaction) re-applies the same progressive
compression with tighter passes to pre-shrink the carryover toward
`:compaction-target-ratio × :max-context-tokens` — deterministic, no LLM call.
See **[compaction.md](compaction.md)**.

### 2.6 Sandbox: a third surface

CoAct runs an SCI sandbox that the LLM uses through `code-blocks`. Two layers of
state share that sandbox:

- **L1 inputs** — `:recalled-memory`, `:previous-turns`, `:agent-state` rendered
  into a `sandbox-context` map that the LLM reaches via `context-get` /
  `context-keys` / `context-sample`.
- **L2 working defs** — anything the LLM `(def …)`s. Persists across iterations
  unconditionally, across turns when `:enable-sandbox-persistence` is set
  (extracted user-vars are stored on `!session :sandbox-state` and rebuilt next
  turn via `clj-sandbox/build-restore-bindings`).

The sandbox is therefore the only surface where state survives without paying
the prompt-token tax every turn. The contract is documented in the
`sandbox-context-accessor` section of `:system-context`.


## 3. ReAct Agent — Current Implementation

### 3.1 Behavior tree

`agent.common.react-agent/react-behavior-tree` builds two modes off the same shape:

```
sequence/main
├── condition  st-memory.question
├── action     prepare-conversation       ; ctx-actions
├── action     prepare-recalled-memory    ; ctx-actions
├── fallback   check-context
│   └── thinking-loop-subtree
│       └── fallback/repeat-guard
│           ├── repeat (max-iterations, condition-fn: goal-achieved OR rfi)
│           │   └── sequence/iteration
│           │       ├── action inc-iteration-count
│           │       ├── action compact-context        ; RLM-style char-threshold
│           │       │                                 ;   compaction on
│           │       │                                 ;   :tool-results/:observations
│           │       │                                 ;   /:thoughts
│           │       ├── action think-and-select-tools ; multi-mode (1st LLM call)
│           │       │  OR   think-act-and-evaluate   ; single-mode (single LLM call)
│           │       ├── display-think / record-thought
│           │       ├── [multi] condition.last-reasoning
│           │       ├── display-tool-calls / call-tools / display-tool-results
│           │       ├── [multi] action observe-and-evaluate  ; 2nd LLM call
│           │       ├── display-observe
│           │       ├── [multi] record-observation + several conditions
│           │       └── action finalize-iteration     ; append to :iterations
│           └── action repeat-fallback                ; single: fallback-answer
│
│       ; Post-loop (multi-mode only): compact-iterations + FinalizeAnswer +
│       ; trace; single-mode just runs compact-iterations and exits with
│       ; the inline answer.
├── condition  st-memory.answer
└── action     maintain-conversation
```

### 3.2 DSPy signatures and stable-keys

Three signatures, all distinct from CoAct's:

- `ThinkAndSelectTools` (multi) — inputs include `:conversation`, `:question`,
  `:agent-context`, `:recalled-memory`, `:instruction`, `:observations`,
  `:tool-context`, `:tool-results`, `:tools`. Outputs `:tool-calls`.
- `ObserveAndEvaluate` (multi) — inputs include `:conversation`, `:question`,
  `:agent-context`, `:recalled-memory`, `:last-reasoning`, `:tool-context`,
  `:tool-results`, `:thoughts`, `:observations`.
- `ThinkActAndEvaluate` (single) — inputs include all of the above plus `:iterations`,
  and produces every output field in one shot.
- `FinalizeAnswer` (multi only) — inputs `:conversation`, `:question`,
  `:agent-context`, `:recalled-memory`, `:iterations`. Outputs `:answer`.

The BT dspy-actions use `:stable-keys #{:instruction :agent-context :tool-context :tools}`
— so those four ride the system message as `## <key>` blocks. Everything else
(including the growing `:thoughts` / `:observations` / `:iterations` / `:conversation`)
lands in the **user message every iteration**.

### 3.3 No token-budget enforcement

ReAct does not invoke `context-budget/enforce`. The only protection against
context overflow is `agent.common.compaction-action/compaction-action`, which
fires when:

- compaction is enabled (`runtime-config :enable-context-compaction`), and
- accumulated chars across `:tool-results :observations :thoughts :iterations`
  exceed `:compaction-threshold` (default ~32K chars), and
- iteration count >= `:compaction-iteration-trigger` (default 3).

When it fires, it calls `compaction/compact-context`, which spends another LLM
call (via `clj-sandbox/completion`) to summarize `:tool-results`, `:observations`,
and `:thoughts` into one compact string apiece. A separate
`compact-iterations-action` fires before `FinalizeAnswer` to compress
`:iterations` similarly.

There is no per-section token budget, no priority ordering, no deterministic
trim strategies — every reduction goes through another LLM call, which is the
same model that produced the bloat in the first place.


## 4. Shared Plumbing

These pieces are shared between both agents and live below the agent layer:

- **`agent.common.context-actions`** — `prepare-conversation-action` and
  `prepare-recalled-memory-action`. Both fire once at the top of every turn for
  every agent. They snapshot session messages (last 20, dropping the trailing
  user message if it matches the current question) and run **one** cross-layer
  recall against the original question.
- **`agent.core.context`** — `build-comprehensive-context`,
  `format-system-commands`, `assemble-field`, `build-recall-query`,
  `extract-parent-context`. Only `assemble-field` is on the hot path
  (CoAct uses it to overlay L1 system-context entries onto base instruction /
  agent-context / tool-context strings). The other functions are vestigial helpers
  from the pre-section assembler.
- **`agent.core.context-budget`** — `model->budget`, `estimate-tokens`,
  `total-tokens`, `compose`, `enforce`, `default-section-policies`. Used only
  by CoAct today. The policy table covers both system and user sections and is
  the natural home for ReAct's sections too.
- **`agent.common.previous-turns`** — `append-turn` with progressive compression.
  Used by CoAct; ReAct doesn't have a previous-turns concept beyond raw session
  `:messages`.
- **`agent.common.compaction` / `compaction-action`** — LLM-driven char-threshold
  compactor for ReAct's accumulators. Not used by CoAct.
- **`clj-llm.core.prompt`** — message assembly. `build-messages-with-breakdown`
  returns a hierarchical `:token-breakdown` so per-section attribution survives
  into observability.


## 5. What the Model Actually Sees

### 5.1 CoAct — system message

```
Your input fields are:
1. `question` (string): User question
2. `context_briefing` (string): Per-turn briefing: latest tool specs...
3. `recalled_memory` (any): Recalled context from agent memory layers...
4. `iterations` (list): Full iteration history (capped + truncated...)

Your output fields are:
1. `reasoning` (string): Step-by-step reasoning before producing the answer
2. `tool_calls` (list): ...
3. `code_blocks` (string): ...
4. `answer` (string): ...

IMPORTANT: You MUST respond with ONLY a valid JSON object matching this schema:
{...output JSON schema...}

Respond with a JSON object containing all output fields.

In adhering to this structure, your objective is:
<ThinkActCode signature docstring>

## system-context
<role + execution-model + channel-routing + tool-call-format +
 code-blocks-format + sandbox-context-accessor + tools (overview +
 function-index + agent-tools + discovery + usage-guide-table +
 per-agent overlay) + critical-rules + large-results-playbook +
 instruction + agent-context + footer>

## user-context
<project-instructions + user-instructions + conversation-history +
 previous-turns + live-artifacts>
```

### 5.2 CoAct — user message

```
question: <the question>
context_briefing: <directory pointers, agent-state snapshot, restored-vars list>
recalled_memory: <vector of recall-hit maps with :_layer :kind :role :tags :content :created-at>
iterations: <vector of iteration records (cap 10), each {:iteration :thought :channel
              :tool-results :eval-results}, sanitized to 80K chars per field>

Respond with a JSON object containing the output fields, starting with the field
`reasoning`, then `tool_calls`, then `code_blocks`, then `answer`.
```

### 5.3 ReAct (single) — system message

```
Your input fields are:
1..N. <conversation, question, agent-context, recalled-memory, instruction, thoughts,
       observations, tool-context, tool-results, tools, iterations>

Your output fields are: <reasoning, tool-calls, observation, goal-achieved,
                         goal-reasoning, request-for-information, answer>

IMPORTANT: ...JSON schema...

In adhering to this structure, your objective is:
<ThinkActAndEvaluate signature docstring>

## instruction
<react-instruction string>

## agent_context
<user-supplied agent-context>

## tool_context
<react-tool-context string>

## tools
<tools vector, EDN-printed>
```

### 5.4 ReAct (single) — user message

```
conversation: <full last-20 messages, EDN-printed>
question: <the question>
recalled_memory: <vector of recall-hit maps>
thoughts: <vector of every thought this turn so far>
observations: <vector of every observation this turn so far>
tool_results: <vector of every tool-result this turn so far, each truncated to
                ~80K chars>
iterations: <vector of every iteration record this turn so far>

Respond with a JSON object ...
```

Note the asymmetry: in ReAct, `conversation`, `thoughts`, `observations`,
`tool_results`, and `iterations` all live in the **user message** and are
re-shipped every iteration. In CoAct, the long-lived parts live in the **system
message** and only the per-iteration delta (`iterations`) plus the question and
recall hits go in the user message.

That asymmetry has two consequences:

1. **Prompt-cache.** A provider with prefix caching (Anthropic
   `cache_control`, OpenAI automatic prefix cache, etc.) can re-use CoAct's
   long stable contexts across iterations almost for free. ReAct's growing
   user message changes every iteration, so the cache hit shrinks to the system
   prompt (which itself doesn't include conversation / iteration history).
2. **Re-billing of stable text.** ReAct pays full input-token cost for the
   tools listing, the conversation, and the entire iteration history every
   iteration. A 10-iteration ReAct turn can easily ship 5–10× more input tokens
   than the equivalent CoAct turn even before either compactor fires.


## 6. Gap Analysis

| # | Gap | Affected agent | Severity |
|---|-----|----------------|----------|
| G1 | **No token-budget enforcement in ReAct.** Only char-threshold compaction via an extra LLM call. No per-section priority, no deterministic trim. | ReAct | **High** |
| G2 | **Budget enforced once per turn in CoAct.** `:iterations` grows mid-turn but the budget is not re-checked until next turn. Long turns can drift over budget without intervention. | CoAct | **High** |
| G3 | **Asymmetric placement of conversation / iteration history.** ReAct ships them in the user message every iteration; CoAct keeps the stable parts in the system message. Per-iteration billing diverges 5–10×. | ReAct | **High** |
| G4 | **`:tools` section is immutable in CoAct's budget loop.** A large tools overlay (function directory + agent-tools + usage-guide table + per-agent overlay) can dominate the budget while only the volatile user-context sections get trimmed. | CoAct | **Medium** |
| G5 | **One-shot recall per turn.** `prepare-recalled-memory-action` runs once at turn start with the raw `:question`. If the LLM pivots mid-turn, no further recall fires unless the LLM proactively calls `memory$recall`. | both | **Medium** |
| G6 | **Recall hits are layer-flat.** Hits across L1/L2/L3 are merged into one `:recalled-memory` vector; the model can't tell episodic from semantic from system-context overlays and can't weight them. The `:_layer` field is projected but never rendered as a heading. | both | **Medium** |
| G7 | **ReAct's compaction is LLM-driven and expensive.** Every compaction round costs another LLM call that summarizes accumulators into single strings — lossy and pays the same model that produced the bloat. | ReAct | **Medium** |
| G8 | **No mid-turn re-budget for ReAct's `:iterations`.** Multi-mode runs `compact-iterations` only before `FinalizeAnswer`. Single-mode also runs it post-loop. Neither acts during the loop. | ReAct | **Medium** |
| G9 | **System-context overlay (L1 `:kind :system-context`) has no budget check.** `assemble-field` will append every entry it finds, regardless of how much that inflates the stable system prompt. | both | **Medium** |
| G10 | **No provider-aware cache breakpoints.** `:stable-keys` is meant to maximise prefix caching, but the prompt assembler emits a single string per message; there is no marker the provider adapter can use to insert Anthropic `cache_control` or partition the prompt for OpenAI prefix caching. | both | **Medium** |
| G11 | **Tool results vs code-eval results have different truncation contracts.** Tool results live in `:iterations[].tool-results` (sanitized to 80K chars / field via `truncate-iter-field` which spills overflow to a file marker). Code-eval results are produced by `clj-sandbox/truncate-to-file`. The on-disk artifact path is inline in the marker but not surfaced as a separate `:artifact-pointers` field; the LLM has to grep marker lines. | CoAct | **Medium** |
| G12 | **Sub-agent context handoff is by string only.** A derived agent (skill / plan / explore / research) inherits parent's `:instruction`, `:tool-context`, `:agent-tools` via `run-coact-derived`, but no L1 entries, no previous-turns, no recalled-memory. The sub-agent fires its own recall, builds its own previous-turns chain. Continuity across the parent/child boundary relies entirely on the `:question` string. | both | **Medium** |
| G13 | **No structured "context briefing" for ReAct.** CoAct's `build-context-briefing` surfaces L1 surface size, dirs, restored-vars at the top of each iteration's user message. ReAct has no equivalent; the LLM must re-read `:tools` (a big vector) every iteration. | ReAct | **Low** |
| G14 | **`agent-context` and `:tool-context` are user-controlled strings without size guards.** A long agent-context overlay pushes everything else out of budget; the only recourse is `context-compaction/compact-agent-context`, which LLM-summarizes after the fact. | both | **Low** |
| G15 | **No cross-turn tool-result cache.** Identical tool calls across turns (especially deterministic MCP reads) re-pay tokens *and* re-make the network call. L2 memory has FTS5 ready to act as such a cache but no wiring exists. | both | **Low** |


## 7. Proposed Improvements

The proposals are grouped into three batches: **P1 — symmetry**, which closes the
gap between the two agents; **P2 — budget loop**, which makes per-iteration
budgeting deterministic; and **P3 — recall and provider awareness**, which
improves what the model sees and how it gets cached.

### P1.1 Lift section assembly into `agent.core.context`

Today, CoAct's section assembler (`coact-system-context`, `coact-user-context`,
`build-tools-section`) lives inside `coact_agent.clj`. Extract a generic
`SectionAssembler` value:

```clojure
(ns ai.brainyard.agent.core.context.section-assembler)

(defprotocol SectionAssembler
  (sections      [this state]   "Return {section-kw text} for the current state.")
  (order         [this]          "Return [section-kw …] render order.")
  (policies      [this]          "Return {section-kw {:priority N :compact strategy-kw}}.")
  (strategies    [this st-memory]
                 "Return {strategy-kw (fn [sections] → sections')} — closures
                  over st-memory the budget loop will invoke."))
```

Provide one implementation per agent: `CoActAssembler`, `ReActAssembler`. The
existing per-agent context functions become the concrete `sections` body.

### P1.2 Move ReAct's stable context into the system prompt

ReAct currently passes `:conversation`, `:tools`, accumulators, and recall hits
into the user message every iteration. Restructure so that:

- **System message** gets: `:role` (synthesized from instruction), `:tool-call-format`,
  `:tools` (formatted by `format-agent-tools`), `:tool-context`, `:agent-context`,
  `:instruction`, `:critical-rules`.
- **User message** gets: `:question`, `:context-briefing` (new), `:recalled-memory`,
  and the in-turn deltas: `:thoughts`, `:observations`, `:iterations` (cap N).

This requires:

1. New stable-keys on the ReAct dspy-actions: `#{:system-context :user-context}`,
   matching CoAct's pattern.
2. Replacing `format-conversation`-equivalent logic in ReAct so conversation
   history lands in `:user-context` (volatile but cache-stable across iterations
   within a turn) instead of as a top-level signature input.
3. Updating `ThinkActAndEvaluate` / `ThinkAndSelectTools` / `ObserveAndEvaluate`
   / `FinalizeAnswer` signatures to drop redundant inputs (`:conversation`,
   `:agent-context`, `:tool-context`, `:tools`) — they're now in the system
   prompt assembled by the new assembler.

### P1.3 Add ReAct sections to `default-section-policies`

```clojure
;; ReAct system sections (immutable per turn)
:react/role                 {:priority 100}
:react/critical-rules       {:priority 95}
:react/tool-call-format     {:priority 99}
:react/tools                {:priority 90}
:react/tool-context         {:priority 90}
:react/instruction          {:priority 95}
:react/agent-context        {:priority 90}

;; ReAct user sections (volatile)
:react/conversation-history {:priority 60 :compact :shrink-conversation}
:react/thoughts             {:priority 55 :compact :keep-last-n-thoughts}
:react/observations         {:priority 55 :compact :keep-last-n-observations}
:react/iterations           {:priority 50 :compact :collapse-old-iterations}
```

Strategies are deterministic — `keep-last-n-*` keeps the most recent N items
verbatim and drops the rest (no LLM call). `collapse-old-iterations` keeps the
last 3 verbatim and replaces older iterations with a one-line summary
(`<thought 1-liner> → <tool-name>(<args-summary>) → <2-sentence observation>`).

### P1.4 Replace LLM-driven compaction in ReAct with the deterministic enforce loop

Delete the `compact-context-fn` / `compact-iterations-fn` actions from ReAct's
behavior tree. Wrap the dspy-action with the same shape CoAct uses:

```
sequence/iteration
├── action  inc-iter-and-reset-scratch
├── action  enforce-budget           ; agent.core.context-budget/enforce
├── action  dspy ThinkActAndEvaluate
├── ...
```

This pays zero extra LLM tokens for compaction (the deterministic strategies
trim or drop sections), preserves recent activity verbatim, and reuses the same
diagnostics CoAct already emits (`:agent.context/budgeted` hook).

### P2.1 Make `:iterations` compactable in CoAct

Add a strategy to CoAct's `coact-init-action` `strategies` map:

```clojure
:collapse-iterations
(fn [secs]
  (let [iters (or (:iterations @st-memory) [])
        keep-n 3]
    (if (<= (count iters) keep-n)
      (dissoc secs :iterations)  ; or leave as-is
      (let [recent (vec (take-last keep-n iters))
            older  (vec (drop-last keep-n iters))
            summary (summarize-iterations-deterministic older)]
        (swap! st-memory assoc :iterations
               (into [{:iteration 0
                       :channel "summary"
                       :thought summary
                       :tool-results []
                       :eval-results []}]
                     recent))
        (assoc secs :iterations
               (format-iterations-block (:iterations @st-memory)))))))
```

Add `:iterations` to the section map and order. The LLM still sees a `:iterations`
input, but its size is bounded.

`summarize-iterations-deterministic` is a pure-Clojure function: it emits one
line per dropped iteration of the shape `(N) [tool] tool-name(arg-summary)
=> result-snippet` or `(N) [code] lang: code-snippet => result-snippet`.

### P2.2 Re-budget per iteration (or every K iterations)

Move `cb/enforce` from `coact-init-action` to a new BT action `coact-rebudget-action`
inserted at the top of each iteration (or every K=3 iterations, configurable).
The action reads the current `st-memory` state, recomputes `merged-sections`
(including the now-growing `:iterations`), and runs the strategies in priority
order. The user-context strategies (bump-previous-turns, shrink-conversation,
drop-live-artifacts, collapse-iterations) handle the trim.

Cost: an extra token-estimation pass per iteration (chars/4, sub-millisecond).
Benefit: long turns can't quietly blow past the budget.

### P2.3 Make `:tools` compactable

Add tiered strategies for the tools section:

- `:drop-function-index` — drop the `### Sandbox Categories` block (default-on
  when `:include-function-directory? true`, default-off otherwise; this strategy
  just toggles the directory off).
- `:drop-usage-guides` — drop the `### Usage Guides` table.
- `:drop-agent-tools-details` — replace the detailed `### Agent Tools` block
  with a one-line listing of `id (type)` per tool.
- `:drop-tool-context-overlay` — drop the per-agent `### Agent-specific guidance`.

Priorities order them so the cheapest (usage guides) goes first, the most
expensive (agent-tools details) last. The static rules / format sections never
get touched.

### P2.4 Guard the system-context overlay path

`agent.core.context/assemble-field` should accept an optional `:max-chars`
budget and emit a `mulog/warn` (with hook fire) when overlays push past it.
Operators can then see when an overlay is bloating the system prompt without
having to compute token totals manually.

### P3.1 Layer-aware recall rendering

Render `:recalled-memory` as a grouped block instead of a flat vector:

```
## Recalled Memory

### Episodes (L2 — recent activity)
- [2026-05-14 user] Asked about S3 bucket policy
- [2026-05-14 assistant] Provided IAM policy template

### Facts (L3 — long-term)
- (confidence 0.92) The user prefers terraform over CloudFormation.

### System-context overlays (L1)
- (instruction overlay) Be terse; rich-text markdown only.
```

This pulls the projection out of `prepare-recalled-memory-action` (which today
strips `:_layer` to keys but doesn't use it for layout) and into a new
`format-recalled-memory` formatter. Both agents adopt it.

### P3.2 Mid-turn re-recall (optional, behind a flag)

Add a `:re-recall-trigger` hook on `:agent.tool-calls/post` that, when the
top tool result contains entity names the LLM hasn't seen in `:recalled-memory`,
fires a second `prepare-recalled-memory-action` with a refined query. Cheap to
implement (recall is FTS5), high-leverage on multi-hop tasks.

Default: off. Enabled via `runtime-config :enable-mid-turn-recall`.

### P3.3 Provider-aware cache breakpoints

Extend the BT dspy-action to emit cache-breakpoint markers between sections.
Two breakpoints — both fit inside Anthropic's 4-breakpoint budget and leave
two breakpoints free for per-agent overlays:

```
─── system message ────────────────────────────────────────────────────
  :role
  :system-info             ← STABLE across turns and iterations
  :execution-model
  :channel-routing
  :tool-call-format
  :code-blocks-format
  :sandbox-context-accessor
  :tools
  :critical-rules
  :large-results-playbook
  :instruction
  :agent-context
  :project-instructions    ← session-stable; .brainyard/BRAINYARD.md
  :user-instructions       ← session-stable; ~/.brainyard/BRAINYARD.md
  :footer
↑↑↑ CACHE BREAKPOINT 1 — cross-turn boundary ↑↑↑
  :turn-info               ← PER-TURN: date/time + turn-id + total-turns
  :conversation-history
  :previous-turns
  :live-artifacts
↑↑↑ CACHE BREAKPOINT 2 — within-turn boundary ↑↑↑
─── user message ──────────────────────────────────────────────────────
  DSPy signature inputs (:question, :context-briefing, :recalled-memory,
                         :iterations)
```

**Why BRAINYARD.md sits above breakpoint 1:** the two files
(`.brainyard/BRAINYARD.md` and `~/.brainyard/BRAINYARD.md`) are read at every
`coact-init-action`, but their *content* is byte-stable across most turns in
a session — users edit them rarely (and committed `.brainyard/BRAINYARD.md`
files in particular change at git-commit cadence, not per-turn). Placing
them above the cross-turn breakpoint extends the cached prefix by however
many tokens those files contain (typically 1K–10K). The cost of the rare
edit is one full-prefix re-bill on the turn after the edit; the savings on
every other turn are larger by an order of magnitude or more.

Cache-hit profile:

- **Cross-turn cache** (breakpoint 1): every byte above is the same across
  every turn in the session, until the user changes model / cd's / restarts
  **or edits BRAINYARD.md**. A 10-turn session re-uses this cached prefix on
  turns 2–10 and pays the prefix-token cost only on turn 1. BRAINYARD.md
  edits invalidate the cache for one turn, then the new prefix takes over.
- **Within-turn cache** (breakpoint 2): every byte above is the same across
  every iteration in the current turn. A 10-iteration turn re-uses this
  cached prefix on iterations 2–10 and pays the additional cost (turn-info,
  conversation, previous-turns, live-artifacts) only on iteration 1.
- **User message** (no breakpoint): re-billed every iteration. This is the
  smallest growable surface in the redesigned layout — `:iterations` is
  capped by the budget loop (P2.1), `:recalled-memory` is one-shot per turn
  unless P3.2 fires, `:context-briefing` is a small per-iteration block.

**The cross-turn cache only survives if every section above breakpoint 1 is
byte-stable across turns.** Two common mistakes that defeat it:

1. Placing the wall-clock timestamp in `:system-info`. Fixed by the
   `:system-info` / `:turn-info` split (P4.1).
2. Embedding any session-volatile detail (last response excerpt, conversation
   summary, etc.) into `:agent-context` or `:instruction` at turn init. Both
   sit above breakpoint 1, so anything turn-volatile in them invalidates the
   cache for everything that follows — tools, BRAINYARD.md, footer.

The system tolerates the edge case where BRAINYARD.md is edited mid-session:
the file is re-read at the next `coact-init-action`, the rendered section
changes, breakpoint 1 invalidates *once*, the next cache window starts.
The cost amortizes to near-zero for any reasonable edit cadence.

**Optional 3-breakpoint variant.** When BRAINYARD.md is large (>5K tokens)
and edits are common, splitting breakpoint 1 in two further reduces the
edit cost: put a breakpoint between `:footer` and `:project-instructions`,
keeping the static system content above it and the BRAINYARD.md content
between the two breakpoints. A BRAINYARD.md edit then only invalidates the
BRAINYARD.md prefix, not the (much larger) static system prefix. Costs one
extra cache-control header and one extra cache write per session. Anthropic
allows up to 4 cache-control blocks per request, so all four budgets are
still met. Default is the 2-breakpoint layout above; enable the 3-breakpoint
variant via `runtime-config :enable-brainyard-cache-breakpoint`.

Provider adapters in `clj-llm.core.providers` translate breakpoints into:

- **Anthropic** — `cache_control: {type: "ephemeral"}` on the system block
  at breakpoint 1, and on the trailing user-context block at breakpoint 2.
  5-minute TTL by default; the `extended-cache` option (1-hour TTL) is worth
  enabling for sessions where turns are sparse.
- **OpenAI / Bedrock** — leave the prompt assembled in a stable prefix order
  (already the case once P1 + P4 are done) and let automatic prefix caching
  kick in. No explicit marker needed; just don't shuffle section order.
- **Local / Ollama** — no-op (no prompt caching).

The breakpoint marker travels as an opt-in `:cache-breakpoints` field on the
outbound message — invisible to the LLM, consumed by the provider adapter.

**Validation:** add a `mulog/log ::cache-prefix-hash` emission at each
breakpoint with a SHA-256 of the prefix string. If breakpoint-1's hash
changes across two consecutive turns in the same session, that's a regression
(something turn-volatile leaked above the boundary) — the test suite asserts
it doesn't.

### P3.4 Symmetric `:context-briefing` for ReAct

Equivalent of CoAct's `build-context-briefing` — a small block at the top of
the user message that summarises:

- working directory + project name,
- active TODO count,
- last 3 tool/code activities (one line each),
- a pointer to large artefacts on disk (the `--- TRUNCATED` markers from this
  turn, grouped),
- recall hit-count per layer.

ReAct's `ThinkActAndEvaluate` signature already has `:tools`, `:tool-results`,
`:iterations`. Replace the verbose `:tool-results` projection with the briefing
plus a `:recent-tool-results` window (last 3 entries verbatim, older items
folded into the briefing).

### P3.5 Cross-turn tool-result cache (optional)

Hash `(tool-name, normalized-tool-args)` and look up L2 episodic entries with
`:kind :tool-result` and matching tags. If the cache hit's age is below
`:tool-cache-ttl` (default 5 minutes for read-style tools, 0 for writes),
substitute the cached result and skip dispatch. The remember side already runs
via the capture pipeline; only the lookup is new.

This is the smallest viable cross-turn cache. A full version would also
deduplicate within a turn (same `(name, args)` pair → reuse the prior result
from `:iterations` without re-dispatching), but that's a follow-up.

### P3.6 Sub-agent context handoff

When `run-coact-derived` is invoked (skill / plan / explore / research / etc.),
do more than merge `:instruction` / `:tool-context` / `:agent-tools`:

- Lift the parent's last K previous-turns into a new `:parent-trail` field on
  the sub-agent's init.
- Project the parent's L1 `:recalled-memory` (filtered by relevance to the
  sub-agent's question) into the sub-agent's first recall.
- Pass an explicit `:depth` + `:parent-agent-id` so the sub-agent's previous-turns
  can be reassembled into the parent's trajectory.

`agent.core.context/extract-parent-context` already exists but is not wired
into the derived dispatch — wire it.

### P4 — System-Information Surface

The LLM cannot answer "what's today's date?", "is this a macOS box?", "am I
running inside a container?", or "what timezone is the user in?" without those
facts being in the prompt. Neither agent currently surfaces them. The
`env-detect` component already gathers most of the raw data
(`detect-os`, `detect-sandbox-environment`, `detect-executables`,
`detect-all-providers`) and `build-agent-state-snapshot` exposes agent identity
and dirs through the sandbox — but nothing renders this into the prompt.

**Naive design is cache-hostile across turns.** A single `:system-info` block
placed near the top of `:system-context` would put a wall-clock timestamp into
the cached prefix. Every turn the timestamp moves forward, invalidating the
cross-turn cache for every byte that follows it — tools, instruction, agent
context, the whole long stable tail. The fix is to **split the surface into
two sections** at different positions and let the cache-breakpoint policy
(P3.3) put each in the right partition:

- **`:system-info`** — stable across turns. Host, workspace, LLM, agent
  identity. Sits high in `:system-context` (priority 98), *above* the
  cross-turn cache breakpoint. Byte-identical across every turn in a session
  (until the user changes model, cd's, or restarts the process).
- **`:turn-info`** — per-turn only. Date / time, turn-id, total-turns. Sits in
  `:user-context` (priority 88, rendered first inside that group), *below* the
  cross-turn cache breakpoint and *above* the within-turn breakpoint. Changes
  every turn but is byte-identical across all iterations within a turn, so
  prefix caching still saves on iteration-to-iteration replays.

Target size: 200–300 tokens for `:system-info`, 60–80 tokens for `:turn-info`,
capped at ~800 tokens total with optional blocks enabled.

#### P4.1 Section content — core (always on)

The split puts content where its volatility matches the cache partition. Both
blocks are small and contain nothing privacy-sensitive beyond what `CLAUDE.md`
already exposes.

##### `:system-info` (stable across turns — above the cross-turn cache breakpoint)

```
## System Information

### Host
- OS: macOS 15.4 (Darwin, arm64)
- Shell: /bin/zsh
- Locale: en_US.UTF-8

### Workspace
- Working directory: /Users/jake/Projects/MyDev/brainyard
- Project root: /Users/jake/Projects/MyDev/brainyard (.brainyard present)
- Home: /Users/jake
- User: jake

### LLM
- Provider: anthropic · Model: claude-sonnet-4-5
- Context window: 200000 tokens · Max output: 8192 tokens

### Session
- Agent: coact-agent (root, depth 0)
- Session: 25854e31-75b6-4a59-842b-099aa11dc5b2
- Timezone: Asia/Seoul (UTC+09:00)
```

Note: the **timezone name** lives in `:system-info` (it doesn't change across
turns within a session), but the **wall-clock timestamp + offset** are in
`:turn-info`. Splitting them this way means the IANA zone string is part of
the cached cross-turn prefix and only the timestamp moves.

##### `:turn-info` (per-turn — below the cross-turn cache breakpoint)

```
## Turn

- Now: 2026-05-16T14:32:17+09:00 (Saturday)
- Turn: 3 (session total: 7)
```

That's it for the volatile block — three short lines. Everything else either
doesn't change per turn (and belongs in `:system-info`) or already lives in
`:user-context` (`:conversation-history`, `:previous-turns`).

Rendering rules:

- **Time** — ISO 8601 with offset is the canonical string the LLM sees; the
  day-of-week rides alongside as a readability aid. Lives in `:turn-info`,
  not `:system-info`. No epoch — the LLM can derive it from the ISO form
  when needed.
- **Timezone** — IANA zone (handles DST without ambiguity) belongs in
  `:system-info` because it's stable per session; the per-turn UTC offset is
  already encoded in the ISO timestamp. Resolved from
  `java.time.ZoneId/systemDefault` with an optional override from
  `runtime-config :timezone`.
- **OS** — `env-detect/detect-os` gives the JVM-side `os.name` / `os.version` /
  `os.arch`. Map raw names to friendly labels (`Mac OS X` → `macOS`, `Linux` →
  `Linux <distro>` when `/etc/os-release` is readable).
- **Sandbox row** — only emitted when `env-detect/detect-sandbox-environment`
  returns a non-`:host` value (Docker, devcontainer, SSH, Nix). Keeps the
  block lean on bare-metal hosts.
- **LLM row** — pulled from `lm-config` and the provider registry. Context
  window must come from the model record, not the legacy
  `runtime-config :max-context-tokens` default (which is a fallback, not the
  truth for the current model). If the user switches model mid-session, this
  row changes — and that invalidates the cross-turn cache, which is acceptable
  because a new model means a new request shape anyway.
- **Session row** — pulled from `build-agent-state-snapshot`. When the agent
  is a sub-agent, include `Parent: <parent-agent-id>` and the sub-agent's
  depth. Session-id is per-session stable, so the row is cache-friendly across
  all turns in the session.
- **Turn row** — turn-id and total-turns belong in `:turn-info`; they
  increment every turn.

#### P4.2 Section content — optional (gated)

These are off by default. Each one is opt-in via
`runtime-config :system-info-extras` (a set of keywords).

```
### Capabilities (enabled via :system-info-extras #{:capabilities})
- Executables present: git, docker, kubectl, aws, gcloud, jq, rg, fd
- LLM providers reachable: anthropic (API key), openai (API key), ollama (daemon)
- MCP servers connected: filesystem, fetch, slack, jira

### Identity (enabled via :system-info-extras #{:identity})
- Hostname: jakes-mbp
- Git author: Jake Na <jake.na@grumatic.com>

### Build (enabled via :system-info-extras #{:build})
- JVM: OpenJDK 21.0.4 (GraalVM CE 21.0.4)
- Brainyard: <git-sha>, built <iso-timestamp>
```

Privacy / cost rationale:

- **Hostname / git author** — fingerprintable identity. Off by default; an
  operator who wants per-host context can flip them on per-session.
- **Executables / providers / MCPs** — each requires subprocess calls or
  network probes. Cheap individually, but adds 100–500 ms to turn init. Gate
  per-feature so cost is explicit.
- **Build info** — useful for `by --version`-style introspection but rarely
  used by the agent.

#### P4.3 Wiring

Implementation surface (small, additive):

```
components/agent/src/ai/brainyard/agent/core/
  system_info.clj                NEW
    (build-system-info-section ...)
      → string for the stable :system-info section (host/workspace/LLM/session)
    (build-turn-info-section
      {:turn-id N :total-turns M :now <instant> :tz <zone-id>})
      → string for the volatile :turn-info section (date/time/turn-progress),
        plus a {:wall-time-iso :tz-iana :tz-offset-minutes} map for hook payloads.

components/agent/src/ai/brainyard/agent/common/coact_agent.clj
  coact-init-action              EDIT
    → call build-system-info-section, assoc into sys-result :sections
      under :system-info, splice into system section-order between :role
      and :execution-model.
    → call build-turn-info-section, assoc into user-result :sections under
      :turn-info, splice into user section-order at the front (before
      :project-instructions).
  coact-system-context           EDIT
    → add :system-info to system section-order (top half).
  coact-user-context             EDIT
    → add :turn-info to user section-order (rendered first).
    → REMOVE :project-instructions and :user-instructions from user
      section-order (they move into :system-context, see P4.6 below).
  coact-system-context           EDIT (continued)
    → add :project-instructions and :user-instructions to the system
      section-order, just before :footer (above cross-turn breakpoint).

components/agent/src/ai/brainyard/agent/core/context_budget.clj
  default-section-policies       EDIT
    → {:system-info {:priority 98}                    ; stable across turns
       :turn-info   {:priority 88}}                   ; per-turn, no compact
       ;; both immutable — no :compact strategy. They're small, and trimming
       ;; them would defeat the point.
```

The `:system-info` section is recomputed at turn init but should be **byte-stable
across turns** for the same agent/session/model. Implementations must:

- pin the LLM model + context-window from `lm-config` at agent creation, not
  per-turn (otherwise a model-default-change between turns would silently
  invalidate the cache);
- use canonical workspace dir resolution (no `pwd`-style snapshots — read from
  `agent.core.config/init-dirs!`);
- avoid embedding the wall-clock anywhere in `:system-info`.

The `:turn-info` section is allowed to change every turn — that's its job.
It lives below the cross-turn cache breakpoint (see P3.3), so its volatility
costs us nothing on the cross-turn prefix cache. Within a turn, it's
byte-identical across all iterations, so the within-turn breakpoint at the
end of `:user-context` still extends the cache to every iteration.

For very long turns (>30 minutes), wall-clock drift inside `:turn-info` is
bounded by turn duration. To let the LLM re-check the clock from a
`clojure` fence without forcing a section re-render, expose a sandbox-side
accessor — `(now)` returning `{:wall-time-iso :tz-iana :tz-offset-minutes}`.

Sub-agents inherit the parent's `:system-info` block except for the
`### Session` row, which is rebuilt for the sub-agent's identity / depth /
parent. The Host / Workspace / LLM rows stay byte-for-byte identical so
prefix caching extends across the parent→child boundary. `:turn-info` is
always sub-agent-local (each sub-agent has its own turn-id).

#### P4.4 ReAct parity

Once P1.2 has moved ReAct's stable context into the system prompt, the same
`build-system-info-section` lives in ReAct's `SectionAssembler` impl. No
ReAct-specific logic — the function reads `agent` and `lm-config` and is
agent-shape-agnostic.

#### P4.6 Promoting BRAINYARD.md to the cross-turn cache prefix

Both BRAINYARD.md files currently live in `:user-context`. They were placed
there in the original CoAct design because they're "user/project context"
rather than "agent definition" — semantically true, but cache-hostile.

In practice both files change at file-edit cadence (rare in interactive
sessions; typically once per session or less, often never), not per-turn.
That makes them strictly cheaper to host above the cross-turn breakpoint:

- **Token math.** Suppose `.brainyard/BRAINYARD.md` is 4K tokens and
  `~/.brainyard/BRAINYARD.md` is 1K tokens — 5K tokens total. Anthropic
  cache reads bill at ~10% of input cost. Cache writes bill at ~125%.
  - Below breakpoint 1 (current): no cache hit possible on these tokens
    on turn 1 of the within-turn cache window; **5K tokens billed every
    turn**.
  - Above breakpoint 1 (proposed): cache hit on every turn after the first;
    **0.5K-equivalent tokens billed every turn**.
  - Edit cost: one turn re-pays the full prefix (the existing tools /
    instruction / agent-context prefix is also re-billed once because it
    sits above the same breakpoint). One turn × per-edit, vs every turn
    × the BRAINYARD.md tokens. Move is cheaper unless edits happen on
    >50% of turns, which never occurs in practice.
- **Failure mode is graceful.** Re-reading the file at turn init is what
  invalidates the cache; the new content takes effect on the same turn.
  No staleness window.

Implementation: in the proposed `coact-system-context` section-order, insert
`:project-instructions` and `:user-instructions` between `:agent-context`
and `:footer`. Remove them from the user-context order. The
`config/load-brainyard-instructions` call already runs at every
`coact-init-action`; no caching layer is needed because the cache layer
that matters is the LLM provider's, and it already keys on prefix content.

Optional: emit a `mulog/log ::brainyard-changed` event when the rendered
section string differs from the previous turn's. Useful for the user
debugging "why did my last turn cost more than expected" and for the
test suite asserting cross-turn prefix stability outside the edit case.

#### P4.7 Worked example: wall-clock-sensitive workflows

The cases where this section pays for itself in 1–2 turns:

- **Date arithmetic** — "what tasks are due this week?" requires knowing
  "this week" maps to which dates.
- **Logs / time-bounded queries** — "show me errors from the last hour"
  requires the current timestamp to compute the lower bound.
- **Path / filesystem reasoning** — `/Users/jake/...` only makes sense once
  the LLM knows the OS is macOS. On Linux, the same path is wrong.
- **Shell command emission** — `find -mtime -1` (BSD-find on macOS) vs
  `find -newermt "1 day ago"` (GNU find on Linux) requires OS-awareness.
- **Container-sensitive recommendations** — "run `brew install …`" inside a
  Docker container is wrong; the system-info block surfaces the container
  fact upfront.
- **Timezone-aware scheduling** — `schedule` skill needs the IANA zone to
  generate the cron entry.


## 8. Proposed Target Architecture

After P1+P2+P3, both agents share the same context layout:

```
                     ┌────────────────────────────────────────────┐
                     │ agent.core.context.section-assembler       │
                     │ (protocol; one impl per agent)             │
                     └────────────────────────────────────────────┘
                                       │
                       per-turn        │       per-iteration
                       ──────────────  │  ──────────────────────
                       run once at init│  run once per iteration
                                       ▼
              ┌─────────────────────────────────────────────────┐
              │ agent.core.context-budget/enforce               │
              │   • model->budget                               │
              │   • walk compactable sections (asc priority)    │
              │   • strategies = closures over st-memory        │
              │   • returns refined sections + diagnostics       │
              └─────────────────────────────────────────────────┘
                                       │
                                       ▼
              ┌─────────────────────────────────────────────────┐
              │ system message                                  │
              │   :role                                         │
              │   :system-info     (stable across turns)        │
              │   :execution-model · :channel-routing · ...     │
              │   :tools · :instruction · :agent-context        │
              │   :project-instructions · :user-instructions    │
              │     (session-stable; one cache miss per edit)   │
              │   :footer                                       │
              │   ↑↑ CACHE BREAKPOINT 1 — cross-turn ↑↑          │
              │   :turn-info       (per-turn: date + turn-id)   │
              │   :conversation-history · :previous-turns       │
              │   :live-artifacts                               │
              │   ↑↑ CACHE BREAKPOINT 2 — within-turn ↑↑         │
              ├─────────────────────────────────────────────────┤
              │ user message    (re-billed every iteration)     │
              │   ── DSPy signature inputs ──                   │
              │   :question                                     │
              │   :context-briefing       (NEW for ReAct)       │
              │   :recalled-memory        (grouped by layer)    │
              │   :iterations             (bounded; older       │
              │                            collapsed)           │
              └─────────────────────────────────────────────────┘
```

Key invariants:

1. **System message is recomputed at most twice per turn**: once at init, once
   when the budget walker has to compact a system section (rare). It is *never*
   recomputed inside an iteration — providers can prefix-cache it.
2. **User message is recomputed every iteration**: `:question` is static,
   `:context-briefing` is a small per-iter block, `:recalled-memory` is stable
   unless mid-turn re-recall fires, `:iterations` grows but is capped via the
   budget loop.
3. **The prefix above cache-breakpoint 1 is byte-stable across all turns in
   the session.** No wall-clock, no turn-id, no recall hits, no
   conversation. Anything turn-volatile is explicitly placed below breakpoint 1
   (most of it sits in `:user-context`). This is what makes cross-turn cache
   hits possible — and it's a property the test suite asserts (see P3.3's
   `::cache-prefix-hash` log).
4. **All compaction is deterministic by default**; LLM-driven compaction stays
   available as an optional strategy for very long turns where the deterministic
   trim isn't enough.


## 9. Migration Plan

Phased so each step is independently shippable.

| Phase | Scope | Files touched | Breaking? |
|-------|-------|---------------|-----------|
| M1 | Extract `SectionAssembler` protocol + `CoActAssembler`. CoAct keeps behaving identically; the assembler is just the existing functions moved into a protocol. | `agent.core.context.section-assembler`, `agent.common.coact-agent` | No |
| M2 | Add ReAct sections to `default-section-policies`. Implement `ReActAssembler`. Move `:conversation` / `:tools` / `:tool-context` / `:agent-context` / `:instruction` into ReAct's system message via stable-keys. Update ReAct signatures. | `agent.core.context-budget`, `agent.common.react-agent`, `agent.common.context-actions` | **Yes** — ReAct signature input set changes. Update tests. |
| M3 | Wire `cb/enforce` into ReAct's BT (pre-dspy action) with deterministic strategies (`keep-last-n-thoughts`, `keep-last-n-observations`, `collapse-old-iterations`). Delete `compact-context-fn` from ReAct (or keep behind a flag for transition). | `agent.common.react-agent`, `agent.core.context-budget` | No (compaction-action remains; just unused by ReAct's BT) |
| M4 | Add `:collapse-iterations` strategy to CoAct. Add `:rebudget-action` to CoAct's iteration loop. | `agent.common.coact-agent` | No |
| M5 | Make `:tools` compactable with tiered strategies. Wire `:drop-function-index` / `:drop-usage-guides` / `:drop-agent-tools-details`. | `agent.common.coact-agent`, `agent.core.context-budget` | No |
| M6 | Layer-aware recall rendering. New `format-recalled-memory`. Update both agents' DSPy signature descriptions to match the grouped layout. | `agent.common.context-actions`, `agent.common.coact-agent`, `agent.common.react-agent` | No (the value type stays `:any`; only the rendering changes) |
| M7 | Provider-aware cache breakpoints. New `:cache-breakpoints` field on outbound messages. Anthropic adapter sets `cache_control`; OpenAI/Bedrock adapters no-op. | `behavior_tree.core.dspy-action`, `clj-llm.core.prompt`, `clj-llm.core.providers` | No (additive) |
| M8 | Mid-turn re-recall (default off) and cross-turn tool-result cache (default off). | `agent.core.hooks`, `agent.core.memory`, `agent.common.context-actions` | No (gated behind flags) |
| M9 | Sub-agent context handoff. Wire `extract-parent-context` + parent-trail + filtered recall into `run-coact-derived`. | `agent.common.coact-agent`, `agent.core.context` | No (sub-agent gets *more* context, not less) |
| M10 | System-information section. New `agent.core.system-info/build-system-info-section`; wire into `coact-init-action` and the new `ReActAssembler`. Add `:system-info` to `default-section-policies`. Optional `(now)` sandbox binding. | `agent.core.system-info` (NEW), `agent.common.coact-agent`, `agent.core.context-budget`, `agent.common.sandbox-bindings` | No (additive section) |


## 10. Observability and Telemetry

The pieces below already exist; the redesign just leans on them harder.

- **`:agent.context/budgeted` hook** (`coact-init-action`) emits per-section
  token estimates, total tokens, budget, and the list of compactions applied.
  Extend with `:over-budget? <bool>` and `:phase :init|:rebudget` so the
  per-iteration re-budget is distinguishable.
- **`clj-llm/build-token-breakdown`** already produces a hierarchical breakdown
  per LLM call. After M2, ReAct will emit the same shape, so the existing TUI /
  analytics widgets work for both agents without changes.
- **`mulog/log ::coact-init`** logs prompt size. Mirror as `::react-init`.
- **`mulog/log ::context-compacted-successfully`** is wired to `compaction-action`
  only; add equivalent log lines in the section-budget strategies so the analytics
  layer doesn't lose visibility when M3 removes the LLM-driven compactor.


## 11. Open Questions

1. **Iteration cap vs budget interaction.** CoAct caps `:iterations` at 10 in
   `coact-accumulate-iteration-action`. After P2.1's `:collapse-iterations`
   strategy, should the hard cap stay, or should the budget loop be the sole
   bound? Hard cap is safer (predictable upper bound), but it can fire before
   budget pressure even appears.

2. **ReAct signature consolidation.** With stable-keys moving everything into
   the system prompt, the only meaningful difference between multi and single
   modes is "two LLM calls vs one". Worth keeping both? Single mode is the
   default and shipped; multi mode survives mostly for parity with the legacy
   Cloudcast port. A follow-up could deprecate multi.

3. **Sandbox state vs L2 cache.** P3.5's tool-result cache and the existing
   sandbox-state persistence overlap (both let the LLM avoid recomputing).
   Should they share a key namespace (so a `def` in the sandbox is also a
   cache key)? Probably not — sandbox state is per-session, L2 cache is
   cross-session; conflating them would make eviction confusing.

4. **Cache-control budget on Anthropic.** Anthropic caps cache breakpoints at
   four per request. The proposed two breakpoints fit comfortably, but
   per-agent overlays (e.g. a `skill-agent` adding its own `### Agent-specific
   guidance`) might want their own breakpoint. The provider adapter should
   degrade gracefully — collapse adjacent breakpoints when the budget is hit.

5. **Recall budget.** `:recalled-memory` is currently unbounded (limit 10 hits
   * unbounded content per hit). After M6's grouped rendering, should each
   layer's block have an independent budget, or should the whole `:recalled-memory`
   section be in `default-section-policies` with a `:trim-recall` strategy that
   drops the lowest-scoring hits first?


## 12. Appendix A — File and Function Map

A short index of where each piece lives so reviewers can follow the references
without bouncing through the codebase.

```
components/agent/
  src/ai/brainyard/agent/
    common/
      coact_agent.clj            CoAct BT, signatures, section assemblers,
                                 init/rebudget/repair/tool-dispatch/code-eval
                                 actions, derived-agent dispatch helper.
      react_agent.clj            ReAct BT, three signatures (multi + single +
                                 FinalizeAnswer), thinking-loop subtree,
                                 tool-calls-action.
      context_actions.clj        prepare-conversation-action,
                                 prepare-recalled-memory-action.
      previous_turns.clj         append-turn + progressive compression.
      compaction.clj             needs-compaction?, compact-context,
                                 compact-iterations (LLM-driven).
      compaction_action.clj      BT wrappers: compaction-action,
                                 compact-iterations-action.
      context_compaction.clj     cross-turn /compact command path.
    core/
      context.clj                assemble-field, build-recall-query,
                                 extract-parent-context.
      context_budget.clj         model->budget, enforce, default-section-policies,
                                 estimate-tokens, total-tokens, compose.
      memory.clj                 recall, remember (layer-aware).
      agent.clj                  ask (per-turn entry; bumps turn-id / total-turns).
      tool.clj                   call-tool (with hooks, permission, depth guards).

components/behavior-tree/
  src/ai/brainyard/behavior_tree/core/
    dspy_action.clj              bt/dspy action: build-system-prompt, stable-keys
                                 split, predict / chain-of-thought dispatch.

components/clj-llm/
  src/ai/brainyard/clj_llm/core/
    prompt.clj                   build-messages, build-messages-with-breakdown
                                 (DSPy-style assembly).
    predict.clj                  predict (with input-token-breakdown merge).
    chain_of_thought.clj         chain-of-thought (CoT inputs/outputs).
    usage.clj                    build-token-breakdown, build-token-group.

components/memory/
  src/ai/brainyard/memory/...    IMemoryStore protocol, L1/L2/L3 stores,
                                 contextual-recall, RRF ranking.
```

## 13. Appendix B — Section Priority Reference (current)

For convenience, the full priority table from
`agent.core.context-budget/default-section-policies`. Lower priority compacts
first.

| Section | Priority | Compact strategy | Owner |
|---------|----------|------------------|-------|
| `:role` | 100 | (none) | CoAct system (above cross-turn breakpoint) |
| `:footer` | 100 | (none) | CoAct system (above cross-turn breakpoint) |
| `:system-info` | 98 | (none — small, immutable; **proposed P4**) | CoAct system (above cross-turn breakpoint) |
| `:execution-model` | 99 | (none) | CoAct system (above cross-turn breakpoint) |
| `:channel-routing` | 99 | (none) | CoAct system |
| `:tool-call-format` | 99 | (none) | CoAct system |
| `:code-blocks-format` | 99 | (none) | CoAct system |
| `:sandbox-context-accessor` | 95 | (none) | CoAct system |
| `:critical-rules` | 95 | (none) | CoAct system |
| `:instruction` | 95 | (none) | CoAct system |
| `:tools` | 90 | (none — proposed P2.3 adds tiered strategies) | CoAct system |
| `:large-results-playbook` | 90 | (none) | CoAct system |
| `:agent-context` | 90 | (none) | CoAct system |
| `:project-instructions` | 88 | (none) | **CoAct system** (above cross-turn breakpoint; **proposed P4 move**) |
| `:user-instructions` | 88 | (none) | **CoAct system** (above cross-turn breakpoint; **proposed P4 move**) |
| `:turn-info` | 88 | (none — small, immutable; **proposed P4**) | CoAct user (below cross-turn breakpoint) |
| `:live-artifacts` | 70 | `:drop-live-artifacts` | CoAct user (below cross-turn breakpoint) |
| `:conversation-history` | 60 | `:shrink-conversation` | CoAct user (below cross-turn breakpoint) |
| `:previous-turns` | 50 | `:bump-previous-turns` | CoAct user (below cross-turn breakpoint) |
