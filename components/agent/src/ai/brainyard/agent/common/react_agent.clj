;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.react-agent
  "ReAct (Reasoning and Acting) agent implementation.

   Implements the classic ReAct pattern with one LLM call per iteration
   (ThinkActAndEvaluate): reasoning + tool selection + observation +
   evaluation + answer synthesis all in one structured response.

   The multi-mode variant (ThinkAndSelectTools / ObserveAndEvaluate /
   FinalizeAnswer — two LLM calls per iteration plus a finalize call)
   was removed once M2/M3 lifted the stable context into the system
   message via :stable-keys. After that change, multi-mode bought no
   quality and cost a third more tokens per turn. Single-mode is now
   the only mode.

   Key differences from the cloudcast port:
   - Only one loop variant (ThinkActAndEvaluate).
   - Uses mulog tracing instead of Grain event-store.
   - Uses brainyard BT/DSPy/memory components."
  (:require [ai.brainyard.clj-llm.interface :as clj-llm :refer [defsignature defschemas]]
            [ai.brainyard.behavior-tree.interface :as bt :refer [st-memory-has-value?]]
            [ai.brainyard.behavior-tree.interface.protocol :as p]
            [ai.brainyard.clj-sandbox.interface :as sandbox]
            [ai.brainyard.agent.common.context-actions :as ctx-actions]
            [ai.brainyard.agent.common.schema :as acs]
            [ai.brainyard.agent.common.trace :as trace]
            [ai.brainyard.agent.core.context :as context]
            [ai.brainyard.agent.core.context-budget :as cb]
            [ai.brainyard.agent.core.context.formatters :as fmt]
            [ai.brainyard.agent.core.context.section-assembler :as sa]
            [ai.brainyard.agent.core.system-info :as sys-info]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.tool :as tool :refer [bind-tools defskill defagent]]
            [ai.brainyard.agent.common.commands :as common-cmds]
            [ai.brainyard.agent.common.agent-roster :as agent-roster]
            [ai.brainyard.agent.mcp.commands]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.bt :as agent-bt]
            [ai.brainyard.agent.core.agent :as agent]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.session :as session]
            [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.util.interface :as util]
            [clojure.string :as str]))

;; ============================================================================
;; Domain Schemas
;; ============================================================================

(defschemas domain
  {::thought [:string {:desc "Reasoning about the current state and what to do next"}]
   ::thoughts [:vector {:desc "History of thoughts"} ::thought]

   ::observation [:string {:desc "Interpreted summary of tool execution results"}]
   ::observations [:vector {:desc "History of observations"} ::observation]

   ::goal-achieved [:boolean {:desc "True if the goal has been achieved"}]
   ::next-user-prompt [:string {:desc "Set ONLY when goal-achieved=true (you are answering): a concise one-line follow-up the USER could send next to build on this answer. Phrase it as the user's own request (imperative, ~12 words max), not a question back to the user. Empty string when no useful follow-up exists or when not yet answering."}]
   ::request-for-information [:boolean {:desc "True if more information from user is required to achieve the goal"}]

   ::iteration-count [:int {:desc "Current iteration number"}]

   ::iterations [:vector {:desc "ReAct iterations"}
                 [:map {:desc "Thought-Actions-Observation-Evaluation"}
                  [:iteration ::iteration-count]
                  [:thought ::thought]
                  [:actions ::acs/tool-results]
                  [:observation ::observation]
                  [:evaluation [:map {:desc "Evaluation result"}
                                [:goal-achieved ::goal-achieved]]]]]

   ::think-and-act-result [:map {:desc "Combined thought and tool selection"}
                           [:thought ::thought]
                           [:tool-calls ::acs/tool-calls]]

   ::observe-and-evaluate-result [:map {:desc "Combined observation and evaluation"}
                                  [:observation ::observation]
                                  [:goal-achieved ::goal-achieved]
                                  [:request-for-information ::request-for-information]]

   ::think-act-evaluate-result [:map {:desc "Combined think, act, observe, evaluate, and answer in one step"}
                                [:thought ::thought]
                                [:tool-calls ::acs/tool-calls]
                                [:observation ::observation]
                                [:goal-achieved ::goal-achieved]
                                [:next-user-prompt ::next-user-prompt]
                                [:request-for-information ::request-for-information]
                                [:answer [:string {:desc "Final answer when goal-achieved is true, empty string otherwise"}]]]})

;; ============================================================================
;; DSPy Signatures
;; ============================================================================

;; Multi-mode signatures (ThinkAndSelectTools / ObserveAndEvaluate /
;; FinalizeAnswer) were removed alongside the multi-mode branch of
;; thinking-loop-subtree. After M2/M3, single-mode (ThinkActAndEvaluate
;; below) is strictly better — same observation/evaluation/answer
;; quality in one LLM call instead of two-plus-finalize.

