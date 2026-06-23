;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.interface
  "RLM (Recursive Language Model) — REPL-based context management for LLMs.

   Enables processing of unbounded context by storing it as a variable
   in a sandboxed Clojure REPL that the LLM explores via code.

   Instead of stuffing large inputs into the prompt, RLM loads them as
   a `context` variable in a SCI sandbox. The LLM writes Clojure code
   to inspect, decompose, and recursively call sub-LLMs — handling inputs
   100x beyond typical context windows.

   Main entry point: `completion`
   BT integration: `rlm-action`"
  (:require [ai.brainyard.clj-sandbox.core.chat :as chat]
            [ai.brainyard.clj-sandbox.core.prompt :as prompt]
            [ai.brainyard.clj-sandbox.core.sandbox :as sandbox]
            [ai.brainyard.clj-sandbox.core.context-accessors :as ctx-acc]
            [ai.brainyard.clj-sandbox.core.message-compaction :as mc]
            [ai.brainyard.clj-sandbox.core.feedback :as feedback]
            [ai.brainyard.clj-sandbox.core.conversation-window :as conv-window]
            [ai.brainyard.clj-sandbox.core.sandbox-state :as sandbox-state]
            [ai.brainyard.clj-sandbox.core.budget :as budget]
            [ai.brainyard.clj-sandbox.core.truncation :as truncation]
            [ai.brainyard.behavior-tree.interface :as bt]
            [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Core API
;; ============================================================================

(def completion
  "Execute an RLM completion.

   (completion query context & opts)

   The LLM writes Clojure code in a sandboxed REPL to process the context.
   Context is stored as a variable, not in the prompt.

   Args:
     query   - The user's question
     context - Large input data (string, EDN, etc.)

   Options:
     :lm-config       - LM config (default: global default)
     :sub-lm-config   - LM for recursive llm-query (default: same as lm-config)
     :max-iterations  - Loop limit (default 20)
     :max-depth       - Recursion depth limit (default 1)
     :eval-timeout-ms - Per-code-block timeout (default 30000). On timeout
                        the future is NOT cancelled by default — it is kept
                        in the sandbox's pending-eval registry up to its
                        `:max-pending` cap so the result can be harvested
                        later via `poll-pending-evals!`. Past the cap, the
                        future is cancelled and an error is returned.
     :usage-tracker   - Shared usage tracker atom
     :on-iteration    - (fn [{:keys [iteration code output error]}]) callback
     :on-chunk        - Streaming callback for LLM responses
     :initial-messages  - Pre-built messages vector for resumption
     :initial-bindings  - Additional sandbox bindings for resumption
     :interop           - SCI interop level for a fresh sandbox (:restricted | :full)

   Returns:
     {:answer          str
      :iterations      [{:code str :output str :error str} ...]
      :usage           usage-map-or-nil
      :terminated-by   :final | :max-iterations | :cancelled
      :total-iterations int
      :messages        vec  ;; final LLM conversation for continuation}"
  chat/completion)

;; ============================================================================
;; Sandbox (advanced / testing)
;; ============================================================================

(def create-sandbox
  "Create a new RLM sandbox. For advanced use and testing.
   Accepts `:interop` (`:restricted` default | `:full`) to control the SCI
   Java-interop surface — `:full` permits arbitrary interop and is only safe
   inside a container. See ai.brainyard.clj-sandbox.core.sandbox/create-sandbox."
  sandbox/create-sandbox)

(def eval-code
  "Evaluate code in a sandbox. For advanced use and testing.

   Returns either a synchronous result map or, on timeout, a hard-cancel
   result map carrying `{:status :timeout :timeout-ms <ms>}`. Pre-Step-G
   `eval-code` supported a soft-timeout-survives mode via the sandbox's
   internal `:pending-evals` registry; that capability is now unified
   into the agent task manager. See
   ai.brainyard.clj-sandbox.core.sandbox/eval-code for details."
  sandbox/eval-code)

(def eval-sandbox-thunk
  "Build a zero-arg thunk that evaluates `code-str` in `sandbox`
   synchronously. Caller owns the future + timeout. Use when an outer
   system already provides task lifecycle (e.g. the agent task manager's
   :clj-sandbox-eval executor). Returns `[thunk eval-output]`. See
   ai.brainyard.clj-sandbox.core.sandbox/eval-sandbox-thunk for details."
  sandbox/eval-sandbox-thunk)

(def set-var!
  "Set a variable in the sandbox. The value is bound directly as a Java object —
   no SCI reader parsing occurs on the value. Safe for strings with special characters."
  sandbox/set-var!)

(def update-context!
  "Update context variable and rebuild context-accessor bindings in a live sandbox.
   Used when reusing a sandbox across turns with new context data."
  sandbox/update-context!)

(def update-bindings!
  "Add or update bindings in a live sandbox without destroying existing user vars.
   Used to refresh tool closures between turns."
  sandbox/update-bindings!)

(def clear-history!
  "Clear evaluation history, optionally keeping the last N entries.
   Use between turns to prevent unbounded growth in persistent sandboxes."
  sandbox/clear-history!)

(def split-code-at-final
  "Split a code string into pre-FINAL code and the rest.
   Returns {:pre-final str-or-nil :has-final? bool}."
  sandbox/split-code-at-final)

(def fork-sandbox
  "Create an independent copy of a sandbox for parallel evaluation.
   The forked sandbox shares the same namespace bindings but has its own
   output buffer and history. After execution, eval-code-blocks-parallel
   merges new defs back into the parent (last-block-wins for conflicts)."
  sandbox/fork-sandbox)

(def eval-code-blocks-parallel
  "Evaluate multiple code blocks concurrently in isolated sandbox forks.
   New defs from forks are merged back into the parent sandbox in block order.
   FINAL is NOT allowed in parallel blocks (returns error if called).
   Returns {:eval-results [result-map ...]}."
  sandbox/eval-code-blocks-parallel)

(def termination?
  "Check if an exception is an RLM termination signal (FINAL)."
  sandbox/termination?)

(def termination-result
  "Extract the termination result from an RLM termination exception.
   Returns {:type :final :value any}."
  sandbox/termination-result)

(def extract-user-vars
  "Extract user-defined variables from a live sandbox as a serializable snapshot."
  sandbox/extract-user-vars)

(def extract-user-vars-with-survival
  "Like `extract-user-vars` but also reports pruned vars and the reason.
   Returns `{:kept {name {:value :type}} :lost [{:name :reason} ...]}`."
  sandbox/extract-user-vars-with-survival)

(def try-repair-escapes
  "Attempt to repair invalid escape sequences in string literals within code.
   LLMs commonly generate code with invalid escapes like \\d, \\s, \\w inside
   SCI strings. This doubles the backslash to make them literal.
   Returns repaired code string, or nil if no repairs were needed."
  sandbox/try-repair-escapes)

;; ============================================================================
;; Context Management (Selective Retrieval)
;; ============================================================================

(def make-context-accessors
  "Build context accessor functions for the RLM sandbox.
   Returns {symbol fn} map for sandbox bindings."
  ctx-acc/make-context-accessors)

(def build-indexed-conversation
  "Build an indexed conversation structure with per-turn summaries."
  conv-window/build-indexed-conversation)

(def extract-defs-from-iterations
  "Extract (def var-name ...) bindings from iteration history."
  sandbox-state/extract-defs-from-iterations)

(def build-restore-bindings
  "Build sandbox bindings from a persisted state snapshot."
  sandbox-state/build-restore-bindings)

;; ============================================================================
;; Mid-Turn Message Compaction
;; ============================================================================

(def estimate-message-tokens
  "Estimate token count for a messages vector."
  mc/estimate-message-tokens)

(def needs-message-compaction?
  "Check if messages exceed the compaction threshold."
  mc/needs-compaction?)

(def compact-messages
  "Compress old iteration messages into a summary."
  mc/compact-messages)

;; ============================================================================
;; Feedback
;; ============================================================================

(def build-feedback-message
  "Build a structure-aware feedback message from eval results."
  feedback/build-feedback-message)

;; ============================================================================
;; Budget Monitoring
;; ============================================================================

(def create-budget-monitor
  "Create a budget monitor atom for token tracking."
  budget/create-budget-monitor)

(def budget-status-string
  "Format compact budget status string."
  budget/budget-status-string)

;; ============================================================================
;; Truncation (safe truncation with temp-file recovery)
;; ============================================================================

(def truncate-to-file
  "If text exceeds max-chars, save original to temp file and return
   truncated text with a recovery path. Otherwise return text unchanged."
  truncation/truncate-to-file)

;; ============================================================================
;; RLM Query Factory (single-shot LLM factories live in clj-llm)
;; ============================================================================

(def create-rlm-query-fn
  "Create the rlm-query function for spawning child RLMs."
  chat/create-rlm-query-fn)

;; ============================================================================
;; Agent Prompt Builders
;; ============================================================================

(def build-system-prompt
  "Build lean system prompt for both standalone and agent modes.
   See ai.brainyard.clj-sandbox.core.prompt/build-system-prompt for options."
  prompt/build-system-prompt)

(def build-user-message
  "Build the first user message for any mode (:structured or :raw).
   Options: :mode, :briefing"
  prompt/build-user-message)

(def extract-markdown-block
  "Extract a ```markdown/```md/```text block from LLM response.
   Returns the block content or nil if not found."
  prompt/extract-markdown-block)

;; ============================================================================
;; Code Extraction & Iteration Helpers
;; ============================================================================

(def build-function-docs
  "Auto-generate compact function reference from sandbox bindings map."
  prompt/build-function-docs)

(def build-function-directory
  "Compact one-line-per-category function signatures for context briefing.
   Format: **Category**: fn1(args), fn2(args), ..."
  prompt/build-function-directory)

(def build-function-index
  "Ultra-compact single-line category index: `Cat (N) · Cat (N) · …`.
   Use as a lightweight replacement for build-function-directory in
   stable system context — pair with `(list-tools …)` / `(get-tool-info …)`
   for on-demand drill-in."
  prompt/build-function-index)

;; NOTE: usage-guide content moved to the agent component
;; (ai.brainyard.agent.core.usage + agent.common.usage-guides). The sandbox
;; `(usage$guide :topic <name>)` binding is the registered `:usage$guide` tool
;; auto-bound into the sandbox, reading that open registry — clj-sandbox no
;; longer owns guide content.

(def extract-all-code-blocks
  "Extract ALL ```clojure/```clj fenced blocks (not just first like extract-code-blocks)."
  prompt/extract-all-code-blocks)

(def extract-code-blocks
  "Extract ```clojure/```clj fenced blocks. Returns at most ONE code string (first block).
   Extra blocks are dropped with metadata {:dropped-count N :total-count M}."
  prompt/extract-code-blocks)

(def extract-xml-tool-calls
  "Best-effort extraction of bash/run_bash commands from XML <function_calls> format.
   Returns Clojure code string wrapping shell commands in (bash ...), or nil."
  prompt/extract-xml-tool-calls)

(def build-iterations-text
  "Format iteration records into text for the user message."
  prompt/build-iterations-text)

(def extract-all-code-blocks-multi
  "Extract ALL fenced code blocks with language tags (clojure, python, bash).
   Returns [{:lang \"clojure\" :code \"...\"} ...]. Also extracts 4+-backtick
   verbatim content fences (markdown/text/html) as
   {:lang :code :verbatim? true :filename}."
  prompt/extract-all-code-blocks-multi)

(def verbatim-lang?
  "True when `lang` names a verbatim content block (markdown/text/html)."
  prompt/verbatim-lang?)

(def build-iterations-text-multi
  "Format iteration records with language tags into text for the user message."
  prompt/build-iterations-text-multi)

(def model-default-iterations
  "Return model-aware default max-iterations."
  prompt/model-default-iterations)

;; `parse-eval-lm` was moved to clj-llm as `clj-llm/parse-lm-str` — string-to-LM
;; parsing has nothing sandbox-specific. Use `clj-llm/parse-lm-str` directly.

;; ============================================================================
;; BT Integration
;; ============================================================================

(defn- resolve-lm-config
  "Resolve lm-config with precedence:
   1. BT action opts :lm-config (per-node override)
   2. Agent's :lm-config via `agent.core.config/get-config` (per-agent
      → session → global → schema :default-fn → nil)
   When the agent component is absent from the classpath (clj-sandbox can
   run standalone), falls back to the legacy slot-2/slot-3 read so the
   resolver still works in tests."
  [context]
  (or (get-in context [:opts :lm-config])
      (when-let [agent (:agent context)]
        (if-let [get-config (try (requiring-resolve
                                  'ai.brainyard.agent.core.config/get-config)
                                 (catch Throwable _ nil))]
          (get-config agent :lm-config)
          (or (get-in @(:!state agent) [:config :lm-config])
              (get-in @(:!session agent) [:config :lm-config]))))))

(defn- resolve-usage-tracker
  "Resolve usage-tracker from:
   1. BT action opts :usage-tracker
   2. Session config :usage-tracker"
  [context]
  (or (get-in context [:opts :usage-tracker])
      (when-let [agent (:agent context)]
        (get-in @(:!session agent) [:config :usage-tracker]))))

(defn rlm-action
  "BT action: run RLM on context from st-memory.

   Expected opts in BT node:
     :context-key    - st-memory key for the large context (default :context)
     :query-key      - st-memory key for the query (default :question)
     :answer-key     - st-memory key to store the answer (default :answer)
     :max-iterations - Loop limit (default 20)
     :max-depth      - Recursion depth limit (default 1)
     :sub-lm-config  - LM config for sub-calls (default: same as main)

   BT config example:
     [:action
      {:id :rlm/process-context
       :context-key :large-document
       :query-key :question
       :answer-key :answer
       :max-iterations 20}
      clj-sandbox/rlm-action]"
  [{{:keys [context-key query-key answer-key max-iterations max-depth sub-lm-config]} :opts
    :keys [st-memory]
    :as context}]
  (let [state @st-memory
        query (get state (or query-key :question))
        ctx-data (get state (or context-key :context))
        lm-config (resolve-lm-config context)
        usage-tracker (resolve-usage-tracker context)
        ;; Build on-chunk callback via the default chunk-factory in
        ;; agent.core.bt (looked up lazily so clj-sandbox doesn't hard-depend
        ;; on the agent component; safe when the agent ns isn't on classpath).
        on-chunk-fn (when-let [factory (try (requiring-resolve
                                             'ai.brainyard.agent.core.bt/chunk-factory-handler)
                                            (catch Throwable _ nil))]
                      (factory {:agent (:agent state)
                                :st-memory-atom st-memory
                                :node-id (:id (:opts context))}))]
    (if (or (nil? query) (nil? ctx-data))
      (do
        (mulog/warn ::rlm-action-missing-input
                    :message "rlm-action: missing query or context in st-memory"
                    :query-key (or query-key :question)
                    :context-key (or context-key :context))
        bt/failure)
      (try
        (let [result (completion query ctx-data
                                 :lm-config lm-config
                                 :sub-lm-config sub-lm-config
                                 :usage-tracker usage-tracker
                                 :max-iterations (or max-iterations 20)
                                 :max-depth (or max-depth 1)
                                 :on-chunk on-chunk-fn)]
          (swap! st-memory assoc (or answer-key :answer) (:answer result))
          (swap! st-memory assoc :full-iterations (:iterations result))
          (swap! st-memory assoc :terminated-by (:terminated-by result))
          bt/success)
        (catch Exception e
          (mulog/error ::rlm-action-error :message "rlm-action error" :exception e)
          bt/failure)))))
