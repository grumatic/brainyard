# Environment Scoping — One Resolver, Three Scopes, and the Difference Between a Knob and a Secret

> **Status: PHASES 0–4 SHIPPED, less tool scope.** §1 is measured against the tree as it was;
> §3 is the design; §7 phases it and marks what has landed. Phases 0–2 added no
> config key and no scoping, and closed every defect §1.2 measured plus the
> child-process asymmetry behind it (§3.1a, §3.6a, §3.5a). Phase 3 is the
> feature as asked: `:env-allow` / `:env-deny` / `:env-vars` at global and agent
> scope (§3.3a). All three ship inert. Phase 4 (§3.3b) made an ancestor's
> restriction bind its descendants — the hole that made a deny escapable by
> delegating — and found two of its own items needed no code. Tool scope stays
> deferred on §5's question 2; question 5, whether the code-eval channel should
> have a gate of its own, remains open.
>
> **Problem in one line:** brainyard has three unrelated things called
> "environment" — `BY_*` config knobs, third-party credentials, and the
> environment a spawned child sees — and only the first has any scoping at all.
>
> **Scope proposed:** a new `components/env` (or `agent/core/env.clj`) holding
> ONE resolver and ONE child-env builder; `core/config.clj`
> (`:env-allow`, `:env-deny`, `:env-vars`, and `schema-env-value` routed through
> the resolver); `core/proc.clj` (`shell-pb` grows an env argument);
> `common/tools.clj`, `common/coact_agent.clj`, `core/exec_backend.clj`,
> `task/executor.clj`, `mcp/client.clj`, `acp-client/core/registry.clj` (each
> builds its child env from the resolver instead of inheriting blindly);
> `dotenv.clj` + `scripts/by-wrapper.sh` (reconciled).
>
> **Found while:** asking how an agent gets a *different* `GH_TOKEN` from the
> one in `.env`. The answer turned out to depend on which of the three things
> you meant, and for two of the three there is no answer at all.

---

## 1. The findings this rests on

### 1.1 "Environment" is three different things wearing one name

They have different owners, different lifetimes, and — critically — different
*achievable* scopings. Conflating them is what makes the question feel hard.

| | what it is | how it is read | can it be scoped today? |
|---|---|---|---|
| **A. `BY_*` knobs** | brainyard's own configuration | 69 `:env-fn` entries in `config-schema` | **yes, three ways already** — `get-config` has env → agent → session → global → default (`core/config.clj:1643`). These are *config that happens to be spellable as an env var*, not environment. |
| **B. Third-party credentials / endpoints** | `ANTHROPIC_API_KEY`, `AWS_PROFILE`, `GH_TOKEN`, `GCP_OAUTH_*` | ~201 raw `System/getenv` calls across 61 files, plus 7 ad-hoc `env-or-prop` copies | **almost never.** One accidental exception, §1.4. |
| **C. Child-process environment** | what a spawned MCP server, `gh`, shell fence or ACP backend sees | `(.environment ProcessBuilder)` | **fully controllable, barely used.** Only ACP and MCP put anything there. |

The user-facing question "how do I give this agent a different token" is a
**category B and C** question. Category A already works and is not what anyone
means when they say environment.

**The whole design follows from this table.** You cannot scope what you cannot
intercept: A is scopable because `get-config` is a chokepoint, C is scopable
because we build the child's map ourselves, and B is not scopable *at all*
today because there is no chokepoint. So the proposal is mostly "give B a
chokepoint, and make C use it".

### 1.2 The JVM environment is immutable, so `.env` is a System Property — and that splits the world in two

`java.lang.System/getenv` returns an unmodifiable view, and brainyard does not
fight it: a repo-wide search for `ProcessEnvironment`, `theEnvironment`,
`setenv`, `putenv` finds **zero** hits. Four separate namespaces say so in
comments (`dotenv.clj:14-16`, `env_detect/core/providers.clj:46-48`,
`clj_llm/core/providers.clj:713`, `mcp/client.clj:214-216`).

So the `.env` loader does the only thing it can:

```clojure
;; projects/agent-tui-app/src/.../dotenv.clj:79
(doseq [[k v] @merged] (System/setProperty k v))
```

**`.env` values live in the JVM property table, not the environment.** Only a
reader written as `(or (System/getenv k) (System/getProperty k))` sees them —
and a child process, which inherits the *environment*, never does.

There are **three** `.env` loaders and they do not agree:

| loader | discovery | mechanism | honors `BY_NO_DOTENV` |
|---|---|---|---|
| `scripts/by-wrapper.sh:24-64` | `BY_ENV_FILE`, else nearest `.env` walking up — **first hit only, no `~/.brainyard/.env`** | real exported env vars | yes |
| `dotenv.clj:23-81` | **every** ancestor `.env`, then `~/.brainyard/.env` | `System/setProperty` | **no — it never reads the flag** |
| `bb.edn:4-25` | `./.env` only | real exported env vars | no |

The installed `by` *is* the wrapper (`bin/install.sh:206`), so on the normal
path most keys do become real env vars. Four measured consequences of the split:

**(a) A `BY_*` knob in `.env` is silently inert without the wrapper.** All
**69 of 69** `:env-fn` entries call `System/getenv` alone — none has the
property fallback. Measured on the built binary, from a scratch directory:

```
.env contains BY_PROJECT_DIR=<brainyard repo>
  $ by sessions list        → [dotenv] loaded 1 key(s) …
                              No persisted sessions.        ← knob did nothing
  $ BY_PROJECT_DIR=<repo> by sessions list
                            → the repo's sessions           ← same value, works
```

