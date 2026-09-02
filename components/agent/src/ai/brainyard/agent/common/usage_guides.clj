;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.usage-guides
  "Built-in usage-guide CONTENT, registered into `agent.core.usage`.

   These guides were previously hosted in `clj-sandbox` (a closed `case`); they
   are agent-domain knowledge, so they live here now and register into the open
   registry. Loading this namespace populates the registry — it is required
   (bare) by `agent.common.commands` so the guides are present whenever the
   agent component loads.

   New topics (`:tool`, `:code`, `:sandbox`, `:agents`) can be added here, or
   registered next to the feature they document via
   `agent.core.usage/register-usage!`. The `:nrepl` guide is colocated this way
   in `agent.common.debug-agent` (it is also that agent's tool-context)."
  (:require [ai.brainyard.agent.core.usage :as usage]))

;; ============================================================================
;; Guide content (migrated verbatim from clj-sandbox/core/prompt.clj)
;; ============================================================================

(def ^:private usage-output-truncation
  "## Output Truncation — CRITICAL
When output is truncated you'll see:
  `--- TRUNCATED (original: N chars, M lines) ---`
  `--- Full content saved to: /tmp/.../abc123.txt ---`
  `--- Truncation limit: L chars (~K lines). Keep read-file chunks within this limit. ---`
  `--- Recovery: (def data (:content (read-file \"/tmp/...\" :lines [1 K]))) then process with code ---`

**WARNING — DO NOT println/cat the full file.** The printed output will be truncated again, creating an infinite loop.

### How to recover truncated data
The truncation notice tells you the **limit** (chars and lines). Size your `read-file` chunks to stay within it.

```clojure
;; The notice says: Truncation limit: 500 chars (~7 lines)
;; → read at most ~7 lines or ~500 chars per chunk

;; 1. Read by lines (1-based inclusive) — preferred for structured data:
(def chunk1 (:content (read-file \"/tmp/.../abc123.txt\" :lines [1 7])))    ;; first 7 lines
(def chunk2 (:content (read-file \"/tmp/.../abc123.txt\" :lines [8 14])))   ;; next 7 lines

;; 2. Read by character offset/limit — preferred for unstructured text:
(def head (:content (read-file \"/tmp/.../abc123.txt\" :offset 0 :limit 500)))
(def next (:content (read-file \"/tmp/.../abc123.txt\" :offset 500 :limit 500)))

;; 3. Best practice — def and process, never println large data:
(def data (:content (read-file \"/tmp/.../abc123.txt\" :lines [1 7])))
(def parsed (parse-json data))  ;; parse if JSON — keys are strings
(def costs (map #(get % \"Amount\") parsed))  ;; access with string keys
```")

(def ^:private usage-final-rules
  "## FINAL Rules
- **FINAL must be ALONE** — no `def`, `let`, or other code alongside it.
- **Verify-then-FINAL** for non-trivial answers: build answer → `(println answer)` to verify → `(FINAL answer)` in the NEXT iteration.
- For simple/short answers: `(FINAL (str ...))` directly is fine.
- **NEVER embed function results in FINAL string literals** — causes EOF parse errors.
  Always assign to a variable first: `(def info (with-out-str (pprint x)))` then `(FINAL info)`.
- **Markdown alternative**: For complex markdown hard to escape in Clojure strings, write a ```markdown block instead of FINAL.")

(def ^:private usage-discovery
  "## Discovery: search
Use `search` as your FIRST step when you're unsure which file, config, memory, or registered tool is relevant.

`(search \"query\")` searches four sources in one call and returns a map — keys with no hits are omitted:
- `:project-files` — paths under the project (capped at 50)
- `:config-files`  — `.brainyard/` config files (user + project)
- `:memory`        — long-term memory recall (semantic + episodic)
- `:tools`         — registered tools (id + type + description)

### Matching rules
- The query is whitespace-split into tokens; tokens shorter than 3 chars are dropped.
- Remaining tokens must ALL match (AND, case-insensitive) for files/config/tools.
- Memory uses the RAW query — runs even when every token is <3 chars.

### Examples
- `(search \"aws cost\")` — files/config/tools must contain both \"aws\" and \"cost\"; memory recalled on \"aws cost\".
- `(search \"kubernetes\")` — single-token search; memory + any matching files/config/tools.
- `(search \"aws cost\" :memory-limit 10)` — raise memory cap from default 5.

### Related discovery bindings
- `(bash \"find src -name '*.clj'\")` — glob file listing (use `bash` for any file enumeration)
- `(read-file \"path\")` — read a project file
- `(grep \"pattern\" \"src/\")` — regex search in files
- `(skills$find :query \"q\")` / `(skills$list)` — skills (brainyard + claude + agents)
- `(doc$list :kind \"plan\")` / `(doc$list :kind \"todo\")` — plans and todos
- `(mcp$server :op \"list\")` — MCP servers")

(def ^:private usage-tool-priority
  "## Tool Priority (use the simplest available option)
1. **Sandbox builtins** (FIRST) — direct functions listed above
2. **Registered tools** — `(<tool-id> :arg \"val\")` for `task$*`, `aws$*`, etc. (auto-bound kebab-case symbols)
3. **MCP tools** — `(mcp$server :op \"list\")`; native MCP tools register as `mcp$<server>$<tool>` and call directly: `(mcp$<server>$<tool> {:arg \"val\"})`
4. **Skills** — `(skills$find :query \"…\")`, `(skills$read :skill-name \"…\")`, then `bash`
5. **Unregistered MCP fallback** — `(call-tool \"<id>\" {…} :server-name \"<srv>\")` ONLY for tools not in the local registry.")

(def ^:private usage-agent-state
  "## Agent State Inspection
- `(context-get [:agent-state :info])` — agent identity (agent-id, name, status)
- `(context-get [:agent-state :config])` — agent config (working-dir, tools, dirs)
- `(context-get [:agent-state :runtime])` — live runtime state with callable fns:
  - `(def rt (context-get [:agent-state :runtime]))` then `((:introspect-fn rt))` for live st-memory keys
  - `((:introspect-fn rt) :iteration-count)` for current iteration, `((:introspect-fn rt) :key :subkey)` for nested
  - `((:pending-tasks-fn rt))` → `{:count N :tasks [{:id … :name … :job-type … :status :pending|:running :elapsed-ms …}]}`
    Single source of truth for in-flight work — covers code-eval tasks (`:job-type :clj-sandbox-eval`)
    as well as background bash/tool jobs. Same surface as the `task$list` command, but with no tool call.
    Use this when a prior code block returned a `[pending — task-id=…]` marker to decide whether to wait or move on.
- Use `introspect-fn` / `pending-tasks-fn` for LIVE state, `context-get` for INPUT data (conversation, memory)
- Variables persist across turns — the sandbox is NOT recreated between questions")

(def ^:private usage-mcp
  "## MCP Tools (External Servers)
Three polymorphic commands cover MCP server / tool / lifecycle work:
- `(mcp$server :op \"list\")` — list all MCP servers with connection status.
  Other ops: \"info\", \"config\", \"capabilities\", \"resources\", \"prompts\", \"health\"
  (each needs `:server-name`).
- `(mcp$tools :op \"list\" :server-name \"<s>\")` — list a server's native tools.
  Use `(mcp$tools :op \"call\" :tool-calls [{:server-name :tool-name :tool-args}])`
  to invoke. `:read-resource` and `:get-prompt` ops also live here.
- `(mcp$lifecycle :op \"start\" :server-name \"<s>\")` — start/stop/restart.
- `(list-tools :pattern \"^mcp\\\\$\")` — list all MCP tools (registered as `mcp$<server>$<tool>`)
- `(mcp$<server>$<tool> {:param \"value\"})` — call an MCP tool directly by its registered id
Always discover exact tool ids via `(list-tools :pattern \"^mcp\\\\$\")` first — server-side names are reflected in the registered id.")

(def ^:private usage-user-feedback
  "## User Feedback
- `(get-user-feedback \"question\" [\"opt1\" \"opt2\" \"opt3\"])` — select kind: present options, wait for choice
  Options: 2-6 items, strings or maps `{:label \"...\" :description \"...\"}`
  Last option may include `:free-input true` for typed text. Returns `{:selected \"...\" :index N}`.
  Always include a free-input option as the last choice. Use sparingly.
- `(get-user-feedback \"question\" [] :kind \"text\")` — text kind: free-form answer (pass `[]` for options). Returns `{:answer \"...\"}`.
- **Do NOT call in parallel blocks.** User feedback blocks the calling thread for terminal I/O;
  concurrent calls queue and display sequentially, defeating parallelism. Gather data in
  parallel, then ask the user in sequential code.")

(def ^:private usage-memory
  "## Memory
- `(memory$remember :content \"...\" :layer \"l3\" :kind \"preference\" :tags [\"t1\"])` — store an entry. Layers: `l1` (session context), `l2` (episode), `l3` (fact, default). Kinds vary per layer
- `(memory$recall :query \"...\" :limit 10)` — cross-layer RRF recall (no `:layer`), or read one layer with `:layer \"l2\"` (text + filters). Default limit 10
- `(search \"query\" :memory-limit 10)` — cross-layer recall via the search dispatcher (raises memory cap from default 5)
- `(search \"keyword\")` also includes memory results (limit 5) alongside tools, skills, plans, etc.")

(def ^:private usage-todo
  "## Todos — markdown checklists, not an in-memory list
A todo is a markdown checklist FILE with YAML frontmatter (`file-type: todo`,
`id`, `title`) and a `## Todo` section of `- [ ] <action>` lines. Two ways in,
and they agree because both end at the same file.

### Managed (canonical todos under todo-agent/todos/)
- `(doc$create :kind \"todo\" :title \"…\" :goal \"…\" :items [{:description \"step 1\"} …])`
  -> `{:slug :file-path :status}`. `:scope` is \"project\" (default) or \"user\".
- `(doc$list :kind \"todo\")` — optional `:status` draft|in-progress|completed|abandoned.
- `(doc$read :kind \"todo\" :slug \"…\")` -> `{:items :progress :goal :file-path}`.
  Absent returns `{:not-found true}`, NOT an error — check the flag.
- `(doc$update :kind \"todo\" :slug \"…\" …)` — exactly ONE sub-op per call:
  `:status`, `:goal`, `:item-idx N :item-done true|false`, or
  `:add-item \"…\"` (optional `:after-idx`, `:tags {:via … :covers […]}`).
- `(doc$delete :kind \"todo\" :slug \"…\")`.

### Direct (working checklists anywhere — e.g. your own agent dir)
Edit the file with the ordinary file tools, then reconcile:
- flip: `update-file` turning `- [ ] <unique text>` into `- [x] <unique text>`.
  Match the line TEXT, never a numeric index — an insert renumbers everything.
- append: `write-file` a new `- [ ] …` line under `## Todo`.
- then ALWAYS `(todo$sync :path \"/abs/path.md\")` (or `:slug` for a managed one)
  — read-only reconciliation that recomputes progress and refreshes the live
  block. Skip it and the TUI keeps showing stale counts.

### Discipline
- Create one when a task needs 3+ distinct steps, BEFORE starting work.
- Flip each item as you finish it — do not batch flips at the end.
- `:progress` / `todo$sync` return `:next-item`; use it instead of guessing an index.
- Relate work back to `:goal`; finish by answering the goal, not the checklist.

See the `## Todo substrate` section of your system prompt for the inline-file
convention, and `(usage$guide :topic :plans)` for the plan kind.")
(def ^:private usage-plans
  "## Plans — persistent markdown, same `doc$*` family as todos
A plan is a markdown file with a free-form `:body` (context, findings,
approach, risks) under a random 3-word slug. It persists across sessions and
is re-read during execution as working context.

### API (`:kind \"plan\"`)
- `(doc$create :kind \"plan\" :title \"…\" :body \"## Context\\n…\")`
  -> `{:slug :file-path :status}`. `:scope` \"project\" (default) or \"user\".
- `(doc$list :kind \"plan\")` — optional `:status`
  draft|in-progress|completed|abandoned.
- `(doc$read :kind \"plan\" :slug \"…\")` -> `{:body :status :file-path}`.
  Absent returns `{:not-found true}`, not an error.
- `(doc$update :kind \"plan\" :slug \"…\" :body \"…\")` — replace the body; or
  `:status \"completed\"|\"abandoned\"|\"reopen\"`. ONE sub-op per call.
- `(doc$delete :kind \"plan\" :slug \"…\")`.
- `(plan$read-dossier :path \"…\")` — parse just the YAML frontmatter of a
  plan-agent dossier (cheap; read a verdict without pulling the body).

### Writing the body
It is not throwaway — it is the context you re-read while executing. Write it
as if briefing a colleague: exact paths, function signatures, data shapes,
commands to run, edge cases, what could go wrong. Research FIRST (`search`,
`grep`, `read-file`, `bash`), then write the plan from what you found.

### Executing
- `def` the create result; you need `(:slug p)` and `(:file-path p)` later.
- Re-read with `doc$read` on resume rather than re-deriving the context.
- Answer beats bookkeeping: once you have everything the answer needs, populate
  `answer`. Do not spend an iteration marking a plan complete first.
- Prose lives in a plan, checkboxes live in a todo — for per-step tracking use
  `(usage$guide :topic :todo)`.

Termination is the signature's `answer` field. `(FINAL …)` is disabled for
CoAct and returns an error nudge, not a terminator.")
(def ^:private usage-skills
  "## Skills — the `skills$*` family
A skill is a named, reusable procedure: a SKILL.md of imperative steps, with
optional `scripts/` and `resources/`. Three backends — `brainyard` (local,
under `.brainyard/skills/`), `claude` (`~/.claude/skills/`) and `agents`
(`~/.agents/skills/`).

### Find and read (what you need almost every time)
- `(skills$find :query \"<key nouns>\")` — ranked search over INSTALLED skills.
  Local, instant, no network. Optional `:type`, `:limit` (default 20).
- `(skills$list)` — browse. Optional `:type`, and `:scope` project|user for
  brainyard.
- `(skills$read :skill-name \"…\")` — SKILL.md + metadata; `:type` auto-detects.

### Install and author
- `(skills$search :query \"…\")` — search the npx MARKETPLACE for installable
  packages. SLOW, hits the network — use `skills$find` for what you already have.
- `(skills$install …)` / `(skills$import …)` / `(skills$sync)` — acquire and
  refresh CLI-backed skills.
- `(skills$write :op \"create\"|\"update\"|\"remove\" :skill-name \"…\" :content \"…\")`
  — the single mutation entry point. `:scripts` / `:resources` take
  `{filename content}` maps. Defaults to brainyard/project on create.
- `(skills$reload)` — re-scan from disk after an out-of-band edit.

### Using one
Loading a skill hands its SKILL.md to YOU (and pins it as a live artifact) —
the agent holding the task context is the one that follows the procedure. See
the `## Using a skill` section of your system prompt for that flow.")
(def ^:private usage-file-ops
  "## File & URL Operations
Use dedicated file functions instead of `bash` for read/write/grep — they are safer, faster, and return structured data. For glob/tree enumeration, use `bash` with `find`/`ls`/`tree`.

### Directory & File Discovery
- `(bash \"find src -name '*.clj'\")` — glob search via shell
- `(bash \"ls -la src\")` — directory listing
- `(bash \"tree -L 3 src\")` — directory tree (if `tree` is installed; otherwise `find . -maxdepth 3 -type d`)

### Reading Files
- `(read-file \"src/core.clj\")` — read entire file, returns `{:path :content :size}`
- `(read-file \"big.csv\" :lines [1 100])` — read lines 1-100 only (1-based inclusive)
- `(read-file \"big.csv\" :offset 0 :limit 5000)` — read first 5000 chars
### Writing Files (restricted to /tmp/ and .brainyard/)
- `(write-file \"/tmp/result.edn\" (pr-str data))` / `(write-file \".brainyard/notes.md\" content)` / `:append true`

### Searching File Contents
- `(grep \"defn.*process\" \"src/\")` — regex search, returns `{:matches [{:file :line :text}] :count}`
- `(grep \"TODO\" \".\" :include-exts [\".clj\" \".md\"])` — filter; `(grep \"error\" \"logs/\" :max-results 20)` — limit

### Fetching URLs
- `(fetch-url \"https://...\")` — returns `{:url :status :content :content-type :size}`
- `(fetch-url \"https://...\" :max-chars 50000)` / `:headers {\"Accept\" \"application/json\"}`

**Security**: File operations validate paths against allowed directories (project dir + /tmp).")

(def ^:private usage-llm-query
  "## LLM Sub-Queries (`query$llm`)
Delegate reasoning to a sub-LLM. `query$llm` is a command, called positionally
with optional kwargs, returning a result map (`{:result …}` / `{:results […]}` /
`{:error …}`).

| Function                          | Tools? | Iterates? | Cost | Use for                                                  |
|-----------------------------------|--------|-----------|------|----------------------------------------------------------|
| `query$llm` (with `:prompt`)      | no     | no        | low  | reasoning/summary/extraction on data you already have    |
| `query$llm` (with `:prompts`)     | no     | no        | low  | concurrent map-reduce over many independent prompts      |

(There is no general agent-clone primitive in the sandbox. `query$clone` —
clone-self / depth-2 recursion — is gated to `rlm-agent` only; if you are not
rlm-agent you will not have it. To run multi-step work, call a registered agent
by name, e.g. `(explore-agent :question \"…\")`.)

### `query$llm` — pure LLM reasoning, no tools
Use for **analysis, reasoning, and summarization** on data you've already collected — not for raw coding.

Pass EITHER `:prompt` (single string → `{:result \"<answer>\"}`) OR `:prompts`
(vector of strings, max 20 → `{:results [\"<a1>\" ...]}` in input order). Don't
pass both. `:sub-context` is shared across all prompts in batched mode.

When to use:
- **Complex analysis of large data**: parsing, classifying, or summarizing file contents, logs, configs
- **Natural-language reasoning**: answering \"why\" questions, comparing alternatives, drawing conclusions
- **Structured extraction**: pulling tables, categories, or reports from unstructured text

Example — analyze deps across a Polylith monorepo:
```clojure
(def all-deps-raw (:output (bash \"find . -name deps.edn -exec echo '=== {} ===' \\\\; -exec cat {} \\\\;\")))
(def root-raw (:content (read-file \"deps.edn\")))
(def analysis (:result (query$llm
  \"Parse these Clojure deps.edn files from a Polylith monorepo. For each file:
1. Extract component/base name (from path)
2. All :deps entries (library + version)
3. Any alias :extra-deps

Return a markdown report with:
- Table of ALL unique external deps (library | version | used by)
- Version conflicts (same lib, different versions)
- Group by category (web, database, ML/AI, testing, utilities)
- Components with empty :deps {}\"
  :sub-context (str \"ROOT deps.edn:\\n\" root-raw \"\\n\\nCOMPONENT/BASE deps.edn:\\n\" all-deps-raw))))
```

Example — batch analysis with `query$llm` `:prompts`:
```clojure
;; MapReduce: analyze each file independently, then aggregate
(def files (map :file (:matches (grep \"defn\" \"src/\" :include-exts [\".clj\"]))))
(def contents (mapv #(:content (read-file %)) (take 10 files)))
(def prompts (mapv #(str \"Summarize the key functions in this file:\\n\" %) contents))
(def summaries (:results (query$llm :prompts prompts)))  ;; concurrent, max 20
(def report (:result (query$llm \"Combine these per-file summaries into a single architecture overview.\"
                                 :sub-context (clojure.string/join \"\\n---\\n\" summaries))))
```

### When NOT to use any sub-query — write Clojure code instead
- Counting, filtering, sorting → Clojure code directly
- JSON/EDN parsing → `parse-json` or `clojure.edn/read-string`
- String manipulation → `clojure.string` functions
- Arithmetic or aggregation → `reduce`, `frequencies`, etc.")

(def ^:private usage-rules-and-tips
  "## Rules
1. Always check for \"TRUNCATED\" markers before using data
2. **Never println/pprint large data** — process it with code, extract what you need
3. Read the **truncation limit** from the notice and size `read-file` chunks accordingly
4. Use `read-file` with `:lines [start end]` or `:offset N :limit M` for chunks
5. Store chunks in a `def` — re-reading wastes iterations
6. Your FINAL answer must contain ONLY data from actual outputs — never fabricate values

## Data Format Awareness
- Sandbox results and tool outputs (`list-tools`, registered tool invocations, etc.) are **EDN** (Clojure data) — use `clojure.edn/read-string` to parse, never `parse-json`
- `bash` and `fetch-url` output may be **JSON** — use `parse-json` for those
- When recovering truncated data from files, the format matches the original: EDN for eval/tool results, JSON for CLI output
- Use `(pprint x)` to inspect data structure before choosing a parsing strategy

## TIPs
- `(keys (ns-publics 'user))` — list all defined variable names
- `(format \"%.2f\" 3.14)` — format strings (standard clojure.core)")

(def ^:private usage-artifacts
  "## Live Artifacts — pin what you'll re-reference
Live artifacts are reference material the runtime re-injects into your `## Live Artifacts`
context EVERY turn, so you don't have to re-read or re-quote it. You decide what earns a slot.

### Decide what to add
After you READ something, ask: *will I reference this again across iterations or turns?*
- **YES, and it's a file** (skill SKILL.md, a spec, a schema, a module you keep citing) →
  `(artifact$add :path \"/abs/path\")`. Prefer `:path` over pasting text: only a short preview
  rides the prompt, the full bytes stay on disk, and it RELOADS FRESH each turn (on-disk edits
  show up automatically — the data-connector pattern).
- **YES, but it's a derived note** (a distilled finding, a decision, a checklist you synthesized) →
  `(artifact$add :content \"…\" :name \"…\")`. Inline content rides the prompt verbatim, so keep
  it tight.

### Don't add
- One-off reads you won't revisit — just use the result and move on.
- Huge files — leave them on disk and `(read-file …)` the slice you need on demand.
- Anything already covered by a **system** artifact (CLAUDE.md / AGENTS.md, badged `system`)
  or by **Project Memory** — don't duplicate context that's already seeded.

### Keep the set lean (it costs budget every turn)
- `(artifact$list)` — see what's loaded: `:id :name :origin :source :pinned :size`.
- `(artifact$remove :id \"…\")` — drop an artifact once it's stale or its sub-task is done
  (effective next turn). You can only remove your own; `system` artifacts are fixed.
- `(artifact$pin :id \"…\" :pinned true)` — protect from context-budget eviction. Pin SPARINGLY:
  only what must survive when the context is tight. Everything pinned is weight you always pay.

Rule of thumb: add when re-reading would otherwise repeat across turns; remove the moment it
stops earning its slot.")

;; ============================================================================
;; New topics (added in the generalization)
;; ============================================================================

(def ^:private usage-tool
  "## Tools — the tool-calls channel
Tools are the registered capabilities beyond sandbox builtins (`task$*`, `aws$*`,
`memory$*`, MCP tools, sub-agents). Discover before you call — never guess a
tool id or its args.

### Discover
- `(list-tools)` — grouped index `{:total N :families {family [{:id :description} …]}}`
  (one line per tool, schemas omitted) so the full roster stays scannable; scan a
  family, then drill in with `get-tool-info`. Narrow to a flat DETAILED list (with
  schemas) via `(list-tools :pattern \"^memory\\\\$\")`, `(list-tools :type \"command\")`,
  or `(list-tools :type \"agent\")`.
- `(get-tool-info \"task$run\")` — one tool's full input/output schema before you invoke it.
- `(search \"<keyword>\")` — also returns matching tools alongside files/config/memory.

### Call
- **In the sandbox** (CoAct's preferred channel): registered tools auto-bind as
  kebab-case fns — `(task$run :job-type :bash :command \"ls\")`.
- **Via call-tool** (any channel, incl. unregistered MCP fallback):
  `(call-tool \"<tool-id>\" {:arg \"val\"})`, or
  `(call-tool \"<id>\" {…} :source \"mcp\" :server-name \"<srv>\")`.

### Notes
- tool-id is a string/keyword; tool-args is a plain map. Results are EDN —
  parse with `clojure.edn/read-string`, never `parse-json`.
- Errors surface as `{:error …}` / `{:error-message …}` (permission denied,
  schema mismatch) — read the message and fix the args, don't retry verbatim.
- Prefer the simplest option that works — see `(usage$guide :topic :tool-priority)`.")

(def ^:private usage-code
  "## Code execution — fences, eval, and background tasks
Each ```clojure block runs in the live evaluator and STOPS for feedback. Think
REPL: one expression, read the result, then the next.

### The loop
- **One block per response**, then wait. `def` intermediate results — they persist
  across iterations, so re-fetching/re-parsing wastes turns.
- Inspect before processing: `(pprint x)`, `(keys (ns-publics 'user))` to see what
  you've already `def`'d.
- Backend is set per agent (`:clj-backend` — `:sandbox` SCI by default, `:nrepl`
  for live-runtime agents). You do NOT choose it per fence; a trailing `:nrepl`
  on the fence is a fence error, not a routing hint.

### Long-running work — deferred tasking
- A block that exceeds `:auto-background-timeout-ms` (default 180s) is auto-detached
  and returns a `[pending — task-id=…]` marker. The eval keeps running as a task.
- Check it with `(task$list)` / `(task$detail :task-id \"…\")`, or the live
  `((:pending-tasks-fn rt))` from `(usage$guide :topic :agent-state)`. Do NOT re-emit the block —
  the marker means STILL RUNNING.
- Run things explicitly in the background with `(task$run :job-type :bash …)`.

### Languages
- `clojure` fences eval in-process. `bash`/`python` fences route through the task
  manager. Use `bash` for short shell; write multi-step scripts to `/tmp/x.sh` and
  `(bash \"bash /tmp/x.sh\")`.

See `(usage$guide :topic :sandbox)` for the SCI execution model and `(usage$guide :topic :truncation)` for
handling large output.")

(def ^:private usage-sandbox
  "## SCI code-eval sandbox — execution model
`clojure` fences eval in an embedded SCI interpreter (NOT a full JVM REPL). It is
fast and persistent across iterations, but interop is policy-gated.

### String escaping (SCI reader)
Only `\\n`, `\\t`, `\\\"`, `\\\\` are valid escapes. Regex in `bash` needs DOUBLED
backslashes: `\\\\d`, not `\\d`. For anything with heavy escaping, write the script
to `/tmp/foo.sh` via `write-file` and run it with `(bash \"bash /tmp/foo.sh\")`.

### Calling tools — prefer kwargs
- `(tool :k v)` is the canonical shape. The map form `(tool {:k v})` is accepted
  and equivalent, but it nests two delimiter kinds in one call, and a dropped
  closing brace is the single most common code-block failure. Kwargs has none.
- `call-tool` is the one exception — its target args MUST ride in a map:
  `(call-tool \"<id>\" {:arg v} :server-name \"<srv>\")`, so the target's arg names
  cannot collide with call-tool's own routing kwargs.
- A nested map *value* still needs braces; kwargs removes the outer pair only.

### Aliases / namespaces
- Alias once: `(require '[clojure.string :as str])` — it persists across iterations,
  so `str/join` works in every later block.
- Builtins are pre-bound (`read-file`, `bash`, `grep`, `query$llm`, `memory$*`, …);
  registered tools auto-bind as kebab-case fns. `(keys (ns-publics 'user))` lists
  your `def`'d vars.

### Macros — for a repeated shape that wraps a BODY (session-local)
`defmacro` works here (syntax-quote and auto-gensym `x#` included). Reach for it
only when a plain `defn` or a user tool cannot express the shape — i.e. when the
form must take code you do NOT want evaluated as an argument:
```clojure
(defmacro with-retry [n & body]
  `(loop [i# 1]
     (let [r# (try ~@body (catch Exception e# (if (< i# ~n) ::retry (throw e#))))]
       (if (= r# ::retry) (recur (inc i#)) r#))))

(with-retry 3 (bash :command \"flaky-check\"))   ;; retries up to 3x, then rethrows
```
- **Prefer a tool for anything else.** `tool-agent$create` persists, registers,
  and is discoverable by every agent; a macro is none of those.
- **Session-local.** It survives later iterations of this session (and parallel
  blocks), but NOT a new session or `--resume`. Re-define it if you need it again.
- **Code-channel only** — a macro is not a tool and cannot be called from `tool-calls`.
- **Hygiene:** syntax-quote namespace-resolves symbols, so `` `(let [page …] ~@body) ``
  binds `user/page` and the body's bare `page` will NOT resolve. To deliberately
  bind a name the body can see, unquote it: `~'page`. Check with
  `(macroexpand-1 '(your-macro …))` before relying on it.
- `(keys (ns-publics 'user))` lists them alongside your `def`s;
  `(:macro (meta #'name))` tells them apart.

### Interop policy (`:sandbox-interop`)
- `restricted` (default) — denies `System`/`Runtime`/`ProcessBuilder`/`ClassLoader`.
- `full` — arbitrary interop (container-only); `auto` — relaxes to `full` only when
  a container is detected. Never auto-relaxes unless explicitly set. A blocked
  interop call throws — prefer a builtin/tool over reaching for raw Java.

### Isolation vs. the live runtime
This SCI sandbox is the ISOLATED eval path. For inspecting/patching the running
brainyard JVM, that's the `:nrepl` backend (debug-agent) — see `(usage$guide :topic :nrepl)`.")

;; NOTE: the `:nrepl` guide is COLOCATED with its feature — it is defined and
;; registered in ai.brainyard.agent.common.debug-agent (the live-runtime agent),
;; which also inlines it as its tool-context. This is the registry's intended
;; colocation pattern; see agent.core.usage/defusage.

(def ^:private usage-agents
  "## Specialized sub-agents — delegate the right work
Call a registered agent by name to run multi-step work in its own context:
`(<agent-name> :question \"…\")`. Discover them with `(list-tools :type \"agent\")`
and inspect inputs with `(get-tool-info \"<agent>\")`.

### When to delegate (and to whom)
- **explore-agent** — broad codebase/web discovery: \"where does X live\", naming
  sweeps, finding all call sites. Returns a dossier, doesn't edit.
- **debug-agent** — a fault in (or a question about) the RUNNING brainyard JVM:
  reproduce/probe/patch live via clj-nrepl, then fix the source itself. See
  `(usage$guide :topic :nrepl)`.
- **exec-agent / edit-agent** — make source edits to fulfil a concrete change
  request (often handed a plan or an explore dossier).
- Others surface via `(list-tools :type \"agent\")` — read the description before
  delegating.

### Lifecycle — a dispatched subagent STAYS ALIVE
It is not thrown away when it answers. The result carries `:subagent-id`
(e.g. `\"explore-agent/crimson-parrot-42\"`) — capture it, that is the handle.
- `(agent-registry$ask :id \"…\" :question \"…\")` — follow up on the SAME
  instance, which still sees its own `## Previous Turns`. Prefer this over
  re-dispatching a fresh agent when the follow-up builds on what it already
  found. Reach is fenced: a root may ask a sibling root or its own subagents;
  a subagent may ask only instances IT dispatched, never upward.
- `(agent-registry$list)` — live instances with `:owner`, `:idle-ms`,
  `:answers`, `:last-question`. Add `:session-id` to scope to one session.
- `(agent-registry$detail :id \"…\")` — check a target is idle before asking.
  A growing idle window on a `:running` instance means mid-turn, NOT wedged —
  do not close one on a quiet window alone.
- `(agent-registry$close :id \"…\")` — reclaim when done; cascades to any
  subagents it owned, and refuses while `:running`. Close what you have
  finished with so it is not the thing LRU-evicted when you dispatch next.

### How to delegate well
- Give a crisp `:question` and, when you have it, `:agent-context` (a dossier path,
  issue link, prior notes) so the sub-agent starts grounded.
- Delegate when the sub-task is self-contained and benefits from a fresh context
  budget; do it inline when it's a one-liner you already have the data for.
- Sub-agents run their own loop and return a single answer — don't micro-manage;
  hand off the goal, not the keystrokes.")

;; ============================================================================
;; Registration — order here = listing order. Loading this ns populates the
;; registry in agent.core.usage.
;; ============================================================================

(def ^:private guides
  "Ordered guide specs. `:order` is assigned from position below. `:consult` is
   the one-line 'when to consult' hint surfaced in the system-prompt table."
  [{:topic :llm-query    :title "LLM Sub-Queries"      :category :llm         :guide usage-llm-query
    :consult "Before dispatching a sub-LLM (`query$llm` with `:prompt`/`:prompts`) — picks model, depth, context."}
   {:topic :agents       :title "Specialized Agents"   :category :agents      :scope :user :guide usage-agents
    :consult "Before delegating to a sub-agent, and before re-dispatching one you already have (agent-registry$ask)."}
   {:topic :agent-state  :title "Agent State"          :category :agent       :guide usage-agent-state
    :consult "Before reading/writing `[:agent-state …]` via `context-get`."}
   {:topic :memory       :title "Memory"               :category :memory      :guide usage-memory
    :consult "Before `memory$recall` / `memory$remember` — kinds, layers, scoring."}
   {:topic :todo         :title "Todos"                :category :planning    :guide usage-todo
    :consult "Before any todo-* call — lifecycle, statuses, dependencies."}
   {:topic :plans        :title "Plans"                :category :planning    :guide usage-plans
    :consult "Before any `plan$*` call — slugs, scope, dossier handoff."}
   {:topic :skills       :title "Skills"               :category :skills      :guide usage-skills
    :consult "Before `skill$*` invocations or `skills$*` admin."}
   {:topic :files        :title "File & URL Ops"       :category :files       :guide usage-file-ops
    :consult "Before bulk `read-file` / `write-file` / `update-file`."}
   {:topic :artifacts    :title "Live Artifacts"       :category :artifacts   :guide usage-artifacts
    :consult "Before `artifact$add/remove/pin` — what to pin into Live Artifacts vs. re-read."}
   {:topic :mcp          :title "MCP Tools"            :category :mcp         :guide usage-mcp
    :consult "Before invoking an MCP server tool you haven't called this turn."}
   {:topic :tool         :title "Tools (tool-calls)"   :category :tools       :scope :user :guide usage-tool
    :consult "Before calling an unfamiliar tool — discover the id + schema first."}
   {:topic :tool-priority :title "Tool Priority"       :category :tools       :guide usage-tool-priority
    :consult "When choosing between competing tools (registry vs MCP vs sandbox)."}
   {:topic :discovery    :title "Discovery: search"    :category :discovery   :guide usage-discovery
    :consult "When unsure what's available — pairs with `list-tools`."}
   {:topic :code         :title "Code Execution"       :category :sandbox     :scope :user :guide usage-code
    :consult "Before multi-block / long-running code — the loop, deferred tasking, languages."}
   {:topic :sandbox      :title "SCI Sandbox Model"    :category :sandbox     :scope :user :guide usage-sandbox
    :consult "When SCI escaping/interop bites — string rules, aliases, interop policy."}
   ;; :nrepl is colocated in agent.common.debug-agent (registered there).
   {:topic :truncation   :title "Output Truncation"   :category :sandbox     :guide usage-output-truncation
    :consult "When a tool result is going to be huge."}
   {:topic :final        :title "FINAL Rules"          :category :sandbox     :guide usage-final-rules
    :consult "Before emitting the FINAL answer — termination contract."}
   {:topic :feedback     :title "User Feedback"        :category :interaction :guide usage-user-feedback
    :consult "Before asking the user a clarifying question."}
   {:topic :rules        :title "Rules & Tips"         :category :sandbox     :guide usage-rules-and-tips
    :consult "Catch-all rules and tips for sandbox/agent etiquette."}])

;; This centralized batch is the built-in :system set by default — its guides
;; are always-on in the system-prompt consult-table. Extended topics that opt
;; out (carrying :scope :user below) are reachable on-demand via `(usage$guide)` + the
;; JIT nudge, but kept out of the always-on prompt to save tokens.
(doseq [[i g] (map-indexed vector guides)]
  (usage/register-usage! (:topic g) (assoc g :order i :scope (or (:scope g) :system))))
