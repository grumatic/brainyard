# Session Analytics — Trajectory-Sourced, On-Demand

**Status:** Proposed
**Author:** —
**Supersedes:** the *Analytics — PQS, waste, cost* section of
[`observability.md`](observability.md) (that section documents the current
per-turn design described below as "Current state").

---

## 1. Summary

Session analytics is reworked from a **per-turn, config-gated, push** pipeline
into an **on-demand, command-driven, pull** pipeline sourced from the session's
`trajectory.edn`.

Three changes:

1. **Source of truth becomes `trajectory.edn`.** Instead of re-deriving inputs
   from the live in-memory `messages` and a live `usage-tracker` atom at the end
   of each turn, the analyzers read the per-session append-only trajectory log,
   which already records every turn (all iterations, tools, tokens, cost, model,
   latency, outcome). This lets us analyze the **whole session up to date** in
   one pass, at any time, including after a restart.
2. **Trigger becomes an explicit command, `session$analytics`,** callable by the
   LLM (and surfaced as `/session$analytics` in the TUI). The async post-turn
   invocation in `core/bt.clj` and the `:enable-analytics` gate are removed.
3. **Scope is extended** beyond today's PQS + waste + cost to add iteration
   convergence, tool reliability, latency/throughput, cache efficiency, outcome
   quality, and a single composite Session Health Score.

The data we need is already on disk for free; this redesign stops paying the
per-turn analysis tax and turns analytics into something the agent (or user)
asks for when it's useful.

---

## 2. Motivation

The current design (`analyze-session` fired from `bt.clj`) has four problems:

- **Per-turn cost, mostly wasted.** Analytics runs after *every* turn when
  enabled, but the result is rarely looked at per turn. PQS and trends are only
  meaningful in aggregate, and the optional RLM refinement spends real tokens on
  output almost nobody reads.
- **Gated off by default.** `:enable-analytics` defaults to `false`, so in
  practice the feature is dark. A boolean a user has to discover and flip is a
  poor activation path; an explicit command the LLM can invoke when relevant is
  better.
- **Reconstructs inputs that already exist.** It pulls `messages` from
  `:!session` and usage from a live tracker atom, so it can only see *current*
  in-memory state. `trajectory.edn` already persists a richer, uncapped,
  per-turn record — including tool calls, per-channel iteration detail,
  `terminated-by`, `success`, and `duration-ms` — that the message/usage view
  doesn't expose.
- **Narrow scope.** PQS + waste + cost ignore signals the trajectory makes
  cheap to compute: how many iterations a turn took, whether tools failed,
  cache-hit rate, whether the turn actually achieved its goal.

---

## 3. Current state (what we're changing)

Components and call sites as they exist today:

| Concern | Location |
|---|---|
| Public API | `components/analytics/src/ai/brainyard/analytics/interface.clj` — `analyze-session`, `score-pqs`, `detect-waste`, `calculate-session-cost`, `persist-analytics!`, `get-session-analytics`, `get-analytics-trends`, `format-analytics` |
| PQS | `analytics/core/pqs.clj` — `score-pqs`, `score-pqs-heuristic`, `compute-adjustments` (5 dims: specificity 25 / task-atomicity 25 / context-completeness 20 / acceptance-criteria 20 / clarity 10) |
| Waste | `analytics/core/waste.clj` — `detect-all-waste` (7 detectors) |
| Cost | `analytics/core/cost.clj` — `session-cost-breakdown`, `estimate-optimal-cost`, `throughput-stats`, `calculate-session-cost`; `model-tiers`, `tier-rates {:opus 20.0 :sonnet 5.0 :haiku 0.80}` |
| Persistence | `analytics/core/persistence.clj` — `persist-analytics!`, `query-session-analytics`, `query-analytics-trends` (L3 memory facts) |
| Trigger | `components/agent/src/ai/brainyard/agent/core/bt.clj` — `run-analytics-async!` (≈L104) called post-BT (≈L161); soft-resolved via `!analyze-session-fn` delay |
| Gate | `agent/core/config.clj` — `:enable-analytics {:type "boolean" :default false}` (L162) |

`analyze-session` takes `{:session-id :user-id :messages :usage-tracker}` and
resolves `get-usage-summary` / `get-usage-history` from `clj-llm` via
`requiring-resolve`. It runs PQS over `messages`, waste over
`messages + usage-summary + usage-history`, and cost over the usage views, then
optionally persists to memory.

