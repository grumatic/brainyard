# Agent TUI — Usage, Testing & Evaluation

Interactive terminal UI for testing agents with real-time colored output (iterations, tool calls, TODO progress, answers). Includes programmatic testing via CliClient and RLM agent self-evaluation framework.

---

## 1. Quick Start (Terminal)

```bash
cd /path/to/brainyard

# Default: react-agent + openai/gpt-4.1-mini
bb tui

# Different provider
bb tui -- anthropic

# Provider + model override
bb tui react-agent -- anthropic:claude-sonnet-4-5-20250929

# RLM agent
bb tui coact-agent -- anthropic

# Ollama (model may contain colons)
bb tui -- ollama:gemma3:12b

# Inline mode (for CliClient / piped stdin)
bb tui --inline coact-agent -- anthropic
```

## 2. CLI Syntax

```
bb tui [--inline] [agent-type] [-- provider[:model]]
```

| Argument | Default | Values |
|----------|---------|--------|
| `--inline` | *(omitted)* | Skip alt screen, use inline output (for CliClient) |
| `agent-type` | `react-agent` | `react-agent`, `coact-agent` |
| `provider` | `openai` | `openai`, `anthropic`, `ollama` |
| `model` | per-provider default | any model string |

Default models per provider:

| Provider | Default Model |
|----------|---------------|
| `openai` | `gpt-4.1-mini` |
| `anthropic` | `claude-haiku-4-5-20251001` |
| `ollama` | `glm-5:cloud` |

### Prerequisites

- `.env` in brainyard root with API keys:
  ```
  OPENAI_API_KEY=sk-proj-...
  ANTHROPIC_API_KEY=sk-ant-api03-...
  ```
- Ollama requires no API key (local server)

## 3. Quick Start (REPL)

```bash
cd /path/to/brainyard
bb repl:component agent
```

```clojure
(require '[ai.brainyard.agent-tui.core :as tui])

;; Start session
(tui/start! :react-agent :lm-provider :openai)

;; Ask questions (watches fire for real-time output)
(tui/ask "What tools are available?")

;; Inspect
(tui/status)
(tui/history)
(tui/todo)
(tui/usage)
(commands/show-traces)  ;; BT traces (also /agent trace at the prompt)

;; Change verbosity on the fly
(tui/verbosity! :verbose)  ;; show BT traces
(tui/verbosity! :quiet)    ;; answer only

;; Stop session
(tui/stop!)
```

### REPL Async Mode

```clojure
(tui/start! :react-agent :lm-provider :openai)

;; ask-async returns a Clojure agent ref
(def ref (tui/ask-async "Summarize the MCP tools available"))
(await ref)
(:output @ref)  ;; => {:result ... :answer "..."}

(tui/stop!)
```

## 4. Interactive Commands

During a `bb tui` or `(tui/run! ...)` session, the following meta-commands are available at the `>` prompt:

| Command | Description |
|---------|-------------|
| `:help` | Show available commands |
| `:quit` | Exit the TUI |
| `:status` | Show agent status (id, type, iteration, goal) |
| `:model` | Show current LM provider and model |
| `:model <name>` | Hot-swap LM model (e.g., `:model gpt-4o`, `:model claude-sonnet-4-5-latest`) |
| `:verbose` | Show current verbosity level |
| `:verbose <level>` | Set verbosity: `quiet`, `normal`, or `verbose` |
| `:history` | Show full conversation history |
| `:todo` | Show current TODO list with progress |
| `:usage` | Show LLM token count and cost |
| `:thinking` | Show BT trace entries (useful in verbose mode) |
| *(empty line)* | No-op, reprompt |
| *(any text)* | Send as question to agent |

## 5. Verbosity Levels

| Level | Shows |
|-------|-------|
| `:quiet` | Final answer only |
| `:normal` | Iteration headers, tool calls/results, TODO list, goal status, answer |
| `:verbose` | Everything in normal + BT trace entries |

## 6. Example Session

```
$ bb tui -- anthropic

LM configured: anthropic / claude-haiku-4-5-20251001
┌──────────────────────────────────────────────────────┐
│ Brainyard Agent TUI                                  │
│                                                      │
│ Agent:     react-agent                          │
│ Session:   tui-1740489600000-4821                    │
│ Verbosity: normal                                    │
└──────────────────────────────────────────────────────┘
Type your question, or :help for commands.

> What tools do you have?

You: What tools do you have?

─── Iteration 1 / 10 ─────────────────────────────────
  • Thinking: I'll check what tools are available...
  → list-tools()
  ← list-tools: [{:name "list-tools" ...}, ...]
  ✓ Goal achieved (Listed all available tools)

┌──────────────────────────────────────────────────────────────────────┐
│ Answer                                                               │
│                                                                      │
│ Here are the tools I have access to:                                 │
│ 1. list-tools - List all available tools                             │
│ 2. common__agent-runtime$config - Read/set runtime configuration      │
│ ...                                                                  │
└──────────────────────────────────────────────────────────────────────┘
1 calls │ 1,234 tokens │ $0.0012

> :usage
1 calls │ 1,234 tokens │ $0.0012

> :quit

TUI session ended.
```

## 7. Runtime Configuration

The agent supports runtime configuration that persists across turns within a session.

### Config Keys

See `core/config.clj` `config-schema` for the full list of keys (e.g. `compaction-target-ratio`, `enable-context-budget`, `react-loop-mode`).

