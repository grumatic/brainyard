;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-sandbox.core.chat
  "RLM chat loop and rlm-query factory.

   Combines the main iteration loop (LLM ↔ sandbox conversation) and the
   factory function for `rlm-query` injected into the sandbox. The
   single-shot sub-LLM factories (`create-llm-query-fn`,
   `create-llm-query-batched-fn`) live in clj-llm — they are pure
   chat-completion wrappers and have nothing sandbox-specific about them.

   Main entry point: `completion`
   Sub-call factory: `create-rlm-query-fn`"
  (:require [ai.brainyard.clj-sandbox.core.sandbox :as sandbox]
            [ai.brainyard.clj-sandbox.core.prompt :as prompt]
            [ai.brainyard.clj-sandbox.core.feedback :as feedback]
            [ai.brainyard.clj-sandbox.core.message-compaction :as mc]
            [ai.brainyard.clj-sandbox.core.budget :as budget]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.mulog.interface :as mulog]))

;; Forward declaration — create-rlm-query-fn references completion below
(declare completion)

(defn create-rlm-query-fn
  "Create the rlm-query function injected into the sandbox.

   Spawns a child RLM with its own sandbox and iteration loop.
   The child can write code, iterate, and return a final answer.
   Enforces strict safety: max 5 sub-iterations, 60s timeout, depth+1.

   Parameters:
     lm-config      - LM configuration for the child RLM
     usage-tracker  - Shared usage tracker atom (may be nil)
     current-depth  - Current recursion depth
     max-depth      - Maximum allowed recursion depth"
  [lm-config usage-tracker current-depth max-depth]
  (fn rlm-query
    ([prompt] (rlm-query prompt nil))
    ([prompt sub-context]
     (when (>= current-depth max-depth)
       (throw (ex-info "Max recursion depth reached — cannot spawn child RLM"
                       {:depth current-depth :max-depth max-depth})))
     (mulog/debug ::rlm-query-spawn-child :depth (inc current-depth))
     (let [context (or sub-context "")
           result (completion prompt context
                              :lm-config lm-config
                              :usage-tracker usage-tracker
                              :max-iterations 5
                              :max-depth max-depth
                              :current-depth (inc current-depth)
                              :eval-timeout-ms 60000)]
       (or (:answer result)
           (str "Sub-RLM completed without FINAL after "
                (:total-iterations result) " iterations"))))))

;; ============================================================================
;; Helpers (formerly loop.clj)
;; ============================================================================

(defn- resolve-lm-config
  "Resolve LM config, falling back to global default."
  [lm-config]
  (or lm-config (clj-llm/get-default-lm)))

(defn- call-llm
  "Make a chat-completion call and return the raw response."
  [lm-config messages {:keys [usage-tracker on-chunk]}]
  (clj-llm/chat-completion lm-config messages
                           :usage-tracker usage-tracker
                           :on-chunk on-chunk))

(defn- extract-response-text
  "Extract text content from an LLM response."
  [response lm-config]
  (clj-llm/extract-content response lm-config))

(defn- eval-one-block
  "Evaluate a single code block. Returns a tagged result:
   [:ok eval-result] | [:terminated termination-result] | [:error eval-result]"
  [sandbox code eval-timeout-ms]
  (try
    [:ok (sandbox/eval-code sandbox code :timeout-ms eval-timeout-ms)]
    (catch clojure.lang.ExceptionInfo e
      (if (sandbox/termination? e)
        [:terminated (sandbox/termination-result e)]
        [:error {:result nil :output "" :error (sandbox/format-error e code) :code code}]))
    (catch Exception e
      [:error {:result nil :output "" :error (sandbox/format-error e code) :code code}])))

(defn- eval-code-blocks
  "Evaluate a sequence of code blocks in the sandbox.

   Returns {:eval-results [...] :terminated? bool :termination-result map}
   If a FINAL is encountered, terminates early."
  [sandbox code-blocks eval-timeout-ms]
  (loop [blocks code-blocks
         results []]
    (if (empty? blocks)
      {:eval-results results :terminated? false}
      (let [code (first blocks)
            [tag value] (eval-one-block sandbox code eval-timeout-ms)]
        (case tag
          :ok         (recur (rest blocks) (conj results value))
          :terminated {:eval-results (conj results {:code code :output "" :result nil})
                       :terminated? true
                       :termination-result value}
          :error      (recur (rest blocks) (conj results value)))))))

