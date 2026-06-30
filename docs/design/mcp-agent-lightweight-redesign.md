# MCP-Agent — Lightweight Confirmation + MCP Substrate (revision 3)

> **Status:** Proposal. Applies the [series](./agent-lightweight-redesign-synthesis.md)
> argument to the **MCP usage/lifecycle specialist**, in the same shape as
> [skill-agent](./skill-agent-lightweight-redesign.md). Like skill-agent,
> mcp-agent is already among the least over-tooled agents — a thin wrapper over
> three polymorphic commands (`mcp$server` / `mcp$tools` / `mcp$lifecycle`) that
> are *all* deterministic mechanism (discovery, RPC invocation, connection
> lifecycle). It authors nothing. So the lightweight half is essentially a
> **confirmation**: keep the mechanism. The substantive contribution is the
> user's ask:
>
> **An MCP substrate in the base agents, so *any* agent can use MCP.** Using an
> MCP server = discover it, read a native tool's input-schema, and invoke it.
> That should be ambient to the whole fleet — not gated behind a route to
> mcp-agent. The critical twist over skills: **MCP has side effects**, so the
> substrate is **read-only by default** with a hard write/lifecycle boundary —
> exactly the read-mostly discipline explore-agent already encodes. mcp-agent
> stays the **management** owner (write-side calls, lifecycle, troubleshooting).
>
> **Scope:** `mcp_agent.clj`, `mcp/commands.clj`, plus a shared MCP-substrate
> section + a command-family roster add in the base agents.

> **As-built correction (2026-06-30) — the side-effect gate mechanism.** Both the
> substrate and the gate have shipped. The substrate landed as proposed. The
> **gate mechanism described throughout this doc (§5.4, §8, and the tables) is
> wrong** and must not be followed literally: this doc says MCP calls "flow
> through `call-tool`'s `check-permission` / `:tool-use-control`, fail-closed,
> default `:approval-required`." In this codebase **`:tool-use-control` is
> visibility-only** (`tool-visible?` — hidden/allow/deny by agent-id, no approval
> concept), and **`check-permission` / `permission-config` is a dormant,
> hardcoded-empty regex path** that does *not* gate `write-file`/`bash` (those use
> the session-injected **`permission-fn`**) and enforces only `:denied`. Stamping
> `:tool-use-control :approval-required` would have been a silent no-op.
>
> **What shipped instead** (`components/agent/src/ai/brainyard/agent/mcp/permission.clj`):
> a **`:agent.tool-use/pre` decision hook** (like eval-agent's `eval-bash-guard`)
> that fires inside `dispatch-with-hooks`, so it covers **both** the native
> `mcp$<server>$<tool>` binding **and** the `mcp$tools :op :call` proxy (incl.
> batches) in one place — no per-tool stamp, no proxy bypass. On an unapproved
> side-effecting call it returns a `:replace` verdict (`{:error …}`); the call
> never runs. It bridges to the **same `permission-fn` UI that actually gates
> `write-file`/`bash`** (extended with a `:type :mcp-tool` branch), honors
> `[:permissions :mode]` (`:auto-approve`/`:deny-by-default`/`:ask-each-time`,
> fail-closed when headless), and downgrades `annotations.readOnlyHint` tools +
> tools matching the new `:mcp-allow-tools` glob allowlist. The doc's *intent*
> (uniform across proxy+binding, fail-closed, no model-side read/write classifier,
> same seam as `write-file`/`bash`) is exactly what shipped — only the named
> internal mechanism was wrong. Read §5.4 / §8 below through this correction.

---

## 1. Why the current path is (barely) error-prone

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
`mcp$*-write` helper family — so there is nothing for the series to retire. Even
`mcp$tools :op :call` (the one place the model builds structure) is just an
**invocation**: a `[{:server-name :tool-name :tool-args}]` vector where
`:tool-args` is dictated by the *native tool's input-schema* (the model reads it
via `mcp$tools :op :list`, then fills the args). That's calling a function with
its arguments — the same as any tool call — not constructing a brittle artifact.

So, exactly as with skill-agent's read seams + CLI wrappers: mcp-agent's whole
surface is the **good** kind of mechanism (discover, inspect, invoke, manage a
connection), which the series **keeps**. The polymorphic `:op` design is itself a
nice compression (one command, many ops), not brittleness. The lightweight half
of this doc is therefore a confirmation, not a change.

