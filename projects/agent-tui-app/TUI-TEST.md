# TUI Integration Test Guide

End-to-end testing of the agent TUI using `CliClient` from a REPL. Spawns `bb tui` as a subprocess, sends questions, captures output, and asserts on results — no manual interaction required.

## Prerequisites

- JDK 11+, Clojure 1.12+, Babashka
- API keys in `<brainyard-root>/.env` (ANTHROPIC_API_KEY, OPENAI_API_KEY, etc.)
- nREPL running for the agent component

## 1. Start the REPL

```bash
cd /path/to/brainyard
bb repl:component agent
# nREPL starts on a random port (e.g., 53631), loads .env with API keys
```

Connect from your editor or via `clj-nrepl-eval`:

```bash
clj-nrepl-eval --discover-ports
clj-nrepl-eval -p <port> "(println :ready)"
```

## 2. Reload After Code Changes

If you edited sandbox, prompt, or agent namespaces, reload before testing:

```clojure
;; Reload core namespaces (order matters)
(require '[ai.brainyard.clj-sandbox.core.sandbox :as sandbox] :reload)
(require '[ai.brainyard.clj-sandbox.core.prompt :as prompt] :reload)
(require '[ai.brainyard.agent.common.coact-agent] :reload)

;; Clear stale agent instances (critical after reload)
(reset! @#'ai.brainyard.agent.core.agent/!agent-registry {})
```

**Why clear the registry**: `get-or-create-agent` caches agent instances. After a reload, cached agents retain old BT context, old protocol implementations, and old sandbox bindings. Clearing forces fresh agent creation with current code.

## 3. Quick Smoke Test (Sandbox Only)

Verify sandbox bindings work before launching the full TUI:

```clojure
(require '[ai.brainyard.clj-sandbox.core.sandbox :as sandbox])

(let [sb (sandbox/create-sandbox)]
  ;; JSON parsing (no require needed) — string keys by default
  (println "parse-json:" (:result (sandbox/eval-code sb "(parse-json \"{\\\"a\\\":1}\")")))
  ;; JSON with keyword keys (opt-in)
  (println "parse-json kw:" (:result (sandbox/eval-code sb "(parse-json \"{\\\"a\\\":1}\" :key-fn keyword)")))
  ;; Number parsing
  (println "Double:"     (:result (sandbox/eval-code sb "(Double/parseDouble \"3.14\")")))
  ;; format
  (println "format:"     (:result (sandbox/eval-code sb "(format \"%.2f\" 3.14159)"))))
;; Expected:
;;   parse-json: {"a" 1}         ← string keys (default since 2026-04-06)
;;   parse-json kw: {:a 1}       ← keyword keys (opt-in)
;;   Double:     3.14
;;   format:     3.14
```

## 4. Launch TUI with CliClient

### 4.1 Setup

```clojure
(require '[ai.brainyard.agent.stdio.client :as cli]
         '[clojure.string :as str])

;; Helpers
(defn strip-ansi [s]
  (str/replace s #"\033\[[0-9;]*[A-Za-z]|\033\[\?[0-9]*[hl]" ""))

(defn clean-text
  "Strip ANSI codes, join lines into one searchable string."
  [lines]
  (str/join "\n" (map strip-ansi lines)))

(defn get-clean-output [c]
  (clean-text (cli/get-all-output c)))
```

### 4.2 Start TUI

```clojure
(def root "/path/to/brainyard")  ;; <-- EDIT THIS

(def c (cli/start!
        :command ["bash" "-c"
                  (str "cd " root " && set -a && source .env"
                       " && bb tui run -a coact-agent -p claude-code -m opus -i")]
        :working-dir root))

;; Wait for startup (~15-30s for JVM + namespace loading)
(Thread/sleep 30000)
(cli/wait-for-idle c :idle-ms 5000 :timeout-ms 30000)
(println "Lines:" (count (cli/get-all-output c)))
```

**Key flags**:
- `-i` / `--inline` — disables alt-screen mode (required for CliClient to capture output)
- `-a coact-agent` — agent type (or `coact-agent`, `react-agent`)
- `-p claude-code -m opus` — provider and model (also: `-p anthropic -m claude-sonnet-4-6`)
- `-- claude-code:opus` — legacy provider:model syntax (still supported)

### 4.3 Provider/Model Options

| Provider | Example | Notes |
|----------|---------|-------|
| `claude-code` | `-p claude-code -m opus` | Default provider. Models: `haiku` (default), `sonnet`, `opus` |
| `anthropic` | `-p anthropic -m claude-sonnet-4-6` | Requires ANTHROPIC_API_KEY |
| `openai` | `-p openai -m gpt-4.1-mini` | Requires OPENAI_API_KEY |
| `ollama` | `-p ollama -m glm-5:cloud` | Local Ollama instance |

## 5. Runtime Configuration: `enable-finalize-answer` and `max-refinements`

### 5.1 What They Do

| Option | Type | Default | Effect |
|--------|------|---------|--------|
| `enable-finalize-answer` | boolean | `false` | When true, runs FinalizeAnswer DSPy signature after RLM completes to synthesize a polished answer from evidence. When false, uses the draft answer from RLM as-is. |
| `max-refinements` | integer | `0` | Number of quality-check refinement rounds. When 0, single RLM execution. When > 0, runs an evaluate → refine loop up to N extra times. |

### 5.2 Execution Flow by Configuration

```
Case 1: enable-finalize-answer=false, max-refinements=0
  RLM sandbox → draft answer → done (fastest, cheapest)

Case 2: enable-finalize-answer=true, max-refinements=0
  RLM sandbox → draft answer → FinalizeAnswer DSPy → polished answer

Case 3: enable-finalize-answer=true, max-refinements=2
  RLM sandbox → EvaluateAnswer → (if incomplete: refine + re-run, up to 2x)
             → FinalizeAnswer DSPy → polished answer
```

### 5.3 Setting via `/config` in TUI

```clojure
;; View current values
(cli/clear-output! c)
(cli/send-line! c "/config enable-finalize-answer")
(cli/wait-for-idle c :idle-ms 3000 :timeout-ms 15000)
(println (get-clean-output c))

;; Change values
(cli/clear-output! c)
(cli/send-line! c "/config enable-finalize-answer false")
(cli/wait-for-idle c :idle-ms 3000 :timeout-ms 15000)

(cli/clear-output! c)
(cli/send-line! c "/config max-refinements 2")
(cli/wait-for-idle c :idle-ms 3000 :timeout-ms 15000)
```

## 6. Ask Questions and Capture Output

