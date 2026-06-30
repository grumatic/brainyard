# MCP-Agent — MCP Management Specialist + Base MCP Substrate

> **Status:** Shipped. The lightweight redesign (keep mcp-agent's mechanism;
> install an MCP *use* substrate in the base agents) shipped in 2026-06. This
> doc is the as-built reference — the former
> `mcp-agent-lightweight-redesign.md` has been folded in here and removed.
>
> **As-built (verify against `common/mcp_agent.clj`, `mcp/commands.clj`,
> `mcp/permission.clj`, `mcp/integration.clj`, `common/agent_roster.clj`,
> `common/coact_agent.clj`, `common/react_agent.clj`):**
> - **mcp-agent is a thin wrapper over three polymorphic commands.** `mcp$server`
>   (list/info/config/capabilities/resources/prompts/health), `mcp$tools`
>   (list/call/read-resource/get-prompt), `mcp$lifecycle` (start/stop/restart).
>   All three are **deterministic mechanism** — discovery, RPC invocation,
>   connection lifecycle. The agent authors **nothing** (no dossier, no
>   frontmatter chain, no `mcp$*-write` helper family), so the lightweight half
>   was a confirmation, not a change: the mechanism was kept verbatim (§4).
> - **The substantive contribution shipped as a base MCP substrate.** A shared
>   `## Using MCP servers (mcp substrate)` system-context section
>   (`agent-roster/mcp-substrate-protocol`) is installed in BOTH base agents
>   (`coact-system-context` + `react-system-context`), and the MCP command family
>   (`mcp$server`/`mcp$tools`/`mcp$lifecycle`) rides `default-agent-roster`. So
>   **any** coact/react-derived agent can discover a server, read a native tool's
>   input-schema, and invoke it — read-only by default with a hard write/lifecycle
>   boundary (§5).
> - **mcp-agent stays the management owner.** Write-side calls, lifecycle
>   (`:stop`/`:restart`), troubleshooting, and complex multi-server flows remain
>   its job. Any agent USES MCP via the substrate; mcp-agent MANAGES it (§5.3).
> - **The side-effect gate is a runtime decision hook, not a registration-time
>   `:tool-use-control` stamp.** The redesign proposed stamping each
>   `:mcp$<server>$<tool>` with `:tool-use-control :approval-required` and routing
>   through `call-tool`'s `check-permission`. That would have been a silent no-op
>   in this codebase (`:tool-use-control` is **visibility-only**; the
>   `check-permission` regex path is dormant and doesn't gate `write-file`/`bash`).
>   **What shipped instead** (`mcp/permission.clj`): a single
>   `:agent.tool-use/pre` decision hook (like eval-agent's `eval-bash-guard`) that
>   fires inside `dispatch-with-hooks`, covering **both** the native
>   `mcp$<server>$<tool>` binding **and** the `mcp$tools :op :call` proxy (incl.
>   batches) in one place. On an unapproved side-effecting call it returns a
>   `:replace` verdict (`{:error …}`) and the call never runs. It bridges to the
>   **same `permission-fn` UI that actually gates `write-file`/`bash`** (extended
>   with a `:type :mcp-tool` branch), honors `[:permissions :mode]`
>   (`:auto-approve`/`:deny-by-default`/`:ask-each-time`, fail-closed when
>   headless), and downgrades `annotations.readOnlyHint` tools + tools matching the
>   `:mcp-allow-tools` glob allowlist. The doc's *intent* (uniform across
>   proxy+binding, fail-closed, no model-side read/write classifier, same seam as
>   `write-file`/`bash`) is exactly what shipped — only the named internal
>   mechanism in §5.4/§8 was wrong; read those sections through this correction.
> - **Registration stamps annotations, not approval policy.**
>   `register-mcp-tools-for-server!` records each native tool's
>   `:mcp-annotations` on the registry `:meta` so the runtime gate can read
>   `:readOnlyHint` — it does **not** stamp `:tool-use-control`. Classification
>   only *relaxes* the gate (auto-allow a read-only tool); it never *is* the gate.
> - Migration (§9) is **complete** — the substrate section + roster add + the
>   permission gate all landed; explore-agent/exec-agent keep their explicit MCP
>   bindings as a harmless no-op now that the family also rides the base roster.
>
> **Scope:** `mcp_agent.clj`, `mcp/commands.clj`, `mcp/permission.clj`,
> `mcp/integration.clj` (registration), plus the shared MCP-substrate section +
> command-family roster add in the base agents (`agent_roster.clj`,
> `coact_agent.clj`, `react_agent.clj`).
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`.
> **Related reading:** `docs/design/agent-lightweight-redesign-synthesis.md`,
> `docs/design/skill-agent-design.md`, `docs/design/explore-agent-design.md`,
> `docs/design/exec-agent-design.md`.

---

## 1. mcp-agent's surface is all the *good* kind of mechanism

mcp-agent is a thin instruction + three polymorphic commands. Look at what they
do:

- **`mcp$server :op <…>`** — list / info / config / capabilities / resources /
  prompts / health. All **read/inspect** (discovery + an RPC ping). Pure
  mechanism.
- **`mcp$tools :op <…>`** — list (cached, no RPC) / **call** (invoke native MCP
  tools) / read-resource / get-prompt. Discovery + an **invocation proxy**.
- **`mcp$lifecycle :op <…>`** — start / stop / restart. **Connection
  management** (process/RPC).

None of these *author* an artifact. There is no dossier, no frontmatter chain, no
`mcp$*-write` helper family — so there was nothing for the lightweight series to
retire. Even `mcp$tools :op :call` (the one place the model builds structure) is
just an **invocation**: a `[{:server-name :tool-name :tool-args}]` vector where
`:tool-args` is dictated by the *native tool's input-schema* (the model reads it
via `mcp$tools :op :list`, then fills the args). That's calling a function with
its arguments — the same as any tool call — not constructing a brittle artifact.

So, exactly as with skill-agent's read seams + CLI wrappers: mcp-agent's whole
surface is the **good** kind of mechanism (discover, inspect, invoke, manage a
connection), which the series **keeps**. The polymorphic `:op` design is itself a
nice compression (one command, many ops), not brittleness. The lightweight half
of this design is therefore a confirmation, not a change.

## 2. The shipped shape

Two parts:

1. **mcp-agent stays as-is** — already all-mechanism; the three commands are kept
   verbatim. Nothing retired.
2. **MCP *use* is a base substrate.** A base system-context protocol — sibling to
   the skill substrate and Project Memory — teaches every coact/react-derived
   agent to *discover an MCP server, inspect a native tool's schema, and invoke
   it inline* — with **side-effect safety provided by the existing tool-permission
   mechanism (fail-closed), not by the model classifying read vs. write** (§5.4).
   mcp-agent remains the **management** owner (stop/restart, troubleshooting,
   complex multi-server flows).

One line: **using MCP is discover → inspect → invoke, which every agent can do —
while the platform's permission layer keeps external side effects deliberate.**

## 3. Design principles

1. **Keep the mechanism.** `mcp$server` / `mcp$tools` / `mcp$lifecycle` are
   deterministic RPC/discovery/invocation — kept. The model can't reach an
   external MCP server by emitting text; these commands are the only path.
2. **Use is a base substrate; management is the specialist.** Any agent can
   discover + invoke MCP (substrate); the heavier management (complex multi-server
   workflows, `:stop`/`:restart`, troubleshooting) stays mcp-agent's. The
   two-kinds split, applied to MCP.
3. **Side effects are gated by the existing tool-permission mechanism — not by
   the model.** Unlike skills (following a SKILL.md has no external effect), an MCP
   tool call can create issues, post messages, send mail. The model does **not**
   pre-classify read vs. write; instead every side-effecting MCP call (proxy or
   `mcp$<server>$<tool>` binding) passes through the fail-closed permission gate
   (§5.4). The same confirmation surface that gates `write-file`/`bash` gates MCP.
4. **Lifecycle.** `:start` (to connect) is fine in the substrate;
   `:stop`/`:restart` and troubleshooting route to mcp-agent.
5. **Use is LLM-inherent.** Discover a server, read a native input-schema, fill
   the args, invoke — that's a tool call the model already does well; the
   substrate is guidance + the bound commands, nothing new.
6. **Degrade gracefully.** No server / not connected → the substrate says so and
   offers to start it or route to mcp-agent; a disconnected call doesn't retry
   blindly.

## 4. What stayed, what went

| Concern | Today | Shipped |
| --- | --- | --- |
| Discover servers / inspect | `mcp$server :op :list/info/config/capabilities/resources/prompts/health` | **Kept** — read seams (and the substrate's discovery). |
| Discover native tools | `mcp$tools :op :list` | **Kept** — cached read seam. |
| Invoke a native tool | `mcp$tools :op :call` *or* the `mcp$<server>$<tool>` binding | **Both kept** — any agent invokes; the permission gate gates side-effecting calls (§5.4). No model-side read/write split. |
| Read resource / render prompt | `mcp$tools :op :read-resource` / `:get-prompt` | **Kept** — read seams. |
| Connection lifecycle | `mcp$lifecycle :op :start/:stop/:restart` | **Kept** — `:start` (connect) in substrate; `:stop`/`:restart` → mcp-agent. |

Net: **nothing retired.** There's no authoring helper chain here — the whole
surface is mechanism the series keeps. The change was purely *additive*: a base
substrate + a roster add (§5) + the permission gate (§5.4), and mcp-agent stays
the management specialist.

## 5. The MCP substrate — any agent can use MCP

Using MCP has the same find → inspect → invoke shape as a skill, plus a connection
step. It ships as a base system-context protocol, modeled on
`project-memory-protocol` and the skill substrate. **Crucially, the model does not
classify read vs. write** — the platform's permission gate gates side-effecting
calls (§5.4).

### 5.1 The base section text

This is the shipped `agent-roster/mcp-substrate-protocol` prose, inserted as an
`:mcp-substrate` section into both base system contexts:

```text
## Using MCP servers (mcp substrate)
Configured MCP servers expose external tools / resources / prompts (Linear, Slack,
Jira, GitHub, Notion, …). When a task needs external data or an external action,
use them:

1. DISCOVER — (mcp$server :op :list). If the relevant server is :connected false,
   (mcp$lifecycle :op :start :server-name "<s>") to connect it. (:stop / :restart
   and troubleshooting a flaky server → route to mcp-agent.)
2. INSPECT — (mcp$tools :op :list :server-name "<s>") to learn the native
   tool-name + input-schema. NEVER invent server/tool names or arg keys.
3. INVOKE — call the native tool, EITHER via the proxy
   (mcp$tools :op :call :tool-calls [{:server-name "<s>" :tool-name "<native>"
    :tool-args {…per schema…}}]) OR by its registered binding mcp$<server>$<tool>.
   Resources via :read-resource, prompts via :get-prompt. Cite mcp:<server>:<tool>.

RULES:
- INSPECT before INVOKE — never guess a tool's args; read the input-schema.
- A call that changes EXTERNAL state (create / update / post / send / delete) is a
  deliberate side effect: surface what you're about to do, and do NOT assume a
  prior approval carries to a new external call.
- USE covers discover / inspect / invoke / :start. Heavier management
  (:stop/:restart, troubleshooting, complex multi-server flows) → mcp-agent.
```

### 5.2 Two halves — guidance + a roster add

Like the skill substrate (and unlike todo/exec/edit), the MCP commands were **not
in `default-agent-roster`** before — explore-agent and exec-agent bound
`mcp-cmds/all-mcp-commands` *explicitly*, the tell. So the MCP substrate shipped
both halves:

- **Guidance** — the §5.1 section (`mcp-substrate-protocol`), inserted as
  `:mcp-substrate` into `coact-system-context` + `react-system-context`, beside
  the skill substrate and Project Memory.
- **Tools** — the **MCP command family** (`mcp$server`, `mcp$tools`,
  `mcp$lifecycle`) added to `default-agent-roster` (`agent_roster.clj`) so every
  derived agent can discover + invoke. (The per-server `mcp$<server>$<tool>`
  bindings are already globally registered, so they're reachable regardless; the
  command family adds first-class discovery.) **No read/write roster split, and
  none is needed** — safety is the permission gate (§5.4), not which tools are
  bound.

### 5.3 Two kinds: USE (substrate) vs. MANAGE (mcp-agent)

| | (A) Use MCP | (B) Manage MCP |
| --- | --- | --- |
| **What** | discover → inspect schema → invoke any native tool; connect (`:start`) | `:stop`/`:restart`; troubleshoot a flaky server; complex multi-server workflows |
| **Who** | any agent (the substrate) | mcp-agent (the specialist) |
| **Side effects** | possible — gated uniformly by the permission gate (§5.4), not by the model | same gate; mcp-agent adds workflow/error-recovery logic |
| **Ceremony** | none beyond inspect-then-invoke | server health/restart logic, error recovery |

Same split as skills (USE substrate / MANAGE specialist). The difference from
skills — MCP calls can have **side effects** — is handled not by making the model
draw a read/write line, but by the fail-closed permission gate (§5.4), which sits
below *both* the substrate and mcp-agent.

### 5.4 The write boundary IS a fail-closed permission gate (no MCP-specific classifier)

> **As-built correction.** This section originally proposed routing MCP calls
> through `call-tool`'s `check-permission` / `:tool-use-control`, fail-closed,
> with each `:mcp$<server>$<tool>` stamped `:approval-required` at registration.
> **That named mechanism does not gate in this codebase** — `:tool-use-control`
> is visibility-only (`tool-visible?`), and the `check-permission` regex path is
> dormant and does not gate `write-file`/`bash` (those use the session-injected
> `permission-fn`). Read this section for *intent*; the shipped mechanism is the
> decision hook described below.

An earlier draft proposed a bespoke read/write classifier (annotations → config →
verb-heuristic) enforced by a hook on `mcp$tools :op :call`. **That was wrong —
over-engineered and leaky** — for one concrete reason: a native MCP tool is
registered **twice**. An agent can invoke it through the proxy (`mcp$tools :op
:call`) *or* directly by its **first-class registry binding
`mcp$<server>$<tool>`** (`integration.clj` registers each native tool as
`:mcp$<server>$<tool>` in `!tool-defs`). A hook scoped to the proxy command
silently misses the direct binding. And building a reliable read/write classifier
is impossible anyway — the server defines the semantics and may not declare them.

The shipped design is **simpler and covers both paths in one place**: a single
`:agent.tool-use/pre` decision hook in `mcp/permission.clj`, installed at
namespace load (`install-mcp-permission-gate!`) and fired inside
`dispatch-with-hooks`, which wraps **both** the native `mcp$<server>$<tool>`
binding **and** the `mcp$tools :op :call` proxy (incl. batches). So:

> **Don't classify MCP calls at all. Gate them at one runtime hook, fail-closed.**
> MCP tools are external side-effecting by nature, so the default posture is
> approval-required (a permission prompt) for any MCP tool that isn't explicitly
> marked safe. The gate bridges to the **same `permission-fn` UI** that gates
> `write-file`/`bash` (extended with a `:type :mcp-tool` branch), and it covers
> **both** call paths uniformly because both pass through `dispatch-with-hooks`.

How the hook decides (`mcp-permission-gate`):

- **Target extraction.** `native-target` splits `mcp$<server>$<tool>` into
  `{:server :tool}` (server/tool names can't contain `$`, so a valid id is exactly
  3 parts). `proxy-call?` + `proxy-targets` pulls the `{:server-name :tool-name}`
  list out of a `mcp$tools :op :call`'s `:tool-calls`. An un-decoded batch (raw
  JSON string) is treated as `:unknown` → fail-closed.
- **Needs approval?** `(not (or read-only? allowlisted?))`. `read-only?` reads the
  tool's `annotations.readOnlyHint` off the registry `:meta` (or the connect-time
  cache for a proxy-only tool); absent ⇒ false ⇒ approval needed.
  `allowlisted?` matches `server/tool` against the `:mcp-allow-tools` globs.
- **Mode (`[:permissions :mode]` / `:permission-mode`).** `:auto-approve` → allow;
  `:deny-by-default` → refuse without prompting; `:ask-each-time` (default) →
  prompt via `permission-fn`. With no interactive channel (headless / sub-agent
  with no `permission-fn`) the call is **refused** — fail-closed, mirroring
  `write-file`'s headless behavior.
- **Refusal verdict.** Returns `{:result :replace :replacement {:error …}}`, so the
  model sees a clear denial and the turn continues; the MCP call never executes.

Consequences — note how much *disappears*:

- **No read/write classifier**, no verb-heuristic enforcement logic.
- **No differentiation in the substrate prose** (the model doesn't pre-judge read
  vs. write — §5.1) **and none in mcp-agent** either. Both just invoke; the gate
  decides.
- **The leak is closed by construction** — gating inside `dispatch-with-hooks`
  catches the direct `mcp$<server>$<tool>` binding that a proxy-scoped hook would
  have missed.

Where registration helps: `register-mcp-tools-for-server!` stamps each tool's
`:mcp-annotations` onto the registry `:meta` (NOT a `:tool-use-control` policy) so
the gate can read `:readOnlyHint`. The `readOnlyHint` (and the `:mcp-allow-tools`
config glob) become a *convenience* that **auto-allows** a known read-only tool —
purely to cut prompt friction, never as the safety boundary. So classification, if
used at all, only *relaxes* the gate; it never *is* the gate.

This is the same answer the series gives for **bash** (also un-classifiable, also
side-effecting): the safety isn't a bespoke per-surface analyzer, it's the one
permission seam that fails closed. MCP calls join `write-file` / `bash` / the rest
under the same `permission-fn` confirmation.

### 5.5 Why high-value

MCP servers are the fleet's window onto **external systems** — the user's own
plugin set (Slack, Linear, Jira, GitHub, Notion, Datadog, PagerDuty, …) is exactly
this. Before, only explore-agent (read-mostly), exec-agent (`:via :mcp` items),
and mcp-agent touched MCP; everyone else routed. Putting **read-side MCP** in the
base lets plan-agent pull a Linear issue, eval-agent check a CI status,
research-agent read a Notion doc — without a hop — while the write/lifecycle
boundary keeps external side effects deliberate. It generalizes explore-agent's
*proven* read-mostly MCP discipline to the whole fleet.

## 6. Where MCP sits in the substrate theory

The tool/meta doc framed the rule: *install a substrate when "use" is a procedure
the model performs; rely on the registry when "use" is a call the runtime
performs.* MCP refines it with a third position:

- **skill** — use = read+follow a procedure; not a local call → **substrate**.
- **user tool / agent** — use = a single registry call (`user$tool$<name>`),
  auto-registered → **no substrate** (ambient).
- **MCP** — in between. MCP tools *are* registered as first-class ids
  (`mcp$<server>$<tool>`) — so on that axis they're like user-tools — but using one
  still requires **connect → inspect-schema → invoke**, which is procedure-shaped
  like skills. So MCP gets a substrate (for the discover→invoke procedure), and its
  **side effects** are handled *not* by substrate-specific discipline but by the
  same fail-closed permission gate (§5.4).

So the rule sharpens to: **a substrate is worth it when "use" is a procedure that
should be ambient; side-effect safety is the existing permission layer, not a
per-substrate classifier.** Skills (procedure, no side effect) and MCP (procedure,
side effects gated by the permission layer) qualify; user-tools/agents (a bare
registry call, no procedure) don't.

## 7. Instruction & tool-context

**mcp-agent** is essentially unchanged — it remains the management specialist:
discovery, invocation, lifecycle, troubleshooting, complex multi-server flows. Its
in-prompt "confirm before write/modify" instruction stays (a "surface what you're
about to do" note is fine even though the permission gate now backstops it
uniformly, §5.4); the connect-first discipline stays. Its roster is the
bootstrap + invocation tools, the MCP command family, and task commands —
de-duped (`mcp_agent.clj`).

**Base agents** gained the §5.1 MCP-substrate section + the MCP command-family
roster add. **explore-agent** and **exec-agent** keep their explicit
`all-mcp-commands` binding — now a harmless no-op since the family also rides the
base roster (the `distinct` de-dupes it); exec-agent's `:via :mcp` is part of its
execution contract and stays.

## 8. Code touchpoints

- **`mcp/permission.clj`** (new): the fail-closed `:agent.tool-use/pre` gate.
  Classifies targets off `:mcp-annotations` (`readOnlyHint`) + `:mcp-allow-tools`
  globs, honors `[:permissions :mode]`, bridges to `permission-fn` with a
  `:type :mcp-tool` request, returns a `:replace` refusal for unapproved
  side-effecting calls. Covers both the native binding and the proxy. Self-installs
  at load; `mcp/commands.clj` requires it for the side-effect.
- **`mcp/integration.clj`**: `register-mcp-tools-for-server!` stamps each
  `:mcp$<server>$<tool>` registry entry's `:meta` with `:mcp-annotations` so the
  gate can read `:readOnlyHint`. (No `:tool-use-control` stamp — that path doesn't
  gate; see §5.4.)
- **`mcp/commands.clj`**: no command retired; all three polymorphic commands stay.
  No write-classifier helper. It requires `mcp.permission` for its install
  side-effect.
- **Base roster** (`agent_roster.clj`): `mcp-cmds/all-mcp-commands` is in
  `default-agent-roster` so every derived agent inherits MCP discovery +
  invocation. Safety is the permission gate, not the roster.
- **Base system-context** (`coact_agent.clj`, `react_agent.clj`): the shared
  `agent-roster/mcp-substrate-protocol` string (§5.1) is inserted as an
  `:mcp-substrate` section, beside `:skill-substrate` and `:project-memory`.

## 9. Migration — Complete

- The MCP command family on the base roster + the substrate section landed —
  purely additive; no behavior removed.
- The fail-closed permission gate at runtime (§5.4) landed — the safety change; it
  gates existing MCP usage too (mcp-agent included), via the same `permission-fn`
  that gates `write-file`/`bash`.
- No data migration. No model-side classification to roll out.
- explore-agent / exec-agent's pre-existing explicit `all-mcp-commands` bindings
  were left in place (harmless after the base-roster add).

## 10. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| A base agent triggers an external **write** side effect inline. | Every side-effecting MCP call routes through the `:agent.tool-use/pre` gate, fail-closed (§5.4, §8). The existing permission UI prompts — uniformly for the proxy *and* the `mcp$<server>$<tool>` binding. No model classification involved. |
| Permission prompts for harmless reads become noise. | `annotations.readOnlyHint` tools and `:mcp-allow-tools`-matched tools auto-allow (§5.4) — a convenience layered on the same gate; absent that, conservative prompting is the safe trade. |
| Every agent now probes MCP and adds latency / connection churn. | The substrate says discover *when a task needs external data*, not every turn; `mcp$tools :op :list` is cached (no RPC); `:start` only when connecting to a needed server. |
| An agent invents server/tool/arg names. | The substrate mandates inspect-before-invoke (`mcp$server :op :list` → `mcp$tools :op :list` → read input-schema). |
| Lifecycle thrash (`:stop`/`:restart` from many agents). | `:stop`/`:restart` route to mcp-agent; only `:start` (connect) is in the substrate. |

## 11. Verification

| Check | What it verifies |
| --- | --- |
| **Substrate discovery + invoke** | A non-MCP agent (e.g. plan-agent) on a task needing a Linear issue runs `mcp$server :op :list` → `mcp$tools :op :list` → invokes `get_issue` (proxy or `mcp$linear$get_issue`), inline, no route to mcp-agent; cites `mcp:linear:get_issue`. |
| **Permission gate (both paths)** | Invoking a write-flagged MCP tool triggers the standard permission prompt whether called via `mcp$tools :op :call` *or* the direct `mcp$<server>$<tool>` binding; denial blocks the call (`:replace` `{:error …}`). No model classification consulted. |
| **Headless fail-closed** | An MCP write call with no `permission-fn` (headless / sub-agent) is refused, not silently run. |
| **Mode honored** | `:auto-approve` runs without prompt; `:deny-by-default` refuses without prompt. |
| **Read auto-allow** | A `readOnlyHint`-true tool, or one matching a `:mcp-allow-tools` glob, invokes without a prompt. |
| **Roster inherited** | `mcp$server/tools/lifecycle` resolve on any coact/react-derived agent after the roster add. |
| **Uniform with mcp-agent** | mcp-agent's MCP calls hit the same permission gate; no separate write-confirm enforcement in its prompt. |

## 12. Open questions

1. **MCP command family on `default-agent-roster` vs. base coact/react rosters
   only?** The former reaches user-defined agents too. Shipped lean on
   `default-agent-roster` (consistent with the skill-substrate decision).
2. **Default-allow read-only-hinted tools, or prompt for everything until config
   says otherwise?** Auto-allowing `readOnlyHint true` tools cuts friction but
   trusts a server-supplied hint; a stricter posture prompts until a per-tool
   config allowlist downgrades it. Shipped: allow readOnly-hinted by default,
   `:mcp-allow-tools` override available — the *gate* (fail-closed) is unchanged
   either way.
3. **Combine the MCP + skill substrates** (and Project Memory) into one "Consult
   external capabilities before reinventing" base section, or keep separate? They
   share the discover→use shape. Align with the
   [synthesis](./agent-lightweight-redesign-synthesis.md)'s combined-section
   decision. (Side-effect safety isn't a factor here — it lives in the permission
   gate, not the substrate text.)
4. **Does `:start` belong in the substrate at all?** Connect-to-read is benign and
   explore-agent does it, but it *is* a lifecycle op. Shipped: allow `:start` (it's
   the difference between "can read" and "must route just to connect"); all other
   lifecycle → mcp-agent.

---

## Appendix — before/after

**Before — only explore-agent / exec-agent / mcp-agent can touch MCP; everyone
else routes:**

```clojure
;; plan-agent needs a Linear issue → it had to route to mcp-agent / explore-agent
(mcp-agent {:question "fetch Linear issue ENG-1234 so I can reference it in the plan"})
```

**After — any agent invokes MCP inline via the base substrate; the permission gate
gates side effects (same for all agents):**

```clojure
;; USE (any base agent) — discover → inspect → invoke, inline:
(mcp$server :op :list)                                  ; linear :connected true
(mcp$tools  :op :list :server-name "linear")            ; native names + schemas
;; invoke via the proxy OR the registered binding — both hit the same permission gate:
(mcp$tools  :op :call :tool-calls
            [{:server-name "linear" :tool-name "get_issue" :tool-args {:id "ENG-1234"}}])
;; or:  (mcp$linear$get_issue {:id "ENG-1234"})
;;   get_issue is readOnly → auto-allowed → runs; cite mcp:linear:get_issue

;; A WRITE — the model just calls it; the permission gate prompts:
(mcp$linear$create_issue {:title "…" :team "ENG"})      ; not readOnly → approval prompt
;;   the model does NOT pre-classify; approval (or denial) happens at the gate,
;;   identically whether called as the proxy or the mcp$linear$create_issue binding.
```

mcp-agent's surface was already pure mechanism (discover / invoke / manage a
connection) — the series keeps all of it. The redesign made MCP **invocation**
ambient to every agent via the base substrate, and routed **side-effect safety**
to one fail-closed `:agent.tool-use/pre` gate that bridges to the same
`permission-fn` UI as `write-file`/`bash` and covers the proxy and the
`mcp$<server>$<tool>` binding alike — so there's no MCP-specific read/write
differentiation in the substrate or in mcp-agent.
