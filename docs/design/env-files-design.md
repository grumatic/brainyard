# Environment Files — `by env`, and Where a Per-Agent Secret Lives

> **Status: PHASES 0–3 SHIPPED; Phase 4 is still a proposal.** §1 is measured
> against the tree as it was; §3 is the design, with as-built notes at §3.7;
> §7 phases it. Shipped: both `.env` locations, the per-agent loader and its
> seam into `env-policy`, `by env` with six verbs, and the agent-scoped read —
> which landed in a different shape than §3.5 proposed, see §3.7. Phase 4's
> `--env-file` shipped too, so the note is fully built. §5's question 2 is
> resolved: per agent TYPE, as §3.2 chose.
>
> **Depends on:** `docs/design/environment-scoping-design.md` (Phases 0–4,
> shipped). That note built the resolver, gave children the `.env` layer, and
> added `:env-allow` / `:env-deny` / `:env-vars` with ancestry-binding
> restrictions. This one adds the two *files* those scopes have no way to be
> written from, and the CLI that writes them.
>
> **Problem in one line:** every project-scoped thing brainyard owns lives under
> `<project>/.brainyard/` — except `.env`, which is the one thing an agent most
> often needs; and a per-agent SECRET has no home at all, because the only
> per-agent env surface is a config key whose write path refuses secrets.
>
> **Scope proposed:** `dotenv.clj` (two new discovery locations), a new
> `agent/core/env_files.clj` (per-agent load + cache), `core/config.clj`
> (`env-policy` merges the agent file), `util/core/env.clj` (an explicit scoped
> read), and `main.clj` (`by env` with six verbs, `--env-file`, and the
> `known-subcommands` set that a new subcommand must also join).
>
> **Found while:** finishing Phase 4. `:env-vars` can scope a value to one
> agent, and `config$apply`'s secret scan refuses to put a credential in it —
> correctly. Which leaves the obvious question unanswered: where does the
> credential go?

---

## 1. What exists, and the three gaps

### 1.1 `.brainyard/` is the project's state directory — except for `.env`

`dotenv.clj`'s discovery order today (`candidate-paths`, `dotenv.clj:45-52`):

```
BY_ENV_FILE (when it names a readable file)
  else  <cwd>/.env → each ancestor's .env → ~/.brainyard/.env
```

Note the asymmetry: **`~/.brainyard/.env` is a location; `<project>/.brainyard/.env` is not.** Everything else brainyard owns at project scope lives under `<project>/.brainyard/` — `config.edn`, `sessions/`, `agents/`, `tasks/`, `skills/`, `memory/` — governed by `subdir-scope-policy` (`config.clj:1296`). `.env` appears in that policy nowhere at all; an unknown name falls to the permissive `#{:user :project}` branch, so nothing forbids it. It was simply never added.

The practical consequence is that a project's brainyard credentials have to go in the project's *application* `.env` — the one its own tooling reads — or in a user-global file shared across every repo. Neither is where they belong.

### 1.2 A per-agent SECRET has no home

Phase 3 gave `:env-vars`, and its schema doc says why it cannot be the answer:

> *"NOT for secrets: config.edn at :project scope is committed with the repo,
> and config$apply's secret scan refuses a write whose value looks like a
> credential."* — `config.clj:674`

That refusal is right and stays. But it means the sentence "give explore-agent a
read-only `GITHUB_TOKEN` and give exec-agent the write-scoped one" has no
implementation. The scoping machinery is in place — `env-policy` resolves per
agent, `env-policies` composes the ancestry, `apply-policy!` hands the result to
every child — and the only thing missing is a place to put the value.

One correction to that docstring while we are here: **"committed with the repo"
is the intended contract, not a fact about every checkout.** This repository's
own `.gitignore:45` ignores `.brainyard/` wholly, so its `config.edn` is *not*
committed. The secret scan is a defensive default that holds either way, but
§3.6 cannot assume a user's `.brainyard/` is ignored — and that assumption, made
silently, is how a token gets committed.

### 1.3 The property table cannot hold a per-agent value

This is the constraint the whole design is shaped by.

`.env` values become **JVM System Properties** (`dotenv.clj:83`), because the JVM
environment is immutable. That table is *process-global*, and the loader runs
once in `-dispatch` (`main.clj:3667`) — before any agent exists.

