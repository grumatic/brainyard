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

(defn- execution-model-core
  "## Execution Model section. The interop bullet is conditional on the SCI
   interop level (`:restricted` default vs `:full` in a container sandbox)."
  [interop]
  (str "## Execution Model
Your code runs in a **sandboxed Clojure interpreter** (SCI). Each ```clojure block is evaluated,
and the results (return value, stdout, or error) are sent back for the next iteration.
- **State persists**: `def` variables survive across iterations.
- **Captured output**: `println`/`pprint` output is captured and returned to you.
- **Errors are non-fatal**: Exceptions show the error message; sandbox state is preserved.
"
       (if (= interop :full)
         (str "- **Full Java interop**: arbitrary Java interop is available (System, Runtime, ProcessBuilder, reflection, etc.) — you are running in a container sandbox.\n"
              "- **File/shell libraries**: `slurp`, `spit`, `sh` (`(sh \"ls\" \"-l\")`), plus `clojure.java.io/*` (file, copy, reader…) and `clojure.java.shell/*` are available.")
         "- **No interop**: System, Runtime, ProcessBuilder, ClassLoader access denied.")
       "
- **Timeout**: 30s per code block."))

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

Start working from the function directory and briefing. Call `(usage :topic)` for detailed usage guides; `(usage)` (no args) lists all available topics.")

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
     :interop                - SCI interop level (:restricted default | :full). Controls the
                               interop bullet in the Execution Model section.
     :return-breakdown?      - When true, returns {:content str :token-breakdown map}"
  [& {:keys [mode max-iterations instruction agent-context tool-context
             function-directory brainyard-instructions interop return-breakdown?]
      :or {mode :raw max-iterations 20 interop :restricted}}]
  (let [brainyard-section (when brainyard-instructions
                            (format-brainyard-instructions brainyard-instructions))
        sections
        (cond->
         {:role-and-execution
          (str (if (= mode :raw)
                 "You are an AI agent that accomplishes tasks by writing and executing code."
                 "You are an AI agent that answers queries by writing Clojure code in a REPL sandbox.")
               "\n\n" (execution-model-core interop))
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
;; On-Demand Usage Guide System
;;
;; Guide CONTENT + the topic registry now live in the agent component
;; (ai.brainyard.agent.core.usage + agent.common.usage-guides). The sandbox
;; `(usage :topic)` binding is built in agent.common.sandbox-bindings and reads
;; that open registry. clj-sandbox no longer hosts guide strings.
;; ============================================================================

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

