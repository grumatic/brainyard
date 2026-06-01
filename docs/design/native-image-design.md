# Native-Image — Making `agent-tui-app` Build as the `by` Binary

> **Status:** Partially shipped. The GraalVM native build ships — `bb build:ata` / `compile:ata` / `uberjar:ata` / `native:ata` / `install:ata` / `check:ata` / `size:ata` / `docker:ata` / `version:ata` all exist, and `reachability-metadata.json` has replaced the deprecated `proxy-config.json` on GraalVM 25 (see `CLAUDE.md` §"GraalVM Native Build" and [build-and-deploy.md](../build-and-deploy.md)). The remaining proposal here — an automated tracing-agent harness that *regenerates* the reflect/resource config and a CI test that the binary lists every agent — is **not yet committed**; config stays hand-curated behind the static `bb check:ata` drift gate.
> **Scope:** the GraalVM native-image build of `projects/agent-tui-app` → `target/by`. Touches `bb.edn` (the `compile:ata` / `uberjar:ata` / `native:ata` / `build:ata` tasks), the `projects/agent-tui-app/resources/META-INF/native-image/ai.brainyard/agent-tui-app/` config directory, several component `deps.edn` files (notably `components/clj-llm`, `components/agent`, `components/util`), and a small amount of source rewriting in `clj-llm` and `agent`'s MCP client.
> **Related reading:** `CLAUDE.md` §"GraalVM Native Build", `docs/build-and-deploy.md` §"The native-image pipeline", `bb.edn` tasks `compile:ata` / `native:ata`, `projects/agent-tui-app/src/ai/brainyard/agent_tui_app/main.clj`, `components/clj-llm/src/ai/brainyard/clj_llm/core/llm.clj`, `components/agent/src/ai/brainyard/agent/mcp/client.clj`, GraalVM reachability metadata docs at <https://www.graalvm.org/latest/reference-manual/native-image/metadata/>, `clj-easy/graal-build-time` at <https://github.com/clj-easy/graal-build-time>, the GraalVM reachability metadata repository at <https://github.com/oracle/graalvm-reachability-metadata>.

---

## 1. Motivation

The repository ships a `bb build:ata` pipeline (`bb.edn` lines 117–184) that runs `compile:ata → uberjar:ata → native:ata` and is documented in `docs/build-and-deploy.md` as producing a ~115 MB `by` binary on arm64 with ~0.5 s cold start. The `projects/agent-tui-app/resources/META-INF/native-image/ai.brainyard/agent-tui-app/` directory already carries hand-curated config (`reflect-config.json` ~2 800 lines, `resource-config.json` ~1 300 lines, a stub `proxy-config.json`, and a `native-image.properties` that uses the deprecated `-H:+AllowDeprecatedInitializeAllClassesAtBuildTime` plus a couple of `--initialize-at-run-time` overrides). The README implies the build works.

In practice the codebase is not yet fitted for a clean native-image build, for four reasons that interact with each other:

1. **Class-initialization policy is the deprecated form.** `native-image.properties` uses `--initialize-at-build-time` with no argument plus the `AllowDeprecated…` opt-in. That global form is end-of-life in modern GraalVM (deprecated from 22.x, removed/repurposed in the JDK 21/22 line and afterwards), and it forces *every* class to be initialized at build time — including some that have side effects (e.g. `SecureRandom` seed, `sun.net.www.protocol.*` registries) that we do not want frozen into the heap. The replacement is to initialize *only* Clojure-generated classes at build time and explicitly opt other classes in or out, which is exactly what `clj-easy/graal-build-time` exists to do. We currently neither depend on it nor list packages explicitly.

2. **Reflection / resource metadata is hand-curated and brittle.** `reflect-config.json` and `resource-config.json` carry 4 000+ entries that look like they were produced by an old tracing-agent run. A new agent (e.g. the recently-landed `research-agent`, `init-agent`, `memory-agent`) does not appear automatically — `defagent` registers in a `defonce` atom at namespace-load time, and `agent.interface` statically requires every built-in (see `components/agent/src/ai/brainyard/agent/interface.clj` lines 37–58), but its `__init` class still needs to be listed in `reflect-config.json` and its source-jar entries listed in `resource-config.json` if we want SCI/`requiring-resolve` paths to find them. There is no documented refresh procedure beyond a one-liner in `docs/build-and-deploy.md`, no committed tracing harness, and no test that the binary can in fact list every agent that `bb tui agents` lists on the JVM. The two have drifted and will keep drifting.

3. **The transitive surface still ships a duplicate HTTP stack.** `components/clj-llm` and `components/agent` depend on `clj-http/clj-http {:mvn/version "3.13.0"}`. That drags in the full Apache HttpComponents stack (`httpclient`, `httpcore`, `commons-codec`, `commons-logging`, and the NTLM auth engine), which is heavy on its own — and entirely redundant once `cognitect.aws/api` is on the classpath, because that already requires `java.net.http` (the JDK module) via `cognitect.aws.http.java`. We are paying for two HTTP implementations and a non-trivial reflection / SSL config surface that exists *only* for clj-http (`--initialize-at-run-time=org.apache.http.impl.auth.NTLMEngineImpl` in `native-image.properties` is a symptom). This is the question the user raised — addressed in §6.

4. **`bb native:ata` is not parameterised correctly.** The task at `bb.edn` lines 145–176 passes only `--no-fallback`, `--initialize-at-build-time`, `--enable-native-access=ALL-UNNAMED`, `--enable-url-protocols=http,https`, `-jar target/agent-tui-app.jar`, `-H:Name=target/by`. It does *not* enable the `clj-easy/graal-build-time` feature, does not enable the reachability-metadata repo, does not pass `-H:IncludeResources` / `-H:ConfigurationFileDirectories`, and does not enable the build-output JSON report that we would need to track size regressions. The META-INF/native-image directory is auto-discovered when the jar is built that way (because it lives under `META-INF/native-image/ai.brainyard/agent-tui-app/`), but the discovery requires that uberdeps preserve the directory layout in the jar — easy to silently break.

The result of (1)–(4) is that the build either fails on a fresh checkout (most likely because Clojure-generated package init drifted), produces a binary that segfaults on an LLM call (because of a missed reflection registration in a freshly-loaded ns), or works by luck and weighs ~115 MB when it should weigh closer to 80–90 MB.

The thesis of this doc is that we can replace the curated artefacts with a deterministic recipe — `clj-easy/graal-build-time` for class-init, the GraalVM reachability metadata repo for third-party libraries, a small in-repo tracing harness for the parts the agent never reaches at test time, and one strategic HTTP-client migration — and reach a binary that is smaller (target ~85 MB), faster (~0.3 s cold start), reproducible from a clean checkout, and self-maintaining (a new defagent does not require a hand-edit to a JSON file).

---

## 2. Goals and Non-Goals

**Goals.**

1. `bb build:ata` succeeds on a clean checkout against the pinned GraalVM 25 (`.sdkmanrc` → `java=25.0.3-graal`).
2. The produced `by` binary passes a smoke suite that covers: `by agents`, `by models`, `by ask -m haiku 'echo'` against a stubbed provider, and a tmux-detached `by run` that loads `coact-agent` and exits cleanly. The smoke suite runs in CI on each PR.
3. The binary's size budget is ≤ 90 MB on arm64 / linux-x86_64 (down from 115 MB), tracked over time in `docs/build-and-deploy.md` as part of the release notes.
4. Adding a new `defagent` requires *no* edits to `reflect-config.json` / `resource-config.json` / `reachability-metadata.json` beyond the existing `agent.interface` static-require contract. The config is generated by `clj-easy/graal-build-time` + a single tracing-agent run committed to `META-INF/native-image/.../agent-tui-app/generated/`.
5. Class-init policy is documented in `native-image.properties` with explicit `--initialize-at-build-time=<pkg>` entries (no `AllowDeprecated` flag). What runs at run time is named and justified.
6. clj-http is removed from the runtime classpath of `agent-tui-app`. All outbound HTTP goes through one client built on `java.net.http`.

