# RLM Research Analysis: Papers vs. Brainyard Implementation

## Papers Analyzed

| # | Title | Authors | Date | arXiv |
|---|-------|---------|------|-------|
| 1 | Recursive Language Models | Alex L. Zhang, Tim Kraska, Omar Khattab | Dec 2025 | 2512.24601 |
| 2 | Think, But Don't Overthink: Reproducing RLMs | Daren Wang | Mar 2026 | 2603.02615 |
| 3 | RLM-JB: Recursive LMs for Jailbreak Detection | Doron Shavit | Feb 2026 | 2602.16520 |
| 4 | PRefLexOR: Preference-based Recursive Language Modeling | Markus J. Buehler | Oct 2024 | 2410.12375 |

Reference implementation: https://github.com/alexzhang13/rlm

---

## 1. Core RLM Paradigm (Paper 1)

### 1.1 Key Insight

RLMs treat the prompt as an **external environment** rather than feeding it into the context window. The LLM is given a REPL sandbox and can:
- Programmatically examine context via code
- Decompose problems into sub-problems
- Recursively invoke itself (`llm_query`, `rlm_query`) over snippets
- Handle inputs **100x beyond** the model's context window (10M+ tokens)

### 1.2 Architecture (Reference Implementation)

The REPL environment provides:

| Function | Purpose |
|----------|---------|
| `context` | Variable holding full prompt data |
| `llm_query(prompt, model?)` | Single-shot sub-LLM call (~500K chars) |
| `llm_query_batched(prompts, model?)` | **Concurrent batch** sub-LLM calls |
| `rlm_query(prompt, model?)` | **Recursive RLM sub-call** (gets its own REPL + loop) |
| `rlm_query_batched(prompts, model?)` | **Batched recursive** sub-calls |
| `FINAL(answer)` / `FINAL_VAR(var)` | Termination signals |
| `SHOW_VARS()` | List REPL variables |

### 1.3 Emergent Strategies

The paper observed LLMs spontaneously developing these strategies without explicit instruction:

1. **Regex filtering** — keyword-based pre-filtering before expensive LLM calls
2. **Uniform chunking** — splitting context by newlines or semantic boundaries
3. **Per-chunk sub-LLM calls** — semantic transformation of each chunk
4. **Answer verification** — redundant sub-queries for self-checking
5. **Variable stitching** — storing sub-LLM outputs in variables for final synthesis

### 1.4 Information Density Classification

| Complexity | Task | Description |
|------------|------|-------------|
| O(1) — Constant | S-NIAH | Single answer regardless of input size |
| O(N) — Linear | OOLONG | Nearly all entries need processing |
| O(N²) — Quadratic | OOLONG-Pairs | All entry pairs must be examined |

**Key finding**: RLM benefits scale with information density. O(1) tasks gain little; O(N) and O(N²) tasks see dramatic improvements.

### 1.5 Performance Results

- **RLM-Qwen3-8B** outperforms base Qwen3-8B by **28.3% average** across 4 tasks
- Approaches **GPT-5 quality** on 3 long-context tasks
- GPT-5 RLM: **2x performance gains** over baselines at comparable or lower cost
- Median cost on BrowseComp+: $0.99 vs $1.50–$2.75 for summarization baselines (**3x cheaper**)

---

## 2. Recursion Depth Study (Paper 2)

### 2.1 Core Finding: "Think, But Don't Overthink"

Depth-1 RLMs boost accuracy on complex tasks, but **depth-2 degrades performance** and inflates cost.

| Model | Task | Base | Depth=1 | Depth=2 |
|-------|------|------|---------|---------|
| DeepSeek v3.2 | S-NIAH | 100% | 85% | 70% |
| DeepSeek v3.2 | OOLONG | 0% | 42.1% | 33.7% |
| Kimi K2 | OOLONG | 86.6% | 60% | 55% |

### 2.2 Three Failure Modes at Depth > 1