### Checking/Changing Config

Ask the agent to call `common__agent-runtime$config` (no args to read, `key`+`value` to set):

```
> Check the current runtime configuration
  → common__agent-runtime$config()
  ← {:runtime-config {:compaction-target-ratio 0.2, ...}}

> Tighten the cross-turn compaction target
  → common__agent-runtime$config(key="compaction-target-ratio", value="0.1")
  ← Config 'compaction-target-ratio' set to 0.1. Effective from next question.
```

## 8. Advanced REPL Usage

### Custom Options

```clojure
(tui/start! :react-agent
  :user-id "dev-user"
  :session-id "my-debug-session"
  :max-iterations 5
  :verbosity :verbose
  :lm-provider :anthropic
  :lm-model "claude-sonnet-4-5-20250929")
```

### Multi-Turn Conversation

```clojure
(tui/start! :react-agent :lm-provider :openai)

(tui/ask "List all configured MCP servers")
(tui/ask "Start the aws-mcp server")
(tui/ask "What MCP tools are now available?")

(tui/history)  ;; see full conversation
(tui/stop!)
```

### MCP Server Testing

```clojure
(require '[ai.brainyard.agent.mcp.integration :as mcp-int])
(mcp-int/initialize-mcp-system!)

(tui/start! :react-agent :lm-provider :openai)
(tui/ask "List MCP servers and start the context7 server")
(tui/ask "Look up Reagent documentation using MCP tools")
(tui/stop!)
```

### Blocking Terminal Loop from REPL

```clojure
(tui/run! :react-agent :lm-provider :openai :verbosity :verbose)
;; Interactive loop starts, type questions at > prompt
;; :quit to exit
```

---

## 9. Programmatic Testing (CliClient)

Programmatic testing of `bb tui` (or any CLI process) using `ai.brainyard.agent.stdio.client`. Replaces brittle sleep-based pipe scripting with pattern-matching and idle-detection on real-time captured stdout.

### 9.1 CliClient Quick Start

```clojure
(require '[ai.brainyard.agent.stdio.client :as cli])

;; Spawn bb tui (sources .env for API keys, ~15s JVM startup)
(def c (cli/start!
        :command ["bash" "-c"
                  "cd /path/to/brainyard && set -a && source .env && bb tui react-agent -- openai"]
        :working-dir "/path/to/brainyard"))

;; Wait for welcome banner
(cli/wait-for c #"(?i)brainyard|agent tui" :timeout-ms 60000)
(cli/wait-for-idle c :idle-ms 3000 :timeout-ms 30000)

;; Ask a question, wait for answer
(cli/clear-output! c)
(cli/send-line! c "What is 2+2?")
(cli/wait-for-idle c :idle-ms 5000 :timeout-ms 120000)

;; Inspect output
(run! println (cli/get-all-output c))

;; Cleanup
(cli/shutdown! c)
(.close c)
```

### 9.2 How `bb tui` Behaves Under ProcessBuilder

When spawned via ProcessBuilder (stdin is not a TTY):

- `stdin-terminal?` returns `false` — no raw mode, no `/dev/tty` reader thread
- Falls back to `BufferedReader.readLine()` for input (line-oriented)
- **Fullscreen layout is still active** — output contains ANSI cursor positioning codes (`ESC[21;1H`, `ESC[2K`, etc.)
- Status bar updates appear as repeated lines with token/cost info
- Exits on EOF or `:quit` command, calls `System.exit(0)` (kills child JVM, not parent REPL)

Use `--inline` mode to skip alt screen when using CliClient.

### 9.3 ANSI Output Handling

The TUI's `ansi.clj` does **not** check the `NO_COLOR` env var — colors are controlled by an internal atom (`!color-enabled`). All output from `bb tui` contains ANSI escape codes. Strip them for assertions:

```clojure
(require '[clojure.string :as str])

(defn strip-ansi [s]
  (str/replace s #"\033\[[0-9;]*[A-Za-z]|\033\[\?[0-9]*[hl]" ""))

(defn clean-text
  "Strip ANSI from all lines, join into one searchable lowercase string."
  [lines]
  (str/lower-case (str/join " " (map strip-ansi lines))))

;; Usage
(let [text (clean-text (cli/get-all-output c))]
  (str/includes? text "4"))
;; => true
```

### 9.4 The `ask-and-wait` Pattern

The recommended pattern for sending questions and waiting for answers:

```clojure
(defn ask-and-wait
  "Send a question, wait for TUI to echo it back (proves receipt),
   then wait for output to go idle (proves answer complete).
   Returns all lines captured since before the question was sent."
  [c question & {:keys [echo-timeout-ms idle-ms idle-timeout-ms]
                 :or   {echo-timeout-ms 15000
                        idle-ms         5000
                        idle-timeout-ms 180000}}]
  (cli/clear-output! c)
  (cli/send-line! c question)
  ;; Wait for the TUI to echo our question back as "You: ..."
  (let [echo-word (first (str/split question #"\s+" 2))]
    (cli/wait-for c (re-pattern (str "(?i)" (java.util.regex.Pattern/quote echo-word)))
                  :timeout-ms echo-timeout-ms))
  ;; Now wait for idle — answer generation + rendering is done
  (cli/wait-for-idle c :idle-ms idle-ms :timeout-ms idle-timeout-ms)
  ;; Return all output captured during this question
  (cli/get-all-output c))
```