That is the entire dev loop (`bb tui`, `by-bin` directly, `BY_JAR=1` without the
wrapper), where `.env`-set knobs quietly do not apply.

**(b) `BY_NO_DOTENV=1` does not disable the in-JVM loader.** Same run: the
`[dotenv] loaded 1 key(s)` banner printed anyway. `BY_NO_DOTENV` and
`BY_ENV_FILE` are wrapper-only knobs; `dotenv.clj` reads neither.

**(c) `~/.brainyard/.env` cannot carry a `BY_*` knob at all.** The wrapper never
looks there, and `dotenv.clj` puts it in properties which no `:env-fn` reads —
so it is inert on *both* paths, despite being documented in `CLAUDE.md:98` and
`.env.example:5`. It works fine for API keys, which go through `env-or-prop`.

**(d) Two sibling functions ten lines apart disagree.**
`resolve-working-dir` reads `(or (System/getenv "BY_WORKING_DIR")
(System/getProperty "BY_WORKING_DIR"))` (`config.clj:955-956`);
`resolve-project-dir` reads `(System/getenv "BY_PROJECT_DIR")` alone
(`config.clj:971`).

### 1.3 There is no read chokepoint — and the duplication proves everyone wanted one

`System/getenv` is called **~201 times across 61 files**. The heaviest:
`core/config.clj` (65, nearly all inside `:env-fn`s), `clj_llm/core/providers.clj`
(11), `env_detect/core/sandbox.clj` (10), `main.clj` (8).

**Six** independent, private, near-identical copies of the property bridge
exist, plus two more of the same expression written inline:

| | file:line | blank ⇒ nil? |
|---|---|---|
| `env*` | `main.clj:812-816` | no |
| `credential` | `agent_tui/helpers.clj:78-80` | yes |
| `env-or-prop` | `env_detect/core/providers.clj:44-49` | no |
| `env-or-prop` | `clj_llm/core/providers.clj:709-719` | yes |
| inline | `mcp/client.clj:228-229` | n/a (leaves literal) |
| inline | `clj_sandbox/core/sandbox.clj:366` | property-only |
| inline | `config.clj:955-956` | no |

Eight sites converging on one shape is the same argument that justified
extracting `glob-match?` for the tool permission gate. Three further readers
(`auth.clj:66`, `agent_tui/mode.clj:36`, `agent_tui/clipboard.clj:53`) already
define an *injectable* `getenv` for testability — they want a seam and each
built a private one.

The cost of having none is visible: `auth.clj:100-106` (`/login`) and the
`/model` picker filters (`agent_tui/commands.clj:41-44`,
`autocomplete.clj:98-100`) are `getenv`-only, so a `.env`-supplied key reads as
"not signed in" and its models are hidden from the picker — while `clj-llm`,
using `env-or-prop`, authenticates with that same key successfully.

### 1.4 Per-agent LLM credentials already work, and nobody can express it

`create-lm`'s explicit `:api-key` outranks the whole env chain
(`clj_llm/core/providers.clj:849`), and `:lm-config` is a `config-schema` key,
so it lands on the **per-agent** precedence layer. That is exactly the path the
work-tier feature uses (`core/tool.clj:513-521`): resolve something at dispatch,
inject it as a top-level schema key on the child's options, let
`setup-agent`'s `(select-keys options config/config-keys)` (`core/agent.clj:1173`)
put it on the sub-agent's layer.

So **one** case of category B is already solved by accident. It is undocumented,
reachable only by hand-writing a whole `:lm-config`, and covers nothing else.

### 1.5 The spawn side already has every seam it needs

- **`proc/shell-pb` (`core/proc.clj:108-118`) is the single ProcessBuilder
  factory** for five shell paths: the `bash` tool (`common/tools.clj:243`),
  coact fences (`common/coact_agent.clj:3919`), skills (`common/skills.clj:64`),
  `exec_backend.clj:57`, and `task/executor.clj:58`. `harden-env!`
  (`proc.clj:98-106`) already writes into `(.environment pb)`, and its docstring
  already states the layering rule: *"Call BEFORE any caller-supplied env so an
  explicit override still wins — inherited env is a default, not a ceiling."*
  `shell-pb`'s own docstring invites callers to `doto` extra env on.
- **`:job-config :env` is implemented, correctly ordered, and has no producer.**
  `task/executor.clj:52,64-67` applies it after hardening; all three
  `:job-config` construction sites (`coact_agent.clj:3973, 4058, 4162`) omit it.