1. **Parametric hallucination** — Model abandons input context entirely, falls back to training knowledge (e.g., returns nuclear physics "magic numbers" instead of searching given text)
2. **Formatting collapse** — Model confuses REPL scratchpad with user-facing output, returns raw code instead of formatted answers
3. **Endless verification loops** — Continuous re-verification cycles without stopping (741.5s for single sample)

### 2.3 Latency Explosion

| Model | Task | Base | Depth=1 | Depth=2 |
|-------|------|------|---------|---------|
| DeepSeek | S-NIAH | 3.6s | 89.3s | **344.5s** |
| Kimi K2 | OOLONG | — | — | **545.5s** |

### 2.4 Implications

- Depth=1 is the **practical sweet spot**
- Strong native long-context models (Kimi K2) may **not benefit** from RLM at all
- RLM is most valuable for weaker models that struggle with long contexts natively

---

## 3. RLM for Jailbreak Detection (Paper 3)

### 3.1 Procedural Decomposition Pattern

RLM-JB transforms single-pass classification into a **5-stage pipeline**:

```
Stage 0: De-obfuscation (Base64 detection + decode)
Stage 1: Chunking (overlapping segments, paragraph + fixed-size splitting)
Stage 2: Per-segment analysis (independent worker models)
Stage 3: Result parsing (3-tier fallback: JSON → literal eval → safe default)
Stage 4: Verdict aggregation (conservative: ANY malicious → malicious)
```

### 3.2 Results

| Model | Recall | Precision | F1 |
|-------|--------|-----------|-----|
| DeepSeek-V3.2 | 92.5% | 100.0% | 96.1% |
| GPT-4o | 97.0% | 99.74% | 98.35% |
| GPT-5.2 | 98.0% | 98.99% | 98.49% |

GPT-5.2 single-pass baseline: 59.57% recall → 98.0% with RLM-JB (**+38.4pp**, 64.5% relative improvement).

### 3.3 Key Insight

Success comes from **procedural decomposition** (chunking + parallel screening), NOT from deep recursion. The pattern is:
1. Split large input into manageable chunks
2. Process each chunk independently (parallelizable)
3. Aggregate results with conservative policy

This is essentially **MapReduce** orchestrated by an LLM through code.

---

## 4. Gap Analysis: Reference vs. Brainyard

### 4.1 What Brainyard Has (Already Implemented)

| Feature | Reference | Brainyard | Notes |
|---------|-----------|-----------|-------|
| Core REPL loop | ✅ Python sandbox | ✅ SCI (Clojure) sandbox | Equivalent |
| `llm_query` | ✅ | ✅ | Sub-LLM calls |
| `FINAL` | ✅ | ✅ `FINAL` | Equivalent |
| Max iterations | ✅ | ✅ | Default 20 |
| Max depth | ✅ (default 1) | ✅ (default 1) | Equivalent |
| Context as variable | ✅ | ✅ | Plus selective retrieval accessors |
| Code block extraction | ✅ | ✅ | Brainyard enforces single-block |
| Compaction | ✅ `_compact_history()` | ✅ Message + state + iteration compaction | Brainyard more sophisticated |
| Budget monitoring | ✅ `max_budget` USD ceiling | ✅ Token-based budget with LLM feedback | Different approach |
| Persistent state | ✅ `persistent` flag | ✅ Sandbox state extraction/restoration | Brainyard auto-extracts `def` bindings |
| EOF repair | ❌ | ✅ `try-repair-eof` | Brainyard-only feature |
| Structure-aware truncation | ❌ (raw truncation) | ✅ Shape-preserving with temp file recovery | Brainyard-only feature |
| Selective retrieval | ❌ (raw context variable) | ✅ `context-index`, `get-conversation`, etc. | Brainyard-only feature |
| Previous runs chain | ❌ | ✅ Progressive compression | Brainyard-only feature |
| Tool ecosystem | ❌ (custom tools only) | ✅ Full tool registry with `call-tool` | Brainyard-only feature |

### 4.2 What's Missing in Brainyard

#### Gap 1: Batched Sub-LLM Calls (`llm_query_batched`)