#### Why This Pattern (Not `wait-for #"tokens"`)

The TUI renders a status bar with `N calls | M tokens | $X.XXXX` on every update. In a multi-turn session, the status bar already shows non-zero values from previous questions. Using `wait-for #"tokens"` will match a **stale** status bar update before the new answer arrives.

The correct two-step approach:
1. **`wait-for` the question echo** — the TUI prints `You: <question>` when it receives input
2. **`wait-for-idle`** — once the LLM finishes, stdout goes quiet. A 5-second idle threshold reliably detects answer completion

### 9.5 REPL Tests

#### Single Question

```clojure
(require '[ai.brainyard.agent.stdio.client :as cli]
         '[clojure.string :as str])

(def brainyard-root "/path/to/brainyard")

(def c (cli/start!
        :command ["bash" "-c"
                  (str "cd " brainyard-root
                       " && set -a && source .env"
                       " && bb tui react-agent -- openai")]
        :working-dir brainyard-root))

;; Wait for startup
(cli/wait-for c #"(?i)brainyard|agent tui" :timeout-ms 60000)
(cli/wait-for-idle c :idle-ms 3000 :timeout-ms 30000)

;; Ask
(cli/clear-output! c)
(cli/send-line! c "What is 2+2? Answer with just the number.")
(cli/wait-for c #"(?i)What" :timeout-ms 15000)  ;; wait for echo
(cli/wait-for-idle c :idle-ms 5000 :timeout-ms 120000) ;; wait for answer

;; Check
(let [text (clean-text (cli/get-all-output c))]
  (println "Contains '4'?" (str/includes? text "4"))
  (println "Tokens tracked?" (boolean (re-find #"[1-9][0-9,]* tokens" text))))
;; => Contains '4'? true
;; => Tokens tracked? true

;; Cleanup
(cli/shutdown! c)
(.close c)
```

#### Multi-Turn Conversation

```clojure
;; (assume c is already started and ready)

;; Turn 1
(let [output (ask-and-wait c "What is 2+2? Answer with just the number.")
      text   (clean-text output)]
  (println "Turn 1 — contains '4'?" (str/includes? text "4")))

;; Turn 2 — agent retains conversation context
(let [output (ask-and-wait c "Name the three primary colors of light. Just list them.")
      text   (clean-text output)]
  (println "Turn 2 — red?" (str/includes? text "red")
           "green?" (str/includes? text "green")
           "blue?" (str/includes? text "blue")))
```

### 9.6 Meta-Commands Testing

```clojure
;; :status
(cli/clear-output! c)
(cli/send-line! c ":status")
(let [output (cli/wait-for-idle c :idle-ms 3000 :timeout-ms 15000)
      text   (clean-text output)]
  (println "Agent info?" (str/includes? text "agent")))

;; :help
(cli/clear-output! c)
(cli/send-line! c ":help")
(let [output (cli/wait-for-idle c :idle-ms 3000 :timeout-ms 15000)
      text   (clean-text output)]
  (println "Shows :quit?" (str/includes? text ":quit")))
```

### 9.7 Changing Provider / Model

The provider is set at `bb tui` launch time via the `-- provider:model` argument:

```clojure
;; OpenAI (default model: gpt-4.1-mini)
(def c (cli/start!
        :command ["bash" "-c" "cd ROOT && set -a && source .env && bb tui -- openai"]))

;; Anthropic with specific model
(def c (cli/start!
        :command ["bash" "-c" "cd ROOT && set -a && source .env && bb tui -- anthropic:claude-sonnet-4-5-20250929"]))

;; Ollama
(def c (cli/start!
        :command ["bash" "-c" "cd ROOT && set -a && source .env && bb tui -- ollama:gemma3:12b"]))
```

### 9.8 Full Integration Test Script

Copy to a temp file and `load-file` from the REPL. Contains its own mini test harness with PASS/FAIL reporting.