(defn- build-result
  "Build the standard result map returned by completion.
   Common fields are derived from the loop state; callers supply overrides."
  [loop-state overrides]
  (let [{:keys [all-iterations usage-tracker messages compaction-count sb]} loop-state]
    (merge {:iterations @all-iterations
            :usage (when usage-tracker (clj-llm/get-usage-summary usage-tracker))
            :messages @messages
            :compaction-count @compaction-count
            :sandbox sb
            :sandbox-vars (sandbox/extract-user-vars sb)}
           overrides)))

(defn- record-iteration!
  "Record an iteration entry and fire log + callback. Returns iter-entry."
  [loop-state iter-entry {:keys [code-blocks terminated?]}]
  (let [{:keys [all-iterations usage-tracker messages on-iteration]} loop-state]
    (swap! all-iterations conj iter-entry)
    (mulog/log ::rlm-iteration
               :iteration (:iteration iter-entry)
               :messages @messages
               :response-text (:response iter-entry)
               :code-blocks (or code-blocks [])
               :eval-results (:eval-results iter-entry)
               :usage (when usage-tracker (clj-llm/get-usage-summary usage-tracker))
               :terminated (boolean terminated?)
               :markdown-answer (boolean (:markdown-answer iter-entry))
               :salvaged-xml (boolean (:salvaged-xml iter-entry)))
    (when on-iteration (on-iteration iter-entry))
    iter-entry))

(defn- maybe-compact-messages!
  "Check and perform mid-turn message compaction if thresholds are met."
  [loop-state iteration {:keys [enable-compaction compaction-trigger
                                compaction-keep-recent compaction-max-summary
                                budget-atom]}]
  (when (and enable-compaction
             (> iteration compaction-trigger)
             (mc/needs-compaction? @(:messages loop-state) (:max-context-tokens loop-state)))
    (let [{:keys [messages compaction-count on-iteration]} loop-state
          before-count (count @messages)
          compacted (mc/compact-messages @messages
                                         :keep-recent compaction-keep-recent
                                         :max-summary-chars compaction-max-summary)]
      (reset! messages compacted)
      (swap! compaction-count inc)
      (when budget-atom (budget/record-compaction! budget-atom))
      (mulog/info ::mid-turn-compaction :before-count before-count :after-count (count compacted))
      (when on-iteration
        (on-iteration {:iteration iteration
                       :event :compaction
                       :before-count before-count
                       :after-count (count compacted)})))))

;; ============================================================================
;; Iteration Handlers
;; ============================================================================

(defn- handle-markdown-answer
  "Handle markdown block found in LLM response (treated as final answer)."
  [loop-state iteration response-text md-content]
  (let [iter-entry {:iteration iteration
                    :response response-text
                    :eval-results [{:code "(FINAL <markdown-block>)"
                                    :result nil :output md-content :error nil}]
                    :markdown-answer true}]
    (record-iteration! loop-state iter-entry
                       {:terminated? true})
    (build-result loop-state
                  {:answer md-content
                   :terminated-by :final
                   :total-iterations iteration})))

(defn- handle-xml-salvage
  "Handle salvaged XML tool calls converted to Clojure code.
   Returns {:result map} for termination or {:continue true} for recur."
  [loop-state iteration response-text salvaged-code eval-timeout-ms]
  (let [{:keys [messages sb]} loop-state
        {:keys [eval-results terminated? termination-result]}
        (eval-code-blocks sb [salvaged-code] eval-timeout-ms)
        assistant-msg {:role "assistant" :content response-text}
        salvage-note {:role "user"
                      :content (str "NOTE: Your XML <function_calls> syntax was auto-converted to Clojure. "
                                    "Next time, write ```clojure code blocks directly. "
                                    "For shell commands use: (bash \"command\")\n\n"
                                    (if terminated?
                                      ""
                                      (:content (prompt/build-feedback-message eval-results))))}
        iter-entry {:iteration iteration
                    :response response-text
                    :eval-results eval-results
                    :error (some :error eval-results)
                    :salvaged-xml true}]
    (record-iteration! loop-state iter-entry
                       {:code-blocks [salvaged-code]
                        :terminated? terminated?})
    (if terminated?
      {:result (build-result loop-state
                             {:answer (:value termination-result)
                              :terminated-by (:type termination-result)
                              :total-iterations iteration})}
      (do (swap! messages conj assistant-msg salvage-note)
          {:continue true}))))

