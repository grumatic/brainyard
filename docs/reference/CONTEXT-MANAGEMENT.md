# Context Management Design — RLM Agent

How context flows through the RLM agent during iterations and multi-turn conversations, what the LLM sees at each step, and the redesign plan to eliminate context degradation.

## Table of Contents

- [Overview: RLM vs ReAct Context Philosophy](#overview-rlm-vs-react-context-philosophy)
- [Current Architecture: Context Layers](#current-architecture-context-layers)
- [RLM Completion Loop: Internal Data Flow](#rlm-completion-loop-internal-data-flow)
- [Context Flow: Single Turn Lifecycle](#context-flow-single-turn-lifecycle)
- [Context Flow: Multi-Turn Lifecycle](#context-flow-multi-turn-lifecycle)
- [What the LLM Sees at Each Step](#what-the-llm-sees-at-each-step)
- [Gap Analysis](#gap-analysis)
- [Redesign: RLM Context Pipeline](#redesign-rlm-context-pipeline)
- [Implementation Plan](#implementation-plan)

---

## Overview: RLM vs ReAct Context Philosophy

The RLM agent inverts the traditional context management model:

| Aspect | ReAct Agent | RLM Agent |
|--------|-------------|-----------|
| **Context delivery** | DSPy signature inputs (all sections injected) | Sandbox `context` variable (LLM explores via code) |
| **Context control** | Framework decides what to include | LLM decides what to read/keep via code |
| **Tool calling** | Structured JSON output → framework dispatch | `(call-tool "name" {...})` in sandbox |
| **Iteration state** | Accumulated in st-memory keys | Sandbox variables persist across iterations |
| **Cross-turn memory** | Working memory conversation (unbounded) | `previous-run` in st-memory-init (compact) |
| **Context growth** | Observations, tool-results, thoughts accumulate | LLM messages accumulate in the RLM loop |

**Key insight**: The RLM agent has *two* context surfaces:

1. **Sandbox context** (`context` variable) — static data loaded at turn start, explored by LLM code
2. **LLM message history** — multi-turn conversation between the LLM and the sandbox REPL, grows with each iteration

Both surfaces can overflow. The sandbox context can be too large to explore efficiently. The LLM message history grows with every code block + eval result, eventually exceeding the model's context window during long-running turns.

---

## Current Architecture: Context Layers

### Layer 1: Sandbox Context Variable — Per-Turn Input

**Scope**: Set once at turn start, read-only during iterations
**Storage**: SCI sandbox `context` binding
**Lifetime**: Single `run-bt` execution

```clojure
context {
  :conversation    [{:role "user" :content "..."} {:role "assistant" :content "..."} ...]
  :recalled-memory {:results [{:content "..." :score 0.8} ...]}
  :previous-run    {:question "last question"
                    :iterations [{:iteration 1 :code ["..."] :output ["..."]} ...]
                    :answer "last answer (truncated to 500 chars)"}
}
```

**Current issues**:
- `:conversation` grows unbounded across turns (full session history verbatim)
- `:previous-run` only keeps last 10 iterations with 500-char output truncation — lossy and fragile
- No mechanism to include multi-turn `previous-run` chains (only the immediately prior turn)

### Layer 2: LLM Message History — Per-Turn, Per-Iteration Growth

**Scope**: Accumulates during the RLM completion loop
**Storage**: Atom in `loop/completion` (`messages`)
**Lifetime**: Single RLM completion call

```
messages [
  {:role "system"  :content "<system prompt with instructions>"}
  {:role "user"    :content "Query: <question>\n\nWrite Clojure code..."}
  ;; -- Iteration 1 --
  {:role "assistant" :content "```clojure\n(list-tools)\n```"}
  {:role "user"    :content "REPL Output:\nstdout:\n24 tools available:\n1. list-tools — ..."}
  ;; -- Iteration 2 --
  {:role "assistant" :content "```clojure\n(get-tool \"mcp$call-tools\")\n```"}
  {:role "user"    :content "REPL Output:\n=> \"Tool: mcp$call-tools\\nType: command...\""}
  ;; ... grows with every iteration ...
]
```

**Current issues**:
- No compaction mechanism — every iteration adds 2 messages (assistant code + REPL output)
- Tool results can be massive (e.g., directory listings, API responses)
- Feedback messages are truncated at 2000 chars per block but can still accumulate significantly
- At 20 iterations with verbose tool results, can easily exceed 100K+ tokens

### Layer 3: st-memory — BT Execution State

**Scope**: Per-BT run
**Storage**: Atom in BT context
**Lifetime**: Single `run-bt` execution

```clojure
st-memory {
  ;; Inputs (from st-memory-init merge)
  :question          "user's question"
  :instruction       "system instruction"
  :tool-context      "tool usage guide"
  :agent-context     "behavioral context"
  ;; (streaming callbacks now come from the hooks registry:
  ;;  :streaming/chunk-factory — see core/hooks.clj)

  ;; Set by recall phase
  :recalled-memory   {:results [...]}

  ;; Updated per iteration by on-iteration callback
  :iteration-count   3
  :last-reasoning    "```clojure\n(call-tool ...)\n```"
  :observation       "=> {:result ...}"

  ;; Set at completion
  :answer            "final answer"
  :rlm-iterations    [{:iteration 1 :code [...] :output [...]} ...]
  :rlm-terminated-by :final
  :rlm-exhausted     false
  :rlm-messages      [<final LLM conversation for /continue>]
}
```

### Layer 4: st-memory-init — Cross-Turn Persistent Config

**Scope**: Persistent across turns within same agent instance
**Storage**: Atom in agent `!state`
**Lifetime**: Agent lifetime

```clojure
st-memory-init {
  :instruction    "..."
  :tool-context   "..."
  :agent-context  "..."
  :previous-run   {:question "..." :iterations [...] :answer "..."}
  :continuation   nil  ;; set by /continue command
}
```

### Layer 5: Session & Working Memory — Shared State

Same as the general agent architecture:
- **Working memory** `:conversation` — LLM-facing multi-turn history
- **Session** `:messages` — UI-facing conversation log
- **Long-term memory** — SQLite FTS5 with episodic + semantic layers

---

## RLM Completion Loop: Internal Data Flow

```
                    ┌─────────────────────────────────────────────────┐
                    │         RLM Completion Loop (loop.clj)          │
                    │                                                 │
 question ─────────►│  messages atom: [system-msg, user-msg]          │
 context ──────────►│  sandbox: {context, question, call-tool, ...}   │
 bindings ─────────►│  all-iterations atom: []                        │
 system-prompt ────►│                                                 │
                    │  ┌─── Iteration Loop (max N) ──────────────┐   │
                    │  │                                          │   │
                    │  │  1. LLM call(@messages)                  │   │
                    │  │     → response-text                      │   │
                    │  │                                          │   │
                    │  │  2. Extract ```clojure blocks             │   │
                    │  │     → code-blocks []                     │   │
                    │  │                                          │   │
                    │  │  3. No code? → feedback "write code"     │   │
                    │  │     → append [assistant, feedback]       │   │
                    │  │     → recur                              │   │
                    │  │                                          │   │
                    │  │  4. Eval code blocks in sandbox          │   │
                    │  │     → eval-results [{:result :output}]   │   │
                    │  │                                          │   │
                    │  │  5a. FINAL called? → return answer       │   │
                    │  │  5b. Continue → build feedback msg       │   │
                    │  │      → append [assistant, feedback]      │   │
                    │  │      → on-iteration callback             │   │
                    │  │      → recur                             │   │
                    │  │                                          │   │
                    │  └──────────────────────────────────────────┘   │
                    │                                                 │
                    │  Return: {:answer :iterations :messages         │
                    │           :terminated-by :total-iterations}     │
                    └─────────────────────────────────────────────────┘
```

**Critical observation**: The `messages` atom grows by 2 entries per iteration. With verbose tool results (MCP calls, file listings, API responses), a single feedback message can be thousands of tokens. Over 10-20 iterations, this is the primary source of context overflow.

---

## Context Flow: Single Turn Lifecycle

```
User Question: "List my S3 buckets and summarize their sizes"
        │
        ▼
┌─── run-bt ────────────────────────────────────────────────────────────┐
│                                                                        │
│  1. RESET: st-memory = merge(st-memory-init, {:question q})           │
│                                                                        │
│  2. RECALL: contextual-recall(question)                                │
│     → st-memory[:recalled-memory] = {:results [...]}                  │
│                                                                        │
│  3. BUILD SANDBOX CONTEXT:                                             │
│     conversation ← session :messages (minus current question)          │
│     recalled-memory ← st-memory[:recalled-memory]                     │
│     previous-run ← st-memory[:previous-run] (from st-memory-init)     │
│     → rlm-context = {:conversation [...] :recalled-memory {...}        │
│                       :previous-run {...}}                              │
│                                                                        │
│  4. CREATE TOOL BINDINGS:                                              │
│     Discover tools from global !tool-defs registry                     │
│     → {call-tool, list-tools, get-tool, inspect, question}             │
│                                                                        │
│  5. BUILD SYSTEM PROMPT:                                               │
│     instruction + tool-context + workflow + rules                      │
│                                                                        │
│  6. RLM COMPLETION LOOP:                                               │
│     ┌─ Iteration 1: (list-tools) → "24 tools available..."            │
│     ├─ Iteration 2: (get-tool "mcp$call-tools") → schema              │
│     ├─ Iteration 3: (call-tool "mcp$call-tools" {...}) → S3 data      │
│     ├─ Iteration 4: (inspect result) → tree view of response          │
│     ├─ Iteration 5: Clojure code to process/summarize                 │
│     └─ Iteration 6: (FINAL summary-string)                            │
│                                                                        │
│  7. POST-COMPLETION:                                                   │
│     st-memory[:answer] = summary-string                                │
│     st-memory[:rlm-iterations] = [{...} ...]                          │
│     st-memory[:rlm-messages] = [final LLM conversation]               │
│                                                                        │
│  8. SAVE PREVIOUS-RUN (to st-memory-init for next turn):               │
│     {:question "List my S3 buckets..."                                 │
│      :iterations [{:iteration 1 :code [...] :output [...]} ...]        │
│      :answer "Here are your S3 buckets... (truncated 500)"}            │
│     Last 10 iterations, outputs truncated to 500 chars                 │
│                                                                        │
│  9. MAINTAIN CONVERSATION:                                             │
│     Session :messages ← append user msg + assistant answer             │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

---

## Context Flow: Multi-Turn Lifecycle

```
Turn 1: "List my MCP servers"
  ├── sandbox context: {:conversation [] :recalled-memory nil :previous-run nil}
  ├── RLM loop: 4 iterations → answer
  └── st-memory-init[:previous-run] =
        {:question "List my MCP servers"
         :iterations [{iter 1...} {iter 2...} {iter 3...} {iter 4...}]
         :answer "You have 3 MCP servers: filesystem, aws, github"}

Turn 2: "Start the aws one"
  ├── sandbox context:
  │   {:conversation [{:role "user" :content "List my MCP servers"}
  │                   {:role "assistant" :content "You have 3 MCP servers..."}]
  │    :recalled-memory {:results [...]}
  │    :previous-run {:question "List my MCP servers"
  │                   :iterations [...]
  │                   :answer "You have 3 MCP servers..."}}
  ├── RLM loop: 3 iterations → answer
  └── st-memory-init[:previous-run] =
        {:question "Start the aws one"
         :iterations [...]
         :answer "Started aws MCP server successfully"}

Turn 3: "What tools does it provide?"
  ├── sandbox context:
  │   {:conversation [{:role "user" :content "List my MCP servers"}
  │                   {:role "assistant" :content "You have 3 MCP servers..."}
  │                   {:role "user" :content "Start the aws one"}
  │                   {:role "assistant" :content "Started aws MCP server..."}]
  │    :recalled-memory {:results [...]}
  │    :previous-run {:question "Start the aws one"    ◄── ONLY the last turn
  │                   :iterations [...]
  │                   :answer "Started aws MCP server..."}}
  ├── LLM reads (:conversation context) to understand "it" = aws
  └── RLM loop: 5 iterations → answer

Turn 10+: Context degradation begins
  ├── :conversation now has 20+ messages (verbatim, including tool results)
  ├── :previous-run only has turn 9 (turns 1-8 are lost except in :conversation)
  └── The sandbox context variable itself becomes expensive to serialize
```

**Degradation pattern**: The `:conversation` in the sandbox context grows unbounded. Since it's serialized as EDN into the `context` variable, the LLM must potentially read through increasingly large data to find relevant prior exchanges. Meanwhile, `:previous-run` only carries the single most recent turn's iterations.

---

## What the LLM Sees at Each Step

### System Message (Static)

```
┌─────────────────────────────────────────────────────────────────────┐
│ SYSTEM MESSAGE                                                       │
│                                                                     │
│ "You are an AI agent that answers queries by writing Clojure code   │
│  in a REPL environment..."                                          │
│                                                                     │
│ Available Functions: context, question, list-tools, get-tool,       │
│                      call-tool, inspect, llm-query, FINAL           │
│                                                                     │
│ Available Clojure: (string ops, collections, math, etc.)            │
│                                                                     │
│ Instructions: (agent-specific instruction)                          │
│ Tool Usage Guide: (discovery workflow, MCP format, task commands)   │
│ Workflow: review context → discover tools → inspect → process → FINAL│
│ Rules: ONE code block, inspect results, computed FINAL              │
└─────────────────────────────────────────────────────────────────────┘
```

~4K-6K tokens. Static per turn.

### Initial User Message

```
┌─────────────────────────────────────────────────────────────────────┐
│ USER MESSAGE (Iteration 0)                                           │
│                                                                     │
│ "Query: What tools does the aws MCP server provide?                 │
│                                                                     │
│  Write Clojure code to answer this query using the `context`        │
│  variable."                                                         │
└─────────────────────────────────────────────────────────────────────┘
```

~50 tokens. Fixed.

### Iteration N: Assistant + Feedback

```
┌─────────────────────────────────────────────────────────────────────┐
│ ASSISTANT (Iteration 3)                                              │
│                                                                     │
│ Let me call the aws MCP server to list its tools.                   │
│                                                                     │
│ ```clojure                                                          │
│ (def aws-tools (call-tool "mcp$list-tools"                          │
│                            {:server-name "aws"}))                   │
│ (inspect aws-tools)                                                 │
│ ```                                                                 │
├─────────────────────────────────────────────────────────────────────┤
│ USER (REPL Feedback)                                                 │
│                                                                     │
│ REPL Output:                                                        │
│ stdout:                                                             │
│ Map(2 keys):                                                        │
│   :result => Map(1 keys):                                           │
│     :tools => Vector(15), first:                                    │
│       Map(3 keys: :name, :description, :inputSchema)                │
│                                                                     │
│ => {:result {:tools [{:name "s3_list_buckets" ...} ...]}}           │
└─────────────────────────────────────────────────────────────────────┘
```

**Variable size**: Tool results can range from 100 tokens (simple) to 10K+ tokens (large API responses). Each iteration adds ~200-10K tokens.

### Cumulative Growth per Turn

| Iterations | Estimated Messages | Token Range |
|------------|-------------------|-------------|
| 1-3 | 4-8 | 5K-15K |
| 5-7 | 12-16 | 15K-40K |
| 10-15 | 22-32 | 40K-80K |
| 15-20 | 32-42 | 80K-150K+ |

The context window becomes the primary bottleneck in complex, multi-tool tasks.

---

## Gap Analysis

### Current Strengths

1. **Code-based context control** — LLM uses `inspect`, `get-in`, `def` to selectively explore data
2. **Simple BT** — Single action node, no multi-signature coordination overhead
3. **Dynamic tool discovery** — Tools not embedded in prompt, discovered at runtime via code
4. **Continuation support** — `/continue` resumes with the full message history
5. **Previous-run context** — Cross-turn continuity via compact iteration summary

### Current Gaps

| # | Gap | Impact | Severity |
|---|-----|--------|----------|
| G1 | **No LLM message compaction** — Messages accumulate unbounded within a single turn's RLM loop | Context window overflow on complex multi-tool tasks (10+ iterations) | **Critical** |
| G2 | **No conversation windowing in sandbox context** — `:conversation` in the `context` var grows with every turn | Sandbox context bloat; LLM wastes iterations navigating old conversation | **High** |
| G3 | **Single-turn previous-run** — Only the immediately prior turn's iterations are available | LLM loses context from 2+ turns ago; "what did I do three questions ago?" unanswerable | **High** |
| G4 | **No adaptive tool-result handling** — Massive tool results (file listings, API responses) kept verbatim in feedback messages | Single tool call can consume 10K+ tokens of message history | **High** |
| G5 | **No sandbox state persistence** — Sandbox variables (`def`s) are lost between turns | LLM must re-discover and re-compute across turns; no incremental knowledge building | **Medium** |
| G6 | **Static system prompt** — Same instructions regardless of context size, task complexity, or iteration count | Wasted tokens on instructions not relevant to current phase | **Medium** |
| G7 | **No cross-turn tool-result cache** — Identical tool calls repeated across turns | Redundant API calls and token usage | **Medium** |
| G8 | **No context-aware recall** — Recall query is just the current question; ignores conversation trajectory | Misses relevant memories from related prior topics | **Medium** |
| G9 | **No iteration budget awareness** — LLM doesn't know how many tokens remain in the context window | Can't plan iteration strategy; may waste early iterations on exploration | **Low** |

### Root Cause

The core issue is that the RLM agent has **two context surfaces that grow independently and have no coordination**:

```
Surface 1: Sandbox context var       Surface 2: LLM message history
(set once, read by code)             (grows every iteration)

Turn 1: small                        Iter 1: +2 msgs
Turn 2: +conversation                Iter 2: +2 msgs (with tool output)
Turn 3: +conversation                Iter 3: +2 msgs (with tool output)
Turn 4: +conversation                ...
...                                  Iter 15: +2 msgs → OVERFLOW
Turn 10: bloated
```

Neither surface has a compaction or windowing mechanism. The sandbox context grows across turns (G2, G3). The LLM message history grows within turns (G1, G4).

---

## Redesign: RLM Context Pipeline

### Architecture Overview

Introduce a **Context Pipeline** that manages both surfaces — the sandbox context variable and the LLM message history — with coordinated budgeting.

```
                                    ┌─────────────────────────────────────┐
                                    │     RLM Context Pipeline             │
                                    │                                     │
 conversation (all turns) ─────────►│  1. Conversation Window             │
 recalled-memory ──────────────────►│     → recent turns verbatim         │
 previous-runs (plural) ──────────►│     → old turns summarized          │
 sandbox-state-snapshot ───────────►│                                     │
                                    │  2. Previous-Run Chain              │
                                    │     → last N turns' iterations      │──► context var
                                    │     → progressively compressed      │
                                    │                                     │
                                    │  3. Sandbox State Restore           │
                                    │     → persistent defs               │
                                    │     → cached tool results           │
                                    └─────────────────────────────────────┘

                                    ┌─────────────────────────────────────┐
                                    │     RLM Message Manager              │
                                    │                                     │
 messages atom ────────────────────►│  4. Feedback Truncation             │
                                    │     → large tool results → summary  │
                                    │                                     │
                                    │  5. Mid-Turn Compaction             │──► messages atom
                                    │     → compress old iterations       │
                                    │     → keep recent 3 verbatim        │
                                    │                                     │
                                    │  6. Budget Monitoring               │
                                    │     → token estimate per message    │
                                    │     → trigger compaction at 60%     │
                                    └─────────────────────────────────────┘
```

### Component 1: Conversation Window Manager

**File**: `clj-sandbox/core/conversation_window.clj`

Manages the `:conversation` key in the sandbox `context` variable.

```clojure
(ns ai.brainyard.clj-sandbox.core.conversation-window)

;; Strategy: Sliding window with progressive summarization
;;
;; ┌───────────────────────────────────────────────────────────────┐
;; │ CONVERSATION WINDOW (in sandbox context var)                   │
;; │                                                               │
;; │  {:summary "Turns 1-5: User asked about MCP servers,         │
;; │            started aws server, explored tools..."             │
;; │   :recent [{:role "user" :content "Show S3 buckets"}         │
;; │            {:role "assistant" :content "Here are 5 buckets..."}│
;; │            {:role "user" :content "Delete the test one"}]}    │
;; └───────────────────────────────────────────────────────────────┘

(defn build-conversation-window
  "Build a windowed conversation for the sandbox context variable.

   Strategy:
   1. Always keep the most recent N turns verbatim
   2. If older turns exist, summarize them into a :summary string
   3. If summarization is disabled, truncate to just recency window

   Parameters:
     conversation   - Full conversation history [{:role :content} ...]
     opts           - {:recent-turns 4       ;; verbatim recent turns (user+assistant pairs)
                       :max-summary-chars 1000
                       :summarize-fn nil}     ;; (fn [messages] summary-string) or nil

   Returns:
     {:summary \"...\"   ;; nil if no old turns or no summarize-fn
      :recent  [...]}"
  [conversation opts]
  ...)
```

**Behavior**:
- Default: keep last 4 exchange pairs (8 messages) verbatim, drop older ones
- With `summarize-fn`: use `llm-query` to summarize dropped turns into a concise paragraph
- The LLM sees `(:summary (:conversation context))` and `(:recent (:conversation context))` separately

### Component 2: Previous-Run Chain

**File**: `clj-sandbox/core/previous_runs.clj`

Replace the single `:previous-run` with a chain of compressed prior turns.

```clojure
(ns ai.brainyard.clj-sandbox.core.previous-runs)

;; Instead of only the last turn, keep a chain of prior turns
;; with progressive compression:
;;
;; previous-runs [
;;   ;; Turn N-1 (most recent): full iteration details
;;   {:question "Delete the test bucket"
;;    :iterations [{:iteration 1 :code ["..."] :output ["..."]} ...]
;;    :answer "Deleted test-bucket successfully"
;;    :depth :full}
;;
;;   ;; Turn N-2: compressed to question + answer only
;;   {:question "Show S3 buckets"
;;    :answer "5 buckets: prod-data, staging, test-bucket, logs, backups"
;;    :depth :summary}
;;
;;   ;; Turn N-3+: single-line summaries
;;   {:question "Start the aws MCP server"
;;    :answer "Started"
;;    :depth :minimal}
;; ]

(defn build-previous-runs-chain
  "Build a chain of previous run summaries with progressive compression.

   Parameters:
     all-previous-runs  - Vector of {:question :iterations :answer} from prior turns
     opts               - {:full-depth 1      ;; how many recent turns get full iterations
                           :summary-depth 2    ;; how many get question+answer
                           :minimal-depth 3    ;; how many get single-line
                           :max-total-chars 8000}

   Returns: Vector of compressed run summaries, most recent first."
  [all-previous-runs opts]
  ...)
```

**Storage**: st-memory-init stores a vector `:previous-runs` (not singular), appending after each turn.

### Component 3: Feedback Truncation

**File**: `clj-sandbox/core/feedback.clj`

Intelligent truncation of REPL feedback messages before they enter the message history.

```clojure
(ns ai.brainyard.clj-sandbox.core.feedback)

;; Current: 2000 char hard truncation per code block output
;; Problem: A 2000-char truncated JSON blob is often useless
;;
;; Redesign: Structure-aware truncation
;; - For maps/vectors: show structure + sample, not raw truncation
;; - For strings: head + tail with "...(N chars omitted)..."
;; - For inspect output: keep as-is (already depth-limited)
;; - Budget: per-block limit adapts based on remaining context budget

(defn truncate-feedback
  "Build a feedback message with intelligent truncation.

   Parameters:
     eval-results     - [{:result :output :error :code}]
     opts             - {:max-chars-per-block 2000
                         :structure-aware true     ;; use inspect-style for large data
                         :total-budget-chars nil}  ;; if set, distributes across blocks

   Returns: {:role \"user\" :content \"...\"}"
  [eval-results opts]
  ...)
```

### Component 4: Mid-Turn Message Compaction

**File**: `clj-sandbox/core/message_compaction.clj`

Compress old iterations' messages within the RLM loop to prevent context overflow.

```clojure
(ns ai.brainyard.clj-sandbox.core.message-compaction)

;; Strategy: After iteration N, compress iterations 1...(N-K) into a
;; single summary message, keeping the last K iterations verbatim.
;;
;; BEFORE compaction (10 iterations):
;; [system, user-query, asst-1, repl-1, asst-2, repl-2, ..., asst-10, repl-10]
;;   = 22 messages
;;
;; AFTER compaction (keep last 3):
;; [system, user-query, SUMMARY-of-iters-1-7, asst-8, repl-8, asst-9, repl-9, asst-10, repl-10]
;;   = 9 messages

(defn compact-messages
  "Compress old iteration messages into a summary.

   Parameters:
     messages         - Current messages vector (from the messages atom)
     opts             - {:keep-recent 3         ;; iterations to keep verbatim
                         :trigger-iteration 5    ;; don't compact before this
                         :summarize-fn nil       ;; (fn [messages] summary-string)
                         :max-summary-chars 2000}

   Returns: Compacted messages vector, or original if no compaction needed."
  [messages opts]
  ...)

(defn estimate-message-tokens
  "Estimate token count for a messages vector.
   Uses char/4 heuristic (conservative for English + code)."
  [messages]
  (int (/ (reduce + (map #(count (str (:content %))) messages)) 4)))

(defn needs-compaction?
  "Check if messages need compaction based on token estimate.

   Parameters:
     messages           - Current messages vector
     max-context-tokens - Model's context window
     threshold-ratio    - Trigger compaction at this ratio (default 0.6)"
  [messages max-context-tokens & {:keys [threshold-ratio] :or {threshold-ratio 0.6}}]
  (> (estimate-message-tokens messages) (* max-context-tokens threshold-ratio)))
```

**Integration point**: Called inside `loop/completion` before each LLM call, when `needs-compaction?` returns true.

**Summarization**: The summary is built by extracting key information from the compressed iterations:
- What tools were called and their essential results
- What variables were `def`d and their values
- Errors encountered and how they were resolved

### Component 5: Sandbox State Persistence

**File**: `clj-sandbox/core/sandbox_state.clj`

Persist key sandbox variables across turns for incremental knowledge building.

```clojure
(ns ai.brainyard.clj-sandbox.core.sandbox-state)

;; After each turn, snapshot user-defined vars from the sandbox.
;; On next turn, restore them as initial bindings.
;;
;; This lets the LLM build up knowledge across turns:
;;   Turn 1: (def servers (call-tool "mcp$list-servers" {}))
;;   Turn 2: servers is already available — no need to re-call

(defn snapshot-sandbox-state
  "Extract user-defined variables from sandbox for persistence.

   Parameters:
     sandbox     - The SCI sandbox
     max-vars    - Maximum number of vars to persist (default 20)
     max-chars   - Maximum total chars for serialized state (default 10000)

   Returns: Map of {symbol-str value-str} for serializable values."
  [sandbox & {:keys [max-vars max-chars] :or {max-vars 20 max-chars 10000}}]
  ...)

(defn restore-sandbox-state
  "Build sandbox bindings from a persisted state snapshot.

   Parameters:
     state-snapshot - Map from snapshot-sandbox-state

   Returns: Map of {symbol value} suitable for :bindings in create-sandbox."
  [state-snapshot]
  ...)
```

**Limitations**: Only persists values that can be serialized as EDN (strings, numbers, keywords, collections). Skips functions, atoms, Java objects.

### Component 6: Budget Monitor

**File**: `clj-sandbox/core/budget.clj`

Track token usage across both context surfaces and expose to the LLM.

```clojure
(ns ai.brainyard.clj-sandbox.core.budget)

(defn create-budget-monitor
  "Create a budget monitor for the RLM completion loop.

   Parameters:
     max-context-tokens  - Model's context window size
     max-iterations      - Maximum iterations allowed

   Returns: Atom with budget state."
  [max-context-tokens max-iterations]
  (atom {:max-tokens max-context-tokens
         :max-iterations max-iterations
         :current-iteration 0
         :estimated-tokens 0
         :compaction-count 0
         :warnings []}))

(defn update-budget
  "Update budget after an iteration.
   Returns updated budget map with :needs-compaction? flag."
  [budget-atom messages iteration]
  ...)

(defn budget-status-string
  "Format budget status for injection into feedback messages.
   e.g., '[Budget: 45K/128K tokens, iter 7/20]'"
  [budget-atom]
  ...)
```

**Integration**: Optionally append budget status to feedback messages so the LLM can plan its remaining iterations.

---

## Integration: Modified Completion Loop

The redesigned `loop/completion` function integrates all components:

```
┌─── completion(question, context, opts) ────────────────────────────────┐
│                                                                        │
│  1. WINDOW CONVERSATION:                                               │
│     context[:conversation] → conversation-window/build                 │
│                                                                        │
│  2. BUILD PREVIOUS-RUNS:                                               │
│     st-memory-init[:previous-runs] → previous-runs/build-chain         │
│                                                                        │
│  3. RESTORE SANDBOX STATE:                                             │
│     st-memory-init[:sandbox-state] → sandbox-state/restore             │
│     → merge into :bindings                                             │
│                                                                        │
│  4. CREATE SANDBOX & MESSAGES                                          │
│                                                                        │
│  5. CREATE BUDGET MONITOR                                              │
│                                                                        │
│  ┌─── Iteration Loop ──────────────────────────────────────────────┐  │
│  │                                                                  │  │
│  │  6. CHECK BUDGET → message-compaction/needs-compaction?          │  │
│  │     → if yes: compact-messages (compress old iterations)         │  │
│  │                                                                  │  │
│  │  7. LLM CALL (with potentially compacted messages)              │  │
│  │                                                                  │  │
│  │  8. EXTRACT & EVAL CODE BLOCKS                                  │  │
│  │                                                                  │  │
│  │  9. BUILD FEEDBACK (with intelligent truncation)                │  │
│  │     → feedback/truncate-feedback (structure-aware)              │  │
│  │     → optionally append budget-status-string                    │  │
│  │                                                                  │  │
│  │  10. APPEND MESSAGES & RECUR                                    │  │
│  │                                                                  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                        │
│  11. POST-COMPLETION:                                                  │
│      → snapshot-sandbox-state → st-memory-init[:sandbox-state]         │
│      → build previous-run entry → append to st-memory-init[:prev-runs]│
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

### Modified Sandbox Context Structure

```clojure
;; BEFORE (current):
context {:conversation    [all messages verbatim]
         :recalled-memory {:results [...]}
         :previous-run    {:question "..." :iterations [...] :answer "..."}}

;; AFTER (redesigned):
context {:conversation    {:summary "Turns 1-5 summary..."
                           :recent [{:role "user" :content "..."}
                                    {:role "assistant" :content "..."}]}
         :recalled-memory {:results [...]}
         :previous-runs   [{:question "..." :iterations [...] :answer "..." :depth :full}
                           {:question "..." :answer "..." :depth :summary}
                           {:question "..." :answer "..." :depth :minimal}]
         :restored-vars   ["servers" "aws-tools" "last-result"]}
```

### Modified st-memory-init Structure

```clojure
;; BEFORE:
st-memory-init {:instruction "..."
                :tool-context "..."
                :agent-context "..."
                :previous-run {:question "..." :iterations [...] :answer "..."}}

;; AFTER:
st-memory-init {:instruction "..."
                :tool-context "..."
                :agent-context "..."
                :previous-runs [{...} {...} ...]       ;; chain of prior turns
                :sandbox-state {"servers" "..." ...}   ;; persistent vars
                :context-config {:recent-turns 4
                                 :max-previous-runs 6
                                 :enable-message-compaction true
                                 :compaction-threshold-ratio 0.6}}
```

---

## Implementation Plan

### Phase 1: Mid-Turn Message Compaction (Critical — fixes context overflow)

**Goal**: Prevent the RLM message history from exceeding the context window during long turns.

**Files**:
- NEW: `clj-sandbox/core/message_compaction.clj`
- NEW: `clj-sandbox/core/budget.clj`
- MODIFY: `clj-sandbox/core/loop.clj` — integrate compaction check before each LLM call
- MODIFY: `clj-sandbox/core/prompt.clj` — update `build-feedback-message` with structure-aware truncation

**Approach**:
1. After each iteration, estimate total message tokens
2. When exceeding 60% of model context window, trigger compaction
3. Compaction compresses iterations 1...(N-3) into a single summary message
4. Summary preserves: tool calls made, key results, defined variables, errors resolved
5. Recent 3 iterations kept verbatim for immediate context continuity

**Config**:
```clojure
:enable-message-compaction     true
:compaction-threshold-ratio    0.6    ;; trigger at 60% of context window
:compaction-keep-recent        3      ;; verbatim recent iterations
:max-context-tokens            128000 ;; model context window (from lm-config)
```

### Phase 2: Feedback Truncation (High — reduces per-iteration growth)

**Goal**: Prevent individual tool results from consuming disproportionate message space.

**Files**:
- NEW: `clj-sandbox/core/feedback.clj`
- MODIFY: `clj-sandbox/core/prompt.clj` — replace `build-feedback-message` with `feedback/truncate-feedback`

**Approach**:
1. Detect result type (map, vector, string, error)
2. For large structured data: use inspect-style tree (already 4-level depth limited)
3. For large strings: head + tail with omission marker
4. Per-block budget adapts based on number of blocks in the iteration
5. Default: 3000 chars per block (up from 2000), but structure-aware

### Phase 3: Conversation Window (High — fixes cross-turn bloat)

**Goal**: Keep the sandbox `context` variable manageable as turns accumulate.

**Files**:
- NEW: `clj-sandbox/core/conversation_window.clj`
- MODIFY: `agent/common/rlm_agent.clj` — apply windowing when building `rlm-context`

**Approach**:
1. Split conversation into recent (last 4 pairs) and old
2. Old turns: if `llm-query` available, summarize; otherwise drop
3. Pass structured `{:summary :recent}` instead of flat message vector
4. Update system prompt to document the new conversation structure

### Phase 4: Previous-Run Chain (High — fixes cross-turn amnesia)

**Goal**: Give the LLM access to multiple prior turns, not just the most recent one.

**Files**:
- NEW: `clj-sandbox/core/previous_runs.clj`
- MODIFY: `agent/common/rlm_agent.clj` — build chain from st-memory-init, store plural runs

**Approach**:
1. After each turn, append to `:previous-runs` vector in st-memory-init
2. Apply progressive compression: full → summary → minimal
3. Total budget: 8K chars for the entire chain
4. Keep max 6 turns in the chain

### Phase 5: Sandbox State Persistence (Medium — enables incremental knowledge)

**Goal**: Let the LLM reuse `def`d variables across turns.

**Files**:
- NEW: `clj-sandbox/core/sandbox_state.clj`
- MODIFY: `agent/common/rlm_agent.clj` — snapshot after completion, restore before next turn
- MODIFY: `clj-sandbox/core/sandbox.clj` — support initial bindings restoration

**Approach**:
1. After RLM completion, scan sandbox for user-defined vars
2. Serialize EDN-safe values (skip functions, atoms, Java objects)
3. Store in st-memory-init as `:sandbox-state`
4. On next turn, restore as additional sandbox bindings
5. List restored var names in context as `:restored-vars`

### Phase 6: Context-Aware Recall (Medium — improves memory relevance)

**Goal**: Use conversation trajectory for better memory retrieval.

**Files**:
- NEW: `agent/core/context_recall.clj`
- MODIFY: `agent/core/memory.clj` — use enriched query

**Approach**:
1. Extract key entities/topics from recent 2-3 conversation turns
2. Include pending TODO items in recall query
3. Build compound query: `question + conversation_keywords + todo_keywords`
4. Weight recent conversation higher than older context

### Phase 7: Budget Monitoring & Adaptive Prompts (Low — quality of life)

**Goal**: Let the LLM make informed decisions about iteration strategy.

**Files**:
- NEW: `clj-sandbox/core/budget.clj`
- MODIFY: `clj-sandbox/core/loop.clj` — create and update budget monitor
- MODIFY: `clj-sandbox/core/prompt.clj` — optionally append budget to feedback

**Approach**:
1. Track token estimates per iteration
2. Append `[Budget: 45K/128K tokens, iter 7/20]` to feedback messages
3. LLM can decide to call FINAL earlier or skip inspection steps when budget is low

---

## Appendix: Context Data Flow Cheatsheet (RLM Agent)

```
                    PERSISTENT                    PER-SESSION                      PER-TURN
                    ──────────                    ───────────                      ────────
                    SQLite FTS5                   !session atom                    RLM Completion
                    ┌──────────┐                  ┌────────────┐                  ┌────────────────┐
Long-Term Memory ──►│ episodes │──recall──►       │ :messages  │──conversation──►│ Sandbox context │
                    │ facts    │         │        │ :thinking  │   window        │  :conversation  │
                    └──────────┘         │        │ :config    │                  │  :recalled-mem  │
                                         │        └────────────┘                  │  :previous-runs │
                                         │              ▲                         │  :restored-vars │
                                         │              │ (UI)                    └────────────────┘
                                         │              │                                │
                    st-memory-init ──────┼───merge─────────────►  st-memory              │
                    ┌──────────────┐     │                       ┌────────────┐          │
                    │ :instruction │     │                       │ :question  │          │
                    │ :agent-ctx   │     │                       │ :answer    │          │
                    │ :tool-ctx    │     │                       │ :rlm-iters │          │
                    │ :prev-runs   │     │                       │ :rlm-msgs  │     ┌────▼──────────┐
                    │ :sandbox-st  │     └──────────────────────►│ :recalled  │     │ LLM Messages  │
                    │ :context-cfg │                              └────────────┘     │ (grows/iter)  │
                    └──────────────┘                                                 │               │
                          ▲                                                          │ ┌───────────┐ │
                          │ (post-turn snapshot)                                     │ │ Compaction│ │
                          │ previous-runs + sandbox-state                            │ │ at 60%    │ │
                          └──────────────────────────────────────────────────────────│ └───────────┘ │
                                                                                     └───────────────┘
```

## Appendix: Migration from Current to Redesigned

### Backward Compatibility

The redesigned system should support both old and new formats:

```clojure
;; In rlm-tool-action, detect format:
(let [prev (or (:previous-runs st)          ;; new plural format
                (when-let [pr (:previous-run st)]  ;; old singular format
                  [pr]))]
  ...)
```

### Configuration Defaults

All new features are **opt-in** via `:context-config` in st-memory-init:

```clojure
(def default-context-config
  {:enable-conversation-window   true
   :recent-turns                 4
   :enable-previous-run-chain    true
   :max-previous-runs            6
   :enable-message-compaction    true
   :compaction-threshold-ratio   0.6
   :compaction-keep-recent       3
   :enable-sandbox-persistence   false   ;; experimental, off by default
   :enable-budget-monitoring     false
   :max-context-tokens           128000})
```

### Rollout

1. Phase 1 (message compaction) can be deployed independently — it's internal to the RLM loop
2. Phases 2-4 change the sandbox context structure — coordinate with system prompt updates
3. Phase 5 (sandbox persistence) is experimental — behind a flag
4. Phases 6-7 are enhancements — no breaking changes
