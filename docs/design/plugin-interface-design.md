# Plugin interface — a package boundary for what brainyard already extends

> **Status: RESEARCH — NOT PLANNED.** Deliberately not on the roadmap, and this
> header is the finding, not a disclaimer. Read §0 before §1.
>
> This is the synthesis of a survey of the existing extension machinery
> (`common/boot.clj`, `common/user_{tools,agents,hooks}.clj`,
> `common/skills.clj`, `core/{tool,hooks,config}.clj`, `mcp/`) against Claude
> Code plugins, MCP `2026-07-28`, ACP, Gemini CLI extensions, opencode, VS Code,
> lazy.nvim, babashka pods, HashiCorp go-plugin and Extism.
>
> Related: `tool-agent-design.md` and `meta-agent-design.md` (the authoring
> agents whose output this would package), `event-bus-and-reactor.md` (the
> event surface a plugin hook would ride), `native-image-design.md` §14 (the
> class-init constraint that shapes the loading model), `mcp-agent-design.md`.

## 0. Why this is not planned

Most docs in this directory are marked *Shipped*. This one is marked *Research*
on purpose, because the survey below answers a question nobody has asked yet.

**A plugin system is a distribution mechanism, and its value scales with the
number of third-party authors.** Assessed 2026-08-28:

| Signal | Value |
|---|---|
| Stars / forks / open issues | 0 / 1 / 0 |
| Contributors | 1 |
| External PRs, ever | 0 |
| `plugins/` directories on disk | none |
| Extensions in use | 4 tools, 2 hooks, 3 skills, 12 agents — all authored by the maintainer |

Everything in §§5–15 — manifests, install/uninstall, versioning, lockfiles,
capability grants, namespacing — exists to let *strangers* coordinate. There are
no strangers. Building it now is infrastructure for a problem the project does
not have.

**The decisive argument is not the user count, though — it is MCP.** brainyard
already ships an MCP client (§3, tier 3), and MCP already has an ecosystem this
project does not. If someone wants to extend brainyard with real functionality
today, the correct answer is "write an MCP server", and that answer is *better
than a brainyard plugin would be*: the same server also runs in Claude Code,
Cursor and Zed. A brainyard-specific format would ask authors to write something
that works in exactly one host, competing against a standard that gives them
many. That inverts the usual "build a plugin API to grow an ecosystem" logic —
it would be a strictly worse distribution channel than the one already in the
binary.

### What was extracted and is being fixed anyway

Three findings here are bugs in the **current** single-author system and do not
need any of this document's machinery. They are tracked separately:

1. **Tool registration collides silently** (§2 gap 2) — and in **six** places,
   not one. Every writer into `!tool-defs` is a bare `swap! … assoc` with no
   occupancy check: `core/tool.clj:205` (`deftool`), `user_tools.clj:188`,
   `user_agents.clj:177`, `skills.clj:1213`, `mcp/integration.clj:646`,
   `a2a.clj:188`. Distinct id prefixes (`user$tool$`, `user$agent$`, `skill$`,
   `mcp$<server>$`, `a2a$<peer>$`, bare) keep *cross*-source collisions
   impossible, so the live exposure is within-source last-write-wins — two
   `.edn` files declaring the same `:name`, in undefined file order.
   `skills.clj` is the one that already solved it: `qualified-skill-id`
   (`:1135`) exists for "a skill that lost the bare `:skill$<name>` id to a more
   local backend". The fix is that precedent applied to the other five.
2. **2 of 47 hook events are gated** (§2 gap 3) — counted exactly: 47 entries in
   `event-catalog`, `:gates? true` on `:agent.ask/finalize` (`hooks.clj:124`)
   and `:agent.tool-use/pre` (`:140`). The one capability MCP structurally
   cannot provide, and a pure host change.
3. **Load failures name their source only in the phase that rarely fails**
   (§13) — the split is the finding. The *read* phase attributes correctly:
   `::load-user-tool-read-failed` and `::load-user-hook-read-failed` carry
   `:file`, `::load-user-agent-failed` carries `:dir`. The *install* phase, the
   one that actually fails, does not: `::load-user-tool-failed`
   (`user_tools.clj:288`) carries a bare `:name` and `::load-user-hook-failed`
   (`user_hooks.clj:314`) a bare `:id`. So an EDN parse error is traceable to a
   file and an SCI body error is not.

### What would make this planned

Any one of: someone asks how to share a tool; a second contributor appears; or a
need arises that MCP genuinely cannot express — which in practice means **a
hook**, since interception is the one layer no capability protocol carries (§4).

Until then the durable value of this document is the constraint map, not the
plan: the tier model and SCI ceiling (§3), what MCP can and cannot carry (§4,
§14 Phase 5), the renderer boundary (§11), and the verification property that
SCI resolves symbols at analysis time (§13). Those stay true regardless.

## 1. The problem statement

**brainyard already has a plugin system. It has no plugin package.**

Five kinds of user-authored contribution already register into live registries,
are already discoverable, and already survive the native-image closed world:

| Contribution | Registry | On disk | ID |
|---|---|---|---|
| Tools | `!tool-defs` (`core/tool.clj:110`) | `.brainyard/tools/<n>.edn` + `.clj` | `:user$tool$<n>` |
| Agents | `!tool-defs` | `.brainyard/agents/user$agent/<n>/{agent.edn,instruction.md,tool-context.md}` | `:user$agent$<n>` |
| Hooks | `!hooks` (`core/hooks.clj`) | `.brainyard/hooks/<id>.edn` + `.clj` | `:user-hook/<id>` |
| Skills | `!tool-defs` | `SKILL.md` across four roots | `:skill$<n>` |
| MCP servers | `mcp/integration.clj` | `[:mcp :servers]` in `.brainyard/config.edn` | `mcp__…` |

`common/boot.clj` already carries the load-bearing insight that every plugin
system for a closed-world binary eventually arrives at, and states it better
than most shipped systems document it:

```
PHASE 1 (boot)        metadata -> !tool-defs        no agent needed
PHASE 2 (coact-init)  bodies   -> tools sandbox     needs the palette
```

with the native-image discipline spelled out: registration must never be a
namespace-load `defonce` side effect, because `ai.brainyard.*` initializes at
**build** time and a load-time scan bakes the build machine's directories into
the image heap. The guard is a `defonce` atom holding `false` — safe to bake —
with the scan behind a runtime `compare-and-set!`.

