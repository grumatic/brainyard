;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.config
  "Configuration + directory resolution for agents.

   Three concerns live here:
   1. `config-schema` — single registry of all configurable items, each
      declaring its type and default value.
   2. Unified config API — `get-config` / `set-config!` resolve every read
      and write through one precedence chain (lowest → highest):
        schema default (`:default` or lazy `:default-fn`)
          < !global-config (.brainyard/config.edn merged over static defaults)
          < session-config (`(:config @!session)`)
          < per-agent override (`(:config @st-memory-init)`)
          < environment variable (a key's `:env-fn`; HIGHEST — a set env var
            wins over every persisted/in-memory layer)
      Each resolution is mulog-tracked once per (key, source) via
      `::config-resolved` so the winning layer is observable.
      Per-agent overrides are seeded from defagent `:config-extra` +
      caller options; schema-shape keys (in `config-keys`) flow into this
      slot automatically at `setup-agent` time. `set-config!` always
      persists to `.brainyard/config.edn`; with an agent arg it also
      writes the per-agent override.
   3. Directory resolution — user/project/working dirs, `.brainyard/`
      config directories, and helpers to read .brainyard/ files
      (BRAINYARD.md, config.edn, SKILL.md, etc.)."
  (:require [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file FileSystems Files LinkOption Path]
           [java.nio.file.attribute BasicFileAttributes]))

;; ============================================================================
;; Config Schema
;; ============================================================================

(declare resolve-dirs user-config-dir default-allowed-dirs)

(def env-unset
  "Sentinel an `:env-fn` returns when its environment variable is absent — so a
   real env value of `false` / `0` / \"\" is distinguishable from \"not set\"
   and resolution falls through to the next layer. Compared in `get-config`."
  ::env-unset)

