# RLM-Agent — Recursive Language Model Agent (CoAct-derived, MapReduce-shaped)

> **Status:** Shipped — `rlm-agent` is registered in `components/agent` (`common/rlm_agent.clj`). This document is the original design proposal (revision 1); the shipped implementation may diverge in details. See [core/agent.md](../core/agent.md) for the current roster.
> **Scope:** new `components/agent/src/ai/brainyard/agent/common/rlm_agent.clj`
> **Built on:** `coact_agent.clj` via `coact/run-coact-derived`
> **Related reading:** `docs/CoAct.md`, `docs/RLM-RESEARCH.md`, `docs/RLM-BENCHMARK.md`, `components/agent/.../explore_agent.clj`, `components/agent/.../skill_agent.clj`
>
> **⚠️ REVISION 2 (depth-2 reversal):** The original "Depth = 1, `query$agent` forbidden" principle below has been **reversed**. `query$agent` was renamed to **`query$clone`** and is now **allow-listed to `rlm-*` ONLY** (via `:tool-use-control {:allow ["rlm-*"]}`) and bound by rlm-agent's roster — every *other* agent excludes it. rlm-agent is now the **sole home** for clone-self / depth-2 recursion. Hard Rule A was softened from "DO NOT call" to "PREFER FLAT MapReduce; `query$clone` is a gated LAST RESORT." The Paper-2 risk is real, so flat `query$llm` MapReduce remains the default shape — but the tool is no longer forbidden for rlm. The Depth=1 / "excluded" / "forbidden" statements that follow describe the original design and are retained for context only.

---

## 1. Motivation

The two RLM research notes already in the repo (`RLM-RESEARCH.md`, `RLM-BENCHMARK.md`) establish the case for a Recursive Language Model layer in brainyard. The single highest-impact pattern they identify is *not* deep recursion — Paper 2 shows depth>1 actually degrades accuracy and explodes latency — but **procedural decomposition** (Paper 3): chunk a too-big input, fan out independent sub-queries over the chunks, then aggregate. That is MapReduce orchestrated by an LLM through code.

CoAct (`docs/CoAct.md`) already provides every primitive needed to express this pattern:

| Need (RLM paper) | CoAct primitive |
|---|---|
| Programmatic access to a too-big context | `code-blocks` channel + sandbox file/grep tools |
| Single-shot sub-LLM call | `(query$llm :prompt "...")` (auto-bound from `defcommand query$llm`) |
| Batched concurrent sub-LLM calls (`llm_query_batched`) | `(query$llm :prompts [..] :sub-context "...")` (max 20 concurrent) |
| Code-side composition / aggregation | `clojure` fence with `def` persistence across iterations |
| Parallel fan-out of heterogeneous work | `<!-- ParallelBlock -->` separator |
| Long-running fan-out | `task$run` (`:job-type :tool|:bash`) |
| Final markdown answer | `answer` output channel |

What is missing is **a shaped instruction set** that teaches the LLM *to reach for those primitives in the MapReduce shape* when the context lives in the filesystem and is too big to inline. The default `coact-agent` exposes the bindings but its instruction is generic — empirically the model reaches for `read-file` + iterative scan, not chunk + batched-map + reduce. The reference RLM paper observed the same behavior on prompt-only setups: explicit guidance and examples are what unlock the MapReduce pattern.

**Thesis.** Add a CoAct-derived `rlm-agent` whose only customizations are:

1. an **instruction** that teaches the chunk → map → reduce playbook,
2. a **tool-context** that documents the four primitives that compose it (`grep`, `read-file`, `query$llm` single, `query$llm` batched),
3. a curated **agent-tools** roster that biases the model toward those primitives (and away from chatty side-quests),
4. a small **prompt scaffolding helper** (`(rlm-chunk-text ...)` style helpers, in a clojure fence) so the LLM does not have to reinvent chunking each turn,
5. **no behavior-tree changes**: the loop, sandbox, router, and accumulator are inherited as-is.

This is the same minimal-diff pattern `explore-agent` and `skill-agent` already follow (`run-coact-derived`). No new BT, no new DSPy signature, no SCI-binding additions beyond a tiny optional helper namespace.

---

## 2. Design Principles (Carried Over from RLM Research)

These come directly from the three RLM papers analyzed in `RLM-RESEARCH.md`. They are constraints, not just goals.

