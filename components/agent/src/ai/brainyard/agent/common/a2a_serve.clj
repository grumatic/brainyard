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

(defn make-ask-fn
  "Build the `:ask-fn` the server calls for every inbound turn.

   Responsibilities, in order:
     1. resolve the addressed skill against the ALLOW-LIST (an unexposed
        agent is not reachable even if the caller names it)
     2. dispatch a fresh local agent instance for the turn
     3. bind the inbound call chain so any onward hop inherits it
     4. stream chunks back through `:on-chunk`
     5. reclaim the instance

   A fresh instance per turn, rather than a long-lived one keyed by
   `contextId`: a remote caller must not be able to accumulate unbounded
   server-side state by inventing context ids."
  [{:keys [allow session-id user-id]}]
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
              sid    (or context-id session-id (str "a2a-" (System/currentTimeMillis)))
              inst   (try
                       (agent-core/setup-agent-by-id
                        (keyword skill)
                        :agent-session {:user-id (or user-id "a2a-remote")
                                        :session-id sid})
                       (catch Throwable t
                         (mulog/error ::serve-instantiate-failed
                                      :skill skill :exception t)
                         nil))]
          (if (nil? inst)
            {:error (str "could not instantiate skill: " skill)}
            (let [hid (when on-chunk
                        (scoped-chunk-hook! (proto/agent-id inst) on-chunk))]
              (try
                ;; The inbound chain becomes the base for any onward hop
                ;; this agent makes, so a cycle that leaves and returns is
                ;; still detected downstream.
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
                  (when hid (hooks/unregister-hook! :agent.dspy-action/chunk hid))
                  (try (agent-core/close-instance! (proto/agent-id inst))
                       (catch Throwable _ nil)))))))))))

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
   has no live agent yet), in which case the schema defaults apply."
  [agent {:keys [url]}]
  (let [allow (or (config/get-config agent :a2a-expose-skills) [])
        token (config/get-config agent :a2a-serve-token)]
    (merge {:card-fn    (fn [] (build-card {:url url :allow allow}))
            :ask-fn     (make-ask-fn {:allow allow})
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