That is the hard part, and it is done. What is missing is everything to do with
**a plugin being a thing**: a name, a version, an author, an install, an
uninstall, a namespace of its own, and a statement of what it is allowed to do.

## 2. The gaps, precisely

1. **No package boundary.** Contributions are loose files under `.brainyard/`.
   Nothing can be installed, versioned, updated, uninstalled, or attributed.
   There is no answer to "where did this tool come from?"
2. **Flat namespace, and collisions are silent.** `user$tool$deploy` is a
   single global slot, and `user-tools/register!` is a plain
   `(swap! !tool-defs assoc id …)` — its docstring says "register **or
   replace**". Two sources shipping a `deploy` tool do not conflict; the second
   one silently wins, and `.edn` file order is undefined. The only collision
   signal anywhere is the advisory `:collision` flag on
   `tool-agent$validate`, which an author has to opt into calling. Silent
   last-write-wins is worse than a throw, and it is the single strongest
   argument for namespacing.
3. **User hooks are observe-only, and there is almost nothing to gate.** The
   catalog has 47 events and exactly **two** carry `:gates? true` —
   `:agent.tool-use/pre` (`hooks.clj:140`) and `:agent.ask/finalize`
   (`hooks.clj:124`). User hooks route through `fire!` and can never block or
   modify. Claude Code ships ~32 events with a full decision protocol. This is
   the largest capability gap and the one most likely to motivate a plugin at
   all — interception is the layer nobody can get from MCP.
4. **Slash commands are hardcoded — but this is a discoverability gap, not a
   reachability one.** `bases/agent-tui/…/commands.clj` dispatches `/` through
   hardcoded `case` arms, with no contribution point. A contributed tool is
   nonetheless already *invocable*: `parse-command` (`commands.clj:1623`)
   classifies `:`-prefixed input as "direct internal tool invocation", so
   `:plugin$acme-deploy$deploy` reaches a registered tool with no new
   machinery, and an unrecognised `/` falls back to the agent. What a plugin
   cannot get is a name in `/help`, autocomplete, and an arg hint. Worth being
   precise about, because it means the slash contribution point (§6) buys
   ergonomics and can be deferred, rather than gating a plugin's usefulness.
5. **No config-schema contribution.** `core/config.clj`'s `config-schema` is a
   static map. A plugin cannot declare a key, so it cannot participate in the
   documented precedence chain (env > per-agent > session > `config.edn` >
   default) and has nowhere to put its settings.
6. **No lazy activation.** `boot-registries!` scans everything, always. The
   skill scan alone slurps and parses every `SKILL.md` across four roots, which
   is why it already needed a `:sync`/`:async`/`:skip` knob. Against a ~50 ms
   startup budget, N plugins scanning eagerly is the thing that kills this.
7. **No trust boundary.** A user tool body is SCI-evaluated with the agent's
   **full** `auto-tool-bindings` palette as extra bindings. Correct for a def
   the user just authored through `tool-agent$create`; wrong for something
   fetched from a URL.

Gaps 1–2 are the package. Gaps 3–5 are contribution points that do not exist
yet. Gaps 6–7 are what stop the whole thing being a liability.

## 3. Native-image is not the blocker

The reflexive objection — GraalVM's closed world means no runtime code loading,
therefore no plugins — is wrong here, and brainyard has already disproved it
twice in its own source tree.

- **SCI** (`components/clj-sandbox`) evaluates Clojure inside the native image
  with the host controlling `:namespaces`, `:classes` and the allow/deny lists.
  This is exactly how babashka itself works. Joyride demonstrates the model
  scaling to a full editor extension API. brainyard already uses it for
  code-eval and for user tool/hook bodies; using it for plugins is not a new
  mechanism, only a new caller with a narrower allowlist.
- **MCP stdio** (`agent/mcp/client.clj`) spawns a subprocess and speaks
  newline-delimited JSON-RPC. Nothing about that touches class loading.
- **Babashka pods** are the closest prior art in existence: same language, same
  compiler, same closed world, same startup obsession. A pod is a separate
  binary; `load-pod` sends `describe` and **synthesizes real Clojure namespaces
  and vars** from the reply, so the RPC is invisible at the call site.

So the design space is not "can we", it is "which of three known-good
mechanisms does each contribution type use", and the answer differs per type.

### The capability tiers, and the ceiling between 2 and 3

Those mechanisms are not interchangeable, and the difference is not a policy
dial. Ranked by what an author can actually reach:

| Tier | Mechanism | New deps? | New host primitives? | Cost |
|---|---|---|---|---|
| 1. Declarative | manifest only — skills, agent prose, config keys, commands | n/a | no | zero |
| 2. SCI body | the `user_tools` / `user_hooks` path | **no** | **no** | zero |
| 3. Subprocess | MCP stdio — **ships today** | yes | yes, but tools only | a process |
| 4. Pod | bencode + its own Clojure runtime | yes | yes, and non-tool contributions if extended | a process + a protocol |

**Tier 2 cannot become tier 3 by granting it capabilities.** This is worth
stating flatly because §9's `:capabilities` map invites the opposite reading —
that a body is restricted by policy and could be un-restricted. It cannot. The
ceiling is structural, and `components/clj-sandbox/…/sandbox.clj` is precise
about why:

- **`:classes` is an enumeration, not a filter.** `sci-classes` (`:87`) holds
  exactly 14 symbols — `Math`, the four boxed numerics, `Thread`, and eight
  `java.time` classes. `full-classes`'s own docstring (`:109`) records the
  consequence: "SCI resolves classes ONLY by the symbols enumerated in the
  `:classes` map — `:allow :all` lifts per-class allow-gating but does **not**
  enable resolution of un-enumerated classes or fully-qualified names." There is
  no interop escape, at any interop level, to a class the host did not name.
- **The namespace palette is four additions.** `library-namespaces` (`:174`)
  adds `clojure.core.protocols`, `clojure.pprint`, `clojure.data.json` and
  `clojure.data` on top of what SCI bundles (`clojure.string`, `.set`, `.walk`,
  `.edn`, `.template`, `.repl`). There is no `require`, so a body cannot reach
  a library the host did not pre-copy — and a plugin cannot ship one.
- **I/O is not in the restricted palette at all.** `clojure.java.io`,
  `clojure.java.shell` (`full-namespaces` `:199`) and `slurp`/`spit`/`sh`
  (`full-user-aliases` `:209`) are `:full`-only, and `sci-deny` (`:104`) blocks
  `System`, `Runtime`, `ProcessBuilder` and `ClassLoader` outright.
