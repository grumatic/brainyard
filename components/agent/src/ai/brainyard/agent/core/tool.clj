;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.tool
  "Tool infrastructure, unified registry, macros, and execution.
   Merged from core.defs + cloudcast.backend.agent.tool.

   Provides:
   - deftool — unified macro for defining tools (commands, skills, agents)
   - defcommand, defskill, defagent — backwards-compatible wrappers around deftool
   - get-tool-defs — single accessor with :id and :type filtering
   - invoke-tool — single dispatcher for all tool types
   - call-tool — dispatcher with hooks/permissions/visibility/agent guards
   - fn->tool, def->tool — converters for plain fns and registered defs
   - bind-tools — aggregate tools from :functions and :tools (any type)
   - ->tools — flat helper that converts var/string/keyword refs to descriptors"
  (:require [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.util.interface :as util]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Malli [:map ...] Entry Helpers
;; ============================================================================

(defn malli-map-entries
  "Extract entry vectors from a [:map ...] schema form. Returns nil for
   [:map] (no entries) so (seq (malli-map-entries ...)) works as a guard.
   Skips map-level props if present."
  [schema]
  (when (and (vector? schema) (= :map (first schema)))
    (let [entries (rest schema)]
      (seq (if (map? (first entries))
             (rest entries)
             entries)))))

(defn malli-map-entry-key [entry] (first entry))

(defn malli-map-entry-props
  [entry]
  (when (and (> (count entry) 2) (map? (second entry)))
    (second entry)))

(defn malli-map-entry-schema
  [entry]
  (if (and (> (count entry) 2) (map? (second entry)))
    (nth entry 2)
    (second entry)))

;; ============================================================================
;; Schema Type Conversion
;; ============================================================================

(defn schema->type
  "Convert a deftool :input-schema (Malli [:map ...]) to JSON-schema-style
   parameter properties.

   Each map entry may carry entry-level props (e.g. {:optional true}) and
   a value schema with its own props (e.g. [:string {:desc \"x\"}]).
   :optional is read from entry props; :desc/:default from value schema
   props via `parse-malli-field`.

   Returns: {input-key {:type str ... [:desc :default :optional]?}}."
  [input-schema]
  (reduce
   (fn [acc entry]
     (let [k           (malli-map-entry-key entry)
           entry-props (malli-map-entry-props entry)
           raw-schema  (malli-map-entry-schema entry)
           {:keys [schema desc default]} (clj-llm/parse-malli-field raw-schema)
           optional    (:optional entry-props)
           json-schema (clj-llm/malli->json-schema schema)]
       (assoc acc k (cond-> json-schema
                      desc     (assoc :desc desc)
                      default  (assoc :default default)
                      optional (assoc :optional optional)))))
   {}
   (malli-map-entries input-schema)))

;; ============================================================================
;; Global Registry
;; ============================================================================

(defonce !tool-defs (atom {}))

;; ============================================================================
;; Unified Tool Macro
;; ============================================================================

(defmacro deftool
  "Define a tool and register it in the global !tool-defs registry.

   Usage:
     (deftool task$list
       \"list background tasks\"
       (fn [& {:keys [status]}] ...)
       :input-schema [:map [:status {:optional true} [:string {:desc \"Optional status filter\"}]]]
       :output-schema [:map [:tasks [:any {:desc \"Vector of task summaries\"}]]])

     (deftool react-agent
       \"ReAct agent\"
       react-agent-fn
       :type :agent
       :bt-factory react-behavior-tree
       :agent-tools {:tools [...]}
       :instruction \"...\")

   Options:
     :type             - :tool (default), :command, :skill, or :agent
     :input-schema     - Malli [:map ...] schema (optional, defaults to [:map])
     :output-schema    - Malli [:map ...] schema (optional, defaults to [:map])
     :aliases          - Alternative names (for commands)
     :tool-use-control - Visibility/permission config
     All other kwargs are passed through as metadata."
  [tool-name description tool-fn & {:keys [type input-schema output-schema] :as args}]
  (when (and input-schema
             (not (and (vector? input-schema) (= :map (first input-schema)))))
    (throw (ex-info (str "deftool " tool-name " :input-schema must be a [:map ...] schema, got: " (pr-str input-schema))
                    {:tool-name tool-name :input-schema input-schema})))
  (let [tool-type (or type :tool)
        id (keyword tool-name)
        options (assoc args :id id :type tool-type :description description
                       :input-schema (or input-schema [:map])
                       :output-schema (or output-schema [:map]))
        ;; Auto-injected metadata keys (:id :type :description :input-schema
        ;; :output-schema) would collide with legitimate caller args of the
        ;; same name (e.g. RAG passing :id, agent calls passing instance :id,
        ;; skills passing :description). Rename them to :_deftool$<key>
        ;; in the per-call merge map so tool-fns can still read their own
        ;; registered meta when they need it (see react-skill$thinking-loop)
        ;; while caller args dominate on the bare keys.
        meta-keys [:id :type :description :input-schema :output-schema]
        merge-opts (reduce (fn [acc k]
                             (-> acc
                                 (dissoc k)
                                 (assoc (keyword (str "_deftool$" (name k)))
                                        (get options k))))
                           options
                           meta-keys)]
    `(let [;; Non-metadata options (bt-factory, agent-tools, etc.) are merged
           ;; into every call so agent-type tools can read their own config.
           ;; Auto-injected metadata is renamed to :_deftool$<key> so it
           ;; survives the merge without colliding with caller args.
           merge-opts# ~merge-opts
           ;; Map-shaped "extras" must DEEP-merge so a caller adding their
           ;; own entries (e.g. the TUI passing :config-extra {:working-dir
           ;; …}) does not clobber defaults the defagent author put in the
           ;; same key (e.g. :config-extra {:enable-memory-capture true}).
           ;; Without this, the caller's :config-extra silently replaces
           ;; the author's. Caller still wins on key collisions.
           merge-extras# (fn [base# overrides#]
                           (reduce (fn [acc# k#]
                                     (let [m# (merge (get base# k#)
                                                     (get overrides# k#))]
                                       (if (seq m#) (assoc acc# k# m#) acc#)))
                                   (merge base# overrides#)
                                   [:config-extra :st-memory-extra]))
           ;; The `(fn? tool-fn)` check is deferred into the wrap-fn body
           ;; (rather than evaluated as part of this top-level `let`) so
           ;; cross-namespace Var references like `coact/run-coact-derived`
           ;; don't race the require graph at GraalVM native-image
           ;; build-time class-init. With the check at the let-site, the
           ;; bare symbol was dereferenced when `search_agent__init.<clinit>`
           ;; ran — which could be before `coact_agent__init.<clinit>`
           ;; finished `(defn run-coact-derived ...)` — and the deftool
           ;; threw with an unbound Var. Moving it here means resolution
           ;; happens at first call, by which point both nses are fully
           ;; loaded. See docs/design/native-image-design.md §14.
           wrap-fn# (fn [& {:as call-args#}]
                      (let [tf# ~tool-fn]
                        (if (fn? tf#)
                          (tf# (merge-extras# merge-opts# call-args#))
                          (throw (ex-info
                                  (str "tool-fn must be a function in deftool: " '~tool-name)
                                  {:tool-name '~tool-name :type ~tool-type})))))]
       (def ~tool-name (with-meta wrap-fn# ~options))
       (swap! !tool-defs assoc ~id {:id ~id
                                    :type ~tool-type
                                    :fn (var ~tool-name)
                                    :meta ~options}))))

;; ============================================================================
;; Backwards-Compatible Wrapper Macros
;; ============================================================================

(defmacro defcommand
  "Define a command. Thin wrapper around deftool with :type :command."
  [cmd-name description call-fn & {:as args}]
  `(deftool ~cmd-name ~description ~call-fn ~@(mapcat identity (assoc args :type :command))))

(defmacro defskill
  "Define a skill. Thin wrapper around deftool with :type :skill."
  [skill-name description use-fn & {:as args}]
  `(deftool ~skill-name ~description ~use-fn ~@(mapcat identity (assoc args :type :skill))))

(defmacro defagent
  "Define an agent. Thin wrapper around deftool with :type :agent."
  [agent-name description ask-fn & {:as args}]
  `(deftool ~agent-name ~description ~ask-fn ~@(mapcat identity (assoc args :type :agent))))

;; ============================================================================
;; Registry Accessor & Reset
;; ============================================================================

(defn reset-tool-registry!
  "Reset the tool registry. For testing — clears all defcommand/defskill/defagent registrations."
  []
  (reset! !tool-defs {}))

(defn get-tool-defs
  "Get tool definitions from the unified registry.
   With no args, returns the full map.
   With :id, returns a single entry.
   With :type, returns entries filtered by type (:tool/:command/:skill/:agent)."
  ([] @!tool-defs)
  ([& {:keys [id type]}]
   (cond id   (get @!tool-defs id)
         type (into {} (filter #(= type (:type (val %))) @!tool-defs))
         :else @!tool-defs)))

;; ============================================================================
;; Dispatcher Function
;; ============================================================================

(defn invoke-tool
  "Invoke a registered tool (command, skill, or agent) by ID.
   BARE dispatcher — no hooks, no permissions, no visibility checks.

   Direct invoke-tool should only be used internally."
  [id & {:as options}]
  (let [str-id (util/kw->str id)]
    (if-let [tool-def (get @!tool-defs id)]
      (if-let [tool-fn (:fn tool-def)]
        (cond
          (instance? clojure.lang.IDeref tool-fn) (apply @tool-fn [options])
          (fn? tool-fn) (apply tool-fn [options])
          :else (mulog/error ::tool-fn-type-invalid :tool-id str-id))
        {:error-message (format "%s tool fn is not defined!" str-id)})
      {:error-message (format "%s tool is not registered!, choose one of tools - %s"
                              str-id (mapv util/kw->str (keys @!tool-defs)))})))

;; ============================================================================
;; Tool Invocation with Hooks / Permissions / Guards
;; ============================================================================

;; Forward declarations for functions defined later in this file
(declare tool-visible? resolve-agent-ref def->tool check-permission
         coerce-tool-args llm-args-transformer)

;; Lazy-resolved refs for deps that would create circular requires
(def ^:private !get-config (delay (requiring-resolve 'ai.brainyard.agent.core.config/get-config)))
(def ^:private !generate-instance-id
  (delay (requiring-resolve 'ai.brainyard.agent.core.agent/generate-instance-id)))

(defn- do-call-tool--agent
  "Dispatch a registered :agent-type tool with subagent guards.

   Guards, in order:
     1. kill switch — `enable-subagent-calls` in the caller's st-memory-init
     2. depth limit — `proto/*call-depth*` vs `get-max-agent-call-depth`
     3. circular detection — target defagent-type must not already be in `proto/*call-chain*`

   When all guards pass, increments *call-depth* / *call-chain* and always
   creates a fresh sub-agent instance via `invoke-tool`. A unique instance-id
   (:<target-type>/<suffix>) is auto-injected so each invocation produces a
   distinct agent — no cross-call reuse. Any `clojure.lang.Agent` ref returned
   is awaited via `resolve-agent-ref`."
  [agent tool-id tool-name parsed-args]
  (let [max-depth (@!get-config agent :max-agent-call-depth)
        target-id tool-id
        current-agent-id (when agent (proto/agent-id agent))]
    (cond
      (not (@!get-config agent :enable-subagent-calls))
      {:error-message "Subagent calls are disabled (enable-subagent-calls=false)"}

      (>= proto/*call-depth* max-depth)
      {:error-message (format "Agent call depth limit reached (%d/%d). Cannot call '%s'."
                              proto/*call-depth* max-depth tool-name)}

      (some #{target-id} proto/*call-chain*)
      {:error-message (format "Circular agent call detected: %s -> %s. Chain: %s"
                              (name (or current-agent-id :unknown)) tool-name
                              (str/join " -> " (conj (mapv name proto/*call-chain*) tool-name)))}

      :else
      (binding [proto/*call-depth* (inc proto/*call-depth*)
                proto/*call-chain* (conj proto/*call-chain*
                                         (or current-agent-id :unknown))]
        (let [agent-session (if agent
                              {:user-id (proto/user-id agent)
                               :session-id (proto/session-id agent)}
                              (:agent-session parsed-args))
              instance-id (or (:id parsed-args) (@!generate-instance-id target-id))
              ;; Sub-agent instances are ephemeral — auto-close after ask completes
              ;; so we don't accumulate dead entries in !agent-registry. The caller
              ;; may override by passing an explicit :auto-close? false in parsed-args.
              auto-close? (get parsed-args :auto-close? (some? agent))]
          (resolve-agent-ref
           (apply invoke-tool target-id
                  (mapcat identity
                          (cond-> parsed-args
                            true          (assoc :id instance-id :auto-close? auto-close?)
                            agent-session (assoc :agent-session agent-session)
                            agent         (assoc :parent-agent agent))))))))))

(defn- blocked-tool-result
  "Synthetic tool-result produced when a :agent.tool-use/pre hook returns :block.
   Carries enough metadata for upstream BT actions to terminate the loop
   and surface the reason / synthesized answer to the user."
  [decision]
  (cond-> {:hook-blocked true
           :reason       (or (:reason decision) "blocked by :agent.tool-use/pre hook")
           :by           (:by decision)}
    (:answer decision) (assoc :answer (:answer decision))))

(defn tool-pre-hook
  "Fire `:agent.tool-use/pre` decision hook. Returns
   {:verdict :args :event-base :decision :early-result} where
   :early-result is non-nil for :replace/:block (tool body should be skipped)."
  [agent tool-name args]
  (let [event-base {:agent agent :tool-name tool-name :args args
                    :call-id (random-uuid)
                    :depth (hooks/current-depth)}
        decision   (hooks/fire-decision! :agent.tool-use/pre event-base)
        verdict    (:result decision)]
    {:verdict    verdict
     :args       (case verdict :modify-args (:args decision) args)
     :event-base event-base
     :decision   decision
     :early-result (case verdict
                     :replace (:replacement decision)
                     :block   (blocked-tool-result decision)
                     nil)}))

(defn tool-post-hook
  "Fire `:agent.tool-use/post` observer hook."
  [{:keys [event-base verdict decision]} result]
  (hooks/fire! :agent.tool-use/post
               (cond-> (assoc event-base :result result)
                 (= :replace verdict)     (assoc :hook-replaced true :replaced-by (:by decision))
                 (= :block verdict)       (assoc :hook-blocked true  :blocked-by  (:by decision))
                 (= :modify-args verdict) (assoc :hook-modified-args true
                                                 :modified-by (:by decision)
                                                 :effective-args (:args decision)))))

(defn- dispatch-with-hooks
  "Fire `:agent.tool-use/pre` as a gated event, dispatch via `run-fn` (or replace /
   block per the decision), then fire `:agent.tool-use/post`."
  [agent tool-name args run-fn]
  (let [pre    (tool-pre-hook agent tool-name args)
        result (or (:early-result pre) (run-fn (:args pre)))]
    (tool-post-hook pre result)
    result))

(defn- do-call-tool--registered-fn
  "Registry dispatch path for a tool resolved in `!tool-defs`.

   Steps:
     1. visibility check via `tool-visible?` against the current agent's id
     2. :agent.tool-use/pre gated hook — may allow / modify-args / replace / block
     3. dispatch (or skip on replace/block) — `do-call-tool--agent` for :agent
        types, `invoke-tool` otherwise
     4. :agent.tool-use/post observer hook

   Returns the raw tool result, or an `{:error-message ...}` map if not visible."
  [agent tool-id tool-name tool-def parsed-args]
  (let [agent-id (:agent-id agent)
        meta-info (:meta tool-def)
        tool-type (:type tool-def)]
    (if (and meta-info (not (tool-visible? tool-def agent-id)))
      {:error-message (format "tool '%s' is not allowed for current agent" tool-name)}
      (dispatch-with-hooks
       agent tool-name parsed-args
       (fn [args]
         (if (= tool-type :agent)
           (do-call-tool--agent agent tool-id tool-name args)
           (apply invoke-tool tool-id (mapcat identity args))))))))

(defn- do-call-tool--bound-fn
  "Dispatch path for tools resolved via the caller-supplied `tools-fn-map`
   (i.e. bound to the agent through `bind-tools`).

   Routes through the same `:agent.tool-use/pre` / `:agent.tool-use/post` hook chain as the
   registered-fn path so gating policies (loop-guard, allowlists, etc.)
   cover both. `:parent-agent` and `:agent-session` are injected into the
   args inside `run-fn`, AFTER the hook has seen them — hooks see only the
   user-meaningful args, not agent bookkeeping. Catches exceptions and
   returns an `{:error-message ...}` map. Resolves async
   `clojure.lang.Agent` refs."
  [agent tool-fn args tool-name]
  (try
    (dispatch-with-hooks
     agent tool-name args
     (fn [a]
       (let [a (if agent
                 (assoc a :parent-agent agent
                        :agent-session {:user-id (proto/user-id agent)
                                        :session-id (proto/session-id agent)})
                 a)]
         (-> (tool-fn a) resolve-agent-ref))))
    (catch Exception e
      (mulog/error ::tool-call-error :tool-name tool-name :message (ex-message e))
      {:error-message (ex-message e)})))

(defn- normalize-tool-args
  "Normalize raw tool-args into a flat map (no coercion).
   Accepts:
     - Already-flat map: {k v}                        → returned as-is
     - Standard LLM vector: [{:name k :value v} ...]  → {(name k) v}
     - Compact LLM vector:  [{k v} ...]               → {(name k) v}
     - Anything else                                  → {}
   Returns a flat map. Coercion is applied separately by the caller using
   `coerce-tool-args` when type info is available."
  [tool-args]
  (cond
    (map? tool-args) tool-args

    (vector? tool-args)
    (let [valid (filterv #(and (:name %) (contains? % :value)) tool-args)]
      (if (seq valid)
        (reduce #(assoc %1 (:name %2) (:value %2)) {} valid)
        (reduce (fn [acc m]
                  (reduce-kv (fn [a k v] (assoc a (name k) v)) acc m))
                {} tool-args)))

    :else {}))

(defn inputs->malli-map-schema
  "Normalize an :input-schema (Malli [:map ...]) for validation. Optional
   fields (those with {:optional true} in entry props) are wrapped in
   `[:maybe schema]` so an explicit `nil` is accepted (LLMs often emit
   `null` for skipped fields). Non-optional entries pass through unchanged."
  [input-schema]
  (into [:map]
        (map (fn [entry]
               (let [k      (malli-map-entry-key entry)
                     props  (malli-map-entry-props entry)
                     schema (malli-map-entry-schema entry)]
                 (if (:optional props)
                   [k props [:maybe schema]]
                   entry))))
        (malli-map-entries input-schema)))

(defn call-tool
  "Dispatch a tool call. Auto-detects the resolution path (and thus the schema
   format) from the tool's location:

     1. tool-name in bound `:tools`        → fn-map path (schema-format :json),
                                              uses tools-fn-map[tool-name]
                                              and bound entry's :parameters/:properties
                                              for coercion.
     2. tool-name in `!tool-defs` registry → registry path (schema-format :malli),
                                              uses (:fn tool-def), coerces via
                                              schema->type of the :input-schema,
                                              then validates with `m/explain`.
     3. neither                            → returns {:error-message ...}.

   `tool-args` is normalized first (accepts the LLM standard
   `[{:name k :value v} ...]` form, the compact `[{k v}]` form, or an
   already-flat map). Permission is checked before dispatch, and `*current-agent*`
   is bound when `agent` is provided and not already current.

   Usage:
     ;; Simple Clojure caller — registry path
     (call-tool :task$run {:job-type :bash :command \"ls\"})
     (call-tool :task$run {:job-type :bash :command \"ls\"} :agent my-agent)

     ;; BT/LLM caller — passes the bound :tools and :tools-fn-map
     (call-tool tool-name tool-args
                :agent agent
                :tools tools
                :tools-fn-map tools-fn-map)"
  [tool-id tool-args & {:keys [agent tools tools-fn-map]}]
  (when (Thread/interrupted)
    (throw (ex-info "Tool call interrupted" {:tool (str tool-id) :interrupted true})))
  (let [tool-name      (util/kw->str tool-id)
        ;; Step 1: normalize raw tool-args to a flat map (no coercion yet)
        normalized-args (normalize-tool-args tool-args)
        ;; Step 2: auto-detect dispatch path
        bound-entry    (some #(when (= (:name %) tool-name) %) tools)
        bound-fn       (when bound-entry (get tools-fn-map tool-name))
        ;; Direct lookup by the full id. Returns [matched-id tool-def] or nil.
        [registry-id tool-def]
        (when-not bound-fn
          (when-let [td (get-tool-defs :id (keyword tool-name))]
            [(keyword tool-name) td]))
        schema-format  (cond
                         bound-fn :json
                         tool-def :malli
                         :else    nil)
        permission     (check-permission tool-name)
        run (fn []
              (cond
                (= permission :denied)
                {:error-message "Tool execution denied by permission configuration."}

                (nil? schema-format)
                {:error-message (format "%s is not bound as a tool, the available tools: %s"
                                        tool-name (mapv :name tools))}

                ;; ---- :json — bound fn-map path (LLM-bound tools) ----
                ;; For deftool-registered tools, look up :input-schema from registry
                ;; and decode with llm-args-transformer so :keyword fields convert
                ;; string→keyword correctly. Otherwise fall back to coerce-tool-args
                ;; (plain fn->tool path without registry entry).
                ;; bind-tools' fn wrappers receive {string-key value} maps; preserve
                ;; string keys after coercion.
                (= schema-format :json)
                (let [tool-id (keyword tool-name)
                      registry-def (get-tool-defs :id tool-id)
                      input-schema (get-in registry-def [:meta :input-schema])
                      coerced (if (and input-schema (seq (malli-map-entries input-schema)))
                                ;; Malli decode path: keywordize, decode, then back to string keys
                                (let [kw-args (update-keys normalized-args keyword)
                                      inputs-schema (inputs->malli-map-schema input-schema)
                                      decoded (m/decode inputs-schema kw-args llm-args-transformer)]
                                  (update-keys decoded name))
                                ;; JSON coerce path: for plain fn->tool without registry entry
                                (let [props (get-in bound-entry [:parameters :properties])]
                                  (coerce-tool-args normalized-args props)))]
                  (do-call-tool--bound-fn agent bound-fn coerced tool-name))

                ;; ---- :malli — registry path (deftool-registered tools) ----
                ;; Keywordize top-level arg keys (LLM JSON has string keys, but
                ;; registry dispatch expects kwargs), decode via `llm-args-transformer`
                ;; against the declared inputs (handles string→keyword/int/bool at
                ;; any depth that the schema describes), then validate.
                (= schema-format :malli)
                (let [raw-schema    (get-in tool-def [:meta :input-schema])
                      inputs-schema (when (seq (malli-map-entries raw-schema))
                                      (inputs->malli-map-schema raw-schema))
                      kw-args0      (update-keys normalized-args keyword)
                      kw-args       (if inputs-schema
                                      (m/decode inputs-schema kw-args0 llm-args-transformer)
                                      kw-args0)
                      malli-error   (when inputs-schema (m/explain inputs-schema kw-args))]
                  (if malli-error
                    {:error-message (format "Invalid tool args for %s: %s"
                                            tool-name (pr-str (me/humanize malli-error)))}
                    (do-call-tool--registered-fn agent registry-id tool-name tool-def kw-args)))))]
    (mulog/debug ::call-tool
                 :tool-id tool-id :tool-name tool-name
                 :tool-args tool-args :schema-format schema-format)
    (if (and agent (not= agent proto/*current-agent*))
      (binding [proto/*current-agent* agent]
        (run))
      (run))))

;; ============================================================================
;; Utility
;; ============================================================================

(defn- validate-fn-var [fn-var]
  (try
    (deref fn-var)
    (catch Exception e
      (throw (ex-info
              (format "fn-var is invalid, seems not yet loaded! - %s" (ex-message e))
              {:fn-var fn-var})))))

;; ============================================================================
;; Tool Argument Coercion
;; ============================================================================

(defn coerce-value
  "Coerce a string value to the type indicated by type-str.
   Falls back to the original value on parse failure."
  [value type-str]
  (if (or (not (string? value))
          (nil? type-str)
          (= "string" type-str))
    value
    (try
      (case type-str
        "integer" (or (parse-long value) value)
        "number"  (or (parse-double value) value)
        "boolean" (case value "true" true "false" false value)
        ;; Strip a leading ':' so callers can pass either "nrepl" or ":nrepl".
        "keyword" (keyword (if (.startsWith ^String value ":")
                             (subs value 1)
                             value))
        ("array" "vector") (let [parsed (json/read-str value :key-fn keyword)] (if (vector? parsed) parsed value))
        "object"  (let [parsed (json/read-str value :key-fn keyword)] (if (map? parsed) parsed value))
        value)
      (catch Exception _ value))))

(defn- coerce-tool-args
  "Coerce each arg in the map using type info from the tool's parameter properties.
   properties is a map of {param-keyword {:type \"integer\" ...}}."
  [args properties]
  (if (empty? properties)
    args
    (reduce-kv (fn [acc k v]
                 (let [type-str (get-in properties [(keyword k) :type])]
                   (assoc acc k (coerce-value v type-str))))
               {}
               args)))

(def llm-args-transformer
  "Malli transformer for LLM tool-call args on the registry path. Walks the
   declared input schema and coerces leaves at any depth — so a
   `[:map [:foo :keyword] [:n :int]]` input decodes a `{:foo \"x\" :n \"5\"}`
   wire payload into `{:foo :x :n 5}`.

   Composition: our `:keyword` override runs FIRST (first-wins in malli
   composition); `mt/string-transformer` covers the rest (`:int`, `:boolean`,
   etc.).

   The override exists because `mt/string-transformer`'s `:keyword` decoder
   treats a leading `:` as a namespace separator (`\":a\"` → `::a`), but LLMs
   routinely hedge by sending either `\"a\"` or `\":a\"` for a keyword field —
   both should yield `:a`.

   The `:enum` decoder handles the dual calling convention for string-member
   enums: a JSON tool-call delivers the value as a wire STRING (`\"gates\"`),
   while a sandbox code-fence delivers it as a KEYWORD (`:gates`) because the
   agent writes Clojure. It normalizes a keyword — or a colon-hedged string
   `\":gates\"` — to its bare name so either form satisfies `[:enum \"gates\" …]`.
   Guarded: it only fires for all-string enums, and only substitutes when the
   normalized form is a real member — non-string enums and genuinely-invalid
   values pass through untouched so validation still reports a clean error."
  (mt/transformer
   {:name :llm-keyword
    :decoders
    {:keyword
     (fn [v]
       (if (string? v)
         (keyword (if (str/starts-with? v ":") (subs v 1) v))
         v))
     :enum
     {:compile
      (fn [schema _]
        (let [members (set (m/children schema))]
          (when (every? string? members)
            (fn [v]
              (let [s (cond
                        (keyword? v)                               (name v)
                        (and (string? v) (str/starts-with? v ":")) (subs v 1)
                        :else                                      v)]
                (if (contains? members s) s v))))))}}}
   mt/string-transformer))

;; ============================================================================
;; Tool Converters
;; ============================================================================

(defn fn->tool
  "Convert a Clojure function var into a tool descriptor using metadata."
  [obj]
  (let [tool-fn (cond
                  (and (var? obj) (fn? @obj) (:arglists (meta obj))) obj
                  :else (throw (ex-info "invalid fn object!" {})))
        fn-meta (meta tool-fn)
        arglists (first (:arglists fn-meta))
        args (into {}
                   (map
                    (fn [arg]
                      (let [arg-meta (meta arg)
                            default (:default arg-meta)
                            optional (:optional arg-meta)]
                        [(keyword arg) (cond-> {:type (:type arg-meta)
                                                :description (:desc arg-meta)}
                                         default (assoc :default default)
                                         optional (assoc :optional optional))]))
                    arglists))]
    {:name (name (:name fn-meta))
     :description (:desc fn-meta)
     :tool-fn-type :defn
     :tool-fn tool-fn
     :parameters {:type "object"
                  :properties args
                  :required (->> arglists
                                 (filter #(not (:optional (get args (keyword %)))))
                                 (mapv name))}}))

(defn def->tool
  "Convert a registered tool definition (tool, command, skill, or agent) to a tool descriptor.
   Resolves from !tool-defs by keyword, string, or var (checks :id in meta of @obj)."
  [obj]
  (let [tool-fn (cond
                  (keyword? obj) (:fn (get (get-tool-defs) obj))
                  (string? obj) (:fn (get (get-tool-defs) (keyword obj)))
                  (and (var? obj) (fn? @obj) (:id (meta @obj))) obj
                  :else (throw (ex-info "invalid tool object!" {:obj obj})))
        _ (validate-fn-var tool-fn)
        {:keys [id type description input-schema]} (meta @tool-fn)
        fn-name (util/kw->str id)
        entries (malli-map-entries input-schema)]
    {:name fn-name
     :description description
     :tool-fn-type type
     :tool-fn tool-fn
     :parameters {:type "object"
                  :properties (if entries
                                (schema->type input-schema)
                                {})
                  :required (if entries
                              (->> entries
                                   (keep (fn [entry]
                                           (when-not (:optional (malli-map-entry-props entry))
                                             (name (malli-map-entry-key entry)))))
                                   vec)
                              [])}}))

;; ============================================================================
;; Tool Builder
;; ============================================================================

(defn ->tools
  "Convert a flat sequence of tool refs (vars/strings/keywords) into tool descriptors."
  [objs get-meta-fn]
  (mapv (fn [item]
          (if (or (var? item) (string? item) (keyword? item))
            (get-meta-fn item)
            (throw (ex-info "unknown tool object type!" {:item item}))))
        objs))

;; ============================================================================
;; Bind Tools
;; ============================================================================

(defn- tool-ref->var
  "Resolve a tool reference (var, keyword, or string id) to the underlying var.
   Returns nil if it can't be resolved — bind-tools' filter then treats it as
   visible so def->tool reports the error in its usual way."
  [obj]
  (cond
    (and (var? obj) (fn? @obj) (:id (meta @obj))) obj
    (keyword? obj) (:fn (get (get-tool-defs) obj))
    (string? obj)  (:fn (get (get-tool-defs) (keyword obj)))
    :else nil))

(defn bind-tools
  "Aggregate tools from various sources into [tools-vec tools-fn-map].

   Options:
     :functions  - Clojure function vars (raw fns, not registered via defcommand/etc.)
     :tools      - Registered tool/command/skill/agent vars or keywords. Each
                   entry's :type is read from its metadata — callers no longer
                   need to separate by type.
     :agent-id   - When supplied, tools hidden by `:tool-use-control` (`:visibility
                   :hidden`, or `:allow`/`:deny` patterns that exclude this agent)
                   are dropped before binding — so the LLM tool roster matches
                   what's actually invocable.

   Returns: [tools-vec tools-fn-map]
     tools-vec    - Vector of tool descriptors (without :tool-fn/:tool-fn-type)
     tools-fn-map - Map of tool-name -> execution fn"
  [& {:keys [functions tools agent-id]}]
  (let [visible-tools (if agent-id
                        (filterv (fn [t]
                                   (if-let [v (tool-ref->var t)]
                                     (tool-visible? {:meta (meta @v)} agent-id)
                                     true))
                                 tools)
                        tools)
        tool-list (cond-> []
                    functions     (into (->tools functions fn->tool))
                    visible-tools (into (->tools visible-tools def->tool)))
        tools-fn-map (reduce
                      (fn [acc tool]
                        (let [{:keys [tool-fn tool-fn-type parameters]} tool
                              arglists (map #(name %) (keys (:properties parameters)))]
                          (conj acc [(:name tool) #(apply tool-fn
                                                          (let [agent-session {:user-id (proto/get-current-user-id)
                                                                               :session-id (proto/get-current-session-id)}]
                                                            (case tool-fn-type
                                                              (:tool :command :skill) [(update-keys % keyword)]
                                                              :agent
                                                              [(assoc (update-keys % keyword)
                                                                      :agent-session agent-session
                                                                      :parent-agent proto/*current-agent*)]
                                                              ;; default case - positional arguments for :defn or nil
                                                              (map (fn [arg] (get % arg)) arglists))))])))
                      {}
                      tool-list)]
    [(mapv #(dissoc % :tool-fn) tool-list) tools-fn-map]))

;; ============================================================================
;; Permission Checking
;; ============================================================================

(def permission-config {:approval []
                        :deny []
                        :allow []})

(defn- match-items [items target]
  (reduce (fn [acc item]
            (if (re-find (re-pattern item) target)
              (reduced true)
              acc))
          false items))

(defn check-permission [tool-name]
  (cond
    (match-items (:approval permission-config) tool-name) :approval-required
    (match-items (:deny permission-config) tool-name) :denied
    (match-items (:allow permission-config) tool-name) :allowed
    :else :allowed))

;; ============================================================================
;; Tool Use Control (Unified Visibility)
;; ============================================================================

(defn- glob->regex
  "Convert a glob pattern string to a regex Pattern.
   Supports * (any chars) and ? (single char)."
  [pattern]
  (re-pattern
   (-> (str "^" pattern "$")
       (str/replace "." "\\\\.")
       (str/replace "*" ".*")
       (str/replace "?" "."))))

(defn- glob-matches?
  "Check if agent-id-str matches any of the glob pattern strings."
  [patterns agent-id-str]
  (boolean (some #(re-matches (glob->regex %) agent-id-str) patterns)))

(defn tool-visible?
  "Check if a tool def is visible to the given agent-id keyword.
   Reads :tool-use-control from the tool's :meta (or the map itself).

   :tool-use-control priority:
   1. {:visibility :hidden}           — invisible to all agents
   2. {:allow [\"react-*\"]}           — only matching agents can see/call
   3. {:deny [\"coact-*\"]}            — all except matching agents
   4. {} or nil                       — visible to all"
  [tool-def agent-id]
  (let [perm (or (:tool-use-control (:meta tool-def))
                 (:tool-use-control tool-def)
                 {})
        agent-str (if agent-id (util/kw->str agent-id) "")]
    (cond
      (= :hidden (:visibility perm)) false
      (seq (:allow perm)) (glob-matches? (:allow perm) agent-str)
      (seq (:deny perm))  (not (glob-matches? (:deny perm) agent-str))
      :else true)))

;; ============================================================================
;; Agent Ref Resolution
;; ============================================================================

(def ^:const agent-ref-timeout-ms
  "Timeout for initial agent ref resolution before falling back to a background task."
  30000)

;; Side-channel for agent refs that can't be serialized into task job-config.
;; Key: task-id keyword, Value: clojure.lang.Agent ref.
;; Entries are cleaned up after the background task completes.
(defonce !pending-agent-refs (atom {}))

(defn- fallback-to-task
  "Create a background task that awaits the agent ref and stores the result.
   Uses the task manager's public API to create a task, then runs a future
   that awaits the agent-ref and updates the task state directly via !tasks.
   Returns a message instructing the LLM to poll the task."
  [agent-ref]
  (let [get-mgr (requiring-resolve 'ai.brainyard.agent.task.manager/get-default-manager)
        create  (requiring-resolve 'ai.brainyard.agent.task.protocol/create-task)]
    (if-let [mgr (get-mgr)]
      (let [task (create mgr "agent-ref-await" :tool {})
            task-id (:id task)
            !tasks  @(requiring-resolve 'ai.brainyard.agent.task.manager/!tasks)
            on-output (fn [line]
                        (swap! (:output-lines task)
                               (fn [lines]
                                 (let [lines (conj lines line)]
                                   (if (> (count lines) (:max-output-lines task))
                                     (subvec lines (- (count lines) (:max-output-lines task)))
                                     lines)))))
            ;; Store agent-ref in side-channel and run awaiter in a future
            _ (swap! !pending-agent-refs assoc task-id agent-ref)
            fut (future
                  (try
                    (on-output "Awaiting agent result...")
                    (let [start-ms (System/currentTimeMillis)
                          output (deref (future (await agent-ref) (:output @agent-ref))
                                        300000
                                        {:error-message "Agent timed out (300s)"})
                          result (or output {:error-message "Agent returned nil output"})
                          elapsed (- (System/currentTimeMillis) start-ms)]
                      (on-output (str "Completed in " elapsed "ms"))
                      (on-output (str "Result: " (pr-str result)))
                      (swap! !tasks update task-id
                             #(when % (assoc % :status :completed
                                             :completed-at (System/currentTimeMillis)
                                             :result {:result result})))
                      (swap! !pending-agent-refs dissoc task-id)
                      {:result result})
                    (catch Exception e
                      (on-output (str "Failed: " (ex-message e)))
                      (swap! !tasks update task-id
                             #(when % (assoc % :status :failed
                                             :completed-at (System/currentTimeMillis)
                                             :result {:error (ex-message e)})))
                      (swap! !pending-agent-refs dissoc task-id)
                      {:error (ex-message e)})))]
        ;; Mark task as running with the future ref for cancellation
        (swap! !tasks update task-id
               #(when % (assoc % :status :running
                               :started-at (System/currentTimeMillis)
                               :future-ref fut)))
        {:status "running-in-background"
         :task-id (name task-id)
         :message (str "Agent tool is still running. Use task$detail with task-id=\""
                       (name task-id) "\" to check status; add :last-n N for the captured output tail.")})
      ;; No task manager — fall back to blocking wait
      (let [output (deref (future (await agent-ref) (:output @agent-ref))
                          300000
                          {:error-message "Agent timed out (300s)"})]
        (or output {:error-message "Agent returned nil output"})))))

(defn resolve-agent-ref
  "If result is a Clojure agent ref (from ask-async), await it with a 30s timeout.
   If the agent completes within 30s, return the result directly.
   If it times out, create a background task to await it and return a message
   instructing the LLM to poll the task status.
   Non-agent results pass through unchanged."
  [result]
  (if (instance? clojure.lang.Agent result)
    (let [sentinel (Object.)
          output (deref (future (await result) (:output @result))
                        agent-ref-timeout-ms
                        sentinel)]
      (if (identical? output sentinel)
        (fallback-to-task result)
        (or output {:error-message "Agent returned nil output"})))
    result))

;; ============================================================================
;; Deferred-Tasking Tool Dispatch
;; ============================================================================

(def ^:private !get-parent-agent
  "requiring-resolve delay for runtime/get-parent-agent — avoids a load cycle
   (runtime sits above core.tool). Non-nil result ⇒ the agent is a sub-agent."
  (delay (requiring-resolve 'ai.brainyard.agent.core.runtime/get-parent-agent)))

(defn parent-agent
  "The agent that spawned `agent` (its `:runtime :parent-agent`), or nil.
   nil-safe."
  [agent]
  (when agent (@!get-parent-agent (:!state agent))))

(defn subagent?
  "True when `agent` is a sub-agent. Detach is a top-level feature — the result
   is harvested in a later iteration and task$wakeup (to park) is top-level-only
   — so a sub-agent (which closes after it answers) must run its tool calls and
   code blocks synchronously/inline rather than detach and orphan the task.
   Shared by the tool path (call-tool-with-fast-eval) and the code-eval path
   (coact_agent block runners)."
  [agent]
  (some? (parent-agent agent)))

(defn detached-wait-options
  "LLM-facing 'how to wait' menu appended to every detach marker — both the
   code-eval markers (coact_agent) and the tool marker below. Presents the
   strategic choice between yielding the turn (task$wakeup) and holding it
   (task$wait); the LLM picks one. Single source of truth so all detach
   surfaces stay consistent."
  [tid]
  (str " Choose how to wait — pick ONE:"
       " (A) task$wakeup :task-id \"" tid "\" — YIELD: acknowledge in one line"
       " and END the turn; you are AUTO-RESUMED next turn when the task"
       " finishes (frees the session — best for a long wait)."
       " (B) task$wait :task-id \"" tid "\" :timeout-ms 60000 — HOLD: block and"
       " continue THIS turn when it finishes (best for a short wait; on"
       " timeout, wait again or switch to task$wakeup)."
       " Peek progress with task$detail (add :last-n N); task$cancel to abort."))

(defn- adopt-tool-into-task
  "Adopt a timed-out tool future into the task manager, await remaining
   auto-bg window, return result or pending marker. Extracted to avoid
   formatter mangling the try/let nesting in call-tool-with-fast-eval."
  [pre fut tool-name tool-id tool-args from-iteration t0 auto-bg-ms fast-eval-ms !task-ref]
  (try
    (let [adopt-fn    @(requiring-resolve 'ai.brainyard.agent.task.manager/adopt-detached!)
          get-mgr     @(requiring-resolve 'ai.brainyard.agent.task.manager/get-default-manager)
          tasks-atom  @(requiring-resolve 'ai.brainyard.agent.task.manager/!tasks)
          await-fn    @(requiring-resolve 'ai.brainyard.agent.task.commands/await-task)
          get-task-fn @(requiring-resolve 'ai.brainyard.agent.task.protocol/get-task)
          poll-fn     @(requiring-resolve 'ai.brainyard.agent.task.executor/make-future-poll-fn)
          tname       (str "tool: " (subs (str/replace (str tool-name) #"\n" " ")
                                          0 (min 60 (count (str tool-name)))))
          meta        (cond-> {:coact/lang (str tool-name)
                               :coact/code (str tool-name " " (pr-str tool-args))
                               :coact/tool-id tool-name
                               :coact/tool-args tool-args}
                        from-iteration (assoc :coact/pending-from-iter from-iteration))
          task        (adopt-fn tname :tool
                                {:tool-id tool-id :tool-args tool-args}
                                {:metadata meta :started-at t0}
                                (poll-fn fut (java.io.StringWriter.) (str tool-name) identity)
                                (fn [] (future-cancel fut)))
          _           (when !task-ref (proto/update-task-id! !task-ref (:id task)))
          mgr         (get-mgr)
          remaining   (max 0 (- auto-bg-ms fast-eval-ms))
          r           (await-fn mgr (:id task) remaining :on-timeout :detach)
          status      (:status r)]
      (if (not= "pending" status)
        (let [t      (get-task-fn mgr (:id task))
              result (:result t)]
          (try (swap! tasks-atom update (:id task)
                      (fn [ex] (when ex (assoc-in ex [:metadata :harvested?] true))))
               (catch Exception _))
          (tool-post-hook pre result)
          result)
        (let [tid    (:task-id r)
              marker (str "[" tool-name " STILL RUNNING — task-id=" tid
                          ". DO NOT re-call " tool-name " — it is already running."
                          (detached-wait-options tid) "]")]
          (tool-post-hook pre marker)
          marker)))
    (catch Throwable t
      (println "TOOL-ADOPT FAILED:" (.getMessage t))
      (.printStackTrace t)
      (let [msg (str "tool adopt failed: " (.getMessage t))]
        (tool-post-hook pre {:error-message msg})
        {:error-message msg}))))

(defn call-tool-with-fast-eval
  "Fast-path tool call: try inline with fast-eval timeout, adopt to task on
   timeout. Uses call-tool (with hooks) in the future — hooks are safe in
   futures from the BT thread.

   Agent-type tools (subagents) go through the fast-eval path like any other
   tool, so a long-running subagent is detached to a background task on
   timeout rather than blocking the loop. Detach is a TOP-LEVEL feature: the
   harvest folds the result back in a later iteration and `task$wakeup` (to
   park) is top-level-only. So calls run synchronously when made by a
   sub-agent (it would close before harvesting a detached task, orphaning it)
   or already inside a task context — alongside tools with `:meta :sync` and
   calls without a `:fast-eval-ms`. Detach happens only at the top level,
   where the parent already wraps the whole sub-agent call in its own
   fast-eval/detach via the agent-type tool path.

   opts keys:
     :agent :tools :tools-fn-map — passed through to call-tool
     :fast-eval-ms — inline deref deadline (nil = sync, no fast path)
     :auto-bg-ms   — total deadline before background detach
     :from-iteration — current iteration (for harvest metadata)"
  [tool-name tool-args {:keys [agent tools tools-fn-map
                               fast-eval-ms auto-bg-ms from-iteration]}]
  (let [tool-id    (keyword tool-name)
        tool-def   (get-tool-defs :id tool-id)
        no-fast?   (not fast-eval-ms)
        in-task?   (proto/in-task-context?)
        parent     (parent-agent agent)
        subagent?  (some? parent)
        sync-meta? (boolean (get-in tool-def [:meta :sync]))
        sync?      (or no-fast? in-task? subagent? sync-meta?)]
    ;; Dormant trace point — surfaces WHY each tool call routes sync vs the
    ;; fast-eval/detach path. Drained only when a mulog publisher is attached;
    ;; queryable via log$events (filter ::tool-dispatch).
    (mulog/debug ::tool-dispatch
                 :tool tool-name
                 :branch (if sync? :sync :fast)
                 :subagent? subagent?
                 :parent (when parent (proto/agent-id parent))
                 :in-task? in-task?
                 :no-fast-eval? no-fast?
                 :sync-meta? sync-meta?
                 :fast-eval-ms fast-eval-ms)
    (if sync?
      (call-tool tool-id tool-args
                 :agent agent :tools tools :tools-fn-map tools-fn-map)
      (let [t0        (System/currentTimeMillis)
            !task-ref (atom :inline-tool-eval)
            fut (future
                  (binding [proto/*current-task* !task-ref]
                    (try (call-tool tool-id tool-args
                                    :agent agent :tools tools
                                    :tools-fn-map tools-fn-map)
                         (catch Exception e
                           {:error-message (ex-message e)}))))
            r   (deref fut fast-eval-ms ::timeout)]
        (if (not= r ::timeout)
          r
          (adopt-tool-into-task nil fut tool-name tool-id tool-args
                                from-iteration t0 auto-bg-ms fast-eval-ms
                                !task-ref))))))