```clojure
(require '[ai.brainyard.agent.stdio.client :as cli]
         '[clojure.string :as str])

;; --- Helpers ----------------------------------------------------------------
(defn strip-ansi [s]
  (str/replace s #"\033\[[0-9;]*[A-Za-z]|\033\[\?[0-9]*[hl]" ""))

(defn clean-text [lines]
  (str/lower-case (str/join " " (map strip-ansi lines))))

(def brainyard-root "/path/to/brainyard")  ;; <-- EDIT THIS
(def results (atom {:tests 0 :pass 0 :fail 0}))

(defn check [label pred]
  (swap! results update :tests inc)
  (if pred
    (do (swap! results update :pass inc)
        (println (str "  PASS  " label)))
    (do (swap! results update :fail inc)
        (println (str "  FAIL  " label)))))

(defn ask-and-wait [c question & {:keys [idle-ms idle-timeout-ms]
                                   :or {idle-ms 5000 idle-timeout-ms 180000}}]
  (cli/clear-output! c)
  (cli/send-line! c question)
  (let [echo-word (first (str/split question #"\s+" 2))]
    (cli/wait-for c (re-pattern (str "(?i)" (java.util.regex.Pattern/quote echo-word)))
                  :timeout-ms 15000))
  (cli/wait-for-idle c :idle-ms idle-ms :timeout-ms idle-timeout-ms)
  (cli/get-all-output c))

;; --- Run --------------------------------------------------------------------
(println "\n========================================")
(println " CliClient + bb tui Integration Test")
(println "========================================\n")

(def c (cli/start!
        :command ["bash" "-c"
                  (str "cd " brainyard-root
                       " && set -a && source .env"
                       " && bb tui react-agent -- openai")]
        :working-dir brainyard-root))

(try
  ;; 1. Startup
  (println "[1/5] Waiting for startup...")
  (let [banner (cli/wait-for c #"(?i)brainyard|agent tui" :timeout-ms 60000)]
    (check "Process alive" (cli/alive? c))
    (check "Welcome banner"
           (str/includes? (clean-text banner) "brainyard agent tui")))
  (cli/wait-for-idle c :idle-ms 3000 :timeout-ms 30000)

  ;; 2. Simple math question
  (println "\n[2/5] Asking: What is 2+2?")
  (let [text (clean-text (ask-and-wait c "What is 2+2? Answer with just the number."))]
    (check "Answer mentions '4'" (str/includes? text "4"))
    (check "Token usage tracked" (boolean (re-find #"[1-9][0-9,]* tokens" text))))

  ;; 3. :status command
  (println "\n[3/5] Sending :status")
  (cli/clear-output! c)
  (cli/send-line! c ":status")
  (let [text (clean-text (cli/wait-for-idle c :idle-ms 3000 :timeout-ms 15000))]
    (check ":status shows agent info"
           (or (str/includes? text "agent") (str/includes? text "react-agent"))))

  ;; 4. Second question (multi-turn)
  (println "\n[4/5] Asking: Primary colors of light")
  (let [text (clean-text (ask-and-wait c "Name the three primary colors of light. Just list them."))]
    (check "Mentions red"   (str/includes? text "red"))
    (check "Mentions green" (str/includes? text "green"))
    (check "Mentions blue"  (str/includes? text "blue")))

  ;; 5. Shutdown
  (println "\n[5/5] Shutdown")
  (check "Substantial output" (> (count (cli/get-all-output c)) 20))
  (let [code (cli/shutdown! c :timeout-ms 15000)]
    (check "Exit code 0" (zero? code))
    (check "Process dead" (not (cli/alive? c))))

  (catch Exception e
    (println "\nFATAL:" (ex-message e))
    (doseq [line (take-last 10 (cli/get-all-output c))]
      (println "  " (strip-ansi line))))
  (finally
    (.close c)))

;; --- Summary ----------------------------------------------------------------
(let [{:keys [tests pass fail]} @results]
  (println "\n========================================")
  (println (str " Results: " pass "/" tests " passed"
               (when (pos? fail) (str ", " fail " FAILED"))))
  (println "========================================"))
```

### 9.9 Comparison: Sleep-Based vs CliClient

| Aspect | Subshell + Sleep | CliClient |
|--------|-----------------|-----------|
| Startup wait | `sleep 8` (guess) | `wait-for #"Agent TUI"` (exact) |
| Question wait | `sleep 120` (guess) | `wait-for-idle :idle-ms 5000` (exact) |
| Total time | ~130s minimum | As fast as LLM responds (~15-30s) |
| Output capture | After-the-fact via log file | Real-time line buffer |
| Assertions | `grep` on log file | Direct on captured lines |
| Multi-turn | Hardcoded sleep gaps | `ask-and-wait` per turn |
| Cleanup | Process may linger | `shutdown!` + `.close` |
| REPL-friendly | No (blocks shell) | Yes (all async reads) |

### 9.10 Unit Tests (No LLM Required)

The unit test suite uses lightweight subprocesses (`/bin/cat`, `bash -c`) — no API keys or network access needed:

```bash
cd components/agent

clojure -M:test -e "
(require 'clojure.test 'ai.brainyard.agent.stdio-test)
(clojure.test/run-tests 'ai.brainyard.agent.stdio-test)
(shutdown-agents)"
```

23 tests, 44 assertions covering: lifecycle, send/wait-for (string and regex), cursor advancement, get-output, get-all-output, clear-output!, wait-for-idle, shutdown, working-dir, env vars, multi-turn interaction, mixed wait strategies, immediate exit, non-zero exit codes, and rapid output (200 lines).

### 9.11 CliClient API Reference

| Function | Description |
|----------|-------------|
| `start! :command [...] :working-dir "..." :env {...}` | Spawn process, start reader threads, return `CliClient` |
| `send-line! client text` | Write text + `\n` to stdin, flush |
| `wait-for client pattern :timeout-ms N` | Block until a line matches (string or regex), return lines, advance cursor |
| `wait-for-idle client :idle-ms N :timeout-ms M` | Block until no new output for N ms, return lines, advance cursor |
| `get-output client` | Return new lines since cursor, advance cursor |
| `get-all-output client` | Return all lines (full buffer), do NOT advance cursor |
| `clear-output! client` | Advance cursor to current end (skip everything) |
| `shutdown! client :timeout-ms N` | Send `:quit`, wait for exit, return exit code |
| `alive? client` | Check if process is still running |
| `.close client` | Destroy process, interrupt reader threads (`Closeable`) |

#### Cursor Model

The `!cursor` atom tracks the read position in the `!lines` buffer. Functions that "advance" the cursor move it past the returned lines, so subsequent calls only see new output.