- **`:full` is not a real option under the shipping binary anyway.** Same file,
  `:119`: "SCI interop relies on reflection, so under the native `by` binary
  only classes already in the reflection config resolve at runtime; the `:full`
  palette is fully usable only under the JVM uberjar (`BY_JAR=1`)."

`user_tools.clj`'s own ns docstring (`:22`) states the design intent that
follows from all of this: user tools are "**macros over the existing tool
palette, not new host primitives** (no new privilege beyond what the sandbox
already grants)."

Three consequences that shape the rest of this document:

1. **A tier-2 body does I/O by calling a tool.** It reads a file by calling
   `read-file`, hits the network by calling `fetch-url` or `bash`. So its
   capability is *entirely* the set of tool symbols it is handed — which is why
   §9's `:tools` is the only row in that table with teeth at tier 2, and why the
   path/net rows there are enforced against **`bash`**, not against the body.
2. **Anything needing a library, a class, or real I/O is tier 3 or 4 by
   construction** — not by permission. A Postgres driver, a vendor SDK, an HTTP
   client, a native binding, anything written in another language: none of these
   are a `:capabilities` grant away.
3. **Tier 3 already exists and already ships.** MCP is brainyard's "rich
   functions in a separate runtime module" mechanism today, spawned by
   `mcp/client.clj`, and a plugin can carry MCP servers in `:contributes :mcp`
   from Phase 1. The gap pods would close is therefore **narrower than it looks**
   — Clojure data fidelity (EDN, metadata, real collections, `ex-info` across
   the boundary) and contributions MCP has no vocabulary for. That is a real
   gap, but it is not "plugins can't do rich work", and it should not be
   sequenced as if it were (§14).

## 4. What to take from the prior art

Every agentic CLI surveyed — Claude Code, Codex, Gemini CLI, opencode, Cursor,
Continue — independently converged on the same three layers:

```
CONTEXT       markdown/rules       what the model knows      (standardized-ish)
CAPABILITY    MCP servers          what the model can do     (standardized)
INTERCEPTION  proprietary hooks    what the harness does     (proprietary EVERYWHERE)
```

Layer 3 is proprietary in every single one, and it carries all the security
exposure. That is not an accident: MCP is a *capability* protocol, not an
*interception* protocol, and `2026-07-28` moved decisively away from
server→host callbacks by deprecating both roots and sampling. Do not try to
express hooks in MCP.

Specific ideas worth lifting, each with the reason:

**From Claude Code**

- *Manifest optional; name falls back to the directory name.* What makes
  `--plugin-dir ./scratch` a one-command experience rather than a ceremony.
- *`${PLUGIN_ROOT}` vs `${PLUGIN_DATA}`.* Root is a versioned cache directory
  that changes on **every update**; data survives. Any system that copies on
  install needs both, and needs to say which is which.
- *ADD vs REPLACE per component path.* `skills` adds to the default scan;
  every other path replaces its default directory. Subtle enough to be worth
  deciding on purpose.
- *Two-layer hook protocol.* Exit code for the coarse case, JSON on stdout for
  the rich case, `hookSpecificOutput.hookEventName` tagging the shape. Scales
  to 32 events without a per-event stdout format. **Exit 2 is not overridable
  by a JSON `allow`** — the coarse signal always wins, which is the right
  default when the two disagree.
- *Hooks may tighten but never loosen past the permission system.* A hook
  returning `allow` still cannot override a deny rule; deny and ask rules are
  evaluated regardless of what the hook says. One rule, and it is the entire
  reason hooks are not a permission bypass.
- *Plugin agents may not declare hooks, MCP servers, or a permission mode.*
  Explicitly for security — no smuggling new execution surfaces in through an
  agent file. The equivalent here: a plugin agent contributes prose and a tool
  list, never a `:config-extra` carrying a `:permission-fn`.
- *Copy on install, never symlink.* What makes path-traversal rejection and
  outside-symlink skipping enforceable rather than advisory.
- *`ask` prompts are labelled by source* (`[settings]`, `[plugin:<name>]`,
  `[skill]`), so the user can see who is asking.

**From Gemini CLI**

- *Environment sanitization by declaration.* An extension receives **only** the
  env vars it declared; the host environment is not inherited, and sensitive
  values go to the keychain. A real capability boundary for almost no cost.
- *Conflict resolution is prefixing, never failure.* Extension commands rank
  lowest; a user/project command claims the bare name and the extension's gets
  namespaced. Nothing silently shadows and nothing hard-errors.

**From VS Code**

- *The manifest is the contribution.* `contributes` is complete enough that the
  command palette, keybindings, settings UI and menus are all populated
  **without running any extension code**. This is the single most transferable
  idea and the one that makes laziness possible at all.
- *Derive activation from the contribution.* Since 1.74, declaring a command
  auto-generates its `onCommand:` activation. The manifest was already the
  source of truth; making authors restate it produced bugs.
- *Proposed API is opt-in, dev-channel-only, and structurally unpublishable.*
  MCP's 12-month deprecation window and go-plugin's `VersionedPlugins` are
  mitigation for debt already incurred. This prevents incurring it.
- *One `when` predicate language across every contribution point*, evaluated by
  the host without calling extension code.

**From babashka pods**

- *Two-layer encoding.* Bencode framing (implementable anywhere in an
  afternoon) plus EDN/transit payloads (full Clojure fidelity). Splitting them
  is what makes a Rust plugin and a Clojure plugin equally cheap.
- *`describe` announces which `ops` the pod supports.* The capability set is
  itself discovered; never assume an op exists because you defined it.
- *`"defer": true` + `load-ns`.* Laziness at **namespace** granularity, from a
  field in the `describe` reply: a deferred namespace ships its name at
  handshake and its vars only when the client sends `load-ns`. The analogue
  here is a plugin that announces its contributions at phase 0 and materialises
  bodies at activation (§10) — same shape, and the reason describe/activate is
  worth keeping as two round trips rather than one.
- *Structured errors cross the boundary* (`ex-message` / `ex-data`), so a
  plugin failure lands as an idiomatic `ex-info`.
- *`BABASHKA_POD=true`.* One binary, two personalities, selected by env — the
  pattern brainyard already uses for `BY_WEB_CHILD` / `BY_SANDBOX_CHILD`.

**From Extism**