### 6.1 Basic Pattern

```clojure
;; Clear previous output, send question, wait for completion
(cli/clear-output! c)
(cli/send-line! c "list tools")
(cli/wait-for-idle c :idle-ms 10000 :timeout-ms 180000)

;; Read and save output
(let [output (get-clean-output c)]
  (spit "/tmp/q1-output.txt" output)
  (println "Output:" (count output) "chars"))
```

### 6.2 ask-and-wait Helper

Recommended wrapper that confirms the question was received before waiting for the answer:

```clojure
(defn ask-and-wait
  "Send question, wait for echo, wait for idle. Returns captured output lines."
  [c question & {:keys [idle-ms idle-timeout-ms]
                 :or   {idle-ms 10000 idle-timeout-ms 180000}}]
  (cli/clear-output! c)
  (cli/send-line! c question)
  ;; Wait for TUI to echo "You: <question>" (proves receipt)
  (let [echo-word (first (str/split question #"\s+" 2))]
    (cli/wait-for c (re-pattern (str "(?i)" (java.util.regex.Pattern/quote echo-word)))
                  :timeout-ms 15000))
  ;; Wait for answer completion (no new output for idle-ms)
  (cli/wait-for-idle c :idle-ms idle-ms :timeout-ms idle-timeout-ms)
  (cli/get-all-output c))
```

**Why two-step wait**: The TUI renders a status bar with token counts that may match prematurely. Waiting for the question echo first, then idle detection, avoids false positives.

### 6.3 Multi-Turn Conversation

Variables and conversation history persist across questions within a session:

```clojure
;; Turn 1
(let [lines (ask-and-wait c "list tools")
      text  (clean-text lines)]
  (println "Has tools?" (str/includes? text "aws$whoami")))

;; Turn 2 — builds on turn 1 context
(let [lines (ask-and-wait c "read aws-cli skill and summarize in max 20 sentences")
      text  (clean-text lines)]
  (println "Has summary?" (str/includes? text "AWS")))

;; Turn 3 — uses skill context from turn 2
(let [lines (ask-and-wait c "with aws-cli skill, get me the recent aws cloud cost over 3 months"
                           :idle-ms 12000 :idle-timeout-ms 300000)
      text  (clean-text lines)]
  (spit "/tmp/aws-cost.txt" text)
  (println "Has cost data?" (boolean (re-find #"\$[\d,]+\.\d{2}" text))))
```

### 6.4 Meta-Commands

```clojure
;; :status — shows agent info
(cli/clear-output! c)
(cli/send-line! c ":status")
(cli/wait-for-idle c :idle-ms 3000 :timeout-ms 15000)
(println (get-clean-output c))

;; /model — switch model mid-session
(cli/clear-output! c)
(cli/send-line! c "/model claude-code:sonnet")
(cli/wait-for-idle c :idle-ms 3000 :timeout-ms 15000)
(println (get-clean-output c))
```

## 7. Assertions and Verification

### 7.1 Check for Iteration Markers

```clojure
(defn count-iterations [text]
  (count (re-seq #"Iteration \d+ / \d+" text)))

(defn has-pass? [text]
  (boolean (re-find #"PASS" text)))

(defn has-hallucination? [text]
  (boolean (re-find #"HALLUCINATED" text)))

(defn has-error? [text]
  (boolean (re-find #"(?i)unsupported escape|could not resolve" text)))

;; Usage
(let [text (get-clean-output c)]
  (println "Iterations:" (count-iterations text))
  (println "Passed?"     (has-pass? text))
  (println "Hallucinated?" (has-hallucination? text))
  (println "SCI errors?" (has-error? text)))
```

### 7.2 Extract Answer Box

```clojure
(defn extract-answer
  "Extract content between Answer box borders."
  [text]
  (let [lines (str/split-lines text)
        in-answer (volatile! false)
        result (volatile! [])]
    (doseq [l lines]
      (cond
        (and (not @in-answer) (str/includes? l "Answer"))
        (vreset! in-answer true)

        (and @in-answer (re-find #"^[└┘]" (str/trim l)))
        (vreset! in-answer false)

        @in-answer
        (vswap! result conj (str/trim l))))
    (str/join "\n" (remove str/blank? @result))))

(println (extract-answer (get-clean-output c)))
```

## 8. Complete Test Script

Save to a file (e.g., `/tmp/tui-test.clj`) and load from the REPL:

```clojure
(require '[ai.brainyard.agent.stdio.client :as cli]
         '[clojure.string :as str])

;; --- Config -----------------------------------------------------------------
(def root "/path/to/brainyard")  ;; <-- EDIT THIS
(def provider "claude-code")
(def model "opus")
(def agent-type "coact-agent")

;; --- Helpers ----------------------------------------------------------------
(defn strip-ansi [s]
  (str/replace s #"\033\[[0-9;]*[A-Za-z]|\033\[\?[0-9]*[hl]" ""))

(defn clean-text [lines]
  (str/join "\n" (map strip-ansi lines)))

(def results (atom {:tests 0 :pass 0 :fail 0}))

(defn check [label pred]
  (swap! results update :tests inc)
  (if pred
    (do (swap! results update :pass inc)
        (println (str "  PASS  " label)))
    (do (swap! results update :fail inc)
        (println (str "  FAIL  " label)))))

(defn ask-and-wait [c question & {:keys [idle-ms idle-timeout-ms]
                                   :or {idle-ms 10000 idle-timeout-ms 180000}}]
  (cli/clear-output! c)
  (cli/send-line! c question)
  (let [echo-word (first (str/split question #"\s+" 2))]
    (cli/wait-for c (re-pattern (str "(?i)" (java.util.regex.Pattern/quote echo-word)))
                  :timeout-ms 15000))
  (cli/wait-for-idle c :idle-ms idle-ms :timeout-ms idle-timeout-ms)
  (cli/get-all-output c))

;; --- Start TUI --------------------------------------------------------------
(println "\n========================================")
(println " TUI Integration Test")
(println (str " Agent: " agent-type " | Provider: " provider ":" model))
(println "========================================\n")

(def c (cli/start!
        :command ["bash" "-c"
                  (str "cd " root " && set -a && source .env"
                       " && bb tui run -a " agent-type " -p " provider " -m " model " -i")]
        :working-dir root))

(try
  ;; 1. Startup
  (println "[1/4] Waiting for startup...")
  (Thread/sleep 30000)
  (cli/wait-for-idle c :idle-ms 5000 :timeout-ms 30000)
  (check "Process alive" (cli/alive? c))
  (check "Output received" (pos? (count (cli/get-all-output c))))

  ;; 2. List tools
  (println "\n[2/4] list tools")
  (let [text (clean-text (ask-and-wait c "list tools"))]
    (spit "/tmp/tui-test-q1.txt" text)
    (check "Has tools"     (str/includes? text "aws$whoami"))
    (check "Has mcp"       (str/includes? text "mcp$"))
    (check "Has skills"    (str/includes? text "skills$"))
    (check "No SCI errors" (not (re-find #"(?i)unsupported escape|could not resolve" text)))
    (check "Eval passed"   (boolean (re-find #"PASS" text))))

  ;; 3. AWS cost query (tests backslash handling, parse-json, format, Double)
  (println "\n[3/4] aws cloud cost (critical sandbox test)")
  (let [text (clean-text (ask-and-wait c
               "with aws-cli skill, get me the recent aws cloud cost over 3 months"
               :idle-ms 12000 :idle-timeout-ms 300000))]
    (spit "/tmp/tui-test-q2.txt" text)
    (check "Has cost data"     (boolean (re-find #"\$[\d,]+\.\d{2}" text)))
    (check "No escape errors"  (not (str/includes? text "Unsupported escape")))
    (check "No json/read-str"  (not (str/includes? text "Could not resolve symbol: json/read-str")))
    (check "Eval passed"       (boolean (re-find #"PASS" text)))
    (check "Not hallucinated"  (not (re-find #"HALLUCINATED" text)))
    (println "  Iterations:" (count (re-seq #"Iteration \d+" text))))

  ;; 4. Shutdown
  (println "\n[4/4] Shutdown")
  (let [code (cli/shutdown! c :timeout-ms 15000)]
    (check "Clean exit" (zero? code)))

  (catch Exception e
    (println "\nFATAL:" (ex-message e))
    (doseq [line (take-last 15 (cli/get-all-output c))]
      (println "  " (strip-ansi line))))
  (finally
    (.close c)))

;; --- Summary ----------------------------------------------------------------
(let [{:keys [tests pass fail]} @results]
  (println "\n========================================")
  (println (str " Results: " pass "/" tests " passed"
               (when (pos? fail) (str ", " fail " FAILED"))))
  (println "========================================")
  (println " Output saved to /tmp/tui-test-q1.txt, /tmp/tui-test-q2.txt"))
```

Run it:

```bash
clj-nrepl-eval -p <port> '(load-file "/tmp/tui-test.clj")' --timeout 600000
```

## 9. Finalize/Refinement Comparison Test Suite

Compares answer quality, iteration count, and cost across three configurations. Each case spawns a fresh TUI session, asks the same question, and records results for side-by-side comparison.

Save to `/tmp/tui-comparison-test.clj` and load from the REPL.