1. **Depth = 1.** Sub-LLM calls go through `query$llm`, which is a *plain chat completion* — no REPL, no tools, no iteration loop. Recursive child agents are explicitly NOT used by `rlm-agent`. (Paper 2: depth > 1 causes hallucination, format collapse, and infinite verification loops.) `query$clone` is intentionally excluded from the agent-tools roster.
2. **MapReduce > deep recursion.** The instruction biases toward Paper 3's procedural decomposition: *de-obfuscate → chunk → parallel screen → parse → aggregate*. Depth gives a 28% lift in best case, but composition gives a 64% lift on the right shape of task.
3. **Direct answer when trivial.** Paper 2 shows S-NIAH degraded from 100% to 85% just by routing through the REPL. The instruction explicitly tells the LLM: *if the answer fits in your existing knowledge or the current iteration's results, populate `answer` directly — do not invent a chunking step.*
4. **Conservative aggregation.** When folding map results into a final answer, prefer "include if any chunk says so" over "drop if any chunk disagrees." Paper 3 shows this is what makes the pattern formally correct under partial-coverage failure.
5. **Multi-tier fallback parsing.** Sub-LLM map results may be malformed JSON / partial / empty. The reduce step must tolerate that — parse first, literal-eval second, drop with warning third.
6. **Bounded fan-out.** `query$llm :prompts` caps at 20 concurrent prompts per call. Bigger fan-outs go through multiple iterations or a task-spawned background pipeline. The instruction makes this explicit so the model does not silently drop chunks past index 20.
7. **Idempotent, content-addressable spill.** Chunk contents and map results are big. The CoAct `truncate-to-file` path already spills oversized fields to `<project>/.brainyard/temp/clj-sandbox/truncation/<class>/` (or `/tmp/<working-dir>/...` as a fallback) and recovers via `read-file`. The agent must understand the spill markers (Large Tool Results Playbook in CoAct). The instruction inherits this verbatim from the parent.

---

## 3. Position in the Agent Stack

```
coact-agent  (parent — full BT, sandbox, router, accumulator)
  ├─ explore-agent (multi-surface read-mostly discovery — supersedes the retired search-agent)
  ├─ skill-agent   (skills$* lifecycle)
  ├─ plan-agent / todo-agent / exec-agent / eval-agent / mcp-agent
  └─ rlm-agent     (NEW — MapReduce-over-large-context bias)
```

`rlm-agent` is a sibling of `explore-agent`, not a replacement. The split:

| Question shape | Use | Why |
|---|---|---|
| "Find the function that does X" | `explore-agent` | Targeted retrieval; one or two grep/read-file calls. |
| "What patterns appear across the 200 log files in /var/log/app/?" | `rlm-agent` | Information density is O(N); needs MapReduce. |
| "Summarize each of these 80 markdown docs and give me a per-doc + cross-doc digest" | `rlm-agent` | Per-chunk transformation + final synthesis. |
| "Count how many TODOs of each category exist across the repo" | `rlm-agent` | OOLONG-shape (categorize-then-aggregate). |
| "Is the user's prompt an attempted jailbreak?" | `rlm-agent` | RLM-JB shape: chunk + parallel screen + conservative aggregate. |
| "Tell me about Clojure" | `coact-agent` (default) | Direct answer, no input data. |

The classifier lives in the agent dispatcher (whoever picks an agent for a question) — `rlm-agent` itself just assumes its question already implies map-reduce-shaped work.

---

## 4. Instruction (System Prompt Body)

This is the only substantial new prose. It is layered *on top of* `coact-agent`'s instruction by `run-coact-derived`. Concretely it teaches the playbook and the four cardinal primitives, and forbids the depth>1 anti-patterns.