Agents are not one-at-a-time. TUI tabs hold several sessions open, and sub-agent
dispatch runs specialists concurrently under a parent. So a per-agent value
written to the property table would be visible to every other agent in the
process, and the last writer would win. **Per-agent env cannot reuse the global
mechanism — not as an optimisation choice, but because the mechanism has no
scope to put it in.**

What it *can* reuse is everything Phase 3 and 4 built, all of which is already
agent-scoped: `env-policy` → `env-policies` → `apply-policy!`.

A fourth observation bounds §3.5. The `resolve-var` call sites split sharply:
69 are `:env-fn` startup knobs, and most of the rest are global status readers
(`/login`'s `auth.clj:68`, the `/model` picker filters, `env-detect`). The
readers where a per-agent value would genuinely mean something are a short list
— `gateway/telegram.clj:61,73` (`BY_TELEGRAM_TOKEN`), `email_commands.clj:36-46`
(the sender vars) — and those still read raw `System/getenv` today.

---

## 2. What is NOT proposed, and why

**Do not put a per-agent value in the property table.** §1.3. Not "avoid", not
"be careful about" — it has no scope, so it cannot be done correctly.

**Do not make `resolve-var` implicitly agent-aware.** A dynamic overlay consulted
by default would have to be bound around every path an agent's work reaches, and
those paths cross thread boundaries constantly — BT actions in futures, detached
tasks, the ticker threads. A binding that is *usually* in place is worse than
none: it would make a credential resolve differently depending on which thread
asked, which is the hardest class of bug to see. §3.5 proposes an explicit
scoped arity instead, for the short list of callers that can pass a scope.

**Do not merge scopes into one file.** `.brainyard/.env` and
`agents/<agent>/.env` are separate files at separate scopes, and the runtime does
not concatenate them into a synthetic whole — the same rule
`subdir-scope-policy` already states for `memory` and `config.edn`
(`config.clj:1305-1310`).

**Do not replace `BY_ENV_FILE`.** It pins discovery for a whole process and is
already load-bearing in tests and CI. §3.4 adds a CLI flag that sets the same
thing, and an `import` verb that is a different operation entirely.

**Do not add a secret store.** These are `.env` files: plain text, `0600`,
gitignored. The keychain-backed store (`clj-oauth`) stays what it is, for OAuth
tokens. A second secret mechanism with different guarantees would be a worse
outcome than one obvious file.

---

## 3. Proposal

### 3.1 Two new files, and where they sit in the chain

```
real environment                            (unchanged — always wins)
  > BY_ENV_FILE / --env-file  (pinned)      (unchanged)
  > <project>/.brainyard/.env               ← NEW  (project, brainyard's own)
  > <cwd>/.env … <ancestors>/.env           (unchanged)
  > ~/.brainyard/.env                       (unchanged)
```

Only one insertion, and its position is forced: **`.brainyard/.env` must outrank
`<project>/.env`, or `by env set` could silently do nothing.** A CLI that writes
must write where the value takes effect; a project that already has an
application `.env` carrying the same name would otherwise shadow every write the
user makes through the tool, with no error and nothing to see.

Everything below it keeps its current order, including "a real environment
variable always wins" — the contract both loaders implement (`by-wrapper.sh:39`,
`dotenv.clj:72`) and the one this note does not touch.

The shell wrapper needs the same insertion (`by-wrapper.sh`), or the two loaders
disagree again — the defect class §1.2 of the scoping note spent four fixes on.

### 3.2 Agent scope resolves per agent, never through the property table

`<project>/.brainyard/agents/<agent>/.env`, where `<agent>` is the **defagent
type name** — `config-agent`, `explore-agent`, `user$agent` — matching every
existing artifact dir (`router.clj:33`, `exec.clj:38`, `explore.clj:50`, and the
`subdir-scope-policy` keys at `config.clj:1344`). It is per TYPE, not per
instance: the `agt-…` session id names a level *below* that and only for
router-agent's routing logs. Per-type is also what `:config-extra` scopes, so the
two agree.

It is read by a new `agent/core/env_files.clj`, cached per `(project, agent)`
with the file's mtime, and it reaches behaviour through exactly one seam —
`config/env-policy` merges it into `:vars`:

```clojure
;; config/env-policy, proposed
{:allow (get-config agent :env-allow)
 :deny  (get-config agent :env-deny)
 :vars  (merge (get-config agent :env-vars)      ; config.edn — committed, non-secret
               (env-files/agent-env agent))}     ; .env — gitignored, secrets
```

**The `.env` file wins a collision**, which is the conventional direction: a
local uncommitted file overrides a shared committed one. And because `:vars`
flows into `apply-policy!`, the per-agent file inherits everything Phase 4
settled for free — an ancestor's `:env-deny` still removes a value a descendant's
`.env` supplied, additions still precede removals, and `env-policies` still
composes the chain root-first.

Two consequences worth stating because both are easy to assume the other way:

- **A per-agent value reaches CHILDREN, not this process.** That is where the
  demand actually is: `gh`, an MCP server, a shell fence, an ACP backend. The
  in-process case is §3.5 and is deliberately narrow.
- **It is invisible to `resolve-var` by default**, which is the correct default
  given §1.3 — a global reader asking a global question must not get one agent's
  answer.

### 3.3 `by env` — the CLI

Six verbs under one parent, following `projects` (`main.clj:3350`) exactly: the
parent carries `:subcommands` and neither `:opts` nor `:runs`; each leaf carries
both; positional args arrive as `(:_arguments opts)`; `--json` and `--yes` come
from the shared `json-opt` (`main.clj:237`) and `yes-opt` (`main.clj:391`).

| verb | does |
|---|---|
| `by env list` | names, source file, and MASKED values across the whole chain |
| `by env get NAME` | one resolved value and which file supplied it |
| `by env set NAME=VALUE` | write to the target scope's file (creating it `0600`) |
| `by env unset NAME` | remove from the target scope's file |
| `by env import --file PATH` | merge an external env file into the target scope |
| `by env doctor [NAME]` | the full chain: every candidate, what each supplies, what shadows what |

Scope selection is one flag pair, shared by the writing verbs:
`--scope project|user` (default `project`) and `--agent <agent-id>` (which
implies agent scope and is mutually exclusive with `--scope user`).

**`by env doctor` is the verb that earns the feature.** It is
`config$reload`'s `:shadowed` idea applied to files: given a name, print every
candidate location, whether it exists, what it holds (masked), and which one
wins — so *"I set it and nothing happened"* has an answer that is one command
long rather than an afternoon. Every other verb here is CRUD; this one is the
diagnostic the four defects in the scoping note existed for.

Three house details a new subcommand gets wrong by default:

- **`known-subcommands` (`main.clj:3496`) is a hardcoded set** and must gain
  `"env"`, or `by env …` dies in `normalize-dispatch-args` with a
  "did you mean" before cli-matic ever sees it.
- **`install-working-dir!` is the first form of every project-sensitive
  `:runs`** (`main.clj:1070`, called from eleven of them). Every `by env` verb
  except a `--scope user` read is project-sensitive.
- **There is no generic table printer.** Each command hand-rolls a
  `format-*-table` returning lines (`format-projects-table`, `main.clj:2963`);
  `--json` branches to `print-json!` (`main.clj:256`) with a `{:success true …}`
  payload.

### 3.4 `--env-file`: pinning at runtime is not importing into a scope

Two different operations that a single name would confuse:

- **`by run --env-file P` / `by ask --env-file P`** — pins discovery for this
  process, exactly as `BY_ENV_FILE` already does, and resolves through the same
  code (flag > env var, the precedence every other `by` flag uses). Nothing is
  written. This is the CI and one-shot case, and it exists today only as an
  environment variable, which is awkward to set for a single invocation.
- **`by env import --file P`** — reads `P` and MERGES its keys into the target
  scope's file, persistently. `--overwrite` to replace names that already exist
  there; without it, existing names are kept and reported as skipped, because
  the destructive reading of "import" is the one a user will regret.

Both parse with the same `parse-line` as `dotenv.clj` (`dotenv.clj:33-58`) —
`export ` stripping and quote handling included — so a file that works in one
place works in the other. That parser is currently private to the app project
and would move to where both can reach it.

### 3.5 Reading a scoped value in-process

`util/resolve-var` gains an explicit scoped arity — an overlay map passed by the
caller, consulted ABOVE the environment:

```clojure
(resolve-var k)                    ; global — unchanged, the default
(resolve-var k {:overlay agent-env}) ; scoped — the caller supplies the scope
```

