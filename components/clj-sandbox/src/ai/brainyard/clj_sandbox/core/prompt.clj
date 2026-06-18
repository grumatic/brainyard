;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.core.prompt
  "Prompt construction, code extraction, and system prompt assembly for RLM.

   Contains:
   - Shared sandbox environment description (standalone + agent mode)
   - Standalone RLM system prompt builder
   - Agent-mode system prompt builder (5-section structure)
   - Code extraction from LLM responses
   - Message building for the RLM conversation loop
   - Config helpers (model defaults)"
  (:require [clojure.string :as str]))

(def ^:dynamic *max-feedback-chars*
  "Maximum chars for stdout in basic feedback. Bind to override (default 100KB)."
  100000)

;; ============================================================================
;; Sandbox Environment — shared base + mode-specific rules
;; ============================================================================

;; --- Decomposed sandbox environment subsections ---
;; These are split so they can be individually included in system prompt or as on-demand usage-* bindings.

(def ^:private execution-model-core
  "## Execution Model
Your code runs in a **sandboxed Clojure interpreter** (SCI). Each ```clojure block is evaluated,
and the results (return value, stdout, or error) are sent back for the next iteration.
- **State persists**: `def` variables survive across iterations.
- **Captured output**: `println`/`pprint` output is captured and returned to you.
- **Errors are non-fatal**: Exceptions show the error message; sandbox state is preserved.
- **No interop**: System, Runtime, ProcessBuilder, ClassLoader access denied.
- **Timeout**: 30s per code block.")

(def ^:private available-clojure-guide
  "## Available Clojure
String: str, subs, count, clojure.string/split, clojure.string/join, clojure.string/includes?,
        clojure.string/replace, clojure.string/trim, clojure.string/lower-case, clojure.string/upper-case
NOTE: No `str` alias — always use full `clojure.string/` prefix (e.g. `clojure.string/join` not `str/join`)
Regex: re-find, re-seq, re-matches, re-pattern
Collections: first, rest, last, nth, take, drop, map, filter, reduce, mapv, filterv,
             sort, sort-by, group-by, into, conj, assoc, dissoc, get, get-in, update,
             merge, keys, vals, frequencies, distinct, partition, partition-by, concat,
             flatten, interleave, zipmap, range, repeat, repeatedly, apply
Math: +, -, *, /, mod, max, min, inc, dec, quot, rem, Math/abs, Math/ceil, Math/floor
Random: rand, rand-int, rand-nth, shuffle
Defs: def, defn, let, fn, loop/recur, cond, case, if, when, when-let, if-let, do
Logic: and, or, not, true?, false?, nil?, some?, some, every?, not-every?, not-any?
Types: str, int, long, double, keyword, name, symbol, type, string?, number?, keyword?, coll?, map?, vector?, seq?, set?
Format: format — e.g. (format \"%.2f\" 3.14) → \"3.14\", (format \"%s: %d\" name count)
I/O: print, println, pr, prn, printf (all captured — output returned to you)
JSON: (parse-json s) — parse JSON string to Clojure data (string keys by default)
      (to-json v) — convert Clojure data to JSON string
Numbers: Integer/parseInt, Long/parseLong, Double/parseDouble, Float/parseFloat — parse strings to numbers
Time: java.time.Instant, java.time.Duration, java.time.LocalDate, java.time.LocalDateTime,
      java.time.LocalTime, java.time.ZonedDateTime, java.time.ZoneId, java.time.format.DateTimeFormatter

## Namespace Discovery & Variable Tracking
- `(keys (ns-publics 'clojure.set))` — list functions in a specific namespace
- Available namespaces: clojure.core, clojure.string, clojure.set, clojure.walk, clojure.edn
- Use `(pprint x)` for pretty-printing data structures
- Variables persist across iterations — use `def` to name results
- Use `clojure.set` for set operations (union, intersection, difference, subset?, etc.)
- Use `clojure.walk` for tree transformations (postwalk, prewalk, keywordize-keys, stringify-keys)
- Use `clojure.edn` for safe EDN parsing (read-string)")

(def ^:private parallel-execution-guide
  "## Parallel Execution
To run independent computations concurrently, use the `parallel-code` output field
with multiple code blocks separated by `;;---PARALLEL---` delimiter.
Each block runs in a **forked sandbox** — after all blocks complete, new `def`s are merged back:
- **Defs are merged** — variables defined in parallel blocks ARE available in subsequent iterations
  (last-block-wins for conflicts; pre-existing parent vars are not overwritten)
- Each block has its own stdout capture — results returned as a vector of outputs
- FINAL is NOT allowed in parallel blocks — use sequential `code` to finalize
- Max 10 blocks per iteration
- Use for: independent tool calls, batch data transforms, decomposable sub-tasks
- Do NOT use for: sequential operations where block B depends on block A's result
- Do NOT call `get-user-feedback` in parallel blocks — it requires serial terminal interaction.
  Place feedback calls in the next sequential `code` iteration instead.

### Pattern: parallel compute → sequential combine
Define results with `def` in parallel blocks — they are merged back into the parent sandbox.
In the next iteration, use those variables directly in sequential `code`:

  ;; Iteration N (parallel-code): run independent queries, DEF results
  (def sum-a (:result (query$llm \"sum of 1 to 100\")))
  ;;---PARALLEL---
  (def sum-b (:result (query$llm \"sum of 101 to 200\")))

  ;; Iteration N+1 (sequential code): use parallel-defined vars directly, FINAL
  ;; sum-a and sum-b are available from the previous parallel iteration.
  (FINAL (str \"Total: \" (+ (parse-long sum-a) (parse-long sum-b))))")

(def ^:private sci-string-restrictions
  "## SCI Sandbox String Restrictions — CRITICAL
Your code runs in SCI (Small Clojure Interpreter), which has stricter string parsing than standard Clojure:

1. **Backslash `\\` is ONLY valid for standard escapes**: `\\n` (newline), `\\t` (tab), `\\\"` (quote), `\\\\` (literal backslash)
   - Shell line-continuation `\\` at end of line is **NOT SUPPORTED** — causes \"Unsupported escape character\" error
   - **NEVER write multi-line shell commands with `\\` continuation in `bash` strings**

2. **Regex in bash/sed/awk/grep — DOUBLE all backslashes**:
   - `(bash \"grep '\\\\d+' file\")` ← CORRECT (`\\\\d` = literal `\\d` for the shell)
   - `(bash \"grep '\\d+' file\")` ← WRONG (`\\d` is invalid in SCI)
   - Same for `\\\\s`, `\\\\w`, `\\\\S`, `\\\\W`, `\\\\b` (regex), `\\\\[`, `\\\\(`, etc.

3. **For complex scripts with many escapes**: write the script to a temp file and run it:
   `(write-file \"/tmp/foo.sh\" \"#!/bin/bash\\n...\")` then `(bash \"bash /tmp/foo.sh\")`.

4. **For multi-line shell commands**: Put everything on ONE line, or use the temp-file pattern above.

5. **For JSON parsing**: Use `parse-json` (built-in, no require needed)")

;; Legacy combined form for backward compat (standalone RLM mode)
(def ^:private codeact-block-rule
  "## CODE BLOCKS REQUIRED
Write Clojure code in ```clojure fences to make progress. Each code block is evaluated in the sandbox.
You may write multiple ```clojure blocks per response — they are evaluated sequentially.
When done, respond with text only (no code blocks) — your text becomes the final answer.
NEVER use XML tool-calling syntax like <function_calls>, <invoke>, or <parameter> tags — those are NOT supported.
ALL actions must be Clojure code in ```clojure fences. For shell commands, use: (bash \"command here\")")

;; ============================================================================
;; Code Extraction
;; ============================================================================

(defn extract-code-blocks
  "Extract ```clojure ... ``` fenced blocks from LLM response text.
   Returns vector with at most ONE code string (the first block).
   If multiple blocks found, only the first is returned — the rest are ignored
   because executing multiple blocks leads to noisy errors and unpredictable behavior.
   When blocks are dropped, the returned vector has metadata:
     {:dropped-count N :total-count M}
   Also matches ```clj blocks."
  [text]
  (if (str/blank? text)
    []
    (let [pattern #"(?m)^(`{3,})(?:clojure|clj)[^\n]*\n([\s\S]*?\n)\1[ \t]*$"
          matches (re-seq pattern text)
          all-blocks (vec (distinct (map #(nth % 2) matches)))]
      (if (<= (count all-blocks) 1)
        all-blocks
        (with-meta [(first all-blocks)]
          {:dropped-count (dec (count all-blocks))
           :total-count (count all-blocks)})))))

(defn extract-markdown-block
  "Extract a ```markdown/```md/```text block from LLM response.
   Used as a fallback when the LLM writes a markdown answer block instead of
   calling (FINAL \"...\") — avoids EOF parse errors with complex formatting.
   Returns the content string or nil if not found."
  [text]
  (when-not (str/blank? text)
    (let [pattern #"```(?:markdown|md|text)\s*\n([\s\S]*?)```"
          match (re-find pattern text)]
      (when match (second match)))))

(defn extract-xml-tool-calls
  "Best-effort extraction of run_bash commands from XML <function_calls> format.
   When the LLM outputs XML tool-calling syntax instead of Clojure code blocks,
   this extracts the shell commands and wraps them in (bash ...) calls.
   Returns a Clojure code string, or nil if no extractable commands found."
  [text]
  (when (re-find #"<function_calls>" text)
    (let [commands (->> (re-seq #"<invoke name=\"(?:bash|run.?bash)\">\s*<parameter name=\"command\">([\s\S]*?)</parameter>" text)
                        (map second)
                        (map str/trim)
                        seq)]
      (when commands
        (str/join "\n"
                  (map-indexed
                   (fn [i cmd]
              ;; Fix backslash line continuations that SCI can't handle
                     (let [clean-cmd (str/replace cmd #"\\\n\s*" " ")]
                       (str "(def result" (when (pos? i) (str "-" (inc i)))
                            " (bash " (pr-str clean-cmd) "))\n"
                            "(pprint result" (when (pos? i) (str "-" (inc i))) ")")))
                   commands))))))

;; ============================================================================
;; Message Building (standalone RLM loop)
;; ============================================================================

(defn build-user-message
  "Build the first user message for any mode.
   Options:
     :mode            - :raw (default), :structured
     :briefing        - Pre-loaded context briefing (agent modes)
     :iterations-text - Pre-formatted iteration history (from build-iterations-text)"
  [query & {:keys [mode briefing iterations-text] :or {mode :raw}}]
  {:role "user"
   :content
   (if (= mode :structured)
     (str "Query: " query
          "\n\n" briefing
          "\nUse the function directory and data directory above. Call `(usage :topic)` (e.g. `(usage :plans)`, `(usage :llm-query)`) for detailed guides; `(usage)` lists topics."
          "\n\nWrite Clojure code to answer this query.")
     ;; :raw (default — also used by standalone completion)
     (str "Query: " query
          (when briefing
            (str "\n\n" briefing
                 "\nSandbox functions and context accessors available per directory above."))
          (when iterations-text
            (str "\n\n" iterations-text))
          "\n\nWrite code to accomplish this task. You can use ```clojure, ```python, or ```bash blocks."))})

(defn build-initial-user-message
  "Build the first user message containing the query.
   Backward-compatible wrapper around build-user-message."
  [query]
  (build-user-message query :mode :raw))

(defn build-feedback-message
  "Build a user message from REPL evaluation results.

   eval-results is a vector of {:result :output :error :code} maps,
   one per code block evaluated in the iteration."
  [eval-results]
  (let [parts (map-indexed
               (fn [i {:keys [result output error]}]
                 (let [block-header (if (= 1 (count eval-results))
                                      "REPL Output:"
                                      (str "Block " (inc i) " Output:"))
                       sections (cond-> []
                                  (and output (not (str/blank? output)))
                                  (conj (str "stdout:\n" (subs output 0 (min *max-feedback-chars* (count output)))))

                                  error
                                  (conj (str "Error: " error))

                                  (and (nil? error) (some? result))
                                  (conj (str "=> " (pr-str result))))]
                   (str block-header "\n" (str/join "\n" sections))))
               eval-results)]
    {:role "user"
     :content (str/join "\n\n" parts)}))

;; ============================================================================
;; Modular Prompt Sections (shared by standalone + agent modes)
;; ============================================================================

;; Context access — exploration pattern + all accessor docs
(def context-access-prompt
  "## Context Access (SELECTIVE RETRIEVAL)
There is NO `context` variable. Context is available ONLY through these accessor functions.
**Do NOT use `context` — it is not bound.** Start with `(context-index)` to explore.

### Step 1: Discover structure
- `(context-index)` — ALWAYS call this first! Shows keys, types, sizes, and nested structure
- `(context-keys [:path :to :key])` — list keys/indices at any nesting level
  `(context-keys [])` — top-level keys; `(context-keys [:data 0])` — keys of first element

### Step 2: Sample and inspect
- `(context-sample [:path] 3)` — sample N items from a collection at path
  Options: `:strategy :random` (default) | `:evenly-spaced` | `:first` | `:last`
- `(context-search \"keyword\")` — search ALL string values recursively, returns paths + matches
  Options: `:limit 10` (default), `:case-sensitive false` (default)

### Step 3: Retrieve specific data
- `(context-get [:path :to :data])` — fetch value at path (auto-truncated for safety)
  Options: `:raw true` (no truncation), `:limit 50` (collection cap), `:str-limit 5000` (string cap)
- `(pprint (context-get [:data 0]))` — print a specific item

### Exploration pattern
```clojure
(context-index)                          ;; 1. See what's available
(context-keys [:interesting-key])        ;; 2. Drill into structure
(context-sample [:interesting-key] 3)    ;; 3. See example items
(pprint (context-get [:interesting-key 0 :field]))  ;; 4. Get specific data
```

### Previous turns & memory
Use `context-get` (path must be a vector) to retrieve previous turns and memory:
- `(context-get [:previous-turns])` — prior turn data (question + iterations + answer)
- `(context-search \"keyword\")` — search ALL context values recursively
- `(context-get [:recalled-memory])` — recalled memory (may be nil)

**CRITICAL — context accessor results contain quotes**: These return Clojure data with embedded strings.
NEVER put the result directly into a FINAL string literal. Assign to a variable first:
```clojure
;; BAD — will cause EOF parse error:
(FINAL (str \"Previous: \" (context-get [:previous-turns])))
;; OK — assign to variable, format for display:
(def prev (context-get [:previous-turns]))
(FINAL (str \"Q: \" (:question (first prev)) \"\\nA: \" (:answer (first prev))))
```")

;; ============================================================================
;; Decomposed Usage Guide Sections (supplement auto-generated function docs)
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
- `(search :memory \"query\" :limit 10)` — alternate cross-layer recall via the search dispatcher
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

;; ============================================================================
;; Unified System Prompt Builder
;; ============================================================================

;; --- Slim system prompt sections (for agent modes with context-briefing) ---

(def ^:private critical-rules-structured
  "## Critical Rules
- **FINAL must be ALONE** — no `def`, `let`, or other code alongside. Assign to var first, then `(FINAL var)`.
- **Never embed fn calls in FINAL**: `(FINAL (str (pprint x)))` → EOF error. Always: `(def v (with-out-str (pprint x)))` then `(FINAL v)`.
- **SCI string escaping**: Only `\\n`, `\\t`, `\\\"`, `\\\\` are valid. Regex in bash needs doubled backslashes: `\\\\d` not `\\d`. For complex scripts: write to /tmp/foo.sh via `write-file` and run with `(bash \"bash /tmp/foo.sh\")`.
- **One ```clojure block per response**, then STOP. Think REPL: one expression, read result, next expression.
- **No XML tool-calling**: Never use `<function_calls>`, `<invoke>`, `<parameter>` — only ```clojure fences.
- **No `str/` alias**: Use `clojure.string/join`, not `str/join`.
- Call `(usage :topic)` for detailed guides on any capability — e.g. `(usage :plans)`, `(usage :skills)`, `(usage :llm-query)`, `(usage :files)`. `(usage)` lists topics.")

(def ^:private critical-rules-raw
  "## Critical Rules
- **SCI string escaping**: Only `\\n`, `\\t`, `\\\"`, `\\\\` are valid. Regex in bash needs doubled backslashes: `\\\\d` not `\\d`. For complex scripts: write to /tmp/foo.sh via `write-file` and run with `(bash \"bash /tmp/foo.sh\")`.
- **One code block per response**: brief reasoning + ONE fenced block (```clojure, ```bash, or ```python). Wait for feedback.
- **Final answer**: Rich markdown text ONLY, no code blocks. Or call `(FINAL \"answer\")` in Clojure.
- **No XML tool-calling**: Never use `<function_calls>`, `<invoke>`, `<parameter>` — only fenced code blocks.
- **No `str/` alias**: Use `clojure.string/join`, not `str/join`.
- Call `(usage :topic)` for detailed guides on any capability — e.g. `(usage :plans)`, `(usage :skills)`, `(usage :llm-query)`, `(usage :files)`. `(usage)` lists topics.")

(def ^:private context-discovery
  "## Context & Functions
The **Function Directory** below lists all sandbox functions grouped by category (signatures).
Your first user message contains a **Context Briefing** with:
- **Data Directory** — what's accessible via `context-get` (conversation, previous turns, memory, agent state)
- **Active State** — tool/skill/MCP counts, in-progress plans, pending todos
- **Instructions** — project and user instructions

Start working from the function directory and briefing. Call `(usage :topic)` for detailed usage guides; `(usage)` lists topics.
Topics: :truncation, :final, :discovery, :tool-priority, :agent-state, :mcp, :feedback, :memory, :todo, :plans, :skills, :files, :llm-query, :rules.")

(defn- condensed-footer
  "Condensed footer for slim system prompt."
  [mode]
  (if (= mode :raw)
    "## Workflow
1. Read query + context briefing. On `[CONTINUATION]`: check `(keys (ns-publics 'user))` and `(list-plans :status :in-progress)`, resume.
2. **Reuse previous findings**: When a question relates to a previous turn, use the data from Conversation History — don't re-search or re-fetch.
3. Write reasoning + ONE code block. Wait for feedback.
4. Read feedback. Need more → another code block. Have everything → final answer (text only, no code).

## Answer Format
- Rich markdown (headers, bullets, tables). Never raw data dumps.
- If results already contain the answer, write it immediately — don't re-fetch.

## Efficiency
- Simple questions: answer in iteration 1.
- `def` intermediate results — re-fetching wastes iterations.
- Batch CLI workflows into `/tmp/script.sh`.
- For large data: use `query$llm :prompts` (chunk → batch → aggregate)."

    ;; :structured
    "## Workflow
1. On `[CONTINUATION]`: sandbox variables alive — `(keys (ns-publics 'user))`, `(list-plans :status :in-progress)`, resume.
2. Briefing is pre-loaded — start working directly. Use `context-get`/`context-search` only when you need details beyond the briefing.
3. If previous-turns-count > 0: `(context-get [:previous-turns])` for earlier turn data.
4. **Reuse previous findings**: When a question relates to a previous turn, use the data from Conversation History — don't re-search or re-fetch.
5. `(pprint result)` on tool results before processing.
6. Call FINAL as soon as you have the answer.

## Answer Format
- **Never return raw maps, EDN, or JSON.** Format as rich markdown.
- Build answer in var → `(println answer)` to verify → `(FINAL answer)`.
- For complex markdown: use a ```markdown block instead of FINAL.

## Efficiency
- Simple questions: FINAL in iteration 1 — no tools needed.
- Prefer `bash` over `task$run :job-type :bash` for short commands. Batch into `/tmp/script.sh` for multi-step.
- `def` intermediate results. Briefing pre-loads tools/skills — don't re-list.
- For large data: MapReduce with `query$llm :prompts`."))

(defn- format-brainyard-instructions
  "Format a {:user-instructions :project-instructions} map as a markdown
   '## Brainyard Instructions' section. Returns nil when both are blank."
  [{:keys [user-instructions project-instructions]}]
  (when (or (and project-instructions (not (str/blank? project-instructions)))
            (and user-instructions (not (str/blank? user-instructions))))
    (let [parts (cond-> ["## Brainyard Instructions"
                         "These instructions MUST be followed. Use `(search \"<keyword>\")` to discover related config files."]
                  (and project-instructions (not (str/blank? project-instructions)))
                  (conj (str "### Project (.brainyard/BRAINYARD.md)\n" project-instructions))
                  (and user-instructions (not (str/blank? user-instructions)))
                  (conj (str "### User (~/.brainyard/BRAINYARD.md)\n" user-instructions)))]
      (str/join "\n\n" parts))))

(defn build-system-prompt
  "Build a lean system prompt (~1000-1500 tokens) for both standalone and agent modes.

   Sections:
   1) Role + execution model
   2) Critical rules (FINAL, SCI, one-block, no-XML)
   3) Context discovery (function directory lives in this prompt, data map in briefing)
   4) Function directory (compact signatures, when :function-directory is provided)
   5) Brainyard instructions (when :brainyard-instructions is provided)
   6) Condensed footer (workflow, answer format, efficiency)
   7) Optional: instruction, agent-context, tool-context

   Detailed guides are available on-demand via `(usage :topic)` in the sandbox.

   Options:
     :mode                   - :structured or :raw (default)
     :max-iterations         - Loop limit (default 20)
     :instruction            - Agent-specific instructions
     :agent-context          - Agent behavioral context
     :tool-context           - Tool usage guide
     :function-directory     - Compact function signatures string (from build-function-directory).
                               Rendered as a '## Function Directory' section when non-blank.
     :brainyard-instructions - Map {:user-instructions :project-instructions} loaded
                               via config/load-brainyard-instructions. Rendered as a
                               '## Brainyard Instructions' section when either side is non-blank.
     :return-breakdown?      - When true, returns {:content str :token-breakdown map}"
  [& {:keys [mode max-iterations instruction agent-context tool-context
             function-directory brainyard-instructions return-breakdown?]
      :or {mode :raw max-iterations 20}}]
  (let [brainyard-section (when brainyard-instructions
                            (format-brainyard-instructions brainyard-instructions))
        sections
        (cond->
         {:role-and-execution
          (str (if (= mode :raw)
                 "You are an AI agent that accomplishes tasks by writing and executing code."
                 "You are an AI agent that answers queries by writing Clojure code in a REPL sandbox.")
               "\n\n" execution-model-core)
          :critical-rules
          (if (= mode :raw)
            critical-rules-raw
            critical-rules-structured)
          :context-discovery context-discovery
          :footer (condensed-footer mode)}
          (and function-directory
               (not (str/blank? function-directory)))
          (assoc :function-directory
                 (str "## Function Directory\n" function-directory))
          brainyard-section
          (assoc :brainyard-instructions brainyard-section)
          instruction    (assoc :instruction (str "## Instructions\n" instruction))
          agent-context  (assoc :agent-context (str "## Agent Context\n" agent-context))
          tool-context   (assoc :tool-context (str "## Tool Usage Guide\n" tool-context)))

        section-order [:role-and-execution :critical-rules :context-discovery
                       :function-directory :brainyard-instructions
                       :instruction :agent-context :tool-context :footer]
        content (str/join "\n\n" (keep #(get sections %) section-order))]
    (if return-breakdown?
      {:content content
       :token-breakdown ((requiring-resolve 'ai.brainyard.clj-llm.core.usage/build-token-breakdown) sections)}
      content)))

;; ============================================================================
;; CodeAct Prompt Helpers (raw mode)
;; ============================================================================

(def ^:private category-order
  "Display order for function categories in generated docs."
  [:core :llm :query :discovery :tools :shell :files
   :todo :plan :planning :skills :skill :react-skill
   :memory :interaction :debugging
   :mcp :aws :task
   :agent-session :agent-registry :agent-knowledge :agent-runtime :agents
   :email-command :slack-command :rag-command :chart-command :query-command
   :usage])

(def ^:private category-names
  "Human-readable display names for function categories.
   Includes the singular `$`-prefix fallbacks emitted by category-from-meta
   (e.g. `skill$foo` → :skill, `query$llm` → :query)."
  {:core "Core" :llm "LLM & Sub-Queries" :query "Sub-LLM / Subagent Queries"
   :discovery "Discovery"
   :tools "Tool Invocation" :shell "Shell Execution" :files "File & URL Operations"
   :todo "Todos" :plan "Plans" :planning "Planning"
   :skills "Skills (admin)" :skill "Skills (invocations)" :react-skill "ReAct Skills"
   :memory "Memory" :interaction "User Interaction" :debugging "Debugging"
   :mcp "MCP" :aws "AWS" :task "Background Tasks"
   :agent-session "Agent Sessions" :agent-registry "Agent Registry"
   :agent-knowledge "Agent Knowledge" :agent-runtime "Agent Runtime"
   :agents "Subagents"
   :email-command "Email" :slack-command "Slack" :rag-command "RAG"
   :chart-command "Charts" :query-command "Queries"
   :usage "Usage Guides"})

(defn- format-arglists
  "Format arglists for a single binding entry.
   Multiple arities rendered as `(fn a)` / `(fn a b)` separated by ` / `."
  [sym arglists]
  (if (and arglists (> (count arglists) 1))
    (str/join " / "
              (map (fn [args]
                     (str "`(" sym (when (seq args) (str " " (str/join " " args))) ")`"))
                   arglists))
    (str "`(" sym
         (when-let [args (first arglists)]
           (when (seq args) (str " " (str/join " " args))))
         ")`")))

(defn- format-binding-entry
  "Format a single binding entry as a markdown list item."
  [{:keys [sym val]}]
  (if (fn? val)
    (let [m (meta val)
          doc (or (:doc m) "")
          first-line (first (str/split-lines doc))
          arglists (:arglists m)]
      (str "- " (format-arglists sym arglists)
           " — " (if (str/blank? first-line) "callable function" first-line)))
    (str "- `" sym "` — " (cond
                            (string? val) "string variable"
                            (number? val) (str "number (" val ")")
                            :else "variable"))))

(defn build-function-docs
  "Auto-generate function reference from sandbox bindings, grouped by category.
   Takes a bindings map {symbol fn-or-value} and produces a formatted string
   with category headings and function signatures.
   Functions with :category metadata are grouped; others fall under 'Other'."
  [bindings]
  (let [entries (map (fn [[sym val]]
                       (let [m (when (fn? val) (meta val))]
                         {:sym sym :val val
                          :category (or (:category m) :other)}))
                     bindings)
        grouped (group-by :category entries)
        ;; Ordered categories: defined order first, then any remaining
        ordered-cats (concat (filter #(contains? grouped %) category-order)
                             (remove (set category-order) (keys grouped)))]
    (str/join "\n"
              (mapcat (fn [cat]
                        (let [items (sort-by (comp str :sym) (get grouped cat))
                              header (get category-names cat (name cat))]
                          (cons (str "### " header)
                                (map format-binding-entry items))))
                      ordered-cats))))

(defn build-function-directory
  "Compact one-line-per-category function signatures for context briefing.
   Format: **Category**: fn1(args), fn2(args), ...
   Much shorter than build-function-docs — signatures only, no descriptions."
  [bindings]
  (let [entries (map (fn [[sym val]]
                       (let [m (when (fn? val) (meta val))]
                         {:sym sym :val val
                          :category (or (:category m) :other)}))
                     bindings)
        grouped (group-by :category entries)
        ordered-cats (concat (filter #(contains? grouped %) category-order)
                             (remove (set category-order) (keys grouped)))]
    (str/join "\n"
              (keep (fn [cat]
                      (let [items (sort-by (comp str :sym) (get grouped cat))
                            cat-name (get category-names cat (name cat))
                            sigs (str/join ", "
                                           (map (fn [{:keys [sym val]}]
                                                  (if (fn? val)
                                                    (let [args (first (:arglists (meta val)))]
                                                      (str sym "(" (when (seq args)
                                                                     (str/join " " args)) ")"))
                                                    (str sym)))
                                                items))]
                        (when (seq items)
                          (str "**" cat-name "**: " sigs))))
                    ordered-cats))))

(defn build-function-index
  "Ultra-compact category index for sandbox bindings.
   Format: a single line of `Category (N) · Category (N) · …`.
   Drops every signature; signals only that a category exists and how
   many callables it holds. Use `(list-tools :pattern \"…\")` /
   `(get-tool-info \"<id>\")` to drill in.

   Options:
     :filter-syms - optional set of binding symbols. When provided, the
                    counts reflect only bindings whose symbol is in the
                    set, and a `+ N more registered (use list-tools …)`
                    tail is appended when the bindings map is larger.
                    Use this to scope the index to a per-agent curated
                    tool roster while still leaving the rest discoverable
                    via list-tools / get-tool-info."
  [bindings & {:keys [filter-syms]}]
  (let [entries (map (fn [[sym val]]
                       (let [m (when (fn? val) (meta val))]
                         {:sym sym :val val
                          :category (or (:category m) :other)}))
                     bindings)
        in-scope?  (if (set? filter-syms)
                     (fn [{:keys [sym]}] (contains? filter-syms sym))
                     (constantly true))
        scoped     (filter in-scope? entries)
        grouped    (group-by :category scoped)
        ordered-cats (concat (filter #(contains? grouped %) category-order)
                             (remove (set category-order) (keys grouped)))
        chips (keep (fn [cat]
                      (let [items (get grouped cat)
                            cat-name (get category-names cat (name cat))]
                        (when (seq items)
                          (str cat-name " (" (count items) ")"))))
                    ordered-cats)
        unscoped (- (count entries) (count scoped))
        line     (str/join " · " chips)]
    (cond
      (seq chips)
      (cond-> line
        (and (set? filter-syms) (pos? unscoped))
        (str " · _+ " unscoped " more registered (use `(list-tools …)`)_"))

      ;; No curated bindings matched, but the registry has them — say so.
      (and (set? filter-syms) (pos? unscoped))
      (str "_no curated tools for this agent; "
           unscoped " registered (use `(list-tools …)`)_")

      :else nil)))

;; ============================================================================
;; On-Demand Usage Guide System ((usage-<topic>) thunks)
;; ============================================================================

(def usage-topics
  "All available usage-guide topics. Each topic `:foo` is exposed in the sandbox as a `(usage-foo)` thunk (see make-usage-bindings)."
  [:truncation :final :discovery :tool-priority :agent-state :mcp
   :feedback :memory :todo :plans :skills :files
   :llm-query :rules])

(defn get-usage-guide
  "Return usage guide text for a topic keyword or string. Returns nil if unknown.
   Used by the sandbox `(usage-<topic>)` thunks to serve guides on-demand."
  [topic]
  (case (if (keyword? topic) topic (keyword topic))
    :truncation    usage-output-truncation
    :final         usage-final-rules
    :discovery     usage-discovery
    :tool-priority usage-tool-priority
    :agent-state   usage-agent-state
    :mcp           usage-mcp
    :feedback      usage-user-feedback
    :memory        usage-memory
    :todo          usage-todo
    :plans         usage-plans
    :skills        usage-skills
    :files         usage-file-ops
    :llm-query     usage-llm-query
    :rules         usage-rules-and-tips
    nil))

(defn extract-all-code-blocks
  "Extract ALL ```clojure/```clj fenced blocks from LLM response text.
   Unlike extract-code-blocks, returns ALL blocks (not just the first).
   Returns a vector of code strings."
  [text]
  (if (str/blank? text)
    []
    (let [pattern #"(?m)^(`{3,})(?:clojure|clj)[^\n]*\n([\s\S]*?\n)\1[ \t]*$"
          matches (re-seq pattern text)]
      (vec (distinct (map #(nth % 2) matches))))))

(def ^:private lang-aliases
  "Canonical language names for code block extraction."
  {"clj" "clojure" "py" "python" "sh" "bash"})

(def ^:private verbatim-lang-aliases
  "Canonical names for verbatim content fences (saved to a file, not executed)."
  {"md" "markdown" "txt" "text"})

(defn verbatim-lang?
  "True when `lang` names a verbatim content block (markdown/text/html) — its
   body is written to a scratch file rather than evaluated."
  [lang]
  (contains? #{"markdown" "text" "html"} lang))

(def ^:private verbatim-fence-re
  "4+ backtick fence carrying verbatim content. Deliberately longer than a code
   fence so the body can contain ordinary ``` code fences with zero escaping
   (CommonMark: a fence is closed only by a fence at least as long). The closing
   fence must repeat the opening backtick run (`\\1`) on its own line.
   Groups: 1=backticks 2=lang 3=info/filename 4=content."
  #"(?m)^(`{4,})(markdown|md|text|txt|html)([^\n]*)\n([\s\S]*?)\n\1[ \t]*$")

(def ^:private code-fence-re
  "Executable code fence. Variable-length (3+ backtick) — like `verbatim-fence-re`
   — so a body that contains ordinary ``` code fences (e.g. code building a
   markdown string) can be wrapped in a longer fence (````clojure) with zero
   escaping (CommonMark: a fence is closed only by a fence at least as long). The
   closing fence repeats the opening backtick run (`\\1`) on its own line.
   Groups: 1=backticks 2=lang 3=info 4=code."
  #"(?m)^(`{3,})(clojure|clj|python|py|bash|sh)([^\n]*)\n([\s\S]*?)\n\1[ \t]*$")

(defn- sanitize-verbatim-filename
  "Reduce an LLM-supplied fence filename hint to a safe basename, or nil."
  [info]
  (let [base (-> (or info "") str/trim
                 (str/replace #".*[/\\]" "")          ; drop any directory part
                 (str/replace #"[^A-Za-z0-9._-]" "_"))]
    (when-not (str/blank? base) base)))

(defn- blank-regions
  "Overwrite each [start end) span of `text` with spaces, preserving length so
   downstream match offsets stay aligned with the original string."
  [^String text regions]
  (if (empty? regions)
    text
    (let [sb (StringBuilder. text)]
      (doseq [[s e] regions]
        (dotimes [i (- (long e) (long s))]
          (.setCharAt sb (+ (long s) i) \space)))
      (.toString sb))))

(defn extract-all-code-blocks-multi
  "Extract ALL fenced blocks from LLM response text, in source order.

   Two fence flavours:
   - *code* fences (clojure/clj, python/py, bash/sh) → executed.
     Aliases normalized; unexpected trailing fence text sets `:fence-error`
     and the dispatcher returns it as an error entry instead of executing.
     (Per-fence backend routing like ```clojure :nrepl was removed; backend
     is configured per-agent via `:clj-backend`.)
     Variable-length (3+ backtick): code whose body contains ordinary ```
     fences (e.g. building a markdown string) can be wrapped in a longer fence
     (````clojure) — closed only by a matching backtick run on its own line, so
     the inner ``` passes through unescaped.
   - 4+-backtick *verbatim* fences (markdown/md, text/txt, html) → saved to a
     file, never executed. The longer fence lets the body hold ordinary ```
     code fences verbatim (no escaping). An optional token after the language
     is taken as a filename hint. Returns `{:lang :code :verbatim? true
     :filename}` — the content rides on `:code`.

   Verbatim spans are claimed first and masked out before code extraction, so a
   ``` fence nested inside verbatim content is never mistaken for executable
   code. Returns a vector of block maps ordered by position in `text`."
  [text]
  (if (str/blank? text)
    []
    (let [;; First pass: claim verbatim (4+ backtick) regions and their offsets.
          vm (re-matcher verbatim-fence-re text)
          verbatim (loop [acc []]
                     (if (.find vm)
                       (recur (conj acc {:start (.start vm)
                                         :end   (.end vm)
                                         :block {:lang (let [l (.group vm 2)]
                                                         (get verbatim-lang-aliases l l))
                                                 :code (.group vm 4)
                                                 :verbatim? true
                                                 :filename (sanitize-verbatim-filename
                                                            (.group vm 3))}}))
                       acc))
          ;; Mask verbatim spans (equal-length blanking keeps offsets aligned)
          ;; so nested ``` fences inside them aren't seen as executable code.
          masked (blank-regions text (map (juxt :start :end) verbatim))
          cm (re-matcher code-fence-re masked)
          code (loop [acc []]
                 (if (.find cm)
                   (let [lang     (.group cm 2)
                         trailing (str/trim (or (.group cm 3) ""))]
                     (recur (conj acc {:start (.start cm)
                                       :end   (.end cm)
                                       :block (cond-> {:lang (get lang-aliases lang lang)
                                                       :code (str/trim (.group cm 4))}
                                                (seq trailing)
                                                (assoc :fence-error
                                                       (str "Unexpected text on code fence: \"" trailing "\". "
                                                            "Fences take only the language token (e.g. ```"
                                                            lang "). Code-execution backend is configured "
                                                            "per-agent, not per-fence.")))})))
                   acc))]
      (->> (concat verbatim code)
           (sort-by :start)
           (mapv :block)))))

(defn build-iterations-text-multi
  "Format iteration records with language tags into text for the user message.
   Supports :lang field on eval-results. Falls back to \"clojure\" if absent."
  [iterations]
  (when (seq iterations)
    (let [sb (StringBuilder.)]
      (.append sb "## Previous Iterations\n")
      (doseq [{:keys [iteration eval-results]} iterations]
        (.append sb (str "\n### Iteration " iteration "\n"))
        (doseq [{:keys [lang code result output error]} eval-results]
          (let [lang (or lang "clojure")]
            (if (verbatim-lang? lang)
              ;; Verbatim content blocks: never echo the body back into history
              ;; — that is the whole point, keeping large content out of the
              ;; token stream. Record only where it landed.
              (when (and result (not (str/blank? (str result))))
                (.append sb (str "[" lang " content saved verbatim → " result "]\n")))
              (do
                (when (and code (not (str/blank? code)))
                  (.append sb (str "```" lang "\n" code "\n```\n")))
                (when (and output (not (str/blank? output)))
                  (.append sb (str "stdout:\n" (subs output 0 (min (long *max-feedback-chars*) (count output))) "\n")))
                (when (and error (not (str/blank? error)))
                  (.append sb (str "Error: " error "\n")))
                (when (and (or (nil? error) (str/blank? error))
                           (some? result) (not (str/blank? (str result))))
                  (.append sb (str (if (= lang "clojure") "=> " "exit-code: ") result "\n"))))))))
      (.toString sb))))

(defn build-iterations-text
  "Format iteration records into text for the user message.
   Takes truncated iteration records (from accumulate-iteration-action).
   Returns nil if iterations is empty."
  [iterations]
  (when (seq iterations)
    (let [sb (StringBuilder.)]
      (.append sb "## Previous Iterations\n")
      (doseq [{:keys [iteration eval-results]} iterations]
        (.append sb (str "\n### Iteration " iteration "\n"))
        (doseq [{:keys [code result output error]} eval-results]
          (when (and code (not (str/blank? code)))
            (.append sb (str "```clojure\n" code "\n```\n")))
          (when (and output (not (str/blank? output)))
            (.append sb (str "stdout:\n" (subs output 0 (min (long *max-feedback-chars*) (count output))) "\n")))
          (when (and error (not (str/blank? error)))
            (.append sb (str "Error: " error "\n")))
          (when (and (or (nil? error) (str/blank? error))
                     (some? result) (not (str/blank? (str result))))
            (.append sb (str "=> " result "\n")))))
      (.toString sb))))

;; ============================================================================
;; Config Helpers
;; ============================================================================

(defn model-default-iterations
  "Return model-aware default max-iterations.
   Conservative models (Opus, GPT-5) need fewer iterations.
   Liberal models (Haiku, small OSS) need more."
  [lm-config]
  (let [model-id (or (when (map? lm-config) (:model lm-config))
                     (when (string? lm-config) lm-config)
                     "")
        model-id (str/lower-case (str model-id))]
    (cond
      (str/includes? model-id "opus")   10
      (str/includes? model-id "gpt-5")  10
      (str/includes? model-id "sonnet") 15
      (str/includes? model-id "gpt-4o") 15
      (str/includes? model-id "gemini") 15
      (str/includes? model-id "haiku")  20
      :else                             20)))