(def config-schema
  "Config key → {:type <coerce-value type-str> :default <value>}

   Each entry declares EITHER `:default` (a static value) OR `:default-fn`
   (a 0-arity callable invoked lazily by `get-config` as the final fallback).
   Use `:default-fn` for runtime-resolved values that depend on env vars,
   system properties, or other components — `(clj-llm/get-default-lm)`,
   `(System/getenv ...)`, etc."
  {:max-output-tokens       {:type "integer" :default 0
                             :doc "Cap on LLM response tokens per call (0 = provider default)."}
   :show-llm-streaming      {:type "boolean" :default false
                             :doc "Stream the LLM's tokens live in the TUI as they arrive."}
   :enable-context-budget      {:type "boolean" :default true
                                :doc "Enforce the token budget on assembled prompt context (coact); master switch for the per-iteration/turn-init budget reducer and cross-turn compaction."}
   :context-budget-safety-ratio {:type "number" :default 0.10
                                 :doc "Fraction of the context window held back as headroom when budgeting prompt context."}
   :reference-artifact-paths   {:type "vector"  :default ["CLAUDE.md" "AGENTS.md"]
                                :doc "Reference files auto-seeded each turn into the `## Live Artifacts` section (resolved against project-dir then working-dir; missing files skipped; seeded entries are pinned)."}
   :live-artifact-max-chars    {:type "integer" :default 4000
                                :doc "Per-artifact truncation cap (chars) for the Live Artifacts renderer when a descriptor declares no :max-chars."}
   :enable-console-activity       {:type "boolean" :default true
                                   :doc "Record each TUI colon-command (`:tool …`) as an inline console live-artifact so the next turn sees what the user just inspected."}
   :console-activity-max-entries  {:type "integer" :default 10
                                   :doc "Max recent console-activity entries kept (oldest evicted first)."}
   :console-activity-result-chars {:type "integer" :default 200
                                   :doc "Per-entry char cap for a recorded console-activity result."}
   :inline-usage-guides        {:type "vector"  :default [:artifacts]
                                :doc "Usage-guide topics permanently inlined into tool-context every turn; all other guide families surface just-in-time on first use (topics: see agent.core.usage/list-usage-topics)."}
   :enable-project-memory      {:type "boolean" :default true
                                :doc "Seed the `## Project Memory` section each turn from <project>/.brainyard/memory/index.md (file-based, project-local, human-editable; distinct from the L1/L2/L3 SQLite memory)."}
   :project-memory-max-chars   {:type "integer" :default 4000
                                :doc "Truncation cap (chars) for the injected project-memory index.md contents."}
   :oauth-qr?                  {:type "boolean" :default true
                                :doc "Render a terminal QR for the OAuth verification_uri_complete next to the code box (code + plain URL always show regardless)."}
   :oauth-token-store          {:type "string"  :default "auto"
                                :doc "OAuth token-store backend: \"auto\" (keychain if available, else file), \"keychain\", or \"file\". config.edn equivalent of BY_OAUTH_TOKEN_STORE."}
   :oauth-flow                 {:type "string"  :default "auto"
                                :doc "Default OAuth flow for an :auto login: \"auto\" (device when advertised, else paste), \"device\", or \"paste\" (per-server :auth :flow still wins)."}
   :include-function-directory {:type "boolean" :default false
                                :doc "CoAct system-context: include the full sandbox function directory (categories + signatures for all bound callables) instead of a compact category index."}
   :compact-agent-tools        {:type "boolean" :default true
                                :doc "CoAct system-context: render the `### Agent Tools` roster compactly (one line per tool) instead of full per-tool specs. ReAct ignores this (its roster is its menu)."}
   :code-channel?              {:type "boolean" :default true
                                :doc "CoAct: expose the code-blocks channel (SCI/bash/python/javascript sandbox). When false the agent is tool-only — the code-blocks prompt sections are dropped and the BT never routes code (react-agent pins this false). Default true keeps every other agent unchanged."}
   :max-context-tokens         {:type "integer" :default 128000
                                :doc "RLM context-window size (tokens); the after-turn auto-compaction valve fires when estimated context exceeds this."}
   :max-output-chars           {:type "integer" :default 32000
                                :doc "Inline cap (chars, ~8k tokens) for a single LLM-facing result before truncation to a recoverable temp file (head 70% + tail 20%); unified coact/react iteration-field truncation knob."}
   :max-collapsed-lines        {:type "integer" :default 20
                                :doc "TUI display-only: lines shown in a collapsed display block (Enter expands)."}
   :max-expanded-lines         {:type "integer" :default 200
                                :doc "TUI display-only: max lines spliced in when expanding a display block (Ctrl-O opens full content)."}
   :enable-sandbox-persistence {:type "boolean" :default true
                                :doc "Persist the SCI sandbox state (defs/bindings) across turns so --resume can restore it."}
   :enable-trajectory-recording {:type "boolean" :default true
                                 :doc "Append one EDN record per turn (all iterations + final answer) to <project>/.brainyard/sessions/<id>/trajectory.edn; master data switch for session analytics."}
   :enable-budget-monitoring   {:type "boolean" :default false
                                :doc "Emit token-budget monitoring diagnostics."}
   :compaction-target-ratio    {:type "number"  :default 0.2
                                :doc "Cross-turn auto-compaction target: shrink carryover to this fraction × :max-context-tokens (gated by :enable-context-budget)."}
   :max-iterations             {:type "integer" :default 100
                                :doc "RLM/BT loop: max iterations per turn before the loop-guard stops with a best-effort answer."}
   :max-refinements            {:type "integer" :default 0
                                :doc "Max answer-refinement passes after the evaluation check (0 = no refinement)."}
   :eval-lm-config             {:type "string"  :default nil
                                :doc "Model for the answer-evaluation (hallucination/completeness) check that gates refinement. nil/blank → use the agent's :lm-config; a \"provider/model\" (or legacy \"provider:model\") label routes to a dedicated model. See resolve-eval-lm."}
   :sub-lm-config              {:type "string"  :default nil
                                :doc "Model for sub-LLM queries (llm-query / rlm-query in the sandbox). nil → use the agent's :lm-config."}
   :llm-query-max-depth        {:type "integer" :default 1
                                :doc "Max recursion depth for nested llm-query / sub-LLM calls."}
   :auto-background-timeout-ms {:type "integer" :default 180000
                                :doc "Auto-background deadline (ms) for an LLM-emitted code block: if a foreground task exceeds it, await-task detaches into background mode and returns a :pending snapshot; a later iteration harvests the result."}
   :fast-eval-timeout-ms       {:type "integer" :default 30000
                                :doc "Fast-eval threshold (ms): Clojure code runs inline first and is promoted to a tracked task only if it exceeds this (0 = always create a task; not applied to bash)."}
   :enable-iteration-hold       {:type "boolean" :default false
                                 :doc "When true the BT loop blocks up to :hold-max-wait-ms waiting for in-flight tasks; when false (default) pending tasks flow to the next iteration and the LLM polls via task$wait."}
   :hold-max-wait-ms            {:type "integer" :default 300000
                                 :doc "Max time (ms) the iteration hold waits for pending tasks before falling through (only when :enable-iteration-hold is true)."}
   :enable-auto-task-notify     {:type "boolean" :default true
                                 :doc "Runtime async task notification: on an interactive host a finished background task auto-resumes the agent (like task$wakeup), and repeated polls of a still-running task force-park the turn after :auto-park-after-polls."}
   :auto-park-after-polls       {:type "integer" :default 2
                                 :doc "Redundant polls (task$detail/task$wait) of a still-running armed task tolerated before the turn is force-parked (first poll always allowed)."}
   :task-timeout-ms            {:type "integer" :default 120000
                                :doc "Default timeout (ms) for tasks launched via task$run without an explicit :timeout (bash and tool jobs, plus the foreground waiter's deadline)."}
   :task-heartbeat-interval-ms {:type "integer" :default 0
                                :doc "Cadence (ms) of the liveness heartbeat appended to a detached tool/subagent task's output. 0 disables (default); set >0 to restore per-N-seconds heartbeat lines."}
   ;; --- coact-repair-action recovery budgets (one per failure kind) ---
   ;; Each attempt fires :agent.recovery/retrying so the TUI can surface a progress line.
   :max-retries-on-llm-empty-result    {:type "integer" :default 5
                                        :doc "Recovery budget: inline re-runs (exponential backoff) when an LLM call succeeds but returns nothing usable (blank reasoning + no channel — a transient CLI hiccup). 0 disables inline retries."}
   :empty-result-retry-base-ms {:type "integer" :default 1000
                                :doc "Base delay (ms) for exponential backoff between empty-result retries (attempt N waits base × 2^(N-1)); also reused by the transient-failure retry."}
   :max-retries-on-llm-malformed-output {:type "integer" :default 3
                                         :doc "Recovery budget: consecutive re-prompts when an LLM call fails parse/schema validation. Fatal errors (auth/rate-limit/quota/billing/bad-request/misconfig) abort immediately regardless."}
   :max-retries-on-llm-transient        {:type "integer" :default 3
                                         :doc "Recovery budget: inline re-runs (backoff via :empty-result-retry-base-ms) when an LLM call hits a transient server/network error clj-llm couldn't ride out. 0 disables agent-level transient retry."}
   :max-retries-on-llm-no-action       {:type "integer" :default 3
                                        :doc "Recovery budget: consecutive nudges when the model reasons but populates no channel (no tool-calls/code/answer) before the loop-guard stops the turn."}
   ;; On-disk artifact GC (ai.brainyard.agent.gc). Sweeps run async on
   ;; :agent.session/created (1h in-process throttle) and via task$sweep.
   :task-retention-count        {:type "integer" :default 100
                                 :doc "Artifact GC: keep terminal task-N dirs only if among the newest N (intersected with :task-retention-days)."}
   :task-retention-days         {:type "integer" :default 7
                                 :doc "Artifact GC: expire terminal task-N dirs older than this many days (intersected with :task-retention-count)."}
   :coact-scratch-max-age-hours {:type "integer" :default 24
                                 :doc "Artifact GC: drop coact-agent/scratch/ entries older than this many hours."}
   :sandbox-cache-max-files     {:type "integer" :default 200
                                 :doc "Artifact GC: cap on clj-sandbox/{truncation,file-backed}/ entry count (oldest dropped first)."}
   :sandbox-cache-max-bytes     {:type "integer" :default 52428800
                                 :doc "Artifact GC: byte cap on the clj-sandbox cache (oldest dropped first)."}
   :sandbox-cache-max-age-days  {:type "integer" :default 7
                                 :doc "Artifact GC: age cap (days) on the clj-sandbox cache (oldest dropped first)."}
   :analytics-lm-config        {:type "string" :default nil
                                :doc "LM for the optional LLM-enhanced analytics pass (session$analytics :deep true), as a settable \"provider/model\" (or legacy \"provider:model\") label (e.g. \"bedrock/amazon.nova-lite-v1:0\") resolved via resolve-analytics-lm. nil/blank → fall back to the agent's :lm-config."}
   :analytics-shs-weights      {:type "object" :default nil
                                :doc "Override map for the composite Session Health Score weights (e.g. {:pqs 0.2 :tce 0.2 …}; must sum to 1.0 or it falls back). nil → built-in defaults."}
   :enable-memory-capture      {:type "boolean" :default true
                                :requires-restart true
                                :doc "Auto-start the L2 memory-capture pipeline on agent creation (subscribes to ask/tool-use/code-eval/exception hooks, feeds the S1 parser into L2); auto-stops when the last sharing agent closes. Read once at agent start — changing it mid-session takes effect only after restarting `by`."}
   ;; Context-graph memory overlay (CR-MEM-20, docs/design/context-graph-memory-design.md).
   :enable-graph-memory        {:type "boolean"
                                :env-fn #(if-some [v (System/getenv "BY_ENABLE_GRAPH_MEMORY")]
                                           (= "true" v) ::env-unset)
                                :default false
                                :requires-restart true
                                :doc "Context-graph memory overlay: maintain a typed entity/relationship graph (graph_nodes/graph_edges) as an extra RRF recall signal over the L1/L2/L3 FTS store. Off by default; non-regressing (empty graph ⇒ recall == pure FTS). Read once when the memory manager is built — changing it mid-session takes effect only after restarting `by`. Env: BY_ENABLE_GRAPH_MEMORY."}
   :graph-embed-model          {:type "string"
                                :env-fn #(if-some [v (System/getenv "BY_GRAPH_EMBED_MODEL")]
                                           v ::env-unset)
                                :default nil
                                :requires-restart true
                                :doc "Semantic-similarity embedder for the context graph (only when :enable-graph-memory). \"static\" = in-binary Model2Vec, or a \"provider/model\" string (e.g. ollama/nomic-embed-text). nil → no vector signal. The embed-fn is built at memory-manager startup — changing it needs a `by` restart (then run memory$reembed). Env: BY_GRAPH_EMBED_MODEL."}
   :graph-extract-model        {:type "string"
                                :env-fn #(if-some [v (System/getenv "BY_GRAPH_EXTRACT_MODEL")]
                                           v ::env-unset)
                                :default nil
                                :requires-restart true
                                :doc "Chat LM that extracts entities/relationships from episodes and writes community summaries for the context graph. nil → graph stays storage-only (manual edge API). The extract-fn is built at memory-manager startup — changing it needs a `by` restart. Env: BY_GRAPH_EXTRACT_MODEL."}
   :graph-extract-mode         {:type "keyword" :default :at-consolidation :requires-restart true
                                :doc "When graph extraction runs (only when :enable-graph-memory). :at-consolidation (default) — batch-extract the episodes captured since the last consolidation in ONE LLM call at each consolidation (fewer calls; graph is stale between consolidations). :per-episode — the async extractor runs one extraction per captured L2 turn (fresher graph, one LLM call per turn). Baked at start-capture! — needs a `by` restart."}
   :graph-extract-max-input-chars {:type "integer" :default 400000 :requires-restart true
                                   :doc "Max chars fed to a single graph-extraction call before truncation (only when :enable-graph-memory). ~400K chars ≈ 100K tokens, enough to batch a whole consolidation window in one call (:at-consolidation), or cap one large episode (:per-episode). Baked into the extractor at start-capture! — needs a `by` restart."}
   :graph-max-entities-per-episode {:type "integer" :default 12 :requires-restart true
                                    :doc "Confines graph explosion: max entities a single episode may add to the graph (only when :enable-graph-memory). Extras are dropped (durable ones are listed first). Baked into the extractor at start-capture! — needs a `by` restart."}
   :graph-max-relations-per-episode {:type "integer" :default 24 :requires-restart true
                                     :doc "Confines graph explosion: max relationships (edges) a single episode may add (only when :enable-graph-memory). Kept highest-confidence-first, then capped. Baked into the extractor at start-capture! — needs a `by` restart."}
   :graph-extract-batch-episodes {:type "integer" :default 10
                                  :doc "Episodes per LLM call when manual `memory graph-build`/`reduce` batches extraction into windows (only when :enable-graph-memory). Larger ⇒ fewer/cheaper calls but the model dilutes over a bigger context and the graph gets sparser; smaller ⇒ more calls, higher fidelity. The per-episode entity/relation caps are scaled by the window's episode count, so a window keeps N× the single-episode budget. Read fresh at each graph-build/reduce (no restart)."}
   :graph-max-nodes            {:type "integer" :default 100 :requires-restart true
                                :doc "Total-size cap: max nodes retained in the context graph per user (only when :enable-graph-memory). Over budget, the lowest-retention nodes are evicted (ranked by edge-degree, then curated-type over the generic `entity` fallback, then has-summary, then recency) down to 90% of the cap. 0 disables the cap. Baked into the extractor at start-capture! — needs a `by` restart."}
   :graph-max-edges            {:type "integer" :default 200 :requires-restart true
                                :doc "Total-size cap: max valid (non-invalidated) edges retained in the context graph per user (only when :enable-graph-memory). Over budget, the lowest-retention edges are evicted (ranked by confidence, then recency — lowest-confidence, stalest first) down to 90% of the cap. 0 disables the cap. Baked into the extractor at start-capture! — needs a `by` restart."}
   :graph-prune-orphans?       {:type "boolean"
                                :env-fn #(if-some [v (System/getenv "BY_GRAPH_PRUNE_ORPHANS")]
                                           (= "true" v) ::env-unset)
                                :default true
                                :requires-restart true
                                :doc "After each graph extraction, hard-delete orphan nodes — nodes with no edge row at all (only when :enable-graph-memory). Removes extracted entities the model never wired into a relation, plus any node left edgeless by node/edge budget eviction, keeping the graph edge-connected. A node whose only edge was superseded is RETAINED (it keeps an invalidated edge row + as-of history). Trade-off: an unrelated node with a summary still feeds vector (:vec) recall, so pruning drops that semantic signal; set false to keep such nodes (they are then only evicted when :graph-max-nodes is exceeded). Baked into the extractor at start-capture! — needs a `by` restart. Env: BY_GRAPH_PRUNE_ORPHANS."}
   :enable-memory-consolidation {:type "boolean"
                                 :env-fn #(if-some [v (System/getenv "BY_ENABLE_MEMORY_CONSOLIDATION")]
                                            (= "true" v) ::env-unset)
                                 :default false
                                 :doc "Batch L2→L3 memory consolidation: an ask/post hook runs the pipeline's reducer every :memory-consolidate-every-n-turns turns (community consolidation when :enable-graph-memory is on, else heuristic). IMPLIED by :enable-graph-memory — when graph memory is on, consolidation runs even if this is false (the extractor builds communities that consolidation harvests into L3). Set this true to also run the heuristic reducer with graph memory off. Replaces the retired per-turn essence loop. Env: BY_ENABLE_MEMORY_CONSOLIDATION."}
   :memory-consolidate-every-n-turns {:type "integer" :default 5
                                      :doc "Cadence for the batch consolidation hook: run the reducer once every N completed turns (higher = cheaper/coarser). Ignored when :enable-memory-consolidation is false."}
   :show-memory-activity       {:type "boolean"
                                :env-fn #(if-some [v (System/getenv "BY_SHOW_MEMORY_ACTIVITY")]
                                           (= "true" v) ::env-unset)
                                :default true
                                :doc "Surface background memory milestones in the TUI scrollback as muted `🧠 memory · …` lines (L2→L3 consolidation, graph extraction) so the user can see memory working. Read at event time by the TUI's dedicated mulog publisher — toggleable live. Off ⇒ memory activity stays silent (still logged). Env: BY_SHOW_MEMORY_ACTIVITY."}
   ;; Self-improvement loop (R1 — docs/design/self-improve-design.md).
   :enable-skill-distillation  {:type "boolean"
                                :env-fn #(if-some [v (System/getenv "BY_ENABLE_SKILL_DISTILLATION")]
                                           (= "true" v) ::env-unset)
                                :default false
                                :doc "Self-improvement: an ask/post hook scores each finished turn for a novel reusable procedure and, past :skill-distill-threshold, stages a SKILL.md proposal under .brainyard/skills/proposals/ (never writes a live skill). Root agents; off by default. Env: BY_ENABLE_SKILL_DISTILLATION."}
   :skill-distill-threshold    {:type "number"
                                :env-fn #(if-some [v (System/getenv "BY_SKILL_DISTILL_THRESHOLD")]
                                           (or (parse-double v) ::env-unset) ::env-unset)
                                :default 0.7
                                :doc "Minimum skill-distillation score (0.0..1.0) for a turn to stage a skill proposal (higher = fewer, higher-confidence proposals). Env: BY_SKILL_DISTILL_THRESHOLD."}
   :enable-self-improve-nudges {:type "boolean"
                                :env-fn #(if-some [v (System/getenv "BY_ENABLE_SELF_IMPROVE_NUDGES")]
                                           (= "true" v) ::env-unset)
                                :default false
                                :doc "Surface a one-line per-turn notice (iteration :notices) when skill proposals await review under .brainyard/skills/proposals/, so the user need not run skill-proposal$list. Root agents; off by default. Env: BY_ENABLE_SELF_IMPROVE_NUDGES."}
   :enable-skill-refinement    {:type "boolean"
                                :env-fn #(if-some [v (System/getenv "BY_ENABLE_SKILL_REFINEMENT")]
                                           (= "true" v) ::env-unset)
                                :default false
                                :doc "Self-improvement (R1 Phase 2): a tool-use/post hook watches skill$<name> failures and, when the SKILL.md is at fault, stages a :refinement proposal (updated SKILL.md) for review. Off by default. Env: BY_ENABLE_SKILL_REFINEMENT."}
   :enable-scheduler           {:type "boolean"
                                :env-fn #(if-some [v (System/getenv "BY_ENABLE_SCHEDULER")]
                                           (= "true" v) ::env-unset)
                                :default false
                                :doc "First-class scheduler (R2): a daemon ticker fires due jobs from <project>/.brainyard/schedule/ in-process each session. Off by default (runs LLM prompts unattended); schedule$run-now/run-due work manually regardless. Env: BY_ENABLE_SCHEDULER."}
   :scheduler-tick-ms          {:type "integer"
                                :env-fn #(if-some [v (System/getenv "BY_SCHEDULER_TICK_MS")]
                                           (or (parse-long v) ::env-unset) ::env-unset)
                                :default 60000
                                :doc "Scheduler ticker interval (ms): lower = finer cron resolution, more wakeups. Env: BY_SCHEDULER_TICK_MS."}
   :gateway-pair-code-ttl-ms   {:type "integer"
                                :env-fn #(if-some [v (System/getenv "BY_GATEWAY_PAIR_CODE_TTL_MS")]
                                           (or (parse-long v) ::env-unset) ::env-unset)
                                :default 600000
                                :doc "Messaging gateway (R3): lifetime (ms) of a one-time pairing code minted by gateway$pair-code (default 10 min). Env: BY_GATEWAY_PAIR_CODE_TTL_MS."}
   :max-agent-call-depth       {:type "integer" :default 3
                                :doc "Max nesting depth for subagent (agent-call) recursion."}
   :enable-subagent-calls      {:type "boolean" :default true
                                :doc "Allow the agent to dispatch subagents (agent-call); when false, subagent dispatch is disabled."}
   :max-subagents-per-session  {:type "integer" :default 8
                                :doc "Per-session cap on live subagents in the registry. Every dispatched subagent is kept alive (askable via agent-registry$ask); at the cap a new dispatch evicts the least-recently-used non-running subagent (LRU) to make room."}
   :enable-user-hooks          {:type "boolean" :default true
                                :doc "Global kill-switch for LLM-authored user hooks (.brainyard/hooks/*.edn). When false, persisted hooks stay on disk but do not fire."}
   :dispose-think-block        {:type "boolean" :default true
                                :doc "TUI thinking live block: true removes it from scrollback when thinking stops; false freezes it as history."}
   :dispose-iteration-block    {:type "boolean" :default false
                                :doc "TUI iteration live block: true removes it from scrollback when the iteration ends; false freezes it as history."}
   :dispose-acp-block          {:type "boolean" :default false
                                :doc "TUI ACP transcript live block: true removes it from scrollback when the ACP turn ends; false freezes it as history. Analog of :dispose-iteration-block for acp-agent instances."}
   :acp-message-max-lines      {:type "integer" :default 100
                                :doc "TUI ACP transcript block: max lines shown for the live streaming assistant-message tail (a [-N lines] fold caps the rest). Defaults high so the block shows the full message inline; the separate turn-end answer box is off by default (:acp-show-final-answer)."}
   :acp-show-thoughts          {:type "boolean" :default true
                                :doc "TUI ACP transcript block: render agent_thought_chunk reasoning as dim '● Thinking:' segments interleaved with tools/message; false hides them."}
   :acp-show-final-answer      {:type "boolean" :default false
                                :doc "TUI: for acp-agent turns, whether ask-post also emits the separate answer box + 'Goal achieved' verdict. Default false because the ACP transcript block already shows the full streamed message inline; set true to restore the turn-end answer box."}
   :dispose-task-block         {:type "boolean" :default true
                                :doc "TUI task live block: true removes it from scrollback when the task ends; false freezes it as history."}
   :dispose-agent-block        {:type "boolean" :default true
                                :doc "TUI sub-agent live block: true removes it from scrollback when the sub-agent call ends; false freezes it as history."}
   :enable-tmux-popup          {:type "boolean" :default true
                                :doc "TUI permission/feedback prompts render as a tmux popup when true (Mode B + popup-capable client); false always falls back to the in-stream live-block."}
   :enable-input-suggestions   {:type "boolean" :default true
                                :doc "TUI: offer the agent's self-reported next-user-prompt as a right-arrow-acceptable help tip on the idle input line; false shows only rotating static tips."}
   :resume-scrollback-bytes    {:type "integer" :default 10485760
                                :doc "TUI --resume: trailing bytes of persisted :stream scrollback (raw ANSI, UTF-8 decoded) replayed into the pane (default 10 MiB; bounded by the on-disk cap)."}
   :enable-mid-turn-recall     {:type "boolean" :default false
                                :doc "Mid-turn re-recall (M8a): a tool-use/post hook extracts novel entity terms from each tool result, fires a refined recall, and merges hits into :recalled-memory. Off by default."}
   :tool-cache-ttl             {:type "integer" :default 0
                                :doc "Cross-turn tool-result cache TTL (seconds); 0 disables lookup. Only fires for tools listed in :tool-cache-readers."}
   :tool-cache-readers         {:type "vector"  :default []
                                :doc "Tool-id strings eligible for the cross-turn tool-result cache (read-style tools whose output is safe to reuse within :tool-cache-ttl)."}
   :explore-persist-threshold  {:type "integer" :default 1000
                                :doc "Explore-agent: result size (chars) above which findings are auto-persisted (per-turn tunable)."}
   :explore-auto-persist       {:type "boolean" :default true
                                :doc "Explore-agent: auto-persist findings exceeding :explore-persist-threshold."}
   :explore-reuse-volatile-hours {:type "integer" :default 24
                                  :doc "Explore-agent: a `volatile` (web/MCP) dossier older than this many hours is treated as stale by the iteration-0 reuse gate (explore$reuse?). `static` dossiers ignore this and are gated on cited files being unchanged."}
   :workflow-auto-finalize     {:type "boolean" :default true
                                :doc "Workflow-agent: auto-finalize the turn when the specialist completes (vs. requiring an explicit finalize)."}
   :research-auto-finalize     {:type "boolean" :default true
                                :doc "Research-agent: auto-finalize the turn when the specialist completes."}
   :rebudget-every-n-iter      {:type "integer" :default 10
                                :doc "BT loop: re-run the context budget reducer every N iterations."}
   :conversation-limit         {:type "integer" :default 20
                                :doc "Max prior conversation turns retained in assembled context."}
   :conversation-style         {:type "string" :default "timeline"
                                :doc "Conversation-history rendering. \"timeline\": completed own-turn Q/A pairs older than :conversation-keep-verbatim collapse to [Turn N] references into the Previous Turns section (sub-agent/system messages stay verbatim) — removes the Q/A double-render between the two sections. \"full\": legacy verbatim window."}
   :conversation-keep-verbatim {:type "integer" :default 2
                                :doc "\"timeline\" conversation style: how many of the most recent completed own turns keep their Q/A verbatim in the window (recency anchor for pronoun-style follow-ups). Older turns become [Turn N] references."}
   :recall-limit               {:type "integer" :default 10
                                :doc "Max recalled memory hits injected into a turn's context."}
   :memory-question-max-chars  {:type "integer" :default 8000
                                :requires-restart true
                                :doc "L2 storage cap (chars) for a captured Q&A episode's question — what FTS can match (decoupled from the recall-render snippet; storage cost is DB/FTS bytes, not prompt tokens). Baked into the capture parser at start-capture! — changing it needs a `by` restart."}
   :memory-answer-max-chars    {:type "integer" :default 16000
                                :requires-restart true
                                :doc "L2 storage cap (chars) for a captured Q&A episode's answer (decoupled from the recall-render snippet). Baked into the capture parser at start-capture! — changing it needs a `by` restart."}
   :memory-recall-snippet-chars {:type "integer" :default 600
                                 :doc "Per-hit char cap when rendering a recalled memory into the prompt."}
   :acp-backend                {:type "keyword" :default :stub
                                :doc "ACP (agent-client-protocol) backend implementation; :stub by default."}
   :acp-backend-opts           {:type "object"  :default {}
                                :doc "Options map for the ACP backend. Launch keys (:command/:working-dir/:env/:forward-env) go to the registry factory; :model (e.g. \"sonnet\"/\"opus\"/\"haiku\" for :claude-code) is resolved against the agent's advertised models and set per session via session/set_model."}
   :acp-timeout-ms             {:type "integer" :default 600000
                                :doc "ACP request timeout (ms)."}
   :acp-permission-timeout-ms  {:type "integer" :default 120000
                                :doc "ACP permission-prompt timeout (ms)."}
   :max-acp-agents-per-session {:type "integer" :default 3
                                :doc "Per-session cap on live ACP agent instances (each backs an external subprocess + one model-pinned session). Counts ALL acp-agent instances in the session — TUI roots and acp$create-provisioned alike. acp$create refuses at the cap (a paid external session is never silently LRU-evicted); close one with acp$close first."}
   :parent-trail-k             {:type "integer" :default 3
                                :doc "Depth of the parent-handoff trail surfaced to a subagent (how many ancestor turns of context)."}
   :react-loop-mode            {:type "string"  :default "single"
                                :doc "ReAct loop mode: \"single\" (one action per iteration) or \"multi\"."}
   :react-keep-thoughts-n      {:type "integer" :default 3
                                :doc "ReAct section-budget compaction floor: thoughts retained."}
   :react-keep-observations-n  {:type "integer" :default 3
                                :doc "ReAct section-budget compaction floor: observations retained."}
   :react-keep-iterations-n    {:type "integer" :default 3
                                :doc "ReAct section-budget compaction floor: full iterations retained."}
   ;; NOTE: working-dir is intentionally NOT a config key. It is resolved
   ;; purely at runtime by `resolve-working-dir` (flag / `BY_WORKING_DIR` env /
   ;; `user.dir`) and surfaced via the `:dirs` map below + `(config/working-dir)`.
   ;; Keeping it out of the schema means config.edn cannot set it, so there is
   ;; one source of truth and no divergence between two notions.
   :lm-config                  {:type "object"
                                :read-only true
                                :default-fn #(clj-llm/get-default-lm)
                                :doc "Agent's main LM config map ({:provider :model :max-tokens …}). Read-only via agent-runtime$config (it's a structured map, not a simple value) — switch models with the TUI /model command. Default resolves via clj-llm/get-default-lm (honors LLM_PROVIDER)."}
   :dirs                       {:type "object"
                                :read-only true
                                :default-fn #(resolve-dirs)
                                :doc "Resolved dirs map ({:user-dir :project-dir :working-dir}). Read-only — derived fresh at runtime (flag/env/git-root), never set via config; seeded into session-config at startup, else resolve-dirs."}
   ;; NOTE: the Tavily web-search key is intentionally NOT a config key. Like
   ;; working-dir (above), a secret has one source of truth — the TAVILY_API_KEY
   ;; env var, read directly at the web-search tool. Keeping it out of the schema
   ;; means a (committable) config.edn can't carry it, and it never enters config
   ;; snapshots/displays. See agent.common.tools/get-tavily-api-key.
   :allowed-dirs               {:type "array"
                                :default-fn #(default-allowed-dirs)
                                :doc "Allow-list of directories for filesystem-touching tools (bash/read/write/grep/task$run). Lazy default: /tmp + project-dir + user-config-dir."}
   :permission-mode            {:type "keyword" :default :auto
                                :doc "Permission-prompt policy for sensitive tool ops: :auto-approve | :ask-each-time | :deny-by-default | :auto (default; :auto-approve when a container is detected via env-detect, else :ask-each-time — so a bare host still prompts). Resolved by resolve-permission-mode. Persisted as [:permissions :mode]."}
   :display-format             {:type "keyword"
                                :env-fn #(if-let [v (not-empty (System/getenv "BY_DISPLAY_FORMAT"))]
                                           (keyword v) ::env-unset)
                                :default :normal
                                :doc "TUI display detail level (source of truth for the /display-format command and the -v flag): :quiet (think bullets + box-less answer) | :normal (iterations+tools+answer) | :verbose (+ BT traces). Env: BY_DISPLAY_FORMAT."}
   :mcp-allow-tools            {:type "array"
                                :default []
                                :doc "Allowlist of MCP tools that skip the fail-closed permission gate (auto-approved). Each entry is a `server/tool` glob — `*` matches any run of chars (e.g. \"linear/*\", \"slack/post_message\", \"*/*_read\"). Side-effecting MCP tools NOT matched here (and lacking readOnlyHint) prompt for approval via the same UI as write-file/bash. See mcp/permission.clj."}
   :nrepl-enabled?             {:type "boolean"
                                :env-fn #(if-some [v (System/getenv "BY_NREPL_ENABLED")]
                                           (= "true" v) ::env-unset)
                                :default false
                                :doc "Enable the in-process nREPL server backing code$eval :backend :nrepl. Off by default; nREPL is the full-trust backend (no scope/confirmation) — use the SCI sandbox for isolated eval. Env BY_NREPL_ENABLED wins over a persisted false."}
   :nrepl-port                 {:type "integer"
                                :env-fn #(if-some [v (System/getenv "BY_NREPL_PORT")]
                                           (or (parse-long v) ::env-unset) ::env-unset)
                                :default 0
                                :doc "Port for the in-process nREPL server (0 = ephemeral). Env: BY_NREPL_PORT."}
   :nrepl-host                 {:type "string"
                                :env-fn #(if-let [v (not-empty (System/getenv "BY_NREPL_HOST"))]
                                           v ::env-unset)
                                :default "127.0.0.1"
                                :doc "nREPL endpoint HOST for the :nrepl Clojure backend (default loopback). Set to a trusted remote host for off-laptop execution (R4). Env: BY_NREPL_HOST."}
   :clj-backend                {:type "keyword" :default :sandbox
                                :doc "Clojure code-execution backend for ```clojure blocks in CoAct: :sandbox (SCI, safe default) or :nrepl (live JVM via clj-nrepl; debug-agent, needs server). Per-agent override; not persisted."}
   :exec-backend               {:type "keyword"
                                :env-fn #(if-let [v (not-empty (System/getenv "BY_EXEC_BACKEND"))]
                                           (keyword v) ::env-unset)
                                :default :local
                                :doc "Execution backend (R4) — WHERE shell + Clojure run: :local (this machine; ProcessBuilder + the :clj-backend strategy). Env: BY_EXEC_BACKEND."}
   :ask-channel-enabled?       {:type "boolean"
                                :env-fn #(if-some [v (System/getenv "BY_ASK_CHANNEL")]
                                           (not= "0" v) ::env-unset)
                                :default true
                                :doc "Side ask channel: each TUI session opens a per-session AF_UNIX socket (ask.sock) so `by ask --attach <id>` can inject a question into the live turn queue. On by default. Env BY_ASK_CHANNEL=0 disables."}
   :ask-timeout-ms             {:type "integer"
                                :env-fn #(if-some [v (System/getenv "BY_ASK_TIMEOUT_MS")]
                                           (or (parse-long v) ::env-unset) ::env-unset)
                                :default 120000
                                :doc "Server-side cap (ms) on a single side-ask turn. Env: BY_ASK_TIMEOUT_MS."}
   ;; SCI sandbox interop level for the CoAct/RLM code sandbox.
   ;;   :restricted (default) — whitelisted pure classes + denylist
   ;;                (System/Runtime/ProcessBuilder/ClassLoader denied). Only
   ;;                safe posture on a host.
   ;;   :full       — arbitrary Java interop (no denylist). Only appropriate
   ;;                inside a disposable container. In the NATIVE binary full
   ;;                interop is bounded by the reflection config; it is complete
   ;;                only under the JVM uberjar (BY_JAR=1).
   ;;   :auto       — :full when a container (Docker/devcontainer) is detected
   ;;                via env-detect, else :restricted. Resolved by
   ;;                `resolve-sandbox-interop`.
   ;; Default reads BY_SANDBOX_INTEROP (restricted|full|auto). Defaults to
   ;; :auto: :full only when a container is detected (env-detect), else it
   ;; stays :restricted — so a bare host is never silently relaxed.
   :sandbox-interop            {:type "keyword"
                                :env-fn #(if-let [v (not-empty (System/getenv "BY_SANDBOX_INTEROP"))]
                                           (keyword v) ::env-unset)
                                :default :auto
                                :doc "SCI code-sandbox Java-interop level: :restricted (whitelisted pure classes + System/Runtime/ProcessBuilder/ClassLoader denied), :full (arbitrary interop, container-only), or :auto (default; :full when a container is detected, else :restricted). Env: BY_SANDBOX_INTEROP."}})

