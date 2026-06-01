# Sandbox Bindings Reference

Last verified: 2026-03-24 | Total bindings: 60

This document lists all functions and values bound into the SCI sandbox for LLM agents.

## Binding Sources

| Source | File | Description |
|--------|------|-------------|
| **Sandbox builtins** | `clj-sandbox/core/sandbox.clj` `build-sci-namespaces` | FINAL, JSON, pprint, llm-query |
| **SCI builtins** | SCI runtime (not explicit bindings) | `print/println/pr/prn/printf/format` — captured via `sci/out` |
| **Context accessors** | `clj-sandbox/core/context_accessors.clj` `make-context-accessors` | Selective context retrieval |
| **Tool bindings** | `agent/common/sandbox_bindings.clj` `make-tool-bindings` | File ops, tools, search, bash, memory, brainyard config |
| **Todo bindings** | `agent/common/sandbox_bindings.clj` `make-todo-bindings` | Ephemeral task tracking |
| **Plan bindings** | `agent/common/sandbox_bindings.clj` `make-plan-bindings` | Persistent plan management |
| **Skill bindings** | `agent/common/sandbox_bindings.clj` `make-skill-bindings` | Unified skill management (native + CLI) |

## Output Capture

`eval-code` binds both `*out*` (Clojure) and `sci/out` (SCI) to the sandbox `StringWriter`:
- SCI builtins (`print/println/pr/prn/printf`) → captured via `sci/out`
- Clojure namespace fns (`clojure.pprint/pprint`) → captured via `*out*`

No `reserved-vars` concept — `initial-vars` (captured at sandbox creation) is used by
`extract-user-vars` to distinguish infrastructure from user-defined variables.

## SCI Builtins (always available, no explicit binding needed)

`print`, `println`, `pr`, `prn`, `printf`, `format` — SCI runtime builtins, captured via `sci/out`.

## Library Namespaces (available via `require` or fully-qualified)

Defined in `build-library-namespaces`:
- `clojure.pprint` — `pprint` (also available as user shorthand)
- `clojure.data.json` — `read-str`, `write-str`
- `clojure.core.protocols` — `datafy`, `nav` (JDBC interop)

## All Explicit Bindings (57 total)

### Sandbox Builtins (always bound)

| Symbol | Type | Description |
|--------|------|-------------|
| `FINAL` | fn | `(FINAL answer)` — return final answer string |
| `pprint` | fn | `(pprint x)` — shorthand for `clojure.pprint/pprint`, output captured via `*out*` |
| `parse-json` | fn | `(parse-json str)` — JSON string to map (string keys; `:key-fn keyword` for keyword keys) |
| `to-json` | fn | `(to-json data)` — data to JSON string |
| `llm-query` | fn | `(llm-query prompt)` — sub-LLM call. Returns `{:error ...}` if not configured |
| `llm-query-batched` | fn | `(llm-query-batched [prompts])` — concurrent batch. Returns `{:error ...}` if not configured |
| `rlm-query` | fn | `(rlm-query prompt)` — child RLM sandbox. Returns `{:error ...}` if not configured |

### Context Accessors (always bound)

| Symbol | Type | Description |
|--------|------|-------------|
| `context-index` | fn | `(context-index)` — structural overview: keys, types, sizes, nested structure |
| `context-get` | fn | `(context-get [:path :to :data])` — path-based access with auto-truncation. Options: `:raw`, `:limit`, `:str-limit` |
| `context-keys` | fn | `(context-keys [:path])` — list keys/indices at any nesting level |
| `context-sample` | fn | `(context-sample [:path] 3)` — sample N items from collection. Options: `:strategy :random\|:evenly-spaced\|:first\|:last` |
| `context-search` | fn | `(context-search "pattern")` — search all string values recursively. Options: `:limit`, `:case-sensitive` |

Agent state is part of the context under `:agent-state` with subkeys `:info`, `:config`, `:runtime`:
- `(context-get [:agent-state :info])` — agent identity (agent-id, name, status)
- `(context-get [:agent-state :config])` — agent config (working-dir, tools, dirs)
- `(context-get [:agent-state :runtime])` — live runtime state with `introspect-fn` for on-demand st-memory access

### Tool Bindings (always bound via make-tool-bindings)

