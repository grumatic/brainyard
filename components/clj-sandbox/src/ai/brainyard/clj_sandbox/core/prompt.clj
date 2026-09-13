;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.core.prompt
  "Prompt construction, code extraction, and system prompt assembly.

   Two halves with different audiences — worth knowing which one you are in:

   - **Standalone RLM loop only** (`core.chat`, i.e. `session$analytics :deep`):
     the sandbox environment description, the message builders, the context-access
     section and `build-system-prompt`. Nothing outside this component calls them.
     Every claim these make is gated on being true of the target sandbox — see
     `build-system-prompt`'s `:available` / `:briefing?` and `context-discovery`.
   - **Shared with CoAct**: `build-function-directory`, `build-function-index`,
     `extract-all-code-blocks-multi` and the usage-guide registry below. The agent
     assembles its own prompt and reaches only these.

   Contains:
   - Sandbox environment description (standalone)
   - System prompt builder (slim, section-assembled; standalone)
   - Code extraction from LLM responses
   - Message building for the RLM conversation loop (standalone)
   - Function directory / index builders (shared)
   - Config helpers (model defaults)"
  (:require [clojure.string :as str]))

(def ^:dynamic *max-feedback-chars*
  "Maximum chars for stdout in basic feedback. Bind to override (default 100KB)."
  100000)

;; ============================================================================
;; Sandbox Environment
;; ============================================================================

;; --- Decomposed sandbox environment subsections ---
;; These are split so they can be individually included in system prompt or as on-demand usage-* bindings.