- **`acp/core/env.clj` is a finished env-map pipeline** — `normalize`,
  `merge-envs`, `strip-nested-session-markers!`, `validate!` — with a spawn-time
  contract (`env.clj:185-189`: *"coercing silently at spawn time HIDES the
  caller's bug"*), used by `acp/.../transport/stdio.clj:164-186`. It is
  structurally reusable by MCP and by `proc`.
- **`acp-client/core/registry.clj:145-148,181-184,213-216` already does
  name-level scoping**: each backend declares `:forward-env ["ANTHROPIC_API_KEY"
  "PATH" "HOME"]` and copies exactly those from the parent. This is the
  visibility model of §3.3, already shipping, for one subsystem.

Two spawn sites inherit the full environment with no filter and no marker
stripping: **MCP** (`mcp/client.clj:281-284` — `.put` only, never `.clear`) and
every `shell-pb` child. `--web` (`web_share/core.clj:215-221`) and `--sandbox`
(`os_sandbox/core.clj:308-315`) accept a `:child-env` map that their launchers
(`main.clj:903-924, 1013-1041`) never populate beyond a re-entrancy marker.

---

## 2. What is NOT proposed, and why

**Do not try to mutate the process environment.** It is immutable, four
namespaces already say so, and a reflection hack would be a
`--illegal-access`-shaped bet that also has to survive native-image. Every
mechanism below is *resolution*, never mutation.

**Do not try to scope the ~201 raw `System/getenv` sites by fiat.** A resolver
they do not call changes nothing, and a lint rule banning `System/getenv` would
be a large mechanical diff with no behavior change. §3.6 migrates the readers
that carry a *credential or endpoint*; everything else — `TERM`, `TMUX`,
`user.dir`-adjacent probes, container detection — is legitimately process-global
and stays as it is. The doc should say which is which rather than implying total
coverage.

**Do not put secret VALUES in `config.edn`.** `secret-scan`
(`common/config.clj:171-178`) already refuses a write containing an `sk-…`,
`AKIA…`, `ghp_…` or PEM block, and `config.edn` at `:project` scope is committed
with the repo. The per-agent and per-tool declarations in §3.3 therefore carry
**names and non-secret values**, never credentials. This is a constraint on the
design, not a limitation to apologize for — it is what keeps the feature from
becoming a way to commit tokens.

**Do not claim containment.** Per-agent env visibility is subject to exactly the
hole measured in `tool-permission-gate-design.md` §1.3.2: a shell fence reaches
the code-eval channel without dispatching a tool, and a fence can run `printenv`.
Filtering what a *child* inherits is real and useful; it is blast-radius and
legibility, not a security boundary. `--sandbox` remains the boundary.

**Do not add a second precedence chain for category A.** `BY_*` knobs already
resolve through `get-config`, and giving them a parallel env-scoped chain would
be the "no config key that describes what another implies" rule violated at the
level of whole subsystems.

---

## 3. Proposal

### 3.1 One resolver, one child-env builder

A new namespace with exactly two public entry points and no state of its own:

```clojure
(env/resolve  agent tool-name var-name)  ;; => String | nil
(env/child-env agent tool-name)          ;; => {"NAME" "VALUE", …} for a spawn
```

`env/resolve` replaces the copies in §1.3. `env/child-env` is what every
`ProcessBuilder` site applies. Both are pure functions of (scope, config, process
env, property table) — nothing is cached, because a `.env` reload
(`config$reload`'s sibling problem) must be observable.

The scope arguments are both optional and both nilable: `(env/resolve nil nil k)`
is the global lookup and is exactly today's `env-or-prop`, which is what makes
the migration in §3.6 a no-op at every call site that has no agent in hand.

### 3.1a Phase 0, as built

`ai.brainyard.util.core.env`, exported through `util/interface`. It lives in
`util` because it depends on nothing and every layer needs it; `clj-llm` and
`env-detect` each gained a `util` dep to reach it — `env-detect`'s first brick
dependency, which does not disturb the standalone-ness that actually matters
there (the deliberate absence of a compile-time dep on `clj-llm`).

| | |
|---|---|
| `resolve-var` | `[k]` / `[k opts]`. Env, then the property table. `k` may be a string, keyword or symbol — used by NAME, so `(str :CLICKHOUSE_HOST)` can no longer produce a variable called `":CLICKHOUSE_HOST"`. |
| `resolve-first` | `[ks]` → `[k value]`. The PAIR, because every caller has to say *which* variable supplied the credential. |
| `resolve-any?` | `[ks]` → boolean. |

**Migrated:** `clj-llm/core/providers.clj` (`env-or-prop`, kept as a local alias
so its call sites read unchanged), `env-detect/core/providers.clj` (`env-or-prop`,
and `detect-api-key-providers` now calls `resolve-first` instead of hand-rolling
the same scan), `agent/core/config.clj` (`resolve-working-dir`),
`agent/mcp/client.clj` (`expand-env-refs`), `agent-tui/helpers.clj`
(`credential`, plus the inline `BY_USER_ID` and `BY_CACHE_TTL` pairs),
`main.clj` (`env*`), `agent/common/auth.clj` (`getenv` — kept as a private
delegating fn because six tests redef it), and the two `/model` picker filters
in `agent-tui/commands.clj` and `autocomplete.clj`.

**Not migrated, and not a copy:** `clj-sandbox/core/sandbox.clj:366` reads
System Properties *only*, deliberately — `sys-info-properties` is a closed list,
and in `by` the property table is the credential store. The original count of
seven included it in error.

**Two defects closed, verified live in a REPL:**

```
auth/auth-status, key supplied only by .env
  before →  :not-signed-in        after →  :signed-in
  and a blank value still reads :not-signed-in
```

`/login` reported not-signed-in, and the `/model` picker hid every model of the
provider, for a credential `clj-llm` was authenticating with successfully — the
two readers disagreed because one consulted the property table and the other
did not.

**One deliberate behaviour change, which the proposal above did not predict.**
Every private copy read `(or (getenv k) (getProperty k))` and blank-checked the
RESULT, so a blank environment variable *shadowed* a non-blank property:
`export GH_TOKEN=` in a shell profile silently and permanently defeated the
`GH_TOKEN` in `.env`, with nothing anywhere saying why. `resolve-var` checks
each source for blankness independently, so a blank falls through instead. That
is the same accident blank-as-unset already existed to forgive, one link earlier
in the chain; forgiving it at one link and not the other was an artifact of
where the check happened to sit. Pinned by `a-blank-value-does-not-answer-for-a-set-one`.

**Tests:** `components/util/test/…/core/env_test.clj` — 8 tests, 24 assertions.

### 3.2 Precedence — the new layers go above the process env, and `.env` does not move

```
tool-scoped        ← :env-vars on the tool def
  > agent-scoped   ← :env-vars on the defagent / per-agent config layer
  > session
  > process env    ← System/getenv          ─┐ unchanged
  > project .env   ← System/getProperty     ─┤ relative
  > user .env      ← System/getProperty     ─┘ order
  > nil
```

Two decisions inside that:

- **The three new layers sit ABOVE the process env.** A scoped override that the
  ambient shell can silently defeat is not an override — the operator wrote it
  precisely to differ from the ambient value. This is the *opposite* of
  `get-config`'s chain, where env beats the agent layer (`config.clj:1649-1652`),
  and the difference is not an inconsistency: for a **knob**, the operator's
  shell is the most specific statement available; for a **scoped env var**, the
  scope is.
- **`.env` stays BELOW the process env**, exactly where both loaders put it
  today (`by-wrapper.sh:39`, `dotenv.clj:72`). Inverting that would be a
  behavior change to a documented contract, for no benefit this feature needs.

### 3.3 Two orthogonal knobs: visibility over NAMES, values for NON-SECRETS

Folding these into one key would invite pasting a token into `config.edn`. They
answer different questions and only one of them can safely hold a value.

| key | type | default | meaning |
|---|---|---|---|
| `:env-allow` | vector of globs | `nil` | when non-nil, ONLY matching var names are visible to this scope (and to its children). `nil` ⇒ no filtering, i.e. today's behavior. |
| `:env-deny` | vector of globs | `[]` | names hidden from this scope, checked FIRST and unconditional. |
| `:env-vars` | map name→value | `{}` | literal overrides. Secret-scanned on write; refused if the value matches `secret-patterns`. |

Globs reuse `tool-permission/glob-match?` — the same matcher already shared by
`:script-bridge-tools`, `:mcp-allow-tools`, `:tool-approval-patterns` and
`:tool-deny-tools`, with the `$`-anchor and substring bugs already pinned by its
tests. An eighth consumer is the argument for that extraction, not a cost.

`:env-deny` before `:env-allow` mirrors the deny/allow ordering settled in
`tool-permission-gate-design.md` §3.2a, for the same reason: a deny another key
can undo is not a deny.

**All three ship inert.** `:env-allow nil` means no filtering, so an untouched
install builds exactly the child environment it builds today.

### 3.3a Phase 3, as built

The three keys land as `config-schema` entries, so they inherit the whole
precedence chain for free and a per-agent `:config-extra` scopes one specialist
with no new plumbing — §3.4's claim, confirmed. `config/env-policy` resolves
them into `{:allow :deny :vars}`, and `util/apply-policy!` is the single
function that shapes a `ProcessBuilder`'s environment map. Four spawn sites call
it: `proc/apply-env!` (which covers all five shell paths at once), MCP, the
`aws` CLI, and — via `resolve-var` — ACP's `copy-env`.

**The scope comes from `proto/*current-agent*`, not an argument**, for the same
reason Phase 2 rejected an argument for the `.env` layer: a scope passed in is a
scope the next spawn site forgets. That var is already bound around every tool
dispatch, which is where a shell command comes from; unbound, it resolves the
global layer, which is the right answer for a spawn belonging to no agent. Both
cases are tested.

**The glob matcher moved to `util`.** `:env-allow` and `:env-deny` are its fifth
and sixth callers and sit below the agent component, so they could not reach the
copy in `common/tool_permission.clj`. It is now `util/core/glob.clj`, with
`tool-permission` keeping var-capturing aliases so a `with-redefs` in either
place still works, and its suite passes untouched.

Two decisions §3.3 left open, both settled by asking what a misconfiguration
does:

- **An `:env-allow` never has to list infrastructure.** `PATH`, `HOME`,
  `TMPDIR`, `SHELL`, `USER`, `LANG`, `TERM` and friends survive unless
  explicitly denied. An allowlist is a statement about secrets and
  configuration, not about whether a process can find `/bin/sh`; without the
  floor, the first `:env-allow ["GITHUB_TOKEN"]` yields a child with no `PATH`,
  every shell command failing, and no diagnosis. ACP's `:forward-env` lists
  `PATH` and `HOME` by hand, which is reasonable for one backend and not
  reasonable to ask of a key that applies to every child. `:env-deny` still
  removes them — a deny is unconditional, and that is the escape hatch for
  anyone who means it.
- **`:env-vars` names are admitted past `:env-allow`, but not past
  `:env-deny`.** The operator just named them in the same config; making them
  list each one twice is bureaucracy. Deny still wins, because a deny another
  key can talk round is not a deny — the rule `:tool-deny-tools` settled.

Order inside `apply-policy!`, and each step is a decision: the `.env` layer,
then `:env-vars`, then `:env-deny` REMOVES, then `:env-allow` removes what it
does not admit. Deny runs before allow so that a name surviving both is one the
operator admitted and did not deny.

**`nil` and `[]` both mean "no opinion" for `:env-allow`.** An empty vector
reading as "allow nothing" would make a half-written config strip every
variable from every child — the loudest possible failure for the quietest
possible edit.

**Secrets stay out by relying on machinery that already exists**, not new
machinery: `config$apply`'s `secret-scan` refuses a write whose value looks like
a credential, and `:env-vars` is a value-bearing key in a file committed at
`:project` scope. That reliance is now asserted rather than assumed —
`env-vars-with-a-secret-value-is-refused` drives `sk-…`, `AKIA…` and `ghp_…`
through `config$apply` and checks for `:stage :secret-detected`, plus a
non-secret value that is accepted.

**Tests:** `util/…/env_test.clj` 23 tests / 51 assertions; `agent/…/core/proc_test.clj`
11 tests, which spawn real processes and include the one that matters most —
two agents, same spawn site, different environments. Feature ledger moves to 49
capability features / 110 knobs / 187 config keys.

### 3.3b Phase 4, as built — and two items that turned out not to need building

**Dispatch-time scoping already worked.** Because Phase 3's keys are
`config-schema` keys, both routes land on the child's per-agent layer with no
dispatch change: a top-level `:env-deny` in the dispatch args, and
`:config-extra {:env-deny …}` from a defagent author. Measured — `config-source`
reports `:agent` for both. §3.4's "no new plumbing" claim delivered §7's Phase 4
item for free, so there was nothing to write except the tests that say so.

**A sub-agent could escape its parent's restriction, and that was the real
work.** `get-config` has no parent→child inheritance, by design: a sub-agent's
config layer is its own, and for most keys that is right. For a RESTRICTION it
is a hole. Measured before the fix: an agent with `:env-deny ["AWS_*"]`
dispatched a specialist whose resolved policy was `{:allow nil :deny [] :vars {}}`
— the credential reached the specialist's shell. A deny a delegation can undo is
not a deny; the same sentence `:tool-deny-tools` settled about
`:tool-allow-tools`.

`config/env-policies` now walks the ancestry (`runtime/get-parent-agent`, via a
`requiring-resolve` delay, the shape `core.tool` already uses) and returns the
chain root-first; `util/apply-policy!` takes a sequence. **Union of denies and
intersection of allows fall out of applying each level in turn** — neither has
to be computed, and an allow intersection is not computable from globs in the
general case anyway. A root agent yields one policy, which is exactly Phase 3,
so nothing without ancestry changes. The walk is depth-bounded at 32 so a
malformed cycle truncates a policy rather than hanging a spawn.

**Additions are grouped before removals, across the whole chain.** Interleaving
per level would let a descendant's `:env-vars` re-add a name its ancestor had
just removed — measured, and the reason the ordering is what it is. The
same-level `:env-vars`-exempt-from-`:env-allow` convenience from §3.3a is
deliberately NOT inherited: an operator writing both in one place should not
say it twice, but an ancestor never saw the descendant's declaration, so its
allowlist still binds.

**A malformed policy now throws instead of permitting.** Every field of a
non-map destructures to nil, which reads as "no opinion" and yields an
unfiltered child — the wrong-direction failure for a restriction, and the same
reason both tool-permission gates take `:on-error :throw`. Not hypothetical: a
stale `export-symbols` value-copy in a REPL handed `apply-policy!` a vector
while it still destructured a map, and the only symptom was a deny that quietly
stopped denying. That is how it would fail in production too, so it is now an
`ex-info`, pinned by a test.

**`--web` and `--sandbox` are deliberately unchanged**, which is the answer §4
said "deserves its own argument". Two measurements settle it. The re-exec'd
child inherits the parent's real environment including `BY_ENV_FILE` (verified
with a stand-in child: `BY_ENV_FILE` and `BY_SANDBOX_CHILD` both arrive), and a
real `by` child runs `load-from-dotenv!` unconditionally at `-dispatch` — so it
reloads the same `.env` itself and needs nothing pushed into it. Pushing it
would in fact be worse, converting `.env` values into REAL environment variables
for that child, which its own loader then refuses to override.

Applying the restriction policy to it is the substantive question, and the
answer is no: a re-exec'd `by` is **the same program with the same rights**, not
a subordinate. `--web` under a global `:env-deny ["AWS_*"]` would become less
privileged than a plain `by` — a shared browser session that silently cannot
reach the model — while the parent, whose own environment cannot be rewritten,
keeps the credential anyway. The asymmetry would buy nothing and break the
feature.

**Tool scope stays deferred** on §5's question 2, unchanged: the same effect is
reachable by giving the tool to a scoped agent, and a key nobody needs is a key
that has to be explained forever.

**Tests:** `util/…/env_test.clj` 30 tests / 66 assertions;
`agent/…/core/proc_test.clj` 14 tests, including an ancestor's deny reaching a
sub-agent's real child process, a descendant failing to re-add it, and an
ancestor's allowlist still leaving the child a `PATH`.

### 3.4 The three scopes, and how each is declared

| scope | declared in | holds |
|---|---|---|
| **global** | `.env` (values, incl. secrets — unchanged) and `config.edn` `[:agent :config]` for the three keys above (names and non-secrets only) | the baseline every agent sees |
| **agent** | `defagent :config-extra {:env-allow … :env-vars …}`, or the per-agent layer at dispatch | what one specialist sees |
| **tool** | `deftool :env-allow … :env-vars …` in the options map | what one tool's subprocess sees |

All three land on machinery that already exists:

- **Agent scope rides `:config-extra`.** The three keys are `config-schema`
  keys, so `setup-agent`'s split (`core/agent.clj:1166-1174`) routes them into
  `schema-overrides` → the per-agent precedence layer, with no new plumbing.
  `merge-agent-options` (`core/tool.clj:163-178`) already layers `:config-extra`
  map-wise, so a caller adding `:env-vars` does not wipe the defagent author's
  `:env-allow` — the bug fixed in `87ad38f`.
- **A parent can scope a child at dispatch**, exactly as the work tier does
  (`core/tool.clj:513-521`): resolve, inject as a top-level schema key, let
  `select-keys` land it on the child's layer.
- **Tool scope reads from `!tool-defs`.** `deftool` already passes every unknown
  kwarg through to `:meta` (`core/tool.clj:264-269`), and `dispatch-with-hooks`
  has the tool name in hand, so `env/child-env` can look the def up without a
  new registry.

### 3.5 The spawn side: `shell-pb` grows an env argument

```clojure
;; core/proc.clj — proposed
(defn shell-pb
  ([command] (shell-pb command nil))
  ([command env]                       ; env applied AFTER harden-env!
   (doto (ProcessBuilder. (into-array String (sh-argv command)))
     (harden-env!)
     (put-env! env)                    ; the documented "explicit override wins"
     (.redirectErrorStream true))))
```

One two-line change reaches all five shell paths. Then each caller supplies
`(env/child-env agent tool-name)`:

| site | today | after |
|---|---|---|
| `bash` tool (`common/tools.clj:243`) | full inheritance | filtered + scoped |
| coact fence (`coact_agent.clj:3919`) | full inheritance | filtered + scoped |
| `:bash` task (`task/executor.clj:58`) | `:job-config :env`, **no producer** | producer supplies it |
| `exec_backend.clj:57` | full inheritance | filtered + scoped |
| `skills.clj:64` | full inheritance | filtered + scoped |
| MCP stdio (`mcp/client.clj:281`) | full inheritance + declared `:env` | `child-env` as the base, declared `:env` on top |
| ACP stdio (`acp/.../stdio.clj:182`) | already scoped via `:forward-env` | `:forward-env` becomes a call to the same resolver |

**Two properties this buys immediately, independent of any scoping anyone
configures.** First, a child finally sees `.env` values: `env/child-env` reads
through the property bridge, so a `~/.brainyard/.env` `GH_TOKEN` reaches `gh` on
the direct-binary path, which it cannot today. Second, MCP gets ACP's
`strip-nested-session-markers!` treatment for free by sharing the builder.

The command-string prologue (`common/scripts.clj:660-702`,
`coact_agent.clj:420-433`) stays as it is. Its rationale — one prefix covers
three spawn paths that all go through `/bin/sh -c` — was correct when the three
paths had no shared env parameter. Once `shell-pb` has one, the prologue's
*future* additions should go through it instead, but rewriting the working
script-library plumbing is not this feature's job.

### 3.5a Phase 2, as built

`util/child-env` returns the `{name value}` map a spawned child needs in order
to see what this process sees. A `ProcessBuilder` child inherits the real
ENVIRONMENT and nothing else, so the one layer it lacks is exactly the one
`.env` supplied — which is why a `.env` `GH_TOKEN` reached `clj-llm` and never
reached `gh`.

**It cannot be "the property table".** That table also holds ~60 standard JVM
entries (`java.version`, `user.dir`, `os.arch`) plus whatever any library set,
and exporting those as environment variables would be wrong and noisy. Only the
loader knows which keys came from a `.env`, so it says so:
`dotenv.clj` calls `util/register-dotenv-keys!` after writing them — the same
injection shape as `persist/set-root!`, because the loader lives in the app
project and the resolver sits below every component. Before the loader runs
(a `bb` task, a test), the set is empty and `child-env` is `{}` — which is the
correct answer, not a missing feature.

**`proc/shell-pb` applies it by default rather than taking it as an argument**,
and that is the one place the plan in §3.5 was wrong. `proc.clj`'s docstring
said it "deliberately depends on nothing", which reads as an argument for the
parameter — but that independence was never the point in itself. The point,
stated in the same docstring, is that *"a sixth spawn site is a two-line change
that inherits [the protection] instead of a fourth copy of the bug"*. An env
layer passed as an argument fails exactly that test: the next site written would
omit it, silently, the way all five old sites each omitted the askpass
hardening. So `proc` now requires `util`, and `apply-dotenv!` sits between
`harden-env!` and any caller entry — hardening is a default, `.env` is what the
user wrote, an explicit caller entry is the most specific thing anyone said.
All five shell sites (`bash` tool, coact fence, skills, exec-backend, `:bash`
task) inherit it with no call-site change.

Three spawn sites build their own `ProcessBuilder` and were wired directly:
**MCP** (`client.clj` — without it, a server whose config says
`{"PGPASSWORD" "${PG_PW}"}` got its value expanded, since `expand-env-refs`
reads the property table, while a server that simply expects `PGPASSWORD` in
its environment got nothing: the same variable, present or absent depending on
which way the server asked), the **`aws` CLI** (`aws_commands.clj` — an
`AWS_PROFILE` in `.env` configured `by`'s own Bedrock calls and then did not
reach `aws`), and **ACP's `copy-env`** (`acp-client/registry.clj` — its
`:forward-env` allowlist read `System/getenv`, so an `ANTHROPIC_API_KEY` from
`.env` authenticated `by` and failed to reach the `claude-code` backend that
reads the same variable name).