**Reference**: `llm_query_batched(prompts, model=None)` sends multiple queries concurrently and returns a list of results. This is critical for MapReduce-style decomposition where the LLM chunks context and processes each chunk independently.

**Brainyard**: Only has `(llm-query prompt)` and `(llm-query prompt sub-context)` — strictly sequential. If the LLM needs to process 10 chunks, it must make 10 sequential calls across 10+ iterations.

**Impact**: High. The paper shows LLMs naturally develop chunk-and-query strategies. Without batching, each sub-call wastes an iteration. A 10-chunk task needs ~12 iterations instead of ~3.

**Suggested implementation**:
```clojure
;; In sandbox bindings:
(llm-query-batched [prompt1 prompt2 ...])           ;; concurrent calls
(llm-query-batched [prompt1 prompt2 ...] sub-context) ;; with shared context
```
Use `pmap` or `future`-based concurrency internally. Return vector of results.

**Files**: `recursive.clj` (add `create-llm-query-batched-fn`), `sandbox.clj` (bind new fn), `rlm_agent.clj` (add to prompt)

#### Gap 2: Recursive RLM Sub-Calls (`rlm_query`)

**Reference**: `rlm_query(prompt, model=None)` spawns a **child RLM** with its own REPL and iteration loop. The child can write code, call tools, and iterate — not just answer a question in one shot.

**Brainyard**: `llm-query` is a **plain chat completion** — single prompt in, single response out. No REPL, no iteration, no tools. This means sub-calls can't decompose complex sub-problems.

**Impact**: Medium. Paper 2 shows depth>1 is risky (overthinking), and Paper 1 found REPL-only (no sub-calls) outperformed full RLM on CodeQA. But for information-dense tasks (OOLONG-Pairs), recursive sub-calls were essential. The brainyard agent already has `max-depth` config but doesn't use it.

**Suggested implementation**:
```clojure
;; In sandbox bindings (when depth < max-depth):
(rlm-query prompt)              ;; child RLM with its own sandbox
(rlm-query prompt sub-context)  ;; child RLM with sub-context
```
Internally: call `rlm/completion` with `depth+1`, `max-depth`, and a smaller sub-model.

**Files**: `recursive.clj` (add `create-rlm-query-fn`), `sandbox.clj` (bind conditionally on depth), `loop.clj` (pass depth to sandbox creation)

**Caution**: Paper 2's failure modes (hallucination, format collapse, infinite loops) must be mitigated with strict timeout, max-iterations-for-sub (e.g., 5), and depth=1 default.

#### Gap 3: `SHOW_VARS()` — Variable Introspection

**Reference**: `SHOW_VARS()` lists all defined variables in the REPL with their types and sizes.

**Brainyard**: No equivalent. The LLM must remember what it `def`'d or use `(inspect)` on specific variables.

**Impact**: Low. Would help LLMs track state across iterations, especially after compaction removes earlier iteration history.

**Suggested implementation**:
```clojure
;; In sandbox bindings:
(show-vars)  ;; => {"data" {:type "vector" :size 42} "result" {:type "string" :size 1024}}
```
Iterate over SCI namespace bindings, filter user-defined vars, return type + size summary.

**Files**: `sandbox.clj` (add `show-vars` to bindings)

#### Gap 4: Async/Parallel Sub-Calls

**Reference**: Identified as a limitation — all sub-calls are synchronous. Paper notes this as future optimization.

**Brainyard**: Also synchronous. But Brainyard already has `task$run :job-type :bash` for async bash. The same pattern could apply to `llm-query`.

**Impact**: Medium-high for latency-sensitive use cases. A 10-chunk task with 5s per sub-call takes 50s sequentially vs ~10s parallel.

**Relationship**: If Gap 1 (`llm_query_batched`) is implemented with internal concurrency, this is partially addressed.

#### Gap 5: Explicit RLM Training (Fine-tuning)

**Reference**: Current RLM results are from **prompt engineering alone** — no explicit training. The paper identifies training as the primary future direction: treating RLM trajectories as reasoning chains trainable via bootstrapping.

**Brainyard**: Also prompt-only. No fine-tuning pipeline.

