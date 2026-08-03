;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.a2a-serve
  "Serving side: build the Agent Card from the local roster and the
   `service` map `components/a2a-server` needs, then run the listener.

   `a2a-server` is pure transport and knows nothing about agents; this is
   the namespace that closes that gap. Everything agent-shaped —
   the skill allow-list, dispatching an ask, binding the inbound call chain
   — lives here.

   ## The allow-list is the security boundary

   `:a2a-expose-skills` defaults to `[]`, so a freshly-enabled server
   exposes NOTHING until an operator names an agent. There is deliberately
   no deny-list mode: an allow-list that defaults to \"everything
   except…\" is how an internal agent leaks. See
   docs/design/a2a-design.md §8."
  (:require [clojure.string :as str]
            [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-server.interface :as a2a-server]
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.remote-agent :as remote]
            [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.agent.task.manager :as task-mgr]
            [ai.brainyard.agent.task.protocol :as task-proto]
            [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.util.interface :as util]))

(defn- id-str [id] (if (keyword? id) (util/kw->str id) (str id)))

;; =============================================================================
;; Card generation
;; =============================================================================

(defn exposable-agents
  "The `!tool-defs` entries of `:type :agent` named in `allow`.

   Matching is on the string form so an operator can write plain names in
   config (`[\"explore-agent\"]`) rather than having to know that registry
   ids are keywords."
  [allow]
  (let [want (set (map #(id-str %) allow))]
    (->> @tool/!tool-defs
         (filter (fn [[id td]]
                   (and (= :agent (:type td))
                        (contains? want (id-str id)))))
         (sort-by (comp id-str key))
         vec)))

(defn build-card
  "Build this process's Agent Card from the exposed roster."
  [{:keys [name description url version allow]}]
  (a2a/build-card
   {:name        (or name "brainyard")
    :description (or description
                     "A brainyard agent runtime exposed over the Agent2Agent protocol")
    :url         url
    :version     version
    :capabilities {:streaming true :pushNotifications false}
    :default-input-modes  ["text/plain"]
    :default-output-modes ["text/plain"]
    :skills (mapv (fn [[id td]]
                    (a2a/build-skill
                     {:id id
                      :name (or (get-in td [:meta :name]) (id-str id))
                      :description (get-in td [:meta :description])
                      :input-modes ["text/plain"]
                      :output-modes ["text/plain"]}))
                  (exposable-agents allow))}))

;; =============================================================================
;; Ask
;; =============================================================================

(defn- resolve-skill-id
  "The registry id an inbound request is addressing.

   A2A has no `skill` field on the wire, so the client-side convention is a
   `[skill: <id>]` prefix line (see `a2a-client/skill-prompt`). We parse it
   back off here and fall back to the single exposed skill when there is
   exactly one — a lone-skill agent should not require the ceremony."
  [text allow]
  (let [m     (re-find #"(?m)^\[skill:\s*([^\]]+)\]" (str text))
        named (some-> m second str/trim not-empty)
        ids   (map (comp id-str first) (exposable-agents allow))]
    (cond
      ;; An EXPLICIT name always wins, even when it names something we do
      ;; not expose. Validity is the caller's check. Letting the
      ;; single-skill fallback override a named skill would silently serve
      ;; a DIFFERENT agent than the one asked for — the caller would get a
      ;; confident answer from the wrong place and have no way to tell.
      named             named
      ;; Only when nothing was named: a lone exposed skill needs no
      ;; ceremony.
      (= 1 (count ids)) (first ids)
      :else             nil)))

(defn- strip-skill-prefix [text]
  (str/replace (str text) #"(?m)^\[skill:[^\]]*\]\n?" ""))

(defn- scoped-chunk-hook!
  "Forward one agent instance's streamed text to `on-chunk`, for the
   duration of a turn.

   Scoped with `:match` on the agent identity so a concurrent turn on
   another instance cannot bleed into this stream — the server is
   multi-threaded and two clients can be mid-turn at once."
  [agent-id on-chunk]
  (let [hid (keyword (str "a2a-serve-chunk-" (id-str agent-id)))]
    (hooks/register-hook!
     :agent.dspy-action/chunk hid
     (fn [{:keys [chunk accumulated]}]
       (try (on-chunk chunk accumulated) (catch Throwable _ nil)))
     :match (fn [{:keys [agent]}]
              (= agent-id (some-> agent proto/agent-id)))
     :source ::serve)
    hid))

;; =============================================================================
;; Context registry — one warm instance per `contextId`
;; =============================================================================
;;
;; A2A's `contextId` names a CONVERSATION, so a follow-up on the same id has
;; to reach the same agent; a fresh instance per turn makes every remote
;; caller a first-time caller, and for a subprocess-backed skill (an exposed
;; acp-agent is one Claude Code session per context) it also throws away an
;; expensive warm backend.
;;
;; The original fresh-per-turn rule was not arbitrary, though: a remote caller
;; invents contextIds freely, so keying live state on them is an invitation to
;; accumulate. Reuse is therefore BOUNDED, never open-ended:
;;
;;   - `:a2a-max-contexts` caps how many contexts stay warm; past it the
;;     least-recently-used IDLE context is evicted and its instance closed.
;;     Set to 0 to restore fresh-per-turn exactly.
;;   - `:a2a-context-ttl-ms` sweeps contexts idle longer than the TTL.
;;   - a context whose turn FAILED is dropped rather than reused, so a wedged
;;     backend cannot poison every later turn on that id.
;;   - a second concurrent turn on one context is REFUSED, not queued —
;;     the same stance `agent-registry$ask` takes on a `:running` instance.
;;     Queueing would let a caller pin instances against the cap by holding
;;     turns open.

(defonce ^:private !contexts
  ;; context-id(string) -> {:agent-id kw|nil  — nil only between reserve and bind
  ;;                        :skill    string  — what this context is bound to
  ;;                        :last-used-ms long — END of the last turn (TTL clock)
  ;;                        :seq      long    — recency rank (LRU order)
  ;;                        :turns    long
  ;;                        :in-use?  boolean — a turn is in flight}
  (atom {}))

(defonce ^:private !tick
  ;; Recency counter. LRU order must NOT come off the wall clock:
  ;; System/currentTimeMillis is coarse enough that several turns routinely
  ;; share a millisecond, and tied keys make the eviction victim whichever
  ;; way an unordered map happened to seq — the wrong context dies, at random.
  ;; The TTL still uses the clock, because that one really is about elapsed
  ;; time.
  (atom 0))

(defn- now-ms ^long [] (System/currentTimeMillis))
(defn- next-tick ^long [] (swap! !tick inc))

(defn context-count
  "How many A2A contexts are currently warm."
  []
  (count @!contexts))

(defn describe-contexts
  "Redaction-safe snapshot of the warm contexts, for diagnostics."
  []
  (mapv (fn [[cid e]]
          {:context-id cid
           :skill      (:skill e)
           :agent-id   (some-> (:agent-id e) id-str)
           :turns      (:turns e 0)
           :idle-ms    (- (now-ms) (:last-used-ms e 0))
           :in-use?    (boolean (:in-use? e))})
        (sort-by key @!contexts)))

(defn- close-instance-quietly! [agent-id]
  (when agent-id
    (try (agent-core/close-instance! agent-id) (catch Throwable _ nil))))

(defn reset-contexts!
  "Close every warm context and forget it. Session teardown and tests."
  []
  (let [[old _] (reset-vals! !contexts {})]
    (doseq [[cid e] old]
      (close-instance-quietly! (:agent-id e))
      (mulog/info ::context-closed :context-id cid :reason :reset))
    (count old)))

(defn- sweep!
  "Drop contexts idle past `ttl-ms`, then evict least-recently-used idle
   contexts until at most `cap` remain.

   Returns the evicted `[context-id agent-id reason]` triples — CLOSING them
   is the caller's job, because side effects must not run inside a `swap!`,
   which is free to retry its function."
  [ttl-ms cap]
  (let [now (now-ms)
        expired? (fn [[_ e]]
                   (and (not (:in-use? e))
                        (pos? (long ttl-ms))
                        (> (- now (:last-used-ms e 0)) (long ttl-ms))))
        [old new] (swap-vals!
                   !contexts
                   (fn [m]
                     (let [alive   (into {} (remove expired? m))
                           ;; An in-flight turn is never a victim: its caller
                           ;; is waiting on the very instance we would close.
                           idle    (->> alive
                                        (remove (comp :in-use? val))
                                        (sort-by (comp #(:seq % 0) val)))
                           over    (max 0 (- (count alive) (long cap)))
                           victims (map key (take over idle))]
                       (apply dissoc alive victims))))
        gone (remove (set (keys new)) (keys old))]
    (mapv (fn [cid]
            [cid (:agent-id (get old cid))
             (if (expired? [cid (get old cid)]) :ttl :lru)])
          gone)))

(defn- reap!
  "Close the instances behind swept contexts."
  [swept]
  (doseq [[cid agent-id reason] swept]
    (close-instance-quietly! agent-id)
    (mulog/info ::context-closed :context-id cid :reason reason)))

(defn- claim!
  "Atomically take the turn slot on `ctx-id` for `skill`.

   Returns `{:mode :reuse|:fresh|:busy :agent-id … :replaced …}`:
     :reuse — `:agent-id` is a live instance to ask again
     :fresh — the slot is reserved; the caller creates an instance and
              `bind!`s it (or `abandon!`s the reservation)
     :busy  — another turn is already in flight on this context

   `:replaced` is a displaced instance the caller must close: a contextId
   re-addressed to a DIFFERENT skill is a different conversation, so the old
   instance is retired rather than handed someone else's history."
  [ctx-id skill]
  ;; Both stamps are taken BEFORE the swap: a `swap!` function can be retried,
  ;; so it must stay free of side effects.
  (let [reserve {:agent-id nil :skill skill :last-used-ms (now-ms)
                 :seq (next-tick) :turns 0 :in-use? true}
        [old _] (swap-vals!
                 !contexts
                 (fn [m]
                   (let [e (get m ctx-id)]
                     (cond
                       (:in-use? e)                       m
                       (and e (= skill (:skill e))
                            (:agent-id e))                (assoc-in m [ctx-id :in-use?] true)
                       :else                              (assoc m ctx-id reserve)))))
        prev (get old ctx-id)]
    (cond
      (:in-use? prev)
      {:mode :busy}

      (and prev (= skill (:skill prev)) (:agent-id prev))
      ;; The instance can have been closed out from under us (cascade,
      ;; session teardown). A stale id would surface as a confusing ask
      ;; failure, so verify liveness and fall back to a fresh one.
      (if (agent-core/get-agent (:agent-id prev))
        {:mode :reuse :agent-id (:agent-id prev)}
        (do (swap! !contexts assoc ctx-id reserve)
            {:mode :fresh}))

      :else
      {:mode :fresh :replaced (when prev (:agent-id prev))})))

(defn- bind!
  "Attach a freshly created instance to its reserved context slot."
  [ctx-id agent-id]
  (swap! !contexts (fn [m]
                     (if (contains? m ctx-id)
                       (assoc-in m [ctx-id :agent-id] agent-id)
                       m))))

(defn- release!
  "End the turn on `ctx-id`. A successful turn stamps the idle clock the TTL
   and LRU order read from; a failed one is dropped and its instance closed,
   so the next turn on that id starts clean instead of inheriting a wedged
   backend."
  [ctx-id ok?]
  (if ok?
    (let [at   (now-ms)
          tick (next-tick)]
      (swap! !contexts (fn [m]
                         (if-let [e (get m ctx-id)]
                           (assoc m ctx-id (assoc e
                                                  :in-use?      false
                                                  :last-used-ms at
                                                  :seq          tick
                                                  :turns        (inc (:turns e 0))))
                           m))))
    (let [[old _] (swap-vals! !contexts dissoc ctx-id)]
      (close-instance-quietly! (:agent-id (get old ctx-id)))
      (mulog/info ::context-closed :context-id ctx-id :reason :turn-failed)))
  nil)

;; =============================================================================
;; Ask
;; =============================================================================

(defn- new-instance
  "Create an instance of `skill` bound to agent-session `sid`, or nil."
  [skill sid user-id]
  (try
    (agent-core/setup-agent-by-id
     (keyword skill)
     :agent-session {:user-id (or user-id "a2a-remote") :session-id sid})
    (catch Throwable t
      (mulog/error ::serve-instantiate-failed :skill skill :exception t)
      nil)))

(defn- run-turn!
  "Ask `inst` and shape the A2A result. Never throws.

   Binds the inbound call chain so any onward hop inherits it — a cycle that
   leaves and returns is still detected downstream — and scopes the chunk
   hook to this instance for the duration."
  [inst prompt skill metadata on-chunk]
  (let [hid (when on-chunk (scoped-chunk-hook! (proto/agent-id inst) on-chunk))]
    (try
      (binding [remote/*inbound-chain* (a2a/inbound-chain metadata)
                proto/*call-depth*     (a2a/read-depth metadata)]
        (mulog/info ::serve-ask :skill skill
                    :chain (a2a/describe-chain metadata))
        (let [r (agent-core/ask inst prompt)]
          (if (:error r)
            {:error (:error r)}
            {:answer     (:answer r)
             :context-id (proto/session-id inst)
             :state      :completed})))
      (catch Throwable t
        (mulog/error ::serve-ask-failed :skill skill :exception t)
        {:error (ex-message t)})
      (finally
        (when hid (hooks/unregister-hook! :agent.dspy-action/chunk hid))))))

(defn make-ask-fn
  "Build the `:ask-fn` the server calls for every inbound turn.

   Responsibilities, in order:
     1. resolve the addressed skill against the ALLOW-LIST (an unexposed
        agent is not reachable even if the caller names it)
     2. reuse the instance warm on this `contextId`, or dispatch one
     3. bind the inbound call chain so any onward hop inherits it
     4. stream chunks back through `:on-chunk`
     5. leave the instance warm for the next turn — bounded by
        `:a2a-max-contexts` / `:a2a-context-ttl-ms` (see the context registry
        above), or reclaimed immediately when the cap is 0"
  [{:keys [allow session-id user-id agent]}]
  (fn [{:keys [text context-id metadata on-chunk]}]
    (let [skill (resolve-skill-id text allow)
          ids   (set (map (comp id-str first) (exposable-agents allow)))]
      (cond
        (str/blank? (str skill))
        {:error (str "no skill addressed and more than one is exposed; prefix the "
                     "message with [skill: <id>]. Exposed: " (str/join ", " ids))}

        (not (contains? ids skill))
        ;; Do NOT distinguish "no such agent" from "not exposed" — that
        ;; would let a caller enumerate the local roster.
        {:error (str "no such skill: " skill)}

        :else
        (let [prompt (strip-skill-prefix text)
              sid    (or context-id session-id (str "a2a-" (now-ms)))
              cap    (or (config/get-config agent :a2a-max-contexts) 0)
              ttl    (or (config/get-config agent :a2a-context-ttl-ms) 0)]
          (if-not (pos? (long cap))
            ;; Reuse disabled: dispatch, answer, reclaim. Byte-for-byte the
            ;; behaviour that shipped before the context registry existed.
            (if-let [inst (new-instance skill sid user-id)]
              (try (run-turn! inst prompt skill metadata on-chunk)
                   (finally (close-instance-quietly! (proto/agent-id inst))))
              {:error (str "could not instantiate skill: " skill)})

            (let [{:keys [mode agent-id replaced]} (claim! sid skill)]
              (close-instance-quietly! replaced)
              (case mode
                :busy
                {:error (str "context " sid " already has a turn in flight;"
                             " wait for it to finish before asking again")}

                :reuse
                (let [inst (agent-core/get-agent agent-id)
                      r    (run-turn! inst prompt skill metadata on-chunk)]
                  (release! sid (not (:error r)))
                  r)

                :fresh
                ;; Sweep BEFORE creating, so the cap counts this context in.
                ;; The reservation is already in the map and marked in-use, so
                ;; it cannot evict itself.
                (do
                  (reap! (sweep! ttl cap))
                  (if-let [inst (new-instance skill sid user-id)]
                    (do
                      (bind! sid (proto/agent-id inst))
                      (let [r (run-turn! inst prompt skill metadata on-chunk)]
                        (release! sid (not (:error r)))
                        r))
                    (do (swap! !contexts dissoc sid)
                        {:error (str "could not instantiate skill: " skill)})))))))))))

;; =============================================================================
;; Task surface
;; =============================================================================

(defn- ->a2a-task
  "Render a local task as an A2A Task object."
  [t]
  (when t
    {:id (id-str (:id t))
     :kind "task"
     :status {:state (a2a/kw->state (:status t))}}))

(defn make-task-fns
  "`:get-task-fn` / `:cancel-fn` over the local task manager.

   Both answer nil for an unknown id, which the handler turns into the
   single indistinguishable not-found response."
  []
  {:get-task-fn (fn [task-id]
                  (some-> (task-mgr/get-default-manager)
                          (task-proto/get-task (keyword task-id))
                          ->a2a-task))
   :cancel-fn   (fn [task-id]
                  (some-> (task-mgr/get-default-manager)
                          (task-proto/cancel-task (keyword task-id))
                          ->a2a-task))})

;; =============================================================================
;; Service assembly
;; =============================================================================

(defn build-service
  "Assemble the `service` map for `a2a-server/start!`.

   Reads `:a2a-expose-skills`, `:a2a-serve-token` and
   `:max-agent-call-depth` from config; `agent` may be nil (the CLI path
   has no live agent yet), in which case the schema defaults apply. It is
   also threaded into the ask-fn, which resolves `:a2a-max-contexts` and
   `:a2a-context-ttl-ms` per turn so an operator can retune context reuse
   without restarting the listener."
  [agent {:keys [url]}]
  (let [allow (or (config/get-config agent :a2a-expose-skills) [])
        token (config/get-config agent :a2a-serve-token)]
    (merge {:card-fn    (fn [] (build-card {:url url :allow allow}))
            :ask-fn     (make-ask-fn {:allow allow :agent agent})
            :auth-token token
            :max-depth  (or (config/get-config agent :max-agent-call-depth) 3)}
           (make-task-fns))))

(defn serve!
  "Start the A2A server for this process.

   Returns the server handle, or `{:error …}`. Refuses when A2A is
   disabled, when no token is configured, or when the allow-list is empty —
   a server exposing nothing is a configuration mistake, not a useful
   default, and saying so beats silently accepting requests it can only
   refuse."
  [agent {:keys [host port] :as opts}]
  (let [host  (or host (config/get-config agent :a2a-serve-host) "127.0.0.1")
        port  (or port (config/get-config agent :a2a-serve-port) 41241)
        allow (or (config/get-config agent :a2a-expose-skills) [])]
    (cond
      (not (config/get-config agent :enable-a2a))
      {:error (str "A2A is disabled. Set :enable-a2a true (env BY_ENABLE_A2A=1) "
                   "before serving.")}

      (empty? allow)
      {:error (str "no skills exposed — set :a2a-expose-skills (env "
                   "BY_A2A_EXPOSE_SKILLS) to the agent ids you want reachable, "
                   "e.g. [\"explore-agent\"]. Nothing is exposed by default.")}

      :else
      (let [url     (str "http://" host ":" port a2a-server/RPC_PATH)
            service (build-service agent (assoc opts :url url))
            result  (a2a-server/start! service {:host host :port port})]
        (when-not (:error result)
          (mulog/info ::serving :url (:url result)
                      :skills (mapv (comp id-str first) (exposable-agents allow))))
        result))))
