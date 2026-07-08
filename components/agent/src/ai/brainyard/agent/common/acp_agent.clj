;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.acp-agent
  "ACP-driven agent. The external ACP backend owns the iteration loop;
   this defagent's BT is a single `:repeat max-n=1` over one ACP turn,
   so the existing TUI iteration block, todo updates, tool-use widgets,
   and permission popups continue to work unchanged.

   Per docs/design/acp-design.md §4.4. The default backend is `:stub`
   (in-tree, deterministic — see `bases/acp-stub-agent`). Real backends
   like `:claude-code`, `:gemini`, `:codex` are `acp-client/registry`
   entries.

   ## Soft coupling

   acp-client is reached via `requiring-resolve` so this namespace
   loads even if the consumer hasn't put `ai.brainyard/acp-client` on
   their classpath. Calling the agent in that case raises a clear
   error pointing at the missing dep.

   ## Hook bridge

   `session/update` notifications are translated by
   `acp-client/translate-update` into brainyard hook events
   (`:agent.dspy-action/chunk`, `:todo/updated`, `:agent.tool-use/pre`,
   `:agent.tool-use/post`) and fired through the standard hooks
   registry. The TUI's existing handlers (in `bases/agent-tui`)
   render these without any new code."
  (:require [ai.brainyard.agent.common.schema :as acs]
            [ai.brainyard.agent.core.agent :as agent]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.session :as session]
            [ai.brainyard.agent.core.tool :refer [defagent]]
            [ai.brainyard.behavior-tree.interface :as bt :refer [st-memory-has-value?]]
            [ai.brainyard.behavior-tree.interface.protocol :as p]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; =============================================================================
;; Soft-resolve into ai.brainyard.acp-client.interface
;; =============================================================================