(def config-keys (set (keys config-schema)))

(def read-only-keys
  "Schema keys flagged `:read-only` — runtime-derived or structured values that
   must NOT be set through the `agent-runtime$config` command (`:dirs` is
   resolved fresh each run; `:lm-config` is a map switched via the TUI /model
   command). Reads/search still surface them. Single source of truth: the
   `:read-only` flag on each schema entry."
  (set (keep (fn [[k entry]] (when (:read-only entry) k)) config-schema)))

(defn read-only-key?
  "True if `k` is a read-only config key (see `read-only-keys`)."
  [k]
  (contains? read-only-keys k))

(def restart-required-keys
  "Schema keys flagged `:requires-restart` — read ONCE at startup (the memory
   manager's graph provider fns at `create-memory-manager`; the capture pipeline
   at `start-capture!`), so changing them via `agent-runtime$config` persists to
   config.edn but does NOT take effect until `by` is restarted. Surfaced in
   search results and called out in the set-confirmation message so the LLM is
   not misled by an 'effective immediately' reply. Single source of truth: the
   `:requires-restart` flag on each schema entry."
  (set (keep (fn [[k entry]] (when (:requires-restart entry) k)) config-schema)))

(defn requires-restart-key?
  "True if changing `k` only takes effect after restarting `by`
   (see `restart-required-keys`)."
  [k]
  (contains? restart-required-keys k))