This API is **retained** — the analyzers are good and well-tested. What changes
is *how they're driven and what feeds them*.

---

## 4. The data source: `trajectory.edn`

Already implemented and wired in; no new capture work is required.

- **Writer:** `coact-store-results-action` in
  `agent/common/coact_agent.clj` appends one record per turn via
  `trajectory/build-turn-trajectory` → `trajectory/append-trajectory!`. It uses
  the **uncapped** `:trajectory-iterations` mirror (not the context-capped
  `:iterations`), so the record covers the full turn.
- **Gate:** `:enable-trajectory-recording {:type "boolean" :default true}`
  (`config.clj` L82). This stays — it is now the master switch for analytics
  data, and best-effort: a write failure never breaks a turn.
- **Reader / layout:** `agent/common/trajectory.clj` —
  `read-trajectories`, `latest-trajectory`, `session-trajectory-file`. One
  newline-delimited EDN record per line under
  `<project>/.brainyard/sessions/<session-id>/trajectory.edn`; corrupt tail lines are
  skipped on read.

### Record shape (schema `:v 2`)

```clojure
{:v 2 :ts <epoch-ms>
 :session "agt-…" :agent "…" :turn N
 :question "…" :answer "…"
 :success true|false :terminated-by :answer|:max-iterations|…
 :total-iterations N
 :iterations [{:n 1 :channel "code"|"tool"|"none" :thought "…"
               :code [..] :result [..] :output [..] :error [..]
               :tools [{:name "…" :args {…} :result "…"}] :async? true}]
 :model "…" :cost 0.0042
 :usage {:in N :out N :cache-read N :cache-write N}
 :duration-ms N}
```

### Why it is sufficient (and richer than the live view)

Every analyzer input is reconstructable from the record stream:

- **PQS** needs `{:role :content}` messages → project each turn's `:question`
  to a `user` message and `:answer` to an `assistant` message. PQS conversation
  adjustments (corrections, retry storms, one-turn completions) are computed
  *more reliably* from `:success`, `:terminated-by`, and `:total-iterations`
  than from message-text heuristics.
- **Cost** needs `usage-summary` + `usage-history` → synthesize a usage-history
  record per turn from `:usage`, `:cost`, `:model`, and `:duration-ms`
  (as `:latency-ms`); sum for the summary. Field mapping is the inverse of
  `usage->compact`: `:in → :input-tokens`, `:out → :output-tokens`,
  `:cache-read → :cache-read-tokens`, `:cache-write → :cache-write-tokens`.
- **Waste** needs messages + usage → both available above.
- **New metrics** (§6.3–§6.7) read `:iterations`, `:tools`, `:error`,
  `:terminated-by`, `:success`, `:cache-*`, `:duration-ms` directly — none of
  which the message/usage-tracker view surfaces.

---

## 5. The `session$analytics` command

A new LLM-callable command, registered with `defcommand` exactly like the
`task$*` commands (`agent/task/commands.clj` is the reference). It is the only
entry point that does session I/O; the analytics component stays I/O-free.

### Placement & registration

- New file: `components/analytics/src/ai/brainyard/analytics/commands.clj`,
  namespace `ai.brainyard.analytics.commands`, OR (to avoid analytics taking an
  agent dep) `components/agent/src/ai/brainyard/agent/common/analytics_commands.clj`.
  **Recommended:** the latter — the command needs `trajectory/read-trajectories`
  (agent component) and `proto/*current-agent*`, and the agent→analytics
  direction already exists as a soft dependency. Keep the *analyzers* in the
  analytics component pure; keep the *I/O + projection* in the agent command.
- Export a `analytics-commands [#'session$analytics]` vector and bind it into the
  agent tool set the same way `task-commands` is.

### Signature

