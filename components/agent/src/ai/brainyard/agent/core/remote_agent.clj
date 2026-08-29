;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.remote-agent
  "`RemoteAgent` — an A2A peer that behaves like a local subagent.

   ## Why this works, and why it is small

   `agent.core.agent/ask` is NOT hardcoded to the behaviour tree. It
   validates `:!state` / `:!session`, resets runtime, does lifecycle
   bookkeeping (`mark-ask-start!`, turn counters, session messages), fires
   `:agent.ask/pre`, and only then calls the POLYMORPHIC `proto/process`.
   The whole tree lives behind that one protocol method.

   So a record carrying those two atoms and implementing the agent
   protocols flows through `ask`, `ask-agent`, `agent-registry$ask`,
   `authorize-ask`'s reach policy, the depth guard, LRU eviction and the
   parent-close cascade **without a single change to any of them**. Its
   `process` issues an A2A `message/send` instead of ticking a tree.

   That is the entire trick. Everything below is bookkeeping to satisfy the
   shapes the existing call sites read.

   ## A remote peer is always a SUBAGENT

   `:owner` is always set. A session has exactly one root and it is local by
   definition — see the two-axis note in `agent.core.runtime`.
   `:share-parent-session?` is false: a remote peer is a dispatched worker,
   not a second model inside the user's own session (that is the ACP case).

   ## What it deliberately does NOT have

   No behaviour tree, no short-term memory, no sandbox, no memory manager.
   A2A peers are **opaque by design** (docs/design/a2a-design.md §2) — we
   cannot see their reasoning, tools or state, and the protocol offers no
   way to. The `IAgentState` / `IAgentBTIntegration` methods therefore
   return nil rather than inventing a local shadow. Every caller in
   `commands.clj` and `agent.clj` reaches them through `some->`, so nil is
   both safe and honest: `agent-registry$detail` on a remote peer shows no
   local iteration count because there genuinely is none."
  (:require [clojure.string :as str]
            [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-client.interface :as a2a-client]
            ;; The instance registry lives in core.agent. Not a cycle:
            ;; core.agent requires nothing from core.remote-agent (nor from
            ;; common.*), so the arrow only points this way.
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.runtime :as runtime]
            [ai.brainyard.agent.core.session :as session]
            [ai.brainyard.mulog.interface :as mulog]))

(defn- now-ms ^long [] (System/currentTimeMillis))

;; =============================================================================
;; Stream descriptors -> hooks
;;
;; `a2a-client/translate` returns pure descriptors; this is where they become
;; real events. Artifact persistence is deliberately NOT done here: it lives in
;; `common/artifacts.clj`, and a `core.* -> common.*` require would invert the
;; layering. Instead we fire `:a2a/artifact` on the hooks bus and
;; `common/a2a.clj` subscribes — the codebase's own extension mechanism, used
;; the way it is meant to be.
;; =============================================================================

(defn fire-descriptor!
  "Fire one translated stream descriptor as a hook event, tagging it with
   the agent so handlers can scope to an instance.

   Never throws: this runs on the SSE reader thread, and one bad handler
   must not take down a live subscription."
  [agent {:keys [event data]}]
  (try
    (hooks/fire! event (assoc data :agent agent))
    (catch Throwable t
      (mulog/warn ::descriptor-fire-failed :event event :error (ex-message t)))))

(defn- interrupted-note
  "The line appended to a partial answer when the peer PAUSED rather than
   finished. Says so explicitly — passing a partial answer off as complete
   is how a caller silently acts on half a result."
  [state task-id]
  (str "\n\n[REMOTE TASK PAUSED — state " (name state)
       (when task-id (str ", task-id " task-id))
       ". The peer is awaiting "
       (if (= :auth-required state) "credentials" "further input")
       "; it has NOT finished. Reply via agent-registry$ask on this"
       " instance to continue.]"))

;; =============================================================================
;; Call-chain stamping — the outbound half of the cross-process guard
;; =============================================================================

