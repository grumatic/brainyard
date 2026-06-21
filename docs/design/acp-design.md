# Applying the Agent Client Protocol (ACP) to brainyard

> Status: Shipped — `acp-agent` is registered in `components/agent` (`common/acp_agent.clj`), backed by the `acp` and `acp-client` components; `bb tui:acp` runs it against an in-tree stub backend. This document is the original design proposal (2026-05-10, author: assistant + Jake Na).
>
> **As-built (verified against code):** all six implementation phases shipped,
> including the "future" Phase 6 real backends.
>
> - `components/acp` — `interface.clj` + `core/{schema,jsonrpc,methods,transport}.clj`
>   and `core/transport/stdio.clj`. (Tests are `jsonrpc_test.clj` and
>   `transport_stdio_test.clj`.)
> - `components/acp-client` — `interface.clj` + `core/{client,session,callbacks,events,registry}.clj`.
>   (Tests are `events_translation_test.clj`, `registry_test.clj`,
>   `e2e_against_stub_test.clj` — the proposed `client_handshake_test.clj`
>   was not created as a separate file.)
> - `bases/acp-stub-agent` — `src/.../core.clj` plus a deployable
>   `projects/acp-stub-agent` project (alias `:asa` in `workspace.edn`), run via
>   `bb acp-stub:run` and spawned by the `:stub` backend through
>   `clj -M -m ai.brainyard.acp-stub-agent.core --echo`.
> - `components/clj-llm/.../core/acp.clj` — the `:acp` provider, wired into
>   `providers.clj` and the `llm.clj` dispatch (`:acp` arm).
> - `acp-agent` is registered in `agent.interface` (the one-line require flagged
>   in §9.2 was added) and its config keys (`:acp-backend`, `:acp-backend-opts`,
>   `:acp-timeout-ms` 600000, `:acp-permission-timeout-ms` 120000) live in
>   `agent.core.config`.
> - **All four registry backends shipped** (`:stub`, `:claude-agent-acp`,
>   `:gemini`, `:codex`) — see §9.5. The phase language below that calls Phase 6
>   "future"/"optional" is therefore stale; treat it as historical sequencing.
>
> Confirmed with the user before this doc was written:
>
> - Direction: **ACP client only** in this milestone (brainyard consumes
>   external ACP agents).
> - Layer: **both** — a `:acp` provider in `components/clj-llm` for
>   predict / chain-of-thought paths, **and** a new `acp-agent` `defagent` in
>   `components/agent` for full agentic loops where the external agent owns
>   iteration.
> - Existing `claude-code` provider: **strictly additive** — leave
>   `components/clj-llm/.../claude_code.clj` in place, do not deprecate.
> - First backend: **a custom Clojure ACP agent in-tree**
>   (`bases/acp-stub-agent`) that wraps `coact-agent` and re-emits its
>   lifecycle as ACP `session/update` notifications. Validates the protocol
>   from both ends without an external Node/TS dependency.

## 1. Context

Brainyard already integrates Claude Code (the CLI) as an LLM provider in
`components/clj-llm/.../claude_code.clj`. That integration is a single-shot
subprocess wrapper: each `chat-completion` spawns `claude -p` with all
built-in tools disabled, flattens the conversation into a labeled prompt,
and reads NDJSON from stdout. It works, but it is bespoke, throws away most
of the agentic UX (plans, streamed tool calls, permissions, multimodal),
and only talks to one CLI.

The **Agent Client Protocol (ACP)** — open-sourced by Zed in 2025 and now
backed by JetBrains — is the LSP-equivalent for coding agents. It is a
JSON-RPC 2.0 contract over stdio that lets any client drive any conformant
agent (Gemini CLI, the official `claude-agent-acp` adapter, Codex CLI,
Cline, OpenCode, Kiro, …). Adopting it as a brainyard *client* gives us:

1. A standard, vendor-neutral way to consume external coding agents —
   replacing the bespoke claude-code subprocess pattern with a protocol
   that already carries plans, streamed tool calls, permission prompts,
   multimodal blocks, and MCP wiring.
2. Reuse of the existing TUI hook system (`:agent.dspy-action/chunk`,
   `:agent.tool-calls/pre|post`, `:agent.tool-use/pre|post`,
   `:todo/updated`) as the rendering substrate — ACP `session/update`
   notifications map almost 1:1 onto these events.
3. A path to make brainyard composable with the rest of the agent
   ecosystem without rewriting per-vendor integrations.

## 2. ACP — protocol summary (the parts that matter for this design)

Sources: <https://agentclientprotocol.com/protocol/overview> and the
JSON-RPC methods documented under
`protocol/{initialization,session-setup,prompt-turn,content,tool-calls,file-system,agent-plan,extensibility}`.

- **Transport.** JSON-RPC 2.0 over **stdio**, framed as **line-delimited
  JSON (NDJSON)**. Requests carry `id`; notifications omit it. Remote
  transports (HTTP, WebSocket) exist but stdio is the only mandatory one.