(defn redact-config-value
  "Mask a config value for LLM-facing display. Flat secrets are intentionally
   NOT config keys (they live in env vars — see the working-dir / tavily notes in
   `config-schema`), so the only secret reaching config is an `:api-key` leaf
   embedded in a config object (e.g. a resolved `:lm-config`): that leaf is
   masked. Other values pass through."
  [_k v]
  (cond
    (and (map? v) (contains? v :api-key)) (assoc v :api-key "***redacted***")
    :else v))

(defn redact-config-snapshot
  "Project a full effective config map down to the LLM-facing view used by
   `agent-runtime$config :all true`: keep only SCHEMA keys (dropping non-schema,
   session-injected runtime entries the snapshot merges in — e.g. `:permission-fn`,
   `:usage-tracker`) and redact secrets via `redact-config-value`."
  [m]
  (reduce-kv (fn [acc k v]
               (if (contains? config-keys k)
                 (assoc acc k (redact-config-value k v))
                 acc))
             {}
             m))

;; ============================================================================
;; Internal Constants (not user-configurable, but centralized to avoid magic numbers)
;; ============================================================================

(defn default-allowed-dirs
  "Default allow-list for filesystem operations.
   Returns a vector containing /tmp plus the resolved :project-dir and
   user-config-dir (~/.brainyard), with nils dropped and duplicates removed.

   Arity-0 resolves dirs at call time via `resolve-dirs`.
   Arity-1 accepts a dirs map (e.g. from a live session) so the caller
   avoids re-resolving."
  ([] (default-allowed-dirs (resolve-dirs)))
  ([dirs]
   (->> ["/tmp" (:project-dir dirs) (user-config-dir dirs)]
        (remove nil?)
        distinct
        vec)))

(def default-config
  "Map of schema defaults — one entry per `config-schema` key that declares
   a static `:default`. Entries that declare `:default-fn` instead are
   omitted here; `get-config` invokes the fn lazily as the final fallback."
  (reduce-kv (fn [m k entry]
               (if (contains? entry :default)
                 (assoc m k (:default entry))
                 m))
             {}
             config-schema))

;; ============================================================================
;; Coercion
;; ============================================================================

(defn coerce-config-value
  "Coerce a value string using the config key's declared type via tool/coerce-value."
  [k value-str]
  (if-let [{:keys [type]} (get config-schema k)]
    (tool/coerce-value value-str type)
    value-str))

(defn valid-config-value?
  "True if `v` matches the declared type for `config-schema` key `k`.
   Unknown keys → false. Used by `common/config` to validate leaves under
   `[:agent :config :*]` in `config$apply` writes."
  [k v]
  (if-let [{:keys [type]} (get config-schema k)]
    (case type
      "string"  (or (string? v) (keyword? v))   ;; legacy tolerance; prefer "keyword" for keyword-valued knobs
      "keyword" (keyword? v)
      "integer" (integer? v)
      "number"  (number? v)
      "boolean" (boolean? v)
      ("array" "vector") (sequential? v)
      "object"  (map? v)
      true)  ;; unknown declared type → accept
    false))

(defn migrate-legacy-edn-shape
  "Relocate the legacy persisted positions for runtime knobs into the
   unified `[:agent :config :*]` subtree:
     [:agent :max-iterations] → [:agent :config :max-iterations]
   Idempotent: a map with the new shape only is returned unchanged.
   Returns {:config <migrated-map> :changed? bool}."
  [cfg]
  (let [agent-map  (get cfg :agent {})
        legacy-mi  (when (contains? agent-map :max-iterations)
                     (:max-iterations agent-map))
        changed?   (some? legacy-mi)]
    (if-not changed?
      {:config cfg :changed? false}
      (let [under-cfg (get agent-map :config {})
            under-cfg (cond-> under-cfg
                        legacy-mi (assoc :max-iterations legacy-mi))
            agent-map (-> agent-map
                          (dissoc :max-iterations)
                          (assoc :config under-cfg))]
        {:config (assoc cfg :agent agent-map) :changed? true}))))

;; ============================================================================
;; Directory Resolution
;; ============================================================================

(defn find-git-root
  "Walk upward from start-dir looking for .git directory.
   Returns canonical path string, or nil."
  [start-dir]
  (loop [dir (io/file start-dir)]
    (when dir
      (if (.isDirectory (io/file dir ".git"))
        (.getCanonicalPath dir)
        (recur (.getParentFile dir))))))

(defonce ^:private !working-dir-override
  ;; Runtime-resolved effective working dir installed once at startup by
  ;; `set-working-dir-override!` (from the `--working-dir`/`-C` flag), read by
  ;; `resolve-working-dir`. nil = fall through to env / `user.dir`. Stays nil at
  ;; native-image build time, so no cwd is baked into the binary.
  (atom nil))

(defn- valid-dir-canonical
  "Canonical path of `s` if it names an existing directory, else nil.
   Blank / nil / non-directory → nil."
  [s]
  (when-let [s (some-> s str/trim not-empty)]
    (let [f (io/file s)]
      (when (.isDirectory f) (.getCanonicalPath f)))))