Explicit, because §2: a dynamic binding that must span futures and ticker
threads would resolve a credential differently depending on which thread asked.
An argument cannot be *partly* in place.

The honest note about this arity is that its caller list is short and known:
`gateway/telegram.clj` and `email_commands.clj`, both of which read a credential
per call with an agent in scope, and both of which read raw `System/getenv`
today and would migrate anyway. Everything else that reads through `resolve-var`
is a startup knob or a global status question, where one agent's answer would be
the wrong one. **If review decides those two are not worth an arity, §3.5 can be
dropped without touching anything else in this note** — the child-process path
(§3.2) is the feature.

### 3.6 Secrets: redaction, mode, and the gitignore the user may not have

- **Masked by default.** `by env list` and `by env doctor` print
  `sk-ant...****ab12` via the existing `mask-key` shape
  (`env_detect/core/providers.clj:38`), never the value. `--show-values` reveals,
  and is the flag a user has to type on purpose.
- **`0600` on create**, matching the OAuth file store (`clj-oauth/core/store.clj`).
- **Never logged.** `by env set` mulogs the NAME and the target file, never the
  value — the precedent MCP already sets by logging its *unexpanded* argv
  (`mcp/client.clj:295-300`).
- **`by env` writes a `.gitignore` alongside the file it creates.** This
  repository ignores `.brainyard/` wholly (`.gitignore:45`), but a user's project
  is documented as committing `<project>/.brainyard/` so config travels with the
  codebase — and under that reading, a `.brainyard/.env` would be committed. So
  creating either file also ensures `<project>/.brainyard/.gitignore` contains
  `.env` and `agents/*/.env`. Cheap, idempotent, and it removes the one way this
  feature could hurt someone.

### 3.7 As built (Phases 0–3)

**§3.1 — discovery is a per-LEVEL interleave, not a resolved project root.**
`candidate-paths` offers `<dir>/.brainyard/.env` then `<dir>/.env` at every
level of the walk it already did. That is what keeps it correct at a moment when
the project root is not yet known: the loader runs in `-dispatch`, before
`install-working-dir!` and before any config loads. The git root is one of the
ancestors, so the walk finds it anyway. `BY_PROJECT_DIR` is honoured first and
as a LEVEL — both of its files, same order — because a root that contributed
only one of its two would be a third rule nobody could remember. The shell
wrapper got the same interleave, loading both files at the first level where
either exists.

**§3.2 — `agent/core/env_files.clj`.** The cache is keyed by PATH and mtime, not
by agent: two instances of one specialist share a file and should share a read,
and an mtime check means `by env set` takes effect on the next spawn without a
restart — the property `config$reload` had to be built by hand for `config.edn`,
free here because there is no process-global cache to invalidate. `defagent-type`
derives the directory from the instance id's namespace (`:explore-agent/i1` →
`explore-agent`), the same derivation `hooks/match-defagent-type` uses, and it
answers nil rather than throwing for a stub, a bare map or nil — callers reach it
from a spawn site, where throwing would take down the spawn.

**The `.env` parser moved to `util`.** It was private to the app project;
`by env import` and the per-agent loader needed the same rules, and a file that
parses one way for the loader and another for the CLI writing it is a defect
waiting to happen. Three readers now share `util/parse-env-file`.

**§3.3 — `by env doctor` reports TWO winners, and the design was wrong to say
one.** Written as specified, it named the agent file as *the* winner for a name
that file defines — asserting the opposite of §3.2, which is that the agent file
is not on the process chain at all. It now answers the two questions separately:

```
Resolving GH_TOKEN
  in this process:  <proj>/.brainyard/.env
  for its children: <proj>/.brainyard/agents/explore-agent/.env  (agent scope)
```

`by env list` carries the same correction as a footnote when an agent file
contributed a row. This is the verb that exists to be precise about where a
value comes from; conflating the two would have made it confidently wrong.

**§3.6 — verified rather than asserted.** The created file's POSIX permissions
are `{OWNER_READ, OWNER_WRITE}`, and the `.gitignore` written beside it is
additive (existing content kept) and idempotent (a second call writes nothing).
Values are masked in table output *and* under `--json`; `--show-values` is the
flag a user has to type on purpose. `set` / `unset` / `import` mulog a NAME and a
path, never a value.