```
!lines:   [line-0] [line-1] [line-2] [line-3] [line-4]
                              ^cursor (after wait-for matched line-2)

get-output → [line-3, line-4], cursor → 5
get-output → [], cursor stays at 5
get-all-output → [line-0 .. line-4], cursor stays at 5 (read-only)
clear-output! → cursor → 5 (skip to end)
```

---

## 10. RLM Agent Evaluation Framework

REPL-driven self-evaluation framework that systematically tests each RLM sandbox function/tool category via CliClient, identifies errors and improvement points, and reports them.

### 10.1 Quick Start

```bash
# Start agent component nREPL
cd /path/to/brainyard
bb repl:component agent
```

```clojure
;; Load framework (creates it in rlm-eval namespace)
(load-file "/tmp/rlm-eval-framework.clj")
(in-ns 'rlm-eval)

;; Run all test categories
(run-all)

;; Run single category
(run-one "file-io")
(run-one "core-sandbox")
(run-one "skills")
```

### 10.2 Test Categories

5 categories, 14 tests total. Each category spawns a fresh `bb tui --inline coact-agent -- anthropic` via CliClient.

| Category | Tests | Multi-turn |
|----------|-------|------------|
| `core-sandbox` | question-var, list-tools, get-tool, inspect | No |
| `file-io` | read-file, read-glob, slurp-spit | No |
| `context-accessors` | setup, get-previous-run, search-conversation | Yes |
| `skills` | skills-list, skills-read, skills-find | No |
| `task-management` | task$run | No |

Each test defines:
- **question** — natural language question sent to the agent
- **expected-fns** — function names that should appear in the LLM's code blocks
- **anti-fns** — function names that should NOT appear (e.g., `task$run :job-type :bash` for file reads)
- **max-iters** — expected iteration budget

### 10.3 Evaluation Criteria

For each test, the framework checks:

1. **Answer present** — `str/includes? "Answer"` in TUI output
2. **Iteration count** — should be <= `max-iters`
3. **Expected functions used** — string match in ANSI-stripped output
4. **Anti-patterns absent** — no bash fallback for file reads, etc.
5. **Errors detected** — EOF while reading, unresolved symbols, ClassNotFoundException, NPE

### 10.4 Scoring: Self-Correcting Errors

EOF errors where the LLM self-corrects (answer is present despite error) are downgraded to **warnings**, not failures. This reflects the system's graceful error recovery — the LLM embeds data in FINAL, gets parse error, then uses a variable on retry.

```
PASSED = has-answer AND no :error-severity issues
EOF + has-answer → :warn (not :error)
EOF + no answer  → :error (true failure)
```

### 10.5 Report Format

```
══════════════════════════════════════════════════
         RLM AGENT EVALUATION REPORT
══════════════════════════════════════════════════

ERRORS (must fix):
  [FAIL] file-io/read-file — No answer produced

ANTI-PATTERNS (should fix):
  [WARN] skills/skills-find — Error: EOF while reading (self-corrected)

IMPROVEMENTS (nice to have):
  [INFO] core-sandbox/inspect — Took 4 iterations (expected <=3)

PASSED: 13/14
FAILED: 1/14
WARNINGS: 1
══════════════════════════════════════════════════
```

### 10.6 Latest Results (Baseline)

As of 2026-03-07, with `anthropic` provider:

```
PASSED: 14/14
FAILED: 0/14
WARNINGS: 3 (all self-correcting EOF in FINAL)
```

| Category | Tests | Result |
|----------|-------|--------|
| core-sandbox | 4 | 4/4 pass |
| file-io | 3 | 3/3 pass |
| context-accessors | 3 | 3/3 pass (2 warnings) |
| skills | 3 | 3/3 pass (1 warning) |
| task-management | 1 | 1/1 pass |

### 10.7 Complete Framework Source

Save to `/tmp/rlm-eval-framework.clj` and `load-file` from REPL:

```clojure
(ns rlm-eval
  "Self-evaluation framework for RLM agent sandbox functions and tools.
   Run via REPL: (load-file \"/tmp/rlm-eval-framework.clj\")")

(require '[ai.brainyard.agent.stdio.client :as cli]
         '[ai.brainyard.agent.core.agent :as ag]
         '[clojure.string :as str]
         :reload-all)

;; ============================================================================
;; Utilities
;; ============================================================================

(defn strip-ansi [s]
  (str/replace s #"\033\[[0-9;]*[A-Za-z]|\033\[\?[0-9]*[hl]" ""))

(defn extract-iterations [raw]
  (let [iters (re-seq #"Iteration (\d+)" raw)]
    (when (seq iters) (apply max (map #(parse-long (second %)) iters)))))

(defn extract-errors [clean]
  (let [lines (str/split-lines clean)]
    (->> lines
         (filter #(or (re-find #"EOF while reading" %)
                      (re-find #"(?i)could not resolve symbol" %)
                      (re-find #"(?i)ClassNotFoundException" %)
                      (re-find #"(?i)NullPointerException" %)))
         ;; Exclude "No code blocks" — normal LLM behavior, self-corrects
         (remove #(re-find #"No code blocks" %))
         (mapv str/trim)
         (distinct)
         (vec))))

;; ============================================================================
;; Test Suite Definition
;; ============================================================================

(def test-suite
  [{:category "core-sandbox"
    :tests [{:name "question-var"
             :question "what is the exact text of the user's question? just print the question variable and return it"
             :expected-fns ["question"]
             :anti-fns ["task$run :job-type :bash"]
             :max-iters 2}
            {:name "list-tools"
             :question "list all available tools grouped by type"
             :expected-fns ["list-tools"]
             :max-iters 2}
            {:name "get-tool"
             :question "show the full input schema of the task$run :job-type :bash tool using get-tool"
             :expected-fns ["get-tool"]
             :max-iters 3}
            {:name "inspect"
             :question "call (call-tool \"task$list\" {}), then use (inspect result) on the result and describe what you see"
             :expected-fns ["inspect"]
             :max-iters 3}]}
   {:category "file-io"
    :tests [{:name "read-file"
             :question "read the first 20 lines of components/clj-sandbox/deps.edn using read-file"
             :expected-fns ["read-file"]
             :anti-fns ["task$run :job-type :bash" "cat " "slurp"]
             :max-iters 3}
            {:name "read-glob"
             :question "use read-glob to find all deps.edn files under components/ and count them"
             :expected-fns ["read-glob"]
             :anti-fns ["task$run :job-type :bash"]
             :max-iters 4}
            {:name "slurp-spit"
             :question "write numbers 1 to 5 to /tmp/eval-nums.txt using spit, read back with slurp, sum them"
             :expected-fns ["spit" "slurp"]
             :anti-fns ["task$run :job-type :bash"]
             :max-iters 4}]}
   {:category "context-accessors"
    :multi-turn true
    :tests [{:name "setup"
             :question "what is 2+2?"
             :expected-fns ["FINAL"]
             :max-iters 2}
            {:name "get-previous-run"
             :question "use get-previous-run to show what code blocks were executed in the last run"
             :expected-fns ["get-previous-run"]
             :max-iters 4}
            {:name "search-conversation"
             :question "use search-conversation to find any turns that mention the number 4"
             :expected-fns ["search-conversation"]
             :max-iters 4}]}
   {:category "skills"
    :tests [{:name "skills-list"
             :question "what CLI skills are installed? list them all"
             :max-iters 2}
            {:name "skills-read"
             :question "use skills-read to read the aws-cli skill and give a 3-line summary"
             :expected-fns ["skills-read"]
             :max-iters 4}
            {:name "skills-find"
             :question "use skills-find to search for kubernetes skills"
             :expected-fns ["skills-find"]
             :max-iters 4}]}
   {:category "task-management"
    :tests [{:name "bash"
             :question "run 'echo hello-from-eval' as a background bash task using task$run :job-type :bash, wait for it, and show the output"
             :expected-fns ["task$run :job-type :bash"]
             :max-iters 6}]}])

;; ============================================================================
;; Turn Runner
;; ============================================================================

(defn run-turn [c question {:keys [timeout-ms idle-ms]
                            :or {timeout-ms 120000 idle-ms 15000}}]
  (cli/clear-output! c)
  (cli/send-line! c question)
  (cli/wait-for-idle c :idle-ms idle-ms :timeout-ms timeout-ms)
  (let [raw (str/join "\n" (cli/get-all-output c))
        clean (strip-ansi raw)]
    (Thread/sleep 1500)
    {:raw raw :clean clean}))

;; ============================================================================
;; Evaluation Logic
;; ============================================================================

(defn evaluate-turn [test-def output]
  (let [{:keys [name expected-fns anti-fns max-iters]} test-def
        {:keys [raw clean]} output
        iters (extract-iterations raw)
        has-answer (str/includes? raw "Answer")
        errors (extract-errors clean)

        ;; Check expected functions used
        expected-fns (or expected-fns [])
        expected-results (mapv (fn [f] {:fn f :used (str/includes? clean f)}) expected-fns)
        all-expected-used (every? :used expected-results)

        ;; Check anti-patterns
        anti-fns (or anti-fns [])
        anti-detected (filterv #(str/includes? clean %) anti-fns)

        ;; Iteration check
        over-iters (when (and iters max-iters (> iters max-iters)) iters)

        ;; Build result
        issues []
        issues (if (not has-answer)
                 (conj issues {:severity :error :msg "No answer produced"})
                 issues)
        ;; EOF errors that self-correct (answer present) are warnings, not errors
        issues (if (seq errors)
                 (let [sev (if has-answer :warn :error)]
                   (into issues (mapv #(hash-map :severity sev :msg (str "Error: " (subs % 0 (min 100 (count %))))) errors)))
                 issues)
        issues (if (and (seq expected-fns) (not all-expected-used))
                 (let [missing (filterv #(not (:used %)) expected-results)]
                   (conj issues {:severity :warn :msg (str "Expected function not used: " (str/join ", " (map :fn missing)))}))
                 issues)
        issues (if (seq anti-detected)
                 (conj issues {:severity :warn :msg (str "Anti-pattern: used " (str/join ", " anti-detected))})
                 issues)
        issues (if over-iters
                 (conj issues {:severity :info :msg (str "Took " over-iters " iterations (expected <=" max-iters ")")})
                 issues)

        passed (and has-answer (empty? (filter #(= :error (:severity %)) issues)))]

    {:name name
     :passed passed
     :iterations iters
     :answer-present has-answer
     :expected-fn-results expected-results
     :anti-detected anti-detected
     :issues issues}))

;; ============================================================================
;; Category Runner
;; ============================================================================

(defn start-client []
  (reset! @#'ag/!agent-registry {})
  (cli/start! :command ["bb" "tui" "--inline" "coact-agent" "--" "anthropic"]
              :working-dir "/Users/you/Projects/brainyard"))

(defn run-category [{:keys [category tests multi-turn]}]
  (println (str "\n╔══════════════════════════════════════╗"))
  (println (str "║  Category: " category (apply str (repeat (max 0 (- 25 (count category))) " ")) "║"))
  (println (str "╚══════════════════════════════════════╝"))

  (let [c (start-client)]
    (try
      (println "  Waiting for TUI startup...")
      (cli/wait-for c #"Brainyard" :timeout-ms 60000)
      (Thread/sleep 2000)

      (let [results (atom [])]
        (doseq [{:keys [name question] :as test-def} tests]
          (println (str "  ▶ " name ": " (subs question 0 (min 60 (count question))) "..."))
          (let [output (run-turn c question {})
                result (evaluate-turn test-def output)]
            (swap! results conj (assoc result :category category))
            (println (str "    " (if (:passed result) "✓ PASS" "✗ FAIL")
                          " | iters=" (:iterations result)
                          (when (seq (:issues result))
                            (str " | " (count (:issues result)) " issues"))))))

        @results)
      (finally
        (.close c)))))

;; ============================================================================
;; Report Generator
;; ============================================================================

(defn generate-report [all-results]
  (let [errors (for [r all-results
                     i (:issues r)
                     :when (= :error (:severity i))]
                 {:test (str (:category r) "/" (:name r)) :msg (:msg i)})
        warnings (for [r all-results
                       i (:issues r)
                       :when (= :warn (:severity i))]
                   {:test (str (:category r) "/" (:name r)) :msg (:msg i)})
        infos (for [r all-results
                    i (:issues r)
                    :when (= :info (:severity i))]
                {:test (str (:category r) "/" (:name r)) :msg (:msg i)})
        total (count all-results)
        passed (count (filter :passed all-results))
        failed (- total passed)]

    (println "\n══════════════════════════════════════════════════")
    (println "         RLM AGENT EVALUATION REPORT")
    (println "══════════════════════════════════════════════════")

    (when (seq errors)
      (println "\nERRORS (must fix):")
      (doseq [{:keys [test msg]} errors]
        (println (str "  [FAIL] " test " — " msg))))

    (when (seq warnings)
      (println "\nANTI-PATTERNS (should fix):")
      (doseq [{:keys [test msg]} warnings]
        (println (str "  [WARN] " test " — " msg))))

    (when (seq infos)
      (println "\nIMPROVEMENTS (nice to have):")
      (doseq [{:keys [test msg]} infos]
        (println (str "  [INFO] " test " — " msg))))

    (when (and (empty? errors) (empty? warnings) (empty? infos))
      (println "\n  All tests passed cleanly!"))

    (println (str "\nPASSED: " passed "/" total))
    (println (str "FAILED: " failed "/" total))
    (println (str "WARNINGS: " (count warnings)))
    (println "══════════════════════════════════════════════════")

    {:errors (vec errors) :warnings (vec warnings) :infos (vec infos)
     :total total :passed passed :failed failed
     :results all-results}))

;; ============================================================================
;; Main Entry Points
;; ============================================================================

(defn run-all
  "Run all test categories and generate report."
  ([] (run-all test-suite))
  ([suite]
   (let [all-results (atom [])]
     (doseq [category suite]
       (let [results (run-category category)]
         (swap! all-results into results)))
     (generate-report @all-results))))

(defn run-one
  "Run a single category by name. E.g. (run-one \"file-io\")"
  [category-name]
  (let [cat (first (filter #(= category-name (:category %)) test-suite))]
    (if cat
      (let [results (run-category cat)]
        (generate-report results))
      (println (str "Category not found: " category-name
                    ". Available: " (str/join ", " (map :category test-suite)))))))

(println "\n═══ RLM Eval Framework Loaded ═══")
(println "Commands:")
(println "  (run-all)              — run all categories")
(println "  (run-one \"file-io\")    — run single category")
(println "  Categories: " (str/join ", " (map :category test-suite)))
```

