# Observability

> **Status: Shipped.** The three-layer (BT step → turn → session)
> observability stack is implemented across `components/agent`
> (`common/trace.clj`, `common/trajectory.clj`, `common/log.clj`,
> `common/evaluation.clj`, `core/hooks.clj`), `components/mulog`, and
> `components/analytics`. (The query module formerly named `turn_log.clj`
> now ships as `common/log.clj`.)

Observability in Brainyard is not a monitoring afterthought; it is a
first-class design pillar. Every BT tick is traced, every iteration is
recorded as a trajectory, every lifecycle event flows through a
typed hooks registry, every LLM call is a structured mulog event, and
every completed session can be scored. Together they provide a
three-layer view — **BT step → turn → session** — and they are the raw
inputs to analytics like PQS (Prompt Quality Score), waste detection,
and cost attribution.

Primary files:

- `components/agent/src/ai/brainyard/agent/common/{trace.clj, trajectory.clj, log.clj, evaluation.clj}` — agent-side emitters and queries.
- `components/agent/src/ai/brainyard/agent/core/hooks.clj` — typed event catalog and registry.
- `components/mulog/` — pluggable structured-event publishers (console, simple-file, elasticsearch, kafka, cloudwatch, slack, zipkin).
- `components/analytics/src/ai/brainyard/analytics/{interface.clj, core/{pqs, waste, cost, persistence, prompts}.clj}` — PQS, waste detectors, cost attribution.
- `bases/agent-tui/src/ai/brainyard/agent_tui/log.clj` — TUI-side publisher setup.

---

## The three layers

### 1. BT step — trace + hooks

`common/trace.clj` emits a trace entry every time a tracing-aware BT
node ticks: depth-indented, with node id, status
(`:success` / `:failure` / `:running`), and any debug payload from
st-memory. Traces are threaded into the session's `:data` stream so
the TUI can render them live (`:thinking` command).

Trace entries are lightweight: a handful of fields per node, meant
for human consumption. They are the lowest-fidelity, highest-frequency
signal.

For programmatic observation, `agent.core.hooks` fires typed events at
every BT boundary. Selected events:

| Event | Phase |
|---|---|
| `:agent.iteration/pre` / `/post` / `/exhausted` | BT main loop boundary |
| `:agent.dspy-action/pre` / `/chunk` / `/post` | Each DSPy LLM call (streaming chunks via `/chunk`) |
| `:agent.tool-calls/pre` / `/post` | A batch of tool calls in one iteration |
| `:agent.tool-use/pre` / `/post` | Each individual tool invocation (`/pre` is gated) |
| `:agent.code-eval/pre` / `/post` | CoAct code-block evaluation |
| `:agent.compaction/post` | Context compaction fired |
| `:agent.evaluation/started` / `/llm-calling` / `/done` / `/verdict` | Evaluation pipeline progress |

See [core/agent.md §Hooks](../core/agent.md) for the complete catalog.
Analytics observers, capture pipelines, and TUI renderers all subscribe
through the same API — there is no separate event bus.

### 2. Turn — trajectory + turn log

`common/trajectory.clj` produces a **structured per-iteration record**
suitable for replay and training-corpus export:

```clojure
{:iteration  3
 :code       "(map inc [1 2 3])"
 :output     "(2 3 4)"
 :reasoning  "need to increment each element"
 :cost       {:input-tokens 412 :output-tokens 87 :usd 0.0023}
 :model      "claude-sonnet-4-6"
 :timing     {:started-at … :ended-at … :duration-ms 1420}}
```

`build-trajectory` assembles per-iteration entries into a turn-level
record; `store-trajectory!` keeps an in-memory buffer plus optional
disk persistence under `.brainyard/<agent>-agent/trajectory/`. CoAct
calls `store-trajectory!` at the end of every turn (the
`coact-store-results-action` BT leaf).

`common/log.clj` provides queries against the mulog event store
on disk. Events are filtered by `:user-id`, `:session-id`, `:agent-id`,
and `:turn-id` (injected via mulog global / local context at turn
start). The current event allowlist focuses on the high-signal
turn-boundary events:

```clojure
#{:ai.brainyard.agent.common.coact-agent/coact-init
  :ai.brainyard.agent.common.coact-agent/store-results
  :ai.brainyard.agent.task.manager/task-execution
  :ai.brainyard.agent.common.trace/agent-conversation}
```

The TUI's history and usage commands are thin wrappers over these
queries.

### 3. Session — mulog stream + analytics

`components/mulog` is Brainyard's structured-logging layer. The TUI's
`bases/agent-tui/src/ai/brainyard/agent_tui/log.clj` wires two
publishers:

- **`start-file-publisher!`** — global file publisher. Writes ALL
  events to a single file (default
  `~/.brainyard/logs/agent-tui-app.log`, resolved via `tui-log/default-log-path`;
  falls back to `/tmp/agent-tui-app.log` when the user-scope dir can't
  be created).