(defn set-working-dir-override!
  "Install the process-wide effective working directory from the
   `--working-dir`/`-C` flag value. The flag is strict: a non-blank path that
   is not an existing directory throws `ex-info` (callers exit non-zero).
   A blank / nil value clears the override (resolution falls back to
   `BY_WORKING_DIR` env, then `user.dir`). Returns the canonical path
   installed, or nil when cleared."
  [dir]
  (if (some-> dir str/trim not-empty)
    (or (when-let [c (valid-dir-canonical dir)]
          (reset! !working-dir-override c)
          c)
        (throw (ex-info (str "working-dir is not a directory: " dir) {:dir dir})))
    (do (reset! !working-dir-override nil) nil)))

(defn resolve-working-dir
  "Resolve the effective working directory at runtime (precedence high→low):
   1. the `--working-dir`/`-C` override installed by `set-working-dir-override!`
   2. `BY_WORKING_DIR` env var, or the same key bridged into a System Property
      by `.env` loading (silently ignored when blank / not a directory)
   3. the JVM's `user.dir` system property (the process cwd)

   Distinct from `project-dir` by design: the three scopes (user / project /
   working) carry separate meanings and should each report truth. For 'where
   should `.brainyard/` artifacts live?' see `project-dir` instead."
  []
  (or @!working-dir-override
      (valid-dir-canonical (or (System/getenv "BY_WORKING_DIR")
                               (System/getProperty "BY_WORKING_DIR")))
      (System/getProperty "user.dir")))

(defn resolve-project-dir
  "Resolve project directory:
   1. BY_PROJECT_DIR env (explicit override)
   2. Git root found walking up from working-dir
   3. working-dir itself (current working directory fallback)

   The working-dir fallback means project-dir is ALWAYS resolvable as long
   as we have a working-dir — there is no nil case in normal operation.
   This keeps `*-agent/` artifact dirs sane even outside a git repo:
   they land under `<cwd>/.brainyard/<agent>/` instead of silently leaking
   into the user's home dir."
  [working-dir]
  (or (System/getenv "BY_PROJECT_DIR")
      (find-git-root working-dir)
      working-dir))

(defn resolve-dirs
  "Compute full directory info map."
  []
  (let [user-dir    (System/getProperty "user.home")
        working-dir (resolve-working-dir)
        project-dir (resolve-project-dir working-dir)]
    {:user-dir    user-dir
     :project-dir project-dir
     :working-dir working-dir}))

(defn user-config-dir
  "Derive the user-level .brainyard/ config dir from a dirs map."
  [dirs]
  (when-let [ud (:user-dir dirs)]
    (str ud "/.brainyard")))

(defn project-config-dir
  "Derive the project-level .brainyard/ config dir from a dirs map, or nil."
  [dirs]
  (when-let [pd (:project-dir dirs)]
    (str pd "/.brainyard")))

(defn ensure-config-dirs!
  "Create .brainyard/ directories if they don't exist. Returns dirs unchanged."
  [dirs]
  (when-let [ucd (user-config-dir dirs)]
    (.mkdirs (io/file ucd)))
  (when-let [pcd (project-config-dir dirs)]
    (.mkdirs (io/file pcd)))
  dirs)

(defn init-dirs!
  "Resolve directories and ensure .brainyard/ dirs exist."
  []
  (ensure-config-dirs! (resolve-dirs)))

;; ============================================================================
;; Config File Helpers (.brainyard/ reads & writes)
;; ============================================================================

(defn- glob-match?
  "Check if filename matches a glob pattern (e.g., \"*.md\", \"*/SKILL.md\")."
  [^String pattern ^String relative-path]
  (let [matcher (.getPathMatcher (FileSystems/getDefault) (str "glob:" pattern))]
    (.matches matcher (Path/of relative-path (into-array String [])))))