---

## 11. Architecture

```
tui/
├── core.clj      # Public API: start!, ask, ask-async, run!, stop!, status, etc.
├── session.clj   # State atom, watch callbacks (st-memory + session), emit!
├── format.clj    # Pure formatting functions (data → string, no I/O)
├── layout.clj    # Fullscreen/inline layout, scroll region, status bar
└── ansi.clj      # !color-enabled atom, ANSI escape constants
```

### How Output Works

1. `start!` attaches atom watches on the agent's state-memory and session
2. When `ask` triggers a BT run, the agent updates state-memory keys (`:iteration-count`, `:tool-calls`, `:answer`, etc.)
3. Watches fire on each state change and call formatting functions
4. `emit!` writes formatted strings thread-safely to the output writer
5. For nREPL: `*out*` is captured so background watch threads can write to the REPL output

### Key State Keys (watched)

| st-memory Key | Output |
|--------------|--------|
| `:iteration-count` | Iteration header (e.g., `--- Iteration 2 / 5 ---`) |
| `:last-reasoning` | Thought/reasoning text |
| `:tool-calls` | Tool invocations with args |
| `:tool-results` | Tool return values (incremental) |
| `:observation` | Observation text |
| `:goal-achieved` | Goal status banner |
| `:todo-list` | TODO progress with checkmarks |
| `:answer` | Final answer in green box (always shown, even in quiet mode) |