**Tests.** `agent/…/core/env_files_test.clj` — 17 tests / 47 assertions,
including the leak this design exists to prevent: a per-agent value is readable
by its own agent, invisible to another agent in the same process, absent from the
property table, and absent from `util/resolve-var`.
`projects/agent-tui-app/test/…/dotenv_test.clj` — 6 tests on discovery order,
run by the project alias (`clojure -M:test`) since Polylith does not cover
project `src`.

**§3.5 — the scoped read shipped as a separate function, not an arity.** The
proposal was `(resolve-var k {:overlay …})`; reading the actual call sites made
that the wrong shape. `util/resolve-var` is called from ~90 places where an
agent means nothing — 69 of them `:env-fn` startup knobs — so a scope parameter
there taxes every one of them for the handful of readers that can supply one.
The scoped read belongs where the scope does:

```clojure
(util/resolve-var "GH_TOKEN")               ; process-global, unchanged
(env-files/resolve-for agent "GH_TOKEN")    ; agent .env → env → property
```

**The agent file outranks the environment, which inverts the global chain, and
consistency forces it.** `apply-policy!` puts a policy's `:vars` into a child's
environment unconditionally, so a child ALREADY sees agent-scope-over-process-env.
An in-process read ordering them the other way would contradict the environment
of the very children that agent spawns.

**And the argument for it is stronger than §3.5 said.** It framed the case as
narrow — two callers. The real problem is SPLIT-BRAIN: give `explore-agent` a
read-only `GH_TOKEN` and `gh` invoked as a subprocess uses it while an in-process
HTTP call from a tool uses the global one. One agent, two identities, differing
on whether the call happened to shell out, and nothing announces it. Narrow
today; wrong in a way that does not surface.

Migrated: `gateway/telegram.clj` (`BY_TELEGRAM_TOKEN`, both reads) and
`email_commands.clj` (the sender identity, reply-to, and `AWS_REGION`). The last
one caught a consistency bug in passing — `email$status` reported the region from
`System/getenv` while `get-ses-client` had moved to the scoped read, so the
status would have named a region the client was not using.

**Three readers must NOT take a scope**, and the reasons are structural rather
than "we only found two":

- **The 69 `:env-fn` knobs.** `get-config` is already agent-aware and env is its
  TOP layer, above the per-agent config layer. Wiring an overlay into
  `schema-env-value` would let an agent's `.env` outrank that same agent's
  `:config-extra`, inverting the precedence the config system documents.
- **Global status readers** — `/login`, the `/model` picker, `env-detect` ask
  whether this MACHINE is configured. A per-agent answer is wrong by construction.
- **MCP.** Servers start once per process and are shared, so "this agent's token"
  has no per-server meaning (§4).

**Why it is explicit rather than implicit**, stated precisely: the hazard is not
using a dynamic var — `proto/*current-agent*` is bound on the tool-dispatch
thread, so reading it inside a synchronous tool body is fine, and `resolve-for`'s
1-arity does exactly that. The hazard is making the GLOBAL resolver consult one.
`resolve-var` is called from tickers, futures, `call-tool-with-fast-eval` and the
task executor, where no binding exists — so the same name would resolve
differently depending on which thread asked, silently, and only for credentials.
The line sits between the two functions, not inside one.

**Still not done, and named rather than smuggled:** the code-eval sandbox has no
env access at all — `System` is denied and `sys-info-properties` is a closed
7-entry list, deliberately, because in `by` the property table IS the credential
store. Exposing `resolve-for` there as a binding would let an agent's own code
use its own credential, which is arguably worth more than either call site above.
It is also a deliberate widening of a boundary someone chose, so it needs its own
argument.

**§3.4 — `--env-file` is pre-scanned from argv, then consumed.** The `.env` has
to be loaded before anything reads config: `-dispatch` calls the loader well
before `cli/run-cmd`, so a flag read from the parsed opts would arrive too late
for the 69 `:env-fn` knobs it exists to supply. `env-file-arg` scans argv (both
`--env-file P` and `--env-file=P`) and is pure, so the argument rules are
testable without `System/exit` — the shape `normalize-dispatch-args` already
uses.

