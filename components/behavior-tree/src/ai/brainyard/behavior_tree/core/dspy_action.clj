;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.behavior-tree.core.dspy-action
  "DSPy action integration for behavior trees.

   Replaces grain's Python-based DSPy extensions with brainyard's
   pure Clojure clj-llm component."
  (:require [ai.brainyard.behavior-tree.interface.protocol :as p]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog]))

(def ^:private default-stable-keys
  "Input keys placed in the system message and excluded from
   DSPy signature inputs (user message) to avoid duplication.
   Empty by default — each agent type sets its own stable keys
   via :stable-keys in the BT node opts."
  #{})

;; Lazy-resolved so behavior-tree doesn't hard-depend on the agent component.
;; Wrapped in try so the delay is safe when the agent ns isn't on classpath
;; (behavior-tree standalone tests).
(def ^:private !chunk-factory
  (delay (try (requiring-resolve 'ai.brainyard.agent.core.bt/chunk-factory-handler)
              (catch Throwable _ nil))))

;; Lazy-resolved hook fire fn for the same reason as !chunk-factory above —
;; behavior-tree must not hard-require agent. Returns nil when agent isn't on
;; the classpath (standalone BT runs), in which case dspy-action hooks are no-ops.
(def ^:private !fire-hook
  (delay (try (requiring-resolve 'ai.brainyard.agent.core.hooks/fire!)
              (catch Throwable _ nil))))

(defn- resolve-on-chunk-fn
  "Resolve the streaming chunk handler for this BT action via the default
   chunk-factory in agent.core.bt (looked up lazily to avoid a hard dep from
   behavior-tree → agent)."
  [state st-memory node-id]
  (when-let [factory @!chunk-factory]
    (factory {:agent (:agent state)
              :st-memory-atom st-memory
              :node-id node-id})))

(defn- resolve-signature
  "Resolve a signature — if it's a var, deref it; otherwise use as-is."
  [signature]
  (if (var? signature) @signature signature))

(defn- resolve-lm-config
  "Resolve lm-config with precedence:
   1. BT action opts :lm-config (per-node override)
   2. Agent config :lm-config
   3. Session config :lm-config (shared across agents in same session)
   4. nil (clj-llm falls back to global default)

   When the resolved value is a raw `{:provider _ :model _}` map (no
   `:message-format`), normalize it via `clj-llm/create-lm` so downstream
   `chat-completion` dispatch routes by provider instead of silently
   falling through to the OpenAI default."
  [context]
  (let [raw (or (get-in context [:opts :lm-config])
                (when-let [agent (:agent context)]
                  (if-let [get-config (try (requiring-resolve
                                            'ai.brainyard.agent.core.config/get-config)
                                           (catch Throwable _ nil))]
                    (get-config agent :lm-config)
                    (or (get-in @(:!state agent) [:config :lm-config])
                        (get-in @(:!session agent) [:config :lm-config])))))
        resolved (if (fn? raw) (raw context) raw)]
    (cond
      (nil? resolved)                resolved
      (:message-format resolved)     resolved
      :else                          (clj-llm/create-lm resolved))))

(defn- resolve-usage-tracker
  "Resolve usage-tracker from:
   1. BT action opts :usage-tracker (per-node override)
   2. Session config :usage-tracker (shared across agents in session)"
  [context]
  (or (get-in context [:opts :usage-tracker])
      (when-let [agent (:agent context)]
        (get-in @(:!session agent) [:config :usage-tracker]))))

(defn- build-system-prompt
  "Build system context string from st-memory state for the system message.
   Only includes keys present in both the stable-keys set and state.
   Each key gets a '## <key-name>' header, sorted alphabetically.
   Returns {:text str-or-nil :token-breakdown map :zones [...]}.

   :zones is a vector of `{:key <kw> :text \"## <key>\\n<value>\"}` blocks
   in render order. Each zone is a candidate cache breakpoint — Anthropic
   provider attaches `cache_control: ephemeral` to each one when
   `:prompt-cache` is enabled in lm-config (M7). Other providers ignore
   zones and consume `:text` as before.

   For token breakdown: if st-memory contains :prompt-token-breakdown (a pre-computed
   per-category breakdown from build-system-prompt), uses those sub-categories instead
   of estimating stable keys as opaque blobs. Stable keys that have a pre-breakdown
   are excluded from blob estimation to avoid double-counting."
  [state stable-keys]
  (let [sorted-keys (sort stable-keys)
        pre-breakdown (:prompt-token-breakdown state)
        key-texts (reduce (fn [acc k]
                            (if-let [v (get state k)]
                              (assoc acc k (str "## " (name k) "\n" v))
                              acc))
                          {} sorted-keys)
        parts (keep #(get key-texts %) sorted-keys)
        text (when (seq parts) (str/join "\n\n" parts))
        zones (vec (keep (fn [k]
                           (when-let [t (get key-texts k)]
                             {:key k :text t}))
                         sorted-keys))
        ;; Build hierarchical breakdown: wrap sub-categories in a :system-prompt group
        breakdown (if pre-breakdown
                    {:system-prompt (clj-llm/build-token-group pre-breakdown)}
                    (when (seq key-texts)
                      {:system-prompt (clj-llm/build-token-group
                                       (clj-llm/build-token-breakdown key-texts))}))]
    {:text text :token-breakdown breakdown :zones zones}))

(defn extract-signature-metadata
  "Extract input and output keys from a signature (var or compiled map)."
  [signature]
  (let [sig (resolve-signature signature)]
    (clj-llm/extract-signature-metadata sig)))

(defmulti execute-dspy-operation
  "Execute DSPy operation using clj-llm.
   Dispatches on operation keyword (:predict, :chain-of-thought)."
  (fn [operation _signature _context _inputs] operation))

(defn- build-llm-call-opts
  "Shared kwarg builder for predict / chain-of-thought invocations.
   Forwards :stream? only when the BT node explicitly set it, so callers that
   pass nothing keep the current `on-chunk`-driven default behavior.
   Forwards :cache-zones (M7) so the Anthropic adapter can build structured
   system blocks with cache_control markers."
  [context lm-config usage-tracker on-chunk system-context token-breakdown
   cache-zones]
  (let [node-opts (:opts context)
        base {:lm-config lm-config
              :usage-tracker usage-tracker
              :system-context system-context
              :on-chunk on-chunk
              :input-token-breakdown token-breakdown}]
    (mapcat identity
            (cond-> base
              (contains? node-opts :stream?) (assoc :stream? (:stream? node-opts))
              (seq cache-zones) (assoc :cache-zones cache-zones)))))

(defmethod execute-dspy-operation :predict
  [_ signature context inputs]
  (let [sig (resolve-signature signature)
        lm-config (resolve-lm-config context)
        max-out (get-in inputs [:state :runtime-config :max-output-tokens] 0)
        lm-config (if (and lm-config (pos? max-out))
                    (assoc lm-config :max-tokens max-out)
                    lm-config)
        usage-tracker (resolve-usage-tracker context)
        on-chunk (get-in context [:opts :on-chunk])
        {:keys [text token-breakdown zones]}
        (build-system-prompt (:state inputs) (:stable-keys inputs))
        result (apply clj-llm/predict sig (:inputs inputs)
                      (build-llm-call-opts context lm-config usage-tracker
                                           on-chunk text token-breakdown zones))]
    {:outputs (:outputs result)
     :usage (:usage result)}))

(defmethod execute-dspy-operation :chain-of-thought
  [_ signature context inputs]
  (let [sig (resolve-signature signature)
        lm-config (resolve-lm-config context)
        max-out (get-in inputs [:state :runtime-config :max-output-tokens] 0)
        lm-config (if (and lm-config (pos? max-out))
                    (assoc lm-config :max-tokens max-out)
                    lm-config)
        usage-tracker (resolve-usage-tracker context)
        on-chunk (get-in context [:opts :on-chunk])
        {:keys [text token-breakdown zones]}
        (build-system-prompt (:state inputs) (:stable-keys inputs))
        result (apply clj-llm/chain-of-thought sig (:inputs inputs)
                      (build-llm-call-opts context lm-config usage-tracker
                                           on-chunk text token-breakdown zones))]
    {:outputs (:outputs result)
     :reasoning (:reasoning result)
     :usage (:usage result)}))

(defn dspy
  "Main DSPy action function for use in behavior trees.

   Expected opts in context:
   - :id          — node identifier
   - :signature   — DSPy signature (var or compiled map)
   - :operation   — :predict or :chain-of-thought
   - :stable-keys — (optional) extra keys to add to system-context beyond defaults.
                     Unioned with default-stable-keys. Each key is:
                     - Included in system-context (system message)
                     - Excluded from signature inputs (user message)
                     Custom keys get a generic '## <key-name>' section header.

   Reads inputs from st-memory, executes the DSPy operation, and stores
   outputs back into st-memory.

   Fires `:agent.dspy-action/pre` after inputs are computed and
   `:agent.dspy-action/post` on every exit (success / missing-inputs /
   exception), only when an `:agent` is present in context."
  [{{:keys [id signature operation stable-keys]} :opts
    :keys [st-memory agent]
    :as context}]
  (let [{:keys [input-keys]} (extract-signature-metadata signature)
        stable-keys (into default-stable-keys stable-keys)
        fire!       (when agent (force !fire-hook))
        base-event  {:agent agent :node-id id :signature signature
                     :operation operation :stable-keys stable-keys}]
    (try
      (let [state @st-memory
            ;; Build on-chunk callback via agent.core.bt/chunk-factory-handler
            ;; (lazy-resolved to avoid a hard dep from behavior-tree → agent).
            base-on-chunk (resolve-on-chunk-fn state st-memory id)
            ;; Wrap the chunk handler so each :content-delta also fires
            ;; :agent.dspy-action/chunk for hook-based UIs (iteration blocks).
            ;; The base handler still updates st-memory :llm-streaming-text
            ;; for legacy watch-based UIs during the migration window.
            on-chunk-fn (when base-on-chunk
                          (fn [chunk]
                            (base-on-chunk chunk)
                            (when (and fire! (= :content-delta (:type chunk)))
                              (fire! :agent.dspy-action/chunk
                                     {:agent agent
                                      :node-id id
                                      :signature signature
                                      :chunk (:text chunk)
                                      :accumulated (or (:llm-streaming-text @st-memory) "")}))))
            context (if on-chunk-fn
                      (assoc-in context [:opts :on-chunk] on-chunk-fn)
                      context)
            all-inputs (reduce (fn [acc key]
                                 (let [value (get state key)]
                                   (if (some? value)
                                     (assoc acc key value)
                                     acc)))
                               {} input-keys)
            filtered-inputs (apply dissoc all-inputs stable-keys)
            pre-event       (assoc base-event :inputs filtered-inputs)]
        (when fire! (fire! :agent.dspy-action/pre pre-event))
        (if (seq all-inputs)
          (let [result (execute-dspy-operation operation signature context
                                               {:inputs filtered-inputs :state state :stable-keys stable-keys})]
            ;; Batch all state updates (outputs + reasoning + usage) into a
            ;; single swap! so TUI watch handlers see all changes atomically.
            (swap! st-memory
                   (fn [m]
                     (let [m (reduce-kv assoc m (:outputs result))
                           m (if-let [reasoning (:reasoning result)]
                               (assoc m :last-reasoning reasoning)
                               m)
                           m (if-let [usage (:usage result)]
                               (update m :lm-usage (fnil conj []) usage)
                               m)]
                       m)))
            ;; Enrich the last tracker history entry with turn/iteration context
            ;; so /usage can display per-turn, per-iteration token breakdowns.
            ;; record-usage! already ran in llm.clj; we patch the entry here
            ;; because turn-id/iteration-count live in st-memory, not in llm.clj.
            (when-let [usage (:usage result)]
              (when-let [tracker (or (resolve-usage-tracker context)
                                     (clj-llm/get-global-tracker))]
                (let [turn-id (or (:turn-id state) (:turn-id @st-memory))
                      iter-count (or (:iteration-count state) (:iteration-count @st-memory))]
                  (when (or turn-id iter-count)
                    (swap! tracker update :history
                           (fn [h]
                             (if (seq h)
                               (update h (dec (count h)) assoc
                                       :turn-id turn-id
                                       :iteration iter-count)
                               h)))))))
            (mulog/debug ::dspy-completed :node-id id)
            (when fire!
              (fire! :agent.dspy-action/post
                     (assoc pre-event
                            :result    p/success
                            :outputs   (:outputs result)
                            :reasoning (:reasoning result)
                            :usage     (:usage result))))
            p/success)
          (do
            (mulog/warn ::dspy-missing-inputs :node-id id :input-keys input-keys)
            (when fire!
              (fire! :agent.dspy-action/post
                     (assoc pre-event :result p/failure :error "missing-inputs")))
            p/failure)))
      (catch Exception e
        (let [raw-text (get (ex-data e) :raw-text)
              msg (cond-> (str (.getMessage e))
                    raw-text (str "\nLLM raw text: " (subs raw-text 0 (min (count raw-text) 300))))]
          (mulog/error ::dspy-error :node-id id :message msg :exception e)
          (swap! st-memory assoc :dspy-error msg)
          (when fire!
            (fire! :agent.dspy-action/post
                   (assoc base-event :result p/failure :error msg)))
          p/failure)))))