### Implementation Files

| File | Role |
|------|------|
| `stdio/client.clj` | `CliClient` record, `start!`, `send-line!`, `wait-for`, `wait-for-idle`, `shutdown!` |
| `mcp/client.clj` | MCP client — same ProcessBuilder pattern (reference implementation) |
| `tui/core.clj` | `run!` main loop, `stdin-terminal?` check, raw/cooked mode, input handling |
| `tui/session.clj` | `handle-st-memory-change` watch — renders iterations, thinking, tool calls, answer |
| `tui/format.clj` | `format-welcome-banner`, ANSI formatting, markdown rendering |
| `tui/layout.clj` | Fullscreen/inline layout, scroll region, status bar |
| `tui/ansi.clj` | `!color-enabled` atom, ANSI escape constants |
| `test/stdio_test.clj` | 23 unit tests using `/bin/cat` and `bash -c` (no LLM) |

---

## 12. Troubleshooting

### `wait-for` matches stale status bar (multi-turn)

**Symptom**: `wait-for c #"tokens"` matches immediately with old token count before the new answer arrives.

**Fix**: Use the two-step `ask-and-wait` pattern — wait for the question echo first, then `wait-for-idle`.

### JVM startup timeout

**Symptom**: `wait-for` times out waiting for the welcome banner.

**Fix**: Increase timeout to 60-90 seconds. The `bb tui` task starts a full Clojure JVM via `clojure -M -e`, which takes 10-20 seconds depending on classpath size and machine load.

### Missing API keys

**Symptom**: Process starts but agent fails with authentication errors.

**Fix**: The `bb tui` task sources `.env` via `set -a && source .env`. Verify `.env` exists in the brainyard root and contains required keys (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, etc.). When using ProcessBuilder, the command must be wrapped in `bash -c` to source `.env`:

```clojure
:command ["bash" "-c" "cd /path/to/brainyard && set -a && source .env && bb tui ..."]
```

### ANSI codes in assertions

**Symptom**: `str/includes?` fails even though the text is visibly present in output.

**Fix**: Always strip ANSI before assertions. The TUI outputs fullscreen cursor positioning codes (`ESC[row;colH`, `ESC[2K`) even when stdin is piped.

### `wait-for-idle` times out on continuous output

**Symptom**: The TUI keeps updating the status bar while the agent runs, preventing idle detection.

**Fix**: Increase `:idle-ms` to 5000+ ms. Status bar updates stop once the agent finishes. For complex multi-tool questions, increase `:idle-timeout-ms` to 180000+ ms.

### Process lingers after test failure

**Symptom**: Orphaned `java` process consuming resources.

**Fix**: Always use `try`/`finally` with `.close`:

```clojure
(let [c (cli/start! ...)]
  (try
    ;; ... test code ...
    (cli/shutdown! c)
    (finally
      (.close c))))
```

### No output in REPL

The TUI captures `*out*` at `start!` time. If you switch REPL buffers or connections, call `(tui-session/capture-writer!)` to re-capture.

### Agent not found

```
ExceptionInfo: Agent not found: :my-agent
```

Valid agent types: `:react-agent`, `:coact-agent`, `:coact-agent`. Registered by `ai.brainyard.agent.common.react-agent` and `ai.brainyard.agent.common.coact-agent` (loaded automatically by `start!`).

### Disable colors

For piping output or non-ANSI terminals:

```clojure
(require '[ai.brainyard.agent.tui.ansi :as ansi])
(ansi/no-color!)   ;; disable globally
(ansi/color!)      ;; re-enable
```

---

## API Reference

### Lifecycle

| Function | Signature | Returns | Description |
|----------|-----------|---------|-------------|
| `start!` | `[agent-type & opts]` | `:ok` | Start TUI session |
| `stop!` | `[]` | `:ok` | Stop TUI session, close agent |
| `run!` | `[agent-type & opts]` | `nil` | Blocking interactive loop (same opts as `start!`) |

### Interaction

| Function | Signature | Returns | Description |
|----------|-----------|---------|-------------|
| `ask` | `[input]` | `{:answer :usage}` | Synchronous ask |
| `ask-async` | `[input]` | clj-agent ref | Async ask, use `(await ref)` |
| `deliver!` | `[action-id value]` | result | Respond to pending action prompt |

### Inspection

| Function | Signature | Description |
|----------|-----------|-------------|
| `status` | `[]` | Agent state snapshot |
| `history` | `[& {:keys [last-n]}]` | Conversation history |
| `todo` | `[]` | TODO list with progress |
| `usage` | `[]` | LLM token/cost summary |
| `thinking` | `[& {:keys [last-n]}]` | BT trace entries |

### Configuration

| Function | Signature | Description |
|----------|-----------|-------------|
| `verbosity!` | `[level]` | Set `:quiet`, `:normal`, or `:verbose` |

### `start!` / `run!` Options

| Option | Default | Description |
|--------|---------|-------------|
| `:user-id` | `"tui-user"` | User ID for session |
| `:session-id` | auto-generated | Session ID |
| `:max-iterations` | from agent meta (10) | Max BT iterations |
| `:verbosity` | `:normal` | Output verbosity level |
| `:lm-provider` | *(none)* | Auto-configure LM: `:openai`, `:anthropic`, `:ollama` |
| `:lm-model` | provider default | Override model name |
