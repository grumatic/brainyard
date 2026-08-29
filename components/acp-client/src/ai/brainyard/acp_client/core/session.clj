;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp-client.core.session
  "ACP session lifecycle on top of the dispatcher.

   A session is a logical conversation with an ACP agent inside an
   AcpClient. Phase 5 wires this up to the agent runtime; for now it's
   used directly by tests and (eventually) by a `:acp` clj-llm
   provider."
  (:require [clojure.string :as str]
            [ai.brainyard.acp-client.core.client :as client]
            [ai.brainyard.acp-client.core.events :as events]))

;; =============================================================================
;; AcpSession — a thin handle, not its own record (state lives in the
;; remote agent + the client's pending-requests + caller-provided
;; on-event closure).
;; =============================================================================

(defn new!
  "Create a new ACP session via `session/new`.

   Opts:
     :cwd          — workspace cwd advertised to the agent (string).
     :mcp-servers  — vector of MCP server configs (default []).
     :timeout-ms   — handshake timeout (default 30000).

   Returns: {:session-id str :client AcpClient :models map?
             :config-options vector?}

   Two model-selection mechanisms can appear here, and an agent serves
   at most one:

   - `:models` — the LEGACY shape
     `{:availableModels [{:modelId :name :description}] :currentModelId}`,
     driven by `set-model!` / `session/set_model`.
   - `:config-options` — the CURRENT spec shape, a vector of
     `{:id :name :category :type :currentValue :options [{:value :name
     :description}]}`, driven by `set-config-option!`. The model selector
     is the entry with `:category \"model\"`; see `model-config-option`.

   claude-agent-acp moved from the first to the second between 0.16.2 and
   0.70.0 — `session/set_model` now answers `-32601 Method not found` —
   so callers must handle both. Either key is nil when unadvertised."
  ([client] (new! client {}))
  ([acp-client {:keys [cwd mcp-servers timeout-ms]
                :or   {cwd         (System/getProperty "user.dir")
                       mcp-servers []
                       timeout-ms  30000}}]
   (let [result (client/await-result
                 acp-client
                 (client/request! acp-client "session/new"
                                  {:cwd        cwd
                                   :mcpServers mcp-servers}
                                  {:timeout-ms timeout-ms})
                 timeout-ms)]
     {:session-id     (:sessionId result)
      :client         acp-client
      :models         (:models result)
      :config-options (:configOptions result)})))

;; =============================================================================
;; set-model!
;; =============================================================================

(defn set-model!
  "Select the session's model via ACP `session/set_model` — the LEGACY
   mechanism. Prefer `set-config-option!` when the session advertises
   `:config-options`; see `new!`.

   `model-id` must be one of the `:modelId`s the agent advertised in the
   `session/new` response's `:models :availableModels` (e.g. the
   claude-code adapter exposes the aliases \"default\" / \"sonnet\" /
   \"haiku\"). The agent does not validate unknown ids — it silently
   no-ops — so callers should resolve `model-id` against the advertised
   list first. Returns the (usually empty) result map.

   Agents that have moved to session config options REMOVED this method
   rather than deprecating it: claude-agent-acp 0.70.0 answers
   `-32601 Method not found` for every id, valid ones included. Callers
   must therefore branch on what the session advertised, not call this
   speculatively and catch."
  ([sess model-id] (set-model! sess model-id {}))
  ([{:keys [session-id client] :as _sess} model-id {:keys [timeout-ms]
                                                    :or   {timeout-ms 30000}}]
   (client/await-result
    client
    (client/request! client "session/set_model"
                     {:sessionId session-id :modelId model-id}
                     {:timeout-ms timeout-ms})
    timeout-ms)))

;; =============================================================================
;; session/set_config_option — the CURRENT model-selection mechanism
;; =============================================================================