**Non-goals.**

1. Cross-compilation (e.g. building Linux binaries on macOS). The pinned GraalVM is per-host; CI cuts both arm64 and linux-x86_64 in parallel.
2. Static-PIE binaries (`--static` / `--libc=musl`). The musl path adds substantial complexity for limited benefit; defer until there is a deployment that needs it.
3. Removing SCI from the image. The recursive language model (`components/clj-sandbox`) is core to CoAct; SCI is well-supported by GraalVM and accounts for an irreducible chunk of the binary.
4. Replacing `cognitect.aws/api` or `cognitect.aws/bedrock-runtime`. Both are GraalVM-compatible on JDK 11+ via `cognitect.aws.http.java`; they are explicitly pre-loaded in `main.clj` (lines 19–33).
5. A new native build of `agent-web-app` or `fulcro-rad-app`. Those projects have different constraints (HTTP server, Jetty, transit) and are out of scope.
6. Removing logback or mulog. Logging is plumbed throughout; the cost is the cost.

---

## 3. Position in the Stack

```
                      ┌────────────────────────────────────────────────────────┐
   bb compile:ata     │ projects/agent-tui-app/src/.../main.clj                │
   ─────────────►     │   (:gen-class) -main                                   │
                      │   Static requires:                                     │
                      │     ai.brainyard.agent.interface  (every defagent)     │
                      │     cognitect.aws.client.api                           │
                      │     cognitect.aws.http.java        ← uses java.net.http│
                      │     cognitect.aws.protocols.rest-json                  │
                      │     ai.brainyard.aws-client.interface                  │
                      │     ai.brainyard.clj-llm.interface                     │
                      │     ai.brainyard.agent-tui.core                        │
                      └──────────┬─────────────────────────────────────────────┘
                                 │ AOT-compiles to projects/agent-tui-app/classes/
                                 ▼
                      ┌────────────────────────────────────────────────────────┐
   bb uberjar:ata     │ uberdeps:                                              │
   ─────────────►     │   target/agent-tui-app.jar  (~38 MB)                   │
                      │   ├── ai/brainyard/**/*.clj + *.class                  │
                      │   ├── sci, edamame, malli, mulog, slf4j, logback       │
                      │   ├── clj-http, httpclient, httpcore, commons-codec    │
                      │   ├── cognitect.aws.{api,bedrock-runtime,sts,iam,…}    │
                      │   ├── sqlite-jdbc, next.jdbc, core.async               │
                      │   └── META-INF/native-image/ai.brainyard/agent-tui-app/│
                      │         ├── native-image.properties                    │
                      │         ├── reflect-config.json                        │
                      │         ├── resource-config.json                       │
                      │         └── reachability-metadata.json                 │
                      └──────────┬─────────────────────────────────────────────┘
                                 │ native-image picks up META-INF auto-discovery
                                 ▼
                      ┌────────────────────────────────────────────────────────┐
   bb native:ata      │ native-image \                                         │
   ─────────────►     │   --features=clj_easy.graal_build_time.InitClojureClasses │
                      │   --initialize-at-build-time=<allow-list>              │
                      │   --initialize-at-run-time=<deny-list>                 │
                      │   --enable-url-protocols=http,https                    │
                      │   --enable-native-access=ALL-UNNAMED                   │
                      │   -H:ConfigurationFileDirectories=…/generated          │
                      │   -H:BuildOutputJSONFile=target/build-output.json      │
                      │   -jar target/agent-tui-app.jar                        │
                      │   -H:Name=target/by                                    │
                      │                                                        │
                      │   →  target/by   (~85 MB target)                       │
                      └────────────────────────────────────────────────────────┘
```

The pipeline does not change shape. What changes is:

- `compile:ata` becomes responsible for triggering `agent.interface` and the AWS pre-loads explicitly, so the AOT pass captures all `defagent` registrations and the cognitect dynaload targets (it already does; we document it).
- `uberjar:ata` keeps producing the same jar, but is constrained to include `META-INF/native-image/**/**` verbatim (we add an explicit `--keep-paths` / equivalent if uberdeps ever changes behaviour, plus a checked-in assertion in the bb task that walks the jar and verifies the dir is present and non-empty before invoking native-image).
- `native:ata` gains the `clj-easy/graal-build-time` feature, switches from the deprecated global init form to an explicit allow-list, enables the JSON build report, and drops `--enable-url-protocols=http` (TLS-only by default once we migrate off clj-http; HTTP-without-S is enabled only if a flag/config asks for it).

---

## 4. GraalVM Native-Image — The Five Things That Matter Here

Native-image is a static analyser over the AOT-compiled classpath. It walks reachable code starting from `main`, eliminates everything it can prove is dead, and emits a self-contained ELF/Mach-O binary. Five aspects of that pipeline bite Clojure projects in particular.

**(a) Reflection.** Calls to `Class.forName`, `getDeclaredMethod`, `newInstance`, etc. with non-constant arguments are invisible to static analysis. To keep them working, you list the target classes and members in `reflect-config.json` (or modern unified `reachability-metadata.json`). Clojure relies on reflection during `:gen-class`, `proxy`, `definterface`, `defprotocol` extension, `clojure.core/find-var`, `clojure.lang.RT.classForName` (used by `Class/forName` literals), and almost every `requiring-resolve` against an ns whose source jar is stripped at image build time. The repo currently uses `requiring-resolve` in 30+ files (`components/agent/src/ai/brainyard/agent/common/*.clj`, `components/clj-llm/src/.../{bedrock,oauth}.clj`, `bases/agent-tui/src/.../{commands,sessions,autocomplete,display_block_ui}.clj`). Each of those targets must either (i) be statically required from `main.clj`'s transitive tree (the current AWS / agent.interface pattern), or (ii) carry a reflect-config entry. We pick (i) wherever feasible — see §11.

**(b) Resources.** Files on the classpath that the runtime reads via `io/resource`, `getResourceAsStream`, or `clojure.java.io/resource` must be listed in `resource-config.json`. Clojure-built jars typically need their `.clj` source files (consumed by `clojure.lang.RT/load` when `*compile-files*` is false, and by SCI when source-loading is on), their `__init.class` files, and any `META-INF/services/**` SPI descriptors. Today the repo's `resource-config.json` enumerates every `agent/common/*` source + class explicitly; this will be regenerated by the tracing agent in §10 rather than hand-edited.

**(c) Class initialization phase (build-time vs run-time).** GraalVM lets each class be initialized at *build time* (its `<clinit>` runs during `native-image` and the resulting state is baked into the heap snapshot) or *run time* (its `<clinit>` runs on first use of the binary). Clojure namespaces compile to `*__init.class` whose `<clinit>` actually loads the namespace's vars and definitions — Clojure was designed before the build-time/run-time distinction existed. For the binary to boot cleanly, those `__init` classes must be initialized at build time; otherwise the runtime tries to load `.clj` source that may not be on disk and fails. Conversely, anything that captures live state in `<clinit>` (random seeds, opened sockets, JNI handles, `java.util.logging.Logger` instances) *must* be initialized at run time, or you get an "instance not allowed in the image heap" error.

The right policy is: initialize Clojure's `*__init` classes at build time, leave everything else at run time, and add explicit `--initialize-at-build-time=<pkg>` entries for any non-Clojure library you have measured as safe. `clj-easy/graal-build-time` walks the classpath looking for `*__init.class` files and asks GraalVM to build-init exactly their packages. With it on the classpath plus `--features=clj_easy.graal_build_time.InitClojureClasses`, we no longer need the deprecated global form.

