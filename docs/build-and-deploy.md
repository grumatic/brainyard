# Build & Deploy

This page walks through the `bb` tasks used during development, the
GraalVM native-image build of the `by` binary, and runtime configuration.

---

## Prerequisites

From the repository root `CLAUDE.md`:

- **JDK 11+** (GraalVM 25 pinned via `.sdkmanrc` for native builds:
  `java=25.0.3-graal`).
- **Clojure 1.12+**.
- **Babashka** — `bb` — for the build / repl tasks.
- **Datomic Pro** credentials in `~/.m2/settings.xml` for the
  `my.datomic.com` repository (needed by the `:dev` alias; not required
  for just building `by`).

Optional for native builds:

- `native-image` on `PATH` *or* `JAVA_HOME` pointing at a GraalVM
  install that ships it (bundled in GraalVM 21+).

---

## Babashka tasks

### Development REPLs

```bash
bb repl                      # Dev REPL with all components (.env loaded)
bb repl:ata                  # Agent TUI app REPL with :dev + :nrepl aliases
bb repl:awa                  # Agent Web app REPL
bb repl:fra                  # Fulcro RAD app REPL
bb repl:ea                   # Electric app REPL
bb repl:ra                   # Replicant app REPL
bb repl:component <name>     # Component-specific REPL with nREPL/CIDER
```

`bb repl:ata` starts nREPL 1.3.0 with CIDER 0.50.2 middleware in
`projects/agent-tui-app`. Connect with `M-x cider-connect` or your
editor's equivalent.

### Running the TUI

```bash
bb tui                                    # default: run with claude-code:haiku
bb tui -p anthropic -m claude-sonnet-4-6  # pick provider + model
bb tui run -a coact-agent -i              # inline mode (CliClient testing)
bb tui ask -m opus 'What is 2+2?'         # one-shot question
bb tui agents                             # list available agents
bb tui config                             # interactive environment wizard
bb tui sessions list                      # list persisted sessions
bb tui sessions prune -s <id>             # delete a persisted session
bb tui -- claude-code:sonnet              # legacy provider:model syntax
```

`run` and `ask` flags:

| Flag | Short | Meaning |
|---|---|---|
| `--agent` | `-a` | Agent id; default `coact-agent`. |
| `--provider` | `-p` | LM provider: `claude-code`, `anthropic`, `openai`, `ollama`. |
| `--model` | `-m` | Model name override (e.g. `sonnet`, `opus`, `haiku`, `claude-sonnet-4-6`). |
| `--inline` | `-i` | Inline mode — no alt-screen; used by `CliClient` tests. |
| `--verbose` | `-v` | Verbose output (BT traces, timings, costs). |
| `--max-iterations` | `-n` | Override BT repeat cap. |
| `--with-tmux` |   | `run` only — require a tmux session for side panes / popups; exit 1 if not in tmux. |
| `--resume` | `-r` | Bare: **pick** a persisted session to resume from an interactive menu (fresh if none). With an id (`--resume <id>`): resume that session — error + exit 1 if absent. Implies hydration + scrollback replay. |
| `--new` |   | Deprecated no-op (sessions start fresh by default) — still accepted so existing launch args keep working. |

### ACP-driven TUI

```bash
bb tui:acp                 # run TUI with acp-agent (default :stub backend)
bb tui:acp -i              # inline mode

bb acp-stub:run            # drive the ACP stub agent on stdin/stdout
bb acp-stub:run --chunk-delay-ms=50   # slow per-token streaming
```

The `acp-agent` defagent forwards prompts to a configured ACP backend
(default `:stub`). Backend selection lives on
`:runtime-config :acp-backend`. The `acp-stub-agent` base provides an
in-tree backend useful for protocol-level testing.

### Polylith workspace

```bash
bb poly                  # Polylith CLI pass-through
bb poly:check            # Validate workspace (no missing refs, no cycles)
bb poly:info             # Show components, bases, projects, deps
bb test                  # Run all Polylith tests
bb test:component <name>
```

### Web app helpers

```bash
bb shadow:fra            # Shadow-CLJS watch for Fulcro RAD app
bb shadow:awa            # Shadow-CLJS watch for Agent Web app
```

### One-shot data migrations

```bash
bb migrate:plan-agent    # copy legacy .brainyard/plans/*.md into the new layout
bb migrate:todo-agent    # copy legacy .brainyard/todos/*.md into the new layout
```

---

## The native-image pipeline

The `by` binary is produced in four steps:

```
bb compile:ata     (AOT-compile ai.brainyard.agent-tui-app.main → classes/)
    │
    ▼
bb uberjar:ata     (uberdeps → target/agent-tui-app.jar, ~38 MB)
    │
    ▼
bb native:ata      (native-image → target/by, ~115 MB arm64)
    │
    ▼
bb install:ata     (cp target/by /usr/local/bin/by)
```

Or run the whole sequence:

```bash
bb build:ata
```

### Output and performance