```clojure
(defcommand session$analytics
  "Analyze this session from its trajectory log (all turns up to now) and
   report Prompt Quality, Token/Cost Efficiency, iteration convergence, tool
   reliability, latency, cache efficiency, outcome quality, and a composite
   Session Health Score. Reads <project>/.brainyard/sessions/<id>/trajectory.edn — no
   per-turn cost. Use when the user asks how the session is going, where time/
   tokens went, or how to prompt more efficiently."
  (fn [& {:keys [format deep persist trends]}] …)
  :input-schema
  [:map
   [:format  {:optional true} [:enum {:desc "summary (default) | full | raw"} "summary" "full" "raw"]]
   [:deep    {:optional true} [:boolean {:desc "Enable LLM-enhanced (RLM) refinement of heuristic metrics. Costs tokens. Uses :analytics-lm-config (falling back to the agent :lm-config). Default false (heuristics only)."}]]
   [:persist {:optional true} [:boolean {:desc "Store this run as a coaching trend point in memory (default false). Independent of the report — the report is always returned."}]]
   [:trends  {:optional true} [:boolean {:desc "Include a coaching trend comparison against prior persisted runs (prompt-quality habits only — see §8). Requires prior persisted runs; implies :persist for this run. Default false."}]]]
  :output-schema
  [:map
   [:session-id   [:string]]
   [:turns        [:int {:desc "Turns analyzed"}]]
   [:health-score {:optional true} [:map {:desc "Composite Session Health Score + grade"}]]
   [:pqs          {:optional true} [:map]]
   [:cost         {:optional true} [:map]]
   [:iteration    {:optional true} [:map]]
   [:tools        {:optional true} [:map]]
   [:latency      {:optional true} [:map]]
   [:cache        {:optional true} [:map]]
   [:outcome      {:optional true} [:map]]
   [:waste        {:optional true} [:map]]
   [:summary      {:optional true} [:string {:desc "Human-readable formatted report"}]]
   [:trends       {:optional true} [:vector :any]]
   [:error        {:optional true} [:string]]])
```

### Control flow

```
session$analytics
  ├─ agent      = proto/*current-agent*
  ├─ session-id = proto/session-id agent
  ├─ records    = trajectory/read-trajectories session-id        ; nil/[] → {:error "no trajectory yet"}
  ├─ lm-config  = (when deep) (config/get-config agent :analytics-lm-config)
  │                           |> (or (config/get-config agent :lm-config))
  ├─ result     = analytics/analyze-trajectory records           ; ALL metrics, PURE, no I/O
  │                 :lm-config lm-config :skip-llm-analysis (not deep)
  ├─ (when (or persist trends)) analytics/persist-analytics! mm (assoc result :session-id … :user-id …)
  ├─ (when trends)              analytics/get-analytics-trends mm user-id :fact-type :pqs-score
  └─ format result per :format → :summary string
```

**Scope is always the whole session.** The command analyzes every turn in
`trajectory.edn` — there is no per-turn, last-n, or single-turn mode. Per-turn
analysis is explicitly out of scope (it was the failure mode of the old design);
the value here is the session-level rollup. A within-session per-turn PQS table
still appears in the `full` format (§7) for drill-down, but it is a *view* of the
one session-level run, not a separately requestable scope.

**All metrics are always computed.** There is no `:metrics` selector — the
analyzer produces the full set (PQS, TCE, ICE, TUR, LT, cache, OGA, waste, SHS)
in one pass. `:format` controls how much of it is *rendered*, not what is
computed.

The zero-arg call (`session$analytics`) does the useful thing: analyze the whole
session, heuristics only, `summary` format, no persistence. `:deep true` opts
into the LLM refinement pass; `:persist` / `:trends` are specified in §8.

---

## 6. Analytics scope & metrics

All metrics are computed from the projected trajectory record stream. Each turn
contributes one data point; session metrics aggregate across turns. Scores are
0–100 where a score is given, so they roll up into the composite cleanly.

### 6.0 New pure module

`components/analytics/src/ai/brainyard/analytics/core/trajectory.clj`
(namespace `ai.brainyard.analytics.core.trajectory`) — pure functions that take a
vector of `:v 2` turn records and return metric maps. No file I/O (the agent
command supplies the records). New `interface/analyze-trajectory` orchestrates:

```clojure
(defn analyze-trajectory
  "Run the full analyzer suite over a vector of trajectory turn records.
   Always computes all metrics. Returns the analytics result map.
   Pure — caller supplies records and (for :deep) the resolved lm-config."
  [turn-records & {:keys [lm-config skip-llm-analysis]
                   :or {skip-llm-analysis true}}] …)
```