- **Lifecycle (client → agent requests).**
  - `initialize` — exchanges `protocolVersion`, `capabilities`,
    `implementation`. Capability negotiation is opt-in: anything omitted
    is treated as `UNSUPPORTED`.
  - `authenticate` — optional, agent-driven auth flow.
  - `session/new` — creates a session with `workingDirectory` and an
    optional `mcp_servers` list. Agent returns a `sessionId`.
  - `session/load` — replay an earlier session (only if the agent
    advertises `loadSession`).
  - `session/prompt` — drives one turn. Body is `{sessionId, content[]}`
    where `content[]` is an array of content blocks.
  - `session/cancel` — cancels the current turn. The agent **must**
    return `StopReason: "cancelled"` instead of an error.
- **Notifications & callbacks (agent → client).**
  - `session/update` — the streaming workhorse. Carries any of:
    `agentMessageChunk` (text delta), `agentThoughtChunk` (reasoning
    delta), `plan` (full replacement of the agent's todo list),
    `toolCall` (lifecycle: `pending` → `in_progress` → `completed | failed`,
    with `kind ∈ {read, edit, delete, move, search, execute, think,
    fetch, other}` and progressively-streamed `result` content blocks).
  - `session/request_permission` — gated request. Body has `toolName`,
    `kind`, and `options[]` of `{id, title}`; canonical ids are
    `allow_once | allow_always | reject_once | reject_always`.
  - `fs/read_text_file` / `fs/write_text_file` — agent calls back into
    the client for filesystem I/O. Both require absolute paths and the
    matching client capability.
- **Content blocks.** `text`, `image`, `audio`, `resource` (embedded),
  `resource_link` (by URI). Each block carries optional annotations and a
  custom `_meta`.
- **Stop reasons.**
  `end_turn | max_tokens | max_turn_requests | refusal | cancelled`.
- **Plans.** Sent as a complete array on each `session/update` (no
  patching). Agents are free to add/remove/reorder entries mid-turn.
- **Extensibility.** Every message accepts `_meta`. Custom methods are
  underscore-prefixed; experimental ones live under `experimental/` or
  `unstable/`.
- **MCP composition.** ACP and MCP are orthogonal. Brainyard, as the
  client, forwards MCP server configs to the agent inside
  `session/new.mcp_servers`; the agent connects to those MCP servers
  itself.

Reference adapters worth studying when wiring up the client:

- `agentclientprotocol/claude-agent-acp` — TypeScript adapter for the
  Claude Agent SDK (closest thing to "claude-code over ACP").
- `zed-industries/agent-client-protocol` — canonical schema and Rust
  reference implementation.

## 3. Comparison with the existing `claude-code` provider

| Concern | `claude-code` provider today | ACP integration (this design) |
|---|---|---|
| Layer in repo | `components/clj-llm/.../claude_code.clj` (single file) | New component `components/acp` (protocol + transport + client), plus a `:acp` provider in clj-llm and a new `acp-agent` in `components/agent/common` |
| Process model | One `claude -p` subprocess **per LLM call** | One ACP agent subprocess **per session** (long-lived); `session/prompt` repeats over the same stdio pair |
| Wire format | Plain text on stdin → NDJSON on stdout (Anthropic-CLI shape) | JSON-RPC 2.0 over NDJSON, bidirectional, with request/response correlation |
| Loop ownership | brainyard BT owns the loop; CLI is gagged with `--max-turns 1 --tools ""` | external agent owns the loop; brainyard observes + services callbacks (fs/\*, request_permission) |
| Tool execution | brainyard `invoke-tool` only — claude-code never executes tools itself | external agent executes its own tools; brainyard renders status from `toolCall` updates and gates writes via `session/request_permission` |
| Streaming chunks | `(on-chunk {:type :content-delta :text …})` per Anthropic SSE delta | `session/update` notifications carrying `agentMessageChunk` / `agentThoughtChunk` / progressive `toolCall` updates |
| Permissions | brainyard `:agent.tool-use/pre` gated hook (loop-guard, TUI prompt) | reuse the same gated hook by translating `session/request_permission` into a synthetic `:agent.tool-use/pre` event, then mapping the decision back to one of the four ACP option ids |
| Multimodal | text only | text + image + audio + resource + resource_link content blocks |
| Plan / todo | `:todo/updated` event from `todo_use.clj` | external agent's `plan` updates surface through the same `:todo/updated` event |
| MCP | brainyard owns the MCP client and surfaces tools through `!tool-defs` | brainyard forwards MCP server configs to the agent in `session/new`; the agent owns its own MCP connections |
| Auth | piggy-backs on the local `claude` CLI's cached login | per-agent `authenticate` method, or env-based bearer (delegated to the spawned binary) |
| Cancellation | process kill / BT abort | graceful `session/cancel`, fall back to process kill on timeout |
| Backends supported | only Claude Code CLI | any ACP-conformant agent (claude-agent-acp, gemini, codex, …); the in-tree stub is the first |
| Coexistence | unchanged — both paths supported, no migration forced |

The two integrations sit at **different ownership levels**, which is why
they coexist cleanly:

- `claude-code` is a *provider* — clj-llm asks "complete this prompt"; the
  subprocess returns text; the brainyard BT continues the loop.
- `acp-agent` is an *agent runtime* — brainyard hands over the user query
  and the external agent runs its own loop, streaming structured
  notifications back. The brainyard BT is bypassed entirely for that
  agent type.

A `:acp` clj-llm provider also exists for one specific case: when
something in brainyard needs a single completion (a `predict` signature,
say), and the user wants that completion to come from an ACP-backed model.
That provider squashes one ACP turn into a single `chat-completion`
result, intentionally flattening away plans, tool-call lifecycle, and
permissions. The provider docstring will say so explicitly.

## 4. Recommended architecture

Three additions, layered so each can be tested in isolation.

### 4.1 `components/acp` — pure protocol + transport (no agent semantics)

```
components/acp/
├── deps.edn
├── src/ai/brainyard/acp/
│   ├── interface.clj                 ;; public facade — re-exports below
│   ├── core/
│   │   ├── schema.clj                ;; Malli schemas for ACP messages,
│   │   │                              ;; content blocks, capability maps,
│   │   │                              ;; tool-call kinds + statuses
│   │   ├── jsonrpc.clj               ;; encode/decode JSON-RPC 2.0,
│   │   │                              ;; correlate requests by id,
│   │   │                              ;; route notifications
│   │   ├── transport.clj             ;; NDJSON framing + ITransport protocol
│   │   ├── transport/stdio.clj       ;; ProcessBuilder-backed transport
│   │   │                              ;; (mirrors components/agent/stdio/client.clj)
│   │   └── methods.clj               ;; method/notification name constants
│   │                                  ;; ("session/prompt" etc.) — no logic
└── test/ai/brainyard/acp/
    ├── jsonrpc_test.clj               ;; round-trip + error-shape tests
    └── transport_stdio_test.clj       ;; against a Clojure echo server
```

This component **must not depend on `agent` or `clj-llm`** — keep it a
reusable JSON-RPC building block. It mirrors the layering that
`components/agent/mcp/client.clj` already follows internally.

### 4.2 `components/acp-client` — brainyard-as-client lifecycle + dispatcher

```
components/acp-client/
├── deps.edn                            ;; depends on acp + util + mulog
├── src/ai/brainyard/acp_client/
│   ├── interface.clj                   ;; spawn-agent!, new-session!,
│   │                                    ;; prompt!, cancel!, close!
│   ├── core/
│   │   ├── client.clj                  ;; AcpClient record:
│   │   │                                ;;   - holds transport + state
│   │   │                                ;;   - implements initialize handshake
│   │   │                                ;;   - dispatches incoming
│   │   │                                ;;     notifications + reverse calls
│   │   ├── session.clj                 ;; AcpSession: id, working-dir,
│   │   │                                ;;   message history, plan,
│   │   │                                ;;   pending tool-calls, pending
│   │   │                                ;;   permission requests
│   │   ├── callbacks.clj               ;; servicer for client-side methods:
│   │   │                                ;;   fs/read_text_file,
│   │   │                                ;;   fs/write_text_file,
│   │   │                                ;;   session/request_permission.
│   │   │                                ;; All gated through the same
│   │   │                                ;; permission policy used elsewhere.
│   │   ├── events.clj                  ;; translate session/update payloads
│   │   │                                ;; into brainyard hook events
│   │   │                                ;; (the bridge layer)
│   │   └── registry.clj                ;; named ACP backends:
│   │                                    ;;   :stub, :claude-agent-acp,
│   │                                    ;;   :gemini, :codex (future)
│   │                                    ;; Each is a launch spec
│   │                                    ;; (executable + args + env).
└── test/ai/brainyard/acp_client/
    ├── client_handshake_test.clj
    ├── events_translation_test.clj
    └── e2e_against_stub_test.clj       ;; spawns bases/acp-stub-agent
                                          ;; and runs a full prompt turn
```

#### 4.2.1 Hook event mapping (the contract)

| ACP `session/update` payload | brainyard hook event fired |
|---|---|
| `agentMessageChunk { delta }` | `:agent.dspy-action/chunk` with `:chunk` + `:accumulated` |
| `agentThoughtChunk { delta }` | `:agent.dspy-action/chunk` with `:chunk` + `:accumulated`, marked `:kind :thought` in `:meta` |
| `plan [...]` | `:todo/updated` with normalized `:todo-list` |
| `toolCall { status: in_progress, kind, … }` | `:agent.tool-use/pre` (observer-only — gating already happened via `session/request_permission`) |
| `toolCall { status: completed, result }` | `:agent.tool-use/post` with `:result` and `:agent.tool-calls/post` for the iteration |
| `toolCall { status: failed, error }` | `:agent.tool-use/post` with `:result {:error …}` |
| Stop with `end_turn` | `:agent.iteration/post` with `:goal-achieved true` |
| Stop with `cancelled` | `:agent.iteration/exhausted` |

This means the **TUI does not need any new code** — the existing live
iteration block (`bases/agent-tui/.../session.clj` `dspy-chunk-handler`,
`dspy-post-handler`, `update-iteration-block!`) re-renders ACP turns for
free. That is the single most valuable property of routing through hooks.

### 4.3 `:acp` provider in `components/clj-llm` (predict-style path)

A new file `components/clj-llm/src/ai/brainyard/clj_llm/core/acp.clj`,
parallel to `claude_code.clj`. It registers as:

```clojure
;; providers.clj — additive entry, mirroring :claude-code
:acp {:base-url             nil
      :api-key-env          nil
      :auth-header          nil
      :supports-json-schema? true
      :message-format        :acp}
```

`acp/chat-completion` and `acp/chat-completion-stream` share an
underlying "start session, send one prompt, accumulate text, return on
stop" helper:

1. Lazily start (or fetch from a per-LM cache) an `AcpClient` for the
   configured backend (`:backend` key on the lm-config — defaults to
   `:stub`).
2. Open a session if none exists for this lm-config; otherwise reuse it
   for conversational continuity (same trick used by Anthropic prompt
   caching).
3. Translate `messages` into ACP content blocks (system → first
   `instructions`-style block in `_meta` or prepended; user/assistant
   turns into text blocks with role annotation).
4. Subscribe to `session/update` notifications scoped to the session,
   fold `agentMessageChunk` text into an accumulator, surface the
   `on-chunk` callback per delta (same `{:type :content-delta :text …}`
   shape used by claude-code so callers don't change).
5. On stop, return `{:content … :usage … :stop-reason …}` matching the
   provider contract.

Switch in `llm.clj`:

```clojure
(case (:message-format lm-config)
  :claude-code (claude-code/chat-completion lm-config messages opts)
  :acp         (acp/chat-completion lm-config messages opts)
  :anthropic   (anthropic-chat-completion lm-config messages opts)
  :bedrock     (bedrock/chat-completion lm-config messages opts)
  (openai-chat-completion lm-config messages opts))
```

Caveat written into the provider docstring: when used through this thin
"chat completion" surface, plans / tool-call lifecycle / multimodal /
permissions are *not* surfaced — they are flattened into the final text.
For full ACP UX, callers should use `acp-agent` (below). This keeps the
provider contract honest.

### 4.4 `acp-agent` in `components/agent/common/acp_agent.clj`

A `defagent` whose body **does not run a BT iteration loop**. Instead:

1. On `:agent.instance/created`, spawn / fetch an `AcpClient` for the
   configured backend and open a session (`workingDirectory` from agent
   config, `mcp_servers` synthesized from brainyard's MCP integration).
2. On `ask`, send `session/prompt` with the user content. Block (or
   return a future, mirroring `react-agent`) until a stop notification
   arrives.
3. While the prompt runs, the bridge in `acp-client/core/events.clj`
   fires the existing brainyard hooks for each `session/update`. Existing
   TUI, memory capture pipeline (`:agent.iteration/post`), and analytics
   keep working with no changes.
4. Permissions: when `session/request_permission` arrives, fire
   `:agent.tool-use/pre` (gated). The existing TUI permission UI handles
   it, maps the decision (`:allow` / `:block` / `:replace`) back to the
   appropriate ACP option id (`allow_once` / `reject_once` / etc.).
5. Cancellation: if the agent runtime fires `:agent/exception` or the
   user cancels, send `session/cancel` with the session id; expect the
   `cancelled` stop reason.
6. Closeable: on agent close, `session/close` (if supported) then
   transport close. Reuse `components/agent/stdio/client.clj`'s
   lifecycle pattern.

This agent advertises in its `defagent` `:meta` that it owns its own
loop, so the `coact` / `react` / etc. BT plumbing is **not** invoked. The
runtime simply hands the user input to the ACP session and renders what
comes back.

### 4.5 `bases/acp-stub-agent` — in-tree dog-fooding agent

A tiny base that exposes brainyard's existing `coact-agent` over ACP:

```
bases/acp-stub-agent/
├── deps.edn          ;; depends on agent + acp + util + mulog
├── src/ai/brainyard/acp_stub_agent/core.clj
│   ;; -main:
│   ;;   1. Read JSON-RPC requests from stdin
│   ;;   2. Implement initialize, session/new, session/prompt, session/cancel
│   ;;   3. Driver: when session/prompt arrives, instantiate a coact-agent,
│   ;;      register hook handlers under :source :acp-stub that translate
│   ;;      brainyard hook events back into session/update notifications
│   ;;      (the inverse of acp-client/events.clj).
│   ;;   4. Emit StopReason on agent.iteration/post or agent.iteration/exhausted.
└── test/ai/brainyard/acp_stub_agent/core_test.clj
```

This base is **not** shipped in `agent-tui-app`; it lives behind a
separate project alias (`:asa`) so the integration test can spawn it as
a subprocess. Run via `bb acp-stub:run` (a new task).

> **As-built:** shipped as proposed. `bases/acp-stub-agent` is composed into a
> deployable `projects/acp-stub-agent` project (alias `:asa` in `workspace.edn`),
> which is what the `:stub` registry factory spawns (`clj -M -m
> ai.brainyard.acp-stub-agent.core --echo`). `bb acp-stub:run` exists. Note the
> §9.1 audit recommended *skipping* the `:projects` entry; the project was kept
> after all so the stub can run with its full brick deps resolved.

### 4.6 Workspace plumbing

- `workspace.edn` — add `acp`, `acp-client`, and `acp-stub-agent` (the
  last as a project under `:projects` with alias `:asa`).
- Root `deps.edn` — extend the `:dev` and `:test` aliases to include
  `components/acp` and `components/acp-client` paths.
- `bb.edn` — add `bb test:component acp` (already pattern-matches), plus
  `bb tui:acp` (run TUI configured with `acp-agent`) and
  `bb acp-stub:run` (spawn the in-tree stub for manual testing).

## 5. Files that will be created or touched

### Created (new)

- `components/acp/deps.edn`
- `components/acp/src/ai/brainyard/acp/interface.clj`
- `components/acp/src/ai/brainyard/acp/core/{schema,jsonrpc,transport,methods}.clj`
- `components/acp/src/ai/brainyard/acp/core/transport/stdio.clj`
- `components/acp/test/ai/brainyard/acp/{jsonrpc,transport_stdio}_test.clj`
- `components/acp-client/deps.edn`
- `components/acp-client/src/ai/brainyard/acp_client/interface.clj`
- `components/acp-client/src/ai/brainyard/acp_client/core/{client,session,callbacks,events,registry}.clj`
- `components/acp-client/test/ai/brainyard/acp_client/{client_handshake,events_translation,e2e_against_stub}_test.clj`
- `components/clj-llm/src/ai/brainyard/clj_llm/core/acp.clj`
- `components/agent/src/ai/brainyard/agent/common/acp_agent.clj`
- `bases/acp-stub-agent/deps.edn`
- `bases/acp-stub-agent/src/ai/brainyard/acp_stub_agent/core.clj`
- `bases/acp-stub-agent/test/ai/brainyard/acp_stub_agent/core_test.clj`

### Modified (small, surgical edits)

- `components/clj-llm/src/ai/brainyard/clj_llm/core/providers.clj` — add
  `:acp` entry to the `providers` map (≈5 lines, mirroring
  `:claude-code`).
- `components/clj-llm/src/ai/brainyard/clj_llm/core/llm.clj` — add
  `:acp` case to the dispatch in `chat-completion` and
  `chat-completion-stream`.
- `workspace.edn` — register the new component(s) and the
  `acp-stub-agent` project alias.
- `bb.edn` — new tasks: `tui:acp`, `acp-stub:run`.
- `deps.edn` (root) — extend `:dev`, `:test` aliases with new paths.

### Untouched (deliberately)

- `components/clj-llm/src/ai/brainyard/clj_llm/core/claude_code.clj` —
  kept as-is. The "strictly additive" decision means we do not migrate
  or deprecate this file.
- All TUI rendering — the hook bridge means no edits to
  `bases/agent-tui` are required for ACP to display correctly.

## 6. Reused existing functions / utilities (do not duplicate)

- `components/util/src/ai/brainyard/util/interface/macros.clj` —
  `export-symbols` for the new `interface.clj` files.
- `components/agent/src/ai/brainyard/agent/stdio/client.clj` —
  ProcessBuilder + async stdout reader pattern. Mirror its lifecycle
  (`start`, `read-line!`, `write!`, `close!`) in
  `components/acp/.../transport/stdio.clj` rather than reinventing.
- `components/agent/src/ai/brainyard/agent/mcp/client.clj` —
  JSON-RPC 2.0 framing (request id allocation, response correlation).
  Either factor it out into `components/acp/core/jsonrpc.clj` and have
  MCP depend on the new shared module, or copy-and-adapt with a TODO to
  consolidate later. Prefer the former if scope allows; otherwise note
  the duplication in a follow-up.
- `components/agent/src/ai/brainyard/agent/core/hooks.clj` —
  `register-hook!`, `unregister-source!`, `fire!`, `fire-decision!`. The
  bridge layer in `acp-client` only emits hooks — it never invents new
  event keys. (We do *not* need to extend `event-catalog`.)
- `bases/agent-tui/src/ai/brainyard/agent_tui/session.clj` — already
  subscribes to the right events. Verify behavior with
  `acp-stub-agent`; do not modify.
- `components/clj-llm/src/ai/brainyard/clj_llm/core/claude_code.clj` —
  read for inspiration on the streaming `on-chunk` shape and the
  `flatten-messages` → CLI prompt translation; reuse the same
  `on-chunk` contract in `acp.clj` so callers don't have to change.
- `components/clj-llm/src/ai/brainyard/clj_llm/core/providers.clj` —
  treat `:claude-code` as the template for `:acp` (no api key, no base
  url, custom message format).

## 7. Implementation phases (ship in order, each independently mergeable)

1. **Phase 1 — Protocol bedrock.** `components/acp` only (schema,
   jsonrpc, stdio transport). Tests: round-trip JSON-RPC envelopes,
   NDJSON framing, transport pumps lines through an echo subprocess.
   *Done when* the new component passes `bb test:component acp`. No
   other code touched.
2. **Phase 2 — In-tree stub agent.** `bases/acp-stub-agent` that wraps
   `coact-agent`. Run it manually with `bb acp-stub:run` and poke it
   from a REPL using `components/acp` directly to verify `initialize`,
   `session/new`, `session/prompt`, `session/update`, and
   `session/cancel` end-to-end against an in-process client. *Done
   when* a hand-driven prompt round-trips with a non-empty
   `agentMessageChunk` stream.
3. **Phase 3 — Client + event bridge.** `components/acp-client` with the
   hook translation table from §4.2.1. The e2e test spawns
   `acp-stub-agent` as a subprocess and verifies that brainyard's hook
   events fire in the expected order. *Done when*
   `e2e_against_stub_test` is green.
4. **Phase 4 — `:acp` provider in clj-llm.** Wire `acp.clj` and the
   `providers.clj` / `llm.clj` switches. Test by routing a `predict`
   call through the stub backend and asserting the structured output
   round-trips.
5. **Phase 5 — `acp-agent` defagent.** Hook the client into the agent
   runtime; add `bb tui:acp` to launch the TUI configured with the
   stub backend. Manual smoke: ask a question, watch tool calls render
   in the TUI's existing iteration block, hit `Esc` and verify
   `session/cancel` triggers a `cancelled` stop reason.
6. **Phase 6 (optional, follow-up plan).** Add real backends to
   `acp-client/registry.clj`: `:claude-agent-acp` (npx
   `@agentclientprotocol/claude-agent-acp`), `:gemini` (Google's
   reference agent), `:codex`. Each is a launch spec only — no
   protocol code changes.

   > **As-built:** Phase 6 shipped (not deferred). All three real backends are
   > registered alongside `:stub` in `acp-client/core/registry.clj`. The
   > claude-agent-acp package landed as `@zed-industries/claude-code-acp` (not
   > `@agentclientprotocol/claude-agent-acp`). See §9.5 for the exact specs.

## 8. Verification

End-to-end test for Phases 1–5 (this milestone). Each step is checkable
locally without external network access, since the in-tree stub agent
removes the dependency on Node/TS adapters.

1. **Component-level unit tests.**

   ```bash
   bb test:component acp
   bb test:component acp-client
   ```

   Asserts JSON-RPC framing, NDJSON transport, schema validation of
   well-formed and malformed `session/update` payloads, and the hook
   translation table.

2. **Stub agent integration test.** From `components/acp-client`:

   ```bash
   clj -M:test -e "(require 'clojure.test 'ai.brainyard.acp-client.e2e-against-stub-test) \
     (clojure.test/run-tests 'ai.brainyard.acp-client.e2e-against-stub-test) \
     (shutdown-agents)"
   ```

   The test starts `bases/acp-stub-agent` via `ProcessBuilder`, drives
   it through one full prompt turn, and asserts the hook event order:
   `:agent.dspy-action/chunk` × N, optional
   `:agent.tool-use/pre|post`, `:agent.iteration/post`.

3. **Provider smoke (Phase 4).** From a REPL:

   ```clojure
   (require '[ai.brainyard.clj-llm.interface :as llm])
   (def lm (llm/create-lm {:provider :acp :backend :stub :model "stub-1"}))
   (llm/chat-completion lm
                        [{:role "system" :content "Reply in one word."}
                         {:role "user"   :content "Status?"}]
                        {:on-chunk #(println (:text %))})
   ;; expect: streamed deltas, then a final {:content … :stop-reason :end-turn}
   ```

4. **TUI smoke (Phase 5).**

   ```bash
   bb tui:acp -a acp-agent
   ```

   Ask a question. Verify in the existing TUI live iteration block:
   - streamed text appears chunk-by-chunk;
   - any tool calls emitted by the stub appear with the same widget the
     coact-agent already uses;
   - if the stub emits a permission request, the existing TUI
     permission popup fires and the chosen option id round-trips back
     as `allow_once` / `reject_once`;
   - pressing the cancel key produces a `cancelled` stop reason and the
     iteration block closes cleanly.

5. **Regression check on claude-code provider.** Run the existing
   claude-code path once to confirm "strictly additive" was honored:

   ```bash
   bb tui -p claude-code -m sonnet
   ```

   Should behave identically to before this change.

6. **Polylith workspace validation.**

   ```bash
   bb poly:check
   bb poly:info
   ```

   Confirms the new components, base, and project alias are wired
   without namespace clashes.

## 9. Audit + phase-by-phase plan

> Added 2026-05-10 after auditing §1–§8 against the current codebase.
> Source: Plan agent run on `acp-design` worktree.

### 9.1 Audit findings vs. design assumptions

| Assumption | Status | Notes |
|---|---|---|
| `claude_code.clj` is single-shot subprocess; `on-chunk {:type :content-delta :text …}`; `flatten-messages` exists (§3, §4.3) | ✅ confirmed | `flatten-messages` at lines 14–29; single-shot + stream variants at 199–392; chunk shape at 292/315. |
| `providers.clj` has `:claude-code`; `llm.clj` dispatch is `case (:message-format …)` with `:claude-code / :anthropic / :bedrock / default openai` (§4.3) | ✅ confirmed | `providers.clj` lines 81–85; `llm.clj` lines 500–510. The `:acp` arm slots in cleanly (~5 lines per arm). |
| `agent/stdio/client.clj` API: `start`, `read-line!`, `write!`, `close!` (§6) | ⚠️ partially correct | Real public API is `start!` / `send-line!` / `wait-for` / `wait-for-idle` / `get-output` / `shutdown!` / `alive?`. Pattern (ProcessBuilder + daemon reader thread + atom-of-lines) is the right reference, but the doc's named ops don't exist verbatim. **Mirror the lifecycle, not the API.** |
| `agent/mcp/client.clj` JSON-RPC framing can be factored out (§6) | ⚠️ partially correct | Encode/decode + id-allocation patterns exist at lines 73–141; **however** the response loop is *synchronous, single-threaded* (one request blocks at a time). ACP needs **concurrent** request/notification multiplex (agent fires `session/update` between turns; client services reverse `fs/*` calls). Refactoring MCP to share is therefore out of scope. **Build new in `components/acp/core/jsonrpc.clj`; leave MCP alone; file a follow-up issue for consolidation.** |
| `agent/core/hooks.clj` exposes `register-hook!`, `unregister-source!`, `fire!`, `fire-decision!`; event keys match §4.2.1 | ✅ confirmed | All four functions present (lines 153, 198, 303, 314). Event catalog (lines 99–137) covers every key the bridge table names; `:agent.tool-use/pre` is correctly marked `:gates? true`. |
| TUI subscribes to those events; "zero new code" claim (§4.2.1) | ✅ confirmed | `bases/agent-tui/.../session.clj` has `dspy-chunk-handler`, `dspy-post-handler`, `update-iteration-block!`, `:todo/updated` and `:agent.tool-use/pre|post` handlers, all wired via `register-hook!`. |
| `coact-agent` location and `defagent` macro (§4.4) | ✅ confirmed | `components/agent/.../common/coact_agent.clj` line 2040; `defagent` lives in `agent.core.tool` line 162 — load-time side-effect into `!tool-defs`. |
| Defagents auto-discovered by TUI via interface requires (§4.4 implicit) | ✅ confirmed (worth calling out) | `agent.interface` lines 20–48 explicitly require every `agent.common.*-agent` namespace. **`acp-agent` MUST be added to that list** for `bb tui -a acp-agent` to find it. The doc never calls this out. |
| `workspace.edn` needs a project entry for the stub (§4.6) | ⚠️ partially correct | Polylith auto-discovers components and bases from their directories; `:projects` only matters when the stub is exposed as a deployable project. **Recommend treating `bases/acp-stub-agent` as a base only, run via `bb acp-stub:run`.** Skip the `:asa` `:projects` entry. |
| `bb test:component <name>` already pattern-matches; `bb tui:acp` shape is right (§4.6) | ✅ confirmed | `bb.edn` line 177–188 (`test:component` glob); `tui` task at line 38 makes `bb tui:acp` a thin wrapper for `bb tui -a acp-agent`. |
| Root `deps.edn` `:dev` alias extended (§4.6) | ✅ confirmed | Lines 7–61 list every component; new components added the same way. `agent-tui-app/deps.edn` *also* needs updating at Phase 5 to import `acp-client`. |
| `claude-code` provider stays untouched | ✅ — enforced by review | Dispatch is a single `case` arm; `:acp` is purely additive. |

### 9.2 Open decisions (need user input)

1. **JSON-RPC sharing**: build new in `components/acp` (recommended), leave MCP as-is, file follow-up — *vs.* refactor MCP. Concurrency gap makes the refactor too large for this milestone.
2. **`acp-agent` registration**: confirm one-line require addition to `agent.interface` lines 20–48 is acceptable as part of Phase 5.
3. **Stub mode**: `--echo` deterministic mode for CI vs. real LLM through coact? Recommend: support both, default `--echo` in CI tests.
4. **Permission id mapping**: when an agent supplies non-canonical option ids, fallback policy = first id starting with `allow_` for `:allow`, first `reject_` for `:block`, else first option.
5. **`:acp` provider session cache key**: `(provider, backend, model, config-hash)` for the per-LM AcpClient cache.
6. **Iteration boundary**: each ACP `session/prompt` round-trip is one `iteration` (recommended); tool calls inside fire `:agent.tool-calls/pre|post` per "batch" the agent emits.

### 9.3 Phase plan

**Phase 1 — Protocol bedrock (`components/acp`).** Schema + jsonrpc + stdio transport + tests. No edits to `agent/`, `clj-llm/`, `bases/` beyond a one-line addition to root `deps.edn` `:dev` alias. **This is the smallest viable first PR.**

- *Files (new):* `components/acp/{deps.edn,src,test}/...`; `core/{schema,jsonrpc,transport,methods}.clj`; `core/transport/stdio.clj`; `interface.clj`; tests.
- *Verification:* `bb test:component acp` green; `clj -M:poly check` clean.
- *Risks:* concurrent stdin/stdout interleaving — mitigated by single reader thread + write lock + sentinel-on-EOF.

**Phase 2 — In-tree stub agent (`bases/acp-stub-agent`).** Wraps `coact-agent`, translates hooks → `session/update`. Add `bb acp-stub:run`. Decide stub mode (open decision 3).

**Phase 3 — Client + event bridge (`components/acp-client`).** Concurrent dispatcher pump (replaces MCP's synchronous loop), session lifecycle, callbacks (`fs/*` + `session/request_permission`), event bridge per §4.2.1, registry (Phase 3 ships `:stub` only). e2e against the stub.

- *Risks:* hook handlers fire on the dispatcher pump thread — slow subscribers block subsequent ACP messages. Run handlers via `(future …)` if marked `:async true`. Verify TUI handlers are non-blocking (they update an atom-backed iteration block, which is fast).

**Phase 4 — `:acp` provider in `clj-llm`.** Mirror `claude_code.clj`. Soft-couple to `acp-client` via `requiring-resolve` (same trick `clj-llm` already uses for the `oauth` namespace) so `clj-llm/deps.edn` does **not** gain an `acp-client` entry — preserves Polylith dependency direction.

- *Edits:* `providers.clj` (+5 lines), `llm.clj` (3 small spots — including the no-api-key set on line 486), new `core/acp.clj`.

**Phase 5 — `acp-agent` defagent.** Requires the one-line addition to `agent.interface` (open decision 2). Add `bb tui:acp`. Permission flow translates `session/request_permission` → synthetic `:agent.tool-use/pre` → existing TUI popup → option id roundtrip.

- *Risks:* TUI permission UI assumes synchronous `fire-decision!`. The dispatcher pump thread invoking it synchronously waits for a keystroke — already works for in-process claude-code; verify by tracing the existing `:agent.tool-use/pre` path before relying on it.

**Phase 6 — Real backends.** Add `:claude-agent-acp`, `:gemini`, `:codex` as registry entries — launch specs only, no protocol changes. See §9.5 for the shipped specs and customization surface.

### 9.4 Smallest viable first PR

**`components/acp` only — Phase 1.** Pure JSON-RPC + stdio transport + Malli schemas + tests, with `bb test:component acp` green and `clj -M:poly check` clean. Zero edits to `agent/`, `clj-llm/`, `bases/`, `bb.edn`, or `workspace.edn` — only a one-line addition of `ai.brainyard/acp {:local/root "components/acp"}` to root `deps.edn`'s `:dev` alias for REPL convenience. Delivers a reusable JSON-RPC building block that any future protocol work can lean on, and is independently mergeable with no functional surface area exposed yet.

### 9.5 Phase 6 backends — registered launch specs

Each backend lives in `components/acp-client/src/.../core/registry.clj`
as a launch-spec factory. The factory takes an `opts` map and returns
`{:command [...] :working-dir str :env {...}}` consumed by
`acp/create-stdio-transport`. Defaults are opinionated; users override
any field via `(acp-client/spawn! :backend {:backend-opts {...}})` or
the agent's `:acp-backend-opts` runtime config.

| Backend | Default command | Prereqs | API key env (forwarded by default) |
|---|---|---|---|
| `:stub` | `clj -M -m ai.brainyard.acp-stub-agent.core --echo` | `clj` | none |
| `:claude-agent-acp` | `npx -y @zed-industries/claude-code-acp` | `npx` (Node) | `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN` |
| `:gemini` | `gemini --experimental-acp` | `gemini` | `GEMINI_API_KEY`, `GOOGLE_API_KEY` |
| `:codex` | `codex --acp` | `codex` | `OPENAI_API_KEY` |

**Override surface (per backend factory):**

- `:command` — vector of strings replacing the default invocation
  (e.g. local-development binary, alternate package, version pin)
- `:working-dir` — cwd for the spawned subprocess (default
  `System/getProperty "user.dir"`)
- `:env` — extra env vars merged on top of the forwarded set
- `:forward-env` — vector of parent-process env-var names to pass
  through (used to keep the forwarding allowlist explicit)

**Public API additions in `acp-client.interface`:**

- `(list-backends)` — introspection without leaking the factory fns
- `(register-backend! kw factory & opts)` — runtime registration of
  custom backends
- `(unregister-backend! kw)` — removal (refuses `:stub`)
- `(backend-available? kw)` — PATH probe; returns
  `{:status :ok | :missing-prereqs | :unregistered ...}`
- `(which cmd)` — absolute path of an executable on PATH (or nil)

**Customization examples:**

```clojure
;; Use a globally-installed binary instead of npx fetch-on-demand
(acp-client/spawn! :claude-agent-acp
                   {:backend-opts {:command ["claude-code-acp"]}})

;; Add a new backend at runtime (e.g. cline)
(acp-client/register-backend!
  :cline
  (fn [{:keys [working-dir]}]
    {:command     ["cline" "--acp"]
     :working-dir (or working-dir (System/getProperty "user.dir"))
     :env         {}})
  :description "Cline ACP adapter"
  :prereqs ["cline"])

;; Skip an integration test gracefully when prereqs are missing
(when (= :ok (:status (acp-client/backend-available? :gemini)))
  (run-gemini-integration-test))
```

**What is **not** verified by the test suite.** The launch specs are
unit-tested for shape (command vector, env passthrough, working-dir),
but real-backend round-trips need the corresponding CLI installed and
authenticated. Integration is left to the user. Recommended approach:
guard with `backend-available?`, fall back to `:stub` in CI.