**(d) Dynamic proxies.** `clojure.core/proxy`, `defprotocol` reflection on extension, `:gen-class`, and a few interop helpers (`reify` on Java interfaces from outside the static reachable set) create JDK dynamic proxies whose interface lists must be enumerated. On GraalVM 25 these live in the `reflection` section of `reachability-metadata.json` (the older `proxy-config.json` is deprecated). Today's file has two entries (`sun.misc.SignalHandler`, `ch.qos.logback.core.Appender`). We will extend this with whatever the tracing agent records.

**(e) Substitutions and feature classes.** Some JDK / library internals have GraalVM-specific substitutions (alternate implementations swapped in at image build). `java.net.http`, `java.security.SecureRandom`, `java.util.zip.*`, `sqlite-jdbc` (since 3.40.1.0) ship their own. Apache `httpclient` does not — *which is the second reason the HTTP-client migration matters.* See §6.

References on these mechanisms: <https://www.graalvm.org/latest/reference-manual/native-image/metadata/>, <https://www.graalvm.org/latest/reference-manual/native-image/metadata/AutomaticMetadataCollection/>, <https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/Reflection/>.

**(f) Clojure-level reflection from unhinted interop.** Beyond GraalVM's
own metadata system, the Clojure compiler itself emits *reflection-based*
calls whenever it can't statically pick a Java overload. These calls
work on the JVM (slow but functional) and silently fail under
native-image (the reflection lookup gets stripped). The most common
sources in this repo are:

- **`ProcessBuilder` constructor.** Has overloads `(String...)` and
  `(List<String>)`. The hint `^java.util.List` on a vector literal is
  *ignored* by the compiler's overload resolution — the metadata
  attaches to the value but doesn't carry through. The working pattern
  is `(ProcessBuilder. ^"[Ljava.lang.String;" (into-array String [...]))`,
  which produces a known `String[]` and matches the varargs constructor.
- **`Process.waitFor`.** Has overloads `()` returning int and
  `(long, TimeUnit)` returning boolean. A bare integer literal like
  `200` resolves via reflection; the working pattern is
  `(.waitFor p (long 200) java.util.concurrent.TimeUnit/MILLISECONDS)`.
- **`Thread/sleep`.** Has overloads `(long)` and `(long, int)`. Always
  cast: `(Thread/sleep (long N))`.
- **`java.io.File` methods on unhinted bindings.** `(.getPath f)`,
  `(.mkdirs f)`, etc. on a value returned by `clojure.java.io/file` get
  resolved via reflection because `io/file`'s return type isn't
  propagated. Type-hint the binding: `^java.io.File f`.

The `*warn-on-reflection*` flag exposes all of the above. `bb compile:ata`
sets it during AOT compile so each warning is visible as it lands —
treat each as a potential native-image break, even if the JVM build
works fine. See task #21 in the work log for the initial sweep.

---

## 5. Current State — A Survey

A line-by-line read of the relevant files turns up the following.

**`projects/agent-tui-app/deps.edn`** pulls the agent-tui base plus 12 components: `agent`, `analytics`, `behavior-tree`, `clj-llm`, `mulog`, `clj-sandbox`, `display-block`, `memory`, `util`, `env-detect`, `agent-tui-persist`, `agent-tui-tmux`. Of those, the ones with non-trivial third-party deps are: `agent` (clj-http, core.async, acp-client), `clj-llm` (clj-http, malli, cognitect bedrock-runtime, data.json), `clj-sandbox` (SCI), `memory` (next.jdbc, sqlite-jdbc, core.async), `mulog` (mu/log, slf4j-api, logback-classic, integrant), `util` (inflections, encore, timbre, talltale), `behavior-tree` (malli).

**`components/util/deps.edn`** transitively brings in `talltale/talltale {:mvn/version "0.5.14"}`. Talltale is a Faker-style synthetic-data library that drags in `dragan/com.taoensso-encore`-adjacent code, large dictionaries of locale-specific names, and reflection patterns that are over-eager for a native binary. It is not used at the `by` runtime (it is a test/dev helper for memory fixtures). The Polylith convention says `util` is shared, so we cannot just delete the dep; we will move it behind a `:test` alias in `components/util/deps.edn` (or split it into a `components/util-talltale` sibling). Net image saving: 1–2 MB plus a few hundred reflection entries.

**`components/mulog/deps.edn`** uses `logback-classic 1.4.14`. Logback works under native-image, but only with a bundle of META-INF resources (`Configurator`, `LoggerFinder`, `SLF4JServiceProvider`) and a proxy entry for `ch.qos.logback.core.Appender` — all already present in the committed configs. Confirm in the new tracing-agent run that no further entries are needed for the `agent-tui-app` log path; the existing config was assembled against a different shape of `main.clj`.

**`components/memory/deps.edn`** uses `org.xerial/sqlite-jdbc 3.46.1.0`. Since 3.40.1.0, sqlite-jdbc ships its own GraalVM `Feature` and JNI registrations; we keep `org.sqlite.JDBC` on `--initialize-at-run-time` (as today) and add `-Dorg.sqlite.lib.exportPath=$(pwd)/target/sqlite-native` to extract the native library at *build* time rather than first-call, shaving cold-start latency. Reference: <https://github.com/xerial/sqlite-jdbc#graalvm-native-image-support>.

