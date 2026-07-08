;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.acp-commands
  "The `acp$*` command family — CRUD + ask management for ACP agent instances.

   ACP agents are session-shared external CONNECTIONS (a subprocess + one
   model-pinned ACP session + live conversation), not throwaway owned
   subagents. The generic `agent-registry$*` family can't tell one from another
   — every instance prints as `:acp-agent/<suffix>`. This family layers an
   acp-aware overlay over the same registry so callers can answer 'who's for
   what, when useful':

     acp$create  — provision a NAMED connection (backend + model + purpose),
                   eager: spawns + opens the session up front. Refuses at the
                   per-session cap (a paid external session is never silently
                   LRU-evicted).
     acp$list    — acp-only, enriched rows (backend/model/purpose/health/…),
                   optional :backend / :model filter for reuse lookup.
     acp$detail  — one connection deep: descriptor + advertised models +
                   last answer.
     acp$ask     — follow-up ask to a shared connection (same-session, ANY
                   caller — no ownership fence, unlike agent-registry$ask).
     acp$update  — relabel (:purpose) or switch model (:model → session
                   RECYCLE, conversation context resets).
     acp$close   — reap an acp$create-provisioned connection's subprocess.

   Reach: ACP instances are shared, so reads are ungated and acp$ask is
   same-session with no ownership fence. acp$close/update are same-session and
   idle-only; acp$close only tears down PROVISIONED roots — TUI-attached roots
   go through `/agent close`, owned subagents through `agent-registry$close`.

   See docs/design/acp-agent-management.md."
  (:require [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.agent.common.acp-agent :as acp]
            [ai.brainyard.util.interface :as util]
            [clojure.string :as str]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- ->kw
  "Coerce a backend arg (\":claude-code\" | \"claude-code\" | :claude-code) to a
   keyword; nil/blank → nil."
  [x]
  (cond
    (keyword? x) x
    (and (string? x) (not (str/blank? x)))
    (keyword (if (str/starts-with? x ":") (subs x 1) x))
    :else nil))

(defn- id->str [aid] (util/kw->str aid))

(defn- acp-instances
  "Live acp-agent instances in `session-id` (all sessions when nil)."
  [session-id]
  (->> (if session-id
         (agent-core/list-agents-for-session session-id)
         (agent-core/list-agents))
       (filter acp/acp-instance?)))

