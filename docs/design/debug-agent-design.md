# Debug-Agent — Live-Runtime Specialist (CoAct-derived)

> **Status:** Shipped. This doc was **rewritten 2026-07** to describe the
> agent as-built. The earlier design (grant/scope/drift/confirmation gates, a
> `debug$promote-hot-patch` artifact, an edit-agent hand-off, and a TUI drift
> chip) has been **removed from the code** — that machinery is recorded in §14
> "History" for archaeology, not as current behavior.
>
> **Scope:** `components/agent/src/ai/brainyard/agent/common/debug_agent.clj` —
> a CoAct-derived specialist that drives a live-runtime self-debugging loop
> against the running brainyard JVM over clj-nrepl.
> **Built on:** `coact_agent.clj` (`coact/run-coact-derived`),
> `ai.brainyard.clj-nrepl.interface` (loopback nREPL — full-trust, deny-list
> only), `ai.brainyard.agent.common.code-eval` (the unified `code$eval`
> surface), and the per-instance config layer in `agent.core.config`.
> **Sibling of:** `explore-agent`, `exec-agent`, `edit-agent`, `plan-agent`,
> `todo-agent`.
> **Related reading:** `docs/design/clj-nrepl-eval.md` (the substrate — server,
> classifier, native-image constraint), `docs/design/exec-agent-design.md`
> (sibling CoAct specialist, structural template), `docs/design/playground-design.md`
> (the Docker workspace where debug-agent actually runs — see §12), `docs/CoAct.md`.

---

## 1. What it is

Debug-agent is the specialist that turns the *substrate* shipped by
`clj-nrepl-eval` — a loopback nREPL server and an `:nrepl` backend on
`code$eval` — into a *good* live-runtime workflow. The substrate makes the
agent *able* to evaluate code in the live JVM; debug-agent makes it *fluent* at
reproducing faults, reading the real image, and landing durable fixes.

It handles **three jobs, end-to-end, in one agent**:

- **(A) DEBUG** a fault in the running system — reproduce, probe, hypothesize,
  validate a fix live.
- **(B) UNDERSTAND** how brainyard works by reading the *real* image
  (namespaces, the tool/command/agent registry, config, hooks, live agents,
  source locations via `(meta #'var)`) instead of recalling from training.
- **(C) FIX** it permanently *itself* — once a patch is proven live, it edits
  the source file with the bound file tools and reloads the namespace over
  nREPL to confirm the on-disk version applies. **There is no hand-off to
  edit-agent.**

**Thesis.** A CoAct-derived defagent — inheriting CoAct's behavior tree, hooks,
and channel discipline — with three specialisations:

1. **Per-instance nREPL session**, opened in the `:agent.instance/created`
   hook and pinned on the instance config. Lets `(def reproducer …)` then
   `(probe reproducer)` work across iterations.
2. **`:clj-backend :nrepl`** on the instance config, so every ` ```clojure `
   fence routes to the live runtime by default (the fence takes only the
   language token — there is no per-block modifier).
3. **A live-JVM system-prompt section**, selected by `coact-system-context`
   from `:clj-backend`, that replaces the SCI-sandbox boilerplate with text
   describing live-JVM routing.

Debug-agent is **not** a replacement for the SCI sandbox backend — that is the
tool for *isolated* evaluation. Debug-agent is deliberately full-trust: it
operates *on* the live image.

---

## 2. Design principles

1. **Read the live image before guessing.** Any question about brainyard's
   behavior, config, tools, or wiring is answered by inspecting the running
   process directly. The tool-context ships a catalog of ready-to-run
   introspection snippets (§9).
2. **One nREPL session per instance, opened at creation.** Multi-turn
   investigation lives or dies on shared namespace state. Pinning the session
   at instance-creation keeps `:session` plumbing invisible to the LLM — it
   never manages session ids.
3. **Default to live, not sandbox.** The instance config writes
   `:clj-backend :nrepl`, so `coact`'s block dispatcher routes ` ```clojure `
   fences to the live runtime with no per-block modifier. Isolated evaluation
   is a *different agent* (the SCI-sandbox coact-agent), not a per-fence flag.