(defn set-config-option!
  "Set one session config option via ACP `session/set_config_option`.

   `config-id` is a `:id` from the session's `:config-options`
   (claude-agent-acp 0.70.0 serves \"mode\", \"model\" and \"agent\");
   `value` must be one of that option's `:options` `:value`s — resolve
   user input with `resolve-config-value` first.

   Returns the agent's reply, `{:configOptions [...]}` — the COMPLETE
   updated option set, not just the one changed, because setting one
   option may change others. Callers should keep the returned vector
   rather than mutating their copy."
  ([sess config-id value] (set-config-option! sess config-id value {}))
  ([{:keys [session-id client] :as _sess} config-id value
    {:keys [timeout-ms] :or {timeout-ms 30000}}]
   (client/await-result
    client
    (client/request! client "session/set_config_option"
                     {:sessionId session-id
                      :configId  config-id
                      :value     value}
                     {:timeout-ms timeout-ms})
    timeout-ms)))

(defn model-config-option
  "The model selector among a session's `:config-options`, or nil.

   Matches `:category \"model\"` first — the spec's tag for the PRIMARY
   selector. Note `model_config` is a DIFFERENT category, for related
   knobs like context size and speed/quality, so matching it here would
   pick a secondary control and set the wrong thing. The `:id`/`:name`
   fallback covers agents that ship the option without a category."
  [config-options]
  (or (first (filter #(= "model" (:category %)) config-options))
      (first (filter #(or (= "model" (str/lower-case (str (:id %))))
                          (= "model" (str/lower-case (str (:name %)))))
                     config-options))))

;; =============================================================================
;; Fuzzy resolution — shared by both mechanisms
;; =============================================================================

(defn- resolve-choice
  "Match `wanted` against `choices` (`[{:id :name :description}]`) and
   return the winning `:id`, or nil.

   Tiered deliberately, because the tiers disagree and the order is what
   makes them agree with intent. Against claude-agent-acp 0.70.0's list,
   \"opus\" appears in BOTH the id `opus[1m]` and the DESCRIPTION of
   `default` (\"Opus (1M context)\"). Ranking id-substring above
   description-substring picks the explicit Opus entry rather than the
   catch-all default, which is what someone asking for opus meant."
  [choices wanted]
  (when (and (seq choices) (some? wanted))
    (let [w   (str/lower-case (str wanted))
          ids (mapv #(str (:id %)) choices)
          hit (fn [pred coll] (some (fn [x] (when (pred x) x)) coll))]
      (or
       ;; tier 1: exact id
       (hit (fn [id] (= id (str wanted))) ids)
       ;; tier 2: case-insensitive exact id
       (hit (fn [id] (= (str/lower-case id) w)) ids)
       ;; tier 3: substring of id
       (hit (fn [id] (str/includes? (str/lower-case id) w)) ids)
       ;; tier 4: substring of name
       (some (fn [x] (when (str/includes? (str/lower-case (str (:name x))) w)
                       (str (:id x))))
             choices)
       ;; tier 5: substring of description (last resort)
       (some (fn [x] (when (str/includes? (str/lower-case (str (:description x))) w)
                       (str (:id x))))
             choices)))))

(defn resolve-model-id
  "Resolve a user-supplied model string against a session's advertised
   `available-models` (vector of `{:modelId :name :description}`) — the
   LEGACY `:models` shape. Returns the matched `:modelId`, or nil."
  [available-models model]
  (resolve-choice (mapv #(assoc % :id (:modelId %)) available-models) model))

(defn resolve-config-value
  "Resolve a user-supplied string against a config option's `:options`
   (vector of `{:value :name :description}`). Returns the matched
   `:value`, or nil. Same fuzzy tiers as `resolve-model-id`."
  [option wanted]
  (resolve-choice (mapv #(assoc % :id (:value %)) (:options option)) wanted))

;; =============================================================================
;; prompt!
;;
;; This is the workhorse. It sends `session/prompt` and waits for
;; the response. Notifications (session/update) flow into the
;; client's on-event callback during the wait. This means the caller
;; gets a streaming experience without polling.
;; =============================================================================

(defn prompt!
  "Send a `session/prompt` and block until the response arrives.

   `content` is a vector of ACP content blocks
   (see `ai.brainyard.acp.interface/ContentBlock`). For text-only
   prompts use `(prompt-text! sess \"hello\")`.

   Returns a result map:
     {:stop-reason str           ;; \"end_turn\" | \"cancelled\" | …
      :raw         map           ;; full agent result
      :end-event   map}          ;; pre-translated end-of-turn event
                                 ;; (caller may forward to its own
                                 ;;  hook firing layer)

   Caller is responsible for firing the iteration/pre event before
   calling prompt! (use `iteration-pre-event` from `events.clj`).
   We do not fire it ourselves to keep `acp-client` independent of
   `agent`."
  ([sess content] (prompt! sess content {}))
  ([{:keys [session-id client] :as _sess} content {:keys [timeout-ms]
                                                   :or   {timeout-ms 600000}}]
   (let [result (client/await-result
                 client
                 (client/request! client "session/prompt"
                                  {:sessionId session-id
                                   :prompt    content}
                                  {:timeout-ms timeout-ms})
                 timeout-ms)
         stop  (:stopReason result)]
     {:stop-reason stop
      :raw         result
      :end-event   (events/translate-stop-reason stop session-id)})))

(defn prompt-text!
  "Convenience: send a single text block."
  ([sess text] (prompt-text! sess text {}))
  ([sess text opts]
   (prompt! sess [{:type "text" :text text}] opts)))

;; =============================================================================
;; cancel!
;; =============================================================================

(defn cancel!
  "Send `session/cancel` for an in-flight prompt.

   A NOTIFICATION, not a request. ACP defines no response for this method, so
   sending it with an id and awaiting a result could only ever fail — the
   claude-code adapter answers `\"Method not found\": session/cancel`, which is
   exactly what it did the first time anything called this. Nothing had, so the
   defect sat here unnoticed.

   Returns nil as soon as the frame is written. There is no acknowledgement to
   wait for: the in-flight `prompt!` resolves on its own with
   `:stop-reason \"cancelled\"` once the agent winds the turn down.

   `opts` is accepted and ignored, so the old timeout-bearing call shape keeps
   working — there is no longer anything for a timeout to bound."
  ([sess] (cancel! sess {}))
  ([{:keys [session-id client] :as _sess} _opts]
   (client/notify! client "session/cancel" {:sessionId session-id})))

;; =============================================================================
;; Iteration helpers — caller fires these via its own hook system if
;; one is wired up (Phase 5).
;; =============================================================================

(defn iteration-pre-event
  "Build the event descriptor for the start of an ACP turn."
  [sess prompt]
  (events/iteration-pre-event (:session-id sess) prompt))