**Impact**: Long-term. Paper shows RLM-Qwen3-8B (no RLM training) already outperforms base by 28.3%. Explicit training could further improve.

**Not actionable now** — requires training infrastructure. But worth noting that the iteration logs (`all-iterations`) are exactly the training data format needed for future fine-tuning.

#### Gap 6: Model-Aware Iteration Limits

**Reference**: Different models behave very differently:
- GPT-5: conservative with sub-calls (~10/task)
- Qwen3-Coder: liberal (thousands on simple tasks)
- Cost variance: 95th percentile significantly exceeds median

**Brainyard**: Fixed `max-iterations` (default 20) regardless of model. No model-specific tuning.

**Impact**: Low-medium. Could save cost by reducing iterations for capable models and increasing for weaker ones.

**Suggested**: Add model-aware defaults in `rlm_agent.clj`:
```clojure
(defn- model-default-iterations [model-id]
  (cond
    (str/includes? model-id "opus") 10
    (str/includes? model-id "sonnet") 15
    (str/includes? model-id "gpt-5") 10
    :else 20))
```

#### Gap 7: Execution Trajectory Visualization

**Reference**: Ships with a **Next.js visualizer** (`visualizer/`) for inspecting execution traces — shows iteration flow, code blocks, outputs, sub-calls, and timing.

**Brainyard**: TUI shows iterations inline. Web app shows formatted output. But no dedicated trajectory inspector for debugging/analysis.

**Impact**: Low for users, medium for development. The mulog events contain all the data; a visualizer would make debugging much easier.

#### Gap 8: Isolated Execution Environments

**Reference**: Supports multiple sandbox backends: LocalREPL (in-process), Docker, Modal, E2B, Prime, Daytona. Isolated environments communicate via HTTP broker pattern.

**Brainyard**: SCI sandbox only (in-process). Safe due to SCI's deny-list, but cannot run arbitrary system commands or install packages.

**Impact**: Low for current use case (agent tool calling). Would matter for code generation/execution tasks where the LLM needs to pip install, compile, or run arbitrary programs.

---

## 5. Actionable Improvements (Priority Order)

### P0 — High Impact, Moderate Effort

#### 5.1 `llm-query-batched` — Concurrent Sub-LLM Calls

The single highest-impact missing feature. Enables MapReduce patterns that LLMs naturally develop.

```clojure
;; User-facing API in sandbox:
(def chunks (partition-all 50 (clojure.string/split-lines context)))
(def summaries (llm-query-batched
                 (mapv #(str "Summarize:\n" (clojure.string/join "\n" %)) chunks)))
(FINAL (clojure.string/join "\n" summaries))
```

**Implementation sketch** (`recursive.clj`):
```clojure
(defn create-llm-query-batched-fn
  [lm-config sub-lm-config depth max-depth usage-tracker]
  (fn llm-query-batched
    ([prompts] (llm-query-batched prompts nil))
    ([prompts sub-context]
     (when (>= depth max-depth)
       (throw (ex-info "Max recursion depth reached" {:depth depth})))
     (let [sub-ctx-str (when sub-context
                         (let [s (pr-str sub-context)]
                           (subs s 0 (min (count s) 500000))))
           config (or sub-lm-config lm-config)
           futures (mapv (fn [prompt]
                           (future
                             (let [messages [{:role "user"
                                             :content (if sub-ctx-str
                                                        (str prompt "\n\nContext:\n" sub-ctx-str)
                                                        prompt)}]
                                   resp (call-llm config messages
                                                  {:usage-tracker usage-tracker})]
                               (extract-response-text resp config))))
                         prompts)]
       (mapv deref futures)))))
```

**Prompt addition** (in `rlm_agent.clj` system prompt):
```
- `(llm-query-batched [prompt1 prompt2 ...])` — concurrent sub-LLM calls, returns vector of answers
- `(llm-query-batched [prompt1 ...] sub-context)` — with shared sub-context (max 500K chars)
- Use for MapReduce: chunk context → batch-query each chunk → aggregate results
```