```clojure
(require '[ai.brainyard.agent.stdio.client :as cli]
         '[clojure.string :as str])

;; --- Config -----------------------------------------------------------------
(def root "/path/to/brainyard")  ;; <-- EDIT THIS
(def provider "claude-code")
(def model "opus")
(def agent-type "coact-agent")
(def test-question "with aws-cli skill, get me the recent aws cloud cost over 3 months")

;; Three test cases to compare
(def test-cases
  [{:label "Case 1: no finalize, no refinements"
    :enable-finalize-answer false
    :max-refinements 0}
   {:label "Case 2: finalize, no refinements"
    :enable-finalize-answer true
    :max-refinements 0}
   {:label "Case 3: finalize + 2 refinements"
    :enable-finalize-answer true
    :max-refinements 2}])

;; --- Helpers ----------------------------------------------------------------
(defn strip-ansi [s]
  (str/replace s #"\033\[[0-9;]*[A-Za-z]|\033\[\?[0-9]*[hl]" ""))

(defn clean-text [lines]
  (str/join "\n" (map strip-ansi lines)))

(defn count-iterations [text]
  (count (re-seq #"Iteration \d+" text)))

(defn count-refinements [text]
  (count (re-seq #"(?i)refin|evaluat" text)))

(defn extract-answer
  "Extract content between Answer box borders."
  [text]
  (let [lines (str/split-lines text)
        in-answer (volatile! false)
        result (volatile! [])]
    (doseq [l lines]
      (cond
        (and (not @in-answer) (str/includes? l "Answer"))
        (vreset! in-answer true)

        (and @in-answer (re-find #"^[└┘]" (str/trim l)))
        (vreset! in-answer false)

        @in-answer
        (vswap! result conj (str/trim l))))
    (str/join "\n" (remove str/blank? @result))))

(defn send-config! [c key value]
  (cli/clear-output! c)
  (cli/send-line! c (str "/config " key " " value))
  (cli/wait-for-idle c :idle-ms 3000 :timeout-ms 15000))

(defn refinement-in-progress?
  "Check if TUI output indicates a refinement loop is still running.
   Returns true when the last evaluation verdict is INCOMPLETE or HALLUCINATED
   without a subsequent finalized answer — meaning the agent is re-running RLM
   with feedback but stdout went quiet during the LLM cold-start gap."
  [c]
  (let [lines (cli/get-all-output c)
        text  (clean-text lines)
        ;; Count answer boxes (bordered with └──) vs INCOMPLETE/HALLUCINATED verdicts
        answer-count (count (re-seq #"(?m)^└──" text))
        incomplete-count (count (re-seq #"INCOMPLETE|HALLUCINATED" text))]
    ;; If there are more verdicts than final answer boxes, a refinement is pending
    (> incomplete-count (max 0 (dec answer-count)))))

(defn ask-and-wait-with-refinements
  "Like ask-and-wait but handles refinement loops.
   After each idle timeout, checks if a refinement is in progress (INCOMPLETE
   verdict without subsequent answer). If so, waits again for the next idle
   period. Repeats up to max-extra-waits times."
  [c question & {:keys [idle-ms idle-timeout-ms max-extra-waits]
                 :or {idle-ms 12000 idle-timeout-ms 300000 max-extra-waits 0}}]
  (cli/clear-output! c)
  (cli/send-line! c question)
  (let [echo-word (first (str/split question #"\s+" 2))]
    (cli/wait-for c (re-pattern (str "(?i)" (java.util.regex.Pattern/quote echo-word)))
                  :timeout-ms 15000))
  ;; Initial wait
  (cli/wait-for-idle c :idle-ms idle-ms :timeout-ms idle-timeout-ms)
  ;; Re-wait if refinement is in progress (INCOMPLETE but no new answer yet)
  (loop [waits-left max-extra-waits]
    (when (and (pos? waits-left)
              (cli/alive? c)
              (refinement-in-progress? c))
      (println (str "    Refinement in progress, waiting for next round ("
                    waits-left " waits remaining)..."))
      (cli/wait-for-idle c :idle-ms idle-ms :timeout-ms idle-timeout-ms)
      (recur (dec waits-left))))
  (cli/get-all-output c))

;; --- Run one test case ------------------------------------------------------
(defn run-test-case
  "Spawn a fresh TUI, configure it, ask the question, collect results."
  [{:keys [label enable-finalize-answer max-refinements]}]
  (println (str "\n── " label " ──"))
  (let [c (cli/start!
           :command ["bash" "-c"
                     (str "cd " root " && set -a && source .env"
                          " && bb tui run -a " agent-type " -p " provider " -m " model " -i")]
           :working-dir root)]
    (try
      ;; Wait for startup
      (println "  Starting TUI...")
      (Thread/sleep 30000)
      (cli/wait-for-idle c :idle-ms 5000 :timeout-ms 30000)

      ;; Configure
      (println (str "  Setting enable-finalize-answer=" enable-finalize-answer
                    ", max-refinements=" max-refinements))
      (send-config! c "enable-finalize-answer" enable-finalize-answer)
      (send-config! c "max-refinements" max-refinements)

      ;; Ask question — use refinement-aware wait for cases with max-refinements > 0
      (println "  Asking question...")
      (let [start-ms (System/currentTimeMillis)
            ;; Each refinement round needs: RLM re-run + EvaluateAnswer + FinalizeAnswer
            ;; Each can trigger an idle gap, so allow max-refinements * 2 extra waits
            extra-waits (* max-refinements 2)
            lines    (ask-and-wait-with-refinements c test-question
                       :idle-ms 12000 :idle-timeout-ms 300000
                       :max-extra-waits extra-waits)
            elapsed  (- (System/currentTimeMillis) start-ms)
            text     (clean-text lines)
            answer   (extract-answer text)
            result   {:label       label
                      :elapsed-ms  elapsed
                      :iterations  (count-iterations text)
                      :has-cost    (boolean (re-find #"\$[\d,]+\.\d{2}" text))
                      :has-pass    (boolean (re-find #"PASS" text))
                      :has-error   (boolean (re-find #"(?i)unsupported escape|could not resolve" text))
                      :hallucinated (boolean (re-find #"HALLUCINATED" text))
                      :answer-len  (count answer)
                      :answer-preview (subs answer 0 (min 200 (count answer)))}]

        ;; Save full output
        (let [filename (str "/tmp/tui-compare-"
                            (str/replace (str/lower-case label) #"[^a-z0-9]+" "-")
                            ".txt")]
          (spit filename text)
          (println (str "  Output saved to " filename)))

        ;; Shutdown
        (cli/shutdown! c :timeout-ms 15000)
        result)

      (catch Exception e
        (println (str "  FATAL: " (ex-message e)))
        (doseq [line (take-last 10 (cli/get-all-output c))]
          (println "    " (strip-ansi line)))
        {:label label :error (ex-message e)})
      (finally
        (.close c)))))

;; --- Run all cases and compare ----------------------------------------------
(println "\n========================================")
(println " Finalize/Refinement Comparison Test")
(println (str " Provider: " provider ":" model " | Agent: " agent-type))
(println (str " Question: " test-question))
(println "========================================")

(def comparison-results (mapv run-test-case test-cases))

;; --- Summary table ----------------------------------------------------------
(println "\n========================================")
(println " Comparison Results")
(println "========================================\n")
(println (format "%-42s %8s %6s %5s %5s %5s %5s %5s"
                 "Case" "Time(s)" "Iters" "Cost?" "Pass?" "Err?" "Hall?" "Len"))
(println (apply str (repeat 90 "-")))
(doseq [r comparison-results]
  (if (:error r)
    (println (format "%-42s ERROR: %s" (:label r) (:error r)))
    (println (format "%-42s %7.1f %6d %5s %5s %5s %5s %5d"
                     (:label r)
                     (/ (:elapsed-ms r) 1000.0)
                     (:iterations r)
                     (if (:has-cost r) "Y" "N")
                     (if (:has-pass r) "Y" "N")
                     (if (:has-error r) "Y" "N")
                     (if (:hallucinated r) "Y" "N")
                     (:answer-len r)))))

(println "\n── Answer Previews ──")
(doseq [r comparison-results]
  (when-not (:error r)
    (println (str "\n[" (:label r) "]"))
    (println (:answer-preview r))
    (when (> (:answer-len r) 200) (println "..."))))

(println "\n Output files: /tmp/tui-compare-case-*.txt")
```

Run it:

```bash
clj-nrepl-eval -p <port> '(load-file "/tmp/tui-comparison-test.clj")' --timeout 1800000
```

**Refinement-aware waiting**: When `max-refinements > 0`, the evaluator may return INCOMPLETE or HALLUCINATED, triggering a refinement re-run. During the gap between the verdict and the next RLM iteration starting (LLM cold-start), stdout goes quiet for 15-30s — enough to trigger a naive `wait-for-idle`. The `ask-and-wait-with-refinements` helper detects this by checking whether the output contains more INCOMPLETE/HALLUCINATED verdicts than finalized answer boxes. If so, it re-waits automatically.

**Expected differences**:

| Metric | Case 1 (raw) | Case 2 (finalize) | Case 3 (finalize + refine) |
|--------|-------------|-------------------|---------------------------|
| Time | Fastest | +1 LLM call | +up to 5 LLM calls |
| Answer quality | Draft only | Polished synthesis | Polished + quality-checked |
| Cost | Lowest | Moderate | Highest |
| Iterations | Baseline | Same RLM iters | More if refinements trigger |

## 10. Self-Correction Testing Workflow

An iterative pattern for validating agent behavior: ask a question, verify execution via logs, inspect state with meta-commands, then switch models and re-test.

### 10.1 Helpers: Log Inspection