**The prediction that was wrong.** §7 said Phase 2 would make MCP "inherit
ACP's marker stripping". It does not, and should not: `strip-nested-session-markers!`
removes `CLAUDECODE`, which exists to stop a nested *Claude* session
misbehaving. An MCP filesystem server is not a Claude session, and stripping a
marker there would be cargo-culting a fix for a problem it does not have. The
sharing that was worth having is the env layer, not the marker policy.

**A bug found by the tests, of the same class as Phase 0's.** `child-env`
originally skipped any key for which `(System/getenv k)` was non-nil, on the
reasoning that the child inherits it. An exported-but-**empty** variable is
non-nil, so the child received the blank while this process — whose
`resolve-var` falls through to the property — used the real value. Parent and
child disagreeing about one variable is the single thing `child-env` exists to
prevent. The guard is now `not-blank`. Not hypothetical: the shell this was
developed in exports `GIT_ASKPASS=` and `SSH_ASKPASS=` empty, which is how the
proc suite caught it, and that is a common agent/CI pattern.

**Tests.** `util/…/env_test.clj` grows the `child-env` cases (15 tests, 34
assertions total), and a new `agent/test/…/core/proc_test.clj` (6 tests) spawns
**real processes** on purpose: every other assertion here proves a map was
computed correctly, which is one step short of the claim being made. The claim
is that a subprocess can now see a `.env` value, and the only way to prove it is
to ask one.