(defn- list-files-recursive
  "List files in a directory, returning relative paths."
  [^String dir-path]
  (let [dir (File. dir-path)]
    (when (.isDirectory dir)
      (->> (file-seq dir)
           (filter #(.isFile ^File %))
           (mapv (fn [^File f]
                   (subs (.getPath f) (inc (count dir-path)))))))))

(defn list-config-files
  "List files in .brainyard/ directories.
   Returns {:user-files [...] :project-files [...]} with relative paths.
   Optional pattern filter (e.g., \"*.md\", \"*/SKILL.md\")."
  [dirs & {:keys [pattern] :or {pattern "**"}}]
  (let [filter-fn (if (= pattern "**")
                    (constantly true)
                    #(glob-match? pattern %))
        user-files (when-let [ucd (user-config-dir dirs)]
                     (->> (list-files-recursive ucd)
                          (filterv filter-fn)))
        project-files (when-let [pcd (project-config-dir dirs)]
                        (->> (list-files-recursive pcd)
                             (filterv filter-fn)))]
    {:user-files    (or user-files [])
     :project-files (or project-files [])}))

(defn read-config-file
  "Read a file from .brainyard/ directories.
   Searches project config dir first, then user config dir.
   Returns {:content str :source :project|:user :path str} or {:error str}."
  [dirs relative-path]
  (let [try-read (fn [config-dir source]
                   (when config-dir
                     (let [f (File. ^String config-dir ^String relative-path)]
                       (when (.isFile f)
                         {:content (slurp f)
                          :source  source
                          :path    (.getPath f)}))))]
    (or (try-read (project-config-dir dirs) :project)
        (try-read (user-config-dir dirs) :user)
        {:error (str "File not found: " relative-path
                     " (searched .brainyard/ in project and user directories)")})))

(defn file-identity
  "A value uniquely identifying the underlying file — NIO `fileKey`
   (device+inode on POSIX), which collapses BOTH symlinks (followed) and
   hardlinks to the same target. Falls back to the canonical path string when
   `fileKey` is unavailable (some filesystems return nil). Returns nil for a
   missing/unreadable file. Used to dedupe BRAINYARD.md / CLAUDE.md / AGENTS.md
   when they are linked to one source so the same content isn't loaded twice."
  [^File f]
  (when (and f (.isFile f))
    (or (try
          (-> (Files/readAttributes (.toPath f) BasicFileAttributes
                                    (make-array LinkOption 0))
              (.fileKey))
          (catch Exception _ nil))
        (try (.getCanonicalPath f) (catch Exception _ (.getPath f))))))

(defn load-brainyard-instructions
  "Load BRAINYARD.md from both user and project config dirs.
   Returns {:user-instructions str-or-nil :project-instructions str-or-nil
            :instruction-identities #{file-identity ...}} — the identities let
   callers dedupe reference artifacts (CLAUDE.md/AGENTS.md) that are linked to
   the same file as a BRAINYARD.md."
  [dirs]
  (let [read-md (fn [config-dir]
                  (when config-dir
                    (let [f (File. ^String config-dir "BRAINYARD.md")]
                      (when (.isFile f)
                        {:content  (str/trim (slurp f))
                         :identity (file-identity f)}))))
        u (read-md (user-config-dir dirs))
        p (read-md (project-config-dir dirs))]
    {:user-instructions      (:content u)
     :project-instructions   (:content p)
     :instruction-identities (set (keep :identity [u p]))}))

(defn load-project-memory-index
  "Load the project-scoped memory index from
   `<project-config-dir>/memory/index.md`.

   Project memory is a durable, file-based knowledge base for the current
   repo: an `index.md` listing one line per topic, each pointing to a
   colocated `<slug>.md` file. The LLM reads/writes these with the ordinary
   read-file/write-file/update-file tools (`.brainyard/` is auto-allowed);
   no special agent tools are involved. Distinct from the L1/L2/L3 SQLite
   memory (user-scoped, auto-captured).

   Returns {:content str-or-nil :file-count int} — :content is the trimmed
   index.md body (nil when absent/empty); :file-count counts colocated
   `*.md` topic files (excluding index.md). Never throws."
  [dirs]
  (let [base (project-config-dir dirs)]
    (if-not base
      {:content nil :file-count 0}
      (let [mem-dir (File. ^String base "memory")
            idx     (File. mem-dir "index.md")
            content (when (.isFile idx)
                      (let [s (str/trim (slurp idx))]
                        (when-not (str/blank? s) s)))
            files   (when (.isDirectory mem-dir)
                      (->> (.listFiles mem-dir)
                           (filter (fn [^File f]
                                     (and (.isFile f)
                                          (let [n (.getName f)]
                                            (and (str/ends-with? n ".md")
                                                 (not= n "index.md"))))))
                           count))]
        {:content    content
         :file-count (or files 0)}))))

(defn- expand-home
  "Expand a leading `~`/`~/` against `user-dir`. Other paths pass through."
  [^String path user-dir]
  (cond
    (nil? path)                  path
    (= path "~")                 (or user-dir path)
    (str/starts-with? path "~/") (str user-dir (subs path 1))
    :else                        path))

(defn reference-artifact-descriptors
  "Build :origin :system live-artifact descriptors from `paths` (typically the
   :reference-artifact-paths config value — e.g. [\"CLAUDE.md\" \"AGENTS.md\"]).

   Resolution: absolute and ~-prefixed paths are used as-is; relative paths
   resolve against :project-dir then :working-dir. Only existing files are
   kept. Content is NOT read here — the per-turn resolver loads it fresh so
   on-disk edits show live.

   Dedup: entries are collapsed by `file-identity` (NIO fileKey), so reference
   docs that are symlinked or hardlinked to ONE source load only once. The
   optional `:exclude-identities` set drops entries whose underlying file is
   already loaded elsewhere — pass the BRAINYARD.md identities so a CLAUDE.md /
   AGENTS.md linked to BRAINYARD.md is not duplicated (BRAINYARD.md rides
   :system-context as instructions).

   Returns a vector of
     {:id \"ref:<abs>\" :name <basename> :source :file :path <abs>
      :origin :system :pinned? true}."
  [dirs paths & {:keys [exclude-identities]}]
  (let [{:keys [user-dir project-dir working-dir]} dirs
        exclude (set exclude-identities)
        resolve-one
        (fn [p]
          (let [p  (expand-home p user-dir)
                f  (File. ^String p)
                candidates (if (.isAbsolute f)
                             [f]
                             (keep (fn [base]
                                     (when base (File. ^String base ^String p)))
                                   [project-dir working-dir]))]
            (some (fn [^File c] (when (.isFile c) c)) candidates)))]
    (->> (or paths [])
         (keep resolve-one)
         (reduce (fn [acc ^File f]
                   (let [id (file-identity f)]
                     (if (or (nil? id)
                             (contains? (:seen acc) id)
                             (contains? exclude id))
                       acc
                       (let [abs (.getCanonicalPath f)]
                         (-> acc
                             (update :seen conj id)
                             (update :out conj
                                     {:id      (str "ref:" abs)
                                      :name    (.getName f)
                                      :source  :file
                                      :path    abs
                                      :origin  :system
                                      :pinned? true}))))))
                 {:seen #{} :out []})
         :out)))

;; ============================================================================
;; EDN Config Read/Write (.brainyard/config.edn)
;; ============================================================================

(defn resolve-scope-dir
  "Resolve a `:scope` keyword to a concrete .brainyard/ directory path.

   Scope values:
     :project — return project-config-dir; nil only when :project-dir was
                explicitly set to nil in the dirs map (rare; `resolve-dirs`
                falls back to working-dir so the normal case is non-nil)
     :user    — return user-config-dir; nil only when :user-dir is nil
     :auto    — return project-config-dir if config.edn exists at that scope,
                else fall back to user-config-dir

   Callers that need to distinguish 'real project (git root)' from
   'cwd-fallback project' should inspect `resolve-project-dir`'s sources
   directly; this helper treats both identically."
  [dirs scope]
  (case scope
    :project (project-config-dir dirs)
    :user    (user-config-dir dirs)
    :auto    (let [pcd (project-config-dir dirs)]
               (if (and pcd (.isFile (File. ^String pcd "config.edn")))
                 pcd
                 (user-config-dir dirs)))
    nil))

(defn resolve-scope
  "Resolve `:scope :project|:user|:auto` against a dirs map, returning a map
   describing the concrete scope chosen, or nil when the request can't be
   honored.

   Returns:
     {:scope :project|:user :config-dir <str> :requested <scope-arg>}
     or nil when:
       - :scope :project is requested but project-dir is nil in the dirs
         map (rare — `resolve-dirs` falls back to working-dir, so the only
         way to hit nil is to construct a dirs map explicitly with :project-dir
         nil, e.g. in tests)
       - :scope is an unknown keyword

   :auto resolves to :project iff that scope's config.edn already exists on
   disk; otherwise it resolves to :user. The `:requested` key preserves the
   original argument so callers can distinguish 'auto resolved to project'
   from 'caller asked for project explicitly'."
  [dirs scope]
  (let [scope (or scope :auto)]
    (case scope
      :project (when-let [pcd (project-config-dir dirs)]
                 {:scope :project :config-dir pcd :requested :project})
      :user    (when-let [ucd (user-config-dir dirs)]
                 {:scope :user :config-dir ucd :requested :user})
      :auto    (let [pcd (project-config-dir dirs)
                     project-file? (and pcd (.isFile (File. ^String pcd "config.edn")))
                     ucd (user-config-dir dirs)]
                 (cond
                   project-file? {:scope :project :config-dir pcd :requested :auto}
                   ucd           {:scope :user    :config-dir ucd :requested :auto}
                   :else         nil))
      nil)))

;; ============================================================================
;; .brainyard/ subdir scope policy
;; ============================================================================

(def subdir-scope-policy
  "Which scopes each `.brainyard/<subdir>` entry is allowed at.

   Membership:
     :user-only — lives ONLY under ~/.brainyard/ (per-account state that
                  must not leak across projects).
     :project-only — lives ONLY under <project>/.brainyard/ (per-repo
                     artifacts that should travel with the codebase).
     :both — may exist at either scope. The two scopes are independent
             files/dirs; the runtime does not auto-merge them.

   The map is explicit for named entries; anything ending in `-agent` not
   listed here is treated as :project-only by `subdir-allowed-scopes`."
  {;; dual-scope: the L1/L2/L3 SQLite store is user-scoped
   ;; (`~/.brainyard/memory/<user-id>.db`, per-account, must not leak across
   ;; projects), while the file-based project memory (`index.md` + colocated
   ;; `<slug>.md` topic files) is project-scoped. Distinct files at distinct
   ;; scopes under the same `memory/` name — the runtime does not merge them.
   "memory"        :both
   "logs"          :user-only

   ;; project-only: sessions are project-specific. Their scrollback, message
   ;; log, todo, queue, permissions and trajectory all describe work done in
   ;; one codebase, so they live under <project>/.brainyard/sessions/ (gitignored)
   ;; rather than a user-global junk drawer shared across every repo.
   "sessions"      :project-only

   ;; both scopes: dual-scope files/dirs the runtime reads from either layer
   "config.edn"    :both
   "BRAINYARD.md"  :both
   "skills"        :both

   ;; dual-scope agents: artifacts mirror the scope of the file they edit
   ;; (config.edn for config-agent, BRAINYARD.md for init-agent). Agent
   ;; artifact dirs live under .brainyard/agents/<name>/.
   "agents/config-agent"  :both
   "agents/init-agent"    :both

   ;; project-only: per-repo artifacts that aren't agent-named
   "charts"             :project-only
   "temp/clj-sandbox"   :project-only
   "tasks"              :project-only})

(defn subdir-allowed-scopes
  "Return the set of scopes (`:project`, `:user`) at which the given
   `.brainyard/<name>` entry is allowed. Defaults to :project-only for
   `*-agent` names not explicitly listed in `subdir-scope-policy`, and to
   `#{:user :project}` for unknown names (permissive — caller decides)."
  [^String name]
  (case (get subdir-scope-policy name)
    :user-only    #{:user}
    :project-only #{:project}
    :both         #{:user :project}
    nil           (if (.endsWith name "-agent")
                    #{:project}
                    #{:user :project})))

(defn subdir-scope-allowed?
  "Predicate: is `scope` permitted for the `.brainyard/<name>` entry?"
  [^String name scope]
  (contains? (subdir-allowed-scopes name) scope))

(defn brainyard-subdir
  "Resolve `.brainyard/<name>` at the requested scope, respecting
   `subdir-scope-policy`. Returns the absolute path string, or nil when:
     - scope is :project but project-config-dir is nil in dirs
     - scope is :user but user-config-dir is nil in dirs
     - scope is not in (subdir-allowed-scopes name) — i.e. caller asked
       for a forbidden scope (e.g. memory at :project)

   Does NOT create the directory. Callers .mkdirs as needed."
  [dirs ^String name scope]
  (when (subdir-scope-allowed? name scope)
    (when-let [base (case scope
                      :project (project-config-dir dirs)
                      :user    (user-config-dir dirs)
                      nil)]
      (str base "/" name))))

(defn brainyard-subdir!
  "Resolve `.brainyard/<name>` at the requested scope (per `brainyard-subdir`)
   and ENSURE the directory exists. Returns the absolute path string, or
   nil when the scope is forbidden or the base dir can't be resolved.

   Convenience for callers that always want the dir to exist before
   writing into it (logs, scratch dirs, caches). Inert when the dir
   already exists."
  [dirs ^String name scope]
  (when-let [p (brainyard-subdir dirs name scope)]
    (.mkdirs (io/file p))
    p))

(def ^:dynamic *sessions-root-override*
  "Test/REPL override for the project-scoped sessions root. When non-nil,
   `sessions-root` returns it verbatim. This is the SINGLE override knob —
   trajectory, memory-agent, and the persist root resolver all flow through
   `sessions-root`, so binding this one var redirects every session writer at
   once (e.g. into a temp dir for a test)."
  nil)

(defn sessions-root
  "Absolute path of the project-scoped sessions dir
   (`<project>/.brainyard/sessions`). Single source of truth for where
   persisted agent sessions live.

   Honors the `--working-dir`/`-C` and `BY_PROJECT_DIR` overrides because it
   flows through `resolve-dirs` → `resolve-project-dir`, and the
   `*sessions-root-override*` dynamic var for tests/REPL. The persist component
   (`agent-tui-persist`) is dependency-free and cannot call this; the base wires
   a resolver `(fn [] (config/sessions-root))` into it via
   `persist/set-root-resolver!`. Sites inside this component (trajectory,
   memory-agent orphan detection) call it directly at point of use."
  ([] (or *sessions-root-override* (sessions-root (resolve-dirs))))
  ([dirs] (brainyard-subdir dirs "sessions" :project)))

(defn read-edn-config
  "Read and parse config.edn from project or user config dir.

   Optional `:scope` is `:project | :user | :auto` (default `:auto`):
     :auto    — project if its config.edn exists on disk, else user
     :project — project only (returns {} if not present)
     :user    — user only
   When `:scope :project` is requested but no project-dir is resolvable,
   returns {} (callers wanting an error should use `resolve-scope` first).

   The arity-1 form preserves the legacy 'project-first, user-fallback'
   behavior used by non-config-agent callers (TUI startup, etc.) and
   matches `write-edn-config!`'s search order."
  ([dirs] (read-edn-config dirs :auto))
  ([dirs scope]
   (if-let [dir (resolve-scope-dir dirs scope)]
     (let [f (File. ^String dir "config.edn")]
       (if (.isFile f)
         (edn/read-string (slurp f))
         {}))
     {})))

(defn write-edn-config!
  "Write config map as pretty-printed EDN to .brainyard/config.edn.

   Optional `:scope` is `:project | :user | :auto` (default `:auto`):
     :auto    — project-config-dir if available, else user-config-dir
                (preserves the legacy wizard/bootstrap-driver behavior)
     :project — project-config-dir only; throws if not available
     :user    — user-config-dir; throws if home dir not available

   Throws if the requested scope's directory can't be resolved. The
   arity-2 form is the legacy 'project-or-user-fallback' behavior used by
   `bb tui config` outside a repo."
  ([dirs config-map] (write-edn-config! dirs config-map :auto))
  ([dirs config-map scope]
   (let [config-dir (case scope
                      :project (project-config-dir dirs)
                      :user    (user-config-dir dirs)
                      :auto    (or (project-config-dir dirs)
                                   (user-config-dir dirs))
                      nil)]
     (when-not config-dir
       (throw (ex-info (str "Cannot write config.edn at scope " scope
                            " — no matching .brainyard/ dir resolved")
                       {:dirs dirs :scope scope})))
     (.mkdirs (io/file config-dir))
     (let [path    (str config-dir "/config.edn")
           content (with-out-str (pprint/pprint config-map))]
       (spit path content)
       path))))

;; ============================================================================
;; Unified Config API — `get-config` / `set-config!`
;; ============================================================================
;;
;; Precedence chain (lowest → highest):
;;   1. Schema `:default` (in `default-config`) or `:default-fn` (invoked
;;      lazily by `get-config` when no override exists)
;;   2. `.brainyard/config.edn`    — `[:agent :config]` subtree merged over (1)
;;                                   to form `!global-config` (cached atom)
;;   3. Session-config             — `(:config @(:!session agent))`, seeded at
;;                                   session creation from `(:session-config ...)`
;;   4. Per-agent override         — `(:config @(:st-memory-init @!state agent))`
;;                                   seeded at `setup-agent`, mutable via
;;                                   `set-config!` with agent arg
;;
;; Reads use `get-config`; writes use `set-config!`. Both accept an optional
;; agent. The 1-arity read returns the global view; the 2-arity overlays the
;; per-agent value when present (boolean false is honored — `contains?` check).
;; The 2-arity write updates global + persists to `config.edn`; the 3-arity
;; additionally writes the per-agent override so the running agent sees it.

(def !global-config
  "Cached merge of `default-config` and the persisted `[:agent :config]`
   subtree of `.brainyard/config.edn`. Lazy-loaded on first `get-config`
   read; invalidated by `invalidate-global-config!` and refreshed by
   `load-global-config!`."
  (atom nil))

(defn- bridge-permissions-section
  "Bridge the user-facing nested `[:permissions ...]` subtree of the
   persisted EDN onto the flat schema-key positions (`:allowed-dirs`,
   `:permission-mode`). One-way: writes to those schema keys are NOT
   mirrored back here. Returns a map ready to merge into the persisted
   slice."
  [edn]
  (let [perm (get edn :permissions)]
    (cond-> {}
      (contains? perm :allowed-dirs) (assoc :allowed-dirs (:allowed-dirs perm))
      (contains? perm :mode)         (assoc :permission-mode (:mode perm)))))

(defn load-global-config!
  "Read `.brainyard/config.edn` (`:auto` scope), pick its `[:agent :config]`
   subtree intersected with `config-keys`, merge over `default-config`, reset
   `!global-config` to the result. Returns the loaded map. Safe to call
   repeatedly (idempotent re-read).

   Applies `migrate-legacy-edn-shape` in-memory so callers using legacy
   positions (`[:agent :max-iterations]` etc.) still take effect at startup
   without requiring a `config$apply` write first.

   Also bridges the user-facing `[:permissions :allowed-dirs]` and
   `[:permissions :mode]` positions onto the flat `:allowed-dirs` /
   `:permission-mode` schema keys — wizard-edited values take effect at
   runtime via `get-config` without changing the persisted EDN shape."
  ([] (load-global-config! (resolve-dirs)))
  ([dirs]
   (let [raw       (read-edn-config dirs :auto)
         migrated  (:config (migrate-legacy-edn-shape raw))
         persisted (-> (get-in migrated [:agent :config] {})
                       (select-keys config-keys))
         bridged   (bridge-permissions-section migrated)
         merged    (merge default-config persisted bridged)]
     (reset! !global-config merged)
     merged)))

(defn invalidate-global-config!
  "Clear the cached `!global-config`. Next read via `get-config` (or an
   explicit `load-global-config!`) will re-read `.brainyard/config.edn`.
   Called by `config$apply` after persisted writes."
  []
  (reset! !global-config nil)
  nil)

(defn- ensure-global-loaded! []
  (when (nil? @!global-config)
    (load-global-config!)))

(defn- agent-config
  "Return the per-agent override map (`(:config @st-memory-init)`) or nil
   when `agent` is nil / not started / has no overrides."
  [agent]
  (some-> agent :!state deref :st-memory-init deref :config))

(defn- session-config
  "Return the session-level config map (`(:config @!session)`) or nil
   when `agent` is nil / has no session / no session-config."
  [agent]
  (some-> agent :!session deref :config))

(defn- resolve-agent
  "Given an Agent record, an st-memory map, or nil, return the Agent record
   most appropriate for config lookup:
     - Agent record (has :!state)               → itself
     - st-memory map containing :agent           → that :agent
     - anything else / nil                       → proto/*current-agent*"
  [x]
  (cond
    (and x (some-> x :!state)) x
    (map? x)                    (or (:agent x) proto/*current-agent*)
    :else                       proto/*current-agent*))

(defn- schema-default-fn-value
  "Invoke the `:default-fn` for schema key `k`, or return nil when the entry
   has no `:default-fn`. The lowest-precedence fallback in `get-config`, used
   for runtime-resolved keys (`:lm-config`, `:dirs`, `:allowed-dirs`)."
  [k]
  (when-let [f (get-in config-schema [k :default-fn])]
    (f)))

(defn schema-env-value
  "Invoke schema key `k`'s `:env-fn`, returning its env-derived value, or
   `env-unset` when the key has no env binding or its variable is absent.
   The ENV layer sits ABOVE every persisted/in-memory layer in `get-config`."
  [k]
  (if-let [f (get-in config-schema [k :env-fn])]
    (f)
    env-unset))

(defonce ^:private !config-logged
  ;; (key, source) pairs already mulog'd — keeps `::config-resolved` to one
  ;; line per key per winning layer instead of one per get-config call.
  (atom #{}))

(defn- track-config!
  "Emit `::config-resolved {:key :source :value}` once per (key, source).
   Collection-valued keys log `:value :elided` to keep events small."
  [k source v]
  (let [sig [k source]]
    (when-not (contains? @!config-logged sig)
      (swap! !config-logged conj sig)
      (mulog/log ::config-resolved
                 :key k :source source
                 :value (if (or (nil? v) (string? v) (number? v)
                                (boolean? v) (keyword? v))
                          v :elided)))))

(defn- resolve-config
  "Resolve config key `k` through the precedence chain, returning
   `[value source]`. `source` ∈ #{:env :agent :session :global :default}:
   `:global` = `!global-config` (.brainyard/config.edn merged over static
   defaults); `:default` = a lazy `:default-fn` (or nil). `agent` may be nil
   (1-arity callers), which skips the :agent and :session layers."
  [agent k]
  (ensure-global-loaded!)
  (let [ev (schema-env-value k)]
    (if (not= env-unset ev)
      [ev :env]
      (let [ac (when agent (agent-config agent))
            sc (when agent (session-config agent))]
        (cond
          (and ac (contains? ac k))     [(get ac k) :agent]
          (and sc (contains? sc k))     [(get sc k) :session]
          (contains? @!global-config k) [(get @!global-config k) :global]
          :else                         [(schema-default-fn-value k) :default])))))

(defn get-config
  "Resolve a single config item. Precedence (highest → lowest):
     environment variable (a key's `:env-fn`; a set env var wins)
       → per-agent override (`(:config @st-memory-init)`)
       → session-config (`(:config @!session)`)
       → `!global-config` (.brainyard/config.edn merged over static defaults)
       → schema default (`:default-fn`, or nil)
   Boolean `false` overrides are honored — env uses a sentinel, the lower
   layers use `contains?` (not `or`). Each resolution is mulog-tracked once
   per (key, source) via `::config-resolved`.

   The 2-arity accepts an Agent record, an st-memory map (with optional
   `:agent` key), or nil; it falls back to `proto/*current-agent*` when no
   agent reference is in the argument. The 1-arity skips the per-agent and
   session layers (but still honors the env layer)."
  ([k]
   (let [[v source] (resolve-config nil k)]
     (track-config! k source v)
     v))
  ([agent-or-st k]
   (let [[v source] (resolve-config (resolve-agent agent-or-st) k)]
     (track-config! k source v)
     v)))

(defn- env-overlay
  "Map of `{k env-value}` for every schema key whose `:env-fn` resolves (its
   variable is set). The highest-precedence layer — merged last in snapshots
   so it overrides global/session/agent, mirroring `get-config`."
  []
  (reduce-kv (fn [m k entry]
               (if (:env-fn entry)
                 (let [ev (schema-env-value k)]
                   (cond-> m (not= env-unset ev) (assoc k ev)))
                 m))
             {}
             config-schema))

(defn get-config-snapshot
  "Return the merged config view (env over per-agent over session over global)
   as a plain map. Use this when a caller needs the whole map at once (e.g.
   sandbox bindings) — single-key lookups should use `get-config`.
   Accepts the same agent-or-st forms as `get-config`.

   Schema keys with only `:default-fn` (no static `:default`) are NOT in the
   snapshot — they exist solely as a per-key lazy fallback in `get-config`.
   Env-bound keys whose variable is set ARE overlaid (highest precedence),
   matching `get-config`. Snapshot callers needing lazy-default keys should
   use `get-config`."
  ([] (ensure-global-loaded!) (merge @!global-config (env-overlay)))
  ([agent-or-st]
   (ensure-global-loaded!)
   (let [agent (resolve-agent agent-or-st)]
     (merge @!global-config
            (or (session-config agent) {})
            (or (agent-config agent) {})
            (env-overlay)))))

(defn config-search-haystack
  "Lowercased searchable text for one `config-schema` entry — its key name,
   declared `:type`, and (when present) its `:doc` text. Backs
   `search-config-keys`; adding a `:doc` to a schema entry makes that key
   discoverable by concept, not just by the literal key name."
  [k spec]
  (->> [(name k) (:type spec) (:doc spec)]
       (remove nil?)
       (str/join " ")
       str/lower-case))

(defn search-config-keys
  "Find config keys whose name / type / `:doc` contain `query` (case-insensitive
   substring). Each hit is annotated with its currently resolved `:value` via
   `get-config`, so values reflect the live precedence chain for `agent-or-st`.
   Results are sorted by key; a blank query returns []. Backs the
   `agent-runtime$config` :query mode so the LLM can discover the handful of
   relevant keys without dumping the whole snapshot."
  [agent-or-st query]
  (if-let [q (some-> query str/lower-case str/trim not-empty)]
    (->> config-schema
         (keep (fn [[k spec]]
                 (when (str/includes? (config-search-haystack k spec) q)
                   (cond-> {:key     (name k)
                            :type    (:type spec)
                            :value   (redact-config-value k (get-config agent-or-st k))
                            :default (:default spec)}
                     (:doc spec)              (assoc :doc (:doc spec))
                     (:read-only spec)        (assoc :read-only true)
                     (:requires-restart spec) (assoc :requires-restart true)))))
         (sort-by :key)
         vec)
    []))

(defn config-overview
  "Curated, concise read for `agent-runtime$config` (no args) — the `list-tools`
   analog for config. Rather than dumping all ~100 effective values, it returns
   only the settings that DIFFER from their schema default (the signal that a
   caller actually changed something), the total key count, and a hint on how to
   drill in. Secrets are redacted via `redact-config-value`. The full snapshot is
   still available on demand (`:all true`), and any single key — with its
   description, type and default — via the `:query` search."
  [agent-or-st]
  (let [snap      (get-config-snapshot agent-or-st)
        overrides (reduce-kv (fn [m k v]
                               ;; Only surface SCHEMA keys with a STATIC default
                               ;; that the effective value deviates from. This
                               ;; excludes default-fn/runtime keys (:dirs,
                               ;; :lm-config, :allowed-dirs) and non-schema
                               ;; session-injected entries (:permission-fn,
                               ;; :usage-tracker) that get-config-snapshot merges
                               ;; in but which aren't user-tunable settings.
                               (if (and (contains? default-config k)
                                        (not= v (get default-config k)))
                                 (assoc m k (redact-config-value k v))
                                 m))
                             {}
                             snap)]
    {:total     (count config-keys)
     :overrides (into (sorted-map) overrides)
     :hint      (format (str "%d of %d config keys differ from their default (shown in :overrides). "
                             "Search any key with :query \"<term>\" (returns its type, default and value); "
                             "set one with :key + :value; pass :all true for the full effective snapshot.")
                        (count overrides) (count config-keys))}))

(defn- detect-container*
  "Uncached container probe — see `container-detected?`."
  []
  (try
    (if-let [f (requiring-resolve
                'ai.brainyard.env-detect.interface/detect-sandbox-environment)]
      (let [{:keys [details]} (f)]
        (boolean (or (:docker? details) (:devcontainer? details))))
      false)
    (catch Throwable _ false)))

(def ^:private !container-detected
  "Process-lifetime cache for `container-detected?`. Container status can't
   change mid-process, and the resolvers below consult it on the hot
   file/bash/code-eval path (mode/interop `:auto`), so we probe env-detect
   once and memoize."
  (atom nil))

(defn- container-detected?
  "Soft-dep probe for a container environment (Docker/devcontainer) via
   env-detect, cached for the process lifetime. Returns false when the
   env-detect component is not on the classpath (clj-sandbox/agent can run
   standalone in tests)."
  []
  (let [v @!container-detected]
    (if (nil? v)
      (reset! !container-detected (detect-container*))
      v)))

(defn resolve-sandbox-interop
  "Resolve the configured `:sandbox-interop` value to a concrete SCI interop
   level (`:restricted` | `:full`) suitable to pass to
   `clj-sandbox/create-sandbox`.

   `:auto` consults env-detect: `:full` when a container (Docker/devcontainer)
   is detected, else `:restricted`. Any other value passes through, defaulting
   to `:restricted` for nil/unknown. Accepts the same agent-or-st forms as
   `get-config` (1-arity uses the global/schema layers only)."
  ([] (resolve-sandbox-interop nil))
  ([agent-or-st]
   (let [v (if agent-or-st
             (get-config agent-or-st :sandbox-interop)
             (get-config :sandbox-interop))]
     (case v
       :auto       (if (container-detected?) :full :restricted)
       :full       :full
       :restricted :restricted
       :restricted))))

(defn resolve-permission-mode
  "Resolve the configured `:permission-mode` to a concrete policy
   (`:auto-approve` | `:ask-each-time` | `:deny-by-default`) for the permission
   gates to branch on.

   `:auto` consults env-detect: `:auto-approve` when a container
   (Docker/devcontainer) is detected — a disposable environment where prompting
   is pointless — else `:ask-each-time`, so a bare host still prompts. Any other
   value passes through, defaulting to `:ask-each-time` for nil/unknown. Accepts
   the same agent-or-st forms as `get-config` (1-arity uses the global/schema
   layers only)."
  ([] (resolve-permission-mode nil))
  ([agent-or-st]
   (let [v (if agent-or-st
             (get-config agent-or-st :permission-mode)
             (get-config :permission-mode))]
     (case v
       :auto            (if (container-detected?) :auto-approve :ask-each-time)
       :auto-approve    :auto-approve
       :ask-each-time   :ask-each-time
       :deny-by-default :deny-by-default
       :ask-each-time))))

(defn- write-persisted-key!
  "Write a single `[:agent :config k]` leaf to `.brainyard/config.edn` at
   `:auto` scope, preserving the rest of the file. Returns the file path."
  [k v]
  (let [dirs     (resolve-dirs)
        existing (read-edn-config dirs :auto)
        updated  (assoc-in existing [:agent :config k] v)]
    (write-edn-config! dirs updated :auto)))

(defn set-config!
  "Persist a single config write. Asserts `k` is in `config-keys`.

   - 2-arity `(set-config! k v)`: updates `!global-config` and writes the
     value to `.brainyard/config.edn` at `[:agent :config k]`.
   - 3-arity `(set-config! agent k v)`: does (2-arity) plus writes the
     per-agent override at `(:config @st-memory-init)` so the running
     agent observes the new value immediately.

   Returns `v`."
  ([k v]
   (assert (contains? config-keys k) (str "Unknown config key: " k))
   (ensure-global-loaded!)
   (swap! !global-config assoc k v)
   (write-persisted-key! k v)
   v)
  ([agent-or-st k v]
   (set-config! k v)
   (when-let [smi (some-> (resolve-agent agent-or-st) :!state deref :st-memory-init)]
     (swap! smi assoc-in [:config k] v))
   v))

;; ============================================================================
;; Agent Directory Helpers
;; ============================================================================
;;
;; Sugar over `get-config` for the working-dir / allowed-dirs / permission-fn
;; tuple used by file-touching tools (`bash`, `read-file`, `write-file`,
;; `grep`, `task$run`, etc.). Centralizing the resolution here keeps the
;; canonicalization + permission-prompt + default-fallback logic in one
;; place — call sites no longer reach into `:!state` / `:!session` directly.
;;
;; `:allowed-dirs` is a schema key with a lazy default — see `config-schema`
;; for its `:default-fn`. `working-dir` is NOT a schema key: it reads the
;; resolved `:dirs` map (mirroring `project-dir`) so the two stay consistent,
;; falling back to `resolve-working-dir` when no `:dirs` is in the chain.

(defn working-dir
  "Resolve the agent's effective working directory from the resolved `:dirs`
   map, falling back to `resolve-working-dir` (flag / `BY_WORKING_DIR` env /
   `user.dir`) when nothing in the chain provides `:dirs`.
   Arity-0 uses `proto/*current-agent*`."
  ([]      (or (:working-dir (get-config :dirs))       (resolve-working-dir)))
  ([agent] (or (:working-dir (get-config agent :dirs)) (resolve-working-dir))))

(defn project-dir
  "Resolve the agent's effective project root (git-root, or `:project-dir`
   from the session's resolved `:dirs` map). Falls back to `working-dir`
   when no git root is detected (e.g. outside a repo, or in tests with no
   `:dirs` configured).

   Prefer this over `working-dir` for filesystem-touching tools (read,
   write, grep, bash). `working-dir` returns the JVM cwd, which for
   `bb tui` is the launcher's `cd projects/agent-tui-app/` subdir — not
   the canonical brainyard root where `.brainyard/` artifacts live. The
   functional agents (plan/todo/explore/research/update/workflow/main)
   anchor at `project-dir`, so tool-channel reads/writes must agree."
  ([]      (or (:project-dir (get-config :dirs))       (working-dir)))
  ([agent] (or (:project-dir (get-config agent :dirs)) (working-dir agent))))

(defn allowed-dirs
  "Resolve the agent's allowed-dirs allow-list via the unified config chain
   (per-agent override → session → global → schema default-fn).
   Arity-0 uses `proto/*current-agent*`."
  ([] (get-config :allowed-dirs))
  ([agent] (get-config agent :allowed-dirs)))

(defn set-allowed-dirs!
  "Update the agent's allowed-dirs allow-list on the per-agent override
   layer (`(:config @st-memory-init)`). Does not persist to
   `.brainyard/config.edn`. Arity-1 uses `proto/*current-agent*`.
   Returns `dirs`."
  ([dirs] (set-allowed-dirs! proto/*current-agent* dirs))
  ([agent dirs]
   (when-let [smi (some-> agent :!state deref :st-memory-init)]
     (swap! smi assoc-in [:config :allowed-dirs] dirs))
   dirs))

(def ^:private allow-all-permission-fn
  "A permission-fn that approves every request without prompting — the effective
   gate when `resolve-permission-mode` is `:auto-approve` (explicit, or `:auto`
   inside a container). Applies even headless (no interactive channel), so a
   containerized `by` can write/exec freely."
  (constantly {:allowed true}))

(def ^:private deny-all-permission-fn
  "A permission-fn that refuses every request without prompting — the effective
   gate when `resolve-permission-mode` is `:deny-by-default`. Non-whitelisted
   file/bash paths are denied; the always-allowed `/tmp` and `.brainyard/` paths
   never reach the permission-fn, so the agent can still manage its own artifacts."
  (constantly {:denied true :reason "permission mode is :deny-by-default"}))

(defn- session-permission-fn
  "Resolve the effective file/bash permission-fn for the agent, honoring the
   resolved `:permission-mode`: `:auto-approve` (explicit or `:auto` in a
   container) auto-approves every op; `:deny-by-default` refuses every
   non-whitelisted op; otherwise returns the session's interactive
   `:permission-fn` (which applies the approved-dir cache + prompt), or nil when
   agent / session / fn is absent."
  [agent]
  (when agent
    (case (resolve-permission-mode agent)
      :auto-approve    allow-all-permission-fn
      :deny-by-default deny-all-permission-fn
      (some-> (:!session agent) deref (get-in [:config :permission-fn])))))

(defn resolve-agent-dirs
  "Return the agent's `{:base-dir :canonical-allowed :permission-fn}` triple
   for path-validating tools (bash, task$run). `:canonical-allowed` is the
   union of `:base-dir` and `(allowed-dirs agent)`, each `.getCanonicalPath`'d.
   Arity-0 uses `proto/*current-agent*`.

   `:base-dir` is `project-dir` (git-root), not `working-dir` (JVM cwd) —
   `bb tui` cd's into `projects/agent-tui-app/` before launching, so a
   working-dir-anchored bash would `cd` into the subdir, see only that
   tree (e.g. `ls .brainyard/` would miss main-agent's routing.log + the
   plan/todo/research artifacts), and reject absolute paths under the
   canonical repo root. Anchoring at project-dir makes bash agree with
   `read-file`/`write-file`/`grep` and with the functional agents'
   `.brainyard/` writes."
  ([] (resolve-agent-dirs proto/*current-agent*))
  ([agent]
   (let [base-dir          (project-dir agent)
         canonical-allowed (into [(-> (File. ^String base-dir) .getCanonicalFile .getPath)]
                                 (keep #(try (.getPath (.getCanonicalFile (File. ^String %)))
                                             (catch Exception _ nil)))
                                 (allowed-dirs agent))
         permission-fn     (session-permission-fn agent)]
     {:base-dir          base-dir
      :canonical-allowed canonical-allowed
      :permission-fn     permission-fn})))

;; ============================================================================
;; LM Config Helpers
;; ============================================================================

(defn resolve-sub-lm
  "Resolve the agent's sub-LM config. Pattern:
     :sub-lm-config (string) → parsed via `clj-llm/parse-lm-str` if non-blank
     otherwise → main `:lm-config`
   Unparseable sub-strings fall back to the main config rather than yielding
   nil. `parse-lm-str` returns nil for anything that isn't a `provider/model`
   (preferred) or legacy `provider:model` pair (a bare model like \"opus\" →
   nil), and a nil sub-LM would crash `query$llm` with \"No LM configuration
   provided\" — so we `or` it against the main LM. Arity-0 uses
   `proto/*current-agent*`."
  ([] (resolve-sub-lm proto/*current-agent*))
  ([agent]
   (let [main-lm (get-config agent :lm-config)
         sub-str (get-config agent :sub-lm-config)]
     (if (and sub-str (not (str/blank? sub-str)))
       (or (clj-llm/parse-lm-str sub-str) main-lm)
       main-lm))))

(defn resolve-analytics-lm
  "Resolve the LM for the LLM-enhanced analytics pass (session$analytics :deep).
   Mirrors `resolve-sub-lm`:
     :analytics-lm-config (string) → parsed via `clj-llm/parse-lm-str` if non-blank
     otherwise → the agent's main `:lm-config`
   An unparseable label falls back to the main LM rather than nil. Arity-0 uses
   `proto/*current-agent*`."
  ([] (resolve-analytics-lm proto/*current-agent*))
  ([agent]
   (let [main-lm (get-config agent :lm-config)
         a-str   (get-config agent :analytics-lm-config)]
     (if (and a-str (not (str/blank? a-str)))
       (or (clj-llm/parse-lm-str a-str) main-lm)
       main-lm))))

(defn resolve-eval-lm
  "Resolve the LM for the answer-evaluation check (EvaluateAnswer). Pattern:
     :eval-lm-config (string) → parsed via `clj-llm/parse-lm-str` if non-blank
     otherwise → the agent's main `:lm-config`
   Mirrors `resolve-sub-lm`: an unparseable label falls back to the main LM
   rather than nil (a nil lm-config would make the dspy call dispatch to the
   wrong provider). Wired as the per-node `:lm-config` fn on the EvaluateAnswer
   BT action; `bt/dspy`'s resolve-lm-config invokes it with the live context."
  ([] (resolve-eval-lm proto/*current-agent*))
  ([agent]
   (let [main-lm  (get-config agent :lm-config)
         eval-str (get-config agent :eval-lm-config)]
     (if (and eval-str (not (str/blank? eval-str)))
       (or (clj-llm/parse-lm-str eval-str) main-lm)
       main-lm))))