| Symbol | Type | Description |
|--------|------|-------------|
| `list-tools` | fn | `(list-tools)` — list available tools |
| `get-tool-info` | fn | `(get-tool-info :tool-id "name")` — tool schema |
| `call-tool` | fn | `(call-tool "name" :arg "val")` — invoke tool |
| `mcp$server` | fn | `(mcp$server :op "list")` — MCP server inspection (op: list/info/config/capabilities/resources/prompts/health) |
| `mcp$tools` | fn | `(mcp$tools :op "list" :server-name "<s>")` — MCP tool work (op: list/call/read-resource/get-prompt) |
| `mcp$lifecycle` | fn | `(mcp$lifecycle :op "start" :server-name "<s>")` — start/stop/restart a server |
| `bash` | fn | `(bash "command")` — shell execution. Use for glob (`find`/`ls`) and tree (`tree -L 4` or `find -maxdepth 4 -type d`) too — no separate `list-files` / `tree` bindings any more. For multi-line scripts, write to `/tmp/foo.sh` via `write-file` then `(bash "bash /tmp/foo.sh")`. |
| `read-file` | fn | `(read-file "path")` — read file content |
| `write-file` | fn | `(write-file "/tmp/out.edn" data)` — write file |
| `grep` | fn | `(grep "pattern" "src/")` — regex search |
| `fetch-url` | fn | `(fetch-url "https://...")` — HTTP GET |
| `search` | fn | `(search "query")` — search project files, config files, long-term memory, registered tools. Tokens <3 chars dropped for non-memory sources (AND, case-insensitive). Options: `:memory-limit` (default 5) |
| `get-turn-log` | fn | `(get-turn-log)` — execution log for a turn |
| `list-turn-logs` | fn | `(list-turn-logs)` — all turn IDs |
| `get-user-feedback` | fn | `(get-user-feedback "question" ["opt1" "opt2"])` — prompt user |
| `remember-fact` | fn | `(remember-fact "content" :fact-type :type)` — store memory |

### Todo Bindings (always bound via make-todo-bindings)

| Symbol | Type | Description |
|--------|------|-------------|
| `create-todo` | fn | `(create-todo "goal" [{:description "step"}])` |
| `mark-todo-done` | fn | `(mark-todo-done task-id "result")` |
| `todo-status` | fn | `(todo-status)` — progress summary |
| `get-todo` | fn | `(get-todo)` — full task list |
| `get-todo-pending` | fn | `(get-todo-pending)` — pending only |
| `get-todo-independent-pending` | fn | `(get-todo-independent-pending)` — parallel-safe |

### Plan Bindings (always bound via make-plan-bindings)

| Symbol | Type | Description |
|--------|------|-------------|
| `create-plan` | fn | `(create-plan "title" "goal" [steps])` |
| `list-plans` | fn | `(list-plans)` |
| `read-plan` | fn | `(read-plan "slug")` |
| `update-plan-step` | fn | `(update-plan-step "slug" idx "result")` |
| `add-plan-step` | fn | `(add-plan-step "slug" "description")` |
| `plan-status` | fn | `(plan-status "slug")` |
| `complete-plan` | fn | `(complete-plan "slug")` |
| `abandon-plan` | fn | `(abandon-plan "slug")` |
| `reopen-plan` | fn | `(reopen-plan "slug")` |
| `reset-plan-step` | fn | `(reset-plan-step "slug" idx)` |
| `delete-plan` | fn | `(delete-plan "slug")` |
| `plan-exists?` | fn | `(plan-exists? "slug")` |

### Skill Bindings (always bound via make-skill-bindings)

Three skill types: `:brainyard` (local FS), `:claude` (~/.claude/skills), `:agents` (~/.agents/skills).

| Symbol | Type | Description |
|--------|------|-------------|
| `list-skills` | fn | `(list-skills)` — all types; `:type :brainyard|:claude|:agents`, `:scope` (brainyard) |
| `read-skill` | fn | `(read-skill "name")` — auto-detects type; `:type`, `:scope` |
| `find-skills` | fn | `(find-skills "query")` — search across types; `:type` |
| `create-skill` | fn | `(create-skill "name" "content")` — brainyard only; `:scope`, `:scripts`, `:resources` |
| `update-skill` | fn | `(update-skill "name" :content "...")` — brainyard only |
| `remove-skill` | fn | `(remove-skill "name")` — `:type`, `:scope` |
| `install-skill` | fn | `(install-skill "package")` — CLI; `:type :claude|:agents` (default :agents) |
| `sync-skills` | fn | `(sync-skills)` — update all CLI skills; `:type` |

## Sync Rules

When adding/removing sandbox bindings, update ALL of these:

1. **Binding function** — `sandbox_bindings.clj` (`make-*-bindings`) or `sandbox.clj` (`build-sci-namespaces`)
2. **Prompt docs** — `prompt.clj` modular sections (use `build-function-docs` + `build-function-usage-prompt`)
3. **This document** — `SANDBOX-BINDINGS.md`

Failing to sync causes:
- In prompt but not bound → LLM tries to call non-existent function, wastes iterations
- Bound but not in prompt → LLM doesn't know the function exists, never uses it
