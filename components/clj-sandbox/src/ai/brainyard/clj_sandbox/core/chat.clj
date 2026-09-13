;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-sandbox.core.chat
  "The RLM chat loop and the rlm-query factory.

   Combines the main iteration loop (LLM ↔ sandbox conversation) and the
   factory function for `rlm-query` injected into the sandbox. The
   single-shot sub-LLM factories (`create-llm-query-fn`,
   `create-llm-query-batched-fn`) live in clj-llm — they are pure
   chat-completion wrappers and have nothing sandbox-specific about them.

   Main entry point: `completion`
   Sub-call factory: `create-rlm-query-fn`

   ## What this loop IS — and what it is not

   It is the engine behind ONE production feature: `session$analytics :deep true`
   → `analytics/analyze-trajectory :lm-config` → `pqs/score-pqs-llm` and the
   three LLM waste detectors → `completion` (soft-resolved, so clj-sandbox stays
   optional on the classpath). `:deep` defaults false; with it off nothing here
   runs. Those two call sites pass `:max-iterations`, `:lm-config` and
   `:usage-tracker` — nothing else.

   It is NOT the agent runtime, and reading it as a second one is what let six
   defects accumulate in the prompt it builds. CoAct assembles its own system
   prompt through behavior-tree's `dspy-action` and carries its own rules and
   footer; what it shares with this component is the SANDBOX (create/fork/eval/
   update-bindings), `build-function-directory` / `build-function-index`,
   `extract-all-code-blocks-multi` and `truncate-to-file`. It reaches nothing in
   this namespace and nothing in the standalone half of `core.prompt`.

   Two consequences worth keeping in view:

   - The prompt is built for a BARE sandbox. `bash`, `read-file`, `write-file`,
     `grep` and `usage$guide` are caller-supplied tools, absent unless passed as
     `:bindings`; the builder is told what exists via `:available`
     (`sandbox/bound-symbols`) and stays silent about the rest. Adding prompt
     text here that names a tool without gating it re-introduces the bug.
   - The options shaped for an agent caller — `:system-prompt`, `:initial-messages`,
     `:initial-bindings`, `:sandbox`, `:max-depth`/`:sub-lm-config`,
     `:enable-parallel`, `:compaction-opts`, `:budget-opts`, `:feedback-opts` —
     plus `build-system-prompt`'s `:briefing?`, `:function-directory`,
     `:instruction`, `:agent-context`, `:tool-context`, `:brainyard-instructions`
     and `:return-breakdown?`, have no production caller today. They are
     exercised only by tests. Treat them as a surface to shrink, not a contract
     to extend."
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
  [loop-state iteration {:keys [compaction? compaction-trigger
                                compaction-keep-recent compaction-max-summary
                                budget-atom]}]
  (when (and compaction?
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
   {:keys [structure-aware? feedback-max-chars dropped-count
           budget? budget-atom parallel-results]}]
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
      (let [feedback-msg (if structure-aware?
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

                           (and budget? budget-atom)
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
  "Execute an RLM completion — the analytics deep-scoring loop.

   The LLM writes Clojure code that runs in a sandboxed REPL. Context is stored
   as a variable, not in the prompt: the LLM inspects and processes it via code,
   then ends the run with `(FINAL …)` or a ```markdown block (see the Critical
   Rules in `core.prompt` — plain prose does NOT end it).

   Reached in production only from `session$analytics :deep true`; see the ns
   docstring for the full path and for which options have no caller. Not the
   agent runtime — CoAct builds its own prompt and shares only the sandbox.

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
                         When nil (every production call), builds a lean system prompt
                         sized to what the sandbox actually has: context-access docs only
                         when the context-* accessors are bound, tool advice only for
                         tools in `sandbox/bound-symbols`, and the Execution Model matched
                         to the sandbox's own `:interop`.
     :bindings        - Additional sandbox bindings {symbol value}, passed to create-sandbox.
     :interop         - SCI interop level for a freshly created sandbox: :restricted
                        (default) or :full. Ignored when reusing a passed-in :sandbox.
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
                           enable-parallel sandbox interop]
                    :or {max-iterations 20
                         max-depth 1
                         current-depth 0
                         eval-timeout-ms 30000
                         max-context-tokens 128000
                         enable-parallel false
                         interop :restricted}}]
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
                                     :bindings (merge bindings initial-bindings llm-bindings)
                                     :interop interop))
        ;; Standalone completion appends context-access docs — but ONLY when the
        ;; sandbox actually HAS the accessors: `create-sandbox` builds them
        ;; `(when clean-context)`, so on a nil-context call `(context-index)` and
        ;; its five siblings are unbound and the section is pure misdirection.
        ;; Asked of the live sandbox, not of the `context` arg: a caller may hand
        ;; us a ready `:sandbox` whose accessors came from ITS context, or one
        ;; that acquired them later via `update-context!`.
        ;;
        ;; The `nil?` and `:else` arms used to hold byte-identical copies of this
        ;; — with string? and map? handled between them, `:else` means "non-nil,
        ;; non-string, non-map", which is the nil behaviour anyway. One default now.
        system-msg (cond
                     (string? system-prompt) {:role "system" :content system-prompt}
                     (map? system-prompt)    system-prompt
                     :else
                     {:role "system"
                      :content (cond-> (prompt/build-system-prompt
                                        :max-iterations max-iterations
                                        :available (sandbox/bound-symbols sb)
                                        ;; From the SANDBOX, not the `interop`
                                        ;; opt: a passed-in `:sandbox` carries its
                                        ;; own level and the opt is ignored for it.
                                        ;; Forwarding nothing at all (the previous
                                        ;; state) pinned the Execution Model to
                                        ;; ":restricted", so a `:full` sandbox was
                                        ;; told "only WHITELISTED classes resolve
                                        ;; … there is no import to add" about
                                        ;; interop it actually had.
                                        :interop (:interop sb :restricted))
                                 (sandbox/context-accessors-bound? sb)
                                 (str "\n\n" prompt/context-access-prompt))})
        user-msg (prompt/build-initial-user-message query)
        ;; These three are `?`-suffixed on purpose. They were :enable-compaction,
        ;; :enable-structure-aware and :enable-budget — names that read like
        ;; config-schema keys in a grep but never were: each is derived from a
        ;; DIFFERENTLY named caller opt just below, and none is resolved through
        ;; agent config. The agent brick has real :enable-* schema keys, so the
        ;; old spelling cost a false hit every time someone searched for one.
        ;; The caller-facing vocabulary (:compaction-opts {:enable},
        ;; :feedback-opts {:structure-aware}, :budget-opts {:enable}) is
        ;; unchanged — only these internal bindings and the private cfg maps
        ;; they feed were renamed. See feature-flags-design.md §9 Q5.
        ;; Compaction config
        compaction? (get compaction-opts :enable false)
        compaction-trigger (get compaction-opts :trigger-iteration 5)
        compaction-keep-recent (get compaction-opts :keep-recent 3)
        compaction-max-summary (get compaction-opts :max-summary-chars 3000)
        ;; Feedback config
        structure-aware? (get feedback-opts :structure-aware false)
        feedback-max-chars (get feedback-opts :max-chars-per-block 10000)
        ;; Budget monitoring
        budget? (get budget-opts :enable false)
        budget-atom (when budget?
                      (budget/create-budget-monitor max-context-tokens max-iterations))
        ;; Shared loop state passed to all handlers
        loop-state {:messages (atom (or initial-messages [system-msg user-msg]))
                    :all-iterations (atom [])
                    :compaction-count (atom 0)
                    :usage-tracker usage-tracker
                    :on-iteration on-iteration
                    :max-context-tokens max-context-tokens
                    :sb sb}
        compaction-cfg {:compaction? compaction?
                        :compaction-trigger compaction-trigger
                        :compaction-keep-recent compaction-keep-recent
                        :compaction-max-summary compaction-max-summary
                        :budget-atom budget-atom}
        eval-cfg {:structure-aware? structure-aware?
                  :feedback-max-chars feedback-max-chars
                  :budget? budget?
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
