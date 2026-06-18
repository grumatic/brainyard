;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.hooks
  "Application-facing hooks registry.

   A single place for apps (TUI, web bridge, plugins) to subscribe to agent
   runtime events. Replaces the previous fragmented scheme where lifecycle
   hooks were stored on st-memory-init, tool-use hooks on bt st-memory, and
   task hooks on their own atom.

   Usage:

     (require '[ai.brainyard.agent.core.hooks :as hooks])

     ;; Register a single observer
     (hooks/register-hook!
       :agent.tool-use/post                                      ; event key
       ::my-tool-logger                                ; handler id
       (fn [{:keys [agent tool-name args result]}]     ; handler fn
         (mulog/log ::tool-call :name tool-name))
       :source :my-plugin)

     ;; Bulk teardown at app shutdown
     (hooks/unregister-source! :my-plugin)

   Event catalog (see `event-catalog`):

     :agent.session/created    {:session-id :user-id :session}
     :agent.session/resumed    {:session-id :user-id :session}
     :agent.session/closed     {:session-id :user-id :session}
     :agent.instance/created   {:agent}
     :agent.instance/closed    {:agent}
     :agent.ask/pre            {:agent :input}
     :agent.ask/post           {:agent :input :result}
     :agent.iteration/pre      {:agent :iteration :max-iterations :repeat-id}
     :agent.iteration/post     {:agent :iteration :max-iterations :repeat-id :result
                                :observation :goal-achieved}
     :agent.iteration/exhausted {:agent :iteration-count :max-iterations}
     :agent.dspy-action/pre    {:agent :node-id :signature :operation :stable-keys :inputs}
     :agent.dspy-action/chunk  {:agent :node-id :signature :chunk :accumulated}
     :agent.dspy-action/post   {:agent :node-id :signature :operation :stable-keys :inputs
                                :result :outputs :reasoning :usage :error}
     :agent.context/budgeted   {:agent :total-tokens :budget :section-tokens
                                :compactions :over-budget?}
     :agent.tool-calls/pre     {:agent :iteration :calls}
     :agent.tool-calls/post    {:agent :iteration :calls :results}
     :agent.tool-use/pre       {:agent :tool-name :args :call-id :depth}     ;; gated
     :agent.tool-use/post      {:agent :tool-name :args :call-id :result}
     :agent.code-eval/pre      {:agent :code :backend}
     :agent.code-eval/post     {:agent :code :result :output :error :duration-ms :backend}
     :agent.compaction/post    {:agent :before-size :after-size :compaction-count}
     :agent.evaluation/started {:agent :round}
     :agent.evaluation/llm-calling {:agent :round :has-evidence :evidence-length
                                    :eval-lm-label}
     :agent.evaluation/done    {:agent :round :verdict :detail}
     :agent.evaluation/verdict {:agent :score :verdict :reason}
     :agent.recovery/retrying  {:agent :kind :attempt :max :detail}
     :agent.suggestion/next-user-prompt {:agent :prompt :input}
     :task/created             {:task}
     :task/completed           {:task}

   Semantics:
   - `fire!` invokes every matching handler in priority order; exceptions are
     swallowed + logged per each handler's `:on-error` setting. Return values
     from handlers are IGNORED on this path. Use for observer-only events.
   - `fire-decision!` is the gated counterpart. It walks handlers in priority
     order, expects each to return a `decision-map` (see below), and returns
     the first non-`:allow` decision (short-circuiting later handlers).
     Caller acts on the decision (block / replace / modify-args).
   - `:match` predicate filters which registered handlers see an event.
   - `:source` is a teardown tag so a plugin can remove all its hooks at once.

   Decision map (returned by handlers on gated events):

     {:result      :allow | :block | :replace | :modify-args   ;; required
      :reason      \"human-readable one-liner\"                ;; required when not :allow
      :replacement any                                         ;; required when :replace
      :args        {...}                                       ;; required when :modify-args
      :answer      \"synthesized agent answer (optional)\"     ;; consumed by :block
      :by          ::handler-id                                ;; auto-stamped
      :meta        {...}}                                      ;; freeform payload

   Returning `nil`, a non-map, or any map without a recognized `:result`
   keyword is treated as `{:result :allow}` — observer-style handlers stay
   compatible without changes."
  (:require [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Registry
;; ============================================================================

(defonce ^:private !hooks (atom {}))
;; Shape: {event-key [entry ...]}
;; entry: {:id kw :handler fn :match fn :priority int :source kw :on-error kw}

;; ============================================================================
;; Event Catalog
;; ============================================================================

(def event-catalog
  "Known hook events. `:gates?` true marks events whose handlers' decisions
   are consumed via `fire-decision!`; all other events are fire-and-forget."
  {:agent.session/created       {:keys #{:session-id :user-id :session}}
   :agent.session/resumed       {:keys #{:session-id :user-id :session}}
   :agent.session/closed        {:keys #{:session-id :user-id :session}}
   :agent.instance/created      {:keys #{:agent}}
   :agent.instance/closed       {:keys #{:agent}}
   :agent.ask/pre               {:keys #{:agent :input}}
   :agent.ask/post              {:keys #{:agent :input :result}}
   ;; Gated. Fires after the result is built, BEFORE :agent.ask/post and the
   ;; session message. A handler may return a :replace decision whose
   ;; :replacement is the new result map — used to inject an auto-persist
   ;; `Saved <kind>: <path>` handoff line the LLM omitted. Observers on
   ;; :agent.ask/post then see the finalized result.
   :agent.ask/finalize          {:keys #{:agent :input :result} :gates? true}
   :agent.iteration/pre         {:keys #{:agent :iteration :max-iterations :repeat-id}}
   :agent.iteration/post        {:keys #{:agent :iteration :max-iterations :repeat-id :result
                                         :observation :goal-achieved}}
   :agent.iteration/exhausted   {:keys #{:agent :iteration-count :max-iterations}}
   :agent.dspy-action/pre       {:keys #{:agent :node-id :signature :operation :stable-keys :inputs}}
   :agent.dspy-action/chunk     {:keys #{:agent :node-id :signature :chunk :accumulated}}
   :agent.dspy-action/post      {:keys #{:agent :node-id :signature :operation :stable-keys :inputs
                                         :result :outputs :reasoning :usage :error}}
   :agent.context/budgeted      {:keys #{:agent :total-tokens :budget :section-tokens
                                         :compactions :over-budget?}}
   :agent.tool-calls/pre        {:keys #{:agent :iteration :calls}}
   :agent.tool-calls/post       {:keys #{:agent :iteration :calls :results}}
   :agent.tool-use/pre          {:keys #{:agent :tool-name :args :call-id :depth} :gates? true}
   :agent.tool-use/post         {:keys #{:agent :tool-name :args :call-id :result}}
   ;; `:backend` is :sandbox or :nrepl — added in clj-nrepl-eval Phase 1
   ;; (docs/design/clj-nrepl-eval.md §4.2). Observers should branch on it
   ;; if they care which backend ran the code (e.g. audit shims).
   ;; `:lang` is the fence language (clojure/bash/python/javascript/…) — used by
   ;; the TUI subagents block to render a per-code-block activity line.
   :agent.code-eval/pre         {:keys #{:agent :code :backend :lang}}
   ;; Memory capture pipeline (P3 — see ai.brainyard.memory.core.capture).
   :agent.code-eval/post        {:keys #{:agent :code :result :output :error :duration-ms :backend :lang}}
   ;; Cross-turn context compaction lifecycle (deterministic, no LLM).
   ;; `:trigger` ∈ #{:manual :auto}; identifies whether /compact or the
   ;; after-turn auto-compact initiated this run. Sizes are token estimates.
   :agent.compaction/pre        {:keys #{:agent :before-tokens :target-tokens :trigger}}
   ;; Per-phase progress events. `:phase` is `:previous-turns` (the carryover
   ;; chain compacted by this path). `:status` is :start (about to run) or
   ;; :done (finished). `:before-tokens`/`:after-tokens` reflect the
   ;; agent-wide estimate measured around the phase; identical values mean
   ;; the phase was a no-op.
   :agent.compaction/phase      {:keys #{:agent :phase :status :before-tokens :after-tokens}}
   :agent.compaction/post       {:keys #{:agent :before-tokens :after-tokens
                                         :compacted-keys :trigger :duration-ms
                                         :compaction-count}}
   :agent.evaluation/started    {:keys #{:agent :round}}
   :agent.evaluation/llm-calling {:keys #{:agent :round :has-evidence :evidence-length :eval-lm-label}}
   :agent.evaluation/done       {:keys #{:agent :round :verdict :detail}}
   :agent.evaluation/verdict    {:keys #{:agent :score :verdict :reason}}
   ;; Recovery progress (observer-only). Fired when the CoAct loop is working
   ;; through a transient stall so the TUI can surface a status line. `:kind`
   ;; ∈ #{:empty-result :malformed-output :no-action}; `:attempt`/`:max`
   ;; describe progress (`:max` may be nil).
   :agent.recovery/retrying     {:keys #{:agent :kind :attempt :max :detail}}
   ;; Observer-only. Fires when an answer carries a non-blank self-reported
   ;; next-user-prompt (a concise follow-up the user could send next). Apps
   ;; (TUI input-bar tip, web bridge) subscribe to surface it. Sub-agents
   ;; fire too; consumers scope to root agents via `match-root-agent`.
   :agent.suggestion/next-user-prompt {:keys #{:agent :prompt :input}}
   ;; Runtime-driven detached-task notification (ai.brainyard.agent.common.auto-notify).
   ;; Observer-only. `:auto-resumed` fires when a backgrounded task completed
   ;; while idle and the runtime injected a resume turn; `:auto-parked` fires
   ;; when the turn was force-parked after repeated polls of a running task.
   :agent.task/auto-resumed     {:keys #{:agent :task-ids :source}}
   :agent.iteration/auto-parked {:keys #{:agent :task-id :polls}}
   :task/created                {:keys #{:task}}
   :task/completed              {:keys #{:task}}
   :todo/updated                {:keys #{:agent :todo-list :active-slug}}
   :agent/exception             {:keys #{:agent :phase :exception}}})

(defn gated-event?
  "True when the event's handlers produce decisions consumed by the caller."
  [event-key]
  (boolean (get-in event-catalog [event-key :gates?])))

(defn known-event?
  "Return true when event-key is in the catalog."
  [event-key]
  (contains? event-catalog event-key))

;; ============================================================================
;; Registration
;; ============================================================================

(defn register-hook!
  "Register a handler for an event.

   Required:
     event-key  - namespaced keyword; see `event-catalog`
     handler-id - unique id for this entry within the event; used for unregister
     handler    - (fn [event-map]) -> any

   Options:
     :match     - (fn [event-map]) -> boolean; default (constantly true)
     :priority  - integer, higher fires first; default 0
     :source    - keyword tag for bulk teardown via `unregister-source!`
     :on-error  - :log (default), :throw, or (fn [ex event-map])

   Warns (but still registers) when event-key is not in the catalog, so
   third-party events keep working during development. Re-registering the
   same [event-key handler-id] replaces the prior entry."
  [event-key handler-id handler & {:keys [match priority source on-error]
                                   :or {priority 0 on-error :log}}]
  (when-not (known-event? event-key)
    (mulog/warn ::unknown-event :event-key event-key :handler-id handler-id))
  (let [entry {:id       handler-id
               :handler  handler
               :match    (or match (constantly true))
               :priority priority
               :source   source
               :on-error on-error}]
    (swap! !hooks update event-key
           (fn [entries]
             (let [filtered (filterv #(not= handler-id (:id %)) (or entries []))]
               (conj filtered entry))))
    handler-id))

(defn unregister-hook!
  "Remove a specific handler by [event-key handler-id]. Returns true if removed."
  [event-key handler-id]
  (let [removed? (volatile! false)]
    (swap! !hooks update event-key
           (fn [entries]
             (let [filtered (filterv #(not= handler-id (:id %)) (or entries []))]
               (when (not= (count filtered) (count (or entries [])))
                 (vreset! removed? true))
               filtered)))
    @removed?))

(defn unregister-source!
  "Remove every handler tagged with `:source source-kw` across all events.
   Returns the count of handlers removed."
  [source-kw]
  (let [removed (volatile! 0)]
    (swap! !hooks
           (fn [m]
             (reduce-kv
              (fn [acc event-key entries]
                (let [filtered (filterv #(not= source-kw (:source %)) entries)]
                  (vswap! removed + (- (count entries) (count filtered)))
                  (assoc acc event-key filtered)))
              {} m)))
    @removed))

(defn reset-hooks!
  "Remove every registered hook. For testing."
  []
  (reset! !hooks {}))

;; ============================================================================
;; Introspection
;; ============================================================================

(defn list-hooks
  "List registered hook entries. With no args returns the full map; with an
   event-key, returns the vector of entries for that event (possibly nil)."
  ([] @!hooks)
  ([event-key] (get @!hooks event-key)))

;; ============================================================================
;; Decision Contract
;; ============================================================================

(def ^:private decision-results
  "The verdict keywords recognized in a decision map."
  #{:allow :block :replace :modify-args})

(defn decision?
  "True when `x` is a map whose `:result` is a recognized verdict.
   Anything else (nil, scalar, foreign map) is treated as observer-only
   (= implicit :allow) by `fire-decision!`."
  [x]
  (and (map? x) (contains? decision-results (:result x))))

(defn- valid-decision?
  "True when a decision map carries the keys required for its verdict."
  [{:keys [result replacement args] :as d}]
  (and (decision? d)
       (case result
         :allow        true
         :block        true
         :replace      (contains? d :replacement)
         :modify-args  (and (map? args) (seq args))
         false)))

(def allow-decision
  "Default verdict when no handler vetoes."
  {:result :allow})

;; ============================================================================
;; Firing
;; ============================================================================

(def ^:dynamic ^:private *hook-depth*
  "Re-entrancy counter incremented around each handler invocation. Tools
   dispatched from inside a hook handler can see `(current-depth) > 0`.
   Useful for hooks that want to opt out when called recursively."
  0)

(defn current-depth
  "Current hook re-entrancy depth (0 outside any hook)."
  []
  *hook-depth*)

(defn- invoke-entry
  "Invoke one handler entry on an event-map. Returns the handler's return value,
   or nil on exception (depending on :on-error)."
  [entry event-map event-key]
  (binding [*hook-depth* (inc *hook-depth*)]
    (try
      ((:handler entry) event-map)
      (catch Exception e
        (case (:on-error entry)
          :throw (throw e)
          :log   (do (mulog/warn ::hook-handler-failed
                                 :event-key event-key
                                 :handler-id (:id entry)
                                 :source (:source entry)
                                 :exception e)
                     nil)
          ;; fn form
          (try ((:on-error entry) e event-map)
               (catch Exception _ nil)))))))

(defn- matching-entries
  "Return entries whose :match predicate accepts event-map, sorted by priority
   descending."
  [event-key event-map]
  (->> (get @!hooks event-key)
       (filter (fn [entry]
                 (try ((:match entry) event-map)
                      (catch Exception _ false))))
       (sort-by (comp - :priority))))

(defn fire!
  "Fire a void event. Invokes all matching handlers in priority order,
   swallowing + logging exceptions per their :on-error setting. Returns nil.

   Use for observer-only events. Handler return values are IGNORED. For
   gated events (catalog `:gates?` true) use `fire-decision!`."
  [event-key event-map]
  (doseq [entry (matching-entries event-key event-map)]
    (invoke-entry entry event-map event-key))
  nil)

(defn fire-decision!
  "Fire a gated event and return the winning decision map.

   Walks matching handlers in priority order. The first handler that returns
   a non-`:allow` decision wins; any later handlers are skipped (their ids
   are logged at debug). Handlers that return `nil`, scalars, or maps
   without a recognized `:result` are treated as `:allow` and the iteration
   continues.

   Returns a decision map with `:by` stamped from the winning handler's id,
   or `{:result :allow}` when nobody vetoed."
  [event-key event-map]
  (when-not (gated-event? event-key)
    (mulog/warn ::fire-decision-on-ungated-event :event-key event-key))
  (let [entries (matching-entries event-key event-map)]
    (loop [[entry & more] entries]
      (if (nil? entry)
        allow-decision
        (let [raw (invoke-entry entry event-map event-key)
              hid (:id entry)]
          (cond
            (or (nil? raw) (not (decision? raw)))
            (recur more)

            (= :allow (:result raw))
            (recur more)

            (not (valid-decision? raw))
            (do (mulog/warn ::malformed-decision
                            :event-key event-key
                            :handler-id hid
                            :decision raw)
                (recur more))

            :else
            (do (when (seq more)
                  (mulog/debug ::skipped-handlers-after-decision
                               :event-key event-key
                               :winner hid
                               :skipped (mapv :id more)))
                (assoc raw :by (or (:by raw) hid)))))))))

;; ============================================================================
;; Match Helpers
;; ============================================================================

(defn match-all
  "Compose predicates with AND semantics."
  [& preds]
  (fn [event-map] (every? #(% event-map) preds)))

(defn match-any
  "Compose predicates with OR semantics."
  [& preds]
  (fn [event-map] (boolean (some #(% event-map) preds))))

(defn- event-agent [event-map]
  (or (:agent event-map) (:stage-agent event-map)))

(defn match-agent-id
  "Match events whose agent has the given agent-id."
  [agent-id]
  (fn [event-map]
    (let [ag (event-agent event-map)]
      (and ag (= agent-id (:agent-id ag))))))

(defn match-defagent-type
  "Match events whose agent's defagent-type equals the given keyword.
   The defagent-type is derived from the namespace of the instance-id
   (:<type>/<suffix>)."
  [type-kw]
  (fn [event-map]
    (let [ag (event-agent event-map)
          aid (when ag (:agent-id ag))]
      (and (keyword? aid)
           (= (name type-kw) (namespace aid))))))

(defn match-root-agent
  "Match events whose agent has no parent (i.e. a root agent, not a sub-agent).
   Looks up the agent's :runtime :parent-agent via !state."
  []
  (fn [event-map]
    (let [ag (event-agent event-map)]
      (and ag
           (nil? (get-in @(:!state ag) [:runtime :parent-agent]))))))