(def ^:dynamic *inbound-chain*
  "The wire call chain we are currently servicing, when this process is
   acting as an A2A SERVER (bound by the Phase-5 handler).

   The local `proto/*call-chain*` cannot carry it: that holds LOCAL agent
   ids (`:explore-agent/lime-mole`), while the wire chain holds NODE ids.
   Mixing the two vocabularies is exactly the mismatch that made the first
   version of this guard silently never fire. They are kept separate and
   each is checked against its own kind.

   nil for a locally-originated call, which stamps a chain of `[]`."
  nil)

(defn outbound-metadata
  "Metadata for an outbound A2A request.

   Appends THIS node's id to the chain we are servicing (see
   `a2a.core.chain` — the caller appends itself, and entries are node ids,
   not agent ids). Depth comes from `proto/*call-depth*`, so a local
   dispatch chain and a remote one share one budget and a caller cannot
   launder depth by alternating local and remote hops.

   ## Why `dec`

   `*call-depth*` ALREADY counts this dispatch by the time `process` runs:
   both entry points bind an incremented depth first — `tool/call-tool` for
   a `:type :agent` tool (`core/tool.clj`), and `agent-core/ask-agent` for
   `agent-registry$ask`. `stamp-chain` then adds one more for the hop it is
   about to make. Passing `*call-depth*` straight through counts ONE logical
   hop twice, which is not a rounding error: with the default limit of 3, a
   first call arrived already at the limit and every remote dispatch was
   refused. Handing `stamp-chain` the PRE-dispatch depth makes a first hop
   stamp 1, which is what a receiver expects.

   Found by live verification against a real peer, not by any unit test —
   every test set `*call-depth*` by hand and so never saw the layering."
  [context-id]
  (a2a/stamp-chain {:chain      (or *inbound-chain* [])
                    :depth      (max 0 (dec proto/*call-depth*))
                    :context-id context-id}))

;; =============================================================================
;; Streaming ask
;; =============================================================================

(defn ask-streaming
  "Run one remote turn over `message/stream`, firing hook events as frames
   arrive, and block until the task reaches a terminal or interrupted state.

   Returns the same outcome shape as `a2a-client/send-message!`.

   Why block at all, when the whole point of streaming is not to: `ask`
   is synchronous by contract, and the TUI's rendering comes from the
   HOOKS, not the return value. So the caller still gets one answer at the
   end while the user watches it arrive — the same arrangement `acp-agent`
   uses."
  [agent peer text {:keys [context-id metadata timeout-ms]}]
  (let [!acc    (atom (a2a-client/initial-acc))
        !result (promise)
        handle  (a2a-client/stream-message!
                 peer text
                 {:on-event
                  (fn [payload]
                    (let [{:keys [acc events]} (a2a-client/translate @!acc payload)]
                      (reset! !acc acc)
                      (doseq [e events]
                        (fire-descriptor! agent e)
                        ;; Resolve on the FIRST terminal-or-interrupted
                        ;; descriptor. `deliver` on an already-delivered
                        ;; promise is a no-op, so later frames cannot
                        ;; overwrite the outcome.
                        (cond
                          ;; An error frame carries the REASON — a cycle
                          ;; refusal, a depth limit, a server fault. Without
                          ;; this arm the stream just ends and the caller is
                          ;; told "closed before terminal", which throws the
                          ;; actual reason away and makes the refusal
                          ;; impossible to diagnose.
                          (-> e :data :error)
                          (deliver !result {:error (-> e :data :error)})

                          (= :a2a/task-terminal (:event e))
                          (deliver !result {:answer (-> e :data :answer)
                                            :task-id (-> e :data :task-id)
                                            :state   (-> e :data :state)})

                          (contains? #{:a2a/input-required :a2a/auth-required}
                                     (:event e))
                          (deliver !result
                                   {:answer (:text @!acc)
                                    :task-id (-> e :data :task-id)
                                    :state (if (= :a2a/auth-required (:event e))
                                             :auth-required :input-required)})

                          :else nil))))
                  :on-error (fn [e] (deliver !result {:error (:error e)}))
                  ;; A stream that ends without a terminal frame is a
                  ;; truncated turn, not a success. Deliver a sentinel so
                  ;; the caller can say so rather than returning whatever
                  ;; text happened to arrive as if it were the answer.
                  :on-close (fn [] (deliver !result ::closed))}
                 :context-id context-id
                 :metadata metadata)]
    (if (:error handle)
      handle
      (let [r (deref !result (or timeout-ms 600000) ::timeout)]
        (try ((:stop! handle)) (catch Throwable _ nil))
        (cond
          (= ::timeout r)
          {:error (str "A2A stream timed out after " (or timeout-ms 600000) "ms")
           :answer (not-empty (:text @!acc))}

          (= ::closed r)
          {:error "A2A stream closed before the task reached a terminal state"
           :answer (not-empty (:text @!acc))
           :task-id (:task-id @!acc)}

          :else
          (cond-> r
            ;; Prefer the accumulated stream text: the terminal frame's
            ;; own message is often just a status line.
            (and (str/blank? (str (:answer r))) (not (str/blank? (:text @!acc))))
            (assoc :answer (:text @!acc))

            ;; Carry the peer's contextId out of the accumulator. The
            ;; terminal descriptor does not include it, so without this the
            ;; streaming path returns no context and `process` stores nil —
            ;; every follow-up then starts a FRESH remote conversation
            ;; instead of continuing the one the instance is supposed to
            ;; remember. The blocking path never had this gap.
            (:context-id @!acc)
            (assoc :context-id (:context-id @!acc))))))))

;; =============================================================================
;; State construction
;; =============================================================================

(defn make-state
  "Build the `!state` map a RemoteAgent carries.

   The keys are exactly those the existing call sites read:
     :status         — `instance-summary`, `running-instance?`
     :lifecycle      — `lifecycle`, `instance-idle-ms`, `mark-ask-start!`
     :runtime        — `reset-runtime`, `cancelled?`, `get-parent-agent`
     :meta           — `agent-name`, `agent-description`
     :st-memory-init — nil; a remote peer has no local short-term memory
     :behavior-tree  — nil; there is no local tree"
  [{:keys [peer-name skill-id parent-agent description remote-agent-id]}]
  (atom {:status         :idle
         :st-memory-init nil
         :behavior-tree  nil
         :memory-manager nil
         :meta           {:name        (str "a2a$" peer-name "$" skill-id)
                          :description (or description
                                           (str "Remote A2A skill '" skill-id
                                                "' on peer '" peer-name "'"))
                          :remote?     true
                          :peer-name   peer-name
                          :skill-id    skill-id
                          :remote-id   remote-agent-id}
         :runtime        {:parent-agent parent-agent
                          :cancelled?   false
                          :paused?      false}
         :lifecycle      {:owner                 (some-> parent-agent proto/agent-id)
                          :share-parent-session? false
                          :answers               0
                          :created-at            (now-ms)
                          :last-ask-at           nil
                          :last-question         nil}
         ;; A2A conversation continuity: the peer groups related work by
         ;; contextId, so holding on to the one it hands back is what makes a
         ;; follow-up `agent-registry$ask` land in the same remote
         ;; conversation instead of a fresh one.
         :a2a            {:peer-name    peer-name
                          :skill-id     skill-id
                          :context-id   nil
                          :last-task-id nil}}))

;; =============================================================================
;; The record
;; =============================================================================

(defrecord RemoteAgent [agent-id !state !session]

  ;; ---- IAgent ----
  proto/IAgent
  (agent-id [_] agent-id)
  (agent-name [_] (get-in @!state [:meta :name] agent-id))
  (agent-description [_] (get-in @!state [:meta :description] ""))
  (user-id [_] (some-> !session deref :user-id))
  (session-id [_] (some-> !session deref :session-id))
  (defagent-type [_]
    (if-let [ns' (and (keyword? agent-id) (namespace agent-id))]
      (keyword ns')
      agent-id))

  (process [this input _ctx]
    (let [{:keys [peer-name skill-id context-id]} (:a2a @!state)
          peer (a2a-client/get-peer peer-name)]
      (if (nil? peer)
        {:error (str "A2A peer '" peer-name "' is no longer connected"
                     " — reconnect it with a2a$connect.")}
        (let [metadata   (outbound-metadata (some-> !session deref :session-id))
              text       (a2a-client/skill-prompt skill-id input)
              timeout-ms (config/get-config this :a2a-timeout-ms)
              ;; Stream when the peer advertises it AND config allows —
              ;; streaming is what makes the answer render progressively in
              ;; the TUI, via the hooks the translator fires.
              stream?    (and (config/get-config this :a2a-stream?)
                              (a2a/card-supports? (:card peer) :streaming))]
          (mulog/info ::a2a-ask
                      :agent-id agent-id :peer peer-name :skill skill-id
                      :streaming stream? :chain (a2a/describe-chain metadata))
          (let [out (if stream?
                      (ask-streaming this peer text
                                     {:context-id context-id
                                      :metadata metadata
                                      :timeout-ms timeout-ms})
                      (a2a-client/send-message! peer text
                                                :context-id context-id
                                                :metadata metadata
                                                :blocking? true))]
            (if (and (:error out) (str/blank? (str (:answer out))))
              {:error (:error out)}
              (do
                ;; Remember the peer's contextId + task so a follow-up ask
                ;; continues the same remote conversation.
                (swap! !state update :a2a merge
                       {:context-id (or (:context-id out) context-id)
                        :last-task-id (:task-id out)})
                (cond-> {:answer (:answer out)
                         :result {:state    (:state out)
                                  :task-id  (:task-id out)
                                  :remote   true
                                  :streamed stream?
                                  :peer     peer-name
                                  :skill    skill-id}}
                  ;; A partial answer plus an error (a truncated stream) is
                  ;; reported as BOTH — dropping either would misrepresent
                  ;; the turn.
                  (:error out) (assoc :error (:error out))

                  ;; An interrupted task is NOT finished — say so plainly
                  ;; rather than passing a partial answer off as complete.
                  (a2a/interrupted? (:state out))
                  (update :answer str (interrupted-note (:state out)
                                                        (:task-id out)))))))))))

  (get-tools [_] [])
  (get-state [_] @!state)

  ;; ---- IAgentLifecycle ----
  proto/IAgentLifecycle
  (start-agent [this]
    (swap! !state assoc :status :idle)
    (agent-core/register-agent this)
    (mulog/info ::remote-agent-started :agent-id agent-id)
    this)
  (stop-agent [this]
    (runtime/cancel-run !state)
    (swap! !state assoc :status :stopped)
    ;; MUST unregister, exactly as the local Agent's stop-agent does.
    ;; Without this, `close-instance!` flips the status but leaves the
    ;; instance in the registry forever: agent-registry$close reports
    ;; success, agent-registry$list keeps showing it, and it still counts
    ;; against the per-session LRU cap. Caught by a2a_registry_test.
    (agent-core/unregister-agent agent-id)
    (mulog/info ::remote-agent-stopped :agent-id agent-id)
    this)
  (agent-running? [_]
    (boolean (#{:idle :running :paused :cancelled} (:status @!state))))
  (clone-agent [this] (proto/clone-agent this {}))
  (clone-agent [_this _opts]
    ;; Cloning snapshots local BT + st-memory state, of which a remote peer
    ;; has none. Reconnecting the same skill produces an equivalent
    ;; instance, so a silent half-clone would be worse than saying no.
    (throw (ex-info "RemoteAgent cannot be cloned — a remote peer holds no local state to snapshot; dispatch the skill again instead."
                    {:agent-id agent-id})))

  ;; ---- IAgentState ----
  ;; nil, not a fabricated local shadow. See the ns docstring.
  proto/IAgentState
  (get-state-value [_ path] (get-in @!state path))
  (set-state-value! [_ path value] (swap! !state assoc-in path value))
  (get-bt [_] nil)
  (get-bt-context [_] nil)
  (get-st-memory-init [_] nil)

  ;; ---- IAgentBTIntegration ----
  ;; Called by BT nodes via Java interop. A RemoteAgent never runs a tree,
  ;; but `agent.core.agent/ask` reaches `.get-bt-st-memory` on EVERY ask
  ;; (for :terminated-by and :next-user-prompt), so these must exist and be
  ;; nil-safe or every remote ask would throw.
  proto/IAgentBTIntegration
  (update-session-data [_ data]
    (when @!session
      (swap! !session session/update-data data)))
  (check-run-cancelled? [_] (runtime/cancelled? !state))
  (check-run-paused? [_] (runtime/paused? !state))
  (await-resume [_] (runtime/wait-if-paused !state))
  (await-resume-task [_] (runtime/await-resume-task !state))
  (apply-resume-note! [_] nil)
  (create-action-promise [_ action-id] (runtime/create-action-promise !state action-id))
  (get-action-permission [_ action-id] (runtime/get-action-permission !state action-id))
  (set-action-permission [_ action-id value] (runtime/set-action-permission !state action-id value))
  (get-bt-st-memory [_] nil)

  ;; ---- IAgentMemoryAccess ----
  proto/IAgentMemoryAccess
  (get-memory-manager [_] nil)

  ;; ---- java.io.Closeable ----
  java.io.Closeable
  (close [this]
    ;; Fire the cascade hook so any subagents this instance owned are
    ;; collected, exactly as a local agent's close does. There is no
    ;; sandbox, watch or capture pipeline to release — and no remote call:
    ;; closing our handle does not cancel work the peer is doing. A live
    ;; remote task must be cancelled explicitly (task$cancel / a2a cancel).
    (hooks/fire! :agent.instance/closed {:agent this})
    (proto/stop-agent this)
    (mulog/info ::remote-agent-closed :agent-id agent-id)))

;; =============================================================================
;; Construction
;; =============================================================================

(defn remote-agent?
  "True when `agent` is a RemoteAgent (an A2A peer rather than a local
   instance). Used by `agent-registry$list`/`$detail` to report `:kind`."
  [agent]
  (instance? RemoteAgent agent))

(defn create
  "Build (but do not register) a RemoteAgent for one remote skill.

   `parent-agent` is required in practice — a remote peer is always a
   subagent — and `!session` is shared with it, so turn counters and the
   message log behave exactly as for a local dispatch."
  [{:keys [agent-id peer-name skill-id parent-agent !session description
           remote-agent-id]}]
  (->RemoteAgent agent-id
                 (make-state {:peer-name peer-name
                              :skill-id skill-id
                              :parent-agent parent-agent
                              :description description
                              :remote-agent-id remote-agent-id})
                 !session))

(defn describe
  "Redaction-safe summary of a remote instance, for `agent-registry$detail`."
  [agent]
  (let [{:keys [a2a meta]} @(:!state agent)]
    {:kind       :remote
     :peer       (:peer-name a2a)
     :skill      (:skill-id a2a)
     :remote-id  (:remote-id meta)
     :context-id (:context-id a2a)
     :last-task-id (:last-task-id a2a)}))

(defn context-id
  "The A2A contextId this instance is continuing, or nil."
  [agent]
  (get-in @(:!state agent) [:a2a :context-id]))

(defn last-task-id
  "The most recent remote task id, or nil."
  [agent]
  (get-in @(:!state agent) [:a2a :last-task-id]))

;; =============================================================================
;; Depth guard shared with the local path
;; =============================================================================

(defn depth-exceeded?
  "True when dispatching one more hop would exceed `:max-agent-call-depth`.

   The SAME limit the local subagent path enforces
   (`agent.core.agent/ask-agent`) — a remote hop is a hop, and giving
   remote calls their own budget would let a chain launder depth by
   alternating local and remote."
  [agent]
  (>= proto/*call-depth* (or (config/get-config agent :max-agent-call-depth) 3)))

(defn cycle-target?
  "True when dispatching `remote-id` would re-enter a remote skill already
   on the LOCAL dispatch stack.

   This is the near-side half of the guard, and it is a different check
   from the wire one: it compares remote-skill tokens
   (`<endpoint>#<skill>`) against `proto/*call-chain*`, catching a local
   agent that recursively dispatches the same remote skill within this
   process. The wire guard (`a2a/check-chain`) works on NODE ids and
   catches recursion that leaves and comes back. Neither subsumes the
   other."
  [remote-id]
  (let [target (str remote-id)]
    (boolean (some #(= target (if (keyword? %) (subs (str %) 1) (str %)))
                   proto/*call-chain*))))

(defn describe-outbound-chain
  "The local dispatch chain that WOULD result from calling `remote-id`."
  [remote-id]
  (str/join " -> " (conj (mapv #(if (keyword? %) (subs (str %) 1) (str %))
                               proto/*call-chain*)
                         (str remote-id))))
