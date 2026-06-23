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
- `(list-skills)` / `(find-skills \"q\")` — skills (brainyard + claude + agents)
- `(list-plans)` — plans
- `(mcp$server :op \"list\")` — MCP servers")

(def ^:private usage-tool-priority
  "## Tool Priority (use the simplest available option)
1. **Sandbox builtins** (FIRST) — direct functions listed above
2. **Registered tools** — `(<tool-id> {:arg \"val\"})` for `task$*`, `aws$*`, etc. (auto-bound kebab-case symbols)
3. **MCP tools** — `(mcp$server :op \"list\")`; native MCP tools register as `mcp$<server>$<tool>` and call directly: `(mcp$<server>$<tool> {:arg \"val\"})`
4. **Skills** — `(list-skills)`, `(read-skill \"name\")`, then `bash`
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
  "## Task Planning (Todo List)
Ephemeral single-session tracking for multi-step tasks.

**When to create a todo:** ALWAYS create a todo when a task requires 3+ distinct steps. Do this BEFORE starting work — plan first, then execute.

### API
- `(create-todo \"goal\" [{:description \"step 1\"} {:description \"step 2\" :independent true}])` — create task list upfront
- `(mark-todo-done task-id \"result\")` — mark a task as completed with its result
- `(todo-status)` — progress summary; `(get-todo)` — full list; `(get-todo-pending)` — pending tasks
- `(get-todo-independent-pending)` — pending tasks safe for parallel execution

### Parallel Execution
Mark tasks `:independent true` when they have NO data dependencies on other tasks. Then use `query$llm` with `:prompts` (a vector) to execute independent tasks concurrently.

**Important:** Each batched sub-query runs in an ISOLATED conversation — it does NOT see prior RLM history. You MUST provide sufficient sub-context (gathered data, file contents, search results) so each sub-query can answer independently.
```clojure
;; Pattern: execute independent todo tasks in parallel with sub-context
(let [tasks   (get-todo-independent-pending)
      context (str \"Relevant data:\\n\" (pr-str gathered-data))  ;; provide enough context!
      prompts (mapv #(str \"Execute: \" (:description %)) tasks)
      results (:results (query$llm :prompts prompts :sub-context context))]  ;; context shared
  (doseq [[task result] (map vector tasks results)]
    (mark-todo-done (:id task) result)))
```

### Progress Discipline
- Check `(todo-status)` after completing tasks to maintain awareness of overall progress
- Do NOT lose sight of the original goal — always relate current work back to the todo
- After all tasks are done, synthesize results into a final answer addressing the original goal")

(def ^:private usage-plans
  "## Plan Management (Persistent Plans)
Plans persist as markdown files in `.brainyard/plans/` across sessions.
Each plan gets a unique random 3-word slug (e.g., \"coral-penguin-42\") and an absolute `:file-path`.
Hybrid format: free-form `:body` (context, approach, risks — whatever you want) + structured `:steps` (machine-tracked progress).

### API
- `(create-plan \"title\" \"body markdown\" [{:description \"step 1\"} {:description \"step 2\" :independent true}])` — options: `:scope`. Returns map with `:slug`, `:file-path`, `:body`, `:steps`.
- `(list-plans)` — options: `:scope`, `:status`; `(read-plan \"slug\")` — full details with `:body`, `:steps`, `:file-path`
- `(update-plan-body \"slug\" \"new body markdown\")` — replace the free-form body (context, risks, etc.)
- `(update-plan-step \"slug\" step-idx \"result\")` — mark step done (0-based)
- `(add-plan-step \"slug\" \"new step\")` — options: `:independent`, `:after-idx`
- `(plan-status \"slug\")` — progress; `(complete-plan \"slug\")` / `(abandon-plan \"slug\")`
- `(reopen-plan \"slug\")` — reset all steps to pending for re-running
- `(reset-plan-step \"slug\" step-idx)` — reset a single step; `(delete-plan \"slug\")`; `(plan-exists? \"slug\")`

Resuming: `(read-plan \"slug\")` shows progress, continue from where you left off.

## Plan Mode (Interactive Planning Workflow)
Enter plan mode when the user's message contains \"plan\", \"make a plan\", \"plan mode\", \"plan first\",
or similar planning intent. Do NOT enter plan mode for simple questions.

### Phase 1: Research (1-3 iterations)
Explore BEFORE creating the plan:
- Use `bash`, `read-file`, `grep`, `search` to gather facts (use `bash`'s
  `find`/`ls`/`tree` for glob/tree views)
- Note file paths, function signatures, data shapes, constraints, CLI commands
- This research becomes the foundation of your plan body

### Phase 2: Create the plan
```clojure
(def p (create-plan \"Title\"
  \"## Context\\nWhat problem we're solving and why\\n\\n## Findings\\nKey facts from research:\\n- file X at path Y has function Z\\n- data shape: {:key type}\\n- exact CLI commands to run\\n\\n## Approach\\nChosen strategy with rationale\\n\\n## Risks\\nWhat could go wrong and mitigations\"
  [{:description \"step 1\"} {:description \"step 2\" :independent true}]))
```
The body is NOT throwaway — it is re-read via `(read-plan slug)` during execution as working context.
Write it as if briefing a colleague: include exact paths, commands, data examples, edge cases.
**Always `def p` the result** — you'll need `(:slug p)` and `(:file-path p)` later.

### Phase 3: Present for review
```clojure
(def step-list (clojure.string/join \"\\n\" (map-indexed (fn [i s] (str (inc i) \". \" (:description s))) (:steps p))))
(get-user-feedback
  (str \"## \" (:title p) \"\\n\\n\" (:body p) \"\\n\\n### Steps\\n\" step-list \"\\n\\n📄 \" (:file-path p))
  [{:label \"Go\" :description \"Execute this plan\"}
   {:label \"Modify\" :description \"I want to change something\" :free-input true}
   {:label \"Cancel\" :description \"Abandon this plan\"}])
```
If `get-user-feedback` times out: the plan is saved — use `(:slug p)` (already def'd) to re-present. Do NOT re-discover via `list-plans`.

### Phase 4: Execute
- **Go** → Start execution. `(read-plan (:slug p))` to reload body context.
- **Modify** → Update plan (`update-plan-body` / `add-plan-step`), re-present (repeat phase 3)
- **Cancel** → In the SAME code block: `(abandon-plan (:slug p))` then `(FINAL \"Plan cancelled.\")`. Do NOT continue working — stop immediately.

Execution rules:
- **FINAL > plan bookkeeping**: If you have all data needed for the answer, write FINAL immediately. Don't waste iterations on `complete-plan` — delivering the answer is the priority.
- **Check before completing**: `(plan-status slug)` shows `:next-step` with `:description` — use this to find the correct pending step index instead of guessing.
- **Trust `def`'d variables**: Data stored via `def` persists across iterations. Do NOT re-read files or re-parse JSON that's already in a variable.
- **Mark steps as you go**: Call `(update-plan-step slug idx \"result\")` right after completing each step's work — don't batch step updates at the end.
- **Step indices are 0-based**: A 5-step plan has indices 0-4. Use `(plan-status slug)` if unsure which step is pending.

### On [CONTINUATION] (resuming after iteration limit)
Sandbox variables (`def`'d values) survive across continuation — the sandbox is NOT reset.
But your LLM context is reset — you don't remember what you computed. On the FIRST iteration:
1. Check what you already have: `(keys (ns-publics 'user))` — shows all def'd variables from prior iterations
2. Review what was done: `(context-get [:previous-turns])` — prior turn summaries show questions asked and answers given
3. `(list-plans :status :in-progress)` — find the active plan slug
4. `(plan-status slug)` — see which steps are done vs pending
5. Resume from the next pending step — do NOT restart from step 1, do NOT re-fetch data that's already in variables")

(def ^:private usage-skills
  "## Skill Management
Three skill types:
- `:brainyard` — local FS under `.brainyard/skills/{name}/` (SKILL.md + optional scripts/ and resources/). Fully managed by the agent.
- `:claude`    — `~/.claude/skills/`, installed via `npx skills add --target claude`.
- `:agents`    — `~/.agents/skills/`, installed via `npx skills add`.

### Unified (auto-detect when `:type` omitted)
- `(list-skills)` — all types; options: `:type (:brainyard|:claude|:agents)`, `:scope` (brainyard only)
- `(read-skill \"name\")` — SKILL.md + metadata; options: `:type`, `:scope`
- `(find-skills \"query\")` — search by name/description; options: `:type`
- `(remove-skill \"name\")` — delete; options: `:type`, `:scope`

### Brainyard only
- `(create-skill \"my-skill\" \"# Instructions...\")` — new skill; options: `:scope (:project|:user)`, `:scripts {...}`, `:resources {...}`
- `(update-skill \"name\" :content \"...\")` — edit SKILL.md or extras; options: `:content`, `:scope`, `:scripts`, `:resources`

### CLI only (:claude / :agents)
- `(install-skill \"owner/repo\")` — install from registry; options: `:type (:claude|:agents, default :agents)`
- `(sync-skills)` — update all CLI skills; options: `:type`")

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
by name, e.g. `(explore-agent {:question \"…\"})`.)

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
  `(artifact$add {:path \"/abs/path\"})`. Prefer `:path` over pasting text: only a short preview
  rides the prompt, the full bytes stay on disk, and it RELOADS FRESH each turn (on-disk edits
  show up automatically — the data-connector pattern).
- **YES, but it's a derived note** (a distilled finding, a decision, a checklist you synthesized) →
  `(artifact$add {:content \"…\" :name \"…\"})`. Inline content rides the prompt verbatim, so keep
  it tight.

### Don't add
- One-off reads you won't revisit — just use the result and move on.
- Huge files — leave them on disk and `(read-file …)` the slice you need on demand.
- Anything already covered by a **system** artifact (CLAUDE.md / AGENTS.md, badged `system`)
  or by **Project Memory** — don't duplicate context that's already seeded.

### Keep the set lean (it costs budget every turn)
- `(artifact$list)` — see what's loaded: `:id :name :origin :source :pinned :size`.
- `(artifact$remove {:id \"…\"})` — drop an artifact once it's stale or its sub-task is done
  (effective next turn). You can only remove your own; `system` artifacts are fixed.
- `(artifact$pin {:id \"…\" :pinned true})` — protect from context-budget eviction. Pin SPARINGLY:
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
  kebab-case fns — `(task$run {:job-type :bash :command \"ls\"})`.
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
- A block that exceeds `:auto-background-timeout-ms` (default 30s) is auto-detached
  and returns a `[pending — task-id=…]` marker. The eval keeps running as a task.
- Check it with `(task$list)` / `(task$detail {:task-id \"…\"})`, or the live
  `((:pending-tasks-fn rt))` from `(usage$guide :topic :agent-state)`. Do NOT re-emit the block —
  the marker means STILL RUNNING.
- Run things explicitly in the background with `(task$run {:job-type :bash …})`.

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

### Aliases / namespaces
- No `str/` alias — call `clojure.string/join`, not `str/join`. Fully-qualify.
- Builtins are pre-bound (`read-file`, `bash`, `grep`, `query$llm`, `memory$*`, …);
  registered tools auto-bind as kebab-case fns. `(keys (ns-publics 'user))` lists
  your `def`'d vars.

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
`(<agent-name> {:question \"…\"})`. Discover them with `(list-tools :type \"agent\")`
and inspect inputs with `(get-tool-info \"<agent>\")`.

### When to delegate (and to whom)
- **explore-agent** — broad codebase/web discovery: \"where does X live\", naming
  sweeps, finding all call sites. Returns a dossier, doesn't edit.
- **debug-agent** — a fault in (or a question about) the RUNNING brainyard JVM:
  reproduce/probe/patch live via clj-nrepl, then fix the source itself. See
  `(usage$guide :topic :nrepl)`.
- **exec-agent / update-agent** — make source edits to fulfil a concrete change
  request (often handed a plan or an explore dossier).
- Others surface via `(list-tools :type \"agent\")` — read the description before
  delegating.

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
    :consult "Before delegating to a sub-agent (explore/debug/exec/…) — who to pick and how to hand off."}
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