4. **Validate ephemerally, then commit to disk.** `def` / `alter-var-root` /
   `defmethod` mutate only the live image and die on restart — that is exactly
   why they are the *safe, reversible* way to prove a fix before the source
   edit makes it durable.
5. **Own the whole cycle.** The agent has the file tools bound
   (`read-file`/`update-file`/`write-file`/`grep`/`search`) plus `bash`/`task$run`
   for tests, so a validated fix becomes a committed source change **inside the
   same agent** — no artifact, no boundary, no second invocation.
6. **The prompt tells the truth.** CoAct's default Execution Model says
   "sandboxed Clojure interpreter (SCI)" — flat wrong here. The `:nrepl`-backend
   prompt section describes the live JVM: `System`, `Runtime`, full reflection,
   and every loaded namespace are reachable.
7. **Minimal, always-on safety.** nREPL is full-trust. The **only** eval-path
   check is a deny-list substring tripwire (§11). Real isolation is the SCI
   sandbox's job; blast-radius containment is the *deployment's* job (see the
   Docker playground, §12).
8. **CoAct for everything else.** The behavior tree, the three channels
   (tool-calls / code-blocks / answer), the hooks, the iteration discipline —
   all inherited via `coact/run-coact-derived` and the `:bt-factory` pin
   pattern used by `explore-agent` / `exec-agent`.

---

## 3. Position in the agent stack

```
                    operator (or main-agent, via `-a debug-agent` / `@debug-agent`)
                                        │
                                        ▼
                                  debug-agent
        (CoAct-derived · pinned nREPL session · :clj-backend :nrepl ·
         live-JVM system prompt · file tools bound for its own fixes)
                                        │
                ┌───────────────────────┼───────────────────────┐
                ▼                       ▼                       ▼
         code$eval :nrepl        read/update/write-file      bash / task$run
                │                (permanent source edit)     (run brick tests)
                ▼
      clj-nrepl loopback client  ── deny-list check (only gate) ──▶ LIVE brainyard JVM
                │
                ▼
   ephemeral validate (def / alter-var-root)  ──▶  edit source  ──▶  (require 'ns :reload)  ──▶  re-verify from source  ──▶  report
```

Within a single turn the loop is CoAct's:

```
debug-agent (iter N)
  → ```clojure block (auto-routed to :nrepl)
     → :clj-nrepl-eval task → loopback client
     → harvest {:result :output :error :ns} → CoAct accumulator
  → next iter sees prior :output / :result through :previous-turns
  → eventually the LLM edits source via file tools and/or emits :answer