(defn- handle-no-code-feedback
  "Handle response with no code blocks and no salvageable content."
  [loop-state iteration response-text]
  (let [{:keys [messages on-iteration]} loop-state
        has-xml? (re-find #"<function_calls>|<invoke\s+name=" response-text)
        assistant-msg {:role "assistant" :content response-text}
        error-msg (if has-xml? "XML tool-calling syntax used" "No code blocks in response")
        feedback-content (if has-xml?
                           "ERROR: You used XML <function_calls> syntax which is NOT supported. Write Clojure code in ```clojure fenced blocks instead. For shell commands use: (bash \"command\")"
                           "No code blocks found in your response. Please write Clojure code in ```clojure fenced blocks, or call (FINAL \"your answer\") if you have the answer.")]
    (swap! messages conj assistant-msg {:role "user" :content feedback-content})
    (record-iteration! loop-state
                       {:iteration iteration
                        :response response-text
                        :eval-results nil
                        :error error-msg}
                       {:terminated? false})))

(defn- handle-no-code-blocks
  "Dispatch handler for responses with no code blocks.
   Returns {:result map} for termination or {:continue true} for recur."
  [loop-state iteration response-text eval-timeout-ms]
  (if-let [md-content (prompt/extract-markdown-block response-text)]
    {:result (handle-markdown-answer loop-state iteration response-text md-content)}
    (let [salvaged-code (prompt/extract-xml-tool-calls response-text)]
      (if salvaged-code
        (handle-xml-salvage loop-state iteration response-text salvaged-code eval-timeout-ms)
        (do (handle-no-code-feedback loop-state iteration response-text)
            {:continue true})))))

(defn- handle-code-evaluation
  "Evaluate code blocks and handle termination or continuation.
   Returns {:result map} for termination or {:continue true} for recur.
   When :parallel-results is provided (from eval-code-blocks-parallel),
   uses those results directly instead of evaluating sequentially."
  [loop-state iteration response-text code-blocks eval-timeout-ms
   {:keys [enable-structure-aware feedback-max-chars dropped-count
           enable-budget budget-atom parallel-results]}]
  (let [{:keys [messages sb compaction-count]} loop-state
        ;; When parallel-results are provided, skip sequential eval.
        ;; Parallel blocks never terminate (FINAL is disallowed).
        [eval-results terminated? termination-result final-stripped?]
        (if parallel-results
          [parallel-results false nil false]
          (let [code (first code-blocks)
                {:keys [pre-final]} (sandbox/split-code-at-final code)
                effective-blocks (if pre-final [pre-final] code-blocks)
                final-stripped? (boolean pre-final)
                {:keys [eval-results terminated? termination-result]}
                (eval-code-blocks sb effective-blocks eval-timeout-ms)]
            [eval-results terminated? termination-result final-stripped?]))]
    (when final-stripped?
      (mulog/info ::stripped-final-deferred :iteration iteration))
    (if (and terminated? (not final-stripped?))
      ;; FINAL was called
      (do (record-iteration! loop-state
                             {:iteration iteration
                              :response response-text
                              :eval-results eval-results
                              :error (some :error eval-results)}
                             {:code-blocks code-blocks :terminated? true})
          {:result (build-result loop-state
                                 {:answer (:value termination-result)
                                  :terminated-by (:type termination-result)
                                  :total-iterations iteration})})
      ;; Continue — feed results back
      (let [feedback-msg (if enable-structure-aware
                           (feedback/build-feedback-message eval-results
                                                            :structure-aware true
                                                            :max-chars-per-block feedback-max-chars
                                                            :iteration iteration
                                                            :compaction-count @compaction-count)
                           (prompt/build-feedback-message eval-results))
            feedback-msg (cond-> feedback-msg
                           (pos? dropped-count)
                           (update :content str "\n\n⚠️ WARNING: You sent "
                                   (:total-count (meta code-blocks))
                                   " code blocks but only the FIRST was executed. "
                                   "Always send exactly ONE ```clojure block per response.")

                           final-stripped?
                           (update :content str "\n\nNOTE: Your code contained expressions before FINAL. "
                                   "The FINAL call was deferred so you can verify the results above. "
                                   "Call (FINAL ...) in your next response.")

                           (and enable-budget budget-atom)
                           (update :content str "\n\n" (budget/budget-status-string budget-atom)))
            assistant-msg {:role "assistant" :content response-text}]
        (swap! messages conj assistant-msg feedback-msg)
        (record-iteration! loop-state
                           {:iteration iteration
                            :response response-text
                            :eval-results eval-results
                            :error (some :error eval-results)}
                           {:code-blocks code-blocks :terminated? false})
        {:continue true}))))

;; ============================================================================
;; Main Loop
;; ============================================================================

(defn completion
  "Execute an RLM completion.

   The LLM writes Clojure code that runs in a sandboxed REPL.
   Context is stored as a variable, not in the prompt. The LLM
   inspects and processes it via code, calling FINAL when done.

   Args:
     query   - The user's question
     context - Map of input data the LLM explores via context-get/context-keys/etc.
               Pass nil when there is no input data. Non-map values throw.

   Options:
     :lm-config       - LM config (default: global default)
     :sub-lm-config   - LM for recursive llm-query (default: same as lm-config)
     :max-iterations  - Loop limit (default 20)
     :max-depth       - Recursion depth limit (default 1)
     :eval-timeout-ms - Per-code-block timeout (default 30000)
     :usage-tracker   - Shared usage tracker atom
     :on-iteration    - (fn [{:keys [iteration code output error]}]) callback
     :on-chunk        - Streaming callback for LLM responses
     :system-prompt   - Override system message (string or {:role \"system\" :content ...}).
                         When nil, builds a lean system prompt with context-access docs.
     :bindings        - Additional sandbox bindings {symbol value}, passed to create-sandbox.
     :initial-messages  - Pre-built messages vector for resumption (replaces default [system-msg user-msg]).
     :initial-bindings  - Additional sandbox bindings for resumption, merged into :bindings.
     :max-context-tokens - Context window size for compaction (default 128000)
     :compaction-opts    - {:enable bool :keep-recent int :trigger-iteration int :max-summary-chars int}
     :feedback-opts      - {:structure-aware bool :max-chars-per-block int}
     :budget-opts        - {:enable bool}
     :enable-parallel    - When true, extract ALL code blocks from LLM response
                           and execute them concurrently (default false)

   Returns:
     {:answer          str
      :iterations      [{:iteration int :response str
                         :eval-results [{:code str :result any :output str :error str}]
                         :error str} ...]
      :usage           usage-summary-or-nil
      :terminated-by   :final | :max-iterations | :cancelled
      :total-iterations int
      :messages        vec  ;; final LLM conversation messages (for continuation)
      :compaction-count int
      :sandbox         map  ;; the sandbox map (for persistent sandbox reuse)}"
  [query context & {:keys [lm-config sub-lm-config max-iterations max-depth current-depth
                           eval-timeout-ms usage-tracker on-iteration on-chunk
                           system-prompt bindings initial-messages initial-bindings
                           max-context-tokens compaction-opts feedback-opts budget-opts
                           enable-parallel sandbox]
                    :or {max-iterations 20
                         max-depth 1
                         current-depth 0
                         eval-timeout-ms 30000
                         max-context-tokens 128000
                         enable-parallel false}}]
  (let [lm-config (resolve-lm-config lm-config)
        sub-lm-config (resolve-lm-config (or sub-lm-config lm-config))
        llm-query-fn (clj-llm/create-llm-query-fn sub-lm-config usage-tracker)
        llm-query-batched-fn (clj-llm/create-llm-query-batched-fn sub-lm-config usage-tracker)
        rlm-query-fn (when (< current-depth max-depth)
                       (create-rlm-query-fn sub-lm-config usage-tracker current-depth max-depth))
        llm-bindings (cond-> {}
                       llm-query-fn         (assoc 'llm-query llm-query-fn)
                       llm-query-batched-fn (assoc 'llm-query-batched llm-query-batched-fn)
                       rlm-query-fn         (assoc 'rlm-query rlm-query-fn))
        sb (if sandbox
             (do (sandbox/update-bindings! sandbox llm-bindings)
                 sandbox)
             (sandbox/create-sandbox :context context
                                     :bindings (merge bindings initial-bindings llm-bindings)))
        ;; Standalone completion appends context-access docs (agent mode gets
        ;; these via the context briefing, but standalone has no briefing)
        system-msg (cond
                     (nil? system-prompt)
                     {:role "system"
                      :content (str (prompt/build-system-prompt :mode :raw
                                                                :max-iterations max-iterations)
                                    "\n\n" prompt/context-access-prompt)}

                     (string? system-prompt)
                     {:role "system" :content system-prompt}

                     (map? system-prompt)
                     system-prompt

                     :else
                     {:role "system"
                      :content (str (prompt/build-system-prompt :mode :raw
                                                                :max-iterations max-iterations)
                                    "\n\n" prompt/context-access-prompt)})
        user-msg (prompt/build-initial-user-message query)
        ;; Compaction config
        enable-compaction (get compaction-opts :enable false)
        compaction-trigger (get compaction-opts :trigger-iteration 5)
        compaction-keep-recent (get compaction-opts :keep-recent 3)
        compaction-max-summary (get compaction-opts :max-summary-chars 3000)
        ;; Feedback config
        enable-structure-aware (get feedback-opts :structure-aware false)
        feedback-max-chars (get feedback-opts :max-chars-per-block 10000)
        ;; Budget monitoring
        enable-budget (get budget-opts :enable false)
        budget-atom (when enable-budget
                      (budget/create-budget-monitor max-context-tokens max-iterations))
        ;; Shared loop state passed to all handlers
        loop-state {:messages (atom (or initial-messages [system-msg user-msg]))
                    :all-iterations (atom [])
                    :compaction-count (atom 0)
                    :usage-tracker usage-tracker
                    :on-iteration on-iteration
                    :max-context-tokens max-context-tokens
                    :sb sb}
        compaction-cfg {:enable-compaction enable-compaction
                        :compaction-trigger compaction-trigger
                        :compaction-keep-recent compaction-keep-recent
                        :compaction-max-summary compaction-max-summary
                        :budget-atom budget-atom}
        eval-cfg {:enable-structure-aware enable-structure-aware
                  :feedback-max-chars feedback-max-chars
                  :enable-budget enable-budget
                  :budget-atom budget-atom}]
    (loop [iteration 1]
      (cond
        ;; Cancellation
        (Thread/interrupted)
        (build-result loop-state
                      {:answer nil
                       :terminated-by :cancelled
                       :total-iterations (dec iteration)})

        ;; Max iterations reached
        (> iteration max-iterations)
        (let [last-result (some-> (sandbox/get-history sb) last :result)]
          (build-result loop-state
                        {:answer (when last-result (str last-result))
                         :terminated-by :max-iterations
                         :total-iterations max-iterations}))

        ;; Normal iteration
        :else
        (do
          (maybe-compact-messages! loop-state iteration compaction-cfg)
          (when budget-atom
            (budget/update-budget! budget-atom @(:messages loop-state) iteration))
          (mulog/debug ::rlm-loop-iteration :iteration iteration :max-iterations max-iterations)
          (let [response (call-llm lm-config @(:messages loop-state)
                                   {:usage-tracker usage-tracker :on-chunk on-chunk})
                response-text (extract-response-text response lm-config)
                ;; When parallel enabled, extract all code blocks and run concurrently
                code-blocks (if enable-parallel
                              (prompt/extract-all-code-blocks response-text)
                              (prompt/extract-code-blocks response-text))
                dropped-count (if enable-parallel
                                0
                                (or (:dropped-count (meta code-blocks)) 0))
                _ (when (pos? dropped-count)
                    (mulog/warn ::dropped-extra-code-blocks
                                :dropped-count dropped-count
                                :iteration iteration
                                :total (:total-count (meta code-blocks))))
                ;; For parallel mode with multiple blocks, use parallel eval
                handler-result (cond
                                 (empty? code-blocks)
                                 (handle-no-code-blocks loop-state iteration response-text eval-timeout-ms)

                                 (and enable-parallel (> (count code-blocks) 1))
                                 (let [{:keys [eval-results]} (sandbox/eval-code-blocks-parallel
                                                               sb code-blocks :timeout-ms eval-timeout-ms)]
                                   (handle-code-evaluation loop-state iteration response-text code-blocks
                                                           eval-timeout-ms
                                                           (assoc eval-cfg
                                                                  :dropped-count 0
                                                                  :parallel-results eval-results)))

                                 :else
                                 (handle-code-evaluation loop-state iteration response-text code-blocks
                                                         eval-timeout-ms
                                                         (assoc eval-cfg :dropped-count dropped-count)))]
            (if (:continue handler-result)
              (recur (inc iteration))
              (:result handler-result))))))))