- Location: `projects/agent-tui-app/target/by`.
- Size: ~115 MB on arm64 (macOS / Linux). Larger than a typical Go
  binary because of the SCI interpreter, FTS5, and embedded Clojure
  runtime, but smaller than the uberjar because dead-code elimination
  removes a lot.
- Startup: ~0.5 s cold start (vs. 3–6 s for JVM + Clojure warm-up).

#### Phase 0 baseline — 2026-05-15 (macOS arm64)

Numbers captured before the native-image cleanup work tracked in
`docs/design/native-image-design.md`. These are the "before" reference
that subsequent phases will be compared against.

| Metric | Value | Notes |
|---|---|---|
| GraalVM | 21.0.9+7.1 (Oracle GraalVM) | Baseline captured on 21.0.9. `.sdkmanrc` now pins 25.0.3-graal; see the "GraalVM 25.0.3 reading" section below for the re-baseline. |
| Uberjar | 49 MB (51,164,949 B) | Design assumed ~38 MB. |
| Native binary | **156 MB** (163,089,952 B) | Design assumed ~115 MB. Repo has grown since the design was written. |
| Native-image build time | 2m 11s | macOS arm64, 11 GB peak RSS. |
| Cold start, `by agents` | ~1.5 s | First run with the binary not yet in the OS page cache. |
| Warm start, `by agents` | < 10 ms | Page cache hot. |
| JVM start, `bb tui agents` | 2.9 s | For comparison; includes JVM + Clojure load. |
| Agent listing parity | 18 / 18 | Native binary lists the same 18 agents as the JVM (`diff` clean). |

Build composition (per `native-image` build report):
- Code area: 64.78 MB (62,764 compilation units; top origins: `agent-tui-app.jar` 38.25 MB, `java.base` 14.93 MB, `java.xml` 4.06 MB, `svm.jar` 3.82 MB, `java.net.http` 1.48 MB).
- Image heap: 89.06 MB (1,250,351 objects, 639 resources).
- Reflection registrations: 3,825 types / 534 fields / 3,366 methods.