- *`allowed_paths` is a remapping, not a boolean.* A host directory appears to
  the plugin at some other path, so the plugin cannot even name what it was not
  given. Capability by namespace construction rather than by check.
- *Empty allowlist denies; null permits.* Making the degenerate case explicit
  rather than nullable.

**From Neovim's rplugin hosts (a warning)**

Issue #5429: when a plugin host dies there is no way for its plugins to resume
without restarting the editor. Process isolation converts "one plugin crashes
the app" into "all plugins of that language are silently dead until restart",
which users experience as worse. **Isolation only counts if recovery ships with
it.**

**What to reject**

- Claude Code's marketplace surface: 8 source types plus 3 pseudo-marketplaces
  is more distribution machinery than this project needs.
- `bin/`-on-PATH and `settings.json: {"agent": …}` — two paths by which a
  plugin silently takes over the main thread with nothing selected by the user.
- opencode's `$` shell handle in plugin context, which makes every other
  restriction decorative.

## 5. The manifest — `plugin.edn`

Static EDN. Read at boot. **Spawns nothing, evaluates nothing, requires no
agent.** Everything else in this document depends on that sentence staying
true.

```clojure
{:name        "acme-deploy"              ; required IF the manifest exists;
                                         ; otherwise the directory name
 :version     "1.2.0"
 :description "Deploy helpers for the Acme stack"
 :author      {:name "Acme" :url "https://acme.example"}
 :license     "MIT"
 :homepage    "https://github.com/acme/by-deploy"

 ;; The runtime contract. `by` refuses to load a plugin whose requirement it
 ;; cannot satisfy, and the REFUSAL CARRIES THE RECOVERY DATA (what this
 ;; runtime actually is) — cf. MCP's UnsupportedProtocolVersionError.
 :requires    {:runtime ">=0.7.0"}
 :api-proposals #{}                      ; unstable surfaces; see §12

 ;; WHAT IT CONTRIBUTES. Complete enough to render /help, autocomplete,
 ;; `by agents`, `by plugins details` and the tool roster from data alone.
 :contributes
 {:tools    [{:name "deploy"
              :description "Deploy the current project"
              :input-schema [:map [:env {:optional true} :string]]
              :impl {:kind :sci :file "tools/deploy.clj"}}]

  :agents   [{:name "release"
              :description "Runs a release end to end"
              :instruction "instructions/release.md"
              :tool-context "instructions/release-tools.md"}]

  :skills   ["skills/"]                  ; ADDS to the default scan (§8)

  :commands [{:name "deploy"             ; a TUI slash command: /acme-deploy:deploy
              :description "Deploy the current project"
              :arg-hint "[env]"
              :dispatch {:tool "deploy"}}]   ; resolves within this plugin

  :hooks    [{:id "audit-bash"
              :event :agent.tool-use/pre
              :match {:tool-name "bash"}
              :priority 10
              :decision? true            ; §9 — requires :capabilities #{:gate-tools}
              :impl {:kind :sci :file "hooks/audit.clj"}}]

  :config   [{:key :acme/registry
              :type "string"
              :default "ghcr.io/acme"
              :doc "Container registry to push to"}]

  :mcp      {"acme-api" {:command "acme-mcp" :args ["serve"]}}}

 ;; WHEN IT WAKES UP. Nothing above is live until one of these fires (§7).
 :activation #{[:command "deploy"]
               [:tool "deploy"]
               [:project-contains "acme.toml"]}

 ;; WHETHER IT APPLIES AT ALL. Host-evaluated predicate over resolved config
 ;; and project facts; never calls plugin code (§7).
 :when {:project-contains "acme.toml"}

 ;; WHAT IT MAY DO. Absent => the empty set. Nothing is ambient (§9).
 :capabilities
 {:env    #{"ACME_TOKEN"}                ; nothing else is inherited
  :read   ["${PROJECT_DIR}"]
  :write  ["${PLUGIN_DATA}"]
  :net    #{"acme.example"}              ; #{} denies; :all permits (explicitly)
  :tools  #{"bash" "read-file"}          ; the SCI palette it may reach
  :gate   #{}}                           ; §9 — decision-returning hook events

 ;; User-supplied settings, prompted on install, referenced as
 ;; ${user-config/token}. Sensitive values go to the OS keychain, never
 ;; to config.edn.
 :user-config
 {:token {:type "string" :title "Acme API token" :sensitive? true :required? true}}}
```

### Variables

| Variable | Meaning |
|---|---|
| `${PLUGIN_ROOT}` | Install directory. **Changes on every update** (versioned cache). |
| `${PLUGIN_DATA}` | `~/.brainyard/plugins/data/<id>/`. Survives updates. |
| `${PROJECT_DIR}` | Resolved project root (post `-C` / `BY_PROJECT_DIR`). |
| `${WORKING_DIR}` | Resolved working dir. |
| `${SESSION_ID}` | Current session. |
| `${user-config/<k>}` | A `:user-config` value. |

**`${user-config/*}` must be rejected anywhere the value reaches a shell.**
Claude Code learned this and carved it out of shell-form hook commands
explicitly. brainyard already has the correct instinct in
`display_block_ui/open-in-editor!` — `sh-quote`, not `pr-str`, because
`pr-str`'s double quotes still expand `$VAR` and backticks. Same class of bug,
same answer: env-var form only.

### Manifest hygiene rules

- **Unknown top-level keys are ignored** (a warning from `by plugins validate`,
  an error under `--strict`). A **known** key with the wrong type **fails the
  load** — a typo that silently disables a capability is worse than a refusal.
- **All paths are relative and may not escape the plugin root.** Enforced by
  copy-on-install rather than by string checking, so it holds regardless of how
  clever the path is. Symlinks pointing outside are skipped.
- **No manifest is legal.** A directory containing `tools/`, `skills/`,
  `agents/` or `hooks/` is a plugin named after its directory.

## 6. Contribution points

Five exist and get packaged; two are new.

| Point | Status | Target registry | Mechanism |
|---|---|---|---|
| Tools | exists | `!tool-defs` | SCI body, phase 2 |
| Agents | exists | `!tool-defs` | prose, phase 1 (no body) |
| Skills | exists | `!tool-defs` | `SKILL.md` |
| Hooks | exists | `!hooks` | SCI body, phase 2 |
| MCP servers | exists | `mcp/integration` | subprocess |
| **Slash commands** | **new** | new `!command-contribs` | declarative dispatch |
| **Config keys** | **new** | `config-schema` | declarative |