```
echo "[$BY_TEST_PROC_TOKEN]"   before → []      after → [ghp-from-dotenv]
echo "[$GIT_ASKPASS]"                          → [false]   (hardening intact)
```

### 3.6 Fixing the four measured `.env` inconsistencies

Each is small and each is a prerequisite for the resolver being trustworthy.

1. **`schema-env-value` reads through `env/resolve`** instead of calling the
   `:env-fn` against `System/getenv` alone (`config.clj:1616-1623`). This fixes
   §1.2(a) and §1.2(c) at once, for all 69 keys, without touching a single
   `:env-fn`. It is the highest-value line in the proposal.
2. **`dotenv.clj` honors `BY_NO_DOTENV` and `BY_ENV_FILE`** (§1.2(b)), so the
   two loaders agree on their own control flags.
3. **`resolve-project-dir` gains the property bridge** its neighbour already has
   (§1.2(d)).
4. **The `env-or-prop` copies collapse** into `env/resolve` (§1.3), which
   is what turns `/login` and the `/model` picker honest about `.env`-supplied
   keys.

Note (1) has a precedence consequence worth stating: it does **not** move `.env`
above the process env, because `env/resolve` keeps that order (§3.2). It only
makes `.env` visible to a layer that could not see it before.

### 3.6a Phase 1, as built