**Files to modify**: `recursive.clj`, `sandbox.clj`, `rlm_agent.clj`

#### 5.2 `show-vars` — Variable Introspection

Quick win. Helps LLMs track state, especially after compaction.

```clojure
;; In sandbox.clj, add to bindings:
'show-vars (fn []
             (let [ns-map (sci/eval-string* sci-ctx "(ns-map *ns*)")]
               (->> ns-map
                    (remove (fn [[k _]] (contains? reserved-syms k)))
                    (map (fn [[k v]]
                           [k {:type (type @v) :preview (subs (pr-str @v) 0 (min 80 (count (pr-str @v))))}]))
                    (into (sorted-map)))))
```

**Files to modify**: `sandbox.clj`, `rlm_agent.clj` (prompt)

### P1 — Medium Impact, Higher Effort

#### 5.3 `rlm-query` — Recursive RLM Sub-Calls

Enables child RLMs with their own REPL for complex sub-problems. Must include Paper 2's safety measures:

- **Max sub-iterations**: 5 (not 20)
- **Strict timeout**: 60s per sub-call
- **Depth limit**: 1 (sub-calls are plain `llm-query`, not recursive)
- **Format guardrails**: Sub-RLM must return string answer, not code

```clojure
;; User-facing API in sandbox (only available when depth < max-depth):
(def analysis (rlm-query "Analyze this document for key themes" document-text))
```

**Files to modify**: `recursive.clj`, `sandbox.clj`, `loop.clj`, `rlm_agent.clj`

#### 5.4 Model-Aware Defaults

Different models need different iteration limits and prompt emphasis:

| Model | Max Iterations | Sub-call Style | Prompt Notes |
|-------|---------------|----------------|-------------|
| Opus | 10 | Conservative | Skip efficiency tips (already efficient) |
| Sonnet | 15 | Liberal (needs guardrails) | Emphasize single-block rule |
| Haiku | 20 | Very liberal | More examples, simpler language |
| GPT-5 | 10 | Conservative | Similar to Opus |

**Files to modify**: `rlm_agent.clj`

### P2 — Low Impact / Long-term

#### 5.5 Trajectory Logging for Future Fine-tuning

The iteration logs are already captured. Add structured export:

```clojure
;; Export format matching paper's training data schema:
{:query "..."
 :context-summary "..."
 :trajectory [{:iteration 1
               :code "(def chunks ...)"
               :output "..."
               :reasoning "..." ;; extract from LLM response text
               }]
 :answer "..."
 :success true
 :cost 0.15
 :model "claude-sonnet-4-20250514"}
```

This positions Brainyard for future RLM fine-tuning when training infrastructure is available.

#### 5.6 Execution Trajectory Visualizer

Build a simple web UI (or extend existing web app) to inspect RLM execution traces:
- Timeline of iterations with code, output, errors
- Sub-call tree (when `rlm-query` is implemented)
- Token/cost breakdown per iteration
- Variable state at each step

---

## 6. Lessons from Paper 2 (Anti-Patterns to Avoid)

### 6.1 Don't Go Deep

Depth > 1 causes:
- **Parametric hallucination**: Sub-RLMs abandon context, use training knowledge
- **Format collapse**: Confusion between scratchpad and user-facing output
- **Infinite verification**: Re-checking loops without termination

**Current status**: Brainyard defaults to `max-depth 1` ✅. Keep this default. If `rlm-query` is added, limit sub-call depth strictly.

### 6.2 Don't Over-Engineer Simple Tasks

RLMs **degrade** performance on simple retrieval (S-NIAH: 100% → 85% at depth=1). The REPL overhead adds unnecessary cognitive load for trivial problems.

**Implication**: The system prompt should encourage **direct FINAL** for simple questions. Already partially addressed in the "Efficiency" section, but could be stronger:

```
If the question can be answered from the briefing or your knowledge alone,
call (FINAL "answer") immediately in iteration 1. Do NOT overthink simple questions.
```

### 6.3 Strong Models May Not Benefit

