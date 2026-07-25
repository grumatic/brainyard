# Cold-Start Optimization — `bb tui` JVM startup (design / research record)

> **Status:** Research record + cautionary tale. Two lazy-load levers were
> attempted and **reverted** (`c33aeb9`) because they **broke `bb tui`** and
> their measured "wins" were a **measurement artifact**. The one real,
> correctness-preserving lever is **opt-in AOT** (`961a0f0`, `BY_AOT=1`, ~2.3s).
> Honest bottom line: **JVM cold-start floor is ~7s from source / ~2.3s with
> AOT; instant start is the native `by` binary (0.015s).**
>
> The load-bearing lesson: **measure a *booted TUI*, not `require main` /
> `--help`.** The headless metric doesn't create an agent, so it silently
> measured a configuration that can't actually run. Derived by the research-agent
> (threads `research-bb-tui-cold-start-2-5s-get-under-1-0s`,
> `profile-lazy-load-memory-subsystem`); code is ground truth.

---

## 1. The premise was wrong: it's ~7.1s, not ~2.5s

The investigation opened on a reported "~2.5s". Measuring first put it at
**7.10s** and localized it precisely:

| Phase | Time | Share |
|---|---|---|
| babashka floor (`bb -e nil`) | 0.013–0.016s | ~0.2% |
| classpath resolve (`clojure -Spath`) | ~0.018s | ~0.3% |
| JVM + Clojure boot (`clojure -M -e nil`) | ~0.32s | ~4.5% |
| **require of `agent-tui-app.main`** | **~7.18s** | **~97%** |
| native `by --version` (floor) | 0.015s | — |

Cold-start is almost entirely **namespace loading / source compilation** on the
require path — not babashka, the CLI, or tools.deps.

## 2. The load-bearing lesson: measure the booted product

`require main` and `-m main --help` were used as the metric. **They never create
an agent**, so they never load the agent roster — and with the roster absent, the
framework files that transitively pull the memory subsystem
(`common/tools.clj`, `common/commands.clj`, `common/context_actions.clj`) also
don't load. The metric therefore measured a configuration that **cannot boot a
TUI**. Every "win" below was really "how fast does main compile when you *don't*
load the things a session needs" — which is not the question.

**This was only caught by booting `bb tui` in tmux.** Do that for any
startup-path change; the headless number lies.

## 3. Attempted lever A — split the eager agent roster (reverted)

`ai.brainyard.agent.interface` eager-`:require`d all 26 defagent namespaces
(~5.2s isolated, 73% of the load). The `magenta-fish-5213` plan moved them to
`agents_eager.clj`, force-included from a new native-only `native_main.clj`, and
**step 5 was to drop the requires "in favour of a lazy registry manifest."**

**The drop landed; the manifest never did.** There is no lazy-agent-ns resolution
in the code — so on the JVM path **no defagent registered**, and creating the root
coact-agent crashed:

```
Could not create TUI agent for 'coact-agent': coact-agent tool is not registered!
```

The native `by` binary was fine (force-include), which is exactly why the
headless measurement missed a total breakage of the interactive TUI.

## 4. Attempted lever B — lazy-load the memory subsystem (reverted)

`ai.brainyard.memory.interface` (graph.clj + embed.clj compilation) measured
2.70s isolated. It was reached on cold-start via `agent.interface`'s
`export-symbols` of the memory-agent hooks. Lever B lazified `main.clj` +
`memory_agent/{hooks,commands}.clj` + the agent core behind a `resolve-mem`
cached `requiring-resolve`, and force-included memory in `native_main.clj`.

Measured 6.18s → 5.02s require-main — **but only because lever A had already
removed the roster** (so the memory-pulling framework files weren't loading
either). Layered on a broken config, it "measured" a second artifact.

## 5. Why the wins evaporate on a working TUI

| Config | require-main | roster loads | memory loads | `bb tui` boots |
|---|---:|---|---|---|
| Original (pre-split) | ~7.18s | yes | yes | ✅ |
| + agent-split (`d0f9400`) | 6.30s | **no** | no | ❌ crash |
| + memory-split (`667fd52`) | 5.02s | **no** | no | ❌ crash |
| roster re-loaded (either fix, or **revert**) | **~6.9–7.3s** | yes | yes | ✅ |

