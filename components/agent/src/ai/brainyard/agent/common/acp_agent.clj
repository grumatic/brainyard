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

   ## Coupling — a static require, deliberately

   acp-client was reached via `requiring-resolve`, so that a consumer
   without `ai.brainyard/acp-client` on the classpath could still load
   this namespace. That optionality was fictional — `components/agent`
   has hard-declared acp-client in deps.edn since the v0.2.0 import —
   and it broke the shipping binary.

   Clojure's AOT compiler only follows a static `:require`, so a
   namespace reached solely by `requiring-resolve` is never compiled.
   The uberjar carried acp-client as `.clj` source with zero `.class`
   entries, and a GraalVM native image has no runtime Clojure compiler
   to load source with — so the resolve could never succeed in `by`.
   Every `acp-agent` session died on first use with a message claiming
   acp-client was missing from a classpath it was already on. It had
   never worked in a shipped binary; all live verification ran through
   `bb tui:acp` on the JVM, where runtime source loading works.

   Requiring statically is what puts acp-client's classes in the image.
   `clj-llm/core/acp.clj` keeps its own soft-resolve on purpose (see the
   boundary note there) and rides on the classes this require bakes in.

   ## Hook bridge

   `session/update` notifications are translated by
   `acp-client/translate-update` into brainyard hook events
   (`:agent.dspy-action/chunk`, `:todo/updated`, `:agent.tool-use/pre`,
   `:agent.tool-use/post`) and fired through the standard hooks
   registry. The TUI's existing handlers (in `bases/agent-tui`)
   render these without any new code."
  (:require [ai.brainyard.acp-client.interface :as acp-client]
            [ai.brainyard.agent.common.auth :as auth]
            [ai.brainyard.agent.common.schema :as acs]
            [ai.brainyard.agent.common.trajectory :as trajectory]
            [ai.brainyard.agent.core.agent :as agent]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.session :as session]
            [ai.brainyard.agent.core.tool :refer [defagent]]
            [ai.brainyard.behavior-tree.interface :as bt :refer [st-memory-has-value?]]
            [ai.brainyard.behavior-tree.interface.protocol :as p]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; =============================================================================
;; acp-client entry points
;;
;; These `*`-suffixed wrappers are what the rest of the namespace calls. They
;; are kept (rather than inlining `acp-client/spawn!` at each of the ~30 call
;; sites) so this fix stays a one-block diff; each now dispatches straight to
;; the statically-required var instead of resolving it at call time.
;;
;; Deliberately thin `defn-`s, NOT `(def spawn!* acp-client/spawn!)`: an eager
;; value-copy def can freeze as an unbound fn under native-image. Calling
;; through the var at runtime is the shape that survives the image build.
;; =============================================================================