All four defects in §1.2 are closed. The work was smaller than the list of
symptoms suggested, because three of the four shared one cause.

**63 reads in `core/config.clj` now go through `env/resolve-var`** — every
`:env-fn` body plus `resolve-project-dir` and the early `BY_PROFILE` read in
`load-global-config!`. One token per site; the only non-mechanical part is the
schema docstring, which now states the rule (`:env-fn` reads with
`env/resolve-var`, never `System/getenv`) so the next key added inherits it.
That single change closes §1.2(a) — `BY_*` knobs from `.env` on a non-wrapper
launch — and §1.2(c) — `~/.brainyard/.env`, which the wrapper never reads and
which therefore only ever existed as properties — and §1.2(d) for
`resolve-project-dir`, whose neighbour ten lines away already had the bridge.

**`dotenv.clj` honors `BY_NO_DOTENV` and `BY_ENV_FILE`** (§1.2(b)). Both are
read from the environment or a `-D` property, never from a `.env` — which is
what keeps them non-circular: the property table is written at the very end of
`load-from-dotenv!`, so a `.env` cannot switch off its own loader. A
`BY_ENV_FILE` naming a file that does not exist falls back to the walk, matching
`by-wrapper.sh`, but now *says so* on stderr: a typo'd path that silently loads
a different `.env` is worse than one that loads nothing, because the user
believes they pinned a file.