A *working* TUI must load the agent roster (main-agent routes to every
specialist) and, through the framework, the memory subsystem. Loading them puts
require-main back at ~7s. The "−30%" was removed functionality, not deferred work.

## 6. The pattern is sound — but incomplete without a lazy registry

The two-path split itself is a legitimate technique:

- **Native force-include** — a static `:require` in `native_main.clj` keeps the
  subsystem reachable for the GraalVM analyzer.
- **JVM lazy resolve** — drop the eager `:require`; resolve on first use via a
  cached `requiring-resolve` (mirroring `analytics/core.persistence`
  `resolve-mem-fn` and `clj-llm/bedrock` `safe-require-resolve`).

But it is only *correct* if the deferred thing has a **lazy loader on the use
path**. For agents that means a `{agent-id → ns}` registry that
`setup-agent-by-id` / `call-tool` resolves on demand — the manifest step 5
promised and never delivered. Without it, "lazy" is just "absent."

And even done correctly, the payoff is small: the 26 agents' *marginal* cost was
<1s (their transitive deps are shared with the rest of the app), and a booted
session's root + routed specialists load most of them anyway. **Isolated require
cost is an upper bound, not the marginal saving** — the central measurement error
this whole effort illustrates, twice.

## 7. The one real lever: opt-in AOT (`961a0f0`)

AOT compiles whatever loads — roster, memory, framework — so it is a *correct*
speedup, not a functionality-removal:

| Lever | require-main | e2e `--help` |
|---|---|---|
| baseline (source) | ~7.10s | ~7.20s |
| **AOT (`-M:aot-dev`)** | **~2.30s** | **~2.35s** |
| `-XX:TieredStopAtLevel=1` | ~10.6s | — (harmful, +48%) |

AOT can't be the dev default (the JVM prefers stale `.class` over fresh `.clj`;
default-on would run stale code + force 40–60s recompiles). So it's opt-in:

- `BY_AOT=1` (or `--aot`) → `bb tui` uses `-M:aot-dev`; unset → source load.
- `bb aot:ensure` — a **freshness gate** (NO-OP unless `BY_AOT=1`) comparing a
  source fingerprint to `target/classes/.aot-stamp`, recompiling on mismatch.
- `scripts/bench-cold-start.sh` — the measurement harness.

## 8. GraalVM native constraints (gate every lever)

- `defonce` **bakes build-time state** into the image.
- Eager value-copy `(def x alias/x)` **freezes an unbound fn** under native-image;
  use `#'alias/x` (`reference_native_image_value_copy_unbound`).
- `proxy` / `defrecord` need reflect-config.
- Any dropped `:require` must be re-added to `native_main.clj` or the subsystem is
  stripped from `by` — **and** paired with a JVM-side lazy loader, or the JVM path
  breaks (§3).

## 9. Conclusion & recommendation

- **Two lazy-load levers reverted** (`c33aeb9`); they broke `bb tui` for artifact
  wins. `bb tui` boots again at the ~7.18s working baseline (verified in tmux).
- **AOT (`961a0f0`) kept** — the real lever (~2.3s), correctness-preserving.
- **`<1.0s` is not achievable on the JVM.** The honest target: "~7s from source,
  ~2.3s with `BY_AOT=1`, and the native `by` (0.015s) for instant."
- **If pushing further:** the only correct next step is a genuine lazy-agent
  registry (§6) *plus* lazifying the memory-pulling framework files — for a
  realistic <1s time-to-first-prompt, at meaningful complexity and risk. The
  high-leverage investment is keeping the native `by` fast and reachable, not
  shaving the dev JVM path toward a target AOT itself can't hit.

## 10. References

- Commits: `961a0f0` (AOT opt-in + freshness gate + bench — kept), `d0f9400`
  (agent split — reverted), `667fd52` (memory split — reverted), `c33aeb9`
  (the revert of both).
- Build: `bb.edn` (`tui` task `BY_AOT` branch, `aot:ensure`),
  `projects/agent-tui-app/deps.edn` (`:aot-dev`), `scripts/bench-cold-start.sh`.
- Pattern precedents: `components/analytics/.../core/persistence.clj`
  (`resolve-mem-fn`), `components/clj-llm/.../core/bedrock.clj`
  (`safe-require-resolve`).
- Native-image rules: `reference_native_image_value_copy_unbound` and the GraalVM
  coding notes.