(defn- spawn!*           [& args] (apply acp-client/spawn! args))
(defn- initialize!*      [& args] (apply acp-client/initialize! args))
(defn- open?*            [& args] (apply acp-client/open? args))
(defn- new-session!*     [& args] (apply acp-client/new-session! args))
(defn- set-model!*       [& args] (apply acp-client/set-model! args))
(defn- resolve-model-id* [& args] (apply acp-client/resolve-model-id args))
(defn- prompt!*          [& args] (apply acp-client/prompt! args))
(defn- cancel!*          [& args] (apply acp-client/cancel! args))
(defn- close!*           [& args] (apply acp-client/close! args))
(defn- translate-update* [& args] (apply acp-client/translate-update args))
(defn- pick-option-id*   [& args] (apply acp-client/pick-option-id args))

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
  "Reverse-call handler for ACP `session/request_permission`. Returns an ACP
   SessionRequestPermissionResult:

     {:outcome {:outcome \"selected\" :optionId <id>}}  on a decision
     {:outcome {:outcome \"cancelled\"}}                 on timeout/dismiss

   The decision follows `:permission-mode` — the SAME policy every other
   sensitive tool op is gated by, resolved through `resolve-permission-mode`, so
   `:auto` still means auto-approve in a container and prompt on a bare host.

   This used to prompt unconditionally and ignore the mode, which made an ACP
   backend the one actor in the system that could not be told to stop asking: a
   container session set to auto-approve still blocked on every backend tool
   call, and a `:deny-by-default` session still put the question on screen.

   Only `:ask-each-time` reaches the interactive `:user-feedback-fn`, bounded by
   `:permission-timeout-ms` — the shared prompt timeout, not an ACP-specific one.
   No interactive session (or no options) → deny, the same posture as
   acp-client's default handler."
  [agent]
  (fn [{:keys [toolCall options] :as _params}]
    (let [options     (vec options)
          mode        (config/resolve-permission-mode agent)
          feedback-fn (some-> (:!session agent) deref
                              (session/get-session-config :user-feedback-fn))
          decide      (fn [d] {:outcome {:outcome  "selected"
                                         :optionId (pick-option-id* d options)}})]
      (cond
        (empty? options)
        {:outcome {:outcome "cancelled"}}

        (= :auto-approve mode)    (decide :allow)
        (= :deny-by-default mode) (decide :block)
        (nil? feedback-fn)        (decide :block)

        :else
        (let [result (feedback-fn
                      {:question   (permission-question toolCall)
                       :options    (mapv permission-option-label options)
                       :timeout-ms (config/get-config agent :permission-timeout-ms)})
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
              fs?       (config/get-config agent :acp-client-fs)
              c (spawn!* backend
                         {:on-event     (fn [msg]
                                          (when-let [f @!on-event]
                                            (f msg)))
                          :callbacks    {"session/request_permission"
                                         (make-permission-callback agent)}
                          :backend-opts backend-opts})
              cached {:client c :on-event-atom !on-event}]
          (initialize!* c {:client-capabilities
                           {:fs {:readTextFile fs? :writeTextFile fs?}}})
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

(defn- backend-auth-status
  "Live sign-in status for backends whose credential lives in an EXTERNAL CLI's
   own store (detect-and-instruct; see /login). :claude-code consumes the
   `claude` CLI's subscription credential. Returns :signed-in | :not-signed-in,
   or nil for backends with no such notion (e.g. :stub, or API-key backends)."
  [backend]
  (case backend
    :claude-code (if (auth/claude-logged-in?) :signed-in :not-signed-in)
    nil))

;; Global-first :acp-backend-opts — the GLOBAL config is the base (1-arity
;; read skips stale per-agent/session layers); this instance's own override
;; wins per key. Mirrors acp$create's precedence: global -> instance -> :model.
(defn- effective-backend-opts
  [agent]
  (merge (or (config/get-config :acp-backend-opts) {})
         (or (config/get-config agent :acp-backend-opts) {})))

(defn descriptor
  "Return the acp connection descriptor for this instance, or nil if it was
   never connected. `:purpose` falls back to a derived `<backend>/<model>`
   label; `:health` is probed live from the client; `:auth` is the live
   subscription sign-in status for CLI-delegated backends (nil otherwise)."
  [agent]
  (when-let [d (get @(:!state agent) descriptor-key)]
    (cond-> (assoc d
                   :health  (live-health agent)
                   :purpose (if (and (:purpose d) (not (str/blank? (:purpose d))))
                              (:purpose d)
                              (str (name (or (:backend d) :claude-code))
                                   (when (:model-label d) (str "/" (:model-label d))))))
      (backend-auth-status (:backend d)) (assoc :auth (backend-auth-status (:backend d))))))

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
        avail   (get-in sess [:models :availableModels])
        current (get-in sess [:models :currentModelId])
        model-id
        (when model
          (let [mid (resolve-model-id* avail model)]
            (if mid
              (do (set-model!* sess mid)
                  (mulog/info ::acp-model-selected :requested model :model-id mid)
                  mid)
              (do (mulog/warn ::acp-model-unmatched :requested model
                              :available (mapv :modelId avail))
                  nil))))
        ;; What the session will ACTUALLY serve: the matched id, else the
        ;; backend's own default (unmatched requests keep it — see below).
        effective (or model-id current)
        ;; nil = no model requested; true = requested & matched; false =
        ;; requested but unmatched (running on the backend default instead).
        matched?  (cond (nil? model) nil
                        (some? model-id) true
                        :else false)]
    (stamp-descriptor! agent
                       {:backend          backend
                        :backend-opts     backend-opts
                        :model-label      model            ;; requested (may be nil)
                        :model-id         model-id         ;; resolved match, or nil
                        :effective-model  effective        ;; what actually serves
                        :model-matched?   matched?
                        :available-models (mapv :modelId avail)
                        :session-id       (:session-id sess)
                        :spawned-at       (System/currentTimeMillis)})
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
        backend-opts (effective-backend-opts agent)
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
        backend-opts (assoc (effective-backend-opts agent) :model new-model)
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
                  ;; `agent_thought_chunk` and `agent_message_chunk` both
                  ;; translate to this event; only the MESSAGE text is the
                  ;; answer. Thoughts (`:meta {:kind :thought}`) still fire the
                  ;; hook (so the ACP block renders them) but must NOT append to
                  ;; the accumulator, or reasoning would pollute `:answer`.
                  (let [chunk    (:chunk data)
                        thought? (= :thought (get-in data [:meta :kind]))]
                    (when (and (seq chunk) (not thought?))
                      (.append accumulator ^String chunk))
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
;; Giving up on a turn
;; =============================================================================

(defn- humanize-ms
  "A duration a person can read back to a config value: 600000 -> \"10m\"."
  [ms]
  (let [s (long (/ (or ms 0) 1000))]
    (cond
      (< s 60)        (str s "s")
      (zero? (mod s 60)) (str (quot s 60) "m")
      :else           (format "%dm %ds" (quot s 60) (mod s 60)))))

(defn- cancel-in-flight!
  "Tell the backend to stop the prompt we just gave up on. Returns true when the
   cancel was handed to the transport.

   `session/cancel` is a notification, so there is no acknowledgement to report —
   true means \"sent\", not \"stopped\". The backend winds the turn down on its
   own; we are no longer listening either way.

   Nothing used to send this. `await-result` timing out only ended brainyard's
   WAIT — the subprocess carried on working and billing for a turn no one would
   ever read, and its later `session/update` notifications arrived for a turn
   that had already been closed. The prompt is what gets cancelled, not the ACP
   session: the connection stays usable for the next ask.

   Short deadline and non-throwing on purpose: this runs on the failure path of a
   turn that has already failed, and a backend wedged badly enough to ignore a
   cancel must not also hold up the error the caller is waiting for."
  [agent]
  (boolean
   (when-let [sess (get @(:!state agent) session-key)]
     (try
       (cancel!* sess {:timeout-ms 5000})
       (mulog/info ::acp-prompt-cancelled :agent-id (:agent-id agent))
       true
       (catch Throwable t
         (mulog/warn ::acp-prompt-cancel-failed :error (ex-message t))
         false)))))

(defn- timeout-answer
  "What the user reads when a turn is cut off — the cap that cut it, whether the
   backend was actually stopped, and the knob that changes it. The bare
   \"ACP error: ACP await timeout\" this replaces named the exception and nothing
   a reader could act on."
  [cap-ms cancelled?]
  (str "⏱ Turn cut off after " (humanize-ms cap-ms)
       " — the backend was still working when `:acp-timeout-ms` (" cap-ms "ms) ran out. "
       (if cancelled?
         "A cancel was sent, so the backend should stop shortly. "
         "The cancel could NOT be delivered, so the backend may still be running. ")
       "Anything it produced after the cut-off is lost. "
       "Raise `:acp-timeout-ms` in config.edn for genuinely long turns."))

;; =============================================================================
;; Trajectory
;; =============================================================================

(defn- record-turn!
  "Append one trajectory record for an ACP turn.

   The recorder lives in coact's turn epilogue, which an acp-agent never reaches
   — its whole tree is a single ACP round-trip, so ACP sessions wrote no
   `trajectory.edn` at all. Their questions and answers were kept only in
   `messages.log` and the scrollback, which nothing reads back: a resumed ACP
   session had no per-turn record to show, and the analytics/trajectory surfaces
   were empty for the one agent type whose transcript lives entirely in another
   process.

   `:iterations` is empty by nature, not by omission: the backend owns the
   iteration loop, so brainyard sees one prompt and one answer. The model
   recorded is the EFFECTIVE one (what the backend actually served), falling back
   to the label that was requested when a session never opened.

   Best-effort, exactly like coact's: a write failure must never fail a turn
   that already produced an answer."
  [agent {:keys [question answer success terminated-by started-at]}]
  (when (and agent (config/get-config agent :enable-trajectory-recording))
    (try
      (when-let [sid (some-> (proto/session-id agent) str)]
        (let [d (descriptor agent)]
          (trajectory/append-trajectory!
           sid
           (trajectory/build-turn-trajectory
            {:session-id       sid
             :agent-id         (str (proto/agent-id agent))
             :question         question
             ;; No iteration loop to record: the backend answers in one hop.
             ;; The builder turns this into the terminal "answer" iteration, so
             ;; an ACP record has the same shape as a coact one — minus the
             ;; thought, which there was no iteration to produce.
             :answer           answer
             :iterations       []
             :success          (boolean success)
             :terminated-by    terminated-by
             :model            (or (:effective-model d) (:model-label d))
             :started-at       started-at}))))
      (catch Exception e
        (mulog/debug ::acp-trajectory-store-failed :message (ex-message e))))))

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
        backend-opts (effective-backend-opts agent)
        question (:question @st-memory)
        accumulator (StringBuilder.)
        ;; Stamped before the spawn, so a cold `npx` backend's start-up counts
        ;; toward the turn's duration — it is time the caller waited.
        started-at (System/currentTimeMillis)
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
        ;; Recorded AFTER the prompts bump so the turn number matches the count
        ;; the descriptor reports.
        (record-turn! agent {:question question
                             :answer answer
                             :success goal-achieved?
                             :terminated-by stop-reason
                             :started-at started-at})
        ;; Fire :agent.dspy-action/post so the TUI iteration block
        ;; clears its streaming state and freezes the final text.
        (hooks/fire! :agent.dspy-action/post
                     {:agent agent :usage {} :reasoning nil})
        p/success)
      (catch Throwable t
        ;; A timeout is not the same failure as a dead backend: the backend is
        ;; alive and working, we simply stopped waiting. So it gets its own stop
        ;; reason, its own cancel, and its own wording.
        (let [timeout?   (= :acp/timeout (:type (ex-data t)))
              cap-ms     (config/get-config agent :acp-timeout-ms)
              cancelled? (when timeout? (cancel-in-flight! agent))
              answer     (if timeout?
                           (timeout-answer cap-ms cancelled?)
                           (str "ACP error: " (ex-message t)))
              reason     (if timeout? "timeout" "error")]
          (mulog/error ::acp-prompt-action-error
                       :error (ex-message t) :timeout? timeout? :cancelled? cancelled?)
          (swap! st-memory assoc
                 :answer answer
                 :goal-achieved false
                 :stop-reason reason)
          ;; A failed turn is recorded too. "The backend died here, on this
          ;; question" is exactly what someone reading the trajectory afterwards
          ;; needs, and dropping it would make the log silently skip turns.
          (record-turn! agent {:question question
                               :answer answer
                               :success false
                               :terminated-by reason
                               :started-at started-at}))
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

(defn register-hooks!
  "(Re)register acp-agent's instance-close cleanup hook. Idempotent —
   `register-hook!` dedupes by [event-key handler-id], so calling this at ns
   load, across reloads, or from a test that has wiped the global registry
   (`hooks/reset-hooks!`) is safe. Exposed so tests can re-establish the hook
   without depending on ambient registration surviving a prior test's reset —
   `live-health`/`descriptor` report `:unconnected` only if this hook clears the
   client cache on close."
  []
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
   :source :acp-agent))

(register-hooks!)

;; =============================================================================
;; defagent registration
;; =============================================================================

(defagent acp-agent
  "ACP-driven agent that hands the question to an external ACP backend (default :claude-code) and streams responses, plans, tool calls, and permission requests through the TUI hook bridge."
  agent/run-agent
  :bt-factory (fn [{:keys [max-iterations]}] (acp-behavior-tree max-iterations))
  :tool-use-control {}
  :input-schema  [:map
                  [:question [:string {:desc "User question to forward to the ACP backend"}]]
                  [:purpose {:optional true} [:string {:desc "Short role label for this connection (\"who's for what\"); surfaced by acp$list/detail"}]]
                  [:agent-context {:optional true} [:string {:desc "Extra context (currently unused)"}]]
                  [:acp-backend {:optional true} [:keyword {:desc "ACP backend keyword (e.g. :claude-code, :gemini, :codex); omit to take the :acp-backend config default" :default :claude-code}]]
                  [:acp-backend-opts {:optional true} [:map {:desc "Per-backend launch options forwarded to acp-client/registry" :default {}}]]]
  :output-schema [:map
                  [:answer [:string {:desc "Agent's final answer accumulated from streamed chunks"}]]]
  :agent-tools nil
  :instruction nil
  :tool-context nil)