- **`start-session-publisher!`** — per-session file publisher.
  Filters events whose `:session-id` matches and appends to
  `<session-dir>/app.log`. The tmux-based TUI's `/log toggle` pane
  tails this file so users see only their own session's events
  instead of every session's events interleaved.

Both wrap `mulog/make-pretty-file-publisher` (or
`make-fn-publisher`) so events land as pretty-printed EDN, one record
per blank-line-separated paragraph.

Other publishers ship in `components/mulog` and can be swapped in
without changing the emit sites: `:console`, `:elasticsearch`,
`:kafka`, `:cloudwatch`, `:slack`, `:zipkin`, plus a `:multi`
aggregator.

`common/evaluation.clj` provides DSPy signatures for the CoAct
quality-loop and react-agent finalize step:

- **`EvaluateAnswer`** — heuristic evaluation of an answer against
  a rubric.
- **`FinalizeAnswer`** — derive the user-facing answer from the
  iteration history.

These feed the CoAct refinement loop and the ReAct finalize node, and
emit `:agent.evaluation/*` hook events along the way.

---

## Analytics — PQS, waste, cost

`components/analytics` is the post-session analytics layer. Its public
API is:

```
(score-pqs                messages    & {:keys [...]})
(detect-waste             messages    & {:keys [usage-summary usage-history ...]})
(calculate-session-cost   usage-summary usage-history & {:keys [...]})
(analyze-session          {:session-id :user-id :messages :usage-tracker} &
                          {:keys [memory-manager persist lm-config skip-llm-analysis]})
(persist-analytics!       memory-manager analytics)
(get-session-analytics    memory-manager session-id)
(get-analytics-trends     memory-manager user-id)
(format-analytics         analytics)
```

The agent runtime invokes `analyze-session` from `core/bt.clj` as an
**async, generation-guarded** post-turn step when
`:enable-analytics` is set (on the config or runtime-config). Stale
writes from earlier turns are blocked by checking the BT generation
counter before publishing. Results are stamped onto
`st-memory :analytics` and fired through the
`:agent.analytics/post` hook for subscribers.

### PQS — Prompt Quality Score

A 0–100 score across five dimensions, computed over the user-prompt
portion of each turn (`analytics.core.pqs`):

| Dimension | Weight |
|---|---|
| Specificity | 25% |
| Task atomicity | 25% |
| Context completeness | 20% |
| Acceptance criteria | 20% |
| Clarity | 10% |

Two modes:

- **Heuristic-only** (default) — regex / NLP-based, zero LLM cost.
  Uses a vague-word stop-list, sentence-tokenization, jaccard
  similarity over word sets, and an acceptance-criteria pattern
  matcher.
- **LLM-enhanced** (opt-in) — refines heuristic scores via an RLM
  pass. Enabled via `:lm-config` on `analyze-session`. Skipped
  entirely when `:skip-llm-analysis true`.

The aim is **coaching** — surface prompts that score poorly so the
user can iterate on their own habits. Like code coverage, it is more
useful as a trend than as an absolute.

### Waste detection

Seven detectors in `analytics.core.waste`:

1. **Context bloat** — system prompt or conversation history pushing
   close to the window limit (`detect-context-bloat`).
2. **Model overkill** — using a top-tier model for O(1) tasks
   solvable by a cheaper tier (`detect-model-overkill`).
3. **Oversized system prompts** — large prompts where a small one
   would do (`detect-oversized-system-prompt`).
4. **Redundant requests** — repeated identical / near-identical
   queries without memoisation; jaccard similarity over user prompts
   (`detect-redundant-requests`).
5. **Token leakage** — tool results included verbatim in every
   iteration instead of being summarised (`detect-token-leakage`).
6. **Unused context** — inclusions that never surface in the answer
   (`detect-unused-context`).
7. **Output verbosity mismatch** — long answers to yes/no questions
   (`detect-output-verbosity-mismatch`).

`detect-all-waste` runs the relevant detectors against a completed
session's messages + usage stream. Detectors that need both heuristic
and RLM passes can run an LLM refinement (`run-rlm-detection`) when
the optional `:lm-config` is supplied; otherwise heuristics alone
emit findings.

### Cost attribution

`analytics.core.cost` produces two views:

- **Exact** — per-model token rate × tokens. Authoritative when using
  pay-per-token APIs.
- **Effective** — flat-rate plan utilisation (e.g. Claude
  subscription). Pro-rates the monthly plan cost across the session's
  share of usage.

Both flow into the TUI usage display and aggregate across sessions for
team analytics.

### Persistence

`analytics.core.persistence` writes the resulting analytics map into
the memory manager (see [core/memory.md](../core/memory.md)) so it
shows up under a session's audit and explain queries.
`get-session-analytics` and `get-analytics-trends` hydrate later for
dashboards / coaching panels.