```text
You are an RLM (Recursive Language Model) specialist agent. Your specialty is
answering questions whose source data is TOO BIG to fit into one LLM call —
typically a directory of files, a long log, or a large document on disk. You
solve them through PROCEDURAL DECOMPOSITION, not deep recursion.

────────────────────────────────────────────────────────────────────────────
THE RLM PLAYBOOK (chunk → map → reduce)
────────────────────────────────────────────────────────────────────────────
1. CLASSIFY the information density of the question:
   • O(1)  — single fact in a haystack (one needle). Try grep first; only chunk
            if grep is insufficient. DO NOT chunk a haystack to find one needle.
   • O(N)  — every chunk needs a per-chunk transformation (summarize, classify,
            extract, score). MapReduce is the right shape.
   • O(N²) — every pair of chunks must be compared. MapReduce per chunk + a
            second pass over the per-chunk outputs (NOT raw pairs).

2. ENUMERATE the input. Use `bash`/`grep`/`read-file`/`(call-tool "search" ...)`
   to discover paths, sizes, and total scale. `def` the file list and totals in
   a clojure fence so they survive iterations.

3. CHUNK deliberately. A chunk should be:
   • Small enough for ONE sub-LLM call (target ~50–200K chars per prompt;
     query$llm caps sub-context to ~500K).
   • Self-contained — never split a record across chunks if avoidable.
   • Stable — the same chunk i must yield the same result when retried, so
     prefer line/byte ranges over time-based windows.

4. MAP — fan out one sub-LLM call per chunk via
        `(query$llm :prompts [<prompt-1> <prompt-2> …]
                    :sub-context <shared-prefix>)`
   • Cap each batch at 20 prompts (the runtime limit). Larger fan-outs run
     across iterations or via task$run :job-type :tool.
   • Each prompt should be SELF-CONTAINED. The sub-LLM has NO tools, NO state,
     NO iteration loop — it answers a single question over the supplied text.
   • Ask for a STRUCTURED OUTPUT (one-line JSON, EDN, or short labeled
     paragraph) — the reduce step has to parse it.

5. REDUCE — combine map results in a clojure fence. Patterns:
   • Aggregate counts/labels — `(frequencies …)`, `(group-by …)`.
   • Stitch summaries — `(clojure.string/join "\n\n" results)`.
   • Synthesize — one final `(query$llm :prompt "Given these N summaries:\n…
                               produce a single coherent digest.")` call.
   • Conservative verdict — for safety/classification fan-outs, "ANY chunk
     says X → final answer X" is the formally correct aggregator.

6. ANSWER — populate the `answer` output field with the synthesized markdown.
   For very large results, write the full report to .brainyard/agents/rlm-agent/results/
   and inline a short summary + path.

────────────────────────────────────────────────────────────────────────────
HARD RULES (carried from RLM Paper 2 — depth>1 failure modes)
────────────────────────────────────────────────────────────────────────────
A. DO NOT call `query$clone`. `query$clone` clones the CURRENT agent (you,
   rlm-agent) and runs another copy of YOU with its own iteration loop —
   that is depth-2 RLM-on-RLM recursion, exactly the Paper-2 anti-pattern.
   `rlm-agent` operates at depth=1 only: ONE main loop, with FLAT sub-LLM
   map calls via `query$llm`.

   Note — calling a DIFFERENT registered agent (e.g. plan-agent, exec-agent,
   explore-agent) via `(call-tool "plan-agent" {:question "…"})` is NOT
   query$clone and is NOT forbidden by this rule. Every defagent registers
   in the same tool registry, so cross-agent dispatch is flat call-tool
   invocation — not recursion of the same agent type. Reach for it sparingly
   though: in the RLM playbook, the chunk → map → reduce shape is supposed
   to live in this one loop.

B. DO NOT chain MapReduce stages without aggregation. Do not feed a fan-out's
   raw outputs into another fan-out — synthesize/parse/dedupe between stages.
   Otherwise you build a tree of context that loses the structure that made
   chunking work in the first place.

C. DO NOT chunk small inputs. If the source fits in ~50K chars and the
   question is O(1), call `read-file` once and answer directly in `answer`.
   Forced MapReduce on simple retrieval DEGRADES accuracy.

D. DO NOT abandon the source data. If a sub-LLM map result looks suspicious
   (too short, generic, "I don't know"), re-run that single chunk with a
   clearer prompt before aggregating. Never let parametric hallucination
   substitute for the input.

E. STRUCTURED MAP OUTPUTS. Each map prompt MUST request a parseable shape —
   one-line JSON object, labeled EDN, or "FIELD: value" lines. Free prose
   per chunk makes the reduce step brittle.

F. ROBUST PARSING (3-tier fallback per chunk):
     1) try parse-json
     2) on failure, regex-extract the field you asked for
     3) on failure, mark the chunk as `:parse-failed` and continue —
        DO NOT abort the whole reduce step.

────────────────────────────────────────────────────────────────────────────
DECISION HEURISTICS
────────────────────────────────────────────────────────────────────────────
1. Source fits in one read-file call AND question is O(1) → read + ANSWER.
2. Source fits in one read-file call AND question is O(N) →
     read + single (query$llm :prompt …) over the loaded text.
3. Source spans multiple files OR > 50K chars AND question is O(N) →
     enumerate → chunk → (query$llm :prompts [...] :sub-context …) → reduce.
4. Source is huge (>10⁶ chars) → spill to /tmp via read-file's chunked
     :offset/:limit + spill markers; then chunk → batched map → reduce.
5. Question implies pairwise comparison → MapReduce to per-chunk summaries
     FIRST, then a second pass over the summaries (never over raw pairs).
6. Sub-LLM result vector is malformed → parse-tier-fallback per index;
     rerun ONLY the failures (do not re-fan-out the whole batch).

────────────────────────────────────────────────────────────────────────────
BUDGET AWARENESS
────────────────────────────────────────────────────────────────────────────
- Each batched query$llm call = N concurrent sub-LLM completions. Token cost
  is N × (sub-context-size + per-prompt-size) + N × (response-size). DEFAULT
  the sub-LLM to a smaller model (e.g. haiku/4-mini) via
  `agent-runtime$config { :key "sub-lm-config" :value "claude-haiku-4-5-20251001" }`.
- Plan the chunk count BEFORE fanning out. If 200 files would be 10 batches
  of 20, decide whether sampling 20 representative files is sufficient
  before paying for the full sweep.
