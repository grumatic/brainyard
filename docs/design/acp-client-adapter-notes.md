# ACP Client Adapter — Notes

Practical notes on how the ACP client adapter behaves: how a backend's
`session/update` stream is translated into brainyard hook events, and the
concrete quirks of the `claude-code-acp` backend observed in practice. Compiled
while building the ACP transcript display block (the TUI surface for `acp-agent`
turns; the `acp-*` block family in
`bases/agent-tui/src/ai/brainyard/agent_tui/session.clj`).

Companion docs: [`acp-design.md`](./acp-design.md) (full client design),
[`acp-agent-management.md`](./acp-agent-management.md) (instance lifecycle),
[`acp-agent-redesign-boundary.md`](./acp-agent-redesign-boundary.md) (why the
adapter stays a pure transport).

Source of truth: `components/acp-client/` and
`components/agent/src/ai/brainyard/agent/common/acp_agent.clj`.

## Backend spawn

`components/acp-client/src/ai/brainyard/acp_client/core/registry.clj` holds the
backend registry. Built-in keys: `:stub` (in-tree, deterministic —
`clj -M -m ai.brainyard.acp-stub-agent.core`), `:claude-code`, `:gemini`,
`:codex`, plus any custom key. Each entry carries a default `:command` vector,
overridable per instance via `:acp-backend-opts {:command [...]}`.

- **`:claude-code`** default command = `npx -y @zed-industries/claude-code-acp`.
  - **Prereq:** `npx` / Node.js on `PATH` (`:prereqs ["npx"]`). `npx -y`
    auto-fetches the adapter on first use.
  - Runs on a Claude **subscription** — no API key required.
  - The first turn pays the adapter spawn cost.

## Event translation

`components/acp-client/src/ai/brainyard/acp_client/core/events.clj` is **pure
data** (no dependency on `agent.core.hooks`). `translate-update` maps one
`session/update` params map to `{:event <hook-kw> :data {…}}`, or `nil`.

`normalize-update` first lifts the spec-nested shape
`{:sessionId .. :update {:sessionUpdate .. <payload>}}` up to a single flat map
(the in-tree stub and some tests emit the payload flat at the top level), so the
dispatch is agnostic to nesting.

| `sessionUpdate` variant | hook event | notes |
|---|---|---|
| `agent_message_chunk` | `:agent.dspy-action/chunk` | the **answer** text |
| `agent_thought_chunk` | `:agent.dspy-action/chunk` | **`:meta {:kind :thought}`** — same event kw as message; disambiguate on `:meta :kind` |
| `plan` | `:todo/updated` | entries → todo-list |
| `tool_call` | `:agent.tool-use/pre` | `{:call-id :tool-name :args :status :observer? true}` |
| `tool_call_update` (completed \| failed) | `:agent.tool-use/post` | status pending / in_progress → **nil** (observer-only) |
| anything else | — | `nil` |

**Stop reasons** come from the `session/prompt` *response*, not `session/update`
— `translate-stop-reason`: `end_turn` → `:agent.iteration/post` (goal true);
`cancelled` / `max_tokens` / `max_turn_requests` / `refusal` →
`:agent.iteration/exhausted`. **One ACP turn maps to one iteration boundary.**

## Tool naming

`acp-tool-name` (in `events.clj`) prefers `_meta.claudeCode.toolName` over the
ACP `:title`. The `:title` is a human-readable *description* (the shell command,
`Read <path>`, …), so using it would show argument text where the tool name
belongs. Fallback order: `_meta.claudeCode.toolName` → `:title` → `:kind` →
`"tool"`.

- **Observed inconsistency:** `claude-code` surfaced some tools bare (`Glob`) and
  others MCP-prefixed (`mcp__acp__Read`) *within the same session* — the
  adapter's reported `toolName` is passed through verbatim, not normalized.

## `acp_agent` bridge

`components/agent/src/ai/brainyard/agent/common/acp_agent.clj` wires the adapter
into an `acp-agent` defagent:

- One `AcpClient` + one ACP session are cached per agent instance on `:!state`
  (`::client` / `::session`) and **reused across asks**, so the backend keeps
  conversation context. Both are torn down on `:agent.instance/closed`.
- `on-event-handler` accumulates chunk text into a `StringBuilder` for
  `:answer`, appending **only non-thought** chunks (thought chunks would
  otherwise pollute the final answer). It fires the translated hook enriched with
  `:agent` and `:accumulated`.
- The behavior tree is `[:repeat max-n=1 [:action acp-prompt]]` — the external
  backend owns the real agentic loop; the single `:repeat` exists only to fire
  the iteration hooks the TUI already renders.
- **Permission bridge:** `session/request_permission` routes to the agent's
  `:user-feedback-fn` N-option picker; **deny-by-default** (selects a `reject_`
  option) when there is no interactive session or no options.
- **Model** is pinned per ACP session (`session/set_model`); switching model is a
  **session recycle** (a new session = a new conversation). The `claude-code`
  model can also be set via `~/.claude/settings.json` `"model"`.

## `claude-code-acp` quirks (verified live)

- **Reports no usage** over `session/update` → any token counter reads `0 tok`.
  Consumers should treat a missing usage as "unknown", not zero work.
- **Emits no `agent_thought_chunk`** for simple read/answer queries (opus).
  Thought output is backend- and prompt-dependent — never assume it is present.
- May emit `tool_call` **twice for one `call-id`** (a placeholder with empty
  input, then the real input). Consumers must **upsert/merge by `call-id`**, not
  blindly append, or a single call shows as two lines.
- Tool result content is nested, e.g.
  `{:status "completed" :content [{:type "content" :content {:type "text" :text "…"}}]}`.
- In this repo's dev environment the default config routes `acp-agent` to
  `:claude-code` / opus, **not** the schema's `:stub` default — check
  `(config/get-config ag :acp-backend)` rather than assuming stub.