(def ^:private acp-client-ns 'ai.brainyard.acp-client.interface)

(defn- acp-client-fn [sym-name]
  (let [v (try (requiring-resolve (symbol (name acp-client-ns) (name sym-name)))
               (catch Exception _ nil))]
    (when-not v
      (throw (ex-info
              (str "acp-agent needs ai.brainyard/acp-client on the classpath; "
                   "could not resolve " acp-client-ns "/" sym-name ".")
              {:agent :acp-agent :missing (str acp-client-ns "/" sym-name)})))
    v))

(defn- spawn!*           [& args] (apply (acp-client-fn 'spawn!) args))
(defn- initialize!*      [& args] (apply (acp-client-fn 'initialize!) args))
(defn- open?*            [& args] (apply (acp-client-fn 'open?) args))
(defn- new-session!*     [& args] (apply (acp-client-fn 'new-session!) args))
(defn- set-model!*       [& args] (apply (acp-client-fn 'set-model!) args))
(defn- resolve-model-id* [& args] (apply (acp-client-fn 'resolve-model-id) args))
(defn- prompt!*          [& args] (apply (acp-client-fn 'prompt!) args))
(defn- close!*           [& args] (apply (acp-client-fn 'close!) args))
(defn- translate-update* [& args] (apply (acp-client-fn 'translate-update) args))
(defn- pick-option-id*   [& args] (apply (acp-client-fn 'pick-option-id) args))

;; =============================================================================
;; Permission bridge — ACP session/request_permission → TUI user-feedback
;;
;; A real ACP backend asks the client to approve a tool call by sending
;; `session/request_permission` with a vector of agent-supplied options
;; (allow_once / reject_once / …). Without an override, acp-client's
;; default handler denies everything (callbacks/default-request-permission).
;;
;; We route the request to the agent's interactive N-option picker — the
;; same `:user-feedback-fn` the TUI installs and `get-user-feedback` uses —
;; so the existing permission UX surfaces ACP approvals too. When no
;; interactive session is wired (piped / non-raw), we keep the deny-by-
;; default posture by selecting a reject_ option.
;; =============================================================================

(defn- permission-option-label
  "Human label for an ACP permission option — prefer :name, fall back to
   the :optionId."
  [{:keys [name optionId]}]
  (if (and name (not (str/blank? name))) name optionId))

(defn- permission-question
  "Build the picker prompt from the ACP toolCall descriptor."
  [{:keys [title kind]}]
  (str "Permission requested"
       (when (and title (not (str/blank? title))) (str ": " title))
       (when (and kind (not (str/blank? kind)))   (str " [" kind "]"))))

(defn- make-permission-callback
  "Reverse-call handler for ACP `session/request_permission`. Routes the
   request to the agent's interactive `:user-feedback-fn` (N-option
   picker). Returns an ACP SessionRequestPermissionResult:

     {:outcome {:outcome \"selected\" :optionId <id>}}  on a choice
     {:outcome {:outcome \"cancelled\"}}                 on timeout/dismiss

   No interactive session (or no options) → deny by selecting a reject_
   option, matching acp-client's deny-by-default handler."
  [agent]
  (fn [{:keys [toolCall options] :as _params}]
    (let [options     (vec options)
          feedback-fn (some-> (:!session agent) deref
                              (session/get-session-config :user-feedback-fn))]
      (cond
        (empty? options)
        {:outcome {:outcome "cancelled"}}

        (nil? feedback-fn)
        {:outcome {:outcome  "selected"
                   :optionId (pick-option-id* :block options)}}

        :else
        (let [result (feedback-fn
                      {:question   (permission-question toolCall)
                       :options    (mapv permission-option-label options)
                       :timeout-ms (config/get-config agent :acp-permission-timeout-ms)})
              idx    (:index result)]
          (if (and (integer? idx) (< -1 idx (count options)))
            {:outcome {:outcome  "selected"
                       :optionId (:optionId (nth options idx))}}
            ;; timeout / cancel / unknown selection → cancelled
            {:outcome {:outcome "cancelled"}}))))))

;; =============================================================================
;; Per-agent AcpClient cache
;;
;; Stored on the Agent record's `:!state` under ::client so it
;; survives across multiple asks. Cleaned up on agent close via the
;; :agent.instance/closed hook registered below.
;; =============================================================================

(def ^:private cache-key ::client)

(defn- get-or-spawn-client!
  "Return the cached `{:client :on-event-atom}` for this agent,
   spawning a fresh AcpClient if absent.

   The AcpClient's `:on-event` is captured by the dispatcher pump at
   spawn-time, so we install a stable wrapper closure once and mutate
   the underlying atom per ACP turn. This keeps the running pump's
   reference valid while still letting each turn install its own
   chunk accumulator."
  [agent backend backend-opts]
  (let [!state (:!state agent)]
    (or (get @!state cache-key)
        (let [!on-event (atom (fn [_msg] nil))
              c (spawn!* backend
                         {:on-event     (fn [msg]
                                          (when-let [f @!on-event]
                                            (f msg)))
                          :callbacks    {"session/request_permission"
                                         (make-permission-callback agent)}
                          :backend-opts backend-opts})
              cached {:client c :on-event-atom !on-event}]
          (initialize!* c)
          (swap! !state assoc cache-key cached)
          cached))))

(def ^:private session-key ::session)

;; =============================================================================
;; Per-instance descriptor — the acp management overlay
;;
;; ACP agents are session-shared external CONNECTIONS (subprocess + one
;; model-pinned ACP session + live conversation), not throwaway owned
;; subagents. The generic registry (`agent-registry$*`) can't distinguish one
;; from another — every instance prints as `:acp-agent/<suffix>`. The
;; `::descriptor` on !state carries the identity that actually matters (which
;; backend, which model, which session, what for, is it alive), so the
;; `acp$*` command family can answer "who's for what, when useful".
;; See docs/design/acp-agent-management.md.
;; =============================================================================

(def ^:private descriptor-key ::descriptor)

(defn acp-instance?
  "True when `agent` is an acp-agent instance (by defagent-type prefix on its
   instance-id, e.g. :acp-agent/silver-otter-7)."
  [agent]
  (let [aid (:agent-id agent)]
    (and (keyword? aid) (= "acp-agent" (namespace aid)))))

(defn- stamp-descriptor! [agent m]
  (swap! (:!state agent) update descriptor-key merge m))

(defn- live-health
  "Current connection health, probed from the cached client (never throws)."
  [agent]
  (if-let [{:keys [client]} (get @(:!state agent) cache-key)]
    (if (try (open?* client) (catch Throwable _ false)) :open :dead)
    :unconnected))

(defn descriptor
  "Return the acp connection descriptor for this instance, or nil if it was
   never connected. `:purpose` falls back to a derived `<backend>/<model>`
   label; `:health` is probed live from the client."
  [agent]
  (when-let [d (get @(:!state agent) descriptor-key)]
    (assoc d
           :health  (live-health agent)
           :purpose (if (and (:purpose d) (not (str/blank? (:purpose d))))
                      (:purpose d)
                      (str (name (or (:backend d) :stub))
                           (when (:model-label d) (str "/" (:model-label d))))))))

(defn set-purpose!
  "Set the human/LLM-facing purpose (\"who's for what\") on the descriptor."
  [agent purpose]
  (when (and purpose (not (str/blank? (str purpose))))
    (swap! (:!state agent) update descriptor-key
           (fn [d] (assoc (or d {}) :purpose (str/trim (str purpose))))))
  agent)

(defn advertised-models
  "Model ids the backend advertised on the open session (for acp$detail so the
   caller sees what acp$update :model can switch to), or nil if not connected."
  [agent]
  (some->> (get @(:!state agent) session-key)
           :models :availableModels
           (mapv :modelId)))

(defn mark-provisioned!
  "Flag this instance as acp$create-provisioned (headless, owner nil) so acp$close
   may tear it down. TUI-attached roots lack this flag and are managed via
   `/agent close` — acp$close refuses them to avoid closing an attached root."
  [agent]
  (swap! (:!state agent) update descriptor-key
         (fn [d] (assoc (or d {}) :provisioned? true)))
  agent)

(defn- open-session!
  "Open a fresh ACP session on `client` and apply model selection once,
   stamping the connection descriptor.

   Anchors cwd at the project root (git-root), not the raw JVM user.dir
   — under `bb tui` that's the projects/agent-tui-app/ subdir, so the
   backend would resolve relative paths against the wrong tree.

   Model: `:acp-backend-opts {:model \"sonnet\"}` → resolve against the
   agent's advertised models and set it via ACP `session/set_model` (a
   per-session concern; the launch spec / env can't carry a model).
   Unmatched ⇒ warn and keep the agent's default model."
  [agent client backend-opts]
  (let [backend (config/get-config agent :acp-backend)
        sess    (new-session!* client {:cwd (config/project-dir agent)})
        model   (:model backend-opts)
        model-id
        (when model
          (let [avail (get-in sess [:models :availableModels])
                mid   (resolve-model-id* avail model)]
            (if mid
              (do (set-model!* sess mid)
                  (mulog/info ::acp-model-selected :requested model :model-id mid)
                  mid)
              (do (mulog/warn ::acp-model-unmatched :requested model
                              :available (mapv :modelId avail))
                  nil))))]
    (stamp-descriptor! agent
                       {:backend      backend
                        :backend-opts backend-opts
                        :model-label  model
                        :model-id     model-id
                        :session-id   (:session-id sess)
                        :spawned-at   (System/currentTimeMillis)})
    sess))

(defn- get-or-open-session!
  "Return the cached ACP session for this agent, opening one on the
   cached client if absent. The session is created ONCE per agent
   instance and reused across every `ask`, so the backend keeps
   conversation context (the claude-code adapter streams each
   `session/prompt` into one long-lived query). Torn down with the
   client on agent close."
  [agent client backend-opts]
  (let [!state (:!state agent)]
    (or (get @!state session-key)
        (let [sess (open-session! agent client backend-opts)]
          (swap! !state assoc session-key sess)
          sess))))

(defn ensure-connected!
  "Eagerly spawn the ACP client + open the session for this instance (idempotent),
   stamping the descriptor. Used by acp$create to PROVISION a connection before
   any question is asked. Returns the descriptor. Throws the clear
   missing-classpath error if ai.brainyard/acp-client is absent."
  [agent]
  (let [backend      (config/get-config agent :acp-backend)
        backend-opts (config/get-config agent :acp-backend-opts)
        {:keys [client]} (get-or-spawn-client! agent backend backend-opts)]
    (get-or-open-session! agent client backend-opts)
    (descriptor agent)))

(defn recycle-session!
  "Model is fixed for the life of an ACP session, so switching models means a
   session RECYCLE: drop the cached session and open a fresh one with `new-model`
   pinned. Conversation context is RESET (a new session is a new conversation).
   Persists the new model as a per-agent config override (not global) so later
   opens keep it. Returns the updated descriptor."
  [agent new-model]
  (let [backend      (config/get-config agent :acp-backend)
        backend-opts (assoc (config/get-config agent :acp-backend-opts) :model new-model)
        {:keys [client]} (get-or-spawn-client! agent backend backend-opts)]
    ;; Per-agent override only — never write the global default model.
    (when-let [smi (some-> agent :!state deref :st-memory-init)]
      (swap! smi assoc-in [:config :acp-backend-opts] backend-opts))
    ;; Drop the cached session so the next open uses the new model. The old
    ;; backend session is abandoned (backends GC idle sessions).
    (swap! (:!state agent) dissoc session-key)
    (get-or-open-session! agent client backend-opts)
    (descriptor agent)))

(defn- on-event-handler
  "Build the on-event closure used during one ACP turn. Translates each
   session/update notification through acp-client/translate-update and
   fires the resulting brainyard hook event with `:agent` and
   `:accumulated` enriched (the TUI's dspy-chunk-handler reads them).

   `accumulator` is a StringBuilder shared with the action so the
   final answer text is reconstructed without additional state."
  [agent ^StringBuilder accumulator]
  (fn [msg]
    (when (= "session/update" (:method msg))
      (try
        (when-let [{:keys [event data]} (translate-update* (:params msg))]
          (let [enriched
                (case event
                  :agent.dspy-action/chunk
                  (let [chunk (:chunk data)]
                    (when (seq chunk) (.append accumulator ^String chunk))
                    (assoc data :agent agent
                           :accumulated (str accumulator)))

                  ;; Other events get :agent enriched only.
                  (assoc data :agent agent))]
            (hooks/fire! event enriched)))
        (catch Throwable t
          (mulog/warn ::acp-on-event-error
                      :method (:method msg)
                      :error  (ex-message t)))))))

;; =============================================================================
;; BT action — drives one ACP turn
;; =============================================================================

(defn- acp-prompt-action
  "BT `:action` body. Reads `:question` from st-memory, drives one
   `session/prompt` round-trip on the cached AcpClient, accumulates
   streamed text into st-memory `:answer`, returns p/success.

   Hook firing during the turn is handled by the on-event closure
   passed at AcpClient spawn-time (one per agent); we replace it
   per-action so the chunks stream into THIS turn's accumulator.

   On `cancelled` or other non-`end_turn` stop reasons, sets
   `:goal-achieved` to false in st-memory; otherwise true."
  [{:keys [st-memory agent]}]
  (let [backend      (config/get-config agent :acp-backend)
        backend-opts (config/get-config agent :acp-backend-opts)
        question (:question @st-memory)
        accumulator (StringBuilder.)
        {:keys [client on-event-atom]} (get-or-spawn-client! agent backend backend-opts)]
    ;; Optional per-dispatch label — folds into the descriptor so
    ;; acp$list/detail can say "who's for what" for a dispatched instance.
    (when-let [p (:purpose @st-memory)] (set-purpose! agent p))
    ;; Install this turn's on-event handler. The pump still calls the
    ;; stable wrapper installed at spawn time; the wrapper dispatches
    ;; through this atom so each turn sees its own accumulator.
    (reset! on-event-atom (on-event-handler agent accumulator))
    (try
      ;; Reuse ONE ACP session per agent instance (opened lazily) so the
      ;; backend keeps conversation context across asks — a fresh
      ;; session/new per turn would reset the conversation.
      (let [sess (get-or-open-session! agent client backend-opts)
            {:keys [stop-reason]} (prompt!* sess
                                            [{:type "text" :text question}]
                                            {:timeout-ms (config/get-config agent :acp-timeout-ms)})
            answer (str accumulator)
            goal-achieved? (= "end_turn" stop-reason)]
        (swap! st-memory assoc
               :answer answer
               :goal-achieved goal-achieved?
               :stop-reason stop-reason)
        ;; Descriptor bookkeeping: count ACP turns driven on this connection.
        (swap! (:!state agent) update descriptor-key
               (fn [d] (update (or d {}) :prompts (fnil inc 0))))
        ;; Fire :agent.dspy-action/post so the TUI iteration block
        ;; clears its streaming state and freezes the final text.
        (hooks/fire! :agent.dspy-action/post
                     {:agent agent :usage {} :reasoning nil})
        p/success)
      (catch Throwable t
        (mulog/error ::acp-prompt-action-error :error (ex-message t))
        (swap! st-memory assoc
               :answer (str "ACP error: " (ex-message t))
               :goal-achieved false
               :stop-reason "error")
        p/failure))))

;; =============================================================================
;; BT factory — minimal one-iteration tree
;; =============================================================================

(defn acp-behavior-tree
  "Build the ACP agent's behavior tree.

   `[:sequence
      [:condition has-question?]
      [:repeat max-n=1
        [:action acp-prompt]]
      [:condition has-answer?]]`

   The `:repeat` wrapper triggers `:agent.iteration/pre|post` hooks
   so the existing TUI iteration block lights up exactly as it does
   for react/coact agents."
  [_max-iterations]
  [:sequence
   {:id :acp-agent/main}

   [:condition
    {:id :acp-agent/has-question
     :path [:question]
     :schema ::acs/question}
    st-memory-has-value?]

   [:repeat
    {:id :acp-agent/turn
     :max-n 1
     :condition-fn (fn [_ctx] true)}
    [:action
     {:id :acp-agent/prompt}
     acp-prompt-action]]

   [:condition
    {:id :acp-agent/has-answer
     :path [:answer]
     :schema ::acs/answer
     :debug {:source :st-memory}}
    st-memory-has-value?]])

;; =============================================================================
;; Cleanup hook — close cached AcpClient when the agent closes
;; =============================================================================

(hooks/register-hook!
 :agent.instance/closed
 :acp-agent/cleanup
 (fn [{:keys [agent]}]
   (when-let [{:keys [client]} (get @(:!state agent) cache-key)]
     (try
       (close!* client)
       (catch Throwable t
         (mulog/warn ::acp-client-close-error :error (ex-message t))))
     ;; Client close tears down the subprocess (and with it the session);
     ;; drop both cache entries so a re-opened instance starts clean.
     (swap! (:!state agent) dissoc cache-key session-key)))
 :source :acp-agent)

;; =============================================================================
;; defagent registration
;; =============================================================================

(defagent acp-agent
  "ACP-driven agent that hands the question to an external ACP backend (default :stub) and streams responses, plans, tool calls, and permission requests through the TUI hook bridge."
  agent/run-agent
  :bt-factory (fn [{:keys [max-iterations]}] (acp-behavior-tree max-iterations))
  :tool-use-control {}
  :input-schema  [:map
                  [:question [:string {:desc "User question to forward to the ACP backend"}]]
                  [:purpose {:optional true} [:string {:desc "Short role label for this connection (\"who's for what\"); surfaced by acp$list/detail"}]]
                  [:agent-context {:optional true} [:string {:desc "Extra context (currently unused)"}]]
                  [:acp-backend {:optional true} [:keyword {:desc "ACP backend keyword (e.g. :stub)" :default :stub}]]
                  [:acp-backend-opts {:optional true} [:map {:desc "Per-backend launch options forwarded to acp-client/registry" :default {}}]]]
  :output-schema [:map
                  [:answer [:string {:desc "Agent's final answer accumulated from streamed chunks"}]]]
  :agent-tools nil
  :instruction nil
  :tool-context nil)