## 2. Thesis

Two parts:

1. **mcp-agent stays as-is** — already all-mechanism; the three commands are kept
   verbatim. Nothing to retire.
2. **MCP *use* becomes a base substrate.** A base system-context protocol —
   sibling to the skill substrate and Project Memory — teaches every
   coact/react-derived agent to *discover an MCP server, inspect a native tool's
   schema, and invoke it inline* — with **side-effect safety provided by the
   existing tool-permission mechanism (fail-closed), not by the model
   classifying read vs. write** (§5.4). mcp-agent remains the **management** owner
   (stop/restart, troubleshooting, complex multi-server flows).

One line: **using MCP is discover → inspect → invoke, which every agent should be
able to do — while the platform's permission layer keeps external side effects
deliberate.**

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
   pre-classify read vs. write; instead every MCP call (proxy or
   `mcp$<server>$<tool>` binding) flows through `call-tool`'s permission check,
   **fail-closed** — MCP tools default to `:approval-required` (§5.4). The same
   confirmation surface that gates `write-file`/`bash` gates MCP.
4. **Lifecycle.** `:start` (to connect) is fine in the substrate;
   `:stop`/`:restart` and troubleshooting route to mcp-agent.
5. **Use is LLM-inherent.** Discover a server, read a native input-schema, fill
   the args, invoke — that's a tool call the model already does well; the
   substrate is guidance + the bound commands, nothing new.
6. **Degrade gracefully.** No server / not connected → the substrate says so and
   offers to start it or route to mcp-agent; a disconnected call doesn't retry
   blindly.

## 4. What stays, what goes

