;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.coact-agent
  "CoAct — Unified Tool-and-Code Action Agent (rev 3).

   Single loop, single DSPy signature, three output channels per iteration:
     - tool-calls  — ReAct-style: invoke registered tools via JSON
     - code-blocks — CoAct-style: markdown-fenced code (clojure, bash, python, js)
     - answer      — terminal: final markdown answer (loop exits)

   Design spec: docs/CoAct.md

   Stable-context categorization (both ride the system message via :stable-keys):
     :system-context  — agent role, sandbox contract, channel routing, code-block
                        format, tool-call format, critical rules, function directory.
     :user-context    — brainyard instructions (BRAINYARD.md), conversation history,
                        previous turns (Q/A + recent iterations), live artifacts
                        (loaded skill files, extension instructions).

   Per-turn inputs (user message):
     :question, :iterations, :context-briefing, :recalled-memory.

   Independence: imports only low-level primitives (clj-sandbox/*, tool/call-tool,
   evaluation/FinalizeAnswer). No dependency on react_agent."
  (:require [ai.brainyard.clj-llm.interface :as clj-llm :refer [defsignature defschemas]]
            [ai.brainyard.behavior-tree.interface :as bt :refer [st-memory-has-value?]]
            [ai.brainyard.clj-sandbox.interface :as clj-sandbox]
            [ai.brainyard.clj-nrepl.interface :as clj-nrepl]
            [ai.brainyard.agent.common.context-actions :as ctx-actions]
            [ai.brainyard.agent.common.evaluation :as evaluation]
            [ai.brainyard.agent.common.previous-turns :as prev-turns]
            [ai.brainyard.agent.common.sandbox-bindings :as sb-bind]
            [ai.brainyard.agent.common.user-tools :as ut]
            [ai.brainyard.agent.common.user-hooks :as uh]
            [ai.brainyard.agent.common.user-agents :as ua]
            [ai.brainyard.agent.common.auto-notify :as auto-notify]
            [ai.brainyard.agent.common.schema :as acs]
            [ai.brainyard.agent.common.trace :as trace]
            [ai.brainyard.agent.common.trajectory :as trajectory]
            [ai.brainyard.agent.common.commands :as common-cmds]
            [ai.brainyard.agent.common.tools :as common-tools]
            [ai.brainyard.agent.mcp.commands :as mcp-cmds]
            [ai.brainyard.agent.core.agent :as agent]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.context :as context]
            [ai.brainyard.agent.core.context-budget :as cb]
            [ai.brainyard.agent.core.context.formatters :as fmt]
            [ai.brainyard.agent.core.context.section-assembler :as sa]
            [ai.brainyard.agent.core.system-info :as sys-info]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool :refer [defagent]]
            [ai.brainyard.agent.task.commands :as task-cmds]
            [ai.brainyard.agent.task.executor :as executor]
            [ai.brainyard.agent.task.manager :as task-mgr]
            [ai.brainyard.agent.task.persist :as task-persist]
            [ai.brainyard.agent.task.protocol :as tp]
            [ai.brainyard.agent.tui.ansi :as ansi]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; Forward declarations: coact-inc-iter-action harvests pending evals at
;; iteration start and needs to sanitize the resolved entries before
;; appending them to :iterations; sanitize-eval-entry is defined later
;; alongside the record-building actions.
(declare sanitize-eval-entry)

;; ============================================================================
;; Domain Schemas
;; ============================================================================

(defschemas coact-domain
  {;; ── Stable context (system message via :stable-keys) ────────────────────
   ::system-context [:string {:desc "System-wide operational rules: agent role, sandbox contract, tool-usage conventions, code-block format rules, language dispatch. Stable across sessions for a given agent version."}]

   ::user-context [:string {:desc "User- and project-specific context: brainyard user/project instructions (BRAINYARD.md), conversation history, previous turns (Q/A + recent iterations), live artifacts (loaded skill files, extension instructions). Stable within a session."}]

   ;; ── Per-turn inputs (user message) ──────────────────────────────────────
   ::context-briefing [:string {:desc "Per-turn briefing: latest tool specs, agent-context snapshot, instruction for THIS turn."}]

   ;; ── LLM outputs ─────────────────────────────────────────────────────────
   ::thought [:string {:desc "Reasoning for this iteration (1-3 sentences)"}]

   ::tool-call [:map {:desc "One tool invocation"}
                [:tool-name [:string {:desc "tool id"}]]
                [:tool-args [:vector {:desc "argument list"}
                             [:map
                              [:name [:string {:desc "arg name"}]]
                              [:value [:string {:desc "arg value"}]]]]]]

   ::tool-calls [:vector {:desc "Tool invocations. Empty when using code-blocks or answer."}
                 ::tool-call]

   ::code-blocks [:string {:desc "Markdown text containing fenced code blocks with language tags (clojure, bash, python, javascript). Blocks separated by a line containing only `<!-- ParallelBlock -->` run concurrently in forked sandboxes. Otherwise blocks run sequentially in source order. Four-backtick fences tagged markdown/text/html are verbatim content blocks: saved to a file (path returned), not executed. Empty when using tool-calls or answer."}]

   ;; Answer-channel self-assessment (replaces the old standalone FinalizeAnswer
   ;; DSPy call). Optional outputs — populated only when `answer` is non-blank;
   ;; left default (false / empty string) on tool-calls / code-blocks turns.
   ::goal-achieved [:boolean {:desc "Set ONLY when populating `answer`: true iff the question is fully and accurately answered with evidence support. false signals you are answering but not yet confident — the loop may refine. Leave false on tool-calls/code-blocks turns."}]
   ::next-user-prompt [:string {:desc "Set ONLY when populating `answer`: a concise one-line follow-up the USER could send next to build on this answer. Phrase it as the user's own request (imperative, ~12 words max), not a question back to the user. Empty string when no useful follow-up exists or when not answering."}]

   ;; ── Iteration record & history ──────────────────────────────────────────
   ::tool-result-entry [:map {:desc "One tool invocation + its result"}
                        [:tool-name [:string {:desc "tool id"}]]
                        [:tool-args [:any {:desc "arguments used"}]]
                        [:tool-result [:string {:desc "printed result (print semantics — real newlines preserved), truncated"}]]]

   ::eval-entry [:map {:desc "One code-block execution result. All four langs (clojure/bash/python/javascript) share this shape — the underlying task manager is the single source of truth for in-flight evals."}
                 [:lang [:enum {:desc "language of the block (markdown/text/html are verbatim content blocks saved to a file, not executed)"}
                         "clojure" "bash" "python" "javascript"
                         "markdown" "text" "html" "other"]]
                 [:code [:string {:desc "code that was executed"}]]
                 [:result [:string {:desc "pr-str of evaluation result (or exit-code for shells)"}]]
                 [:output [:string {:desc "captured stdout (and stderr for shells)"}]]
                 [:error [:string {:desc "error message, empty if none"}]]
                 [:parallel? [:boolean {:desc "true if this block ran in a parallel partition"}]]
                 ;; Optional fields set on the soft-pending / harvest paths.
                 [:status [:maybe [:enum {:desc "Optional task lifecycle marker. :pending = soft-timeout deferred, harvest later; :timeout = hard-cancel at the LLM-set deadline; :resolved = harvested completion of a previously-pending task; :cancelled = cancel-task was called. Missing on plain sync completion (success / failure)."}
                                   :pending :timeout :resolved :cancelled]]]
                 [:task-id [:maybe [:string {:desc "Stable task-manager handle. Set when :status is :pending (so a later iteration can correlate the harvested completion) or :resolved (so the LLM can pass it to `task$detail` (add `:last-n N` for the output tail) / `task$cancel`)."}]]]
                 [:from-iteration [:maybe [:int {:desc "On :resolved entries, the iteration that first emitted the eval (provenance for cross-iteration formatting)."}]]]]

   ::iteration [:map {:desc "One CoAct iteration record"}
                [:iteration [:int {:desc "1-based index"}]]
                [:thought ::thought]
                [:channel [:enum {:desc "which channel executed. \"evaluation\" is a synthetic record injected when an emitted answer was rejected (self-reported not-done or EvaluateAnswer verdict) so the next iteration sees the critique."}
                           "tool" "code" "none" "evaluation"]]
                [:tool-results [:vector {:desc "non-empty iff channel = tool"} ::tool-result-entry]]
                [:code-results [:vector {:desc "non-empty iff channel = code"} ::eval-entry]]
                ;; ── Evaluation-record fields (channel = "evaluation" only) ──
                [:rejected-answer {:optional true} [:string {:desc "the answer draft that was rejected"}]]
                [:verdict {:optional true} [:string {:desc "rejection reason: \"SELF\" (you reported goal-achieved=false), \"INCOMPLETE\", or \"HALLUCINATED\""}]]
                [:feedback {:optional true} [:string {:desc "what to fix before answering again"}]]]

   ::iterations [:vector {:desc "Full iteration history (capped + truncated for context budget)"}
                 ::iteration]})

;; ============================================================================
;; DSPy Signature
;; ============================================================================

(defsignature ThinkActCode
  "You are a CoAct agent. Each iteration, reason briefly about the next step,
then emit exactly ONE of three things:

  1. TOOL CALL       — populate `tool-calls`, leave `code-blocks`/`answer` empty
  2. CODE BLOCK(S)   — populate `code-blocks`, leave `tool-calls`/`answer` empty
  3. FINAL ANSWER    — populate `answer`, leave both action fields empty

The iteration loop terminates when `answer` is non-blank. You signal completion
by writing the final markdown answer.

IF you cannot finish (missing info, dead-end, need user clarification), still
populate `answer` — explain what you learned, what is missing, and what you
need from the user. That becomes the final response.

---------------------------------------------------------------------------
ACTION CHANNELS — WHEN TO USE EACH
---------------------------------------------------------------------------

TOOL CHANNEL — use when:
  - A registered tool directly satisfies the sub-goal.
  - No post-processing of the result is needed before the next step.
  - You want to launch a fire-and-forget background task (task$run (:job-type :tool|:bash))
    that you'll explicitly poll via `task$detail` (add `:last-n N` for the output tail).
    Long-running CODE-channel blocks survive automatically — they auto-detach into
    the background after the auto-background deadline (see below) and a later
    iteration's harvest folds in the resolved result.

  Output shape:
    tool-calls: [{\"tool-name\": \"...\",
                  \"tool-args\": [{\"name\": \"...\", \"value\": \"...\"}]}]
    code-blocks: \"\"   answer: \"\"

CODE CHANNEL — populate `code-blocks` with markdown-fenced blocks. Supported
languages: `clojure` (evaluated in the shared SCI sandbox), `bash` (written to
a temp file and executed), `python` (temp file + python3), `javascript`
(temp file + node). Use when:
  - Composition: filter → map → reduce → pretty-print.
  - `def` persistence across iterations (use a `clojure` block).
  - Raw scripts with nested quotes or regex backslashes — use a `bash` or
    `python` block. The block content is passed verbatim; no SCI escaping.
  - Parallel fan-out: separate independent blocks with a line containing only
    `<!-- ParallelBlock -->`. Each block in a parallel partition runs
    concurrently (forked sandbox for clojure; fresh process for shell/python/js).
  - Producing document content (markdown/HTML/text report): use a FOUR-backtick
    verbatim fence (```` ````markdown name.md ````). The body is saved verbatim
    to a file and you get the path back — never hand-escape large content into a
    string literal. See the code-blocks format help for details.

  Sequential example:
    ```clojure
    (def tools (list-tools))
    ```
    ```clojure
    (pprint (filter #(re-find #\"(?i)aws\" (str (:id %))) tools))
    ```

  Parallel example:
    ```clojure
    (println (search {:query \"topic A\"}))
    ```
    <!-- ParallelBlock -->
    ```clojure
    (println (search {:query \"topic B\"}))
    ```
    <!-- ParallelBlock -->
    ```bash
    curl -s https://api.example.com/status
    ```

  Raw bash example (no escaping — block content is verbatim):
    ```bash
    grep -E '\\d+\\.\\d+' /tmp/input.txt | awk '{print $2}'
    ```

  Output shape:
    code-blocks: \"```clojure\\n...\\n```\\n<!-- ParallelBlock -->\\n```bash\\n...\\n```\\n\"
    tool-calls: []   answer: \"\"

ANSWER CHANNEL — use when:
  - You have enough evidence to answer, OR
  - You cannot make further progress (include the reason), OR
  - You need user clarification (ask in the answer).

  When you populate `answer`, ALSO set the two self-assessment outputs:
    - `goal-achieved`: true ONLY if the question is fully and accurately
      answered with support from the evidence in `iterations`. Set false if you
      are answering but uncertain, partial, or blocked — the loop may refine.
    - `next-user-prompt`: one concise line (~12 words max) the USER could send
      next to build on this answer, phrased as the user's own imperative request
      (NOT a question back to them). Empty string when no useful follow-up.
  Leave both at their defaults (false / \"\") on tool-calls and code-blocks turns.

  If a prior `[evaluation]` record in `iterations` shows your previous answer was
  REJECTED, read its feedback, FIX the issue (re-run tools/code if data is
  missing), and do not simply repeat the rejected text.

  Output shape:
    answer: \"## Markdown answer\\n- bullet\\n- caveat: ...\"
    goal-achieved: true   next-user-prompt: \"add error handling to the script\"
    tool-calls: []   code-blocks: \"\"

---------------------------------------------------------------------------
EACH ITERATION — FOLLOW THESE STEPS
---------------------------------------------------------------------------

STEP 1 — REVIEW
Read `iterations`. Each record shows your prior thought, the channel you
took, and the concrete result (tool-results or code-results with `lang`).
Do NOT re-do work whose result is already in the history.

STEP 2 — REASON (captured by chain-of-thought)
Briefly identify the next gap and which channel fits best. The CoT layer
stores this reasoning.

STEP 3 — ACT
Populate exactly ONE channel per the rules above.

---------------------------------------------------------------------------
CHANNEL DECISION HEURISTICS
---------------------------------------------------------------------------
1. One registered tool, no post-processing                    → TOOL
2. Background/long-running task                               → TOOL
3. Compose, filter, transform, pprint                         → CODE (clojure)
4. Parallel independent sub-queries                           → CODE (<!-- ParallelBlock -->)
5. Raw shell/Python with nested quotes or regex backslashes   → CODE (bash/python fence)
6. Need cross-iteration `def` state                           → CODE (clojure)
7. Ready to answer (or cannot proceed, or need clarification) → ANSWER

---------------------------------------------------------------------------
FIELD-CONSISTENCY RULES (enforced by the BT router)
---------------------------------------------------------------------------
- answer non-blank         ⇒ tool-calls=[],  code-blocks=\"\".
- tool-calls non-empty     ⇒ code-blocks=\"\", answer=\"\".
- code-blocks non-blank    ⇒ tool-calls=[],  answer=\"\".
- Populating NEITHER a channel NOR an answer triggers a repair iteration.

---------------------------------------------------------------------------
CODE-BLOCK LIFECYCLE — AUTO-BACKGROUND DETACH
---------------------------------------------------------------------------
Each code block runs as a task in synchronous foreground mode (you see its
live output). If the block has not finished by the agent's
`:auto-background-timeout-ms` (configurable; 120s default), the runner
detaches into background — the task keeps executing, but you get a
`:status :pending :task-id <id>` eval-entry back immediately so the loop
can continue. A LATER iteration receives the resolved result as an
`[↺ async-completion]` record. You may also poll any time with
`task$detail` (add `:last-n N` for the output tail) or stop with
`task$cancel`. No per-iteration timeout knob is needed.
"
  ;; `:system-context` and `:user-context` are embedded in the system prompt
  ;; by the BT dspy action via :stable-keys — they do NOT need to be signature
  ;; inputs (build-system-prompt reads them from st-memory directly).
  {:inputs  {:question         ::acs/question
             :context-briefing ::context-briefing
             :recalled-memory  ::acs/recalled-memory
             :iterations       ::iterations}
   :outputs {:tool-calls       ::tool-calls
             :code-blocks      ::code-blocks
             :answer           ::acs/answer
             :goal-achieved    ::goal-achieved
             :next-user-prompt ::next-user-prompt}})

;; ============================================================================
;; Section Strings — CoAct-native
;; ============================================================================

(def ^:private coact-role
  "You are an AI agent that answers questions by choosing ONE of three output channels per turn:
(1) **tool-calls** — invoke registered tools via a JSON array (ReAct-style).
(2) **code-blocks** — write markdown fenced code (clojure / bash / python / javascript)
    that runs in a persistent SCI sandbox (CoAct-style).
(3) **answer** — finalize with a rich markdown answer. Non-blank `answer` TERMINATES the loop.
Your reasoning is captured automatically by the chain-of-thought layer — no separate `thought`
field is required. Pick exactly one of (1), (2), (3); the router treats populated-field-count > 1
as a conflict and prefers code > tool > answer.")

(def ^:private coact-channel-routing
  "## When to Use Which Channel
- **tool-calls** for: one-shot RPC-style operations with a known tool — `slack$search`,
  `jira$search`, `aws$describe-instances`, bootstrap `list-tools`/`get-tool-info`. Also
  use for fire-and-forget background work via `task$run :sync false` (async mode) when
  you want explicit polling control (`task$detail` — add `:last-n N` for the output tail).
- **code-blocks (clojure)** for: composing over previous results (filter/map/reduce), iterating
  on a `def`'d value, discovery (`(filter ... (list-tools))`), chaining multiple tool results
  inside a single iteration with local `let`/`def`.
- **code-blocks (bash / python / javascript)** for: raw scripts with verbatim content
  (no SCI escaping), long commands, or environment-sensitive logic that wants a fresh process.
- **Long-running code** (any lang): no special flag needed. Each block runs synchronously
  in the foreground; if it exceeds the agent's `:auto-background-timeout-ms` it auto-detaches
  into the background as a pending task. A later iteration receives the resolved result
  automatically as an `[↺ async-completion]` record. Poll any time with `task$detail` or
  stop with `task$cancel`.
- **answer** for: you have enough information to respond. Markdown only. This terminates the loop.
  This is legitimate on iteration 1 — greetings, direct-knowledge questions, or clarification
  requests don't require tool calls or code. Don't artificially generate a throwaway tool
  call just to justify 'using the loop'.

> See **Large Tool Results Playbook** below for `--- TRUNCATED` recovery.")

(def ^:private coact-tool-call-format
  "## tool-calls Format (JSON array)
Populate `tool-calls` as a JSON array to invoke one or more tools in a single iteration:
   [{\"tool-name\": \"<id>\", \"tool-args\": [{\"name\": \"<arg>\", \"value\": \"<val>\"}]}]
- For agent tools, pass `{\"name\": \"question\", \"value\": \"<your question>\"}`.
- The runtime dispatches each entry and appends results to the iteration record under `tool-results`.

### Bootstrap Tools (always bound)
1. **list-tools** — enumerate registered tools (commands, skills, agents, MCP tools all live in the
   same registry). Args: `type` (\"tool\"|\"command\"|\"skill\"|\"agent\"), `pattern` (regex on
   id/name/description). MCP tools are registered as `mcp$<server>$<tool>`, so filter by server with
   `:pattern \"^mcp\\\\$<server>\\\\$\"`.
2. **get-tool-info** — fetch full schema for a specific `tool-id`. Call this before invoking an unfamiliar tool.

### Background Tasks (for long-running operations)
- **task$run** — start a background task. Required: `job-type` (`\"tool\"` | `\"bash\"`).
  - `:job-type :tool` — also pass `tool-id` (required) + optional `tool-args` (JSON object) + optional `name` label.
  - `:job-type :bash` — also pass `command` (required) + optional `timeout` (ms; default 120000) + optional `name`.
  Returns `{task-id, status}`; poll with `task$detail` (add `:last-n N` for the output tail); cancel with `task$cancel`.
- Launch multiple background tasks and collect them in subsequent iterations.

### Runtime Configuration
- `agent-runtime$config` — view current runtime settings (no args), or change
  one setting via `:key`/`:value` (takes effect next iteration).")

(def ^:private coact-code-blocks-format
  "## code-blocks Format (markdown fences, one string)
`code-blocks` is ONE markdown string with one or more fenced blocks.

| Fence | Runtime | Escaping | State |
|---|---|---|---|
| ```clojure / ```clj | SCI sandbox (shared across iterations) | SCI escaping (see below) | `def` persists |
| ```bash / ```sh    | fresh subprocess, /bin/bash | raw — no escaping | stateless |
| ```python / ```py  | fresh subprocess, python3   | raw | stateless |
| ```javascript / ```js | fresh subprocess, node   | raw | stateless |
| ````markdown / ````text / ````html | NOT executed — body saved verbatim to a file | raw — no escaping | returns the file path |

### Verbatim content fences (markdown / text / html)
To PRODUCE document content (a report, an HTML page, a long text blob), do NOT
embed it as a string literal inside a clojure/python/bash block — escaping it
by hand is error-prone. Instead emit a **four-backtick** verbatim fence:

````markdown report.md
# Title
Even nested ```clojure (inc 1)``` fences stay literal — no escaping.
````

The body is written byte-for-byte to a scratch file and the eval result is its
absolute path (`:result`) plus `Wrote N chars to <path>`. The optional token
after the language (`report.md`) is a filename hint. Use **4+ backticks** so any
ordinary ``` fences inside the content pass through untouched.

To READ the file back in a later block, use the `read-file` tool, e.g. a clojure
fence `(read-file {:path \"<path>\"})`. To PROMOTE it into the working tree (files
are scratch, GC'd ~24h), copy it with a bash fence — `slurp`/`spit` are NOT
available in the SCI sandbox:
```bash
cp \"<path>\" docs/report.md
```

### Parallel execution (all-or-nothing)
Insert `<!-- ParallelBlock -->` anywhere in `code-blocks` to run ALL fenced blocks
concurrently; absent, they run sequentially in source order. Parallel clojure blocks run in
**forked sandboxes** — new `def`s merge back after all finish (last-block-wins on conflict).
For mixed pipelines (\"A then B+C parallel then D\"), span multiple iterations.

### Rules
- One channel per iteration: `tool-calls` OR `code-blocks`, never both. No XML tool-calling.
- A clojure block invokes a registered tool via its auto-bound kebab-case
  symbol — `(<tool-id> {...args})`. `call-tool` is only needed for MCP fallback
  via `:server-name`; see `### Hot-path primitives`.
- **Fences take only the language token** — no per-block modifiers. ```clojure
  (not ```clojure :nrepl, not ```clj :sandbox). Code-execution backend is
  fixed per-agent; the dispatcher errors on unexpected fence text.
- **Code whose body contains ``` fences** (e.g. building a markdown string with
  embedded code fences) must be wrapped in a LONGER fence — open with 4 backticks
  ````clojure … ```` — so the inner ``` passes through unparsed. A 3-backtick
  fence closes at the first inner ```. (To merely *save* such content to a file,
  prefer a 4-backtick ````markdown verbatim fence instead — it is not executed.)
- **SCI string escaping** (clojure fences only): only `\\n` `\\t` `\\\"` `\\\\` are valid. For
  regex in shell strings, double the backslashes (`\"grep '\\\\d+' …\"`). For multi-line
  shell, here-docs, or template literals: use a raw ```bash / ```python fence instead — the
  block content is passed verbatim with no SCI parsing.
- JSON parsing in a clojure fence: `(parse-json s)` is built-in (no require).
- Use `clojure.string/join`, not `str/join` (no `str/` alias).

Detailed escaping recipes: `(usage :rules)`.")

(def ^:private coact-critical-rules
  "## Critical Rules
- **Iteration 1 has no history.** When `iterations` is empty in the user message, do NOT reference
  prior results, earlier tool calls, or previous findings — there are none. Act on the question
  as posed; gather what you need via tools or code this iteration.
- **`(FINAL …)` is disabled for CoAct.** Terminate ONLY by populating the signature's `answer`
  output field in the JSON envelope. A `(FINAL …)` call returns an error nudge, not a terminator.
- **Artifacts & dossiers: use the typed readers, never a bare `ls`/`find`.** To check whether a
  plan / todo / dossier / result exists, call its typed tool — `doc$read`, `exec$find`,
  `plan$read`/`plan$list`, `todo$read`/`todo$list`, `explore$…`, `research$…`. They resolve across
  BOTH project scope (`<repo-root>/.brainyard/`) and user scope (`~/.brainyard/`), plus legacy
  locations — a hand-rolled `ls .brainyard/…` cannot, so it silently misses artifacts. The `bash`,
  `read-file`, `write-file`, and `grep` tools all anchor relative paths at the **project root
  (git-root)**, never the JVM cwd (which under `bb tui` is a `projects/…` subdir) — so
  `.brainyard/agents/…` is correct from any of them; do not prepend cwd guesses. If you genuinely
  must sweep with bash, search BOTH roots: `root=$(git rev-parse --show-toplevel); find \"$root/.brainyard\" \"$HOME/.brainyard\" …`.
- **Call `(usage :topic)` in a clojure fence** for detailed guides
  (e.g. `(usage :truncation)`, `(usage :discovery)`, `(usage :plans)`, `(usage :skills)`,
  `(usage :files)`, `(usage :llm-query)`, `(usage :rules)`). `(usage)` lists topics.
  See the `### Usage Guides` table in `## Tools` for when to consult each.")

(def ^:private coact-large-results-playbook
  "## Large Tool Results Playbook
Big tool/code-block results spill to `/tmp/…` and inline a marker:
```
--- <label> TRUNCATED (original: N chars, M lines) ---
--- Full content saved to: /tmp/.../abc.txt ---
--- Truncation limit: L chars (~SAFE_LINES lines). Keep read-file chunks within this limit. ---
--- Recovery: (def data (:content (read-file \"/tmp/...\" :lines [1 SAFE_LINES]))) then process with code ---
```
Read `N`, `M`, `SAFE_LINES` off the marker and obey them.

### Rules
1. **Never bare-read a spilled path** — pass `:lines [a b]` OR `:offset/:limit`. A bare read
   re-truncates → another marker → loop.
2. **Reach for `grep` first** when you need a pattern/region — its `{:matches :count}` result
   inlines without re-truncation. Skip the chunked walk entirely when grep is enough.
3. **Chunk via `read-file` tool-call** (one-shot RPC; not a clojure fence — fences add the
   tighter eval-output budget on top). Multi-line file → `:lines [1, SAFE_LINES]` then advance.
   Single-line / structured file → `:offset/:limit` in chars (the marker says which mode).
4. **Code fences are for composition only** — filter/regex/aggregate across chunks. NEVER
   `println`/`pprint` a reconstructed-from-chunks value; the printed string re-truncates.
5. **\"Show me X\" → populate `answer`**, don't re-print: read the chunk, then in the next
   iteration put the content verbatim into the signature's `answer` field. `(FINAL …)` is
   disabled for CoAct — populating `answer` is the only termination path.

Detailed recipes + worked examples: `(usage :truncation)`.")

(def ^:private coact-footer
  "## Answer Format
- Rich markdown (headers, bullets, tables). Never return raw EDN or JSON dumps.
- If the last iteration's results already contain the answer, write it immediately —
  don't re-fetch.")

(def ^:private coact-project-memory-protocol
  "Durable, project-scoped notes for THIS repo, kept as plain files under
`.brainyard/memory/` and persisting across sessions. The index below lists what
is stored; each entry points to a colocated `<slug>.md` topic file. You manage
these with the ordinary read-file / write-file / update-file tools — no special
tools, and `.brainyard/` writes never prompt for permission.

- RECALL: when a listed topic is relevant to the request, read its file
  (`read-file .brainyard/memory/<slug>.md`) BEFORE answering.
- REMEMBER: when you learn a durable project fact, decision, or convention worth
  keeping, write `.brainyard/memory/<slug>.md` (short YAML frontmatter —
  `title`, `tags`, `updated` — then the fact; link related notes with
  `[[other-slug]]`), and add or update its one-line pointer in
  `.brainyard/memory/index.md` (`- [Title](<slug>.md) — one-line hook`).
- One fact per file. Check the index first and UPDATE an existing file rather
  than creating a duplicate; delete a note that turns out wrong.
- Do NOT store transient task state, or anything already captured by the code,
  git history, or BRAINYARD.md.")

(defn- format-project-memory-section
  "Render the `## Project Memory` system-context section: the static protocol
   followed by the live index.md contents (truncated to `max-chars`), or an
   empty-state stub when no index exists yet."
  [{:keys [content max-chars]}]
  (let [cap   (or max-chars 4000)
        idx   (when (and content (not (str/blank? content)))
                (if (> (count content) cap)
                  (str (subs content 0 cap)
                       "\n…(index truncated — read .brainyard/memory/index.md in full)")
                  content))
        body  (if idx
                (str "### Index\n" idx)
                "### Index\n(empty — no memories yet. Create `.brainyard/memory/index.md` with your first note.)")]
    (str "## Project Memory (.brainyard/memory/)\n"
         coact-project-memory-protocol
         "\n\n" body)))

;; ---- Tools section helpers (calling conventions + directory hints) -------
;; The unified `## Tools` section in the system context combines:
;;   1. calling-conventions prose (this file)
;;   2. function directory  (clj-sandbox/build-function-directory)
;;   3. detailed agent-tools listing (format-agent-tools)
;;   4. discovery pointer to list-tools / get-tool-info
;; Source for the function-directory builder lives in
;; components/clj-sandbox/src/ai/brainyard/clj_sandbox/core/prompt.clj.

;; format-agent-tools / format-conversation / format-agent-tools-compact live
;; in ai.brainyard.agent.core.context.formatters (shared with ReAct).

(def ^:private coact-tools-overview
  "Every tool below is invocable via TWO channels — pick whichever fits the iteration:

**A) JSON `tool-calls` channel** (one-shot RPC, ReAct-style):
```
[{\"tool-name\": \"<id>\",
  \"tool-args\": [{\"name\": \"<arg>\", \"value\": \"<val>\"}]}]
```

**B) ```clojure code-block** (composable, can chain across iterations and feed
results into `let`/`def`). Registered tools surface in the sandbox under their
kebab-case names — call them directly:
```clojure
(query$llm \"prompt\")
(list-tools :type \"command\")
(mcp$server {:op \"list\"})
```

For MCP tools that aren't registered locally, use `call-tool` with `:server-name`:
```clojure
(call-tool \"fetch\" {:url \"…\"} :server-name \"fetch-server\")
```")

(def ^:private coact-tools-hotpath
  "These are the primitives to reach for unprompted — the rest of the registry is
on-demand (see `### Sandbox Categories` and `### Discovery`).

| When | Call | Notes |
|---|---|---|
| Run any registered tool | `(<tool-id> {:arg val})` — e.g. `(mcp$server {:op \"list\"})` | JSON channel `tool-calls` is equivalent. |
| Find tools by pattern   | `(list-tools :pattern \"…\")` / `:type \"command\"` | Regex over id/name/desc. |
| Inspect one tool        | `(get-tool-info \"<id>\")` | Schema (inputs/outputs/description). |
| Inspect a sandbox sym   | `(meta #'<sym>)` | `:doc`/`:arglists`/`:category`. |
| Cheap sub-LLM           | `(query$llm :prompt \"prompt\")` / `(query$llm :prompts [\"a\" \"b\"])` | One-shot, no agent state. |
| Run a registered agent  | `(explore-agent {:question \"…\"})` / `(plan-agent {…})` | Flat dispatch to a sibling agent by name. |
| MCP fallback             | `(call-tool \"<id>\" {…} :server-name \"<srv>\")` | Only for tools not in the local registry. |
| Look up usage guide     | `(usage :topic)` | Topics: see `### Usage Guides` table below. |
| Shell / files           | `(bash \"…\")`, `(read-file \"…\")`, `(write-file \"…\" \"…\")`, `(grep \"…\" \"path\")` | Standard primitives. |

Per-agent overrides ride in `### Agent-specific guidance` at the bottom of this
section — when present, prefer those over this generic list.")

(def ^:private coact-tools-directory-hints
  "Reading entries: signatures are `name(arg1 arg2)` (shorthand for `(name arg1 arg2)`).
Multi-arity bindings list each arity separated by `/`. A trailing entry without
parens is a value binding, not a callable. Use `(meta #'name)` inside a clojure
block for `:doc` / `:arglists` / `:category`.")

(def ^:private coact-tools-index-hints
  "Categories above only count callables — they do NOT list signatures. To use
anything outside the per-turn `### Agent Tools` block:
- `(list-tools :pattern \"…\")` — regex over id/name/description (e.g. `:pattern \"^mcp\\\\$\"`).
- `(list-tools :type \"command\")` — filter by registry type.
- `(get-tool-info \"<id>\")` — full inputs/outputs schema for one tool.
- `(meta #'<sandbox-binding>)` — arglists/doc/category for a callable already in scope.")

(def ^:private coact-tools-discovery
  "Beyond the bound set above, the runtime exposes everything else via:
- `list-tools` — enumerate registered tools (commands, skills, agents, MCP tools all live in the
  same registry). Filter by `type` (\"tool\"|\"command\"|\"skill\"|\"agent\") or `pattern` (regex on
  id/name/description). MCP tools are registered as `mcp$<server>$<tool>`, so to filter by server use
  `:pattern \"^mcp\\\\$<server>\\\\$\"`.
- `get-tool-info` — fetch full schema (id/type/inputs/outputs/description) by
  `tool-id`. Always call this BEFORE invoking an unfamiliar tool.")

(def ^:private coact-usage-guide-table
  "On-demand depth on a specific topic — call `(usage :topic)` to fetch.
Pull these BEFORE you commit to a non-trivial pattern in that area:

| Topic           | When to consult                                                    |
|-----------------|--------------------------------------------------------------------|
| `:llm-query`    | Before dispatching a sub-LLM (`query$llm` with `:prompt` or `:prompts`) — picks model, depth, context. |
| `:agent-state`  | Before reading/writing `[:agent-state …]` via `context-get`.       |
| `:memory`       | Before `memory$recall` / `memory$remember` — kinds, layers, scoring. |
| `:todo`         | Before any todo-* call — lifecycle, statuses, dependencies.        |
| `:plans`        | Before any `plan$*` call — slugs, scope, dossier handoff.          |
| `:skills`       | Before `skill$*` invocations or `skills$*` admin.                  |
| `:files`        | Before bulk `read-file` / `write-file` / `update-file`.            |
| `:mcp`          | Before invoking an MCP server tool you haven't called this turn.    |
| `:tool-priority`| When choosing between competing tools (registry vs MCP vs sandbox). |
| `:discovery`    | When unsure what's available — pairs with `list-tools`.            |
| `:truncation`   | When a tool result is going to be huge.                            |
| `:final`        | Before emitting the FINAL answer — termination contract.           |
| `:feedback`     | Before asking the user a clarifying question.                      |
| `:rules`        | Catch-all rules and tips for sandbox/agent etiquette.              |

`(usage)` (no args) returns the full topic catalog if you forget the names.")

(defn- build-tools-section
  "Render the unified `## Tools` system-context section.

   Combines:
     1. coact-tools-overview          — calling conventions + examples
     2a. ### Function Directory       — full per-binding signatures
                                        (only when :include-directory? true)
         + coact-tools-directory-hints — how to read the listing
     2b. ### Sandbox Categories       — compact `Cat (N) · Cat (N) · …` index
                                        (default; replaces 2a)
         + coact-tools-index-hints     — how to drill in
     3. ### Agent Tools                 — full per-tool detail (format-agent-tools)
     4. ### Discovery                   — coact-tools-discovery (list-tools/get-tool-info)
     5. ### Agent-specific guidance     — optional per-agent :tool-context overlay

   Returns nil when there is nothing to render (no bindings, no tools, no overlay).

   Options:
     :include-directory? — when true, render the full per-signature directory
                           (build-function-directory). When false (default),
                           render the compact category index instead.
     :disabled-tiers     — set of tier keywords to suppress. Tier names:
                             :function-index          — drop both ### Function
                                                        Directory and ### Sandbox
                                                        Categories blocks.
                             :agent-tools-details     — replace the verbose
                                                        ### Agent Tools block
                                                        with a one-liner per tool.
                             :usage-guides            — drop ### Usage Guides.
                             :tool-context-overlay    — drop ### Agent-specific
                                                        guidance.
                           The static rules / format / hot-path / discovery blocks
                           are never disabled."
  [{:keys [sandbox-bindings agent-tools tool-context-overlay include-directory?
           disabled-tiers]}]
  (let [disabled (or disabled-tiers #{})
        bindings? (seq sandbox-bindings)
        ;; When the agent has a curated :agent-tools roster (the same vector
        ;; rendered in `### Agent Tools`), scope the compact index to those
        ;; symbols so a focused agent doesn't see the global registry's noise.
        ;; The long tail stays reachable via list-tools / get-tool-info.
        curated-syms (when (seq agent-tools)
                       (set (keep #(some-> % :name symbol) agent-tools)))
        ;; Both function-directory and function-index live under the
        ;; :function-index tier — disabling it drops whichever was active.
        function-directory (when (and bindings? include-directory?
                                      (not (disabled :function-index)))
                             (clj-sandbox/build-function-directory sandbox-bindings))
        function-index (when (and bindings? (not include-directory?)
                                  (not (disabled :function-index)))
                         (clj-sandbox/build-function-index
                          sandbox-bindings :filter-syms curated-syms))
        ;; :agent-tools-details swaps the verbose block for a one-liner.
        agent-tools-block  (if (disabled :agent-tools-details)
                             (fmt/format-agent-tools-compact agent-tools)
                             (fmt/format-agent-tools agent-tools))
        overlay (when (and tool-context-overlay
                           (not (str/blank? tool-context-overlay))
                           (not (disabled :tool-context-overlay)))
                  tool-context-overlay)]
    (when (or function-directory function-index agent-tools-block overlay)
      (str/join "\n\n"
                (cond-> ["## Tools" coact-tools-overview
                         "### Hot-path primitives (reach for these first)"
                         coact-tools-hotpath]
                  (and function-directory (not (str/blank? function-directory)))
                  (conj "### Function Directory (sandbox callables, by category)"
                        function-directory
                        coact-tools-directory-hints)

                  (and function-index (not (str/blank? function-index)))
                  (conj "### Sandbox Categories (counts only — no signatures)"
                        function-index
                        coact-tools-index-hints)

                  agent-tools-block
                  (conj "### Agent Tools (bound for THIS turn — full specs)"
                        agent-tools-block)

                  true
                  (conj "### Discovery (other registered tools)"
                        coact-tools-discovery)

                  (not (disabled :usage-guides))
                  (conj "### Usage Guides (on-demand `(usage :topic)` lookup)"
                        coact-usage-guide-table)

                  overlay
                  (conj "### Agent-specific guidance"
                        overlay))))))

(defn- execution-model-sandbox
  "## Execution Model — SCI Sandbox. The interop bullet is conditional on the
   resolved SCI interop level (`:restricted` default vs `:full` in a container)."
  [interop]
  (str "## Execution Model — SCI Sandbox
Your clojure code runs in a **sandboxed Clojure interpreter** (SCI). Each ```clojure block is evaluated,
and the results (return value, stdout, or error) are sent back for the next iteration.
- **State persists**: `def` variables survive across iterations.
- **Captured output**: `println`/`pprint` output is captured and returned to you.
- **Errors are non-fatal**: Exceptions show the error message; sandbox state is preserved.
"
       (if (= interop :full)
         (str "- **Full Java interop**: arbitrary Java interop is available (System, Runtime, ProcessBuilder, reflection, etc.) — running in a container sandbox.\n"
              "- **File/shell libraries**: `slurp`, `spit`, `sh` (`(sh \"ls\" \"-l\")`), plus `clojure.java.io/*` and `clojure.java.shell/*` are available.")
         "- **No interop**: System, Runtime, ProcessBuilder, ClassLoader access denied.")
       "
- **Auto-background detach**: a block that hasn't finished by the agent's
  `:auto-background-timeout-ms` (default 120s) detaches into the background; the
  resolved result is harvested into a later iteration as an `[↺ async-completion]`
  record. Wait for it; do NOT poll repeatedly."))

(def ^:private execution-model-nrepl
  "## Execution Model — Live JVM via clj-nrepl
Your ```clojure blocks run against the LIVE brainyard JVM via clj-nrepl —
NOT the SCI sandbox. `System`, `Runtime`, `Thread/getAllStackTraces`, full
reflection, every loaded namespace, and arbitrary interop are all reachable.
- **State persists across iterations**: a server-issued nREPL session is
  pinned per agent instance; `(def …)` / `(alter-var-root …)` survive for
  the duration of this agent's session.
- **Captured output**: `println` / `*out*` / `*err*` are captured and
  returned to you in the next iteration.
- **Errors are non-fatal**: a CompilerException or runtime throw shows up
  as `:error` on the eval entry; session state is preserved.
- **Ephemeral edits**: `(def …)` / `(alter-var-root …)` change the running
  image but die on process restart and are NOT written to source. To make a
  fix permanent, hand off to update-agent (it owns the source edit).
- **Full-trust backend**: reaching the loopback server gives full eval —
  no grant, scope, or confirmation. The ONLY eval-path check is the
  **deny-list**: `System/exit`, `Runtime/.exec`, credential namespaces
  (`ai.brainyard.aws-client`, `ai.brainyard.keycloak`) are rejected. If you
  need ISOLATED evaluation, the SCI sandbox backend is the tool, not this.
- **NO SCI shortcuts**: `context-get`, `(usage :foo)`, and bare
  tool-name-as-fn (`(some-tool …)`) do not exist here. Use the
  tool-call channel for registered tools, and refer to library functions
  by their full namespace (`clojure.pprint/pprint`, not bare `pprint`).
- **Auto-background detach**: a block that hasn't returned by the agent's
  `:auto-background-timeout-ms` (default 120s) detaches into the background;
  the underlying nREPL session keeps running and the resolved result is
  harvested into a later iteration as an `[↺ async-completion]` record.
  Wait for it; do NOT poll repeatedly.")

(defn- execution-model-for
  "Pick the system-prompt execution-model section for `agent` based on its
   `:clj-backend` config. Defaults to the SCI-sandbox text when nil or
   unknown — same as historical behavior for non-overriding agents."
  [agent]
  (case (when agent (config/get-config agent :clj-backend))
    :nrepl   execution-model-nrepl
    (execution-model-sandbox (config/resolve-sandbox-interop agent))))

(def ^:private sandbox-context-accessor
  "## SCI Sandbox State Memory (TWO LAYERS)
The sandbox is your state memory. There is **no `context` variable** — always go through accessors.

| Layer | What | Owner | Lifetime | Access |
|---|---|---|---|---|
| **L1 inputs** | recalled memory, previous turns, agent state | agent (read-only) | per-turn | `(context-get [:recalled-memory])`, `[:previous-turns]`, `[:agent-state …]` |
| **L2 working `def`s** | anything you `(def x …)` in a clojure fence | you | across iterations + turns (in-session) | direct symbol; `(context-get [:user-vars])` for an inventory |

### Accessors (call `(context-index)` FIRST every turn — top-level keys + sizes)
- `(context-keys [:path])` — keys/indices at a level. `(context-keys [])` = top-level catalog.
- `(context-get [:path …] & {:keys [raw limit str-limit]})` — fetch (auto-truncated unless `:raw true`).
- `(context-sample [:path] N & {:keys [strategy]})` — `:strategy :random` (default) / `:evenly-spaced` / `:first` / `:last`.
- `(context-search \"keyword\" & {:keys [limit case-sensitive]})` — recursive string search across L1.

### Patterns
- Pulling agent-provided data → L1 via `context-get`. Building a reusable value → L2 via `def`.
- Exploration: `(context-index)` → `(context-keys […])` → `(context-sample [… ] 3)` → `(context-get […])`.

**CRITICAL — accessor results contain embedded strings.** Never embed a raw accessor result
inside another string literal (causes EOF parse errors). `def` it first, then read fields:
```clojure
(def prev (context-get [:previous-turns]))
(println (str \"Q: \" (:question (first prev))))
```

Live-state introspection (runtime keys, iteration count): `(usage :agent-state)`.")

;; ============================================================================
;; Context Assemblers
;; ============================================================================

(def ^:private default-artifact-max-chars
  "Fallback per-artifact truncation cap when a descriptor declares no
   :max-chars (the resolver stamps the config value; this guards direct
   callers)."
  4000)

(def ^:private file-artifact-preview-chars
  "File-backed artifacts reload from disk every turn, so the prompt carries
   only a short preview — past this many chars the body is cut and the LLM is
   pointed at `read-file` for the full content."
  400)

(defn- format-live-artifacts
  "Compact formatter for live-artifact descriptors (loaded skill files,
   reference docs like CLAUDE.md/AGENTS.md, LLM-added notes). Badges
   :origin :system / :pinned? so the LLM can tell which artifacts it may remove.

   File-backed artifacts (:source :file) render only a `file-artifact-preview-chars`
   preview; when longer, the body is cut and a `(read-file {:path …})` pointer is
   appended (the file reloads fresh each turn, so the full bytes need not ride
   the prompt). Inline/legacy artifacts render their content up to :max-chars."
  [artifacts]
  (when (seq artifacts)
    (->> artifacts
         (map (fn [{:keys [name content origin pinned? max-chars source path]}]
                (let [s     (str content)
                      body  (if (= source :file)
                              (if (> (count s) file-artifact-preview-chars)
                                (str (subs s 0 file-artifact-preview-chars)
                                     "…\n[truncated — `(read-file {:path \"" path
                                     "\"})` for the full content]")
                                s)
                              (let [cap (or max-chars default-artifact-max-chars)]
                                (if (> (count s) cap) (str (subs s 0 cap) "…") s)))
                      badge (str/join " " (cond-> []
                                            (= origin :system) (conj "system")
                                            pinned?            (conj "📌")))]
                  (str "### " (or name "artifact")
                       (when (seq badge) (str " (" badge ")")) "\n"
                       body))))
         (str/join "\n\n"))))

(defn- resolve-artifacts
  "Materialize live-artifact descriptors for rendering. For :source :file,
   load content fresh from disk (dropping descriptors whose file is missing or
   unreadable) so on-disk edits show live. :source :inline and legacy
   {:name :content} maps pass through. Stamps each surviving descriptor with an
   effective :max-chars (its own, else `default`)."
  [descriptors default]
  (->> (or descriptors [])
       (keep (fn [{:keys [source path content] :as d}]
               (let [d (update d :max-chars #(or % default))]
                 (cond
                   (= source :file)
                   (let [f (when path (java.io.File. ^String path))]
                     (when (and f (.isFile f))
                       (try (assoc d :content (slurp f))
                            (catch Exception _ nil))))
                   :else (when (some? content) d)))))
       vec))

(defn merge-artifact-descriptors
  "Merge system-seeded (re-derived each turn) over persisted dynamic
   descriptors, de-duped by :id (falling back to :name). System artifacts come
   first for stable display; the first occurrence of an id wins. Public so the
   artifact$* tools can dedupe on write."
  [& descriptor-seqs]
  (->> (apply concat descriptor-seqs)
       (reduce (fn [acc d]
                 (let [k (or (:id d) (:name d))]
                   (if (contains? (:seen acc) k)
                     acc
                     (-> acc (update :seen conj k) (update :out conj d)))))
               {:seen #{} :out []})
       :out))

(defn- format-previous-turns
  "Compact formatter for the previous-turns chain (Q/A + last few iterations).
   Honours each turn's :depth (`:full` keeps iterations, `:summary`/`:minimal`
   only show Q + A)."
  [previous-turns]
  (when (seq previous-turns)
    (->> previous-turns
         (map-indexed
          (fn [idx {:keys [question answer iterations depth]}]
            (let [q (str question)
                  a (str answer)
                  q-snip (if (> (count q) 500) (str (subs q 0 500) "…") q)
                  a-snip (if (> (count a) 1000) (str (subs a 0 1000) "…") a)
                  header (str "[Turn " (inc idx) (when depth (str " · " (name depth))) "]")
                  iter-lines
                  (when (and (= depth :full) (seq iterations))
                    (->> (take-last 3 iterations)
                         (map (fn [it]
                                (let [code-str (str/join "; " (or (:code it) []))
                                      result-str (first (or (:result it) []))
                                      code-snip (if (> (count code-str) 300)
                                                  (str (subs code-str 0 300) "…")
                                                  code-str)
                                      result-snip (when result-str
                                                    (let [s (str result-str)]
                                                      (if (> (count s) 300)
                                                        (str (subs s 0 300) "…")
                                                        s)))]
                                  (cond-> (str "    " (:iteration it) ". " code-snip)
                                    result-snip (str "\n       → " result-snip)))))
                         (str/join "\n")))]
              (cond-> (str header "\n  Q: " q-snip)
                (not (str/blank? a)) (str "\n  A: " a-snip)
                iter-lines           (str "\n  Iterations:\n" iter-lines)))))
         (str/join "\n\n"))))

(defn- coact-system-context
  "Assemble the stable system-context string. Sections are emitted in the
   order below; nil/blank inputs are omitted. SCI string restrictions are
   folded into the code-blocks format section. Sandbox bindings, agent tool
   listings, and per-agent tool-context overlay are unified in the `## Tools`
   section.

    1)  Role                       — coact-role
    2)  Execution model            — execution-model-core
    3)  Channel routing rules      — coact-channel-routing
    4)  tool-calls format          — coact-tool-call-format
    5)  code-blocks format         — coact-code-blocks-format
    6)  Sandbox context accessor   — sandbox-context-accessor
    7)  Tools                      — calling conventions + function directory
                                     + detailed agent-tools + discovery pointer
                                     + (optional) per-agent :tool-context overlay
    8)  Critical rules             — coact-critical-rules
    9)  Large-results playbook     — coact-large-results-playbook
    10) Instructions               — :instruction
    11) Agent Context              — :agent-context
    12) Project/User Instructions  — BRAINYARD.md (project then user)
    13) Project Memory             — :project-memory (index.md + protocol)
    14) Footer                     — coact-footer

   Deliberately OMITS brainyard instructions — those flow via :user-context.

   Inputs:
     :sandbox-bindings - raw SCI bindings map (sym → fn/value). Drives the
                         function-directory sub-section of `## Tools`.
     :agent-tools      - vector of bound tool descriptors (:name,
                         :description, :tool-fn-type, :parameters). Rendered
                         as the detailed agent-tools sub-section of `## Tools`.
     :tool-context     - per-agent tool guidance overlay. Appended as a
                         trailing `### Agent-specific guidance` sub-section
                         inside `## Tools` (used by derived agents).
     :instruction      - per-agent instruction string.
     :agent-context    - per-agent context string.
     :include-function-directory? - when true, render the full per-binding
                         function directory; otherwise (default) render the
                         compact `Sandbox Categories` index.

   Options:
     :return-breakdown? - when true, returns {:content str :sections {kw text}}
                          with one entry per non-blank section (used by the
                          dspy-action for per-section token attribution)."
  [{:keys [sandbox-bindings instruction agent-context tool-context agent-tools
           include-function-directory? system-info tools-disabled-tiers
           brainyard-instructions project-memory execution-model]}
   & {:keys [return-breakdown?]}]
  (let [tools-section (build-tools-section
                       {:sandbox-bindings     sandbox-bindings
                        :agent-tools          agent-tools
                        :tool-context-overlay tool-context
                        :include-directory?   include-function-directory?
                        :disabled-tiers       tools-disabled-tiers})
        {:keys [user-instructions project-instructions]} brainyard-instructions
        ;; :execution-model is pre-resolved by execution-model-for (keyed
        ;; off the agent's :clj-backend config). Caller may still pass nil
        ;; (e.g. early bootstrap paths with no agent) — fall back to the
        ;; sandbox text in that case.
        exec-model (or execution-model execution-model-sandbox)
        sections
        (cond-> {:role                         coact-role
                 :execution-model              exec-model
                 :channel-routing              coact-channel-routing
                 :tool-call-format             coact-tool-call-format
                 :code-blocks-format           coact-code-blocks-format
                 :sandbox-context-accessor     sandbox-context-accessor
                 :critical-rules               coact-critical-rules
                 :large-results-playbook       coact-large-results-playbook}

          (and system-info (not (str/blank? system-info)))
          (assoc :system-info system-info)

          tools-section
          (assoc :tools tools-section)

          (and instruction (not (str/blank? instruction)))
          (assoc :instruction (str "## Instructions\n" instruction))

          (and agent-context (not (str/blank? agent-context)))
          (assoc :agent-context (str "## Agent Context\n" agent-context))

          ;; P4.6: BRAINYARD.md promoted above the cross-turn cache
          ;; breakpoint. Lives in :system-context (session-stable) so
          ;; its bytes ride the prefix cache — one cache miss per file
          ;; edit, not per turn.
          (and project-instructions (not (str/blank? project-instructions)))
          (assoc :project-instructions
                 (str "## Project Instructions (.brainyard/BRAINYARD.md)\n"
                      project-instructions))

          ;; Project-scoped file memory index. Session-stable like the
          ;; instructions above (re-seeded each turn; one cache miss per
          ;; on-disk edit). `project-memory` is nil when the facility is
          ;; disabled via :enable-project-memory.
          (some? project-memory)
          (assoc :project-memory (format-project-memory-section project-memory))

          (and user-instructions (not (str/blank? user-instructions)))
          (assoc :user-instructions
                 (str "## User Instructions (~/.brainyard/BRAINYARD.md)\n"
                      user-instructions))

          true
          (assoc :footer coact-footer))
        ;; Stable display order. :system-info (priority 98) and
        ;; BRAINYARD.md sections (priority 85, session-stable) sit
        ;; above the cross-turn cache breakpoint so they ride the
        ;; prefix cache.
        section-order [:role :system-info :execution-model
                       :channel-routing :tool-call-format :code-blocks-format
                       :sandbox-context-accessor :tools
                       :critical-rules :large-results-playbook
                       :instruction :agent-context
                       :project-instructions :project-memory :user-instructions
                       :footer]
        content (str/join "\n\n" (keep #(get sections %) section-order))]
    (if return-breakdown?
      {:content content :sections sections :order section-order}
      content)))

(defn- coact-user-context
  "Assemble the user-context string from brainyard instructions (loaded via
   config/load-brainyard-instructions), conversation history, and live
   artifacts. Returns \"\" when nothing is present.

   When :return-breakdown? is true, returns {:content str :sections {kw text}}
   with one entry per non-blank section."
  [{:keys [conversation previous-turns live-artifacts turn-info parent-trail]}
   & {:keys [return-breakdown?]}]
  ;; P4.6: :project-instructions and :user-instructions moved to
  ;; :system-context (above the cross-turn cache breakpoint). The
  ;; user-context now holds only per-turn-volatile sections.
  ;; NOTE: refinement feedback is NO LONGER a user-context section — in-loop
  ;; refinement injects the critique as an [evaluation] record in :iterations
  ;; (a live DSPy input), since user-context is a cached stable-key built once
  ;; per turn and would not re-render mid-loop.
  (let [sections (cond-> {}
                   (and turn-info (not (str/blank? turn-info)))
                   (assoc :turn-info turn-info)

                   ;; M9: parent-trail (last K previous-turns from the
                   ;; caller) — present only on sub-agent calls.
                   (seq parent-trail)
                   (assoc :parent-trail
                          (str "## Parent Trail (last "
                               (count parent-trail) " turns from caller)\n"
                               (format-previous-turns parent-trail)))

                   (seq conversation)
                   (assoc :conversation-history
                          (str "## Conversation History\n"
                               (fmt/format-conversation conversation)))

                   (seq previous-turns)
                   (assoc :previous-turns
                          (str "## Previous Turns\n"
                               (format-previous-turns previous-turns)))

                   (seq live-artifacts)
                   (assoc :live-artifacts
                          (str "## Live Artifacts\n"
                               (format-live-artifacts live-artifacts))))
        section-order [:turn-info :parent-trail
                       :conversation-history :previous-turns :live-artifacts]
        content (if (seq sections)
                  (str/join "\n\n" (keep #(get sections %) section-order))
                  "")]
    (if return-breakdown?
      {:content content :sections sections :order section-order}
      content)))

;; ============================================================================
;; SectionAssembler — CoAct implementation
;; ============================================================================

(defn- truncate-snippet
  "Cut `s` to `max-w` chars with a trailing ellipsis."
  [s max-w]
  (let [s (str s)]
    (if (> (count s) max-w) (str (subs s 0 max-w) "…") s)))

(defn- format-in-flight-roster-line
  "Render a synthesized in-flight-roster record (still-pending background
   tasks) into a single line for cost estimation. Distinct shape so the
   roster does not look like a fresh iteration:

     `(N) [🚦 ACTIVE BG] task-X(clj, iter 2, 42s); task-Y(bash, iter 3, 17s)`"
  [{:keys [iteration tasks]}]
  (let [head (str "(" iteration ") [🚦 ACTIVE BG]")
        per (mapv (fn [{:keys [task-id lang submitted-iter age-s]}]
                    (str task-id "("
                         (case lang
                           "clojure" "clj"
                           "javascript" "js"
                           (or lang "?"))
                         ", iter " submitted-iter ", " age-s "s)"))
                  (or tasks []))]
    (if (seq per)
      (str head " " (str/join "; " per))
      head)))

(defn- format-async-completion-line
  "Render a synthesized async-completion record into a single line of the
   iterations-block. Distinct shape so the LLM doesn't confuse a delayed
   result with a fresh iteration:

     `(N) [↺ async from iter M] task-id=task-7 status=resolved => snippet`

   Falls back to `from iter ?` when the provenance metadata is missing."
  [{:keys [iteration code-results]}]
  (let [sources (->> code-results
                     (keep :from-iteration)
                     distinct
                     sort
                     vec)
        from-tag (if (seq sources)
                   (str "from iter"
                        (when (> (count sources) 1) "s")
                        " "
                        (str/join "," sources))
                   "from iter ?")
        entries (or code-results [])
        head (str "(" iteration ") [↺ async " from-tag "]")
        per-entry
        (mapv
         (fn [{:keys [task-id status result error]}]
           (let [status-s (or (some-> status name) "resolved")
                 payload (cond
                           (and (not (str/blank? error))
                                (= "" error)) nil
                           (not (str/blank? error)) (str "error=" (truncate-snippet error 100))
                           (not (str/blank? (str result))) (truncate-snippet result 100)
                           :else "")
                 prefix (str "task-id=" task-id " status=" status-s)]
             (cond-> prefix
               (not (str/blank? payload))
               (str " => " payload))))
         entries)]
    (if (seq per-entry)
      (str head " " (str/join " ; " per-entry))
      head)))

(defn- format-iterations-block
  "Render :iterations as a budget-trackable block. Each iteration becomes
   `(N) [channel] thought-snippet => result-snippet`. The LLM sees the
   full vector via DSPy serialization in the user message; this block is
   only used for cost estimation in the budget loop.

   Async-completion records (`:async-completion? true`) take a distinct
   single-line shape — see `format-async-completion-line`.
   In-flight-roster records (`:in-flight-roster? true`) likewise — see
   `format-in-flight-roster-line`."
  [iterations]
  (when (seq iterations)
    (->> iterations
         (map-indexed
          (fn [idx {:keys [iteration channel thought tool-results code-results
                           async-completion? in-flight-roster?
                           rejected-answer verdict feedback] :as rec}]
            (cond
              in-flight-roster?
              (format-in-flight-roster-line
               (assoc rec :iteration (or iteration (inc idx))))

              async-completion?
              (format-async-completion-line
               (assoc rec :iteration (or iteration (inc idx))))

              (= channel "evaluation")
              (let [n (or iteration (inc idx))
                    snip (let [s (str rejected-answer)]
                           (if (> (count s) 200) (str (subs s 0 200) "…") s))]
                (str "(" n ") [evaluation] REJECTED " (or verdict "") ": "
                     (str feedback)
                     (when-not (str/blank? snip)
                       (str " | your rejected answer: " snip))))

              :else
              (let [n (or iteration (inc idx))
                    ch (or channel "")
                    th (let [s (str thought)]
                         (if (> (count s) 200) (str (subs s 0 200) "…") s))
                    result-snip
                    (cond
                      (seq tool-results)
                      (str/join "; "
                                (map (fn [{:keys [tool-name tool-result]}]
                                       (str tool-name "→"
                                            (let [r (str tool-result)]
                                              (if (> (count r) 100)
                                                (str (subs r 0 100) "…")
                                                r))))
                                     tool-results))
                      (seq code-results)
                      (str/join "; "
                                (map (fn [{:keys [lang result]}]
                                       (str lang ":"
                                            (let [r (str result)]
                                              (if (> (count r) 100)
                                                (str (subs r 0 100) "…")
                                                r))))
                                     code-results))
                      :else "")]
                (cond-> (str "(" n ") [" ch "] " th)
                  (not (str/blank? result-snip))
                  (str " => " result-snip))))))
         (str/join "\n"))))

(defn- summarize-iterations-deterministic
  "Pure-Clojure recap of older iterations. Used by :collapse-iterations
   to replace the dropped iteration prefix with a single summary entry."
  [iterations]
  (or (format-iterations-block iterations) ""))

(defn- coact-strategies
  "Per-turn compaction strategies for the CoAct section-budget loop.
   Each closure mutates the relevant slice of `@st-memory` and returns
   a section map reflecting the post-mutation state."
  [st-memory]
  {:bump-previous-turns
   (fn [secs]
     (let [pt (or (:previous-turns @st-memory) [])]
       (if (empty? pt)
         (dissoc secs :previous-turns)
         (let [trimmed (vec (rest pt))]
           (swap! st-memory assoc :previous-turns trimmed)
           (if (seq trimmed)
             (assoc secs :previous-turns
                    (str "## Previous Turns\n"
                         (format-previous-turns trimmed)))
             (dissoc secs :previous-turns))))))

   ;; M9: parent-trail compactor — drop oldest parent turn.
   :bump-parent-trail
   (fn [secs]
     (let [pt (or (:parent-trail @st-memory) [])]
       (if (empty? pt)
         (dissoc secs :parent-trail)
         (let [trimmed (vec (rest pt))]
           (swap! st-memory assoc :parent-trail trimmed)
           (if (seq trimmed)
             (assoc secs :parent-trail
                    (str "## Parent Trail (last "
                         (count trimmed) " turns from caller)\n"
                         (format-previous-turns trimmed)))
             (dissoc secs :parent-trail))))))

   :shrink-conversation
   (fn [secs]
     (let [conv (or (:conversation @st-memory) [])]
       (if (empty? conv)
         (dissoc secs :conversation-history)
         (let [trimmed (vec (drop 2 conv))]
           (swap! st-memory assoc :conversation trimmed)
           (if (seq trimmed)
             (assoc secs :conversation-history
                    (str "## Conversation History\n"
                         (fmt/format-conversation trimmed)))
             (dissoc secs :conversation-history))))))

   ;; Pin-aware: evict the OLDEST droppable artifact first — droppable =
   ;; not :pinned? and not :origin :system. System reference files
   ;; (CLAUDE.md/AGENTS.md) and explicitly-pinned artifacts are protected.
   ;; When nothing is droppable the section is returned unchanged; because the
   ;; :live-artifacts policy sets :keep-floor? true, `enforce` keeps that floor
   ;; (recorded :kept-floor) instead of dropping the section, so pinned/system
   ;; artifacts are never evicted. See docs/design/compaction.md.
   :drop-live-artifacts
   (fn [secs]
     (let [arts     (vec (or (:live-artifacts @st-memory) []))
           drop-idx (->> arts
                         (map-indexed vector)
                         (some (fn [[i a]]
                                 (when-not (or (:pinned? a)
                                               (= (:origin a) :system))
                                   i))))]
       (cond
         (empty? arts)   (dissoc secs :live-artifacts)
         (nil? drop-idx) secs ; only pinned/system remain — let enforce drop
         :else
         (let [trimmed (into (subvec arts 0 drop-idx)
                             (subvec arts (inc drop-idx)))]
           (swap! st-memory assoc :live-artifacts trimmed)
           (if (seq trimmed)
             (assoc secs :live-artifacts
                    (str "## Live Artifacts\n"
                         (format-live-artifacts trimmed)))
             (dissoc secs :live-artifacts))))))

   ;; :iterations strategy (M4). Keeps the most recent 3 iterations
   ;; verbatim and replaces older entries with a single "summary"
   ;; iteration record carrying a deterministic recap in its :thought
   ;; field. Mutates st-memory's :iterations (so the LLM sees the
   ;; compacted vector via DSPy) and returns an updated section text
   ;; for the budget loop to track.
   :collapse-iterations
   (fn [secs]
     (let [iters (or (:iterations @st-memory) [])
           keep-n 3]
       (if (<= (count iters) keep-n)
         ;; Nothing to compact — return unchanged. enforce will drop
         ;; the section. The LLM still sees the full iterations vector
         ;; via DSPy serialization.
         secs
         (let [recent (vec (take-last keep-n iters))
               older  (vec (drop-last keep-n iters))
               summary-text (summarize-iterations-deterministic older)
               new-iters (into [{:iteration 0
                                 :channel "summary"
                                 :thought summary-text
                                 :tool-results []
                                 :code-results []}]
                               recent)]
           (swap! st-memory assoc :iterations new-iters)
           (assoc secs :iterations
                  (or (format-iterations-block new-iters) ""))))))

   ;; Tiered :tools strategy (M5). Each invocation disables the next
   ;; tier in `tier-order` (cheapest first) and re-renders the section.
   ;; When all tiers are disabled, returns `secs` unchanged so enforce
   ;; drops the section. Requires `:tools-section-config` to be present
   ;; in st-memory (stashed by coact-init-action).
   :tools-tier
   (fn [secs]
     (let [config (:tools-section-config @st-memory)
           current-disabled (or (:tools-disabled-tiers @st-memory) #{})
           ;; Cheapest first (usage guides is a small static table; agent-tools
           ;; details is the largest variable block).
           tier-order [:usage-guides :tool-context-overlay
                       :function-index :agent-tools-details]
           next-tier (some (fn [t] (when-not (current-disabled t) t)) tier-order)]
       (if (or (nil? config) (nil? next-tier))
         secs
         (let [new-disabled (conj current-disabled next-tier)
               _ (swap! st-memory assoc :tools-disabled-tiers new-disabled)
               new-text (build-tools-section
                         (assoc config :disabled-tiers new-disabled))]
           (if new-text
             (assoc secs :tools new-text)
             (dissoc secs :tools))))))})

(defrecord CoActAssembler []
  sa/SectionAssembler
  (sections [_ state]
    (let [sys (coact-system-context
               (select-keys state [:sandbox-bindings :instruction
                                   :agent-context :tool-context :agent-tools
                                   :include-function-directory? :system-info
                                   :tools-disabled-tiers
                                   :brainyard-instructions :project-memory
                                   :execution-model])
               :return-breakdown? true)
          usr (coact-user-context
               (select-keys state [:conversation :previous-turns
                                   :live-artifacts :turn-info :parent-trail])
               :return-breakdown? true)]
      (merge (:sections sys) (:sections usr))))
  (system-order [_]
    [:role :system-info :execution-model
     :channel-routing :tool-call-format :code-blocks-format
     :sandbox-context-accessor :tools
     :critical-rules :large-results-playbook
     :instruction :agent-context
     :project-instructions :project-memory :user-instructions
     :footer])
  (user-order [_]
    [:turn-info :parent-trail
     :conversation-history :previous-turns :live-artifacts])
  (policies [_] cb/default-section-policies)
  (strategies [_ st-memory] (coact-strategies st-memory)))

(def coact-assembler
  "Stateless CoAct section assembler. All per-turn inputs flow through
   protocol method calls."
  (->CoActAssembler))

;; ============================================================================
;; Shared helpers
;; ============================================================================

(defn- trace-agent
  "Send a trace message to the agent's session thinking (TUI display)."
  [agent depth content]
  (when agent
    (proto/update-session-data
     agent {:trace {:agent-id (:agent-id agent)
                    :depth depth
                    :content content}})))

(defn- truncate-iter-field
  "Truncate an iteration field for DSPy input using truncate-to-file."
  [s class]
  (let [s (cond (nil? s) ""
                (string? s) s
                :else (pr-str s))]
    (clj-sandbox/truncate-to-file s (config/get-config :max-output-chars) class
                                  :label class)))

;; ============================================================================
;; BT Actions — CoAct-native
;; ============================================================================

(defn coact-init-action
  "BT action: initialize the CoAct sandbox and st-memory.

   Builds:
   - sandbox (SCI with tool/usage bindings; sub-LLM dispatch rides the
     auto-tool-bound query$llm (single :prompt or batched :prompts). Clone-self
     dispatch via query$clone is gated to rlm-agent only — it surfaces in the
     sandbox just for rlm). Reuses the previous turn's sandbox
     when one
     is attached to the agent (so user
     `def`s from prior turns persist); otherwise creates a fresh one,
     optionally seeded from `:sandbox-state` when `enable-sandbox-persistence`
     is on.
   - :system-context (system-wide rules + function directory)
   - :user-context (brainyard instructions + conversation + previous turns + live artifacts)
   Seeds :iterations [], :answer \"\", :terminated false, :iteration-count 0."
  [{:keys [st-memory agent opts] :as context}]
  (let [st @st-memory

        ;; Assemble fields with system-context overlay. assemble-field
        ;; reads BASE from the BT st-memory and overlays L1
        ;; :system-context entries on top.
        instruction   (if agent
                        (context/assemble-field agent :system-context :instruction)
                        (:instruction st))
        agent-context (if agent
                        (context/assemble-field agent :system-context :agent-context)
                        (:agent-context st))
        tool-context  (if agent
                        (context/assemble-field agent :system-context :tool-context)
                        (:tool-context st))

        ;; Recalled memory is primed by the top-level prepare-recalled-memory
        ;; action (see ai.brainyard.agent.common.context-actions) before this
        ;; init runs.
        question        (:question st)
        recalled-memory (:recalled-memory st)

        ;; Previous-turns chain
        previous-turns (or (:previous-turns st) [])

        ;; Runtime config + sandbox-reuse/restore inputs
        cfg-snap (config/get-config-snapshot agent)
        enable-sandbox-persistence (:enable-sandbox-persistence cfg-snap)
        ;; sandbox (live SCI context) lives in !state; sandbox-state (extracted
        ;; user vars) lives in !session so it persists with the session and
        ;; supports restoration across agent instances.
        existing-sandbox (when agent (get-in @(:!state agent) [:sandbox]))
        sandbox-state (when agent (get-in @(:!session agent) [:sandbox-state]))
        restore-bindings (when (and (nil? existing-sandbox)
                                    sandbox-state
                                    enable-sandbox-persistence)
                           (clj-sandbox/build-restore-bindings sandbox-state))

        ;; Session boot: load this project's persisted user-defined tools
        ;; (idempotent per process) BEFORE make-tool-bindings, so they register
        ;; in !tool-defs and auto-bind as `user$tool$<name>` symbols this turn.
        _ (when (nil? existing-sandbox)
            (try
              (ut/ensure-loaded! :dirs (sb-bind/get-dirs agent)
                                 :extra-bindings (sb-bind/auto-tool-bindings agent))
              (catch Exception e
                (mulog/warn ::load-user-tools-failed :error (ex-message e))))
            ;; Session boot: rehydrate this project's persisted user hooks
            ;; (idempotent per process) so they fire on the existing hook
            ;; registry this turn. Mirrors the user-tools loader above.
            (try
              (uh/ensure-loaded! :dirs (sb-bind/get-dirs agent)
                                 :extra-bindings (sb-bind/auto-tool-bindings agent))
              (catch Exception e
                (mulog/warn ::load-user-hooks-failed :error (ex-message e))))
            ;; Session boot: register this project's persisted user-defined
            ;; agents (idempotent per process) so they are routable / callable
            ;; this turn. No sandbox to rehydrate — an agent has no eval-able
            ;; body, only prose — so this is just "read each dir, register".
            (try
              (ua/ensure-loaded! :dirs (sb-bind/get-dirs agent))
              (catch Exception e
                (mulog/warn ::load-user-agents-failed :error (ex-message e)))))

        ;; Build sandbox bindings (tool + usage + optional restore). Sub-LLM
        ;; dispatch (query$llm — single :prompt or batched :prompts) is a
        ;; defcommand in agent.common.commands and surfaces in the sandbox via
        ;; the auto-tool-binding path inside make-tool-bindings. Clone-self
        ;; dispatch (query$clone) is gated to rlm-agent via :tool-use-control,
        ;; so it only binds for rlm. The legacy notes API was removed in the
        ;; L1 simplification refactor.
        bindings (merge (sb-bind/make-tool-bindings agent)
                        (or restore-bindings {})
                        (sb-bind/make-usage-bindings)
                        {'now (with-meta
                                (fn [] (sys-info/now-snapshot))
                                {:doc "Current wall-clock as {:wall-time-iso :tz-iana :tz-offset-minutes}. Use this when you need the current time mid-turn without forcing the system prompt to re-render."
                                 :arglists '([])
                                 :category :system})})

        ;; Sandbox-side context map for context-accessors (context-get, etc.).
        ;; `:recalled-memory` is wired as a direct DSPy signature input;
        ;; `:previous-turns` is rendered into :user-context (system message),
        ;; so neither needs to be exposed via sandbox `context-get`.
        sandbox-context (cond-> {}
                          (seq restore-bindings)
                          (assoc :restored-vars (mapv name (keys restore-bindings)))
                          agent (assoc :agent-state
                                       (sb-bind/build-agent-state-snapshot agent)))

        synthetic-keys {}

        ;; Reuse previous turn's sandbox when available (so user `def`s
        ;; persist across turns); otherwise create a fresh one.
        sandbox (if existing-sandbox
                  (do (clj-sandbox/update-context! existing-sandbox sandbox-context)
                      (clj-sandbox/update-bindings! existing-sandbox bindings)
                      (clj-sandbox/clear-history!   existing-sandbox :keep-last 5)
                      existing-sandbox)
                  (clj-sandbox/create-sandbox
                   :context        sandbox-context
                   :bindings       bindings
                   :synthetic-keys synthetic-keys
                   :interop        (config/resolve-sandbox-interop agent)))

        ;; Load brainyard instructions once per turn
        agent-dirs (sb-bind/get-dirs agent)
        brainyard-instructions (when agent-dirs
                                 (try (config/load-brainyard-instructions agent-dirs)
                                      (catch Exception _ nil)))

        ;; Project-scoped file memory: re-seed the index.md contents each
        ;; turn so on-disk edits (including the LLM's own writes last turn)
        ;; show live. nil disables the `## Project Memory` section entirely.
        project-memory-input
        (when (and agent-dirs (get cfg-snap :enable-project-memory true))
          (try (-> (config/load-project-memory-index agent-dirs)
                   (assoc :max-chars (get cfg-snap :project-memory-max-chars 4000)))
               (catch Exception _ nil)))

        ;; Live artifacts: merge system-seeded reference files (re-derived
        ;; fresh each turn from config :reference-artifact-paths, never
        ;; persisted) over the persisted dynamic registry. Dynamic
        ;; descriptors arrive via (:live-artifacts st) — the BT reset copies
        ;; them from st-memory-init each turn, so LLM-added artifacts survive
        ;; across turns within a session. Resolve loads :file content fresh
        ;; and drops missing files; the result is written back to the
        ;; per-turn st-memory so the :drop-live-artifacts budget strategy can
        ;; re-render from it.
        dynamic-artifacts (or (:live-artifacts st) [])
        system-artifacts  (if agent-dirs
                            (try (config/reference-artifact-descriptors
                                  agent-dirs
                                  (or (get cfg-snap :reference-artifact-paths)
                                      ["CLAUDE.md" "AGENTS.md"])
                                  ;; Dedupe against BRAINYARD.md (loaded as
                                  ;; :system-context instructions) so a
                                  ;; CLAUDE.md/AGENTS.md linked to the same file
                                  ;; isn't loaded twice.
                                  :exclude-identities
                                  (:instruction-identities brainyard-instructions))
                                 (catch Exception _ []))
                            [])
        artifact-max-chars (or (get cfg-snap :live-artifact-max-chars) 4000)
        resolved-artifacts (resolve-artifacts
                            (merge-artifact-descriptors system-artifacts
                                                        dynamic-artifacts)
                            artifact-max-chars)
        _ (swap! st-memory assoc :live-artifacts resolved-artifacts)

        ;; Turn identity is read upfront so the per-turn :turn-info section
        ;; can be rendered before the assembler runs.
        turn-id     (:turn-id st)
        total-turns (:total-turns st)

        ;; :system-info — byte-stable across turns for the same agent/model/dir.
        ;; :turn-info   — per-turn wall-clock + turn counters (cache-friendly
        ;;                within a turn, regenerated every turn).
        system-info-text (sys-info/build-system-info-section
                          :agent agent
                          :depth (or (:depth context) 0)
                          :parent-agent-id (:parent-agent-id st))
        turn-info-text   (sys-info/build-turn-info-section
                          :turn-id turn-id
                          :total-turns total-turns)

        ;; M5: stash the tools-section config so the :tools-tier compact
        ;; strategy can re-render the section as it disables successive
        ;; tiers under budget pressure. Reset :tools-disabled-tiers so a
        ;; new turn starts with the full tools section.
        tools-section-config
        {:sandbox-bindings     bindings
         :agent-tools          (:tools st)
         :tool-context-overlay tool-context
         :include-directory?
         (boolean (get cfg-snap :include-function-directory false))}
        _ (swap! st-memory assoc
                 :tools-section-config tools-section-config
                 :tools-disabled-tiers #{})

        ;; Build the section map via the CoAct SectionAssembler. The
        ;; assembler returns the merged {section-kw text} map for both
        ;; message slots; system-order / user-order partition them when
        ;; recomposing each message string after enforce.
        assembler coact-assembler
        ;; M9: parent-trail flows through :st-memory-extra at sub-agent
        ;; setup and persists in st-memory across iterations within the
        ;; sub-agent's turn. Root agents have no parent — :parent-trail
        ;; is nil and the section is silently skipped.
        parent-trail (:parent-trail st)
        assembler-state {:sandbox-bindings bindings
                         :instruction      instruction
                         :agent-context    agent-context
                         :tool-context     tool-context
                         :agent-tools      (:tools st)
                         :include-function-directory?
                         (boolean (get cfg-snap :include-function-directory false))
                         :system-info     system-info-text
                         :tools-disabled-tiers #{}
                         :brainyard-instructions brainyard-instructions
                         :project-memory  project-memory-input
                         ;; Execution-model prompt section is keyed off
                         ;; the agent's :clj-backend config (the same key
                         ;; that selects the actual runtime — see
                         ;; agent-clj-backend / run-clj-sandbox-block / run-clj-nrepl-block). No
                         ;; separate :execution-model knob.
                         :execution-model (execution-model-for agent)
                         :conversation           (:conversation st)
                         :previous-turns         previous-turns
                         :live-artifacts         resolved-artifacts
                         :turn-info       turn-info-text
                         :parent-trail    parent-trail}
        merged-sections (sa/sections assembler assembler-state)
        sys-order       (sa/system-order assembler)
        usr-order       (sa/user-order assembler)
        merged-order    (sa/order assembler)
        strategies      (sa/strategies assembler st-memory)

        ;; ── Token budget enforcement ──────────────────────────────────
        ;; Strategies (from the assembler) trim the underlying state in
        ;; st-memory and re-render the affected section. They run
        ;; lowest-priority first.
        budget-enabled? (get cfg-snap :enable-context-budget true)
        budget-input
        {:max-context-tokens (or (get cfg-snap :max-context-tokens) 128000)
         :max-output-tokens  (or (get-in opts [:lm-config :max-tokens])
                                 (:max-tokens (config/get-config agent :lm-config))
                                 4096)
         :safety-ratio       (or (get cfg-snap :context-budget-safety-ratio)
                                 0.10)}
        budget-tokens (cb/model->budget budget-input)

        enforced (if budget-enabled?
                   (cb/enforce {:sections   merged-sections
                                :order      merged-order
                                :budget     budget-tokens
                                :policies   (sa/policies assembler)
                                :strategies strategies})
                   {:sections     merged-sections
                    :order        merged-order
                    :total-tokens (cb/total-tokens merged-sections merged-order)
                    :budget       budget-tokens
                    :compactions  []
                    :over-budget? false})

        ;; Recompose the two prompt strings from the post-enforcement
        ;; sections, preserving each side's original order. System
        ;; sections never have compact strategies, so they pass through
        ;; unchanged; user sections may have been trimmed or dropped.
        system-context (cb/compose (:sections enforced) sys-order)
        user-context   (cb/compose (:sections enforced) usr-order)

        prompt-token-breakdown
        (clj-llm/build-token-breakdown
         (select-keys (:sections enforced) merged-order))

        ;; Per-turn briefing (covers tools/plans + sandbox data directory).
        briefing (sb-bind/build-context-briefing sandbox-context
                                                 :dirs agent-dirs
                                                 :agent agent)

        _ (when agent
            (mulog/update-global-context!
             {:user-id (proto/user-id agent)
              :session-id (proto/session-id agent)
              :agent-id (proto/agent-id agent)
              :turn-id turn-id
              :total-turns total-turns}))]

    ;; Strategies above may have mutated :previous-turns / :conversation /
    ;; :live-artifacts in st-memory. Read the post-enforcement
    ;; previous-turns so the explicit reassign below does not clobber it.
    (let [final-previous-turns (or (:previous-turns @st-memory) previous-turns [])]
      (swap! st-memory assoc
             :sandbox          sandbox
             :sandbox-context  sandbox-context
             :existing-sandbox-reused (boolean existing-sandbox)
             :system-context   system-context
             :user-context     user-context
             :prompt-token-breakdown prompt-token-breakdown
             :context-briefing briefing
             :iterations       []
             ;; Uncapped mirror of :iterations for trajectory recording. Unlike
             ;; :iterations (capped to 10 + compacted for context budget), this
             ;; retains every iteration so trajectory.edn covers the full turn.
             :trajectory-iterations []
             :answer           ""
             :terminated       false
             :terminated-by    nil
             ;; Cleared at turn start so a prior turn's exhaustion flag can't
             ;; make this turn's answer look exhausted; coact-ensure-answer-action
             ;; re-sets it after the loop if this turn hits the iteration ceiling.
             :iterations-exhausted false
             ;; Per-turn answer-evaluation / in-loop refinement bookkeeping.
             ;; init runs ONCE per turn now (refinement no longer re-inits), so
             ;; these reset here rather than in a separate pre-loop action.
             :refinement-round   0
             :refinement-feedback nil
             :answer-complete    nil
             :previous-answer    nil
             :pending-answer     nil
             :answer-decision    nil
             :goal-achieved      nil
             :next-user-prompt   nil
             :consecutive-llm-failures 0
             :last-repair-iter nil
             :iteration-count  0
             :previous-turns   final-previous-turns
             :turn-id          turn-id
             :total-turns      total-turns
             :started-at       (System/currentTimeMillis)
             ;; Stash for the per-iteration rebudget action (M4).
             :cached-sections  (:sections enforced)
             :sys-order        sys-order
             :usr-order        usr-order
             :merged-order     merged-order
             :budget-tokens    (:budget enforced)))

    (trace-agent agent (or (:depth context) 0)
                 "CoAct initialized -- ThinkActCode loop starting...")

    ;; Runtime detached-task notification: install the always-on hooks once
    ;; (idempotent, runtime-only) and clear this turn's poll counters so the
    ;; deflect/auto-park threshold is per-turn.
    (auto-notify/ensure-global-hooks!)
    (auto-notify/reset-poll-counts! st-memory)

    (when agent
      (hooks/fire! :agent.context/budgeted
                   {:agent          agent
                    :phase          :init
                    :total-tokens   (:total-tokens enforced)
                    :budget         (:budget enforced)
                    :section-tokens (cb/section-tokens (:sections enforced)
                                                       merged-order)
                    :compactions    (:compactions enforced)
                    :over-budget?   (:over-budget? enforced)}))

    (mulog/log ::coact-init
               :agent-id (when agent (:agent-id agent))
               :turn-id turn-id
               :total-turns total-turns
               :previous-turns-count (count (or (:previous-turns @st-memory) []))
               :system-context-chars (count system-context)
               :user-context-chars   (count user-context)
               :budget-tokens        (:budget enforced)
               :prompt-tokens        (:total-tokens enforced)
               :budget-compactions   (count (:compactions enforced))
               :over-budget?         (:over-budget? enforced))

    bt/success))

;; Forward decls — defined further down with the code-block runners.
(declare delete-coact-tmp-file! project-terminal-task->eval-entry)
;; Owner-scoping predicate for the in-flight task surfaces — defined with
;; in-flight-coact-tasks below; used here by harvest-pending-tasks!.
(declare task-owned-by-agent?)

(defn harvest-pending-tasks!
  "Walk the task manager for any coact-tagged code-eval tasks that reached
   terminal state since the last iteration (i.e. tasks that previously
   soft-timed-out and have now finished); for each, synthesize a
   `:channel \"code\"` iteration record so the next LLM call sees the
   resolved result alongside the ongoing narrative.

   The synthesized record carries `:async-completion? true` and assigns
   `:iteration` to the *current* iteration count so it appears immediately
   before the new iteration's record. Each entry's `:status` is set to
   `:resolved`. Harvested tasks are *marked* `:metadata :harvested? true`
   (rather than removed) so they remain visible in `/tasks` and the task list
   view — the marker is what prevents double-harvest. Any leaked tmp-files
   (from run-script-block) are cleaned up.

   Returns the vector of surfaced task-id keywords (or nil) so the caller —
   which holds the `agent` — can report them to auto-notify via `mark-surfaced!`
   (a completion the live loop just folded in is dropped from the idle-resume
   inbox, avoiding a redundant auto-ask).

   With `agent`, only tasks owned by that agent instance are harvested into its
   narrative (see `task-owned-by-agent?`) — a sub-agent must not fold in the
   parent task that wraps it. Nil agent → no owner filter."
  [st-memory agent]
  (let [{:keys [iteration-count]} @st-memory
        mgr (task-mgr/get-default-manager)]
    (when mgr
      (let [terminal-set #{:completed :failed :cancelled}
            coact-pending? (fn [t] (some? (get-in t [:metadata :coact/pending-from-iter])))
            harvested?     (fn [t] (get-in t [:metadata :harvested?]))
            done-coact?    (fn [t] (and (coact-pending? t)
                                        (terminal-set (:status t))
                                        (not (harvested? t))
                                        (task-owned-by-agent? t agent)))
            candidates (try (filterv done-coact? (tp/list-tasks mgr))
                            (catch Exception e
                              (mulog/warn ::harvest-list-tasks-error
                                          :error (.getMessage e))
                              []))]
        (when (seq candidates)
          (let [tool?   #(some? (get-in % [:metadata :coact/tool-id]))
                code-cs (filterv (complement tool?) candidates)
                tool-cs (filterv tool? candidates)
                code-entries
                (mapv (fn [t]
                        (delete-coact-tmp-file! (get-in t [:metadata :coact/tmp-file]))
                        (let [base (project-terminal-task->eval-entry t)]
                          (sanitize-eval-entry
                           (assoc base
                                  :status   :resolved
                                  :task-id  (clojure.core/name (:id t))
                                  :from-iteration (get-in t [:metadata :coact/pending-from-iter])))))
                      code-cs)
                tool-entries
                (mapv (fn [t]
                        (let [raw-result (get-in t [:result :result])
                              result-str (cond
                                           (string? raw-result) raw-result
                                           (nil? raw-result)    ""
                                           :else (with-out-str (print raw-result)))]
                          {:tool-name   (get-in t [:metadata :coact/tool-id])
                           :tool-args   (get-in t [:metadata :coact/tool-args])
                           :tool-result result-str
                           :status      :resolved
                           :task-id     (clojure.core/name (:id t))
                           :from-iteration (get-in t [:metadata :coact/pending-from-iter])}))
                      tool-cs)
                ids     (mapv #(clojure.core/name (:id %)) candidates)
                sources (->> candidates
                             (keep #(get-in % [:metadata :coact/pending-from-iter]))
                             (distinct) (sort) (vec))
                channel (cond
                          (and (seq code-cs) (seq tool-cs)) "mixed"
                          (seq tool-cs) "tool"
                          :else "code")
                record  {:iteration         iteration-count
                         :thought           (str "(async "
                                                 (case channel
                                                   "code"  "eval"
                                                   "tool"  "tool"
                                                   "mixed" "eval+tool")
                                                 (when (> (count candidates) 1) "s")
                                                 " from iter"
                                                 (when (> (count sources) 1) "s")
                                                 " "
                                                 (if (seq sources)
                                                   (str/join "," sources)
                                                   "?")
                                                 " completed: "
                                                 (str/join ", " ids) ")")
                         :channel           channel
                         :tool-results      tool-entries
                         :code-results      code-entries
                         :async-completion? true}]
            ;; Mark harvested so we never project them twice. The task stays
            ;; in the registry (visible in /tasks and the activity panel)
            ;; instead of being removed — the :harvested? flag is what the
            ;; candidates filter checks to avoid double-harvest.
            (doseq [t candidates]
              (try (swap! task-mgr/!tasks update (:id t)
                          (fn [existing]
                            (when existing
                              (assoc-in existing [:metadata :harvested?] true))))
                   (catch Exception _)))
            (swap! st-memory update :iterations (fnil conj []) record)
            ;; Uncapped mirror for trajectory recording.
            (swap! st-memory update :trajectory-iterations (fnil conj []) record)
            (mulog/info ::async-completions-harvested
                        :count (count candidates)
                        :iteration iteration-count
                        :task-ids ids
                        :from-iterations sources)
            ;; Return the surfaced ids so the caller (which holds the agent)
            ;; can drop them from the auto-notify idle-resume inbox.
            (mapv :id candidates)))))))

(def ^:private in-flight-roster-thought
  "Section-title text the LLM sees verbatim in the synthesized in-flight
   roster record. Prescriptive on purpose — the model should not need to
   derive 'this task is still running' from scanning :pending entries in
   prior iterations."
  (str "🚦 ACTIVE BACKGROUND TASKS — these tool calls / code blocks are STILL RUNNING."
       " DO NOT re-call the same tool or re-emit the same code. To handle them,"
       " pick ONE per task: (A) task$wakeup :task-id to YIELD — end the turn and be"
       " AUTO-RESUMED next turn when the task finishes (frees the session, best for"
       " a long wait), or (B) task$wait :task-id :timeout-ms to HOLD — block and"
       " continue this turn (best for a short wait; on timeout, wait again or switch"
       " to task$wakeup)."
       " Resolved results also arrive as [↺ async-completion] records."
       " Use task$detail (add :last-n N) to peek at the output tail mid-flight,"
       " and task$cancel only to stop a task."))

(defn- task-owned-by-agent?
  "True when task `t` belongs in `agent`'s in-flight task surfaces — i.e. it
   was created by that exact agent instance, OR it carries no owner tag at all
   (legacy/untagged tasks stay visible to everyone — backward-compatible). The
   owner is the creating agent's INSTANCE id (`proto/agent-id`), not its
   session-id: a sub-agent inherits its parent's session-id, so only the
   instance id distinguishes a skill-agent's tasks from the parent task that
   wraps it. `agent` nil → no filter (every task passes)."
  [t agent]
  (let [owner (get-in t [:metadata :coact/owner-agent-id])]
    (or (nil? owner)
        (nil? agent)
        (= owner (proto/agent-id agent)))))

(defn in-flight-coact-tasks
  "Return coact-tagged tasks that are still running (not terminal, not
   harvested). With `agent`, scopes to tasks owned by that agent instance
   (see `task-owned-by-agent?`) so a sub-agent doesn't surface the parent
   task that wraps it. Zero-arg / nil agent → no owner filter."
  ([] (in-flight-coact-tasks nil))
  ([agent]
   (let [mgr (task-mgr/get-default-manager)]
     (when mgr
       (let [terminal-set #{:completed :failed :cancelled}]
         (try (filterv (fn [t]
                         (and (some? (get-in t [:metadata :coact/pending-from-iter]))
                              (not (terminal-set (:status t)))
                              (not (get-in t [:metadata :harvested?]))
                              (task-owned-by-agent? t agent)))
                       (tp/list-tasks mgr))
              (catch Exception _ [])))))))

(defn- resolve-tool-entries
  "Resolve pending tool-result entries by matching the task-id embedded in the
   marker string against terminal tasks. Used for both :tool-results (coact)
   and :actions (react)."
  [entries terminal-tasks]
  (mapv (fn [e]
          (if-let [t (and (string? (:tool-result e))
                          (str/includes? (or (:tool-result e) "") "STILL RUNNING")
                          (let [tid (second (re-find #"task-id=(\S+)" (:tool-result e)))]
                            (get terminal-tasks tid)))]
            (let [raw (:result t)
                  result-str (cond
                               (string? raw) raw
                               (nil? raw) ""
                               :else (with-out-str (print raw)))]
              (assoc e :tool-result result-str))
            e))
        (or entries [])))

(defn resolve-pending-entries!
  "Rewrite :pending entries in :iterations with completed task results so the
   LLM sees them as synchronous completions. For code-results: replaces the
   entry. For tool-results/actions: replaces the pending marker string,
   matching by task-id extracted from the marker.

   Returns the vector of resolved task-id keywords (or nil) so the caller can
   report them to auto-notify via `mark-surfaced!`.

   With `agent`, only resolves tasks owned by that agent instance (see
   `task-owned-by-agent?`). Nil agent → no owner filter."
  [st-memory agent]
  (let [mgr (task-mgr/get-default-manager)]
    (when mgr
      (let [terminal-set #{:completed :failed :cancelled}
            terminal-tasks
            (->> (tp/list-tasks mgr)
                 (filter (fn [t]
                           (and (some? (get-in t [:metadata :coact/pending-from-iter]))
                                (terminal-set (:status t))
                                (not (get-in t [:metadata :harvested?]))
                                (task-owned-by-agent? t agent))))
                 (reduce (fn [m t] (assoc m (clojure.core/name (:id t)) t)) {}))]
        (when (seq terminal-tasks)
          (swap! st-memory update :iterations
                 (fn [iters]
                   (mapv (fn [record]
                           (if (:in-flight-roster? record)
                             record
                             (cond-> record
                               (:code-results record)
                               (update :code-results
                                       (fn [entries]
                                         (mapv (fn [e]
                                                 (if-let [t (and (= :pending (:status e))
                                                                 (get terminal-tasks (:task-id e)))]
                                                   (let [base (project-terminal-task->eval-entry t)]
                                                     (merge base {:status :resolved
                                                                  :task-id (:task-id e)
                                                                  :from-iteration (:from-iteration e)}))
                                                   e))
                                               (or entries []))))
                               (:tool-results record)
                               (update :tool-results #(resolve-tool-entries % terminal-tasks))
                               (:actions record)
                               (update :actions #(resolve-tool-entries % terminal-tasks)))))
                         (or iters []))))
          ;; Mark harvested
          (doseq [[_ t] terminal-tasks]
            (try (swap! task-mgr/!tasks update (:id t)
                        (fn [ex] (when ex (assoc-in ex [:metadata :harvested?] true))))
                 (catch Exception _)))
          ;; Return resolved ids so the caller can mark them surfaced.
          (mapv :id (vals terminal-tasks)))))))

(defn inject-in-flight-roster!
  "Snapshot all coact-tagged code-eval tasks that are still mid-flight
   (status :running, has :coact/pending-from-iter, not :harvested?) and
   write ONE synthetic iteration record `:in-flight-roster? true` to the
   END of `:iterations`. Replaces any prior roster — at most one is ever
   present.

   Goal: give the LLM an explicit per-iteration roster of work it has
   already submitted, so it doesn't re-emit the same code block while
   the task is still running. The prescriptive in-line marker on the
   :pending eval-entry (see run-task-block) tells the LLM 'do not
   re-emit this code'; this roster reinforces it with a fresh top-level
   view that survives across iterations until the task resolves.

   With `agent`, the roster is scoped to that agent instance's own tasks
   (see `task-owned-by-agent?`) so a sub-agent doesn't list the parent task
   that wraps it. Nil agent → no owner filter."
  [st-memory agent]
  (let [{:keys [iteration-count]} @st-memory
        tasks (or (in-flight-coact-tasks agent) [])]
    (when (some? (task-mgr/get-default-manager))
      (let [now-ms (System/currentTimeMillis)
            roster (mapv (fn [t]
                           (let [tid    (clojure.core/name (:id t))
                                 lang   (get-in t [:metadata :coact/lang])
                                 from   (get-in t [:metadata :coact/pending-from-iter])
                                 code   (or (get-in t [:metadata :coact/code]) "")
                                 prev   (subs code 0 (min 80 (count code)))
                                 prev   (cond-> prev
                                          (> (count code) 80) (str "…"))
                                 started (or (:created-at t)
                                             (:start-time t))
                                 age-s  (when started
                                          (quot (- now-ms started) 1000))]
                             (cond-> {:task-id        tid
                                      :lang           lang
                                      :code-preview   prev
                                      :submitted-iter from}
                               age-s (assoc :age-s age-s))))
                         tasks)
            ;; Drop any previous in-flight-roster record before reconjing.
            cleaned-iters (vec (remove :in-flight-roster?
                                       (or (:iterations @st-memory) [])))]
        (if (seq roster)
          (let [record {:iteration         iteration-count
                        :thought           in-flight-roster-thought
                        :channel           "none"
                        :tool-results      []
                        :code-results      []
                        :tasks             roster
                        :in-flight-roster? true}]
            (swap! st-memory assoc :iterations (conj cleaned-iters record))
            (mulog/info ::in-flight-roster-injected
                        :count     (count roster)
                        :iteration iteration-count
                        :task-ids  (mapv :task-id roster)))
          ;; No in-flight tasks → drop any stale prior roster.
          (when (not= cleaned-iters (:iterations @st-memory))
            (swap! st-memory assoc :iterations cleaned-iters)))))))

(defn coact-inc-iter-action
  "BT action: increment the 1-based iteration counter and reset per-iteration
   channel/results scratch fields so the router sees a clean slate.
   Clears :eval-display so a non-code iteration (answer/tool-only) never
   re-renders the previous iteration's code output.
   Clears :last-reasoning so the DSPy CoT layer can re-populate it this turn
   (the iteration record's `:thought` is sourced from :last-reasoning, not
   from a signature output field).

   Also harvests any async-survived pending code-eval tasks (soft-timed-out
   in a prior iteration but kept running by the task manager's detach
   watcher) and folds the resolved results into :iterations so the next LLM
   call observes them. After harvest, refreshes the in-flight-roster
   record so the next LLM call has an explicit list of still-running
   tasks (anti-re-emission)."
  [{:keys [st-memory agent]}]
  (swap! st-memory
         (fn [m]
           (-> m
               (update :iteration-count (fnil inc 0))
               (assoc :display-stage     :iteration-start
                      :tool-calls        []
                      :code-blocks       ""
                      :last-reasoning    nil
                      :last-channel      nil
                      :last-tool-results []
                      :last-code-results []
                      :eval-display      nil
                      ;; Reset the answer-channel self-assessment outputs so an
                      ;; optional output the model omits this iteration can't
                      ;; leak a prior iteration's value into the answer path /
                      ;; the hybrid refine gate (capture-answer-meta reads these
                      ;; fresh). :answer-decision is the per-answer routing key.
                      :goal-achieved     nil
                      :next-user-prompt  nil
                      :answer-decision   nil
                      ;; Clear any stale DSPy error from a prior iteration so
                      ;; coact-repair-action only reads THIS iteration's error.
                      :dspy-error        nil))))
  ;; Harvest folds resolved background tasks into :iterations and returns the
  ;; surfaced ids; reporting them to auto-notify drops them from the idle-resume
  ;; inbox so the turn-settle flush won't re-announce an already-seen result.
  (auto-notify/mark-surfaced! agent (harvest-pending-tasks! st-memory agent))
  (inject-in-flight-roster! st-memory agent)
  bt/success)

(defn coact-await-pending-action
  "BT action: hold the iteration loop if there are in-flight coact tasks.
   Disabled by default (:enable-iteration-hold false) — the LLM polls via
   task$wait instead. When enabled, blocks with a poll loop that wakes on
   :task/completed. When all pending tasks resolve, rewrites the :pending
   entries in :iterations with the completed results."
  [{:keys [st-memory agent]}]
  (if-not (config/get-config agent :enable-iteration-hold)
    bt/success
    (let [pending (in-flight-coact-tasks agent)]
      (if (empty? pending)
        bt/success
        (let [max-wait  (or (config/get-config agent :hold-max-wait-ms) 300000)
              !wake     (atom false)
              hook-key  (keyword "await-pending" (str (System/currentTimeMillis)))
              check-fn  (fn []
                          (when (empty? (in-flight-coact-tasks agent))
                            (reset! !wake true)))]
          (hooks/register-hook! :task/completed hook-key
                                (fn [_] (check-fn))
                                :source :await-pending)
          (hooks/fire! :agent.iteration/hold
                       {:agent agent
                        :count (count pending)
                        :task-ids (mapv #(clojure.core/name (:id %)) pending)})
          (check-fn)
          (try
            (loop [elapsed 0]
              (when (and (not @!wake)
                         (not (:terminated @st-memory))
                         (< elapsed max-wait))
                (Thread/sleep (long 500))
                (recur (+ elapsed 500))))
            (finally
              (hooks/unregister-hook! :task/completed hook-key)))
          (auto-notify/mark-surfaced! agent (resolve-pending-entries! st-memory agent))
          (inject-in-flight-roster! st-memory agent)
          (hooks/fire! :agent.iteration/hold-complete {:agent agent})
          bt/success)))))

(defn coact-rebudget-action
  "BT action (M4): per-iteration token-budget re-enforcement.

   Each iteration grows `:iterations` in st-memory. To prevent the prompt
   from quietly drifting over budget mid-turn, this action:

     1. Refreshes the `:iterations` section text from the current
        st-memory state (so the budget loop can see its current cost).
     2. Re-runs `cb/enforce` with the same orders + strategies init
        used. The `:collapse-iterations` strategy may collapse older
        iterations into a single summary record; the user-context
        strategies (bump/shrink/drop) may also fire if other sections
        have grown.
     3. Re-composes :system-context / :user-context and stores them
        back in st-memory.
     4. Fires :agent.context/budgeted with :phase :rebudget so the
        observability layer can distinguish init from per-iter passes.

   Cadence is config key `:rebudget-every-n-iter` (default 10). When
   K > 1, intermediate iterations skip the enforce pass."
  [{:keys [st-memory agent]}]
  (let [st @st-memory
        rebudget-n (config/get-config agent :rebudget-every-n-iter)
        iter-count (:iteration-count st 0)
        budget-enabled? (config/get-config agent :enable-context-budget)
        cached-sections (:cached-sections st)
        sys-order (:sys-order st)
        usr-order (:usr-order st)
        merged-order (:merged-order st)
        budget (:budget-tokens st)]
    (when (and budget-enabled?
               cached-sections sys-order usr-order merged-order budget
               (pos? iter-count)
               (zero? (mod iter-count (max 1 rebudget-n))))
      (let [iters (or (:iterations st) [])
            iters-text (or (format-iterations-block iters) "")
            ;; :iterations is a budget-only slot — included in the
            ;; enforce order but never composed into the prompt strings.
            budget-order (conj (vec merged-order) :iterations)
            sections-with-iters (assoc cached-sections :iterations iters-text)
            strategies (sa/strategies coact-assembler st-memory)
            enforced (cb/enforce
                      {:sections   sections-with-iters
                       :order      budget-order
                       :budget     budget
                       :policies   (sa/policies coact-assembler)
                       :strategies strategies})
            new-sys (cb/compose (:sections enforced) sys-order)
            new-usr (cb/compose (:sections enforced) usr-order)
            new-breakdown (clj-llm/build-token-breakdown
                           (select-keys (:sections enforced) budget-order))]
        (swap! st-memory assoc
               :system-context         new-sys
               :user-context           new-usr
               :cached-sections        (:sections enforced)
               :prompt-token-breakdown new-breakdown)
        (when agent
          (hooks/fire! :agent.context/budgeted
                       {:agent          agent
                        :phase          :rebudget
                        :iteration      iter-count
                        :total-tokens   (:total-tokens enforced)
                        :budget         (:budget enforced)
                        :section-tokens (cb/section-tokens
                                         (:sections enforced) budget-order)
                        :compactions    (:compactions enforced)
                        :over-budget?   (:over-budget? enforced)}))))
    bt/success))

;; ---- Display actions (TUI stage hooks) ----

(defn coact-display-think-action
  [{:keys [st-memory]}]
  (swap! st-memory assoc :display-stage :think)
  bt/success)

(defn coact-display-code-action
  [{:keys [st-memory]}]
  (let [code-blocks (:code-blocks @st-memory)]
    (when (and code-blocks (not (str/blank? code-blocks)))
      (let [blocks (clj-sandbox/extract-all-code-blocks-multi code-blocks)
            code-display (mapv (fn [{:keys [code]}] {:code code}) blocks)]
        (swap! st-memory assoc
               :eval-display code-display
               :display-stage :code-display))))
  bt/success)

(defn coact-display-eval-action
  [{:keys [st-memory]}]
  (let [code-results (or (:last-code-results @st-memory) [])
        eval-display (mapv (fn [{:keys [code result output error]}]
                             (cond-> {:code code}
                               (and error (not (str/blank? error))) (assoc :error error)
                               (and output (not (str/blank? output))) (assoc :output output)
                               (and (or (nil? error) (str/blank? error))
                                    (some? result)) (assoc :result result)))
                           code-results)]
    (swap! st-memory assoc
           :eval-display eval-display
           :display-stage :eval-result))
  bt/success)

(defn coact-display-tools-action
  [{:keys [st-memory]}]
  (when (seq (:tool-calls @st-memory))
    (swap! st-memory assoc :display-stage :tool-calls))
  bt/success)

(defn coact-display-tool-results-action
  [{:keys [st-memory]}]
  (when (seq (:last-tool-results @st-memory))
    (swap! st-memory assoc :display-stage :tool-results))
  bt/success)

;; ---- Predicates ----

(defn coact-answer-non-blank?
  "Condition-fn: true iff :answer is a non-blank string in st-memory."
  [{:keys [st-memory]}]
  (let [a (:answer @st-memory)]
    (and (string? a) (not (str/blank? a)))))

(defn coact-has-code-blocks?
  "Condition-fn: true iff `:code-blocks` actually parses into at least
   one fenced code block via `clj-sandbox/extract-all-code-blocks-multi`.

   A naive non-blank check matches free-form prose the LLM sometimes
   writes into the `code-blocks` field (e.g. \"I will now write a Clojure
   code block to filter…\") even though no triple-backtick fence is
   present.  When that happens the code-eval path runs anyway, the parser
   returns 0 blocks, and `coact-code-eval-action` pushes a synthetic
   `\"No fenced code blocks found in code-blocks output.\"` error.  The
   LLM often keeps repeating the same prose, producing an :blocks 0 loop
   that only terminates at `max-iterations`.  Requiring an actual fence
   lets the router fall through to the tool path (usually empty in this
   case) and then to repair, where `coact-repair-action`'s :none-channel
   loop guard can escalate."
  [{:keys [st-memory]}]
  (let [cb (:code-blocks @st-memory)]
    (and (string? cb)
         (not (str/blank? cb))
         (boolean (seq (clj-sandbox/extract-all-code-blocks-multi cb))))))

(defn coact-has-tool-calls?
  "Condition-fn: true iff :tool-calls is non-empty."
  [{:keys [st-memory]}]
  (boolean (seq (:tool-calls @st-memory))))

;; ---- Channel stamp helper ----

(defn coact-stamp-channel
  "Factory: return a BT action that stamps :last-channel with the given channel.
   A populated channel is a productive iteration, so it also resets the
   malformed-output streak (:consecutive-llm-failures) — see
   `repair-malformed-output!`."
  [channel]
  (fn [{:keys [st-memory]}]
    (swap! st-memory assoc
           :last-channel channel
           :consecutive-llm-failures 0)
    bt/success))

(defn coact-stamp-answer-action
  "BT action for the terminal answer path. Stamps :last-channel :answer, marks
   :terminated true, and flips :display-stage to :eval-result so the TUI's
   answer watcher fires (matches CoAct's FINAL-termination signalling shape —
   see agent_tui/session.clj `final-code-shown?`).

   Preserves a pre-set :terminated-by (e.g. :llm-error from a malformed-output
   abort, which stamps :answer in the llm-guard slot *before* the router runs
   this Path A action) so the abort reason survives; defaults to :answer-channel
   for the normal answer path. coact-init-action clears :terminated-by per turn,
   so a prior turn's reason can't leak in.

   Clears :eval-display so the TUI's :display-stage :eval-result branch
   (session.clj render-eval-result) has nothing to render between the thought
   and the answer box. Without this, a prior iteration's eval-display would
   re-render here because our answer iteration produced no code."
  [{:keys [st-memory]}]
  (swap! st-memory
         (fn [m]
           (assoc m
                  :last-channel   :answer
                  :terminated     true
                  :terminated-by  (or (:terminated-by m) :answer-channel)
                  :consecutive-llm-failures 0
                  :eval-display   nil
                  :display-stage  :eval-result
                  ;; Finalize the answer-channel self-assessment (folded in from
                  ;; the old FinalizeAnswer pass). goal-achieved is coerced to a
                  ;; definite boolean; next-user-prompt rides through verbatim.
                  ;; Both are surfaced by the TUI + flow into the agent result.
                  :goal-achieved    (boolean (:goal-achieved m))
                  :next-user-prompt (or (:next-user-prompt m) ""))))
  bt/success)

;; ============================================================================
;; Tool Dispatch
;; ============================================================================

(defn- hook-blocked-result?
  "True when a raw tool result is the synthetic `{:hook-blocked true ...}`
   sentinel produced by `agent.core.tool/dispatch-with-hooks` on a `:block`
   verdict from a `:agent.tool-use/pre` hook (e.g. the loop guard)."
  [raw]
  (and (map? raw) (true? (:hook-blocked raw))))

(defn coact-tool-dispatch-action
  "BT action: dispatch tool-calls in parallel via pmap + call-tool-with-fast-eval.
   Per-tool hooks (tool-use/pre, tool-use/post) fire inside call-tool.
   Batch hooks (tool-calls/pre, tool-calls/post) fire here."
  [{:keys [st-memory agent opts depth] :as _context}]
  (let [depth (or depth 0)
        {:keys [tool-calls tools tools-fn-map]} @st-memory
        {:keys [max-tool-calls] :or {max-tool-calls 30}} opts
        iteration (:iteration-count @st-memory)
        fast-eval-ms (config/get-config agent :fast-eval-timeout-ms)
        auto-bg-ms   (config/get-config agent :auto-background-timeout-ms)
        batch (vec (take max-tool-calls tool-calls))
        call-opts {:agent agent :tools tools :tools-fn-map tools-fn-map
                   :fast-eval-ms (when (and fast-eval-ms (pos? fast-eval-ms))
                                   fast-eval-ms)
                   :auto-bg-ms auto-bg-ms
                   :from-iteration iteration}]
    (trace-agent agent depth
                 (format "%d tool-calls dispatched (max: %d)"
                         (count tool-calls) max-tool-calls))
    (when (seq batch)
      (hooks/fire! :agent.tool-calls/pre
                   {:agent agent :iteration iteration :calls batch}))
    (let [results
          (doall
           (pmap (fn [{:keys [tool-name tool-args]}]
                   (trace-agent agent depth
                                (format "calling **%s** with args %s"
                                        tool-name (pr-str tool-args)))
                   (let [raw (tool/call-tool-with-fast-eval
                              tool-name tool-args call-opts)
                         raw-str (cond
                                   (string? raw) raw
                                   (nil? raw)    ""
                                   :else         (with-out-str (print raw)))]
                     {:tool-name   tool-name
                      :tool-args   tool-args
                      :tool-result raw-str
                      :raw         raw}))
                 batch))
          blocked (first (filter #(hook-blocked-result? (:raw %)) results))
          clean   (mapv #(dissoc % :raw) results)]
      (when (seq batch)
        (hooks/fire! :agent.tool-calls/post
                     {:agent agent :iteration iteration
                      :calls batch :results clean}))
      (if blocked
        (do
          (trace-agent agent depth
                       (format "agent.tool-use/pre hook blocked dispatch (%s) — terminating"
                               (or (:reason (:raw blocked)) "no reason")))
          (mulog/warn ::tool-call-blocked-by-hook
                      :iteration (:iteration-count @st-memory)
                      :reason (:reason (:raw blocked))
                      :by (:by (:raw blocked)))
          (swap! st-memory assoc
                 :last-tool-results clean
                 :last-code-results []
                 :last-channel :tool
                 :answer (or (:answer (:raw blocked))
                             (str "*(Tool dispatch blocked: " (:reason (:raw blocked)) ")*"))
                 :terminated true))
        (do
          (swap! st-memory assoc
                 :last-tool-results clean
                 :last-code-results []
                 :last-channel :tool)
          (mulog/log ::tool-dispatch
                     :iteration (:iteration-count @st-memory)
                     :count (count clean)))))
    bt/success))

;; ============================================================================
;; Code Evaluation (multi-language; sequential or parallel)
;; ============================================================================

(def ^:private parallel-marker-re
  "Parallel toggle marker. Presence anywhere in :code-blocks flips the whole
   iteration into parallel mode."
  #"(?m)^\s*<!--\s*ParallelBlock\s*-->\s*$")

(defn- parallel-mode?
  [code-blocks]
  (boolean (re-find parallel-marker-re (or code-blocks ""))))

(defn- coact-scratch-path
  "Resolve a scratch-file path for a code block execution.
   Prefers `<project>/.brainyard/temp/coact-agent/scratch/`, falls back to `/tmp/`
   only when the project-scope dir can't be created."
  [ext]
  (let [stamp (str (System/currentTimeMillis) "-"
                   (Math/abs (.nextInt (java.util.Random.))) ext)]
    (if-let [agent-dir (config/brainyard-subdir!
                        (config/init-dirs!) "temp/coact-agent" :project)]
      (let [scratch (str agent-dir "/scratch")]
        (.mkdirs (java.io.File. scratch))
        (str scratch "/coact-" stamp))
      (str "/tmp/coact-" stamp))))

(defn- coact-verbatim-path
  "Resolve a scratch path for a verbatim content block. Like `coact-scratch-path`
   but folds in the LLM's sanitized filename hint (for readable artifacts and a
   correct extension), keeping a unique stamp prefix to avoid clobbering across
   iterations. Lives in the same scratch dir, so it rides the 24h GC sweep."
  [ext filename]
  (let [stamp (str (System/currentTimeMillis) "-"
                   (Math/abs (.nextInt (java.util.Random.))))
        fname (if filename (str stamp "-" filename) (str "verbatim-" stamp ext))]
    (if-let [agent-dir (config/brainyard-subdir!
                        (config/init-dirs!) "temp/coact-agent" :project)]
      (let [scratch (str agent-dir "/scratch")]
        (.mkdirs (java.io.File. scratch))
        (str scratch "/coact-" fname))
      (str "/tmp/coact-" fname))))

;; ----------------------------------------------------------------------------
;; Code-block runners
;;
;; Post-Step-F, all four langs (clojure / bash / python / javascript) flow
;; through the task manager:
;;   - clojure → :clj-sandbox-eval job type (uses sandbox via eval-sandbox-thunk)
;;   - bash/python/javascript → :bash job type (proc via ProcessBuilder)
;;
;; Deadline policy: every code-block task is awaited with `:on-timeout :detach`
;; using the agent's `:auto-background-timeout-ms` config. On detach the task
;; stays :running, its display-mode flips to :background, and the next
;; iteration harvests it via `harvest-pending-tasks!`. Cross-iteration
;; provenance rides on the task's `:metadata :coact/pending-from-iter` /
;; `:coact/lang` / `:coact/tmp-file`.
;; ----------------------------------------------------------------------------

(defn- task-label
  "One-line task name: prefix + muted code snippet, truncated with '...'."
  [prefix code]
  (let [max-len   60
        collapsed (str/replace code #"\n" " ")
        truncated? (> (count collapsed) max-len)
        snippet   (if truncated?
                    (str (subs collapsed 0 max-len) "...")
                    collapsed)]
    (str prefix (ansi/muted snippet))))

(defn- coact-task-metadata
  "Build the task :metadata map coact attaches to every code-eval task. Used
   by `harvest-pending-tasks!` to find soft-timed-out tasks and project them
   into the right eval-entry shape (the :lang isn't derivable from
   :job-type, since bash/python/js share :bash). `:coact/code` is the
   authoritative source for the LLM-facing rendering — the bash job-config
   only carries the dispatch command (`bash /…/scratch.sh`), and the
   clj-sandbox executor's `:code` on `r` only survives the sync path.

   Display-mode is NOT set here. The executor's pure-async contract returns
   `:detached` immediately for every task — that is an implementation
   detail. Only `await-task`'s `:on-timeout :detach` branch (the sync →
   async waiter hand-off) flips the per-task block to `:background`."
  [{:keys [lang from-iteration tmp-file code owner-agent-id]}]
  (cond-> {:coact/lang lang}
    owner-agent-id (assoc :coact/owner-agent-id owner-agent-id)
    from-iteration (assoc :coact/pending-from-iter from-iteration)
    tmp-file       (assoc :coact/tmp-file tmp-file)
    code           (assoc :coact/code code)))

(defn- delete-coact-tmp-file!
  "Best-effort cleanup for run-script-block's scratch file. No-op when nil."
  [tmp-file]
  (when tmp-file
    (try (java.io.File/.delete (java.io.File. ^String tmp-file))
         (catch Exception _))))

;; Safety net: any path that finalizes a coact-tagged task (cancel,
;; crash-recovery, detach-watcher) hits :task/completed even when the
;; harvest loop never gets to run. The hook deletes the scratch file
;; regardless of how the task ended. Idempotent — explicit deletes in
;; run-task-block / harvest-pending-tasks! are kept for promptness, and
;; this becomes a no-op when the file is already gone.
(defonce ^:private !coact-cleanup-hook-registered?
  (delay
    (hooks/register-hook!
     :task/completed
     ::coact-scratch-cleanup
     (fn [{:keys [task]}]
       (when-let [tmp-file (get-in task [:metadata :coact/tmp-file])]
         (delete-coact-tmp-file! tmp-file)))
     :source ::coact-agent)
    true))

;; Force registration at namespace load.
@!coact-cleanup-hook-registered?

(defn- maybe-mark-truncated
  "Prefix a one-line `[output truncated …]` marker to `output-str` when the
   task's on-disk output.log has more lines than survived the in-memory
   tail cache (`:output-lines`). The marker tells the LLM exactly how
   many lines are visible vs total and where to slurp the complete log.

   Returns `output-str` unchanged when no log file is available (e.g. the
   `persist/open-appender!` call silently degraded because dirs couldn't
   be resolved) or when total ≤ surviving. Always-prefix shape — the LLM
   sees the warning before the content, surviving any downstream re-
   truncation that might lop off a trailing footer."
  [task output-str]
  (let [tid         (:id task)
        output-file (task-persist/output-path nil tid)
        total       (when output-file (task-persist/line-count nil tid))
        surviving   (count @(:output-lines task))]
    (if (and output-file (> (or total 0) surviving))
      (str "[output truncated: showing last " surviving " of " total
           " lines — full log: " output-file "]\n"
           output-str)
      output-str)))

(defn- project-terminal-task->eval-entry
  "Project a terminal task (`:completed` / `:failed` / `:cancelled`) into the
   eval-entry shape consumed by format/sanitize. Single source of truth used
   by the sync-completion path AND by `harvest-pending-tasks!`. `lang` /
   `code` are sourced from task metadata + job-config since the task's
   `:result` only carries the executor's terminal map.

   For :clj-sandbox-eval the executor return shape is
     {:result <val> :code <code> :output <captured>}  ;; success
     or {:error <msg> :code <code> :output <captured>}  ;; error / FINAL.
   For :bash the lines fan out via :output-lines (no :output on the
   executor map). `code` is always sourced from `:metadata :coact/code`
   — the bash job-config only carries the dispatch command (`bash
   /…/scratch.sh`), not the script body."
  [task]
  (let [meta-map  (:metadata task)
        lang      (or (:coact/lang meta-map) "clojure")
        r         (:result task)
        job-type  (:job-type task)
        sandbox?  (= :clj-sandbox-eval job-type)
        nrepl?    (= :clj-nrepl-eval   job-type)
        clj?      (or sandbox? nrepl?)
        code      (or (:coact/code meta-map) "")
        output    (if clj?
                    ;; clojure: full output comes from the executor — sandbox
                    ;; from its StringWriter, nrepl from the harvested
                    ;; response. Neither goes through the tail cache, so no
                    ;; truncation marker applies.
                    (or (:output r) "")
                    ;; bash/python/javascript: the tail cache holds the last
                    ;; N lines; the on-disk log holds everything. When the
                    ;; log has more than the cache, prefix a marker pointing
                    ;; the LLM at the file path.
                    (maybe-mark-truncated task
                                          (str/join "\n" @(:output-lines task))))]
    (cond
      ;; :failed (executor returned {:error …}) — distinguish FINAL from
      ;; other errors via the marker the sandbox executor projects.
      (:error r)
      (cond-> {:lang   lang
               :code   code
               :result nil
               :output output
               :error  (if (and sandbox? (str/includes? (:error r) "FINAL termination"))
                         (str "FINAL is not supported in CoAct. "
                              "Populate the signature's `answer` output field "
                              "(JSON: \"answer\": \"...\") to terminate the loop.")
                         (:error r))}
        (= :cancelled (:status task)) (assoc :status :cancelled))

      ;; Success path. Sandbox returns a raw Clojure value on :result;
      ;; nrepl returns nREPL's :value (a string already pr-str-ed by the
      ;; server). Bash returns :exit-code (0 when no :error).
      :else
      (let [result-str (cond
                         sandbox?        (when-let [v (:result r)] (pr-str v))
                         nrepl?          (:result r)
                         (:exit-code r)  (str (:exit-code r))
                         :else           "0")]
        {:lang   lang
         :code   code
         :result result-str
         :output output
         :error  ""}))))

(defn- detached-task-marker
  "Marker appended to a code block's output when it detaches to the background.
   Keeps the anti-re-emission guard and shares the yield-vs-hold wait menu
   (task$wakeup / task$wait) with the tool detach marker via
   `tool/detached-wait-options`."
  [auto-bg-ms tid]
  (str "[detached to background after " auto-bg-ms
       "ms — task-id=" tid " STILL RUNNING."
       " DO NOT re-emit this code; the task is in flight."
       " Its result will be delivered to you AUTOMATICALLY when ready"
       " (folded into a later iteration, and the session auto-resumes if the"
       " turn has ended) — do NOT poll it repeatedly."
       (tool/detached-wait-options tid) "]"))

(defn- run-task-block
  "Shared runner: create + start a code-eval task, await up to `auto-bg-ms`,
   then either project to eval-entry (sync terminal path) or surface
   :status :pending + :task-id (auto-detach path — display-mode flips to
   :background and the next iteration harvests when the work completes).
   Used by both run-script-block and run-clj-sandbox-block.

   `start-fn` (zero-arg) creates + starts the task and returns the Task.
   On the pending path, scratch-file cleanup rides along on
   `:metadata :coact/tmp-file` so harvest deletes it when the task
   eventually terminates."
  [{:keys [start-fn lang code auto-bg-ms tmp-file]}]
  (try
    (let [task (start-fn)
          mgr  (task-mgr/get-default-manager)
          r    (task-cmds/await-task mgr (:id task) auto-bg-ms
                                     :on-timeout :detach)
          status (:status r)]
      (cond
        ;; Auto-background: task stays :running in background display mode,
        ;; harvest will fold the result back in a later iteration.
        (= "pending" status)
        (let [pre-output (or (:output r) "")
              tid        (:task-id r)]
          {:lang     lang
           :code     code
           :result   nil
           :output   (str pre-output
                          (when-not (str/blank? pre-output) "\n")
                          (detached-task-marker auto-bg-ms tid))
           :error    ""
           :status   :pending
           :task-id  tid})

        ;; Terminal (sync). Re-fetch the task in case await-task's snapshot
        ;; was taken slightly before finalize-task!'s :result write landed.
        ;; Mark the task :harvested? so the next iteration's
        ;; harvest-pending-tasks! doesn't fold this result in again as an
        ;; [↺ async-completion] record — the LLM already sees it as a
        ;; regular eval entry in this iteration's history.
        :else
        (let [t (tp/get-task mgr (:id task))]
          (delete-coact-tmp-file! tmp-file)
          (try (swap! task-mgr/!tasks update (:id task)
                      (fn [existing]
                        (when existing
                          (assoc-in existing [:metadata :harvested?] true))))
               (catch Exception _))
          (project-terminal-task->eval-entry t))))
    (catch Exception e
      (delete-coact-tmp-file! tmp-file)
      {:lang lang :code code :result nil :output ""
       :error (str (.getMessage e))})))

(defn- make-process-poll-fn
  "Build a make-on-poll for Process-based evals (bash/python/js).
   Returns (fn [on-output] -> poll-fn) suitable for adopt-detached!."
  [^Process proc reader-future eval-output]
  (let [!drained (atom 0)]
    (fn [on-output]
      (fn []
        (executor/drain-incremental-output! on-output eval-output !drained false)
        (if (not (.isAlive proc))
          (do (deref reader-future 5000 nil)
              (executor/drain-incremental-output! on-output eval-output !drained true)
              (let [exit-code (.exitValue proc)]
                (if (zero? exit-code)
                  {:exit-code 0}
                  {:error (str "Exit code: " exit-code) :exit-code exit-code})))
          tp/still-running)))))

(defn- adopt-and-await-task
  "Adopt a pre-existing eval into the task manager, then await up to
   the remaining auto-bg window. Returns an eval-entry (sync terminal or
   :status :pending for the harvest path). Callers provide make-on-poll
   and on-cancel closures appropriate to their eval backend."
  [{:keys [task-name job-type job-config metadata
           make-on-poll on-cancel
           lang auto-bg-ms fast-eval-ms started-at tmp-file !task-ref]}]
  (try
    (let [code      (or (:coact/code metadata) "")
          task      (task-mgr/adopt-detached!
                     task-name job-type job-config
                     {:metadata metadata :started-at started-at}
                     make-on-poll on-cancel)
          _         (when !task-ref (proto/update-task-id! !task-ref (:id task)))
          mgr       (task-mgr/get-default-manager)
          remaining (max 0 (- auto-bg-ms fast-eval-ms))
          r         (task-cmds/await-task mgr (:id task) remaining
                                          :on-timeout :detach)
          status    (:status r)]
      (cond
        (= "pending" status)
        (let [pre-output (or (:output r) "")
              tid        (:task-id r)]
          {:lang     lang
           :code     code
           :result   nil
           :output   (str pre-output
                          (when-not (str/blank? pre-output) "\n")
                          (detached-task-marker auto-bg-ms tid))
           :error    ""
           :status   :pending
           :task-id  tid})

        :else
        (let [t (tp/get-task mgr (:id task))]
          (delete-coact-tmp-file! tmp-file)
          (try (swap! task-mgr/!tasks update (:id task)
                      (fn [existing]
                        (when existing
                          (assoc-in existing [:metadata :harvested?] true))))
               (catch Exception _))
          (project-terminal-task->eval-entry t))))
    (catch Exception e
      (delete-coact-tmp-file! tmp-file)
      {:lang lang :code (or (:coact/code metadata) "") :result nil :output ""
       :error (str (.getMessage e))})))

(defn- run-script-block
  "Write code to a temp file and execute via shell. When `fast-eval-ms` is set,
   tries inline process execution first — only promotes to a tracked task on
   timeout. Returns an eval-entry map."
  [lang code auto-bg-ms & {:keys [from-iteration fast-eval-ms subagent? owner-agent-id]}]
  (let [ext (case lang "bash" ".sh" "python" ".py" "javascript" ".js" ".txt")
        cmd-prefix (case lang
                     "bash" "bash "
                     "python" "python3 "
                     "javascript" "node "
                     "cat ")
        tmp-file (coact-scratch-path ext)
        _ (spit tmp-file code)
        command (str cmd-prefix tmp-file)
        ;; Anchor the fence's relative paths at the git-root (project-dir),
        ;; matching the `bash` tool (tools.clj) and the prompt's own promise —
        ;; NOT the JVM cwd, which under `bb tui` is the projects/agent-tui-app/
        ;; subdir. Without this a ```bash``` fence doing `.brainyard/…` reads
        ;; the wrong tree (the main-agent path-divergence pattern).
        proj-dir (config/project-dir)]
    (if (or (proto/in-task-context?) subagent?)
      (try
        (let [pb (ProcessBuilder. ^"[Ljava.lang.String;"
                  (into-array String ["/bin/sh" "-c" command]))
              _ (.redirectErrorStream pb true)
              _ (.directory pb (java.io.File. ^String proj-dir))
              ^Process proc (.start pb)
              _ (.close (.getOutputStream proc))
              eval-output (java.io.StringWriter.)
              reader-future
              (future
                (let [^java.io.InputStreamReader reader
                      (java.io.InputStreamReader. (.getInputStream proc))
                      buf (char-array 1024)]
                  (try (loop []
                         (let [n (.read reader buf)]
                           (when (pos? n)
                             (.write eval-output buf 0 ^int n)
                             (recur))))
                       (catch java.io.IOException _)
                       (catch Throwable _))))
              timeout-ms (or fast-eval-ms auto-bg-ms 30000)
              completed? (.waitFor proc (long timeout-ms)
                                   java.util.concurrent.TimeUnit/MILLISECONDS)]
          (if completed?
            (do (deref reader-future 2000 nil)
                (delete-coact-tmp-file! tmp-file)
                (let [exit-code (.exitValue proc)
                      output    (.toString eval-output)]
                  {:lang lang :code code
                   :result (str exit-code)
                   :output output
                   :error (if (zero? exit-code) "" (str "Exit code: " exit-code))}))
            ;; Hung subprocess in a sub-agent / in-task context (no task system
            ;; here to detach) — kill it so the iteration can't block forever;
            ;; surface a timeout the LLM can act on. (The fast-eval branch below
            ;; already had this guard; the subagent branch did not.)
            (do (.destroyForcibly proc)
                (future-cancel reader-future)
                (delete-coact-tmp-file! tmp-file)
                {:lang lang :code code
                 :result nil
                 :output (.toString eval-output)
                 :error (str "Subprocess timed out after " timeout-ms "ms")})))
        (catch Exception e
          (delete-coact-tmp-file! tmp-file)
          {:lang lang :code code :result nil :output ""
           :error (ex-message e)}))
      (if-not fast-eval-ms
        (run-task-block
         {:lang lang :code code :auto-bg-ms auto-bg-ms :tmp-file tmp-file
          :start-fn
          (fn []
            (let [mgr (task-mgr/get-default-manager)
                  task (tp/create-task mgr
                                       (task-label (str lang ": ") code)
                                       :bash
                                       {:command command :timeout-ms 500 :working-dir proj-dir}
                                       {:metadata (coact-task-metadata
                                                   {:lang lang
                                                    :from-iteration from-iteration
                                                    :tmp-file tmp-file
                                                    :code     code
                                                    :owner-agent-id owner-agent-id})})]
              (tp/start-task mgr (:id task))
              task))})
        (let [t0 (System/currentTimeMillis)
              pb (ProcessBuilder. ^"[Ljava.lang.String;"
                  (into-array String ["/bin/sh" "-c" command]))
              _ (.redirectErrorStream pb true)
              _ (.directory pb (java.io.File. ^String proj-dir))
              ^Process proc (.start pb)
              _ (.close (.getOutputStream proc))
              eval-output (java.io.StringWriter.)
              reader-future
              (future
                (let [^java.io.InputStreamReader reader
                      (java.io.InputStreamReader. (.getInputStream proc))
                      buf (char-array 1024)]
                  (try
                    (loop []
                      (let [n (.read reader buf)]
                        (when (pos? n)
                          (.write eval-output buf 0 ^int n)
                          (recur))))
                    (catch java.io.IOException _)
                    (catch Throwable _))))
              completed? (.waitFor proc
                                   (long fast-eval-ms)
                                   java.util.concurrent.TimeUnit/MILLISECONDS)]
          (if completed?
            (do (deref reader-future 2000 nil)
                (delete-coact-tmp-file! tmp-file)
                (let [exit-code (.exitValue proc)
                      output    (.toString eval-output)]
                  {:lang lang :code code
                   :result (str exit-code)
                   :output output
                   :error (if (zero? exit-code) "" (str "Exit code: " exit-code))}))
            (let [meta (coact-task-metadata
                        {:lang lang :from-iteration from-iteration
                         :tmp-file tmp-file :code code
                         :owner-agent-id owner-agent-id})]
              (adopt-and-await-task
               {:task-name    (task-label (str lang ": ") code)
                :job-type     :bash
                :job-config   {:command command :working-dir proj-dir}
                :metadata     meta
                :make-on-poll (make-process-poll-fn proc reader-future eval-output)
                :on-cancel    (fn []
                                (executor/destroy-process-tree! proc)
                                (future-cancel reader-future))
                :lang         lang
                :auto-bg-ms   auto-bg-ms
                :fast-eval-ms fast-eval-ms
                :started-at   t0
                :tmp-file     tmp-file}))))))))

(defn- project-fast-eval-result
  "Project a thunk's raw return into eval-entry shape for the fast path.
   backend: :sandbox or :nrepl — determines how :result is stringified."
  [code backend r]
  (let [output (or (:output r) "")]
    (if (:error r)
      {:lang "clojure" :code code :result nil :output output
       :error (if (and (= :sandbox backend)
                       (str/includes? (:error r) "FINAL termination"))
                (str "FINAL is not supported in CoAct. "
                     "Populate the signature's `answer` output field "
                     "(JSON: \"answer\": \"...\") to terminate the loop.")
                (:error r))}
      {:lang "clojure" :code code
       :result (case backend
                 :sandbox (when-let [v (:result r)] (pr-str v))
                 :nrepl   (:result r))
       :output output :error ""})))

(defn- run-inline-clj-eval
  "Run a clojure eval thunk synchronously on the current thread — no future,
   no task. Used when already inside a task context to prevent nesting."
  [code backend thunk-fn]
  (try
    (let [r (thunk-fn)]
      (project-fast-eval-result code backend r))
    (catch Exception e
      {:lang "clojure" :code code :result nil :output ""
       :error (ex-message e)})))

(defn- run-clj-sandbox-block
  "Evaluate a clojure block in the SCI sandbox. When inside a task context or
   invoked by a sub-agent, runs inline (no detach). When `fast-eval-ms` is set,
   tries inline eval first — only promotes to a tracked task on timeout."
  [sandbox code auto-bg-ms & {:keys [from-iteration fast-eval-ms subagent? owner-agent-id]}]
  (cond
    (or (proto/in-task-context?) subagent?)
    (let [[thunk _] (clj-sandbox/eval-sandbox-thunk sandbox code)]
      (run-inline-clj-eval code :sandbox thunk))

    (not fast-eval-ms)
    (run-task-block
     {:lang "clojure" :code code :auto-bg-ms auto-bg-ms
      :start-fn
      (fn []
        (let [mgr  (task-mgr/get-default-manager)
              task (tp/create-task mgr
                                   (task-label "clj: " code)
                                   :clj-sandbox-eval
                                   {:sandbox sandbox :code code :timeout-ms 500}
                                   {:metadata (coact-task-metadata
                                               {:lang "clojure" :backend :sandbox
                                                :from-iteration from-iteration
                                                :code code
                                                :owner-agent-id owner-agent-id})})]
          (tp/start-task mgr (:id task))
          task))})

    :else
    (let [t0        (System/currentTimeMillis)
          [thunk eval-output] (clj-sandbox/eval-sandbox-thunk sandbox code)
          !task-ref (atom :inline-code-eval)
          fut (future (binding [proto/*current-task* !task-ref] (thunk)))
          r   (deref fut fast-eval-ms ::timeout)]
      (if (not= r ::timeout)
        (project-fast-eval-result code :sandbox r)
        (let [meta (coact-task-metadata
                    {:lang "clojure" :backend :sandbox
                     :from-iteration from-iteration :code code
                     :owner-agent-id owner-agent-id})]
          (adopt-and-await-task
           {:task-name    (task-label "clj: " code)
            :job-type     :clj-sandbox-eval
            :job-config   {:sandbox sandbox :code code}
            :metadata     meta
            :make-on-poll (executor/make-future-poll-fn fut eval-output code
                                                        executor/project-sandbox-result)
            :on-cancel    (fn [] (future-cancel fut))
            :lang         "clojure"
            :auto-bg-ms   auto-bg-ms
            :fast-eval-ms fast-eval-ms
            :started-at   t0
            :!task-ref    !task-ref}))))))

(defn- run-clj-nrepl-block
  "Evaluate a clojure block against the live JVM via clj-nrepl. When inside a
   task context or invoked by a sub-agent, runs inline (no detach). When
   `fast-eval-ms` is set, tries inline eval first — only promotes to a tracked
   task on timeout."
  [code auto-bg-ms & {:keys [from-iteration nrepl-session-id fast-eval-ms subagent? owner-agent-id]}]
  (cond
    (or (proto/in-task-context?) subagent?)
    (let [[thunk _] (clj-nrepl/eval-nrepl-thunk
                     code
                     :session    nrepl-session-id
                     :timeout-ms (* 1000 60 60))]
      (run-inline-clj-eval code :nrepl thunk))

    (not fast-eval-ms)
    (run-task-block
     {:lang "clojure" :code code :auto-bg-ms auto-bg-ms
      :start-fn
      (fn []
        (let [mgr  (task-mgr/get-default-manager)
              task (tp/create-task mgr
                                   (task-label "nrepl-clj: " code)
                                   :clj-nrepl-eval
                                   (cond-> {:code code :timeout-ms (* 1000 60 60)}
                                     nrepl-session-id (assoc :session nrepl-session-id))
                                   {:metadata (coact-task-metadata
                                               {:lang "clojure" :backend :nrepl
                                                :nrepl-session-id nrepl-session-id
                                                :from-iteration from-iteration
                                                :code code
                                                :owner-agent-id owner-agent-id})})]
          (tp/start-task mgr (:id task))
          task))})

    :else
    (let [t0        (System/currentTimeMillis)
          [thunk eval-output] (clj-nrepl/eval-nrepl-thunk
                               code
                               :session    nrepl-session-id
                               :timeout-ms (* 1000 60 60))
          !task-ref (atom :inline-code-eval)
          fut (future (binding [proto/*current-task* !task-ref] (thunk)))
          r   (deref fut fast-eval-ms ::timeout)]
      (if (not= r ::timeout)
        (project-fast-eval-result code :nrepl r)
        (let [meta (coact-task-metadata
                    {:lang "clojure" :backend :nrepl
                     :nrepl-session-id nrepl-session-id
                     :from-iteration from-iteration :code code
                     :owner-agent-id owner-agent-id})]
          (adopt-and-await-task
           {:task-name    (task-label "nrepl-clj: " code)
            :job-type     :clj-nrepl-eval
            :job-config   (cond-> {:code code :timeout-ms (* 1000 60 60)}
                            nrepl-session-id (assoc :session nrepl-session-id))
            :metadata     meta
            :make-on-poll (executor/make-future-poll-fn fut eval-output code
                                                        #(select-keys % [:code :output :result :error :ns]))
            :on-cancel    (fn [] (future-cancel fut))
            :lang         "clojure"
            :auto-bg-ms   auto-bg-ms
            :fast-eval-ms fast-eval-ms
            :started-at   t0
            :!task-ref    !task-ref}))))))

(defn- agent-clj-backend
  "Resolve the Clojure code-execution backend for `agent`. There is **no**
   per-fence override — the backend is fixed per agent for the duration of
   the session.

   Reads `:clj-backend` via the unified config chain (per-agent override →
   session → global → schema default :sandbox). Specialist defagents like
   debug-agent set the per-agent override via a lifecycle hook. Possible
   values:
     :sandbox — SCI sandbox (default for coact/react/etc.)
     :nrepl   — live brainyard JVM via clj-nrepl (debug-agent)"
  [agent]
  (config/get-config agent :clj-backend))

(defn- agent-nrepl-session-id
  "Read the pinned nREPL session id from the agent's per-instance
   config. nil when the agent doesn't pin a session — `eval-string`
   then opens an ephemeral one per call."
  [agent]
  (when agent (config/get-config agent :nrepl-session-id)))

(defn- run-verbatim-block
  "Write a verbatim content block (markdown/text/html) byte-for-byte to a
   scratch file and return its absolute path as the eval result. No evaluation,
   no task — a synchronous write, so escaping never enters the picture. The LLM
   reads / transforms / promotes the file from a later code block (e.g.
   `(spit \"docs/report.md\" (slurp <path>))`). The file rides the existing 24h
   scratch GC sweep; it is intermediate, not a deliverable, until copied out.

   The full content rides back in `:code` (like any other code block) so the
   model keeps sight of what it generated across turns instead of only a path;
   token economy is the iteration-record compaction layer's job, not this
   writer's. On a write failure `:code` is the only surviving copy, so it is
   carried then too."
  [lang content filename]
  (let [ext  (case lang "markdown" ".md" "html" ".html" ".txt")
        path (coact-verbatim-path ext filename)]
    (try
      (spit path content)
      {:lang lang :code content :result path
       :output (str "Wrote " (count content) " chars to " path)
       :error ""}
      (catch Exception e
        {:lang lang :code content :result nil :output ""
         :error (str "Failed to write verbatim " lang " file: " (.getMessage e))}))))

(defn- run-single-block
  "Dispatch one {:lang :code [:fence-error]} block by language. Used
   sequentially and (indirectly) in parallel mode.

   For \"clojure\", backend comes from `agent-clj-backend` — no per-block
   selection. A pinned `:nrepl-session-id` from agent config is plumbed
   through to the nREPL executor so multi-turn investigations accumulate
   server-side state.

   Blocks whose fence had unexpected trailing text (`:fence-error` set
   by `extract-all-code-blocks-multi`) short-circuit with that error."
  [sandbox {:keys [lang code fence-error filename verbatim?]}
   {:keys [auto-bg-ms fast-eval-ms from-iteration agent]}]
  (when (Thread/interrupted)
    (throw (ex-info "Code eval interrupted" {:lang lang :interrupted true})))
  ;; Sub-agents run code blocks inline (no detach): detach is top-level-only
  ;; (the harvest folds in a later iteration; a sub-agent closes first and
  ;; would orphan the task). Mirrors the tool path's subagent guard. The
  ;; runners already inline when `in-task-context?`; this extends that to the
  ;; subagent case where *current-task* may not be bound (e.g. fast-eval off).
  (let [subagent?      (tool/subagent? agent)
        ;; Tag the task with the creating agent instance so the in-flight
        ;; surfaces (roster/harvest/resolve) can scope to it — keeps a
        ;; sub-agent from listing the parent task that wraps it.
        owner-agent-id (try (proto/agent-id agent) (catch Throwable _ nil))]
    (cond
      fence-error
      {:lang lang :code code :result nil :output "" :error fence-error}

      verbatim?
      (run-verbatim-block lang code filename)

      (= "clojure" lang)
      (let [backend    (agent-clj-backend agent)
            session-id (when (= :nrepl backend)
                         (agent-nrepl-session-id agent))]
        (case backend
          :nrepl   (run-clj-nrepl-block code auto-bg-ms
                                        :from-iteration   from-iteration
                                        :nrepl-session-id session-id
                                        :fast-eval-ms     fast-eval-ms
                                        :subagent?        subagent?
                                        :owner-agent-id   owner-agent-id)
          :sandbox (run-clj-sandbox-block sandbox code auto-bg-ms
                                          :from-iteration from-iteration
                                          :fast-eval-ms   fast-eval-ms
                                          :subagent?      subagent?
                                          :owner-agent-id owner-agent-id)))

      (#{"bash" "python" "javascript"} lang)
      (run-script-block lang code auto-bg-ms
                        :from-iteration from-iteration
                        :fast-eval-ms   fast-eval-ms
                        :subagent?      subagent?
                        :owner-agent-id owner-agent-id)

      :else
      {:lang "other" :code code :result nil :output ""
       :error (str "Unsupported language: " lang)})))

(defn- run-blocks-concurrently
  "All-or-nothing parallel runner. Splits blocks three ways: fence-errored
   blocks (short-circuit to an error entry), clojure blocks (go through
   clj-sandbox/eval-code-blocks-parallel — fork + merge-defs semantics,
   FINAL disallowed), and the rest — shell/python/js plus verbatim
   markdown/text/html — each in its own future via `run-single-block` (a
   separate temp file per script; a synchronous file write per verbatim
   block). Results are re-ordered to source position."
  [sandbox blocks dispatch-opts]
  (let [indexed         (map-indexed vector blocks)
        fence-err-indexed (filter (comp :fence-error second) indexed)
        ok-indexed      (remove (comp :fence-error second) indexed)
        clj-indexed     (filter #(= "clojure" (:lang (second %))) ok-indexed)
        script-indexed  (remove #(= "clojure" (:lang (second %))) ok-indexed)
        fence-err-results
        (mapv (fn [[idx {:keys [lang code fence-error]}]]
                [idx {:lang lang :code code :result nil :output ""
                      :error fence-error :parallel? true}])
              fence-err-indexed)

        ;; Clojure partition: delegate to the existing parallel runner
        ;; (fork-and-merge). FINAL inside these blocks surfaces as an error
        ;; entry with :error "FINAL is not allowed in parallel blocks" per
        ;; clj-sandbox semantics — we do NOT promote those to :answer.
        ;;
        ;; Parallel SCI forks are in-process futures, not task-manager
        ;; tasks, so they cannot detach into the background. The deadline
        ;; (`:auto-bg-ms`) is enforced as a hard kill — at the deadline the
        ;; fork is cancelled and the entry surfaces :status :timeout.
        ;;
        ;; nREPL backend demotion: when the agent's :clj-backend is :nrepl
        ;; (e.g. debug-agent), parallel mode still routes clojure blocks
        ;; through the SCI sandbox because clj-sandbox's fork+merge runner
        ;; can't share an nREPL session safely across concurrent evals. We
        ;; surface a marker on each entry's :output so the LLM knows the
        ;; live-runtime semantics were silently dropped.
        nrepl-demoted? (= :nrepl (agent-clj-backend (:agent dispatch-opts)))
        demotion-marker
        (when nrepl-demoted?
          (str "[parallel mode demoted nREPL → SCI sandbox: the fork+merge"
               " runner cannot share an nREPL session safely. This block"
               " ran in the SCI sandbox, NOT against the live brainyard JVM."
               " Use sequential mode (drop the <!-- ParallelBlock --> markers)"
               " to keep live-runtime semantics.]"))
        clj-results
        (when (seq clj-indexed)
          (let [codes (mapv (comp :code second) clj-indexed)
                ;; clj-sandbox's eval API returns {:eval-results [...]} — bind it
                ;; to the local `code-results` (the agent-side name for the
                ;; code channel; the sandbox component keeps its own key).
                {code-results :eval-results}
                (clj-sandbox/eval-code-blocks-parallel
                 sandbox codes
                 :timeout-ms (:auto-bg-ms dispatch-opts))]
            (mapv (fn [[idx _block] r]
                    (let [raw-output (or (:output r) "")
                          stamped-output (if demotion-marker
                                           (str demotion-marker
                                                (when-not (str/blank? raw-output) "\n")
                                                raw-output)
                                           raw-output)]
                      [idx
                       (cond-> {:lang "clojure"
                                :code (or (:code r) "")
                                :result (when-let [v (:result r)] (pr-str v))
                                :output stamped-output
                                :error (or (:error r) "")
                                :parallel? true}
                         ;; Preserve :status :timeout (hard-cancel branch).
                         (= :timeout (:status r))
                         (assoc :status :timeout))]))
                  clj-indexed
                  code-results)))

        ;; Non-clojure partition: each block runs as a future
        script-futures
        (when (seq script-indexed)
          (mapv (fn [[idx block]]
                  [idx (future
                         (assoc (run-single-block sandbox block dispatch-opts)
                                :parallel? true))])
                script-indexed))
        script-results
        (mapv (fn [[idx fut]] [idx @fut]) script-futures)

        ;; Re-order into source position
        all-by-idx (into {} (concat fence-err-results clj-results script-results))]
    (mapv all-by-idx (range (count blocks)))))

(defn coact-code-eval-action
  "BT action: parse :code-blocks, execute each fenced block, and populate
   :last-code-results. CoAct terminates ONLY via the signature's `answer`
   output field — it does not promote sandbox-level `FINAL` into `:answer`.

   Every code block runs as a task in synchronous foreground mode. If a
   block has not finished by `:auto-background-timeout-ms` (agent config),
   the task auto-detaches into the background and the entry surfaces
   :status :pending :task-id <id> — a later iteration's harvest folds the
   resolved result back in."
  [{:keys [st-memory agent] :as _context}]
  (let [{:keys [sandbox code-blocks tools tools-fn-map
                iteration-count]} @st-memory
        auto-bg-ms   (config/get-config agent :auto-background-timeout-ms)
        fast-eval-ms (config/get-config agent :fast-eval-timeout-ms)
        blocks (clj-sandbox/extract-all-code-blocks-multi code-blocks)
        dispatch-opts {:auto-bg-ms     auto-bg-ms
                       :fast-eval-ms   (when (and fast-eval-ms (pos? fast-eval-ms))
                                         fast-eval-ms)
                       :agent          agent
                       :tools          tools
                       :tools-fn-map   tools-fn-map
                       :from-iteration iteration-count}
        parallel? (parallel-mode? code-blocks)
        entries
        (cond
          (empty? blocks)
          [{:lang "other" :code "" :result nil :output ""
            :error "No fenced code blocks found in code-blocks output."
            :parallel? false}]

          parallel?
          (let [batch-total (count blocks)]
            ;; Parallel mode: fire :pre for all blocks upfront, then run
            ;; them concurrently, then fire :post per entry below.
            ;; Parallel mode routes ALL clj blocks through the SCI sandbox
            ;; (Phase 1 of clj-nrepl-eval): the fork+merge runner doesn't
            ;; support :nrepl session sharing yet, so :backend stays
            ;; :sandbox even when an info-arg requested otherwise.
            ;; The :parallel? + :parallel-batch-index markers let observers
            ;; (e.g. TUI render) accumulate all blocks of one partition
            ;; into a single eval-display instead of overwriting.
            (doseq [[idx {:keys [code lang]}] (map-indexed vector blocks)]
              (hooks/fire! :agent.code-eval/pre
                           {:agent agent :code code :backend :sandbox :lang lang
                            :parallel? true
                            :parallel-batch-index idx
                            :parallel-batch-total batch-total}))
            (run-blocks-concurrently sandbox blocks dispatch-opts))

          :else
          ;; Sequential mode: weave :pre / run / :post per block so the
          ;; UI can render code → result → code → result in order.
          (mapv (fn [block]
                  (let [backend (if (= "clojure" (:lang block))
                                  (agent-clj-backend agent)
                                  :sandbox)]
                    (hooks/fire! :agent.code-eval/pre
                                 {:agent agent :code (:code block)
                                  :backend backend :lang (:lang block)})
                    (let [start-ms (System/currentTimeMillis)
                          entry    (assoc (run-single-block sandbox block dispatch-opts)
                                          :parallel? false
                                          :backend   backend)
                          elapsed  (- (System/currentTimeMillis) start-ms)
                          entry    (assoc entry :duration-ms elapsed)]
                      (hooks/fire! :agent.code-eval/post
                                   {:agent agent
                                    :code (:code entry)
                                    :result (:result entry)
                                    :output (:output entry)
                                    :error (:error entry)
                                    :duration-ms elapsed
                                    :backend backend
                                    :lang (:lang entry)})
                      entry)))
                blocks))]
    (swap! st-memory assoc
           :last-code-results entries
           :last-tool-results []
           :last-channel      :code)
    ;; Auto-detached blocks (:status :pending): arm runtime auto-notify so the
    ;; agent is auto-resumed on completion instead of relying on the LLM to poll
    ;; or call task$wakeup. No-op on headless / sub-agent / disabled.
    (auto-notify/arm-pending-entries! agent entries)
    (mulog/log ::code-eval
               :iteration (:iteration-count @st-memory)
               :blocks (count blocks)
               :parallel parallel?)
    ;; Parallel mode :post fires (sequential mode fired inline above).
    ;; :backend is :sandbox in this branch — see the parallel :pre block.
    (when parallel?
      (let [batch-total (count entries)]
        (doseq [[idx {:keys [code result output error duration-ms lang]}]
                (map-indexed vector entries)]
          (hooks/fire! :agent.code-eval/post
                       {:agent agent :code code
                        :result result :output output :error error
                        :duration-ms duration-ms
                        :backend :sandbox
                        :lang lang
                        :parallel? true
                        :parallel-batch-index idx
                        :parallel-batch-total batch-total}))))
    bt/success))

;; ============================================================================
;; Repair (empty-action nudge)
;; ============================================================================

(def ^:private repair-loop-guard-prefix
  "*(Loop guard: 3 consecutive iterations with no actionable output (no `tool-calls`, no fenced `code-blocks`, no `answer`). Stopping early. Latest tool result:)*\n\n```\n")

(defn- latest-tool-result-from-iterations
  "Walk `iterations` newest-first and return the most recent
   `:tool-result` string from any tool-channel record, or nil when no
   tool call has been recorded this turn."
  [iterations]
  (some (fn [{:keys [channel tool-results]}]
          (when (and (= "tool" channel) (seq tool-results))
            (-> tool-results last :tool-result)))
        (reverse iterations)))

(defn- trailing-none-channel-count
  "Count how many of the most recent `iterations` ended on the `:none`
   channel.  Stops at the first non-:none record."
  [iterations]
  (count (take-while #(= "none" (:channel %)) (reverse iterations))))

(defn- latest-thought-from-iterations
  "Walk `iterations` newest-first and return the most recent non-blank
   `:thought` (the LLM's reasoning for that iteration), or nil. Used as the
   second-best progress signal when no tool ever ran — a stalled LLM still
   reasons, so its last thought is more useful to the user than a content-free
   placeholder."
  [iterations]
  (some (fn [{:keys [thought]}]
          (when-not (str/blank? (str thought)) (str thought)))
        (reverse iterations)))

(defn- best-effort-progress-text
  "Best-available 'here's where I got to' text for a turn that is ending
   without a real `answer` (none-channel loop-guard, or max-iterations
   exhaustion). Precedence: latest tool-result > latest non-blank thought >
   deterministic iteration recap. Returns nil only when `iterations` is empty
   (nothing happened at all)."
  [iterations]
  (or (latest-tool-result-from-iterations iterations)
      (latest-thought-from-iterations iterations)
      (let [s (summarize-iterations-deterministic iterations)]
        (when-not (str/blank? (str s)) s))))

(defn- backoff-delay-ms
  "Exponential backoff delay for a 1-based retry attempt: base * 2^(attempt-1).
   attempt 1 → base, attempt 2 → 2*base, attempt 3 → 4*base, …"
  [attempt base-ms]
  (long (* base-ms (Math/pow 2 (dec attempt)))))

(defn- retry-sleep!
  "Indirection over Thread/sleep so tests can stub the backoff wait."
  [ms]
  (when (pos? ms) (Thread/sleep (long ms))))

(defn- any-channel-populated?
  "True when ThinkActCode populated at least one channel (answer / code / tool)."
  [context]
  (or (coact-answer-non-blank? context)
      (coact-has-code-blocks? context)
      (coact-has-tool-calls? context)))

(defn- empty-llm-result?
  "True when ThinkActCode produced nothing usable — blank reasoning AND no
   channel. This is the signature of a transient CLI hiccup (rate limit /
   nonzero exit / empty stream), distinct from a deliberate no-action where
   the model reasoned but populated no channel."
  [{:keys [st-memory] :as context}]
  (and (str/blank? (str (:last-reasoning @st-memory)))
       (not (any-channel-populated? context))))

(defn- coact-dispatch-channel!
  "Route a freshly-populated ThinkActCode result to its channel, mirroring
   the BT router's precedence (answer > code > tool). Used by the repair
   path after an empty-result retry recovers a usable result, so it is acted
   on in the same iteration instead of costing an extra one."
  [context]
  (coact-display-think-action context)
  (cond
    (coact-answer-non-blank? context)
    (coact-stamp-answer-action context)

    (coact-has-code-blocks? context)
    (do (coact-display-code-action context)
        (coact-code-eval-action context)
        (coact-display-eval-action context)
        ((coact-stamp-channel :code) context))

    (coact-has-tool-calls? context)
    (do (coact-display-tools-action context)
        (coact-tool-dispatch-action context)
        (coact-display-tool-results-action context)
        ((coact-stamp-channel :tool) context)))
  bt/success)

(defn- repair-malformed-output!
  "Recover from a malformed ThinkActCode result. Two triggers, same remedy:
     1. `bt/dspy` threw a DSPy/JSON parse error and stashed `:dspy-error`.
     2. `bt/dspy` succeeded but the parsed response failed output-schema
        validation (`:dspy-validation-errors`, set by dspy-action) — e.g. the
        model wrapped its output in placeholder/wrong keys (`$PARAMETER_NAME`)
        and populated no usable channel.
   Aborts the turn on a fatal error (auth / rate-limit / quota / billing) or
   once `:consecutive-llm-failures` reaches `:max-retries-on-llm-malformed-output`;
   otherwise re-prompts: pushes a FORMAT ERROR eval-result naming the schema and
   clears the channels so the next iteration's ThinkActCode retries. Clears both
   error keys so the router-slot re-entry of `coact-repair-action` doesn't
   re-classify the same failure."
  [{:keys [st-memory agent]}]
  (let [dspy-err (:dspy-error @st-memory)
        verrs    (:dspy-validation-errors @st-memory)
        err      (or dspy-err
                     (when (seq verrs)
                       (str "your previous output did not match the required schema "
                            (pr-str verrs)))
                     "LLM call failed")
        ;; Distinguish a schema-validation failure from a hard parse/throw so
        ;; the TUI can show the right progress line.
        kind     (if (and (nil? dspy-err) (seq verrs)) :validation-failure :malformed-output)
        max-r    (or (config/get-config agent :max-retries-on-llm-malformed-output) 3)
        fatal?   (boolean
                  (re-find #"(?i)authentication|401|403|api.key|invalid.credentials|rate.limit|429|quota|billing"
                           (str err)))
        consec   (get @st-memory :consecutive-llm-failures 0)
        abort?   (or fatal? (>= consec max-r))]
    (if abort?
      (swap! st-memory assoc
             :terminated true
             :answer (str "Agent stopped: " err)
             :terminated-by :llm-error
             :tool-calls [] :code-blocks "" :dspy-error nil :dspy-validation-errors nil
             :last-code-results [{:lang "other" :code "" :result ""
                                  :output "" :error (str "FATAL: " err ". Aborting.")
                                  :parallel? false}]
             :last-channel :none)
      (do
        ;; Progress surface: we're re-prompting the model rather than failing
        ;; silently. (Skip on abort — the terminal answer already explains it.)
        (when agent
          (hooks/fire! :agent.recovery/retrying
                       {:agent agent :kind kind
                        :attempt (inc consec) :max max-r}))
        (swap! st-memory assoc
               :tool-calls [] :code-blocks "" :dspy-error nil :dspy-validation-errors nil
               :consecutive-llm-failures (inc consec)
               :last-code-results [{:lang "other" :code "" :result "" :output ""
                                    :error (str "FORMAT ERROR: " err
                                                ". You MUST respond with valid JSON matching the output schema. "
                                                "Populate exactly ONE of `tool-calls` / `code-blocks` / `answer` "
                                                "using those exact field names — do NOT wrap your output in "
                                                "placeholder keys like $PARAMETER_NAME.")
                                    :parallel? false}]
               :last-channel :none)))))

(defn- repair-no-action!
  "Handle an iteration that succeeded but populated no channel (deliberate
   no-action, or empty-result retries exhausted). A successful DSPy call breaks
   any malformed-output streak, so reset `:consecutive-llm-failures`. Nudge up
   to `:max-retries-on-llm-no-action` consecutive no-action iterations; on the
   one past that, escalate via the loop-guard — terminate the turn with a
   best-effort progress answer (mirrors `coact-stamp-answer-action`'s state)."
  [{:keys [st-memory agent]}]
  (let [iters       (or (:iterations @st-memory) [])
        max-noact   (or (config/get-config agent :max-retries-on-llm-no-action) 3)
        none-streak (trailing-none-channel-count iters)]
    (swap! st-memory assoc :consecutive-llm-failures 0)
    (if (>= none-streak max-noact)
      (let [progress    (best-effort-progress-text iters)
            answer-text (str repair-loop-guard-prefix
                             (or progress "(no progress captured)")
                             "\n```")]
        (swap! st-memory assoc
               :answer            answer-text
               :last-channel      :answer
               :terminated        true
               :terminated-by     :none-channel-loop-guard
               :last-tool-results []
               :last-code-results []
               :eval-display      nil
               :display-stage     :eval-result)
        (mulog/log ::repair-loop-guard
                   :iteration (:iteration-count @st-memory)
                   :consecutive-none (inc none-streak)))
      (do
        ;; Progress surface: no actionable output this iteration; show the user
        ;; we're nudging it before the loop-guard would stop the turn.
        (when agent
          (hooks/fire! :agent.recovery/retrying
                       {:agent agent :kind :no-action
                        :attempt (inc none-streak) :max max-noact}))
        (swap! st-memory assoc
               :last-channel :none
               :last-tool-results []
               :last-code-results [{:lang "other"
                                    :code ""
                                    :result ""
                                    :output ""
                                    :error (str "You emitted no action this iteration — none of "
                                                "`tool-calls`, `code-blocks`, `answer` was populated. "
                                                "Populate exactly one next iteration.")
                                    :parallel? false}])
        (mulog/log ::repair
                   :iteration (:iteration-count @st-memory)
                   :consecutive-none (inc none-streak))))))

(defn coact-repair-action
  "Unified recovery action for an unproductive ThinkActCode iteration. The same
   fn sits in TWO behavior-tree slots and classifies the failure kind:

   1. Malformed output (`:fallback/llm-guard` slot — `bt/dspy` threw, leaving
      `:dspy-error`): re-prompt across iterations up to
      `:max-retries-on-llm-malformed-output`, then abort (fatal errors abort
      immediately). See `repair-malformed-output!`.

   2. Empty result (router Path D — DSPy succeeded but returned blank reasoning
      AND no channel, the signature of a transient CLI hiccup): re-run
      ThinkActCode inline up to `:max-retries-on-llm-empty-result` times with
      exponential backoff; if a retry recovers a channel, dispatch it this same
      iteration (no wasted turn).

   3. No action (router Path D — the model reasoned but populated no channel):
      nudge up to `:max-retries-on-llm-no-action` consecutive iterations, then
      the loop-guard stops the turn with a best-effort progress answer. See
      `repair-no-action!`.

   Idempotent within an iteration via `:last-repair-iter`: on a malformed-output
   re-prompt the channels are left empty, so the router-slot copy of this action
   re-enters in the same iteration — the guard makes that second call a no-op so
   the re-prompt isn't re-classified as a no-action."
  [{:keys [st-memory agent] :as context}]
  (let [iter (:iteration-count @st-memory)]
    (cond
      ;; Already handled this iteration (llm-guard slot ran; router Path D
      ;; re-enters with the same empty channels).
      (= (:last-repair-iter @st-memory) iter)
      bt/success

      ;; (1) Malformed output — bt/dspy threw (:dspy-error), OR the parsed
      ;; response failed output-schema validation (:dspy-validation-errors,
      ;; e.g. placeholder/wrong keys) and populated no channel. Both get a
      ;; schema-reminder re-prompt rather than a blind re-run of the same call
      ;; (which usually repeats the mistake) — this is the split from the
      ;; genuinely-empty-stream backoff retry below.
      (or (some? (:dspy-error @st-memory))
          (seq (:dspy-validation-errors @st-memory)))
      (do (repair-malformed-output! context)
          (swap! st-memory assoc :last-repair-iter iter)
          bt/success)

      ;; (2/3) DSPy succeeded but produced no usable channel.
      :else
      (let [max-empty (or (config/get-config agent :max-retries-on-llm-empty-result) 5)
            base-ms   (or (config/get-config agent :empty-result-retry-base-ms) 1000)]
        ;; Inline empty-result retries: re-run the (unchanged) ThinkActCode call
        ;; with exponential backoff before treating the iteration as a no-op.
        (when (and agent (pos? max-empty) (empty-llm-result? context))
          (loop [attempt 1]
            (let [delay-ms (backoff-delay-ms attempt base-ms)]
              (mulog/log ::repair-empty-result-retry
                         :iteration iter :attempt attempt :max max-empty :backoff-ms delay-ms)
              ;; Progress surface: recovering from an empty model response rather
              ;; than silently sleeping through the backoff.
              (hooks/fire! :agent.recovery/retrying
                           {:agent agent :kind :empty-result
                            :attempt attempt :max max-empty})
              (retry-sleep! delay-ms)
              (bt/dspy (assoc context :opts
                              {:id         :coact.action/repair-retry-think
                               :signature  #'ThinkActCode
                               :operation  :chain-of-thought
                               :stable-keys #{:system-context :user-context}}))
              (when (and (empty-llm-result? context) (< attempt max-empty))
                (recur (inc attempt))))))
        (if (any-channel-populated? context)
          ;; A retry recovered a usable channel — dispatch it now, same iteration.
          (coact-dispatch-channel! context)
          ;; Deliberate no-action, or retries exhausted/still empty — nudge/guard.
          (repair-no-action! context))
        (swap! st-memory assoc :last-repair-iter iter)
        bt/success))))

;; ============================================================================
;; Accumulate iteration
;; ============================================================================

(defn- sanitize-tool-result [{:keys [tool-name tool-args tool-result]}]
  {:tool-name   (str tool-name)
   :tool-args   tool-args
   :tool-result (truncate-iter-field tool-result "tool-result")})

(defn- sanitize-eval-entry [{:keys [lang code result output error parallel?
                                    status task-id from-iteration]}]
  (cond-> {:lang      (or lang "other")
           :code      (or code "")
           :result    (truncate-iter-field result "eval-result")
           :output    (truncate-iter-field output "eval-output")
           :error     (or error "")
           :parallel? (boolean parallel?)}
    status         (assoc :status status)
    task-id        (assoc :task-id task-id)
    from-iteration (assoc :from-iteration from-iteration)))

(defn coact-accumulate-iteration-action
  "BT action: build an iteration record from :last-channel / :last-*-results
   / :last-reasoning, append to :iterations, cap to the last N records.
   The record's `:thought` is sourced from :last-reasoning (written by the
   DSPy chain-of-thought layer) — we do NOT ask the LLM for a separate
   thought output."
  [{:keys [st-memory]}]
  (let [{:keys [iteration-count last-reasoning last-channel
                last-tool-results last-code-results]} @st-memory
        thought (or last-reasoning "")
        record (case last-channel
                 :tool {:iteration iteration-count
                        :thought thought
                        :channel "tool"
                        :tool-results (mapv sanitize-tool-result (or last-tool-results []))
                        :code-results []}
                 :code {:iteration iteration-count
                        :thought thought
                        :channel "code"
                        :tool-results []
                        :code-results (mapv sanitize-eval-entry (or last-code-results []))}
                 :none {:iteration iteration-count
                        :thought thought
                        :channel "none"
                        :tool-results []
                        :code-results (mapv sanitize-eval-entry (or last-code-results []))}
                 ;; :answer — no record; the loop is about to exit
                 nil)]
    (when record
      (swap! st-memory update :iterations (fnil conj []) record)
      ;; Uncapped mirror for trajectory recording (full turn, no budget cap).
      (swap! st-memory update :trajectory-iterations (fnil conj []) record)
      ;; Cap :iterations to the last 10
      (let [max-n 10
            iters (:iterations @st-memory)]
        (when (> (count iters) max-n)
          (swap! st-memory assoc :iterations (vec (take-last max-n iters)))))
      (mulog/log ::accumulate
                 :iteration iteration-count
                 :channel last-channel)))
  bt/success)

;; ============================================================================
;; Loop & store-results fallbacks
;; ============================================================================

(defn coact-loop-fallback-action
  "BT action: if the repeat itself fails catastrophically, surface a minimal
   answer so downstream conditions pass."
  [{:keys [st-memory]}]
  (let [ans (:answer @st-memory)]
    (when (or (nil? ans) (str/blank? ans))
      (swap! st-memory assoc :answer
             "*(Note: Processing encountered an error. Please try again.)*")))
  bt/success)

(def ^:private exhaustion-answer-prefix
  "*(The agent hit its iteration limit without producing a final answer. Stopping. Latest progress:)*\n\n```\n")

(defn coact-ensure-answer-action
  "BT action: guarantee a non-blank `:answer` after the main loop.

   The `:repeat` decorator returns `:success` on max-iterations exhaustion (it
   does NOT fail), so the `:fallback/loop-guard`'s catastrophic-failure branch
   never fires for the common 'ran out of iterations without ever populating
   `answer`' case. The `:none`-streak loop-guard only catches *consecutive*
   empty iterations — a turn that keeps calling tools/code productively but
   never sets `answer` sails right past it to the ceiling. Without this action
   the downstream `:cond/answer-present` would fail, returning a raw BT
   `:failure` with a nil answer and nothing surfaced to the user.

   When `:answer` is already non-blank (normal termination, llm-error abort, or
   none-channel loop-guard) this is a no-op. Otherwise it stamps a best-effort
   answer from the trajectory so the user sees where the agent got to, and
   flips the same terminal state `coact-stamp-answer-action` would."
  [{:keys [st-memory]}]
  (let [{:keys [answer iterations]} @st-memory]
    (when (str/blank? (str answer))
      (let [progress    (best-effort-progress-text (or iterations []))
            answer-text (str exhaustion-answer-prefix
                             (or progress "(no progress captured)")
                             "\n```")]
        (swap! st-memory assoc
               :answer            answer-text
               :last-channel      :answer
               :terminated        true
               :terminated-by     :max-iterations-exhausted
               ;; Signal the answer evaluator (prepare-evaluation-action) that
               ;; this round ran out of iterations rather than answering, so it
               ;; retries with "be more efficient" feedback when refinement
               ;; budget remains.
               :iterations-exhausted true
               ;; Exhaustion backfill is not a goal-achieved answer; surface a
               ;; definite false (the self-assessment the answer path would set).
               :goal-achieved     false
               :next-user-prompt  ""
               :last-tool-results []
               :last-code-results []
               :eval-display      nil
               :display-stage     :eval-result)
        (mulog/log ::ensure-answer-exhausted
                   :iteration (:iteration-count @st-memory)))))
  bt/success)

;; ============================================================================
;; Answer-evaluation / in-loop refinement
;;
;; Refinement is folded INTO the iteration loop's answer path (no outer loop, no
;; re-init). When ThinkActCode emits an answer it also self-reports
;; `goal-achieved` / `next-user-prompt` (the old standalone FinalizeAnswer). The
;; hybrid gate then decides per answer:
;;   round >= max-refinements  → :accept       (terminate; covers max-refs=0)
;;   goal-achieved = true       → :needs-eval   (independent EvaluateAnswer confirm)
;;   else                       → :refine-self  (model said not-done → refine now)
;; A rejection appends a synthetic [evaluation] iteration record (the critique)
;; and blanks :answer so the SAME loop continues — sandbox / defs / trajectory
;; are preserved and the next ThinkActCode sees the feedback via :iterations.
;; ============================================================================

(defn coact-eval-needs-llm?
  "BT condition: true only when `coact-prepare-eval-action` chose the LLM
   hallucination-check path (it stamps `:evaluation-status {:phase :llm-calling}`).
   When there is no evidence to check against it leaves the phase :skipped, so
   the eval-guard falls through to accept-on-skip (stamp-answer)."
  [{:keys [st-memory]}]
  (= :llm-calling (get-in @st-memory [:evaluation-status :phase])))

(defn answer-decision-is?
  "Factory: BT condition true when the hybrid-gate decision stamped by
   `coact-capture-answer-meta` equals `decision`."
  [decision]
  (fn [{:keys [st-memory]}]
    (= decision (:answer-decision @st-memory))))

(defn coact-capture-answer-meta
  "Answer path entry: the ThinkActCode iteration populated `answer` (and,
   per the signature, the optional `goal-achieved` / `next-user-prompt`
   self-assessment). Stash the draft and compute the hybrid refine/accept
   decision into `:answer-decision`. Does NOT terminate — the downstream
   answer-gate fallback dispatches on the decision."
  [{:keys [st-memory agent]}]
  (let [m        @st-memory
        round    (or (:refinement-round m) 0)
        max-refs (or (config/get-config agent :max-refinements) 0)
        ga       (:goal-achieved m)
        decision (cond
                   (>= round max-refs) :accept
                   (true? ga)          :needs-eval
                   :else               :refine-self)]
    (swap! st-memory assoc
           :pending-answer  (:answer m)
           :answer-decision decision)
    (mulog/log ::capture-answer-meta
               :round round :max-refinements max-refs
               :goal-achieved ga :decision decision)
    bt/success))

(defn- append-evaluation-record!
  "Append a synthetic `channel \"evaluation\"` record carrying the rejected
   answer + critique to `:iterations`, blank `:answer`, and bump the refinement
   round so the SAME loop continues. The next ThinkActCode call observes the
   record via its `:iterations` input (no context rebuild — user-context is a
   cached stable-key)."
  [st-memory {:keys [verdict feedback]}]
  (let [m        @st-memory
        n        (:iteration-count m)
        rejected (str (or (:pending-answer m) (:answer m) ""))
        record   {:iteration       n
                  :channel         "evaluation"
                  :thought         (or (:last-reasoning m) "")
                  :rejected-answer rejected
                  :verdict         (str verdict)
                  :feedback        (str feedback)
                  :tool-results    []
                  :code-results    []}]
    (swap! st-memory
           (fn [s]
             (-> s
                 (update :iterations (fnil conj []) record)
                 (assoc :answer           ""
                        :answer-complete  false
                        :previous-answer  rejected
                        :refinement-round (inc (or (:refinement-round s) 0))
                        :pending-answer   nil
                        :goal-achieved    nil
                        :next-user-prompt nil
                        :answer-decision  nil
                        :display-stage    :think))))
    ;; Cap :iterations to the last 10 (parity with accumulate). The just-conj'd
    ;; evaluation record is most-recent, so take-last always keeps it.
    (let [max-n 10
          iters (:iterations @st-memory)]
      (when (> (count iters) max-n)
        (swap! st-memory assoc :iterations (vec (take-last max-n iters)))))
    (mulog/log ::inject-refinement-feedback
               :iteration n :verdict verdict)))

(defn coact-refine-self-action
  "Answer path, :refine-self branch: the model emitted an answer but reported
   goal-achieved=false (or omitted it) while refinement budget remains. Inject a
   synthetic [evaluation] record carrying the model's own follow-up as feedback
   so the next iteration refines — WITHOUT a separate EvaluateAnswer call and
   WITHOUT terminating the loop."
  [{:keys [st-memory]}]
  (let [np (:next-user-prompt @st-memory)
        fb (if (str/blank? (str np))
             (str "You reported the goal was not achieved. Identify the remaining "
                  "gap, gather any missing data via tools/code, then produce a "
                  "complete answer.")
             (str "You reported the goal was not yet achieved. Your own suggested "
                  "next step: " np ". Act on it, then produce a complete answer."))]
    (append-evaluation-record! st-memory {:verdict "SELF" :feedback fb}))
  bt/success)

(defn coact-prepare-eval-action
  "Answer path, :needs-eval branch: build evidence + eval-context for the
   independent EvaluateAnswer hallucination check and flag :llm-calling so
   `coact-eval-needs-llm?` lets the DSPy node run. When there is no evidence to
   check against (pure general-knowledge answer, or tool/code produced nothing),
   skip the check — leave the phase :skipped so the eval-guard falls through to
   accept-on-skip."
  [{:keys [st-memory agent]}]
  (let [m          @st-memory
        iterations (or (:iterations m) (:full-iterations m))
        round      (or (:refinement-round m) 0)
        evidence   (evaluation/build-iteration-evidence (or iterations []))
        eval-ctx   (evaluation/build-evaluation-context m)]
    (if (str/blank? evidence)
      (do (swap! st-memory assoc :evaluation-status {:phase :skipped :round round})
          (mulog/log ::eval-skipped-no-evidence :round round)
          bt/success)
      (let [eval-lm-label (let [e (config/get-config agent :eval-lm-config)]
                            (when-not (str/blank? (str e)) e))]
        (swap! st-memory assoc
               :evidence     evidence
               :eval-context (if (str/blank? eval-ctx) "No additional context" eval-ctx)
               :evaluation-status {:phase :llm-calling :round round
                                   :has-evidence true
                                   :evidence-length (count evidence)
                                   :eval-lm-label eval-lm-label})
        (when agent
          (hooks/fire! :agent.evaluation/started {:agent agent :round round})
          (hooks/fire! :agent.evaluation/llm-calling
                       {:agent agent :round round :has-evidence true
                        :evidence-length (count evidence)
                        :eval-lm-label eval-lm-label}))
        bt/success))))

(defn coact-process-eval-in-loop-action
  "Answer path, :needs-eval branch, after EvaluateAnswer. On COMPLETE (or an
   unrecognized verdict) → accept and terminate via `coact-stamp-answer-action`.
   On HALLUCINATED / INCOMPLETE → inject the verdict as feedback and continue
   the loop. Fires :agent.evaluation/done so the TUI eval widget resolves."
  [{:keys [st-memory agent] :as ctx}]
  (let [m       @st-memory
        verdict (:verdict m)
        detail  (or (:detail m) "")
        round   (or (:refinement-round m) 0)
        done!   (fn [verdict-kw]
                  (when agent
                    (hooks/fire! :agent.evaluation/done
                                 {:agent agent :round round
                                  :verdict verdict-kw :detail detail})))]
    (case verdict
      "HALLUCINATED"
      (do (swap! st-memory assoc :evaluation-status
                 {:phase :done :round round :verdict :hallucinated
                  :detail detail :reasoning (:last-reasoning m)})
          (done! :hallucinated)
          (append-evaluation-record! st-memory
                                     {:verdict "HALLUCINATED"
                                      :feedback (str "CRITICAL: your previous answer contained HALLUCINATED data. "
                                                     detail
                                                     " Use ONLY data from actual tool/code outputs — re-run them and verify with (pprint result) before answering.")})
          bt/success)

      "INCOMPLETE"
      (do (swap! st-memory assoc :evaluation-status
                 {:phase :done :round round :verdict :incomplete
                  :detail detail :reasoning (:last-reasoning m)})
          (done! :incomplete)
          (append-evaluation-record! st-memory
                                     {:verdict "INCOMPLETE"
                                      :feedback (str "Your previous answer was INCOMPLETE. " detail
                                                     " Address the gap and produce a better answer.")})
          bt/success)

      ;; COMPLETE or unrecognized -> accept
      (do (swap! st-memory assoc :evaluation-status
                 {:phase :done :round round :verdict :complete
                  :detail detail :reasoning (:last-reasoning m)})
          (done! :complete)
          (coact-stamp-answer-action ctx)))))

(defn- tool-call-pseudo-code
  "Synthesize a compact pseudo-code string for a tool invocation — used when
   compacting tool-channel iterations so `build-context-briefing` (which reads
   `:code` from previous-turns) shows the call site meaningfully."
  [{:keys [tool-name tool-args]}]
  (let [args-str (when (seq tool-args)
                   (->> tool-args
                        (map (fn [{:keys [name value]}]
                               (str name "=" (pr-str value))))
                        (str/join ", ")))]
    (str tool-name "(" (or args-str "") ")")))

(defn- compact-async-completion-record
  "Project a synthesized async-completion record (`:async-completion? true`)
   into the briefing shape. Replaces the original code (already shown when
   the LLM first emitted it) with a `[↺ async-completion …]` marker line
   so the LLM can connect the resolved value back to the originating iter
   without re-reading the source. Errors and output ride along verbatim."
  [{:keys [iteration code-results]}]
  (let [code-strs   (->> code-results
                         (map (fn [{:keys [task-id from-iteration status]}]
                                (str "[↺ async-completion"
                                     (when from-iteration
                                       (str " from iter " from-iteration))
                                     " · task-id=" (or task-id "?")
                                     " · status=" (or (some-> status name) "resolved")
                                     "]")))
                         vec)
        result-strs (->> code-results (keep :result) (remove str/blank?) vec)
        output-strs (->> code-results (keep :output) (remove str/blank?) vec)
        error-strs  (->> code-results (keep :error)  (remove str/blank?) vec)]
    (cond-> {:iteration iteration :async-completion? true}
      (seq code-strs)   (assoc :code   code-strs)
      (seq result-strs) (assoc :result result-strs)
      (seq output-strs) (assoc :output output-strs)
      (seq error-strs)  (assoc :error  error-strs))))

(defn- compact-iteration-record
  "Compact an iteration record into the shape `build-context-briefing` expects:
   {:iteration :code :result :output :error} — so prior-turn iterations render
   under `### Conversation History` in the briefing. Tool-channel iterations
   synthesize a pseudo-code line from `tool-name(args)`; the tool result rides
   as `:result`. Code-channel iterations project `:code-results` directly.

   Async-completion records (`:async-completion? true`) take a distinct
   projection — see `compact-async-completion-record`.
   In-flight-roster records (`:in-flight-roster? true`) are dropped from
   cross-turn projections — the roster is freshly recomputed each
   iteration from the live task manager, so stale snapshots from a prior
   turn are misleading."
  [{:keys [iteration channel tool-results code-results
           async-completion? in-flight-roster?]
    :as rec}]
  (cond
    in-flight-roster?
    nil

    ;; Synthetic in-loop refinement records are a within-turn artifact (the
    ;; evaluator's critique fed back to the same loop). They carry no code/tool
    ;; result worth persisting, so drop them from the cross-turn briefing.
    (= channel "evaluation")
    nil

    async-completion?
    (compact-async-completion-record rec)

    :else
    (let [code-strs   (case channel
                        "tool" (mapv tool-call-pseudo-code tool-results)
                        "code" (->> code-results
                                    (keep (fn [{:keys [lang code]}]
                                            (cond
                                              (str/blank? code) nil
                                              (= lang "clojure") code
                                              :else (str "```" lang "\n" code "\n```"))))
                                    vec)
                        [])
          result-strs (case channel
                        "tool" (->> tool-results (keep :tool-result) (remove str/blank?) vec)
                        "code" (->> code-results (keep :result)      (remove str/blank?) vec)
                        [])
          output-strs (case channel
                        "code" (->> code-results (keep :output) (remove str/blank?) vec)
                        [])
          error-strs  (->> code-results (keep :error) (remove str/blank?) vec)]
      (cond-> {:iteration iteration}
        (seq code-strs)   (assoc :code   code-strs)
        (seq result-strs) (assoc :result result-strs)
        (seq output-strs) (assoc :output output-strs)
        (seq error-strs)  (assoc :error  error-strs)))))

(defn coact-store-results-action
  "BT action: post-loop bookkeeping — trajectory, previous-turns chain,
   sandbox persistence, per-turn logging."
  [{:keys [st-memory agent opts] :as context}]
  (let [st @st-memory
        question (:question st)
        answer (:answer st)
        terminated-by (or (:terminated-by st) :answer)
        iterations (:iterations st)
        ;; Uncapped, full-turn iteration list for trajectory recording.
        traj-iterations (or (:trajectory-iterations st) iterations)
        total-iterations (count traj-iterations)
        previous-turns (or (:previous-turns st) [])
        cfg-snap (config/get-config-snapshot agent)
        enable-sandbox-persistence (:enable-sandbox-persistence cfg-snap)
        enable-trajectory-recording (get cfg-snap :enable-trajectory-recording true)
        lm-config (or (get-in context [:opts :lm-config])
                      (config/get-config agent :lm-config))
        usage-tracker (or (get-in context [:opts :usage-tracker])
                          (when agent
                            (get-in @(:!session agent) [:config :usage-tracker])))]

    ;; Persist sandbox for reuse across turns (parallel to CoAct)
    (when (and agent (:sandbox st) (not (:existing-sandbox-reused st)))
      (swap! (:!state agent) assoc :sandbox (:sandbox st)))

    ;; Append one trajectory record (all iterations + answer) to the session's
    ;; trajectory.edn. Best-effort: a write failure never breaks the turn.
    (when (and enable-trajectory-recording agent (proto/session-id agent))
      (try
        (let [model-id (when (map? lm-config) (:model lm-config))
              usage-summary (when usage-tracker
                              (try (ai.brainyard.clj-llm.interface/get-usage-summary usage-tracker)
                                   (catch Exception _ nil)))
              session-id (str (proto/session-id agent))
              traj (trajectory/build-turn-trajectory
                    {:session-id session-id
                     :agent-id (str (proto/agent-id agent))
                     :turn-id (:turn-id st)
                     :question question
                     :answer answer
                     :iterations traj-iterations
                     ;; Success = a real answer that did NOT terminate on a
                     ;; failure path (exhaustion / llm-error / none-channel guard).
                     :success (boolean
                               (and answer (not (str/blank? answer))
                                    (not (:iterations-exhausted st))
                                    (not (contains? #{:max-iterations-exhausted
                                                      :llm-error
                                                      :none-channel-loop-guard}
                                                    terminated-by))))
                     :terminated-by terminated-by
                     :total-iterations total-iterations
                     :model model-id
                     :usage-summary usage-summary
                     :started-at (:started-at st)})]
          (trajectory/append-trajectory! session-id traj)
          (swap! st-memory assoc :last-trajectory-turn (:turn-id st)))
        (catch Exception e
          (mulog/debug ::trajectory-store-failed :message (ex-message e)))))

    ;; Save compact iteration summary + answer into st-memory-init for the next turn
    (when agent
      (let [compact-iters (->> iterations
                               (keep compact-iteration-record)
                               vec
                               (#(if (> (count %) 30) (vec (take-last 30 %)) %)))
            new-turn {:question question
                      :iterations compact-iters
                      :answer (when answer
                                (clj-sandbox/truncate-to-file
                                 answer 50000 "answer" :label "answer"))}
            existing-turns (or (:previous-turns @(proto/get-st-memory-init agent)) [])
            updated-turns (prev-turns/append-turn existing-turns new-turn)]
        (swap! (proto/get-st-memory-init agent) assoc
               :previous-turns updated-turns)

        (when enable-sandbox-persistence
          (let [survival (clj-sandbox/extract-user-vars-with-survival (:sandbox st))
                kept     (:kept survival)
                lost     (:lost survival)]
            (when (or (seq kept) (seq lost))
              (swap! (:!session agent) assoc
                     :sandbox-state kept
                     :sandbox-lost-defs (vec lost)))))))

    (mulog/log ::store-results
               :agent-id (when agent (:agent-id agent))
               :turn-id (:turn-id st)
               :total-turns (:total-turns st)
               :terminated-by terminated-by
               :total-iterations total-iterations
               :answer-length (when answer (count answer)))
    bt/success))

;; ============================================================================
;; Behavior Tree — Loop subtree
;; ============================================================================

(defn coact-loop-subtree
  "Main CoAct iteration loop. Exits when :terminated is set — the ONLY
   terminator is `coact-stamp-answer-action` (an accepted answer) or
   `coact-repair-action` (fatal LLM error). A rejected answer is blanked and
   re-injected as an [evaluation] record, so the loop continues. Each iteration:
     1. inc counter + reset per-iter scratch
     2. compact context (if needed)
     3. ThinkActCode DSPy call (coact-repair-action guards malformed output)
     4. display-think
     5. router (answer[+hybrid refine gate] | code | tool | repair)
     6. accumulate iteration record"
  [max-iterations]
  (let [kw (fn [suffix] (keyword (str "coact." (namespace suffix)) (name suffix)))]
    [:repeat
     {:id (kw :repeat/iterate)
      :max-n (fn [{:keys [agent]}]
               (or (config/get-config agent :max-iterations) max-iterations))
      :condition-fn (fn [{:keys [st-memory]}]
                      (:terminated @st-memory))}

     [:sequence {:id (kw :sequence/iteration)}

      [:action {:id (kw :action/inc-iter)} coact-inc-iter-action]
      [:action {:id (kw :action/await-pending)} coact-await-pending-action]
      [:action {:id (kw :action/rebudget)} coact-rebudget-action]

      ;; One LLM call per iteration — ThinkActCode. On failure (DSPy/JSON parse
      ;; error) the same coact-repair-action handles the malformed-output kind
      ;; (it reads :dspy-error); on success the router below dispatches.
      [:fallback {:id (kw :fallback/llm-guard)}
       [:action {:id (kw :action/think-act-code)
                 :signature #'ThinkActCode
                 :operation :chain-of-thought
                 ;; system-context + user-context ride the system message
                 :stable-keys #{:system-context :user-context}
                 :debug {:source :reasoning}}
        bt/dspy]
       [:action {:id (kw :action/repair-llm-guard)}
        coact-repair-action]]

      [:action {:id (kw :action/display-think)} coact-display-think-action]

      ;; Router — precedence: answer > code > tool > repair
      [:fallback {:id (kw :fallback/router)}

       ;; Path A — answer channel + in-loop hybrid refine gate.
       ;; capture-answer-meta stamps :answer-decision; the answer-gate fallback
       ;; dispatches: :accept → terminate; :refine-self → inject + continue;
       ;; :needs-eval → EvaluateAnswer confirm (COMPLETE → terminate, reject →
       ;; inject + continue; no-evidence / eval-failure → accept-on-skip).
       [:sequence {:id (kw :sequence/answer-path)}
        [:condition {:id (kw :cond/answer-non-blank)}
         coact-answer-non-blank?]
        [:action {:id (kw :action/capture-answer-meta)}
         coact-capture-answer-meta]

        [:fallback {:id (kw :fallback/answer-gate)}

         ;; A1 — accept outright (refinement budget exhausted; also the
         ;; max-refinements=0 low/medium-effort case).
         [:sequence {:id (kw :sequence/answer-accept)}
          [:condition {:id (kw :cond/answer-accept)}
           (answer-decision-is? :accept)]
          [:action {:id (kw :action/stamp-answer)}
           coact-stamp-answer-action]]

         ;; A2 — self-reported not-done, budget remains → refine without an
         ;; eval call (inject the model's own follow-up as feedback).
         [:sequence {:id (kw :sequence/answer-refine-self)}
          [:condition {:id (kw :cond/answer-refine-self)}
           (answer-decision-is? :refine-self)]
          [:action {:id (kw :action/refine-self)}
           coact-refine-self-action]]

         ;; A3 — goal-achieved=true, budget remains → independent EvaluateAnswer
         ;; confirm. prepare-eval skips (phase :skipped) when there's no
         ;; evidence; the guard then falls through to accept-on-skip.
         [:sequence {:id (kw :sequence/answer-eval)}
          [:action {:id (kw :action/prepare-eval)}
           coact-prepare-eval-action]
          [:fallback {:id (kw :fallback/eval-llm-guard)}
           [:sequence {:id (kw :sequence/eval-llm)}
            [:condition {:id (kw :cond/eval-needs-llm)}
             coact-eval-needs-llm?]
            [:action {:id (kw :action/evaluate-answer)
                      :signature #'evaluation/EvaluateAnswer
                      :operation :chain-of-thought
                      :lm-config (fn [context]
                                   (config/resolve-eval-lm (:agent context)))
                      :debug {:source :reasoning}}
             bt/dspy]
            [:action {:id (kw :action/process-eval)}
             coact-process-eval-in-loop-action]]
           ;; accept-on-skip: no evidence to check, or EvaluateAnswer failed.
           [:action {:id (kw :action/eval-accept-on-skip)}
            coact-stamp-answer-action]]]]]

       ;; Path B — code channel
       [:sequence {:id (kw :sequence/code-path)}
        [:condition {:id (kw :cond/has-code-blocks)}
         coact-has-code-blocks?]
        [:action {:id (kw :action/display-code)}
         coact-display-code-action]
        [:action {:id (kw :action/code-eval)}
         coact-code-eval-action]
        [:action {:id (kw :action/display-eval)}
         coact-display-eval-action]
        [:action {:id (kw :action/stamp-code)}
         (coact-stamp-channel :code)]]

       ;; Path C — tool channel
       [:sequence {:id (kw :sequence/tool-path)}
        [:condition {:id (kw :cond/has-tool-calls)}
         coact-has-tool-calls?]
        [:action {:id (kw :action/display-tools)}
         coact-display-tools-action]
        [:action {:id (kw :action/tool-dispatch)}
         coact-tool-dispatch-action]
        [:action {:id (kw :action/display-tool-results)}
         coact-display-tool-results-action]
        [:action {:id (kw :action/stamp-tool)}
         (coact-stamp-channel :tool)]]

       ;; Path D — repair (nothing populated)
       [:action {:id (kw :action/repair)} coact-repair-action]]

      ;; Merge the iteration into :iterations
      [:action {:id (kw :action/accumulate)}
       coact-accumulate-iteration-action]]]))

;; ============================================================================
;; Top-Level Behavior Tree
;; ============================================================================

(defn coact-behavior-tree
  "Build the CoAct top-level BT.

   The code-block auto-detach deadline is read from config key
   `:auto-background-timeout-ms` inside `coact-code-eval-action`."
  [max-iterations]
  (let [kw (fn [suffix] (keyword (str "coact." (namespace suffix)) (name suffix)))]
    [:sequence {:id (kw :sequence/main)}

     ;; 1. Question must be present
     [:condition {:id (kw :cond/question-present)
                  :path [:question]
                  :schema ::acs/question}
      st-memory-has-value?]

     ;; 2. Per-turn context priming — st-memory :conversation + :recalled-memory
     ;; (consumed by coact-init-action and the user-context builder).
     [:action {:id (kw :action/prepare-conversation)}
      ctx-actions/prepare-conversation-action]

     [:action {:id (kw :action/prepare-recalled-memory)}
      ctx-actions/prepare-recalled-memory-action]

     ;; 3. Init sandbox + stable contexts + seeded st-memory (ONCE per turn).
     ;; coact-init-action also resets the per-turn answer-evaluation bookkeeping
     ;; (:refinement-round, :answer-complete, :iterations-exhausted). Refinement
     ;; no longer re-runs init — it happens inside the loop's answer path, which
     ;; preserves the sandbox / defs / trajectory and feeds the evaluator's
     ;; critique back through :iterations (no context rebuild).
     [:action {:id (kw :action/init)}
      coact-init-action]

     ;; 4. Main loop (with catastrophic-failure guard). The hybrid refine gate
     ;; lives in the loop's answer path: an answer is accepted (terminate) or
     ;; rejected (inject an [evaluation] record + continue), bounded by
     ;; :max-refinements rejections and :max-iterations total iterations.
     [:fallback {:id (kw :fallback/loop-guard)}
      (coact-loop-subtree max-iterations)
      [:action {:id (kw :action/loop-fallback)} coact-loop-fallback-action]]

     ;; 5. Guarantee a non-blank answer. The :repeat returns :success on
     ;; max-iterations exhaustion (it does NOT fail), so the loop-guard's
     ;; fallback branch never fires for the common 'exhausted without an answer'
     ;; case — this backfills a best-effort answer from the trajectory.
     [:action {:id (kw :action/ensure-answer)} coact-ensure-answer-action]

     ;; 6. Answer must exist by now
     [:condition {:id (kw :cond/answer-present)
                  :path [:answer]
                  :schema ::acs/answer
                  :debug {:source :st-memory}}
      st-memory-has-value?]

     ;; 7. Store trajectory + append previous-turns
     [:action {:id (kw :action/store-results)}
      coact-store-results-action]

     ;; 8. Conversation bookkeeping
     [:action {:id (kw :action/maintain-conversation)}
      trace/default-maintain-conversation]]))

;; ============================================================================
;; Agent Registration
;; ============================================================================

(def ^:private coact-instruction
  "Use the CoAct three-channel framework (tool-calls / code-blocks / answer)
to answer the user's question. Channel rules, formats, and the truncated-results
playbook are in the system context above.")

;; ============================================================================
;; Derived-agent dispatch helper
;;
;; Every derived agent (skill / search / plan / mcp / future …) shares the same
;; recipe: skill-specific :instruction / :tool-context / :agent-tools are
;; appended onto coact-agent's, and :bt-factory falls back to coact-agent's
;; when the derived agent doesn't supply one. Centralizing the wrapper keeps
;; the four call sites uniform and means a coact-agent meta change (new
;; tool, instruction tweak) propagates without touching the derived files.
;; ============================================================================

;; Forward-declare so `run-coact-derived` can reference `#'coact-agent`
;; before its `defagent` form below. Without this, a fresh JVM load fails
;; with "Unable to resolve var: coact-agent" at compile time.
(declare coact-agent)

(defn- merge-derived-instruction
  "Concatenate two instruction/tool-context strings with a blank line between.
   Either side being nil yields the other side."
  [base addition]
  (cond
    (nil? addition) base
    (nil? base)     addition
    :else           (str base "\n\n" addition)))

(def ^:private default-parent-trail-k
  "Number of parent previous-turns lifted into :parent-trail. Tunable via
   config key :parent-trail-k (set on the calling agent)."
  3)

(defn- parent-handoff-st-memory
  "Extract sub-agent context handoff fields from the parent agent.

   Returns a map with:
     :parent-trail     — vector of the parent's last K previous-turns
                         (verbatim shape, formatted by the sub-agent's
                         user-context renderer).
     :parent-agent-id  — keyword id of the parent (surfaced in the
                         sub-agent's :system-info `### Session` row).

   Returns nil when `parent-agent` is nil. Defensive — every read goes
   through `some->` so a half-constructed parent (or a stub used in
   tests) yields an empty handoff instead of crashing."
  [parent-agent]
  (when parent-agent
    (let [persistent (some-> parent-agent :!state deref :st-memory-init deref)
          parent-prev (or (:previous-turns persistent) [])
          k (config/get-config parent-agent :parent-trail-k)
          parent-trail (when (pos? k)
                         (vec (take-last k parent-prev)))
          parent-id (try (proto/agent-id parent-agent)
                         (catch Throwable _ nil))]
      (cond-> {}
        parent-id        (assoc :parent-agent-id parent-id)
        (seq parent-trail) (assoc :parent-trail parent-trail)))))

(defn- merge-derived-tools
  "Merge two :agent-tools maps by concatenating their :tools vectors with
   distinct. Either side being nil yields the other side."
  [base addition]
  (cond
    (nil? addition) base
    (nil? base)     addition
    :else           {:tools (vec (distinct (concat (:tools base)
                                                   (:tools addition))))}))

(defn run-coact-derived
  "Tool-fn for defagents that derive from coact-agent. Wraps agent/run-agent:
   when coact-agent is registered, append its :instruction, :tool-context,
   and :agent-tools onto the corresponding parameters in opts so the derived
   agent inherits the full CoAct prompt and tool roster while keeping its
   own guidance on top. :bt-factory falls back to coact-agent's when the
   derived agent doesn't supply one. The merged options are then passed to
   agent/run-agent."
  [opts]
  (let [r (meta @#'coact-agent)
        ;; M9: when invoked from another agent (call-tool path sets
        ;; :parent-agent), lift the parent's last K previous-turns and
        ;; identity into :st-memory-extra so the sub-agent's prompt has
        ;; a `## Parent Trail` block and its :system-info shows the
        ;; parent.
        handoff (parent-handoff-st-memory (:parent-agent opts))
        opts (cond-> opts
               (some? (:instruction r))
               (update :instruction  merge-derived-instruction (:instruction r))

               (some? (:tool-context r))
               (update :tool-context merge-derived-instruction (:tool-context r))

               (some? (:agent-tools r))
               (update :agent-tools  merge-derived-tools (:agent-tools r))

               (some? (:bt-factory r))
               (update :bt-factory   #(or % (:bt-factory r)))

               (seq handoff)
               (update :st-memory-extra (fnil merge {}) handoff))]
    (apply agent/run-agent (mapcat identity opts))))

(defagent coact-agent
  "CoAct (Reasoning-and-Code-and-Action) agent — unifies tool-calling and
   code-as-action in a single loop with three output channels per iteration."
  agent/run-agent
  :bt-factory (fn [{:keys [max-iterations]}]
                (coact-behavior-tree max-iterations))
  :tool-use-control {}
  :input-schema [:map
                 [:question [:string {:desc "User request or question to answer"}]]
                 [:agent-context {:optional true} [:string {:desc "Additional contextual information"}]]]
  :output-schema [:map
                  [:answer [:string {:desc "Final answer to the user's question"}]]]
  :agent-tools {:tools (vec (distinct (concat common-tools/all-common-tools
                                              common-cmds/all-common-commands)))}
  ;; :config-extra resorts to the config-schema defaults — both keys below
  ;; already equal their schema default, so they're left commented as a
  ;; per-agent opt-in/opt-out reference rather than set explicitly.
  :config-extra {;; Memory capture (L2 event pipeline) — schema default true,
                 ;; so capture is ON for coact and every derived agent.
                 ;; Per-agent OPT-OUT: uncomment as false.
                 ;; :enable-memory-capture false
                 ;; End-of-turn essence distillation (L3, LLM call) — schema
                 ;; default false, so it's OFF; it fires only on root-agent
                 ;; turns (the hook predicate elides sub-agents/specialists).
                 ;; Per-agent OPT-IN: uncomment as true.
                 ;; :enable-memory-essence true
                 }
  :instruction coact-instruction)
