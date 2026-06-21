# Tool System

A single registry, one dispatch function, four macro flavours. The tool
system is the contract between reasoning (ReAct / CoAct) and everything
else: local commands, skills, sub-agents, and MCP servers.

Primary files:

- `components/agent/src/ai/brainyard/agent/core/tool.clj` — registry + macros + dispatch.
- `components/agent/src/ai/brainyard/agent/common/{commands.clj, skills.clj, delegation_use.clj, tools.clj}` — shared registrations.
- `components/agent/src/ai/brainyard/agent/mcp/{integration.clj, client.clj, commands.clj}` — MCP plumbing.

---

## One registry to rule them all

`core/tool.clj` centralises tool infrastructure in a single atom, `!tool-defs`:

```clojure
{:id   :my-tool
 :type :tool | :command | :skill | :agent
 :fn   #'my.ns/my-fn
 :meta {:description "…"
        :input-schema  {…}    ;; Malli [:map …] schema
        :output-schema {…}
        :aliases  [...]                     ; optional
        :tool-use-control {…}               ; visibility/permission
        :bt-factory   …                     ; agent only
        :agent-tools  {…}                   ; agent only
        :instruction  "…"                   ; agent only
        ...}}
```

Every tool — whether a native Clojure fn, a skill backed by an external
CLI, an MCP-exposed remote, or a full sub-agent — lives in this same map
with the same shape. The LLM surface is uniform: `call-tool`,
`get-tool-defs`, `list-tools`, `get-tool-info`.

---

## Macro flavours

`deftool` is the primitive; the other three are thin wrappers that
preset `:type`:

| Macro | `:type` | Typical use |
|---|---|---|
| `deftool` | `:tool` | Primitive. Use when you want to set `:type` explicitly. |
| `defcommand` | `:command` | A callable function with inputs/outputs. ReAct-style tool call. |
| `defskill` | `:skill` | An external CLI or template-driven capability. |
| `defagent` | `:agent` | A full sub-agent (itself running a BT). Invocation = `ask-async`. |

Metadata (description, inputs, outputs, visibility, permissions) is
attached at registration and **re-injected into call arguments** at
invocation time. This lets a skill receive its prompt template or a
command receive its context block without the caller constructing them.

`deftool` renames the canonical metadata keys to `:_deftool$<key>` in
the per-call merge so the auto-injected meta does not collide with
caller args that happen to use the same names (`:id`, `:type`,
`:description`, `:input-schema`, `:output-schema`).

### Example

```clojure
(defcommand my-search
  "Full-text search across indexed docs."
  (fn [{:keys [q limit parent-agent agent-session]}]
    (my-impl q limit))
  :input-schema  [:map
                  [:q [:string {:desc "query"}]]
                  [:limit [:int {:desc "max results"}]]]
  :output-schema [:map
                  [:hits [:any {:desc "matches"}]]])
```

`:parent-agent` and `:agent-session` are auto-injected at the bind step
below, so sub-agent calls share agent-session state without the caller
threading it manually. `:agent-session` is `{:user-id :session-id}` —
the agent-session identity layer (distinct from any app/bridge-layer
session the TUI or web UI may track).

---

## Binding and invocation

`bind-tools` produces `[tools-vec tools-fn-map]`:

- `tools-vec` — the list of visible tools for this agent, passed to the
  LLM as part of the system prompt.
- `tools-fn-map` — a map from tool id to bound invocation fn, with
  `:parent-agent` and `:agent-session` already partially applied.

`tool-calls-action` (in `common/react_agent.clj`, reused by CoAct) is
the BT leaf that turns LLM-selected calls into real invocations:

```
1. Loop-guard / permission via :agent.tool-use/pre   (gated hook —
                                                      can block / replace /
                                                      modify-args; sentinel
                                                      bubbles through as a
                                                      synthesized answer)
2. Permission check                                  (:allow / :deny / :approval globs)
3. Normalise & coerce inputs                         (Malli schemas from :input-schema,
                                                      see §JSON ↔ Malli below)
4. Bind *current-agent*, *call-depth*, *call-chain*
5. Invoke bound fn
6. Resolve async refs                                (Clojure agents from
                                                      ask-async — wait ≤ 30 s;
                                                      on timeout hand off to
                                                      the task manager and
                                                      nudge the LLM to poll
                                                      via task$detail)
7. Truncate results                                  (structure-aware,
                                                      shared budget with
                                                      iteration fields)
8. Fire :agent.tool-use/post
```