**One hazard Phase 1 introduces, and closes in the same change.** Now that
properties are read, a blank one reaches the coercion:
`#(if-some [v …] (= "true" v) ::env-unset)` would turn an exported-but-empty
`BY_X=` into a hard **false** at the highest-precedence layer, silently
overriding `config.edn` with a value nobody set. `resolve-var`'s blank-is-unset
rule means it falls through instead. Pinned by
`a-blank-env-knob-falls-through-instead-of-coercing`, which is the one test here
that passed *before* the change — for the wrong reason (the property was not
read at all), so it is a guard rather than a regression test.

**Verified, not assumed.** Reverting `config.clj` to the pre-change tree and
re-running the suite failed **5 of the new assertions**; restored, all pass. And
end-to-end, against the same command the original §1.2(a) measurement used:

```
$ … sessions list                                    → 13 sessions
$ BY_ENV_FILE=<pinned .env setting BY_PROJECT_DIR>   → [dotenv] loaded 1 key(s) from …/pinned.env
  … sessions list                                      No persisted sessions.
```

which exercises both halves at once — the pinned file is honored, and the
`BY_PROJECT_DIR` it supplies takes effect from a property.

**Tests:** `agent/test/…/core/config_test.clj` — 3 new deftests, 11 assertions.

---

## 4. What this deliberately does not fix

**Third-party SDKs that read the environment themselves.** The AWS credential
chain is the live case: `clj_llm/core/providers.clj:885` and
`aws-client/core/credentials.clj` read `AWS_PROFILE` / `AWS_ACCESS_KEY_ID`
directly, and the SDK reads more of them below that. Per-agent AWS credentials
are therefore reachable only by the `:lm-config` route (§1.4) or, for a
subprocess, by the child env — not by scoping what our own process sees. The doc
should say so rather than implying the resolver covers everything in-process.

**A shell fence can read the whole environment.** `printenv` in a fence prints
whatever the child inherited. Scoping decides what the child inherits, which is
the useful control; it does not stop the model from looking at what it got.