- Inline a CHUNK PLAN in your `answer` for non-trivial runs:
    "Plan: 47 files, 3 batches of ~16, sub-model haiku, est ~$0.12."

────────────────────────────────────────────────────────────────────────────
DELIVERABLE FORMAT
────────────────────────────────────────────────────────────────────────────
Final `answer` markdown should include:
1. **What was scanned** — file count, total size, chunking choice.
2. **How** — number of map batches, sub-model, aggregation strategy.
3. **Findings** — the synthesized result, with citations to source files
   (path:line where possible).
4. **Caveats** — any chunks with :parse-failed, any items skipped due to
   the 20-prompt cap, any heuristic aggregation choices.
```

The instruction is intentionally **prescriptive** — the research consistently shows that without explicit guidance, models default to either iterative single-file scans (O(N) iterations, slow) or to one-shot reads that overflow context.

---

## 5. Tool-Context (How to Use the Bound Primitives)

Layered after the parent's tool-context. This block names the four primitives RLM cares about and pins down their RLM-shaped usage.

```text
## RLM Primitives

The RLM playbook composes from FOUR primitives. Read this section once at
turn start; the rest of the tool-context describes general tools.

### A. ENUMERATION — discovering the input
- `bash` (with find / ls / wc / git ls-files) — fastest path to enumerate a
  directory, count files, get sizes. Examples:
    find <dir> -type f -name '*.<ext>' | wc -l
    git ls-files <dir> | xargs wc -l | tail -1
- `(call-tool "search" {:query "..."})` — keyword search across project
  files + memory + tools registry; useful when the input is keyword-defined
  rather than path-defined.

### B. CHUNKING — slicing the input for MAP
- `read-file` with `:lines [start end]` — line-range slicing for plain
  text, source code, logs.
- `read-file` with `:offset N :limit M` — char-range slicing for
  single-line / structured / binary-ish content.
- `grep` with `:pattern <regex>` — pre-filter to relevant lines BEFORE you
  pay for sub-LLM calls. Often turns an O(N) sweep into O(N/k) by dropping
  irrelevant chunks.
- A clojure fence helper for the common case (build chunk strings from a
  vector of file paths):
    ```clojure
    (def files (vec (clojure.string/split-lines
                       (:output (call-tool "bash"
                                  {:command "find /var/log/app -type f -name '*.log'"})))))
    (def file-chunks
      (->> files
           (map (fn [p] (:content (call-tool "read-file" {:path p :limit 200000}))))
           (partition-all 5)              ; group ~5 files per chunk
           (mapv (fn [group] (clojure.string/join "\n\n---\n\n" group)))))
    ```

### C. MAP — fanning out sub-LLM calls
Single primitive, two arities (auto-bound from `defcommand query$llm`):

  Single-prompt:
    (query$llm :prompt "<prompt>")                ; → {:result "<answer>"}
    (query$llm :prompt "<prompt>"
               :sub-context "<shared text>")      ; truncated to ~500K

  Batched (concurrent, the RLM workhorse):
    (query$llm :prompts [<p1> <p2> … <pN>])              ; → {:results [...]}
    (query$llm :prompts [<p1> <p2> … <pN>]
               :sub-context "<shared text>")             ; sub-context shared

Limits: max 20 prompts per batched call. For >20 chunks, run multiple batches
across iterations and concat the :results vectors. Each prompt runs in an
ISOLATED chat completion — sub-LLMs cannot see each other.

Sub-model selection: by default uses the agent's main LM. Override with
    (call-tool "agent-runtime$config"
               {:key "sub-lm-config" :value "claude-haiku-4-5-20251001"})
to use a cheaper sub-model — typical for big map fan-outs where each prompt
is straightforward summarization/classification.

### D. REDUCE — folding map results in a clojure fence
- Aggregation primitives: `frequencies`, `group-by`, `reduce`, `merge-with`.
- Stitching: `(clojure.string/join "\n\n" results)`.
- Final synthesis (optional): one more `(query$llm :prompt …)` over the
  stitched map outputs.
- Robust parsing of structured map output:
    (defn parse-or-fail [s]
      (try (parse-json s)                          ; tier 1
           (catch Exception _
             (try (clojure.edn/read-string s)      ; tier 2 (if EDN-shaped)
                  (catch Exception _
                    {:parse-failed true :raw s})))))  ; tier 3