```clojure
(require '[clojure.edn :as edn])

(defn read-log-events
  "Read all mulog events from the TUI log file.
   Events are pretty-printed EDN maps separated by blank lines."
  []
  (let [f (java.io.File. "/tmp/agent-tui-app.log")]
    (when (.exists f)
      (->> (clojure.string/split (slurp f) #"\n\n")
           (remove clojure.string/blank?)
           (keep #(try (edn/read-string %) (catch Exception _ nil)))
           vec))))

(defn last-log-events
  "Return the last N mulog events from the log file."
  [n]
  (let [events (read-log-events)]
    (take-last n events)))

(defn summarize-llm-calls
  "Extract LLM call summary from log events: model, tokens, cost, duration."
  [events]
  (let [llm-events (filter #(#{:ai.brainyard.clj-llm.core.claude-code/claude-code-stream-call-result
                                :ai.brainyard.clj-llm.core.llm/openai-api-call
                                :ai.brainyard.clj-llm.core.llm/anthropic-api-call}
                              (:mulog/event-name %))
                           events)]
    (mapv (fn [e]
            {:event  (name (:mulog/event-name e))
             :model  (or (get-in e [:response :model]) (:model e) "?")
             :input  (or (get-in e [:response :usage :input_tokens])
                         (:input-tokens e) (:prompt-tokens e) 0)
             :output (or (get-in e [:response :usage :output_tokens])
                         (:output-tokens e) (:completion-tokens e) 0)
             :cost   (or (get-in e [:response :ai.brainyard.clj-llm.core.claude-code/cli-cost])
                         (:cost e) 0.0)
             :ms     (when-let [d (:mulog/duration e)]
                       (/ d 1000000))})  ;; ns → ms
          llm-events)))

(defn find-verdicts
  "Find evaluation verdicts (COMPLETE/INCOMPLETE/HALLUCINATED) in TUI output text."
  [text]
  (re-seq #"(COMPLETE|INCOMPLETE|HALLUCINATED)\s*[—–-]\s*([^\n]*)" text))
```

### 10.2 Helpers: Meta-Command Execution

```clojure
(defn run-meta-cmd
  "Send a TUI meta-command, wait for response, return cleaned output."
  [c cmd & {:keys [idle-ms timeout-ms] :or {idle-ms 3000 timeout-ms 15000}}]
  (cli/clear-output! c)
  (cli/send-line! c cmd)
  (cli/wait-for-idle c :idle-ms idle-ms :timeout-ms timeout-ms)
  (clean-text (cli/get-all-output c)))

(defn switch-model
  "Switch model via /model command. Returns confirmation output."
  [c model-name]
  (run-meta-cmd c (str "/model " model-name)))

(defn show-config
  "Show all config or a single key."
  ([c]       (run-meta-cmd c "/config"))
  ([c key]   (run-meta-cmd c (str "/config " key))))

(defn set-config
  "Set a config key to a value."
  [c key value]
  (run-meta-cmd c (str "/config " key " " value)))
```

### 10.3 Ask → Read Response → Verify with Logs

The core self-correction pattern: ask a question, capture the TUI output, then cross-reference with the last few log events to confirm what actually happened.

```clojure
;; Step 1: Clear the log file before testing
(let [f (java.io.File. "/tmp/agent-tui-app.log")]
  (when (.exists f) (.delete f)))

;; Step 2: Ask question and capture output
(let [lines (ask-and-wait c "list tools")
      text  (clean-text lines)]
  (spit "/tmp/self-test-output.txt" text)
  (println "=== TUI Output ===")
  (println "Iterations:" (count-iterations text))
  (println "Has PASS?" (has-pass? text))
  (println "SCI errors?" (has-error? text))

  ;; Step 3: Verify with last log events
  (println "\n=== Last 5 Log Events ===")
  (doseq [e (last-log-events 5)]
    (println " " (:mulog/event-name e)))

  ;; Step 4: Check LLM call details
  (println "\n=== LLM Calls ===")
  (let [calls (summarize-llm-calls (read-log-events))]
    (doseq [{:keys [model input output cost ms]} calls]
      (println (format "  model=%-20s in=%-6d out=%-6d cost=$%.4f %s"
                       model input output cost
                       (if ms (format "%.0fms" (double ms)) "")))))

  ;; Step 5: Check for verdicts in output
  (when-let [verdicts (seq (find-verdicts text))]
    (println "\n=== Verdicts ===")
    (doseq [[_ verdict detail] verdicts]
      (println (str "  " verdict ": " detail)))))
```

### 10.4 Inspect with Meta-Commands

Use meta-commands mid-session to inspect agent state without starting a new session.

```clojure
;; Check current agent state
(println "=== Status ===")
(println (run-meta-cmd c "/status"))

;; Check token usage, cost, and per-call input token breakdown
;; Shows: aggregate summary + per-call table (Turn, Iter, System/Sig/UserMsg/SysCtx tokens)
;; + system prompt parts breakdown + user message growth analysis
(println "=== Usage ===")
(println (run-meta-cmd c "/usage"))

;; Check per-call latency breakdown
(println "=== Perf ===")
(println (run-meta-cmd c "/perf"))

;; Check current TODO list
(println "=== TODO ===")
(println (run-meta-cmd c "/todo"))

;; Check BT trace / thinking (last 5 entries)
(println "=== Thinking ===")
(println (run-meta-cmd c "/agent trace 5"))

;; View all runtime config
(println "=== Config ===")
(println (run-meta-cmd c "/config"))

;; View specific config value
(println "enable-finalize-answer:" (show-config c "enable-finalize-answer"))

;; List sandbox bindings (RLM agent only)
(println "=== Sandbox Bindings ===")
(println (run-meta-cmd c "/sandbox"))
```

### 10.5 Model Switching and Re-Testing

Hot-swap the model mid-session with `/model`, then re-ask the same question to compare behavior.

```clojure
;; Check current model
(println "Current model:" (run-meta-cmd c "/model"))

;; Ask with current model
(let [f (java.io.File. "/tmp/agent-tui-app.log")]
  (when (.exists f) (.delete f)))

(let [lines-a (ask-and-wait c "what is 2 + 2?")
      text-a  (clean-text lines-a)
      calls-a (summarize-llm-calls (read-log-events))]

  ;; Switch model
  (println (switch-model c "claude-code:sonnet"))

  ;; Clear log, re-ask same question
  (let [f (java.io.File. "/tmp/agent-tui-app.log")]
    (when (.exists f) (.delete f)))

  (let [lines-b (ask-and-wait c "what is 2 + 2?")
        text-b  (clean-text lines-b)
        calls-b (summarize-llm-calls (read-log-events))]

    ;; Compare
    (println "\n=== Model Comparison ===")
    (println (format "%-20s %-12s %-12s" "" "Model A" "Model B"))
    (println (format "%-20s %-12s %-12s" "Model"
                     (:model (first calls-a) "?")
                     (:model (first calls-b) "?")))
    (println (format "%-20s %-12d %-12d" "Iterations"
                     (count-iterations text-a) (count-iterations text-b)))
    (println (format "%-20s %-12s %-12s" "PASS?"
                     (has-pass? text-a) (has-pass? text-b)))
    (println (format "%-20s $%-11.4f $%-11.4f" "Total cost"
                     (reduce + (map :cost calls-a))
                     (reduce + (map :cost calls-b))))))
```