**`components/agent/deps.edn`** uses `core.async 1.7.701`. core.async needs `--initialize-at-build-time=clojure.core.async.impl.exec.threadpool` historically; recent versions are mostly fine under graal-build-time. Two known gotchas: the `pool-executor` `ScheduledThreadPoolExecutor` must not be created in `<clinit>` (it isn't, in current core.async), and `clojure.core.async.impl.dispatch/in-dispatch-thread?` must resolve correctly at runtime (it does once `__init` classes are build-initialized).

**`components/clj-llm/deps.edn`** + **`components/agent/deps.edn`** both depend on `clj-http/clj-http {:mvn/version "3.13.0"}`. This is the heavy item. The HTTP-client decision is §6.

**`components/aws-client/deps.edn`** depends on `com.cognitect.aws/api 0.8.723` and the per-service jars `bedrock-runtime`, `sts`, `iam`, plus `endpoints`. `cognitect.aws.api` already uses `java.net.http` (`cognitect.aws.http.java` namespace, pre-required from `main.clj` line 31). The aws-api `dynaload` machinery is the reason `main.clj` explicitly requires `cognitect.aws.protocols.rest-json` and `cognitect.aws.http.default` — those are otherwise discovered at runtime via name-based lookups, which native-image cannot follow without help. The committed `reflect-config.json` includes these classes.

**`projects/agent-tui-app/resources/META-INF/native-image/ai.brainyard/agent-tui-app/native-image.properties`** sets `--initialize-at-build-time` with no argument, `-H:+AllowDeprecatedInitializeAllClassesAtBuildTime`, `--initialize-at-run-time=org.sqlite.JDBC,org.apache.http.impl.auth.NTLMEngineImpl`, `--report-unsupported-elements-at-runtime`, `--no-fallback`, `-H:+ReportExceptionStackTraces`, `-march=compatibility`. The `--report-unsupported-elements-at-runtime` flag is also deprecated; modern advice is to fix the underlying use-of-reflection rather than punt to runtime.

**`bb.edn`** `native:ata` ignores `native-image.properties` and passes its own incompatible flag set (`--initialize-at-build-time` global, no `AllowDeprecated`, no `-march`). The duplication is a footgun — whichever flag is "last" wins, and that depends on how GraalVM merges the META-INF properties with the command-line. We will move all flags into `native-image.properties` and have `bb.edn` invoke `native-image -jar target/agent-tui-app.jar -H:Name=target/by` only.

**Source surface.** `requiring-resolve` is used in 30+ files. Most resolve agent-internal namespaces that are already statically required from `agent.interface` (e.g. `ai.brainyard.agent-tui.core/ask`). Two cases need handling:

- `components/clj-llm/src/.../bedrock.clj` lines 26–44 do a `(try (require ns-sym) (catch …) (resolve sym))` dance specifically to survive the native-image case where source `.clj` files are not on the runtime classpath. `main.clj` lines 19–33 pre-load the cognitect namespaces explicitly to make this work. **Document this pattern; do not "fix" it.** The pre-load contract is correct.
- `components/clj-llm/src/.../llm.clj` line 333 does `(try (require 'ai.brainyard.clj-llm.core.oauth) … (resolve …))` for Anthropic OAuth. Add `[ai.brainyard.clj-llm.core.oauth]` to `main.clj`'s requires (or to `clj-llm.interface`, which `main.clj` already loads) so the ns is reachable from the static graph.

**`(eval …)` calls.** A handful of files call `clojure.core/eval` (`components/agent/.../research_agent.clj`, `eval_agent.clj`, `exec_agent.clj`, `workflow_agent.clj`, `display-block/core/eval.clj`, `clj-sandbox/core/{sandbox,chat}.clj`). The sandbox uses **SCI** (Small Clojure Interpreter), which is a custom evaluator and does not call `clojure.core/eval`; SCI is fully native-image-compatible. The other `eval` calls all eval *quoted forms with no free symbols* (they parse user-supplied EDN into Clojure data and call eval on a known shape). Native-image supports this via `clojure.lang.Compiler/eval` in the JDK runtime, but only if no AOT-unseen var is referenced. Audit pass: confirm that every `eval` site evaluates a form that references only vars that were AOT-compiled — i.e. nothing that requires a not-yet-loaded namespace. Three sites need explicit pre-requires; the others are already covered by the `agent.interface` chain.

---

## 6. The HTTP-Client Question — Replace clj-http with `java.net.http`?

**The user's framing.** clj-http is "huge" (Apache HttpComponents) and the binary is correspondingly bloated. Should we rewrite the few callers on top of `java.net.http`?

**Short answer: yes, and the saving compounds with the cognitect.aws.http.java dep we already ship.**

The long answer follows.

### 6.1 What clj-http actually costs

`clj-http {:mvn/version "3.13.0"}` is a Clojure wrapper around Apache HttpComponents 4.5.x. The runtime jars it pulls in (excluding transitive Clojure helpers) are roughly:

| Library                                  | Source jar size | Why it's there                                  |
|------------------------------------------|----------------:|-------------------------------------------------|
| `org.apache.httpcomponents:httpclient`   | ~750 KB         | Core HTTP/1.1 client, conn pool, redirects      |
| `org.apache.httpcomponents:httpcore`     | ~340 KB         | Wire-level codec, request/response objects      |
| `org.apache.httpcomponents:httpmime`     | ~40 KB          | Multipart/form-data (used by `:multipart`)      |
| `commons-codec:commons-codec`            | ~360 KB         | Base64, hex, percent-encoding                   |
| `commons-logging:commons-logging`        | ~62 KB          | JCL (clj-http calls it directly)                |
| `org.apache.james:apache-mime4j-core`    | ~280 KB         | MIME parsing for multipart bodies               |
| `slingshot/slingshot`                    | ~30 KB          | clj-http's exception sugar                      |

Total jar bytecode ≈ **1.85 MB** before native-image expansion.

That number is the *floor*. Native-image expansion costs much more because:

1. Apache `httpclient` uses ServiceLoader heavily (auth schemes, redirect strategies, connection managers), each of which adds a `META-INF/services/**` entry and a reflection registration. The committed `resource-config.json` already has `META-INF/services/org.apache.commons.logging.LogFactory` for that reason.
2. NTLM auth (`org.apache.http.impl.auth.NTLMEngineImpl`) instantiates `SecureRandom` in its `<clinit>` — exactly the "live state in image heap" pattern GraalVM forbids — so it must be deferred to runtime, which is why the current `native-image.properties` lists it.
3. SSL/TLS wiring (`org.apache.http.conn.ssl.*`) reflectively loads provider classes; native-image needs every `SSLContextFactory` / `HostnameVerifier` impl named.
4. `commons-logging` does runtime classpath scanning (`LogFactory.getFactoryClass()`) which needs `META-INF/services/...` AND a Class-of-the-day reflection entry for whichever logger is wired (logback, in our case).

Empirically — and this is consistent with the Babashka size budget thread on Clojurians Slack (<https://clojurians-log.clojureverse.org/babashka/2021-08-28>), where adding `java.net.http` ran ~4.8 MB and adding `httpkit` ran ~1 MB — Apache httpclient in a native image lands somewhere in the **4–6 MB** native code range once reflection metadata, SSL paths, and unused-but-not-strippable code are accounted for.

### 6.2 What `java.net.http` actually costs

`java.net.http` is a JDK module. It is *already* in the image because `cognitect.aws.http.java` uses it — `main.clj` pre-requires `cognitect.aws.http.java` precisely to pin the cognitect HTTP backend to the JDK client. Babashka's measurement says java.net.http alone adds ~4.8 MB on a 65 MB binary; on our larger 115 MB binary the marginal cost is **~3–4 MB** (some shared infrastructure with SSL provider code, IPv4/v6 stack, etc., amortises with the rest of the JDK we already ship).

The crucial point is that **we are paying that 3–4 MB already**. Removing clj-http means we no longer pay Apache's separate 4–6 MB on top.

### 6.3 What we'd lose

`clj-http`'s feature surface that we actually use, by call site (`grep -n 'http/post\|http/get'` across `components/`):

- `components/clj-llm/src/.../llm.clj` — 6 calls (`http/post` for chat completions and embeddings, all with `:body`, `:headers`, `:as :string` or `:as :reader` for SSE). Uses a `reusable-conn-manager` (`clj-http.conn-mgr/make-reusable-conn-manager`) with `:timeout 30`, `:threads 4`, `:default-per-route 4`, `:insecure?`. Uses `:proxy-host`/`:proxy-port` derived from `https_proxy`. Streaming via `:as :reader` returns a `BufferedReader` consumed by `sse/process-{openai,anthropic}-stream`. Throws on non-2xx via `:throw-exceptions true`, with `:status` in ex-data — code at `llm.clj` line 167 reads `(-> (ex-data e) :status)` and `(get-in [:headers "retry-after"])` for retry/backoff.
- `components/clj-llm/src/.../oauth.clj` — 2 calls (`http/post` for token exchange + refresh). Headers, form-encoded body, no streaming.
- `components/agent/src/.../mcp/client.clj` — 4 calls (`http/post`, `http/delete`) for HTTP+SSE transport to MCP servers. Headers, JSON body, streaming response.
- `components/prometheus/src/.../client.clj` — `http/get`/`http/post` (out of scope; not in `agent-tui-app` deps).
- `components/slack/src/.../api.clj` — `http/post`/`http/get` (out of scope; not in `agent-tui-app` deps).

What we do *not* use: cookie store, NTLM auth, basic-auth helpers, automatic redirect to non-HTTPS, kerberos, digest auth, mime4j multipart parsing on responses. The conn-manager is real but small — `java.net.http`'s built-in pooling subsumes it (HTTP/2 multiplexing + per-host pool size via `HttpClient.Builder`).

### 6.4 Migration shape

We add a tiny in-repo HTTP wrapper at `components/clj-llm/src/ai/brainyard/clj_llm/http/native.clj` (or — better, since `agent.mcp.client` also calls it — a top-level `components/clj-http-native` shared component). The wrapper's surface is intentionally narrow: `(post url opts)`, `(get url opts)`, `(delete url opts)`, returning `{:status :headers :body}` and throwing `ex-info` with `:status` and `:headers` on non-2xx (matching what `retry-with-backoff` already destructures).

Internally:

```clojure
(defonce ^:private !shared-client
  (delay
    (-> (HttpClient/newBuilder)
        (.version  HttpClient$Version/HTTP_2)
        (.connectTimeout (Duration/ofSeconds 10))
        (cond-> (System/getenv "https_proxy")
          (.proxy (ProxySelector/of …)))
        (cond-> (= "true" (System/getenv "CLJ_HTTP_INSECURE"))
          (.sslContext (insecure-ssl-context)))
        (.build))))

(defn post [url {:keys [headers body as throw-exceptions]}]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (Duration/ofSeconds 60))
                (.POST (HttpRequest$BodyPublishers/ofString body))
                (apply-headers headers)
                (.build))
        handler (case as
                  :reader (HttpResponse$BodyHandlers/ofInputStream)
                  (HttpResponse$BodyHandlers/ofString))
        resp (.send @!shared-client req handler)
        status (.statusCode resp)]
    (if (and throw-exceptions (>= status 400))
      (throw (ex-info (str "HTTP " status)
                      {:status status
                       :headers (->clj (.headers resp))
                       :body (.body resp)}))
      {:status status
       :headers (->clj (.headers resp))
       :body (cond-> (.body resp)
               (= as :reader) (-> InputStreamReader. BufferedReader.))})))
```

The streaming variant returns the raw `InputStream` (wrapped in a `BufferedReader` to match what `sse/process-openai-stream` expects). All three caller files (`llm.clj`, `oauth.clj`, `mcp/client.clj`) change at the `require` line plus one or two destructuring tweaks per call site. The wrapper is < 200 LOC; the rewrite is mechanical.

### 6.5 Why not `babashka/http-client`?

`babashka/http-client` is also built on `java.net.http`, GraalVM-friendly, and exposes a closer-to-clj-http API. We could adopt it instead of writing our own wrapper. Trade-off:

| Choice                 | Pros                                                                | Cons                                                            |
|------------------------|---------------------------------------------------------------------|-----------------------------------------------------------------|
| Hand-rolled wrapper    | < 200 LOC, no new dep, exact behaviour we need, owned in-tree.      | Need to maintain SSE handling, proxy/insecure flags ourselves.  |
| `babashka/http-client` | Battle-tested, used by Babashka under native-image, clj-http-ish API. | Adds a dep; we still ship a thin shim because retry semantics + active-stream-register live in `llm.clj`. |

Recommendation: start with the hand-rolled wrapper. It is < 1 day's work, every line is justified by an existing call site, and we keep zero indirection for the perf-critical SSE path. If we later add HTTP usage outside `clj-llm` / `mcp` (e.g. for a new HTTP-backed tool), revisit and consider switching to `babashka/http-client`.

### 6.6 Expected image-size impact

Conservative estimate:

| Source                                                | Saving        |
|-------------------------------------------------------|---------------|
| Remove Apache `httpclient` + `httpcore`               | 3–4 MB        |
| Remove `commons-codec` + `commons-logging` + `mime4j` | 0.5–1.0 MB    |
| Drop NTLM / SSL reflection entries from `reflect-config.json` | 50–150 KB metadata |
| Drop `META-INF/services/org.apache.commons.logging.LogFactory` from `resource-config.json` | negligible byte count, removes a startup classpath scan |
| **Net**                                               | **~4–5 MB**   |

Plus removal of `--initialize-at-run-time=org.apache.http.impl.auth.NTLMEngineImpl` from `native-image.properties`, the deprecated `slingshot` exception sugar, and one fewer reason to keep `--report-unsupported-elements-at-runtime`. The migration is the single largest size win available without giving up a feature.

### 6.7 Decision

**Yes, replace `clj-http` with a `java.net.http` wrapper.** Rationale:

1. We are *already paying* for `java.net.http` (cognitect.aws.http.java). Apache HttpComponents is pure additional weight.
2. The clj-http feature surface we use is small and easy to reproduce.
3. The migration removes the largest single source of GraalVM-specific reflection / class-init headaches in the project (NTLM, SSL providers, JCL bridges).
4. It is straightforward and reversible — each call site has 1–2 LOC churn.

This work is **Phase 2** in §13. It does not block Phase 1 (graal-build-time + tracing-agent refresh), so we can ship the build-cleanness work first and treat the size reduction as a follow-up.

Sources: <https://github.com/babashka/http-client>, <https://clojurians-log.clojureverse.org/babashka/2021-08-28> (size budget thread).

---

## 7. Class-Initialization Policy

The new policy, written in plain terms:

- **Clojure-generated classes** (every `*__init`, every `*$fn__*`, every AOT-compiled record/proxy) are initialized at **build time**, via the `clj-easy/graal-build-time` Feature. We add the dep to `projects/agent-tui-app/deps.edn` and pass `--features=clj_easy.graal_build_time.InitClojureClasses` in `native-image.properties`.
- **Java standard library** is initialized at build time by default (GraalVM's own decision). We do not override.
- **Third-party Java libraries** are initialized at **run time** unless we have a specific reason to build-init them. Listed below.

Explicit `--initialize-at-build-time=<pkg>` entries (justified individually):

| Package / class                                          | Why build-time                                                                                  |
|----------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| `com.brunobonacci.mulog.*`                               | mu/log's macros expand to var refs that need stable class identity at AOT time.                  |
| `clojure.tools.logging.*`                                | (Pulled by mulog/slf4j bridge.) Var-based dispatch; safe to build-init.                          |
| `org.slf4j.LoggerFactory`                                 | Resolves the SLF4J binding once; baking it in saves a startup classpath scan.                    |
| `ch.qos.logback.classic.*`                                | We *want* the logback config baked in. Plus a `META-INF/services/...Configurator` resource entry.|
| `clojure.core.async.impl.protocols`                       | Protocol metadata; build-init avoids first-call dispatch quirks.                                 |
| `cognitect.aws.client.api`, `cognitect.aws.http.java`, `cognitect.aws.protocols.rest-json` | Pre-required from `main.clj`; build-init keeps the AOT classes consistent. |

Explicit `--initialize-at-run-time=<class>` entries:

| Class                                                     | Why run-time                                                                                |
|-----------------------------------------------------------|----------------------------------------------------------------------------------------------|
| `org.sqlite.JDBC`                                          | Library calls `System.loadLibrary` lazily; must be deferred. (Plus exportPath at build time.)|
| `java.security.SecureRandom`                               | The default; called out for clarity. The image must not freeze a seed.                       |
| `java.util.logging.LogManager`                             | JUL configuration is environment-dependent (we use `helpers/suppress-jul-cookie-warnings!`). |
| `sun.net.www.protocol.http.HttpURLConnection$DefaultHostnameVerifier` | Reads system properties at first use.                                            |

Once clj-http is gone (§6), `org.apache.http.impl.auth.NTLMEngineImpl` is removed from this list.

Where do we put these flags? In `projects/agent-tui-app/resources/META-INF/native-image/ai.brainyard/agent-tui-app/native-image.properties`. The file is auto-discovered by `native-image` when the jar contains it under `META-INF/native-image/<groupId>/<artifactId>/`. `bb.edn` no longer passes init-time flags on the command line, so there is one source of truth.

---

## 8. Reflection, Resource, and Proxy Metadata

We replace the hand-curated 2 800-line `reflect-config.json` with two sources:

**(a) `clj-easy/graal-build-time` + `clj-easy/graal-config`.** The latter is a community-maintained set of Clojure-library reflection configs (<https://github.com/clj-easy/graal-config>). We add it as a build-only feature dependency. For libraries it covers (data.json, core.async, etc.), our hand-curation goes away.

**(b) GraalVM Reachability Metadata Repository.** Recent native-image picks up community metadata for popular Java libraries automatically when the build is told to (`--exclude-config` / `-H:ConfigurationFileDirectories`). We enable `-H:ReachabilityMetadataRepositoryPath=…` to pull metadata for `logback-classic`, `slf4j-api`, `sqlite-jdbc`, etc. Reference: <https://github.com/oracle/graalvm-reachability-metadata>.

**(c) A committed tracing-agent run for the gaps.** What (a) and (b) don't cover — primarily the agent's own reflection (every `defagent`'s `__init` class plus its source `.clj` resource entry, and any first-party `requiring-resolve` paths) — is captured by a *single* tracing-agent run, committed under `projects/agent-tui-app/resources/META-INF/native-image/ai.brainyard/agent-tui-app/generated/`. The harness:

```
projects/agent-tui-app/dev/tracing-harness.clj   ; checked into the project, not a per-developer file
projects/agent-tui-app/Makefile.tracing           ; or bb tracing:ata
```

The harness boots `main.clj` and runs a scripted scenario that touches every agent and every code path we care about: `by agents`, `by models`, `by ask` against a stubbed provider, `by run -a research-agent` against a stubbed prompt that flows through coact / explore / plan / todo / exec / eval / update, plus an MCP stdio transport echo and one HTTP+SSE handshake against a local mock. The agent emits `META-INF/native-image/ai.brainyard/agent-tui-app/generated/{reflect,resource,proxy,serialization}-config.json`. The bb task verifies the four files are non-empty and `git diff`-clean against the committed snapshot; CI fails on drift.

Refreshing the trace is a documented per-PR step *only when the PR adds a defagent / changes the sandbox bindings / adds a new HTTP target / adds a new MCP transport*. For ordinary changes (new helper, refactor inside an agent) the trace does not need re-running.

This is the single largest reduction in operational toil: today a new defagent's omission silently breaks the binary; with the harness committed it either fails the trace-diff CI check or the new defagent's class entries are picked up automatically.

---

## 9. `bb.edn` and Build-Pipeline Changes

The new `compile:ata` adds an `ai.brainyard.agent-tui-app.tracing-loader` to its `compile` set (a tiny ns that `:require`s every namespace that `main.clj` touches transitively, plus the cognitect aws-api dynaload targets). Without it the AOT pass may skip classes that are only reached via the runtime resolution path. The ns is < 30 lines, defines nothing of its own, and exists only to widen the AOT graph.

The new `uberjar:ata` adds a post-build verification: walk the produced jar, assert `META-INF/native-image/ai.brainyard/agent-tui-app/native-image.properties` exists and is non-empty, fail otherwise. This catches uberdeps regressions before native-image is even invoked.

The new `native:ata`:

```clojure
native:ata
{:doc "Build native binary 'by' with GraalVM native-image"
 :task (do
         (println "Building native binary 'by' ...")
         (let [ni-path (find-native-image-binary)
               dir    "projects/agent-tui-app"
               start  (System/currentTimeMillis)
               result (shell {:continue true :dir dir}
                             ni-path
                             "--features=clj_easy.graal_build_time.InitClojureClasses"
                             "--no-fallback"
                             "--enable-native-access=ALL-UNNAMED"
                             "--enable-url-protocols=https"
                             "-H:Name=target/by"
                             "-H:BuildOutputJSONFile=target/native-build.json"
                             "-jar" "target/agent-tui-app.jar")]
           …))}
```

What's gone: the global `--initialize-at-build-time` (now in `native-image.properties` with explicit packages), `--enable-url-protocols=http` (TLS-only by default), command-line / META-INF flag duplication.

What's added: `--features=clj_easy.graal_build_time.InitClojureClasses`, `-H:BuildOutputJSONFile` for per-build size tracking (CI parses this and gates merge on a budget).

`bb build:ata` chains the three unchanged. `bb install:ata` is unchanged.

A new `bb tracing:ata` runs the tracing harness and writes the four `generated/*.json` files. A new `bb check:ata` does a `git diff --exit-code` on those files plus a `wc -l` ceiling check (so a runaway trace doesn't silently triple the config size).

---

## 10. Tracing Harness — What It Exercises

The harness lives at `projects/agent-tui-app/dev/tracing/scenarios.clj` and is invoked by:

```bash
java -agentlib:native-image-agent=config-output-dir=projects/agent-tui-app/resources/META-INF/native-image/ai.brainyard/agent-tui-app/generated \
     -jar projects/agent-tui-app/target/agent-tui-app.jar \
     dev:trace
```

`dev:trace` is a hidden cli-matic subcommand registered only when the `dev` alias is on the classpath (`bb tracing:ata` adds it). The scenarios it runs:

1. `by agents` — exercises `agent.interface` and every defagent's `__init`.
2. `by models` — exercises `clj-llm/get-popular-models`.
3. `by ask -p stub -m haiku 'hello'` against an in-tree stub LLM (drop a fake `:stub` provider in `clj-llm/providers.clj` keyed on env var). Walks coact's full BT cycle for one iteration.
4. An MCP stdio transport handshake against `bases/acp-stub-agent` (already in-tree).
5. One HTTP+SSE handshake against a `:stub` provider that returns a chunked SSE body — covers the streaming path through `sse/process-{openai,anthropic}-stream`.
6. SCI evaluation of a sandbox program that calls every auto-bound registry tool category (read-file, search, ask-user) — exercises `clj-sandbox` reflection.
7. SQLite open / FTS5 query / close — exercises `next.jdbc` + `sqlite-jdbc`.
8. mulog file-publisher start / one event / stop.
9. Datomic and the web/electric/replicant bases are NOT exercised (they are not in `agent-tui-app/deps.edn`).

The harness is the documented contract: if a new feature in `by` does something not on this list, the PR adds a scenario.

---

## 11. Source-Level Cleanups

A few small changes in the components let the static analyser do its job without metadata.

**`components/clj-llm/src/.../llm.clj` line 333** — replace the `try-require + resolve` for OAuth with a static require at top-of-file (guarded by feature flag if we don't want OAuth on by default; otherwise a plain `:require`). The `resolve` keeps working; the metadata stops being needed.

**`components/clj-llm/src/.../bedrock.clj` lines 26–44** — keep the `safe-require-resolve` pattern (it documents the contract that AWS is optional). Add a comment pointing at `main.clj`'s pre-load block as the canonical inclusion site.

**`components/agent/src/.../interface.clj` lines 37–58** — already exhaustive. Add a `clj-kondo` hook that flags any new `*-agent` source file not listed here.

**`components/util/deps.edn`** — move `talltale/talltale` to the `:test` alias.

**`components/clj-llm/deps.edn`** + **`components/agent/deps.edn`** — replace `clj-http/clj-http` with the new shared `ai.brainyard/clj-http-native` component (Phase 2).

**`bases/agent-tui/src/.../output_sink.clj` line 54** — `(Class/forName "[C")` for primitive `char[]`. Native-image handles this via array-class entries in `reflect-config.json` (already present — the file's first 10 entries are `[B`, `[C`, `[D`, `[F`, `[I`, `[J`, `[Ljava.lang.Object;`, `[Ljava.lang.String;`, `[S`, `[Z`); confirm the tracing harness emits them. No source change.

**`projects/agent-tui-app/src/.../main.clj`** — add explicit `:require` entries for any namespace that the harness flags as unreachable. Today's `main.clj` already has the cognitect block (lines 19–33); we extend the same pattern, and document the contract in a comment.

---

## 12. Size Budget — Where We Are vs. Where We're Going

Today (per `docs/build-and-deploy.md`): ~115 MB on arm64.

Per-component rough estimate of the existing image's composition (after dead-code elimination):

| Region                                           | Estimated MB |
|--------------------------------------------------|-------------:|
| GraalVM JDK runtime + GC + class-data + Substrate VM | 30–35        |
| SCI + edamame + Clojure runtime                  | 12–15        |
| `cognitect.aws.{api,bedrock-runtime,sts,iam,endpoints}` | 14–16        |
| `clj-http` + Apache HttpComponents + commons-* + mime4j | 5–7          |
| `mulog` + `slf4j` + `logback-classic`             | 5–6          |
| `next.jdbc` + `sqlite-jdbc` (native lib bundled)  | 6–7          |
| `metosin/malli` + `core.async` + `data.json`      | 6–8          |
| `talltale` + `inflections` + `encore` + `timbre`  | 3–4          |
| First-party Clojure (agent, clj-llm, clj-sandbox, agent-tui, etc.) | 10–14        |
| Padding / debug / heap-snapshot images / SVM internals | 5–8          |
| **Total**                                         | **~115**     |

Targeted reductions:

1. **clj-http removal (Phase 2):** ~5 MB.
2. **Talltale to test-only (Phase 1):** ~2 MB.
3. **Disabling `-march=compatibility` for the macOS dev build, using `-march=native`:** ~1–2 MB (host-specific code paths only; CI keeps `compatibility` for release artefacts).
4. **`--gc=serial`** (currently the default; confirm we are not pulling G1) and **`-O2`** instead of `-Ob` for release: ~1 MB.
5. **`-Dorg.sqlite.lib.exportPath` at build time:** small image-size saving, larger cold-start saving.
6. **`-H:+InlineBeforeAnalysis` and `-H:+ProtectionKeys`:** known to shave 1–3% on heavy Clojure builds.

Aggregate target: **~85–90 MB** on arm64 / linux-x86_64. Tracked in `target/native-build.json` over time.

---

## 13. Phased Rollout

**Phase 0 — Baseline.** Before touching anything, run `bb build:ata` on a clean checkout and record: (a) does it succeed, (b) size, (c) cold-start latency, (d) does `bb tui agents` against the binary list the same agents that `bb tui agents` against the JVM lists. Commit the numbers to `docs/build-and-deploy.md` as the "before".

**Phase 1 — Build cleanliness (no behaviour change).**

1. Add `com.github.clj-easy/graal-build-time {:mvn/version "1.0.5"}` (or current) to `projects/agent-tui-app/deps.edn`.
2. Rewrite `native-image.properties` with explicit `--initialize-at-build-time=<pkg>` and `--initialize-at-run-time=<class>` lists; drop `AllowDeprecated…` and `--report-unsupported-elements-at-runtime`.
3. Add `--features=clj_easy.graal_build_time.InitClojureClasses` (in `native-image.properties`).
4. Move all command-line flags from `bb.edn` `native:ata` into `native-image.properties`; reduce `bb.edn` to `native-image -jar … -H:Name=…`.
5. Add the tracing harness (`bb tracing:ata`, `dev/tracing/scenarios.clj`).
6. Replace hand-curated `reflect-config.json` / `resource-config.json` / `reachability-metadata.json` with the harness output in `generated/`. Keep a hand-edited overlay in `manual/` for the irreducible bits (e.g. `[C` array entries).
7. Add `bb check:ata` (git-diff + line-count ceiling).
8. CI: gate PRs on `bb build:ata` + `bb check:ata`.

Expected outcome: build is reproducible. No size change. Binary should be identical-ish in behaviour. Acceptance: every smoke-suite scenario in §10 passes.

**Phase 2 — HTTP-client migration.**

1. Add new `components/clj-http-native` component (or a single `clj-llm/http/native.clj` if we don't want a new component). Implement `post`, `get`, `delete`, plus streaming variant.
2. Migrate `components/clj-llm/src/.../llm.clj` and `oauth.clj` and `components/agent/src/.../mcp/client.clj` to the new wrapper.
3. Drop `clj-http/clj-http` from `components/clj-llm/deps.edn` and `components/agent/deps.edn`.
4. Drop `org.apache.http.impl.auth.NTLMEngineImpl` from `native-image.properties` `--initialize-at-run-time`.
5. Drop `META-INF/services/org.apache.commons.logging.LogFactory` from `resource-config.json` (or rather, do not regenerate it — `clj-http`'s dep on commons-logging is what put it there).
6. Re-run tracing harness; commit updated `generated/*.json`.
7. CI checks: size budget regression test (require < 90 MB).

Expected outcome: **~5 MB smaller binary**, simpler reflect-config, fewer init-at-run-time entries, no behaviour change.

**Phase 3 — Polish.**

1. Move `talltale` to test-only.
2. Enable `-H:BuildOutputJSONFile` and add a CI step that posts the size delta to the PR.
3. Document the steady-state developer workflow in `docs/build-and-deploy.md` (replace the existing "Regenerating these is usually done with GraalVM's native-image-agent" paragraph with the new bb-task-based flow).
4. (Optional) Investigate `-H:+ProtectionKeys`, `--gc=epsilon` for short-lived `by ask` invocations, and `--initialize-at-build-time=ai.brainyard.*` once we trust the static graph.

Each phase ships independently. Phase 1 is the prerequisite — Phase 2 and 3 can be reordered or run in parallel.

---

## 14. Risks and Mitigations

**Risk: tracing harness misses a code path.** A new agent introduces a `requiring-resolve` to a namespace that the scenarios never trigger; the binary builds, ships, and crashes on a user's first invocation of that agent.

*Mitigation:* the smoke suite in CI runs every defagent via `bb tui agents` (which calls `defagent-type` on each registered agent and therefore exercises the entry's `__init`). For agents whose runtime path differs from listing, we add an explicit scenario. A `clj-kondo` rule flags any `requiring-resolve` whose target ns is not statically required somewhere in `agent-tui-app`'s deps.

**Risk: `clj-easy/graal-build-time` over-eagerly build-inits a Clojure ns that has live state in `<clinit>`.** Some Clojure code legitimately wants to capture a `Date` or a `ServerSocket` at namespace load; build-init would freeze a stale value.

*Mitigation:* `graal-build-time` only targets `*__init` classes by extension; it does not build-init e.g. record classes that the ns defined. If we find a real conflict, the standard escape hatch is `--initialize-at-run-time=ai.brainyard.<offender>` in `native-image.properties`. We expect zero such cases in the current codebase based on a grep for `defonce` with live state (everything in the repo uses `delay` or atoms-of-nil, which are safe).

> **Empirical update (Phase 1, 2026-05-15).** Enabling
> `--features=clj_easy.graal_build_time.InitClojureClasses` on a clean
> checkout *failed the build* with "tool-fn must be a function in deftool:
> search-agent" at `components/agent/.../search_agent.clj:156`. Root cause:
> 14 of our 18 `defagent` forms reference a cross-namespace Var at
> top-level (e.g. `(defagent search-agent "..." coact/run-coact-derived
> ...)`). Under build-time class-init, `search_agent__init.<clinit>` runs
> before `coact_agent__init.<clinit>` has fully completed the `(defn
> run-coact-derived ...)` it depends on, so the Var resolves to an
> unbound state and `deftool`'s `(fn? tool-fn)` runtime check throws. The
> require graph isn't enough — GraalVM's class-init order is independent
> of Clojure's load order, so a `require` triggered partway through
> `<clinit>` finds the target namespace already mid-init.
>
> **Workaround shipped in Phase 1:** `graal-build-time` stays on the
> classpath but the `InitClojureClasses` feature is *not* activated. We
> rely on the explicit `--initialize-at-build-time=<pkg>` allow-list for
> the libraries that genuinely benefit (mulog, slf4j, logback,
> tools.logging, core.async.impl.protocols, cognitect.aws.*). All
> first-party `ai.brainyard.*` Clojure namespaces stay at run-time init —
> same effective policy as the pre-Phase-1 baseline.
>
> **Properly fixing this is a separate refactor:** either (a) change the
> `deftool` macro to defer the `tool-fn` resolution behind a `delay` so
> the `fn?` check runs at first invocation rather than at namespace
> load, or (b) change every derived defagent call site to pass `#'<var>`
> (a Var literal) instead of the symbol form. Either approach removes
> the build-time evaluation dependency on cross-namespace Var bindings.
> Worth a sibling design doc; not blocking Phase 1.
>
> **Follow-up — applied 2026-05-15.** Option (a) shipped at
> `components/agent/src/ai/brainyard/agent/core/tool.clj:136`. The
> `(fn? tool-fn)` check moved from the let-binding into the wrap-fn
> body, so cross-namespace Vars are dereferenced at first invocation
> rather than at namespace-load. With this fix the defagent obstacle
> is gone, BUT `--features=InitClojureClasses` *still* breaks the
> build — on a different `(set! *warn-on-reflection* true)` failure
> in `next.jdbc.*` and `clojure.stacktrace` (graal-build-time 1.0.5
> hardcodes an exclusion for `clojure/` only; `next/` is swept into
> its build-init list and conflicts with our run-time-init override).
> The deftool fix DID unlock the Phase 2 clj-http removal under the
> deprecated init policy: applying the migration plus a one-line
> `--initialize-at-run-time=clojure.stacktrace__init` workaround
> delivered the predicted ~5 MB binary win (actual: 7.65 MB), bringing
> the binary from 161.31 MB → 153.65 MB (146.5 MB on-disk). See
> `docs/build-and-deploy.md` §"Task #20 outcome".
>
> **Phase 3 — also resolved 2026-05-15.** The "still open" `next.jdbc`
> obstacle in (2) turned out to be NOT structural — it surfaced only
> because the broader reflection cleanup (task #22) hadn't happened
> yet. After the reflection sweep, retrying `--features=InitClojureClasses`
> with the explicit allow-list lit the build green:
>
> ```
> Args = --features=clj_easy.graal_build_time.InitClojureClasses \
>        --initialize-at-build-time=clojure.lang.XMLHandler \
>        --initialize-at-build-time=com.brunobonacci.mulog \
>        --initialize-at-build-time=clojure.tools.logging \
>        --initialize-at-build-time=org.slf4j \
>        --initialize-at-build-time=ch.qos.logback \
>        --initialize-at-run-time=org.sqlite.JDBC \
>        --initialize-at-run-time=clojure.stacktrace__init \
>        --no-fallback \
>        --enable-native-access=ALL-UNNAMED \
>        --enable-url-protocols=http,https \
>        -H:+ReportExceptionStackTraces \
>        -H:BuildOutputJSONFile=target/native-build.json \
>        --strict-image-heap \
>        -march=compatibility
> ```
>
> What dropped: `-H:+AllowDeprecatedInitializeAllClassesAtBuildTime`,
> bare `--initialize-at-build-time`, `--report-unsupported-elements-at-runtime`.
> Notable additions: `--strict-image-heap` (recommended by GraalVM 21
> build report for forward compat), `clojure.lang.XMLHandler` as a
> single-class build-init carve-out (graal-build-time's `clojure`
> directive covers the root package but not `clojure.lang.*`).
> Cost: +0.44 MB (the strict init policy is slightly more aggressive
> than the deprecated one, so image heap grew marginally). Tradeoff
> accepted — modern policy is forward-compatible with future GraalVM
> versions where the deprecated form will be removed.

**Risk: HTTP wrapper has a subtle behavioural difference from clj-http.** SSE backpressure, proxy semantics, or 100-Continue handling.

*Mitigation:* unit tests at `components/clj-llm/test/.../http_native_test.clj` cover every call site against a `wiremock`-style local HTTP server. The streaming test reproduces the Anthropic SSE shape and asserts that `sse/process-anthropic-stream` produces identical output to the clj-http path on a fixture.

**Risk: cold-start regression.** Some of the changes (lazier init-at-run-time) could *slow* startup if we move too many classes off build-time.

*Mitigation:* `target/native-build.json` includes startup timings; CI compares against a baseline and gates merge on regression > 50 ms.

**Risk: cross-platform divergence.** macOS arm64 and linux-x86_64 may surface different metadata gaps because the AWS HTTP client picks a different SSL provider, or because the SQLite JNI bundles different native libraries.

*Mitigation:* CI runs the smoke suite on both platforms. The tracing harness is run on both and the union of outputs is committed.

**Risk: GraalVM version churn.** Upgrading from GraalVM 25 to 26 may flip `--initialize-at-build-time` semantics, deprecate a flag, or change the metadata format.

*Mitigation:* `.sdkmanrc` pins the version; the README documents the supported range; CI's GraalVM is pinned to the same. Upgrades are an explicit, separate PR with smoke-suite + size-budget verification.

---

## 15. Open Questions

**(a) Should we keep `clj-http` available behind an opt-in for components that are not in `agent-tui-app` (prometheus, slack)?** Those components live in the monorepo and may be pulled by `agent-web-app` later. We propose: keep `clj-http` in their `deps.edn`, but exclude it transitively from `agent-tui-app` by NOT migrating those callers in this PR. The `agent-tui-app` build does not depend on prometheus or slack — confirmed by reading `projects/agent-tui-app/deps.edn`.

**(b) Do we want `babashka/http-client` as a fallback in case our hand-rolled wrapper turns out to be missing a feature?** Defer to Phase 2 review. If the wrapper is < 200 LOC and well-tested, no. If it grows to handle multipart uploads or cookie jars, switch.

**(c) Static-PIE / musl build?** Not in scope. Would shave another 5–10 MB but introduces a separate SSL-provider headache (the JDK's TLS implementation against musl has known quirks) and a new CI lane.

**(d) Is it worth a `:type :agent` registry persistence mechanism so that `bb tui agents` can list agents at runtime without statically requiring every defagent ns?** That would let us drop the `agent.interface` static-require list and let agents be lazy-loaded from a manifest. Substantial refactor; out of scope for this design. Worth a sibling design doc.

---

## 16. Summary

- The build pipeline at `bb build:ata` is mostly in place but uses deprecated GraalVM mechanisms and hand-curated reflection metadata that is silently drifting from the codebase.
- Adopting `clj-easy/graal-build-time` plus a committed tracing-agent harness replaces both with a reproducible, CI-checked recipe.
- The largest size win available without sacrificing features is removing `clj-http`. Since `cognitect.aws.http.java` already brings `java.net.http` into the image, the Apache HttpComponents stack is pure redundant weight (~5 MB). Migrating the seven call sites in `components/clj-llm` and `components/agent` to a < 200 LOC `java.net.http` wrapper is straightforward.
- A three-phase rollout (build cleanliness → HTTP migration → polish) gets us to a reproducible, ~85 MB `by` binary with documented per-PR procedure for refreshing native-image metadata when an agent / sandbox-binding / HTTP target / MCP transport is added.

See also: `docs/build-and-deploy.md` (will be updated in Phase 3 to match this design), `CLAUDE.md` §"GraalVM Native Build" (canonical bb-task list), the GraalVM reachability metadata docs at <https://www.graalvm.org/latest/reference-manual/native-image/metadata/>, and the community reachability-metadata repository at <https://github.com/oracle/graalvm-reachability-metadata>.