(defn- resolve-acp
  "Resolve an id string to a live ACP instance, tolerating a leading colon and a
   bare suffix (unique among acp instances). Returns the Agent or nil."
  [id-str]
  (let [s   (str id-str)
        aid (keyword (if (str/starts-with? s ":") (subs s 1) s))
        ag  (agent-core/get-agent aid)]
    (cond
      (and ag (acp/acp-instance? ag)) ag
      (nil? (namespace aid))
      (let [suffix  (name aid)
            matches (filter #(= suffix (name (:agent-id %))) (acp-instances nil))]
        (when (= 1 (count matches)) (first matches)))
      :else nil)))

(defn- authorize-acp
  "Shared-connection reach fence for acp$ask/update/close. Returns nil when the
   caller may act, else an {:error …} map. Reads (list/detail) do not call this."
  [caller target op]
  (cond
    (nil? caller) nil ;; programmatic / TUI colon-command (dispatched AS the root)

    (not (config/get-config caller :enable-subagent-calls))
    {:error "ACP management is disabled (enable-subagent-calls=false)."}

    (not= (proto/session-id target) (proto/session-id caller))
    {:error "ACP instance belongs to a different session; you can only manage connections in your own session."}

    (and (= op :ask) (= (:agent-id caller) (:agent-id target)))
    {:error "You cannot ask yourself."}

    :else nil))

(defn- acp-row
  "One summary row for acp$list."
  [ag]
  (let [d  (acp/descriptor ag)
        st @(:!state ag)
        lc (agent-core/lifecycle ag)]
    {:acp-id       (id->str (:agent-id ag))
     :backend      (:backend d)
     :model        (:model-label d)
     :purpose      (:purpose d)
     :session-id   (:session-id d)
     :health       (:health d)
     :prompts      (or (:prompts d) 0)
     :status       (:status st)
     :owner        (:owner lc)
     :provisioned? (boolean (:provisioned? d))
     :idle-ms      (agent-core/instance-idle-ms ag)}))

;; ============================================================================
;; Commands
;; ============================================================================

(defcommand acp$create
  "Provision a NEW managed ACP connection and return its :acp-id. Eager: spawns
   the backend subprocess and opens the model-pinned session up front, ready for
   acp$ask. Prefer reusing an existing connection (acp$list with :backend/:model)
   over creating a redundant one. Refuses at the per-session cap
   (:max-acp-agents-per-session) — close one with acp$close first. :backend is
   required (:stub | :claude-code | :gemini | :codex | a registered custom key);
   :model is optional and pinned for the connection's life (switch later via
   acp$update, which recycles the session)."
  (fn [& {:as args}]
    (let [caller  proto/*current-agent*
          backend (->kw (:backend args))
          model   (:model args)
          purpose (:purpose args)
          extra   (:backend-opts args)]
      (cond
        (nil? caller)
        {:error "acp$create needs an active session (no caller agent is bound)."}

        (not (config/get-config caller :enable-subagent-calls))
        {:error "ACP management is disabled (enable-subagent-calls=false)."}

        (nil? backend)
        {:error ":backend is required (e.g. :claude-code, :gemini, :codex, :stub)."}

        :else
        (let [sid  (proto/session-id caller)
              uid  (proto/user-id caller)
              cap  (or (config/get-config caller :max-acp-agents-per-session) 3)
              live (count (acp-instances sid))]
          (if (>= live cap)
            {:error (format "ACP cap reached (%d/%d) for this session. Close one with acp$close before creating another."
                            live cap)}
            (let [backend-opts (cond-> (or extra {})
                                 (and model (not (str/blank? (str model)))) (assoc :model model))
                  ag (agent-core/setup-agent-by-id
                      :acp-agent
                      :agent-session    {:user-id uid :session-id sid}
                      :acp-backend      backend
                      :acp-backend-opts backend-opts)]
              (acp/mark-provisioned! ag)
              (when purpose (acp/set-purpose! ag purpose))
              (try
                (let [d (acp/ensure-connected! ag)]
                  {:acp-id     (id->str (:agent-id ag))
                   :descriptor d})
                (catch Throwable t
                  ;; Roll back a registered-but-unusable instance (e.g. missing
                  ;; prereq / spawn failure) so it doesn't linger in the registry.
                  (agent-core/force-close-instance! (:agent-id ag))
                  {:error (str "Failed to open ACP connection for " backend ": "
                               (ex-message t))}))))))))
  :input-schema  [:map
                  [:backend [:string {:desc "Backend key: :stub | :claude-code | :gemini | :codex | a registered custom key"}]]
                  [:model {:optional true} [:string {:desc "Model to pin for this connection (e.g. \"opus\"/\"sonnet\" for :claude-code); resolved against the backend's advertised models"}]]
                  [:purpose {:optional true} [:string {:desc "Short role label (\"who's for what\"), e.g. \"refactor payments\""}]]
                  [:backend-opts {:optional true} [:map {:desc "Extra launch options merged into :acp-backend-opts (e.g. :command/:working-dir/:env)"}]]]
  :output-schema [:map
                  [:acp-id [:string {:desc "Instance id of the new connection (e.g. \"acp-agent/silver-otter-7\") — use with acp$ask"}]]
                  [:descriptor [:map {:desc "{:backend :model-label :model-id :session-id :purpose :health :provisioned? …}"}]]
                  [:error [:string {:desc "Error: no session, disabled, missing :backend, cap reached, or open failure (missing prereq)"}]]])

(defcommand acp$list
  "List live ACP connections in your session with backend/model/purpose/health —
   the 'who's for what, when useful' view. Filter by :backend and/or :model to
   check whether a connection you want already exists before acp$create (reuse
   over re-spawn). Pass :session-id to scope to another session; omit to list all
   sessions."
  (fn [& {:as args}]
    (let [caller  proto/*current-agent*
          sid     (or (:session-id args) (some-> caller proto/session-id))
          bkf     (->kw (:backend args))
          mdf     (:model args)
          rows    (->> (acp-instances (when-not (str/blank? (str sid)) sid))
                       (map acp-row)
                       (filter (fn [r]
                                 (and (or (nil? bkf) (= bkf (:backend r)))
                                      (or (str/blank? (str mdf)) (= mdf (:model r))))))
                       vec)]
      {:acp-agents rows :total (count rows)}))
  :input-schema  [:map
                  [:session-id {:optional true} [:string {:desc "Session id to scope to; omit to list all sessions"}]]
                  [:backend {:optional true} [:string {:desc "Filter to one backend (e.g. :claude-code)"}]]
                  [:model {:optional true} [:string {:desc "Filter to one model label (e.g. \"opus\")"}]]]
  :output-schema [:map
                  [:acp-agents [:string {:desc "Vector of {:acp-id :backend :model :purpose :session-id :health :prompts :status :owner :provisioned? :idle-ms}"}]]
                  [:total [:int {:desc "Number of connections returned"}]]])

(defcommand acp$detail
  "Detail for one ACP connection by :id — full descriptor, the backend's
   advertised models (what acp$update :model can switch to), status, idle age,
   and last answer."
  (fn [& {:as args}]
    (let [id (:id args)]
      (if (str/blank? (str id))
        {:error "id is required"}
        (if-let [ag (resolve-acp id)]
          {:acp-id            (id->str (:agent-id ag))
           :descriptor        (acp/descriptor ag)
           :advertised-models (acp/advertised-models ag)
           :status            (:status @(:!state ag))
           :idle-ms           (agent-core/instance-idle-ms ag)
           :last-answer       (agent-core/last-answer ag)}
          {:error (str "ACP instance not found: " id)}))))
  :input-schema  [:map
                  [:id [:string {:desc "ACP instance id (e.g. \"acp-agent/silver-otter-7\")"}]]]
  :output-schema [:map
                  [:acp-id [:string {:desc "Instance id"}]]
                  [:descriptor [:map {:desc "Connection descriptor"}]]
                  [:advertised-models [:string {:desc "Model ids the backend advertised on the session"}]]
                  [:status [:string {:desc ":idle | :running | …"}]]
                  [:idle-ms [:int {:desc "ms since last ask (or spawn)"}]]
                  [:last-answer [:string {:desc "Most recent answer"}]]
                  [:error [:string {:desc "Error if id missing or not found"}]]])

(defcommand acp$ask
  "Send a question to a live ACP connection by :id, reusing its conversation
   context. Because ACP connections are SHARED, any agent in the session may ask
   any connection (no ownership fence — unlike agent-registry$ask). Runs
   synchronously and leaves the connection alive. Errors if missing, :running
   (busy), a different session, or the agent-call depth limit is reached."
  (fn [& {:as args}]
    (let [id     (:id args)
          q      (:question args)
          caller proto/*current-agent*]
      (cond
        (str/blank? (str id)) {:error "id is required"}
        (str/blank? (str q))  {:error "question is required"}
        :else
        (if-let [target (resolve-acp id)]
          (or (authorize-acp caller target :ask)
              (agent-core/ask-agent (:agent-id target) q
                                    :caller-id (some-> caller :agent-id)))
          {:error (str "ACP instance not found: " id)}))))
  :input-schema  [:map
                  [:id [:string {:desc "ACP instance id to ask (from acp$list)"}]]
                  [:question [:string {:desc "Question for the connection"}]]]
  :output-schema [:map
                  [:id [:string {:desc "Instance id"}]]
                  [:answer [:string {:desc "The connection's answer"}]]
                  [:status [:string {:desc "Status after the ask (typically :idle)"}]]
                  [:error [:string {:desc "Error: missing args, not found, :running, different session, or depth-limit"}]]])

(defcommand acp$update
  "Update a live ACP connection by :id. :purpose relabels it. :model SWITCHES the
   model — since a model is fixed per ACP session, this RECYCLES the session: a
   fresh session opens with the new model and the conversation context is RESET.
   Idle-only (wait out a :running connection)."
  (fn [& {:as args}]
    (let [id          (:id args)
          new-purpose (:purpose args)
          new-model   (:model args)
          caller      proto/*current-agent*]
      (if (str/blank? (str id))
        {:error "id is required"}
        (if-let [target (resolve-acp id)]
          (or (authorize-acp caller target :update)
              (if (agent-core/running-instance? target)
                {:error "Instance is :running; wait until idle before updating."}
                (do
                  (when (and new-purpose (not (str/blank? (str new-purpose))))
                    (acp/set-purpose! target new-purpose))
                  (let [recycled (when (and new-model (not (str/blank? (str new-model))))
                                   (acp/recycle-session! target new-model))]
                    {:acp-id         (id->str (:agent-id target))
                     :descriptor     (or recycled (acp/descriptor target))
                     :model-recycled (boolean recycled)
                     :note           (when recycled
                                       "Model changed → ACP session recycled; conversation context was RESET.")}))))
          {:error (str "ACP instance not found: " id)}))))
  :input-schema  [:map
                  [:id [:string {:desc "ACP instance id"}]]
                  [:purpose {:optional true} [:string {:desc "New role label"}]]
                  [:model {:optional true} [:string {:desc "New model — recycles the session (context reset)"}]]]
  :output-schema [:map
                  [:acp-id [:string {:desc "Instance id"}]]
                  [:descriptor [:map {:desc "Updated descriptor"}]]
                  [:model-recycled [:boolean {:desc "True when a model switch recycled the session"}]]
                  [:note [:string {:desc "Human note when the session was recycled"}]]
                  [:error [:string {:desc "Error: missing id, not found, :running, different session"}]]])

(defcommand acp$close
  "Close (reap) an acp$create-provisioned ACP connection by :id — tears down its
   subprocess and external session. Refuses while :running. Only tears down
   PROVISIONED connections: a TUI-attached root goes through /agent close; an
   owned subagent through agent-registry$close."
  (fn [& {:as args}]
    (let [id     (:id args)
          caller proto/*current-agent*]
      (if (str/blank? (str id))
        {:error "id is required"}
        (if-let [target (resolve-acp id)]
          (or (authorize-acp caller target :close)
              (let [d     (acp/descriptor target)
                    owner (:owner (agent-core/lifecycle target))]
                (cond
                  (some? owner)
                  {:error "This ACP instance is an owned subagent — close it via agent-registry$close."}

                  (not (:provisioned? d))
                  {:error "This ACP instance is a TUI-attached root — close it via /agent close (acp$close only reaps acp$create-provisioned connections)."}

                  :else
                  (agent-core/close-instance! (:agent-id target)))))
          {:error (str "ACP instance not found: " id)}))))
  :input-schema  [:map
                  [:id [:string {:desc "ACP instance id to close"}]]]
  :output-schema [:map
                  [:closed [:boolean {:desc "True when closed"}]]
                  [:id [:string {:desc "Instance id"}]]
                  [:error [:string {:desc "Error: missing id, not found, :running, different session, owned subagent, or TUI root"}]]])

;; ============================================================================
;; Roster export
;; ============================================================================

(def all-acp-commands
  "The acp$* family — rides default-agent-roster so every coact/react-derived
   agent can manage ACP connections."
  [#'acp$create #'acp$list #'acp$detail #'acp$ask #'acp$update #'acp$close])