**Available models for `/model`**:

| Provider | Models |
|----------|--------|
| `claude-code` | `haiku` (default), `sonnet`, `opus` |
| `anthropic` | `claude-sonnet-4-6`, `claude-haiku-4-5-20251001` |
| `openai` | `gpt-4.1-mini`, `gpt-4.1` |
| `ollama` | `glm-5:cloud`, any local model |

### 10.6 Config Tuning and Re-Testing

Adjust runtime config and re-test to observe the effect.

```clojure
;; Disable finalize-answer for faster raw output
(set-config c "enable-finalize-answer" false)
(println "Config set:" (show-config c "enable-finalize-answer"))

;; Ask, capture, verify
(let [lines (ask-and-wait c "list tools")
      text  (clean-text lines)]
  (println "Iterations:" (count-iterations text))
  (println "PASS?" (has-pass? text)))

;; Re-enable and add refinements
(set-config c "enable-finalize-answer" true)
(set-config c "max-refinements" 1)
(println "Config:" (show-config c "enable-finalize-answer"))
(println "Config:" (show-config c "max-refinements"))

;; Re-ask with refinement
(let [lines (ask-and-wait-with-refinements c "list tools"
              :idle-ms 10000 :idle-timeout-ms 180000 :max-extra-waits 2)
      text  (clean-text lines)]
  (println "Iterations:" (count-iterations text))
  (println "PASS?" (has-pass? text))
  (println "Verdicts:" (find-verdicts text)))
```

## 11. Iteration Compaction & Error Recovery

The structured agent has several mechanisms to prevent wasted iterations and context bloat. These are important to understand when debugging test failures.

### 11.1 Repeated Code Detection

When the LLM generates identical code in consecutive iterations (e.g., using wrong API field names that return nulls), the system injects a `REPEATED CODE WARNING` into the error field, forcing the LLM to change strategy.

**What to look for in output:**
```
REPEATED CODE WARNING: You generated the same code as the previous iteration.
```

### 11.2 Repeated Iteration Collapsing

Consecutive iterations with identical code are collapsed into a single record annotated with the repeat count. This prevents context bloat from N copies of the same null-result output.

**What to look for in output:**
```
COLLAPSED: Iterations repeated 4 times with identical code and same result.
```

### 11.3 Dropped Iteration Summarization

When iterations exceed the 10-iteration DSPy window, dropped iterations are summarized into a pseudo-iteration-0 record instead of being silently discarded. The LLM retains awareness of what was tried earlier.

**What to look for in output:**
```
=== Summary of earlier iterations (dropped from window) ===
Iter 1: (search "heka finops") → {:brainyard-skills [{:name "heka-finops"...
Iter 2: (pprint (read-skill "heka-finops")) → {:name "heka-finops", :title...
```

### 11.4 LLM Format Error Recovery

When the LLM returns plain text instead of required JSON (DSPy structured mode), the detailed error is preserved through the eval pipeline instead of being replaced with a generic "No code generated" message. The error explicitly instructs the LLM to fix its output format.

**What to look for in output:**
```
FORMAT ERROR: JSON parse failed: ... You MUST respond with valid JSON matching the output schema.
```

### 11.5 `/usage` Token Breakdown

After any multi-iteration question, `/usage` now shows per-call input token breakdown:

```
Token Breakdown (5 calls)

  #  Turn  Iter   System       Sig   UserMsg    SysCtx   TotalIn      Out
  1     1     1    9,853     1,673     3,937         0    15,463      863
  2     1     2    9,853     1,673     5,210         0    16,736      512
  ...

  System Prompt Parts (latest):
    usage-guides              5,517 tok (56%)
    sandbox-environment       1,636 tok (17%)
    ...

  Growth: User message 3,937 → 8,422 tokens (+114%) over 5 calls
```

Use this to diagnose context bloat — if UserMsg grows rapidly across iterations, the iteration history is accumulating too much data.

### 11.6 `parse-json` Returns String Keys

As of 2026-04-06, `parse-json` in the sandbox defaults to **string keys** (not keyword keys). This matches the raw JSON field names the LLM sees from `bash` output, eliminating silent nil returns when the LLM writes `(get data "totalCost")` instead of `(:totalCost data)`.

```clojure
(parse-json "{\"a\":1}")                ;; => {"a" 1}  (string keys)
(parse-json "{\"a\":1}" :key-fn keyword) ;; => {:a 1}   (opt-in keyword keys)
```

### 10.7 Full Self-Correction Loop Script

Complete script combining all patterns. Save to `/tmp/self-correction-test.clj`:

```clojure
(require '[ai.brainyard.agent.stdio.client :as cli]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

;; --- Config -----------------------------------------------------------------
(def root "/path/to/brainyard")  ;; <-- EDIT THIS
(def provider "claude-code")
(def model "opus")
(def agent-type "coact-agent")
(def test-question "with aws-cli skill, get me the recent aws cloud cost over 3 months")

;; --- Shared helpers (from Section 4.1) --------------------------------------
(defn strip-ansi [s]
  (str/replace s #"\033\[[0-9;]*[A-Za-z]|\033\[\?[0-9]*[hl]" ""))

(defn clean-text [lines]
  (str/join "\n" (map strip-ansi lines)))

(defn get-clean-output [c]
  (clean-text (cli/get-all-output c)))

(defn count-iterations [text]
  (count (re-seq #"Iteration \d+" text)))

(defn has-pass? [text]
  (boolean (re-find #"PASS" text)))

(defn has-error? [text]
  (boolean (re-find #"(?i)unsupported escape|could not resolve" text)))

(defn ask-and-wait [c question & {:keys [idle-ms idle-timeout-ms]
                                   :or {idle-ms 10000 idle-timeout-ms 180000}}]
  (cli/clear-output! c)
  (cli/send-line! c question)
  (let [echo-word (first (str/split question #"\s+" 2))]
    (cli/wait-for c (re-pattern (str "(?i)" (java.util.regex.Pattern/quote echo-word)))
                  :timeout-ms 15000))
  (cli/wait-for-idle c :idle-ms idle-ms :timeout-ms idle-timeout-ms)
  (cli/get-all-output c))

;; --- Self-correction helpers ------------------------------------------------
(defn read-log-events []
  (let [f (java.io.File. "/tmp/agent-tui-app.log")]
    (when (.exists f)
      (->> (str/split (slurp f) #"\n\n")
           (remove str/blank?)
           (keep #(try (edn/read-string %) (catch Exception _ nil)))
           vec))))

(defn last-log-events [n]
  (take-last n (read-log-events)))

(defn summarize-llm-calls [events]
  (let [llm-events (filter #(#{:ai.brainyard.clj-llm.core.claude-code/claude-code-stream-call-result
                                :ai.brainyard.clj-llm.core.llm/openai-api-call
                                :ai.brainyard.clj-llm.core.llm/anthropic-api-call}
                              (:mulog/event-name %)) events)]
    (mapv (fn [e]
            {:model  (or (get-in e [:response :model]) (:model e) "?")
             :input  (or (get-in e [:response :usage :input_tokens])
                         (:input-tokens e) (:prompt-tokens e) 0)
             :output (or (get-in e [:response :usage :output_tokens])
                         (:output-tokens e) (:completion-tokens e) 0)
             :cost   (or (get-in e [:response :ai.brainyard.clj-llm.core.claude-code/cli-cost])
                         (:cost e) 0.0)})
          llm-events)))

(defn find-verdicts [text]
  (re-seq #"(COMPLETE|INCOMPLETE|HALLUCINATED)\s*[—–-]\s*([^\n]*)" text))

(defn run-meta-cmd [c cmd & {:keys [idle-ms timeout-ms] :or {idle-ms 3000 timeout-ms 15000}}]
  (cli/clear-output! c)
  (cli/send-line! c cmd)
  (cli/wait-for-idle c :idle-ms idle-ms :timeout-ms timeout-ms)
  (clean-text (cli/get-all-output c)))

(defn switch-model [c model-name]
  (run-meta-cmd c (str "/model " model-name)))

(defn clear-log! []
  (let [f (java.io.File. "/tmp/agent-tui-app.log")]
    (when (.exists f) (.delete f))))

;; --- Run --------------------------------------------------------------------
(println "\n========================================")
(println " Self-Correction Test")
(println (str " Agent: " agent-type " | Provider: " provider ":" model))
(println "========================================\n")

(def c (cli/start!
        :command ["bash" "-c"
                  (str "cd " root " && set -a && source .env"
                       " && bb tui run -a " agent-type " -p " provider " -m " model " -i")]
        :working-dir root))

(try
  ;; 1. Startup
  (println "[1/5] Starting TUI...")
  (Thread/sleep 30000)
  (cli/wait-for-idle c :idle-ms 5000 :timeout-ms 30000)
  (println "  TUI ready.")

  ;; 2. Ask question, verify with logs
  (println "\n[2/5] Ask + Log Verification")
  (clear-log!)
  (let [text (clean-text (ask-and-wait c test-question
               :idle-ms 12000 :idle-timeout-ms 300000))]
    (spit "/tmp/self-test-q1.txt" text)
    (println "  Iterations:" (count-iterations text))
    (println "  PASS?" (has-pass? text))
    (println "  Errors?" (has-error? text))
    (when-let [vs (seq (find-verdicts text))]
      (doseq [[_ v d] vs] (println (str "  Verdict: " v " — " d))))

    (println "\n  --- Last 3 Log Events ---")
    (doseq [e (last-log-events 3)]
      (println "   " (:mulog/event-name e)))

    (println "\n  --- LLM Calls ---")
    (doseq [{:keys [model input output cost]} (summarize-llm-calls (read-log-events))]
      (println (format "    model=%-20s in=%-6d out=%-6d cost=$%.4f"
                       model input output cost))))

  ;; 3. Inspect with meta-commands
  (println "\n[3/5] Meta-Command Inspection")
  (println "  Status:" (str/replace (run-meta-cmd c "/status") #"\n" " | "))
  (println "  Usage:" (str/replace (run-meta-cmd c "/usage") #"\n" " | "))
  (println "  Config (finalize):" (run-meta-cmd c "/config enable-finalize-answer"))
  (println "  Config (refinements):" (run-meta-cmd c "/config max-refinements"))

  ;; 4. Switch model and re-test
  (println "\n[4/5] Model Switch + Re-Test")
  (println "  Switching to claude-code:sonnet...")
  (println "  " (switch-model c "claude-code:sonnet"))
  (println "  Current model:" (run-meta-cmd c "/model"))

  (clear-log!)
  (let [text (clean-text (ask-and-wait c test-question
               :idle-ms 12000 :idle-timeout-ms 300000))]
    (spit "/tmp/self-test-q2.txt" text)
    (println "  Iterations:" (count-iterations text))
    (println "  PASS?" (has-pass? text))

    (println "\n  --- LLM Calls (after switch) ---")
    (doseq [{:keys [model input output cost]} (summarize-llm-calls (read-log-events))]
      (println (format "    model=%-20s in=%-6d out=%-6d cost=$%.4f"
                       model input output cost))))

  ;; 5. Shutdown
  (println "\n[5/5] Shutdown")
  (let [code (cli/shutdown! c :timeout-ms 15000)]
    (println "  Exit code:" code))

  (catch Exception e
    (println "\nFATAL:" (ex-message e))
    (doseq [line (take-last 10 (cli/get-all-output c))]
      (println "  " (strip-ansi line))))
  (finally
    (.close c)))

(println "\n========================================")
(println " Output saved to /tmp/self-test-q1.txt, /tmp/self-test-q2.txt")
(println "========================================")
```

Run it:

```bash
clj-nrepl-eval -p <port> '(load-file "/tmp/self-correction-test.clj")' --timeout 900000
```

## 11. Idle Timeout Tuning

| Question type | Recommended `idle-ms` | Recommended `idle-timeout-ms` | Notes |
|---------------|----------------------|------------------------------|-------|
| Simple (list tools, status) | 8000-10000 | 180000 (3 min) | |
| Tool-calling (AWS, MCP) | 10000-12000 | 300000 (5 min) | |
| Multi-step reasoning | 12000-15000 | 300000 (5 min) | |
| With refinements (`max-refinements > 0`) | 12000 | 300000 (5 min) | Use `ask-and-wait-with-refinements` — re-waits on INCOMPLETE/HALLUCINATED |
| :status, :help | 3000 | 15000 | |

`idle-ms` is how long stdout must be quiet before declaring "done". Higher values avoid false triggers from slow LLM streaming or tool execution pauses.

**Refinement gap caveat**: Between an INCOMPLETE/HALLUCINATED evaluation verdict and the next RLM re-run, stdout can be quiet for 15-30s (LLM cold-start). A plain `wait-for-idle` with `idle-ms 12000` will falsely declare completion mid-refinement. Use `ask-and-wait-with-refinements` (Section 9) which detects this and re-waits automatically.