| Concern | Today | Redesign |
| --- | --- | --- |
| Discover servers / inspect | `mcp$server :op :list/info/config/capabilities/resources/prompts/health` | **Keep** — read seams (and the substrate's discovery). |
| Discover native tools | `mcp$tools :op :list` | **Keep** — cached read seam. |
| Invoke a native tool | `mcp$tools :op :call` *or* the `mcp$<server>$<tool>` binding | **Keep both** — any agent invokes; the tool-permission mechanism gates side-effecting calls (§5.4). No model-side read/write split. |
| Read resource / render prompt | `mcp$tools :op :read-resource` / `:get-prompt` | **Keep** — read seams. |
| Connection lifecycle | `mcp$lifecycle :op :start/:stop/:restart` | **Keep** — `:start` (connect) in substrate; `:stop`/`:restart` → mcp-agent. |

Net: **nothing retires.** There's no authoring helper chain here — the whole
surface is mechanism the series keeps. The change is purely *additive*: a base
substrate + a roster add (§5), and mcp-agent stays the management specialist.

## 5. The MCP substrate — any agent can use MCP (the user's ask)

Using MCP has the same find → inspect → invoke shape as a skill, plus a connection
step. Make it a base system-context protocol, modeled on
`coact-project-memory-protocol` and the skill substrate. **Crucially, the model
does not classify read vs. write** — the platform's existing tool-permission
mechanism gates side-effecting calls (§5.4).

### 5.1 The base section text

```text
## Using MCP servers (mcp substrate)
Configured MCP servers expose external tools / resources / prompts (Linear, Slack,
Jira, GitHub, Notion, …). When a task needs external data or an external action,
use them:

1. DISCOVER — (mcp$server :op :list). If the relevant server is :connected false,
   (mcp$lifecycle :op :start :server-name "<s>") to connect it. (:stop / :restart
   and troubleshooting a flaky server → route to mcp-agent.)
2. INSPECT — (mcp$tools :op :list :server-name "<s>") to learn the native
   tool-name + input-schema. Never invent server/tool names or arg keys.
3. INVOKE — call the native tool, EITHER via the proxy
   (mcp$tools :op :call :tool-calls [{:server-name "<s>" :tool-name "<native>"
    :tool-args {…per schema…}}]) OR by its registered binding mcp$<server>$<tool>.
   Resources via :read-resource, prompts via :get-prompt. Cite mcp:<server>:<tool>.
   You do NOT pre-judge read vs. write: a call that changes external state will
   trigger the normal tool-permission prompt — approve only what the user wants.
   Don't assume a prior approval carries to a new external call.
```

### 5.2 Two halves — guidance + a roster add

Like the skill substrate (and unlike todo/exec/edit), the MCP commands are **not
in `default-agent-roster`** today — explore-agent and exec-agent bind
`mcp-cmds/all-mcp-commands` *explicitly*, the tell. So the MCP substrate ships
both:

- **Guidance** — the §5.1 section, inserted into `coact-system-context` +
  `react-system-context` (shared string), beside the skill substrate and Project
  Memory.
- **Tools** — add the **MCP command family** (`mcp$server`, `mcp$tools`,
  `mcp$lifecycle`) to `default-agent-roster` so every derived agent can discover +
  invoke. (The per-server `mcp$<server>$<tool>` bindings are already globally
  registered, so they're reachable regardless; the command family adds first-class
  discovery.) **No read/write roster split, and none is needed** — safety is the
  tool-permission layer (§5.4), not which tools are bound.

### 5.3 Two kinds: USE (substrate) vs. MANAGE (mcp-agent)

| | (A) Use MCP | (B) Manage MCP |
| --- | --- | --- |
| **What** | discover → inspect schema → invoke any native tool; connect (`:start`) | `:stop`/`:restart`; troubleshoot a flaky server; complex multi-server workflows |
| **Who** | any agent (the substrate) | mcp-agent (the specialist) |
| **Side effects** | possible — gated uniformly by the tool-permission mechanism (§5.4), not by the model | same gate; mcp-agent adds workflow/error-recovery logic |
| **Ceremony** | none beyond inspect-then-invoke | server health/restart logic, error recovery |

Same split as skills (USE substrate / MANAGE specialist). The difference from
skills — MCP calls can have **side effects** — is handled not by making the model
draw a read/write line, but by the existing fail-closed tool-permission mechanism
(§5.4), which sits below *both* the substrate and mcp-agent.

### 5.4 The write boundary IS the existing tool-permission mechanism (no MCP-specific differentiation)

An earlier draft proposed a bespoke read/write classifier (annotations → config →
verb-heuristic) enforced by a `:agent.tool-use/pre` hook on `mcp$tools :op :call`.
**That was wrong — over-engineered and leaky** — for one concrete reason: a native
MCP tool is registered **twice**. An agent can invoke it through the proxy
(`mcp$tools :op :call`) *or* directly by its **first-class registry binding
`mcp$<server>$<tool>`** (integration.clj registers each native tool as
`:mcp$<server>$<tool>` in `!tool-defs`). A hook scoped to the proxy command
silently misses the direct binding. And building a reliable read/write classifier
is impossible anyway — the server defines the semantics and may not declare them.

The correct design is **simpler and already exists**: every tool call — the proxy
*and* the `mcp$<server>$<tool>` binding — flows through `call-tool`, which runs
`check-permission` / `:tool-use-control` (tool.clj: "dispatcher with
hooks/permissions/visibility/agent guards"). So:

> **Don't classify MCP calls at all. Route them through the standard
> tool-permission mechanism, fail-closed.** MCP tools are external side-effecting
> by nature, so the default posture is `:approval-required` (a permission prompt)
> for any MCP tool that isn't explicitly marked safe. The existing permission UI
> (the same one that gates `write-file`, `bash`, …) is the confirmation surface,
> and it covers **both** call paths uniformly because both pass through
> `call-tool`.

Consequences — note how much *disappears*:

- **No read/write classifier**, no `readOnlyHint`/verb-heuristic enforcement logic,
  no MCP-specific `:agent.tool-use/pre` hook.
- **No differentiation in the substrate prose** (the model doesn't pre-judge read
  vs. write — §5.1) **and none in mcp-agent** either. Both just invoke; the
  permission layer gates.
- **The leak is closed by construction** — gating at `call-tool` catches the
  direct `mcp$<server>$<tool>` binding that a proxy-scoped hook would have missed.

Where the registration sets the policy: classify at **registration time**, not
call time. When `register-mcp-tools-for-server!` registers `:mcp$<server>$<tool>`,
stamp its `:tool-use-control` (or add it to `permission-config :approval`) so the
existing mechanism prompts. **Fail-closed default: MCP tools are
`:approval-required`** unless downgraded. The `tools/list` `annotations.readOnlyHint`
(and a per-server/tool config allowlist) become a *convenience* that can
**auto-allow** a known read-only tool — purely to cut prompt friction, never as
the safety boundary. So classification, if used at all, only *relaxes* the gate;
it never *is* the gate.

This is the same answer the series gives for **bash** (also un-classifiable,
also side-effecting): the safety isn't a bespoke per-surface analyzer, it's the
one existing permission seam that fails closed. MCP calls join `write-file` /
`bash` / the rest under the same mechanism.

### 5.5 Why high-value

MCP servers are the fleet's window onto **external systems** — the user's own
plugin set (Slack, Linear, Jira, GitHub, Notion, Datadog, PagerDuty, …) is
exactly this. Today only explore-agent (read-mostly), exec-agent (`:via :mcp`
items), and mcp-agent touch MCP; everyone else routes. Putting **read-side MCP**
in the base lets plan-agent pull a Linear issue, eval-agent check a CI status,
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
  same fail-closed tool-permission mechanism every registered tool already passes
  through (§5.4).

So the rule sharpens to: **a substrate is worth it when "use" is a procedure that
should be ambient; side-effect safety is the existing tool-permission layer, not a
per-substrate classifier.** Skills (procedure, no side effect) and MCP (procedure,
side effects gated by the permission layer) qualify; user-tools/agents (a bare
registry call, no procedure) don't.

## 7. Instruction & tool-context (minimal change)

**mcp-agent** is essentially unchanged — it remains the management specialist:
discovery, invocation, lifecycle, troubleshooting, complex multi-server flows.
Its in-prompt write-confirm instruction can be *relaxed* (the permission layer now
gates writes uniformly, §5.4), but keeping a "surface what you're about to do"
note is fine; the connect-first discipline stays.

**Base agents** gain the §5.1 MCP-substrate section + the MCP command-family
roster add. **explore-agent** can drop its now-redundant explicit
`all-mcp-commands` binding (it inherits from the base) — a small cleanup; exec-agent
keeps MCP routing as today (its `:via :mcp` is part of its execution contract).

## 8. `mcp/commands.clj`, `mcp/integration.clj` + base-agent changes

- **`mcp/integration.clj`**: in `register-mcp-tools-for-server!`, stamp each
  `:mcp$<server>$<tool>` with a **fail-closed `:tool-use-control`**
  (`:approval-required`) so the existing permission mechanism prompts on every MCP
  call — both the direct binding and the `mcp$tools :op :call` proxy route through
  `call-tool`. Optionally read the `tools/list` `annotations.readOnlyHint` (and a
  per-server/tool config allowlist) to **downgrade** known read-only tools to
  `:allowed` — a prompt-reduction convenience, never the safety boundary (§5.4).
- **`mcp/commands.clj`**: no command retires; all three polymorphic commands stay.
  No write-classifier helper, no MCP-specific hook.
- **Base roster**: add `mcp-cmds/all-mcp-commands` to `default-agent-roster` so
  every derived agent inherits MCP discovery + invocation. Safety is the
  permission layer, not the roster.
- **Base system-context**: define a shared `mcp-substrate-protocol` string (§5.1)
  and insert an `:mcp-substrate` section into `coact-system-context` +
  `react-system-context`, beside `:skill-substrate` and `:project-memory`.

## 9. Migration

- Add the MCP command family to the base roster + the substrate section — purely
  additive; no behavior removed, so it can land independently (with Phase 1's
  other base substrates).
- Add the fail-closed `:tool-use-control` stamp at MCP-tool registration (§8) —
  this is the safety change; it gates existing MCP usage too (mcp-agent included),
  via the mechanism that already gates `write-file`/`bash`.
- No data migration. No model-side classification to roll out.

## 10. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| A base agent triggers an external **write** side effect inline. | Every MCP call routes through `call-tool`'s permission check; MCP tools default to `:approval-required` (§5.4, §8). The existing permission UI prompts — uniformly for the proxy *and* the `mcp$<server>$<tool>` binding. No model classification involved. |
| Permission prompts for harmless reads become noise. | Downgrade known read-only tools to `:allowed` via `annotations.readOnlyHint` / a config allowlist (§8) — a convenience layered on the same mechanism; absent that, conservative prompting is the safe trade. |
| Every agent now probes MCP and adds latency / connection churn. | The substrate says discover *when a task needs external data*, not every turn; `mcp$tools :op :list` is cached (no RPC); `:start` only when connecting to a needed server. |
| An agent invents server/tool/arg names. | The substrate mandates inspect-before-invoke (`mcp$server :op :list` → `mcp$tools :op :list` → read input-schema). |
| Lifecycle thrash (`:stop`/`:restart` from many agents). | `:stop`/`:restart` route to mcp-agent; only `:start` (connect) is in the substrate. |

## 11. Verification

- **Substrate discovery + invoke** — a non-MCP agent (e.g. plan-agent) on a task
  needing a Linear issue runs `mcp$server :op :list` → `mcp$tools :op :list` →
  invokes `get_issue` (proxy or `mcp$linear$get_issue`), inline, no route to
  mcp-agent; cites `mcp:linear:get_issue`.
- **Permission gate (both paths)** — invoking a write-flagged MCP tool triggers the
  standard permission prompt whether called via `mcp$tools :op :call` *or* the
  direct `mcp$<server>$<tool>` binding; denial blocks the call. No model
  classification is consulted.
- **Read auto-allow (optional)** — a tool downgraded to `:allowed` (readOnly /
  config) invokes without a prompt.
- **Roster inherited** — `mcp$server/tools/lifecycle` resolve on any
  coact/react-derived agent after the roster add.
- **Uniform with mcp-agent** — mcp-agent's MCP calls hit the same permission gate;
  no separate write-confirm logic in its prompt.

## 12. Open questions

1. **MCP command family on `default-agent-roster` vs. base coact/react rosters
   only?** Former reaches user-defined agents too. Lean `default-agent-roster`
   (consistent with the skill-substrate decision).
2. **Default-allow read-only-hinted tools, or prompt for everything until config
   says otherwise?** Auto-allowing `readOnlyHint true` tools cuts friction but
   trusts a server-supplied hint; a stricter posture prompts until a per-tool
   config allowlist downgrades it. Lean: allow readOnly-hinted by default,
   per-server override available — the *gate* (fail-closed) is unchanged either way.
3. **Combine the MCP + skill substrates** (and Project Memory) into one "Consult
   external capabilities before reinventing" base section, or keep separate? They
   share the discover→use shape. Align with the synthesis's combined-section
   decision. (Side-effect safety isn't a factor here — it lives in the permission
   layer, not the substrate text.)
4. **Does `:start` belong in the substrate at all?** Connect-to-read is benign and
   explore-agent does it, but it *is* a lifecycle op. Alternative: substrate reads
   only already-connected servers; all lifecycle → mcp-agent. Lean: allow `:start`
   (it's the difference between "can read" and "must route just to connect").

---

## Appendix — before/after

**Before — only explore-agent / exec-agent / mcp-agent can touch MCP; everyone
else routes:**

```clojure
;; plan-agent needs a Linear issue → today it must route to mcp-agent / explore-agent
(mcp-agent {:question "fetch Linear issue ENG-1234 so I can reference it in the plan"})
```

**After — any agent invokes MCP inline via the base substrate; the permission
layer gates side effects (same for all agents):**

```clojure
;; USE (any base agent) — discover → inspect → invoke, inline:
(mcp$server :op :list)                                  ; linear :connected true
(mcp$tools  :op :list :server-name "linear")            ; native names + schemas
;; invoke via the proxy OR the registered binding — both hit call-tool's permission gate:
(mcp$tools  :op :call :tool-calls
            [{:server-name "linear" :tool-name "get_issue" :tool-args {:id "ENG-1234"}}])
;; or:  (mcp$linear$get_issue {:id "ENG-1234"})
;;   get_issue is readOnly → :allowed → runs; cite mcp:linear:get_issue

;; A WRITE — the model just calls it; the permission layer prompts:
(mcp$linear$create_issue {:title "…" :team "ENG"})      ; :approval-required → user prompt
;;   the model does NOT pre-classify; approval (or denial) happens at call-tool,
;;   identically whether called as the proxy or the mcp$linear$create_issue binding.
```

mcp-agent's surface was already pure mechanism (discover / invoke / manage a
connection) — the series keeps all of it. The redesign makes MCP **invocation**
ambient to every agent via the base substrate, and routes **side-effect safety**
to the one place it already lives: `call-tool`'s fail-closed permission mechanism,
which gates the proxy and the `mcp$<server>$<tool>` binding alike — so there's no
MCP-specific read/write differentiation in the substrate or in mcp-agent.
