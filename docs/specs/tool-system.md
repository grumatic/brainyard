# Spec: Tool System

*Area code `TOOL`. Covers the unified tool registry (`!tool-defs`), the
`deftool` family of registration macros, dispatch with hooks and
visibility, MCP surfacing, sub-agents-as-tools, and sandbox
auto-binding. The reasoning loops that dispatch into this registry are in
[reasoning](reasoning.md); the hooks fired during dispatch are in
[agent-runtime](agent-runtime.md) §6.*

Status legend and contract-ID conventions: see [README](README.md).

---

## 1. The registry

A single registry holds commands, skills, and sub-agents under one shape,
so dispatch, visibility, and conversion are uniform.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TOOL-01 | A single `!tool-defs` atom MUST hold every tool; each entry MUST have the shape `{:id kw :type kw :fn (var …) :meta options}`. | Implemented | `agent/core/tool.clj` (`!tool-defs`) |
| CR-TOOL-02 | `deftool` MUST be the unified registration macro; `defcommand`/`defskill`/`defagent` MUST be thin wrappers that differ only by `:type` (`:command`/`:skill`/`:agent`). | Implemented | `agent/core/tool.clj` |
| CR-TOOL-03 | Auto-injected meta MUST be namespaced (`:_deftool$<key>`) to avoid colliding with caller arguments. | Implemented | `agent/core/tool.clj` |

The `:type` is the only behavioral discriminator between a command, a
skill, and an agent at the registry level — dispatch routes on it.

---

## 2. Dispatch, hooks, visibility

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TOOL-04 | `call-tool` MUST auto-detect the dispatch path: a bound `:tools` fn-map uses the JSON path; otherwise the `!tool-defs` registry uses the Malli path, validating args via `m/explain` against the tool's input schema. | Implemented | `agent/core/tool.clj` (`call-tool`) |
| CR-TOOL-05 | Registered-fn dispatch MUST run through the hook gate `dispatch-with-hooks`, honoring verdicts `{:allow :modify-args :replace :block}`. | Implemented | `do-call-tool--registered-fn`, `dispatch-with-hooks` |
| CR-TOOL-06 | Tool visibility MUST be resolved by `tool-visible?`: `:visibility :hidden` → not visible; `:allow`/`:deny` glob patterns honored; otherwise visible. | Implemented | `tool.clj` (`tool-visible?`) |
| CR-TOOL-07 | `bind-tools` MUST drop hidden tools when an `:agent-id` is supplied. | Implemented | `tool.clj` (`bind-tools`) |

CR-TOOL-05 is where the `:agent.tool-use/pre` gated hook (the loop guard)
takes effect — see [agent-runtime](agent-runtime.md) CR-RT-24..26.

---

## 3. Sub-agents as tools

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TOOL-08 | An `:agent`-type tool call MUST pass three guards before instantiation: the `:enable-subagent-calls` kill-switch, the depth cap `:max-agent-call-depth` (default 3) via `*call-depth*`, and circular-call detection via `*call-chain*`. | Implemented | `do-call-tool--agent`, `tool.clj` |
| CR-TOOL-09 | Each sub-agent call MUST create a fresh ephemeral instance (default `:auto-close?`), and on pass MUST increment both `*call-depth*` and `*call-chain*`. | Implemented | `do-call-tool--agent`, `tool.clj` |
| CR-TOOL-10 | An async agent reference MUST be awaited via `resolve-agent-ref` (30s, then fall back to a background task). | Implemented | `resolve-agent-ref`, `tool.clj` |

CR-TOOL-08 is the live enforcement site for the delegation caps that
[agent-runtime](agent-runtime.md) CR-RT-22 declares — the dynvars are
declared in `protocol.clj` but enforced here.

---

## 4. MCP surfacing

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TOOL-11 | MCP tools MUST register into the same `!tool-defs` under id `:mcp$<server>$<tool>` with `:type :tool`, and MUST be unwound by `unregister-mcp-tools-for-server!`. | Implemented | `register-mcp-tools-for-server!`, `agent/mcp/integration.clj` |

MCP tools are first-class registry entries — there is no separate
`bind-tools` prefix or parallel dispatch path. They flow through the same
`call-tool`, hooks, and visibility machinery as native tools.

---

## 5. Sandbox auto-binding

The SCI sandbox used by CoAct ([reasoning](reasoning.md) CR-RSN-13)
exposes registry + MCP tools as ordinary callable Clojure functions.

| ID | Contract | Status | Source |
|---|---|---|---|
| CR-TOOL-12 | `auto-tool-bindings` MUST map every visible `!tool-defs` entry to `{symbol fn}`, with a kebab-case symbol and arglists derived from the tool's `:inputs`. | Implemented | `agent/common/sandbox_bindings.clj` (`auto-tool-bindings`, `bind-one-tool`) |
| CR-TOOL-13 | `make-tool-bindings` MUST add the hand-written `call-tool` special (excluded from auto-bind) so the sandbox can reach MCP tools by `:server-name`. | Implemented | `make-tool-bindings`, `sandbox_bindings.clj` |

---

## Gaps & candidate TODOs (this spec)

No `TODO`/`FIXME`/stub markers were found in the tool, MCP, or
sandbox-binding files, and every contract above is Implemented on the
live path. The only cross-cutting note is documentation: the delegation
caps (CR-TOOL-08) are enforced here but declared in `protocol.clj`
([agent-runtime](agent-runtime.md) CR-RT-22) — a cross-reference would
help future readers. *(Doc-only.)*