### Slash commands (new)

`bases/agent-tui/…/commands.clj` gains a lookup against a contribution registry
**after** its built-in dispatch and **before** the existing agent fallback,
never before the built-ins. A contributed command is declarative — it names a
tool/agent/skill to dispatch to plus an arg hint. It does not get a handler
function, because a handler function is a foothold in the TUI and §11 says
plugins do not get one.

This is the lowest-value contribution point of the two, precisely because the
`:` channel already reaches contributed tools by id (§2 gap 4). It buys `/help`
presence, autocomplete and a friendly name — real ergonomics, but not a
capability. Sequence it accordingly.

### Config keys (new)

`config-schema` (`core/config.clj:79`) becomes `static-schema` merged with
contributed entries, so `get-config`'s precedence chain and its
`::config-resolved` tracking work unchanged.

**A contributed entry may declare `:default` only, never `:default-fn`.** The
existing schema permits either — `:default-fn` is a 0-arity callable
`get-config` invokes lazily as the final fallback — and that is a code
execution foothold reached on an ordinary config read, at a point far from
anything that looks like plugin activation. Static values only.

Contributed keys **must** be namespaced (`:acme/registry`); an unnamespaced
contributed key is rejected, because the built-in schema is unnamespaced and a
collision there would silently reroute a core setting.

An env-var binding for a contributed key is derived, never author-supplied:
`:acme/registry` → `BY_ACME_REGISTRY`. Letting a plugin name its own env var
lets it claim `BY_ENABLE_GRAPH_MEMORY`.

## 7. Activation and `when`

Two separate questions, deliberately not merged:

- **`:when`** — does this plugin apply here at all? Evaluated once at boot.
  False ⇒ the plugin contributes nothing and is not listed.
- **`:activation`** — given that it applies, what wakes it up? Until one fires,
  contributions exist as **metadata only**: listed, autocompleted, described,
  but with no body evaluated and no subprocess spawned.

Activation vocabulary, taken from the intersection of VS Code's activation
events and lazy.nvim's triggers, restricted to what an agent runtime can
actually observe:

| Trigger | Fires when |
|---|---|
| `[:command "x"]` | the user runs the contributed command |
| `[:tool "x"]` | the LLM calls the contributed tool |
| `[:agent "x"]` | the router dispatches to the contributed agent |
| `[:event :k]` | a contributed hook's event fires |
| `[:project-contains "glob"]` | the glob matches under the project root |
| `[:startup-finished]` | after the first render — the sanctioned escape hatch |
| `[:always]` | eager. Discouraged, and reported by `by plugins details`. |