```

The agent's output is the **source edit it makes directly** (tracked by git)
plus the `:answer` report — there is no per-session dossier and no promotion
artifact.

---

## 4. Per-instance lifecycle

`debug_agent.clj` registers two hooks on the shared `hooks` registry, both
filtered by `(= :debug-agent (proto/defagent-type agent))`:

**`:agent.instance/created`** (`on-instance-created`):
1. Write `:clj-backend :nrepl` to the instance's per-agent config.
2. If `clj-nrepl/running?`, open a server-issued session via
   `clj-nrepl/new-session` and write the id to `:nrepl-session-id`; mulog
   `::debug-agent-session-opened`.
3. If the server is **not** running, mulog `::debug-agent-no-server` and
   continue — the first code-eval will surface the gate error so the LLM can
   report it (or start the server itself via `clj-nrepl$start-server`, §5).

**`:agent.instance/closed`** (`on-instance-closed`):
1. Read `:nrepl-session-id`; `clj-nrepl/close-session` it (exceptions
   swallowed — the server may already be gone).

Both writes use `write-config!`, a direct `swap!` into
`:!state @st-memory-init :config`, **not** `config/set-config!` — the 2-/3-arity
`set-config!` also writes the global atom + `.brainyard/config.edn`, which would
leak instance-scoped state into global config. This mirrors the
`set-allowed-dirs!` pattern in `agent.core.config`.

`coact`'s block dispatcher reads this per-instance config generically:
`(config/get-config agent :clj-backend)` routes the block, and
`:nrepl-session-id` flows into the job-config as `:session` (forwarded to
`clj-nrepl/eval-string`). The system-prompt section follows from `:clj-backend`
— no debug-agent-specific code in CoAct; any future specialist can reuse the
same override mechanism.

**Server prerequisite.** The embedded nREPL server is enabled, in precedence
order, by: `.brainyard/config.edn` `:agent {:config {:nrepl-enabled? true}}`
(durable) · `BY_NREPL_ENABLED=true` (transient env-fallback of the same
`:nrepl-enabled?` key) · or `clj-nrepl$start-server` on demand (§5). The config
keys are just `:nrepl-enabled?` and `:nrepl-port` (`0` = ephemeral).

---

## 5. nREPL server lifecycle tools

Three commands let debug-agent manage the embedded server **on demand, without a
process restart**. They are gated to `debug-*` via `:tool-use-control {:allow
["debug-*"]}` and bound on the agent's `:agent-tools`:

| Tool | Behavior |
|---|---|
| `clj-nrepl$start-server` | Idempotent start (no-op if already up). Writes a per-instance port file `~/.brainyard/nrepl-ports/by-<pid>.port` so external CIDER tooling can attach to the **same** live image. Returns `{:running :port :port-file :already-running}`. |
| `clj-nrepl$stop-server` | Stops the server and removes the port file. Returns `:stopped false` when nothing was running. |
| `clj-nrepl$status` | `{:running :port :port-files}` — the live-runtime channel's state plus the inventory of known per-instance port files. |

**These MUST be called through the TOOL channel, never from a ` ```clojure `
block** — the code block is evaluated *by* the very server it would be trying to
start (a chicken-and-egg deadlock). The agent's tool-context leads with a
`debug-lifecycle-preamble` spelling this out: check `clj-nrepl$status` first;
only after it confirms the server is up do ` ```clojure ` blocks evaluate against
the live image.

---

## 6. Tool bag

From `debug_agent.clj`'s `:agent-tools`:

```clojure
:agent-tools {:tools [:code$eval
                      ;; Source editing — debug-agent makes its own permanent
                      ;; fixes (no edit-agent handoff).
                      :read-file :update-file :write-file :grep :search
                      :bash
                      ;; Background execution / inspection (e.g. running a
                      ;; brick's tests after a source edit).
                      :task$run :task$detail :task$list :task$cancel
                      :clj-nrepl$start-server :clj-nrepl$stop-server :clj-nrepl$status]}
```

| Tool | Role |
|---|---|
| `code$eval` | Reaches the live runtime. Default backend is `:nrepl` (per instance config). |
| `read-file` / `update-file` / `write-file` / `grep` / `search` | Source editing — the agent makes the validated live patch permanent by editing the file directly. |
| `bash` / `task$run` | Run a brick's tests / probes after a source edit. |
| `task$detail` / `task$list` / `task$cancel` | Long-running probes detach via the task manager; the LLM polls/cancels through these. |
| `clj-nrepl$start-server` / `stop-server` / `status` | Manage the embedded server lifecycle on demand (§5). TOOL-channel only. |

Notably **absent**: any `debug$promote-hot-patch` or `clj-nrepl$drift-markers`
tool — both were removed with the drift/promotion layer (§14).

---

## 7. Execution model (system prompt)

The `:nrepl`-backend Execution Model section is selected by
`coact-system-context` from the agent's `:clj-backend` config — setting
`:clj-backend :nrepl` on the instance is sufficient; no separate
`:execution-model` write is performed. In substance it tells the LLM:

- Blocks run against the **LIVE brainyard JVM** via clj-nrepl — not SCI.
  `System`, `Runtime`, `Thread/getAllStackTraces`, full reflection, every
  loaded namespace, and arbitrary interop are all reachable.
- **State persists across iterations**: a server-issued session is pinned per
  instance; `(def …)` / `(alter-var-root …)` survive for the agent's lifetime.
- **Output is captured**: `println` / `*out*` / `*err*` are returned in the
  next iteration. **Errors are non-fatal**: a throw shows up as `:error` on the
  eval entry; session state is preserved.
- **Deny-list only**: `System/exit`, `Runtime/.exec`, credential namespaces are
  rejected. There is **no** grant, scope, drift, or per-mutation confirmation.
- **No SCI shortcuts**: `context-get`, `(usage :foo)`, and bare
  tool-name-as-fn are SCI-only. From here, invoke registered tools via
  `call-tool` (§10) and use fully-qualified symbols.
- **No parallel mode**: the single live session cannot be forked, so multiple
  ` ```clojure ` fences in one turn run **sequentially** in the same session
  (each sees the prior blocks' defs/state). Do not emit `<!-- ParallelBlock -->`.

The full authoritative text lives in `debug_agent.clj`'s `debug-instruction`
and the `nrepl-guide` (registered into `agent.core.usage` as topic `:nrepl`,
`:scope :user`, and inlined into debug-agent's `:tool-context` — one string,
two consumers: debug-agent inline, plus `(usage$guide :topic :nrepl)` for any
other agent on demand).

---

## 8. The debug → fix loop (seven steps)

For a fault, `debug-instruction` prescribes:

1. **Reproduce** — bind the offending inputs to a var, call the failing
   function, read `*e` and the stack trace.
2. **Probe** — inspect related state (config, tool registry, hooks, atoms,
   agent sessions, the namespace where the symbol lives). Use
   `(meta #'the-var)` `:file`/`:line` to locate the source on disk.
3. **Hypothesize** — state the guess explicitly before testing.
4. **Validate live (ephemeral)** — `def` / `alter-var-root` / `defmethod` a
   replacement in the running image and re-run the reproducer. Confirms the fix
   *without* touching source — fast, reversible.
5. **Make it permanent** — once proven, edit the SOURCE file with the file
   tools (`read-file` for context, then `update-file` for a targeted change or
   `write-file` for a new file). The edit must match the validated patch.
6. **Reload + verify** — `(require 'the.ns :reload)` (or re-eval the changed
   form, or `(load-file "…")`) to pull the on-disk version into the live image,
   then re-run the reproducer to confirm the SOURCE fix — not just the ephemeral
   def — resolves it. Optionally run the brick's tests via `bash` / `task$run`.
7. **Report** — source path(s) edited, what changed, how it was verified.

**Reload discipline** (called out explicitly because it can corrupt a live
image): prefer single-namespace `:reload`, or re-eval the one changed form, or
`(load-file path)`. **Never `:reload-all` an interface namespace** — it rebuilds
protocols and orphans live record instances (e.g. running agents). `:reload` is
a flag, not a libspec key: `(require '[ns :as a] :reload)`, never inside the
libspec vector.

---

## 9. Introspection catalog (job B)

The tool-context ships a catalog of **non-destructive** snippets so the LLM
reads the system instead of guessing. Representative entries:

```clojure
;; Survey — every brainyard namespace, one ns's public vars, a var's source loc
(->> (all-ns) (map ns-name)
     (filter #(clojure.string/starts-with? (str %) "ai.brainyard")) sort)
(sort (keys (ns-publics 'ai.brainyard.agent.core.config)))
(select-keys (meta #'ai.brainyard.agent.core.config/get-config) [:file :line])

;; Registry — what brainyard can do
(count (ai.brainyard.agent.core.tool/get-tool-defs))
(sort (keys (ai.brainyard.agent.core.tool/get-tool-defs :type :command)))
(ai.brainyard.agent.core.tool/get-tool-defs :id :code$eval)

;; Config / hooks / live agents
(ai.brainyard.agent.core.config/get-config-snapshot)
(ai.brainyard.agent.core.hooks/list-hooks)
(ai.brainyard.agent.interface/list-agents)

;; Reproduce a fault / read runtime state
*e  (ex-message *e)  (ex-data *e)
(keys (Thread/getAllStackTraces))
@ai.brainyard.agent.core.tool/!tool-defs
```

Fully-qualify symbols (the session is the `user` ns) and slice big values
(`(take 20 …)`, `(keys …)`) rather than dumping.

---

## 10. Invoking registered tools from nREPL

Unlike the SCI sandbox, the `:nrepl` backend does **not** auto-bind registered
tools as kebab-case fns — `(some-tool {…})` hits Unable-to-resolve. Dispatch any
registered tool by id via `ai.brainyard.agent.core.tool/call-tool`, which
normalizes args, checks permissions, validates against the schema, and runs the
tool fn:

```clojure
(require '[ai.brainyard.agent.core.tool :as t])
(t/call-tool :list-tools {:pattern "^memory\\$"})
(t/call-tool :read-file {:path "components/agent/src/…/tool.clj" :lines [450 510]})
(t/call-tool :task$run  {:job-type :bash :command "ls -la .brainyard"})
```

**Agent-state tools** (`memory$*`, session, anything reading the running agent)
resolve `protocol/*current-agent*`, which is **nil on the nREPL thread** — each
eval runs unbound, and a `(binding …)` does not survive to the next eval. Pass
`:agent` per call; `call-tool` binds `*current-agent*` for you. Default to the
running debug-agent instance from the registry (the agent-id namespace *is* the
defagent type):

```clojure
(require '[ai.brainyard.agent.core.agent :as ag]
         '[ai.brainyard.agent.core.protocol :as proto])
(def dbg (first (filter #(= "debug-agent" (namespace (proto/agent-id %)))
                        (ag/list-agents))))
(t/call-tool :memory$status {} :agent dbg)
```

For internal fns (not registered as tools), call them directly by their
fully-qualified var — no `call-tool`.

---

## 11. Safety

nREPL is **full-trust**. Every earlier gate — grant (read-only/mutate/TTL),
scope-based mutating-heads classifier, first-mutation-per-session confirmation,
runtime-drift marking, and the associated audit events — was **removed** (§14).
What remains:

- **Deny-list substring tripwire** (`clj-nrepl.core.classifier`) — the *only*
  eval-path check, enforced unconditionally in the client gate. It rejects
  source containing `System/exit`, `Runtime/.exec`, `Runtime/getRuntime`,
  `shutdown-agents`, `java.lang.Runtime`, or the credential namespaces
  `ai.brainyard.aws-client` / `ai.brainyard.keycloak`. This is **best-effort
  defense** (raises the cost of an *accident*, not of a determined bypass) —
  explicitly NOT a sandbox.
- **Loopback-only socket** — the server binds `127.0.0.1`; there is no remote
  reach into the image.

For real isolation, the SCI sandbox backend is the tool. For blast-radius
containment of the full-trust path, the **deployment** is the boundary — which
is precisely why the Docker playground (§12) is the natural place to run
debug-agent.

---

## 12. Usefulness in the playground (Docker) workspace

The playground (`docs/design/playground-design.md`,
`deploy/playground-workspace/`) gives each tenant a **dedicated, disposable,
isolated container** running `by` from a browser terminal. For the **live-patch
+ introspect** half of the workflow (jobs A and B) it is close to the *only
practical* place — it makes full-trust live surgery safe. The **durable
source-fix** half (job C) is the exception: it needs a source checkout on the
classpath, which the packaged container does not provide — see the caveat in
point 1 and the dev-runtime note in §13b.

**1. The playground runs the JVM uberjar — the one environment where nREPL eval
works.** Per `clj-nrepl-eval` Principle 8, the GraalVM native binary "cannot
host a stock nREPL/SCI-driven REPL … (no runtime classloading/compilation)";
`clj-nrepl-eval` is a **JVM-mode capability** and on the native binary it
degrades to a clear "not supported in native image" error. The public `by`
install ships the *native* binary. The playground image, by design, runs the
**`by` uberjar on `eclipse-temurin:21-jre`** (Dockerfile header:
"runs the `by` UBERJAR on a stock JRE — NOT the native binary"). So the
container is exactly the runtime where:
- ` ```clojure ` blocks actually evaluate against the live image, and
- `(require 'ns :reload)` is *mechanically* able to recompile a namespace from
  its `.clj` — the uberjar bundles the sources alongside the AOT classes, so the
  `.clj` is on the classpath. On the closed-world native binary that reload is
  unsupported; on the JVM uberjar it is normal Clojure.

  **Caveat — which `.clj` a reload sees (why job C does NOT fully hold in the
  container).** The tenant's `by` runs as `java -jar /opt/by/by.jar`
  (`Dockerfile` `by` shim), so the process classpath is **the uberjar alone**,
  and brainyard's `.clj` sources exist *only inside that jar*. `/workspace` is an
  **empty `git init`-ed dir** (`entrypoint.sh`), not a brainyard checkout, and is
  **not on the classpath**. Consequences for fixing *brainyard's own* code:
  `(meta #'foo)` `:file` points at a path *inside the jar* with no on-disk twin;
  `update-file` (project-root-anchored to `/workspace`) can't reach or edit a
  file inside the jar; and `(require 'ns :reload)` reads the namespace from the
  classpath — i.e. the jar's **frozen** copy — so it would never reflect a
  `/workspace` edit anyway. The durable **edit-source → reload-from-source** loop
  (steps 5–6) therefore belongs to the **dev source-checkout runtime**
  (`bb tui` / `clojure -M …`, where the classpath *is* the component `src/`
  dirs), not the packaged jar/container. In the container the source-fix loop
  only works for a **tenant's own** Clojure project under `/workspace`, and only
  once its `src` is put on the eval's classpath (`load-file` / `add-classpath`).

**2. The container is the blast-radius boundary that makes full-trust safe.**
Debug-agent's only eval gate is the deny-list tripwire (§11) — fine when the
process is confined. The playground already provides that confinement: a
non-root tenant (`USER by`), a per-session container, writable only on the
mounted `~/.brainyard` + `/workspace` volumes, with Phase-2 hardening (egress
allowlist, gVisor/Firecracker, quotas) layering on top. Full-trust live surgery
that would be reckless on a developer's host is *contained* here — you can let
the agent `alter-var-root` the running system and, if it wedges the image, throw
the container away.

**3. The image already ships everything the loop needs.** The playground
Dockerfile bakes the full dev toolchain the agent drives via `bash`: `clj` / `bb`
(reload + tests), `git` (the `/workspace` project repo is `git init`-ed at first
boot — a tenant-project repo, not a brainyard checkout; point 1 caveat),
`rg`/`fd`/`jq`/`tree` (grep/search), plus `clj-nrepl-eval` (an external nREPL
*client* pre-warmed for CIDER-style attach). Nothing extra to install for
debug-agent to reproduce → probe → live-patch → verify.

**4. Persistence + reattach fit the workflow.** Per-session named volumes
(`pg-state-<id>` → `~/.brainyard`, `pg-work-<id>` → `/workspace`) mean any file
debug-agent writes under `/workspace` (a tenant-project edit, a captured probe,
notes) **survives suspend/resume**, and `--web-tmux` + `--resume-latest` means a
dropped browser tab reattaches to the same live session — the investigation (and
its pinned nREPL session) keeps running. (This persists `/workspace` files, not
edits to brainyard's own source — those live in the jar; see the caveat in
point 1.)

**5. External attach for a human co-pilot.** `clj-nrepl$start-server` writes a
per-instance port file so a developer can point CIDER/`clj-nrepl-eval` at the
**same** live image the agent is driving — the human and the agent share one
JVM. The container makes that safe to expose on loopback.

**What it's good for, concretely, in the container:**
- **(B) Understand** — a tenant learning brainyard asks "how does config
  resolution actually work?" or "what tools does `code$eval` expose?" and
  debug-agent answers from the live registry/namespaces, not from stale docs.
  Fully works.
- **(A) Debug + validate** — "reproduce why my last agent turn errored and prove
  a fix" runs reproduce → probe → hypothesize → **ephemeral live-patch**
  (`alter-var-root`/`def`/`defmethod`) against the running `by`, all inside the
  sandbox. Fully works — the validation is exactly what the container is for.
- **(C) Durable fix — dev checkout, not the container.** Making the fix permanent
  in *brainyard's own* source (edit + reload-from-source, steps 5–6) does **not**
  hold in the packaged container: its classpath is the uberjar, brainyard's
  `.clj` lives inside the jar, and `/workspace` is empty and off-classpath (point
  1 caveat). Do this in the dev source-checkout runtime (`bb tui` / `clojure -M`)
  instead. In the container, job C applies only to a **tenant's own**
  `/workspace` project.
- **A throwaway teaching/demo sandbox** — because the container is disposable,
  it's the ideal place to *show* the reproduce → probe → **live hot-patch** →
  verify loop end-to-end without risking anything.

---

## 13. Playground demo recipe (runnable)

Two ways to exercise debug-agent in a container. Both need Bedrock creds (or
another provider) for the LLM round-trip; the container forwards your AWS
profile. Requires a built uberjar — `bb uberjar:ata` if
`projects/agent-tui-app/target/agent-tui-app.jar` is missing.

### 13a. Quick path — `bb docker:ata` (one throwaway container)

`bb docker:ata` bind-mounts the uberjar into `eclipse-temurin:21-jre-jammy` and
forwards your AWS creds. Launch the interactive TUI *as* debug-agent — no
`BY_NREPL_ENABLED` needed, because the agent can start its own server:

```bash
# Interactive TUI, rooted on debug-agent, against Bedrock
AWS_PROFILE=<profile> bb docker:ata run -p bedrock \
    -m global.anthropic.claude-opus-4-7 -a debug-agent
```

Then, in the TUI, drive the three jobs. Debug-agent's tool-context tells it to
`clj-nrepl$status` → `clj-nrepl$start-server` (TOOL channel) before any
` ```clojure ` block, so a bare prompt works:

```
> Start the live-runtime server, then list every ai.brainyard namespace and
  show where ai.brainyard.agent.core.config/get-config is defined (file + line).

> Reproduce: call (ai.brainyard.agent.core.config/get-config :clj-backend) in
  the live image and show the result.

> How many tools does code$eval expose, and what's its input schema? Read it
  from the live registry.
```

To skip the self-start step, pre-enable the server by adding the env var to the
`docker run` (edit the `docker:ata` argv, or run the equivalent `docker run`
directly with `-e BY_NREPL_ENABLED=true`).

> `bb docker:ata` also does one-shot calls (`bb docker:ata ask -p bedrock -m … 'What is 2+2?'`),
> but the debug→fix loop wants the interactive `run` TUI so the pinned session
> persists across turns.

### 13b. Full playground image + browser terminal

Build and run the real playground workspace image (JVM uberjar + full
toolchain + ttyd), then drive `by` from the browser:

```bash
bb playground:ui       # build the SPA (first time)
bb uberjar:ata         # the image bakes in the by uberjar (build order matters)
bb playground:image    # build deploy/playground-workspace → brainyard/workspace:dev
bb playground:run      # run a container; opens the browser terminal
```

Enable nREPL for the tenant's `by` either by baking
`:agent {:config {:nrepl-enabled? true}}` into the seeded
`~/.brainyard/config.edn`, injecting `-e BY_NREPL_ENABLED=true` at container
start, or just letting debug-agent call `clj-nrepl$start-server`. Then, in the
browser TUI, invoke the specialist inline:

```
@debug-agent understand how the hooks registry works — list registered hooks
and the event catalog from the live image.

@debug-agent reproduce and validate: <describe a fault>. Reproduce it in the
live image, hypothesize, and prove a fix with an ephemeral live-patch
(alter-var-root / def). Report the root cause and the validated patch.
```

The live-patch validation runs entirely in the container. To make the fix
**permanent in brainyard's own source**, apply the reported patch in a dev
source-checkout runtime (`bb tui` / `clojure -M`) and `:reload` there — the
packaged container has no editable on-disk brainyard source and its `:reload`
reads the jar's frozen copy (§12 point 1 caveat). `--resume-latest` reattaches
the container session if the tab drops. (For a fault in the tenant's *own*
`/workspace` project, the full edit → reload loop does work in-container once
that project's `src` is on the eval classpath.)

### 13c. What to watch for

- **Server up first.** If a ` ```clojure ` block returns "clj-nrepl server is
  not running", the agent skipped the lifecycle step — nudge it to
  `clj-nrepl$status` / `clj-nrepl$start-server` (TOOL channel).
- **Deny-list.** Probes touching `System/exit` / `Runtime` / credential
  namespaces are rejected by design (§11) — expected, not a bug.
- **Reload scope.** Confirm the agent uses single-namespace `:reload`, never
  `:reload-all` on an interface ns (§8).
- **mulog trail.** `::debug-agent-session-opened`, `::debug-agent-no-server`,
  `::nrepl-eval-detached` in the container log confirm routing went to the live
  JVM.

---

## 14. History (removed machinery — for archaeology only)

The original design shipped, then was simplified. None of the following is in
the current code; it is recorded so old commits/branches read cleanly.

- **Grant / scope / confirmation / drift / audit layers** on the nREPL path —
  removed (commits `dc2348a`, `82404ff`, `d7633e3`, `4a44ce2`). nREPL became
  full-trust; the deny-list is the only eval gate (§11). Config lost
  `:nrepl-grant`; the env var `BY_NREPL_GRANT` and the read-only/mutate
  classifier no longer exist.
- **`clj-nrepl$drift-markers` tool + TUI "drifted (N)" status chip** — removed
  with the drift layer (its only producer was the eval path). There is no
  `:drifted?` / `:drift-count` wiring in the status bar; debug-agent has **no
  special TUI surface**.
- **`debug$promote-hot-patch` tool + `.brainyard/agents/debug-agent/promotions/`
  artifact + `bb tui ask "@…" -a edit-agent` hand-off** — removed when
  debug-agent took over permanent fixes itself (commits `33a4870`, `3891760`,
  `66357a1`, `370e1f7`). The agent now edits source directly and reloads over
  nREPL; there is no promotion artifact and no boundary to edit-agent.
- **Separate `:execution-model` config write** — folded into `:clj-backend`
  selection (§7); the agent only sets `:clj-backend :nrepl`.

---

## 15. Files

```
components/agent/src/ai/brainyard/agent/common/debug_agent.clj       (defagent + nREPL
                                                                      lifecycle commands
                                                                      + instruction / tool-context
                                                                      + :nrepl usage guide)
components/agent/test/ai/brainyard/agent/common/debug_agent_test.clj
components/agent/src/ai/brainyard/agent/interface.clj                (require list)
components/agent/src/ai/brainyard/agent/common/coact_agent.clj       (block dispatcher +
                                                                      :clj-backend-driven
                                                                      execution-model selection)
components/agent/src/ai/brainyard/agent/core/config.clj              (:nrepl-enabled? / :nrepl-port)
```

Substrate (used, not introduced here):

```
components/clj-nrepl/                                                (server, classifier deny-list, client)
components/agent/src/ai/brainyard/agent/common/code_eval.clj         (unified code$eval)
components/agent/src/ai/brainyard/agent/task/executor.clj            (NreplEvalJobExecutor)
components/agent/src/ai/brainyard/agent/task/manager.clj             (:clj-nrepl-eval registration)
bases/agent-tui/src/ai/brainyard/agent_tui/core.clj                  (opt-in nREPL server bootstrap)
deploy/playground-workspace/                                         (Dockerfile — JVM uberjar host, §12)
```

---

## 16. Open questions

1. **Per-session dossier.** Do operators want a cumulative
   `.brainyard/agents/debug-agent/sessions/<sid>.md` summarising an
   investigation, or are the CoAct turn record + mulog audit log enough? Wait
   for a concrete reader before building it.
2. **Playground default for nREPL.** Should the playground image ship
   `:nrepl-enabled? true` in the seeded config so debug-agent needs no
   self-start, given the container is already the blast-radius boundary? Trade:
   convenience vs. an always-listening loopback server per tenant.
3. **Native-binary story.** Debug-agent is effectively JVM-only (§12).
   Worth a first-class "this agent needs `BY_JAR=1` / the JVM build" gate in the
   TUI so a native-binary user gets a crisp message instead of a first-eval
   failure.
4. **Human + agent co-driving one image.** The port file already enables CIDER
   attach (§12.5). A guided "attach your editor to the agent's JVM" flow in the
   playground UI could make the shared-image workflow a feature, not a trick.

---

## 17. Related reading

- `docs/design/clj-nrepl-eval.md` — the substrate (server, classifier,
  Principle 8 native-image constraint, §8 safety model).
- `docs/design/playground-design.md` — the Docker workspace debug-agent runs in
  (§12); §5.6 isolation, §9 as-built.
- `docs/design/exec-agent-design.md` — sibling CoAct specialist, structural
  template.
- `docs/CoAct.md` — the inherited loop.
- Source: `components/agent/src/ai/brainyard/agent/common/debug_agent.clj`,
  `components/clj-nrepl/src/ai/brainyard/clj_nrepl/core/classifier.clj`.