## 12. Inspecting the File Log

The TUI automatically writes all mulog events as pretty-printed EDN to `/tmp/agent-tui-app.log`. This is useful for post-hoc analysis of LLM calls, token usage, costs, and timing.

### 12.1 Read the Log After a Test

```clojure
;; Quick check: file size and line count
(let [f (java.io.File. "/tmp/agent-tui-app.log")]
  (when (.exists f)
    (println "Size:" (.length f) "bytes")
    (println "Lines:" (count (clojure.string/split-lines (slurp f))))))

;; Read all events as EDN (each event is a standalone map separated by blank lines)
(let [content (slurp "/tmp/agent-tui-app.log")
      ;; Split on double-newline boundaries between pprint'd maps
      events (->> (clojure.string/split content #"\n\n")
                  (remove clojure.string/blank?)
                  (mapv #(try (read-string %) (catch Exception _ nil)))
                  (remove nil?))]
  (println "Total events:" (count events))
  (doseq [e events]
    (println " " (:mulog/event-name e))))
```

### 12.2 Filter by Event Type

```clojure
;; Extract LLM call events with cost/token info
(let [content (slurp "/tmp/agent-tui-app.log")
      events (->> (clojure.string/split content #"\n\n")
                  (remove clojure.string/blank?)
                  (keep #(try (read-string %) (catch Exception _ nil))))]
  (doseq [e (filter #(= (:mulog/event-name %)
                         :ai.brainyard.clj-llm.core.claude-code/claude-code-stream-call-result) events)]
    (println (format "model=%-10s tokens=%-6d cost=$%.4f"
                     (:model (:response e))
                     (get-in e [:response :usage :output_tokens] 0)
                     (get-in e [:response :ai.brainyard.clj-llm.core.claude-code/cli-cost] 0.0)))))
```

### 12.3 Clear the Log Before a Test

```clojure
;; Delete log file to start fresh
(let [f (java.io.File. "/tmp/agent-tui-app.log")]
  (when (.exists f) (.delete f)))
```

### 12.4 Integration with Test Script

Add log inspection after shutdown in the complete test script (Section 8):

```clojure
;; After shutdown, inspect file log
(println "\n[5/5] File log inspection")
(let [log-path "/tmp/agent-tui-app.log"
      f (java.io.File. log-path)]
  (check "Log file exists" (.exists f))
  (check "Log file non-empty" (pos? (.length f)))
  (when (.exists f)
    (let [content (slurp log-path)
          events (->> (clojure.string/split content #"\n\n")
                      (remove clojure.string/blank?)
                      (keep #(try (read-string %) (catch Exception _ nil))))]
      (check "Has mulog events" (pos? (count events)))
      (println "  Events:" (count events))
      (doseq [e events]
        (println "   " (:mulog/event-name e))))))
```

**Log location**: `/tmp/agent-tui-app.log`
**Format**: Pretty-printed EDN maps, one per event, separated by blank lines. Append-mode (accumulates across sessions).

## 13. Cleanup

```clojure
;; Graceful — sends :quit, waits for exit
(cli/shutdown! c :timeout-ms 15000)
(.close c)

;; Force — if shutdown hangs
(try (.close c) (catch Exception _))
```

**Warning**: `bb tui` calls `System.exit(0)` on exit, which kills the child JVM but NOT your parent REPL.

## 14. Troubleshooting

### No output after start

- **Cause**: `bb tui` must run from the brainyard project root (where `bb.edn` lives)
- **Fix**: Ensure `:working-dir` points to the root and the `cd` in the command matches

### wait-for times out on startup

- **Cause**: JVM startup takes 15-30s; `--inline` mode may not print the exact banner text
- **Fix**: Use `Thread/sleep 30000` followed by `wait-for-idle` instead of `wait-for` with a regex

### "Unsupported escape character" in sandbox

- **Cause**: LLM wrote `\` line continuation in a `bash` string
- **Fix**: Ensure the system prompt includes the SCI Sandbox String Restrictions section (added in sandbox.clj and rlm_agent.clj)

### "Could not resolve symbol: json/read-str"

- **Cause**: LLM used `json/read-str` without requiring the namespace
- **Fix**: Ensure `parse-json` and `to-json` are bound in sandbox.clj user-bindings (no require needed)

### Stale agent after code reload

- **Cause**: `!agent-registry` caches old agent instances
- **Fix**: Clear registry and restart TUI:
  ```clojure
  (reset! @#'ai.brainyard.agent.core.agent/!agent-registry {})
  ;; Then restart the CliClient with a new bb tui process
  ```

### Output truncated during refinement loop

- **Cause**: `wait-for-idle` triggers during the gap between an INCOMPLETE/HALLUCINATED evaluation verdict and the next RLM re-run. The LLM cold-start takes 15-30s — longer than `idle-ms` — so the CliClient declares "done" mid-refinement.
- **Symptoms**: Output ends with `INCOMPLETE — ...` but no subsequent iterations or finalized answer. The log file shows the refinement continued after the CliClient stopped reading.
- **Fix**: Use `ask-and-wait-with-refinements` instead of `ask-and-wait` when `max-refinements > 0`. It checks output for unresolved INCOMPLETE/HALLUCINATED verdicts and re-waits automatically. Set `:max-extra-waits` to `(* max-refinements 2)` to cover both the re-run and re-evaluation gaps per round.

### Output contains ANSI escape codes

- **Cause**: TUI always emits ANSI colors regardless of NO_COLOR
- **Fix**: Use `strip-ansi` on all output before assertions

## 15. CliClient API Quick Reference

| Function | Description |
|----------|-------------|
| `(cli/start! :command [...] :working-dir "...")` | Spawn process, return `CliClient` |
| `(cli/send-line! c text)` | Write text + newline to stdin |
| `(cli/wait-for c pattern :timeout-ms N)` | Block until output matches string/regex |
| `(cli/wait-for-idle c :idle-ms N :timeout-ms M)` | Block until stdout is quiet for N ms |
| `(cli/get-output c)` | New lines since last read (advances cursor) |
| `(cli/get-all-output c)` | All lines (does NOT advance cursor) |
| `(cli/clear-output! c)` | Skip all buffered output (advance cursor to end) |
| `(cli/shutdown! c :timeout-ms N)` | Send `:quit`, wait for exit, return exit code |
| `(cli/alive? c)` | Check if process is running |
| `(.close c)` | Force-destroy process |
