# RLM Benchmark Tests

Benchmark harness for evaluating the RLM (Recursive Language Model) agent against synthetic tasks derived from the RLM paper suite (arXiv:2512.24601).

## Benchmarks

### 1. S-NIAH (Single Needle-In-A-Haystack) — O(1)

Based on NVIDIA's RULER benchmark. A single "needle" sentence is embedded in large filler text. The agent must find it.

- **Needle format**: `"One of the special magic numbers for {key} is: {value}."`
- **Query**: `"What is the special magic number for {key}?"`
- **Scoring**: Exact match (case-insensitive, with answer normalization)
- **Default config**: 4 context sizes (8K, 32K, 64K, 128K tokens) x 10 examples = 40 total
- **Paper results**: RLM-Qwen3-8B 100% at depth=1; **degrades to 85% at depth=2** (Paper 2)

### 2. OOLONG (Classification Aggregation) — O(N)

Based on oolongbench/oolong-synth. N categorized text items; queries ask about category distributions.

- **Item format**: `"Item 1 [category]: descriptive text..."`
- **Query types**: `:most-frequent` (which category appears most?) and `:count-category` (how many items in category X?)
- **Scoring**: Exact match for labels; exponential decay `0.75^|delta|` for numeric counts
- **Default config**: 2 context sizes (32K, 64K tokens) x 10 examples = 20 total
- **Paper results**: RLM dramatically improves O(N) tasks; tests MapReduce decomposition

### 3. Simple Retrieval — O(1) Baseline

Key-value pairs in context; query asks for one specific value. Tests that RLM handles trivial tasks efficiently without overthinking.

- **Context format**: `"Key: alpha-1, Value: 7392\nKey: beta-3, Value: 4821\n..."`
- **Query**: `"What is the value for key '{target-key}'?"`
- **Scoring**: Exact match
- **Default config**: 4 pair counts (5, 10, 20, 50) x 5 examples = 20 total
- **Paper 2 finding**: RLM can **degrade** simple retrieval (100% → 85% at depth=1)

## Quick Start

### From REPL (port 53631)

Load the benchmark namespaces:

```clojure
;; Load all bench files (test path not on classpath by default)
(load-file "components/clj-sandbox/test/ai/brainyard/clj_sandbox/bench/scoring.clj")
(load-file "components/clj-sandbox/test/ai/brainyard/clj_sandbox/bench/gen.clj")
(load-file "components/clj-sandbox/test/ai/brainyard/clj_sandbox/bench/sniah.clj")
(load-file "components/clj-sandbox/test/ai/brainyard/clj_sandbox/bench/oolong.clj")
(load-file "components/clj-sandbox/test/ai/brainyard/clj_sandbox/bench/retrieval.clj")
(load-file "components/clj-sandbox/test/ai/brainyard/clj_sandbox/bench/core.clj")

(require '[ai.brainyard.clj-sandbox.bench.core :as bench]
         '[ai.brainyard.clj-sandbox.bench.sniah :as sniah]
         '[ai.brainyard.clj-sandbox.bench.oolong :as oolong]
         '[ai.brainyard.clj-sandbox.bench.retrieval :as retrieval])
```

### Smoke Test (fast, cheap)

```clojure
;; 2 S-NIAH examples at 8K tokens — ~$0.01, ~10s
(def r (bench/run-benchmark sniah/benchmark-def
         :context-sizes [8000] :examples-per-size 2))
(bench/print-summary r)
```

### Single Benchmark

```clojure
;; Full S-NIAH with specific model
(require '[ai.brainyard.clj-llm.interface :as llm])
(def lm (llm/create-lm {:model "claude-sonnet-4-6"}))

(def r (bench/run-benchmark sniah/benchmark-def :lm-config lm))
(bench/print-summary r)
(bench/save-results r)
```

### All Benchmarks

```clojure
(def results (bench/run-all :lm-config lm))
(doseq [r results] (bench/print-summary r))
(doseq [r results] (bench/save-results r))
```

### Compare Models