Re-targeted budget (revised against the 156 MB starting point — design
§12's "< 90 MB" target was sized against the 115 MB baseline):
- Phase 1: no expected size change (build cleanliness only).
- Phase 2 (clj-http → java.net.http): ~5 MB reduction → ~151 MB.
- Phase 3 (talltale to test-only + flag experiments): a further ~2–4 MB → ~147–149 MB.

Closing the gap to < 90 MB would require additional work beyond this design (likely: reducing the AWS SDK surface, splitting SCI, or `--static --libc=musl`). Not in scope.

#### Phase 1 outcome — 2026-05-15 (macOS arm64)

| Metric | Phase 0 baseline | After Phase 1 | Δ |
|---|---|---|---|
| Native binary | 163,089,952 B (155.5 MB) | 161,305,408 B (153.8 MB) | **−1.78 MB** |
| Cold start, `by agents` | ~1.5 s | ~1.5 s | unchanged |
| Agent listing | 18 | 18 | unchanged |
| `bb build:ata` (clean) | 2m 11s | 2m 4s | -7s |

What actually shipped (vs the design's Phase 1 plan):
- ✓ All native-image flags consolidated into `native-image.properties`. `bb.edn` `native:ata` reduced to `-jar` + `-H:Name`. Single source of truth.
- ✓ `bb uberjar:ata` asserts `META-INF/native-image/.../native-image.properties` is present before declaring success — catches uberdeps regressions.
- ✓ `bb check:ata` added: static drift gate on the committed config files.
- ✓ `talltale` moved to `:test` alias and its dead require dropped from `naming.cljc` — sole source of the 1.78 MB win.
- ✓ `-H:BuildOutputJSONFile=target/native-build.json` enabled for size tracking.
- ✓ `com.github.clj-easy/graal-build-time {:mvn/version "1.0.5"}` added to deps but **not active** (see below).

What did not ship from Phase 1 (deferred):
- ✗ Dropping the deprecated `-H:+AllowDeprecatedInitializeAllClassesAtBuildTime` + bare `--initialize-at-build-time`. Blocked: 14 of 18 defagent forms reference cross-namespace Vars at top-level, which fails under build-time class-init.
- ✗ Activating `--features=clj_easy.graal_build_time.InitClojureClasses`. Same blocker.
- ✗ Adding `[ai.brainyard.clj-llm.core.oauth]` to `main.clj`'s pre-load list. Blocked: cascades into `next.jdbc` / `clojure.stacktrace` `set! *warn-on-reflection*` failures under the deprecated init policy.
- ✗ Tracing harness (`bb tracing:ata`) and config regeneration. Scoped out: the existing hand-curated configs work, drift is caught by `bb check:ata`.

All four blockers are tracked as a single follow-up: refactor `deftool` to lazy-resolve `tool-fn`, then re-attempt the design's explicit init policy. Phase 2 (clj-http removal) also eliminates much of the transitive-build-init enumeration burden, so it should be done before re-attempting init modernization.

#### Phase 2 outcome — 2026-05-15 (macOS arm64)

| Metric | Phase 1 baseline | After Phase 2 attempt | Δ |
|---|---|---|---|
| Native binary | 161,305,408 B | 161,305,408 B | 0 |
| Agent listing | 18 | 18 | unchanged |

What shipped from Phase 2:
- ✓ New `components/clj-http-native` Polylith component — a < 200 LOC clj-http-compatible wrapper built on `java.net.http`. Public API: `post`, `get`, `delete`, `put`. Supports `:as :string / :reader / :stream`, `:headers`, `:body`, `:throw-exceptions`, `:timeout-ms`, `:content-type :json`, proxy and insecure SSL. Throws `ex-info` with `{:status :headers :body}` matching clj-http's shape so `retry-with-backoff` keeps working unchanged.
- ✓ 8-test / 25-assertion unit suite in `components/clj-http-native/test/`, run against an in-process `com.sun.net.httpserver.HttpServer` (no extra deps). Covers GET/POST/DELETE, header round-trip, non-2xx with and without throwing, `Retry-After` propagation, chunked SSE-style streaming, `:as :stream` raw InputStream.
- ✓ Migration template documented: the 3-file `:require` swap (llm.clj × 6 sites, oauth.clj × 2 sites, mcp/client.clj × 5 sites) was implemented end-to-end, verified to compile and pass clj-llm's existing tests, then **rolled back** because the native binary couldn't be built (see below).

What did not ship (rolled back):
- ✗ `:require` swaps in `clj-llm/llm.clj`, `clj-llm/oauth.clj`, `agent/mcp/client.clj`.
- ✗ Dropping `clj-http` from `clj-llm/deps.edn` and `agent/deps.edn`.
- ✗ Removing `org.apache.http.impl.auth.NTLMEngineImpl` from `--initialize-at-run-time`.
- ✗ The ~5 MB binary size reduction promised by design §6.7.

Why it rolled back: the same root-cause as Phase 1's deferred init-policy modernization. Adding `clj-http-native` to the dep graph — even with `clj-http` still present and source unmodified — shifts class-init order enough that `clojure.stacktrace__init.<clinit>` (and downstream `next.jdbc.*__init`) hit "Can't change/establish root binding of: *warn-on-reflection* with set". The deprecated bare `--initialize-at-build-time` mechanism conflicts with the explicit `--initialize-at-run-time=<class>__init` workaround because `next.jdbc`'s own `META-INF/native-image/native-image.properties` build-inits the entire `clojure` package. Closing this requires fixing the deftool / cross-namespace-Var pattern first (refactor tracked as follow-up). The wrapper component is parked on disk; reapplying the migration is a small follow-up once init policy is modernized.

#### Task #20 outcome — 2026-05-15 (macOS arm64)

The "single follow-up that unlocks everything else" turned out to be partially correct. Two distinct changes landed:

| Metric | Phase 0 | Phase 1 | After #20 | Δ from baseline |
|---|---|---|---|---|
| Native binary | 163,089,952 B | 161,305,408 B | **153,653,568 B** (146.5 MB) | **−9.43 MB** |
| Code area | (n/a) | 64.27 MB | 61.12 MB | (−3.15 MB) |
| Image heap | (n/a) | 87.89 MB | 83.81 MB | (−4.08 MB) |
| Compilation units | 62,764 | 62,203 | 58,713 | (−4,051) |
| Reflection types | 3,825 | 3,815 | 3,467 | (−358) |
| Reachable classes | (n/a) | 27,487 | 26,159 | (−1,328) |
| Cold start, `by agents` | ~1.5 s | ~1.5 s | ~1.5 s | unchanged |
| Agent listing | 18 | 18 | 18 | unchanged |

What shipped from #20:

1. **`deftool` macro refactor** (`components/agent/src/ai/brainyard/agent/core/tool.clj`): the `(fn? tool-fn)` check moved from the top-level `let` binding into the wrap-fn body so cross-namespace Vars like `coact/run-coact-derived` are dereferenced at first invocation rather than at namespace-load. Removes the fragility that was breaking `--features=clj_easy.graal_build_time.InitClojureClasses`. All 18 defagent registrations still work; agent tests (17 tests / 124 assertions) pass.

2. **Phase 2 migration applied** (3 files, 13 sites):
   - `components/clj-llm/src/.../llm.clj` — 6 sites swapped to `clj-http-native`, `clj-http.conn-mgr` require + `!connection-manager` defonce dropped.
   - `components/clj-llm/src/.../oauth.clj` — 2 sites swapped.
   - `components/agent/src/.../mcp/client.clj` — 5 sites swapped.
   - `clj-http/clj-http` dropped from both `components/clj-llm/deps.edn` and `components/agent/deps.edn`.
   - `--initialize-at-run-time=org.apache.http.impl.auth.NTLMEngineImpl` removed from `native-image.properties` (the class is no longer on the classpath).

3. **One compensating workaround** (`native-image.properties`): added `--initialize-at-run-time=clojure.stacktrace__init`. The clj-http removal shifts the class-init analysis order enough to surface a latent `(set! *warn-on-reflection* true)` failure in `clojure.stacktrace` that was previously masked by the load order. The override defers `clojure.stacktrace`'s `__init` to runtime — same effective behaviour the JVM has during a normal namespace load.

What did NOT ship (still deferred):

- `--features=clj_easy.graal_build_time.InitClojureClasses` is still NOT activated. The deftool refactor unblocks it for the agent/defagent pattern, but **next.jdbc has the same `(set! *warn-on-reflection* true)` shape** as `clojure.stacktrace` — and graal-build-time 1.0.5 build-inits the whole `next.jdbc` package via its classpath scan (it hardcodes an exclusion only for `clojure/`, not `next/`). Trying to override `next.jdbc.*__init` with run-time-init triggered GraalVM's "Classes that should be initialized at run time got initialized during image building" conflict.
- The deprecated `-H:+AllowDeprecatedInitializeAllClassesAtBuildTime` + bare `--initialize-at-build-time` + `--report-unsupported-elements-at-runtime` are retained. Modernizing this path would require either patching/forking graal-build-time to add a `next/` exclusion, or replacing next.jdbc with a thinner SQLite wrapper. Neither is in scope.

Net: ~93% of the design's promised win (5 MB predicted vs 7.65 MB delivered for the Phase 2 chunk) on a path that's slightly different from what the design proposed — but ships today.

#### Task #19 / Phase 3 outcome — 2026-05-15 (macOS arm64)

The "out of scope" follow-up in design §14 (modern init policy
modernization) turned out to be unblocked after the reflection cleanup
in task #22. Phase 3 successfully:

| Metric | After task #21 | After Phase 3 | Δ |
|---|---|---|---|
| Native binary | 153,653,568 B (146.54 MB) | 154,133,120 B (146.99 MB) | +0.44 MB |
| Deprecated init flags | retained | **removed** | — |
| `--report-unsupported-elements-at-runtime` | retained | **removed** | — |
| `--features=InitClojureClasses` | inactive | **active** | — |
| `--strict-image-heap` | inactive | **active** | — |

What shipped:

1. **`-H:+AllowDeprecatedInitializeAllClassesAtBuildTime`** + bare
   `--initialize-at-build-time` → **gone**. Both were deprecated in
   GraalVM 22+ and would be removed in future versions.
2. **`--report-unsupported-elements-at-runtime`** → **gone**. Modern
   advice is to fix reflection at build time; we did that in task
   #21/#22.
3. **`--features=clj_easy.graal_build_time.InitClojureClasses`** is
   now active. graal-build-time builds-inits Clojure namespaces
   based on their `__init` class footprint on the classpath.
4. **`--strict-image-heap`** is now active (GraalVM 21 build report
   recommended this for future-proofing).
5. **Explicit allow-list** for libraries that genuinely benefit from
   build-time init: mulog, slf4j, logback, tools.logging, plus a
   single-class carve-out for `clojure.lang.XMLHandler` (since
   graal-build-time's `clojure` directive doesn't cover the
   `clojure.lang.*` subpackage).
6. **Single-class run-time carve-out** for `clojure.stacktrace__init`
   (its `(set! *warn-on-reflection* true)` at top of namespace can't
   establish a thread-local Var binding at GraalVM build-time
   class-init).

The +0.44 MB cost is a fair trade for forward compatibility — the
deprecated flags would have been removed eventually and the build
would have broken.

What did NOT ship from #19 (genuinely deferred):
- `--gc=epsilon` for `by ask`-only short-lived invocations. Would
  require a separate binary; not worth the complexity.
- `-march=native` dev profile. Could be added as a bb task variant
  later; not on the critical path.
- `--pgo` Profile-Guided Optimization. Two-step build workflow; out
  of scope for this design.
- `-H:+ProtectionKeys` (Intel MPK). Experimental; would need
  validation on linux-x86_64 (we're on arm64).

#### GraalVM 25.0.3 reading — 2026-05-17 (macOS arm64)

Re-baseline after bumping `.sdkmanrc` to `java=25.0.3-graal` and clearing
the GraalVM 25 native-image warnings: drop `--strict-image-heap` (now
the default), gate `-H:BuildOutputJSONFile` under `-H:±UnlockExperimentalVMOptions`,
switch `bb native:ata` from `-H:Name=target/by` to `-o target/by`, and
migrate the deprecated `proxy-config.json` to `reachability-metadata.json`
(proxies under `reflection.type.proxy`). Build is warning-free, and the
GraalVM 25 compiler trims another ~10 MB off the binary on top of
Phase 3.

| Metric | After Phase 3 (GraalVM 21.0.9) | GraalVM 25.0.3 | Δ |
|---|---|---|---|
| Native binary | 154,133,120 B (146.99 MB) | **144,051,568 B (137.38 MB)** | **−9.61 MB** |
| Build time, `bb build:ata` | (n/a) | **1m 41s** (101.7s native-image) | — |
| Build peak RSS | 11 GB (Phase 0) | **6.24 GB** | −4.76 GB |
| Warm start, `by agents` | < 10 ms | ~7 ms | unchanged |
| JVM start, `bb tui agents` | 2.9 s (Phase 0) | ~7.0 s | repo has grown |
| Agent listing parity | 18 / 18 | 18 / 18 | unchanged |

Build composition (per `target/native-build.json`):
- Code area: 58.98 MB (57,488 compilation units; top origins:
  `agent-tui-app.jar` 33.95 MB, `java.base` 13.79 MB, `svm.jar` 4.09 MB,
  `java.xml` 3.76 MB, `java.net.http` 1.34 MB).
- Image heap: 83.56 MB (1,144,559 objects, 621 resources).
- Reflection registrations: 3,504 types / 477 fields / 3,105 methods.
- JNI registrations: 73 types / 70 fields / 72 methods.
- Reachable: 25,851 types / 35,728 fields / 98,571 methods.
- GraalVM features active: `clj_easy.graal_build_time.InitClojureClasses`,
  `com.oracle.svm.thirdparty.gson.GsonFeature`,
  `org.sqlite.nativeimage.SqliteJdbcFeature`.

Notes:
- Warm-start ~7 ms measured with hot page cache (5 runs, Python
  `time.perf_counter`); Phase 0's ~1.5 s cold-start observation is
  preserved as the expected first-run number.
- The size reduction is from GraalVM 25's improved dead-code elimination
  and the lighter heap snapshot under the now-default strict image heap.
  No runtime behaviour changed; `bb check:ata` + agent listing both
  unchanged.

### Native-image configuration

Config lives alongside the project source:

```
projects/agent-tui-app/resources/META-INF/native-image/ai.brainyard/agent-tui-app/
├── native-image.properties
├── reflect-config.json
├── resource-config.json
└── reachability-metadata.json
```

| File | Purpose |
|---|---|
| `native-image.properties` | Build flags. Default `--initialize-at-build-time`. Run-time init for `org.sqlite.JDBC`, `org.apache.http.impl.auth.NTLMEngineImpl`. Passes `--no-fallback`, `--report-unsupported-elements-at-runtime`, `-march=compatibility`, `-H:+ReportExceptionStackTraces`, `-H:+AllowDeprecatedInitializeAllClassesAtBuildTime`. |
| `reflect-config.json` | Reflection registrations (~2800 lines: all agent namespaces, sandbox, memory, BT, JDBC, SLF4J, …). |
| `resource-config.json` | Bundled `.clj` sources, class files, `META-INF` service providers (~1300 lines). |
| `reachability-metadata.json` | Dynamic-proxy registrations under `reflection`. Replaced `proxy-config.json` on GraalVM 25 (the older file is deprecated). |

Day-to-day workflow (post-Phase-1):

- **`bb check:ata`** — static drift gate. Verifies the four config files
  exist, are non-empty, and haven't grown past a line-count ceiling.
  Wire `git diff --exit-code` on top in CI to also catch unintended
  modifications. Fast; no native-image build required.
- **`bb size:ata`** — pretty-print the latest `target/native-build.json`
  (binary size, code area, image heap, reachability counts, GraalVM
  version). Run after `bb build:ata` to spot regressions. Designed
  to be the hook for a future CI size-budget gate.

Regenerating the configs from scratch — when a new code path needs
reflection / resources that didn't exist before — is still possible
via GraalVM's native-image-agent. It's a manual operation, not a
committed bb task (the design's automated tracing harness is parked;
see `docs/design/native-image-design.md` §10):

```bash
# Run the JVM build with the tracing agent attached, exercise the new
# code path manually, then diff the output into the committed configs:
java -agentlib:native-image-agent=config-output-dir=/tmp/ni-config \
  -jar projects/agent-tui-app/target/agent-tui-app.jar run -a coact-agent
diff /tmp/ni-config/reflect-config.json \
     projects/agent-tui-app/resources/META-INF/native-image/ai.brainyard/agent-tui-app/reflect-config.json
```

In practice manual additions are far more common than full regenerations:
when a `requiring-resolve` against a not-yet-statically-required namespace
breaks at runtime, append the missing entry to `reflect-config.json` and
`resource-config.json` by hand. The list of currently-statically-required
namespaces is in `projects/agent-tui-app/src/ai/brainyard/agent_tui_app/main.clj`
(lines 10–36).

### `main.clj` and AOT hazards

`main.clj` ultimately loads `ai.brainyard.agent.interface`, which
statically requires every built-in defagent namespace so AOT
compilation captures their registrations. Without the static requires,
the registrations happen only when those namespaces are first loaded —
which never happens in the native image because nothing in `-main`'s
transitive class graph references them.

If you add a new registerable agent, add a static require to
`agent.interface` alongside the existing entries (coact, react, plan,
todo, exec, eval, update, explore, research, workflow, search, skill,
mcp, rlm, acp).

---

## Public release & distribution

Public distribution of `by` happens out of a separate, public mirror
repository — **`grumatic/brainyard`** — not this private dev repo. The
design and current rollout plan live in that repo's
`docs/deploy-design.md` (v0.2 — 2026-05-17, owner Jake); the summary
below is a pointer, not the source of truth. Most of the machinery
described here (sync script, CI workflows, installer) is **planned**;
the public repo today contains only `.git/` and `docs/`.

### Distribution channel

GitHub Releases on `grumatic/brainyard` is the sole channel in v1.
Homebrew, Scoop, Docker images, and `bbin` are explicit non-goals for
v1 and may be added later. The Babashka-script artifact from the v0.1
design was dropped because the runtime deps (sqlite-jdbc, cognitect.aws,
logback, mulog) require a full JVM.

### Released artifacts per tag

| Asset | Platform / role | Built by |
|---|---|---|
| `by-<version>-linux-amd64` | Linux x86_64 (`ubuntu-latest`) | `bb native:ata` |
| `by-<version>-linux-arm64` | Linux arm64 (`ubuntu-22.04-arm`) | `bb native:ata` |
| `by-<version>-macos-amd64` | macOS Intel (`macos-13`) | `bb native:ata` |
| `by-<version>-macos-arm64` | macOS Apple Silicon (`macos-14`) | `bb native:ata` |
| `by-<version>.jar` | JDK 21+ uberjar | `bb uberjar:ata` |
| `by.jar` | stable-filename copy of the above (keeps `/latest/download/by.jar` URLs valid) | — |
| `by-wrapper.sh` | POSIX wrapper — walks up to find `.env`, sources it, `exec`s `by-bin` | mirrored from `projects/agent-tui-app/scripts/by-wrapper.sh` |
| `SHA256SUMS` | checksums covering every asset above | — |

Windows is **deferred for v1** — the upstream `bb native:ata` + wrapper
script are POSIX-only and need a separate MSVC toolchain pass.

### Versioning

- Semantic versioning, tags shaped `vMAJOR.MINOR.PATCH` (e.g. `v0.1.0`).
- Pre-releases use `vX.Y.Z-rc.N`; the workflow auto-flags the GitHub
  Release as prerelease when the tag contains a hyphen.
- The release workflow asserts that the stripped-`v` tag matches
  `app-version` in
  `projects/agent-tui-app/src/ai/brainyard/agent_tui_app/main.clj`.
  Mismatches abort the release rather than ship a confused version.

### Install UX (consumers)

Two install paths in v1; the README leads with the easiest first.

```bash
# Native binary (recommended):
curl -fsSL https://raw.githubusercontent.com/grumatic/brainyard/main/bin/install.sh | bash

# JVM users:
curl -LO https://github.com/grumatic/brainyard/releases/latest/download/by.jar
java -jar by.jar --help
```

`bin/install.sh` (planned, in the public repo) is a small POSIX shell
installer that:

1. Detects OS + arch (`uname -s`, `uname -m`), maps to the matching
   release asset name.
2. Resolves the latest tag (or honours `BY_VERSION=vX.Y.Z`).
3. Downloads the native binary → `~/.local/bin/by-bin`, the wrapper
   → `~/.local/bin/by`, and `SHA256SUMS` for verification.
4. On macOS: re-applies an ad-hoc codesign
   (`codesign --force --sign - ~/.local/bin/by-bin`) — without it,
   AMFI SIGKILLs the copied binary on first launch (this is the same
   step `bb install:ata` does locally).
5. `--with-jar` pulls the uberjar too, enabling `BY_JAR=1 by …` JVM
   fallback mode.

The install layout matches `bb install:ata`'s local-dev layout: `by`
(wrapper) + `by-bin` (native) + optional `by.jar`.

### CI / CD (in the public repo)

Two planned GitHub Actions workflows. Both call the upstream `bb …:ata`
tasks rather than reinventing build steps — `bb.edn` is the contract;
CI is a thin wrapper.

- **`.github/workflows/ci.yml`** — runs on every PR and push to `main`:
  `bb check:ata` (drift gate) → `bb compile:ata` → `bb uberjar:ata`
  (asserts `META-INF/native-image` survived bundling) → `bb poly:check`
  → `clj-kondo` → `bb poly test :project agent-tui-app`. No native
  build on PRs — 10–20 min per matrix leg is too slow for review.

- **`.github/workflows/release.yml`** — runs on tag push matching
  `v*.*.*`. Three jobs:
  1. `build-jar` (Ubuntu) — `bb compile:ata && bb uberjar:ata`,
     version-asserts, renames `target/agent-tui-app.jar`
     → `by-<version>.jar`.
  2. `build-native` (matrix: linux-amd64 / linux-arm64 / macos-amd64
     / macos-arm64) — `bb native:ata`, codesigns on Darwin, uploads.
  3. `publish-release` — gathers artifacts, generates `SHA256SUMS`,
     creates the GitHub Release via `softprops/action-gh-release`.
     Pre-release flag is auto-set when the tag name contains `-`.

Secrets: only the default `GITHUB_TOKEN` with `contents: write`. No
LLM API keys are exercised during the release build — only AOT compile
and `native-image`.

### Sync from this dev repo

The public repo mirrors a **Polylith subset** transitively required by
`projects/agent-tui-app`, preserving relative paths so the `:local/root`
graph stays intact and the existing `bb …:ata` tasks work unchanged.
Other projects (`agent-web-app`, `fulcro-rad-app`, `electric-app`,
`replicant-app`, `acp-stub-agent`) and their unique components stay
private.

Sync is **manual and gated**, performed by running
`bin/sync-from-dev.sh` in the public repo:

1. Resolves upstream commit SHA (in `~/MyDev/brainyard` by default;
   overridable via `BRAINYARD_DEV_REPO`).
2. Computes the publishable brick set by walking `:local/root` edges
   from `projects/agent-tui-app` to a fixed point (currently 1 base
   + 12 components, but re-derived on every run because upstream may
   add components).
3. `rsync`s the resolved subset into the public repo:
   `projects/agent-tui-app/`, `bases/agent-tui/`, each transitive
   `components/<brick>/`, `bb.edn`, `deps.edn`, `workspace.edn`,
   `.clj-kondo/`, `.sdkmanrc`.
4. Writes `SYNCED-FROM.txt` (upstream SHA + branch + timestamp + brick
   list) at the public repo root for provenance.
5. Runs `bb compile:ata` as a validation step — a successful compile
   proves the mirrored subset is closed (no `:local/root` reference
   points outside the mirror).
6. Refuses to push. The operator commits and pushes explicitly.

Why manual sync rather than git subtree / submodule: the dev repo is
private and contains other subprojects and internal discussion that
must not be exposed, even via shared history. A copy-with-recorded-SHA
gives provenance without leaking history.

### Release process (maintainer flow)

1. **In this (dev) repo**: bump `app-version` in
   `projects/agent-tui-app/src/ai/brainyard/agent_tui_app/main.clj`,
   finish the change, run `bb build:ata` locally to verify, commit, push.
2. **In the public repo**: run `bin/sync-from-dev.sh`. Review
   `git status` / `git diff`; confirm the validation `bb compile:ata`
   ran cleanly.
3. Update `CHANGELOG.md` with a section matching the new `app-version`.
   Extract that section into `CHANGELOG-latest.md` (used as release notes).
4. Commit: `chore: sync from upstream @ <sha>`.
5. Tag: `git tag v0.1.0 && git push origin main v0.1.0`. The
   stripped-`v` tag must equal `app-version` or the workflow aborts.
6. Watch `release.yml`. Artifacts appear on the Releases page.
7. Verify by running the `curl | bash` installer on a clean machine (or
   in a container) and exercising a quick-start command — e.g.
   `by ask -m haiku 'What is 2+2?'`.

`bin/release.sh` in the public repo wraps steps 4–5 with sanity checks
(`SYNCED-FROM.txt` SHA matches upstream HEAD, tag doesn't already exist,
tag version matches `app-version`, `CHANGELOG-latest.md` is non-empty).

### Open questions (per public-repo design v0.2)

Pending decisions before v1 ships — none block the design, all live in
`grumatic/brainyard:docs/deploy-design.md §9`:

- **License** — MIT, copyright Grumatic, Inc.
- **Brick-set freezing** — commit a resolved brick list
  (`bin/.brick-set`) so accidental brick additions in upstream are
  caught at sync time.
- **Source distribution policy** — publicly mirroring 12 components
  exposes LLM-provider wiring, sandbox policy, persistence schema, etc.
  Dry-run the sync first, decide per-brick whether to redact or stub.
- **Windows native build** — deferred for v1; revisit after demand
  surfaces.
- **Telemetry / update check** — default position is **no**; `mulog`'s
  local writes to `~/.brainyard/logs/agent-tui-app.log` should remain
  the ceiling.

Rollout milestones (M0 skeleton → M0.5 brick-set review → M1 first
manual release → M2 CI for jar → M3 native binary matrix → M4 polish)
are tracked in §10 of the design doc.

---

## Configuration at runtime

### `.env`

The `bb tui` task does `set -a && source .env` before invoking
Clojure, so any of the provider / agent environment variables (API
keys, endpoints, workspace paths) can live there and are picked up
automatically.

### `.brainyard/` directories

`components/agent` resolves two scopes — see
[architecture.md §`.brainyard/` subdir scope contract](architecture.md)
and [config-agent-design.md §2A](design/config-agent-design.md) for the
full policy.

- **Project** — `<repo>/.brainyard/` next to `deps.edn`. Per-repo
  artifacts:
  - `*-agent/` runtime state (`explore-agent/`, `plan-agent/`,
    `workflow-agent/`, `research-agent/<id>/`, …).
  - `temp/coact-agent/scratch/` — code-block execution + verbatim
    content scratch files (rides the 24h GC sweep).
  - `charts/chart-<ts>.html` — Plotly exports from
    `chart-command$export-html`.
  - `clj-sandbox/truncation/<class>` — sandbox truncation recovery
    cache. `clj-sandbox/file-backed/<class>` — display-block backing
    files. Both are seeded by the running agent and re-read by the LLM
    via `read-file`.
  - The project's `config.edn` / `BRAINYARD.md` / `skills/`.

  **All `*-agent/` dirs are project-only** except `config-agent/` and
  `init-agent/` (whose artifacts mirror the scope of the file they edit).

- **User** — `~/.brainyard/`. Per-account state:
  - Memory DB (`memory/<user>.db`) — SQLite, BM25 FTS5.
  - Persisted TUI sessions (`sessions/<id>/`) — scrollback, queues,
    input history, lock file.
  - Application logs (`logs/agent-tui-app.log`,
    `logs/agent-web-app.log`, `logs/by-crash.log`,
    `logs/by-input-crash.log`) — mulog file publishers + crash dumps.
  - The user's cross-project `config.edn` / `BRAINYARD.md` / `skills/`.

  **`memory/`, `sessions/`, `logs/` are user-only by policy** — they
  hold per-account state that must not travel with a repo.

Project-dir resolution: `BY_PROJECT_DIR` env → nearest `.git`
ancestor of cwd → **cwd itself** (fallback). The fallback means
project-scope writes always have a target, even outside a git repo.

`bb tui config` (the bootstrap wizard) writes via `:auto`, which prefers
project-scope when a project dir is resolvable. After bootstrap, run
`bb tui run -a config-agent` to edit either scope through chat.

### Runtime-config precedence

CLI flags win, then file config, then hardcoded defaults:

```
bb tui -p anthropic                                    >
  <repo>/.brainyard/config.edn :llm :default-provider  >    (project)
  ~/.brainyard/config.edn      :llm :default-provider  >    (user)
  hardcoded :claude-code
```

The two scopes are **not auto-merged**. `read-edn-config` returns the
project file if it exists, else the user file. A key set in user scope
is shadowed by the project value when project's `config.edn` is present.

The currently-loaded runtime-config is visible via `:status` in the TUI.

### Persisted sessions

Session state for the tmux-based TUI lives under
`~/.brainyard/sessions/<agent-session-id>/` (EDN I/O, lock, eviction,
scrollback, snapshots). Use `bb tui sessions list` and
`bb tui sessions prune -s <id>` to inspect and clean up.
See `docs/tui/` for the architectural details.

---

## Sandboxed and proxied environments

`docs/design/SANDBOX.md` covers the operational side: running `by`
inside Kubernetes / OpenShell-style sandboxes with a TLS-terminating
proxy.

Key points:

- `CLJ_HTTP_INSECURE=true` — Java accepts the proxy's injected cert.
- `~/.m2/settings.xml` — Maven configured explicitly (it ignores JVM
  system properties for proxy routing).
- All providers (openai, anthropic, claude-code, ollama) work via the
  proxy.
- No local-inference option — no model weights ship with the binary;
  outbound HTTPS is assumed.

---

## Dockerfile

`Dockerfile.sandbox` at the repo root builds a container with the
toolchain preinstalled — useful for reproducible builds in CI or for
hermetic runtime environments. See the file for the full recipe.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Could not find or load main class ai.brainyard.agent_tui_app.main` | AOT compile step skipped | `bb compile:ata` before uberjar |
| `ClassNotFoundException` in native image | Missing `reflect-config` entry | Regenerate with the tracing agent, or add by hand |
| `SQLite: Not found: …` in native image | JDBC init at build-time | Confirm `org.sqlite.JDBC` in `--initialize-at-run-time` |
| `coact-agent` not in `bb tui agents` output | Static require missing | Ensure `agent.interface` still requires the namespace |
| TUI breaks mid-session with odd characters | Stale alt-screen after crash | `reset` in the terminal; re-run |
| `bb tui run --with-tmux` exits 1 immediately | Not inside a tmux session | Run `tmux` first, or drop `--with-tmux` |

---

## See also

- [Architecture](architecture.md) — where the binary sits in the
  Polylith graph.
- [core/reasoning.md](core/reasoning.md) — runtime sandbox + bindings
  once the binary is running.
- Root `CLAUDE.md` — canonical list of all `bb` tasks.
- `docs/design/SANDBOX.md` — sandboxing playbook.
- `docs/tui/` — tmux-based TUI substrate.
- `grumatic/brainyard:docs/deploy-design.md` — full public-release
  design (v0.2 — 2026-05-17): sync model, CI/CD workflows, installer
  spec, rollout milestones, open questions. The source of truth for
  everything in §"Public release & distribution" above.