There is no metric selector — the suite always runs in full. `skip-llm-analysis`
defaults to **true** (the point is cheap, on-demand heuristics). The command
resolves `:lm-config` from the new `:analytics-lm-config` config key (falling
back to the agent's `:lm-config`) and passes `:skip-llm-analysis false` only when
the caller sets `:deep true`. See §10.

### 6.1 PQS — Prompt Quality Score *(retained, extended)*

Unchanged 5-dimension heuristic (`pqs.clj`), but fed from trajectory `:question`
text per turn, and with **adjustments recomputed from trajectory signals**
instead of message-text inference:

- **Correction turns** — a turn whose `:question` semantically retries the prior
  turn (jaccard) *and* the prior turn had `:success false` or
  `:terminated-by :max-iterations`. More precise than today's message-only test.
- **One-turn completions** — `:total-iterations` small (≤ 2) *and* `:success
  true` *and* `:terminated-by :answer`.
- **Retry storms** — ≥ 3 jaccard-similar `:question`s in a window.

Output adds a `:per-turn` PQS breakdown keyed by `:turn` (a within-session
drill-down surfaced in `full` format) so the user can see which prompts dragged
the session average down. This is a *view* of the single session-level run, not a
per-turn analysis mode. Aim is **coaching** — PQS is the one metric whose trend
*across* sessions is meaningful (see §8), because prompting habits carry over
where cost/latency/outcome do not.

### 6.2 Token & Cost Efficiency (TCE) *(retained, extended)*

Reuses `cost/calculate-session-cost` (breakdown, optimal estimate, savings,
throughput) with usage-history synthesized from `:usage`/`:cost`/`:model`/
`:duration-ms`. Extended with:

- **Cache-hit rate** = `Σ cache-read / Σ (cache-read + in)` — prompt-cache
  effectiveness; low rate on a long session is a context-stability problem.
- **Cost per successful turn** = `Σ cost / (count turns with :success true)` —
  normalizes spend by useful output, not raw turns.
- **Output share** = `Σ out / Σ (in + out)` — input-heavy sessions signal
  context bloat (cross-checks the waste detectors).
- **Efficiency score (0–100)** blends model right-sizing headroom
  (`savings-potential / actual-cost`), cache-hit rate, and output share.

### 6.3 Iteration / Convergence Efficiency (ICE) *(new)*

From `:total-iterations`, `:terminated-by`, `:success`:

- avg / p50 / p90 iterations per turn,
- one-shot rate (turns resolved in ≤ 2 iterations),
- `terminated-by` distribution (`:answer` vs `:max-iterations` vs other) — a
  high `:max-iterations` share means the agent is thrashing,
- refinement-loop count (synthetic `[evaluation]` iterations, see coact quality
  loop).
- **ICE score (0–100):** high when most turns converge quickly to `:answer`.

### 6.4 Tool Utilization & Reliability (TUR) *(new)*

From `:iterations[].tools[]` and `:iterations[].error`:

- total tool calls, calls per turn, unique tools, top-N by frequency,
- **tool error rate** = tool/iteration results that errored ÷ tool calls,
- redundant-call detection (same `:name` + `:args` repeated within a turn),
- channel mix (`code` vs `tool` vs `none`).
- **TUR score (0–100):** penalized by error rate and redundancy.

### 6.5 Latency & Throughput (LT) *(new)*

From `:duration-ms` and `:usage`:

- per-turn and total wall-clock, p50/p90 turn latency,
- output tokens/sec (reuses `throughput-stats`),
- slowest turns (so the user can see where wall-clock went).

### 6.6 Cache Efficiency *(new, surfaced from §6.2)*

Cache-hit rate, fresh-input vs cached-input token split, and the trend across
turns (is caching degrading as context churns?). Broken out because prompt-cache
behavior is the single biggest lever on cost for long sessions.

### 6.7 Outcome / Goal Achievement (OGA) *(new)*

From `:success` and `:terminated-by`:

- success rate, failed-turn list with `:terminated-by`,
- "stuck" detection — consecutive failed/`:max-iterations` turns.
- **OGA score (0–100):** session-level success rate, weighted to penalize
  trailing failures (a session that ends broken scores worse than one that
  recovered).

### 6.8 Waste detection *(retained)*

`detect-all-waste` runs heuristic-only by default over projected
messages+usage. The trajectory's per-iteration tool results make the
`token-leakage` / `unused-context` detectors more accurate, but those remain
opt-in (need `:lm-config`).

### 6.9 Composite — Session Health Score (SHS) *(new)*

A single 0–100 + letter grade rolling up the component scores, so the LLM can
answer "how's this session going?" in one number and then drill in:

```
SHS = 0.20·PQS + 0.20·TCE + 0.20·OGA + 0.15·ICE + 0.15·TUR + 0.10·LT
```

Weights are a starting point (config-overridable, see §10). PQS/TCE/OGA lead
because prompt quality, spend, and whether work actually got done are what users
care about most; ICE/TUR/LT are diagnostic levers behind them.

---

## 7. Output & formatting

`format-analytics` is extended (or a sibling `format-session-analytics` added)
to render the new sections. Three `:format` levels:

- **`summary`** (default) — SHS + grade, the six component scores on one line
  each, top 1–2 findings per section, and 2–3 prioritized recommendations.
  This is what the LLM gets back and relays to the user.
- **`full`** — every metric, per-turn PQS table, slowest turns, tool table.
- **`raw`** — the analytics map itself (for the LLM to post-process or for
  artifacts).

Example `summary`:

```
=== Session Health: 78/100 (B+) ===  (14 turns, $0.0421, 6m12s)
  Prompt Quality (PQS)      72/100   2 vague prompts dragged the avg
  Token/Cost Efficiency     81/100   cache-hit 64%, $0.0035/successful-turn
  Outcome (OGA)             86/100   13/14 turns succeeded
  Iteration Convergence     74/100   avg 3.1 iters; 2 turns hit max-iterations
  Tool Reliability          80/100   41 calls, 7% error rate, 1 redundant
  Latency                   —        p90 turn 38s; slowest: turn 9 (1m02s)
  Recommendations:
    - Turns 4 & 11 scored low on specificity — name files/functions explicitly.
    - 2 turns exhausted iterations on the same tool error — fix root cause.
    - Cache-hit fell from 80%→44% after turn 8; context is churning.
```

---

## 8. Persistence & trends

Unchanged storage layer (`persistence.clj`, L3 memory facts), now **pull-driven**.
The report is *always returned* to the caller regardless of these flags;
persistence and trends are purely about what gets recorded and compared.

### `:persist` (optional, default false)

`session$analytics :persist true` calls `persist-analytics!` to store this run as
a trend point. It is opt-in because the command can be run repeatedly within a
session — we don't want every ad-hoc analysis writing a fact. The intended
pattern is one persisted run near the end of a session (the LLM can do this on
its own, or the user can ask). With `:persist false` (default), the command is a
read-only report.

### `:trends` (optional, default false, requires persisted history)

`:trends true` includes a comparison against prior persisted runs. It is
**meaningless without persistence**: it reads the trend store, and a run that
isn't itself persisted can't be compared or contribute. So `:trends true`
**implies `:persist` for the current run** (the command persists, then compares),
and returns an empty/`"no prior runs"` trend section when no earlier runs exist.

### What trends can and cannot deliver

This is the important constraint. **Most session metrics are not comparable
across sessions** — a 3-turn bug-fix and a 40-turn research session differ in
kind, so comparing their cost, latency, iteration counts, outcome rate, or
composite SHS produces noise, not insight. The doc deliberately does **not**
offer "your SHS this session vs last" as a headline, because it would invite
false conclusions.

The trend that *is* meaningful is **prompt-quality coaching**: PQS measures the
user's prompting habits, which carry across sessions independent of the task.
Tracking PQS (and its dimension breakdown) over time answers a real question —
"am I getting better at writing prompts?" — in a way cost or latency cannot.
Accordingly, `get-analytics-trends` is called with `:fact-type :pqs-score`, and
the trend section reports only the PQS habit trajectory (overall + weakest
recurring dimension), explicitly **not** a cross-session comparison of
cost/latency/outcome/SHS.

The full per-session metric set is still *persisted* (for an external dashboard
or a user who explicitly wants the raw history), but the in-command trend view is
scoped to the one cross-session-meaningful signal.

Optionally, the schedule skill can run `session$analytics :persist true` as an
end-of-session rollup, but that wiring is out of scope here.

---

## 9. Removal / migration plan

1. **`bt.clj`** — delete `run-analytics-async!` and its call site (≈L161), and
   the `!analyze-session-fn` delay. The behavior tree no longer touches
   analytics. (Trajectory writing in `coact-store-results-action` is untouched.)
2. **`config.clj`** — remove `:enable-analytics` (L162). If backward-compat
   matters, keep the key as a documented **no-op** for one release with a
   deprecation note rather than erroring on unknown config. **Add**
   `:analytics-lm-config` to `config-schema` (§10) — previously referenced by
   `bt.clj` (L122) but never schema-backed; it is now the default LM for the
   `:deep` analysis pass.
3. **`observability.md`** — replace its analytics subsection with a pointer to
   this doc; note the trigger change (command, not per-turn).
4. **Keep** `:enable-trajectory-recording` — it is now the analytics data switch.
5. **No data migration** — existing `trajectory.edn` files are already `:v 2`
   and directly consumable.

Net deletion is small (one async helper + one config key); the analyzer code is
reused, and one pure module + one command are added.

---

## 10. Configuration

Replace the single boolean with analytics-as-data-plus-command. New/changed keys
in `agent/core/config.clj`:

- `:enable-trajectory-recording` (existing, default `true`) — master data switch.
- **`:analytics-lm-config` (new, optional, default `nil`)** — the LM config used
  for the LLM-enhanced (RLM) analysis pass when the command is called with
  `:deep true`. Added to `config-schema` (the old design referenced this key on
  agents but it was never schema-backed). Resolution order for the deep pass:

  ```
  :analytics-lm-config  →  (fallback) :lm-config  →  (else) heuristics only
  ```

  So `:deep true` always works (it falls back to the agent's main `:lm-config`),
  but operators can point analytics at a *cheaper* model than the agent's primary
  one by setting `:analytics-lm-config` explicitly — analysis shouldn't cost more
  than the work it analyzes. When `nil` and `:deep` is not set, no LM is used.

  Schema entry:

  ```clojure
  ;; LM config for the optional LLM-enhanced analytics pass (session$analytics :deep true).
  ;; nil → fall back to :lm-config when :deep is requested.
  :analytics-lm-config {:type "map" :default nil}
  ```

- `:analytics-shs-weights` (new, optional) — map overriding §6.9 weights, e.g.
  `{:pqs 0.2 :tce 0.2 :oga 0.2 :ice 0.15 :tur 0.15 :lt 0.10}`. Defaults baked in;
  must sum to 1.0 (validated, fall back to defaults on mismatch).
- No `:enable-analytics` — analysis is always available on demand; cost is paid
  only when the command runs (and only hits an LM under `:deep true`).

---

## 11. Testing

- **Pure analyzers** (`core/trajectory.clj`, `analyze-trajectory`) — table tests
  over hand-built `:v 2` record vectors: a clean session, a thrashing session
  (high `:max-iterations`), a tool-error-heavy session, a cache-degrading
  session; assert each component score moves in the expected direction and SHS
  rolls up correctly. No I/O, native-image-safe (mirrors `trajectory_test.clj`).
- **Projection** — round-trip: `build-turn-trajectory` output → projection →
  analyzer inputs; assert `usage->compact` field mapping inverts correctly.
- **Command** — bind `trajectory/*sessions-root*` to a temp dir (as the
  trajectory tests do), write a few records, invoke `session$analytics`; assert
  the full session is analyzed, `:error` on empty trajectory, `:deep true`
  resolves `:analytics-lm-config` (then falls back to `:lm-config`), and
  `:trends true` implies persistence and returns only the PQS habit trend.
- **Regression** — existing `analytics/interface_test.clj` and `pqs`/`cost`/
  `waste` tests stay green (the analyzers are unchanged).
- **Smoke** — after a real session, `by … then /session$analytics` returns a
  populated summary; verify no analytics runs fire during the turns themselves
  (the `bt.clj` path is gone).

---

## 12. Open questions

- **SHS when a component is N/A** (e.g. no tool calls → TUR undefined) —
  renormalize weights over present components, or hold TUR at a neutral 100. Lean
  toward renormalizing so SHS isn't inflated by absent signal.
- **Is even PQS comparable across sessions?** §8 keeps PQS as the one
  cross-session trend on the theory that prompting habits transfer. The weaker
  version of that claim: a one-line follow-up ("now do the same for Y") is a
  low-PQS prompt that is perfectly appropriate in context. Mitigation —
  trend the *session-median* PQS rather than the mean, and exclude turns flagged
  as one-turn completions, so legitimate terse follow-ups don't depress the
  coaching signal. Worth validating before shipping the trend view.
- **Cross-session vs per-session trajectory.** `trajectory.edn` is per session;
  the (PQS-only) trend comes from the memory trend store (§8), not by scanning
  every session's trajectory. Per-session metrics other than PQS are intentionally
  *not* compared across sessions (they aren't comparable).
- **Sub-agent trajectories.** Sub-agents share the session id, so their turns
  land in the same `trajectory.edn`. Decide whether `session$analytics` should
  segment by `:agent` (root vs sub-agent) in `full` format.
```