(defn- execution-model-core
  "## Execution Model section. The interop bullet is conditional on the SCI
   interop level (`:restricted` default vs `:full` in a container sandbox);
   the escape-hatch clause is conditional on `bash` actually being bound, since
   it is a caller-supplied tool and not part of the sandbox (see
   `sandbox/bound-symbols`)."
  [interop available]
  (str "## Execution Model
Your code runs in a **sandboxed Clojure interpreter** (SCI). Each ```clojure block is evaluated,
and the results (return value, stdout, or error) are sent back for the next iteration.
- **State persists**: `def` variables survive across iterations.
- **Captured output**: `println`/`pprint` output is captured and returned to you.
- **Errors are non-fatal**: Exceptions show the error message; sandbox state is preserved.
- **HTTP** (available at every interop level): `http/get`, `http/post`, `http/put`, `http/delete`.
  `(http/get \"https://api.example.com/x\" {})` returns `{:status :headers :body}` — plain data,
  so `(parse-json (:body r))` reads a JSON response. Opts: `:headers {\"Accept\" \"application/json\"}`,
  `:body \"…\"`, `:content-type :json`, `:timeout-ms` (default 60000).
  A non-2xx status does NOT throw — check `:status` yourself.
"
       (if (= interop :full)
         (str "- **Full Java interop**: arbitrary Java interop is available (System, Runtime, ProcessBuilder, reflection, etc.) — you are running in a container sandbox.\n"
              "- **File/shell libraries**: `slurp`, `spit`, `sh` (`(sh \"ls\" \"-l\")`), plus `clojure.java.io/*` (file, copy, reader…) and `clojure.java.shell/*` are available.")
         (str "- **Limited interop**: only WHITELISTED classes resolve — `Math`, the numeric boxes, `Thread`, `java.time`. "
              "Anything else (System, Runtime, ProcessBuilder, ClassLoader, arbitrary `java.*`) fails with `Could not resolve symbol`; there is no import to add. "
              "For what they are usually reached for: **date and time** via `java.time`, which is "
              "whitelisted and needs no interop — `(java.time.LocalDate/now)`, "
              "`(java.time.ZonedDateTime/now)`, formatted with "
              "`java.time.format.DateTimeFormatter`; OS/JVM facts via `(sys-info)`"
              (if (contains? available 'bash)
                "; environment, working directory and anything else via `(bash :command \"…\")`."
                ". There is no shell here — `bash` is not bound in this sandbox.")))
       "
- **Timeout**: 30s per code block."))

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
  "Build the first user message for the standalone RLM loop.
   Options:
     :briefing        - Pre-loaded context briefing (omitted by standalone completion)
     :iterations-text - Pre-formatted iteration history (caller-supplied)"
  [query & {:keys [briefing iterations-text]}]
  {:role "user"
   :content
   (str "Query: " query
        (when briefing
          (str "\n\n" briefing
               "\nSandbox functions and context accessors available per directory above."))
        (when iterations-text
          (str "\n\n" iterations-text))
        ;; clojure/clj ONLY. This loop extracts with `extract-code-blocks` /
        ;; `extract-all-code-blocks`, whose pattern is `(?:clojure|clj)` — a
        ;; ```python or ```bash fence is not executed, not reported, just
        ;; invisible, and the iteration comes back "No code blocks found".
        ;; (The multi-language dispatcher is `extract-all-code-blocks-multi`,
        ;; which the agent's code-eval path uses and this one does not.)
        "\n\nWrite Clojure code to accomplish this task, in a ```clojure fenced block.")})

(defn build-initial-user-message
  "Build the first user message containing the query.
   Backward-compatible wrapper around build-user-message."
  [query]
  (build-user-message query))

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
;; Modular Prompt Sections
;; ============================================================================

;; Context access — exploration pattern + all accessor docs.
;;
;; Append this ONLY when the sandbox actually has the accessors — ask
;; `sandbox/context-accessors-bound?`. `create-sandbox` builds them
;; `(when clean-context)`, so with a nil context NONE of the six functions below
;; is bound — `(context-index)` answers "Could not resolve symbol", and this
;; section is then six paragraphs instructing the model to start by calling
;; something that does not exist.
;;
;; It also used to describe `## Previous Turns` / `## Recalled Memory` sections
;; and `(trajectory$search …)`, and to demo `(context-get [:agent-state])`.
;; All four are AGENT constructs — the agent injects `:agent-state` into the
;; context it passes and registers `trajectory$search` as a tool — and the
;; standalone loop this prompt serves has none of them.
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

### What is in there
The context is the data map your caller handed this loop — nothing else. Its top-level
keys are whatever that map had, plus one synthetic key the sandbox adds:
- `(context-get [:user-vars])` — inventory of your own `def`s, refreshed each call
- `(context-search \"keyword\")` — search ALL context values recursively

**CRITICAL — context accessor results contain quotes**: These return Clojure data with embedded strings.
NEVER put the result directly into a FINAL string literal. Assign to a variable first:
```clojure
;; BAD — will cause EOF parse error:
(FINAL (str \"Rows: \" (context-get [:rows])))
;; OK — assign to variable, format for display:
(def rows (context-get [:rows]))
(FINAL (str \"row count: \" (count rows)))
```")

;; ============================================================================
;; Unified System Prompt Builder
;; ============================================================================

;; --- Slim system prompt sections ---
;;
;; There was once a second, `:structured` phrasing of every section here, for an
;; agent loop that terminated on `(FINAL …)`. That loop no longer builds its
;; prompt from this namespace — CoAct carries its own rules/footer and assembles
;; via behavior-tree's dspy-action — so the `:structured` half had no production
;; caller (only a test), and had drifted into being wrong about the very agent it
;; described: it mandated `(FINAL var)`, which CoAct explicitly disables. What
;; remains is the one phrasing the standalone RLM loop actually uses.

(defn- critical-rules
  "The rules that actually govern this loop.

   The termination bullet is the one that was measured wrong. It used to read
   \"Final answer: Rich markdown text ONLY, no code blocks\" — but prose with no
   fence reaches `chat/handle-no-code-feedback`, which bounces it with \"No code
   blocks found in your response.\" Observed live on claude-code/opus: a correct,
   complete answer at iteration 2 was rejected and re-sent verbatim inside
   `(FINAL …)` at iteration 3, so following the rule as written cost a whole
   round trip on every free-form query. The two things that DO terminate are
   `(FINAL …)` (`handle-code-evaluation`) and a ```markdown/```md/```text block
   in a response carrying no clojure fence (`handle-no-code-blocks` →
   `extract-markdown-block`).

   The FINAL-must-be-alone clause is `sandbox/split-code-at-final`: expressions
   before a FINAL in the same block make `final-stripped?` true, the pre-FINAL
   code is evaluated, and the FINAL is deferred to the next iteration with a
   NOTE. That is another silent iteration, and the rule was only ever stated in
   the `:structured` text that no caller used.

   The script bullet is gated because `write-file` and `bash` are
   caller-supplied tools; a bare sandbox binds neither."
  [available]
  (let [script? (and (contains? available 'write-file) (contains? available 'bash))]
    (str "## Critical Rules
- **SCI string escaping**: Only `\\n`, `\\t`, `\\\"`, `\\\\` are valid. Regex in a shell string needs doubled backslashes: `\\\\d` not `\\d`."
         (when script?
           " For complex scripts: write to /tmp/foo.sh via `write-file` and run with `(bash \"bash /tmp/foo.sh\")`.")
         "
- **One code block per response**: brief reasoning + ONE ```clojure fenced block. Wait for feedback. Only clojure/clj fences are executed — a ```bash or ```python fence is ignored, not run.
- **Ending the run — prose does NOT end it.** A response with no fenced block is bounced back asking for one. Finish in exactly one of two ways:
  1. `(FINAL \"your answer\")` **alone** in a ```clojure block. Other expressions in the same block defer the FINAL by one iteration — assign first, then `(FINAL v)` in the NEXT block.
  2. a ```markdown block holding the whole answer, in a response with no clojure block.
- **No XML tool-calling**: Never use `<function_calls>`, `<invoke>`, `<parameter>` — only fenced code blocks.
- **Alias once**: `(require '[clojure.string :as str])` — it persists across iterations and forks, so `str/join` works thereafter.")))

(def ^:private usage-guide-pointer
  "Appended to the rules ONLY when this prompt carries agent affordances.
   `usage$guide` is an agent-registered tool auto-bound into the sandbox; a
   standalone `completion` has no agent, so the binding is absent and the
   bullet was pointing at a function that would throw."
  "\n- Call `(usage$guide :topic <name>)` for detailed guides on any capability — e.g. `(usage$guide :topic :plans)`, `(usage$guide :topic :skills)`, `(usage$guide :topic :llm-query)`, `(usage$guide :topic :files)`. `(usage$guide)` lists topics.")

(defn- context-discovery
  "Describe ONLY the context sections this prompt actually carries.

   Both claims are conditional, and both used to be stated unconditionally:
   `## Function Directory` is emitted solely when `:function-directory` is
   supplied, and the briefing lives in a DIFFERENT message that this builder
   never sees. The standalone loop — the only live caller — passes neither, so
   its system prompt was telling the model to start from two sections that are
   not in its context. Returns nil when there is nothing present to describe,
   so the section is dropped rather than lying about it."
  [{:keys [function-directory? briefing?]}]
  (when (or function-directory? briefing?)
    (str "## Context & Functions\n"
         (when function-directory?
           "The **Function Directory** below lists all sandbox functions grouped by category (signatures).\n")
         (when briefing?
           (str "Your first user message contains a **Context Briefing** with:\n"
                "- **Data Directory** — what's accessible via `context-get` (agent state, your saved vars). Previous turns & recalled memory arrive as their own prompt sections, not `context-get`.\n"
                "- **Active State** — tool/skill/MCP counts, in-progress plans, pending todos\n"
                "- **Instructions** — project and user instructions\n"))
         "\nStart working from "
         (cond
           (and function-directory? briefing?) "the function directory and briefing"
           function-directory?                 "the function directory"
           :else                               "the briefing")
         ". Call `(usage$guide :topic <name>)` for detailed usage guides; `(usage$guide)` (no args) lists all available topics.")))

(defn- condensed-footer
  "Condensed footer for the slim system prompt.

   `max-iterations` is the caller's real loop limit. It was accepted by
   `build-system-prompt`, defaulted, and documented as \"Loop limit\" — and then
   referenced nowhere, so a caller running a 5-iteration budget stated it to
   nobody and the model paced itself against a limit it could not see.

   The workflow and efficiency bullets name tools the CALLER supplies, not the
   sandbox: `list-plans`, `bash` and `query$llm` are unbound in a bare
   `create-sandbox`. Each is gated on being present, for the same reason as the
   rules above — advice you cannot act on is worse than no advice."
  [max-iterations available briefing?]
  (str "## Workflow
1. Read the query"
       (when briefing? " + context briefing")
       ". On `[CONTINUATION]`: check `(keys (ns-publics 'user))`"
       (when (contains? available 'list-plans)
         " and `(list-plans :status :in-progress)`")
       ", resume.
2. **Reuse previous findings**: When a question relates to a previous turn, use the data from Conversation History — don't re-search or re-fetch.
3. Write reasoning + ONE code block. Wait for feedback.
4. Read feedback. Need more → another code block. Have everything → finish it the way `## Critical Rules` describes.

## Answer Format
- Rich markdown (headers, bullets, tables). Never raw data dumps.
- If results already contain the answer, write it immediately — don't re-fetch.

## Efficiency
- **Budget: " max-iterations " iteration" (when (not= 1 max-iterations) "s")
       " total.** The loop stops there whether or not you have an answer — spend them on the question, not on re-checking.
- Simple questions: answer in iteration 1.
- `def` intermediate results — re-fetching wastes iterations."
       (when (contains? available 'bash)
         "\n- Batch CLI workflows into `/tmp/script.sh`.")
       (when (contains? available 'query$llm)
         "\n- For large data: use `query$llm :prompts` (chunk → batch → aggregate).")))

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
  "Build a lean system prompt (~1000-1500 tokens) for the code-writing loop.

   Sections:
   1) Role + execution model
   2) Critical rules (FINAL, SCI, one-block, no-XML)
   3) Context discovery — emitted only when this prompt actually carries a
      function directory or its caller supplies a briefing (see context-discovery)
   4) Function directory (compact signatures, when :function-directory is provided)
   5) Brainyard instructions (when :brainyard-instructions is provided)
   6) Condensed footer (workflow, answer format, efficiency, iteration budget)
   7) Optional: instruction, agent-context, tool-context

   Options:
     :max-iterations         - Loop limit (default 20). Stated to the model in the footer.
     :instruction            - Agent-specific instructions
     :agent-context          - Agent behavioral context
     :tool-context           - Tool usage guide
     :available              - Set of symbols actually bound in the target sandbox
                               (`sandbox/bound-symbols`). Prompt text that names a
                               caller-supplied tool — `bash`, `write-file`, `list-plans`,
                               `query$llm` — is emitted only when that tool is in the set,
                               since a bare sandbox binds none of them and an instruction
                               the model cannot follow costs an iteration. Default #{}.
     :function-directory     - Compact function signatures string (from build-function-directory).
                               Rendered as a '## Function Directory' section when non-blank.
     :briefing?              - True when the caller puts a Context Briefing in the first
                               USER message (build-user-message :briefing). This builder
                               cannot see that message, so it has to be told; default false.
     :brainyard-instructions - Map {:user-instructions :project-instructions} loaded
                               via config/load-brainyard-instructions. Rendered as a
                               '## Brainyard Instructions' section when either side is non-blank.
     :interop                - SCI interop level (:restricted default | :full). Controls the
                               interop bullet in the Execution Model section.
     :return-breakdown?      - When true, returns {:content str :token-breakdown map}"
  [& {:keys [max-iterations instruction agent-context tool-context available
             function-directory briefing? brainyard-instructions interop return-breakdown?]
      :or {max-iterations 20 interop :restricted available #{}}}]
  (let [brainyard-section (when brainyard-instructions
                            (format-brainyard-instructions brainyard-instructions))
        function-directory? (and function-directory
                                 (not (str/blank? function-directory)))
        ;; `usage$guide` is an agent-registered binding; the same two inputs that
        ;; say "an agent assembled this prompt" are what say it will resolve.
        agent-affordances? (boolean (or function-directory? briefing?))
        discovery (context-discovery {:function-directory? function-directory?
                                      :briefing? briefing?})
        sections
        (cond->
         {:role-and-execution
          (str "You are an AI agent that accomplishes tasks by writing and executing code."
               "\n\n" (execution-model-core interop available))
          :critical-rules
          (cond-> (critical-rules available)
            agent-affordances? (str usage-guide-pointer))
          :footer (condensed-footer max-iterations available briefing?)}
          discovery           (assoc :context-discovery discovery)
          function-directory? (assoc :function-directory
                                     (str "## Function Directory\n" function-directory))
          brainyard-section   (assoc :brainyard-instructions brainyard-section)
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
;; CodeAct Prompt Helpers
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

(defn build-function-directory
  "Compact one-line-per-category function signatures for context briefing.
   Format: **Category**: fn1(args), fn2(args), ...
   Signatures only, no descriptions — the compact form used in a system prompt."
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
;; `(usage$guide :topic <name>)` binding is the registered `:usage$guide` tool
;; auto-bound into the sandbox, reading that open registry. clj-sandbox no
;; longer hosts guide strings.
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