---

## Where observability is consumed

```
            ┌────────────────────────────────┐
   TUI  ◄───┤ trace                          │ live BT rendering (:thinking)
            │ trajectory                     │ per-iteration details
            │ mulog (global / per-session)   │ structured events on disk
            │ analytics (PQS / waste / cost) │ post-session coaching
            │ evaluation signatures          │ CoAct refinement loop
            └────────────────────────────────┘
                         ▲
                         │ hooks + direct emits
                         │
            ┌────────────┴────────────┐
            │  Agent Runtime           │ fires events / writes traces
            │  (components/agent)      │   on every BT tick, every turn
            └─────────────────────────┘
```

Replay use-cases:

- `:thinking` renders a BT trace tree for the last turn.
- `bb tui ask -v` prints trajectory + timing inline.
- The research-agent and workflow-agent read trajectories + analytics
  out of `.brainyard/<agent>-agent/` to inform their next step.
- The web bridge (`agent-web-app`) can subscribe to mulog over
  WebSocket and render a live session explorer.

---

## mulog event conventions

A typical event:

```edn
{:mulog/event-name :ai.brainyard.agent.common.coact-agent/store-results
 :mulog/trace-id   #uuid "…"
 :mulog/timestamp  1744800000000
 :user-id          "cli-user"
 :session-id       "ask-1744800000000"
 :agent-id         :coact-agent/crimson-parrot-42
 :turn-id          3
 :iteration        2
 :model            "claude-sonnet-4-6"
 :provider         :claude-code
 :prompt-tokens    1243
 :completion-tokens 287
 :duration-ms      1420
 :cost-usd         0.0031}
```

Event names follow `:ai.brainyard.<component>.<ns>/<event>`. Trace-ids
let you correlate across components (an LLM call inside a tool call
inside a sub-agent turn inside a workflow stage). The agent runtime
sets mulog global context at turn start so `:user-id`, `:session-id`,
`:agent-id`, `:turn-id` are attached to every nested emit without
threading them by hand.

Publisher configuration lives in `bases/agent-tui/src/ai/brainyard/agent_tui/log.clj`:

```clojure
;; Global publisher — all events to one file
(mulog/start-publisher!
  {:type :inline
   :publisher (mulog/make-pretty-file-publisher log-path)})

;; Session publisher — filter on :session-id before writing
(mulog/start-publisher!
  {:type :inline
   :publisher (mulog/make-fn-publisher
                (fn [event]
                  (when (= session-id (:session-id event))
                    (append-to log-path (pretty-event-str event)))))})
```

Projects that need a different sink swap the publisher; the emit sites
do not change.

---

## File map

| File | Purpose |
|---|---|
| `agent/common/trace.clj` | Depth-indented BT traces into session thinking |
| `agent/common/trajectory.clj` | Per-iteration structured record + in-memory store + disk persistence |
| `agent/common/log.clj` | Queries over mulog events filtered by user / session / agent / turn |
| `agent/common/evaluation.clj` | `EvaluateAnswer`, `FinalizeAnswer` DSPy signatures |
| `agent/core/hooks.clj` | Typed event catalog + registry (subscription point) |
| `agent-tui/log.clj` | Global and per-session mulog file publishers |
| `mulog/interface.clj` | Pluggable publishers (console, file, elasticsearch, kafka, cloudwatch, slack, zipkin) |
| `analytics/interface.clj` | `analyze-session`, `score-pqs`, `detect-waste`, `calculate-session-cost`, persistence + formatting |
| `analytics/core/pqs.clj` | PQS heuristic + LLM-enhanced scoring |
| `analytics/core/waste.clj` | Seven waste-pattern detectors |
| `analytics/core/cost.clj` | Exact + effective cost attribution |
| `analytics/core/persistence.clj` | Persist / retrieve analytics through the memory manager |
| `analytics/core/prompts.clj` | DSPy prompts for LLM-enhanced analytics |

---

## See also

- [core/agent.md](../core/agent.md) — hooks catalog, agent registry,
  multi-agent coordination.
- [core/bt.md](../core/bt.md) — where trace entries come from.
- [core/reasoning.md](../core/reasoning.md) — CoAct's
  `store-results` step and the evaluation/refinement loop.
- [core/memory.md](../core/memory.md) — audit trail (`memory/explain`)
  for "why did the agent say that on turn 7?".

---

## Governance & roadmap (aspirational)

These are not yet implemented; the observability layer is designed to
be receptive when they land.

- **DLP / PII redaction** before publishing events outside the local log.
- **Policy engine** blocking queries that would exceed per-user budget
  caps.
- **Audit trail** for approval decisions (which user approved which
  tool call, when). Today this is partially covered by
  `:action-permissions` + mulog events; a dedicated query surface is
  outstanding.
- **RBAC** over provider / model selection.