`strip-env-file-arg` then removes it before cli-matic sees it. That is what lets
the flag work on EVERY subcommand while being declared only on `run` and `ask`,
where it belongs in `--help`: without the strip, the pre-scan would already have
acted on `by env doctor --env-file P` and cli-matic would then reject the flag as
unknown. A flag that works on some subcommands and errors on others, having
already taken effect, is the worse outcome.

**A missing pin means different things from the two sources**, so it is handled
differently. `BY_ENV_FILE` may be inherited from another context and is
plausibly stale: the walk still runs and the fallback is announced. `--env-file`
was typed for this invocation and is an assertion about this run, so it exits
non-zero rather than silently reading somewhere else.

**A pin REPLACES the walk, so `by env` reports the pin.** `load-from-dotenv!`
records the resolved file in `dotenv/pinned-file`, and `env-candidates` uses it
as the global chain when set — otherwise `by env list` and `doctor` would
describe a discovery that never happened for this process. It also makes
`by env doctor --env-file P` answer a genuinely useful question: what does *this*
file give me?

### 3.8 `by env doctor` answers with the POLICY, not just the files

Found by investigating question 4, and measured before it was fixed:

```
agent .env  => {"BY_Q4_PROBE" "in-the-agent-file"}
policy      => {:deny ["BY_Q4_PROBE"] :vars {"BY_Q4_PROBE" "in-the-agent-file"}}
child sees  => []                              ← :env-deny wins, correctly

doctor said => "for its children: …/agents/coact-agent/.env  (agent scope)"
```

The verb whose entire job is *"why doesn't this reach my agent?"* stated the
opposite of what happens. Its `for its children` line came from
`env-candidates`, which reads files and never calls `env-policy`, so
`:env-deny` and `:env-allow` were invisible to it. This is the same class of
error §3.7 corrected one level shallower — the child answer was still a FILE
answer wearing a CHILD label.

`env-effective-for-children` now runs the real machinery: `util/apply-policy!`
over a map seeded from this process's environment, with the policy chain the CLI
can know. Files answer *what is written down*; only the policy answers *what
arrives*.

```
for its children: REMOVED by :env-deny / :env-allow (defined in …/coact-agent/.env)
```

**Two layers are reachable from outside a running agent**, and one is not. The
global layer comes from the 1-arity `get-config` (config.edn, env, defaults);
the named agent's comes from the defagent registry's `:config-extra` plus its
`.env`. ANCESTRY cannot: a router that denies a name to the specialists it
dispatches only exists during a dispatch, and there is none at a CLI. So every
`doctor` answer carries a note saying a dispatched sub-agent may receive less —
stating the limit rather than letting the omission read as an answer.

`by env list` still reports files, which is what it is for, and its agent-scope
footnote now says `:env-deny`/`:env-allow` may remove a row before it arrives
and points at `doctor` — rather than promising delivery it cannot check.

---

## 4. What this deliberately does not fix

**It is not containment.** A per-agent credential keeps a *mistake* narrow and
makes the audit trail legible. An agent holding a shell fence can print whatever
its child inherited — the boundary measured in
`tool-permission-gate-design.md` §1.3.2 — and `--sandbox` remains the answer when
containment is the requirement.

**It does not give MCP servers per-agent credentials.** MCP servers are started
once per process and shared by every agent (`mcp/integration.clj`), so "this
agent's GITHUB_TOKEN" has no per-server meaning today. A per-agent MCP server is
its own design.

**It does not change what `resolve-var` answers by default**, and therefore does
not make a per-agent value visible to `/login`, the `/model` picker, or
`env-detect`. Those ask global questions and should keep getting global answers.

**It does not unify `.env` parsing across all three loaders.** `bb.edn:4-25`
still sources `./.env` in the shell for `bb` tasks. Making that agree is a
separate, mechanical change with its own risk of breaking the dev loop.

---

## 5. Open questions for review

1. **Should `by env set` refuse a secret-shaped value at USER scope?** At project
   scope the answer is clearly no — a `.env` is where secrets belong. But
   `~/.brainyard/.env` is shared across every repo on the machine, and a
   deployment key pasted there reaches agents in projects the user was not
   thinking about. A warning is probably right; a refusal probably is not.
2. ~~**Per agent TYPE or per agent INSTANCE?**~~ **RESOLVED: type**, as §3.2
   chose — matching every existing artifact dir and what `:config-extra` scopes.
   Per instance (`agt-…`) would let two live sessions of one specialist hold
   different credentials, but there is no discoverable way to say which instance
   you are configuring, and the directory would be unreadable.
