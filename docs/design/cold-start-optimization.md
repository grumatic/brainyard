# Cold-Start Optimization — `bb tui` JVM startup (design / research record)

> **Status:** Design-of-record + measured research log. Two levers landed
> (`961a0f0` AOT opt-in + `d0f9400` agent-split + `667fd52` memory-split);
> require-main went **7.18s → 6.30s → 5.02s (−30%)**. The honest bottom line:
> **<1.0s is unreachable on the JVM path** — the remaining ~5s is the
> irreducible framework core (TUI + agent runtime + clj-llm + SCI) needed to
> reach the first prompt. AOT floors at ~2.3s (opt-in via `BY_AOT=1`); the
> native `by` binary is **0.015s** and is the real answer for instant start.
>
> Derived bottom-up by the research-agent (threads
> `research-bb-tui-cold-start-2-5s-get-under-1-0s` and
> `profile-lazy-load-memory-subsystem`), code is ground truth. This doc is the
> durable summary; the per-iteration dossiers hold the raw trace.

---

## 1. The premise was wrong: it's ~7.1s, not ~2.5s

The investigation opened on a reported "~2.5s" cold-start. Measuring first
(`clojure -M -e "(time (require 'ai.brainyard.agent-tui-app.main))"`, median-of-5
over the ~0.32s JVM-boot floor) put the real figure at **7.10s**, and localized
it precisely:

| Phase | Time | Share |
|---|---|---|
| babashka floor (`bb -e nil`) | 0.013–0.016s | ~0.2% |
| classpath resolve (`clojure -Spath`) | ~0.018s | ~0.3% |
| JVM + Clojure boot (`clojure -M -e nil`) | ~0.32s | ~4.5% |
| **require of `agent-tui-app.main`** | **~7.18s** | **~97%** |
| native `by --version` (floor) | 0.015s | — |

Neither babashka, the `clojure` CLI, nor tools.deps is implicated. Cold-start is
**almost entirely namespace loading / source compilation** on the require path.
Measure-first killed a false premise before any code changed — the discipline
this whole effort rests on.

## 2. The reusable mechanism: force-include + soft-resolve

Every lever below is the same move, and it exists because of one hard constraint:

- The **native `by` image** must keep the full subsystem baked in (GraalVM does
  static reachability analysis at build time — a dropped `:require` strips the
  code from the binary).
- The **JVM dev path** (`bb tui` from source) pays the compile cost of every
  eager `:require` on the chain, whether or not the current invocation uses it.

So we split the two paths:

1. **Native force-include** — a static `:require` in
   `projects/agent-tui-app/src-native/.../native_main.clj` (the native-only entry
   that delegates to the JVM `-main`). This keeps the subsystem reachable for the
   GraalVM analyzer *without* putting it on the JVM require path.
2. **JVM lazy resolve** — drop the eager `:require` from the cold-start
   namespaces; resolve the symbols on first use via a cached `requiring-resolve`:

   ```clojure
   (def ^:private resolve-mem
     (memoize
      (fn [sym-name]
        (requiring-resolve
         (symbol "ai.brainyard.memory.interface" (name sym-name))))))
   ;; call site: (mem/get-stats mm)  →  ((resolve-mem 'get-stats) mm)
   ```

   This mirrors the pre-existing `analytics/core.persistence` `resolve-mem-fn`
   and `clj-llm`'s `bedrock/safe-require-resolve` (the AWS soft-dep). The compile
   moves from the require phase to first dispatch — off cold-start, onto a path
   that already pays LLM/DB latency.

## 3. Landed lever A — split the eager agent roster (`d0f9400`)

`ai.brainyard.agent.interface` used to side-effect-`:require` **all 26 defagent
namespaces** ("single source of truth: add a new agent here when it ships"),
which measured **~5.2s in isolation** — 73% of the load. Those requires moved to
`components/agent/src/ai/brainyard/agent/agents_eager.clj`, force-included from
`native_main.clj`; `interface.clj` keeps only the framework API.

**Result: 7.18s → 6.30s.** The projected ~5.2s saving **did not materialize** —
the real cut was **<1s**.

> **Lesson (the central one): isolated require cost is an UPPER BOUND, not the
> marginal saving.** The 26 agents share almost all their transitive deps
> (clj-llm, the agent core, SCI, the tool registry) with the rest of the app,
> which loads them anyway. Removing the agents' *own* code saved little; the
> shared chain dominated. This falsified the headline lever and reframed the
> whole problem.

## 4. Landed lever B — lazy-load the memory subsystem (`667fd52`)

`ai.brainyard.memory.interface` (graph.clj + embed.clj **compilation**, not eager
I/O — no SQLite conn/pragma/Model2Vec at require) measured **2.70s isolated**.

**The seam was not obvious.** The plan targeted the eager `:require` at
`main.clj:23`; removing it alone left memory still loaded (`:memory-loaded? true`,
require-main unchanged). The true chain was:

```
main → agent.interface  (export-symbols ai.brainyard.agent.common.memory-agent.hooks)
     → memory-agent.hooks + memory-agent.commands  → eager (:require memory.interface)
```

`export-symbols` requires the ns it re-exports, so the memory-agent hooks (wired
for the write-guard + consolidation cadence) transitively pulled memory in. The
fix lazified **all three** sites — `main.clj` (29 `mem/` sites),
`memory_agent/hooks.clj` (4), `memory_agent/commands.clj` (13) — plus the agent
core (`core/{agent,context,memory}.clj`, `interface.clj`), with a
`memory.interface` force-include added to `native_main.clj`.

