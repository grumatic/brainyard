;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.bt
  "Behavior tree integration for agent execution.

   Provides:
   - BT building from config with st-memory
   - BT execution with memory recall/remember cycle
   - Skill behavior function factory for sub-BTs
   - Extended BT nodes: tracing-aware overrides, repeat decorator,
     user interaction actions, and visualization utilities

   The extended nodes expect an `agent` object in the context that provides:
   - .update-session-data [agent data]
   - .check-run-cancelled? [agent]
   - .create-action-promise [agent action-id]
   - .get-action-permission [agent action-id]
   - .set-action-permission [agent action-id value]
   - .get-bt-st-memory [agent]

   When no agent is present, the base nodes from nodes.clj handle execution.

   Depends on the behavior-tree component (ai.brainyard.behavior-tree.interface)."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.behavior-tree.interface :as bt]     ;; force nodes.clj to load BEFORE our overrides
            [ai.brainyard.behavior-tree.interface.protocol :as p]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog]))

;; Analytics is not a hard dep of the agent component — cache with delay.
(def ^:private !analyze-session-fn
  (delay (try (requiring-resolve 'ai.brainyard.analytics.interface/analyze-session)
              (catch Exception _ nil))))

;; The previous :conversation working-memory buffer was removed in the
;; unified-store refactor — `agent.core.agent/ask` already writes user
;; and assistant messages to !session :messages, and react-agent's
;; tool-result tracking writes there too. The working-memory copy was
;; duplicative.

;; ============================================================================
;; Streaming Chunk Handler
;; ============================================================================

(defn chunk-factory-handler
  "Default streaming-chunk factory used by BT LLM-calling actions.

   Takes an event map `{:agent :st-memory-atom :node-id}` and returns a
   per-node on-chunk fn suitable for passing as `:on-chunk` to
   `clj-llm/chat-completion`. The returned handler writes incremental deltas
   into the agent's st-memory under `:llm-streaming-text`, which UIs (TUI,
   web bridge) watch to render streaming LLM output.

   Invoked directly by `dspy-action`, `coact-raw-generate`, and `clj-sandbox`
   completion (behavior-tree / clj-sandbox reach it via requiring-resolve so
   they don't hard-depend on the agent component)."
  [{:keys [st-memory-atom]}]
  (fn [chunk]
    (when (= :content-delta (:type chunk))
      (swap! st-memory-atom update :llm-streaming-text
             (fn [prev] (str (or prev "") (:text chunk)))))
    (when (= :done (:type chunk))
      (swap! st-memory-atom assoc :llm-streaming-text nil))))

;; ============================================================================
;; BT Building
;; ============================================================================

(defn build-bt
  "Build a behavior tree for an agent.

   Parameters:
     bt-config - BT configuration vector [node-type opts? & children]
     agent     - Agent record (provides fields and context)

   Options:
     :st-memory-init - Initial short-term memory map (default {})

   Returns: built BT map {:tree :context}"
  [bt-config agent & {:keys [st-memory-init]}]
  (bt/build bt-config
            {:st-memory (or st-memory-init {})
             :agent agent}))

;; ============================================================================
;; BT Execution with Memory Cycle
;; ============================================================================

(defn- reset-st-memory!
  "Reset short-term memory to initial state, set question, and increment BT generation.
   Returns {:st-memory-atom atom, :generation int}."
  [!state bt question]
  (let [st-memory-atom (get-in bt [:context :st-memory])
        st-memory-init (:st-memory-init @!state)
        generation (:bt-generation (swap! !state update :bt-generation (fnil inc 0)))]
    (reset! st-memory-atom (assoc (if st-memory-init @st-memory-init {})
                                  :question question))
    {:st-memory-atom st-memory-atom :generation generation}))

(defn- run-analytics-async!
  "Launch async session analytics if enabled. Guards stale writes with generation check."
  [agent st-memory-atom !state generation state]
  (when-let [analyze-fn @!analyze-session-fn]
    (let [agent-cfg (:config state)]
      (when (or (:enable-analytics agent-cfg)
                (config/get-config agent :enable-analytics))
        (future
          (try
            (let [analytics-result
                  (analyze-fn {:session-id (proto/session-id agent)
                               :user-id    (proto/user-id agent)
                               :messages   (some-> (:!session agent) deref :messages)
                               :usage-tracker (some-> (:!session agent)
                                                      deref
                                                      (get-in [:config :usage-tracker]))}
                              :memory-manager (:memory-manager state)
                              :persist true
                              :lm-config (:analytics-lm-config agent-cfg)
                              :skip-llm-analysis (not (:analytics-lm-config agent-cfg)))]
              (when (and analytics-result
                         (= generation (:bt-generation @!state)))
                (swap! st-memory-atom assoc :analytics analytics-result)
                (hooks/fire! :agent.analytics/post
                             {:agent agent :analytics analytics-result})))
            (catch Exception e
              (mulog/warn ::post-session-analytics-failed :message (ex-message e)))))))))

(defn run-bt
  "Run the agent's behavior tree.

   Steps:
   1. Reset st-memory, set question
   2. Execute BT

   Parameters:
     agent    - Agent record
     question - User's input/question

   Returns: BT execution result (:success, :failure, :running)"
  [agent question]
  (let [!state (:!state agent)
        state @!state
        bt (:behavior-tree state)]

    (when-not bt
      (throw (ex-info "No behavior tree configured" {:agent-id (proto/agent-id agent)})))

    (let [{:keys [st-memory-atom generation]} (reset-st-memory! !state bt question)
          result (volatile! nil)
          bt-error (volatile! nil)]
      (try
        (vreset! result (bt/run bt))
        (catch Exception e
          (vreset! bt-error e)))

      (when-not @bt-error
        (run-analytics-async! agent st-memory-atom !state generation state))

      (when-let [e @bt-error]
        (throw e))

      @result)))

;; ============================================================================
;; Skill Behavior Function
;; ============================================================================

(defn skill-behavior-fn
  "Create a BT action function that runs a sub-behavior-tree.
   Used for skills that have their own BT configurations.

   Parameters:
     skill-bt-config - BT config for the skill

   Returns: action-fn compatible with BT action nodes"
  [skill-bt-config]
  (fn [{:keys [st-memory agent] :as _context}]
    (let [skill-bt (bt/build skill-bt-config
                             {:st-memory @st-memory
                              :agent agent})
          result (bt/run skill-bt)]
      ;; Merge skill's st-memory back into parent's st-memory
      (let [skill-st @(get-in skill-bt [:context :st-memory])]
        (swap! st-memory merge (dissoc skill-st :question)))
      result)))

;; ============================================================================
;; Cloudcast-compatible Skill Behavior Function
;; ============================================================================

(defn skill-behavior-fn*
  "Cloudcast-compatible skill behavior function.
   Operates on the parent agent's BT context (reuses st-memory atom).

   Parameters (keyword args):
     :skill-id    - Skill identifier
     :agent       - Agent instance
     :tree        - BT config vector for the skill
     :merge-inputs - Map to merge into st-memory before running
     :dirty-keys  - Keys to save/restore around skill execution
     :output-fn   - (fn [result st-memory-state]) -> output value
     :check-fn    - (fn [st-memory-state]) -> nil or {:result :output}

   Returns: {:result bt-result :output output-value}"
  [& {:keys [skill-id agent tree dirty-keys merge-inputs output-fn check-fn]}]
  (if (and agent (:agent-id agent))
    (let [parent-bt (get-in @(:!state agent) [:behavior-tree])
          bt (assoc parent-bt
                    :tree (bt/build tree (get parent-bt :context)))
          {:keys [st-memory]} (:context parent-bt)
          merge-inputs (assoc merge-inputs :skill-id skill-id)
          dirty-keys (conj (vec dirty-keys) :skill-id)
          saved-st-memory (select-keys @st-memory dirty-keys)
          check-failure (when check-fn (check-fn @st-memory))]
      (if-not check-failure
        (do
          ;; Merge inputs for skill
          (swap! st-memory merge merge-inputs)
          (let [result (bt/run bt)
                output {:result result
                        :output (when output-fn (output-fn result @st-memory))}]
            ;; Restore previous memory state
            (swap! st-memory #(-> (apply dissoc % dirty-keys) (merge saved-st-memory)))
            output))
        check-failure))
    {:result :failure :output "invalid agent instance"}))

;; ============================================================================
;; Extended BT Nodes — Utility helpers
;; ============================================================================

(defn- abbreviate
  "Abbreviate a string to max-len characters, appending '...' if truncated."
  [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "...")
    s))

(defn- materialize-map
  "Convert a map to its string representation for display."
  [m]
  (pr-str m))

;; ============================================================================
;; Repeat decorator
;; ============================================================================

(defn- check-condition-fn [id depth condition-fn-result ^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent]
  (when agent
    (.update-session-data agent
                          {:trace {:agent-id (:agent-id agent) :depth depth :content
                                   (format (if condition-fn-result
                                             "%s repeat condition-fn **passed**."
                                             "%s repeat condition-fn **failed**.") id)}}))
  condition-fn-result)

(defn- check-interrupt-cancel-pause!
  "Standard pre-tick checkpoint shared by :condition, :action, and the
   :repeat iteration boundary. In order:
     1. honor a Java thread interrupt (preemptive cancel)
     2. honor an explicit cancel request
     3. if paused, park the thread on the pause condition; on wake, re-check
        cancel (because cancel-run signals the condition to unblock waiters)
   Throws ex-info on interrupt or cancel; otherwise returns nil."
  [^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent depth id node-type]
  (when agent
    (when (Thread/interrupted)
      (.update-session-data agent
                            {:trace {:agent-id (:agent-id agent) :depth depth :content (format "%s %s **interrupted**." id (name node-type))}
                             :exception (format "agent processing interrupted at %s so the processing agent aborted!" id)})
      (throw (ex-info "Interrupted" {:node-type node-type, :node-id id})))
    (when (.check-run-cancelled? agent)
      (.update-session-data agent
                            {:trace {:agent-id (:agent-id agent) :depth depth :content (format "%s %s **cancelled**." id (name node-type))}
                             :exception (format "agent processing cancelled by user at %s so the processing agent aborted!" id)})
      (throw (ex-info "Cancelled" {:node-type node-type, :node-id id})))
    (when (.check-run-paused? agent)
      (.update-session-data agent
                            {:trace {:agent-id (:agent-id agent) :depth depth :content (format "%s %s **paused**." id (name node-type))}})
      (let [outcome (.await-resume agent)]
        (when (= outcome :cancelled)
          (.update-session-data agent
                                {:trace {:agent-id (:agent-id agent) :depth depth :content (format "%s %s **cancelled** (while paused)." id (name node-type))}
                                 :exception (format "agent processing cancelled by user at %s so the processing agent aborted!" id)})
          (throw (ex-info "Cancelled" {:node-type node-type, :node-id id})))
        (.update-session-data agent
                              {:trace {:agent-id (:agent-id agent) :depth depth :content (format "%s %s **resumed**." id (name node-type))}})))))

(defmethod p/tick :repeat
  [{:keys [id max-n condition-fn child depth]
    :or {id "?" depth 0 max-n 5
         condition-fn (fn [_] true)} :as _node}
   {:keys [st-memory ^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent] :as context}]

  (let [max-n (or (if (fn? max-n) (max-n context) max-n) 5)]

    (when agent
      (.update-session-data agent
                            {:trace {:agent-id (:agent-id agent) :depth depth :content (format ">>> %s repeat(max-n:%d) **started**." id max-n)}}))

    (let [stop-reason (get @st-memory :stop-reason)
          result (if child
                   (loop [n 0]
                     (if (< n max-n)
                       (let [iter-num     (inc n)
                             _            (check-interrupt-cancel-pause! agent depth id :repeat)
                             _            (when agent
                                            (hooks/fire! :agent.iteration/pre
                                                         {:agent agent
                                                          :iteration iter-num
                                                          :max-iterations max-n
                                                          :repeat-id id}))
                             child-result (p/tick (assoc child :depth (inc depth)) context)
                             post-st      @st-memory
                             _            (when agent
                                            (hooks/fire! :agent.iteration/post
                                                         {:agent agent
                                                          :iteration iter-num
                                                          :max-iterations max-n
                                                          :repeat-id id
                                                          :result child-result
                                                          :observation (:observation post-st)
                                                          :goal-achieved (:goal-achieved post-st)
                                                          :goal-reasoning (:goal-reasoning post-st)}))]
                         (condp = child-result
                           p/success (if (check-condition-fn id depth (condition-fn context) agent)
                                       p/success
                                       (recur (inc n)))
                           p/failure (do
                                       (when (and agent stop-reason)
                                         (.update-session-data agent
                                                               {:trace {:agent-id (:agent-id agent) :depth depth :content (format "%s repeat **stopped** by %s." id stop-reason)}}))
                                       p/failure)
                         ;; else
                           (throw (ex-info "unknown child-result" {:child-result child-result}))))
                       (do
                         ;; Loop exhausted — n hit max-n without the condition-fn
                         ;; ever returning true. Fire :agent.iteration/exhausted
                         ;; so observers (TUI, web bridge) can render a warning.
                         (when agent
                           (hooks/fire! :agent.iteration/exhausted
                                        {:agent agent
                                         :iteration-count n
                                         :max-iterations max-n}))
                         p/success)))
                   p/success)]

      (when agent
        (.update-session-data agent
                              {:trace {:agent-id (:agent-id agent) :depth depth :content (format "<<< %s repeat **%s**." id result)}}))
      result)))

;; ============================================================================
;; Tracing-aware sequence override
;; ============================================================================

(defmethod p/tick :sequence
  [{:keys [id depth] :or {depth 0} :as node}
   {:keys [^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent] :as context}]
  (let [id (or id "?")
        _ (when agent
            (.update-session-data agent
                                  {:trace {:agent-id (:agent-id agent) :depth depth :content (format ">>> %s sequence **started**." id)}}))
        result (loop [[child-node :as children] (:children node)]
                 (if-not child-node
                   p/success
                   (let [result (p/tick (assoc child-node :depth (inc depth)) context)]
                     (case result
                       :success (recur (rest children))
                       :failure p/failure
                       :running p/running))))]
    (when agent
      (.update-session-data agent
                            {:trace {:agent-id (:agent-id agent) :depth depth :content (format "<<< %s sequence **%s**." id result)}}))
    result))

;; ============================================================================
;; Tracing-aware fallback override
;; ============================================================================

(defmethod p/tick :fallback
  [{:keys [id depth] :or {depth 0} :as node}
   {:keys [^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent] :as context}]
  (let [id (or id "?")
        _ (when agent
            (.update-session-data agent
                                  {:trace {:agent-id (:agent-id agent) :depth depth :content (format ">>> %s fallback **started**." id)}}))
        result (loop [[child-node :as children] (:children node)]
                 (if-not child-node
                   p/failure
                   (let [result (p/tick (assoc child-node :depth (inc depth)) context)]
                     (case result
                       :success p/success
                       :failure (recur (rest children))
                       :running p/running))))]
    (when agent
      (.update-session-data agent
                            {:trace {:agent-id (:agent-id agent) :depth depth :content (format "<<< %s fallback **%s**." id result)}}))
    result))

;; ============================================================================
;; Memory value helpers (for condition nodes)
;; ============================================================================

(defn get-st-memory-value
  [{{:keys [path] {:keys [collapse] :or {collapse true}} :debug} :opts
    :keys [st-memory]}]
  (let [st-memory-state @st-memory
        value (when path (get-in st-memory-state path))
        str-pr-value (with-out-str (pr value))]
    (format "%s=%s" path (if collapse
                           (abbreviate str-pr-value 100)
                           str-pr-value))))

;; ============================================================================
;; Tracing-aware condition override (with interrupt/cancel checks)
;; ============================================================================

(defmethod p/tick :condition
  [{:keys [condition-fn opts depth] :or {depth 0} :as _node}
   {:keys [st-memory ^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent] :as context}]
  (let [id (or (:id opts) "?")
        _ (check-interrupt-cancel-pause! agent depth id :condition)
        context (assoc context :opts opts)
        result (if (condition-fn context)
                 p/success
                 p/failure)
        ;; for debugging
        {:keys [source]} (:debug opts)
        debug-value (case source
                      :st-memory (format "<br/>[*] %s" (get-st-memory-value context))
                      nil)]
    (when agent
      (.update-session-data agent
                            {:trace {:agent-id (:agent-id agent) :depth depth :content (format "%s condition **%s**." id result) :debug debug-value}}))
    (when (= result p/failure)
      (swap! st-memory update :last-failure (constantly (format "%s condition **%s**." id result))))
    result))

;; ============================================================================
;; Tracing-aware action override (with interrupt/cancel checks)
;; ============================================================================

(defmethod p/tick :action
  [{:keys [action-fn opts depth] :or {depth 0} :as _node}
   {:keys [st-memory ^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent] :as context}]
  (let [id (or (:id opts) "?")
        _ (check-interrupt-cancel-pause! agent depth id :action)
        _ (when agent
            ;; action starts
            (.update-session-data agent {:trace {:agent-id (:agent-id agent) :depth depth :content (format "%s action **started**..." id)}}))
        result (action-fn (assoc context :opts opts :depth depth))
        ;; for debugging
        {:keys [source check-fn]} (:debug opts)
        debug-value (case source
                      :reasoning (when (and (= (:operation opts) :chain-of-thought)
                                            (or (nil? check-fn) (check-fn context)))
                                   (if-let [reasoning (some-> agent (.get-bt-st-memory) deref :last-reasoning)]
                                     (format "<br/>[!] %s" reasoning)
                                     (format "<br/>[!] no reasoning, something wrong!")))
                      nil)]

    (when-let [todo-update (:todo-update opts)]
      (when-let [todo-list (:todo-list @st-memory)]
        (when agent
          (.update-session-data agent
                                {:todo-info {:action-id id :todo-update todo-update :todo-list todo-list}}))))
    (when agent
      (.update-session-data agent
                            {:trace {:agent-id (:agent-id agent) :depth depth :content (format "%s action **%s**." id result) :debug debug-value}}))
    (when (= result p/failure)
      (swap! st-memory update :last-failure (constantly (format "%s action **%s**." id result))))
    result))

;; ============================================================================
;; User interaction actions
;; ============================================================================

(defn request-user-action
  "Generic user action request via agent promises.
   Requires `:agent` in context with `.create-action-promise` method."
  [{:keys [st-memory ^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent]
    {:keys [id request-message request-check-fn response-fn]} :opts
    :as context}]
  (or
   ;; check if user action is already allowed
   (when request-check-fn
     (let [{:keys [request-skip? response-value]} (request-check-fn context)]
       (when request-skip?
         (if response-fn
           (response-fn context response-value)
           p/success))))

   ;; check if user action is required
   (let [p (.create-action-promise agent id)
         {:keys [show-instruction show-last-reasoning show-chart]} request-message
         instruction (when show-instruction (get @st-memory :instruction))
         last-reasoning (when show-last-reasoning (some-> (.get-bt-st-memory agent) deref :last-reasoning))]

     (.update-session-data agent
                           {:user-action (cond-> {:user-id (proto/user-id agent) :session-id (proto/session-id agent) :agent-id (proto/agent-id agent)
                                                  :action-id id :request-message request-message}
                                           instruction (assoc :instruction instruction)
                                           last-reasoning (assoc :last-reasoning last-reasoning))})
     ;; waiting for user action (with timeout to prevent indefinite blocking)
     (let [timeout-ms (or (get-in context [:opts :timeout-ms]) 300000)
           val (deref p timeout-ms ::action-timed-out)]
       (when (= val ::action-timed-out)
         (throw (ex-info "User action timed out"
                         {:action-id id :timeout-ms timeout-ms})))
       ;; update user-action with result
       (.update-session-data agent
                             {:user-action (cond-> {:action-id id :request-message request-message :result val}
                                             instruction (assoc :instruction instruction)
                                             last-reasoning (assoc :last-reasoning last-reasoning))})
       (if response-fn
         (response-fn context val)
         p/success)))))

(defn request-user-action--request-check-fn
  "Permission check factory for user action requests."
  [request-skip-fn response-value]
  (fn [{:keys [^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent]
        {:keys [id]} :opts
        :as _context}]
    (let [permission (.get-action-permission agent id)]
      {:request-skip? (request-skip-fn permission)
       :response-value (if (fn? response-value)
                         (response-value permission)
                         response-value)})))

(defn request-user-action--response-fn
  "Response handling factory for user action requests."
  [& {:keys [response-value-handle-fn]}]
  (fn [{:keys [st-memory ^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent]
        {:keys [id] {:keys [data]} :request-message} :opts
        :as context} val]
    (or
     ;; use response-value handle-fn
     (and response-value-handle-fn (response-value-handle-fn context val))
     ;; default response-value (:yes, :no) processing
     (case val
       :yes p/success
       :no p/failure
       (if-let [[_ _ _ option] (first (filter #(= val (nth % 2)) data))]
         (case option
           :permission
           (do (.set-action-permission agent id val)
               (case val
                 :allowed p/success
                 :denied p/failure))
           ;; unknown option
           p/failure)
         p/failure)))))

(defn artifact-action
  "Artifact display action. Sends artifact data to the agent."
  [{:keys [^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent]
    {:keys [id artifact-type artifact-data]} :opts
    :as _context}]
  (.update-session-data agent
                        {:artifact {:user-id (proto/user-id agent) :session-id (proto/session-id agent) :agent-id (proto/agent-id agent)
                                    :action-id id :artifact-type artifact-type :artifact-data artifact-data}})
  p/success)

(defn user-approval-action
  "Yes/no/always approval flow action."
  [{:keys [^ai.brainyard.agent.core.protocol.IAgentBTIntegration agent]
    {:keys [id content bt-action?]
     :or {bt-action? true}} :opts
    :as context}]
  (let [request-message {:type :choice
                         :header content
                         :data [["Yes" "Permission allowed" :yes]
                                ["Always yes" "Allowed, will not ask further approval..." :allowed :permission]
                                ["No" "Permission denied" :no]
                                ["Always no" "Denied, will not ask further approval..." :denied :permission]]}]
    (request-user-action (update context :opts #(assoc %
                                                       :request-message request-message
                                                       :request-check-fn (request-user-action--request-check-fn
                                                                          (fn [perm] (contains? #{:allowed :denied} perm))
                                                                          (fn [perm] (case perm
                                                                                       :allowed :yes
                                                                                       :denied :no
                                                                                       nil)))
                                                       :response-fn (request-user-action--response-fn
                                                                     :response-value-handle-fn
                                                                     (fn [_ val]
                                                                       (case val
                                                                         :yes (if bt-action? p/success :allowed)
                                                                         :allowed (do (.set-action-permission agent id val)
                                                                                      (if bt-action? p/success :allowed))
                                                                         :no (if bt-action? p/failure :denied)
                                                                         :denied (do (.set-action-permission agent id val)
                                                                                     (if bt-action? p/failure :denied))))))))))

(defn user-interrupt-action
  "Interrupt handling action. Prompts user to continue or stop."
  [{:keys [st-memory agent] {:keys [id]} :opts :as context}]
  (let [request-message {:type :choice
                         :header "Interrupted by user, continue?"
                         :data [["Continue" "Agent execution is continued..." :yes]
                                ["Stop" "Agent execution is stopped..." :no]]}]
    (request-user-action (update context :opts #(assoc %
                                                       :request-message request-message
                                                       :response-fn (request-user-action--response-fn
                                                                     :response-value-handle-fn
                                                                     (fn [_ val]
                                                                       (case val
                                                                         :yes p/success
                                                                         :no (throw (ex-info "Agent has been stopped by user interruption"
                                                                                             {:agent-id (:agent-id agent)
                                                                                              :action-id id}))))))))))

;; ============================================================================
;; BT Visualization
;; ============================================================================

(defn get-btree-node-id [node]
  (or (:id node) (get-in node [:opts :id]) :?/?))

(defn get-btree-node-info
  "Extract node metadata for display."
  [node]
  (let [id (get-btree-node-id node)
        type (:type node)
        signature (get-in node [:opts :signature])
        signature-meta (when (var? signature) (meta signature))]
    (cond-> {:id id :type type :opts (materialize-map (:opts node))}
      (= type :repeat) (assoc :child (get-btree-node-id (:child node)))
      (contains? #{:sequence :fallback} type) (assoc :children
                                                     (mapv #(get-btree-node-id %) (:children node)))
      (and (= type :action) signature signature-meta)
      (-> (assoc
           :initial-instructions (get-in signature-meta [:dspy/signature :instructions])
           :inputs (get-in signature-meta [:dspy/signature :inputs])
           :outputs (get-in signature-meta [:dspy/signature :outputs]))))))

(defn btree->jstree
  "Convert a behavior tree to jsTree format for visualization."
  [node]
  (let [id (get-btree-node-id node)
        type (:type node)]
    (case type
      :repeat {:id (str id)
               :text (format "[%s]%s" type id)
               :children [(btree->jstree (:child node))]}
      (:sequence :fallback) {:id (str id)
                             :text (format "[%s]%s" type id)
                             :children (mapv #(btree->jstree %) (:children node))}
      :condition {:id (str id) :text (format "[%s]%s" type id)}
      :action {:id (str id) :text (str (format "[%s]%s" type id) (when (get-in node [:opts :signature]) "[!]"))})))

(defn get-btree-node
  "Find a node by ID in the behavior tree."
  [node node-id]
  (let [id (get-btree-node-id node)
        type (:type node)]
    (if (= node-id id)
      node
      (case type
        :repeat (get-btree-node (:child node) node-id)
        (:sequence :fallback)
        (reduce #(let [found-node (get-btree-node %2 node-id)]
                   (when found-node
                     (reduced found-node)))
                nil
                (:children node))
        (:condition :action) (when (= node-id id) node)))))