```clojure
(def sonnet-r (bench/run-benchmark sniah/benchmark-def
                :lm-config (llm/create-lm {:model "claude-sonnet-4-6"})))
(def haiku-r (bench/run-benchmark sniah/benchmark-def
               :lm-config (llm/create-lm {:model "claude-haiku-4-5-20251001"})))
(bench/compare-runs sonnet-r haiku-r)
```

## Configuration

Each benchmark has a `default-config` map that can be overridden:

### S-NIAH

| Key | Default | Description |
|-----|---------|-------------|
| `:context-sizes` | `[8000 32000 64000 128000]` | Token counts |
| `:examples-per-size` | `10` | Examples per context size |
| `:max-iterations` | `20` | RLM iteration limit |
| `:max-depth` | `1` | Recursion depth |

### OOLONG

| Key | Default | Description |
|-----|---------|-------------|
| `:context-sizes` | `[32000 64000]` | Token counts |
| `:examples-per-size` | `10` | Examples per context size |
| `:categories` | 6 TREC categories | Category labels |
| `:query-types` | `[:most-frequent :count-category]` | Query variations |
| `:max-iterations` | `20` | RLM iteration limit |

### Simple Retrieval

| Key | Default | Description |
|-----|---------|-------------|
| `:n-pairs-options` | `[5 10 20 50]` | Number of key-value pairs |
| `:examples-per-option` | `5` | Examples per pair count |
| `:max-iterations` | `10` | Lower limit (should be fast) |

## Cost Estimates

| Benchmark | Config | Approx Cost (Sonnet) | Approx Time |
|-----------|--------|---------------------|-------------|
| S-NIAH smoke | 2 x 8K | $0.01 | ~10s |
| S-NIAH full | 40 examples | $2-5 | ~5min |
| OOLONG full | 20 examples | $1-3 | ~5min |
| Retrieval full | 20 examples | $0.10-0.50 | ~2min |
| **All defaults** | **80 examples** | **$3-8** | **~12min** |

## Output Format

`run-benchmark` returns:

```clojure
{:benchmark    "S-NIAH"
 :description  "Single Needle-In-A-Haystack — O(1) retrieval..."
 :config       {:context-sizes [8000] :examples-per-size 2 ...}
 :results      [{:id "sniah-8000t-0"
                  :query "What is the special magic number for charlie-63?"
                  :gold "311248"
                  :answer "311248"
                  :iterations-count 2
                  :terminated-by :final
                  :elapsed-ms 4500
                  :usage {:input-tokens 3200 :output-tokens 150 :total-cost 0.005}
                  :score-result {:score 1.0 :correct? true}} ...]
 :aggregate    {:mean 0.95 :accuracy 0.9 :median 1.0 :count 10}
 :total-cost   0.05
 :total-elapsed-ms 45000
 :timestamp    #inst "2026-03-13T..."}
```

## Files

```
components/clj-sandbox/test/ai/brainyard/clj_sandbox/bench/
  scoring.clj     — exact-match, numeric-exponential-decay, aggregate-scores
  gen.clj         — deterministic synthetic data generation (seeded RNG)
  sniah.clj       — S-NIAH benchmark definition
  oolong.clj      — OOLONG benchmark definition
  retrieval.clj   — Simple Retrieval benchmark definition
  core.clj        — Runner, results I/O, reporting
```

## Key Design Decisions

- **Deterministic**: All data generation uses seeded `java.util.Random` — same config = identical data across runs
- **Self-contained**: No external datasets or downloads needed
- **Context stripped from results**: Large context strings are excluded from saved results (regenerable from seed)
- **Sequential execution**: Avoids API rate limits; `pmap` is a drop-in upgrade if needed
- **REPL-first**: No CLI entry point — designed for interactive use from the development REPL

## Paper References

| # | Title | Key Finding |
|---|-------|-------------|
| 1 | Recursive Language Models (arXiv:2512.24601) | RLM-Qwen3-8B +28.3% over base; benefits scale with O(N) complexity |
| 2 | Think, But Don't Overthink (arXiv:2603.02615) | Depth=1 is optimal; depth>1 degrades accuracy and explodes latency |
| 3 | RLM-JB: Jailbreak Detection (arXiv:2602.16520) | MapReduce decomposition (chunk → parallel screen → aggregate) is the key pattern |
