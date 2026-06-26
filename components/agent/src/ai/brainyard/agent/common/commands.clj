;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.commands
  "Common commands for agent system.

   Provides:
   - Registry:  agent-registry$instances
   - Runtime:   agent-runtime$config (read or set)
   - Memory:    memory$remember, memory$recall, memory$status, memory$explain
   - Query:     query$llm (accepts :prompt or :prompts)
   - all-common-commands vector

   NOTE: query$clone (clone-self recursion) is defined here but is NOT part of
   all-common-commands — it is gated to rlm-* via :tool-use-control and bound
   only by rlm-agent's curated roster."
  (:require [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.agent.core.memory :as agent-mem]
            [ai.brainyard.agent.core.runtime :as runtime]
            [ai.brainyard.agent.task.commands :as task-cmds]
            [ai.brainyard.agent.common.analytics-commands :as analytics-cmds]
            [ai.brainyard.agent.common.artifacts :as artifacts]
            [ai.brainyard.agent.common.gateway :as gateway]
            [ai.brainyard.agent.common.gateway.telegram :as gateway-telegram]
            [ai.brainyard.agent.common.log :as log]
            [ai.brainyard.agent.common.schedule :as schedule]
            [ai.brainyard.agent.common.trajectory-export :as traj-export]
            [ai.brainyard.clj-llm.interface :as clj-llm]
            [ai.brainyard.agent.core.usage :as usage]
            ;; bare — registers the built-in usage guides into agent.core.usage
            [ai.brainyard.agent.common.usage-guides]
            [ai.brainyard.memory.interface :as mem]
            [ai.brainyard.memory.interface.protocol :as mproto]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; ============================================================================
;; Registry Commands
;; ============================================================================

(defcommand agent-registry$instances
  "List registered agent instances (root + sub-agents)."
  (fn [& _]
    (let [agents (agent-core/list-agents)
          first-session (some-> agents first :!session deref)
          entries (->> agents
                       (mapv (fn [a]
                               (let [st      @(:!state a)
                                     st-init (some-> (proto/get-st-memory-init a) deref)
                                     st-mem  (some-> (proto/get-bt-st-memory a) deref)
                                     parent  (runtime/get-parent-agent (:!state a))]
                                 {:agent-id  (proto/agent-id a)
                                  :turn      (or (:turn-id st-init) 0)
                                  :iter      (or (:iteration-count st-mem) 0)
                                  :parent-id (some-> parent proto/agent-id)
                                  :status    (:status st)}))))]
      {:agents      entries
       :total       (count entries)
       :total-turns (or (:total-turns first-session) 0)}))
  :input-schema  [:map]
  :output-schema [:map
                  [:agents [:string {:desc "Vector of {:agent-id :turn :iter :parent-id :status}"}]]
                  [:total [:int {:desc "Total agent instances registered"}]]
                  [:total-turns [:int {:desc "Cumulative ask count across the session"}]]])

;; The agent-knowledge$* commands (update / remove / list) were removed
;; in the L1 simplification refactor. System context is now operator-
;; only — operators write directly via `mem/write-entry` at L1 with
;; `:kind :system-context`, and the LLM cannot self-edit its prompt
;; mid-conversation.

;; ============================================================================
;; Runtime Commands
;; ============================================================================

(defcommand agent-runtime$config
  "Read merged config (no args) or set one entry (pair :key + :value). Valid keys: see agent.core.config/config-schema.
   Setting writes both the per-agent override (effective immediately) and the persisted global config in .brainyard/config.edn."
  (fn [& {:as args}]
    (if-let [agent proto/*current-agent*]
      (let [k-raw    (:key args)
            v-str    (:value args)
            has-key? (not (str/blank? (str k-raw)))
            has-val? (not (str/blank? (str v-str)))]
        (cond
          (and (not has-key?) (not has-val?))
          {:config (config/get-config-snapshot agent)}

          (not= has-key? has-val?)
          {:error-message "Both 'key' and 'value' are required to set config; omit both to read"}

          :else
          (let [k (keyword k-raw)]
            (if-not (contains? config/config-keys k)
              {:error-message (format "Invalid config key '%s'. Valid: %s"
                                      (name k) (str/join ", " (map name config/config-keys)))}
              (let [coerced (config/coerce-config-value k v-str)]
                (config/set-config! agent k coerced)
                {:result (format "Config '%s' set to %s. Effective immediately; persisted to .brainyard/config.edn."
                                 (name k) coerced)
                 :config (config/get-config-snapshot agent)})))))
      {:error-message "current agent is not running"}))
  :input-schema  [:map
                  [:key {:optional true} [:string {:desc "Config key to set (omit both :key and :value to read; see agent.core.config/config-schema)"}]]
                  [:value {:optional true} [:string {:desc "Config value to set, e.g. 'true', 'false', '3' (required with :key)"}]]]
  :output-schema [:map
                  [:result {:optional true} [:string {:desc "Confirmation when a value was set"}]]
                  [:config {:optional true} [:string {:desc "Merged config snapshot (per-agent overrides over global) — used for both read and set responses"}]]
                  [:error-message {:optional true} [:string {:desc "Error if invalid key, partial args, or agent not running"}]]])

;; ============================================================================
;; Memory Commands
;; ============================================================================

(def ^:private layer-kw
  {"l1" :l1 "l2" :l2 "l3" :l3
   "L1" :l1 "L2" :l2 "L3" :l3})

(def ^:private match-kw
  {"or" :or "and" :and "phrase" :phrase})

(def ^:private layer->valid-kinds
  "Canonical kinds per memory layer (see memory protocol)."
  {:l1 mproto/entry-kinds
   :l2 mproto/episode-types
   :l3 mproto/fact-types})

(defn- invalid-kind-error
  "When `kind-kw` is a non-nil kind that isn't valid for layer `lyr`, return
   an actionable {:error ...} enumerating the valid kinds — so the LLM (which
   tends to invent plausible-but-wrong kinds) retries with a good one instead
   of looping on an opaque write failure or a silently-empty recall. Returns
   nil when the kind is nil or valid. `kind-str` is the raw user input, echoed
   verbatim in the message."
  [lyr kind-kw kind-str]
  (when-let [valid (layer->valid-kinds lyr)]
    (when (and kind-kw (not (contains? valid kind-kw)))
      {:error (format "Invalid kind \"%s\" for layer %s. Valid kinds: %s."
                      kind-str (name lyr)
                      (str/join ", " (sort (map name valid))))})))

(defn- current-mm []
  (some-> proto/*current-agent* :!state deref :memory-manager))

(defn- current-session-id []
  (some-> proto/*current-agent* proto/session-id))

(defcommand memory$remember
  "Store one memory entry. Pick :layer by lifetime — l3=durable cross-session, l2=session timeline, l1=session-only pin."
  (fn [& {:keys [layer kind content tags role confidence field section]}]
    (if-let [mm (current-mm)]
      (let [lyr      (or (layer-kw layer) :l3)
            kind-kw  (when-not (str/blank? kind) (keyword kind))
            field-kw (when-not (str/blank? field) (keyword field))
            kind-err (invalid-kind-error lyr kind-kw kind)]
        (cond
          (str/blank? content)
          {:error "content is required"}

          ;; Reject an explicit-but-unknown kind up front with the valid
          ;; set, so the LLM retries with a good kind instead of looping
          ;; on the opaque "returned no entry" error.  (Omitted kind falls
          ;; through to the per-layer default below.)
          kind-err kind-err

          :else
          (let [sid (current-session-id)
                entry (cond-> {:content content}
                        kind-kw         (assoc :kind kind-kw)
                        (seq tags)      (assoc :tags (set tags))
                        (and role
                             (not (str/blank? role)))
                        (assoc :role role)
                        (and (= lyr :l3)
                             (some? confidence))
                        (assoc :confidence confidence)
                        (contains? #{:l1 :l2} lyr)
                        (assoc :session-id sid))
                entry (cond-> entry
                        (and (= lyr :l1) (nil? (:kind entry)))
                        (assoc :kind :user-context)
                        (and (= lyr :l2) (nil? (:kind entry)))
                        (assoc :kind :observation)
                        (and (= lyr :l3) (nil? (:kind entry)))
                        (assoc :kind :fact))
                ;; L1 canonical addressing: :field/:section land in :data
                ;; (where read-entries matches and assemble-field groups on
                ;; them), and when BOTH are present derive the stable
                ;; {kind}/{field}/{section} id so a repeat write upserts the
                ;; same overlay instead of piling up random-uuid entries.
                entry (cond-> entry
                        (and (= lyr :l1) field-kw)
                        (assoc-in [:data :field] field-kw)
                        (and (= lyr :l1) (not (str/blank? section)))
                        (assoc-in [:data :section] section)
                        (and (= lyr :l1) field-kw (not (str/blank? section)))
                        (assoc :id (mem/l1-entry-id (:kind entry) field-kw section)))
                result (agent-mem/remember mm lyr [entry])
                persisted (-> result lyr first)]
            (if persisted
              {:result (format "Stored in %s (kind: %s, id: %s)"
                               (name lyr) (name (:kind entry)) (:id persisted))
               :entry-id (:id persisted)
               :layer (name lyr)}
              {:error (format "Write to %s returned no entry" (name lyr))}))))
      {:error "current agent has no memory manager"}))
  :input-schema  [:map
                  [:layer {:optional true} [:string {:desc "memory layer: l1 | l2 | l3 (default l3)" :default "l3"}]]
                  [:kind {:optional true} [:string {:desc "entry kind, must match the layer — l1: system-context|user-context|episode|fact|observation (default user-context); l2: conversation|action|observation|thought|evaluation|error (default observation); l3: summary|fact|preference|entity|concept|relationship (default fact)"}]]
                  [:content [:string {:desc "entry content (required)"}]]
                  [:tags {:optional true} [:vector {:desc "tags for the entry"} :string]]
                  [:role {:optional true} [:string {:desc "L2 only: message role (user/assistant/system/tool)"}]]
                  [:confidence {:optional true} [:double {:desc "L3 only: confidence 0.0..1.0 (default 1.0)"}]]
                  [:field {:optional true} [:string {:desc "L1 only: overlay field grouping entries into one assembled prompt fragment. system-context fields: instruction | agent-context | tool-context. user-context: arbitrary grouping key (e.g. preferences, notes)."}]]
                  [:section {:optional true} [:string {:desc "L1 only: section name within :field. Entries sort by it and render as '### <section>'. With :field, forms the stable id {kind}/{field}/{section} so a repeat write upserts rather than duplicates."}]]]
  :output-schema [:map
                  [:result {:optional true} [:string {:desc "Confirmation message with layer and entry id"}]]
                  [:entry-id {:optional true} [:string {:desc "Persisted entry id"}]]
                  [:layer {:optional true} [:string {:desc "Layer the entry was written to"}]]
                  [:error {:optional true} [:string {:desc "Error if write failed or no memory manager"}]]])

(defcommand memory$recall
  "Search agent memory. Omit :layer for cross-layer RRF; set :layer to query just l1/l2/l3."
  (fn [& {:keys [query layer limit match kind min-confidence]}]
    (if-let [mm (current-mm)]
      (let [lyr (layer-kw layer)
            lim (or limit 10)
            mtch (or (match-kw match) :or)
            sid (current-session-id)
            kind-kw (when-not (str/blank? kind) (keyword kind))
            kind-err (invalid-kind-error lyr kind-kw kind)]
        (cond
          ;; A kind filter only applies to a specific layer; cross-layer
          ;; recall ignores :kind. Surface that instead of silently
          ;; returning unfiltered results the LLM thinks were filtered.
          (and kind-kw (nil? lyr))
          {:error "kind filtering requires a specific :layer (l1/l2/l3); cross-layer recall ignores :kind."}

          ;; An unknown kind for the chosen layer would silently match
          ;; nothing (looks like 'memory is empty'); error with the valid set.
          kind-err kind-err

          lyr
          (let [layer-opts
                (cond-> {:limit lim}
                  (= lyr :l1)        (assoc :session-id sid)
                  (and (= lyr :l2)
                       (not (str/blank? query)))
                  (assoc :text query :match mtch)
                  (= lyr :l2)        (assoc :session-id sid)
                  (and (= lyr :l3)
                       (not (str/blank? query)))
                  (assoc :text query :match mtch)
                  kind-kw            (assoc :kind kind-kw)
                  (and (= lyr :l3) (some? min-confidence))
                  (assoc :min-confidence min-confidence))
                results (agent-mem/recall mm lyr layer-opts)
                entries (get results lyr [])]
            {:layer (name lyr)
             :count (count entries)
             :entries (mapv #(select-keys % [:id :kind :content :role :tags
                                             :confidence :session-id :created-at])
                            entries)})

          :else
          (let [combined (agent-mem/recall mm
                                           :query (or query "")
                                           :session-id sid
                                           :limit lim
                                           :match mtch)]
            {:layer "combined"
             :count (count combined)
             :entries (mapv #(select-keys % [:id :_layer :kind :content :role
                                             :tags :_rrf_score :session-id :created-at])
                            combined)})))
      {:error "current agent has no memory manager"}))
  :input-schema  [:map
                  [:query {:optional true} [:string {:desc "search text (used for L2/L3 FTS or cross-layer recall)"}]]
                  [:layer {:optional true} [:string {:desc "specific layer: l1 | l2 | l3 (omit for cross-layer)"}]]
                  [:limit {:optional true} [:int {:desc "max results (default 10)" :default 10}]]
                  [:match {:optional true} [:string {:desc "FTS multi-word mode: or | and | phrase (default or)" :default "or"}]]
                  [:kind {:optional true} [:string {:desc "kind filter, requires :layer (cross-layer recall ignores it) — l1: system-context|user-context|episode|fact|observation; l2: conversation|action|observation|thought|evaluation|error; l3: summary|fact|preference|entity|concept|relationship"}]]
                  [:min-confidence {:optional true} [:double {:desc "L3 only: minimum confidence"}]]]
  :output-schema [:map
                  [:layer {:optional true} [:string {:desc "Layer queried, or 'combined' for cross-layer recall"}]]
                  [:count {:optional true} [:int {:desc "Number of entries returned"}]]
                  [:entries {:optional true} [:vector {:desc "Recalled entries (selected fields)"} :any]]
                  [:error {:optional true} [:string {:desc "Error if no memory manager"}]]])

(defn- safe-explain-session
  "Call mem/explain-session, returning {:turns []} on any failure."
  [mm sid]
  (if (str/blank? (str sid))
    {:turns []}
    (try (mem/explain-session mm sid)
         (catch Exception _ {:turns []}))))

(defcommand memory$status
  "Report memory usage: per-layer counts, schema version, capture-pipeline state, and audit summary."
  (fn [& _]
    (if-let [_agent proto/*current-agent*]
      (if-let [mm (current-mm)]
        (let [sid     (current-session-id)
              stats   (try (mem/get-stats mm) (catch Exception _ {}))
              l1-cnt  (try (count (mem/read-entries mm :l1
                                                    {:session-id sid}
                                                    {:limit 10000}))
                           (catch Exception _ 0))
              l2-sess (try (count (mem/read-entries mm :l2
                                                    {:session-id sid}
                                                    {:limit 10000}))
                           (catch Exception _ 0))
              audit   (safe-explain-session mm sid)
              turns   (or (:turns audit) [])
              vstat   (try (mem/graph-vec-status mm) (catch Exception _ nil))]
          {:user-id          (str (:user-id mm))
           :session-id       (str sid)
           :schema-version   (str (:schema-version stats))
           :capture-running? (boolean (mem/capture-running? mm))
           :l1               {:session-entries l1-cnt}
           :l2               {:total   (or (:episodes stats) 0)
                              :session l2-sess}
           :l3               {:total (or (:semantic-facts stats) 0)}
           :audit            {:turns              (count turns)
                              :total-prompt-bytes (reduce + 0 (keep :prompt-bytes turns))}
           ;; CR-MEM-21: vector index health. :stale? true ⇒ embed model
           ;; changed, semantic recall paused until `memory$reembed`.
           :vec-index        (when vstat
                               (cond-> {:model      (:now vstat)
                                        :built-with (:was vstat)
                                        :indexed    (:count vstat)
                                        :stale?     (boolean (:stale? vstat))}
                                 (:stale? vstat) (assoc :notice (mem/graph-vec-stale-notice mm))))})
        {:error "current agent has no memory manager"})
      {:error "current agent is not running"}))
  :input-schema  [:map]
  :output-schema [:map
                  [:user-id {:optional true} [:string {:desc "User id"}]]
                  [:session-id {:optional true} [:string {:desc "Current session id"}]]
                  [:schema-version {:optional true} [:string {:desc "Memory DB schema version"}]]
                  [:capture-running? {:optional true} [:string {:desc "True when memory capture pipeline is active"}]]
                  [:l1 {:optional true} [:string {:desc "{:session-entries N} — pinned L1 entries this session"}]]
                  [:l2 {:optional true} [:string {:desc "{:total N :session N} — episodic counts (user-wide and this session)"}]]
                  [:l3 {:optional true} [:string {:desc "{:total N} — L3 semantic facts count"}]]
                  [:audit {:optional true} [:string {:desc "{:turns N :total-prompt-bytes N} — memory_audit summary for this session"}]]
                  [:vec-index {:optional true} [:string {:desc "{:model :built-with :indexed :stale? :notice} — semantic vector index health (CR-MEM-21); :stale? true means the embed model changed and recall is paused until memory$reembed"}]]
                  [:error {:optional true} [:string {:desc "Error if no agent or no memory manager"}]]])

(defcommand memory$reembed
  "Rebuild the semantic vector index for the current embedding model (CR-MEM-21).
   Run this after changing :graph-embed-model — re-embeds all L3 facts and node
   summaries and resumes semantic recall. Re-embeds N rows, so it may take a
   while on large stores."
  (fn [& _]
    (if-let [mm (current-mm)]
      (try
        (let [before (mem/graph-vec-status mm)
              r      (mem/reembed-graph-vec! mm)]
          (if r
            {:rebuilt true
             :facts (:facts r) :nodes (:nodes r)
             :model (:now before)
             :message (str "Rebuilt vector index for " (:now before)
                           " — re-embedded " (:facts r) " facts, " (:nodes r)
                           " node summaries. Semantic recall resumed.")}
            {:rebuilt false
             :message "No embedding model configured — nothing to rebuild (recall uses keyword search)."}))
        (catch Exception e
          (mulog/warn ::memory$reembed-failed :exception e)
          {:error (ex-message e)}))
      {:error "current agent has no memory manager"}))
  :input-schema  [:map]
  :output-schema [:map
                  [:rebuilt {:optional true} [:boolean {:desc "True when the index was rebuilt"}]]
                  [:facts   {:optional true} [:int     {:desc "L3 facts re-embedded"}]]
                  [:nodes   {:optional true} [:int     {:desc "Graph node summaries re-embedded"}]]
                  [:model   {:optional true} [:string  {:desc "Embedding model the index was rebuilt for"}]]
                  [:message {:optional true} [:string  {:desc "Human-readable result"}]]
                  [:error   {:optional true} [:string  {:desc "Error message"}]]])

(defn- summarize-audit-entry
  "Project an {:audit :entry} pair into a compact map for tool output."
  [{:keys [audit entry]}]
  (cond-> {:layer    (:layer audit)
           :entry-id (:entry_id audit)
           :bytes    (:byte_cost audit)}
    entry (assoc :kind    (some-> (:kind entry) name)
                 :role    (:role entry)
                 :tags    (some-> (:tags entry) seq vec)
                 :content (:content entry))))

(defn- summarize-audit-turn
  [limit {:keys [agent-id turn-id total-turns entries prompt-bytes]}]
  {:agent-id     (some-> agent-id str)
   :turn-id      turn-id
   :total-turns  total-turns
   :prompt-bytes prompt-bytes
   :count        (count entries)
   :entries      (mapv summarize-audit-entry (take limit entries))})

(defn- parse-turn-id [v]
  (cond
    (number? v) (long v)
    (and (string? v) (not (str/blank? v)))
    (try (Long/parseLong (str/trim v)) (catch Exception _ nil))
    :else nil))

(defn- current-agent-id-str
  []
  (some-> proto/*current-agent* proto/agent-id str))

(defcommand memory$explain
  "Audit-trail view: which memory entries informed past prompts. Defaults to latest turn for current agent."
  (fn [& {:keys [turn-id agent-id session-id all-turns limit]}]
    (if-let [_agent proto/*current-agent*]
      (if-let [mm (current-mm)]
        (let [sid (or (when-not (str/blank? (str session-id)) session-id)
                      (current-session-id))
              aid (or (when-not (str/blank? (str agent-id)) (str agent-id))
                      (current-agent-id-str))
              lim (or limit 20)]
          (cond
            (str/blank? (str sid))
            {:error "no session-id available (provide :session-id or run inside a session)"}

            all-turns
            (let [{:keys [turns]} (safe-explain-session mm sid)
                  turns'          (mapv (partial summarize-audit-turn lim)
                                        (or turns []))]
              {:session-id sid
               :count      (count turns')
               :turns      turns'})

            :else
            (let [tid (or (parse-turn-id turn-id)
                          ;; Default: latest per-agent turn for the
                          ;; current agent. Falls back to any-agent's
                          ;; latest turn if aid filter has no rows.
                          (let [{:keys [turns]} (safe-explain-session mm sid)
                                ag-turns        (if aid
                                                  (filter #(= aid (str (:agent-id %))) turns)
                                                  turns)
                                tids            (keep :turn-id ag-turns)]
                            (when (seq tids) (apply max tids))))]
              (if (nil? tid)
                {:error (format "no audit rows recorded for session '%s'%s"
                                sid (if aid (format " (agent '%s')" aid) ""))}
                (let [{:keys [user-id prompt-bytes entries]}
                      (try (mem/explain mm sid aid tid)
                           (catch Exception e
                             {:error (ex-message e)}))]
                  {:session-id   sid
                   :agent-id     aid
                   :turn-id      tid
                   :user-id      (str user-id)
                   :prompt-bytes (or prompt-bytes 0)
                   :count        (count entries)
                   :entries      (mapv summarize-audit-entry (take lim entries))})))))
        {:error "current agent has no memory manager"})
      {:error "current agent is not running"}))
  :input-schema  [:map
                  [:turn-id {:optional true} [:int {:desc "Specific per-agent turn-id (default: latest for current agent)"}]]
                  [:agent-id {:optional true} [:string {:desc "Agent id (default: current agent)"}]]
                  [:session-id {:optional true} [:string {:desc "Session id (default: current session)"}]]
                  [:all-turns {:optional true} [:boolean {:desc "If true, return per-(agent,turn) breakdown for the whole session"}]]
                  [:limit {:optional true} [:int {:desc "Max entries per turn (default 20)" :default 20}]]]
  :output-schema [:map
                  [:session-id {:optional true} [:string {:desc "Session id queried"}]]
                  [:agent-id {:optional true} [:string {:desc "Agent id (single-turn mode)"}]]
                  [:turn-id {:optional true} [:int {:desc "Per-agent turn-id (single-turn mode)"}]]
                  [:user-id {:optional true} [:string {:desc "User id"}]]
                  [:prompt-bytes {:optional true} [:int {:desc "Total bytes from entries on this turn"}]]
                  [:count {:optional true} [:int {:desc "Entries on this turn (or turn count when :all-turns)"}]]
                  [:entries {:optional true} [:vector {:desc "Audit entries: {:layer :kind :content :role :tags :entry-id :bytes}"} :any]]
                  [:turns {:optional true} [:vector {:desc "Per-(agent,turn) summaries (when :all-turns)"} :any]]
                  [:error {:optional true} [:string {:desc "Error if no agent / no memory manager / no audit rows"}]]])

;; ============================================================================
;; Query Commands
;; ============================================================================
;;
;; Sub-LLM and cloned-agent dispatch, exposed in the sandbox via the standard
;; auto-tool-binding path. lm-config / usage-tracker are resolved lazily from
;; the current agent each call, so config changes take effect immediately.

(defn- resolve-usage-tracker []
  (when-let [agent proto/*current-agent*]
    (get-in @(:!session agent) [:config :usage-tracker])))

(defcommand query$llm
  "Query a sub-LLM (no tools, no iteration). Pass :prompt (single) or :prompts (concurrent batch)."
  (fn [& {:keys [prompt prompts sub-context]}]
    (let [has-prompt?  (not (str/blank? (str prompt)))
          has-prompts? (and (sequential? prompts) (seq prompts))]
      (cond
        (and has-prompt? has-prompts?)
        {:error "supply :prompt OR :prompts, not both"}

        has-prompts?
        (try
          (let [f (clj-llm/create-llm-query-batched-fn (config/resolve-sub-lm) (resolve-usage-tracker))]
            {:results (if sub-context (f (vec prompts) sub-context) (f (vec prompts)))})
          (catch Exception e
            {:error (str "query$llm error (batched): " (.getMessage e))}))

        has-prompt?
        (try
          (let [f (clj-llm/create-llm-query-fn (config/resolve-sub-lm) (resolve-usage-tracker))]
            {:result (if sub-context (f prompt sub-context) (f prompt))})
          (catch Exception e
            {:error (str "query$llm error: " (.getMessage e))}))

        :else
        {:error "either :prompt (string) or :prompts (vector of strings) is required"})))
  :input-schema  [:map
                  [:prompt {:optional true} [:string {:desc "Single prompt to send to the sub-LLM (omit when using :prompts)"}]]
                  [:prompts {:optional true} [:vector {:desc "Multiple prompts to dispatch concurrently (max 20). Omit when using :prompt."} :string]]
                  [:sub-context {:optional true} [:string {:desc "Optional supplementary context (max ~500K chars). Shared across all prompts in batched mode."}]]]
  :output-schema [:map
                  [:result {:optional true} [:string {:desc "Sub-LLM response (single-prompt mode)"}]]
                  [:results {:optional true} [:vector {:desc "Vector of sub-LLM responses in input order (batched mode)"} :string]]
                  [:error {:optional true} [:string {:desc "Error if the call failed"}]]])

(defcommand query$clone
  "Query a cloned copy of the current agent (same tools, isolated state, auto-closed). rlm-only: clone-self recursion gated to rlm-* via :tool-use-control."
  (fn [& {:keys [query agent-context instruction tool-context]}]
    (if (str/blank? (str query))
      {:error "query is required"}
      (if-let [agent proto/*current-agent*]
        (let [overrides (cond-> {}
                          (and agent-context (not (str/blank? agent-context)))
                          (assoc :agent-context agent-context)
                          (and instruction (not (str/blank? instruction)))
                          (assoc :instruction instruction)
                          (and tool-context (not (str/blank? tool-context)))
                          (assoc :tool-context tool-context))
              clone (proto/clone-agent agent
                                       (cond-> {:parent-agent agent}
                                         (seq overrides)
                                         (assoc :st-memory-init-overrides overrides)))]
          (try
            (let [result (agent-core/ask clone (str query))]
              {:result (or (:answer result)
                           (:error result)
                           "query$clone: clone completed without an answer")})
            (catch Exception e
              {:error (str "query$clone error: " (.getMessage e))})
            (finally
              (.close ^java.io.Closeable clone))))
        {:error "current agent is not running"})))
  :input-schema  [:map
                  [:query [:string {:desc "Question/task for the cloned agent"}]]
                  [:agent-context {:optional true} [:string {:desc "Override clone's :agent-context (world knowledge)"}]]
                  [:instruction {:optional true} [:string {:desc "Override clone's :instruction (system prompt)"}]]
                  [:tool-context {:optional true} [:string {:desc "Override clone's :tool-context (tool emphasis)"}]]]
  :output-schema [:map
                  [:result {:optional true} [:string {:desc "Cloned agent's answer"}]]
                  [:error {:optional true} [:string {:desc "Error if no current agent or the clone failed"}]]]
  ;; Gated to rlm-* only — clone-self recursion is the depth-2 RLM path and
  ;; lives exclusively with rlm-agent. tool-visible? hides it from every other
  ;; agent's roster even though it stays registered in !tool-defs.
  :tool-use-control {:allow ["rlm-*"]})

;; ============================================================================
;; LLM Metadata
;; ============================================================================

(defcommand llm$models
  "List known LLM models from the catalog (single source of truth across
   claude-code, openai, anthropic, google, deepseek, mistral, groq, ollama,
   free-llm, apple-fm, bedrock). Pure data — no network calls, no API keys.

   Defaults to the curated set, ordered by rank — ideal for `/model` pickers,
   config-agent, and bootstrap dialogs. Options:
     :provider <kw|str>  restrict to one provider (e.g. :anthropic, \"openai\")
     :all      <bool>    return the full catalog, not just curated entries
     :limit    <int>     cap the returned entries (applied after filtering)

   Each entry: {:model :provider :curated? (:curated-rank :description :region
   when present)}."
  (fn [& {:keys [provider all limit]}]
    (let [provider* (cond
                      (keyword? provider) provider
                      (and (string? provider) (not (str/blank? provider)))
                      (keyword (str/replace provider #"^:" ""))
                      :else nil)
          entries   (clj-llm/list-models :provider provider* :curated? (not all))
          capped    (if (and (integer? limit) (pos? limit))
                      (vec (take limit entries))
                      (vec entries))]
      {:models    capped
       :count     (count capped)
       :total     (count entries)
       :curated   (not (boolean all))
       :providers (vec (sort (keys (clj-llm/get-models-by-provider))))}))
  :input-schema  [:map
                  [:provider {:optional true} [:string {:desc "Optional provider filter (keyword or string), e.g. :anthropic, \"openai\""}]]
                  [:all {:optional true} [:boolean {:desc "Include the full catalog, not just curated entries (default false)"}]]
                  [:limit {:optional true} [:int {:desc "Cap on returned entries, applied after filtering (default: no cap)"}]]]
  :output-schema [:map
                  [:models [:vector {:desc "Model entries — each {:model :provider :curated? (:curated-rank :description :region when present)}"} :map]]
                  [:count [:int {:desc "Entries returned after filter / limit"}]]
                  [:total [:int {:desc "Entries after filter, before limit"}]]
                  [:curated [:boolean {:desc "True when only curated entries were returned (i.e. :all was not set)"}]]
                  [:providers [:vector {:desc "All providers in the catalog"} :keyword]]])

(defcommand usage$guide
  "Return an on-demand usage guide for a topic. Call with no topic to list all
   available topics; call with one (e.g. `(usage$guide :topic :memory)`) to get
   that guide's text. A known topic returns the guide as a plain STRING (so it
   renders verbatim — real newlines preserved — in the iteration record rather
   than a pr-str'd map with escaped newlines); no topic returns `{:topics [...]}`
   and an unknown topic returns `{:error ... :topics [...]}`. The same registered
   tool backs both the tool-calls channel and the auto-generated sandbox binding
   `(usage$guide :topic <name>)`."
  (fn [& {:keys [topic]}]
    (let [k (cond
              (keyword? topic) topic
              (symbol? topic)  (keyword (name topic))
              (and (string? topic) (not (str/blank? topic)))
              (keyword (str/replace topic #"^:" ""))
              :else            nil)
          topics (usage/list-usage-topics)
          guide  (when k (usage/get-usage-guide k))]
      (cond
        (nil? k)     {:topics topics}
        (nil? guide) {:error  (str "unknown topic " (pr-str k)) :topics topics}
        :else        guide)))
  :input-schema  [:map
                  [:topic {:optional true}
                   [:keyword {:desc "Topic to fetch (e.g. :memory, :todo, :files). Omit to list all topics."}]]]
  :output-schema [:or
                  [:string {:desc "The guide text, when a known topic was requested (returned verbatim, not wrapped)."}]
                  [:map
                   [:topics {:optional true} [:vector {:desc "All available topics (returned when no topic given, or on an unknown topic)."} :keyword]]
                   [:error  {:optional true} [:string {:desc "Present when the topic is unknown."}]]]])

;; ============================================================================
;; Command Categories
;; ============================================================================

(def registry-commands
  "Commands for inspecting the agent registry (root + sub-agent instances)"
  [#'agent-registry$instances])

(def runtime-commands
  "Commands for reading and updating runtime configuration"
  [#'agent-runtime$config])

(def memory-commands
  "Commands for reading and writing agent memory"
  [#'memory$remember
   #'memory$recall
   #'memory$status
   #'memory$explain])

(def query-commands
  "Commands for sub-LLM dispatch. query$clone (clone-self recursion) is
   intentionally NOT here — it is rlm-only, gated via :tool-use-control and
   bound explicitly by rlm-agent's roster, so it must stay out of
   all-common-commands (which every other coact-derived agent inherits)."
  [#'query$llm])

(def llm-commands
  "Commands for inspecting LLM metadata (no network calls)."
  [#'llm$models])

(def all-common-commands
  "All common commands available for tool use"
  (vec (concat registry-commands
               runtime-commands
               memory-commands
               query-commands
               llm-commands
               artifacts/artifact-commands
               task-cmds/task-commands
               analytics-cmds/analytics-commands
               log/log-commands
               traj-export/export-commands
               schedule/schedule-commands
               gateway/gateway-commands
               gateway-telegram/telegram-commands)))