**Result: 6.18s → 5.02s (−1.16s, ~19%).** Memory is off the cold-start chain
(`:mem false`), resolving on first `by memory` / recall/remember. Non-regression:
`by memory stats` reads the live store (639 episodes via SQLite-FTS5), `clj-kondo
errors:0`, `bb test` green.

> **Same lesson, again:** isolated 2.70s → marginal **1.16s**. Shared deps
> (sqlite-jdbc, next.jdbc, clj-llm, sci) were already on the chain.

## 5. AOT: a 3× win that still doesn't reach 1.0s — hence opt-in (`961a0f0`)

| Lever | require-main (median) | e2e `-m main --help` |
|---|---|---|
| baseline (source load) | ~7.10s | ~7.20s |
| **AOT (`-M:aot-dev`)** | **~2.30s** | **~2.35s** |
| `-XX:TieredStopAtLevel=1`, no AOT | ~10.6s | — |
| AOT + tiered1 | ~2.35s | ~2.39s |

- **AOT is the single biggest lever (−67%)** but stops at ~2.3s ≫ 1.0s.
- **`TieredStopAtLevel=1` is actively harmful (+48%)** — Clojure's load path
  needs C2. Do not ship it.

AOT can't be the dev default: `target/classes` already holds ~20k `.class`
files, and the JVM prefers stale `.class` over fresh `.clj`, so a default-on AOT
would silently run stale code after every edit — and force a 40–60s recompile on
the common edit-run loop. So AOT is **opt-in** (owner decision, Jake Na):

- `BY_AOT=1` (or `--aot`) flips `bb tui` to `-M:aot-dev`; unset → source load, no AOT.
- `bb aot:ensure` — a **freshness gate** (NO-OP unless `BY_AOT=1`) that compares a
  source fingerprint against `target/classes/.aot-stamp` and recompiles on
  mismatch, so opt-in AOT can never run stale.
- `scripts/bench-cold-start.sh` — the repeatable measurement harness.

## 6. What's left is the irreducible framework core

Post-memory-split isolated requires (median-3): `agent-tui.core` **4.66s**,
`agent.interface` 3.77s, `core.agent` 2.11s, `clj-llm.interface` 1.22s — and they
**overlap heavily**: `agent-tui.core` alone ≈ the whole 5.02s chain, because it
transitively pulls the agent framework + clj-llm + core.agent. The peripheral
eager-load levers (agents, memory) are **exhausted**; the remaining ~5s is the
TUI + agent runtime + clj-llm + SCI that the interactive session genuinely needs
to render the first prompt and run the first turn — not lazy-deferrable without
breaking the TUI.

The one remaining JVM candidate is **lazy-loading `clj-llm`** (~1.2s isolated,
realistically ~0.5–1s marginal) — higher risk, since `create-lm` is wired into
agent setup. Not yet attempted.

## 7. GraalVM native constraints (gate every lever)

- `defonce` **bakes build-time state** into the image — never hold cold-start
  state in a `defonce` that a lever touches.
- Eager value-copy `(def x alias/x)` **freezes an unbound fn** under native-image;
  use `#'alias/x` (see `reference_native_image_value_copy_unbound`).
- `proxy` / `defrecord` need reflect-config.
- Any dropped `:require` must be re-added to `native_main.clj` or the subsystem is
  stripped from `by`. Verify with a native force-include grep (a native build was
  not re-run per lever — the force-include mirrors the proven `agents_eager`
  pattern).

## 8. Conclusion & recommendation

- **Measured progress:** 7.18s → 6.30s → 5.02s require-main across two landed
  levers, plus opt-in AOT to ~2.3s.
- **`<1.0s` is not achievable on the JVM path.** The honest target is "~5.0s from
  source, ~2.3s with `BY_AOT=1`, and use the native `by` (0.015s) when you need
  instant." Chasing sub-second on the JVM fights the framework core the TUI can't
  run without.
- **If pushing further:** profile/attempt the `clj-llm`-lazy lever, but expect
  diminishing returns (~0.5–1s) against rising risk. The high-leverage
  investment is keeping the native `by` fast and easy to reach, not shaving the
  dev JVM path toward a target AOT itself can't hit.

## 9. References

- Commits: `961a0f0` (AOT opt-in + freshness gate + bench), `d0f9400` (agent
  split, `magenta-fish-5213`), `667fd52` (memory split, `maroon-eagle-5703`).
- Build: `bb.edn` (the `tui` task's `BY_AOT` branch L128–135, `aot:ensure`
  L608+), `projects/agent-tui-app/deps.edn` (`:aot-dev`), `scripts/bench-cold-start.sh`.
- Native entry: `projects/agent-tui-app/src-native/.../native_main.clj`
  (force-include site), `.../main.clj` (JVM entry).
- Split targets: `components/agent/src/ai/brainyard/agent/agents_eager.clj`,
  `.../agent/interface.clj`, `.../agent/core/{agent,context,memory}.clj`,
  `.../agent/common/memory_agent/{hooks,commands}.clj`.
- Pattern precedents: `components/analytics/.../core/persistence.clj`
  (`resolve-mem-fn`), `components/clj-llm/.../core/bedrock.clj`
  (`safe-require-resolve`).
- Native-image rules: `docs/…` GraalVM notes;
  `reference_native_image_value_copy_unbound`.