**Derive what you can.** A contributed tool `deploy` implies
`[:tool "deploy"]`; a contributed command implies `[:command …]`. `:activation`
is only for triggers that cannot be derived. Restating a derivable trigger is
redundancy that produces bugs (VS Code 1.74's exact lesson).

`:when` predicates are host-evaluated over resolved config and project facts —
never plugin code:

```clojure
{:project-contains "deps.edn"}
{:config [:enable-graph-memory true]}     ; reads the RESOLVED value
{:all [{:os :macos} {:config [:enable-mouse true]}]}
{:any [...]}  {:not {...}}
```

Reading resolved config means a plugin gated on `:enable-graph-memory` needs no
new machinery — it rides the documented precedence chain.

`[:startup-finished]` exists for the same reason VS Code's `onStartupFinished`
does: authors who must be eager will otherwise write `[:always]`, and the
startup budget pays for it. Give them the less harmful option.

## 8. Namespacing and precedence

Contributions are namespaced by plugin id:

```
tool     :plugin$acme-deploy$deploy
agent    :plugin$acme-deploy$release
skill    :skill$acme-deploy$…
hook     :plugin-hook/acme-deploy$audit-bash
command  /acme-deploy:deploy
config   :acme/registry            (namespaced keyword, author-chosen)
MCP tool mcp__plugin_acme-deploy_acme-api__…
```

A contributed slash command **also** gets the bare alias `/deploy` **unless
something already owns that name**. That rule is what makes migrating a local
def into a plugin non-breaking, and it is why Claude Code's plugin skills give
you both `/foo` and `/my-plugin:foo`.

Precedence, highest to lowest — mirroring the existing config chain so there is
one ordering to learn:

```
built-in  >  project (.brainyard/)  >  user (~/.brainyard/)  >  plugin
```

**A collision prefixes and logs; it never throws.** `::plugin-name-shadowed` is
emitted with both ids. Same reasoning as `::tier-clamped`: an author colliding
on a name is wrong about names, not about intent, and failing the load turns a
naming problem into an outage. It is also the difference between a plugin
*adding* a command and a plugin *hijacking* one.

The prefix rule applies to plugins only — but note it is *replacing* nothing,
because today's user-def path has no collision handling at all (§2 gap 2). A
plugin whose tool silently replaced a user's own `deploy` would be the same
bug with a worse blast radius, since the user did not author the plugin and has
no reason to suspect it. Namespacing plugins makes that structurally
impossible; whether to also give the flat user-def path a warning is a separate
question, and out of scope here.

## 9. Trust and capabilities

Nothing is ambient. `:capabilities` absent means the empty set.

**The table is tier-aware, and it has to be** (§3). A capability is not one
thing enforced one way: the same `:net` declaration is a *host allowlist* for a
subprocess and a *`bash` permission rule* for an SCI body, because a body has no
network of its own to restrict.

| Capability | Tier 2 (SCI body) | Tier 3/4 (subprocess, MCP, pod) |
|---|---|---|
| `:tools #{…}` | **the whole boundary.** The extra-bindings map handed to the sandbox — a strict subset of `auto-tool-bindings` | n/a — a subprocess has no palette |
| `:env #{…}` | n/a — no `System/getenv` (`sci-deny`) | only these are passed to the child; the host env is **not** inherited |
| `:read` / `:write` | **derived, not primary.** Enforced against the granted tools (`bash`, `read-file`, `write-file`), not the body | path allowlists; `os-sandbox` on macOS, host checks elsewhere |
| `:net #{…}` | **derived.** Enforced against `fetch-url` / `bash`; meaningless if neither is granted | host allowlist; `#{}` denies, `:all` must be written explicitly |
| `:gate #{…}` | which gated events this plugin may return decisions on | same |

Reading that table top-down gives the honest summary: **at tier 2 there is
really only one capability, and it is `:tools`.** Everything else is a statement
about how the granted tools should behave. That is not a weakness in the model —
it is the ceiling from §3 showing through, and it is why a tier-2 plugin is
cheap to reason about: enumerate its tool symbols and you have enumerated its
powers, with no interop, `require`, or class-resolution path left to audit.

It also means `:read`/`:write`/`:net` must not be *silently* inert. A manifest
declaring `:net #{"acme.example"}` while granting no network-capable tool is
either confused or aspirational; `by plugins validate` reports it (§13, level 4)
rather than accepting a declaration it will never enforce. The converse — a
`:read` allowlist alongside a granted `bash` — is where the enforcement actually
has to happen, and it lands in the permission layer, not the sandbox.

`:tools` is the one that changes existing behaviour. Today a user tool body
gets the agent's full palette; a plugin body gets the declared subset and
nothing else. That is the whole difference between "a def I wrote" and "a
package I installed", and it is why the flat `user$tool$` path can stay exactly
as it is rather than being retrofitted.

**`:sandbox-interop` is not a plugin-facing knob.** A plugin may never request
`:full`: it would hand it `clojure.java.io`, `clojure.java.shell` and
`slurp`/`spit`/`sh` (§3), collapsing every row above into "it has a shell" —
the exact failure this document rejects opencode's `$` handle for. A plugin body
evaluates at `:restricted`, on a host configured either way.

**Never hand a plugin a shell handle.** `bash` is grantable through `:tools`
and therefore visible in `by plugins details`, permission-gated, and logged.
An ambient `$` (opencode's model) would make every other row in that table
decorative.

### Hook decisions

Only a plugin declaring `:capabilities {:gate #{:agent.tool-use/pre}}` may
register a decision-returning hook on that event, and installing such a plugin
prompts separately from installing one that only observes. Codex reached the
same conclusion: **a hook is strictly more dangerous than a tool, because a
tool runs when the model chooses it and a hook runs unprompted on every turn.**

And the rule that makes gating survivable, straight from Claude Code:

> **A plugin hook may tighten. It may never loosen.**

A hook returning `:allow` does not bypass a permission denial; deny decisions
are evaluated regardless of what any hook returns. A hook can turn an allow
into a deny or an ask; it can never turn a deny into an allow. Multi-hook
precedence is `deny > ask > allow`, and a thrown handler is a deny, not a
skip — `:on-error :log` (today's user-hook default) is correct for observers
and wrong for gates.

This needs work on the host side first: with only two gated events, plugin
hooks have almost nothing to gate. Widening the gated set is a prerequisite,
not a follow-up (§14, Phase 3).

## 10. Loading model

Three phases, extending `boot.clj`'s existing two.

```
PHASE 0  DISCOVER + PARSE            no agent, no eval, no spawn
         scan ~/.brainyard/plugins/ and <project>/.brainyard/plugins/,
         read plugin.edn, evaluate :when, check :requires
         => registries hold metadata; /help, autocomplete, `by agents`,
            `by plugins list` are all correct

PHASE 1  ACTIVATE                    triggered, per plugin
         an :activation trigger fires => contributed config keys merge,
         MCP servers spawn, SCI bodies become eligible

PHASE 2  INSTALL BODIES              at coact-init, needs the palette
         SCI eval of tool/hook bodies into a per-plugin sandbox whose
         extra-bindings are the :capabilities :tools subset
```

Phase 0 is the whole point. **N installed plugins cost N file reads, not N
process spawns.** That is what makes this compatible with a binary whose
selling point is startup time.

Phase 2 reuses `user-tools/install-bodies!` and `user-hooks/load-user-hooks!`
verbatim, differing only in the bindings map handed in. The `__ut_<name>` /
`__uh_<id>` sandbox-var convention extends to `__pt_<plugin>$<name>` /
`__ph_<plugin>$<id>`.

Native-image discipline is unchanged and non-negotiable: guard atoms hold
`false` or `#{}` at build time; every scan sits behind a runtime
`compare-and-set!`. A plugin loader that scans at namespace-load time bakes the
build machine's plugin directory into the image.

### Supervision

MCP servers and any future pod processes get supervised, restarted with
backoff, and surfaced in a visible degraded state. Their stdout/stderr is
forwarded into mulog with plugin attribution — go-plugin's insight that you
capture the plugin's existing logging rather than asking authors to instrument.

This is Neovim #5429's lesson: isolation without recovery is worse than no
isolation, because the failure is silent. "Why is plugin X contributing
nothing" must be answerable from the log, to the same standard
`::config-resolved` and `::tier-routed` already set.

## 11. What a plugin may never touch

**The renderer.** Not negotiable, and specific to this codebase rather than a
general principle.

CLAUDE.md documents, at length, that TUI correctness lives in `!scrollback-src`
reflow, `layout/row->scrollback-idx` being `render-viewport!` read backwards,
`sessions/!tab-spans` being recorded *by* `format-tab-strip`, decorator
memoisation, and DECSTBM row accounting — and that violating any of them fails
**silently**. A plugin holding `!layout` would break all of it in ways that
present as "the tail of every line disappeared".

The bar is set by opencode, which has ~30 hooks and still exposes the TUI as
exactly three verbs (append to prompt, run a command, show a toast). Nobody
hands out the renderer. MCP Apps reaches the same place from a different
direction: a UI contribution is a *resource* the host renders into a container
it fully controls.

So a plugin that wants to draw contributes **a `(fn [cols] rows)` or structured
data**, never a reference to `!layout`, and never a live-block provider it
registers itself. `display-block`'s existing `BlockProvider` protocol
(`display_block/interface/protocol.clj:19`) is the right shape for this — a
plugin supplies the provider, the host owns `register!`, disposal and the
ticker. Note the registry is itself a protocol (`IBlockRegistry`), so a plugin
can be handed a scoped registry rather than the default one.

Also out of reach, for the same "no silent takeover" reason: replacing the main
thread's agent, putting executables on the bash tool's PATH, mutating another
plugin's contributions, and writing `~/.brainyard/config.edn`.

## 12. Versioning

- **`:requires {:runtime ">=0.7.0"}`** in the manifest. `--version` is already
  baked from `git describe`, so the comparison is available.
- **The refusal carries the recovery data.** "requires >=0.7.0, this runtime is
  0.6.2" — cf. MCP's `UnsupportedProtocolVersionError` shipping its `supported`
  list so the client can act.
- **`:api-proposals` are dev-build-only and structurally unpublishable.** A
  plugin naming a proposal fails to load on a release binary, full stop. This
  is VS Code's proposed-API rule and it is the cheapest good idea in the whole
  survey: MCP's 12-month deprecation window and go-plugin's `VersionedPlugins`
  are both mitigation for debt already taken on; this prevents taking it on.
- **`.brainyard/plugins-lock.edn`** — SHA per plugin. This project already has
  the posture (`SHA256SUMS`, `release-verify.sh`, sha-pinned `bb
  sqlite-vec:fetch` / `bb model2vec:fetch`); lazy.nvim's `lazy-lock.json` was
  the only reproducibility story in the entire survey and it costs one file.

## 13. Development and verification

Nothing above says how a plugin gets *written*, and a package format with no
authoring loop is a format nobody authors against. This section is not an
afterthought to the security model — it is largely the **same machinery read in
the other direction**, because the thing that constrains a plugin (§3's ceiling)
is also the thing that makes it checkable.

### The property that makes verification unusually strong here

From `install-bodies!`'s own docstring:

> Needs `extra-bindings` (the agent's `auto-tool-bindings`): **SCI resolves
> symbols during analysis, so a body calling `(bash :command …)` cannot eval
> without the palette bound.** That is the whole reason this is a separate phase
> from registration.

That is a verification primitive, not just a loading constraint. Because
resolution happens at **analysis** time rather than at call time:

- A body referencing a tool the plugin did not declare **fails to load**, not on
  some unlucky branch at 3am. There is no dynamic `resolve`, no `require`, no
  fully-qualified-class escape (§3) — so the set of host symbols a body can
  reach is decidable by evaluating the `def` and nothing else.
- Checking is therefore **static in the sense that matters and costs one eval**:
  `(def __probe <body>)` against a palette containing *only* the declared
  `:tools` either succeeds — proving every reference is satisfied by the
  declaration — or fails with the offending symbol.

**This is strictly stronger than anything available at tier 3 or 4.** An MCP
server or a pod is an opaque binary; the host can validate its `describe` reply
and nothing about its behaviour. Worth being explicit about, because it inverts
the usual intuition: the *least* powerful tier is the one that can be
mechanically verified, and buying tier-3 power means giving up tier-2
provability. That trade is fine — it just should be made knowingly, and it is a
second reason (alongside §3's) not to reach for a process by default.

### `by plugins validate` — four levels

The model already exists and ships: `tool-agent$validate` forks the live tools
sandbox, evals `(def __probe body)`, optionally runs the body once against
`:sample`, and returns `{:valid :name-ok :collision :schema-ok :body-ok
:sample-result :errors}` — **persisting nothing, registering nothing, mutating
no live state, and never throwing**. Plugin validation is that command widened
from one draft tool to a whole package, and it should keep all four of those
properties.

| Level | Checks | Needs |
|---|---|---|
| **1. Manifest** | schema; unknown keys (warn) vs known-key type errors (fail); every path relative and inside the root; `:requires` satisfiable; activation derivable; contributed config keys namespaced and `:default-fn`-free | file read |
| **2. Body** | each tool/hook body `(def __probe …)`-evals against a palette of **exactly** the declared `:tools` — no more | a throwaway sandbox |
| **3. Contract** | per-tool `:sample` args run once; result checked against `:output-schema` | level 2 + one eval |
| **4. Capability audit** | declared `:tools` vs symbols actually referenced; declared `:read`/`:write`/`:net` vs whether any granted tool can act on them (§9) | levels 1–2 |

Level 4 is the one that does not exist today and is worth the most. Level 2
catches **under**-declaration for free — an undeclared symbol simply fails to
resolve — so the residual risk is entirely **over**-declaration, and
over-declaration is exactly the security smell a reviewer cannot see by reading
a manifest. A plugin declaring `:tools #{"bash"}` whose bodies never call `bash`
is either sloppy or preparing to be. Report it as a diff:

```
acme-deploy  capability audit
  declared but unused:  bash            <- narrow this
  declared but inert:   :net #{"acme.example"}   (no network-capable tool granted)
  referenced:           read-file, fetch-url
```

Levels 1–2 must also run at **install** time, not only when an author chooses to
ask. Their cost is a file read and one eval per body — affordable — and the
alternative is discovering a package is broken at the first turn that touches
it.

**Validation is not a trust decision.** A plugin can be perfectly valid and
still malicious; level 4 narrows what a *reviewer* has to think about, it does
not decide for them. Install-time consent (§9) stays.

### The dev loop

`--plugin-dir <path>` (§14 Phase 1) loads an uninstalled directory from disk —
manifest optional, name defaulting to the directory name (§5), so a scratch
directory with a `tools/` in it is already a plugin. The one thing it must add
beyond "load it once" is **reload without restart**, since a binary that sells
on a ~50 ms startup still costs a session to restart, and losing session state
is what makes authors stop iterating.

The precedent is already in the tree, and so are the hazards:

- `reset-tools-sandbox!` (`user_tools.clj:77`) exists precisely for this — it
  drops `!tools-sandbox` and **both** phase guards, "for tests / reload". A
  plugin reload is the same operation scoped to one plugin rather than to
  everything.
- **The guards are the reload bug waiting to happen.** `!registered` and
  `!loaded` are sets of *dirs* seen this process, so `ensure-loaded!` no-ops on
  the second call. A reload that forgets to evict the plugin's entry silently
  does nothing, and the author concludes their edit had no effect — the worst
  possible failure for a dev loop, because it teaches them to distrust the tool.
- **Bodies unload untidily and that is fine.** `delete-user-tool!` records the
  existing answer: orphaned `__ut_<name>` / `user$tool$<name>` sandbox vars are
  "harmless (registry dispatch is gone) and clear on the next sandbox rebuild".
  Reload evicts from `!tool-defs` / `!hooks` — the registries are the authority
  — and lets the sandbox vars go stale.
- **Partial failure rolls back per contribution, not per plugin.**
  `install-bodies!` already "rolls a tool back OUT of `!tool-defs` if its body
  fails to eval, so the LLM is never shown a tool that cannot run." A plugin
  whose third tool fails should surface as degraded-with-a-reason (§10's
  supervision requirement), not as a silently absent package.

Two smaller things that decide whether authoring is pleasant:

- **`by plugins dev <dir>` should watch and reload**, because the loop is
  edit→invoke→read-error and anything that adds a manual step to it gets
  skipped.
- **Errors must name the plugin.** `install-bodies!` logs
  `::load-user-tool-failed` with a bare `:name`; with plugins in play that is
  ambiguous across packages. Plugin attribution in the log line is the same
  requirement §10 puts on supervision, and the same standard `::config-resolved`
  and `::tier-routed` already set.

### Testing a plugin

Level 3's `:sample` is a smoke test, not a test suite, and should not pretend
otherwise. A plugin that wants real tests writes them against its tools the way
anything else does — the useful thing the host can offer is a **hermetic load**:
construct the registries from one manifest, with a stub palette for the declared
`:tools`, asserting no other plugin and no user def is present. That is the
shape the existing agent test suites already use (structural + hermetic
pass-through), so it is a helper rather than a framework.

## 14. Phasing

The ordering follows from §3: **tier 3 already ships**, so the sequencing
question is not "when do plugins get to run rich code" — a plugin can carry an
MCP server from Phase 1 — but "when does each of the *boundary*, the
*capability model*, and the *authoring loop* land". Pods (tier 4) buy a narrower
increment than they appear to and are last accordingly.

**Phase 1 — the package, no new powers.**
`plugin.edn` + phase-0 discovery + namespacing + `by plugins list|details|validate`
(§13 levels 1–3) + `--plugin-dir` with reload. Contributions limited to what
already exists (tools, agents, skills, hooks, MCP). No capability enforcement
yet — plugins get what user defs get. Ships the *boundary* and nothing else, so
it can be evaluated on its own.

Note what this already permits, because it is easy to under-read: a Phase-1
plugin bundling an MCP server has **full tier-3 reach** — any language, any
dependency, real I/O — on day one. The `:capabilities` work in Phase 2 does not
unlock that; it constrains it.

**Phase 2 — capabilities and lazy activation.**
`:capabilities` enforcement (env allowlist, `:tools` subset, `os-sandbox` paths
on macOS), `:activation` + `:when`, supervision and log attribution, and §13's
level-4 capability audit — which only becomes meaningful once declarations are
enforced rather than advisory. This is what makes a third-party plugin
installable rather than merely loadable.

**Phase 3 — new contribution points.**
Slash commands, config keys. Widen the gated event set beyond two, then allow
`:gate` capabilities on plugin hooks under the tighten-never-loosen rule. The
event widening is a host change and gates Phase 3, not a follow-on to it.

**Phase 4 — distribution.**
Install from git/URL/local path, `plugins-lock.edn`, `by plugins install|update|uninstall`,
copy-on-install with a versioned cache and `${PLUGIN_DATA}`. Deliberately last:
a package format nobody has authored against is not worth a registry.

**Phase 5, speculative — out-of-process plugins (pods).**
Only if Phases 1–4 produce authors who want it, and the case is narrower than
"pods give plugins a real runtime" suggests — §3: MCP already gives them one.
What a pod adds over the MCP server a plugin can ship in Phase 1 is exactly two
things:

1. **Clojure data fidelity.** MCP is JSON, so keywords, sets, metadata, records
   and namespaced maps all round-trip lossily, and an error arrives as a string.
   Pods carry EDN and `ex-info` (`ex-message`/`ex-data`), so a Clojure plugin
   talks to a Clojure host in Clojure values. For a plugin whose payload *is*
   data this is the whole difference; for one that shells out it is nothing.
2. **Non-tool contributions from a subprocess.** MCP's vocabulary is tools,
   resources and prompts — it has no way to express a slash command, a config
   key, an agent or a hook. A pod-shaped protocol could carry them.

Both are real; neither is "plugins can do more work". Note also that (2) is the
weaker half of its own argument: contributions MCP cannot carry are exactly the
*declarative* ones, which a plugin already ships in its manifest without any
process at all (§6). The genuinely un-carriable case is a **hook** — interception
is the one layer no capability protocol expresses (§4) — and hooks are also the
one contribution where an out-of-process round trip on every turn is hardest to
afford. That tension should be resolved before building this, not during.

Cheaper than it looks on one axis: **bencode already ships in the binary.**
`clj-nrepl` depends on `nrepl/nrepl 1.3.0`, and `nrepl/bencode.clj` — with
`read-bencode`, `write-bencode`, `read-netstring`, `write-netstring` — is inside
the uberjar today. More expensive on another: **the MCP client cannot be reused
for transport.** `mcp/client.clj` wraps the child's stdout in a `BufferedReader`
and drives it with `.readLine` (`:224`, `:256`), which is correct for
newline-delimited JSON-RPC and wrong for bencode's length-prefixed binary
framing — `read-bencode` needs a `PushbackInputStream`. A parallel byte-oriented
transport is required, not a parameterisation of the existing one.

`BY_PLUGIN=1` as the personality switch, mirroring `BY_WEB_CHILD` /
`BY_SANDBOX_CHILD`. Supervision and recovery (§10) are a prerequisite here, not
a follow-up — this is the phase where Neovim #5429's failure mode becomes
reachable.

Explicitly **not** planned: a marketplace with multiple source types, WASM
(Extism-on-Chicory is real and native-image-viable, but experimental and a
large dependency for a binary that sells on size), and anything that lets a
plugin take the main thread.

## 15. Open questions

1. **Where do plugins live?** `plugins` needs an entry in
   `core/config.clj`'s `subdir-scope-policy`. `:both` is the honest answer
   (a project may vendor one; a user installs most globally), but `:both` means
   two independent scopes the runtime does not merge — so precedence between a
   project plugin and a user plugin of the same name needs deciding, not
   defaulting.
2. **Do plugins compose?** A plugin depending on another plugin's tool is
   plausible and every surveyed system eventually grew `dependencies`. Phase 1
   should probably say no and mean it.
3. **Is `:when` worth its cost at N=3 plugins?** Possibly not. It is cheap to
   add later and awkward to add after authors have shipped manifests, which is
   the usual argument for putting it in the schema early and enforcing it
   lazily.
4. **How does a plugin skill interact with the four existing skill roots?**
   `~/.claude/skills/` is already read. A plugin shipping `skills/` is a fifth
   root with different namespacing, and the ADD-vs-REPLACE rule needs to be
   stated for it explicitly.
5. **Should `tool-agent$create` / `meta-agent$create` gain a `:plugin` target?**
   The authoring agents already produce exactly the artifacts a plugin bundles.
   Letting them write into a plugin under development is the shortest path from
   "I made a tool" to "I published a plugin", and costs a directory argument.