**Secrets remain in `.env` and the keychain.** This adds no secret store. The
existing ones (`clj-oauth`'s keychain/file store, `.env`) stay authoritative,
and `secret-scan` continues to refuse values in `config.edn`.

**`--web` and `--sandbox` children.** Both already accept `:child-env` and both
launchers ignore it. Wiring them is mechanical and belongs in a later phase, not
because it is hard but because those two re-exec `by` itself, where "what should
the child NOT inherit" deserves its own argument.

---

## 5. Open questions for review

1. **Should `:env-allow` default to `nil` (no filtering) forever, or should a
   later phase ship a curated default allowlist for spawned children?** Inert is
   the safe landing and matches the tool gate. But a default of "children inherit
   the operator's entire environment, including every API key" is the status quo
   precisely because nobody chose it. ACP already chose otherwise for its
   backends (`:forward-env` allowlists) and nothing broke.
2. **Does tool scope earn its place in v1?** Agent scope has a clear caller
   (a specialist that should not see production credentials). Tool scope is
   sharper — "this one tool gets this one token" — but the same effect is
   reachable by giving the tool to a scoped agent. Two scopes may be enough,
   with tool scope deferred until a real case appears.
3. **Where do the three keys live in `config.edn`?** `[:agent :config]` matches
   every other schema key and gets the whole precedence chain for free. But
   there is an existing `[:environment …]` section (`sandbox-mode` lives there)
   whose name is now ambiguous, and `[:mcp :servers <n> :config :env]` is a
   fourth spelling of the same idea. Consolidating spellings is more valuable
   than adding a fifth.
4. **Should `env/resolve` cache?** It is on the spawn path (cheap, rare) and on
   the `:env-fn` path (hot — `get-config` calls it for every key resolution
   before consulting any other layer). A memo would need the same invalidation
   discipline `!global-config` has, and `config$reload` has just shown what
   happens to a cache with no invalidation route.
5. **Does an env override belong in the audit trail?** A dispatch that changes
   which credential a sub-agent uses is at least as consequential as one that
   changes its model, and the work tier already logs `::tier-routed` on every
   dispatch. The value must never be logged; the NAME and the scope should be.

---

## 6. Test obligations

- **Inertness, asserted end-to-end.** With no key set, `env/child-env` must
  produce a map byte-identical to what each of the seven spawn sites builds
  today, and `env/resolve` must agree with each of the `env-or-prop`
  copies it replaces — including the two that differ on blank-⇒-nil
  (`clj_llm/core/providers.clj:709-719` and `helpers.clj:78-80` treat `""` as
  unset; `main.clj:812-816` does not). Pick one semantics deliberately and pin
  the four call sites whose behavior changes.
- **The `.env` regressions, pinned by the measurements in §1.2.** A `BY_*` knob
  in a project `.env` must take effect on the direct-binary path; the same knob
  in `~/.brainyard/.env` must take effect; `BY_NO_DOTENV=1` must suppress both
  loaders. Each of these is a currently-failing assertion, so each is a
  regression test the moment it passes.
- **Precedence, per layer.** Tool over agent over session over process env over
  project `.env` over user `.env`. Assert the layer that *won*, not just the
  value — a test that checks the value passes when two layers happen to agree.
- **Deny is unconditional and not exemptable**, mirroring
  `tool-permission-gate-design.md` §6: a name in `:env-deny` stays hidden with
  the same name in `:env-allow` and in `:env-vars`.
- **Nothing leaks into a log or a result.** `env/child-env`'s output must never
  be logged whole. Assert that the mulog event carries names and scope only, and
  that a value matching `secret-patterns` never appears in any event or in any
  tool result. The MCP spawn already sets this precedent by logging the
  *unexpanded* args (`mcp/client.clj:295-300`).
- **`secret-scan` refuses `:env-vars` with a secret-shaped value**, at the
  `config$apply` boundary, with the existing `:stage :secret-detected` shape.
- **A scoped child actually differs.** Spawn a real subprocess under two agents
  with different `:env-vars` and assert `printenv` disagrees — the only test
  that proves the resolver reached a ProcessBuilder rather than merely computing
  a map.

---

## 7. Phasing

Each phase is independently shippable and independently useful; none requires
the next.

| phase | content | value on its own |
|---|---|---|
| **0** ✅ | `env/resolve` + collapse the `env-or-prop` copies. No new config keys. **Shipped — see §3.1a.** | `/login` and the `/model` picker stop lying about `.env`-supplied keys. |
| **1** ✅ | Route `schema-env-value` through it; reconcile `dotenv.clj`'s control flags; fix `resolve-project-dir`. **Shipped — see §3.6a.** | §1.2(a)(b)(c)(d) all close. `BY_*` knobs in `.env` work everywhere. |
| **2** ✅ | `env/child-env` + `proc/shell-pb` applies it + MCP, the `aws` CLI and ACP's forward-env. Still no config keys. **Shipped — see §3.5a.** | A `.env` `GH_TOKEN` reaches `gh` on the direct-binary path. |
| **3** ✅ | `:env-allow` / `:env-deny` / `:env-vars` at global + agent scope. **Shipped — see §3.3a.** | The feature as asked. |
| **4** ◐ | Sub-agent policy inheritance. Dispatch-time scoping already worked; `--web`/`--sandbox` investigated and deliberately unchanged; tool scope deferred on Q2. **Shipped — see §3.3b.** | An ancestor's restriction binds its descendants. |

Phases 0–2 are strictly bug-fixing and consolidation — they add no surface and
close four measured defects. If review rejects the scoping model in §3.3
entirely, those three phases still stand on their own.