3. **Does `by env` need a `--session` scope at all?** Session config is a real
   precedence layer (`resolve-config`, `config.clj:1649`) and has no file. It is
   also the one layer that vanishes on restart, which may make a file for it
   incoherent.
4. ~~**Should the agent `.env` also feed `:env-allow`?**~~ **RESOLVED, and the
   question was hiding a defect.** The answer is no — a `.env` is admitted past
   its OWN policy's `:env-allow` (it lands in that policy's `:vars`) and not past
   an ancestor's, which is the same-level rule Phase 4 of the scoping note
   settled. But investigating it showed `by env doctor` could not have explained
   any of that: it read the file chain alone, so with `:env-deny ["X"]` in
   config.edn and `X` in an agent's `.env` it reported *"for its children:
   &lt;that file&gt;"* while the child received nothing. Measured, then fixed —
   see §3.8.
5. **`env` is a heavily overloaded word, and the overload had teeth.** Five
   things carry it: `by env` (files), `:env-allow`/`:env-deny`/`:env-vars`
   (policy), `util/…/env.clj` (the resolver), `agent/…/env_files.clj` (the
   files), and `--env-file`/`BY_ENV_FILE`/`.env`. The concern is not aesthetic:
   what a user means by *this agent's environment* is files PLUS policy, and
   `by env` managed only the first half — which is exactly how question 4's
   defect happened. §3.8 closes the gap for `doctor`; `list` still reports
   files, and now says so rather than promising delivery.

---

## 6. Test obligations

- **Discovery order, per new location.** `<project>/.brainyard/.env` outranks
  `<project>/.env` and is outranked by a real environment variable. Assert the
  file that WON, not just the value — two files agreeing hides an ordering bug.
- **Both loaders agree.** The shell wrapper and `dotenv.clj` must find the same
  file and reach the same answer for the same tree. This is the exact defect
  class §1.2 of the scoping note found four times, so it is asserted rather
  than assumed.
- **A per-agent value never reaches the property table.** Assert
  `System/getProperty` is nil for a name only an agent `.env` supplies, and that
  a SECOND agent in the same process does not see the first agent's value — the
  leak §1.3 exists to prevent, and the one no unit test of a single agent would
  catch.
- **The agent file composes with Phase 4.** An ancestor's `:env-deny` still
  removes a value a descendant's `.env` supplied; a value present in both
  `:env-vars` and the file resolves to the file.
- **A real child process sees it**, via the `proc_test.clj` pattern — every
  other assertion proves a map was computed, which is one step short of the
  claim.
- **Nothing leaks into output.** `by env list` masks by default; `--json` masks
  too (a machine-readable dump is the easier one to paste somewhere public); the
  mulog event for `set` carries a name and a path and no value.
- **The gitignore is written and is idempotent**, including the case where
  `<project>/.brainyard/.gitignore` already exists with other content.
- **`known-subcommands` contains `"env"`.** A one-line test, because the failure
  is a "did you mean" on a command that exists, and nothing else in the suite
  would notice.

---

## 7. Phasing

| phase | content | value on its own |
|---|---|---|
| **0** ✅ | `<project>/.brainyard/.env` as a discovery location, in both loaders. No CLI. | The project's own brainyard credentials stop having to live in the application's `.env` or a user-global file. |
| **1** ✅ | `by env list` / `get` / `doctor` — read-only, masked. | *"I set it and nothing happened"* becomes one command. Nothing can be broken by a read. |
| **2** ✅ | `by env set` / `unset` / `import --file`, `0600`, the `.gitignore` guard. | The files become manageable without an editor. |
| **3** ✅ | `agents/<agent>/.env` + `env-policy` merging it into `:vars`. | The per-agent secret that Phase 3 of the scoping note could scope but not store. |
| **4** ✅ | The agent-scoped read (as `env-files/resolve-for`, not a `resolve-var` arity) and `--env-file`. | An agent's in-process reads and its children's environment stop disagreeing; a single invocation can pin its own `.env`. |

Phases 0–2 are about the project file and are useful with no agent scoping at
all. Phase 3 is the one that answers the question this note was written for, and
it depends only on Phase 0's loader change — so if the CLI is contentious, Phase
3 can land before Phases 1–2.