(defsignature ThinkActAndEvaluate
  "You are a ReAct agent that performs reasoning, action selection, observation, evaluation, and answer synthesis in ONE step.

**STEP 1 - OBSERVE PREVIOUS RESULTS (skip on first iteration when no tool-results exist):**
- Summarize what you learned from the latest tool results
- Extract key information and connect results to the original question
- Note any errors or limitations
- Be concise (2-4 sentences)
- Set observation to empty string on first iteration when there are no tool-results

**STEP 2 - EVALUATE PROGRESS:**

SET goal-achieved = TRUE if:
- You have sufficient, reliable information to answer the question
- The observations provide clear insights that address the user's needs
- Additional investigation would not meaningfully improve the answer

SET goal-achieved = FALSE if:
- Critical information is still missing
- Observations were incomplete, contradictory, or error-prone
- You have a clear path to gathering more relevant information

SET request-for-information = TRUE if:
- User's inputs are required for clarification
- Goal cannot be achieved without more information from user

**STEP 3 - THINK (always):**
- Analyze: Review what you know from observations and history
- Identify Gaps: What specific information is missing?
- Plan Action: What concrete step will fill those gaps?
- Avoid Repetition: Don't repeat failed or completed approaches

**STEP 4 - ACT OR ANSWER:**

IF goal-achieved = TRUE:
- Set tool-calls to empty array []
- Provide a comprehensive answer that:
  1. Directly addresses the user's question
  2. Synthesizes insights from all thoughts, actions, observations
  3. Provides clear, actionable information with rich-text markdown format
  4. Acknowledges any limitations or uncertainties
- ALSO set next-user-prompt: one concise line (~12 words max) the USER could
  send next to build on this answer, phrased as the user's own imperative
  request (NOT a question back to them). Empty string if no useful follow-up.

IF goal-achieved = FALSE:
- Set answer to empty string
- Leave next-user-prompt as an empty string
- Select appropriate tools based on your reasoning:
  - Align tool selection with your thought
  - Avoid tools already used (check observations)
  - Provide precise tool arguments

**TOOL SELECTION GUIDELINES:**
1. **THOUGHT-TOOL ALIGNMENT:** Select tools that directly support the current thought
2. **REDUNDANCY AVOIDANCE:** Check observations to avoid repeating tools with same parameters
3. **PARALLEL EXECUTION:** Select multiple tools if they can run independently
4. **PARAMETER PRECISION:** Extract specific values from thought, follow tool specs
5. **ZERO-ARGUMENT TOOLS:** For functions with NO arguments, use empty array: \"tool-args\": []
6. **STRATEGIC TERMINATION:** Return `[]` if no tools needed or sufficient info gathered

**Context to Consider:**
- system-context / user-context — agent role, critical rules, tool-call format, bound tools, instruction, agent-context, tool-context, turn info, conversation history (system message via stable-keys).
- recalled-memory — long-term memory hits from prior sessions.
- thoughts, observations, tool-results, iterations — in-turn accumulators.
"
  {:inputs {:question ::acs/question
            :recalled-memory ::acs/recalled-memory
            :thoughts ::thoughts
            :observations ::observations
            :tool-results ::acs/tool-results
            :iterations ::iterations}
   :outputs {:tool-calls ::acs/tool-calls
             :observation ::observation
             :goal-achieved ::goal-achieved
             :next-user-prompt ::next-user-prompt
             :request-for-information ::request-for-information
             :answer ::acs/answer}})

;; ============================================================================
;; Section Content — ReAct-native (M2)
;; ============================================================================