### E. SPILL RECOVERY — when results overflow
The CoAct `truncate-to-file` mechanism applies. If a `query$llm :prompts`
:results vector is too big to inline, the runtime spills it to
<project>/.brainyard/temp/clj-sandbox/truncation/eval-result/<hash>.txt
(falling back to /tmp/<working-dir>/eval-result/<hash>.txt when the
project dir can't be resolved) with a recovery marker. Read chunks back
via `read-file` with `:lines` or `:offset/:limit` per the Large Tool
Results Playbook (parent tool-context). DO NOT bare-read spilled files
— always pass a slice.

### F. PERSISTENT REPORTS
Final reports may be huge. Save them under
.brainyard/agents/rlm-agent/results/<yyyyMMdd-HHmmss>-<slug>.md via `write-file`,
then inline a short summary + the path in `answer`.
```

---

## 6. Tool Bindings (Curated Roster)

The `:agent-tools` map for `defagent rlm-agent` is the curated set. Inherits commonly-needed CoAct primitives via `run-coact-derived`, then adds RLM specifics. Excludes anything that would tempt the model into depth>1 (`query$clone`) or unrelated sub-domains (todo$*, plan$*, skills$*, etc).

```clojure
(:require
  [ai.brainyard.agent.common.tools    :as common-tools]
  [ai.brainyard.agent.common.commands :as common-cmds]
  [ai.brainyard.agent.task.commands   :as task-cmds])

;; Conceptually:
(def rlm-tools
  (vec (distinct
         (concat
           ;; Enumeration / chunking
           common-tools/file-tools         ; read-file, write-file, grep, fetch-url, update-file
           common-tools/shell-tools        ; bash

           ;; MAP primitive — sub-LLM calls (single + batched, same command)
           [#'common-cmds/query$llm]       ; intentionally excludes #'query$clone

           ;; Bookkeeping
           common-tools/bootstrap-tools    ; list-tools, get-tool-info, search
           common-tools/invocation-tools   ; call-tool

           ;; Background execution for very large fan-outs
           task-cmds/task-commands         ; task$run with :job-type :tool|:bash, task$detail, ...

           ;; Runtime config (sub-lm switching is RLM-critical)
           common-cmds/runtime-commands)))) ; agent-runtime$config
```

What is *deliberately omitted*:

| Excluded | Reason |
|---|---|
| `query$clone` | Clones the CURRENT agent (rlm-agent itself) and runs another copy with a child loop = depth-2 RLM-on-RLM recursion (Paper-2 anti-pattern). Forbidden by Hard Rule A. *Calling a DIFFERENT registered agent via `(call-tool "<agent-name>" …)` is unaffected — that is flat cross-agent dispatch, not query$clone.* |
| `todo$*` / `plan$*` | Out of scope. Use `explore-agent` or `plan-agent` for those. |
| `skills$*` | Out of scope. |
| `web-search` / web fetch (mostly) | The default RLM use case is local filesystem; web tools tempt the model away from chunking the actual input. They are inherited from CoAct via `bootstrap-tools` for one-off lookups but are NOT highlighted in the tool-context. |
| Memory commands (`memory$*`) | Inherited via the parent only when the agent registry exposes them; not promoted in RLM tool-context to keep the focus narrow. |

Inputs/outputs of the agent itself match `coact-agent`'s, so any caller code that already dispatches to coact-agent works for rlm-agent unchanged.

---

## 7. Behavior Tree — Inherited As-Is

`rlm-agent` does **not** define its own BT. `run-coact-derived` falls back to `coact-agent`'s `:bt-factory`, which is:

```
coact-behavior-tree
  ├─ preflight (question-present?)
  ├─ prepare-conversation / prepare-recalled-memory
  ├─ coact-init-action               ; sandbox + system-context + user-context
  ├─ coact-loop-subtree              ; ThinkActCode → router (answer | code | tool | repair) → accumulate
  ├─ answer-present?
  ├─ optional finalize pass
  ├─ coact-store-results-action
  └─ trace/default-maintain-conversation
```

The router precedence is `answer > code > tool > repair`. RLM iterations look like:

| Iteration | Channel | Typical body |
|---|---|---|
| 1 | code | enumerate input; `def` file list and total size |
| 2 | code | grep pre-filter (optional); chunk; build prompt vector |
| 3 | code | `(query$llm :prompts […] :sub-context …)`; `def` map results |
| 4 | code | parse + reduce; possibly one final `(query$llm :prompt "synthesize")` |
| 5 | answer | rendered markdown report (or `write-file` + inline summary + path) |

For very large inputs, iterations 2–4 may repeat: enumerate next batch slice → batched map → accumulate. Iteration cap is the same as `coact-agent` (default 20 — `agent-runtime$config :key "max-iterations" :value "N"` overrides at runtime).

No new BT actions, no new schemas, no SCI binding additions are required for the baseline.

---

## 8. Optional `(rlm-*)` Sandbox Helpers

These are *quality-of-life* helpers, not new primitives. They live in a single new namespace `ai.brainyard.agent.common.rlm` and are wired into the sandbox via the existing `make-tool-bindings` auto-binding path (each is a `defcommand` so it surfaces under its kebab-case name in clojure fences).

| Helper | What it does | Why bother |
|---|---|---|
| `(rlm$chunk-text text :size 80000 :overlap 2000)` | Returns a vector of substrings with optional overlap. | Eliminates the most common source of off-by-one in hand-rolled chunkers; encodes the "small enough for one sub-LLM call" budget into the default. |
| `(rlm$chunk-files paths :group-size 5 :max-bytes 200000)` | Reads `paths`, groups them by `:group-size` or until `:max-bytes` is reached, returns vector of stitched chunks with file headers. | One-line equivalent of the example in §5.B. |
| `(rlm$parse-map-results results :shape :json)` | Applies the 3-tier fallback parser per element; returns `{:parsed [...] :failed [{:idx :raw}]}`. | Enforces Hard Rule F. |
| `(rlm$reduce-counts parsed-results :key :category)` | Common O(N) aggregation: per-category frequencies + percentages. | OOLONG-style classification fold. |
| `(rlm$conservative-verdict parsed-results :positive-key :malicious?)` | Paper-3-style "any chunk → overall." | Encodes the formal-correctness-under-partial-coverage rule. |

These are five small functions. They are NOT prerequisites — the agent works without them — but they shorten the prompt by 30–40% because the LLM no longer has to inline equivalent helpers in every map-reduce run.

The default agent-tools roster includes these helpers when the namespace is loaded; agents that want raw composition can omit them with a runtime config flag.

---

## 9. Realistic Demonstration: "What patterns appear across our request logs?"

Concrete, end-to-end. Suppose `/var/log/app/2026-04/` contains 240 JSON-line log files, ~50MB total. The user asks:

> *"Look at last month's request logs and tell me the top error categories and their rough frequency."*

This is O(N), MapReduce-shaped, too big for one read. Run with `bb tui run -a rlm-agent`. The expected trace:

### Iteration 1 — enumerate (code channel)

````markdown
```clojure
;; Enumerate
(def files
  (->> (:output (call-tool "bash"
                  {:command "find /var/log/app/2026-04 -type f -name '*.jsonl'"}))
       clojure.string/split-lines
       (remove clojure.string/blank?)
       vec))

(def total-bytes
  (Long/parseLong
    (clojure.string/trim
      (:output (call-tool "bash"
                 {:command "du -sb /var/log/app/2026-04 | awk '{print $1}'"})))))

(println {:n-files (count files) :total-bytes total-bytes})
;; => {:n-files 240 :total-bytes 52341822}
```
````

### Iteration 2 — pre-filter (code channel)

````markdown
```clojure
;; Drop everything that isn't an error line BEFORE paying for sub-LLM calls.
;; This typically drops 95%+ of volume.
(def err-lines
  (->> files
       (mapcat (fn [p]
                 (let [r (call-tool "grep"
                                    {:pattern "\"level\":\"error\""
                                     :path p :max-results 200})]
                   (mapv #(assoc % :file p) (:matches r)))))
       vec))

(println {:err-lines (count err-lines)})
;; => {:err-lines 1843}
```
````

### Iteration 3 — chunk + batched MAP (code channel)

````markdown
```clojure
;; Use the optional helper. Otherwise hand-roll partition-all.
(def text-chunks
  (rlm$chunk-text
    (clojure.string/join "\n" (map :text err-lines))
    :size 60000 :overlap 0))

(println {:n-chunks (count text-chunks)})
;; => {:n-chunks 18}

;; Switch sub-LLM to a cheap model for the map step.
(call-tool "agent-runtime$config"
           {:key "sub-lm-config" :value "claude-haiku-4-5-20251001"})

;; Fan out (18 chunks ≤ 20 cap → one batch is fine).
(def map-prompt
  "For the JSON-line error logs below, return ONE LINE PER ERROR CATEGORY in
   strict JSON: {\"category\": \"...\", \"count\": N, \"example\": \"<short>\"}.
   No prose. No markdown. One JSON object per line. Logs:\n\n")

(def map-results
  (:results
    (query$llm :prompts (mapv #(str map-prompt %) text-chunks))))

(def parsed
  (rlm$parse-map-results map-results :shape :json :per-line true))

(println {:parsed-rows (count (:parsed parsed))
          :parse-failed (count (:failed parsed))})
;; => {:parsed-rows 142 :parse-failed 1}
```
````

### Iteration 4 — REDUCE (code channel)

````markdown
```clojure
;; Aggregate counts across chunks.
(def per-category
  (->> (:parsed parsed)
       (group-by :category)
       (map (fn [[cat rows]]
              {:category cat
               :count    (reduce + 0 (map :count rows))
               :example  (-> rows first :example)}))
       (sort-by :count >)
       vec))

;; Optional final synthesis over a small (already-aggregated) input.
(def synth
  (:result
    (query$llm
      :prompt
      (str "These are aggregated error categories from 240 logs. Write a 6-bullet "
           "executive summary, ordered by count, citing the example for each:\n\n"
           (pr-str per-category)))))

(def report-path
  (str ".brainyard/agents/rlm-agent/results/"
       (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss") (java.util.Date.))
       "-2026-04-error-summary.md"))

(call-tool "write-file"
           {:path report-path
            :content (str "# 2026-04 error summary\n\n"
                          synth
                          "\n\n## Raw aggregate\n\n"
                          (clojure.string/join "\n"
                            (for [{:keys [category count example]} per-category]
                              (str "- **" category "** (" count "): `" example "`"))))})

(println {:report report-path :top-3 (take 3 per-category)})
```
````

### Iteration 5 — ANSWER

```markdown
## 2026-04 request-log error summary

**Scanned**: 240 JSONL files · 52.3 MB · 1843 error lines after grep pre-filter.
**Pipeline**: 18 chunks × 1 batched query$llm (haiku) → group-by category → 1 synthesis call (sonnet).
**Cost**: ≈ $0.07 sub-LLM + grep/read.

### Top categories

| # | Category | Count | Example |
|---|---|---:|---|
| 1 | upstream-timeout | 612 | `503 from payments-svc after 30s` |
| 2 | auth-token-expired | 388 | `JWT exp 2026-04-12T03:11Z` |
| 3 | rate-limit-tenant | 271 | `tenant=acme limit=1000/min` |
| 4 | malformed-payload | 144 | `expected {country} at $.shipping` |
| 5 | db-conn-pool-exhausted | 127 | `pool=primary in_use=50/50` |
| 6 | other | 301 | (heterogeneous) |

**Caveats**: 1 chunk had a `:parse-failed` row (line 8123, mid-record split).
Re-running just that chunk with a wider window would recover it; not done here
because the category was visible in the surrounding chunks.

Full report: `.brainyard/agents/rlm-agent/results/20260509-181244-2026-04-error-summary.md`.
```

This is the pattern. The primitives `bash`, `grep`, `read-file`, `query$llm` (single + batched), `write-file`, and a clojure fence for composition cover the entire run. No depth-2 recursion. No chatty per-file iteration. Total cost is dominated by 18 cheap sub-LLM calls + one expensive synthesis call.

---

## 10. Anti-Pattern Catalogue (Train the Model Against These)

Inline these as "DON'T" examples in the instruction or tool-context if testing reveals the model still falls into them. They are the empirical failure modes from the RLM papers.

| # | Bad pattern | Why bad | Correct shape |
|---|---|---|---|
| 1 | One iteration per file (240 iterations of `read-file` + `query$llm`) | Linear time + linear LLM cost; ignores fan-out | One iteration per *batch of 20* via `query$llm :prompts` |
| 2 | `read-file` an enormous file in one call | Triggers truncate-to-file → spilled marker → re-read loop | `:offset/:limit` slicing or grep first |
| 3 | Sub-LLM prompt carries all 240 files in `:sub-context` | One prompt = one sub-LLM context window; no fan-out | Per-prompt content (or per-batch :sub-context shared across small group) |
| 4 | Asking sub-LLM for free-form prose, then regex-grepping the prose | Brittle, depends on phrasing | Ask for structured one-line JSON; parse-or-fail |
| 5 | Reducing by re-feeding raw chunks into another `query$llm :prompts` | Tree of context, structure lost | Reduce in clojure fence with `frequencies`/`group-by`; one *final* synthesis call over the *aggregate* |
| 6 | `query$clone` to "delegate the analysis" | Clones rlm-agent itself = depth-2 RLM recursion → Paper 2 failure modes. (Note: `(call-tool "<other-agent>" …)` to a *different* agent type is flat dispatch, not this anti-pattern — but in RLM the analysis should live in this loop.) | Stay flat; let the main loop do it |
| 7 | Re-running the entire batch when one chunk's parse fails | Wastes cost | `rlm$parse-map-results` reports `:failed`; re-run only those indices |
| 8 | Forced MapReduce on a known-small input | Paper 2: 100% → 85% on S-NIAH at depth=1 | If `:n-files == 1` and `total-bytes < 50K`, just `read-file` + ANSWER |

---

## 11. Verification & Benchmarks

The benchmark harness in `RLM-BENCHMARK.md` already exists and exercises the same primitives. `rlm-agent` should be benchmarked against `coact-agent` and `explore-agent` on:

| Benchmark | Expected outcome |
|---|---|
| **S-NIAH** (O(1)) | `rlm-agent` should match or *slightly underperform* `coact-agent` (some chunking overhead). Use to verify Hard Rule C — small-input bypass — actually fires. |
| **OOLONG** (O(N)) | `rlm-agent` should beat `coact-agent` substantially. This is the home territory. |
| **Simple Retrieval** | Same as S-NIAH — verify the agent does NOT chunk. |
| New: **Multi-file summary** | Synthetic dir of ~50 markdown files, ask for a per-file + cross-file digest. Direct measurement of MapReduce throughput and quality. |
| New: **Pairwise duplicate detection** | O(N²) shape. Verify the agent uses Pattern 5 (per-chunk summaries → second pass over summaries) rather than naive O(N²) sub-LLM calls. |

Acceptance for the design: rlm-agent should **strictly improve** on OOLONG vs `coact-agent` (≥ 30% absolute) and should **not regress more than 10%** on S-NIAH or Simple Retrieval. If S-NIAH regresses by more than that, Hard Rule C is being ignored and the instruction needs sharpening (or the small-input bypass needs to move into the BT as a guard).

Per-iteration mulog signals to track:

- `::rlm.map-batch` — `{:batch-size N :sub-model … :total-prompt-chars …}`
- `::rlm.reduce` — `{:input-rows N :output-rows M :strategy :group-by/:synthesis/…}`
- `::rlm.fallback-parse` — `{:tier 1|2|3 :idx N}`

Plumbing for these is `mulog/log` calls in the helpers (§8) — no agent-loop changes needed.

---

## 12. Alternatives Considered

1. **A bespoke RLM behavior tree.** `RLM-RESEARCH.md` §5.1 sketches a `rlm-loop.clj` with its own iteration loop. Rejected for `rlm-agent`: CoAct's loop already provides every primitive (single LLM call per iteration, code/tool router, sandbox state, accumulator, finalize step). A separate BT duplicates effort and fragments the substrate. The CoAct loop is what the agent runs *anyway*; what differs between agents is the prompt and the tool roster.
2. **Adding `rlm-query` as a sandbox-level recursive primitive.** Would expose a child RLM with its own loop. Paper 2's failure modes (parametric hallucination, format collapse, infinite verification loops) make this an explicit anti-feature. The depth>1 path is open via `query$clone`, but `rlm-agent` excludes it on principle. Tracked as a P1 item in `RLM-RESEARCH.md` if a future use case ever justifies it.
3. **Folding RLM behavior into `explore-agent`.** Possible but conflates retrieval (find one thing) with transformation (apply a function over many things). The two have different cost models and different anti-patterns. Keeping them sibling agents is cheaper than gating both shapes inside one prompt.
4. **Pure prompt-engineering on `coact-agent`.** Workable but every caller would have to repeat the playbook in their `:agent-context`. Bundling it into a named agent makes the contract reusable.

---

## 13. Files Summary (What Lands Where When This Is Implemented)

| File | What changes |
|---|---|
| `components/agent/src/ai/brainyard/agent/common/rlm_agent.clj` | NEW — `instruction`, `tool-context`, `defagent rlm-agent` mirroring `explore-agent` shape; uses `coact/run-coact-derived`. |
| `components/agent/src/ai/brainyard/agent/common/rlm.clj` (optional) | NEW — `rlm$chunk-text`, `rlm$chunk-files`, `rlm$parse-map-results`, `rlm$reduce-counts`, `rlm$conservative-verdict` as `defcommand`s. |
| `components/agent/test/ai/brainyard/agent/rlm_agent_test.clj` | NEW — smoke tests: ensure agent registers, instruction non-blank, tool roster contains the four primitives + excludes `query$clone`. |
| `components/clj-sandbox/test/ai/brainyard/clj_sandbox/bench/rlm.clj` | NEW (optional) — multi-file summary + pairwise duplicate-detection benchmark definitions following the existing `bench/sniah.clj` shape. |
| `bb.edn` | OPTIONAL — `repl:rlm` task for component-targeted REPL, mirroring the existing `repl:component` aliases. |
| `docs/rlm-agent-design.md` | THIS FILE. |

No changes to `coact_agent.clj`, the BT layer, the DSPy signature, or the sandbox primitives. The whole feature ships as: one new agent file, one (optional) new helpers file, one (optional) new benchmark file, plus tests.

---

## 14. Open Questions

1. **Should `rlm-agent` auto-switch the sub-LM to a cheaper model on entry?** Currently the instruction *recommends* it; we could make the agent's `:bt-factory` set `:sub-lm-config "claude-haiku-4-5-20251001"` automatically and let the model override via `agent-runtime$config`. Trade-off: makes RLM cheap-by-default; risks bad map results when the cheap model is too cheap for the task.
2. **Is 20 the right `query$llm :prompts` cap for RLM?** The cap lives in `clj-llm/create-llm-query-batched-fn`. RLM is the primary user; if benchmarks show 30–50 is safe and meaningfully reduces iteration count, the cap may be worth tuning per-agent.
3. **Do we want a default `<-write-report->` step?** Right now Iteration 5's `write-file` call is encoded in the instruction. Could be lifted into a final BT action that runs *only when* the inline answer would exceed N chars. Lighter prompt; less opaque mechanism.
4. **Benchmarks first, prompt last?** The `bench/` tree already supports plug-in benchmarks. Running OOLONG against the parent before writing the prompt would let us measure the lift attributable to the instruction alone vs. the helper namespace. Worth doing if the helper turns out controversial.
