# Environment Scoping — One Resolver, Three Scopes, and the Difference Between a Knob and a Secret

> **Status: PHASE 0 SHIPPED; §3.3 onward is still a PROPOSAL.** §1 is measured
> against the tree; §3 is the design; §7 phases it and marks what has landed.
> Phase 0 built the resolver and collapsed the private copies — it added no
> config key and no scoping, and closed two live defects on its own. Its
> as-built map is §3.1a.
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
| **1** | Route `schema-env-value` through it; reconcile `dotenv.clj`'s control flags; fix `resolve-project-dir`. | §1.2(a)(b)(c)(d) all close. `BY_*` knobs in `.env` work everywhere. |
| **2** | `env/child-env` + `shell-pb`'s env argument + wire the five shell sites and MCP. Still no new config keys — the map is just "the resolver's view", so children finally see `.env`. | A `.env` `GH_TOKEN` reaches `gh` on the direct-binary path. MCP inherits ACP's marker stripping. |
| **3** | `:env-allow` / `:env-deny` / `:env-vars` at global + agent scope. | The feature as asked. |
| **4** | Tool scope (pending Q2); `--web` / `--sandbox` `:child-env`; dispatch-time scoping for sub-agents, mirroring the work tier. | |

Phases 0–2 are strictly bug-fixing and consolidation — they add no surface and
close four measured defects. If review rejects the scoping model in §3.3
entirely, those three phases still stand on their own.