CoAct code calls `(call-tool :my-tool {…})` directly from the sandbox; it
goes through the same `tool/call-tool` pipeline, minus the LLM-selection step.

---

## JSON ↔ Malli type conversion

LLM tool-call arguments arrive on the wire as JSON — strings, numbers,
booleans, arrays, objects. Tool schemas declare Malli types
(`:keyword`, `:int`, `:boolean`, `[:vector :keyword]`, `[:map [:foo :keyword]]`),
which are richer than JSON. The gap is bridged at step 3 of the dispatch
pipeline by `llm-args-transformer` in `core/tool.clj`.

The transformer composes a custom `:keyword` decoder with
`malli.transform/string-transformer` and `m/decode`s the args against the
declared input schema. Because Malli walks the schema, coercion fires at
any depth the schema describes — a `[:map [:foo :keyword] [:n :int]]`
input decodes `{:foo "x" :n "5"}` into `{:foo :x :n 5}`, and a
`[:vector :keyword]` decodes `["a" "b"]` into `[:a :b]`.

Coverage:

| Schema | Wire input | Decoded |
|---|---|---|
| `:keyword` | `"b"` or `":b"` | `:b` |
| `:int` | `"42"` or `42` | `42` |
| `:boolean` | `"false"` or `false` | `false` |
| `[:enum :a :b :c]` | `"b"` | `:b` |
| `[:enum "mcp" "registered"]` | `"mcp"` | `"mcp"` (no over-coercion) |
| `[:maybe :keyword]` | `"auto"` or `nil` | `:auto` / `nil` |
| `[:vector :keyword]` | `["a" "b"]` | `[:a :b]` |
| `[:map [:foo :keyword] [:n :int]]` | `{:foo "x" :n "5"}` | `{:foo :x :n 5}` |

The custom `:keyword` decoder runs first in the composition (first-wins in
Malli transformer composition) specifically to override
`mt/string-transformer`'s default, which treats a leading `:` as a namespace
separator (`":foo"` → `::foo`). LLMs hedge between `"a"` and `":a"` for a
keyword field — both should yield `:a`.

**Known limitation.** A bare `[:map {…}]` declaration (no nested field
schemas) is opaque to the transformer. Native string-keyed inner maps pass
through unchanged — `{"agent" {"max-iterations" 50}}` does not become
`{:agent {:max-iterations 50}}`. Tool authors who want nested coercion must
declare the inner fields (`[:map [:agent [:map [:max-iterations :int]]]]`).
The transformer never guesses at intent for a bare `[:map]` because some
tools legitimately accept string-keyed payloads.

Behavior is pinned by
`components/agent/test/ai/brainyard/agent/core/tool_test.clj`.

> The earlier ad-hoc `coerce-tool-args` / `schema->type` helpers remain
> exported from `core/tool.clj` for scalar coercion in
> `bases/agent-tui/.../commands.clj` and `core/config.clj`; the registry
> dispatch path no longer uses them.

---

## Visibility vs. permissions

Two orthogonal concerns, often confused:

- **Visibility** (`:tool-use-control` on the tool's metadata) controls
  which *agents can see* the tool. A low-privilege sub-agent can be
  scoped to a subset of the registry.
- **Permissions** (a global regex config plus per-session
  `:action-permissions` cache) control whether a *given invocation* is
  allowed. A visible tool might still prompt the user for approval.

The cache stores "always yes" / "always no" answers so the user is not
spammed on repeated calls with the same key.

---

## Sub-agents as tools

A `defagent` invocation is indistinguishable at the call site from a
`defcommand`. Under the hood:

- **Every sub-agent call creates a fresh instance.** Sub-agent dispatch
  auto-generates a unique instance-id in the `:<target-defagent-type>/<suffix>`
  format (e.g. `:react-agent/amber-fox-812`) and dispatches through
  `invoke-tool`. There is no reuse-by-type cache — two successive calls
  to `:react-agent` from the same parent produce two distinct instances.
- The new instance inherits the parent's `!session` (agent-session), so
  it shares `:user-id`, `:session-id`, messages, thinking, and
  `:agent-activity`.
- `ask-async` is called (unless the caller is itself a sub-agent, in
  which case a synchronous `ask` is used); the result is a Clojure
  agent ref the caller derefs.
- If the deref takes longer than 30 s, the ref is handed off to the
  [task manager](task.md) and the LLM is prompted to poll.

`core/protocol.clj` enforces two safeguards (see [agent.md](agent.md)):

- `*call-depth*` — capped by `:max-agent-call-depth` (default 3).
- `*call-chain*` — rejects calls where the target id already appears in
  the chain. Prevents cycles.

Messages persisted by sub-agents are tagged with `:agent-id`,
preserving attribution in transcript replays.

---

## MCP — Model Context Protocol

`mcp/integration.clj` and `mcp/client.clj` treat external MCP servers
as first-class tool sources:

- **Discovery** — `integration.clj` watches configured MCP servers
  (stdio or HTTP) and registers their exposed tools in `!tool-defs`
  with id `mcp$<server>$<tool>` and `:type :command`.
- **Dispatch** — an MCP tool call is just another `call-tool`
  invocation that happens to forward to `client.clj`, which speaks
  the JSON-RPC protocol.
- **Specialisations** — `common/aws_commands.clj` exposes AWS
  credential-management commands (`aws$list-profiles`, `aws$whoami`,
  `aws$get-profile`, `aws$set-profile`); `aws$set-profile` restarts
  any running aws-knowledge MCP server so the new credentials take
  effect.

From the agent's point of view, an MCP tool is identical to a native
Clojure fn. A ReAct loop can mix native tools and remote MCP tools in
the same turn without caring which is which.

### Configuration

MCP servers are declared in config (typically `.brainyard/mcp.edn`).
A TUI slash command (`/mcp`) lets users attach/detach servers live.

---

## Hooks integration

Two hook events bracket every tool call (see [agent.md](agent.md) §Hooks):

- **`:agent.tool-use/pre`** — *gated*. Handlers may return
  `{:result :block | :replace | :modify-args | :allow}`. The default
  loop guard (`common/loop_guard_hook.clj`) registers here to break
  pathological repeat-call patterns.
- **`:agent.tool-use/post`** — observer. After the call returns.

Plus the batch-level `:agent.tool-calls/pre` and `:agent.tool-calls/post`
that surround a whole iteration's tool dispatch.

---

## Background tasks as tools

The task manager registers a set of commands that any LLM can use to
schedule long work (see [task.md](task.md)):

- `task$run :job-type :tool` — run any registered tool asynchronously.
- `task$run :job-type :bash` — same pattern for shell commands.
- `task$list` / `task$detail` / `task$cancel` — inspection and control.
  `task$detail` accepts `:last-n N` for the captured output tail.
- `task$wait` / `task$wakeup` / `task$sweep` — block-wait, park-and-resume,
  and on-disk artifact GC (see [task.md](task.md)).

A LLM that hits the inline fast-eval deadline is nudged to switch to
`task$run` and poll via `task$detail` (add `:last-n N` for the tail).

---

## File map

| File | Purpose |
|---|---|
| `core/tool.clj` | `!tool-defs`, `deftool` / `defcommand` / `defskill` / `defagent`, `get-tool-defs`, `bind-tools`, `invoke-tool` |
| `common/commands.clj` | Bootstrap commands (`ask`, `think`, `artifacts`, …) |
| `common/skills.clj` | Unified skill management (brainyard + claude + agent skills) |
| `common/tools.clj` | Shared tool-roster utilities |
| `common/delegation_use.clj` | Delegation / team-dispatch helpers |
| `common/loop_guard_hook.clj` | Default loop-guard via `:agent.tool-use/pre` |
| `mcp/integration.clj` | MCP config, server lifecycle, dynamic tool registration |
| `mcp/client.clj` | stdio / HTTP MCP client |
| `mcp/commands.clj` | MCP-specific slash commands |

---

## TUI integration

The TUI surfaces tools in three ways:

- **Autocomplete** — `bases/agent-tui/src/ai/brainyard/agent_tui/autocomplete.clj`
  queries `agent/get-tool-defs` and suggests completions for slash
  commands and tool names.
- **Slash commands** — tools with `:type :command` are exposed via
  slash invocation (e.g. `/mcp`, `/task`, `/queue`).
- **Permissions prompts** — when `tool-calls-action` requests approval,
  `bases/agent-tui/src/ai/brainyard/agent_tui/permissions.clj` renders
  the prompt inline and delivers the action-promise result.