Kimi K2 (strong native long-context) actually **lost** performance under RLM (86.6% → 60%). The REPL paradigm forces a coding indirection that hurts models already good at direct reasoning.

**Implication**: For frontier models (Opus, GPT-5), consider a simpler agent architecture for tasks that don't need tool calling. The RLM overhead is justified only when the LLM needs to:
- Call external tools
- Process context beyond its window
- Decompose complex multi-step problems

---

## 7. Lessons from Paper 3 (MapReduce Pattern)

### 7.1 Procedural Decomposition Works

RLM-JB's success (59.57% → 98.0% recall) came from structured decomposition, not deep recursion:

```
Input → De-obfuscate → Chunk → Parallel Screen → Parse → Aggregate
```

This is a **MapReduce** pattern orchestrated by LLM-written code.

### 7.2 Conservative Aggregation

"ANY chunk malicious → overall malicious" provides a formal guarantee: if any chunk is correctly identified, the final verdict is correct.

**Applicability to Brainyard**: When the LLM processes multiple chunks, it should aggregate conservatively (err on the side of including information rather than dropping it).

### 7.3 Multi-tier Fallback Parsing

RLM-JB uses 3-tier fallback for parsing sub-LLM results:
1. JSON parse
2. Literal eval
3. Safe default

**Applicability**: When `llm-query-batched` is implemented, results should be parsed robustly with fallbacks rather than failing on malformed JSON.

---

## 8. Comparison: Reference Prompt vs. Brainyard Prompt

### Reference System Prompt (key differences)

```python
# Reference uses ```repl language tag, not ```clojure
# Reference provides explicit chunking examples in the prompt:
"For large contexts, chunk the data and process each chunk:
 chunks = [context[i:i+CHUNK_SIZE] for i in range(0, len(context), CHUNK_SIZE)]
 results = llm_query_batched([f'Summarize: {c}' for c in chunks])"

# Reference provides SHOW_VARS() for state introspection
# Reference explicitly mentions rlm_query for recursive sub-calls
```

### Brainyard Prompt Advantages

1. **Selective retrieval**: `context-index`, `get-conversation`, `search-conversation`, `get-previous-run` — the reference just gives raw `context` variable
2. **Tool ecosystem**: `call-tool`, `list-tools`, `get-tool` with full registry — reference only has `custom_tools`
3. **File I/O**: `read-file`, `read-url`, `read-glob`, `spit`, `slurp` — reference sandbox has none
4. **CLI skills**: `skills-list`, `skills-read`, etc. — reference has no skill system
5. **Efficiency guidance**: Explicit tips for direct FINAL, batch scripts, def intermediates — reference has none
6. **Context briefing**: Pre-computed summary in first user message — reference starts cold

### Brainyard Prompt Gaps

1. **No batched query examples**: Missing the MapReduce pattern that LLMs naturally develop
2. **No `SHOW_VARS` mention**: LLMs can't introspect their own state
3. **No explicit chunking guidance**: How to split and process large data

---

## 9. Summary of Recommendations

| Priority | Feature | Impact | Effort | Paper Source |
|----------|---------|--------|--------|-------------|
| P0 | `llm-query-batched` | High — enables MapReduce | Medium | Paper 1, 3 |
| P0 | `show-vars` | Low-Med — state tracking | Low | Paper 1 (ref impl) |
| P1 | `rlm-query` (recursive sub-RLM) | Medium — complex decomposition | High | Paper 1 |
| P1 | Model-aware iteration defaults | Low-Med — cost savings | Low | Paper 1, 2 |
| P1 | Chunking examples in prompt | Medium — guides LLM strategy | Low | Paper 1, 3 |
| P2 | Trajectory export for fine-tuning | Long-term | Medium | Paper 1 |
| P2 | Execution visualizer | Dev productivity | High | Ref impl |
| — | Depth > 1 | **Avoid** — degrades performance | — | Paper 2 |

The single most impactful improvement is **`llm-query-batched`** — it unlocks the MapReduce decomposition pattern that both Paper 1 and Paper 3 show is the primary driver of RLM's real-world gains.