(def ^:private react-role
  "You are a ReAct (Reasoning and Acting) agent. Each iteration you reason briefly,
select tools (or produce the final answer), interpret results, and decide
whether the goal is achieved. The runtime dispatches your tool-calls and feeds
the results back to you.")

(def ^:private react-critical-rules
  "## Critical Rules
- Populate `tool-calls` as a JSON array — never as a single object or a string.
- Use `[]` (empty array) for `tool-calls` when no tool is needed or you have
  enough information to answer.
- Avoid re-dispatching a tool with identical args if its prior result is
  already in `tool-results` / `observations`.
- When `goal-achieved` is true, you MUST also fill `answer` with the final
  markdown response. Do NOT leave `answer` blank when terminating the loop.
- When `goal-achieved` is false, leave `answer` empty and select tools (or
  produce a thought-only step) to make progress.
- Tool calls follow the exact JSON shape documented in the `## tool-calls
  Format` section of system-context.
- **Large/truncated tool results:** a big result is truncated inline and its
  full content saved to a temp file, marked `--- … TRUNCATED (… saved to: PATH) ---`
  with a `Recovery:` line. Do NOT re-call the same tool to get more — instead use
  the `read-file` tool on PATH (with `:lines`/`:offset`/`:limit` chunks per the
  marker) to recover the parts you need, then answer.")

(def ^:private react-tool-call-format
  "## tool-calls Format (JSON array)
Populate `tool-calls` as a JSON array to invoke one or more tools in a single iteration:
   [{\"tool-name\": \"<id>\", \"tool-args\": [{\"name\": \"<arg>\", \"value\": \"<val>\"}]}]
- For agent tools, pass `{\"name\": \"question\", \"value\": \"<your question>\"}`.
- For zero-argument tools, use `\"tool-args\": []`.
- The runtime dispatches each entry and appends results to `tool-results`.

### Bootstrap Tools (always bound)
1. **list-tools** — enumerate registered tools (commands, skills, agents, MCP
   tools all live in the same registry). Args: `type` (\"tool\"|\"command\"|
   \"skill\"|\"agent\"), `pattern` (regex on id/name/description). MCP tools are
   registered as `mcp$<server>$<tool>`, so filter by server with
   `:pattern \"^mcp\\\\$<server>\\\\$\"`.
2. **get-tool-info** — fetch full schema for a specific `tool-id`. Call this
   before invoking an unfamiliar tool so the `tool-args` shape is correct.")

(def ^:private react-footer
  "---
Remember: choose tools that *advance* the goal. When you have enough evidence,
set `goal-achieved` to true AND provide the final `answer` in the same response.")

;; ============================================================================
;; Context Assemblers — ReAct (M2)
;; ============================================================================

(defn- react-system-context
  "Assemble the stable :system-context string for ReAct.

   Sections (in display order):
     :role                — react-role
     :system-info         — host / workspace / LLM / session (stable per turn)
     :critical-rules      — ReAct contract
     :tool-call-format    — JSON shape + bootstrap tools (list-tools/get-tool-info)
     :tools               — agent-tools detail (format-agent-tools)
     :tool-context        — per-agent :tool-context overlay
     :instruction         — per-agent :instruction
     :agent-context       — per-agent :agent-context
     :footer              — react-footer

   Returns either `content` or `{:content :sections :order}` when
   `:return-breakdown?` is true (matches the CoAct pattern)."
  [{:keys [agent-tools tool-context instruction agent-context system-info
           brainyard-instructions]}
   & {:keys [return-breakdown?]}]
  (let [tools-block (fmt/format-agent-tools agent-tools)
        {:keys [user-instructions project-instructions]} brainyard-instructions
        sections
        (cond-> {:role             react-role
                 :critical-rules   react-critical-rules
                 :tool-call-format react-tool-call-format
                 :footer           react-footer}

          (and system-info (not (str/blank? system-info)))
          (assoc :system-info system-info)

          tools-block
          (assoc :tools (str "## Tools (bound for THIS turn)\n" tools-block))

          (and tool-context (not (str/blank? tool-context)))
          (assoc :tool-context (str "## Tool Context\n" tool-context))

          (and instruction (not (str/blank? instruction)))
          (assoc :instruction (str "## Instructions\n" instruction))

          (and agent-context (not (str/blank? agent-context)))
          (assoc :agent-context (str "## Agent Context\n" agent-context))

          ;; P4.6: BRAINYARD.md promoted above the cross-turn cache breakpoint.
          (and project-instructions (not (str/blank? project-instructions)))
          (assoc :project-instructions
                 (str "## Project Instructions (.brainyard/BRAINYARD.md)\n"
                      project-instructions))

          (and user-instructions (not (str/blank? user-instructions)))
          (assoc :user-instructions
                 (str "## User Instructions (~/.brainyard/BRAINYARD.md)\n"
                      user-instructions))

          ;; Base todo substrate — same checklist convention as coact (ReAct
          ;; has no Project Memory section, so this is net-new here).
          true
          (assoc :todo-substrate agent-roster/todo-substrate-protocol))
        section-order [:role :system-info
                       :critical-rules :tool-call-format
                       :tools :tool-context
                       :instruction :agent-context
                       :project-instructions :user-instructions
                       :todo-substrate
                       :footer]
        content (str/join "\n\n" (keep #(get sections %) section-order))]
    (if return-breakdown?
      {:content content :sections sections :order section-order}
      content)))

(defn- react-user-context
  "Assemble the volatile :user-context string for ReAct.

   Sections (in display order):
     :turn-info             — date/time + turn id (regenerated every turn)
     :conversation-history  — last N session messages, compact formatter

   P4.6: BRAINYARD.md (project / user instructions) now live in
   `react-system-context` (above the cross-turn cache breakpoint).

   Returns either `content` or `{:content :sections :order}` when
   `:return-breakdown?` is true."
  [{:keys [conversation turn-info]}
   & {:keys [return-breakdown?]}]
  (let [sections (cond-> {}
                   (and turn-info (not (str/blank? turn-info)))
                   (assoc :turn-info turn-info)

                   (seq conversation)
                   (assoc :conversation-history
                          (str "## Conversation History\n"
                               (fmt/format-conversation conversation))))
        section-order [:turn-info :conversation-history]
        content (if (seq sections)
                  (str/join "\n\n" (keep #(get sections %) section-order))
                  "")]
    (if return-breakdown?
      {:content content :sections sections :order section-order}
      content)))

;; ============================================================================
;; SectionAssembler — ReAct (M2)
;; ============================================================================

(defn- format-thoughts-block
  "Render :thoughts as a budget-trackable block. Numbered lines with
   length-capped thought text. Returns nil for an empty vector."
  [thoughts]
  (when (seq thoughts)
    (->> thoughts
         (map-indexed (fn [idx t]
                        (let [s (str t)
                              s (if (> (count s) 200) (str (subs s 0 200) "…") s)]
                          (str "(" (inc idx) ") " s))))
         (str/join "\n"))))

(defn- format-observations-block
  "Render :observations as a budget-trackable block. Same shape as
   format-thoughts-block."
  [observations]
  (when (seq observations)
    (->> observations
         (map-indexed (fn [idx o]
                        (let [s (str o)
                              s (if (> (count s) 200) (str (subs s 0 200) "…") s)]
                          (str "(" (inc idx) ") " s))))
         (str/join "\n"))))

(defn- format-iterations-block
  "Render ReAct :iterations as a budget-trackable block. Each entry
   becomes `(N) thought => action-summary | observation`.
   Different from CoAct's iteration shape — ReAct iterations carry
   :actions (tool-result records) and :observation (string)."
  [iterations]
  (when (seq iterations)
    (->> iterations
         (map-indexed
          (fn [idx {:keys [iteration thought actions observation]}]
            (let [n (or iteration (inc idx))
                  th (let [s (str thought)]
                       (if (> (count s) 200) (str (subs s 0 200) "…") s))
                  action-snip (when (seq actions)
                                (str/join "; "
                                          (map (fn [{:keys [tool-name]}]
                                                 (str tool-name))
                                               actions)))
                  obs-snip (let [s (str observation)]
                             (cond
                               (str/blank? s) ""
                               (> (count s) 150) (str (subs s 0 150) "…")
                               :else s))]
              (cond-> (str "(" n ") " th)
                action-snip (str " => " action-snip)
                (not (str/blank? obs-snip)) (str " | " obs-snip)))))
         (str/join "\n"))))

(defn- summarize-iterations-deterministic
  "Pure-Clojure recap of older ReAct iterations. Used by
   :collapse-iterations to replace the dropped iteration prefix with
   a single summary entry."
  [iterations]
  (or (format-iterations-block iterations) ""))

(defn- react-strategies
  "Per-turn compaction strategies for the ReAct section-budget loop.

   Strategies:
     :shrink-conversation       — drop oldest 2 conversation messages.
     :keep-last-n-thoughts      — drop oldest thought; stops at floor
                                  (default 3) so the LLM sees recent
                                  reasoning even under budget pressure.
     :keep-last-n-observations  — drop oldest observation; same floor.
     :collapse-iterations       — keep last 3 iterations verbatim,
                                  replace older with a single summary
                                  iteration carrying a deterministic
                                  recap in its :thought field.

   Floors come from agent config:
     :react-keep-thoughts-n     (default 3)
     :react-keep-observations-n (default 3)
     :react-keep-iterations-n   (default 3)"
  [st-memory]
  (let [a                   proto/*current-agent*
        thoughts-floor      (config/get-config a :react-keep-thoughts-n)
        observations-floor  (config/get-config a :react-keep-observations-n)
        iterations-keep-n   (config/get-config a :react-keep-iterations-n)]
    {:shrink-conversation
     (fn [secs]
       (let [conv (or (:conversation @st-memory) [])]
         (if (empty? conv)
           (dissoc secs :conversation-history)
           (let [trimmed (vec (drop 2 conv))]
             (swap! st-memory assoc :conversation trimmed)
             (if (seq trimmed)
               (assoc secs :conversation-history
                      (str "## Conversation History\n"
                           (fmt/format-conversation trimmed)))
               (dissoc secs :conversation-history))))))

     :keep-last-n-thoughts
     (fn [secs]
       (let [thoughts (or (:thoughts @st-memory) [])]
         (if (<= (count thoughts) thoughts-floor)
           ;; At floor — return unchanged. enforce drops the budget
           ;; slot but the LLM still sees the floor count via DSPy.
           secs
           (let [trimmed (vec (rest thoughts))]
             (swap! st-memory assoc :thoughts trimmed)
             (assoc secs :thoughts
                    (or (format-thoughts-block trimmed) ""))))))

     :keep-last-n-observations
     (fn [secs]
       (let [observations (or (:observations @st-memory) [])]
         (if (<= (count observations) observations-floor)
           secs
           (let [trimmed (vec (rest observations))]
             (swap! st-memory assoc :observations trimmed)
             (assoc secs :observations
                    (or (format-observations-block trimmed) ""))))))

     :collapse-iterations
     (fn [secs]
       (let [iters (or (:iterations @st-memory) [])]
         (if (<= (count iters) iterations-keep-n)
           secs
           (let [recent (vec (take-last iterations-keep-n iters))
                 older  (vec (drop-last iterations-keep-n iters))
                 summary-text (summarize-iterations-deterministic older)
                 new-iters (into [{:iteration 0
                                   :thought summary-text
                                   :actions []
                                   :observation ""
                                   :evaluation {:goal-achieved false
                                                :request-for-information false}}]
                                 recent)]
             (swap! st-memory assoc :iterations new-iters)
             (assoc secs :iterations
                    (or (format-iterations-block new-iters) ""))))))}))

(defrecord ReActAssembler []
  sa/SectionAssembler
  (sections [_ state]
    (let [sys (react-system-context
               (select-keys state [:agent-tools :tool-context :instruction
                                   :agent-context :system-info
                                   :brainyard-instructions])
               :return-breakdown? true)
          usr (react-user-context
               (select-keys state [:conversation :turn-info])
               :return-breakdown? true)]
      (merge (:sections sys) (:sections usr))))
  (system-order [_]
    [:role :system-info
     :critical-rules :tool-call-format
     :tools :tool-context
     :instruction :agent-context
     :project-instructions :user-instructions
     :footer])
  (user-order [_]
    [:turn-info :conversation-history])
  (policies [_] cb/default-section-policies)
  (strategies [_ st-memory] (react-strategies st-memory)))

(def react-assembler
  "Stateless ReAct section assembler. All per-turn inputs flow through
   protocol method calls."
  (->ReActAssembler))

;; ============================================================================
;; Init Action — ReAct (M2)
;; ============================================================================

(defn react-init-action
  "BT action: build the ReAct :system-context and :user-context strings,
   enforce the token budget, and stash everything in st-memory so the
   dspy-actions (think-and-select-tools, observe-and-evaluate,
   think-act-and-evaluate, finalize-answer) can lift them into the
   system message via :stable-keys #{:system-context :user-context}.

   Idempotent within a turn — fires once at the top of each turn."
  [{:keys [st-memory agent opts] :as context}]
  (let [st @st-memory
        instruction   (if agent
                        (context/assemble-field agent :system-context :instruction)
                        (:instruction st))
        agent-context (if agent
                        (context/assemble-field agent :system-context :agent-context)
                        (:agent-context st))
        tool-context  (if agent
                        (context/assemble-field agent :system-context :tool-context)
                        (:tool-context st))

        ;; Load brainyard instructions once per turn.
        agent-dirs (config/get-config agent :dirs)
        brainyard-instructions (when agent-dirs
                                 (try (config/load-brainyard-instructions agent-dirs)
                                      (catch Exception _ nil)))

        turn-id     (:turn-id st)
        total-turns (:total-turns st)
        cfg-snap (config/get-config-snapshot agent)

        system-info-text (sys-info/build-system-info-section
                          :agent agent
                          :depth (or (:depth context) 0)
                          :parent-agent-id (:parent-agent-id st))
        turn-info-text   (sys-info/build-turn-info-section
                          :turn-id turn-id
                          :total-turns total-turns)

        assembler react-assembler
        assembler-state {:agent-tools     (:tools st)
                         :tool-context    tool-context
                         :instruction     instruction
                         :agent-context   agent-context
                         :system-info     system-info-text
                         :brainyard-instructions brainyard-instructions
                         :conversation           (:conversation st)
                         :turn-info       turn-info-text}
        merged-sections (sa/sections assembler assembler-state)
        sys-order       (sa/system-order assembler)
        usr-order       (sa/user-order assembler)
        merged-order    (sa/order assembler)
        strategies      (sa/strategies assembler st-memory)

        budget-enabled? (get cfg-snap :enable-context-budget true)
        budget-input
        {:max-context-tokens (or (get cfg-snap :max-context-tokens) 128000)
         :max-output-tokens  (or (get-in opts [:lm-config :max-tokens])
                                 (:max-tokens (config/get-config agent :lm-config))
                                 4096)
         :safety-ratio       (or (get cfg-snap :context-budget-safety-ratio)
                                 0.10)}
        budget-tokens (cb/model->budget budget-input)

        enforced (if budget-enabled?
                   (cb/enforce {:sections   merged-sections
                                :order      merged-order
                                :budget     budget-tokens
                                :policies   (sa/policies assembler)
                                :strategies strategies})
                   {:sections     merged-sections
                    :order        merged-order
                    :total-tokens (cb/total-tokens merged-sections merged-order)
                    :budget       budget-tokens
                    :compactions  []
                    :over-budget? false})

        system-context (cb/compose (:sections enforced) sys-order)
        user-context   (cb/compose (:sections enforced) usr-order)

        prompt-token-breakdown
        (clj-llm/build-token-breakdown
         (select-keys (:sections enforced) merged-order))]

    (swap! st-memory assoc
           :system-context         system-context
           :user-context           user-context
           :prompt-token-breakdown prompt-token-breakdown
           ;; Per-turn reset of the degenerate-iteration guard (see
           ;; normalize-evaluation-action).
           :consecutive-empty      0
           :react-degenerate-stop  false
           ;; Stash for M3 rebudget action (lands in a follow-up commit).
           :cached-sections        (:sections enforced)
           :sys-order              sys-order
           :usr-order              usr-order
           :merged-order           merged-order
           :budget-tokens          (:budget enforced))

    (when agent
      (hooks/fire! :agent.context/budgeted
                   {:agent          agent
                    :phase          :init
                    :total-tokens   (:total-tokens enforced)
                    :budget         (:budget enforced)
                    :section-tokens (cb/section-tokens (:sections enforced)
                                                       merged-order)
                    :compactions    (:compactions enforced)
                    :over-budget?   (:over-budget? enforced)}))

    (mulog/log ::react-init
               :agent-id (when agent (:agent-id agent))
               :turn-id turn-id
               :total-turns total-turns
               :system-context-chars (count system-context)
               :user-context-chars   (count user-context)
               :budget-tokens        (:budget enforced)
               :prompt-tokens        (:total-tokens enforced)
               :budget-compactions   (count (:compactions enforced))
               :over-budget?         (:over-budget? enforced))

    bt/success))

;; ============================================================================
;; Rebudget Action — ReAct (M3)
;; ============================================================================

(defn react-rebudget-action
  "BT action: per-iteration token-budget re-enforcement for ReAct.

   ReAct grows three accumulators each iteration: :thoughts,
   :observations, :iterations. To prevent the prompt from quietly
   drifting over budget mid-turn, this action:

     1. Refreshes the three accumulator section texts from current
        st-memory state (budget-only slots — not composed into the
        prompt strings).
     2. Re-runs cb/enforce with the same orders + strategies init
        used. The :keep-last-n-* / :collapse-iterations strategies
        trim each accumulator to its configured floor before the
        :shrink-conversation strategy kicks in.
     3. Re-composes :system-context / :user-context and stores them
        back in st-memory.
     4. Fires :agent.context/budgeted with :phase :rebudget.

   Cadence is config key `:rebudget-every-n-iter` (default 10)."
  [{:keys [st-memory agent]}]
  (let [st @st-memory
        rebudget-n (config/get-config agent :rebudget-every-n-iter)
        iter-count (:iteration-count st 0)
        budget-enabled? (config/get-config agent :enable-context-budget)
        cached-sections (:cached-sections st)
        sys-order (:sys-order st)
        usr-order (:usr-order st)
        merged-order (:merged-order st)
        budget (:budget-tokens st)]
    (when (and budget-enabled?
               cached-sections sys-order usr-order merged-order budget
               (pos? iter-count)
               (zero? (mod iter-count (max 1 rebudget-n))))
      (let [thoughts-text (or (format-thoughts-block (:thoughts st)) "")
            observations-text (or (format-observations-block (:observations st)) "")
            iterations-text (or (format-iterations-block (:iterations st)) "")
            ;; Budget-only slots — appended to enforce order but never
            ;; composed into the prompt strings.
            budget-order (into (vec merged-order)
                               [:thoughts :observations :iterations])
            sections-with-accumulators
            (cond-> cached-sections
              (seq thoughts-text)     (assoc :thoughts thoughts-text)
              (seq observations-text) (assoc :observations observations-text)
              (seq iterations-text)   (assoc :iterations iterations-text))
            strategies (sa/strategies react-assembler st-memory)
            enforced (cb/enforce
                      {:sections   sections-with-accumulators
                       :order      budget-order
                       :budget     budget
                       :policies   (sa/policies react-assembler)
                       :strategies strategies})
            new-sys (cb/compose (:sections enforced) sys-order)
            new-usr (cb/compose (:sections enforced) usr-order)
            new-breakdown (clj-llm/build-token-breakdown
                           (select-keys (:sections enforced) budget-order))]
        (swap! st-memory assoc
               :system-context         new-sys
               :user-context           new-usr
               :cached-sections        (:sections enforced)
               :prompt-token-breakdown new-breakdown)
        (when agent
          (hooks/fire! :agent.context/budgeted
                       {:agent          agent
                        :phase          :rebudget
                        :iteration      iter-count
                        :total-tokens   (:total-tokens enforced)
                        :budget         (:budget enforced)
                        :section-tokens (cb/section-tokens
                                         (:sections enforced) budget-order)
                        :compactions    (:compactions enforced)
                        :over-budget?   (:over-budget? enforced)}))))
    bt/success))

;; ============================================================================
;; Thinking Loop (single-mode only)
;; ============================================================================

(def ^:private max-consecutive-empty
  "Stop the ReAct loop after this many consecutive degenerate iterations — the
   LLM returned nothing usable (empty structured-output completion), e.g. after
   an oversized tool result wedged the model. Bounds a stuck loop so it can't
   burn the whole iteration budget; the post-loop ensure-answer then backfills."
  2)

(defn- normalize-evaluation-action
  "Runs right after ThinkActAndEvaluate. Coerces the boolean evaluation fields to
   STRICT booleans: structured-output can return \"\" (empty string — which is
   truthy in Clojure) when the LLM yields a degenerate/empty completion, e.g.
   after an oversized tool result. Left as-is, \"\" would falsely satisfy the
   loop-exit condition `(or goal-achieved request-for-information)` and end the
   turn with no answer. Also counts consecutive degenerate iterations (no answer,
   no tool-calls, no observation, goal not achieved) and flags `:react-degenerate-stop`
   so the loop terminates instead of spinning to the ceiling."
  [{:keys [st-memory]}]
  (swap! st-memory
         (fn [m]
           (let [ga          (true? (:goal-achieved m))
                 rfi         (true? (:request-for-information m))
                 degenerate? (and (not ga) (not rfi)
                                  (str/blank? (str (:answer m)))
                                  (empty? (:tool-calls m))
                                  (str/blank? (str (:observation m))))
                 empties     (if degenerate? (inc (or (:consecutive-empty m) 0)) 0)]
             (assoc m
                    :goal-achieved           ga
                    :request-for-information rfi
                    :consecutive-empty       empties
                    :react-degenerate-stop   (>= empties max-consecutive-empty)))))
  bt/success)

(defn- fallback-answer
  "Construct a partial answer from observations/thoughts when LLM synthesis fails."
  [st-memory agent error-prefix]
  (let [{:keys [observations thoughts iterations]} @st-memory
        body (or (last observations)
                 (last thoughts)
                 "Processing completed but could not generate a final summary.")
        answer (str error-prefix body)]
    (swap! st-memory assoc :answer answer)
    (trace/add-trace-event agent {:question (:question @st-memory)
                                  :iterations iterations
                                  :answer answer})
    bt/success))

(defn- trace-agent
  "Send a trace message to the agent's session thinking, when agent is present."
  [agent depth content]
  (when agent
    (proto/update-session-data
     agent {:trace {:agent-id (:agent-id agent) :depth depth :content content}})))

(defn- truncate-result
  "Truncate a tool result for the LLM. Oversized results spill to a temp file
   with a recovery marker (read-file guidance) — the same large-results playbook
   CoAct uses — instead of an opaque inline ellipsis. This keeps a giant blob out
   of the context window (which can wedge a structured-output completion into an
   empty/degenerate response) and gives the model a path to recover the full
   content. Returns {:result-str truncated :simplified brief-summary}."
  [result]
  (let [raw-str    (cond (nil? result)    ""
                         (string? result) result
                         :else            (pr-str result))
        max-chars  (config/get-config :max-output-chars)
        result-str (sandbox/truncate-to-file raw-str max-chars "tool-result"
                                             :label "tool-result")
        simplified (util/abbreviate result-str 100)]
    {:result-str result-str :simplified simplified}))

(defn- hook-blocked-result?
  "True when a raw tool result is the synthetic `{:hook-blocked true ...}`
   sentinel produced by `agent.core.tool/dispatch-with-hooks` on a `:block`
   verdict from a `:agent.tool-use/pre` hook (e.g. the loop guard)."
  [raw]
  (and (map? raw) (true? (:hook-blocked raw))))

(defn tool-calls-action
  "Behavior tree action that executes tool calls selected by the LLM.

   Reads :tool-calls from st-memory and dispatches each via `tool/call-tool`
   (which handles permission, registry/fn-map resolution, hooks, visibility,
   and agent depth/circular guards). For each call, this action then truncates
   the result, traces it on the agent session, and pushes a
   {:tool-name :tool-args :tool-result} entry onto st-memory :tool-results.
   After the round, the new entries are appended to working-memory conversation.

   `:agent.tool-use/pre` hook integration: when a hook returns `:block` (e.g. the
   default loop guard), `tool/call-tool` returns a `{:hook-blocked true
   :reason :answer :by}` sentinel. This action captures the first such
   sentinel and, if it carries an `:answer`, lifts it to st-memory and
   flips `:goal-achieved true` so the BT loop exits cleanly.

   Context keys:
     :st-memory - Short-term memory atom containing :tool-calls, :tools, :tools-fn-map
     :agent     - Agent instance
     :opts      - Options map with :max-tool-calls (default 30)

   Returns: bt/success or bt/failure"
  [{:keys [st-memory agent opts depth] :as _context}]
  (let [depth (or depth 0)
        {:keys [tool-calls tools tools-fn-map]} @st-memory
        {:keys [max-tool-calls] :or {max-tool-calls 30}} opts
        iteration (:iteration-count @st-memory)
        batch (vec (take max-tool-calls tool-calls))
        !blocked (atom nil)
        prev-results-count (count (:tool-results @st-memory))]
    (trace-agent agent depth (format "%d tool-calls dispatched (max: %d)"
                                     (count tool-calls) max-tool-calls))
    (let [fast-eval-ms (config/get-config agent :fast-eval-timeout-ms)
          auto-bg-ms   (config/get-config agent :auto-background-timeout-ms)
          call-opts {:agent agent :tools tools :tools-fn-map tools-fn-map
                     :fast-eval-ms (when (and fast-eval-ms (pos? fast-eval-ms))
                                     fast-eval-ms)
                     :auto-bg-ms auto-bg-ms
                     :from-iteration iteration}]
      (when (seq batch)
        (hooks/fire! :agent.tool-calls/pre
                     {:agent agent :iteration iteration :calls batch})
        (let [results
              (doall
               (pmap (fn [{:keys [tool-name tool-args]}]
                       (trace-agent agent depth (format "calling **%s** with args %s" tool-name (pr-str tool-args)))
                       (let [result (tool/call-tool-with-fast-eval
                                     tool-name tool-args call-opts)
                             {:keys [result-str simplified]} (truncate-result result)]
                         {:tool-name tool-name :tool-args tool-args
                          :tool-result result-str :simplified simplified
                          :raw result}))
                     batch))]
          (doseq [{:keys [tool-name simplified raw]} results]
            (when (and (hook-blocked-result? raw) (nil? @!blocked))
              (reset! !blocked raw))
            (trace-agent agent depth (format "call result: %s" simplified)))
          (swap! st-memory update :tool-results
                 (fnil into [])
                 (mapv #(dissoc % :simplified :raw) results)))))
    ;; Append new tool-results to !session :messages so the canonical
    ;; conversation stream shows tool turns alongside user/assistant.
    (when-let [!session (some-> agent :!session)]
      (let [st          @st-memory
            new-results (subvec (or (:tool-results st) []) prev-results-count)]
        (when (seq new-results)
          (swap! !session session/add-message
                 {:role "tool" :content (pr-str new-results)}))))
    (when (seq batch)
      (let [new-results (subvec (or (:tool-results @st-memory) []) prev-results-count)]
        (hooks/fire! :agent.tool-calls/post
                     {:agent agent :iteration iteration
                      :calls batch :results new-results})))
    (when-let [b @!blocked]
      (trace-agent agent depth
                   (format "agent.tool-use/pre hook blocked dispatch (%s) — terminating"
                           (or (:reason b) "no reason")))
      (swap! st-memory assoc
             :goal-achieved true
             :answer (or (:answer b)
                         (str "*(Tool dispatch blocked: " (:reason b) ")*"))))
    ;; Always succeed: a tool error lands in :tool-results as an observation
    ;; the LLM adapts to next iteration — it must NOT abort the ReAct loop.
    ;; (The old :call-failures counter was never incremented; removed so the
    ;; return doesn't imply failure-on-tool-error semantics.)
    bt/success))

(defn thinking-loop-subtree
  "ReAct thinking loop. One LLM call per iteration via ThinkActAndEvaluate;
   thinking, tool selection, observation, evaluation, and answer
   synthesis happen in a single structured response. When the LLM sets
   goal-achieved=true, the answer rides the same response — no separate
   FinalizeAnswer step needed.

   The assembled prompt is kept under the token budget by the
   deterministic per-iteration rebudget pass (see context-budget)."
  [max-iterations]
  (let [kw (fn [suffix] (keyword (str "react." (namespace suffix)) (name suffix)))]
    [:sequence
     {:id (kw :sequence/thinking-loop)}

     [:fallback
      {:id (kw :fallback/repeat-guard)}

      [:repeat
       {:id (kw :repeat/think-act-evaluate)
        :max-n max-iterations
        :condition-fn (fn [{:keys [st-memory]}]
                        ;; Strict booleans only — a degenerate "" goal-achieved
                        ;; (truthy in Clojure) must NOT exit the loop. The
                        ;; degenerate-stop flag (set by normalize-evaluation after
                        ;; N empty iterations) is the bounded escape hatch.
                        (let [{:keys [goal-achieved request-for-information
                                      react-degenerate-stop]} @st-memory]
                          (or (true? goal-achieved)
                              (true? request-for-information)
                              (true? react-degenerate-stop))))}

       [:sequence
        {:id (kw :sequence/iteration)}

        [:action
         {:id (kw :action/inc-iteration-count)}
         (fn [{:keys [st-memory agent]}]
           ;; Per-iteration reset: clear the LLM-produced :tool-calls and
           ;; :observation so a thinking-only iteration (where dspy omits them)
           ;; can't re-dispatch last turn's calls or re-record its observation.
           ;; The ThinkActAndEvaluate dspy-action below overwrites both with
           ;; fresh outputs. Mirrors CoAct's per-iteration reset.
           (swap! st-memory #(-> % (update :iteration-count (fnil inc 0))
                                 (assoc :display-stage :iteration-start
                                        :tool-calls []
                                        :observation ""
                                        ;; Reset the answer-channel follow-up so a
                                        ;; non-answering iteration (goal-achieved
                                        ;; false, dspy omits it) can't leak a stale
                                        ;; value into the turn-completion display.
                                        :next-user-prompt "")))
           (let [harvest! @(requiring-resolve 'ai.brainyard.agent.common.coact-agent/harvest-pending-tasks!)
                 roster!  @(requiring-resolve 'ai.brainyard.agent.common.coact-agent/inject-in-flight-roster!)]
             (harvest! st-memory agent)
             (roster! st-memory agent))
           bt/success)]

        [:action
         {:id (kw :action/await-pending)}
         @(requiring-resolve 'ai.brainyard.agent.common.coact-agent/coact-await-pending-action)]

        ;; M3: deterministic per-iteration re-enforce of the section
        ;; budget. Trims accumulators in cheap-strategy order to keep
        ;; the assembled prompt under the token budget.
        [:action
         {:id (kw :action/rebudget)}
         react-rebudget-action]

        ;; One LLM call: ThinkActAndEvaluate.
        [:action
         {:id (kw :action/think-act-and-evaluate)
          :signature #'ThinkActAndEvaluate
          :operation :chain-of-thought
          :stable-keys #{:system-context :user-context}
          :debug {:source :reasoning}}
         bt/dspy]

        ;; Coerce evaluation booleans to strict booleans and track degenerate
        ;; (empty) iterations — guards against an oversized-tool-result-induced
        ;; empty completion falsely terminating the loop with no answer.
        [:action
         {:id (kw :action/normalize-evaluation)}
         normalize-evaluation-action]

        [:action
         {:id (kw :action/display-think)}
         (fn [{:keys [st-memory]}]
           (swap! st-memory assoc :display-stage :think)
           bt/success)]

        [:action
         {:id (kw :action/record-thought)}
         (fn [{:keys [st-memory]}]
           (let [thought (get @st-memory :last-reasoning)]
             (when thought
               (swap! st-memory update :thoughts (fnil conj []) thought))
             bt/success))]

        [:action
         {:id (kw :action/record-observation)}
         (fn [{:keys [st-memory]}]
           (let [observation (get @st-memory :observation)]
             (when (and observation (not (str/blank? observation)))
               (swap! st-memory update :observations (fnil conj []) observation))
             bt/success))]

        [:action
         {:id (kw :action/display-tool-calls)}
         (fn [{:keys [st-memory]}]
           (when (seq (:tool-calls @st-memory))
             (swap! st-memory assoc :display-stage :tool-calls))
           bt/success)]

        [:action
         {:id (kw :action/call-tools)}
         (fn [{:keys [st-memory] :as context}]
           (let [{:keys [goal-achieved tool-calls]} @st-memory]
             (if (or goal-achieved (empty? tool-calls))
               bt/success
               (tool-calls-action context))))]

        [:action
         {:id (kw :action/display-tool-results)}
         (fn [{:keys [st-memory]}]
           (when (seq (:tool-results @st-memory))
             (swap! st-memory assoc :display-stage :tool-results))
           bt/success)]

        [:action
         {:id (kw :action/display-observe)}
         (fn [{:keys [st-memory]}]
           (swap! st-memory assoc :display-stage :observe)
           bt/success)]

        [:action
         {:id (kw :action/finalize-iteration)}
         (fn [{:keys [st-memory]}]
           (let [{:keys [iteration-count last-reasoning tool-calls tool-results observation
                         goal-achieved request-for-information]} @st-memory
                 actions (vec (take-last (count tool-calls) tool-results))
                 actions (mapv (fn [tr]
                                 (update tr :tool-result #(util/abbreviate (str %) 1024 1024)))
                               actions)]
             (swap! st-memory update :iterations (fnil conj [])
                    {:iteration iteration-count
                     :thought last-reasoning
                     :actions actions
                     :observation observation
                     :evaluation {:goal-achieved goal-achieved
                                  :request-for-information request-for-information}})
             bt/success))]

        [:action
         {:id (kw :action/trace-on-answer)}
         (fn [{:keys [st-memory agent]}]
           (let [{:keys [goal-achieved question iterations answer]} @st-memory]
             (when (and goal-achieved answer)
               (trace/add-trace-event agent {:question question
                                             :iterations iterations
                                             :answer answer})))
           bt/success)]]]

      ;; Fallback child: absorb repeat failure
      [:action
       {:id (kw :action/repeat-fallback)}
       (fn [{:keys [st-memory agent]}]
         (fallback-answer st-memory agent
                          "*(Note: Processing encountered an error. Below are the collected findings.)*\n\n"))]]]))

;; ============================================================================
;; Behavior Tree Top-Level (unified)
;; ============================================================================

(defn react-behavior-tree
  "Build the ReAct behavior tree. Single-mode only — see
   `thinking-loop-subtree` docstring."
  [max-iterations]
  (let [kw (fn [suffix] (keyword (str "react." (namespace suffix)) (name suffix)))]
    [:sequence
     {:id (kw :sequence/main)}

     [:condition
      {:id (kw :condition/st-memory.question)
       :path [:question]
       :schema ::acs/question}
      st-memory-has-value?]

     [:action
      {:id (kw :action/prepare-conversation)}
      ctx-actions/prepare-conversation-action]

     [:action
      {:id (kw :action/prepare-recalled-memory)}
      ctx-actions/prepare-recalled-memory-action]

     ;; M2: assemble :system-context / :user-context and enforce the
     ;; section-token budget. The dspy-actions below lift these two
     ;; fields into the system message via :stable-keys.
     [:action
      {:id (kw :action/init)}
      react-init-action]

     [:fallback
      {:id (kw :fallback/check-context)}

      (thinking-loop-subtree max-iterations)]

     ;; Guarantee a non-blank answer. The loop's :repeat returns :success on
     ;; both a normal goal-achieved exit AND a degenerate-stop / max-iterations
     ;; exit — the repeat-fallback only fires on :failure, so a turn that stops
     ;; without synthesizing an answer (e.g. after an oversized tool result
     ;; wedged the model) would otherwise fail the answer-present condition
     ;; below. Backfill a best-effort answer from collected findings.
     [:action
      {:id (kw :action/ensure-answer)}
      (fn [{:keys [st-memory agent]}]
        (when (str/blank? (str (:answer @st-memory)))
          (fallback-answer st-memory agent
                           "*(Note: stopped without a synthesized final answer. Collected findings below.)*\n\n"))
        bt/success)]

     [:condition
      {:id (kw :condition/st-memory.answer)
       :path [:answer]
       :schema ::acs/answer
       :debug {:source :st-memory}}
      st-memory-has-value?]

     [:action
      {:id (kw :action/maintain-conversation)}
      trace/default-maintain-conversation]]))

;; ============================================================================
;; Skill: ReAct Thinking Loop
;; ============================================================================

(defskill react-skill$thinking-loop
  "ReAct thinking skill for question-answering in Think-Acts-Observation-Evaluation loop"
  (fn [& {:keys [_deftool$id _deftool$input-schema _deftool$output-schema
                 question instruction skill-tools
                 tool-context agent-instance max-iterations]
          :or {skill-tools {}
               max-iterations 10}}]
    (let [agent (or agent-instance proto/*current-agent*)
          [tools tools-fn-map] (bind-tools skill-tools)
          ;; M2: wrap the bare thinking loop with prepare-conversation /
          ;; prepare-recalled-memory / react-init-action so the
          ;; dspy-actions see populated :system-context / :user-context
          ;; via :stable-keys. Mirrors the main BT's pre-loop steps.
          tree [:sequence
                {:id :react-skill.sequence/main}
                [:action {:id :react-skill.action/prepare-conversation}
                 ctx-actions/prepare-conversation-action]
                [:action {:id :react-skill.action/prepare-recalled-memory}
                 ctx-actions/prepare-recalled-memory-action]
                [:action {:id :react-skill.action/init}
                 react-init-action]
                (thinking-loop-subtree max-iterations)]]
      (agent-bt/skill-behavior-fn*
       :skill-id _deftool$id
       :agent agent
       :tree tree
       :merge-inputs {:question question :instruction instruction
                      :tools tools :tools-fn-map tools-fn-map :tool-context tool-context}
       :dirty-keys (concat (mapv tool/malli-map-entry-key (tool/malli-map-entries _deftool$input-schema))
                           (mapv tool/malli-map-entry-key (tool/malli-map-entries _deftool$output-schema)))
       :output-fn (fn [result st-memory-state]
                    (if (= result bt/success)
                      (:answer st-memory-state)
                      (format "Failed at %s" (:last-failure st-memory-state)))))))
  :tool-use-control {:visibility :hidden}
  :input-schema [:map [:question ::acs/question]]
  :output-schema [:map [:answer ::acs/answer]])

;; ============================================================================
;; Shared Agent Configuration
;; ============================================================================

(def ^:private react-instruction
  "Instruction string for the react agent. Single-mode only.
   Deliberately minimal: the tool-call JSON format, bootstrap discovery tools,
   background-task tools, and loop/goal-achieved rules already live in the
   always-present system-context sections (react-tool-call-format,
   react-critical-rules, react-role) and the ThinkActAndEvaluate signature.
   This carries ONLY the non-redundant nuances."
  "
Use the ReAct framework: each step, reason about the current state, then either
call tools to make progress or give the final answer — observing prior results
and judging whether the goal is met. The tool-call JSON format and the bootstrap
discovery tools (list-tools / get-tool-info) are documented in the system context
above; this section adds only what's specific:

- Prefer the bound tools listed below when they cover the goal; list-tools /
  get-tool-info are discovery aids for reaching anything else registered — not
  every-iteration calls.
- Runtime config: invoke agent-runtime$config (no args to view settings; :key and
  :value to change one, effective from the next question round).
")

;; ============================================================================
;; Agent Registration
;; ============================================================================

;; defagent is a macro — :inputs/:outputs/:agent-tools must be literal maps (parsed at macro-expand time).
;; Shared config extracted above as defs; spliced inline here via the ~@ pattern in defagent → deftool.

(defagent react-agent
  "ReAct (Reasoning and Acting) agent — one LLM call per iteration via ThinkActAndEvaluate."
  agent/run-agent
  :bt-factory (fn [{:keys [max-iterations]}]
                (react-behavior-tree max-iterations))
  :tool-use-control {}
  :input-schema [:map
                 [:question [:string {:desc "User request or question to answer"}]]
                 [:agent-context {:optional true} [:string {:desc "Additional contextual information"}]]]
  :output-schema [:map
                  [:answer [:string {:desc "Final answer to the user's question"}]]]
  ;; Shared coact/react roster — defined once in agent-roster so it can't
  ;; drift. react has no code channel, so this roster IS its advertised tool
  ;; surface and renders verbose (coact renders the same roster compactly).
  :agent-tools agent-roster/default-agent-roster
  :instruction react-instruction
  ;; Tool discovery/invocation/background-task guidance already lives in the
  ;; always-present `## tool-calls Format` system-context section
  ;; (react-tool-call-format); the per-agent :tool-context carries only the
  ;; NET-NEW knowledge-recall vs. operational-trace guidance.
  :tool-context common-cmds/operational-recall-guidance)
